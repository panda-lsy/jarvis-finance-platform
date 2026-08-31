import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// GitHub Pages 部署在 /gold-trading/ 子路径下
export default defineConfig({
  base: '/gold-trading/',
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
