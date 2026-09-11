# JARVIS same-origin API proxy

该 Worker 只接管 `https://f.shengxia.me/api/*`，其余静态页面继续由 GitHub Pages 提供。

部署顺序：

1. 确认 `f.shengxia.me` DNS 由 Cloudflare 代理，并保持 GitHub Pages 为静态源站。
2. 在本目录执行 `npx wrangler deploy`，确认 `/api/health/ready` 能通过 `f.shengxia.me` 返回 Java readiness。
3. 将 GitHub 仓库 Actions Variable `VITE_API_MODE` 设置为 `same-origin`，重新运行 `Deploy Frontend to GitHub Pages`。
4. 将 GitHub OAuth App 的 callback URL 和生产 `GITHUB_REDIRECT_URI` 改为 `https://f.shengxia.me/api/auth/github/callback`。
5. 完成一次邮箱登录和 GitHub OAuth 登录回归。切换后原先仅存在于 `agent.shengxia.me` 的 host-only 登录 Cookie 不会迁移，用户需要重新登录一次。

回滚时先把 `VITE_API_MODE` 清空并重新部署前端，再删除 Worker Route。这样前端会恢复直接请求 `https://agent.shengxia.me/api/**`。

Worker 不保存 JWT、CSRF token 或业务数据，只透传 `/api/*` 请求。Java 后端仍是唯一认证和业务边界。
