import { run } from './proc';
import type { AdbDevice } from '../../shared/types';

export async function adbDevices(adbExe: string): Promise<AdbDevice[]> {
  const result = await run(adbExe, ['devices', '-l'], { timeoutMs: 20000 });
  if (!result.started) throw new Error('无法启动 adb，请检查前置资源是否就绪。');
  const lines = result.stdout.split(/\r?\n/).slice(1);
  const devices: AdbDevice[] = [];
  for (const line of lines) {
    const trimmed = line.trim();
    if (!trimmed) continue;
    const parts = trimmed.split(/\s+/);
    if (parts.length < 2) continue;
    const [serial, state, ...rest] = parts;
    const modelField = rest.find((p) => p.startsWith('model:'));
    devices.push({
      serial,
      state,
      model: modelField ? modelField.slice('model:'.length).replace(/_/g, ' ') : undefined,
    });
  }
  return devices;
}

export async function adbPair(
  adbExe: string,
  host: string,
  port: string,
  code: string,
): Promise<{ ok: boolean; message: string }> {
  const target = `${host.trim()}:${port.trim()}`;
  const result = await run(adbExe, ['pair', target, code.trim()], { timeoutMs: 60000 });
  const output = `${result.stdout}\n${result.stderr}`.trim();
  if (!result.started) return { ok: false, message: '无法启动 adb。' };
  return { ok: result.code === 0 && /Successfully paired/i.test(output), message: output };
}

export async function adbConnect(
  adbExe: string,
  host: string,
  port: string,
): Promise<{ ok: boolean; message: string }> {
  const target = `${host.trim()}:${port.trim()}`;
  const result = await run(adbExe, ['connect', target], { timeoutMs: 60000 });
  const output = `${result.stdout}\n${result.stderr}`.trim();
  if (!result.started) return { ok: false, message: '无法启动 adb。' };
  const ok = /connected to/i.test(output) && !/failed|refused|unable/i.test(output);
  return { ok, message: output };
}

export async function adbDisconnect(adbExe: string, target?: string): Promise<{ ok: boolean; message: string }> {
  const args = target ? ['disconnect', target] : ['disconnect'];
  const result = await run(adbExe, args, { timeoutMs: 30000 });
  return {
    ok: result.started && result.code === 0,
    message: `${result.stdout}\n${result.stderr}`.trim() || '已断开',
  };
}

/** 重启 adb 服务：用于在工具外建立了连接、但工具内看不到设备的情况 */
export async function adbRestartServer(adbExe: string): Promise<{ ok: boolean; message: string }> {
  const kill = await run(adbExe, ['kill-server'], { timeoutMs: 30000 });
  const start = await run(adbExe, ['start-server'], { timeoutMs: 60000 });
  if (!start.started) return { ok: false, message: '无法启动 adb。' };
  const output = `${kill.stdout}${kill.stderr}${start.stdout}${start.stderr}`.trim();
  return {
    ok: start.code === 0,
    message: output || 'adb 服务已重启',
  };
}
