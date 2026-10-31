import { useRef, useState } from 'react';
import { Badge } from '@/components/Badge';
import { Button } from '@/components/Button';
import { useEpisodeShots, useProject, useStore } from '@/lib/store';

export function StoryTab({ episodeId }: { episodeId: string }) {
  const { projects, setEpisodeStory, runBreakdown, updateEpisode } = useStore();
  const shots = useEpisodeShots(episodeId);
  const episode = projects.flatMap((p) => p.episodes).find((e) => e.id === episodeId);
  const project = useProject(projects.find((p) => p.episodes.some((e) => e.id === episodeId))?.id ?? '');
  const fileRef = useRef<HTMLInputElement>(null);
  const [replaceNotice, setReplaceNotice] = useState(false);

  if (!episode) return null;

  const text = episode.story;
  const state = shots.length > 0 ? 'done' : episode.breakdown;
  const wordCount = text.replace(/\s/g, '').length;

  const onFile = async (file?: File) => {
    if (!file) return;
    if (!/\.(txt|md)$/i.test(file.name)) return;
    const content = await file.text();
    if (shots.length > 0) setReplaceNotice(true);
    setEpisodeStory(episodeId, content);
  };

  return (
    <div className="grid min-h-0 flex-1 grid-cols-1 gap-6 overflow-hidden p-8 xl:grid-cols-[minmax(0,1fr)_320px]">
      <section className="flex min-h-0 flex-col">
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
            <Button variant="outline" size="sm" onClick={() => fileRef.current?.click()}>
              上传文件
            </Button>
            <Button
              size="sm"
              onClick={() => runBreakdown(episodeId)}
              disabled={state === 'running' || text.trim().length < 20}
            >
              {state === 'running' ? '拆解中…' : shots.length > 0 ? '重新拆解' : 'AI 拆解镜头'}
            </Button>
          </div>
        </div>

        <div className="mt-4 flex min-h-0 flex-1 flex-col rounded-panel border border-stroke/20 bg-panel/60">
          <textarea
            className="min-h-[420px] flex-1 resize-none bg-transparent p-6 text-[15px] leading-8 outline-none scroll-thin"
            placeholder={'把这一集的剧本贴在这里，或上传 .txt / .md 文件。\n\n示例：\n暴雨把整条街浇成一条发亮的河……'}
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

      <aside className="flex min-h-0 flex-col gap-4 overflow-y-auto scroll-thin">
        <div className="rounded-panel border border-stroke/20 bg-panel/60 p-5">
          <p className="text-xs text-muted">这一集</p>
          <label className="mt-3 block text-[11px] text-muted">
            集名
            <input
              className="field mt-1.5 h-9 text-sm"
              value={episode.title}
              onChange={(e) => updateEpisode(episodeId, { title: e.target.value })}
            />
          </label>
          <label className="mt-3 block text-[11px] text-muted">
            一句话梗概
            <textarea
              className="field mt-1.5 min-h-16 resize-y text-sm"
              placeholder="这一集发生了什么，一句话说清"
              value={episode.summary}
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
    </div>
  );
}
