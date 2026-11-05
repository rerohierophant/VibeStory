package com.vibestory.agent;

import com.vibestory.auth.Viewer;
import com.vibestory.project.ProjectAccess;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "session")
public class EpisodeWorkflowController {
    private final EpisodeWorkflow workflow;
    private final ProjectAccess access;

    public EpisodeWorkflowController(EpisodeWorkflow workflow, ProjectAccess access) {
        this.workflow = workflow;
        this.access = access;
    }

    @GetMapping("/api/episodes/{episodeId}/workflow")
    public EpisodeWorkflow.EpisodePlan plan(@AuthenticationPrincipal Viewer viewer, @PathVariable long episodeId) {
        var grant = access.read(viewer.id(), ProjectAccess.Resource.EPISODE, episodeId);
        return workflow.plan(grant.partitionUserId(), episodeId);
    }
}
