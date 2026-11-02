package com.vibestory.provider;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("vibestory.image")
public record ImageProperties(String provider, String baseUrl, String model, String apiKey, Duration timeout) {
    public ImageProperties {
        if (!java.util.List.of("mock", "minimax").contains(provider)) throw new IllegalArgumentException("IMAGE_PROVIDER 为 mock 或 minimax");
        var uri = URI.create(baseUrl);
        boolean local = "localhost".equals(uri.getHost()) || "127.0.0.1".equals(uri.getHost()) || "[::1]".equals(uri.getHost());
        if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()) && local) || uri.getHost() == null ||
            uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null)
            throw new IllegalArgumentException("图片接口地址须为 HTTPS 或本机 HTTP，不能含凭证、查询串或片段");
        if (timeout == null || timeout.isZero() || timeout.isNegative() || model == null || model.isBlank() || model.length() > 160)
            throw new IllegalArgumentException("图片模型或超时无效");
        if (!provider.equals("mock") && (apiKey == null || apiKey.isBlank())) throw new IllegalArgumentException("真实图片模型需要 IMAGE_API_KEY");
    }
    @Override public String toString() { return "ImageProperties[provider=" + provider + ", model=" + model + ", apiKey=REDACTED]"; }
}
