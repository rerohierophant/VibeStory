package com.vibestory.provider;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("vibestory.video")
public record VideoProperties(String provider, String baseUrl, String model, String apiKey, Duration timeout,
                              Duration pollInterval, Duration generationTimeout) {
    public VideoProperties {
        if (!java.util.List.of("mock","minimax").contains(provider)) throw new IllegalArgumentException("VIDEO_PROVIDER 为 mock 或 minimax");
        var uri=URI.create(baseUrl);
        boolean local="localhost".equals(uri.getHost()) || "127.0.0.1".equals(uri.getHost()) || "[::1]".equals(uri.getHost());
        if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()) && local) || uri.getHost()==null || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null)
            throw new IllegalArgumentException("视频接口地址须为 HTTPS 或本机 HTTP，不能含凭证、查询串或片段");
        if (!"MiniMax-Hailuo-2.3-Fast".equals(model)) throw new IllegalArgumentException("当前视频仅支持 MiniMax-Hailuo-2.3-Fast");
        if (timeout==null || timeout.isNegative() || timeout.isZero() || pollInterval==null || pollInterval.isNegative() || pollInterval.isZero() || generationTimeout==null || generationTimeout.isNegative() || generationTimeout.isZero())
            throw new IllegalArgumentException("视频超时与轮询间隔必须为正数");
        if (provider.equals("minimax") && (apiKey==null || apiKey.isBlank())) throw new IllegalArgumentException("MiniMax 视频需要 VIDEO_API_KEY 或 IMAGE_API_KEY");
    }
    @Override public String toString() { return "VideoProperties[provider="+provider+", model="+model+", apiKey=REDACTED]"; }
}
