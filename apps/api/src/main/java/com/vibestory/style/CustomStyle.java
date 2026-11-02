package com.vibestory.style;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "custom_style")
public class CustomStyle {
    @Id private String id;
    private long userId;
    private String label;
    private String storageKey;
    private String mediaType;
    private Instant createdAt;
    private Instant deletedAt;
    @Version private long version;

    protected CustomStyle() {}
    public CustomStyle(String id, long userId, String label, String storageKey, String mediaType) {
        this.id = id;
        this.userId = userId;
        this.label = label;
        this.storageKey = storageKey;
        this.mediaType = mediaType;
        this.createdAt = Instant.now();
    }
    public String id() { return id; }
    public String storageKey() { return storageKey; }
    public String mediaType() { return mediaType; }
    public boolean archived() { return deletedAt != null; }
    public void archive() { deletedAt = Instant.now(); }
    public VisualPreset view() {
        return new VisualPreset(id, "custom", label, "上传的参考图", "/api/visual-styles/" + id + "/image", null, null, true);
    }
}
