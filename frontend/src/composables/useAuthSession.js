import { computed, ref } from 'vue'
import { AUTH_EXPIRED_EVENT, api } from '../api/client.js'

export function useAuthSession() {
  const user = ref(null)
  const restoring = ref(false)
  const sessionState = ref('unknown')
  const isLoggedIn = computed(() => Boolean(user.value))
  let restoreRequestId = 0

  function acceptLogin(value) {
    // 使正在进行的会话恢复请求失效，避免旧的 401 响应覆盖刚完成的登录。
    restoreRequestId += 1
    user.value = value || null
    sessionState.value = user.value ? 'authenticated' : 'anonymous'
  }

  function expireSession() {
    restoreRequestId += 1
    user.value = null
    sessionState.value = 'expired'
  }

  async function restore() {
    if (restoring.value) return user.value
    const requestId = ++restoreRequestId
    restoring.value = true
    sessionState.value = 'restoring'
    try {
      const response = await api.me()
      if (requestId !== restoreRequestId) return user.value
      if (response.code === 200 && response.data) {
        user.value = response.data
        sessionState.value = 'authenticated'
      } else if (response.httpStatus === 401 || response.code === 401) {
        user.value = null
        sessionState.value = 'anonymous'
      } else {
        // 403/5xx/网关异常不代表 Cookie 会话失效，保留现有用户视图。
        sessionState.value = 'degraded'
      }
    } catch (_) {
      if (requestId === restoreRequestId) sessionState.value = 'degraded'
    } finally {
      restoring.value = false
    }
    return user.value
  }

  async function logout() {
    // 登出也要使尚未返回的 restore() 结果失效。
    restoreRequestId += 1
    try {
      await api.logout()
    } catch (_) {
      // 网络异常不应阻止本地会话视图退出。
    }
    user.value = null
    sessionState.value = 'anonymous'
  }

  if (typeof window?.addEventListener === 'function') {
    window.addEventListener(AUTH_EXPIRED_EVENT, expireSession)
  }

  return {
    user,
    restoring,
    sessionState,
    isLoggedIn,
    acceptLogin,
    expireSession,
    restore,
    logout,
  }
}
