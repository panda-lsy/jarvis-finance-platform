# JARVIS 金融投研平台前端

Vue 3 + Vite 金融研究终端。浏览器只访问 Java 主后端 `/api/**`；Python AI 服务只允许 Java 通过内部服务令牌调用，前端不直连 Python 或第三方行情源。

## 本地开发

```bash
cd frontend
npm install
npm run dev
```

默认访问 `http://localhost:5173/`。Vite 将 `/api/**` 代理到 Java `http://127.0.0.1:8200`。

## P0 测试与构建

```bash
npm run test:p0
npm run build
```

P0 测试使用 Node 20 内置 `node:test`，验证秒级行情 EventSource 解析/关闭行为，并扫描 `src/`，禁止浏览器代码出现 Python 8100、`/py` 内部路由或 AI/内部服务 Secret。CI 会在生产构建前执行该门禁。构建产物位于 `dist/`。

## 生产入口

生产前端由 GitHub Pages 提供，入口为 `https://f.shengxia.me`；GitHub Actions 从 GitHub `main` 分支构建并发布，`frontend/public/CNAME` 固定自定义域名。

默认 API 仍访问 `https://agent.shengxia.me/api/**`。仓库已经支持同源 API 迁移：先部署 `deploy/cloudflare/api-proxy/` 的 Cloudflare Worker Route，再把 GitHub Actions Variable `VITE_API_MODE` 设置为 `same-origin`，前端就会改为访问 `https://f.shengxia.me/api/**`。不要在 Worker Route 未上线时提前打开该变量，否则 GitHub Pages 会对 `/api/**` 返回 404。

Gitee 是代码镜像，不作为 Pages 的直接发布源。修改前端后需要同步推送 GitHub `main`，等待 `Deploy Frontend to GitHub Pages` 完成。Ubuntu/Nginx 仅承载 `agent.shengxia.me` 后端反向代理，不再承载 `f.shengxia.me` 静态文件。

认证使用 Java 写入的 HttpOnly JWT Cookie；浏览器写操作同时携带 Cookie-CSRF `X-XSRF-TOKEN`。登录成功后前端会立即调用 `/api/auth/me` 二次确认 Cookie 会话；CSRF 失效会刷新 token 并仅重试一次，401 才会触发会话失效确认，403/5xx/网络错误不会误清空当前用户。不要在前端保存 JWT、AI API Key、GitHub Client Secret 或 Python 内部服务令牌。

## 实时行情

- 黄金 ETF、伦敦金、积存金：`GET /api/market/prices/stream` SSE 秒级推送。
- SSE 异常时行情页保留 30 秒 HTTP 轮询兜底。
- A 股、美股、Crypto：当前报价 1 秒刷新；K 线与交易时段维持低频刷新，避免每秒请求历史接口。
- 模拟盘订单票据复用秒级 SSE 报价；后端成交与风控同样优先使用秒级内存行情。

## 主要页面

```text
src/
├── pages/
│   ├── MarketPage.vue        # 黄金/伦敦金/积存金行情工作台
│   └── BacktestPage.vue      # 双均线可复现回测
├── components/
│   ├── CrossMarketView.vue   # A股/美股/Crypto 多市场终端
│   ├── SimTradeView.vue      # 模拟交易与风控状态
│   ├── AiCenter.vue          # AI 研究助手
│   ├── AdminView.vue         # 用户/配额/权限管理
│   └── LoginView.vue         # 登录/注册/密码重置
├── api/client.js             # 统一 Java API 客户端
└── composables/              # 图表、轮询、会话、freshness 等复用逻辑
```

## 技术栈

- Vue 3
- Vite 5
- ECharts 5
- SSE / EventSource
