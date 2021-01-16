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
import java.util.function.Consumer;
import java.util.function.LongConsumer;

@Slf4j
abstract  class NCommand<T>  implements Cloneable {
        static HashMap<String, List<NCommand<?>>> ChannelCmds=new HashMap<>();

        MonoSink<T> sink ;
        String sendBefore=null;
        Consumer<MonoSink<T>> internalCb=(s)->{
            MsgContext context =s.currentContext().get(MsgContext.KEY);
            preRegister(context);
            register(context);
            sink=new SinkDecorator<>(s);
        };

        NCommand() {}
        Mono<T> mono(MsgContext c) {
            return Mono.create(internalCb).subscriberContext((context)->context.put(MsgContext.KEY,c));
        }

        //callback to handle messages
        abstract void onMessage(MsgContext ctxt);

        //create a new instance of this command everytime a msg is received
        public Object clone() throws CloneNotSupportedException{
            return super.clone();
        }

        //determines whether an incoming message is matching this command or not
        boolean matches(String content) {return content.matches("\\d+");}

        void execute(MsgContext c) {
            NCommand<T> newOne;
            try {
                //noinspection unchecked
                newOne = (NCommand<T>) clone();
            } catch (CloneNotSupportedException e) {
                e.printStackTrace();
                return;
            }
            try {
                newOne.onMessage(c);
            } catch(RecoverableError r) {
                c.send(r.getMessage());
            } catch(Throwable t) {
                c.send("Unexpected error");
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

        // Allows to perform an action just befor ethe register happens
        protected void preRegister(MsgContext c) {if(sendBefore!= null) c.send(sendBefore);}


    static void findAndExec(MsgContext c) {
        Server.ChannelPartKey k=new Server.ChannelPartKey(c.channel,c.participant);
        Command followUp= c.curServer.getFollowUpCmd(k);
        if(followUp!= null && followUp.matches(c.content)) {
            followUp.execute(c.content,c.participant,c.channel,c.curServer);
            return;
        }
        NCommand<?> nFollowUp = c.curServer.getNFollowUpCmd(k);
        if(nFollowUp!= null) {
            nFollowUp.execute(c);
            return;
        }
        if(c.channel.getType() == Channel.Type.GUILD_TEXT) {
            TextChannel tch = (TextChannel) c.channel;
            List<NCommand<?>> baseCmds = ChannelCmds.get(tch.getName());
            if (baseCmds != null && baseCmds.size()>0) {
                for (NCommand<?> cmd : baseCmds) {
                    if (cmd.matches(c.content)) {
                        cmd.mono(c).subscribe();
                        cmd.execute(c);
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
            log.info("No matching command for: " + c.content);
        }
    }
    static void registerCmds(String channelName,List<NCommand<?>> l) {ChannelCmds.put(channelName,l);}

    static abstract class NsimpleCommand<T>  extends NCommand<T> {
        String theCmd;
        NsimpleCommand(String command) {
              theCmd=command;
        }

        @Override
        boolean matches(String content) {
            return content.trim().equalsIgnoreCase(theCmd);
        }
    }
    static class readIntCmd extends NCommand<Integer> {
        readIntCmd() {super();}
        @Override
        void onMessage(MsgContext ctxt) {
            try {
                int read=Integer.parseInt(ctxt.content);
                log.info("readint "+read);
                sink.success(read);
            } catch (NumberFormatException e) {
                sink.error(new RecoverableError("Incorrect format "+ctxt.content));
            }
        }
    }
    static class yesNoCmd extends NCommand<Boolean> {
        void onMessage(MsgContext ctxt) {
            sink.success(ctxt.content.trim().equalsIgnoreCase("yes"));
        }
    }

    static class readDuration extends NCommand<Duration> {
        void onMessage(MsgContext c) {
            String[] parts=c.content.trim().split(":");
            if(parts.length ==2 || parts.length==3) {
                try {
                    int hours = Integer.parseInt(parts[0]);
                    if(hours<0 || hours>23) {
                        sink.error(new RecoverableError("incorrect hour nb <"+parts[0]+">"));
                        return;
                    }
                    int minutes = Integer.parseInt(parts[1]);
                    if(minutes<0 || minutes>59) {
                        sink.error(new RecoverableError("incorrect minute nb <"+parts[1]+">"));
                        return;
                    }
                    int seconds=0;

                    if (parts.length == 3) {
                        seconds = Integer.parseInt(parts[2]);
                        if(seconds<0 || seconds>59) {
                            sink.error(new RecoverableError("incorrect seconds nb <"+parts[2]+">"));
                            return;
                        }
                    }
                    sink.success(Duration.ofSeconds(hours*3600+minutes*60+seconds));
                } catch(NumberFormatException e) {
                    sink.error(new RecoverableError("Wrong format <"+c.content.trim()+"> expecting hh:mm or hh:mm:ss"));
                }
            } else {
                sink.error(new RecoverableError("Wrong format <"+c.content.trim()+"> expecting hh:mm or hh:mm:ss"));
            }
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
    }
}
