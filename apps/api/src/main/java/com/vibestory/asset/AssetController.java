package com.vibestory.asset;

import com.vibestory.auth.Viewer;
import java.util.List;
import com.vibestory.image.ImageTaskService;
import com.vibestory.task.TaskService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.http.*;
import org.springframework.core.io.Resource;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "session")
public class AssetController {
    private final AssetService assets;
    private final ImageTaskService images;
    public AssetController(AssetService assets, ImageTaskService images) { this.assets = assets; this.images=images; }
    public record GenerateAsset(@Size(max=128) String regenerateNonce) {}
    public record SelectVersion(long versionId) {}

    @GetMapping("/api/projects/{projectId}/characters")
    List<AssetService.Card> characters(@AuthenticationPrincipal Viewer viewer, @PathVariable long projectId) {
        return assets.list(viewer.id(), projectId, true);
    }
    @GetMapping("/api/projects/{projectId}/scenes")
    List<AssetService.Card> scenes(@AuthenticationPrincipal Viewer viewer, @PathVariable long projectId) {
        return assets.list(viewer.id(), projectId, false);
    }

    @GetMapping("/api/assets/{kind}")
    List<AssetService.Card> global(@AuthenticationPrincipal Viewer viewer, @PathVariable String kind) {
        return assets.global(viewer.id(), character(kind));
    }

    @PostMapping(value="/api/assets/{kind}/{assetId}/versions", consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    AssetService.Version upload(@AuthenticationPrincipal Viewer viewer, @PathVariable String kind, @PathVariable long assetId,
                                @RequestParam(required=false) @Size(max=120) String label, @RequestParam MultipartFile file) {
        return assets.upload(viewer.id(), character(kind), assetId, label, file);
    }

    @PostMapping("/api/assets/{kind}/{assetId}/image-tasks")
    ResponseEntity<TaskService.Submission> generate(@AuthenticationPrincipal Viewer viewer, @PathVariable String kind, @PathVariable long assetId,
                                                   @Valid @RequestBody GenerateAsset body) {
        var task = images.submitAsset(viewer.id(), character(kind), assetId, body.regenerateNonce());
        return ResponseEntity.accepted().location(java.net.URI.create("/api/tasks/" + task.taskId())).body(task);
    }

    @PutMapping("/api/assets/{kind}/{assetId}/selected-version")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void select(@AuthenticationPrincipal Viewer viewer, @PathVariable String kind, @PathVariable long assetId, @RequestBody SelectVersion body) {
        assets.select(viewer.id(), character(kind), assetId, body.versionId());
    }

    @GetMapping("/api/asset-versions/{versionId}/image")
    ResponseEntity<Resource> image(@AuthenticationPrincipal Viewer viewer, @PathVariable long versionId) {
        var image = assets.image(viewer.id(), versionId);
        return ResponseEntity.ok().contentType(MediaType.valueOf(image.mediaType())).header(HttpHeaders.CACHE_CONTROL, "private, no-store").body(image.resource());
    }

    private boolean character(String kind) {
        return switch(kind) { case "characters" -> true; case "scenes" -> false; default -> throw com.vibestory.common.ApiException.notFound(); };
    }
}
