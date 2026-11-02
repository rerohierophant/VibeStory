package com.vibestory.provider;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("vibestory.text")
public record TextProperties(String provider, String baseUrl, String model, String apiKey,
                             Duration timeout, int maxTokens, boolean disableThinking) {
    public TextProperties {
        if (!("mock".equals(provider) || "openai-compatible".equals(provider)))
            throw new IllegalArgumentException("TEXT_PROVIDER 为 mock 或 openai-compatible");
        var uri = URI.create(baseUrl);
        boolean local = ListHosts.isLocal(uri.getHost());
        if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()) && local) ||
                uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null)
            throw new IllegalArgumentException("文本接口地址须为 HTTPS，或本机 HTTP 测试接口；不能含凭证、查询串或片段");
        if (timeout == null || timeout.isNegative() || timeout.isZero() || maxTokens < 1 || maxTokens > 32768 ||
                model == null || model.isBlank() || model.length() > 160)
            throw new IllegalArgumentException("文本模型、超时或输出长度配置无效");
        if (provider.equals("openai-compatible") && (apiKey == null || apiKey.isBlank()))
            throw new IllegalArgumentException("真实文本模型需要 TEXT_API_KEY");
    }
    @Override public String toString() { return "TextProperties[provider=" + provider + ", model=" + model + ", apiKey=REDACTED]"; }
    private static class ListHosts {
        static boolean isLocal(String host) { return "localhost".equals(host) || "127.0.0.1".equals(host) || "[::1]".equals(host); }
    }
}
