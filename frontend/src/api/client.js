/*
 * API 客户端 (支持登录认证)
 * 浏览器只访问 Java 主后端；Java 负责业务/数据库，并在内部转发 AI 请求到 Python。
 * 生产入口: https://agent.shengxia.me/api/* → Java 8200
 */
// 生产统一入口域名
const PROD_API = 'https://agent.shengxia.me'
export const AUTH_EXPIRED_EVENT = 'jarvis:auth-expired'

function configuredApiMode() {
  return String(import.meta.env?.VITE_API_MODE || '').trim().toLowerCase()
}

function resolveApiBase() {
  // 本地开发(通过vite代理)走空串
  if (window.location.hostname === 'localhost' || window.location.hostname === '127.0.0.1') {
    return ''
  }
  // 同源 API 代理准备完成后，可在构建时设置 VITE_API_MODE=same-origin。
  // 默认仍保持现网 agent.shengxia.me，避免在 Worker Route 尚未启用时产生 404。
  if (configuredApiMode() === 'same-origin' && window.location.hostname === 'f.shengxia.me') return ''
  return PROD_API
}

// Java 主后端: 登录、业务、数据库、行情、回测、AI代理
const API_BASE = resolveApiBase()

export { API_BASE }

let csrfToken = null
let csrfPromise = null
let authProbePromise = null

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

function withHttpStatus(data, status) {
  const payload = data && typeof data === 'object' ? data : {}
  return { ...payload, httpStatus: status }
}

function isPublicAuthPath(path) {
  return path === '/api/auth/login'
    || path === '/api/auth/register'
    || path === '/api/auth/logout'
    || path === '/api/auth/csrf'
    || path.startsWith('/api/auth/verification/')
    || path.startsWith('/api/auth/password/')
    || path.startsWith('/api/auth/github/')
}

function notifyAuthExpired() {
  if (typeof window?.dispatchEvent !== 'function' || typeof CustomEvent !== 'function') return
  window.dispatchEvent(new CustomEvent(AUTH_EXPIRED_EVENT))
}

async function confirmSessionAfterUnauthorized(base, path) {
  if (path === '/api/auth/me' || isPublicAuthPath(path)) return
  if (!authProbePromise) {
    authProbePromise = fetch(`${base}/api/auth/me`, { credentials: 'include' })
      .then((res) => {
        if (res.status === 401) return false
        if (res.ok) return true
        return null
      })
      .catch(() => null)
      .finally(() => { authProbePromise = null })
  }
  const valid = await authProbePromise
  if (valid === false) notifyAuthExpired()
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
    // 后端使用明确的 419 表示 CSRF 失败；401 始终表示认证问题，403 表示权限拒绝，不应自动重放写请求。
    if (attempt === 0 && csrfRequired && res.status === 419) {
      continue
    }
    if (res.status === 401) await confirmSessionAfterUnauthorized(base, path)
    return withHttpStatus(data, res.status)
  }
}

function get(base, path, params, options = {}) { return request(base, path, { ...options, method: 'GET' }, params) }
function post(base, path, body) { return request(base, path, { method: 'POST', body: JSON.stringify(body) }) }

async function postAuth(base, path, body, resetRegardless = false) {
  const response = await post(base, path, body)
  if (resetRegardless || (response?.httpStatus >= 200 && response.httpStatus < 300)) resetCsrfToken()
  return response
}

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
    if (attempt === 0 && res.status === 419) {
      // Retry only before consuming the stream. This keeps the retry bounded
      // and lets the browser release the rejected response body first.
      await res.body?.cancel?.()
      continue
    }
    break
  }

  if (!res.ok) {
    if (res.status === 401) await confirmSessionAfterUnauthorized(base, path)
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
  register: (email, password, displayName) => postAuth(API_BASE, '/api/auth/register', { email, password, displayName }),
  login: (email, password) => postAuth(API_BASE, '/api/auth/login', { email, password }),
  sendEmailVerification: (email) => post(API_BASE, '/api/auth/verification/email', { email }),
  confirmEmailVerification: (email, code) => post(API_BASE, '/api/auth/verification/email/confirm', { email, code }),
  requestPasswordReset: (email) => post(API_BASE, '/api/auth/password/reset/request', { email }),
  resetPassword: (email, code, newPassword) => post(API_BASE, '/api/auth/password/reset', { email, code, newPassword }),
  updateProfile: (displayName) => request(API_BASE, '/api/auth/profile', {
    method: 'PATCH', body: JSON.stringify({ displayName }),
  }),
  socialProfile: () => get(API_BASE, '/api/social/profile'),
  updateSocialProfile: (body) => request(API_BASE, '/api/social/profile', {
    method: 'PATCH', body: JSON.stringify(body),
  }),
  socialAchievements: () => get(API_BASE, '/api/social/achievements'),
  socialUsers: (query = '', page = 0, size = 20) => get(API_BASE, '/api/social/users', { query, page, size }),
  socialUser: (userId) => get(API_BASE, `/api/social/users/${userId}`),
  socialUserActivity: (userId, page = 0, size = 20) => get(API_BASE, `/api/social/users/${userId}/activity`, { page, size }),
  communityFeed: (page = 0, size = 20) => get(API_BASE, '/api/social/feed', { page, size }),
  communityCreatePost: (body) => post(API_BASE, '/api/social/posts', body),
  communityDeletePost: (postId) => request(API_BASE, `/api/social/posts/${postId}`, { method: 'DELETE' }),
  communityGroups: (query = '', page = 0, size = 30) => get(API_BASE, '/api/social/groups', { query, page, size }),
  communityGroup: (groupId) => get(API_BASE, `/api/social/groups/${groupId}`),
  communityUpdateGroup: (groupId, body) => request(API_BASE, `/api/social/groups/${groupId}`, {
    method: 'PATCH', body: JSON.stringify(body),
  }),
  communityDeleteGroup: (groupId) => request(API_BASE, `/api/social/groups/${groupId}`, { method: 'DELETE' }),
  communityCreateGroup: (body) => post(API_BASE, '/api/social/groups', body),
  communityJoinGroup: (groupId) => post(API_BASE, `/api/social/groups/${groupId}/join`, {}),
  communityLeaveGroup: (groupId) => request(API_BASE, `/api/social/groups/${groupId}/leave`, { method: 'DELETE' }),
  communityAddMember: (groupId, userId) => post(API_BASE, `/api/social/groups/${groupId}/members/${userId}`, {}),
  communityRemoveMember: (groupId, userId) => request(API_BASE, `/api/social/groups/${groupId}/members/${userId}`, { method: 'DELETE' }),
  communityGroupPosts: (groupId, page = 0, size = 20) => get(API_BASE, `/api/social/groups/${groupId}/posts`, { page, size }),
  communityCreateGroupPost: (groupId, body) => post(API_BASE, `/api/social/groups/${groupId}/posts`, body),
  socialConversations: () => get(API_BASE, '/api/social/messages/conversations'),
  socialMessageUnreadCount: () => get(API_BASE, '/api/social/messages/unread-count'),
  socialThread: (userId, page = 0, size = 40) => get(API_BASE, `/api/social/messages/${userId}`, { page, size }),
  socialSendMessage: (userId, content) => post(API_BASE, `/api/social/messages/${userId}`, { content }),
  me: () => get(API_BASE, '/api/auth/me'),
  logout: () => postAuth(API_BASE, '/api/auth/logout', {}, true),
  githubBindAuthorize: () => { window.location.href = `${API_BASE}/api/auth/github/bind/authorize` },
  health: () => get(API_BASE, '/api/health'),
  healthReady: () => get(API_BASE, '/api/health/ready'),
  databaseHealth: () => get(API_BASE, '/api/health/db'),
  // 模拟盘 (需登录)
  simAccount: () => get(API_BASE, '/api/sim/account'),
  simTrades: (limit) => get(API_BASE, '/api/sim/trades', { limit }),
  simOrder: (type, symbol, quantity, leverage, clientOrderId, options = {}) => post(API_BASE, '/api/sim/order', {
    type,
    symbol,
    quantity,
    leverage: leverage || 1,
    clientOrderId,
    orderType: options.orderType || 'MARKET',
    stopPrice: options.stopPrice,
    timeInForce: options.timeInForce || 'DAY',
  }),
  simOpenOrders: () => get(API_BASE, '/api/sim/orders/open'),
  simUpdateOrder: (orderId, stopPrice) => request(API_BASE, `/api/sim/orders/${orderId}`, {
    method: 'PATCH', body: JSON.stringify({ stopPrice }),
  }),
  simCancelOrder: (orderId) => request(API_BASE, `/api/sim/orders/${orderId}`, { method: 'DELETE' }),
  // 市场数据 (Java 主管数据存储: 实时价格 + K线)
  marketPrices: () => get(API_BASE, '/api/market/prices'),
  marketPriceStream: (onEvent, onError) => openSse(
    API_BASE, '/api/market/prices/stream', 'prices', onEvent, onError,
  ),
  marketKline: (params) => get(API_BASE, '/api/market/kline', params),
  marketIndicators: (market, limit = 120) => get(API_BASE, '/api/market/indicators', { market, limit }),
  marketOverview: () => get(API_BASE, '/api/market/overview'),
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
  // Agent Research Runtime：Codex-style 可观察工具调用流
  agentResearchStream: (question, context, onEvent, signal) => postSse(
    API_BASE, '/api/agent/research/stream', { question, context }, onEvent, signal,
  ),
  agentRuns: () => get(API_BASE, '/api/agent/runs'),
  agentRun: (runId) => get(API_BASE, `/api/agent/runs/${encodeURIComponent(runId)}`),
  agentRunEvents: (runId) => get(API_BASE, `/api/agent/runs/${encodeURIComponent(runId)}/events`),
  agentRunStream: (runId, onEvent, onError) => openSse(
    API_BASE, `/api/agent/runs/${encodeURIComponent(runId)}/stream`, 'agent_step',
    data => onEvent?.({ event: 'agent_step', data }), onError,
  ),
  agentCancel: (runId) => request(API_BASE, `/api/agent/runs/${encodeURIComponent(runId)}`, { method: 'DELETE' }),
  // 智能询报价（FR-07）：现价/涨跌 + 未来价格走势趋势区间 + AI 解读。
  // closes 由 Java 服务端从自营 K 线库注入并覆盖客户端传值，前端只传标的与预测参数。
  aiQuote: (priceData, options = {}) => post(API_BASE, '/api/ai/quote', {
    price_data: priceData,
    market: options.market,
    horizon_days: options.horizonDays,
    confidence: options.confidence,
  }),
  // 市场趋势预测（FR-12）：单资产日 K 统计基线（趋势区间）+ 技术依据 + AI 解读。
  // 历史收盘价由 Java 服务端从自营 K 线库注入并覆盖客户端传值，前端只传标的与预测参数。
  aiTrend: (options = {}) => post(API_BASE, '/api/ai/trend', {
    market: options.market,
    horizon_days: options.horizonDays,
    confidence: options.confidence,
  }),
  aiFinancialReport: (content) => post(API_BASE, '/api/ai/financial/report', { content }),
  aiChain: (node, context = '') => post(API_BASE, '/api/ai/analyze/chain', { node, context }),
  aiSentiment: (reports) => post(API_BASE, '/api/ai/analyze/sentiment', { reports }),
  aiRisk: (market, confidence = 0.95, portfolioValue = null, days = 60) => post(API_BASE, '/api/ai/analyze/risk', {
    market, confidence, portfolio_value: portfolioValue, days,
  }),
  // 个性化策略生成（FR-11）：风险偏好问卷 → 风险等级 + 建议配置比例 + AI 策略说明
  aiStrategy: (payload) => post(API_BASE, '/api/ai/analyze/strategy', payload, { csrfRetry: true }),

  // 研究任务 (Phase 2 AI Research Core)：**有状态**的研究工作流——任务落库、可回看、失败有原因。
  // 与 /api/ai/* 的透传端点不同，这里返回 Java 自己的 {code,message,data} 信封，
  // 浏览器不需要知道 Python 服务的信封长什么样。
  researchTasks: (page = 0, size = 20) => get(API_BASE, '/api/research/tasks', { page, size }),
  researchTask: (id) => get(API_BASE, `/api/research/tasks/${id}`),
  createResearchTask: (body) => post(API_BASE, '/api/research/tasks', body),
  runResearchTask: (id) => post(API_BASE, `/api/research/tasks/${id}/run`, {}),

  // 定时任务：后端能力已上线，前端统一从这里接入，避免页面各自拼 URL。
  scheduledTasks: (type = '', page = 0, size = 20) => get(API_BASE, '/api/scheduled-tasks', { type: type || undefined, page, size }),
  scheduledTask: (id) => get(API_BASE, `/api/scheduled-tasks/${id}`),
  scheduledTaskTypes: () => get(API_BASE, '/api/scheduled-tasks/types'),
  // 当前账号能否管理定时任务（TASK_MANAGE）。渲染阶段先问一次，用来把写操作置灰 ——
  // 拿不到就保持"不置灰"，让后端在提交时兜底，避免把能用的用户误挡。
  scheduledTaskCapabilities: () => get(API_BASE, '/api/scheduled-tasks/capabilities'),
  createScheduledTask: (body) => post(API_BASE, '/api/scheduled-tasks', body),
  updateScheduledTask: (id, body) => request(API_BASE, `/api/scheduled-tasks/${id}`, { method: 'PUT', body: JSON.stringify(body) }),
  pauseScheduledTask: (id) => post(API_BASE, `/api/scheduled-tasks/${id}/pause`, {}),
  resumeScheduledTask: (id) => post(API_BASE, `/api/scheduled-tasks/${id}/resume`, {}),
  runScheduledTask: (id) => post(API_BASE, `/api/scheduled-tasks/${id}/run`, {}),
  deleteScheduledTask: (id) => request(API_BASE, `/api/scheduled-tasks/${id}`, { method: 'DELETE' }),
  scheduledTaskRuns: (id, page = 0, size = 20) => get(API_BASE, `/api/scheduled-tasks/${id}/runs`, { page, size }),

  // 站内通知：任务完成/失败、风险事件等统一从后端通知中心读取。
  notifications: (unreadOnly = false, page = 0, size = 20) => get(API_BASE, '/api/notifications', { unread_only: unreadOnly, page, size }),
  notificationUnreadCount: () => get(API_BASE, '/api/notifications/unread-count'),
  markNotificationRead: (id) => post(API_BASE, `/api/notifications/${id}/read`, {}),
  markAllNotificationsRead: () => post(API_BASE, '/api/notifications/read-all', {}),
  deleteNotification: (id) => request(API_BASE, `/api/notifications/${id}`, { method: 'DELETE' }),

  // 当前用户审计事件。
  auditRecent: (limit = 50) => get(API_BASE, '/api/audit/recent', { limit }),
  auditReport: (days = 7) => get(API_BASE, '/api/audit/report', { days }),

  // 管理员账户、配额和功能权限
  adminUsers: (query = '', limit = 50) => get(API_BASE, '/api/admin/users', { query, limit }),
  adminUser: (userId) => get(API_BASE, `/api/admin/users/${userId}`),
  adminUserAudit: (userId, limit = 50) => get(API_BASE, `/api/admin/users/${userId}/audit`, { limit }),
  adminUpdateStatus: (userId, enabled, reason) => request(API_BASE, `/api/admin/users/${userId}/status`, {
    method: 'PATCH', body: JSON.stringify({ enabled, reason }),
  }),
  adminUpdateRole: (userId, role, reason) => request(API_BASE, `/api/admin/users/${userId}/role`, {
    method: 'PATCH', body: JSON.stringify({ role, reason }),
  }),
  adminRevokeSessions: (userId, body) => request(API_BASE, `/api/admin/users/${userId}/sessions/revoke`, {
    method: 'POST', body: JSON.stringify(body),
  }),
  adminUpdateQuota: (userId, body) => request(API_BASE, `/api/admin/users/${userId}/quota`, {
    method: 'PUT', body: JSON.stringify(body),
  }),
  adminUpdatePermissions: (userId, body) => request(API_BASE, `/api/admin/users/${userId}/permissions`, {
    method: 'PUT', body: JSON.stringify(body),
  }),
  adminGroups: () => get(API_BASE, '/api/admin/groups'),
  adminGroup: (groupId) => get(API_BASE, `/api/admin/groups/${groupId}`),
  adminCreateGroup: (body) => request(API_BASE, '/api/admin/groups', {
    method: 'POST', body: JSON.stringify(body),
  }),
  adminUpdateGroup: (groupId, body) => request(API_BASE, `/api/admin/groups/${groupId}`, {
    method: 'PATCH', body: JSON.stringify(body),
  }),
  adminDeleteGroup: (groupId, reason) => request(API_BASE, `/api/admin/groups/${groupId}`, {
    method: 'DELETE', body: JSON.stringify({ reason }),
  }),
  adminUpdateGroupMembers: (groupId, body) => request(API_BASE, `/api/admin/groups/${groupId}/members`, {
    method: 'PUT', body: JSON.stringify(body),
  }),
  adminUpdateGroupQuota: (groupId, body) => request(API_BASE, `/api/admin/groups/${groupId}/quota`, {
    method: 'PUT', body: JSON.stringify(body),
  }),
  adminUpdateGroupPermissions: (groupId, body) => request(API_BASE, `/api/admin/groups/${groupId}/permissions`, {
    method: 'PUT', body: JSON.stringify(body),
  }),
  // RSS 信息中心：来源目录与用户订阅由 Java + PostgreSQL 持久化。
  newsSources: (signal) => get(API_BASE, '/api/news/sources', undefined, { signal }),
  newsSubscriptions: (signal) => get(API_BASE, '/api/news/subscriptions', undefined, { signal }),
  saveNewsSubscriptions: (body) => request(API_BASE, '/api/news/subscriptions', {
    method: 'PUT', body: JSON.stringify(body),
  }),
  adminNewsSources: () => get(API_BASE, '/api/admin/news/sources'),
  adminCreateNewsSource: (body) => post(API_BASE, '/api/admin/news/sources', body),
  adminUpdateNewsSource: (sourceKey, body) => request(
    API_BASE, `/api/admin/news/sources/${encodeURIComponent(sourceKey)}`,
    { method: 'PATCH', body: JSON.stringify(body) },
  ),

  // ===== 京东积存金：Java 定时采集 + Java 数据库 =====
  jdPrices: () => get(API_BASE, '/api/market/jd/prices'),
  jdKline: (market, interval, limit) => get(API_BASE, '/api/market/jd/kline', { market, interval, limit }),

  // 每日要闻：Java 代理 Python 的 RSS digest，抓取间隔由 Python 侧限制（默认 300s）。
  newsDaily: (limit = 12, force = false, ranking = 'smart') => get(API_BASE, '/api/news/daily', { limit, refresh: true, force, ranking }),
  newsDailyWithSignal: (limit = 12, force = false, ranking = 'smart', signal) => get(
    API_BASE, '/api/news/daily', { limit, refresh: true, force, ranking }, { signal },
  ),
  newsTranslate: (titles) => post(API_BASE, '/api/news/translate', { titles }),
  newsAnalyze: (items) => post(API_BASE, '/api/news/analyze', { items }),
}
