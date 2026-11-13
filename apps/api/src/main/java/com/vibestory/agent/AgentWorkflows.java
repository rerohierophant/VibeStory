package com.vibestory.agent;

import com.vibestory.batch.GenerationBatch;
import com.vibestory.common.ApiException;
import com.vibestory.common.ScopeGuard;
import com.vibestory.task.TaskService;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Service;

@Service
class AgentWorkflows {
    enum State { WAITING_BREAKDOWN, WAITING_CONFIRMATION, GENERATING, PAUSED, COMPLETE, PARTIAL, BLOCKED }
    record Workflow(UUID id, String projectId, String episodeId, String taskId, State state, int shotCount, int unconfirmed,
                    List<GenerationBatch.View> batches, String message) {}
    private static final String SELECT = """
        select w.*, t.project_id, t.status as breakdown_status, t.error_message,
               array(select s.id::bigint from jsonb_array_elements_text(t.output_snapshot -> 'shotIds')
                     with ordinality as s(id, seq) order by s.seq) as shot_ids,
               (t.input_snapshot ->> 'storyRevision')::bigint as story_revision
        from agent_workflow w join task t on t.id = w.breakdown_task_id and t.episode_id = w.episode_id
        join agent_conversation c on c.id = w.conversation_id and c.user_id = t.user_id
        where c.user_id = ? and w.conversation_id = ?
        """;
    private final DSLContext db;
    private final ScopeGuard scope;
    private final EpisodeWorkflow workflow;
    private final GenerationBatch batches;
    private final TaskService tasks;

    AgentWorkflows(DSLContext db, ScopeGuard scope, EpisodeWorkflow workflow,
                   GenerationBatch batches, TaskService tasks) {
        this.db = db;
        this.scope = scope;
        this.workflow = workflow;
        this.batches = batches;
        this.tasks = tasks;
    }

    // Writes join the conversation transaction. Long work belongs to the existing tasks.
    void start(long user, UUID conversation, UUID requestKey, Long project, Long episode) {
        if (episode == null) throw ApiException.badRequest("请先打开要拆镜的一集");
        long actualProject = scope.lockEpisode(user, episode);
        if (project != null && actualProject != project) throw ApiException.notFound();
        var task = workflow.breakdown(user, episode, false);
        if (db.fetchExists(db.selectOne().from("agent_workflow")
                .where("conversation_id = ? and breakdown_task_id = ?", conversation, Long.parseLong(task.taskId()))))
            throw ApiException.conflict("这个会话已经有同一次拆镜流程，请查看之前的卡片");
        db.execute("""
            insert into agent_workflow(id, conversation_id, request_key, episode_id, breakdown_task_id)
            values (?, ?, ?, ?, ?)
            """, UUID.randomUUID(), conversation, requestKey, episode, Long.parseLong(task.taskId()));
    }

    Map<UUID, Workflow> views(long user, UUID conversation) {
        return db.fetch(SELECT, user, conversation).stream().collect(java.util.stream.Collectors.toMap(
            row -> row.get("request_key", UUID.class), row -> view(user, row)));
    }

    private Workflow view(long user, Record row) {
        List<GenerationBatch.View> output = Arrays.stream(row.get("batch_ids", Long[].class))
            .map(id -> batches.get(user, id)).toList();
        if (row.get("confirmed_at") != null) {
            if (output.stream().anyMatch(batch -> batch.state().equals("running")))
                return view(row, State.GENERATING, 0, output, "正在生成分镜图");
            if (output.stream().anyMatch(batch -> batch.state().equals("paused")))
                return view(row, State.PAUSED, 0, output, "出图任务已暂停，请在分镜批次中查看原因");
            boolean incomplete = output.stream().anyMatch(batch -> batch.progress().succeeded() < batch.progress().total());
            return view(row, incomplete ? State.PARTIAL : State.COMPLETE, 0, output,
                incomplete ? "出图已结算，请查看未成功的镜头" : "分镜图已就绪，可到分镜页审阅");
        }
        return switch (row.get("breakdown_status", String.class)) {
            case "QUEUED", "RUNNING" -> view(row, State.WAITING_BREAKDOWN, 0, List.of(), "正在拆镜");
            case "SUCCEEDED" -> {
                var current = workflow.matchingShots(user, row);
                if (current.isEmpty())
                    yield view(row, State.BLOCKED, 0, List.of(), "正文或镜头范围已变化，请检查本集后再发起流程");
                int draft = (int) current.stream().filter(shot -> shot.firstFrameId() != null && !shot.confirmed()).count();
                yield view(row, State.WAITING_CONFIRMATION, draft, output, "拆镜完成，可以为缺图的镜头生成分镜图");
            }
            default -> view(row, State.BLOCKED, 0, List.of(), "拆镜未完成。"
                + java.util.Objects.toString(row.get("error_message", String.class), "请到故事页查看任务状态"));
        };
    }

    void confirm(long user, UUID conversation, UUID id) {
        var row = require(user, conversation, id);
        long episode = row.get("episode_id", Long.class);
        scope.lockEpisode(user, episode);
        row = require(user, conversation, id);
        if (row.get("confirmed_at") != null) return;
        if (!row.get("breakdown_status", String.class).equals("SUCCEEDED"))
            throw ApiException.conflict("请等待拆镜成功后再提交出图");
        var current = workflow.matchingShots(user, row);
        if (current.isEmpty()) throw ApiException.conflict("正文或镜头范围已变化，请检查当前分集");
        var linked = row.get("batch_ids", Long[].class);
        var covered = new java.util.HashSet<>(workflow.coveredShots(linked));
        var missing = current.stream().filter(shot -> !shot.hasImage() && !covered.contains(shot.id()))
            .map(EpisodeWorkflow.Target::id).toList();
        var submitted = new ArrayList<>(Arrays.asList(linked));
        workflow.generateBatches(user, episode, GenerationBatch.Type.IMAGE, "agent-" + id, missing)
            .forEach(batch -> submitted.add(Long.parseLong(batch.id())));
        db.execute("update agent_workflow set confirmed_at = clock_timestamp(), batch_ids = ? where id = ?",
            submitted.toArray(Long[]::new), id);
    }

    String continueRun(long user, UUID conversation, Long project, Long episode) {
        var rows = db.fetch(SELECT + " order by t.id desc, w.id", user, conversation).stream()
            .filter(row -> matchesScope(row, project, episode)).toList();
        if (rows.isEmpty()) return null;
        if (episode == null && rows.stream().map(row -> row.get("episode_id", Long.class)).distinct().count() > 1)
            throw ApiException.conflict("本会话包含多个分集，请先打开要继续的分集");
        var row = rows.getFirst();
        long targetEpisode = row.get("episode_id", Long.class);
        scope.lockEpisode(user, targetEpisode);
        UUID id = row.get("id", UUID.class);
        row = require(user, conversation, id);
        var current = view(user, row);
        if (current.state() == State.WAITING_CONFIRMATION && !current.batches().isEmpty()) {
            confirm(user, conversation, id);
            current = view(user, require(user, conversation, id));
        }
        return switch (current.state()) {
            case COMPLETE -> "分镜图已就绪。" + workflow.plan(user, targetEpisode).next().message();
            default -> current.message();
        };
    }

    Integer retryFailed(long user, UUID conversation, Long project, Long episode) {
        var rows = db.fetch(SELECT + " and w.confirmed_at is not null order by w.confirmed_at desc, w.id",
            user, conversation).stream().filter(row -> matchesScope(row, project, episode)).toList();
        if (rows.isEmpty()) return null;
        if (episode == null && rows.stream().map(row -> row.get("episode_id", Long.class)).distinct().count() > 1)
            throw ApiException.conflict("本会话包含多个分集，请先打开要重试的分集");
        var row = rows.getFirst();
        scope.lockEpisode(user, row.get("episode_id", Long.class));
        var output = view(user, require(user, conversation, row.get("id", UUID.class)));
        int queued = 0;
        for (var batch : output.batches()) {
            for (var member : batch.members()) {
                if (!member.state().equals("failed") || member.taskId() == null) continue;
                long taskId = Long.parseLong(member.taskId());
                if (tasks.get(user, taskId).retryable()) {
                    tasks.retry(user, taskId, false);
                    queued++;
                }
            }
        }
        return queued;
    }

    private boolean matchesScope(Record row, Long project, Long episode) {
        return (project == null || project.equals(row.get("project_id", Long.class)))
            && (episode == null || episode.equals(row.get("episode_id", Long.class)));
    }

    private Record require(long user, UUID conversation, UUID id) {
        var row = db.fetchOne(SELECT + " and w.id = ?", user, conversation, id);
        if (row == null) throw ApiException.notFound();
        return row;
    }

    private Workflow view(Record row, State state, int unconfirmed, List<GenerationBatch.View> output, String message) {
        return new Workflow(row.get("id", UUID.class), row.get("project_id", String.class), row.get("episode_id", String.class),
            row.get("breakdown_task_id", String.class), state, row.get("shot_ids", Long[].class).length,
            unconfirmed, output, message);
    }
}
