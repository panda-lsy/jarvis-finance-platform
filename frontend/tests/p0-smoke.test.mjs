import test from 'node:test'
import assert from 'node:assert/strict'
import { readdir, readFile } from 'node:fs/promises'
import { extname, join, relative } from 'node:path'
import { fileURLToPath } from 'node:url'

const frontendRoot = fileURLToPath(new URL('..', import.meta.url))

globalThis.window = { location: { hostname: 'localhost' } }

class FakeEventSource {
  static instances = []

  constructor(url, options) {
    this.url = url
    this.options = options
    this.listeners = new Map()
    this.closed = false
    this.onerror = null
    FakeEventSource.instances.push(this)
  }

  addEventListener(name, callback) {
    this.listeners.set(name, callback)
  }

  emit(name, data) {
    this.listeners.get(name)?.({ data })
  }

  close() {
    this.closed = true
  }
}

globalThis.EventSource = FakeEventSource

const { API_BASE, api } = await import('../src/api/client.js')
const { useAuthSession } = await import('../src/composables/useAuthSession.js')
const {
  hasMarketPreferences,
  marketPreferencesKey,
  normalizeMarketPreferences,
  readMarketPreferences,
  writeMarketPreferences,
} = await import('../src/utils/marketPreferences.js')

test('local development keeps browser on the Java/Vite same-origin API', () => {
  assert.equal(API_BASE, '')
})

test('market price stream parses JSON events and closes cleanly', () => {
  let received = null
  let errors = 0
  const close = api.marketPriceStream(
    payload => { received = payload },
    () => { errors += 1 },
  )

  const source = FakeEventSource.instances.at(-1)
  assert.ok(source)
  assert.equal(source.url, '/api/market/prices/stream')
  assert.deepEqual(source.options, { withCredentials: true })

  const payload = {
    market: { gold_etf: { price: 7.88 } },
    jd: { zheshang: { price: 812.3 } },
    server_time: '2026-09-08T10:00:00Z',
  }
  source.emit('prices', JSON.stringify(payload))
  assert.deepEqual(received, payload)

  source.onerror?.(new Error('network'))
  assert.equal(errors, 1)

  close()
  assert.equal(source.closed, true)
})

test('authenticated writes refresh CSRF after a 419 and retry once', async () => {
  const calls = []
  let preferenceWrites = 0
  const previousFetch = globalThis.fetch
  globalThis.fetch = async (url, options = {}) => {
    calls.push({ url: String(url), method: options.method || 'GET', token: options.headers?.['X-XSRF-TOKEN'] || '' })
    if (String(url).endsWith('/api/auth/csrf')) {
      return { ok: true, status: 200, json: async () => ({ code: 200, data: { token: `csrf-${calls.length}` } }) }
    }
    if (String(url).endsWith('/api/market/preferences')) {
      preferenceWrites += 1
      if (preferenceWrites === 2) {
        return { ok: false, status: 419, json: async () => ({ code: 419, message: 'CSRF token 已失效，请重试' }) }
      }
      return { ok: true, status: 200, json: async () => ({ code: 200, data: { persisted: true } }) }
    }
    throw new Error(`unexpected request: ${url}`)
  }

  try {
    const body = { watchlist: [], hiddenDefaultKeys: [] }
    await api.saveMarketPreferences(body)
    await api.saveMarketPreferences(body)
  } finally {
    globalThis.fetch = previousFetch
  }

  assert.deepEqual(calls.map(call => call.method), ['GET', 'PUT', 'PUT', 'GET', 'PUT'])
  assert.equal(calls[1].token, 'csrf-1')
  assert.equal(calls[2].token, 'csrf-1')
  assert.equal(calls[3].url, '/api/auth/csrf')
  assert.equal(calls[4].token, 'csrf-4')
})

test('successful login rotates the cached CSRF token before the next write', async () => {
  const calls = []
  const previousFetch = globalThis.fetch
  globalThis.fetch = async (url, options = {}) => {
    const entry = {
      url: String(url),
      method: options.method || 'GET',
      token: options.headers?.['X-XSRF-TOKEN'] || '',
    }
    calls.push(entry)
    if (entry.url.endsWith('/api/auth/csrf')) {
      return { ok: true, status: 200, json: async () => ({ code: 200, data: { token: `rotated-${calls.length}` } }) }
    }
    if (entry.url.endsWith('/api/auth/login')) {
      return { ok: true, status: 200, json: async () => ({ code: 200, data: { id: 7 } }) }
    }
    if (entry.url.endsWith('/api/market/preferences')) {
      return { ok: true, status: 200, json: async () => ({ code: 200, data: { persisted: true } }) }
    }
    throw new Error(`unexpected request: ${url}`)
  }

  try {
    await api.login('test@example.invalid', 'test-password')
    await api.saveMarketPreferences({ watchlist: [], hiddenDefaultKeys: [] })
  } finally {
    globalThis.fetch = previousFetch
  }

  const loginIndex = calls.findIndex(call => call.url.endsWith('/api/auth/login'))
  const tokenIndex = calls.findIndex((call, index) => index > loginIndex && call.url.endsWith('/api/auth/csrf'))
  const writeIndex = calls.findIndex(call => call.url.endsWith('/api/market/preferences'))
  assert.ok(loginIndex >= 0 && tokenIndex > loginIndex && writeIndex > tokenIndex)
  assert.equal(calls[writeIndex].token, calls[tokenIndex].url.endsWith('/api/auth/csrf') ? `rotated-${tokenIndex + 1}` : '')
})

test('non-idempotent writes are not replayed after an auth failure', async () => {
  const calls = []
  const previousFetch = globalThis.fetch
  globalThis.fetch = async (url, options = {}) => {
    calls.push({ url: String(url), method: options.method || 'GET' })
    return { ok: false, status: 401, json: async () => ({ code: 401, message: '未登录或登录已过期' }) }
  }

  try {
    const response = await api.simOrder('BUY', 'AAPL', 1, 1, 'order-1')
    assert.equal(response.code, 401)
    assert.equal(response.httpStatus, 401)
  } finally {
    globalThis.fetch = previousFetch
  }

  assert.equal(calls.filter(call => call.url.endsWith('/api/sim/order')).length, 1)
})

test('protected 401 expires the UI session only after /me confirms it', async () => {
  const previousFetch = globalThis.fetch
  const previousDispatchEvent = globalThis.window.dispatchEvent
  const previousCustomEvent = globalThis.CustomEvent
  const events = []

  globalThis.CustomEvent = class {
    constructor(type) { this.type = type }
  }
  globalThis.window.dispatchEvent = event => {
    events.push(event.type)
    return true
  }
  globalThis.fetch = async (url) => {
    if (String(url).endsWith('/api/sim/account')) {
      return { ok: false, status: 401, json: async () => ({ code: 401, message: 'expired' }) }
    }
    if (String(url).endsWith('/api/auth/me')) {
      return { ok: false, status: 401, json: async () => ({ code: 401, message: 'expired' }) }
    }
    throw new Error(`unexpected request: ${url}`)
  }

  try {
    const response = await api.simAccount()
    assert.equal(response.httpStatus, 401)
    assert.deepEqual(events, ['jarvis:auth-expired'])
  } finally {
    globalThis.fetch = previousFetch
    globalThis.window.dispatchEvent = previousDispatchEvent
    globalThis.CustomEvent = previousCustomEvent
  }
})

test('SSE refreshes CSRF once before consuming a rejected response', async () => {
  const calls = []
  let streamReads = 0
  let cancelled = false
  const previousFetch = globalThis.fetch
  globalThis.fetch = async (url, options = {}) => {
    const entry = { url: String(url), method: options.method || 'GET', token: options.headers?.['X-XSRF-TOKEN'] || '' }
    calls.push(entry)
    if (entry.url.endsWith('/api/auth/csrf')) {
      return { ok: true, status: 200, json: async () => ({ code: 200, data: { token: 'csrf-sse' } }) }
    }
    if (calls.filter(call => call.url.endsWith('/api/ai/chat/stream')).length === 1) {
      return { ok: false, status: 419, body: { cancel: async () => { cancelled = true } } }
    }
    return {
      ok: true,
      status: 200,
      body: {
        getReader: () => ({
          read: async () => streamReads++ === 0
            ? { value: new TextEncoder().encode('event: answer\ndata: {"ok":true}\n\n'), done: false }
            : { value: undefined, done: true },
        }),
      },
    }
  }

  try {
    const events = []
    await api.aiChatStream([{ role: 'user', content: 'hi' }], event => events.push(event))
    assert.deepEqual(events, [{ event: 'answer', data: { ok: true } }])
  } finally {
    globalThis.fetch = previousFetch
  }

  assert.equal(cancelled, true)
  assert.equal(calls.filter(call => call.url.endsWith('/api/ai/chat/stream')).length, 2)
  assert.equal(calls.at(-1).token, 'csrf-sse')
})

test('authorization 403 is not replayed as a CSRF failure', async () => {
  const calls = []
  const previousFetch = globalThis.fetch
  globalThis.fetch = async (url, options = {}) => {
    calls.push({ url: String(url), method: options.method || 'GET' })
    if (String(url).endsWith('/api/auth/csrf')) {
      return { ok: true, status: 200, json: async () => ({ code: 200, data: { token: 'csrf-403' } }) }
    }
    return { ok: false, status: 403, json: async () => ({ code: 403, message: '无权限访问该资源' }) }
  }

  try {
    const response = await api.adminUpdateStatus(42, false, 'verified abuse')
    assert.equal(response.httpStatus, 403)
  } finally {
    globalThis.fetch = previousFetch
  }

  assert.equal(calls.filter(call => call.url.endsWith('/api/admin/users/42/status')).length, 1)
})

test('admin session revocation posts an explicit audit reason with CSRF protection', async () => {
  const calls = []
  const previousFetch = globalThis.fetch
  globalThis.fetch = async (url, options = {}) => {
    calls.push({ url: String(url), method: options.method || 'GET', headers: options.headers, body: options.body })
    if (String(url).endsWith('/api/auth/csrf')) {
      return { ok: true, status: 200, json: async () => ({ code: 200, data: { token: 'csrf-revoke' } }) }
    }
    return { ok: true, status: 200, json: async () => ({ code: 200, data: { userId: 72, sessionsRevoked: true } }) }
  }

  try {
    const response = await api.adminRevokeSessions(72, { reason: 'security review' })
    assert.equal(response.code, 200)
  } finally {
    globalThis.fetch = previousFetch
  }

  const revoke = calls.find(call => call.url.endsWith('/api/admin/users/72/sessions/revoke'))
  assert.equal(revoke.method, 'POST')
  assert.match(revoke.headers['X-XSRF-TOKEN'], /^csrf-/)
  assert.deepEqual(JSON.parse(revoke.body), { reason: 'security review' })
})

test('late session restore cannot overwrite a successful login', async () => {
  const previousMe = api.me
  let resolveMe
  api.me = () => new Promise((resolve) => { resolveMe = resolve })

  try {
    const session = useAuthSession()
    const restorePromise = session.restore()
    const loggedInUser = { id: 7, email: 'user@example.com' }
    session.acceptLogin(loggedInUser)
    resolveMe({ code: 401, data: null })
    await restorePromise
    assert.deepEqual(session.user.value, loggedInUser)
  } finally {
    api.me = previousMe
  }
})

test('session restore preserves an existing user on non-auth failures', async () => {
  const previousMe = api.me
  const session = useAuthSession()
  const loggedInUser = { id: 9, email: 'stable@example.com' }
  session.acceptLogin(loggedInUser)

  try {
    api.me = async () => ({ code: 503, httpStatus: 503, message: 'temporary outage' })
    await session.restore()
    assert.deepEqual(session.user.value, loggedInUser)
    assert.equal(session.sessionState.value, 'degraded')

    api.me = async () => { throw new Error('network down') }
    await session.restore()
    assert.deepEqual(session.user.value, loggedInUser)
    assert.equal(session.sessionState.value, 'degraded')
  } finally {
    api.me = previousMe
  }
})

test('market preferences persist per user and discard malformed entries', () => {
  let value = null
  const storage = {
    getItem: () => value,
    setItem: (_key, nextValue) => { value = nextValue },
  }
  const key = marketPreferencesKey({ id: 23 })
  assert.equal(writeMarketPreferences(storage, key, {
    watchlist: [
      { market: 'us_stock', symbol: 'AAPL', name: 'Apple', currency: 'USD', source: 'Yahoo Finance' },
      { market: 'us_stock', symbol: 'AAPL', name: 'duplicate' },
      { market: 'us_stock', symbol: 'https://bad.example', name: 'invalid' },
    ],
    hiddenDefaultKeys: ['a_share:sh600519', 'not-a-market-key'],
  }), true)
  assert.deepEqual(readMarketPreferences(storage, key), {
    watchlist: [{ market: 'us_stock', symbol: 'AAPL', name: 'Apple', currency: 'USD', source: 'Yahoo Finance' }],
    hiddenDefaultKeys: ['a_share:sh600519'],
  })
})

test('server market preferences normalize and distinguish an empty saved state', () => {
  const normalized = normalizeMarketPreferences({
    watchlist: [{ market: 'crypto', symbol: 'BTCUSDT', name: 'Bitcoin' }],
    hiddenDefaultKeys: ['crypto:BTCUSDT'],
    persisted: true,
  })
  assert.equal(hasMarketPreferences(normalized), true)
  assert.deepEqual(normalized.watchlist[0], {
    market: 'crypto', symbol: 'BTCUSDT', name: 'Bitcoin', currency: '', source: '',
  })
  assert.deepEqual(normalizeMarketPreferences({ watchlist: [], hiddenDefaultKeys: [] }), {
    watchlist: [], hiddenDefaultKeys: [],
  })
  assert.equal(hasMarketPreferences({ watchlist: [], hiddenDefaultKeys: [] }), false)
})

test('cross-market view persists through the authenticated backend and exposes edit/delete actions', async () => {
  const source = await readFile(join(frontendRoot, 'src/components/CrossMarketView.vue'), 'utf8')
  const listSource = await readFile(join(frontendRoot, 'src/components/market/InstrumentList.vue'), 'utf8')
  assert.match(source, /api\.marketPreferences\(\)/)
  assert.match(source, /api\.saveMarketPreferences\(/)
  assert.match(source, /function saveEditedInstrument\(/)
  assert.match(source, /resetSelectedData\(\)/)
  assert.match(listSource, /edit-watchlist/)
  assert.match(listSource, /修改自选标的/)
})

test('simulation view reuses multi-market instruments and enforces the trading window', async () => {
  const source = await readFile(join(frontendRoot, 'src/components/SimTradeView.vue'), 'utf8')
  const ticketSource = await readFile(join(frontendRoot, 'src/components/trading/OrderTicket.vue'), 'utf8')
  const terminalSource = await readFile(join(frontendRoot, 'src/components/trading/TradingTerminal.vue'), 'utf8')
  assert.match(source, /<InstrumentList/)
  assert.match(source, /api\.resolveMarketInstrument\(market\.value, query\)/)
  assert.match(source, /api\.saveMarketPreferences\(preferences\)/)
  assert.match(source, /const marketOpen = computed/)
  assert.match(source, /模拟盘仅允许在开市时间成交/)
  assert.match(source, /<TradingTerminal/)
  assert.match(terminalSource, /getMultiMarketChartOptions/)
  assert.match(terminalSource, /sessionOpen: \{ type: Boolean/)
  assert.match(terminalSource, /!props\.sessionOpen/)
  assert.match(ticketSource, /instruments: \{ type: Array/)
  assert.match(ticketSource, /v-for="item in instrumentOptions"/)
})

async function sourceFiles(dir) {
  const entries = await readdir(dir, { withFileTypes: true })
  const output = []
  for (const entry of entries) {
    const path = join(dir, entry.name)
    if (entry.isDirectory()) output.push(...await sourceFiles(path))
    else if (['.js', '.vue'].includes(extname(entry.name))) output.push(path)
  }
  return output
}

test('frontend source cannot bypass the Java security boundary', async () => {
  const forbidden = [
    ['direct Python port', /(?:localhost|127\.0\.0\.1):8100/i],
    ['public Python route', /["'`]\/py(?:\/|["'`])/i],
    ['internal service token header', /X-Internal-Service-Token/i],
    ['Python service secret', /PYTHON_SERVICE_TOKEN/i],
    ['AI provider secret', /(?:AI_API_KEY|DEEPSEEK_API_KEY|OLLAMA_API_KEY)/i],
  ]

  const violations = []
  for (const file of await sourceFiles(join(frontendRoot, 'src'))) {
    const text = await readFile(file, 'utf8')
    for (const [label, pattern] of forbidden) {
      if (pattern.test(text)) violations.push(`${relative(frontendRoot, file)}: ${label}`)
    }
  }
  assert.deepEqual(violations, [])
})

test('public homepage keeps auth callbacks and animation lifecycle safe', async () => {
  const appSource = await readFile(join(frontendRoot, 'src/App.vue'), 'utf8')
  const landingSource = await readFile(join(frontendRoot, 'src/pages/LandingPage.vue'), 'utf8')
  const sessionSource = await readFile(join(frontendRoot, 'src/composables/useAuthSession.js'), 'utf8')
  const loginSource = await readFile(join(frontendRoot, 'src/components/LoginView.vue'), 'utf8')

  assert.match(appSource, /params\.has\('oauth'\)/)
  assert.match(appSource, /history\.replaceState\(/)
  assert.match(appSource, /@login="showLogin"/)
  assert.match(landingSource, /src="\/landing\/index\.html"/)
  assert.match(landingSource, /textContent\?\.includes\('进入 JARVIS'\)/)
  assert.match(landingSource, /emit\('login'\)/)
  assert.doesNotMatch(landingSource, /observe\(frame\.value\)/)
  assert.doesNotMatch(landingSource, /const timers = new Set\(\)/)
  assert.match(landingSource, /onBeforeUnmount\(/)
  const landingDocument = await readFile(join(frontendRoot, 'public/landing/index.html'), 'utf8')
  assert.match(landingDocument, /const createObserver = \(callback, options\)/)
  assert.match(landingDocument, /revealHeroFallback/)
  assert.match(landingDocument, /addEventListener\('error', revealHeroFallback\)/)
  assert.match(landingDocument, /playback\.catch\(revealHeroFallback\)/)
  const viteConfig = await readFile(join(frontendRoot, 'vite.config.js'), 'utf8')
  assert.match(viteConfig, /modulePreload:\s*\{[\s\S]*polyfill:\s*false/)
  assert.match(sessionSource, /restoreRequestId/)
  assert.match(sessionSource, /requestId !== restoreRequestId/)
  assert.match(sessionSource, /sessionState\.value = 'degraded'/)
  assert.match(loginSource, /const session = await api\.me\(\)/)
  assert.match(loginSource, /登录凭证未能建立/)
})

test('sentiment page renders dispute and section cards from backend-shaped data', async () => {
  const source = await readFile(join(frontendRoot, 'src/pages/SentimentPage.vue'), 'utf8')
  // 争议焦点卡片的数据来自后端确定性切分结果，前端只负责渲染，不做语义推断
  assert.match(source, /response\.data\.disputes/)
  assert.match(source, /response\.data\.sections/)
  assert.match(source, /v-for="item in disputes" :key="item\.id"/)
  assert.match(source, /v-for="card in sectionCards" :key="card\.id"/)
  assert.match(source, /多方论据/)
  assert.match(source, /空方论据/)
  // 模型原文仍保留，切分失败时页面不会丢信息
  assert.match(source, /class="st-output" :content="result"/)
  assert.match(source, /MarkdownContent from '\.\.\/components\/common\/MarkdownContent\.vue'/)
})

test('quote page renders trend band from backend forecast and never sends closes', async () => {
  const source = await readFile(join(frontendRoot, 'src/pages/QuotePage.vue'), 'utf8')
  const clientSource = await readFile(join(frontendRoot, 'src/api/client.js'), 'utf8')
  const tabsSource = await readFile(join(frontendRoot, 'src/composables/useWorkspaceTabs.js'), 'utf8')
  const appSource = await readFile(join(frontendRoot, 'src/App.vue'), 'utf8')

  // 趋势区间数据来自后端确定性计算结果，前端只做展示
  assert.match(source, /result\.value\?\.forecast/)
  assert.match(source, /forecast\.lower/)
  assert.match(source, /forecast\.center/)
  assert.match(source, /forecast\.upper/)
  assert.match(source, /forecast\.horizon_days/)
  // 样本不足时给出友好提示，不当作硬错误
  assert.match(source, /insufficient_closes/)
  assert.match(source, /样本不足/)
  // 页面四态齐全
  assert.match(source, /state="loading"/)
  assert.match(source, /state="error"/)
  assert.match(source, /qt-empty/)

  // 前端不计算、也不传递历史收盘价：closes 一律由服务端注入
  assert.doesNotMatch(source, /closes\s*:/)
  assert.match(clientSource, /aiQuote:[\s\S]*market: options\.market/)
  assert.doesNotMatch(clientSource, /aiQuote:[\s\S]{0,400}?closes/)

  // tab 已注册并挂载
  assert.match(tabsSource, /'智能报价'/)
  assert.match(appSource, /QuotePage v-else-if="workspaceRenderRoute === '智能报价'"/)
})

test('strategy analysis explicitly opts into the bounded CSRF retry policy', async () => {
  const clientSource = await readFile(join(frontendRoot, 'src/api/client.js'), 'utf8')
  assert.match(clientSource, /aiStrategy:[\s\S]*csrfRetry:\s*true/)
})

test('trend page renders interval and basis from backend forecast and never sends closes', async () => {
  const source = await readFile(join(frontendRoot, 'src/pages/TrendPage.vue'), 'utf8')
  const clientSource = await readFile(join(frontendRoot, 'src/api/client.js'), 'utf8')
  const tabsSource = await readFile(join(frontendRoot, 'src/composables/useWorkspaceTabs.js'), 'utf8')
  const appSource = await readFile(join(frontendRoot, 'src/App.vue'), 'utf8')

  // 趋势区间与预测依据均来自后端确定性计算结果，前端只做展示
  assert.match(source, /result\.value\?\.forecast/)
  assert.match(source, /forecast\.lower/)
  assert.match(source, /forecast\.center/)
  assert.match(source, /forecast\.upper/)
  assert.match(source, /indicators\./)
  // 样本不足时给出友好提示，不当作硬错误
  assert.match(source, /insufficient_closes/)
  assert.match(source, /样本不足/)
  // 页面四态齐全
  assert.match(source, /state="loading"/)
  assert.match(source, /state="error"/)
  assert.match(source, /tr-empty/)

  // 前端不计算、也不传递历史收盘价：一律由服务端注入
  assert.doesNotMatch(source, /closes\s*:/)
  assert.match(clientSource, /aiTrend:[\s\S]*market: options\.market/)
  assert.doesNotMatch(clientSource, /aiTrend:[\s\S]{0,400}?closes/)
  // 请求路径必须是 Java 主后端端点 /api/ai/trend；
  // /api/ai/analyze/trend 是 Java 转发给 Python 的内部路径，前端直连会 500（09-14 浏览器走查发现）
  assert.match(clientSource, /aiTrend:[\s\S]{0,120}?'\/api\/ai\/trend'/)
  assert.doesNotMatch(clientSource, /aiTrend:[\s\S]{0,120}?'\/api\/ai\/analyze\/trend'/)

  // tab 已注册并挂载
  assert.match(tabsSource, /'市场趋势预测'/)
  assert.match(appSource, /TrendPage v-else-if="workspaceRenderRoute === '市场趋势预测'"/)
})
