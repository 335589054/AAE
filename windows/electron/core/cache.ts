import fs from 'node:fs';
import { cacheDir, dirStats, ensureDir } from './paths';
import type { CacheInfo, ClearCacheResult } from '../../shared/types';

export function cacheInfo(): CacheInfo {
  const dir = ensureDir(cacheDir());
  const stats = dirStats(dir);
  return { path: dir, bytes: stats.bytes, files: stats.files };
}

export function clearCache(): ClearCacheResult {
  const dir = cacheDir();
  const before = dirStats(dir);
  try {
    fs.rmSync(dir, { recursive: true, force: true });
    ensureDir(dir);
    return { cleared: true, freedBytes: before.bytes };
  } catch (err) {
    return {
      cleared: false,
      freedBytes: 0,
      reason: err instanceof Error ? err.message : String(err),
    };
  }
}
