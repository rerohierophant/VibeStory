import type { State } from './types';

export function demoProjectStyleEditable(projectId: string, { shots, characters, scenes }: Pick<State, 'shots' | 'characters' | 'scenes'>) {
  const active = (state?: string) => state === 'queued' || state === 'running' || state === 'unknown';
  return !shots.some(shot => shot.projectId === projectId
    && (shot.images.length > 0 || !!shot.deletedImages?.length || active(shot.imageState)))
    && ![...characters, ...scenes].some(asset => asset.projectId === projectId
      && (active(asset.generationState) || asset.versions.some(version => version.source === 'generated')));
}
