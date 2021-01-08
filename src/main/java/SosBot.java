import discord4j.common.util.Snowflake;
import discord4j.core.DiscordClient;
import discord4j.core.GatewayDiscordClient;
import discord4j.core.event.domain.guild.MemberJoinEvent;
import discord4j.core.event.domain.guild.MemberLeaveEvent;
import discord4j.core.event.domain.guild.MemberUpdateEvent;
import discord4j.core.event.domain.lifecycle.ReadyEvent;
import discord4j.core.event.domain.message.MessageCreateEvent;
import discord4j.core.object.entity.Member;
import discord4j.core.object.entity.Message;
import discord4j.core.object.entity.User;
import discord4j.core.object.entity.channel.MessageChannel;
import discord4j.gateway.intent.Intent;
import discord4j.gateway.intent.IntentSet;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuple4;
import reactor.util.function.Tuples;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;


@Slf4j
public class SosBot {
    static final Duration TenSec=Duration.ofSeconds(10);
    private static GatewayDiscordClient theGw;
    public static GatewayDiscordClient getDiscordGateway() {return theGw;}
    private static Snowflake myId;
    public static void main(final String[] args) {
        //DB init
        try {
            initDB();
        } catch (SQLException se) {
            log.error("Failed to initialize database connection", se);
            System.exit(-2);
        }

        //Discord init
        final String token = System.getenv("TOKEN");
        final DiscordClient client = DiscordClient.builder(token).build();
        IntentSet is = IntentSet.of(Intent.GUILD_MEMBERS,Intent.DIRECT_MESSAGES);
        client.gateway().setEnabledIntents(is);
        theGw=client.login().block(TenSec);
        if(theGw==null) {
            log.error("Login to discord failed");
            System.exit(-1);
        }
        theGw.on(ReadyEvent.class).subscribe((re)->{
           log.info("Ready!");
           myId=re.getSelf().getId();
           ChannelAndCommands.initAll();
           SosBot.initAll();
           Notification.initFromDb();
        });
        theGw.onDisconnect().block();
    } //end of main

    private static void initAll() {
        theGw.on(MessageCreateEvent.class).map(SosBot::processMessage).onErrorContinue((error, event)->{
            try {
                log.error("Uncaught exception in process method",error);
                final Message message = ((MessageCreateEvent) event).getMessage();
                message.getChannel().subscribe(
                        (c)->c.createMessage("Unexpected error...").subscribe(),
                        (t)-> log.error("Error fetching channel info",t)
                );
            }
            catch(Throwable t) {
                log.error("Double error!!",t);
            }
        }).subscribe();
        theGw.on(MemberJoinEvent.class).map(SosBot::ProcessNewMember).onErrorContinue((error,event)->{
            try {
                Member m = ((MemberJoinEvent) event).getMember();
                log.error("Problem in new member callback for " + m.getDisplayName(), error);
            }catch(Throwable t) {
                log.error("Double error ",t);
            }
        }).subscribe();
        theGw.on(MemberLeaveEvent.class).map(SosBot::ProcessDeleteMember).onErrorContinue((error, event)->{
            try {
                String name =((MemberLeaveEvent) event).getMember().map(Member::getDisplayName).orElse("<>");
                log.error("Problem in delete member callback for " + name, error);
            }catch(Throwable t) {
                log.error("Double error ",t);
            }
        }).subscribe();
        theGw.on(MemberUpdateEvent.class).map(SosBot::ProcessUpdateMember).onErrorContinue((error, event)->{
            try {
                log.error("Problem in update member callback " , error);
            } catch(Throwable t) {
                log.error("Double error ",t);
            }
        }).subscribe();
    }
    private static MemberUpdateEvent ProcessUpdateMember(MemberUpdateEvent e) {
        Server.getServerFromId(e.getGuildId()).subscribe(
                srv->{
                    Participant p= srv.getExistingParticipant(e.getMemberId());
                    if(p==null) {
                        log.error("Member not found in member update cb "+e.getMemberId());
                        return ;
                    }
                    log.info("Member update for "+p+"=>"+e);
                    srv.guild.getMemberById(p.member.getId())
                            .doOnNext((m)->{
                                p.member=m;
                                p.update();
                            }).subscribe();
                },
                (t)-> log.error("Error while getting Server for event: "+e,t)
        );
        return e;
    }
    private static MemberLeaveEvent ProcessDeleteMember(MemberLeaveEvent e) {
        log.info("New Leave callback for "+e.getUser()+"=>"+e);
        Server.getServerFromId(e.getGuildId()).subscribe(
                srv->{
                    if(Participant.delete(e.getUser().getId().asLong(),e.getGuildId().asLong())) {
                        srv.sessions.remove(e.getUser().getId().asLong());
                    } else {
                        log.error("Error deleting "+e.getUser());
                    }
                },
                (t)-> log.error("Error while getting Server for event: "+e,t)
        );
        return e;
    }

    private static  MemberJoinEvent ProcessNewMember(MemberJoinEvent e) {
        log.info("New Joiner callback for "+e.getMember().getDisplayName()+"=>"+e);
        Server.getServerFromId(e.getGuildId()).subscribe(
                srv->{
                    Participant p=new Participant(e.getMember(),srv);
                    if(!p.create()) {
                        log.error("error while saving new member in cb");
                    }
                },
                (t)-> log.error("Error while getting Server for event: "+e,t)
        );
        return e;
    }

    static MessageCreateEvent processMessage( MessageCreateEvent event) {
        Optional<User> msgAuthor=event.getMessage().getAuthor();
        if(msgAuthor.isPresent() && msgAuthor.get().getId().equals(myId)) {
            // Ignore Message from myself
            return event;
        }
        final String content = event.getMessage().getContent().trim();
        if(event.getGuildId().isEmpty()) {
            log.warn("received message with no Server ID=>"+content);
            return event;
        }
        if (event.getMember().isEmpty()) {
            log.warn("received message with no Member=>" + content);
            return event;
        }
        final Member m=event.getMember().get();
        log.info("Received "+content+" from "+m.getDisplayName());
        Server.getServerFromId(event.getGuildId().get()).flatMap(srv->{
            Participant p= srv.getParticipant(m);
            if(p==null) {
                log.error("Participant not found");
                return Mono.empty();
            } else {
                return event.getMessage().getChannel()
                        .map((ch)->Tuples.of(content,p,ch,srv));
            }
        }).map((t) -> {
            Command.findAndExec(t.getT1(),t.getT2(),t.getT3(),t.getT4());
            return Mono.empty();
        }).onErrorContinue(( thr,t)->{
            log.error("Error executing "+content,thr);
            @SuppressWarnings("unchecked")
            Tuple4<String,Participant, MessageChannel,Server> tup=(Tuple4<String,Participant, MessageChannel,Server> )t;
            if(thr instanceof RecoverableError) {
                tup.getT3().createMessage(thr.getMessage()).subscribe();
            } else {
                tup.getT4().removeFollowupCmd(tup.getT3(), tup.getT2()); //cancel current action if any
                tup.getT3().createMessage("Unexpected error...").subscribe(); //notify user something is wrong
            }
        }).subscribe();
        return event;
    }
    static private Connection theDbCOnnection=null;
    static void initDB() throws  SQLException  {
        String dbUrl = System.getenv("JDBC_DATABASE_URL");
        theDbCOnnection = DriverManager.getConnection(dbUrl);
        AnalysisCenter.initQueries(theDbCOnnection);
        Participant.initQueries(theDbCOnnection);
        Server.initQueries(theDbCOnnection);
        Notification.initQueries(theDbCOnnection);
    }
    static boolean checkDBConnection() {
        try {
            synchronized (SosBot.class) {
                if (theDbCOnnection.isClosed()) {
                    log.warn("Detected DB connection closed...re-opening!");
                    initDB();
                    return true;
                }
            }
        } catch(SQLException e) {
            log.error("Error while checking DB connection status",e);
        }
        return false;
    }
}
