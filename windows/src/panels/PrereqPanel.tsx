import { useEffect, type JSX } from 'react';
import { useStore } from '../state';
import { Badge, Button, Card, EmptyState } from '../components/ui';

export function PrereqPanel(): JSX.Element {
  const { prereqs, prereqsLoaded, refreshPrereqs, downloadPrereq, busy, notify } = useStore();

  useEffect(() => {
    if (!prereqsLoaded) void refreshPrereqs();
  }, [prereqsLoaded, refreshPrereqs]);

  const missingRequired = prereqs.filter((p) => p.required && !p.found);

  return (
    <div className="flex flex-col gap-3">
      <Card
        title="前置资源检测"
        actions={
          <>
            <Button size="sm" onClick={() => void refreshPrereqs()} disabled={Boolean(busy)}>
              重新检测
            </Button>
          </>
        }
      >
        <p className="mb-3 text-[12px] leading-relaxed text-slate-400">
          本工具复用你本机已安装的 Android 相关工具；缺失的资源可以一键下载到工具的
          <span className="mx-1 font-mono text-slate-300">tools/</span>
          目录，不会修改系统环境变量。打包时的 zip 对齐由本工具自行完成，因此
          <span className="mx-1 text-slate-300">不需要额外的 zipalign</span>。
        </p>

        <div className="flex flex-col divide-y divide-slate-800 overflow-hidden rounded-md border border-slate-800">
          {prereqs.map((item) => (
            <div key={item.id} className="flex items-start gap-3 bg-slate-950/40 px-3 py-2.5">
              <div className="mt-0.5">
                {item.found ? (
                  <Badge tone="emerald">已就绪</Badge>
                ) : item.required ? (
                  <Badge tone="rose">缺失</Badge>
                ) : (
                  <Badge tone="amber">可选</Badge>
                )}
              </div>
              <div className="min-w-0 flex-1">
                <div className="flex flex-wrap items-center gap-2">
                  <span className="text-[13px] font-medium text-slate-200">{item.name}</span>
                  {item.required ? (
                    <span className="text-[11px] text-slate-500">必需</span>
                  ) : (
                    <span className="text-[11px] text-slate-500">可选</span>
                  )}
                  {item.version && (
                    <span className="truncate font-mono text-[11px] text-slate-500">
                      {item.version}
                    </span>
                  )}
                </div>
                <div className="mt-0.5 text-[12px] leading-relaxed text-slate-400">
                  {item.purpose}
                </div>
                {item.path && (
                  <div className="mt-1 truncate font-mono text-[11px] text-slate-500">
                    {item.path}
                  </div>
                )}
                {!item.found && item.hint && (
                  <div className="mt-1 text-[12px] text-amber-300/80">{item.hint}</div>
                )}
              </div>
              <div className="flex shrink-0 flex-col items-end gap-1">
                {!item.found && item.canAutoDownload && (
                  <Button
                    size="sm"
                    variant="primary"
                    disabled={Boolean(busy)}
                    onClick={() => void downloadPrereq(item.id)}
                  >
                    一键下载
                  </Button>
                )}
                {item.downloadUrl && (
                  <Button
                    size="sm"
                    variant="ghost"
                    onClick={() => {
                      void window.api.shell.openExternal(item.downloadUrl!).catch(() => {
                        notify('error', '无法打开浏览器，请手动访问该链接');
                      });
                    }}
                  >
                    下载页面
                  </Button>
                )}
              </div>
            </div>
          ))}
          {prereqs.length === 0 && (
            <div className="p-4">
              <EmptyState title={prereqsLoaded ? '未获取到检测结果' : '正在检测…'} />
            </div>
          )}
        </div>
      </Card>

      {missingRequired.length > 0 && (
        <Card title="推荐安装步骤">
          <ol className="ml-4 list-decimal space-y-1.5 text-[12px] leading-relaxed text-slate-300">
            <li>
              安装 <span className="font-medium">JDK 17 或更高版本</span>（
              <span className="font-mono text-slate-400">java</span>）。apksigner 与 keytool
              都依赖它；仅安装 JRE 时无法自动生成签名密钥。
            </li>
            <li>
              点击上表 <span className="font-medium">Android Platform Tools</span> 的「一键下载」，
              获取 adb 用于无线安装。
            </li>
            <li>
              点击 <span className="font-medium">apksigner</span> 的「一键下载」，获取 Android
              build-tools 中的签名工具（约 58MB）。
            </li>
            <li>
              全部显示「已就绪」后即可导入 APK 开始修改。若你的机器上已有 Android SDK
              build-tools，工具会自动定位其中的 apksigner，无需再下载。
            </li>
          </ol>
        </Card>
      )}
    </div>
  );
}
