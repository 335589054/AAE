/**
 * 主进程与渲染进程共享的类型定义（仅类型，无运行时导出）。
 * 这里同时充当 IPC 契约：preload 暴露的 API 必须与 RendererApi 完全一致。
 */

/* ------------------------------- 前置资源 ------------------------------- */

export type PrereqId = 'java' | 'adb' | 'apksigner' | 'apktool';

export interface PrereqStatus {
  id: PrereqId;
  name: string;
  /** 是否为核心流程必需（apktool 为可选） */
  required: boolean;
  found: boolean;
  path?: string;
  version?: string;
  /** system = 复用本机已安装；tools = 下载到工具目录 */
  source?: 'system' | 'tools';
  /** 未找到时的安装提示 */
  hint?: string;
  /** 可一键下载的直链 */
  downloadUrl?: string;
  canAutoDownload: boolean;
  /** 该前置资源的作用说明 */
  purpose: string;
}

/* --------------------------------- 数据模型 -------------------------------- */

export interface Localized {
  [locale: string]: string | undefined;
}

export interface SongDifficulty {
  ratingClass: number;
  rating?: number;
  ratingPlus?: boolean;
  chartDesigner?: string;
  jacketDesigner?: string;
  title_localized?: Localized;
  bpm?: string;
  bpm_base?: number;
  audioOverride?: boolean;
  [key: string]: unknown;
}

export interface Song {
  id: string;
  title_localized?: Localized;
  artist?: string;
  bpm?: string;
  bpm_base?: number;
  set?: string;
  purchase?: string;
  audioPreview?: number;
  audioPreviewEnd?: number;
  side?: number;
  bg?: string;
  date?: number;
  version?: string;
  source_localized?: Localized;
  difficulties?: SongDifficulty[];
  [key: string]: unknown;
}

export interface Pack {
  id: string;
  section?: string;
  is_extend_pack?: boolean;
  is_active_extend_pack?: boolean;
  custom_banner?: boolean;
  cutout_pack_image?: boolean;
  small_pack_image?: boolean;
  plus_character?: number;
  name_localized?: Localized;
  description_localized?: Localized;
  [key: string]: unknown;
}

/** 难度 0..4 与谱面/独立音频文件名一一对应 */
export const DIFFICULTY_NAMES = ['PST', 'PRS', 'FTR', 'BYD', 'ETR'] as const;
export const DIFFICULTY_FULL_NAMES = ['Past', 'Present', 'Future', 'Beyond', 'Eternal'] as const;

/* --------------------------------- 工程视图 -------------------------------- */

export interface DifficultyFileStatus {
  ratingClass: number;
  hasChart: boolean;
  hasAudio: boolean;
}

export interface SongView {
  song: Song;
  /** 在 songlist.songs 中的下标 */
  index: number;
  /** 目录 assets/songs/<id>/ 下的文件名列表（含未识别的文件） */
  files: string[];
  dirExists: boolean;
  jackets: {
    base: boolean;
    base256: boolean;
    hd: boolean;
    hd256: boolean;
  };
  hasBaseAudio: boolean;
  charts: DifficultyFileStatus[];
}

export interface PackView {
  pack: Pack;
  index: number;
  songCount: number;
  banners: { select: boolean; hd: boolean };
}

export interface ApkInfo {
  path: string;
  fileName: string;
  size: number;
  /** 当前将产出的包名（若设置了改包名则为新包名） */
  packageName: string;
  /** 导入时的原始包名，改包名时用于展示与生成自定义标识符 */
  originalPackageName: string;
  versionName: string;
  versionCode: number;
  minSdk?: number;
  targetSdk?: number;
  entryCount: number;
  hasSongAssets: boolean;
  hasNativeLibs: boolean;
  /** zip 中存在的曲目目录名（用于发现孤儿目录） */
  songDirs: string[];
  packBanners: string[];
}

export interface PendingSummary {
  total: number;
  jsonChanges: number;
  assetWrites: number;
  assetDeletes: number;
  packageChanged: boolean;
  items: string[];
}

export interface ProjectSnapshot {
  apk: ApkInfo;
  packs: PackView[];
  songs: SongView[];
  unlocksRaw: string;
  pending: PendingSummary;
  /** 会导致曲目异常的真问题：条目与资源不匹配、曲包缺横幅等 */
  warnings: string[];
  /** 提示性信息：缺少可选资源、定义了但没有谱面的难度等，通常可以忽略 */
  notes: string[];
  /** 自上次成功导出后是否又产生了新的改动（清除缓存的前置条件） */
  dirtySinceExport: boolean;
  /** 本次会话中是否至少成功导出过一次 */
  exportedOnce: boolean;
}

/* --------------------------------- 导出与签名 ------------------------------- */

export interface KeystoreSelection {
  /** auto = 使用工具内置自动生成的调试签名；existing = 使用用户指定的 keystore */
  mode: 'auto' | 'existing';
  path?: string;
  storePassword?: string;
  keyAlias?: string;
  keyPassword?: string;
}

export interface BuildOptions {
  outPath: string;
  /** 为空则保持原包名 */
  packageName?: string;
  /**
   * 改包名时是否同时改写清单里以原包名为前缀的自定义权限名与 Provider 授权名。
   * 不打开的话，与原版 App 同时安装会报 INSTALL_FAILED_DUPLICATE_PERMISSION /
   * INSTALL_FAILED_CONFLICTING_PROVIDER。
   */
  rewriteIdentifiers?: boolean;
  keystore: KeystoreSelection;
  /** 是否保留未签名的中间产物（默认 false，导出目录下不保留） */
  keepUnsigned?: boolean;
  /** 是否在导出完成后清理缓存目录中的中间文件 */
  cleanupTemp?: boolean;
}

export interface BuildResult {
  outPath: string;
  signed: boolean;
  unsignedPath?: string;
  packageName: string;
  size: number;
  durationMs: number;
  log: string[];
}

/* ----------------------------------- ADB ---------------------------------- */

export interface AdbDevice {
  serial: string;
  state: string;
  model?: string;
}

/* --------------------------------- 原生菜单 -------------------------------- */

export interface ContextMenuItem {
  id: string;
  label: string;
  enabled?: boolean;
  /** 使用危险色显示（仅作提示，由各平台渲染） */
  danger?: boolean;
  /** 在此项之前插入分隔线 */
  separatorBefore?: boolean;
}

/* ------------------------------- 资源 zip 导入 ------------------------------ */

export interface SongZipInspection {
  entryCount: number;
  /** 被剥离的公共目录前缀，可能为 null */
  root: string | null;
  /** 将被导入到 assets/songs/<id>/ 的相对路径（扁平） */
  resources: string[];
  skipped: string[];
  /** 从包内的 songlist / slst / song.json 解析出的元数据 */
  metadata: Partial<Song> | null;
  metadataSource: string | null;
  hasJacket: boolean;
  hasChart: boolean;
  hasAudio: boolean;
  warnings: string[];
}

/* ---------------------------------- 缓存 ---------------------------------- */
export interface CacheInfo {
  path: string;
  bytes: number;
  files: number;
}

export interface ClearCacheResult {
  cleared: boolean;
  freedBytes: number;
  reason?: string;
}

/* --------------------------------- 进度事件 -------------------------------- */

export interface ProgressEvent {
  scope: string;
  phase: string;
  message: string;
  percent?: number;
  /** 当前阶段已传输字节数 */
  bytesSent?: number;
  /** 当前阶段总字节数 */
  totalBytes?: number;
  /** 实时速率（字节/秒） */
  bytesPerSecond?: number;
  /** 当前阶段已用时（毫秒） */
  elapsedMs?: number;
  /** true 表示数值来自兜底估算（设备端文件大小）而非 adb 自身进度 */
  estimated?: boolean;
}

/* ---------------------------------- API ---------------------------------- */

export interface FileFilter {
  name: string;
  extensions: string[];
}

export interface RendererApi {
  prereq: {
    check(): Promise<PrereqStatus[]>;
    download(id: PrereqId): Promise<{ ok: boolean; message: string; status: PrereqStatus[] }>;
  };
  dialog: {
    openApk(): Promise<string | null>;
    openFile(title: string, filters: FileFilter[]): Promise<string | null>;
    saveFile(defaultFileName: string, filters: FileFilter[]): Promise<string | null>;
  };
  shell: {
    openPath(target: string): Promise<void>;
    openExternal(url: string): Promise<void>;
  };
  project: {
    open(apkPath: string): Promise<ProjectSnapshot>;
    snapshot(): Promise<ProjectSnapshot | null>;
    close(): Promise<void>;
  };
  songs: {
    update(id: string, patch: Partial<Song>): Promise<ProjectSnapshot>;
    create(input: {
      id: string;
      setId: string;
      title?: string;
      /** 可选：同时从该 zip 导入资源，并采用包内的 songlist/slst/song.json 元数据 */
      zipPath?: string;
    }): Promise<ProjectSnapshot>;
    remove(id: string): Promise<ProjectSnapshot>;
    reorder(fromIndex: number, toIndex: number): Promise<ProjectSnapshot>;
  };
  packs: {
    update(id: string, patch: Partial<Pack>): Promise<ProjectSnapshot>;
    create(input: { id: string; section?: string; name?: string }): Promise<ProjectSnapshot>;
    remove(id: string, moveSongsTo?: string): Promise<ProjectSnapshot>;
  };
  assets: {
    readBase64(relPath: string): Promise<{ mime: string; base64: string } | null>;
    readText(relPath: string): Promise<string | null>;
    /** 写入文本（仅限 assets/ 下的 JSON / aff 等文本资源） */
    writeText(relPath: string, text: string): Promise<ProjectSnapshot>;
    /** 用本地文件覆盖/新增 APK 内条目 */
    importFile(relPath: string, srcPath: string): Promise<ProjectSnapshot>;
    remove(relPath: string): Promise<ProjectSnapshot>;
    exists(relPath: string): Promise<boolean>;
    /** 检查一个资源 zip，返回将要导入的文件与解析出的元数据（不写入工程） */
    inspectZip(zipPath: string): Promise<SongZipInspection>;
  };
  build: {
    pickKeystore(): Promise<string | null>;
    createKeystore(input: {
      path: string;
      storePassword: string;
      keyAlias: string;
      keyPassword: string;
      cn: string;
    }): Promise<{ ok: boolean; message: string }>;
    export(opts: BuildOptions): Promise<BuildResult>;
  };
  adb: {
    list(): Promise<AdbDevice[]>;
    pair(host: string, port: string, code: string): Promise<{ ok: boolean; message: string }>;
    connect(host: string, port: string): Promise<{ ok: boolean; message: string }>;
    disconnect(target?: string): Promise<{ ok: boolean; message: string }>;
    /** serial 为空时由 adb 自行选择设备（多设备会失败） */
    install(apkPath: string, serial?: string): Promise<{ ok: boolean; message: string; cancelled?: boolean }>;
    /** 中止正在进行的安装（会杀掉上传进程并清理设备端临时文件） */
    cancelInstall(): Promise<{ ok: boolean; message: string }>;
    /** 重启 adb 服务并重新扫描，用于识别在工具外建立的连接 */
    restartServer(): Promise<{ ok: boolean; message: string }>;
  };
  menu: {
    /** 弹出原生右键菜单，返回被点击项的 id（未选择时为 null） */
    show(items: ContextMenuItem[]): Promise<string | null>;
  };
  cache: {
    info(): Promise<CacheInfo>;
    clear(force: boolean): Promise<ClearCacheResult>;
  };
  onProgress(cb: (e: ProgressEvent) => void): () => void;
  /** 订阅安装过程中 adb 的原始输出（按行） */
  onAdbLog(cb: (line: string) => void): () => void;
}
