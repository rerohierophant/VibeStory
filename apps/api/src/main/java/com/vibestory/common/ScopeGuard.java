package com.vibestory.common;

import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Component;

@Component
public class ScopeGuard {
    private final DSLContext db;

    public ScopeGuard(DSLContext db) { this.db = db; }

    public void project(long userId, long projectId) {
        require(db.fetchOne("select id from project where id = ? and user_id = ?", projectId, userId));
    }

    // Also serializes allocation of episode_index inside the caller's transaction.
    public void lockProject(long userId, long projectId) {
        require(db.fetchOne("select id from project where id = ? and user_id = ? for update", projectId, userId));
    }

    public void episode(long userId, long episodeId) {
        require(db.fetchOne("""
            select e.id from episode e join project p on p.id = e.project_id and p.user_id = e.user_id
            where e.id = ? and p.user_id = ?
            """, episodeId, userId));
    }

    public long lockEpisode(long userId, long episodeId) {
        var row = require(db.fetchOne("select project_id from episode where id = ? and user_id = ?", episodeId, userId));
        long projectId = row.get("project_id", Long.class);
        lockProject(userId, projectId);
        require(db.fetchOne("select id from episode where id = ? and project_id = ? and user_id = ? for update",
            episodeId, projectId, userId));
        return projectId;
    }

    public void lockTaskProject(long userId, long taskId) {
        var row = require(db.fetchOne("select project_id from task where id = ? and user_id = ?", taskId, userId));
        lockProject(userId, row.get("project_id", Long.class));
        task(userId, taskId);
    }

    public void touchProject(long projectId, long userId) {
        db.execute("update project set updated_at = clock_timestamp(), version = version + 1 where id = ? and user_id = ?", projectId, userId);
    }

    public Record shot(long userId, long shotId) {
        var parent = require(db.fetchOne("select project_id from shot where id = ? and user_id = ?", shotId, userId));
        lockProject(userId, parent.get("project_id", Long.class));
        return require(db.fetchOne("""
            select s.*, p.visual_type, p.style from shot s
            join episode e on e.id = s.episode_id and e.project_id = s.project_id and e.user_id = s.user_id
            join project p on p.id = e.project_id and p.user_id = e.user_id
            where s.id = ? and p.user_id = ?
            for update of s
            """, shotId, userId));
    }

    public void task(long userId, long taskId) {
        require(db.fetchOne("""
            select t.id from task t
            join project p on p.id = t.project_id and p.user_id = t.user_id
            left join episode e on e.id = t.episode_id and e.project_id = t.project_id and e.user_id = t.user_id
            where t.id = ? and p.user_id = ? and (t.episode_id is null or e.id is not null)
            """, taskId, userId));
    }

    private Record require(Record record) {
        if (record == null) throw ApiException.notFound();
        return record;
    }
}
