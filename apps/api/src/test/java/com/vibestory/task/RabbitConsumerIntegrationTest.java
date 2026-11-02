package com.vibestory.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vibestory.dispatch.*;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Tag("rabbit")
@SpringBootTest(properties={"vibestory.dispatch.mode=rabbit","vibestory.dispatch.role=worker","vibestory.dispatch.video-slots=1","vibestory.dispatch.prefetch=3","vibestory.worker.enabled=true","vibestory.mock-only=true","vibestory.mock.delay=500ms"})
class RabbitConsumerIntegrationTest extends PostgresSupport {
    static final String NAMESPACE="vibestory-consumer-"+UUID.randomUUID().toString().substring(0,8);
    @DynamicPropertySource static void broker(DynamicPropertyRegistry r) {
        r.add("spring.rabbitmq.port",()->System.getenv().getOrDefault("TEST_RABBITMQ_PORT","5679"));
        r.add("vibestory.dispatch.namespace",()->NAMESPACE);
    }
    @Autowired DSLContext db;
    @Autowired RabbitTemplate rabbit;
    @Autowired ConnectionFactory connections;
    @Autowired ObjectMapper json;
    @Autowired TaskService videos;
    @MockitoSpyBean TaskStore tasks;
    @MockitoSpyBean DispatchStore dispatch;
    long user,project,episode,shot;
    @BeforeEach void setup() {
        db.execute("truncate app_user,storage_cleanup restart identity cascade");
        user=db.fetchOne("insert into app_user(email,password_hash) values ('consumer@test','unused') returning id").get(0,Long.class);
        project=db.fetchOne("insert into project(user_id,name,visual_type) values (?,'消费','realistic') returning id",user).get(0,Long.class);
        episode=db.fetchOne("insert into episode(user_id,project_id,episode_index,title) values (?,?,1,'一') returning id",user,project).get(0,Long.class);
        shot=db.fetchOne("insert into shot(user_id,project_id,episode_id,seq,status,description) values (?,?,?,1,'CONFIRMED','private-prompt') returning id",user,project,episode).get(0,Long.class);
        db.execute("insert into shot_image(shot_id,episode_id,selected,storage_key) values (?,?,true,'first-frame')",shot,episode);
    }
    @AfterAll static void cleanup(@Autowired RabbitDispatch listeners,@Autowired ConnectionFactory connections) {
        listeners.stop();var admin=new RabbitAdmin(connections);
        for(String name:new String[]{"text","image","video","dead"}) admin.deleteQueue(NAMESPACE+".mock."+name);
        admin.deleteExchange(NAMESPACE+".mock.tasks");admin.deleteExchange(NAMESPACE+".mock.dead");
    }
    @Test void infrastructureFailureUsesBoundedOutboxRetryThenDeadLetterWithoutCreatingAttempts() throws Exception {
        long id=submit("failure");var notification=event(id);
        doThrow(new org.jooq.exception.DataAccessException("injected SQL infrastructure failure")).when(tasks).claimNotification(any(),anyString(),any(),anyBoolean());
        for(int n=1;n<=5;n++) {
            send(notification);final int expected=n;
            await(()->db.fetchOne("select delivery_failures from task_outbox where task_id=?",id).get(0,Integer.class)==expected);
        }
        assertThat(rabbit.receive(lane()+".dead",3000)).isNotNull();
        assertThat(videos.get(user,id).attempts()).isZero();assertThat(videos.get(user,id).state()).isEqualTo("queued");
        assertThat(db.fetchOne("select dispatch_blocked from task where id=?",id).get(0,String.class)).isEqualTo("CONSUMER_BUDGET_EXHAUSTED");
        doCallRealMethod().when(tasks).claimNotification(any(),anyString(),any(),anyBoolean());
        dispatch.resume(user,id);send(event(id));await(()->videos.get(user,id).ready());assertThat(videos.get(user,id).attempts()).isEqualTo(1);
    }
    @Test void unavailableFailureBookkeepingRejectsToDlqInsteadOfAcknowledgingTaskAsMissing() throws Exception {
        long id=submit("database-down");
        doThrow(new org.jooq.exception.DataAccessException("injected database unavailable")).when(tasks).claimNotification(any(),anyString(),any(),anyBoolean());
        doThrow(new org.jooq.exception.DataAccessException("injected database unavailable")).when(dispatch).deliveryFailed(any());
        send(event(id));assertThat(rabbit.receive(lane()+".dead",3000)).isNotNull();
        assertThat(videos.get(user,id).attempts()).isZero();assertThat(videos.get(user,id).state()).isEqualTo("queued");
    }
    @Test void localExecutionSlotStillBoundsRunningTasksAfterManualAck() throws Exception {
        long a=submit("one"),b=submit("two"),c=submit("three");send(event(a));send(event(b));send(event(c));
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);int max=0;
        while(System.nanoTime()<deadline) {
            max=Math.max(max,db.fetchOne("select count(*) from task where status='RUNNING'").get(0,Integer.class));
            if(db.fetchOne("select count(*) from task where status='SUCCEEDED'").get(0,Integer.class)==3) break;
            Thread.sleep(20);
        }
        assertThat(max).isEqualTo(1);assertThat(db.fetchOne("select count(*) from task where status='SUCCEEDED'").get(0,Integer.class)).isEqualTo(3);
        assertThat(db.fetchOne("select count(*) from task_attempt").get(0,Integer.class)).isEqualTo(3);
    }
    @Test void unsupportedContractIsRejectedByActualConsumerBeforeAnyClaim() throws Exception {
        long id=submit("invalid");var e=event(id);send(new TaskNotification(99,e.eventId(),e.taskId(),e.dispatchGeneration(),e.type(),e.userId(),e.projectId(),e.episodeId()));
        var dead=rabbit.receive(lane()+".dead",3000);assertThat(dead).isNotNull();assertThat(videos.get(user,id).attempts()).isZero();
        verify(tasks,never()).claimNotification(any(),anyString(),any(),anyBoolean());
    }
    private long submit(String nonce) { return Long.parseLong(videos.submit(user,shot,nonce).taskId()); }
    private TaskNotification event(long id) { return dispatch.event(db.fetchOne("select o.*,t.type,t.user_id,t.project_id,t.episode_id from task_outbox o join task t on t.id=o.task_id and t.dispatch_generation=o.dispatch_generation where t.id=?",id)); }
    private String lane() { return NAMESPACE+".mock"; }
    private void send(TaskNotification event) throws Exception {
        var props=new MessageProperties();props.setContentType("application/json");props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);var c=new CorrelationData(UUID.randomUUID().toString());
        rabbit.send(lane()+".tasks","video",new Message(json.writeValueAsBytes(event),props),c);assertThat(c.getFuture().get(5,TimeUnit.SECONDS).isAck()).isTrue();
    }
    private void await(BooleanSupplier condition) throws Exception { long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);while(!condition.getAsBoolean() && System.nanoTime()<end) Thread.sleep(20);assertThat(condition.getAsBoolean()).isTrue(); }
}
