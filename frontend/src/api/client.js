/*
 * API 客户端 (支持登录认证)
 * 浏览器只访问 Java 主后端；Java 负责业务/数据库，并在内部转发 AI 请求到 Python。
 * 生产入口: https://agent.shengxia.me/api/* → Java 8200
 */
// 生产统一入口域名
const PROD_API = 'https://agent.shengxia.me'

function resolveApiBase() {
  // 本地开发(通过vite代理)走空串
  if (window.location.hostname === 'localhost' || window.location.hostname === '127.0.0.1') {
    return ''
  }
  return PROD_API
}

// Java 主后端: 登录、业务、数据库、行情、回测、AI代理
const API_BASE = resolveApiBase()

export { API_BASE }

let csrfToken = null
let csrfPromise = null

function resetCsrfToken() {
  csrfToken = null
  csrfPromise = null
}

async function ensureCsrfToken(base, force = false) {
  if (force) resetCsrfToken()
  if (csrfToken) return csrfToken
  if (!csrfPromise) {
    csrfPromise = fetch(`${base}/api/auth/csrf`, { credentials: 'include' })
      .then(async (res) => {
        const data = await res.json().catch(() => ({}))
        const token = data?.data?.token
        if (!res.ok || !token) throw new Error(data?.message || '无法获取 CSRF token')
        csrfToken = token
        return token
      })
      .finally(() => { csrfPromise = null })
  }
  return csrfPromise
}

async function request(base, path, options = {}, params = {}) {
  const qs = new URLSearchParams(
    Object.entries(params).filter(([, v]) => v !== undefined && v !== '')
  ).toString()
  const url = `${base}${path}${qs ? '?' + qs : ''}`
  const method = (options.method || 'GET').toUpperCase()
  const headers = { ...(options.headers || {}) }
  if (options.body != null && !headers['Content-Type']) headers['Content-Type'] = 'application/json'
  const csrfRequired = !['GET', 'HEAD', 'OPTIONS'].includes(method)
  for (let attempt = 0; attempt < 2; attempt += 1) {
    const requestHeaders = { ...headers }
    if (csrfRequired) {
      requestHeaders['X-XSRF-TOKEN'] = await ensureCsrfToken(base, attempt > 0)
    }
    const res = await fetch(url, {
      ...options,
      method,
      headers: requestHeaders,
      credentials: 'include',
    })
    const data = await res.json().catch(() => ({}))
    // 安全 Cookie 可能在后续 GET 响应中被浏览器清理；认证请求和幂等的市场偏好替换
    // 失败时刷新一次 CSRF token，避免把 CSRF 失败误呈现成“未登录”。
    const csrfRetryable = path.startsWith('/api/auth/') || path === '/api/market/preferences'
    if (attempt === 0 && csrfRequired && csrfRetryable && (res.status === 401 || res.status === 403)) {
      continue
    }
    return data
  }
}

function get(base, path, params) { return request(base, path, { method: 'GET' }, params) }
function post(base, path, body) { return request(base, path, { method: 'POST', body: JSON.stringify(body) }) }

function openSse(base, path, eventName, onEvent, onError) {
  const source = new EventSource(`${base}${path}`, { withCredentials: true })
  source.addEventListener(eventName, event => {
    try {
      onEvent?.(JSON.parse(event.data))
    } catch (_) {
      onEvent?.(event.data)
    }
  })
  source.onerror = error => onError?.(error)
  return () => source.close()
}

async function postSse(base, path, body, onEvent, signal) {
  let res
  for (let attempt = 0; attempt < 2; attempt += 1) {
    const token = await ensureCsrfToken(base, attempt > 0)
    res = await fetch(`${base}${path}`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'X-XSRF-TOKEN': token,
        Accept: 'text/event-stream',
      },
      credentials: 'include',
      body: JSON.stringify(body),
      signal,
    })
    if (attempt === 0 && (res.status === 401 || res.status === 403)) {
      // Retry only before consuming the stream. This keeps the retry bounded
      // and lets the browser release the rejected response body first.
      await res.body?.cancel?.()
      continue
    }
    break
  }

  if (!res.ok) {
    const text = await res.text()
    let message = text || `HTTP ${res.status}`
    try {
      const data = JSON.parse(text)
      message = data?.message || data?.detail || message
    } catch (_) { /* 非 JSON 错误正文 */ }
    throw new Error(message)
  }
  if (!res.body) throw new Error('浏览器不支持流式响应')

  const reader = res.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''

  function consumeFrame(frame) {
    let event = 'message'
    const dataLines = []
    for (const line of frame.split(/\r?\n/)) {
      if (line.startsWith('event:')) event = line.slice(6).trim()
      else if (line.startsWith('data:')) dataLines.push(line.slice(5).trimStart())
    }
    if (!dataLines.length) return
    const raw = dataLines.join('\n')
    let data = raw
    try { data = JSON.parse(raw) } catch (_) { /* 允许纯文本 SSE */ }
    onEvent?.({ event, data })
  }

  while (true) {
    const { value, done } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })
    const frames = buffer.split(/\r?\n\r?\n/)
    buffer = frames.pop() || ''
    frames.forEach(consumeFrame)
  }
  buffer += decoder.decode()
  if (buffer.trim()) consumeFrame(buffer)
}

export const api = {
  // ===== Java 后端 (数据存储) =====
  // 认证
  register: (email, password, displayName) => post(API_BASE, '/api/auth/register', { email, password, displayName }),
  login: (email, password) => post(API_BASE, '/api/auth/login', { email, password }),
  sendEmailVerification: (email) => post(API_BASE, '/api/auth/verification/email', { email }),
  confirmEmailVerification: (email, code) => post(API_BASE, '/api/auth/verification/email/confirm', { email, code }),
  requestPasswordReset: (email) => post(API_BASE, '/api/auth/password/reset/request', { email }),
  resetPassword: (email, code, newPassword) => post(API_BASE, '/api/auth/password/reset', { email, code, newPassword }),
  updateProfile: (displayName) => request(API_BASE, '/api/auth/profile', {
    method: 'PATCH', body: JSON.stringify({ displayName }),
  }),
  me: () => get(API_BASE, '/api/auth/me'),
  logout: () => post(API_BASE, '/api/auth/logout', {}),
  githubBindAuthorize: () => { window.location.href = `${API_BASE}/api/auth/github/bind/authorize` },
  health: () => get(API_BASE, '/api/health'),
  healthReady: () => get(API_BASE, '/api/health/ready'),
  databaseHealth: () => get(API_BASE, '/api/health/db'),
  // 模拟盘 (需登录)
  simAccount: () => get(API_BASE, '/api/sim/account'),
  simTrades: (limit) => get(API_BASE, '/api/sim/trades', { limit }),
  simOrder: (type, symbol, quantity, leverage, clientOrderId) => post(API_BASE, '/api/sim/order', {
    type, symbol, quantity, leverage: leverage || 1, clientOrderId,
  }),
  // 市场数据 (Java 主管数据存储: 实时价格 + K线)
  marketPrices: () => get(API_BASE, '/api/market/prices'),
  marketPriceStream: (onEvent, onError) => openSse(
    API_BASE, '/api/market/prices/stream', 'prices', onEvent, onError,
  ),
  marketKline: (params) => get(API_BASE, '/api/market/kline', params),
  marketInstruments: () => get(API_BASE, '/api/market/instruments'),
  resolveMarketInstrument: (market, query) => get(API_BASE, '/api/market/instruments/resolve', { market, query }),
  marketPreferences: () => get(API_BASE, '/api/market/preferences'),
  saveMarketPreferences: (body) => request(API_BASE, '/api/market/preferences', {
    method: 'PUT', body: JSON.stringify(body),
  }),
  marketSession: (market) => get(API_BASE, '/api/market/session', { market }),
  marketAssetQuote: (market, symbol) => get(API_BASE, '/api/market/extended/quote', { market, symbol }),
  marketAssetKline: (market, symbol, interval, limit) => get(
    API_BASE, '/api/market/extended/kline', { market, symbol, interval, limit },
  ),

  // Python AI 服务健康状态也由 Java 代理查询。
  aiServiceHealth: () => get(API_BASE, '/api/health/ai'),
  // 回测: Java 读取/管理数据库并统一计算，浏览器不再直连 Python。
  backtest: (params) => get(API_BASE, '/api/backtest', params),
  // AI: 浏览器只调用 Java；Java 完成 JWT 鉴权后再转发 Python。
  aiStatus: () => get(API_BASE, '/api/ai/capabilities'),
  aiChat: (messages) => post(API_BASE, '/api/ai/chat', { messages }),
  aiChatStream: (messages, onEvent, signal) => postSse(
    API_BASE, '/api/ai/chat/stream', { messages }, onEvent, signal,
  ),
  aiQuote: (priceData) => post(API_BASE, '/api/ai/quote', { price_data: priceData }),
  aiFinancialReport: (content) => post(API_BASE, '/api/ai/financial/report', { content }),
  aiChain: (node, context = '') => post(API_BASE, '/api/ai/analyze/chain', { node, context }),
  aiSentiment: (reports) => post(API_BASE, '/api/ai/analyze/sentiment', { reports }),
  aiRisk: (market, confidence = 0.95, portfolioValue = null, days = 60) => post(API_BASE, '/api/ai/analyze/risk', {
    market, confidence, portfolio_value: portfolioValue, days,
  }),
  // 个性化策略生成（FR-11）：风险偏好问卷 → 风险等级 + 建议配置比例 + AI 策略说明
  aiStrategy: (payload) => post(API_BASE, '/api/ai/analyze/strategy', payload, { csrfRetry: true }),

  // 管理员账户、配额和功能权限
  adminUsers: (query = '', limit = 50) => get(API_BASE, '/api/admin/users', { query, limit }),
  adminUser: (userId) => get(API_BASE, `/api/admin/users/${userId}`),
  adminUpdateStatus: (userId, enabled) => request(API_BASE, `/api/admin/users/${userId}/status`, {
    method: 'PATCH', body: JSON.stringify({ enabled }),
  }),
  adminUpdateRole: (userId, role) => request(API_BASE, `/api/admin/users/${userId}/role`, {
    method: 'PATCH', body: JSON.stringify({ role }),
  }),
  adminUpdateQuota: (userId, body) => request(API_BASE, `/api/admin/users/${userId}/quota`, {
    method: 'PUT', body: JSON.stringify(body),
  }),
  adminUpdatePermissions: (userId, body) => request(API_BASE, `/api/admin/users/${userId}/permissions`, {
    method: 'PUT', body: JSON.stringify(body),
  }),

  // ===== 京东积存金：Java 定时采集 + Java 数据库 =====
  jdPrices: () => get(API_BASE, '/api/market/jd/prices'),
  jdKline: (market, interval, limit) => get(API_BASE, '/api/market/jd/kline', { market, interval, limit }),
}
