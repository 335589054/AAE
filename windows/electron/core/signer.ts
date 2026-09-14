import fs from 'node:fs';
import path from 'node:path';
import { run } from './proc';
import { ensureDir, keystoreDir } from './paths';

export interface KeystoreInfo {
  path: string;
  storePassword: string;
  keyAlias: string;
  keyPassword: string;
}

export const AUTO_KEYSTORE_ALIAS = 'androiddebugkey';
export const AUTO_KEYSTORE_PASSWORD = 'android';

/** keytool 与 java 位于同一目录 */
export function keytoolFromJava(javaExe: string): string {
  const dir = path.dirname(javaExe);
  const candidate = path.join(dir, process.platform === 'win32' ? 'keytool.exe' : 'keytool');
  return fs.existsSync(candidate) ? candidate : 'keytool';
}

/** 自动生成并复用一把固定签名密钥，保证多次导出可以覆盖安装 */
export async function ensureAutoKeystore(
  javaExe: string,
  log: (message: string) => void,
): Promise<KeystoreInfo> {
  const dir = ensureDir(keystoreDir());
  const storePath = path.join(dir, 'auto.keystore');
  if (fs.existsSync(storePath)) {
    return {
      path: storePath,
      storePassword: AUTO_KEYSTORE_PASSWORD,
      keyAlias: AUTO_KEYSTORE_ALIAS,
      keyPassword: AUTO_KEYSTORE_PASSWORD,
    };
  }

  const keytool = keytoolFromJava(javaExe);
  log(`正在生成自动签名密钥：${storePath}`);
  const result = await run(
    keytool,
    [
      '-genkeypair',
      '-v',
      '-keystore',
      storePath,
      '-storetype',
      'PKCS12',
      '-alias',
      AUTO_KEYSTORE_ALIAS,
      '-keyalg',
      'RSA',
      '-keysize',
      '2048',
      '-validity',
      '10000',
      '-storepass',
      AUTO_KEYSTORE_PASSWORD,
      '-keypass',
      AUTO_KEYSTORE_PASSWORD,
      '-dname',
      'CN=Arcaea Apk Manager, OU=Local Build, O=Local, L=Local, ST=Local, C=CN',
    ],
    { timeoutMs: 120000 },
  );

  if (!result.started) {
    throw new Error(`无法启动 keytool（${keytool}）。请确认已安装 JDK 而不是仅安装 JRE。`);
  }
  if (result.code !== 0 || !fs.existsSync(storePath)) {
    throw new Error(`生成签名密钥失败：${(result.stderr || result.stdout).trim()}`);
  }
  return {
    path: storePath,
    storePassword: AUTO_KEYSTORE_PASSWORD,
    keyAlias: AUTO_KEYSTORE_ALIAS,
    keyPassword: AUTO_KEYSTORE_PASSWORD,
  };
}

export async function createKeystore(
  javaExe: string,
  info: KeystoreInfo,
  cn: string,
): Promise<{ ok: boolean; message: string }> {
  if (fs.existsSync(info.path)) return { ok: false, message: `文件已存在：${info.path}` };
  ensureDir(path.dirname(info.path));
  const keytool = keytoolFromJava(javaExe);
  const result = await run(
    keytool,
    [
      '-genkeypair',
      '-v',
      '-keystore',
      info.path,
      '-storetype',
      'PKCS12',
      '-alias',
      info.keyAlias,
      '-keyalg',
      'RSA',
      '-keysize',
      '2048',
      '-validity',
      '10000',
      '-storepass',
      info.storePassword,
      '-keypass',
      info.keyPassword,
      '-dname',
      `CN=${cn || 'Arcaea Apk Manager'}, OU=Local Build, O=Local, L=Local, ST=Local, C=CN`,
    ],
    { timeoutMs: 120000 },
  );
  if (!result.started) return { ok: false, message: '无法启动 keytool，请确认已安装 JDK。' };
  if (result.code !== 0) {
    return { ok: false, message: (result.stderr || result.stdout).trim() || 'keytool 执行失败' };
  }
  return { ok: true, message: `已创建签名密钥：${info.path}` };
}

export interface SignParams {
  javaExe: string;
  apksignerJar: string;
  keystore: KeystoreInfo;
  inputApk: string;
  outputApk: string;
  log: (message: string) => void;
}

/** 使用 apksigner 生成 v1 + v2 + v3 签名 */
export async function signApk(params: SignParams): Promise<void> {
  const { javaExe, apksignerJar, keystore, inputApk, outputApk, log } = params;
  if (fs.existsSync(outputApk)) fs.rmSync(outputApk, { force: true });

  log('正在使用 apksigner 签名（v1 + v2 + v3）…');
  const args = [
    '-jar',
    apksignerJar,
    'sign',
    '--ks',
    keystore.path,
    '--ks-pass',
    `pass:${keystore.storePassword}`,
    '--ks-key-alias',
    keystore.keyAlias,
    '--key-pass',
    `pass:${keystore.keyPassword}`,
    '--v1-signing-enabled',
    'true',
    '--v2-signing-enabled',
    'true',
    '--v3-signing-enabled',
    'true',
    '--out',
    outputApk,
    inputApk,
  ];

  const result = await run(javaExe, args, {
    timeoutMs: 0,
    onOutput: (chunk) => {
      const text = chunk.trim();
      if (text) log(text);
    },
  });

  if (!result.started) {
    throw new Error('无法启动 java，请检查 Java 运行环境是否可用。');
  }
  if (result.code !== 0 || !fs.existsSync(outputApk)) {
    const detail = (result.stderr || result.stdout).trim();
    throw new Error(`签名失败：${detail || `apksigner 退出码 ${result.code}`}`);
  }
}

/** 校验签名 */
export async function verifyApk(
  javaExe: string,
  apksignerJar: string,
  apkPath: string,
): Promise<{ ok: boolean; output: string }> {
  const result = await run(javaExe, ['-jar', apksignerJar, 'verify', '--verbose', '--print-certs', apkPath], {
    timeoutMs: 300000,
  });
  const output = `${result.stdout}\n${result.stderr}`.trim();
  return { ok: result.code === 0, output };
}
