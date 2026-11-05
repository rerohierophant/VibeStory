package com.vibestory.experience;

import com.vibestory.auth.Viewer;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/image-experience")
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "session")
public class ImageExperienceController {
    private final ImageExperience experience;
    private final ImageCreationService creation;

    public ImageExperienceController(ImageExperience experience, ImageCreationService creation) {
        this.experience = experience;
        this.creation = creation;
    }

    @GetMapping("/targets/{type}/{targetId}")
    ImageExperience.Context context(@AuthenticationPrincipal Viewer user, @PathVariable ImageExperience.TargetType type, @PathVariable long targetId) {
        return experience.context(user.id(), type, targetId);
    }

    @PostMapping("/creations")
    @ResponseStatus(HttpStatus.ACCEPTED)
    ImageExperience.Creation create(@AuthenticationPrincipal Viewer user, @Valid @RequestBody ImageCreationService.Request request) {
        return creation.create(user.id(), request);
    }

    @io.swagger.v3.oas.annotations.media.Schema(name = "ImageDecisionInput")
    public record Decide(@NotNull ImageExperience.Decision decision) {}

    @PutMapping("/creations/{id}/decision")
    ImageExperience.Creation decide(@AuthenticationPrincipal Viewer user, @PathVariable UUID id, @Valid @RequestBody Decide request) {
        return experience.decide(user.id(), id, request.decision());
    }

    @PostMapping("/creations/{id}/preferences")
    ImageExperience.Preference remember(@AuthenticationPrincipal Viewer user, @PathVariable UUID id, @Valid @RequestBody ImageExperience.Remember request) {
        return experience.remember(user.id(), id, request);
    }

    @PutMapping("/preferences/{id}")
    ImageExperience.Preference edit(@AuthenticationPrincipal Viewer user, @PathVariable UUID id, @Valid @RequestBody ImageExperience.EditPreference request) {
        return experience.editPreference(user.id(), id, request);
    }

    @PostMapping("/recipes")
    ImageExperience.Recipe recipe(@AuthenticationPrincipal Viewer user, @Valid @RequestBody ImageExperience.SaveRecipe request) {
        return experience.saveRecipe(user.id(), request);
    }
}
