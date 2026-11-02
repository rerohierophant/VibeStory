import {
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from 'react';
import { buildDemoShots, defaultSettings, demoCharacters, demoScenes } from './mock';
import { loadState, saveState } from './storage';
import { uid } from './id';
import { splitIntoShots } from './splitter';
import { captureImageInputSnapshot } from './imageSource';
import type {
  Character,
  Episode,
  Project,
  ProviderSettings,
  SceneAsset,
  Shot,
  ShotImage,
  State,
} from './types';

import { StoreContext, type StoreValue, type NewProjectInput } from './storeContext';

function nowIso(): number {
  return Date.now();
}

function findEpisode(projects: Project[], episodeId: string): Episode | undefined {
  for (const p of projects) {
    const hit = p.episodes.find((e) => e.id === episodeId);
    if (hit) return hit;
  }
  return undefined;
}

function mapEpisode(
  projects: Project[],
  episodeId: string,
  fn: (e: Episode) => Episode,
): Project[] {
  return projects.map((p) =>
    p.episodes.some((e) => e.id === episodeId)
      ? { ...p, episodes: p.episodes.map((e) => (e.id === episodeId ? fn(e) : e)), updatedAt: nowIso() }
      : p,
  );
}

export function LocalStoreProvider({ children }: { children: ReactNode }) {
  const [state, setState] = useState<State>(loadState);
  const timers = useRef<number[]>([]);

  useEffect(() => {
    saveState(state);
  }, [state]);

  useEffect(() => {
    return () => {
      timers.current.forEach((t) => window.clearTimeout(t));
    };
  }, []);

  const later = useCallback((fn: () => void, ms: number) => {
    const id = window.setTimeout(fn, ms);
    timers.current.push(id);
  }, []);

  const login = useCallback((email: string) => {
    setState((s) => {
      const next = { ...s, user: { email } };
      // 登录后经常伴随整页跳转，effect 里的保存可能来不及执行，这里同步落盘
      saveState(next);
      return next;
    });
  }, []);

  const logout = useCallback(() => {
    setState((s) => {
      const next = { ...s, user: null };
      saveState(next);
      return next;
    });
  }, []);

  const createProject = useCallback((input: NewProjectInput) => {
    const id = uid('p');
    setState((s) => ({
      ...s,
      projects: [
        {
          id,
          name: input.name.trim() || '未命名项目',
          visualType: input.visualType,
          style: input.style.trim(),
          createdAt: nowIso(),
          updatedAt: nowIso(),
          episodes: [],
        },
        ...s.projects,
      ],
    }));
    return id;
  }, []);

  const updateProject = useCallback(
    (id: string, patch: Partial<Pick<Project, 'name' | 'style' | 'visualType'>>) => {
      setState((s) => ({
        ...s,
        projects: s.projects.map((p) => (p.id === id ? { ...p, ...patch, updatedAt: nowIso() } : p)),
      }));
    },
    [],
  );

  const renameProject = useCallback((id: string, name: string) => {
    setState((s) => ({
      ...s,
      projects: s.projects.map((p) =>
        p.id === id ? { ...p, name: name.trim() || p.name, updatedAt: nowIso() } : p,
      ),
    }));
  }, []);

  const deleteProject = useCallback((id: string) => {
    setState((s) => ({
      ...s,
      projects: s.projects.filter((p) => p.id !== id),
      shots: s.shots.filter((x) => x.projectId !== id),
      characters: s.characters.filter((c) => c.projectId !== id),
      scenes: s.scenes.filter((c) => c.projectId !== id),
    }));
  }, []);

  const createEpisode = useCallback((projectId: string) => {
    const id = uid('ep');
    setState((s) => ({
      ...s,
      projects: s.projects.map((p) => {
        if (p.id !== projectId) return p;
        const index = p.episodes.reduce((max, e) => Math.max(max, e.index), 0) + 1;
        const episode: Episode = {
          id,
          index,
          title: `第 ${index} 集`,
          summary: '',
          story: '',
          breakdown: 'idle',
          createdAt: nowIso(),
          updatedAt: nowIso(),
        };
        return { ...p, episodes: [...p.episodes, episode], updatedAt: nowIso() };
      }),
    }));
    return id;
  }, []);

  const updateEpisode = useCallback(
    (episodeId: string, patch: Partial<Pick<Episode, 'title' | 'summary'>>) => {
      setState((s) => ({ ...s, projects: mapEpisode(s.projects, episodeId, (e) => ({ ...e, ...patch })) }));
    },
    [],
  );

  const setEpisodeStory = useCallback((episodeId: string, text: string) => {
    setState((s) => ({
      ...s,
      projects: mapEpisode(s.projects, episodeId, (e) => ({
        ...e,
        story: text,
        // 改正文不会自动删镜头，但拆解结果标记为待重跑
        breakdown: e.breakdown === 'done' ? 'idle' : e.breakdown,
      })),
    }));
  }, []);

  const runBreakdown = useCallback(
    (episodeId: string) => {
      setState((s) => {
        const ep = findEpisode(s.projects, episodeId);
        if (!ep || ep.breakdown === 'running') return s;
        return { ...s, projects: mapEpisode(s.projects, episodeId, (e) => ({ ...e, breakdown: 'running' })) };
      });

      later(() => {
        setState((s) => {
          const ep = findEpisode(s.projects, episodeId);
          if (!ep) return s;
          const project = s.projects.find((p) => p.episodes.some((e) => e.id === episodeId));
          if (!project) return s;

          // 场景名优先从项目场景库里认，没建库时拆解器用兜底词表
          const projectScenes = s.scenes.filter((c) => c.projectId === project.id);
          const drafts = splitIntoShots(
            ep.story,
            projectScenes.length > 0 ? projectScenes.map((c) => ({ name: c.name })) : undefined,
          );

          const generated: Shot[] = drafts.map((d, index) => ({
            ...d,
            id: uid('shot'),
            projectId: project.id,
            episodeId,
            seq: index + 1,
            status: 'draft',
            characterNames: [],
            images: [],
            imageState: 'idle',
            video: { ready: false, state: 'idle', attempts: 0 },
          }));

          return {
            ...s,
            shots: [...s.shots.filter((x) => x.episodeId !== episodeId), ...generated],
            projects: mapEpisode(s.projects, episodeId, (e) => ({ ...e, breakdown: 'done' })),
            characters: s.characters.some((c) => c.projectId === project.id)
              ? s.characters
              : [
                  ...s.characters,
                  ...demoCharacters.slice(0, 2).map((c) => ({ ...c, id: uid('c'), projectId: project.id })),
                ],
            scenes: s.scenes.some((c) => c.projectId === project.id)
              ? s.scenes
              : [
                  ...s.scenes,
                  ...demoScenes.slice(0, 2).map((c) => ({ ...c, id: uid('s'), projectId: project.id })),
                ],
          };
        });
      }, 1500);
    },
    [later],
  );

  const updateShot = useCallback((id: string, patch: Partial<Shot>) => {
    setState((s) => ({
      ...s,
      shots: s.shots.map((x) => (x.id === id ? { ...x, ...patch } : x)),
    }));
  }, []);

  const confirmShot = useCallback((id: string) => {
    setState((s) => ({
      ...s,
      shots: s.shots.map((x) => (x.id === id ? { ...x, status: 'confirmed' } : x)),
    }));
  }, []);

  const unconfirmShot = useCallback((id: string) => {
    setState((s) => ({
      ...s,
      shots: s.shots.map((x) => (x.id === id ? { ...x, status: 'draft' } : x)),
    }));
  }, []);

  const confirmAll = useCallback((episodeId: string) => {
    setState((s) => ({
      ...s,
      shots: s.shots.map((x) => (x.episodeId === episodeId ? { ...x, status: 'confirmed' } : x)),
    }));
  }, []);

  const generateImage = useCallback(
    (id: string) => {
      const shot = state.shots.find((x) => x.id === id);
      if (
        !shot || shot.status !== 'confirmed' ||
        shot.imageState === 'queued' || shot.imageState === 'running'
      ) return;
      const inputSnapshot = captureImageInputSnapshot(shot, state.characters, state.scenes);

      setState((s) => ({
        ...s,
        shots: s.shots.map((x) => (x.id === id ? { ...x, imageState: 'queued' } : x)),
      }));

      later(() => {
        setState((s) => ({
          ...s,
          shots: s.shots.map((x) => (x.id === id ? { ...x, imageState: 'running' } : x)),
        }));
      }, 500);

      later(() => {
        setState((s) => ({
          ...s,
          shots: s.shots.map((x) => {
            if (x.id !== id) return x;
            const candidate: ShotImage = {
              id: uid('img'),
              label: `候选 ${x.images.length + 1}`,
              seed: `${shot.sceneName}-${shot.seq}-${x.images.length + 1}`,
              createdAt: nowIso(),
              inputSnapshot,
            };
            const images = [...x.images, candidate];
            return {
              ...x,
              images,
              imageState: 'idle',
              selectedImageId: x.selectedImageId ?? candidate.id,
            };
          }),
        }));
      }, 2200);
    },
    [later, state.shots, state.characters, state.scenes],
  );

  const selectImage = useCallback((shotId: string, imageId: string) => {
    setState((s) => ({
      ...s,
      shots: s.shots.map((x) => (x.id === shotId ? { ...x, selectedImageId: imageId } : x)),
    }));
  }, []);

  const generateVideo = useCallback(
    (id: string) => {
      const shot = state.shots.find((x) => x.id === id);
      if (
        !shot || shot.status !== 'confirmed' || !shot.selectedImageId ||
        shot.video.state === 'queued' || shot.video.state === 'running'
      ) return;
      const sourceImageId = shot.selectedImageId;

      setState((s) => ({
        ...s,
        shots: s.shots.map((x) => {
          if (x.id !== id) return x;
          if (!x.selectedImageId) return x;
          return {
            ...x,
            video: { ...x.video, state: 'queued', error: undefined, attempts: x.video.attempts + 1 },
          };
        }),
      }));

      later(() => {
        setState((s) => ({
          ...s,
          shots: s.shots.map((x) => (x.id === id ? { ...x, video: { ...x.video, state: 'running' } } : x)),
        }));
      }, 600);

      later(() => {
        setState((s) => ({
          ...s,
          shots: s.shots.map((x) => {
            if (x.id !== id) return x;
            const fail = Math.random() < 0.18;
            return {
              ...x,
              video: fail
                ? {
                    ...x.video,
                    state: 'failed',
                    error: '供应商超时（60s），本次结果未知，请确认后再重试',
                  }
                : {
                    ...x.video,
                    state: 'idle',
                    ready: true,
                    basedOn: sourceImageId,
                    error: undefined,
                  },
            };
          }),
        }));
      }, 3000);
    },
    [later, state.shots],
  );

  const addAssetVersion = useCallback(
    (kind: 'character' | 'scene', ownerId: string, source: 'uploaded' | 'generated') => {
      setState((s) => {
        const patch = <T extends Character | SceneAsset>(owner: T): T => {
          const version = {
            id: uid('v'),
            label: `v${owner.versions.length + 1}`,
            seed: `${owner.name}-${owner.versions.length + 1}`,
            createdAt: nowIso(),
            source,
          };
          return {
            ...owner,
            versions: [...owner.versions, version],
            selectedVersionId: version.id,
          };
        };
        return kind === 'character'
          ? { ...s, characters: s.characters.map((c) => (c.id === ownerId ? patch(c) : c)) }
          : { ...s, scenes: s.scenes.map((c) => (c.id === ownerId ? patch(c) : c)) };
      });
    },
    [],
  );

  const selectAssetVersion = useCallback(
    (kind: 'character' | 'scene', ownerId: string, versionId: string) => {
      setState((s) =>
        kind === 'character'
          ? {
              ...s,
              characters: s.characters.map((c) =>
                c.id === ownerId ? { ...c, selectedVersionId: versionId } : c,
              ),
            }
          : {
              ...s,
              scenes: s.scenes.map((c) =>
                c.id === ownerId ? { ...c, selectedVersionId: versionId } : c,
              ),
            },
      );
    },
    [],
  );

  const saveSettings = useCallback((patch: Partial<ProviderSettings>) => {
    setState((s) => ({ ...s, settings: { ...s.settings, ...patch } }));
  }, []);

  const addCustomStyle = useCallback((input: { label: string; image: string }) => {
    const id = uid('st');
    setState((s) => ({
      ...s,
      customStyles: [
        ...s.customStyles,
        {
          id,
          category: 'custom' as const,
          label: input.label.trim() || '自定义风格',
          hint: '上传的参考图',
          image: input.image,
          custom: true,
        },
      ],
    }));
    return id;
  }, []);

  const removeCustomStyle = useCallback((id: string) => {
    setState((s) => ({ ...s, customStyles: s.customStyles.filter((p) => p.id !== id) }));
  }, []);

  const reset = useCallback(() => {
    setState({
      user: null,
      projects: [],
      shots: [],
      characters: [],
      scenes: [],
      customStyles: [],
      settings: defaultSettings,
    });
  }, []);

  const value = useMemo<StoreValue>(
    () => ({
      ...state,
      mode: 'demo',
      login,
      logout,
      createProject,
      updateProject,
      renameProject,
      deleteProject,
      createEpisode,
      updateEpisode,
      setEpisodeStory,
      runBreakdown,
      updateShot,
      confirmShot,
      confirmAll,
      unconfirmShot,
      generateImage,
      selectImage,
      generateVideo,
      addAssetVersion,
      selectAssetVersion,
      saveSettings,
      addCustomStyle,
      removeCustomStyle,
      reset,
    }),
    [
      state,
      login,
      logout,
      createProject,
      updateProject,
      renameProject,
      deleteProject,
      createEpisode,
      updateEpisode,
      setEpisodeStory,
      runBreakdown,
      updateShot,
      confirmShot,
      confirmAll,
      unconfirmShot,
      generateImage,
      selectImage,
      generateVideo,
      addAssetVersion,
      selectAssetVersion,
      saveSettings,
      addCustomStyle,
      removeCustomStyle,
      reset,
    ],
  );

  return <StoreContext.Provider value={value}>{children}</StoreContext.Provider>;
}

export function useStore(): StoreValue {
  const ctx = useContext(StoreContext);
  if (!ctx) throw new Error('useStore 必须在 StoreProvider 内使用');
  return ctx;
}

export function useProjects(): Project[] {
  const { projects } = useStore();
  return projects;
}

export function useProject(projectId: string): Project | undefined {
  const { projects } = useStore();
  return projects.find((p) => p.id === projectId);
}

export function useEpisodeShots(episodeId: string): Shot[] {
  const { shots } = useStore();
  return useMemo(
    () => shots.filter((s) => s.episodeId === episodeId).sort((a, b) => a.seq - b.seq),
    [shots, episodeId],
  );
}

export function useProjectShots(projectId: string): Shot[] {
  const { shots } = useStore();
  return useMemo(
    () => shots.filter((s) => s.projectId === projectId).sort((a, b) => a.seq - b.seq),
    [shots, projectId],
  );
}

export { buildDemoShots };
