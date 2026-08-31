<script setup>
import { ref } from 'vue'
import { api, auth } from '../api/client'

const emit = defineEmits(['logged-in'])
const mode = ref('login')           // login | register
const email = ref('')
const password = ref('')
const displayName = ref('')
const loading = ref(false)
const error = ref('')
const showPwd = ref(false)

async function submit() {
  error.value = ''
  if (!email.value || !password.value) {
    error.value = '请输入邮箱和密码'
    return
  }
  loading.value = true
  try {
    let res
    if (mode.value === 'register') {
      if (!displayName.value.trim()) displayName.value = email.value.split('@')[0]
      res = await api.register(email.value, password.value, displayName.value)
    } else {
      res = await api.login(email.value, password.value)
    }
    if (res.code === 200 && res.data && res.data.token) {
      auth.token = res.data.token
      emit('logged-in', res.data.user)
    } else {
      error.value = res.message || '操作失败'
    }
  } catch (e) {
    error.value = String(e)
  } finally {
    loading.value = false
  }
}

function switchMode() {
  mode.value = mode.value === 'login' ? 'register' : 'login'
  error.value = ''
}
</script>

<template>
  <div class="auth-page">
    <div class="auth-card">
      <div class="auth-brand">
        <span class="brand-icon"></span>
        <h1>贾维斯 · 金融投研</h1>
        <p class="sub">DeepSeek 驱动 · 模拟盘交易</p>
      </div>

      <form @submit.prevent="submit" class="auth-form">
        <div v-if="mode === 'register'" class="field">
          <label>昵称</label>
          <input v-model="displayName" type="text" placeholder="如何称呼你" />
        </div>
        <div class="field">
          <label>邮箱</label>
          <input v-model="email" type="email" placeholder="you@example.com" autocomplete="email" />
        </div>
        <div class="field">
          <label>密码</label>
          <div class="pwd-row">
            <input :type="showPwd ? 'text' : 'password'" v-model="password"
                   placeholder="至少6位" autocomplete="current-password" />
            <button type="button" class="eye" @click="showPwd = !showPwd">{{ showPwd ? '🙈' : '👁' }}</button>
          </div>
        </div>

        <div v-if="error" class="error">{{ error }}</div>

        <button type="submit" class="btn primary big" :disabled="loading">
          {{ loading ? '处理中...' : (mode === 'login' ? '登 录' : '注册并开通模拟盘') }}
        </button>
      </form>

      <div class="switch">
        {{ mode === 'login' ? '还没有账号？' : '已有账号？' }}
        <a @click="switchMode">{{ mode === 'login' ? '注册' : '去登录' }}</a>
      </div>
      <div class="hint">注册即自动开通 $100,000 模拟账户</div>
    </div>
  </div>
</template>

<style scoped>
.auth-page {
  min-height: 100vh;
  display: flex; align-items: center; justify-content: center;
  padding: 20px;
  background:
    radial-gradient(900px 450px at -10% 0%, #213662 0%, transparent 60%),
    radial-gradient(800px 500px at 120% 20%, #1b3558 0%, transparent 60%),
    #0b1020;
}
.auth-card {
  width: 100%; max-width: 400px;
  background: #121a2d; border: 1px solid #243453; border-radius: 16px;
  padding: 36px 32px;
}
.auth-brand { text-align: center; margin-bottom: 24px; }
.brand-icon {
  width: 42px; height: 42px; border-radius: 10px; display: inline-block;
  background: linear-gradient(135deg, #4da8ff, #17d5c2);
  box-shadow: 0 0 24px rgba(54,174,255,.5);
}
h1 { color: #e9effb; font-size: 22px; margin: 12px 0 4px; }
.sub { color: #8ba0c8; font-size: 13px; margin: 0; }
.auth-form { display: flex; flex-direction: column; gap: 14px; }
.field label { display: block; color: #8ba0c8; font-size: 13px; margin-bottom: 6px; }
.field input {
  width: 100%; background: #0f1626; border: 1px solid #243453; color: #e9effb;
  border-radius: 8px; padding: 11px 12px; font-size: 14px;
}
.field input:focus { outline: none; border-color: #4da8ff; }
.pwd-row { position: relative; }
.eye {
  position: absolute; right: 8px; top: 50%; transform: translateY(-50%);
  background: none; border: none; cursor: pointer; font-size: 16px;
}
.btn.primary.big { margin-top: 4px; padding: 12px; font-size: 15px; font-weight: 600; }
.error { color: #ef5350; font-size: 13px; }
.switch { text-align: center; color: #8ba0c8; font-size: 14px; margin-top: 18px; }
.switch a { color: #4da8ff; cursor: pointer; text-decoration: underline; }
.hint { text-align: center; color: #5a6b8c; font-size: 12px; margin-top: 8px; }
</style>
