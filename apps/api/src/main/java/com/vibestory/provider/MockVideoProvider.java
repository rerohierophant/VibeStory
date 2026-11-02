package com.vibestory.provider;

import com.vibestory.storage.LocalStorage;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.jcodec.api.awt.AWTSequenceEncoder;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
public class MockVideoProvider implements VideoProvider {
    @ConfigurationProperties("vibestory.mock")
    public record Properties(Duration delay, double failureRate, String failureMode) {
        public Properties {
            if (delay == null || delay.isNegative() || !Double.isFinite(failureRate) || failureRate < 0 || failureRate > 1 ||
                    !("known".equals(failureMode) || "unknown".equals(failureMode))) {
                throw new IllegalArgumentException("Mock 配置无效：delay >= 0，failure-rate 为 0..1，failure-mode 为 known/unknown");
            }
        }
    }

    private final Properties properties;
    private final LocalStorage storage;

    public MockVideoProvider(Properties properties, LocalStorage storage) { this.properties = properties; this.storage = storage; }
    @Override public Capabilities capabilities() { return new Capabilities(false, true, 3000); }

    @Override public Result generate(Request request) throws InterruptedException {
        Thread.sleep(properties.delay());
        // Deterministic fault injection, rather than flaky random test outcomes.
        double score = Long.parseUnsignedLong(request.signature().substring(0, 8), 16) / 4294967296.0;
        if (score < properties.failureRate()) {
            boolean unknown = properties.failureMode().equals("unknown");
            throw new ProviderException(unknown ? "MOCK_RESULT_UNKNOWN" : "MOCK_REJECTED",
                unknown ? "Mock 模拟外部结果未知，请确认后再重试" : "Mock 模拟可重试失败", !unknown, unknown);
        }
        // Stable, scoped key: repeating an interrupted Mock execution replaces the same local result.
        String key = "u/" + request.userId() + "/p/" + request.projectId() + "/e/" + request.episodeId() +
            "/video/" + request.taskId() + "-" + request.signature() + ".mp4";
        Path temporary = null;
        try {
            temporary = storage.temporary(key);
            var encoder = AWTSequenceEncoder.createSequenceEncoder(temporary.toFile(), 24);
            var frame = new BufferedImage(320, 180, BufferedImage.TYPE_3BYTE_BGR);
            var graphics = frame.createGraphics();
            try {
                graphics.setColor(new Color(Integer.parseInt(request.signature().substring(0, 6), 16)));
                graphics.fillRect(0, 0, 320, 180);
                graphics.setColor(Color.WHITE);
                graphics.drawString("VibeStory Mock / 3 seconds", 32, 88);
                graphics.drawString(request.signature().substring(0, 16), 32, 110);
            } finally { graphics.dispose(); }
            try {
                for (int i = 0; i < 72; i++) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                    encoder.encodeImage(frame);
                }
            } finally { encoder.finish(); }
            storage.publish(temporary, key);
            return new Result(key, 3000);
        } catch (IOException error) {
            throw new ProviderException("LOCAL_STORAGE_ERROR", "本地 Mock 视频写入失败", true, false);
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); } catch (IOException ignored) { /* Reaped with local runtime data. */ }
            }
        }
    }
}
