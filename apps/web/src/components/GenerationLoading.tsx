import { useId } from 'react';
import { brandMarkPath } from './Logo';
import styles from './GenerationLoading.module.css';

export type GenerationLoadingState = 'submitting' | 'queued' | 'running';

export function GenerationMark({ state, className = 'size-4' }: {
  state: GenerationLoadingState;
  className?: string;
}) {
  const id = useId();
  const pathId = `${id}-v`;
  const gradientId = `${id}-gradient`;

  return (
    <svg aria-hidden="true" focusable="false" viewBox="12 15 72 72" fill="none" strokeWidth="7.5"
      strokeLinecap="round" strokeLinejoin="round"
      className={`shrink-0 ${state === 'queued' ? styles.queued : ''} ${className}`}>
      <defs>
        <path id={pathId} d={brandMarkPath} pathLength="100" />
        <linearGradient id={gradientId} x1="23" y1="29" x2="73" y2="73" gradientUnits="userSpaceOnUse">
          <stop offset="0" stopColor="color-mix(in srgb, var(--vibe-accent-soft) 68%, var(--vibe-accent))" />
          <stop offset="0.58" stopColor="var(--vibe-accent-soft)" />
          <stop offset="1" stopColor="color-mix(in srgb, var(--vibe-accent-soft) 82%, var(--vibe-accent))" />
        </linearGradient>
      </defs>
      <use href={`#${pathId}`} className={styles.track} />
      <use href={`#${pathId}`} stroke={`url(#${gradientId})`} className={styles.trace} />
    </svg>
  );
}

export function ImageGenerationLoading({ state, label }: {
  state: GenerationLoadingState;
  label: string;
}) {
  return (
    <div className={`absolute inset-0 grid place-content-center justify-items-center overflow-hidden rounded-card px-1.5 text-center ${styles.preview}`}>
      <GenerationMark state={state} className="size-10 max-w-full" />
      <span className="text-[11px] leading-4 font-medium text-fg">{label}</span>
    </div>
  );
}
