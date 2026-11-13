package com.vibestory.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.vibestory.provider.ProviderException;
import com.vibestory.provider.TextProperties;
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

/** The Mock and HTTP adapters return the same assistant/tool_calls wire protocol. */
@Component
public class AgentLoopModel {
    public record Reply(JsonNode message, JsonNode usage) {}
    private final ObjectMapper json;
    private final TextProperties config;
    private final MockAgentPolicy mock;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NEVER).build();

    AgentLoopModel(ObjectMapper json, TextProperties config, MockAgentPolicy mock) {
        this.json = json;
        this.config = config;
        this.mock = mock;
    }

    JsonNode request(ArrayNode messages, List<Map<String, Object>> tools) {
        return json.valueToTree(Map.of("model", config.model(), "stream", false, "max_tokens", config.maxTokens(),
            "thinking", Map.of("type", "disabled"), "messages", messages, "tools", tools, "tool_choice", "auto"));
    }

    public Reply complete(String role, JsonNode request) throws IOException, InterruptedException {
        if (config.provider().equals("mock")) return new Reply(mock.respond(role, request.path("messages")), json.createObjectNode());
        var response = http.send(call(request), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 500) throw new ProviderException("AGENT_HTTP_UNKNOWN", "模型请求结果未知", false, true);
        if (response.statusCode() != 200 || response.body().length() > 1024 * 1024)
            throw new ProviderException("AGENT_HTTP_REJECTED", "模型请求被拒绝或响应过大", false, false);
        var root = json.readTree(response.body());
        var choices = root.path("choices");
        if (!choices.isArray() || choices.size() != 1) throw new ProviderException("AGENT_INVALID", "模型响应无效", false, false);
        var choice = choices.get(0);
        if (!List.of("stop", "tool_calls").contains(choice.path("finish_reason").asText()))
            throw new ProviderException("AGENT_TRUNCATED", "模型响应未完整结束", false, false);
        return new Reply(choice.path("message"), root.path("usage"));
    }

    Reply completeStreaming(String role, JsonNode request, Consumer<String> delta) throws IOException, InterruptedException {
        if (!config.provider().equals("mock")) return AgentReplyStream.read(http, call(request), json, delta);
        var reply = complete(role, request);
        if (!reply.message().path("tool_calls").isArray()) {
            String content = reply.message().path("content").asText("");
            int points = content.codePointCount(0, content.length());
            int step = Math.max(3, (points + 23) / 24);
            for (int offset = 0; offset < points; offset += step) {
                int start = content.offsetByCodePoints(0, offset);
                int end = content.offsetByCodePoints(start, Math.min(step, points - offset));
                delta.accept(content.substring(start, end));
                if (end < content.length()) Thread.sleep(80);
            }
        }
        return reply;
    }

    private HttpRequest call(JsonNode request) throws IOException {
        return HttpRequest.newBuilder(URI.create(config.baseUrl().replaceAll("/+$", "") + "/chat/completions"))
            .timeout(config.timeout()).header("Authorization", "Bearer " + config.apiKey())
            .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(request))).build();
    }
}
