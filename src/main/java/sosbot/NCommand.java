package sosbot;

import discord4j.core.object.entity.channel.Channel;
import discord4j.core.object.entity.channel.MessageChannel;
import discord4j.core.object.entity.channel.TextChannel;
import lombok.extern.slf4j.Slf4j;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;
import reactor.util.annotation.NonNull;
import reactor.util.context.Context;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.LongConsumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
abstract  class NCommand<T> {
    static HashMap<String, List<NCommand<?>>> ChannelCmds=new HashMap<>();
    BaseData bd=null;
    Supplier<NCommand<T>> factory=this::me;
    NCommand<T> me() {return this;}
    MonoSink<T> sink ;

    Consumer<MonoSink<T>> internalCb=(s)->{
        MsgContext context =s.currentContext().get(MsgContext.KEY);
        register(context);
        sink=new SinkDecorator<>(s);
    };
    public boolean isBaseCommand() {return bd!= null;}
    // syntax of the command
    public String getSyntax() {return bd!= null ? bd.syntax:"";}
    // Help text describing the syntax
    public String getHelpText(){ return bd!= null ? bd.helpText:"";}
    // true if only R4 can perform this command
    public boolean isR4Only() { return bd != null && bd.isR4Only;}
    public NCommand<T> getOne() { return factory==null? null:factory.get();}
    protected NCommand() {}
    // stateful base commands need to implement this constructor
    protected NCommand(BaseData bdata, Supplier<NCommand<T>> factory) { bd=bdata; this.factory=factory;}
    // ok for stateless commands
    protected NCommand(BaseData bdata) { bd=bdata; this.factory=this::me;}
    

    Mono<T> mono(MsgContext c) {
        return Mono.create(internalCb).subscriberContext((context)->context.put(MsgContext.KEY,c));
    }


    //callback to handle messages
    abstract Optional<T> onMessage(MsgContext ctxt);


    //determines whether an incoming message is matching this command or not
    abstract boolean matches(String content);

    void execute(MsgContext c) {
        try {
            Optional<T> ret=onMessage(c);
            if(ret.isPresent()) sink.success(ret.get());
            else sink.success();
        } catch(RecoverableError r) {
            c.send(r.getMessage());
        }   catch(Util.UnrecoverableError r) {
            c.send(r.getMessage());
            c.removeFollowup();
            sink.error(r);
        } catch(Throwable t) {
            c.send("Unexpected error");
            c.removeFollowup();
            sink.error(t);
        } finally {
            c.curServer.removeFollowupCmd(c.channel,c.participant);
        }

    }
    //used to register the onMessage callback in the discord event loop when mono is subscribed
    void register(MsgContext c) {
        c.curServer.setFollowUpNCmd(c.channel,c.participant,this);
        log.info("register "+this);
    }


    static void findAndExec(MsgContext c) {

        Server.ChannelPartKey k=new Server.ChannelPartKey(c.channel,c.participant);
        followupOld followUp= c.curServer.getFollowUpCmd(k);
        if(followUp!= null && followUp.cmd.matches(c.content)) {
            followUp.cmd.execute(c.content,c.participant,c.channel,c.curServer);
            return;
        }
        followup nFollowUp = c.curServer.getNFollowUpCmd(k);
        if(nFollowUp!= null) {
            nFollowUp.cmd.execute(c);
            return;
        }
        if(c.channel.getType() == Channel.Type.GUILD_TEXT) {
            TextChannel tch = (TextChannel) c.channel;
            List<NCommand<?>> baseCmds = ChannelCmds.get(tch.getName());
            if (baseCmds != null && baseCmds.size()>0) {
                for (NCommand<?> cmd : baseCmds) {
                    if (cmd.matches(c.content)) {
                        if(cmd.isR4Only() && !c.participant.isR4()) {
                            c.send("Sorry, the \"" + cmd.getSyntax()+"\" command is for R4s only.");
                            return;
                        }
                        NCommand<?> cloned=cmd.getOne();
                        cloned.mono(c).subscribe((ret)->{},(thr)->{
                            if(thr instanceof Util.UnrecoverableError) {
                                c.send(thr.getMessage());
                                c.curServer.removeFollowupNCmd(c.channel,c.participant);
                            } else if(thr instanceof RecoverableError) {
                                c.send(thr.getMessage());
                            } else {

                                c.send("Unexpected error...");
                                c.curServer.removeFollowupNCmd(c.channel,c.participant);
                            }
                            log.error("Mono error",thr);
                        });
                        cloned.execute(c);
                        return;
                    }
                }
            } else {//todo remove when everything migrated to Ncommand
                List<Command> oldBaseCmds = Command.ChannelCmds.get(tch.getName());
                if (oldBaseCmds != null && oldBaseCmds.size()>0) {
                    for (Command cmd : oldBaseCmds)
                        if (cmd.matches(c.content)) {
                            if(cmd.isR4Only() && !c.participant.isR4()) {
                                c.send("Sorry, the \"" + cmd.getSyntax()+"\" command is for R4s only.");
                                return;
                            }
                            cmd.getOne().execute(c.content,c.participant,c.channel, c.curServer);
                            return;
                        }
                }
            }
            //log.info("No matching command for: " + c.content);
        }
    }
    static void registerCmds(String channelName,List<NCommand<?>> l) {ChannelCmds.put(channelName,l);}

    static abstract class SimpleCommand<T>  extends NCommand<T> {
        String theCmd;
        SimpleCommand(String command,BaseData bdata, Supplier<NCommand<T>> factory) {
              super(bdata,factory);
              theCmd=command;
        }
        SimpleCommand(String command,BaseData bdata) {
            super(bdata);
            theCmd=command;
        }
        SimpleCommand(String command) {
            theCmd=command;
        }
        @Override
        boolean matches(String content) {
            return content.trim().equalsIgnoreCase(theCmd);
        }
    }
    static class followup {
        NCommand<?> cmd;
        int retry =0;
        followup(NCommand<?> cmd) {this.cmd=cmd;}
    }
    static class followupOld {
        Command cmd;
        int retry =0;
        followupOld(Command cmd) {this.cmd=cmd;}
    }


    static  abstract  class NRegexCommand<T>  extends NCommand<T> {
        Pattern regex;
        NRegexCommand(String regex) {
            this.regex=Pattern.compile(regex);
        }
        NRegexCommand(String regex,BaseData bd) {
            super(bd);this.regex=Pattern.compile(regex);
        }
        NRegexCommand(String regex,BaseData bd,Supplier<NCommand<T>> factory) {
            super(bd,factory);
            this.regex=Pattern.compile(regex);
        }
        @Override
        Optional<T> onMessage(MsgContext c) {
            return onMessage(regex.matcher(c.content.trim()),c);
        }

        abstract Optional<T> onMessage(Matcher ma, MsgContext c);

        @Override
        boolean matches(String content) {
            return regex.matcher(content).matches();
        }
    }

    static class readIntCmd extends NCommand<Integer> {
        readIntCmd() {super();}
        @Override
        boolean matches(String content) { return content.trim().matches("\\d+"); }
        @Override
        Optional<Integer> onMessage(MsgContext ctxt) {
            try {
                int read=Integer.parseInt(ctxt.content.trim());
                log.info("readint "+read);
                return Optional.of(read);
            } catch (NumberFormatException e) {
                throw new RecoverableError("Incorrect number format "+ctxt.content);
            }
        }
    }
    static class readFloatCmd extends NCommand<Float> {
        @Override
        boolean matches(String content) { return content.trim().matches("\\d+(.\\d+)?"); }
        @Override
        Optional<Float> onMessage(MsgContext ctxt) {
            try {
                float read=Float.parseFloat(ctxt.content.trim());
                log.info("readfloat "+read);
                return Optional.of(read);
            } catch (NumberFormatException e) {
                throw new RecoverableError("Incorrect number format "+ctxt.content);
            }
        }
    }
    static class yesNoCmd extends NCommand<Boolean> {
        @Override
        Optional<Boolean> onMessage(MsgContext ctxt) {
            return Optional.of(ctxt.content.trim().equalsIgnoreCase("yes"));
        }

        @Override
        boolean matches(String content) { return true; }
    }

    static class readDuration extends NRegexCommand<Duration> {
        readDuration() {
            super("((\\d)d)*\\s*(\\d{1,2}):(\\d{2})(:(\\d{2}))*");
        }


        @Override
        Optional<Duration> onMessage(Matcher ma,MsgContext c) {
            if(!ma.matches()) {
                throw new RecoverableError("incorrect duration format <"+c.content+"> expecting somrthing like 1d 23:30:24");
            }
            int days=0;
            if(ma.group(2)!= null) {
                days=Integer.parseInt(ma.group(2));
            }
            int hours = Integer.parseInt(ma.group(3));
            if(hours<0 || hours>23) {
                throw new RecoverableError("incorrect hour nb <"+ma.group(3)+">");
            }
            int minutes = Integer.parseInt(ma.group(4));
            if(minutes<0 || minutes>59) {
                 throw new RecoverableError("incorrect minute nb <"+ma.group(4)+">");
            }
            int seconds=0;
            if (ma.group(6) != null) {
                seconds = Integer.parseInt(ma.group(6));
                if(seconds<0 || seconds>59) {
                    throw new RecoverableError("incorrect seconds nb <"+ma.group(6)+">");
                }
            }
            return Optional.of(Duration.ofSeconds((days*24L+hours)*3600+minutes*60+seconds));
        }
    }

    static class  SinkDecorator<T> implements MonoSink<T> {

        MonoSink<T> sink;
        SinkDecorator(MonoSink<T> ms) {sink=ms;}


        public @NonNull Context currentContext() { return sink.currentContext();}

        public void success() {
            MsgContext c =currentContext().get(MsgContext.KEY);
            c.curServer.removeFollowupNCmd(c.channel,c.participant);
            sink.success();
        }

        public void success(T value) {
            MsgContext c =currentContext().get(MsgContext.KEY);
            c.curServer.removeFollowupNCmd(c.channel,c.participant);
            sink.success(value);
        }

        public void error(@NonNull Throwable e) {
            MsgContext c =currentContext().get(MsgContext.KEY);
            c.curServer.removeFollowupNCmd(c.channel,c.participant);
            sink.error(e);
        }

        public @NonNull MonoSink<T> onRequest(@NonNull LongConsumer consumer) { return sink.onRequest(consumer);}

        public @NonNull MonoSink<T> onCancel(@NonNull Disposable d) { return sink.onCancel(d);}

        public @NonNull MonoSink<T> onDispose(@NonNull Disposable d) {return sink.onDispose(d);}
    }

    static class MsgContext {
        public static final String KEY = "MSG_CTX";
        MsgContext(String content, Participant participant, MessageChannel channel, Server curServer) {
            this.content=content;this.participant=participant;this.channel=channel;this.curServer=curServer;
        }
        String content;
        Participant participant;
        MessageChannel channel;
        Server curServer;
        //Utility function to send messages back toward discord caller
        void send(String msg) {
            channel.createMessage(msg).subscribe();
        }
        void removeFollowup() {
            curServer.removeFollowupNCmd(channel,participant);}
    }
    static class BaseData {
        boolean isR4Only;
        String syntax;
        String helpText;

        public BaseData(boolean isR4 , String syntax, String help) {
            isR4Only=isR4;
            this.syntax=syntax;
            helpText=help;
        }
    }
    static class HelpCommand extends SimpleCommand<Void> {
        static final int space = 4;

        HelpCommand() {
            super("help", new BaseData(false, "help", "Provide a list of commands available on this channel"));
        }

        @Override
        Optional<Void> onMessage(MsgContext ctxt) {
            int max = 0;
            StringBuilder sb = new StringBuilder("```");
            if(ctxt.channel.getType() == Channel.Type.GUILD_TEXT) {
                TextChannel tch=(TextChannel) ctxt.channel;
                for (NCommand<?> c : ChannelCmds.get(tch.getName())) {
                    max = Math.max(max, c.getSyntax().length());
                }
                for (NCommand<?> c : ChannelCmds.get(tch.getName())) {
                    if (c.isBaseCommand()) {
                        sb.append(c.getSyntax()).append(" ".repeat(max + space - c.getSyntax().length())).append(c.getHelpText())
                                .append(c.isR4Only() ? "(R4 only)" : "").append("\n");

                    }
                }
                ctxt.send(sb.append("```").toString());
            }
            return Optional.empty();
        }

    }
}
