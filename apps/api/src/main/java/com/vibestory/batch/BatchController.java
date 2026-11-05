package com.vibestory.batch;

import com.vibestory.agent.EpisodeWorkflow;
import com.vibestory.auth.Viewer;
import com.vibestory.project.ProjectAccess;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import static com.vibestory.project.ProjectAccess.Resource.*;

@RestController
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name="session")
public class BatchController {
    private final GenerationBatch batches;
    private final EpisodeWorkflow workflow;
    private final ProjectAccess access;
    public BatchController(GenerationBatch batches, ProjectAccess access, EpisodeWorkflow workflow) { this.batches=batches; this.access = access; this.workflow = workflow; }
    public record Submit(@NotNull GenerationBatch.Type type,@NotBlank @Size(max=128) String requestKey,
                         @NotEmpty @Size(max=200) List<@NotNull @Pattern(regexp="[1-9][0-9]{0,17}") String> shotIds,boolean regenerate) {}
    @PostMapping("/api/episodes/{episodeId}/generation-batches")
    @Transactional
    @ResponseStatus(HttpStatus.ACCEPTED)
    public GenerationBatch.View submit(@AuthenticationPrincipal Viewer viewer,@PathVariable long episodeId,@Valid @RequestBody Submit body) {
        var grant = access.edit(viewer.id(), EPISODE, episodeId);
        var batch = workflow.generateIndependentBatch(grant.partitionUserId(), episodeId, body.type(), body.requestKey(),
            body.shotIds().stream().map(Long::parseLong).toList(), body.regenerate());
        access.recordBatch(grant, batch.id());
        return batch;
    }
    @GetMapping("/api/episodes/{episodeId}/generation-batches")
    public List<GenerationBatch.View> list(@AuthenticationPrincipal Viewer viewer,@PathVariable long episodeId,@RequestParam GenerationBatch.Type type) { return batches.list(access.read(viewer.id(), EPISODE, episodeId).partitionUserId(), episodeId, type); }
    @GetMapping("/api/generation-batches/{id}")
    public GenerationBatch.View get(@AuthenticationPrincipal Viewer viewer,@PathVariable long id) { return batches.get(access.read(viewer.id(), BATCH, id).partitionUserId(), id); }
}
