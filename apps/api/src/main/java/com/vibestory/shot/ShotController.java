package com.vibestory.shot;

import com.vibestory.agent.EpisodeWorkflow;
import com.vibestory.auth.Viewer;
import com.vibestory.project.ProjectAccess;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import static com.vibestory.project.ProjectAccess.Resource.*;

@RestController
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "session")
public class ShotController {
    private final ShotService shots;
    private final EpisodeWorkflow workflow;
    private final ProjectAccess access;
    public ShotController(ShotService shots, ProjectAccess access, EpisodeWorkflow workflow) { this.shots = shots; this.access = access; this.workflow = workflow; }
    public record Patch(@Size(max = 4000) String description, @Size(max = 4000) String dialogue, @Size(max = 120) String sceneName,
                        String shotSize, String cameraMove, @Min(1500) @Max(12000) Integer durationMs,
                        @PositiveOrZero Long expectedVersion) {}
    public record Status(@NotNull @Pattern(regexp = "draft|confirmed") String status) {}

    @GetMapping("/api/episodes/{episodeId}/shots")
    List<ShotService.View> list(@AuthenticationPrincipal Viewer viewer, @PathVariable long episodeId,
                               @RequestParam(defaultValue = "all") String filter, @RequestParam(defaultValue = "false") boolean taskActive) {
        return shots.list(access.read(viewer.id(), EPISODE, episodeId).partitionUserId(), episodeId, filter);
    }
    @GetMapping("/api/shots/{shotId}")
    ShotService.View get(@AuthenticationPrincipal Viewer viewer, @PathVariable long shotId) { return shots.get(access.read(viewer.id(), SHOT, shotId).partitionUserId(), shotId); }
    @PatchMapping("/api/shots/{shotId}")
    @Transactional
    ShotService.View patch(@AuthenticationPrincipal Viewer viewer, @PathVariable long shotId, @Valid @RequestBody Patch patch) {
        var grant = access.edit(viewer.id(), SHOT, shotId);
        grant.requireVersion(patch.expectedVersion());
        return shots.update(grant.partitionUserId(), shotId, patch.description(), patch.dialogue(), patch.sceneName(), patch.shotSize(),
            patch.cameraMove(), patch.durationMs(), patch.expectedVersion());
    }
    @PutMapping("/api/shots/{shotId}/status")
    @Transactional
    ShotService.View status(@AuthenticationPrincipal Viewer viewer, @PathVariable long shotId, @Valid @RequestBody Status body) {
        return workflow.shotStatus(access.edit(viewer.id(), SHOT, shotId).partitionUserId(), shotId, body.status());
    }
    @PostMapping("/api/episodes/{episodeId}/shots/confirm-all")
    @Transactional
    List<ShotService.View> confirmAll(@AuthenticationPrincipal Viewer viewer, @PathVariable long episodeId) { return workflow.confirmShots(access.edit(viewer.id(), EPISODE, episodeId).partitionUserId(), episodeId); }
}
