package com.vibestory.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vibestory.agent.AgentContextManager;
import com.vibestory.agent.AgentLoopModel;
import com.vibestory.agent.AgentRuntime;
import com.vibestory.agent.CreativeWorker;
import com.vibestory.auth.Viewer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicIntegerArray;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"vibestory.worker.enabled=false","vibestory.mock-only=true",
    "vibestory.agent-context.tool-result-bytes=1024","vibestory.agent-context.preview-bytes=96",
    "vibestory.agent-context.trigger-bytes=32768","vibestory.agent-context.target-bytes=24576",
    "vibestory.agent-context.summary-input-bytes=4096","vibestory.agent-context.summary-output-bytes=4096"})
@AutoConfigureMockMvc
class AgentContextIntegrationTest extends PostgresSupport {
    @Autowired DSLContext db;
    @Autowired ObjectMapper json;
    @Autowired MockMvc mvc;
    @Autowired AgentRuntime runtime;
    @Autowired AgentContextManager contexts;
    @Autowired CreativeWorker worker;
    @MockitoSpyBean AgentLoopModel model;
    Viewer alice,bob;
    long project,episode;
    UUID conversation;
    final String fact="中段保留线索MIDDLE-ONLY-FACT";
    final String artifact=UUID.randomUUID().toString();

    @BeforeEach void setup() throws Exception {
        db.execute("truncate app_user,storage_cleanup restart identity cascade");
        alice=viewer("context-owner@test");bob=viewer("context-other@test");
        project=db.fetchOne("insert into project(user_id,name,visual_type) values (?,'渐进压缩','realistic') returning id",alice.id()).get(0,Long.class);
        episode=db.fetchOne("insert into episode(user_id,project_id,episode_index,title,story_content) values (?,?,1,'雨巷',?) returning id",
            alice.id(),project,"沈夜走进便利店，许星遥递给他一张旧纸条，请他在天亮前去旧宅寻找钟声。").get(0,Long.class);
        conversation=UUID.fromString(postJson("/api/episodes/"+episode+"/creative/session",null).path("mainSessionId").asText());
    }

    @Test void largeChildResultIsPersistedAndRetrievedWithUnicodeAndExactHash() throws Exception {
        String original="沈夜雨巷😀，".repeat(800);
        db.execute("update episode set story_content=? where id=?",original,episode);
        UUID child=UUID.fromString(postJson("/api/episodes/"+episode+"/creative/script",Map.of("requestKey",UUID.randomUUID())).path("id").asText());
        worker.runOne();
        assertThat(runtime.view(alice.id(),child).state()).isEqualTo("DONE");
        assertThat(runtime.view(alice.id(),child).context().offloadedResults()).isPositive();
        assertThat(db.fetch("select name from agent_tool_call where run_id=?",child).getValues(0,String.class)).contains("read_tool_result");
        String call=db.fetchOne("select call_id from agent_tool_call where run_id=? and name='read_input'",child).get(0,String.class);
        var rebuilt=new StringBuilder();int offset=0;
        while(true) {
            var page=contexts.readResult(alice.id(),child,call,offset,137);
            rebuilt.append(page.text());
            assertThat(page.sha256()).isEqualTo(hash(db.fetchOne("select result_text from agent_tool_call where run_id=? and call_id=?",child,call).get(0,String.class)));
            if(page.nextOffset()==null) break;
            offset=page.nextOffset();
        }
        assertThat(json.readTree(rebuilt.toString()).path("data").path("source").asText()).isEqualTo(original);
        assertThat(getJson("/api/episodes/"+episode+"/creative/script/"+child).path("script").asText()).isEqualTo(original);
        mvc.perform(get("/api/agent/runs/"+child+"/tool-results/"+call).with(user(bob))).andExpect(status().isNotFound());
        mvc.perform(get("/api/agent/runs/"+child+"/tool-results/"+call).param("limit","9000").with(user(alice))).andExpect(status().isBadRequest());
    }

    @Test void threeLayersUseAllOriginalChunksAndKeepCurrentRequestAndMiddleFacts() throws Exception {
        UUID id=seed(8,4500,5000);
        String original=db.fetchOne("select messages from agent_run where id=?",id).get(0,String.class);
        long receipts=db.fetchCount(db.selectFrom("agent_command_receipt"));
        runtime.drive(id);
        var execution=runtime.view(alice.id(),id);
        assertThat(execution.state()).isEqualTo("DONE");
        assertThat(execution.context().offloadedResults()).isPositive();
        assertThat(execution.context().prunedResults()).isPositive();
        assertThat(execution.context().compactions()).isPositive();
        assertThat(execution.context().afterBytes()).isLessThan(execution.context().beforeBytes());
        assertThat(db.fetchCount(db.selectFrom("agent_command_receipt"))).isEqualTo(receipts);
        var summary=db.fetchOne("select * from agent_context_summary where run_id=? and state='READY' order by created_at desc limit 1",id);
        var source=new StringBuilder();
        for(var row:db.fetch("select c.request from agent_compaction_call c where summary_id=? order by part",summary.get("id"))) {
            var request=json.readTree(row.get(0,String.class));
            var input=json.readTree(request.path("messages").get(1).path("content").asText());
            String chunk=input.path("originalHistory").asText();
            assertThat(chunk.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(4096);
            source.append(chunk);
        }
        assertThat(source.toString()).contains(fact,"保留旧首帧不重新生成",artifact);
        assertThat(hash(source.toString())).isEqualTo(summary.get("source_hash",String.class));
        assertThat(summary.get("summary",String.class)).contains(fact,"保留旧首帧不重新生成");
        var last=json.readTree(db.fetchOne("select request from agent_model_call where run_id=? order by round desc limit 1",id).get(0,String.class));
        assertThat(last.toString()).contains("把项目风格改为写实",fact,artifact,"read_history");
        var audit=(ArrayNode)json.readTree(db.fetchOne("select messages from agent_run where id=?",id).get(0,String.class));
        var old=(ArrayNode)json.readTree(original);
        assertThat(audit.size()).isGreaterThan(old.size());
        for(int i=0;i<old.size();i++) assertThat(audit.get(i)).isEqualTo(old.get(i));
        var again=contexts.readHistory(alice.id(),id,summary.get("source_messages",Integer.class),0,8192);
        assertThat(again.sha256()).isEqualTo(summary.get("source_hash",String.class));
        assertPairs(last.path("messages"));
        var report=json.createObjectNode().put("scenario","synthetic_three_layer_pressure").put("synthetic",true)
            .put("beforeRequestBytes",execution.context().beforeBytes()).put("afterRequestBytes",execution.context().afterBytes())
            .put("sourceParts",summary.get("part_count",Integer.class)).put("sourceSha256",summary.get("source_hash",String.class))
            .put("allSourcePartsCovered",true).put("canonicalPrefixUnchanged",true).put("pairedProtocolPreserved",true);
        report.set("context",json.valueToTree(execution.context()));
        report.set("fixtureFacts",json.valueToTree(List.of("把项目风格改为写实",fact,"保留旧首帧不重新生成",artifact)));
        var output=java.nio.file.Path.of("build/verification/agent-context.json");
        java.nio.file.Files.createDirectories(output.getParent());
        java.nio.file.Files.writeString(output,json.writerWithDefaultPrettyPrinter().writeValueAsString(report)+"\n");
    }

    @Test void microCanFitWithoutAnySummaryCallOrDroppingMessages() throws Exception {
        UUID id=seed(20,0,900);
        var policy=(ObjectNode)json.readTree(db.fetchOne("select context_policy from agent_run where id=?",id).get(0,String.class));
        policy.put("toolResultBytes",4096).put("triggerBytes",24576).put("targetBytes",16384);
        db.execute("update agent_run set context_policy=?::jsonb where id=?",json.writeValueAsString(policy),id);
        runtime.drive(id);
        var view=runtime.view(alice.id(),id);
        assertThat(view.state()).isEqualTo("DONE");
        assertThat(view.context().prunedResults()).isPositive();
        assertThat(view.context().compactions()).isZero();
        assertThat(view.context().summaryCalls()).isZero();
    }

    @Test void summaryCheckpointResumesWithoutRepeatingCompletedPartOrBusinessWrite() throws Exception {
        UUID id=seed(8,4500,5000);
        var seen=new AtomicIntegerArray(64);var once=new AtomicBoolean();
        doAnswer(call -> {
            if(call.getArgument(0).equals("COMPACT")) {
                JsonNode request=call.getArgument(1);
                int part=json.readTree(request.path("messages").get(1).path("content").asText()).path("part").asInt();
                seen.incrementAndGet(part);
                if(part==1 && once.compareAndSet(false,true)) throw new InterruptedException("fixture process stop");
            }
            return call.callRealMethod();
        }).when(model).complete(anyString(),any());
        long receipts=db.fetchCount(db.selectFrom("agent_command_receipt"));
        runtime.drive(id);Thread.interrupted();
        assertThat(db.fetchOne("select cursor from agent_context_summary where run_id=?",id).get(0,Integer.class)).isEqualTo(1);
        db.execute("update agent_run set lease_until=clock_timestamp()-interval '1 second' where id=?",id);
        runtime.drive(id);
        assertThat(runtime.view(alice.id(),id).state()).isEqualTo("DONE");
        assertThat(seen.get(0)).isEqualTo(1);
        assertThat(seen.get(1)).isEqualTo(2);
        assertThat(db.fetchCount(db.selectFrom("agent_command_receipt"))).isEqualTo(receipts);
        assertThat(db.fetchOne("select count(*) from agent_context_summary where run_id=?",id).get(0,Integer.class)).isEqualTo(1);
    }

    @Test void malformedSummaryFailsWithoutReplacingOrDeletingCanonicalHistory() throws Exception {
        UUID id=seed(8,4500,5000);
        String original=db.fetchOne("select messages from agent_run where id=?",id).get(0,String.class);
        doAnswer(call -> call.getArgument(0).equals("COMPACT") ? new AgentLoopModel.Reply(
            json.valueToTree(Map.of("role","assistant","content","{\"summary\":\"missing fields\"}")),json.createObjectNode()) : call.callRealMethod())
            .when(model).complete(anyString(),any());
        runtime.drive(id);
        assertThat(runtime.view(alice.id(),id).state()).isEqualTo("FAILED");
        assertThat(db.fetchOne("select messages from agent_run where id=?",id).get(0,String.class)).isEqualTo(original);
        assertThat(db.fetchOne("select state from agent_context_summary where run_id=?",id).get(0,String.class)).isEqualTo("FAILED");
    }

    @Test void interruptedRealSummaryIsUnknownAndNeverAutomaticallySentAgain() throws Exception {
        UUID id=seed(8,4500,5000);
        doAnswer(call -> { if(call.getArgument(0).equals("COMPACT")) throw new InterruptedException("fixture missing response"); return call.callRealMethod(); })
            .when(model).complete(anyString(),any());
        runtime.drive(id);Thread.interrupted();
        db.execute("update agent_run set provider='openai-compatible',lease_until=clock_timestamp()-interval '1 second' where id=?",id);
        clearInvocations(model);
        runtime.drive(id);
        assertThat(runtime.view(alice.id(),id).state()).isEqualTo("UNKNOWN");
        assertThat(db.fetchOne("select state from agent_context_summary where run_id=?",id).get(0,String.class)).isEqualTo("UNKNOWN");
        verifyNoInteractions(model);
    }

    @Test void allVisibleTurnsRemainAndFullArchiveIncludesEarlierMainDecisionsButNoChildSource() throws Exception {
        UUID first=main("把项目风格改为写实");
        var history=(ArrayNode)json.readTree(db.fetchOne("select messages from agent_run where id=?",first).get(0,String.class));
        history.add(json.valueToTree(Map.of("role","assistant","content","关键决定：旧主运行中段决定 EARLIER-MAIN-FACT")));
        db.execute("update agent_run set messages=?::jsonb where id=?",json.writeValueAsString(history),first);
        String secret="子会话专属来源 CHILD-PRIVATE-SOURCE，不应进入主会话压缩输入。";
        db.execute("update episode set story_content=? where id=?",secret,episode);
        postJson("/api/episodes/"+episode+"/creative/script",Map.of("requestKey",UUID.randomUUID()));worker.runOne();
        for(int i=0;i<5;i++) main("继续");
        UUID last=main("哪些镜头没成功");
        var row=db.fetchOne("select * from agent_run where id=?",last);
        var messages=json.readTree(row.get("messages",String.class));
        long users=0;for(var message:messages) if(message.path("role").asText().equals("user")) users++;
        assertThat(users).isEqualTo(7);
        assertThat(messages.get(1).path("content").asText()).isEqualTo("把项目风格改为写实");
        var archive=new StringBuilder();int offset=0;
        while(true) {
            var page=contexts.readHistory(alice.id(),last,messages.size(),offset,8192);archive.append(page.text());
            if(page.nextOffset()==null) break;offset=page.nextOffset();
        }
        assertThat(archive.toString()).contains("EARLIER-MAIN-FACT").doesNotContain("CHILD-PRIVATE-SOURCE");
        mvc.perform(get("/api/agent/runs/"+last+"/history").param("through",Integer.toString(messages.size())).with(user(bob))).andExpect(status().isNotFound());
    }

    @Test void mainRetrievalToolCannotReadAChildResultByGuessingItsCallId() throws Exception {
        UUID child=UUID.fromString(postJson("/api/episodes/"+episode+"/creative/script",Map.of("requestKey",UUID.randomUUID())).path("id").asText());worker.runOne();
        String callId=db.fetchOne("select call_id from agent_tool_call where run_id=? and name='read_input'",child).get(0,String.class);
        var once=new AtomicBoolean();
        doAnswer(call -> {
            if(call.getArgument(0).equals("MAIN") && once.compareAndSet(false,true)) return reply("read_tool_result",Map.of("callId",callId,"offset",0,"limit",512));
            return call.callRealMethod();
        }).when(model).complete(anyString(),any());
        UUID main=main("继续");
        var result=db.fetchOne("select result from agent_tool_call where run_id=? and name='read_tool_result'",main).get(0,String.class);
        assertThat(result).contains("false","BUSINESS_404").doesNotContain("沈夜走进便利店");
    }

    private UUID seed(int groups,int assistantBytes,int toolBytes) throws Exception {
        UUID id=main("把项目风格改为写实");
        var messages=(ArrayNode)json.readTree(db.fetchOne("select messages from agent_run where id=?",id).get(0,String.class));
        messages.remove(messages.size()-1);
        for(int i=0;i<groups;i++) {
            String callId="pressure-"+i;
            String note=(i==3?"关键决定：保留旧首帧不重新生成。":"历史观察。")+"a".repeat(assistantBytes);
            messages.add(json.valueToTree(Map.of("role","assistant","content",note,"tool_calls",List.of(Map.of("id",callId,"type","function",
                "function",Map.of("name","get_status","arguments","{}"))))));
            String large="p".repeat(toolBytes/2)+(i==3?"关键决定："+fact+"。":"")+"q".repeat(toolBytes/2);
            var result=json.valueToTree(Map.of("ok",true,"tool","get_status","data",Map.of("id",artifact,"message","synthetic pressure fixture","large",large)));
            String encoded=json.writeValueAsString(result);
            messages.add(json.valueToTree(Map.of("role","tool","tool_call_id",callId,"content",encoded)));
            db.execute("insert into agent_tool_call(run_id,call_id,name,arguments,result,result_text) values (?,?,'get_status','{}',?::jsonb,?)",id,callId,encoded,encoded);
        }
        db.execute("update agent_run set state='QUEUED',messages=?::jsonb,model_rounds=model_rounds-1 where id=?",json.writeValueAsString(messages),id);
        return id;
    }
    private UUID main(String instruction) throws Exception {
        var result=mvc.perform(multipart("/api/agent/conversations/"+conversation+"/messages").param("requestKey",UUID.randomUUID().toString())
            .param("projectId",Long.toString(project)).param("episodeId",Long.toString(episode)).param("instruction",instruction).with(user(alice)).with(csrf()))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var messages=json.readTree(result).path("messages");return UUID.fromString(messages.get(messages.size()-1).path("execution").path("id").asText());
    }
    private void assertPairs(JsonNode messages) {
        var ids=new java.util.HashSet<String>();
        for(var message:messages) {
            message.path("tool_calls").forEach(call -> ids.add(call.path("id").asText()));
            if(message.path("role").asText().equals("tool")) assertThat(ids.remove(message.path("tool_call_id").asText())).isTrue();
        }
        assertThat(ids).isEmpty();
    }
    private AgentLoopModel.Reply reply(String name,Map<String,?> args) throws Exception {
        return new AgentLoopModel.Reply(json.valueToTree(Map.of("role","assistant","content","","tool_calls",List.of(Map.of("id","test_"+UUID.randomUUID(),"type","function",
            "function",Map.of("name",name,"arguments",json.writeValueAsString(args)))))),json.createObjectNode());
    }
    private JsonNode postJson(String path,Object body) throws Exception {
        var call=post(path).with(user(alice)).with(csrf());if(body!=null) call.contentType("application/json").content(json.writeValueAsString(body));
        return json.readTree(mvc.perform(call).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
    private JsonNode getJson(String path) throws Exception { return json.readTree(mvc.perform(get(path).with(user(alice))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()); }
    private String hash(String text) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
    private Viewer viewer(String email) { return new Viewer(db.fetchOne("insert into app_user(email,password_hash) values (?,'unused') returning id",email).get(0,Long.class),email,"unused"); }
}
