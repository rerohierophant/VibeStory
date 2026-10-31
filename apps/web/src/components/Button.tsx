import type { ButtonHTMLAttributes, ReactNode } from 'react';

type Variant = 'primary' | 'ghost' | 'outline' | 'danger';
type Size = 'sm' | 'md' | 'lg';

const base =
  'inline-flex items-center justify-center gap-2 whitespace-nowrap rounded-card font-medium transition select-none disabled:opacity-40 disabled:cursor-not-allowed';
const variants: Record<Variant, string> = {
  primary: 'text-white vibe-cta',
  ghost: 'text-muted hover:text-fg hover:bg-hover/60',
  outline: 'border border-stroke/40 text-fg hover:bg-hover/50',
  danger: 'text-error border border-error/40 hover:bg-error/10',
};

const sizes: Record<Size, string> = {
  sm: 'h-8 px-3 text-xs',
  md: 'h-10 px-4 text-sm',
  lg: 'h-12 px-6 text-[15px]',
};

interface Props extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: Variant;
  size?: Size;
  children: ReactNode;
}

export function Button({ variant = 'primary', size = 'md', className = '', children, ...rest }: Props) {
  return (
    <button className={`${base} ${variants[variant]} ${sizes[size]} ${className}`} {...rest}>
      {children}
    </button>
  );
}
