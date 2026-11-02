package com.vibestory.common;

import com.vibestory.provider.*;
import org.springframework.web.bind.annotation.*;

@RestController
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name="session")
public class RuntimeController {
    private final TextProperties text;
    private final ImageProperties image;
    private final VideoProperties video;
    public RuntimeController(TextProperties text,ImageProperties image,VideoProperties video){this.text=text;this.image=image;this.video=video;}
    public record Model(String provider,String model){}
    public record VideoModel(String provider,String model,int durationMs,String resolution){}
    public record RuntimeConfig(Model text,Model image,VideoModel video){}
    @GetMapping("/api/runtime")
    RuntimeConfig get(){return new RuntimeConfig(new Model(text.provider(),text.provider().equals("mock")?"mock-splitter-v1":text.model()),
        new Model(image.provider(),image.provider().equals("mock")?"mock-image-v1":image.model()),
        new VideoModel(video.provider(),video.provider().equals("mock")?"mock-video-v1":video.model(),video.provider().equals("mock")?3000:6000,video.provider().equals("mock")?"320×180":"768P"));}
}
