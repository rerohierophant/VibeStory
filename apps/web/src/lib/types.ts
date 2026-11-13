import type { VisualPreset } from './visualPresets';

/** 画面风格预设的 id，见 lib/visualPresets.ts；保存的是稳定 id 而不是展示名。 */
export type VisualType = string;

export const shotSizes = ['远景', '全景', '中景', '近景', '特写'] as const;
export type ShotSize = (typeof shotSizes)[number];

export const cameraMoves = ['固定', '推', '拉', '摇', '移', '跟'] as const;
export type CameraMove = (typeof cameraMoves)[number];

export type ShotStatus = 'draft' | 'confirmed';

export type TaskState = 'idle' | 'queued' | 'running' | 'failed' | 'unknown';

export type BreakdownState = 'idle' | 'running' | 'done';

/** 集是项目里的一等对象：有自己的标题、正文和拆解状态，镜头挂在集下面。 */
export interface Episode {
  id: string;
  index: number;
  title: string;
  summary: string;
  story: string;
  breakdown: BreakdownState;
  createdAt: number;
  updatedAt: number;
  version?: number;
  storyRevision?: number;
  breakdownTask?: { id: string; state: TaskState; error?: string; retryable: boolean; resumable?: boolean };
}

export interface Project {
  id: string;
  name: string;
  visualType: VisualType;
  style: string;
  synopsis?: string;
  createdAt: number;
  updatedAt: number;
  episodes: Episode[];
  visualPreset?: VisualPreset;
  styleEditable?: boolean;
  coverImageUrl?: string;
  teamId?: string;
  version?: number;
}

/** 分镜图生成时使用的人物 / 场景版本；未关联版本时保留名称。 */
export interface AssetVersionSnapshot {
  assetId?: string;
  name: string;
  versionId?: string;
  versionLabel?: string;
}

export interface ShotImageInputSnapshot {
  characters: AssetVersionSnapshot[];
  scene: AssetVersionSnapshot;
}

/** 镜头的生成产物，不属于可复用资产库，也不设陈旧状态。 */
export interface ShotImage {
  id: string;
  label: string;
  seed: string;
  createdAt: number;
  url?: string;
  /** 提交生成时固定；历史图片没有记录时不按当前资产版本补填。 */
  inputSnapshot?: ShotImageInputSnapshot;
  /** 后端记录实际使用的参考图与适配器降级说明。 */
  guidance?: {
    mode: 'none' | 'full' | 'partial' | 'text-only';
    usedVersionIds: string[];
    notice?: string;
  };
}

interface VideoOutput {
  /** 固定记录提交生成时的首帧，不随当前候选图切换。 */
  basedOn?: string;
  url?: string;
  durationMs?: number;
  /** 提交该视频时使用的提示词。 */
  prompt?: string;
}

export interface VideoCandidate extends VideoOutput {
  id: string;
  createdAt?: number;
}

export interface ShotVideo extends VideoOutput {
  ready: boolean;
  state: TaskState;
  attempts: number;
  error?: string;
  taskId?: string;
  retryable?: boolean;
  resumable?: boolean;
  pendingBasedOn?: string;
  /** 成功产物，按生成完成时间从新到旧；任务状态与尝试次数仍在外层。 */
  candidates?: VideoCandidate[];
}

export interface Shot {
  id: string;
  version?: number;
  projectId: string;
  episodeId: string;
  seq: number;
  sceneName: string;
  shotSize: ShotSize;
  cameraMove: CameraMove;
  description: string;
  dialogue: string;
  durationMs: number;
  status: ShotStatus;
  characterNames: string[];
  images: ShotImage[];
  /** 离线演示保留的历史图片，用于追溯视频首帧。 */
  deletedImages?: ShotImage[];
  selectedImageId?: string;
  imageState: TaskState;
  imageTaskId?: string;
  imageError?: string;
  imageRetryable?: boolean;
  video: ShotVideo;
}

export interface AssetVersion {
  id: string;
  label: string;
  seed: string;
  createdAt: number;
  source: 'uploaded' | 'generated';
}

export interface Character {
  id: string;
  projectId: string;
  name: string;
  description: string;
  versions: AssetVersion[];
  selectedVersionId?: string;
  generationState?: TaskState;
  taskId?: string;
}

export interface SceneAsset {
  id: string;
  projectId: string;
  name: string;
  description: string;
  versions: AssetVersion[];
  selectedVersionId?: string;
  generationState?: TaskState;
  taskId?: string;
}

export interface User {
  id?: string;
  email: string;
}

export type ProviderKind = 'mock' | 'openai' | 'compatible';

/** 离线演示兼容字段；联机模式读取后端实际配置，不读取浏览器 Token。 */
export interface ProviderSettings {
  provider: ProviderKind;
  baseUrl: string;
  token: string;
  imageModel: string;
  videoModel: string;
}

export interface State {
  user: User | null;
  projects: Project[];
  /** 用户上传的自定义画面风格，与内置预设同构，存在本地存储里。 */
  customStyles: VisualPreset[];
  shots: Shot[];
  characters: Character[];
  scenes: SceneAsset[];
  settings: ProviderSettings;
}
