import { useState } from 'react';

export function EpisodeNameInput({ title, onSave, onCancel }: {
  title: string;
  onSave: (title: string) => void;
  onCancel: () => void;
}) {
  const [name, setName] = useState(title);

  function save() {
    const trimmed = name.trim();
    if (trimmed && trimmed !== title) onSave(trimmed);
    else onCancel();
  }

  return <input
    type="text"
    aria-label="集名"
    className="field h-6 min-w-0 px-1.5 py-0 text-[13px]"
    value={name}
    maxLength={120}
    autoFocus
    onChange={(event) => setName(event.target.value)}
    onBlur={save}
    onKeyDown={(event) => {
      if (event.nativeEvent.isComposing) return;
      if (event.key === 'Enter') {
        event.preventDefault();
        save();
      } else if (event.key === 'Escape') {
        event.preventDefault();
        onCancel();
      }
    }}
  />;
}
