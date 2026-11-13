package com.vibestory.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vibestory.agent.AgentConversationController.ChatInput;
import com.vibestory.agent.WorkbenchAgent.AgentResult;
import com.vibestory.common.ApiException;
import com.vibestory.common.ScopeGuard;
import com.vibestory.common.TextInput;
import com.vibestory.shot.ShotEditProposals.State;
import com.vibestory.style.ReferenceImage;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

@Service
public class AgentConversations {
    public record Conversation(UUID id, String title, OffsetDateTime updatedAt) {}
    public record ConversationHistoryItem(UUID id, String title, OffsetDateTime updatedAt,
                                          String projectId, String episodeId, Integer episodeIndex) {}
    public record Attachment(String id, String name, String url) {}
    public record ChatMessage(String id, String instruction, String projectId, String episodeId, List<Attachment> images, AgentResult result,
                              State proposalState, String imageTaskId, AgentWorkflows.Workflow workflow,
                              String suggestedInstruction, String error, AgentRuntime.Execution execution) {}
    public record Transcript(Conversation conversation, List<ChatMessage> messages) {}
    public record Image(String mediaType, byte[] bytes) {}
    private record Upload(String name, ReferenceImage.Encoded image) {}

    private final DSLContext db;
    private final ObjectMapper json;
    private final ReferenceImage referenceImage;
    private final AgentWorkflows workflows;
    private final CreativeWorkflows creative;
    private final AgentRuntime runtime;
    private final ScopeGuard scope;
    private final TransactionTemplate transactions;

    public AgentConversations(DSLContext db, ObjectMapper json, ReferenceImage referenceImage,
                              AgentWorkflows workflows, CreativeWorkflows creative,
                              ScopeGuard scope, TransactionTemplate transactions, AgentRuntime runtime) {
        this.db = db;
        this.json = json;
        this.referenceImage = referenceImage;
        this.workflows = workflows;
        this.creative = creative;
        this.scope = scope;
        this.transactions = transactions;
        this.runtime = runtime;
    }

    @Transactional(readOnly = true)
    public List<ConversationHistoryItem> history(long user) {
        // The title comes from the first message, except for the main session created for an episode.
        return db.fetch("""
            select c.id, case when c.creative_episode_id is not null then '主会话' else c.title end as title,
                c.updated_at, p.id as project_id, e.id as episode_id, e.episode_index
            from agent_conversation c
            left join lateral (
                select project_id, episode_id from agent_chat_message where conversation_id = c.id order by id limit 1
            ) first_message on c.creative_episode_id is null
            left join episode e on e.id = coalesce(c.creative_episode_id, first_message.episode_id)
                and e.user_id = c.user_id
                and (first_message.project_id is null or e.project_id = first_message.project_id)
            left join project p on p.id = coalesce(e.project_id, first_message.project_id)
                and p.user_id = c.user_id and p.team_id is null
            where c.user_id = ? order by c.updated_at desc, c.id
            """, user).map(row -> new ConversationHistoryItem(row.get("id", UUID.class), row.get("title", String.class),
                row.get("updated_at", OffsetDateTime.class), row.get("project_id", String.class), row.get("episode_id", String.class),
                row.get("episode_index", Integer.class)));
    }

    @Transactional(readOnly = true)
    public Transcript transcript(long user, UUID id) {
        return transcript(require(user, id, false));
    }

    @Transactional(readOnly = true)
    public Image image(long user, long id) {
        var row = db.fetchOne("""
            select i.media_type, i.content from agent_chat_image i
            join agent_chat_message m on m.id = i.message_id
            join agent_conversation c on c.id = m.conversation_id
            where i.id = ? and c.user_id = ?
            """, id, user);
        if (row == null) throw ApiException.notFound();
        return new Image(row.get("media_type", String.class), row.get("content", byte[].class));
    }

    @Transactional
    public Transcript confirmWorkflow(long user, UUID conversationId, UUID workflowId) {
        require(user, conversationId, false);
        workflows.confirm(user, conversationId, workflowId);
        return transcript(require(user, conversationId, false));
    }

    public Transcript send(long user, UUID id, ChatInput input) {
        return send(user, id, input, null, null);
    }

    Transcript send(long user, UUID id, ChatInput input, BiConsumer<UUID, Transcript> started, Consumer<String> delta) {
        creative.requireConversationScope(id, input.episodeId());
        if (input.projectId() != null) scope.personalProject(user, input.projectId());
        if (input.episodeId() != null) scope.personalEpisode(user, input.episodeId());
        String instruction = input.instruction() == null ? "" : input.instruction().strip();
        TextInput.validate(instruction, 2000, "对话消息");
        var files = input.images() == null ? List.<MultipartFile>of() : input.images();
        if (instruction.isEmpty() && files.isEmpty()) throw ApiException.badRequest("请输入消息或添加图片");
        if (files.size() > 4 || files.stream().mapToLong(MultipartFile::getSize).sum() > 5 * 1024 * 1024)
            throw ApiException.badRequest("每条消息最多上传 4 张图片，合计不超过 5 MiB");
        var uploads = files.stream().map(file -> {
            String filename = Objects.toString(file.getOriginalFilename(), "图片").replace('\\', '/');
            String name = filename.substring(filename.lastIndexOf('/') + 1).strip();
            TextInput.validate(name, 2000, "图片名称");
            return new Upload(shorten(name.isEmpty() ? "图片" : name, 120), referenceImage.read(file));
        }).toList();

        // Reject a reused message identity before any model or business operation executes.
        var replay = transactions.execute(status -> {
            var owner = db.fetchOne("select user_id from agent_conversation where id=? for update", id);
            if (owner != null && owner.get(0, Long.class) != user) throw ApiException.notFound();
            var prior = db.fetchOne("select * from agent_chat_message where conversation_id=? and request_key=?",id,input.requestKey());
            if (prior == null) return null;
            checkReplay(prior,input,instruction,uploads);
            return transcript(require(user,id,false));
        });
        if (replay != null) return replay;

        UUID execution = null;
        if (uploads.isEmpty()) {
            execution = runtime.startMain(user, id, new ChatInput(input.requestKey(), instruction, input.projectId(), input.episodeId(), List.of()));
            UUID run = execution;
            runtime.driveWithReply(run, started == null ? null : () -> started.accept(run, transcript(user, id)), delta);
        }
        UUID run = execution;
        return transactions.execute(status -> save(user, id, input, instruction, uploads, null, null, run));
    }

    private Transcript save(long user, UUID id, ChatInput input, String instruction, List<Upload> uploads,
                            String suggestion, String rejection, UUID execution) {
        if (input.episodeId() != null) {
            long project = scope.lockEpisode(user, input.episodeId());
            scope.personalEpisode(user, input.episodeId());
            if (input.projectId() != null && input.projectId() != project) throw ApiException.notFound();
        } else if (input.projectId() != null) {
            scope.lockProject(user, input.projectId());
            scope.personalProject(user, input.projectId());
        }
        String title = instruction.isEmpty() ? "图片会话" : shorten(instruction.lines().findFirst().orElse(instruction), 28);
        db.execute("""
            insert into agent_conversation(id, user_id, title) values (?, ?, ?)
            on conflict (id) do nothing
            """, id, user, title);
        var conversation = require(user, id, true);
        var previous = db.fetchOne("select * from agent_chat_message where conversation_id = ? and request_key = ?", id, input.requestKey());
        if (previous != null) {
            checkReplay(previous, input, instruction, uploads);
            return transcript(conversation);
        }

        AgentResult result;
        if (execution != null) {
            var view = runtime.view(user, execution);
            rejection = view.error();
            result = rejection == null ? runtime.result(user, execution) : null;
            if (result == null && rejection == null) result = AgentResult.note(WorkbenchSkills.Skill.PROGRESS, "正在执行创作任务", List.of());
        } else {
            result = AgentResult.note(null, "图片已保存到此会话。需要操作工作台时，请另发一条文字指令。", List.of());
        }
        long messageId = db.fetchOne("""
            insert into agent_chat_message(conversation_id, request_key, instruction, project_id, episode_id, result, suggested_instruction, error, agent_run_id)
            values (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?) returning id
            """, id, input.requestKey(), instruction, input.projectId(), input.episodeId(), encode(result), suggestion, rejection, execution).get(0, Long.class);
        for (var upload : uploads) {
            db.execute("insert into agent_chat_image(message_id, name, media_type, content) values (?, ?, ?, ?)",
                messageId, upload.name(), upload.image().mediaType(), upload.image().bytes());
        }
        db.execute("update agent_conversation set updated_at = clock_timestamp() where id = ?", id);
        return transcript(require(user, id, false));
    }

    private void checkReplay(Record previous, ChatInput input, String instruction, List<Upload> uploads) {
        var saved = db.fetch("select name, media_type, content from agent_chat_image where message_id = ? order by id",
            previous.get("id", Long.class));
        if (!instruction.equals(previous.get("instruction", String.class))
            || !Objects.equals(input.projectId(), previous.get("project_id", Long.class))
            || !Objects.equals(input.episodeId(), previous.get("episode_id", Long.class))
            || saved.size() != uploads.size()) throw ApiException.conflict("同一请求标识不能发送不同消息");
        for (int i = 0; i < saved.size(); i++) {
            var row = saved.get(i);
            var upload = uploads.get(i);
            if (!upload.name().equals(row.get("name", String.class))
                || !upload.image().mediaType().equals(row.get("media_type", String.class))
                || !Arrays.equals(upload.image().bytes(), row.get("content", byte[].class)))
                throw ApiException.conflict("同一请求标识不能发送不同图片");
        }
    }

    private Record require(long user, UUID id, boolean lock) {
        var row = db.fetchOne("select * from agent_conversation where id = ? and user_id = ?" + (lock ? " for update" : ""), id, user);
        if (row == null) throw ApiException.notFound();
        return row;
    }

    private Conversation conversation(Record row) {
        return new Conversation(row.get("id", UUID.class), row.get("title", String.class), row.get("updated_at", OffsetDateTime.class));
    }

    private Transcript transcript(Record row) {
        var conversation = conversation(row);
        var runs = workflows.views(row.get("user_id", Long.class), conversation.id());
        var attachments = db.fetch("""
            select i.id, i.message_id, i.name from agent_chat_image i
            join agent_chat_message m on m.id = i.message_id
            where m.conversation_id = ? order by i.id
            """, conversation.id()).stream().collect(Collectors.groupingBy(image -> image.get("message_id", Long.class)));
        var messages = db.fetch("""
            select m.*, p.state as proposal_state, p.image_task_id
            from agent_chat_message m
            left join shot_edit_proposal p on p.id = (m.result #>> '{proposal,id}')::bigint and p.episode_id = m.episode_id
            where m.conversation_id = ? order by m.id
            """, conversation.id()).map(message -> {
                long id = message.get("id", Long.class);
                var images = attachments.getOrDefault(id, List.of()).stream().map(image -> {
                    String imageId = image.get("id", String.class);
                    return new Attachment(imageId, image.get("name", String.class), "/api/agent/conversations/images/" + imageId);
                }).toList();
                String state = message.get("proposal_state", String.class);
                UUID runId = message.get("agent_run_id", UUID.class);
                var execution = runId == null ? null : runtime.view(row.get("user_id", Long.class), runId);
                var currentResult = runId == null ? null : runtime.result(row.get("user_id", Long.class), runId);
                return new ChatMessage(Long.toString(id), message.get("instruction", String.class), message.get("project_id", String.class),
                    message.get("episode_id", String.class), images, currentResult == null ? decode(message.get("result", String.class)) : currentResult,
                    state == null ? null : State.valueOf(state), message.get("image_task_id", String.class),
                    runs.get(message.get("request_key", UUID.class)), message.get("suggested_instruction", String.class),
                    execution != null && execution.error() != null ? execution.error() : message.get("error", String.class),
                    execution);
            });
        return new Transcript(conversation, messages);
    }

    private String shorten(String value, int max) {
        return value.substring(0, value.offsetByCodePoints(0, Math.min(max, value.codePointCount(0, value.length()))));
    }

    private String encode(AgentResult result) {
        if (result == null) return null;
        try { return json.writeValueAsString(result); }
        catch (JsonProcessingException error) { throw new IllegalStateException("无法保存对话回复", error); }
    }

    private AgentResult decode(String result) {
        if (result == null) return null;
        try { return json.readValue(result, AgentResult.class); }
        catch (JsonProcessingException error) { throw new IllegalStateException("无法读取对话回复", error); }
    }

}
