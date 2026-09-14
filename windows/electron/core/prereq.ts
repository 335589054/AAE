import fs from 'node:fs';
import path from 'node:path';
import { toolsDir, ensureDir, uniqueTempFile, tmpDir } from './paths';
import { run, downloadFile } from './proc';
import { ZipReader } from './zip';
import type { PrereqId, PrereqStatus } from '../../shared/types';

/* --------------------------- 可选资源位置 --------------------------- */

function sdkRoots(): string[] {
  const roots = [process.env.ANDROID_HOME, process.env.ANDROID_SDK_ROOT];
  const local = process.env.LOCALAPPDATA;
  if (local) roots.push(path.join(local, 'Android', 'Sdk'));
  return roots.filter((v): v is string => Boolean(v && fs.existsSync(v)));
}

function findHighestBuildToolsApksigner(): string | null {
  const found: Array<{ version: string; file: string }> = [];
  for (const root of sdkRoots()) {
    const buildTools = path.join(root, 'build-tools');
    if (!fs.existsSync(buildTools)) continue;
    for (const dir of fs.readdirSync(buildTools)) {
      const jar = path.join(buildTools, dir, 'lib', 'apksigner.jar');
      if (fs.existsSync(jar)) found.push({ version: dir, file: jar });
    }
  }
  if (found.length === 0) return null;
  found.sort((a, b) =>
    a.version.localeCompare(b.version, undefined, { numeric: true, sensitivity: 'base' }),
  );
  return found[found.length - 1].file;
}

function findSdkAdb(): string | null {
  for (const root of sdkRoots()) {
    const exe = path.join(root, 'platform-tools', process.platform === 'win32' ? 'adb.exe' : 'adb');
    if (fs.existsSync(exe)) return exe;
  }
  return null;
}

export interface Toolset {
  java: string | null;
  adb: string | null;
  apksignerJar: string | null;
  apktoolJar: string | null;
}

let cachedToolset: { at: number; value: Toolset } | null = null;

function whichSync(name: string): string | null {
  const paths = (process.env.PATH ?? '').split(path.delimiter);
  const exts = process.platform === 'win32' ? ['', '.exe', '.bat', '.cmd'] : [''];
  for (const dir of paths) {
    if (!dir) continue;
    for (const ext of exts) {
      const candidate = path.join(dir, name + ext);
      if (fs.existsSync(candidate)) return candidate;
    }
  }
  return null;
}

export function resolveToolset(force = false): Toolset {
  if (!force && cachedToolset && Date.now() - cachedToolset.at < 30_000) return cachedToolset.value;

  const localTools = toolsDir();

  const javaHomeExe =
    process.env.JAVA_HOME && fs.existsSync(path.join(process.env.JAVA_HOME, 'bin', 'java.exe'))
      ? path.join(process.env.JAVA_HOME, 'bin', 'java.exe')
      : process.env.JAVA_HOME && fs.existsSync(path.join(process.env.JAVA_HOME, 'bin', 'java'))
        ? path.join(process.env.JAVA_HOME, 'bin', 'java')
        : null;

  const java = javaHomeExe ?? whichSync('java');

  const adbCandidates = [
    findSdkAdb(),
    whichSync('adb'),
    path.join(localTools, 'platform-tools', process.platform === 'win32' ? 'adb.exe' : 'adb'),
  ].filter((v): v is string => Boolean(v));
  const adb = adbCandidates.find((p) => fs.existsSync(p)) ?? null;

  const apksignerCandidates = [
    path.join(localTools, 'apksigner', 'apksigner.jar'),
    findHighestBuildToolsApksigner(),
  ].filter((v): v is string => Boolean(v));
  const apksignerJar = apksignerCandidates.find((p) => fs.existsSync(p)) ?? null;

  const apktoolCandidates = [path.join(localTools, 'apktool', 'apktool.jar')].filter((v) =>
    fs.existsSync(v),
  );
  const apktoolJar = apktoolCandidates[0] ?? null;

  const value: Toolset = { java, adb, apksignerJar, apktoolJar };
  cachedToolset = { at: Date.now(), value };
  return value;
}

/* --------------------------- 状态检测 --------------------------- */

const DOWNLOAD_URLS = {
  adb: 'https://dl.google.com/android/repository/platform-tools-latest-windows.zip',
  apksigner: 'https://dl.google.com/android/repository/build-tools_r34-windows.zip',
  apktoolFallback: 'https://github.com/iBotPeaches/Apktool/releases/download/v3.0.3/apktool_3.0.3.jar',
} as const;

export async function checkPrerequisites(): Promise<PrereqStatus[]> {
  const tools = resolveToolset(true);

  const javaStatus: PrereqStatus = {
    id: 'java',
    name: 'Java 运行环境（JRE/JDK）',
    required: true,
    found: Boolean(tools.java),
    path: tools.java ?? undefined,
    canAutoDownload: false,
    purpose: 'apksigner 依赖 Java 运行；安装 JDK/JRE 17 或更高版本即可。',
    hint: '未检测到 java。请安装 Temurin JDK 17+（https://adoptium.net/），或设置 JAVA_HOME。',
    downloadUrl: 'https://adoptium.net/temurin/releases/',
  };
  if (tools.java) {
    const res = await run(tools.java, ['-version'], { timeoutMs: 15000 });
    const versionLine = (res.stderr || res.stdout).split(/\r?\n/).find((l) => l.trim()) ?? '';
    javaStatus.version = versionLine.trim();
    if (!res.started) {
      javaStatus.found = false;
      javaStatus.hint = 'java 无法启动，请检查 JAVA_HOME 或重新安装 JDK。';
    }
  }

  const adbStatus: PrereqStatus = {
    id: 'adb',
    name: 'adb（Android Platform Tools）',
    required: true,
    found: Boolean(tools.adb),
    path: tools.adb ?? undefined,
    canAutoDownload: true,
    downloadUrl: DOWNLOAD_URLS.adb,
    purpose: '用于通过无线调试连接手机并安装打包后的 APK。',
    hint: '未检测到 adb。可一键下载 platform-tools，或安装 Android SDK Platform Tools 并加入 PATH。',
  };
  if (tools.adb) {
    const res = await run(tools.adb, ['version'], { timeoutMs: 15000 });
    const line = (res.stdout || res.stderr).split(/\r?\n/).find((l) => l.trim()) ?? '';
    adbStatus.version = line.trim();
  }

  const signerStatus: PrereqStatus = {
    id: 'apksigner',
    name: 'apksigner（Android Build Tools）',
    required: true,
    found: Boolean(tools.apksignerJar),
    path: tools.apksignerJar ?? undefined,
    canAutoDownload: true,
    downloadUrl: DOWNLOAD_URLS.apksigner,
    purpose: '为重新打包的 APK 生成 v1/v2/v3 签名（Android 11+ 必须 v2 及以上）。',
    hint: '未检测到 apksigner.jar。可一键下载 Android build-tools，或从 Android SDK 的 build-tools 目录获取。',
  };

  const apktoolStatus: PrereqStatus = {
    id: 'apktool',
    name: 'apktool（可选）',
    required: false,
    found: Boolean(tools.apktoolJar),
    path: tools.apktoolJar ?? undefined,
    canAutoDownload: true,
    downloadUrl: DOWNLOAD_URLS.apktoolFallback,
    purpose:
      '可选备用工具。本工具已内置 AndroidManifest 二进制补丁，改包名无需 apktool；仅在需要人工深度修复资源时使用。',
    hint: '未安装 apktool（不影响正常使用）。',
  };

  return [javaStatus, adbStatus, signerStatus, apktoolStatus];
}

/* --------------------------- 下载与安装 --------------------------- */

async function extractFromZip(
  zipPath: string,
  destRoot: string,
  mapEntry: (entryName: string) => string | null,
): Promise<number> {
  const reader = ZipReader.open(zipPath);
  try {
    let count = 0;
    for (const name of reader.listAll()) {
      const rel = mapEntry(name);
      if (!rel) continue;
      const data = reader.readFile(name);
      if (!data) continue;
      const dest = path.join(destRoot, rel);
      ensureDir(path.dirname(dest));
      fs.writeFileSync(dest, data);
      count++;
    }
    return count;
  } finally {
    reader.close();
  }
}

async function resolveApktoolUrl(): Promise<string> {
  try {
    const res = await fetch('https://api.github.com/repos/iBotPeaches/Apktool/releases/latest', {
      headers: { 'User-Agent': 'arcaea-apk-manager', Accept: 'application/vnd.github+json' },
    });
    if (res.ok) {
      const json = (await res.json()) as { assets?: Array<{ name: string; browser_download_url: string }> };
      const asset = (json.assets ?? []).find((a) => a.name.endsWith('.jar'));
      if (asset) return asset.browser_download_url;
    }
  } catch {
    /* 回退到固定版本 */
  }
  return DOWNLOAD_URLS.apktoolFallback;
}

export async function downloadPrerequisite(
  id: PrereqId,
  onProgress: (message: string, percent?: number) => void,
): Promise<{ ok: boolean; message: string }> {
  const tools = toolsDir();
  ensureDir(tools);
  ensureDir(tmpDir());

  try {
    if (id === 'adb') {
      const zipPath = uniqueTempFile('platform-tools', '.zip');
      onProgress('正在下载 Android Platform Tools…', 0);
      const dl = await downloadFile(DOWNLOAD_URLS.adb, zipPath, (received, total) => {
        onProgress('正在下载 Android Platform Tools…', total ? (received / total) * 80 : undefined);
      });
      if (!dl.ok) return { ok: false, message: `下载失败：${dl.message}` };
      onProgress('正在解压 platform-tools…', 85);
      const dest = path.join(tools, 'platform-tools');
      const count = await extractFromZip(zipPath, dest, (name) => {
        const m = /^platform-tools\/(.+)$/.exec(name);
        return m ? m[1] : null;
      });
      fs.rmSync(zipPath, { force: true });
      if (count === 0) return { ok: false, message: '解压 platform-tools 失败：压缩包结构异常' };
      cachedToolset = null;
      onProgress('platform-tools 安装完成', 100);
      return { ok: true, message: `adb 已安装到 ${dest}` };
    }

    if (id === 'apksigner') {
      const zipPath = uniqueTempFile('build-tools', '.zip');
      onProgress('正在下载 Android build-tools（约 58MB）…', 0);
      const dl = await downloadFile(DOWNLOAD_URLS.apksigner, zipPath, (received, total) => {
        onProgress('正在下载 Android build-tools（约 58MB）…', total ? (received / total) * 90 : undefined);
      });
      if (!dl.ok) return { ok: false, message: `下载失败：${dl.message}` };
      onProgress('正在提取 apksigner…', 95);
      const dest = path.join(tools, 'apksigner');
      const count = await extractFromZip(zipPath, dest, (name) => {
        if (/^[^/]+\/lib\/apksigner\.jar$/.test(name)) return 'apksigner.jar';
        return null;
      });
      fs.rmSync(zipPath, { force: true });
      if (count === 0) return { ok: false, message: '提取 apksigner.jar 失败：压缩包结构异常' };
      cachedToolset = null;
      onProgress('apksigner 安装完成', 100);
      return { ok: true, message: `apksigner 已安装到 ${dest}` };
    }

    if (id === 'apktool') {
      const url = await resolveApktoolUrl();
      const dest = path.join(tools, 'apktool', 'apktool.jar');
      ensureDir(path.dirname(dest));
      onProgress('正在下载 apktool…', 5);
      const dl = await downloadFile(url, dest, (received, total) => {
        onProgress('正在下载 apktool…', total ? 5 + (received / total) * 90 : undefined);
      });
      if (!dl.ok) return { ok: false, message: `下载失败：${dl.message}` };
      cachedToolset = null;
      onProgress('apktool 安装完成', 100);
      return { ok: true, message: `apktool 已安装到 ${dest}` };
    }

    return {
      ok: false,
      message: 'Java 需要手动安装：请前往 https://adoptium.net/ 下载 Temurin JDK 17+。',
    };
  } catch (err) {
    return { ok: false, message: err instanceof Error ? err.message : String(err) };
  }
}
