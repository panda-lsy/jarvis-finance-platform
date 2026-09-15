<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { api } from '../api/client'
import QuoteStrip from '../components/market/QuoteStrip.vue'
import DataState from '../components/common/DataState.vue'
import { useMarketChart } from '../composables/useMarketChart'
import { usePolling } from '../composables/usePolling'
import { useLatestRequest } from '../composables/useLatestRequest'
import { useFreshness } from '../composables/useFreshness'
import { formatNumber, formatPercent } from '../utils/formatters'

const props = defineProps({ active: { type: Boolean, default: true } })
const emit = defineEmits(['connection-change', 'context-change', 'ready'])

const connected = ref(false)
const initializing = ref(true)
const streamServerTime = ref('')
let closePriceStream = null
const realtimePrices = ref(null)
const jdPrices = ref(null)
const marketFocus = ref('gold_etf')
const marketKlines = reactive({ gold_etf: [], london_gold: [] })
const klineRanges = ref({ gold_etf: null, london_gold: null })
const jdKlineData = ref([])
const jdKlineRange = ref(null)
const jdKlineError = ref('')
const klineErrors = reactive({ gold_etf: '', london_gold: '' })

const etfCfg = reactive({ limit: 120, interval: 'day' })
const londonCfg = reactive({ limit: 120, interval: 'day' })
const jdKlineCfg = reactive({ market: 'zheshang', interval: 5, limit: 200 })

const intervals = [
  { v: 'day', label: '日K' },
  { v: '1', label: '1分' },
  { v: '5', label: '5分' },
  { v: '15', label: '15分' },
  { v: '30', label: '30分' },
  { v: '60', label: '1小时' },
]
const marketFocusTabs = [
  { key: 'gold_etf', label: '黄金ETF' },
  { key: 'london_gold', label: '伦敦金' },
  { key: 'jd', label: '积存金' },
]

const marketChart = useMarketChart()
const marketChartRef = marketChart.elementRef
const latestMarketKline = useLatestRequest()
const latestJdKline = useLatestRequest()
const freshness = useFreshness(90000)
// SSE 是主实时链路；30s HTTP 轮询保留为兜底，避免代理层暂时不支持 SSE 时页面完全失去报价。
const poll = usePolling(async () => {
  await Promise.all([loadJdLive(), loadRealtime()])
}, 30000)

function startPriceStream() {
  if (closePriceStream) return
  closePriceStream = api.marketPriceStream(payload => {
    if (payload?.market && Object.keys(payload.market).length) realtimePrices.value = payload.market
    if (payload?.jd && Object.keys(payload.jd).length) jdPrices.value = payload.jd
    streamServerTime.value = payload?.server_time || ''
    freshness.touch()
    setConnected(true)
  }, () => {
    // EventSource 会自动重连；期间继续由 30s fallback poll 保留最后有效报价。
    setConnected(false)
  })
}

function stopPriceStream() {
  closePriceStream?.()
  closePriceStream = null
}

function setConnected(value) {
  connected.value = value
  emit('connection-change', value)
}

async function loadRealtime() {
  try {
    const response = await api.marketPrices()
    realtimePrices.value = response.data || null
    if (realtimePrices.value) freshness.touch()
  } catch (_) { /* 保留最后一次有效报价 */ }
}

async function loadJdLive() {
  try {
    const response = await api.jdPrices()
    if (response.code === 200 && response.data && Object.keys(response.data).length) {
      jdPrices.value = response.data
      freshness.touch()
    }
  } catch (_) { /* 保留最后一次有效报价 */ }
}

async function loadJdKline() {
  const requestVersion = latestJdKline.begin()
  jdKlineError.value = ''
  const requestMarket = jdKlineCfg.market
  const requestInterval = jdKlineCfg.interval
  const requestLimit = jdKlineCfg.limit
  try {
    const response = await api.jdKline(requestMarket, requestInterval, requestLimit)
    if (!latestJdKline.isLatest(requestVersion)) return
    const body = response?.data && response.data.data ? response.data : response
    jdKlineRange.value = body?.range || null
    jdKlineData.value = body?.data || []
    if (marketFocus.value === 'jd') await renderPrimaryChart()
  } catch (error) {
    if (latestJdKline.isLatest(requestVersion)) jdKlineError.value = error?.message || String(error)
  }
}

async function loadOneKline(marketKey, cfg) {
  const requestVersion = latestMarketKline.begin(marketKey)
  klineErrors[marketKey] = ''
  const requestLimit = cfg.limit
  const requestInterval = cfg.interval
  try {
    const response = await api.marketKline({ market: marketKey, limit: requestLimit, interval: requestInterval })
    if (!latestMarketKline.isLatest(requestVersion, marketKey)) return
    if (response.code !== 200) throw new Error(response.message || 'K线加载失败')
    const data = Array.isArray(response.data) ? response.data : (response.data?.data || [])
    marketKlines[marketKey] = data
    klineRanges.value[marketKey] = response.data?.range || null
    setConnected(true)
    if (marketFocus.value === marketKey) await renderPrimaryChart()
  } catch (error) {
    if (latestMarketKline.isLatest(requestVersion, marketKey)) {
      klineErrors[marketKey] = error?.message || String(error)
      setConnected(false)
    }
  }
}

async function loadKlines() {
  await Promise.all([
    loadOneKline('gold_etf', etfCfg),
    loadOneKline('london_gold', londonCfg),
  ])
}

const focusedTitle = computed(() => {
  if (marketFocus.value === 'gold_etf') return '黄金ETF华夏'
  if (marketFocus.value === 'london_gold') return '伦敦金（现货黄金）'
  return jdKlineCfg.market === 'zheshang' ? '浙商积存金' : '民生积存金'
})
const focusedInterval = computed(() => marketFocus.value === 'gold_etf'
  ? etfCfg.interval
  : marketFocus.value === 'london_gold' ? londonCfg.interval : String(jdKlineCfg.interval))
const focusedLimit = computed(() => marketFocus.value === 'gold_etf'
  ? etfCfg.limit
  : marketFocus.value === 'london_gold' ? londonCfg.limit : jdKlineCfg.limit)
const focusedRange = computed(() => marketFocus.value === 'gold_etf'
  ? klineRanges.value.gold_etf
  : marketFocus.value === 'london_gold' ? klineRanges.value.london_gold : jdKlineRange.value)
const focusedQuote = computed(() => {
  if (marketFocus.value === 'gold_etf') return realtimePrices.value?.gold_etf || null
  if (marketFocus.value === 'london_gold') return realtimePrices.value?.london_gold || null
  return jdPrices.value?.[jdKlineCfg.market] || null
})
const focusedResearchContext = computed(() => {
  if (marketFocus.value === 'gold_etf') {
    return { market: 'gold_etf', symbol: '518850', name: focusedTitle.value, sourceModule: 'market' }
  }
  if (marketFocus.value === 'london_gold') {
    return { market: 'london_gold', symbol: 'XAUUSD', name: focusedTitle.value, sourceModule: 'market' }
  }
  return {
    market: 'jd_gold',
    symbol: jdKlineCfg.market === 'zheshang' ? 'JD-ZS-GOLD' : 'JD-MS-GOLD',
    name: focusedTitle.value,
    sourceModule: 'market',
  }
})
const focusedQuoteStale = computed(() => Boolean(focusedQuote.value?.stale))
const focusedIntervals = computed(() => marketFocus.value === 'jd'
  ? [{ v: '1', label: '1分' }, { v: '5', label: '5分' }, { v: '15', label: '15分' }, { v: '30', label: '30分' }, { v: '60', label: '1小时' }]
  : intervals)
const focusedData = computed(() => marketFocus.value === 'jd' ? jdKlineData.value : marketKlines[marketFocus.value])
const focusedError = computed(() => marketFocus.value === 'jd' ? jdKlineError.value : klineErrors[marketFocus.value])
const chartState = computed(() => {
  if (initializing.value && !focusedData.value?.length) return 'loading'
  if (focusedError.value && !focusedData.value?.length) return 'error'
  if (!focusedData.value?.length) return 'empty'
  return ''
})

async function setMarketFocus(key, jdMarket = null) {
  if (jdMarket) jdKlineCfg.market = jdMarket
  marketFocus.value = key
  if (key === 'jd' && !jdKlineData.value.length) await loadJdKline()
  await nextTick()
  await renderPrimaryChart()
  emit('context-change', focusedResearchContext.value)
}

async function handleQuoteSelect(selection) {
  await setMarketFocus(selection.key, selection.jdMarket || null)
}

async function setFocusedInterval(value) {
  if (marketFocus.value === 'gold_etf') {
    etfCfg.interval = value
    await loadOneKline('gold_etf', etfCfg)
  } else if (marketFocus.value === 'london_gold') {
    londonCfg.interval = value
    await loadOneKline('london_gold', londonCfg)
  } else {
    jdKlineCfg.interval = Number(value)
    await loadJdKline()
  }
}

async function setFocusedLimit(value) {
  const limit = Number(value)
  if (marketFocus.value === 'gold_etf') {
    etfCfg.limit = limit
    await loadOneKline('gold_etf', etfCfg)
  } else if (marketFocus.value === 'london_gold') {
    londonCfg.limit = limit
    await loadOneKline('london_gold', londonCfg)
  } else {
    jdKlineCfg.limit = limit
    await loadJdKline()
  }
}

async function renderPrimaryChart() {
  const isJd = marketFocus.value === 'jd'
  const data = isJd ? jdKlineData.value : marketKlines[marketFocus.value]
  await marketChart.renderCandles(data, {
    withVolume: !isJd,
    visibleCount: 80,
  })
}

async function retryFocusedKline() {
  if (marketFocus.value === 'jd') return loadJdKline()
  const cfg = marketFocus.value === 'gold_etf' ? etfCfg : londonCfg
  return loadOneKline(marketFocus.value, cfg)
}

async function initialize() {
  initializing.value = true
  try {
    await Promise.all([loadKlines(), loadRealtime(), loadJdKline(), loadJdLive()])
    await renderPrimaryChart()
  } finally {
    initializing.value = false
  }
}

async function initializeMountedWorkspace() {
  // Initialize the ECharts canvas before the archive-to-workspace crossfade.
  // The workspace is pre-mounted at opacity 0, so module parsing, canvas setup
  // and the first chart allocation happen off-screen instead of on the first
  // visible MARKET frame.
  await marketChart.prepare()
  await initialize()
  await nextTick()
  requestAnimationFrame(() => {
    requestAnimationFrame(() => {
      marketChart.resize()
      emit('ready')
    })
  })
}

watch(() => props.active, async active => {
  if (active) {
    poll.start()
    startPriceStream()
    await nextTick()
    requestAnimationFrame(() => marketChart.resize())
  } else {
    poll.stop()
    stopPriceStream()
  }
}, { immediate: true })

onMounted(initializeMountedWorkspace)
onBeforeUnmount(stopPriceStream)

watch([marketFocus, () => jdKlineCfg.market], () => {
  emit('context-change', focusedResearchContext.value)
}, { immediate: true })
</script>

<template>
  <section class="market-workspace">
    <div class="section-bar">
      <div>
        <h1>MARKET / LIVE FEED</h1>
        <span>{{ focusedResearchContext.symbol }} · {{ focusedTitle }} · 核心报价、K 线与市场结构</span>
      </div>
      <span class="section-status" :class="{ stale: freshness.stale || focusedQuoteStale }">
        <i :class="{ ok: connected && !freshness.stale && !focusedQuoteStale, warn: freshness.stale || focusedQuoteStale }"></i>
        {{ !connected ? '行情服务检查中' : focusedQuoteStale ? '源行情时间已陈旧' : freshness.stale ? '行情推送可能中断' : '行情服务正常' }}
      </span>
    </div>

    <QuoteStrip :jd-prices="jdPrices" :realtime-prices="realtimePrices" :market-focus="marketFocus"
                :jd-market="jdKlineCfg.market" @select="handleQuoteSelect" />

    <div class="market-focus-tabs" role="tablist" aria-label="黄金市场标的">
      <button v-for="item in marketFocusTabs" :key="item.key" class="focus-tab" type="button"
              role="tab" :aria-selected="marketFocus === item.key"
              :class="{ active: marketFocus === item.key }" @click="setMarketFocus(item.key)">
        {{ item.label }}
      </button>
    </div>

    <div class="market-primary-layout">
      <div class="panel market-chart-panel">
        <div class="panel-head chart-head">
          <div>
            <h2>{{ focusedTitle }}</h2>
            <span class="chart-caption">主图 · {{ focusedInterval === 'day' ? '日K' : focusedInterval + (marketFocus === 'jd' ? '分' : '分钟') }}</span>
          </div>
          <div class="chart-tools">
            <select v-if="marketFocus === 'jd'" v-model="jdKlineCfg.market" aria-label="积存金来源" @change="loadJdKline" class="select compact-select">
              <option value="zheshang">浙商积存金</option>
              <option value="minsheng">民生积存金</option>
            </select>
            <div class="period-group" role="group" aria-label="K线周期">
              <button v-for="item in focusedIntervals" :key="item.v" type="button" class="period-btn"
                      :aria-pressed="String(focusedInterval) === String(item.v)"
                      :class="{ active: String(focusedInterval) === String(item.v) }" @click="setFocusedInterval(item.v)">
                {{ item.label }}
              </button>
            </div>
            <select :value="focusedLimit" aria-label="K线样本数量" @change="setFocusedLimit($event.target.value)" class="select compact-select">
              <template v-if="marketFocus === 'jd'">
                <option :value="100">100 根</option><option :value="200">200 根</option><option :value="500">500 根</option>
              </template>
              <template v-else>
                <option :value="60">60 根</option><option :value="120">120 根</option><option :value="250">250 根</option>
              </template>
            </select>
          </div>
        </div>
        <div class="chart-shell">
          <div ref="marketChartRef" class="chart market-main-chart"></div>
          <DataState v-if="chartState" :state="chartState" overlay
                     :message="chartState === 'error' ? focusedError : chartState === 'empty' ? '当前条件下没有可绘制的 K 线数据。' : '正在获取行情与历史数据。'"
                     :retryable="chartState === 'error'" @retry="retryFocusedKline" />
        </div>
        <div v-if="focusedRange" class="chart-footnote">
          <span>数据区间</span>
          <b>{{ focusedRange.min ?? focusedRange.start ?? '-' }} — {{ focusedRange.max ?? focusedRange.end ?? '-' }}</b>
          <span>{{ focusedRange.count ?? focusedLimit }} 根</span>
        </div>
      </div>

      <aside class="market-rail">
        <div class="rail-panel quote-detail">
          <div class="rail-label">QUOTE / 当前报价</div>
          <div class="rail-symbol">{{ focusedTitle }}</div>
          <div class="rail-price" :class="marketFocus === 'jd' ? 'gold' : ''">{{ formatNumber(focusedQuote?.price) }}</div>
          <div class="rail-change" :class="Number(focusedQuote?.change || 0) >= 0 ? 'pos' : 'neg'">
            {{ formatNumber(focusedQuote?.change) }} · {{ formatPercent(focusedQuote?.change_pct) }}
          </div>
          <dl class="data-list">
            <div v-if="focusedQuote?.prev_close != null"><dt>昨收</dt><dd>{{ formatNumber(focusedQuote.prev_close) }}</dd></div>
            <div v-if="focusedQuote?.open != null"><dt>今开</dt><dd>{{ formatNumber(focusedQuote.open) }}</dd></div>
            <div v-if="focusedQuote?.high != null"><dt>最高</dt><dd>{{ formatNumber(focusedQuote.high) }}</dd></div>
            <div v-if="focusedQuote?.low != null"><dt>最低</dt><dd>{{ formatNumber(focusedQuote.low) }}</dd></div>
            <div><dt>周期</dt><dd>{{ focusedInterval === 'day' ? '日K' : focusedInterval + ' 分钟' }}</dd></div>
            <div><dt>样本</dt><dd>{{ focusedLimit }} 根</dd></div>
          </dl>
        </div>

        <div class="rail-panel data-health">
          <div class="rail-label">DATA HEALTH / 数据状态</div>
          <div class="health-row"><span><i :class="{ ok: connected && !freshness.stale && !focusedQuoteStale, warn: freshness.stale || focusedQuoteStale }"></i>市场数据</span><b>{{ !connected ? '检查中' : focusedQuoteStale ? '源行情陈旧' : freshness.stale ? '推送中断' : '正常' }}</b></div>
          <div class="health-row"><span>数据源</span><b>{{ focusedQuote?.source || (marketFocus === 'jd' ? '京东积存金' : '行情接口') }}</b></div>
          <div class="health-row"><span>行情时间</span><b>{{ focusedQuote?.quote_time || focusedQuote?.time || '实时刷新' }}</b></div>
          <div class="health-row"><span>实时推送</span><b>1 秒 / SSE</b></div>
          <div class="health-row"><span>服务端时间</span><b>{{ streamServerTime || '-' }}</b></div>
          <div class="health-row"><span>本地同步</span><b>{{ freshness.label }}</b></div>
          <p>拖动图表底部时间轴可查看历史区间；滚轮或触控可缩放 K 线。</p>
        </div>
      </aside>
    </div>
  </section>
</template>

<style scoped>
.market-workspace { display: flex; flex-direction: column; gap: 12px; margin: 0; }
.section-bar { display: flex; align-items: flex-end; justify-content: space-between; gap: 16px; min-height: 50px; padding: 0 2px 10px; border-bottom: 1px solid var(--line); }
.section-bar h1 { margin: 0; color: var(--text); font: 650 13px/1 ui-monospace, monospace; letter-spacing: .11em; }
.section-bar > div > span { display: block; margin-top: 7px; color: var(--subtle); font-size: 10px; }
.section-status { display: inline-flex; align-items: center; gap: 7px; color: var(--muted); font: 600 9px/1 ui-monospace, monospace; letter-spacing: .05em; }
.section-status i, .health-row i { width: 6px; height: 6px; border-radius: 50%; background: var(--bad); }
.section-status i.ok, .health-row i.ok { background: var(--ok); }
.section-status i.warn, .health-row i.warn { background: var(--warn); }
.section-status.stale { color: var(--warn); }
.market-focus-tabs { display: flex; gap: 24px; min-height: 32px; border-bottom: 1px solid var(--line); }
.focus-tab { position: relative; border: 0; background: transparent; color: var(--muted); padding: 4px 0 10px; font: 600 9px/1 ui-monospace, monospace; letter-spacing: .06em; cursor: pointer; }
.focus-tab.active { color: var(--text); font-weight: 650; }
.focus-tab.active::after { content: ''; position: absolute; left: 0; right: 0; bottom: -1px; height: 2px; background: var(--accent); }
.market-primary-layout { display: grid; grid-template-columns: minmax(0, 1fr) 286px; gap: 0; align-items: stretch; border: 1px solid var(--line); }
.market-chart-panel { min-width: 0; }
.chart-caption { display: block; margin-top: 3px; color: var(--subtle); font-size: 10px; }
.chart-tools { display: flex; align-items: center; justify-content: flex-end; gap: 6px; flex-wrap: wrap; }
.period-group { display: flex; align-items: center; gap: 0; padding: 0; border: 1px solid var(--line); border-radius: 0; background: transparent; }
.period-btn { min-width: 38px; border: 0; border-right: 1px solid var(--line); background: transparent; color: var(--muted); border-radius: 0; padding: 6px 7px; font-size: 9px; cursor: pointer; }
.period-btn:last-child { border-right: 0; }
.period-btn:hover { color: var(--text); background: var(--workspace-hover-bg, rgba(209,201,188,.28)); }
.period-btn.active { color: #f4f0e8; background: #46483f; font-weight: 700; }
.compact-select { min-height: 30px; padding: 5px 8px; font-size: 10px; }
.chart-shell { position: relative; margin-top: 8px; border-top: 1px solid var(--line); background: var(--workspace-chart-bg, transparent); }
.market-main-chart { height: 500px; }
.chart-footnote { display: flex; align-items: center; gap: 8px; color: var(--subtle); font-size: 10px; margin-top: 7px; font-variant-numeric: tabular-nums; }
.chart-footnote b { color: var(--muted); font-weight: 550; }
.market-rail { display: flex; flex-direction: column; gap: 0; min-width: 0; border-left: 1px solid var(--line); background: var(--workspace-panel-rail, transparent); }
.rail-panel { background: var(--workspace-panel-rail, rgba(239,235,227,.55)); border: 0; border-bottom: 1px solid var(--line); border-radius: 0; padding: 16px; }
.rail-label { color: var(--muted); font: 600 8px/1 ui-monospace, monospace; letter-spacing: .11em; text-transform: uppercase; }
.rail-symbol { margin-top: 9px; color: var(--muted); font-size: 11px; }
.rail-price { margin-top: 3px; color: var(--text); font-size: 29px; line-height: 1.08; font-weight: 680; letter-spacing: -.03em; font-variant-numeric: tabular-nums; }
.rail-price.gold { color: var(--accent-strong); }
.rail-change { margin-top: 6px; font-size: 11px; font-variant-numeric: tabular-nums; }
.data-list { margin: 14px 0 0; border-top: 1px solid var(--line); }
.data-list > div { display: flex; align-items: center; justify-content: space-between; gap: 10px; min-height: 31px; border-bottom: 1px solid var(--line); }
.data-list dt { color: var(--muted); font-size: 10px; }
.data-list dd { margin: 0; color: var(--text); font-size: 11px; font-weight: 550; font-variant-numeric: tabular-nums; }
.data-health { flex: 1; }
.health-row { display: flex; align-items: center; justify-content: space-between; gap: 12px; min-height: 32px; border-bottom: 1px solid var(--line); color: var(--muted); font-size: 10px; }
.health-row span { display: inline-flex; align-items: center; gap: 6px; }
.health-row b { max-width: 145px; color: var(--text); font-weight: 550; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.data-health p { margin: 12px 0 0; color: var(--muted); font-size: 10px; line-height: 1.6; }
.pos { color: var(--ok); }
.neg { color: var(--bad); }
@media (max-width: 1100px) { .market-primary-layout { grid-template-columns: minmax(0, 1fr) 245px; } }
@media (max-width: 900px) { .market-primary-layout { grid-template-columns: 1fr; } .market-rail { display: grid; grid-template-columns: 1fr 1fr; border-left: 0; border-top: 1px solid var(--line); } .rail-panel + .rail-panel { border-left: 1px solid var(--line); } .market-main-chart { height: 410px; } }
@media (max-width: 620px) { .section-bar { align-items: flex-start; flex-direction: column; } .market-rail { grid-template-columns: 1fr; } .chart-tools { justify-content: flex-start; } .period-group { max-width: 100%; overflow-x: auto; } }
</style>
