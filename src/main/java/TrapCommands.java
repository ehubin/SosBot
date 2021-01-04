import com.joestelmach.natty.DateGroup;
import com.joestelmach.natty.Parser;
import discord4j.core.object.entity.channel.MessageChannel;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;


public class TrapCommands {
    public static final String name ="\uD83D\uDC7Dtrap\uD83D\uDC7D";

    static void init() {
        ArrayList<Command> commands=new ArrayList<>();
        commands.add(new HelpCommand());
        commands.add(notifyCmd);
        Command.registerCmds(name,commands);
    }
    static final  Parser parser = new Parser(TimeZone.getTimeZone("UTC"));

    static final DateTimeFormatter dtf = DateTimeFormatter.ofPattern("EEEE dd MMM h:mm a z", Locale.US);
    static Command notifyCmd = new SimpleCommand("notify",
            new Command.BaseData(true,"notify","Set notification time for trap")) {
        @Override
        protected void execute(String content, Participant participant, MessageChannel channel, Server curServer) {
            curServer.setFollowUpCmd(channel,participant,parseTime);
            channel.createMessage("Please enter next Trap date and timing in utc (e.g tonight 20:00 or tomorrow 21:00)").subscribe();
        }

        final Command parseTime = new FollowupCommand() {
            @Override
            protected void execute(String content, Participant participant, MessageChannel channel, Server curServer) {
                List<DateGroup> groups = parser.parse(content);
                if(groups.size()==0 || groups.get(0).getDates().size()==0) {
                    channel.createMessage("Bad date and time format :"+content);
                    curServer.removeFollowupCmd(channel,participant);
                    return;
                }
                ZonedDateTime base = ZonedDateTime.ofInstant(Instant.ofEpochMilli(groups.get(0).getDates().get(0).getTime()), ZoneId.of("UTC"));
                Notification.scheduleNotif(NotifType.Trap,curServer,base );
                log.info("Scheduled trap notif for "+base);
                channel.createMessage("Trap notifications now active for event at "+dtf.format(base)).subscribe();
                curServer.removeFollowupCmd(channel,participant);
            }
        };
    };


}

