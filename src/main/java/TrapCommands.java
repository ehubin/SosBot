import com.joestelmach.natty.DateGroup;
import com.joestelmach.natty.Parser;
import discord4j.core.object.entity.channel.MessageChannel;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;


public class TrapCommands {
    public static final String name ="\uD83D\uDC7Dtrap\uD83D\uDC7D";

    static void init() {
        ArrayList<Command> commands=new ArrayList<>();
        commands.add(new HelpCommand());
        commands.add(notifyCmd);
        commands.add(stopNotifyCmd);
        commands.add(infoCmd);
        Command.registerCmds(name,commands);
    }
    static final  Parser parser = new Parser(TimeZone.getTimeZone("UTC"));

    static final DateTimeFormatter dtf = DateTimeFormatter.ofPattern("EEEE dd MMM h:mm a z", Locale.US).withZone(ZoneId.of("UTC"));
    static Command notifyCmd = new SimpleCommand("notify",
            new Command.BaseData(true,"notify","Set notification time for trap")) {
        @Override
        protected void execute(String content, Participant participant, MessageChannel channel, Server curServer) {
            curServer.setFollowUpCmd(channel,participant,parseTime);
            channel.createMessage("Please enter next Trap date and timing in utc (e.g 20:00 or tomorrow 21:00)").subscribe();
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
                Instant base = Instant.ofEpochMilli(groups.get(0).getDates().get(0).getTime());
                Set<Instant> timings=Notification.getNotifs(NotifType.Trap,curServer);
                if(timings.size()>0) {
                    log.info("Deleting existing trap notif first");
                    Notification.cancelAllNotifs(NotifType.Trap,curServer);
                }
                Notification.scheduleNotif(NotifType.Trap, curServer, base);
                log.info("Scheduled trap notif for " + base);
                channel.createMessage("Trap notifications now active for event at "+dtf.format(base)).subscribe();
                curServer.removeFollowupCmd(channel,participant);
            }
        };
    };
    static Command stopNotifyCmd = new SimpleCommand("stopnotifs",
            new Command.BaseData(true,"stopnotifs","Disable trap notifications")) {

        @Override
        protected void execute(String content, Participant participant, MessageChannel channel, Server curServer) {
            int nbDeleted=Notification.cancelAllNotifs(NotifType.Trap,curServer);
            if(nbDeleted==0) {
                channel.createMessage("Nothing to cancel").subscribe();
            } else {

                    channel.createMessage("Trap notifications canceled").subscribe();
            }
        }
    };

    static Command infoCmd = new SimpleCommand("info",
            new Command.BaseData(false,"info","Provide information on next trap and its related notifications")) {

        @Override
        protected void execute(String content, Participant participant, MessageChannel channel, Server curServer) {
            Set<Instant> timings=Notification.getNotifs(NotifType.Trap,curServer);
            if(timings.size()==0) {
                channel.createMessage("Trap notifications are not active right now").subscribe();
            } else if(timings.size()>1) {
                channel.createMessage("Error: Trap notifications active for "+timings.size()+" distinct times!!!").subscribe();
            } else {
                Notification notif=Notification.getNotificationDescription(NotifType.Trap);
                StringBuilder sb=new StringBuilder("Trap notifications are active for an event on ");
                sb.append(timings.iterator().next()).append(" and every 48h after that. Reminders will be sent:\n");
                for(Duration d:notif.reminderPattern) sb.append(Notification.format(d)).append("\n");
                sb.append("before the event");
                channel.createMessage(sb.toString()).subscribe();
            }
        }
    };



}

