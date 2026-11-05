package com.vibestory.agent;

import com.vibestory.common.ApiException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** Files are the source of truth. The database only stores immutable per-run snapshots. */
@Component
public class SkillDirectory {
    @io.swagger.v3.oas.annotations.media.Schema(name="SkillDocument")
    public record Skill(String id, String description, List<String> roles, String content,
                        String document, String version, String location, boolean personal) {}
    @io.swagger.v3.oas.annotations.media.Schema(name="SkillEntry")
    public record Entry(String id, String description, List<String> roles, String version, String location, boolean personal) {}
    private static final Set<String> ROLES = Set.of("MAIN","SCRIPT","STORYBOARD");
    private static final int MAX_BYTES = 64 * 1024;
    private static final ConcurrentHashMap<Path, ReentrantLock> LOCKS = new ConcurrentHashMap<>();
    private final Path root;

    public SkillDirectory(@Value("${vibestory.skills.root:./skills}") String root) {
        this.root = Path.of(root).toAbsolutePath().normalize();
        if (!Files.isDirectory(this.root)) throw new IllegalStateException("Skill 根目录不存在，请配置 SKILLS_ROOT");
    }

    public List<Skill> catalog(long user, String role) {
        if (role != null && !ROLES.contains(role)) throw ApiException.badRequest("未知 Agent 角色");
        var byId = new LinkedHashMap<String,Skill>();
        scan(root, false, byId);
        Path personal = root.resolve("users").resolve(Long.toString(user));
        safe(personal);
        if (Files.isDirectory(personal, LinkOption.NOFOLLOW_LINKS)) scan(personal, true, byId);
        if (byId.size() > 64) throw ApiException.badRequest("可见 Skill 超过 64 项，请整理目录");
        return byId.values().stream().filter(s -> role == null || s.roles().contains(role)).sorted(java.util.Comparator.comparing(Skill::id)).toList();
    }

    public List<Entry> list(long user, String role) { return catalog(user, role).stream().map(this::entry).toList(); }
    public Skill read(long user, String id) {
        name(id);
        return catalog(user, null).stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow(ApiException::notFound);
    }
    public Entry entry(Skill s) { return new Entry(s.id(),s.description(),s.roles(),s.version(),s.location(),s.personal()); }

    public Skill save(long user, String id, String expectedVersion, String document) {
        name(id);
        Path directory = root.resolve("users").resolve(Long.toString(user)).resolve(id);
        Path target = directory.resolve("SKILL.md");
        parse(id,document,target,true);
        safe(directory);
        var mutex = LOCKS.computeIfAbsent(target,p -> new ReentrantLock());
        mutex.lock();
        Path temporary = null;
        try {
            Files.createDirectories(directory);
            safe(directory);
            try (var channel = FileChannel.open(directory.resolve(".write.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS);
                 var ignored = channel.lock()) {
                var available = catalog(user,null);
                Skill current = available.stream().filter(s -> s.id().equals(id)).findFirst().orElse(null);
                if (current == null && available.size() >= 64) throw ApiException.badRequest("可见 Skill 已达 64 项，请先整理目录");
                // A tool replay after the file commit must not fail or create a second asset.
                if (current != null && current.personal() && current.document().equals(document)) return current;
                if (!(current == null ? "" : current.version()).equals(expectedVersion)) throw ApiException.conflict("Skill 已变化，请读取最新版本后合并修改");
                temporary = Files.createTempFile(directory,".skill-",".tmp");
                try (var output = FileChannel.open(temporary,StandardOpenOption.WRITE)) {
                    var bytes = ByteBuffer.wrap(document.getBytes(StandardCharsets.UTF_8));
                    while (bytes.hasRemaining()) output.write(bytes);
                    output.force(true);
                }
                Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
                return read(user,id);
            }
        } catch (IOException invalid) { throw ApiException.conflict("Skill 文件写入失败，请检查目录状态后重试"); }
        finally {
            if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
            mutex.unlock();
        }
    }

    private void scan(Path parent, boolean personal, Map<String,Skill> skills) {
        safe(parent);
        try (var files = Files.list(parent)) {
            for (var directory : files.sorted().toList()) {
                String id = directory.getFileName().toString();
                if (id.startsWith(".") || id.equals("users") || !Files.isDirectory(directory,LinkOption.NOFOLLOW_LINKS)) continue;
                Path file = directory.resolve("SKILL.md");
                if (!Files.exists(file,LinkOption.NOFOLLOW_LINKS)) continue;
                safe(file);
                if (Files.size(file) > MAX_BYTES) throw ApiException.badRequest("Skill 文件超过 64 KiB：" + id);
                byte[] bytes = Files.readAllBytes(file);
                String document = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
                skills.put(id,parse(id,document,file,personal));
            }
        } catch (IOException invalid) { throw ApiException.conflict("Skill 目录无法读取"); }
    }

    private Skill parse(String id, String document, Path file, boolean personal) {
        name(id);
        if (document == null || document.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES)
            throw ApiException.badRequest("Skill 文件须为 UTF-8，最多 64 KiB");
        String normalized = document.replace("\r\n","\n");
        if (!normalized.startsWith("---\n")) throw ApiException.badRequest("Skill 缺少 YAML frontmatter：" + id);
        int end = normalized.indexOf("\n---\n",4);
        if (end < 0) throw ApiException.badRequest("Skill frontmatter 未闭合：" + id);
        var options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(0);
        options.setCodePointLimit(MAX_BYTES);
        Object loaded;
        try { loaded = new Yaml(new SafeConstructor(options)).load(normalized.substring(4,end)); }
        catch (RuntimeException invalid) { throw ApiException.badRequest("Skill 元数据 YAML 无效：" + id); }
        if (!(loaded instanceof Map<?,?> metadata) || !(metadata.get("name") instanceof String declared) || !declared.equals(id)
            || !(metadata.get("description") instanceof String description) || description.isBlank() || description.length() > 300
            || !(metadata.get("roles") instanceof List<?> roles) || roles.isEmpty() || !roles.stream().allMatch(r -> r instanceof String && ROLES.contains(r)))
            throw ApiException.badRequest("Skill 的 name、description 或 roles 无效：" + id);
        String content = normalized.substring(end+5).strip();
        if (content.isBlank()) throw ApiException.badRequest("Skill 正文不能为空：" + id);
        var roleNames = roles.stream().map(Object::toString).distinct().sorted().toList();
        try {
            String version = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(document.getBytes(StandardCharsets.UTF_8)));
            return new Skill(id,description,roleNames,content,document,version,root.relativize(file).toString().replace('\\','/'),personal);
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private void safe(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(root)) throw ApiException.badRequest("Skill 路径越界");
        Path current = root;
        if (Files.isSymbolicLink(current)) throw ApiException.badRequest("Skill 根目录不能是符号链接");
        for (Path part : root.relativize(normalized)) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) throw ApiException.badRequest("Skill 路径不能包含符号链接");
        }
    }
    private void name(String id) {
        if (id == null || !id.matches("[a-z][a-z0-9]*(?:-[a-z0-9]+)*") || id.length() > 64 || id.equals("users"))
            throw ApiException.badRequest("Skill 名称须为小写字母、数字和连字符，最长 64 字符");
    }
}
