package com.vibestory.task;

import com.vibestory.auth.*;
import com.vibestory.batch.GenerationBatch;
import com.vibestory.common.ApiException;
import com.vibestory.dispatch.*;
import com.vibestory.image.ImageTaskService;
import java.util.*;
import java.util.concurrent.*;
import org.jooq.DSLContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"vibestory.worker.enabled=false","vibestory.mock.delay=0ms","vibestory.mock-only=true"})
@AutoConfigureMockMvc
class PhaseSevenIntegrationTest extends PostgresSupport {
    @Autowired DSLContext db;
    @Autowired GenerationBatch batches;
    @Autowired TaskService videos;
    @Autowired ImageTaskService images;
    @Autowired TaskStore tasks;
    @Autowired DispatchStore dispatch;
    @Autowired DispatchController metrics;
    @Autowired TransactionTemplate transactions;
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder passwords;
    Viewer alice,bob;
    long project,episode,a,b,draft;
    final TaskStore.Providers mocks=new TaskStore.Providers("mock","mock","mock");
    @BeforeEach void setup() {
        db.execute("truncate app_user,storage_cleanup restart identity cascade");
        alice=account("alice@phase7.test");bob=account("bob@phase7.test");
        project=db.fetchOne("insert into project(user_id,name,visual_type) values (?,'批次','realistic') returning id",alice.id()).get(0,Long.class);
        episode=db.fetchOne("insert into episode(user_id,project_id,episode_index,title) values (?,?,1,'一') returning id",alice.id(),project).get(0,Long.class);
        a=shot(1,"CONFIRMED");b=shot(2,"CONFIRMED");draft=shot(3,"DRAFT");
        db.execute("insert into shot_image(shot_id,episode_id,selected,storage_key) values (?,?,true,'frozen-first-frame')",a,episode);
    }
    @Test void batchesRecordPartialAcceptanceAndReuseWithoutRollingBackValidMembers() {
        var view=submit("mixed",GenerationBatch.Type.VIDEO,List.of(a,b,draft),false);
        assertThat(view.progress().accepted()).isEqualTo(1);assertThat(view.progress().blocked()).isEqualTo(2);
        assertThat(db.fetchCount(org.jooq.impl.DSL.table("task"))).isEqualTo(1);
        assertThat(db.fetchCount(org.jooq.impl.DSL.table("task_outbox"))).isEqualTo(1);
        var repeated=submit("second",GenerationBatch.Type.VIDEO,List.of(a),false);
        assertThat(repeated.progress().reused()).isEqualTo(1);
        assertThat(repeated.members().getFirst().taskId()).isEqualTo(view.members().getFirst().taskId());
        var conflict=submit("regen",GenerationBatch.Type.VIDEO,List.of(a),true);
        assertThat(conflict.progress().blocked()).isEqualTo(1);
        assertThat(conflict.members().getFirst().reason()).contains("不同输入");
    }
    @Test void imageBatchCanAcceptAfterAnEarlierBlockedMember() {
        var view=submit("image",GenerationBatch.Type.IMAGE,List.of(draft,a,b),false);
        assertThat(view.progress().accepted()).isEqualTo(2);assertThat(view.progress().blocked()).isEqualTo(1);
        assertThat(batches.list(alice.id(),episode,GenerationBatch.Type.IMAGE)).hasSize(1);
    }
    @Test void concurrentSameRequestHasOneBatchAndOneTaskPerEligibleMember() throws Exception {
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var futures=new ArrayList<Future<GenerationBatch.View>>();
            for(int n=0;n<10;n++) futures.add(pool.submit(()->submit("stable",GenerationBatch.Type.IMAGE,List.of(a,b),false)));
            var ids=new HashSet<String>();for(var f:futures) ids.add(f.get(10,TimeUnit.SECONDS).id());
            assertThat(ids).hasSize(1);
        }
        assertThat(db.fetchOne("select count(*) from task").get(0,Integer.class)).isEqualTo(2);
        assertThat(db.fetchOne("select count(*) from task_outbox").get(0,Integer.class)).isEqualTo(2);
        assertThatThrownBy(()->submit("stable",GenerationBatch.Type.IMAGE,List.of(a),false)).isInstanceOf(ApiException.class);
    }
    @Test void databaseFailureRollsBackBatchTasksMembersAndOutboxTogether() {
        db.execute("create function fail_phase7_member() returns trigger language plpgsql as $$ begin if NEW.seq=2 then raise exception 'injected rollback'; end if; return NEW; end $$");
        db.execute("create trigger fail_phase7 before insert on generation_batch_member for each row execute function fail_phase7_member()");
        try {
            assertThatThrownBy(()->submit("rollback",GenerationBatch.Type.IMAGE,List.of(a,b),false)).isInstanceOf(org.jooq.exception.DataAccessException.class);
            for(String table:List.of("generation_batch","generation_batch_member","task","task_outbox")) assertThat(db.fetchOne("select count(*) from "+table).get(0,Integer.class)).isZero();
        } finally { db.execute("drop trigger fail_phase7 on generation_batch_member");db.execute("drop function fail_phase7_member()"); }
    }
    @Test void batchApiChecksScopeAndKeyContent() throws Exception {
        var view=submit("scoped",GenerationBatch.Type.VIDEO,List.of(a),false);
        mvc.perform(get("/api/generation-batches/"+view.id()).with(user(bob))).andExpect(status().isNotFound());
        mvc.perform(get("/api/episodes/"+episode+"/generation-batches?type=VIDEO").with(user(bob))).andExpect(status().isNotFound());
        mvc.perform(post("/api/episodes/"+episode+"/generation-batches").with(user(alice)).with(csrf()).contentType("application/json").content("{\"type\":\"IMAGE\",\"requestKey\":\"http\",\"shotIds\":[\""+a+"\"]}")).andExpect(status().isAccepted()).andExpect(jsonPath("$.progress.accepted").value(1));
        assertThatThrownBy(()->submit("foreign",GenerationBatch.Type.IMAGE,List.of(a,999999L),false)).isInstanceOf(ApiException.class);
        assertThat(batches.list(alice.id(),episode,GenerationBatch.Type.VIDEO)).hasSize(1);
    }
    @Test void notificationClaimsOnceAndStaleOrTerminalMessagesDoNotCreateAttempts() {
        long id=video();var event=event(id);var lease=tasks.claimNotification(event,"first",mocks,true).orElseThrow();
        assertThat(tasks.claimNotification(event,"second",mocks,true)).isEmpty();
        assertThat(tasks.succeed(lease,lease.version(),new com.vibestory.provider.VideoProvider.Result("result",3000))).isTrue();
        assertThat(tasks.claimNotification(event,"third",mocks,true)).isEmpty();
        assertThat(db.fetchOne("select count(*) from task_attempt where task_id=?",id).get(0,Integer.class)).isEqualTo(1);
    }
    @Test void earlyMessageLeavesNoAttemptAndCurrentOutboxIsRepublishedWithoutRotatingGeneration() {
        long id=video();var event=event(id);db.execute("update task set not_before=now()+interval '1 hour' where id=?",id);
        assertThat(tasks.claimNotification(event,"early",mocks,true)).isEmpty();assertThat(dispatch.take()).isNull();
        db.execute("update task set not_before=now()-interval '1 second' where id=?",id);
        var publication=dispatch.take();assertThat(publication).isNotNull();dispatch.sent(publication);
        db.execute("update task_outbox set sent_at=now()-interval '1 hour' where task_id=?",id);dispatch.reconcileQueued();
        assertThat(dispatch.take().notification()).isEqualTo(event);
        assertThat(db.fetchOne("select attempt_count from task where id=?",id).get(0,Integer.class)).isZero();
    }
    @Test void lostPublisherBookkeepingAndExpiredRelayLeaseReusesIdentityAndFencesOldRelay() {
        video();var first=dispatch.take();assertThat(dispatch.take()).isNull();
        db.execute("update task_outbox set lease_until=now()-interval '1 second'");
        var next=dispatch.take();assertThat(next.notification()).isEqualTo(first.notification());
        assertThat(dispatch.sent(first)).isFalse();assertThat(dispatch.sent(next)).isTrue();
    }
    @Test void finitePublishBudgetBlocksCoordinatorUntilExplicitScopedResume() throws Exception {
        long id=video();var old=event(id);
        for(int n=0;n<5;n++) { var p=dispatch.take();assertThat(p).isNotNull();dispatch.publishFailed(p);db.execute("update task_outbox set available_at=now() where task_id=?",id); }
        dispatch.reconcileQueued();assertThat(dispatch.take()).isNull();assertThat(videos.get(alice.id(),id).attempts()).isZero();
        mvc.perform(post("/api/tasks/"+id+"/dispatch/resume").with(user(bob)).with(csrf())).andExpect(status().isNotFound());
        dispatch.resume(alice.id(),id);var next=dispatch.take().notification();
        assertThat(next.dispatchGeneration()).isEqualTo(old.dispatchGeneration()+1);
        assertThat(tasks.claimNotification(old,"stale",mocks,true)).isEmpty();
        assertThat(tasks.claimNotification(next,"new",mocks,true)).isPresent();
    }
    @Test void poisonTaskIsDurablyPausedAndDeadLetterDoesNotMeanGenerationFailure() {
        long id=video();var e=event(id);dispatch.deadLetter("a".repeat(64),e,"DEAD_LETTER");
        dispatch.reconcileQueued();assertThat(dispatch.take()).isNull();
        assertThat(videos.get(alice.id(),id).state()).isEqualTo("queued");
        assertThat(metrics.metrics(alice).deadLetters()).isEqualTo(1);assertThat(metrics.metrics(bob).deadLetters()).isZero();
        assertThat(metrics.metrics(alice).groups().getFirst().blocked()).isEqualTo(1);
        dispatch.resume(alice.id(),id);assertThat(dispatch.take()).isNotNull();
    }
    @Test void expiredMockIsRequeuedWithFencingAndNewAttemptOnlyOnNextClaim() {
        long id=video();var old=event(id);var lease=tasks.claimNotification(old,"crashed",mocks,true).orElseThrow();expire(id);
        tasks.recoverExpired(mocks);assertThat(videos.get(alice.id(),id).attempts()).isEqualTo(1);
        var next=event(id);assertThat(next.dispatchGeneration()).isEqualTo(old.dispatchGeneration()+1);
        assertThat(tasks.claimNotification(old,"old-event",mocks,true)).isEmpty();
        var resumed=tasks.claimNotification(next,"recovered",mocks,true).orElseThrow();assertThat(resumed.attemptNo()).isEqualTo(2);
        assertThat(tasks.succeed(lease,lease.version(),new com.vibestory.provider.VideoProvider.Result("old",3000))).isFalse();
    }
    @Test void externalIdentityRecoveryReusesAttemptAndUnknownIsNeverAutomaticallyReplayed() {
        long id=video();db.execute("update task set provider='minimax' where id=?",id);
        var live=new TaskStore.Providers("mock","mock","minimax");
        var lease=tasks.claimNotification(event(id),"remote",live,false).orElseThrow();assertThat(tasks.checkpoint(lease,lease.version(),"fixture-external-id")).isTrue();expire(id);
        tasks.recoverExpired(live);var resumed=tasks.claimNotification(event(id),"resume",live,false).orElseThrow();
        assertThat(resumed.attemptId()).isEqualTo(lease.attemptId());assertThat(resumed.externalTaskId()).isEqualTo("fixture-external-id");
        tasks.fail(resumed,resumed.version(),new com.vibestory.provider.ProviderException("UNKNOWN","fixture",false,true));
        tasks.recoverExpired(live);assertThat(dispatch.take()).isNull();
        assertThat(tasks.claimNotification(event(id),"forbidden",live,false)).isEmpty();
    }
    @Test void unsafeExpiredRealRequestWithoutIdentityBecomesUnknownAndMockWorkerIgnoresIt() {
        long id=video();db.execute("update task set provider='minimax' where id=?",id);
        var live=new TaskStore.Providers("mock","mock","minimax");var e=event(id);
        assertThatThrownBy(()->tasks.claimNotification(e,"mock",mocks,true)).isInstanceOf(IllegalArgumentException.class);
        tasks.claimNotification(e,"live",live,false).orElseThrow();expire(id);
        tasks.recoverExpired(mocks);assertThat(videos.get(alice.id(),id).state()).isEqualTo("running");
        tasks.recoverExpired(live);assertThat(videos.get(alice.id(),id).state()).isEqualTo("unknown");
        assertThatThrownBy(()->dispatch.resume(alice.id(),id)).isInstanceOf(ApiException.class);
    }
    @Test void identityScopeAndProviderMismatchCannotExecuteAndDeletedTaskIsDiscarded() {
        long id=video();var e=event(id);
        var wrong=new TaskNotification(1,e.eventId(),id,e.dispatchGeneration(),e.type(),alice.id(),project,episode+1);
        assertThatThrownBy(()->tasks.claimNotification(wrong,"bad-scope",mocks,true)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->tasks.claimNotification(e,"bad-lane",mocks,false)).isInstanceOf(IllegalArgumentException.class);
        transactions.executeWithoutResult(tx->{db.execute("delete from task_outbox where task_id=?",id);db.execute("delete from task where id=?",id);});
        assertThat(tasks.claimNotification(e,"deleted",mocks,true)).isEmpty();
    }
    @Test void retryRotatesDispatchAndOuterRollbackErasesRetryNotification() {
        long id=video();var first=event(id);var lease=tasks.claim("worker").orElseThrow();
        tasks.fail(lease,lease.version(),new com.vibestory.provider.ProviderException("RETRY","known",true,false));
        transactions.executeWithoutResult(tx->{videos.retry(alice.id(),id,false);assertThat(event(id).dispatchGeneration()).isEqualTo(2);tx.setRollbackOnly();});
        assertThat(event(id)).isEqualTo(first);assertThat(videos.get(alice.id(),id).state()).isEqualTo("failed");
        videos.retry(alice.id(),id,false);assertThat(event(id).dispatchGeneration()).isEqualTo(2);
    }
    @Test void bulkProgressUsesDurableTaskOutcomesAndFirstFrameSnapshotSurvivesSelectionChanges() {
        var view=submit("frozen",GenerationBatch.Type.VIDEO,List.of(a),false);long id=Long.parseLong(view.members().getFirst().taskId());
        long original=videos.input(db.fetchOne("select input_snapshot from task where id=?",id).get(0,String.class)).sourceImageId();
        db.execute("update shot_image set selected=false where shot_id=?",a);
        db.execute("insert into shot_image(shot_id,episode_id,version,selected,storage_key) values (?,?,2,true,'new-first')",a,episode);
        var lease=tasks.claimNotification(event(id),"finish",mocks,true).orElseThrow();tasks.succeed(lease,lease.version(),new com.vibestory.provider.VideoProvider.Result("done",3000));
        var current=batches.get(alice.id(),Long.parseLong(view.id()));assertThat(current.state()).isEqualTo("complete");assertThat(current.progress().succeeded()).isEqualTo(1);
        assertThat(db.fetchOne("select source_image_id from video where task_id=?",id).get(0,Long.class)).isEqualTo(original);
        var regen=submit("new-generation",GenerationBatch.Type.VIDEO,List.of(a),true);assertThat(regen.members().getFirst().taskId()).isNotEqualTo(Long.toString(id));
    }
    @Test void batchImageOutputKeepsSubmittedAssetVersionAfterBaselineChanges() {
        long character=db.fetchOne("insert into character_asset(user_id,project_id,name) values (?,?,'角色') returning id",alice.id(),project).get(0,Long.class);
        long version=db.fetchOne("insert into asset_version(user_id,project_id,character_id,source,label,is_selected,storage_key,media_type) values (?,?,?,'uploaded','v1',true,'baseline-one','image/png') returning id",alice.id(),project,character).get(0,Long.class);
        db.execute("insert into asset_ref(shot_id,project_id,role,character_id) values (?,?,'CHARACTER',?)",a,project,character);
        var view=submit("frozen-image",GenerationBatch.Type.IMAGE,List.of(a),false);long id=Long.parseLong(view.members().getFirst().taskId());
        db.execute("update asset_version set is_selected=false where id=?",version);
        db.execute("insert into asset_version(user_id,project_id,character_id,source,label,is_selected,storage_key,media_type) values (?,?,?,'uploaded','v2',true,'baseline-two','image/png')",alice.id(),project,character);
        var lease=tasks.claimNotification(event(id),"image",mocks,true).orElseThrow();
        assertThat(images.input(lease.input()).sources().characters().getFirst().versionId()).isEqualTo(Long.toString(version));
        assertThat(tasks.succeedImage(lease,lease.version(),new com.vibestory.provider.ImageProvider.Result(new byte[]{1},"image/png",null,"mock"))).isTrue();
        assertThat(db.fetchOne("select input_snapshot->'characters'->0->>'versionId' from shot_image where task_id=?",id).get(0,String.class)).isEqualTo(Long.toString(version));
    }
    @Test void multipleRelaysUseSeparateShortLeasesWithoutHoldingPublishingTransactions() throws Exception {
        video();videos.submit(alice.id(),a,"separate");
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var first=pool.submit(()->dispatch.take());var second=pool.submit(()->dispatch.take());
            var one=first.get(5,TimeUnit.SECONDS);var two=second.get(5,TimeUnit.SECONDS);
            assertThat(one.notification().eventId()).isNotEqualTo(two.notification().eventId());assertThat(dispatch.take()).isNull();
        }
    }
    private GenerationBatch.View submit(String key,GenerationBatch.Type type,List<Long> ids,boolean regen) { return batches.submit(alice.id(),episode,type,key,ids,regen); }
    private long video() { return Long.parseLong(videos.submit(alice.id(),a,null).taskId()); }
    private TaskNotification event(long id) { return dispatch.event(db.fetchOne("select o.*,t.type,t.user_id,t.project_id,t.episode_id from task_outbox o join task t on t.id=o.task_id and t.dispatch_generation=o.dispatch_generation where t.id=?",id)); }
    private void expire(long id) { db.execute("update task set lease_until=now()-interval '1 second' where id=?",id); }
    private long shot(int seq,String status) { return db.fetchOne("insert into shot(user_id,project_id,episode_id,seq,status,description) values (?,?,?,?,?,'私有提示词') returning id",alice.id(),project,episode,seq,status).get(0,Long.class); }
    private Viewer account(String email) { var u=users.saveAndFlush(new User(email,passwords.encode("test-password")));return new Viewer(u.getId(),u.getEmail(),u.getPasswordHash()); }
}
