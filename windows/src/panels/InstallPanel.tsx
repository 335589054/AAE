import { useCallback, useEffect, useRef, useState, type JSX, type UIEvent } from 'react';
import { useStore } from '../state';
import {
  Badge,
  Button,
  Card,
  EmptyState,
  Field,
  ProgressBar,
  TextInput,
  cx,
} from '../components/ui';
import { formatBytes, formatDuration, formatRate } from '../lib';
import type { AdbDevice } from '../../shared/types';

const POLL_INTERVAL_MS = 4000;

const STATE_LABEL: Record<string, string> = {
  device: '可用',
  offline: '离线',
  unauthorized: '未授权',
  bootloader: 'Bootloader',
  recovery: 'Recovery',
  no: '未连接',
};

const PHASE_LABEL: Record<string, string> = {
  push: '正在上传 APK 到设备',
  install: '设备端正在安装（解包与校验）',
  cleanup: '正在清理设备端临时文件',
};

export function InstallPanel(): JSX.Element {
  const { prereqs, notify, run, lastExportPath, installProgress, clearInstallProgress } = useStore();
  const [devices, setDevices] = useState<AdbDevice[]>([]);
  const [selectedSerial, setSelectedSerial] = useState('');
  const [pollError, setPollError] = useState<string | null>(null);
  const [host, setHost] = useState('');
  const [pairPort, setPairPort] = useState('');
  const [pairCode, setPairCode] = useState('');
  const [connectPort, setConnectPort] = useState('');
  const [apkPath, setApkPath] = useState(lastExportPath ?? '');
  const [output, setOutput] = useState('');
  const [installing, setInstalling] = useState(false);
  const [follow, setFollow] = useState(true);
  const polling = useRef(false);
  const logRef = useRef<HTMLPreElement | null>(null);

  const adbReady = prereqs.find((p) => p.id === 'adb')?.found ?? false;

  /** 静默刷新设备列表（不触发忙碌遮罩），供轮询与手动刷新共用 */
  const pollDevices = useCallback(
    async (announce = false): Promise<AdbDevice[]> => {
      if (polling.current) return [];
      polling.current = true;
      try {
        const list = await window.api.adb.list();
        setDevices(list);
        setPollError(null);
        if (announce) {
          const online = list.filter((d) => d.state === 'device').length;
          notify('info', `检测到 ${online} 台可用设备，共 ${list.length} 条记录`);
        }
        return list;
      } catch (err) {
        setPollError(err instanceof Error ? err.message : String(err));
        return [];
      } finally {
        polling.current = false;
      }
    },
    [notify],
  );

  // 进入本页后轮询设备，这样在工具外部建立的连接也能被发现
  useEffect(() => {
    if (!adbReady) return;
    void pollDevices();
    const timer = window.setInterval(() => void pollDevices(), POLL_INTERVAL_MS);
    return () => window.clearInterval(timer);
  }, [adbReady, pollDevices]);

  // 唯一可用设备时自动选中；有多个可用设备时要求用户显式选择
  useEffect(() => {
    const online = devices.filter((d) => d.state === 'device');
    if (selectedSerial && online.some((d) => d.serial === selectedSerial)) return;
    setSelectedSerial(online.length === 1 ? online[0].serial : '');
  }, [devices, selectedSerial]);

  // 未手动指定时自动回退到最近一次导出的安装包（见下方 effectiveApk）
  const onlineDevices = devices.filter((d) => d.state === 'device');
  const otherDevices = devices.filter((d) => d.state !== 'device');
  const effectiveApk = apkPath || lastExportPath || '';

  const appendOutput = (text: string): void => {
    setOutput((prev) => {
      const merged = prev ? `${prev}\n${text}` : text;
      const lines = merged.split('\n');
      // 只保留最近 2000 行，避免长时间运行后内存与渲染压力过大
      return lines.length > 2000 ? lines.slice(-2000).join('\n') : merged;
    });
  };

  // 安装过程中实时接收 adb 原始输出
  useEffect(() => {
    const unsubscribe = window.api.onAdbLog((line) => appendOutput(line));
    return typeof unsubscribe === 'function' ? unsubscribe : undefined;
  }, []);

  // 自动滚动到最新一行（用户手动向上滚动时暂停跟随）
  useEffect(() => {
    if (!follow) return;
    const element = logRef.current;
    if (element) element.scrollTop = element.scrollHeight;
  }, [output, follow, installing]);

  const handleLogScroll = (event: UIEvent<HTMLPreElement>): void => {
    const element = event.currentTarget;
    const atBottom = element.scrollHeight - element.scrollTop - element.clientHeight < 24;
    setFollow(atBottom);
  };

  const startInstall = async (): Promise<void> => {
    setInstalling(true);
    clearInstallProgress();
    try {
      const res = await window.api.adb.install(effectiveApk, selectedSerial);
      appendOutput(res.message);
      if (res.cancelled) notify('info', '已停止安装');
      else notify(res.ok ? 'success' : 'error', res.ok ? '安装成功' : res.message);
    } catch (err) {
      notify('error', err instanceof Error ? err.message : String(err));
    } finally {
      setInstalling(false);
      clearInstallProgress();
    }
  };

  const stopInstall = async (): Promise<void> => {
    try {
      const res = await window.api.adb.cancelInstall();
      notify(res.ok ? 'info' : 'error', res.message);
    } catch (err) {
      notify('error', err instanceof Error ? err.message : String(err));
    }
  };

  return (
    <div className="flex flex-col gap-3">
      <Card
        title="adb 无线调试"
        actions={
          <>
            <Badge tone={adbReady ? 'emerald' : 'rose'}>{adbReady ? 'adb 已就绪' : 'adb 缺失'}</Badge>
            <Button
              size="sm"
              disabled={!adbReady}
              onClick={async () => {
                const list = await run('正在刷新设备…', () => pollDevices(true));
                if (list && list.length === 0) {
                  notify('info', '当前没有检测到设备，可尝试「重启 adb 服务」或重新配对。');
                }
              }}
            >
              刷新设备
            </Button>
            <Button
              size="sm"
              disabled={!adbReady}
              title="杀掉并重启 adb 服务，用于识别在工具外部建立的连接"
              onClick={async () => {
                await run('正在重启 adb 服务…', async () => {
                  const result = await window.api.adb.restartServer();
                  appendOutput(result.message);
                  await pollDevices();
                  notify(result.ok ? 'success' : 'error', result.ok ? 'adb 服务已重启' : result.message);
                });
              }}
            >
              重启 adb 服务
            </Button>
          </>
        }
      >
        {!adbReady ? (
          <p className="text-[12px] text-amber-300/85">
            未检测到 adb。请到「前置资源」页一键下载 Android Platform Tools。
          </p>
        ) : (
          <div className="flex flex-col gap-3">
            <ol className="ml-4 list-decimal space-y-1 text-[12px] leading-relaxed text-slate-400">
              <li>
                手机进入 <span className="text-slate-300">设置 → 开发者选项</span>，打开
                <span className="mx-1 text-slate-300">无线调试</span>（需与电脑在同一 Wi-Fi）。
              </li>
              <li>
                点击「使用配对码配对设备」，记下页面上的
                <span className="mx-1 text-slate-300">IP 地址、配对端口与 6 位配对码</span>，填入下方完成配对。
              </li>
              <li>
                配对成功后回到无线调试页面，用其中的
                <span className="mx-1 text-slate-300">连接端口</span>（与配对端口不同）执行连接。
              </li>
            </ol>

            <div className="grid gap-3 xl:grid-cols-2">
              <div className="rounded-md border border-slate-800 bg-slate-950/40 p-3">
                <div className="mb-2 text-[12px] font-medium text-slate-300">① 配对（首次需要）</div>
                <div className="grid grid-cols-3 gap-2">
                  <Field label="IP / 主机">
                    <TextInput value={host} onChange={setHost} placeholder="192.168.1.10" />
                  </Field>
                  <Field label="配对端口">
                    <TextInput value={pairPort} onChange={setPairPort} placeholder="37000" />
                  </Field>
                  <Field label="配对码">
                    <TextInput value={pairCode} onChange={setPairCode} placeholder="123456" />
                  </Field>
                </div>
                <div className="mt-2">
                  <Button
                    size="sm"
                    variant="primary"
                    disabled={!host || !pairPort || !pairCode}
                    onClick={async () => {
                      const res = await run('正在配对…', () =>
                        window.api.adb.pair(host, pairPort, pairCode),
                      );
                      if (res) {
                        appendOutput(res.message);
                        notify(res.ok ? 'success' : 'error', res.message);
                      }
                    }}
                  >
                    配对
                  </Button>
                </div>
              </div>

              <div className="rounded-md border border-slate-800 bg-slate-950/40 p-3">
                <div className="mb-2 text-[12px] font-medium text-slate-300">② 连接</div>
                <div className="grid grid-cols-2 gap-2">
                  <Field label="IP / 主机">
                    <TextInput value={host} onChange={setHost} placeholder="192.168.1.10" />
                  </Field>
                  <Field label="连接端口">
                    <TextInput value={connectPort} onChange={setConnectPort} placeholder="41000" />
                  </Field>
                </div>
                <div className="mt-2 flex gap-2">
                  <Button
                    size="sm"
                    variant="primary"
                    disabled={!host || !connectPort}
                    onClick={async () => {
                      const res = await run('正在连接…', () =>
                        window.api.adb.connect(host, connectPort),
                      );
                      if (res) {
                        appendOutput(res.message);
                        notify(res.ok ? 'success' : 'error', res.message);
                        await pollDevices();
                      }
                    }}
                  >
                    连接
                  </Button>
                  <Button
                    size="sm"
                    onClick={async () => {
                      const res = await run('正在断开…', () => window.api.adb.disconnect());
                      if (res) {
                        appendOutput(res.message);
                        await pollDevices();
                      }
                    }}
                  >
                    断开全部
                  </Button>
                </div>
              </div>
            </div>

            <div>
              <div className="mb-2 flex items-center gap-2">
                <span className="text-[12px] font-medium text-slate-300">③ 选择目标设备</span>
                <Badge tone={onlineDevices.length > 0 ? 'emerald' : 'amber'}>
                  可用 {onlineDevices.length} 台
                </Badge>
                <span className="text-[11px] text-slate-500">
                  每 {POLL_INTERVAL_MS / 1000} 秒自动刷新一次设备列表
                </span>
              </div>

              {devices.length === 0 ? (
                <EmptyState
                  title="暂无设备"
                  description={
                    pollError
                      ? `读取设备列表失败：${pollError}`
                      : '完成上面的配对与连接后设备会自动出现；如果已在其它工具里连好，可点「重启 adb 服务」。'
                  }
                />
              ) : (
                <div className="flex flex-col divide-y divide-slate-800 overflow-hidden rounded-md border border-slate-800">
                  {[...onlineDevices, ...otherDevices].map((device) => {
                    const selectable = device.state === 'device';
                    const active = device.serial === selectedSerial;
                    return (
                      <button
                        key={device.serial}
                        type="button"
                        disabled={!selectable}
                        onClick={() => setSelectedSerial(device.serial)}
                        className={cx(
                          'flex items-center gap-3 px-3 py-2 text-left transition-colors',
                          active ? 'bg-violet-950/60' : 'bg-slate-950/40',
                          selectable ? 'hover:bg-slate-800/50' : 'cursor-not-allowed opacity-60',
                        )}
                      >
                        <span
                          className={cx(
                            'flex h-3.5 w-3.5 shrink-0 items-center justify-center rounded-full border',
                            active ? 'border-violet-400 bg-violet-500' : 'border-slate-600',
                          )}
                        />
                        <Badge tone={selectable ? 'emerald' : 'amber'}>
                          {STATE_LABEL[device.state] ?? device.state}
                        </Badge>
                        <span className="font-mono text-[12px] text-slate-200">{device.serial}</span>
                        {device.model && (
                          <span className="text-[12px] text-slate-400">{device.model}</span>
                        )}
                        {active && <span className="ml-auto text-[11px] text-violet-300">安装目标</span>}
                      </button>
                    );
                  })}
                </div>
              )}

              {onlineDevices.length > 1 && !selectedSerial && (
                <p className="mt-2 text-[12px] text-amber-300/85">
                  检测到多台可用设备（含模拟器）。请在上方列表中点击选择要安装的目标设备，
                  否则 adb 会以 “more than one device/emulator” 拒绝执行。
                </p>
              )}
            </div>
          </div>
        )}
      </Card>

      <Card title="安装 APK">
        <div className="flex flex-col gap-3">
          <Field label="APK 文件" hint="默认使用最近一次导出的安装包，也可以手动选择其它 APK">
            <div className="flex gap-2">
              <TextInput value={effectiveApk} onChange={setApkPath} placeholder="D:\\out\\arcaea_mod.apk" />
              <Button
                onClick={async () => {
                  const picked = await window.api.dialog.openFile('选择要安装的 APK', [
                    { name: 'Android 安装包', extensions: ['apk'] },
                  ]);
                  if (picked) setApkPath(picked);
                }}
              >
                浏览
              </Button>
            </div>
          </Field>

          {lastExportPath && (
            <div className="flex flex-wrap items-center gap-2 text-[11.5px] text-slate-400">
              <Badge tone="violet">最近导出</Badge>
              <span className="truncate font-mono">{lastExportPath}</span>
              {effectiveApk !== lastExportPath && (
                <Button size="sm" variant="ghost" onClick={() => setApkPath(lastExportPath)}>
                  使用这个
                </Button>
              )}
            </div>
          )}

          {installing && (
            <div className="rounded-md border border-violet-900/60 bg-violet-950/25 p-3">
              <div className="flex items-center gap-2">
                <span className="inline-block h-3 w-3 animate-spin rounded-full border-2 border-violet-400 border-t-transparent" />
                <span className="text-[12.5px] font-medium text-slate-200">
                  {installProgress
                    ? (PHASE_LABEL[installProgress.phase] ?? installProgress.message)
                    : '正在准备安装…'}
                </span>
                {installProgress?.estimated && <Badge tone="amber">速率来自设备端估算</Badge>}
                <div className="flex-1" />
                <Button size="sm" variant="danger" onClick={() => void stopInstall()}>
                  停止安装
                </Button>
              </div>

              <div className="mt-2">
                {installProgress?.percent === undefined ? (
                  <div className="h-1.5 w-full overflow-hidden rounded-full bg-slate-800">
                    <div className="aam-indeterminate h-full w-1/4 rounded-full bg-gradient-to-r from-violet-500 to-sky-400" />
                  </div>
                ) : (
                  <ProgressBar percent={installProgress.percent} />
                )}
              </div>

              <div className="mt-1.5 flex flex-wrap items-center gap-x-4 gap-y-1 text-[11.5px] text-slate-400">
                {installProgress?.percent !== undefined && (
                  <span className="font-semibold text-slate-100">{installProgress.percent}%</span>
                )}
                {installProgress?.totalBytes ? (
                  <span>
                    已传输 {formatBytes(installProgress.bytesSent ?? 0)} /{' '}
                    {formatBytes(installProgress.totalBytes)}
                  </span>
                ) : null}
                <span>实时速率 {formatRate(installProgress?.bytesPerSecond)}</span>
                {installProgress?.elapsedMs !== undefined && (
                  <span>本阶段已用时 {formatDuration(installProgress.elapsedMs)}</span>
                )}
              </div>

              <div className="mt-1 truncate text-[11px] text-slate-500">
                {installProgress?.message}
              </div>
            </div>
          )}

          <div className="flex items-center gap-2">
            <Badge tone={selectedSerial ? 'emerald' : 'amber'}>
              {selectedSerial ? `目标设备 ${selectedSerial}` : '尚未选择设备'}
            </Badge>
          </div>

          <div className="flex gap-2">
            <Button
              variant="primary"
              disabled={!effectiveApk || !adbReady || !selectedSerial || installing}
              onClick={() => void startInstall()}
            >
              {installing ? '正在安装…' : '安装到所选设备'}
            </Button>
            {installing && (
              <Button variant="danger" onClick={() => void stopInstall()}>
                停止安装
              </Button>
            )}
            <Button
              variant="ghost"
              disabled={!effectiveApk || installing}
              onClick={() => void window.api.shell.openPath(effectiveApk)}
            >
              定位文件
            </Button>
          </div>

          {!selectedSerial && adbReady && (
            <p className="text-[12px] text-amber-300/85">
              {devices.length === 0
                ? '还没有检测到设备，请先完成配对与连接。'
                : '请先在上面的设备列表中选择目标设备。'}
            </p>
          )}

          <p className="text-[12px] leading-relaxed text-amber-200/85">
            安装完成后，请在手机上<strong>清除 Arcaea 的应用数据 / 缓存</strong>
            再启动游戏，否则可能仍然加载到旧资源。详见「缓存与清理」页。
          </p>
          <p className="text-[12px] leading-relaxed text-amber-200/85">
            进入游戏后建议<strong>断开网络</strong>（飞行模式，或关掉 WiFi 与移动数据）再开始游玩：
            客户端会与服务器同步曲目数据（游戏二进制中包含 <span className="font-mono">SongHashes</span> /
            <span className="mx-1 font-mono">OnlineManager</span>），在线时可能加载到与改包内容不匹配的曲目数据而闪退。
          </p>
        </div>
      </Card>

      {(output || installing) && (
        <Card
          title="adb 输出"
          actions={
            <>
              <Badge tone={follow ? 'emerald' : 'amber'}>
                {follow ? '自动滚动中' : '已暂停跟随'}
              </Badge>
              {!follow && (
                <Button
                  size="sm"
                  onClick={() => {
                    setFollow(true);
                    const element = logRef.current;
                    if (element) element.scrollTop = element.scrollHeight;
                  }}
                >
                  跟随最新
                </Button>
              )}
              <Button
                size="sm"
                variant="ghost"
                disabled={!output}
                onClick={() => setOutput('')}
              >
                清空
              </Button>
            </>
          }
        >
          <pre
            ref={logRef}
            onScroll={handleLogScroll}
            className="max-h-80 overflow-auto whitespace-pre-wrap break-words rounded-md border border-slate-800 bg-slate-950/70 p-2 font-mono text-[11px] leading-relaxed text-slate-400"
          >
            {output || '（等待输出…）'}
          </pre>
          <p className="mt-2 text-[11px] leading-relaxed text-slate-500">
            这里会实时滚出安装过程中的 adb 原始输出（已过滤掉上传进度刷屏）。向上滚动可暂停自动跟随。
          </p>
        </Card>
      )}
    </div>
  );
}
