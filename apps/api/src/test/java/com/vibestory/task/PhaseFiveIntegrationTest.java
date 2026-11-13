package com.vibestory.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vibestory.auth.*;
import com.vibestory.common.ApiException;
import com.vibestory.image.ImageTaskService;
import com.vibestory.provider.*;
import java.util.Map;
import org.jooq.DSLContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"vibestory.worker.enabled=false","vibestory.worker.lease-duration=5s","vibestory.worker.heartbeat-interval=200ms","vibestory.mock.delay=0ms"})
@AutoConfigureMockMvc
class PhaseFiveIntegrationTest extends PostgresSupport {
    @Autowired DSLContext db;
    @Autowired TaskService tasks;
    @Autowired TaskStore store;
    @Autowired ImageTaskService images;
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder passwords;
    @Autowired ObjectMapper json;
    private Viewer alice,bob;
    private long project,episode,shot,first,next;
    @BeforeEach void setup(){
        resetDatabase(db);
        alice=account("alice@phase5.test");bob=account("bob@phase5.test");
        project=db.fetchOne("insert into project(user_id,name,visual_type) values (?,'视频测试','realistic') returning id",alice.id()).get(0,Long.class);
        episode=db.fetchOne("insert into episode(user_id,project_id,episode_index,title) values (?,?,1,'第一集') returning id",alice.id(),project).get(0,Long.class);
        shot=db.fetchOne("insert into shot(user_id,project_id,episode_id,seq,status) values (?,?,?,1,'CONFIRMED') returning id",alice.id(),project,episode).get(0,Long.class);
        first=image(true,1);next=image(false,2);
    }
    @Test void optionalNonceDeduplicatesAndExplicitRegenerationCreatesAnotherTask() throws Exception {
        mvc.perform(post("/api/shots/"+shot+"/video-tasks").with(user(alice)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isAccepted());
        long id=submit(); assertThat(db.fetchOne("select count(*) from task").get(0,Integer.class)).isEqualTo(1);
        assertThat(Long.parseLong(tasks.submit(alice.id(),shot,"new-generation").taskId())).isNotEqualTo(id);
    }
    @Test void persistedExternalIdResumesSameAttemptAfterCrashAndFencesOldOwner(){
        long id=remote();var old=store.claim("old").orElseThrow();assertThat(store.checkpoint(old,old.version(),"42")).isTrue();expire(id);
        var resumed=store.claim("replacement").orElseThrow();
        assertThat(resumed.externalTaskId()).isEqualTo("42");assertThat(resumed.attemptNo()).isEqualTo(1);assertThat(resumed.attemptId()).isEqualTo(old.attemptId());
        assertThat(store.heartbeat(old,old.version())).isEmpty();assertThat(store.checkpoint(old,old.version(),"99")).isFalse();
        assertThat(store.succeed(old,old.version(),new VideoProvider.Result("late.mp4",6000))).isFalse();
        assertThat(store.succeed(resumed,resumed.version(),new VideoProvider.Result("ready.mp4",6000))).isTrue();
        assertThat(tasks.get(alice.id(),id).attempts()).isEqualTo(1);assertThat(tasks.get(alice.id(),id).ready()).isTrue();
    }
    @Test void missingExternalIdNeverAutomaticallyResubmitsPaidVideo(){
        long id=remote();store.claim("crashed-before-checkpoint").orElseThrow();expire(id);
        assertThat(store.claim("replacement")).isEmpty();assertThat(tasks.get(alice.id(),id).state()).isEqualTo("unknown");
        assertThat(tasks.get(alice.id(),id).resumable()).isFalse();assertThatThrownBy(this::submit).isInstanceOf(ApiException.class);
    }
    @Test void mockOnlyWorkerDoesNotClaimPaidVideoTasks(){
        long id=remote();
        assertThat(store.claim("mock-worker",new TaskStore.Providers("mock","mock","mock"))).isEmpty();
        assertThat(tasks.get(alice.id(),id).attempts()).isZero();
        assertThat(store.claim("minimax-worker",new TaskStore.Providers("mock","mock","minimax"))).isPresent();
    }
    @Test void queryFailureCanResumeOriginalAttemptAfterExplicitAcknowledgement(){
        long id=remote();var lease=store.claim("query").orElseThrow();store.checkpoint(lease,lease.version(),"42");
        store.fail(lease,lease.version(),new ProviderException("VIDEO_QUERY_UNKNOWN","查询中断",false,true));
        assertThat(tasks.get(alice.id(),id).resumable()).isTrue();assertThatThrownBy(()->tasks.retry(alice.id(),id,false)).isInstanceOf(ApiException.class);
        db.execute("update shot set status='DRAFT' where id=?",shot); // Querying an already submitted video does not generate a new video.
        tasks.retry(alice.id(),id,true);var resumed=store.claim("resume-query").orElseThrow();
        assertThat(resumed.attemptNo()).isEqualTo(1);assertThat(resumed.externalTaskId()).isEqualTo("42");
    }
    @Test void knownExternalFailureCreatesNewAttemptOnlyWhenRetried(){
        long id=remote();var lease=store.claim("failed").orElseThrow();store.checkpoint(lease,lease.version(),"42");
        store.fail(lease,lease.version(),new ProviderException("VIDEO_GENERATION_FAILED","生成失败",true,false));
        tasks.retry(alice.id(),id,false);var retry=store.claim("retry").orElseThrow();
        assertThat(retry.attemptNo()).isEqualTo(2);assertThat(retry.externalTaskId()).isNull();
    }
    @Test void selectedImageChangeDoesNotRewritePendingOrReadySourceAndDraftCannotRetry() throws Exception {
        long id=submit();var lease=store.claim("first-frame").orElseThrow();images.select(alice.id(),shot,next);
        assertThat(tasks.get(alice.id(),id).basedOn()).isEqualTo(Long.toString(first));
        mvc.perform(get("/api/shots/"+shot).with(user(alice))).andExpect(jsonPath("$.video.pendingBasedOn").value(Long.toString(first)));
        store.succeed(lease,lease.version(),new VideoProvider.Result("original.mp4",3000));
        long regenerated=Long.parseLong(tasks.submit(alice.id(),shot,"new").taskId());var second=store.claim("second").orElseThrow();
        mvc.perform(get("/api/shots/"+shot).with(user(alice))).andExpect(jsonPath("$.video.ready").value(true))
            .andExpect(jsonPath("$.video.basedOn").value(Long.toString(first))).andExpect(jsonPath("$.video.pendingBasedOn").value(Long.toString(next)));
        store.fail(second,second.version(),new ProviderException("FAIL","可重试失败",true,false));
        db.execute("update shot set status='DRAFT' where id=?",shot);
        assertThatThrownBy(()->tasks.retry(alice.id(),regenerated,false)).isInstanceOf(ApiException.class);
    }
    @Test void runtimeConfigurationIsReadOnlyAndDoesNotExposeCredentials() throws Exception {
        mvc.perform(get("/api/runtime").with(user(alice))).andExpect(status().isOk())
            .andExpect(jsonPath("$.video.provider").value("mock")).andExpect(jsonPath("$.video.durationMs").value(3000))
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("apiKey"))))
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("baseUrl"))));
        mvc.perform(get("/api/runtime")).andExpect(status().isUnauthorized());
        long id=remote();var lease=store.claim("private").orElseThrow();store.checkpoint(lease,lease.version(),"42");
        mvc.perform(get("/api/tasks/"+id).with(user(bob))).andExpect(status().isNotFound());
    }
    private long image(boolean selected,int version){return db.fetchOne("insert into shot_image(shot_id,episode_id,selected,version,storage_key) values (?,?,?,?,'mock/frame') returning id",shot,episode,selected,version).get(0,Long.class);}
    private long submit(){return Long.parseLong(tasks.submit(alice.id(),shot,null).taskId());}
    private long remote(){long id=submit();db.execute("update task set provider='minimax' where id=?",id);return id;}
    private void expire(long id){db.execute("update task set lease_until=now()-interval '1 second' where id=?",id);}
    private Viewer account(String email){var u=users.saveAndFlush(new User(email,passwords.encode("test-password")));return new Viewer(u.getId(),email,u.getPasswordHash());}
}
