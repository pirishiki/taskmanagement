import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    // /api で始まるリクエストをバックエンド（Spring Boot）に取り次ぐ
    proxy: {
      '/api': 'http://localhost:8080',
    },
  },
})
