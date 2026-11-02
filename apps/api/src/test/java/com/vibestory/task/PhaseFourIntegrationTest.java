package com.vibestory.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vibestory.asset.AssetService;
import com.vibestory.auth.*;
import com.vibestory.common.ApiException;
import com.vibestory.image.ImageTaskService;
import com.vibestory.provider.*;
import com.vibestory.storage.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import org.jooq.DSLContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"vibestory.worker.enabled=false","vibestory.worker.lease-duration=5s","vibestory.worker.heartbeat-interval=200ms","vibestory.mock.delay=0ms"})
@AutoConfigureMockMvc
class PhaseFourIntegrationTest extends PostgresSupport {
    @Autowired DSLContext db;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder passwords;
    @Autowired AssetService assets;
    @Autowired ImageTaskService images;
    @Autowired MockImageProvider mock;
    @Autowired TaskService tasks;
    @Autowired TaskStore store;
    @Autowired TaskWorker worker;
    @Autowired LocalStorage storage;
    @Autowired StorageCleanup cleanup;
    private Viewer alice,bob;
    private long project,episode,shot,second,character,scene;

    @BeforeEach void setup() throws Exception {
        db.execute("truncate app_user,storage_cleanup restart identity cascade");
        alice=account("alice@phase4.test"); bob=account("bob@phase4.test");
        project=postJson("/api/projects",Map.of("name","第四阶段","visualType","realistic"),201).path("id").asLong();
        episode=postJson("/api/projects/"+project+"/episodes",Map.of(),201).path("id").asLong();
        shot=createShot(episode,1); second=createShot(episode,2);
        assets.bind(alice.id(),project,shot,"雨夜街道",List.of("沈夜"));
        assets.bind(alice.id(),project,second,"雨夜街道",List.of("沈夜"));
        character=db.fetchOne("select id from character_asset").get(0,Long.class);
        scene=db.fetchOne("select id from scene_asset").get(0,Long.class);
    }

    @Test void uploadAndGeneratedVersionsArePersistentAndNewVersionsBecomeSelected() throws Exception {
        var first=upload(true,character,"初始定妆");
        var next=upload(true,character,"改版");
        assertThat(assets.list(alice.id(),project,true).getFirst().selectedVersionId()).isEqualTo(next.id());
        assets.select(alice.id(),true,character,Long.parseLong(first.id()));
        long task=Long.parseLong(images.submitAsset(alice.id(),true,character,null).taskId());
        assertThat(db.fetchOne("select episode_id from task where id=?",task).get(0)).isNull();
        worker.dispatchAvailable();
        await().atMost(Duration.ofSeconds(8)).untilAsserted(()->assertThat(tasks.get(alice.id(),task).ready()).isTrue());
        var card=assets.list(alice.id(),project,true).getFirst();
        assertThat(card.versions()).hasSize(3);
        assertThat(card.selectedVersionId()).isEqualTo(card.versions().getLast().id());
        assertThat(card.versions().getLast().source()).isEqualTo("generated");
        assertThat(card.usage().shots()).isEqualTo(2);
        assertThat(card.usage().episodes()).containsExactly(1);
        assertThat(assets.global(alice.id(),true).getFirst().projectName()).isEqualTo("第四阶段");
        assertThat(assets.global(bob.id(),true)).isEmpty();
        mvc.perform(get(card.versions().getLast().url()).with(user(alice))).andExpect(status().isOk()).andExpect(content().contentType("image/png"));
        mvc.perform(get(first.url()).with(user(bob))).andExpect(status().isNotFound());
    }

    @Test void generationFreezesCharacterAndSceneAtSubmissionAndNeverRewritesHistory() throws Exception {
        var v1=upload(true,character,"人物 v1"); var s1=upload(false,scene,"场景 v1");
        long task=submit(shot,null);
        var lease=store.claim("before-change").orElseThrow();
        var frozen=images.input(lease.input());
        assertThat(frozen.sources().characters().getFirst().versionId()).isEqualTo(v1.id());
        assertThat(frozen.sources().scene().versionId()).isEqualTo(s1.id());
        assertThat(frozen.references()).extracting(ImageProvider.Reference::versionId).containsExactlyInAnyOrder(v1.id(),s1.id());
        var v2=upload(true,character,"人物 v2"); upload(false,scene,"场景 v2");
        assertThat(store.succeedImage(lease,lease.version(),mock.generate(frozen,lease.signature()))).isTrue();
        var old=readShot().path("images").get(0);
        assertThat(old.path("inputSnapshot").path("characters").get(0).path("versionId").asText()).isEqualTo(v1.id());
        assertThat(old.path("inputSnapshot").path("scene").path("versionId").asText()).isEqualTo(s1.id());
        long regenerated=submit(shot,"new-version");
        var next=store.claim("after-change").orElseThrow();
        assertThat(images.input(next.input()).sources().characters().getFirst().versionId()).isEqualTo(v2.id());
        assertThat(store.succeedImage(next,next.version(),mock.generate(images.input(next.input()),next.signature()))).isTrue();
        assertThat(readShot().path("images").get(0)).isEqualTo(old);
        assertThat(readShot().path("selectedImageId").asText()).isEqualTo(old.path("id").asText());
        assertThat(tasks.get(alice.id(),regenerated).ready()).isTrue();
        assertThat(readShot().toString()).doesNotContain("stale");
        assertThat(tasks.get(alice.id(),task).imageGuidance().mode()).isEqualTo("full");
    }

    @Test void concurrentDuplicateSubmissionHasOneTaskAndRegenerationIsExplicit() throws Exception {
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var futures=new ArrayList<Future<Long>>();
            for(int n=0;n<10;n++) futures.add(pool.submit(()->submit(shot,null)));
            var ids=new ArrayList<Long>(); for(var future:futures) ids.add(future.get(5,TimeUnit.SECONDS));
            assertThat(ids).containsOnly(ids.getFirst());
        }
        assertThat(count("task")).isEqualTo(1);
        assertThat(submit(shot,"new-candidate")).isNotEqualTo(db.fetchOne("select min(id) from task").get(0,Long.class));
        assertThat(count("task")).isEqualTo(2);
        var lease=store.claim("deterministic").orElseThrow();
        var input=images.input(lease.input());
        assertThat(mock.generate(input,lease.signature()).bytes()).isEqualTo(mock.generate(input,lease.signature()).bytes());
        assertThat(store.succeedImage(lease,lease.version(),mock.generate(input,lease.signature()))).isTrue();
        assertThat(count("shot_image")).isEqualTo(1);
    }

    @Test void selectionBelongsToShotAndDoesNotChangeExistingVideoSource() throws Exception {
        finish(submit(shot,null));
        long first=readShot().path("selectedImageId").asLong();
        long video=Long.parseLong(tasks.submit(alice.id(),shot,"video").taskId());
        var videoLease=store.claim("video").orElseThrow();
        finish(submit(shot,"next"));
        long next=readShot().path("images").get(1).path("id").asLong();
        images.select(alice.id(),shot,next);
        assertThat(store.succeed(videoLease,videoLease.version(),new VideoProvider.Result("mock/video",3000))).isTrue();
        assertThat(tasks.get(alice.id(),video).basedOn()).isEqualTo(Long.toString(first));
        assertThat(readShot().path("video").path("basedOn").asLong()).isEqualTo(first);
        assertThatThrownBy(()->images.select(alice.id(),second,next)).isInstanceOf(ApiException.class);
        mvc.perform(put("/api/shots/"+shot+"/selected-image").with(user(alice)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"imageId\":"+first+"}")).andExpect(status().isNoContent());
    }

    @Test void draftBlocksGenerationAndScopedPathsRejectOtherUser() throws Exception {
        db.execute("update shot set status='DRAFT' where id=?",shot);
        postJson("/api/shots/"+shot+"/image-tasks",Map.of(),409);
        db.execute("update shot set status='CONFIRMED' where id=?",shot);
        var version=upload(true,character,"v1");
        finish(submit(shot,null));
        var image=readShot().path("images").get(0);
        for(String path:List.of(image.path("url").asText(),version.url()))
            mvc.perform(get(path).with(user(bob))).andExpect(status().isNotFound());
        for(String path:List.of("/api/shots/"+shot+"/image-tasks","/api/assets/characters/"+character+"/image-tasks"))
            mvc.perform(post(path).with(user(bob)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isNotFound());
        mvc.perform(put("/api/assets/characters/"+character+"/selected-version").with(user(bob)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"versionId\":"+version.id()+"}")).andExpect(status().isNotFound());
        assertThatThrownBy(()->assets.select(alice.id(),false,scene,Long.parseLong(version.id()))).isInstanceOf(ApiException.class);
    }

    @Test void missingVersionsAndLegacyImagesKeepMissingProvenance() throws Exception {
        db.execute("insert into shot_image(shot_id,episode_id,storage_key) values (?,?,'legacy/image')",shot,episode);
        assertThat(readShot().path("images").get(0).has("inputSnapshot")).isFalse();
        long task=submit(shot,null);
        var input=images.input(db.fetchOne("select input_snapshot from task where id=?",task).get(0,String.class));
        assertThat(input.sources().characters().getFirst().versionId()).isNull();
        assertThat(input.sources().scene().versionId()).isNull();
        upload(true,character,"after submission"); finish(task);
        assertThat(readShot().path("images").get(0).has("inputSnapshot")).isFalse();
        assertThat(readShot().path("images").get(1).path("inputSnapshot").path("characters").get(0).has("versionId")).isFalse();
    }

    @Test void pollingReturnsImageStatesAndFiltersMatchPageAndOtherShotsStayUntouched() throws Exception {
        long task=submit(shot,null);
        mvc.perform(get("/api/episodes/"+episode+"/shots?taskActive=true").with(user(alice))).andExpect(jsonPath("$[0].imageState").value("queued"));
        var lease=store.claim("failure").orElseThrow();
        assertThat(readShot().path("imageState").asText()).isEqualTo("running");
        store.fail(lease,lease.version(),new ProviderException("FIXTURE","可重试",true,false));
        assertThat(readShot().path("imageState").asText()).isEqualTo("failed");
        tasks.retry(alice.id(),task,false); finish(task);
        mvc.perform(get("/api/episodes/"+episode+"/shots?filter=todo").with(user(alice))).andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].id").value(Long.toString(second)));
        assertThat(tasks.get(alice.id(),task).attempts()).isEqualTo(2);
    }

    @Test void realExpiredLeaseIsUnknownAndOrdinarySubmissionCannotBypassAcknowledgement() throws Exception {
        long task=submit(shot,null);
        db.execute("update task set provider='minimax' where id=?",task);
        var old=store.claim("paid").orElseThrow(); expire(task);
        assertThat(store.claim("replacement")).isEmpty();
        assertThat(tasks.get(alice.id(),task).state()).isEqualTo("unknown");
        assertThatThrownBy(()->submit(shot,null)).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->tasks.retry(alice.id(),task,false)).isInstanceOf(ApiException.class);
        tasks.retry(alice.id(),task,true);
        assertThat(store.claim("acknowledged").orElseThrow().attemptNo()).isEqualTo(2);
        assertThat(store.succeedImage(old,old.version(),mock.generate(images.input(old.input()),old.signature()))).isFalse();
    }

    @Test void mockExpiredLeaseReplaysAndOldOwnerCannotPublish() throws Exception {
        long task=submit(shot,null); var old=store.claim("old").orElseThrow(); expire(task);
        var next=store.claim("new").orElseThrow();
        assertThat(store.succeedImage(old,old.version(),mock.generate(images.input(old.input()),old.signature()))).isFalse();
        assertThat(store.succeedImage(next,next.version(),mock.generate(images.input(next.input()),next.signature()))).isTrue();
        assertThat(count("shot_image")).isEqualTo(1);
        assertThat(tasks.get(alice.id(),task).history()).extracting(TaskService.Attempt::state).containsExactly("abandoned","succeeded");
    }

    @Test void rebreakdownCancelsPendingImagesAndCleansCompletedImagesButPreservesAssetVersions() throws Exception {
        upload(true,character,"保留基准"); finish(submit(shot,null));
        String key=db.fetchOne("select storage_key from shot_image").get(0,String.class);
        long pending=submit(shot,"pending"); var lease=store.claim("late").orElseThrow();
        var drafts=List.of(new TextProvider.ShotDraft("替换","","新场景","中景","固定",3000,List.of("沈夜")));
        // Replace through the actual breakdown boundary, which locks the project and fences all old shot tasks.
        mvc.perform(put("/api/episodes/"+episode+"/story").with(user(alice)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(Map.of("story","新的正文，人物走进雨夜街道。".repeat(10))))).andExpect(status().isOk());
        postJson("/api/episodes/"+episode+"/breakdowns",Map.of("replaceExisting",true),202);
        var breakdown=store.claim("replace").orElseThrow();
        store.succeedBreakdown(breakdown,breakdown.version(),new TextProvider.Result(drafts,null,"fixture"));
        assertThat(store.succeedImage(lease,lease.version(),mock.generate(images.input(lease.input()),lease.signature()))).isFalse();
        assertThat(tasks.get(alice.id(),pending).errorCode()).isEqualTo("SHOTS_REPLACED");
        cleanup.sweep(); assertThat(storage.resource(key).exists()).isFalse();
        assertThat(count("asset_version")).isEqualTo(1);
    }

    @Test void episodeDeletionCleansItsImagesAndDoesNotRemoveProjectAssetTasksOrVersions() throws Exception {
        finish(submit(shot,null));
        String key=db.fetchOne("select storage_key from shot_image").get(0,String.class);
        long assetTask=Long.parseLong(images.submitAsset(alice.id(),true,character,null).taskId());
        mvc.perform(delete("/api/episodes/"+episode).with(user(alice)).with(csrf())).andExpect(status().isNoContent());
        finish(assetTask);
        cleanup.sweep(); assertThat(storage.resource(key).exists()).isFalse();
        assertThat(tasks.get(alice.id(),assetTask).ready()).isTrue();
        assertThat(assets.list(alice.id(),project,true).getFirst().versions()).hasSize(1);
    }

    @Test void projectDeletionFencesLateResultAndCleansUploadedAndGeneratedFiles() throws Exception {
        var version=upload(true,character,"v1"); finish(submit(shot,null));
        String uploadKey=db.fetchOne("select storage_key from asset_version").get(0,String.class);
        String key=db.fetchOne("select storage_key from shot_image").get(0,String.class);
        submit(shot,"pending"); var lease=store.claim("deleted").orElseThrow();
        mvc.perform(delete("/api/projects/"+project).with(user(alice)).with(csrf())).andExpect(status().isNoContent());
        assertThat(store.succeedImage(lease,lease.version(),mock.generate(images.input(lease.input()),lease.signature()))).isFalse();
        cleanup.sweep(); assertThat(storage.resource(key).exists()).isFalse(); assertThat(storage.resource(uploadKey).exists()).isFalse();
        assertThat(count("asset_version")).isZero();
        mvc.perform(get(version.url()).with(user(alice))).andExpect(status().isNotFound());
    }

    @Test void invalidUploadsAndCrossAssetSelectionAreRejectedAndOneSelectedIndexIsEnforced() throws Exception {
        var valid=upload(true,character,"v1"); upload(true,character,"v2");
        assertThatThrownBy(()->db.execute("update asset_version set is_selected=true where id=?",Long.parseLong(valid.id()))).isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
        mvc.perform(multipart("/api/assets/characters/"+character+"/versions").file(new MockMultipartFile("file","bad.png","image/png",new byte[]{1,2})).with(user(alice)).with(csrf())).andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/assets/characters/"+character+"/versions").file(file()).with(user(bob)).with(csrf())).andExpect(status().isNotFound());
        long task=submit(shot,null);
        assertThatThrownBy(()->db.execute("update task set provider='openai-compatible' where id=?",task)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test void databaseFailureRollsBackCompletionAndRemovesTheJustPublishedImage() throws Exception {
        long task=submit(shot,null); var lease=store.claim("rollback").orElseThrow();
        // Force a real database constraint error after the file write, without mocking transaction behavior.
        db.execute("insert into shot_image(shot_id,episode_id,storage_key,task_id) values (?,?,'fixture/duplicate',?)",shot,episode,task);
        var result=mock.generate(images.input(lease.input()),lease.signature());
        assertThatThrownBy(()->store.succeedImage(lease,lease.version(),result)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(tasks.get(alice.id(),task).state()).isEqualTo("running");
        String key="u/"+alice.id()+"/p/"+project+"/images/"+task+"/1/image.png";
        assertThat(storage.resource(key).exists()).isFalse();
        db.execute("delete from shot_image where task_id=?",task);
        assertThat(store.succeedImage(lease,lease.version(),result)).isTrue();
    }

    private long createShot(long ep,int seq) {
        return db.fetchOne("insert into shot(user_id,project_id,episode_id,seq,description,status) values (?,?,?,?,?,'CONFIRMED') returning id",alice.id(),project,ep,seq,"雨夜街道，人物在路灯下等待").get(0,Long.class);
    }
    private long submit(long target,String nonce) { return Long.parseLong(images.submitShot(alice.id(),target,nonce).taskId()); }
    private void finish(long task) throws Exception {
        var lease=store.claim("finish-"+task).orElseThrow(); assertThat(lease.taskId()).isEqualTo(task);
        assertThat(store.succeedImage(lease,lease.version(),mock.generate(images.input(lease.input()),lease.signature()))).isTrue();
    }
    private AssetService.Version upload(boolean character,long id,String label) throws Exception { return assets.upload(alice.id(),character,id,label,file()); }
    private MockMultipartFile file() throws Exception {
        var bytes=new ByteArrayOutputStream(); ImageIO.write(new BufferedImage(32,32,BufferedImage.TYPE_INT_RGB),"png",bytes);
        return new MockMultipartFile("file","reference.png","image/png",bytes.toByteArray());
    }
    private JsonNode readShot() throws Exception { return json.readTree(mvc.perform(get("/api/shots/"+shot).with(user(alice))).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray()); }
    private JsonNode postJson(String path,Object body,int expected) throws Exception { return json.readTree(mvc.perform(post(path).with(user(alice)).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body))).andExpect(status().is(expected)).andReturn().getResponse().getContentAsByteArray()); }
    private Viewer account(String email) { var u=users.saveAndFlush(new User(email,passwords.encode("test-password"))); return new Viewer(u.getId(),email,u.getPasswordHash()); }
    private void expire(long id) { db.execute("update task set lease_until=now()-interval '1 second' where id=?",id); }
    private int count(String table) { return db.fetchOne("select count(*) from "+table).get(0,Integer.class); }
}
