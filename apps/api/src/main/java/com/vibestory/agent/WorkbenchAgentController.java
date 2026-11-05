package com.vibestory.agent;

import com.vibestory.auth.Viewer;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/agent")
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "session")
public class WorkbenchAgentController {
    private final WorkbenchAgent agent;

    public WorkbenchAgentController(WorkbenchAgent agent) { this.agent = agent; }

    public record AgentInstruction(@NotNull UUID requestKey, @NotBlank @Size(max = 500) String instruction,
                                   @Positive Long projectId, @Positive Long episodeId) {}

    @GetMapping("/skills")
    List<WorkbenchSkills.Entry> skills() { return WorkbenchSkills.catalog(); }

    @PostMapping("/commands")
    WorkbenchAgent.AgentResult run(@AuthenticationPrincipal Viewer viewer, @Valid @RequestBody AgentInstruction input) {
        return agent.run(viewer.id(), input);
    }
}
