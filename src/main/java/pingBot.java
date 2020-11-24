import discord4j.core.*;
import discord4j.core.event.domain.message.MessageCreateEvent;
import discord4j.core.object.entity.Member;
import discord4j.core.object.entity.Message;
import discord4j.core.object.entity.channel.TextChannel;

import java.io.*;
import java.sql.*;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

//Command state machine steps
enum Step { begin,registration,power,cancel,create, confirmCreate, stopReg }
public class pingBot {
    static final  String DbFile="DBfile.txt";
    static final Pattern registerPattern= Pattern.compile("(.*\\S)\\s+(\\d+.?\\d*)");
    static final String helpStr="```register\t\tstarts registering to event\n" +
                                    "list\t\tdisplays list of registered members for next event\n"+
                                    "create\t\tcreate a new event\n"+
                                    "stopReg\t\tclose event registration process\n" +
                                    "teams\t\tgive a breakdown of teams```";
    static Connection dbConnection;
    static PreparedStatement insertP,insertE,deleteP;
    static String eventDetails="",newEventDetails="";
    static final Duration BLOCK=Duration.ofSeconds(3);


    public static void main(final String[] args) {
        final String token = System.getenv("TOKEN");
        final DiscordClient client = DiscordClient.create(token);
        final GatewayDiscordClient gateway = client.login().block();



        BufferedWriter db=null;
        initFromDB();
        //initFromFile();
        try{
            db = new BufferedWriter(new FileWriter(DbFile,true));
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
            final String channelName =channel.getName();
            if(channelName!= null && channelName.equals("reservoir-raid")) {
                String user;
                Member m = message.getAuthorAsMember().block();
                if(m==null) {
                    System.err.println("Error fetching member info");
                    return;
                }
                user = m.getNickname().orElseGet(() -> message.getUserData().username());
                System.out.println("==>" + message.getContent() + ", " + user);
                Participant participant = sessions.get(user);
                if(participant==null) { participant=new Participant(user,-1); sessions.put(user,participant);}
                String rawContent = message.getContent(),content=rawContent.trim().toLowerCase();

                switch(content) {
                    case "help":
                        channel.createMessage(helpStr).block(BLOCK);
                        participant.setStep(Step.begin);
                        return;
                    case "list":
                        List<Participant> registered = sessions.values().stream()
                                .filter(i -> i.registered)
                                .collect(Collectors.toList());
                        if (registered.size() == 0) {
                            participant.setStep(Step.begin);
                            channel.createMessage("Nobody registered yet").block(BLOCK);
                            return;
                        }
                        StringBuilder sb = new StringBuilder("Registered so far for ").append(eventDetails).append("\n");
                        for (Participant p : registered) sb.append(p).append("\n");
                        participant.setStep(Step.begin);
                        channel.createMessage(sb.toString()).block(BLOCK);
                        return;
                    case "register":
                        if (participant.registered) {
                                channel.createMessage(user + " you are already registered!").block(BLOCK);
                        } else {
                            participant.setStep(Step.registration);
                            channel.createMessage(user + " can you commit to be online " + eventDetails + "(yes/no)").block(BLOCK);
                        }
                        return;
                    case "cancel":
                        if(participant.registered) {
                            participant.setStep(Step.cancel);
                            channel.createMessage(user + " Do you really want to cancel your registration for " + eventDetails + " (yes/no)").block(BLOCK);
                        } else {
                            participant.setStep(Step.begin);
                            channel.createMessage(user + " You are not registered! No need to cancel!").block(BLOCK);
                        }
                        return;
                    case "create":
                        participant.setStep(Step.create);
                        channel.createMessage(user + "Please enter event date (free text including date and utc time)").block(BLOCK);
                        return;
                    case "yes":
                        switch(participant.step) {
                            case registration:
                                if(participant.timedOut()) {
                                    participant.setStep(Step.begin);
                                    channel.createMessage(user + " registration timed out!").block(BLOCK);
                                    return;
                                }
                                participant.setStep(Step.power);
                                channel.createMessage("Please enter your current overall Battle Power(just  number in millions) to help creating balanced teams").block(BLOCK);
                                return;
                            case cancel:
                                if(participant.timedOut()) {
                                    participant.setStep(Step.begin);
                                    channel.createMessage(user + " cancellation timed out!").block(BLOCK);
                                    return;
                                }
                                participant.registered=false;
                                participant.setStep(Step.begin);
                                channel.createMessage(user + " your registration has been cancelled!").block(BLOCK);
                                return;
                            case confirmCreate:
                                if(participant.timedOut()) {
                                    participant.setStep(Step.begin);
                                    channel.createMessage(user + " creation timed out!").block(BLOCK);
                                    return;
                                }
                                participant.setStep(Step.begin);
                                channel.createMessage("Event \""+newEventDetails+"\" now live!").block(BLOCK);
                                eventDetails=newEventDetails;
                                sessions.clear();
                                insertEvent(newEventDetails);
                                return;
                        }
                    default:
                        switch(participant.step) {
                            case power:
                                if(participant.timedOut()) {
                                    participant.setStep(Step.begin);
                                    channel.createMessage(user + " registration timed out!").block(BLOCK);
                                    return;
                                }
                                float pow;
                                try { pow=Float.parseFloat(content); }
                                catch (NumberFormatException nfe) {channel.createMessage("incorrect number format "+content).block(BLOCK); return;}
                                if (pow <0.1 || pow > 300.) {
                                    channel.createMessage("incorrect power value "+content).block(BLOCK);
                                } else {
                                    participant.power = pow;
                                    participant.setStep(Step.begin);
                                    participant.registered=true;
                                    try {
                                        finalDb.write(participant + "\n");
                                        finalDb.flush();
                                    } catch (IOException e) {
                                        e.printStackTrace();
                                        return;
                                    }
                                    insertParticipant(participant);
                                    channel.createMessage(user + " your registration is confirmed we count on you!").block(BLOCK);
                                }
                                return;
                            case cancel:
                                participant.setStep(Step.begin);
                                channel.createMessage(user + " cancellation aborted you are still registered").block(BLOCK);
                                return;
                            case registration:
                                participant.setStep(Step.begin);
                                channel.createMessage("registration aborted").block(BLOCK);
                                return;
                            case create:
                                newEventDetails = rawContent.trim();
                                participant.setStep(Step.confirmCreate);
                                channel.createMessage("do you confirm you want to create new RR event \""+newEventDetails+"\" (yes/no)").block(BLOCK);
                        }
                }
            }
        });
        gateway.onDisconnect().block();
    }
    //static ArrayList<Participant> registered = new ArrayList<>();
    static HashMap<String,Participant> sessions = new HashMap<>();

    static void initFromFile() {
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
    static void insertParticipant(Participant p) {
        try {
            insertP.setString(1, p.name);
            insertP.setFloat(2, p.power);
            insertP.executeUpdate();
        } catch(SQLException e) {
            e.printStackTrace();
        }
    }
    static void insertEvent(String s) {
        try {
            insertE.setString(1, s);
            insertE.executeUpdate();
            deleteP.executeUpdate();
        } catch(SQLException e) {
            e.printStackTrace();
        }
    }
    static void initFromDB() {
        try {
            Statement stmt = getConnection().createStatement();
            ResultSet rs = stmt.executeQuery("SELECT name FROM event");
            if(rs.next()) { // read first event
                eventDetails = rs.getString("name");
            } else {
                eventDetails="";
            }
            rs=stmt.executeQuery("SELECT name,power FROM participants");
            while(rs.next()) {
                String player=rs.getString("name");
                float power= rs.getFloat("power");
                Participant p;
                if(!sessions.containsKey(player)) {
                    p=new Participant(player, power);
                    p.registered=true;
                    sessions.put(player, p);
                }
            }

        } catch(SQLException e) {
            e.printStackTrace();
        }
    }

    private static Connection getConnection() throws  SQLException {
        if(dbConnection==null) {
            String dbUrl = System.getenv("JDBC_DATABASE_URL");
            dbConnection=DriverManager.getConnection(dbUrl);
            insertP = dbConnection.prepareStatement("INSERT INTO participants(name,power) VALUES(?,?)", Statement.RETURN_GENERATED_KEYS);
            insertE = dbConnection.prepareStatement("INSERT INTO event(name) VALUES(?)", Statement.RETURN_GENERATED_KEYS);
            deleteP = dbConnection.prepareStatement("delete * from participants");
        }
        return dbConnection;
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
    }
}

