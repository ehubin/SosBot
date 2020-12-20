import com.joestelmach.natty.DateGroup;
import com.joestelmach.natty.Parser;
import discord4j.common.util.Snowflake;
import discord4j.core.*;
import discord4j.core.event.domain.message.MessageCreateEvent;
import discord4j.core.object.entity.Guild;
import discord4j.core.object.entity.Member;
import discord4j.core.object.entity.Message;
import discord4j.core.object.entity.channel.Channel;
import discord4j.core.object.entity.channel.MessageChannel;
import discord4j.core.object.entity.channel.PrivateChannel;
import discord4j.core.object.entity.channel.TextChannel;
import discord4j.rest.util.Color;


import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.*;
import java.sql.*;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

//Command state machine steps
enum Step { begin,registration,power,cancel,create, confirmCreate, teamsNb, closeReg,teamSave }
public class pingBot {
    static  Parser dateParser;
    static final Pattern registerPattern= Pattern.compile("(.*\\S)\\s+(\\d+.?\\d*)");
    static final Pattern swapPattern=Pattern.compile("\\s*(\\d).(\\d+)\\s*(\\d).(\\d+)");
    static final Pattern oneFloatPattern=Pattern.compile("\\s*(\\d+.?\\d*)");
    static final Pattern SDRegPattern=Pattern.compile("(.*\\S)\\s+(\\d+.?\\d*)\\s+([lrc])");
    static final String RRhelpStr= "```register             starts registering to event\n" +
                                      "list                 displays list of registered members for next event\n"+
                                      "create               create a new event\n"+
                                      "closeReg             close event registration process\n" +
                                      "r4reg <name> <power> allows to register another player (only for R4s)\n"+
                                      "teams                give a breakdown of participants into teams\n"+
                                      "swap x.y z.t         swaps player y in team x with player t in team z\n"+
                                      "showmap              displays a map of the game suggesting team placements\n"+
                                      "notify               R4 can use this command to send everyone their team info for next RR```";
    static final String SDhelpStr= "```register                     starts registering to event\n" +
                                      "lanes                        displays list of registered members for next event sorted by lane\n"+
                                      "open <power limit>           Open showdown and give power limit between right lane and others\n"+
                                      "r4reg <name> <power> <lane>  Register a guy that did skip the bot and registered in-game direct (for R4) lane=l r or c```";
    static Connection dbConnection;

    //static String eventDetails="",newEventDetails="";
    static final Duration BLOCK=Duration.ofSeconds(3);
    static HashMap<String,Server> servers= new HashMap<>();
    static HashMap<String,Boolean> channelsCreated= new HashMap<>();
    static BufferedImage rrmap,tmpImage;
    static final String RRname ="\uD83D\uDCA6reservoir-raid\uD83D\uDCA6";
    static final String showdownName="\u2694showdown\u2694";

    public static void main(final String[] args) {
        System.setProperty(org.slf4j.simple.SimpleLogger.DEFAULT_LOG_LEVEL_KEY, "warn");
        TimeZone.setDefault(TimeZone.getTimeZone("UTC")); //all dates in UTC for sos
        dateParser=new Parser();
        final String token = System.getenv("TOKEN");
        final DiscordClient client = DiscordClient.create(token);

        final GatewayDiscordClient gateway = client.login().block(BLOCK);
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
        try {
            rrmap = ImageIO.read(new File("docs/rrmap.png"));
            tmpImage = new BufferedImage(rrmap.getColorModel(),
                    rrmap.copyData(null),
                    rrmap.isAlphaPremultiplied(),null);
        } catch (Exception e) {
            e.printStackTrace();
        }

        gateway.on(MessageCreateEvent.class).map(pingBot::processMessage).onErrorContinue((error, event)->{
            try {
                error.printStackTrace();
                final Message message = ((MessageCreateEvent) event).getMessage();
                final MessageChannel channel = message.getChannel().block(BLOCK);
                if (channel == null) {
                    System.err.println("Error fetching channel info");
                    return;
                }
                channel.createMessage("Unexpected error...").block(BLOCK);
            }
            catch(Throwable t) {
                System.err.println("Double error!!");
                t.printStackTrace();
            }
        }).subscribe();
        gateway.onDisconnect().block();
    } //end of main

    static MessageCreateEvent processMessage( MessageCreateEvent event) {
        final Message message = event.getMessage();

        final MessageChannel mc = message.getChannel().block(BLOCK);
        if(mc == null) {
            System.err.println("Error fetching channel info");
            return event;
        }
        if(!mc.getType().equals(Channel.Type.GUILD_TEXT)) {
            System.out.println("Not processing message in non text channels for now");
            return event;
        }
        TextChannel channel = (TextChannel)mc;

        Guild guild=event.getGuild().block(BLOCK);
        Server curServer;
        if(guild==null) {
            System.err.println("Error fetching server info");
            return event;
        } else {
            curServer=servers.get(guild.getId().asString());
            if(curServer==null) {
                curServer= new Server(guild);
                servers.put(guild.getId().asString(),curServer);
                curServer.initFromDB();
            }
            if(channelsCreated.get(guild.getName())==null) {
                AtomicBoolean foundRR = new AtomicBoolean(false);
                AtomicBoolean foundSC = new AtomicBoolean(false);
                AtomicReference<Snowflake> parentId=new AtomicReference<>();
                guild.getChannels().subscribe(c->{
                    //System.out.println(c.getName()+" "+c.getType()+" "+c.getPosition());
                    if(c.getName().equals(RRname)) foundRR.set(true);
                    else if(c.getName().equals(showdownName)) foundSC.set(true);
                    else if(c.getName().equalsIgnoreCase("text channels")) {
                        //System.out.println("found parent");
                        parentId.set(c.getId());
                    }
                });
                if(!foundRR.get()) {
                    System.out.println("Creating reservoir raid channel for "+guild.getName());
                    guild.createTextChannel(c->{
                        c.setName(RRname);
                        c.setTopic("Channel for reservoir raid registration");
                        if(parentId.get() != null) c.setParentId(parentId.get());
                    }).doOnError(Throwable::printStackTrace).subscribe(System.out::println);

                }
                if(!foundSC.get()) {
                    System.out.println("Creating showdown channel for "+guild.getName());
                   guild.createTextChannel(c->{
                        c.setName(showdownName);
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
                (channelName.equals(RRname) || channelName.equals(showdownName))) {
            String user;
            Member m = message.getAuthorAsMember().block(BLOCK);
            if(m==null) {
                System.err.println("Error fetching member info");
                return event;
            }
            final boolean[] foundR4= {false};
            m.getRoles().subscribe( r-> {
                //System.out.println(r);
                if(r.getName().equals("R4")) foundR4[0]=true;
            });
            boolean isR4 = foundR4[0];
            user = m.getDisplayName();
            // don't process bot messages and log user messages
            if(user.equals("SosBot")) return event;
            else System.out.println("==>" + message.getContent() + ", " + user);
            //HashMap<String,Participant> sessions=curServer.sessions;
            Participant participant = curServer.sessions.get(m.getId().asLong());
            if(participant==null) {
                System.out.println("New member "+ m.getDisplayName());
                participant=curServer.createNewDiscordParticipant(m);
                if(participant==null) {
                    channel.createMessage("Unexpected error while trying to create new user");
                    return event;
                }
            }
            String rawContent = message.getContent(),content=rawContent.trim().toLowerCase();


            if(channelName.equals(RRname)) {
                switch (content) {
                    case "help":
                        channel.createMessage(RRhelpStr).block(BLOCK);
                        participant.setStep(Step.begin);
                        return event;
                    case "list": {
                        List<Participant> registered = curServer.sessions.values().stream()
                                .filter(i -> i.registeredToRR)
                                .sorted(Comparator.comparingDouble(Participant::getPower))
                                .collect(Collectors.toList());
                        if (registered.size() == 0) {
                            participant.setStep(Step.begin);
                            channel.createMessage("Nobody registered yet").block(BLOCK);
                            return event;
                        }
                        StringBuilder sb = new StringBuilder("Registered so far for ").append(curServer.RRevent).append("\n```");
                        int max = registered.stream().map(p -> p.getName().length()).max(Integer::compareTo).get();
                        for (Participant p : registered) {
                            sb.append(p.getName()).append(" ".repeat(max + 5 - p.getName().length())).append(p.power).append("\n");
                        }
                        sb.append("```");
                        participant.setStep(Step.begin);
                        channel.createMessage(sb.toString()).block(BLOCK);
                        return event;
                    }
                    case "register":
                        if (participant.registeredToRR) {
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
                        if (participant.registeredToRR) {
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
                            ArrayList<ArrayList<Participant>> teams = curServer.getRRSavedTeams();
                            channel.createMessage(Server.displayTeams(teams).toString()).block(BLOCK);
                            if(isR4) {
                                channel.createMessage("You can swap players around by typing (e.g swap 1.2 3.1) to swap second player in team 1 with first player in team 3").block(BLOCK);
                                return event;
                            }
                        } else {
                            if(isR4) {
                                participant.setStep(Step.teamsNb);
                                channel.createMessage("How many teams do you want?").block(BLOCK);
                            } else {
                                channel.createMessage("Registration still ongoing. Team composition not finalised yet.").block(BLOCK);
                            }
                        }
                        return event;
                    case "showmap": {
                        if(!curServer.RRevent.teamSaved) {
                            channel.createMessage("Can only display map when teams have been saved.").block(BLOCK);
                            return event;
                        }
                        ArrayList<ArrayList<Participant>> teams = curServer.getRRSavedTeams();
                        Graphics2D g2d=tmpImage.createGraphics();
                        String[] leaders= new String[teams.size()];
                        int i=0;
                        for(ArrayList<Participant> t:teams) { leaders[i++]= t.get(0).getName();}
                        g2d.drawImage(rrmap,0,0,null);
                        RRmapTeam.drawTeams(g2d,leaders);
                        try {
                            ByteArrayOutputStream bos= new ByteArrayOutputStream();
                            ImageIO.write(tmpImage,"PNG",bos);
                            final byte[] img=bos.toByteArray();
                            channel.createMessage(mcs-> {
                                mcs.addFile("rrmap.png",new ByteArrayInputStream(img));
                                mcs.setEmbed(ecs-> ecs.setImage("attachment://rrmap.png").setColor(Color.MOON_YELLOW));
                            }).block(BLOCK);
                        } catch(Exception e){
                            e.printStackTrace();
                        }
                    }
                    return event;
                    case "notify" : {
                        if(!isR4) {
                            participant.setStep(Step.begin);
                            channel.createMessage("Only R4 can notify other players").block(BLOCK);
                            return event;
                        }
                        if(!curServer.RRevent.active) {
                            participant.setStep(Step.begin);
                            channel.createMessage("You have to save teams first before notifying people").block(BLOCK);
                            return event;
                        }
                        ArrayList<ArrayList<Participant>> teams=curServer.getRRSavedTeams();
                        byte[] img;
                        Graphics2D g2d=tmpImage.createGraphics();
                        String[] leaders= new String[teams.size()];
                        int i=0;
                        for(ArrayList<Participant> t:teams) { leaders[i++]= t.get(0).getName();}
                        g2d.drawImage(rrmap,0,0,null);
                        RRmapTeam.drawTeams(g2d,leaders);
                        try {
                            ByteArrayOutputStream bos = new ByteArrayOutputStream();
                            ImageIO.write(tmpImage, "PNG", bos);
                            img = bos.toByteArray();
                        } catch(Exception e) {
                            e.printStackTrace();
                            return event;
                        }
                        StringBuilder sb=new StringBuilder();
                        for(ArrayList<Participant> t:teams) {
                            for(Participant p:t) {
                                if(p.isDiscord && p.member!= null) {
                                    sb.setLength(0);
                                    for(Participant pa:t) {
                                        if (p != pa) {
                                            sb.append(" - ").append(pa.getName()).append(" (").append(pa.power).append(")\n");
                                        }
                                    }
                                    final String teamMates=sb.toString();
                                    final String teamAssignment = t.get(0)==p ?
                                            "You are the leader of one of the "+teams.size()+" teams" :
                                            "You have been assigned to "+t.get(0).getName()+ "'s team";

                                    PrivateChannel pv=p.member.getPrivateChannel().block(BLOCK);
                                    if(pv==null) {
                                        System.err.println("Could not get private channel for "+p);
                                        continue;
                                    }
                                    Server finalCurServer = curServer;
                                    pv.createMessage(mcs-> {
                                        mcs.addFile("rrmap.png",new ByteArrayInputStream(img));
                                        mcs.setEmbed(ecs-> {
                                            ecs.setDescription("Your Reservoir Raid info for "+ finalCurServer.RRevent);
                                            ecs.addField(teamAssignment,"\u200b",false);
                                            ecs.addField("Your team mates",teamMates,false);
                                            ecs.addField("\u200b","If you want to change to another team ask one of the R4s",false);
                                            ecs.addField("Your attendance to the event is important! We cannot cancel your registration anymore. We count on you!","\u200b",false);
                                            ecs.setImage("attachment://rrmap.png").setColor(Color.MOON_YELLOW);
                                        });
                                    }).subscribe(null,
                                            thr->{
                                                thr.printStackTrace();
                                            System.err.println("Error while sending message to "+p.getName());
                                            },
                                            ()-> System.out.println("Sent to "+p.getName())
                                    );
                                }
                            }
                        }
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

                                participant.setStep(Step.begin);
                                if(!participant.setRRregistered(false)) {
                                    channel.createMessage("Unexpected error while trying to cancel your RR registration").block(BLOCK);
                                    return event;
                                }
                                channel.createMessage(user + " your registration has been cancelled!").block(BLOCK);
                                return event;
                            case confirmCreate:
                                if (participant.timedOut()) {
                                    participant.setStep(Step.begin);
                                    channel.createMessage(user + " creation timed out!").block(BLOCK);
                                    return event;
                                }
                                participant.setStep(Step.begin);
                                if(!curServer.newRRevent.save()) {
                                    channel.createMessage("Failure while saving RR event").block(BLOCK);
                                    return event;
                                }
                                curServer.RRevent = curServer.newRRevent;
                                curServer.newRRevent = new RREvent(guild);
                                if(!curServer.unregisterRR()) {
                                    channel.createMessage("Unexpected error while updating participant status").block(BLOCK);
                                    return event;
                                }
                                channel.createMessage("Event \"" + curServer.RRevent + "\" now live!").block(BLOCK);
                                return event;
                            case teamSave: {
                                List<Participant> registered = curServer.getRegisteredRRparticipants();
                                if(registered.size()==0) {
                                    participant.setStep(Step.begin);
                                    channel.createMessage(" Nobody registered yet!").block(BLOCK);
                                    return event;
                                }
                                ArrayList<ArrayList<Participant>> teams = curServer.getRRTeams(curServer.RRevent.nbTeams,registered);
                                assert teams != null;
                                if(!curServer.RRevent.saveTeams(true)) {
                                    channel.createMessage("unexpected error while trying to save teams");
                                    return event;
                                }
                                for(int i=0;i<teams.size();++i) {
                                    for(Participant p:teams.get(i)) {
                                        if(!p.updateRRTeam(i+1)) {
                                            channel.createMessage("Failure in saving participant team data");
                                            System.err.println("participant save error for "+p);
                                            return event;
                                        }
                                    }

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
                                if(!curServer.RRevent.close()) {
                                    channel.createMessage("Event \"" + curServer.RRevent + "\" could not be closed for unexpected reason").block(BLOCK);
                                    return event;
                                }
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
                                Participant p = curServer.createRRParticipant(ma.group(1),pow,true);
                                if(p==null) {
                                    channel.createMessage("Unexpected error while trying to create participant "+ma.group(1));
                                    return event;
                                }
                                if(curServer.RRevent.teamSaved) {
                                    // unsave the teams as a new participant has been added otherwise he will be in no team
                                    curServer.RRevent.saveTeams(false);
                                    channel.createMessage("Removing saved team while adding new user").block(BLOCK);
                                }
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
                                ArrayList<ArrayList<Participant>> teams = curServer.getRRSavedTeams();
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

                                channel.createMessage("Swapping "+from.getName()+" and "+to.getName()).block(BLOCK);
                                from.swap(to);
                                teams = curServer.getRRSavedTeams();
                                channel.createMessage(Server.displayTeams(teams).toString()).block(BLOCK);
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
                                if (pow < 0.1 || pow > 1000.) {
                                    channel.createMessage("incorrect power value " + content).block(BLOCK);
                                } else {
                                    participant.power = pow;
                                    participant.setStep(Step.begin);
                                    if(!participant.setRRregistered(true)) {
                                        channel.createMessage("Unexpected error while saving registration data!").block(BLOCK);
                                        return event;
                                    }
                                    if(curServer.RRevent.teamSaved) {
                                        // unsave the teams as a new participant has been added otherwise he will be in no team
                                        curServer.RRevent.saveTeams(false);
                                        channel.createMessage("Removing saved team while adding new user").block(BLOCK);
                                    }
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


                                List<Participant> registered = curServer.getRegisteredRRparticipants();
                                if(registered.size()==0) {
                                    participant.setStep(Step.begin);
                                    channel.createMessage(" Nobody registered yet!").block(BLOCK);
                                    return event;
                                }
                                ArrayList<ArrayList<Participant>> teams = curServer.getRRTeams(nbTeam,registered);
                                assert(teams!= null);
                                channel.createMessage(Server.displayTeams(teams).toString()).block(BLOCK);
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
                                List<DateGroup> dg=dateParser.parse(rawContent.trim());
                                if(dg.size()==1 && dg.get(0).getDates().size()==1) {
                                    curServer.newRRevent.date=dg.get(0).getDates().get(0);
                                    participant.setStep(Step.confirmCreate);
                                    channel.createMessage("do you confirm you want to create new RR event \"" + curServer.newRRevent + "\" (yes/no)").block(BLOCK);
                                }
                                return event;
                            case confirmCreate:
                                participant.setStep(Step.begin);
                                channel.createMessage("creation of event aborted").block(BLOCK);
                        }
                }
            }   else //noinspection ConstantConditions
                if(channelName.equalsIgnoreCase(showdownName)) {
                    switch (content) {
                        case "help":
                            channel.createMessage(SDhelpStr).block(BLOCK);
                            participant.setStep(Step.begin);
                            return event;
                        case "register": {
                            if(!curServer.Sd.active) {
                                channel.createMessage("Showdown event not active right now").block(BLOCK);
                                return event;
                            }
                            channel.createMessage("Please enter your power in million with one decimal precision (e.g 25.3)").block(BLOCK);
                            participant.setStep(Step.power);
                            return event;
                        }
                        case "lanes": {
                            if(!curServer.Sd.active) {
                                channel.createMessage("No ongoing SD event!").block(BLOCK);
                                return event;
                            }
                            StringBuilder sb = curServer.getSDLanesString();
                            channel.createMessage(sb.toString()).block(BLOCK);
                            return event;
                        }
                        default: {
                            if(content.startsWith("open ")) {
                                if(!isR4) {
                                    channel.createMessage("only R4 can use open command").block(BLOCK);
                                    return event;
                                }
                                Matcher ma=oneFloatPattern.matcher(rawContent.substring(4));
                                if(ma.find()) {
                                    float pow=Float.parseFloat(ma.group(1));
                                    System.out.println("opening showdown with power threshold "  + pow);
                                    curServer.Sd.active=true;
                                    curServer.Sd.threshold=pow;
                                    if(curServer.Sd.save()) {
                                        channel.createMessage("Succesfully opened Showdown with power threshold at " + pow).block(BLOCK);
                                    } else {
                                        channel.createMessage("Unexpected error while trying to open showdown").block(BLOCK);
                                    }
                                } else {
                                    channel.createMessage("syntax is open <power threshold>").block(BLOCK);
                                }
                                return event;
                            } else if(content.startsWith("r4reg ")) {
                                if(!isR4) {
                                    channel.createMessage("only R4 can use r4reg command").block(BLOCK);
                                    return event;
                                }
                                Matcher ma=SDRegPattern.matcher(rawContent.substring(6));
                                if(ma.find()) {
                                    float pow=Float.parseFloat(ma.group(2));
                                    String name=ma.group(1).trim();
                                    SDPos lane;
                                    switch(ma.group(3)) {
                                        case "l": lane=SDPos.Left;break;
                                        case "r":lane=SDPos.Right;break;
                                        case "c": lane=SDPos.Center;break;
                                        default:lane=SDPos.Undef;
                                    }
                                    System.out.println("registering "  + name + "| " + ma.group(2)+" in "+lane+" lane");
                                    Participant p = curServer.createSDParticipant(name,pow,lane);
                                    if(p==null) {
                                        channel.createMessage("Unexpected error while trying to create participant "+name).block(BLOCK);
                                        return event;
                                    }
                                    channel.createMessage("Successfully registered "+p+" in "+lane+" lane").block(BLOCK);
                                } else {
                                    channel.createMessage("syntax is r4reg <name> <power>").block(BLOCK);
                                }
                                return event;
                            } else if(participant.step==Step.power) {
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
                                if (pow < 0.1 || pow > 1000.) {
                                    channel.createMessage("incorrect power value " + content).block(BLOCK);
                                } else {
                                    participant.power = pow;
                                    participant.setStep(Step.begin);
                                    participant.decideSDLane(curServer);
                                    if(participant.saveSD()) {
                                        channel.createMessage("You are successfully registered to **"+participant.lane+"** lane. Please go in-game and register in that lane now!").block(BLOCK);
                                    } else {
                                        channel.createMessage("Unexpected error while updating your data").block(BLOCK);
                                    }
                                    return event;
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







    static class SDEvent {
        boolean active=true;
        LocalDateTime start;
        float threshold;
        Guild guild;
        SDEvent(Guild g) {
            guild=g;
            start=LocalDateTime.now();
        }
        boolean save() {
            try{
                SDsave.setBoolean(1, active);
                SDsave.setFloat(2,threshold );
                SDsave.setLong( 3,guild.getId().asLong());
                SDsave.executeUpdate();
            } catch(SQLException e) {
                e.printStackTrace();
                return false;
            }
            return true;
        }

    }

    static class RREvent {
        static SimpleDateFormat df=new SimpleDateFormat("EEEEEEEEE KK:mm Z");
        public Date date;
        boolean active=true;
        boolean teamSaved=false;
        Guild guild;
        int nbTeams=-1;
        public RREvent(Guild g) { this.guild=g;}
        public String toString() { return df.format(date)+(active?"":"*");}
        boolean saveTeams(boolean saved) {
            try {
                RRsaveTeam.setBoolean(1,saved);
                RRsaveTeam.setLong(2,guild.getId().asLong());
                RRsaveTeam.executeUpdate();
            } catch(SQLException se) {
                se.printStackTrace();
                return false;
            }
            teamSaved=saved;
            return true;
        }
        boolean save() {
            try {
                deleteE.setLong(1, guild.getId().asLong());
                deleteE.executeUpdate();
                insertE.setDate(1, new java.sql.Date(date.getTime()));
                insertE.setBoolean(2, active);
                insertE.setLong(3, guild.getId().asLong());
                insertE.setBoolean(4, teamSaved);
                insertE.executeUpdate();
                deleteP.setLong(1, guild.getId().asLong());
                deleteP.executeUpdate();
            } catch(SQLException ex) {
                ex.printStackTrace();
                return false;
            }
            return true;
        }
        boolean close() {
            try {
                closeE.setLong(1, guild.getId().asLong());
                closeE.executeUpdate();
            } catch(SQLException ex) {
                ex.printStackTrace();
                return false;
            }
            active=false;
            return true;
        }
    }


    static PreparedStatement insertP,insertE,deleteP,deleteOneP,closeE,deleteE,selectRRevent,selectRRparticipants,
             updateRRTeam,updateRRreg,RRunregAll,RRsaveTeam,updateSDLane,SDsave,
            deleteLocalRRParticipants, deleteLocalSDParticipants,           updateUID,selectParticipants;
    private static void connectToDB() throws  SQLException {
        String dbUrl = System.getenv("JDBC_DATABASE_URL");
        dbConnection = DriverManager.getConnection(dbUrl);
        insertP = dbConnection.prepareStatement("INSERT INTO members(name,power,server,team,lane,uid,rr,isdiscord) VALUES(?,?,?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS);
        insertE = dbConnection.prepareStatement("INSERT INTO servers(rrdate,active,server,teamsaved) VALUES(?,?,?,?)", Statement.RETURN_GENERATED_KEYS);
        closeE = dbConnection.prepareStatement("UPDATE servers set active='0' where server=?", Statement.RETURN_GENERATED_KEYS);
        //saveTeam = dbConnection.prepareStatement("UPDATE servers set teamsaved='t' where server=?", Statement.RETURN_GENERATED_KEYS);
        deleteE = dbConnection.prepareStatement("DELETE from servers where server=?", Statement.RETURN_GENERATED_KEYS);
        deleteP = dbConnection.prepareStatement("delete from members where server=?");
        deleteOneP = dbConnection.prepareStatement("delete from members where name=? and server=?", Statement.RETURN_GENERATED_KEYS);
        selectRRevent = dbConnection.prepareStatement("SELECT * FROM servers where server=?");
        selectRRparticipants = dbConnection.prepareStatement("SELECT * FROM members where server=?");
        updateRRTeam =  dbConnection.prepareStatement("UPDATE  members set team=?,name=? where server=? and uid=?");
        deleteLocalRRParticipants =  dbConnection.prepareStatement("DELETE  from members where server=? and isdiscord='f' and lane='0' and rr='t' ");
        deleteLocalSDParticipants =  dbConnection.prepareStatement("DELETE  from members where server=? and isdiscord='f' and lane!='0' and rr='f' ");
        updateRRreg =  dbConnection.prepareStatement("UPDATE  members set rr=?,power=? where uid=?");
        RRsaveTeam = dbConnection.prepareStatement("UPDATE  servers set teamsaved=? where server=?");
        SDsave = dbConnection.prepareStatement("UPDATE  servers set sdactive=?,sdthreshold=? where server=?");
        updateSDLane =  dbConnection.prepareStatement("UPDATE  members set lane=?,power=? where server=? and name=?");
        RRunregAll = dbConnection.prepareStatement("UPDATE  members set rr='f', team='-1' where server=?");

        updateUID =  dbConnection.prepareStatement("UPDATE  members set uid=? where server=? and name=?");
        selectParticipants = dbConnection.prepareStatement("SELECT * FROM members");
    }
    static class Server {
        Guild guild;
        RREvent RRevent,newRRevent;
        SDEvent Sd;
        HashMap<Long,Participant> sessions = new HashMap<>();

        public Server(Guild guild) {
            this.guild=guild;
            RRevent= new RREvent(guild);
            newRRevent= new RREvent(guild);
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
            return  sessions.values().stream().filter(i -> i.lane != SDPos.Undef)
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
            res.sort((ArrayList<Participant> t1,ArrayList<Participant> t2)->
                    Float.compare(teamPow(t2), teamPow(t1)));
            return res;
        }
        ArrayList<ArrayList<Participant>> getRRTeams(int nbTeam,List<Participant> registered) {
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
            teams.sort((ArrayList<Participant> t1,ArrayList<Participant> t2)->
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
            final AtomicReference<SDPos> lane= new AtomicReference<>(SDPos.Undef);
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
                deleteLocalRRParticipants.setLong(1,getId());
                deleteLocalRRParticipants.executeUpdate();
                RRunregAll.setLong(1,getId());
                RRunregAll.executeUpdate();
            }catch(SQLException se) {
                se.printStackTrace();
                return false;
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
            Participant newby = new Participant(name,guild);
            newby.setRRregistered(rr);
            newby.power=power;
            if(newby.save()) {
                sessions.put(newby.uid,newby);
                return newby;
            }
            return null;
        }
        void initFromDB() {
            try {
                selectRRevent.setLong(1,getId());
                ResultSet rs = selectRRevent.executeQuery();
                if(rs.next()) { // read first event
                    RREvent e=new RREvent(guild);
                    e.date=rs.getDate("rrdate");
                    e.active=rs.getBoolean("active");
                    e.teamSaved=rs.getBoolean("teamsaved");
                    RRevent = e;

                    SDEvent se = new SDEvent(guild);
                    se.active = rs.getBoolean("sdactive");
                    se.threshold = rs.getFloat("sdthreshold");
                    Sd = se;

                } else {
                    RRevent=new RREvent(guild);
                    // first time event with empty DB is inactive
                    RRevent.active=false;
                    Sd = new SDEvent(guild);
                    Sd.active=false;
                }
                selectRRparticipants.setLong(1,getId());
                rs=selectRRparticipants.executeQuery();
                while(rs.next()) {
                    long uid=rs.getLong("uid");
                    Participant p=sessions.get(uid);
                    if(p==null) {
                        boolean isDiscord=rs.getBoolean("isdiscord");
                        if(isDiscord) {
                            Member m=guild.getMemberById(Snowflake.of(uid)).onErrorContinue((t,e)-> t.printStackTrace()).block(BLOCK);
                            if(m==null) {
                                System.err.println("Error retrieving member for "+uid);
                                continue;
                            }
                            p=new Participant(m,guild);
                        } else {
                            p=new Participant(rs.getString("name"),uid,guild);
                        }
                        sessions.put(uid, p);

                    }
                    p.registeredToRR =rs.getBoolean("rr");
                    p.power=rs.getFloat("power");
                    p.RRteamNumber =rs.getInt("team");
                    p.lane = SDPos.values()[rs.getInt("lane")];
                }

            } catch(SQLException e) {
                e.printStackTrace();
            }
        }

        public Participant createSDParticipant(String name, float pow, SDPos lane) {
            Participant newbie = new Participant(name,guild);
            newbie.lane=lane;
            newbie.power=pow;
            if(newbie.save()) {
                sessions.put(newbie.uid,newbie);
                return newbie;
            }
            return null;
        }
    }


    enum SDPos { Undef,Left,Center,Right}

    static class SDLane {
        int nbPlayers;
        int totalPower;
        int powerEstimate() {
            return -1;
        }
    }
    static class Participant {
        //String name;
        boolean isDiscord;
        long uid;
        String name;
        private final Member member;
        Guild guild;
        float power=0.0f;
        boolean registeredToRR =false;
        Step step=Step.begin;
        long timestamp=-1L;
        int RRteamNumber =-1;
        SDPos lane=SDPos.Undef;
        void setStep(Step s) { step=s; timestamp=System.currentTimeMillis();}
        boolean timedOut() { return (System.currentTimeMillis()-timestamp) > 60000 && step != Step.begin;}
        // create a discord-based participant
        private Participant(Member m,Guild g) {
            member=m; guild=g;isDiscord=true;
            name=member.getDisplayName();
            uid=member.getId().asLong();
        }
        //create a non-discord participant from first time
        private Participant(String n,Guild g) {
            member=null; guild=g;isDiscord=false;
            name=n;
            uid=generateUID(n);
        }
        //create a non-discord participant from existing uid
        private Participant(String n,long uid,Guild g) {
            member=null; guild=g;isDiscord=false;
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
        public String getName() { return name;}
        public long getGuildId() { return guild.getId().asLong();}
        public long getUid() { return uid;}

        boolean save() {
            try {
                insertP.setString(1, getName());
                insertP.setFloat(2, power);
                insertP.setLong(3,getGuildId());
                insertP.setInt(4,RRteamNumber);
                insertP.setInt(5,lane.ordinal());
                insertP.setLong(6,uid);
                insertP.setBoolean(7,registeredToRR);
                insertP.setBoolean(8,isDiscord);
                insertP.executeUpdate();
            } catch(SQLException e) {
                e.printStackTrace();
                return false;
            }
            return true;
        }
        boolean setRRregistered(boolean b) {
            try{
                updateRRreg.setBoolean(1,b);
                updateRRreg.setFloat(2,power);
                updateRRreg.setLong(3,getUid());
                updateRRreg.executeUpdate();
            }catch(SQLException se) {
                se.printStackTrace();
            }
            registeredToRR=b;
            return true;
        }

        public boolean updateRRTeam(int teamNb) {
            try {
                updateRRTeam.setInt(1, teamNb);
                updateRRTeam.setString(2, getName());
                updateRRTeam.setLong(3, getGuildId());
                updateRRTeam.setLong(4, getUid());
                updateRRTeam.executeUpdate();
            } catch(SQLException se) {
                se.printStackTrace();
                return false;
            }
            RRteamNumber =teamNb;
            return true;
        }
        public void swap(Participant o) {
            int curTeam= RRteamNumber;
            updateRRTeam(o.RRteamNumber);
            o.updateRRTeam(curTeam);
        }
        void decideSDLane(Server s) {
            List<Participant> sdguys=s.sessions.values().stream().filter(p->p.lane!=SDPos.Undef).collect(Collectors.toList());
            if (power < s.Sd.threshold) {
                lane=SDPos.Right;
            } else {
                double left=0.0,center=0.0;
                for(Participant p:sdguys) {
                    if(p.lane==SDPos.Left) left+=p.power;
                    else center+=p.power;
                }
                lane = left>center ? SDPos.Center : SDPos.Left;
            }
        }
        boolean saveSD() {
            try {
                updateSDLane.setInt(1,lane.ordinal());
                updateSDLane.setFloat(2,power);
                updateSDLane.setLong(3,getGuildId());
                updateSDLane.setString(4,getName());
                updateSDLane.executeUpdate();
            } catch(SQLException se) {
                se.printStackTrace();
                return false;
            }
            return true;
        }

        public double getPower() { return power;}
    }

    // class to draw teams on RR map
    private static class RRmapTeam {
        static final java.awt.Color mygreen = new java.awt.Color(11, 181, 51);
        static RRmapTeam[] get = {
                new RRmapTeam(365,25,180,120,475,25, java.awt.Color.MAGENTA,true,Math.PI/5),
                new RRmapTeam(385,225,175,80,485,325, java.awt.Color.CYAN,true,-Math.PI/6),
                new RRmapTeam(205,100,250,120,435,190,mygreen, true,Math.PI/5),
                new RRmapTeam(125,200,180,120,160,190, java.awt.Color.RED,false,Math.PI/5),
                new RRmapTeam(95,20,175,80,160,25, java.awt.Color.BLUE,false,-Math.PI/6)
        };

        static int[][] mapping = { {3},{1,4},{3,1,4},{2,1,4,5},{3,1,4,2,5}};
        int fromx,fromy,width,height,stringx,stringy;
        java.awt.Color c;
        boolean stringAlign;
        double angle;
        RRmapTeam(int x, int y, int w, int h, int sx, int sy, java.awt.Color co, boolean right, double a) {
            fromx=x; fromy=y; width=w;height=h; stringx=sx; stringy=sy; c=co; stringAlign=right; angle=a;
        }
        void draw(Graphics2D g2d, String name) {
            drawString(g2d,name,c,stringx,stringy,stringAlign);
            drawOval(g2d,fromx,fromy,width,height,c,angle);
        }
        static void drawTeam(Graphics2D g2d,int nb,String name) { RRmapTeam.get[nb-1].draw(g2d,name);}

        static void drawTeams(Graphics2D g2d,String[] teamLeaders) {
            Font currentFont=g2d.getFont();
            Font newFont = currentFont.deriveFont(Font.BOLD,18.0f);
            g2d.setFont(newFont);
            if(teamLeaders.length <1 || teamLeaders.length >5)  {
                System.err.println("Not right # of team leaders "+teamLeaders.length);
                return;
            }
            int[] teamMap= mapping[teamLeaders.length-1];
            for(int i=0; i<teamLeaders.length;++i) {
                drawTeam(g2d,teamMap[i],teamLeaders[i]);
            }
            if(teamLeaders.length == 2 || teamLeaders.length == 4 ) {
                drawString(g2d,teamLeaders[0]+" goes to center after 10 mins",
                        RRmapTeam.get[teamMap[0]-1].c, 10,150,true);
            }
        }
        private static void drawOval(Graphics2D g2d, int fromx, int fromy, int width, int height, java.awt.Color c, double angle) {
            //noinspection IntegerDivisionInFloatingPointContext
            g2d.setTransform(AffineTransform.getRotateInstance(angle,fromx+width/2,fromy+height/2));
            g2d.setPaint(c);
            g2d.setStroke(new BasicStroke((float) 3.0));
            g2d.drawOval(fromx,fromy,width,height);
        }

        private static void drawString(Graphics2D g2d, String txt, java.awt.Color c, int x, int y, boolean totheright) {
            g2d.setPaint(c);
            g2d.setTransform(AffineTransform.getRotateInstance(0));
            if(!totheright) x-= g2d.getFontMetrics().stringWidth(txt);
            g2d.drawString(txt,x,y);
        }
    }

}

