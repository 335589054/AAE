import { ZipReader } from './zip';
import type { Song, SongDifficulty, SongZipInspection } from '../../shared/types';

/**
 * 从 zip 导入单曲资源。
 *
 * 支持常见打包方式：
 *  1. 直接压缩歌曲目录内容：`base.jpg`、`3.aff`、`base.ogg`…
 *  2. 压缩整个歌曲文件夹：`mysong/base.jpg`…
 *  3. 压缩解包目录的一部分：`assets/songs/mysong/base.jpg`…
 *  4. 标题目录 + 歌曲 id 目录的嵌套打包：`Lost Requiem/lostrequiem/3.aff`…
 *
 * 若压缩包内包含 `songlist` / `songlist.txt` / `songlist.json` / `slst` / `song.json` 这类
 * 单曲元数据片段，会一并解析出来用于预填歌曲信息（id 与曲包仍以用户填写为准）。
 * 若 songlist 声明的背景图（`bg`）也随包提供，会被归到 `assets/img/bg/1080/`（见 [SongZipExtra]）。
 */

const METADATA_BASENAMES = new Set([
  'songlist',
  'songlist.txt',
  'songlist.json',
  'slst',
  'song.json',
  'song-meta.json',
]);

const IGNORED_PATTERNS = [
  /^__MACOSX\//i,
  /(^|\/)\.DS_Store$/i,
  /(^|\/)Thumbs\.db$/i,
  /(^|\/)desktop\.ini$/i,
];

/** 歌曲目录里允许出现的资源扩展名；其它文件（说明文档、脚本等）一律忽略 */
const RESOURCE_EXTENSIONS = new Set([
  'aff', 'ogg', 'opus', 'mp3', 'wav', 'm4a', 'aac', 'flac',
  'jpg', 'jpeg', 'png', 'webp', 'bmp',
]);

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

/** 不属于歌曲目录、需要写到 APK 其它位置的资源（如背景图） */
export interface SongZipExtra {
  /** 相对 APK 根的完整路径，例如 `assets/img/bg/1080/djmax_wagd.jpg` */
  relPath: string;
  data: Buffer;
}

export interface SongZipContent {
  inspection: SongZipInspection;
  files: SongZipFile[];
  /** 需要写到歌曲目录之外的资源（如背景图） */
  extras: SongZipExtra[];
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
  const cleaned = text.replace(/^\uFEFF/, '').trim();
  if (!cleaned) return null;
  const attempts = [cleaned, `{${cleaned.replace(/,\s*$/, '')}}`];
  for (const candidate of attempts) {
    try {
      const parsed = JSON.parse(candidate) as unknown;
      if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) continue;
      const record = parsed as Record<string, unknown>;
      // 兼容三种形态：整包 songlist 的 {"songs":[…] / {"song":{…} / 裸单曲对象
      const songs = record.songs;
      const fromSongs = Array.isArray(songs)
        ? (songs.find((item) => item && typeof item === 'object') as Record<string, unknown> | undefined)
        : undefined;
      const nested = record.song;
      const song =
        fromSongs ?? (nested && typeof nested === 'object' ? (nested as Record<string, unknown>) : record);
      const fields = pickSongFields(song);
      // 没有任何可识别字段时视为解析失败，避免把 {"songs":[]} 之类当成有效元数据
      if (Object.keys(fields).length > 0) return fields;
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
      const rel = item.rel;

      // 只把真正的路径穿越段（`..`）视为不安全；像 `P.S..txt` 这种文件名里的 `..` 不算
      if (!rel || rel === '.' || rel.split('/').some((segment) => segment === '..')) {
        skipped.push(rel || item.raw);
        warnings.push(`跳过了不安全的条目：${rel || item.raw}`);
        continue;
      }

      // 扁平化：取最深层文件名，兼容 `资源` / `<歌曲id>/资源` / `<标题>/<歌曲id>/资源`
      // （后者是社区常见打包方式，如 Lost Requiem.zip）。
      // 超过 3 段一律忽略，避免把 assets/img/bg/… 之类的无关内容吞进歌曲目录。
      const parts = rel.split('/').filter(Boolean);
      if (parts.length > 3) {
        skipped.push(rel);
        warnings.push(`跳过了层级过深的条目：${rel}`);
        continue;
      }
      const flat = parts[parts.length - 1] ?? rel;

      if (METADATA_BASENAMES.has(flat.toLowerCase())) {
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

      // 只接受单曲资源扩展名，避免把 README / 脚本等无关文件写进歌曲目录
      const ext = flat.split('.').pop()?.toLowerCase() ?? '';
      if (!RESOURCE_EXTENSIONS.has(ext)) {
        skipped.push(rel);
        continue;
      }

      const data = reader.readFile(item.raw);
      if (!data) {
        skipped.push(rel);
        continue;
      }
      files.push({ rel: flat, data });
      resources.push(flat);
    }

    // 背景图归类：`<bg>.jpg` 常被打在标题目录下，但游戏只从 assets/img/bg/1080/ 读取背景图，
    // 因此按 songlist 的 bg 字段把同名图片挪到那里（排除常规封面名，避免把封面误当背景图）。
    const extras: SongZipExtra[] = [];
    const bgName = typeof metadata?.bg === 'string' ? metadata.bg.trim() : '';
    if (bgName) {
      const jacketNames = new Set([
        'base.jpg', 'base.png', 'base_256.jpg', 'base_256.png',
        '1080_base.jpg', '1080_base.png', '1080_base_256.jpg', '1080_base_256.png',
      ]);
      const index = files.findIndex((file) => {
        const lower = file.rel.toLowerCase();
        if (jacketNames.has(lower)) return false;
        if (!/\.(jpg|jpeg|png|webp|bmp)$/.test(lower)) return false;
        return lower.replace(/\.[^.]+$/, '') === bgName.toLowerCase();
      });
      if (index >= 0) {
        const [moved] = files.splice(index, 1);
        const ext = moved.rel.split('.').pop()?.toLowerCase() ?? 'jpg';
        extras.push({ relPath: `assets/img/bg/1080/${bgName.toLowerCase()}.${ext}`, data: moved.data });
        const ri = resources.indexOf(moved.rel);
        if (ri >= 0) resources.splice(ri, 1);
      }
    }

    resources.sort();
    files.sort((a, b) => a.rel.localeCompare(b.rel));

    const hasJacket = resources.some((name) => /^(1080_)?base(_256)?\.(jpg|jpeg|png)$/i.test(name));
    const hasChart = resources.some((name) => /^\d+\.aff$/i.test(name));
    const hasAudio = resources.some((name) => /^base\.ogg$/i.test(name) || /^\d+\.ogg$/i.test(name));

    if (!hasJacket) warnings.push('压缩包内没有找到封面（base.jpg / 1080_base.jpg 等）');
    if (!hasChart) warnings.push('压缩包内没有找到谱面（0.aff ~ 4.aff）');
    if (!hasAudio) warnings.push('压缩包内没有找到音频（base.ogg 或 <难度>.ogg）');
    if (files.length === 0 && extras.length === 0) warnings.push('没有可导入的资源文件');

    return {
      files,
      extras,
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
