import { initialState } from './mock';
import type { State } from './types';

// v2：镜头改挂 episodeId，项目内嵌 episodes；旧结构的本地数据不再兼容，直接重建。
const KEY = 'vibestory.state.v2';

const LEGACY_KEYS = ['vibestory.state.v1'];

export function loadState(): State {
  try {
    LEGACY_KEYS.forEach((k) => localStorage.removeItem(k));
    const raw = localStorage.getItem(KEY);
    if (!raw) return initialState();
    const parsed = JSON.parse(raw) as State;
    const state = { ...initialState(), ...parsed };
    return {
      ...state,
      settings: { ...state.settings, token: '' },
      shots: state.shots.map((shot) => {
        const video = { ...shot.video };
        // 保留现有作品与来源，仅清理旧版已移除的状态字段。
        delete (video as typeof video & { stale?: boolean }).stale;
        return { ...shot, video };
      }),
    };
  } catch {
    return initialState();
  }
}

export function saveState(state: State): void {
  try {
    localStorage.setItem(KEY, JSON.stringify({ ...state, settings: { ...state.settings, token: '' } }));
  } catch {
    // 本地存储不可用时静默降级，演示不受影响
  }
}

export function resetState(): State {
  localStorage.removeItem(KEY);
  return initialState();
}
