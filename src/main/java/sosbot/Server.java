package sosbot;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import discord4j.common.util.Snowflake;
import discord4j.core.object.entity.Guild;
import discord4j.core.object.entity.Member;
import discord4j.core.object.entity.channel.MessageChannel;
import discord4j.core.object.entity.channel.TextChannel;
import discord4j.core.retriever.EntityRetrievalStrategy;
import discord4j.rest.util.Color;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang.builder.HashCodeBuilder;

import reactor.core.publisher.Mono;
import reactor.util.retry.RetryBackoffSpec;

import java.sql.*;

import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import java.util.Date;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import java.util.stream.Collectors;
import java.util.stream.Stream;


enum SDPos { Undef,Left,Center,Right}

@Slf4j
public class Server {
    private static final HashMap<Snowflake,Server> KnownServers=new HashMap<>();
    private static final ConcurrentHashMap<Snowflake,Boolean> lockMap = new ConcurrentHashMap<>();

    private final HashMap<ChannelPartKey, NCommand.followupOld> followUpCmd=new HashMap<>();
    private final HashMap<ChannelPartKey, NCommand.followup> FollowupNCommand=new HashMap<>();
    Guild guild;
    Snowflake R4roleId=null;
    RREvent RRevent,newRRevent;
    SDEvent Sd;
    boolean CCactive=false;
    Instant CCstart=null;
    protected HashMap<Long, Participant> sessions = new HashMap<>();

    public Server(Guild guild) {
        this.guild=guild;
        RRevent= new RREvent(guild);
        newRRevent= new RREvent(guild);
    }
    @Override
    public boolean equals(Object o) {
        if(o instanceof Server) {
            Server other =(Server)o;
            return guild.getId().equals(other.guild.getId());
        }
        return false;
    }
    private static final RetryBackoffSpec getServerRetryPolicy =RetryBackoffSpec
            .backoff(4, Duration.ofMillis(100))
            .filter((t)->t instanceof ServerInitInProgress);

    @SuppressWarnings("unused")
    public static Mono<Server> getServerFromId(long id) {
        return getInternalServerFromId(Snowflake.of(id)).retryWhen(getServerRetryPolicy);
    }
    public static Mono<Server> getServerFromId(Snowflake id) {
        return getInternalServerFromId(id).retryWhen(getServerRetryPolicy);
    }
    private static Mono<Server> getInternalServerFromId(Snowflake id) {
        //log.info("stacktrace dump",new Exception());
        if(!KnownServers.containsKey(id)) {
            if (lockMap.putIfAbsent(id, true) == null) { // i am first i do the init
                return SosBot.getDiscordGateway().getGuildById(id).onErrorResume(e -> {
                    if (e.getMessage().matches("GET.*message=Missing Access.*")) {
                        log.warn("ignoring guild permission error for id=" + id + " not a big deal");
                        return Mono.empty();
                    } else {
                        log.error("Unexpected error while trying to retrieve guild data for id=" + id);
                        return Mono.error(e);
                    }
                })
                .flatMap(g -> {
                    log.info("Initializing "+g.getName());
                    Server newOne = new Server(g);
                    KnownServers.put(id, newOne);
                    Mono<Void> dbInit = newOne.initFromDB();
                    return dbInit.then(Mono.just(newOne));
                });
            } else { // somebody already initializing the server need to wait and retry
                log.info("init conflicts");
                return Mono.error(new ServerInitInProgress());
            }
        } else return Mono.just(KnownServers.get(id)); //server already known just return it
    }

    public long getId() {
        return guild.getId().asLong();
    }


    List<Participant> getRegisteredRRparticipants(boolean reversed) {
        Comparator<Participant> comp= Comparator.comparingDouble(Participant::getPower);
        if(reversed) comp=comp.reversed();
        return  sessions.values().stream().filter(i -> i.registeredToRR)
                .sorted(comp).collect(Collectors.toList());

    }
    Stream<Participant> getRegisteredSDparticipants() {
        return  sessions.values().stream().filter(i -> i.lane != SDPos.Undef)
                .sorted(Comparator.comparingDouble(Participant::getPower).reversed());
    }
    @SuppressWarnings("SameParameterValue")
    Stream<Participant> getRegisteredCCparticipants(boolean reversed) {
        Comparator<Participant> comp= Comparator.comparingDouble(Participant::getPower);
        if(reversed) comp=comp.reversed();
        return sessions.values().stream().filter(i -> i.registeredToCC)
                .sorted(comp);
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
    ArrayList<ArrayList<Participant>> getRRTeams(int nbTeam) {
        List<Participant> registered = getRegisteredRRparticipants(true);
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

    boolean openCC(Instant start) {
        try{
            synchronized (_Q.openCC) {
                _Q.openCC.setTimestamp(1,Timestamp.from(start));
                _Q.openCC.setLong(2,getId());
                _Q.openCC.executeUpdate();
                _Q.CCunregAll.setLong(1,getId());
                _Q.CCunregAll.executeUpdate();
            }
        } catch(SQLException e) {
            SosBot.checkDBConnection();
            log.error("error openning cc",e);
            return false;
        }
        getRegisteredCCparticipants(false).forEach(p->p.registeredToCC=false);
        CCactive=true;
        CCstart=start;
        return true;
    }

    boolean closeCC() {
        try{
            synchronized (_Q.closeCC) {
                _Q.closeCC.setLong(1,getId());
                _Q.closeCC.executeUpdate();
            }
        } catch(SQLException e) {
            SosBot.checkDBConnection();
            log.error("error closing cc",e);
            return false;
        }
        CCactive=false;
        CCstart=null;
        return true;
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
        final AtomicReference<SDPos> lane= new AtomicReference<>(SDPos.Undef);
        getRegisteredSDparticipants().sorted(Comparator.comparing((Participant p) -> p.lane.ordinal()).reversed()
                .thenComparing(p -> p.power).reversed()).forEachOrdered(p -> {
            if(!p.lane.equals(lane.get())) {
                lane.set(p.lane);
                sb.append("\n>>>").append(p.lane).append("\n");
            }
            sb.append(p.getName()).append(" (").append(p.power).append(")\n");
        });
        if(sb.length()==3) sb.append("No-one registered yet");
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
            SosBot.checkDBConnection();
            return false;
        }
        sessions.values().removeIf(p->p.getGuildId()==getId() && !p.isDiscord && p.registeredToRR && p.lane== SDPos.Undef);
        for(Participant p:sessions.values()) {
            if(p.isDiscord && p.getGuildId()==getId()) p.registeredToRR=false;
        }
        return true;
    }
    Participant createNewDiscordParticipant(Member m) {
        Participant newby = new Participant(m,this);
        if(newby.create()) {
            sessions.put(newby.uid,newby);
            return newby;
        } else {
            log.error("Database issue while creating user "+m.getDisplayName());
            SosBot.checkDBConnection();
            return null;
        }
    }
    @SuppressWarnings("SameParameterValue")
    Participant createRRParticipant(String name, float power, boolean rr) {
        Participant newbie = new Participant(name,this);
        newbie.setRRregistered(rr);
        newbie.power=power;
        if(newbie.create()) {
            sessions.put(newbie.uid,newbie);
            return newbie;
        }
        return null;
    }
    Mono<Void> initFromDB() {
        final Map<Long, Participant.data> newDiscordMembers= new HashMap<>();
        try {
            ResultSet rs;
            synchronized (_Q.selectRRevent) {
                _Q.selectRRevent.setLong(1, getId());
                rs = _Q.selectRRevent.executeQuery();
            }
            if(rs.next()) { // read first event
                RREvent e=new RREvent(guild);
                e.date=rs.getTimestamp("rrdate").toInstant();
                e.active=rs.getBoolean("active");
                e.teamSaved=rs.getBoolean("teamsaved");
                RRevent = e;

                SDEvent se = new SDEvent(guild);
                se.active=rs.getBoolean("sdactive");
                se.threshold = rs.getFloat("sdthreshold");
                se.initLaneStatus(rs.getString("sdlanedata"));
                se.initEnemyStatus(rs.getString("enemylanedata"));
                Sd = se;
                CCactive=rs.getBoolean("ccactive");
                Date da=rs.getTimestamp("ccdate");
                CCstart=(da==null?null:da.toInstant());

            } else { //server is not in db
                RRevent=new RREvent(guild);
                // first time event with empty DB is inactive
                RRevent.active=false;
                Sd = new SDEvent(guild);
                synchronized (_Q.createServer) {
                    PreparedStatement createServer=_Q.createServer;
                    createServer.setLong(1, getId());
                    createServer.setBoolean(2, RRevent.active);
                    createServer.setBoolean(3, RRevent.teamSaved);
                    createServer.setFloat(4, Sd.threshold);
                    createServer.setTimestamp(5, new Timestamp(RRevent.date.toEpochMilli()));
                    createServer.executeUpdate();
                }
            }


            updateDiscordServerAtFirstConnection();


            synchronized (_Q.selectParticipants) {
                _Q.selectParticipants.setLong(1, getId());
                rs = _Q.selectParticipants.executeQuery();
                while (rs.next()) {
                    long uid = rs.getLong("uid");
                    Participant p = sessions.get(uid);
                    if (p == null) {

                        boolean isDiscord = rs.getBoolean("isdiscord");
                        final boolean regToRR = rs.getBoolean("rr");
                        final boolean regToCC = rs.getBoolean("cc");
                        final float pow= rs.getFloat("power");
                        final int RRteamNumber = rs.getInt("team");
                        final SDPos lane = SDPos.values()[rs.getInt("lane")];
                        if (isDiscord) {
                            Participant.data pd = new Participant.data();
                            pd.lane=lane;
                            pd.power=pow;
                            pd.registeredToRR=regToRR;
                            pd.registeredToCC=regToCC;
                            pd.RRteam=RRteamNumber;
                            newDiscordMembers.put(uid,pd);
                        } else { // non discord participant
                            p = new Participant(rs.getString("name"), uid, this);
                            p.registeredToRR = regToRR;
                            p.power = pow;
                            p.RRteamNumber =RRteamNumber;
                            p.registeredToCC=regToCC;
                            p.lane = lane;
                            sessions.put(uid, p);
                        }
                    }
                }
            }
            AnalysisCenter.initFromDb(this);
            Notification.initFromDb(this);

        } catch(SQLException e) {
            e.printStackTrace();
            SosBot.checkDBConnection();
        }
        //merge DB and discord data
        return guild.getMembers(EntityRetrievalStrategy.REST).doOnNext(m->{
            long uid=m.getId().asLong();
            log.info("Discord uid "+uid+", "+m.getDisplayName());
            Participant p;
            Participant.data pd;
            if(newDiscordMembers.containsKey(uid)) {
                p=new Participant(m,this);
                pd=newDiscordMembers.get(uid);
                p.lane=pd.lane;
                p.RRteamNumber=pd.RRteam;
                p.power=pd.power;
                p.registeredToRR=pd.registeredToRR;
                p.registeredToCC=pd.registeredToCC;
                sessions.put(uid,p);
                newDiscordMembers.remove(uid);
            } else { // user is in discord and not in DB (to be created later when he interacts)
                log.warn("Not yet in DB"+m.getDisplayName());
                Participant newOne=new Participant(m,this);
                if(!newOne.create()) log.error("Error creating "+m.getDisplayName()+" in DB");
            }

        }).thenEmpty(Mono.fromRunnable(() -> {
            for(long id:newDiscordMembers.keySet()) { // these users are in DB but not in discord
                log.warn("To be removed from DB "+id);
                if(Participant.delete(id,getId())) sessions.remove(id);
            }
        }));


    }
    private void updateDiscordServerAtFirstConnection() {
        Set<ChannelAndCommands> clist=ChannelAndCommands.getAllChannels();
        AtomicBoolean[] found=new AtomicBoolean[clist.size()];
        Arrays.setAll(found,(i)->new AtomicBoolean());

        AtomicReference<Snowflake> parentId=new AtomicReference<>();
        guild.getChannels().doOnNext(c->{
            int i=0;
            for(ChannelAndCommands cac:clist) {
                if(c.getName().equals(cac.getDefaulName())) {
                    found[i].set(true);
                    cac.setChannel(c instanceof TextChannel? (TextChannel)c:null,this);
                }
                ++i;
            }
            if(c.getName().equalsIgnoreCase("text channels")) {
                //System.out.println("found parent");
                parentId.set(c.getId());
            }
        }).then(Mono.fromRunnable(()-> {
                    int i = 0;
                    for (ChannelAndCommands cac : clist) {
                        if (!found[i++].get()) {
                            guild.createTextChannel(c -> {
                                c.setName(cac.getDefaulName());
                                c.setTopic(cac.getTopic());
                                if (parentId.get() != null) c.setParentId(parentId.get());
                            }).doOnError(Throwable::printStackTrace).subscribe((c) -> {
                                cac.setChannel(c,this);
                                log.info(cac.getDefaulName() + " channel successfully created for " + guild.getName());
                            });
                        }
                    }
                })).subscribe();

        //create R4 role if it does not already exists
        AtomicBoolean foundR4 = new AtomicBoolean(false);
        guild.getRoles().doOnNext(r->{
            if(r.getName().equals("R4")) {
                foundR4.set(true);
                R4roleId = r.getId();
            }

        }).then(Mono.fromRunnable(()-> {
            if (!foundR4.get()) {
                System.out.println("Creating R4 role for " + guild.getName());
                guild.createRole(rcs -> {
                    rcs.setName("R4");
                    rcs.setColor(Color.MOON_YELLOW);
                    rcs.setReason("This is a role for R4 members");
                }).doOnError(Throwable::printStackTrace).subscribe((r) -> {
                    System.out.println(r);
                    R4roleId = r.getId();
                });
            }
        })).subscribe();
    }

    public Participant createSDParticipant(String name, float pow, SDPos lane) {
        Participant newbie = new Participant(name,this);
        newbie.lane=lane;
        newbie.power=pow;
        if(newbie.create()) {
            sessions.put(newbie.uid,newbie);
            return newbie;
        }
        return null;
    }

    private static queries _Q;
    static void initQueries(Connection db) throws SQLException { _Q=new queries(db); }

    public Participant getOrCreateParticipant(Member m) {
        Participant res= sessions.get(m.getId().asLong());
        if(res==null) { // new user
            res = createNewDiscordParticipant(m);
        }
        return res;
    }
    // returns follow-up command for participant p in that server
    public NCommand.followupOld getFollowUpCmd(ChannelPartKey k) {
        return followUpCmd.get(k);
    }

    public void setFollowUpCmd(MessageChannel channel, Participant p, Command fup) {
        followUpCmd.put(new ChannelPartKey(channel,p),new NCommand.followupOld(fup));
    }
    public void setFollowUpNCmd(MessageChannel channel, Participant p, NCommand<?> fup) {
        FollowupNCommand.put(new ChannelPartKey(channel,p),new NCommand.followup(fup));
    }
    public void removeFollowupCmd(MessageChannel ch, Participant p) {
        ChannelPartKey k=new ChannelPartKey(ch,p);
        followUpCmd.remove(k);
    }
    public void removeFollowupNCmd(MessageChannel ch, Participant p) {
        ChannelPartKey k=new ChannelPartKey(ch,p);
        FollowupNCommand.remove(k);
    }

    public Participant getExistingParticipant(Snowflake memberId) {
        return sessions.get(memberId.asLong());
    }

    public NCommand.followup getNFollowUpCmd(ChannelPartKey k) {
        return FollowupNCommand.get(k);
    }

    public int getNbRRParticipant() {
        return (int)sessions.values().stream().filter(i -> i.registeredToRR).count();
    }


    private static class queries  {
        final PreparedStatement deleteLocalRRParticipants,deleteLocalSDParticipants ,RRunregAll,selectParticipants,
                selectRRevent,createServer,updateRR,closeE,openE,RRsaveTeam,SDsave,SDunregAll,openCC,closeCC,CCunregAll;

        queries(Connection dbConnection)  throws SQLException {
            selectParticipants  = dbConnection.prepareStatement("SELECT * FROM members where server=?");
            deleteLocalRRParticipants =  dbConnection.prepareStatement("DELETE  from members where server=? and isdiscord='f' and lane='0' and rr='t' ");
            deleteLocalSDParticipants =  dbConnection.prepareStatement("DELETE  from members where server=? and isdiscord='f' and lane!='0' and rr='f' ");
            SDunregAll = dbConnection.prepareStatement("UPDATE  members set lane='0' where server=?");
            RRunregAll = dbConnection.prepareStatement("UPDATE  members set rr='f', team='-1' where server=?");
            CCunregAll = dbConnection.prepareStatement("UPDATE  members set cc='f' where server=?");
            selectRRevent = dbConnection.prepareStatement("SELECT * FROM servers where server=?");
            createServer = dbConnection.prepareStatement("INSERT INTO servers(server,active,teamsaved,sdthreshold,rrdate) VALUES(?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS);
            updateRR = dbConnection.prepareStatement("UPDATE servers set rrdate=?,active=?,teamsaved=? where server=?", Statement.RETURN_GENERATED_KEYS);
            closeE = dbConnection.prepareStatement("UPDATE servers set active='f' where server=?", Statement.RETURN_GENERATED_KEYS);
            openE = dbConnection.prepareStatement("UPDATE servers set active='t' where server=?", Statement.RETURN_GENERATED_KEYS);
            RRsaveTeam = dbConnection.prepareStatement("UPDATE  servers set teamsaved=? where server=?");
            SDsave = dbConnection.prepareStatement("UPDATE  servers set sdthreshold=?,sdlanedata=?,enemylanedata=?,sdactive=? where server=?");
            openCC = dbConnection.prepareStatement("UPDATE  servers set CCactive='t',CCdate=? where server=?");
            closeCC = dbConnection.prepareStatement("UPDATE  servers set CCactive='f',CCdate=NULL where server=?");
        }

    }
    static class ChannelPartKey {
        public ChannelPartKey(MessageChannel c, Participant p) {
            partUid=p.uid; channelId=c.getId().asLong();
        }
        long channelId, partUid;
        @Override
        public boolean equals(Object o) {
            if(!(o instanceof ChannelPartKey)) return false;
            ChannelPartKey cpk=(ChannelPartKey) o;
            return cpk.channelId==channelId && cpk.partUid == partUid;
        }
        @Override
        public int hashCode() {
            HashCodeBuilder hcb = new HashCodeBuilder();
            return hcb.append(channelId).append(partUid).hashCode();
        }
    }
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY, getterVisibility = JsonAutoDetect.Visibility.NONE, setterVisibility = JsonAutoDetect.Visibility.NONE)
    static class SDLaneStatus implements Comparable<SDLaneStatus> {
        int nb;
        double pow;
        double realPow;
        @Override
        public int compareTo(SDLaneStatus o) {
            return Double.compare(realPow, o.realPow);
        }
        public String toString() {
            return nb+":"+pow+"("+realPow+")";
        }
    }
    static class SDEvent {
        static ObjectMapper objectMapper = new ObjectMapper();
        LocalDateTime start;
        float threshold;
        Guild guild;
        Map<SDPos,SDLaneStatus> laneStatus=new HashMap<>();
        Map<SDPos,SDLaneStatus> enemyStatus=new HashMap<>();
        boolean active;
        boolean registrationActive() {
            return active;
        }
        String getLaneStatus() {
            try {
                return objectMapper.writeValueAsString(laneStatus);
            } catch (JsonProcessingException e) {
                log.error("serialize error ",e);
            }
            return "";
        }
        String getEnemyStatus() {
            try {
                return objectMapper.writeValueAsString(enemyStatus);
            } catch (JsonProcessingException e) {
                log.error("serialize error ",e);
            }
            return "";
        }
        void initLaneStatus(String dbs) {
            try {
                if(dbs!= null && dbs.length()>0) laneStatus=objectMapper.readValue(dbs, new TypeReference<>() {
                });
            } catch (JsonProcessingException e) {
                log.error("deserialize error ",e);
            }
        }
        void initEnemyStatus(String dbs) {
            try {
                if(dbs!= null && dbs.length()>0) enemyStatus=objectMapper.readValue(dbs,new TypeReference<>(){});
            } catch (JsonProcessingException e) {
                log.error("deserialize error ",e);
            }
        }
        SDEvent(Guild g) {
            guild=g;
            start=LocalDateTime.now();
            laneStatus.put(SDPos.Left,new SDLaneStatus());
            laneStatus.put(SDPos.Center,new SDLaneStatus());
            laneStatus.put(SDPos.Right,new SDLaneStatus());
        }
        boolean save() {
            try{
                synchronized (_Q.SDsave) {
                    _Q.SDsave.setFloat(1, threshold);
                    _Q.SDsave.setString(2, getLaneStatus());
                    _Q.SDsave.setString(3, getEnemyStatus());
                    _Q.SDsave.setBoolean(4,active);
                    _Q.SDsave.setLong(5, guild.getId().asLong());

                    _Q.SDsave.executeUpdate();
                }
            } catch(SQLException e) {
                e.printStackTrace();
                SosBot.checkDBConnection();
                return false;
            }
            return true;
        }
        boolean cleanUp() {
            try {
                synchronized(_Q.deleteLocalSDParticipants) {
                    _Q.deleteLocalSDParticipants.setLong(1,guild.getId().asLong());
                    _Q.deleteLocalSDParticipants.executeUpdate();
                }
                synchronized(_Q.SDunregAll) {
                    _Q.SDunregAll.setLong(1,guild.getId().asLong());
                    _Q.SDunregAll.executeUpdate();
                }

            } catch(SQLException e) {
                log.error("SD user cleanup error",e);
                SosBot.checkDBConnection();
                return false;
            }
            Server s=KnownServers.get(guild.getId());
            s.sessions.values().removeIf((p)->{
                if(p.isDiscord) { p.lane=SDPos.Undef; return false;}
                return true;
            });
            return true;
        }

        @SuppressWarnings("ConstantConditions")
        public StringBuilder computeBestMatching(StringBuilder sb) {
            if(sb==null) sb=new StringBuilder();
            int[] mapping = new int[3];
            SDLaneStatus[] mylanes=laneStatus.values().stream().sorted().toArray(SDLaneStatus[]::new);
            SDLaneStatus[] hislanes=enemyStatus.values().stream().sorted().toArray(SDLaneStatus[]::new);
            if(mylanes[0].realPow > 1.05*hislanes[0].realPow &&
                    mylanes[1].realPow > 1.05*hislanes[1].realPow &&
                    mylanes[2].realPow > 1.05*hislanes[2].realPow) {
                log.info("Easy win");
                mapping[0]=0;
                mapping[1]=1;
                mapping[2]=2;
            } else if (mylanes[2].realPow > 0.95*hislanes[1].realPow &&
                    mylanes[1].realPow > 0.95*hislanes[0].realPow
            ) {
                log.info("close win");
                mapping[0]=2;
                mapping[1]=0;
                mapping[2]=1;
            } else {
                log.info("Impossible win");
                mapping[0]=2;
                mapping[1]=1;
                mapping[2]=0;
            }
            sb.append(" Us     vs    Them\n");
            for(int i=0;i<3;++i) {
                appendPair(sb,mylanes[i],hislanes[mapping[i]]);
            }
            return sb;
        }
        private static final DecimalFormat df1 = new DecimalFormat("000.0K");
        private void appendPair(StringBuilder sb,SDLaneStatus us,SDLaneStatus them) {
            sb.append(" ").append(us.nb).append("/20        ").append(them.nb).append("/20\n")
                    .append(df1.format(us.pow).replaceAll("\\G0", " "))
                    .append("        ").append((int)Math.round(them.pow*1000)).append("\n\n");
        }
        // get expected power of one participant (by averaging current team)
        public int getAvgParticipantPower() {
            int nb=0;
            double pow=0.0;
            for(SDLaneStatus sdl:laneStatus.values()) {
                nb+= sdl.nb;
                pow += sdl.pow;
            }
            if(nb==0 || pow==0.0) return 1000;
            return (int)Math.round(1000.*pow/nb);
        }
        // get expected power( in thousands) of a team of nbPart taking average power of current team as a reference
        public double getExpectedPower(int nbPart) {
            return nbPart*getAvgParticipantPower()/1000.;
        }
    }

    static class RREvent {
        static SimpleDateFormat df=new SimpleDateFormat("EEEE dd MMM h a z",Locale.US);
        static{df.setTimeZone(TimeZone.getTimeZone("UTC"));}
        public Instant date= Instant.EPOCH;
        boolean active=true;
        boolean teamSaved=false;
        Guild guild;
        int nbTeams=-1;
        public RREvent(Guild g) { this.guild=g;}
        public String toString() { return df.format(Date.from(date))+(active?"":"*");}
        boolean saveTeams(boolean saved) {
            try {
                synchronized (_Q.RRsaveTeam) {
                    _Q.RRsaveTeam.setBoolean(1, saved);
                    _Q.RRsaveTeam.setLong(2, guild.getId().asLong());
                    _Q.RRsaveTeam.executeUpdate();
                }
            } catch(SQLException se) {
                se.printStackTrace();
                SosBot.checkDBConnection();
                return false;
            }
            teamSaved=saved;
            return true;
        }

        boolean save() {
            try {
                synchronized (_Q.updateRR) {
                    _Q.updateRR.setTimestamp(1, new Timestamp(date.toEpochMilli()));
                    _Q.updateRR.setBoolean(2, active);
                    _Q.updateRR.setBoolean(3, teamSaved);
                    _Q.updateRR.setLong(4, guild.getId().asLong());
                    _Q.updateRR.executeUpdate();
                }
            } catch(SQLException ex) {
                ex.printStackTrace();
                SosBot.checkDBConnection();
                return false;
            }
            return true;
        }
        @SuppressWarnings("BooleanMethodIsAlwaysInverted")
        boolean close() {
            try {
                synchronized (_Q.closeE) {
                    _Q.closeE.setLong(1, guild.getId().asLong());
                    _Q.closeE.executeUpdate();
                }
            } catch(SQLException ex) {
                ex.printStackTrace();
                SosBot.checkDBConnection();
                return false;
            }
            active=false;
            return true;
        }

        boolean reopen() {
            try {
                synchronized (_Q.openE) {
                    _Q.openE.setLong(1, guild.getId().asLong());
                    _Q.openE.executeUpdate();
                }
            } catch(SQLException ex) {
                ex.printStackTrace();
                SosBot.checkDBConnection();
                return false;
            }
            active=true;
            return true;
        }
    }
    static class ServerInitInProgress extends Exception {}
}


