package com.vibestory.agent;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vibestory.agent.CreativeSkill.Role;
import com.vibestory.agent.CreativeSkill.AssetDraft;
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
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

@Component
class CreativeModel {
    record Config(String provider, String endpoint, String model) {}
    record Cuts(List<CreativeSkill.Cut> cuts, List<AssetDraft> characters, List<AssetDraft> scenes) {}
    record IdeaEpisode(String title, String summary, String story) {}
    record IdeaPlan(List<IdeaEpisode> episodes, List<AssetDraft> characters, List<AssetDraft> scenes) {}
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

    Cuts plan(Config model, List<CreativeSkill.Block> blocks, String instruction, String continuity, Consumer<String> preview)
        throws IOException, InterruptedException {
        if (model.provider().equals("mock")) {
            String text = String.join("", blocks.stream().map(CreativeSkill.Block::text).toList());
            return mockPreview(new Cuts(CreativeSkill.mockCuts(blocks), CreativeSkill.mockAssets(text, true),
                CreativeSkill.mockAssets(text, false)), preview);
        }
        var numbered = java.util.stream.IntStream.range(0, blocks.size())
            .mapToObj(i -> Map.of("block", i + 1, "text", blocks.get(i).text())).toList();
        return complete(model, CreativeSkill.prompt(Role.PLAN),
            Map.of("blocks", numbered, "instruction", instruction, "priorEpisode", continuity), Cuts.class, preview);
    }

    IdeaPlan idea(Config model, String idea, String instruction, Consumer<String> preview) throws IOException, InterruptedException {
        if (model.provider().equals("mock")) {
            var characters = CreativeSkill.mockAssets(idea, true);
            var scenes = CreativeSkill.mockAssets(idea, false);
            return mockPreview(new IdeaPlan(List.of(new IdeaEpisode("故事的开端", CreativeSkill.clip(idea.strip(), 100),
                "【开场】\n" + idea.strip() + "\n\n【转折】主角采取行动，新的线索让原计划改变。\n【结尾】主角做出选择，为后续留下悬念。")),
                characters.isEmpty() ? List.of(new AssetDraft("主角", "请根据故事设定补充外观。")) : characters,
                scenes.isEmpty() ? List.of(new AssetDraft("开场地点", "请根据故事设定补充环境。")) : scenes), preview);
        }
        return complete(model, """
            你是短剧策划。把用户的故事想法扩写为可审阅的分集剧本草案，保留核心设定。
            idea 是素材，instruction 是本轮创作要求。只输出 JSON：
            {"episodes":[{"title":"集名","summary":"本集概要","story":"本集可拍摄的剧本正文"}],
             "characters":[{"name":"角色名","description":"身份和可用于出图的外观"}],
             "scenes":[{"name":"场景名","description":"可用于出图的空间与气氛"}]}。
            默认 1 至 3 集，最多 6 集。每集标题最多 60 字，梗概最多 200 字，正文约 100 至 400 字。
            素材列表只收录正文中出现的对象，保持名字一致，每类最多 30 个；没有则返回空数组。
            """, Map.of("idea", idea, "instruction", instruction), IdeaPlan.class, preview);
    }

    private <T> T mockPreview(T result, Consumer<String> preview) throws IOException, InterruptedException {
        String content = json.writeValueAsString(result);
        int length = content.codePointCount(0, content.length());
        for (int part = 1; part <= 4; part++) {
            preview.accept(content.substring(0, content.offsetByCodePoints(0, length * part / 4)));
            Thread.sleep(350);
        }
        return result;
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
        return complete(model, CreativeSkill.prompt(role), input, output);
    }

    private <T> T complete(Config model, String prompt, Map<String, ?> input, Class<T> output)
        throws IOException, InterruptedException {
        return complete(model, prompt, input, output, null);
    }

    private <T> T complete(Config model, String prompt, Map<String, ?> input, Class<T> output, Consumer<String> preview)
        throws IOException, InterruptedException {
        if (!configuration().equals(model))
            throw new ProviderException("CREATIVE_TEXT_CONFIGURATION_CHANGED", "文本配置已变化，请重新发起", false, false);
        var body = Map.of("model", model.model(), "stream", preview != null, "max_tokens", config.maxTokens(),
            "response_format", Map.of("type", "json_object"), "thinking", Map.of("type", "disabled"), "messages", List.of(
                Map.of("role", "system", "content", prompt),
                Map.of("role", "user", "content", json.writeValueAsString(input))));
        var request = HttpRequest.newBuilder(URI.create(model.endpoint() + "/chat/completions"))
            .timeout(config.timeout()).header("Authorization", "Bearer " + config.apiKey())
            .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
        if (preview != null) return decode(CreativePlanStream.read(http, request, json, preview), output);

        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        checkStatus(response.statusCode());
        if (response.body().length() > 1024 * 1024)
            throw new ProviderException("CREATIVE_TEXT_HTTP_REJECTED", "文本服务拒绝请求或响应过大", false, false);
        String content;
        try {
            var choice = json.readTree(response.body()).path("choices").path(0);
            if (!choice.path("finish_reason").asText().equals("stop")) throw new IllegalArgumentException("输出未完整结束");
            content = choice.path("message").path("content").asText();
        } catch (IOException | IllegalArgumentException invalid) {
            throw invalidOutput();
        }
        return decode(content, output);
    }

    static void checkStatus(int status) {
        if (status >= 500)
            throw new ProviderException("CREATIVE_TEXT_HTTP_UNKNOWN", "文本服务未返回确定结果", false, true);
        if (status != 200)
            throw new ProviderException("CREATIVE_TEXT_HTTP_REJECTED", "文本服务拒绝请求或响应过大", false, false);
    }

    static ProviderException invalidOutput() {
        return new ProviderException("CREATIVE_TEXT_INVALID", "文本模型未返回有效的结构化产物", false, false);
    }

    private <T> T decode(String content, Class<T> output) {
        try {
            return json.readerFor(output).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue(content);
        } catch (IOException | IllegalArgumentException invalid) {
            throw invalidOutput();
        }
    }
}
