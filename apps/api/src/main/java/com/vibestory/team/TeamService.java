package com.vibestory.team;

import com.vibestory.auth.Viewer;
import com.vibestory.common.ApiException;
import com.vibestory.common.TextInput;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TeamService {
    private final DSLContext db;

    public TeamService(DSLContext db) { this.db = db; }

    public record Capabilities(boolean manageTeam, boolean editProjects, boolean manageAdmins, boolean leave) {}
    public record TeamView(String id, String name, TeamRole role, Capabilities capabilities) {}
    public record MemberView(String userId, String email, TeamRole role, OffsetDateTime joinedAt, boolean manageable) {}
    public record InvitationView(String id, String teamId, String teamName, String email, TeamRole role,
                                 String status, OffsetDateTime expiresAt) {}

    @Transactional(readOnly = true)
    public List<TeamView> list(long user) {
        return db.fetch("""
            select t.id, t.name, m.role from team_member m join team t on t.id=m.team_id
            where m.user_id=? order by t.id desc
            """, user).map(this::view);
    }

    @Transactional(readOnly = true)
    public TeamView get(long user, long team) {
        var row = db.fetchOne("""
            select t.id, t.name, m.role from team_member m join team t on t.id=m.team_id
            where m.user_id=? and t.id=?
            """, user, team);
        if (row == null) throw ApiException.notFound();
        return view(row);
    }

    @Transactional
    public TeamView create(long user, String name) {
        long team = db.fetchOne("insert into team(name) values (?) returning id", TextInput.name(name, "团队名")).get(0, Long.class);
        db.execute("insert into team_member(team_id,user_id,role) values (?,?,'OWNER')", team, user);
        audit(team, user, "TEAM_CREATED", team, null, TeamRole.OWNER);
        return get(user, team);
    }

    @Transactional
    public TeamView rename(long user, long team, String name) {
        lockTeam(team);
        require(role(user, team, false).managesTeam());
        String renamed = TextInput.name(name, "团队名");
        if (db.execute("update team set name=? where id=? and name<>?", renamed, team, renamed) > 0)
            audit(team, user, "TEAM_RENAMED", team, null, null);
        return get(user, team);
    }

    @Transactional(readOnly = true)
    public List<MemberView> members(long user, long team) {
        TeamRole actor = role(user, team, false);
        return db.fetch("""
            select m.user_id,u.email,m.role,m.joined_at from team_member m join app_user u on u.id=m.user_id
            where m.team_id=? order by case m.role when 'OWNER' then 0 when 'ADMIN' then 1 else 2 end, m.joined_at,m.user_id
            """, team).map(row -> new MemberView(row.get("user_id", String.class), row.get("email", String.class),
                roleOf(row), row.get("joined_at", OffsetDateTime.class), actor.manages(roleOf(row))));
    }

    @Transactional
    public void changeRole(long user, long team, long member, TeamRole next) {
        lockTeam(team);
        TeamRole actor = role(user, team, false);
        TeamRole current = role(member, team, false);
        require(actor.manages(current) && actor.manages(next));
        if (current == next) return;
        db.execute("update team_member set role=? where team_id=? and user_id=?", next.name(), team, member);
        revokeLostInvites(team, user, member, next);
        audit(team, user, "ROLE_CHANGED", member, current, next);
    }

    @Transactional
    public void remove(long user, long team, long member) {
        lockTeam(team);
        TeamRole actor = role(user, team, false);
        TeamRole current = role(member, team, false);
        require(user == member ? current != TeamRole.OWNER : actor.manages(current));
        db.execute("delete from team_member where team_id=? and user_id=?", team, member);
        revokeLostInvites(team, user, member, null);
        audit(team, user, user == member ? "MEMBER_LEFT" : "MEMBER_REMOVED", member, current, null);
    }

    @Transactional(readOnly = true)
    public List<InvitationView> invitations(long user, long team) {
        require(role(user, team, false).managesTeam());
        return db.fetch("""
            select i.*,t.name as team_name from team_invitation i join team t on t.id=i.team_id
            where i.team_id=? and i.status='PENDING' and i.expires_at>now() order by i.id desc
            """, team).map(this::invitation);
    }

    @Transactional
    public InvitationView invite(long user, long team, String email, TeamRole invitedRole) {
        lockTeam(team);
        require(role(user, team, false).manages(invitedRole));
        String recipient = normalized(email);
        if (db.fetchOne("""
            select m.user_id from team_member m join app_user u on u.id=m.user_id where m.team_id=? and u.email=?
            """, team, recipient) != null) throw ApiException.conflict("该账号已是团队成员");
        db.execute("""
            update team_invitation set status='EXPIRED' where team_id=? and email=? and status='PENDING' and expires_at<=now()
            """, team, recipient);
        var old = db.fetchOne("select id,role from team_invitation where team_id=? and email=? and status='PENDING'", team, recipient);
        if (old != null) {
            if (roleOf(old) != invitedRole) throw ApiException.conflict("该邮箱已有待处理邀请，请先撤销原邀请");
            return invitationById(old.get("id", Long.class));
        }
        long id = db.fetchOne("""
            insert into team_invitation(team_id,email,role,inviter_id) values (?,?,?,?) returning id
            """, team, recipient, invitedRole.name(), user).get(0, Long.class);
        audit(team, user, "INVITATION_CREATED", id, null, invitedRole);
        return invitationById(id);
    }

    @Transactional
    public void revoke(long user, long team, long id) {
        lockTeam(team);
        TeamRole actor = role(user, team, false);
        require(actor.managesTeam());
        var row = db.fetchOne("select * from team_invitation where id=? and team_id=?", id, team);
        if (row == null) throw ApiException.notFound();
        require(actor.manages(roleOf(row)));
        if (row.get("status", String.class).equals("REVOKED")) return;
        if (db.execute("""
            update team_invitation set status='REVOKED' where id=? and status='PENDING' and expires_at>now()
            """, id) == 0) throw ApiException.conflict("邀请已处理或已过期");
        audit(team, user, "INVITATION_REVOKED", id, roleOf(row), null);
    }

    @Transactional(readOnly = true)
    public List<InvitationView> inbox(Viewer viewer) {
        return db.fetch("""
            select i.*,t.name as team_name from team_invitation i join team t on t.id=i.team_id
            where i.email=? and i.status='PENDING' and i.expires_at>now() order by i.id desc
            """, normalized(viewer.email())).map(this::invitation);
    }

    @Transactional
    public void respond(Viewer viewer, long id, boolean accept) {
        String email = normalized(viewer.email());
        var parent = db.fetchOne("select team_id from team_invitation where id=? and email=?", id, email);
        if (parent == null) throw ApiException.notFound();
        long team = parent.get("team_id", Long.class);
        lockTeam(team);
        var row = db.fetchOne("""
            select *, expires_at>now() as live from team_invitation where id=? and email=?
            """, id, email);
        String next = accept ? "ACCEPTED" : "DECLINED";
        String status = row.get("status", String.class);
        if (status.equals(next)) return;
        if (!status.equals("PENDING") || !row.get("live", Boolean.class))
            throw ApiException.conflict("邀请已处理或已过期");
        TeamRole invited = roleOf(row);
        if (accept) {
            TeamRole inviter = findRole(row.get("inviter_id", Long.class), team, false);
            if (inviter == null || !inviter.manages(invited)) throw ApiException.conflict("邀请已失效");
            db.execute("""
                insert into team_member(team_id,user_id,role) values (?,?,?) on conflict (team_id,user_id) do nothing
                """, team, viewer.id(), invited.name());
        }
        db.execute("update team_invitation set status=? where id=?", next, id);
        audit(team, viewer.id(), "INVITATION_" + next, id, null, accept ? invited : null);
    }

    public TeamRole role(long user, long team, boolean lock) {
        TeamRole role = findRole(user, team, lock);
        if (role == null) throw ApiException.notFound();
        return role;
    }

    public void audit(long team, long actor, String action, long target, TeamRole previous, TeamRole next) {
        db.execute("""
            insert into team_audit(team_id,actor_id,action,target_id,previous_role,next_role) values (?,?,?,?,?,?)
            """, team, actor, action, target, previous == null ? null : previous.name(), next == null ? null : next.name());
    }

    private TeamRole findRole(long user, long team, boolean lock) {
        var row = db.fetchOne("select role from team_member where team_id=? and user_id=?" + (lock ? " for share" : ""), team, user);
        return row == null ? null : roleOf(row);
    }

    private void lockTeam(long team) {
        if (db.fetchOne("select id from team where id=? for no key update", team) == null) throw ApiException.notFound();
    }

    private void revokeLostInvites(long team, long actor, long inviter, TeamRole remaining) {
        if (remaining != null && remaining.managesTeam()) return;
        var revoked = db.fetch("""
            update team_invitation set status='REVOKED' where team_id=? and inviter_id=? and status='PENDING' returning id,role
            """, team, inviter);
        for (var row : revoked) audit(team, actor, "INVITATION_REVOKED", row.get("id", Long.class), roleOf(row), null);
    }

    private InvitationView invitationById(long id) {
        return invitation(db.fetchOne("""
            select i.*, t.name as team_name from team_invitation i join team t on t.id=i.team_id where i.id=?
            """, id));
    }

    private InvitationView invitation(Record row) {
        return new InvitationView(row.get("id", String.class), row.get("team_id", String.class), row.get("team_name", String.class),
            row.get("email", String.class), roleOf(row), row.get("status", String.class), row.get("expires_at", OffsetDateTime.class));
    }

    private TeamView view(Record row) {
        TeamRole role = roleOf(row);
        return new TeamView(row.get("id", String.class), row.get("name", String.class), role,
            new Capabilities(role.managesTeam(), role.editsProject(), role == TeamRole.OWNER, role != TeamRole.OWNER));
    }

    private static TeamRole roleOf(Record row) { return TeamRole.valueOf(row.get("role", String.class)); }
    private static String normalized(String email) { return email.strip().toLowerCase(Locale.ROOT); }
    private static void require(boolean authorized) {
        if (!authorized) throw new ApiException(HttpStatus.FORBIDDEN, "当前团队角色没有此操作权限");
    }
}
