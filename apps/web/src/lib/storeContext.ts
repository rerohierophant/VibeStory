import { createContext } from 'react';
import type { State, Episode, Shot, VisualType, ProviderSettings } from './types';
import type { components } from '../../../../packages/contracts/src/api';
type Schema = components['schemas'];
export type RuntimeConfig = Schema['RuntimeConfig'];
export type VideoRegeneration = Required<Pick<Schema['Submit'], 'prompt' | 'expectedFirstFrameId' | 'regenerateNonce'>>;
export interface NewProjectInput {
  name: string;
  visualType: VisualType;
  style: string;
}

export type ProjectStyleInput = Pick<NewProjectInput, 'visualType' | 'style'>;

export interface StoreValue extends State {
  mode: 'api' | 'demo';
  workspaceLoading?: boolean;
  workspaceTeam?: Schema['TeamView'];
  workspaceReadOnly?: boolean;
  saving?: boolean;
  error?: string;
  dismissError?: () => void;
  retrySave?: () => void;
  discardConflictedEdit?: () => void;
  runtime?: RuntimeConfig;
  retryTask?: (taskId: string, acknowledgeUnknown?: boolean) => void;
  login: (email: string, password?: string) => void | Promise<void>;
  register?: (email: string, password: string) => Promise<void>;
  logout: () => void;
  createProject: (input: NewProjectInput) => string | Promise<string>;
  updateProjectStyle: (id: string, input: ProjectStyleInput, expectedVersion?: number) => Promise<void>;
  renameProject: (id: string, name: string) => void;
  deleteProject: (id: string) => void;

  createEpisode: (projectId: string) => string | Promise<string>;
  updateEpisode: (episodeId: string, patch: Partial<Pick<Episode, 'title' | 'summary'>>) => void;
  deleteEpisode: (episodeId: string) => void | Promise<void>;
  setEpisodeStory: (episodeId: string, text: string) => void;
  runBreakdown: (episodeId: string, replaceExisting: boolean) => void;
  performCreative?: <T>(key: string, action: () => Promise<T>) => Promise<T>;

  updateShot: (id: string, patch: Partial<Shot>) => void;
  sendAgentMessage?: (conversationId: string, requestKey: string, input: FormData,
    onEvent: (event: Schema['ReplyEvent']) => void) => Promise<Schema['Transcript']>;
  confirmAgentWorkflow?: (conversationId: string, workflowId: string) => Promise<Schema['Transcript']>;
  decideShotEdit?: (episodeId: string, id: string, decision: 'apply' | 'discard') => Promise<Schema['Proposal']>;
  confirmShot: (id: string, imageId: string) => void | Promise<unknown>;
  unconfirmShot: (id: string) => void | Promise<unknown>;
  deleteShotImage: (shotId: string, imageId: string) => void | Promise<void>;
  confirmAll: (episodeId: string, frames: Record<string, string>) => void | Promise<unknown>;
  generateImage: (id: string) => void | Promise<unknown>;
  generateVideo: (id: string, regeneration?: VideoRegeneration) => void | Promise<unknown>;
  submitBatch?: (episodeId: string, type: 'IMAGE' | 'VIDEO', requestKey: string, shotIds: string[], regenerate: boolean) => Promise<components['schemas']['GenerationBatch']>;

  addAssetVersion: (
    kind: 'character' | 'scene',
    ownerId: string,
    source: 'uploaded' | 'generated',
    file?: File,
  ) => void | Promise<unknown>;
  selectAssetVersion: (kind: 'character' | 'scene', ownerId: string, versionId: string) => void;
  saveSettings: (patch: Partial<ProviderSettings>) => void;
  addCustomStyle: (input: { label: string; image: string }) => string | Promise<string>;
  removeCustomStyle: (id: string) => void;
  reset: () => void;
}


export const StoreContext = createContext<StoreValue | null>(null);
