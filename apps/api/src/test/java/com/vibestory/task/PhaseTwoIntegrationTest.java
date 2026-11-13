package com.vibestory.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vibestory.auth.User;
import com.vibestory.auth.UserRepository;
import com.vibestory.auth.Viewer;
import com.vibestory.common.ScopeGuard;
import com.vibestory.project.ProjectService;
import com.vibestory.provider.VideoProvider;
import com.vibestory.storage.LocalStorage;
import com.vibestory.storage.StorageCleanup;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"vibestory.worker.enabled=false", "vibestory.worker.lease-duration=3s",
    "vibestory.worker.heartbeat-interval=200ms", "vibestory.mock.delay=0ms"})
@AutoConfigureMockMvc
class PhaseTwoIntegrationTest extends PostgresSupport {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired DSLContext db;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder passwords;
    @Autowired TaskService tasks;
    @Autowired TaskStore store;
    @Autowired ProjectService projects;
    @Autowired ScopeGuard scope;
    @Autowired LocalStorage storage;
    @Autowired StorageCleanup cleanup;
    @Autowired PlatformTransactionManager transactions;
    private Viewer alice;
    private Viewer bob;

    @BeforeEach void setup() {
        resetDatabase(db);
        alice = account("alice@example.test");
        bob = account("bob@example.test");
    }

    @Test void projectCrudAndSearchUseLiteralCaseInsensitiveNamesWithinUserScope() throws Exception {
        var first = project("realistic", "Film 100%_One");
        project("anime", "Film Two");
        var results = body(mvc.perform(get("/api/projects").param("q", "100%_").with(user(alice)))
            .andExpect(status().isOk()).andReturn());
        assertThat(results.size()).isEqualTo(1);
        assertThat(results.get(0).get("id").asText()).isEqualTo(first.get("id").asText());
        var updated = patchJson("/api/projects/" + first.get("id").asText(), Map.of("name", "  雨夜  ", "style", "", "visualType", "cyber"));
        assertThat(updated.get("name").asText()).isEqualTo("雨夜");
        assertThat(updated.get("style").asText()).isEmpty();
        assertThat(updated.get("visualPreset").get("id").asText()).isEqualTo("cyber");
        mvc.perform(get("/api/projects").param("q", "film").with(user(alice)))
            .andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get("/api/projects").with(user(bob))).andExpect(content().json("[]"));
    }

    @Test void projectStyleCanChangeBeforeFirstImageAndFailedTasksCannotReplayOldStyle() throws Exception {
        var project = project("realistic", "换风格");
        assertThat(project.path("styleEditable").asBoolean()).isTrue();
        String path = "/api/projects/" + id(project);
        long episode = id(postJson(path + "/episodes", Map.of(), 201));
        long shot = coverShot(id(project), episode, 1);
        long scene = db.fetchOne("insert into scene_asset(user_id, project_id, name) values (?, ?, '雨巷') returning id",
            alice.id(), id(project)).get(0, Long.class);
        String shotImages = "/api/shots/" + shot + "/image-tasks";
        String sceneImages = "/api/assets/scenes/" + scene + "/image-tasks";
        var shotTask = postJson(shotImages, Map.of(), 202).path("taskId").asText();
        var sceneTask = postJson(sceneImages, Map.of(), 202).path("taskId").asText();

        mvc.perform(get(path).with(user(alice))).andExpect(jsonPath("$.styleEditable").value(false));
        mvc.perform(patch(path).with(user(alice)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"style\":\"冷色调\"}")).andExpect(status().isConflict());

        db.execute("update task set status='FAILED', retryable=true where project_id=?", id(project));
        var changed = patchJson(path, Map.of("visualType", "cyber", "style", "冷色调"));
        assertThat(changed.path("styleEditable").asBoolean()).isTrue();
        assertThat(changed.at("/visualPreset/id").asText()).isEqualTo("cyber");
        for (String task : List.of(shotTask, sceneTask)) postJson("/api/tasks/" + task + "/retry", Map.of(), 409);
        mvc.perform(get("/api/shots/" + shot).with(user(alice))).andExpect(jsonPath("$.imageState").value("idle"));
        mvc.perform(get(path + "/scenes").with(user(alice))).andExpect(jsonPath("$[0].generationState").value("idle"));

        for (String imagePath : List.of(shotImages, sceneImages)) {
            long task = postJson(imagePath, Map.of(), 202).path("taskId").asLong();
            assertThat(db.fetchOne("select input_snapshot->>'prompt' from task where id=?", task).get(0, String.class))
                .contains("赛博朋克", "冷色调");
        }
    }

    @Test void projectStyleLocksOnGeneratedAssetsAndLegacyStoryboardFrames() throws Exception {
        long project = id(project("realistic", "首图锁定风格"));
        String path = "/api/projects/" + project;
        long character = db.fetchOne("insert into character_asset(user_id, project_id, name) values (?, ?, '沈夜') returning id",
            alice.id(), project).get(0, Long.class);
        db.execute("""
            insert into asset_version(user_id, project_id, character_id, source, label, storage_key, media_type)
            values (?, ?, ?, 'uploaded', '参考', 'mock/reference', 'image/png')
            """, alice.id(), project, character);
        assertThat(patchJson(path, Map.of("visualType", "anime")).path("styleEditable").asBoolean()).isTrue();
        db.execute("update asset_version set source='generated' where character_id=?", character);
        mvc.perform(get(path).with(user(alice))).andExpect(jsonPath("$.styleEditable").value(false));
        mvc.perform(patch(path).with(user(alice)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"visualType\":\"cyber\"}")).andExpect(status().isConflict());
        assertThat(patchJson(path, Map.of("name", "改名")).path("name").asText()).isEqualTo("改名");

        long otherProject = id(project("realistic", "历史分镜"));
        String otherPath = "/api/projects/" + otherProject;
        long episode = id(postJson(otherPath + "/episodes", Map.of(), 201));
        coverImage(coverShot(otherProject, episode, 1), episode, false, null);
        mvc.perform(get(otherPath).with(user(alice))).andExpect(jsonPath("$.styleEditable").value(false));
        mvc.perform(patch(otherPath).with(user(alice)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"style\":\"冷色调\"}")).andExpect(status().isConflict());
    }

    @Test void threeEpisodesPersistIndependentTitlesSummariesAndExactStoriesInProjectRead() throws Exception {
        long project = id(project("realistic", "三集"));
        for (int i = 1; i <= 3; i++) {
            var episode = postJson("/api/projects/" + project + "/episodes", Map.of(), 201);
            assertThat(episode.get("title").asText()).isEqualTo("第 " + i + " 集");
            long id = id(episode);
            patchJson("/api/episodes/" + id, Map.of("title", "集 " + i, "summary", "梗概 " + i));
            putStory(id, "  第 " + i + " 集\r\n\n雨夜。🙂\t尾声  ", 200);
        }
        var read = body(mvc.perform(get("/api/projects/" + project).with(user(alice))).andExpect(status().isOk()).andReturn());
        assertThat(read.get("episodes").size()).isEqualTo(3);
        assertThat(read.at("/stats/episodeCount").asInt()).isEqualTo(3);
        for (int i = 1; i <= 3; i++) {
            var episode = read.get("episodes").get(i - 1);
            assertThat(episode.get("index").asInt()).isEqualTo(i);
            assertThat(episode.get("summary").asText()).isEqualTo("梗概 " + i);
            assertThat(episode.get("story").asText()).isEqualTo("  第 " + i + " 集\r\n\n雨夜。🙂\t尾声  ");
        }
    }

    @Test void projectCoverUsesTheFirstStoryboardShotWithMediaAndPrefersItsSelectedImage() throws Exception {
        long project = id(project("realistic", "项目封面"));
        String path = "/api/projects/" + project;
        mvc.perform(get(path).with(user(alice))).andExpect(status().isOk()).andExpect(jsonPath("$.coverImageUrl").doesNotExist());
        long firstEpisode = id(postJson(path + "/episodes", Map.of(), 201));
        long secondEpisode = id(postJson(path + "/episodes", Map.of(), 201));
        long laterShot = coverShot(project, secondEpisode, 1);
        coverImage(laterShot, secondEpisode, true, "image/png");
        long firstShot = coverShot(project, firstEpisode, 1);
        coverImage(firstShot, firstEpisode, true, null);
        long secondShot = coverShot(project, firstEpisode, 2);
        long firstCandidate = coverImage(secondShot, firstEpisode, false, "image/png");
        long selected = coverImage(secondShot, firstEpisode, true, "image/png");
        mvc.perform(get("/api/projects").with(user(alice))).andExpect(status().isOk())
            .andExpect(jsonPath("$[0].coverImageUrl").value("/api/shot-images/" + selected + "/image"));

        db.execute("update shot_image set selected = false where id = ?", selected);
        mvc.perform(get(path).with(user(alice))).andExpect(status().isOk())
            .andExpect(jsonPath("$.coverImageUrl").value("/api/shot-images/" + firstCandidate + "/image"));
        mvc.perform(get(path).with(user(bob))).andExpect(status().isNotFound());
    }

    private long coverShot(long project, long episode, int seq) {
        return db.fetchOne("insert into shot(user_id, project_id, episode_id, seq) values (?, ?, ?, ?) returning id",
            alice.id(), project, episode, seq).get(0, Long.class);
    }

    private long coverImage(long shot, long episode, boolean selected, String mediaType) {
        return db.fetchOne("""
            insert into shot_image(shot_id, episode_id, selected, media_type, storage_key)
            values (?, ?, ?, ?, 'mock/cover') returning id
            """, shot, episode, selected, mediaType).get(0, Long.class);
    }

    @Test void storyChangeResetsOnlyDoneAndPreservesShotsImagesVideosAndHistory() throws Exception {
        var fixture = fixture();
        putStory(fixture.episode(), "原文", 200);
        db.execute("update episode set breakdown_state = 'DONE' where id = ?", fixture.episode());
        putStory(fixture.episode(), "原文", 200).andExpect(jsonPath("$.breakdown").value("done"));
        var lease = store.claim("source").orElseThrow();
        store.succeed(lease, lease.version(), new VideoProvider.Result(fixture.key(), 3000));
        putStory(fixture.episode(), "新正文\n第二行", 200).andExpect(jsonPath("$.breakdown").value("idle"));
        assertThat(count("shot")).isEqualTo(1);
        assertThat(count("shot_image")).isEqualTo(1);
        assertThat(count("video")).isEqualTo(1);
        assertThat(tasks.get(alice.id(), fixture.task()).basedOn()).isEqualTo(Long.toString(fixture.image()));
        db.execute("update episode set breakdown_state = 'RUNNING' where id = ?", fixture.episode());
        putStory(fixture.episode(), "", 200).andExpect(jsonPath("$.breakdown").value("running"));
    }

    @Test void storyRejectsOversizeInvalidUnicodeNulAndMalformedUtf8WithoutWriting() throws Exception {
        long project = id(project("realistic", "校验"));
        long episode = id(postJson("/api/projects/" + project + "/episodes", Map.of(), 201));
        putStory(episode, "保留原文", 200);
        putStory(episode, "中".repeat(349526), 400);
        putStory(episode, "bad\0text", 400);
        mvc.perform(put("/api/episodes/" + episode + "/story").with(user(alice)).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content("{\"story\":\"\\ud800\"}"))
            .andExpect(status().isBadRequest());
        mvc.perform(put("/api/episodes/" + episode + "/story").with(user(alice)).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(new byte[]{123,34,115,116,111,114,121,34,58,34,(byte)255,34,125}))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/episodes/" + episode).with(user(alice)))
            .andExpect(jsonPath("$.story").value("保留原文"));
    }

    @Test void episodePartialEditsAndDeletingMiddleEpisodeKeepOtherIndexesStable() throws Exception {
        long project = id(project("realistic", "序号"));
        var first = postJson("/api/projects/" + project + "/episodes", Map.of(), 201);
        var second = postJson("/api/projects/" + project + "/episodes", Map.of(), 201);
        postJson("/api/projects/" + project + "/episodes", Map.of(), 201);
        patchJson("/api/episodes/" + id(first), Map.of("summary", "梗概"));
        var changed = patchJson("/api/episodes/" + id(first), Map.of("title", "新集名"));
        assertThat(changed.get("summary").asText()).isEqualTo("梗概");
        mvc.perform(delete("/api/episodes/" + id(second)).with(user(alice)).with(csrf())).andExpect(status().isNoContent());
        var fourth = postJson("/api/projects/" + project + "/episodes", Map.of(), 201);
        assertThat(fourth.get("index").asInt()).isEqualTo(4);
        assertThat(db.fetch("select episode_index from episode order by episode_index").getValues(0, Integer.class))
            .containsExactly(1, 3, 4);
        mvc.perform(patch("/api/episodes/" + id(first)).with(user(alice)).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"  \"}")).andExpect(status().isBadRequest());
    }

    @Test void newProjectEpisodeAndStoryEndpointsReturn404ForOtherUsers() throws Exception {
        long project = id(project("realistic", "私有"));
        long episode = id(postJson("/api/projects/" + project + "/episodes", Map.of(), 201));
        mvc.perform(patch("/api/projects/" + project).with(user(bob)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"bad\"}")).andExpect(status().isNotFound());
        mvc.perform(delete("/api/projects/" + project).with(user(bob)).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(get("/api/projects/" + project + "/overview").with(user(bob))).andExpect(status().isNotFound());
        mvc.perform(get("/api/episodes/" + episode).with(user(bob))).andExpect(status().isNotFound());
        mvc.perform(patch("/api/episodes/" + episode).with(user(bob)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"summary\":\"bad\"}")).andExpect(status().isNotFound());
        mvc.perform(put("/api/episodes/" + episode + "/story").with(user(bob)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content("{\"story\":\"bad\"}")).andExpect(status().isNotFound());
        mvc.perform(delete("/api/episodes/" + episode).with(user(bob)).with(csrf())).andExpect(status().isNotFound());
    }

    @Test void customPngAndJpegAreStoredResizedResolvedAndScoped() throws Exception {
        var uploadedIds = new java.util.ArrayList<String>();
        for (String format : List.of("png", "jpg")) {
            var style = upload(format, 800, 400);
            String id = style.get("id").asText();
            uploadedIds.add(id);
            var project = project(id, "自定义 " + format);
            var read = body(mvc.perform(get("/api/projects/" + id(project)).with(user(alice))).andExpect(status().isOk()).andReturn());
            assertThat(read.get("visualType").asText()).isEqualTo(id);
            assertThat(read.at("/visualPreset/label").asText()).isEqualTo("参考 " + format);
            byte[] bytes = mvc.perform(get(style.get("image").asText()).with(user(alice))).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "private, no-store")).andReturn().getResponse().getContentAsByteArray();
            var image = ImageIO.read(new java.io.ByteArrayInputStream(bytes));
            assertThat(image.getWidth()).isEqualTo(480);
            assertThat(image.getHeight()).isEqualTo(240);
            mvc.perform(get(style.get("image").asText()).with(user(bob))).andExpect(status().isNotFound());
            mvc.perform(post("/api/projects").with(user(bob)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("name", "bad", "visualType", id)))).andExpect(status().isNotFound());
        }
        var styles = body(mvc.perform(get("/api/visual-styles").with(user(alice))).andReturn());
        var otherStyles = body(mvc.perform(get("/api/visual-styles").with(user(bob))).andReturn());
        assertThat(styles).filteredOn(style -> style.path("custom").asBoolean())
            .extracting(style -> style.path("id").asText()).containsExactlyInAnyOrderElementsOf(uploadedIds);
        assertThat(otherStyles).noneMatch(style -> style.path("custom").asBoolean());
    }

    @Test void customStyleUploadChecksMimeContentSizeAndDimensions() throws Exception {
        uploadFailure(new MockMultipartFile("image", "fake.png", "image/png", "not an image".getBytes()), 400);
        uploadFailure(new MockMultipartFile("image", "wrong.jpg", "image/jpeg", image("png", 2, 2)), 400);
        uploadFailure(new MockMultipartFile("image", "fake.svg", "image/svg+xml", "<svg/>".getBytes()), 415);
        uploadFailure(new MockMultipartFile("image", "large.png", "image/png", new byte[5 * 1024 * 1024 + 1]), 400);
        uploadFailure(new MockMultipartFile("image", "wide.png", "image/png", image("png", 4097, 1)), 400);
        assertThat(count("custom_style")).isZero();
    }

    @Test void deletingReferencedStyleHidesChooserEntryButPreservesProjectsUntilLastReferenceReleased() throws Exception {
        var style = upload("png", 2, 2);
        String id = style.get("id").asText();
        long first = id(project(id, "引用一"));
        long second = id(project(id, "引用二"));
        mvc.perform(delete("/api/visual-styles/" + id).with(user(alice)).with(csrf())).andExpect(status().isNoContent());
        var styles = body(mvc.perform(get("/api/visual-styles").with(user(alice))).andReturn());
        assertThat(styles).noneMatch(entry -> entry.path("id").asText().equals(id));
        mvc.perform(get("/api/projects/" + first).with(user(alice))).andExpect(jsonPath("$.visualPreset.id").value(id));
        mvc.perform(get(style.get("image").asText()).with(user(alice))).andExpect(status().isOk());
        mvc.perform(post("/api/projects").with(user(alice)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("name", "new", "visualType", id)))).andExpect(status().isNotFound());
        patchJson("/api/projects/" + first, Map.of("name", "改名仍可读"));
        patchJson("/api/projects/" + first, Map.of("visualType", "realistic"));
        cleanup.sweep();
        mvc.perform(get(style.get("image").asText()).with(user(alice))).andExpect(status().isOk());
        mvc.perform(delete("/api/projects/" + second).with(user(alice)).with(csrf())).andExpect(status().isNoContent());
        cleanup.sweep();
        mvc.perform(get(style.get("image").asText()).with(user(alice))).andExpect(status().isNotFound());
        assertThat(Files.exists(storage.path("u/" + alice.id() + "/styles/" + id))).isFalse();
    }

    @Test void anotherUserCannotReadDeleteOrResolvePrivateStyleAndBuiltInsAreNotDeletable() throws Exception {
        var style = upload("png", 2, 2);
        String id = style.get("id").asText();
        mvc.perform(get("/api/visual-styles/" + id).with(user(bob))).andExpect(status().isNotFound());
        mvc.perform(delete("/api/visual-styles/" + id).with(user(bob)).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(delete("/api/visual-styles/realistic").with(user(alice)).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(delete("/api/visual-styles/" + id).with(user(alice)).with(csrf())).andExpect(status().isNoContent());
        cleanup.sweep();
        assertThat(Files.exists(storage.path("u/" + alice.id() + "/styles/" + id))).isFalse();
    }

    @Test void twoProjectsCanSwapCustomStylesConcurrentlyWithoutOppositeLockOrder() throws Exception {
        String firstStyle = upload("png", 2, 2).get("id").asText();
        String secondStyle = upload("jpg", 2, 2).get("id").asText();
        long first = id(project(firstStyle, "一"));
        long second = id(project(secondStyle, "二"));
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var firstChange = pool.submit(() -> projects.update(alice.id(), first, null, secondStyle, null));
            var secondChange = pool.submit(() -> projects.update(alice.id(), second, null, firstStyle, null));
            assertThat(firstChange.get(5, TimeUnit.SECONDS).visualType()).isEqualTo(secondStyle);
            assertThat(secondChange.get(5, TimeUnit.SECONDS).visualType()).isEqualTo(firstStyle);
        }
    }

    @Test void overviewCountsShotsOnceAcrossCandidatesVideoVersionsAndDifferentProjects() throws Exception {
        var fixture = fixture();
        var first = store.claim("stats-one").orElseThrow();
        store.succeed(first, first.version(), new VideoProvider.Result(fixture.key(), 3000));
        tasks.submit(alice.id(), fixture.shot(), "second-video");
        var second = store.claim("stats-two").orElseThrow();
        store.succeed(second, second.version(), new VideoProvider.Result(fixture.key() + ".second", 3000));
        mvc.perform(get("/api/episodes/" + fixture.episode() + "/shots").with(user(alice)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].video.candidates.length()").value(2))
            .andExpect(jsonPath("$[0].video.candidates[0].basedOn").value(Long.toString(fixture.image())))
            .andExpect(jsonPath("$[0].video.candidates[1].basedOn").value(Long.toString(fixture.image())))
            .andExpect(jsonPath("$[0].video.candidates[0].url").value("/api/tasks/" + second.taskId() + "/video"))
            .andExpect(jsonPath("$[0].video.candidates[1].url").value("/api/tasks/" + first.taskId() + "/video"));
        db.execute("insert into shot_image(shot_id, episode_id, selected, storage_key) values (?, ?, false, 'mock/other')", fixture.shot(), fixture.episode());
        db.execute("insert into shot(user_id, project_id, episode_id, seq) values (?, ?, ?, 2)", alice.id(), fixture.project(), fixture.episode());
        project("anime", "不应计入");
        mvc.perform(get("/api/projects/" + fixture.project() + "/overview").with(user(alice)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.episodeCount").value(1))
            .andExpect(jsonPath("$.shotCount").value(2)).andExpect(jsonPath("$.confirmedCount").value(1))
            .andExpect(jsonPath("$.imageCount").value(1)).andExpect(jsonPath("$.videoCount").value(1));
        db.execute("update shot_image set selected = false where id = ?", fixture.image());
        mvc.perform(get("/api/episodes/" + fixture.episode()).with(user(alice)))
            .andExpect(jsonPath("$.stats.imageCount").value(0)).andExpect(jsonPath("$.stats.videoCount").value(1));
    }

    @Test void videoRegenerationFreezesReviewedFrameAndEditedPromptWithoutChangingTheShot() throws Exception {
        var fixture = fixture();
        var first = store.claim("original-video").orElseThrow();
        assertThat(store.succeed(first, first.version(), new VideoProvider.Result(fixture.key(), 3000))).isTrue();
        String shotPath = "/api/shots/" + fixture.shot();
        patchJson(shotPath, Map.of("description", "原分镜描述"));
        long frame = db.fetchOne("""
            insert into shot_image(shot_id, episode_id, version, selected, storage_key)
            values (?, ?, 2, false, 'mock/next-frame') returning id
            """, fixture.shot(), fixture.episode()).get(0, Long.class);
        db.execute("update shot_image set selected=false where id=?", fixture.image());
        db.execute("update shot_image set selected=true where id=?", frame);

        String path = shotPath + "/video-tasks";
        String prompt = "人物停下，镜头缓慢向前推。";
        var request = Map.of("regenerateNonce", "reviewed-video", "expectedFirstFrameId", Long.toString(frame), "prompt", prompt);
        postJson(path, Map.of("regenerateNonce", "stale-frame", "expectedFirstFrameId", Long.toString(fixture.image()), "prompt", prompt), 409);
        postJson(path, Map.of("regenerateNonce", "blank-prompt", "expectedFirstFrameId", Long.toString(frame), "prompt", " 　"), 400);
        assertThat(count("task")).isEqualTo(1);

        var result = postJson(path, request, 202);
        long task = result.get("taskId").asLong();
        var snapshot = tasks.input(db.fetchOne("select input_snapshot from task where id=?", task).get(0, String.class));
        assertThat(snapshot.prompt()).isEqualTo(prompt);
        assertThat(snapshot.sourceImageId()).isEqualTo(frame);
        mvc.perform(get(shotPath).with(user(alice)).with(csrf())).andExpect(status().isOk())
            .andExpect(jsonPath("$.description").value("原分镜描述"))
            .andExpect(jsonPath("$.video.basedOn").value(Long.toString(fixture.image())))
            .andExpect(jsonPath("$.video.pendingBasedOn").value(Long.toString(frame)))
            .andExpect(jsonPath("$.video.candidates.length()").value(1));
        var lease = store.claim("regenerated-video").orElseThrow();
        assertThat(store.succeed(lease, lease.version(), new VideoProvider.Result(fixture.key() + ".second", 3000))).isTrue();
        assertThat(postJson(path, request, 202).get("taskId").asLong()).isEqualTo(task);
        assertThat(count("task")).isEqualTo(2);
        mvc.perform(get(shotPath).with(user(alice))).andExpect(status().isOk())
            .andExpect(jsonPath("$.video.prompt").value(prompt))
            .andExpect(jsonPath("$.video.basedOn").value(Long.toString(frame)))
            .andExpect(jsonPath("$.video.candidates[0].prompt").value(prompt))
            .andExpect(jsonPath("$.video.candidates[0].basedOn").value(Long.toString(frame)))
            .andExpect(jsonPath("$.video.candidates[1].basedOn").value(Long.toString(fixture.image())));
    }

    @Test void projectDeletionCascadesAndRejectsOldWorkerThenDurablyCleansLateMedia() throws Exception {
        var fixture = fixture();
        var lease = store.claim("deleted-scope").orElseThrow();
        String sibling = "u/" + alice.id() + "/p/" + fixture.project() + "0/keep.mp4";
        storage.put(fixture.key(), new byte[]{1, 2, 3});
        storage.put(sibling, new byte[]{4});
        mvc.perform(delete("/api/projects/" + fixture.project()).with(user(alice)).with(csrf())).andExpect(status().isNoContent());
        assertThat(count("episode")).isZero();
        assertThat(count("shot")).isZero();
        assertThat(count("shot_image")).isZero();
        assertThat(count("task")).isZero();
        assertThat(count("task_attempt")).isZero();
        assertThat(store.succeed(lease, lease.version(), new VideoProvider.Result(fixture.key(), 3000))).isFalse();
        assertThat(store.heartbeat(lease, lease.version())).isEmpty();
        cleanup.sweep();
        assertThat(Files.exists(storage.path(fixture.key()))).isFalse();
        assertThat(Files.exists(storage.path(sibling))).isTrue();
        storage.put(fixture.key(), new byte[]{9}); // Simulate a stale publisher after the first sweep.
        cleanup.sweep();
        assertThat(Files.exists(storage.path(fixture.key()))).isFalse();
        assertThat(count("storage_cleanup")).isEqualTo(1);
    }

    @Test void deletingEpisodeCleansItsCompletedVideoWithoutRenumberingSibling() throws Exception {
        var fixture = fixture();
        var lease = store.claim("completed").orElseThrow();
        store.succeed(lease, lease.version(), new VideoProvider.Result(fixture.key(), 3000));
        storage.put(fixture.key(), new byte[]{1});
        var sibling = postJson("/api/projects/" + fixture.project() + "/episodes", Map.of(), 201);
        mvc.perform(delete("/api/episodes/" + fixture.episode()).with(user(alice)).with(csrf())).andExpect(status().isNoContent());
        assertThat(count("video")).isZero();
        assertThat(count("task")).isZero();
        cleanup.sweep();
        assertThat(Files.exists(storage.path(fixture.key()))).isFalse();
        mvc.perform(get("/api/episodes/" + id(sibling)).with(user(alice))).andExpect(jsonPath("$.index").value(2));
    }

    @Test void rolledBackDeletionDoesNotEraseRowsOrEnqueueMediaCleanup() throws Exception {
        var fixture = fixture();
        storage.put(fixture.key(), new byte[]{1});
        new TransactionTemplate(transactions).executeWithoutResult(transaction -> {
            projects.delete(alice.id(), fixture.project());
            transaction.setRollbackOnly();
        });
        assertThat(count("project")).isEqualTo(1);
        assertThat(count("task")).isEqualTo(1);
        assertThat(count("storage_cleanup")).isZero();
        cleanup.sweep();
        assertThat(Files.exists(storage.path(fixture.key()))).isTrue();
    }

    @Test void completionWaitingOnDeletionCannotResurrectRemovedProject() throws Exception {
        var fixture = fixture();
        var lease = store.claim("waiting-worker").orElseThrow();
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var ready = new CountDownLatch(1);
            var completion = new AtomicReference<Future<Boolean>>();
            new TransactionTemplate(transactions).executeWithoutResult(transaction -> {
                scope.lockProject(alice.id(), fixture.project());
                completion.set(pool.submit(() -> {
                    ready.countDown();
                    return store.succeed(lease, lease.version(), new VideoProvider.Result(fixture.key(), 3000));
                }));
                try { assertThat(ready.await(2, TimeUnit.SECONDS)).isTrue(); }
                catch (InterruptedException error) { throw new IllegalStateException(error); }
                projects.delete(alice.id(), fixture.project());
            });
            assertThat(completion.get().get(3, TimeUnit.SECONDS)).isFalse();
        }
        assertThat(count("video")).isZero();
    }

    @Test void phaseTwoEndpointsAndUploadTypesAreInOpenApi() throws Exception {
        mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
            .andExpect(jsonPath("$.paths['/api/projects/{id}'].patch").exists())
            .andExpect(jsonPath("$.paths['/api/projects'].post.responses['201']").exists())
            .andExpect(jsonPath("$.paths['/api/shots/{shotId}/video-tasks'].post.responses['202']").exists())
            .andExpect(jsonPath("$.paths['/api/episodes/{episodeId}/story'].put").exists())
            .andExpect(jsonPath("$.paths['/api/visual-styles'].post.requestBody.content['multipart/form-data']").exists())
            .andExpect(jsonPath("$.components.schemas.Project.properties.episodes").exists())
            .andExpect(jsonPath("$.components.schemas.Episode.properties.stats").exists());
    }

    private record Fixture(long project, long episode, long shot, long image, long task, String key) {}
    private Fixture fixture() throws Exception {
        long project = id(project("realistic", "媒体项目"));
        long episode = id(postJson("/api/projects/" + project + "/episodes", Map.of(), 201));
        long shot = db.fetchOne("insert into shot(user_id, project_id, episode_id, seq, status) values (?, ?, ?, 1, 'CONFIRMED') returning id",
            alice.id(), project, episode).get(0, Long.class);
        long image = db.fetchOne("insert into shot_image(shot_id, episode_id, selected, storage_key) values (?, ?, true, 'mock/frame') returning id",
            shot, episode).get(0, Long.class);
        long task = Long.parseLong(tasks.submit(alice.id(), shot, "fixture").taskId());
        return new Fixture(project, episode, shot, image, task, "u/" + alice.id() + "/p/" + project + "/e/" + episode + "/video/fixture.mp4");
    }
    private JsonNode project(String visualType, String name) throws Exception {
        return postJson("/api/projects", Map.of("name", name, "visualType", visualType, "style", "风格描述"), 201);
    }
    private JsonNode postJson(String path, Object input, int expected) throws Exception {
        return body(mvc.perform(post(path).with(user(alice)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsBytes(input))).andExpect(status().is(expected)).andReturn());
    }
    private JsonNode patchJson(String path, Object input) throws Exception {
        return body(mvc.perform(patch(path).with(user(alice)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsBytes(input))).andExpect(status().isOk()).andReturn());
    }
    private org.springframework.test.web.servlet.ResultActions putStory(long episode, String story, int expected) throws Exception {
        return mvc.perform(put("/api/episodes/" + episode + "/story").with(user(alice)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsBytes(Map.of("story", story)))).andExpect(status().is(expected));
    }
    private JsonNode upload(String format, int width, int height) throws Exception {
        return body(mvc.perform(multipart("/api/visual-styles").file(new MockMultipartFile("image", "reference." + format,
            format.equals("png") ? "image/png" : "image/jpeg", image(format, width, height)))
            .param("label", "参考 " + format).with(user(alice)).with(csrf())).andExpect(status().isCreated()).andReturn());
    }
    private void uploadFailure(MockMultipartFile file, int expected) throws Exception {
        mvc.perform(multipart("/api/visual-styles").file(file).param("label", "参考").with(user(alice)).with(csrf()))
            .andExpect(status().is(expected));
    }
    private byte[] image(String format, int width, int height) throws Exception {
        var out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), format, out);
        return out.toByteArray();
    }
    private Viewer account(String email) {
        var saved = users.saveAndFlush(new User(email, passwords.encode("test-password")));
        return new Viewer(saved.getId(), email, saved.getPasswordHash());
    }
    private JsonNode body(MvcResult result) throws Exception { return json.readTree(result.getResponse().getContentAsByteArray()); }
    private long id(JsonNode node) { return node.get("id").asLong(); }
    private int count(String table) { return db.fetchOne("select count(*) from " + table).get(0, Integer.class); }
}
