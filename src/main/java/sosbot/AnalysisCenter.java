package sosbot;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang.builder.HashCodeBuilder;


import java.sql.*;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

@Slf4j
public class AnalysisCenter {
    enum Type { Battle,Weapon,Construction,Production,Gathering,Research,Vehicle}


    private static final HashMap<Long,Set<AnalysisCenter>> ACMap=new HashMap<>();

    Type type;
    int level;
    Server server;
    boolean ours;
    boolean challenged;
    Instant nextChange;
    public String toString() {
        return "Level"+level+" "+type+ " analysis center "+(challenged?"\u2694":"\uD83D\uDEE1");
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
    static AnalysisCenter create(Type t, int lvl, Server server, boolean challenged, boolean ours, Instant next) throws SQLException{
        AnalysisCenter res=new AnalysisCenter(t,lvl,server,challenged,ours,next);
        if(res.alreadyExists()) throw new RecoverableError("Analysis center "+res+"already exists");
        synchronized(_Q.create) {
            PreparedStatement q = _Q.create;
            q.setLong(1, server.getId());
            q.setInt(2, t.ordinal());
            q.setInt(3, lvl);
            q.setBoolean(4, challenged);
            q.setBoolean(5, ours);
            q.setTimestamp(6, Timestamp.from(next));
            q.executeUpdate();
        }
        long id=server.getId();
        ACMap.computeIfAbsent(id, k -> new HashSet<>());
        ACMap.get(id).add(res);
        return res;
    }

    private boolean alreadyExists() {
        return ACMap.getOrDefault(server.getId(),Set.of()).contains(this);
    }

    boolean setState(boolean challenged,long nbOfSeconds) {
        long theNext=System.currentTimeMillis()+nbOfSeconds*1000;
        try {
            synchronized(_Q.update) {
                PreparedStatement q = _Q.update;
                q.setBoolean(1, challenged);
                q.setTimestamp(2, new Timestamp(theNext));
                q.setLong(3, server.getId());
                q.setInt(4, type.ordinal());
                q.setInt(5, level);
                q.executeUpdate();
            }
        } catch(SQLException e) {
            log.error("failed in AC update for "+this,e);
            return false;
        }
        this.challenged=challenged;
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
                q.setLong(1, server.getId());
                q.setInt(2, type.ordinal());
                q.setInt(3, level);
                q.executeUpdate();
            }
        } catch(SQLException e) {
            log.error("failed in AC delete for "+this,e);
            return false;
        }

        return true;
    }

    private static queries _Q;
    static void initQueries(Connection db) throws SQLException { _Q=new queries(db); }
    static class queries  {
        final PreparedStatement create,update,delete,getAll;
        queries(Connection db)  throws SQLException {
            create = db.prepareStatement("insert into analysiscenters(server,type,level,challenged,ours,next) values(?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS);
            update = db.prepareStatement( "UPDATE analysiscenters set challenged=?,next=? where server=? and type=? and level=?",Statement.RETURN_GENERATED_KEYS );
            delete = db.prepareStatement( "DELETE FROM analysiscenters  where server=? and type=? and level=?",Statement.RETURN_GENERATED_KEYS );
            getAll = db.prepareStatement( "select * FROM analysiscenters  where server=?");
        }

    }
}
