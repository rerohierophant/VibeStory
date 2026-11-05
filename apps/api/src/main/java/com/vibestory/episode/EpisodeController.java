package com.vibestory.episode;

import com.vibestory.auth.Viewer;
import com.vibestory.project.ProjectAccess;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import static com.vibestory.project.ProjectAccess.Resource.*;

@RestController
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "session")
public class EpisodeController {
    private final EpisodeService episodes;
    private final ProjectAccess access;
    public EpisodeController(EpisodeService episodes, ProjectAccess access) { this.episodes = episodes; this.access = access; }
    @io.swagger.v3.oas.annotations.media.Schema(name = "CreateEpisode")
    public record Create(@Size(max = 120) String title) {}
    @io.swagger.v3.oas.annotations.media.Schema(name = "UpdateEpisode")
    public record Update(@Size(max = 120) String title, @Size(max = 4000) String summary, @PositiveOrZero Long expectedVersion) {}
    public record Story(@NotNull @Size(max = 1048576) String story, @PositiveOrZero Long expectedStoryRevision) {}

    @PostMapping("/api/projects/{projectId}/episodes") @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    Episode.View create(@AuthenticationPrincipal Viewer viewer, @PathVariable long projectId,
                        @Valid @RequestBody(required = false) Create body) {
        return episodes.create(access.edit(viewer.id(), PROJECT, projectId).partitionUserId(), projectId, body == null ? null : body.title());
    }

    @GetMapping("/api/projects/{projectId}/episodes")
    List<Episode.View> list(@AuthenticationPrincipal Viewer viewer, @PathVariable long projectId) {
        return episodes.list(access.read(viewer.id(), PROJECT, projectId).partitionUserId(), projectId);
    }

    @GetMapping("/api/episodes/{episodeId}")
    Episode.View get(@AuthenticationPrincipal Viewer viewer, @PathVariable long episodeId) {
        return episodes.get(access.read(viewer.id(), EPISODE, episodeId).partitionUserId(), episodeId);
    }

    @PatchMapping("/api/episodes/{episodeId}")
    @Transactional
    Episode.View update(@AuthenticationPrincipal Viewer viewer, @PathVariable long episodeId, @Valid @RequestBody Update body) {
        var grant = access.edit(viewer.id(), EPISODE, episodeId);
        grant.requireVersion(body.expectedVersion());
        return episodes.update(grant.partitionUserId(), episodeId, body.title(), body.summary(), body.expectedVersion());
    }

    @PutMapping("/api/episodes/{episodeId}/story")
    @Transactional
    Episode.View story(@AuthenticationPrincipal Viewer viewer, @PathVariable long episodeId, @Valid @RequestBody Story body) {
        var grant = access.edit(viewer.id(), EPISODE, episodeId);
        grant.requireVersion(body.expectedStoryRevision());
        return episodes.story(grant.partitionUserId(), episodeId, body.story(), body.expectedStoryRevision());
    }

    @DeleteMapping("/api/episodes/{episodeId}") @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    void delete(@AuthenticationPrincipal Viewer viewer, @PathVariable long episodeId) {
        episodes.delete(access.edit(viewer.id(), EPISODE, episodeId).partitionUserId(), episodeId);
    }
}
