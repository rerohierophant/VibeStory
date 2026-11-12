package com.vibestory.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

final class WorkspaceApi {
    static final class Failure extends RuntimeException {
        private final int status;

        Failure(int status, String message) {
            super(message);
            this.status = status;
        }

        int status() { return status; }
    }

    private record Csrf(String header, String value) {}

    private final ObjectMapper json;
    private final URI origin;
    private final String email;
    private final String password;
    private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER);
    private final HttpClient http = HttpClient.newBuilder().cookieHandler(cookies).proxy(ProxySelector.of(null))
        .connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
    private Csrf session;

    WorkspaceApi(ObjectMapper json, String url, String email, String password) {
        this.json = json;
        this.origin = loopbackOrigin(url);
        this.email = email;
        this.password = password;
    }

    synchronized JsonNode get(String path) {
        ensureLogin();
        try {
            return request("GET", path, null, null);
        } catch (Failure expired) {
            if (expired.status() != 401) throw expired;
            session = null;
            ensureLogin();
            return request("GET", path, null, null);
        }
    }

    JsonNode post(String path, Map<String, ?> body) { return write("POST", path, body); }

    JsonNode put(String path, Map<String, ?> body) { return write("PUT", path, body); }

    private synchronized JsonNode write(String method, String path, Map<String, ?> body) {
        ensureLogin();
        try {
            return request(method, path, body, session);
        } catch (Failure failure) {
            if (failure.status() == 401 || failure.status() == 403) session = null;
            throw failure;
        }
    }

    void requireMock(String providerType) {
        if (!get("/api/runtime").path(providerType).path("provider").asText().equals("mock"))
            throw new Failure(409, "请将 VibeStory 的 " + providerType + " Provider 切换为 Mock 后再调用此工具。");
    }

    private void ensureLogin() {
        if (session != null) return;
        if (email == null || email.isBlank() || password == null || password.isBlank())
            throw new Failure(401, "请在本机 .env 配置 VibeStory 登录账号和密码。");
        cookies.getCookieStore().removeAll();
        var before = csrf();
        request("POST", "/api/auth/login", Map.of("email", email, "password", password), before);
        session = csrf();
    }

    private Csrf csrf() {
        var response = request("GET", "/api/auth/csrf", null, null);
        if (!response.path("headerName").isTextual() || !response.path("token").isTextual())
            throw new Failure(502, "后端未返回有效的会话令牌。");
        return new Csrf(response.get("headerName").textValue(), response.get("token").textValue());
    }

    private JsonNode request(String method, String path, Map<String, ?> body, Csrf csrf) {
        try {
            var builder = HttpRequest.newBuilder(origin.resolve(path)).timeout(Duration.ofSeconds(12))
                .header("Accept", "application/json, application/problem+json");
            if (body != null) builder.header("Content-Type", "application/json");
            if (csrf != null) builder.header(csrf.header(), csrf.value());
            var payload = body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body));
            var response = http.send(builder.method(method, payload).build(), HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300) throw failure(response);
            if (response.body().length == 0) return json.createObjectNode();
            return json.readTree(response.body());
        } catch (IOException error) {
            String message = method.equals("GET") ? "读取 VibeStory 失败，请检查本机服务。" :
                "VibeStory 请求未得到完整响应，请先查询当前状态再决定是否重试。";
            throw new Failure(502, message);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new Failure(503, "VibeStory 请求已中断，请查询当前状态。");
        }
    }

    private Failure failure(HttpResponse<byte[]> response) {
        int status = response.statusCode();
        String message = switch (status) {
            case 401 -> "VibeStory 登录失败或会话已过期，请检查本机账号后重试。";
            case 403 -> "当前账号没有操作权限，或会话校验失败。";
            case 404 -> "资源不存在，或不属于当前账号的访问范围。";
            case 400, 409 -> backendDetail(response.body());
            default -> "VibeStory 后端请求失败。";
        };
        return new Failure(status, message);
    }

    private String backendDetail(byte[] body) {
        try {
            var problem = json.readTree(body);
            String detail = problem == null ? "" : problem.path("detail").asText("");
            if (!detail.isBlank() && detail.length() <= 400) return detail.replace(password, "[REDACTED]");
        } catch (IOException ignored) {
            // Keep malformed upstream error bodies out of MCP results.
        }
        return "请求未通过 VibeStory 的业务校验。";
    }

    private static URI loopbackOrigin(String url) {
        var uri = URI.create(url);
        if (!"http".equals(uri.getScheme()) || uri.getHost() == null ||
            !Set.of("127.0.0.1", "[::1]").contains(uri.getHost()) || uri.getUserInfo() != null ||
            uri.getQuery() != null || uri.getFragment() != null || !Set.of("", "/").contains(uri.getPath()))
            throw new IllegalArgumentException("VIBESTORY_MCP_URL 必须是本机 HTTP 地址，例如 http://127.0.0.1:8080");
        return uri;
    }
}
