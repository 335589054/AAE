// 用 esbuild 打包 Electron 主进程与预加载脚本。
// 全部使用 Node 内置模块，无需外部运行时依赖。
import { build } from 'esbuild';
import { rmSync, mkdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const outDir = join(root, 'dist-electron');
const watch = process.argv.includes('--watch');

rmSync(outDir, { recursive: true, force: true });
mkdirSync(outDir, { recursive: true });

const common = {
  bundle: true,
  platform: 'node',
  target: 'node20',
  format: 'cjs',
  sourcemap: true,
  logLevel: 'info',
  external: ['electron'],
};

const targets = [
  { entryPoints: [join(root, 'electron/main.ts')], outfile: join(outDir, 'main.js') },
  { entryPoints: [join(root, 'electron/preload.ts')], outfile: join(outDir, 'preload.js') },
];

for (const t of targets) {
  await build({ ...common, ...t });
}

if (watch) {
  console.log('[build-main] 已构建（watch 模式未启用增量，改动主进程后请重新运行 npm run build:main）');
}
