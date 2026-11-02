package com.vibestory.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vibestory.task.TaskService;
import java.util.function.Predicate;
import org.springframework.stereotype.Component;

@Component
public class VideoProviders {
    private final MockVideoProvider mock;
    private final MiniMaxVideoProvider minimax;
    private final VideoProperties config;
    private final ObjectMapper json;
    public VideoProviders(MockVideoProvider mock, MiniMaxVideoProvider minimax, VideoProperties config, ObjectMapper json) {
        this.mock=mock; this.minimax=minimax; this.config=config; this.json=json;
    }
    public VideoProvider.Capabilities capabilities(String provider) {
        return switch(provider) { case "mock" -> mock.capabilities(); case "minimax" -> new VideoProvider.Capabilities(true,false,6000); default -> throw new IllegalArgumentException("未知视频适配器"); };
    }
    public VideoProvider.Result generate(VideoProvider.Request request, String externalId, Predicate<String> checkpoint) throws InterruptedException {
        TaskService.VideoInput input;
        try { input=json.readValue(request.inputSnapshot(),TaskService.VideoInput.class); }
        catch(java.io.IOException error) { throw new IllegalStateException("视频输入无效",error); }
        if (input.provider().equals("mock")) return mock.generate(request);
        if (!input.provider().equals(config.provider()) || !config.baseUrl().replaceAll("/+$","").equals(input.endpoint()))
            throw new ProviderException("VIDEO_CONFIGURATION_CHANGED","任务提交后的视频接口配置已变化",true,false);
        return minimax.generate(request,input,externalId,checkpoint);
    }
}
