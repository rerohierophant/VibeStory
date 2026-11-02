package com.vibestory.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vibestory.dispatch.*;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.jooq.DSLContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.assertj.core.api.Assertions.*;

@Tag("rabbit")
@SpringBootTest(properties={"vibestory.dispatch.mode=rabbit","vibestory.dispatch.role=api","vibestory.dispatch.relay-enabled=false","vibestory.worker.enabled=false","vibestory.mock-only=true","vibestory.mock.delay=0ms"})
class RabbitProtocolIntegrationTest extends PostgresSupport {
    static final String NAMESPACE="vibestory-test-"+UUID.randomUUID().toString().substring(0,8);
    @DynamicPropertySource static void broker(DynamicPropertyRegistry r) {
        r.add("spring.rabbitmq.port",()->System.getenv().getOrDefault("TEST_RABBITMQ_PORT","5679"));
        r.add("vibestory.dispatch.namespace",()->NAMESPACE);
    }
    @Autowired DSLContext db;
    @Autowired RabbitTemplate rabbit;
    @Autowired ConnectionFactory connections;
    @Autowired ObjectMapper json;
    @Autowired TaskService videos;
    @Autowired TaskStore tasks;
    @Autowired DispatchStore dispatch;
    long user,project,episode,shot;
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path fixtureRoot;
    final TaskStore.Providers mocks=new TaskStore.Providers("mock","mock","mock");
    @BeforeEach void setup() {
        db.execute("truncate app_user,storage_cleanup restart identity cascade");
        user=db.fetchOne("insert into app_user(email,password_hash) values ('protocol@test','unused') returning id").get(0,Long.class);
        project=db.fetchOne("insert into project(user_id,name,visual_type) values (?,'协议','realistic') returning id",user).get(0,Long.class);
        episode=db.fetchOne("insert into episode(user_id,project_id,episode_index,title) values (?,?,1,'一') returning id",user,project).get(0,Long.class);
        shot=db.fetchOne("insert into shot(user_id,project_id,episode_id,seq,status,description) values (?,?,?,1,'CONFIRMED','must-not-enter-message') returning id",user,project,episode).get(0,Long.class);
        db.execute("insert into shot_image(shot_id,episode_id,selected,storage_key) values (?,?,true,'private-first-frame')",shot,episode);
        var admin=new RabbitAdmin(connections);for(String name:new String[]{"text","image","video","dead"}) admin.purgeQueue(lane()+"."+name,false);
    }
    @AfterAll static void cleanupBroker(@Autowired ConnectionFactory connections) {
        var admin=new RabbitAdmin(connections);for(String name:new String[]{"text","image","video","dead"}) admin.deleteQueue(NAMESPACE+".mock."+name);
        admin.deleteExchange(NAMESPACE+".mock.tasks");admin.deleteExchange(NAMESPACE+".mock.dead");
    }
    @Test void persistentMandatoryPublicationIsConfirmedAndTypesAreIsolated() throws Exception {
        var p=publication();var c=send(p.notification(),"video");assertThat(c.getFuture().get(5,TimeUnit.SECONDS).isAck()).isTrue();assertThat(c.getReturned()).isNull();assertThat(dispatch.sent(p)).isTrue();
        var message=rabbit.receive(lane()+".video",3000);assertThat(message).isNotNull();assertThat(message.getMessageProperties().getReceivedDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
        String payload=new String(message.getBody(),java.nio.charset.StandardCharsets.UTF_8);assertThat(payload).doesNotContain("must-not-enter-message","private-first-frame","apiKey","password","prompt");
        assertThat(json.readValue(payload,TaskNotification.class)).isEqualTo(p.notification());assertThat(rabbit.receive(lane()+".text")).isNull();assertThat(rabbit.receive(lane()+".image")).isNull();
        assertThat(videos.get(user,p.notification().taskId()).state()).isEqualTo("queued"); // confirm is not completion
    }
    @Test void mandatoryReturnPreventsTreatingUnroutablePublicationAsDelivered() throws Exception {
        var p=publication();var c=send(p.notification(),"missing-route");assertThat(c.getFuture().get(5,TimeUnit.SECONDS).isAck()).isTrue();assertThat(c.getReturned()).isNotNull();
        dispatch.publishFailed(p);assertThat(db.fetchOne("select status from task_outbox where id=?",p.notification().eventId()).get(0,String.class)).isEqualTo("PENDING");
        assertThat(videos.get(user,p.notification().taskId()).attempts()).isZero();
    }
    @Test void committedClaimBeforeAckRedeliversWithoutAnotherAttempt() throws Exception {
        var p=publication();send(p.notification(),"video").getFuture().get(5,TimeUnit.SECONDS);
        try(var connection=connections.createConnection();var first=connection.createChannel(false)) {
            var receipt=first.basicGet(lane()+".video",false);assertThat(receipt).isNotNull();
            tasks.claimNotification(p.notification(),"before-ack",mocks,true).orElseThrow();
            ((org.springframework.amqp.rabbit.connection.ChannelProxy)first).getTargetChannel().close(); // Simulate connection loss after SQL commit and before ACK.
        }
        try(var connection=connections.createConnection();var second=connection.createChannel(false)) {
            var redelivery=second.basicGet(lane()+".video",false);assertThat(redelivery).isNotNull();assertThat(redelivery.getEnvelope().isRedeliver()).isTrue();
            assertThat(tasks.claimNotification(p.notification(),"redelivered",mocks,true)).isEmpty();second.basicAck(redelivery.getEnvelope().getDeliveryTag(),false);
        }
        assertThat(videos.get(user,p.notification().taskId()).attempts()).isEqualTo(1);
    }
    @Test void ackThenCrashIsRecoveredByDatabaseLeaseAndOldOwnerIsFenced() throws Exception {
        var p=publication();send(p.notification(),"video").getFuture().get(5,TimeUnit.SECONDS);
        TaskStore.Lease old;
        try(var connection=connections.createConnection();var ch=connection.createChannel(false)) {
            var receipt=ch.basicGet(lane()+".video",false);old=tasks.claimNotification(p.notification(),"acked-crash",mocks,true).orElseThrow();ch.basicAck(receipt.getEnvelope().getDeliveryTag(),false);
        }
        assertThat(rabbit.receive(lane()+".video")).isNull();db.execute("update task set lease_until=now()-interval '1 second' where id=?",old.taskId());tasks.recoverExpired(mocks);
        var next=dispatch.take();assertThat(next.notification().dispatchGeneration()).isEqualTo(p.notification().dispatchGeneration()+1);
        send(next.notification(),"video").getFuture().get(5,TimeUnit.SECONDS);
        var message=rabbit.receive(lane()+".video",3000);var event=json.readValue(message.getBody(),TaskNotification.class);
        assertThat(tasks.claimNotification(event,"recovered",mocks,true).orElseThrow().attemptNo()).isEqualTo(2);
        assertThat(tasks.succeed(old,old.version(),new com.vibestory.provider.VideoProvider.Result("late",3000))).isFalse();
    }
    @Test void rejectedNotificationReachesDurableDeadLetterQueueAndPausesOnlyDispatch() throws Exception {
        var p=publication();send(p.notification(),"video").getFuture().get(5,TimeUnit.SECONDS);
        try(var connection=connections.createConnection();var ch=connection.createChannel(false)) { var message=ch.basicGet(lane()+".video",false);ch.basicReject(message.getEnvelope().getDeliveryTag(),false); }
        var dead=rabbit.receive(lane()+".dead",3000);assertThat(dead).isNotNull();assertThat(dead.getMessageProperties().getXDeathHeader()).isNotEmpty();
        dispatch.deadLetter("b".repeat(64),json.readValue(dead.getBody(),TaskNotification.class),"DEAD_LETTER");dispatch.reconcileQueued();assertThat(dispatch.take()).isNull();
        assertThat(videos.get(user,p.notification().taskId()).state()).isEqualTo("queued");dispatch.resume(user,p.notification().taskId());assertThat(dispatch.take()).isNotNull();
    }
    @Test void publishWithoutMarkingSentCanRepeatButDatabaseClaimExecutesOnlyOnce() throws Exception {
        var p=publication();send(p.notification(),"video").getFuture().get(5,TimeUnit.SECONDS);db.execute("update task_outbox set lease_until=now()-interval '1 second'");
        var repeated=dispatch.take();assertThat(repeated.notification()).isEqualTo(p.notification());send(repeated.notification(),"video").getFuture().get(5,TimeUnit.SECONDS);dispatch.sent(repeated);
        var first=json.readValue(rabbit.receive(lane()+".video",3000).getBody(),TaskNotification.class);
        var next=json.readValue(rabbit.receive(lane()+".video",3000).getBody(),TaskNotification.class);
        assertThat(tasks.claimNotification(first,"one",mocks,true)).isPresent();assertThat(tasks.claimNotification(next,"two",mocks,true)).isEmpty();
        assertThat(videos.get(user,p.notification().taskId()).attempts()).isEqualTo(1);
    }
    @Test void externalIdSurvivesMqRecoveryAndFixtureOnlyQueriesAndDownloadsOriginalAttempt() throws Exception {
        var storage=new com.vibestory.storage.LocalStorage(fixtureRoot.toString());
        var localMovie=new com.vibestory.provider.MockVideoProvider(new com.vibestory.provider.MockVideoProvider.Properties(java.time.Duration.ZERO,0,"known"),storage)
            .generate(new com.vibestory.provider.VideoProvider.Request(user,project,episode,99,1,"1234567890abcdef","{}"));
        byte[] bytes=storage.resource(localMovie.storageKey()).getContentAsByteArray();
        var creates=new java.util.concurrent.atomic.AtomicInteger();var queries=new java.util.concurrent.atomic.AtomicInteger();var downloads=new java.util.concurrent.atomic.AtomicInteger();
        var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        String endpoint="http://127.0.0.1:"+server.getAddress().getPort()+"/v1";
        server.createContext("/",exchange->{
            String path=exchange.getRequestURI().getPath();byte[] response;
            if(path.equals("/clip.mp4")) { downloads.incrementAndGet();response=bytes; }
            else if(path.equals("/v1/query/video_generation")) { queries.incrementAndGet();response="{\"status\":\"Success\",\"file_id\":\"99\",\"base_resp\":{\"status_code\":0}}".getBytes(java.nio.charset.StandardCharsets.UTF_8); }
            else if(path.equals("/v1/files/retrieve")) response=("{\"file\":{\"download_url\":\""+endpoint.replace("/v1","")+"/clip.mp4\"},\"base_resp\":{\"status_code\":0}}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            else { creates.incrementAndGet();response="{}".getBytes(java.nio.charset.StandardCharsets.UTF_8); }
            exchange.sendResponseHeaders(200,response.length);exchange.getResponseBody().write(response);exchange.close();
        });server.start();
        try {
            var p=publication();long id=p.notification().taskId();
            var oldInput=json.readValue(db.fetchOne("select input_snapshot from task where id=?",id).get(0,String.class),TaskService.VideoInput.class);
            var input=new TaskService.VideoInput("VIDEO",shot,"minimax",oldInput.prompt(),"realistic","",oldInput.sourceImageId(),oldInput.sourceImageVersion(),oldInput.sourceImageKey(),3000,java.util.Map.of("outputDurationMs",6000),null,endpoint,"MiniMax-Hailuo-2.3-Fast");
            db.execute("update task set provider='minimax',input_snapshot=?::jsonb where id=?",json.writeValueAsString(input),id);
            var live=new TaskStore.Providers("mock","mock","minimax");
            var original=tasks.claimNotification(p.notification(),"remote",live,false).orElseThrow();tasks.checkpoint(original,original.version(),"42");
            db.execute("update task set lease_until=now()-interval '1 second' where id=?",id);tasks.recoverExpired(live);
            // The recovering notification crosses a real broker; no generation submission is allowed.
            var row=db.fetchOne("select o.*,t.type,t.user_id,t.project_id,t.episode_id from task_outbox o join task t on t.id=o.task_id and t.dispatch_generation=o.dispatch_generation where t.id=?",id);
            var next=dispatch.event(row);
            String liveLane=NAMESPACE+".live";var admin=new RabbitAdmin(connections);
            admin.declareExchange(new DirectExchange(liveLane+".tasks",true,false));admin.declareQueue(new org.springframework.amqp.core.Queue(liveLane+".video",true));
            admin.declareBinding(new Binding(liveLane+".video",Binding.DestinationType.QUEUE,liveLane+".tasks","video",null));
            var correlation=new CorrelationData(UUID.randomUUID().toString());var props=new MessageProperties();props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
            rabbit.send(liveLane+".tasks","video",new Message(json.writeValueAsBytes(next),props),correlation);correlation.getFuture().get(5,TimeUnit.SECONDS);
            var receipt=rabbit.receive(liveLane+".video",3000);var resumed=tasks.claimNotification(json.readValue(receipt.getBody(),TaskNotification.class),"recovered",live,false).orElseThrow();
            var config=new com.vibestory.provider.VideoProperties("minimax",endpoint,"MiniMax-Hailuo-2.3-Fast","fixture-only",java.time.Duration.ofSeconds(2),java.time.Duration.ofMillis(10),java.time.Duration.ofSeconds(3));
            var result=new com.vibestory.provider.MiniMaxVideoProvider(config,storage,json).generate(resumed.request(),input,resumed.externalTaskId(),ignored->{throw new AssertionError("must not submit");});
            assertThat(tasks.succeed(resumed,resumed.version(),result)).isTrue();
            assertThat(resumed.attemptId()).isEqualTo(original.attemptId());assertThat(videos.get(user,id).attempts()).isEqualTo(1);
            assertThat(creates.get()).isZero();assertThat(queries.get()).isEqualTo(1);assertThat(downloads.get()).isEqualTo(1);
        } finally { server.stop(0);new RabbitAdmin(connections).deleteQueue(NAMESPACE+".live.video");new RabbitAdmin(connections).deleteExchange(NAMESPACE+".live.tasks"); }
    }
    String lane() { return NAMESPACE+".mock"; }
    DispatchStore.Publication publication() { videos.submit(user,shot,null);return dispatch.take(); }
    CorrelationData send(TaskNotification event,String routing) throws Exception {
        var props=new MessageProperties();props.setContentType("application/json");props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);var c=new CorrelationData(UUID.randomUUID().toString());
        rabbit.send(lane()+".tasks",routing,new Message(json.writeValueAsBytes(event),props),c);return c;
    }
}
