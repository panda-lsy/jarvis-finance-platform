# JARVIS 测试智能体

把需求文档转成可追踪的验收用例，并可调用仓库现有的 Playwright 用例生成脱敏缺陷报告。

```powershell
# 只读 PRD 并生成用例/报告，不启动浏览器
node tools/test-agent/test-agent.mjs --prd specs/financial-agent-workflow/requirements.md

# 在已启动前端的环境执行 smoke，并将结果合并到报告
node tools/test-agent/test-agent.mjs --run --project smoke --base-url https://f.shengxia.me

# 在已配置 E2E_USER_EMAIL / E2E_USER_PASSWORD 的真实环境执行 Agent 专项
node tools/test-agent/test-agent.mjs --run --project agent --base-url https://f.shengxia.me
```

输出目录默认是 `.test-agent/latest/`，其中 `test-cases.json` 是结构化用例，
`defect-report.md` 是评审报告。该工具不生成、保存或打印账号密码、Cookie、Token、API Key；
真实登录仍由 `E2E_USER_*` / `E2E_ADMIN_*` 环境变量提供，Playwright 登录态目录也不入库。

## 导航闪烁审计

在已启动的 Vite 前端和开启远程调试的 Edge 页面上执行：

```powershell
# Node.js 22+
$env:APP_URL = 'http://localhost:5173/'
node tools/test-agent/nav-flicker-audit.mjs

# Node.js 20.10+
node --experimental-websocket tools/test-agent/nav-flicker-audit.mjs
```

`APP_URL` 默认为 `http://localhost:5173/`，也兼容 `127.0.0.1` 与 `[::1]`；
远程调试端口可通过 `CDP_PORT` 覆盖，默认值为 `9333`。
