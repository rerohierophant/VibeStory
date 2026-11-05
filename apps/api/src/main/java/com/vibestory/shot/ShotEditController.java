package com.vibestory.shot;

import com.vibestory.auth.Viewer;
import com.vibestory.project.ProjectAccess;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import static com.vibestory.project.ProjectAccess.Resource.*;

@RestController
@RequestMapping("/api/episodes/{episodeId}/shot-edit-proposals")
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "session")
public class ShotEditController {
    private final ShotEditProposals proposals;

    private final ProjectAccess access;
    public ShotEditController(ShotEditProposals proposals, ProjectAccess access) { this.proposals = proposals; this.access = access; }

    public record Preview(@NotNull UUID requestKey, @NotBlank @Size(max = 500) String instruction) {}

    @PostMapping
    ShotEditProposals.Proposal preview(@AuthenticationPrincipal Viewer viewer, @PathVariable long episodeId,
                                       @Valid @RequestBody Preview input) {
        access.authorize(viewer.id(), EPISODE, episodeId, ProjectAccess.Action.PERSONAL);
        return proposals.preview(viewer.id(), episodeId, input.requestKey(), input.instruction());
    }

    @GetMapping("/latest")
    ResponseEntity<ShotEditProposals.Proposal> latest(@AuthenticationPrincipal Viewer viewer, @PathVariable long episodeId) {
        access.authorize(viewer.id(), EPISODE, episodeId, ProjectAccess.Action.PERSONAL);
        var proposal = proposals.latest(viewer.id(), episodeId);
        return proposal == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(proposal);
    }

    @PostMapping("/{id}/apply")
    ShotEditProposals.Proposal apply(@AuthenticationPrincipal Viewer viewer, @PathVariable long episodeId, @PathVariable long id) {
        access.authorize(viewer.id(), EPISODE, episodeId, ProjectAccess.Action.PERSONAL);
        return proposals.decide(viewer.id(), episodeId, id, true);
    }

    @PostMapping("/{id}/discard")
    ShotEditProposals.Proposal discard(@AuthenticationPrincipal Viewer viewer, @PathVariable long episodeId, @PathVariable long id) {
        access.authorize(viewer.id(), EPISODE, episodeId, ProjectAccess.Action.PERSONAL);
        return proposals.decide(viewer.id(), episodeId, id, false);
    }
}
