import { ZipReader } from './zip';
import type { Song, SongDifficulty, SongZipInspection } from '../../shared/types';

/**
 * 从 zip 导入单曲资源。
 *
 * 支持三种常见打包方式：
 *  1. 直接压缩歌曲目录内容：`base.jpg`、`3.aff`、`base.ogg`…
 *  2. 压缩整个歌曲文件夹：`mysong/base.jpg`…
 *  3. 压缩解包目录的一部分：`assets/songs/mysong/base.jpg`…
 *
 * 若压缩包内包含 `songlist` / `songlist.txt` / `slst` / `song.json` 这类
 * 单曲元数据片段，会一并解析出来用于预填歌曲信息（id 与曲包仍以用户填写为准）。
 */

const METADATA_BASENAMES = new Set(['songlist', 'songlist.txt', 'slst', 'song.json', 'song-meta.json']);

const IGNORED_PATTERNS = [
  /^__MACOSX\//i,
  /(^|\/)\.DS_Store$/i,
  /(^|\/)Thumbs\.db$/i,
  /(^|\/)desktop\.ini$/i,
];

const SONG_FIELDS = [
  'title_localized',
  'artist',
  'bpm',
  'bpm_base',
  'side',
  'bg',
  'version',
  'date',
  'audioPreview',
  'audioPreviewEnd',
  'purchase',
  'source_localized',
] as const;

const DIFFICULTY_FIELDS = [
  'ratingClass',
  'rating',
  'ratingPlus',
  'chartDesigner',
  'jacketDesigner',
  'audioOverride',
  'title_localized',
  'bpm',
  'bpm_base',
] as const;

export interface SongZipFile {
  /** 相对 assets/songs/<id>/ 的路径（扁平） */
  rel: string;
  data: Buffer;
}

export interface SongZipContent {
  inspection: SongZipInspection;
  files: SongZipFile[];
}

function isIgnored(path: string): boolean {
  return IGNORED_PATTERNS.some((pattern) => pattern.test(path));
}

/** 去掉 assets/songs/<folder>/ 前缀并统一分隔符 */
function normalizePath(entry: string): string {
  const cleaned = entry.replace(/\\/g, '/').replace(/^\/+/, '');
  const match = /^assets\/songs\/[^/]+\/(.+)$/.exec(cleaned);
  return match ? match[1] : cleaned;
}

function pickSongFields(source: Record<string, unknown>): Partial<Song> {
  const out: Record<string, unknown> = {};
  for (const key of SONG_FIELDS) {
    if (source[key] !== undefined) out[key] = source[key];
  }
  if (Array.isArray(source.difficulties)) {
    out.difficulties = (source.difficulties as Array<Record<string, unknown>>)
      .filter((item) => item && typeof item === 'object')
      .map((item) => {
        const difficulty: Record<string, unknown> = {};
        for (const key of DIFFICULTY_FIELDS) {
          if (item[key] !== undefined) difficulty[key] = item[key];
        }
        return difficulty as unknown as SongDifficulty;
      })
      .filter((item) => typeof item.ratingClass === 'number')
      .sort((a, b) => a.ratingClass - b.ratingClass);
  }
  return out as Partial<Song>;
}

/** 解析单曲元数据片段：既支持标准 JSON，也支持 sonlist/slst 那种“裸片段”写法 */
export function parseSongFragment(text: string): Partial<Song> | null {
  let cleaned = text.replace(/^\uFEFF/, '').trim();
  if (!cleaned) return null;
  const attempts = [cleaned, `{${cleaned.replace(/,\s*$/, '')}}`];
  for (const candidate of attempts) {
    try {
      const parsed = JSON.parse(candidate) as unknown;
      if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
        return pickSongFields(parsed as Record<string, unknown>);
      }
    } catch {
      /* 尝试下一种形式 */
    }
  }
  return null;
}

export function readSongZip(zipPath: string): SongZipContent {
  const reader = ZipReader.open(zipPath);
  try {
    const allEntries = reader.listAll().filter((name) => !isIgnored(name));
    if (allEntries.length === 0) {
      throw new Error('压缩包是空的（或只包含 __MACOSX 之类的系统文件）');
    }

    const normalized = allEntries.map((name) => ({ raw: name, rel: normalizePath(name) }));

    // 计算公共一级目录：只有「所有条目都带同一层目录、且没有根级文件」时才剥离
    const firstSegments = new Set(
      normalized.filter((item) => item.rel.includes('/')).map((item) => item.rel.split('/')[0]),
    );
    const hasRootFiles = normalized.some((item) => !item.rel.includes('/'));
    const root = !hasRootFiles && firstSegments.size === 1 ? [...firstSegments][0] : null;

    const files: SongZipFile[] = [];
    const resources: string[] = [];
    const skipped: string[] = [];
    let metadata: Partial<Song> | null = null;
    let metadataSource: string | null = null;
    const warnings: string[] = [];

    for (const item of normalized) {
      let rel = item.rel;
      if (root && rel.startsWith(`${root}/`)) rel = rel.slice(root.length + 1);

      const basename = rel.split('/').pop() ?? rel;

      if (!rel || rel === '.' || rel.includes('..')) {
        skipped.push(rel || item.raw);
        warnings.push(`跳过了不安全的条目：${rel || item.raw}`);
        continue;
      }

      if (METADATA_BASENAMES.has(basename.toLowerCase())) {
        if (!metadata) {
          const text = reader.readText(item.raw);
          if (text) {
            const parsed = parseSongFragment(text);
            if (parsed) {
              metadata = parsed;
              metadataSource = rel;
            }
          }
        }
        skipped.push(rel);
        continue;
      }

      if (rel.includes('/')) {
        // 只接受扁平结构，避免把不确定的目录层级写进歌曲目录
        skipped.push(rel);
        warnings.push(`跳过了带子目录的条目：${rel}（仅支持扁平的歌曲目录结构）`);
        continue;
      }

      const data = reader.readFile(item.raw);
      if (!data) {
        skipped.push(rel);
        continue;
      }
      files.push({ rel, data });
      resources.push(rel);
    }

    resources.sort();
    files.sort((a, b) => a.rel.localeCompare(b.rel));

    const hasJacket = resources.some((name) => /^(1080_)?base(_256)?\.(jpg|jpeg|png)$/i.test(name));
    const hasChart = resources.some((name) => /^\d+\.aff$/i.test(name));
    const hasAudio = resources.some((name) => /^base\.ogg$/i.test(name) || /^\d+\.ogg$/i.test(name));

    if (!hasJacket) warnings.push('压缩包内没有找到封面（base.jpg / 1080_base.jpg 等）');
    if (!hasChart) warnings.push('压缩包内没有找到谱面（0.aff ~ 4.aff）');
    if (!hasAudio) warnings.push('压缩包内没有找到音频（base.ogg 或 <难度>.ogg）');
    if (files.length === 0) warnings.push('没有可导入的资源文件');

    return {
      files,
      inspection: {
        entryCount: allEntries.length,
        root,
        resources,
        skipped,
        metadata,
        metadataSource,
        hasJacket,
        hasChart,
        hasAudio,
        warnings,
      },
    };
  } finally {
    reader.close();
  }
}

export function inspectSongZip(zipPath: string): SongZipInspection {
  return readSongZip(zipPath).inspection;
}
