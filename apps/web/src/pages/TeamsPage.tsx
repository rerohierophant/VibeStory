import { useState, type FormEvent } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { Button } from '@/components/Button';
import { Modal } from '@/components/Modal';
import { ApiError, api, type Schema } from '@/lib/api';
import { clearTeamWorkspace, useStore } from '@/lib/store';
import { visualPresets } from '@/lib/visualPresets';
import type { Project } from '@/lib/types';
import { ProjectCard } from './IndexPage';

type Team = Schema['TeamView'];
type Role = Schema['TeamRole'];
type Member = Schema['MemberView'];
type Invitation = Schema['InvitationView'];

const roleNames: Record<Role, string> = {
  OWNER: '负责人', ADMIN: '管理员', MEMBER: '成员', VIEWER: '只读成员',
};

const date = (value?: string) => value ? new Date(value).toLocaleDateString('zh-CN') : '—';

function RoleOptions({ admin }: { admin: boolean }) {
  return <>
    <option value="MEMBER">成员</option>
    <option value="VIEWER">只读成员</option>
    {admin && <option value="ADMIN">管理员</option>}
  </>;
}

export function TeamsPage() {
  const { mode, user } = useStore();
  const { teamId } = useParams();
  if (mode === 'demo') return <div className="p-10">
    <h1 className="text-2xl font-semibold">团队管理</h1>
    <p className="mt-3 text-sm text-muted">连接后端后可管理团队和处理邀请。</p>
  </div>;
  if (!user?.id) return null;
  return <ConnectedTeams key={user.id + ':' + (teamId ?? '')} userId={user.id} teamId={teamId} />;
}

function ConnectedTeams({ userId, teamId }: { userId: string; teamId?: string }) {
  const navigate = useNavigate();
  const cache = useQueryClient();
  const key = ['teams', userId];
  const teamPath = `/api/teams/${teamId}`;
  const [name, setName] = useState('');
  const [email, setEmail] = useState('');
  const [inviteRole, setInviteRole] = useState<Role>('MEMBER');
  const [tab, setTab] = useState<'members' | 'projects'>('members');
  const teams = useQuery({ queryKey: [...key, 'list'], enabled: !teamId,
    queryFn: ({ signal }) => api<Team[]>('/api/teams', 'GET', undefined, signal) });
  const inbox = useQuery({ queryKey: [...key, 'inbox'], enabled: !teamId,
    queryFn: ({ signal }) => api<Invitation[]>('/api/me/team-invitations', 'GET', undefined, signal) });
  const team = useQuery({ queryKey: [...key, teamId], enabled: !!teamId, refetchInterval: 5000,
    queryFn: ({ signal }) => api<Team>(teamPath, 'GET', undefined, signal) });
  const capabilities = team.error ? undefined : team.data?.capabilities;
  const members = useQuery({ queryKey: [...key, teamId, 'members'], enabled: !!team.data && !team.error, refetchInterval: 5000,
    queryFn: ({ signal }) => api<Member[]>(`${teamPath}/members`, 'GET', undefined, signal) });
  const invites = useQuery({ queryKey: [...key, teamId, 'invites'], enabled: !!capabilities?.manageTeam, refetchInterval: 5000,
    queryFn: ({ signal }) => api<Invitation[]>(`${teamPath}/invitations`, 'GET', undefined, signal) });
  const mutation = useMutation({
    mutationFn: (action: () => Promise<unknown>) => action(),
    onSettled: () => cache.invalidateQueries({ queryKey: key }),
  });
  const error = mutation.error ?? (teamId ? team.error ?? members.error ?? (capabilities?.manageTeam ? invites.error : null) : teams.error ?? inbox.error);
  const denied = team.error instanceof ApiError && team.error.status === 404;

  function saveName(event: FormEvent) {
    event.preventDefault();
    mutation.mutate(async () => {
      const saved = await api<Team>(teamId ? teamPath : '/api/teams', teamId ? 'PATCH' : 'POST', { name: name.trim() });
      setName('');
      if (!teamId) navigate(`/teams/${saved.id}`);
    });
  }

  function sendInvite(event: FormEvent) {
    event.preventDefault();
    mutation.mutate(async () => {
      await api(`${teamPath}/invitations`, 'POST', { email: email.trim(), role: inviteRole });
      setEmail('');
    });
  }

  return <div className="min-h-0 flex-1 overflow-y-auto px-10 py-8 scroll-thin">
    {teamId && <Link className="mb-4 inline-block text-sm text-muted hover:text-fg" to="/teams">← 团队管理</Link>}
    <h1 className="text-2xl font-semibold">{denied ? '无法访问团队' : team.data?.name ?? '团队管理'}</h1>
    {error && <p role="alert" className="mt-4 text-sm text-error-soft">{error.message}</p>}

    {!teamId && <>
      <form className="mt-6 flex flex-wrap items-end gap-3" onSubmit={saveName}>
        <label className="text-sm">团队名称<input className="field mt-2 block" required maxLength={120} value={name} onChange={e => setName(e.target.value)} /></label>
        <Button type="submit" disabled={mutation.isPending || !name.trim()}>创建团队</Button>
      </form>
      <section className="mt-8">
        <h2 className="text-lg font-medium">我的团队</h2>
        {teams.isPending && <p className="mt-3 text-sm text-muted">正在读取团队…</p>}
        {teams.data?.length === 0 && <p className="mt-3 text-sm text-muted">还没有团队。创建团队或接受邀请后即可进入。</p>}
        <ul className="mt-3 grid gap-3 lg:grid-cols-2">
          {teams.data?.map(item => <li key={item.id}>
            <Link className="flex items-center justify-between rounded-card border border-stroke/25 bg-panel/50 p-4 hover:border-accent/60" to={`/teams/${item.id}`}>
              <span>{item.name}</span><span className="text-sm text-muted">{roleNames[item.role!]}</span>
            </Link>
          </li>)}
        </ul>
      </section>
      <section className="mt-8">
        <h2 className="text-lg font-medium">收到的邀请</h2>
        {inbox.data?.length === 0 && <p className="mt-3 text-sm text-muted">暂无待处理邀请。</p>}
        <ul className="mt-3 space-y-3">
          {inbox.data?.map(invite => <li key={invite.id} className="flex flex-wrap items-center justify-between gap-3 rounded-card border border-stroke/25 p-4">
            <div><p>{invite.teamName}</p><p className="text-sm text-muted">{roleNames[invite.role!]} · {date(invite.expiresAt)} 到期</p></div>
            <div className="flex gap-2">
              <Button size="sm" variant="ghost" disabled={mutation.isPending} onClick={() => mutation.mutate(() => api(`/api/me/team-invitations/${invite.id}/decline`, 'POST'))}>拒绝</Button>
              <Button size="sm" disabled={mutation.isPending} onClick={() => mutation.mutate(async () => {
                await api(`/api/me/team-invitations/${invite.id}/accept`, 'POST');
                navigate(`/teams/${invite.teamId}`);
              })}>接受邀请</Button>
            </div>
          </li>)}
        </ul>
      </section>
    </>}

    {teamId && team.isPending && <p className="mt-4 text-sm text-muted">正在读取团队…</p>}
    {teamId && team.data && capabilities && <>
      <p className="mt-2 text-sm text-muted">我的角色 · {roleNames[team.data.role!]}</p>
      <div className="mt-5 flex flex-wrap items-end gap-4">
        {capabilities.manageTeam && <form className="flex flex-wrap items-end gap-3" onSubmit={saveName}>
          <label className="text-sm">新团队名<input className="field mt-2 block" required maxLength={120} value={name} onChange={e => setName(e.target.value)} /></label>
          <Button type="submit" variant="outline" disabled={mutation.isPending || !name.trim()}>保存名称</Button>
        </form>}
        {capabilities.leave && <Button variant="outline" disabled={mutation.isPending} onClick={() => {
          if (!window.confirm('退出后将无法访问团队项目和产物。确定退出？')) return;
          mutation.mutate(async () => {
            await api(`${teamPath}/members/${userId}`, 'DELETE');
            clearTeamWorkspace(cache, userId, teamId);
            navigate('/teams');
          });
        }}>退出团队</Button>}
      </div>

      <nav aria-label="团队工作区" className="mt-6 flex gap-2 border-b border-stroke/20 pb-2">
        <Button size="sm" variant={tab === 'members' ? 'outline' : 'ghost'} aria-pressed={tab === 'members'} onClick={() => setTab('members')}>成员</Button>
        <Button size="sm" variant={tab === 'projects' ? 'outline' : 'ghost'} aria-pressed={tab === 'projects'} onClick={() => setTab('projects')}>项目</Button>
      </nav>
      {tab === 'projects' && <TeamProjects userId={userId} team={team.data} />}
      {tab === 'members' && <section className="mt-8">
        <h2 className="text-lg font-medium">成员</h2>
        {members.isPending && <p className="mt-3 text-sm text-muted">正在读取成员…</p>}
        <div className="mt-3 overflow-x-auto rounded-card border border-stroke/25">
          <table className="w-full min-w-[520px] text-left text-sm">
            <thead className="bg-panel/60 text-muted"><tr><th className="p-3">账号</th><th className="p-3">角色</th><th className="p-3">加入时间</th><th className="p-3">操作</th></tr></thead>
            <tbody>{members.data?.map(member => <tr key={member.userId} className="border-t border-stroke/20">
              <td className="p-3">{member.email}{member.userId === userId && '（我）'}</td>
              <td className="p-3">{member.manageable ? <select className="field" aria-label={`调整 ${member.email} 的角色`}
                value={member.role} disabled={mutation.isPending} onChange={event => {
                  const role = event.target.value as Role;
                  if (!window.confirm(`把 ${member.email} 的角色改为「${roleNames[role]}」？权限将立即更新。`)) return;
                  mutation.mutate(() => api(`${teamPath}/members/${member.userId}`, 'PATCH', { role }));
                }}><RoleOptions admin={!!capabilities.manageAdmins} /></select> : roleNames[member.role!]}</td>
              <td className="p-3 text-muted">{date(member.joinedAt)}</td>
              <td className="p-3">{member.manageable && <Button size="sm" variant="ghost" disabled={mutation.isPending} onClick={() => {
                if (window.confirm(`移除 ${member.email} 后，对方将无法访问团队项目和产物。确定移除？`))
                  mutation.mutate(() => api(`${teamPath}/members/${member.userId}`, 'DELETE'));
              }}>移除</Button>}</td>
            </tr>)}</tbody>
          </table>
        </div>
      </section>}

      {tab === 'members' && capabilities.manageTeam && <section className="mt-8">
        <h2 className="text-lg font-medium">邀请成员</h2>
        <form className="mt-3 flex flex-wrap items-end gap-3" onSubmit={sendInvite}>
          <label className="text-sm">账号邮箱<input type="email" className="field mt-2 block" required maxLength={254} value={email} onChange={e => setEmail(e.target.value)} /></label>
          <label className="text-sm">邀请角色<select className="field mt-2 block" value={inviteRole} onChange={e => setInviteRole(e.target.value as Role)}>
            <RoleOptions admin={!!capabilities.manageAdmins} />
          </select></label>
          <Button type="submit" disabled={mutation.isPending || !email.trim()}>发出站内邀请</Button>
        </form>
        <ul className="mt-4 space-y-2">
          {invites.data?.map(invite => <li key={invite.id} className="flex flex-wrap items-center justify-between gap-3 rounded-card border border-stroke/25 p-3 text-sm">
            <span>{invite.email} · {roleNames[invite.role!]} · {date(invite.expiresAt)} 到期</span>
            {(capabilities.manageAdmins || invite.role !== 'ADMIN') && <Button size="sm" variant="ghost" disabled={mutation.isPending}
              onClick={() => mutation.mutate(() => api(`${teamPath}/invitations/${invite.id}`, 'DELETE'))}>撤销邀请</Button>}
          </li>)}
        </ul>
      </section>}
    </>}
  </div>;
}

function TeamProjects({ userId, team }: { userId: string; team: Team }) {
  const navigate = useNavigate();
  const cache = useQueryClient();
  const key = ['teams', userId, team.id, 'projects'];
  const path = `/api/teams/${team.id}/projects`;
  const [draft, setDraft] = useState<{ id?: string; name: string; visualType: string; style: string; version?: number }>();
  const projects = useQuery({ queryKey: key, queryFn: ({ signal }) => api<Schema['Project'][]>(path, 'GET', undefined, signal) });
  const save = useMutation({
    mutationFn: async (input: NonNullable<typeof draft>) => {
      const body = { name: input.name.trim(), visualType: input.visualType, style: input.style, expectedVersion: input.version };
      return input.id ? api<Schema['Project']>(`/api/projects/${input.id}`, 'PATCH', body)
        : api<Schema['Project']>(path, 'POST', body);
    },
    onSuccess: () => { setDraft(undefined); return cache.invalidateQueries({ queryKey: key }); },
    onError: error => { if (error instanceof ApiError && error.status === 409) void cache.invalidateQueries({ queryKey: key }); },
  });
  const remove = useMutation({
    mutationFn: (id: string) => api(`/api/projects/${id}`, 'DELETE'),
    onSuccess: () => cache.invalidateQueries({ queryKey: key }),
  });

  return <section className="mt-6">
    <div className="flex flex-wrap items-center justify-between gap-3">
      <h2 className="text-lg font-medium">团队项目</h2>
      {team.capabilities?.editProjects && <Button onClick={() => {
        save.reset();
        setDraft({ name: '', visualType: visualPresets[0].id, style: '' });
      }}>新建团队项目</Button>}
    </div>
    {(projects.error ?? remove.error) && <p role="alert" className="mt-3 text-sm text-error-soft">{(projects.error ?? remove.error)?.message}</p>}
    {projects.isPending && <p className="mt-3 text-sm text-muted">正在读取项目…</p>}
    {projects.data?.length === 0 && <p className="mt-3 text-sm text-muted">团队还没有项目。</p>}
    <div className="mt-4 grid gap-4 sm:grid-cols-2 xl:grid-cols-3">
      {projects.data?.map(item => <ProjectCard key={item.id} project={item as Project} shots={item.stats?.shotCount ?? 0} customStyles={[]}
        onOpen={() => navigate(`/projects/${item.id}`)}
        onRename={team.capabilities?.editProjects ? () => {
          save.reset();
          setDraft({ id: item.id, name: item.name ?? '', visualType: item.visualType ?? visualPresets[0].id, style: item.style ?? '', version: item.version });
        } : undefined}
        onDelete={team.capabilities?.manageTeam ? () => {
          if (!remove.isPending && window.confirm(`删除「${item.name}」将移除它的分集、资产和生成产物。确定删除？`)) remove.mutate(item.id!);
        } : undefined} />)}
    </div>
    <Modal open={!!draft} onClose={() => { if (!save.isPending) setDraft(undefined); }} labelledBy="team-project-title" width="max-w-lg">
      {draft && <form className="space-y-4 p-6" onSubmit={event => { event.preventDefault(); if (!save.isPending && draft.name.trim()) save.mutate(draft); }}>
        <h2 id="team-project-title" className="text-lg font-medium">{draft.id ? '编辑团队项目' : '新建团队项目'}</h2>
        <p className="text-sm text-muted">归属团队 · {team.name}</p>
        <label className="block text-sm">项目名称<input className="field mt-2" required maxLength={120} value={draft.name}
          onChange={e => setDraft({ ...draft, name: e.target.value })} /></label>
        <label className="block text-sm">画面风格<select className="field mt-2" value={draft.visualType}
          onChange={e => setDraft({ ...draft, visualType: e.target.value })}>
          {visualPresets.map(p => <option key={p.id} value={p.id}>{p.label}</option>)}
        </select></label>
        <label className="block text-sm">风格描述<textarea className="field mt-2" maxLength={4000} value={draft.style}
          onChange={e => setDraft({ ...draft, style: e.target.value })} /></label>
        {save.error && <p role="alert" className="text-sm text-error-soft">{save.error.message}</p>}
        <div className="flex justify-end gap-2">
          <Button type="button" variant="ghost" disabled={save.isPending} onClick={() => setDraft(undefined)}>取消</Button>
          <Button type="submit" disabled={save.isPending || !draft.name.trim()}>{draft.id ? '保存项目' : '创建项目'}</Button>
        </div>
      </form>}
    </Modal>
  </section>;
}
