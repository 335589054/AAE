import { useEffect, useState, type JSX } from 'react';
import { StoreProvider, useStore } from './state';
import { PrereqPanel } from './panels/PrereqPanel';
import { ProjectPanel } from './panels/ProjectPanel';
import { PacksPanel } from './panels/PacksPanel';
import { SongsPanel } from './panels/SongsPanel';
import { BuildPanel } from './panels/BuildPanel';
import { InstallPanel } from './panels/InstallPanel';
import { CachePanel } from './panels/CachePanel';
import { Badge, Button, ProgressBar, cx } from './components/ui';
import { formatBytes } from './lib';

type PageId = 'prereq' | 'project' | 'packs' | 'songs' | 'build' | 'install' | 'cache';

const PAGES: Array<{ id: PageId; label: string; icon: string; group: string }> = [
  { id: 'prereq', label: '前置资源', icon: '⚙', group: '准备' },
  { id: 'project', label: '工程', icon: '📦', group: '准备' },
  { id: 'packs', label: '曲包', icon: '🗂', group: '编辑' },
  { id: 'songs', label: '歌曲与谱面', icon: '🎵', group: '编辑' },
  { id: 'build', label: '打包与签名', icon: '🔏', group: '输出' },
  { id: 'install', label: '无线安装', icon: '📱', group: '输出' },
  { id: 'cache', label: '缓存与清理', icon: '🧹', group: '输出' },
];

export default function App(): JSX.Element {
  return (
    <StoreProvider>
      <Shell />
    </StoreProvider>
  );
}

function Shell(): JSX.Element {
  const { snapshot, busy, progress, toasts, dismiss, refreshPrereqs, pickAndOpenApk } = useStore();
  const [page, setPage] = useState<PageId>('prereq');

  useEffect(() => {
    void refreshPrereqs();
  }, [refreshPrereqs]);

  // 导入 APK 后自动跳到工程页，方便确认解析结果
  useEffect(() => {
    if (snapshot && page === 'prereq') setPage('project');
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [snapshot?.apk.path]);

  const groups = [...new Set(PAGES.map((p) => p.group))];
  const pending = snapshot?.pending.total ?? 0;

  return (
    <div className="flex h-full flex-col bg-[#070b16]">
      <header className="flex h-11 shrink-0 items-center gap-3 border-b border-slate-800 bg-slate-950/80 px-3">
        <div className="flex items-center gap-2">
          <span className="text-[15px] font-semibold tracking-wide text-slate-100">
            Arcaea Apk Manager
          </span>
          <Badge tone="violet">不内置任何游戏资源</Badge>
        </div>
        <div className="flex-1" />
        {snapshot ? (
          <div className="flex items-center gap-2">
            <span className="max-w-[280px] truncate font-mono text-[12px] text-slate-400">
              {snapshot.apk.fileName} · {formatBytes(snapshot.apk.size)}
            </span>
            <Badge tone={pending > 0 ? 'amber' : 'emerald'}>
              {pending > 0 ? `${pending} 项待导出` : '无未导出改动'}
            </Badge>
          </div>
        ) : (
          <span className="text-[12px] text-slate-500">尚未导入 APK</span>
        )}
        <Button size="sm" variant="primary" disabled={Boolean(busy)} onClick={() => void pickAndOpenApk()}>
          {snapshot ? '重新导入 APK' : '导入 APK'}
        </Button>
      </header>

      <div className="flex min-h-0 flex-1">
        <nav className="flex w-[200px] shrink-0 flex-col gap-3 border-r border-slate-800 bg-slate-950/50 p-2.5">
          {groups.map((group) => (
            <div key={group}>
              <div className="mb-1 px-2 text-[10.5px] font-semibold uppercase tracking-wider text-slate-600">
                {group}
              </div>
              <div className="flex flex-col gap-0.5">
                {PAGES.filter((item) => item.group === group).map((item) => (
                  <button
                    key={item.id}
                    type="button"
                    onClick={() => setPage(item.id)}
                    className={cx(
                      'flex items-center gap-2 rounded-md px-2 py-1.5 text-left text-[12.5px] transition-colors',
                      page === item.id
                        ? 'bg-violet-600/90 text-white'
                        : 'text-slate-300 hover:bg-slate-800/70',
                    )}
                  >
                    <span className="w-4 text-center text-[13px]">{item.icon}</span>
                    <span className="flex-1">{item.label}</span>
                    {item.id === 'project' && (snapshot?.warnings.length ?? 0) > 0 && (
                      <span className="rounded bg-amber-500/90 px-1 text-[10px] font-semibold text-black">
                        {snapshot?.warnings.length}
                      </span>
                    )}
                  </button>
                ))}
              </div>
            </div>
          ))}
        </nav>

        <main className="flex min-h-0 min-w-0 flex-1 flex-col">
          {busy && (
            <div className="flex shrink-0 flex-col gap-1 border-b border-slate-800 bg-slate-900/70 px-3 py-2">
              <div className="flex items-center gap-2 text-[12px] text-slate-300">
                <span className="inline-block h-3 w-3 animate-spin rounded-full border-2 border-violet-400 border-t-transparent" />
                <span>{busy}</span>
                {progress?.message && (
                  <span className="text-slate-500">· {progress.message}</span>
                )}
                {progress?.percent !== undefined && (
                  <span className="ml-auto font-mono text-[11px] text-slate-400">
                    {progress.percent.toFixed(0)}%
                  </span>
                )}
              </div>
              <ProgressBar percent={progress?.percent ?? 0} />
            </div>
          )}

          <div
            className={cx(
              'min-h-0 flex-1 p-3',
              page === 'songs' ? 'flex overflow-hidden' : 'overflow-auto',
            )}
          >
            {page === 'prereq' && <PrereqPanel />}
            {page === 'project' && <ProjectPanel />}
            {page === 'packs' && <PacksPanel />}
            {page === 'songs' && <SongsPanel />}
            {page === 'build' && <BuildPanel onNavigate={() => setPage('install')} />}
            {page === 'install' && <InstallPanel />}
            {page === 'cache' && <CachePanel />}
          </div>
        </main>
      </div>

      <div className="pointer-events-none fixed bottom-4 right-4 z-50 flex w-[380px] flex-col gap-2">
        {toasts.map((toast) => (
          <div
            key={toast.id}
            className={cx(
              'pointer-events-auto rounded-lg border px-3 py-2 text-[12px] leading-relaxed shadow-lg backdrop-blur',
              toast.kind === 'error'
                ? 'border-rose-800 bg-rose-950/90 text-rose-100'
                : toast.kind === 'success'
                  ? 'border-emerald-800 bg-emerald-950/90 text-emerald-100'
                  : 'border-slate-700 bg-slate-900/95 text-slate-200',
            )}
          >
            <div className="flex items-start gap-2">
              <span className="whitespace-pre-wrap break-words">{toast.text}</span>
              <button
                type="button"
                className="ml-auto shrink-0 text-slate-400 hover:text-slate-200"
                onClick={() => dismiss(toast.id)}
              >
                ✕
              </button>
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}
