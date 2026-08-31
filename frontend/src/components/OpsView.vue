<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import { api } from '../api/client'

const java = ref(null)   // Java 数据存储
const py = ref(null)     // Python 行情+AI
const lastCheck = ref('')
let timer = null

async function check() {
  lastCheck.value = new Date().toLocaleTimeString('zh-CN')
  try { const d = await api.health(); java.value = d.data } catch (e) { java.value = { error: String(e) } }
  try { const d = await api.pyHealth(); py.value = d.data ?? d } catch (e) { py.value = { error: String(e) } }
}

function ok(v) { return !v || v.error ? 'bad' : 'ok' }

onMounted(() => { check(); timer = setInterval(check, 10000) })
onUnmounted(() => clearInterval(timer))
</script>

<template>
  <div class="ops">
    <div class="grid">
      <div class="card">
        <h2>Java 数据存储层</h2>
        <div class="status" :class="ok(java)">
          <span class="dot"></span>
          {{ java?.error ? '异常' : (java?.status === 'ok' ? '运行中' : '检查中') }}
        </div>
        <div class="small" v-if="java && !java.error">
          服务: {{ java.service }}<br/>
          时间: {{ java.time?.replace('T', ' ') }}
        </div>
        <div class="small bad" v-else-if="java?.error">{{ java.error }}</div>
        <a class="link" href="https://agent.shengxia.me/api/health" target="_blank" rel="noopener">探针: /api/health → Java</a>
      </div>

      <div class="card">
        <h2>Python 行情 + AI 层</h2>
        <div class="status" :class="ok(py)">
          <span class="dot"></span>
          {{ py?.error ? '异常' : (py?.status === 'ok' ? '运行中' : '检查中') }}
        </div>
        <div class="small" v-if="py && !py.error">
          服务: {{ py.service }}<br/>
          时间: {{ py.time?.replace('T', ' ') }}
        </div>
        <div class="small bad" v-else-if="py?.error">{{ py.error }}</div>
        <a class="link" href="https://agent.shengxia.me/py/api/health" target="_blank" rel="noopener">探针: /py/api/health → Python</a>
      </div>

      <div class="card">
        <h2>AI 引擎</h2>
        <a class="link" href="https://agent.shengxia.me/py/api/ai/capabilities" target="_blank" rel="noopener">探针: AI capabilities</a>
      </div>
    </div>

    <div class="card">
      <h2>最后检查时间</h2>
      <div class="value">{{ lastCheck || '--' }}</div>
      <div class="small">自动刷新：10 秒</div>
    </div>
  </div>
</template>

<style scoped>
.ops { display: flex; flex-direction: column; gap: 16px; }
.grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(260px, 1fr)); gap: 16px; }
.card { background: #121a2d; border: 1px solid #243453; border-radius: 12px; padding: 20px; }
.card h2 { margin: 0 0 14px; font-size: 16px; color: #e9effb; }
.status { display: inline-flex; align-items: center; gap: 8px; padding: 6px 14px; border-radius: 999px; border: 1px solid #243453; font-size: 13px; background: rgba(0,0,0,.2); }
.dot { width: 8px; height: 8px; border-radius: 50%; background: #8ba0c8; }
.status.ok .dot { background: #27c46b; box-shadow: 0 0 8px #27c46b; }
.status.bad .dot { background: #ef5350; }
.status.ok { color: #27c46b; }
.status.bad { color: #ef5350; }
.small { color: #8ba0c8; font-size: 12px; margin-top: 12px; line-height: 1.7; }
.bad { color: #ef5350; }
.link { color: #88c2ff; text-decoration: none; font-size: 13px; margin-top: 12px; display: inline-block; }
.value { font-size: 22px; font-weight: 600; color: #e9effb; }
</style>
