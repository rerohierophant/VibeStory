package com.vibestory.variant;

import com.vibestory.auth.Viewer;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/episodes/{episodeId}/storyboard-variants")
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "session")
public class StoryboardVariantController {
    private final StoryboardVariants variants;

    public StoryboardVariantController(StoryboardVariants variants) { this.variants = variants; }

    @GetMapping
    StoryboardVariants.Overview list(@AuthenticationPrincipal Viewer viewer, @PathVariable long episodeId) {
        return variants.list(viewer.id(), episodeId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    StoryboardVariants.Variant submit(@AuthenticationPrincipal Viewer viewer, @PathVariable long episodeId,
                                       @Valid @RequestBody StoryboardVariants.Submit request) {
        return variants.submit(viewer.id(), episodeId, request);
    }

    @PostMapping("/revisions/{revisionId}/adopt")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void adopt(@AuthenticationPrincipal Viewer viewer, @PathVariable long episodeId, @PathVariable long revisionId,
               @Valid @RequestBody StoryboardVariants.Adopt request) {
        variants.adopt(viewer.id(), episodeId, revisionId, request);
    }

    @PostMapping("/{variantId}/archive")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void archive(@AuthenticationPrincipal Viewer viewer, @PathVariable long episodeId, @PathVariable long variantId) {
        variants.archive(viewer.id(), episodeId, variantId);
    }
}
