
import discord4j.core.object.entity.Member;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.stream.Collectors;

public class Participant {
    private static final Logger _logger = LoggerFactory.getLogger(Participant.class);
    boolean isDiscord;
    long uid;
    final private String name; //for local users only
    Member member;
    Server server;
    float power=0.0f;
    boolean registeredToRR =false;
    int RRteamNumber =-1;
    SDPos lane= SDPos.Undef;
    // create a discord-based participant
    Participant(Member m,Server s) {
        member=m; server=s;isDiscord=true;
        name=null;
        uid=member.getId().asLong();
    }
    //create a non-discord participant for the first time
    Participant(String n,Server s) {
        member=null; server=s;isDiscord=false;
        name=n;
        uid=generateUID(n);
    }
    public boolean isR4() {
        return isDiscord && member.getRoleIds().contains(server.R4roleId);
    }
    //create a non-discord participant from existing uid
    Participant(String n,long uid,Server s) {
        member=null; server=s;isDiscord=false;
        name=n;
        this.uid=uid;
    }
    static private final long offset=(2020L-1970L)*31536000L*1000L;
    private long generateUID(String n) {
        long ts=System.currentTimeMillis()-offset;
        long str= n.hashCode()%8388593;
        if(str<0) str=-str;
        return (ts<<23)|str;
    }

    public String toString() { return getName()+"\t"+power;}
    public String getName() { return isDiscord? member.getDisplayName():name;}
    public long getGuildId() { return server.getId();}
    public long getUid() { return uid;}

    boolean create() {
        try {
            synchronized (_Q.insertP) {
                PreparedStatement insertP=_Q.insertP;
                insertP.setString(1, getName());
                insertP.setFloat(2, power);
                insertP.setLong(3, getGuildId());
                insertP.setInt(4, RRteamNumber);
                insertP.setInt(5, lane.ordinal());
                insertP.setLong(6, uid);
                insertP.setBoolean(7, registeredToRR);
                insertP.setBoolean(8, isDiscord);
                insertP.executeUpdate();
            }
        } catch(SQLException e) {
            e.printStackTrace();
            SosBot.checkDBConnection();
            return false;
        }
        return true;
    }

    boolean update() {
        try {
            synchronized (_Q.updateP) {
                PreparedStatement updateP=_Q.updateP;
                updateP.setString(1, getName());
                updateP.setFloat(2, power);
                updateP.setInt(3, RRteamNumber);
                updateP.setInt(4, lane.ordinal());
                updateP.setBoolean(5, registeredToRR);
                updateP.setBoolean(6, isDiscord);
                updateP.setLong(7, uid);
                updateP.setLong(8, getGuildId());
                updateP.executeUpdate();
            }
        } catch(SQLException e) {
            e.printStackTrace();
            SosBot.checkDBConnection();
            return false;
        }
        return true;
    }

    static  boolean delete(long uid,long serverid) {
        synchronized(_Q.deleteOne) {
            try {
                _Q.deleteOne.setLong(1, serverid);
                _Q.deleteOne.setLong(2, uid);
                _Q.deleteOne.executeUpdate();
            } catch(SQLException s) {
                _logger.error("Error while deleting "+uid,s);
                SosBot.checkDBConnection();
                return false;
            }
            return true;
        }
    }
    boolean setRRregistered(boolean b) {
        try{
            synchronized (_Q.updateRRreg) {
                PreparedStatement updateRRreg=_Q.updateRRreg;
                updateRRreg.setBoolean(1, b);
                updateRRreg.setFloat(2, power);
                updateRRreg.setLong(3, getGuildId());
                updateRRreg.setLong(4, getUid());
                updateRRreg.executeUpdate();
            }
        }catch(SQLException se) {
            se.printStackTrace();
            SosBot.checkDBConnection();
            return false;
        }
        registeredToRR=b;
        return true;
    }

    public boolean updateRRTeam(int teamNb) {
        try {
            synchronized (_Q.updateRRTeam) {
                PreparedStatement updateRRTeam=_Q.updateRRTeam;
                updateRRTeam.setInt(1, teamNb);
                updateRRTeam.setString(2, getName());
                updateRRTeam.setLong(3, getGuildId());
                updateRRTeam.setLong(4, getUid());
                updateRRTeam.executeUpdate();
            }
        } catch(SQLException se) {
            se.printStackTrace();
            SosBot.checkDBConnection();
            return false;
        }
        RRteamNumber =teamNb;
        return true;
    }
    public boolean swap(Participant o) {
        int curTeam= RRteamNumber;
        return (updateRRTeam(o.RRteamNumber)&&o.updateRRTeam(curTeam));
    }
    void decideSDLane(Server s) {
        List<Participant> sdguys=s.sessions.values().stream().filter(p->p.lane!= SDPos.Undef).collect(Collectors.toList());
        if (power < s.Sd.threshold) {
            lane= SDPos.Right;
        } else {
            double left=0.0,center=0.0;
            for(Participant p:sdguys) {
                if(p.lane== SDPos.Left) left+=p.power;
                else center+=p.power;
            }
            lane = left>center ? SDPos.Center : SDPos.Left;
        }
    }
    boolean saveSD() {
        try {
            synchronized (_Q.updateSDLane) {
                PreparedStatement updateSDLane = _Q.updateSDLane;
                updateSDLane.setInt(1, lane.ordinal());
                updateSDLane.setFloat(2, power);
                updateSDLane.setLong(3, getGuildId());
                updateSDLane.setLong(4, getUid());
                updateSDLane.executeUpdate();
            }
        } catch(SQLException se) {
            se.printStackTrace();
            SosBot.checkDBConnection();
            return false;
        }
        return true;
    }

    public double getPower() { return power;}

    private static queries _Q;
    static void initQueries(Connection db) throws SQLException { _Q=new Participant.queries(db); }
    private static class queries  {
        final PreparedStatement insertP,updateP,updateRRreg,updateRRTeam,updateSDLane,deleteOne;
        queries(Connection db)  throws SQLException {
            insertP = db.prepareStatement("INSERT INTO members(name,power,server,team,lane,uid,rr,isdiscord) VALUES(?,?,?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS);
            updateP = db.prepareStatement("UPDATE members set name=?,power=?,team=?,lane=?,rr=?,isdiscord=? where uid=? and server=?", Statement.RETURN_GENERATED_KEYS);
            updateRRreg =  db.prepareStatement("UPDATE  members set rr=?,power=? where server=? and uid=?");
            updateRRTeam =  db.prepareStatement("UPDATE  members set team=?,name=? where server=? and uid=?");
            updateSDLane =  db.prepareStatement("UPDATE  members set lane=?,power=? where server=? and uid=?");
            deleteOne = db.prepareStatement("DELETE  from members  where server=? and uid=?");
        }

    }
    static class data {
        boolean registeredToRR;
        SDPos lane;
        float power;
        int RRteam;
    }
}