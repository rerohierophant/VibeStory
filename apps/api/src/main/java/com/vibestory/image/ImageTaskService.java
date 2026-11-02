package com.vibestory.image;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vibestory.asset.AssetService;
import com.vibestory.common.*;
import com.vibestory.provider.*;
import com.vibestory.storage.LocalStorage;
import com.vibestory.task.InputSignature;
import com.vibestory.task.TaskService;
import com.vibestory.style.VisualStyleService;
import java.util.*;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ImageTaskService {
    private final DSLContext db;
    private final ScopeGuard scope;
    private final AssetService assets;
    private final ImageProperties config;
    private final InputSignature signatures;
    private final ObjectMapper json;
    private final LocalStorage storage;
    private final VisualStyleService styles;
    private final ImageProviders providers;
    public ImageTaskService(DSLContext db, ScopeGuard scope, AssetService assets, ImageProperties config,
                            InputSignature signatures, ObjectMapper json, LocalStorage storage, VisualStyleService styles, ImageProviders providers) {
        this.db=db; this.scope=scope; this.assets=assets; this.config=config; this.signatures=signatures; this.json=json; this.storage=storage;
        this.styles=styles;
        this.providers=providers;
    }

    @Transactional
    public TaskService.Submission submitShot(long userId, long shotId, String nonce) {
        return submitBatchMember(userId,shotId,nonce,false);
    }

    @Transactional(noRollbackFor = ApiException.class)
    public TaskService.Submission submitBatchMember(long userId,long shotId,String nonce,boolean rejectConflictingActive) {
        var shot = scope.shot(userId, shotId);
        if (!shot.get("status", String.class).equals("CONFIRMED")) throw ApiException.conflict("镜头确认后才能生成分镜图");
        var characters = new ArrayList<ImageProvider.AssetSnapshot>();
        var references = new ArrayList<ImageProvider.Reference>();
        ImageProvider.AssetSnapshot scene = new ImageProvider.AssetSnapshot(null, shot.get("scene_name", String.class), null, null);
        var refs = db.fetch("""
            select r.role, coalesce(c.id,s.id) as asset_id, coalesce(c.name,s.name) as name,
                v.id as version_id,v.label,v.storage_key,v.media_type
            from asset_ref r left join character_asset c on c.id=r.character_id
            left join scene_asset s on s.id=r.scene_id
            left join asset_version v on (v.character_id=r.character_id or v.scene_id=r.scene_id) and v.is_selected
            where r.shot_id=? order by r.role,r.id
            """, shotId);
        for (var row : refs) {
            var source = new ImageProvider.AssetSnapshot(row.get("asset_id", String.class), row.get("name", String.class), row.get("version_id", String.class), row.get("label", String.class));
            if (row.get("role", String.class).equals("CHARACTER")) characters.add(source); else scene=source;
            if (row.get("version_id") != null) references.add(reference(row));
        }
        // Legacy or unbound names remain visible without inventing a selected version.
        try {
            for (var name : json.readTree(shot.get("character_names", String.class)))
                if (characters.stream().noneMatch(c -> c.name().equals(name.asText())))
                    characters.add(new ImageProvider.AssetSnapshot(null, name.asText(), null, null));
        } catch (java.io.IOException error) { throw new IllegalStateException("镜头人物名称无效", error); }
        var sources = new ImageProvider.Sources(List.copyOf(characters), scene);
        String prompt = shot.get("description", String.class) + "\n景别：" + shot.get("shot_size", String.class) + "；运镜：" + shot.get("camera_move", String.class)
            + "；场景：" + scene.name() + "；人物：" + String.join("、", characters.stream().map(ImageProvider.AssetSnapshot::name).toList()) + style(shot);
        var input = input("IMAGE", shotId, "SHOT", prompt, "16:9", sources, references, nonce);
        return submit(userId, shot.get("project_id", Long.class), shot.get("episode_id", Long.class), input, rejectConflictingActive);
    }

    @Transactional
    public TaskService.Submission submitAsset(long userId, boolean character, long id, String nonce) {
        var asset = assets.lock(userId, character, id);
        var selected = db.fetchOne("select id as version_id,storage_key,media_type from asset_version where " + (character ? "character_id" : "scene_id") + "=? and is_selected", id);
        var references = selected == null ? List.<ImageProvider.Reference>of() : List.of(new ImageProvider.Reference(selected.get("version_id", String.class), character ? "CHARACTER" : "SCENE", selected.get("storage_key", String.class), selected.get("media_type", String.class)));
        String prompt = (character ? "人物设定，单人清晰正面形象：" : "场景设定，清晰环境全景：") + asset.get("name", String.class) + "。" + asset.get("description", String.class) + style(asset);
        return submit(userId, asset.get("project_id", Long.class), null,
            input("ASSET_IMAGE", id, character ? "CHARACTER" : "SCENE", prompt, character ? "1:1" : "16:9", null, references, nonce));
    }

    private ImageProvider.Input input(String type, long id, String target, String prompt, String aspect, ImageProvider.Sources sources,
                                       List<ImageProvider.Reference> available, String nonce) {
        if (nonce != null && (nonce.isBlank() || nonce.length() > 128)) throw ApiException.badRequest("重新生成标识无效");
        TextInput.validate(prompt, 24000, "图片提示词");
        var capability=providers.capabilities(config.provider());
        if (prompt.codePointCount(0, prompt.length()) > capability.maxPromptCharacters())
            throw ApiException.badRequest("MiniMax 图片提示词含风格与引用信息后不能超过 1500 字符，请缩短镜头描述");
        var used=new ArrayList<ImageProvider.Reference>();
        used.addAll(available.stream().filter(r -> r.role().equals("CHARACTER")).limit(capability.characterReferences()).toList());
        if (capability.sceneReferences()) used.addAll(available.stream().filter(r -> r.role().equals("SCENE")).toList());
        String mode = available.isEmpty() ? "none" : used.isEmpty() ? "text-only" : used.size() == available.size() ? "full" : "partial";
        String notice = config.provider().equals("mock") ? "Mock 占位图，未验证视觉一致性"
            : mode.equals("text-only") ? "当前适配器仅使用文本提示，未发送资产参考图"
            : mode.equals("partial") ? "MiniMax 仅支持一个人物参考图；其他人物与场景仅记录来源并通过文本描述"
            : null;
        return new ImageProvider.Input(type, id, target, config.provider(), config.baseUrl(), config.model(), prompt,
            aspect, "image-v1", sources,
            List.copyOf(available), new ImageProvider.Guidance(mode, used.stream().map(ImageProvider.Reference::versionId).toList(), notice), nonce);
    }

    private String style(Record row) {
        var preset=styles.resolve(row.get("user_id",Long.class), row.get("visual_type",String.class));
        return "；画面风格：" + preset.label() + "。" + preset.hint() + "。" + row.get("style",String.class);
    }
    private ImageProvider.Reference reference(Record row) {
        return new ImageProvider.Reference(row.get("version_id", String.class), row.get("role", String.class), row.get("storage_key", String.class), row.get("media_type", String.class));
    }

    private TaskService.Submission submit(long userId, long projectId, Long episodeId, ImageProvider.Input input) {
        return submit(userId,projectId,episodeId,input,false);
    }

    private TaskService.Submission submit(long userId,long projectId,Long episodeId,ImageProvider.Input input,boolean rejectConflictingActive) {
        var frozen = signatures.of(input);
        if(rejectConflictingActive && db.fetchOne("select id from task where user_id=? and type=? and target_type=? and target_id=? and status in ('QUEUED','RUNNING') and input_signature<>? limit 1",userId,input.type(),input.targetType(),input.targetId(),frozen.signature())!=null)
            throw ApiException.conflict("同镜头已有不同输入的活跃图片任务");
        // Same submitted input cannot bypass UNKNOWN acknowledgement by clicking the regular submit button.
        var existing = db.fetchOne("select id,status from task where user_id=? and type=? and target_type=? and target_id=? and input_signature=? and status in ('QUEUED','RUNNING','UNKNOWN') order by id desc limit 1",
            userId, input.type(), input.targetType(), input.targetId(), frozen.signature());
        if (existing != null) {
            if (existing.get("status", String.class).equals("UNKNOWN")) throw ApiException.conflict("同一输入的外部结果未知，请在原任务明确确认重试");
            return new TaskService.Submission(existing.get("id", String.class));
        }
        var row = db.fetchOne("""
            insert into task(user_id,project_id,episode_id,type,target_type,target_id,provider,input_signature,input_snapshot)
            values (?,?,?,?,?,?,?, ?,?::jsonb)
            on conflict (user_id,type,target_type,target_id,input_signature) where status in ('QUEUED','RUNNING')
            do update set input_signature=excluded.input_signature returning id
            """, userId, projectId, episodeId, input.type(), input.targetType(), input.targetId(), input.provider(), frozen.signature(), frozen.json());
        return new TaskService.Submission(row.get("id", String.class));
    }

    public ImageProvider.Input input(String snapshot) {
        try { return json.readValue(snapshot, ImageProvider.Input.class); }
        catch (JsonProcessingException error) { throw new IllegalStateException("图片输入快照无效", error); }
    }

    @Transactional
    public void prepareRetry(long userId, Record task) {
        var input = input(task.get("input_snapshot", String.class));
        if (input.type().equals("IMAGE")) {
            if (!scope.shot(userId, input.targetId()).get("status", String.class).equals("CONFIRMED")) throw ApiException.conflict("镜头确认后才能重试生成");
        } else assets.lock(userId, input.targetType().equals("CHARACTER"), input.targetId());
    }

    @Transactional
    public void install(long userId, long projectId, Long episodeId, long taskId, int attempt, ImageProvider.Input input, ImageProvider.Result result) {
        String prefix = "u/" + userId + "/p/" + projectId + "/images/" + taskId + "/" + attempt;
        String key = prefix + "/image." + (result.mediaType().equals("image/png") ? "png" : "jpg");
        assets.putFile(prefix, key, result.bytes());
        if (input.type().equals("IMAGE")) {
            boolean first = db.fetchOne("select id from shot_image where shot_id=? and selected", input.targetId()) == null;
            long version = db.fetchOne("select coalesce(max(version),0)+1 from shot_image where shot_id=?", input.targetId()).get(0, Long.class);
            db.execute("""
                insert into shot_image(shot_id,episode_id,version,selected,storage_key,input_snapshot,label,task_id,media_type)
                values (?,?,?,?,?,?::jsonb,?,?,?)
                """, input.targetId(), episodeId, version, first, key, encode(input.sources()), "候选 " + version, taskId, result.mediaType());
        } else assets.addVersion(userId, projectId, input.targetType().equals("CHARACTER"), input.targetId(), "生成版本 " + taskId, "generated", key, result.mediaType(), taskId);
        db.execute("update task set output_snapshot=?::jsonb where id=?", encode(Map.of("ready", true, "guidance", input.guidance())), taskId);
    }

    @Transactional
    public void select(long userId, long shotId, long imageId) {
        scope.shot(userId, shotId);
        if (db.fetchOne("select id from shot_image where id=? and shot_id=? and status='READY'", imageId, shotId) == null) throw ApiException.notFound();
        db.execute("update shot_image set selected=false where shot_id=? and selected", shotId);
        db.execute("update shot_image set selected=true where id=?", imageId);
    }

    @Transactional(readOnly = true)
    public com.vibestory.style.VisualStyleService.Image image(long userId, long id) {
        var row = db.fetchOne("select i.* from shot_image i join shot s on s.id=i.shot_id join project p on p.id=s.project_id and p.user_id=s.user_id where i.id=? and s.user_id=? and i.status='READY'", id, userId);
        if (row == null || row.get("media_type") == null) throw ApiException.notFound();
        var resource = storage.resource(row.get("storage_key", String.class));
        if (!resource.isReadable()) throw ApiException.notFound();
        return new com.vibestory.style.VisualStyleService.Image(resource, row.get("media_type", String.class));
    }

    private String encode(Object value) {
        try { return json.writeValueAsString(value); } catch (JsonProcessingException error) { throw new IllegalStateException(error); }
    }
}
