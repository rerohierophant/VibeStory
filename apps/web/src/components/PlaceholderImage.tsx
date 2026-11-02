const palettes = [
  ['#2b3f5c', '#6d7fa8', '#c9d3e6'],
  ['#4a2f47', '#8a5b7c', '#e0c7d6'],
  ['#1f3d38', '#4f7a70', '#c6ded6'],
  ['#3a2f1c', '#7a6640', '#e3d5b3'],
  ['#262a3d', '#545a80', '#c3c7e2'],
  ['#3d2222', '#7d4a45', '#e6c0b8'],
];

function hash(seed: string): number {
  let h = 0;
  for (let i = 0; i < seed.length; i += 1) h = (h * 31 + seed.charCodeAt(i)) % 9973;
  return h;
}

interface Props {
  seed: string;
  label?: string;
  className?: string;
  ratio?: string | null;
  rounded?: string;
}

/**
 * 占位图：由 seed 决定配色与构图，用来替代尚未接入的真实生成结果。
 * 接入 Provider 后，把这里换成 <img src={url} /> 即可。
 * ratio 传 null 表示不给宽高比，由父级（例如网格单元）决定尺寸。
 */
export function PlaceholderImage({ seed, label, className = '', ratio = '16 / 9', rounded = 'rounded-card' }: Props) {
  if (seed.startsWith('/api/') || seed.startsWith('data:image/')) {
    return <div className={`relative overflow-hidden bg-black/25 ${rounded} ${className}`} style={{ aspectRatio: ratio ?? undefined }}>
      <img src={seed} alt={label ?? '生成图片'} className="h-full w-full object-cover" loading="lazy" />
      {label && <span className="absolute bottom-2 left-2.5 rounded bg-black/45 px-1.5 py-0.5 text-[10px] text-white/85">{label}</span>}
    </div>;
  }
  const h = hash(seed);
  const [dark, mid, light] = palettes[h % palettes.length];
  const x = 20 + (h % 55);
  const y = 25 + ((h >> 3) % 45);
  const angle = 120 + (h % 140);

  return (
    <div
      className={`relative overflow-hidden ${rounded} ${className}`}
      style={{ aspectRatio: ratio ?? undefined, background: `linear-gradient(${angle}deg, ${dark}, ${mid})` }}
      aria-hidden
    >
      <div
        className="absolute inset-0 opacity-70 mix-blend-screen"
        style={{
          background: `radial-gradient(circle at ${x}% ${y}%, ${light}33, transparent 55%),
                       radial-gradient(circle at ${100 - x}% ${100 - y}%, ${mid}66, transparent 60%)`,
        }}
      />
      <div
        className="absolute inset-0 opacity-[0.18]"
        style={{
          backgroundImage:
            'repeating-linear-gradient(0deg, rgba(255,255,255,0.5) 0 1px, transparent 1px 3px)',
        }}
      />
      <div className="absolute inset-x-0 bottom-0 h-1/2 bg-gradient-to-t from-black/55 to-transparent" />
      {label && (
        <span className="absolute bottom-2 left-2.5 rounded bg-black/45 px-1.5 py-0.5 text-[10px] tracking-wide text-white/85">
          {label}
        </span>
      )}
    </div>
  );
}
