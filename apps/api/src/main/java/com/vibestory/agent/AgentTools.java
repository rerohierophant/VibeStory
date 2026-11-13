package com.vibestory.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vibestory.agent.AgentConversationController.ChatInput;
import com.vibestory.agent.WorkbenchAgent.AgentResult;
import com.vibestory.common.ApiException;
import com.vibestory.experience.ImageExperience;
import com.vibestory.experience.ImageCreationService;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Component;

@Component
class AgentTools {
    record Context(Record run, UUID creativeLease) {
        UUID id() { return run.get("id", UUID.class); }
        long user() { return run.get("user_id", Long.class); }
        Long project() { return run.get("project_id", Long.class); }
        Long episode() { return run.get("episode_id", Long.class); }
        String role() { return run.get("role", String.class); }
        UUID turn() { return run.get("creative_turn_id", UUID.class); }
    }
    record Outcome(Object data, AgentResult reply, boolean waiting, boolean terminal) {
        static Outcome data(Object value) { return new Outcome(value, null, false, false); }
    }
    private final DSLContext db;
    private final ObjectMapper json;
    private final WorkbenchCommands commands;
    private final CreativeWorkflows creative;
    private final EpisodeWorkflow workflow;
    private final SkillDirectory skills;
    private final AgentContextManager contexts;
    private final ImageExperience experience;
    private final ImageCreationService imageCreation;
    AgentTools(DSLContext db, ObjectMapper json, WorkbenchCommands commands, CreativeWorkflows creative, EpisodeWorkflow workflow, SkillDirectory skills, AgentContextManager contexts,
               ImageExperience experience, ImageCreationService imageCreation) {
        this.db = db; this.json = json; this.commands = commands; this.creative = creative; this.workflow = workflow;
        this.skills = skills;
        this.contexts = contexts;
        this.experience = experience;
        this.imageCreation = imageCreation;
    }

    List<Map<String,Object>> definitions(String role) {
        var tools = new ArrayList<Map<String,Object>>();
        tools.add(definition("read_tool_result","按引用分页读取本运行的原始工具结果，不重新执行业务动作。offset 按 Unicode 字符，limit 最大 8192",Map.of(
            "callId",string(),"offset",Map.of("type","integer"),"limit",Map.of("type","integer")),List.of("callId","offset","limit")));
        tools.add(definition("read_history","读取本运行完整历史存档，包括同一作用域的前序主运行。through 使用摘要 archive 的值，offset 按 Unicode 字符，limit 最大 8192",Map.of(
            "through",Map.of("type","integer"),"offset",Map.of("type","integer"),"limit",Map.of("type","integer")),List.of("through","offset","limit")));
        tools.add(definition("load_skill", "按 ID 加载当前角色可用的 Skill", Map.of("id", string()), List.of("id")));
        if (role.equals("MAIN")) {
            tools.add(definition("get_image_context", "读取本项目的图片、纠正、已确认偏好和配方。SHOT 的 target 填本集镜号，CHARACTER/SCENE 填完整名称", Map.of(
                "targetType", string(), "target", string()), List.of("targetType", "target")));
            tools.add(definition("generate_image", "按用户本轮要求生成或纠正图片，返回排队任务。先读取图片上下文。sourceTaskId 纠正时填原图任务，首次生成填空。配方版本留空或填已读版本", Map.of(
                "targetType", string(), "targetId", string(), "sourceTaskId", string(), "instruction", string(), "recipeVersionId", string(),
                "parameters", Map.of("type", "object")), List.of("targetType", "targetId", "sourceTaskId", "instruction", "recipeVersionId", "parameters")));
            tools.add(definition("evaluate_image", "仅在用户明确评价对应结果时记录 ACCEPTED 或 REJECTED，不能把生成完成等同满意", Map.of(
                "id", string(), "decision", string()), List.of("id", "decision")));
            tools.add(definition("remember_image_preference", "用户要求记住偏好时，将明确满意结果的纠正保存为 TARGET、PROJECT 或 USER 范围的可编辑偏好", Map.of(
                "id", string(), "scope", string(), "rule", string()), List.of("id", "scope", "rule")));
            tools.add(definition("save_image_recipe", "用户明确要求保存配方时，从已满意结果提炼可复用要求。素材由下次目标绑定，文本槽位可写 {{lighting}}", Map.of(
                "id", string(), "name", string(), "template", string()), List.of("id", "name", "template")));
            tools.add(definition("list_skills", "发现共享 Skill 目录中的内置项与个人项，新编辑只对新运行生效", Map.of(), List.of()));
            tools.add(definition("read_skill", "读取 Skill 当前文件用于审阅或编辑，不改变本轮已冻结的执行版本", Map.of("id",string()), List.of("id")));
            tools.add(definition("save_skill", "用户明确要求新增或编辑 Skill 时保存个人文件，expectedVersion 使用读取版本，新建用空字符串", Map.of(
                "id",string(),"document",string(),"expectedVersion",string()), List.of("id","document","expectedVersion")));
            tools.add(definition("get_status", "读取绑定项目 / 集的进度、待审项与产物引用，不返回原文", Map.of(), List.of()));
            tools.add(definition("execute_action", "按 workbench Skill 执行用户明确要求的业务指令", Map.of("instruction", string()), List.of("instruction")));
            tools.add(definition("delegate_child", "派发一个单集专业任务，返回子任务引用", Map.of(
                "role", Map.of("type", "string", "enum", List.of("SCRIPT","STORYBOARD")), "instruction", string()), List.of("role","instruction")));
            tools.add(definition("await_child", "等待本集的创作任务，返回摘要和引用，不能读取完整产物", Map.of("id", string()), List.of("id")));
        } else {
            tools.add(definition("read_input", "读取当前子任务冻结的原文或剧本与相关资产", Map.of(), List.of()));
            tools.add(definition("read_previous", "读取同输入版本的上一版产物", Map.of(), List.of()));
            tools.add(definition("submit_draft", "校验并保存完整草稿，失败时根据 error 修复后再提交", Map.of("draft", Map.of("type","object")), List.of("draft")));
        }
        return List.copyOf(tools);
    }

    Outcome execute(Context c, String callId, String name, JsonNode args) {
        var spec = definitions(c.role()).stream().map(t -> (Map<?,?>) t.get("function"))
            .filter(f -> f.get("name").equals(name)).findFirst().orElseThrow(() -> ApiException.badRequest("当前角色不能使用工具 " + name));
        var params = (Map<?,?>) spec.get("parameters");
        var properties = (Map<?,?>) params.get("properties");
        if (args == null || !args.isObject() || args.size() != properties.size()) throw ApiException.badRequest("工具参数字段不完整或包含未知字段");
        for (var key : properties.keySet()) {
            var value = args.get(key.toString());
            String type = ((Map<?,?>) properties.get(key)).get("type").toString();
            if (value == null || type.equals("string") && !value.isTextual() || type.equals("object") && !value.isObject()
                || type.equals("integer") && (!value.isIntegralNumber() || !value.canConvertToInt()))
                throw ApiException.badRequest("工具参数 " + key + " 类型错误");
        }
        if(name.equals("read_tool_result")) return Outcome.data(contexts.readResult(c.user(),c.id(),args.path("callId").asText(),args.path("offset").asInt(),args.path("limit").asInt()));
        if(name.equals("read_history")) return Outcome.data(contexts.readHistory(c.user(),c.id(),args.path("through").asInt(),args.path("offset").asInt(),args.path("limit").asInt()));
        if (name.equals("load_skill")) {
            String id = args.path("id").asText();
            var snapshot = jsonValue(c.run().get("skill_snapshot",String.class)).path(id);
            SkillDirectory.Skill skill;
            if (snapshot.isMissingNode()) {
                // Runs created before V21 retain the old hash check rather than loading changed instructions.
                skill = skills.read(c.user(),id);
                if (!jsonValue(c.run().get("skill_versions",String.class)).path(id).asText().equals(skill.version()))
                    throw ApiException.conflict("该 Skill 不在本轮快照中，请在新运行中使用");
            } else {
                try { skill = json.treeToValue(snapshot,SkillDirectory.Skill.class); }
                catch (java.io.IOException invalid) { throw new IllegalStateException(invalid); }
            }
            if (!skill.roles().contains(c.role())) throw ApiException.badRequest("当前角色不能加载该 Skill");
            return Outcome.data(Map.of("id", skill.id(), "version", skill.version(), "content", skill.content(),
                "operations", skill.id().equals("workbench") ? WorkbenchSkills.catalog() : List.of()));
        }
        if (name.equals("get_status")) {
            if (c.episode() != null) return Outcome.data(Map.of("episodeWorkflow", workflow.plan(c.user(), c.episode()),
                "creative", creative.episodeSummary(c.user(), c.episode(), c.project())));
            return Outcome.data(c.project() == null ? Map.of("message", "请先选择项目或分集") : creative.project(c.user(), c.project()));
        }
        if (name.equals("list_skills")) return Outcome.data(skills.list(c.user(),null));
        if (name.equals("read_skill")) return Outcome.data(skills.read(c.user(),args.path("id").asText()));
        requireSkill(c);
        if (name.equals("get_image_context")) {
            if (c.project() == null) throw ApiException.badRequest("请先打开项目");
            var type = ImageExperience.TargetType.valueOf(args.path("targetType").asText());
            String target = args.path("target").asText();
            var row = type == ImageExperience.TargetType.SHOT
                ? db.fetchOne("select id from shot where project_id=? and episode_id=? and user_id=? and seq::text=?", c.project(), c.episode(), c.user(), target)
                : db.fetchOne("select id from " + (type == ImageExperience.TargetType.CHARACTER ? "character_asset" : "scene_asset")
                    + " where project_id=? and user_id=? and name=?", c.project(), c.user(), target);
            if (row == null) throw ApiException.notFound();
            return Outcome.data(experience.context(c.user(), type, row.get(0, Long.class)));
        }
        if (name.equals("generate_image")) {
            String original = c.run().get("instruction", String.class);
            if (!original.matches("(?s).*(?:生成|出图|重画|重出|纠正|修改|改图|调整|太|一点).*") || original.contains("不要生成"))
                throw ApiException.badRequest("用户未要求生成或纠正图片");
            var type = ImageExperience.TargetType.valueOf(args.path("targetType").asText());
            long target = Long.parseLong(args.path("targetId").asText());
            imageTarget(c, type, target);
            if (!db.fetchExists(db.selectOne().from("agent_tool_call").where("run_id=? and name='get_image_context' and result->>'ok'='true' and result->'data'->>'targetId'=? and result->'data'->>'targetType'=?",
                c.id(), Long.toString(target), type.name()))) throw ApiException.badRequest("请先读取该素材的图片上下文");
            var parameters = new java.util.LinkedHashMap<String, String>();
            args.path("parameters").fields().forEachRemaining(entry -> {
                if (!entry.getValue().isTextual()) throw ApiException.badRequest("配方槽位只能填写文字");
                parameters.put(entry.getKey(), entry.getValue().asText());
            });
            UUID key = UUID.nameUUIDFromBytes((c.id() + ":image:" + type + ":" + target).getBytes(StandardCharsets.UTF_8));
            var made = imageCreation.create(c.user(), new ImageCreationService.Request(key, type, target,
                args.path("sourceTaskId").asText().isBlank() ? null : Long.parseLong(args.path("sourceTaskId").asText()),
                args.path("instruction").asText(), optionalUuid(args.path("recipeVersionId").asText()), parameters));
            var reply = new AgentResult(WorkbenchSkills.Skill.GENERATE, "已按要求提交图片候选。生成后可在工作区查看，也可以在这里告诉我是否满意。",
                List.of("创作记录 " + made.id()), null, null, made.taskId(), null, null);
            return new Outcome(made, reply, false, false);
        }
        if (Set.of("evaluate_image", "remember_image_preference", "save_image_recipe").contains(name)) {
            UUID id = UUID.fromString(args.path("id").asText());
            var row = db.fetchOne("select project_id,target_type,target_id from image_creation where id=? and user_id=?", id, c.user());
            if (row == null) throw ApiException.notFound();
            var type = ImageExperience.TargetType.valueOf(row.get("target_type", String.class));
            imageTarget(c, type, row.get("target_id", Long.class));
            String original = c.run().get("instruction", String.class);
            if (name.equals("evaluate_image")) {
                var decision = ImageExperience.Decision.valueOf(args.path("decision").asText());
                boolean negative = original.matches("(?s).*(?:不满意|不喜欢|不要这|不行).*");
                if (!(negative || original.matches("(?s).*(?:满意|喜欢|就用这|这(?:张|版).*可以).*"))
                    || negative != (decision == ImageExperience.Decision.REJECTED)) throw ApiException.badRequest("用户未明确给出该结果评价");
                return Outcome.data(experience.decide(c.user(), id, decision));
            }
            if (name.equals("remember_image_preference")) {
                if (!original.matches("(?s).*(?:记住|保存.*偏好|记为.*偏好).*")) throw ApiException.badRequest("用户未要求记住偏好");
                var range = ImageExperience.Scope.valueOf(args.path("scope").asText());
                if (range == ImageExperience.Scope.USER && !original.matches("(?s).*(?:个人|所有项目|跨项目|记住我|我的偏好).*"))
                    throw ApiException.badRequest("个人偏好需要用户明确要求跨项目使用");
                return Outcome.data(experience.remember(c.user(), id, new ImageExperience.Remember(range, args.path("rule").asText())));
            }
            if (!original.contains("配方") || !original.matches("(?s).*(?:保存|沉淀|新增|创建).*")) throw ApiException.badRequest("用户未要求保存创作配方");
            UUID key = UUID.nameUUIDFromBytes((c.id() + ":recipe:" + id).getBytes(StandardCharsets.UTF_8));
            return Outcome.data(experience.saveRecipe(c.user(), new ImageExperience.SaveRecipe(key, null, 0, type,
                args.path("name").asText(), args.path("template").asText(), id)));
        }
        if (name.equals("save_skill")) {
            String original = c.run().get("instruction",String.class).toLowerCase(java.util.Locale.ROOT);
            if (!(original.contains("skill") || original.contains("技能")) || !original.matches("(?s).*(?:新增|创建|修改|更新|保存|编辑).*"))
                throw ApiException.badRequest("用户未要求新增或修改 Skill");
            var saved = skills.save(c.user(),args.path("id").asText(),args.path("expectedVersion").asText(),args.path("document").asText());
            var reply = AgentResult.note(WorkbenchSkills.Skill.WORKSPACE,"已保存 Skill《"+saved.id()+"》，新运行可使用该版本",
                List.of("文件 "+saved.location(),"版本 "+saved.version()));
            return new Outcome(Map.of("id",saved.id(),"version",saved.version(),"location",saved.location(),"message",reply.message()),reply,false,false);
        }
        // Business identity survives a model repeating the same write with a different tool_call_id.
        UUID key = UUID.nameUUIDFromBytes((c.id() + ":" + name + ":" + (name.equals("delegate_child")
            ? args.path("role").asText() : args.path("instruction").asText())).getBytes(StandardCharsets.UTF_8));
        if (name.equals("execute_action")) {
            String instruction = args.path("instruction").asText();
            authorize(c.run().get("instruction", String.class), instruction);
            // The chat request key is retained for the legacy durable workflow association.
            var input = new ChatInput(c.run().get("request_key", UUID.class), instruction, c.project(), c.episode(), List.of());
            var reply = commands.run(c.user(), c.run().get("session_id", UUID.class), input, key, instruction);
            // A submitted project plan already has a durable receipt and progress in its own worker.
            return new Outcome(reply, reply, false, reply.projectPlanId() != null);
        }
        if (name.equals("delegate_child")) {
            if (c.episode() == null) throw ApiException.badRequest("请先打开要创作的一集");
            String role = args.path("role").asText();
            if (!Set.of("SCRIPT","STORYBOARD").contains(role)) throw ApiException.badRequest("只能派发剧本或分镜子 Agent");
            String original = c.run().get("instruction", String.class);
            if (!original.matches("(?s).*(?:剧本|脚本|分镜|继续).*") ) throw ApiException.badRequest("用户未要求该创作任务");
            if (role.equals("STORYBOARD") && !original.contains("分镜") && !AgentIntentRouter.isContinue(original))
                throw ApiException.badRequest("用户未要求规划分镜");
            var turn = role.equals("SCRIPT") ? creative.script(c.user(), c.episode(), c.project(), key, args.path("instruction").asText())
                : creative.storyboard(c.user(), c.episode(), c.project(), key, null, args.path("instruction").asText());
            var reply = AgentResult.note(role.equals("SCRIPT") ? WorkbenchSkills.Skill.STORY : WorkbenchSkills.Skill.SHOT,
                role.equals("SCRIPT") ? "已派发剧本子 Agent" : "已派发分镜子 Agent", List.of("产物引用 " + turn.id(), "子会话 " + turn.sessionId()));
            return new Outcome(Map.of("id",turn.id(),"sessionId",turn.sessionId(),"state",turn.state(),"message",reply.message()), reply, false, false);
        }
        if (name.equals("await_child")) {
            var row = db.fetchOne("""
                select t.id,t.state,t.summary,t.error,s.episode_id,s.project_id from creative_turn t
                join creative_session s on s.id=t.session_id join project p on p.id=s.project_id
                where t.id=? and p.user_id=? and s.episode_id=? and s.project_id=?
                """, UUID.fromString(args.path("id").asText()), c.user(), c.episode(), c.project());
            if (row == null) throw ApiException.notFound();
            String state = row.get("state", String.class);
            if (List.of("QUEUED","RUNNING").contains(state)) return new Outcome(null, null, true, false);
            if (!List.of("READY","APPLIED").contains(state)) throw ApiException.conflict("子任务未完成：" + row.get("error", String.class));
            return Outcome.data(Map.of("id",row.get("id", UUID.class),"state",state,"summary",row.get("summary", String.class)));
        }
        if (name.equals("read_input")) return Outcome.data(creative.childInput(c.user(), c.turn()));
        if (name.equals("read_previous")) return Outcome.data(creative.childPrevious(c.user(), c.turn()));
        if (name.equals("submit_draft")) {
            if (!db.fetchExists(db.selectOne().from("agent_tool_call").where("run_id=? and name='read_input' and result->>'ok'='true'", c.id())))
                throw ApiException.badRequest("请先通过 read_input 读取本轮输入");
            var saved = creative.publishDraft(c.user(), c.turn(), c.creativeLease(), args.path("draft"));
            return new Outcome(Map.of("id",saved.id(),"state",saved.state(),"summary",saved.summary()), null, false, true);
        }
        throw ApiException.badRequest("工具不可用");
    }

    private void requireSkill(Context c) {
        if (!db.fetchExists(db.selectOne().from("agent_tool_call").where(
            "run_id=? and name='load_skill' and result->>'ok'='true'", c.id())))
            throw ApiException.badRequest("请先加载适用于当前角色的 Skill");
    }

    private void imageTarget(Context c, ImageExperience.TargetType type, long id) {
        var row = experience.target(c.user(), type, id);
        if (!Objects.equals(c.project(), row.get("project_id", Long.class))) throw ApiException.notFound();
        if (type == ImageExperience.TargetType.SHOT && !db.fetchExists(db.selectOne().from("shot").where("id=? and episode_id=?", id, c.episode())))
            throw ApiException.notFound();
    }

    private UUID optionalUuid(String value) { return value.isBlank() ? null : UUID.fromString(value); }

    private void authorize(String original, String proposed) {
        if (AgentIntentRouter.known(original)) {
            if (!original.strip().equals(proposed.strip())) throw ApiException.badRequest("明确指令不能改写为其他业务操作");
            return;
        }
        var action = WorkbenchSkills.route(proposed).action();
        boolean explicit = switch (action) {
            case START_SHORT_DRAMA -> false;
            case ACCEPT_EPISODE_PLAN, CONFIRM_SHOT, CONFIRM_ALL -> original.contains("确认");
            case APPLY_STORYBOARD -> original.contains("应用") || original.contains("采纳");
            case GENERATE_ASSET, MISSING_IMAGES, MISSING_VIDEOS, GENERATE_SHOT, SELECT_FRAME ->
                original.matches("(?s).*(?:生成|出图|重画|重出|首帧).*");
            case RETRY_FAILED -> original.contains("重试");
            default -> true;
        };
        if (!explicit) throw ApiException.badRequest("该操作需要用户明确要求");
    }
    private Map<String,Object> string() { return Map.of("type","string"); }
    private JsonNode jsonValue(String text) {
        try { return json.readTree(text); } catch (java.io.IOException invalid) { throw new IllegalStateException(invalid); }
    }
    private Map<String,Object> definition(String name, String description, Map<String,?> fields, List<String> required) {
        return Map.of("type","function","function",Map.of("name",name,"description",description,"parameters",
            Map.of("type","object","properties",fields,"required",required,"additionalProperties",false)));
    }
}
