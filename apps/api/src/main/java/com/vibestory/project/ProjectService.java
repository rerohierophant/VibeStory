package com.vibestory.project;

import com.vibestory.common.ApiException;
import com.vibestory.common.ScopeGuard;
import com.vibestory.common.TextInput;
import com.vibestory.common.ProductionStatistics;
import com.vibestory.common.ProjectDataCleanup;
import com.vibestory.episode.EpisodeService;
import com.vibestory.style.VisualStyleService;
import com.vibestory.storage.StorageCleanup;
import com.vibestory.team.TeamService;
import java.util.List;
import org.jooq.DSLContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProjectService {
    private final ProjectRepository projects;
    private final ScopeGuard scope;
    private final VisualStyleService styles;
    private final EpisodeService episodes;
    private final ProductionStatistics statistics;
    private final StorageCleanup cleanup;
    private final ProjectDataCleanup dataCleanup;
    private final TeamService teams;
    private final DSLContext db;

    public ProjectService(ProjectRepository projects, ScopeGuard scope, VisualStyleService styles,
                          EpisodeService episodes, ProductionStatistics statistics, StorageCleanup cleanup,
                          ProjectDataCleanup dataCleanup, TeamService teams, DSLContext db) {
        this.projects = projects; this.scope = scope; this.styles = styles;
        this.episodes = episodes; this.statistics = statistics; this.cleanup = cleanup; this.teams = teams;
        this.dataCleanup = dataCleanup;
        this.db = db;
    }

    @Transactional
    public Project.View create(long userId, String name, String visualType, String style) {
        return createProject(userId, null, name, visualType, style);
    }

    @Transactional
    public Project.View createTeam(long actor, long team, String name, String visualType, String style) {
        if (!teams.role(actor, team, true).editsProject())
            throw new ApiException(HttpStatus.FORBIDDEN, "当前团队角色不能新建项目");
        var project = createProject(actor, team, name, visualType, style);
        teams.audit(team, actor, "PROJECT_CREATED", Long.parseLong(project.id()), null, null);
        return project;
    }

    @Transactional(readOnly = true)
    public List<Project.View> listTeam(long actor, long team) {
        teams.role(actor, team, false);
        return projects.findByTeamIdOrderByUpdatedAtDescIdDesc(team).stream()
            .map(project -> view(project.partitionUserId(), project)).toList();
    }

    private Project.View createProject(long userId, Long teamId, String name, String visualType, String style) {
        requireTeamPreset(teamId, visualType);
        styles.validateSelection(userId, visualType);
        if (style != null) TextInput.validate(style, 16_000, "风格描述");
        return view(userId, projects.saveAndFlush(new Project(userId, teamId, TextInput.name(name, "项目名"), visualType, style)));
    }

    @Transactional(readOnly = true)
    public Project.View get(long userId, long id) {
        return view(userId, find(userId, id));
    }

    @Transactional(readOnly = true)
    public List<Project.View> list(long userId, String query) {
        return projects.findByUserIdAndTeamIdIsNullAndNameContainingIgnoreCaseOrderByUpdatedAtDescIdDesc(userId, query.strip())
            .stream().map(project -> view(userId, project)).toList();
    }

    @Transactional
    public Project.View update(long userId, long id, String name, String visualType, String style) {
        return update(userId, id, name, visualType, style, null, null);
    }

    @Transactional
    public Project.View update(long userId, long id, String name, String visualType, String style, String synopsis, Long expectedVersion) {
        scope.lockProject(userId, id);
        var project = find(userId, id);
        if (expectedVersion != null && expectedVersion != project.version())
            throw ApiException.conflict("项目已被修改，请刷新后再编辑");
        String previous = project.visualType();
        boolean presetChanged = visualType != null && !visualType.equals(previous);
        boolean styleChanged = presetChanged || (style != null && !style.equals(project.style()));
        if (styleChanged && !styleEditable(userId, id))
            throw ApiException.conflict("项目已有生成图，或有排队中、生成中、结果未知的图片任务，暂时无法切换风格");
        requireTeamPreset(project.teamId(), visualType);
        if (presetChanged) styles.changeSelection(userId, previous, visualType);
        if (style != null) TextInput.validate(style, 16_000, "风格描述");
        if (synopsis != null) TextInput.validate(synopsis, 8000, "故事简介");
        if (styleChanged) {
            // Retrying a failed task would replay its original prompt. Keep it in history and submit anew.
            db.execute("""
                update task set status = 'CANCELED', retryable = false, error_code = 'PROJECT_STYLE_CHANGED',
                    error_message = '项目风格已切换，请重新生成', updated_at = now(), version = version + 1
                where project_id = ? and user_id = ? and type in ('IMAGE', 'ASSET_IMAGE') and status = 'FAILED'
                """, id, userId);
        }
        project.update(name == null ? null : TextInput.name(name, "项目名"), visualType, style,
            synopsis == null ? null : synopsis.strip());
        projects.flush();
        if (presetChanged) styles.releaseIfUnused(userId, previous);
        return view(userId, project);
    }

    @Transactional
    public void delete(long userId, long id) {
        scope.lockProject(userId, id);
        var project = find(userId, id);
        String visualType = project.visualType();
        dataCleanup.project(userId, id);
        projects.delete(project);
        projects.flush();
        cleanup.enqueue("u/" + userId + "/p/" + id);
        styles.releaseIfUnused(userId, visualType);
    }

    @Transactional(readOnly = true)
    public ProductionStatistics.Overview overview(long userId, long id) {
        scope.project(userId, id);
        return statistics.project(userId, id);
    }

    private Project.View view(long userId, Project project) {
        long id = project.getId();
        return project.view(episodes.list(userId, id), styles.resolve(userId, project.visualType()),
            statistics.project(userId, id), coverImageUrl(userId, id), styleEditable(userId, id));
    }

    private boolean styleEditable(long userId, long projectId) {
        // Image submission, retries and result installation serialize on the same project row as update.
        return db.fetchOne("""
            select not (
                exists (select 1 from task where project_id = ? and user_id = ? and type in ('IMAGE', 'ASSET_IMAGE')
                    and status in ('QUEUED', 'RUNNING', 'UNKNOWN', 'SUCCEEDED'))
                or exists (select 1 from shot s join shot_image i on i.shot_id = s.id and i.episode_id = s.episode_id
                    where s.project_id = ? and s.user_id = ? and i.status = 'READY')
                or exists (select 1 from asset_version where project_id = ? and user_id = ? and source = 'generated')
            )
            """, projectId, userId, projectId, userId, projectId, userId).get(0, Boolean.class);
    }

    private String coverImageUrl(long userId, long projectId) {
        var image = db.fetchOne("""
            select i.id
            from episode e
            join shot s on s.episode_id = e.id
            join shot_image i on i.shot_id = s.id
            where e.project_id = ? and e.user_id = ?
                and i.status = 'READY' and i.deleted_at is null and i.media_type is not null
            order by e.episode_index, s.seq, i.selected desc, i.id
            limit 1
            """, projectId, userId);
        return image == null ? null : "/api/shot-images/" + image.get("id", Long.class) + "/image";
    }

    private Project find(long userId, long id) {
        return projects.findByIdAndUserId(id, userId).orElseThrow(ApiException::notFound);
    }

    private void requireTeamPreset(Long teamId, String visualType) {
        if (teamId != null && visualType != null && visualType.startsWith("custom_"))
            throw ApiException.badRequest("团队项目请选择内置画面风格");
    }
}
