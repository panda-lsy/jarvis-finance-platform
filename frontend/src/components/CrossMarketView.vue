<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { api } from '../api/client'
import InstrumentList from './market/InstrumentList.vue'
import DataState from './common/DataState.vue'
import QuoteDetail from './market/QuoteDetail.vue'
import TechnicalSummary from './market/TechnicalSummary.vue'
import ResearchRail from './market/ResearchRail.vue'
import { getMultiMarketChartOptions, useMarketChart } from '../composables/useMarketChart'
import { usePolling } from '../composables/usePolling'
import { useLatestRequest } from '../composables/useLatestRequest'
import { useFreshness } from '../composables/useFreshness'
import { formatNumber, formatPercent } from '../utils/formatters'
import {
  hasMarketPreferences,
  marketPreferencesKey,
  normalizeMarketPreferences,
  readMarketPreferences,
  writeMarketPreferences,
} from '../utils/marketPreferences'

const props = defineProps({
  user: { type: Object, default: null },
  active: { type: Boolean, default: true },
})
const emit = defineEmits(['context-change', 'ready'])

const market = ref('a_share')
const selectedSymbol = ref('')
const instruments = ref([])
const watchlist = ref([])
const hiddenDefaultKeys = ref([])
const session = ref(null)
const quote = ref(null)
const kline = ref([])
const range = ref(null)
const technicalAnalysis = ref(null)
const interval = ref('1d')
const loading = ref(false)
const error = ref('')
const analysis = ref('')
const analysisLoading = ref(false)
const customQuery = ref('')
const resolveLoading = ref(false)
const resolveError = ref('')
const preferenceSaving = ref(false)
const preferenceError = ref('')
const editingInstrument = ref(null)
const editSymbol = ref('')
const editName = ref('')
const editLoading = ref(false)
const editError = ref('')
const latestDataRequest = useLatestRequest()
const freshness = useFreshness(90000)
const marketChart = useMarketChart()
const chartRef = marketChart.elementRef
let lastDailyRefreshAt = 0
let preferenceSaveChain = Promise.resolve()
let preferenceSaveVersion = 0
let bootstrapping = true
let readyEmitted = false
// 报价与 K 线解耦：报价 1 秒刷新，K 线/交易时段维持低频刷新，避免每秒请求重型历史接口。
const quotePolling = usePolling(async () => {
  const shouldWatch = market.value === 'crypto' || session.value?.is_open
  if (shouldWatch) await loadQuoteOnly()
}, 1000)
const maintenancePolling = usePolling(async () => {
  await loadSession()
  if (interval.value === '1d') {
    if (Date.now() - lastDailyRefreshAt < 30000) return
    lastDailyRefreshAt = Date.now()
    if (!loading.value) await loadData()
    return
  }
  const shouldWatch = market.value === 'crypto' || session.value?.is_open
  if (!loading.value && shouldWatch) await loadData()
}, 15000)

const marketOptions = [
  { value: 'a_share', label: 'A股' },
  { value: 'us_stock', label: '美股' },
  { value: 'crypto', label: '加密货币' },
]

const marketIntervals = computed(() => [
  { value: '1d', label: '日K' },
  { value: '5m', label: '5分钟' },
  { value: '10m', label: '10分钟' },
  { value: '15m', label: '15分钟' },
  { value: '30m', label: '30分钟' },
  { value: '1h', label: '1小时' },
])

const currentInstruments = computed(() => instruments.value.filter(i => i.market === market.value))
const currentWatchlist = computed(() => watchlist.value.filter(i => i.market === market.value))
const watchlistKeys = computed(() => new Set(currentWatchlist.value.map(instrumentKey)))
const currentDefaults = computed(() => currentInstruments.value.filter(item =>
  !hiddenDefaultKeys.value.includes(instrumentKey(item)) && !watchlistKeys.value.has(instrumentKey(item))))
const displayedInstruments = computed(() => [...currentWatchlist.value, ...currentDefaults.value])
const hiddenDefaultCount = computed(() => currentInstruments.value
  .filter(item => hiddenDefaultKeys.value.includes(instrumentKey(item))).length)
const currentInstrument = computed(() => displayedInstruments.value.find(i => i.symbol === selectedSymbol.value))
const currentMarketLabel = computed(() => marketOptions.find(item => item.value === market.value)?.label || '市场')
const currentIntervalLabel = computed(() => marketIntervals.value.find(item => item.value === interval.value)?.label || interval.value)
const chartState = computed(() => {
  if (loading.value && !kline.value.length) return 'loading'
  if (error.value && !kline.value.length) return 'error'
  if (!loading.value && selectedSymbol.value && !kline.value.length) return 'empty'
  return ''
})

const fmt = value => formatNumber(value, 2, 4)
const fmtPct = value => formatPercent(value)

function instrumentKey(item) {
  return `${item.market}:${item.symbol}`
}

function applyPreferences(preferences) {
  watchlist.value = preferences.watchlist
  hiddenDefaultKeys.value = preferences.hiddenDefaultKeys
}

function currentPreferences() {
  return {
    watchlist: watchlist.value,
    hiddenDefaultKeys: hiddenDefaultKeys.value,
  }
}

function persistLocalPreferences(preferences = currentPreferences()) {
  if (typeof window === 'undefined') return false
  return writeMarketPreferences(window.localStorage, marketPreferencesKey(props.user), preferences)
}

async function persistPreferences() {
  const preferences = normalizeMarketPreferences(currentPreferences())
  if (!persistLocalPreferences(preferences)) {
    preferenceError.value = '浏览器本地存储不可用，无法保留偏好'
  }
  const version = ++preferenceSaveVersion
  preferenceSaving.value = true
  preferenceSaveChain = preferenceSaveChain
    .catch(() => {})
    .then(async () => {
      const response = await api.saveMarketPreferences(preferences)
      if (response.code !== 200) throw new Error(response.message || '标的偏好保存失败')
      if (version === preferenceSaveVersion) preferenceError.value = ''
    })
    .catch(error => {
      if (version === preferenceSaveVersion) preferenceError.value = error?.message || '标的偏好未同步'
    })
  await preferenceSaveChain
  if (version === preferenceSaveVersion) preferenceSaving.value = false
}

async function loadPreferences() {
  if (typeof window === 'undefined') return
  const localPreferences = readMarketPreferences(window.localStorage, marketPreferencesKey(props.user))
  applyPreferences(localPreferences)
  try {
    const response = await api.marketPreferences()
    if (response.code !== 200 || !response.data) return
    const serverPreferences = normalizeMarketPreferences(response.data)
    if (response.data.persisted) {
      applyPreferences(serverPreferences)
      persistLocalPreferences(serverPreferences)
      return
    }
    // 只把旧浏览器数据迁移到后端一次；后端已有空记录时不再用旧数据覆盖删除操作。
    if (hasMarketPreferences(localPreferences)) await persistPreferences()
  } catch (_) {
    // 后端暂时不可用时保留本地数据，待用户下一次操作时继续同步。
  }
}

async function loadInstruments() {
  const response = await api.marketInstruments()
  if (response.code !== 200 || !Array.isArray(response.data)) {
    throw new Error(response.message || '标的列表加载失败')
  }
  instruments.value = response.data
  const availableKeys = new Set(response.data.map(instrumentKey))
  const cleanedHiddenKeys = hiddenDefaultKeys.value.filter(key => availableKeys.has(key))
  if (cleanedHiddenKeys.length !== hiddenDefaultKeys.value.length) {
    hiddenDefaultKeys.value = cleanedHiddenKeys
    await persistPreferences()
  }
  chooseDefaultSymbol()
}

async function loadSession() {
  try {
    const response = await api.marketSession(market.value)
    if (response.code !== 200 || !response.data) throw new Error(response.message || '交易状态加载失败')
    session.value = response.data
  } catch (e) {
    session.value = null
  }
}

async function resolveCustomInstrument() {
  const query = customQuery.value.trim()
  if (!query || resolveLoading.value) return
  resolveLoading.value = true
  resolveError.value = ''
  try {
    const response = await api.resolveMarketInstrument(market.value, query)
    if (response.code !== 200 || !response.data?.symbol) {
      throw new Error(response.message || '标的解析失败')
    }
    const item = response.data
    const existingIndex = watchlist.value.findIndex(candidate => instrumentKey(candidate) === instrumentKey(item))
    if (existingIndex >= 0) watchlist.value.splice(existingIndex, 1, item)
    else watchlist.value.push(item)
    await persistPreferences()
    selectInstrument(item.symbol)
    customQuery.value = ''
  } catch (e) {
    resolveError.value = e?.message || String(e)
  } finally {
    resolveLoading.value = false
  }
}

async function addToWatchlist(item) {
  if (!item) return
  const existingIndex = watchlist.value.findIndex(candidate => instrumentKey(candidate) === instrumentKey(item))
  if (existingIndex >= 0) watchlist.value.splice(existingIndex, 1, item)
  else watchlist.value.push(item)
  await persistPreferences()
  selectInstrument(item.symbol)
}

async function removeFromWatchlist(item) {
  if (!item) return
  watchlist.value = watchlist.value.filter(candidate => instrumentKey(candidate) !== instrumentKey(item))
  await persistPreferences()
  chooseDefaultSymbol()
}

async function removeDefault(item) {
  if (!item) return
  const key = instrumentKey(item)
  if (!hiddenDefaultKeys.value.includes(key)) hiddenDefaultKeys.value.push(key)
  await persistPreferences()
  chooseDefaultSymbol()
}

async function restoreDefaults() {
  const marketPrefix = `${market.value}:`
  hiddenDefaultKeys.value = hiddenDefaultKeys.value.filter(key => !key.startsWith(marketPrefix))
  await persistPreferences()
  chooseDefaultSymbol()
}

function resetSelectedData() {
  quote.value = null
  kline.value = []
  range.value = null
  technicalAnalysis.value = null
  error.value = ''
}

function selectInstrument(symbol) {
  if (selectedSymbol.value === symbol) return
  selectedSymbol.value = symbol
  resetSelectedData()
}

function chooseDefaultSymbol() {
  const available = displayedInstruments.value
  const nextSymbol = available.some(i => i.symbol === selectedSymbol.value)
    ? selectedSymbol.value
    : available[0]?.symbol || ''
  if (nextSymbol !== selectedSymbol.value) {
    selectedSymbol.value = nextSymbol
    resetSelectedData()
  }
  if (!marketIntervals.value.some(i => i.value === interval.value)) {
    interval.value = marketIntervals.value[0].value
  }
}

function startEditInstrument(item) {
  editingInstrument.value = item
  editSymbol.value = item?.symbol || ''
  editName.value = item?.name || ''
  editError.value = ''
}

function cancelEditInstrument() {
  editingInstrument.value = null
  editSymbol.value = ''
  editName.value = ''
  editError.value = ''
}

async function saveEditedInstrument() {
  const current = editingInstrument.value
  const query = editSymbol.value.trim()
  if (!current || !query || editLoading.value) return
  editLoading.value = true
  editError.value = ''
  try {
    const response = await api.resolveMarketInstrument(current.market, query)
    if (response.code !== 200 || !response.data?.symbol) {
      throw new Error(response.message || '标的解析失败')
    }
    const resolved = response.data
    const next = {
      ...resolved,
      name: editName.value.trim() || resolved.name,
    }
    const previousKey = instrumentKey(current)
    const nextKey = instrumentKey(next)
    if (watchlist.value.some(item => instrumentKey(item) === nextKey && instrumentKey(item) !== previousKey)) {
      throw new Error('该标的已在自选列表中')
    }
    const index = watchlist.value.findIndex(item => instrumentKey(item) === previousKey)
    if (index < 0) throw new Error('自选标的已不存在，请刷新列表')
    watchlist.value.splice(index, 1, next)
    await persistPreferences()
    cancelEditInstrument()
    selectInstrument(next.symbol)
  } catch (e) {
    editError.value = e?.message || String(e)
  } finally {
    editLoading.value = false
  }
}

async function loadQuoteOnly() {
  if (!selectedSymbol.value) return
  const requestMarket = market.value
  const requestSymbol = selectedSymbol.value
  try {
    const response = await api.marketAssetQuote(requestMarket, requestSymbol)
    if (market.value !== requestMarket || selectedSymbol.value !== requestSymbol) return
    if (response.code !== 200) return
    quote.value = response.data
    freshness.touch()
  } catch (_) {
    // 秒级报价失败时保留最后有效值；15s maintenance 仍会继续尝试完整刷新。
  }
}

async function loadData() {
  if (!selectedSymbol.value) return
  const requestVersion = latestDataRequest.begin()
  const requestMarket = market.value
  const requestSymbol = selectedSymbol.value
  const requestInterval = interval.value
  loading.value = true
  error.value = ''
  try {
    const [quoteResponse, klineResponse] = await Promise.all([
      api.marketAssetQuote(requestMarket, requestSymbol),
      api.marketAssetKline(requestMarket, requestSymbol, requestInterval, 120),
    ])
    if (!latestDataRequest.isLatest(requestVersion)) return
    if (quoteResponse.code !== 200) throw new Error(quoteResponse.message || '报价加载失败')
    if (klineResponse.code !== 200) throw new Error(klineResponse.message || 'K线加载失败')
    quote.value = quoteResponse.data
    kline.value = klineResponse.data?.data || []
    freshness.touch()
    if (requestInterval === '1d') lastDailyRefreshAt = Date.now()
    range.value = klineResponse.data?.range || null
    technicalAnalysis.value = klineResponse.data?.analysis || null
    await nextTick()
    if (latestDataRequest.isLatest(requestVersion)) await renderChart()
  } catch (e) {
    if (latestDataRequest.isLatest(requestVersion)) error.value = e?.message || String(e)
  } finally {
    if (latestDataRequest.isLatest(requestVersion)) loading.value = false
  }
}

async function renderChart() {
  await marketChart.renderCandles(kline.value, getMultiMarketChartOptions())
}

function resizeChart() {
  marketChart.resize()
}

defineExpose({ resizeChart })

async function runAnalysis() {
  if (!quote.value || analysisLoading.value) return
  analysisLoading.value = true
  analysis.value = ''
  try {
    const response = await api.aiQuote({
      ...quote.value,
      analysis_market: market.value,
      technical_analysis: technicalAnalysis.value,
    })
    if (response.code !== 200) throw new Error(response.message || '分析失败')
    analysis.value = response.data?.content || '（暂无研究结论）'
  } catch (e) {
    analysis.value = `⚠️ ${e?.message || e}`
  } finally {
    analysisLoading.value = false
  }
}

async function refresh() {
  try {
    if (!instruments.value.length) await loadInstruments()
    await loadSession()
    await loadData()
  } catch (e) {
    error.value = e?.message || String(e)
  }
}

watch(market, async () => {
  resetSelectedData()
  chooseDefaultSymbol()
  analysis.value = ''
  technicalAnalysis.value = null
  if (bootstrapping) return
  await loadSession()
  loadData()
})

watch(currentInstrument, item => {
  if (!item) return
  emit('context-change', {
    market: item.market,
    symbol: item.symbol,
    name: item.name || item.symbol,
    sourceModule: 'cross-market',
  })
}, { immediate: true })
watch([selectedSymbol, interval], () => {
  analysis.value = ''
  resetSelectedData()
  if (bootstrapping) return
  loadData()
})

onMounted(async () => {
  try {
    await marketChart.prepare()
    await loadPreferences()
    await refresh()
  } finally {
    bootstrapping = false
    if (props.active) {
      quotePolling.start()
      maintenancePolling.start()
    }
    await nextTick()
    requestAnimationFrame(() => {
      requestAnimationFrame(() => {
        marketChart.resize()
        if (!readyEmitted) {
          readyEmitted = true
          emit('ready')
        }
      })
    })
  }
})

watch(() => props.active, active => {
  if (bootstrapping) return
  if (active) {
    quotePolling.start()
    maintenancePolling.start()
    requestAnimationFrame(() => marketChart.resize())
  } else {
    quotePolling.stop()
    maintenancePolling.stop()
  }
})

onBeforeUnmount(() => {
  quotePolling.stop()
  maintenancePolling.stop()
})
</script>

<template>
  <div class="cross-market">
    <div class="cross-heading">
      <div>
        <h1>CROSS MARKET / OBSERVATORY</h1>
        <span>{{ currentInstrument?.symbol || 'NO SYMBOL' }} · {{ currentInstrument?.name || '选择研究对象' }} · 跨市场行情、技术结构与 AI 研究联动</span>
      </div>
      <strong>{{ currentMarketLabel }} / {{ currentIntervalLabel }}</strong>
    </div>
    <div class="cross-toolbar">
      <div class="market-switch" role="tablist" aria-label="市场切换">
        <button v-for="item in marketOptions" :key="item.value" type="button" class="market-tab"
                role="tab" :aria-selected="market === item.value"
                :class="{ active: market === item.value }" @click="market = item.value">
          {{ item.label }}
        </button>
      </div>
      <div class="toolbar-right">
        <div class="period-switch" role="group" aria-label="K线周期">
          <button v-for="item in marketIntervals" :key="item.value" type="button" class="period-btn"
                  :aria-pressed="interval === item.value"
                  :class="{ active: interval === item.value }" @click="interval = item.value">
            {{ item.label }}
          </button>
        </div>
        <span class="market-status" :class="{ open: session?.is_open }">
          <i></i>{{ session?.label || '交易状态加载中' }}
        </span>
        <span class="refresh-note" :class="{ stale: freshness.stale }">
          {{ freshness.stale ? '数据可能陈旧' : interval === '1d' ? `30s 自动刷新 · ${freshness.label}` : session?.is_open || market === 'crypto' ? `盯盘中 · 15s刷新 · ${freshness.label}` : '非交易时段 · 手动刷新' }}
        </span>
        <button type="button" class="btn" @click="refresh" :disabled="loading">{{ loading ? '加载中…' : '刷新' }}</button>
      </div>
    </div>

    <div v-if="error && kline.length" class="error">{{ error }}</div>

    <div class="symbol-parser">
      <span class="parser-label">INSTRUMENT / 自定义标的</span>
      <input v-model="customQuery" class="parser-input" :placeholder="market === 'a_share' ? '输入 600519 / SH600519' : market === 'us_stock' ? '输入 AAPL / BRK.B' : '输入 BTC / BTCUSDT'" @keyup.enter="resolveCustomInstrument" />
      <button type="button" class="btn parser-btn" :disabled="resolveLoading || !customQuery.trim()" @click="resolveCustomInstrument">
        {{ resolveLoading ? 'RESOLVING…' : 'RESOLVE + LOAD' }}
      </button>
      <span class="parser-hint">仅校验代码格式，不会保存密钥或任意外部地址</span>
    </div>
    <div class="preference-status" aria-live="polite">
      <span v-if="preferenceSaving">标的偏好保存中…</span>
      <span v-else-if="preferenceError" class="parser-error">{{ preferenceError }}</span>
    </div>
    <div v-if="resolveError" class="parser-error">{{ resolveError }}</div>

    <div v-if="editingInstrument" class="instrument-editor" role="dialog" aria-label="修改自选标的">
      <div class="editor-title">
        <b>修改自选标的</b>
        <span>{{ currentMarketLabel }} · 重新解析代码后保存</span>
      </div>
      <input v-model="editSymbol" class="parser-input" aria-label="修改标的代码" placeholder="标的代码" @keyup.enter="saveEditedInstrument" />
      <input v-model="editName" class="parser-input" aria-label="修改标的名称" placeholder="显示名称（可选）" @keyup.enter="saveEditedInstrument" />
      <button type="button" class="btn parser-btn" :disabled="editLoading || !editSymbol.trim()" @click="saveEditedInstrument">
        {{ editLoading ? '校验中…' : '保存修改' }}
      </button>
      <button type="button" class="text-action editor-cancel" :disabled="editLoading" @click="cancelEditInstrument">取消</button>
      <span v-if="editError" class="parser-error">{{ editError }}</span>
    </div>

    <div class="cross-layout">
      <InstrumentList
        :market-label="currentMarketLabel"
        :default-instruments="currentDefaults"
        :watchlist-instruments="currentWatchlist"
        :hidden-default-count="hiddenDefaultCount"
        :selected-symbol="selectedSymbol"
        @select="selectInstrument"
        @add-to-watchlist="addToWatchlist"
        @edit-watchlist="startEditInstrument"
        @remove-watchlist="removeFromWatchlist"
        @remove-default="removeDefault"
        @restore-defaults="restoreDefaults"
      />

      <main class="panel chart-panel">
        <div class="symbol-head">
          <div class="symbol-title">
            <div><b>{{ currentInstrument?.name || quote?.name || '选择标的' }}</b><span>{{ selectedSymbol }}</span></div>
            <small>{{ currentMarketLabel }} · {{ quote?.currency || '-' }} · {{ quote?.source || '公开行情源' }}</small>
          </div>
          <div v-if="quote" class="headline-quote">
            <b>{{ fmt(quote.price) }}</b>
            <span :class="Number(quote.change || 0) >= 0 ? 'pos' : 'neg'">{{ fmt(quote.change) }} · {{ fmtPct(quote.change_pct) }}</span>
          </div>
        </div>
        <div class="chart-shell">
          <div ref="chartRef" class="chart tall"></div>
          <DataState v-if="chartState" :state="chartState" overlay
                     :message="chartState === 'error' ? error : chartState === 'empty' ? '当前标的暂时没有可绘制的历史数据。' : '正在加载报价、K 线与技术指标。'"
                     :retryable="chartState === 'error'" @retry="refresh" />
        </div>
        <div class="chart-meta">
          <span v-if="range">{{ range.start }} — {{ range.end }} · {{ range.count }} 根</span>
          <span>拖动底部时间轴查看历史 · 滚轮缩放</span>
        </div>
      </main>

      <aside class="right-rail">
        <QuoteDetail :quote="quote" :interval-label="currentIntervalLabel" />
        <TechnicalSummary :analysis="technicalAnalysis" />
        <ResearchRail :content="analysis" :loading="analysisLoading" :disabled="!quote" @generate="runAnalysis" />
      </aside>
    </div>
  </div>
</template>

<style scoped>
.cross-market { display: flex; flex-direction: column; gap: 10px; min-width: 0; }
.cross-heading { min-height: 50px; display: flex; align-items: flex-end; justify-content: space-between; gap: 16px; padding: 0 2px 10px; border-bottom: 1px solid var(--line); }
.cross-heading h1 { margin: 0; color: var(--text); font: 650 13px/1 ui-monospace, monospace; letter-spacing: .11em; }
.cross-heading span { display: block; margin-top: 7px; color: var(--subtle); font-size: 10px; }
.cross-heading > strong { color: var(--muted); font: 600 8px/1 ui-monospace, monospace; letter-spacing: .1em; }
.cross-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 16px; min-height: 36px; border-bottom: 1px solid var(--line); }
.market-switch { display: flex; align-items: stretch; gap: 20px; align-self: stretch; }
.market-tab { position: relative; border: 0; background: transparent; color: var(--muted); padding: 0 1px 9px; font: 600 9px/1 ui-monospace, monospace; letter-spacing: .06em; cursor: pointer; }
.market-tab.active { color: var(--text); font-weight: 650; }
.market-tab.active::after { content: ''; position: absolute; left: 0; right: 0; bottom: -1px; height: 2px; background: var(--accent); }
.toolbar-right { display: flex; align-items: center; justify-content: flex-end; gap: 8px; padding-bottom: 7px; }
.period-switch { display: flex; gap: 0; padding: 0; border: 1px solid var(--line); border-radius: 0; background: transparent; }
.period-btn { border: 0; border-right: 1px solid var(--line); background: transparent; color: var(--muted); border-radius: 0; padding: 6px 8px; font-size: 9px; cursor: pointer; }
.period-btn:last-child { border-right: 0; }
.period-btn.active { background: #44473f; color: #f2eee6; font-weight: 700; }
.refresh-note { color: var(--subtle); font-size: 9px; white-space: nowrap; }
.refresh-note.stale { color: var(--warn); }
.market-status { display: inline-flex; align-items: center; gap: 5px; color: var(--subtle); font-size: 9px; white-space: nowrap; }
.market-status i { width: 5px; height: 5px; border-radius: 50%; background: #686d72; }
.market-status.open { color: #27c46b; }
.market-status.open i { background: #27c46b; box-shadow: 0 0 0 3px rgba(39,196,107,.1); }
.btn { min-height: 30px; background: transparent; border: 1px solid var(--line-strong); color: var(--text); border-radius: 0; padding: 5px 11px; cursor: pointer; font-size: 10px; }
.btn:disabled { opacity: .45; cursor: not-allowed; }
.error { color: #96564e; padding: 9px 10px; background: rgba(154,91,83,.06); border: 1px solid rgba(154,91,83,.22); border-radius: 0; font-size: 11px; }
.symbol-parser { display: flex; align-items: center; gap: 8px; padding: 8px 0; background: transparent; border: 0; border-bottom: 1px solid var(--line); border-radius: 0; }
.parser-label { color: var(--text); font: 650 9px/1 ui-monospace, monospace; letter-spacing: .07em; white-space: nowrap; }
.parser-input { flex: 0 1 260px; min-width: 140px; height: 30px; background: transparent; border: 0; border-bottom: 1px solid var(--line-strong); border-radius: 0; color: var(--text); padding: 0 5px; font-size: 10px; outline: none; }
.parser-input:focus { border-color: #6a5b40; }
.parser-btn { white-space: nowrap; }
.parser-hint { color: var(--subtle); font-size: 9px; }
.parser-error { color: #ef5350; font-size: 10px; padding: 0 2px; }
.preference-status { min-height: 12px; color: var(--subtle); font-size: 9px; }
.instrument-editor { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; padding: 8px 10px; background: rgba(161,132,88,.06); border: 1px solid var(--accent); border-radius: 0; }
.editor-title { display: flex; align-items: baseline; gap: 8px; margin-right: 4px; }
.editor-title b { color: var(--text); font-size: 10px; }
.editor-title span { color: var(--subtle); font-size: 9px; }
.instrument-editor .parser-input { flex: 0 1 190px; }
.editor-cancel { font-size: 10px; }
.cross-layout { display: grid; grid-template-columns: 220px minmax(0, 1fr) 300px; gap: 0; align-items: stretch; min-width: 0; border: 1px solid var(--line); }
.panel { background: var(--panel); border: 1px solid var(--line); border-radius: var(--radius); }
.chart-panel { padding: 13px; min-width: 0; border-top: 0 !important; border-bottom: 0 !important; }
.symbol-head { display: flex; align-items: flex-end; justify-content: space-between; gap: 16px; min-height: 45px; }
.symbol-title > div { display: flex; align-items: baseline; gap: 8px; }
.symbol-title b { color: var(--text); font-size: 15px; font-weight: 680; }
.symbol-title span { color: var(--muted); font-size: 10px; font-variant-numeric: tabular-nums; }
.symbol-title small { display: block; margin-top: 4px; color: var(--subtle); font-size: 9px; }
.headline-quote { display: flex; align-items: baseline; gap: 9px; white-space: nowrap; }
.headline-quote > b { color: var(--accent-strong); font-size: 24px; line-height: 1; font-weight: 680; letter-spacing: -.025em; font-variant-numeric: tabular-nums; }
.headline-quote span { font-size: 10px; font-variant-numeric: tabular-nums; }
.chart-shell { position: relative; margin-top: 10px; }
.chart { width: 100%; background: var(--workspace-chart-bg, transparent); border: 1px solid var(--line); border-radius: 0; }
.chart.tall { height: 520px; }
.chart-meta { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-top: 7px; color: var(--subtle); font-size: 9px; }
.right-rail { display: flex; flex-direction: column; gap: 0; min-width: 0; border-left: 1px solid var(--line); }
.pos { color: #27c46b !important; } .neg { color: #ef5350 !important; }
@media (max-width: 1180px) { .cross-layout { grid-template-columns: 195px minmax(0, 1fr) 265px; } .chart.tall { height: 480px; } }
@media (max-width: 980px) { .cross-layout { grid-template-columns: 190px minmax(0, 1fr); } .right-rail { grid-column: 1 / -1; display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); } }
@media (max-width: 700px) { .cross-toolbar { align-items: flex-start; flex-direction: column; } .toolbar-right { width: 100%; justify-content: flex-start; overflow-x: auto; } .symbol-parser { align-items: stretch; flex-wrap: wrap; } .parser-input { flex: 1 1 180px; } .parser-hint { width: 100%; } .cross-layout { grid-template-columns: 1fr; } .right-rail { grid-column: auto; grid-template-columns: 1fr; } .chart.tall { height: 380px; } .symbol-head { align-items: flex-start; flex-direction: column; } .chart-meta { align-items: flex-start; flex-direction: column; } }
</style>
