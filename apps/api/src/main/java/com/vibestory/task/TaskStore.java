package com.vibestory.task;

import com.vibestory.provider.ProviderException;
import com.vibestory.provider.VideoProvider;
import com.vibestory.provider.VideoProviders;
import com.vibestory.provider.TextProvider;
import com.vibestory.provider.TextProviders;
import com.vibestory.breakdown.BreakdownService;
import com.vibestory.image.ImageTaskService;
import com.vibestory.provider.ImageProviders;
import com.vibestory.provider.ImageProvider;
import java.util.Optional;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class TaskStore {
    private final DSLContext db;
    private final WorkerProperties config;
    private final VideoProviders provider;
    private final TaskService tasks;
    private final BreakdownService breakdowns;
    private final TextProviders text;
    private final ImageProviders images;
    private final ImageTaskService imageTasks;
    private final TaskEvents events;

    public TaskStore(DSLContext db, WorkerProperties config, VideoProviders provider, TaskService tasks, BreakdownService breakdowns, TextProviders text, ImageProviders images, ImageTaskService imageTasks, TaskEvents events) {
        this.db = db;
        this.config = config;
        this.provider = provider;
        this.tasks = tasks;
        this.breakdowns = breakdowns;
        this.text = text;
        this.images = images; this.imageTasks = imageTasks;
        this.events = events;
    }

    public record Lease(long taskId, long attemptId, int attemptNo, String owner, long version,
                        long userId, long projectId, Long episodeId, long shotId, String signature, String input, String type, String provider, String externalTaskId) {
        VideoProvider.Request request() {
            return new VideoProvider.Request(userId, projectId, episodeId, taskId, attemptNo, signature, input);
        }
    }

    @Transactional
    public Optional<Lease> claim(String owner) {
        return claim(owner,new Providers("openai-compatible","minimax","minimax"));
    }

    public record Providers(String text,String image,String video) {}

    @Transactional
    public Optional<Lease> claim(String owner,Providers providers) {
        Record task = db.fetchOne("""
            select * from task where dispatch_blocked is null and not_before<=now() and (status = 'QUEUED' or (status = 'RUNNING' and lease_until <= now()))
                and (provider='mock' or (type='VIDEO' and provider=?) or (type in ('IMAGE','ASSET_IMAGE') and provider=?) or (type='BREAKDOWN' and provider=?))
            order by id for update skip locked limit 1
            """,providers.video(),providers.image(),providers.text());
        if (task == null) return Optional.empty();
        return claimLocked(task, owner);
    }

    @Transactional
    public Optional<Lease> claimNotification(com.vibestory.dispatch.TaskNotification event, String owner, Providers providers, boolean mockLane) {
        var task=notificationTask(event,providers,mockLane,true);
        return task==null?Optional.empty():claimLocked(task,owner);
    }
    public record Candidate(long taskId,String type,String provider,boolean externalRecovery) {}
    @Transactional(readOnly=true)
    public Optional<Candidate> inspectNotification(com.vibestory.dispatch.TaskNotification event,Providers providers,boolean mockLane) {
        var task=notificationTask(event,providers,mockLane,false);
        if(task==null) return Optional.empty();
        boolean resume=task.get("type",String.class).equals("VIDEO") && task.get("provider",String.class).equals("minimax") && db.fetchExists(db.selectOne().from("task_attempt").where("task_id=? and attempt_no=? and external_task_id is not null and status in ('RUNNING','UNKNOWN')",event.taskId(),task.get("attempt_count",Integer.class)));
        return Optional.of(new Candidate(event.taskId(),event.type(),task.get("provider",String.class),resume));
    }
    private Record notificationTask(com.vibestory.dispatch.TaskNotification event,Providers providers,boolean mockLane,boolean lock) {
        // Parent-before-task lock order. Preflight holds no locks and never creates an attempt.
        var parent=db.fetchOne("select id from project where id=? and user_id=?"+(lock?" for update":""),event.projectId(),event.userId());
        if(parent==null) return null;
        var task=db.fetchOne("select t.*,t.not_before<=now() as dispatch_due from task t join task_outbox o on o.task_id=t.id and o.dispatch_generation=t.dispatch_generation where t.id=? and o.id=?"+(lock?" for update of t":""),event.taskId(),event.eventId());
        if (task==null || task.get("dispatch_generation",Long.class)!=event.dispatchGeneration()) return null;
        if (task.get("user_id",Long.class)!=event.userId() || task.get("project_id",Long.class)!=event.projectId()
            || !java.util.Objects.equals(task.get("episode_id",Long.class),event.episodeId()) || !task.get("type",String.class).equals(event.type()))
            throw new IllegalArgumentException("通知作用域不匹配");
        String configured = switch(event.type()) { case "VIDEO" -> providers.video(); case "BREAKDOWN" -> providers.text(); default -> providers.image(); };
        String persisted=task.get("provider",String.class);
        if (persisted.equals("mock")!=mockLane || (!persisted.equals("mock") && !persisted.equals(configured)))
            throw new IllegalArgumentException("通知供应商通道不匹配");
        if (!task.get("status",String.class).equals("QUEUED") || task.get("dispatch_blocked")!=null
            || !task.get("dispatch_due",Boolean.class)) return null;
        if (event.episodeId()!=null && db.fetchOne("select id from episode where id=? and project_id=? and user_id=?",event.episodeId(),event.projectId(),event.userId())==null)
            return null;
        if (task.get("target_type",String.class).equals("SHOT") && db.fetchOne("select id from shot where id=? and episode_id=? and project_id=? and user_id=?",task.get("target_id",Long.class),event.episodeId(),event.projectId(),event.userId())==null)
            return null;
        String targetType=task.get("target_type",String.class);
        if(targetType.equals("EPISODE") && !java.util.Objects.equals(task.get("target_id",Long.class),event.episodeId())) throw new IllegalArgumentException("集目标不匹配");
        if(java.util.Set.of("CHARACTER","SCENE").contains(targetType)) {
            String table=targetType.equals("CHARACTER")?"character_asset":"scene_asset";
            if(db.fetchOne("select id from "+table+" where id=? and project_id=? and user_id=?",task.get("target_id",Long.class),event.projectId(),event.userId())==null) return null;
        }
        return task;
    }

    private Optional<Lease> claimLocked(Record task,String owner) {
        long taskId = task.get("id", Long.class);
        var previous=db.fetchOne("select * from task_attempt where task_id=? order by attempt_no desc limit 1",taskId);
        if(task.get("type",String.class).equals("VIDEO") && task.get("provider",String.class).equals("minimax") && previous!=null && previous.get("external_task_id")!=null &&
            (task.get("status",String.class).equals("RUNNING") || java.util.Set.of("UNKNOWN","RUNNING").contains(previous.get("status",String.class)))) {
            // Resume the SAME external attempt: no paid submission, no artificial attempt increment.
            var resumed=db.fetchOne("""
                update task set status='RUNNING',lease_owner=?,lease_until=now()+(? * interval '1 millisecond'),version=version+1,
                    updated_at=now(),finished_at=null,error_code=null,error_message=null,retryable=false where id=? returning *
                """,owner,config.leaseDuration().toMillis(),taskId);
            db.execute("update task_attempt set lease_owner=?,status='RUNNING',finished_at=null,error_code=null where id=?",owner,previous.get("id",Long.class));
            events.record("attempt_resumed", taskId, previous.get("id", Long.class));
            return Optional.of(lease(resumed,previous,owner));
        }
        if (task.get("status", String.class).equals("RUNNING")) {
            boolean safeReplay = task.get("type", String.class).equals("BREAKDOWN")
                ? text.capabilities(task.get("provider", String.class)).idempotencyKeySupported()
                : task.get("type", String.class).endsWith("IMAGE") ? images.safeReplay(task.get("provider", String.class))
                : provider.capabilities(task.get("provider",String.class)).idempotencyKeySupported();
            db.execute("""
                update task_attempt set status = ?, error_code = 'LEASE_EXPIRED', finished_at = now()
                where task_id = ? and status = 'RUNNING'
                """, safeReplay ? "ABANDONED" : "UNKNOWN", taskId);
            if (previous != null) events.record("attempt_finished", taskId, previous.get("id", Long.class));
            // Safe automatic replay is currently limited to the idempotent local Mock adapter.
            if (!safeReplay) {
                terminalWithoutLease(taskId, "UNKNOWN", "LEASE_EXPIRED_RESULT_UNKNOWN", "执行进程中断，外部结果未知");
                return Optional.empty();
            }
        }
        if (task.get("attempt_count", Integer.class) >= config.maxAttempts()) {
            terminalWithoutLease(taskId, "FAILED", "MAX_ATTEMPTS", "租约恢复已达到最大尝试次数");
            return Optional.empty();
        }
        Record claimed = db.fetchOne("""
            update task set status = 'RUNNING', lease_owner = ?,
                lease_until = now() + (? * interval '1 millisecond'), version = version + 1,
                attempt_count = attempt_count + 1, updated_at = now(), finished_at = null,
                error_code = null, error_message = null, retryable = false
            where id = ? and version = ? returning *
            """, owner, config.leaseDuration().toMillis(), taskId, task.get("version", Long.class));
        Record attempt = db.fetchOne("""
            insert into task_attempt(task_id, attempt_no, lease_owner) values (?, ?, ?) returning id
            """, taskId, claimed.get("attempt_count", Integer.class), owner);
        events.record("attempt_started", taskId, attempt.get("id", Long.class));
        return Optional.of(new Lease(taskId, attempt.get("id", Long.class), claimed.get("attempt_count", Integer.class),
            owner, claimed.get("version", Long.class), claimed.get("user_id", Long.class),
            claimed.get("project_id", Long.class), claimed.get("episode_id", Long.class),
            claimed.get("target_id", Long.class), claimed.get("input_signature", String.class),
            claimed.get("input_snapshot", String.class), claimed.get("type", String.class), claimed.get("provider", String.class),null));
    }

    @Transactional
    public void recoverExpired(Providers providers) {
        for (var task : db.fetch("select * from task where status='RUNNING' and lease_until<=now() and dispatch_blocked is null and (provider='mock' or (type='BREAKDOWN' and provider=?) or (type in ('IMAGE','ASSET_IMAGE') and provider=?) or (type='VIDEO' and provider=?)) order by id for update skip locked limit 50",providers.text(),providers.image(),providers.video())) {
            long id=task.get("id",Long.class);
            var previous=db.fetchOne("select * from task_attempt where task_id=? order by attempt_no desc limit 1",id);
            boolean resume=task.get("type",String.class).equals("VIDEO") && task.get("provider",String.class).equals("minimax") && previous!=null && previous.get("external_task_id")!=null;
            boolean mock=task.get("provider",String.class).equals("mock");
            if (!resume) {
                db.execute("update task_attempt set status=?,error_code='LEASE_EXPIRED',finished_at=now() where task_id=? and status='RUNNING'",mock?"ABANDONED":"UNKNOWN",id);
                if(previous!=null) events.record("attempt_finished",id,previous.get("id",Long.class));
            }
            if (!resume && !mock) terminalWithoutLease(id,"UNKNOWN","LEASE_EXPIRED_RESULT_UNKNOWN","执行进程中断，外部结果未知");
            else if (!resume && task.get("attempt_count",Integer.class)>=config.maxAttempts()) terminalWithoutLease(id,"FAILED","MAX_ATTEMPTS","租约恢复已达到最大尝试次数");
            else {
                db.execute("update task set status='QUEUED',lease_owner=null,lease_until=null,version=version+1,recovery_count=recovery_count+1,updated_at=now() where id=?",id);
                events.record("lease_recovered",id,previous==null?null:previous.get("id",Long.class));
            }
        }
    }

    private Lease lease(Record task,Record attempt,String owner) {
        return new Lease(task.get("id",Long.class),attempt.get("id",Long.class),attempt.get("attempt_no",Integer.class),owner,task.get("version",Long.class),
            task.get("user_id",Long.class),task.get("project_id",Long.class),task.get("episode_id",Long.class),task.get("target_id",Long.class),
            task.get("input_signature",String.class),task.get("input_snapshot",String.class),task.get("type",String.class),task.get("provider",String.class),attempt.get("external_task_id",String.class));
    }

    @Transactional
    public boolean checkpoint(Lease lease,long version,String externalId) {
        return db.execute("""
            update task_attempt a set external_task_id=? where a.id=? and a.status='RUNNING' and a.lease_owner=?
                and (a.external_task_id is null or a.external_task_id=?) and exists
                (select 1 from task t where t.id=a.task_id and t.status='RUNNING' and t.version=? and t.lease_owner=? and t.lease_until>now())
            """,externalId,lease.attemptId(),lease.owner(),externalId,version,lease.owner())==1;
    }

    @Transactional
    public Optional<Long> heartbeat(Lease lease, long version) {
        Record renewed = db.fetchOne("""
            update task set lease_until = now() + (? * interval '1 millisecond'), version = version + 1, updated_at = now()
            where id = ? and status = 'RUNNING' and lease_owner = ? and version = ? and lease_until > now()
            returning version
            """, config.leaseDuration().toMillis(), lease.taskId(), lease.owner(), version);
        return renewed == null ? Optional.empty() : Optional.of(renewed.get("version", Long.class));
    }

    @Transactional
    public boolean succeed(Lease lease, long version, VideoProvider.Result result) {
        if (!finish(lease, version, "SUCCEEDED", null, null, false)) return false;
        var input = tasks.input(lease.input());
        db.execute("""
            insert into video(task_id, shot_id, episode_id, source_image_id, source_image_version, storage_key, duration_ms)
            values (?, ?, ?, ?, ?, ?, ?)
            """, lease.taskId(), lease.shotId(), lease.episodeId(), input.sourceImageId(), input.sourceImageVersion(),
            result.storageKey(), result.durationMs());
        events.record("attempt_finished", lease.taskId(), lease.attemptId());
        return true;
    }

    @Transactional
    public boolean succeedImage(Lease lease, long version, ImageProvider.Result result) {
        if (!finish(lease, version, "SUCCEEDED", null, null, false)) return false;
        imageTasks.install(lease.userId(), lease.projectId(), lease.episodeId(), lease.taskId(), lease.attemptNo(), imageTasks.input(lease.input()), result);
        diagnostics(lease, result.diagnostics(), result.actualModel());
        events.record("attempt_finished", lease.taskId(), lease.attemptId());
        return true;
    }

    @Transactional
    public boolean succeedBreakdown(Lease lease, long version, TextProvider.Result result) {
        if (!finish(lease, version, "SUCCEEDED", null, null, false)) return false;
        var outcome = breakdowns.install(lease.userId(), lease.projectId(), lease.episodeId(), lease.taskId(), breakdowns.input(lease.input()), result);
        if (!outcome.applied()) {
            db.execute("update task set status = 'CANCELED', error_code = ?, error_message = ? where id = ?", outcome.code(), outcome.message(), lease.taskId());
            db.execute("update task_attempt set status = 'CANCELED', error_code = ? where id = ?", outcome.code(), lease.attemptId());
        }
        diagnostics(lease, result.rawResponse(), result.actualModel());
        events.record("attempt_finished", lease.taskId(), lease.attemptId());
        return outcome.applied();
    }

    @Transactional
    public boolean fail(Lease lease, long version, ProviderException error) {
        if (!finish(lease, version, error.unknown() ? "UNKNOWN" : "FAILED", error.code(), error.getMessage(), error.retryable())) return false;
        diagnostics(lease, error.rawResponse(), error.actualModel());
        if (lease.type().equals("BREAKDOWN")) breakdowns.failed(lease.taskId(), lease.episodeId());
        events.record("attempt_finished", lease.taskId(), lease.attemptId());
        return true;
    }

    private void diagnostics(Lease lease, String raw, String actualModel) {
        db.execute("update task_attempt set raw_response = ?, actual_model = ? where id = ?", raw, actualModel, lease.attemptId());
    }

    private boolean finish(Lease lease, long version, String status, String code, String message, boolean retryable) {
        // Same parent-before-task lock order as deletion. No result can resurrect a removed scope.
        if (db.fetchOne("select id from project where id = ? and user_id = ? for update", lease.projectId(), lease.userId()) == null)
            return false;
        int changed = db.execute("""
            update task set status = ?, lease_owner = null, lease_until = null, version = version + 1,
                error_code = ?, error_message = ?, retryable = ?, finished_at = now(), updated_at = now(),
                admission_wait_reason=null,admission_wait_since=null
            where id = ? and status = 'RUNNING' and lease_owner = ? and version = ? and lease_until > now()
            """, status, code, message, retryable, lease.taskId(), lease.owner(), version);
        if (changed == 0) return false;
        db.execute("""
            update task_attempt set status = ?, error_code = ?, finished_at = now()
            where id = ? and status = 'RUNNING' and lease_owner = ?
            """, status, code, lease.attemptId(), lease.owner());
        return true;
    }

    private void terminalWithoutLease(long taskId, String status, String code, String message) {
        db.execute("""
            update task set status = ?, lease_owner = null, lease_until = null, version = version + 1,
                error_code = ?, error_message = ?, retryable = false, finished_at = now(), updated_at = now(),
                admission_wait_reason=null,admission_wait_since=null
            where id = ?
            """, status, code, message, taskId);
        events.record("task_terminal", taskId, null);
    }
}
