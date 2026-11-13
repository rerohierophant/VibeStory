import { GenerationMark } from '@/components/GenerationLoading';
import { PlaceholderImage } from '@/components/PlaceholderImage';
import styles from '@/components/GenerationLoading.module.css';

export function VideoLoading({ state, hasPreviousVideo, firstFrame }: {
  state: 'queued' | 'running';
  hasPreviousVideo: boolean;
  firstFrame?: string;
}) {
  const queued = state === 'queued';
  const label = queued ? '视频排队中' : hasPreviousVideo ? '正在生成新版视频' : '正在生成视频';

  if (hasPreviousVideo) {
    return (
      <div className="pointer-events-none absolute top-3 left-3 flex items-center gap-2 rounded-card border border-white/15 bg-black/80 px-2.5 py-1.5 text-xs text-white">
        <GenerationMark state={state} className="size-5" />
        {label}
      </div>
    );
  }

  return (
    <div className={`absolute inset-0 flex flex-col justify-between overflow-hidden p-3.5 @min-[40rem]/video:p-5 ${styles.preview}`}>
      <div className="pointer-events-none absolute inset-0 grid place-items-center">
        <GenerationMark state={state} className="size-14 @min-[40rem]/video:size-[5.75rem]" />
      </div>
      <div className="relative w-fit rounded-card border border-white/10 bg-black/55 px-3 py-2">
        <p className="text-sm font-semibold text-white">{label}</p>
        <p className="mt-0.5 text-xs text-white/75">{queued ? '任务已提交，等待开始生成' : '完成后会自动显示视频'}</p>
      </div>
      {firstFrame && (
        <figure className="relative flex w-fit items-center gap-2 rounded-card border border-white/10 bg-black/50 p-1.5 pr-2.5">
          <PlaceholderImage seed={firstFrame} alt="分镜首帧" fit="contain" className="w-14 shrink-0" />
          <figcaption className="text-xs text-white/80">分镜首帧</figcaption>
        </figure>
      )}
    </div>
  );
}
