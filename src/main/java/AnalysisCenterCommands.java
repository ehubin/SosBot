import discord4j.core.object.entity.channel.MessageChannel;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.ArrayList;
@Slf4j
public class AnalysisCenterCommands extends ChannelAndCommands{
        public static final String name ="\uD83D\uDDA5analysis-center\uD83D\uDDA5";
        public static final String topic ="This channel allows to keep track of our Analysis centers  and get notified when they need to be watched/defended";
        AnalysisCenterCommands() {
            super(name,topic);
            register(new HelpCommand());
            register(listCmd);
            init();
            Notification.registerNotifType(NotifType.defendAC,new Notification(
                    new Duration[] {Duration.ofMinutes(1L),Duration.ofMinutes(30L),Duration.ofHours(6)},
                    (in)->{
                        getChannel().createMessage("@everyone Trap will take place in "+Util.format(in.before)+" at "+Util.hhmm.format(in.basetime)+"\n").subscribe();
                        log.info("sending trap notif for minus "+in.before.toString());
                    },
                    Duration.ofDays(2L)
            ));
        }

        static Command listCmd= new SimpleCommand("list",
                new Command.BaseData(false, "list", "Lists all analysis centers for this server")) {
            @Override
            protected void execute(String content, Participant participant, MessageChannel channel, Server curServer) {

            }
        };
}
