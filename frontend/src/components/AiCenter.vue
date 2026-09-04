<script setup>
import { ref, onMounted, nextTick } from 'vue'
import { api } from '../api/client'

// ---- 对话 ----
const messages = ref([])
const input = ref('')
const sending = ref(false)
const aiStatus = ref(null)
const chatBox = ref(null)
let currentChatAbort = null

// ---- 智能报价 ----
const quoteData = ref(null)
const quoteLoading = ref(false)
const quoteResult = ref('')

// ---- 财报解析 ----
const reportText = ref('')
const reportLoading = ref(false)
const reportResult = ref('')

// ---- 产业链 ----
const chainNode = ref('黄金')
const chainLoading = ref(false)
const chainResult = ref('')

const sugg = [
  '当前黄金ETF适合定投吗？',
  '分析一下黄金的产业链逻辑',
  '金价处于什么位置，风险如何？',
]

async function loadStatus() {
  try {
    const d = await api.aiStatus()
    aiStatus.value = d.data
  } catch (e) { aiStatus.value = { available: false, message: 'AI服务未连接' } }
}

function push(role, content) {
  messages.value.push({ role, content })
}

async function scrollChatToBottom() {
  await nextTick()
  if (chatBox.value) chatBox.value.scrollTop = chatBox.value.scrollHeight
}

function stopChat() {
  currentChatAbort?.abort()
}

async function sendChat() {
  const text = input.value.trim()
  if (!text || sending.value) return
  push('user', text)
  input.value = ''
  // 不把前端欢迎语发给模型；保留最近 20 条真实 user/assistant 上下文。
  const history = messages.value.slice(1).slice(-20).map(({ role, content }) => ({ role, content }))
  push('assistant', '')
  const assistantIndex = messages.value.length - 1
  sending.value = true
  currentChatAbort = new AbortController()
  await scrollChatToBottom()
  try {
    await api.aiChatStream(history, ({ event, data }) => {
      if (event === 'delta' && data?.content) {
        messages.value[assistantIndex].content += data.content
        scrollChatToBottom()
      } else if (event === 'error') {
        throw new Error(data?.message || 'AI 流式响应中断')
      }
    }, currentChatAbort.signal)
    if (!messages.value[assistantIndex].content) {
      messages.value[assistantIndex].content = '（无回复）'
    }
  } catch (e) {
    if (e?.name === 'AbortError') {
      if (!messages.value[assistantIndex].content) messages.value[assistantIndex].content = '（已停止）'
    } else {
      const prefix = messages.value[assistantIndex].content ? '\n\n' : ''
      messages.value[assistantIndex].content += `${prefix}⚠️ ${e?.message || e}`
    }
  } finally {
    currentChatAbort = null
    sending.value = false
    scrollChatToBottom()
  }
}

function useSuggestion(s) { input.value = s }

// 智能报价解读
async function runQuote() {
  quoteLoading.value = true; quoteResult.value = ''
  try {
    const q = await api.marketPrices()
    const rt = q.data?.gold_etf
    if (!rt) { quoteResult.value = '未获取到行情' ; return }
    quoteData.value = rt
    const d = await api.aiQuote(rt)
    quoteResult.value = d.data?.content || '（无回复）'
  } catch (e) { quoteResult.value = '⚠️ ' + e }
  finally { quoteLoading.value = false }
}

// 财报解析
async function runReport() {
  if (!reportText.value.trim()) return
  reportLoading.value = true; reportResult.value = ''
  try {
    const d = await api.aiFinancialReport(reportText.value.trim())
    reportResult.value = d.data?.content || '（无回复）'
  } catch (e) { reportResult.value = '⚠️ ' + e }
  finally { reportLoading.value = false }
}

// 产业链
async function runChain() {
  chainLoading.value = true; chainResult.value = ''
  try {
    const d = await api.aiChain(chainNode.value.trim())
    chainResult.value = d.data?.content || '（无回复）'
  } catch (e) { chainResult.value = '⚠️ ' + e }
  finally { chainLoading.value = false }
}

onMounted(() => {
  loadStatus()
  messages.value.push({ role: 'assistant', content: '你好！我是库里帕酱，贾维斯金融投研 AI 助手。可以问我金价走势、投资建议、财报分析等。' })
})
</script>

<template>
  <div class="ai">
    <!-- 状态 -->
    <div class="status-bar">
      <span class="dot" :class="aiStatus?.available ? 'ok' : 'bad'"></span>
      AI 引擎: {{ aiStatus?.provider || 'DeepSeek' }} · {{ aiStatus?.model || '...' }}
      <span class="hint" style="margin-left:auto">{{ aiStatus?.available ? '已连接' : aiStatus?.message }}</span>
    </div>

    <div class="grid">
      <!-- 对话 -->
      <div class="panel chat-panel">
        <div class="panel-head"><h2>AI 智能对话</h2></div>
        <div class="chat-window" ref="chatBox">
          <div v-for="(m, i) in messages" :key="i" class="chat-item" :class="m.role">
            <div class="role">{{ m.role === 'user' ? '你' : '库里帕酱' }}</div>
            <div class="bubble">{{ m.content }}</div>
          </div>
        </div>
        <div class="sugg">
          <button v-for="s in sugg" :key="s" class="chip" @click="useSuggestion(s)">{{ s }}</button>
        </div>
        <div class="chat-input">
          <input v-model="input" @keyup.enter="sendChat" placeholder="问金价、投资建议、财报…" :disabled="sending" />
          <button class="btn primary" @click="sending ? stopChat() : sendChat()">{{ sending ? '停止生成' : '发送' }}</button>
        </div>
      </div>

      <!-- 功能卡片 -->
      <div class="side">
        <!-- 智能报价 -->
        <div class="panel">
          <div class="panel-head"><h2>智能报价解读</h2></div>
          <div class="row">
            <button class="btn" @click="runQuote" :disabled="quoteLoading">{{ quoteLoading ? '分析中…' : '生成解读' }}</button>
          </div>
          <div v-if="quoteData" class="quote-mini">
            现价 <b>{{ quoteData.price }}</b> · 昨收 {{ quoteData.prev_close }}
            <span :class="quoteData.change >= 0 ? 'pos' : 'neg'">{{ quoteData.change }} ({{ quoteData.change_pct }}%)</span>
          </div>
          <div v-if="quoteResult" class="out">{{ quoteResult }}</div>
        </div>

        <!-- 财报解析 -->
        <div class="panel">
          <div class="panel-head"><h2>财报智能解析</h2></div>
          <textarea v-model="reportText" placeholder="粘贴财报内容或关键数据…" rows="4"></textarea>
          <button class="btn" @click="runReport" :disabled="reportLoading">{{ reportLoading ? '解析中…' : '解析财报' }}</button>
          <div v-if="reportResult" class="out">{{ reportResult }}</div>
        </div>

        <!-- 产业链 -->
        <div class="panel">
          <div class="panel-head"><h2>产业链挖掘</h2></div>
          <div class="row">
            <input v-model="chainNode" class="input" placeholder="输入产业链节点，如：黄金" />
            <button class="btn" @click="runChain" :disabled="chainLoading">{{ chainLoading ? '分析中…' : '分析' }}</button>
          </div>
          <div v-if="chainResult" class="out">{{ chainResult }}</div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.ai { display: flex; flex-direction: column; gap: 16px; }
.status-bar { display: flex; align-items: center; gap: 8px; color: #8ba0c8; font-size: 13px; padding: 10px 14px; background: #121a2d; border: 1px solid #243453; border-radius: 10px; }
.dot { width: 8px; height: 8px; border-radius: 50%; background: #ef5350; }
.dot.ok { background: #27c46b; box-shadow: 0 0 8px #27c46b; }
.grid { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; }
.panel { background: #121a2d; border: 1px solid #243453; border-radius: 12px; padding: 20px; }
.panel-head h2 { margin: 0 0 14px; font-size: 17px; color: #e9effb; }
.chat-panel { display: flex; flex-direction: column; }
.chat-window { flex: 1; height: 380px; overflow: auto; background: #0f1626; border: 1px solid #1a2540; border-radius: 8px; padding: 12px; }
.chat-item { margin-bottom: 12px; }
.chat-item.user .bubble { background: rgba(77,168,255,.15); border: 1px solid rgba(77,168,255,.3); }
.chat-item.assistant .bubble { background: rgba(255,255,255,.05); border: 1px solid #243453; }
.role { font-size: 11px; color: #8ba0c8; margin-bottom: 4px; }
.bubble { padding: 10px 14px; border-radius: 8px; font-size: 14px; line-height: 1.6; white-space: pre-wrap; color: #d9e6ff; }
.sugg { display: flex; gap: 8px; flex-wrap: wrap; margin: 10px 0; }
.chip { background: #152442; border: 1px solid #243453; color: #88c2ff; border-radius: 999px; padding: 5px 12px; font-size: 12px; cursor: pointer; }
.chat-input { display: flex; gap: 8px; }
.chat-input input, .input { flex: 1; background: #0f1626; border: 1px solid #243453; color: #e9effb; border-radius: 6px; padding: 10px 12px; }
textarea { width: 100%; background: #0f1626; border: 1px solid #243453; color: #e9effb; border-radius: 6px; padding: 10px; margin-bottom: 10px; resize: vertical; }
.btn { background: #1a2540; border: 1px solid #243453; color: #e9effb; border-radius: 6px; padding: 8px 16px; cursor: pointer; font-size: 13px; }
.btn.primary { background: linear-gradient(135deg, #4da8ff, #a842ff); border: none; color: #fff; }
.row { display: flex; gap: 8px; flex-wrap: wrap; }
.out { margin-top: 12px; background: rgba(8,16,34,.7); border: 1px dashed #38517f; border-radius: 8px; padding: 12px; white-space: pre-wrap; font-size: 13px; color: #d9e6ff; min-height: 40px; max-height: 260px; overflow: auto; }
.quote-mini { margin: 12px 0; font-size: 13px; color: #e9effb; }
.pos { color: #27c46b; } .neg { color: #ef5350; }
.side { display: flex; flex-direction: column; gap: 16px; }
@media (max-width: 900px) { .grid { grid-template-columns: 1fr; } }
</style>
