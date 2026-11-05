package com.vibestory.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vibestory.auth.Viewer;
import com.vibestory.common.ApiException;
import com.vibestory.experience.ImageCreationService;
import com.vibestory.experience.ImageExperience;
import com.vibestory.experience.ImageExperience.*;
import com.vibestory.image.ImageTaskService;
import com.vibestory.provider.MockImageProvider;
import com.vibestory.provider.ImageProvider;
import com.vibestory.provider.ImageProviders;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import static org.mockito.Mockito.doReturn;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"vibestory.worker.enabled=false", "vibestory.mock.delay=0ms", "vibestory.mock-only=true"})
@AutoConfigureMockMvc
class ImageExperienceIntegrationTest extends PostgresSupport {
    @Autowired DSLContext db;
    @Autowired ObjectMapper json;
    @Autowired ImageTaskService images;
    @Autowired ImageCreationService creation;
    @Autowired ImageExperience experience;
    @Autowired TaskStore store;
    @Autowired MockImageProvider mock;
    @Autowired MockMvc mvc;
    @MockitoSpyBean ImageProviders providers;
    Viewer owner, other;
    long project, episode, shot, second;

    @BeforeEach void setup() {
        db.execute("truncate app_user,storage_cleanup restart identity cascade");
        owner = account("learning@image.test");
        other = account("other@image.test");
        project = project("雨夜");
        episode = episode(project);
        shot = shot(project, episode, 1, "沈夜走进雨巷");
        second = shot(project, episode, 2, "纸条落在地上");
    }

    @Test void correctionRetainsEvidenceAndRequiresExplicitSatisfactionBeforeRemembering() throws Exception {
        long original = Long.parseLong(images.submitShot(owner.id(), shot, null).taskId());
        finish();
        var made = create(shot, original, "脸自然一点，服装低饱和");
        assertThat(made.sourceUrl()).isNotNull();
        assertThat(made.learning().sourceTaskId()).isEqualTo(Long.toString(original));
        assertThat(made.prompt()).contains("沈夜走进雨巷", "脸自然一点");
        assertThatThrownBy(() -> experience.decide(owner.id(), made.id(), Decision.ACCEPTED)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> experience.remember(owner.id(), made.id(), new Remember(Scope.USER, "自然面容"))).isInstanceOf(ApiException.class);
        finish();
        experience.decide(owner.id(), made.id(), Decision.ACCEPTED);
        var preference = experience.remember(owner.id(), made.id(), new Remember(Scope.PROJECT, "人物面容自然，服装低饱和"));
        assertThat(experience.remember(owner.id(), made.id(), new Remember(Scope.PROJECT, preference.rule())).id()).isEqualTo(preference.id());
        long next = Long.parseLong(images.submitShot(owner.id(), second, "after-feedback").taskId());
        assertThat(snapshot(next).learning().preferences()).hasSize(1);
        assertThat(snapshot(next).prompt()).contains(preference.rule());
        assertThat(snapshot(original).learning()).isNull();
        assertThat(experience.context(owner.id(), TargetType.SHOT, shot).creations().getFirst().decision()).isEqualTo("ACCEPTED");
        mvc.perform(get("/api/image-experience/targets/SHOT/" + shot).with(user(owner))).andExpect(status().isOk())
            .andExpect(jsonPath("$.creations[0].sourceTaskId").value(Long.toString(original)))
            .andExpect(jsonPath("$.creations[0].resultUrl").isNotEmpty());
    }

    @Test void targetProjectAndPersonalPreferencesHaveDifferentReachAndNeverEnterTeamMedia() throws Exception {
        var accepted = accepted();
        experience.remember(owner.id(), accepted.id(), new Remember(Scope.TARGET, "只此镜头的红伞"));
        experience.remember(owner.id(), accepted.id(), new Remember(Scope.PROJECT, "本项目冷色调"));
        experience.remember(owner.id(), accepted.id(), new Remember(Scope.USER, "个人偏好柔光"));
        var local = snapshot(Long.parseLong(images.submitShot(owner.id(), shot, "local").taskId()));
        assertThat(local.prompt()).contains("红伞", "冷色调", "柔光");
        var sibling = snapshot(Long.parseLong(images.submitShot(owner.id(), second, "sibling").taskId()));
        assertThat(sibling.prompt()).contains("冷色调", "柔光").doesNotContain("红伞");
        long another = project("夏日");
        long destination = shot(another, episode(another), 1, "许星遥站在花园");
        assertThat(snapshot(Long.parseLong(images.submitShot(owner.id(), destination, "other-project").taskId())).prompt())
            .contains("柔光").doesNotContain("红伞", "冷色调");
        long character = db.fetchOne("insert into character_asset(user_id,project_id,name) values (?,?,'许星遥') returning id", owner.id(), another).get(0, Long.class);
        assertThat(snapshot(Long.parseLong(images.submitAsset(owner.id(), true, character, "character").taskId())).learning()).isNull();
        long team = db.fetchOne("insert into team(name) values ('协作') returning id").get(0, Long.class);
        db.execute("update project set team_id=? where id=?", team, another);
        assertThat(snapshot(Long.parseLong(images.submitShot(owner.id(), destination, "team").taskId())).learning()).isNull();
        assertThatThrownBy(() -> experience.context(owner.id(), TargetType.SHOT, destination)).isInstanceOf(ApiException.class);
    }

    @Test void concurrentReplayAndReplayAfterCompletionKeepOneCreationAndOneTask() throws Exception {
        var request = new ImageCreationService.Request(UUID.randomUUID(), TargetType.SHOT, shot, null, "低饱和", null, Map.of());
        Creation first;
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var one = pool.submit(() -> creation.create(owner.id(), request));
            var two = pool.submit(() -> creation.create(owner.id(), request));
            first = one.get(10, TimeUnit.SECONDS);
            assertThat(two.get(10, TimeUnit.SECONDS).id()).isEqualTo(first.id());
        }
        finish();
        assertThat(creation.create(owner.id(), request).taskId()).isEqualTo(first.taskId());
        assertThat(db.fetchCount(db.selectFrom("task"))).isEqualTo(1);
        assertThat(db.fetchCount(db.selectFrom("image_creation"))).isEqualTo(1);
        assertThatThrownBy(() -> creation.create(owner.id(), new ImageCreationService.Request(request.requestKey(), TargetType.SHOT, shot,
            null, "完全不同要求", null, Map.of()))).isInstanceOf(ApiException.class);
        experience.decide(owner.id(), first.id(), Decision.REJECTED);
        experience.decide(owner.id(), first.id(), Decision.REJECTED);
        assertThatThrownBy(() -> experience.decide(owner.id(), first.id(), Decision.ACCEPTED)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> experience.remember(owner.id(), first.id(), new Remember(Scope.USER, "未采纳"))).isInstanceOf(ApiException.class);
    }

    @Test void recipeVersionsBindDestinationAssetsAndPreserveHistoricalParameters() throws Exception {
        var origin = accepted();
        var original = new SaveRecipe(UUID.randomUUID(), null, 0, TargetType.SHOT, "柔光人物", "人物自然，光线 {{lighting}}", origin.id());
        var saved = experience.saveRecipe(owner.id(), original);
        var v1 = saved.versions().getFirst();
        long another = project("目的项目");
        long destination = shot(another, episode(another), 1, "许星遥在夏日花园");
        var made = creation.create(owner.id(), new ImageCreationService.Request(UUID.randomUUID(), TargetType.SHOT, destination, null,
            "生成花园人物", v1.id(), Map.of("lighting", "清晨柔光")));
        assertThat(made.prompt()).contains("许星遥在夏日花园", "光线 清晨柔光").doesNotContain("沈夜", "雨巷");
        assertThat(made.learning().recipe().version()).isEqualTo(1);
        assertThat(made.learning().recipe().parameters()).containsEntry("lighting", "清晨柔光");
        var update = new SaveRecipe(UUID.randomUUID(), saved.id(), 1, TargetType.SHOT, saved.name(), "人物自然，避免浓妆，光线 {{lighting}}", origin.id());
        assertThat(experience.saveRecipe(owner.id(), update).version()).isEqualTo(2);
        assertThat(experience.saveRecipe(owner.id(), update).versions()).hasSize(2);
        assertThat(experience.saveRecipe(owner.id(), original).version()).isEqualTo(1);
        assertThat(snapshot(Long.parseLong(made.taskId())).learning().recipe().template()).isEqualTo(v1.template());
        assertThatThrownBy(() -> experience.saveRecipe(owner.id(), new SaveRecipe(UUID.randomUUID(), saved.id(), 1, TargetType.SHOT,
            saved.name(), "旧版本覆盖", origin.id()))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> creation.create(owner.id(), new ImageCreationService.Request(UUID.randomUUID(), TargetType.SHOT, destination, null,
            "缺参数", v1.id(), Map.of()))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> creation.create(owner.id(), new ImageCreationService.Request(UUID.randomUUID(), TargetType.SHOT, destination, null,
            "多参数", v1.id(), Map.of("lighting", "清晨", "extra", "额外")))).isInstanceOf(ApiException.class);
    }

    @Test void correctionUsesTheOriginalSnapshotEvenWhenCurrentDescriptionExceedsTheProviderLimit() throws Exception {
        doReturn(new ImageProvider.Capabilities(false, false, 1, false, 300)).when(providers).capabilities("mock");
        long original = Long.parseLong(images.submitShot(owner.id(), shot, null).taskId());
        finish();
        db.execute("update shot set description=? where id=?", "新描述".repeat(200), shot);
        var corrected = create(shot, original, "光线柔和一点");
        assertThat(corrected.prompt()).contains("沈夜走进雨巷", "光线柔和一点").doesNotContain("新描述");
    }

    @Test void preferenceEditingAndDisablingLeaveSubmittedSnapshotsUntouched() throws Exception {
        var origin = accepted();
        var preference = experience.remember(owner.id(), origin.id(), new Remember(Scope.PROJECT, "不要浓妆"));
        long before = Long.parseLong(images.submitShot(owner.id(), second, "before").taskId());
        experience.editPreference(owner.id(), preference.id(), new EditPreference(1, "自然妆容", true));
        assertThatThrownBy(() -> experience.editPreference(owner.id(), preference.id(), new EditPreference(1, "旧页面要求", true))).isInstanceOf(ApiException.class);
        var edited = snapshot(Long.parseLong(images.submitShot(owner.id(), second, "edited").taskId()));
        assertThat(edited.learning().preferences().getFirst().version()).isEqualTo(2);
        assertThat(snapshot(before).prompt()).contains("不要浓妆").doesNotContain("自然妆容");
        experience.editPreference(owner.id(), preference.id(), new EditPreference(2, "自然妆容", false));
        assertThat(snapshot(Long.parseLong(images.submitShot(owner.id(), second, "disabled").taskId())).learning()).isNull();
    }

    @Test void unrelatedAccountsTargetsAndUnfinishedSourcesCannotSupplyLearningEvidence() throws Exception {
        var origin = accepted();
        mvc.perform(get("/api/image-experience/targets/SHOT/" + shot).with(user(other))).andExpect(status().isNotFound());
        mvc.perform(put("/api/image-experience/creations/" + origin.id() + "/decision").with(user(other)).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"ACCEPTED\"}")).andExpect(status().isNotFound());
        assertThatThrownBy(() -> experience.remember(other.id(), origin.id(), new Remember(Scope.USER, "偷用偏好"))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> create(second, Long.parseLong(origin.taskId()), "错误来源")).isInstanceOf(ApiException.class);
        long pending = Long.parseLong(images.submitShot(owner.id(), second, "pending").taskId());
        assertThatThrownBy(() -> create(second, pending, "尚未生成")).isInstanceOf(ApiException.class);
        assertThat(db.fetchCount(db.selectFrom("image_creation"))).isEqualTo(1);
    }

    @Test void agentCorrectionAndExplicitPreferenceBecomeInputsForTheNextOrdinaryGeneration() throws Exception {
        images.submitShot(owner.id(), shot, null);
        finish();
        UUID conversation = UUID.randomUUID();
        var corrected = chat(conversation, "纠正第1镜：面容自然一点，颜色柔和一点");
        assertThat(corrected.path("messages").get(0).path("execution").path("state").asText()).isEqualTo("DONE");
        assertThat(db.fetchCount(db.selectFrom("image_creation"))).isEqualTo(1);
        finish();
        var satisfied = chat(conversation, "满意第1镜，记住为本项目偏好");
        assertThat(satisfied.path("messages").get(1).path("execution").path("state").asText()).isEqualTo("DONE");
        assertThat(db.fetchCount(db.selectFrom("image_preference"))).isEqualTo(1);
        long next = Long.parseLong(images.submitShot(owner.id(), second, "next").taskId());
        assertThat(snapshot(next).prompt()).contains("面容自然一点，颜色柔和一点");
        var tools = db.fetch("select name from agent_tool_call where name in ('generate_image','evaluate_image','remember_image_preference') order by id").getValues(0, String.class);
        assertThat(tools).containsExactly("generate_image", "evaluate_image", "remember_image_preference");
    }

    private Creation accepted() throws Exception {
        var made = create(shot, null, "面容自然，颜色柔和");
        finish();
        return experience.decide(owner.id(), made.id(), Decision.ACCEPTED);
    }

    private Creation create(long target, Long source, String instruction) {
        return creation.create(owner.id(), new ImageCreationService.Request(UUID.randomUUID(), TargetType.SHOT, target, source, instruction, null, Map.of()));
    }

    private com.vibestory.provider.ImageProvider.Input snapshot(long task) {
        return images.input(db.fetchOne("select input_snapshot from task where id=?", task).get(0, String.class));
    }

    private void finish() throws Exception {
        var lease = store.claim("image-experience-test").orElseThrow();
        assertThat(store.succeedImage(lease, lease.version(), mock.generate(images.input(lease.input()), lease.signature()))).isTrue();
    }

    private JsonNode chat(UUID conversation, String instruction) throws Exception {
        var response = mvc.perform(multipart("/api/agent/conversations/" + conversation + "/messages").with(user(owner)).with(csrf())
            .param("requestKey", UUID.randomUUID().toString()).param("instruction", instruction)
            .param("projectId", Long.toString(project)).param("episodeId", Long.toString(episode))).andExpect(status().isOk()).andReturn();
        return json.readTree(response.getResponse().getContentAsByteArray());
    }

    private Viewer account(String email) {
        long id = db.fetchOne("insert into app_user(email,password_hash) values (?,'test-hash') returning id", email).get(0, Long.class);
        return new Viewer(id, email, "test-hash");
    }

    private long project(String name) {
        return db.fetchOne("insert into project(user_id,name,visual_type) values (?,?,'realistic') returning id", owner.id(), name).get(0, Long.class);
    }

    private long episode(long project) {
        return db.fetchOne("insert into episode(user_id,project_id,episode_index,title) values (?,?,1,'测试') returning id", owner.id(), project).get(0, Long.class);
    }

    private long shot(long project, long episode, int seq, String description) {
        return db.fetchOne("insert into shot(user_id,project_id,episode_id,seq,status,description) values (?,?,?,?,'CONFIRMED',?) returning id",
            owner.id(), project, episode, seq, description).get(0, Long.class);
    }
}
