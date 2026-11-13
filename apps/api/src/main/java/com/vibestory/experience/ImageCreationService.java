package com.vibestory.experience;

import com.vibestory.common.ApiException;
import com.vibestory.common.ScopeGuard;
import com.vibestory.common.TextInput;
import com.vibestory.image.ImageTaskService;
import com.vibestory.provider.ImageProviders;
import com.vibestory.style.ReferenceImage;
import com.vibestory.task.InputSignature;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class ImageCreationService {
    @io.swagger.v3.oas.annotations.media.Schema(name = "ImageCreationRequest")
    public record Request(@NotNull UUID requestKey, @NotNull ImageExperience.TargetType targetType, long targetId,
                          Long sourceTaskId, @NotNull String instruction, UUID recipeVersionId, Map<String, String> parameters) {}
    private final DSLContext db;
    private final ScopeGuard scope;
    private final ImageExperience experience;
    private final ImageTaskService images;
    private final InputSignature signatures;
    private final ReferenceImage referenceImages;
    private final ImageProviders providers;

    public ImageCreationService(DSLContext db, ScopeGuard scope, ImageExperience experience, ImageTaskService images,
                                InputSignature signatures, ReferenceImage referenceImages, ImageProviders providers) {
        this.db = db;
        this.scope = scope;
        this.experience = experience;
        this.images = images;
        this.signatures = signatures;
        this.referenceImages = referenceImages;
        this.providers = providers;
    }

    @Transactional
    public ImageExperience.Creation create(long user, Request request) {
        return create(user, request, null);
    }

    @Transactional
    public ImageExperience.Creation createWithReference(long user, Request request, MultipartFile reference) {
        return create(user, request, reference);
    }

    private ImageExperience.Creation create(long user, Request request, MultipartFile reference) {
        TextInput.validate(request.instruction(), 1000, "图片生成要求");
        var target = experience.target(user, request.targetType(), request.targetId());
        long project = target.get("project_id", Long.class);
        var encoded = reference == null ? null : referenceImages.read(reference);
        var parameters = request.parameters() == null ? Map.<String, String>of() : request.parameters();
        var normalized = new Request(request.requestKey(), request.targetType(), request.targetId(), request.sourceTaskId(),
            request.instruction(), request.recipeVersionId(), parameters);
        String signature = signatures.of(encoded == null ? normalized : List.of(normalized, signatures.of(encoded).signature())).signature();
        scope.lockProject(user, project);
        experience.lockUser(user);
        var replay = db.fetchOne("select id,request_signature from image_creation where user_id=? and request_key=?", user, request.requestKey());
        if (replay != null) {
            experience.checkSignature(replay.get("request_signature", String.class), signature);
            return experience.creation(user, replay.get("id", UUID.class));
        }
        if (encoded != null && !providers.supportsAssetReference(request.targetType().name()))
            throw ApiException.badRequest("当前图片模型不支持为此素材上传生成参考图");
        var recipe = experience.useRecipe(user, request.targetType(), request.recipeVersionId(), parameters);
        UUID id = UUID.randomUUID();
        var submission = images.submitCreative(user, request.targetType(), request.targetId(), request.sourceTaskId(),
            request.instruction().strip(), recipe, id, request.requestKey().toString(), encoded);
        db.execute("""
            insert into image_creation(id,user_id,project_id,target_type,target_id,request_key,request_signature,source_task_id,result_task_id,instruction)
            values (?,?,?,?,?,?,?,?,?,?)
            """, id, user, project, request.targetType().name(), request.targetId(), request.requestKey(), signature,
            request.sourceTaskId(), Long.parseLong(submission.taskId()), request.instruction().strip());
        return experience.creation(user, id);
    }
}
