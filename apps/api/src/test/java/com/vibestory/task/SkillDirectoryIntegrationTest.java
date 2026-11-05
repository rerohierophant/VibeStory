package com.vibestory.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vibestory.agent.AgentLoopModel;
import com.vibestory.agent.CreativeWorker;
import com.vibestory.agent.SkillDirectory;
import com.vibestory.auth.Viewer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"vibestory.worker.enabled=false","vibestory.mock-only=true"})
@AutoConfigureMockMvc
class SkillDirectoryIntegrationTest extends PostgresSupport {
    static final Path ROOT = temporaryRoot();
    @DynamicPropertySource static void skills(DynamicPropertyRegistry r) { r.add("vibestory.skills.root",ROOT::toString); }
    @Autowired MockMvc mvc;
    @Autowired DSLContext db;
    @Autowired ObjectMapper json;
    @Autowired SkillDirectory skills;
    @Autowired CreativeWorker worker;
    @MockitoSpyBean AgentLoopModel model;
    Viewer alice;
    Viewer bob;
    long project;
    long episode;
    UUID conversation;

    @BeforeEach void setup() throws Exception {
        remove(ROOT.resolve("users"));
        remove(ROOT.resolve("dynamic-method"));
        db.execute("truncate app_user,storage_cleanup restart identity cascade");
        alice=viewer("skill-owner@test"); bob=viewer("skill-other@test");
        project=db.fetchOne("insert into project(user_id,name,visual_type) values (?,'文件 Skill','realistic') returning id",alice.id()).get(0,Long.class);
        episode=db.fetchOne("insert into episode(user_id,project_id,episode_index,title,story_content) values (?,?,1,'雨夜',?) returning id",
            alice.id(),project,"沈夜走进便利店，许星遥递给他一张纸条，提醒他去旧宅寻找失踪的钟表。").get(0,Long.class);
        conversation=UUID.fromString(postJson("/api/episodes/"+episode+"/creative/session",null).path("mainSessionId").asText());
    }

    @Test void apiAndEditorReadTheSameFileWhilePersonalOverridesStayIsolated() throws Exception {
        var builtin=skills.read(alice.id(),"script");
        String document=document("script","SCRIPT","个人剧本规范");
        var saved=putJson("/api/skills/script",Map.of("document",document,"expectedVersion",builtin.version()));
        assertThat(Files.readString(ROOT.resolve(saved.path("location").asText()))).isEqualTo(document);
        assertThat(json.readTree(mvc.perform(get("/api/skills/script").with(user(bob))).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString()).path("version").asText()).isEqualTo(builtin.version());
        String editor=document("script","SCRIPT","编辑器直接修改");
        Files.writeString(ROOT.resolve(saved.path("location").asText()),editor);
        assertThat(getJson("/api/skills/script").path("document").asText()).isEqualTo(editor);
        mvc.perform(put("/api/skills/script").with(user(alice)).with(csrf()).contentType("application/json")
            .content(json.writeValueAsString(Map.of("document",document,"expectedVersion",saved.path("version").asText()))))
            .andExpect(status().isConflict());
    }

    @Test void directoryEditsAfterRunCreationDoNotChangeTheSkillLoadedByThatRun() throws Exception {
        var original=skills.read(alice.id(),"script");
        var first=skills.save(alice.id(),"script",original.version(),document("script","SCRIPT","冻结规则甲"));
        var once=new AtomicBoolean();
        doAnswer(call -> {
            if (call.getArgument(0).equals("SCRIPT") && once.compareAndSet(false,true))
                skills.save(alice.id(),"script",first.version(),document("script","SCRIPT","后续规则乙"));
            return call.callRealMethod();
        }).when(model).complete(anyString(),any());
        String path="/api/episodes/"+episode+"/creative/script";
        UUID id=UUID.fromString(postJson(path,Map.of("requestKey",UUID.randomUUID())).path("id").asText());
        worker.runOne();
        String requests=String.join("",db.fetch("select request from agent_model_call where run_id=? order by round",id).getValues(0,String.class));
        assertThat(requests).contains("冻结规则甲").doesNotContain("后续规则乙");
        assertThat(skills.read(alice.id(),"script").content()).isEqualTo("后续规则乙");
        UUID second=UUID.fromString(postJson(path,Map.of("requestKey",UUID.randomUUID())).path("id").asText());
        worker.runOne();
        assertThat(String.join("",db.fetch("select request from agent_model_call where run_id=?",second).getValues(0,String.class)))
            .contains("后续规则乙");
    }

    @Test void agentDiscoversAndWritesTheSameDirectoryAndNewRunsSeeTheNewSkill() throws Exception {
        Files.createDirectory(ROOT.resolve("dynamic-method"));
        Files.writeString(ROOT.resolve("dynamic-method/SKILL.md"),document("dynamic-method","MAIN","人工新增的工作方法"));
        assertThat(skills.list(alice.id(),"MAIN")).extracting(SkillDirectory.Entry::id).contains("dynamic-method");
        String doc=document("my-script","SCRIPT","保留线索与主要台词，读取本集输入后提交草稿，按校验错误修复。");
        String instruction="新增 Skill《my-script》："+doc;
        var response=mvc.perform(multipart("/api/agent/conversations/"+conversation+"/messages").param("instruction",instruction)
            .param("requestKey",UUID.randomUUID().toString()).param("projectId",Long.toString(project)).param("episodeId",Long.toString(episode))
            .with(user(alice)).with(csrf())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var message=json.readTree(response).path("messages").get(0);
        assertThat(message.path("execution").path("state").asText()).isEqualTo("DONE");
        assertThat(message.path("result").path("message").asText()).contains("已保存 Skill");
        assertThat(Files.readString(ROOT.resolve("users/"+alice.id()+"/my-script/SKILL.md"))).isEqualTo(doc.stripTrailing());
        assertThat(skills.list(bob.id(),"SCRIPT")).extracting(SkillDirectory.Entry::id).doesNotContain("my-script");
        var selected=new AtomicBoolean();
        doAnswer(call -> {
            if (call.getArgument(0).equals("SCRIPT") && selected.compareAndSet(false,true)) {
                var assistant=json.valueToTree(Map.of("role","assistant","content","","tool_calls",List.of(Map.of("id","custom_"+UUID.randomUUID(),
                    "type","function","function",Map.of("name","load_skill","arguments","{\"id\":\"my-script\"}")))));
                return new AgentLoopModel.Reply(assistant,json.createObjectNode());
            }
            return call.callRealMethod();
        }).when(model).complete(anyString(),any());
        UUID turn=UUID.fromString(postJson("/api/episodes/"+episode+"/creative/script",Map.of("requestKey",UUID.randomUUID())).path("id").asText());
        worker.runOne();
        assertThat(db.fetchOne("select skill_snapshot from agent_run where id=?",turn).get(0,String.class)).contains("my-script",doc.strip().split("\n")[1]);
        assertThat(db.fetchOne("select arguments->>'id' from agent_tool_call where run_id=? and name='load_skill' and result->>'ok'='true'",turn).get(0,String.class))
            .isEqualTo("my-script");
        assertThat(db.fetchOne("select state from agent_run where id=?",turn).get(0,String.class)).isEqualTo("DONE");
    }

    private static Path temporaryRoot() {
        try {
            Path root=Files.createTempDirectory("vibestory-skill-test-");
            Path source=Path.of(System.getProperty("vibestory.skills.root"));
            for (String id:List.of("workflow","workbench","script","storyboard")) {
                Files.createDirectory(root.resolve(id));
                Files.copy(source.resolve(id+"/SKILL.md"),root.resolve(id+"/SKILL.md"));
            }
            return root;
        } catch (java.io.IOException invalid) { throw new IllegalStateException(invalid); }
    }
    @AfterAll static void cleanSkills() throws Exception { remove(ROOT); }
    private static void remove(Path path) throws Exception {
        if (!Files.exists(path)) return;
        try (var files=Files.walk(path)) { for(var file:files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(file); }
    }
    private String document(String id,String role,String body) { return "---\nname: "+id+"\ndescription: 文件规范\nroles: ["+role+"]\n---\n\n"+body+"\n"; }
    private JsonNode getJson(String path) throws Exception { return json.readTree(mvc.perform(get(path).with(user(alice))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()); }
    private JsonNode postJson(String path,Object body) throws Exception {
        var call=post(path).with(user(alice)).with(csrf());
        if(body!=null) call.contentType("application/json").content(json.writeValueAsString(body));
        return json.readTree(mvc.perform(call).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }
    private JsonNode putJson(String path,Object body) throws Exception { return json.readTree(mvc.perform(put(path).with(user(alice)).with(csrf()).contentType("application/json").content(json.writeValueAsString(body))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()); }
    private Viewer viewer(String email) { return new Viewer(db.fetchOne("insert into app_user(email,password_hash) values (?,'unused') returning id",email).get(0,Long.class),email,"unused"); }
}
