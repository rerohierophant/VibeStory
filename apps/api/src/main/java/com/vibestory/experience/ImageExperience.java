package com.vibestory.experience;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vibestory.common.ApiException;
import com.vibestory.common.ScopeGuard;
import com.vibestory.common.TextInput;
import com.vibestory.provider.ImageProvider;
import com.vibestory.provider.ImageProviders;
import com.vibestory.task.InputSignature;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** User-confirmed image rules and immutable recipe editions. Raw corrections remain separate evidence. */
@Service
public class ImageExperience {
    public enum TargetType { SHOT, CHARACTER, SCENE }
    public enum Scope { TARGET, PROJECT, USER }
    public enum Decision { ACCEPTED, REJECTED }
    @Schema(name = "ImagePreference")
    public record Preference(UUID id, Scope scope, String rule, boolean enabled, int version, UUID originId) {}
    @Schema(name = "ImageRecipeVersion")
    public record RecipeVersion(UUID id, int version, String template, List<String> slots, UUID originId) {}
    @Schema(name = "ImageRecipe")
    public record Recipe(UUID id, String name, TargetType targetType, int version, List<RecipeVersion> versions,
                         List<String> steps) {}
    @Schema(name = "ImageCreation")
    public record Creation(UUID id, String instruction, String decision, String taskId, String taskState,
                           String error, String sourceTaskId, String sourceUrl, String resultUrl,
                           String prompt, ImageProvider.Learning learning, OffsetDateTime createdAt) {}
    @Schema(name = "ImageExample")
    public record Example(String taskId, String url, String prompt, boolean selected) {}
    @Schema(name = "ImageExperienceContext")
    public record Context(TargetType targetType, String targetId, String name, List<Example> examples,
                          List<Creation> creations, List<Preference> preferences, List<Recipe> recipes,
                          boolean referenceUploadSupported) {}
    @Schema(name = "ImagePreferenceRemember")
    public record Remember(@NotNull Scope scope, @NotBlank String rule) {}
    @Schema(name = "ImagePreferenceEdit")
    public record EditPreference(int expectedVersion, @NotBlank String rule, boolean enabled) {}
    @Schema(name = "ImageRecipeSave")
    public record SaveRecipe(@NotNull UUID requestKey, UUID recipeId, int expectedVersion,
                             @NotNull TargetType targetType, @NotBlank String name, @NotBlank String template, UUID originId) {}

    private static final Pattern SLOT = Pattern.compile("\\{\\{([a-z][a-z0-9_]{0,31})}}");
    private final DSLContext db;
    private final ScopeGuard scope;
    private final ObjectMapper json;
    private final InputSignature signatures;
    private final ImageProviders providers;

    public ImageExperience(DSLContext db, ScopeGuard scope, ObjectMapper json, InputSignature signatures, ImageProviders providers) {
        this.db = db;
        this.scope = scope;
        this.json = json;
        this.signatures = signatures;
        this.providers = providers;
    }

    public Record target(long user, TargetType type, long id) {
        String table = switch (type) { case SHOT -> "shot"; case CHARACTER -> "character_asset"; case SCENE -> "scene_asset"; };
        var row = db.fetchOne("select id,project_id from " + table + " where id=? and user_id=?", id, user);
        if (row == null) throw ApiException.notFound();
        scope.personalProject(user, row.get("project_id", Long.class));
        return row;
    }

    @Transactional(readOnly = true)
    public Context context(long user, TargetType type, long id) {
        var target = target(user, type, id);
        long project = target.get("project_id", Long.class);
        String name = type == TargetType.SHOT ? "第 " + db.fetchOne("select seq from shot where id=?", id).get(0) + " 镜"
            : db.fetchOne("select name from " + (type == TargetType.CHARACTER ? "character_asset" : "scene_asset") + " where id=?", id).get(0, String.class);
        var examples = db.fetch("""
            select t.id,t.input_snapshot from task t where t.user_id=? and t.project_id=? and t.target_type=? and t.target_id=?
            and t.type in ('IMAGE','ASSET_IMAGE') and t.status='SUCCEEDED'
            and not exists (select 1 from asset_version v where v.task_id=t.id and not v.visible_in_library)
            order by t.id desc limit 20
            """, user, project, type.name(), id).map(row -> {
                long task = row.get("id", Long.class);
                var input = input(row.get("input_snapshot", String.class));
                return new Example(Long.toString(task), imageUrl(task), input.prompt(), selected(task, type));
            });
        var creations = db.fetch("select * from image_creation where user_id=? and target_type=? and target_id=? order by created_at desc limit 20",
            user, type.name(), id).map(this::creation);
        return new Context(type, Long.toString(id), name, examples, creations, preferences(user, project, type, id), recipes(user, type),
            providers.supportsAssetReference(type.name()));
    }

    public List<Preference> preferences(long user, long project, TargetType type, long target) {
        return db.fetch("""
            select * from image_preference where user_id=? and target_type=?
            and (scope='USER' or project_id=? and (scope='PROJECT' or target_id=?))
            order by case scope when 'USER' then 0 when 'PROJECT' then 1 else 2 end,created_at,id
            """, user, type.name(), project, target).map(this::preference);
    }

    public ImageProvider.Learning freeze(long user, long project, TargetType type, long target, String basePrompt,
                                         ImageProvider.RecipeUse recipe, String source, UUID feedback) {
        // Team media uses an owner partition key. Personal preferences must never leak into that partition.
        if (!db.fetchExists(db.selectOne().from("project").where("id=? and user_id=? and team_id is null", project, user))) return null;
        var rules = preferences(user, project, type, target).stream().filter(Preference::enabled)
            .map(p -> new ImageProvider.PreferenceUse(p.id().toString(), p.version(), p.scope().name(), p.rule())).toList();
        if (rules.isEmpty() && recipe == null && feedback == null) return null;
        return new ImageProvider.Learning(basePrompt, rules, recipe, source, feedback == null ? null : feedback.toString());
    }

    public String render(String base, ImageProvider.Learning learning) {
        if (learning == null) return base;
        int current = base.lastIndexOf("\n本轮用户要求：");
        var prompt = new StringBuilder(current < 0 ? base : base.substring(0, current));
        for (var preference : learning.preferences()) prompt.append("\n用户已确认的画面偏好（").append(preference.scope()).append("）：").append(preference.rule());
        if (learning.recipe() != null) prompt.append("\n本次创作配方：").append(learning.recipe().rendered());
        if (current >= 0) prompt.append(base.substring(current));
        return prompt.toString();
    }

    @Transactional
    public Preference remember(long user, UUID creationId, Remember request) {
        var origin = readAccepted(user, creationId, false);
        var type = TargetType.valueOf(origin.get("target_type", String.class));
        long project = origin.get("project_id", Long.class);
        long target = origin.get("target_id", Long.class);
        scope.lockProject(user, project);
        scope.personalProject(user, project);
        lockUser(user);
        readAccepted(user, creationId, true);
        String rule = rule(request.rule());
        var prior = db.fetchOne("select * from image_preference where user_id=? and origin_id=? and scope=?", user, creationId, request.scope().name());
        if (prior != null) {
            if (!prior.get("rule", String.class).equals(rule)) throw ApiException.conflict("该纠正已保存为偏好，请编辑原偏好");
            return preference(prior);
        }
        Long boundProject = request.scope() == Scope.USER ? null : project;
        Long boundTarget = request.scope() == Scope.TARGET ? target : null;
        checkPreferenceLimit(user, type, request.scope(), boundProject, boundTarget);
        return preference(db.fetchOne("""
            insert into image_preference(id,user_id,project_id,target_type,target_id,scope,origin_id,rule)
            values (?,?,?,?,?,?,?,?) returning *
            """, UUID.randomUUID(), user, boundProject, type.name(), boundTarget, request.scope().name(), creationId, rule));
    }

    @Transactional
    public Preference editPreference(long user, UUID id, EditPreference request) {
        lockUser(user);
        var row = db.fetchOne("select * from image_preference where id=? and user_id=? for update", id, user);
        if (row == null) throw ApiException.notFound();
        if (row.get("project_id") != null) scope.personalProject(user, row.get("project_id", Long.class));
        String rule = rule(request.rule());
        if (row.get("rule", String.class).equals(rule) && row.get("enabled", Boolean.class) == request.enabled()) return preference(row);
        if (row.get("version", Integer.class) != request.expectedVersion()) throw ApiException.conflict("偏好已变化，请重新读取后编辑");
        if (request.enabled() && !row.get("enabled", Boolean.class)) checkPreferenceLimit(user, TargetType.valueOf(row.get("target_type", String.class)),
            Scope.valueOf(row.get("scope", String.class)), row.get("project_id", Long.class), row.get("target_id", Long.class));
        return preference(db.fetchOne("update image_preference set rule=?,enabled=?,version=version+1 where id=? returning *", rule, request.enabled(), id));
    }

    @Transactional(readOnly = true)
    public List<Recipe> recipes(long user, TargetType type) {
        return db.fetch("select id from image_recipe where user_id=? and target_type=? order by created_at desc", user, type.name())
            .map(row -> recipe(user, row.get(0, UUID.class)));
    }

    @Transactional(readOnly = true)
    public List<Recipe> recipes(long user) {
        return db.fetch("select id from image_recipe where user_id=? order by created_at desc, id", user)
            .map(row -> recipe(user, row.get(0, UUID.class)));
    }

    @Transactional
    public Recipe saveRecipe(long user, SaveRecipe request) {
        lockUser(user);
        String name = TextInput.name(request.name(), "配方名称");
        String template = rule(request.template());
        slots(template);
        String signature = signatures.of(request).signature();
        var replay = db.fetchOne("select recipe_id,number,request_signature from image_recipe_version where user_id=? and request_key=?", user, request.requestKey());
        if (replay != null) {
            checkSignature(replay.get("request_signature", String.class), signature);
            var saved = recipe(user, replay.get("recipe_id", UUID.class));
            int version = replay.get("number", Integer.class);
            return new Recipe(saved.id(), saved.name(), saved.targetType(), version,
                saved.versions().stream().filter(v -> v.version() <= version).toList(), saved.steps());
        }
        if (request.originId() != null) {
            var origin = readAccepted(user, request.originId(), true);
            scope.personalProject(user, origin.get("project_id", Long.class));
            if (!origin.get("target_type", String.class).equals(request.targetType().name())) throw ApiException.badRequest("配方素材类型与纠正来源不一致");
        }
        UUID id = request.recipeId();
        if (id == null) {
            if (request.expectedVersion() != 0) throw ApiException.conflict("新配方的基准版本应为 0");
            if (db.fetchCount(db.selectOne().from("image_recipe").where("user_id=?", user)) >= 40) throw ApiException.conflict("最多保留 40 个创作配方");
            if (db.fetchExists(db.selectOne().from("image_recipe").where("user_id=? and target_type=? and name=?", user, request.targetType().name(), name)))
                throw ApiException.conflict("同类型已有同名配方，请编辑原配方");
            id = UUID.randomUUID();
            db.execute("insert into image_recipe(id,user_id,target_type,name) values (?,?,?,?)", id, user, request.targetType().name(), name);
        }
        var current = db.fetchOne("select * from image_recipe where id=? and user_id=? for update", id, user);
        if (current == null) throw ApiException.notFound();
        if (current.get("version", Integer.class) != request.expectedVersion()) throw ApiException.conflict("配方已更新，请基于最新版本编辑");
        if (!current.get("target_type", String.class).equals(request.targetType().name()) || !current.get("name", String.class).equals(name))
            throw ApiException.badRequest("配方名称和素材类型在版本间保持一致");
        int version = request.expectedVersion() + 1;
        db.execute("""
            insert into image_recipe_version(id,recipe_id,user_id,number,template,origin_id,request_key,request_signature)
            values (?,?,?,?,?,?,?,?)
            """, UUID.randomUUID(), id, user, version, template, request.originId(), request.requestKey(), signature);
        db.execute("update image_recipe set version=? where id=?", version, id);
        return recipe(user, id);
    }

    public ImageProvider.RecipeUse useRecipe(long user, TargetType type, UUID versionId, Map<String, String> parameters) {
        if (versionId == null) {
            if (!parameters.isEmpty()) throw ApiException.badRequest("填写参数前请选择配方版本");
            return null;
        }
        var row = db.fetchOne("""
            select v.*,r.name,r.target_type from image_recipe_version v join image_recipe r on r.id=v.recipe_id
            where v.id=? and v.user_id=? and r.user_id=? and r.target_type=?
            """, versionId, user, user, type.name());
        if (row == null) throw ApiException.notFound();
        String template = row.get("template", String.class);
        if (!parameters.keySet().equals(new LinkedHashSet<>(slots(template)))) throw ApiException.badRequest("请完整填写配方槽位，且不包含额外参数");
        var matcher = SLOT.matcher(template);
        var rendered = new StringBuilder();
        while (matcher.find()) matcher.appendReplacement(rendered, java.util.regex.Matcher.quoteReplacement(rule(parameters.get(matcher.group(1)))));
        matcher.appendTail(rendered);
        return new ImageProvider.RecipeUse(row.get("recipe_id", String.class), versionId.toString(), row.get("number", Integer.class),
            row.get("name", String.class), template, Map.copyOf(parameters), rendered.toString());
    }

    public Creation creation(long user, UUID id) {
        var row = db.fetchOne("select * from image_creation where id=? and user_id=?", id, user);
        if (row == null) throw ApiException.notFound();
        scope.personalProject(user, row.get("project_id", Long.class));
        return creation(row);
    }

    @Transactional
    public Creation decide(long user, UUID id, Decision decision) {
        var row = db.fetchOne("select * from image_creation where id=? and user_id=? for update", id, user);
        if (row == null) throw ApiException.notFound();
        scope.personalProject(user, row.get("project_id", Long.class));
        String previous = row.get("decision", String.class);
        if (previous.equals(decision.name())) return creation(row);
        if (!previous.equals("PENDING")) throw ApiException.conflict("该结果已评价，不能覆盖原评价");
        var task = db.fetchOne("select status from task where id=? and user_id=?", row.get("result_task_id"), user);
        if (task == null || !task.get(0, String.class).equals("SUCCEEDED") || imageUrl(row.get("result_task_id", Long.class)) == null)
            throw ApiException.conflict("生成结果就绪后才能评价");
        return creation(db.fetchOne("update image_creation set decision=?,decided_at=clock_timestamp() where id=? returning *", decision.name(), id));
    }

    public void lockUser(long user) { db.fetchOne("select id from app_user where id=? for update", user); }

    public void checkSignature(String stored, String actual) {
        if (!stored.equals(actual)) throw ApiException.conflict("同一请求标识不能用于不同创作输入");
    }

    private Record readAccepted(long user, UUID id, boolean lock) {
        var row = db.fetchOne("select * from image_creation where id=? and user_id=? and decision='ACCEPTED'"
            + (lock ? " for key share" : ""), id, user);
        if (row == null) throw ApiException.badRequest("请先明确确认满意的生成结果");
        return row;
    }

    private Recipe recipe(long user, UUID id) {
        var row = db.fetchOne("select * from image_recipe where id=? and user_id=?", id, user);
        if (row == null) throw ApiException.notFound();
        var versions = db.fetch("select * from image_recipe_version where recipe_id=? order by number desc", id).map(v ->
            new RecipeVersion(v.get("id", UUID.class), v.get("number", Integer.class), v.get("template", String.class),
                slots(v.get("template", String.class)), v.get("origin_id", UUID.class)));
        return new Recipe(id, row.get("name", String.class), TargetType.valueOf(row.get("target_type", String.class)),
            row.get("version", Integer.class), versions, List.of("绑定当前素材", "填写参数并生成候选", "用户审阅结果"));
    }

    private Preference preference(Record row) {
        return new Preference(row.get("id", UUID.class), Scope.valueOf(row.get("scope", String.class)), row.get("rule", String.class),
            row.get("enabled", Boolean.class), row.get("version", Integer.class), row.get("origin_id", UUID.class));
    }

    private Creation creation(Record row) {
        Long result = row.get("result_task_id", Long.class);
        var task = result == null ? null : db.fetchOne("select status,error_message,input_snapshot from task where id=?", result);
        var input = task == null ? null : input(task.get("input_snapshot", String.class));
        Long source = row.get("source_task_id", Long.class);
        return new Creation(row.get("id", UUID.class), row.get("instruction", String.class), row.get("decision", String.class),
            Objects.toString(result, null), task == null ? "MISSING" : task.get("status", String.class),
            task == null ? null : task.get("error_message", String.class), Objects.toString(source, null), source == null ? null : imageUrl(source),
            result == null ? null : imageUrl(result), input == null ? null : input.prompt(), input == null ? null : input.learning(), row.get("created_at", OffsetDateTime.class));
    }

    private String imageUrl(long task) {
        var shot = db.fetchOne("select id from shot_image where task_id=? and status='READY'", task);
        if (shot != null) return "/api/shot-images/" + shot.get(0) + "/image";
        var asset = db.fetchOne("select id from asset_version where task_id=?", task);
        return asset == null ? null : "/api/asset-versions/" + asset.get(0) + "/image";
    }

    private boolean selected(long task, TargetType type) {
        return type == TargetType.SHOT
            ? db.fetchExists(db.selectOne().from("shot_image").where("task_id=? and selected", task))
            : db.fetchExists(db.selectOne().from("asset_version").where("task_id=? and is_selected", task));
    }

    private ImageProvider.Input input(String value) {
        try { return json.readValue(value, ImageProvider.Input.class); }
        catch (java.io.IOException invalid) { throw new IllegalStateException("图片输入快照无效", invalid); }
    }

    private String rule(String value) {
        if (value == null || value.isBlank()) throw ApiException.badRequest("请填写创作要求");
        TextInput.validate(value, 1000, "创作要求");
        return value.strip();
    }

    private List<String> slots(String template) {
        var result = new LinkedHashSet<String>();
        SLOT.matcher(template).results().forEach(match -> result.add(match.group(1)));
        String remainder = SLOT.matcher(template).replaceAll("");
        if (remainder.contains("{{") || remainder.contains("}}") || result.size() > 8)
            throw ApiException.badRequest("最多 8 个槽位，格式为 {{lighting}}，名称使用小写字母、数字和下划线");
        return List.copyOf(result);
    }

    private void checkPreferenceLimit(long user, TargetType type, Scope scope, Long project, Long target) {
        int count = db.fetchOne("""
            select count(*) from image_preference where user_id=? and target_type=? and scope=? and enabled
            and project_id is not distinct from cast(? as bigint) and target_id is not distinct from cast(? as bigint)
            """, user, type.name(), scope.name(), project, target).get(0, Integer.class);
        if (count >= 6) throw ApiException.conflict("该范围最多启用 6 条偏好，请先合并或停用旧偏好");
    }
}
