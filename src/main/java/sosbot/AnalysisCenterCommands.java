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

@Slf4j
public class AnalysisCenterCommands extends ChannelAndCommands {
        public static final String name ="\uD83D\uDDA5analysis-center\uD83D\uDDA5";
        public static final String topic ="This channel allows to keep track of our Analysis centers  and get notified when they need to be watched/defended";
        AnalysisCenterCommands() {
            super(name,topic);
            register(new NCommand.HelpCommand());
            register(listCmd);
            register(new createCmd());
            register(listChannels);
            register(testCountdown);
            init();
            Notification.registerNotifType(NotifType.defendAC,new Notification(
                    new Duration[] {Duration.ofMinutes(1L),Duration.ofMinutes(30L),Duration.ofHours(6)},
                    (in)->{
                        getChannel(in.server).createMessage("@everyone Trap will take place in "+ Util.format(in.before)+" at "+ Util.hhmm.format(in.basetime)+"\n").subscribe();
                        log.info("sending trap notif for minus "+in.before.toString());
                    },
                    Duration.ofDays(2L)
            ));
        }

        static NCommand.SimpleCommand<Void> listCmd= new NCommand.SimpleCommand<>("list",
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
                sb.append( "\n\uD83D\uDC4D=under our control.\n\uD83D\uDC4E=under enemy control") ;
                c.send(sb.toString());
                return Optional.empty();
            }
        };

        static NCommand<Void> testCountdown = new NCommand.SimpleCommand<>("countdown") {

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
        static NCommand<Void> listChannels = new NCommand<>() {
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

    static class createCmd extends  NCommand.SimpleCommand<Void> {
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
                        c.send("Analysis center succesfully created! " + ac);
                        return Mono.empty();
                    });
        }
    }
}
