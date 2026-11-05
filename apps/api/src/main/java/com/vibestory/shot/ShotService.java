package com.vibestory.shot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vibestory.asset.AssetService;
import com.vibestory.common.ApiException;
import com.vibestory.common.ScopeGuard;
import com.vibestory.common.TextInput;
import com.vibestory.provider.ShotDraftValidation;
import com.vibestory.provider.TextProvider;
import com.vibestory.provider.ImageProvider;
import com.vibestory.storage.StorageCleanup;
import com.vibestory.task.WorkerProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ShotService {
    private final ShotRepository shots;
    private final ScopeGuard scope;
    private final AssetService assets;
    private final DSLContext db;
    private final ObjectMapper json;
    private final StorageCleanup cleanup;
    private final WorkerProperties worker;

    public ShotService(ShotRepository shots, ScopeGuard scope, AssetService assets, DSLContext db, ObjectMapper json, StorageCleanup cleanup, WorkerProperties worker) {
        this.shots = shots; this.scope = scope; this.assets = assets; this.db = db; this.json = json; this.cleanup = cleanup;
        this.worker=worker;
    }
    public record Image(String id, String label, String seed, long createdAt, JsonNode inputSnapshot, String url, ImageProvider.Guidance guidance) {}
    public record Video(boolean ready, String basedOn,
                        @io.swagger.v3.oas.annotations.media.Schema(allowableValues = {"idle", "queued", "running", "failed", "unknown"})
                        String state, int attempts, String error, String url, String taskId, boolean retryable, boolean resumable, Integer durationMs, String pendingBasedOn) {}
    @io.swagger.v3.oas.annotations.media.Schema(name = "Shot")
    public record View(String id, String projectId, String episodeId, int seq, String sceneName, String shotSize, String cameraMove,
                       String description, String dialogue, int durationMs,
                       @io.swagger.v3.oas.annotations.media.Schema(allowableValues = {"draft", "confirmed"}) String status,
                       List<String> characterNames, List<Image> images, String selectedImageId,
                       @io.swagger.v3.oas.annotations.media.Schema(allowableValues = {"idle", "queued", "running", "failed", "unknown"}) String imageState, Video video,
                       String imageTaskId, String imageError, boolean imageRetryable, long version) {}

    @Transactional(readOnly = true)
    public List<View> list(long userId, long episodeId, String filter) {
        scope.episode(userId, episodeId);
        if (!List.of("all", "todo", "draft").contains(filter)) throw ApiException.badRequest("未知镜头筛选条件");
        var rows = shots.findByEpisodeIdAndUserIdOrderBySeq(episodeId, userId);
        var images = db.fetch("select * from shot_image where episode_id = ? and status = 'READY' and visible_in_storyboard order by id", episodeId)
            .stream().collect(Collectors.groupingBy(row -> row.get("shot_id", Long.class)));
        var videos = db.fetch("select distinct on (shot_id) * from video where episode_id = ? order by shot_id, id desc", episodeId)
            .stream().collect(Collectors.toMap(row -> row.get("shot_id", Long.class), row -> row));
        var tasks = db.fetch("select distinct on (target_id) t.*,(select external_task_id from task_attempt where task_id=t.id order by attempt_no desc limit 1) as external_task_id from task t where episode_id = ? and type = 'VIDEO' order by target_id,(status in ('QUEUED','RUNNING')) desc,id desc", episodeId)
            .stream().collect(Collectors.toMap(row -> row.get("target_id", Long.class), row -> row));
        var attempts = db.fetch("select target_id, sum(attempt_count) as attempts from task where episode_id = ? and type = 'VIDEO' group by target_id", episodeId)
            .stream().collect(Collectors.toMap(row -> row.get("target_id", Long.class), row -> row.get("attempts", Integer.class)));
        var imageTasks = db.fetch("select distinct on (target_id) * from task where episode_id=? and type='IMAGE' and storyboard_variant_revision_id is null order by target_id,(status in ('QUEUED','RUNNING')) desc,id desc", episodeId)
            .stream().collect(Collectors.toMap(row -> row.get("target_id", Long.class), row -> row));
        var guidance = db.fetch("select id,input_snapshot from task where episode_id=? and type='IMAGE'", episodeId)
            .stream().collect(Collectors.toMap(row -> row.get("id", Long.class), row -> {
                try { return json.readValue(row.get("input_snapshot", String.class), ImageProvider.Input.class).guidance(); }
                catch (java.io.IOException error) { throw new IllegalStateException("图片任务快照无效",error); }
            }));
        return rows.stream().map(shot -> view(shot, images.getOrDefault(shot.id(), List.of()), videos.get(shot.id()), tasks.get(shot.id()), attempts.getOrDefault(shot.id(), 0), imageTasks.get(shot.id()), guidance))
            .filter(shot -> filter.equals("all") || filter.equals("draft") && shot.status().equals("draft") ||
                filter.equals("todo") && shot.images().isEmpty()).toList();
    }

    @Transactional(readOnly = true)
    public View get(long userId, long shotId) {
        var shot = find(userId, shotId);
        return list(userId, shot.episodeId(), "all").stream().filter(item -> item.id().equals(Long.toString(shotId))).findFirst().orElseThrow(ApiException::notFound);
    }

    @Transactional
    public View update(long userId, long id, String description, String dialogue, String sceneName, String size, String move,
                       Integer duration, Long expectedVersion) {
        scope.shot(userId, id);
        if (description != null) TextInput.validate(description, 16000, "画面描述");
        if (dialogue != null) TextInput.validate(dialogue, 16000, "台词");
        if (sceneName != null && !sceneName.isBlank()) TextInput.name(sceneName, "场景名称");
        if (size != null && !ShotDraftValidation.SIZES.contains(size) || move != null && !ShotDraftValidation.MOVES.contains(move) || duration != null && (duration < 1500 || duration > 12000))
            throw ApiException.badRequest("镜头参数无效");
        var shot = find(userId, id);
        if (expectedVersion != null && shot.version() != expectedVersion)
            throw ApiException.conflict("镜头已被修改，请刷新后再编辑");
        if (shot.update(description, dialogue, sceneName, size, move, duration)) {
            if (sceneName != null) {
                var binding = assets.bind(userId, shot.projectId(), id, shot.sceneName(), shot.characterNames());
                shot.bind(binding.sceneName(), binding.characterNames());
            }
            shots.flush(); touch(shot.episodeId(), userId); scope.touchProject(shot.projectId(), userId);
        }
        return get(userId, id);
    }

    @Transactional
    public View status(long userId, long id, String status) {
        scope.shot(userId, id);
        var shot = find(userId, id);
        if (shot.changeStatus(status.toUpperCase(java.util.Locale.ROOT))) {
            shots.flush(); touch(shot.episodeId(), userId); scope.touchProject(shot.projectId(), userId);
        }
        return get(userId, id);
    }

    @Transactional
    public List<View> confirmAll(long userId, long episodeId) {
        long projectId = scope.lockEpisode(userId, episodeId);
        boolean changed = false;
        for (var shot : shots.findByEpisodeIdAndUserIdOrderBySeq(episodeId, userId)) changed |= shot.changeStatus("CONFIRMED");
        if (changed) { shots.flush(); touch(episodeId, userId); scope.touchProject(projectId, userId); }
        return list(userId, episodeId, "all");
    }

    public boolean hasActiveImageVariants(long episodeId) {
        return db.fetchExists(db.selectOne().from("storyboard_variant").where("episode_id=? and not archived", episodeId));
    }

    @Transactional
    public List<Long> replace(long userId, long projectId, long episodeId, List<TextProvider.ShotDraft> drafts) {
        String directory = "u/" + userId + "/p/" + projectId + "/e/" + episodeId;
        for (var video : db.fetch("select storage_key from video where episode_id = ?", episodeId)) {
            String key = video.get("storage_key", String.class);
            if (key.startsWith(directory + "/video/")) cleanup.enqueue(directory+"/video/"+key.substring((directory+"/video/").length()).split("/")[0]);
        }
        for (var task : db.fetch("select id, input_signature from task where episode_id = ? and type = 'VIDEO'", episodeId)) {
            cleanup.enqueue(directory + "/video/" + task.get("id", Long.class) + "-" + task.get("input_signature", String.class) + ".mp4");
            cleanup.enqueue(directory + "/video/" + task.get("id", Long.class) + "-" + task.get("input_signature", String.class));
        }
        for (var task : db.fetch("select id from task where episode_id=? and type='IMAGE'", episodeId))
            cleanup.enqueue("u/" + userId + "/p/" + projectId + "/images/" + task.get("id", Long.class));
        for (var shot : db.fetch("select id from shot where episode_id = ?", episodeId)) cleanup.enqueue(directory + "/shot/" + shot.get("id", Long.class));
        db.execute("""
            update task_attempt set status = 'CANCELED', error_code = 'SHOTS_REPLACED', finished_at = now()
            where status = 'RUNNING' and task_id in (select id from task where episode_id = ? and type in ('VIDEO','IMAGE') and status in ('QUEUED','RUNNING'))
            """, episodeId);
        db.execute("""
            update task set status = 'CANCELED', error_code = 'SHOTS_REPLACED', error_message = '原镜头已被重新拆解替换',
                retryable = false, lease_owner = null, lease_until = null, version = version + 1, updated_at = now(), finished_at = now()
            where episode_id = ? and type in ('VIDEO','IMAGE') and status in ('QUEUED','RUNNING')
            """, episodeId);
        db.execute("delete from storyboard_variant where episode_id = ?", episodeId);
        db.execute("delete from shot where episode_id = ? and user_id = ?", episodeId, userId);
        var created = new ArrayList<Shot>();
        for (int index = 0; index < drafts.size(); index++) created.add(new Shot(userId, projectId, episodeId, index + 1, drafts.get(index)));
        shots.saveAllAndFlush(created);
        for (int index = 0; index < created.size(); index++) {
            var shot = created.get(index);
            var binding = assets.bind(userId, projectId, shot.id(), shot.sceneName(), shot.characterNames());
            shot.bind(binding.sceneName(), binding.characterNames());
        }
        shots.flush();
        return created.stream().map(Shot::id).toList();
    }

    private View view(Shot shot, List<Record> images, Record video, Record task, int attempts, Record imageTask, Map<Long,ImageProvider.Guidance> guidance) {
        var fields = shot.fields();
        var candidates = new ArrayList<Image>();
        String selected = null;
        for (Record row : images) {
            String id = row.get("id", String.class);
            if (row.get("selected", Boolean.class)) selected = id;
            JsonNode snapshot = null;
            try { if (row.get("input_snapshot") != null) snapshot = json.readTree(row.get("input_snapshot", String.class)); }
            catch (java.io.IOException error) { throw new IllegalStateException("图片来源快照无效", error); }
            String url = row.get("media_type") == null ? null : "/api/shot-images/" + id + "/image";
            candidates.add(new Image(id, row.get("label") == null ? "候选 " + (candidates.size() + 1) : row.get("label", String.class), url == null ? row.get("storage_key", String.class) : url,
                row.get("created_at", java.time.OffsetDateTime.class).toInstant().toEpochMilli(), snapshot, url, guidance.get(row.get("task_id", Long.class))));
        }
        String state = task == null ? "idle" : switch (task.get("status", String.class)) {
            case "QUEUED" -> "queued"; case "RUNNING" -> "running"; case "FAILED" -> "failed"; case "UNKNOWN" -> "unknown"; default -> "idle";
        };
        String pending=null;
        if(task!=null) try { pending=json.readTree(task.get("input_snapshot",String.class)).path("sourceImageId").asText(null); }
        catch(java.io.IOException error){throw new IllegalStateException("视频来源快照无效",error);}
        var projected = new Video(video != null, video == null ? null : video.get("source_image_id", String.class), state,
            attempts, task == null ? null : task.get("error_message", String.class), video == null ? null : "/api/tasks/" + video.get("task_id", Long.class) + "/video",
            task==null?null:task.get("id",String.class),task!=null && task.get("retryable",Boolean.class) && task.get("attempt_count",Integer.class)<worker.maxAttempts(),
            task!=null && state.equals("unknown") && task.get("external_task_id")!=null,video==null?null:video.get("duration_ms",Integer.class),pending);
        return new View(fields.id(), fields.projectId(), fields.episodeId(), fields.seq(), fields.sceneName(), fields.shotSize(), fields.cameraMove(),
            fields.description(), fields.dialogue(), fields.durationMs(), fields.status(), fields.characterNames(), List.copyOf(candidates), selected,
            imageTask == null ? "idle" : AssetService.state(imageTask.get("status", String.class)), projected,
            imageTask==null?null:imageTask.get("id",String.class),imageTask==null?null:imageTask.get("error_message",String.class),
            imageTask!=null && imageTask.get("retryable",Boolean.class) && imageTask.get("attempt_count",Integer.class)<worker.maxAttempts(), shot.version());
    }

    private void touch(long episodeId, long userId) {
        db.execute("update episode set shot_revision = shot_revision + 1, updated_at = now(), version = version + 1 where id = ? and user_id = ?", episodeId, userId);
    }
    private Shot find(long userId, long shotId) { return shots.findByIdAndUserId(shotId, userId).orElseThrow(ApiException::notFound); }
}
