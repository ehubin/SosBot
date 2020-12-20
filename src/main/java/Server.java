import discord4j.common.util.Snowflake;
import discord4j.core.object.entity.Guild;
import discord4j.core.object.entity.Member;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class Server {
    private static final Logger _logger = LoggerFactory.getLogger(Server.class);

    Guild guild;
    pingBot.RREvent RRevent,newRRevent;
    pingBot.SDEvent Sd;
    HashMap<Long, Participant> sessions = new HashMap<>();

    public Server(Guild guild) {
        this.guild=guild;
        RRevent= new pingBot.RREvent(guild);
        newRRevent= new pingBot.RREvent(guild);
    }

    public long getId() {
        return guild.getId().asLong();
    }
    List<Participant> getRegisteredRRparticipants() {
        return  sessions.values().stream().filter(i -> i.registeredToRR)
                .sorted(Comparator.comparingDouble(Participant::getPower).reversed())
                .collect(Collectors.toList());

    }
    Stream<Participant> getRegisteredSDparticipants() {
        return  sessions.values().stream().filter(i -> i.lane != pingBot.SDPos.Undef)
                .sorted(Comparator.comparingDouble(Participant::getPower).reversed());
    }

    ArrayList<ArrayList<Participant>> getRRSavedTeams() {
        List<Participant>list=sessions.values().stream().filter(i -> i.registeredToRR)
                .sorted(Comparator.comparingDouble(Participant::getPower).reversed())
                .collect(Collectors.toList());
        ArrayList<ArrayList<Participant>> res = new ArrayList<>();
        for(Participant p:list) {
            while(res.size()<p.RRteamNumber) res.add(new ArrayList<>());
            res.get(p.RRteamNumber -1).add(p);
        }
        res.sort((ArrayList<Participant> t1, ArrayList<Participant> t2)->
                Float.compare(teamPow(t2), teamPow(t1)));
        return res;
    }
    ArrayList<ArrayList<Participant>> getRRTeams(int nbTeam, List<Participant> registered) {
        if(registered.size()==0) return null;
        if(registered.size()<nbTeam) nbTeam=registered.size();
        ArrayList<ArrayList<Participant>> teams = new ArrayList<>();
        int[] power = new int[nbTeam];
        for (int i = 0; i < nbTeam; ++i) {
            teams.add(new ArrayList<>());
        }
        for (Participant p : registered) {
            int best = 0, min = power[0];
            for (int i = 1; i < nbTeam; ++i)
                if (power[i] < min) {
                    min = power[i];
                    best = i;
                }
            teams.get(best).add(p);
            power[best] += p.power;
        }
        teams.sort((ArrayList<Participant> t1, ArrayList<Participant> t2)->
                Float.compare(teamPow(t2), teamPow(t1)));
        return teams;
    }
    static float teamPow(ArrayList<Participant> t) {
        float res=0f;
        for(Participant p:t) res+= p.power;
        return res;
    }

    static StringBuilder displayTeams(ArrayList<ArrayList<Participant>> teams) {
        StringBuilder sb = new StringBuilder();
        sb.append("```");
        int nbTeam= teams.size();
        for (int i = 0; i < nbTeam; ++i) {
            sb.append("Team ").append(i + 1).append(" (").append(teamPow(teams.get(i))).append(")\n");
            int j=0;
            for (Participant p : teams.get(i)) {
                sb.append(++j).append(". ").append(p.getName()).append(" (").append(p.power).append(")\n");
            }
            sb.append("\n");
        }
        sb.append("```");
        return sb;
    }

    StringBuilder getSDLanesString() {
        final StringBuilder sb=new StringBuilder("```");
        final AtomicReference<pingBot.SDPos> lane= new AtomicReference<>(pingBot.SDPos.Undef);
        getRegisteredSDparticipants().sorted(Comparator.comparing((Participant p) -> p.lane.ordinal()).reversed()
                .thenComparing(p -> p.power).reversed()).forEachOrdered(p -> {
            if(!p.lane.equals(lane.get())) {
                lane.set(p.lane);
                sb.append("\n>>>").append(p.lane).append("\n");
            }
            sb.append(p.getName()).append(" (").append(p.power).append(")\n");
        });
        sb.append("\n```");
        return sb;
    }
    boolean unregisterRR() {
        try {
            synchronized (_Q.deleteLocalRRParticipants) {
                _Q.deleteLocalRRParticipants.setLong(1, getId());
                _Q.deleteLocalRRParticipants.executeUpdate();
            }
            synchronized (_Q.RRunregAll) {
                _Q.RRunregAll.setLong(1, getId());
                _Q.RRunregAll.executeUpdate();
            }
        }catch(SQLException se) {
            se.printStackTrace();
            return false;
        }
        sessions.values().removeIf(p->p.getGuildId()==getId() && !p.isDiscord && p.registeredToRR && p.lane== pingBot.SDPos.Undef);
        for(Participant p:sessions.values()) {
            if(p.isDiscord && p.getGuildId()==getId()) p.registeredToRR=false;
        }
        return true;
    }
    Participant createNewDiscordParticipant(Member m) {
        Participant newby = new Participant(m,guild);
        if(newby.save()) {
            sessions.put(newby.uid,newby);
            return newby;
        }
        return null;
    }
    @SuppressWarnings("SameParameterValue")
    Participant createRRParticipant(String name, float power, boolean rr) {
        Participant newbie = new Participant(name,guild);
        newbie.setRRregistered(rr);
        newbie.power=power;
        if(newbie.save()) {
            sessions.put(newbie.uid,newbie);
            return newbie;
        }
        return null;
    }
    void initFromDB() {
        try {
            ResultSet rs;
            synchronized (_Q.selectRRevent) {
                _Q.selectRRevent.setLong(1, getId());
                rs = _Q.selectRRevent.executeQuery();
            }
            if(rs.next()) { // read first event
                pingBot.RREvent e=new pingBot.RREvent(guild);
                e.date=rs.getTimestamp("rrdate");
                e.active=rs.getBoolean("active");
                e.teamSaved=rs.getBoolean("teamsaved");
                RRevent = e;

                pingBot.SDEvent se = new pingBot.SDEvent(guild);
                se.active = rs.getBoolean("sdactive");
                se.threshold = rs.getFloat("sdthreshold");
                Sd = se;

            } else { //server is not in db
                RRevent=new pingBot.RREvent(guild);
                // first time event with empty DB is inactive
                RRevent.active=false;
                Sd = new pingBot.SDEvent(guild);
                Sd.active=false;
                synchronized (_Q.createServer) {
                    PreparedStatement createServer=_Q.createServer;
                    createServer.setLong(1, getId());
                    createServer.setBoolean(2, RRevent.active);
                    createServer.setBoolean(3, RRevent.teamSaved);
                    createServer.setBoolean(4, Sd.active);
                    createServer.setFloat(5, Sd.threshold);
                    createServer.setTimestamp(6, new Timestamp(RRevent.date.getTime()));
                    createServer.executeUpdate();
                }
            }
            synchronized (_Q.selectParticipants) {
                _Q.selectParticipants.setLong(1, getId());
                rs = _Q.selectParticipants.executeQuery();
                while (rs.next()) {
                    long uid = rs.getLong("uid");
                    Participant p = sessions.get(uid);
                    if (p == null) {
                        boolean isDiscord = rs.getBoolean("isdiscord");
                        if (isDiscord) {
                            Member m = guild.getMemberById(Snowflake.of(uid)).onErrorContinue((t, e) -> t.printStackTrace()).block(pingBot.BLOCK);
                            if (m == null) {
                                System.err.println("Error retrieving member for " + uid);
                                continue;
                            }
                            p = new Participant(m, guild);
                        } else {
                            p = new Participant(rs.getString("name"), uid, guild);
                        }
                        sessions.put(uid, p);

                    }
                    p.registeredToRR = rs.getBoolean("rr");
                    p.power = rs.getFloat("power");
                    p.RRteamNumber = rs.getInt("team");
                    p.lane = pingBot.SDPos.values()[rs.getInt("lane")];
                }
            }

        } catch(SQLException e) {
            e.printStackTrace();
        }
    }

    public Participant createSDParticipant(String name, float pow, pingBot.SDPos lane) {
        Participant newbie = new Participant(name,guild);
        newbie.lane=lane;
        newbie.power=pow;
        if(newbie.save()) {
            sessions.put(newbie.uid,newbie);
            return newbie;
        }
        return null;
    }

    private static queries _Q;
    static void initQueries(Connection db) throws SQLException { _Q=new queries(db); }
    private static class queries  {
        final PreparedStatement deleteLocalRRParticipants,deleteLocalSDParticipants ,RRunregAll,selectParticipants,
                selectRRevent,createServer;
        queries(Connection dbConnection)  throws SQLException {
            selectParticipants  = dbConnection.prepareStatement("SELECT * FROM members where server=?");
            deleteLocalRRParticipants =  dbConnection.prepareStatement("DELETE  from members where server=? and isdiscord='f' and lane='0' and rr='t' ");
            deleteLocalSDParticipants =  dbConnection.prepareStatement("DELETE  from members where server=? and isdiscord='f' and lane!='0' and rr='f' ");
            RRunregAll = dbConnection.prepareStatement("UPDATE  members set rr='f', team='-1' where server=?");
            selectRRevent = dbConnection.prepareStatement("SELECT * FROM servers where server=?");
            createServer = dbConnection.prepareStatement("INSERT INTO servers(server,active,teamsaved,sdactive,sdthreshold,rrdate) VALUES(?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS);
        }

    }
}
