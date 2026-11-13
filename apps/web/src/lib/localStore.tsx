import {
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from 'react';
import { buildDemoShots, defaultSettings, demoCharacters } from './mock';
import { loadState, saveState } from './storage';
import { uid } from './id';
import { splitIntoShots } from './splitter';
import { captureImageInputSnapshot } from './imageSource';
import { demoProjectStyleEditable } from './projectStyle';
import { resolveStyle } from './visualPresets';
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

import { StoreContext, type StoreValue, type NewProjectInput, type ProjectStyleInput, type VideoRegeneration } from './storeContext';

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
  const [state, setState] = useState<State>(() => {
    const saved = loadState();
    const restoreAsset = (asset: Character | SceneAsset): Character | SceneAsset =>
      asset.generationState === 'queued' || asset.generationState === 'running'
        ? { ...asset, generationState: 'idle' } : asset;
    return {
      ...saved,
      shots: saved.shots.map(shot => shot.images.some(image => image.id === shot.selectedImageId)
        ? shot : { ...shot, status: 'draft' }),
      characters: saved.characters.map(restoreAsset),
      scenes: saved.scenes.map(restoreAsset),
    };
  });
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

  const updateProjectStyle = useCallback(
    async (id: string, input: ProjectStyleInput) => {
      if (!demoProjectStyleEditable(id, state))
        throw new Error('项目已有生成图，或有正在处理的图片任务，暂时无法切换风格');
      setState((s) => ({
        ...s,
        projects: s.projects.map(p => p.id === id ? {
          ...p, ...input,
          visualPreset: input.visualType === p.visualType ? p.visualPreset : resolveStyle(input.visualType, s.customStyles),
          updatedAt: nowIso(),
        } : p),
      }));
    },
    [state],
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

  const deleteEpisode = useCallback((episodeId: string) => {
    setState((s) => ({
      ...s,
      projects: s.projects.map((p) => p.episodes.some((e) => e.id === episodeId)
        ? { ...p, episodes: p.episodes.filter((e) => e.id !== episodeId), updatedAt: nowIso() }
        : p),
      shots: s.shots.filter((shot) => shot.episodeId !== episodeId),
    }));
  }, []);

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
    (episodeId: string, replaceExisting: boolean) => {
      setState((s) => {
        const ep = findEpisode(s.projects, episodeId);
        if (!ep || ep.breakdown === 'running') return s;
        return { ...s, projects: mapEpisode(s.projects, episodeId, (e) => ({ ...e, breakdown: 'running' })) };
      });

      later(() => {
        setState((s) => {
          const ep = findEpisode(s.projects, episodeId);
          if (!ep || ep.breakdown !== 'running') return s;
          if (!replaceExisting && s.shots.some((shot) => shot.episodeId === episodeId)) {
            return { ...s, projects: mapEpisode(s.projects, episodeId, (e) => ({ ...e, breakdown: 'done' })) };
          }
          const project = s.projects.find((p) => p.episodes.some((e) => e.id === episodeId));
          if (!project) return s;

          // 场景名优先从项目场景库里认，没建库时拆解器用兜底词表
          const projectScenes = s.scenes.filter((c) => c.projectId === project.id);
          const drafts = splitIntoShots(
            ep.story,
            projectScenes.length > 0 ? projectScenes.map((c) => ({ name: c.name })) : undefined,
          );
          const newScenes: SceneAsset[] = [...new Set(drafts.map(draft => draft.sceneName))]
            .filter(name => !projectScenes.some(scene => scene.name === name))
            .map(name => ({ id: uid('s'), projectId: project.id, name, description: '', versions: [] }));

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
                  ...demoCharacters.slice(0, 2).map(({ name, description }) => ({
                    id: uid('c'), projectId: project.id, name, description, versions: [],
                  })),
                ],
            scenes: [...s.scenes, ...newScenes],
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

  const confirmShot = useCallback((id: string, imageId: string) => {
    setState((s) => ({
      ...s,
      shots: s.shots.map((x) => (x.id === id && x.images.some(image => image.id === imageId)
        ? { ...x, status: 'confirmed', selectedImageId: imageId } : x)),
    }));
  }, []);

  const unconfirmShot = useCallback((id: string) => {
    setState((s) => ({
      ...s,
      shots: s.shots.map((x) => (x.id === id ? { ...x, status: 'draft' } : x)),
    }));
  }, []);

  const deleteShotImage = useCallback((shotId: string, imageId: string) => {
    setState(s => ({
      ...s,
      shots: s.shots.map(shot => {
        if (shot.id !== shotId) return shot;
        const image = shot.images.find(candidate => candidate.id === imageId);
        if (!image) return shot;
        const images = shot.images.filter(candidate => candidate.id !== imageId);
        const updated = { ...shot, images, deletedImages: [...(shot.deletedImages ?? []), image] };
        return shot.selectedImageId === imageId
          ? { ...updated, selectedImageId: images.at(-1)?.id, status: 'draft' }
          : updated;
      }),
    }));
  }, []);

  const confirmAll = useCallback((episodeId: string, frames: Record<string, string>) => {
    setState((s) => ({
      ...s,
      shots: s.shots.map((x) => (x.episodeId === episodeId && x.selectedImageId && frames[x.id] === x.selectedImageId
        ? { ...x, status: 'confirmed' } : x)),
    }));
  }, []);

  const generateImage = useCallback(
    (id: string) => {
      const shot = state.shots.find((x) => x.id === id);
      if (
        !shot ||
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
            const version = x.images.length + (x.deletedImages?.length ?? 0) + 1;
            const candidate: ShotImage = {
              id: uid('img'),
              label: `候选 ${version}`,
              seed: `${shot.sceneName}-${shot.seq}-${version}`,
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

  const generateVideo = useCallback(
    (id: string, regeneration?: VideoRegeneration) => {
      const shot = state.shots.find((x) => x.id === id);
      if (
        !shot || shot.status !== 'confirmed' || !shot.selectedImageId ||
        shot.video.state === 'queued' || shot.video.state === 'running'
      ) return;
      const sourceImageId = shot.selectedImageId;
      if (regeneration && regeneration.expectedFirstFrameId !== sourceImageId)
        throw new Error('当前选中的首帧已变化，请重新打开生成窗口');

      setState((s) => ({
        ...s,
        shots: s.shots.map((x) => {
          if (x.id !== id) return x;
          if (!x.selectedImageId) return x;
          return {
            ...x,
            video: { ...x.video, state: 'queued', error: undefined, pendingBasedOn: sourceImageId, attempts: x.video.attempts + 1 },
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
            if (fail) return {
              ...x,
              video: {
                ...x.video,
                state: 'failed',
                error: '供应商超时（60s），本次结果未知，请确认后再重试',
              },
            };
            const { ready, basedOn, url, durationMs, prompt } = x.video;
            const previous = x.video.candidates ?? (ready ? [{ id: uid('video'), basedOn, url, durationMs, prompt }] : []);
            const candidate = {
              id: uid('video'),
              basedOn: sourceImageId,
              prompt: regeneration?.prompt ?? shot.description,
              createdAt: nowIso(),
            };
            return {
              ...x,
              video: {
                ...x.video,
                state: 'idle',
                ready: true,
                basedOn: candidate.basedOn,
                prompt: candidate.prompt,
                pendingBasedOn: undefined,
                error: undefined,
                candidates: [candidate, ...previous],
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
      function update(patch: (owner: Character | SceneAsset) => Character | SceneAsset) {
        setState(s => kind === 'character'
          ? { ...s, characters: s.characters.map(c => c.id === ownerId ? patch(c) : c) }
          : { ...s, scenes: s.scenes.map(c => c.id === ownerId ? patch(c) : c) });
      }
      function appendVersion(owner: Character | SceneAsset) {
        const version = {
          id: uid('v'),
          label: `v${owner.versions.length + 1}`,
          seed: `${owner.name}-${owner.versions.length + 1}`,
          createdAt: nowIso(),
          source,
        };
        return { ...owner, versions: [...owner.versions, version], selectedVersionId: version.id };
      }

      const owner = (kind === 'character' ? state.characters : state.scenes).find(asset => asset.id === ownerId);
      if (!owner) return;
      if (source === 'uploaded') {
        update(appendVersion);
        return;
      }
      if (owner.generationState === 'queued' || owner.generationState === 'running') return;
      if (owner.versions.length) {
        update(appendVersion);
        return;
      }
      update(asset => ({ ...asset, generationState: 'queued' }));
      later(() => update(asset => ({ ...asset, generationState: 'running' })), 500);
      later(() => update(asset => {
        const result = appendVersion(asset);
        return { ...result, selectedVersionId: asset.selectedVersionId ?? result.selectedVersionId, generationState: 'idle' };
      }), 2200);
    },
    [later, state.characters, state.scenes],
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
      updateProjectStyle,
      renameProject,
      deleteProject,
      createEpisode,
      updateEpisode,
      deleteEpisode,
      setEpisodeStory,
      runBreakdown,
      updateShot,
      confirmShot,
      confirmAll,
      unconfirmShot,
      deleteShotImage,
      generateImage,
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
      updateProjectStyle,
      renameProject,
      deleteProject,
      createEpisode,
      updateEpisode,
      deleteEpisode,
      setEpisodeStory,
      runBreakdown,
      updateShot,
      confirmShot,
      confirmAll,
      unconfirmShot,
      deleteShotImage,
      generateImage,
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
