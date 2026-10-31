import { useEffect, useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { useStore } from '@/lib/store';
import { Button } from '@/components/Button';
import { Logo } from '@/components/Logo';

export function LoginPage() {
  const { login, user } = useStore();
  const navigate = useNavigate();
  const [email, setEmail] = useState('creator@vibestory.dev');
  const [password, setPassword] = useState('demo1234');
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    if (user) navigate('/', { replace: true });
  }, [user, navigate]);

  const submit = (e: FormEvent) => {
    e.preventDefault();
    if (!email.includes('@')) {
      setError('请输入有效邮箱');
      return;
    }
    if (password.length < 6) {
      setError('密码至少 6 位');
      return;
    }
    setError('');
    setLoading(true);
    window.setTimeout(() => {
      login(email.trim());
      navigate('/', { replace: true });
    }, 350);
  };

  return (
    <div className="grid min-h-full grid-cols-1 lg:grid-cols-[1fr_519px]">
      <section className="relative hidden overflow-hidden lg:block">
        <div className="absolute inset-0 bg-[radial-gradient(circle_at_28%_32%,rgba(123,87,224,0.35),transparent_55%),radial-gradient(circle_at_70%_75%,rgba(217,70,239,0.22),transparent_55%)]" />
        <div
          className="absolute inset-0 opacity-[0.07]"
          style={{
            backgroundImage:
              'linear-gradient(rgba(255,255,255,.6) 1px, transparent 1px), linear-gradient(90deg, rgba(255,255,255,.6) 1px, transparent 1px)',
            backgroundSize: '64px 64px',
          }}
        />
        <div className="relative flex h-full flex-col justify-between p-14">
          <Logo size={40} wordmarkClassName="text-xl tracking-[0.18em]" />

          <div className="max-w-lg">
            <h1 className="text-[42px] leading-[1.15] tracking-wide">
              从一段文本，
              <br />
              到一个可审的分镜。
            </h1>
            <p className="mt-6 text-[15px] leading-7 text-muted">
              上传故事，AI 拆解镜头，产出分镜图与视频片段。
              每一镜都是独立事务：改一镜，不动其余。
            </p>
            <div className="mt-10 flex flex-wrap gap-2 text-xs text-muted">
              {['文本拆解', '资产版本', '分镜图', '图生视频', '陈旧标记'].map((tag) => (
                <span key={tag} className="rounded-full border border-white/10 px-3 py-1">
                  {tag}
                </span>
              ))}
            </div>
          </div>

          <p className="text-xs text-muted/60">原型演示环境 · 数据保存在本机浏览器</p>
        </div>
      </section>

      <section className="flex items-center justify-center border-l border-stroke/15 bg-panel/60 px-8 py-12">
        <form onSubmit={submit} className="w-full max-w-[380px]">
          <div className="mb-10 lg:hidden">
            <Logo size={36} className="mb-4" wordmarkClassName="text-lg tracking-[0.18em]" />
          </div>

          <h2 className="text-2xl font-semibold">登录工作台</h2>
          <p className="mt-2 text-sm text-muted">使用创作者账号登录，所有项目只对你自己可见。</p>

          <label className="mt-8 block text-xs text-muted">
            邮箱
            <input
              className="field mt-2"
              type="email"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              autoComplete="username"
            />
          </label>

          <label className="mt-4 block text-xs text-muted">
            密码
            <input
              className="field mt-2"
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              autoComplete="current-password"
            />
          </label>

          <p className="mt-3 h-5 text-xs text-error">{error}</p>

          <Button type="submit" size="lg" className="mt-3 w-full" disabled={loading}>
            {loading ? '登录中…' : '进入工作台'}
          </Button>

          <div className="mt-6 flex items-center justify-between text-xs text-muted">
            <span>演示账号已预填</span>
            <button type="button" className="hover:text-fg">
              忘记密码？
            </button>
          </div>
        </form>
      </section>
    </div>
  );
}
