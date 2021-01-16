package sosbot;

import discord4j.core.object.entity.channel.MessageChannel;
import lombok.extern.slf4j.Slf4j;
import sosbot.ChannelAndCommands;

import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Slf4j
public class TrapCommands extends ChannelAndCommands {
    public static final String name ="\uD83D\uDC7Dtrap\uD83D\uDC7D";
    public static final String topic="This channel is to keep track of trap event and the related notifications! Enjoy!";
    static final String trapHelp = "Everyone should create one rally with best heroes. Try to schedule the rallies so that they are evenly spread across the first 5 minutes.\n Then you join rallies with as many marches as possible as long as you have 3 heroes available.";

    TrapCommands() {
        super(name,topic);
        register(new HelpCommand());
        register(notifyCmd);
        register(stopNotifyCmd);
        register(infoCmd);
        init();
        Notification.registerNotifType(NotifType.Trap,new Notification(
                new Duration[] {Duration.ofMinutes(1L),Duration.ofMinutes(30L),Duration.ofHours(6)},
                (in)->{
                    getChannel(in.server).createMessage("@everyone Trap will take place in "+ Util.format(in.before)+" at "+ Util.hhmm.format(in.basetime)+"\n"+trapHelp).subscribe();
                    log.info("sending trap notif for minus "+in.before.toString());
                },
                Duration.ofDays(2L)
        ));
    }


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
                Instant base;
                try {
                    base = Util.getParser().parseOne(content);
                } catch(ParseException e) {
                    channel.createMessage("Bad date and time format :"+content);
                    curServer.removeFollowupCmd(channel,participant);
                    return;
                }
                Notification.scheduleNotif(NotifType.Trap, curServer, base);
                Command.log.info("Scheduled trap notif for " + base);
                channel.createMessage("Trap notifications now active for event at " + Util.format(base)).subscribe();
                curServer.removeFollowupCmd(channel, participant);

            }
        };
    };
    static Command stopNotifyCmd = new SimpleCommand("stopnotifs",
            new Command.BaseData(true,"stopnotifs","Disable trap notifications")) {

        @Override
        protected void execute(String content, Participant participant, MessageChannel channel, Server curServer) {
            int nbDeleted= Notification.cancelAllNotifs(NotifType.Trap,curServer);
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
            Set<Instant> timings= Notification.getNotifs(NotifType.Trap,curServer);
            if(timings.size()==0) {
                channel.createMessage("Trap notifications are not active right now").subscribe();
            } else if(timings.size()>1) {
                channel.createMessage("Error: Trap notifications active for "+timings.size()+" distinct times!!!").subscribe();
            } else {
                Notification notif= Notification.getNotificationDescription(NotifType.Trap);
                StringBuilder sb=new StringBuilder("Trap notifications are active for an event on ");
                sb.append(timings.iterator().next()).append(" and every 48h after that. Reminders will be sent:\n");
                for(Duration d:notif.reminderPattern) sb.append(Util.format(d)).append("\n");
                sb.append("before the event");
                channel.createMessage(sb.toString()).subscribe();
            }
        }
    };

}

