package com.vibestory.agent;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vibestory.agent.CreativeSkill.Role;
import com.vibestory.provider.ProviderException;
import com.vibestory.provider.TextProperties;
import com.vibestory.provider.TextProvider;
import com.vibestory.provider.TextProviders;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
class CreativeModel {
    record Config(String provider, String endpoint, String model) {}
    record Cuts(List<CreativeSkill.Cut> cuts) {}
    record Board(List<TextProvider.ShotDraft> shots) {}

    private final ObjectMapper json;
    private final TextProperties config;
    private final TextProviders text;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NEVER).build();

    CreativeModel(ObjectMapper json, TextProperties config, TextProviders text) {
        this.json = json;
        this.config = config;
        this.text = text;
    }

    Config configuration() {
        return new Config(config.provider(), config.baseUrl().replaceAll("/+$", ""), config.model());
    }

    Duration timeout() { return config.timeout(); }

    List<CreativeSkill.Cut> plan(Config model, List<CreativeSkill.Block> blocks, String instruction, String continuity)
        throws IOException, InterruptedException {
        if (model.provider().equals("mock")) return CreativeSkill.mockCuts(blocks);
        var numbered = java.util.stream.IntStream.range(0, blocks.size())
            .mapToObj(i -> Map.of("block", i + 1, "text", blocks.get(i).text())).toList();
        return complete(model, Role.PLAN, Map.of("blocks", numbered, "instruction", instruction, "priorEpisode", continuity), Cuts.class).cuts();
    }

    CreativeSkill.ScriptDraft script(Config model, String source, String instruction, String previous)
        throws IOException, InterruptedException {
        if (model.provider().equals("mock")) {
            String script = previous.isBlank() ? source.strip() : previous;
            String summary = instruction.isBlank() ? "已整理本集脚本，共 " + script.codePointCount(0, script.length()) + " 字"
                : "Mock 修订记录 · " + CreativeSkill.clip(instruction, 100);
            return new CreativeSkill.ScriptDraft(script, summary);
        }
        return complete(model, Role.SCRIPT, Map.of("source", source, "instruction", instruction, "previousScript", previous),
            CreativeSkill.ScriptDraft.class);
    }

    List<TextProvider.ShotDraft> storyboard(Config model, long episode, String script, List<TextProvider.SceneOption> scenes,
                                             List<String> characters, String instruction, JsonNode previous)
        throws IOException, InterruptedException {
        if (model.provider().equals("mock")) {
            return text.generate(new TextProvider.Input("BREAKDOWN", episode, "mock", model.endpoint(), model.model(),
                "creative-storyboard-v1", 0, 0, script, scenes, config.maxTokens(), true)).shots();
        }
        return complete(model, Role.STORYBOARD, Map.of("script", script, "scenes", scenes, "characters", characters,
            "instruction", instruction, "previousStoryboard", previous), Board.class).shots();
    }

    private <T> T complete(Config model, Role role, Map<String, ?> input, Class<T> output)
        throws IOException, InterruptedException {
        if (!configuration().equals(model))
            throw new ProviderException("CREATIVE_TEXT_CONFIGURATION_CHANGED", "文本配置已变化，请重新发起", false, false);
        var body = Map.of("model", model.model(), "stream", false, "max_tokens", config.maxTokens(),
            "response_format", Map.of("type", "json_object"), "thinking", Map.of("type", "disabled"), "messages", List.of(
                Map.of("role", "system", "content", CreativeSkill.prompt(role)),
                Map.of("role", "user", "content", json.writeValueAsString(input))));
        var request = HttpRequest.newBuilder(URI.create(model.endpoint() + "/chat/completions"))
            .timeout(config.timeout()).header("Authorization", "Bearer " + config.apiKey())
            .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 500)
            throw new ProviderException("CREATIVE_TEXT_HTTP_UNKNOWN", "文本服务未返回确定结果", false, true);
        if (response.statusCode() != 200 || response.body().length() > 1024 * 1024)
            throw new ProviderException("CREATIVE_TEXT_HTTP_REJECTED", "文本服务拒绝请求或响应过大", false, false);
        try {
            var choice = json.readTree(response.body()).path("choices").path(0);
            if (!choice.path("finish_reason").asText().equals("stop")) throw new IllegalArgumentException("输出未完整结束");
            return json.readerFor(output).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue(choice.path("message").path("content").asText());
        } catch (IOException | IllegalArgumentException invalid) {
            throw new ProviderException("CREATIVE_TEXT_INVALID", "文本模型未返回有效的结构化产物", false, false);
        }
    }
}
