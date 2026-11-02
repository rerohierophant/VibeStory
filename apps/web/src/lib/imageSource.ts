import type {
  AssetVersionSnapshot,
  Character,
  SceneAsset,
  Shot,
  ShotImageInputSnapshot,
} from './types';

/** 复制提交时的版本身份与展示名，后续切换基准版本不会改写来源。 */
export function captureImageInputSnapshot(
  shot: Pick<Shot, 'projectId' | 'characterNames' | 'sceneName'>,
  characters: Character[],
  scenes: SceneAsset[],
): ShotImageInputSnapshot {
  const capture = (name: string, assets: (Character | SceneAsset)[]): AssetVersionSnapshot => {
    const asset = assets.find(
      (a) => a.projectId === shot.projectId && a.name.trim().toLowerCase() === name.trim().toLowerCase(),
    );
    const version = asset?.versions.find((v) => v.id === asset.selectedVersionId);
    return {
      name: asset?.name ?? name,
      assetId: asset?.id,
      versionId: version?.id,
      versionLabel: version?.label,
    };
  };

  return {
    characters: shot.characterNames.map((name) => capture(name, characters)),
    scene: capture(shot.sceneName, scenes),
  };
}
