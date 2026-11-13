import { Link } from 'react-router-dom';
import type { Episode, Project, Shot } from '@/lib/types';

function storyExcerpt(episodes: Episode[], shots: Shot[]) {
  const episode = episodes.find(ep => ep.story.trim() || ep.summary.trim());
  if (episode) return {
    episode, label: episode.story.trim() ? '剧本节选' : '分集梗概', tab: 'story',
    text: episode.story.trim().split(/\r?\n\s*\r?\n/)[0] || episode.summary.trim(),
  };

  for (const ep of episodes) {
    const shot = shots.find(s => s.episodeId === ep.id && (s.description.trim() || s.dialogue.trim()));
    if (shot) return { episode: ep, label: '分镜节选', tab: 'storyboard', text: shot.description.trim() || shot.dialogue.trim() };
  }
}

export function ProjectStory({ project, episodes, shots }: { project: Project; episodes: Episode[]; shots: Shot[] }) {
  const synopsis = project.synopsis?.trim() ?? '';
  const excerpt = synopsis ? undefined : storyExcerpt(episodes, shots);
  if (!synopsis && !excerpt) return null;

  return (
    <section aria-labelledby="project-story-heading" className="rounded-panel border border-stroke/25 bg-panel/70 px-5 py-4 sm:px-6">
      <h2 id="project-story-heading" className="text-base font-semibold">故事简介</h2>
      {excerpt ? (
        <div className="mt-2 max-w-[42rem]">
          <p className="line-clamp-3 break-words text-sm leading-7 text-fg/90">{excerpt.text}</p>
          <Link to={`/projects/${project.id}/episodes/${excerpt.episode.id}/${excerpt.tab}`}
            className="mt-2 inline-block text-xs text-muted hover:text-fg">
            {excerpt.label} · 第 {excerpt.episode.index} 集「{excerpt.episode.title}」 <span aria-hidden="true">→</span>
          </Link>
        </div>
      ) : (
        <div className="mt-2 max-w-[42rem] space-y-3 break-words text-sm leading-7 text-fg/90">
          {synopsis.split(/\r?\n\s*\r?\n/).map((paragraph, index) => (
            <p key={index} className="whitespace-pre-line">{paragraph}</p>
          ))}
        </div>
      )}
    </section>
  );
}
