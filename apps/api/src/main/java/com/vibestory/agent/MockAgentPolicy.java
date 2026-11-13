package com.vibestory.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vibestory.provider.TextProperties;
import com.vibestory.provider.TextProvider;
import com.vibestory.provider.TextProviders;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Deterministic model double; tools and persistence still execute through the production runtime. */
@Component
class MockAgentPolicy {
    private final ObjectMapper json;
    private final TextProviders text;
    private final TextProperties config;
    private final AgentIntentRouter router;
    MockAgentPolicy(ObjectMapper json, TextProviders text, TextProperties config, AgentIntentRouter router) {
        this.json = json;
        this.text = text;
        this.config = config;
        this.router = router;
    }

    JsonNode respond(String role, JsonNode messages) throws InterruptedException {
        if(role.equals("COMPACT")) return summarize(messages);
        String instruction="";
        for(var message:messages) if(message.path("role").asText().equals("user")) instruction=message.path("content").asText();
        var byCall=new java.util.LinkedHashMap<String,JsonNode>();
        for(var message:messages) if(message.path("role").asText().equals("assistant") && message.path("content").asText().startsWith("{\"kind\":\"context_summary\"")) {
            try {
                var memo=json.readTree(message.path("content").asText());
                for(var result:memo.path("taskState").path("completed")) byCall.put(result.path("callId").asText(),result);
            } catch(java.io.IOException invalid) { throw new IllegalStateException(invalid); }
        }
        for(var message:messages) if(message.path("role").asText().equals("tool")) {
            try { byCall.put(message.path("tool_call_id").asText(),json.readTree(message.path("content").asText())); }
            catch(java.io.IOException invalid) { throw new IllegalStateException(invalid); }
        }
        var results=new ArrayList<JsonNode>(byCall.values());
        var needed=role.equals("MAIN") ? java.util.Set.of("list_skills","read_skill","get_image_context") : java.util.Set.of("read_input","read_previous");
        for(int i=0;i<results.size();i++) {
            var result=results.get(i);
            if(!needed.contains(result.path("tool").asText()) || !result.path("offloaded").asBoolean()) continue;
            String id=result.path("reference").path("callId").asText();
            var pages=new java.util.TreeMap<Integer,JsonNode>();
            for(var page:results) if(page.path("tool").asText().equals("read_tool_result") && page.path("data").path("reference").asText().equals("tool:"+id))
                pages.put(page.path("data").path("offset").asInt(),page.path("data"));
            var full=new StringBuilder();
            int offset=0;
            while(true) {
                var page=pages.get(offset);
                if(page==null || !page.path("text").isTextual()) return call("read_tool_result",Map.of("callId",id,"offset",offset,"limit",8192));
                full.append(page.path("text").asText());
                if(page.path("nextOffset").isMissingNode() || page.path("nextOffset").isNull()) break;
                offset=page.path("nextOffset").asInt();
            }
            try { results.set(i,json.readTree(full.toString())); }
            catch(java.io.IOException invalid) { throw new IllegalStateException(invalid); }
        }
        String skill = role.equals("MAIN") ? "workflow" : role.toLowerCase();
        if (results.stream().noneMatch(r -> r.path("ok").asBoolean() && r.path("tool").asText().equals("load_skill")))
            return call("load_skill", Map.of("id", skill));
        if (!role.equals("MAIN")) return child(role, instruction, results);
        if (find(results, "get_status") == null) return call("get_status", Map.of());
        var image = imageRequest(instruction, results);
        if (image != null) return image;
        var author = java.util.regex.Pattern.compile("(?is)^(?:新增|创建|修改|保存)\\s*(?:Skill|技能)\\s*《([a-z0-9-]+)》[：:]\\s*([\\s\\S]+)$").matcher(instruction);
        if (author.matches()) {
            String id = author.group(1);
            var listing = find(results,"list_skills");
            if (listing == null) return call("list_skills",Map.of());
            JsonNode existing = null;
            for (var item : listing.path("data")) if (item.path("id").asText().equals(id)) existing=item;
            var current = find(results,"read_skill");
            if (existing != null && current == null) return call("read_skill",Map.of("id",id));
            var saved = find(results,"save_skill");
            if (saved != null) return done(saved.path("ok").asBoolean() ? saved.path("data").path("message").asText()
                : saved.path("error").path("message").asText());
            return call("save_skill",Map.of("id",id,"document",author.group(2),
                "expectedVersion",current == null ? "" : current.path("data").path("version").asText()));
        }
        boolean combined = instruction.matches("(?s).*(?:剧本|脚本).*(?:再|然后|并|接着).*分镜.*");
        var delegated = results.stream().filter(r -> r.path("tool").asText().equals("delegate_child") && r.path("ok").asBoolean()).toList();
        if (combined) {
            if (delegated.isEmpty()) return call("delegate_child", Map.of("role", "SCRIPT", "instruction", instruction));
            var last = delegated.getLast();
            String turn = last.path("data").path("id").asText();
            var waited = results.stream().filter(r -> r.path("tool").asText().equals("await_child")
                && r.path("data").path("id").asText().equals(turn)).findFirst().orElse(null);
            if (waited == null) return call("await_child", Map.of("id", turn));
            if (!waited.path("ok").asBoolean()) return done(waited.path("error").path("message").asText());
            if (delegated.size() == 1) return call("delegate_child", Map.of("role", "STORYBOARD", "instruction", instruction));
            return done("剧本与分镜草案已就绪。分镜草案版本 " + turn + "。");
        }
        WorkbenchSkills.Command command = null;
        try { command = WorkbenchSkills.route(instruction); } catch (IllegalArgumentException ignored) { }
        if (command != null && (command.action() == WorkbenchSkills.Action.PREPARE_SCRIPT
                || command.action() == WorkbenchSkills.Action.PLAN_STORYBOARD)) {
            if (find(results, "delegate_child") != null) return done(results.getLast().path("data").path("message").asText("已派发创作子 Agent"));
            String childRole = command.action() == WorkbenchSkills.Action.PREPARE_SCRIPT ? "SCRIPT" : "STORYBOARD";
            return call("delegate_child", Map.of("role", childRole, "instruction", command.text(1) == null ? "" : command.text(1)));
        }
        var action = find(results, "execute_action");
        if (action != null) return done(action.path("ok").asBoolean() ? action.path("data").path("message").asText()
            : action.path("error").path("message").asText());
        if (results.stream().noneMatch(r -> r.path("tool").asText().equals("load_skill") && r.path("data").path("id").asText().equals("workbench")))
            return call("load_skill", Map.of("id", "workbench"));
        if (!AgentIntentRouter.known(instruction)) {
            try {
                var status = find(results, "get_status").path("data");
                var plan = status.path("episodeWorkflow").isObject()
                    ? json.treeToValue(status.path("episodeWorkflow"), EpisodeWorkflow.EpisodePlan.class) : null;
                String mapped = router.suggest(instruction, true, plan, null);
                if (mapped == null) return done("暂时无法识别这条指令，可以输入 / 查看可用操作。");
                instruction = mapped;
            } catch (Exception invalid) { throw new IllegalStateException(invalid); }
        }
        return call("execute_action", Map.of("instruction", instruction));
    }

    private JsonNode imageRequest(String instruction, List<JsonNode> results) {
        var shot = java.util.regex.Pattern.compile("^(纠正|重画|生成)第\\s*([1-9][0-9]*)\\s*镜(?:分镜图)?[：:,，]\\s*(.+)$", java.util.regex.Pattern.DOTALL).matcher(instruction);
        var asset = java.util.regex.Pattern.compile("^(纠正|重画|生成)(角色|场景)《([^》]+)》[：:,，]\\s*(.+)$", java.util.regex.Pattern.DOTALL).matcher(instruction);
        var evaluation = java.util.regex.Pattern.compile("^(满意|不满意)第\\s*([1-9][0-9]*)\\s*镜(?:[，,].*)?$", java.util.regex.Pattern.DOTALL).matcher(instruction);
        var assetEvaluation = java.util.regex.Pattern.compile("^(满意|不满意)(角色|场景)《([^》]+)》(?:[，,].*)?$", java.util.regex.Pattern.DOTALL).matcher(instruction);
        var saveRecipe = java.util.regex.Pattern.compile(
            "^(?:保存|沉淀)(?:第\\s*([1-9][0-9]*)\\s*镜|(角色|场景)《([^》]+)》)为(?:创作)?配方《([^》]+)》(?:[：:]\\s*(.+))?$",
            java.util.regex.Pattern.DOTALL).matcher(instruction);
        boolean isShot = shot.matches();
        boolean isAsset = asset.matches();
        boolean isAssetEvaluation = assetEvaluation.matches();
        boolean isEvaluation = evaluation.matches() || isAssetEvaluation;
        boolean isRecipe = saveRecipe.matches();
        if (!isShot && !isAsset && !isEvaluation && !isRecipe) return null;
        if (results.stream().noneMatch(r -> r.path("tool").asText().equals("load_skill") && r.path("data").path("id").asText().equals("image-learning")))
            return call("load_skill", Map.of("id", "image-learning"));
        String assetKind = isRecipe ? saveRecipe.group(2) : isAsset ? asset.group(2) : isAssetEvaluation ? assetEvaluation.group(2) : null;
        String type = assetKind == null ? "SHOT" : assetKind.equals("角色") ? "CHARACTER" : "SCENE";
        String target = isRecipe ? saveRecipe.group(assetKind == null ? 1 : 3)
            : isAsset ? asset.group(3) : isShot ? shot.group(2) : isAssetEvaluation ? assetEvaluation.group(3) : evaluation.group(2);
        var context = find(results, "get_image_context");
        if (context == null) return call("get_image_context", Map.of("targetType", type, "target", target));
        if (!context.path("ok").asBoolean()) return done(context.path("error").path("message").asText());
        var data = context.path("data");
        if (isRecipe) {
            var saved = find(results, "save_image_recipe");
            if (saved != null) return done(saved.path("ok").asBoolean()
                ? "已保存配方「" + saved.path("data").path("name").asText() + "」，可在对话栏的「创作配方」查看。"
                : saved.path("error").path("message").asText());
            var latest = data.path("creations").path(0);
            if (!latest.path("decision").asText().equals("ACCEPTED")) return done("请先告诉我是否满意这次出图，再保存配方。");
            String template = saveRecipe.group(5) == null ? latest.path("instruction").asText() : saveRecipe.group(5).strip();
            if (template.isBlank()) return done("请补充想复用的画面要求。");
            return call("save_image_recipe", Map.of("id", latest.path("id").asText(), "name", saveRecipe.group(4), "template", template));
        }
        if (isEvaluation) {
            boolean satisfied = (isAssetEvaluation ? assetEvaluation.group(1) : evaluation.group(1)).equals("满意");
            var latest = data.path("creations").path(0);
            if (latest.isMissingNode()) return done("当前没有可评价的出图记录，请先提交一次带要求的出图。");
            var evaluated = find(results, "evaluate_image");
            if (evaluated == null) return call("evaluate_image", Map.of("id", latest.path("id").asText(),
                "decision", satisfied ? "ACCEPTED" : "REJECTED"));
            if (!evaluated.path("ok").asBoolean()) return done(evaluated.path("error").path("message").asText());
            if (instruction.contains("记住") && satisfied) {
                if (latest.path("instruction").asText().isBlank()) return done("已记录满意。请告诉我具体想记住哪条画面偏好。");
                var saved = find(results, "remember_image_preference");
                if (saved != null) return done(saved.path("ok").asBoolean() ? "已记录满意结果并保存可编辑的创作偏好。" : saved.path("error").path("message").asText());
                String range = instruction.matches("(?s).*(?:个人|所有项目|跨项目|记住我|我的偏好).*") ? "USER" : instruction.contains("项目") ? "PROJECT" : "TARGET";
                return call("remember_image_preference", Map.of("id", latest.path("id").asText(), "scope", range, "rule", latest.path("instruction").asText()));
            }
            return done("已记录你对这次图片结果的评价。");
        }
        var made = find(results, "generate_image");
        if (made != null) return done(made.path("ok").asBoolean() ? "已提交图片候选，请生成完成后比较和评价。" : made.path("error").path("message").asText());
        boolean correction = !(isAsset ? asset.group(1) : shot.group(1)).equals("生成");
        var example = data.path("examples").path(0);
        for (var candidate : data.path("examples")) {
            if (candidate.path("selected").asBoolean()) { example = candidate; break; }
        }
        if (correction && example.isMissingNode()) return done("请先生成一张图片，再指定要纠正的结果。");
        return call("generate_image", Map.of("targetType", type, "targetId", data.path("targetId").asText(),
            "sourceTaskId", correction ? example.path("taskId").asText() : "", "instruction", isAsset ? asset.group(4) : shot.group(3),
            "recipeVersionId", "", "parameters", Map.of()));
    }

    private JsonNode child(String role, String instruction, List<JsonNode> results) throws InterruptedException {
        var input = find(results, "read_input");
        if (input == null) return call("read_input", Map.of());
        var previous = find(results, "read_previous");
        if (previous == null) return call("read_previous", Map.of());
        var source = input.path("data");
        if (role.equals("SCRIPT")) {
            String prior = previous.path("data").path("script").asText();
            String script = prior.isBlank() ? source.path("source").asText() : prior;
            return call("submit_draft", Map.of("draft", Map.of("script", script,
                "summary", instruction.isBlank() ? "已整理本集脚本" : "Mock 修订记录 · " + CreativeSkill.clip(instruction, 100))));
        }
        var scenes = new ArrayList<TextProvider.SceneOption>();
        source.path("scenes").forEach(s -> scenes.add(new TextProvider.SceneOption(s.asText(), null)));
        var generated = text.generate(new TextProvider.Input("BREAKDOWN", source.path("episodeId").asLong(), "mock",
            config.baseUrl(), config.model(), "agent-loop-mock-v1", 0, 0, source.path("script").asText(), scenes, config.maxTokens(), true));
        return call("submit_draft", Map.of("draft", Map.of("shots", generated.shots())));
    }

    private JsonNode summarize(JsonNode messages) {
        try {
            var input=json.readTree(messages.get(1).path("content").asText());
            var decisions=new java.util.LinkedHashSet<String>();
            input.path("previousSummary").path("decisions").forEach(v -> decisions.add(v.asText()));
            String original=input.path("previousTail").asText()+input.path("originalHistory").asText();
            int start=0;
            while((start=original.indexOf("关键决定：",start))>=0) {
                start+="关键决定：".length();
                int end=start;
                while(end<original.length() && end<start+180 && original.charAt(end)!='"'
                    && original.charAt(end)!='\\' && original.charAt(end)!='\n' && original.charAt(end)!='\r') end++;
                if(end>start) decisions.add(original.substring(start,end));
                start=Math.max(end,start+1);
            }
            return done(json.writeValueAsString(Map.of("summary","已按原始存档整理至第 "+(input.path("part").asInt()+1)+" 块",
                "decisions",decisions.stream().limit(12).toList(),"pending",List.of())));
        } catch(java.io.IOException invalid) { throw new IllegalStateException(invalid); }
    }

    private JsonNode find(List<JsonNode> results, String tool) {
        return results.stream().filter(r -> r.path("tool").asText().equals(tool)).reduce((a,b) -> b).orElse(null);
    }
    private JsonNode call(String name, Map<String, ?> args) {
        try {
            return json.valueToTree(Map.of("role", "assistant", "content", "", "tool_calls", List.of(Map.of(
                "id", "call_" + UUID.randomUUID(), "type", "function", "function", Map.of("name", name, "arguments", json.writeValueAsString(args))))));
        } catch (Exception invalid) { throw new IllegalStateException(invalid); }
    }
    private JsonNode done(String content) { return json.valueToTree(Map.of("role", "assistant", "content", content)); }
}
