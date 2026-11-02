package com.vibestory.breakdown;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vibestory.common.ApiException;
import com.vibestory.common.ScopeGuard;
import com.vibestory.provider.TextProperties;
import com.vibestory.provider.TextProvider;
import com.vibestory.provider.ShotDraftValidation;
import com.vibestory.shot.ShotService;
import com.vibestory.task.InputSignature;
import com.vibestory.task.TaskService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BreakdownService {
    private final DSLContext db;
    private final ScopeGuard scope;
    private final TextProperties properties;
    private final InputSignature signatures;
    private final ObjectMapper json;
    private final ShotService shots;
    private final ShotDraftValidation validation;

    public BreakdownService(DSLContext db, ScopeGuard scope, TextProperties properties, InputSignature signatures, ObjectMapper json, ShotService shots, ShotDraftValidation validation) {
        this.db = db; this.scope = scope; this.properties = properties; this.signatures = signatures; this.json = json; this.shots = shots; this.validation = validation;
    }

    @Transactional
    public TaskService.Submission submit(long userId, long episodeId, boolean replaceExisting) {
        long projectId = scope.lockEpisode(userId, episodeId);
        var episode = db.fetchOne("select * from episode where id = ?", episodeId);
        String story = episode.get("story_content", String.class);
        if (story.strip().length() < 20) throw ApiException.badRequest("正文至少需要 20 字符");
        var active = db.fetchOne("select * from task where episode_id = ? and type = 'BREAKDOWN' and status in ('QUEUED','RUNNING')", episodeId);
        if (active != null) {
            var input = input(active.get("input_snapshot", String.class));
            if (sameSource(episode, input) && input.provider().equals(properties.provider()) && input.model().equals(model()) &&
                    input.endpoint().equals(endpoint())) return new TaskService.Submission(active.get("id", String.class));
        }
        var unknown = db.fetchOne("select input_snapshot from task where episode_id = ? and type = 'BREAKDOWN' and status = 'UNKNOWN' order by id desc limit 1", episodeId);
        if (unknown != null && sameSource(episode, input(unknown.get("input_snapshot", String.class))))
            throw ApiException.conflict("本集存在外部结果未知的拆解任务，请查看最新任务并明确确认重试");
        if (!replaceExisting && db.fetchOne("select count(*) from shot where episode_id = ?", episodeId).get(0, Integer.class) > 0)
            throw ApiException.conflict("本集已有镜头，请确认覆盖镜头、手动修改、确认状态与产物后再提交 replaceExisting=true");
        if (active != null) cancel(active.get("id", Long.class), "BREAKDOWN_SUPERSEDED", "拆解已被新的提交取代");
        var scenes = db.fetch("select name from scene_asset where user_id = ? and project_id = ? order by id", userId, projectId)
            .stream().map(row -> new TextProvider.SceneOption(row.get("name", String.class), null)).toList();
        var input = new TextProvider.Input("BREAKDOWN", episodeId, properties.provider(), endpoint(), model(), "breakdown-v1",
            episode.get("story_revision", Long.class), episode.get("shot_revision", Long.class), story, scenes, properties.maxTokens(), properties.disableThinking());
        var snapshot = signatures.of(input);
        var task = db.fetchOne("""
            insert into task(user_id, project_id, episode_id, type, target_type, target_id, provider, input_signature, input_snapshot)
            values (?, ?, ?, 'BREAKDOWN', 'EPISODE', ?, ?, ?, ?::jsonb) returning id
            """, userId, projectId, episodeId, episodeId, input.provider(), snapshot.signature(), snapshot.json());
        long taskId = task.get("id", Long.class);
        db.execute("update episode set breakdown_state = 'RUNNING', breakdown_task_id = ?, updated_at = now(), version = version + 1 where id = ?", taskId, episodeId);
        scope.touchProject(projectId, userId);
        return new TaskService.Submission(Long.toString(taskId));
    }

    @Transactional(readOnly = true)
    public Optional<Long> latest(long userId, long episodeId) {
        scope.episode(userId, episodeId);
        var task = db.fetchOne("select id from task where episode_id = ? and user_id = ? and type = 'BREAKDOWN' order by id desc limit 1", episodeId, userId);
        return task == null ? Optional.empty() : Optional.of(task.get("id", Long.class));
    }

    public TextProvider.Input input(String snapshot) {
        try { return json.readValue(snapshot, TextProvider.Input.class); }
        catch (JsonProcessingException error) { throw new IllegalStateException("拆解输入快照无效", error); }
    }

    public record Outcome(boolean applied, String code, String message) {}

    @Transactional
    public Outcome install(long userId, long projectId, long episodeId, long taskId, TextProvider.Input input, TextProvider.Result result) {
        validation.validate(result.shots());
        // TaskStore already holds the parent project and winning task row for this transaction.
        var episode = db.fetchOne("select * from episode where id = ? and user_id = ? for update", episodeId, userId);
        if (episode == null || !Long.valueOf(taskId).equals(episode.get("breakdown_task_id", Long.class)))
            return new Outcome(false, "BREAKDOWN_SUPERSEDED", "本次拆解已被取代");
        if (!sameSource(episode, input)) {
            db.execute("update episode set breakdown_state = 'IDLE', breakdown_task_id = null, updated_at = now(), version = version + 1 where id = ?", episodeId);
            return new Outcome(false, "BREAKDOWN_SOURCE_CHANGED", "正文或镜头在拆解期间发生修改，请确认后重新拆解");
        }
        var ids = shots.replace(userId, projectId, episodeId, result.shots());
        db.execute("""
            update episode set breakdown_state = 'DONE', breakdown_task_id = null, shot_revision = shot_revision + 1,
                updated_at = now(), version = version + 1 where id = ?
            """, episodeId);
        try {
            db.execute("update task set output_snapshot = ?::jsonb where id = ?",
                json.writeValueAsString(Map.of("shotIds", ids, "shots", result.shots(), "storyRevision", input.storyRevision())), taskId);
        } catch (JsonProcessingException error) { throw new IllegalStateException(error); }
        scope.touchProject(projectId, userId);
        return new Outcome(true, null, null);
    }

    @Transactional
    public void prepareRetry(long userId, Record task) {
        long episodeId = task.get("episode_id", Long.class);
        scope.lockEpisode(userId, episodeId);
        var episode = db.fetchOne("select * from episode where id = ?", episodeId);
        var input = input(task.get("input_snapshot", String.class));
        if (!sameSource(episode, input)) throw ApiException.conflict("原任务的正文或镜头输入已变化，请重新确认并提交拆解");
        if (!db.fetch("select id from task where episode_id = ? and type = 'BREAKDOWN' and status in ('QUEUED','RUNNING') and id <> ?",
                episodeId, task.get("id", Long.class)).isEmpty()) throw ApiException.conflict("本集已有进行中的拆解任务");
        db.execute("update episode set breakdown_state = 'RUNNING', breakdown_task_id = ?, updated_at = now(), version = version + 1 where id = ?",
            task.get("id", Long.class), episodeId);
    }

    @Transactional
    public void failed(long taskId, long episodeId) {
        db.execute("update episode set breakdown_state = 'IDLE', breakdown_task_id = null, updated_at = now(), version = version + 1 where id = ? and breakdown_task_id = ?", episodeId, taskId);
    }

    private boolean sameSource(Record episode, TextProvider.Input input) {
        return episode.get("story_revision", Long.class) == input.storyRevision() && episode.get("shot_revision", Long.class) == input.shotRevision();
    }
    private String model() { return properties.provider().equals("mock") ? "mock-splitter-v1" : properties.model(); }
    private String endpoint() { return properties.baseUrl().replaceAll("/+$", ""); }
    private void cancel(long taskId, String code, String message) {
        db.execute("update task_attempt set status = 'CANCELED', error_code = ?, finished_at = now() where task_id = ? and status = 'RUNNING'", code, taskId);
        db.execute("""
            update task set status = 'CANCELED', lease_owner = null, lease_until = null, version = version + 1,
                error_code = ?, error_message = ?, retryable = false, finished_at = now(), updated_at = now() where id = ?
            """, code, message, taskId);
    }
}
