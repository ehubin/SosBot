import com.joestelmach.natty.DateGroup;
import com.joestelmach.natty.Parser;
import discord4j.core.object.entity.channel.MessageChannel;
import discord4j.rest.util.Color;
import reactor.core.publisher.Mono;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class ReservoirRaidCommands {
    static final String name ="\uD83D\uDCA6reservoir-raid\uD83D\uDCA6";
    static void init() {
        ArrayList<Command> commands=new ArrayList<>();
        commands.add(new HelpCommand());
        commands.add(registerCmd);
        commands.add(createCmd);
        commands.add(closeCmd);
        commands.add(reopenCmd);
        commands.add(teamsCmd);
        commands.add(swapCmd);
        commands.add(showmapCmd);
        commands.add(listCmd);
        commands.add(r4regCmd);
        commands.add(notifyCmd);
        Command.registerCmds(name,commands);
    }

    static Parser dateParser;
    public static Parser getParser() {
        if(dateParser==null) {
            dateParser=new Parser();
        }
        return dateParser;
    }
    static Command registerCmd = new SimpleCommand("register",
            new Command.BaseData(false,"register","Registers yourself to next reservoir raid event")) {
        @Override
        protected void execute(String content, Participant participant, MessageChannel channel, Server curServer)  {
            if (participant.registeredToRR) {
                channel.createMessage(participant.getName() + " you are already registered!").subscribe();
            } else if(!curServer.RRevent.active) {
                channel.createMessage("Sorry RR event now closed to registrations!").subscribe();
            }
            else {
                curServer.setFollowUpCmd(channel,participant,yesNo);
                channel.createMessage(participant.getName() + " can you commit to be online " + curServer.RRevent + "(yes/no)").subscribe();
            }
        }
        final Command yesNo= new FollowupCommand() {
            @Override
            protected void execute(String content, Participant participant, MessageChannel channel, Server curServer) {
                if(content.trim().equalsIgnoreCase("yes")) {
                    curServer.setFollowUpCmd(channel,participant,power);
                    channel.createMessage("Please enter your current overall Battle Power(e.g 30 or 30.2) to help creating balanced teams").subscribe();
                } else {
                    channel.createMessage("Registration aborted").subscribe();
                    curServer.removeFollowupCmd(channel,participant);
                }
            }
        };
        final Command power= new FollowupCommand() {
            @Override
            protected void execute(String content, Participant participant, MessageChannel channel, Server curServer) {
                float pow;
                try {
                    pow = Float.parseFloat(content.trim());
                } catch (NumberFormatException nfe) {
                    channel.createMessage("incorrect number format " + content).subscribe();
                    return;
                }
                if (pow < 0.1 || pow > 1000.) {
                    channel.createMessage("incorrect power value " + content).subscribe();
                } else {
                    participant.power = pow;
                    if(!participant.setRRregistered(true)) {
                        channel.createMessage("Unexpected error while saving registration data!").subscribe();
                        curServer.removeFollowupCmd(channel,participant);
                        return;
                    }
                    if(curServer.RRevent.teamSaved) {
                        // unsave the teams as a new participant has been added otherwise he will be in no team
                        curServer.RRevent.saveTeams(false);
                        channel.createMessage("Removing saved team while adding new user").subscribe();
                    }
                    channel.createMessage(participant.getName() + " your registration is confirmed we count on you!").subscribe();
                    curServer.removeFollowupCmd(channel,participant);
                }
            }
        };
    };
    static Command listCmd = new SimpleCommand("list",
            new Command.BaseData(false,"list","Diplays a list of currently registered people")) {
        @Override
        protected void execute(String content, Participant participant, MessageChannel channel, Server curServer) {
            List<Participant> registered = curServer.sessions.values().stream()
                    .filter(i -> i.registeredToRR)
                    .sorted(Comparator.comparingDouble(Participant::getPower))
                    .collect(Collectors.toList());
            if (registered.size() == 0) {
                channel.createMessage("Nobody registered yet").subscribe();
                return;
            }
            StringBuilder sb = new StringBuilder("Registered so far for ").append(curServer.RRevent).append("\n```");
            int max = registered.stream().map(p -> p.getName().length()).max(Integer::compareTo).get();
            for (Participant p : registered) {
                sb.append(p.getName()).append(" ".repeat(max + 5 - p.getName().length())).append(p.power).append("\n");
            }
            sb.append("```");
            channel.createMessage(sb.toString()).subscribe();
        }
    };
    static Command closeCmd = new SimpleCommand("close",
            new Command.BaseData(true,"close","Closes the registration process")) {

        @Override
        protected void execute(String content, Participant participant, MessageChannel channel, Server curServer) {
            curServer.setFollowUpCmd(channel,participant,yesNo);
            channel.createMessage(participant.getName() + " are you sure you want to stop registration for " + curServer.RRevent + "(yes/no)").subscribe();
        }
        final Command yesNo=new FollowupCommand() {
            @Override
            protected void execute(String content, Participant participant, MessageChannel channel, Server curServer) {
                if(content.trim().equalsIgnoreCase("yes")) {
                    // update DB
                    if (!curServer.RRevent.close()) {
                        channel.createMessage("Event \"" + curServer.RRevent + "\" could not be closed for unexpected reason").subscribe();
                    } else {
                        channel.createMessage("Event \"" + curServer.RRevent + "\" now closed for registration! When your team composition is finalized (teams command) notify everyone (notify command)").subscribe();
                    }
                } else {
                    channel.createMessage("Closure of \"" + curServer.RRevent + "\" aborted").subscribe();
                }
                curServer.removeFollowupCmd(channel,participant);
            }
        };
    };
    static Command reopenCmd = new SimpleCommand("reopen",
            new Command.BaseData(true,"reopen","Re-opens the registration process")) {
        @Override
        protected void execute(String content, Participant participant, MessageChannel channel, Server curServer) {
            if (!curServer.RRevent.reopen()) {
                channel.createMessage("Event \"" + curServer.RRevent + "\" could not be re-opened for unexpected reason").subscribe();
            } else {
                channel.createMessage("Event \"" + curServer.RRevent + "\" now re-opened for registration!").subscribe();
            }
        }
    };
    static Command teamsCmd = new SimpleCommand("teams",
            new Command.BaseData(false,"teams","Gives a breakdown of participants into teams")) {
        @Override
        protected void execute(String content, Participant participant, MessageChannel channel, Server curServer) {
            if(curServer.RRevent.teamSaved) {
                ArrayList<ArrayList<Participant>> teams = curServer.getRRSavedTeams();
                StringBuilder sb = Server.displayTeams(teams);
                if (participant.isR4()) sb.append("\nYou can swap players around by typing (e.g swap 1.2 3.1) to swap second player in team 1 with first player in team 3");
                channel.createMessage(sb.toString()).subscribe();
            } else {
                if(participant.isR4()) {
                    curServer.setFollowUpCmd(channel,participant,teamNb);
                    channel.createMessage("How many teams do you want?").subscribe();
                } else {
                    channel.createMessage("Registration still ongoing. Team composition not finalised yet.").subscribe();
                }
            }
        }
        final Command teamNb= new FollowupCommand() {
            @Override
            protected void execute(String content, Participant participant, MessageChannel channel, Server curServer) {
                int nbTeam;
                try {
                    nbTeam = Integer.parseInt(content.trim());
                } catch (NumberFormatException ne) {
                    channel.createMessage("Wrong number of teams " + content).subscribe();
                    return;
                }
                if(nbTeam<=0 || nbTeam>5) {
                    channel.createMessage("Number of teams should be between 1 and 5").subscribe();
                    return;
                }

                List<Participant> registered = curServer.getRegisteredRRparticipants();
                if(registered.size()==0) {
                    curServer.removeFollowupCmd(channel,participant);
                    channel.createMessage(" Nobody registered yet!").subscribe();
                    return ;
                }
                ArrayList<ArrayList<Participant>> teams = curServer.getRRTeams(nbTeam,registered);
                assert(teams!= null);
                curServer.RRevent.nbTeams=nbTeam;
                curServer.setFollowUpCmd(channel,participant,yesNo);
                channel.createMessage(Server.displayTeams(teams)
                        .append("Do you want to save this team configuration for the event? (yes/no)").toString()).subscribe();
            }
        };
        final Command yesNo = new FollowupCommand() {
            @Override
            protected void execute(String content, Participant participant, MessageChannel channel, Server curServer) {
                if (content.trim().equalsIgnoreCase("yes")) {
                    List<Participant> registered = curServer.getRegisteredRRparticipants();
                    if (registered.size() == 0) {
                        curServer.removeFollowupCmd(channel, participant);
                        channel.createMessage(" Nobody registered yet!").subscribe();
                        return;
                    }
                    ArrayList<ArrayList<Participant>> teams = curServer.getRRTeams(curServer.RRevent.nbTeams, registered);
                    assert teams != null;
                    if (!curServer.RRevent.saveTeams(true)) {
                        channel.createMessage("unexpected error while trying to save teams");
                        curServer.removeFollowupCmd(channel, participant);
                        return;
                    }
                    for (int i = 0; i < teams.size(); ++i) {
                        for (Participant p : teams.get(i)) {
                            if (!p.updateRRTeam(i + 1)) {
                                channel.createMessage("Failure in saving participant team data");
                                curServer.removeFollowupCmd(channel, participant);
                                System.err.println("participant save error for " + p);
                                return;
                            }
                        }
                    }

                    channel.createMessage("Teams saved successfully").subscribe();
                }
                curServer.removeFollowupCmd(channel,participant);
            }
        };

    };
    static Command showmapCmd = new SimpleCommand("showmap",
            new Command.BaseData(false,"showmap","Displays a map of the game suggesting team placements")) {

        @Override
        protected void execute(String content, Participant participant, MessageChannel channel, Server curServer) {
            if(!curServer.RRevent.teamSaved) {
                channel.createMessage("Can only display map when teams have been saved.").subscribe();
                return;
            }
            ArrayList<ArrayList<Participant>> teams = curServer.getRRSavedTeams();
            BufferedImage tmpImage=RRmapTeam.getMapImage();
            if(tmpImage==null) {
                channel.createMessage("Error while retrieving map image").subscribe();
                return;
            }
            Graphics2D g2d=tmpImage.createGraphics();
            String[] leaders= new String[teams.size()];
            int i=0;
            for(ArrayList<Participant> t:teams) { leaders[i++]= t.get(0).getName();}
            RRmapTeam.drawTeams(g2d,leaders);
            try {
                ByteArrayOutputStream bos= new ByteArrayOutputStream();
                ImageIO.write(tmpImage,"PNG",bos);
                final byte[] img=bos.toByteArray();
                channel.createMessage(mcs-> {
                    mcs.addFile("rrmap.png",new ByteArrayInputStream(img));
                    mcs.setEmbed(ecs-> ecs.setImage("attachment://rrmap.png").setColor(Color.MOON_YELLOW));
                }).subscribe();
            } catch(Exception e){
                e.printStackTrace();
            }
        }
    };

    static Command createCmd = new SimpleCommand("create",
            new Command.BaseData(true,"create","Creates the next reservoir raid event and erases the previous one")) {

        @Override
        protected void execute(String content, Participant participant, MessageChannel channel, Server curServer) {
            curServer.setFollowUpCmd(channel,participant,getTime);
            channel.createMessage(participant.getName() + " please enter event date (e.g Sunday the 12th at 20:00 utc)").subscribe();
        }
        final Command getTime = new FollowupCommand() {
            @Override
            protected void execute(String content, Participant participant, MessageChannel channel, Server curServer) {
                List<DateGroup> dg=getParser().parse(content.trim());
                if(dg.size()==1 && dg.get(0).getDates().size()==1) {
                    curServer.newRRevent.date=dg.get(0).getDates().get(0);
                    curServer.setFollowUpCmd(channel,participant,yesNo);
                    channel.createMessage("do you confirm you want to create new RR event \"" + curServer.newRRevent + "\" (yes/no)").subscribe();
                } else {
                    curServer.removeFollowupCmd(channel,participant);
                    channel.createMessage("Ambiguous date "+content.trim()).subscribe();
                }
            }
        };
        final Command yesNo = new FollowupCommand() {
            @Override
            protected void execute(String content, Participant participant, MessageChannel channel, Server curServer) {
                if(content.trim().equalsIgnoreCase("yes")) {
                    if (!curServer.newRRevent.save()) {
                        channel.createMessage("Failure while saving RR event").subscribe();
                        curServer.removeFollowupCmd(channel,participant);
                        return;
                    }
                    curServer.RRevent = curServer.newRRevent;
                    curServer.newRRevent = new Server.RREvent(curServer.guild);
                    if (!curServer.unregisterRR()) {
                        channel.createMessage("Unexpected error while updating participant status").subscribe();
                        curServer.removeFollowupCmd(channel,participant);
                        return;
                    }
                    curServer.removeFollowupCmd(channel,participant);
                    channel.createMessage("Event \"" + curServer.RRevent + "\" now live!").subscribe();
                }
            }
        };
    };
    static Command swapCmd = new RegexCommand(Pattern.compile("swap\\s+(\\d).(\\d+)\\s+(\\d).(\\d+)",Pattern.CASE_INSENSITIVE),
            new Command.BaseData(true,"swap x.y z.t","Swaps player y in team x with player t in team z")) {

        @Override
        protected void execute(Matcher ma, String content, Participant participant, MessageChannel channel, Server curServer) {
            if(!curServer.RRevent.teamSaved) {
                channel.createMessage("You have to save teams before swapping players").subscribe();
                return;
            }
            int fromTeam=Integer.parseInt(ma.group(1));
            int fromPlayer=Integer.parseInt(ma.group(2));
            int toTeam=Integer.parseInt(ma.group(3));
            int toPlayer=Integer.parseInt(ma.group(4));
            ArrayList<ArrayList<Participant>> teams = curServer.getRRSavedTeams();
            int teamNb = teams.size();
            if(fromTeam<=0 || fromTeam>teamNb) {
                channel.createMessage("Invalid team number "+fromTeam).subscribe();
                return;
            }
            if(toTeam<=0 || toTeam>teamNb) {
                channel.createMessage("Invalid team number "+toTeam).subscribe();
                return;
            }
            if(fromPlayer<=0 || fromPlayer > teams.get(fromTeam-1).size()) {
                channel.createMessage("Invalid player number "+fromPlayer).subscribe();
                return;
            }
            if(toPlayer<=0 || toPlayer > teams.get(toTeam-1).size()) {
                channel.createMessage("Invalid player number "+toPlayer).subscribe();
                return;
            }
            Participant from = teams.get(fromTeam-1).get(fromPlayer-1);
            Participant to = teams.get(toTeam-1).get(toPlayer-1);
            if(from.swap(to)) {
                StringBuilder sb = Server.displayTeams(curServer.getRRSavedTeams());
                sb.insert(0,"Swapping "+from.getName()+" and "+to.getName());
                channel.createMessage(sb.toString()).subscribe();
            } else {
                channel.createMessage("Unexpected error while swapping").subscribe();
            }
        }
    };

    static Command r4regCmd = new RegexCommand(Pattern.compile("r4reg\\s+(.*\\S)\\s+(\\d+.?\\d*)",Pattern.CASE_INSENSITIVE),
            new Command.BaseData(true,"r4reg <name> <power>","Registers a participant who did/can not go through the bot")) {

        @Override
        protected void execute(Matcher ma, String content, Participant participant, MessageChannel channel, Server curServer) {
            if(!curServer.RRevent.active) {
                channel.createMessage("Registrations closed (use reopen command first if really needed)").subscribe();
                return;
            }
            float pow=Float.parseFloat(ma.group(2).trim());
            _logger.info("registering "  + ma.group(1) + "| " + ma.group(2));
            Participant p = curServer.createRRParticipant(ma.group(1),pow,true);
            if(p==null) {
                channel.createMessage("Unexpected error while trying to create participant "+ma.group(1)).subscribe();
                return;
            }
            if(curServer.RRevent.teamSaved) {
                // unsave the teams as a new participant has been added otherwise he will be in no team
                if(curServer.RRevent.saveTeams(false))  channel.createMessage("Removing saved team while adding new user").subscribe();
                else channel.createMessage("Unexpected Error while removing saved team").subscribe();
            }
            channel.createMessage("Successfully registered "+p).subscribe();
        }
    };
    static Command notifyCmd = new SimpleCommand("notify",
            new Command.BaseData(true,"notify","R4 can use this command to send everyone their team info for next RR")) {

        @Override
        protected void execute(String content, Participant participant, MessageChannel channel, Server curServer) {
            if (!curServer.RRevent.teamSaved || curServer.RRevent.active ) {
                channel.createMessage("You have to save teams and close registration before notifying people").subscribe();
                return;
            }
            ArrayList<ArrayList<Participant>> teams = curServer.getRRSavedTeams();
            byte[] img;
            BufferedImage tmpImage=RRmapTeam.getMapImage();
            if(tmpImage==null) {
                channel.createMessage("Unable to get Map image").subscribe();
                return;
            }
            Graphics2D g2d = tmpImage.createGraphics();
            String[] leaders = new String[teams.size()];
            int i = 0;
            for (ArrayList<Participant> t : teams) {
                leaders[i++] = t.get(0).getName();
            }
            RRmapTeam.drawTeams(g2d, leaders);
            try {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                ImageIO.write(tmpImage, "PNG", bos);
                img = bos.toByteArray();
            } catch (Exception e) {
                e.printStackTrace();
                return;
            }
            StringBuilder sb = new StringBuilder();
            List<Mono<String>> notifs = new ArrayList<>();
            for (ArrayList<Participant> t : teams) {
                for (Participant p : t) {
                    if (p.isDiscord && p.member != null) {
                        sb.setLength(0);
                        for (Participant pa : t) {
                            if (p != pa) {
                                sb.append(" - ").append(pa.getName()).append(" (").append(pa.power).append(")\n");
                            }
                        }
                        final String teamMates = sb.toString();
                        final String teamAssignment = t.get(0) == p ?
                                "You are the leader of one of the " + teams.size() + " teams" :
                                "You have been assigned to " + t.get(0).getName() + "'s team";

                        Mono<String> notif =p.member.getPrivateChannel().flatMap((pc) -> {
                            pc.createMessage(mcs -> {
                                mcs.addFile("rrmap.png", new ByteArrayInputStream(img));
                                mcs.setEmbed(ecs -> {
                                    ecs.setDescription("Your Reservoir Raid info for " + curServer.RRevent);
                                    ecs.addField(teamAssignment, "\u200b", false);
                                    ecs.addField("Your team mates", teamMates, false);
                                    ecs.addField("\u200b", "If you want to change to another team ask one of the R4s", false);
                                    ecs.addField("Your attendance to the event is important! We cannot cancel your registration anymore. We count on you!", "\u200b", false);
                                    ecs.setImage("attachment://rrmap.png").setColor(Color.MOON_YELLOW);
                                });
                            }).subscribe(null,
                                    thr -> {
                                        thr.printStackTrace();
                                        System.err.println("Error while sending message to " + p.getName());
                                    },
                                    () -> System.out.println("Sent to " + p.getName())
                            );
                            return Mono.just(p.getName());
                        });
                        notifs.add(notif);
                    }
                }
            }
            if(notifs.size()>0) {
                Mono.zip(notifs, obj -> Arrays.stream(obj).map(Object::toString).toArray(String[]::new))
                        .flatMap((sa) -> channel.createMessage("Notified " + String.join(", ", sa))).subscribe();
            } else {
                channel.createMessage("Nobody to notify!!!").subscribe();
            }
        }
    };

    // class to draw teams on RR map
    static class RRmapTeam {
        static BufferedImage rrmap = null;
        static BufferedImage getMapImage() {
            if(rrmap == null) {
                try { rrmap=ImageIO.read(new File("docs/rrmap.png")); }
                catch(IOException io) {
                    io.printStackTrace();
                    return null;
                }
            }
            return new BufferedImage(rrmap.getColorModel(),
                    rrmap.copyData(null),
                    rrmap.isAlphaPremultiplied(),null);
        }

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
