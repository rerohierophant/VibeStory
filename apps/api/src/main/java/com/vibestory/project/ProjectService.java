package com.vibestory.project;

import com.vibestory.common.ApiException;
import com.vibestory.common.ScopeGuard;
import com.vibestory.common.TextInput;
import com.vibestory.common.ProductionStatistics;
import com.vibestory.episode.EpisodeService;
import com.vibestory.style.VisualStyleService;
import com.vibestory.storage.StorageCleanup;
import com.vibestory.team.TeamService;
import java.util.List;
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
    private final TeamService teams;

    public ProjectService(ProjectRepository projects, ScopeGuard scope, VisualStyleService styles,
                          EpisodeService episodes, ProductionStatistics statistics, StorageCleanup cleanup, TeamService teams) {
        this.projects = projects; this.scope = scope; this.styles = styles;
        this.episodes = episodes; this.statistics = statistics; this.cleanup = cleanup; this.teams = teams;
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
        return update(userId, id, name, visualType, style, null);
    }

    @Transactional
    public Project.View update(long userId, long id, String name, String visualType, String style, Long expectedVersion) {
        scope.lockProject(userId, id);
        var project = find(userId, id);
        if (expectedVersion != null && expectedVersion != project.version())
            throw ApiException.conflict("项目已被修改，请刷新后再编辑");
        requireTeamPreset(project.teamId(), visualType);
        String previous = project.visualType();
        if (visualType != null && !visualType.equals(previous)) styles.changeSelection(userId, previous, visualType);
        if (style != null) TextInput.validate(style, 16_000, "风格描述");
        project.update(name == null ? null : TextInput.name(name, "项目名"), visualType, style);
        projects.flush();
        if (!previous.equals(project.visualType())) styles.releaseIfUnused(userId, previous);
        return view(userId, project);
    }

    @Transactional
    public void delete(long userId, long id) {
        scope.lockProject(userId, id);
        var project = find(userId, id);
        String visualType = project.visualType();
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
        return project.view(episodes.list(userId, project.getId()), styles.resolve(userId, project.visualType()),
            statistics.project(userId, project.getId()));
    }

    private Project find(long userId, long id) {
        return projects.findByIdAndUserId(id, userId).orElseThrow(ApiException::notFound);
    }

    private void requireTeamPreset(Long teamId, String visualType) {
        if (teamId != null && visualType != null && visualType.startsWith("custom_"))
            throw ApiException.badRequest("团队项目请选择内置画面风格");
    }
}
