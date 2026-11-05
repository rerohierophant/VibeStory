import { useRef, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Button } from '@/components/Button';
import { Modal } from '@/components/Modal';
import { PlaceholderImage } from '@/components/PlaceholderImage';
import { api, type Schema } from '@/lib/api';
import { useStore } from '@/lib/store';
import { cameraMoves, shotSizes, type Shot } from '@/lib/types';

type Variant = Schema['StoryboardVariant'];
type Member = Schema['StoryboardVariantMember'];
type Fields = { description: string; shotSize: string; cameraMove: string };
type Draft = { name: string; base?: Variant; edits: Record<string, Fields> };
type Selection = { variant: Variant; ids: string[] };
type Preview = { variant: Variant; items: { shot: Shot; member: Member }[] };

const states: Record<string, string> = {
  queued: '排队中', running: '生成中', succeeded: '已生成', failed: '失败',
  unknown: '结果未知', canceled: '已取消', paused: '派发暂停',
};

function fields(shot: Shot | Member): Fields {
  return { description: shot.description ?? '', shotSize: shot.shotSize ?? '中景', cameraMove: shot.cameraMove ?? '固定' };
}

function Sources({ sources }: { sources?: Member['sources'] }) {
  const refs = [...(sources?.characters ?? []), ...(sources?.scene ? [sources.scene] : [])];
  return refs.length > 0 && <details className="text-xs text-muted">
    <summary className="cursor-pointer">出图来源</summary>
    <ul className="mt-1">{refs.map((ref, index) => <li key={index}>{ref.name} · {ref.versionLabel ?? '文字描述'}</li>)}</ul>
  </details>;
}

export function StoryboardVariants({ episodeId, shots }: { episodeId: string; shots: Shot[] }) {
  const { mode, user, saving } = useStore();
  const cache = useQueryClient();
  const [open, setOpen] = useState(false);
  const [draft, setDraft] = useState<Draft>();
  const [selection, setSelection] = useState<Selection>();
  const [preview, setPreview] = useState<Preview>();
  const [notice, setNotice] = useState('');
  const request = useRef<{ fingerprint: string; key: string } | null>(null);
  const path = `/api/episodes/${episodeId}/storyboard-variants`;
  const queryKey = ['storyboard-variants', user?.id, episodeId];
  const variants = useQuery({
    queryKey, enabled: open && mode === 'api' && !!user,
    queryFn: ({ signal }) => api<Schema['StoryboardVariantOverview']>(path, 'GET', undefined, signal),
    refetchInterval: open ? 2200 : false,
  });
  const mutation = useMutation({
    mutationFn: (run: () => Promise<void>) => run(),
    onSettled: () => cache.invalidateQueries({ queryKey }),
  });
  const canEdit = !variants.isError && variants.data?.canEdit === true;
  const busy = mutation.isPending || saving;
  const active = variants.isError ? [] : variants.data?.variants ?? [];
  const rows = shots.filter(shot => active.some(variant => variant.members?.some(member => member.shotId === shot.id)));
  const edits = draft ? Object.entries(draft.edits) : [];
  const error = mutation.error ?? variants.error;

  function begin(base?: Variant) {
    setDraft({ name: base?.name ?? '', base, edits: {} });
    setSelection(undefined);
    setPreview(undefined);
    setNotice('');
    mutation.reset();
  }

  function toggleEdit(id: string, from: Shot | Member, checked: boolean) {
    setDraft(current => {
      if (!current) return current;
      const next = { ...current.edits };
      if (checked) next[id] = fields(from);
      else delete next[id];
      return { ...current, edits: next };
    });
  }

  function changeField(id: string, key: keyof Fields, value: string) {
    setDraft(current => current && { ...current, edits: { ...current.edits, [id]: { ...current.edits[id], [key]: value } } });
  }

  function run(action: () => Promise<void>) {
    if (busy || !canEdit) return;
    setNotice('');
    mutation.mutate(action);
  }

  function requestKey(payload: object) {
    const fingerprint = JSON.stringify(payload);
    if (request.current?.fingerprint !== fingerprint) request.current = { fingerprint, key: crypto.randomUUID() };
    return request.current.key;
  }

  function submit() {
    if (!draft) return;
    const payload = { name: draft.base ? undefined : draft.name.trim(), baseRevisionId: draft.base?.revisionId,
      shots: edits.map(([shotId, edit]) => ({ shotId, ...edit })) };
    run(async () => {
      const saved = await api<Variant>(path, 'POST', { ...payload, requestKey: requestKey(payload) });
      request.current = null;
      setDraft(undefined);
      setNotice(`已提交「${saved.name}」的 ${edits.length} 个出图任务`);
    });
  }

  function toggleAdopt(variant: Variant, id: string, checked: boolean) {
    const ids = selection && selection.variant.revisionId === variant.revisionId ? selection.ids : [];
    setSelection({ variant, ids: checked ? [...ids, id] : ids.filter(value => value !== id) });
    setPreview(undefined);
  }

  function showAdoption() {
    if (!selection) return;
    const variant = active.find(item => item.revisionId === selection.variant.revisionId);
    if (!variant) return;
    const members = selection.ids.map(id => variant.members?.find(item => item.shotId === id && item.imageId));
    if (members.some(member => !member)) return;
    run(async () => {
      const items = await Promise.all(members.map(async member => ({
        shot: await api<Shot>(`/api/shots/${member!.shotId}`), member: member!,
      })));
      setPreview({ variant, items });
    });
  }

  function adopt() {
    if (!preview) return;
    const url = `${path}/revisions/${preview.variant.revisionId}/adopt`;
    const payload = { shots: preview.items.map(({ shot, member }) => ({
      shotId: shot.id, imageId: member.imageId, shotVersion: shot.version, selectedImageId: shot.selectedImageId ?? null,
    })) };
    run(async () => {
      await api(url, 'POST', { ...payload, requestKey: requestKey({ url, ...payload }) });
      request.current = null;
      setSelection(undefined);
      setPreview(undefined);
      setNotice(`已采纳 ${payload.shots.length} 镜`);
      await cache.invalidateQueries({ queryKey: ['workspace', user?.id] });
    });
  }

  function retry(member: Member) {
    if (member.state === 'unknown' && !window.confirm('原出图结果未知，确认提交新的生成尝试？')) return;
    run(async () => {
      const task = `/api/tasks/${member.taskId}`;
      if (member.state === 'paused') await api(`${task}/dispatch/resume`, 'POST', {});
      else await api(`${task}/retry`, 'POST', { acknowledgeUnknown: member.state === 'unknown' });
    });
  }

  function archive(variant: Variant) {
    if (!window.confirm(`归档「${variant.name}」并移出方案对比？`)) return;
    run(async () => {
      await api(`${path}/${variant.id}/archive`, 'POST');
      if (selection?.variant.id === variant.id) setSelection(undefined);
      if (draft?.base?.id === variant.id) setDraft(undefined);
      setPreview(undefined);
    });
  }

  if (mode !== 'api' || !user) return null;
  return <div className="border-b border-stroke/15 px-6 py-3">
    <Button size="sm" variant="outline" onClick={() => setOpen(true)}>分镜出图方案</Button>
    <Modal open={open} onClose={() => { if (!busy) setOpen(false); }} width="max-w-6xl" labelledBy="storyboard-variants-title">
      <div className="space-y-4 p-6 text-sm">
        <header className="flex flex-wrap items-center gap-3">
          <h2 id="storyboard-variants-title" className="mr-auto font-medium">分镜出图方案</h2>
          {canEdit && <Button size="sm" variant="outline" disabled={busy || active.length >= 6} onClick={() => begin()}>新建出图方案</Button>}
          <Button size="sm" variant="ghost" disabled={busy} onClick={() => setOpen(false)}>关闭</Button>
        </header>
        <p className="text-xs text-muted">用不同镜头参数出图。按镜头对比后，采纳选中的参数和分镜图。</p>
        {notice && <p role="status" className="text-xs text-success-soft">{notice}</p>}
        {error && <p role="alert" className="text-xs text-error-soft">{error.message}</p>}
        {variants.isPending && <p className="text-xs text-muted">正在读取方案…</p>}
        {variants.isSuccess && active.length === 0 && !draft && <p className="text-xs text-muted">本集暂无出图方案。</p>}

        {draft && canEdit && <form className="space-y-3 rounded-card border border-stroke/25 p-4" onSubmit={event => { event.preventDefault(); submit(); }}>
          <h3 className="font-medium">{draft.base ? `局部重出 · ${draft.base.name}` : '新建出图方案'}</h3>
          {!draft.base && <label className="block text-xs">方案名称
            <input className="field mt-1 block w-full" required maxLength={120} value={draft.name} disabled={busy}
              onChange={e => setDraft({ ...draft, name: e.target.value })} />
          </label>}
          {(draft.base ? draft.base.members ?? [] : shots.filter(shot => shot.status === 'confirmed')).map(shot => {
            const id = ('id' in shot ? shot.id : shot.shotId)!;
            const edit = draft.edits[id];
            return <fieldset key={id} className="space-y-2 border-t border-stroke/15 pt-2" disabled={busy}>
              <label className="flex items-center gap-2 text-xs">
                <input type="checkbox" checked={!!edit} disabled={!edit && edits.length >= 12}
                  onChange={e => toggleEdit(id, shot, e.target.checked)} />
                {draft.base ? '重出' : '选择'}第 {shot.seq} 镜
              </label>
              {edit && <>
                <label className="block text-xs">第 {shot.seq} 镜画面描述
                  <textarea className="field mt-1 block w-full" rows={2} maxLength={16000} required
                    value={edit.description} onChange={e => changeField(id, 'description', e.target.value)} />
                </label>
                <div className="flex gap-3 text-xs">
                  <label>景别 <select className="field" value={edit.shotSize} onChange={e => changeField(id, 'shotSize', e.target.value)}>
                    {shotSizes.map(value => <option key={value}>{value}</option>)}
                  </select></label>
                  <label>运镜 <select className="field" value={edit.cameraMove} onChange={e => changeField(id, 'cameraMove', e.target.value)}>
                    {cameraMoves.map(value => <option key={value}>{value}</option>)}
                  </select></label>
                </div>
              </>}
            </fieldset>;
          })}
          <div className="flex gap-2">
            <Button type="submit" size="sm" disabled={busy || !draft.name.trim() || edits.length === 0}>提交 {edits.length} 个出图任务</Button>
            <Button size="sm" variant="ghost" disabled={busy} onClick={() => setDraft(undefined)}>取消</Button>
          </div>
        </form>}

        {rows.length > 0 && <div className="overflow-x-auto">
          <table className="w-full border-collapse text-left">
            <thead><tr><th scope="col" className="p-2 text-xs text-muted">镜头</th>{active.map(variant =>
              <th key={variant.id} scope="col" className="min-w-52 p-2 font-medium">
                {variant.name} · 第 {variant.revision} 版
                {canEdit && <div className="flex gap-2 pt-1 text-xs">
                  <button className="underline" type="button" disabled={busy} onClick={() => begin(variant)}>局部重出</button>
                  <button className="underline" type="button" disabled={busy} onClick={() => archive(variant)}>归档</button>
                </div>}
              </th>)}</tr></thead>
            <tbody>{rows.map(shot => <tr key={shot.id} className="border-t border-stroke/20 align-top">
              <th scope="row" className="min-w-16 p-2 text-xs font-medium">第 {shot.seq} 镜</th>
              {active.map(variant => {
                const member = variant.members?.find(item => item.shotId === shot.id);
                return <td key={variant.id} className="space-y-2 p-2 text-xs">
                  {member ? <>
                    {member.imageUrl && <PlaceholderImage seed={member.imageUrl} label={`${variant.name} · 第 ${shot.seq} 镜`} />}
                    <p className="text-muted">{states[member.state ?? '']} · {member.shotSize} · {member.cameraMove}{member.reused ? ' · 复用' : ''}</p>
                    <p className="whitespace-pre-wrap break-words">{member.description}</p>
                    <Sources sources={member.sources} />
                    {member.error && <p className="text-error-soft">{member.error}</p>}
                    {canEdit && (member.retryable || member.state === 'paused') &&
                      <button type="button" className="underline" disabled={busy} onClick={() => retry(member)}>
                        {member.state === 'paused' ? '恢复派发' : member.state === 'unknown' ? '确认后重试' : '重试出图'}
                      </button>}
                    {canEdit && member.imageId && <label className="flex items-center gap-2">
                      <input type="checkbox" disabled={busy} checked={!!selection && selection.variant.revisionId === variant.revisionId && selection.ids.includes(shot.id)}
                        onChange={e => toggleAdopt(variant, shot.id, e.target.checked)} />
                      采纳{variant.name}第 {shot.seq} 镜
                    </label>}
                  </> : <span className="text-muted">—</span>}
                </td>;
              })}
            </tr>)}</tbody>
          </table>
        </div>}

        {canEdit && !!selection?.ids.length && !preview && active.some(item => item.revisionId === selection.variant.revisionId) &&
          <Button size="sm" disabled={busy} onClick={showAdoption}>预览采纳 {selection.ids.length} 镜</Button>}
        {preview && canEdit && <section className="space-y-3 rounded-card border border-stroke/25 p-4" aria-label="采纳预览">
          <h3 className="font-medium">采纳预览 · {preview.variant.name}</h3>
          {preview.items.map(({ shot, member }) => <div key={shot.id} className="space-y-1 text-xs">
            <p>第 {shot.seq} 镜 · {shot.shotSize} / {shot.cameraMove} → {member.shotSize} / {member.cameraMove}</p>
            <p className="text-muted">当前描述：{shot.description}</p>
            <p>方案描述：{member.description}</p>
            <p className="text-muted">分镜图：{shot.selectedImageId ? `#${shot.selectedImageId}` : '未选图'} → #{member.imageId}</p>
          </div>)}
          <div className="flex gap-2">
            <Button size="sm" disabled={busy} onClick={adopt}>确认采纳 {preview.items.length} 镜</Button>
            <Button size="sm" variant="ghost" disabled={busy} onClick={() => {
              setPreview(undefined);
              mutation.reset();
              void cache.invalidateQueries({ queryKey: ['workspace', user?.id] });
            }}>返回对比</Button>
          </div>
        </section>}
      </div>
    </Modal>
  </div>;
}
