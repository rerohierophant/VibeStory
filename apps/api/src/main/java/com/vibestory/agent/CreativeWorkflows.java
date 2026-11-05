package com.vibestory.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vibestory.admission.AdmissionProperties;
import com.vibestory.agent.CreativeSkill.EpisodeDraft;
import com.vibestory.agent.CreativeSkill.Role;
import com.vibestory.agent.CreativeSkill.ScriptDraft;
import com.vibestory.common.ApiException;
import com.vibestory.common.ScopeGuard;
import com.vibestory.common.TextInput;
import com.vibestory.episode.EpisodeService;
import com.vibestory.provider.TextProvider;
import com.vibestory.shot.ShotService;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CreativeWorkflows {
    public enum CreativeState { QUEUED, RUNNING, READY, APPLIED, FAILED, UNKNOWN }
    public record CreativeSource(String id, String name, int characters) {}
    public record CreativeTurn(UUID id, UUID sessionId, Role role, CreativeState state, String summary, String error,
                               UUID scriptId, UUID previousId, boolean current, List<String> appliedIds) {}
    public record CreativeProject(CreativeSource source, CreativeTurn plan) {}
    public record CreativeEpisode(UUID mainSessionId, UUID scriptSessionId, UUID storyboardSessionId,
                                  CreativeTurn script, CreativeTurn storyboard) {}
    public record EpisodePreview(String title, String summary, int start, int end, String opening, String closing) {}
    public record CreativePlanPreview(UUID id, List<EpisodePreview> episodes) {}
    public record CreativeStoryboard(UUID id, List<TextProvider.ShotDraft> shots) {}

    private static final int EPISODE_LIMIT = 32_000;
    private final DSLContext db;
    private final ScopeGuard scope;
    private final ObjectMapper json;
    private final CreativeModel model;
    private final AdmissionProperties admission;
    private final EpisodeService episodes;
    private final ShotService shots;

    CreativeWorkflows(DSLContext db, ScopeGuard scope, ObjectMapper json, CreativeModel model, AdmissionProperties admission,
                      EpisodeService episodes, ShotService shots) {
        this.db = db;
        this.scope = scope;
        this.json = json;
        this.model = model;
        this.admission = admission;
        this.episodes = episodes;
        this.shots = shots;
    }

    @Transactional
    public CreativeSource upload(long user, long project, UUID key, String name, String body) {
        lockProject(user, project);
        name = TextInput.name(name, "来源文件名");
        TextInput.validate(body, 1024 * 1024, "项目原文");
        if (body.strip().length() < 20) throw ApiException.badRequest("项目原文至少需要 20 字符");
        var previous = db.fetchOne("select * from creative_source where project_id=? and request_key=?", project, key);
        if (previous != null) {
            if (!name.equals(previous.get("name", String.class)) || !body.equals(previous.get("body", String.class)))
                throw ApiException.conflict("同一上传标识不能用于不同原文");
            return source(previous);
        }
        return source(db.fetchOne("insert into creative_source(project_id,request_key,name,body) values (?,?,?,?) returning *",
            project, key, name, body));
    }

    @Transactional(readOnly = true)
    public CreativeProject project(long user, long project) {
        scope.personalProject(user, project);
        var source = db.fetchOne("select id,name,char_length(body) as characters from creative_source where project_id=? order by id desc limit 1", project);
        var plan = latest(sessionId(project, null, Role.PLAN));
        return new CreativeProject(source == null ? null : new CreativeSource(source.get("id", String.class), source.get("name", String.class), source.get("characters", Integer.class)),
            plan == null ? null : turn(plan, Role.PLAN, source != null && sameLong(plan, "source_id", source, "id")));
    }

    @Transactional
    public CreativeTurn plan(long user, long project, UUID key, String instruction) {
        lockProject(user, project);
        var session = ensureSession(project, null, Role.PLAN, null);
        var old = replay(session, key, instruction, null);
        if (old != null) return turn(old, Role.PLAN, sameSource(project, old));
        var source = latestSource(project);
        if (source == null) throw ApiException.conflict("请先在项目概览上传整部原文");
        if (db.fetchExists(db.selectOne().from("creative_turn").where("session_id=? and source_id=? and state='APPLIED'", session, source.get("id"))))
            throw ApiException.conflict("该来源已创建分集");
        return turn(enqueue(session, key, instruction, source.get("id", Long.class), null, null, null, null, null), Role.PLAN, true);
    }

    @Transactional(readOnly = true)
    public CreativePlanPreview planPreview(long user, long project, UUID id) {
        scope.personalProject(user, project);
        var row = requireTurn(project, null, Role.PLAN, id);
        requireResult(row);
        var body = db.fetchOne("select body from creative_source where id=? and project_id=?", row.get("source_id"), project).get(0, String.class);
        return new CreativePlanPreview(id, drafts(row).stream().map(draft -> {
            String part = body.substring(draft.start(), draft.end()).strip();
            int size = part.codePointCount(0, part.length());
            return new EpisodePreview(draft.title(), draft.summary(), draft.start(), draft.end(), CreativeSkill.clip(part, 100),
                part.substring(part.offsetByCodePoints(0, Math.max(0, size - 100))));
        }).toList());
    }

    @Transactional
    public CreativeTurn acceptPlan(long user, long project, UUID id) {
        lockProject(user, project);
        var row = requireTurn(project, null, Role.PLAN, id);
        if (state(row) == CreativeState.APPLIED) return turn(row, Role.PLAN, sameSource(project, row));
        requireResult(row);
        var source = latestSource(project);
        if (source == null || !sameLong(row, "source_id", source, "id") || !latestId(row))
            throw ApiException.conflict("来源或分集草案已更新，请审阅最新版本");
        long sourceId = source.get("id", Long.class);
        String body = source.get("body", String.class);
        var ids = new ArrayList<Long>();
        for (var draft : drafts(row)) {
            var created = episodes.create(user, project, draft.title());
            long episode = Long.parseLong(created.id());
            episodes.update(user, episode, null, draft.summary(), null);
            episodes.story(user, episode, body.substring(draft.start(), draft.end()));
            db.execute("insert into creative_episode_source values (?,?,?,?,?)", episode, sourceId, id, draft.start(), draft.end());
            ids.add(episode);
        }
        return turn(applied(id, ids), Role.PLAN, true);
    }

    @Transactional
    public CreativeEpisode episode(long user, long episode) {
        var row = lockEpisode(user, episode, null);
        long project = row.get("project_id", Long.class);
        UUID main = mainSession(user, project, episode);
        UUID script = ensureSession(project, episode, Role.SCRIPT, main);
        UUID board = ensureSession(project, episode, Role.STORYBOARD, main);
        return episodeView(row, main, script, board);
    }

    // Used by the main Agent. This projection contains summaries and references only.
    @Transactional(readOnly = true)
    public CreativeEpisode episodeSummary(long user, long episode, Long requestedProject) {
        scope.personalEpisode(user, episode);
        var row = db.fetchOne("select id,project_id,story_revision,shot_revision from episode where id=? and user_id=?", episode, user);
        if (row == null || requestedProject != null && !requestedProject.equals(row.get("project_id", Long.class)))
            throw ApiException.notFound();
        long project = row.get("project_id", Long.class);
        return episodeView(row, sessionId(project, episode, Role.MAIN), sessionId(project, episode, Role.SCRIPT),
            sessionId(project, episode, Role.STORYBOARD));
    }

    @Transactional
    public CreativeTurn script(long user, long episode, Long requestedProject, UUID key, String instruction) {
        var row = lockEpisode(user, episode, requestedProject);
        long project = row.get("project_id", Long.class);
        UUID session = childSession(user, project, episode, Role.SCRIPT);
        var old = replay(session, key, instruction, null);
        if (old != null) return turn(old, Role.SCRIPT, sameLong(old, "story_revision", row, "story_revision"));
        var body = row.get("story_content", String.class);
        if (body.strip().length() < 20 || body.length() > EPISODE_LIMIT)
            throw ApiException.badRequest("本集正文须为 20 至 32000 字符，长稿请先在项目概览分集");
        var previous = latestReady(session);
        UUID previousId = previous != null && sameLong(previous, "story_revision", row, "story_revision")
            ? previous.get("id", UUID.class) : null;
        return turn(enqueue(session, key, instruction, null, null, previousId, row.get("story_revision", Long.class), null, body),
            Role.SCRIPT, true);
    }

    @Transactional
    public CreativeTurn storyboard(long user, long episode, Long requestedProject, UUID key, UUID scriptId, String instruction) {
        var row = lockEpisode(user, episode, requestedProject);
        long project = row.get("project_id", Long.class);
        UUID session = childSession(user, project, episode, Role.STORYBOARD);
        var old = replay(session, key, instruction, scriptId);
        if (old != null) return turn(old, Role.STORYBOARD, boardCurrent(row, old, currentScript(row)));
        var script = currentScript(row);
        if (script == null || scriptId != null && !scriptId.equals(script.get("id", UUID.class)))
            throw ApiException.conflict("请先整理并审阅当前版本的剧本");
        scriptId = script.get("id", UUID.class);
        var previous = latestReady(session);
        UUID previousId = previous != null && scriptId.equals(previous.get("script_id", UUID.class)) ? previous.get("id", UUID.class) : null;
        return turn(enqueue(session, key, instruction, null, scriptId, previousId,
            row.get("story_revision", Long.class), row.get("shot_revision", Long.class), null), Role.STORYBOARD, true);
    }

    @Transactional(readOnly = true)
    public ScriptDraft scriptPreview(long user, long episode, UUID id) {
        var row = episodeRow(user, episode, null);
        var script = requireTurn(row.get("project_id", Long.class), episode, Role.SCRIPT, id);
        requireResult(script);
        return decode(script.get("result", String.class), ScriptDraft.class);
    }

    @Transactional(readOnly = true)
    public CreativeStoryboard storyboardPreview(long user, long episode, UUID id) {
        var row = episodeRow(user, episode, null);
        var board = requireTurn(row.get("project_id", Long.class), episode, Role.STORYBOARD, id);
        requireResult(board);
        return new CreativeStoryboard(id, storyboardShots(board));
    }

    @Transactional
    public CreativeTurn applyStoryboard(long user, long episode, Long requestedProject, UUID id) {
        var row = lockEpisode(user, episode, requestedProject);
        long project = row.get("project_id", Long.class);
        var board = requireTurn(project, episode, Role.STORYBOARD, id);
        if (state(board) == CreativeState.APPLIED) return turn(board, Role.STORYBOARD, boardCurrent(row, board, currentScript(row)));
        requireResult(board);
        if (!boardCurrent(row, board, currentScript(row)) || !latestId(board))
            throw ApiException.conflict("正文、镜头或脚本版本已变化，请重新规划分镜");
        if (row.get("breakdown_task_id") != null || db.fetchExists(db.selectOne().from("shot").where("episode_id=?", episode)))
            throw ApiException.conflict("本集已有拆镜任务或镜头，请使用故事页的覆盖重拆入口");
        var ids = shots.replace(user, project, episode, storyboardShots(board));
        db.execute("""
            update episode set breakdown_state='DONE', shot_revision=shot_revision+1, version=version+1,
                updated_at=clock_timestamp() where id=?
            """, episode);
        scope.touchProject(project, user);
        return turn(applied(id, ids), Role.STORYBOARD, true);
    }

    // Only the child tool set calls these methods. Identity comes from the run, never model arguments.
    Object childInput(long user, UUID id) {
        var turn = childTurn(user, id);
        if (turn.get("body") != null) return java.util.Map.of("source", turn.get("body", String.class),
            "storyRevision", turn.get("story_revision", Long.class));
        var script = db.fetchOne("select result from creative_turn where id=? and state='READY'", turn.get("script_id"));
        if (script == null) throw ApiException.conflict("绑定的剧本不可读取");
        long project = turn.get("project_id", Long.class);
        return java.util.Map.of("script", decode(script.get(0, String.class), ScriptDraft.class).script(),
            "scriptId", turn.get("script_id", UUID.class), "episodeId", turn.get("episode_id", Long.class),
            "characters", db.fetch("select name from character_asset where project_id=? order by id limit 30", project).getValues("name", String.class),
            "scenes", db.fetch("select name from scene_asset where project_id=? order by id limit 30", project).getValues("name", String.class));
    }

    Object childPrevious(long user, UUID id) {
        var turn = childTurn(user, id);
        if (turn.get("previous_id") == null) return java.util.Map.of();
        var prior = db.fetchOne("select result from creative_turn where id=? and session_id=?", turn.get("previous_id"), turn.get("session_id"));
        return prior == null ? java.util.Map.of() : decode(prior.get(0, String.class), Object.class);
    }

    CreativeTurn publishDraft(long user, UUID id, UUID lease, com.fasterxml.jackson.databind.JsonNode draft) {
        var turn = childTurn(user, id);
        Object result;
        String summary;
        Role role = Role.valueOf(turn.get("role", String.class));
        if (role == Role.SCRIPT) {
            if (!draft.path("script").isTextual() || !draft.path("summary").isTextual())
                throw ApiException.badRequest("script 和 summary 必须是字符串");
            var script = strictDraft(draft, ScriptDraft.class);
            TextInput.validate(script.script(), 128_000, "脚本");
            TextInput.validate(script.summary(), 800, "脚本摘要");
            if (script.script().strip().length() < 20 || script.script().length() > 32_000)
                throw ApiException.badRequest("script 须为 20 至 32000 字符，请修改后再次提交");
            result = script;
            summary = CreativeSkill.clip(script.summary(), 150);
        } else {
            if (!draft.isObject() || draft.size() != 1 || !draft.path("shots").isArray())
                throw ApiException.badRequest("draft 只包含 shots 镜头数组");
            for (int i = 0; i < draft.path("shots").size(); i++) {
                var shot = draft.path("shots").get(i);
                for (String field : List.of("description","dialogue","sceneName","shotSize","cameraMove"))
                    if (!shot.path(field).isTextual()) throw ApiException.badRequest("shots[" + i + "]." + field + " 必须是字符串");
                if (!shot.path("durationMs").isIntegralNumber() || !shot.path("characterNames").isArray())
                    throw ApiException.badRequest("shots[" + i + "] 的 durationMs 必须是整数，characterNames 必须是字符串数组");
                for (var name : shot.path("characterNames")) if (!name.isTextual())
                    throw ApiException.badRequest("shots[" + i + "].characterNames 只能包含字符串");
            }
            var board = java.util.Arrays.asList(strictDraft(draft.path("shots"), TextProvider.ShotDraft[].class));
            new com.vibestory.provider.ShotDraftValidation().validate(board);
            result = board;
            summary = "分镜草案包含 " + board.size() + " 镜，待审阅应用";
        }
        // Draft creation preserves the frozen input; applying it still checks the current revisions.
        var saved = db.fetchOne("""
            update creative_turn set result=?::jsonb,summary=?,state='READY',lease_id=null,lease_until=null
            where id=? and state='RUNNING' and lease_id=? and lease_until >= clock_timestamp() returning *
            """, encode(result), summary, id, lease);
        if (saved == null) throw ApiException.conflict("子任务执行租约已变化");
        return turn(saved, role, true);
    }

    private Record childTurn(long user, UUID id) {
        var row = db.fetchOne("""
            select t.*,s.project_id,s.episode_id,s.role from creative_turn t
            join creative_session s on s.id=t.session_id join project p on p.id=s.project_id
            where t.id=? and p.user_id=? and s.role in ('SCRIPT','STORYBOARD')
            """, id, user);
        if (row == null) throw ApiException.notFound();
        scope.personalEpisode(user, row.get("episode_id", Long.class));
        return row;
    }

    private <T> T strictDraft(com.fasterxml.jackson.databind.JsonNode value, Class<T> type) {
        try {
            return json.readerFor(type).with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,
                com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES)
                .without(com.fasterxml.jackson.databind.DeserializationFeature.ACCEPT_FLOAT_AS_INT).readValue(value);
        } catch (java.io.IOException invalid) { throw ApiException.badRequest("草稿字段不完整或包含未知字段，请按 Skill 的 JSON 结构修复"); }
    }

    private CreativeEpisode episodeView(Record episode, UUID main, UUID scriptSession, UUID boardSession) {
        var script = latest(scriptSession);
        var board = latest(boardSession);
        return new CreativeEpisode(main, scriptSession, boardSession,
            script == null ? null : turn(script, Role.SCRIPT, sameLong(script, "story_revision", episode, "story_revision")),
            board == null ? null : turn(board, Role.STORYBOARD, boardCurrent(episode, board, currentScript(episode))));
    }

    private Record currentScript(Record episode) {
        var script = latestReady(sessionId(episode.get("project_id", Long.class), episode.get("id", Long.class), Role.SCRIPT));
        return script != null && sameLong(script, "story_revision", episode, "story_revision") ? script : null;
    }

    private boolean boardCurrent(Record episode, Record board, Record script) {
        return script != null && script.get("id", UUID.class).equals(board.get("script_id", UUID.class))
            && sameLong(board, "story_revision", episode, "story_revision")
            && episode.get("shot_revision", Long.class) == board.get("shot_revision", Long.class) + (state(board) == CreativeState.APPLIED ? 1 : 0);
    }

    private boolean latestId(Record turn) {
        return turn.get("id", UUID.class).equals(latest(turn.get("session_id", UUID.class)).get("id", UUID.class));
    }

    private boolean sameSource(long project, Record turn) {
        var source = latestSource(project);
        return source != null && sameLong(turn, "source_id", source, "id");
    }

    private static boolean sameLong(Record a, String af, Record b, String bf) {
        return Objects.equals(a.get(af, Long.class), b.get(bf, Long.class));
    }

    private void lockProject(long user, long project) {
        scope.personalProject(user, project);
        scope.lockProject(user, project);
    }

    private Record lockEpisode(long user, long episode, Long project) {
        scope.personalEpisode(user, episode);
        scope.lockEpisode(user, episode);
        return episodeRow(user, episode, project);
    }

    private Record episodeRow(long user, long episode, Long project) {
        scope.personalEpisode(user, episode);
        var row = db.fetchOne("select * from episode where id=? and user_id=?", episode, user);
        if (row == null || project != null && !project.equals(row.get("project_id", Long.class))) throw ApiException.notFound();
        return row;
    }

    private UUID childSession(long user, long project, long episode, Role role) {
        return ensureSession(project, episode, role, mainSession(user, project, episode));
    }

    private UUID mainSession(long user, long project, long episode) {
        UUID main = ensureSession(project, episode, Role.MAIN, null);
        db.execute("""
            insert into agent_conversation(id,user_id,title,creative_episode_id)
            select ?,user_id,title || ' · 主会话',id from episode where id=? and user_id=?
            on conflict (id) do nothing
            """, main, episode, user);
        return main;
    }

    void requireConversationScope(UUID conversation, Long episode) {
        var row = db.fetchOne("select episode_id from creative_session where id=? and role='MAIN'", conversation);
        if (row != null && !Objects.equals(episode, row.get("episode_id", Long.class))) throw ApiException.notFound();
    }

    private UUID ensureSession(long project, Long episode, Role role, UUID parent) {
        db.execute("""
            insert into creative_session(id,project_id,episode_id,parent_id,role) values (?,?,?,?,?)
            on conflict (project_id,episode_id,role) do nothing
            """, UUID.randomUUID(), project, episode, parent, role.name());
        return sessionId(project, episode, role);
    }

    private UUID sessionId(long project, Long episode, Role role) {
        var row = db.fetchOne("select id from creative_session where project_id=? and episode_id is not distinct from cast(? as bigint) and role=?",
            project, episode, role.name());
        return row == null ? null : row.get("id", UUID.class);
    }

    private Record requireTurn(long project, Long episode, Role role, UUID id) {
        var row = db.fetchOne("select * from creative_turn where session_id=? and id=?", sessionId(project, episode, role), id);
        if (row == null) throw ApiException.notFound();
        return row;
    }

    private static final String TURN_SUMMARY = """
        select id,session_id,source_id,script_id,previous_id,story_revision,shot_revision,state,summary,error,applied_ids
        from creative_turn where session_id=?
        """;

    private Record latest(UUID session) {
        return session == null ? null : db.fetchOne(TURN_SUMMARY + " order by seq desc limit 1", session);
    }

    private Record latestReady(UUID session) {
        return session == null ? null : db.fetchOne(TURN_SUMMARY + " and state in ('READY','APPLIED') order by seq desc limit 1", session);
    }

    private Record latestSource(long project) {
        return db.fetchOne("select * from creative_source where project_id=? order by id desc limit 1", project);
    }

    private Record replay(UUID session, UUID key, String instruction, UUID scriptId) {
        TextInput.validate(instruction, 2000, "创作要求");
        var row = db.fetchOne("select * from creative_turn where session_id=? and request_key=?", session, key);
        if (row != null && (!instruction.equals(row.get("instruction", String.class)) ||
                scriptId != null && !scriptId.equals(row.get("script_id", UUID.class))))
            throw ApiException.conflict("同一请求标识不能用于不同指令或输入");
        return row;
    }

    private Record enqueue(UUID session, UUID key, String instruction, Long source, UUID script, UUID previous,
                           Long storyRevision, Long shotRevision, String body) {
        if (admission.enabled()) throw ApiException.conflict("共享文本准入开启时暂不能发起创作子会话");
        if (db.fetchExists(db.selectOne().from("creative_turn").where("session_id=? and state in ('QUEUED','RUNNING')", session)))
            throw ApiException.conflict("该子会话已有进行中的轮次");
        var config = model.configuration();
        return db.fetchOne("""
            insert into creative_turn(id,session_id,request_key,source_id,script_id,previous_id,story_revision,
                shot_revision,body,instruction,provider,endpoint,model,state)
            values (?,?,?,?,?,?,?,?,?,?,?,?,?,'QUEUED') returning *
            """, UUID.randomUUID(), session, key, source, script, previous, storyRevision, shotRevision, body, instruction,
            config.provider(), config.endpoint(), config.model());
    }

    private Record applied(UUID id, List<Long> ids) {
        return db.fetchOne("update creative_turn set state='APPLIED', applied_ids=cast(? as bigint[]) where id=? returning *",
            ids.toArray(Long[]::new), id);
    }

    private void requireResult(Record turn) {
        if (state(turn) != CreativeState.READY && state(turn) != CreativeState.APPLIED)
            throw ApiException.conflict("产物仍未完成，请查看创作进度");
    }

    private static CreativeState state(Record row) { return CreativeState.valueOf(row.get("state", String.class)); }

    private CreativeSource source(Record row) {
        String body = row.get("body", String.class);
        return new CreativeSource(row.get("id", String.class), row.get("name", String.class), body.codePointCount(0, body.length()));
    }

    private CreativeTurn turn(Record row, Role role, boolean current) {
        var ids = row.get("applied_ids", Long[].class);
        return new CreativeTurn(row.get("id", UUID.class), row.get("session_id", UUID.class), role, state(row),
            row.get("summary", String.class), row.get("error", String.class), row.get("script_id", UUID.class),
            row.get("previous_id", UUID.class), current,
            ids == null ? List.of() : Arrays.stream(ids).map(String::valueOf).toList());
    }

    List<EpisodeDraft> drafts(Record row) {
        String result = row.get("result", String.class);
        return result == null ? List.of() : Arrays.asList(decode(result, EpisodeDraft[].class));
    }

    List<TextProvider.ShotDraft> storyboardShots(Record row) {
        return Arrays.asList(decode(row.get("result", String.class), TextProvider.ShotDraft[].class));
    }

    <T> T decode(String value, Class<T> type) {
        try { return json.readValue(value, type); }
        catch (JsonProcessingException error) { throw new IllegalStateException("创作产物无法读取", error); }
    }

    String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException error) { throw new IllegalStateException("创作产物无法保存", error); }
    }
}
