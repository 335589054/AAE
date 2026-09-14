import { BrowserWindow, dialog, ipcMain, Menu, shell, type MenuItemConstructorOptions } from 'electron';
import fs from 'node:fs';
import path from 'node:path';
import {
  ApkProject,
  createSongWithResources,
  PACKLIST_PATH,
  SONGLIST_PATH,
  UNLOCKS_PATH,
  type CreateSongInput,
} from './core/project';
import { readSongZip } from './core/songzip';
import { checkPrerequisites, downloadPrerequisite, resolveToolset } from './core/prereq';
import { cacheInfo, clearCache } from './core/cache';
import {
  adbConnect,
  adbDevices,
  adbDisconnect,
  adbPair,
  adbRestartServer,
} from './core/adb';
import { startInstall, type InstallHandle } from './core/install';
import { createKeystore, ensureAutoKeystore, signApk, verifyApk } from './core/signer';
import { uniqueTempFile } from './core/paths';
import type {
  BuildOptions,
  BuildResult,
  ContextMenuItem,
  FileFilter,
  Pack,
  PrereqId,
  ProgressEvent,
  Song,
} from '../shared/types';

let project: ApkProject | null = null;
let activeInstall: InstallHandle | null = null;
let getWindow: () => BrowserWindow | null = () => null;

function sendProgress(event: ProgressEvent): void {
  getWindow()?.webContents.send('job:progress', event);
}

function requireProject(): ApkProject {
  if (!project) throw new Error('尚未导入 APK。请先在「工程」页导入你的 Arcaea 安装包。');
  return project;
}

/** 统一把异常转成可读的中文错误，避免渲染进程收到 "Error invoking remote method" */
function handle(channel: string, fn: (...args: any[]) => unknown): void {
  ipcMain.handle(channel, async (_event, ...args: unknown[]) => {
    try {
      return await fn(...args);
    } catch (err) {
      const message = err instanceof Error ? err.message : String(err);
      throw new Error(message);
    }
  });
}

function mimeFor(ext: string): string {
  switch (ext.toLowerCase()) {
    case '.png':
      return 'image/png';
    case '.jpg':
    case '.jpeg':
      return 'image/jpeg';
    case '.ogg':
      return 'audio/ogg';
    default:
      return 'application/octet-stream';
  }
}

const PACKAGE_NAME_RE = /^[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z][a-zA-Z0-9_]*)+$/;

function validatePackageName(pkg: string): void {
  if (!PACKAGE_NAME_RE.test(pkg)) {
    throw new Error(
      `包名不合法："${pkg}"。需要是至少两段的 Java 包名，每段以字母开头，例如 com.example.arcaea。`,
    );
  }
}

export function registerIpc(windowGetter: () => BrowserWindow | null): void {
  getWindow = windowGetter;

  /* ----------------------------- 前置资源 ----------------------------- */

  handle('prereq:check', () => checkPrerequisites());

  handle('prereq:download', async (id) => {
    const result = await downloadPrerequisite(id as PrereqId, (message, percent) =>
      sendProgress({ scope: 'prereq', phase: 'download', message, percent }),
    );
    return { ok: result.ok, message: result.message, status: await checkPrerequisites() };
  });

  /* ------------------------------- 对话框 ------------------------------- */

  handle('dialog:openApk', async () => {
    const result = await dialog.showOpenDialog({
      title: '选择 Arcaea 安装包（APK）',
      properties: ['openFile'],
      filters: [{ name: 'Android 安装包', extensions: ['apk'] }],
    });
    return result.canceled || result.filePaths.length === 0 ? null : result.filePaths[0];
  });

  handle('dialog:openFile', async (title, filters) => {
    const result = await dialog.showOpenDialog({
      title: String(title),
      properties: ['openFile'],
      filters: (filters as FileFilter[]) ?? [{ name: '所有文件', extensions: ['*'] }],
    });
    return result.canceled || result.filePaths.length === 0 ? null : result.filePaths[0];
  });

  handle('dialog:saveFile', async (defaultFileName, filters) => {
    const result = await dialog.showSaveDialog({
      title: '保存为',
      defaultPath: String(defaultFileName),
      filters: (filters as FileFilter[]) ?? [{ name: '所有文件', extensions: ['*'] }],
    });
    return result.canceled || !result.filePath ? null : result.filePath;
  });

  handle('shell:openPath', async (target) => {
    await shell.openPath(String(target));
  });

  handle('shell:openExternal', async (url) => {
    const text = String(url);
    if (!/^https?:\/\//i.test(text)) throw new Error('只允许打开 http/https 链接');
    await shell.openExternal(text);
  });

  /* -------------------------------- 工程 -------------------------------- */

  handle('project:open', async (apkPath) => {
    project?.close();
    project = null;
    const opened = ApkProject.open(String(apkPath));
    project = opened;
    sendProgress({ scope: 'project', phase: 'open', message: '已解析 APK 资源清单' });
    return opened.snapshot();
  });

  handle('project:snapshot', () => (project ? project.snapshot() : null));

  handle('project:close', () => {
    project?.close();
    project = null;
  });

  /* ---------------------------- 歌曲 / 曲包 ---------------------------- */

  handle('songs:update', (id, patch) => {
    const proj = requireProject();
    proj.updateSong(String(id), patch as Partial<Song>);
    return proj.snapshot();
  });

  handle('songs:create', (input) => {
    const proj = requireProject();
    createSongWithResources(proj, input as CreateSongInput);
    return proj.snapshot();
  });

  handle('songs:remove', (id) => {
    const proj = requireProject();
    proj.removeSong(String(id));
    return proj.snapshot();
  });

  handle('songs:reorder', (from, to) => {
    const proj = requireProject();
    proj.reorderSongs(Number(from), Number(to));
    return proj.snapshot();
  });

  handle('packs:update', (id, patch) => {
    const proj = requireProject();
    proj.updatePack(String(id), patch as Partial<Pack>);
    return proj.snapshot();
  });

  handle('packs:create', (input) => {
    const proj = requireProject();
    const { id, section, name } = input as { id: string; section?: string; name?: string };
    const clean = String(id).trim();
    if (!/^[a-zA-Z0-9_]+$/.test(clean)) {
      throw new Error('曲包 id 只能包含字母、数字与下划线（会作为横幅文件名的一部分）');
    }
    proj.addPack({
      id: clean,
      section: section || 'sidestory',
      custom_banner: true,
      cutout_pack_image: true,
      plus_character: -1,
      name_localized: { en: name || clean },
      description_localized: { en: '', ja: '' },
    });
    return proj.snapshot();
  });

  handle('packs:remove', (id, moveSongsTo) => {
    const proj = requireProject();
    proj.removePack(String(id), moveSongsTo ? String(moveSongsTo) : undefined);
    return proj.snapshot();
  });

  /* -------------------------------- 资源 -------------------------------- */

  handle('assets:readBase64', (relPath) => {
    const proj = requireProject();
    const name = String(relPath);
    const data = proj.readEntry(name);
    if (!data) return null;
    return { mime: mimeFor(path.extname(name)), base64: data.toString('base64') };
  });

  handle('assets:readText', (relPath) => {
    const proj = requireProject();
    const data = proj.readEntry(String(relPath));
    if (!data) return null;
    let text = data.toString('utf8');
    if (text.charCodeAt(0) === 0xfeff) text = text.slice(1);
    return text;
  });

  handle('assets:writeText', (relPath, text) => {
    const proj = requireProject();
    const name = String(relPath);
    if ([SONGLIST_PATH, PACKLIST_PATH, UNLOCKS_PATH].includes(name)) {
      throw new Error('该文件由本工具的结构化编辑器维护，请勿直接写入文本');
    }
    proj.stageWrite(name, { kind: 'buffer', data: Buffer.from(String(text), 'utf8') });
    return proj.snapshot();
  });

  handle('assets:importFile', (relPath, srcPath) => {
    const proj = requireProject();
    const source = String(srcPath);
    if (!fs.existsSync(source)) throw new Error(`源文件不存在：${source}`);
    proj.stageWrite(String(relPath), { kind: 'file', srcPath: source });
    return proj.snapshot();
  });

  handle('assets:remove', (relPath) => {
    const proj = requireProject();
    proj.stageDelete(String(relPath));
    return proj.snapshot();
  });

  handle('assets:exists', (relPath) => requireProject().exists(String(relPath)));

  handle('assets:inspectZip', (zipPath) => {
    const target = String(zipPath);
    if (!fs.existsSync(target)) throw new Error(`文件不存在：${target}`);
    return readSongZip(target).inspection;
  });

  /* ------------------------------ 打包与签名 ------------------------------ */

  handle('build:pickKeystore', async () => {
    const result = await dialog.showOpenDialog({
      title: '选择签名密钥库（.jks / .keystore）',
      properties: ['openFile'],
      filters: [
        { name: '密钥库', extensions: ['jks', 'keystore', 'bks'] },
        { name: '所有文件', extensions: ['*'] },
      ],
    });
    return result.canceled || result.filePaths.length === 0 ? null : result.filePaths[0];
  });

  handle('build:createKeystore', async (input) => {
    const tools = resolveToolset(true);
    if (!tools.java) return { ok: false, message: '未检测到 Java，无法创建密钥库。' };
    const payload = input as {
      path: string;
      storePassword: string;
      keyAlias: string;
      keyPassword: string;
      cn: string;
    };
    return createKeystore(
      tools.java,
      {
        path: payload.path,
        storePassword: payload.storePassword,
        keyAlias: payload.keyAlias,
        keyPassword: payload.keyPassword,
      },
      payload.cn,
    );
  });

  handle('build:export', async (rawOptions): Promise<BuildResult> => {
    const options = rawOptions as BuildOptions;
    const proj = requireProject();
    const startedAt = Date.now();
    const tools = resolveToolset(true);

    if (!tools.java) {
      throw new Error('未检测到 Java 运行环境，无法签名。请先到「前置资源」页安装 JDK 17+。');
    }
    if (!tools.apksignerJar) {
      throw new Error('未检测到 apksigner，无法签名。请先到「前置资源」页一键下载 build-tools。');
    }
    if (!options.outPath) throw new Error('请先选择导出路径');
    if (options.packageName) validatePackageName(options.packageName);

    proj.setPackageNameOverride(options.packageName ?? null, options.rewriteIdentifiers !== false);

    const log: string[] = [];
    const push = (message: string): void => {
      log.push(message);
      sendProgress({ scope: 'export', phase: 'log', message });
    };

    const unsignedPath = uniqueTempFile('unsigned', '.apk');
    try {
      sendProgress({ scope: 'export', phase: 'pack', message: '开始重新打包…', percent: 0 });
      proj.exportUnsigned(unsignedPath, (message, percent) =>
        sendProgress({ scope: 'export', phase: 'pack', message, percent }),
      );
      push(`已生成未签名 APK（${(fs.statSync(unsignedPath).size / 1024 / 1024).toFixed(1)} MB）`);

      const keystore =
        options.keystore.mode === 'existing'
          ? (() => {
              if (!options.keystore.path) throw new Error('请选择 .jks / .keystore 密钥库文件');
              if (!options.keystore.keyAlias) throw new Error('请填写 key alias');
              return {
                path: options.keystore.path,
                storePassword: options.keystore.storePassword ?? '',
                keyAlias: options.keystore.keyAlias,
                keyPassword: options.keystore.keyPassword ?? options.keystore.storePassword ?? '',
              };
            })()
          : await ensureAutoKeystore(tools.java, push);

      sendProgress({ scope: 'export', phase: 'sign', message: '正在签名…', percent: 90 });
      await signApk({
        javaExe: tools.java,
        apksignerJar: tools.apksignerJar,
        keystore,
        inputApk: unsignedPath,
        outputApk: options.outPath,
        log: push,
      });

      sendProgress({ scope: 'export', phase: 'verify', message: '正在校验签名…', percent: 96 });
      const verify = await verifyApk(tools.java, tools.apksignerJar, options.outPath);
      if (!verify.ok) {
        push(verify.output);
        throw new Error(`签名校验未通过，导出结果不可用：\n${verify.output}`);
      }
      push('签名校验通过（v1/v2/v3）');

      proj.markExported();
      const stat = fs.statSync(options.outPath);
      sendProgress({ scope: 'export', phase: 'done', message: '导出完成', percent: 100 });

      return {
        outPath: options.outPath,
        signed: true,
        packageName: options.packageName || proj.originalPackageName(),
        size: stat.size,
        durationMs: Date.now() - startedAt,
        log,
      };
    } finally {
      if (!options.keepUnsigned) fs.rmSync(unsignedPath, { force: true });
      if (options.cleanupTemp) clearCache();
    }
  });

  /* --------------------------------- ADB --------------------------------- */

  handle('adb:list', async () => {
    const tools = resolveToolset(true);
    if (!tools.adb) throw new Error('未检测到 adb，请先到「前置资源」页安装 Platform Tools。');
    return adbDevices(tools.adb);
  });

  handle('adb:pair', async (host, port, code) => {
    const tools = resolveToolset(true);
    if (!tools.adb) return { ok: false, message: '未检测到 adb，请先到「前置资源」页安装 Platform Tools。' };
    return adbPair(tools.adb, String(host), String(port), String(code));
  });

  handle('adb:connect', async (host, port) => {
    const tools = resolveToolset(true);
    if (!tools.adb) return { ok: false, message: '未检测到 adb，请先到「前置资源」页安装 Platform Tools。' };
    return adbConnect(tools.adb, String(host), String(port));
  });

  handle('adb:disconnect', async (target) => {
    const tools = resolveToolset(true);
    if (!tools.adb) return { ok: false, message: '未检测到 adb，请先到「前置资源」页安装 Platform Tools。' };
    return adbDisconnect(tools.adb, target ? String(target) : undefined);
  });

  handle('adb:install', async (apkPath, serial) => {
    const tools = resolveToolset(true);
    if (!tools.adb) return { ok: false, message: '未检测到 adb，请先到「前置资源」页安装 Platform Tools。' };
    const target = String(apkPath);
    if (!fs.existsSync(target)) return { ok: false, message: `文件不存在：${target}` };
    if (activeInstall) {
      return { ok: false, message: '已有正在进行的安装任务，请等待完成或先点「停止安装」。' };
    }

    const device = serial ? String(serial) : '';
    const handle = startInstall({
      adbExe: tools.adb,
      apkPath: target,
      serial: device || undefined,
      onProgress: (progress) =>
        sendProgress({
          scope: 'install',
          phase: progress.phase,
          message: progress.message,
          percent: progress.percent,
          bytesSent: progress.bytesSent,
          totalBytes: progress.totalBytes,
          bytesPerSecond: progress.bytesPerSecond,
          elapsedMs: progress.elapsedMs,
          estimated: progress.estimated,
        }),
      onLog: (line) => getWindow()?.webContents.send('adb:log', line),
    });
    activeInstall = handle;
    try {
      return await handle.promise;
    } finally {
      activeInstall = null;
    }
  });

  handle('adb:installCancel', () => {
    if (!activeInstall) return { ok: false, message: '当前没有正在进行的安装任务' };
    activeInstall.cancel();
    return { ok: true, message: '已请求停止安装' };
  });

  handle('adb:restartServer', async () => {
    const tools = resolveToolset(true);
    if (!tools.adb) return { ok: false, message: '未检测到 adb，请先到「前置资源」页安装 Platform Tools。' };
    return adbRestartServer(tools.adb);
  });

  /* ------------------------------- 原生菜单 ------------------------------- */

  ipcMain.handle('menu:show', (event, rawItems: unknown) => {
    const items = (rawItems as ContextMenuItem[]) ?? [];
    if (items.length === 0) return Promise.resolve(null);
    const win = BrowserWindow.fromWebContents(event.sender) ?? undefined;

    return new Promise<string | null>((resolve) => {
      let picked: string | null = null;
      const template: MenuItemConstructorOptions[] = [];
      for (const item of items) {
        if (item.separatorBefore && template.length > 0) template.push({ type: 'separator' });
        template.push({
          label: item.label,
          enabled: item.enabled !== false,
          click: () => {
            picked = item.id;
          },
        });
      }
      const menu = Menu.buildFromTemplate(template);
      menu.popup({ window: win, callback: () => resolve(picked) });
    });
  });

  /* -------------------------------- 缓存 -------------------------------- */

  handle('cache:info', () => cacheInfo());

  handle('cache:clear', (force) => {
    const dirty = project ? project.isDirtySinceExport() : false;
    if (dirty && !force) {
      return {
        cleared: false,
        freedBytes: 0,
        reason: '检测到尚未导出的改动。请先导出 APK，再清除缓存。',
      };
    }
    return clearCache();
  });
}
