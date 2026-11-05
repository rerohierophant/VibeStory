package com.vibestory.project;

import com.vibestory.common.ApiException;
import com.vibestory.team.TeamRole;
import com.vibestory.team.TeamService;
import org.jooq.DSLContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Resolves the authenticated actor separately from the stable owner key used by storage and Workers. */
@Component
public class ProjectAccess {
    public enum Resource {
        PROJECT("select id from project where id=?"),
        EPISODE("select project_id from episode where id=?"),
        SHOT("select project_id from shot where id=?"),
        TASK("select project_id from task where id=?"),
        BATCH("select project_id from generation_batch where id=?"),
        CHARACTER("select project_id from character_asset where id=?"),
        SCENE("select project_id from scene_asset where id=?"),
        ASSET_VERSION("select project_id from asset_version where id=?"),
        SHOT_IMAGE("select s.project_id from shot_image i join shot s on s.id=i.shot_id where i.id=?");

        private final String sql;
        Resource(String sql) { this.sql = sql; }
    }

    public enum Action { READ, EDIT, MANAGE, DIAGNOSE, PERSONAL }

    public record Grant(long actorId, long partitionUserId, long projectId, Long teamId) {
        public void requireVersion(Long version) {
            if (teamId != null && version == null) throw ApiException.conflict("请刷新页面后提交团队项目的当前版本");
        }
    }

    private final DSLContext db;
    private final TeamService teams;

    public ProjectAccess(DSLContext db, TeamService teams) {
        this.db = db;
        this.teams = teams;
    }

    public Grant read(long actor, Resource resource, long id) { return authorize(actor, resource, id, Action.READ); }
    public Grant edit(long actor, Resource resource, long id) { return authorize(actor, resource, id, Action.EDIT); }

    public boolean canEdit(Grant grant) {
        return grant.teamId() == null || teams.role(grant.actorId(), grant.teamId(), false).editsProject();
    }

    // EDIT and MANAGE callers hold the transaction through their business write.
    public Grant authorize(long actor, Resource resource, long id, Action action) {
        var row = db.fetchOne("select id,user_id,team_id from project where id=(" + resource.sql + ")", id);
        if (row == null) throw ApiException.notFound();
        long owner = row.get("user_id", Long.class);
        Long team = row.get("team_id", Long.class);
        if (team == null) {
            if (actor != owner) throw ApiException.notFound();
        } else {
            if (action == Action.PERSONAL) throw ApiException.notFound();
            TeamRole role = teams.role(actor, team, action == Action.EDIT || action == Action.MANAGE);
            if (action == Action.EDIT && !role.editsProject()
                || (action == Action.MANAGE || action == Action.DIAGNOSE) && !role.managesTeam())
                throw new ApiException(HttpStatus.FORBIDDEN, "当前团队角色没有此项目操作权限");
        }
        return new Grant(actor, owner, row.get("id", Long.class), team);
    }

    public void recordTask(Grant grant, String id) {
        if (grant.teamId() == null) return;
        db.execute("update task set requested_by=? where id=? and project_id=? and requested_by is null",
            grant.actorId(), Long.parseLong(id), grant.projectId());
    }

    public void recordBatch(Grant grant, String id) {
        if (grant.teamId() == null) return;
        long batch = Long.parseLong(id);
        db.execute("update generation_batch set requested_by=? where id=? and project_id=? and requested_by is null",
            grant.actorId(), batch, grant.projectId());
        db.execute("""
            update task set requested_by=? where project_id=? and requested_by is null and id in
            (select task_id from generation_batch_member where batch_id=?)
            """, grant.actorId(), grant.projectId(), batch);
    }
}
