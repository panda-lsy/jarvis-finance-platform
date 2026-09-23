<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { api } from '../api/client'

const tasks = ref([])
const types = ref([])
const loading = ref(true)
const error = ref('')
const saving = ref(false)
const actionId = ref(null)
const editorOpen = ref(false)
const editingId = ref(null)
const historyTask = ref(null)
const runs = ref([])
const runsLoading = ref(false)
/**
 * 当前账号能否管理定时任务（后端 TASK_MANAGE）。
 *
 * 默认 **true**（放行）：这个值只用来"提前置灰"，真正的门禁在服务端。
 * 探测失败时若默认 false，会把本来能用的用户误挡在门外 —— 那比"点了才被拒"更糟。
 * 因此只有后端明确返回 `can_manage: false` 才置灰。
 */
const canManage = ref(true)

const form = reactive({
  name: '',
  taskType: 'MARKET_SCAN',
  cronExpr: '0 */30 * * * *',
  timezone: 'Asia/Shanghai',
  markets: ['gold_etf', 'london_gold'],
  changeThresholdPct: 1.5,
  warnBelowPct: 25,
  backtestMarket: 'gold_etf',
  shortMa: 5,
  longMa: 20,
  initialCash: 100000,
  limit: 120,
  asOf: '',
  digestLimit: 10,
  headlineCount: 3,
  analyzeNews: true,
})

const typeLabels = Object.freeze({
  MARKET_SCAN: '行情扫描',
  RISK_CHECK: '风险检测',
  BACKTEST: '策略回测',
  DAILY_DIGEST: '每日资讯日报',
})

const statusLabels = Object.freeze({
  ACTIVE: '运行中',
  PAUSED: '已暂停',
  DELETED: '已删除',
})

const runStatusLabels = Object.freeze({
  PENDING: '等待',
  RUNNING: '执行中',
  SUCCESS: '成功',
  FAILED: '失败',
  TIMEOUT: '超时',
  SKIPPED: '跳过',
})

const typeOptions = computed(() => types.value.map(item => ({
  ...item,
  label: typeLabels[item.type] || item.type,
})))

function taskTypeLabel(type) {
  return typeLabels[type] || type || '—'
}

function statusLabel(status) {
  return statusLabels[status] || status || '—'
}

function runStatusLabel(status) {
  return runStatusLabels[status] || status || '—'
}

function formatTime(value) {
  if (!value) return '—'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return String(value).replace('T', ' ').slice(0, 16)
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit',
  }).format(date)
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const [taskResponse, typeResponse, capabilityResponse] = await Promise.all([
      api.scheduledTasks('', 0, 100),
      api.scheduledTaskTypes(),
      api.scheduledTaskCapabilities(),
    ])
    if (taskResponse?.code !== 200) throw new Error(taskResponse?.message || '定时任务加载失败')
    tasks.value = Array.isArray(taskResponse?.data?.items) ? taskResponse.data.items : []
    types.value = Array.isArray(typeResponse?.data) ? typeResponse.data : []
    canManage.value = capabilityResponse?.data?.can_manage !== false
  } catch (e) {
    error.value = e?.message || '定时任务暂不可用'
  } finally {
    loading.value = false
  }
}

function resetForm() {
  editingId.value = null
  Object.assign(form, {
    name: '',
    taskType: typeOptions.value.find(item => item.supported)?.type || 'MARKET_SCAN',
    cronExpr: '0 */30 * * * *',
    timezone: 'Asia/Shanghai',
    markets: ['gold_etf', 'london_gold'],
    changeThresholdPct: 1.5,
    warnBelowPct: 25,
    backtestMarket: 'gold_etf',
    shortMa: 5,
    longMa: 20,
    initialCash: 100000,
    limit: 120,
    asOf: '',
    digestLimit: 10,
    headlineCount: 3,
    analyzeNews: true,
  })
}

function openCreate() {
  resetForm()
  editorOpen.value = true
}

function openEdit(task) {
  editingId.value = task.id
  const params = task.params && typeof task.params === 'object' ? task.params : {}
  Object.assign(form, {
    name: task.name || '',
    taskType: task.task_type || 'MARKET_SCAN',
    cronExpr: task.cron_expr || '0 */30 * * * *',
    timezone: task.timezone || 'Asia/Shanghai',
    markets: Array.isArray(params.markets) && params.markets.length ? [...params.markets] : ['gold_etf', 'london_gold'],
    changeThresholdPct: Number(params.changeThresholdPct ?? 1.5),
    warnBelowPct: Number(params.warnBelowPct ?? 25),
    backtestMarket: params.market || 'gold_etf',
    shortMa: Number(params.shortMa ?? 5),
    longMa: Number(params.longMa ?? 20),
    initialCash: Number(params.initialCash ?? 100000),
    limit: Number(params.limit ?? 120),
    asOf: params.asOf || '',
    digestLimit: Number(params.limit ?? 10),
    headlineCount: Number(params.headlineCount ?? 3),
    // 必须与执行器口径一致：DailyDigestExecutor 用 Boolean.TRUE.equals(analyze)，
    // 即**参数缺失时不跑 AI**。这里若写成 `!== false`，打开一个早期任务（params 里还没有
    // analyze）会显示成"已勾选"，用户没碰它就点保存 → 静默把 AI 分析从关变开、产生用量。
    // 该口径由 `e2e/tests/scheduled-tasks.spec.ts` 的「编辑旧日报任务时……」用例钉住
    // （改回 `!== false` 该用例会立刻变红）。
    analyzeNews: params.analyze === true,
  })
  editorOpen.value = true
}

function paramsForForm() {
  if (form.taskType === 'MARKET_SCAN') {
    return {
      markets: [...form.markets],
      changeThresholdPct: Number(form.changeThresholdPct),
    }
  }
  if (form.taskType === 'RISK_CHECK') {
    return { warnBelowPct: Number(form.warnBelowPct) }
  }
  if (form.taskType === 'BACKTEST') {
    return {
      market: form.backtestMarket,
      shortMa: Number(form.shortMa),
      longMa: Number(form.longMa),
      initialCash: Number(form.initialCash),
      limit: Number(form.limit),
      ...(form.asOf ? { asOf: form.asOf } : {}),
    }
  }
  if (form.taskType === 'DAILY_DIGEST') {
    return {
      limit: Number(form.digestLimit),
      headlineCount: Number(form.headlineCount),
      analyze: Boolean(form.analyzeNews),
    }
  }
  return {}
}

async function save() {
  if (saving.value) return
  saving.value = true
  error.value = ''
  const body = {
    name: form.name.trim(),
    taskType: form.taskType,
    cronExpr: form.cronExpr.trim(),
    timezone: form.timezone,
    params: paramsForForm(),
  }
  try {
    const response = editingId.value
      ? await api.updateScheduledTask(editingId.value, body)
      : await api.createScheduledTask(body)
    if (response?.code !== 200) throw new Error(response?.message || '任务保存失败')
    editorOpen.value = false
    await load()
  } catch (e) {
    error.value = e?.message || '任务保存失败'
  } finally {
    saving.value = false
  }
}

async function taskAction(task, action) {
  if (!task?.id || actionId.value) return
  actionId.value = task.id
  error.value = ''
  try {
    let response
    if (action === 'pause') response = await api.pauseScheduledTask(task.id)
    else if (action === 'resume') response = await api.resumeScheduledTask(task.id)
    else if (action === 'run') response = await api.runScheduledTask(task.id)
    else if (action === 'delete') response = await api.deleteScheduledTask(task.id)
    if (response?.code !== 200) throw new Error(response?.message || '操作失败')
    await load()
  } catch (e) {
    error.value = e?.message || '任务操作失败'
  } finally {
    actionId.value = null
  }
}

async function showRuns(task) {
  historyTask.value = task
  runsLoading.value = true
  runs.value = []
  try {
    const response = await api.scheduledTaskRuns(task.id, 0, 50)
    if (response?.code !== 200) throw new Error(response?.message || '执行历史加载失败')
    runs.value = Array.isArray(response?.data?.items) ? response.data.items : []
  } catch (e) {
    error.value = e?.message || '执行历史加载失败'
  } finally {
    runsLoading.value = false
  }
}

function toggleMarket(value) {
  const index = form.markets.indexOf(value)
  if (index >= 0) form.markets.splice(index, 1)
  else form.markets.push(value)
}

onMounted(load)
</script>

<template>
  <div class="scheduled-workspace">
    <header class="scheduled-head">
      <div>
        <span>SCHEDULED AUTOMATION</span>
        <h1 data-testid="system-task-heading">定时任务</h1>
        <p>把行情扫描、风险检测和回测按固定节奏自动执行，结果进入执行历史和站内通知。</p>
      </div>
      <div class="head-actions">
        <button type="button" data-testid="system-task-refresh-btn" @click="load">刷新</button>
        <button type="button" class="primary" data-testid="task-list-create-btn" :disabled="!canManage" @click="openCreate">新建任务</button>
      </div>
    </header>

    <p v-if="!canManage" class="task-notice">
      当前账号未开通「定时任务管理」权限：可以查看任务与执行历史、暂停或删除自己的任务，但新建、编辑、立即执行、恢复会被服务端拒绝，所以这里直接置灰。
    </p>

    <p v-if="error" class="task-error" data-testid="system-task-error">{{ error }}</p>

    <section class="task-summary" data-testid="system-task-summary">
      <div><span>任务总数</span><strong data-testid="system-task-summary-total">{{ tasks.length }}</strong></div>
      <div><span>运行中</span><strong data-testid="system-task-summary-active">{{ tasks.filter(item => item.status === 'ACTIVE').length }}</strong></div>
      <div><span>已暂停</span><strong data-testid="system-task-summary-paused">{{ tasks.filter(item => item.status === 'PAUSED').length }}</strong></div>
      <div><span>可用类型</span><strong data-testid="system-task-summary-types">{{ types.filter(item => item.supported).length }}</strong></div>
    </section>

    <section class="task-list-panel">
      <header><strong>自动化任务</strong><span>Spring 6 段 Cron · Asia/Shanghai</span></header>
      <p v-if="loading && !tasks.length" class="empty-state" data-testid="system-task-loading">正在加载定时任务…</p>
      <p v-else-if="!tasks.length" class="empty-state" data-testid="system-task-empty">暂无任务。创建第一条自动化任务后，执行结果会在这里持续留痕。</p>
      <div v-else class="task-table-wrap" data-testid="system-task-table-wrap">
        <table class="task-table" data-testid="system-task-table">
          <thead><tr><th>任务</th><th>类型</th><th>状态</th><th>Cron</th><th>下次运行</th><th>最近结果</th><th>操作</th></tr></thead>
          <tbody>
            <tr v-for="task in tasks" :key="task.id" data-testid="system-task-row">
              <td><div class="task-name"><strong>{{ task.name }}</strong><small>#{{ task.id }}</small></div></td>
              <td>{{ taskTypeLabel(task.task_type) }}</td>
              <td><span class="status-pill" :class="String(task.status || '').toLowerCase()">{{ statusLabel(task.status) }}</span></td>
              <td><code>{{ task.cron_expr }}</code></td>
              <td>{{ formatTime(task.next_run_at) }}</td>
              <td>
                <div class="last-run">
                  <span>{{ runStatusLabel(task.last_run_status) }}</span>
                  <small v-if="task.last_error">{{ task.last_error }}</small>
                </div>
              </td>
              <td>
                <div class="row-actions">
                  <button type="button" :disabled="!canManage" @click="openEdit(task)">编辑</button>
                  <button type="button" @click="showRuns(task)">历史</button>
                  <button type="button" :disabled="!canManage || actionId === task.id" @click="taskAction(task, 'run')">立即执行</button>
                  <button v-if="task.status === 'ACTIVE'" type="button" :disabled="actionId === task.id" @click="taskAction(task, 'pause')">暂停</button>
                  <button v-else type="button" :disabled="!canManage || actionId === task.id" @click="taskAction(task, 'resume')">恢复</button>
                  <button type="button" class="danger" :disabled="actionId === task.id" @click="taskAction(task, 'delete')">删除</button>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>

    <section v-if="historyTask" class="history-panel" data-testid="system-task-history-panel">
      <header>
        <div><strong>执行历史 · {{ historyTask.name }}</strong><span>最近 50 次</span></div>
        <button type="button" data-testid="system-task-history-close" @click="historyTask = null; runs = []">关闭</button>
      </header>
      <p v-if="runsLoading" class="empty-state" data-testid="system-task-history-loading">正在读取执行历史…</p>
      <p v-else-if="!runs.length" class="empty-state" data-testid="system-task-history-empty">暂无执行记录。</p>
      <div v-else class="run-list" data-testid="system-task-history-list">
        <article v-for="run in runs" :key="run.id" data-testid="system-task-history-item">
          <div class="run-top">
            <span class="status-pill" :class="String(run.status || '').toLowerCase()">{{ runStatusLabel(run.status) }}</span>
            <time>{{ formatTime(run.started_at || run.scheduled_at) }}</time>
            <small>{{ run.duration_ms == null ? '—' : run.duration_ms + ' ms' }}</small>
          </div>
          <p>{{ run.result_summary || run.error_message || '本次执行无摘要' }}</p>
        </article>
      </div>
    </section>

    <div v-if="editorOpen" class="editor-backdrop" data-testid="system-task-editor-backdrop" @click.self="editorOpen = false">
      <section class="task-editor" role="dialog" aria-modal="true" aria-label="定时任务编辑器" data-testid="system-task-editor">
        <header>
          <div><span>{{ editingId ? 'EDIT AUTOMATION' : 'NEW AUTOMATION' }}</span><strong>{{ editingId ? '编辑定时任务' : '新建定时任务' }}</strong></div>
          <button type="button" aria-label="关闭" data-testid="system-task-editor-close" @click="editorOpen = false">×</button>
        </header>

        <div class="editor-grid">
          <label class="field wide"><span>任务名称</span><input v-model="form.name" maxlength="80" placeholder="例如：工作日风险检查" data-testid="system-task-form-name" /></label>
          <label class="field"><span>任务类型</span>
            <select v-model="form.taskType" data-testid="system-task-form-type">
              <option v-for="item in typeOptions" :key="item.type" :value="item.type" :disabled="!item.supported">
                {{ item.label }}{{ item.supported ? '' : '（执行器未就绪）' }}
              </option>
            </select>
          </label>
          <label class="field"><span>时区</span><input v-model="form.timezone" readonly /></label>
          <label class="field wide"><span>Cron（秒 分 时 日 月 周）</span><input v-model="form.cronExpr" placeholder="0 */30 * * * *" data-testid="system-task-form-cron" /></label>
        </div>

        <div class="cron-presets" data-testid="system-task-cron-presets">
          <button type="button" @click="form.cronExpr = '0 */30 * * * *'">每30分钟</button>
          <button type="button" @click="form.cronExpr = '0 0 * * * *'">每小时</button>
          <button type="button" @click="form.cronExpr = '0 0 9 * * MON-FRI'">工作日09:00</button>
          <button type="button" @click="form.cronExpr = '0 0 15 * * MON-FRI'">工作日15:00</button>
        </div>

        <section class="type-params" data-testid="system-task-form-params">
          <template v-if="form.taskType === 'MARKET_SCAN'">
            <h3>行情扫描参数</h3>
            <div class="market-checks">
              <button type="button" :class="{ active: form.markets.includes('gold_etf') }" @click="toggleMarket('gold_etf')">黄金ETF</button>
              <button type="button" :class="{ active: form.markets.includes('london_gold') }" @click="toggleMarket('london_gold')">伦敦金</button>
            </div>
            <label class="field"><span>涨跌幅提醒阈值（%）</span><input v-model.number="form.changeThresholdPct" type="number" min="0" step="0.1" /></label>
          </template>

          <template v-else-if="form.taskType === 'RISK_CHECK'">
            <h3>风险检测参数</h3>
            <label class="field"><span>维持担保比例警戒线（%）</span><input v-model.number="form.warnBelowPct" type="number" min="0.1" max="100" step="0.1" /></label>
          </template>

          <template v-else-if="form.taskType === 'BACKTEST'">
            <h3>回测参数</h3>
            <div class="editor-grid">
              <label class="field"><span>市场</span><select v-model="form.backtestMarket"><option value="gold_etf">黄金ETF</option><option value="london_gold">伦敦金</option></select></label>
              <label class="field"><span>截止日（可选）</span><input v-model="form.asOf" type="date" /></label>
              <label class="field"><span>短均线</span><input v-model.number="form.shortMa" type="number" min="1" /></label>
              <label class="field"><span>长均线</span><input v-model.number="form.longMa" type="number" min="2" /></label>
              <label class="field"><span>初始资金</span><input v-model.number="form.initialCash" type="number" min="1" /></label>
              <label class="field"><span>样本根数</span><input v-model.number="form.limit" type="number" min="2" max="5000" /></label>
            </div>
          </template>

          <template v-else-if="form.taskType === 'DAILY_DIGEST'">
            <h3>资讯日报参数</h3>
            <div class="editor-grid">
              <label class="field"><span>保留资讯条数</span><input v-model.number="form.digestLimit" type="number" min="1" max="20" data-testid="system-task-form-digest-limit" /></label>
              <label class="field"><span>摘要标题数</span><input v-model.number="form.headlineCount" type="number" min="1" max="5" data-testid="system-task-form-digest-headlines" /></label>
            </div>
            <label class="check-field"><input v-model="form.analyzeNews" type="checkbox" data-testid="system-task-form-digest-analyze" /> <span>运行可审计的 AI 摘要、关键词、情绪和风险分析</span></label>
            <p class="field-hint">AI 分析失败时仍保留 RSS 原文和规则分析，不会阻断日报任务。</p>
          </template>

          <template v-else>
            <p>该任务类型当前没有可用执行器，因此不可创建。</p>
          </template>
        </section>

        <footer class="editor-actions">
          <button type="button" data-testid="system-task-form-cancel" @click="editorOpen = false">取消</button>
          <button type="button" class="primary" data-testid="system-task-form-save" :disabled="saving || !form.name.trim()" @click="save">{{ saving ? '保存中…' : '保存任务' }}</button>
        </footer>
      </section>
    </div>
  </div>
</template>

<style scoped>
.scheduled-workspace { min-width: 0; display: grid; gap: 14px; }
.scheduled-head { min-height: 70px; display: flex; align-items: flex-end; justify-content: space-between; gap: 20px; padding: 2px 2px 14px; border-bottom: 1px solid var(--line); }
.scheduled-head span { color: var(--subtle); font: 650 7px/1 ui-monospace, monospace; letter-spacing: .12em; }
.scheduled-head h1 { margin: 6px 0 0; color: var(--text); font-size: 24px; font-weight: 680; letter-spacing: -.035em; }
.scheduled-head p { margin: 7px 0 0; color: var(--muted); font-size: 10px; }
.head-actions, .row-actions, .editor-actions, .cron-presets, .market-checks { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
button { border: 1px solid var(--line); border-radius: 7px; background: transparent; color: var(--muted); cursor: pointer; }
button:hover:not(:disabled) { color: var(--text); border-color: var(--line-strong); background: var(--workspace-hover-bg); }
button:disabled { opacity: .45; cursor: default; }
.head-actions button { min-height: 32px; padding: 0 11px; font-size: 9px; }
button.primary { border-color: var(--workspace-action-border); background: var(--workspace-action-bg); color: var(--workspace-action-text); }
.task-error { margin: 0; padding: 9px 11px; border: 1px solid color-mix(in srgb, var(--bad) 35%, var(--line)); color: var(--bad); font-size: 9px; }
.task-notice { margin: 0; padding: 9px 11px; border: 1px solid color-mix(in srgb, var(--warn) 35%, var(--line)); color: var(--warn); font-size: 9px; line-height: 1.5; }
.task-summary { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 8px; }
.task-summary > div { min-height: 72px; display: grid; gap: 8px; align-content: center; padding: 11px 13px; border: 1px solid var(--line); border-radius: 9px; background: var(--workspace-panel-wash, var(--panel)); }
.task-summary span { color: var(--subtle); font-size: 8px; }
.task-summary strong { color: var(--text); font: 680 20px/1 ui-monospace, monospace; }
.task-list-panel, .history-panel { border: 1px solid var(--line); border-radius: 10px; background: var(--workspace-panel-wash, var(--panel)); overflow: hidden; }
.task-list-panel > header, .history-panel > header { min-height: 48px; display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 0 12px; border-bottom: 1px solid var(--line); }
.task-list-panel > header strong, .history-panel > header strong { color: var(--text); font-size: 10px; }
.task-list-panel > header span, .history-panel > header span { color: var(--subtle); font-size: 8px; }
.task-table-wrap { width: 100%; overflow-x: auto; }
.task-table { width: 100%; min-width: 1060px; border-collapse: collapse; font-size: 9px; }
.task-table th, .task-table td { padding: 10px 11px; border-bottom: 1px solid var(--line); color: var(--muted); text-align: left; vertical-align: middle; }
.task-table th { color: var(--subtle); font: 650 8px/1 ui-monospace, monospace; letter-spacing: .05em; }
.task-table tbody tr:last-child td { border-bottom: 0; }
.task-table tbody tr:hover td { background: var(--workspace-hover-bg); }
.task-name, .last-run { display: grid; gap: 4px; }
.task-name strong { color: var(--text); font-size: 10px; }
.task-name small, .last-run small { max-width: 220px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--subtle); font-size: 7px; }
.task-table code { color: var(--text); font: 600 8px/1 ui-monospace, monospace; }
.status-pill { display: inline-flex; align-items: center; min-height: 20px; padding: 0 7px; border: 1px solid var(--line); border-radius: 999px; color: var(--muted); font-size: 7px; }
.status-pill.active, .status-pill.success { color: var(--ok); border-color: color-mix(in srgb, var(--ok) 35%, var(--line)); }
.status-pill.failed, .status-pill.timeout { color: var(--bad); border-color: color-mix(in srgb, var(--bad) 35%, var(--line)); }
.status-pill.paused { color: var(--warn); border-color: color-mix(in srgb, var(--warn) 35%, var(--line)); }
.row-actions button { min-height: 26px; padding: 0 7px; font-size: 7px; }
.row-actions button.danger { color: var(--bad); }
.empty-state { margin: 0; padding: 28px 14px; color: var(--muted); font-size: 9px; }
.history-panel > header > div { display: flex; align-items: baseline; gap: 8px; }
.history-panel > header button { min-height: 26px; padding: 0 8px; font-size: 8px; }
.run-list { display: grid; }
.run-list article { padding: 11px 13px; border-bottom: 1px solid var(--line); }
.run-list article:last-child { border-bottom: 0; }
.run-top { display: flex; align-items: center; gap: 10px; color: var(--subtle); font-size: 8px; }
.run-top small { margin-left: auto; }
.run-list p { margin: 8px 0 0; color: var(--muted); font-size: 9px; line-height: 1.55; }
.editor-backdrop { position: fixed; z-index: 300; inset: 0; display: grid; place-items: center; padding: 18px; background: rgba(5,7,9,.58); backdrop-filter: blur(5px); }
.task-editor { width: min(720px, 100%); max-height: calc(100vh - 36px); overflow-y: auto; border: 1px solid var(--line-strong); border-radius: 14px; background: var(--material-elevated, var(--panel-raised)); box-shadow: 0 30px 90px rgba(0,0,0,.35); }
.task-editor > header { min-height: 60px; display: flex; align-items: center; justify-content: space-between; gap: 14px; padding: 0 16px; border-bottom: 1px solid var(--line); }
.task-editor > header > div { display: grid; gap: 5px; }
.task-editor > header span { color: var(--subtle); font: 650 7px/1 ui-monospace, monospace; letter-spacing: .1em; }
.task-editor > header strong { color: var(--text); font-size: 13px; }
.task-editor > header button { width: 30px; height: 30px; border: 0; font-size: 18px; }
.editor-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px; padding: 14px 16px 0; }
.field { display: grid; gap: 6px; }
.field.wide { grid-column: 1 / -1; }
.field span { color: var(--muted); font-size: 8px; }
.field input, .field select { width: 100%; min-height: 34px; box-sizing: border-box; border: 1px solid var(--line); border-radius: 7px; background: var(--surface); color: var(--text); padding: 0 9px; font-size: 9px; }
.cron-presets { padding: 10px 16px 0; }
.cron-presets button, .market-checks button { min-height: 28px; padding: 0 8px; font-size: 8px; }
.type-params { margin: 14px 16px 0; padding: 13px; border: 1px solid var(--line); border-radius: 9px; }
.type-params h3 { margin: 0 0 12px; color: var(--text); font-size: 10px; }
.type-params > .field { max-width: 320px; margin-top: 12px; }
.type-params .editor-grid { padding: 0; }
.check-field { display: flex; align-items: flex-start; gap: 8px; margin-top: 13px; color: var(--muted); font-size: 9px; line-height: 1.45; cursor: pointer; }
.check-field input { margin-top: 1px; accent-color: var(--accent); }
.field-hint { margin-top: 8px !important; color: var(--subtle) !important; }
.market-checks button.active { color: var(--accent-strong); border-color: var(--accent); background: var(--workspace-accent-wash); }
.type-params p { margin: 0; color: var(--muted); font-size: 9px; }
.editor-actions { justify-content: flex-end; padding: 14px 16px 16px; }
.editor-actions button { min-height: 32px; padding: 0 12px; font-size: 9px; }
@media (max-width: 760px) { .scheduled-head { align-items: flex-start; flex-direction: column; } .task-summary { grid-template-columns: repeat(2, minmax(0, 1fr)); } .editor-grid { grid-template-columns: 1fr; } .field.wide { grid-column: auto; } }
</style>
