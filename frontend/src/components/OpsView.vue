<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import { api } from '../api/client'

const java = ref(null)   // Java + DB readiness
const db = ref(null)     // 数据库详细状态（登录后）
const py = ref(null)     // Python AI 服务（经 Java 代理检查）
const engine = ref(null) // AI provider/model
const lastCheck = ref('')
let timer = null

async function check() {
  lastCheck.value = new Date().toLocaleTimeString('zh-CN')
  try {
    const d = await api.healthReady()
    java.value = d.code === 200 && d.data?.status === 'ready' ? d.data : { error: d.message || 'Java/DB not ready' }
  } catch (e) { java.value = { error: String(e) } }
  try {
    const d = await api.databaseHealth()
    db.value = d.code === 200 ? d.data : { error: d.message || '数据库不可用' }
  } catch (e) { db.value = { error: String(e) } }
  try {
    const d = await api.aiServiceHealth()
    const value = d.data ?? d
    py.value = d.code === 200 || d.status === 'ready' ? value : { error: d.message || 'AI服务不可用' }
  } catch (e) { py.value = { error: String(e) } }
  try {
    const d = await api.aiStatus()
    engine.value = d.data?.available ? d.data : { error: d.data?.message || d.message || 'AI引擎不可用' }
  } catch (e) { engine.value = { error: String(e) } }
}

function ok(v) { return !v || v.error ? 'bad' : 'ok' }

onMounted(() => { check(); timer = setInterval(check, 10000) })
onUnmounted(() => clearInterval(timer))
</script>

<template>
  <div class="ops">
    <div class="grid">
      <div class="card">
        <h2>Java 主后端 / Readiness</h2>
        <div class="status" :class="ok(java)">
          <span class="dot"></span>
          {{ java?.error ? '异常' : (java?.status === 'ready' ? 'Ready' : '检查中') }}
        </div>
        <div class="small" v-if="java && !java.error">
          服务: {{ java.service }}<br/>
          DB: {{ java.database?.status }} · {{ java.database?.latency_ms ?? '-' }} ms<br/>
          时间: {{ java.time?.replace('T', ' ') }}
        </div>
        <div class="small bad" v-else-if="java?.error">{{ java.error }}</div>
        <a class="link" href="https://agent.shengxia.me/api/health/ready" target="_blank" rel="noopener">探针: /api/health/ready → Java + DB</a>
      </div>

      <div class="card">
        <h2>PostgreSQL</h2>
        <div class="status" :class="ok(db)">
          <span class="dot"></span>
          {{ db?.error ? '异常' : (db?.status === 'up' ? '可查询' : '检查中') }}
        </div>
        <div class="small" v-if="db && !db.error">
          产品: {{ db.product || '-' }}<br/>
          SELECT 1: {{ db.latency_ms ?? '-' }} ms
        </div>
        <div class="small bad" v-else-if="db?.error">{{ db.error }}</div>
      </div>

      <div class="card">
        <h2>Python AI 层</h2>
        <div class="status" :class="ok(py)">
          <span class="dot"></span>
          {{ py?.error ? '异常' : (py?.status === 'ok' ? '运行中' : '检查中') }}
        </div>
        <div class="small" v-if="py && !py.error">
          服务: {{ py.service }}<br/>
          时间: {{ py.time?.replace('T', ' ') }}
        </div>
        <div class="small bad" v-else-if="py?.error">{{ py.error }}</div>
        <a class="link" href="https://agent.shengxia.me/api/health/ai" target="_blank" rel="noopener">探针: /api/health/ai → Java → Python</a>
      </div>

      <div class="card">
        <h2>AI 引擎</h2>
        <div class="status" :class="ok(engine)">
          <span class="dot"></span>
          {{ engine?.error ? '异常' : '已配置' }}
        </div>
        <div class="small" v-if="engine && !engine.error">
          Provider: {{ engine.provider }}<br/>
          Model: {{ engine.model }}
        </div>
        <div class="small bad" v-else-if="engine?.error">{{ engine.error }}</div>
        <a class="link" href="https://agent.shengxia.me/api/ai/capabilities" target="_blank" rel="noopener">探针: /api/ai/capabilities → Java → Python</a>
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
