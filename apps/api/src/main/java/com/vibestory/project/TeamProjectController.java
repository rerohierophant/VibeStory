package com.vibestory.project;

import com.vibestory.auth.Viewer;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/teams/{teamId}/projects")
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "session")
public class TeamProjectController {
    private final ProjectService projects;

    public TeamProjectController(ProjectService projects) { this.projects = projects; }

    @GetMapping
    public List<Project.View> list(@AuthenticationPrincipal Viewer viewer, @PathVariable long teamId) {
        return projects.listTeam(viewer.id(), teamId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Project.View create(@AuthenticationPrincipal Viewer viewer, @PathVariable long teamId,
                               @Valid @RequestBody ProjectController.Create body) {
        return projects.createTeam(viewer.id(), teamId, body.name(), body.visualType(), body.style());
    }
}
