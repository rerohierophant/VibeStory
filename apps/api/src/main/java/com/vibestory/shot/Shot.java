package com.vibestory.shot;

import com.vibestory.provider.TextProvider;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "shot")
public class Shot {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    private long userId;
    private long projectId;
    private long episodeId;
    private int seq;
    private String sceneName;
    private String shotSize;
    private String cameraMove;
    private String description;
    private String dialogue;
    private int durationMs;
    private String status;
    @JdbcTypeCode(SqlTypes.JSON) private List<String> characterNames;
    private Instant createdAt;
    private Instant updatedAt;
    @Version private long version;

    protected Shot() {}
    public Shot(long userId, long projectId, long episodeId, int seq, TextProvider.ShotDraft draft) {
        this.userId = userId; this.projectId = projectId; this.episodeId = episodeId; this.seq = seq;
        sceneName = draft.sceneName(); shotSize = draft.shotSize(); cameraMove = draft.cameraMove();
        description = draft.description(); dialogue = draft.dialogue(); durationMs = draft.durationMs();
        characterNames = List.copyOf(draft.characterNames()); status = "DRAFT";
        createdAt = updatedAt = Instant.now();
    }
    public long id() { return id; }
    public long episodeId() { return episodeId; }
    public long projectId() { return projectId; }
    public String sceneName() { return sceneName; }
    public List<String> characterNames() { return characterNames; }
    public void bind(String scene, List<String> characters) { sceneName = scene; characterNames = characters; }

    public boolean update(String description, String dialogue, String sceneName, String shotSize, String cameraMove, Integer durationMs) {
        boolean changed = description != null && !description.equals(this.description) || dialogue != null && !dialogue.equals(this.dialogue) ||
            sceneName != null && !sceneName.equals(this.sceneName) || shotSize != null && !shotSize.equals(this.shotSize) ||
            cameraMove != null && !cameraMove.equals(this.cameraMove) || durationMs != null && durationMs != this.durationMs;
        if (description != null) this.description = description;
        if (dialogue != null) this.dialogue = dialogue;
        if (sceneName != null) this.sceneName = sceneName;
        if (shotSize != null) this.shotSize = shotSize;
        if (cameraMove != null) this.cameraMove = cameraMove;
        if (durationMs != null) this.durationMs = durationMs;
        if (changed) updatedAt = Instant.now();
        return changed;
    }
    public boolean changeStatus(String next) {
        if (!next.equals("DRAFT") && !next.equals("CONFIRMED")) throw new IllegalArgumentException("未知镜头状态");
        if (status.equals(next)) return false;
        status = next; updatedAt = Instant.now(); return true;
    }
    public record Fields(String id, String projectId, String episodeId, int seq, String sceneName, String shotSize,
                         String cameraMove, String description, String dialogue, int durationMs, String status, List<String> characterNames) {}
    public Fields fields() {
        return new Fields(id.toString(), Long.toString(projectId), Long.toString(episodeId), seq, sceneName, shotSize,
            cameraMove, description, dialogue, durationMs, status.toLowerCase(java.util.Locale.ROOT), List.copyOf(characterNames));
    }
}
