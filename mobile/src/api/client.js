/**
 * 移动端 API 客户端
 * 后端地址: 通过 app.json / env 配置, 指向本机后端 (FastAPI :8100)
 * 真机需使用局域网 IP, 例如 http://192.168.1.100:8100
 */
import { Platform } from 'react-native'

// 本机后端地址 (真机调试改成你电脑的局域网 IP)
const HOST = 'http://192.168.1.100:8100'
const LOCALHOST = Platform.OS === 'android' ? 'http://10.0.2.2:8100' : 'http://127.0.0.1:8100'

export const API_BASE = HOST // 生产可读取 Config

async function get(path, params = {}) {
  const qs = Object.entries(params)
    .filter(([, v]) => v !== undefined && v !== '')
    .map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(v)}`)
    .join('&')
  const url = `${API_BASE}${path}${qs ? '?' + qs : ''}`
  const res = await fetch(url)
  if (!res.ok) throw new Error(`HTTP ${res.status}`)
  return res.json()
}

export const api = {
  health: () => get('/api/health'),
  prices: () => get('/api/prices'),
  kline: (params) => get('/api/kline', params),
  backtest: (params) => get('/api/backtest', params),
}
