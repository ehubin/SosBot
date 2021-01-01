
import discord4j.core.object.entity.channel.Channel;
import discord4j.core.object.entity.channel.MessageChannel;
import discord4j.core.object.entity.channel.TextChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public abstract class Command {
    protected static final Logger _logger = LoggerFactory.getLogger(Command.class);


    static HashMap<String, List<Command>> ChannelCmds=new HashMap<>();

    static void registerCmds(String channelName,List<Command> cmds) {ChannelCmds.put(channelName,cmds); }
    BaseData bd=null;

    abstract protected void execute(String content, Participant participant, MessageChannel channel, Server curServer); //called when command is executed
    //true if this command can be executed directly false if it is follow-up from a basic one
    public boolean isBaseCommand() {return bd!= null;}
    // syntax of the command
    public String getSyntax() {return bd!= null ? bd.syntax:"";}
    // Help text describing the syntax
    public String getHelpText(){ return bd!= null ? bd.helpText:"";}
    // true if only R4 can perform this command
    public boolean isR4Only() { return bd != null && bd.isR4Only;}
    protected Command() {}
    protected Command(BaseData bdata) { bd=bdata;}
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
    abstract protected boolean matches(String content);

    static void findAndExec(String content, Participant participant, MessageChannel channel, Server curServer) {
        Command followUp= curServer.getFollowUpCmd(new Server.ChannelPartKey(channel,participant));
        if(followUp!= null && followUp.matches(content)) {
            followUp.execute(content,participant,channel,curServer);
            return;
        }
        if(channel.getType() == Channel.Type.GUILD_TEXT) {
            TextChannel tch = (TextChannel) channel;
            List<Command> baseCmds = ChannelCmds.get(tch.getName());
            if (baseCmds != null) {
                for (Command c : baseCmds)
                    if (c.matches(content)) {
                        if(c.isR4Only() && !participant.isR4()) {
                            channel.createMessage("Sorry, the \"" + c.getSyntax()+"\" command is for R4s only.").subscribe();
                            return;
                        }
                        c.execute(content,participant,channel,curServer);
                        return;
                    }
            }
            _logger.info("No matching command for: " + content);
        }
    }


}
@SuppressWarnings("unused")
abstract class SimpleCommand extends Command {
    protected String cmdStr;
    protected boolean matches(String content) { return content.equalsIgnoreCase(cmdStr);}
    protected SimpleCommand(String cmdStr,BaseData bd) { super(bd);this.cmdStr=cmdStr;}
    protected SimpleCommand(String cmdStr) {this.cmdStr=cmdStr;}
}

@SuppressWarnings("unused")
abstract class FollowupCommand extends Command {
    protected boolean matches(String content) { return true;} //always matches for "normal" follow-up
}


@SuppressWarnings("unused")
abstract class RegexCommand extends Command {
    final protected Pattern pattern;
    Matcher m=null;
    String content=null;
    protected RegexCommand(String regex,BaseData bd) {
        super(bd);
        this.pattern=Pattern.compile(regex);
    }

    protected RegexCommand(Pattern p, BaseData bd) {  super(bd);this.pattern=p; }
    protected RegexCommand(Pattern p) { this.pattern=p; }
    protected RegexCommand(String regexp) { this.pattern=Pattern.compile(regexp); }

    protected boolean matches(String content) {
        m=pattern.matcher(content);
        this.content=content;
        return m.matches();
    }
    @Override
    protected void execute(String content, Participant participant, MessageChannel channel, Server curServer) {
        if(this.content==null || !this.content.equals(content)) {
            _logger.error("Wrong regex execution on "+getSyntax());
            return;
        }
        execute(m,content,participant,channel,curServer);
    }

    protected abstract void execute(Matcher ma,String content, Participant participant, MessageChannel channel, Server curServer);
}

class HelpCommand extends SimpleCommand {
    static final int space=4;
    HelpCommand() {
        super("help",new BaseData(false,"help","Provide a list of commands available on this channel"));

    }
    @Override
    protected void execute(String content, Participant participant, MessageChannel channel, Server curServer) {
        int max=0;
        StringBuilder sb=new StringBuilder("```");
        if(channel.getType() == Channel.Type.GUILD_TEXT) {
            TextChannel tch = (TextChannel) channel;
            for(Command c:ChannelCmds.get(tch.getName())) {
                max=Math.max(max,c.getSyntax().length());
            }
            for(Command c:ChannelCmds.get(tch.getName())) {
                if (c.isBaseCommand()) {
                    sb.append(c.getSyntax()).append(" ".repeat(max + space - c.getSyntax().length())).append(c.getHelpText())
                            .append(c.isR4Only() ? "(R4 only)" : "").append("\n");

                }
            }
            channel.createMessage(sb.append("```").toString())
                    .doOnError(t->_logger.error("Error sending message",t))
                    .subscribe();
        }
    }
}




