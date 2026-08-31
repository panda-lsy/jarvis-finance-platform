/*
 * API 客户端
 * 演示前端与后端分离:
 *  - 本地开发: vite proxy → http://127.0.0.1:8100
 *  - GitHub Pages(生产): 通过 URL 参数 ?api=http://<本机IP>:8100 指定后端地址
 */
const DEFAULT_API = ''  // 空 -> 使用相对 /api (开发环境代理)

function resolveApiBase() {
  const params = new URLSearchParams(window.location.search)
  const fromUrl = params.get('api')
  if (fromUrl) return fromUrl.replace(/\/$/, '')
  if (DEFAULT_API) return DEFAULT_API
  if (window.location.hostname === 'localhost' || window.location.hostname === '127.0.0.1') {
    return ''  // 开发走 vite 代理
  }
  // 生产(GitHub Pages): 需通过 ?api= 指定后端, 否则不可用
  return ''
}

export const API_BASE = resolveApiBase()

async function get(path, params = {}) {
  const qs = new URLSearchParams(
    Object.entries(params).filter(([, v]) => v !== undefined && v !== '')
  ).toString()
  const url = `${API_BASE}${path}${qs ? '?' + qs : ''}`
  const res = await fetch(url)
  if (!res.ok) throw new Error(`HTTP ${res.status}: ${url}`)
  return res.json()
}

export const api = {
  health: () => get('/api/health'),
  markets: () => get('/api/markets'),
  prices: () => get('/api/prices'),
  storage: () => get('/api/storage'),
  kline: (params) => get('/api/kline', params),
  backtest: (params) => get('/api/backtest', params),
}
