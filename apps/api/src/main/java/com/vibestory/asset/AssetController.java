package com.vibestory.asset;

import com.vibestory.auth.Viewer;
import com.vibestory.project.ProjectAccess;
import java.util.List;
import com.vibestory.image.ImageTaskService;
import com.vibestory.task.TaskService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.http.*;
import org.springframework.core.io.Resource;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import static com.vibestory.project.ProjectAccess.Resource.*;

@RestController
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "session")
public class AssetController {
    private final AssetService assets;
    private final ImageTaskService images;
    private final ProjectAccess access;
    public AssetController(AssetService assets, ImageTaskService images, ProjectAccess access) { this.assets = assets; this.images=images; this.access = access; }
    public record GenerateAsset(@Size(max=128) String regenerateNonce) {}
    public record SelectVersion(long versionId) {}

    @GetMapping("/api/projects/{projectId}/characters")
    List<AssetService.Card> characters(@AuthenticationPrincipal Viewer viewer, @PathVariable long projectId) {
        return assets.list(access.read(viewer.id(), PROJECT, projectId).partitionUserId(), projectId, true);
    }
    @GetMapping("/api/projects/{projectId}/scenes")
    List<AssetService.Card> scenes(@AuthenticationPrincipal Viewer viewer, @PathVariable long projectId) {
        return assets.list(access.read(viewer.id(), PROJECT, projectId).partitionUserId(), projectId, false);
    }

    @GetMapping("/api/assets/{kind}")
    List<AssetService.Card> global(@AuthenticationPrincipal Viewer viewer, @PathVariable String kind) {
        return assets.global(viewer.id(), character(kind));
    }

    @PostMapping(value="/api/assets/{kind}/{assetId}/versions", consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    @Transactional
    @ResponseStatus(HttpStatus.CREATED)
    AssetService.Version upload(@AuthenticationPrincipal Viewer viewer, @PathVariable String kind, @PathVariable long assetId,
                                @RequestParam(required=false) @Size(max=120) String label, @RequestParam MultipartFile file) {
        boolean character = character(kind);
        var grant = access.edit(viewer.id(), character ? CHARACTER : SCENE, assetId);
        return assets.upload(grant.partitionUserId(), character, assetId, label, file);
    }

    @PostMapping("/api/assets/{kind}/{assetId}/image-tasks")
    @Transactional
    ResponseEntity<TaskService.Submission> generate(@AuthenticationPrincipal Viewer viewer, @PathVariable String kind, @PathVariable long assetId,
                                                   @Valid @RequestBody GenerateAsset body) {
        boolean character = character(kind);
        var grant = access.edit(viewer.id(), character ? CHARACTER : SCENE, assetId);
        var task = images.submitAsset(grant.partitionUserId(), character, assetId, body.regenerateNonce());
        access.recordTask(grant, task.taskId());
        return ResponseEntity.accepted().location(java.net.URI.create("/api/tasks/" + task.taskId())).body(task);
    }

    @PutMapping("/api/assets/{kind}/{assetId}/selected-version")
    @Transactional
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void select(@AuthenticationPrincipal Viewer viewer, @PathVariable String kind, @PathVariable long assetId, @RequestBody SelectVersion body) {
        boolean character = character(kind);
        var grant = access.edit(viewer.id(), character ? CHARACTER : SCENE, assetId);
        assets.select(grant.partitionUserId(), character, assetId, body.versionId());
    }

    @GetMapping("/api/asset-versions/{versionId}/image")
    ResponseEntity<Resource> image(@AuthenticationPrincipal Viewer viewer, @PathVariable long versionId) {
        var image = assets.image(access.read(viewer.id(), ASSET_VERSION, versionId).partitionUserId(), versionId);
        return ResponseEntity.ok().contentType(MediaType.valueOf(image.mediaType())).header(HttpHeaders.CACHE_CONTROL, "private, no-store").body(image.resource());
    }

    private boolean character(String kind) {
        return switch(kind) { case "characters" -> true; case "scenes" -> false; default -> throw com.vibestory.common.ApiException.notFound(); };
    }
}
