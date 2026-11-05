package com.vibestory.project;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.List;
import com.vibestory.episode.Episode;
import com.vibestory.style.VisualPreset;
import com.vibestory.common.ProductionStatistics;

@Entity
@Table(name = "project")
public class Project {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private long userId;
    private Long teamId;
    private String name;
    private String visualType;
    private String style;
    private Instant createdAt;
    private Instant updatedAt;
    @Version private long version;

    protected Project() {}

    public Project(long userId, Long teamId, String name, String visualType, String style) {
        this.userId = userId;
        this.teamId = teamId;
        this.name = name.strip();
        this.visualType = visualType;
        this.style = style == null ? "" : style;
        this.createdAt = this.updatedAt = Instant.now();
    }

    public long getId() { return id; }
    public long partitionUserId() { return userId; }
    public Long teamId() { return teamId; }
    public long version() { return version; }
    public String visualType() { return visualType; }
    public void update(String name, String visualType, String style) {
        if (name != null) this.name = name;
        if (visualType != null) this.visualType = visualType;
        if (style != null) this.style = style;
        updatedAt = Instant.now();
    }
    @io.swagger.v3.oas.annotations.media.Schema(name = "Project")
    public record View(String id, String name, String visualType, String style, long createdAt, long updatedAt,
                       List<Episode.View> episodes, VisualPreset visualPreset, ProductionStatistics.Overview stats,
                       String teamId, long version) {}
    public View view(List<Episode.View> episodes, VisualPreset preset, ProductionStatistics.Overview stats) {
        return new View(id.toString(), name, visualType, style, createdAt.toEpochMilli(), updatedAt.toEpochMilli(),
            episodes, preset, stats, teamId == null ? null : teamId.toString(), version);
    }
}
