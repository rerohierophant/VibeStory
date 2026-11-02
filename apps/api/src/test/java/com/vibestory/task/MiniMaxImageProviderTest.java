package com.vibestory.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.vibestory.provider.*;
import com.vibestory.storage.LocalStorage;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class MiniMaxImageProviderTest {
    @TempDir java.nio.file.Path root;
    private final ObjectMapper json=new ObjectMapper();
    private HttpServer server;
    private java.util.concurrent.ExecutorService executor;
    private String endpoint, base64;
    private LocalStorage storage;
    private final AtomicReference<String> response=new AtomicReference<>(), request=new AtomicReference<>(), path=new AtomicReference<>(), auth=new AtomicReference<>();
    private final AtomicInteger code=new AtomicInteger(200), calls=new AtomicInteger(), delay=new AtomicInteger();
    private static final String KEY="fixture-image-secret";

    @BeforeEach void setup() throws Exception {
        storage=new LocalStorage(root.toString());
        var bytes=new ByteArrayOutputStream(); ImageIO.write(new BufferedImage(16,16,BufferedImage.TYPE_INT_RGB),"png",bytes);
        base64=Base64.getEncoder().encodeToString(bytes.toByteArray()); storage.put("reference.png",bytes.toByteArray());
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        executor=Executors.newVirtualThreadPerTaskExecutor(); server.setExecutor(executor);
        server.createContext("/",exchange->{
            calls.incrementAndGet(); path.set(exchange.getRequestURI().getPath());
            request.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8)); auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            try { Thread.sleep(delay.get()); } catch(InterruptedException error) { Thread.currentThread().interrupt(); }
            byte[] body=response.get().getBytes(StandardCharsets.UTF_8);
            if(code.get()==302) exchange.getResponseHeaders().set("Location",endpoint+"/must-not-follow");
            try { exchange.sendResponseHeaders(code.get(),body.length); exchange.getResponseBody().write(body); }
            catch(java.io.IOException ignored) {} finally { exchange.close(); }
        });
        server.start(); endpoint="http://127.0.0.1:"+server.getAddress().getPort()+"/v1";
        response.set("{\"base_resp\":{\"status_code\":0},\"data\":{\"image_base64\":[\""+base64+"\"]}}");
    }
    @AfterEach void stop() { server.stop(0); executor.shutdownNow(); }

    @Test void minimaxUsesOneFrozenCharacterReferenceAndOneImageWithoutLeakingBodyInDiagnostics() throws Exception {
        var result=provider("minimax",Duration.ofSeconds(3)).generate(input("minimax",true),"signature");
        assertThat(result.mediaType()).isEqualTo("image/png");
        var body=json.readTree(request.get());
        assertThat(body.path("n").asInt()).isEqualTo(1);
        assertThat(body.path("response_format").asText()).isEqualTo("base64");
        assertThat(body.path("subject_reference").size()).isEqualTo(1);
        assertThat(body.at("/subject_reference/0/image_file").asText()).isEqualTo("data:image/png;base64,"+base64);
        assertThat(body.path("model").asText()).isEqualTo("frozen-model");
        assertThat(path.get()).isEqualTo("/v1/image_generation"); assertThat(auth.get()).isEqualTo("Bearer "+KEY);
        assertThat(request.get()).doesNotContain(KEY);
        assertThat(result.diagnostics()).doesNotContain(base64,KEY);
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test void minimaxTextToImageOmitsReferenceAndRetainsRequestedAspectRatio() throws Exception {
        var result=provider("minimax",Duration.ofSeconds(3)).generate(input("minimax",false),"signature");
        assertThat(path.get()).isEqualTo("/v1/image_generation");
        var body=json.readTree(request.get());
        assertThat(body.path("response_format").asText()).isEqualTo("base64");
        assertThat(body.path("aspect_ratio").asText()).isEqualTo("16:9");
        assertThat(body.has("subject_reference")).isFalse();
        assertThat(result.mediaType()).isEqualTo("image/png");
    }

    @Test void httpStatusesDistinguishRejectedRateLimitedAndUnknownWithoutRetries() {
        for(int status:List.of(401,429,408,503)) {
            code.set(status); response.set("{\"error\":\""+KEY+"\"}");
            assertThatThrownBy(()->provider("minimax",Duration.ofSeconds(3)).generate(input("minimax",false),"signature"))
                .isInstanceOfSatisfying(ProviderException.class,error->{
                    assertThat(error.code()).isEqualTo("IMAGE_HTTP_"+status);
                    assertThat(error.unknown()).isEqualTo(status>=500 || status==408);
                    assertThat(error.retryable()).isEqualTo(status==429);
                    assertThat(error.rawResponse()).doesNotContain(KEY);
                });
        }
        assertThat(calls.get()).isEqualTo(4);
    }

    @Test void minimaxBusinessErrorsAreNotSuccessEvenWhenHttpIs200() {
        for(int status:List.of(1002,1004,1008,1001,1026,2013)) {
            response.set("{\"base_resp\":{\"status_code\":"+status+",\"status_msg\":\""+KEY+"\"}}");
            assertThatThrownBy(()->provider("minimax",Duration.ofSeconds(3)).generate(input("minimax",false),"signature"))
                .isInstanceOfSatisfying(ProviderException.class,error->{
                    assertThat(error.code()).isEqualTo("IMAGE_MINIMAX_"+status);
                    assertThat(error.unknown()).isEqualTo(status==1001);
                    assertThat(error.retryable()).isEqualTo(status==1002);
                    assertThat(error.rawResponse()).doesNotContain(KEY);
                });
        }
    }

    @Test void malformedEmptyOrInvalidImageResultsAreUnknownToAvoidAccidentalRepurchase() {
        for(String body:List.of("not-json","{}","{\"base_resp\":{\"status_code\":0},\"data\":{\"image_base64\":[]}}",
            "{\"base_resp\":{\"status_code\":0},\"data\":{\"image_base64\":[\"aW52YWxpZA==\"]}}")) {
            response.set(body);
            assertThatThrownBy(()->provider("minimax",Duration.ofSeconds(3)).generate(input("minimax",false),"signature"))
                .isInstanceOfSatisfying(ProviderException.class,error->{ assertThat(error.unknown()).isTrue(); assertThat(error.retryable()).isFalse(); });
        }
    }

    @Test void timeoutDoesNotRetryAndRedirectNeverReceivesCredentials() {
        delay.set(500);
        assertThatThrownBy(()->provider("minimax",Duration.ofMillis(80)).generate(input("minimax",false),"signature"))
            .isInstanceOfSatisfying(ProviderException.class,error->assertThat(error.unknown()).isTrue());
        delay.set(0); code.set(302);
        assertThatThrownBy(()->provider("minimax",Duration.ofSeconds(3)).generate(input("minimax",false),"signature"))
            .isInstanceOfSatisfying(ProviderException.class,error->assertThat(error.code()).isEqualTo("IMAGE_HTTP_302"));
        assertThat(calls.get()).isBetween(1,2);
    }

    @Test void missingReferenceFailsBeforeCallingPaidEndpointAndConfigurationChangesAreFenced() throws Exception {
        storage.deleteTree("reference.png");
        assertThatThrownBy(()->provider("minimax",Duration.ofSeconds(3)).generate(input("minimax",true),"signature"))
            .isInstanceOfSatisfying(ProviderException.class,error->{ assertThat(error.code()).isEqualTo("IMAGE_REFERENCE_MISSING"); assertThat(error.unknown()).isFalse(); });
        var registry=new ImageProviders(new ImageProperties("minimax",endpoint+"/changed","model",KEY,Duration.ofSeconds(3)),new MockImageProvider(Duration.ZERO),provider("minimax",Duration.ofSeconds(3)));
        assertThatThrownBy(()->registry.generate(input("minimax",false),"signature"))
            .isInstanceOfSatisfying(ProviderException.class,error->assertThat(error.code()).isEqualTo("IMAGE_CONFIGURATION_CHANGED"));
        assertThat(calls.get()).isZero();
        assertThat(new ImageProperties("minimax",endpoint,"model",KEY,Duration.ofSeconds(3)).toString()).doesNotContain(KEY);
        assertThatThrownBy(()->new ImageProperties("openai-compatible",endpoint,"model",KEY,Duration.ofSeconds(3))).isInstanceOf(IllegalArgumentException.class);
    }

    private MiniMaxImageProvider provider(String provider,Duration timeout) { return new MiniMaxImageProvider(new ImageProperties(provider,endpoint,"current-model",KEY,timeout),json,storage); }
    private ImageProvider.Input input(String provider,boolean reference) {
        return new ImageProvider.Input("IMAGE",1,"SHOT",provider,endpoint,"frozen-model","雨夜的路灯","16:9","image-v1",null,
            reference?List.of(new ImageProvider.Reference("7","CHARACTER","reference.png","image/png")):List.of(),
            new ImageProvider.Guidance(reference?"full":"none",reference?List.of("7"):List.of(),null),null);
    }
}
