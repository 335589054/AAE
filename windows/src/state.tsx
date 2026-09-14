import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type JSX,
  type ReactNode,
} from 'react';
import type { PrereqId, PrereqStatus, ProgressEvent, ProjectSnapshot } from '../shared/types';

export interface Toast {
  id: number;
  kind: 'info' | 'success' | 'error';
  text: string;
}

interface StoreValue {
  prereqs: PrereqStatus[];
  prereqsLoaded: boolean;
  snapshot: ProjectSnapshot | null;
  busy: string | null;
  progress: ProgressEvent | null;
  /** 安装（上传/设备端安装）的实时进度，独立于全局进度条保存，避免被自动清理 */
  installProgress: ProgressEvent | null;
  clearInstallProgress(): void;
  toasts: Toast[];
  /** 最近一次成功导出的 APK 路径，用于「无线安装」页自动填充 */
  lastExportPath: string | null;
  notify(kind: Toast['kind'], text: string): void;
  dismiss(id: number): void;
  setLastExportPath(path: string): void;
  refreshPrereqs(): Promise<void>;
  downloadPrereq(id: PrereqId): Promise<void>;
  pickAndOpenApk(): Promise<void>;
  reloadSnapshot(): Promise<void>;
  closeProject(): Promise<void>;
  setSnapshot(snapshot: ProjectSnapshot): void;
  /** 执行一个可能抛错的操作：显示忙碌状态、捕获错误并弹出提示 */
  run<T>(label: string, fn: () => Promise<T>): Promise<T | undefined>;
  /** 执行一个返回快照的操作，并自动刷新界面状态 */
  runSnapshot(label: string, fn: () => Promise<ProjectSnapshot>): Promise<void>;
}

const StoreContext = createContext<StoreValue | null>(null);

export function useStore(): StoreValue {
  const value = useContext(StoreContext);
  if (!value) throw new Error('useStore 必须在 StoreProvider 内使用');
  return value;
}

export function StoreProvider({ children }: { children: ReactNode }): JSX.Element {
  const [prereqs, setPrereqs] = useState<PrereqStatus[]>([]);
  const [prereqsLoaded, setPrereqsLoaded] = useState(false);
  const [snapshot, setSnapshot] = useState<ProjectSnapshot | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [progress, setProgress] = useState<ProgressEvent | null>(null);
  const [installProgress, setInstallProgress] = useState<ProgressEvent | null>(null);
  const [toasts, setToasts] = useState<Toast[]>([]);
  const [lastExportPath, setLastExportPath] = useState<string | null>(null);
  const toastId = useRef(0);

  const notify = useCallback((kind: Toast['kind'], text: string) => {
    const id = ++toastId.current;
    setToasts((prev) => [...prev, { id, kind, text }]);
    const ttl = kind === 'error' ? 12000 : 5000;
    window.setTimeout(() => setToasts((prev) => prev.filter((t) => t.id !== id)), ttl);
  }, []);

  const dismiss = useCallback((id: number) => {
    setToasts((prev) => prev.filter((t) => t.id !== id));
  }, []);

  useEffect(() => {
    const unsubscribe = window.api.onProgress((event) => {
      setProgress(event);
      if (event.percent === undefined || event.percent >= 100) {
        window.setTimeout(() => setProgress((prev) => (prev === event ? null : prev)), 1200);
      }
      // 安装进度单独保存，由安装流程结束时显式清除
      if (event.scope === 'install') setInstallProgress(event);
    });
    // contextBridge 对返回的函数做代理，这里做一层兜底，避免清理函数缺失时报错
    return typeof unsubscribe === 'function' ? unsubscribe : undefined;
  }, []);

  const clearInstallProgress = useCallback(() => setInstallProgress(null), []);

  const refreshPrereqs = useCallback(async () => {
    try {
      const status = await window.api.prereq.check();
      setPrereqs(status);
      setPrereqsLoaded(true);
    } catch (err) {
      setPrereqsLoaded(true);
      notify('error', err instanceof Error ? err.message : String(err));
    }
  }, [notify]);

  const downloadPrereq = useCallback(
    async (id: PrereqId) => {
      setBusy('正在下载前置资源…');
      try {
        const result = await window.api.prereq.download(id);
        setPrereqs(result.status);
        notify(result.ok ? 'success' : 'error', result.message);
      } catch (err) {
        notify('error', err instanceof Error ? err.message : String(err));
      } finally {
        setBusy(null);
        setProgress(null);
      }
    },
    [notify],
  );

  const run = useCallback(
    async <T,>(label: string, fn: () => Promise<T>): Promise<T | undefined> => {
      setBusy(label);
      try {
        return await fn();
      } catch (err) {
        notify('error', err instanceof Error ? err.message : String(err));
        return undefined;
      } finally {
        setBusy(null);
      }
    },
    [notify],
  );

  const setSnapshotSafe = useCallback((next: ProjectSnapshot) => setSnapshot(next), []);

  const runSnapshot = useCallback(
    async (label: string, fn: () => Promise<ProjectSnapshot>) => {
      setBusy(label);
      try {
        const next = await fn();
        setSnapshot(next);
      } catch (err) {
        notify('error', err instanceof Error ? err.message : String(err));
      } finally {
        setBusy(null);
      }
    },
    [notify],
  );

  const pickAndOpenApk = useCallback(async () => {
    const path = await window.api.dialog.openApk();
    if (!path) return;
    setBusy('正在解析 APK…');
    try {
      const next = await window.api.project.open(path);
      setSnapshot(next);
      notify('success', `已导入 ${next.apk.fileName}`);
      if (next.warnings.length > 0) {
        notify('info', `检测到 ${next.warnings.length} 条资源一致性问题，详见「工程」页。`);
      }
    } catch (err) {
      notify('error', err instanceof Error ? err.message : String(err));
    } finally {
      setBusy(null);
    }
  }, [notify]);

  const reloadSnapshot = useCallback(async () => {
    try {
      const next = await window.api.project.snapshot();
      setSnapshot(next);
    } catch {
      setSnapshot(null);
    }
  }, []);

  const closeProject = useCallback(async () => {
    await window.api.project.close();
    setSnapshot(null);
    notify('info', '已关闭当前工程');
  }, [notify]);

  const value = useMemo<StoreValue>(
    () => ({
      prereqs,
      prereqsLoaded,
      snapshot,
      busy,
      progress,
      installProgress,
      clearInstallProgress,
      toasts,
      lastExportPath,
      notify,
      dismiss,
      setLastExportPath,
      refreshPrereqs,
      downloadPrereq,
      pickAndOpenApk,
      reloadSnapshot,
      closeProject,
      setSnapshot: setSnapshotSafe,
      run,
      runSnapshot,
    }),
    [
      prereqs,
      prereqsLoaded,
      snapshot,
      busy,
      progress,
      installProgress,
      clearInstallProgress,
      toasts,
      lastExportPath,
      notify,
      dismiss,
      refreshPrereqs,
      downloadPrereq,
      pickAndOpenApk,
      reloadSnapshot,
      closeProject,
      setSnapshotSafe,
      run,
      runSnapshot,
    ],
  );

  return <StoreContext.Provider value={value}>{children}</StoreContext.Provider>;
}
