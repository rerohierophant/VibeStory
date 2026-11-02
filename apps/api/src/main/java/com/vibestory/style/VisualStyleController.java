package com.vibestory.style;

import com.vibestory.auth.Viewer;
import java.net.URI;
import java.util.List;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/visual-styles")
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "session")
public class VisualStyleController {
    private final VisualStyleService styles;
    public VisualStyleController(VisualStyleService styles) { this.styles = styles; }

    @io.swagger.v3.oas.annotations.media.Schema(name = "StyleUpload")
    public record Upload(@NotBlank @Size(max = 120) String label,
                         @NotNull @io.swagger.v3.oas.annotations.media.Schema(type = "string", format = "binary") MultipartFile image) {}

    @GetMapping
    List<VisualPreset> list(@AuthenticationPrincipal Viewer viewer) { return styles.list(viewer.id()); }

    @GetMapping("/{id}")
    VisualPreset get(@AuthenticationPrincipal Viewer viewer, @PathVariable String id) { return styles.resolve(viewer.id(), id); }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    ResponseEntity<VisualPreset> create(@AuthenticationPrincipal Viewer viewer, @Valid @ModelAttribute Upload upload) {
        var style = styles.create(viewer.id(), upload.label(), upload.image());
        return ResponseEntity.created(URI.create("/api/visual-styles/" + style.id())).body(style);
    }

    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@AuthenticationPrincipal Viewer viewer, @PathVariable String id) { styles.delete(viewer.id(), id); }

    @GetMapping("/{id}/image")
    ResponseEntity<Resource> image(@AuthenticationPrincipal Viewer viewer, @PathVariable String id) {
        var image = styles.image(viewer.id(), id);
        return ResponseEntity.ok().contentType(MediaType.valueOf(image.mediaType()))
            .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
            .header("X-Content-Type-Options", "nosniff").body(image.resource());
    }
}
