package com.vibestory.experience;

import com.vibestory.common.ApiException;
import com.vibestory.common.ScopeGuard;
import com.vibestory.common.TextInput;
import com.vibestory.image.ImageTaskService;
import com.vibestory.task.InputSignature;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ImageCreationService {
    @io.swagger.v3.oas.annotations.media.Schema(name = "ImageCreationRequest")
    public record Request(@NotNull UUID requestKey, @NotNull ImageExperience.TargetType targetType, long targetId,
                          Long sourceTaskId, @NotBlank String instruction, UUID recipeVersionId, Map<String, String> parameters) {}
    private final DSLContext db;
    private final ScopeGuard scope;
    private final ImageExperience experience;
    private final ImageTaskService images;
    private final InputSignature signatures;

    public ImageCreationService(DSLContext db, ScopeGuard scope, ImageExperience experience, ImageTaskService images, InputSignature signatures) {
        this.db = db;
        this.scope = scope;
        this.experience = experience;
        this.images = images;
        this.signatures = signatures;
    }

    @Transactional
    public ImageExperience.Creation create(long user, Request request) {
        TextInput.validate(request.instruction(), 1000, "图片生成要求");
        if (request.instruction().isBlank()) throw ApiException.badRequest("请填写图片生成要求");
        var target = experience.target(user, request.targetType(), request.targetId());
        long project = target.get("project_id", Long.class);
        scope.lockProject(user, project);
        experience.lockUser(user);
        var parameters = request.parameters() == null ? Map.<String, String>of() : request.parameters();
        String signature = signatures.of(new Request(request.requestKey(), request.targetType(), request.targetId(), request.sourceTaskId(),
            request.instruction(), request.recipeVersionId(), parameters)).signature();
        var replay = db.fetchOne("select id,request_signature from image_creation where user_id=? and request_key=?", user, request.requestKey());
        if (replay != null) {
            experience.checkSignature(replay.get("request_signature", String.class), signature);
            return experience.creation(user, replay.get("id", UUID.class));
        }
        var recipe = experience.useRecipe(user, request.targetType(), request.recipeVersionId(), parameters);
        UUID id = UUID.randomUUID();
        var submission = images.submitCreative(user, request.targetType(), request.targetId(), request.sourceTaskId(),
            request.instruction().strip(), recipe, id, request.requestKey().toString());
        db.execute("""
            insert into image_creation(id,user_id,project_id,target_type,target_id,request_key,request_signature,source_task_id,result_task_id,instruction)
            values (?,?,?,?,?,?,?,?,?,?)
            """, id, user, project, request.targetType().name(), request.targetId(), request.requestKey(), signature,
            request.sourceTaskId(), Long.parseLong(submission.taskId()), request.instruction().strip());
        return experience.creation(user, id);
    }
}
