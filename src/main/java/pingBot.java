import discord4j.common.util.Snowflake;
import discord4j.core.*;
import discord4j.core.event.domain.message.MessageCreateEvent;
import discord4j.core.object.entity.Guild;
import discord4j.core.object.entity.Member;
import discord4j.core.object.entity.Message;
import discord4j.core.object.entity.channel.TextChannel;


import java.io.*;
import java.sql.*;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

//Command state machine steps
enum Step { begin,registration,power,cancel,create, confirmCreate, teamsNb, closeReg }
public class pingBot {
    static final  String DbFile="DBfile.txt";
    static final Pattern registerPattern= Pattern.compile("(.*\\S)\\s+(\\d+.?\\d*)");
    static final String RRhelpStr= "```register    starts registering to event\n" +
                                      "list        displays list of registered members for next event\n"+
                                      "create      create a new event\n"+
                                      "closeReg    close event registration process\n" +
                                      "teams       give a breakdown of participants into teams```";
    static final String SChelpStr= "```register    starts registering to event\n" +
                                      "lanes       displays list of registered members for next event\n"+
                                      "create      create a new event\n";
    static Connection dbConnection;
    static PreparedStatement insertP,insertE,deleteP,deleteOneP,closeE,deleteE,selectRRevent,selectRRparticipants;
    //static String eventDetails="",newEventDetails="";
    static final Duration BLOCK=Duration.ofSeconds(3);
    static HashMap<String,Server> servers= new HashMap<>();
    static HashMap<String,Boolean> channelsCreated= new HashMap<>();

    public static void main(final String[] args) {
        final String token = System.getenv("TOKEN");
        final DiscordClient client = DiscordClient.create(token);
        final GatewayDiscordClient gateway = client.login().block();



        BufferedWriter db=null;
        //initFromFile();
        try{
            db = new BufferedWriter(new FileWriter(DbFile,true));
            connectToDB();
        } catch(Exception e) {
            e.printStackTrace();
        }
        BufferedWriter finalDb = db;
        if(gateway==null) {
            System.err.println("Failed to initialize discord Gateway");
            System.exit(-1);
        }
        gateway.on(MessageCreateEvent.class).subscribe(event -> {
            final Message message = event.getMessage();
            final TextChannel channel = ((TextChannel)message.getChannel().block());
            if(channel == null) {
                System.err.println("Error fetching channel info");
                return;
            }
            Guild guild=event.getGuild().block();
            Server curServer;
            if(guild==null) {
                System.err.println("Error fetching server info");
                return;
            } else {
                curServer=servers.get(guild.getId().asString());
                if(curServer==null) {
                    curServer= new Server(guild);
                    servers.put(guild.getId().asString(),curServer);
                    initFromDB(curServer);
                }
                if(channelsCreated.get(guild.getName())==null) {
                    AtomicBoolean foundRR = new AtomicBoolean(false);
                    AtomicBoolean foundSC = new AtomicBoolean(false);
                    AtomicReference<Snowflake> parentId=new AtomicReference<>();
                    guild.getChannels().subscribe(c->{
                        //System.out.println(c.getName()+" "+c.getType()+" "+c.getPosition());
                        if(c.getName().equals("reservoir-raid")) foundRR.set(true);
                        else if(c.getName().equals("showdown")) foundSC.set(true);
                        else if(c.getName().equalsIgnoreCase("text channels")) {
                            System.out.println("found parent");
                            parentId.set(c.getId());
                        }
                    });
                    if(!foundRR.get()) {
                        System.out.println("Creating reservoir raid channel");
                        guild.createTextChannel(c->{
                            c.setName("reservoir-raid");
                            c.setTopic("Channel for reservoir raid registration");
                            if(parentId.get() != null) c.setParentId(parentId.get());
                        }).doOnError(Throwable::printStackTrace);
                                //.subscribe(System.out::println);

                    }
                    if(!foundSC.get()) {
                        System.out.println("Creating showdown channel");
                        guild.createTextChannel(c->{
                            c.setName("showdown");
                            c.setTopic("Channel for showdown registration");
                            if(parentId.get() != null) c.setParentId(parentId.get());
                        }).doOnError(Throwable::printStackTrace);
                                //.subscribe(System.out::println);

                    }
                    channelsCreated.put(guild.getName(),true);
                }
            }
            final String channelName =channel.getName();
            if(channelName!= null &&
                    (channelName.equals("reservoir-raid") || channelName.equals("showdown"))) {
                String user;
                Member m = message.getAuthorAsMember().block();
                if(m==null) {
                    System.err.println("Error fetching member info");
                    return;
                }
                final boolean[] foundR4= {false};
                m.getRoles().subscribe( r-> {  if(r.getName().equals("R4")) foundR4[0]=true;});
                boolean isR4 = foundR4[0];
                user = m.getNickname().orElseGet(() -> message.getUserData().username());
                System.out.println("==>" + message.getContent() + ", " + user);
                HashMap<String,Participant> sessions=curServer.sessions;
                Participant participant = sessions.get(user);
                if(participant==null) { participant=new Participant(user,-1); sessions.put(user,participant);}
                String rawContent = message.getContent(),content=rawContent.trim().toLowerCase();
                if(channelName.equals("reservoir-raid")) {
                    switch (content) {
                        case "help":
                            channel.createMessage(RRhelpStr).block(BLOCK);
                            participant.setStep(Step.begin);
                            return;
                        case "list": {
                            List<Participant> registered = sessions.values().stream()
                                    .filter(i -> i.registered)
                                    .sorted(Comparator.comparingDouble(Participant::getPower))
                                    .collect(Collectors.toList());
                            if (registered.size() == 0) {
                                participant.setStep(Step.begin);
                                channel.createMessage("Nobody registered yet").block(BLOCK);
                                return;
                            }
                            StringBuilder sb = new StringBuilder("Registered so far for ").append(curServer.RRevent).append("\n```");
                            int max = registered.stream().map(p -> p.name.length()).max(Integer::compareTo).get();
                            for (Participant p : registered) {
                                sb.append(p.name).append(" ".repeat(max + 5 - p.name.length())).append(p.power).append("\n");
                            }
                            sb.append("```");
                            participant.setStep(Step.begin);
                            channel.createMessage(sb.toString()).block(BLOCK);
                            return;
                        }
                        case "register":
                            if (participant.registered) {
                                channel.createMessage(user + " you are already registered!").block(BLOCK);
                            } else {
                                participant.setStep(Step.registration);
                                channel.createMessage(user + " can you commit to be online " + curServer.RRevent + "(yes/no)").block(BLOCK);
                            }
                            return;
                        case "cancel":
                            if (participant.registered) {
                                participant.setStep(Step.cancel);
                                channel.createMessage(user + " Do you really want to cancel your registration for " + curServer.RRevent + " (yes/no)").block(BLOCK);
                            } else {
                                participant.setStep(Step.begin);
                                channel.createMessage(user + " You are not registered! No need to cancel!").block(BLOCK);
                            }
                            return;
                        case "create":
                            if (!isR4) {
                                channel.createMessage("Create command only allowed for R4 members").block(BLOCK);
                                participant.setStep(Step.begin);
                                return;
                            }
                            participant.setStep(Step.create);
                            channel.createMessage(user + " please enter event date (e.g Sunday the 12th at 20:00 utc)").block(BLOCK);
                            return;
                        case "closereg":
                            participant.setStep(Step.closeReg);
                            channel.createMessage(user + " are you sure you want to stop registration for " + curServer.RRevent + "(yes/no)").block(BLOCK);
                            return;
                        case "teams":
                            participant.setStep(Step.teamsNb);
                            channel.createMessage("How many teams do you want?").block(BLOCK);
                            return;
                        case "yes":
                            switch (participant.step) {
                                case registration:
                                    if (participant.timedOut()) {
                                        participant.setStep(Step.begin);
                                        channel.createMessage(user + " registration timed out!").block(BLOCK);
                                        return;
                                    }
                                    participant.setStep(Step.power);
                                    channel.createMessage("Please enter your current overall Battle Power(e.g 30 or 30.2) to help creating balanced teams").block(BLOCK);
                                    return;
                                case cancel:
                                    if (participant.timedOut()) {
                                        participant.setStep(Step.begin);
                                        channel.createMessage(user + " cancellation timed out!").block(BLOCK);
                                        return;
                                    }
                                    participant.registered = false;
                                    participant.setStep(Step.begin);
                                    deleteFromDB(participant,guild);
                                    channel.createMessage(user + " your registration has been cancelled!").block(BLOCK);
                                    return;
                                case confirmCreate:
                                    if (participant.timedOut()) {
                                        participant.setStep(Step.begin);
                                        channel.createMessage(user + " creation timed out!").block(BLOCK);
                                        return;
                                    }
                                    participant.setStep(Step.begin);

                                    curServer.RRevent = curServer.newRRevent;
                                    curServer.newRRevent = new RREvent(guild);
                                    sessions.clear();
                                    insertEvent(curServer.RRevent);
                                    channel.createMessage("Event \"" + curServer.RRevent + "\" now live!").block(BLOCK);
                                    return;
                                case closeReg:
                                    if (participant.timedOut()) {
                                        participant.setStep(Step.begin);
                                        channel.createMessage(user + " stop registration timed out!").block(BLOCK);
                                        return;
                                    }
                                    participant.setStep(Step.begin);
                                    // update DB
                                    curServer.RRevent.active = false;
                                    closeEvent(curServer.RRevent);
                                    channel.createMessage("Event \"" + curServer.RRevent + "\" now closed for registration! you can still get teams or list of participants").block(BLOCK);
                                    return;
                            }
                        default:
                            switch (participant.step) {
                                case power:
                                    if (participant.timedOut()) {
                                        participant.setStep(Step.begin);
                                        channel.createMessage(user + " registration timed out!").block(BLOCK);
                                        return;
                                    }
                                    float pow;
                                    try {
                                        pow = Float.parseFloat(content);
                                    } catch (NumberFormatException nfe) {
                                        channel.createMessage("incorrect number format " + content).block(BLOCK);
                                        return;
                                    }
                                    if (pow < 0.1 || pow > 300.) {
                                        channel.createMessage("incorrect power value " + content).block(BLOCK);
                                    } else {
                                        participant.power = pow;
                                        participant.setStep(Step.begin);
                                        participant.registered = true;
                                        try {
                                            finalDb.write(participant + "\n");
                                            finalDb.flush();
                                        } catch (IOException e) {
                                            e.printStackTrace();
                                            return;
                                        }
                                        insertParticipant(participant,guild);
                                        channel.createMessage(user + " your registration is confirmed we count on you!").block(BLOCK);
                                    }
                                    return;
                                case teamsNb: {
                                    if (participant.timedOut()) {
                                        participant.setStep(Step.begin);
                                        channel.createMessage(user + " teams timed out!").block(BLOCK);
                                        return;
                                    }
                                    int nbTeam;
                                    try {
                                        nbTeam = Integer.parseInt(content);
                                    } catch (NumberFormatException ne) {
                                        participant.setStep(Step.begin);
                                        channel.createMessage("Wrong number of teams " + content).block(BLOCK);
                                        return;
                                    }
                                    List<Participant> registered = sessions.values().stream()
                                            .filter(i -> i.registered)
                                            .sorted(Comparator.comparingDouble(Participant::getPower).reversed())
                                            .collect(Collectors.toList());
                                    if (registered.size() == 0) {
                                        participant.setStep(Step.begin);
                                        channel.createMessage(" Nobody registered yet!").block(BLOCK);
                                        return;
                                    }
                                    ArrayList<ArrayList<Participant>> teams = new ArrayList<>();
                                    int[] power = new int[nbTeam], maxLength = new int[nbTeam];
                                    for (int i = 0; i < nbTeam; ++i) {
                                        teams.add(new ArrayList<>());
                                        maxLength[i] = 10;
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
                                        if (p.name.length() > maxLength[best]) maxLength[best] = p.name.length();
                                    }
                                    StringBuilder sb = new StringBuilder();
                                    for (int i = 0; i < nbTeam; ++i)
                                        sb.append(padStr("Team " + (i + 1) + " (" + power[i] + ")", maxLength[i] + 5));
                                    channel.createMessage('`' + sb.toString() + '`').block(BLOCK);
                                    sb.setLength(0);
                                    sb.append("```");
                                    boolean foundOne;
                                    int idx = 0;
                                    do {
                                        foundOne = false;
                                        for (int i = 0; i < nbTeam; ++i) {
                                            if (idx < teams.get(i).size()) {
                                                foundOne = true;
                                                sb.append(padStr(teams.get(i).get(idx).name, maxLength[i] + 5));
                                            } else sb.append(" ".repeat(maxLength[i] + 5));
                                        }
                                        sb.append("\n");
                                        ++idx;
                                    } while (foundOne);
                                    sb.append("```");
                                    channel.createMessage(sb.toString()).block(BLOCK);
                                    participant.setStep(Step.begin);
                                    return;
                                }
                                case cancel:
                                    participant.setStep(Step.begin);
                                    channel.createMessage(user + " cancellation aborted you are still registered").block(BLOCK);
                                    return;
                                case registration:
                                    participant.setStep(Step.begin);
                                    channel.createMessage("registration aborted").block(BLOCK);
                                    return;
                                case create:
                                    curServer.newRRevent.name = rawContent.trim();
                                    participant.setStep(Step.confirmCreate);
                                    channel.createMessage("do you confirm you want to create new RR event \"" + curServer.newRRevent + "\" (yes/no)").block(BLOCK);
                                    return;
                                case confirmCreate:
                                    participant.setStep(Step.begin);
                                    channel.createMessage("creation of event aborted").block(BLOCK);
                            }
                    }
                }   else //noinspection ConstantConditions
                    if(channelName.equalsIgnoreCase("showdown")) {
                    switch (content) {
                        case "help":
                            channel.createMessage(SChelpStr).block(BLOCK);
                            participant.setStep(Step.begin);
                            return;
                        case "lanes": {

                        }
                    }
                }
            }
        });
        gateway.onDisconnect().block();
    }
    //static ArrayList<Participant> registered = new ArrayList<>();

    static String padStr(String s,int l) {
        return s.length() < l ? s+" ".repeat(l-s.length()) : s.substring(0,l);
    }

    @SuppressWarnings("unused")
    static void initFromFile(HashMap<String,Participant> sessions) {
        try{
            BufferedReader reader= new BufferedReader(new FileReader(DbFile));
            String line = reader.readLine();
            while(line != null) {
                Matcher m=registerPattern.matcher(line);
                if(m.find()) {

                    float pow=Float.parseFloat(m.group(2));
                    if(!sessions.containsKey(m.group(1))) {
                        System.out.println("restoring >" + line + "|" + m.group(1) + "|" + m.group(2));
                        sessions.put(m.group(1),new Participant(m.group(1), pow));
                    }
                }
                line = reader.readLine();
            }
            reader.close();
        } catch(Exception e) {
           e.printStackTrace();
        }
    }
    static void insertParticipant(Participant p,Guild g) {
        try {
            insertP.setString(1, p.name);
            insertP.setFloat(2, p.power);
            insertP.setLong(3,g.getId().asLong());
            insertP.executeUpdate();
        } catch(SQLException e) {
            e.printStackTrace();
        }
    }
    static class RREvent {
        String name="";
        boolean active=true;
        Guild guild;
        public RREvent(Guild g) { this.guild=g;}
        public String toString() { return name+(active?"":"*");}
    }
    static void insertEvent(RREvent e) {
        try {
            deleteE.setLong(1, e.guild.getId().asLong());
            deleteE.executeUpdate();
            insertE.setString(1, e.name);
            insertE.setBoolean(2, e.active);
            insertE.setLong(3, e.guild.getId().asLong());
            insertE.executeUpdate();
            deleteP.setLong(1, e.guild.getId().asLong());
            deleteP.executeUpdate();
        } catch(SQLException ex) {
            ex.printStackTrace();
        }
    }
    static void closeEvent(RREvent e) {
        try {
            closeE.setString(1, e.name);
            closeE.setLong(2, e.guild.getId().asLong());
            closeE.executeUpdate();
        } catch(SQLException ex) {
            ex.printStackTrace();
        }
    }
    static void initFromDB(Server server) {
        try {
            selectRRevent.setLong(1,server.guild.getId().asLong());
            ResultSet rs = selectRRevent.executeQuery();
            if(rs.next()) { // read first event
                RREvent e=new RREvent(server.guild);
                e.name=rs.getString("name");
                e.active=rs.getBoolean("active");
                server.RRevent = e;

            } else {
                server.RRevent=new RREvent(server.guild);
            }
            selectRRparticipants.setLong(1,server.guild.getId().asLong());
            rs=selectRRparticipants.executeQuery();
            while(rs.next()) {
                String player=rs.getString("name");
                float power= rs.getFloat("power");
                Participant p;
                if(!server.sessions.containsKey(player)) {
                    p=new Participant(player, power);
                    p.registered=true;
                    server.sessions.put(player, p);
                }
            }

        } catch(SQLException e) {
            e.printStackTrace();
        }
    }
    static void deleteFromDB(Participant p,Guild g) {
        try {
           deleteOneP.setString(1,p.name);
            deleteOneP.setLong(2,g.getId().asLong());
           deleteOneP.executeUpdate();
        } catch(SQLException e) {
            e.printStackTrace();
        }
    }
    private static void connectToDB() throws  SQLException {
        String dbUrl = System.getenv("JDBC_DATABASE_URL");
        dbConnection = DriverManager.getConnection(dbUrl);
        insertP = dbConnection.prepareStatement("INSERT INTO rrparticipants(name,power,server) VALUES(?,?,?)", Statement.RETURN_GENERATED_KEYS);
        insertE = dbConnection.prepareStatement("INSERT INTO rrevent(name,active,server) VALUES(?,?,?)", Statement.RETURN_GENERATED_KEYS);
        closeE = dbConnection.prepareStatement("UPDATE rrevent set active='0' where name=? and server=?", Statement.RETURN_GENERATED_KEYS);
        deleteE = dbConnection.prepareStatement("DELETE from rrevent where server=?", Statement.RETURN_GENERATED_KEYS);
        deleteP = dbConnection.prepareStatement("delete from rrparticipants where server=?");
        deleteOneP = dbConnection.prepareStatement("delete from rrparticipants where name=? and server=?", Statement.RETURN_GENERATED_KEYS);
        selectRRevent = dbConnection.prepareStatement("SELECT name,active FROM rrevent where server=?");
        selectRRparticipants = dbConnection.prepareStatement("SELECT name,power FROM rrparticipants where server=?");
    }
    static class Server {
        Guild guild;
        RREvent RRevent,newRRevent;
        HashMap<String,Participant> sessions = new HashMap<>();

        public Server(Guild guild) {
            this.guild=guild;
            RRevent= new RREvent(guild);
            newRRevent= new RREvent(guild);
        }
    }
    static class Participant {
        String name;
        float power;
        boolean registered=false;
        Step step=Step.begin;
        long timestamp;
        void setStep(Step s) { step=s; timestamp=System.currentTimeMillis();}
        boolean timedOut() { return (System.currentTimeMillis()-timestamp) > 60000 && step != Step.begin;}
        Participant(String n,float pow) {name=n;power=pow; }
        public String toString() { return name+"\t"+power;}

        public double getPower() { return power;}
    }
}

