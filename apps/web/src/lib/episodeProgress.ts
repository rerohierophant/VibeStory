import type { BreakdownState } from './types';

/** 正文修改会重置拆解状态，已经保存的镜头仍计入制作进度。 */
export function episodeBreakdownProgress(breakdown: BreakdownState, shotCount: number): BreakdownState {
  if (breakdown === 'running') return 'running';
  return shotCount > 0 ? 'done' : 'idle';
}
