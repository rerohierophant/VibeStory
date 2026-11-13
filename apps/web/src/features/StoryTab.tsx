import { Badge } from '@/components/Badge';
import { Button } from '@/components/Button';
import { BreakdownError } from './BreakdownError';
import { useEpisodeShots, useStore } from '@/lib/store';

export function StoryTab({ episodeId }: { episodeId: string }) {
  const { projects, setEpisodeStory, runBreakdown, workspaceReadOnly } = useStore();
  const shots = useEpisodeShots(episodeId);
  const project = projects.find((p) => p.episodes.some((e) => e.id === episodeId));
  const episode = project?.episodes.find((e) => e.id === episodeId);
  if (!episode) return null;

  const text = episode.story;
  const running = episode.breakdown === 'running';
  const canBreakDown = text.trim().length >= 20;
  const wordCount = text.replace(/\s/g, '').length;

  return (
    <section className="flex min-h-0 flex-1 flex-col overflow-y-auto scroll-thin p-8">
      <div className="mb-4 flex shrink-0 flex-wrap items-center justify-between gap-3">
        <h2 className="text-lg font-medium">第 {episode.index} 集 · 剧本</h2>
        {shots.length === 0 && (
          <div className="flex flex-wrap items-center gap-2">
            {running ? <Badge tone="accent" dot>拆解中</Badge> : !workspaceReadOnly && (
              <>
                {!canBreakDown && <span className="text-xs text-muted">正文至少 20 字</span>}
                <Button size="sm" variant="outline" disabled={!canBreakDown}
                  onClick={() => runBreakdown(episodeId, false)}>
                  AI 拆解镜头
                </Button>
              </>
            )}
          </div>
        )}
      </div>
      {shots.length === 0 && <BreakdownError task={episode.breakdownTask} className="mb-3 shrink-0" />}

      <div className="flex flex-1 flex-col rounded-panel border border-stroke/20 bg-panel/60">
        {episode.summary?.trim() && (
          <div className="flex shrink-0 items-center gap-3 border-b border-stroke/15 px-6 py-3 text-xs text-muted">
            <span className="shrink-0 font-medium text-fg">概要剧情</span>
            <span className="min-w-0 truncate">{episode.summary}</span>
          </div>
        )}
        <textarea
          aria-label="剧本正文"
          className="min-h-64 flex-1 resize-none bg-transparent p-6 text-[15px] leading-8 outline-none scroll-thin"
          placeholder="把这一集的剧本贴在这里"
          readOnly={workspaceReadOnly}
          value={text}
          onChange={(e) => setEpisodeStory(episodeId, e.target.value)}
        />
        <div className="flex shrink-0 items-center justify-between gap-2 border-t border-stroke/15 px-6 py-3 text-xs text-muted">
          <span>{wordCount} 字</span>
          {shots.length > 0 && <span>已拆出 {shots.length} 个镜头</span>}
        </div>
      </div>
    </section>
  );
}
