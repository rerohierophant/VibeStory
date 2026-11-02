package com.vibestory.project;

import com.vibestory.auth.Viewer;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import com.vibestory.common.ProductionStatistics;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/projects")
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "session")
public class ProjectController {
    private final ProjectService projects;
    public ProjectController(ProjectService projects) { this.projects = projects; }

    @io.swagger.v3.oas.annotations.media.Schema(name = "CreateProject")
    public record Create(@NotBlank @Size(max = 120) String name,
                         @NotBlank @Size(max = 80) String visualType, @Size(max = 4000) String style) {}
    @io.swagger.v3.oas.annotations.media.Schema(name = "UpdateProject")
    public record Update(@Size(max = 120) String name, @Size(max = 80) String visualType, @Size(max = 4000) String style) {}

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ResponseEntity<Project.View> create(@AuthenticationPrincipal Viewer viewer, @Valid @RequestBody Create body) {
        var project = projects.create(viewer.id(), body.name(), body.visualType(), body.style());
        return ResponseEntity.created(URI.create("/api/projects/" + project.id())).body(project);
    }

    @GetMapping("/{id}")
    Project.View get(@AuthenticationPrincipal Viewer viewer, @PathVariable long id) { return projects.get(viewer.id(), id); }

    @GetMapping
    List<Project.View> list(@AuthenticationPrincipal Viewer viewer, @RequestParam(defaultValue = "") String q) {
        return projects.list(viewer.id(), q);
    }

    @PatchMapping("/{id}")
    Project.View update(@AuthenticationPrincipal Viewer viewer, @PathVariable long id, @Valid @RequestBody Update body) {
        return projects.update(viewer.id(), id, body.name(), body.visualType(), body.style());
    }

    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@AuthenticationPrincipal Viewer viewer, @PathVariable long id) { projects.delete(viewer.id(), id); }

    @GetMapping("/{id}/overview")
    ProductionStatistics.Overview overview(@AuthenticationPrincipal Viewer viewer, @PathVariable long id) {
        return projects.overview(viewer.id(), id);
    }
}
