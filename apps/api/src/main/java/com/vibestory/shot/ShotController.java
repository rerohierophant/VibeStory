package com.vibestory.shot;

import com.vibestory.auth.Viewer;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "session")
public class ShotController {
    private final ShotService shots;
    public ShotController(ShotService shots) { this.shots = shots; }
    public record Patch(@Size(max = 4000) String description, @Size(max = 4000) String dialogue, @Size(max = 120) String sceneName,
                        String shotSize, String cameraMove, @Min(1500) @Max(12000) Integer durationMs) {}
    public record Status(@NotNull @Pattern(regexp = "draft|confirmed") String status) {}

    @GetMapping("/api/episodes/{episodeId}/shots")
    List<ShotService.View> list(@AuthenticationPrincipal Viewer viewer, @PathVariable long episodeId,
                               @RequestParam(defaultValue = "all") String filter, @RequestParam(defaultValue = "false") boolean taskActive) {
        return shots.list(viewer.id(), episodeId, filter);
    }
    @GetMapping("/api/shots/{shotId}")
    ShotService.View get(@AuthenticationPrincipal Viewer viewer, @PathVariable long shotId) { return shots.get(viewer.id(), shotId); }
    @PatchMapping("/api/shots/{shotId}")
    ShotService.View patch(@AuthenticationPrincipal Viewer viewer, @PathVariable long shotId, @Valid @RequestBody Patch patch) {
        return shots.update(viewer.id(), shotId, patch.description(), patch.dialogue(), patch.sceneName(), patch.shotSize(), patch.cameraMove(), patch.durationMs());
    }
    @PutMapping("/api/shots/{shotId}/status")
    ShotService.View status(@AuthenticationPrincipal Viewer viewer, @PathVariable long shotId, @Valid @RequestBody Status body) {
        return shots.status(viewer.id(), shotId, body.status());
    }
    @PostMapping("/api/episodes/{episodeId}/shots/confirm-all")
    List<ShotService.View> confirmAll(@AuthenticationPrincipal Viewer viewer, @PathVariable long episodeId) { return shots.confirmAll(viewer.id(), episodeId); }
}
