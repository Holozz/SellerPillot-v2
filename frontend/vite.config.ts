import { defineConfig,loadEnv } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig(({mode})=>{
  const env=loadEnv(mode,process.cwd(),'VITE_')
  const target=env.VITE_API_TARGET||(env.VITE_BACKEND==='java'?'http://127.0.0.1:8081':'http://127.0.0.1:8002')
  return {
  plugins: [react()],
  server: {
    port: 5190,
    strictPort: true,
    proxy: {
      '/api/v2': { target, changeOrigin: true },
      '/api': {
        target,
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/api/, ''),
      },
    },
  },
  }
})
