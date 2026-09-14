import { useEffect, useState } from 'react';
import type { SongView } from '../shared/types';

export const DIFF_SHORT = ['PST', 'PRS', 'FTR', 'BYD', 'ETR'] as const;
export const DIFF_FULL = ['Past', 'Present', 'Future', 'Beyond', 'Eternal'] as const;
export const DIFF_TONE = ['sky', 'emerald', 'violet', 'rose', 'amber'] as const;

export function diffShort(ratingClass: number): string {
  return DIFF_SHORT[ratingClass] ?? `#${ratingClass}`;
}

export function diffFull(ratingClass: number): string {
  return DIFF_FULL[ratingClass] ?? `自定义难度 ${ratingClass}`;
}

export function formatBytes(bytes: number): string {
  if (!bytes) return '0 B';
  const units = ['B', 'KB', 'MB', 'GB'];
  let value = bytes;
  let unit = 0;
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024;
    unit++;
  }
  return `${value.toFixed(value >= 100 || unit === 0 ? 0 : 1)} ${units[unit]}`;
}

/** 传输速率，例如 "3.4 MB/s" */
export function formatRate(bytesPerSecond: number | undefined): string {
  if (!bytesPerSecond || bytesPerSecond <= 0) return '—';
  return `${formatBytes(bytesPerSecond)}/s`;
}

/** 时长，例如 "1 分 23 秒" */
export function formatDuration(ms: number | undefined): string {
  if (ms === undefined || ms < 0) return '—';
  const totalSeconds = Math.floor(ms / 1000);
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  return minutes > 0 ? `${minutes} 分 ${seconds} 秒` : `${seconds} 秒`;
}

export function songAsset(songId: string, file: string): string {
  return `assets/songs/${songId}/${file}`;
}

export function packBanner(packId: string, hd = false): string {
  return `assets/songs/pack/${hd ? '1080_select_' : 'select_'}${packId}.png`;
}

export function localizedEn(value: unknown): string {
  if (value && typeof value === 'object') {
    const record = value as Record<string, unknown>;
    const en = record.en;
    if (typeof en === 'string') return en;
    const first = Object.values(record).find((v) => typeof v === 'string' && v);
    return typeof first === 'string' ? first : '';
  }
  return '';
}

/* ------------------------------ 图片自动选择 ------------------------------ */

/** 列表缩略图：优先小尺寸，其次任意存在的封面（很多曲目只有 1080 版本） */
const THUMB_ORDER = ['base_256.jpg', '1080_base_256.jpg', 'base.jpg', '1080_base.jpg'];
/** 大图预览：优先高分辨率 */
const JACKET_ORDER = ['1080_base.jpg', 'base.jpg', '1080_base_256.jpg', 'base_256.jpg'];

export function pickSongThumb(view: SongView): string | null {
  const file = THUMB_ORDER.find((name) => view.files.includes(name));
  return file ? songAsset(view.song.id, file) : null;
}

export function pickSongJacket(view: SongView): string | null {
  const file = JACKET_ORDER.find((name) => view.files.includes(name));
  return file ? songAsset(view.song.id, file) : null;
}

/** 曲包横幅：select_<id>.png 缺失时回退到 1080_select_<id>.png */
export function pickPackBanner(
  banners: { select: boolean; hd: boolean },
  packId: string,
): string | null {
  if (banners.select) return packBanner(packId, false);
  if (banners.hd) return packBanner(packId, true);
  return null;
}

/* ---------------------------- 图片预览缓存 ---------------------------- */

const imageCache = new Map<string, string | null>();
const inflight = new Map<string, Promise<string | null>>();

export function loadAssetImage(relPath: string): Promise<string | null> {
  if (imageCache.has(relPath)) return Promise.resolve(imageCache.get(relPath) ?? null);
  const existing = inflight.get(relPath);
  if (existing) return existing;

  const task = window.api.assets
    .readBase64(relPath)
    .then((result) => {
      const url = result ? `data:${result.mime};base64,${result.base64}` : null;
      imageCache.set(relPath, url);
      inflight.delete(relPath);
      return url;
    })
    .catch(() => {
      imageCache.set(relPath, null);
      inflight.delete(relPath);
      return null;
    });

  inflight.set(relPath, task);
  return task;
}

export function invalidateAssetImage(relPath: string): void {
  imageCache.delete(relPath);
}

export function invalidateSongImages(songId: string): void {
  for (const key of [...imageCache.keys()]) {
    if (key.startsWith(`assets/songs/${songId}/`)) imageCache.delete(key);
  }
}

export function useAssetImage(relPath: string | null): string | null {
  const [url, setUrl] = useState<string | null>(relPath ? (imageCache.get(relPath) ?? null) : null);

  useEffect(() => {
    if (!relPath) {
      setUrl(null);
      return;
    }
    let active = true;
    void loadAssetImage(relPath).then((value) => {
      if (active) setUrl(value);
    });
    return () => {
      active = false;
    };
  }, [relPath]);

  return url;
}
