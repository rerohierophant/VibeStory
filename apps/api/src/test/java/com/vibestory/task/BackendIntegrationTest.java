package com.vibestory.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vibestory.auth.User;
import com.vibestory.auth.UserRepository;
import com.vibestory.auth.Viewer;
import com.vibestory.common.ApiException;
import com.vibestory.provider.ProviderException;
import com.vibestory.provider.VideoProvider;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"vibestory.worker.enabled=false", "vibestory.worker.lease-duration=3s",
    "vibestory.worker.heartbeat-interval=200ms", "vibestory.mock.delay=0ms"})
@AutoConfigureMockMvc
class BackendIntegrationTest extends PostgresSupport {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired DSLContext db;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder passwords;
    @Autowired TaskService tasks;
    @Autowired TaskStore store;
    @Autowired TaskWorker worker;
    @Autowired VideoProvider provider;
    private Viewer alice;
    private Viewer bob;
    private long projectId;
    private long episodeId;
    private long shotId;
    private long imageId;

    @BeforeEach void setup() throws Exception {
        resetDatabase(db);
        alice = account("alice@example.test");
        bob = account("bob@example.test");
        projectId = responseId(mvc.perform(post("/api/projects").with(user(alice)).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"雨夜\",\"visualType\":\"realistic\",\"style\":\"电影光影\"}"))
            .andExpect(status().isCreated()).andReturn(), "id");
        episodeId = responseId(mvc.perform(post("/api/projects/" + projectId + "/episodes").with(user(alice)).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"第一集\"}"))
            .andExpect(status().isCreated()).andReturn(), "id");
        shotId = db.fetchOne("""
            insert into shot(user_id, project_id, episode_id, seq, status, description)
            values (?, ?, ?, 1, 'CONFIRMED', '雨夜重逢') returning id
            """, alice.id(), projectId, episodeId).get("id", Long.class);
        imageId = db.fetchOne("""
            insert into shot_image(shot_id, episode_id, selected, storage_key) values (?, ?, true, 'mock/first-frame') returning id
            """, shotId, episodeId).get("id", Long.class);
    }

    @Test void realLoginRotatesSessionAndPersistsAuthenticationAndLogout() throws Exception {
        var csrfResult = mvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn();
        var session = (MockHttpSession) csrfResult.getRequest().getSession();
        String oldId = session.getId();
        String token = body(csrfResult).get("token").asText();
        mvc.perform(post("/api/auth/login").session(session).header("X-CSRF-TOKEN", token)
            .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"alice@example.test\",\"password\":\"test-password\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.email").value(alice.email()))
            .andExpect(jsonPath("$.passwordHash").doesNotExist());
        assertThat(session.getId()).isNotEqualTo(oldId);
        mvc.perform(get("/api/me").session(session)).andExpect(status().isOk()).andExpect(jsonPath("$.id").value("1"));
        mvc.perform(post("/api/projects").session(session).header("X-CSRF-TOKEN", token)
            .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isForbidden()); // The pre-login CSRF token was revoked.
        var fresh = body(mvc.perform(get("/api/auth/csrf").session(session)).andReturn()).get("token").asText();
        mvc.perform(post("/api/auth/logout").session(session).header("X-CSRF-TOKEN", fresh)).andExpect(status().isNoContent());
        mvc.perform(get("/api/me")).andExpect(status().isUnauthorized());
        assertThat(users.findByEmail(alice.email()).orElseThrow().getPasswordHash()).startsWith("$2");
    }

    @Test void invalidLoginAndMalformedInputAreProblemDetails() throws Exception {
        mvc.perform(post("/api/auth/login").with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"alice@example.test\",\"password\":\"incorrect\"}"))
            .andExpect(status().isUnauthorized()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        mvc.perform(post("/api/projects").with(user(alice)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"\",\"visualType\":\"realistic\"}"))
            .andExpect(status().isBadRequest()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        mvc.perform(post("/api/projects").with(user(alice)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"a\",\"visualType\":\"made-up\"}"))
            .andExpect(status().isBadRequest());
    }

    @Test void registrationCreatesAnIsolatedAccountAndRotatesSessionAndCsrf() throws Exception {
        String credentials = "{\"email\":\"Creator@Example.test\",\"password\":\"test-password\"}";
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(credentials))
            .andExpect(status().isForbidden());
        var csrfResult = mvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn();
        var session = (MockHttpSession) csrfResult.getRequest().getSession();
        String oldId = session.getId();
        String token = body(csrfResult).get("token").asText();
        var registered = mvc.perform(post("/api/auth/register").session(session).header("X-CSRF-TOKEN", token)
                .contentType(MediaType.APPLICATION_JSON).content(credentials))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.email").value("creator@example.test"))
            .andExpect(jsonPath("$.passwordHash").doesNotExist()).andReturn();
        assertThat(session.getId()).isNotEqualTo(oldId);
        var account = users.findByEmail("creator@example.test").orElseThrow();
        assertThat(passwords.matches("test-password", account.getPasswordHash())).isTrue();
        mvc.perform(get("/api/me").session(session)).andExpect(status().isOk())
            .andExpect(content().json(registered.getResponse().getContentAsString()));
        mvc.perform(get("/api/projects").session(session)).andExpect(content().json("[]"));
        mvc.perform(get("/api/projects/{id}", projectId).session(session)).andExpect(status().isNotFound());
        mvc.perform(post("/api/projects").session(session).header("X-CSRF-TOKEN", token)
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isForbidden());
    }

    @Test void concurrentRegistrationOfSameEmailCreatesOnlyOneAccount() throws Exception {
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var start = new CountDownLatch(1);
            List<Future<MvcResult>> submissions = new ArrayList<>();
            for (String email : List.of("new@example.test", "NEW@example.test")) {
                submissions.add(pool.submit(() -> {
                    start.await();
                    return mvc.perform(post("/api/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", email, "password", "test-password")))).andReturn();
                }));
            }
            start.countDown();
            var first = submissions.get(0).get(10, TimeUnit.SECONDS);
            var second = submissions.get(1).get(10, TimeUnit.SECONDS);
            assertThat(List.of(first.getResponse().getStatus(), second.getResponse().getStatus()))
                .containsExactlyInAnyOrder(201, 409);
            var conflict = first.getResponse().getStatus() == 409 ? first : second;
            assertThat(body(conflict).get("detail").asText()).isEqualTo("该邮箱已注册，请直接登录");
        }
        assertThat(users.count()).isEqualTo(3);
        mvc.perform(post("/api/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", "long@example.test", "password", "密".repeat(25)))))
            .andExpect(status().isBadRequest());
        assertThat(users.findByEmail("long@example.test")).isEmpty();
    }

    @Test void allResourceLevelsHideOtherUsersData() throws Exception {
        long taskId = submit("scope");
        mvc.perform(get("/api/projects/" + projectId).with(user(bob))).andExpect(status().isNotFound());
        mvc.perform(get("/api/projects/" + projectId + "/episodes").with(user(bob))).andExpect(status().isNotFound());
        mvc.perform(post("/api/projects/" + projectId + "/episodes").with(user(bob)).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"bad\"}")).andExpect(status().isNotFound());
        mvc.perform(post("/api/shots/" + shotId + "/video-tasks").with(user(bob)).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content("{\"regenerateNonce\":\"bad\"}")).andExpect(status().isNotFound());
        mvc.perform(get("/api/tasks/" + taskId).with(user(bob))).andExpect(status().isNotFound());
        mvc.perform(get("/api/tasks/" + taskId + "/video").with(user(bob))).andExpect(status().isNotFound());
        mvc.perform(post("/api/tasks/" + taskId + "/retry").with(user(bob)).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isNotFound());
        mvc.perform(get("/api/projects").with(user(bob))).andExpect(content().json("[]"));
    }

    @Test void draftOrMissingFirstFrameCannotGenerate() throws Exception {
        db.execute("update shot set status = 'DRAFT' where id = ?", shotId);
        submitRequest("draft", 409);
        db.execute("update shot set status = 'CONFIRMED' where id = ?", shotId);
        db.execute("update shot_image set selected = false where id = ?", imageId);
        submitRequest("no-image", 409);
    }

    @Test void tenConcurrentSubmissionsAndClaimsCreateOnlyOneTaskAndAttempt() throws Exception {
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var submissions = new ArrayList<Future<Long>>();
            for (int i = 0; i < 10; i++) submissions.add(pool.submit(() -> submit("same-click")));
            var ids = new ArrayList<Long>();
            for (var future : submissions) ids.add(future.get(10, TimeUnit.SECONDS));
            assertThat(ids).containsOnly(ids.getFirst());
            var claims = new ArrayList<Future<java.util.Optional<TaskStore.Lease>>>();
            for (int i = 0; i < 10; i++) {
                String owner = "worker-" + i;
                claims.add(pool.submit(() -> store.claim(owner)));
            }
            int claimed = 0;
            for (var future : claims) if (future.get(10, TimeUnit.SECONDS).isPresent()) claimed++;
            assertThat(claimed).isEqualTo(1);
        }
        assertThat(count("task")).isEqualTo(1);
        assertThat(count("task_attempt")).isEqualTo(1);
    }

    @Test void lockedQueueHeadDoesNotBlockClaimingTheNextTask() throws Exception {
        long first = submit("locked-first");
        long second = submit("unlocked-second");
        try (var connection = db.configuration().connectionProvider().acquire()) {
            connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement("select id from task where id = ? for update")) {
                statement.setLong(1, first);
                statement.executeQuery().close();
            }
            try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
                var claim = pool.submit(() -> store.claim("skip-locked").orElseThrow());
                assertThat(claim.get(2, TimeUnit.SECONDS).taskId()).isEqualTo(second);
            } finally { connection.rollback(); }
        }
    }

    @Test void actualWorkerWritesThreeSecondMp4AndProjectsSuccessAsReadyIdle() throws Exception {
        long id = submit("worker");
        assertThat(tasks.get(alice.id(), id).state()).isEqualTo("queued");
        worker.dispatchAvailable();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(tasks.get(alice.id(), id).ready()).isTrue());
        var view = tasks.get(alice.id(), id);
        assertThat(view.state()).isEqualTo("idle");
        assertThat(view.attempts()).isEqualTo(1);
        assertThat(db.fetchOne("select status from task where id = ?", id).get(0, String.class)).isEqualTo("SUCCEEDED");
        byte[] bytes = mvc.perform(get(view.videoUrl()).with(user(alice))).andExpect(status().isOk())
            .andExpect(content().contentType("video/mp4")).andReturn().getResponse().getContentAsByteArray();
        assertThat(bytes).hasSizeGreaterThan(1000);
        var channel = org.jcodec.common.io.NIOUtils.readableChannel(tasks.video(alice.id(), id).getFile());
        try {
            var movie = org.jcodec.containers.mp4.demuxer.MP4Demuxer.createMP4Demuxer(channel);
            assertThat(movie.getVideoTrack().getMeta().getTotalDuration()).isEqualTo(3.0);
        } finally { channel.close(); }
        mvc.perform(get(view.videoUrl()).with(user(alice)).header("Range", "bytes=0-31"))
            .andExpect(status().isPartialContent()).andExpect(header().string("Content-Length", "32"));
    }

    @Test void expiredLeaseIncrementsAttemptAndFencesOldWorkerWrites() throws Exception {
        long id = submit("crash");
        var old = store.claim("old-process").orElseThrow();
        assertThat(store.heartbeat(old, old.version() - 1)).isEmpty();
        db.execute("update task set lease_until = now() - interval '1 second' where id = ?", id);
        assertThat(store.heartbeat(old, old.version())).isEmpty();
        var replacement = store.claim("new-process").orElseThrow();
        assertThat(replacement.attemptNo()).isEqualTo(2);
        assertThat(store.succeed(old, old.version(), new VideoProvider.Result("stale.mp4", 3000))).isFalse();
        assertThat(store.fail(old, old.version(), new ProviderException("OLD", "old", true, false))).isFalse();
        long renewed = store.heartbeat(replacement, replacement.version()).orElseThrow();
        assertThat(store.succeed(replacement, replacement.version(), new VideoProvider.Result("stale-version.mp4", 3000))).isFalse();
        assertThat(store.succeed(replacement, renewed, new VideoProvider.Result("replacement.mp4", 3000))).isTrue();
        assertThat(tasks.get(alice.id(), id).history()).extracting(TaskService.Attempt::state).containsExactly("abandoned", "succeeded");
        assertThat(count("video")).isEqualTo(1);
    }

    @Test void videoKeepsSubmittedSourceAfterSelectionChanges() throws Exception {
        long id = submit("source");
        db.execute("update shot_image set selected = false where id = ?", imageId);
        db.execute("insert into shot_image(shot_id, episode_id, selected, storage_key) values (?, ?, true, 'mock/new-frame')", shotId, episodeId);
        var lease = store.claim("source-worker").orElseThrow();
        assertThat(store.succeed(lease, lease.version(), new VideoProvider.Result("source.mp4", 3000))).isTrue();
        assertThat(tasks.get(alice.id(), id).basedOn()).isEqualTo(Long.toString(imageId));
        assertThat(db.fetchOne("select source_image_id from video where task_id = ?", id).get(0, Long.class)).isEqualTo(imageId);
        assertThat(tasks.get(alice.id(), id).ready()).isTrue();
    }

    @Test void nonceCreatesNewTaskWhileRetryReusesTaskAndUnknownRequiresAcknowledgement() throws Exception {
        long id = submit("first");
        assertThat(submit("second")).isNotEqualTo(id);
        var lease = store.claim("retry-worker").orElseThrow();
        store.fail(lease, lease.version(), new ProviderException("MOCK_REJECTED", "failed", true, false));
        assertThat(tasks.get(alice.id(), id).retryable()).isTrue();
        assertThat(tasks.retry(alice.id(), id, false).taskId()).isEqualTo(Long.toString(id));
        var retry = store.claim("retry-worker-2").orElseThrow();
        assertThat(retry.attemptNo()).isEqualTo(2);
        store.fail(retry, retry.version(), new ProviderException("MOCK_RESULT_UNKNOWN", "unknown", false, true));
        assertThat(tasks.get(alice.id(), id).state()).isEqualTo("unknown");
        assertThatThrownBy(() -> tasks.retry(alice.id(), id, false)).isInstanceOf(ApiException.class);
        assertThat(tasks.retry(alice.id(), id, true).taskId()).isEqualTo(Long.toString(id));
        var last = store.claim("retry-worker-3").orElseThrow();
        store.fail(last, last.version(), new ProviderException("MOCK_REJECTED", "failed", true, false));
        assertThat(tasks.get(alice.id(), id).retryable()).isFalse();
        assertThatThrownBy(() -> tasks.retry(alice.id(), id, true)).isInstanceOf(ApiException.class);
    }

    @Test void expiredFinalAttemptStopsInsteadOfRetryingForever() throws Exception {
        long id = submit("exhausted");
        for (int i = 0; i < 3; i++) {
            assertThat(store.claim("dead-" + i)).isPresent();
            db.execute("update task set lease_until = now() - interval '1 second' where id = ?", id);
        }
        assertThat(store.claim("reaper")).isEmpty();
        assertThat(tasks.get(alice.id(), id).errorCode()).isEqualTo("MAX_ATTEMPTS");
        assertThat(tasks.get(alice.id(), id).state()).isEqualTo("failed");
        assertThat(db.fetch("select id from task_attempt where status = 'RUNNING'")).isEmpty();
    }

    @Test void concurrentEpisodeCreationAllocatesDistinctIndexesAndOpenApiIsAvailable() throws Exception {
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var creates = new ArrayList<Future<MvcResult>>();
            for (int i = 0; i < 5; i++) creates.add(pool.submit(() -> mvc.perform(
                post("/api/projects/" + projectId + "/episodes").with(user(alice)).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"并发集\"}"))
                .andExpect(status().isCreated()).andReturn()));
            for (var create : creates) create.get(10, TimeUnit.SECONDS);
        }
        assertThat(db.fetch("select episode_index from episode order by episode_index").getValues(0, Integer.class))
            .containsExactly(1, 2, 3, 4, 5, 6);
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
            .andExpect(jsonPath("$.paths['/api/shots/{shotId}/video-tasks']").exists())
            .andExpect(jsonPath("$.paths['/api/auth/logout']").exists())
            .andExpect(jsonPath("$.components.schemas.Project.properties.visualType").exists())
            .andExpect(jsonPath("$.components.schemas.Episode.properties.story").exists())
            .andExpect(jsonPath("$.components.schemas.Task.properties.ready").exists())
            .andExpect(jsonPath("$.paths['/api/auth/login'].post.parameters[0].name").value("X-CSRF-TOKEN"))
            .andExpect(jsonPath("$.paths['/api/auth/register'].post.parameters[0].name").value("X-CSRF-TOKEN"))
            .andExpect(jsonPath("$.paths['/api/auth/csrf'].get.parameters").doesNotExist());
    }

    private Viewer account(String email) {
        var saved = users.saveAndFlush(new User(email, passwords.encode("test-password")));
        return new Viewer(saved.getId(), email, saved.getPasswordHash());
    }
    private long submit(String nonce) throws Exception { return responseId(submitRequest(nonce, 202), "taskId"); }
    private MvcResult submitRequest(String nonce, int expected) throws Exception {
        return mvc.perform(post("/api/shots/" + shotId + "/video-tasks").with(user(alice)).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("regenerateNonce", nonce))))
            .andExpect(status().is(expected)).andReturn();
    }
    private int count(String table) { return db.fetchOne("select count(*) from " + table).get(0, Integer.class); }
    private JsonNode body(MvcResult result) throws Exception { return json.readTree(result.getResponse().getContentAsByteArray()); }
    private long responseId(MvcResult result, String field) throws Exception { return body(result).get(field).asLong(); }
}
