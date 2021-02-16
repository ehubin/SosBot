package sosbot;

import io.timeandspace.cronscheduler.CronScheduler;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang.builder.HashCodeBuilder;
import reactor.util.annotation.NonNull;
import reactor.util.annotation.Nullable;

import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;


enum NotifType { SDcloseReg,SDnextWave,RRcloseReg, RRevent, defendAC, Trap}
@Slf4j
public  class Notification<T extends dbready> {
    static final CronScheduler scheduler=CronScheduler.create(Duration.ofMinutes(1));
    static final Notification<?>[] All=new Notification[NotifType.values().length];
    static private final HashMap<ServerNotifTime, List<Future<?>>> activeNotifs = new HashMap<>();
    static private final HashMap<ServerNotif, Set<Instant>> notifIndex = new HashMap<>();

    //*********************Fields***************************************************************
    Duration[] reminderPattern;
    Consumer<NotificationInput<T>> callback;
    Duration period;
    NotifType type;
    Function<String,T> dbFactory;

    public Notification(NotifType type,Duration[] reminderPattern, Consumer<NotificationInput<T>> callback,Function<String,T> dbfactory) {
        this(type,reminderPattern,callback,null,dbfactory);
    }
    public Notification(NotifType type,Duration[] reminderPattern, Consumer<NotificationInput<T>> callback) {
        this(type,reminderPattern,callback,null,null);
    }
    public Notification(NotifType type,Duration[] reminderPattern, Consumer<NotificationInput<T>> callback, Duration period) {
        this(type,reminderPattern,callback,period,null);
    }
    public Notification(NotifType type,Duration[] reminderPattern, Consumer<NotificationInput<T>> callback, Duration period,Function<String,T> dbfactory) {
        this.reminderPattern=reminderPattern;
        this.callback = callback;
        this.period=period;
        this.type=type;
        this.dbFactory=dbfactory;
        if(All[type.ordinal()] == null) All[type.ordinal()] = this;
        else throw new Util.UnrecoverableError("2 objects for notif type "+type);

    }

    T buildData(String dbStr) { return dbStr==null?null:dbFactory.apply(dbStr);}

    void scheduleNotif(NotifType type, Server srv, Instant basetime) {
        scheduleNotif(type,srv,basetime,true,true);
    }
    @SuppressWarnings({"SameParameterValue", "unused"})
    void scheduleNotif(NotifType type, Server srv, Instant basetime, boolean cancelPrevious) {
        scheduleNotif(type,srv,basetime,true,cancelPrevious);
    }
    void scheduleNotif(NotifType type, Server srv, Instant basetime, boolean updateDB, boolean cancelPrevious) {
        scheduleNotif(type,srv,basetime,updateDB,cancelPrevious, (T)null);
    }
    void scheduleNotif(NotifType type, Server srv, Instant basetime, boolean updateDB, boolean cancelPrevious, @Nullable String data) {
        scheduleNotif(type,srv,basetime,updateDB,cancelPrevious,buildData(data));
    }

    void scheduleNotif(NotifType type, Server srv, Instant basetime, boolean updateDB, boolean cancelPrevious, @Nullable T data) {

        ServerNotif sn=new ServerNotif(srv,type,data==null?"":data.serialize());
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
                        n.setString(4, data!= null?data.serialize():null);
                        n.executeUpdate();

                    }
                } catch (SQLException e) {
                    log.error("Notif db save error", e);
                    SosBot.checkDBConnection();
                }
            } else {
                // persist in DB to resist process outages
                sno.createInDb(data!= null?data.serialize():null);
            }
        }

        Instant now=Instant.now();
        List<Future<?>> taskList=new ArrayList<>();

        if(period == null) { //one-off task
            for(Duration d:reminderPattern) {
                Instant event = Instant.from(basetime.minus(d));
                if (now.isAfter(event)) {
                    log.warn("Dropping " + type + " notif  for duratioin minus " + Util.format(d));
                } else {
                    NotificationInput<T> in = new NotificationInput<>(d, basetime, srv,data);
                    taskList.add(scheduler.scheduleAt(Instant.from(basetime.minus(d)), () -> {
                        try {
                            callback.accept(in);
                        } catch (Throwable t) {
                            log.error("Uncaught exception while running scheduled " + type + "task ", t);
                        }
                    }));
                }
            }
        } else { // periodic task
            for(Duration d:reminderPattern) {
                NotificationInput<T> in=new NotificationInput<>(d,basetime,srv,data);
                Duration delay= Duration.between(now,Instant.from(basetime.minus(d)));
                while(delay.isNegative()) {
                    delay = delay.plus(period);
                    in.basetime = in.basetime.plus(period);
                }
                taskList.add(scheduler.scheduleAtFixedRate(delay.getSeconds(),period.getSeconds(), TimeUnit.SECONDS
                        ,(long scheduledTime) -> {
                            try {
                                in.basetime= Instant.ofEpochMilli(scheduledTime).plus(in.before);
                                callback.accept(in);
                            } catch (Throwable t) {
                                log.error("Uncaught exception while running scheduled " + type + "task ", t);
                            }
                        }));

            }
        }
        if(taskList.size() >0) {
            activeNotifs.put(sno,taskList);
            sn.register(basetime);
            log.info("Created notification for "+sno);
            //log.info("stack",new Exception());
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
                    sn.unregister(basetime);
                } catch(SQLException e) {
                    log.error("notif cleanup DB error",e);
                    SosBot.checkDBConnection();
                }
            }
        }
    }

    @SuppressWarnings("SameParameterValue")
    static Set<Instant> getNotifs(NotifType type, Server srv) {
        Set<Instant> res = notifIndex.get(new ServerNotif(srv,type));
        if(res==null) res=Set.of();
        final Duration theperiod =All[type.ordinal()].period;
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
    static Notification<?> getNotificationDescription(NotifType type) {
        return All[type.ordinal()];
    }

    public static int cancelAllNotifs(NotifType type, Server curServer) {
        return cancelAllNotifs(type, curServer,"");
    }
    public static int cancelAllNotifs(NotifType type, Server curServer,String data) {
        ServerNotif sn=new ServerNotif(curServer,type,data);
        return sn.cancelAll();
    }
    //allows to index notifications for a given Server/type of notification
    static class ServerNotif {
        ServerNotif(Server s, NotifType t) {srv=s;type=t; data="";}
        ServerNotif(Server s, NotifType t,@NonNull String data) {srv=s;type=t; this.data=data;}
        Server srv;
        NotifType type;
        String data;
        public boolean equals(Object o) {
            return o instanceof ServerNotif && srv.getId()==(((ServerNotif)o).srv.getId())
                    && type == ((ServerNotif)o).type &&
                    data.equals(((ServerNotif)o).data);
        }
        @Override
        public int hashCode() {
            HashCodeBuilder hcb = new HashCodeBuilder();
            return hcb.append(srv.getId()).append(type).append(data).hashCode();
        }

        public void register(Instant basetime) {
            Set<Instant> timings = notifIndex.computeIfAbsent(this, k -> new HashSet<>());
            timings.add(basetime);
        }
        public void unregister(Instant basetime) {
            Set<Instant> timings=notifIndex.get(this);
            if(timings != null) {
                timings.remove(basetime);
                log.info("==> removing notif for "+this+" at "+basetime);
            }
        }

        public int cancelAll() {
            log.info("==> removing all notifs for "+this);
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
            }
            removeFromDB();
            notifIndex.put(this,new HashSet<>());
            return res;
        }
        public void removeFromDB() {
            try {
                synchronized (_Q.deleteNotif) {
                    PreparedStatement n = data.equals("") ? _Q.deleteNotif:_Q.deleteDataNotif ;
                    n.setLong(1, srv.getId());
                    n.setString(2, type.name());
                    if(!data.equals("")) n.setString(3,data);
                    n.executeUpdate();
                }
            } catch (SQLException e) {
                log.error("Notif db delete error", e);
                SosBot.checkDBConnection();
            }
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
        public void createInDb(String data) {
            try {
                synchronized (_Q.insertNotif) {
                    PreparedStatement n = _Q.insertNotif;
                    n.setLong(1, srv.getId());
                    n.setString(2, type.name());
                    n.setTimestamp(3, Timestamp.from(time));
                    n.setString(4,data);
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

    }

    static void initFromDb(Server srv) {
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
                _Q.getAllNotifs.setLong(1,srv.getId());
                ResultSet rs=_Q.getAllNotifs.executeQuery();
                while(rs.next()) {
                    final Instant inst = rs.getTimestamp("basetime").toInstant();
                    final NotifType nt=NotifType.valueOf(rs.getString("notif"));
                    final String dataStr=rs.getString("data");
                    final Notification<?> notif=All[nt.ordinal()];
                    log.info("notif "+notif);
                    notif.scheduleNotif(nt,
                            srv,
                            inst,
                            false,
                            false,
                            dataStr);
                }
            }
        } catch(SQLException e) {
            log.error("Notif init DB error for",e);

        }
    }
    private static queries _Q;
    static void initQueries(Connection db) throws SQLException { _Q=new queries(db); }
    private static class queries {
        final PreparedStatement insertNotif,updateNotif,getAllNotifs,deleteNotif,deleteDataNotif;
        queries(Connection db) throws SQLException {
            insertNotif = db.prepareStatement("insert into notifications(server,notif,basetime,data) values(?,?,?,?)");
            updateNotif = db.prepareStatement("update notifications set basetime=? where server=? and notif=?");
            getAllNotifs =db.prepareStatement( "select * from notifications where server=?");
            deleteNotif=db.prepareStatement( "delete from notifications where server=? and notif=?");
            deleteDataNotif=db.prepareStatement( "delete from notifications where server=? and notif=? data=?");
        }
    }

    static class NotificationInput<T> {
        NotificationInput(Duration before, Instant basetime, Server s) {
            this.before=before; this.basetime=basetime; server=s;data=null;
        }
        NotificationInput(Duration before, Instant basetime, Server s,T data) {
            this.before=before; this.basetime=basetime; server=s;this.data = data;}
        Duration before;
        Instant basetime;
        Server server;
        T data;
    }
}
interface dbready {
    String serialize();
}
class Empty implements dbready {
    @Override
    public String serialize() {
        return null;
    }
}
