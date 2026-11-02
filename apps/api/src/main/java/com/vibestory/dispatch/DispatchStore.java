package com.vibestory.dispatch;

import com.vibestory.common.ApiException;
import com.vibestory.common.ScopeGuard;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class DispatchStore {
    private final DSLContext db;
    private final DispatchProperties config;
    private final ScopeGuard scope;
    private final com.vibestory.task.TaskStore.Providers providers;
    public DispatchStore(DSLContext db,DispatchProperties config,ScopeGuard scope,com.vibestory.provider.TextProperties text,com.vibestory.provider.ImageProperties image,com.vibestory.provider.VideoProperties video) { this.db=db;this.config=config;this.scope=scope;this.providers=new com.vibestory.task.TaskStore.Providers(text.provider(),image.provider(),video.provider()); }
    public record Publication(TaskNotification notification, UUID owner, boolean mock) {}

    @Transactional
    public Publication take() {
        var row=db.fetchOne("""
            select o.*,t.user_id,t.project_id,t.episode_id,t.type,t.provider from task_outbox o join task t on t.id=o.task_id
            where o.dispatch_generation=t.dispatch_generation and t.status='QUEUED' and t.dispatch_blocked is null
              and t.not_before<=now() and o.available_at<=now()
              and (t.provider='mock' or (t.type='BREAKDOWN' and t.provider=?) or (t.type in ('IMAGE','ASSET_IMAGE') and t.provider=?) or (t.type='VIDEO' and t.provider=?))
              and (o.status='PENDING' or (o.status='SENDING' and o.lease_until<=now()))
            order by o.available_at,o.created_at for update of o skip locked limit 1
            """,providers.text(),providers.image(),providers.video());
        if(row==null) return null;
        UUID owner=UUID.randomUUID();
        db.execute("update task_outbox set status='SENDING',lease_owner=?,lease_until=now()+(? * interval '1 millisecond') where id=?",owner,config.relayLease().toMillis(),row.get("id",UUID.class));
        return new Publication(event(row),owner,row.get("provider",String.class).equals("mock"));
    }
    public TaskNotification event(Record row) {
        return new TaskNotification(1,row.get("id",UUID.class),row.get("task_id",Long.class),row.get("dispatch_generation",Long.class),row.get("type",String.class),row.get("user_id",Long.class),row.get("project_id",Long.class),row.get("episode_id",Long.class));
    }
    @Transactional
    public boolean sent(Publication publication) {
        return db.execute("update task_outbox set status='SENT',sent_at=now(),lease_owner=null,lease_until=null,last_error=null where id=? and lease_owner=? and status='SENDING' and lease_until>now()",publication.notification().eventId(),publication.owner())==1;
    }
    @Transactional
    public void publishFailed(Publication publication) {
        var e=publication.notification();
        // Serialize a budget change and its task block under the same lock order as submission.
        var task=db.fetchOne("select id from task where id=? for update",e.taskId());
        if(task==null) return;
        var row=db.fetchOne("""
            update task_outbox set publish_failures=publish_failures+1,last_error='PUBLISH_UNCONFIRMED',
              status=case when publish_failures+1>=? then 'BLOCKED' else 'PENDING' end,
              available_at=now()+(least(30000,500*power(2,least(publish_failures,6))) * interval '1 millisecond'),lease_owner=null,lease_until=null
            where id=? and lease_owner=? and status='SENDING' and lease_until>now() returning status
            """,config.maxFailures(),e.eventId(),publication.owner());
        if(row!=null && row.get("status",String.class).equals("BLOCKED"))
            db.execute("update task set dispatch_blocked='PUBLISH_BUDGET_EXHAUSTED' where id=? and dispatch_generation=? and status='QUEUED'",e.taskId(),e.dispatchGeneration());
    }
    @Transactional
    public void reconcileQueued() {
        // Keep the same identity while queued. A delayed old notification remains valid.
        db.execute("""
            update task_outbox o set status='PENDING',available_at=greatest(t.not_before,now()),lease_owner=null,lease_until=null
            from task t where t.id=o.task_id and t.dispatch_generation=o.dispatch_generation and t.status='QUEUED'
              and t.dispatch_blocked is null and o.status='SENT' and o.sent_at<now()-(? * interval '1 millisecond')
            """,config.redeliveryAfter().toMillis());
    }
    @Transactional
    public void duplicate(TaskNotification e) {
        db.execute("update task_outbox set duplicate_count=duplicate_count+1 where id=? and task_id=? and dispatch_generation=?",e.eventId(),e.taskId(),e.dispatchGeneration());
    }
    @Transactional
    public boolean deliveryFailed(TaskNotification e) {
        var task=db.fetchOne("select id from task where id=? for update",e.taskId());
        if(task==null) return false;
        var row=db.fetchOne("""
            update task_outbox set delivery_failures=delivery_failures+1,last_error='CONSUMER_INFRASTRUCTURE',
              status=case when delivery_failures+1>=? then 'BLOCKED' else 'PENDING' end,
              available_at=now()+(least(30000,500*power(2,least(delivery_failures,6))) * interval '1 millisecond'),lease_owner=null,lease_until=null
            where id=? and task_id=? and dispatch_generation=? returning status
            """,config.maxFailures(),e.eventId(),e.taskId(),e.dispatchGeneration());
        if(row!=null && row.get("status",String.class).equals("BLOCKED")) {
            db.execute("update task set dispatch_blocked='CONSUMER_BUDGET_EXHAUSTED' where id=? and dispatch_generation=? and status='QUEUED'",e.taskId(),e.dispatchGeneration());
            return false;
        }
        return true;
    }
    @Transactional
    public void deadLetter(String fingerprint, TaskNotification e, String reason) {
        Long taskId=null;
        if(e!=null && e.eventId()!=null) {
            var row=db.fetchOne("select t.id,t.dispatch_generation from task t join task_outbox o on o.task_id=t.id where t.id=? and o.id=? and o.dispatch_generation=? for update of t",e.taskId(),e.eventId(),e.dispatchGeneration());
            if(row!=null) {
                taskId=row.get("id",Long.class);
                if(row.get("dispatch_generation",Long.class)==e.dispatchGeneration()) {
                    db.execute("update task set dispatch_blocked=? where id=? and status='QUEUED'",reason,taskId);
                    db.execute("update task_outbox set status='BLOCKED',last_error=? where id=?",reason,e.eventId());
                }
            }
        }
        db.execute("insert into dispatch_dead_letter(fingerprint,task_id,reason) values (?,?,?) on conflict do nothing",fingerprint,taskId,reason);
    }
    @Transactional
    public void resume(long userId,long taskId) {
        scope.lockTaskProject(userId,taskId);
        var row=db.fetchOne("select * from task where id=? for update",taskId);
        if(!row.get("status",String.class).equals("QUEUED") || row.get("dispatch_blocked")==null)
            throw ApiException.conflict("仅可恢复派发已暂停的排队任务；未知结果请使用原任务重试入口");
        db.execute("update task set dispatch_blocked=null,dispatch_generation=dispatch_generation+1,not_before=now(),version=version+1,updated_at=now() where id=?",taskId);
    }
}
