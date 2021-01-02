import io.timeandspace.cronscheduler.CronScheduler;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.function.Consumer;


enum NotifType { SDcloseReg,RRcloseReg,Trap,ACDanger}
@Slf4j
public  class Notification {
    static Duration[] defaultPattern = new Duration[] {Duration.ofMinutes(5L),Duration.ofMinutes(30L),Duration.ofHours(2L)};
    static CronScheduler scheduler=CronScheduler.create(Duration.ofMinutes(1));
    static HashMap<NotifType,Notification> notifMap=new HashMap<>();
    static {
        notifMap.put(NotifType.SDcloseReg,new Notification(
                defaultPattern,
                (d)->{

        }));
    }
    static void scheduleNotif(NotifType type,Server srv,ZonedDateTime basetime) {
        Notification notif=notifMap.get(type);
        if(notif==null) {
            log.error("Notification not found for " + type);
            return;
        }
        // persist in DB to resist process outages

        for(Duration d:notif.reminderPattern) {
            scheduler.scheduleAt(Instant.from(basetime.minus(d)),()->notif.callback.accept(d));
        }
    }

    private Notification(Duration[] reminderPattern,Consumer<Duration> callback) {
        this.reminderPattern=reminderPattern;
        this.callback = callback;
    }
    Duration[] reminderPattern;
    Consumer<Duration> callback;


}
