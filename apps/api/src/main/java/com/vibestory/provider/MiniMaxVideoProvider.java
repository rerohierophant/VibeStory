package com.vibestory.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vibestory.storage.LocalStorage;
import com.vibestory.task.TaskService;
import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.nio.file.Files;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Predicate;
import org.springframework.stereotype.Component;

/** Only Hailuo 2.3 Fast, one 768P / six-second video. External IDs are checkpointed before polling. */
@Component
public class MiniMaxVideoProvider {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(MiniMaxVideoProvider.class);
    private final VideoProperties config;
    private final LocalStorage storage;
    private final ObjectMapper json;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
    public MiniMaxVideoProvider(VideoProperties config, LocalStorage storage, ObjectMapper json) { this.config=config; this.storage=storage; this.json=json; }

    public VideoProvider.Result generate(VideoProvider.Request request, TaskService.VideoInput input, String externalId, Predicate<String> checkpoint) throws InterruptedException {
        if (externalId==null) {
            String frame;
            try {
                var file=storage.path(input.sourceImageKey());
                if (Files.size(file)>20*1024*1024) throw new IOException("首帧过大");
                byte[] bytes=Files.readAllBytes(file);
                String mime=MiniMaxImageProvider.validateImage(bytes);
                frame="data:"+mime+";base64,"+Base64.getEncoder().encodeToString(bytes);
            } catch(IOException error) { throw new ProviderException("VIDEO_SOURCE_UNREADABLE","提交时的首帧无法读取",false,false); }
            log.info("提交 MiniMax 视频 task={} attempt={}",request.taskId(),request.attempt());
            var created=call(input.endpoint()+"/video_generation",Map.of("model",input.model(),"prompt",input.prompt(),
                "first_frame_image",frame,"duration",6,"resolution","768P","prompt_optimizer",false),true);
            externalId=id(created.path("task_id").asText());
            if (!checkpoint.test(externalId)) throw new InterruptedException("视频提交后失去租约，停止当前进程查询");
        }
        externalId=id(externalId);
        long deadline=System.nanoTime()+config.generationTimeout().toNanos();
        while (System.nanoTime()<deadline) {
            var result=call(input.endpoint()+"/query/video_generation?task_id="+externalId,null,false);
            String state=result.path("status").asText();
            if (state.equals("Success")) {
                String fileId=id(result.path("file_id").asText());
                var file=call(input.endpoint()+"/files/retrieve?file_id="+fileId,null,false);
                byte[] bytes=download(file.path("file").path("download_url").asText());
                int duration=Mp4Media.durationMs(bytes);
                String key="u/"+request.userId()+"/p/"+request.projectId()+"/e/"+request.episodeId()+"/video/"+request.taskId()+"-"+request.signature()+"/attempt-"+request.attempt()+".mp4";
                try { storage.put(key,bytes); }
                catch(IOException error) { throw new ProviderException("VIDEO_STORAGE_UNKNOWN","视频已生成但本地保存失败，可重查原任务",false,true); }
                return new VideoProvider.Result(key,duration);
            }
            if (state.equals("Fail")) throw new ProviderException("VIDEO_GENERATION_FAILED","MiniMax 视频生成失败",true,false);
            if (!Set.of("Preparing","Queueing","Processing").contains(state))
                throw new ProviderException("VIDEO_QUERY_UNKNOWN","视频任务返回未知状态，可重查原任务",false,true);
            Thread.sleep(config.pollInterval());
        }
        throw new ProviderException("VIDEO_WAIT_UNKNOWN","视频仍未完成，可继续查询原任务，不会重复生成",false,true);
    }

    private String id(String value) {
        if (value==null || !value.matches("[0-9]{1,100}")) throw new ProviderException("VIDEO_ID_UNKNOWN","视频接口未返回有效任务或文件编号",false,true);
        return value;
    }

    private JsonNode call(String url, Object body, boolean creating) throws InterruptedException {
        try {
            var builder=HttpRequest.newBuilder(URI.create(url)).timeout(config.timeout()).header("Authorization","Bearer "+config.apiKey());
            if (body!=null) builder.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            var response=send(builder.build(),1024*1024);
            int status=response.statusCode();
            if (status<200 || status>=300) throw new ProviderException("VIDEO_HTTP_"+status,"视频接口返回 HTTP "+status,
                creating && status==429, !creating || status>=500 || status==408);
            var tree=json.readTree(response.body());
            var code=tree==null ? null : tree.path("base_resp").path("status_code");
            if (code==null || !code.isIntegralNumber()) throw new IOException("状态码无效");
            int number=code.asInt();
            if (number!=0) {
                boolean rejected=Set.of(1002,1004,1008,1026,1045,2013,2014,2022,2049).contains(number);
                throw new ProviderException("VIDEO_MINIMAX_"+number,"MiniMax 视频接口返回错误码 "+number,
                    creating && (number==1002 || number==1045), !creating || !rejected);
            }
            return tree;
        } catch(IOException | IllegalArgumentException error) {
            throw new ProviderException("VIDEO_IO_UNKNOWN","视频接口响应异常，外部结果未知",false,true);
        }
    }

    private HttpResponse<byte[]> send(HttpRequest request,int limit) throws InterruptedException {
        var pending=http.sendAsync(request,ignored -> boundedBody(limit));
        try { return pending.get(config.timeout().toMillis(),TimeUnit.MILLISECONDS); }
        catch(ExecutionException | TimeoutException error) { throw new ProviderException("VIDEO_REQUEST_UNKNOWN","视频请求中断或超时，外部结果未知",false,true); }
        finally { if (!pending.isDone()) pending.cancel(true); }
    }

    private byte[] download(String link) throws InterruptedException {
        URI uri;
        try { uri=URI.create(link); }
        catch(IllegalArgumentException error) { throw new ProviderException("VIDEO_DOWNLOAD_UNKNOWN","视频下载地址无效",false,true); }
        for(int redirect=0;redirect<4;redirect++) {
            validateDownload(uri);
            var result=send(HttpRequest.newBuilder(uri).timeout(config.timeout()).GET().build(),100*1024*1024);
            if (Set.of(301,302,303,307,308).contains(result.statusCode())) {
                String location=result.headers().firstValue("Location").orElseThrow(()->new ProviderException("VIDEO_DOWNLOAD_UNKNOWN","视频下载重定向无效",false,true));
                uri=uri.resolve(location); continue;
            }
            if (result.statusCode()!=200) throw new ProviderException("VIDEO_DOWNLOAD_UNKNOWN","视频下载失败，可重查原任务",false,true);
            return result.body();
        }
        throw new ProviderException("VIDEO_DOWNLOAD_UNKNOWN","视频下载重定向次数过多",false,true);
    }

    private void validateDownload(URI uri) {
        try {
            boolean localFixture=URI.create(config.baseUrl()).getScheme().equals("http") && Objects.equals(uri.getHost(),URI.create(config.baseUrl()).getHost());
            if (!("https".equals(uri.getScheme()) || localFixture && "http".equals(uri.getScheme())) || uri.getHost()==null || uri.getUserInfo()!=null || uri.getFragment()!=null) throw new IOException("无效地址");
            for(var address:InetAddress.getAllByName(uri.getHost()))
                if (!localFixture && (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress() || address.isSiteLocalAddress() || address.isMulticastAddress())) throw new IOException("非公网地址");
        } catch(IOException | IllegalArgumentException error) { throw new ProviderException("VIDEO_DOWNLOAD_UNKNOWN","视频下载地址不受支持",false,true); }
    }

    private static HttpResponse.BodySubscriber<byte[]> boundedBody(int limit) {
        var delegate=HttpResponse.BodySubscribers.ofByteArray();
        return new HttpResponse.BodySubscriber<>() {
            java.util.concurrent.Flow.Subscription subscription;
            long size;
            boolean failed;
            public CompletionStage<byte[]> getBody(){return delegate.getBody();}
            public void onSubscribe(java.util.concurrent.Flow.Subscription value){subscription=value;delegate.onSubscribe(value);}
            public void onNext(List<java.nio.ByteBuffer> buffers){
                for(var buffer:buffers) size+=buffer.remaining();
                if(size>limit){failed=true;subscription.cancel();delegate.onError(new IOException("响应超过上限"));}
                else if(!failed) delegate.onNext(buffers);
            }
            public void onError(Throwable error){if(!failed) delegate.onError(error);}
            public void onComplete(){if(!failed) delegate.onComplete();}
        };
    }
}
