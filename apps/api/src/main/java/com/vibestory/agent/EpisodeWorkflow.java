package com.vibestory.agent;

import com.vibestory.agent.EpisodePlanSkill.Next;
import com.vibestory.agent.EpisodePlanSkill.StoryContext;
import com.vibestory.agent.EpisodePlanSkill.StoryboardContext;
import com.vibestory.agent.EpisodePlanSkill.VideoContext;
import com.vibestory.batch.GenerationBatch;
import com.vibestory.breakdown.BreakdownService;
import com.vibestory.common.ApiException;
import com.vibestory.common.ScopeGuard;
import com.vibestory.image.ImageTaskService;
import com.vibestory.shot.ShotService;
import com.vibestory.task.TaskService;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EpisodeWorkflow {
    public record EpisodePlan(String projectId, String episodeId, long storyRevision, String breakdownTaskId,
                              StoryContext story, StoryboardContext storyboard, VideoContext video, Next next) {}

    record Target(long id, boolean confirmed, boolean hasImage, String firstFrameId, boolean hasVideo,
                  String imageState, String videoState) {}

    private final DSLContext db;
    private final ScopeGuard scope;
    private final BreakdownService storyAgent;
    private final ShotService shots;
    private final ImageTaskService images;
    private final TaskService videos;
    private final GenerationBatch batches;

    public EpisodeWorkflow(DSLContext db, ScopeGuard scope, BreakdownService storyAgent, ShotService shots,
                           ImageTaskService images, TaskService videos, GenerationBatch batches) {
        this.db = db;
        this.scope = scope;
        this.storyAgent = storyAgent;
        this.shots = shots;
        this.images = images;
        this.videos = videos;
        this.batches = batches;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public EpisodePlan plan(long user, long episode) {
        var source = db.fetchOne("""
            select e.project_id, e.story_revision,
                   char_length(regexp_replace(e.story_content, '^[[:space:]]+|[[:space:]]+$', '', 'g')) >= 20 as story_ready,
                   breakdown.id as breakdown_id, breakdown.status
            from episode e
            left join lateral (
                select id, status from task
                where episode_id=e.id and user_id=e.user_id and type='BREAKDOWN'
                  and input_snapshot->>'storyRevision'=e.story_revision::text
                order by id desc limit 1
            ) breakdown on true
            where e.id=? and e.user_id=?
            """, episode, user);
        if (source == null) throw ApiException.notFound();
        var targets = targets(user, episode);
        var story = new StoryContext(source.get("story_ready", Boolean.class), source.get("status", String.class));
        var storyboard = new StoryboardContext(targets.size(), count(targets, t -> !t.confirmed()),
            count(targets, t -> !t.hasImage()), count(targets, t -> active(t.imageState())),
            count(targets, t -> !t.hasImage() && issue(t.imageState())));
        var video = new VideoContext(count(targets, t -> !t.hasVideo()),
            count(targets, t -> !t.hasVideo() && t.firstFrameId() == null), count(targets, t -> active(t.videoState())),
            count(targets, t -> !t.hasVideo() && issue(t.videoState())));
        return new EpisodePlan(source.get("project_id", String.class), Long.toString(episode),
            source.get("story_revision", Long.class), source.get("breakdown_id", String.class),
            story, storyboard, video, EpisodePlanSkill.next(story, storyboard, video));
    }

    List<Target> targets(long user, long episode) {
        return db.fetch("""
            select s.id, s.status,
                   exists(select 1 from shot_image i where i.shot_id=s.id and i.visible_in_storyboard and i.status='READY') as has_image,
                   (select i.id from shot_image i where i.shot_id=s.id and i.selected and i.status='READY') as first_frame_id,
                   exists(select 1 from video v where v.shot_id=s.id and v.episode_id=s.episode_id) as has_video,
                   image.status as image_state, video.status as video_state
            from shot s
            left join lateral (
                select t.status from task t where t.user_id=s.user_id and t.episode_id=s.episode_id and t.target_type='SHOT'
                    and t.target_id=s.id and t.type='IMAGE' and t.storyboard_variant_revision_id is null
                order by (t.status in ('QUEUED','RUNNING')) desc, t.id desc limit 1
            ) image on true
            left join lateral (
                select t.status from task t where t.user_id=s.user_id and t.episode_id=s.episode_id and t.target_type='SHOT'
                    and t.target_id=s.id and t.type='VIDEO'
                order by (t.status in ('QUEUED','RUNNING')) desc, t.id desc limit 1
            ) video on true
            where s.episode_id=? and s.user_id=? order by s.seq
            """, episode, user).map(row -> new Target(row.get("id", Long.class), "CONFIRMED".equals(row.get("status", String.class)),
                row.get("has_image", Boolean.class), row.get("first_frame_id", String.class), row.get("has_video", Boolean.class),
                row.get("image_state", String.class), row.get("video_state", String.class)));
    }

    private static int count(List<Target> targets, Predicate<Target> predicate) {
        return (int) targets.stream().filter(predicate).count();
    }

    private static boolean active(String state) {
        return "QUEUED".equals(state) || "RUNNING".equals(state);
    }

    private static boolean issue(String state) {
        return "FAILED".equals(state) || "UNKNOWN".equals(state);
    }

    @Transactional
    public TaskService.Submission breakdown(long user, long episode, boolean replaceExisting) {
        return storyAgent.submit(user, episode, replaceExisting);
    }

    @Transactional
    public ShotService.View shotStatus(long user, long shot, String status) {
        return shots.status(user, shot, status);
    }

    @Transactional
    public List<ShotService.View> confirmShots(long user, long episode) {
        return shots.confirmAll(user, episode);
    }

    @Transactional
    public TaskService.Submission generateShot(long user, long shot, GenerationBatch.Type type, String nonce, boolean rejectConflictingActive) {
        return type == GenerationBatch.Type.IMAGE
            ? images.submitBatchMember(user, shot, nonce, rejectConflictingActive)
            : videos.submitBatchMember(user, shot, nonce, rejectConflictingActive);
    }

    @Transactional
    public void selectFrame(long user, long shot, long image) {
        images.select(user, shot, image);
    }

    @Transactional
    public GenerationBatch.View generateIndependentBatch(long user, long episode, GenerationBatch.Type type,
                                                          String key, List<Long> ids, boolean regenerate) {
        var batch = batches.submit(user, episode, type, key, ids, regenerate);
        if (type == GenerationBatch.Type.IMAGE && !regenerate) linkImageBatch(user, episode, batch);
        return batch;
    }

    @Transactional
    public List<GenerationBatch.View> generateMissingVideos(long user, long episode, String key) {
        scope.lockEpisode(user, episode);
        var ids = targets(user, episode).stream()
            .filter(t -> t.confirmed() && t.firstFrameId() != null && !t.hasVideo() && !active(t.videoState()) && !issue(t.videoState()))
            .map(Target::id).toList();
        return generateBatches(user, episode, GenerationBatch.Type.VIDEO, key, ids);
    }

    @Transactional
    public List<GenerationBatch.View> generateBatches(long user, long episode, GenerationBatch.Type type,
                                                       String key, List<Long> ids) {
        var submitted = new ArrayList<GenerationBatch.View>();
        for (int first = 0; first < ids.size(); first += 200)
            submitted.add(batches.submit(user, episode, type, key + "-" + first,
                ids.subList(first, Math.min(first + 200, ids.size())), false));
        return List.copyOf(submitted);
    }

    List<Target> matchingShots(long user, Record run) {
        long episode = run.get("episode_id", Long.class);
        var revision = db.fetchOne("select story_revision from episode where id=? and user_id=?", episode, user);
        if (revision == null || !revision.get("story_revision", Long.class).equals(run.get("story_revision", Long.class))) return List.of();
        var current = targets(user, episode);
        return Arrays.asList(run.get("shot_ids", Long[].class)).equals(current.stream().map(Target::id).toList()) ? current : List.of();
    }

    private void linkImageBatch(long user, long episode, GenerationBatch.View batch) {
        // GenerationBatch holds the project and episode locks until this association commits.
        var targets = targets(user, episode);
        var ids = targets.stream().map(Target::id).toList();
        var members = batch.members().stream().map(member -> Long.parseLong(member.shotId())).toList();
        var confirmed = targets.stream().filter(Target::confirmed).map(Target::id).collect(Collectors.toSet());
        if (!confirmed.containsAll(members)) return;
        long batchId = Long.parseLong(batch.id());
        var runs = db.fetch("""
            select w.id, w.batch_ids
            from agent_workflow w join agent_conversation c on c.id=w.conversation_id
            join episode e on e.id=w.episode_id and e.user_id=c.user_id
            join task t on t.id=w.breakdown_task_id and t.episode_id=e.id and t.user_id=e.user_id
            where e.id=? and e.user_id=? and w.confirmed_at is null and t.status='SUCCEEDED'
              and t.input_snapshot->>'storyRevision'=e.story_revision::text
              and array(select value::bigint from jsonb_array_elements_text(t.output_snapshot->'shotIds'))=cast(? as bigint[])
              and not exists (select 1 from generation_batch b where b.id=? and b.created_at < t.created_at)
            """, episode, user, ids.toArray(Long[]::new), batchId);
        for (var run : runs) {
            var linked = run.get("batch_ids", Long[].class);
            Set<Long> covered = new HashSet<>(coveredShots(linked));
            if (members.stream().anyMatch(covered::contains)) continue;
            covered.addAll(members);
            boolean complete = targets.stream().allMatch(t -> t.confirmed() && (t.hasImage() || covered.contains(t.id())));
            db.execute("""
                update agent_workflow set batch_ids=array_append(batch_ids, ?),
                    confirmed_at=case when ? then clock_timestamp() else confirmed_at end
                where id=?
                """, batchId, complete, run.get("id", UUID.class));
        }
    }

    List<Long> coveredShots(Long[] batchIds) {
        return db.fetch("select shot_id from generation_batch_member where batch_id=any(cast(? as bigint[]))",
            (Object) batchIds).getValues("shot_id", Long.class);
    }
}
