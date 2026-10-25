import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5190,
    strictPort: true,
    proxy: {
      '/api/v2': { target: 'http://127.0.0.1:8002', changeOrigin: true },
      '/api': {
        target: 'http://127.0.0.1:8002',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/api/, ''),
      },
    },
  },
})
