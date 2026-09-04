/**
 * 移动端 API 客户端。
 * 架构与 Web 保持一致：移动端只访问 Java 主后端，不直连 Python AI 服务。
 */

export const API_BASE = process.env.EXPO_PUBLIC_API_BASE || 'https://agent.shengxia.me'

let authToken = null

export function setAuthToken(token) {
  authToken = token || null
}

async function request(path, params = {}) {
  const qs = Object.entries(params)
    .filter(([, v]) => v !== undefined && v !== '')
    .map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(v)}`)
    .join('&')
  const url = `${API_BASE}${path}${qs ? '?' + qs : ''}`
  const headers = {}
  if (authToken) headers.Authorization = `Bearer ${authToken}`

  const res = await fetch(url, { headers })
  const data = await res.json().catch(() => ({}))
  if (!res.ok) {
    throw new Error(data.message || data.detail || `HTTP ${res.status}`)
  }
  return data
}

export const api = {
  health: () => request('/api/health'),
  prices: () => request('/api/market/prices'),
  kline: (params) => request('/api/market/kline', params),
  backtest: (params) => request('/api/backtest', params),
}
