/// <reference types="vitest/config" />
import { fileURLToPath, URL } from 'node:url'
import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: { alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) } },
  // Same origin in dev as in production (Nginx): cookies and CSRF work without CORS.
  // Port 5180: 5173 is used by other projects on the dev PC.
  // changeOrigin: false keeps the browser's Host header, so Spring sees a same-origin request (as behind
  // Nginx with proxy_set_header Host $host). The string shorthand would rewrite Host and fail CORS.
  server: { port: 5180, strictPort: true, proxy: {
    '/api': { target: 'http://localhost:8080', changeOrigin: false },
    '/ws': { target: 'ws://localhost:8080', ws: true, changeOrigin: false }, // live bin status (STOMP)
  } },
  preview: { port: 5180, strictPort: true },
  build: { target: 'es2022', manifest: true }, // manifest: lets check-size.mjs measure the worker app
  test: { environment: 'jsdom', globals: true, setupFiles: ['./src/test-setup.ts'] },
})
