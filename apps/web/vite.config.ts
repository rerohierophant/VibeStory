import { fileURLToPath } from 'node:url';
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';
import path from 'node:path';

const root = fileURLToPath(new URL('.', import.meta.url));

export default defineConfig({
  // 设计素材直接在 dev 期间静态提供：/style/*.png
  publicDir: path.resolve(root, '../../design/assets/images'),
  plugins: [react(), tailwindcss()],
  resolve: {
    alias: {
      '@': path.resolve(root, 'src'),
      '@design': path.resolve(root, '../../design'),
    },
  },
  server: {
    port: 5173,
    fs: { allow: [path.resolve(root, '../..')] },
  },
});
