import io.timeandspace.cronscheduler.CronScheduler;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.function.Consumer;


enum NotifType { SDcloseReg,RRcloseReg,Trap,ACDanger}
@Slf4j
public  class Notification {
    static CronScheduler scheduler=CronScheduler.create(Duration.ofMinutes(1));

    static HashMap<NotifType,Notification> notifMap=new HashMap<>();
    static {
        final String trapHelp = "Everyone should create one rally with best heroes. Try to schedule the rallies so that they are evenly spread across the first 5 minutes.\n Then you join rallies with as many marches as possible as long as you have 3 heroes available.";
        final DateTimeFormatter hhmm=DateTimeFormatter.ofPattern("hh:mm a z");
        notifMap.put(NotifType.Trap,new Notification(
                new Duration[] {Duration.ofMinutes(1L),Duration.ofMinutes(30L),Duration.ofHours(6)},
                (in)->{
           in.server.TrapChannel.createMessage("@everyone Trap will take place in "+format(in.duration)+" at "+in.basetime.format(hhmm)+"\n"+trapHelp).subscribe();
           log.info("sending trap notif for minus "+in.duration.toString());

        }));
    }
    static void scheduleNotif(NotifType type,Server srv,ZonedDateTime basetime) {
        Notification notif=notifMap.get(type);
        if(notif==null) {
            log.error("Notification not found for " + type);
            return;
        }
        // persist in DB to resist process outages
        Instant now=Instant.now();
        for(Duration d:notif.reminderPattern) {
            Instant  event=Instant.from(basetime.minus(d));
            if(now.isAfter(event)) {
                log.warn("Dropping "+type+" notif  for duratioin minus "+format(d));
            } else {
                NotificationInput in = new NotificationInput();
                in.server = srv;
                in.duration = d;
                in.basetime = basetime;
                scheduler.scheduleAt(Instant.from(basetime.minus(d)), () -> notif.callback.accept(in));
            }
        }
    }

    private Notification(Duration[] reminderPattern,Consumer<NotificationInput> callback) {
        this.reminderPattern=reminderPattern;
        this.callback = callback;
    }
    Duration[] reminderPattern;
    Consumer<NotificationInput> callback;

    static class NotificationInput {
        Duration duration;
        ZonedDateTime basetime;
        Server server;
    }
    static String format(Duration d) {
        long s=d.toSeconds();
        return s>3600 ? ( s%3600 != 0 ? String.format("%d hour%s %02d min", s / 3600,(s>7200?"s":""), (s % 3600) / 60)
                 :String.format("%d hour%s", s / 3600,(s>7200?"s":"")))
                 : String.format("%02d min", (s % 3600) / 60); }
}
