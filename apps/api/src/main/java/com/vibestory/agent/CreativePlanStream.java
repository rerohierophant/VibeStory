package com.vibestory.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vibestory.provider.ProviderException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

final class CreativePlanStream {
    private static final int MAX_CONTENT = 1024 * 1024;
    private static final long PREVIEW_INTERVAL = TimeUnit.MILLISECONDS.toNanos(300);
    private static final ScheduledThreadPoolExecutor DEADLINES = new ScheduledThreadPoolExecutor(
        1, Thread.ofPlatform().daemon().name("creative-stream-deadline").factory());
    static { DEADLINES.setRemoveOnCancelPolicy(true); }

    private CreativePlanStream() {}

    static String read(HttpClient http, HttpRequest request, ObjectMapper json, Consumer<String> preview)
        throws IOException, InterruptedException {
        long deadline = System.nanoTime() + request.timeout().orElseThrow().toNanos();
        var response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (var body = response.body()) {
            CreativeModel.checkStatus(response.statusCode());
            var timer = DEADLINES.schedule(() -> {
                try { body.close(); } catch (IOException ignored) { }
            }, Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            try {
                return readEvents(new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8)), json, preview, deadline);
            } finally {
                timer.cancel(false);
            }
        }
    }

    private static String readEvents(BufferedReader reader, ObjectMapper json, Consumer<String> preview, long deadline)
        throws IOException {
        var output = new StringBuilder();
        String finish = "";
        long lastPreview = 0;
        for (String line; (line = reader.readLine()) != null;) {
            long now = System.nanoTime();
            if (now - deadline >= 0) throw new HttpTimeoutException("Creative plan stream timed out");
            if (!line.startsWith("data:")) continue;
            String data = line.substring(5).strip();
            if ("[DONE]".equals(data)) {
                if (!"stop".equals(finish)) throw CreativeModel.invalidOutput();
                return output.toString();
            }
            if (data.length() > MAX_CONTENT) throw CreativeModel.invalidOutput();
            var chunk = json.readTree(data);
            if (chunk == null) throw CreativeModel.invalidOutput();
            if (chunk.hasNonNull("error"))
                throw new ProviderException("CREATIVE_TEXT_HTTP_UNKNOWN", "文本服务中断，未返回完整草案", false, true);
            var choice = chunk.path("choices").path(0);
            if (choice.hasNonNull("finish_reason")) finish = choice.path("finish_reason").asText();
            String delta = choice.path("delta").path("content").asText("");
            if (delta.isEmpty()) continue;
            if (output.length() + delta.length() > MAX_CONTENT) throw CreativeModel.invalidOutput();
            output.append(delta);
            if (lastPreview == 0 || now - lastPreview >= PREVIEW_INTERVAL) {
                preview.accept(output.toString());
                lastPreview = now;
            }
        }
        throw new IOException("Creative plan stream ended before [DONE]");
    }
}
