package com.vibestory.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.vibestory.agent.AgentLoopModel;
import com.vibestory.agent.AgentRuntime;
import com.vibestory.agent.CreativeWorker;
import com.vibestory.auth.Viewer;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"vibestory.worker.enabled=false", "vibestory.mock-only=true"})
@AutoConfigureMockMvc
class AgentRuntimeIntegrationTest extends PostgresSupport {
    @Autowired DSLContext db;
    @Autowired ObjectMapper json;
    @Autowired MockMvc mvc;
    @Autowired CreativeWorker worker;
    @Autowired AgentRuntime runtime;
    @MockitoSpyBean AgentLoopModel model;
    Viewer alice;
    Viewer bob;
    long project;
    long episode;
    UUID conversation;
    String creative;
    final String source = "独立上下文标记。沈夜推开便利店的门，店员递来一张旧纸条，提醒他不要前往长街尽头。";

    @BeforeEach void setup() throws Exception {
        resetDatabase(db);
        alice = viewer("loop@agent.test");
        bob = viewer("other@agent.test");
        project = db.fetchOne("insert into project(user_id,name,visual_type) values (?,'工具循环','realistic') returning id", alice.id()).get(0, Long.class);
        episode = db.fetchOne("insert into episode(user_id,project_id,title,episode_index,story_content) values (?,?,'雨夜',1,?) returning id",
            alice.id(), project, source).get(0, Long.class);
        creative = "/api/episodes/" + episode + "/creative";
        conversation = UUID.fromString(postJson(creative + "/session", null).path("mainSessionId").asText());
    }

    @Test void mainLoadsSkillsAndExecutesDirectToolsWithDurableReceipts() throws Exception {
        UUID key = UUID.randomUUID();
        var message = chat("确认全部镜头", key).path("messages").get(0);
        UUID run = UUID.fromString(message.path("execution").path("id").asText());
        assertThat(message.path("execution").path("state").asText()).isEqualTo("DONE");
        assertThat(toolNames(run)).containsExactly("load_skill","get_status","load_skill","execute_action");
        assertThat(chat("确认全部镜头", key).path("messages")).hasSize(1);
        assertThat(db.fetchCount(db.selectFrom("agent_run"))).isEqualTo(1);
        assertThat(db.fetchCount(db.selectFrom("agent_model_call"))).isGreaterThan(1);
        assertThat(db.fetchOne("select request from agent_model_call where run_id=? order by round desc limit 1", run).get(0, String.class))
            .contains("tool_call_id", "确认全部镜头").doesNotContain(source);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/agent/runs/" + run).with(user(bob))).andExpect(status().isNotFound());
    }

    @Test void aShortDramaRequestCreatesOneProjectAndOnePlanAcrossMessageReplay() throws Exception {
        UUID chatId = UUID.randomUUID();
        UUID key = UUID.randomUUID();
        String instruction = "帮我做个雨夜悬疑主题的短剧";
        String path = "/api/agent/conversations/" + chatId + "/messages";
        var first = json.readTree(mvc.perform(multipart(path).param("requestKey", key.toString())
            .param("instruction", instruction).with(user(alice)).with(csrf()))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        var result = first.path("messages").get(0).path("result");
        String projectId = result.path("projectId").asText();
        String planId = result.path("projectPlanId").asText();
        String projectPath = "/api/projects/" + projectId;
        String creativePath = projectPath + "/creative";
        assertThat(getJson(projectPath).path("name").asText()).isEqualTo("雨夜悬疑");
        var pending = getJson(creativePath);
        assertThat(pending.path("source").path("kind").asText()).isEqualTo("IDEA");
        assertThat(pending.path("source").path("ideaText").asText()).isEqualTo(instruction);
        assertThat(pending.path("plan").path("state").asText()).isEqualTo("QUEUED");
        assertThat(pending.path("plan").path("id").asText()).isEqualTo(planId);

        var replay = json.readTree(mvc.perform(multipart(path).param("requestKey", key.toString())
            .param("instruction", instruction).with(user(alice)).with(csrf()))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(replay.path("messages")).hasSize(1);
        assertThat(replay.path("messages").get(0).path("result")).isEqualTo(result);
        assertThat(db.fetchCount(db.selectFrom("project").where("name='雨夜悬疑'"))).isEqualTo(1);
        assertThat(db.fetchCount(db.selectFrom("creative_source").where("project_id=?", Long.parseLong(projectId)))).isEqualTo(1);
        assertThat(db.fetchCount(db.selectFrom("creative_turn"))).isEqualTo(1);
        mvc.perform(get(creativePath).with(user(bob))).andExpect(status().isNotFound());

        worker.runOne();
        assertThat(getJson(creativePath).path("plan").path("state").asText()).isEqualTo("READY");
        assertThat(getJson(creativePath + "/plan/" + planId).path("episodes")).isNotEmpty();
        assertThat(getJson(projectPath).path("episodes")).isEmpty();
    }

    @Test void mainWaitsForIndependentScriptAndStoryboardAndContinuesAfterReload() throws Exception {
        UUID key = UUID.randomUUID();
        var first = chat("先整理本集剧本，然后规划分镜", key).path("messages").get(0);
        UUID run = UUID.fromString(first.path("execution").path("id").asText());
        assertThat(runtime.view(alice.id(), run).state()).isEqualTo("WAITING");
        worker.runOne();
        runtime.resumeOne();
        assertThat(runtime.view(alice.id(), run).state()).isEqualTo("WAITING");
        worker.runOne();
        runtime.resumeOne();
        var transcript = getJson("/api/agent/conversations/" + conversation);
        assertThat(transcript.path("messages").get(0).path("execution").path("state").asText()).isEqualTo("DONE");
        assertThat(transcript.path("messages").get(0).path("result").path("message").asText()).contains("剧本与分镜草案已就绪");
        var children = db.fetch("select * from agent_run where role <> 'MAIN' order by created_at");
        assertThat(children).hasSize(2);
        assertThat(children.get(0).get("session_id")).isNotEqualTo(children.get(1).get("session_id")).isNotEqualTo(conversation);
        assertThat(toolNames(children.get(0).get("id", UUID.class))).containsExactly("load_skill","read_input","read_previous","submit_draft");
        assertThat(toolNames(children.get(1).get("id", UUID.class))).containsExactly("load_skill","read_input","read_previous","submit_draft");
        var mainRequests = db.fetch("select request from agent_model_call where run_id=?", run).getValues(0, String.class);
        assertThat(String.join("", mainRequests)).doesNotContain("独立上下文标记").contains("await_child", "summary");
        assertThat(db.fetchCount(db.selectFrom("shot"))).isZero(); // Never adopt or generate media without the user.
        assertThat(chat("先整理本集剧本，然后规划分镜", key).path("messages")).hasSize(1);
        assertThat(db.fetchCount(db.selectFrom("creative_turn"))).isEqualTo(2);
    }

    @Test void childReceivesValidationErrorAndRepairsItsOwnDraft() throws Exception {
        var injected = new AtomicBoolean();
        doAnswer(call -> {
            var reply = (AgentLoopModel.Reply) call.callRealMethod();
            if (call.getArgument(0).equals("SCRIPT") && reply.message().path("tool_calls").path(0).path("function").path("name").asText().equals("submit_draft")
                && injected.compareAndSet(false, true)) return reply("submit_draft", Map.of("draft", Map.of("script","短稿","summary","待修复")));
            return reply;
        }).when(model).complete(anyString(), any());
        var turn = postJson(creative + "/script", Map.of("requestKey",UUID.randomUUID()));
        UUID id = UUID.fromString(turn.path("id").asText());
        worker.runOne();
        assertThat(runtime.view(alice.id(), id).state()).isEqualTo("DONE");
        var submissions = db.fetch("select result from agent_tool_call where run_id=? and name='submit_draft' order by id", id);
        assertThat(submissions).hasSize(2);
        assertThat(submissions.get(0).get(0, String.class)).contains("false", "32000");
        assertThat(submissions.get(1).get(0, String.class)).contains("true", "READY");
        assertThat(db.fetchOne("select request from agent_model_call where run_id=? order by round desc limit 1", id).get(0, String.class))
            .contains("tool_call_id", "32000", "false");
        assertThat(getJson(creative + "/script/" + id).path("script").asText()).isEqualTo(source);
    }

    @Test void childCannotUseMainToolsAndThenRecoversWithinItsAllowlist() throws Exception {
        var once = new AtomicBoolean();
        doAnswer(call -> call.getArgument(0).equals("SCRIPT") && once.compareAndSet(false,true)
            ? reply("execute_action", Map.of("instruction","确认全部镜头")) : call.callRealMethod()).when(model).complete(anyString(), any());
        UUID id = UUID.fromString(postJson(creative + "/script", Map.of("requestKey",UUID.randomUUID())).path("id").asText());
        worker.runOne();
        assertThat(runtime.view(alice.id(), id).state()).isEqualTo("DONE");
        assertThat(runtime.view(alice.id(), id).trace().get(0).ok()).isFalse();
        assertThat(runtime.view(alice.id(), id).trace().get(0).message()).contains("当前角色不能使用");
        assertThat(db.fetchCount(db.selectFrom("agent_command_receipt"))).isZero();
    }

    @Test void endlessModelStopsAtRoundBudgetWithoutPublishingDraft() throws Exception {
        doAnswer(call -> call.getArgument(0).equals("SCRIPT") ? reply("load_skill",Map.of("id","script")) : call.callRealMethod())
            .when(model).complete(anyString(), any());
        UUID id = UUID.fromString(postJson(creative + "/script", Map.of("requestKey",UUID.randomUUID())).path("id").asText());
        worker.runOne();
        assertThat(runtime.view(alice.id(), id).state()).isEqualTo("FAILED");
        assertThat(runtime.view(alice.id(), id).modelRounds()).isEqualTo(AgentRuntime.MAX_ROUNDS);
        assertThat(getJson(creative).path("script").path("state").asText()).isEqualTo("FAILED");
        assertThat(db.fetchOne("select result from creative_turn where id=?",id).get(0)).isNull();
    }

    @Test void committedToolResultIsReplayedAfterInterruptionWithoutExecutingItAgain() throws Exception {
        var message = chat("把项目风格改为写实", UUID.randomUUID()).path("messages").get(0);
        UUID id = UUID.fromString(message.path("execution").path("id").asText());
        assertThat(runtime.view(alice.id(), id).state()).isEqualTo("DONE");
        long receipts = db.fetchCount(db.selectFrom("agent_command_receipt"));
        var row = db.fetchOne("select messages,model_rounds from agent_run where id=?",id);
        var history = (ArrayNode) json.readTree(row.get("messages",String.class));
        history.remove(history.size()-1); // Simulated process death after tool commit, before final model request.
        db.execute("update agent_run set state='QUEUED',messages=?::jsonb,model_rounds=model_rounds-1 where id=?",json.writeValueAsString(history),id);
        runtime.drive(id);
        assertThat(runtime.view(alice.id(),id).state()).isEqualTo("DONE");
        assertThat(db.fetchCount(db.selectFrom("agent_command_receipt"))).isEqualTo(receipts);
        assertThat(toolNames(id).stream().filter("execute_action"::equals).count()).isEqualTo(1);
    }

    @Test void repeatedWriteWithNewCallIdReusesTheBusinessReceipt() throws Exception {
        var repeated = new AtomicBoolean();
        doAnswer(call -> {
            var reply = (AgentLoopModel.Reply) call.callRealMethod();
            if (reply.message().path("tool_calls").isMissingNode() && repeated.compareAndSet(false,true))
                return reply("execute_action",Map.of("instruction","把项目风格改为写实"));
            return reply;
        }).when(model).complete(anyString(), any());
        var message = chat("把项目风格改为写实",UUID.randomUUID()).path("messages").get(0);
        UUID id = UUID.fromString(message.path("execution").path("id").asText());
        assertThat(toolNames(id).stream().filter("execute_action"::equals).count()).isEqualTo(2);
        assertThat(db.fetchCount(db.selectFrom("agent_command_receipt"))).isEqualTo(1);
    }

    @Test void lostRealRequestIsMarkedUnknownWithoutAnotherProviderCall() throws Exception {
        var message = chat("继续",UUID.randomUUID()).path("messages").get(0);
        UUID id = UUID.fromString(message.path("execution").path("id").asText());
        db.execute("update agent_run set provider='openai-compatible',state='RUNNING',lease_id=?,lease_until=clock_timestamp()-interval '1 second' where id=?",UUID.randomUUID(),id);
        db.execute("update agent_model_call set state='RUNNING' where run_id=? and round=1",id);
        clearInvocations(model);
        runtime.drive(id);
        assertThat(runtime.view(alice.id(),id).state()).isEqualTo("UNKNOWN");
        verifyNoInteractions(model);
    }

    @Test void messageAndRunAreCommittedBeforeTheFirstModelRequest() throws Exception {
        var seen = new AtomicBoolean();
        UUID key = UUID.randomUUID();
        doAnswer(call -> {
            assertThat(db.fetchOne("select agent_run_id from agent_chat_message where conversation_id=? and request_key=?",conversation,key)).isNotNull();
            seen.set(true);
            return call.callRealMethod();
        }).when(model).complete(anyString(),any());
        assertThat(chat("继续",key).path("messages").get(0).path("execution").path("state").asText()).isEqualTo("DONE");
        assertThat(seen).isTrue();
    }

    @Test void changingAnAttachmentMessageToACommandCannotExecuteBeforeReplayRejection() throws Exception {
        UUID key = UUID.randomUUID();
        long message = db.fetchOne("""
            insert into agent_chat_message(conversation_id,request_key,instruction,project_id,episode_id)
            values (?,?,'图片备注',?,?) returning id
            """,conversation,key,project,episode).get(0,Long.class);
        db.execute("insert into agent_chat_image(message_id,name,media_type,content) values (?,'fixture.png','image/png',?::bytea)",message,new byte[]{1});
        clearInvocations(model);
        mvc.perform(multipart("/api/agent/conversations/"+conversation+"/messages").param("requestKey",key.toString())
            .param("projectId",Long.toString(project)).param("episodeId",Long.toString(episode)).param("instruction","把项目风格改为写实")
            .with(user(alice)).with(csrf())).andExpect(status().isConflict());
        verifyNoInteractions(model);
        assertThat(db.fetchCount(db.selectFrom("agent_command_receipt"))).isZero();
    }

    private AgentLoopModel.Reply reply(String name, Map<String,?> args) throws Exception {
        return new AgentLoopModel.Reply(json.valueToTree(Map.of("role","assistant","content","","tool_calls",List.of(Map.of(
            "id","test_"+UUID.randomUUID(),"type","function","function",Map.of("name",name,"arguments",json.writeValueAsString(args)))))),json.createObjectNode());
    }
    private List<String> toolNames(UUID run) { return db.fetch("select name from agent_tool_call where run_id=? order by id",run).getValues(0,String.class); }
    private JsonNode chat(String instruction, UUID key) throws Exception {
        return json.readTree(mvc.perform(multipart("/api/agent/conversations/"+conversation+"/messages").param("requestKey",key.toString())
            .param("projectId",Long.toString(project)).param("episodeId",Long.toString(episode)).param("instruction",instruction)
            .with(user(alice)).with(csrf())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
    private JsonNode postJson(String path, Object body) throws Exception {
        var call = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path).with(user(alice)).with(csrf());
        if (body != null) call.contentType("application/json").content(json.writeValueAsString(body));
        return json.readTree(mvc.perform(call).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
    private JsonNode getJson(String path) throws Exception {
        return json.readTree(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path).with(user(alice))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
    private Viewer viewer(String email) {
        return new Viewer(db.fetchOne("insert into app_user(email,password_hash) values (?,'unused') returning id",email).get(0,Long.class),email,"unused");
    }
}
