import { useRef, useState } from 'react';
import { Badge } from '@/components/Badge';
import { Button } from '@/components/Button';
import { EpisodeCreativePanel } from './CreativePanels';
import { Modal } from '@/components/Modal';
import { useEpisodeShots, useProject, useStore } from '@/lib/store';

export function StoryTab({ episodeId }: { episodeId: string }) {
  const { projects, setEpisodeStory, runBreakdown, updateEpisode, retryTask, workspaceReadOnly, mode } = useStore();
  const shots = useEpisodeShots(episodeId);
  const episode = projects.flatMap((p) => p.episodes).find((e) => e.id === episodeId);
  const project = useProject(projects.find((p) => p.episodes.some((e) => e.id === episodeId))?.id ?? '');
  const fileRef = useRef<HTMLInputElement>(null);
  const [replaceNotice, setReplaceNotice] = useState(false);
  const [confirmingBreakdown, setConfirmingBreakdown] = useState(false);

  if (!episode) return null;

  const text = episode.story;
  const state = episode.breakdown === 'running' ? 'running' : shots.length > 0 ? 'done' : episode.breakdown;
  const wordCount = text.replace(/\s/g, '').length;

  const onFile = async (file?: File) => {
    if (!file) return;
    if (!/\.(txt|md)$/i.test(file.name)) return;
    const content = await file.text();
    if (shots.length > 0) setReplaceNotice(true);
    setEpisodeStory(episodeId, content);
  };

  return (
    <div className="flex min-h-0 flex-1 flex-col gap-6 overflow-y-auto scroll-thin p-8 xl:grid xl:grid-cols-[minmax(0,1fr)_320px] xl:grid-rows-[minmax(0,1fr)] xl:overflow-hidden">
      <section className="flex shrink-0 flex-col xl:min-h-0">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div>
            <h2 className="text-lg font-medium">第 {episode.index} 集 · 剧本</h2>
            <p className="mt-1 text-xs text-muted">
              只放这一集的正文。拆解只读取这一份文本，原文始终保留。
            </p>
          </div>
          <div className="flex items-center gap-2">
            <input
              ref={fileRef}
              type="file"
              accept=".txt,.md"
              className="hidden"
              onChange={(e) => onFile(e.target.files?.[0])}
            />
            <Button variant="outline" size="sm" disabled={workspaceReadOnly} onClick={() => fileRef.current?.click()}>
              上传文件
            </Button>
            <Button
              size="sm"
              onClick={() => {
                if (shots.length > 0) setConfirmingBreakdown(true);
                else runBreakdown(episodeId);
              }}
              disabled={workspaceReadOnly || state === 'running' || text.trim().length < 20}
            >
              {state === 'running' ? '拆解中…' : shots.length > 0 ? '重新拆解' : 'AI 拆解镜头'}
            </Button>
          </div>
        </div>

        <div className="mt-4 flex min-h-0 flex-1 flex-col rounded-panel border border-stroke/20 bg-panel/60">
          <textarea
            className="min-h-[420px] flex-1 resize-none bg-transparent p-6 text-[15px] leading-8 outline-none scroll-thin xl:min-h-0"
            placeholder={'把这一集的剧本贴在这里，或上传 .txt / .md 文件。\n\n示例：\n暴雨把整条街浇成一条发亮的河……'}
            readOnly={workspaceReadOnly}
            value={text}
            onChange={(e) => setEpisodeStory(episodeId, e.target.value)}
          />
          <div className="flex items-center justify-between border-t border-stroke/15 px-6 py-3 text-xs text-muted">
            <span>{wordCount} 字</span>
            <span>{shots.length > 0 ? `已拆出 ${shots.length} 个镜头` : '尚未拆解'}</span>
          </div>
        </div>

        {replaceNotice && (
          <p className="mt-3 text-xs text-warning">
            文本已替换。已拆解的镜头不会自动更新，需要重新拆解，旧镜头会被覆盖。
            <button className="ml-2 underline" onClick={() => setReplaceNotice(false)}>
              知道了
            </button>
          </p>
        )}
      </section>

      <aside className="flex shrink-0 flex-col gap-4 scroll-thin xl:min-h-0 xl:overflow-y-auto">
        {mode === 'api' && project && !project.teamId && <EpisodeCreativePanel key={episodeId} episodeId={episodeId} hasShots={shots.length > 0} />}
        <div className="rounded-panel border border-stroke/20 bg-panel/60 p-5">
          <p className="text-xs text-muted">这一集</p>
          <label className="mt-3 block text-[11px] text-muted">
            集名
            <input
              className="field mt-1.5 h-9 text-sm"
              value={episode.title}
              readOnly={workspaceReadOnly}
              onChange={(e) => updateEpisode(episodeId, { title: e.target.value })}
            />
          </label>
          <label className="mt-3 block text-[11px] text-muted">
            一句话梗概
            <textarea
              className="field mt-1.5 min-h-16 resize-y text-sm"
              placeholder="这一集发生了什么，一句话说清"
              value={episode.summary}
              readOnly={workspaceReadOnly}
              onChange={(e) => updateEpisode(episodeId, { summary: e.target.value })}
            />
          </label>
        </div>

        <div className="rounded-panel border border-stroke/20 bg-panel/60 p-5">
          <p className="text-xs text-muted">拆解状态</p>
          <div className="mt-3 flex items-center gap-2">
            {state === 'idle' && <Badge tone="muted">未开始</Badge>}
            {state === 'running' && (
              <Badge tone="accent" dot>
                生成中
              </Badge>
            )}
            {state === 'done' && <Badge tone="success">已完成</Badge>}
            {state === 'done' && <span className="text-xs text-muted">{shots.length} 镜</span>}
          </div>
          <p className="mt-3 text-xs leading-6 text-muted">
            拆解结果会写成这一集的可编辑镜头清单。未确认的镜头不能提交生成。
          </p>
          {episode.breakdownTask?.error && <div className="mt-3 space-y-2 text-xs text-error-soft">
            <p>{episode.breakdownTask.error}</p>
            {!workspaceReadOnly && (episode.breakdownTask.retryable || episode.breakdownTask.state === 'unknown') && <Button size="sm" variant="outline" onClick={() => {
              const task = episode.breakdownTask!;
              if (task.state === 'unknown' && !window.confirm('外部结果未知，重试可能重复计费。确认重试？')) return;
              retryTask?.(task.id, task.state === 'unknown');
            }}>重试拆解</Button>}
          </div>}
        </div>

        <div className="rounded-panel border border-stroke/20 bg-panel/60 p-5">
          <p className="text-xs text-muted">集进度</p>
          <ul className="mt-3 space-y-2 text-xs">
            <li className="flex items-center justify-between">
              <span className="text-muted">镜头</span>
              <span>{shots.length}</span>
            </li>
            <li className="flex items-center justify-between">
              <span className="text-muted">已确认</span>
              <span>{shots.filter((s) => s.status === 'confirmed').length}</span>
            </li>
            <li className="flex items-center justify-between">
              <span className="text-muted">有分镜图</span>
              <span>{shots.filter((s) => s.selectedImageId).length}</span>
            </li>
            <li className="flex items-center justify-between">
              <span className="text-muted">有视频</span>
              <span>{shots.filter((s) => s.video.ready).length}</span>
            </li>
          </ul>
          {project && (
            <p className="mt-3 border-t border-stroke/15 pt-3 text-[11px] leading-5 text-muted/70">
              属于《{project.name}》，共 {project.episodes.length} 集。
            </p>
          )}
        </div>
      </aside>

      <Modal
        open={confirmingBreakdown}
        onClose={() => setConfirmingBreakdown(false)}
        width="max-w-md"
        labelledBy="confirm-breakdown-title"
      >
        <div className="p-6">
          <h2 id="confirm-breakdown-title" className="text-lg font-medium">
            重新拆解这一集？
          </h2>
          <p className="mt-3 text-sm leading-6 text-muted">
            将覆盖第 {episode.index} 集现有的 {shots.length} 个镜头，包括手动修改、确认状态、分镜图和视频结果。新镜头需要重新确认。
          </p>
          <p className="mt-2 text-xs leading-5 text-muted">
            当前剧本、人物库和场景库会保留。此操作无法撤销。
          </p>
          <div className="mt-6 flex justify-end gap-3">
            <Button variant="ghost" autoFocus onClick={() => setConfirmingBreakdown(false)}>
              取消
            </Button>
            <Button
              variant="danger"
              disabled={workspaceReadOnly || state === 'running' || text.trim().length < 20}
              onClick={() => {
                setConfirmingBreakdown(false);
                setReplaceNotice(false);
                runBreakdown(episodeId);
              }}
            >
              确认重新拆解
            </Button>
          </div>
        </div>
      </Modal>
    </div>
  );
}
