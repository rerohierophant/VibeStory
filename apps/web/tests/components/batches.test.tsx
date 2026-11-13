import { beforeEach, expect, it, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { BatchGeneration } from '@/features/BatchGeneration';
import type { Shot } from '@/lib/types';

const state = vi.hoisted(() => ({ submit: vi.fn(), api: vi.fn(), shots: [] as Shot[] }));
vi.mock('@/lib/store', () => ({ useStore: () => ({
  mode: 'api', user: { id: '1' }, submitBatch: state.submit, characters: [],
  scenes: [{ id: 'scene', projectId: 'p', name: '街道', selectedVersionId: 'v1',
    versions: [{ id: 'v1', seed: 'street', label: '基准图', source: 'generated', createdAt: 1 }] }],
}), useEpisodeShots: () => state.shots }));
vi.mock('@/lib/api', () => ({ api: (...args: unknown[]) => state.api(...args) }));
const batch = { id: '7', state: 'complete', progress: { total: 2, accepted: 1, reused: 0, blocked: 1, succeeded: 1 }, members: [
  { shotId: '1', seq: 1, outcome: 'accepted', state: 'succeeded', taskId: '8' }, { shotId: '2', seq: 2, outcome: 'blocked', state: 'blocked', reason: '结果未知，请先处理原任务' },
] };
beforeEach(() => {
  state.submit.mockReset(); state.api.mockReset().mockResolvedValue([]);
  state.shots = [{ id: '1', seq: 1, status: 'confirmed', description: '确认镜头', projectId: 'p', episodeId: 'e', sceneName: '街道', shotSize: '中景', cameraMove: '固定', dialogue: '', durationMs: 3000, characterNames: [], images: [], imageState: 'idle', video: { ready: false, state: 'idle', attempts: 0 } },
    { id: '2', seq: 2, status: 'draft', description: '待确认镜头', projectId: 'p', episodeId: 'e', sceneName: '街道', shotSize: '中景', cameraMove: '固定', dialogue: '', durationMs: 3000, characterNames: [], images: [], imageState: 'idle', video: { ready: false, state: 'idle', attempts: 0 } }] as Shot[];
});
function view() { return render(<MemoryRouter><QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })}><BatchGeneration episodeId="e" type="IMAGE" /></QueryClientProvider></MemoryRouter>); }

it('explains a fully blocked batch and excludes only missing references in a mixed batch', async () => {
  state.shots = state.shots.map(shot => ({ ...shot, sceneName: '夜市' }));
  const blocked = view();
  const button = screen.getByRole('button', { name: '本集批量生成分镜图' });
  expect(button).toHaveAttribute('aria-disabled', 'true');
  await userEvent.click(button);
  expect(screen.getByRole('status')).toHaveTextContent('请先为场景「夜市」生成或上传参考图。');
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  expect(state.submit).not.toHaveBeenCalled();
  blocked.unmount();

  state.shots[0].sceneName = '街道';
  state.submit.mockResolvedValue(batch);
  view();
  await userEvent.click(screen.getByRole('button', { name: '本集批量生成分镜图' }));
  expect(screen.getByRole('checkbox', { name: '选择镜头 2' })).toBeDisabled();
  await userEvent.click(screen.getByRole('button', { name: '选择本集镜头（最多 200）' }));
  await userEvent.click(screen.getByRole('button', { name: '提交 1 镜' }));
  expect(state.submit).toHaveBeenCalledExactlyOnceWith('e', 'IMAGE', expect.any(String), ['1'], false);
});
it('closes the selection dialog immediately and shows submission and acceptance on the page', async () => {
  let accept!: (result: typeof batch) => void;
  state.submit.mockReturnValue(new Promise<typeof batch>(resolve => { accept = resolve; }));
  view();
  await userEvent.click(screen.getByRole('button', { name: '本集批量生成分镜图' }));
  expect(screen.getByRole('checkbox', { name: '选择镜头 1' })).toBeChecked();
  expect(screen.getByRole('checkbox', { name: '选择镜头 2' })).toBeChecked();
  await userEvent.click(screen.getByRole('button', { name: '提交 2 镜' }));
  expect(state.submit).toHaveBeenCalledWith('e', 'IMAGE', expect.any(String), ['1', '2'], false);
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  expect(screen.getByRole('button', { name: '本集批量生成分镜图' })).toBeDisabled();
  const progress = within(screen.getByRole('region', { name: '分镜图批次进度' }));
  expect(progress.getByRole('status')).toHaveTextContent('正在提交 2 镜…');
  accept(batch);
  expect(await progress.findByText('镜头 1 · 已接收 · 已生成')).toBeVisible();
  expect(progress.getByText('镜头 2 · 已阻塞 · 未提交')).toBeVisible();
});
it('reuses the request identity after an ambiguous network failure', async () => {
  state.submit.mockRejectedValueOnce(new Error('连接中断')).mockResolvedValueOnce(batch); view();
  await userEvent.click(screen.getByRole('button', { name: '本集批量生成分镜图' }));
  await userEvent.click(screen.getByRole('checkbox', { name: '选择镜头 2' }));
  await userEvent.click(screen.getByRole('checkbox', { name: '重新生成已选镜头' }));
  await userEvent.click(screen.getByRole('button', { name: '提交 1 镜' }));
  expect(await screen.findByRole('alert')).toHaveTextContent('连接中断');
  await userEvent.click(screen.getByRole('button', { name: '本集批量生成分镜图' }));
  await userEvent.click(screen.getByRole('checkbox', { name: '重新生成已选镜头' }));
  await userEvent.click(screen.getByRole('button', { name: '关闭' }));
  await userEvent.click(screen.getByRole('button', { name: '重试提交' }));
  expect(state.submit.mock.calls).toEqual([
    ['e', 'IMAGE', expect.any(String), ['1'], true],
    state.submit.mock.calls[0],
  ]);
});
it('restores persisted progress and keeps dispatch pause separate from generation failure', async () => {
  state.api.mockResolvedValue([{ ...batch, state: 'paused', members: [{ shotId: '1', seq: 1, outcome: 'accepted', state: 'queued', taskId: '8', dispatchBlocked: 'DEAD_LETTER' }], progress: { total: 1, paused: 1 } }]); view();
  expect(await screen.findByText('批次 #7 · 派发暂停')).toBeVisible();
  await userEvent.click(screen.getByRole('button', { name: '查看明细' }));
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  expect(screen.getByText(/派发已暂停 · DEAD_LETTER/)).toBeVisible();
  await userEvent.click(screen.getByRole('button', { name: '修复后恢复派发' }));
  expect(state.api).toHaveBeenCalledWith('/api/tasks/8/dispatch/resume', 'POST', {});
});

it('restores admission waiting without showing a generation failure or manual resume', async () => {
  state.api.mockResolvedValue([{ ...batch, state: 'running', members: [{ shotId: '1', seq: 1, outcome: 'accepted', state: 'queued', taskId: '8', waitReason: 'RATE_LIMITED' }], progress: { total: 1, queued: 1 } }]); view();
  await userEvent.click(await screen.findByRole('button', { name: '查看明细' }));
  expect(screen.getByText('等待生成名额 · 将自动继续')).toBeVisible();
  expect(screen.getByText('镜头 1 · 已接收 · 排队中')).toBeVisible();
  expect(screen.queryByRole('button', { name: '修复后恢复派发' })).not.toBeInTheDocument();
});
