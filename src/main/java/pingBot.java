import discord4j.core.*;
import discord4j.core.event.domain.message.MessageCreateEvent;
import discord4j.core.object.entity.Member;
import discord4j.core.object.entity.Message;
import discord4j.core.object.entity.channel.TextChannel;

import java.io.*;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

enum Step { registration,power,finalized,cancel }
public class pingBot {
    static final  String DbFile="DBfile.txt";
    static final Pattern registerPattern= Pattern.compile("(.*\\S)\\s+(\\d+.?\\d*)");
    static final String helpStr="**register**\t\tstarts registering to event\n**list**\tdisplays list of registered members for next event";
    public static void main(final String[] args) {
        final String token = System.getenv("TOKEN");
        final DiscordClient client = DiscordClient.create(token);
        final GatewayDiscordClient gateway = client.login().block();

        String eventDetails="Sunday 20:00 utc";

        BufferedWriter db=null;
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
                    for (String s : registered) sb.append(s).append("\n");
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
                        registered.removeIf((s) -> s.startsWith(user));
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
                    double pow=Double.MAX_VALUE;
                    try { pow=Double.parseDouble(content); }
                    catch (NumberFormatException nfe) {channel.createMessage("incorrect number format "+content).block();}
                    if (pow <0.1 || pow > 200.) {
                        channel.createMessage("incorrect power value "+content).block();
                    } else {
                        String registration=user+"\t"+pow;
                        try {
                            finalDb.write(registration+"\n");
                            finalDb.flush();
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                        registered.add(registration);
                        sessions.put(user,new State(Step.finalized));
                        channel.createMessage(user + " your registration is confirmed we count on you!").block();
                    }
                }
            }
        });

        gateway.onDisconnect().block();
    }
    static ArrayList<String> registered = new ArrayList<>();
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
                registered.add(line);
                Matcher m=registerPattern.matcher(line);
                if(m.find()) {
                    System.out.println("restoring >" + line + "|" + m.group(1) + "|" + m.group(2));
                    sessions.put(m.group(1),new State(Step.finalized));
                }
                line = reader.readLine();
            }
            reader.close();
        } catch(Exception e) {
           e.printStackTrace();
        }
    }
}

