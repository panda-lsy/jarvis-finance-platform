# JARVIS 端到端测试（Playwright）

对应 SRS V1.1 新增需求第 4 条「自动化测试与测试智能体」。

本目录**自带 `package.json`，独立于 `frontend/`** —— Playwright 及其浏览器不会进入前端构建产物，
也不会改动前端的依赖树。这是刻意的隔离，请勿把 `@playwright/test` 加进 `frontend/package.json`。

---

## 1. 快速开始

```bash
cd e2e
npm install
npm run install:browsers      # 首次需要下载 chromium，约百余 MB

# 起前端（默认由调用方起服；只要用例不触达后端，前端一个进程就够）
cd ../frontend && npm run dev

# 回到 e2e 跑用例
cd ../e2e
npm test                      # 全部
npm run test:smoke            # 冒烟组：不依赖后端
npm run test:visual           # 四档 viewport 的工作区视觉回归
npm run test:visual:update    # 有意更新视觉时刷新基线截图
npm run typecheck             # 只做类型检查，不需要浏览器
npm run list                  # 只列出用例，不执行
```

也可让 Playwright 代管前端进程：

```bash
E2E_MANAGED_SERVER=1 npm test
```

> 后端（Java `:8200` / Python `:8100`）只有在用例真正触达业务接口时才需要启动。
> 当前 `smoke` 组不含任何后端依赖。

---

## 2. 目录结构

```
e2e/
├── playwright.config.ts      配置：projects 分 smoke / permissions / auth /
│                             financial-import / agent / tasks / visual-*（报告与追踪产物）
├── fixtures/test.ts          自定义 test，注入各 Page Object
├── pages/
│   ├── BasePage.ts           POM 基类 ← 对外契约（见第 3 节）
│   ├── LandingPage.ts        官网（iframe 承载）
│   ├── LoginPage.ts          登录视图
│   ├── WorkspacePage.ts      登录后的工作台外壳
│   ├── VisualWorkspacePage.ts 视觉回归专用（档案过场 + 稳定快照）
│   ├── ScheduledTasksPage.ts  定时任务功能用例（走 data-testid 单轨，见第 4 节）
│   └── TaskPermissionPage.ts  定时任务权限置灰契约（本页无 testid，走 role + 文案）
├── utils/
│   ├── env.ts                环境变量与三栈地址
│   └── selectors.ts          data-testid 命名约定 + 回退选择器（见第 4 节）
└── tests/
    ├── smoke.spec.ts         冒烟：不依赖后端
    ├── auth.spec.ts          登录与权限
    ├── financial-import.spec.ts 财报解析导入流程
    ├── agent.spec.ts         研究智能体浏览器验收
    ├── scheduled-tasks.spec.ts  定时任务核心流程（22 条，全打桩）
    ├── task-permission.spec.ts  定时任务写操作的权限置灰（7 条，全打桩）
    └── visual.spec.ts        Market / Research / Chain / Strategy / Execution 视觉回归
```

---

## 3. POM 基类契约（**已冻结**）

`pages/BasePage.ts` 是对外提供给其他模块复用的稳定接口。**方法名与语义已冻结，不要改名**；
需要扩展时新增方法，不要修改既有签名。

| 方法 | 说明 |
|---|---|
| `goto(query?)` | 跳转到该 Page 的 `path`，可选 query 对象（自动编码） |
| `byTestId(id, fallbackSelector?)` | 双轨定位：优先 `[data-testid]`，回退既有选择器 |
| `byRole(role, name)` | 按 ARIA role + 名称定位 |
| `expectVisible(locator, description)` | 断言可见，`description` 会出现在失败信息里 |
| `expectHidden(locator, description)` | 断言不可见 |
| `expectText(locator, expected, description)` | 断言文本 |
| `expectUrlContains(fragment)` | 断言地址栏包含片段 |
| `fillField(locator, value, description)` | 断言可见后填写 |
| `clickAndWait(locator, description)` | 断言可见后点击 |
| `waitForApi(pathFragment)` | 等待 URL 含该片段的响应 |
| `screenshot(name)` | 截图到 `test-results/screenshots/`，供缺陷报告引用 |

约定：

- 定位与动作方法为 `protected`，请**通过继承 `BasePage` 暴露给用例**，不要在用例里直接调 `page.locator`。
- 断言必须传 `description`，且描述「哪个功能坏了」而非「哪个元素找不到」——
  测试智能体要靠它生成可读的缺陷报告。
- 每个页面一个 Page Object，路径写在 `path` 字段里。

新增页面对象的模板（**不需要写 `constructor`**，基类构造器可直接继承）：

```ts
import { type Locator } from '@playwright/test'
import { BasePage } from './BasePage'

export class RssFeedPage extends BasePage {
  readonly path = '/'

  get list(): Locator {
    return this.byTestId('info-rss-list', '[data-legacy="rss-list"]')
  }
}
```

---

## 4. `data-testid` 命名约定（**已冻结**）

格式：`<域>-<模块>-<元素>`，全小写、连字符分隔。

| 域（Domain） | 对应一级业务域 |
|---|---|
| `market` | 行情中心 |
| `research` | 投研工作台 |
| `strategy` | 策略中心 |
| `risk` | 风险中心 |
| `info` | 信息中心 |
| `system` | 系统管理 |
| `auth` | 登录 / 注册 / 重置 |
| `common` | 跨域公共组件（导航、外壳等） |

示例：

```
nav-domain-market          一级域「行情中心」导航项
nav-item-live-quote        二级项「实时行情」
auth-login-email           登录邮箱输入框
task-list-create-btn       定时任务「新建」按钮
risk-alert-detail-chart    风险预警详情图表
```

> 六个域即 SRS V1.1 的二级菜单信息架构（A 线会落地 `navConfig.js`），
> 命名与之一一对应，改造后可直接套用。

**现状与过渡**（2026-09-22 核实）：`data-testid` 已**部分补齐** ——
`pages/ScheduledTasksPage.vue` 有 33 个（统一前缀 `system-task-`），其余页面仍为空。
因此本套件用 `byTestId(id, fallback)` 走双轨：**已补属性的页面收敛为单轨**
（`ScheduledTasksPage.ts`），未补的页面继续走回退选择器。
`utils/selectors.ts` 的 `FALLBACK` 表记录的是**当前真实存在**的 `id` / `role`
（如 `#auth-email`、`[role="alert"]`）。
补 `data-testid` 时**只加属性**，不要顺带改结构或样式，以免与队友分支冲突。

---

## 5. 环境变量

| 变量 | 默认值 | 说明 |
|---|---|---|
| `E2E_BASE_URL` | `http://localhost:5173` | 前端地址（**用 `localhost`**：本机 Vite 只监听它，`127.0.0.1` 连不上） |
| `E2E_API_URL` | `http://127.0.0.1:8200` | Java 地址（仅测试数据准备用，用例不要绕过前端） |
| `E2E_USER_EMAIL` / `E2E_USER_PASSWORD` | 空 | 普通用户凭据；未配置则相关用例跳过 |
| `E2E_ADMIN_EMAIL` / `E2E_ADMIN_PASSWORD` | 空 | 管理员凭据；未配置则相关用例跳过 |
| `E2E_MANAGED_SERVER` | `0` | 设为 `1` 时由 Playwright 拉起 Vite dev server |
| `CI` | — | 由 CI 环境自动设置，控制重试与并发 |

⚠️ 凭据只经环境变量注入，**绝不写进仓库**。复用登录态的 `.auth/` 已在 `.gitignore` 中排除。

---

## 6. 已知限制

1. **`?preview=1` 免登录模式仅在 dev + localhost 生效**（见 `frontend/src/App.vue` 的 `localPreview`）。
   生产构建、`vite preview`、GitHub Pages 下该模式不可用，相关用例会自动跳过 ——
   这类环境下要验证工作台需配真实账号。
2. **注册 / 重置密码链路未覆盖**：两者都依赖邮箱验证码，需要外部邮件通道，不适合放进浏览器 E2E。
3. **官网是 iframe**：`LandingPage.vue` 内嵌 `/landing/index.html`，断言必须走 `frameLocator`。
4. **CI 集成尚未开启**：新增 `e2e` job 属于后续任务（待确定门禁强度后接入）。
5. **RSS 相关用例不在本目录**：按分工由信息中心负责人在本套件的 POM 基类之上编写。

### 视觉回归约定

- 基线覆盖 `1600×900`、`1280×800`、`1024×768`、`768×1024`。
- 用例使用固定 API fixture、`reduced-motion` 与稳定化样式，避免实时价格、时间和新闻造成假失败。
- 视觉 project 固定 `workers=1`，避免多个 Archive Sea WebGL 场景并发争抢 GPU 导致过场超时或像素抖动。
- 只有确认视觉变化是有意设计时才运行 `npm run test:visual:update` 并提交对应快照。
