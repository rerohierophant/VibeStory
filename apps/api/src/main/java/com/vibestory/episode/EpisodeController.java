package com.vibestory.episode;

import com.vibestory.auth.Viewer;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "session")
public class EpisodeController {
    private final EpisodeService episodes;
    public EpisodeController(EpisodeService episodes) { this.episodes = episodes; }
    @io.swagger.v3.oas.annotations.media.Schema(name = "CreateEpisode")
    public record Create(@Size(max = 120) String title) {}
    @io.swagger.v3.oas.annotations.media.Schema(name = "UpdateEpisode")
    public record Update(@Size(max = 120) String title, @Size(max = 4000) String summary) {}
    public record Story(@NotNull @Size(max = 1048576) String story) {}

    @PostMapping("/api/projects/{projectId}/episodes") @ResponseStatus(HttpStatus.CREATED)
    Episode.View create(@AuthenticationPrincipal Viewer viewer, @PathVariable long projectId,
                        @Valid @RequestBody(required = false) Create body) {
        return episodes.create(viewer.id(), projectId, body == null ? null : body.title());
    }

    @GetMapping("/api/projects/{projectId}/episodes")
    List<Episode.View> list(@AuthenticationPrincipal Viewer viewer, @PathVariable long projectId) {
        return episodes.list(viewer.id(), projectId);
    }

    @GetMapping("/api/episodes/{episodeId}")
    Episode.View get(@AuthenticationPrincipal Viewer viewer, @PathVariable long episodeId) { return episodes.get(viewer.id(), episodeId); }

    @PatchMapping("/api/episodes/{episodeId}")
    Episode.View update(@AuthenticationPrincipal Viewer viewer, @PathVariable long episodeId, @Valid @RequestBody Update body) {
        return episodes.update(viewer.id(), episodeId, body.title(), body.summary());
    }

    @PutMapping("/api/episodes/{episodeId}/story")
    Episode.View story(@AuthenticationPrincipal Viewer viewer, @PathVariable long episodeId, @Valid @RequestBody Story body) {
        return episodes.story(viewer.id(), episodeId, body.story());
    }

    @DeleteMapping("/api/episodes/{episodeId}") @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@AuthenticationPrincipal Viewer viewer, @PathVariable long episodeId) { episodes.delete(viewer.id(), episodeId); }
}
