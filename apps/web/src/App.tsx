import { Navigate, Route, Routes } from 'react-router-dom';
import { useStore } from '@/lib/store';
import { LoginPage } from '@/pages/LoginPage';
import { HomeLayout } from '@/pages/HomeLayout';
import { IndexPage } from '@/pages/IndexPage';
import { AssetsPage } from '@/pages/AssetsPage';
import { SettingsPage } from '@/pages/SettingsPage';
import { ProjectLayout } from '@/pages/ProjectLayout';
import { ProjectOverviewPage } from '@/pages/ProjectOverviewPage';
import { EpisodesPage } from '@/pages/EpisodesPage';
import { EpisodeWorkspacePage } from '@/pages/EpisodeWorkspacePage';
import { AssetLibraryPage } from '@/pages/AssetLibraryPage';
import { AgentWorkbench } from '@/features/AgentWorkbench';
import { CreativeRecipesPage } from '@/features/CreativeRecipes';
import { TeamsPage } from '@/pages/TeamsPage';

export function App() {
  const { user } = useStore();

  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />

      <Route element={user ? <AgentWorkbench key={user.id} /> : <Navigate to="/login" replace />}>
        <Route path="/" element={<HomeLayout />}>
          <Route index element={<IndexPage />} />
          <Route path="assets" element={<AssetsPage />} />
          <Route path="teams/:teamId?" element={<TeamsPage />} />
          <Route path="settings" element={<SettingsPage />} />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Route>

        <Route path="/projects/:id" element={<ProjectLayout />}>
          <Route index element={<ProjectOverviewPage />} />
          <Route path="episodes" element={<EpisodesPage />} />
          <Route path="episodes/:episodeId/:tab?" element={<EpisodeWorkspacePage />} />
          <Route path="characters" element={<AssetLibraryPage kind="character" />} />
          <Route path="scenes" element={<AssetLibraryPage kind="scene" />} />
          <Route path="recipes" element={<CreativeRecipesPage />} />
          <Route path="*" element={<Navigate to="" replace />} />
        </Route>
      </Route>

      {/* 旧入口兼容：/projects 曾是首页，/index 是上一版工作台路径 */}
      <Route path="/projects" element={<Navigate to="/" replace />} />
      <Route path="/index/*" element={<Navigate to="/" replace />} />
      <Route path="*" element={<Navigate to={user ? '/' : '/login'} replace />} />
    </Routes>
  );
}
