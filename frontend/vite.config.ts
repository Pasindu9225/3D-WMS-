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
  server: { port: 5180, strictPort: true, proxy: { '/api': 'http://localhost:8080' } },
  preview: { port: 5180, strictPort: true },
  build: { target: 'es2022' },
  test: { environment: 'jsdom', globals: true },
})
