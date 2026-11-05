package com.vibestory.agent;

import com.vibestory.auth.Viewer;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/agent/conversations")
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "session")
public class AgentConversationController {
    private final AgentConversations conversations;

    public AgentConversationController(AgentConversations conversations) {
        this.conversations = conversations;
    }

    public record ChatInput(@NotNull UUID requestKey, @Size(max = 500) String instruction, @Positive Long projectId,
                            @Positive Long episodeId,
                            @Size(max = 4) List<MultipartFile> images) {}

    @GetMapping
    List<AgentConversations.Conversation> history(@AuthenticationPrincipal Viewer viewer) {
        return conversations.history(viewer.id());
    }

    @GetMapping("/{id}")
    AgentConversations.Transcript transcript(@AuthenticationPrincipal Viewer viewer, @PathVariable UUID id) {
        return conversations.transcript(viewer.id(), id);
    }

    @PostMapping(value = "/{id}/messages", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    AgentConversations.Transcript send(@AuthenticationPrincipal Viewer viewer, @PathVariable UUID id,
                                       @Valid @ModelAttribute ChatInput input) {
        return conversations.send(viewer.id(), id, input);
    }

    @PostMapping("/{id}/workflows/{workflowId}/confirm")
    AgentConversations.Transcript confirmWorkflow(@AuthenticationPrincipal Viewer viewer, @PathVariable UUID id,
                                                   @PathVariable UUID workflowId) {
        return conversations.confirmWorkflow(viewer.id(), id, workflowId);
    }

    @GetMapping("/images/{id}")
    ResponseEntity<byte[]> image(@AuthenticationPrincipal Viewer viewer, @PathVariable long id) {
        var image = conversations.image(viewer.id(), id);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(image.mediaType()))
            .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
            .header("X-Content-Type-Options", "nosniff").body(image.bytes());
    }
}
