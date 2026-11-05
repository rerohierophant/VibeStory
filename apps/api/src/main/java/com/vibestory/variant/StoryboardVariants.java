package com.vibestory.variant;

import com.vibestory.common.ApiException;
import com.vibestory.common.ScopeGuard;
import com.vibestory.common.TextInput;
import com.vibestory.image.ImageTaskService;
import com.vibestory.project.ProjectAccess;
import com.vibestory.provider.ImageProvider;
import com.vibestory.provider.ShotDraftValidation;
import com.vibestory.shot.ShotService;
import com.vibestory.task.InputSignature;
import com.vibestory.task.WorkerProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.*;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static com.vibestory.project.ProjectAccess.Resource.EPISODE;

@Service
public class StoryboardVariants {
    private static final int MAX_VARIANTS = 6;
    private static final int MAX_SHOTS = 12;
    private final DSLContext db;
    private final ScopeGuard scope;
    private final ProjectAccess access;
    private final ImageTaskService images;
    private final ShotService shots;
    private final InputSignature signatures;
    private final WorkerProperties worker;

    public StoryboardVariants(DSLContext db, ScopeGuard scope, ProjectAccess access, ImageTaskService images,
                              ShotService shots, InputSignature signatures, WorkerProperties worker) {
        this.db = db;
        this.scope = scope;
        this.access = access;
        this.images = images;
        this.shots = shots;
        this.signatures = signatures;
        this.worker = worker;
    }

    @Schema(name = "StoryboardVariantEdit")
    public record Edit(long shotId, @NotNull String description, @NotNull String shotSize, @NotNull String cameraMove) {}
    @Schema(name = "StoryboardVariantSubmit")
    public record Submit(@NotNull UUID requestKey, String name, Long baseRevisionId,
                         @NotEmpty @Size(max = MAX_SHOTS) List<@NotNull @Valid Edit> shots) {}
    @Schema(name = "StoryboardVariantTarget")
    public record Target(long shotId, long imageId, long shotVersion, Long selectedImageId) {}
    @Schema(name = "StoryboardVariantAdopt")
    public record Adopt(@NotNull UUID requestKey, @NotEmpty @Size(max = MAX_SHOTS) List<@NotNull Target> shots) {}
    @Schema(name = "StoryboardVariantMember")
    public record VariantMember(String shotId, int seq, String description, String shotSize, String cameraMove, String taskId,
                                String state, String error, boolean retryable, String imageId, String imageUrl,
                                boolean reused, ImageProvider.Sources sources) {}
    @Schema(name = "StoryboardVariant")
    public record Variant(String id, String name, String revisionId, int revision, boolean archived, List<VariantMember> members) {}
    @Schema(name = "StoryboardVariantOverview")
    public record Overview(boolean canEdit, List<Variant> variants) {}

    @Transactional(readOnly = true)
    public Overview list(long actorId, long episodeId) {
        var grant = access.read(actorId, EPISODE, episodeId);
        var variants = db.fetch("""
            select latest.id from storyboard_variant v
            cross join lateral (
                select id from storyboard_variant_revision where variant_id=v.id order by number desc limit 1
            ) latest
            where v.episode_id=? and not v.archived order by v.id
            """, episodeId).getValues("id", Long.class).stream().map(id -> view(episodeId, id)).toList();
        return new Overview(access.canEdit(grant), variants);
    }

    @Transactional
    public Variant submit(long actorId, long episodeId, Submit request) {
        var grant = lockEpisode(actorId, episodeId);
        var edits = new TreeMap<Long, Edit>();
        for (var edit : request.shots()) {
            TextInput.validate(edit.description(), 16000, "画面描述");
            if (edit.description().isBlank() || !ShotDraftValidation.SIZES.contains(edit.shotSize())
                || !ShotDraftValidation.MOVES.contains(edit.cameraMove()))
                throw ApiException.badRequest("请输入有效的画面描述、景别和运镜");
            if (edits.putIfAbsent(edit.shotId(), edit) != null) throw ApiException.badRequest("镜头不能重复");
        }
        String name = request.baseRevisionId() == null ? TextInput.name(request.name(), "方案名称") : null;
        String signature = signatures.of(new Submit(request.requestKey(), name, request.baseRevisionId(),
            List.copyOf(edits.values()))).signature();
        var replay = db.fetchOne("select id,request_signature from storyboard_variant_revision where episode_id=? and request_key=?",
            episodeId, request.requestKey());
        if (replay != null) {
            checkReplay(replay, signature);
            return view(episodeId, replay.get("id", Long.class));
        }

        long variantId;
        int number;
        if (request.baseRevisionId() == null) {
            int count = db.fetchOne("select count(*) from storyboard_variant where episode_id=? and not archived",
                episodeId).get(0, Integer.class);
            if (count >= MAX_VARIANTS) throw ApiException.conflict("每集最多保留 6 个活动方案，请先归档一个方案");
            variantId = db.fetchOne("insert into storyboard_variant(episode_id,name,created_by) values (?,?,?) returning id",
                episodeId, name, actorId).get(0, Long.class);
            number = 1;
        } else {
            var base = currentRevision(episodeId, request.baseRevisionId());
            variantId = base.get("variant_id", Long.class);
            number = base.get("number", Integer.class) + 1;
            var members = db.fetch("""
                select m.shot_id,t.status from storyboard_variant_member m join task t on t.id=m.task_id where m.revision_id=?
                """, request.baseRevisionId()).intoMap("shot_id");
            if (!members.keySet().containsAll(edits.keySet())) throw ApiException.notFound();
            if (edits.keySet().stream().anyMatch(id -> "UNKNOWN".equals(members.get(id).get("status", String.class))))
                throw ApiException.conflict("该镜头上一次出图结果未知，请先确认原任务是否重试");
        }

        long revisionId = db.fetchOne("""
            insert into storyboard_variant_revision(variant_id,episode_id,number,request_key,request_signature,created_by)
            values (?,?,?,?,?,?) returning id
            """, variantId, episodeId, number, request.requestKey(), signature, actorId).get(0, Long.class);
        if (request.baseRevisionId() != null) {
            db.execute("""
                insert into storyboard_variant_member(revision_id,shot_id,task_id,description,shot_size,camera_move)
                select ?,shot_id,task_id,description,shot_size,camera_move from storyboard_variant_member where revision_id=?
                """, revisionId, request.baseRevisionId());
        }
        for (var edit : edits.values()) {
            requireShot(grant, episodeId, edit.shotId());
            var task = images.submitVariant(grant.partitionUserId(), edit.shotId(), revisionId,
                edit.description(), edit.shotSize(), edit.cameraMove());
            access.recordTask(grant, task.taskId());
            db.execute("""
                insert into storyboard_variant_member(revision_id,shot_id,task_id,description,shot_size,camera_move)
                values (?,?,?,?,?,?) on conflict (revision_id,shot_id) do update
                set task_id=excluded.task_id,description=excluded.description,shot_size=excluded.shot_size,camera_move=excluded.camera_move
                """, revisionId, edit.shotId(), Long.parseLong(task.taskId()), edit.description(), edit.shotSize(), edit.cameraMove());
        }
        return view(episodeId, revisionId);
    }

    @Transactional
    public void adopt(long actorId, long episodeId, long revisionId, Adopt request) {
        var grant = lockEpisode(actorId, episodeId);
        var targets = request.shots().stream().sorted(Comparator.comparingLong(Target::shotId)).toList();
        if (targets.stream().map(Target::shotId).distinct().count() != targets.size())
            throw ApiException.badRequest("镜头不能重复");
        String signature = signatures.of(List.of(revisionId, targets)).signature();
        var replay = db.fetchOne("select request_signature from storyboard_variant_adoption where episode_id=? and request_key=?",
            episodeId, request.requestKey());
        if (replay != null) {
            checkReplay(replay, signature);
            return;
        }
        currentRevision(episodeId, revisionId);
        for (var target : targets) {
            var shot = requireShot(grant, episodeId, target.shotId());
            var selectedImage = db.fetchOne("select id from shot_image where shot_id=? and selected", target.shotId());
            Long selected = selectedImage == null ? null : selectedImage.get("id", Long.class);
            if (target.shotVersion() != shot.get("version", Long.class) || !Objects.equals(selected, target.selectedImageId()))
                throw ApiException.conflict("镜头或选图已变化，请重新比较后再采纳");
            var member = db.fetchOne("""
                select m.* from storyboard_variant_member m join shot_image i on i.task_id=m.task_id and i.shot_id=m.shot_id
                where m.revision_id=? and m.shot_id=? and i.id=? and i.status='READY'
                """, revisionId, target.shotId(), target.imageId());
            if (member == null) throw ApiException.conflict("所选分镜图尚未就绪或已变化");
            shots.update(grant.partitionUserId(), target.shotId(), member.get("description", String.class), null, null,
                member.get("shot_size", String.class), member.get("camera_move", String.class), null, target.shotVersion());
            db.execute("update shot_image set visible_in_storyboard=true where id=?", target.imageId());
            images.select(grant.partitionUserId(), target.shotId(), target.imageId());
        }
        db.execute("""
            insert into storyboard_variant_adoption(episode_id,revision_id,request_key,request_signature,targets,actor_id)
            values (?,?,?,?,?::jsonb,?)
            """, episodeId, revisionId, request.requestKey(), signature, signatures.of(targets).json(), actorId);
    }

    @Transactional
    public void archive(long actorId, long episodeId, long variantId) {
        lockEpisode(actorId, episodeId);
        if (db.execute("update storyboard_variant set archived=true where episode_id=? and id=?", episodeId, variantId) == 0)
            throw ApiException.notFound();
    }

    private ProjectAccess.Grant lockEpisode(long actorId, long episodeId) {
        var grant = access.edit(actorId, EPISODE, episodeId);
        scope.lockEpisode(grant.partitionUserId(), episodeId);
        return grant;
    }

    private Record requireShot(ProjectAccess.Grant grant, long episodeId, long id) {
        if (!db.fetchExists(db.selectOne().from("shot").where("id=? and episode_id=? and project_id=?", id, episodeId, grant.projectId())))
            throw ApiException.notFound();
        return scope.shot(grant.partitionUserId(), id);
    }

    private Record currentRevision(long episodeId, long revisionId) {
        var row = revision(episodeId, revisionId);
        if (row.get("archived", Boolean.class)) throw ApiException.conflict("方案已归档");
        if (db.fetchExists(db.selectOne().from("storyboard_variant_revision")
            .where("variant_id=? and number>?", row.get("variant_id", Long.class), row.get("number", Integer.class))))
            throw ApiException.conflict("方案已有新版本，请刷新后重试");
        return row;
    }

    private Record revision(long episodeId, long revisionId) {
        var row = db.fetchOne("""
            select r.*,v.name,v.archived from storyboard_variant_revision r join storyboard_variant v on v.id=r.variant_id
            where r.episode_id=? and r.id=?
            """, episodeId, revisionId);
        if (row == null) throw ApiException.notFound();
        return row;
    }

    private Variant view(long episodeId, long revisionId) {
        var revision = revision(episodeId, revisionId);
        var members = db.fetch("""
            select m.*,s.seq,t.status,t.retryable,t.attempt_count,t.error_message,t.dispatch_blocked,t.admission_wait_reason,
                t.storyboard_variant_revision_id as source_revision,t.input_snapshot,i.id as image_id
            from storyboard_variant_member m join shot s on s.id=m.shot_id join task t on t.id=m.task_id
            left join shot_image i on i.task_id=t.id and i.status='READY'
            where m.revision_id=? order by s.seq
            """, revisionId).map(row -> {
            String id = row.get("image_id", String.class);
            String state = row.get("dispatch_blocked") == null ? row.get("status", String.class).toLowerCase(Locale.ROOT) : "paused";
            String error = row.get("error_message", String.class);
            if (error == null && row.get("admission_wait_reason") != null) error = "等待生成额度";
            if (state.equals("paused")) error = "任务派发已暂停";
            boolean retryable = (state.equals("unknown") || state.equals("failed") && row.get("retryable", Boolean.class))
                && row.get("attempt_count", Integer.class) < worker.maxAttempts();
            return new VariantMember(row.get("shot_id", String.class), row.get("seq", Integer.class), row.get("description", String.class),
                row.get("shot_size", String.class), row.get("camera_move", String.class), row.get("task_id", String.class), state, error,
                retryable, id, id == null ? null : "/api/shot-images/" + id + "/image",
                !Objects.equals(row.get("source_revision", Long.class), revisionId),
                images.input(row.get("input_snapshot", String.class)).sources());
        });
        return new Variant(revision.get("variant_id", String.class), revision.get("name", String.class), Long.toString(revisionId),
            revision.get("number", Integer.class), revision.get("archived", Boolean.class), members);
    }

    private void checkReplay(Record replay, String signature) {
        if (!signature.equals(replay.get("request_signature", String.class)))
            throw ApiException.conflict("同一请求标识不能用于不同的方案操作");
    }
}
