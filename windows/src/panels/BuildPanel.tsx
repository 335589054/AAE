import { useMemo, useState, type JSX } from 'react';
import { useStore } from '../state';
import { Badge, Button, Card, Field, Modal, ProgressBar, Select, TextInput, Toggle } from '../components/ui';
import { formatBytes } from '../lib';
import type { BuildResult, KeystoreSelection } from '../../shared/types';

const PACKAGE_RE = /^[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z][a-zA-Z0-9_]*)+$/;

export function BuildPanel({ onNavigate }: { onNavigate: (page: 'install') => void }): JSX.Element {
  const { snapshot, run, notify, progress, setSnapshot, setLastExportPath } = useStore();
  const [renamePackage, setRenamePackage] = useState(false);
  const [packageName, setPackageName] = useState('');
  const [rewriteIdentifiers, setRewriteIdentifiers] = useState(true);
  const [keystoreMode, setKeystoreMode] = useState<'auto' | 'existing'>('auto');
  const [ksPath, setKsPath] = useState('');
  const [ksStorePass, setKsStorePass] = useState('');
  const [ksAlias, setKsAlias] = useState('');
  const [ksKeyPass, setKsKeyPass] = useState('');
  const [result, setResult] = useState<BuildResult | null>(null);
  const [log, setLog] = useState<string[]>([]);
  const [creatingKs, setCreatingKs] = useState(false);
  const [newKsPath, setNewKsPath] = useState('');
  const [newKsPass, setNewKsPass] = useState('');
  const [newKsAlias, setNewKsAlias] = useState('arcaea');
  const [newKsCn, setNewKsCn] = useState('Arcaea Apk Manager');

  const apk = snapshot?.apk;

  const packageValid = useMemo(
    () => !renamePackage || PACKAGE_RE.test(packageName.trim()),
    [renamePackage, packageName],
  );

  if (!snapshot || !apk) {
    return (
      <Card title="打包与签名">
        <p className="text-[12px] text-slate-400">请先在「工程」页导入 APK。</p>
      </Card>
    );
  }

  const keystoreSelection: KeystoreSelection =
    keystoreMode === 'auto'
      ? { mode: 'auto' }
      : {
          mode: 'existing',
          path: ksPath,
          storePassword: ksStorePass,
          keyAlias: ksAlias,
          keyPassword: ksKeyPass || ksStorePass,
        };

  const startExport = async (): Promise<void> => {
    if (renamePackage && !packageValid) {
      notify('error', '包名不合法：需要至少两段、每段以字母开头的 Java 包名，例如 com.example.arcaea');
      return;
    }
    if (keystoreMode === 'existing' && (!ksPath || !ksAlias)) {
      notify('error', '请先选择 keystore 文件并填写 key alias');
      return;
    }

    const defaultName = renamePackage
      ? `${packageName.trim().split('.').pop() || 'arcaea'}_mod.apk`
      : apk.fileName.replace(/\.apk$/i, '') + '_mod.apk';
    const outPath = await window.api.dialog.saveFile(defaultName, [
      { name: 'Android 安装包', extensions: ['apk'] },
    ]);
    if (!outPath) return;

    setLog([]);
    setResult(null);
    await run('正在打包并签名…', async () => {
      const buildResult = await window.api.build.export({
        outPath,
        packageName: renamePackage ? packageName.trim() : undefined,
        rewriteIdentifiers,
        keystore: keystoreSelection,
        keepUnsigned: false,
        cleanupTemp: true,
      });
      setResult(buildResult);
      setLog(buildResult.log);
      setLastExportPath(buildResult.outPath);
      notify('success', `导出完成：${buildResult.outPath}`);
      const next = await window.api.project.snapshot();
      if (next) setSnapshot(next);
    });
  };

  return (
    <div className="flex flex-col gap-3">
      <Card title="包名">
        <div className="flex flex-col gap-3">
          <div className="flex flex-wrap items-center gap-2">
            <Button
              size="sm"
              variant={renamePackage ? 'ghost' : 'primary'}
              onClick={() => setRenamePackage(false)}
            >
              保持原包名
            </Button>
            <Button
              size="sm"
              variant={renamePackage ? 'primary' : 'ghost'}
              onClick={() => {
                setRenamePackage(true);
                if (!packageName) setPackageName(`${apk.originalPackageName}.mod`);
              }}
            >
              指定新包名
            </Button>
            <Badge tone="slate">原包名：{apk.originalPackageName}</Badge>
          </div>

            {renamePackage && (
              <>
                <Field
                  label="新的包名"
                  hint="会写入二进制 AndroidManifest.xml 的 <manifest package>，由本工具直接补丁，无需 apktool"
                >
                  <TextInput
                    value={packageName}
                    onChange={setPackageName}
                    placeholder="com.example.arcaea"
                  />
                </Field>
                {!packageValid && (
                  <p className="text-[12px] text-rose-300">
                    包名不合法：每段必须以字母开头，只能包含字母、数字、下划线，且至少两段。
                  </p>
                )}

                <Toggle
                  checked={rewriteIdentifiers}
                  onChange={setRewriteIdentifiers}
                  label="同时改写自定义权限名与 Provider 授权名（与原版共存时必须开启）"
                />
                <p className="text-[11.5px] leading-relaxed text-slate-400">
                  清单里的
                  <span className="mx-1 font-mono">{apk.originalPackageName}.permission.C2D_MESSAGE</span>
                  、
                  <span className="mx-1 font-mono">{apk.originalPackageName}.provider</span>
                  这类标识符在全机范围内唯一。不改写就与原版同时安装，会先报
                  <span className="mx-1 font-mono">INSTALL_FAILED_DUPLICATE_PERMISSION</span>
                  ，即使绕过还会卡在
                  <span className="mx-1 font-mono">INSTALL_FAILED_CONFLICTING_PROVIDER</span>。
                  本工具只改写「权限名 / 授权名」，不会改动组件类名。
                </p>

                <p className="text-[12px] leading-relaxed text-amber-200/85">
                  注意：改包名后这是一个全新应用，<strong>不会</strong>继承原版的存档、登录状态与已购内容，
                  也不会覆盖原版 Arcaea。如果只想替换资源且可以卸载原版，建议保持原包名。
                </p>
              </>
            )}
        </div>
      </Card>

      <Card
        title="签名"
        actions={
          <Button size="sm" onClick={() => setCreatingKs(true)}>
            新建 keystore
          </Button>
        }
      >
        <div className="flex flex-col gap-3">
          <div className="flex flex-wrap gap-2">
            <Button
              size="sm"
              variant={keystoreMode === 'auto' ? 'primary' : 'ghost'}
              onClick={() => setKeystoreMode('auto')}
            >
              自动签名（推荐）
            </Button>
            <Button
              size="sm"
              variant={keystoreMode === 'existing' ? 'primary' : 'ghost'}
              onClick={() => setKeystoreMode('existing')}
            >
              使用已有 keystore
            </Button>
          </div>

          {keystoreMode === 'auto' ? (
            <p className="text-[12px] leading-relaxed text-slate-400">
              会自动生成并复用一把固定的调试密钥（保存在工具配置目录的
              <span className="mx-1 font-mono text-slate-300">keystore/auto.keystore</span>
              ，口令 <span className="font-mono">android</span>）。保持同一把密钥可以反复覆盖安装；
              若要发布给他人安装，请改用你自己的 keystore 并妥善保管。
            </p>
          ) : (
            <div className="grid grid-cols-2 gap-3 xl:grid-cols-4">
              <Field label="keystore 文件" className="col-span-2">
                <div className="flex gap-2">
                  <TextInput value={ksPath} onChange={setKsPath} placeholder="D:\\keys\\my.jks" />
                  <Button
                    onClick={async () => {
                      const picked = await window.api.build.pickKeystore();
                      if (picked) setKsPath(picked);
                    }}
                  >
                    浏览
                  </Button>
                </div>
              </Field>
              <Field label="store 口令">
                <TextInput type="password" value={ksStorePass} onChange={setKsStorePass} />
              </Field>
              <Field label="key alias">
                <TextInput value={ksAlias} onChange={setKsAlias} />
              </Field>
              <Field label="key 口令（留空则与 store 相同）">
                <TextInput type="password" value={ksKeyPass} onChange={setKsKeyPass} />
              </Field>
            </div>
          )}
        </div>
      </Card>

      <Card
        title="导出"
        actions={
          <Button variant="primary" disabled={Boolean(!packageValid)} onClick={() => void startExport()}>
            开始打包并签名
          </Button>
        }
      >
        <div className="flex flex-col gap-3">
          <ul className="ml-4 list-disc space-y-1 text-[12px] leading-relaxed text-slate-400">
            <li>未改动的 zip 条目会原样直通拷贝，不会重新压缩整个安装包。</li>
            <li>所有条目按 4 字节对齐；未被压缩的 <span className="font-mono">lib/**.so</span> 会做 4096 字节页对齐（等价 zipalign -p）。</li>
            <li>原有 <span className="font-mono">META-INF</span> 签名会被移除，随后由 apksigner 生成 v1 + v2 + v3 签名并校验。</li>
          </ul>

          {progress?.scope === 'export' && (
            <div className="flex flex-col gap-1.5">
              <ProgressBar percent={progress.percent ?? 0} />
              <span className="text-[11.5px] text-slate-400">{progress.message}</span>
            </div>
          )}

          {result && (
            <div className="rounded-md border border-emerald-900/60 bg-emerald-950/30 p-3">
              <div className="text-[12.5px] font-medium text-emerald-300">导出成功</div>
              <div className="mt-1 font-mono text-[11.5px] text-slate-300">{result.outPath}</div>
              <div className="mt-1 text-[11.5px] text-slate-400">
                包名 {result.packageName} · {formatBytes(result.size)} · 用时{' '}
                {(result.durationMs / 1000).toFixed(1)} 秒
              </div>
              <div className="mt-2 flex gap-2">
                <Button size="sm" onClick={() => void window.api.shell.openPath(result.outPath)}>
                  打开所在位置
                </Button>
                <Button size="sm" variant="subtle" onClick={() => onNavigate('install')}>
                  去「无线安装」页安装
                </Button>
              </div>
              <p className="mt-2 text-[11.5px] leading-relaxed text-emerald-200/80">
                已导出完成。「无线安装」页会自动选用这个安装包；走完安装流程后，可到「缓存与清理」页
                清除工具缓存，并按提示清理手机端缓存。
              </p>
            </div>
          )}

          {log.length > 0 && (
            <div className="max-h-56 overflow-auto rounded-md border border-slate-800 bg-slate-950/60 p-2 font-mono text-[11px] leading-relaxed text-slate-400">
              {log.map((line, index) => (
                <div key={`${index}-${line.slice(0, 12)}`}>{line}</div>
              ))}
            </div>
          )}
        </div>
      </Card>

      <Modal
        open={creatingKs}
        title="新建签名密钥库（keystore）"
        onClose={() => setCreatingKs(false)}
        width="max-w-lg"
        footer={
          <>
            <Button variant="ghost" onClick={() => setCreatingKs(false)}>
              取消
            </Button>
            <Button
              variant="primary"
              disabled={!newKsPath || !newKsPass || !newKsAlias}
              onClick={async () => {
                const res = await run('正在生成 keystore…', () =>
                  window.api.build.createKeystore({
                    path: newKsPath,
                    storePassword: newKsPass,
                    keyAlias: newKsAlias,
                    keyPassword: newKsPass,
                    cn: newKsCn,
                  }),
                );
                if (res?.ok) {
                  notify('success', res.message);
                  setKsPath(newKsPath);
                  setKsStorePass(newKsPass);
                  setKsAlias(newKsAlias);
                  setKsKeyPass(newKsPass);
                  setKeystoreMode('existing');
                  setCreatingKs(false);
                } else if (res) {
                  notify('error', res.message);
                }
              }}
            >
              生成
            </Button>
          </>
        }
      >
        <div className="flex flex-col gap-3">
          <Field label="保存路径">
            <div className="flex gap-2">
              <TextInput value={newKsPath} onChange={setNewKsPath} placeholder="D:\\keys\\arcaea.jks" />
              <Button
                onClick={async () => {
                  const picked = await window.api.dialog.saveFile('arcaea.jks', [
                    { name: '密钥库', extensions: ['jks', 'keystore'] },
                  ]);
                  if (picked) setNewKsPath(picked);
                }}
              >
                选择
              </Button>
            </div>
          </Field>
          <Field label="口令" hint="PKCS12 密钥库要求 store 与 key 口令一致">
            <TextInput type="password" value={newKsPass} onChange={setNewKsPass} />
          </Field>
          <Field label="key alias">
            <TextInput value={newKsAlias} onChange={setNewKsAlias} />
          </Field>
          <Field label="CN（证书名）">
            <TextInput value={newKsCn} onChange={setNewKsCn} />
          </Field>
          <Select
            value="pkcs12"
            onChange={() => undefined}
            options={[{ value: 'pkcs12', label: '类型：PKCS12' }]}
            disabled
          />
        </div>
      </Modal>
    </div>
  );
}
