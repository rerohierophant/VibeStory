package com.vibestory.task;

import com.vibestory.auth.Viewer;
import java.time.OffsetDateTime;
import java.util.List;
import org.jooq.DSLContext;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Small, scoped diagnostics derived from durable task history, surviving JVM restarts. */
@RestController
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "session")
public class TaskMetrics {
    private final DSLContext db;
    public TaskMetrics(DSLContext db) { this.db = db; }

    public record Timing(long samples, Double averageMs, Double maxMs) {}
    public record Group(String type, String provider, long tasks, long queued, long running,
                        long succeeded, long failed, long unknown, long canceled,
                        long knownOutcomes, Double failureRate, Timing firstQueueWait, Timing attemptElapsed) {}
    public record View(OffsetDateTime from, OffsetDateTime to, List<Group> groups) {}

    @GetMapping("/api/task-metrics")
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public View get(@AuthenticationPrincipal Viewer viewer) {
        var to = db.fetchOne("select now()").get(0, OffsetDateTime.class);
        var from = to.minusHours(24);
        var groups = db.fetch("""
            with scoped as (
                select * from task where user_id=? and created_at>=?::timestamptz and created_at<=?::timestamptz
                    and exists (select 1 from project p where p.id=task.project_id and p.team_id is null)
            ), timings as (
                select t.id,
                    max(extract(epoch from (a.started_at-t.created_at))*1000) filter (where a.attempt_no=1) as queue_ms,
                    count(*) filter (where a.finished_at is not null) as elapsed_samples,
                    sum(extract(epoch from (a.finished_at-a.started_at))*1000) filter (where a.finished_at is not null) as elapsed_sum,
                    max(extract(epoch from (a.finished_at-a.started_at))*1000) filter (where a.finished_at is not null) as elapsed_max
                from scoped t left join task_attempt a on a.task_id=t.id group by t.id
            )
            select t.type,t.provider,count(*) as tasks,
                count(*) filter (where t.status='QUEUED') as queued,
                count(*) filter (where t.status='RUNNING') as running,
                count(*) filter (where t.status='SUCCEEDED') as succeeded,
                count(*) filter (where t.status='FAILED') as failed,
                count(*) filter (where t.status='UNKNOWN') as unknown,
                count(*) filter (where t.status='CANCELED') as canceled,
                count(m.queue_ms) as queue_samples,avg(m.queue_ms) as queue_avg,max(m.queue_ms) as queue_max,
                sum(m.elapsed_samples) as elapsed_samples,
                sum(m.elapsed_sum)/nullif(sum(m.elapsed_samples),0) as elapsed_avg,max(m.elapsed_max) as elapsed_max
            from scoped t join timings m on m.id=t.id group by t.type,t.provider order by t.type,t.provider
            """, viewer.id(), from, to).stream().map(row -> {
                long success = row.get("succeeded", Long.class), failed = row.get("failed", Long.class);
                long known = success + failed;
                return new Group(row.get("type", String.class), row.get("provider", String.class), row.get("tasks", Long.class),
                    row.get("queued", Long.class), row.get("running", Long.class), success, failed,
                    row.get("unknown", Long.class), row.get("canceled", Long.class), known,
                    known == 0 ? null : (double) failed / known,
                    new Timing(row.get("queue_samples", Long.class), row.get("queue_avg", Double.class), row.get("queue_max", Double.class)),
                    new Timing(row.get("elapsed_samples", Long.class), row.get("elapsed_avg", Double.class), row.get("elapsed_max", Double.class)));
            }).toList();
        return new View(from, to, groups);
    }
}
