package com.vibestory.episode;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.Locale;
import com.vibestory.common.ProductionStatistics;

@Entity
@Table(name = "episode")
public class Episode {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private long userId;
    private long projectId;
    private int episodeIndex;
    private String title;
    private String summary = "";
    private String storyContent = "";
    private String breakdownState = "IDLE";
    private Instant createdAt;
    private Instant updatedAt;
    @Version private long version;
    private long storyRevision;
    private long shotRevision;
    private Long breakdownTaskId;

    protected Episode() {}
    public Episode(long userId, long projectId, int index, String title) {
        this.userId = userId;
        this.projectId = projectId;
        this.episodeIndex = index;
        this.title = title.strip();
        this.createdAt = this.updatedAt = Instant.now();
    }

    public long projectId() { return projectId; }
    public long id() { return id; }
    public long version() { return version; }
    public long storyRevision() { return storyRevision; }
    public void update(String title, String summary) {
        if (title != null) this.title = title;
        if (summary != null) this.summary = summary;
        updatedAt = Instant.now();
    }
    public void setStory(String story) {
        if (!storyContent.equals(story)) {
            storyContent = story;
            storyRevision++;
            if (breakdownState.equals("DONE")) breakdownState = "IDLE";
            updatedAt = Instant.now();
        }
    }

    @io.swagger.v3.oas.annotations.media.Schema(name = "Episode")
    public record View(String id, int index, String title, String summary, String story, String breakdown,
                       long createdAt, long updatedAt, ProductionStatistics.Counts stats, long version, long storyRevision) {}
    public View view(ProductionStatistics.Counts stats) {
        return view(stats, breakdownState);
    }
    public View view(ProductionStatistics.Counts stats, String state) {
        return new View(id.toString(), episodeIndex, title, summary, storyContent,
            state.toLowerCase(Locale.ROOT), createdAt.toEpochMilli(), updatedAt.toEpochMilli(), stats, version, storyRevision);
    }
}
