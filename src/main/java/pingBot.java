import discord4j.core.*;
import discord4j.core.event.domain.message.MessageCreateEvent;
import discord4j.core.object.entity.Member;
import discord4j.core.object.entity.Message;
import discord4j.core.object.entity.channel.TextChannel;

import java.io.*;
import java.sql.*;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

enum Step { registration,power,finalized,cancel }
public class pingBot {
    static final  String DbFile="DBfile.txt";
    static final Pattern registerPattern= Pattern.compile("(.*\\S)\\s+(\\d+.?\\d*)");
    static final String helpStr="**register**        starts registering to event\n**list**           displays list of registered members for next event";
    static Connection dbConnection;
    static PreparedStatement insertP,insertE;
    static String eventDetails="";


    public static void main(final String[] args) {
        final String token = System.getenv("TOKEN");
        final DiscordClient client = DiscordClient.create(token);
        final GatewayDiscordClient gateway = client.login().block();



        BufferedWriter db=null;
        initFromDB();
        initFromFile();
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
                State state = sessions.get(user);
                String content = message.getContent().trim();

                if (content.equalsIgnoreCase("help")) {
                    channel.createMessage(helpStr).block(Duration.ofSeconds(3));
                }
                else if (content.equalsIgnoreCase("list")) {
                    if (registered.size() == 0) {
                        channel.createMessage("Nobody registered yet").block();
                        return;
                    }
                    StringBuilder sb = new StringBuilder("Registered so far for ").append(eventDetails).append("\n");
                    for (Participant p : registered) sb.append(p).append("\n");
                    channel.createMessage(sb.toString()).block();
                } else if (state == null || state.timedOut()) {
                    if (content.equalsIgnoreCase("register")) {
                        channel.createMessage(user + " can you commit to be online " + eventDetails + "(yes/no)").block();
                        sessions.put(user, new State(Step.registration));
                    } else {
                        channel.createMessage("invalid command \"" + content + '"');
                    }
                } else if (state.step == Step.finalized) {
                    if (content.equalsIgnoreCase("register")) {
                        channel.createMessage(user + " you are already registered!").block();
                    } else if (content.equalsIgnoreCase("cancel")) {
                        sessions.put(user, new State(Step.cancel));
                        channel.createMessage(user + " Do you really want to cancel your registration for " + eventDetails + " (yes/no)").block();
                    }
                } else if (state.step == Step.cancel) {
                    if (content.equalsIgnoreCase("yes")) {
                        sessions.put(user, null);
                        registered.removeIf((p) -> p.name.equals(user));
                        channel.createMessage(user + " your registration has been cancelled!").block();
                    } else {
                        sessions.put(user, null);
                        channel.createMessage(user + " cancellation aborted you are still registered").block();
                    }
                }
                else if(state.step==Step.registration) {
                    if(content.equalsIgnoreCase("yes")) {
                        sessions.put(user,new State(Step.power));
                        channel.createMessage("Please enter your current overall Battle Power(just  number in millions) to help creating balanced teams").block();

                    } else {
                        sessions.put(user,null);
                        channel.createMessage("registration aborted").block();
                    }
                }  else if(state.step==Step.power) {
                    float pow=Float.MAX_VALUE;
                    try { pow=Float.parseFloat(content); }
                    catch (NumberFormatException nfe) {channel.createMessage("incorrect number format "+content).block();}
                    if (pow <0.1 || pow > 200.) {
                        channel.createMessage("incorrect power value "+content).block();
                    } else {
                        Participant p=new Participant(user,pow);

                        try {
                            finalDb.write(p+"\n");
                            finalDb.flush();
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                        insertParticipant(p);
                        registered.add(p);
                        sessions.put(user,new State(Step.finalized));
                        channel.createMessage(user + " your registration is confirmed we count on you!").block();
                    }
                }
            }
        });

        gateway.onDisconnect().block();
    }
    static ArrayList<Participant> registered = new ArrayList<>();
    static HashMap<String,State> sessions = new HashMap<>();
    static class State {
        Step step;
        State(Step s) { step = s; timestamp = System.currentTimeMillis();}
        boolean timedOut() { return (System.currentTimeMillis()-timestamp) > 60000 && step != Step.finalized;} // 1 minute timeout
        long timestamp;
    }

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
                        sessions.put(m.group(1), new State(Step.finalized));
                        registered.add(new Participant(m.group(1), pow));
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
                if(!sessions.containsKey(player)) {
                    sessions.put(player, new State(Step.finalized));
                    registered.add(new Participant(player, power));
                }
            }

        } catch(SQLException e) {
            e.printStackTrace();
        }
    }

    private static Connection getConnection() throws  SQLException {
        if(dbConnection==null) {
            String dbUrl = System.getenv("JDBC_DATABASE_URL");
            Connection res=DriverManager.getConnection(dbUrl);
            insertP = res.prepareStatement("INSERT INTO participants(name,power) VALUES(?,?)", Statement.RETURN_GENERATED_KEYS);
            insertE = res.prepareStatement("INSERT INTO event(name) VALUES(?)", Statement.RETURN_GENERATED_KEYS);
        }
        return dbConnection;
    }
    static class Participant {
        String name;
        float power;
        Participant(String n,float pow) {name=n;power=pow; }
        public String toString() { return name+"\t"+power;}
    }
}

