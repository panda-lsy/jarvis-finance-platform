<script setup>
import { onMounted, ref } from 'vue'
import { API_BASE, api } from '../api/client'

const emit = defineEmits(['logged-in'])
const mode = ref('login')           // login | register | reset
const email = ref('')
const password = ref('')
const displayName = ref('')
const verificationCode = ref('')
const codeSent = ref(false)
const codeSending = ref(false)
const notice = ref('')
const loading = ref(false)
const error = ref('')
const showPwd = ref(false)

async function submit() {
  error.value = ''
  notice.value = ''
  if (!email.value || !password.value) {
    error.value = '请输入邮箱和密码'
    return
  }
  loading.value = true
  try {
    let res
    if (mode.value === 'reset') {
      if (!verificationCode.value.trim()) {
        error.value = '请先获取并填写密码重置验证码'
        return
      }
      res = await api.resetPassword(email.value, verificationCode.value.trim(), password.value)
      if (res.code === 200) {
        mode.value = 'login'
        password.value = ''
        verificationCode.value = ''
        codeSent.value = false
        notice.value = '密码已重置，请使用新密码登录'
      } else {
        error.value = res.message || '密码重置失败'
      }
      return
    }
    if (mode.value === 'register') {
      if (!verificationCode.value.trim()) {
        error.value = '请先获取并填写邮箱验证码'
        return
      }
      const verify = await api.confirmEmailVerification(email.value, verificationCode.value.trim())
      if (verify.code !== 200) {
        error.value = verify.message || '邮箱验证码验证失败'
        return
      }
      if (!displayName.value.trim()) displayName.value = email.value.split('@')[0]
      res = await api.register(email.value, password.value, displayName.value)
    } else {
      res = await api.login(email.value, password.value)
    }
    if (res.code === 200 && res.data?.user) {
      // 登录接口返回成功只代表凭据校验通过；必须再用 Cookie 会话访问 /me，
      // 确认浏览器已经真正持久化认证 Cookie，避免进入“假登录”工作台。
      const session = await api.me()
      if (session.code === 200 && session.data) {
        emit('logged-in', session.data)
      } else {
        error.value = '登录凭证未能建立，请刷新页面后重试'
      }
    } else {
      error.value = res.message || '操作失败'
    }
  } catch (e) {
    error.value = String(e)
  } finally {
    loading.value = false
  }
}

async function sendVerificationCode() {
  error.value = ''
  notice.value = ''
  if (!email.value) {
    error.value = '请先输入邮箱'
    return
  }
  codeSending.value = true
  try {
    const res = mode.value === 'reset'
      ? await api.requestPasswordReset(email.value)
      : await api.sendEmailVerification(email.value)
    if (res.code === 200) {
      codeSent.value = true
      notice.value = mode.value === 'reset'
        ? '如果该邮箱已注册，重置验证码会发送至邮箱（有效期 10 分钟）'
        : '验证码已发送，请检查邮箱（有效期 10 分钟）'
    } else {
      error.value = res.message || '验证码发送失败'
    }
  } catch (e) {
    error.value = String(e)
  } finally {
    codeSending.value = false
  }
}

function githubLogin() {
  window.location.href = `${API_BASE}/api/auth/github/authorize`
}

function setMode(nextMode) {
  mode.value = nextMode
  error.value = ''
  notice.value = ''
  password.value = ''
  verificationCode.value = ''
  codeSent.value = false
}

function switchMode() {
  setMode(mode.value === 'login' ? 'register' : 'login')
}

onMounted(() => {
  const params = new URLSearchParams(window.location.search)
  if (params.get('oauth') === 'error') error.value = 'GitHub 登录失败，请重试或使用邮箱登录'
  if (params.get('oauth') === 'bind-required') {
    error.value = '该 GitHub 邮箱已注册，请先使用邮箱登录，再点击顶部“绑定 GitHub”'
  }
})
</script>

<template>
  <div class="auth-page">
    <div class="auth-card">
      <div class="auth-brand">
        <img src="/favicon.svg" class="brand-icon" alt="Jarvis Finance" />
        <h1>贾维斯 · 金融投研</h1>
        <p class="sub">行情 · 策略 · 模拟交易</p>
      </div>

      <form @submit.prevent="submit" class="auth-form">
        <div v-if="mode === 'register'" class="field">
          <label for="auth-display-name">昵称</label>
          <input id="auth-display-name" v-model="displayName" type="text" placeholder="如何称呼你" />
        </div>
        <div class="field">
          <label for="auth-email">邮箱</label>
          <input id="auth-email" v-model="email" type="email" placeholder="you@example.com" autocomplete="email" />
        </div>
        <div class="field">
          <label for="auth-password">密码</label>
          <div class="pwd-row">
            <input id="auth-password" :type="showPwd ? 'text' : 'password'" v-model="password"
                   :placeholder="mode === 'login' ? '请输入密码' : (mode === 'reset' ? '设置至少10位新密码' : '至少10位')"
                   :autocomplete="mode === 'login' ? 'current-password' : 'new-password'" />
            <button type="button" class="eye" :aria-label="showPwd ? '隐藏密码' : '显示密码'" @click="showPwd = !showPwd">{{ showPwd ? '🙈' : '👁' }}</button>
          </div>
        </div>
        <div v-if="mode !== 'login'" class="field">
          <label for="auth-code">{{ mode === 'reset' ? '密码重置验证码' : '邮箱验证码' }}</label>
          <div class="code-row">
            <input id="auth-code" v-model="verificationCode" inputmode="numeric" maxlength="6" placeholder="6位验证码" />
            <button type="button" class="btn code-btn" :disabled="codeSending" @click="sendVerificationCode">
              {{ codeSending ? '发送中...' : (codeSent ? '重新发送' : '获取验证码') }}
            </button>
          </div>
        </div>

        <div v-if="error" class="error" role="alert">{{ error }}</div>
        <div v-if="notice" class="notice" role="status" aria-live="polite">{{ notice }}</div>

        <button type="submit" class="btn primary big" :disabled="loading">
          {{ loading ? '处理中...' : (mode === 'login' ? '登 录' : mode === 'reset' ? '重置密码' : '注册并开通模拟盘') }}
        </button>
      </form>

      <template v-if="mode !== 'reset'">
        <div class="oauth-divider"><span>或</span></div>
        <button type="button" class="btn github-btn" @click="githubLogin">使用 GitHub 登录 / 注册</button>

        <div class="switch">
          {{ mode === 'login' ? '还没有账号？' : '已有账号？' }}
          <button type="button" class="switch-link" @click="switchMode">{{ mode === 'login' ? '注册' : '去登录' }}</button>
        </div>
        <div v-if="mode === 'login'" class="switch reset-link">
          <button type="button" class="switch-link" @click="setMode('reset')">忘记密码？</button>
        </div>
      </template>
      <div v-else class="switch">
        已经想起密码？ <button type="button" class="switch-link" @click="setMode('login')">返回登录</button>
      </div>
      <div class="hint">{{ mode === 'reset' ? '重置成功后，已签发的旧登录凭证会立即失效' : '注册即自动开通 $100,000 模拟账户' }}</div>
    </div>
  </div>
</template>

<style scoped>
.auth-page {
  min-height: 100vh;
  display: flex; align-items: center; justify-content: center;
  padding: 20px;
  background: var(--bg);
}
.auth-card {
  width: 100%; max-width: 400px;
  background: var(--panel); border: 1px solid var(--line); border-radius: 8px;
  padding: 34px 32px;
}
.auth-brand { text-align: center; margin-bottom: 24px; }
.brand-icon {
  width: 48px; height: 48px; display: inline-block;
  object-fit: contain;
}
h1 { color: var(--text); font-size: 21px; font-weight: 650; letter-spacing: .01em; margin: 12px 0 4px; }
.sub { color: var(--muted); font-size: 12px; margin: 0; }
.auth-form { display: flex; flex-direction: column; gap: 14px; }
.field label { display: block; color: var(--muted); font-size: 12px; margin-bottom: 6px; }
.field input {
  width: 100%; background: var(--surface); border: 1px solid var(--line-strong); color: var(--text);
  border-radius: 4px; padding: 11px 12px; font-size: 14px;
}
.field input:focus { outline: none; border-color: #72684f; background: #141619; }
.pwd-row { position: relative; }
.code-row { display: flex; gap: 8px; }
.code-row input { flex: 1; min-width: 0; }
.code-btn { white-space: nowrap; padding: 10px 12px; font-size: 12px; }
.eye {
  position: absolute; right: 8px; top: 50%; transform: translateY(-50%);
  background: none; border: none; cursor: pointer; font-size: 16px;
}
.btn.primary.big { margin-top: 4px; padding: 12px; font-size: 15px; font-weight: 600; }
.error { color: #ef5350; font-size: 13px; }
.notice { color: #67d6a0; font-size: 13px; }
.oauth-divider { display: flex; align-items: center; gap: 10px; color: var(--subtle); font-size: 11px; margin: 20px 0 10px; }
.oauth-divider::before, .oauth-divider::after { content: ''; height: 1px; background: var(--line); flex: 1; }
.github-btn { width: 100%; padding: 11px; color: var(--text); border: 1px solid var(--line-strong); background: #1b1e21; }
.github-btn:hover { border-color: #555a60; background: #222529; }
.switch { text-align: center; color: var(--muted); font-size: 13px; margin-top: 18px; }
.switch-link { border: 0; background: transparent; color: var(--accent-strong); cursor: pointer; padding: 0; font-size: inherit; }
.hint { text-align: center; color: var(--subtle); font-size: 11px; margin-top: 8px; }
</style>
