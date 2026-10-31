import { NavLink, Outlet } from 'react-router-dom';
import { Logo, LogoMark } from '@/components/Logo';
import { SidebarToggle } from '@/components/SidebarToggle';
import { sidebarWidth, useSidebarCollapsed } from '@/lib/sidebar';
import { useStore } from '@/lib/store';

function NavItem({
  to,
  end,
  label,
  icon,
  count,
  collapsed,
}: {
  to: string;
  end?: boolean;
  label: string;
  icon: React.ReactNode;
  count?: number;
  collapsed: boolean;
}) {
  return (
    <NavLink
      to={to}
      end={end}
      title={collapsed ? label : undefined}
      className={({ isActive }) =>
        `flex items-center rounded-card text-[13px] transition ${
          collapsed ? 'justify-center px-0 py-2.5' : 'gap-3 px-3 py-2.5'
        } ${isActive ? 'bg-hover/70 text-fg' : 'text-muted hover:bg-hover/40 hover:text-fg'}`
      }
    >
      <span className="flex w-4 shrink-0 justify-center text-current/80">{icon}</span>
      {!collapsed && (
        <>
          <span className="flex-1">{label}</span>
          {count !== undefined && <span className="text-xs text-muted/70">{count}</span>}
        </>
      )}
    </NavLink>
  );
}

/** 工作台外壳：入口导航固定在左栏，项目详情使用自己的 ProjectLayout。 */
export function HomeLayout() {
  const { user, projects, characters, scenes } = useStore();
  const [collapsed, toggleSidebar] = useSidebarCollapsed();

  return (
    <div className="flex h-full overflow-hidden">
      {/* 折叠把手挂在 aside 外侧的定位容器上，否则会被 overflow-hidden 裁掉 */}
      <div className="relative flex shrink-0">
        <aside
          className={`flex flex-col overflow-hidden border-r border-stroke/15 bg-panel/50 py-6 transition-[width] duration-200 ease-out ${
            collapsed ? `${sidebarWidth.collapsed} px-2` : `${sidebarWidth.expanded} px-4`
          }`}
        >
          <div className={collapsed ? 'flex justify-center' : 'px-2'}>
            {collapsed ? (
              <LogoMark size={30} />
            ) : (
              <Logo size={30} wordmarkClassName="text-sm tracking-[0.16em]" />
            )}
          </div>

          <nav className="mt-8 min-h-0 flex-1 space-y-1 overflow-y-auto scroll-thin">
            {!collapsed && (
              <p className="px-3 pb-2 text-[11px] tracking-wide text-muted/60">工作台</p>
            )}
            <NavItem
              to="/"
              end
              label="我的项目"
              count={projects.length}
              collapsed={collapsed}
              icon={
                <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.3" className="h-4 w-4">
                  <path d="M2 5.2c0-.7.6-1.2 1.2-1.2h2.4l1.2 1.5h5c.7 0 1.2.6 1.2 1.2v5.1c0 .7-.6 1.2-1.2 1.2H3.2c-.7 0-1.2-.6-1.2-1.2V5.2Z" />
                </svg>
              }
            />
            <NavItem
              to="/assets"
              label="资产库"
              count={characters.length + scenes.length}
              collapsed={collapsed}
              icon={
                <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.3" className="h-4 w-4">
                  <rect x="2" y="2" width="5.5" height="5.5" rx="1" />
                  <rect x="8.5" y="2" width="5.5" height="5.5" rx="1" />
                  <rect x="2" y="8.5" width="5.5" height="5.5" rx="1" />
                  <rect x="8.5" y="8.5" width="5.5" height="5.5" rx="1" />
                </svg>
              }
            />
          </nav>

          <div className="mt-3 space-y-3 border-t border-stroke/15 pt-4">
            <NavItem
              to="/settings"
              label="设置"
              collapsed={collapsed}
              icon={
                <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.3" className="h-4 w-4">
                  <circle cx="8" cy="8" r="2.2" />
                  <path d="M8 1.8v1.5M8 12.7v1.5M14.2 8h-1.5M3.3 8H1.8M12.4 3.6l-1 1M4.6 11.4l-1 1M12.4 12.4l-1-1M4.6 4.6l-1-1" />
                </svg>
              }
            />

            <div className={collapsed ? 'hidden' : 'px-1 pt-2 text-xs text-muted'}>
              <p className="truncate" title={user?.email}>
                {user?.email}
              </p>
            </div>
          </div>
        </aside>

        <SidebarToggle collapsed={collapsed} onToggle={toggleSidebar} />
      </div>

      <main className="flex min-h-0 min-w-0 flex-1 flex-col overflow-hidden">
        <Outlet />
      </main>
    </div>
  );
}
