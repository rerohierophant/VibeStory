import { beforeEach, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { BatchGeneration } from '@/features/BatchGeneration';
import type { Shot } from '@/lib/types';

const state = vi.hoisted(() => ({ submit: vi.fn(), api: vi.fn(), shots: [] as Shot[] }));
vi.mock('@/lib/store', () => ({ useStore: () => ({ mode: 'api', user: { id: '1' }, submitBatch: state.submit }), useEpisodeShots: () => state.shots }));
vi.mock('@/lib/api', () => ({ api: (...args: unknown[]) => state.api(...args) }));
const batch = { id: '7', state: 'complete', progress: { total: 2, accepted: 1, reused: 0, blocked: 1, succeeded: 1 }, members: [
  { shotId: '1', seq: 1, outcome: 'accepted', state: 'succeeded', taskId: '8' }, { shotId: '2', seq: 2, outcome: 'blocked', state: 'blocked', reason: '尚未确认' },
] };
beforeEach(() => {
  state.submit.mockReset(); state.api.mockReset().mockResolvedValue([]);
  state.shots = [{ id: '1', seq: 1, status: 'confirmed', description: '确认镜头', projectId: 'p', episodeId: 'e', sceneName: '街道', shotSize: '中景', cameraMove: '固定', dialogue: '', durationMs: 3000, characterNames: [], images: [], imageState: 'idle', video: { ready: false, state: 'idle', attempts: 0 } },
    { id: '2', seq: 2, status: 'draft', description: '待确认镜头', projectId: 'p', episodeId: 'e', sceneName: '街道', shotSize: '中景', cameraMove: '固定', dialogue: '', durationMs: 3000, characterNames: [], images: [], imageState: 'idle', video: { ready: false, state: 'idle', attempts: 0 } }] as Shot[];
});
function view() { return render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })}><BatchGeneration episodeId="e" type="IMAGE" /></QueryClientProvider>); }
it('shows prerequisites, permits choosing blocked members and presents the server acceptance result', async () => {
  state.submit.mockResolvedValue(batch); view();
  await userEvent.click(screen.getByRole('button', { name: '本集批量生成分镜图' }));
  expect(screen.getByRole('checkbox', { name: '选择镜头 1' })).toBeChecked();
  expect(screen.getByRole('checkbox', { name: '选择镜头 2' })).not.toBeChecked();
  await userEvent.click(screen.getByRole('button', { name: '选择本集镜头（最多 200）' }));
  await userEvent.click(screen.getByRole('button', { name: '提交 2 镜' }));
  expect(state.submit).toHaveBeenCalledWith('e', 'IMAGE', expect.any(String), ['1', '2'], false);
  expect(await screen.findByText('镜头 1 · 已接收 · 已生成')).toBeVisible();
  expect(screen.getByText('镜头 2 · 已阻塞 · 未提交')).toBeVisible();
});
it('reuses the request identity after an ambiguous network failure', async () => {
  state.submit.mockRejectedValueOnce(new Error('连接中断')).mockResolvedValueOnce(batch); view();
  await userEvent.click(screen.getByRole('button', { name: '本集批量生成分镜图' }));
  await userEvent.click(screen.getByRole('button', { name: '提交 1 镜' }));
  expect(await screen.findByRole('alert')).toHaveTextContent('连接中断');
  await userEvent.click(screen.getByRole('button', { name: '提交 1 镜' }));
  expect(state.submit.mock.calls[0][2]).toBe(state.submit.mock.calls[1][2]);
});
it('restores persisted progress and keeps dispatch pause separate from generation failure', async () => {
  state.api.mockResolvedValue([{ ...batch, state: 'paused', members: [{ shotId: '1', seq: 1, outcome: 'accepted', state: 'queued', taskId: '8', dispatchBlocked: 'DEAD_LETTER' }], progress: { total: 1, paused: 1 } }]); view();
  await userEvent.click(await screen.findByRole('button', { name: /最近批次 #7/ }));
  expect(screen.getByText(/派发已暂停 · DEAD_LETTER/)).toBeVisible();
  await userEvent.click(screen.getByRole('button', { name: '修复后恢复派发' }));
  expect(state.api).toHaveBeenCalledWith('/api/tasks/8/dispatch/resume', 'POST', {});
});

it('restores admission waiting without showing a generation failure or manual resume', async () => {
  state.api.mockResolvedValue([{ ...batch, state: 'running', members: [{ shotId: '1', seq: 1, outcome: 'accepted', state: 'queued', taskId: '8', waitReason: 'RATE_LIMITED' }], progress: { total: 1, queued: 1 } }]); view();
  await userEvent.click(await screen.findByRole('button', { name: /最近批次 #7/ }));
  expect(screen.getByText('等待生成名额 · 将自动继续')).toBeVisible();
  expect(screen.getByText('镜头 1 · 已接收 · 排队中')).toBeVisible();
  expect(screen.queryByRole('button', { name: '修复后恢复派发' })).not.toBeInTheDocument();
});
