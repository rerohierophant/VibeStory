import type {
  AssetVersionSnapshot,
  AssetVersion,
  Character,
  SceneAsset,
  Shot,
  ShotImageInputSnapshot,
} from './types';

type ShotAssets = Pick<Shot, 'projectId' | 'characterNames' | 'sceneName'>;
type ReferenceKind = 'character' | 'scene';
export type ShotReference = { kind: ReferenceKind; name: string; assetId?: string; version?: AssetVersion };

export function resolveShotReferences(shot: ShotAssets, characters: Character[], scenes: SceneAsset[]): ShotReference[] {
  const resolve = (kind: ReferenceKind, name: string, assets: (Character | SceneAsset)[]): ShotReference => {
    const asset = assets.find(a => a.projectId === shot.projectId &&
      a.name.trim().toLowerCase().normalize() === name.trim().toLowerCase().normalize());
    return { kind, name: asset?.name ?? name, assetId: asset?.id,
      version: asset?.versions.find(v => v.id === asset.selectedVersionId) };
  };
  return [
    ...shot.characterNames.filter(name => name.trim()).map(name => resolve('character', name, characters)),
    ...(shot.sceneName.trim() ? [resolve('scene', shot.sceneName, scenes)] : []),
  ];
}

export function missingReferenceMessage(references: ShotReference[]): string {
  const groups = (['character', 'scene'] as const).flatMap(kind => {
    const names = [...new Set(references.filter(ref => ref.kind === kind && !ref.version).map(ref => ref.name))];
    return names.length ? [`${kind === 'character' ? '角色' : '场景'}「${names.join('、')}」`] : [];
  });
  return groups.length ? `请先为${groups.join('和')}生成或上传参考图。` : '';
}

/** 复制提交时的版本身份与展示名，后续切换基准版本不会改写来源。 */
export function captureImageInputSnapshot(
  shot: ShotAssets,
  characters: Character[],
  scenes: SceneAsset[],
): ShotImageInputSnapshot {
  const references = resolveShotReferences(shot, characters, scenes);
  const capture = ({ name, assetId, version }: ShotReference): AssetVersionSnapshot => ({
    name, assetId, versionId: version?.id, versionLabel: version?.label,
  });
  const scene = references.find(ref => ref.kind === 'scene');

  return {
    characters: references.filter(ref => ref.kind === 'character').map(capture),
    scene: scene ? capture(scene) : { name: shot.sceneName },
  };
}
