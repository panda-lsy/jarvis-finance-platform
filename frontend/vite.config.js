import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// GitHub Pages 部署: 自定义域名 f.shengxia.me 映射到根路径, 故 base='/' (根路径部署)
export default defineConfig({
  base: '/',
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
