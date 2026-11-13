import { useRef, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Navigate, useParams } from 'react-router-dom';
import { Button } from '@/components/Button';
import { api, type Schema } from '@/lib/api';
import { useProject, useStore } from '@/lib/store';

const path = '/api/image-experience/recipes';
export const creativeRecipesKey = (userId: string) => ['creative-recipes', userId];
const kinds = { CHARACTER: '角色', SCENE: '场景', SHOT: '分镜' };
type Recipe = Schema['ImageRecipe'];
type Editor = { recipe: Recipe; originId?: string; template: string };

export function useCreativeRecipes(userId?: string) {
  return useQuery({
    queryKey: creativeRecipesKey(userId ?? ''),
    enabled: !!userId,
    queryFn: ({ signal }) => api<Recipe[]>(path, 'GET', undefined, signal),
  });
}

export function CreativeRecipesPage() {
  const { id = '' } = useParams();
  const project = useProject(id);
  const { mode, user } = useStore();
  if (mode !== 'api' || !user?.id || !project || project.teamId) return <Navigate to=".." replace />;

  return <section aria-labelledby="creative-recipes-title" className="@container/recipes flex min-h-0 flex-1 flex-col">
    <header className="shrink-0 border-b border-stroke/15 px-4 py-6 @min-[48rem]/recipes:px-8">
      <h1 id="creative-recipes-title" className="text-lg font-medium">创作配方</h1>
      <p className="mt-1 text-xs leading-5 text-muted">在这里管理个人配方，可在不同个人项目的「再生成一版」中选用。</p>
    </header>
    <div className="flex-1 overflow-y-auto scroll-thin p-4 @min-[48rem]/recipes:p-8">
      <CreativeRecipes userId={user.id} />
    </div>
  </section>;
}

export function CreativeRecipes({ userId }: { userId: string }) {
  const cache = useQueryClient();
  const key = creativeRecipesKey(userId);
  const [editor, setEditor] = useState<Editor>();
  const [notice, setNotice] = useState('');
  const request = useRef<{ input: string; key: string }>(undefined);
  const recipes = useCreativeRecipes(userId);
  const save = useMutation({
    mutationFn: ({ recipe, originId, template }: Editor) => {
      const body = { recipeId: recipe.id, targetType: recipe.targetType, name: recipe.name,
        expectedVersion: recipe.version, originId, template: template.trim() };
      const input = JSON.stringify(body);
      if (request.current?.input !== input) request.current = { input, key: crypto.randomUUID() };
      return api<Recipe>(path, 'POST', { ...body, requestKey: request.current.key });
    },
    onSuccess: async recipe => {
      setEditor(undefined);
      setNotice(`已保存「${recipe.name}」v${recipe.version}`);
      await Promise.all([
        cache.invalidateQueries({ queryKey: key }),
        cache.invalidateQueries({ queryKey: ['image-experience', userId] }),
      ]);
    },
  });

  return <div className="max-w-3xl space-y-4">
    {recipes.isPending && <p role="status" className="text-sm text-muted">正在读取配方…</p>}
    {(recipes.error || save.error) && <p role="alert" className="text-sm text-error-soft">{(recipes.error ?? save.error)?.message}</p>}
    {notice && <p role="status" className="text-sm text-fg">{notice}</p>}
    {recipes.data?.length === 0 && <div className="space-y-2 rounded-card border border-stroke/20 p-4 text-sm">
      <p>还没有创作配方</p>
      <p className="text-xs leading-5 text-muted">例如，先对 Agent 说「满意第1镜」，再说「保存第1镜为配方《人物柔光》」。</p>
    </div>}
    {recipes.data?.map(recipe => (
      <details key={recipe.id} className="space-y-3 rounded-card border border-stroke/20 p-3">
        <summary className="cursor-pointer text-sm">
          {recipe.name} <span className="ml-1 text-xs text-muted">{recipe.targetType && kinds[recipe.targetType]} · v{recipe.version}</span>
        </summary>
        {recipe.versions?.map(version => <section key={version.id} className="space-y-2 border-t border-stroke/20 pt-3">
          <p className="text-xs text-muted">v{version.version}{version.version === recipe.version ? ' · 最新' : ''}</p>
          <p className="whitespace-pre-wrap break-words text-sm">{version.template}</p>
          {!!version.slots?.length && <p className="text-xs text-muted">参数：{version.slots.join('、')}</p>}
          {version.version === recipe.version && editor?.recipe.id !== recipe.id && <Button size="sm" variant="ghost"
            disabled={save.isPending} onClick={() => {
              save.reset();
              setNotice('');
              setEditor({ recipe, originId: version.originId, template: version.template ?? '' });
            }}>编辑为新版本</Button>}
        </section>)}
        {editor && editor.recipe.id === recipe.id && <form className="space-y-2" onSubmit={event => {
          event.preventDefault();
          if (!save.isPending && editor.template.trim() && editor.template.trim() !== recipe.versions?.[0]?.template)
            save.mutate(editor);
        }}>
          <label className="block space-y-1 text-xs text-muted">配方要求
            <textarea rows={4} maxLength={1000} value={editor.template} disabled={save.isPending}
              className="w-full rounded-card border border-stroke/30 bg-panel-2/40 px-3 py-2 text-sm text-fg"
              onChange={event => setEditor({ ...editor, template: event.target.value })} />
          </label>
          <div className="flex gap-2">
            <Button size="sm" type="submit" disabled={save.isPending || !editor.template.trim()
              || editor.template.trim() === recipe.versions?.[0]?.template}>保存配方版本</Button>
            <Button size="sm" type="button" variant="ghost" disabled={save.isPending} onClick={() => setEditor(undefined)}>取消</Button>
          </div>
        </form>}
      </details>
    ))}
  </div>;
}
