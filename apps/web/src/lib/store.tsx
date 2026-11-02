import { useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { QueryClient, QueryClientProvider, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, ApiError, backendAvailable, type Schema } from './api';
import { LocalStoreProvider } from './localStore';
import { StoreContext, type RuntimeConfig, type StoreValue } from './storeContext';
import { defaultSettings, buildDemoShots } from './mock';
import type { Character, Project, SceneAsset, Shot, State } from './types';

const empty: State = { user: null, projects: [], shots: [], characters: [], scenes: [], customStyles: [], settings: defaultSettings };
const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: 1000 }, mutations: { retry: false } } });
type Workspace = State & { runtime?: RuntimeConfig };

async function loadWorkspace(signal: AbortSignal): Promise<Workspace> {
  const [projects, styles, characters, scenes, runtime] = await Promise.all([
    api<Schema['Project'][]>('/api/projects', 'GET', undefined, signal),
    api<Schema['VisualPreset'][]>('/api/visual-styles', 'GET', undefined, signal),
    api<Schema['AssetCard'][]>('/api/assets/characters', 'GET', undefined, signal),
    api<Schema['AssetCard'][]>('/api/assets/scenes', 'GET', undefined, signal),
    api<RuntimeConfig>('/api/runtime', 'GET', undefined, signal),
  ]);
  const episodeData = await Promise.all(projects.flatMap(p => p.episodes ?? []).map(async episode => {
    const [shots, task] = await Promise.all([
      api<Schema['Shot'][]>(`/api/episodes/${episode.id}/shots?taskActive=true`, 'GET', undefined, signal),
      api<Schema['Task'] | undefined>(`/api/episodes/${episode.id}/breakdowns/latest`, 'GET', undefined, signal),
    ]);
    return { id: episode.id, shots, task };
  }));
  return {
    ...empty, runtime,
    projects: projects.map(p => ({ ...p, episodes: (p.episodes ?? []).map(e => ({ ...e, breakdownTask: episodeData.find(x => x.id === e.id)?.task })) })) as Project[],
    shots: episodeData.flatMap(e => e.shots) as Shot[], characters: characters as Character[], scenes: scenes as SceneAsset[],
    customStyles: styles.filter(s => s.custom) as State['customStyles'],
  };
}

function ApiStoreProvider({ children }: { children: ReactNode }) {
  const cache = useQueryClient();
  const [error, setError] = useState('');
  const [saving, setSaving] = useState(false);
  const edits = useRef(new Map<string, { method: string; body: Record<string, unknown> }>());
  const timer = useRef<number | undefined>(undefined);
  const draining = useRef<Promise<void> | undefined>(undefined);
  const operations = useRef(new Map<string, Promise<unknown>>());
  const session = useQuery({ queryKey: ['session'], queryFn: async () => {
    try { return await api<Schema['Me']>('/api/me'); }
    catch (cause) { if (cause instanceof ApiError && cause.status === 401) return null; throw cause; }
  }, retry: false });
  const key = useMemo(() => ['workspace', session.data?.id ?? 'anonymous'], [session.data?.id]);
  const workspace = useQuery({ queryKey: key, enabled: !!session.data, queryFn: ({ signal }) => loadWorkspace(signal),
    refetchOnWindowFocus: () => !edits.current.size && !draining.current,
  });
  const state: Workspace = { ...(workspace.data ?? empty), user: session.data ? { id: session.data.id, email: session.data.email ?? '' } : null };
  const current = useRef(state); current.current = state;
  const keyRef = useRef(key); keyRef.current = key;

  function report(cause: unknown) {
    if (cause instanceof ApiError && cause.status === 401) {
      edits.current.clear(); window.clearTimeout(timer.current); setSaving(false);
      cache.removeQueries({ queryKey: ['workspace'] }); cache.setQueryData(['session'], null);
    }
    setError(cause instanceof Error ? cause.message : '操作失败，请重试');
  }
  async function refresh() {
    await cache.invalidateQueries({ queryKey: keyRef.current, refetchType: 'none' });
    if (!edits.current.size && !draining.current) await cache.refetchQueries({ queryKey: keyRef.current, type: 'active' });
  }
  function flush(): Promise<void> {
    if (draining.current) return draining.current;
    window.clearTimeout(timer.current); setSaving(true);
    const result = (async () => {
      while (edits.current.size) {
        const [path, change] = edits.current.entries().next().value!;
        edits.current.delete(path);
        try { await api(path, change.method, change.body); }
        catch (cause) {
          const newer = edits.current.get(path);
          edits.current.set(path, { ...change, body: { ...change.body, ...newer?.body } });
          report(cause); throw cause;
        }
      }
    })();
    draining.current = result;
    void result.finally(() => { draining.current = undefined; setSaving(edits.current.size > 0); }).catch(() => {});
    return result;
  }
  function edit(path: string, body: Record<string, unknown>, update: (state: Workspace) => Workspace, method = 'PATCH') {
    void cache.cancelQueries({ queryKey: keyRef.current });
    cache.setQueryData<Workspace>(keyRef.current, s => update(s ?? current.current));
    const prior = edits.current.get(path); edits.current.set(path, { method, body: { ...prior?.body, ...body } });
    setSaving(true); window.clearTimeout(timer.current);
    timer.current = window.setTimeout(() => { void flush().then(refresh).catch(() => {}); }, 500);
  }
  async function perform<T>(name: string, action: () => Promise<T>): Promise<T> {
    const prior = operations.current.get(name); if (prior) return prior as Promise<T>;
    const promise = (async () => { await flush(); setError(''); const value = await action(); await refresh(); return value; })()
      .catch(cause => { report(cause); void refresh(); throw cause; }).finally(() => operations.current.delete(name));
    operations.current.set(name, promise); return promise;
  }
  const run = (name: string, action: () => Promise<unknown>) => { void perform(name, action).catch(() => {}); };
  async function retryTask(id: string, acknowledgeUnknown = false) { await api(`/api/tasks/${id}/retry`, 'POST', { acknowledgeUnknown }); }
  function retryOrGenerate(task: { state: string; taskId?: string; retryable?: boolean; resumable?: boolean }, generate: () => Promise<unknown>) {
    if (task.taskId && task.state === 'unknown') {
      if (!task.resumable && !window.confirm('外部可能已执行，重试可能重复计费。确认再次尝试？')) return Promise.resolve();
      return retryTask(task.taskId, true);
    }
    if (task.taskId && task.state === 'failed') {
      if (!task.retryable) throw new Error('该任务不可重试，请检查失败原因；修改输入后可重新提交');
      return retryTask(task.taskId);
    }
    return generate();
  }
  useEffect(() => {
    const leave = (event: BeforeUnloadEvent) => { if (edits.current.size || draining.current) event.preventDefault(); };
    window.addEventListener('beforeunload', leave); return () => window.removeEventListener('beforeunload', leave);
  }, []);
  useEffect(() => {
    let polling = false;
    const tick = async () => {
      if (polling || document.visibilityState === 'hidden' || edits.current.size || draining.current || !current.current.user) return;
      polling = true;
      try {
        const snapshot = current.current;
        const owner = snapshot.user?.id;
        const targetKey = [...keyRef.current];
        const episodes = new Set(snapshot.shots.filter(s => ['queued', 'running'].includes(s.imageState) || ['queued', 'running'].includes(s.video.state)).map(s => s.episodeId));
        snapshot.projects.forEach(p => p.episodes.filter(e => e.breakdown === 'running').forEach(e => episodes.add(e.id)));
        for (const id of episodes) {
          const [shots, episode, task] = await Promise.all([
            api<Schema['Shot'][]>(`/api/episodes/${id}/shots?taskActive=true`), api<Schema['Episode']>(`/api/episodes/${id}`),
            api<Schema['Task'] | undefined>(`/api/episodes/${id}/breakdowns/latest`),
          ]);
          if (edits.current.size || draining.current || current.current.user?.id !== owner) continue;
          cache.setQueryData<Workspace>(targetKey, s => s && ({ ...s, shots: [...s.shots.filter(x => x.episodeId !== id), ...shots as Shot[]],
            projects: s.projects.map(p => ({ ...p, episodes: p.episodes.map(e => e.id === id ? { ...episode, breakdownTask: task } as typeof e : e) })) }));
        }
        if ([...snapshot.characters, ...snapshot.scenes].some(a => ['queued', 'running'].includes(a.generationState ?? 'idle')) || episodes.size) {
          const [characters, scenes] = await Promise.all([api<Schema['AssetCard'][]>('/api/assets/characters'), api<Schema['AssetCard'][]>('/api/assets/scenes')]);
          if (!edits.current.size && !draining.current && current.current.user?.id === owner) cache.setQueryData<Workspace>(targetKey, s => s && ({ ...s, characters: characters as Character[], scenes: scenes as SceneAsset[] }));
        }
      } catch (cause) { report(cause); }
      finally { polling = false; }
    };
    const interval = window.setInterval(() => { void tick(); }, 3000);
    return () => window.clearInterval(interval);
  }, [cache]);
  useEffect(() => { if (workspace.error) report(workspace.error); if (session.error) report(session.error); }, [workspace.error, session.error]);

  const value: StoreValue = {
    ...state, mode: 'api', saving, error, dismissError: () => setError(''), retrySave: () => { void flush().then(refresh).then(() => setError('')).catch(() => {}); },
    login: async (email, password) => {
      const me = await api<Schema['Me']>('/api/auth/login', 'POST', { email, password });
      cache.removeQueries({ queryKey: ['workspace'] }); cache.setQueryData(['session'], me);
    },
    logout: () => run('logout', async () => { await api('/api/auth/logout', 'POST'); cache.removeQueries({ queryKey: ['workspace'] }); cache.setQueryData(['session'], null); }),
    createProject: input => perform('create-project', async () => (await api<Schema['Project']>('/api/projects', 'POST', input)).id!),
    updateProject: (id, patch) => edit(`/api/projects/${id}`, patch, s => ({ ...s, projects: s.projects.map(p => p.id === id ? { ...p, ...patch } : p) })),
    renameProject: (id, name) => run(`rename-${id}`, () => api(`/api/projects/${id}`, 'PATCH', { name })),
    deleteProject: id => run(`delete-${id}`, () => api(`/api/projects/${id}`, 'DELETE')),
    createEpisode: projectId => perform(`create-episode-${projectId}`, async () => (await api<Schema['Episode']>(`/api/projects/${projectId}/episodes`, 'POST', {})).id!),
    updateEpisode: (id, patch) => edit(`/api/episodes/${id}`, patch, s => ({ ...s, projects: s.projects.map(p => ({ ...p, episodes: p.episodes.map(e => e.id === id ? { ...e, ...patch } : e) })) })),
    setEpisodeStory: (id, story) => edit(`/api/episodes/${id}/story`, { story }, s => ({ ...s, projects: s.projects.map(p => ({ ...p, episodes: p.episodes.map(e => e.id === id ? { ...e, story, breakdown: e.breakdown === 'done' ? 'idle' : e.breakdown } : e) })) }), 'PUT'),
    runBreakdown: id => run(`breakdown-${id}`, () => api(`/api/episodes/${id}/breakdowns`, 'POST', { replaceExisting: current.current.shots.some(s => s.episodeId === id) })),
    updateShot: (id, patch) => edit(`/api/shots/${id}`, patch, s => ({ ...s, shots: s.shots.map(shot => shot.id === id ? { ...shot, ...patch } : shot) })),
    confirmShot: id => run(`status-${id}`, () => api(`/api/shots/${id}/status`, 'PUT', { status: 'confirmed' })),
    unconfirmShot: id => run(`status-${id}`, () => api(`/api/shots/${id}/status`, 'PUT', { status: 'draft' })),
    confirmAll: id => run(`confirm-${id}`, () => api(`/api/episodes/${id}/shots/confirm-all`, 'POST', {})),
    generateImage: id => run(`image-${id}`, () => {
      const shot = current.current.shots.find(s => s.id === id)!;
      return retryOrGenerate({ state: shot.imageState, taskId: shot.imageTaskId, retryable: shot.imageRetryable },
        () => api(`/api/shots/${id}/image-tasks`, 'POST', shot.images.length ? { regenerateNonce: crypto.randomUUID() } : {}));
    }),
    submitBatch: (episodeId, type, requestKey, shotIds, regenerate) => perform(`batch-${episodeId}-${type}`, () => api<Schema['GenerationBatch']>(`/api/episodes/${episodeId}/generation-batches`, 'POST', { type, requestKey, shotIds, regenerate })),
    selectImage: (id, imageId) => run(`select-image-${id}`, () => api(`/api/shots/${id}/selected-image`, 'PUT', { imageId: Number(imageId) })),
    generateVideo: id => run(`video-${id}`, () => {
      const shot = current.current.shots.find(s => s.id === id)!;
      return retryOrGenerate(shot.video, () => api(`/api/shots/${id}/video-tasks`, 'POST', shot.video.ready ? { regenerateNonce: crypto.randomUUID() } : {}));
    }),
    addAssetVersion: (kind, id, source, file) => run(`asset-${kind}-${id}`, async () => {
      const path = `/api/assets/${kind === 'character' ? 'characters' : 'scenes'}/${id}`;
      if (source === 'uploaded') {
        if (!file) return; const body = new FormData(); body.append('file', file); body.append('label', file.name.replace(/\.[^.]+$/, ''));
        return api(`${path}/versions`, 'POST', body);
      }
      const card = (kind === 'character' ? current.current.characters : current.current.scenes).find(a => a.id === id);
      if (card?.taskId && ['failed', 'unknown'].includes(card.generationState ?? '')) {
        const task = await api<Schema['Task']>(`/api/tasks/${card.taskId}`);
        return retryOrGenerate({ state: task.state!, taskId: task.id, retryable: task.retryable, resumable: task.resumable }, () => Promise.resolve());
      }
      return api(`${path}/image-tasks`, 'POST', { regenerateNonce: crypto.randomUUID() });
    }),
    selectAssetVersion: (kind, id, versionId) => run(`select-asset-${kind}-${id}`, () => api(`/api/assets/${kind === 'character' ? 'characters' : 'scenes'}/${id}/selected-version`, 'PUT', { versionId: Number(versionId) })),
    saveSettings: () => setError('模型由后端本机环境配置，当前页面只展示实际配置'),
    addCustomStyle: input => perform('custom-style', async () => {
      const blob = await (await fetch(input.image)).blob(); const body = new FormData(); body.append('label', input.label); body.append('image', blob, 'reference.jpg');
      return (await api<Schema['VisualPreset']>('/api/visual-styles', 'POST', body)).id!;
    }),
    removeCustomStyle: id => run(`remove-style-${id}`, () => api(`/api/visual-styles/${id}`, 'DELETE')),
    reset: () => setError('后端数据请在项目列表逐项删除'), retryTask: (id, ack) => run(`retry-${id}`, () => retryTask(id, ack)),
  };
  if (session.isPending || session.data && workspace.isPending) return <div className="flex min-h-screen items-center justify-center text-sm text-muted">正在读取工作台…</div>;
  return <StoreContext.Provider value={value}>{children}</StoreContext.Provider>;
}

function ConnectionNotice() {
  const store = useStore();
  if (store.error) return <div role="alert" className="fixed bottom-5 right-5 z-[80] max-w-md rounded-card border border-error/40 bg-panel p-4 text-xs shadow-xl">
    <p className="leading-5 text-error-soft">{store.error}</p>
    <div className="mt-2 flex gap-4"><button onClick={store.retrySave}>重试保存 / 刷新</button><button onClick={store.dismissError}>关闭</button></div>
  </div>;
  return <div className="fixed bottom-2 right-3 z-20 rounded bg-panel/90 px-2 py-1 text-[10px] text-muted">
    {store.mode === 'demo' ? <>离线演示 · 数据仅保存在浏览器 <button className="ml-2 underline" onClick={() => location.reload()}>重新连接</button></> : store.saving ? '修改正在保存…' : '后端已连接'}
  </div>;
}
export function StoreProvider({ children }: { children: ReactNode }) {
  const [mode, setMode] = useState<'api' | 'demo' | null>(null);
  useEffect(() => { let active = true; void backendAvailable().then(ok => { if (active) setMode(ok ? 'api' : 'demo'); }); return () => { active = false; }; }, []);
  if (!mode) return <div className="flex min-h-screen items-center justify-center text-sm text-muted">正在连接工作台…</div>;
  const Provider = mode === 'api' ? ApiStoreProvider : LocalStoreProvider;
  return <QueryClientProvider client={client}><Provider>{children}<ConnectionNotice /></Provider></QueryClientProvider>;
}
export function useStore(): StoreValue { const value = useContext(StoreContext); if (!value) throw new Error('useStore 必须在 StoreProvider 内使用'); return value; }
export function useProjects(): Project[] { return useStore().projects; }
export function useProject(id: string): Project | undefined { return useStore().projects.find(p => p.id === id); }
export function useEpisodeShots(id: string): Shot[] { return useStore().shots.filter(s => s.episodeId === id).sort((a, b) => a.seq - b.seq); }
export function useProjectShots(id: string): Shot[] { return useStore().shots.filter(s => s.projectId === id).sort((a, b) => a.seq - b.seq); }
export { buildDemoShots };
