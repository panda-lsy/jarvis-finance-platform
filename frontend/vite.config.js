import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// GitHub Pages 部署在 /jarvis-finance-platform/ 子路径下 (仓库名)
export default defineConfig({
  base: '/jarvis-finance-platform/',
  plugins: [vue()],
  server: {
    port: 5173,
    // 本地开发代理到 Java 主后端, 避免跨域
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:8200',
        changeOrigin: true
      }
    }
  }
})
