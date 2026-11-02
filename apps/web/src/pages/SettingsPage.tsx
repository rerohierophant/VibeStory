import { Badge } from '@/components/Badge';
import { Button } from '@/components/Button';
import { useStore } from '@/lib/store';

export function SettingsPage() {
  const { mode, runtime, user, logout } = useStore();
  const models = [
    { label: '文本拆解', provider: runtime?.text?.provider, model: runtime?.text?.model },
    { label: '图片生成', provider: runtime?.image?.provider, model: runtime?.image?.model },
    { label: '视频生成', provider: runtime?.video?.provider, model: runtime?.video?.model },
  ];
  return <div className="flex-1 overflow-y-auto scroll-thin px-10 py-8"><div className="max-w-2xl">
    <h1 className="text-2xl font-semibold">设置</h1>
    <p className="mt-2 text-sm leading-6 text-muted">查看工作台当前使用的模型。模型与凭证由本机后端配置。</p>
    <section className="mt-8 rounded-panel border border-stroke/20 bg-panel/50 p-6">
      <div className="flex items-center justify-between"><h2 className="text-sm font-medium">当前生成能力</h2><Badge tone="muted">{mode === 'api' ? '后端实际配置' : '离线演示'}</Badge></div>
      <div className="mt-5 space-y-4">{models.map(model => <div key={model.label} className="rounded-card border border-stroke/20 p-4">
        <p className="text-xs text-muted">{model.label}</p><p className="mt-2 text-sm">{mode === 'demo' ? 'Mock' : model.model ?? '读取中'}</p>
        <p className="mt-1 text-xs text-muted">{mode === 'demo' ? '模拟生成，不调用供应商' : model.provider === 'mock' ? 'Mock · 不产生供应商费用' : model.provider === 'openai-compatible' ? 'DeepSeek' : 'MiniMax'}</p>
      </div>)}</div>
      {mode === 'api' && <p className="mt-4 text-xs leading-6 text-muted">视频输出：{runtime?.video?.resolution} · {(runtime?.video?.durationMs ?? 0) / 1000} 秒。镜头时长用于计划，实际出片长度按当前视频模型规格。</p>}
      <p className="mt-4 text-xs leading-6 text-muted">凭证不保存在浏览器，也不会通过接口返回。当前仅接入 DeepSeek 与 MiniMax，另保留 Mock 用于开发验证。</p>
    </section>
    <section className="mt-6 rounded-panel border border-stroke/20 bg-panel/50 p-6"><h2 className="text-sm font-medium">账号</h2>
      <p className="mt-3 text-xs leading-6 text-muted">当前登录：{user?.email}<br />{mode === 'api' ? '项目、资产、来源和任务状态保存在后端，刷新后可恢复。' : '离线演示数据仅保存在当前浏览器，不会写入后端。'}</p>
      <Button className="mt-4" variant="outline" onClick={logout}>退出登录</Button>
    </section>
  </div></div>;
}
