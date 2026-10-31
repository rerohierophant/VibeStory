import type { VisualPreset } from './visualPresets';

/** 画面风格预设的 id，见 lib/visualPresets.ts；保存的是稳定 id 而不是展示名。 */
export type VisualType = string;

export type ShotSize = '远景' | '全景' | '中景' | '近景' | '特写';

export type CameraMove = '固定' | '推' | '拉' | '摇' | '移' | '跟';

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
}

export interface Project {
  id: string;
  name: string;
  visualType: VisualType;
  style: string;
  createdAt: number;
  updatedAt: number;
  episodes: Episode[];
}

export interface ShotImage {
  id: string;
  label: string;
  seed: string;
  createdAt: number;
}

export interface ShotVideo {
  ready: boolean;
  basedOn?: string;
  stale: boolean;
  state: TaskState;
  attempts: number;
  error?: string;
}

export interface Shot {
  id: string;
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
  selectedImageId?: string;
  imageState: TaskState;
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
}

export interface SceneAsset {
  id: string;
  projectId: string;
  name: string;
  description: string;
  versions: AssetVersion[];
  selectedVersionId?: string;
}

export interface User {
  email: string;
}

export type ProviderKind = 'mock' | 'openai' | 'compatible';

/** 生成能力的接入配置。原型阶段只保存，不发起真实调用。 */
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
