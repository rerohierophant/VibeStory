/**
 * 侧边栏折叠把手：贴在左栏右边缘，展开时箭头朝左（折叠），折叠时朝右（展开）。
 */
export function SidebarToggle({
  collapsed,
  onToggle,
  className = '',
}: {
  collapsed: boolean;
  onToggle: () => void;
  className?: string;
}) {
  return (
    <button
      type="button"
      onClick={onToggle}
      aria-label={collapsed ? '展开侧边栏' : '折叠侧边栏'}
      aria-expanded={!collapsed}
      title={collapsed ? '展开侧边栏' : '折叠侧边栏'}
      className={`absolute -right-3 top-6 z-20 flex h-6 w-6 items-center justify-center rounded-full border border-stroke/30 bg-panel text-muted shadow-[0_2px_10px_rgba(0,0,0,0.35)] transition hover:border-stroke/60 hover:text-fg ${className}`}
    >
      <svg
        viewBox="0 0 16 16"
        fill="none"
        stroke="currentColor"
        strokeWidth="1.6"
        strokeLinecap="round"
        strokeLinejoin="round"
        className={`h-3.5 w-3.5 transition-transform duration-200 ${collapsed ? '' : 'rotate-180'}`}
      >
        <path d="m6 3.5 4.5 4.5L6 12.5" />
      </svg>
    </button>
  );
}
