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

async function ensureCsrfToken(base) {
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
  if (!['GET', 'HEAD', 'OPTIONS'].includes(method)) {
    headers['X-XSRF-TOKEN'] = await ensureCsrfToken(base)
  }
  const res = await fetch(url, { ...options, method, headers, credentials: 'include' })
  const data = await res.json().catch(() => ({}))
  return data
}

function get(base, path, params) { return request(base, path, { method: 'GET' }, params) }
function post(base, path, body) { return request(base, path, { method: 'POST', body: JSON.stringify(body) }) }

async function postSse(base, path, body, onEvent, signal) {
  const token = await ensureCsrfToken(base)
  const res = await fetch(`${base}${path}`, {
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
  me: () => get(API_BASE, '/api/auth/me'),
  logout: () => post(API_BASE, '/api/auth/logout', {}),
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
  marketKline: (params) => get(API_BASE, '/api/market/kline', params),

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

  // ===== 京东积存金：Java 定时采集 + Java 数据库 =====
  jdPrices: () => get(API_BASE, '/api/market/jd/prices'),
  jdKline: (market, interval, limit) => get(API_BASE, '/api/market/jd/kline', { market, interval, limit }),
}
