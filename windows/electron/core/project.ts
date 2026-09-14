import fs from 'node:fs';
import path from 'node:path';
import { ZipReader, ZipWriter } from './zip';
import { readSongZip } from './songzip';
import { readManifest, setPackageName, type ManifestInfo } from './axml';
import { ensureDir, tmpDir } from './paths';
import type {
  ApkInfo,
  DifficultyFileStatus,
  Pack,
  PackView,
  PendingSummary,
  ProjectSnapshot,
  Song,
  SongDifficulty,
  SongView,
} from '../../shared/types';

export const SONGS_ROOT = 'assets/songs/';
export const SONGLIST_PATH = `${SONGS_ROOT}songlist`;
export const PACKLIST_PATH = `${SONGS_ROOT}packlist`;
export const UNLOCKS_PATH = `${SONGS_ROOT}unlocks`;
export const PACK_DIR = `${SONGS_ROOT}pack/`;
export const MANIFEST_PATH = 'AndroidManifest.xml';

export const BASE_AUDIO = 'base.ogg';
/** 这些扩展名本身已是压缩格式，写入时不再 deflate */
const STORE_EXTENSIONS = new Set([
  '.png',
  '.jpg',
  '.jpeg',
  '.ogg',
  '.mp3',
  '.mp4',
  '.webm',
  '.aac',
  '.m4a',
  '.zip',
  '.jar',
  '.so',
]);

/** 签名相关条目在重新打包时会被剔除，随后由 apksigner 重新生成 */
function isSignatureEntry(name: string): boolean {
  if (!name.startsWith('META-INF/')) return false;
  const rest = name.slice('META-INF/'.length);
  if (rest.includes('/')) return false;
  return (
    rest === 'MANIFEST.MF' ||
    /\.(SF|RSA|DSA|EC)$/i.test(rest) ||
    /^CERT\./i.test(rest)
  );
}

type WriteOp = { kind: 'buffer'; data: Buffer } | { kind: 'file'; srcPath: string };

export interface OpenResult {
  snapshot: ProjectSnapshot;
}

export class ApkProject {
  private reader: ZipReader;
  private manifest: ManifestInfo;
  private originalManifestRaw: Buffer;
  private originalSonglist: Song[];
  private originalPacklist: Pack[];
  private originalUnlocks: string;

  private songs: Song[];
  private packs: Pack[];
  private unlocksRaw: string;

  private writes = new Map<string, WriteOp>();
  private deletes = new Set<string>();
  private packageNameOverride: string | null = null;
  /** 改包名时是否一并改写自定义权限名 / Provider 授权名 */
  private rewriteIdentifiers = true;
  /** songId -> 该目录下的文件名 */
  private dirIndex = new Map<string, string[]>();
  /** 距上次成功导出后是否有新的改动 */
  private dirtySinceExport = false;
  private exportedOnce = false;

  readonly apkPath: string;

  private constructor(apkPath: string, reader: ZipReader, manifestRaw: Buffer) {
    this.apkPath = apkPath;
    this.reader = reader;
    this.originalManifestRaw = manifestRaw;
    this.manifest = readManifest(manifestRaw);

    const songlistRaw = reader.readText(SONGLIST_PATH);
    if (songlistRaw === null) {
      reader.close();
      throw new Error(
        '该 APK 的 assets/songs/songlist 不存在。请确认导入的是包含曲目资源的 Arcaea 安装包（完整版或已修改版）。',
      );
    }
    const songlistJson = parseJson(songlistRaw, 'assets/songs/songlist');
    this.originalSonglist = (songlistJson.songs as Song[]) ?? [];

    const packlistRaw = reader.readText(PACKLIST_PATH) ?? '{"packs":[]}';
    const packlistJson = parseJson(packlistRaw, 'assets/songs/packlist');
    this.originalPacklist = (packlistJson.packs as Pack[]) ?? [];

    this.originalUnlocks = reader.readText(UNLOCKS_PATH) ?? '{\n\t"unlocks": []\n}';

    this.songs = clone(this.originalSonglist);
    this.packs = clone(this.originalPacklist);
    this.unlocksRaw = this.originalUnlocks;

    this.buildDirIndex();
  }

  static open(apkPath: string): ApkProject {
    if (!fs.existsSync(apkPath)) throw new Error(`文件不存在：${apkPath}`);
    const reader = ZipReader.open(apkPath);
    try {
      const manifestRaw = reader.readFile(MANIFEST_PATH);
      if (!manifestRaw) throw new Error('APK 中缺少 AndroidManifest.xml');
      return new ApkProject(apkPath, reader, manifestRaw);
    } catch (err) {
      reader.close();
      throw err;
    }
  }

  close(): void {
    this.reader.close();
  }

  private buildDirIndex(): void {
    this.dirIndex.clear();
    for (const name of this.reader.listAll()) {
      if (!name.startsWith(SONGS_ROOT)) continue;
      const rest = name.slice(SONGS_ROOT.length);
      const slash = rest.indexOf('/');
      if (slash <= 0) continue; // songlist / packlist / songlist.txt 等根文件
      const songId = rest.slice(0, slash);
      if (songId === 'pack') continue;
      const file = rest.slice(slash + 1);
      if (!file || file.includes('/')) continue;
      const list = this.dirIndex.get(songId) ?? [];
      list.push(file);
      this.dirIndex.set(songId, list);
    }
  }

  /* --------------------------- 资源读写 --------------------------- */

  private songPrefix(songId: string): string {
    return `${SONGS_ROOT}${songId}/`;
  }

  /** 读取条目：优先待写入内容，其次待删除判断，最后读 APK */
  readEntry(name: string): Buffer | null {
    if (this.deletes.has(name)) return null;
    const write = this.writes.get(name);
    if (write) {
      if (write.kind === 'buffer') return write.data;
      try {
        return fs.readFileSync(write.srcPath);
      } catch {
        return null;
      }
    }
    if (name === SONGLIST_PATH) return Buffer.from(this.serializeSonglist(), 'utf8');
    if (name === PACKLIST_PATH) return Buffer.from(this.serializePacklist(), 'utf8');
    if (name === UNLOCKS_PATH) return Buffer.from(this.unlocksRaw, 'utf8');
    if (name === MANIFEST_PATH && this.packageNameOverride) {
      return setPackageName(this.originalManifestRaw, this.packageNameOverride, {
        rewriteIdentifiers: this.rewriteIdentifiers,
      });
    }
    return this.reader.readFile(name);
  }

  exists(name: string): boolean {
    return this.readEntry(name) !== null;
  }

  listSongFiles(songId: string): string[] {
    const prefix = this.songPrefix(songId);
    const set = new Set(this.dirIndex.get(songId) ?? []);
    for (const deleted of this.deletes) {
      if (deleted.startsWith(prefix)) set.delete(deleted.slice(prefix.length));
    }
    for (const written of this.writes.keys()) {
      if (written.startsWith(prefix)) set.add(written.slice(prefix.length));
    }
    return [...set].sort();
  }

  /** 仅允许改动 assets/ 下的内容，避免误删 dex / so / 资源表 */
  private assertMutable(relPath: string): void {
    if (!relPath.startsWith('assets/')) {
      throw new Error(`出于安全考虑，只允许修改 assets/ 下的文件（收到：${relPath}）`);
    }
    if (isSignatureEntry(relPath)) {
      throw new Error('签名文件由签名步骤自动生成，不能手动修改');
    }
  }

  stageWrite(relPath: string, op: WriteOp): void {
    this.assertMutable(relPath);
    this.deletes.delete(relPath);
    this.writes.set(relPath, op);
    this.dirtySinceExport = true;
  }

  stageDelete(relPath: string): void {
    this.assertMutable(relPath);
    this.writes.delete(relPath);
    this.deletes.add(relPath);
    this.dirtySinceExport = true;
  }

  stageDeleteSongDir(songId: string): void {
    const prefix = this.songPrefix(songId);
    for (const name of this.reader.listAll()) {
      if (name.startsWith(prefix) && !this.deletes.has(name)) {
        this.deletes.add(name);
        this.writes.delete(name);
      }
    }
    for (const name of [...this.writes.keys()]) {
      if (name.startsWith(prefix)) this.writes.delete(name);
    }
    this.dirtySinceExport = true;
  }

  /* --------------------------- 歌曲 / 曲包 --------------------------- */

  getPacks(): Pack[] {
    return this.packs;
  }

  findSong(id: string): Song | undefined {
    return this.songs.find((s) => s.id === id);
  }

  updateSong(id: string, patch: Partial<Song>): void {
    const index = this.songs.findIndex((s) => s.id === id);
    if (index < 0) throw new Error(`找不到歌曲：${id}`);
    const next = { ...this.songs[index], ...patch } as Song;
    if (typeof next.id !== 'string' || !next.id.trim()) throw new Error('歌曲 id 不能为空');
    if (patch.id && patch.id !== id) {
      if (this.songs.some((s) => s.id === patch.id)) {
        throw new Error(`歌曲 id 已存在：${patch.id}`);
      }
      if (this.dirIndex.has(patch.id)) {
        throw new Error(`APK 中已存在同名目录 assets/songs/${patch.id}/，请换一个 id`);
      }
      // 目录名必须与 id 一致，因此重命名时同步搬运整个歌曲目录
      const files = this.listSongFiles(id);
      const oldPrefix = this.songPrefix(id);
      const newPrefix = this.songPrefix(patch.id);
      for (const file of files) {
        const data = this.readEntry(oldPrefix + file);
        if (data) this.stageWrite(newPrefix + file, { kind: 'buffer', data });
        this.stageDelete(oldPrefix + file);
      }
      this.dirIndex.set(patch.id, [...files]);
      this.dirIndex.delete(id);
    }
    this.songs[index] = next;
    this.dirtySinceExport = true;
  }

  addSong(song: Song): void {
    if (!song.id || !song.id.trim()) throw new Error('歌曲 id 不能为空');
    if (this.songs.some((s) => s.id === song.id)) throw new Error(`歌曲 id 已存在：${song.id}`);
    if (this.dirIndex.has(song.id)) {
      throw new Error(`APK 中已存在同名目录 assets/songs/${song.id}/，请换一个 id`);
    }
    if (!Array.isArray(song.difficulties)) song.difficulties = [];
    this.songs.push(clone(song));
    this.dirtySinceExport = true;
  }

  removeSong(id: string): void {
    const index = this.songs.findIndex((s) => s.id === id);
    if (index < 0) throw new Error(`找不到歌曲：${id}`);
    this.songs.splice(index, 1);
    this.stageDeleteSongDir(id);
    this.dirtySinceExport = true;
  }

  reorderSongs(from: number, to: number): void {
    if (from < 0 || from >= this.songs.length) return;
    const target = Math.max(0, Math.min(this.songs.length - 1, to));
    const [item] = this.songs.splice(from, 1);
    this.songs.splice(target, 0, item);
    this.dirtySinceExport = true;
  }

  updatePack(id: string, patch: Partial<Pack>): void {
    const index = this.packs.findIndex((p) => p.id === id);
    if (index < 0) throw new Error(`找不到曲包：${id}`);
    const next = { ...this.packs[index], ...patch } as Pack;
    if (patch.id && patch.id !== id) {
      if (this.packs.some((p) => p.id === patch.id)) throw new Error(`曲包 id 已存在：${patch.id}`);
      // 曲包 id 变更时同步歌曲的 set 引用，避免歌曲丢失归属
      for (const song of this.songs) {
        if (song.set === id) song.set = patch.id;
      }
    }
    this.packs[index] = next;
    this.dirtySinceExport = true;
  }

  addPack(pack: Pack): void {
    if (!pack.id || !pack.id.trim()) throw new Error('曲包 id 不能为空');
    if (this.packs.some((p) => p.id === pack.id)) throw new Error(`曲包 id 已存在：${pack.id}`);
    this.packs.push(clone(pack));
    this.dirtySinceExport = true;
  }

  removePack(id: string, moveSongsTo?: string): void {
    const index = this.packs.findIndex((p) => p.id === id);
    if (index < 0) throw new Error(`找不到曲包：${id}`);
    const affected = this.songs.filter((s) => s.set === id);
    if (moveSongsTo) {
      if (!this.packs.some((p) => p.id === moveSongsTo)) {
        throw new Error(`目标曲包不存在：${moveSongsTo}`);
      }
      for (const song of affected) song.set = moveSongsTo;
    } else {
      for (const song of affected) this.removeSong(song.id);
    }
    this.packs.splice(index, 1);
    this.dirtySinceExport = true;
  }

  setPackageNameOverride(pkg: string | null, rewriteIdentifiers = true): void {
    const next = pkg && pkg.trim() ? pkg.trim() : null;
    if (next === this.packageNameOverride && rewriteIdentifiers === this.rewriteIdentifiers) return;
    this.packageNameOverride = next;
    this.rewriteIdentifiers = rewriteIdentifiers;
    this.dirtySinceExport = true;
  }

  getPackageNameOverride(): string | null {
    return this.packageNameOverride;
  }

  getRewriteIdentifiers(): boolean {
    return this.rewriteIdentifiers;
  }

  originalPackageName(): string {
    return this.manifest.packageName;
  }

  /** 导出成功后调用，用于解除「清除缓存」的前置限制 */
  markExported(): void {
    this.dirtySinceExport = false;
    this.exportedOnce = true;
  }

  isDirtySinceExport(): boolean {
    return this.dirtySinceExport;
  }

  hasExportedOnce(): boolean {
    return this.exportedOnce;
  }

  /* --------------------------- 序列化 --------------------------- */

  private serializeSonglist(): string {
    return `${JSON.stringify({ songs: this.songs }, null, 2)}\n`;
  }

  private serializePacklist(): string {
    return `${JSON.stringify({ packs: this.packs }, null, 2)}\n`;
  }

  /* --------------------------- 快照 --------------------------- */

  getApkInfo(): ApkInfo {
    const stat = fs.statSync(this.apkPath);
    return {
      path: this.apkPath,
      fileName: path.basename(this.apkPath),
      size: stat.size,
      packageName: this.packageNameOverride ?? this.manifest.packageName,
      originalPackageName: this.manifest.packageName,
      versionName: this.manifest.versionName ?? '-',
      versionCode: this.manifest.versionCode ?? -1,
      minSdk: this.manifest.minSdk,
      targetSdk: this.manifest.targetSdk,
      entryCount: this.reader.entries.size,
      hasSongAssets: true,
      hasNativeLibs: this.reader.listAll().some((n) => n.startsWith('lib/') && n.endsWith('.so')),
      songDirs: [...this.dirIndex.keys()].sort(),
      packBanners: this.reader.list(PACK_DIR).map((n) => n.slice(PACK_DIR.length)),
    };
  }

  private buildSongView(song: Song, index: number): SongView {
    const files = this.listSongFiles(song.id);
    const fileSet = new Set(files);
    const charts: DifficultyFileStatus[] = (song.difficulties ?? []).map((d) => ({
      ratingClass: d.ratingClass,
      hasChart: fileSet.has(`${d.ratingClass}.aff`),
      hasAudio: fileSet.has(`${d.ratingClass}.ogg`),
    }));
    return {
      song,
      index,
      files,
      dirExists: files.length > 0,
      jackets: {
        base: fileSet.has('base.jpg'),
        base256: fileSet.has('base_256.jpg'),
        hd: fileSet.has('1080_base.jpg'),
        hd256: fileSet.has('1080_base_256.jpg'),
      },
      hasBaseAudio: fileSet.has(BASE_AUDIO),
      charts,
    };
  }

  snapshot(): ProjectSnapshot {
    const apk = this.getApkInfo();
    const songIds = new Set(this.songs.map((s) => s.id));
    const packIds = new Set(this.packs.map((p) => p.id));

    const packViews: PackView[] = this.packs.map((pack, index) => ({
      pack,
      index,
      songCount: this.songs.filter((s) => s.set === pack.id).length,
      banners: {
        select: this.exists(`${PACK_DIR}select_${pack.id}.png`),
        hd: this.exists(`${PACK_DIR}1080_select_${pack.id}.png`),
      },
    }));

    const songViews = this.songs.map((song, index) => this.buildSongView(song, index));

    const warnings: string[] = [];
    const notes: string[] = [];
    for (const dir of apk.songDirs) {
      if (!songIds.has(dir) && dir !== 'tutorial') {
        notes.push(`目录 assets/songs/${dir}/ 存在，但没有对应的 songlist 条目（资源未被使用）`);
      }
    }
    for (const view of songViews) {
      if (!view.dirExists) {
        warnings.push(`歌曲「${view.song.id}」在 songlist 中有条目，但没有对应目录（游戏中不可用）`);
        continue;
      }
      if (!view.jackets.base && !view.jackets.hd) {
        notes.push(`「${view.song.id}」缺少封面（base.jpg 与 1080_base.jpg 均不存在）`);
      }
      if (!view.jackets.base256 && !view.jackets.hd256) {
        notes.push(`「${view.song.id}」缺少缩略封面（base_256.jpg 与 1080_base_256.jpg 均不存在）`);
      }

      let playableCount = 0;
      for (const chart of view.charts) {
        const difficulty = (view.song.difficulties ?? []).find(
          (d) => d.ratingClass === chart.ratingClass,
        );
        const playable = (difficulty?.rating ?? 0) > 0;
        if (playable) playableCount++;
        if (!chart.hasChart && playable) {
          notes.push(
            `「${view.song.id}」难度 ${chart.ratingClass}（定数 ${difficulty?.rating}）缺少谱面 ${chart.ratingClass}.aff`,
          );
        }
        if (difficulty?.audioOverride && !chart.hasAudio) {
          warnings.push(
            `「${view.song.id}」难度 ${chart.ratingClass} 启用了 audioOverride 但缺少音频文件 ${chart.ratingClass}.ogg`,
          );
        }
      }

      const hasOverride = (view.song.difficulties ?? []).some((d) => d.audioOverride);
      if (!view.hasBaseAudio && !hasOverride && playableCount > 0) {
        warnings.push(
          `「${view.song.id}」没有整曲音频 base.ogg，也没有任何启用 audioOverride 的难度，播放时将没有声音`,
        );
      }
      if (view.song.set && !packIds.has(view.song.set)) {
        warnings.push(`「${view.song.id}」的曲包 set="${view.song.set}" 在 packlist 中不存在，歌曲不会出现在任何曲包中`);
      }
      if (view.song.bg && !this.exists(`assets/img/bg/1080/${view.song.bg}.jpg`)) {
        notes.push(`「${view.song.id}」引用的背景 assets/img/bg/1080/${view.song.bg}.jpg 不存在，游戏内可能使用默认背景`);
      }
    }
    for (const packView of packViews) {
      if (!packView.banners.select && !packView.banners.hd) {
        warnings.push(
          `曲包「${packView.pack.id}」缺少横幅（assets/songs/pack/select_${packView.pack.id}.png 与 1080_select_${packView.pack.id}.png 均不存在），选曲界面会显示异常`,
        );
      } else if (!packView.banners.select) {
        notes.push(
          `曲包「${packView.pack.id}」只提供了高清横幅 1080_select_${packView.pack.id}.png，建议再补一份 select_${packView.pack.id}.png`,
        );
      }
      if (packView.songCount === 0) {
        // 实测结论（对照原始 mod 与改包后的 APK）：extend 包可以为空，
        // 但普通包一旦为空，游戏在打开歌单时会直接闪退（原生 std::exception）。
        // 原始 mod 里 lowest/slow/slow2/extra 都是空的，但它们都带 "is_extend_pack": true。
        if (packView.pack.is_extend_pack === true) {
          notes.push(`曲包「${packView.pack.id}」下没有任何歌曲（它标记为 extend 包，保持为空是安全的）`);
        } else {
          warnings.push(
            `曲包「${packView.pack.id}」下没有任何歌曲，且它不是 extend 包（缺少 "is_extend_pack": true）` +
              `——这会导致游戏打开歌单时直接闪退。请三选一：给该曲包添加歌曲 / 删除该曲包 / 给它加上 "is_extend_pack": true。`,
          );
        }
      }
    }

    const items: string[] = [];
    const songlistDirty = !deepEqual(this.songs, this.originalSonglist);
    const packlistDirty = !deepEqual(this.packs, this.originalPacklist);
    const unlocksDirty = this.unlocksRaw !== this.originalUnlocks;
    if (songlistDirty) items.push('歌曲列表（songlist）已修改');
    if (packlistDirty) items.push('曲包列表（packlist）已修改');
    if (unlocksDirty) items.push('unlocks 已修改');
    if (this.packageNameOverride) {
      items.push(
        `包名将改为 ${this.packageNameOverride}` +
          (this.rewriteIdentifiers
            ? '（同时改写自定义权限名与 Provider 授权名）'
            : '（保留原有权限名与授权名，与原版共存时可能安装冲突）'),
      );
    }
    for (const name of this.writes.keys()) items.push(`写入资源：${name}`);
    for (const name of this.deletes) items.push(`删除资源：${name}`);

    const pending: PendingSummary = {
      total:
        (songlistDirty ? 1 : 0) +
        (packlistDirty ? 1 : 0) +
        (unlocksDirty ? 1 : 0) +
        this.writes.size +
        this.deletes.size,
      jsonChanges: (songlistDirty ? 1 : 0) + (packlistDirty ? 1 : 0) + (unlocksDirty ? 1 : 0),
      assetWrites: this.writes.size,
      assetDeletes: this.deletes.size,
      packageChanged: Boolean(this.packageNameOverride),
      items,
    };

    return {
      apk,
      packs: packViews,
      songs: songViews,
      unlocksRaw: this.unlocksRaw,
      pending,
      warnings: warnings.slice(0, 200),
      notes: notes.slice(0, 500),
      dirtySinceExport: this.dirtySinceExport,
      exportedOnce: this.exportedOnce,
    };
  }

  /* --------------------------- 导出 --------------------------- */

  /**
   * 重新打包 APK。
   * 未改动的条目走原始字节直通，因此大体积 APK 也能快速完成。
   */
  exportUnsigned(outPath: string, onProgress?: (message: string, percent?: number) => void): void {
    ensureDir(path.dirname(outPath));
    ensureDir(tmpDir());

    const overrides = new Map<string, Buffer>();
    overrides.set(SONGLIST_PATH, Buffer.from(this.serializeSonglist(), 'utf8'));
    overrides.set(PACKLIST_PATH, Buffer.from(this.serializePacklist(), 'utf8'));
    overrides.set(UNLOCKS_PATH, Buffer.from(this.unlocksRaw, 'utf8'));
    if (this.packageNameOverride) {
      onProgress?.('正在修改 AndroidManifest 包名…', 2);
      overrides.set(
        MANIFEST_PATH,
        setPackageName(this.originalManifestRaw, this.packageNameOverride, {
          rewriteIdentifiers: this.rewriteIdentifiers,
        }),
      );
    } else {
      overrides.set(MANIFEST_PATH, this.originalManifestRaw);
    }
    for (const [name, op] of this.writes) {
      const data = op.kind === 'buffer' ? op.data : fs.readFileSync(op.srcPath);
      overrides.set(name, data);
    }

    const writer = new ZipWriter(outPath);
    try {
      const entries = [...this.reader.entries.values()];
      const done = new Set<string>();
      let index = 0;
      for (const entry of entries) {
        index++;
        if (index % 200 === 0) {
          onProgress?.(
            `正在重新打包（${index}/${entries.length}）…`,
            5 + (index / entries.length) * 75,
          );
        }
        if (this.deletes.has(entry.name)) {
          done.add(entry.name);
          continue;
        }
        if (isSignatureEntry(entry.name)) {
          done.add(entry.name);
          continue;
        }
        const override = overrides.get(entry.name);
        if (override) {
          writer.addBuffer(entry.name, override, {
            compress: shouldCompress(entry.name),
            dosTime: entry.dosTime,
            dosDate: entry.dosDate,
            externalAttrs: entry.externalAttrs,
          });
          done.add(entry.name);
          continue;
        }
        writer.addRawEntry(this.reader, entry);
      }

      for (const [name, data] of overrides) {
        if (done.has(name)) continue;
        if (this.deletes.has(name)) continue;
        if (isSignatureEntry(name)) continue;
        writer.addBuffer(name, data, { compress: shouldCompress(name) });
      }

      onProgress?.('正在写入中央目录…', 85);
      writer.finish();
    } catch (err) {
      writer.abort();
      throw err;
    }
  }
}

function shouldCompress(name: string): boolean {
  const ext = path.extname(name).toLowerCase();
  if (STORE_EXTENSIONS.has(ext)) return false;
  return true;
}

function parseJson(text: string, label: string): Record<string, unknown> {
  try {
    return JSON.parse(text) as Record<string, unknown>;
  } catch (err) {
    throw new Error(
      `${label} 不是合法 JSON（${err instanceof Error ? err.message : String(err)}）。` +
        '请先修复该文件或在文本编辑器中另存为 UTF-8 无 BOM 的合法 JSON。',
    );
  }
}

function clone<T>(value: T): T {
  return JSON.parse(JSON.stringify(value)) as T;
}

function deepEqual(a: unknown, b: unknown): boolean {
  return stableStringify(a) === stableStringify(b);
}

function stableStringify(value: unknown): string {
  if (value === null || typeof value !== 'object') return JSON.stringify(value) ?? 'null';
  if (Array.isArray(value)) return `[${value.map(stableStringify).join(',')}]`;
  const obj = value as Record<string, unknown>;
  const keys = Object.keys(obj).sort();
  return `{${keys.map((k) => `${JSON.stringify(k)}:${stableStringify(obj[k])}`).join(',')}}`;
}

/** 新建歌曲时的默认模板，字段取自样本中实际存在的条目 */
export function createSongTemplate(id: string, packId: string): Song {
  const difficulties: SongDifficulty[] = [0, 1, 2, 3].map((ratingClass) => ({
    ratingClass,
    chartDesigner: '',
    jacketDesigner: '',
    rating: 1,
    ratingPlus: false,
  }));
  return {
    id,
    title_localized: { en: id },
    artist: '',
    bpm: '180',
    bpm_base: 180,
    set: packId,
    purchase: '',
    audioPreview: 0,
    audioPreviewEnd: 18285,
    side: 1,
    bg: 'base',
    date: Math.floor(Date.now() / 1000),
    version: '1.0',
    difficulties,
  };
}

export interface CreateSongInput {
  id: string;
  setId: string;
  title?: string;
  /** 可选：同时从该 zip 导入资源，并采用包内的 songlist/slst/song.json 元数据 */
  zipPath?: string;
}

/**
 * 新增歌曲（可选同时导入资源 zip）。
 * 放在 core 层是为了让 IPC 与自检共用同一段逻辑。
 */
export function createSongWithResources(project: ApkProject, input: CreateSongInput): Song {
  const id = String(input.id ?? '').trim();
  if (!/^[A-Za-z0-9_-]+$/.test(id)) {
    throw new Error('歌曲 id 只能包含字母、数字、下划线与连字符（会作为目录名与 zip 内路径）');
  }
  if (!project.getPacks().some((pack) => pack.id === input.setId)) {
    throw new Error(`曲包不存在：${input.setId}。请先创建曲包。`);
  }

  // 先读取 zip，避免资源有问题时留下半成品条目
  const zipContent = input.zipPath ? readSongZip(String(input.zipPath)) : null;

  const song = createSongTemplate(id, input.setId);
  const metadata = zipContent?.inspection.metadata;
  if (metadata) {
    const merged: Partial<Song> = { ...metadata };
    // id 与所属曲包仍以用户填写为准
    delete merged.id;
    delete merged.set;
    Object.assign(song, merged);
  }
  if (input.title) {
    song.title_localized = { ...(song.title_localized ?? {}), en: input.title };
  }

  project.addSong(song);
  if (zipContent) {
    for (const file of zipContent.files) {
      project.stageWrite(`${SONGS_ROOT}${id}/${file.rel}`, { kind: 'buffer', data: file.data });
    }
  }
  return song;
}
