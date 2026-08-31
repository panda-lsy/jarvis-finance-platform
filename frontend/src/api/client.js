/*
 * API 客户端 (支持登录认证) - 混合双后端架构
 * 后端地址 (生产经 agent.shengxia.me, CloudFlare):
 *  - Java 主管数据存储: https://agent.shengxia.me/api/*  (auth登录/模拟盘/health)  → nginx → Java 8200
 *  - Python 管行情+AI:   https://agent.shengxia.me/py/api/* (行情/历史K线/AI对话)  → nginx → Python 8100
 * 本地开发: vite proxy
 */
const TOKEN_KEY = 'jarvis_token'

// 生产: 统一入口域名 (Java = /api, Python = /py/api)
const PROD_API = 'https://agent.shengxia.me'

function resolveApiBase() {
  // 本地开发(通过vite代理)走空串
  if (window.location.hostname === 'localhost' || window.location.hostname === '127.0.0.1') {
    return ''
  }
  return PROD_API
}

// Java 后端 (数据存储): 登录/模拟盘/健康
const API_BASE = resolveApiBase()
// Python 后端 (行情+AI): /py/api 前缀
const API_PY = API_BASE + '/py'

export { API_BASE, API_PY }

export const auth = {
  get token() { return localStorage.getItem(TOKEN_KEY) },
  set token(v) { v ? localStorage.setItem(TOKEN_KEY, v) : localStorage.removeItem(TOKEN_KEY) },
  isLoggedIn() { return !!this.token },
  logout() { this.token = null },
}

async function request(base, path, options = {}, params = {}) {
  const qs = new URLSearchParams(
    Object.entries(params).filter(([, v]) => v !== undefined && v !== '')
  ).toString()
  const url = `${base}${path}${qs ? '?' + qs : ''}`
  const headers = { 'Content-Type': 'application/json', ...(options.headers || {}) }
  if (auth.token) headers['Authorization'] = 'Bearer ' + auth.token

  const res = await fetch(url, { ...options, headers })
  const data = await res.json().catch(() => ({}))
  // 401 -> 清除登录态
  if (res.status === 401) {
    auth.logout()
  }
  return data  // 统一 ApiResponse {code, message, data}
}

function get(base, path, params) { return request(base, path, { method: 'GET' }, params) }
function post(base, path, body) { return request(base, path, { method: 'POST', body: JSON.stringify(body) }) }

export const api = {
  // ===== Java 后端 (数据存储) =====
  // 认证
  register: (email, password, displayName) => post(API_BASE, '/api/auth/register', { email, password, displayName }),
  login: (email, password) => post(API_BASE, '/api/auth/login', { email, password }),
  health: () => get(API_BASE, '/api/health'),
  // 模拟盘 (需登录)
  simAccount: () => get(API_BASE, '/api/sim/account'),
  simTrades: (limit) => get(API_BASE, '/api/sim/trades', { limit }),
  simOrder: (type, symbol, quantity, leverage) => post(API_BASE, '/api/sim/order', { type, symbol, quantity, leverage: leverage || 1 }),

  // ===== Python 后端 (行情 + AI) =====
  // 行情
  pyHealth: () => get(API_PY, '/api/health'),
  goldQuote: (symbol) => get(API_PY, '/api/prices', { market: symbol }),
  goldKline: (params) => get(API_PY, '/api/kline', params),
  // 回测
  backtest: (params) => get(API_PY, '/api/backtest', params),
  // AI (DeepSeek, Python 直连)
  aiStatus: () => get(API_PY, '/api/ai/capabilities'),
  aiChat: (messages) => post(API_PY, '/api/ai/chat', { messages }),
}
