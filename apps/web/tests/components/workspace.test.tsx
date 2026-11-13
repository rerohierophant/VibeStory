import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { VideoTab } from '@/features/VideoTab';
import { StoryboardTab } from '@/features/StoryboardTab';
import { TaskBadge } from '@/components/Badge';
import { EpisodesPage } from '@/pages/EpisodesPage';
import { ProjectOverviewPage } from '@/pages/ProjectOverviewPage';
import type { Character, Episode, Project, SceneAsset, Shot, TaskState } from '@/lib/types';

const state = vi.hoisted(() => ({
  project: {
    id: 'p', name: '长夜将明', visualType: 'realistic', style: '', createdAt: 1, updatedAt: 1, episodes: [] as Episode[],
  } satisfies Project,
  shots: [] as Shot[], generateVideo: vi.fn(), generateImage: vi.fn(), confirmAll: vi.fn(),
  characters: [] as Character[], scenes: [] as SceneAsset[],
}));
vi.mock('@/lib/store', () => ({
  useEpisodeShots: (episodeId: string) => state.shots.filter((shot) => shot.episodeId === episodeId),
  useProject: () => state.project,
  useProjectShots: () => state.shots,
  useStore: () => ({ generateVideo: state.generateVideo, generateImage: state.generateImage, confirmAll: state.confirmAll,
    characters: state.characters, scenes: state.scenes }),
}));

function shot(id: string, patch: Partial<Shot> = {}): Shot {
  return { id, projectId: 'p', episodeId: 'e', seq: Number(id), sceneName: '街道', shotSize: '中景',
    cameraMove: '固定', description: `镜头${id}`, dialogue: '', durationMs: 6000, status: 'confirmed',
    characterNames: [], images: [{ id: 'first', seed: 'mock-first', label: '原首帧', createdAt: 1 }],
    selectedImageId: 'first', imageState: 'idle', video: { ready: false, state: 'idle', attempts: 0 }, ...patch };
}
function video() { return render(<MemoryRouter><VideoTab projectId="p" episodeId="e" /></MemoryRouter>); }
function storyboard() { return render(<MemoryRouter><StoryboardTab projectId="p" episodeId="e" /></MemoryRouter>); }

beforeEach(() => {
  state.shots = [];
  state.generateImage.mockClear();
  state.characters = [];
  state.scenes = [{ id: 'street', projectId: 'p', name: '街道', description: '', selectedVersionId: 'v1',
    versions: [{ id: 'v1', source: 'uploaded', seed: 'street', label: '街道', createdAt: 1 }] }];
  const defaults = { summary: '', story: '', breakdown: 'idle', createdAt: 1, updatedAt: 1 } as const;
  state.project.episodes = [
    { ...defaults, id: 'e', index: 1, title: '第一集', story: '沈夜走进雨夜街道。便利店的门打开。积水反射出远处的路灯。' },
    { ...defaults, id: 'e2', index: 2, title: '第二集', story: '许星遥在便利店等人。' },
    { ...defaults, id: 'e3', index: 3, title: '第三集' },
  ];
});

describe('episode breakdown progress', () => {
  it('uses persisted shots for overview progress even when the breakdown is idle', () => {
    state.shots = [shot('1'), shot('2'), shot('3')];
    render(<MemoryRouter initialEntries={['/projects/p']}><Routes>
      <Route path="/projects/:id" element={<ProjectOverviewPage />} />
    </Routes></MemoryRouter>);

    const episodes = within(screen.getByRole('region', { name: /^分集$/ }));
    expect(episodes.getByRole('link', { name: /第一集/ })).toHaveTextContent('3/3 已确认');
    expect(episodes.getByRole('link', { name: /第二集/ })).toHaveTextContent('待拆解');
    expect(episodes.getByRole('link', { name: /第三集/ })).toHaveTextContent('空白');
  });

  it('shows a running breakdown even when the previous shots are still present', () => {
    state.project.episodes[0].breakdown = 'running';
    state.shots = [shot('1')];
    render(<MemoryRouter initialEntries={['/projects/p/episodes']}><Routes>
      <Route path="/projects/:id/episodes" element={<EpisodesPage />} />
    </Routes></MemoryRouter>);

    const episode = within(screen.getByRole('heading', { name: '第一集' }).closest('article')!);
    expect(episode.getByText('拆解中', { exact: true })).toBeVisible();
    expect(episode.queryByText('已拆解', { exact: true })).not.toBeInTheDocument();
  });
});

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
    const shots = within(screen.getByRole('region', { name: '本集镜头' }));
    expect(shots.getAllByRole('listitem')).toHaveLength(1);
    expect(screen.getByText('新尝试失败')).toBeVisible();
    await userEvent.click(screen.getByRole('button', { name: '未生成 2' }));
    expect(shots.getAllByRole('listitem')).toHaveLength(2);
    expect(shots.getByText('结果未知', { exact: true })).toBeVisible();
    expect(screen.getByRole('article', { name: '第 02 镜' })).toBeVisible();
    expect(screen.queryByText('新尝试失败')).not.toBeInTheDocument();
  });

  it('blocks missing first frames, disables in-flight jobs and keeps query and unknown retry labels distinct', async () => {
    state.shots = [shot('1', { images: [], selectedImageId: undefined }),
      shot('2', { video: { ready: false, state: 'running', attempts: 1 } }),
      shot('3', { video: { ready: false, state: 'unknown', attempts: 1, resumable: true } }),
      shot('4', { video: { ready: false, state: 'unknown', attempts: 1, resumable: false } })];
    video();
    expect(screen.getByRole('button', { name: '先去分镜页生成首帧' })).toBeDisabled();
    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: /^第 02 镜/ }));
    expect(within(screen.getByRole('article')).getByRole('button', { name: '生成中' })).toBeDisabled();
    await user.click(screen.getByRole('button', { name: /^第 03 镜/ }));
    await user.click(screen.getByRole('button', { name: '继续查询' }));
    expect(state.generateVideo).toHaveBeenCalledExactlyOnceWith('3');
    await user.click(screen.getByRole('button', { name: /^第 04 镜/ }));
    expect(screen.getByRole('button', { name: '确认后重试' })).toBeEnabled();
  });

  it('renders the submitted first frame in playback after the current image selection changes', async () => {
    state.shots = [shot('1', {
      images: [{ id: 'first', seed: '/api/shot-images/1/image', label: '原首帧', createdAt: 1 }, { id: 'new', seed: 'new', label: '新图', createdAt: 2 }],
      selectedImageId: 'new', video: { ready: true, state: 'idle', attempts: 1, basedOn: 'first', durationMs: 3000, url: '/api/tasks/1/video' },
    })];
    video();
    const card = within(screen.getByRole('article'));
    const player = card.getByLabelText('镜头视频');
    expect(player).toHaveAttribute('src', '/api/tasks/1/video');
    expect(player).toHaveAttribute('poster', '/api/shot-images/1/image');
    await userEvent.click(card.getByText('镜头详情与首帧来源'));
    expect(card.getByText(/首帧来源 · 原首帧/)).toBeVisible();
    expect(card.getByText('计划 6s', { exact: true })).toBeVisible();
    expect(card.getByText('成片 3s', { exact: true })).toBeVisible();
    expect(card.getByText('尝试 1 次')).toBeVisible();
  });

  it('shows video generation in the preview and keeps the previous player usable during regeneration', () => {
    const firstFrame = { id: 'first', seed: '/api/shot-images/1/image', label: '原首帧', createdAt: 1 };
    const renderTab = () => <MemoryRouter><VideoTab projectId="p" episodeId="e" /></MemoryRouter>;
    state.shots = [shot('1', { images: [firstFrame], video: { ready: false, state: 'queued', attempts: 0 } })];
    const { rerender } = render(renderTab());
    const card = within(screen.getByRole('article'));
    const status = within(card.getByRole('status'));
    expect(status.getByText('视频排队中')).toBeVisible();
    expect(status.getByRole('img', { name: '分镜首帧' })).toBeVisible();
    expect(card.getByRole('button', { name: '排队中' })).toBeDisabled();

    state.shots = [shot('1', { images: [firstFrame], video: { ready: false, state: 'running', attempts: 1 } })];
    rerender(renderTab());
    expect(status.getByText('正在生成视频')).toBeVisible();
    expect(status.getByRole('img', { name: '分镜首帧' })).toBeVisible();

    state.shots = [shot('1', { images: [firstFrame], video: {
      ready: true, basedOn: 'first', state: 'running', attempts: 2, url: '/api/tasks/1/video',
    } })];
    rerender(renderTab());
    expect(status.getByText('正在生成新版视频')).toBeVisible();
    expect(status.queryByRole('img', { name: '分镜首帧' })).toBeNull();
    expect(card.getByLabelText('镜头视频')).toHaveAttribute('controls');
    expect(within(screen.getByRole('region', { name: '本集镜头' })).getByText('生成中')).toBeVisible();

    state.shots = [shot('1', { images: [firstFrame], video: {
      ready: true, basedOn: 'first', state: 'idle', attempts: 2, url: '/api/tasks/2/video',
    } })];
    rerender(renderTab());
    expect(card.getByRole('status')).toBeEmptyDOMElement();
    expect(card.getByLabelText('镜头视频')).toHaveAttribute('src', '/api/tasks/2/video');
  });

  it('opens one playable preview, switches to an ungenerated shot, and links to other episodes', async () => {
    state.shots = [
      shot('1', { images: [{ id: 'first', seed: '/api/shot-images/1/image', label: '原首帧', createdAt: 1 }] }),
      shot('2', { video: { ready: true, state: 'idle', attempts: 1, basedOn: 'first', url: '/api/tasks/2/video' } }),
      shot('3', { episodeId: 'e2', status: 'draft' }),
    ];
    video();
    const list = within(screen.getByRole('region', { name: '本集镜头' }));
    expect(list.getByRole('button', { name: /第 02 镜/ })).toHaveAttribute('aria-current', 'true');
    expect(screen.getByLabelText('镜头视频')).toHaveAttribute('src', '/api/tasks/2/video');

    const first = list.getByRole('button', { name: /^第 01 镜/ });
    await userEvent.click(first);
    expect(first).toHaveAttribute('aria-current', 'true');
    expect(screen.queryByLabelText('镜头视频')).not.toBeInTheDocument();
    expect(screen.getByRole('article', { name: '第 01 镜' })).toContainElement(screen.getByRole('img', { name: '分镜首帧' }));

    const episodes = within(screen.getByRole('navigation', { name: '选集' }));
    expect(episodes.getByRole('link', { name: /第一集/ })).toHaveAttribute('aria-current', 'page');
    expect(episodes.getByRole('link', { name: /第二集/ })).toHaveAttribute('href', '/projects/p/episodes/e2/video');
    expect(episodes.getByRole('link', { name: /第三集/ })).toHaveAttribute('href', '/projects/p/episodes/e3/story');
  });

  it('shows an actionable story empty state before breakdown', () => {
    video();
    expect(screen.getByText('还没有可以生成视频的镜头')).toBeVisible();
    expect(screen.getByRole('link', { name: '去拆解这一集' })).toHaveAttribute('href', '/projects/p/episodes/e/story');
  });

  it('explains the prerequisite when all shots are drafts', () => {
    state.shots = [shot('1', { status: 'draft' })]; video();
    expect(screen.getByText('还没有确认的镜头。先到分镜页确认镜头。')).toBeVisible();
    expect(screen.queryByRole('article')).not.toBeInTheDocument();
  });
});

describe('storyboard workspace', () => {
  it('explains exactly which references block a new image, then allows generation once they are selected', async () => {
    state.shots = [shot('1', { images: [], selectedImageId: undefined, status: 'draft', characterNames: ['沈夜'] })];
    const version = { id: 'portrait', source: 'generated', seed: 'portrait', label: '定妆图', createdAt: 1 } as const;
    state.characters = [
      { id: 'other', projectId: 'other-project', name: '沈夜', description: '', versions: [version], selectedVersionId: version.id },
      { id: 'character', projectId: 'p', name: '沈夜', description: '', versions: [version] },
    ];
    const view = storyboard();
    const generate = screen.getByRole('button', { name: '生成分镜图' });
    expect(generate).toHaveAttribute('aria-disabled', 'true');
    await userEvent.click(generate);
    expect(screen.getByRole('status')).toHaveTextContent('请先为角色「沈夜」生成或上传参考图。');
    expect(screen.getByRole('link', { name: '去角色库' })).toHaveAttribute('href', '/projects/p/characters');
    expect(screen.queryByRole('link', { name: '去场景库' })).not.toBeInTheDocument();
    expect(state.generateImage).not.toHaveBeenCalled();

    state.characters[1] = { ...state.characters[1], selectedVersionId: version.id };
    view.rerender(<MemoryRouter><StoryboardTab projectId="p" episodeId="e" /></MemoryRouter>);
    expect(generate).not.toHaveAttribute('aria-disabled');
    expect(screen.queryByRole('status')).not.toBeInTheDocument();
    await userEvent.click(generate);
    expect(state.generateImage).toHaveBeenCalledExactlyOnceWith('1');
  });

  it('allows image generation before confirmation and only shows confirmation for shots with a frame', async () => {
    state.shots = [shot('1', { status: 'draft', images: [], selectedImageId: undefined }), shot('2', { status: 'draft' }),
      shot('3', { images: [], selectedImageId: undefined })];
    storyboard();
    expect(screen.getByText(/3 镜 · 2 镜待出图/)).toBeVisible();
    const [, noImage, ready, legacy] = screen.getAllByRole('row');
    for (const row of [noImage, legacy]) {
      expect(within(row).getByText('待出图', { exact: true })).toBeVisible();
      expect(within(row).queryByText(/已确认|待确认/)).not.toBeInTheDocument();
      expect(within(row).queryByRole('button', { name: '确认画面' })).not.toBeInTheDocument();
      expect(within(row).getByRole('button', { name: '生成分镜图' })).toBeEnabled();
    }
    expect(within(ready).getByText('待确认', { exact: true })).toBeVisible();
    expect(within(ready).getByRole('button', { name: '确认画面' })).toBeEnabled();
    await userEvent.click(screen.getByRole('button', { name: '待确认' }));
    expect(screen.getAllByRole('row')).toHaveLength(2);
    await userEvent.click(screen.getByRole('button', { name: '待出图' }));
    expect(screen.getAllByRole('row')).toHaveLength(3);
  });

  it('shows a filter empty state and hides redundant whole-episode confirmation', async () => {
    state.shots = [shot('1')]; storyboard();
    expect(screen.queryByRole('button', { name: '批量确认画面' })).not.toBeInTheDocument();
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
