package com.vibestory.admission;

import com.vibestory.auth.Viewer;
import java.util.List;
import org.jooq.DSLContext;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name="session")
public class AdmissionController {
    private final DSLContext db;
    private final AdmissionProperties config;
    public AdmissionController(DSLContext db,AdmissionProperties config) { this.db=db;this.config=config; }
    public record BucketMetrics(String bucketId,long allowed,long denied,long errors,long expired,long submissions,Double waitedMs) {}
    public record Metrics(boolean enabled,long waiting,List<BucketMetrics> buckets) {}
    @GetMapping("/api/admission-metrics")
    @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Metrics metrics(@AuthenticationPrincipal Viewer viewer) {
        long waiting=db.fetchOne("""
            select count(*) from task t join project p on p.id=t.project_id
            where t.user_id=? and p.team_id is null and t.status in ('QUEUED','RUNNING') and t.admission_wait_reason is not null
            """,viewer.id()).get(0,Long.class);
        var buckets=db.fetch("""
            select a.bucket_id,count(*) filter(where a.outcome='ALLOWED') as allowed,count(*) filter(where a.outcome='DENIED') as denied,
                count(*) filter(where a.outcome='ERROR') as errors,count(*) filter(where a.expired) as expired,
                count(a.submitted_at) as submissions,coalesce(sum(a.waited_ms) filter(where a.submitted_at is not null),0) as waited
            from generation_admission a join task t on t.id=a.task_id join project p on p.id=t.project_id
            where t.user_id=? and p.team_id is null group by a.bucket_id order by a.bucket_id
            """,viewer.id()).stream().map(r->new BucketMetrics(r.get("bucket_id",String.class),r.get("allowed",Long.class),r.get("denied",Long.class),r.get("errors",Long.class),r.get("expired",Long.class),r.get("submissions",Long.class),r.get("waited",Double.class))).toList();
        return new Metrics(config.enabled(),waiting,buckets);
    }
}
