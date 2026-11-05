package com.vibestory.image;

import com.vibestory.agent.EpisodeWorkflow;
import com.vibestory.batch.GenerationBatch;
import com.vibestory.auth.Viewer;
import com.vibestory.project.ProjectAccess;
import com.vibestory.task.TaskService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import static com.vibestory.project.ProjectAccess.Resource.*;

@RestController
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name="session")
public class ImageController {
    private final ImageTaskService images;
    private final EpisodeWorkflow workflow;
    private final ProjectAccess access;
    public ImageController(ImageTaskService images, ProjectAccess access, EpisodeWorkflow workflow) { this.images=images; this.access = access; this.workflow = workflow; }
    public record GenerateImage(@Size(max=128) String regenerateNonce) {}
    public record SelectImage(long imageId) {}
    @PostMapping("/api/shots/{shotId}/image-tasks")
    @Transactional
    ResponseEntity<TaskService.Submission> submit(@AuthenticationPrincipal Viewer viewer, @PathVariable long shotId, @Valid @RequestBody GenerateImage body) {
        var grant = access.edit(viewer.id(), SHOT, shotId);
        var task = workflow.generateShot(grant.partitionUserId(), shotId, GenerationBatch.Type.IMAGE, body.regenerateNonce(), false);
        access.recordTask(grant, task.taskId());
        return ResponseEntity.accepted().location(java.net.URI.create("/api/tasks/" + task.taskId())).body(task);
    }
    @PutMapping("/api/shots/{shotId}/selected-image")
    @Transactional
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void select(@AuthenticationPrincipal Viewer viewer, @PathVariable long shotId, @RequestBody SelectImage body) {
        workflow.selectFrame(access.edit(viewer.id(), SHOT, shotId).partitionUserId(), shotId, body.imageId());
    }
    @GetMapping("/api/shot-images/{imageId}/image")
    ResponseEntity<Resource> image(@AuthenticationPrincipal Viewer viewer, @PathVariable long imageId) {
        var image=images.image(access.read(viewer.id(), SHOT_IMAGE, imageId).partitionUserId(), imageId);
        return ResponseEntity.ok().contentType(MediaType.valueOf(image.mediaType())).header(HttpHeaders.CACHE_CONTROL,"private, no-store").body(image.resource());
    }
}
