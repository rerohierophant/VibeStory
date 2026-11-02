package com.vibestory.image;

import com.vibestory.auth.Viewer;
import com.vibestory.task.TaskService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name="session")
public class ImageController {
    private final ImageTaskService images;
    public ImageController(ImageTaskService images) { this.images=images; }
    public record GenerateImage(@Size(max=128) String regenerateNonce) {}
    public record SelectImage(long imageId) {}
    @PostMapping("/api/shots/{shotId}/image-tasks")
    ResponseEntity<TaskService.Submission> submit(@AuthenticationPrincipal Viewer viewer, @PathVariable long shotId, @Valid @RequestBody GenerateImage body) {
        var task=images.submitShot(viewer.id(), shotId, body.regenerateNonce());
        return ResponseEntity.accepted().location(java.net.URI.create("/api/tasks/" + task.taskId())).body(task);
    }
    @PutMapping("/api/shots/{shotId}/selected-image")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void select(@AuthenticationPrincipal Viewer viewer, @PathVariable long shotId, @RequestBody SelectImage body) {
        images.select(viewer.id(), shotId, body.imageId());
    }
    @GetMapping("/api/shot-images/{imageId}/image")
    ResponseEntity<Resource> image(@AuthenticationPrincipal Viewer viewer, @PathVariable long imageId) {
        var image=images.image(viewer.id(), imageId);
        return ResponseEntity.ok().contentType(MediaType.valueOf(image.mediaType())).header(HttpHeaders.CACHE_CONTROL,"private, no-store").body(image.resource());
    }
}
