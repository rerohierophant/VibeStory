package com.vibestory.batch;

import com.vibestory.common.*;
import com.vibestory.image.ImageTaskService;
import com.vibestory.task.*;
import java.util.*;
import org.jooq.DSLContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GenerationBatch {
    private final DSLContext db;
    private final ScopeGuard scope;
    private final InputSignature signatures;
    private final TaskService videos;
    private final ImageTaskService images;
    public GenerationBatch(DSLContext db,ScopeGuard scope,InputSignature signatures,TaskService videos,ImageTaskService images) {
        this.db=db;this.scope=scope;this.signatures=signatures;this.videos=videos;this.images=images;
    }
    public enum Type { IMAGE, VIDEO }
    public record Member(String shotId,int seq,String outcome,String reason,String taskId,String state,String dispatchBlocked) {}
    public record Progress(int total,int accepted,int reused,int blocked,int queued,int running,int succeeded,int failed,int unknown,int canceled,int paused) {}
    @io.swagger.v3.oas.annotations.media.Schema(name="GenerationBatch")
    public record View(String id,String episodeId,String type,String requestKey,String state,Progress progress,List<Member> members) {}
    private record Identity(Type type,List<Long> shotIds,boolean regenerate) {}

    @Transactional
    public View submit(long userId,long episodeId,Type type,String requestKey,List<Long> requested,boolean regenerate) {
        if(type==null || requestKey==null || requestKey.isBlank() || requestKey.length()>128 || requested==null || requested.isEmpty() || requested.size()>200
            || requested.stream().anyMatch(id->id==null || id<1) || new HashSet<>(requested).size()!=requested.size()) throw ApiException.badRequest("选择 1 至 200 个不同镜头并提供请求标识");
        long projectId=scope.lockEpisode(userId,episodeId);
        var ids=requested.stream().sorted().toList();
        var signature=signatures.of(new Identity(type,ids,regenerate)).signature();
        var old=db.fetchOne("select id,request_signature from generation_batch where user_id=? and episode_id=? and request_key=?",userId,episodeId,requestKey);
        if(old!=null) {
            if(!old.get("request_signature",String.class).equals(signature)) throw ApiException.conflict("同一批次请求标识不能用于不同内容");
            return get(userId,old.get("id",Long.class));
        }
        // Validate every selected scope before creating any member. Cross-episode IDs are a 404.
        for(long id:ids) if(db.fetchOne("select id from shot where id=? and episode_id=? and project_id=? and user_id=?",id,episodeId,projectId,userId)==null) throw ApiException.notFound();
        long batch=db.fetchOne("insert into generation_batch(user_id,project_id,episode_id,request_key,request_signature,type) values (?,?,?,?,?,?) returning id",userId,projectId,episodeId,requestKey,signature,type.name()).get(0,Long.class);
        for(long id:ids) {
            int seq=db.fetchOne("select seq from shot where id=?",id).get(0,Integer.class);
            var before=new HashSet<>(db.fetch("select id from task where user_id=? and type=? and target_type='SHOT' and target_id=? and status in ('QUEUED','RUNNING')",userId,type.name(),id).getValues("id",Long.class));
            String outcome="ACCEPTED",reason=null;Long taskId=null;
            try {
                String nonce=regenerate?requestKey:null;
                var submission=type==Type.IMAGE?images.submitBatchMember(userId,id,nonce,true):videos.submitBatchMember(userId,id,nonce,true);
                taskId=Long.parseLong(submission.taskId());
                if(before.contains(taskId)) outcome="REUSED";
            } catch(ApiException blocked) {
                if(blocked.status()!=org.springframework.http.HttpStatus.BAD_REQUEST && blocked.status()!=org.springframework.http.HttpStatus.CONFLICT) throw blocked;
                outcome="BLOCKED";reason=blocked.getMessage();
            }
            db.execute("insert into generation_batch_member(batch_id,shot_id,seq,outcome,task_id,reason) values (?,?,?,?,?,?)",batch,id,seq,outcome,taskId,reason);
        }
        return get(userId,batch);
    }
    @Transactional(readOnly=true)
    public View get(long userId,long id) {
        var batch=db.fetchOne("select b.* from generation_batch b join project p on p.id=b.project_id and p.user_id=b.user_id join episode e on e.id=b.episode_id and e.project_id=b.project_id and e.user_id=b.user_id where b.id=? and b.user_id=?",id,userId);
        if(batch==null) throw ApiException.notFound();
        var members=db.fetch("select m.*,t.status,t.dispatch_blocked from generation_batch_member m left join task t on t.id=m.task_id and t.user_id=? where m.batch_id=? order by m.seq,m.shot_id",userId,id).stream().map(r->new Member(r.get("shot_id",String.class),r.get("seq",Integer.class),r.get("outcome",String.class).toLowerCase(Locale.ROOT),
            r.get("reason",String.class)!=null?r.get("reason",String.class):r.get("task_id")==null && !r.get("outcome",String.class).equals("BLOCKED")?"任务已移除":null,
            r.get("task_id",String.class),r.get("status")==null?(r.get("outcome",String.class).equals("BLOCKED")?"blocked":"canceled"):r.get("status",String.class).toLowerCase(Locale.ROOT),r.get("dispatch_blocked",String.class))).toList();
        var p=new Progress(members.size(),count(members,"accepted",true),count(members,"reused",true),count(members,"blocked",true),count(members,"queued",false),count(members,"running",false),count(members,"succeeded",false),count(members,"failed",false),count(members,"unknown",false),count(members,"canceled",false),(int)members.stream().filter(m->m.dispatchBlocked()!=null).count());
        String state=p.queued()+p.running()==0?"complete":p.paused()>0 && p.running()==0 && p.paused()==p.queued()?"paused":"running";
        return new View(Long.toString(id),batch.get("episode_id",String.class),batch.get("type",String.class).toLowerCase(Locale.ROOT),batch.get("request_key",String.class),state,p,members);
    }
    private int count(List<Member> members,String value,boolean outcome) { return (int)members.stream().filter(m->value.equals(outcome?m.outcome():m.state())).count(); }
    @Transactional(readOnly=true)
    public List<View> list(long userId,long episodeId,Type type) {
        scope.episode(userId,episodeId);
        return db.fetch("select id from generation_batch where user_id=? and episode_id=? and type=? order by id desc limit 10",userId,episodeId,type.name()).stream().map(r->get(userId,r.get("id",Long.class))).toList();
    }
}
