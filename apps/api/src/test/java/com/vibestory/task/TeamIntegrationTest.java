package com.vibestory.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vibestory.auth.Viewer;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"vibestory.worker.enabled=false", "vibestory.mock.delay=0ms", "vibestory.mock-only=true"})
@AutoConfigureMockMvc
class TeamIntegrationTest extends PostgresSupport {
    @Autowired DSLContext db;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TaskStore tasks;
    @Autowired TaskWorker worker;
    Viewer owner, member, viewer, outsider;
    String team;

    @BeforeEach void setup() throws Exception {
        db.execute("truncate app_user,team,storage_cleanup restart identity cascade");
        owner = account("owner");
        member = account("member");
        viewer = account("viewer");
        outsider = account("outsider");
        team = read(post("/api/teams"), owner, "{\"name\":\"短剧团队\"}", 201).path("id").asText();
    }

    @Test void invitationsAreBoundToTheAccountAndAcceptOnlyOnce() throws Exception {
        String invite = invite(owner, member, "MEMBER");
        mvc.perform(post("/api/me/team-invitations/{id}/accept", invite).with(user(outsider)).with(csrf()))
            .andExpect(status().isNotFound());
        mvc.perform(get("/api/me/team-invitations").with(user(member)))
            .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(invite));
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = pool.submit(() -> accept(member, invite));
            var second = pool.submit(() -> accept(member, invite));
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        }
        assertThat(db.fetchOne("select count(*) from team_member").get(0, Integer.class)).isEqualTo(2);
        assertThat(db.fetchOne("select count(*) from team_audit where action='INVITATION_ACCEPTED'").get(0, Integer.class)).isEqualTo(1);
        remove(owner, member);
        accept(member, invite);
        mvc.perform(get("/api/teams/{team}", team).with(user(member))).andExpect(status().isNotFound());

        String expired = invite(owner, member, "MEMBER");
        db.execute("update team_invitation set expires_at=now()-interval '1 day' where id=?", Long.parseLong(expired));
        mvc.perform(post("/api/me/team-invitations/{id}/accept", expired).with(user(member)).with(csrf()))
            .andExpect(status().isConflict());
        assertThat(invite(owner, member, "MEMBER")).isNotEqualTo(expired);
    }

    @Test void roleChangesAreScopedAndRevokeInviterAuthority() throws Exception {
        join(member, "ADMIN");
        join(viewer, "ADMIN");
        read(post("/api/teams/{team}/invitations", team), member, "{\"email\":\"new@team.test\",\"role\":\"ADMIN\"}", 403);
        mvc.perform(delete("/api/teams/{team}/members/{id}", team, viewer.id()).with(user(member)).with(csrf()))
            .andExpect(status().isForbidden());
        mvc.perform(delete("/api/teams/{team}/members/{id}", team, owner.id()).with(user(owner)).with(csrf()))
            .andExpect(status().isForbidden());
        String pending = invite(member, outsider, "VIEWER");
        read(patch("/api/teams/{team}/members/{id}", team, member.id()), owner, "{\"role\":\"MEMBER\"}", 204);
        mvc.perform(post("/api/me/team-invitations/{id}/accept", pending).with(user(outsider)).with(csrf()))
            .andExpect(status().isConflict());
        mvc.perform(get("/api/teams/{team}/invitations", team).with(user(member))).andExpect(status().isForbidden());
    }

    @Test void revokedCreatorLosesEveryEntryPointWhileTheProjectTaskCompletes() throws Exception {
        join(member, "MEMBER");
        join(viewer, "VIEWER");
        String project = read(post("/api/teams/{team}/projects", team), member,
            "{\"name\":\"共享制作\",\"visualType\":\"realistic\"}", 201).path("id").asText();
        String episode = read(post("/api/projects/{project}/episodes", project), owner, "{}", 201).path("id").asText();
        String storyPath = "/api/episodes/" + episode + "/story";
        read(put(storyPath), viewer, "{\"story\":\"越权修改\",\"expectedStoryRevision\":0}", 403);
        read(put(storyPath), member, "{\"story\":\"团队成员保存剧本\",\"expectedStoryRevision\":0}", 200);
        read(put(storyPath), owner, "{\"story\":\"旧版本不得覆盖\",\"expectedStoryRevision\":0}", 409);
        assertThat(read(get("/api/episodes/{episode}", episode), viewer, null, 200).path("story").asText()).isEqualTo("团队成员保存剧本");
        assertThat(read(get("/api/projects"), member, null, 200)).isEmpty();
        mvc.perform(get("/api/projects/{project}", project).with(user(outsider))).andExpect(status().isNotFound());
        read(post("/api/agent/commands"), member, """
            {"requestKey":"%s","projectId":%s,"episodeId":%s,"instruction":"确认全部镜头"}
            """.formatted(UUID.randomUUID(), project, episode), 404);

        long shot = db.fetchOne("""
            insert into shot(user_id,project_id,episode_id,seq,status,scene_name,description)
            values (?,?,?,1,'CONFIRMED','雨夜街道','固定镜头') returning id
            """, member.id(), Long.parseLong(project), Long.parseLong(episode)).get(0, Long.class);
        String batchBody = """
            {"type":"IMAGE","requestKey":"team-image","shotIds":["%d"]}
            """.formatted(shot);
        String batchPath = "/api/episodes/" + episode + "/generation-batches";
        var batch = read(post(batchPath), member, batchBody, 202);
        assertThat(read(post(batchPath), owner, batchBody, 202).path("id")).isEqualTo(batch.path("id"));
        String taskId = batch.path("members").path(0).path("taskId").asText();
        assertThat(db.fetchOne("select requested_by from task where id=?", Long.parseLong(taskId)).get(0, Long.class)).isEqualTo(member.id());
        remove(owner, member);

        var lease = tasks.claim("team-test", new TaskStore.Providers("mock", "mock", "mock")).orElseThrow();
        assertThat(lease.taskId()).isEqualTo(Long.parseLong(taskId));
        var finished = new CountDownLatch(1);
        worker.executeClaimed(lease, finished::countDown);
        assertThat(finished.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(read(get("/api/tasks/{task}", taskId), owner, null, 200).path("ready").asBoolean()).isTrue();
        String image = read(get("/api/shots/{shot}", shot), viewer, null, 200).path("images").path(0).path("url").asText();
        mvc.perform(get(image).with(user(viewer))).andExpect(status().isOk()).andExpect(header().string("Cache-Control", "private, no-store"));
        for (String path : new String[]{"/api/projects/" + project, "/api/shots/" + shot, "/api/tasks/" + taskId, image})
            mvc.perform(get(path).with(user(member))).andExpect(status().isNotFound());
        assertThat(read(get("/api/task-metrics"), member, null, 200).path("groups")).isEmpty();
        assertThat(read(get("/api/tasks/{task}/attempts/1/diagnostics", taskId), owner, null, 200).has("rawResponse")).isFalse();
        mvc.perform(get("/api/tasks/{task}/attempts/1/diagnostics", taskId).with(user(viewer))).andExpect(status().isForbidden());
    }

    private Viewer account(String name) {
        String email = name + "@team.test";
        long id = db.fetchOne("insert into app_user(email,password_hash) values (?,'unused') returning id", email).get(0, Long.class);
        return new Viewer(id, email, "unused");
    }

    private String invite(Viewer actor, Viewer recipient, String role) throws Exception {
        return read(post("/api/teams/{team}/invitations", team), actor,
            "{\"email\":\"%s\",\"role\":\"%s\"}".formatted(recipient.email(), role), 201).path("id").asText();
    }

    private void join(Viewer recipient, String role) throws Exception { accept(recipient, invite(owner, recipient, role)); }

    private Void accept(Viewer recipient, String invite) throws Exception {
        read(post("/api/me/team-invitations/{id}/accept", invite), recipient, null, 204);
        return null;
    }

    private void remove(Viewer actor, Viewer removed) throws Exception {
        read(delete("/api/teams/{team}/members/{id}", team, removed.id()), actor, null, 204);
    }

    private JsonNode read(MockHttpServletRequestBuilder request, Viewer viewer, String body, int code) throws Exception {
        if (body != null) request.contentType("application/json").content(body);
        var result = mvc.perform(request.with(user(viewer)).with(csrf())).andExpect(status().is(code)).andReturn();
        return json.readTree(result.getResponse().getContentAsByteArray());
    }
}
