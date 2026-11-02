package com.vibestory.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vibestory.auth.*;
import com.vibestory.breakdown.BreakdownService;
import com.vibestory.common.ApiException;
import com.vibestory.provider.*;
import com.vibestory.storage.LocalStorage;
import com.vibestory.storage.StorageCleanup;
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
class PhaseThreeIntegrationTest extends PostgresSupport {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired DSLContext db;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder passwords;
    @Autowired BreakdownService breakdowns;
    @Autowired TaskService tasks;
    @Autowired TaskStore store;
    @Autowired TaskWorker worker;
    @Autowired MockTextProvider mock;
    @Autowired LocalStorage storage;
    @Autowired StorageCleanup cleanup;
    private Viewer alice;
    private Viewer bob;
    private long project;
    private long episode;
    private String story;

    @BeforeEach void setup() throws Exception {
        db.execute("truncate app_user, storage_cleanup restart identity cascade");
        alice = account("alice@example.test"); bob = account("bob@example.test");
        project = id(postJson("/api/projects", Map.of("name", "阶段三", "visualType", "realistic"), 201));
        episode = id(postJson("/api/projects/" + project + "/episodes", Map.of(), 201));
        story = "  雨落在路灯下，积水映着人影。沈夜收起伞走到便利店门口，店员抬头。她说：「一份萝卜。」许星遥放下牛奶，望向门外。\r\n\n".repeat(14);
        saveStory(story);
    }

    @Test void workerBreaksDownAtLeast800CharactersIntoTenEditableDrafts() throws Exception {
        assertThat(story.length()).isGreaterThanOrEqualTo(800);
        mvc.perform(get("/api/episodes/" + episode + "/breakdowns/latest").with(user(alice))).andExpect(status().isNoContent());
        long task = submit(false);
        assertThat(tasks.get(alice.id(), task).type()).isEqualTo("breakdown");
        worker.dispatchAvailable();
        await().atMost(Duration.ofSeconds(12)).untilAsserted(() -> assertThat(tasks.get(alice.id(), task).ready()).isTrue());
        var shots = shots();
        assertThat(shots.size()).isGreaterThanOrEqualTo(10);
        for (int index = 0; index < shots.size(); index++) {
            assertThat(shots.get(index).get("seq").asInt()).isEqualTo(index + 1);
            assertThat(shots.get(index).get("status").asText()).isEqualTo("draft");
        }
        mvc.perform(get("/api/episodes/" + episode).with(user(alice))).andExpect(jsonPath("$.breakdown").value("done"));
        assertThat(tasks.get(alice.id(), task).basedOn()).isNull();
        mvc.perform(get("/api/tasks/" + task).with(user(alice)))
            .andExpect(jsonPath("$.basedOn").doesNotExist()).andExpect(jsonPath("$.videoUrl").doesNotExist());
        assertThat(tasks.get(alice.id(), task).history().getFirst().model()).isEqualTo("mock-splitter-v1");
    }

    @Test void concurrentSubmissionsAndClaimsReuseOneBreakdownTaskAndAttempt() throws Exception {
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var submissions = new ArrayList<Future<String>>();
            for (int i = 0; i < 10; i++) submissions.add(pool.submit(() -> breakdowns.submit(alice.id(), episode, false).taskId()));
            var ids = new ArrayList<String>();
            for (var submission : submissions) ids.add(submission.get(5, TimeUnit.SECONDS));
            assertThat(ids).containsOnly(ids.getFirst());
            var claims = new ArrayList<Future<java.util.Optional<TaskStore.Lease>>>();
            for (int i = 0; i < 10; i++) {
                String owner = "claim-" + i;
                claims.add(pool.submit(() -> store.claim(owner)));
            }
            int active = 0;
            for (var claim : claims) if (claim.get(5, TimeUnit.SECONDS).isPresent()) active++;
            assertThat(active).isEqualTo(1);
        }
        assertThat(count("task")).isEqualTo(1);
        assertThat(count("task_attempt")).isEqualTo(1);
    }

    @Test void existingShotsRequireExplicitConfirmationBeforeAnyMutation() throws Exception {
        completed();
        long shot = shots().get(0).get("id").asLong();
        putStatus(shot, "confirmed");
        patchShot(shot, Map.of("description", "人工修改不得无确认覆盖"));
        var ids = shotIds();
        postJson("/api/episodes/" + episode + "/breakdowns", Map.of(), 409);
        assertThat(shotIds()).isEqualTo(ids);
        assertThat(count("task")).isEqualTo(1);
        mvc.perform(get("/api/shots/" + shot).with(user(alice)))
            .andExpect(jsonPath("$.status").value("confirmed")).andExpect(jsonPath("$.description").value("人工修改不得无确认覆盖"));
        long task = submit(true);
        assertThat(shotIds()).isEqualTo(ids); // Keep old results until a valid replacement wins.
        finish(mock.generate(breakdowns.input(db.fetchOne("select input_snapshot from task where id = ?", task).get(0, String.class))));
        assertThat(shotIds()).doesNotContainAnyElementsOf(ids);
        for (var row : shots()) assertThat(row.get("status").asText()).isEqualTo("draft");
    }

    @Test void rebreakdownDeletesOnlyCurrentEpisodeMediaAndKeepsTasksAttemptsAndAssets() throws Exception {
        completed();
        long old = shots().get(0).get("id").asLong();
        putStatus(old, "confirmed");
        db.execute("insert into shot_image(shot_id, episode_id, selected, storage_key) values (?, ?, true, 'mock/frame')", old, episode);
        long videoTask = Long.parseLong(tasks.submit(alice.id(), old, "video").taskId());
        var videoLease = store.claim("old-video").orElseThrow();
        String key = "u/" + alice.id() + "/p/" + project + "/e/" + episode + "/video/" + videoTask + "-" + videoLease.signature() + ".mp4";
        storage.put(key, new byte[]{1});
        store.succeed(videoLease, videoLease.version(), new VideoProvider.Result(key, 3000));
        long second = id(postJson("/api/projects/" + project + "/episodes", Map.of(), 201));
        db.execute("insert into shot(user_id, project_id, episode_id, seq, status) values (?, ?, ?, 1, 'CONFIRMED')", alice.id(), project, second);
        long replacement = submit(true);
        finish(namedResult());
        assertThat(count("video")).isZero();
        assertThat(count("shot_image")).isZero();
        assertThat(count("task")).isEqualTo(3);
        assertThat(count("task_attempt")).isEqualTo(3);
        assertThat(tasks.get(alice.id(), replacement).ready()).isTrue();
        assertThat(db.fetchOne("select count(*) from shot where episode_id = ? and status = 'CONFIRMED'", second).get(0, Integer.class)).isEqualTo(1);
        cleanup.sweep();
        assertThat(storage.resource(key).exists()).isFalse();
        assertThat(count("character_asset")).isEqualTo(2);
        assertThat(count("scene_asset")).isGreaterThanOrEqualTo(1);
        assertThatThrownBy(() -> tasks.retry(alice.id(), videoTask, false)).isInstanceOf(ApiException.class);
    }

    @Test void inputSnapshotPreservesStoryAndChangedStoryCannotBeOverwritten() throws Exception {
        completed();
        var oldIds = shotIds();
        long task = submit(true);
        var lease = store.claim("old-source").orElseThrow();
        assertThat(breakdowns.input(lease.input()).story()).isEqualTo(story);
        saveStory("修改后的正文。新的角色走进旧宅客厅，故事已与提交时不同。".repeat(10));
        assertThat(store.succeedBreakdown(lease, lease.version(), namedResult())).isFalse();
        assertThat(shotIds()).isEqualTo(oldIds);
        assertThat(tasks.get(alice.id(), task).errorCode()).isEqualTo("BREAKDOWN_SOURCE_CHANGED");
        assertThat(tasks.get(alice.id(), task).ready()).isFalse();
        mvc.perform(get("/api/episodes/" + episode).with(user(alice))).andExpect(jsonPath("$.breakdown").value("idle"));
    }

    @Test void replacementCancelsInFlightVideoAndCleansItsLatePublishedFile() throws Exception {
        completed();
        long oldShot = shots().get(0).get("id").asLong();
        putStatus(oldShot, "confirmed");
        db.execute("insert into shot_image(shot_id, episode_id, selected, storage_key) values (?, ?, true, 'mock/frame')", oldShot, episode);
        long videoTask = Long.parseLong(tasks.submit(alice.id(), oldShot, "still-running").taskId());
        var oldVideo = store.claim("old-video").orElseThrow();
        submit(true); finish(namedResult());
        String key = "u/" + alice.id() + "/p/" + project + "/e/" + episode + "/video/" + videoTask + "-" + oldVideo.signature() + ".mp4";
        storage.put(key, new byte[]{1});
        assertThat(store.succeed(oldVideo, oldVideo.version(), new VideoProvider.Result(key, 3000))).isFalse();
        assertThat(tasks.get(alice.id(), videoTask).errorCode()).isEqualTo("SHOTS_REPLACED");
        assertThat(tasks.get(alice.id(), videoTask).history().getFirst().state()).isEqualTo("canceled");
        cleanup.sweep();
        assertThat(storage.resource(key).exists()).isFalse();
    }

    @Test void manualShotEditWhileWaitingPreventsReplacementOfThatEdit() throws Exception {
        completed();
        long task = submit(true);
        var lease = store.claim("editing").orElseThrow();
        long shot = shots().get(0).get("id").asLong();
        patchShot(shot, Map.of("description", "拆解提交后的人工修改"));
        assertThat(store.succeedBreakdown(lease, lease.version(), namedResult())).isFalse();
        mvc.perform(get("/api/shots/" + shot).with(user(alice))).andExpect(jsonPath("$.description").value("拆解提交后的人工修改"));
        assertThat(tasks.get(alice.id(), task).state()).isEqualTo("failed");
    }

    @Test void newSourceSubmissionFencesOlderAttemptWithoutErasingTheNewRunningMarker() throws Exception {
        long oldTask = submit(false);
        var old = store.claim("superseded").orElseThrow();
        saveStory("另一份正文。路灯照亮了新场景，人物沿着街道走向便利店。".repeat(10));
        long next = submit(false);
        assertThat(next).isNotEqualTo(oldTask);
        assertThat(store.succeedBreakdown(old, old.version(), namedResult())).isFalse();
        mvc.perform(get("/api/episodes/" + episode).with(user(alice))).andExpect(jsonPath("$.breakdown").value("running"));
        finish(namedResult());
        assertThat(tasks.get(alice.id(), oldTask).history().getFirst().state()).isEqualTo("canceled");
        assertThat(tasks.get(alice.id(), next).ready()).isTrue();
    }

    @Test void metadataEditDoesNotInvalidateTheSameStoryRevision() throws Exception {
        long task = submit(false);
        var lease = store.claim("metadata").orElseThrow();
        mvc.perform(patch("/api/episodes/" + episode).with(user(alice)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"title\":\"新标题\",\"summary\":\"新梗概\"}")).andExpect(status().isOk());
        assertThat(store.succeedBreakdown(lease, lease.version(), namedResult())).isTrue();
        mvc.perform(get("/api/episodes/" + episode).with(user(alice)))
            .andExpect(jsonPath("$.title").value("新标题")).andExpect(jsonPath("$.breakdown").value("done"));
    }

    @Test void repeatedCharacterAndSceneNamesResolveOnceAndScopeReferencesToProject() throws Exception {
        submit(false); finish(namedResult());
        assertThat(count("character_asset")).isEqualTo(2);
        assertThat(count("scene_asset")).isEqualTo(1);
        assertThat(count("asset_ref")).isEqualTo(6);
        assertThat(shots().get(0).get("characterNames").toString()).isEqualTo("[\"沈夜\",\"alice\"]");
        long second = id(postJson("/api/projects/" + project + "/episodes", Map.of(), 201));
        mvc.perform(put("/api/episodes/" + second + "/story").with(user(alice)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsBytes(Map.of("story", story)))).andExpect(status().isOk());
        breakdowns.submit(alice.id(), second, false); finish(namedResult());
        assertThat(count("character_asset")).isEqualTo(2);
        assertThat(count("scene_asset")).isEqualTo(1);
        mvc.perform(get("/api/projects/" + project + "/characters").with(user(alice))).andExpect(jsonPath("$.length()").value(2));
        mvc.perform(get("/api/projects/" + project + "/scenes").with(user(alice))).andExpect(jsonPath("$.length()").value(1));
    }

    @Test void draftCannotGenerateAndConfirmedShotRemainsEditableAndCanReturnToDraft() throws Exception {
        completed();
        long shot = shots().get(0).get("id").asLong();
        postJson("/api/shots/" + shot + "/video-tasks", Map.of("regenerateNonce", "draft"), 409);
        putStatus(shot, "confirmed");
        var edited = patchShot(shot, Map.of("description", "修改后的确认镜头", "dialogue", "台词", "sceneName", "新场景", "shotSize", "特写", "cameraMove", "推", "durationMs", 4500));
        assertThat(edited.get("status").asText()).isEqualTo("confirmed");
        mvc.perform(get("/api/shots/" + shot).with(user(alice))).andExpect(jsonPath("$.description").value("修改后的确认镜头"));
        putStatus(shot, "draft");
        postJson("/api/shots/" + shot + "/video-tasks", Map.of("regenerateNonce", "draft-again"), 409);
        mvc.perform(put("/api/shots/" + shot + "/status").with(user(alice)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\":\"frozen\"}")).andExpect(status().isBadRequest());
        mvc.perform(patch("/api/shots/" + shot).with(user(alice)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"durationMs\":13000}")).andExpect(status().isBadRequest());
        postJson("/api/episodes/" + episode + "/shots/confirm-all", Map.of(), 200);
        for (var item : shots()) assertThat(item.get("status").asText()).isEqualTo("confirmed");
    }

    @Test void todoFilterMatchesPageImagesLengthRuleEvenWhenDraftOrUnselected() throws Exception {
        submit(false); finish(namedResult());
        var rows = shots();
        long first = rows.get(0).get("id").asLong();
        db.execute("insert into shot_image(shot_id, episode_id, selected, storage_key) values (?, ?, false, 'mock/unselected')", first, episode);
        mvc.perform(get("/api/episodes/" + episode + "/shots").param("filter", "todo").with(user(alice)))
            .andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get("/api/episodes/" + episode + "/shots").param("filter", "draft").with(user(alice)))
            .andExpect(jsonPath("$.length()").value(2));
    }

    @Test void failedProviderKeepsExistingShotsAndRawDiagnosticsArePrivate() throws Exception {
        completed(); var old = shotIds();
        long task = submit(true);
        var lease = store.claim("invalid-json").orElseThrow();
        store.fail(lease, lease.version(), new ProviderException("TEXT_INVALID_OUTPUT", "JSON 失败", true, false, "not-json-response", "fixture-model"));
        assertThat(shotIds()).isEqualTo(old);
        String url = tasks.get(alice.id(), task).history().getFirst().diagnosticUrl();
        mvc.perform(get(url).with(user(alice))).andExpect(status().isOk()).andExpect(jsonPath("$.rawResponse").value("not-json-response"));
        mvc.perform(get(url).with(user(bob))).andExpect(status().isNotFound());
        assertThat(tasks.retry(alice.id(), task, false).taskId()).isEqualTo(Long.toString(task));
        var retried = store.claim("retry").orElseThrow();
        assertThat(retried.attemptNo()).isEqualTo(2);
        store.succeedBreakdown(retried, retried.version(), namedResult());
        assertThat(tasks.get(alice.id(), task).ready()).isTrue();
    }

    @Test void changedSourceRejectsRetryOfFailedTask() throws Exception {
        long task = submit(false);
        var lease = store.claim("failed").orElseThrow();
        store.fail(lease, lease.version(), new ProviderException("TEXT_INVALID_OUTPUT", "invalid", true, false));
        saveStory("新正文已变化，不能重试旧快照覆盖这份内容。".repeat(12));
        assertThatThrownBy(() -> tasks.retry(alice.id(), task, false)).isInstanceOf(ApiException.class);
    }

    @Test void expiredRealProviderBecomesUnknownAndRequiresAcknowledgementWithoutAutomaticReplay() throws Exception {
        long task = submit(false);
        db.execute("update task set provider = 'openai-compatible', input_snapshot = jsonb_set(input_snapshot, '{provider}', '\"openai-compatible\"'::jsonb) where id = ?", task);
        var lease = store.claim("real-process").orElseThrow();
        expire(task);
        assertThat(store.claim("replacement")).isEmpty();
        assertThat(tasks.get(alice.id(), task).state()).isEqualTo("unknown");
        assertThat(tasks.get(alice.id(), task).attempts()).isEqualTo(1);
        mvc.perform(get("/api/episodes/" + episode).with(user(alice))).andExpect(jsonPath("$.breakdown").value("idle"));
        assertThatThrownBy(() -> breakdowns.submit(alice.id(), episode, false)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> tasks.retry(alice.id(), task, false)).isInstanceOf(ApiException.class);
        assertThat(tasks.retry(alice.id(), task, true).taskId()).isEqualTo(Long.toString(task));
        assertThat(store.claim("explicit-retry").orElseThrow().attemptNo()).isEqualTo(2);
    }

    @Test void expiredMockProviderCanReplayButOldOwnerCannotApply() throws Exception {
        long task = submit(false);
        var old = store.claim("old").orElseThrow(); expire(task);
        var replacement = store.claim("replacement").orElseThrow();
        assertThat(store.succeedBreakdown(old, old.version(), namedResult())).isFalse();
        assertThat(store.succeedBreakdown(replacement, replacement.version(), namedResult())).isTrue();
        assertThat(tasks.get(alice.id(), task).history()).extracting(TaskService.Attempt::state).containsExactly("abandoned", "succeeded");
    }

    @Test void applyingInvalidResultRollsBackTaskCompletionAndLeavesOldDataIntact() throws Exception {
        completed(); var old = shotIds();
        long task = submit(true);
        var lease = store.claim("rollback").orElseThrow();
        var invalid = new TextProvider.Result(List.of(new TextProvider.ShotDraft("x", "", "场景", "非法", "固定", 3000, List.of())), null, "fixture");
        int cleanupBefore = count("storage_cleanup");
        assertThatThrownBy(() -> store.succeedBreakdown(lease, lease.version(), invalid)).isInstanceOf(IllegalArgumentException.class);
        assertThat(tasks.get(alice.id(), task).state()).isEqualTo("running");
        assertThat(shotIds()).isEqualTo(old);
        assertThat(count("storage_cleanup")).isEqualTo(cleanupBefore);
        assertThat(store.succeedBreakdown(lease, lease.version(), namedResult())).isTrue();
    }

    @Test void deletingProjectCascadesAssetsAndFencesPendingBreakdown() throws Exception {
        submit(false); finish(namedResult());
        long task = submit(true);
        var lease = store.claim("deleted").orElseThrow();
        mvc.perform(delete("/api/projects/" + project).with(user(alice)).with(csrf())).andExpect(status().isNoContent());
        assertThat(count("asset_ref")).isZero();
        assertThat(count("scene_asset")).isZero();
        assertThat(count("character_asset")).isZero();
        assertThat(store.succeedBreakdown(lease, lease.version(), namedResult())).isFalse();
    }

    @Test void allNewResourcePathsEnforceUserScopeAndGeneratedContractSeparatesPayloads() throws Exception {
        submit(false); finish(namedResult());
        long shot = shots().get(0).get("id").asLong();
        for (String path : List.of("/api/episodes/" + episode + "/shots", "/api/episodes/" + episode + "/breakdowns/latest",
                "/api/shots/" + shot, "/api/projects/" + project + "/characters", "/api/projects/" + project + "/scenes"))
            mvc.perform(get(path).with(user(bob))).andExpect(status().isNotFound());
        mvc.perform(post("/api/episodes/" + episode + "/breakdowns").with(user(bob)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"replaceExisting\":true}")).andExpect(status().isNotFound());
        mvc.perform(patch("/api/shots/" + shot).with(user(bob)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"description\":\"bad\"}")).andExpect(status().isNotFound());
        mvc.perform(put("/api/shots/" + shot + "/status").with(user(bob)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\":\"confirmed\"}")).andExpect(status().isNotFound());
        mvc.perform(post("/api/episodes/" + episode + "/shots/confirm-all").with(user(bob)).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
            .andExpect(jsonPath("$.components.schemas.SubmitBreakdown.properties.replaceExisting").exists())
            .andExpect(jsonPath("$.components.schemas.Submit.properties.regenerateNonce").exists())
            .andExpect(jsonPath("$.paths['/api/episodes/{episodeId}/breakdowns'].post.responses['202']").exists());
    }

    private TextProvider.Result namedResult() {
        return new TextProvider.Result(List.of(
            new TextProvider.ShotDraft("沈夜进店", "你好", "便利店", "近景", "固定", 3000, List.of("沈夜", " alice ", "ALICE")),
            new TextProvider.ShotDraft("沈夜回头", "", " 便利店 ", "全景", "推", 4000, List.of("沈夜", "alice"))), "fixture-raw-response", "fixture-model");
    }
    private long completed() throws Exception {
        long task = submit(false);
        var lease = store.claim("initial").orElseThrow();
        assertThat(store.succeedBreakdown(lease, lease.version(), mock.generate(breakdowns.input(lease.input())))).isTrue();
        return task;
    }
    private void finish(TextProvider.Result result) {
        var lease = store.claim("finish").orElseThrow();
        assertThat(store.succeedBreakdown(lease, lease.version(), result)).isTrue();
    }
    private long submit(boolean replace) throws Exception {
        return postJson("/api/episodes/" + episode + "/breakdowns", Map.of("replaceExisting", replace), 202).get("taskId").asLong();
    }
    private void saveStory(String text) throws Exception {
        mvc.perform(put("/api/episodes/" + episode + "/story").with(user(alice)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsBytes(Map.of("story", text)))).andExpect(status().isOk());
    }
    private JsonNode shots() throws Exception {
        return body(mvc.perform(get("/api/episodes/" + episode + "/shots").with(user(alice))).andExpect(status().isOk()).andReturn());
    }
    private List<Long> shotIds() { return db.fetch("select id from shot where episode_id = ? order by seq", episode).getValues(0, Long.class); }
    private void putStatus(long shot, String status) throws Exception {
        mvc.perform(put("/api/shots/" + shot + "/status").with(user(alice)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsBytes(Map.of("status", status)))).andExpect(status().isOk());
    }
    private JsonNode patchShot(long shot, Object input) throws Exception {
        return body(mvc.perform(patch("/api/shots/" + shot).with(user(alice)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsBytes(input))).andExpect(status().isOk()).andReturn());
    }
    private JsonNode postJson(String path, Object input, int status) throws Exception {
        return body(mvc.perform(post(path).with(user(alice)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsBytes(input))).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().is(status)).andReturn());
    }
    private Viewer account(String email) {
        var saved = users.saveAndFlush(new User(email, passwords.encode("test-password")));
        return new Viewer(saved.getId(), email, saved.getPasswordHash());
    }
    private void expire(long task) { db.execute("update task set lease_until = now() - interval '1 second' where id = ?", task); }
    private JsonNode body(MvcResult result) throws Exception { return json.readTree(result.getResponse().getContentAsByteArray()); }
    private long id(JsonNode node) { return node.get("id").asLong(); }
    private int count(String table) { return db.fetchOne("select count(*) from " + table).get(0, Integer.class); }
}
