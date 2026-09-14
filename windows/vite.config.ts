import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// 渲染进程（React）构建配置。
// base 使用相对路径，保证打包后 Electron 以 file:// 协议加载 dist/index.html 时资源可用。
export default defineConfig({
  root: __dirname,
  base: './',
  plugins: [react()],
  server: {
    port: 5173,
    strictPort: true,
  },
  build: {
    outDir: 'dist',
    emptyOutDir: true,
  },
});
