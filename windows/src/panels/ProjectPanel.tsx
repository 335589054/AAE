import type { JSX } from 'react';
import { useStore } from '../state';
import { Badge, Button, Card, EmptyState } from '../components/ui';
import { formatBytes } from '../lib';

export function ProjectPanel(): JSX.Element {
  const { snapshot, pickAndOpenApk, closeProject, busy, notify } = useStore();

  if (!snapshot) {
    return (
      <EmptyState
        title="导入你的 Arcaea 安装包开始修改"
        description={
          <>
            <p>
              本工具<strong className="text-slate-300">不包含任何 Arcaea 游戏资源</strong>
              。请选择你自己设备上的 Arcaea APK（完整版或已修改的版本），工具会就地读取
              <span className="mx-1 font-mono text-slate-400">assets/songs</span>
              下的歌曲、曲包与谱面信息，所有修改都写回你导出的新 APK 中。
            </p>
            <p className="mt-2">
              导入只读取 APK 的目录结构与元数据，不会立即解压全部资源，因此大体积安装包也能快速打开。
            </p>
          </>
        }
        action={
          <Button variant="primary" className="mt-3" disabled={Boolean(busy)} onClick={() => void pickAndOpenApk()}>
            选择 APK 文件
          </Button>
        }
      />
    );
  }

  const { apk, pending, warnings, notes } = snapshot;

  return (
    <div className="flex flex-col gap-3">
      <Card
        title="当前工程"
        actions={
          <>
            <Button size="sm" onClick={() => void pickAndOpenApk()} disabled={Boolean(busy)}>
              重新导入
            </Button>
            <Button
              size="sm"
              variant="outline"
              onClick={() => void window.api.shell.openPath(apk.path)}
            >
              定位文件
            </Button>
            <Button size="sm" variant="ghost" onClick={() => void closeProject()}>
              关闭
            </Button>
          </>
        }
      >
        <div className="grid grid-cols-2 gap-x-6 gap-y-2 lg:grid-cols-3">
          <Info label="文件" value={apk.fileName} mono />
          <Info label="大小" value={formatBytes(apk.size)} />
          <Info label="zip 条目" value={String(apk.entryCount)} />
          <Info
            label={apk.packageName === apk.originalPackageName ? '包名' : '包名（导出后）'}
            value={
              apk.packageName === apk.originalPackageName
                ? apk.packageName
                : `${apk.packageName}（原 ${apk.originalPackageName}）`
            }
            mono
          />
          <Info label="版本" value={`${apk.versionName} (versionCode ${apk.versionCode})`} />
          <Info
            label="SDK"
            value={
              apk.minSdk !== undefined || apk.targetSdk !== undefined
                ? `min ${apk.minSdk ?? '?'} / target ${apk.targetSdk ?? '?'}`
                : '清单中未声明'
            }
          />
          <Info label="歌曲目录" value={`${apk.songDirs.length} 个`} />
          <Info label="曲包横幅" value={`${apk.packBanners.length} 个`} />
          <Info label="原生库" value={apk.hasNativeLibs ? '存在 lib/**.so（导出时会保持页对齐）' : '无'} />
        </div>
      </Card>

      <div className="grid gap-3 lg:grid-cols-2">
        <Card title={`待导出改动（${pending.total}）`}>
          <div className="mb-2 flex flex-wrap gap-2">
            <Badge tone={pending.jsonChanges > 0 ? 'violet' : 'slate'}>
              元数据改动 {pending.jsonChanges}
            </Badge>
            <Badge tone={pending.assetWrites > 0 ? 'sky' : 'slate'}>
              写入资源 {pending.assetWrites}
            </Badge>
            <Badge tone={pending.assetDeletes > 0 ? 'rose' : 'slate'}>
              删除资源 {pending.assetDeletes}
            </Badge>
            <Badge tone={pending.packageChanged ? 'amber' : 'slate'}>
              {pending.packageChanged ? '将修改包名' : '包名不变'}
            </Badge>
          </div>
          {pending.items.length === 0 ? (
            <p className="text-[12px] text-slate-500">当前没有任何未导出的改动。</p>
          ) : (
            <ul className="max-h-64 space-y-1 overflow-auto pr-1 font-mono text-[11px] leading-relaxed text-slate-400">
              {pending.items.map((item) => (
                <li key={item} className="truncate">
                  {item}
                </li>
              ))}
            </ul>
          )}
        </Card>

        <Card title={`资源检查：问题 ${warnings.length} · 提示 ${notes.length}`}>
          {warnings.length === 0 ? (
            <p className="text-[12px] text-emerald-300/80">
              未发现会导致曲目异常的问题：songlist / packlist 与 assets/songs 下的目录、谱面、音频完全对应。
            </p>
          ) : (
            <ul className="max-h-64 space-y-1 overflow-auto pr-1 text-[12px] leading-relaxed text-rose-200/90">
              {warnings.map((warning) => (
                <li key={warning} className="flex gap-1.5">
                  <span className="text-rose-500">!</span>
                  <span>{warning}</span>
                </li>
              ))}
            </ul>
          )}

          {notes.length > 0 && (
            <details className="mt-3 rounded border border-slate-800 bg-slate-950/40 p-2">
              <summary className="cursor-pointer select-none text-[12px] text-slate-400">
                展开 {notes.length} 条提示信息（通常无需处理）
              </summary>
              <ul className="mt-2 max-h-56 space-y-1 overflow-auto pr-1 text-[12px] leading-relaxed text-slate-400">
                {notes.map((note) => (
                  <li key={note} className="flex gap-1.5">
                    <span className="text-slate-600">•</span>
                    <span>{note}</span>
                  </li>
                ))}
              </ul>
            </details>
          )}

          <div className="mt-3 flex gap-2">
            <Button
              size="sm"
              onClick={async () => {
                const text = [
                  `【问题 ${warnings.length}】`,
                  ...warnings,
                  '',
                  `【提示 ${notes.length}】`,
                  ...notes,
                ].join('\n');
                await navigator.clipboard.writeText(text);
                notify('success', '已复制检查结果到剪贴板');
              }}
              disabled={warnings.length === 0 && notes.length === 0}
            >
              复制检查结果
            </Button>
          </div>
        </Card>
      </div>

      <Card title="关于修改范围">
        <ul className="ml-4 list-disc space-y-1 text-[12px] leading-relaxed text-slate-400">
          <li>本工具只修改 <span className="font-mono text-slate-300">assets/</span> 下的资源与 
            <span className="mx-1 font-mono text-slate-300">AndroidManifest.xml</span> 的包名，
            不会触碰 <span className="font-mono">classes.dex</span>、<span className="font-mono">lib/</span>、
            <span className="font-mono"> resources.arsc</span>。</li>
          <li>未改动的 zip 条目会原样直通拷贝，因此不会重新压缩整个安装包。</li>
          <li>原有签名会在导出时被移除，并由 apksigner 重新生成 v1 / v2 / v3 签名。</li>
        </ul>
      </Card>
    </div>
  );
}

function Info({ label, value, mono }: { label: string; value: string; mono?: boolean }): JSX.Element {
  return (
    <div className="min-w-0">
      <div className="text-[11px] uppercase tracking-wide text-slate-500">{label}</div>
      <div className={mono ? 'truncate font-mono text-[12px] text-slate-200' : 'truncate text-[12px] text-slate-200'}>
        {value}
      </div>
    </div>
  );
}
