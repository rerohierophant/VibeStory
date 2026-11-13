package com.vibestory.agent;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vibestory.admission.AdmissionProperties;
import com.vibestory.common.ApiException;
import com.vibestory.common.TextInput;
import com.vibestory.provider.TextProperties;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
class AgentIntentRouter {
    private static final String TOOL = "suggest_workbench_command";
    private static final Pattern MOCK_LIGHT = Pattern.compile("^(?:请\\s*)?(?:把)?第\\s*([1-9][0-9]{0,8})\\s*镜(?:头)?(?:的)?((?:左边|右边|左侧|右侧)(?:那盏|的)?灯)改成(\\p{IsHan}{1,8}色)([，,]\\s*(?:然后|再)重新出图)?[。！!]?$");
    private final TextProperties text;
    private final AdmissionProperties admission;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NEVER).build();

    AgentIntentRouter(TextProperties text, AdmissionProperties admission, ObjectMapper json) {
        this.text = text;
        this.admission = admission;
        this.json = json;
    }

    static boolean known(String instruction) {
        String command = instruction.strip().replaceFirst("^请\\s*", "").replaceFirst("[。！!]+$", "");
        if (WorkbenchSkills.isStoryToImages(command) || isContinue(command)
            || command.matches("只重试(?:上次|上一批)?失败的(?:任务)?")) return true;
        try {
            if (WorkbenchSkills.route(instruction).action() == WorkbenchSkills.Action.EDIT_SHOT)
                new ShotEditSkill().parse(instruction);
            return true;
        } catch (IllegalArgumentException error) {
            return false;
        }
    }

    static boolean isContinue(String command) {
        return command.equals("继续") || command.equals("继续做这集");
    }

    String suggest(String instruction, boolean inProject, EpisodeWorkflow.EpisodePlan episode, Object creativeState) {
        if (text.provider().equals("mock")) {
            var match = MOCK_LIGHT.matcher(instruction);
            return episode != null && match.matches()
                ? "把第 " + match.group(1) + " 镜的画面描述追加：" + match.group(2) + "为" + match.group(3)
                    + (match.group(4) == null ? "" : "，并生成新候选")
                : null;
        }
        // The shared token bucket currently admits requests backed by a task attempt.
        if (admission.enabled()) return null;
        String examples = WorkbenchSkills.catalog().stream().flatMap(entry -> entry.examples().stream())
            .collect(java.util.stream.Collectors.joining("\n"));
        var parameters = Map.of("type", "object", "additionalProperties", false,
            "properties", Map.of("instruction", Map.of("type", "string")),
            "required", List.of("instruction"));
        var function = Map.of("name", TOOL, "description", "给用户一个可核对的工作台指令，空字符串表示无法安全确定", "parameters", parameters);
        var context = new LinkedHashMap<String, Object>();
        context.put("instruction", instruction);
        context.put("inProject", inProject);
        if (episode != null) context.put("episodeWorkflow", episode);
        if (creativeState != null) context.put("creativeState", creativeState);
        var body = Map.of("model", text.model(), "stream", false, "max_tokens", Math.min(1024, text.maxTokens()),
            "thinking", Map.of("type", "disabled"), "tools", List.of(Map.of("type", "function", "function", function)),
            "tool_choice", Map.of("type", "function", "function", Map.of("name", TOOL)),
            "messages", List.of(Map.of("role", "system", "content", """
                将用户请求转成一个可执行的工作台指令，按示例格式改参数。episodeWorkflow 和 creativeState 是服务端进度摘要。保留原有意图，不添加用户没要求的操作。
                缺少目标、要求精确局部重绘、请求超出示例能力或需要将任意操作组合时，返回空字符串。
                改画面内容可用「把第 3 镜的画面描述追加：右边的灯为红色」。用户要求生成时可加「，并生成新候选」。
                """ + examples),
                Map.of("role", "user", "content", encode(context))));
        try {
            var request = HttpRequest.newBuilder(URI.create(text.baseUrl().replaceAll("/+$", "") + "/chat/completions"))
                .timeout(text.timeout()).header("Authorization", "Bearer " + text.apiKey())
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(encode(body))).build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200 || response.body().length() > 128 * 1024)
                throw unavailable();
            return readSuggestion(response.body());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw unavailable();
        } catch (IOException error) {
            throw unavailable();
        }
    }

    String readSuggestion(String response) throws IOException {
        var reader = json.readerFor(JsonNode.class).with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        JsonNode choices = reader.<JsonNode>readValue(response).path("choices");
        if (!choices.isArray() || choices.size() != 1) return null;
        var choice = choices.get(0);
        var calls = choice.path("message").path("tool_calls");
        if (!choice.path("finish_reason").asText().equals("tool_calls") || !calls.isArray() || calls.size() != 1) return null;
        var call = calls.get(0);
        var function = call.path("function");
        if (!call.path("type").asText().equals("function") || call.path("id").asText().isBlank()
            || !function.path("name").asText().equals(TOOL) || !function.path("arguments").isTextual()) return null;
        JsonNode args = reader.readValue(function.get("arguments").textValue());
        if (!args.isObject() || args.size() != 1 || !args.path("instruction").isTextual()) return null;
        String candidate = args.get("instruction").textValue().strip();
        if (candidate.isBlank() || candidate.length() > 500 || !known(candidate)) return null;
        TextInput.validate(candidate, 2000, "建议指令");
        return candidate;
    }

    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (IOException error) { throw new IllegalStateException("无法编码指令请求", error); }
    }

    private ApiException unavailable() {
        return new ApiException(HttpStatus.BAD_GATEWAY, "暂时无法识别自由表达，可以输入 / 查看可用指令");
    }
}
