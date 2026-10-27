/// <reference types="vitest/config" />
import { defineConfig, loadEnv } from 'vite'
import react from '@vitejs/plugin-react'

// The dev server proxies /api to the Spring Boot API, so the browser sees a single origin
// and no CORS configuration is needed in development.
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  const target = env.PIDEA_API_TARGET || 'http://127.0.0.1:8080'
  return {
    plugins: [react()],
    server: {
      // Explicit loopback IPv4: Node can resolve "localhost" to ::1 only (seen on Windows), leaving
      // 127.0.0.1 unreachable. Loopback only, so the dev server is not exposed to the LAN.
      host: '127.0.0.1',
      port: 5173,
      strictPort: true,
      proxy: { '/api': { target, changeOrigin: false } },
    },
    test: {
      environment: 'jsdom',
      globals: true,
      include: ['src/**/*.test.{ts,tsx}'],
      setupFiles: ['./src/test/setup.ts'],
      css: false,
    },
  }
})
