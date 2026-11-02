import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { VideoTab } from '@/features/VideoTab';
import { StoryboardTab } from '@/features/StoryboardTab';
import { TaskBadge } from '@/components/Badge';
import type { Shot, TaskState } from '@/lib/types';

const state = vi.hoisted(() => ({ shots: [] as Shot[], generateVideo: vi.fn(), generateImage: vi.fn(), confirmAll: vi.fn() }));
vi.mock('@/lib/store', () => ({
  useEpisodeShots: () => state.shots,
  useProject: () => ({ episodes: [{ id: 'e', index: 1, title: '第一集' }] }),
  useStore: () => ({ generateVideo: state.generateVideo, generateImage: state.generateImage, confirmAll: state.confirmAll }),
}));

function shot(id: string, patch: Partial<Shot> = {}): Shot {
  return { id, projectId: 'p', episodeId: 'e', seq: Number(id), sceneName: '街道', shotSize: '中景',
    cameraMove: '固定', description: `镜头${id}`, dialogue: '', durationMs: 6000, status: 'confirmed',
    characterNames: [], images: [{ id: 'first', seed: 'mock-first', label: '原首帧', createdAt: 1 }],
    selectedImageId: 'first', imageState: 'idle', video: { ready: false, state: 'idle', attempts: 0 }, ...patch };
}
function video() { return render(<MemoryRouter><VideoTab projectId="p" episodeId="e" /></MemoryRouter>); }
function storyboard() { return render(<MemoryRouter><StoryboardTab projectId="p" episodeId="e" /></MemoryRouter>); }

beforeEach(() => { state.shots = []; });

describe('video workspace', () => {
  it('counts confirmed shots once, keeps old ready output in the failure filter and sums actual media duration', async () => {
    state.shots = [
      shot('1', { video: { ready: true, state: 'failed', attempts: 2, durationMs: 3000, basedOn: 'first', error: '新尝试失败' } }),
      shot('2'), shot('3', { video: { ready: false, state: 'unknown', attempts: 1 } }),
      shot('4', { status: 'draft' }),
    ];
    video();
    expect(screen.getByRole('button', { name: '全部 3' })).toBeVisible();
    expect(screen.getByRole('button', { name: '已生成 1' })).toBeVisible();
    expect(screen.getByRole('button', { name: '未生成 2' })).toBeVisible();
    expect(screen.getByText(/总时长 3.0s/)).toBeVisible();
    await userEvent.click(screen.getByRole('button', { name: '失败 1' }));
    expect(screen.getAllByRole('article')).toHaveLength(1);
    expect(screen.getByText('新尝试失败')).toBeVisible();
    await userEvent.click(screen.getByRole('button', { name: '未生成 2' }));
    expect(screen.getAllByRole('article')).toHaveLength(2);
    expect(screen.getByText('结果未知', { exact: true })).toBeVisible();
    expect(screen.queryByText('新尝试失败')).not.toBeInTheDocument();
  });

  it('blocks missing first frames, disables in-flight jobs and keeps query and unknown retry labels distinct', async () => {
    state.shots = [shot('1', { images: [], selectedImageId: undefined }),
      shot('2', { video: { ready: false, state: 'running', attempts: 1 } }),
      shot('3', { video: { ready: false, state: 'unknown', attempts: 1, resumable: true } }),
      shot('4', { video: { ready: false, state: 'unknown', attempts: 1, resumable: false } })];
    video();
    expect(screen.getByRole('button', { name: '先去分镜页生成首帧' })).toBeDisabled();
    expect(screen.getByRole('button', { name: '生成中' })).toBeDisabled();
    await userEvent.click(screen.getByRole('button', { name: '继续查询' }));
    expect(state.generateVideo).toHaveBeenCalledExactlyOnceWith('3');
    expect(screen.getByRole('button', { name: '确认后重试' })).toBeEnabled();
  });

  it('renders the submitted first frame in playback after the current image selection changes', async () => {
    state.shots = [shot('1', {
      images: [{ id: 'first', seed: 'first', label: '原首帧', createdAt: 1 }, { id: 'new', seed: 'new', label: '新图', createdAt: 2 }],
      selectedImageId: 'new', video: { ready: true, state: 'idle', attempts: 1, basedOn: 'first', durationMs: 3000 },
    })];
    video();
    await userEvent.click(within(screen.getByRole('article')).getAllByRole('button')[0]);
    expect(screen.getByText(/原首帧 ·/)).toBeVisible();
    expect(screen.queryByText(/新图 ·/)).not.toBeInTheDocument();
    expect(screen.getByText(/3s · 尝试/)).toBeVisible();
  });

  it('shows an actionable story empty state before breakdown', () => {
    video();
    expect(screen.getByText('还没有可以生成视频的镜头')).toBeVisible();
    expect(screen.getByRole('button', { name: '去拆解这一集' })).toBeEnabled();
  });

  it('explains the prerequisite when all shots are drafts', () => {
    state.shots = [shot('1', { status: 'draft' })]; video();
    expect(screen.getByText('还没有确认的镜头。先到分镜页确认镜头。')).toBeVisible();
    expect(screen.queryByRole('article')).not.toBeInTheDocument();
  });
});

describe('storyboard workspace', () => {
  it('counts no-image shots independently from confirmation and blocks generation until confirmed', async () => {
    state.shots = [shot('1', { status: 'draft', images: [], selectedImageId: undefined }), shot('2'),
      shot('3', { images: [], selectedImageId: undefined })];
    storyboard();
    expect(screen.getByText(/3 镜 · 2 待出图/)).toBeVisible();
    await userEvent.click(screen.getByRole('button', { name: '待确认' }));
    expect(screen.getAllByRole('row')).toHaveLength(2);
    expect(screen.getByText('确认镜头后即可生成')).toBeVisible();
    expect(screen.getByRole('button', { name: '生成分镜图' })).toBeDisabled();
    await userEvent.click(screen.getByRole('button', { name: '待出图' }));
    expect(screen.getAllByRole('row')).toHaveLength(3);
    expect(screen.getAllByRole('button', { name: '生成分镜图' }).filter(button => !(button as HTMLButtonElement).disabled)).toHaveLength(1);
  });

  it('shows a filter empty state and disables redundant whole-episode confirmation', async () => {
    state.shots = [shot('1')]; storyboard();
    expect(screen.getByRole('button', { name: '全部确认' })).toBeDisabled();
    await userEvent.click(screen.getByRole('button', { name: '待出图' }));
    expect(screen.getByText('没有符合条件的镜头')).toBeVisible();
  });

  it('shows the no-breakdown state with a navigation action', () => {
    storyboard(); expect(screen.getByText('这一集还没有分镜')).toBeVisible();
    expect(screen.getByRole('button', { name: '去拆解这一集' })).toBeEnabled();
  });
});

it.each<[TaskState, string]>([['idle', '空闲'], ['queued', '排队中'], ['running', '生成中'], ['failed', '失败'], ['unknown', '结果未知']])(
  'renders %s with its own truthful task badge', (state, label) => { render(<TaskBadge state={state} />); expect(screen.getByText(label)).toBeVisible(); },
);
