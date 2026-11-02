package com.vibestory.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.vibestory.provider.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;

class OpenAiCompatibleTextProviderTest {
    private HttpServer server;
    private java.util.concurrent.ExecutorService executor;
    private final ObjectMapper json = new ObjectMapper();
    private String endpoint;
    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicReference<String> response = new AtomicReference<>();
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private final AtomicReference<String> authorization = new AtomicReference<>();
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicInteger delay = new AtomicInteger();
    private static final String KEY = "unit-test-secret";
    private static final String DRAFT = "{\"description\":\"沈夜走进便利店\",\"dialogue\":\"你好\",\"sceneName\":\"便利店\",\"shotSize\":\"近景\",\"cameraMove\":\"固定\",\"durationMs\":3000,\"characterNames\":[\"沈夜\"]}";

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/v1/chat/completions", exchange -> {
            calls.incrementAndGet();
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            try { Thread.sleep(delay.get()); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            byte[] body = response.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            if (status.get() == 302) exchange.getResponseHeaders().set("Location", "https://example.invalid/never-follow");
            try { exchange.sendResponseHeaders(status.get(), body.length); exchange.getResponseBody().write(body); }
            catch (java.io.IOException ignored) { /* Client timeout closes its own exchange. */ }
            finally { exchange.close(); }
        });
        server.start();
        endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/";
        response.set(envelope("{\"shots\":[" + DRAFT + "]}", "stop"));
    }

    @AfterEach void stop() { server.stop(0); executor.shutdownNow(); }

    @Test void sendsCompatibleJsonAndUsesFrozenModelWithCredentialsOnlyInHeader() throws Exception {
        var result = provider(Duration.ofSeconds(3)).generate(input());
        assertThat(result.shots().getFirst().characterNames()).containsExactly("沈夜");
        assertThat(result.actualModel()).isEqualTo("fixture-model-version");
        var body = json.readTree(requestBody.get());
        assertThat(body.get("model").asText()).isEqualTo("deepseek-flash");
        assertThat(body.at("/response_format/type").asText()).isEqualTo("json_object");
        assertThat(body.at("/thinking/type").asText()).isEqualTo("disabled");
        assertThat(authorization.get()).isEqualTo("Bearer " + KEY);
        assertThat(requestBody.get()).doesNotContain(KEY);
        assertThat(new TextProperties("openai-compatible", endpoint, "deepseek-flash", KEY, Duration.ofSeconds(3), 8192, true).toString()).doesNotContain(KEY);
    }

    @Test void rejectsMarkdownUnknownFieldsAndInvalidTypesWithRawDiagnostics() throws Exception {
        for (String content : List.of("```json\n{\"shots\":[]}\n```", "{\"shots\":[" + DRAFT + "],\"extra\":true}",
                "{\"shots\":[" + DRAFT.replace("3000", "3000.1") + "]}",
                "{\"shots\":[" + DRAFT.replace("3000", "\"3000\"") + "]}",
                "{\"shots\":[" + DRAFT.replace("\"近景\"", "\"错误景别\"") + "]}")) {
            response.set(envelope(content, "stop"));
            assertThatThrownBy(() -> provider(Duration.ofSeconds(3)).generate(input())).isInstanceOfSatisfying(ProviderException.class, error -> {
                assertThat(error.code()).isEqualTo("TEXT_INVALID_OUTPUT");
                assertThat(error.unknown()).isFalse();
                assertThat(error.rawResponse()).isNotBlank();
            });
        }
    }

    @Test void emptyOrTruncatedContentCannotBeAppliedAsSuccess() throws Exception {
        response.set(envelope("   ", "stop"));
        assertThatThrownBy(() -> provider(Duration.ofSeconds(3)).generate(input())).isInstanceOfSatisfying(ProviderException.class,
            error -> assertThat(error.code()).isEqualTo("TEXT_INVALID_OUTPUT"));
        response.set(envelope("{\"shots\":[", "length"));
        assertThatThrownBy(() -> provider(Duration.ofSeconds(3)).generate(input())).isInstanceOfSatisfying(ProviderException.class,
            error -> assertThat(error.code()).isEqualTo("TEXT_OUTPUT_INCOMPLETE"));
    }

    @Test void providerErrorsDistinguishRejectionRateLimitAndUnknownExecution() {
        for (int code : List.of(401, 429, 503)) {
            status.set(code); response.set("{\"error\":\"fixture\"}");
            assertThatThrownBy(() -> provider(Duration.ofSeconds(3)).generate(input())).isInstanceOfSatisfying(ProviderException.class, error -> {
                assertThat(error.code()).isEqualTo("TEXT_HTTP_" + code);
                assertThat(error.retryable()).isEqualTo(code == 429);
                assertThat(error.unknown()).isEqualTo(code >= 500);
            });
        }
    }

    @Test void timeoutIsUnknownAndNotAutomaticallyRetryable() {
        delay.set(500);
        assertThatThrownBy(() -> provider(Duration.ofMillis(100)).generate(input())).isInstanceOfSatisfying(ProviderException.class, error -> {
            assertThat(error.code()).isEqualTo("TEXT_TIMEOUT_UNKNOWN");
            assertThat(error.unknown()).isTrue();
            assertThat(error.retryable()).isFalse();
        });
    }

    @Test void diagnosticsRedactCredentialEvenIfUpstreamEchoesIt() throws Exception {
        response.set(envelope("{\"shots\":[],\"echo\":\"" + KEY + "\"}", "stop"));
        assertThatThrownBy(() -> provider(Duration.ofSeconds(3)).generate(input())).isInstanceOfSatisfying(ProviderException.class, error -> {
            assertThat(error.rawResponse()).doesNotContain(KEY).contains("[REDACTED]");
            assertThat(error.getMessage()).doesNotContain(KEY);
        });
    }

    @Test void redirectIsNotFollowedAndSecretsCannotBeSentToRedirectTarget() {
        status.set(302);
        assertThatThrownBy(() -> provider(Duration.ofSeconds(3)).generate(input())).isInstanceOfSatisfying(ProviderException.class,
            error -> assertThat(error.code()).isEqualTo("TEXT_HTTP_302"));
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test void oversizedServerErrorStillHasUnknownExecutionOutcome() {
        status.set(503); response.set("x".repeat(1024 * 1024 + 1));
        assertThatThrownBy(() -> provider(Duration.ofSeconds(3)).generate(input())).isInstanceOfSatisfying(ProviderException.class, error -> {
            assertThat(error.code()).isEqualTo("TEXT_RESPONSE_TOO_LARGE");
            assertThat(error.unknown()).isTrue();
            assertThat(error.retryable()).isFalse();
            assertThat(error.rawResponse()).hasSize(1024 * 1024);
        });
    }

    private OpenAiCompatibleTextProvider provider(Duration timeout) {
        return new OpenAiCompatibleTextProvider(new TextProperties("openai-compatible", endpoint, "other-live-model", KEY, timeout, 8192, true), json, new ShotDraftValidation());
    }
    private TextProvider.Input input() {
        return new TextProvider.Input("BREAKDOWN", 1, "openai-compatible", endpoint, "deepseek-flash", "breakdown-v1", 0, 0,
            "原样剧本\n第二段。", List.of(), 8192, true);
    }
    private String envelope(String content, String reason) throws Exception {
        return json.writeValueAsString(Map.of("model", "fixture-model-version", "choices", List.of(Map.of("finish_reason", reason, "message", Map.of("content", content)))));
    }
}
