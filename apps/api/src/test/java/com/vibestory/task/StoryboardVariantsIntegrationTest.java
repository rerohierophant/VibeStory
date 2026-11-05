package com.vibestory.task;

import com.vibestory.auth.User;
import com.vibestory.auth.UserRepository;
import com.vibestory.auth.Viewer;
import com.vibestory.batch.GenerationBatch;
import com.vibestory.breakdown.BreakdownService;
import com.vibestory.common.ApiException;
import com.vibestory.image.ImageTaskService;
import com.vibestory.provider.MockImageProvider;
import com.vibestory.shot.ShotService;
import com.vibestory.variant.StoryboardVariants;
import com.vibestory.variant.StoryboardVariants.*;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"vibestory.worker.enabled=false", "vibestory.mock.delay=0ms", "vibestory.mock-only=true"})
@AutoConfigureMockMvc
class StoryboardVariantsIntegrationTest extends PostgresSupport {
    @Autowired StoryboardVariants variants;
    @Autowired ShotService shots;
    @Autowired BreakdownService breakdowns;
    @Autowired ImageTaskService images;
    @Autowired GenerationBatch batches;
    @Autowired TaskStore store;
    @Autowired MockImageProvider mock;
    @Autowired DSLContext db;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder passwords;
    @Autowired MockMvc mvc;
    Viewer owner, other;
    long project, episode, first, second;

    @BeforeEach
    void setUp() {
        db.execute("truncate app_user, storage_cleanup restart identity cascade");
        owner = account("owner@variant.test");
        other = account("other@variant.test");
        project = db.fetchOne("insert into project(user_id,name,visual_type) values (?,'雨夜','realistic') returning id",
            owner.id()).get(0, Long.class);
        episode = db.fetchOne("""
            insert into episode(user_id,project_id,episode_index,title,story_content)
            values (?,?,1,'雨巷','两人在雨夜巷子里追逐，走到路灯下，远处的脚步声越来越近。') returning id
            """, owner.id(), project).get(0, Long.class);
        first = shot(1);
        second = shot(2);
    }

    @Test
    void variantsFinishOutOfOrderAndOnlyExplicitAdoptionChangesTheStoryboard() throws InterruptedException {
        var wide = submit("远景方案", edit(first, "雨巷远处出现人影", "远景"));
        var close = submit("近景方案", edit(first, "脚步踩进积水", "近景"));
        var wideLease = store.claim("worker-wide").orElseThrow();
        var closeLease = store.claim("worker-close").orElseThrow();
        assertThat(images.input(wideLease.input()).prompt()).contains("雨巷远处出现人影");
        assertThat(images.input(closeLease.input()).prompt()).contains("脚步踩进积水");
        finish(closeLease);
        finish(wideLease);
        var base = shots.get(owner.id(), first);
        assertThat(base.images()).isEmpty();
        assertThat(base.selectedImageId()).isNull();
        assertThat(base.imageState()).isEqualTo("idle");
        assertThat(base.description()).isEqualTo("原分镜");
        var output = find(close).members().getFirst();
        long imageId = Long.parseLong(output.imageId());
        assertThatThrownBy(() -> images.select(owner.id(), first, imageId)).isInstanceOf(ApiException.class);

        var request = new Adopt(UUID.randomUUID(), List.of(target(base, output)));
        variants.adopt(owner.id(), episode, Long.parseLong(close.revisionId()), request);
        variants.adopt(owner.id(), episode, Long.parseLong(close.revisionId()), request);
        var adopted = shots.get(owner.id(), first);
        assertThat(adopted.images()).hasSize(1);
        assertThat(adopted.selectedImageId()).isEqualTo(output.imageId());
        assertThat(adopted.description()).isEqualTo(output.description());
        assertThat(adopted.shotSize()).isEqualTo("近景");
        assertThat(find(wide).members().getFirst().imageId()).isNotEqualTo(output.imageId());
    }

    @Test
    void concurrentReplayQueuesOneTaskPerShotAndLocalRevisionKeepsUntouchedTask() throws Exception {
        var request = new Submit(UUID.randomUUID(), "同一请求", null, List.of(edit(first, "向前跑", "全景"), edit(second, "路灯熄灭", "近景")));
        Variant initial;
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var one = pool.submit(() -> variants.submit(owner.id(), episode, request));
            var two = pool.submit(() -> variants.submit(owner.id(), episode, request));
            initial = one.get(10, TimeUnit.SECONDS);
            assertThat(two.get(10, TimeUnit.SECONDS).revisionId()).isEqualTo(initial.revisionId());
        }
        assertThatThrownBy(() -> variants.submit(owner.id(), episode, new Submit(request.requestKey(),
            "不同方案", null, request.shots()))).isInstanceOf(ApiException.class);
        assertThat(count("task")).isEqualTo(2);
        assertThat(count("task_outbox")).isEqualTo(2);
        var revised = variants.submit(owner.id(), episode, new Submit(UUID.randomUUID(), null,
            Long.parseLong(initial.revisionId()), List.of(edit(first, "俯拍向前跑", "特写"))));
        assertThat(revised.members().getFirst().taskId()).isNotEqualTo(initial.members().getFirst().taskId());
        assertThat(revised.members().getLast().taskId()).isEqualTo(initial.members().getLast().taskId());
        assertThat(revised.members().getLast().reused()).isTrue();
        assertThat(count("task")).isEqualTo(3);
        assertThat(count("task_outbox")).isEqualTo(3);

        var ordinary = batches.submit(owner.id(), episode, GenerationBatch.Type.IMAGE, "ordinary", List.of(first), false);
        assertThat(ordinary.progress().accepted()).isEqualTo(1);
        assertThatThrownBy(() -> variants.submit(owner.id(), episode, new Submit(UUID.randomUUID(), null,
            Long.parseLong(initial.revisionId()), List.of(edit(first, "旧版本", "特写"))))).isInstanceOf(ApiException.class);
    }

    @Test
    void staleShotVersionRollsBackWholeAdoptionAndActiveVariantsProtectReplacement() throws InterruptedException {
        var variant = submit("双镜", edit(first, "第一个镜头", "近景"), edit(second, "第二个镜头", "全景"));
        finish(store.claim("first").orElseThrow());
        finish(store.claim("second").orElseThrow());
        var ready = find(variant);
        var base = shots.get(owner.id(), second);
        var targets = List.of(target(shots.get(owner.id(), first), ready.members().getFirst()), target(base, ready.members().getLast()));
        shots.update(owner.id(), second, "其他页面刚改的画面", null, null, null, null, null, base.version());
        assertThatThrownBy(() -> variants.adopt(owner.id(), episode, Long.parseLong(variant.revisionId()),
            new Adopt(UUID.randomUUID(), targets))).isInstanceOfSatisfying(ApiException.class,
            error -> assertThat(error.status()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(shots.get(owner.id(), first).images()).isEmpty();
        assertThat(shots.get(owner.id(), first).description()).isEqualTo("原分镜");
        assertThat(count("storyboard_variant_adoption")).isZero();

        assertThatThrownBy(() -> breakdowns.submit(owner.id(), episode, true)).isInstanceOf(ApiException.class);
        variants.archive(owner.id(), episode, Long.parseLong(variant.id()));
        assertThat(variants.list(owner.id(), episode).variants()).isEmpty();
        assertThat(breakdowns.submit(owner.id(), episode, true).taskId()).isNotBlank();
    }

    @Test
    void unrelatedAccountCannotReadSubmitOrFetchVariantImage() throws Exception {
        var variant = submit("有权限的方案", edit(first, "巷口", "近景"));
        finish(store.claim("media").orElseThrow());
        var ready = find(variant).members().getFirst();
        mvc.perform(get(ready.imageUrl()).with(user(other))).andExpect(status().isNotFound());
        mvc.perform(get("/api/episodes/{episode}/storyboard-variants", episode).with(user(other))).andExpect(status().isNotFound());
        assertThatThrownBy(() -> variants.submit(other.id(), episode,
            new Submit(UUID.randomUUID(), "外部用户", null, List.of(edit(first, "错误输入", "中景")))))
            .isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.status()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void teamAuthorizationUsesCurrentRoleWhileTheTaskKeepsProjectOwnership() throws Exception {
        long team = db.fetchOne("insert into team(name) values ('协作组') returning id").get(0, Long.class);
        db.execute("insert into team_member(team_id,user_id,role) values (?,?,'OWNER'),(?,?,'VIEWER')",
            team, owner.id(), team, other.id());
        db.execute("update project set team_id=? where id=?", team, project);
        assertThat(variants.list(other.id(), episode).canEdit()).isFalse();
        var request = new Submit(UUID.randomUUID(), "团队方案", null, List.of(edit(first, "路灯下", "近景")));
        assertThatThrownBy(() -> variants.submit(other.id(), episode, request)).isInstanceOfSatisfying(ApiException.class,
            error -> assertThat(error.status()).isEqualTo(HttpStatus.FORBIDDEN));
        db.execute("update team_member set role='MEMBER' where team_id=? and user_id=?", team, other.id());
        var variant = variants.submit(other.id(), episode, request);
        var task = db.fetchOne("select user_id,requested_by from task where storyboard_variant_revision_id=?", Long.parseLong(variant.revisionId()));
        assertThat(task.get("user_id", Long.class)).isEqualTo(owner.id());
        assertThat(task.get("requested_by", Long.class)).isEqualTo(other.id());
        db.execute("delete from team_member where team_id=? and user_id=?", team, other.id());
        finish(store.claim("team-worker").orElseThrow());
        var output = find(variant).members().getFirst();
        mvc.perform(get(output.imageUrl()).with(user(owner))).andExpect(status().isOk());
        mvc.perform(get(output.imageUrl()).with(user(other))).andExpect(status().isNotFound());
    }

    @Test
    void agentCreatesAndPreviewsAnImageVariantThroughTheSharedService() throws Exception {
        command("给第 1 镜创建近景出图方案《雨巷》").andExpect(jsonPath("$.message").value("已提交出图方案《雨巷》"));
        finish(store.claim("agent").orElseThrow());
        var created = variants.list(owner.id(), episode).variants().getFirst();
        var member = created.members().getFirst();
        command("查看出图方案").andExpect(jsonPath("$.details[0]").value("《雨巷》第 1 版 · 已出图 1/1"));
        command("预览采纳出图方案《雨巷》的第 1 镜")
            .andExpect(jsonPath("$.variantChoice.imageId").value(member.imageId()))
            .andExpect(jsonPath("$.variantChoice.revisionId").value(created.revisionId()));
        assertThat(shots.get(owner.id(), first).selectedImageId()).isNull();
        command("把出图方案《雨巷》的第 1 镜改为特写并重出")
            .andExpect(jsonPath("$.message").value("已提交《雨巷》第 2 版"));
        assertThat(find(created).members().getFirst().shotSize()).isEqualTo("特写");
    }

    private org.springframework.test.web.servlet.ResultActions command(String instruction) throws Exception {
        String body = """
            {"requestKey":"%s","projectId":%d,"episodeId":%d,"instruction":"%s"}
            """.formatted(UUID.randomUUID(), project, episode, instruction);
        return mvc.perform(post("/api/agent/commands").with(user(owner)).with(csrf()).contentType("application/json").content(body))
            .andExpect(status().isOk());
    }

    private Viewer account(String email) {
        var user = users.saveAndFlush(new User(email, passwords.encode("test-password")));
        return new Viewer(user.getId(), email, user.getPasswordHash());
    }

    private long shot(int seq) {
        return db.fetchOne("""
            insert into shot(user_id,project_id,episode_id,seq,status,description)
            values (?,?,?,?,'CONFIRMED','原分镜') returning id
            """, owner.id(), project, episode, seq).get(0, Long.class);
    }

    private Variant submit(String name, Edit... edits) {
        return variants.submit(owner.id(), episode, new Submit(UUID.randomUUID(), name, null, List.of(edits)));
    }

    private Edit edit(long id, String description, String size) {
        return new Edit(id, description, size, "固定");
    }

    private Variant find(Variant variant) {
        return variants.list(owner.id(), episode).variants().stream().filter(item -> item.id().equals(variant.id())).findFirst().orElseThrow();
    }

    private Target target(ShotService.View shot, VariantMember member) {
        return new Target(Long.parseLong(shot.id()), Long.parseLong(member.imageId()), shot.version(),
            shot.selectedImageId() == null ? null : Long.parseLong(shot.selectedImageId()));
    }

    private void finish(TaskStore.Lease lease) throws InterruptedException {
        assertThat(store.succeedImage(lease, lease.version(), mock.generate(images.input(lease.input()), lease.signature()))).isTrue();
    }

    private int count(String table) {
        return db.fetchCount(org.jooq.impl.DSL.table(table));
    }
}
