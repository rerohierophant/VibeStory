package com.vibestory.project;

import com.vibestory.auth.Viewer;
import com.vibestory.team.TeamService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import com.vibestory.common.ProductionStatistics;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import static com.vibestory.project.ProjectAccess.Resource.PROJECT;

@RestController
@RequestMapping("/api/projects")
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "session")
public class ProjectController {
    private final ProjectService projects;
    private final ProjectAccess access;
    private final TeamService teams;
    public ProjectController(ProjectService projects, ProjectAccess access, TeamService teams) {
        this.projects = projects; this.access = access; this.teams = teams;
    }

    @io.swagger.v3.oas.annotations.media.Schema(name = "CreateProject")
    public record Create(@NotBlank @Size(max = 120) String name,
                         @NotBlank @Size(max = 80) String visualType, @Size(max = 4000) String style) {}
    @io.swagger.v3.oas.annotations.media.Schema(name = "UpdateProject")
    public record Update(@Size(max = 120) String name, @Size(max = 80) String visualType, @Size(max = 4000) String style,
                         @PositiveOrZero Long expectedVersion) {}

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ResponseEntity<Project.View> create(@AuthenticationPrincipal Viewer viewer, @Valid @RequestBody Create body) {
        var project = projects.create(viewer.id(), body.name(), body.visualType(), body.style());
        return ResponseEntity.created(URI.create("/api/projects/" + project.id())).body(project);
    }

    @GetMapping("/{id}")
    Project.View get(@AuthenticationPrincipal Viewer viewer, @PathVariable long id) {
        return projects.get(access.read(viewer.id(), PROJECT, id).partitionUserId(), id);
    }

    @GetMapping
    List<Project.View> list(@AuthenticationPrincipal Viewer viewer, @RequestParam(defaultValue = "") String q) {
        return projects.list(viewer.id(), q);
    }

    @PatchMapping("/{id}")
    @Transactional
    Project.View update(@AuthenticationPrincipal Viewer viewer, @PathVariable long id, @Valid @RequestBody Update body) {
        var grant = access.edit(viewer.id(), PROJECT, id);
        grant.requireVersion(body.expectedVersion());
        return projects.update(grant.partitionUserId(), id, body.name(), body.visualType(), body.style(), body.expectedVersion());
    }

    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    void delete(@AuthenticationPrincipal Viewer viewer, @PathVariable long id) {
        var grant = access.authorize(viewer.id(), PROJECT, id, ProjectAccess.Action.MANAGE);
        projects.delete(grant.partitionUserId(), id);
        if (grant.teamId() != null) teams.audit(grant.teamId(), grant.actorId(), "PROJECT_DELETED", id, null, null);
    }

    @GetMapping("/{id}/overview")
    ProductionStatistics.Overview overview(@AuthenticationPrincipal Viewer viewer, @PathVariable long id) {
        return projects.overview(access.read(viewer.id(), PROJECT, id).partitionUserId(), id);
    }
}
