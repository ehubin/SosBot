import com.joestelmach.natty.Parser;
import discord4j.core.object.entity.Guild;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;


public class AnalysisCenter {
    private static final Logger _logger = LoggerFactory.getLogger(Parser.class);


    enum Type { Battle,Weapon,Construction,Production,Gathering,Research,Vehicle};
    Type type;
    int level;
    Guild guild;
    boolean challenged;
    long nextChange;
    public String toString() {
        return "Level"+level+" "+type+ " analysis center "+(challenged?"\uD83D\uDCA6":"\uD83D\uDEE1");
    }
    boolean create(Type t,int lvl,Guild g) {
        type=t;
        level=lvl;
        guild=g;
        try {
            synchronized(_Q.create) {
                PreparedStatement q = _Q.create;
                q.setLong(1, guild.getId().asLong());
                q.setInt(2, type.ordinal());
                q.setInt(3, level);
                q.setBoolean(4, challenged);
                q.setTimestamp(5, new Timestamp(nextChange));
                q.executeUpdate();
            }
        } catch(SQLException e) {
            _logger.error("failed in AC create",e);
            return false;
        }
        return true;
    }

    boolean setState(boolean challenged,long nbOfSeconds) {
        long theNext=System.currentTimeMillis()+nbOfSeconds*1000;
        try {
            synchronized(_Q.update) {
                PreparedStatement q = _Q.update;
                q.setBoolean(1, challenged);
                q.setTimestamp(2, new Timestamp(theNext));
                q.setLong(3, guild.getId().asLong());
                q.setInt(4, type.ordinal());
                q.setInt(5, level);
                q.executeUpdate();
            }
        } catch(SQLException e) {
            _logger.error("failed in AC update for "+this,e);
            return false;
        }
        this.challenged=challenged;
        nextChange= theNext;
        return true;
    }

    boolean delete() {
        try {
            synchronized(_Q.delete) {
                PreparedStatement q = _Q.delete;
                q.setLong(1, guild.getId().asLong());
                q.setInt(2, type.ordinal());
                q.setInt(3, level);
                q.executeUpdate();
            }
        } catch(SQLException e) {
            _logger.error("failed in AC delete for "+this,e);
            return false;
        }
        return true;
    }

    private static queries _Q;
    static void initQueries(Connection db) throws SQLException { _Q=new queries(db); }
    static class queries  {
        final PreparedStatement create,update,delete;
        queries(Connection db)  throws SQLException {
            create = db.prepareStatement("insert into analysiscenters(server,type,level,challenged,next) values(?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS);
            update = db.prepareStatement( "UPDATE analysiscenters set challenged=?,next=? where server=? and type=? and level=?",Statement.RETURN_GENERATED_KEYS );
            delete = db.prepareStatement( "DELETE FROM analysiscenters  where server=? and type=? and level=?",Statement.RETURN_GENERATED_KEYS );
        }

    }
}
