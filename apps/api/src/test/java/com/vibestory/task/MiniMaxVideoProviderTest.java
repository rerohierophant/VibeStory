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
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class MiniMaxVideoProviderTest {
    @TempDir java.nio.file.Path root;
    private final ObjectMapper json=new ObjectMapper();
    private HttpServer server;
    private ExecutorService executor;
    private LocalStorage storage;
    private String endpoint;
    private byte[] movie;
    private final AtomicInteger submissions=new AtomicInteger(),queries=new AtomicInteger(),httpStatus=new AtomicInteger(200),businessCode=new AtomicInteger(),delay=new AtomicInteger();
    private final AtomicReference<String> request=new AtomicReference<>(),checkpoint=new AtomicReference<>(),downloadAuth=new AtomicReference<>();
    private final AtomicReference<String> state=new AtomicReference<>("Success");
    private final AtomicBoolean queriedBeforeCheckpoint=new AtomicBoolean();
    private static final String KEY="video-fixture-secret";

    @BeforeEach void setup() throws Exception {
        storage=new LocalStorage(root.toString());
        var png=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(640,360,BufferedImage.TYPE_INT_RGB),"png",png);storage.put("frame.png",png.toByteArray());
        var fixture=new MockVideoProvider(new MockVideoProvider.Properties(Duration.ZERO,0,"known"),storage)
            .generate(new VideoProvider.Request(9,9,9,9,1,"1234567890abcdef","{}"));
        movie=storage.resource(fixture.storageKey()).getContentAsByteArray();
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);executor=Executors.newVirtualThreadPerTaskExecutor();server.setExecutor(executor);
        server.createContext("/",exchange->{
            String path=exchange.getRequestURI().getPath(); String response;
            if(path.equals("/v1/video_generation")) {
                submissions.incrementAndGet();request.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
                try {Thread.sleep(delay.get());}catch(InterruptedException e){Thread.currentThread().interrupt();}
                response="{\"task_id\":\"42\",\"base_resp\":{\"status_code\":"+businessCode.get()+"}}";
            } else if(path.equals("/v1/query/video_generation")) {
                queries.incrementAndGet();if(checkpoint.get()==null) queriedBeforeCheckpoint.set(true);
                response="{\"task_id\":\"42\",\"status\":\""+state.get()+"\",\"file_id\":\"99\",\"base_resp\":{\"status_code\":0}}";
            } else if(path.equals("/v1/files/retrieve")) response="{\"file\":{\"download_url\":\""+endpoint.replace("/v1","")+"/clip.mp4\"},\"base_resp\":{\"status_code\":0}}";
            else {
                downloadAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
                byte[] bytes=movie;
                exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();return;
            }
            byte[] bytes=response.getBytes(StandardCharsets.UTF_8);
            try {exchange.sendResponseHeaders(path.equals("/v1/video_generation")?httpStatus.get():200,bytes.length);exchange.getResponseBody().write(bytes);}catch(java.io.IOException ignored){}finally{exchange.close();}
        });server.start();endpoint="http://127.0.0.1:"+server.getAddress().getPort()+"/v1";
    }
    @AfterEach void stop(){server.stop(0);executor.shutdownNow();}

    @Test void createsOneVideoCheckpointsBeforePollingAndDownloadsWithoutCredentials() throws Exception {
        var result=provider(Duration.ofSeconds(2),Duration.ofSeconds(3)).generate(request(),input(),null,id->{checkpoint.set(id);return true;});
        var body=json.readTree(request.get());
        assertThat(body.path("duration").asInt()).isEqualTo(6);assertThat(body.path("resolution").asText()).isEqualTo("768P");
        assertThat(body.path("first_frame_image").asText()).startsWith("data:image/png;base64,");
        assertThat(body.path("prompt_optimizer").asBoolean()).isFalse();assertThat(request.get()).doesNotContain(KEY);
        assertThat(submissions.get()).isEqualTo(1);assertThat(queriedBeforeCheckpoint.get()).isFalse();assertThat(downloadAuth.get()).isNull();
        assertThat(storage.resource(result.storageKey()).exists()).isTrue();assertThat(result.durationMs()).isEqualTo(3000);
        assertThatThrownBy(()->Mp4Media.durationMs(new byte[64])).isInstanceOf(ProviderException.class);
    }
    @Test void persistedExternalIdResumesWithoutSubmittingAgain() throws Exception {
        checkpoint.set("42");provider(Duration.ofSeconds(2),Duration.ofSeconds(3)).generate(request(),input(),"42",id->{throw new AssertionError("must not create again");});
        assertThat(submissions.get()).isZero();assertThat(queries.get()).isEqualTo(1);
    }
    @Test void rejectionRateLimitAndUnknownOutcomeAreDistinctAndNeverRetried() {
        for(int status:List.of(401,429,503)) {
            httpStatus.set(status);
            assertThatThrownBy(()->provider(Duration.ofSeconds(2),Duration.ofSeconds(3)).generate(request(),input(),null,id->true))
                .isInstanceOfSatisfying(ProviderException.class,error->{assertThat(error.retryable()).isEqualTo(status==429);assertThat(error.unknown()).isEqualTo(status>=500);});
        }
        assertThat(submissions.get()).isEqualTo(3);
    }
    @Test void balanceFailureIsKnownAndCreateTimeoutIsUnknownWithoutReplaying() {
        businessCode.set(1008);
        assertThatThrownBy(()->provider(Duration.ofSeconds(2),Duration.ofSeconds(3)).generate(request(),input(),null,id->true))
            .isInstanceOfSatisfying(ProviderException.class,error->{assertThat(error.code()).isEqualTo("VIDEO_MINIMAX_1008");assertThat(error.unknown()).isFalse();});
        businessCode.set(0);delay.set(500);
        assertThatThrownBy(()->provider(Duration.ofMillis(80),Duration.ofSeconds(3)).generate(request(),input(),null,id->true))
            .isInstanceOfSatisfying(ProviderException.class,error->assertThat(error.unknown()).isTrue());
        assertThat(submissions.get()).isBetween(1,2);
    }
    @Test void lostLeaseAfterCreationStopsPollingAndKnownFailureIsRetryable() {
        assertThatThrownBy(()->provider(Duration.ofSeconds(2),Duration.ofSeconds(3)).generate(request(),input(),null,id->false)).isInstanceOf(InterruptedException.class);
        assertThat(queries.get()).isZero();checkpoint.set("42");state.set("Fail");
        assertThatThrownBy(()->provider(Duration.ofSeconds(2),Duration.ofSeconds(3)).generate(request(),input(),"42",id->false))
            .isInstanceOfSatisfying(ProviderException.class,error->{assertThat(error.retryable()).isTrue();assertThat(error.unknown()).isFalse();});
    }
    @Test void waitTimeoutKeepsExternalIdForLaterQuery() {
        state.set("Processing");
        assertThatThrownBy(()->provider(Duration.ofSeconds(2),Duration.ofMillis(60)).generate(request(),input(),null,id->{checkpoint.set(id);return true;}))
            .isInstanceOfSatisfying(ProviderException.class,error->{assertThat(error.code()).isEqualTo("VIDEO_WAIT_UNKNOWN");assertThat(error.unknown()).isTrue();});
        assertThat(checkpoint.get()).isEqualTo("42");assertThat(submissions.get()).isEqualTo(1);
    }
    private MiniMaxVideoProvider provider(Duration timeout,Duration generationTimeout){return new MiniMaxVideoProvider(new VideoProperties("minimax",endpoint,"MiniMax-Hailuo-2.3-Fast",KEY,timeout,Duration.ofMillis(10),generationTimeout),storage,json);}
    private VideoProvider.Request request() throws Exception {return new VideoProvider.Request(1,1,1,1,1,"1234567890abcdef",json.writeValueAsString(input()));}
    private TaskService.VideoInput input(){return new TaskService.VideoInput("VIDEO",1,"minimax","路灯下的雨夜街道","realistic","",1,1,"frame.png",3000,Map.of("outputDurationMs",6000),null,endpoint,"MiniMax-Hailuo-2.3-Fast");}
}
