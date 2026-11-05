package com.vibestory.shot;

import com.vibestory.agent.ShotEditSkill;
import com.vibestory.common.ApiException;
import com.vibestory.common.ScopeGuard;
import com.vibestory.common.TextInput;
import com.vibestory.image.ImageTaskService;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ShotEditProposals {
    public enum State { PENDING, APPLIED, DISCARDED }
    public record Change(String field, String before, String after) {}
    public record Proposal(String id, String shotId, int shotNumber, String instruction, State state, List<Change> changes,
                           boolean generateImage, String imageTaskId) {}

    private static final String SELECT = """
        select p.*, s.seq from shot_edit_proposal p
        join shot s on s.id = p.shot_id and s.episode_id = p.episode_id
        where p.episode_id = ?
        """;
    private static final Pattern GENERATE = Pattern.compile("[，,]\\s*(?:并|再)生成新候选[。！!]*$");

    private final DSLContext db;
    private final ScopeGuard scope;
    private final ShotService shots;
    private final ImageTaskService images;
    private final ShotEditSkill skill = new ShotEditSkill();

    public ShotEditProposals(DSLContext db, ScopeGuard scope, ShotService shots, ImageTaskService images) {
        this.db = db;
        this.scope = scope;
        this.shots = shots;
        this.images = images;
    }

    @Transactional
    public Proposal preview(long userId, long episodeId, UUID requestKey, String instruction) {
        long projectId = scope.lockEpisode(userId, episodeId);
        TextInput.validate(instruction, 2000, "改镜指令");
        String command = instruction.strip();
        var existing = db.fetchOne(SELECT + " and p.request_key = ?", episodeId, requestKey);
        if (existing != null) {
            if (!command.equals(existing.get("instruction", String.class)))
                throw ApiException.conflict("同一请求标识不能提交不同的改镜指令");
            return view(existing);
        }

        ShotEditSkill.Intent intent;
        try {
            intent = skill.parse(command);
        } catch (IllegalArgumentException error) {
            throw ApiException.badRequest(error.getMessage());
        }
        var shot = shot(userId, projectId, episodeId, intent.shotNumber());

        String oldSize = shot.get("shot_size", String.class);
        String oldDialogue = shot.get("dialogue", String.class);
        String size = Objects.equals(oldSize, intent.shotSize()) ? null : intent.shotSize();
        String dialogue;
        try {
            dialogue = intent.shortenDialogue() ? skill.shorten(oldDialogue) : null;
        } catch (IllegalArgumentException error) {
            throw ApiException.badRequest(error.getMessage());
        }
        if (size == null && dialogue == null) throw ApiException.badRequest("镜头已是指定景别");
        return insert(episodeId, requestKey, command, shot, size, dialogue, null, false);
    }

    @Transactional
    public Proposal previewDescription(long userId, long episodeId, UUID requestKey, String instruction,
                                       int number, boolean append, String requested) {
        long projectId = scope.lockEpisode(userId, episodeId);
        var existing = db.fetchOne(SELECT + " and p.request_key = ?", episodeId, requestKey);
        if (existing != null) {
            if (!instruction.equals(existing.get("instruction", String.class)))
                throw ApiException.conflict("同一请求标识不能提交不同的改镜指令");
            return view(existing);
        }
        var shot = shot(userId, projectId, episodeId, number);
        var suffix = GENERATE.matcher(requested);
        boolean generate = suffix.find();
        String description = (generate ? requested.substring(0, suffix.start()) : requested).strip();
        String before = shot.get("description", String.class);
        if (description.isBlank()) throw ApiException.badRequest("请提供新的画面描述");
        if (append && !before.isBlank()) description = before + "\n" + description;
        TextInput.validate(description, 16000, "画面描述");
        if (description.equals(before)) throw ApiException.badRequest("画面描述没有变化");
        if (generate && !"CONFIRMED".equals(shot.get("status", String.class)))
            throw ApiException.conflict("请先确认这镜，再修改画面描述并生成新候选");
        return insert(episodeId, requestKey, instruction, shot, null, null, description, generate);
    }

    private Record shot(long userId, long projectId, long episodeId, int number) {
        var shot = db.fetchOne("""
            select id, version, shot_size, dialogue, description, status from shot
            where episode_id = ? and project_id = ? and user_id = ? and seq = ?
            """, episodeId, projectId, userId, number);
        if (shot == null) throw ApiException.notFound();
        return shot;
    }

    private Proposal insert(long episodeId, UUID requestKey, String instruction, Record shot, String size,
                            String dialogue, String description, boolean generate) {
        long id = db.fetchOne("""
            insert into shot_edit_proposal (episode_id, shot_id, request_key, instruction, base_version,
                old_shot_size, new_shot_size, old_dialogue, new_dialogue, old_description, new_description, generate_image)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) returning id
            """, episodeId, shot.get("id", Long.class), requestKey, instruction, shot.get("version", Long.class),
            shot.get("shot_size", String.class), size, shot.get("dialogue", String.class), dialogue,
            shot.get("description", String.class), description, generate).get(0, Long.class);
        return view(require(episodeId, id));
    }

    @Transactional(readOnly = true)
    public Proposal latest(long userId, long episodeId) {
        scope.episode(userId, episodeId);
        var row = db.fetchOne(SELECT + " order by p.id desc limit 1", episodeId);
        return row == null ? null : view(row);
    }

    @Transactional
    public Proposal decide(long userId, long episodeId, long id, boolean apply) {
        scope.lockEpisode(userId, episodeId);
        var row = require(episodeId, id);
        var current = State.valueOf(row.get("state", String.class));
        var next = apply ? State.APPLIED : State.DISCARDED;
        if (current == next) return view(row);
        if (current != State.PENDING) throw ApiException.conflict("这份提案已经处理");
        Long taskId = null;
        if (apply) {
            long shotId = row.get("shot_id", Long.class);
            if (Boolean.TRUE.equals(row.get("generate_image", Boolean.class)) && "unknown".equals(shots.get(userId, shotId).imageState()))
                throw ApiException.conflict("原出图任务结果未知，请先处理原任务");
            shots.update(userId, shotId, row.get("new_description", String.class), row.get("new_dialogue", String.class), null,
                row.get("new_shot_size", String.class), null, null, row.get("base_version", Long.class));
            if (Boolean.TRUE.equals(row.get("generate_image", Boolean.class)))
                taskId = Long.parseLong(images.submitBatchMember(userId, shotId, "agent-proposal-" + id, true).taskId());
        }
        db.execute("update shot_edit_proposal set state = ?, image_task_id = ? where id = ? and episode_id = ?",
            next.name(), taskId, id, episodeId);
        return view(require(episodeId, id));
    }

    private Record require(long episodeId, long id) {
        var row = db.fetchOne(SELECT + " and p.id = ?", episodeId, id);
        if (row == null) throw ApiException.notFound();
        return row;
    }

    private Proposal view(Record row) {
        var changes = new ArrayList<Change>();
        if (row.get("new_shot_size") != null)
            changes.add(new Change("shotSize", row.get("old_shot_size", String.class), row.get("new_shot_size", String.class)));
        if (row.get("new_dialogue") != null)
            changes.add(new Change("dialogue", row.get("old_dialogue", String.class), row.get("new_dialogue", String.class)));
        if (row.get("new_description") != null)
            changes.add(new Change("description", row.get("old_description", String.class), row.get("new_description", String.class)));
        return new Proposal(row.get("id", String.class), row.get("shot_id", String.class), row.get("seq", Integer.class),
            row.get("instruction", String.class), State.valueOf(row.get("state", String.class)), List.copyOf(changes),
            Boolean.TRUE.equals(row.get("generate_image", Boolean.class)), row.get("image_task_id", String.class));
    }
}
