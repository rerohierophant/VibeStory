package com.vibestory.task;

import com.vibestory.admission.*;
import com.vibestory.dispatch.*;
import com.vibestory.auth.Viewer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.jooq.DSLContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Tag("redis")
@AutoConfigureMockMvc
@SpringBootTest(properties={"vibestory.dispatch.mode=rabbit","vibestory.dispatch.role=api","vibestory.dispatch.relay-enabled=false","vibestory.worker.enabled=false","vibestory.mock-only=true",
    "vibestory.admission.enabled=true","vibestory.admission.media-rate=4","vibestory.admission.media-burst=3","vibestory.admission.text-rate=4","vibestory.admission.text-burst=3",
    "vibestory.admission.permit-ttl=100ms","vibestory.admission.error-backoff=100ms","vibestory.admission.ttl-margin=1s"})
class RedisAdmissionIntegrationTest extends PostgresSupport {
    static final String NAMESPACE="vibestory-redis-"+UUID.randomUUID().toString().substring(0,8);
    @DynamicPropertySource static void middleware(DynamicPropertyRegistry r) {
        r.add("spring.rabbitmq.port",()->System.getenv().getOrDefault("TEST_RABBITMQ_PORT","5679"));
        r.add("spring.data.redis.port",()->System.getenv().getOrDefault("TEST_REDIS_PORT","6389"));
        r.add("vibestory.dispatch.namespace",()->NAMESPACE);r.add("vibestory.admission.namespace",()->NAMESPACE);
    }
    @Autowired DSLContext db;
    @Autowired StringRedisTemplate redis;
    @Autowired AdmissionProperties config;
    @Autowired GenerationAdmission admission;
    @Autowired AdmissionStore admissions;
    @Autowired DispatchProperties dispatchConfig;
    @Autowired com.vibestory.provider.MockSubmissionProbe probe;
    @Autowired TaskService videos;
    @Autowired TaskStore tasks;
    @Autowired DispatchStore outbox;
    @Autowired MockMvc mvc;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper json;
    @Autowired org.springframework.amqp.rabbit.core.RabbitTemplate rabbit;
    @Autowired ConnectionFactory connections;
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path fixtureRoot;
    long userId,project,episode,shot;
    final TaskStore.Providers mocks=new TaskStore.Providers("mock","mock","mock");
    @BeforeEach void setup() {
        redis.delete(redis.keys(NAMESPACE+":admission:*"));
        resetDatabase(db);
        userId=db.fetchOne("insert into app_user(email,password_hash) values ('redis@test','unused') returning id").get(0,Long.class);
        project=db.fetchOne("insert into project(user_id,name,visual_type) values (?,'限流','realistic') returning id",userId).get(0,Long.class);
        episode=db.fetchOne("insert into episode(user_id,project_id,episode_index,title) values (?,?,1,'一') returning id",userId,project).get(0,Long.class);
        shot=db.fetchOne("insert into shot(user_id,project_id,episode_id,seq,status,description) values (?,?,?,1,'CONFIRMED','private-prompt') returning id",userId,project,episode).get(0,Long.class);
        db.execute("insert into shot_image(shot_id,episode_id,selected,storage_key) values (?,?,true,'private-first-frame')",shot,episode);
    }
    @AfterAll static void cleanup(@Autowired StringRedisTemplate redis,@Autowired ConnectionFactory connections) {
        redis.delete(redis.keys(NAMESPACE+":admission:*"));var admin=new RabbitAdmin(connections);
        for(String n:List.of("text","image","video","dead")) admin.deleteQueue(NAMESPACE+".mock."+n);
        admin.deleteExchange(NAMESPACE+".mock.tasks");admin.deleteExchange(NAMESPACE+".mock.dead");
    }
    @Test void mediaTypesShareCredentialBucketAndTextAndMockLiveAreIsolated() {
        assertThat(config.bucket("IMAGE","mock")).isEqualTo(config.bucket("VIDEO","mock")).isEqualTo(config.bucket("ASSET_IMAGE","mock"));
        assertThat(config.bucket("BREAKDOWN","mock").id()).isNotEqualTo(config.bucket("VIDEO","mock").id());
        assertThat(config.bucket("VIDEO","minimax").id()).isNotEqualTo(config.bucket("VIDEO","mock").id());
        var alias=new AdmissionProperties(true,config.namespace(),config.textCredentialId(),config.mediaCredentialId(),4,3,4,3,config.permitTtl(),config.errorBackoff(),config.ttlMargin());
        assertThat(alias.bucket("VIDEO","mock")).isEqualTo(config.bucket("IMAGE","mock"));
        var separate=new AdmissionProperties(true,config.namespace(),config.textCredentialId(),"different-credential",4,3,4,3,config.permitTtl(),config.errorBackoff(),config.ttlMargin());
        assertThat(separate.bucket("VIDEO","mock").id()).isNotEqualTo(config.bucket("VIDEO","mock").id());
    }
    @Test void concurrentClientsUseOneServerTimeTokenBucket() throws Exception {
        long id=submit();admission.request(id,"VIDEO","mock");Thread.sleep(800);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var futures=new ArrayList<Future<?>>();
            for(int n=0;n<40;n++) futures.add(pool.submit(()->admission.request(id,"VIDEO","mock")));
            for(var future:futures) future.get(10,TimeUnit.SECONDS);
        }
        var allowed=db.fetch("select redis_at_ms from generation_admission where outcome='ALLOWED' order by redis_at_ms").getValues(0,Long.class);
        assertThat(allowed).isNotEmpty();
        for(int a=0;a<allowed.size();a++) for(int b=a;b<allowed.size();b++)
            assertThat(b-a+1).isLessThanOrEqualTo((int)Math.floor(3+4*(allowed.get(b)-allowed.get(a))/1000.0+0.001));
        assertThat(videos.get(userId,id).attempts()).isZero();
        assertThat(redis.keys(NAMESPACE+":admission:*")).hasSize(2);
    }
    @Test void refusalRotatesOutboxAtomicallyAndDoesNotCreateOrFailAttempt() {
        long id=submit();var event=event(id);var d=admission.request(id,"VIDEO","mock");assertThat(d.allowed()).isFalse();
        assertThat(admissions.defer(event,d.reason(),500)).isTrue();assertThat(admissions.defer(event,d.reason(),500)).isFalse();
        assertThat(videos.get(userId,id).state()).isEqualTo("queued");assertThat(videos.get(userId,id).attempts()).isZero();
        assertThat(videos.get(userId,id).waitReason()).isEqualTo("ADMISSION_RECOVERING");
        assertThat(tasks.inspectNotification(event,mocks,true)).isEmpty();assertThat(tasks.inspectNotification(event(id),mocks,true)).isEmpty();
        assertThat(db.fetchOne("select count(*) from task_outbox where task_id=?",id).get(0,Integer.class)).isEqualTo(2);
        assertThat(db.fetchOne("select o.available_at=t.not_before from task_outbox o join task t on t.id=o.task_id and t.dispatch_generation=o.dispatch_generation where t.id=?",id).get(0,Boolean.class)).isTrue();
        assertThat(db.fetchOne("select error_code from task where id=?",id).get(0,String.class)).isNull();
    }
    @Test void staleAndCrossScopeNotificationsCannotDeferOrConsumeTokens() {
        long id=submit();var e=event(id);
        var bad=new TaskNotification(1,e.eventId(),id,e.dispatchGeneration(),e.type(),userId+999,project,episode);
        assertThat(tasks.inspectNotification(bad,mocks,true)).isEmpty();
        assertThat(admissions.defer(bad,"RATE_LIMITED",100)).isFalse();
        tasks.claimNotification(e,"winner",mocks,true).orElseThrow();
        assertThat(admissions.defer(e,"RATE_LIMITED",100)).isFalse();assertThat(tasks.inspectNotification(e,mocks,true)).isEmpty();
        assertThat(db.fetchOne("select count(*) from generation_admission").get(0,Integer.class)).isZero();
    }
    @Test void deletedBucketRecoversOneDurableEpochFromZeroAcrossRacingClients() throws Exception {
        long id=submit();admission.request(id,"VIDEO","mock");long old=epoch();
        redis.delete(config.bucket("VIDEO","mock").key()+":tokens");
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var fs=new ArrayList<Future<GenerationAdmission.Decision>>();for(int n=0;n<8;n++) fs.add(pool.submit(()->admission.request(id,"VIDEO","mock")));
            for(var f:fs) assertThat(f.get().allowed()).isFalse();
        }
        assertThat(epoch()).isEqualTo(old+1);assertThat(admission.request(id,"VIDEO","mock").allowed()).isFalse();
        assertThat(db.fetchOne("select state from generation_bucket").get(0,String.class)).isEqualTo("ACTIVE");
    }
    @Test void idleTtlCoversRefillAndExpiryRebuildsConservatively() throws Exception {
        long id=submit();admission.request(id,"VIDEO","mock");var b=config.bucket("VIDEO","mock");
        assertThat(b.ttlMs()).isGreaterThanOrEqualTo((long)Math.ceil(1000*b.burst()/b.rate())+1000);
        assertThat(redis.getExpire(b.key()+":marker",TimeUnit.MILLISECONDS)).isEqualTo(-1);
        long old=epoch();Thread.sleep(b.ttlMs()+100);
        assertThat(admission.request(id,"VIDEO","mock").reason()).isEqualTo("ADMISSION_RECOVERING");assertThat(epoch()).isEqualTo(old+1);
        assertThat(admission.request(id,"VIDEO","mock").allowed()).isFalse();
    }
    @Test void incompleteRecoveryCanBeCompletedIdempotentlyWithoutRefillingTokens() {
        long id=submit();admission.request(id,"VIDEO","mock");var b=config.bucket("VIDEO","mock");
        var before=redis.opsForHash().get(b.key()+":tokens","tokens");
        db.execute("update generation_bucket set state='RECOVERING'");
        assertThat(admission.request(id,"VIDEO","mock").allowed()).isFalse();
        assertThat(Double.parseDouble(redis.opsForHash().get(b.key()+":tokens","tokens").toString())).isLessThan(1);
        assertThat(before).isNotNull();assertThat(epoch()).isEqualTo(1);
    }
    @Test void conflictingWorkerRateConfigurationFailsClosed() {
        long id=submit();admission.request(id,"VIDEO","mock");var b=config.bucket("VIDEO","mock");
        var conflicting=new AdmissionProperties.Bucket(b.id(),100,100,b.ttlMs(),AdmissionProperties.hash("conflict"),b.key());
        assertThatThrownBy(()->admissions.register(conflicting)).hasMessage("ADMISSION_CONFIG_MISMATCH");assertThat(epoch()).isEqualTo(1);
    }
    @Test void scriptFailureIsRecoverableQueueWaitWithoutAttempt() {
        long id=submit();admission.request(id,"VIDEO","mock");String marker=config.bucket("VIDEO","mock").key()+":marker";
        redis.delete(marker);redis.opsForValue().set(marker,"wrong-type");
        var d=admission.request(id,"VIDEO","mock");assertThat(d.reason()).isEqualTo("REDIS_UNAVAILABLE");
        admissions.defer(event(id),d.reason(),d.retryAfterMs());assertThat(videos.get(userId,id).attempts()).isZero();assertThat(videos.get(userId,id).state()).isEqualTo("queued");
        assertThat(db.fetchOne("select count(*) from generation_admission where outcome='ERROR'").get(0,Integer.class)).isEqualTo(1);
    }
    @Test void expiredPermitReacquiresOnSameAttemptAndInvokesOnlyOnce() throws Exception {
        long id=submit();admission.request(id,"VIDEO","mock");Thread.sleep(300);
        var permit=admission.request(id,"VIDEO","mock").permit();assertThat(permit).isNotNull();
        var lease=tasks.claimNotification(event(id),"slow-claim",mocks,true).orElseThrow();
        var calls=new AtomicInteger();assertThat(admission.execute(lease,permit,()->{Thread.sleep(150);SubmissionBoundary.beforeSubmission();return calls.incrementAndGet();})).isEqualTo(1);
        assertThat(calls.get()).isEqualTo(1);assertThat(videos.get(userId,id).attempts()).isEqualTo(1);
        assertThat(db.fetchOne("select expired from generation_admission where id=?",permit.id()).get(0,Boolean.class)).isTrue();
        assertThat(db.fetchOne("select count(*) from generation_admission where submitted_at is not null").get(0,Integer.class)).isEqualTo(1);
    }
    @Test void lostLeaseCannotUseFreshPermissionOrInvokeProvider() throws Exception {
        long id=submit();admission.request(id,"VIDEO","mock");Thread.sleep(300);var p=admission.request(id,"VIDEO","mock").permit();
        var lease=tasks.claimNotification(event(id),"lost",mocks,true).orElseThrow();db.execute("update task set lease_until=now()-interval '1 second' where id=?",id);
        var calls=new AtomicInteger();assertThatThrownBy(()->admission.execute(lease,p,()->{SubmissionBoundary.beforeSubmission();return calls.incrementAndGet();})).isInstanceOf(InterruptedException.class);assertThat(calls.get()).isZero();
    }
    @Test void externalIdRecoverySkipsRedisAndPreservesOriginalAttempt() throws Exception {
        long id=submit();db.execute("update task set provider='minimax' where id=?",id);var live=new TaskStore.Providers("mock","mock","minimax");
        var old=tasks.claimNotification(event(id),"external",live,false).orElseThrow();tasks.checkpoint(old,old.version(),"fixture-external-42");
        db.execute("update task set lease_until=now()-interval '1 second' where id=?",id);tasks.recoverExpired(live);
        assertThat(tasks.inspectNotification(event(id),live,false).orElseThrow().externalRecovery()).isTrue();
        var resumed=tasks.claimNotification(event(id),"query-only",live,false).orElseThrow();assertThat(resumed.attemptId()).isEqualTo(old.attemptId());
        assertThat(admission.execute(resumed,null,()->"query-only")).isEqualTo("query-only");
        assertThat(db.fetchOne("select count(*) from generation_admission").get(0,Integer.class)).isZero();
    }
    @Test void metricsAndWaitReasonAreScopedToAuthenticatedUser() throws Exception {
        long id=submit();var d=admission.request(id,"VIDEO","mock");admissions.defer(event(id),d.reason(),500);
        var viewer=new Viewer(userId,"redis@test","unused");
        mvc.perform(get("/api/admission-metrics").with(user(viewer))).andExpect(status().isOk()).andExpect(jsonPath("$.waiting").value(1)).andExpect(jsonPath("$.buckets[0].denied").value(1));
        mvc.perform(get("/api/tasks/"+id).with(user(viewer))).andExpect(status().isOk()).andExpect(jsonPath("$.waitReason").value("ADMISSION_RECOVERING"));
        long other=db.fetchOne("insert into app_user(email,password_hash) values ('other@test','unused') returning id").get(0,Long.class);
        mvc.perform(get("/api/admission-metrics").with(user(new Viewer(other,"other@test","unused")))).andExpect(status().isOk()).andExpect(jsonPath("$.waiting").value(0)).andExpect(jsonPath("$.buckets").isEmpty());
        mvc.perform(get("/api/tasks/"+id).with(user(new Viewer(other,"other@test","unused")))).andExpect(status().isNotFound());
    }
    @Test void realMqExternalRecoveryUsesHttpFixtureWithoutRedisOrCreateCall() throws Exception {
        var storage=new com.vibestory.storage.LocalStorage(fixtureRoot.toString());
        var localMovie=new com.vibestory.provider.MockVideoProvider(new com.vibestory.provider.MockVideoProvider.Properties(java.time.Duration.ZERO,0,"known"),storage)
            .generate(new com.vibestory.provider.VideoProvider.Request(userId,project,episode,99,1,"1234567890abcdef","{}"));
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
            var next=outbox.event(row);
            String liveLane=NAMESPACE+".live";var admin=new RabbitAdmin(connections);
            admin.declareExchange(new org.springframework.amqp.core.DirectExchange(liveLane+".tasks",true,false));admin.declareQueue(new org.springframework.amqp.core.Queue(liveLane+".video",true));
            admin.declareBinding(new org.springframework.amqp.core.Binding(liveLane+".video",org.springframework.amqp.core.Binding.DestinationType.QUEUE,liveLane+".tasks","video",null));
            var correlation=new org.springframework.amqp.rabbit.connection.CorrelationData(UUID.randomUUID().toString());var props=new org.springframework.amqp.core.MessageProperties();props.setDeliveryMode(org.springframework.amqp.core.MessageDeliveryMode.PERSISTENT);
            rabbit.send(liveLane+".tasks","video",new org.springframework.amqp.core.Message(json.writeValueAsBytes(next),props),correlation);correlation.getFuture().get(5,TimeUnit.SECONDS);
            var receipt=rabbit.receive(liveLane+".video",3000);var resumed=tasks.claimNotification(json.readValue(receipt.getBody(),TaskNotification.class),"recovered",live,false).orElseThrow();
            var config=new com.vibestory.provider.VideoProperties("minimax",endpoint,"MiniMax-Hailuo-2.3-Fast","fixture-only",java.time.Duration.ofSeconds(2),java.time.Duration.ofMillis(10),java.time.Duration.ofSeconds(3));
            var result=admission.execute(resumed,null,()->new com.vibestory.provider.MiniMaxVideoProvider(config,storage,json).generate(resumed.request(),input,resumed.externalTaskId(),ignored->{throw new AssertionError("must not submit");}));
            assertThat(tasks.succeed(resumed,resumed.version(),result)).isTrue();
            assertThat(resumed.attemptId()).isEqualTo(original.attemptId());assertThat(videos.get(userId,id).attempts()).isEqualTo(1);
            assertThat(creates.get()).isZero();assertThat(queries.get()).isEqualTo(1);assertThat(downloads.get()).isEqualTo(1);
        } finally { server.stop(0);new RabbitAdmin(connections).deleteQueue(NAMESPACE+".live.video");new RabbitAdmin(connections).deleteExchange(NAMESPACE+".live.tasks"); }
    }
    @Test void differentCredentialBucketsHaveIndependentActualCapacity() throws Exception {
        long id=submit();
        var otherConfig=new AdmissionProperties(true,config.namespace(),config.textCredentialId(),"different-media",4,3,4,3,config.permitTtl(),config.errorBackoff(),config.ttlMargin());
        var other=new GenerationAdmission(otherConfig,admissions,redis,dispatchConfig,probe);
        admission.request(id,"VIDEO","mock");other.request(id,"IMAGE","mock");Thread.sleep(300);
        assertThat(admission.request(id,"VIDEO","mock").allowed()).isTrue();assertThat(other.request(id,"IMAGE","mock").allowed()).isTrue();
        assertThat(db.fetchOne("select count(*) from generation_bucket").get(0,Integer.class)).isEqualTo(2);
    }
    @Test void locallyLostHeartbeatStopsSubmissionEvenBeforeDurableLeaseExpires() {
        long id=submit();var lease=tasks.claimNotification(event(id),"lost-heartbeat",mocks,true).orElseThrow();var calls=new AtomicInteger();
        assertThatThrownBy(()->admission.execute(lease,null,()->false,()->{SubmissionBoundary.beforeSubmission();return calls.incrementAndGet();})).isInstanceOf(InterruptedException.class);
        assertThat(calls.get()).isZero();assertThat(db.fetchOne("select count(*) from generation_admission").get(0,Integer.class)).isZero();
    }
    @Test void terminalStateClearsAdmissionWaitingWithoutCountingItAsProviderFailure() {
        long id=submit();var d=admission.request(id,"VIDEO","mock");admissions.defer(event(id),d.reason(),10);
        db.execute("update task set not_before=now() where id=?",id);
        var lease=tasks.claimNotification(event(id),"validation",mocks,true).orElseThrow();
        tasks.fail(lease,lease.version(),new com.vibestory.provider.ProviderException("INVALID_INPUT","invalid",false,false));
        assertThat(videos.get(userId,id).waitReason()).isNull();
        assertThat(db.fetchOne("select count(*) from generation_admission where submitted_at is not null").get(0,Integer.class)).isZero();
    }
    private DispatchStore.Publication publication() { submit();return outbox.take(); }
    private long epoch() { return db.fetchOne("select epoch from generation_bucket").get(0,Long.class); }
    private long submit() { return Long.parseLong(videos.submit(userId,shot,UUID.randomUUID().toString()).taskId()); }
    private TaskNotification event(long id) { return outbox.event(db.fetchOne("select o.*,t.type,t.user_id,t.project_id,t.episode_id from task_outbox o join task t on t.id=o.task_id and t.dispatch_generation=o.dispatch_generation where t.id=?",id)); }
}
