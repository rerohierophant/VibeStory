package com.vibestory.batch;

import com.vibestory.auth.Viewer;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name="session")
public class BatchController {
    private final GenerationBatch batches;
    public BatchController(GenerationBatch batches) { this.batches=batches; }
    public record Submit(@NotNull GenerationBatch.Type type,@NotBlank @Size(max=128) String requestKey,
                         @NotEmpty @Size(max=200) List<@NotNull @Pattern(regexp="[1-9][0-9]{0,17}") String> shotIds,boolean regenerate) {}
    @PostMapping("/api/episodes/{episodeId}/generation-batches")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public GenerationBatch.View submit(@AuthenticationPrincipal Viewer viewer,@PathVariable long episodeId,@Valid @RequestBody Submit body) {
        return batches.submit(viewer.id(),episodeId,body.type(),body.requestKey(),body.shotIds().stream().map(Long::parseLong).toList(),body.regenerate());
    }
    @GetMapping("/api/episodes/{episodeId}/generation-batches")
    public List<GenerationBatch.View> list(@AuthenticationPrincipal Viewer viewer,@PathVariable long episodeId,@RequestParam GenerationBatch.Type type) { return batches.list(viewer.id(),episodeId,type); }
    @GetMapping("/api/generation-batches/{id}")
    public GenerationBatch.View get(@AuthenticationPrincipal Viewer viewer,@PathVariable long id) { return batches.get(viewer.id(),id); }
}
