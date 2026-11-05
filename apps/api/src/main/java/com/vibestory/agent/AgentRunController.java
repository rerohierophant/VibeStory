package com.vibestory.agent;

import com.vibestory.auth.Viewer;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;

@RestController
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "session")
class AgentRunController {
    private final AgentRuntime runtime;
    private final AgentContextManager contexts;
    AgentRunController(AgentRuntime runtime,AgentContextManager contexts) { this.runtime = runtime;this.contexts=contexts; }

    @GetMapping("/api/agent/runs/{id}")
    AgentRuntime.Execution execution(@AuthenticationPrincipal Viewer viewer, @PathVariable UUID id) {
        return runtime.view(viewer.id(), id);
    }
    @GetMapping("/api/agent/runs/{id}/tool-results/{callId}")
    AgentContextManager.Page result(@AuthenticationPrincipal Viewer viewer,@PathVariable UUID id,@PathVariable String callId,
        @RequestParam(defaultValue="0") int offset,@RequestParam(defaultValue="4096") int limit) {
        runtime.view(viewer.id(),id);
        return contexts.readResult(viewer.id(),id,callId,offset,limit);
    }
    @GetMapping("/api/agent/runs/{id}/history")
    AgentContextManager.Page history(@AuthenticationPrincipal Viewer viewer,@PathVariable UUID id,@RequestParam int through,
        @RequestParam(defaultValue="0") int offset,@RequestParam(defaultValue="4096") int limit) {
        runtime.view(viewer.id(),id);
        return contexts.readHistory(viewer.id(),id,through,offset,limit);
    }
}
