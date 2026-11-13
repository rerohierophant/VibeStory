export function SidebarToggle({
  collapsed,
  onToggle,
  side = 'left',
  controls,
}: {
  collapsed: boolean;
  onToggle: () => void;
  side?: 'left' | 'right';
  controls?: string;
}) {
  const label = side === 'left'
    ? (collapsed ? '展开侧边栏' : '折叠侧边栏')
    : (collapsed ? '展开 Agent' : '收起 Agent');
  const pointsLeft = side === 'left' ? !collapsed : collapsed;

  return (
    <button
      type="button"
      onClick={onToggle}
      aria-label={label}
      aria-expanded={!collapsed}
      aria-controls={controls}
      title={label}
      className={`absolute top-6 z-20 flex h-6 w-6 items-center justify-center rounded-full border border-stroke/30 bg-panel text-muted shadow-[0_2px_10px_rgba(0,0,0,0.35)] transition hover:border-stroke/60 hover:text-fg ${side === 'left' ? '-right-3' : '-left-3'}`}
    >
      <svg
        aria-hidden="true"
        viewBox="0 0 16 16"
        fill="none"
        stroke="currentColor"
        strokeWidth="1.6"
        strokeLinecap="round"
        strokeLinejoin="round"
        className={`h-3.5 w-3.5 transition-transform duration-200 motion-reduce:transition-none ${pointsLeft ? 'rotate-180' : ''}`}
      >
        <path d="m6 3.5 4.5 4.5L6 12.5" />
      </svg>
    </button>
  );
}
