import { Link } from 'react-router-dom';
import { missingReferenceMessage, type ShotReference } from '@/lib/imageSource';

export const blockedGenerationStyle = 'cursor-not-allowed opacity-40';

export function ShotReferenceHint({ projectId, missing, id }: { projectId: string; missing: ShotReference[]; id?: string }) {
  return <div id={id} role="status" className="space-y-1 text-xs leading-5 text-muted [overflow-wrap:anywhere]">
    <p>{missingReferenceMessage(missing)}</p>
    <div className="flex flex-wrap gap-x-2 text-accent-soft">
      {missing.some(ref => ref.kind === 'character') && <Link className="underline" to={`/projects/${projectId}/characters`}>去角色库</Link>}
      {missing.some(ref => ref.kind === 'scene') && <Link className="underline" to={`/projects/${projectId}/scenes`}>去场景库</Link>}
    </div>
  </div>;
}
