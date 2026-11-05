package com.vibestory.breakdown;

import com.vibestory.agent.EpisodeWorkflow;
import com.vibestory.auth.Viewer;
import com.vibestory.project.ProjectAccess;
import com.vibestory.task.TaskService;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import static com.vibestory.project.ProjectAccess.Resource.*;

@RestController
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "session")
public class BreakdownController {
    private final BreakdownService breakdowns;
    private final EpisodeWorkflow workflow;
    private final TaskService tasks;
    private final ProjectAccess access;
    public BreakdownController(BreakdownService breakdowns, TaskService tasks, ProjectAccess access, EpisodeWorkflow workflow) { this.breakdowns = breakdowns; this.tasks = tasks; this.access = access; this.workflow = workflow; }
    @io.swagger.v3.oas.annotations.media.Schema(name = "SubmitBreakdown")
    public record Submit(boolean replaceExisting) {}

    @PostMapping("/api/episodes/{episodeId}/breakdowns") @ResponseStatus(HttpStatus.ACCEPTED)
    @Transactional
    ResponseEntity<TaskService.Submission> submit(@AuthenticationPrincipal Viewer viewer, @PathVariable long episodeId,
                                                  @RequestBody(required = false) Submit body) {
        var grant = access.edit(viewer.id(), EPISODE, episodeId);
        var task = workflow.breakdown(grant.partitionUserId(), episodeId, body != null && body.replaceExisting());
        access.recordTask(grant, task.taskId());
        return ResponseEntity.accepted().location(URI.create("/api/tasks/" + task.taskId())).body(task);
    }
    @GetMapping("/api/episodes/{episodeId}/breakdowns/latest")
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", content = @io.swagger.v3.oas.annotations.media.Content(
            schema = @io.swagger.v3.oas.annotations.media.Schema(implementation = TaskService.View.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "尚无拆解任务")})
    ResponseEntity<TaskService.View> latest(@AuthenticationPrincipal Viewer viewer, @PathVariable long episodeId) {
        long partition = access.read(viewer.id(), EPISODE, episodeId).partitionUserId();
        return breakdowns.latest(partition, episodeId).map(id -> ResponseEntity.ok(tasks.get(partition, id)))
            .orElseGet(() -> ResponseEntity.noContent().build());
    }
}
