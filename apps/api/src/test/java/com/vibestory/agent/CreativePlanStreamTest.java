package com.vibestory.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.vibestory.provider.ProviderException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CreativePlanStreamTest {
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();
    private HttpServer server;

    @AfterEach void stop() {
        if (server != null) server.stop(0);
    }

    @Test void publishesModelContentBeforeTheResponseFinishes() throws Exception {
        var published = new CountDownLatch(1);
        start(exchange -> {
            var body = exchange.getResponseBody();
            body.write("""
                : keep-alive

                data: {"choices":[{"delta":{"content":"{\\"episodes\\":["},"finish_reason":null}]}

                """.getBytes(StandardCharsets.UTF_8));
            body.flush();
            try {
                if (!published.await(3, TimeUnit.SECONDS)) return;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
            body.write("""
                data: {"choices":[{"delta":{"content":"{\\"title\\":\\"海底洗衣店\\"}]}"},"finish_reason":null}]}

                data: {"choices":[{"delta":{},"finish_reason":"stop"}]}

                data: [DONE]

                """.getBytes(StandardCharsets.UTF_8));
        });

        String result = CreativePlanStream.read(client, request(Duration.ofSeconds(5)), json, text -> {
            assertThat(text).startsWith("{\"episodes\":[");
            published.countDown();
        });
        assertThat(json.readTree(result).at("/episodes/0/title").asText()).isEqualTo("海底洗衣店");
    }

    @Test void rejectsOutputTruncatedByTheTokenLimit() throws Exception {
        start(exchange -> exchange.getResponseBody().write("""
            data: {"choices":[{"delta":{"content":"{}"},"finish_reason":"length"}]}

            data: [DONE]

            """.getBytes(StandardCharsets.UTF_8)));
        assertThatThrownBy(() -> CreativePlanStream.read(client, request(Duration.ofSeconds(5)), json, text -> {}))
            .isInstanceOf(ProviderException.class).hasMessageContaining("有效的结构化产物");
    }

    @Test void boundsWaitForBodyAfterHeaders() throws Exception {
        var release = new CountDownLatch(1);
        start(exchange -> {
            exchange.getResponseBody().write(": waiting\n\n".getBytes(StandardCharsets.UTF_8));
            exchange.getResponseBody().flush();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        try {
            long started = System.nanoTime();
            assertThatThrownBy(() -> CreativePlanStream.read(client, request(Duration.ofMillis(350)), json, text -> {}))
                .isInstanceOf(java.io.IOException.class);
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
        } finally {
            release.countDown();
        }
    }

    private void start(com.sun.net.httpserver.HttpHandler handler) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            try (exchange) {
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, 0);
                handler.handle(exchange);
            }
        });
        server.start();
    }

    private HttpRequest request(Duration timeout) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/chat/completions"))
            .timeout(timeout).build();
    }
}
