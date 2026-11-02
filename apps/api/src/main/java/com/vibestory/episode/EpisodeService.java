package com.vibestory.episode;

import com.vibestory.common.ScopeGuard;
import com.vibestory.common.ApiException;
import com.vibestory.common.ProductionStatistics;
import com.vibestory.common.TextInput;
import com.vibestory.storage.StorageCleanup;
import java.util.List;
import org.jooq.DSLContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EpisodeService {
    private final EpisodeRepository episodes;
    private final ScopeGuard scope;
    private final ProductionStatistics statistics;
    private final StorageCleanup cleanup;
    private final DSLContext db;
    public EpisodeService(EpisodeRepository episodes, ScopeGuard scope, ProductionStatistics statistics, StorageCleanup cleanup, DSLContext db) {
        this.episodes = episodes; this.scope = scope; this.statistics = statistics; this.cleanup = cleanup; this.db = db;
    }

    @Transactional
    public Episode.View create(long userId, long projectId, String title) {
        scope.lockProject(userId, projectId);
        int index = episodes.maxIndex(projectId) + 1;
        String name = title == null ? "第 " + index + " 集" : TextInput.name(title, "集名");
        var episode = episodes.saveAndFlush(new Episode(userId, projectId, index, name));
        scope.touchProject(projectId, userId);
        return episode.view(new ProductionStatistics.Counts(0, 0, 0, 0));
    }

    @Transactional(readOnly = true)
    public List<Episode.View> list(long userId, long projectId) {
        scope.project(userId, projectId);
        return episodes.findByProjectIdAndUserIdOrderByEpisodeIndex(projectId, userId).stream()
            .map(episode -> view(userId, episode)).toList();
    }

    @Transactional(readOnly = true)
    public Episode.View get(long userId, long episodeId) {
        scope.episode(userId, episodeId);
        return view(userId, find(userId, episodeId));
    }

    @Transactional
    public Episode.View update(long userId, long episodeId, String title, String summary) {
        long projectId = scope.lockEpisode(userId, episodeId);
        if (summary != null) TextInput.validate(summary, 16_000, "梗概");
        var episode = find(userId, episodeId);
        episode.update(title == null ? null : TextInput.name(title, "集名"), summary);
        episodes.flush();
        scope.touchProject(projectId, userId);
        return view(userId, episode);
    }

    @Transactional
    public Episode.View story(long userId, long episodeId, String story) {
        long projectId = scope.lockEpisode(userId, episodeId);
        TextInput.validate(story, 1024 * 1024, "正文");
        var episode = find(userId, episodeId);
        episode.setStory(story);
        episodes.flush();
        scope.touchProject(projectId, userId);
        return view(userId, episode);
    }

    @Transactional
    public void delete(long userId, long episodeId) {
        long projectId = scope.lockEpisode(userId, episodeId);
        for (var task : db.fetch("select id from task where episode_id=? and type='IMAGE'",episodeId))
            cleanup.enqueue("u/" + userId + "/p/" + projectId + "/images/" + task.get("id",Long.class));
        episodes.delete(find(userId, episodeId));
        episodes.flush();
        cleanup.enqueue("u/" + userId + "/p/" + projectId + "/e/" + episodeId);
        scope.touchProject(projectId, userId);
    }

    private Episode find(long userId, long episodeId) {
        return episodes.findByIdAndUserId(episodeId, userId).orElseThrow(ApiException::notFound);
    }
    private Episode.View view(long userId, Episode episode) {
        // Lease-expiry reaping can finish a task without locking its parent in reverse order.
        // Project the cached marker from the persisted task, so an UNKNOWN job cannot leave the UI stuck.
        var projection = db.fetchOne("""
            select case when e.breakdown_state = 'RUNNING' and t.id is not null and t.status not in ('QUEUED','RUNNING')
                then 'IDLE' else e.breakdown_state end as state
            from episode e left join task t on t.id = e.breakdown_task_id where e.id = ? and e.user_id = ?
            """, episode.id(), userId);
        if (projection == null) throw ApiException.notFound();
        String state = projection.get("state", String.class);
        return episode.view(statistics.episode(userId, episode.id()), state);
    }
}
