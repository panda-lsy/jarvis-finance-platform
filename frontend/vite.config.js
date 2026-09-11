import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// GitHub Pages 部署: 自定义域名 f.shengxia.me 映射到根路径, 故 base='/' (根路径部署)
export default defineConfig({
  base: '/',
  plugins: [vue()],
  build: {
    // GitHub Pages targets modern browsers. The injected modulepreload
    // MutationObserver shim is incompatible with the embedded preview
    // browser, while native modulepreload is supported by the target.
    modulePreload: {
      polyfill: false,
    },
  },
  server: {
    port: 5173,
    // 浏览器只访问 Java 主后端；Java 再通过内部令牌调用本机 Python AI 服务。
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:8200',
        changeOrigin: true
      }
    }
  }
})
