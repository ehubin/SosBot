import io.timeandspace.cronscheduler.CronScheduler;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang.builder.HashCodeBuilder;

import java.sql.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;


enum NotifType { SDcloseReg,SDnextWave,RRcloseReg,Trap,ACDanger}
@Slf4j
public  class Notification {
    static final CronScheduler scheduler=CronScheduler.create(Duration.ofMinutes(1));
    static final HashMap<NotifType,Notification> notifMap=new HashMap<>();
    static final HashMap<ServerNotifTime, List<Future<?>>> activeNotifs = new HashMap<>();
    static final HashMap<ServerNotif, Set<Instant>> notifIndex = new HashMap<>();

    //*********************Fields***************************************************************
    Duration[] reminderPattern;
    Consumer<NotificationInput> callback;
    Duration period=null;


    private Notification(Duration[] reminderPattern,Consumer<NotificationInput> callback) {
        this.reminderPattern=reminderPattern;
        this.callback = callback;
    }
    private Notification(Duration[] reminderPattern,Consumer<NotificationInput> callback,Duration period) {
        this.reminderPattern=reminderPattern;
        this.callback = callback;
        this.period=period;
    }

    static Set<Instant> getNotifs(NotifType type,Server srv) {
        Set<Instant> res = notifIndex.get(new ServerNotif(srv,type));
        if(res==null) res=Set.of();
        final Duration theperiod =notifMap.get(type).period;
        if( theperiod!= null) {
            Instant now=Instant.now();
            return res.stream().map((i)->{
                Duration delay= Duration.between(now,i);
                while(delay.isNegative()) {
                    delay = delay.plus(theperiod);
                    i=i.plus(theperiod);
                }
                return i;
            }).collect(Collectors.toSet());
        }
        return res;
    }
    static Notification getNotificationDescription(NotifType type) {
        return notifMap.get(type);
    }
    static void cancel(Server s,NotifType t,Instant i) {
        ServerNotifTime snt= new ServerNotifTime(s,t,i);
        List<Future<?>> tasks = activeNotifs.remove(snt);
        Set<Instant> basetimes=notifIndex.get(new ServerNotif(s,t));
        if(basetimes != null) basetimes.removeIf((inst)->inst.equals(i));
        snt.removeFromDB();
        if(tasks==null) {
            log.warn("No tasks found for "+t+" event in server "+s.guild.getName());
            return;
        }
        for(Future<?> task:tasks) {
            task.cancel(false);
        }

    }
    static final String trapHelp = "Everyone should create one rally with best heroes. Try to schedule the rallies so that they are evenly spread across the first 5 minutes.\n Then you join rallies with as many marches as possible as long as you have 3 heroes available.";
    static final DateTimeFormatter hhmm=DateTimeFormatter.ofPattern("hh:mm a z").withZone(ZoneId.of("UTC"));
    // initializes all known notifications
    static {
        notifMap.put(NotifType.Trap,new Notification(
                new Duration[] {Duration.ofMinutes(1L),Duration.ofMinutes(30L),Duration.ofHours(6)},
                (in)->{
                    in.server.TrapChannel.createMessage("@everyone Trap will take place in "+format(in.before)+" at "+hhmm.format(in.basetime)+"\n"+trapHelp).subscribe();
                    log.info("sending trap notif for minus "+in.before.toString());

                },
                Duration.ofDays(2)));
        notifMap.put(NotifType.SDnextWave,new Notification(
            new Duration[] {Duration.ofMinutes(5L),Duration.ofMinutes(30L),Duration.ofMinutes(120L)},
                (in) ->{
                    in.server.SDChannel.createMessage("@R4 showdown swapping phase will close in "+format(in.before)+" at "+hhmm.format(in.basetime)+"\n"+"Try to perform swapping at the very last minute").subscribe();
                    log.info("sending SD next wave notif for minus "+in.before.toString());
                }
        ));
    }

    public static int cancelAllNotifs(NotifType type, Server curServer) {
        ServerNotif sn=new ServerNotif(curServer,type);
        return sn.cancelAll();
    }

    //allows to index notifications for a given Server/type of notification
    static class ServerNotif {
        ServerNotif(Server s, NotifType t) {srv=s;type=t; }
        Server srv;
        NotifType type;
        public boolean equals(Object o) {
            return o instanceof ServerNotif && srv.getId()==(((ServerNotif)o).srv.getId())
                    && type == ((ServerNotif)o).type;
        }
        @Override
        public int hashCode() {
            HashCodeBuilder hcb = new HashCodeBuilder();
            return hcb.append(srv.getId()).append(type).hashCode();
        }

        public void register(Instant basetime) {
            Set<Instant> timings = notifIndex.computeIfAbsent(this, k -> new HashSet<>());
            timings.add(basetime);
        }
        public void unregister(Instant basetime) {
            Set<Instant> timings=notifIndex.get(this);
            if(timings != null) timings.remove(basetime);
        }

        public int cancelAll() {
            int res=0;
            Set<Instant> existing=notifIndex.get(this);
            if(existing==null || existing.size()<=0) {
                log.info("No Notif to delete for "+this);
                return 0;
            }
            for(Instant i:existing) {
                ++res;
                ServerNotifTime snt=new ServerNotifTime(srv,type,i);
                List<Future<?>> tasks = activeNotifs.get(snt);
                if(tasks!=null) {
                    for(Future<?> task:tasks)
                        task.cancel(false);
                }
                activeNotifs.put(snt,new ArrayList<>());
                snt.removeFromDB();
            }
            notifIndex.put(this,new HashSet<>());
            return res;
        }
    }

    // uniquely identifies an active notification for a given Server,Type and time
    static class ServerNotifTime {
        Server srv;
        NotifType type;
        Instant time;

        ServerNotifTime(Server s, NotifType t, Instant basetime) {srv=s;type=t; time=basetime;}
        @Override
        public boolean equals(Object o) {
            return o instanceof ServerNotifTime && srv.equals(((ServerNotifTime)o).srv)
                    && type == ((ServerNotifTime)o).type
                    && time.equals(((ServerNotifTime)o).time);
        }
        @Override
        public int hashCode() {
            HashCodeBuilder hcb = new HashCodeBuilder();
            return hcb.append(srv.getId()).append(type).append(time.getEpochSecond()).hashCode();
        }
        public void createInDb() {
            try {
                synchronized (_Q.insertNotif) {
                    PreparedStatement n = _Q.insertNotif;
                    n.setLong(1, srv.getId());
                    n.setString(2, type.name());
                    n.setTimestamp(3, Timestamp.from(time));
                    n.executeUpdate();
                }
            } catch (SQLException e) {
                log.error("Notif db save error", e);
                SosBot.checkDBConnection();
            }
        }
        @Override
        public String toString() {
            return type+" event for "+srv.guild.getName()+" server at "+time;
        }
        public void removeFromDB() {
            try {
                synchronized (_Q.deleteNotif) {
                    PreparedStatement n = _Q.deleteNotif;
                    n.setLong(1, srv.getId());
                    n.setString(2, type.name());
                    n.setTimestamp(3, Timestamp.from(time));
                    if(n.executeUpdate()!= 1) {
                        log.warn("Nothing deleted for "+this);
                    }
                }
            } catch (SQLException e) {
                log.error("Notif db delete error", e);
                SosBot.checkDBConnection();
            }
        }
    }



    static void scheduleNotif(NotifType type,Server srv,Instant basetime) {
        scheduleNotif(type,srv,basetime,true);
    }
    static void scheduleNotif(NotifType type,Server srv,Instant basetime,boolean updateDB) {
        Notification notif=notifMap.get(type);
        if(notif==null) {
            log.error("Notification not found for " + type);
            return;
        }
        ServerNotifTime sno = new ServerNotifTime(srv,type,basetime);
        List<Future<?>> activeTasks = activeNotifs.get(sno);
        if(updateDB) {
            if (activeTasks != null) {
                for (Future<?> ft : activeTasks) ft.cancel(false);
                // persist in DB to resist process outages
                try {
                    synchronized (_Q.updateNotif) {
                        PreparedStatement n = _Q.updateNotif;
                        n.setTimestamp(1, Timestamp.from(basetime));
                        n.setLong(2, srv.getId());
                        n.setString(3, type.name());
                        n.executeUpdate();

                    }
                } catch (SQLException e) {
                    log.error("Notif db save error", e);
                    SosBot.checkDBConnection();
                }
            } else {
                // persist in DB to resist process outages
                sno.createInDb();
            }
        }

        Instant now=Instant.now();
        List<Future<?>> taskList=new ArrayList<>();

        if(notif.period == null) { //one-off task
            for(Duration d:notif.reminderPattern) {
                Instant event = Instant.from(basetime.minus(d));
                if (now.isAfter(event)) {
                    log.warn("Dropping " + type + " notif  for duratioin minus " + format(d));
                } else {
                    NotificationInput in = new NotificationInput(d, basetime, srv);
                    taskList.add(scheduler.scheduleAt(Instant.from(basetime.minus(d)), () -> {
                        try {
                            notif.callback.accept(in);
                        } catch (Throwable t) {
                            log.error("Uncaught exception while running scheduled " + type + "task ", t);
                        }
                    }));
                }
            }
        } else { // periodic task
            for(Duration d:notif.reminderPattern) {
                NotificationInput in=new NotificationInput(d,basetime,srv);
                Duration delay= Duration.between(now,Instant.from(basetime.minus(d)));
                while(delay.isNegative()) {
                    delay = delay.plus(notif.period);
                    in.basetime = in.basetime.plus(notif.period);
                }
                taskList.add(scheduler.scheduleAtFixedRate(delay.getSeconds(),notif.period.getSeconds(), TimeUnit.SECONDS
                    ,(long scheduledTime) -> {
                        try {
                            in.basetime= Instant.ofEpochMilli(scheduledTime).plus(in.before);
                            notif.callback.accept(in);
                        } catch (Throwable t) {
                            log.error("Uncaught exception while running scheduled " + type + "task ", t);
                        }
                    }));

            }
        }
        if(taskList.size() >0) {
            activeNotifs.put(sno,taskList);
            new ServerNotif(srv,type).register(basetime);
            log.info("Created notification for "+sno);
        }
        else{
            log.warn("Did not schedule any task for "+type);
            //cleanup
            synchronized (_Q.deleteNotif) {
                try {
                    _Q.deleteNotif.setLong(1, srv.getId());
                    _Q.deleteNotif.setString(2,type.name());
                    _Q.deleteNotif.setTimestamp(3,java.sql.Timestamp.from(basetime));
                    int nb;
                    if((nb=_Q.deleteNotif.executeUpdate()) != 1) {
                        log.error("notif cleanup cleaned "+nb+" items!!");
                    }
                    activeNotifs.remove(sno);
                    new ServerNotif(srv,type).unregister(basetime);
                } catch(SQLException e) {
                    log.error("notif cleanup DB error",e);
                    SosBot.checkDBConnection();
                }
            }
        }
    }
    static void initFromDb() {
        //cancel everything
        for(List<Future<?>> lf:activeNotifs.values()) {
            for (Future<?> f : lf) {
                f.cancel(true);
            }
        }
        activeNotifs.clear();
        notifIndex.clear();

        try {
            synchronized(_Q.getAllNotifs) {
                ResultSet rs=_Q.getAllNotifs.executeQuery();

                while(rs.next()) {
                    final Instant inst = rs.getTimestamp("basetime").toInstant();
                    final NotifType nt=NotifType.valueOf(rs.getString("notif"));
                    Server.getServerFromId(rs.getLong("server")).subscribe((s)-> scheduleNotif(nt,s,inst,false));

                }
            }
        } catch(SQLException e) {
            log.error("Notif init DB error for",e);

        }
    }



    private static queries _Q;
    static void initQueries(Connection db) throws SQLException { _Q=new queries(db); }
    private static class queries {
        final PreparedStatement insertNotif,updateNotif,getAllNotifs,deleteNotif;
        queries(Connection db) throws SQLException {
            insertNotif = db.prepareStatement("insert into notifications(server,notif,basetime) values(?,?,?)");
            updateNotif = db.prepareStatement("update notifications set basetime=? where server=? and notif=?");
            getAllNotifs =db.prepareStatement( "select * from notifications");
            deleteNotif=db.prepareStatement( "delete from notifications where server=? and notif=? and basetime=?");
        }
    }

    static class NotificationInput {
        NotificationInput(Duration before,Instant basetime,Server s) { this.before=before; this.basetime=basetime; server=s;}
        Duration before;
        Instant basetime;
        Server server;
    }
    static String format(Duration d) {
        long s=d.toSeconds();
        return s>3600 ? ( s%3600 != 0 ? String.format("%d hour%s %02d min", s / 3600,(s>7200?"s":""), (s % 3600) / 60)
                 :String.format("%d hour%s", s / 3600,(s>7200?"s":"")))
                 : String.format("%02d min", (s % 3600) / 60);
    }
}
