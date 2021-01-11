import discord4j.core.object.entity.channel.MessageChannel;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;


import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;

@Slf4j
public class AnalysisCenterCommands extends ChannelAndCommands{
        public static final String name ="\uD83D\uDDA5analysis-center\uD83D\uDDA5";
        public static final String topic ="This channel allows to keep track of our Analysis centers  and get notified when they need to be watched/defended";
        AnalysisCenterCommands() {
            super(name,topic);
            //register(new HelpCommand());
            register(listCmd);
            register(createCmd);
            init();
            Notification.registerNotifType(NotifType.defendAC,new Notification(
                    new Duration[] {Duration.ofMinutes(1L),Duration.ofMinutes(30L),Duration.ofHours(6)},
                    (in)->{
                        getChannel().createMessage("@everyone Trap will take place in "+Util.format(in.before)+" at "+Util.hhmm.format(in.basetime)+"\n").subscribe();
                        log.info("sending trap notif for minus "+in.before.toString());
                    },
                    Duration.ofDays(2L)
            ));
        }

        static NCommand.NsimpleCommand<Void> listCmd= new NCommand.NsimpleCommand<>("list") {
            @Override
            void onMessage(MsgContext c) {
                Set<AnalysisCenter> allAc=AnalysisCenter.getAll(c.curServer);
                if(allAc.isEmpty()) {
                    c.send("No analysis centers for this server");
                    return;
                }
                StringBuilder sb=new StringBuilder();
                int i=0;
                for(AnalysisCenter ac:allAc) {
                    sb.append(++i).append(". ").append(ac).append("\n");
                }
                c.send(sb.toString());
            }
        };


    static NCommand.NsimpleCommand<Void> createCmd= new NCommand.NsimpleCommand<>("create") {
        AnalysisCenter.Type t;
        int lvl;
        boolean ours;
        boolean challenged;
        Instant next;

        @Override
        protected void onMessage(MsgContext c) {
            StringBuilder sb=new StringBuilder("Which type of Analysis center do you want to create between:\n");
            for(AnalysisCenter.Type t:AnalysisCenter.Type.values()) sb.append(t.ordinal()+1).append(". ").append(t.name()).append("\n");
            c.send(sb.toString());
            sink.success();
        }

        @Override
        Mono<Void> mono(MsgContext c) {
            NCommand<Integer> readIntCmd = new readIntCmd();
            NCommand<Boolean> yesNoCmd = new yesNoCmd();
             return super.mono(c).then(readIntCmd.mono(c))
                     .doOnNext((i)->{
                         t= AnalysisCenter.Type.values()[i-1];
                         c.send("Enter the level of this analysis center");
                     })
                     .then(readIntCmd.mono(c)).doOnNext((i)->{
                         lvl=i;
                         c.send("Is the analysis center owned by our alliance(yes/no)");
                     }).then(yesNoCmd.mono(c)).doOnNext((b)->{
                        ours=b;
                        c.send("Is the analysis currently in  challenge status(yes/no)?");
                     }).then(yesNoCmd.mono(c)).doOnNext((b)->{
                         challenged=b;
                         c.send("How long will it be in "+(b?"Challenge":"Protected")+" status for? (hh:mm:ss)");
                     }).then(new readDuration().mono(c)).flatMap((d)->{
                         log.info("after read duration "+d);
                         AnalysisCenter ac;
                         try {
                             ac=AnalysisCenter.create(t, lvl, c.curServer, challenged, ours, Instant.now().plus(d));
                         } catch (SQLException e) {
                             if(e.getMessage().contains("violates unique constraint")) {
                                 log.error("Error while saving AC in database",e);
                             }
                             log.error("Error while saving AC in database",e);
                             return Mono.error(new Exception("Error while saving AC in database",e));
                         }
                         c.send("Analysis center succesfully created! "+ac);
                         return Mono.empty();
                     });
        }

        Command doCreate = new FollowupCommand() {
            @Override
            protected void execute(String content, Participant participant, MessageChannel channel, Server curServer) {
                AnalysisCenter theOne;
                try {
                    theOne = AnalysisCenter.create(t, lvl, curServer, challenged, ours, next);
                } catch(RecoverableError re) {
                   channel.createMessage(re.getMessage()).subscribe();
                    curServer.removeFollowupCmd(channel,participant);
                    return;
                }
                catch (SQLException t) {
                    log.error("AC DB create issue", t);
                    channel.createMessage("Unexpected Database error while creating AC...aborted").subscribe();
                    curServer.removeFollowupCmd(channel, participant);
                    return;
                }
                curServer.removeFollowupCmd(channel,participant);
                channel.createMessage("Succesfully created "+theOne).subscribe();
            }
        };
    };
}
