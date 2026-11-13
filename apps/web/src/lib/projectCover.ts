import type { Project, Shot, ShotImage } from './types';

export function projectCoverSeed(project: Project, shots: Shot[], demo: boolean) {
  if (project.coverImageUrl) return project.coverImageUrl;

  const episodeIndex = new Map(project.episodes.map(episode => [episode.id, episode.index]));
  const ordered = shots.filter(shot => shot.projectId === project.id && episodeIndex.has(shot.episodeId)).sort((a, b) =>
    episodeIndex.get(a.episodeId)! - episodeIndex.get(b.episodeId)! || a.seq - b.seq,
  );
  const imageSeed = (image: ShotImage) => image.url || (
    demo || image.seed.startsWith('/api/') || image.seed.startsWith('data:image/') ? image.seed : undefined
  );
  for (const shot of ordered) {
    const image = shot.images.find(candidate => candidate.id === shot.selectedImageId && imageSeed(candidate))
      ?? shot.images.find(imageSeed);
    if (image) return imageSeed(image);
  }
}
