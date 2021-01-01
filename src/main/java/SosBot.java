
import discord4j.core.DiscordClient;

import discord4j.core.GatewayDiscordClient;
import discord4j.core.event.domain.guild.MemberJoinEvent;
import discord4j.core.event.domain.guild.MemberLeaveEvent;
import discord4j.core.event.domain.guild.MemberUpdateEvent;
import discord4j.core.event.domain.message.MessageCreateEvent;
import discord4j.core.object.entity.Member;
import discord4j.core.object.entity.Message;

import discord4j.gateway.intent.Intent;
import discord4j.gateway.intent.IntentSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;



import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;



public class SosBot {

    private static final Logger _logger = LoggerFactory.getLogger(SosBot.class);
    static final Duration TenSec=Duration.ofSeconds(10);
    private static GatewayDiscordClient theGw;
    public static GatewayDiscordClient getDiscordGateway() {return theGw;}
    public static void main(final String[] args) {
        //DB init
        try {
            initDB();
        } catch (SQLException se) {
            _logger.error("Failed to initialize database connection", se);
            System.exit(-2);
        }

        //Discord init
        final String token = System.getenv("TOKEN");
        final DiscordClient client = DiscordClient.builder(token).build();
        IntentSet is = IntentSet.of(Intent.GUILD_MEMBERS,Intent.DIRECT_MESSAGES);
        client.gateway().setEnabledIntents(is);
        theGw=client.login().block(TenSec);
        if(theGw==null) {
            _logger.error("Login to discord failed");
            System.exit(-1);
        }
        // register command callbacks
        ShowdownCommands.init();
        ReservoirRaidCommands.init();

        theGw.on(MessageCreateEvent.class).map(SosBot::processMessage).onErrorContinue((error, event)->{
            try {
                _logger.error("Uncaught exception in process method",error);
                final Message message = ((MessageCreateEvent) event).getMessage();
                message.getChannel().subscribe(
                        (c)->c.createMessage("Unexpected error...").subscribe(),
                        (t)->_logger.error("Error fetching channel info",t)
                );
            }
            catch(Throwable t) {
                _logger.error("Double error!!",t);
            }
        }).subscribe();
        theGw.on(MemberJoinEvent.class).map(SosBot::ProcessNewMember).onErrorContinue((error,event)->{
            try {
                Member m = ((MemberJoinEvent) event).getMember();
                _logger.error("Problem in new member callback for " + m.getDisplayName(), error);
            }catch(Throwable t) {
                _logger.error("Double error ",t);
            }
        }).subscribe();
        theGw.on(MemberLeaveEvent.class).map(SosBot::ProcessDeleteMember).onErrorContinue((error, event)->{
            try {
                String name =((MemberLeaveEvent) event).getMember().map(Member::getDisplayName).orElse("<>");
                _logger.error("Problem in delete member callback for " + name, error);
            }catch(Throwable t) {
                _logger.error("Double error ",t);
            }
        }).subscribe();
        theGw.on(MemberUpdateEvent.class).map(SosBot::ProcessUpdateMember).onErrorContinue((error, event)->{
            try {
                _logger.error("Problem in update member callback " , error);
            } catch(Throwable t) {
                _logger.error("Double error ",t);
            }
        }).subscribe();
        theGw.onDisconnect().block();
    } //end of main

    private static MemberUpdateEvent ProcessUpdateMember(MemberUpdateEvent e) {
        Server.getServerFromId(e.getGuildId()).subscribe(
                srv->{
                    Participant p= srv.getExistingParticipant(e.getMemberId());
                    if(p==null) {
                        _logger.error("Member not found in member update cb "+e.getMemberId());
                        return ;
                    }
                    _logger.info("Member update for "+p+"=>"+e);
                    srv.guild.getMemberById(p.member.getId())
                            .doOnNext((m)->{
                                p.member=m;
                                p.update();
                            }).subscribe();
                },
                (t)-> _logger.error("Error while getting Server for event: "+e,t)
        );
        return e;
    }
    private static MemberLeaveEvent ProcessDeleteMember(MemberLeaveEvent e) {
        _logger.info("New Leave callback for "+e.getUser()+"=>"+e);
        Server.getServerFromId(e.getGuildId()).subscribe(
                srv->{
                    if(Participant.delete(e.getUser().getId().asLong(),e.getGuildId().asLong())) {
                        srv.sessions.remove(e.getUser().getId().asLong());
                    } else {
                        _logger.error("Error deleting "+e.getUser());
                    }
                },
                (t)-> _logger.error("Error while getting Server for event: "+e,t)
        );
        return e;
    }

    private static  MemberJoinEvent ProcessNewMember(MemberJoinEvent e) {
        _logger.info("New Joiner callback for "+e.getMember().getDisplayName()+"=>"+e);
        Server.getServerFromId(e.getGuildId()).subscribe(
                srv->{
                    Participant p=new Participant(e.getMember(),srv);
                    if(!p.create()) {
                        _logger.error("error while saving new member in cb");
                    }
                },
                (t)-> _logger.error("Error while getting Server for event: "+e,t)
        );
        return e;
    }

    static MessageCreateEvent processMessage( MessageCreateEvent event) {
        final String content = event.getMessage().getContent().trim();
        _logger.warn("Received "+content);
        if(event.getGuildId().isEmpty()) {
            _logger.warn("received message with no Server ID=>"+content);
            return event;
        }
        if (event.getMember().isEmpty()) {
            _logger.warn("received message with no Member=>" + content);
            return event;
        }
        final Member m=event.getMember().get();
        Server.getServerFromId(event.getGuildId().get()).subscribe(
                srv->{
                    Participant p= srv.getParticipant(m);
                    if(p==null) {
                        _logger.error("Participant not found");
                    } else {
                        event.getMessage().getChannel().subscribe(
                                (ch) -> Command.findAndExec(content, p,ch, srv),
                                (t) -> _logger.error("Error while getting channel from=>" + content, t)
                        );
                    }
                },
                (t)-> _logger.error("Error while getting Server for msg: "+content,t)
        );
        return event;
    }
    static private Connection theDbCOnnection=null;
    static void initDB() throws  SQLException  {
        String dbUrl = System.getenv("JDBC_DATABASE_URL");
        theDbCOnnection = DriverManager.getConnection(dbUrl);
        AnalysisCenter.initQueries(theDbCOnnection);
        Participant.initQueries(theDbCOnnection);
        Server.initQueries(theDbCOnnection);
    }
    static boolean checkDBConnection() {
        try {
            synchronized (SosBot.class) {
                if (theDbCOnnection.isClosed()) {
                    _logger.warn("Detected DB connection closed...re-opening!");
                    initDB();
                    return true;
                }
            }
        } catch(SQLException e) {
            _logger.error("Error while checking DB connection status",e);
        }
        return false;
    }
}
