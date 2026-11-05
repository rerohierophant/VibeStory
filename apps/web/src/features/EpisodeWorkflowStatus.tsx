import { useQuery } from '@tanstack/react-query';
import { api, type Schema } from '@/lib/api';
import { useStore } from '@/lib/store';

const roles = { STORY: '剧本', STORYBOARD: '分镜', VIDEO: '视频' };

export function EpisodeWorkflowStatus({ episodeId }: { episodeId: string }) {
  const { mode, user } = useStore();
  const plan = useQuery({
    queryKey: ['episode-workflow', user?.id, episodeId],
    enabled: mode === 'api' && !!user?.id,
    queryFn: ({ signal }) => api<Schema['EpisodePlan']>(`/api/episodes/${episodeId}/workflow`, 'GET', undefined, signal),
    refetchInterval: 3000,
  });

  if (mode !== 'api') return null;
  const next = plan.data?.next;
  return <section aria-label="本集工作流" className="flex flex-wrap items-baseline gap-x-2 text-xs">
    {next?.role && <span className="font-medium text-fg">{roles[next.role]}</span>}
    <span className="text-muted">
      {plan.isError ? '分集工作流读取失败' : next?.message ?? '正在读取分集工作流…'}
    </span>
    {plan.isError && <button type="button" className="text-muted underline" onClick={() => void plan.refetch()}>重试</button>}
  </section>;
}
