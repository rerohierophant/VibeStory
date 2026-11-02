package com.vibestory.storage;

import java.io.IOException;
import java.nio.file.*;
import java.util.Comparator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

@Component
public class LocalStorage {
    private final Path root;
    public LocalStorage(@Value("${vibestory.storage.root}") String root) {
        this.root = Path.of(root).toAbsolutePath().normalize();
    }

    public Path path(String key) {
        Path path = root.resolve(key).normalize();
        if (!path.startsWith(root) || path.equals(root)) throw new IllegalArgumentException("无效的存储路径");
        return path;
    }

    public Path temporary(String key) throws IOException {
        Path target = path(key);
        Files.createDirectories(target.getParent());
        return Files.createTempFile(target.getParent(), "mock-", ".tmp");
    }

    public void publish(Path temporary, String key) throws IOException {
        Files.move(temporary, path(key), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    public Resource resource(String key) { return new FileSystemResource(path(key)); }

    public void put(String key, byte[] bytes) throws IOException {
        Path temporary = temporary(key);
        try { Files.write(temporary, bytes); publish(temporary, key); }
        finally { Files.deleteIfExists(temporary); }
    }

    public void deleteTree(String prefix) throws IOException {
        Path directory = path(prefix);
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return;
        // Files.walk does not follow symlinks; deleting p/1 cannot touch p/10.
        try (var paths = Files.walk(directory)) {
            for (Path entry : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(entry);
        }
    }
}
