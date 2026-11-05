package com.vibestory.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vibestory.agent.CreativeWorker;
import com.vibestory.auth.Viewer;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"vibestory.worker.enabled=false", "vibestory.mock-only=true"})
@AutoConfigureMockMvc
class CreativeWorkflowsIntegrationTest extends PostgresSupport {
    @Autowired DSLContext db;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired CreativeWorker worker;
    Viewer alice;
    Viewer bob;
    long project;
    String base;

    @BeforeEach void setup() {
        db.execute("truncate app_user,storage_cleanup restart identity cascade");
        alice = viewer("alice@creative.test");
        bob = viewer("bob@creative.test");
        project = db.fetchOne("insert into project(user_id,name,visual_type) values (?,'整稿','realistic') returning id", alice.id()).get(0, Long.class);
        base = "/api/projects/" + project + "/creative";
    }

    @Test void wholeDraftAndIndependentChildSessionsUseTheSameWorkflowFromPageAndChat() throws Exception {
        String first = "第一集 雨夜\n沈夜走进雨夜街道，便利店的灯亮着，窗上映出旧纸条。\n";
        String last = "第二集 旧宅\n许星遥推开旧宅的门，发现屋里摆着一盏从未见过的灯。";
        String manuscript = first + "\n" + last;
        var source = postJson(base + "/source", Map.of("requestKey", UUID.randomUUID(), "name", "整部.txt", "text", manuscript));
        var planning = chat(UUID.randomUUID().toString(), null, "规划项目分集");
        assertThat(planning.toString()).contains("已登记项目分集规划").doesNotContain(manuscript);
        worker.runOne();
        var plan = getJson(base).path("plan");
        String planId = plan.path("id").asText();
        assertThat(plan.path("state").asText()).isEqualTo("READY");
        var preview = getJson(base + "/plan/" + planId).path("episodes");
        assertThat(preview).hasSize(2);
        assertThat(preview.get(0).path("start").asInt()).isZero();
        assertThat(preview.get(0).path("end").asInt()).isEqualTo(preview.get(1).path("start").asInt());
        assertThat(preview.get(1).path("end").asInt()).isEqualTo(manuscript.length());

        var accepted = postJson(base + "/plan/" + planId + "/accept", null);
        var applied = accepted.path("appliedIds");
        assertThat(applied).hasSize(2);
        assertThat(postJson(base + "/plan/" + planId + "/accept", null).path("appliedIds")).isEqualTo(applied);
        String episode = applied.get(0).asText();
        String path = "/api/episodes/" + episode + "/creative";
        assertThat(db.fetchOne("select source_id from creative_episode_source where episode_id=?", Long.parseLong(episode)).get(0, String.class))
            .isEqualTo(source.path("id").asText());
        assertThat(getJson("/api/episodes/" + episode).path("story").asText()).startsWith(first);

        var session = postJson(path + "/session", null);
        assertThat(postJson(path + "/session", null)).isEqualTo(session);
        String main = session.path("mainSessionId").asText();
        String scriptSession = session.path("scriptSessionId").asText();
        String boardSession = session.path("storyboardSessionId").asText();
        assertThat(new java.util.HashSet<>(java.util.List.of(main, scriptSession, boardSession))).hasSize(3);
        assertThat(chat(main, episode, "整理本集剧本").toString()).contains("已派发剧本子 Agent");
        worker.runOne();
        var script = getJson(path).path("script");
        String scriptId = script.path("id").asText();
        assertThat(script.path("sessionId").asText()).isEqualTo(scriptSession);
        assertThat(script.path("state").asText()).isEqualTo("READY");
        assertThat(getJson(path).toString()).doesNotContain("旧纸条");
        assertThat(getJson(path + "/script/" + scriptId).path("script").asText()).contains("旧纸条").doesNotContain("旧宅");
        assertThat(chat(main, episode, "继续").toString()).contains("剧本已就绪").doesNotContain("旧纸条");

        var board = postJson(path + "/storyboard", Map.of("requestKey", UUID.randomUUID(), "scriptId", scriptId));
        String boardId = board.path("id").asText();
        assertThat(board.path("sessionId").asText()).isEqualTo(boardSession);
        worker.runOne();
        assertThat(getJson(path + "/storyboard/" + boardId).path("shots")).isNotEmpty();
        mvc.perform(get(path + "/storyboard/" + boardId).with(user(bob))).andExpect(status().isNotFound());
        mvc.perform(multipart("/api/agent/conversations/" + main + "/messages")
            .param("projectId", Long.toString(project)).param("episodeId", applied.get(1).asText())
            .param("requestKey", UUID.randomUUID().toString()).param("instruction", "继续").with(user(alice)).with(csrf()))
            .andExpect(status().isNotFound());

        chat(main, episode, "整理本集剧本：保留主要台词");
        worker.runOne();
        var revised = getJson(path).path("script");
        assertThat(revised.path("previousId").asText()).isEqualTo(scriptId);
        assertThat(revised.path("sessionId").asText()).isEqualTo(scriptSession);
        mvc.perform(post(path + "/storyboard/" + boardId + "/apply").with(user(alice)).with(csrf())).andExpect(status().isConflict());
        chat(main, episode, "规划本集分镜：加强开场构图");
        worker.runOne();
        var replacement = getJson(path).path("storyboard");
        assertThat(replacement.path("scriptId")).isEqualTo(revised.path("id"));
        var reply = chat(main, episode, "应用分镜草案 " + replacement.path("id").asText());
        assertThat(reply.toString()).contains("已应用分镜草案");
        assertThat(getJson(path).path("storyboard").path("state").asText()).isEqualTo("APPLIED");
        assertThat(getJson("/api/episodes/" + episode + "/shots")).isNotEmpty();
    }

    @Test void longSourceCheckpointsWindowAndCannotApplyAfterReplacement() throws Exception {
        String manuscript = "雨夜长街。".repeat(1250) + "\n" + "许星遥走进长廊。".repeat(1000);
        postJson(base + "/source", Map.of("requestKey", UUID.randomUUID(), "name", "长稿.md", "text", manuscript));
        var turn = postJson(base + "/plan", Map.of("requestKey", UUID.randomUUID()));
        String id = turn.path("id").asText();
        worker.runOne();
        assertThat(getJson(base).path("plan").path("state").asText()).isEqualTo("QUEUED");
        assertThat(db.fetchOne("select cursor from creative_turn where id=?", UUID.fromString(id)).get(0, Integer.class)).isGreaterThan(0);
        for (int i = 0; i < 4; i++) worker.runOne();
        var draft = getJson(base + "/plan/" + id).path("episodes");
        assertThat(draft.size()).isGreaterThan(1);
        assertThat(draft.get(draft.size() - 1).path("end").asInt()).isEqualTo(manuscript.length());
        postJson(base + "/source", Map.of("requestKey", UUID.randomUUID(), "name", "新版.md", "text", manuscript + "新的结尾。"));
        mvc.perform(post(base + "/plan/" + id + "/accept").with(user(alice)).with(csrf())).andExpect(status().isConflict());
        assertThat(db.fetchCount(org.jooq.impl.DSL.table("episode"))).isZero();
    }

    private JsonNode chat(String conversation, String episode, String instruction) throws Exception {
        var request = multipart("/api/agent/conversations/" + conversation + "/messages")
            .param("projectId", Long.toString(project)).param("requestKey", UUID.randomUUID().toString()).param("instruction", instruction);
        if (episode != null) request.param("episodeId", episode);
        return json.readTree(mvc.perform(request.with(user(alice)).with(csrf())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private JsonNode postJson(String url, Object payload) throws Exception {
        var request = post(url).with(user(alice)).with(csrf());
        if (payload != null) request.contentType("application/json").content(json.writeValueAsString(payload));
        return json.readTree(mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private JsonNode getJson(String url) throws Exception {
        return json.readTree(mvc.perform(get(url).with(user(alice))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private Viewer viewer(String email) {
        long id = db.fetchOne("insert into app_user(email,password_hash) values (?,'unused') returning id", email).get(0, Long.class);
        return new Viewer(id, email, "unused");
    }
}
