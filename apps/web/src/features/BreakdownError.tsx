import { Button } from '@/components/Button';
import { useStore } from '@/lib/store';
import type { Episode } from '@/lib/types';

export function BreakdownError({ task, className }: { task: Episode['breakdownTask']; className: string }) {
  const { retryTask, workspaceReadOnly } = useStore();
  if (!task?.error) return null;

  const unknown = task.state === 'unknown';

  return (
    <div className={`flex flex-wrap items-center gap-2 text-xs text-error-soft ${className}`}>
      <p>{task.error}</p>
      {!workspaceReadOnly && (task.retryable || unknown) && (
        <Button size="sm" variant="outline" onClick={() => {
          if (unknown && !window.confirm('外部结果未知，重试可能重复计费。确认重试？')) return;
          retryTask?.(task.id, unknown);
        }}>
          重试拆解
        </Button>
      )}
    </div>
  );
}
