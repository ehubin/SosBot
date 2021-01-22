package sosbot;

import discord4j.core.object.entity.channel.MessageChannel;
import discord4j.rest.util.Color;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.text.ParseException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import static sosbot.Util.i18n;

@Slf4j
public class ReservoirRaidCommands extends ChannelAndCommands {
    static final String name ="\uD83D\uDCA6reservoir-raid\uD83D\uDCA6";
    static final String topic = i18n.getString("RRtopic");
    final Notification<Empty> RRcloseReg = new Notification<>(
            NotifType.RRcloseReg,
            new Duration[] {Duration.ofMinutes(5L),Duration.ofMinutes(30L),Duration.ofMinutes(120L)},
            (in) ->{
                getChannel(in.server).createMessage("@everyone Reservoir raid registration closes in "+ Util.format(in.before)+" at "+ Util.hhmm.format(in.basetime)+"\n"+"Go and register yourself if you want to participate").subscribe();
                log.info("sending SD next wave notif for minus "+in.before.toString());
            });
    final Notification<Empty> RRevent =new Notification<>(
    NotifType.RRevent,
            new Duration[] {Duration.ofMinutes(5L),Duration.ofMinutes(30L),Duration.ofMinutes(120L)},
            (in) -> notifyRR(in.server).onErrorResume((e)-> {
                if (e instanceof RecoverableError) {
                    log.error("RR notif error: "+e.getMessage());
                    return Mono.empty();
                } else return Mono.error(e);
            }).switchIfEmpty(Flux.empty().doOnComplete(() -> log.error("there were no elements")).cast(String.class))
            .reduce((s1, s2)->s1+", "+s2).doOnSuccess((s) ->{if(s!= null && s.length() >0) log.info("Sent RR notif to "+s);}).subscribe()
        );
    ReservoirRaidCommands() {
        super(name,topic);
        register(new HelpCommand());
        register(registerCmd);
        register(createCmd);
        register(closeCmd);
        register(reopenCmd);
        register(teamsCmd);
        register(swapCmd);
        register(moveCmd);
        register(showmapCmd);
        register(listCmd);
        register(r4regCmd);
        register(unregCmd);
        register(notifyCmd);
        init();
    }

    Command unregCmd = new RegexCommand("unreg\\s+(\\d+)",
            new Command.BaseData(true,"unreg <number>","cancels registration for another user by providing his number from list command")) {

        @Override
        protected void execute(Matcher ma, String content, Participant participant, MessageChannel channel, Server curServer) {
            int part_id;
            try {
                part_id = Integer.parseInt(ma.group(1));
            } catch(NumberFormatException e) {
                channel.createMessage("Incorrect participant number <"+ma.group(1)+"> expected unreg <nb> where number is taken from list command").subscribe();
                return;
            }
            List<Participant> list =curServer.getRegisteredRRparticipants(false);
            if(part_id<1 || part_id > list.size()) {
                channel.createMessage("Incorrect participant number <"+part_id+"> expected unreg <nb> where number is taken from list command").subscribe();
                return;
            }
            Participant target=list.get(part_id-1);
            target.registeredToRR=false;
            if(target.updateRRTeam(-1)) {
                channel.createMessage("Registration succesfully cancelled for <"+target.getName()+">").subscribe();
            } else {
                channel.createMessage("Unexpected error while trying to cancel registration of <"+target.getName()+">").subscribe();
            }
            if(curServer.RRevent.teamSaved) {
                curServer.RRevent.saveTeams(false);
                channel.createMessage(i18n.getString("RR.rmSaved")).subscribe();
            }
        }
    };
    Command registerCmd = new SimpleCommand("register",
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
                    if(curServer.getNbRRParticipant() >=30) {
                        // update DB
                        if (!curServer.RRevent.close()) {
                            channel.createMessage(" the 30 participant mark was reached but RR could not be closed for unexpected reason").subscribe();
                        } else {
                            Notification.cancelAllNotifs(NotifType.RRcloseReg,curServer);
                            channel.createMessage("There are now 30 participants and registration is closed").subscribe();
                        }
                    }
                    curServer.removeFollowupCmd(channel,participant);
                }
            }
        };
    };
    Command listCmd = new SimpleCommand("list",
            new Command.BaseData(false,"list","Diplays a list of currently registered people")) {
        @Override
        protected void execute(String content, Participant participant, MessageChannel channel, Server curServer) {
            List<Participant> registered = curServer.getRegisteredRRparticipants(false);
            if (registered.size() == 0) {
                channel.createMessage("Nobody registered yet").subscribe();
                return;
            }
            StringBuilder sb = new StringBuilder("Registered so far for ").append(curServer.RRevent).append("\n```");
            int idx=0,max = registered.stream().map(p -> p.getName().length()).max(Integer::compareTo).get();
            for (Participant p : registered) {
                sb.append(++idx).append(". ").append(p.getName()).append(" ".repeat(max + 5 - p.getName().length()))
                        .append(p.power).append("\n");
            }
            if(participant.isR4()) sb.append("you can type unreg <nb> to cancel registration for one of the above guys.");
            sb.append("```");
            channel.createMessage(sb.toString()).subscribe();
        }
    };
    Command closeCmd = new SimpleCommand("close",
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
                        Notification.cancelAllNotifs(NotifType.RRcloseReg,curServer);
                        channel.createMessage("Event \"" + curServer.RRevent + "\" now closed for registration! When your team composition is finalized (teams command) notify everyone (notify command)").subscribe();

                    }
                } else {
                    channel.createMessage("Closure of \"" + curServer.RRevent + "\" aborted").subscribe();
                }
                curServer.removeFollowupCmd(channel,participant);
            }
        };
    };
    Command reopenCmd = new SimpleCommand("reopen",
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
    Command teamsCmd = new SimpleCommand("teams",
            new Command.BaseData(false,"teams","Gives a breakdown of participants into teams")) {
        @Override
        protected void execute(String content, Participant participant, MessageChannel channel, Server curServer) {
            if(curServer.RRevent.teamSaved) {
                ArrayList<ArrayList<Participant>> teams = curServer.getRRSavedTeams();
                StringBuilder sb = Server.displayTeams(teams);
                if (participant.isR4())
                    sb.append("\nYou can swap players around by typing (e.g swap 1.2 3.1) to swap second player in team 1 with first player in team 3\n")
                            .append("Also you can move players. move 2.1 1 will move first player in team 2 into team 1.");
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


                if(curServer.getNbRRParticipant()==0) {
                    curServer.removeFollowupCmd(channel,participant);
                    channel.createMessage(" Nobody registered yet!").subscribe();
                    return ;
                }
                ArrayList<ArrayList<Participant>> teams = curServer.getRRTeams(nbTeam);
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

                    if(curServer.getNbRRParticipant() == 0) {
                        curServer.removeFollowupCmd(channel, participant);
                        channel.createMessage(" Nobody registered yet!").subscribe();
                        return;
                    }
                    ArrayList<ArrayList<Participant>> teams = curServer.getRRTeams(curServer.RRevent.nbTeams);
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
    Command showmapCmd = new SimpleCommand("showmap",
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

    Command createCmd = new SimpleCommand("create",
            new Command.BaseData(true,"create","Creates the next reservoir raid event and erases the previous one")) {

        @Override
        protected void execute(String content, Participant participant, MessageChannel channel, Server curServer) {
            curServer.setFollowUpCmd(channel,participant,getTime);
            channel.createMessage(participant.getName() + " please enter event utc date and time (e.g Sunday  20:00)").subscribe();
        }
        final Command getTime = new FollowupCommand() {
            @Override
            protected void execute(String content, Participant participant, MessageChannel channel, Server curServer) {
                try {
                    if(content.trim().equalsIgnoreCase("cancel")) {
                        curServer.removeFollowupCmd(channel,participant);
                        channel.createMessage("Aborted!").subscribe();
                        return;
                    }
                    curServer.setFollowUpCmd(channel,participant,yesNo);
                    curServer.newRRevent.date= Util.getParser().parseOne(content);
                    channel.createMessage("Do you confirm you want to create RR event for "+ Util.format(curServer.newRRevent.date)).subscribe();
                } catch(ParseException e) {
                    curServer.removeFollowupCmd(channel,participant);
                    channel.createMessage("Ambiguous date "+content.trim()).subscribe();
                }
            }
        };
        final Command yesNo = new FollowupCommand() {
            @Override
            protected void execute(String content, Participant participant, MessageChannel channel, Server curServer) {
                if(content.trim().equalsIgnoreCase("yes")) {
                    curServer.setFollowUpCmd(channel,participant,lastOne);
                    channel.createMessage("Good! now please enter date and time of the RR registration closure").subscribe();
                } else {
                    curServer.setFollowUpCmd(channel,participant,getTime);
                    channel.createMessage("No worries enter it again (or type \"cancel\" to abort)").subscribe();
                }
            }
        };
        final Command lastOne = new FollowupCommand() {
            @Override
            protected void execute(String content, Participant participant, MessageChannel channel, Server curServer) {
                Instant regTime;
                try {
                    regTime= Util.getParser().parseOne(content);
                } catch(ParseException e) {
                    channel.createMessage("Incorrect date/time "+content+"please re-enter a correct one").subscribe();
                    return;
                }
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
                // create notifications
                RRcloseReg.scheduleNotif(NotifType.RRcloseReg,curServer,regTime);

                curServer.removeFollowupCmd(channel,participant);
                channel.createMessage("Event \"" + curServer.RRevent + "\" now live!").subscribe();
            }
        };

    };

    Command swapCmd = new RegexCommand(Pattern.compile("swap\\s+(\\d).(\\d+)\\s+(\\d).(\\d+)",Pattern.CASE_INSENSITIVE),
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
            if(toTeam==fromTeam) {
                channel.createMessage("Swapping players from the same team does not make sense").subscribe();
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
    Command moveCmd = new RegexCommand(Pattern.compile("move\\s+(\\d).(\\d+)\\s+(\\d)",Pattern.CASE_INSENSITIVE),
            new Command.BaseData(true,"move x.y z","Swaps player y in team x  in team z")) {

        @Override
        protected void execute(Matcher ma, String content, Participant participant, MessageChannel channel, Server curServer) {
            if(!curServer.RRevent.teamSaved) {
                channel.createMessage("You have to save teams before swapping players").subscribe();
                return;
            }
            int fromTeam=Integer.parseInt(ma.group(1));
            int fromPlayer=Integer.parseInt(ma.group(2));
            int toTeam=Integer.parseInt(ma.group(3));
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
            if(toTeam==fromTeam) {
                channel.createMessage("Moving to the same team does not make sense").subscribe();
                return;
            }
            if(fromPlayer<=0 || fromPlayer > teams.get(fromTeam-1).size()) {
                channel.createMessage("Invalid player number "+fromPlayer).subscribe();
                return;
            }
            Participant from = teams.get(fromTeam-1).get(fromPlayer-1);
            int to=teams.get(toTeam-1).get(0).RRteamNumber;
            log.info("==>"+to+","+toTeam);
            if(from.updateRRTeam(to)) {
                StringBuilder sb = Server.displayTeams(curServer.getRRSavedTeams());
                sb.insert(0,"Moved "+from.getName()+" from "+teams.get(fromTeam-1).get(0).getName()+"'s team to "+teams.get(toTeam-1).get(0).getName()+"'s one (Team order/numbers might have changed).");
                channel.createMessage(sb.toString()).subscribe();
            } else {
                channel.createMessage("Unexpected error while moving "+from.getName()).subscribe();
            }
        }
    };
    Command r4regCmd = new RegexCommand(Pattern.compile("r4reg\\s+(.*\\S)\\s+(\\d+.?\\d*)",Pattern.CASE_INSENSITIVE),
            new Command.BaseData(true,"r4reg <name> <power>","Registers a participant who did/can not go through the bot")) {

        @Override
        protected void execute(Matcher ma, String content, Participant participant, MessageChannel channel, Server curServer) {
            if(!curServer.RRevent.active) {
                channel.createMessage("Registrations closed (use reopen command first if really needed)").subscribe();
                return;
            }
            float pow=Float.parseFloat(ma.group(2).trim());
            Command.log.info("registering "  + ma.group(1) + "| " + ma.group(2));
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
            if(curServer.getNbRRParticipant() >=30) {
                // update DB
                if (!curServer.RRevent.close()) {
                    channel.createMessage(" the 30 participant mark was reached but RR could not be closed for unexpected reason").subscribe();
                } else {
                    Notification.cancelAllNotifs(NotifType.RRcloseReg,curServer);
                    channel.createMessage("There are now 30 participants and registration is closed").subscribe();
                }
            }
        }
    };
    Command notifyCmd = new SimpleCommand("notify",
            new Command.BaseData(true,"notify","R4 can use this command to send everyone their team info for next RR")) {

        @Override
        protected void execute(String content, Participant participant, MessageChannel channel, Server curServer) {
            if (!curServer.RRevent.teamSaved || curServer.RRevent.active ) {
                channel.createMessage("You have to save teams and close registration before notifying people").subscribe();
                return;
            }
            RRevent.scheduleNotif(NotifType.RRevent,curServer,curServer.RRevent.date);
            notifyRR(curServer).onErrorResume((e)->{
               if(e instanceof RecoverableError) {
                   channel.createMessage(e.getMessage()).subscribe();
                   return Mono.empty();
               }
               else return Mono.error(e);
            }).switchIfEmpty(Flux.empty().doOnComplete(() ->channel.createMessage("Nobody to notify!").subscribe()).cast(String.class))
                    .reduce((s1, s2)->s1+", "+s2)
                    .doOnSuccess((s) ->{if(s!= null && s.length() >0) channel.createMessage("Sent RR notif to "+s).subscribe();})
                    .subscribe();
        }
    };

    static Flux<String> notifyRR(Server curServer) {
        ArrayList<ArrayList<Participant>> teams = curServer.getRRSavedTeams();
        byte[] img;
        BufferedImage tmpImage = RRmapTeam.getMapImage();
        if (tmpImage == null) {
            return Flux.error(new RecoverableError("Unable to get Map image"));
        }
        Graphics2D g2d = tmpImage.createGraphics();
        String[] leaders = teams.stream().filter((t) -> t.size() > 0).map((t) -> t.get(0).getName()).toArray(String[]::new);

        RRmapTeam.drawTeams(g2d, leaders);
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ImageIO.write(tmpImage, "PNG", bos);
            img = bos.toByteArray();
        } catch (Exception e) {
            return Flux.error(e);
        }
        StringBuilder sb = new StringBuilder();
        return Flux.fromIterable(teams).flatMap((team)-> Flux.fromIterable(Util.getIterable(team,team))).flatMap((tup) -> {
            Participant p=tup.getT1();
            ArrayList<Participant> t= tup.getT2();
            if (p.isDiscord && p.member != null) {
                sb.setLength(0);
                for (Participant pa : t) {
                    if (p != pa) {
                        sb.append(" - ").append(pa.getName()).append(" (").append(pa.power).append(")\n");
                    }
                }
                final String teamMates=sb.toString();
                final String teamAssignment = t.get(0) == p ?
                        "You are the leader of one of the " + teams.size() + " teams" :
                        "You have been assigned to " + t.get(0).getName() + "'s team";
                return p.member.getPrivateChannel().flatMap((pc) ->
                        pc.createMessage(mcs -> {
                            mcs.addFile("rrmap.png", new ByteArrayInputStream(img));
                            mcs.setEmbed(ecs -> {
                                ecs.setDescription("Your Reservoir Raid info for " + curServer.RRevent);
                                ecs.addField(teamAssignment, "\u200b", false);
                                if(t.size()>1) ecs.addField("Your team mates", teamMates, false);
                                ecs.addField("\u200b", "If you want to change to another team ask one of the R4s", false);
                                ecs.addField("Your attendance to the event is important! We cannot cancel your registration anymore. We count on you!", "\u200b", false);
                                ecs.setImage("attachment://rrmap.png").setColor(Color.MOON_YELLOW);
                            });
                        }).onErrorResume((e) -> {
                    log.error("Error sending to " + p + ": caused by " + e.getMessage());
                    return Mono.empty();
                }).flatMap((m) -> Mono.just(p.getName())).defaultIfEmpty(""));
            } else {
                return Mono.just("");
            }
        }).filter((String s) -> s.length() > 0);
    }

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
