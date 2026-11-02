package com.vibestory.admission;

import com.vibestory.dispatch.TaskNotification;
import com.vibestory.task.TaskStore;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class AdmissionStore {
    private final DSLContext db;
    public AdmissionStore(DSLContext db) { this.db=db; }
    public record Epoch(long number,String state,String runId,UUID token) {}
    private Epoch epoch(org.jooq.Record r) { return new Epoch(r.get("epoch",Long.class),r.get("state",String.class),r.get("redis_run_id",String.class),r.get("recovery_token",UUID.class)); }
    @Transactional
    public Epoch register(AdmissionProperties.Bucket bucket) {
        db.execute("insert into generation_bucket(bucket_id,config_signature) values (?,?) on conflict do nothing",bucket.id(),bucket.signature());
        var r=db.fetchOne("select * from generation_bucket where bucket_id=?",bucket.id());
        if(!bucket.signature().equals(r.get("config_signature",String.class))) throw new IllegalStateException("ADMISSION_CONFIG_MISMATCH");
        return epoch(r);
    }
    @Transactional
    public Epoch recover(String bucket,Epoch observed,String runId) {
        var r=db.fetchOne("select * from generation_bucket where bucket_id=? for update",bucket);
        var current=epoch(r);
        if(current.number()!=observed.number()) return current;
        if(observed.state().equals("RECOVERING") && current.runId().equals(runId)) return current;
        if(current.state().equals("RECOVERING") && current.runId().equals(runId)) return current;
        return epoch(db.fetchOne("update generation_bucket set epoch=epoch+1,state='RECOVERING',redis_run_id=?,recovery_token=?,updated_at=now() where bucket_id=? returning *",runId,UUID.randomUUID(),bucket));
    }
    @Transactional
    public void activate(String bucket,Epoch epoch) {
        db.execute("update generation_bucket set state='ACTIVE',updated_at=now() where bucket_id=? and epoch=? and recovery_token=? and redis_run_id=?",bucket,epoch.number(),epoch.token(),epoch.runId());
    }
    @Transactional
    public void record(UUID id,long task,String bucket,Long epoch,String outcome,String reason,Long redisAt,long retry) {
        // A concurrent deletion is an expected lost race, not a business failure.
        db.execute("insert into generation_admission(id,task_id,bucket_id,epoch,outcome,reason,redis_at_ms,retry_after_ms) select ?,id,?,?,?,?,?,? from task where id=?",id,bucket,epoch,outcome,reason,redisAt,retry,task);
    }
    @Transactional
    public boolean defer(TaskNotification event,String reason,long retryMs) {
        return db.execute("""
            update task t set not_before=clock_timestamp()+(? * interval '1 millisecond'),
                admission_wait_reason=?,admission_wait_since=coalesce(admission_wait_since,clock_timestamp()),
                dispatch_generation=dispatch_generation+1,version=version+1,updated_at=now()
            where t.id=? and t.dispatch_generation=? and t.status='QUEUED' and t.dispatch_blocked is null
                and t.user_id=? and t.project_id=? and t.type=? and t.not_before<=clock_timestamp()
                and exists(select 1 from task_outbox o where o.id=? and o.task_id=t.id and o.dispatch_generation=t.dispatch_generation)
            """,Math.max(10,retryMs),reason,event.taskId(),event.dispatchGeneration(),event.userId(),event.projectId(),event.type(),event.eventId())==1;
    }
    @Transactional(readOnly=true)
    public boolean owns(TaskStore.Lease lease) {
        return db.fetchExists(db.selectOne().from("task").where("id=? and status='RUNNING' and lease_owner=? and lease_until>clock_timestamp()",lease.taskId(),lease.owner()));
    }
    @Transactional
    public void waiting(TaskStore.Lease lease,String reason) {
        db.execute("update task set admission_wait_reason=?,admission_wait_since=coalesce(admission_wait_since,clock_timestamp()) where id=? and status='RUNNING' and lease_owner=? and lease_until>clock_timestamp()",reason,lease.taskId(),lease.owner());
    }
    @Transactional
    public boolean submission(TaskStore.Lease lease,GenerationAdmission.Permit permit) {
        // Same owner remains stable across heartbeat version changes. Recheck the durable epoch and lease.
        var row=db.fetchOne("select t.id,t.admission_wait_since from task t where t.id=? and t.status='RUNNING' and t.lease_owner=? and t.lease_until>clock_timestamp() for update",lease.taskId(),lease.owner());
        if(row==null) return false;
        int updated=db.execute("""
            update generation_admission a set attempt_id=?,submitted_at=clock_timestamp(),
                waited_ms=coalesce(extract(epoch from (clock_timestamp()-t.admission_wait_since))*1000,0)
            from generation_bucket b, task t where a.id=? and a.task_id=t.id and t.id=? and a.outcome='ALLOWED'
                and a.submitted_at is null and not a.expired and b.bucket_id=a.bucket_id and b.epoch=a.epoch and b.state='ACTIVE'
            """,lease.attemptId(),permit.id(),lease.taskId());
        if(updated==1) db.execute("update task set admission_wait_reason=null,admission_wait_since=null where id=?",lease.taskId());
        return updated==1;
    }
    @Transactional
    public void expired(UUID permit) {
        db.execute("update generation_admission set expired=true,submitted_at=null where id=?",permit);
    }
}
