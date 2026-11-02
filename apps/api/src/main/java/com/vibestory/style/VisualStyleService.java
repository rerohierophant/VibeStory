package com.vibestory.style;

import com.vibestory.common.ApiException;
import com.vibestory.common.TextInput;
import com.vibestory.storage.LocalStorage;
import com.vibestory.storage.StorageCleanup;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.jooq.DSLContext;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

@Service
public class VisualStyleService {
    private final VisualCatalog catalog;
    private final CustomStyleRepository styles;
    private final ReferenceImage images;
    private final LocalStorage storage;
    private final StorageCleanup cleanup;
    private final DSLContext db;

    public VisualStyleService(VisualCatalog catalog, CustomStyleRepository styles, ReferenceImage images,
                              LocalStorage storage, StorageCleanup cleanup, DSLContext db) {
        this.catalog = catalog;
        this.styles = styles;
        this.images = images;
        this.storage = storage;
        this.cleanup = cleanup;
        this.db = db;
    }

    @Transactional(readOnly = true)
    public List<VisualPreset> list(long userId) {
        var result = new ArrayList<>(catalog.list());
        styles.findByUserIdAndDeletedAtIsNullOrderByCreatedAt(userId).stream().map(CustomStyle::view).forEach(result::add);
        return List.copyOf(result);
    }

    @Transactional(readOnly = true)
    public VisualPreset resolve(long userId, String id) {
        return catalog.find(id).orElseGet(() -> readable(userId, id).view());
    }

    @Transactional
    public void validateSelection(long userId, String id) {
        if (catalog.find(id).isPresent()) return;
        if (id == null || !id.startsWith("custom_")) throw ApiException.badRequest("请选择有效的画面风格 ID");
        var style = styles.lock(userId, id).orElseThrow(ApiException::notFound);
        if (style.archived()) throw ApiException.notFound();
    }

    @Transactional
    public void changeSelection(long userId, String previous, String next) {
        // Two projects can swap styles concurrently; always lock their catalog entries in the same order.
        Stream.of(previous, next).filter(id -> id != null && id.startsWith("custom_")).distinct().sorted()
            .forEach(id -> styles.lock(userId, id).orElseThrow(ApiException::notFound));
        validateSelection(userId, next);
    }

    @Transactional
    public VisualPreset create(long userId, String label, MultipartFile file) {
        String name = TextInput.name(label, "风格名称");
        var encoded = images.read(file);
        String id = "custom_" + UUID.randomUUID().toString().replace("-", "");
        String prefix = "u/" + userId + "/styles/" + id;
        String key = prefix + "/reference." + encoded.extension();
        try { storage.put(key, encoded.bytes()); }
        catch (IOException error) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "参考图保存失败，请重试");
        }
        // A rolled-back database write must not leave a successful upload behind.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    try { storage.deleteTree(prefix); } catch (IOException ignored) { /* Local runtime data only. */ }
                }
            }
        });
        return styles.saveAndFlush(new CustomStyle(id, userId, name, key, encoded.mediaType())).view();
    }

    @Transactional
    public void delete(long userId, String id) {
        var style = styles.lock(userId, id).orElseThrow(ApiException::notFound);
        if (style.archived()) throw ApiException.notFound();
        style.archive();
        styles.flush();
        releaseIfUnused(userId, id);
    }

    @Transactional
    public void releaseIfUnused(long userId, String id) {
        if (catalog.find(id).isPresent()) return;
        var style = styles.lock(userId, id).orElseThrow(ApiException::notFound);
        if (style.archived() && !referenced(userId, id)) cleanup.enqueue("u/" + userId + "/styles/" + id);
    }

    public record Image(Resource resource, String mediaType) {}

    @Transactional(readOnly = true)
    public Image image(long userId, String id) {
        var style = readable(userId, id);
        var resource = storage.resource(style.storageKey());
        if (!resource.isReadable()) throw ApiException.notFound();
        return new Image(resource, style.mediaType());
    }

    private CustomStyle readable(long userId, String id) {
        var style = styles.findByIdAndUserId(id, userId).orElseThrow(ApiException::notFound);
        // Removing a chooser entry does not destroy the reference used by an existing project.
        if (style.archived() && !referenced(userId, id)) throw ApiException.notFound();
        return style;
    }

    private boolean referenced(long userId, String id) {
        return db.fetchOne("select count(*) from project where user_id = ? and visual_type = ?", userId, id)
            .get(0, Long.class) > 0;
    }
}
