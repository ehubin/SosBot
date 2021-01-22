package sosbot;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang.builder.HashCodeBuilder;


import java.sql.*;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

@Slf4j
public class AnalysisCenter implements dbready {



    enum Type { Battle,Weapon,Construction,Production,Gathering,Research,Vehicle}


    private static final HashMap<Long,Set<AnalysisCenter>> ACMap=new HashMap<>();
    long dbid;
    Type type;
    int level;
    Server server;
    boolean ours;
    boolean challenged;
    Instant nextChange;
    public String toString() {
        return (ours?"\uD83D\uDC4D ":"\uD83D\uDC4E ")+"Lvl "+level+"\t"+type+ " AC "+(challenged?"\u2694\n":"\uD83D\uDEE1\n")
                +(challenged?"In danger until: ":"Up for grabs from: ")+Util.dayDuration.format(nextChange);
    }
    private AnalysisCenter(Type t, int lvl, Server server, boolean challenged, boolean ours, Instant next) {
        type=t;
        level=lvl;
        this.server=server;
        this.challenged=challenged;
        this.ours=ours;
        this.nextChange=next;
    }
    @Override
    public boolean equals(Object o) {
        if(!(o instanceof AnalysisCenter)) return false;
        AnalysisCenter oth =(AnalysisCenter) o;
        return level==oth.level && type==oth.type && server.equals(oth.server);

    }
    @Override
    public int hashCode() {
        HashCodeBuilder hcb=new HashCodeBuilder();
        return hcb.append(level).append(server.getId()).append(type.ordinal()).hashCode();
    }
    public Instant nextDefend() {
        return challenged? nextChange.plus(Duration.ofDays(3L)):nextChange;
    }


    static void initFromDb(Server s) {
        try {
            synchronized (_Q.getAll) {
                _Q.getAll.setLong(1, s.getId());
                ResultSet rs=_Q.getAll.executeQuery();

                while(rs.next()) {
                    AnalysisCenter ac=new AnalysisCenter(
                            Type.values()[rs.getInt("type")],
                            rs.getInt("level"),
                            s,
                            rs.getBoolean("challenged"),
                            rs.getBoolean("ours"),
                            Instant.ofEpochMilli(rs.getTimestamp("next").getTime())
                            );
                    ac.dbid=rs.getLong("id");
                    Set<AnalysisCenter> set=ACMap.computeIfAbsent(s.getId(), k -> new LinkedHashSet<>());
                    set.add(ac);
                }
            }
        } catch(SQLException e) {
            log.error("AC DB retrieve issue",e);
        }

    }
    static AnalysisCenter create(Type t, int lvl, Server server, boolean challenged, boolean ours, Instant next) throws SQLException{
        AnalysisCenter res=new AnalysisCenter(t,lvl,server,challenged,ours,next);
        if(res.alreadyExists()) throw new RecoverableError("Error: Analysis center "+res+"\nalready exists");
        synchronized(_Q.create) {
            PreparedStatement q = _Q.create;
            q.setLong(1, server.getId());
            q.setInt(2, t.ordinal());
            q.setInt(3, lvl);
            q.setBoolean(4, challenged);
            q.setBoolean(5, ours);
            q.setTimestamp(6, Timestamp.from(next));
            q.executeUpdate();
            ResultSet keys = q.getGeneratedKeys();
            keys.next();
            res.dbid = keys.getLong("id");
        }
        long id=server.getId();
        ACMap.computeIfAbsent(id, k -> new HashSet<>());
        ACMap.get(id).add(res);
        return res;
    }

    private boolean alreadyExists() {
        return ACMap.getOrDefault(server.getId(),Set.of()).contains(this);
    }

    boolean setState(boolean ours,long nbOfSeconds) {
        long theNext=System.currentTimeMillis()+nbOfSeconds*1000;
        try {
            synchronized(_Q.update) {
                PreparedStatement q = _Q.update;
                q.setBoolean(1, ours);
                q.setTimestamp(2, new Timestamp(theNext));
                q.setLong(3, dbid);
                q.executeUpdate();
            }
        } catch(SQLException e) {
            SosBot.checkDBConnection();
            log.error("failed in AC update for "+this,e);
            return false;
        }
        this.ours=ours;
        this.challenged=false;
        nextChange= Instant.ofEpochMilli(theNext);
        return true;
    }

    static Set<AnalysisCenter> getAll(Server srv) {
        return ACMap.getOrDefault(srv.getId(), Set.of());
    }

    boolean delete() {
        try {
            synchronized(_Q.delete) {
                PreparedStatement q = _Q.delete;
                q.setLong(1, dbid);
                q.executeUpdate();
            }
        } catch(SQLException e) {
            log.error("failed in AC delete for "+this,e);
            SosBot.checkDBConnection();
            return false;
        }
        ACMap.get(server.getId()).remove(this);
        return true;
    }

    @Override
    public String serialize() {
        return Long.toString(dbid);
    }


    public static AnalysisCenter buildFrom(String dbString) {
        long dbid=Long.parseLong(dbString);
        for(Set<AnalysisCenter> s:ACMap.values())
            for(AnalysisCenter ac:s)
                if(ac.dbid==dbid) return ac;

        return null;
    }

    private static queries _Q;
    static void initQueries(Connection db) throws SQLException { _Q=new queries(db); }
    static class queries  {
        final PreparedStatement create,update,delete,getAll;
        queries(Connection db)  throws SQLException {
            create = db.prepareStatement("insert into analysiscenters(server,type,level,challenged,ours,next) values(?,?,?,?,?,?) returning id", Statement.RETURN_GENERATED_KEYS);
            update = db.prepareStatement( "UPDATE analysiscenters set ours=?,challenged='f',next=? where id=?");
            delete = db.prepareStatement( "DELETE FROM analysiscenters  where id=?");
            getAll = db.prepareStatement( "select * FROM analysiscenters  where server=?");
        }

    }
}
