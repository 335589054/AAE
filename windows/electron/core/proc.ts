import { spawn } from 'node:child_process';

export interface RunResult {
  code: number;
  stdout: string;
  stderr: string;
  /** 命令本身是否成功启动（找不到可执行文件时为 false） */
  started: boolean;
}

export interface RunOptions {
  cwd?: string;
  timeoutMs?: number;
  env?: NodeJS.ProcessEnv;
  onOutput?: (chunk: string, stream: 'stdout' | 'stderr') => void;
  input?: string;
}

/** 无 shell 地执行外部命令，避免路径/引号注入问题 */
export function run(exe: string, args: string[], options: RunOptions = {}): Promise<RunResult> {
  const { cwd, timeoutMs = 0, env, onOutput, input } = options;
  return new Promise((resolve) => {
    let child;
    try {
      child = spawn(exe, args, {
        cwd,
        env: env ?? process.env,
        windowsHide: true,
        stdio: ['pipe', 'pipe', 'pipe'],
      });
    } catch {
      resolve({ code: -1, stdout: '', stderr: '', started: false });
      return;
    }

    let stdout = '';
    let stderr = '';
    let settled = false;
    let timer: NodeJS.Timeout | undefined;

    const settle = (result: RunResult): void => {
      if (settled) return;
      settled = true;
      if (timer) clearTimeout(timer);
      resolve(result);
    };

    child.on('error', () => settle({ code: -1, stdout, stderr, started: false }));

    child.stdout?.on('data', (buf: Buffer) => {
      const text = buf.toString('utf8');
      stdout += text;
      onOutput?.(text, 'stdout');
    });
    child.stderr?.on('data', (buf: Buffer) => {
      const text = buf.toString('utf8');
      stderr += text;
      onOutput?.(text, 'stderr');
    });

    child.on('close', (code) => settle({ code: code ?? -1, stdout, stderr, started: true }));

    if (input !== undefined) {
      child.stdin?.write(input);
      child.stdin?.end();
    } else {
      child.stdin?.end();
    }

    if (timeoutMs > 0) {
      timer = setTimeout(() => {
        try {
          child.kill();
        } catch {
          /* ignore */
        }
        settle({ code: -1, stdout, stderr, started: true });
      }, timeoutMs);
    }
  });
}

/** 下载文件到本地，支持进度回调 */
export async function downloadFile(
  url: string,
  dest: string,
  onProgress?: (received: number, total: number) => void,
): Promise<{ ok: boolean; message: string; bytes: number }> {
  const fs = await import('node:fs');
  const path = await import('node:path');

  try {
    const response = await fetch(url, {
      redirect: 'follow',
      headers: { 'User-Agent': 'arcaea-apk-manager' },
    });
    if (!response.ok || !response.body) {
      return { ok: false, message: `HTTP ${response.status} ${response.statusText}`, bytes: 0 };
    }
    const total = Number(response.headers.get('content-length') ?? 0);
    fs.mkdirSync(path.dirname(dest), { recursive: true });

    const fileStream = fs.createWriteStream(dest);
    const reader = response.body.getReader();
    let received = 0;
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      if (value) {
        received += value.byteLength;
        if (!fileStream.write(Buffer.from(value))) {
          await new Promise<void>((resolve) => fileStream.once('drain', () => resolve()));
        }
        onProgress?.(received, total);
      }
    }
    await new Promise<void>((resolve, reject) => {
      fileStream.end(() => resolve());
      fileStream.on('error', reject);
    });
    return { ok: true, message: '下载完成', bytes: received };
  } catch (err) {
    return { ok: false, message: err instanceof Error ? err.message : String(err), bytes: 0 };
  }
}
