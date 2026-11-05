package com.vibestory.dispatch;

import com.vibestory.auth.Viewer;
import com.vibestory.project.ProjectAccess;
import com.vibestory.task.TaskService;
import java.time.OffsetDateTime;
import java.util.List;
import org.jooq.DSLContext;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import static com.vibestory.project.ProjectAccess.Resource.*;

@RestController
@io.swagger.v3.oas.annotations.security.SecurityRequirement(name="session")
public class DispatchController {
    private final DispatchStore store;
    private final DSLContext db;
    private final DispatchProperties config;
    private final ProjectAccess access;
    public DispatchController(DispatchStore store, DSLContext db, DispatchProperties config, ProjectAccess access) { this.store=store;this.db=db;this.config=config;this.access=access; }
    public record Group(String type,long queued,long running,long blocked,long recoveries) {}
    public record Metrics(String mode,long pending,Double oldestWaitMs,long publishRetries,long deliveryRetries,long duplicates,long deadLetters,List<Group> groups) {}
    @PostMapping("/api/tasks/{taskId}/dispatch/resume")
    @Transactional
    public TaskService.Submission resume(@AuthenticationPrincipal Viewer viewer,@PathVariable long taskId) { store.resume(access.edit(viewer.id(), TASK, taskId).partitionUserId(), taskId);return new TaskService.Submission(Long.toString(taskId)); }
    @GetMapping("/api/dispatch-metrics")
    @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Metrics metrics(@AuthenticationPrincipal Viewer viewer) {
        var r=db.fetchOne("""
            select count(*) filter (where o.status in ('PENDING','SENDING') and t.status='QUEUED' and t.dispatch_generation=o.dispatch_generation) as pending,
              max(extract(epoch from (now()-o.created_at))*1000) filter (where o.status in ('PENDING','SENDING') and t.status='QUEUED' and t.dispatch_generation=o.dispatch_generation) as oldest,
              coalesce(sum(o.publish_failures),0) as publishes,coalesce(sum(o.delivery_failures),0) as deliveries,coalesce(sum(o.duplicate_count),0) as duplicates
            from task_outbox o join task t on t.id=o.task_id join project p on p.id=t.project_id
            where t.user_id=? and p.team_id is null
            """,viewer.id());
        var groups=db.fetch("""
            select type,count(*) filter (where status='QUEUED') as queued,count(*) filter (where status='RUNNING') as running,
              count(*) filter (where dispatch_blocked is not null and status='QUEUED') as blocked,coalesce(sum(recovery_count),0) as recoveries
            from task t join project p on p.id=t.project_id where t.user_id=? and p.team_id is null group by type order by type
            """,viewer.id()).map(x->new Group(x.get("type",String.class),x.get("queued",Long.class),x.get("running",Long.class),x.get("blocked",Long.class),x.get("recoveries",Long.class)));
        long dead=db.fetchOne("""
            select count(*) from dispatch_dead_letter d join task t on t.id=d.task_id join project p on p.id=t.project_id
            where t.user_id=? and p.team_id is null
            """,viewer.id()).get(0,Long.class);
        return new Metrics(config.mode(),r.get("pending",Long.class),r.get("oldest",Double.class),r.get("publishes",Long.class),r.get("deliveries",Long.class),r.get("duplicates",Long.class),dead,groups);
    }
}
