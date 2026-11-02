package com.vibestory.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vibestory.storage.LocalStorage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import javax.imageio.ImageIO;
import org.springframework.stereotype.Component;

/** One paid request, one image; no implicit retries, redirects, or expiring result URL downloads. */
@Component
public class MiniMaxImageProvider implements ImageProvider {
    private final ImageProperties config;
    private final ObjectMapper json;
    private final LocalStorage storage;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
    public MiniMaxImageProvider(ImageProperties config, ObjectMapper json, LocalStorage storage) {
        this.config = config; this.json = json; this.storage = storage;
    }

    @Override public Result generate(Input input, String signature) throws InterruptedException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", input.model()); body.put("prompt", input.prompt()); body.put("n", 1);
        body.put("response_format", "base64");
        body.put("aspect_ratio", input.aspectRatio()); body.put("prompt_optimizer", false);
        var sentReferences = input.references().stream().filter(r -> input.guidance().usedVersionIds().contains(r.versionId())).toList();
        if (!sentReferences.isEmpty()) {
            var ref = sentReferences.getFirst();
            try {
                byte[] bytes = storage.resource(ref.storageKey()).getContentAsByteArray();
                if (bytes.length > 10 * 1024 * 1024) throw new IOException("参考图过大");
                body.put("subject_reference", List.of(Map.of("type", "character", "image_file",
                    "data:" + ref.mediaType() + ";base64," + Base64.getEncoder().encodeToString(bytes))));
            } catch (IOException error) { throw new ProviderException("IMAGE_REFERENCE_MISSING", "提交时的参考图不可读取，请检查后重新提交", false, false); }
        }
        String endpoint = input.endpoint().replaceAll("/+$", "") + "/image_generation";
        CompletableFuture<HttpResponse<byte[]>> pending = null;
        String diagnostic = null;
        try {
            var request = HttpRequest.newBuilder(URI.create(endpoint)).timeout(config.timeout())
                .header("Content-Type", "application/json").header("Authorization", "Bearer " + config.apiKey())
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
            com.vibestory.admission.SubmissionBoundary.beforeSubmission();
            pending = http.sendAsync(request, ignored -> boundedBody());
            var response = pending.get(config.timeout().toMillis(), TimeUnit.MILLISECONDS);
            int status = response.statusCode();
            diagnostic = "{\"httpStatus\":" + status + "}";
            if (status < 200 || status >= 300)
                throw new ProviderException("IMAGE_HTTP_" + status, "图片接口返回 HTTP " + status, status == 429, status >= 500 || status == 408, diagnostic, null);
            var tree = json.readTree(response.body());
            if (tree == null || !tree.isObject()) throw new IOException("响应无效");
            var codeNode = tree.path("base_resp").path("status_code");
            if (!codeNode.isIntegralNumber()) throw new IOException("缺少供应商状态码");
            int code = codeNode.asInt();
            diagnostic = "{\"httpStatus\":" + status + ",\"providerCode\":" + code + "}";
            if (code != 0) {
                boolean unknown = !Set.of(1002, 1004, 1008, 1026, 1045, 2013, 2014, 2022, 2049).contains(code);
                throw new ProviderException("IMAGE_MINIMAX_" + code, "MiniMax 图片接口返回错误码 " + code,
                    code == 1002 || code == 1045, unknown, diagnostic, null);
            }
            var images = tree.path("data").path("image_base64");
            if (!images.isArray() || images.size() != 1) throw new IOException("未返回且仅返回一张图");
            var encoded = images.get(0);
            if (!encoded.isTextual()) throw new IOException("缺少 base64 图片");
            byte[] bytes = Base64.getDecoder().decode(encoded.asText());
            String mediaType = validateImage(bytes);
            String actual = tree.path("model").isTextual() ? tree.path("model").asText() : null;
            if (actual != null && actual.length() > 160) throw new IOException("模型名称无效");
            if (actual != null && config.apiKey() != null && !config.apiKey().isBlank()) actual=actual.replace(config.apiKey(), "[REDACTED]");
            // Keep diagnostic metadata only: never persist response base64, echoed prompts, URLs or credentials.
            return new Result(bytes, mediaType, actual, diagnostic);
        } catch (TimeoutException error) {
            throw new ProviderException("IMAGE_TIMEOUT_UNKNOWN", "图片接口超时，外部结果未知，确认后才能重试", false, true);
        } catch (ExecutionException error) {
            var cause = error.getCause();
            boolean connect = cause instanceof java.net.ConnectException || cause instanceof java.net.http.HttpConnectTimeoutException;
            throw new ProviderException(connect ? "IMAGE_CONNECTION_FAILED" : "IMAGE_IO_UNKNOWN",
                connect ? "无法连接图片接口" : "图片接口连接中断，外部结果未知", connect, !connect);
        } catch (IOException | IllegalArgumentException error) {
            throw new ProviderException("IMAGE_OUTPUT_UNKNOWN", "图片响应未通过格式或内容校验，外部结果未知，请查看诊断", false, true, diagnostic, null);
        } finally { if (pending != null && !pending.isDone()) pending.cancel(true); }
    }

    public static String validateImage(byte[] bytes) throws IOException {
        if (bytes.length == 0 || bytes.length > 16 * 1024 * 1024) throw new IOException("图片大小无效");
        try (var stream = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) throw new IOException("图片内容无效");
            var reader = readers.next();
            try {
                reader.setInput(stream, true, true);
                String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                if (!Set.of("png", "jpeg", "jpg").contains(format)) throw new IOException("图片类型无效");
                int w = reader.getWidth(0), h = reader.getHeight(0);
                if (w < 1 || h < 1 || w > 4096 || h > 4096 || (long) w * h > 16_000_000) throw new IOException("图片像素过大");
                var bitmap = reader.read(0); bitmap.flush();
                return format.equals("png") ? "image/png" : "image/jpeg";
            } finally { reader.dispose(); }
        }
    }

    private static HttpResponse.BodySubscriber<byte[]> boundedBody() {
        var delegate = HttpResponse.BodySubscribers.ofByteArray();
        return new HttpResponse.BodySubscriber<>() {
            private Flow.Subscription subscription;
            private long size;
            private boolean failed;
            public CompletionStage<byte[]> getBody() { return delegate.getBody(); }
            public void onSubscribe(Flow.Subscription value) { subscription = value; delegate.onSubscribe(value); }
            public void onNext(List<ByteBuffer> buffers) {
                for (var buffer : buffers) size += buffer.remaining();
                if (size > 24 * 1024 * 1024) { failed = true; subscription.cancel(); delegate.onError(new IOException("响应过大")); }
                else if (!failed) delegate.onNext(buffers);
            }
            public void onError(Throwable error) { if (!failed) delegate.onError(error); }
            public void onComplete() { if (!failed) delegate.onComplete(); }
        };
    }
}
