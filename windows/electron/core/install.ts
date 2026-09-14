import { spawn, type ChildProcess } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { run } from './proc';

/**
 * 带进度、可中止的 APK 安装。
 *
 * 不用 `adb install`，而是拆成两步：
 *   1. `adb push <apk> /data/local/tmp/...` —— adb push 默认就会输出
 *      `[ 42%] /data/local/tmp/xxx.apk` 形式的进度（`-q` 才是关闭），
 *      因此可以据此算出真实的上传百分比与实时带宽；
 *      若某些版本/adb 组合不输出进度，则退化为轮询设备端文件大小来估算。
 *   2. `adb shell pm install -r -t <remote>` —— 设备端安装，无法获取百分比，
 *      改为每秒上报一次已用时间（不定进度）。
 *   3. 最后清理设备上的临时文件。
 *
 * 过程中可以随时 cancel()：直接杀掉 adb 客户端进程即可中止上传。
 */

export type InstallPhase = 'push' | 'install' | 'cleanup';

export interface InstallProgress {
  phase: InstallPhase;
  message: string;
  percent?: number;
  bytesSent?: number;
  totalBytes?: number;
  bytesPerSecond?: number;
  elapsedMs?: number;
  /** true 表示数据来自兜底探测（设备端文件大小），而非 adb 自身进度输出 */
  estimated?: boolean;
}

export interface InstallResult {
  ok: boolean;
  cancelled?: boolean;
  message: string;
}

export interface InstallHandle {
  promise: Promise<InstallResult>;
  cancel(): void;
}

/** 从 adb push 的输出中解析最后出现的进度百分比 */
export function parsePushPercent(text: string): number | null {
  const matches = [...text.matchAll(/\[\s*(\d{1,3})%\]/g)];
  if (matches.length === 0) return null;
  const value = Number(matches[matches.length - 1][1]);
  return Number.isFinite(value) ? Math.min(100, Math.max(0, value)) : null;
}

/** 从 `ls -l` 输出中解析包含 token 的文件大小（兜底进度） */
export function parseRemoteSize(text: string, token: string): number | null {
  for (const line of text.split(/\r?\n/)) {
    if (!line.includes(token)) continue;
    const match = /^\S+\s+\d+\s+\S+\s+\S+\s+(\d+)\s/.exec(line);
    if (match) return Number(match[1]);
  }
  return null;
}

/** `adb push` 最终摘要里的总体速率，例如 "1 file pushed, 0 skipped. 25.4 MB/s (...)" */
export function parsePushSummary(text: string): string | null {
  const match = /([\d.]+\s*[KMG]B\/s)/.exec(text);
  return match ? match[1].replace(/\s+/g, '') : null;
}

/** 把 adb 的失败输出翻译成可操作的中文提示 */
export function explainInstallFailure(output: string): string {
  // 与原版共存时最常见的两个冲突：自定义权限与 Provider 授权名为全机唯一
  const duplicatePermission =
    /INSTALL_FAILED_DUPLICATE_PERMISSION/.exec(output) ??
    /attempting to redeclare permission\s+(\S+)/i.exec(output);
  if (duplicatePermission) {
    const permission = /permission\s+(\S+?)\s+already owned/i.exec(output)?.[1];
    return [
      '安装被拒绝：新安装包声明了一个已被其它应用占用的自定义权限。',
      permission ? `冲突权限：${permission}` : '',
      '自定义权限在整台设备上只能由一个应用声明，原版 Arcaea 已经持有它，所以改名后的版本无法再声明同名权限。',
      '解决办法（任选其一）：',
      '  1) 回到「打包与签名」页，勾选「同时改写自定义权限名与 Provider 授权名」后重新导出并安装（推荐，可与原版共存）；',
      '  2) 先卸载手机上的原版 Arcaea，再安装当前这个包；',
      '  3) 不修改包名直接覆盖安装（需要与原版相同的签名，普通用户不适用）。',
    ]
      .filter(Boolean)
      .join('\n');
  }

  if (/INSTALL_FAILED_CONFLICTING_PROVIDER/i.test(output)) {
    const authority = /authority\s+(\S+?)\s+already/i.exec(output)?.[1];
    return [
      '安装被拒绝：新安装包使用了已被其它应用占用的 ContentProvider 授权名。',
      authority ? `冲突授权名：${authority}` : '',
      '解决办法：回到「打包与签名」页，勾选「同时改写自定义权限名与 Provider 授权名」后重新导出；',
      '或先卸载手机上的原版 Arcaea 再安装。',
    ]
      .filter(Boolean)
      .join('\n');
  }

  const hints: Array<[RegExp, string]> = [
    [/more than one device|more than one emulator/i, '请在设备列表中选择目标设备后重试'],
    [/device .*not found|device offline/i, '设备已断开或离线，请重新连接并刷新设备列表'],
    [/INSTALL_FAILED_UPDATE_INCOMPATIBLE|signatures do not match/i, '签名不一致：请先卸载手机上的旧版本，或改用「修改包名」导出成独立应用'],
    [/INSTALL_FAILED_VERSION_DOWNGRADE/i, '目标机上的版本号更高：请提高 versionCode 或先卸载旧版本'],
    [/INSTALL_FAILED_INSUFFICIENT_STORAGE/i, '设备存储空间不足'],
    [/INSTALL_FAILED_INVALID_APK|INSTALL_PARSE_FAILED/i, 'APK 被系统判定为无效：请确认导出过程没有中断'],
    [/INSTALL_FAILED_USER_RESTRICTED/i, '系统限制了安装：请在手机开发者选项里关闭「USB 安装限制」/「MIUI 优化」等开关'],
    [/Failure \[([A-Z_]+)\]/i, '设备拒绝了安装'],
    [/no such file|No such file/i, '设备端临时文件不存在，可能是上传被中断'],
    [/permission denied/i, '权限被拒绝：请确认已授权 USB/无线调试'],
  ];
  for (const [pattern, hint] of hints) {
    if (pattern.test(output)) return hint;
  }
  return '';
}

interface StreamResult {
  code: number;
  stdout: string;
  stderr: string;
  started: boolean;
}

export function startInstall(options: {
  adbExe: string;
  apkPath: string;
  serial?: string;
  onProgress: (progress: InstallProgress) => void;
  /** 原始 adb 输出（已按行切分、过滤掉进度刷屏），用于界面滚动展示 */
  onLog?: (line: string) => void;
}): InstallHandle {
  const { adbExe, apkPath, serial, onProgress, onLog } = options;
  const totalBytes = fs.statSync(apkPath).size;
  const token = `aam_${Date.now().toString(36)}`;
  const safeName = path.basename(apkPath).replace(/[^\w.\-]+/g, '_') || 'package.apk';
  const remotePath = `/data/local/tmp/${token}_${safeName}`;
  const baseArgs = serial ? ['-s', serial] : [];
  const startedAt = Date.now();

  let cancelled = false;
  let current: ChildProcess | null = null;
  let pollTimer: NodeJS.Timeout | null = null;
  let installTicker: NodeJS.Timeout | null = null;

  // 速率计算用的滑动窗口
  const samples: Array<{ at: number; bytes: number }> = [];
  let lastEmitAt = 0;

  const currentRate = (bytes: number): number | undefined => {
    const now = Date.now();
    samples.push({ at: now, bytes });
    while (samples.length > 2 && now - samples[0].at > 4000) samples.shift();
    if (samples.length < 2) return undefined;
    const first = samples[0];
    const seconds = (now - first.at) / 1000;
    const delta = bytes - first.bytes;
    if (seconds <= 0 || delta <= 0) return undefined;
    return delta / seconds;
  };

  const emit = (progress: InstallProgress, throttle = false): void => {
    const now = Date.now();
    if (throttle && now - lastEmitAt < 180) return;
    lastEmitAt = now;
    onProgress(progress);
  };

  const stopTimers = (): void => {
    if (pollTimer) clearInterval(pollTimer);
    if (installTicker) clearInterval(installTicker);
    pollTimer = null;
    installTicker = null;
  };

  /** 由各阶段替换，用于消费子进程输出 */
  let onStdout: (chunk: string) => void = () => undefined;

  /** 把子进程输出按行切分后转交界面（跳过 adb push 的 \r 刷屏进度） */
  let logPending = '';
  const feedLog = (chunk: string): void => {
    if (!onLog) return;
    logPending += chunk;
    const parts = logPending.split(/\r?\n/);
    logPending = parts.pop() ?? '';
    for (const part of parts) {
      const line = part.trim();
      if (!line) continue;
      if (/^\[\s*\d{1,3}%\]/.test(line)) continue; // 进度行由进度条展示
      onLog(line.slice(0, 600));
    }
    if (logPending.length > 4000) logPending = logPending.slice(-2000);
  };

  const logCommand = (args: string[]): void => {
    onLog?.(`> adb ${args.join(' ')}`);
  };

  const runRemote = (args: string[], timeoutMs = 0): Promise<StreamResult> =>
    new Promise((resolve) => {
      let child: ChildProcess;
      try {
        child = spawn(adbExe, [...baseArgs, ...args], { windowsHide: true });
      } catch {
        resolve({ code: -1, stdout: '', stderr: '', started: false });
        return;
      }
      current = child;
      let stdout = '';
      let stderr = '';
      let settled = false;
      let timer: NodeJS.Timeout | undefined;
      const finish = (result: StreamResult): void => {
        if (settled) return;
        settled = true;
        if (timer) clearTimeout(timer);
        if (current === child) current = null;
        resolve(result);
      };
      child.stdout?.on('data', (buf: Buffer) => {
        const text = buf.toString('utf8');
        stdout += text;
        feedLog(text);
        onStdout(text);
      });
      child.stderr?.on('data', (buf: Buffer) => {
        const text = buf.toString('utf8');
        stderr += text;
        feedLog(text);
        onStdout(text);
      });
      child.on('error', () => finish({ code: -1, stdout, stderr, started: false }));
      child.on('close', (code) => finish({ code: code ?? -1, stdout, stderr, started: true }));
      if (timeoutMs > 0) {
        timer = setTimeout(() => {
          try {
            child.kill();
          } catch {
            /* ignore */
          }
        }, timeoutMs);
      }
    });

  const promise = (async (): Promise<InstallResult> => {
    /* ---------------------------- 1. 上传 APK ---------------------------- */
    let tail = '';
    let lastPercent: number | null = null;

    onStdout = (chunk) => {
      tail = (tail + chunk).slice(-4096);
      const percent = parsePushPercent(tail);
      if (percent === null) return;
      lastPercent = percent;
      const bytesSent = Math.round((totalBytes * percent) / 100);
      emit(
        {
          phase: 'push',
          message: `正在上传 APK（${percent}%）`,
          percent,
          bytesSent,
          totalBytes,
          bytesPerSecond: currentRate(bytesSent),
          elapsedMs: Date.now() - startedAt,
        },
        true,
      );
    };

    emit({
      phase: 'push',
      message: '正在上传 APK 到设备…',
      percent: 0,
      bytesSent: 0,
      totalBytes,
      elapsedMs: 0,
    });

    // 若 4 秒内没拿到 adb 的进度输出，则退化为轮询设备端文件大小
    const fallbackTimer = setTimeout(() => {
      if (lastPercent !== null || cancelled) return;
      emit({
        phase: 'push',
        message: '正在上传 APK（adb 未输出进度，改用设备端大小估算）…',
        percent: 0,
        bytesSent: 0,
        totalBytes,
        estimated: true,
        elapsedMs: Date.now() - startedAt,
      });
      pollTimer = setInterval(() => {
        void (async () => {
          if (cancelled) return;
          const result = await run(adbExe, [...baseArgs, 'shell', 'ls', '-l', path.posix.dirname(remotePath)], {
            timeoutMs: 15000,
          });
          if (!result.started) return;
          const size = parseRemoteSize(result.stdout, token);
          if (size === null || size <= 0) return;
          const percent = Math.min(99, Math.round((size / totalBytes) * 100));
          emit(
            {
              phase: 'push',
              message: `正在上传 APK（约 ${percent}%）`,
              percent,
              bytesSent: size,
              totalBytes,
              bytesPerSecond: currentRate(size),
              estimated: true,
              elapsedMs: Date.now() - startedAt,
            },
            true,
          );
        })();
      }, 1500);
    }, 4000);

    onLog?.('');
    logCommand([...baseArgs, 'push', apkPath, remotePath]);
    const pushResult = await runRemote(['push', apkPath, remotePath]);
    clearTimeout(fallbackTimer);
    if (pollTimer) {
      clearInterval(pollTimer);
      pollTimer = null;
    }
    onLog?.(`< push 退出码 ${pushResult.code}`);

    if (cancelled) {
      stopTimers();
      return { ok: false, cancelled: true, message: '已停止安装（上传被中断）' };
    }
    if (!pushResult.started) {
      stopTimers();
      return { ok: false, message: '无法启动 adb，请检查前置资源是否就绪。' };
    }
    if (pushResult.code !== 0) {
      stopTimers();
      const output = `${pushResult.stdout}\n${pushResult.stderr}`.trim();
      const hint = explainInstallFailure(output);
      return { ok: false, message: `上传失败：${output}${hint ? `\n提示：${hint}` : ''}` };
    }

    const summaryRate = parsePushSummary(`${pushResult.stdout}\n${pushResult.stderr}`);
    emit({
      phase: 'push',
      message: summaryRate ? `上传完成（平均 ${summaryRate}）` : '上传完成',
      percent: 100,
      bytesSent: totalBytes,
      totalBytes,
      elapsedMs: Date.now() - startedAt,
    });

    /* --------------------------- 2. 设备端安装 --------------------------- */
    const installStartedAt = Date.now();
    emit({
      phase: 'install',
      message: '正在设备端安装（解包与校验，可能需要较长时间）…',
      elapsedMs: 0,
    });
    installTicker = setInterval(() => {
      emit({
        phase: 'install',
        message: '正在设备端安装（解包与校验，可能需要较长时间）…',
        elapsedMs: Date.now() - installStartedAt,
      });
    }, 1000);

    logCommand([...baseArgs, 'shell', 'pm', 'install', '-r', '-t', remotePath]);
    const installResult = await runRemote(['shell', 'pm', 'install', '-r', '-t', remotePath]);
    if (installTicker) {
      clearInterval(installTicker);
      installTicker = null;
    }
    onLog?.(`< install 退出码 ${installResult.code}`);

    const installOutput = `${installResult.stdout}\n${installResult.stderr}`.trim();

    /* ----------------------------- 3. 清理 ----------------------------- */
    emit({ phase: 'cleanup', message: '正在清理设备上的临时文件…' });
    logCommand([...baseArgs, 'shell', 'rm', '-f', remotePath]);
    await run(adbExe, [...baseArgs, 'shell', 'rm', '-f', remotePath], { timeoutMs: 20000 });
    stopTimers();

    if (cancelled) {
      return { ok: false, cancelled: true, message: '已停止安装（已清理设备端临时文件）' };
    }
    if (installResult.code === 0 && /Success/i.test(installOutput)) {
      return { ok: true, message: installOutput || 'Success' };
    }

    const hint = explainInstallFailure(installOutput);
    return {
      ok: false,
      message: `${installOutput || `pm install 退出码 ${installResult.code}`}${hint ? `\n提示：${hint}` : ''}`,
    };
  })();

  return {
    promise,
    cancel(): void {
      if (cancelled) return;
      cancelled = true;
      stopTimers();
      const child = current;
      if (child) {
        try {
          child.kill();
        } catch {
          /* ignore */
        }
      }
      // 尽力清理设备端残留（不阻塞取消动作）
      void run(adbExe, [...baseArgs, 'shell', 'rm', '-f', remotePath], { timeoutMs: 15000 });
    },
  };
}
