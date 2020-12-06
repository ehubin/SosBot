import discord4j.common.util.Snowflake;
import discord4j.core.*;
import discord4j.core.event.domain.message.MessageCreateEvent;
import discord4j.core.object.entity.Guild;
import discord4j.core.object.entity.Member;
import discord4j.core.object.entity.Message;
import discord4j.core.object.entity.channel.TextChannel;
import discord4j.rest.util.Color;



import java.io.*;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

//Command state machine steps
enum Step { begin,registration,power,cancel,create, confirmCreate, teamsNb, closeReg,teamSave }
public class pingBot {
    static final  String DbFile="DBfile.txt";
    static final Pattern registerPattern= Pattern.compile("(.*\\S)\\s+(\\d+.?\\d*)");
    static final Pattern swapPattern=Pattern.compile("\\s*(\\d).(\\d+)\\s*(\\d).(\\d+)");
    static final String RRhelpStr= "```register             starts registering to event\n" +
                                      "list                 displays list of registered members for next event\n"+
                                      "create               create a new event\n"+
                                      "closeReg             close event registration process\n" +
                                      "r4reg <name> <power> allows to register another player (only for R4s)\n"+
                                      "teams                give a breakdown of participants into teams\n"+
                                      "swap x.y z.t         swaps player y in team x with player t in team z```";
    static final String SDhelpStr= "```register    starts registering to event\n" +
                                      "lanes       displays list of registered members for next event\n"+
                                      "create      create a new event```";
    static Connection dbConnection;
    static PreparedStatement insertP,insertE,deleteP,deleteOneP,closeE,deleteE,selectRRevent,selectRRparticipants,
            saveTeam,updateTeam,RRsaveTeam;
    //static String eventDetails="",newEventDetails="";
    static final Duration BLOCK=Duration.ofSeconds(3);
    static HashMap<String,Server> servers= new HashMap<>();
    static HashMap<String,Boolean> channelsCreated= new HashMap<>();

    public static void main(final String[] args) {
        final String token = System.getenv("TOKEN");
        final DiscordClient client = DiscordClient.create(token);
        final GatewayDiscordClient gateway = client.login().block();


        if(gateway==null) {
            System.err.println("Failed to initialize discord Gateway");
            System.exit(-1);
        }
        try {
            connectToDB();
        } catch (SQLException se) {
            System.err.println("Failed to initialize database connection");
            se.printStackTrace();
            System.exit(-2);
        }

        gateway.on(MessageCreateEvent.class).map(pingBot::processMessage).onErrorContinue((error, event)->{
            try {
                error.printStackTrace();
                final Message message = ((MessageCreateEvent) event).getMessage();
                final TextChannel channel = ((TextChannel) message.getChannel().block());
                if (channel == null) {
                    System.err.println("Error fetching channel info");
                    return;
                }
                channel.createMessage("Unexpected error...").block();
            }
            catch(Error e) {
                System.err.println("Double error!!");
                e.printStackTrace();
            }
        }).subscribe();
        gateway.onDisconnect().block();
    } //end of main

    static MessageCreateEvent processMessage( MessageCreateEvent event) {
        final Message message = event.getMessage();
        final TextChannel channel = ((TextChannel)message.getChannel().block());
        if(channel == null) {
            System.err.println("Error fetching channel info");
            return event;
        }
        Guild guild=event.getGuild().block();
        Server curServer;
        if(guild==null) {
            System.err.println("Error fetching server info");
            return event;
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
                        //System.out.println("found parent");
                        parentId.set(c.getId());
                    }
                });
                if(!foundRR.get()) {
                    System.out.println("Creating reservoir raid channel for "+guild.getName());
                    guild.createTextChannel(c->{
                        c.setName("reservoir-raid");
                        c.setTopic("Channel for reservoir raid registration");
                        if(parentId.get() != null) c.setParentId(parentId.get());
                    }).doOnError(Throwable::printStackTrace).subscribe(System.out::println);

                }
                if(!foundSC.get()) {
                    System.out.println("Creating showdown channel for "+guild.getName());
                   guild.createTextChannel(c->{
                        c.setName("showdown");
                        c.setTopic("Channel for showdown registration");
                        if(parentId.get() != null) c.setParentId(parentId.get());
                    }).doOnError(Throwable::printStackTrace).subscribe(System.out::println);

                }
                //create R4 role if it does not already exists
                AtomicBoolean foundR4 = new AtomicBoolean(false);
                guild.getRoles().subscribe(r->{
                    if(r.getName().equals("R4")) foundR4.set(true);
                });
                if(!foundR4.get()) {
                    System.out.println("Creating R4 role for "+guild.getName());
                   guild.createRole(rcs -> {
                       rcs.setName("R4");
                       rcs.setColor(Color.MOON_YELLOW);
                       rcs.setReason("This is a role for R4 members");
                    }).doOnError(Throwable::printStackTrace).subscribe(System.out::println);
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
                return event;
            }
            final boolean[] foundR4= {false};
            m.getRoles().subscribe( r-> {  if(r.getName().equals("R4")) foundR4[0]=true;});
            boolean isR4 = foundR4[0];
            user = m.getNickname().orElseGet(() -> message.getUserData().username());
            // don't process bot messages and log user messages
            if(user.equals("SosBot")) return event;
            else System.out.println("==>" + message.getContent() + ", " + user);
            HashMap<String,Participant> sessions=curServer.sessions;
            Participant participant = sessions.get(user);
            if(participant==null) { participant=new Participant(user,-1); sessions.put(user,participant);}
            String rawContent = message.getContent(),content=rawContent.trim().toLowerCase();
            if(channelName.equals("reservoir-raid")) {
                switch (content) {
                    case "help":
                        channel.createMessage(RRhelpStr).block(BLOCK);
                        participant.setStep(Step.begin);
                        return event;
                    case "list": {
                        List<Participant> registered = sessions.values().stream()
                                .filter(i -> i.registered)
                                .sorted(Comparator.comparingDouble(Participant::getPower))
                                .collect(Collectors.toList());
                        if (registered.size() == 0) {
                            participant.setStep(Step.begin);
                            channel.createMessage("Nobody registered yet").block(BLOCK);
                            return event;
                        }
                        StringBuilder sb = new StringBuilder("Registered so far for ").append(curServer.RRevent).append("\n```");
                        int max = registered.stream().map(p -> p.name.length()).max(Integer::compareTo).get();
                        for (Participant p : registered) {
                            sb.append(p.name).append(" ".repeat(max + 5 - p.name.length())).append(p.power).append("\n");
                        }
                        sb.append("```");
                        participant.setStep(Step.begin);
                        channel.createMessage(sb.toString()).block(BLOCK);
                        return event;
                    }
                    case "register":
                        if (participant.registered) {
                            channel.createMessage(user + " you are already registered!").block(BLOCK);
                        } else if(!curServer.RRevent.active) {
                            channel.createMessage(user + "RR event now closed to registrations!").block(BLOCK);
                        }
                        else {
                            participant.setStep(Step.registration);
                            channel.createMessage(user + " can you commit to be online " + curServer.RRevent + "(yes/no)").block(BLOCK);
                        }
                        return event;
                    case "cancel":
                        if (participant.registered) {
                            participant.setStep(Step.cancel);
                            channel.createMessage(user + " Do you really want to cancel your registration for " + curServer.RRevent + " (yes/no)").block(BLOCK);
                        } else {
                            participant.setStep(Step.begin);
                            channel.createMessage(user + " You are not registered! No need to cancel!").block(BLOCK);
                        }
                        return event;
                    case "create":
                        if (!isR4) {
                            channel.createMessage("Create command only allowed for R4 members").block(BLOCK);
                            participant.setStep(Step.begin);
                            return event;
                        }
                        participant.setStep(Step.create);
                        channel.createMessage(user + " please enter event date (e.g Sunday the 12th at 20:00 utc)").block(BLOCK);
                        return event;
                    case "closereg":
                        if(!isR4) {
                            participant.setStep(Step.begin);
                            channel.createMessage(user + "Stop registration only for R4").block(BLOCK);
                            return event;
                        }
                        participant.setStep(Step.closeReg);
                        channel.createMessage(user + " are you sure you want to stop registration for " + curServer.RRevent + "(yes/no)").block(BLOCK);
                        return event;
                    case "teams":
                        if(curServer.RRevent.teamSaved) {
                            participant.setStep(Step.begin);
                            ArrayList<ArrayList<Participant>> teams = getRRSavedTeams(sessions.values());
                            channel.createMessage(displayTeams(teams).toString()).block(BLOCK);
                            if(isR4) {
                                channel.createMessage("You can swap players around by typing (e.g swap 1.2 3.1) to swap second player in team 1 with first player in team 3").block(BLOCK);
                                return event;
                            }
                        } else {
                            participant.setStep(Step.teamsNb);
                            channel.createMessage("How many teams do you want?").block(BLOCK);
                        }
                        return event;
                    case "yes":
                        switch (participant.step) {
                            case registration:
                                if (participant.timedOut()) {
                                    participant.setStep(Step.begin);
                                    channel.createMessage(user + " registration timed out!").block(BLOCK);
                                    return event;
                                }
                                participant.setStep(Step.power);
                                channel.createMessage("Please enter your current overall Battle Power(e.g 30 or 30.2) to help creating balanced teams").block(BLOCK);
                                return event;
                            case cancel:
                                if (participant.timedOut()) {
                                    participant.setStep(Step.begin);
                                    channel.createMessage(user + " cancellation timed out!").block(BLOCK);
                                    return event;
                                }
                                participant.registered = false;
                                participant.setStep(Step.begin);
                                deleteFromDB(participant,guild);
                                channel.createMessage(user + " your registration has been cancelled!").block(BLOCK);
                                return event;
                            case confirmCreate:
                                if (participant.timedOut()) {
                                    participant.setStep(Step.begin);
                                    channel.createMessage(user + " creation timed out!").block(BLOCK);
                                    return event;
                                }
                                participant.setStep(Step.begin);

                                curServer.RRevent = curServer.newRRevent;
                                curServer.newRRevent = new RREvent(guild);
                                sessions.clear();
                                inserRRevent(curServer.RRevent);
                                channel.createMessage("Event \"" + curServer.RRevent + "\" now live!").block(BLOCK);
                                return event;
                            case teamSave: {
                                List<Participant> registered = getRegisteredRRparticipants(sessions);
                                if(registered.size()==0) {
                                    participant.setStep(Step.begin);
                                    channel.createMessage(" Nobody registered yet!").block(BLOCK);
                                    return event;
                                }
                                ArrayList<ArrayList<Participant>> teams = getRRTeams(curServer.RRevent.nbTeams,registered,null);
                                for(int i=0;i<curServer.RRevent.nbTeams;++i) {
                                    assert teams != null;
                                    for(Participant p:teams.get(i)) {
                                        p.updateTeam(i+1,curServer.guild.getId().asLong());
                                    }
                                    curServer.RRevent.saveTeams();
                                }
                                return event;
                            }
                            case closeReg:
                                if (participant.timedOut()) {
                                    participant.setStep(Step.begin);
                                    channel.createMessage(user + " stop registration timed out!").block(BLOCK);
                                    return event;
                                }

                                participant.setStep(Step.begin);
                                // update DB
                                curServer.RRevent.active = false;
                                closeRREvent(curServer.RRevent);
                                channel.createMessage("Event \"" + curServer.RRevent + "\" now closed for registration! you can still get teams or list of participants").block(BLOCK);
                                return event;
                        }
                    default:
                        if(content.startsWith("r4reg ")) {
                            if(!isR4) {
                                channel.createMessage("only R4 can use r4reg command").block(BLOCK);
                                return event;
                            }
                            Matcher ma=registerPattern.matcher(rawContent.substring(6));
                            if(ma.find()) {
                                float pow=Float.parseFloat(ma.group(2));
                                System.out.println("registering "  + ma.group(1) + "| " + ma.group(2));
                                Participant p = new Participant(ma.group(1), pow);
                                p.registered=true;
                                sessions.put(ma.group(1),p);
                                insertParticipant(p,guild);
                                channel.createMessage("Succesfully registered "+p).block(BLOCK);
                            } else {
                                channel.createMessage("syntax is r4reg <name> <power>").block(BLOCK);
                            }
                            return event;
                        } else if(content.startsWith("swap ")) {
                            if(!isR4) {
                                channel.createMessage("only R4 can use swap command").block(BLOCK);
                                return event;
                            }
                            if(!curServer.RRevent.teamSaved) {
                                channel.createMessage("You have to save teams before swapping players").block(BLOCK);
                                return event;
                            }
                            Matcher ma=swapPattern.matcher(content.substring(4));
                            if(ma.find()) {
                                int fromTeam=Integer.parseInt(ma.group(1));
                                int fromPlayer=Integer.parseInt(ma.group(2));
                                int toTeam=Integer.parseInt(ma.group(3));
                                int toPlayer=Integer.parseInt(ma.group(4));
                                ArrayList<ArrayList<Participant>> teams = getRRSavedTeams(sessions.values());
                                int teamNb = teams.size();
                                if(fromTeam<=0 || fromTeam>teamNb) {
                                    channel.createMessage("Invalid team number "+fromTeam).block(BLOCK);
                                    return event;
                                }
                                if(toTeam<=0 || toTeam>teamNb) {
                                    channel.createMessage("Invalid team number "+toTeam).block(BLOCK);
                                    return event;
                                }
                                if(fromPlayer<=0 || fromPlayer > teams.get(fromTeam-1).size()) {
                                    channel.createMessage("Invalid player number "+fromPlayer).block(BLOCK);
                                    return event;
                                }
                                if(toPlayer<=0 || toPlayer > teams.get(toTeam-1).size()) {
                                    channel.createMessage("Invalid player number "+toPlayer).block(BLOCK);
                                    return event;
                                }
                                Participant from = teams.get(fromTeam-1).get(fromPlayer-1);
                                Participant to = teams.get(toTeam-1).get(toPlayer-1);

                                channel.createMessage("Swapping "+from.name+" and "+to.name).block(BLOCK);
                                from.swap(to,guild);
                                teams = getRRSavedTeams(sessions.values());
                                channel.createMessage(displayTeams(teams).toString()).block(BLOCK);
                            } else {
                                channel.createMessage("syntax is swap x.y z.t").block(BLOCK);
                            }
                            return event;
                        }
                        switch (participant.step) {
                            case power:
                                if (participant.timedOut()) {
                                    participant.setStep(Step.begin);
                                    channel.createMessage(user + " registration timed out!").block(BLOCK);
                                    return event;
                                }
                                float pow;
                                try {
                                    pow = Float.parseFloat(content);
                                } catch (NumberFormatException nfe) {
                                    channel.createMessage("incorrect number format " + content).block(BLOCK);
                                    return event;
                                }
                                if (pow < 0.1 || pow > 300.) {
                                    channel.createMessage("incorrect power value " + content).block(BLOCK);
                                } else {
                                    participant.power = pow;
                                    participant.setStep(Step.begin);
                                    participant.registered = true;
                                    insertParticipant(participant,guild);
                                    channel.createMessage(user + " your registration is confirmed we count on you!").block(BLOCK);
                                }
                                return event;
                            case teamsNb: {
                                if (participant.timedOut()) {
                                    participant.setStep(Step.begin);
                                    channel.createMessage(user + " teams timed out!").block(BLOCK);
                                    return event;
                                }
                                int nbTeam;
                                try {
                                    nbTeam = Integer.parseInt(content);
                                } catch (NumberFormatException ne) {
                                    participant.setStep(Step.begin);
                                    channel.createMessage("Wrong number of teams " + content).block(BLOCK);
                                    return event;
                                }
                                if(nbTeam<=0 || nbTeam>5) {
                                    channel.createMessage("Number of teams should be between 1 and 5").block(BLOCK);
                                    return event;
                                }


                                List<Participant> registered = getRegisteredRRparticipants(sessions);
                                if(registered.size()==0) {
                                    participant.setStep(Step.begin);
                                    channel.createMessage(" Nobody registered yet!").block(BLOCK);
                                    return event;
                                }
                                int[] power = new int[nbTeam];
                                ArrayList<ArrayList<Participant>> teams = getRRTeams(nbTeam,registered,power);
                                assert(teams!= null);
                                channel.createMessage(displayTeams(teams).toString()).block(BLOCK);
                                if(isR4) {
                                    participant.setStep(Step.teamSave);
                                    curServer.RRevent.nbTeams=nbTeam;
                                    channel.createMessage("Do you want to save this team configuration for the event? (yes/no)").block(BLOCK);
                                    return event;
                                }
                                participant.setStep(Step.begin);
                                return event;
                            }

                            case cancel:
                                participant.setStep(Step.begin);
                                channel.createMessage(user + " cancellation aborted you are still registered").block(BLOCK);
                                return event;
                            case registration:
                                participant.setStep(Step.begin);
                                channel.createMessage("registration aborted").block(BLOCK);
                                return event;
                            case create:
                                curServer.newRRevent.name = rawContent.trim();
                                participant.setStep(Step.confirmCreate);
                                channel.createMessage("do you confirm you want to create new RR event \"" + curServer.newRRevent + "\" (yes/no)").block(BLOCK);
                                return event;
                            case confirmCreate:
                                participant.setStep(Step.begin);
                                channel.createMessage("creation of event aborted").block(BLOCK);
                        }
                }
            }   else //noinspection ConstantConditions
                if(channelName.equalsIgnoreCase("showdown")) {
                    switch (content) {
                        case "help":
                            channel.createMessage(SDhelpStr).block(BLOCK);
                            participant.setStep(Step.begin);
                            return event;
                        case "register": {
                            channel.createMessage("Please enter your power in million with one decimal precision (e.g 25.3)").block(BLOCK);
                            participant.setStep(Step.power);
                            return event;
                        }
                        case "lanes": {

                        }
                        default: {
                            if(participant.step==Step.power) {
                                if (participant.timedOut()) {
                                    participant.setStep(Step.begin);
                                    channel.createMessage(user + "registration timed out!").block(BLOCK);
                                    return event;
                                }
                                float pow;
                                try {
                                    pow = Float.parseFloat(content);
                                } catch (NumberFormatException nfe) {
                                    channel.createMessage("incorrect number format " + content).block(BLOCK);
                                    return event;
                                }
                                if (pow < 0.1 || pow > 300.) {
                                    channel.createMessage("incorrect power value " + content).block(BLOCK);
                                } else {
                                    participant.power = pow;
                                    participant.setStep(Step.begin);
                                    //TODO compute lane

                                    //todo save in DB the updated participant data
                                }
                            } else {
                                participant.setStep(Step.begin);
                            }
                        }
                    }
                }
        }
        return event;
    }

    static List<Participant> getRegisteredRRparticipants(HashMap<String,Participant> sessions) {
        return sessions.values().stream().filter(i -> i.registered)
                .sorted(Comparator.comparingDouble(Participant::getPower).reversed())
                .collect(Collectors.toList());

    }
    static ArrayList<ArrayList<Participant>> getRRTeams(int nbTeam,List<Participant> registered,int[] power) {
        if(registered.size()==0) return null;
        ArrayList<ArrayList<Participant>> teams = new ArrayList<>();
        if(power==null) power = new int[nbTeam];
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
        return teams;
    }
    static ArrayList<ArrayList<Participant>> getRRSavedTeams(Collection<Participant> all) {
        List<Participant>list=all.stream().filter(i -> i.registered)
                .sorted(Comparator.comparingDouble(Participant::getPower).reversed())
                .collect(Collectors.toList());
        ArrayList<ArrayList<Participant>> res = new ArrayList<>();
        for(Participant p:list) {
            while(res.size()<p.teamNumber) res.add(new ArrayList<>());
            res.get(p.teamNumber-1).add(p);
        }
        return res;
    }
    static StringBuilder displayTeams(ArrayList<ArrayList<Participant>> teams) {
        StringBuilder sb = new StringBuilder();
        sb.append("```");
        int nbTeam= teams.size();
        int[] power =new int[nbTeam];
        for (int i = 0; i < nbTeam; ++i) {
            for (Participant p : teams.get(i)) {power[i]+= p.power;}
        }
        for (int i = 0; i < nbTeam; ++i) {
            sb.append("Team ").append(i + 1).append(" (").append(power[i]).append(")\n");
            int j=0;
            for (Participant p : teams.get(i)) {
                sb.append(++j).append(". ").append(p.name).append(" (").append(p.power).append(")\n");
            }
            sb.append("\n");
        }
        sb.append("```");
        return sb;
    }

    @SuppressWarnings("unused")
    static String padStr(String s, int l) {
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
        boolean teamSaved=false;
        Guild guild;
        int nbTeams=-1;
        public RREvent(Guild g) { this.guild=g;}
        public String toString() { return name+(active?"":"*");}
        boolean saveTeams() {
            try {
                teamSaved=true;
                RRsaveTeam.setLong(1,guild.getId().asLong());
                RRsaveTeam.setString(2,name);
                RRsaveTeam.executeUpdate();
            } catch(SQLException se) {
                se.printStackTrace();
                return false;
            }
            return true;
        }
    }
    static void inserRRevent(RREvent e) {
        try {
            deleteE.setLong(1, e.guild.getId().asLong());
            deleteE.executeUpdate();
            insertE.setString(1, e.name);
            insertE.setBoolean(2, e.active);
            insertE.setLong(3, e.guild.getId().asLong());
            insertE.setBoolean(4, e.teamSaved);
            insertE.executeUpdate();
            deleteP.setLong(1, e.guild.getId().asLong());
            deleteP.executeUpdate();
        } catch(SQLException ex) {
            ex.printStackTrace();
        }
    }
    static void closeRREvent(RREvent e) {
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
                e.teamSaved=rs.getBoolean("teamsaved");
                server.RRevent = e;

            } else {
                server.RRevent=new RREvent(server.guild);
                // first time event with empty DB is inactive
                server.RRevent.active=false;
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
                    p.teamNumber=rs.getInt("team");
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
        insertE = dbConnection.prepareStatement("INSERT INTO rrevent(name,active,server,teamsaved) VALUES(?,?,?,?)", Statement.RETURN_GENERATED_KEYS);
        closeE = dbConnection.prepareStatement("UPDATE rrevent set active='0' where name=? and server=?", Statement.RETURN_GENERATED_KEYS);
        saveTeam = dbConnection.prepareStatement("UPDATE rrevent set teamsaved='t' where name=? and server=?", Statement.RETURN_GENERATED_KEYS);
        deleteE = dbConnection.prepareStatement("DELETE from rrevent where server=?", Statement.RETURN_GENERATED_KEYS);
        deleteP = dbConnection.prepareStatement("delete from rrparticipants where server=?");
        deleteOneP = dbConnection.prepareStatement("delete from rrparticipants where name=? and server=?", Statement.RETURN_GENERATED_KEYS);
        selectRRevent = dbConnection.prepareStatement("SELECT name,active,teamsaved FROM rrevent where server=?");
        selectRRparticipants = dbConnection.prepareStatement("SELECT name,power,team FROM rrparticipants where server=?");
        updateTeam =  dbConnection.prepareStatement("UPDATE  rrparticipants set team=? where server=? and name=?");
        RRsaveTeam = dbConnection.prepareStatement("UPDATE  rrevent set teamsaved='t' where server=? and name=?");
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
        int teamNumber=-1;
        void setStep(Step s) { step=s; timestamp=System.currentTimeMillis();}
        boolean timedOut() { return (System.currentTimeMillis()-timestamp) > 60000 && step != Step.begin;}
        Participant(String n,float pow) {name=n;power=pow; }
        public String toString() { return name+"\t"+power;}
        public boolean updateTeam(int teamNb,long server) {
            teamNumber=teamNb;
            try {
                updateTeam.setInt(1, teamNb);
                updateTeam.setLong(2, server);
                updateTeam.setString(3, name);
                updateTeam.executeUpdate();
            } catch(SQLException se) {
                se.printStackTrace();
                return false;
            }
            return true;
        }
        public void swap(Participant o,Guild g) {
            int curTeam=teamNumber;
            updateTeam(o.teamNumber,g.getId().asLong());
            o.updateTeam(curTeam,g.getId().asLong());
        }

        public double getPower() { return power;}
    }
}

