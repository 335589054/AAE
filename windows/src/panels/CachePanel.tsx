import { useCallback, useEffect, useState, type JSX } from 'react';
import { useStore } from '../state';
import { Badge, Button, Card, Modal } from '../components/ui';
import { formatBytes } from '../lib';
import type { CacheInfo } from '../../shared/types';

export function CachePanel(): JSX.Element {
  const { snapshot, notify, run } = useStore();
  const [info, setInfo] = useState<CacheInfo | null>(null);
  const [confirmClear, setConfirmClear] = useState(false);
  const [blockedReason, setBlockedReason] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    try {
      setInfo(await window.api.cache.info());
    } catch {
      setInfo(null);
    }
  }, []);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  const dirty = snapshot?.dirtySinceExport ?? false;

  const doClear = async (force: boolean): Promise<void> => {
    setBlockedReason(null);
    const result = await run('正在清除缓存…', () => window.api.cache.clear(force));
    if (!result) return;
    if (!result.cleared) {
      setBlockedReason(result.reason ?? '清除失败');
      return;
    }
    setConfirmClear(false);
    notify('success', `已清除缓存，释放 ${formatBytes(result.freedBytes)}`);
    await refresh();
  };

  return (
    <div className="flex flex-col gap-3">
      <Card
        title="工具缓存"
        actions={
          <>
            <Button size="sm" onClick={() => void refresh()}>
              刷新
            </Button>
            <Button
              size="sm"
              variant="danger"
              disabled={!info || info.files === 0}
              onClick={() => {
                if (dirty) {
                  setBlockedReason('检测到尚未导出的改动。请先导出 APK，再清除缓存。');
                  setConfirmClear(false);
                  return;
                }
                setConfirmClear(true);
              }}
            >
              清除缓存
            </Button>
          </>
        }
      >
        <div className="flex flex-col gap-3">
          <div className="grid grid-cols-3 gap-3">
            <div>
              <div className="text-[11px] uppercase tracking-wide text-slate-500">缓存位置</div>
              <div className="truncate font-mono text-[12px] text-slate-300">{info?.path ?? '—'}</div>
            </div>
            <div>
              <div className="text-[11px] uppercase tracking-wide text-slate-500">占用空间</div>
              <div className="text-[12px] text-slate-300">{info ? formatBytes(info.bytes) : '—'}</div>
            </div>
            <div>
              <div className="text-[11px] uppercase tracking-wide text-slate-500">文件数</div>
              <div className="text-[12px] text-slate-300">{info?.files ?? '—'}</div>
            </div>
          </div>

          <div className="flex flex-wrap items-center gap-2">
            {snapshot && (
              <Badge tone={dirty ? 'amber' : 'emerald'}>
                {dirty ? '有改动尚未导出' : '当前改动均已导出'}
              </Badge>
            )}
            {snapshot?.exportedOnce && <Badge tone="violet">本次会话已导出过 APK</Badge>}
          </div>

          {blockedReason && (
            <div className="rounded-md border border-amber-900/60 bg-amber-950/30 p-3 text-[12px] leading-relaxed text-amber-200">
              {blockedReason}
              <div className="mt-1 text-amber-200/80">
                请先到「打包与签名」页导出 APK，确认导出成功后即可清除缓存。
              </div>
            </div>
          )}

          <ul className="ml-4 list-disc space-y-1 text-[12px] leading-relaxed text-slate-400">
            <li>
              缓存中只包含打包过程中的临时文件（未签名中间产物等）。本工具
              <strong className="text-slate-300">不会长期保存任何 Arcaea 游戏资源</strong>
              ，你导入的 APK 与导出的新 APK 都在你自己选择的位置。
            </li>
            <li>每次成功导出后工具会自动清理临时文件；此处用于手动兜底清理。</li>
            <li>
              为避免误删尚未导出的成果，只要存在「未导出的改动」，清除缓存会被拦截。
            </li>
          </ul>
        </div>
      </Card>

      <Card title="手机端清理引导（重要）">
        <div className="flex flex-col gap-3">
          <p className="text-[12px] leading-relaxed text-slate-300">
            手机上的 Arcaea 可能缓存了旧的曲绘、音频或曲目信息。安装修改后的 APK 后，请按下面步骤清理，
            否则可能看不到你的改动。
          </p>
          <ol className="ml-4 list-decimal space-y-1.5 text-[12px] leading-relaxed text-slate-400">
            <li>
              确认本工具已经
              <span className="mx-1 text-slate-300">成功导出 APK</span>
              且已安装到手机。
            </li>
            <li>
              手机进入 <span className="text-slate-300">设置 → 应用 → Arcaea → 存储</span>。
            </li>
            <li>
              先点击 <span className="text-slate-300">清除缓存</span>；如果改动仍未生效（尤其替换了封面 /
              音频），再点击 <span className="text-slate-300">清除数据</span>。
            </li>
            <li>
              <strong className="text-amber-300">清除数据会删除本地存档与登录状态</strong>
              （本地游玩进度、谱面成绩）。如需要保留，请先自行备份，或优先尝试只清缓存。
            </li>
            <li>
              重新启动游戏，并建议先断网启动一次，避免在线校验覆盖本地资源。
            </li>
          </ol>
          <div className="flex flex-wrap gap-2">
            <Button
              size="sm"
              onClick={() => {
                void navigator.clipboard
                  .writeText(
                    '设置 → 应用 → Arcaea → 存储 → 清除缓存（必要时再清除数据）',
                  )
                  .then(() => notify('success', '已复制清理步骤'));
              }}
            >
              复制清理步骤
            </Button>
          </div>
        </div>
      </Card>

      <Modal
        open={confirmClear}
        title="确认清除缓存"
        onClose={() => setConfirmClear(false)}
        width="max-w-md"
        footer={
          <>
            <Button variant="ghost" onClick={() => setConfirmClear(false)}>
              取消
            </Button>
            <Button variant="danger" onClick={() => void doClear(false)}>
              清除缓存
            </Button>
          </>
        }
      >
        <p className="text-[12.5px] leading-relaxed text-slate-300">
          将删除缓存目录下的临时文件（当前占用 {info ? formatBytes(info.bytes) : '—'}）。
          这不会影响你已经导出的 APK，也不会删除导入的原始 APK。
        </p>
      </Modal>
    </div>
  );
}
