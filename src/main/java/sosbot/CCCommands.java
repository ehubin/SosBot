package sosbot;

import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;


public class CCCommands extends ChannelAndCommands {

    public static final String name ="\uD83D\uDDFCcapital-clash\uD83D\uDDFC";
    public static final String topic ="This channel allows to keep track of our capital clash event";
    CCCommands() {
        super(name,topic);
        register(new NCommand.HelpCommand());
        register(listCmd);
        register(openCmd);
        register(closeCmd);
        register(registerCmd);
        init();
        /*
        Notification.registerNotifType(NotifType.defendAC,new Notification<Void>(
                new Duration[] {Duration.ofMinutes(1L),Duration.ofMinutes(30L),Duration.ofHours(6)},
                (in)->{
                    getChannel(in.server).createMessage("@everyone Trap will take place in "+ Util.format(in.before)+" at "+ Util.hhmm.format(in.basetime)+"\n").subscribe();
                    log.info("sending trap notif for minus "+in.before.toString());
                },
                Duration.ofDays(2L)
        ));

         */
    }

    static NCommand.SimpleCommand<Void> listCmd= new NCommand.SimpleCommand<>("list",
            new NCommand.BaseData(false, "list", "Provide a list of registered members for CC")) {
        @Override
        Optional<Void> onMessage(MsgContext c) {
            StringBuilder sb= new StringBuilder("```");
            final AtomicInteger i=new AtomicInteger(0);
            c.curServer.getRegisteredCCparticipants(false).forEach((p)->
                    sb.append(i.incrementAndGet()).append(". ")
                            .append(p.getName()).append(" ").append(p.power).append("\n"));
            if(sb.length() ==3) {
                c.send("Nobody registered yet");
            } else {
                c.send(sb.append("```").toString());
            }
            return Optional.empty();
        }
    };


    static NCommand.SimpleCommand<Void> openCmd= new NCommand.SimpleCommand<>("open",
            new NCommand.BaseData(true, "open", "start new capital clash event")) {
        @Override
        Optional<Void> onMessage(MsgContext c) {
            c.send("In how long will next Capital clash start (e.g 2d 15:34:23)");
            return Optional.empty();
        }
        @Override
        Mono<Void> mono(MsgContext c) {
            return super.mono(c).then(new readDuration().mono(c)).flatMap((duration)->{
                Instant start = Instant.now().plus(duration);
                if(c.curServer.openCC(start)) {
                    c.send("Successfully opened CC for "+Util.format(start));
                } else {
                    c.send("Unexpected database error while trying to open CC");
                }
                return Mono.empty();
            });
        }
    };

    static NCommand.SimpleCommand<Void> closeCmd= new NCommand.SimpleCommand<>("close",
            new NCommand.BaseData(true, "close", "close registrations for capital clash event")) {
        @Override
        Optional<Void> onMessage(MsgContext c) {
            c.send("Are you sure you want to close registration for next CC event?");
            return Optional.empty();
        }
        @Override
        Mono<Void> mono(MsgContext c) {
            return super.mono(c).then(new yesNoCmd().mono(c)).flatMap((yes)->{

                if(yes) {
                    if (c.curServer.closeCC()) {
                        c.send("Successfully closed Capital clash registrations");
                    } else {
                        c.send("Unexpected database error while trying to close CC registration");
                    }
                } else {
                    c.send("CC registration was not closed!");
                }
                return Mono.empty();
            });
        }
    };
    static NCommand.SimpleCommand<Void> registerCmd= new NCommand.SimpleCommand<>("register",
            new NCommand.BaseData(false, "register", "register yourself to next capital clash event")) {
        @Override
        Optional<Void> onMessage(MsgContext c) {
            if(!c.curServer.CCactive) {
                throw new Util.UnrecoverableError("Sorry, capital clash registrations are currently closed");
            }
            c.send("Are you sure to be available for next event starting "+Util.format(c.curServer.CCstart)+"(yes/no)?");
            return Optional.empty();
        }
        @Override
        Mono<Void> mono(MsgContext c) {
            return super.mono(c).then(new yesNoCmd().mono(c)).flatMap((yes)->{
                if(yes) {
                    c.send("Please input your battle power in millions( e,g 65.3)");
                } else {
                    throw new Util.UnrecoverableError("Another time maybe?");
                }
                return Mono.empty();
            }).then(new readFloatCmd().mono(c)).flatMap((power)->{
                if(power <0 || power > 700) {
                    c.send("Incorrect power value "+power);
                } else if(c.participant.registerCC(power)) {
                    c.send("You are successfully registered! We count on you!");
                } else {
                    c.send("Unexpected error while saving registration data :(");
                }
                return Mono.empty();
            });
        }
    };
}
