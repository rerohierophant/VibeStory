package com.vibestory.agent;

import com.vibestory.auth.Viewer;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "session")
public class CreativeController {
    private final CreativeWorkflows creative;

    CreativeController(CreativeWorkflows creative) { this.creative = creative; }

    public record UploadSource(@NotNull UUID requestKey, @NotNull @Size(max = 120) String name,
                               @NotNull @Size(max = 1048576) String text, CreativeWorkflows.SourceKind kind) {}
    public record CreativeRequest(@NotNull UUID requestKey, @Size(max = 500) String instruction, UUID scriptId) {
        String notes() { return instruction == null ? "" : instruction.strip(); }
    }

    @GetMapping("/api/projects/{projectId}/creative")
    CreativeWorkflows.CreativeProject project(@AuthenticationPrincipal Viewer viewer, @PathVariable long projectId) {
        return creative.project(viewer.id(), projectId, true);
    }

    @PostMapping("/api/projects/{projectId}/creative/source")
    CreativeWorkflows.CreativeSource upload(@AuthenticationPrincipal Viewer viewer, @PathVariable long projectId,
                                            @Valid @RequestBody UploadSource input) {
        return creative.upload(viewer.id(), projectId, input.requestKey(), input.name(), input.text(), input.kind());
    }

    @PostMapping("/api/projects/{projectId}/creative/plan")
    CreativeWorkflows.CreativeTurn plan(@AuthenticationPrincipal Viewer viewer, @PathVariable long projectId,
                                        @Valid @RequestBody CreativeRequest input) {
        return creative.plan(viewer.id(), projectId, input.requestKey(), input.notes());
    }

    @GetMapping("/api/projects/{projectId}/creative/plan/{id}")
    CreativeWorkflows.CreativePlanPreview planPreview(@AuthenticationPrincipal Viewer viewer, @PathVariable long projectId, @PathVariable UUID id) {
        return creative.planPreview(viewer.id(), projectId, id);
    }

    @PostMapping("/api/projects/{projectId}/creative/plan/{id}/accept")
    CreativeWorkflows.CreativeTurn acceptPlan(@AuthenticationPrincipal Viewer viewer, @PathVariable long projectId, @PathVariable UUID id) {
        return creative.acceptPlan(viewer.id(), projectId, id);
    }

    @GetMapping("/api/episodes/{episodeId}/creative")
    CreativeWorkflows.CreativeEpisode episode(@AuthenticationPrincipal Viewer viewer, @PathVariable long episodeId) {
        return creative.episodeSummary(viewer.id(), episodeId, null);
    }

    @PostMapping("/api/episodes/{episodeId}/creative/session")
    CreativeWorkflows.CreativeEpisode openCreativeSession(@AuthenticationPrincipal Viewer viewer, @PathVariable long episodeId) {
        return creative.episode(viewer.id(), episodeId);
    }

    @PostMapping("/api/episodes/{episodeId}/creative/script")
    CreativeWorkflows.CreativeTurn script(@AuthenticationPrincipal Viewer viewer, @PathVariable long episodeId,
                                          @Valid @RequestBody CreativeRequest input) {
        return creative.script(viewer.id(), episodeId, null, input.requestKey(), input.notes());
    }

    @GetMapping("/api/episodes/{episodeId}/creative/script/{id}")
    CreativeSkill.ScriptDraft scriptPreview(@AuthenticationPrincipal Viewer viewer, @PathVariable long episodeId, @PathVariable UUID id) {
        return creative.scriptPreview(viewer.id(), episodeId, id);
    }

    @PostMapping("/api/episodes/{episodeId}/creative/storyboard")
    CreativeWorkflows.CreativeTurn storyboard(@AuthenticationPrincipal Viewer viewer, @PathVariable long episodeId,
                                              @Valid @RequestBody CreativeRequest input) {
        return creative.storyboard(viewer.id(), episodeId, null, input.requestKey(), input.scriptId(), input.notes());
    }

    @GetMapping("/api/episodes/{episodeId}/creative/storyboard/{id}")
    CreativeWorkflows.CreativeStoryboard storyboardPreview(@AuthenticationPrincipal Viewer viewer, @PathVariable long episodeId, @PathVariable UUID id) {
        return creative.storyboardPreview(viewer.id(), episodeId, id);
    }

    @PostMapping("/api/episodes/{episodeId}/creative/storyboard/{id}/apply")
    CreativeWorkflows.CreativeTurn applyStoryboard(@AuthenticationPrincipal Viewer viewer, @PathVariable long episodeId, @PathVariable UUID id) {
        return creative.applyStoryboard(viewer.id(), episodeId, null, id);
    }
}
