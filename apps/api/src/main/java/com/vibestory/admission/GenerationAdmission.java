package com.vibestory.admission;

import com.vibestory.dispatch.DispatchProperties;
import com.vibestory.task.TaskStore;
import java.util.*;
import java.util.concurrent.Callable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
public class GenerationAdmission {
    private final AdmissionProperties config;
    private final AdmissionStore store;
    private final StringRedisTemplate redis;
    private final DefaultRedisScript<List> acquire=new DefaultRedisScript<>();
    private final DefaultRedisScript<Long> reset=new DefaultRedisScript<>();
    private final com.vibestory.provider.MockSubmissionProbe probe;
    public record Permit(UUID id,long validUntilNanos) { public boolean fresh() { return System.nanoTime()<validUntilNanos; } }
    public record Decision(Permit permit,String reason,long retryAfterMs) { public boolean allowed() { return permit!=null; } }
    public GenerationAdmission(AdmissionProperties config,AdmissionStore store,StringRedisTemplate redis,DispatchProperties dispatch,com.vibestory.provider.MockSubmissionProbe probe) {
        this.config=config;this.store=store;this.redis=redis;this.probe=probe;
        if(config.enabled() && !dispatch.rabbit()) throw new IllegalArgumentException("Redis 准入须使用 rabbit 模式，禁止绕过领取前准入");
        acquire.setLocation(new ClassPathResource("admission/acquire.lua"));acquire.setResultType(List.class);
        reset.setLocation(new ClassPathResource("admission/reset.lua"));reset.setResultType(Long.class);
    }
    public boolean enabled() { return config.enabled(); }
    public Decision request(long taskId,String type,String provider) {
        if(!enabled()) return new Decision(new Permit(null,Long.MAX_VALUE),null,0);
        var bucket=config.bucket(type,provider);UUID id=UUID.randomUUID();
        try {
            var epoch=store.register(bucket);
            long started=System.nanoTime();
            List<?> result=redis.execute(acquire,List.of(bucket.key()+":marker",bucket.key()+":tokens"),Long.toString(epoch.number()),epoch.runId(),epoch.token().toString(),Double.toString(bucket.rate()),Integer.toString(bucket.burst()),Long.toString(bucket.ttlMs()));
            int code=((Number)result.get(0)).intValue();long at=((Number)result.get(2)).longValue();
            if(code==2 || epoch.state().equals("RECOVERING")) {
                var recovery=store.recover(bucket.id(),epoch,result.get(3).toString());
                if(recovery.state().equals("RECOVERING")) {
                    Long done=redis.execute(reset,List.of(bucket.key()+":marker",bucket.key()+":tokens"),Long.toString(recovery.number()),recovery.runId(),recovery.token().toString(),Long.toString(bucket.ttlMs()));
                    if(Long.valueOf(1).equals(done)) store.activate(bucket.id(),recovery);
                }
                store.record(id,taskId,bucket.id(),recovery.number(),"DENIED","ADMISSION_RECOVERING",at,config.errorBackoff().toMillis());
                return new Decision(null,"ADMISSION_RECOVERING",config.errorBackoff().toMillis());
            }
            if(code==3) {
                store.record(id,taskId,bucket.id(),epoch.number(),"DENIED","ADMISSION_EPOCH_CHANGED",at,config.errorBackoff().toMillis());
                return new Decision(null,"ADMISSION_EPOCH_CHANGED",config.errorBackoff().toMillis());
            }
            boolean allowed=code==1;long retry=((Number)result.get(1)).longValue();
            store.record(id,taskId,bucket.id(),epoch.number(),allowed?"ALLOWED":"DENIED",allowed?"TOKEN_GRANTED":"RATE_LIMITED",at,retry);
            return new Decision(allowed?new Permit(id,started+config.permitTtl().toNanos()):null,allowed?null:"RATE_LIMITED",retry);
        } catch(Exception unavailable) {
            // Do not log exception bodies: Redis URIs and credentials must never enter diagnostics.
            String reason="ADMISSION_CONFIG_MISMATCH".equals(unavailable.getMessage())?"ADMISSION_CONFIG_MISMATCH":"REDIS_UNAVAILABLE";
            store.record(id,taskId,bucket.id(),null,"ERROR",reason,null,config.errorBackoff().toMillis());
            return new Decision(null,reason,config.errorBackoff().toMillis());
        }
    }
    public <T> T execute(TaskStore.Lease lease,Permit initial,Callable<T> call) throws Exception {
        return execute(lease,initial,()->true,call);
    }
    public <T> T execute(TaskStore.Lease lease,Permit initial,java.util.function.BooleanSupplier active,Callable<T> call) throws Exception {
        if(!enabled() || lease.externalTaskId()!=null) {
            if(!active.getAsBoolean() || !store.owns(lease)) throw new InterruptedException("租约已失效");
            return probe.invoke(lease,null,call);
        }
        return probe.invoke(lease,null,()->SubmissionBoundary.invoke(()->probe.permit(awaitPermit(lease,initial,active)),call));
    }
    private UUID awaitPermit(TaskStore.Lease lease,Permit initial,java.util.function.BooleanSupplier active) throws InterruptedException {
        Permit permit=initial;
        while(!Thread.currentThread().isInterrupted() && active.getAsBoolean() && store.owns(lease)) {
            if(permit!=null && permit.fresh()) {
                if(store.submission(lease,permit)) {
                    if(permit.fresh() && active.getAsBoolean()) return permit.id();
                    store.expired(permit.id());
                }
            } else if(permit!=null) store.expired(permit.id());
            var decision=request(lease.taskId(),lease.type(),lease.provider());permit=decision.permit();
            if(!decision.allowed()) {
                store.waiting(lease,decision.reason());
                Thread.sleep(Math.max(10,Math.min(1000,decision.retryAfterMs())));
            }
        }
        throw new InterruptedException("任务停止或租约已失效");
    }
}
