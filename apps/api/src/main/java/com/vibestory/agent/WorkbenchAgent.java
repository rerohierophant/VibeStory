package com.vibestory.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vibestory.agent.WorkbenchAgentController.AgentInstruction;
import com.vibestory.agent.WorkbenchSkills.Command;
import com.vibestory.agent.WorkbenchSkills.Skill;
import com.vibestory.asset.AssetService;
import com.vibestory.batch.GenerationBatch;
import com.vibestory.common.ApiException;
import com.vibestory.common.ScopeGuard;
import com.vibestory.common.TextInput;
import com.vibestory.episode.EpisodeService;
import com.vibestory.image.ImageTaskService;
import com.vibestory.project.ProjectService;
import com.vibestory.shot.ShotEditProposals;
import com.vibestory.shot.ShotService;
import com.vibestory.style.VisualStyleService;
import com.vibestory.task.TaskService;
import com.vibestory.variant.StoryboardVariants;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import org.jooq.DSLContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkbenchAgent {
    public record AgentResult(Skill skill, String message, List<String> details, String projectId, String episodeId,
                              String taskId, String batchId, ShotEditProposals.Proposal proposal, VariantChoice variantChoice) {
        AgentResult(Skill skill, String message, List<String> details, String projectId, String episodeId,
                    String taskId, String batchId, ShotEditProposals.Proposal proposal) {
            this(skill, message, details, projectId, episodeId, taskId, batchId, proposal, null);
        }
        static AgentResult note(Skill skill, String message, List<String> details) {
            return new AgentResult(skill, message, details, null, null, null, null, null);
        }
        static AgentResult task(Skill skill, String message, TaskService.Submission task) {
            return new AgentResult(skill, message, List.of(), null, null, task.taskId(), null, null);
        }
        static AgentResult destination(String projectId, String episodeId, String message) {
            return new AgentResult(Skill.WORKSPACE, message, List.of(), projectId, episodeId, null, null, null);
        }
    }

    public record VariantChoice(String episodeId, String revisionId, String requestKey, String shotId, long shotVersion,
                                String selectedImageId, String imageId, String imageUrl) {}

    private final DSLContext db;
    private final ObjectMapper json;
    private final ScopeGuard scope;
    private final ProjectService projects;
    private final EpisodeService episodes;
    private final VisualStyleService styles;
    private final EpisodeWorkflow workflow;
    private final CreativeWorkflows creative;
    private final ShotService shots;
    private final ShotEditProposals proposals;
    private final AssetService assets;
    private final ImageTaskService images;
    private final GenerationBatch batches;
    private final TaskService tasks;
    private final StoryboardVariants variants;

    public WorkbenchAgent(DSLContext db, ObjectMapper json, ScopeGuard scope,
                          ProjectService projects, EpisodeService episodes, VisualStyleService styles, EpisodeWorkflow workflow, CreativeWorkflows creative,
                          ShotService shots, ShotEditProposals proposals, AssetService assets, ImageTaskService images,
                          GenerationBatch batches, TaskService tasks, StoryboardVariants variants) {
        this.db = db;
        this.json = json;
        this.scope = scope;
        this.projects = projects;
        this.episodes = episodes;
        this.styles = styles;
        this.workflow = workflow;
        this.creative = creative;
        this.shots = shots;
        this.proposals = proposals;
        this.assets = assets;
        this.images = images;
        this.batches = batches;
        this.tasks = tasks;
        this.variants = variants;
    }

    @Transactional
    public AgentResult run(long userId, AgentInstruction input) {
        TextInput.validate(input.instruction(), 2000, "Agent 指令");
        String instruction = input.instruction().strip().replaceFirst("^请\\s*", "");
        Command command;
        try {
            command = WorkbenchSkills.route(instruction);
        } catch (IllegalArgumentException error) {
            throw ApiException.badRequest(error.getMessage());
        }
        checkContext(userId, input, command.action().writes());
        if (!command.action().writes()) return execute(userId, input, instruction, command);

        int inserted = db.execute("""
            insert into agent_command_receipt(user_id, request_key, instruction, project_id, episode_id)
            values (?, ?, ?, ?, ?) on conflict do nothing
            """, userId, input.requestKey(), instruction, input.projectId(), input.episodeId());
        try {
            if (inserted == 0) {
                var receipt = db.fetchOne("""
                    select instruction, project_id, episode_id, result
                    from agent_command_receipt where user_id = ? and request_key = ?
                    """, userId, input.requestKey());
                if (!instruction.equals(receipt.get("instruction", String.class))
                    || !Objects.equals(input.projectId(), receipt.get("project_id", Long.class))
                    || !Objects.equals(input.episodeId(), receipt.get("episode_id", Long.class)))
                    throw ApiException.conflict("同一请求标识不能执行不同指令");
                return json.readValue(receipt.get("result", String.class), AgentResult.class);
            }
            var result = execute(userId, input, instruction, command);
            db.execute("update agent_command_receipt set result = ?::jsonb where user_id = ? and request_key = ?",
                json.writeValueAsString(result), userId, input.requestKey());
            return result;
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Agent 执行回执无法处理", error);
        }
    }

    private void checkContext(long user, AgentInstruction input, boolean writes) {
        if (input.projectId() != null) scope.personalProject(user, input.projectId());
        if (input.episodeId() != null) scope.personalEpisode(user, input.episodeId());
        if (input.episodeId() != null) {
            if (writes) {
                long parent = scope.lockEpisode(user, input.episodeId());
                if (input.projectId() != null && parent != input.projectId()) throw ApiException.notFound();
            } else {
                scope.episode(user, input.episodeId());
                if (input.projectId() != null && db.fetchOne(
                    "select id from episode where id = ? and project_id = ? and user_id = ?", input.episodeId(), input.projectId(), user) == null)
                    throw ApiException.notFound();
            }
        } else if (input.projectId() != null) {
            if (writes) scope.lockProject(user, input.projectId());
            else scope.project(user, input.projectId());
        }
    }

    private AgentResult execute(long user, AgentInstruction input, String instruction, Command command) {
        Long project = input.projectId();
        Long episode = input.episodeId();
        String nonce = input.requestKey().toString();
        Skill skill = command.action().skill();
        return switch (command.action()) {
            case OPEN_EPISODE -> openEpisode(user, command);
            case NEW_EPISODE -> {
                long id = required(project, "请先打开项目");
                var created = episodes.create(user, id, command.text(1));
                yield AgentResult.destination(Long.toString(id), created.id(), "已新建第 " + created.index() + " 集《" + created.title() + "》");
            }
            case PROJECT_STYLE -> {
                long id = required(project, "请先打开项目");
                String name = command.text(1).strip().equals("写实") ? "realistic" : command.text(1).strip();
                var style = unique(styles.list(user).stream().filter(item -> item.label().equals(name) || item.id().equals(name)).toList(),
                    "找不到该风格，请使用预设名称", "风格名称有重复，请使用预设 ID");
                projects.update(user, id, null, style.id(), null);
                yield AgentResult.note(skill, "项目风格已设为" + style.label(), List.of());
            }
            case SAVE_STORY -> {
                long id = required(episode, "请先打开一集");
                if (!episodes.get(user, id).story().isBlank())
                    throw ApiException.conflict("本集已有正文，请在故事页查看和修改");
                episodes.story(user, id, command.text(1));
                yield AgentResult.note(skill, "已保存本集正文", List.of());
            }
            case PLAN_PROJECT -> creativeReceipt(skill, "已登记项目分集规划",
                creative.plan(user, required(project, "请先打开项目"), input.requestKey(), notes(command.text(1))));
            case ACCEPT_EPISODE_PLAN -> creativeReceipt(skill, "已确认并创建分集",
                creative.acceptPlan(user, required(project, "请先打开项目"), java.util.UUID.fromString(command.text(1))));
            case PREPARE_SCRIPT -> creativeReceipt(skill, "已派发剧本子 Agent",
                creative.script(user, required(episode, "请先打开一集"), project, input.requestKey(), notes(command.text(1))));
            case PLAN_STORYBOARD -> creativeReceipt(skill, "已派发分镜子 Agent",
                creative.storyboard(user, required(episode, "请先打开一集"), project, input.requestKey(), null, notes(command.text(1))));
            case APPLY_STORYBOARD -> creativeReceipt(skill, "已应用分镜草案",
                creative.applyStoryboard(user, required(episode, "请先打开一集"), project, java.util.UUID.fromString(command.text(1))));
            case BREAKDOWN -> AgentResult.task(skill, "拆镜任务已提交",
                workflow.breakdown(user, required(episode, "请先打开一集"), false));
            case EDIT_SHOT -> new AgentResult(skill, "镜头改动待确认", List.of(), null, null, null, null,
                proposals.preview(user, required(episode, "请先打开一集"), input.requestKey(), instruction));
            case DESCRIBE_SHOT -> new AgentResult(skill, "画面描述待确认", List.of(), null, null, null, null,
                proposals.previewDescription(user, required(episode, "请先打开一集"), input.requestKey(), instruction,
                    command.number(1), command.text(2).equals("追加"), command.text(3)));
            case CONFIRM_SHOT -> {
                var shot = shot(user, episode, command.number(1));
                workflow.shotStatus(user, Long.parseLong(shot.id()), "confirmed");
                yield AgentResult.note(skill, "已确认第 " + shot.seq() + " 镜", List.of());
            }
            case CONFIRM_ALL -> {
                var confirmed = workflow.confirmShots(user, required(episode, "请先打开一集"));
                yield AgentResult.note(skill, "本集已确认 " + confirmed.size() + " 个镜头", List.of());
            }
            case GENERATE_ASSET, SELECT_ASSET -> asset(user, required(project, "请先打开项目"), command, nonce);
            case MISSING_IMAGES -> missingImages(user, required(episode, "请先打开一集"), nonce);
            case MISSING_VIDEOS -> missingVideos(user, required(episode, "请先打开一集"), nonce);
            case GENERATE_SHOT -> generateShot(user, shot(user, episode, command.number(1)), command.text(2));
            case SELECT_FRAME -> selectFrame(user, shot(user, episode, command.number(1)), command);
            case VARIANT_CREATE, VARIANT_LIST, VARIANT_REVISE, VARIANT_PREVIEW ->
                variant(user, input, required(episode, "请先打开一集"), command);
            case PROBLEMS -> problems(user, required(episode, "请先打开一集"));
            case VIDEO_SOURCE -> source(shot(user, episode, command.number(1)));
            case RETRY_FAILED -> retryFailed(user, required(episode, "请先打开一集"));
        };
    }

    private AgentResult variant(long user, AgentInstruction input, long episode, Command command) {
        Skill skill = command.action().skill();
        if (command.action() == WorkbenchSkills.Action.VARIANT_CREATE) {
            var shot = shot(user, episode, command.number(1));
            var edit = new StoryboardVariants.Edit(Long.parseLong(shot.id()), shot.description(), command.text(2), shot.cameraMove());
            var variant = variants.submit(user, episode, new StoryboardVariants.Submit(input.requestKey(), command.text(3), null, List.of(edit)));
            return AgentResult.note(skill, "已提交出图方案《" + variant.name() + "》", List.of("第 " + shot.seq() + " 镜 · " + edit.shotSize()));
        }
        var available = variants.list(user, episode).variants();
        if (command.action() == WorkbenchSkills.Action.VARIANT_LIST) {
            var details = available.stream().map(variant -> "《" + variant.name() + "》第 " + variant.revision()
                + " 版 · 已出图 " + variant.members().stream().filter(member -> member.imageId() != null).count()
                + "/" + variant.members().size()).toList();
            return AgentResult.note(skill, details.isEmpty() ? "本集暂无出图方案" : "本集出图方案", details);
        }
        var variant = unique(available.stream().filter(item -> item.name().equals(command.text(1))).toList(),
            "找不到该出图方案", "方案名称有重复，请在分镜页选择");
        var shot = shot(user, episode, command.number(2));
        var member = variant.members().stream().filter(item -> item.shotId().equals(shot.id())).findFirst()
            .orElseThrow(() -> ApiException.badRequest("这套方案没有该镜头"));
        long revision = Long.parseLong(variant.revisionId());
        if (command.action() == WorkbenchSkills.Action.VARIANT_REVISE) {
            var edit = new StoryboardVariants.Edit(Long.parseLong(shot.id()), member.description(), command.text(3), member.cameraMove());
            var revised = variants.submit(user, episode, new StoryboardVariants.Submit(input.requestKey(), null, revision, List.of(edit)));
            return AgentResult.note(skill, "已提交《" + revised.name() + "》第 " + revised.revision() + " 版",
                List.of("重出第 " + shot.seq() + " 镜 · " + edit.shotSize()));
        }
        if (member.imageId() == null) throw ApiException.conflict("这张分镜图尚未就绪");
        var details = List.of("当前 " + shot.shotSize() + " / " + shot.cameraMove() + " · " + shot.description(),
            "方案 " + member.shotSize() + " / " + member.cameraMove() + " · " + member.description());
        var choice = new VariantChoice(Long.toString(episode), variant.revisionId(), input.requestKey().toString(), shot.id(),
            shot.version(), shot.selectedImageId(), member.imageId(), member.imageUrl());
        return new AgentResult(skill, "采纳预览 · " + variant.name() + " · 第 " + shot.seq() + " 镜", details,
            null, null, null, null, null, choice);
    }

    private AgentResult openEpisode(long user, Command command) {
        String name = command.text(1).strip();
        var project = unique(projects.list(user, name).stream().filter(item -> item.name().equals(name)).toList(),
            "找不到该项目", "项目名称有重复，请在工作台选定项目");
        var episode = project.episodes().stream().filter(item -> item.index() == command.number(2)).findFirst()
            .orElseThrow(ApiException::notFound);
        return AgentResult.destination(project.id(), episode.id(), "打开《" + project.name() + "》的第 " + episode.index() + " 集");
    }

    private AgentResult asset(long user, long project, Command command, String nonce) {
        boolean kindAtEnd = command.text(1) == null;
        boolean character = command.text(kindAtEnd ? 4 : 1).equals("角色");
        String name = command.text(kindAtEnd ? 3 : 2).strip();
        var asset = unique(assets.list(user, project, character).stream().filter(item -> item.name().equals(name)).toList(),
            "当前项目里找不到该资产", "资产名称有重复，请在资产页选定");
        long id = Long.parseLong(asset.id());
        if (command.action() == WorkbenchSkills.Action.GENERATE_ASSET) {
            if (asset.generationState().equals("unknown"))
                throw ApiException.conflict("该资产的生成结果未知，请先在资产页处理原任务");
            return AgentResult.task(Skill.ASSET, "已提交《" + asset.name() + "》的参考图任务", images.submitAsset(user, character, id, nonce));
        }
        int version = command.number(3);
        if (version > asset.versions().size()) throw ApiException.badRequest("该资产没有指定的版本");
        assets.select(user, character, id, Long.parseLong(asset.versions().get(version - 1).id()));
        return AgentResult.note(Skill.ASSET, "已将《" + asset.name() + "》的第 " + version + " 版设为基准", List.of());
    }

    private static String notes(String value) { return value == null ? "" : value.strip(); }

    private AgentResult creativeReceipt(Skill skill, String message, CreativeWorkflows.CreativeTurn turn) {
        return AgentResult.note(skill, message, List.of("产物引用 " + turn.id(), "子会话 " + turn.sessionId()));
    }

    private AgentResult missingImages(long user, long episode, String nonce) {
        var eligible = shots.list(user, episode, "todo").stream().filter(shot -> shot.status().equals("confirmed")).toList();
        var unknown = eligible.stream().filter(shot -> shot.imageState().equals("unknown"))
            .map(shot -> "第 " + shot.seq() + " 镜 · 结果未知，请先处理原任务").toList();
        var ids = eligible.stream().filter(shot -> !shot.imageState().equals("unknown"))
            .map(shot -> Long.parseLong(shot.id())).toList();
        if (ids.isEmpty()) return AgentResult.note(Skill.GENERATE, "本集暂无可提交的缺图镜头", unknown);
        var batch = workflow.generateIndependentBatch(user, episode, GenerationBatch.Type.IMAGE, nonce, ids, false);
        var details = new ArrayList<>(unknown);
        batch.members().stream().filter(member -> member.outcome().equals("blocked"))
            .map(member -> "第 " + member.seq() + " 镜 · " + member.reason()).forEach(details::add);
        var counts = batch.progress();
        String message = "出图批次已登记，新增 " + counts.accepted() + "、复用 " + counts.reused() + "、阻塞 " + counts.blocked();
        return new AgentResult(Skill.GENERATE, message, details, null, null, null, batch.id(), null);
    }

    private AgentResult missingVideos(long user, long episode, String nonce) {
        var submitted = workflow.generateMissingVideos(user, episode, nonce);
        if (submitted.isEmpty()) return AgentResult.note(Skill.GENERATE, "本集暂无可提交的缺视频镜头",
            List.of(workflow.plan(user, episode).next().message()));
        var details = submitted.stream().map(batch -> "批次 " + batch.id() + " · 新增 " + batch.progress().accepted()
            + "、复用 " + batch.progress().reused() + "、阻塞 " + batch.progress().blocked()).toList();
        return new AgentResult(Skill.GENERATE, "已登记 " + submitted.size() + " 个视频批次", details,
            null, null, null, submitted.getFirst().id(), null);
    }

    private AgentResult generateShot(long user, ShotService.View shot, String media) {
        long id = Long.parseLong(shot.id());
        boolean video = media.equals("视频");
        if ((video ? shot.video().state() : shot.imageState()).equals("unknown"))
            throw ApiException.conflict("该镜头的" + media + "结果未知，请先处理原任务");
        var submission = workflow.generateShot(user, id,
            video ? GenerationBatch.Type.VIDEO : GenerationBatch.Type.IMAGE, null, true);
        return AgentResult.task(Skill.GENERATE, "第 " + shot.seq() + " 镜的" + media + "任务已提交", submission);
    }

    private AgentResult selectFrame(long user, ShotService.View shot, Command command) {
        int candidate = command.number(2);
        if (candidate > shot.images().size()) throw ApiException.badRequest("该镜头没有指定的候选图");
        workflow.selectFrame(user, Long.parseLong(shot.id()), Long.parseLong(shot.images().get(candidate - 1).id()));
        if (command.text(3) != null) return generateShot(user, shot, "视频");
        return AgentResult.note(Skill.GENERATE, "已选第 " + shot.seq() + " 镜的第 " + candidate + " 张图作为首帧", List.of());
    }

    private AgentResult problems(long user, long episode) {
        var details = new ArrayList<String>();
        for (var shot : shots.list(user, episode, "all")) {
            addProblem(details, shot.seq(), "分镜图", shot.imageState(), shot.imageError());
            addProblem(details, shot.seq(), "视频", shot.video().state(), shot.video().error());
        }
        String message = details.isEmpty() ? "本集当前没有失败或结果未知的分镜图、视频任务" : "本集有 " + details.size() + " 项任务需要关注";
        return AgentResult.note(Skill.PROGRESS, message, details);
    }

    private void addProblem(List<String> details, int shot, String media, String state, String error) {
        if (!state.equals("failed") && !state.equals("unknown")) return;
        details.add("第 " + shot + " 镜 · " + media + (state.equals("unknown") ? "结果未知" : "失败")
            + (error == null ? "" : " · " + error));
    }

    private AgentResult source(ShotService.View shot) {
        var video = shot.video();
        String id = video.ready() ? video.basedOn() : video.pendingBasedOn();
        if (id == null) return AgentResult.note(Skill.PROGRESS, "第 " + shot.seq() + " 镜暂无可查询的首帧来源", List.of());
        String label = shot.images().stream().filter(image -> image.id().equals(id)).findFirst()
            .map(ShotService.Image::label).orElse("历史分镜图");
        return AgentResult.note(Skill.PROGRESS, "第 " + shot.seq() + " 镜" + (video.ready() ? "的视频使用" : "的视频任务提交时使用")
            + label + "作为首帧", List.of("分镜图 ID " + id));
    }

    private AgentResult retryFailed(long user, long episode) {
        var batch = Stream.of(GenerationBatch.Type.values()).flatMap(type -> batches.list(user, episode, type).stream())
            .max(Comparator.comparingLong(item -> Long.parseLong(item.id())))
            .orElseThrow(() -> ApiException.badRequest("本集还没有生成批次"));
        var details = new ArrayList<String>();
        for (var member : batch.members()) {
            if (!member.state().equals("failed") || member.taskId() == null) continue;
            long id = Long.parseLong(member.taskId());
            var task = tasks.get(user, id);
            if (!task.retryable()) continue;
            if (task.type().equals("video") && !shot(user, episode, member.seq()).status().equals("confirmed")) continue;
            tasks.retry(user, id, false);
            details.add("第 " + member.seq() + " 镜 · 任务 " + id);
        }
        String message = details.isEmpty() ? "上一批暂无可重试的已知失败任务" : "已重新排队 " + details.size() + " 个失败任务";
        return new AgentResult(Skill.PROGRESS, message, details, null, null, null, batch.id(), null);
    }

    private ShotService.View shot(long user, Long episode, int sequence) {
        return shots.list(user, required(episode, "请先打开一集"), "all").stream().filter(item -> item.seq() == sequence)
            .findFirst().orElseThrow(ApiException::notFound);
    }

    private long required(Long id, String message) {
        if (id == null) throw ApiException.badRequest(message);
        return id;
    }

    private <T> T unique(List<T> matches, String missing, String ambiguous) {
        if (matches.isEmpty()) throw ApiException.badRequest(missing);
        if (matches.size() > 1) throw ApiException.conflict(ambiguous);
        return matches.getFirst();
    }
}
