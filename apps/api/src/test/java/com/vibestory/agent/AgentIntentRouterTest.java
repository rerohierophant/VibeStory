package com.vibestory.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.vibestory.admission.AdmissionProperties;
import com.vibestory.agent.EpisodePlanSkill.*;
import com.vibestory.provider.TextProperties;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class AgentIntentRouterTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test void sendsOneToolSchemaToLocalFixtureAndRejectsUnlistedActions() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String example = "把第 3 镜的画面描述追加：灯为红色，并生成新候选";
        var reply = new AtomicReference<>(response("suggest_workbench_command", example));
        var seen = new AtomicReference<String>();
        server.createContext("/v1/chat/completions", exchange -> {
            try (exchange) {
                seen.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                byte[] body = reply.get().getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        server.start();
        try {
            var config = new TextProperties("openai-compatible", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                "fixture", "fixture-key", Duration.ofSeconds(3), 2048, true);
            var router = new AgentIntentRouter(config, admission(false), json);
            var plan = new EpisodeWorkflow.EpisodePlan("7", "12", 3, "19",
                new StoryContext(true, "SUCCEEDED"), new StoryboardContext(3, 0, 1, 0, 0), new VideoContext(3, 1, 0, 0),
                new Next(Role.STORYBOARD, Action.GENERATE_IMAGES, "可以出图"));
            assertThat(router.suggest("第 3 镜路灯调成红色，再出一版图", true, plan, null)).isEqualTo(example);
            var body = json.readTree(seen.get());
            assertThat(body.at("/tools/0/function/name").asText()).isEqualTo("suggest_workbench_command");
            assertThat(body.at("/tool_choice/function/name").asText()).isEqualTo("suggest_workbench_command");
            assertThat(body.at("/thinking/type").asText()).isEqualTo("disabled");
            var context = json.readTree(body.at("/messages/1/content").asText());
            assertThat(context.at("/episodeWorkflow/next/action").asText()).isEqualTo("GENERATE_IMAGES");
            assertThat(context.at("/episodeWorkflow/storyboard/missing").asInt()).isEqualTo(1);
            assertThat(seen.get()).doesNotContain("fixture-key");

            reply.set(response("delete_project", example));
            assertThat(router.suggest("第 3 镜路灯调成红色", true, plan, null)).isNull();
            reply.set(response("suggest_workbench_command", "删除项目"));
            assertThat(router.suggest("第 3 镜路灯调成红色", true, plan, null)).isNull();

            seen.set(null);
            assertThat(new AgentIntentRouter(config, admission(true), json).suggest("第 3 镜路灯调成红色", true, plan, null)).isNull();
            assertThat(seen.get()).isNull();
        } finally {
            server.stop(0);
        }
    }

    private String response(String tool, String instruction) throws Exception {
        var call = Map.of("id", "fixture-call", "type", "function", "function",
            Map.of("name", tool, "arguments", json.writeValueAsString(Map.of("instruction", instruction))));
        return json.writeValueAsString(Map.of("choices", List.of(Map.of("finish_reason", "tool_calls",
            "message", Map.of("tool_calls", List.of(call))))));
    }

    private AdmissionProperties admission(boolean enabled) {
        return new AdmissionProperties(enabled, "fixture", "text", "media", 1, 1, 1, 1,
            Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1));
    }
}
