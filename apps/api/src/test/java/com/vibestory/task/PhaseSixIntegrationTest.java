package com.vibestory.task;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.vibestory.auth.*;
import com.vibestory.common.ApiErrors;
import com.vibestory.provider.ProviderException;
import com.vibestory.provider.VideoProvider;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.stream.Collectors;
import org.jooq.DSLContext;
import org.junit.jupiter.api.*;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"vibestory.worker.enabled=false", "vibestory.mock.delay=0ms", "vibestory.mock-only=true"})
@AutoConfigureMockMvc
class PhaseSixIntegrationTest extends PostgresSupport {
    @Autowired DSLContext db;
    @Autowired TaskService tasks;
    @Autowired TaskStore store;
    @Autowired TaskMetrics metrics;
    @Autowired TaskEvents events;
    @Autowired TransactionTemplate transactions;
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder passwords;
    private Viewer alice, bob;
    private long shot;
    private ListAppender<ILoggingEvent> logs;
    private Logger taskLog, scopeLog;

    @BeforeEach void setup() {
        resetDatabase(db);
        alice = account("alice@phase6.test"); bob = account("bob@phase6.test");
        long project = db.fetchOne("insert into project(user_id,name,visual_type) values (?,'验收','realistic') returning id", alice.id()).get(0, Long.class);
        long episode = db.fetchOne("insert into episode(user_id,project_id,episode_index,title) values (?,?,1,'一') returning id", alice.id(), project).get(0, Long.class);
        shot = db.fetchOne("insert into shot(user_id,project_id,episode_id,seq,status,description) values (?,?,?,1,'CONFIRMED','private-prompt-do-not-log') returning id", alice.id(), project, episode).get(0, Long.class);
        db.execute("insert into shot_image(shot_id,episode_id,selected,storage_key) values (?,?,true,'private-storage-key')", shot, episode);
        logs = new ListAppender<>(); logs.start();
        taskLog = (Logger) LoggerFactory.getLogger(TaskEvents.class); taskLog.addAppender(logs);
        scopeLog = (Logger) LoggerFactory.getLogger(ApiErrors.class); scopeLog.addAppender(logs);
    }

    @AfterEach void detach() { taskLog.detachAppender(logs); scopeLog.detachAppender(logs); logs.stop(); }

    @Test void metricsUseDurableScopedHistoryAndDoNotMultiplyTasksByAttempts() throws Exception {
        long success = timedTask("SUCCEEDED", 2, 8);
        long failed = timedTask("FAILED", 4, 14);
        timedTask("UNKNOWN", 6, 18);
        submit("queued");
        // A second completed attempt contributes one timing, not another task or failure denominator.
        db.execute("insert into task_attempt(task_id,attempt_no,lease_owner,status,started_at,finished_at) values (?,2,'test','FAILED',now()-interval '5 seconds',now()-interval '3 seconds')", failed);
        db.execute("update task set attempt_count=2 where id=?", failed);
        long old = submit("outside-window");
        db.execute("update task set created_at=now()-interval '25 hours' where id=?", old);
        var view = metrics.get(alice);
        assertThat(view.to().minusHours(24)).isEqualTo(view.from());
        var group = view.groups().getFirst();
        assertThat(group.tasks()).isEqualTo(4);
        assertThat(group.queued()).isEqualTo(1); assertThat(group.succeeded()).isEqualTo(1);
        assertThat(group.failed()).isEqualTo(1); assertThat(group.unknown()).isEqualTo(1);
        assertThat(group.knownOutcomes()).isEqualTo(2); assertThat(group.failureRate()).isEqualTo(0.5);
        assertThat(group.firstQueueWait().samples()).isEqualTo(3);
        assertThat(group.firstQueueWait().averageMs()).isEqualTo(4000.0);
        assertThat(group.attemptElapsed().samples()).isEqualTo(4);
        assertThat(group.attemptElapsed().averageMs()).isEqualTo(7500.0);
        assertThat(group.attemptElapsed().maxMs()).isEqualTo(12000.0);
        assertThat(new TaskMetrics(db).get(alice).groups()).isEqualTo(view.groups());
        assertThat(metrics.get(bob).groups()).isEmpty();
        mvc.perform(get("/api/task-metrics").with(user(bob))).andExpect(status().isOk()).andExpect(jsonPath("$.groups").isEmpty());
        mvc.perform(get("/api/task-metrics")).andExpect(status().isUnauthorized());
        assertThat(success).isPositive();
    }

    @Test void metricsOmitUnknownFailureRatesInsteadOfInventingZeroAndExcludeCancellations() {
        submit("waiting");
        var group = metrics.get(alice).groups().getFirst();
        assertThat(group.knownOutcomes()).isZero(); assertThat(group.failureRate()).isNull();
        assertThat(group.firstQueueWait().samples()).isZero(); assertThat(group.firstQueueWait().averageMs()).isNull();
        assertThat(group.attemptElapsed().samples()).isZero(); assertThat(group.attemptElapsed().averageMs()).isNull();
        timedTask("CANCELED", 1, 3);
        group = metrics.get(alice).groups().getFirst();
        assertThat(group.canceled()).isEqualTo(1); assertThat(group.knownOutcomes()).isZero();
    }

    @Test void logsOnlyCommittedLifecycleAndAllowlistedSourceMetadata() {
        long id = submit("logs");
        var lease = store.claim("test").orElseThrow();
        assertThat(fields(logs.list.getFirst())).containsEntry("event", "attempt_started")
            .containsEntry("task_id", id).containsEntry("attempt_no", 1).containsKey("source_image_id");
        assertThat(store.succeed(lease, lease.version(), new VideoProvider.Result("private-result-key", 3000))).isTrue();
        assertThat(fields(logs.list.getLast())).containsEntry("event", "attempt_finished")
            .containsEntry("attempt_status", "SUCCEEDED").containsEntry("task_status", "SUCCEEDED");
        String rendered = logs.list.stream().map(line -> fields(line).toString() + line.getFormattedMessage()).collect(Collectors.joining());
        assertThat(rendered).doesNotContain("private-prompt", "private-storage", "private-result", "apiKey", "endpoint", "raw_response");
        int before = logs.list.size();
        transactions.executeWithoutResult(tx -> { events.record("rolled_back", id, lease.attemptId()); tx.setRollbackOnly(); });
        assertThat(logs.list).hasSize(before);
        assertThat(store.fail(lease, lease.version(), new ProviderException("LATE", "late", true, false))).isFalse();
        assertThat(logs.list).hasSize(before);
    }

    @Test void failedInstallationRollsBackSuccessAndDoesNotEmitAFalseCompletion() {
        submit("rollback"); var lease = store.claim("rollback").orElseThrow();
        int before = logs.list.size();
        assertThatThrownBy(() -> store.succeed(lease, lease.version(), new VideoProvider.Result("key", 0))).isInstanceOf(Exception.class);
        assertThat(logs.list).hasSize(before);
        assertThat(tasks.get(alice.id(), lease.taskId()).state()).isEqualTo("running");
    }

    @Test void scopeDenialsUseRouteTemplatesAndDoNotExposeForeignIdentityOrRequestText() throws Exception {
        mvc.perform(get("/api/shots/" + shot + "?secret=do-not-log").with(user(bob)))
            .andExpect(status().isNotFound());
        assertThat(logs.list).hasSize(1);
        assertThat(fields(logs.list.getFirst())).containsEntry("event", "scope_not_found")
            .containsEntry("user_id", bob.id()).containsEntry("route", "/api/shots/{shotId}");
        assertThat(fields(logs.list.getFirst()).toString()).doesNotContain("secret", "do-not-log", "alice");
    }

    private long timedTask(String status, int start, int finish) {
        long id = submit(status);
        OffsetDateTime created = db.fetchOne("select now()-interval '60 seconds'").get(0, OffsetDateTime.class);
        db.execute("update task set created_at=?::timestamptz,status=?,attempt_count=1,finished_at=?::timestamptz where id=?", created, status, created.plusSeconds(finish), id);
        db.execute("insert into task_attempt(task_id,attempt_no,lease_owner,status,started_at,finished_at) values (?,1,'test',?,?::timestamptz,?::timestamptz)", id, status, created.plusSeconds(start), created.plusSeconds(finish));
        return id;
    }
    private long submit(String nonce) { return Long.parseLong(tasks.submit(alice.id(), shot, nonce).taskId()); }
    private Map<String, Object> fields(ILoggingEvent event) { return event.getKeyValuePairs().stream().collect(Collectors.toMap(pair -> pair.key, pair -> pair.value)); }
    private Viewer account(String email) { var user = users.saveAndFlush(new User(email, passwords.encode("test-password"))); return new Viewer(user.getId(), email, user.getPasswordHash()); }
}
