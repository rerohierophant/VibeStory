import { useId, useState } from 'react';
import { Badge } from '@/components/Badge';
import { Button } from '@/components/Button';
import { Modal } from '@/components/Modal';
import { ProjectStyleDescription, ProjectStylePicker } from '@/components/ProjectStyleFields';
import { useStore } from '@/lib/store';
import type { Project } from '@/lib/types';
import { visualLabel } from '@/lib/visualPresets';

export function ProjectStyle({ project, editable }: { project: Project; editable: boolean }) {
  const { customStyles } = useStore();
  const [draft, setDraft] = useState<Project>();
  const label = project.visualPreset?.label ?? visualLabel(project.visualType, customStyles);

  return (
    <>
      {editable ? (
        <button type="button" aria-haspopup="dialog" title="切换项目风格" onClick={() => setDraft(project)}
          className="group/style inline-flex cursor-pointer rounded-full focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent-soft">
          <Badge tone="muted" className="transition group-hover/style:border-accent/50 group-hover/style:text-fg">{label}</Badge>
        </button>
      ) : <Badge tone="muted">{label}</Badge>}
      {draft && <ProjectStyleDialog project={draft} editable={editable} onClose={() => setDraft(undefined)} />}
    </>
  );
}

function ProjectStyleDialog({ project, editable, onClose }: { project: Project; editable: boolean; onClose: () => void }) {
  const { updateProjectStyle } = useStore();
  const title = useId();
  const [visualType, setVisualType] = useState(project.visualType);
  const [style, setStyle] = useState(project.style);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');
  const changed = visualType !== project.visualType || style !== project.style;

  async function save() {
    setSaving(true);
    setError('');
    try {
      await updateProjectStyle(project.id, { visualType, style }, project.version);
      onClose();
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '保存失败，请重试');
      setSaving(false);
    }
  }

  return (
    <Modal open onClose={() => { if (!saving) onClose(); }} labelledBy={title} width="max-w-[900px]">
      <header className="sticky top-0 z-10 border-b border-stroke/20 bg-panel/95 px-6 py-5 backdrop-blur sm:px-8">
        <h2 id={title} className="text-xl font-semibold">切换项目风格</h2>
        <p className="mt-1.5 text-sm text-muted">保存后，分镜图、角色图和场景图将按新风格生成。</p>
      </header>
      <fieldset disabled={saving || !editable} className="min-w-0 space-y-5 px-6 py-5 sm:px-8">
        <ProjectStylePicker visualType={visualType} setVisualType={setVisualType}
          currentPreset={project.visualPreset} allowCustom={!project.teamId} />
        <ProjectStyleDescription value={style} onChange={setStyle} />
      </fieldset>
      <footer className="sticky bottom-0 border-t border-stroke/20 bg-panel/95 px-6 py-4 backdrop-blur sm:px-8">
        {(error || !editable) && (
          <p role="alert" className="mb-3 text-xs text-error-soft">{error || '当前项目的出图状态已变化，暂时无法切换风格。'}</p>
        )}
        <div className="flex justify-end gap-3">
          <Button variant="ghost" disabled={saving} onClick={onClose}>取消</Button>
          <Button disabled={!changed || saving || !editable} onClick={() => void save()}>
            {saving ? '保存中…' : '保存风格'}
          </Button>
        </div>
      </footer>
    </Modal>
  );
}
