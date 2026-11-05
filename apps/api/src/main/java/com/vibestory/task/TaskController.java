package com.vibestory.task;

import com.vibestory.agent.EpisodeWorkflow;
import com.vibestory.batch.GenerationBatch;
import com.vibestory.auth.Viewer;
import com.vibestory.project.ProjectAccess;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.net.URI;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import static com.vibestory.project.ProjectAccess.Resource.*;

@RestController
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "session")
public class TaskController {
    private final TaskService tasks;
    private final EpisodeWorkflow workflow;
    private final ProjectAccess access;
    public TaskController(TaskService tasks, ProjectAccess access, EpisodeWorkflow workflow) { this.tasks = tasks; this.access = access; this.workflow = workflow; }

    public record Submit(@Size(max = 128) String regenerateNonce) {}
    public record Retry(boolean acknowledgeUnknown) {}

    @PostMapping("/api/shots/{shotId}/video-tasks")
    @Transactional
    @ResponseStatus(org.springframework.http.HttpStatus.ACCEPTED)
    ResponseEntity<TaskService.Submission> submit(@AuthenticationPrincipal Viewer viewer,
            @PathVariable long shotId, @Valid @RequestBody Submit body) {
        var grant = access.edit(viewer.id(), SHOT, shotId);
        var task = workflow.generateShot(grant.partitionUserId(), shotId, GenerationBatch.Type.VIDEO, body.regenerateNonce(), false);
        access.recordTask(grant, task.taskId());
        return accepted(task);
    }

    @GetMapping("/api/tasks/{taskId}")
    TaskService.View get(@AuthenticationPrincipal Viewer viewer, @PathVariable long taskId) {
        return tasks.get(access.read(viewer.id(), TASK, taskId).partitionUserId(), taskId);
    }

    @GetMapping("/api/tasks/{taskId}/attempts/{attempt}/diagnostics")
    TaskService.Diagnostics diagnostics(@AuthenticationPrincipal Viewer viewer, @PathVariable long taskId, @PathVariable int attempt) {
        var grant = access.authorize(viewer.id(), TASK, taskId, ProjectAccess.Action.DIAGNOSE);
        return tasks.diagnostics(grant.partitionUserId(), taskId, attempt, grant.teamId() == null);
    }

    @PostMapping("/api/tasks/{taskId}/retry")
    @Transactional
    @ResponseStatus(org.springframework.http.HttpStatus.ACCEPTED)
    ResponseEntity<TaskService.Submission> retry(@AuthenticationPrincipal Viewer viewer,
            @PathVariable long taskId, @RequestBody Retry body) {
        return accepted(tasks.retry(access.edit(viewer.id(), TASK, taskId).partitionUserId(), taskId, body.acknowledgeUnknown()));
    }

    @GetMapping(value = "/api/tasks/{taskId}/video", produces = "video/mp4")
    ResponseEntity<Resource> video(@AuthenticationPrincipal Viewer viewer, @PathVariable long taskId) {
        return ResponseEntity.ok().contentType(MediaType.valueOf("video/mp4"))
            .header(HttpHeaders.CACHE_CONTROL, "private, no-store").body(tasks.video(access.read(viewer.id(), TASK, taskId).partitionUserId(), taskId));
    }

    private ResponseEntity<TaskService.Submission> accepted(TaskService.Submission task) {
        return ResponseEntity.accepted().location(URI.create("/api/tasks/" + task.taskId())).body(task);
    }
}
