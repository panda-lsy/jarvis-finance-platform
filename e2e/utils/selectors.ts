import type { Page } from '@playwright/test'

/**
 * 选择器与 `data-testid` 命名约定 —— 对应 SRS V1.1「自动化测试」的跨域契约 **S3**。
 *
 * 约定：`<域>-<模块>-<元素>`，全小写、连字符分隔。
 *
 *   market-live-price-panel      行情中心的实时行情面板
 *   task-list-create-btn         系统管理 > 定时任务的「新建」按钮
 *   nav-domain-market            一级域「行情中心」的导航项
 *   risk-alert-detail-chart      风险中心的预警详情图表
 *
 * ── 现状（2026-09-22 核实）──────────────────────────────────────
 * 前端已**部分**补上 `data-testid`：`pages/ScheduledTasksPage.vue` 有 33 个
 * （统一前缀 `system-task-`，命名域 `system`），其余页面仍为空。
 * 因此本套件继续用「优先 testid、回退既有选择器」的双轨查找：
 * 已补属性的页面可收敛为单轨（见 `pages/ScheduledTasksPage.ts`），
 * 未补的页面照旧走回退选择器，不需要等谁先改完。
 *
 * ⚠️ 新增 `data-testid` 时只加属性、不要顺带改结构或样式，避免与队友的分支互相冲突。
 */

/** 六个一级业务域（对齐 SRS V1.1 的二级菜单信息架构）+ 认证与公共域。 */
export const DOMAIN = {
  market: 'market',
  research: 'research',
  strategy: 'strategy',
  risk: 'risk',
  info: 'info',
  system: 'system',
  auth: 'auth',
  common: 'common',
} as const

export type Domain = (typeof DOMAIN)[keyof typeof DOMAIN]

/** 按约定拼装 testid，避免各处手写字符串导致拼写漂移。 */
export function buildTestId(domain: Domain, module: string, element: string): string {
  return [domain, module, element].join('-')
}

/** testid → CSS 选择器。 */
export function cssTestId(id: string): string {
  return `[data-testid="${id}"]`
}

/**
 * 既有选择器回退表。
 *
 * 全部取自当前前端真实存在的 `id` / `role`，**不是猜测**：
 * - `#auth-email` / `#auth-password`：`components/LoginView.vue`
 * - `[role="alert"]` / `[role="status"]`：`LoginView.vue` 的错误与提示区
 * - `.landing-frame iframe`：`pages/LandingPage.vue`（官网是 iframe 承载的）
 * - `.container`：`App.vue` 登录后的工作台外壳
 */
export const FALLBACK = {
  loginEmail: '#auth-email',
  loginPassword: '#auth-password',
  loginSubmit: '.auth-form button[type="submit"]',
  authError: '[role="alert"]',
  authNotice: '[role="status"]',
  landingFrame: '.landing-frame iframe',
  workspaceContainer: '.container',
} as const

/** 官方首页 iframe 内的「进入 JARVIS」入口文案（LandingPage.vue 以文案判定点击）。 */
export const LANDING_ENTER_TEXT = '进入 JARVIS'

/** 供 POM 使用的页面定位器集合。 */
export class Selectors {
  constructor(private readonly page: Page) {}

  testId(id: string) {
    return this.page.locator(cssTestId(id))
  }

  fallback(selector: string) {
    return this.page.locator(selector)
  }
}
