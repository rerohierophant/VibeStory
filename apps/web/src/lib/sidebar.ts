import { useCallback, useState } from 'react';

const KEY = 'vibestory.sidebar.collapsed';

/**
 * 左侧栏折叠状态。两个布局（工作台 / 项目内）共用一份：
 * 在一处折叠，切到另一处也是折叠的，避免来回跳页时宽度闪一下。
 */
export function useSidebarCollapsed(): [boolean, () => void] {
  const [collapsed, setCollapsed] = useState(() => {
    try {
      return localStorage.getItem(KEY) === '1';
    } catch {
      return false;
    }
  });

  const toggle = useCallback(() => {
    setCollapsed((current) => {
      const next = !current;
      try {
        localStorage.setItem(KEY, next ? '1' : '0');
      } catch {
        /* 隐私模式下写不了，只在本次会话生效 */
      }
      return next;
    });
  }, []);

  return [collapsed, toggle];
}

export const sidebarWidth = {
  expanded: 'w-[280px]',
  collapsed: 'w-[76px]',
} as const;
