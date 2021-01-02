import discord4j.core.object.entity.channel.MessageChannel;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ShowdownCommands {
    static final String name="\u2694showdown\u2694";
    static final Random random= new Random();
    static void init() {
        ArrayList<Command> commands=new ArrayList<>();
        commands.add(new HelpCommand());
        commands.add(registerCmd);
        commands.add(openCmd);
        commands.add(lanesCmd);
        commands.add(r4regCmd);
        commands.add(new closeCmd());
        commands.add(new nextWaveCmd());
        Command.registerCmds(name,commands);
    }

    static Command registerCmd = new SimpleCommand("register",
            new Command.BaseData(false,
                    "register",
                    "register to showdown event to get your lane assignment")) {
        @Override
        protected void execute(String content, Participant p, MessageChannel ch, Server s) {
            if(!s.Sd.registrationActive()) {
                ch.createMessage("Showdown event not in registration phase").subscribe();
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
            curServer.Sd.threshold=pow;
            curServer.Sd.laneStatus.clear();
            curServer.Sd.enemyStatus.clear();
            if(curServer.Sd.save()) {
                curServer.Sd.cleanUp();
                channel.createMessage("Successfully opened Showdown with power threshold at " + pow).subscribe();
            } else {
                channel.createMessage("Unexpected error while trying to open showdown").subscribe();
            }
        }
    };

    static final Pattern intDoublePattern = Pattern.compile("(\\d+)\\s+(\\d+\\.?\\d*)");
    static final Pattern twoIntPattern=Pattern.compile("(\\d+)\\s+(\\d+)");
    static class closeCmd extends SimpleCommand {
        closeCmd() {
            super("close", new Command.BaseData(true, "close",
                    "close showdown registration phase"), closeCmd::new);
        }
        Iterator<SDPos> laneIt;
        SimpleCommand getCloseCmd() {return this;}
        boolean forceClose=false;
        @Override
        protected void execute(String content, Participant p, MessageChannel channel, Server curServer) {
            if(curServer.Sd.registrationActive()||forceClose) {
                laneIt = Arrays.stream(new SDPos[]{SDPos.Left, SDPos.Center, SDPos.Right}).iterator();
                executeNext(p, channel, curServer);
            } else {
                curServer.setFollowUpCmd(channel,p,yesNo);
                channel.createMessage("Registration already closed are you sure you want to re-enter lane data? (yes/no").subscribe();
            }
        }
        void executeNext(Participant p, MessageChannel channel, Server curServer) {
            if(laneIt.hasNext()) {
                SDPos next = laneIt.next();
                curServer.Sd.laneStatus.put(next,new Server.SDLaneStatus());
                curServer.setFollowUpCmd(channel, p, new input(next));
                channel.createMessage("provide **"+next+"** lane Participant numbers then total power (eg 23 29.5)").subscribe();
            } else {

                if(!curServer.Sd.save()) {
                    log.error("Unexpected database error");
                    channel.createMessage("Unexpected database error").subscribe();
                } else {
                    channel.createMessage("Thanks you are done! Now use nextwave command to proceed.").subscribe();
                }
                curServer.removeFollowupCmd(channel,p);
            }
        }
        final Command yesNo=new FollowupCommand() {
            @Override
            protected void execute(String content, Participant participant, MessageChannel channel, Server curServer) {
                if(content.trim().equalsIgnoreCase("yes")) {
                    forceClose=true;
                    getCloseCmd().execute(content, participant, channel, curServer);
                    return;
                }
                curServer.removeFollowupCmd(channel,participant);
                channel.createMessage("close aborted").subscribe();
            }
        };
        class input extends FollowupCommand {
            input(SDPos lane) {
                this.lane = lane;
            }
            final SDPos lane;
            @Override
            protected void execute(String content, Participant participant, MessageChannel channel, Server curServer) {
                Matcher m = intDoublePattern.matcher(content);
                if (m.matches()) {
                    Server.SDLaneStatus ls=curServer.Sd.laneStatus.get(lane);
                    ls.nb=Integer.parseInt(m.group(1));
                    if(ls.nb <0 || ls.nb >60) throw new RecoverableError("Incorrect participant number "+ls.nb);
                    double candidatePow= Double.parseDouble(m.group(2));
                    double expectedPow=curServer.Sd.getExpectedPower(ls.nb);
                    if(candidatePow < expectedPow/6 || candidatePow > expectedPow*6) {
                        throw new RecoverableError("Incorrect team power "+m.group(2)+ " it should be around "+0.1*Math.round(expectedPow*10));
                    }
                    ls.pow=candidatePow;
                    ls.realPow=ls.pow;

                    int nbReservists = ls.nb - 20;
                    if (nbReservists > 0) {
                        String[] rnd=new String[nbReservists];
                        for(int i=0;i<nbReservists;++i) rnd[i]=Integer.toString((int)Math.floor(600*ls.nb/ls.pow)+random.nextInt(300));
                        curServer.setFollowUpCmd(channel, participant, new reservists());
                        channel.createMessage("Click on troops, scroll down and provide power for the " + (nbReservists == 1 ? "reservist" : nbReservists + " reservists") + " in **" + lane + "** lane (eg "+String.join(" ",rnd)+")").subscribe();
                    } else {
                        executeNext(participant,channel,curServer);
                    }
                } else {
                    channel.createMessage("Incorrect input format. Needs something like \"21 32.4\"").subscribe();
                }
            }
            class reservists extends FollowupCommand {
                @Override
                protected void execute(String content, Participant participant, MessageChannel channel, Server curServer) {
                    int[] powers = Pattern.compile("\\s+").splitAsStream(content).mapToInt((s)->{
                        try {
                            int nb= Integer.parseInt(s);
                            int expected=curServer.Sd.getAvgParticipantPower();
                            if(nb < expected/6 || nb > expected*4) {
                                throw new RecoverableError("Invalid reservist power " + s + " Expecting more something around " + expected);
                            }
                            return nb;
                        } catch(NumberFormatException e) {
                            throw new RecoverableError("Not a valid reservist power "+s);
                        }
                    }).toArray();
                    Server.SDLaneStatus ls=curServer.Sd.laneStatus.get(lane);
                    int resNb=ls.nb-20;
                    if(powers.length != resNb) {
                        channel.createMessage("There are "+resNb+" reservists in "+lane+" lane. I need same number of power values not "+powers.length).subscribe();
                        return;
                    }
                    int sum=0;
                    for(int p:powers) sum+=p;
                    ls.realPow= ls.pow-sum/1000.0;
                    executeNext(participant,channel,curServer);
                }
            }
        }
    }

    static class nextWaveCmd extends SimpleCommand {
        nextWaveCmd() {
            super("nextwave",
                    new Command.BaseData(true,"nextwave","Input enemy data for next wave to get matching advice"),
                    nextWaveCmd::new);
        }
        Iterator<SDPos> laneIt;
        SDPos cur=SDPos.Undef;
        void executeNext(Participant p, MessageChannel channel,Server curServer) {
            if(laneIt.hasNext()) {
                cur=laneIt.next();
                curServer.setFollowUpCmd(channel, p, new input());
                channel.createMessage("provide enemy team's **"+cur+"** lane nb of participants then total power (eg 23 29592)").subscribe();
            } else { //wrap-up
                if(!curServer.Sd.save()) {
                    curServer.removeFollowupCmd(channel,p);
                    throw new RecoverableError("Unexpected database error");
                }
                StringBuilder sb =new StringBuilder("```Best configuration to win is:\n");
                curServer.Sd.computeBestMatching(sb);
                curServer.removeFollowupCmd(channel,p);
                channel.createMessage(sb.append("```").toString()).subscribe();
            }

        }
        @Override
        protected void execute(String content, Participant participant, MessageChannel channel, Server curServer) {
            laneIt = Arrays.stream(new SDPos[] {SDPos.Left,SDPos.Center,SDPos.Right}).iterator();
            Map<SDPos, Server.SDLaneStatus> enemyLanes =curServer.Sd.enemyStatus;
            enemyLanes.put(SDPos.Left,new Server.SDLaneStatus());
            enemyLanes.put(SDPos.Center,new Server.SDLaneStatus());
            enemyLanes.put(SDPos.Right,new Server.SDLaneStatus());
            executeNext(participant,channel,curServer);
        }
        double estimate(int nb,double pow) {
            if(nb<=20) return pow;
            else {
                return pow -0.8*pow*(nb-20)/nb;
            }
        }
        class input extends FollowupCommand {
            @Override
            protected void execute(String content, Participant participant, MessageChannel channel, Server curServer) {
                Matcher m = twoIntPattern.matcher(content);
                if (m.matches()) {
                    Server.SDLaneStatus ls=curServer.Sd.enemyStatus.get(cur);
                    ls.nb=Integer.parseInt(m.group(1));
                    if(ls.nb <0 || ls.nb >60) throw new RecoverableError("Incorrect participant number "+ls.nb);
                    double candidatePow= Integer.parseInt(m.group(2))*0.001;
                    double expectedPow=curServer.Sd.getExpectedPower(ls.nb);
                    if(candidatePow < expectedPow/4 || candidatePow> expectedPow*4) {
                        throw new RecoverableError("Incorrect team power "+m.group(2)+ " it should be around "+(int)(Math.round(expectedPow)*1000));
                    }
                    ls.pow=candidatePow;
                    ls.realPow=estimate(ls.nb,ls.pow);
                    executeNext(participant,channel,curServer);
                } else {
                    channel.createMessage("Incorrect input format. Needs something like \"23 "
                            +(int)Math.round(curServer.Sd.getExpectedPower(23)*1000)+"\"").subscribe();
                }
            }
        }
    }

    static Command lanesCmd = new SimpleCommand("lanes",
            new Command.BaseData(false,"lanes","Shows who is registered in which lane")) {
        @Override
        protected void execute(String content, Participant participant, MessageChannel channel, Server curServer) {
            if(!curServer.Sd.registrationActive()) {
                channel.createMessage("No ongoing Showdown registration!").subscribe();
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
