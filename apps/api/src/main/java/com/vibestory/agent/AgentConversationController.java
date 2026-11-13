package com.vibestory.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vibestory.auth.Viewer;
import com.vibestory.common.ApiException;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.io.IOException;
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
    private static final String NDJSON = "application/x-ndjson";
    private final AgentConversations conversations;
    private final ObjectMapper json;

    public AgentConversationController(AgentConversations conversations, ObjectMapper json) {
        this.conversations = conversations;
        this.json = json;
    }

    public record ChatInput(@NotNull UUID requestKey, @Size(max = 500) String instruction, @Positive Long projectId,
                            @Positive Long episodeId,
                            @Size(max = 4) List<MultipartFile> images) {}

    @GetMapping
    List<AgentConversations.ConversationHistoryItem> history(@AuthenticationPrincipal Viewer viewer) {
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

    public record ReplyEvent(String type, UUID executionId, String text, AgentConversations.Transcript transcript, Integer status) {}

    @PostMapping(value = "/{id}/messages/stream", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = NDJSON)
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "逐行输出回复事件",
        content = @io.swagger.v3.oas.annotations.media.Content(mediaType = NDJSON,
            schema = @io.swagger.v3.oas.annotations.media.Schema(implementation = ReplyEvent.class)))
    void stream(@AuthenticationPrincipal Viewer viewer, @PathVariable UUID id,
                @Valid @ModelAttribute ChatInput input, HttpServletResponse response) {
        var events = new ReplyEvents(response);
        try {
            var transcript = conversations.send(viewer.id(), id, input,
                (run, snapshot) -> events.write(new ReplyEvent("start", run, null, snapshot, null)),
                text -> events.write(new ReplyEvent(text.isEmpty() ? "reset" : "delta", null, text, null, null)));
            events.write(new ReplyEvent("complete", null, null, transcript, null));
        } catch (ApiException error) {
            if (!response.isCommitted()) throw error;
            events.write(new ReplyEvent("error", null, error.getMessage(), null, error.status().value()));
        }
    }

    private final class ReplyEvents {
        private final HttpServletResponse response;
        private boolean disconnected;

        private ReplyEvents(HttpServletResponse response) { this.response = response; }

        void write(ReplyEvent event) {
            if (disconnected) return;
            try {
                response.setContentType(NDJSON);
                response.setCharacterEncoding("UTF-8");
                response.setHeader(HttpHeaders.CACHE_CONTROL, "private, no-store, no-transform");
                response.setHeader("X-Accel-Buffering", "no");
                var output = response.getOutputStream();
                output.write(json.writeValueAsBytes(event));
                output.write('\n');
                response.flushBuffer();
            } catch (IOException clientDisconnected) {
                // The run belongs to the durable conversation, not to this HTTP connection.
                disconnected = true;
            }
        }
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
