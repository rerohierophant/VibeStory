package com.vibestory.agent;

import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.TreeMap;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Reassembles streamed tool calls before the runtime can validate or execute them. */
final class AgentReplyStream {
    private static final int MAX_CONTENT = 1024 * 1024;
    private static final ScheduledThreadPoolExecutor DEADLINES = new ScheduledThreadPoolExecutor(
        1, Thread.ofPlatform().daemon().name("agent-stream-deadline").factory());
    static { DEADLINES.setRemoveOnCancelPolicy(true); }

    private AgentReplyStream() {}

    static AgentLoopModel.Reply read(HttpClient http, HttpRequest request, ObjectMapper json, Consumer<String> delta)
        throws IOException, InterruptedException {
        long deadline = System.nanoTime() + request.timeout().orElseThrow().toNanos();
        var response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (var body = response.body()) {
            if (response.statusCode() >= 500)
                throw new ProviderException("AGENT_HTTP_UNKNOWN", "模型请求结果未知", false, true);
            if (response.statusCode() != 200)
                throw new ProviderException("AGENT_HTTP_REJECTED", "模型请求被拒绝", false, false);
            var timer = DEADLINES.schedule(() -> {
                try { body.close(); } catch (IOException ignored) { }
            }, Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            try {
                return readEvents(new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8)), json, delta, deadline);
            } finally {
                timer.cancel(false);
            }
        }
    }

    static AgentLoopModel.Reply readEvents(BufferedReader reader, ObjectMapper json, Consumer<String> output, long deadline)
        throws IOException {
        var text = new StringBuilder();
        var calls = new TreeMap<Integer, Call>();
        JsonNode usage = json.createObjectNode();
        String finish = "";
        int size = 0;
        int shown = 0;
        for (String line; (line = reader.readLine()) != null;) {
            if (System.nanoTime() - deadline >= 0) throw new HttpTimeoutException("Agent reply stream timed out");
            if (!line.startsWith("data:")) continue;
            String data = line.substring(5).strip();
            if (data.equals("[DONE]")) {
                if (!(finish.equals("stop") && calls.isEmpty() || finish.equals("tool_calls") && !calls.isEmpty()))
                    throw invalid();
                var message = json.createObjectNode().put("role", "assistant").put("content", text.toString());
                if (!calls.isEmpty()) {
                    var tools = message.putArray("tool_calls");
                    for (var call : calls.values()) {
                        tools.addObject().put("id", call.id.toString()).put("type", call.type)
                            .putObject("function").put("name", call.name.toString()).put("arguments", call.arguments.toString());
                    }
                }
                return new AgentLoopModel.Reply(message, usage);
            }
            if (data.length() > MAX_CONTENT - size) throw invalid();
            size += data.length();
            var chunk = json.readTree(data);
            if (chunk == null || !chunk.isObject()) throw invalid();
            if (chunk.hasNonNull("error")) throw new IOException("Agent provider interrupted the reply stream");
            if (chunk.hasNonNull("usage")) usage = chunk.path("usage");
            var choices = chunk.path("choices");
            if (choices.isEmpty()) continue;
            if (!choices.isArray() || choices.size() != 1 || !finish.isEmpty()) throw invalid();
            var choice = choices.get(0);
            if (choice.hasNonNull("finish_reason")) finish = choice.path("finish_reason").asText();
            var delta = choice.path("delta");
            for (var item : delta.path("tool_calls")) {
                var index = item.path("index");
                if (!index.isIntegralNumber() || !index.canConvertToInt() || index.asInt() < 0 || index.asInt() >= 4) throw invalid();
                calls.computeIfAbsent(index.asInt(), ignored -> new Call()).append(item);
            }
            String part = delta.path("content").asText("");
            text.append(part);
            if (calls.isEmpty() && !part.isEmpty() && shown < 2000) {
                int count = Math.min(2000 - shown, part.codePointCount(0, part.length()));
                output.accept(part.substring(0, part.offsetByCodePoints(0, count)));
                shown += count;
            }
        }
        throw new IOException("Agent reply stream ended before [DONE]");
    }

    private static ProviderException invalid() {
        return new ProviderException("AGENT_INVALID_STREAM", "模型未返回完整有效的回复", false, false);
    }

    private static final class Call {
        private final StringBuilder id = new StringBuilder();
        private String type = "";
        private final StringBuilder name = new StringBuilder();
        private final StringBuilder arguments = new StringBuilder();

        void append(JsonNode item) {
            id.append(item.path("id").asText(""));
            if (item.hasNonNull("type")) type = item.path("type").asText();
            var function = item.path("function");
            name.append(function.path("name").asText(""));
            arguments.append(function.path("arguments").asText(""));
        }
    }
}
