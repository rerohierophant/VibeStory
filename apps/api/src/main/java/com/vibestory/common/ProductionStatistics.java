package com.vibestory.common;

import org.jooq.DSLContext;
import org.springframework.stereotype.Component;

@Component
public class ProductionStatistics {
    private final DSLContext db;
    public ProductionStatistics(DSLContext db) { this.db = db; }

    public record Counts(int shotCount, int confirmedCount, int imageCount, int videoCount) {}
    public record Overview(int episodeCount, int shotCount, int confirmedCount, int imageCount, int videoCount) {}

    public Counts episode(long userId, long episodeId) { return counts(userId, episodeId, "episode_id"); }

    public Overview project(long userId, long projectId) {
        int episodes = db.fetchOne("select count(*) from episode where project_id = ? and user_id = ?", projectId, userId)
            .get(0, Integer.class);
        var counts = counts(userId, projectId, "project_id");
        return new Overview(episodes, counts.shotCount(), counts.confirmedCount(), counts.imageCount(), counts.videoCount());
    }

    private Counts counts(long userId, long target, String column) {
        // EXISTS counts shots, not candidate images, video versions, or attempts.
        var row = db.fetchOne("""
            select count(*) as shots,
                count(*) filter (where s.status = 'CONFIRMED') as confirmed,
                count(*) filter (where exists (select 1 from shot_image i where i.shot_id = s.id and i.selected and i.status = 'READY')) as images,
                count(*) filter (where exists (select 1 from video v where v.shot_id = s.id)) as videos
            from shot s where s.user_id = ? and s.%s = ?
            """.formatted(column), userId, target);
        return new Counts(row.get("shots", Integer.class), row.get("confirmed", Integer.class),
            row.get("images", Integer.class), row.get("videos", Integer.class));
    }
}
