import { contextBridge, ipcRenderer } from 'electron';
import type { ProgressEvent, RendererApi } from '../shared/types';

const invoke = (channel: string, ...args: unknown[]): Promise<unknown> =>
  ipcRenderer.invoke(channel, ...args);

const api: RendererApi = {
  prereq: {
    check: () => invoke('prereq:check') as ReturnType<RendererApi['prereq']['check']>,
    download: (id) =>
      invoke('prereq:download', id) as ReturnType<RendererApi['prereq']['download']>,
  },
  dialog: {
    openApk: () => invoke('dialog:openApk') as ReturnType<RendererApi['dialog']['openApk']>,
    openFile: (title, filters) =>
      invoke('dialog:openFile', title, filters) as ReturnType<RendererApi['dialog']['openFile']>,
    saveFile: (defaultFileName, filters) =>
      invoke('dialog:saveFile', defaultFileName, filters) as ReturnType<
        RendererApi['dialog']['saveFile']
      >,
  },
  shell: {
    openPath: (target) => invoke('shell:openPath', target) as Promise<void>,
    openExternal: (url) => invoke('shell:openExternal', url) as Promise<void>,
  },
  project: {
    open: (apkPath) => invoke('project:open', apkPath) as ReturnType<RendererApi['project']['open']>,
    snapshot: () =>
      invoke('project:snapshot') as ReturnType<RendererApi['project']['snapshot']>,
    close: () => invoke('project:close') as Promise<void>,
  },
  songs: {
    update: (id, patch) =>
      invoke('songs:update', id, patch) as ReturnType<RendererApi['songs']['update']>,
    create: (input) => invoke('songs:create', input) as ReturnType<RendererApi['songs']['create']>,
    remove: (id) => invoke('songs:remove', id) as ReturnType<RendererApi['songs']['remove']>,
    reorder: (from, to) =>
      invoke('songs:reorder', from, to) as ReturnType<RendererApi['songs']['reorder']>,
  },
  packs: {
    update: (id, patch) =>
      invoke('packs:update', id, patch) as ReturnType<RendererApi['packs']['update']>,
    create: (input) => invoke('packs:create', input) as ReturnType<RendererApi['packs']['create']>,
    remove: (id, moveSongsTo) =>
      invoke('packs:remove', id, moveSongsTo) as ReturnType<RendererApi['packs']['remove']>,
  },
  assets: {
    readBase64: (relPath) =>
      invoke('assets:readBase64', relPath) as ReturnType<RendererApi['assets']['readBase64']>,
    readText: (relPath) =>
      invoke('assets:readText', relPath) as ReturnType<RendererApi['assets']['readText']>,
    writeText: (relPath, text) =>
      invoke('assets:writeText', relPath, text) as ReturnType<RendererApi['assets']['writeText']>,
    importFile: (relPath, srcPath) =>
      invoke('assets:importFile', relPath, srcPath) as ReturnType<
        RendererApi['assets']['importFile']
      >,
    remove: (relPath) =>
      invoke('assets:remove', relPath) as ReturnType<RendererApi['assets']['remove']>,
    exists: (relPath) => invoke('assets:exists', relPath) as Promise<boolean>,
    inspectZip: (zipPath) =>
      invoke('assets:inspectZip', zipPath) as ReturnType<RendererApi['assets']['inspectZip']>,
  },
  build: {
    pickKeystore: () =>
      invoke('build:pickKeystore') as ReturnType<RendererApi['build']['pickKeystore']>,
    createKeystore: (input) =>
      invoke('build:createKeystore', input) as ReturnType<RendererApi['build']['createKeystore']>,
    export: (opts) => invoke('build:export', opts) as ReturnType<RendererApi['build']['export']>,
  },
  adb: {
    list: () => invoke('adb:list') as ReturnType<RendererApi['adb']['list']>,
    pair: (host, port, code) =>
      invoke('adb:pair', host, port, code) as ReturnType<RendererApi['adb']['pair']>,
    connect: (host, port) =>
      invoke('adb:connect', host, port) as ReturnType<RendererApi['adb']['connect']>,
    disconnect: (target) =>
      invoke('adb:disconnect', target) as ReturnType<RendererApi['adb']['disconnect']>,
    install: (apkPath, serial) =>
      invoke('adb:install', apkPath, serial) as ReturnType<RendererApi['adb']['install']>,
    cancelInstall: () =>
      invoke('adb:installCancel') as ReturnType<RendererApi['adb']['cancelInstall']>,
    restartServer: () =>
      invoke('adb:restartServer') as ReturnType<RendererApi['adb']['restartServer']>,
  },
  menu: {
    show: (items) => invoke('menu:show', items) as ReturnType<RendererApi['menu']['show']>,
  },
  cache: {
    info: () => invoke('cache:info') as ReturnType<RendererApi['cache']['info']>,
    clear: (force) => invoke('cache:clear', force) as ReturnType<RendererApi['cache']['clear']>,
  },
  onProgress: (cb) => {
    const listener = (_event: unknown, payload: ProgressEvent): void => cb(payload);
    ipcRenderer.on('job:progress', listener);
    return () => {
      ipcRenderer.removeListener('job:progress', listener);
    };
  },
  onAdbLog: (cb) => {
    const listener = (_event: unknown, line: string): void => cb(line);
    ipcRenderer.on('adb:log', listener);
    return () => {
      ipcRenderer.removeListener('adb:log', listener);
    };
  },
};

contextBridge.exposeInMainWorld('api', api);
