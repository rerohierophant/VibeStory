package com.vibestory.task;

import com.vibestory.auth.Viewer;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.net.URI;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "session")
public class TaskController {
    private final TaskService tasks;
    public TaskController(TaskService tasks) { this.tasks = tasks; }

    public record Submit(@Size(max = 128) String regenerateNonce) {}
    public record Retry(boolean acknowledgeUnknown) {}

    @PostMapping("/api/shots/{shotId}/video-tasks")
    @ResponseStatus(org.springframework.http.HttpStatus.ACCEPTED)
    ResponseEntity<TaskService.Submission> submit(@AuthenticationPrincipal Viewer viewer,
            @PathVariable long shotId, @Valid @RequestBody Submit body) {
        return accepted(tasks.submit(viewer.id(), shotId, body.regenerateNonce()));
    }

    @GetMapping("/api/tasks/{taskId}")
    TaskService.View get(@AuthenticationPrincipal Viewer viewer, @PathVariable long taskId) {
        return tasks.get(viewer.id(), taskId);
    }

    @GetMapping("/api/tasks/{taskId}/attempts/{attempt}/diagnostics")
    TaskService.Diagnostics diagnostics(@AuthenticationPrincipal Viewer viewer, @PathVariable long taskId, @PathVariable int attempt) {
        return tasks.diagnostics(viewer.id(), taskId, attempt);
    }

    @PostMapping("/api/tasks/{taskId}/retry")
    @ResponseStatus(org.springframework.http.HttpStatus.ACCEPTED)
    ResponseEntity<TaskService.Submission> retry(@AuthenticationPrincipal Viewer viewer,
            @PathVariable long taskId, @RequestBody Retry body) {
        return accepted(tasks.retry(viewer.id(), taskId, body.acknowledgeUnknown()));
    }

    @GetMapping(value = "/api/tasks/{taskId}/video", produces = "video/mp4")
    ResponseEntity<Resource> video(@AuthenticationPrincipal Viewer viewer, @PathVariable long taskId) {
        return ResponseEntity.ok().contentType(MediaType.valueOf("video/mp4"))
            .header(HttpHeaders.CACHE_CONTROL, "private, no-store").body(tasks.video(viewer.id(), taskId));
    }

    private ResponseEntity<TaskService.Submission> accepted(TaskService.Submission task) {
        return ResponseEntity.accepted().location(URI.create("/api/tasks/" + task.taskId())).body(task);
    }
}
