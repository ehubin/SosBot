package sosbot;

import discord4j.common.util.Snowflake;
import discord4j.core.object.entity.Guild;
import discord4j.core.object.entity.channel.Channel;
import discord4j.core.object.entity.channel.TextChannel;
import discord4j.rest.util.Color;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;


import java.io.ByteArrayInputStream;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;

@Slf4j
public class AnalysisCenterCommands extends ChannelAndCommands {
    public static final String name ="\uD83D\uDDA5analysis-center\uD83D\uDDA5";
    public static final String topic ="This channel allows to keep track of our Analysis centers  and get notified when they need to be watched/defended";
    Notification<AnalysisCenter> defendACNotif=new Notification<>(
        NotifType.defendAC,
        new Duration[] {Duration.ofMinutes(1L),Duration.ofMinutes(30L),Duration.ofHours(2)},
        (in)->{
            log.info("in ac defend notif");
            if(in.data != null) {
                AnalysisCenter ac=in.data;
                getChannel(in.server).createMessage("@everyone "+ac.toString()+"\n we count on you to defend it!").subscribe();
            } else {
                log.error("Analysis center notification has no data: cannot send it");
            }
        },
        Duration.ofDays(4L),AnalysisCenter::buildFrom
        );

    AnalysisCenterCommands() {
        super(name,topic);
        register(new NCommand.HelpCommand());
        register(listCmd);
        register(new createCmd());
        register(new deleteCmd());
        register(new weLostCmd());
        register(new weCapturedCmd());
        register(listChannels);
        register(testCountdown);
        init();
    }

    NCommand.SimpleCommand<Void> listCmd= new NCommand.SimpleCommand<>("list",
            new NCommand.BaseData(false, "list", "Provide a list of Alliance's Analysis centers")) {

        @Override
        Optional<Void> onMessage(MsgContext c) {
            Set<AnalysisCenter> allAc=AnalysisCenter.getAll(c.curServer);
            if(allAc.isEmpty()) {
                c.send("No analysis centers for this server");
                return Optional.empty();
            }
            StringBuilder sb=new StringBuilder();
            int i=0;
            for(AnalysisCenter ac:allAc) {
                sb.append("**").append(++i).append(".** ").append(ac).append("\n");
            }
            sb.append( "\n\uD83D\uDC4D=We control, \uD83D\uDC4E= Enemy control") ;
            sb.append( "\n\uD83D\uDEE1=Protected status, \u2694= Challenged status") ;
            c.send(sb.toString());
            return Optional.empty();
        }
    };
    static class  deleteCmd extends NCommand.NRegexCommand<Void> {
        deleteCmd() {
            super("delete (\\d{1,2})",
                new NCommand.BaseData(true,
                        "delete <nb>",
                        "delete one of the AC's from the list by providing its number."));
        }

        @Override
        Optional<Void> onMessage(Matcher ma, MsgContext c) {
            if(ma.matches()) {
                int nb=Integer.parseInt(ma.group(1));
                int i=0;
                Set<AnalysisCenter> allAc=AnalysisCenter.getAll(c.curServer);
                if(allAc.isEmpty()) {
                    c.send("There are no ACs to delete");
                }
                for(AnalysisCenter ac:allAc) {
                    if (++i == nb) {
                        if(ac.delete()) {
                            c.send("Successfully deleted "+ac);
                        } else {
                            c.send("Unexpected problem...");
                        }
                        return Optional.empty();
                    }
                }
                c.send("No AC matching number <"+nb+">");
            }
            return Optional.empty();
        }
    }
    static class  weLostCmd extends NCommand.NRegexCommand<Void> {
        weLostCmd() {
            super("welost (\\d{1,2})",
                    new NCommand.BaseData(true, "welost <nb>", "update an AC after it has been captured by enemy"),
                    weLostCmd::new);
        }
        AnalysisCenter theAc;
        @Override
        Optional<Void> onMessage(Matcher ma, MsgContext c) {
            if(ma.matches()) {
                int nb=Integer.parseInt(ma.group(1));
                int i=0;
                Set<AnalysisCenter> allAc=AnalysisCenter.getAll(c.curServer);
                if(allAc.isEmpty()) {
                    throw new Util.UnrecoverableError("There are no ACs to loose");
                }
                for(AnalysisCenter ac:allAc) {
                    if (++i == nb) {
                        theAc = ac;
                        c.send("Please enter in how long will the lost AC be in protected mode for (e.g 2d 3:30:30)");
                        return Optional.empty();
                    }
                }
                throw new Util.UnrecoverableError("No AC matching number <"+nb+">");
            }
            return Optional.empty();
        }

        @Override
        Mono<Void> mono(MsgContext c) {
            return  super.mono(c).then(new readDuration().mono(c)).flatMap((duration)->{
                if(theAc.setState(false,duration.getSeconds())) {
                    c.send("AC loss has been recorded:\n"+theAc);
                    return Mono.empty();
                } else {
                    return Mono.error(new Util.UnrecoverableError("Unexpected error while recording the loss..."));
                }
            }).then();
        }
    }

     static class weCapturedCmd extends NCommand.NRegexCommand<Void> {
        AnalysisCenter theAC=null;
         weCapturedCmd() {
             super("wecaptured (\\d{1,2})",
                     new NCommand.BaseData(true, "wecaptured <nb>", "update an AC status after we just captured it"),
                     weCapturedCmd::new);
         }

        @Override
        Optional<Void> onMessage(Matcher ma, MsgContext c) {
            if(ma.matches()) {
                int nb=Integer.parseInt(ma.group(1));
                int i=0;
                Set<AnalysisCenter> allAc=AnalysisCenter.getAll(c.curServer);
                if(allAc.isEmpty()) {
                    c.send("There are no ACs to delete");
                }
                for(AnalysisCenter ac:allAc) {
                    if (++i == nb) {
                        theAC=ac;
                        c.send("Enter how long the newly captured AC will be protected for");
                        return Optional.empty();
                    }
                }
                c.send("No AC matching number <"+nb+">");
            }
            return Optional.empty();
        }

         @Override
         Mono<Void> mono(MsgContext c) {
             return super.mono(c).then(new readDuration().mono(c)).flatMap((duration)->{
                 if(theAC.setState(true,duration.getSeconds())) {
                     c.send("AC capture has been recorded:\n"+theAC);
                     return Mono.empty();
                 } else {
                     return Mono.error(new Util.UnrecoverableError("Unexpected error while recording the loss..."));
                 }
             }).then();
         }
     }

        NCommand<Void> testCountdown = new NCommand.SimpleCommand<>("countdown") {

            @Override
            Optional<Void> onMessage(MsgContext ctxt) {
                ctxt.channel.createMessage(mcs -> {
                    mcs.addFile("c1.gif", new ByteArrayInputStream(countDown.getCountdown(5,10)));
                    mcs.addFile("c2.gif", new ByteArrayInputStream(countDown.getCountdown(9,1)));
                    mcs.addFile("c3.gif", new ByteArrayInputStream(countDown.getCountdown(8,1)));
                    mcs.addFile("c4.gif", new ByteArrayInputStream(countDown.getCountdown(7,1)));
                    mcs.setEmbed(ecs -> {
                        ecs.setDescription("countdown test");
                        ecs.addField("\u200b", "If you want to change to another team ask one of the R4s", false);
                        ecs.addField("Your attendance to the event is important! We cannot cancel your registration anymore. We count on you!", "\u200b", false);
                        ecs.setImage("attachment://c1.gif").setColor(Color.MOON_YELLOW);
                    });
                }).subscribe();
                return Optional.empty();
            }

        };
        NCommand<Void> listChannels = new NCommand<>() {
            final StringBuilder sb=new StringBuilder();
            final ArrayList<Channel> res=new ArrayList<>();
            @Override
            boolean matches(String content) {
                return content.trim().toLowerCase(Locale.ROOT).startsWith("listchannel");
            }

            @Override
            Optional<Void> onMessage(NCommand.MsgContext ctxt) {
                return Optional.empty();
            }

            @Override
            Mono<Void> mono(NCommand.MsgContext c) {
                String intpart=c.content.replaceAll("[^0-9]", "");
                long srv=Long.parseLong(intpart);
                log.info(" retrieving from server "+srv);
                Mono<Void> getChannels= SosBot.getDiscordGateway().getGuildById(Snowflake.of(srv))
                        .onErrorMap((t)->{
                            log.error("retrieve channel error",t);
                            c.send("Retrieve channel error");
                            return new Exception("Guild_Retrieve");
                        })
                        .flatMapMany(Guild::getChannels)
                        .doOnNext((ch)->{
                            res.add(ch);
                            log.info(ch.getName()+"  "+ch.getId());
                            sb.append(res.size()).append(ch.getName()).append("\t\t\t").append(ch.getId().asLong()).append("\n");
                        }).then().doOnSuccess((t)-> c.send(sb.append("Which channel to delete?").toString()));

                Flux<Integer> readInput=new NCommand.readIntCmd().mono(c)
                        .onErrorResume((t)->{
                            if(t.getMessage().equals("Guild_Retrieve")) return Mono.empty();
                            if(t instanceof RecoverableError) {
                                c.send(t.getMessage());
                            } else {
                                c.send("Unexpected error");
                            }
                            return Mono.empty();
                        })
                        .repeat().takeWhile((i)->i>0);


                return super.mono(c).then(getChannels).thenMany(readInput)
                        .flatMap((i)->{
                            Channel ch=res.get(i-1);
                            return ch.delete().then(Mono.just(ch));
                        })
                        .doOnError((t)->{
                            if(t.getMessage().equals("Guild_Retrieve")) return;
                            log.error("Channel delete error",t);
                            c.send("Channel delete error");
                        })
                        .doOnNext((Channel ch)->c.send("Deleted "+(ch.getType().equals(Channel.Type.GUILD_TEXT)?((TextChannel)ch).getName():"Non text channel")))
                        .doOnComplete(()->c.send("Done!")).then();
            }
        };

    class createCmd extends  NCommand.SimpleCommand<Void> {
        createCmd() {
            super("create",
                    new NCommand.BaseData(true, "create", "creates a new Analysis center"),
                    createCmd::new);
        }
        AnalysisCenter.Type t;
        int lvl;
        boolean ours;
        boolean challenged;

        @Override
        protected Optional<Void> onMessage(MsgContext c) {
            StringBuilder sb = new StringBuilder("Which type of Analysis center do you want to create between:\n");
            for (AnalysisCenter.Type t : AnalysisCenter.Type.values())
                sb.append(t.ordinal() + 1).append(". ").append(t.name()).append("\n");
            c.send(sb.toString());
            return Optional.empty();
        }

        @Override
        Mono<Void> mono(MsgContext c) {
            NCommand<Integer> readIntCmd = new readIntCmd();
            NCommand<Boolean> yesNoCmd = new yesNoCmd();
            return super.mono(c).then(readIntCmd.mono(c))
                    .doOnNext((i) -> {
                        t = AnalysisCenter.Type.values()[i - 1];
                        c.send("Enter the level of this analysis center");
                    })
                    .then(readIntCmd.mono(c)).doOnNext((i) -> {
                        lvl = i;
                        c.send("Is the analysis center owned by our alliance(yes/no)");
                    }).then(yesNoCmd.mono(c)).doOnNext((b) -> {
                        ours = b;
                        c.send("Is the analysis currently in  challenge status(yes/no)?");
                    }).then(yesNoCmd.mono(c)).doOnNext((b) -> {
                        challenged = b;
                        c.send("How long will it be in " + (b ? "Challenge" : "Protected") + " status for? (e.g 1d 23:34:20)");
                    }).then(new readDuration().mono(c)).flatMap((d) -> {
                        log.info("after read duration " + d);
                        AnalysisCenter ac;
                        try {
                            ac = AnalysisCenter.create(t, lvl, c.curServer, challenged, ours, Instant.now().plus(d));
                        } catch (SQLException e) {
                            if (e.getMessage().contains("violates unique constraint")) {
                                log.error("Error while saving AC in database", e);
                                return Mono.error(new Util.UnrecoverableError("This AC already exists!"));
                            }
                            log.error("Error while saving AC in database", e);
                            return Mono.error(new Exception("Error while saving AC in database", e));
                        }
                        defendACNotif.scheduleNotif(NotifType.defendAC, c.curServer, ac.nextDefend(),true,true,ac);
                        c.send("Analysis center succesfully created! " + ac);
                        return Mono.empty();
                    });
        }
    }
}
