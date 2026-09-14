import { app } from 'electron';
import fs from 'node:fs';
import path from 'node:path';

/** 工具自带的第三方资源目录（与「缓存」区分，清除缓存不会删除） */
export function toolsDir(): string {
  return path.join(app.getPath('userData'), 'tools');
}

/** 缓存目录：预览解压的临时文件、未签名中间产物等 */
export function cacheDir(): string {
  return path.join(app.getPath('userData'), 'cache');
}

export function tmpDir(): string {
  return path.join(cacheDir(), 'tmp');
}

/** 自动生成的签名密钥默认位置 */
export function keystoreDir(): string {
  return path.join(app.getPath('userData'), 'keystore');
}

export function ensureDir(dir: string): string {
  fs.mkdirSync(dir, { recursive: true });
  return dir;
}

/** 递归统计目录大小与文件数 */
export function dirStats(dir: string): { bytes: number; files: number } {
  let bytes = 0;
  let files = 0;
  const walk = (current: string): void => {
    let items: fs.Dirent[];
    try {
      items = fs.readdirSync(current, { withFileTypes: true });
    } catch {
      return;
    }
    for (const item of items) {
      const full = path.join(current, item.name);
      if (item.isDirectory()) walk(full);
      else if (item.isFile()) {
        files++;
        try {
          bytes += fs.statSync(full).size;
        } catch {
          /* ignore */
        }
      }
    }
  };
  walk(dir);
  return { bytes, files };
}

export function uniqueTempFile(prefix: string, ext = ''): string {
  ensureDir(tmpDir());
  const name = `${prefix}-${Date.now()}-${Math.random().toString(36).slice(2, 8)}${ext}`;
  return path.join(tmpDir(), name);
}
