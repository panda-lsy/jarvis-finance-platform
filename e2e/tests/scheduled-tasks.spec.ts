import { expect, test } from '../fixtures/test'
import { PREVIEW_PARAMS } from '../utils/env'
import { type Page, type Route } from '@playwright/test'

/**
 * 系统管理 · 定时任务（SRS V1.1「覆盖任务管理核心流程」）。
 *
 * ── 为什么全部打桩 ──────────────────────────────────────────────
 * 这里覆盖的是**前端交互契约**：菜单能否到达、列表如何渲染、弹窗如何开关、
 * 保存时提交了什么 body、状态机（ACTIVE/PAUSED）如何影响按钮。这些与后端数据无关，
 * 打桩后 ① 只起 Vite 就能跑，不需要 Java/Python；② 不会因为真实任务的增删而假失败。
 * 后端行为另由 Java 单测覆盖（`DailyDigestExecutorTest` 等）。
 *
 * ── 桩字段名的依据 ──────────────────────────────────────────────
 * 取自 `ScheduledTasksPage.vue` 的实际读取路径：`task.task_type` / `task.cron_expr` /
 * `task.next_run_at` / `task.last_run_status` / `task.last_error` / `task.params`。
 * `params` 是**扁平 camelCase**（`changeThresholdPct` 而非 `change_threshold_pct`）
 * —— 见 `openEdit()` 的 `params.xxx` 读法。
 *
 * ── 三个踩过的坑（改本文件前务必知道）──────────────────────────
 * 1. **写请求要先有 CSRF**：前端 POST 前会 GET `/api/auth/csrf` 取 `data.token`。
 *    桩不返回 token 时，前端抛「无法获取 CSRF token」且**根本不发出写请求** ——
 *    表现为断言「没抓到任何请求」，极易误判成按钮没绑事件。
 * 2. **不要拦「全部请求」**：见下方 `API_ONLY` 的说明。
 * 3. **headless 下必须 `reducedMotion: 'reduce'`**（已在 `playwright.config.ts` 的
 *    `tasks` project 里设好）：否则 Archive Sea 的 WebGL 过场 settle 不了，工作台
 *    外壳永远不出现，用例会一路等满超时。
 *
 * ⚠️ 已知环境限制：用例**失败时** `browser.close` 偶发挂死，导致 reporter 写不出结果，
 * 终端看起来像「无输出地卡住」而不是「失败」。此时用 `DEBUG=pw:api` 看日志尾部——
 * 若停在 `browser.close started`，说明用例其实已经失败并截了图，只是结果没打印出来。
 */

/**
 * 只拦 `/api/**`，其余请求（Vite 模块、HMR WebSocket、静态资源）**完全不拦截**。
 *
 * ⚠️ 不要图省事拦「全部请求」再 `route.continue()`：那样会连 Vite 的 HMR WebSocket
 * 一起拦下，`continue()` 与浏览器关闭会互相等待 —— 表现为用例「无输出地卡死」，
 * 连超时都不触发，也拿不到失败原因。用 URL 谓词精确限定即可避开。
 */
const API_ONLY = (url: URL) => url.pathname.startsWith('/api/')

/**
 * 外壳渲染所需的最小 fixture。
 * 字段取自 `VisualWorkspacePage.visualFixture`（那里已被视觉回归验证过可用），
 * 这里只保留外壳与首屏真正会请求的那几个接口。
 */
function shellFixture(pathname: string): unknown {
  if (pathname === '/api/market/prices') {
    return {
      gold_etf: {
        name: '黄金ETF华夏', price: 8.95, change: -0.05, change_pct: -0.57,
        prev_close: 9.0, low: 8.9, high: 9.05,
      },
      london_gold: {
        name: '伦敦金（现货黄金）', price: 4318.82, change: 1.29, change_pct: 0.03,
        prev_close: 4317.53, low: 4298.12, high: 4332.44,
      },
    }
  }
  if (pathname === '/api/market/overview') return []
  if (pathname === '/api/market/instruments') return []
  if (pathname === '/api/market/kline') return []
  if (pathname === '/api/market/preferences') {
    return { persisted: false, watchlist: [], hiddenDefaultKeys: [] }
  }
  if (pathname === '/api/market/session') return { is_open: true }
  // 写请求前，前端会先 GET /api/auth/csrf 取 `data.token`；拿不到就抛
  // 「无法获取 CSRF token」并且**根本不发出写请求**，用例会表现为没有任何请求被抓到。
  if (pathname === '/api/auth/csrf') return { token: 'e2e-csrf-token' }
  if (pathname.startsWith('/api/notifications')) return { unread: 0, items: [], total: 0 }
  if (pathname === '/api/ai/status') return { available: false }
  if (pathname.includes('/api/news')) return []
  if (pathname === '/api/sim/account') {
    return {
      cash: 100000, netEquity: 100000, totalAssets: 100000,
      marketValue: 0, totalReturnPct: 0, positions: {},
    }
  }
  if (pathname === '/api/sim/orders/open') return []
  return null
}

/** 桩任务形状。`status` 只会是 ACTIVE / PAUSED（DELETED 不进列表）。 */
type StubTask = {
  id: number
  name: string
  task_type: string
  status: 'ACTIVE' | 'PAUSED'
  cron_expr: string
  next_run_at: string | null
  last_run_status: string | null
  last_error: string | null
  params: Record<string, unknown>
}

const TASK_MARKET_SCAN: StubTask = {
  id: 101,
  name: '工作日行情扫描',
  task_type: 'MARKET_SCAN',
  status: 'ACTIVE',
  cron_expr: '0 */30 * * * *',
  next_run_at: '2026-09-21T09:30:00+08:00',
  last_run_status: 'SUCCESS',
  last_error: null,
  params: { markets: ['gold_etf', 'london_gold'], changeThresholdPct: 1.5 },
}

const TASK_RISK_CHECK: StubTask = {
  id: 102,
  name: '夜间风险检查',
  task_type: 'RISK_CHECK',
  status: 'PAUSED',
  cron_expr: '0 0 9 * * MON-FRI',
  next_run_at: null,
  last_run_status: 'SKIPPED',
  last_error: null,
  params: { warnBelowPct: 25 },
}

/**
 * 创建于 `analyze` 参数出现**之前**的日报任务：params 里没有这个键。
 * 执行器侧 `Boolean.TRUE.equals(analyze)` 意味着「缺失即不跑 AI」，
 * 所以编辑器必须显示为**未勾选**，否则打开就保存会把 AI 分析静默打开。
 */
const TASK_DAILY_DIGEST_LEGACY: StubTask = {
  id: 103,
  name: '每日资讯日报（旧）',
  task_type: 'DAILY_DIGEST',
  status: 'ACTIVE',
  cron_expr: '0 0 8 * * *',
  next_run_at: '2026-09-22T08:00:00+08:00',
  last_run_status: 'SUCCESS',
  last_error: null,
  params: { limit: 12, headlineCount: 3 },
}

/** 显式开启过 AI 分析的日报任务，用于确认回填不是「一律不勾选」。 */
const TASK_DAILY_DIGEST_ANALYZE_ON: StubTask = {
  id: 104,
  name: '每日资讯日报（含 AI 分析）',
  task_type: 'DAILY_DIGEST',
  status: 'ACTIVE',
  cron_expr: '0 0 8 * * *',
  next_run_at: '2026-09-22T08:00:00+08:00',
  last_run_status: 'SUCCESS',
  last_error: null,
  params: { limit: 10, headlineCount: 3, analyze: true },
}

/** 4 个执行器全部就绪（DAILY_DIGEST 于 2026-09-20 补齐后的现状）。 */
const ALL_TYPES = [
  { type: 'MARKET_SCAN', supported: true },
  { type: 'RISK_CHECK', supported: true },
  { type: 'BACKTEST', supported: true },
  { type: 'DAILY_DIGEST', supported: true },
]

/** 用例期间发出的**写**请求（读请求不记），用于断言提交的 body 与动作端点。 */
type Captured = { method: string; url: string; body: unknown }

/**
 * 安全读取请求体。
 *
 * ⚠️ 不能直接用 `request.postDataJSON()`：body 非 JSON（如表单、空 body）时它会**抛异常**，
 * 而抛在 route handler 里会导致该请求**永远得不到 fulfill** → 页面一直等响应，
 * 表现为用例「无输出地卡死」而不是干脆失败。宁可退化成原始字符串。
 */
function safePostData(request: { postDataJSON: () => unknown; postData: () => string | null }): unknown {
  try {
    return request.postDataJSON()
  } catch {
    return request.postData()
  }
}

type StubOptions = {
  tasks?: StubTask[]
  runs?: unknown[]
  types?: unknown[]
  /** 让列表接口返回业务失败码，用于验证错误态。 */
  failList?: boolean
  /** 让所有写接口返回业务失败码，用于验证保存失败路径。 */
  failSave?: string
  /**
   * 传入一个计数器对象，桩会累加「列表接口」的调用次数。
   * 用于验证「刷新」确实重新拉取；比另写一套路由桩更省且不会与主桩逻辑漂移。
   */
  listCalls?: { count: number }
}

async function stubScheduledTasksApi(page: Page, options: StubOptions = {}): Promise<Captured[]> {
  const captured: Captured[] = []
  const tasks = options.tasks ?? []
  const runs = options.runs ?? []
  const types = options.types ?? ALL_TYPES

  await page.route(API_ONLY, async (route: Route) => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    const method = request.method()

    if (method !== 'GET') {
      captured.push({ method, url: path, body: safePostData(request) })
    }

    const json = (payload: unknown) =>
      route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(payload) })

    // ── 定时任务接口（本用例集的被测对象）────────────────────────────
    if (path.startsWith('/api/scheduled-tasks')) {
      if (method === 'GET' && /\/api\/scheduled-tasks$/.test(path)) {
        if (options.listCalls) options.listCalls.count += 1
        if (options.failList) return json({ code: 500, message: '定时任务加载失败' })
        return json({ code: 200, data: { items: tasks, total: tasks.length } })
      }
      if (method === 'GET' && path.endsWith('/types')) {
        return json({ code: 200, data: types })
      }
      if (method === 'GET' && path.endsWith('/runs')) {
        return json({ code: 200, data: { items: runs, total: runs.length } })
      }
      if (method === 'GET' && /\/api\/scheduled-tasks\/\d+$/.test(path)) {
        const id = path.split('/').pop()
        return json({ code: 200, data: tasks.find(t => String(t.id) === id) ?? null })
      }
      if (options.failSave) {
        return json({ code: 500, message: options.failSave })
      }
      if (method === 'POST' && /\/api\/scheduled-tasks$/.test(path)) {
        return json({ code: 200, data: { ...TASK_MARKET_SCAN, id: 999 } })
      }
      return json({ code: 200, data: {} })
    }

    // ── 其它接口：本地应答，保证外壳可渲染、且不触碰任何后端 ──────────
    return json({ code: 200, data: shellFixture(path) })
  })

  return captured
}

test.describe('定时任务 · 核心流程', () => {
  test.setTimeout(60_000)

  // ── 导航与渲染 ────────────────────────────────────────────────────

  test('工作台「系统」菜单可进入定时任务页', async ({ scheduledTasksPage, page }) => {
    await stubScheduledTasksApi(page, { tasks: [TASK_MARKET_SCAN] })
    await scheduledTasksPage.goto(PREVIEW_PARAMS)
    await scheduledTasksPage.openFromWorkspace()

    await scheduledTasksPage.expectVisible(
      scheduledTasksPage.heading,
      '经「系统 → 定时任务」应能进入定时任务页',
    )
  })

  test('汇总卡按状态统计任务数', async ({ scheduledTasksPage, page }) => {
    await stubScheduledTasksApi(page, { tasks: [TASK_MARKET_SCAN, TASK_RISK_CHECK] })
    await scheduledTasksPage.goto(PREVIEW_PARAMS)
    await scheduledTasksPage.openFromWorkspace()

    await scheduledTasksPage.expectText(scheduledTasksPage.totalCount, '2', '任务总数应为 2')
    await scheduledTasksPage.expectText(scheduledTasksPage.activeCount, '1', '运行中应为 1')
    await scheduledTasksPage.expectText(scheduledTasksPage.pausedCount, '1', '已暂停应为 1')
    await scheduledTasksPage.expectText(
      scheduledTasksPage.supportedTypeCount,
      '4',
      '4 个执行器全部就绪时，可用类型应为 4',
    )
  })

  test('列表按任务渲染类型、Cron 与最近结果', async ({ scheduledTasksPage, page }) => {
    await stubScheduledTasksApi(page, { tasks: [TASK_MARKET_SCAN, TASK_RISK_CHECK] })
    await scheduledTasksPage.goto(PREVIEW_PARAMS)
    await scheduledTasksPage.openFromWorkspace()

    const scanRow = scheduledTasksPage.rowByName('工作日行情扫描')
    await scheduledTasksPage.expectVisible(scanRow, '应渲染「工作日行情扫描」这一行')
    await expect(scanRow, 'MARKET_SCAN 应显示为「行情扫描」').toContainText('行情扫描')
    await expect(scanRow, '应展示该任务的 Cron 表达式').toContainText('0 */30 * * * *')
    await expect(scanRow, '应展示最近执行结果').toContainText('成功')

    const riskRow = scheduledTasksPage.rowByName('夜间风险检查')
    await expect(riskRow, 'RISK_CHECK 应显示为「风险检测」').toContainText('风险检测')
    await expect(riskRow, 'PAUSED 应显示为「已暂停」').toContainText('已暂停')
  })

  test('无任务时展示空态而不是空表格', async ({ scheduledTasksPage, page }) => {
    await stubScheduledTasksApi(page, { tasks: [] })
    await scheduledTasksPage.goto(PREVIEW_PARAMS)
    await scheduledTasksPage.openFromWorkspace()

    await scheduledTasksPage.expectVisible(
      scheduledTasksPage.emptyState,
      '没有任务时应给出空态文案，避免用户面对一块空白',
    )
    await expect(scheduledTasksPage.table, '空态下不应渲染任务表格').toBeHidden()
  })

  test('加载失败时给出可见错误提示', async ({ scheduledTasksPage, page }) => {
    await stubScheduledTasksApi(page, { failList: true })
    await scheduledTasksPage.goto(PREVIEW_PARAMS)
    await scheduledTasksPage.openFromWorkspace()

    await scheduledTasksPage.expectVisible(
      scheduledTasksPage.errorBanner,
      '后端返回非 200 时应把失败原因显示给用户，「静默失败」是最难排查的一类缺陷',
    )
  })

  // ── 新建 ──────────────────────────────────────────────────────────

  test('「新建任务」可打开编辑器且名称未填时禁止保存', async ({ scheduledTasksPage, page }) => {
    await stubScheduledTasksApi(page, { tasks: [] })
    await scheduledTasksPage.goto(PREVIEW_PARAMS)
    await scheduledTasksPage.openFromWorkspace()

    await scheduledTasksPage.openCreateEditor()
    await scheduledTasksPage.expectVisible(
      scheduledTasksPage.nameInput,
      '编辑器应包含任务名称输入框',
    )
    await expect(
      scheduledTasksPage.saveButton,
      '名称为空时保存按钮应禁用（否则会提交一个无名任务）',
    ).toBeDisabled()

    await scheduledTasksPage.fillField(
      scheduledTasksPage.nameInput,
      '工作日行情扫描',
      '应能填写任务名称',
    )
    await expect(scheduledTasksPage.saveButton, '填了名称后保存按钮应可用').toBeEnabled()
  })

  test('任务类型下拉列出全部 4 个可用类型', async ({ scheduledTasksPage, page }) => {
    await stubScheduledTasksApi(page, { tasks: [] })
    await scheduledTasksPage.goto(PREVIEW_PARAMS)
    await scheduledTasksPage.openFromWorkspace()
    await scheduledTasksPage.openCreateEditor()

    const labels = (await scheduledTasksPage.typeSelect.locator('option').allTextContents()).join('|')
    for (const expected of ['行情扫描', '风险检测', '策略回测', '每日资讯日报']) {
      expect(labels, `类型下拉应包含「${expected}」`).toContain(expected)
    }
    expect(
      labels,
      '四个执行器都已就绪，下拉里不应出现「执行器未就绪」',
    ).not.toContain('执行器未就绪')
  })

  test('Cron 预置按钮应写入对应表达式', async ({ scheduledTasksPage, page }) => {
    await stubScheduledTasksApi(page, { tasks: [] })
    await scheduledTasksPage.goto(PREVIEW_PARAMS)
    await scheduledTasksPage.openFromWorkspace()
    await scheduledTasksPage.openCreateEditor()

    await scheduledTasksPage.applyCronPreset('工作日09:00')
    await expect(
      scheduledTasksPage.cronInput,
      '点「工作日09:00」应把 Cron 写成 MON-FRI 的 9 点',
    ).toHaveValue('0 0 9 * * MON-FRI')

    await scheduledTasksPage.applyCronPreset('每30分钟')
    await expect(
      scheduledTasksPage.cronInput,
      '点「每30分钟」应把 Cron 写回 */30',
    ).toHaveValue('0 */30 * * * *')
  })

  test('类型切换应展示对应的参数区', async ({ scheduledTasksPage, page }) => {
    await stubScheduledTasksApi(page, { tasks: [] })
    await scheduledTasksPage.goto(PREVIEW_PARAMS)
    await scheduledTasksPage.openFromWorkspace()
    await scheduledTasksPage.openCreateEditor()

    await scheduledTasksPage.selectTaskType('MARKET_SCAN')
    await expect(scheduledTasksPage.typeParams, '行情扫描应展示「行情扫描参数」').toContainText(
      '行情扫描参数',
    )

    await scheduledTasksPage.selectTaskType('RISK_CHECK')
    await expect(scheduledTasksPage.typeParams, '风险检测应展示「风险检测参数」').toContainText(
      '风险检测参数',
    )

    await scheduledTasksPage.selectTaskType('BACKTEST')
    await expect(scheduledTasksPage.typeParams, '策略回测应展示「回测参数」').toContainText('回测参数')

    await scheduledTasksPage.selectTaskType('DAILY_DIGEST')
    await expect(scheduledTasksPage.typeParams, '资讯日报应展示「资讯日报参数」').toContainText(
      '资讯日报参数',
    )
  })

  test('DAILY_DIGEST 参数区应提供条数、标题数与 AI 分析开关', async ({
    scheduledTasksPage,
    page,
  }) => {
    await stubScheduledTasksApi(page, { tasks: [] })
    await scheduledTasksPage.goto(PREVIEW_PARAMS)
    await scheduledTasksPage.openFromWorkspace()
    await scheduledTasksPage.openCreateEditor()

    await scheduledTasksPage.selectTaskType('DAILY_DIGEST')

    await scheduledTasksPage.expectVisible(
      scheduledTasksPage.digestLimitInput,
      '资讯日报应能配置「保留资讯条数」',
    )
    await scheduledTasksPage.expectVisible(
      scheduledTasksPage.digestHeadlineInput,
      '资讯日报应能配置「摘要标题数」',
    )
    await scheduledTasksPage.expectVisible(
      scheduledTasksPage.digestAnalyzeCheckbox,
      '资讯日报应提供「AI 分析」开关',
    )

    // 后端把 limit/headlineCount 夹在 10/3 且 analyze 默认开启，前端初值应与之一致，
    // 否则用户不碰表单直接保存会提交出与后端预期不同的参数。
    await expect(
      scheduledTasksPage.digestLimitInput,
      '「保留资讯条数」初值应为 10，与执行器默认上限对齐',
    ).toHaveValue('10')
    await expect(
      scheduledTasksPage.digestHeadlineInput,
      '「摘要标题数」初值应为 3，与执行器默认一致',
    ).toHaveValue('3')
    await expect(
      scheduledTasksPage.digestAnalyzeCheckbox,
      '「AI 分析」默认应开启（新建表单默认开启并提交 analyze:true）',
    ).toBeChecked()
  })

  test('保存 DAILY_DIGEST 应提交 limit / headlineCount / analyze', async ({
    scheduledTasksPage,
    page,
  }) => {
    const captured = await stubScheduledTasksApi(page, { tasks: [] })
    await scheduledTasksPage.goto(PREVIEW_PARAMS)
    await scheduledTasksPage.openFromWorkspace()
    await scheduledTasksPage.openCreateEditor()

    await scheduledTasksPage.fillField(
      scheduledTasksPage.nameInput,
      '每日资讯日报',
      '应能填写名称',
    )
    await scheduledTasksPage.selectTaskType('DAILY_DIGEST')
    await scheduledTasksPage.fillField(scheduledTasksPage.digestLimitInput, '5', '应能填写条数')
    await scheduledTasksPage.fillField(scheduledTasksPage.digestHeadlineInput, '2', '应能填写标题数')
    await scheduledTasksPage.digestAnalyzeCheckbox.uncheck()

    await scheduledTasksPage.clickAndWait(scheduledTasksPage.saveButton, '应能提交表单')

    await expect
      .poll(() => captured.filter(item => item.method === 'POST').length, {
        message: '保存应发出一次创建请求',
      })
      .toBe(1)

    const body = captured.find(item => item.method === 'POST')!.body as Record<string, unknown>
    expect(body.taskType, '请求体应带 DAILY_DIGEST 类型').toBe('DAILY_DIGEST')
    expect(
      body.params,
      'DAILY_DIGEST 应提交 limit / headlineCount / analyze，且字段名与执行器读取的一致',
    ).toEqual({ limit: 5, headlineCount: 2, analyze: false })
  })

  test('保存应提交名称、类型、Cron 与该类型的参数', async ({ scheduledTasksPage, page }) => {
    const captured = await stubScheduledTasksApi(page, { tasks: [] })
    await scheduledTasksPage.goto(PREVIEW_PARAMS)
    await scheduledTasksPage.openFromWorkspace()
    await scheduledTasksPage.openCreateEditor()

    await scheduledTasksPage.fillField(scheduledTasksPage.nameInput, '工作日风险检查', '应能填写名称')
    await scheduledTasksPage.selectTaskType('RISK_CHECK')
    await scheduledTasksPage.applyCronPreset('工作日15:00')

    await scheduledTasksPage.clickAndWait(scheduledTasksPage.saveButton, '应能提交表单')

    await expect
      .poll(() => captured.filter(item => item.method === 'POST').length, {
        message: '保存应发出一次创建请求',
      })
      .toBe(1)

    const body = captured.find(item => item.method === 'POST')!.body as Record<string, unknown>
    expect(body.name, '请求体应带任务名称').toBe('工作日风险检查')
    expect(body.taskType, '请求体应带任务类型').toBe('RISK_CHECK')
    expect(body.cronExpr, '请求体应带 Cron').toBe('0 0 15 * * MON-FRI')
    expect(body.timezone, '请求体应固定为 Asia/Shanghai').toBe('Asia/Shanghai')
    expect(
      body.params,
      'RISK_CHECK 只该提交 warnBelowPct，不该混入别的类型的参数',
    ).toEqual({ warnBelowPct: 25 })
  })

  test('保存失败时保留编辑器并提示原因', async ({ scheduledTasksPage, page }) => {
    await stubScheduledTasksApi(page, { tasks: [], failSave: '任务名称重复' })
    await scheduledTasksPage.goto(PREVIEW_PARAMS)
    await scheduledTasksPage.openFromWorkspace()
    await scheduledTasksPage.openCreateEditor()

    await scheduledTasksPage.fillField(scheduledTasksPage.nameInput, '重复的任务', '应能填写名称')
    await scheduledTasksPage.clickAndWait(scheduledTasksPage.saveButton, '应能提交表单')

    await scheduledTasksPage.expectVisible(
      scheduledTasksPage.errorBanner,
      '保存失败应把后端原因显示出来',
    )
    await scheduledTasksPage.expectVisible(
      scheduledTasksPage.editor,
      '保存失败不应关闭编辑器，否则用户填的内容会丢',
    )
  })

  test('编辑器可用「取消」关闭且不发出任何写请求', async ({ scheduledTasksPage, page }) => {
    const captured = await stubScheduledTasksApi(page, { tasks: [TASK_MARKET_SCAN] })
    await scheduledTasksPage.goto(PREVIEW_PARAMS)
    await scheduledTasksPage.openFromWorkspace()
    await scheduledTasksPage.openCreateEditor()

    await scheduledTasksPage.fillField(scheduledTasksPage.nameInput, '不该被提交', '应能填写名称')
    await scheduledTasksPage.clickAndWait(scheduledTasksPage.cancelButton, '应能点击「取消」')

    await expect(scheduledTasksPage.editor, '点取消应关闭编辑器').toBeHidden()
    expect(captured, '取消不应发出任何写请求').toHaveLength(0)
  })

  // ── 状态机：暂停 / 恢复 ────────────────────────────────────────────

  test('ACTIVE 任务显示「暂停」、PAUSED 任务显示「恢复」', async ({
    scheduledTasksPage,
    page,
  }) => {
    await stubScheduledTasksApi(page, { tasks: [TASK_MARKET_SCAN, TASK_RISK_CHECK] })
    await scheduledTasksPage.goto(PREVIEW_PARAMS)
    await scheduledTasksPage.openFromWorkspace()

    await expect(
      scheduledTasksPage.rowAction('工作日行情扫描', '暂停'),
      '运行中的任务应提供「暂停」',
    ).toBeVisible()
    await expect(
      scheduledTasksPage.rowAction('夜间风险检查', '恢复'),
      '已暂停的任务应提供「恢复」',
    ).toBeVisible()
  })

  test('点击「暂停」应发出 pause 请求', async ({ scheduledTasksPage, page }) => {
    const captured = await stubScheduledTasksApi(page, { tasks: [TASK_MARKET_SCAN] })
    await scheduledTasksPage.goto(PREVIEW_PARAMS)
    await scheduledTasksPage.openFromWorkspace()

    await scheduledTasksPage.clickAndWait(
      scheduledTasksPage.rowAction('工作日行情扫描', '暂停'),
      '应能点击「暂停」',
    )

    await expect
      .poll(
        () => captured.filter(item => /\/api\/scheduled-tasks\/101\/pause$/.test(item.url)).length,
        { message: '点击暂停应请求 /api/scheduled-tasks/{id}/pause' },
      )
      .toBe(1)
  })

  test('点击「恢复」应发出 resume 请求', async ({ scheduledTasksPage, page }) => {
    const captured = await stubScheduledTasksApi(page, { tasks: [TASK_RISK_CHECK] })
    await scheduledTasksPage.goto(PREVIEW_PARAMS)
    await scheduledTasksPage.openFromWorkspace()

    await scheduledTasksPage.clickAndWait(
      scheduledTasksPage.rowAction('夜间风险检查', '恢复'),
      '应能点击「恢复」',
    )

    await expect
      .poll(
        () => captured.filter(item => /\/api\/scheduled-tasks\/102\/resume$/.test(item.url)).length,
        { message: '点击恢复应请求 /api/scheduled-tasks/{id}/resume' },
      )
      .toBe(1)
  })

  test('点击「立即执行」应发出 run 请求', async ({ scheduledTasksPage, page }) => {
    const captured = await stubScheduledTasksApi(page, { tasks: [TASK_MARKET_SCAN] })
    await scheduledTasksPage.goto(PREVIEW_PARAMS)
    await scheduledTasksPage.openFromWorkspace()

    await scheduledTasksPage.clickAndWait(
      scheduledTasksPage.rowAction('工作日行情扫描', '立即执行'),
      '应能点击「立即执行」',
    )

    await expect
      .poll(
        () => captured.filter(item => /\/api\/scheduled-tasks\/101\/run$/.test(item.url)).length,
        { message: '点击立即执行应请求 /api/scheduled-tasks/{id}/run' },
      )
      .toBe(1)
  })

  test('点击「删除」应发出 DELETE 请求', async ({ scheduledTasksPage, page }) => {
    const captured = await stubScheduledTasksApi(page, { tasks: [TASK_MARKET_SCAN] })
    await scheduledTasksPage.goto(PREVIEW_PARAMS)
    await scheduledTasksPage.openFromWorkspace()

    await scheduledTasksPage.clickAndWait(
      scheduledTasksPage.rowAction('工作日行情扫描', '删除'),
      '应能点击「删除」',
    )

    await expect
      .poll(() => captured.filter(item => item.method === 'DELETE').length, {
        message: '点击删除应发出 DELETE 请求',
      })
      .toBe(1)
  })

  // ── 执行历史 ──────────────────────────────────────────────────────

  test('「历史」应拉取并展示该任务的执行记录', async ({ scheduledTasksPage, page }) => {
    await stubScheduledTasksApi(page, {
      tasks: [TASK_MARKET_SCAN],
      runs: [
        {
          id: 1,
          status: 'SUCCESS',
          scheduled_at: '2026-09-20T09:30:00+08:00',
          started_at: '2026-09-20T09:30:01+08:00',
          duration_ms: 842,
          result_summary: '行情扫描完成：2 个市场均未触及阈值',
          error_message: null,
        },
        {
          id: 2,
          status: 'FAILED',
          scheduled_at: '2026-09-20T09:00:00+08:00',
          started_at: '2026-09-20T09:00:01+08:00',
          duration_ms: 120,
          result_summary: null,
          error_message: '行情源不可用',
        },
      ],
    })
    await scheduledTasksPage.goto(PREVIEW_PARAMS)
    await scheduledTasksPage.openFromWorkspace()

    await scheduledTasksPage.clickAndWait(
      scheduledTasksPage.rowAction('工作日行情扫描', '历史'),
      '应能打开执行历史',
    )

    await scheduledTasksPage.expectVisible(scheduledTasksPage.historyPanel, '应渲染执行历史面板')
    await expect(scheduledTasksPage.historyItems, '应渲染两条执行记录').toHaveCount(2)
    const panel = scheduledTasksPage.historyPanel
    await expect(panel, '应展示成功摘要').toContainText('2 个市场均未触及阈值')
    await expect(
      panel,
      '失败的记录没有摘要时应回退显示错误信息，而不是留空',
    ).toContainText('行情源不可用')
  })

  test('无执行记录时历史面板给出空态', async ({ scheduledTasksPage, page }) => {
    await stubScheduledTasksApi(page, { tasks: [TASK_MARKET_SCAN], runs: [] })
    await scheduledTasksPage.goto(PREVIEW_PARAMS)
    await scheduledTasksPage.openFromWorkspace()

    await scheduledTasksPage.clickAndWait(
      scheduledTasksPage.rowAction('工作日行情扫描', '历史'),
      '应能打开执行历史',
    )

    await scheduledTasksPage.expectVisible(
      scheduledTasksPage.historyEmptyState,
      '没有执行记录时应给出空态',
    )
  })

  // ── 刷新 ──────────────────────────────────────────────────────────

  test('「刷新」应重新拉取任务列表', async ({ scheduledTasksPage, page }) => {
    const listCalls = { count: 0 }
    await stubScheduledTasksApi(page, { tasks: [TASK_MARKET_SCAN], listCalls })

    await scheduledTasksPage.goto(PREVIEW_PARAMS)
    await scheduledTasksPage.openFromWorkspace()

    // 任务页是异步组件，首次列表请求可能晚于导航完成 —— 必须先等它落地再记基线，
    // 否则 before 恒为 0，会误判成「进页面根本没请求」。
    await expect
      .poll(() => listCalls.count, { message: '进入定时任务页后应拉取任务列表' })
      .toBeGreaterThan(0)
    const before = listCalls.count

    await scheduledTasksPage.clickAndWait(scheduledTasksPage.refreshButton, '应能点击「刷新」')
    await expect
      .poll(() => listCalls.count, { message: '点击刷新应重新请求任务列表' })
      .toBeGreaterThan(before)
  })

  // ── 编辑回填口径 ────────────────────────────────────────────────────

  test('编辑旧日报任务时，「AI 分析」应反映真实配置而非默认勾选', async ({ scheduledTasksPage, page }) => {
    await stubScheduledTasksApi(page, { tasks: [TASK_DAILY_DIGEST_LEGACY] })
    await scheduledTasksPage.goto(PREVIEW_PARAMS)
    await scheduledTasksPage.openFromWorkspace()

    await scheduledTasksPage.clickAndWait(
      scheduledTasksPage.rowAction(TASK_DAILY_DIGEST_LEGACY.name, '编辑'),
      '应能打开该任务的编辑弹窗',
    )
    await scheduledTasksPage.expectVisible(scheduledTasksPage.editor, '编辑弹窗应打开')

    // 这条钉住一个真实缺陷：回填若写成 `params.analyze !== false`，旧任务会显示成"已勾选"，
    // 用户没碰它就保存 → 静默把 AI 分析从关改成开并产生用量。必须与执行器口径一致。
    await expect(
      scheduledTasksPage.digestAnalyzeCheckbox,
      'params 里没有 analyze 时不应勾选（执行器同样不会跑 AI）',
    ).not.toBeChecked()
  })

  test('显式开启过 AI 分析的日报任务，编辑时应保持勾选', async ({ scheduledTasksPage, page }) => {
    await stubScheduledTasksApi(page, { tasks: [TASK_DAILY_DIGEST_ANALYZE_ON] })
    await scheduledTasksPage.goto(PREVIEW_PARAMS)
    await scheduledTasksPage.openFromWorkspace()

    await scheduledTasksPage.clickAndWait(
      scheduledTasksPage.rowAction(TASK_DAILY_DIGEST_ANALYZE_ON.name, '编辑'),
      '应能打开该任务的编辑弹窗',
    )
    await scheduledTasksPage.expectVisible(scheduledTasksPage.editor, '编辑弹窗应打开')

    // 反向验证，避免把回填修成「一律不勾选」——那会丢掉用户显式开启的配置。
    await expect(
      scheduledTasksPage.digestAnalyzeCheckbox,
      'analyze 显式为 true 的任务应保持勾选',
    ).toBeChecked()
  })
})
