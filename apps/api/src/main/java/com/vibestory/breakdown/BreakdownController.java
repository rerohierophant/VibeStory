package com.vibestory.breakdown;

import com.vibestory.auth.Viewer;
import com.vibestory.task.TaskService;
import java.net.URI;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "session")
public class BreakdownController {
    private final BreakdownService breakdowns;
    private final TaskService tasks;
    public BreakdownController(BreakdownService breakdowns, TaskService tasks) { this.breakdowns = breakdowns; this.tasks = tasks; }
    @io.swagger.v3.oas.annotations.media.Schema(name = "SubmitBreakdown")
    public record Submit(boolean replaceExisting) {}

    @PostMapping("/api/episodes/{episodeId}/breakdowns") @ResponseStatus(HttpStatus.ACCEPTED)
    ResponseEntity<TaskService.Submission> submit(@AuthenticationPrincipal Viewer viewer, @PathVariable long episodeId,
                                                  @RequestBody(required = false) Submit body) {
        var task = breakdowns.submit(viewer.id(), episodeId, body != null && body.replaceExisting());
        return ResponseEntity.accepted().location(URI.create("/api/tasks/" + task.taskId())).body(task);
    }
    @GetMapping("/api/episodes/{episodeId}/breakdowns/latest")
    @io.swagger.v3.oas.annotations.responses.ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", content = @io.swagger.v3.oas.annotations.media.Content(
            schema = @io.swagger.v3.oas.annotations.media.Schema(implementation = TaskService.View.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "204", description = "尚无拆解任务")})
    ResponseEntity<TaskService.View> latest(@AuthenticationPrincipal Viewer viewer, @PathVariable long episodeId) {
        return breakdowns.latest(viewer.id(), episodeId).map(id -> ResponseEntity.ok(tasks.get(viewer.id(), id)))
            .orElseGet(() -> ResponseEntity.noContent().build());
    }
}
