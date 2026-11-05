package com.vibestory.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

final class StoryboardTools {
    private record Field(String name, Map<String, Object> schema, boolean required) {}
    private static final Map<String, Object> ID = Map.of("type", "string", "pattern", "^[1-9][0-9]{0,17}$");
    private static final Field EPISODE = idField("episode_id", "VibeStory 分集 ID", true);
    private static final Field PROPOSAL = idField("proposal_id", "改镜预览返回的提案 ID", true);
    private static final Field REQUEST_KEY = new Field("request_key",
        Map.of("type", "string", "format", "uuid", "description", "调用方为本次操作生成的 UUID，重试时复用"), true);
    private static final Field OFFSET = new Field("offset", Map.of("type", "integer", "minimum", 0, "default", 0), false);
    private static final Field LIMIT = new Field("limit",
        Map.of("type", "integer", "minimum", 1, "maximum", 50, "default", 20), false);

    private final ObjectMapper json;
    private final WorkspaceApi api;

    StoryboardTools(ObjectMapper json, WorkspaceApi api) {
        this.json = json;
        this.api = api;
    }

    List<SyncToolSpecification> tools() {
        return List.of(
            tool("list_projects", "查找个人项目及分集 ID。提供 team_id 时查询该团队项目。", true, false, true,
                this::projects, idField("team_id", "可选的团队 ID", false),
                textField("query", "按项目名筛选", 120, false), OFFSET, LIMIT),
            tool("list_shots", "按镜号读取分镜，返回镜头 ID、描述、台词、景别、确认状态和当前选图。结果分页。",
                true, false, true, this::shots, EPISODE, OFFSET, LIMIT),
            tool("submit_breakdown", "将分集已保存的正文拆成镜头。保护已有镜头，返回异步任务 ID。", false, false, false,
                args -> {
                    String path = episode(args) + "/breakdowns";
                    api.requireMock("text");
                    return api.post(path, Map.of("replaceExisting", false));
                }, EPISODE),
            tool("get_task", "读取异步任务进度。ready 为 true 时产物已就绪，随后可读取分镜或批次。",
                true, false, true, args -> api.get("/api/tasks/" + id(args, "task_id")),
                idField("task_id", "生成接口返回的任务 ID", true)),
            tool("preview_shot_edit", "为个人项目预览镜头景别或台词缩短的改动。返回差异和提案 ID。",
                false, false, true, args -> api.post(episode(args) + "/shot-edit-proposals",
                    Map.of("requestKey", requestKey(args), "instruction", string(args, "instruction", 500))),
                EPISODE, REQUEST_KEY, textField("instruction", "例如 把第 3 镜改成近景，台词短一点", 500, true)),
            tool("apply_shot_edit", "用户确认差异后保存同一分集的提案。镜头版本已变化时请重新预览。",
                false, true, true, args -> {
                    String path = proposal(args) + "/apply";
                    api.requireMock("image");
                    return api.post(path, null);
                }, EPISODE, PROPOSAL),
            tool("discard_shot_edit", "放弃指定的改镜提案。", false, true, true,
                args -> api.post(proposal(args) + "/discard", null), EPISODE, PROPOSAL),
            tool("confirm_all_shots", "用户明确确认整集时，将该分集的全部镜头标为已确认。",
                false, false, true, args -> {
                    var result = api.post(episode(args) + "/shots/confirm-all", null);
                    return json.createObjectNode().put("confirmed_count", result.size());
                }, EPISODE),
            tool("submit_image_batch", "为明确指定且已确认的镜头提交分镜图批次。结果记录每个成员的受理、复用和阻塞状态。",
                false, false, true, args -> {
                    String path = episode(args) + "/generation-batches";
                    var payload = Map.of("type", "IMAGE", "requestKey", requestKey(args), "shotIds", shotIds(args),
                        "regenerate", false);
                    api.requireMock("image");
                    return api.post(path, payload);
                }, EPISODE, REQUEST_KEY, new Field("shot_ids", Map.of("type", "array", "items", ID,
                    "minItems", 1, "maxItems", 200, "uniqueItems", true), true)),
            tool("get_image_batch", "按批次 ID 查询分镜图批次进度和成员。", true, false, true,
                args -> api.get("/api/generation-batches/" + id(args, "batch_id")),
                idField("batch_id", "批次提交返回的 ID", true))
        );
    }

    private JsonNode projects(Map<String, Object> args) {
        String query = args.containsKey("query") ? string(args, "query", 120).strip() : "";
        String path = args.containsKey("team_id") ? "/api/teams/" + id(args, "team_id") + "/projects"
            : "/api/projects?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8);
        var selected = json.createArrayNode();
        for (var project : api.get(path)) {
            if (!project.path("name").asText().toLowerCase(java.util.Locale.ROOT).contains(query.toLowerCase(java.util.Locale.ROOT))) continue;
            var item = pick(project, "id", "name", "teamId");
            var episodes = item.putArray("episodes");
            for (var episode : project.path("episodes")) episodes.add(pick(episode, "id", "index", "title", "breakdown"));
            selected.add(item);
        }
        return page("projects", selected, args, Function.identity());
    }

    private JsonNode shots(Map<String, Object> args) {
        return page("shots", api.get(episode(args) + "/shots"), args, shot ->
            pick(shot, "id", "seq", "status", "sceneName", "description", "dialogue", "shotSize", "cameraMove",
                "durationMs", "version", "imageState", "selectedImageId"));
    }

    private JsonNode page(String key, JsonNode values, Map<String, Object> args, Function<JsonNode, JsonNode> view) {
        int offset = integer(args, "offset", 0, 0, Integer.MAX_VALUE);
        int limit = integer(args, "limit", 20, 1, 50);
        int end = (int) Math.min(values.size(), (long) offset + limit);
        var result = json.createObjectNode().put("total", values.size()).put("offset", offset);
        if (end < values.size()) result.put("next_offset", end);
        ArrayNode items = result.putArray(key);
        for (int index = offset; index < end; index++) items.add(view.apply(values.get(index)));
        return result;
    }

    private ObjectNode pick(JsonNode row, String... keys) {
        var result = json.createObjectNode();
        for (String key : keys) if (row.has(key)) result.set(key, row.get(key));
        return result;
    }

    private SyncToolSpecification tool(String name, String description, boolean readOnly, boolean destructive, boolean idempotent,
                                       Function<Map<String, Object>, JsonNode> handler, Field... fields) {
        var properties = new LinkedHashMap<String, Object>();
        for (var field : fields) properties.put(field.name(), field.schema());
        var required = java.util.Arrays.stream(fields).filter(Field::required).map(Field::name).toList();
        var schema = Map.<String, Object>of("type", "object", "properties", properties,
            "required", required, "additionalProperties", false);
        var annotations = ToolAnnotations.builder().readOnlyHint(readOnly).destructiveHint(destructive)
            .idempotentHint(idempotent).openWorldHint(false).build();
        return SyncToolSpecification.builder()
            .tool(Tool.builder(name, schema).description(description).annotations(annotations).build())
            .callHandler((exchange, request) -> {
                var args = request.arguments() == null ? Map.<String, Object>of() : request.arguments();
                if (!properties.keySet().containsAll(args.keySet()))
                    return error(400, "请求包含未声明的参数。");
                try {
                    JsonNode data = handler.apply(args);
                    return CallToolResult.builder().addTextContent(data.toString())
                        .structuredContent(data).build();
                } catch (WorkspaceApi.Failure failure) {
                    return error(failure.status(), failure.getMessage());
                } catch (IllegalArgumentException invalid) {
                    return error(400, invalid.getMessage());
                }
            }).build();
    }

    private CallToolResult error(int status, String message) {
        var data = json.createObjectNode().put("status", status).put("message", message);
        return CallToolResult.builder().isError(true).addTextContent(data.toString())
            .structuredContent(data).build();
    }

    private static Field idField(String name, String description, boolean required) {
        return new Field(name, Map.of("type", "string", "pattern", ID.get("pattern"), "description", description), required);
    }

    private static Field textField(String name, String description, int max, boolean required) {
        return new Field(name, Map.of("type", "string", "maxLength", max, "description", description), required);
    }

    private static String episode(Map<String, Object> args) { return "/api/episodes/" + id(args, "episode_id"); }
    private static String proposal(Map<String, Object> args) {
        return episode(args) + "/shot-edit-proposals/" + id(args, "proposal_id");
    }

    private static String id(Map<String, Object> args, String name) { return idValue(args.get(name), name); }
    private static String idValue(Object value, String name) {
        if (value instanceof String id && id.matches((String) ID.get("pattern"))) return id;
        throw new IllegalArgumentException(name + " 必须是正整数 ID 字符串。");
    }

    private static String string(Map<String, Object> args, String name, int max) {
        if (args.get(name) instanceof String value && !value.isBlank() && value.length() <= max) return value;
        throw new IllegalArgumentException(name + " 必须是长度不超过 " + max + " 的非空文本。");
    }

    private static int integer(Map<String, Object> args, String name, int fallback, int min, int max) {
        if (!args.containsKey(name)) return fallback;
        if (args.get(name) instanceof Integer value && value >= min && value <= max) return value;
        throw new IllegalArgumentException(name + " 超出整数范围。");
    }

    private static String requestKey(Map<String, Object> args) {
        String raw = string(args, "request_key", 36);
        try {
            var id = UUID.fromString(raw);
            if (id.toString().equalsIgnoreCase(raw)) return id.toString();
        } catch (IllegalArgumentException ignored) {
            // Return a field-specific error without reflecting the supplied value.
        }
        throw new IllegalArgumentException("request_key 必须是 UUID。");
    }

    private static List<String> shotIds(Map<String, Object> args) {
        if (!(args.get("shot_ids") instanceof List<?> values) || values.isEmpty() || values.size() > 200)
            throw new IllegalArgumentException("shot_ids 必须包含 1 到 200 个镜头 ID。");
        var ids = values.stream().map(value -> idValue(value, "shot_ids")).toList();
        if (ids.stream().distinct().count() != ids.size()) throw new IllegalArgumentException("shot_ids 中的镜头 ID 必须不同。");
        return ids;
    }
}
