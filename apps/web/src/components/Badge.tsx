import type { ShotStatus, TaskState } from '@/lib/types';

type Tone = 'neutral' | 'accent' | 'success' | 'warning' | 'error' | 'muted';

const tones: Record<Tone, string> = {
  neutral: 'bg-panel-2 text-fg border-stroke/30',
  accent: 'bg-accent/15 text-accent-soft border-accent/40',
  success: 'bg-success/15 text-success-soft border-success/35',
  warning: 'bg-warning/10 text-warning border-warning/30',
  error: 'bg-error/15 text-error-soft border-error/35',
  muted: 'bg-white/5 text-muted border-white/10',
};

export function Badge({
  tone = 'neutral',
  children,
  dot = false,
  className = '',
}: {
  tone?: Tone;
  children: React.ReactNode;
  dot?: boolean;
  className?: string;
}) {
  return (
    <span
      className={`inline-flex items-center gap-1.5 rounded-full border px-2 py-0.5 text-[11px] leading-5 ${tones[tone]} ${className}`}
    >
      {dot && <span className="h-1.5 w-1.5 rounded-full bg-current" />}
      {children}
    </span>
  );
}

export function ShotStatusBadge({ status }: { status: ShotStatus }) {
  return status === 'confirmed' ? (
    <Badge tone="success">已确认</Badge>
  ) : (
    <Badge tone="warning" dot>
      待确认
    </Badge>
  );
}

const taskLabels: Record<TaskState, { text: string; tone: Tone }> = {
  idle: { text: '空闲', tone: 'muted' },
  queued: { text: '排队中', tone: 'neutral' },
  running: { text: '生成中', tone: 'accent' },
  failed: { text: '失败', tone: 'error' },
  unknown: { text: '结果未知', tone: 'warning' },
};

export function TaskBadge({ state }: { state: TaskState }) {
  const { text, tone } = taskLabels[state];
  return (
    <Badge tone={tone} dot={state === 'running'}>
      {text}
    </Badge>
  );
}
