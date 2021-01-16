package sosbot;

import io.timeandspace.cronscheduler.CronScheduler;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang.builder.HashCodeBuilder;

import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;


enum NotifType { SDcloseReg,SDnextWave,RRcloseReg, RRevent, defendAC, Trap}
@Slf4j
public  class Notification {
    static final CronScheduler scheduler=CronScheduler.create(Duration.ofMinutes(1));
    static private final HashMap<NotifType,Notification> notifMap=new HashMap<>();
    static private final HashMap<ServerNotifTime, List<Future<?>>> activeNotifs = new HashMap<>();
    static private final HashMap<ServerNotif, Set<Instant>> notifIndex = new HashMap<>();

    //*********************Fields***************************************************************
    Duration[] reminderPattern;
    Consumer<NotificationInput> callback;
    Duration period=null;


    public Notification(Duration[] reminderPattern, Consumer<NotificationInput> callback) {
        this.reminderPattern=reminderPattern;
        this.callback = callback;
    }
    public Notification(Duration[] reminderPattern, Consumer<NotificationInput> callback, Duration period) {
        this.reminderPattern=reminderPattern;
        this.callback = callback;
        this.period=period;
    }

    @SuppressWarnings("SameParameterValue")
    static Set<Instant> getNotifs(NotifType type, Server srv) {
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
    @SuppressWarnings("SameParameterValue")
    static Notification getNotificationDescription(NotifType type) {
        return notifMap.get(type);
    }

    static void registerNotifType(NotifType t,Notification notif) {
        notifMap.put(t,notif);
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
    static void scheduleNotif(NotifType type, Server srv, Instant basetime) {
        scheduleNotif(type,srv,basetime,true,true);
    }
    @SuppressWarnings({"SameParameterValue", "unused"})
    static void scheduleNotif(NotifType type, Server srv, Instant basetime, boolean cancelPrevious) {
        scheduleNotif(type,srv,basetime,true,cancelPrevious);
    }
    static void scheduleNotif(NotifType type, Server srv, Instant basetime, boolean updateDB, boolean cancelPrevious) {
        Notification notif=notifMap.get(type);
        if(notif==null) {
            log.error("Notification not found for " + type);
            return;
        }
        ServerNotif sn=new ServerNotif(srv,type);
        if(cancelPrevious) {
            sn.cancelAll();
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
                    log.warn("Dropping " + type + " notif  for duratioin minus " + Util.format(d));
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
                    Server.getServerFromId(rs.getLong("server")).subscribe((s)-> scheduleNotif(nt,s,inst,false,false));

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
        NotificationInput(Duration before, Instant basetime, Server s) { this.before=before; this.basetime=basetime; server=s;}
        Duration before;
        Instant basetime;
        Server server;
    }

}