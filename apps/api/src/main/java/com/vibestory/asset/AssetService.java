package com.vibestory.asset;

import com.vibestory.common.ScopeGuard;
import com.vibestory.common.TextInput;
import com.vibestory.common.ApiException;
import com.vibestory.storage.LocalStorage;
import com.vibestory.style.ReferenceImage;
import com.vibestory.style.VisualStyleService;
import java.io.IOException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

@Service
public class AssetService {
    private final DSLContext db;
    private final ScopeGuard scope;
    private final LocalStorage storage;
    private final ReferenceImage images;
    public AssetService(DSLContext db, ScopeGuard scope, LocalStorage storage, ReferenceImage images) {
        this.db = db; this.scope = scope; this.storage = storage; this.images = images;
    }

    @io.swagger.v3.oas.annotations.media.Schema(name = "AssetVersion")
    public record Version(String id, String label, String seed, long createdAt,
                          @io.swagger.v3.oas.annotations.media.Schema(allowableValues = {"uploaded","generated"}) String source, String url) {}
    public record Usage(int shots, List<Integer> episodes) {}
    @io.swagger.v3.oas.annotations.media.Schema(name = "AssetCard")
    public record Card(String id, String projectId, String name, String description, List<Version> versions, String selectedVersionId,
                       String projectName, Usage usage, String generationState, String taskId) {}
    public record Binding(String sceneName, List<String> characterNames) {}

    @Transactional(readOnly = true)
    public List<Card> list(long userId, long projectId, boolean characters) {
        scope.project(userId, projectId);
        return cards(userId, projectId, characters);
    }

    @Transactional(readOnly = true)
    public List<Card> global(long userId, boolean characters) { return cards(userId, null, characters); }

    private List<Card> cards(long userId, Long projectId, boolean characters) {
        String table = table(characters), column = column(characters);
        var rows = projectId == null
            ? db.fetch("select a.*, p.name as project_name from " + table + " a join project p on p.id=a.project_id where a.user_id=? and p.team_id is null order by a.project_id,a.id", userId)
            : db.fetch("select a.*, p.name as project_name from " + table + " a join project p on p.id=a.project_id where a.user_id=? and a.project_id=? order by a.id", userId, projectId);
        return rows.stream().map(row -> {
            long id = row.get("id", Long.class);
            var versions = db.fetch("select * from asset_version where " + column + "=? order by id", id);
            String selected = versions.stream().filter(v -> v.get("is_selected", Boolean.class)).map(v -> v.get("id", String.class)).findFirst().orElse(null);
            var usage = db.fetch("select distinct s.id, e.episode_index from asset_ref r join shot s on s.id=r.shot_id join episode e on e.id=s.episode_id where r." + column + "=? order by e.episode_index,s.id", id);
            var task = db.fetchOne("select id,status from task where user_id=? and project_id=? and type='ASSET_IMAGE' and target_type=? and target_id=? order by id desc limit 1",
                userId, row.get("project_id", Long.class), characters ? "CHARACTER" : "SCENE", id);
            return new Card(Long.toString(id), row.get("project_id", String.class), row.get("name", String.class), row.get("description", String.class),
                versions.stream().map(this::version).toList(), selected, row.get("project_name", String.class),
                new Usage(usage.size(), usage.getValues("episode_index", Integer.class).stream().distinct().sorted().toList()),
                task == null ? "idle" : state(task.get("status", String.class)), task == null ? null : task.get("id", String.class));
        }).toList();
    }

    public static String state(String status) {
        return switch (status) { case "QUEUED" -> "queued"; case "RUNNING" -> "running"; case "FAILED", "CANCELED" -> "failed"; case "UNKNOWN" -> "unknown"; default -> "idle"; };
    }

    @Transactional
    public Record lock(long userId, boolean character, long id) {
        String table = table(character);
        var parent = db.fetchOne("select project_id from " + table + " where id=? and user_id=?", id, userId);
        if (parent == null) throw ApiException.notFound();
        scope.lockProject(userId, parent.get("project_id", Long.class));
        var row = db.fetchOne("select a.*, p.visual_type, p.style from " + table + " a join project p on p.id=a.project_id where a.id=? and a.user_id=? for update of a", id, userId);
        if (row == null) throw ApiException.notFound();
        return row;
    }

    @Transactional
    public Version upload(long userId, boolean character, long id, String label, MultipartFile file) {
        var asset = lock(userId, character, id);
        String display = label == null || label.isBlank() ? "上传版本" : TextInput.name(label, "版本名称");
        var encoded = images.read(file);
        String prefix = "u/" + userId + "/p/" + asset.get("project_id", Long.class) + "/assets/" + (character ? "character" : "scene") + "/" + id + "/" + java.util.UUID.randomUUID().toString().replace("-", "");
        putFile(prefix, prefix + "/image." + encoded.extension(), encoded.bytes());
        return addVersion(userId, asset.get("project_id", Long.class), character, id, display, "uploaded", prefix + "/image." + encoded.extension(), encoded.mediaType(), null);
    }

    @Transactional
    public Version addVersion(long userId, long projectId, boolean character, long id, String label, String source, String key, String mediaType, Long taskId) {
        // All callers hold the project lock; the database partial indexes also enforce one selection.
        String column = column(character);
        db.execute("update asset_version set is_selected=false where " + column + "=? and is_selected", id);
        return version(db.fetchOne("insert into asset_version(user_id,project_id," + column + ",label,source,storage_key,media_type,task_id,is_selected) values (?,?,?,?,?,?,?,?,?) returning *",
            userId, projectId, id, label, source, key, mediaType, taskId, true));
    }

    @Transactional
    public void select(long userId, boolean character, long id, long versionId) {
        lock(userId, character, id);
        String column = column(character);
        if (db.fetchOne("select id from asset_version where id=? and " + column + "=?", versionId, id) == null) throw ApiException.notFound();
        db.execute("update asset_version set is_selected=false where " + column + "=? and is_selected", id);
        db.execute("update asset_version set is_selected=true where id=?", versionId);
    }

    @Transactional(readOnly = true)
    public VisualStyleService.Image image(long userId, long versionId) {
        var row = db.fetchOne("select v.storage_key,v.media_type from asset_version v join project p on p.id=v.project_id and p.user_id=v.user_id where v.id=? and v.user_id=?", versionId, userId);
        if (row == null) throw ApiException.notFound();
        var resource = storage.resource(row.get("storage_key", String.class));
        if (!resource.isReadable()) throw ApiException.notFound();
        return new VisualStyleService.Image(resource, row.get("media_type", String.class));
    }

    public void putFile(String prefix, String key, byte[] bytes) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) try { storage.deleteTree(prefix); } catch (IOException ignored) { }
            }
        });
        try { storage.put(key, bytes); }
        catch (IOException error) { throw new IllegalStateException("图片保存失败", error); }
    }

    private Version version(Record row) {
        String id = row.get("id", String.class);
        String url = "/api/asset-versions/" + id + "/image";
        return new Version(id, row.get("label", String.class), url,
            row.get("created_at", java.time.OffsetDateTime.class).toInstant().toEpochMilli(), row.get("source", String.class), url);
    }
    private static String table(boolean character) { return character ? "character_asset" : "scene_asset"; }
    private static String column(boolean character) { return character ? "character_id" : "scene_id"; }

    @Transactional
    public Binding bind(long userId, long projectId, long shotId, String sceneName, List<String> characterNames) {
        db.execute("delete from asset_ref where shot_id = ? and project_id = ?", shotId, projectId);
        String scene = "";
        if (!sceneName.isBlank()) {
            var asset = resolve(userId, projectId, sceneName, false);
            scene = asset.get("name", String.class);
            db.execute("insert into asset_ref(shot_id, project_id, role, scene_id) values (?, ?, 'SCENE', ?)",
                shotId, projectId, asset.get("id", Long.class));
        }
        var names = new LinkedHashSet<String>();
        for (String name : characterNames) {
            var asset = resolve(userId, projectId, name, true);
            names.add(asset.get("name", String.class));
            db.execute("""
                insert into asset_ref(shot_id, project_id, role, character_id) values (?, ?, 'CHARACTER', ?)
                on conflict (shot_id, character_id) where role = 'CHARACTER' do nothing
                """, shotId, projectId, asset.get("id", Long.class));
        }
        return new Binding(scene, List.copyOf(names));
    }

    private Record resolve(long userId, long projectId, String rawName, boolean character) {
        String name = Normalizer.normalize(TextInput.name(rawName, character ? "人物名称" : "场景名称"), Normalizer.Form.NFC);
        String table = character ? "character_asset" : "scene_asset";
        return db.fetchOne("insert into " + table + "(user_id, project_id, name) values (?, ?, ?) " +
            "on conflict (project_id, lower(name)) do update set name = " + table + ".name returning id, name", userId, projectId, name);
    }
}
