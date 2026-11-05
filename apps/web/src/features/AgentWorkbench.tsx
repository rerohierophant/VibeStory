import { useEffect, useRef, useState, type FormEvent, type ReactNode } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Outlet, useMatch, useNavigate } from 'react-router-dom';
import { Button } from '@/components/Button';
import { creativeEpisodeKey } from './CreativePanels';
import { ExecutionRecord } from './AgentExecution';
import { EpisodeWorkflowStatus } from '@/features/EpisodeWorkflowStatus';
import { api, ApiError, type Schema } from '@/lib/api';
import { useStore } from '@/lib/store';

const fieldLabels: Record<string, string> = { shotSize: '景别', dialogue: '台词', description: '画面描述' };
const paths = {
  panel: <><rect x="3" y="4" width="18" height="16" rx="2" /><path d="M15 4v16M7 9l3 3-3 3" /></>,
  history: <><path d="M3 4v5h5M3.7 8.8a8.5 8.5 0 1 1 0 6.4M12 7v5l3 2" /></>,
  plus: <path d="M12 5v14M5 12h14" />,
  back: <path d="M14 6l-6 6 6 6" />,
  robot: <><rect x="5" y="8" width="14" height="11" rx="2" /><path d="M12 8V4H9M2 13h3m14 0h3M9 13v2m6-2v2" /></>,
  clip: <path d="M8.5 13.5l6.4-6.4a2 2 0 0 1 2.8 2.8l-8.5 8.5a4 4 0 0 1-5.7-5.7l8.5-8.5a5 5 0 0 1 7.1 7.1l-7.8 7.8" />,
  send: <path d="M12 19V5m-6 6 6-6 6 6" />,
  close: <path d="m6 6 12 12M18 6 6 18" />,
};

function Icon({ name }: { name: keyof typeof paths }) {
  return <svg aria-hidden="true" viewBox="0 0 24 24" className="size-5 shrink-0" fill="none"
    stroke="currentColor" strokeWidth="1.65" strokeLinecap="round" strokeLinejoin="round">{paths[name]}</svg>;
}

function IconButton({ icon, label, onClick, disabled }: {
  icon: keyof typeof paths; label: string; onClick: () => void; disabled?: boolean;
}) {
  return <button type="button" aria-label={label} title={label} onClick={onClick} disabled={disabled}
    className="grid size-9 shrink-0 place-items-center rounded-card text-muted transition hover:bg-hover/45 hover:text-fg disabled:opacity-40">
    <Icon name={icon} />
  </button>;
}

export function AgentWorkbench() {
  const [open, setOpen] = useState(false);
  const { mode, projects, user, workspaceLoading } = useStore();
  const projectRoute = useMatch('/projects/:id/*');
  const episodeRoute = useMatch('/projects/:id/episodes/:episodeId/:tab?');
  const project = projects.find(item => item.id === projectRoute?.params.id);
  const episode = project?.episodes.find(item => item.id === episodeRoute?.params.episodeId);
  const scope = project ? project.name + (episode ? ` · 第 ${episode.index} 集` : '') : undefined;
  const showAgent = !project?.teamId && !workspaceLoading;

  return (
    <div className="relative flex h-full min-h-0 overflow-hidden">
      <div className="h-full min-w-0 flex-1"><Outlet /></div>
      {showAgent && !open && (
        <div className="shrink-0 border-l border-stroke/20 bg-panel/50 p-2">
          <Button type="button" variant="ghost" size="sm" aria-expanded={false} aria-controls="agent-panel" onClick={() => setOpen(true)}>
            Agent
          </Button>
        </div>
      )}
      <aside id="agent-panel" aria-label="Agent 对话"
        className={`${showAgent && open ? 'flex' : 'hidden'} absolute inset-y-0 right-0 z-30 w-[26rem] max-w-full shrink-0 flex-col border-l border-stroke/20 bg-panel shadow-xl xl:static xl:shadow-none`}>
        {mode === 'demo' ? <>
          <header className="flex h-14 shrink-0 items-center gap-2 border-b border-stroke/20 px-3">
            <IconButton icon="panel" label="收起 Agent" onClick={() => setOpen(false)} />
            <h2 className="font-medium text-fg">新会话</h2>
          </header>
          <div className="grid flex-1 place-items-center p-4 text-sm text-muted">连接后端后即可使用 Agent。</div>
        </> : user?.id && <AgentConversation key={`${user.id}/${episode?.id ?? 'workspace'}`} userId={user.id} projectId={showAgent ? projectRoute?.params.id : undefined}
          episodeId={showAgent ? episodeRoute?.params.episodeId : undefined}
          scope={showAgent ? scope : undefined} open={showAgent && open} onClose={() => setOpen(false)} />}
      </aside>
    </div>
  );
}

function AgentConversation({ userId, projectId, episodeId, scope, open, onClose }: {
  userId: string; projectId?: string; episodeId?: string; scope?: string; open: boolean; onClose: () => void;
}) {
  const { sendAgentMessage, decideShotEdit, confirmAgentWorkflow } = useStore();
  const cache = useQueryClient();
  const navigate = useNavigate();
  const selectionKey = `vibestory.agent.conversation.${userId}${episodeId ? `.${episodeId}` : ''}`;
  const [selection, setSelection] = useState(() => {
    const remembered = sessionStorage.getItem(selectionKey);
    return { id: remembered ?? crypto.randomUUID(), saved: !!remembered };
  });
  const [useMain, setUseMain] = useState(() => !!episodeId && !sessionStorage.getItem(selectionKey));
  const main = useQuery({
    queryKey: ['creative-main', userId, episodeId],
    enabled: open && useMain && !!episodeId,
    queryFn: () => api<Schema['CreativeEpisode']>(`/api/episodes/${episodeId}/creative/session`, 'POST'),
    retry: false,
  });
  const creative = useQuery({
    queryKey: creativeEpisodeKey(userId, episodeId),
    enabled: open && !!episodeId,
    queryFn: ({ signal }) => api<Schema['CreativeEpisode']>(`/api/episodes/${episodeId}/creative`, 'GET', undefined, signal),
    refetchInterval: open ? 2500 : false,
  });
  const [historyOpen, setHistoryOpen] = useState(false);
  const [draft, setDraft] = useState('');
  const [skillsDismissed, setSkillsDismissed] = useState(false);
  const [skillIndex, setSkillIndex] = useState(0);
  const [files, setFiles] = useState<File[]>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [staleProposal, setStaleProposal] = useState<string>();
  const composer = useRef<HTMLTextAreaElement>(null);
  const upload = useRef<HTMLInputElement>(null);
  const scroll = useRef<HTMLDivElement>(null);
  const retry = useRef<{ key: string; projectId?: string; episodeId?: string }>(undefined);

  const historyKey = ['agent-conversations', userId];
  const transcriptKey = ['agent-transcript', userId, selection.id];
  const path = `/api/agent/conversations/${selection.id}`;
  const history = useQuery({
    queryKey: historyKey, enabled: open && historyOpen,
    queryFn: ({ signal }) => api<Schema['Conversation'][]>('/api/agent/conversations', 'GET', undefined, signal),
  });
  const transcript = useQuery({
    queryKey: transcriptKey, enabled: open && selection.saved && !historyOpen,
    queryFn: ({ signal }) => api<Schema['Transcript']>(path, 'GET', undefined, signal),
    refetchInterval: query => open && !historyOpen && query.state.data?.messages?.some(message =>
      ['WAITING_BREAKDOWN', 'WAITING_CONFIRMATION', 'GENERATING', 'PAUSED'].includes(message.workflow?.state ?? '')
      || ['QUEUED', 'RUNNING', 'WAITING'].includes(message.execution?.state ?? '')) ? 1500 : false,
  });
  const messages = transcript.data?.messages ?? [];
  const skillSearch = draft.trimStart().match(/^[/／]([^\n\r]*)$/u)?.[1];
  const skillsOpen = skillSearch !== undefined && !skillsDismissed && open && !historyOpen && !busy;
  const skills = useQuery({
    queryKey: ['agent-skills', userId], enabled: skillsOpen, staleTime: 5 * 60 * 1000,
    queryFn: ({ signal }) => api<Schema['Entry'][]>('/api/agent/skills', 'GET', undefined, signal),
  });
  const search = skillSearch?.trim().toLowerCase() ?? '';
  const matches = skillsOpen ? (skills.data ?? []).flatMap(group => {
    const groupMatches = `${group.label ?? ''} ${group.key ?? ''}`.toLowerCase().includes(search);
    const examples = group.examples?.filter(example => groupMatches || example.toLowerCase().includes(search)) ?? [];
    return examples.length ? [{ ...group, examples }] : [];
  }) : [];
  const options = matches.flatMap(group => group.examples);
  const activeSkill = options.length ? `agent-skill-${Math.min(skillIndex, options.length - 1)}` : undefined;

  useEffect(() => {
    if (activeSkill) document.getElementById(activeSkill)?.scrollIntoView({ block: 'nearest' });
  }, [activeSkill]);

  useEffect(() => {
    if (open && !historyOpen && scroll.current) scroll.current.scrollTop = scroll.current.scrollHeight;
  }, [messages.length, open, historyOpen, selection.id, busy]);

  useEffect(() => {
    if (!useMain || !main.data?.mainSessionId) return;
    const id = main.data.mainSessionId;
    sessionStorage.setItem(selectionKey, id);
    setSelection({ id, saved: true });
    setUseMain(false);
  }, [main.data?.mainSessionId, selectionKey, useMain]);

  function select(id?: string) {
    if (busy) return;
    setUseMain(false);
    if (id) sessionStorage.setItem(selectionKey, id);
    else sessionStorage.removeItem(selectionKey);
    setSelection({ id: id ?? crypto.randomUUID(), saved: !!id });
    setHistoryOpen(false);
    setDraft('');
    setFiles([]);
    setError('');
    setStaleProposal(undefined);
    retry.current = undefined;
  }

  function attach(added: File[]) {
    if (!added.length || busy) return;
    const next = [...files, ...added];
    if (added.some(file => !['image/jpeg', 'image/png'].includes(file.type))) {
      setError('请选择 PNG 或 JPEG 图片');
    } else if (next.length > 4 || next.reduce((total, file) => total + file.size, 0) > 5 * 1024 * 1024) {
      setError('每条消息最多 4 张图片，合计不超过 5 MiB');
    } else {
      setFiles(next);
      retry.current = undefined;
      setError('');
    }
  }

  function selectSkill(example: string) {
    setDraft(example);
    setSkillIndex(0);
    setError('');
    retry.current = undefined;
    composer.current?.focus();
  }

  async function send(event: FormEvent) {
    event.preventDefault();
    const instruction = draft.trim();
    if (busy || useMain || skillSearch !== undefined || (!instruction && !files.length) || !sendAgentMessage) return;
    if (!retry.current || retry.current.projectId !== projectId || retry.current.episodeId !== episodeId) {
      retry.current = { key: crypto.randomUUID(), projectId, episodeId };
    }
    const requestKey = retry.current.key;
    const body = new FormData();
    body.append('requestKey', requestKey);
    body.append('instruction', instruction);
    if (projectId) body.append('projectId', projectId);
    if (episodeId) body.append('episodeId', episodeId);
    for (const file of files) body.append('images', file);

    setBusy(true);
    setError('');
    try {
      const result = await sendAgentMessage(selection.id, requestKey, body);
      await cache.cancelQueries({ queryKey: transcriptKey });
      cache.setQueryData(transcriptKey, result);
      void cache.invalidateQueries({ queryKey: historyKey });
      sessionStorage.setItem(selectionKey, selection.id);
      setSelection({ id: selection.id, saved: true });
      retry.current = undefined;
      setDraft('');
      setFiles([]);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '消息发送失败');
    } finally {
      setBusy(false);
    }
  }

  async function decide(message: Schema['ChatMessage'], decision: 'apply' | 'discard') {
    const id = message.result?.proposal?.id;
    if (busy || !decideShotEdit || !message.episodeId || !id) return;
    setBusy(true);
    setError('');
    try {
      const proposal = await decideShotEdit(message.episodeId, id, decision);
      await cache.cancelQueries({ queryKey: transcriptKey });
      cache.setQueryData<Schema['Transcript']>(transcriptKey, value => value && ({
        ...value, messages: value.messages?.map(item => item.result?.proposal?.id === id
          ? { ...item, proposalState: proposal.state, imageTaskId: proposal.imageTaskId } : item),
      }));
    } catch (cause) {
      if (cause instanceof ApiError && decision === 'apply' &&
        (cause.status === 404 || (cause.status === 409 && cause.message.includes('镜头已被修改')))) {
        setStaleProposal(id);
        setError('提案无法应用。请重新预览当前镜头。');
      } else {
        setError(cause instanceof Error ? cause.message : '无法处理提案');
      }
    } finally {
      setBusy(false);
    }
  }

  async function confirmWorkflow(workflowId: string) {
    if (busy || !confirmAgentWorkflow) return;
    setBusy(true);
    setError('');
    try {
      const result = await confirmAgentWorkflow(selection.id, workflowId);
      await cache.cancelQueries({ queryKey: transcriptKey });
      cache.setQueryData(transcriptKey, result);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '无法确认出图');
      void cache.invalidateQueries({ queryKey: transcriptKey });
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <header className="flex h-14 shrink-0 items-center gap-2 border-b border-stroke/20 px-3">
        <IconButton icon="panel" label="收起 Agent" onClick={onClose} />
        <div className="min-w-0 flex-1">
          <h2 className="truncate text-sm font-semibold text-fg">{historyOpen ? '历史会话' : transcript.data?.conversation?.title ?? '新会话'}</h2>
          {!historyOpen && scope && <p className="truncate text-[11px] text-muted/75" title={scope}>{scope}</p>}
        </div>
        <IconButton icon={historyOpen ? 'back' : 'history'} label={historyOpen ? '返回对话' : '历史会话'}
          onClick={() => setHistoryOpen(!historyOpen)} disabled={busy} />
        <IconButton icon="plus" label="新建会话" onClick={() => select()} disabled={busy} />
      </header>
      {episodeId && open && !historyOpen && <div className="space-y-1 border-b border-stroke/20 px-4 py-2">
        <EpisodeWorkflowStatus episodeId={episodeId} />
        <button type="button" className="text-xs text-muted underline" disabled={busy}
          onClick={() => { select(); setUseMain(true); }}>本集主会话</button>
        {main.isError && useMain && <p className="text-xs text-error-soft" role="alert">{main.error.message}</p>}
        {creative.data?.script?.summary && <p className="text-xs text-muted">剧本 · {creative.data.script.summary}</p>}
        {creative.data?.storyboard?.summary && <p className="text-xs text-muted">分镜 · {creative.data.storyboard.summary}</p>}
      </div>}
      {historyOpen ? (
        <div className="min-h-0 flex-1 overflow-y-auto px-3 py-4 scroll-thin">
          {history.isLoading && <p className="px-2 text-sm text-muted">正在读取历史会话…</p>}
          {history.isError && <p role="alert" className="px-2 text-sm text-error-soft">{history.error.message}</p>}
          {history.isSuccess && !history.data.length && <p className="px-2 text-sm text-muted">还没有历史会话</p>}
          <ul className="space-y-1">
            {history.data?.map(item => item.id && (
              <li key={item.id}>
                <button type="button" onClick={() => select(item.id)} aria-current={item.id === selection.id ? 'true' : undefined}
                  className="flex w-full items-center justify-between gap-2 rounded-card px-3 py-2.5 text-left hover:bg-hover/40 aria-[current=true]:bg-hover/40">
                  <span className="truncate text-sm text-fg">{item.title}</span>
                  {item.updatedAt && <time dateTime={item.updatedAt} className="shrink-0 text-xs text-muted">
                    {new Intl.DateTimeFormat('zh-CN', { month: 'numeric', day: 'numeric' }).format(new Date(item.updatedAt))}
                  </time>}
                </button>
              </li>
            ))}
          </ul>
        </div>
      ) : (
        <>
          <div ref={scroll} className="min-h-0 flex-1 overflow-y-auto scroll-thin">
            {transcript.isLoading ? <p className="p-4 text-sm text-muted">正在读取会话…</p> : transcript.isError
              ? <p role="alert" className="p-4 text-sm text-error-soft">{transcript.error.message}</p>
              : messages.length === 0 ? (
                <div className="flex min-h-full flex-col items-center justify-center gap-3 p-6 text-center">
                  <div className="grid size-11 place-items-center rounded-2xl bg-panel-2/60 text-fg"><Icon name="robot" /></div>
                  <p className="text-base font-medium text-fg">在下方输入消息开始对话</p>
                  <p className="text-sm text-muted/80">输入工作台指令，或添加图片</p>
                </div>
              ) : (
                <div className="space-y-6 px-4 py-5">
                  {messages.map(message => (
                    <div key={message.id} className="space-y-3">
                      <div className="ml-auto w-fit max-w-[90%] space-y-2 rounded-panel rounded-br-md bg-panel-2/75 px-3 py-2.5">
                        {!!message.images?.length && <div className="flex flex-wrap gap-2">
                          {message.images.map(image => (
                            <a key={image.id} href={image.url} target="_blank" rel="noreferrer" aria-label={`查看图片 ${image.name}`}>
                              <img src={image.url} alt={image.name} loading="lazy" className="max-h-48 max-w-full rounded-card object-contain" />
                            </a>
                          ))}
                        </div>}
                        {message.instruction && <p className="whitespace-pre-wrap text-sm leading-6 text-fg [overflow-wrap:anywhere]">{message.instruction}</p>}
                      </div>
                      <div className="flex items-start gap-2.5">
                        <span className="grid size-8 shrink-0 place-items-center rounded-xl bg-panel-2/60 text-muted"><Icon name="robot" /></span>
                        <div className="min-w-0 flex-1 pt-1">
                          <MessageReply message={message} busy={busy} staleProposal={staleProposal}
                            matchingScope={(message.projectId ?? undefined) === projectId && (message.episodeId ?? undefined) === episodeId}
                            onSuggestion={selectSkill} onConfirmWorkflow={id => void confirmWorkflow(id)}
                            onDecide={decision => void decide(message, decision)} onOpen={(project, episode, tab = 'story') =>
                              navigate(`/projects/${project}/episodes/${episode}/${tab}`)} />
                          <ExecutionRecord execution={message.execution} />
                        </div>
                      </div>
                    </div>
                  ))}
                </div>
              )}
          </div>
          <form onSubmit={send} className="relative shrink-0 border-t border-stroke/20 px-3 pt-3 pb-6">
            {skillsOpen && <div className="absolute inset-x-3 bottom-full z-10 mb-2 rounded-panel border border-stroke/45 bg-panel shadow-xl"
              onMouseDown={event => event.preventDefault()}>
              <p className="px-3 pt-3 text-xs font-medium text-fg">技能快捷菜单</p>
              {skills.isLoading ? <p role="status" className="p-3 text-xs text-muted">正在读取技能…</p>
                : skills.isError && !skills.data ? <p role="alert" className="p-3 text-xs text-error-soft">读取失败，请重新输入 / 试试。</p>
                : !options.length ? <p role="status" className="p-3 text-xs text-muted">没有匹配的技能</p> : null}
              <div id="agent-skill-options" role="listbox" aria-label="技能示例" className="max-h-[min(18rem,45dvh)] overflow-y-auto px-2 pb-2 scroll-thin">
                {matches.map(group => (
                  <div key={group.key} role="group" aria-label={group.label} className="pt-2">
                    <p aria-hidden="true" className="px-2 pb-1 text-[11px] text-muted">{group.label}</p>
                    {group.examples.map(example => {
                      const id = `agent-skill-${options.indexOf(example)}`;
                      return <button key={example} type="button" id={id} role="option" tabIndex={-1}
                        aria-selected={id === activeSkill} onClick={() => selectSkill(example)}
                        className="block w-full rounded-card px-2 py-2 text-left text-sm leading-5 text-fg hover:bg-hover/40 aria-selected:bg-hover/55">
                        {example}
                      </button>;
                    })}
                  </div>
                ))}
              </div>
            </div>}
            {error && <p role="alert" className="mb-2 text-sm text-error-soft">{error}</p>}
            <div className="space-y-2 rounded-panel border border-stroke/30 bg-panel-2/45 p-2.5 focus-within:border-stroke/75">
              {!!files.length && <div aria-label="待发送图片" className="flex flex-wrap gap-2 px-1">
                {files.map((file, index) => <PendingImage key={index} file={file} disabled={busy} onRemove={() => {
                  setFiles(items => items.filter((_, i) => i !== index));
                  retry.current = undefined;
                  setError('');
                }} />)}
              </div>}
              <textarea ref={composer} aria-label="Agent 指令" value={draft} disabled={busy} maxLength={500} rows={2}
                aria-controls={skillsOpen ? 'agent-skill-options' : undefined} aria-activedescendant={activeSkill}
                placeholder="输入消息，输入 / 查看技能" className="block w-full resize-none bg-transparent px-1.5 py-1 text-sm leading-6 text-fg outline-none placeholder:text-muted/65"
                onChange={event => {
                  setDraft(event.target.value); setSkillsDismissed(false); setSkillIndex(0); retry.current = undefined;
                }}
                onFocus={() => setSkillsDismissed(false)} onBlur={() => setSkillsDismissed(true)}
                onPaste={event => {
                  const pasted = Array.from(event.clipboardData.files);
                  if (pasted.length) { event.preventDefault(); attach(pasted); }
                }}
                onKeyDown={event => {
                  if (event.nativeEvent.isComposing || event.keyCode === 229) return;
                  if (skillsOpen && event.key === 'Escape') {
                    event.preventDefault();
                    setSkillsDismissed(true);
                  } else if (skillsOpen && options.length && (event.key === 'ArrowDown' || event.key === 'ArrowUp')) {
                    event.preventDefault();
                    const step = event.key === 'ArrowDown' ? 1 : -1;
                    setSkillIndex(index => (Math.min(index, options.length - 1) + step + options.length) % options.length);
                  } else if (event.key === 'Enter' && !event.shiftKey) {
                    event.preventDefault();
                    if (skillsOpen && options.length) selectSkill(options[Math.min(skillIndex, options.length - 1)]);
                    else if (skillSearch === undefined) event.currentTarget.form?.requestSubmit();
                  }
                }} />
              <div className="flex items-center justify-between">
                <input type="file" ref={upload} hidden accept="image/png,image/jpeg" multiple aria-label="选择图片文件"
                  onChange={event => { attach(Array.from(event.currentTarget.files ?? [])); event.currentTarget.value = ''; }} />
                <IconButton icon="clip" label="添加图片" onClick={() => upload.current?.click()} disabled={busy} />
                <button type="submit" aria-label="发送" title="发送" disabled={busy || useMain || skillSearch !== undefined || (!draft.trim() && !files.length)}
                  className="grid size-9 place-items-center rounded-xl bg-accent text-white transition hover:brightness-110 disabled:opacity-35">
                  {busy ? <span aria-label="处理中" className="size-4 animate-spin rounded-full border-2 border-current border-r-transparent" /> : <Icon name="send" />}
                </button>
              </div>
            </div>
          </form>
        </>
      )}
    </>
  );
}

function PendingImage({ file, disabled, onRemove }: { file: File; disabled: boolean; onRemove: () => void }) {
  const [url, setUrl] = useState('');
  useEffect(() => {
    const src = URL.createObjectURL(file);
    setUrl(src);
    return () => URL.revokeObjectURL(src);
  }, [file]);
  return <div className="relative">
    {url && <img src={url} alt={file.name} title={file.name} className="size-16 rounded-card border border-stroke/25 object-cover" />}
    <button type="button" aria-label={`移除图片 ${file.name}`} disabled={disabled} onClick={onRemove}
      className="absolute -right-1 -top-1 grid size-6 place-items-center rounded-full bg-canvas text-fg hover:bg-hover disabled:opacity-40">
      <Icon name="close" />
    </button>
  </div>;
}

function VariantChoice({ result, adopted, disabled }: { result: Schema['AgentResult']; adopted?: boolean; disabled: boolean }) {
  const choice = result.variantChoice!;
  const { user, saving } = useStore();
  const cache = useQueryClient();
  const adopt = useMutation({
    mutationFn: () => api(`/api/episodes/${choice.episodeId}/storyboard-variants/revisions/${choice.revisionId}/adopt`,
      'POST', { requestKey: choice.requestKey, shots: [{ shotId: choice.shotId, imageId: choice.imageId,
        shotVersion: choice.shotVersion, selectedImageId: choice.selectedImageId ?? null }] }),
    onSuccess: () => Promise.all([
      cache.invalidateQueries({ queryKey: ['workspace', user?.id] }),
      cache.invalidateQueries({ queryKey: ['agent-transcript', user?.id] }),
      cache.invalidateQueries({ queryKey: ['storyboard-variants', user?.id, choice.episodeId] }),
    ]),
  });
  return <section aria-label="方案采纳预览" className="space-y-2 rounded-panel border border-stroke/25 p-3 text-sm">
    <h3 className="font-medium">{result.message}</h3>
    {choice.imageUrl && <img src={choice.imageUrl} alt="待采纳分镜图" className="rounded-card" />}
    {result.details?.map(detail => <p className="break-words text-xs text-muted" key={detail}>{detail}</p>)}
    {(adopted || adopt.isSuccess) ? <p role="status" className="text-xs text-success-soft">已采纳分镜图和镜头参数</p> :
      <Button size="sm" disabled={disabled || saving || adopt.isPending} onClick={() => adopt.mutate()}>确认采纳这一镜</Button>}
    {adopt.error && <p role="alert" className="text-xs text-error-soft">{adopt.error.message}</p>}
  </section>;
}

function MessageReply({ message, busy, staleProposal, matchingScope, onSuggestion, onConfirmWorkflow, onDecide, onOpen }: {
  message: Schema['ChatMessage']; busy: boolean; staleProposal?: string;
  matchingScope: boolean; onSuggestion: (instruction: string) => void; onConfirmWorkflow: (id: string) => void;
  onDecide: (decision: 'apply' | 'discard') => void; onOpen: (project: string, episode: string, tab?: 'story' | 'storyboard') => void;
}) {
  if (message.error) return <p className="text-sm leading-6 text-error-soft">{message.error}</p>;
  if (message.workflow) {
    return <WorkflowReply workflow={message.workflow} busy={busy} onConfirm={onConfirmWorkflow} onOpen={onOpen} />;
  }
  if (message.suggestedInstruction) {
    return <section aria-label="建议指令" className="space-y-2 rounded-panel border border-stroke/25 p-3 text-sm leading-6">
      <p className="text-muted">可以这样操作，请核对指令后发送。</p>
      <p className="whitespace-pre-wrap [overflow-wrap:anywhere]">{message.suggestedInstruction}</p>
      <Button type="button" variant="outline" size="sm" disabled={busy || !matchingScope}
        onClick={() => onSuggestion(message.suggestedInstruction!)}>填入输入框</Button>
      {!matchingScope && <p className="text-xs text-muted">回到发送这条消息时的页面可使用此建议。</p>}
    </section>;
  }
  const result = message.result;
  if (!result) return null;
  if (result.variantChoice) return <VariantChoice result={result} adopted={message.variantAdopted} disabled={busy || !matchingScope} />;
  const proposal = result.proposal;
  if (proposal) {
    const state = message.proposalState;
    let action: ReactNode;
    if (state === 'APPLIED') action = <p role="status" className="text-xs text-success-soft">已保存第 {proposal.shotNumber} 镜</p>;
    else if (state === 'DISCARDED') action = <p className="text-xs text-muted">已放弃</p>;
    else if (state === 'PENDING') action = <div className="flex flex-wrap gap-2">
      <Button type="button" size="sm" disabled={busy || staleProposal === proposal.id} onClick={() => onDecide('apply')}>
        {proposal.generateImage ? '保存并生成新候选' : '确认保存'}
      </Button>
      <Button type="button" variant="ghost" size="sm" disabled={busy} onClick={() => onDecide('discard')}>放弃</Button>
    </div>;
    else action = <p className="text-xs text-muted">该镜头已变更，提案不可应用。</p>;
    return <section aria-label="改镜提案" className="space-y-3 rounded-panel border border-stroke/25 p-3">
      <h3 className="text-sm font-medium text-fg">第 {proposal.shotNumber} 镜 · 改动预览</h3>
      <dl className="space-y-3 text-sm [overflow-wrap:anywhere]">
        {proposal.changes?.map(change => (
          <div key={change.field} className="space-y-1">
            <dt className="text-xs text-muted">{fieldLabels[change.field ?? ''] ?? change.field}</dt>
            <dd className="whitespace-pre-wrap text-muted">修改前　{change.before}</dd>
            <dd className="whitespace-pre-wrap text-fg">修改后　{change.after}</dd>
          </div>
        ))}
      </dl>
      {proposal.generateImage && state === 'PENDING' && <p className="text-xs text-muted">按新描述重新生成整张分镜图，作为新的候选。</p>}
      {action}
      {message.imageTaskId && <ImageTaskProgress id={message.imageTaskId} />}
    </section>;
  }
  return <section aria-label="指令结果" className="space-y-2 text-sm leading-6 text-fg">
    <p role="status">{result.message}</p>
    {result.taskId && <p className="text-xs text-muted">任务 {result.taskId}</p>}
    {result.batchId && <p className="text-xs text-muted">批次 {result.batchId}</p>}
    {!!result.details?.length && <ul className="space-y-1 text-xs text-muted">
      {result.details.map((detail, index) => <li key={index}>{detail}</li>)}
    </ul>}
    {result.projectId && result.episodeId && (
      <Button type="button" variant="outline" size="sm" onClick={() => onOpen(result.projectId!, result.episodeId!)}>打开分集</Button>
    )}
  </section>;
}

function WorkflowReply({ workflow, busy, onConfirm, onOpen }: {
  workflow: Schema['Workflow']; busy: boolean; onConfirm: (id: string) => void;
  onOpen: (project: string, episode: string, tab: 'story' | 'storyboard') => void;
}) {
  const { state, id, projectId, episodeId } = workflow;
  const issues = workflow.batches?.flatMap(batch => (batch.members ?? []).filter(member =>
    ['failed', 'unknown', 'blocked', 'canceled'].includes(member.state ?? '')));
  return <section aria-label="拆镜与出图流程" className="space-y-2 rounded-panel border border-stroke/25 p-3 text-sm leading-6">
    <h3 className="font-medium">拆镜与出图</h3>
    <p role="status">{workflow.message}</p>
    {state === 'WAITING_CONFIRMATION' && <p className="text-xs text-muted">
      {workflow.shotCount} 镜，{workflow.unconfirmed} 镜待确认。确认后只为这批镜头中仍缺图的镜头出图。
    </p>}
    {workflow.batches?.map(batch => <p key={batch.id} className="text-xs text-muted">
      批次 {batch.id} · {batch.progress?.succeeded ?? 0}/{batch.progress?.total ?? 0} 已出图
    </p>)}
    {!!issues?.length && <ul className="space-y-1 text-xs text-muted">
      {issues.map(member => <li key={member.shotId}>第 {member.seq} 镜 · {member.reason ?? (
        member.state === 'unknown' ? '结果未知' : member.state === 'canceled' ? '已取消' : '未生成成功')}
      </li>)}
    </ul>}
    <div className="flex flex-wrap gap-2">
      {projectId && episodeId && <Button type="button" variant="outline" size="sm" onClick={() => onOpen(projectId, episodeId,
        state === 'WAITING_BREAKDOWN' || state === 'BLOCKED' ? 'story' : 'storyboard')}>查看分集</Button>}
      {id && state === 'WAITING_CONFIRMATION' && <Button type="button" size="sm" disabled={busy} onClick={() => onConfirm(id)}>
        确认镜头并出图
      </Button>}
    </div>
  </section>;
}

function ImageTaskProgress({ id }: { id: string }) {
  const task = useQuery({
    queryKey: ['agent-image-task', id],
    queryFn: ({ signal }) => api<Schema['Task']>(`/api/tasks/${id}`, 'GET', undefined, signal),
    refetchInterval: query => ['queued', 'running'].includes(query.state.data?.state ?? '') ? 1500 : false,
  });
  const state = task.data?.state;
  const label = task.data?.ready ? '新候选已生成，可在分镜页查看' :
    ['failed', 'unknown', 'canceled'].includes(state ?? '') ? '出图未完成，请在分镜页查看任务' : '已提交新候选出图任务';
  return <p role="status" className="text-xs text-muted">{label} · {id}</p>;
}
