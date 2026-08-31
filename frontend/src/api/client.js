/*
 * API 客户端 (支持登录认证)
 * 后端地址:
 *  - 本地开发: vite proxy → http://127.0.0.1:8200 (Java主后端)
 *  - 生产(GitHub Pages): ?api=http://<本机IP>:8200 指定
 */
const TOKEN_KEY = 'jarvis_token'

function resolveApiBase() {
  const params = new URLSearchParams(window.location.search)
  const fromUrl = params.get('api')
  if (fromUrl) return fromUrl.replace(/\/$/, '')
  if (window.location.hostname === 'localhost' || window.location.hostname === '127.0.0.1') {
    return ''  // 开发走 vite 代理
  }
  return ''
}

export const API_BASE = resolveApiBase()

export const auth = {
  get token() { return localStorage.getItem(TOKEN_KEY) },
  set token(v) { v ? localStorage.setItem(TOKEN_KEY, v) : localStorage.removeItem(TOKEN_KEY) },
  isLoggedIn() { return !!this.token },
  logout() { this.token = null },
}

async function request(path, options = {}, params = {}) {
  const qs = new URLSearchParams(
    Object.entries(params).filter(([, v]) => v !== undefined && v !== '')
  ).toString()
  const url = `${API_BASE}${path}${qs ? '?' + qs : ''}`
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

function get(path, params) { return request(path, { method: 'GET' }, params) }
function post(path, body) { return request(path, { method: 'POST', body: JSON.stringify(body) }) }

export const api = {
  // 认证
  register: (email, password, displayName) => post('/api/auth/register', { email, password, displayName }),
  login: (email, password) => post('/api/auth/login', { email, password }),
  // 行情
  health: () => get('/api/health'),
  goldQuote: (symbol) => get('/api/gold/quote', { symbol }),
  goldKline: (params) => get('/api/gold/kline', params),
  // 模拟盘 (需登录)
  simAccount: () => get('/api/sim/account'),
  simTrades: (limit) => get('/api/sim/trades', { limit }),
  simOrder: (type, symbol, quantity) => post('/api/sim/order', { type, symbol, quantity }),
  // AI
  aiStatus: () => get('/api/ai/status'),
  aiChat: (messages) => post('/api/ai/chat', { messages }),
}
