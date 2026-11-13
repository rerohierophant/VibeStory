import { useEffect, useMemo, useRef } from 'react';

const labels: Record<string, string> = {
  title: '分集', summary: '梗概', story: '剧本', name: '素材', description: '描述',
};

function readPreview(snapshot: string) {
  // Consume quoted strings as units so escaped quotes inside prose cannot be mistaken for keys.
  // An unfinished final string is readable up to its last complete escape sequence.
  const tokens = /"((?:[^"\\\x00-\x1f]|\\(?:["\\/bfnrt]|u[\da-fA-F]{4}))*)(")?/g;
  const fields: { label: string; text: string }[] = [];
  let valueOffset = -1;
  let label = '';
  for (const match of snapshot.matchAll(tokens)) {
    const [, body, closed] = match;
    if (label && match.index === valueOffset && body) {
      const text: string = JSON.parse('"' + body + '"');
      fields.push({ label, text });
    }
    if (!closed) break;
    const separator = /^\s*:\s*/.exec(snapshot.slice(match.index + match[0].length));
    label = separator && Object.hasOwn(labels, body) ? labels[body] : '';
    valueOffset = match.index + match[0].length + (separator?.[0].length ?? 0);
  }
  return fields;
}

export function CreativePlanProgress({ status, snapshot = '' }: { status: string; snapshot?: string }) {
  const fields = useMemo(() => readPreview(snapshot), [snapshot]);
  const output = useRef<HTMLDivElement>(null);
  const following = useRef(true);
  useEffect(() => {
    const area = output.current;
    if (area && following.current) area.scrollTop = area.scrollHeight;
  }, [fields]);

  return (
    <section aria-label="分集草案生成预览" className="mt-4 rounded-card border border-accent-soft/25 bg-panel-2/30">
      <div role="status" className="flex items-center gap-2 px-3 py-2.5 text-xs">
        <span aria-hidden="true" className="size-2 shrink-0 rounded-full bg-accent-soft motion-safe:animate-pulse" />
        <span className="font-medium">{status}</span>
      </div>
      {fields.length > 0 && (
        <div ref={output} role="region" aria-label="草案实时内容" tabIndex={0}
          onScroll={event => {
            const area = event.currentTarget;
            following.current = area.scrollHeight - area.scrollTop - area.clientHeight < 24;
          }}
          className="max-h-60 space-y-2 overflow-y-auto scroll-thin border-t border-stroke/20 px-3 py-2.5 text-xs leading-5 text-muted">
          {fields.map(({ label, text }, index) => (
            <p key={index} className="whitespace-pre-wrap wrap-anywhere"><span className="mr-2 font-medium text-fg">{label}</span>{text}</p>
          ))}
        </div>
      )}
    </section>
  );
}
