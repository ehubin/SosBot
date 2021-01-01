import discord4j.core.object.entity.channel.MessageChannel;

import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ShowdownCommands {
    static final String name="\u2694showdown\u2694";

    static void init() {
        ArrayList<Command> commands=new ArrayList<>();
        commands.add(new HelpCommand());
        commands.add(registerCmd);
        commands.add(openCmd);
        commands.add(lanesCmd);
        commands.add(r4regCmd);
        Command.registerCmds(name,commands);
    }

    static Command registerCmd = new SimpleCommand("register",
            new Command.BaseData(false,
                    "register",
                    "register to showdown event to get your lane assignment")) {
        @Override
        protected void execute(String content, Participant p, MessageChannel ch, Server s) {
            if(!s.Sd.active) {
                ch.createMessage("Showdown event not active right now").subscribe();
                return;
            }
            if(p.lane != SDPos.Undef) {
                ch.createMessage("You are already registered to **" + p.lane + "** lane.").subscribe();
                return;
            }
            s.setFollowUpCmd(ch, p,powerSD );
            ch.createMessage("Please enter your power in million with one decimal precision (e.g 25.3)").subscribe();
        }

        final Command powerSD= new FollowupCommand() {
            @Override
            protected void execute(String content, Participant participant, MessageChannel ch, Server s) {
                float pow;
                try {
                    pow = Float.parseFloat(content);
                } catch (NumberFormatException nfe) {
                    ch.createMessage("incorrect number format " + content).subscribe();
                    return;
                }
                if (pow < 0.1 || pow > 1000.) {
                    ch.createMessage("incorrect power value " + content).subscribe();
                } else {
                    participant.power = pow;
                    participant.decideSDLane(s);
                    if (participant.saveSD()) {
                        ch.createMessage("You are successfully registered to **" + participant.lane + "** lane. Please go in-game and register in that lane now!").subscribe();
                    } else {
                        ch.createMessage("Unexpected error while updating your data").subscribe();
                    }
                }
                s.removeFollowupCmd(ch,participant);
            }
        };
    };

    static Command openCmd = new RegexCommand(Pattern.compile("open\\s+(\\d+.?\\d*)", Pattern.CASE_INSENSITIVE),
                                                new Command.BaseData(true,"open <power>",
                                                        "Opens showdown event with given power limit")) {
        @Override
        protected void execute(Matcher ma, String content, Participant p, MessageChannel channel, Server curServer) {
            float pow=Float.parseFloat(ma.group(1));
            System.out.println("opening showdown with power threshold "  + pow);
            curServer.Sd.active=true;
            curServer.Sd.threshold=pow;
            if(curServer.Sd.save()) {
                curServer.Sd.cleanUp();
                channel.createMessage("Successfully opened Showdown with power threshold at " + pow).subscribe();
            } else {
                channel.createMessage("Unexpected error while trying to open showdown").subscribe();
            }
        }
    };

    static Command lanesCmd = new SimpleCommand("lanes",
            new Command.BaseData(false,"lanes","Shows who is registered in which lane")) {
        @Override
        protected void execute(String content, Participant participant, MessageChannel channel, Server curServer) {
            if(!curServer.Sd.active) {
                channel.createMessage("No ongoing SD event!").subscribe();
            } else {
                StringBuilder sb = curServer.getSDLanesString();
                channel.createMessage(sb.toString()).subscribe();
            }
        }
    };
    static Command r4regCmd = new RegexCommand(Pattern.compile("r4reg(.*\\S)\\s+(\\d+.?\\d*)\\s+([lrc])", Pattern.CASE_INSENSITIVE),
            new Command.BaseData(true,"r4reg <name> <power> <lane>",
                    "Allows to register a participant that did not go through the bot. Power in million and lane should be l,r or c")) {
        @Override
        protected void execute(Matcher ma, String content, Participant participant, MessageChannel channel, Server curServer) {
            float pow=Float.parseFloat(ma.group(2));
            String name=ma.group(1).trim();
            SDPos lane;
            switch(ma.group(3)) {
                case "l": lane= SDPos.Left;break;
                case "r":lane= SDPos.Right;break;
                case "c": lane= SDPos.Center;break;
                default:lane= SDPos.Undef;
            }
            System.out.println("registering "  + name + "| " + ma.group(2)+" in "+lane+" lane");
            Participant p = curServer.createSDParticipant(name,pow,lane);
            channel.createMessage(p==null?"Unexpected error while trying to create participant "+name :
                    "Successfully registered "+p+" in "+lane+" lane").subscribe();
        }
    };
}
