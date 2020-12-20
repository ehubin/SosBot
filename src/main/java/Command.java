import discord4j.core.object.entity.Member;
import discord4j.core.object.entity.channel.MessageChannel;

public interface Command {
    String getBaseCommand();
    void execute(CmdInput in); //called when command is executed
    boolean isBaseCommand(); //true if this command can be executed directly false if it is follow-up from a basic one
    String getSyntax();     // syntax of the command
    String getHelpText();   // Help text describing the syntax
    boolean isR4Only();     // true if only R4 can perform this command

}

interface CmdInput {
    String getRawInput();
    Participant getParticipant();
    Server getServer();
    MessageChannel getChannel();
}
