package com.vibestory.task;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vibestory.common.ApiException;
import com.vibestory.common.ScopeGuard;
import com.vibestory.storage.LocalStorage;
import com.vibestory.breakdown.BreakdownService;
import com.vibestory.image.ImageTaskService;
import com.vibestory.provider.ImageProvider;
import com.vibestory.provider.VideoProperties;
import java.util.List;
import java.util.Map;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TaskService {
    private final DSLContext db;
    private final ScopeGuard scope;
    private final InputSignature signatures;
    private final ObjectMapper json;
    private final LocalStorage storage;
    private final WorkerProperties worker;
    private final BreakdownService breakdowns;
    private final ImageTaskService images;
    private final VideoProperties videoConfig;

    public TaskService(DSLContext db, ScopeGuard scope, InputSignature signatures, ObjectMapper json,
                       LocalStorage storage, WorkerProperties worker, BreakdownService breakdowns, ImageTaskService images, VideoProperties videoConfig) {
        this.db = db;
        this.scope = scope;
        this.signatures = signatures;
        this.json = json;
        this.storage = storage;
        this.worker = worker;
        this.breakdowns = breakdowns;
        this.images=images;
        this.videoConfig=videoConfig;
    }

    public record VideoInput(String type, long targetId, String provider, String prompt, String visualType,
                             String style, long sourceImageId, long sourceImageVersion, String sourceImageKey,
                             int durationMs, Map<String, Integer> params, String regenerateNonce, String endpoint, String model) {}
    public record Submission(String taskId) {}
    public record Attempt(int number, String state, String errorCode, String model, String diagnosticUrl, String externalTaskId) {}
    @io.swagger.v3.oas.annotations.media.Schema(name = "Task")
    public record View(String id,
                       @io.swagger.v3.oas.annotations.media.Schema(allowableValues = {"idle", "queued", "running", "failed", "unknown"})
                       String state, boolean ready, String basedOn, int attempts,
                       String error, String errorCode, boolean retryable, String videoUrl, List<Attempt> history,
                       String type, String targetId, ImageProvider.Guidance imageGuidance, boolean resumable, String waitReason, java.time.OffsetDateTime notBefore) {}

    @Transactional
    public Submission submit(long userId, long shotId, String nonce) {
        return submitBatchMember(userId,shotId,nonce,false);
    }

    @Transactional(noRollbackFor = ApiException.class)
    public Submission submitBatchMember(long userId,long shotId,String nonce,boolean rejectConflictingActive) {
        return submitBatchMember(userId, shotId, nonce, rejectConflictingActive, null);
    }

    @Transactional(noRollbackFor = ApiException.class)
    public Submission submitBatchMember(long userId, long shotId, String nonce, boolean rejectConflictingActive,
                                        Long expectedFirstFrameId) {
        Record shot = scope.shot(userId, shotId);
        if (!"CONFIRMED".equals(shot.get("status", String.class))) throw ApiException.conflict("镜头确认后才能生成视频");
        Record image = db.fetchOne("select * from shot_image where shot_id = ? and selected and status = 'READY'", shotId);
        if (image == null) throw ApiException.conflict("请先选定一张已就绪的分镜图");
        if (expectedFirstFrameId != null && !expectedFirstFrameId.equals(image.get("id", Long.class)))
            throw ApiException.conflict("当前选中的首帧已变化，请重新读取并确认");
        if (nonce!=null && (nonce.isBlank() || nonce.length()>128)) throw ApiException.badRequest("重新生成标识无效");
        String prompt=shot.get("description",String.class);
        boolean real=videoConfig.provider().equals("minimax");
        if(real) {
            prompt += "；景别："+shot.get("shot_size",String.class)+"；运镜："+shot.get("camera_move",String.class);
            if(prompt.codePointCount(0,prompt.length())>2000) throw ApiException.badRequest("视频提示词不能超过 2000 字符");
            try(var stream=javax.imageio.ImageIO.createImageInputStream(storage.path(image.get("storage_key",String.class)).toFile())) {
                if(stream==null) throw new java.io.IOException("首帧无效");
                var readers=javax.imageio.ImageIO.getImageReaders(stream);
                if(!readers.hasNext()) throw new java.io.IOException("首帧格式无效");
                var reader=readers.next();
                try {
                    reader.setInput(stream); int w=reader.getWidth(0),h=reader.getHeight(0);
                    if(Math.min(w,h)<=300 || (double)w/h<0.4 || (double)w/h>2.5) throw ApiException.badRequest("MiniMax 首帧短边须大于 300 像素，宽高比须在 2:5 至 5:2 之间");
                } finally {reader.dispose();}
            } catch(java.io.IOException error) { throw ApiException.conflict("提交时的首帧不可读取"); }
        }
        var input = signatures.of(new VideoInput("VIDEO", shotId, videoConfig.provider(), prompt,
            shot.get("visual_type", String.class), shot.get("style", String.class),
            image.get("id", Long.class), image.get("version", Long.class), image.get("storage_key", String.class),
            shot.get("duration_ms", Integer.class), Map.of("fps",24,"outputDurationMs",real?6000:3000), nonce,
            real?videoConfig.baseUrl().replaceAll("/+$",""):null, real?videoConfig.model():null));
        if(rejectConflictingActive && db.fetchOne("select id from task where user_id=? and type='VIDEO' and target_id=? and status in ('QUEUED','RUNNING') and input_signature<>? limit 1",userId,shotId,input.signature())!=null)
            throw ApiException.conflict("同镜头已有不同输入的活跃视频任务");
        var unknown=db.fetchOne("select id from task where user_id=? and type='VIDEO' and target_id=? and input_signature=? and status='UNKNOWN'",userId,shotId,input.signature());
        if(unknown!=null) throw ApiException.conflict("同一输入的视频结果未知，请在原任务确认重试或继续查询");
        // Partial-index upsert stays in a valid transaction under concurrent duplicate submissions.
        Record task = db.fetchOne("""
            insert into task(user_id, project_id, episode_id, type, target_type, target_id, provider, input_signature, input_snapshot)
            values (?, ?, ?, 'VIDEO', 'SHOT', ?, ?, ?, ?::jsonb)
            on conflict (user_id, type, target_type, target_id, input_signature)
                where status in ('QUEUED', 'RUNNING')
            do update set input_signature = excluded.input_signature
            returning id
            """, userId, shot.get("project_id", Long.class), shot.get("episode_id", Long.class), shotId,
            videoConfig.provider(), input.signature(), input.json());
        return new Submission(task.get("id", Long.class).toString());
    }

    @Transactional(readOnly = true)
    public View get(long userId, long taskId) {
        scope.task(userId, taskId);
        Record task = db.fetchOne("""
            select t.*, v.source_image_id, v.storage_key from task t left join video v on v.task_id = t.id
            where t.id = ?
            """, taskId);
        if (task == null) throw ApiException.notFound();
        var history = db.fetch("select * from task_attempt where task_id = ? order by attempt_no", taskId).stream()
            .map(attempt -> new Attempt(attempt.get("attempt_no", Integer.class),
                attempt.get("status", String.class).toLowerCase(java.util.Locale.ROOT), attempt.get("error_code", String.class),
                attempt.get("actual_model", String.class), attempt.get("raw_response") == null ? null : "/api/tasks/" + taskId + "/attempts/" + attempt.get("attempt_no", Integer.class) + "/diagnostics",attempt.get("external_task_id",String.class)))
            .toList();
        boolean video = task.get("type", String.class).equals("VIDEO");
        boolean ready = video ? task.get("storage_key", String.class) != null : task.get("status", String.class).equals("SUCCEEDED") && task.get("output_snapshot") != null;
        if (task.get("type", String.class).endsWith("IMAGE"))
            ready = task.get("type", String.class).equals("IMAGE")
                ? db.fetchOne("select id from shot_image where task_id=? and status='READY'",taskId) != null
                : db.fetchOne("select id from asset_version where task_id=?",taskId) != null;
        String state = switch (task.get("status", String.class)) {
            case "QUEUED" -> "queued";
            case "RUNNING" -> "running";
            case "FAILED" -> "failed";
            case "UNKNOWN" -> "unknown";
            case "CANCELED" -> "failed";
            default -> "idle";
        };
        // basedOn is the submitted image even before the video is ready, never the shot's current selection.
        String sourceImageId = video ? Long.toString(input(task.get("input_snapshot", String.class)).sourceImageId()) : null;
        return new View(Long.toString(taskId), state, ready, sourceImageId,
            task.get("attempt_count", Integer.class), task.get("error_message", String.class), task.get("error_code", String.class),
            task.get("retryable", Boolean.class) && task.get("attempt_count", Integer.class) < worker.maxAttempts(),
            ready && video ? "/api/tasks/" + taskId + "/video" : null, history,
            task.get("type", String.class).toLowerCase(java.util.Locale.ROOT), task.get("target_id", String.class),
            task.get("type", String.class).endsWith("IMAGE") ? images.input(task.get("input_snapshot", String.class)).guidance() : null,
            task.get("status",String.class).equals("UNKNOWN") && !history.isEmpty() && history.getLast().externalTaskId()!=null,
            task.get("admission_wait_reason",String.class), task.get("not_before",java.time.OffsetDateTime.class));
    }

    @Transactional
    public Submission retry(long userId, long taskId, boolean acknowledgeUnknown) {
        scope.lockTaskProject(userId, taskId);
        Record task = db.fetchOne("select * from task where id = ? for update", taskId);
        Record shot=task.get("type", String.class).equals("VIDEO") ? scope.shot(userId,task.get("target_id",Long.class)) : null;
        String status = task.get("status", String.class);
        if (status.equals("QUEUED") || status.equals("RUNNING")) return new Submission(Long.toString(taskId));
        boolean resume=status.equals("UNKNOWN") && task.get("type",String.class).equals("VIDEO") && db.fetchOne("select id from task_attempt where task_id=? and attempt_no=? and external_task_id is not null",taskId,task.get("attempt_count",Integer.class))!=null;
        if(shot!=null && !resume && !shot.get("status",String.class).equals("CONFIRMED")) throw ApiException.conflict("镜头确认后才能重试生成视频");
        if (!resume && task.get("attempt_count", Integer.class) >= worker.maxAttempts()) throw ApiException.conflict("已达到最大尝试次数");
        if (status.equals("UNKNOWN")) {
            if (!acknowledgeUnknown) throw ApiException.conflict("外部结果未知，请明确确认重试");
        } else if (!status.equals("FAILED") || !task.get("retryable", Boolean.class)) {
            throw ApiException.conflict("该任务不可重试");
        }
        if (!db.fetch("""
            select id from task where user_id = ? and type = ? and target_type = ?
                and target_id = ? and input_signature = ? and status in ('QUEUED', 'RUNNING')
            """, userId, task.get("type", String.class), task.get("target_type", String.class), task.get("target_id", Long.class), task.get("input_signature", String.class)).isEmpty()) {
            throw ApiException.conflict("同一输入已有活跃任务");
        }
        if (task.get("type", String.class).equals("BREAKDOWN")) breakdowns.prepareRetry(userId, task);
        if (task.get("type", String.class).endsWith("IMAGE")) images.prepareRetry(userId, task);
        db.execute("""
            update task set status = 'QUEUED', error_code = null, error_message = null, retryable = false,
                finished_at = null, updated_at = now(), version = version + 1 where id = ?
            """, taskId);
        return new Submission(Long.toString(taskId));
    }

    public record Diagnostics(String provider, String model, String rawResponse, String status, String errorCode) {}

    @Transactional(readOnly = true)
    public Diagnostics diagnostics(long userId, long taskId, int attempt, boolean includeRaw) {
        scope.task(userId, taskId);
        var row = db.fetchOne("""
            select t.provider,a.actual_model,case when ? then a.raw_response end as raw_response,a.status,a.error_code
            from task_attempt a join task t on t.id=a.task_id where a.task_id=? and a.attempt_no=?
            """, includeRaw, taskId, attempt);
        if (row == null || includeRaw && row.get("raw_response") == null) throw ApiException.notFound();
        return new Diagnostics(row.get("provider", String.class), row.get("actual_model", String.class),
            row.get("raw_response", String.class), row.get("status", String.class), row.get("error_code", String.class));
    }

    @Transactional(readOnly = true)
    public Resource video(long userId, long taskId) {
        scope.task(userId, taskId);
        Record video = db.fetchOne("select storage_key from video where task_id = ?", taskId);
        if (video == null) throw ApiException.notFound();
        Resource resource = storage.resource(video.get("storage_key", String.class));
        if (!resource.isReadable()) throw ApiException.notFound();
        return resource;
    }

    public VideoInput input(String snapshot) {
        try { return json.readValue(snapshot, VideoInput.class); }
        catch (JsonProcessingException error) { throw new IllegalStateException("任务输入快照无效", error); }
    }
}
