import type { ReactNode } from 'react';

export function EmptyState({
  title,
  description,
  action,
  icon,
}: {
  title: string;
  description?: string;
  action?: ReactNode;
  icon?: ReactNode;
}) {
  return (
    <div className="flex flex-col items-center justify-center gap-3 rounded-panel border border-dashed border-stroke/35 px-8 py-14 text-center">
      {icon && <div className="text-muted/70">{icon}</div>}
      <p className="text-[15px] font-medium">{title}</p>
      {description && <p className="max-w-sm text-sm leading-6 text-muted">{description}</p>}
      {action && <div className="mt-1">{action}</div>}
    </div>
  );
}
