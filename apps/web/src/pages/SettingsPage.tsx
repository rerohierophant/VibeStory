import { useState } from 'react';
import { Badge } from '@/components/Badge';
import { Button } from '@/components/Button';
import { useStore } from '@/lib/store';
import type { ProviderKind, ProviderSettings } from '@/lib/types';

const providers: { value: ProviderKind; label: string; hint: string }[] = [
  { value: 'mock', label: 'Mock', hint: '本地模拟生成，不需要网络与凭证，默认选项。' },
  { value: 'openai', label: 'OpenAI', hint: '使用 OpenAI 官方接口。' },
  { value: 'compatible', label: '兼容接口', hint: '任何 OpenAI 兼容的自建或第三方服务。' },
];

export function SettingsPage() {
  const { settings, saveSettings, user } = useStore();
  const [draft, setDraft] = useState<ProviderSettings>(settings);
  const [saved, setSaved] = useState(false);
  const [error, setError] = useState('');

  const patch = (next: Partial<ProviderSettings>) => {
    setDraft((d) => ({ ...d, ...next }));
    setSaved(false);
    setError('');
  };

  const needsEndpoint = draft.provider !== 'mock';

  const submit = () => {
    if (needsEndpoint) {
      if (!draft.baseUrl.trim()) {
        setError('请填写接口地址');
        return;
      }
      if (!draft.token.trim()) {
        setError('请填写访问 Token');
        return;
      }
    }
    if (!draft.imageModel.trim() || !draft.videoModel.trim()) {
      setError('图片与视频模型名称不能为空');
      return;
    }
    saveSettings(draft);
    setSaved(true);
  };

  return (
    <div className="flex-1 overflow-y-auto scroll-thin px-10 py-8">
      <div className="max-w-2xl">
        <h1 className="text-2xl font-semibold">设置</h1>
        <p className="mt-1 text-sm text-muted">配置生成能力使用的模型提供方。原型阶段只保存配置，不发起真实调用。</p>

        <section className="mt-8 rounded-panel border border-stroke/20 bg-panel/50 p-6">
          <div className="flex items-center justify-between">
            <h2 className="text-sm font-medium">模型提供方</h2>
            <Badge tone="muted">保存在本机浏览器</Badge>
          </div>

          <div className="mt-4 grid grid-cols-3 gap-2">
            {providers.map((p) => {
              const active = p.value === draft.provider;
              return (
                <button
                  key={p.value}
                  type="button"
                  onClick={() => patch({ provider: p.value })}
                  className={`rounded-card border px-3 py-3 text-left transition ${
                    active ? 'border-accent bg-accent/10' : 'border-stroke/25 hover:border-stroke/50'
                  }`}
                >
                  <span className={`block text-sm ${active ? 'text-fg' : 'text-fg/85'}`}>{p.label}</span>
                  <span className="mt-1 block text-[11px] leading-4 text-muted">{p.hint}</span>
                </button>
              );
            })}
          </div>

          <label className="mt-5 block text-xs text-muted">
            接口地址
            <input
              className="field mt-2 disabled:opacity-40"
              placeholder={needsEndpoint ? 'https://api.example.com/v1' : 'Mock 模式无需填写'}
              value={draft.baseUrl}
              disabled={!needsEndpoint}
              onChange={(e) => patch({ baseUrl: e.target.value })}
              autoComplete="off"
              spellCheck={false}
            />
          </label>

          <label className="mt-4 block text-xs text-muted">
            访问 Token
            <input
              className="field mt-2 disabled:opacity-40"
              type="password"
              placeholder={needsEndpoint ? '粘贴提供方给你的 API Key' : 'Mock 模式无需填写'}
              value={draft.token}
              disabled={!needsEndpoint}
              onChange={(e) => patch({ token: e.target.value })}
              autoComplete="off"
            />
            <span className="mt-1.5 block text-[11px] leading-5 text-muted/70">
              Token 只存在当前浏览器的本地存储里，不会随项目数据上传。请不要在这里填写正式环境凭证。
            </span>
          </label>

          <div className="mt-4 grid gap-4 sm:grid-cols-2">
            <label className="block text-xs text-muted">
              图片模型
              <input
                className="field mt-2"
                value={draft.imageModel}
                onChange={(e) => patch({ imageModel: e.target.value })}
                spellCheck={false}
              />
            </label>
            <label className="block text-xs text-muted">
              视频模型
              <input
                className="field mt-2"
                value={draft.videoModel}
                onChange={(e) => patch({ videoModel: e.target.value })}
                spellCheck={false}
              />
            </label>
          </div>

          <div className="mt-6 flex items-center justify-between gap-4 border-t border-stroke/15 pt-5">
            <p className="text-[11px] leading-5 text-muted/70">
              {saved ? '已保存。' : error ? <span className="text-error">{error}</span> : '修改后点保存生效。'}
            </p>
            <Button onClick={submit}>保存设置</Button>
          </div>
        </section>

        <section className="mt-6 rounded-panel border border-stroke/20 bg-panel/50 p-6">
          <h2 className="text-sm font-medium">账号</h2>
          <p className="mt-2 text-xs leading-6 text-muted">
            当前登录：{user?.email}
            <br />
            原型环境没有真实后端，项目、资产与设置都保存在这台机器的浏览器里，换设备不会同步。
          </p>
        </section>
      </div>
    </div>
  );
}
