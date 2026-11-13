import { useEffect, useRef, useState, type FormEvent, type ReactNode } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, Outlet, useMatch, useNavigate } from 'react-router-dom';
import { Button } from '@/components/Button';
import { SidebarToggle } from '@/components/SidebarToggle';
import { ExecutionRecord } from './AgentExecution';
import { creativeRecipesKey } from './CreativeRecipes';
import { api, ApiError, type Schema } from '@/lib/api';
import { sidebarWidth } from '@/lib/sidebar';
import { useStore } from '@/lib/store';

const fieldLabels: Record<string, string> = { shotSize: '景别', dialogue: '台词', description: '画面描述' };
const paths = {
  sparkles: <>
    <path d="M10 3.5 12.2 9.8 18.5 12 12.2 14.2 10 20.5 7.8 14.2 1.5 12 7.8 9.8 10 3.5Z" />
    <path d="M19 2v6m-3-3h6m-2 10v5m-2.5-2.5h5" />
  </>,
  history: <><path d="M3 4v5h5M3.7 8.8a8.5 8.5 0 1 1 0 6.4M12 7v5l3 2" /></>,
  plus: <path d="M12 5v14M5 12h14" />,
  robot: <><rect x="5" y="8" width="14" height="11" rx="2" /><path d="M12 8V4H9M2 13h3m14 0h3M9 13v2m6-2v2" /></>,
  clip: <path d="M8.5 13.5l6.4-6.4a2 2 0 0 1 2.8 2.8l-8.5 8.5a4 4 0 0 1-5.7-5.7l8.5-8.5a5 5 0 0 1 7.1 7.1l-7.8 7.8" />,
  send: <path d="M12 19V5m-6 6 6-6 6 6" />,
  close: <path d="m6 6 12 12M18 6 6 18" />,
};

function Icon({ name }: { name: keyof typeof paths }) {
  return <svg aria-hidden="true" viewBox="0 0 24 24" className="size-5 shrink-0" fill="none"
    stroke="currentColor" strokeWidth="1.65" strokeLinecap="round" strokeLinejoin="round">{paths[name]}</svg>;
}

function IconButton({ icon, label, onClick, disabled, expanded, controls }: {
  icon: keyof typeof paths; label: string; onClick: () => void; disabled?: boolean; expanded?: boolean; controls?: string;
}) {
  return <button type="button" aria-label={label} title={label} onClick={onClick} disabled={disabled}
    aria-expanded={expanded} aria-controls={controls}
    className="grid size-9 shrink-0 place-items-center rounded-card text-muted transition hover:bg-hover/45 hover:text-fg aria-expanded:bg-hover/45 aria-expanded:text-fg disabled:opacity-40">
    <Icon name={icon} />
  </button>;
}

export function AgentWorkbench() {
  const [openPreference, setOpenPreference] = useState<boolean>();
  const [assetsAgentOpen, setAssetsAgentOpen] = useState(false);
  const { mode, projects, user, workspaceLoading } = useStore();
  const assetsRoute = useMatch('/assets');
  const projectRoute = useMatch('/projects/:id/*');
  const episodeRoute = useMatch('/projects/:id/episodes/:episodeId/:tab?');
  const project = projects.find(item => item.id === projectRoute?.params.id);
  const episode = project?.episodes.find(item => item.id === episodeRoute?.params.episodeId);
  const scope = project ? project.name + (episode ? ` · 第 ${episode.index} 集` : '') : undefined;
  const showAgent = !project?.teamId && !workspaceLoading;
  const open = assetsRoute ? assetsAgentOpen : (openPreference ?? !!project);
  const setOpen = assetsRoute ? setAssetsAgentOpen : setOpenPreference;

  return (
    <div className="relative flex h-full min-h-0 overflow-hidden">
      <div className="h-full min-w-0 flex-1"><Outlet /></div>
      <div className={`${showAgent ? 'flex' : 'hidden'} relative shrink-0 ${sidebarWidth.collapsed} xl:w-auto`}>
        <div className={`absolute inset-y-0 right-0 z-30 max-w-[calc(100dvw-1rem)] xl:relative xl:z-auto ${open ? 'w-[26rem]' : sidebarWidth.collapsed} transition-[width] duration-200 ease-out motion-reduce:transition-none`}>
          <div className={`relative h-full overflow-hidden border-l border-stroke/20 ${open ? 'bg-panel shadow-xl xl:shadow-none' : 'bg-panel/50'}`}>
            <aside id="agent-panel" aria-label="Agent 对话" aria-hidden={!open} inert={!open || !showAgent}
              className={`absolute inset-y-0 right-0 flex w-[26rem] max-w-[calc(100dvw-1rem)] flex-col transition-opacity duration-200 ease-out motion-reduce:transition-none ${open ? 'opacity-100' : 'opacity-0'}`}>
              {mode === 'demo' ? <>
                <header className="flex h-14 shrink-0 items-center border-b border-stroke/20 px-4">
                  <h2 className="font-medium text-fg">新会话</h2>
                </header>
                <div className="grid flex-1 place-items-center p-4 text-sm text-muted">连接后端后即可使用 Agent。</div>
              </> : user?.id && <AgentConversation key={conversationSelectionKey(user.id, project?.id, episode?.id)} userId={user.id}
                projectId={project?.id} episodeId={episode?.id} scope={scope} open={showAgent && open} />}
            </aside>
            {!open && <div className="relative flex justify-center pt-5">
              <button type="button" aria-label="打开 Agent 对话" title="打开 Agent 对话" aria-expanded={false} aria-controls="agent-panel"
                onClick={() => setOpen(true)}
                className="grid size-9 place-items-center rounded-card bg-accent/15 text-accent-soft transition hover:bg-accent/25">
                <Icon name="sparkles" />
              </button>
            </div>}
          </div>
          <SidebarToggle side="right" collapsed={!open} controls="agent-panel" onToggle={() => setOpen(!open)} />
        </div>
      </div>
    </div>
  );
}

function conversationSelectionKey(userId: string, projectId?: string, episodeId?: string) {
  const prefix = `vibestory.agent.conversation.${userId}`;
  return episodeId ? `${prefix}.${episodeId}` : projectId ? `${prefix}.project.${projectId}` : prefix;
}

function AgentConversation({ userId, projectId, episodeId, scope, open }: {
  userId: string; projectId?: string; episodeId?: string; scope?: string; open: boolean;
}) {
  const { projects, sendAgentMessage, decideShotEdit, confirmAgentWorkflow } = useStore();
  const cache = useQueryClient();
  const navigate = useNavigate();
  const selectionKey = conversationSelectionKey(userId, projectId, episodeId);
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
  const [historyOpen, setHistoryOpen] = useState(false);
  const [draft, setDraft] = useState('');
  const [skillsDismissed, setSkillsDismissed] = useState(false);
  const [skillIndex, setSkillIndex] = useState(0);
  const [files, setFiles] = useState<File[]>([]);
  const [busy, setBusy] = useState(false);
  const [streamingReply, setStreamingReply] = useState<{ executionId: string; text: string }>();
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
    queryFn: ({ signal }) => api<Schema['ConversationHistoryItem'][]>('/api/agent/conversations', 'GET', undefined, signal),
  });
  const conversations = history.data?.filter(item => !!item.id && (!projectId || item.projectId === projectId)) ?? [];
  const transcript = useQuery({
    queryKey: transcriptKey, enabled: open && selection.saved,
    queryFn: ({ signal }) => api<Schema['Transcript']>(path, 'GET', undefined, signal),
    refetchInterval: query => open && query.state.data?.messages?.some(message =>
      ['WAITING_BREAKDOWN', 'WAITING_CONFIRMATION', 'GENERATING', 'PAUSED'].includes(message.workflow?.state ?? '')
      || ['QUEUED', 'RUNNING', 'WAITING'].includes(message.execution?.state ?? '')) ? 1500 : false,
  });
  const messages = transcript.data?.messages ?? [];
  const skillSearch = draft.trimStart().match(/^[/／]([^\n\r]*)$/u)?.[1];
  const skillsOpen = skillSearch !== undefined && !skillsDismissed && open && !busy;
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
    if (open && scroll.current) scroll.current.scrollTop = 0;
  }, [messages.length, open, selection.id, busy]);

  useEffect(() => {
    if (!useMain || !main.data?.mainSessionId) return;
    const id = main.data.mainSessionId;
    sessionStorage.setItem(selectionKey, id);
    setSelection({ id, saved: true });
    setUseMain(false);
    void cache.invalidateQueries({ queryKey: ['agent-conversations', userId] });
  }, [cache, main.data?.mainSessionId, selectionKey, useMain, userId]);

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

  function selectHistory(item: Schema['ConversationHistoryItem']) {
    if (busy || !item.id) return;
    setHistoryOpen(false);
    if (item.id === selection.id) return;
    if (item.projectId && (item.projectId !== projectId || item.episodeId !== episodeId)) {
      sessionStorage.setItem(conversationSelectionKey(userId, item.projectId, item.episodeId), item.id);
      const path = `/projects/${item.projectId}`;
      navigate(item.episodeId ? `${path}/episodes/${item.episodeId}/story` : path);
    } else {
      select(item.id);
    }
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
      const result = await sendAgentMessage(selection.id, requestKey, body, event => {
        if (event.type === 'start' && event.executionId && event.transcript) {
          void cache.cancelQueries({ queryKey: transcriptKey });
          cache.setQueryData(transcriptKey, event.transcript);
          sessionStorage.setItem(selectionKey, selection.id);
          setSelection({ id: selection.id, saved: true });
          setStreamingReply({ executionId: event.executionId, text: '' });
          setDraft('');
          void cache.invalidateQueries({ queryKey: historyKey });
        } else if (event.type === 'delta' || event.type === 'reset') {
          setStreamingReply(reply => reply && ({
            ...reply, text: event.type === 'reset' ? '' : reply.text + (event.text ?? ''),
          }));
        }
      });
      await cache.cancelQueries({ queryKey: transcriptKey });
      cache.setQueryData(transcriptKey, result);
      void cache.invalidateQueries({ queryKey: historyKey });
      void cache.invalidateQueries({ queryKey: creativeRecipesKey(userId) });
      void cache.invalidateQueries({ queryKey: ['image-experience', userId] });
      sessionStorage.setItem(selectionKey, selection.id);
      setSelection({ id: selection.id, saved: true });
      retry.current = undefined;
      setDraft('');
      setFiles([]);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '消息发送失败');
      void cache.invalidateQueries({ queryKey: transcriptKey });
    } finally {
      setStreamingReply(undefined);
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
      <header className="flex h-14 shrink-0 items-center gap-2 border-b border-stroke/20 pl-4 pr-3">
        <div className="min-w-0 flex-1">
          <h2 className="truncate text-sm font-semibold text-fg">{transcript.data?.conversation?.title ?? '新会话'}</h2>
          {scope && <p className="truncate text-[11px] text-muted/75" title={scope}>{scope}</p>}
        </div>
        <IconButton icon="history" label="历史会话" expanded={historyOpen} controls="agent-history"
          onClick={() => setHistoryOpen(value => !value)} />
        <IconButton icon="plus" label="新建会话" onClick={() => select()} disabled={busy} />
      </header>
      <section id="agent-history" aria-label="历史会话" hidden={!historyOpen}
        className="max-h-[min(11rem,22dvh)] shrink-0 overflow-y-auto border-b border-stroke/20 bg-panel-2/30 px-3 py-2 scroll-thin">
        {history.isLoading && <p className="px-2 py-1 text-sm text-muted">正在读取历史会话…</p>}
        {history.isError && <p role="alert" className="px-2 py-1 text-sm text-error-soft">{history.error.message}</p>}
        {history.isSuccess && !conversations.length && <p className="px-2 py-1 text-sm text-muted">还没有历史会话</p>}
        <ul className="space-y-1">
          {conversations.map(item => {
            const projectName = !projectId && projects.find(project => project.id === item.projectId)?.name;
            const context = item.episodeIndex != null ? `第 ${item.episodeIndex} 集` : item.projectId ? '项目会话' : undefined;
            const label = [projectName, context, item.title].filter(Boolean).join(' · ');
            return <li key={item.id}>
              <button type="button" onClick={() => selectHistory(item)} disabled={busy}
                aria-current={item.id === selection.id ? 'true' : undefined}
                className="flex w-full items-center justify-between gap-2 rounded-card px-3 py-2 text-left hover:bg-hover/40 aria-[current=true]:bg-hover/40 disabled:opacity-50">
                <span className="truncate text-sm text-fg" title={label}>{label}</span>
                {item.updatedAt && <time dateTime={item.updatedAt} className="shrink-0 text-xs text-muted">
                  {new Intl.DateTimeFormat('zh-CN', { month: 'numeric', day: 'numeric' }).format(new Date(item.updatedAt))}
                </time>}
              </button>
            </li>;
          })}
        </ul>
      </section>
      {open && main.isError && useMain && <p className="border-b border-stroke/20 px-4 py-2 text-xs text-error-soft"
        role="alert">{main.error.message}</p>}
        <>
          {/* Anchor the viewport at the latest reply even when the history list changes height. */}
          <div ref={scroll} className="flex min-h-0 flex-1 flex-col-reverse overflow-y-auto scroll-thin [overflow-anchor:none]">
            {transcript.isLoading ? <p className="min-h-full p-4 text-sm text-muted">正在读取会话…</p> : transcript.isError
              ? <p role="alert" className="min-h-full p-4 text-sm text-error-soft">{transcript.error.message}</p>
              : messages.length === 0 ? (
                <div className="flex min-h-full flex-col items-center justify-center gap-3 p-6 text-center">
                  <div className="grid size-11 place-items-center rounded-2xl bg-panel-2/60 text-fg"><Icon name="robot" /></div>
                  <p className="text-base font-medium text-fg">在下方输入消息开始对话</p>
                  <p className="text-sm text-muted/80">输入工作台指令，或添加图片</p>
                </div>
              ) : (
                <div className="min-h-full shrink-0 space-y-6 px-4 py-5">
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
                          {streamingReply && streamingReply.executionId === message.execution?.id && streamingReply.text ? (
                            <p data-testid="agent-streaming-reply" className="whitespace-pre-wrap break-words text-sm leading-6 text-fg">
                              {streamingReply.text}
                            </p>
                          ) : <MessageReply message={message} userId={userId} open={open} busy={busy} staleProposal={staleProposal}
                            matchingScope={(message.projectId ?? undefined) === projectId && (message.episodeId ?? undefined) === episodeId}
                            onSuggestion={selectSkill} onConfirmWorkflow={id => void confirmWorkflow(id)}
                            onDecide={decision => void decide(message, decision)} onOpen={(project, episode, tab = 'story') =>
                              navigate(`/projects/${project}/episodes/${episode}/${tab}`)} />}
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

function displayResultText(text?: string) {
  // Render older replies without directing users to removed controls.
  return text?.replaceAll('请检查已选首帧，然后提交视频生成', '本集还有镜头未生成视频')
    .replaceAll('，可在 Agent 对话的「创作草案」审阅并应用。', '。')
    .replaceAll('可在 Agent 对话的「创作草案」审阅，或说', '可以说');
}

function MessageReply({ message, userId, open, busy, staleProposal, matchingScope, onSuggestion, onConfirmWorkflow, onDecide, onOpen }: {
  message: Schema['ChatMessage']; userId: string; open: boolean; busy: boolean; staleProposal?: string;
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
  if (!result) return ['QUEUED', 'RUNNING', 'WAITING'].includes(message.execution?.state ?? '')
    ? <p className="text-sm text-muted" role="status">{message.execution?.message}</p> : null;
  if (result.projectId && result.projectPlanId) {
    return <ProjectPlanReply userId={userId} projectId={result.projectId} planId={result.projectPlanId}
      message={result.message} open={open} />;
  }
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
    <p role="status" className="whitespace-pre-wrap break-words">{displayResultText(result.message)}</p>
    {result.taskId && <p className="text-xs text-muted">任务 {result.taskId}</p>}
    {result.batchId && <p className="text-xs text-muted">批次 {result.batchId}</p>}
    {!!result.details?.length && <ul className="space-y-1 text-xs text-muted">
      {result.details.map((detail, index) => <li key={index}>{displayResultText(detail)}</li>)}
    </ul>}
    {result.projectId && result.episodeId && (
      <Button type="button" variant="outline" size="sm" onClick={() => onOpen(result.projectId!, result.episodeId!)}>打开分集</Button>
    )}
  </section>;
}

function ProjectPlanReply({ userId, projectId, planId, message, open }: {
  userId: string; projectId: string; planId: string; message?: string; open: boolean;
}) {
  const plan = useQuery({
    queryKey: ['creative-project', userId, projectId],
    enabled: open,
    queryFn: ({ signal }) => api<Schema['CreativeProject']>(`/api/projects/${projectId}/creative`, 'GET', undefined, signal),
    refetchInterval: query => {
      const plan = query.state.data?.plan;
      return open && plan?.id === planId && (plan.state === 'QUEUED' || plan.state === 'RUNNING') ? 1500 : false;
    },
  });
  const current = plan.data?.plan;
  const isCurrent = current?.id === planId && current.current;
  const ready = isCurrent && current.state === 'READY';
  const status = plan.isError ? '暂时无法读取规划进度。'
    : !plan.data ? '正在读取规划进度…'
    : !isCurrent ? '项目的创作计划已更新。'
    : current.state === 'READY' ? '分集草案已就绪，待审阅。'
    : current.state === 'APPLIED' ? '分集草案已确认。'
    : current.state === 'FAILED' ? '分集规划失败。'
    : current.state === 'UNKNOWN' ? '分集规划结果未知。'
    : current.state === 'RUNNING' ? '正在规划分集剧本、角色和场景…'
    : '已提交分集规划，正在排队。';
  return <section aria-label="短剧创作进度" className="space-y-2 rounded-panel border border-stroke/25 p-3 text-sm leading-6">
    <p className="wrap-anywhere">{message}</p>
    <p role="status">{status}</p>
    {isCurrent && current.error && <p className="text-xs text-error-soft">{current.error}</p>}
    {ready && current.summary && <p className="text-xs text-muted">{current.summary}</p>}
    {!(plan.error instanceof ApiError && plan.error.status === 404) && (
      <Link to={`/projects/${projectId}`}
        className="inline-flex h-8 items-center rounded-card border border-stroke/40 px-3 text-xs font-medium text-fg transition hover:bg-hover/50">
        {ready ? '前往项目概览审阅' : '打开项目概览'}
      </Link>
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
        生成缺失分镜图
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
