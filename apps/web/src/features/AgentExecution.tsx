import type { Schema } from '@/lib/api';

const labels: Record<string, string> = {
  QUEUED: '等待执行', RUNNING: '执行中', WAITING: '等待专业任务', DONE: '已完成', FAILED: '失败', UNKNOWN: '结果未知',
};
const toolLabels: Record<string, string> = {
  load_skill: '加载创作规范', get_status: '读取项目进度', execute_action: '执行工作台操作',
  delegate_child: '派发专业任务', await_child: '接收专业任务结果', read_input: '读取创作输入',
  read_previous: '读取上一版', submit_draft: '校验并保存草案',
  read_tool_result: '读取已保存结果', read_history: '回查完整记录',
  get_image_context: '读取图片与创作经验', generate_image: '按要求生成图片', evaluate_image: '记录结果评价',
  remember_image_preference: '保存创作偏好', save_image_recipe: '沉淀创作配方',
};

export function ExecutionRecord({ execution }: { execution?: Schema['Execution'] }) {
  if (!execution) return null;
  return <details className="mt-2 text-xs text-muted" aria-label="创作执行记录">
    <summary className="cursor-pointer">执行记录 · {labels[execution.state ?? ''] ?? execution.state} · {execution.toolCount ?? 0} 步</summary>
    <ol className="mt-2 space-y-1 border-l border-stroke/25 pl-3">
      {execution.trace?.map(step => <li key={step.id}>
        {toolLabels[step.tool ?? ''] ?? '处理创作任务'} · {step.ok ? '完成' : '需修正'}{step.message ? ` · ${step.message}` : ''}
      </li>)}
    </ol>
    {!!execution.context?.compactions && <p className="mt-2">已整理上下文 {execution.context.compactions} 次，完整记录保留</p>}
    {execution.error && <p className="mt-1 text-error-soft">{execution.error}</p>}
  </details>;
}
