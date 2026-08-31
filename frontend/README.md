# 贾维斯黄金 - Vue.js 演示前端

纯 GitHub Pages 展示前端，与后端分离。展示黄金历史K线 + 双均线回测。

## 本地开发

```bash
cd frontend
npm install
npm run dev        # http://localhost:5173/gold-trading/
```

开发模式通过 vite 代理将 `/api` 转发到本机后端 `http://127.0.0.1:8100`。

## 构建

```bash
npm run build      # 产物在 dist/
```

## 部署到 GitHub Pages

仓库已配置 GitHub Actions (`deploy-frontend.yml`)，推送到 `main` 且改动 `frontend/` 时自动构建并部署到：

```
https://<你的用户名>.github.io/gold-trading/
```

部署前需在仓库 Settings → Pages 开启：**Source = GitHub Actions**。

## 连接后端

前后端分离，纯静态页无法直接访问你本机的后端，需要**通过 URL 参数指定后端地址**：

```
https://<你的用户名>.github.io/gold-trading/?api=http://<你本机IP>:8100
```

- 本机后端需监听 `0.0.0.0:8100`（已开启 CORS）
- `<你本机IP>` 为局域网/公网可达地址
- 未带 `?api=` 时，页面显示"无法连接后端"，但结构仍可展示

## 目录

```
frontend/
├── src/
│   ├── App.vue          # 主页面(概览+K线+回测)
│   ├── api/client.js    # API 客户端 + 地址解析
│   ├── main.js          # 入口
│   └── style.css        # 样式
├── public/              # 静态资源
├── vite.config.js       # base=/gold-trading/ + 开发代理
└── package.json
```

## 技术栈

- Vue 3 + Vite 5
- ECharts 5（K线图、净值曲线）
