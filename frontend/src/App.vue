<script setup>
import { ref, reactive, computed, onMounted, nextTick } from 'vue'
import * as echarts from 'echarts'
import { api, auth, API_BASE } from './api/client'
import LoginView from './components/LoginView.vue'
import SimTradeView from './components/SimTradeView.vue'
import AiCenter from './components/AiCenter.vue'
import OpsView from './components/OpsView.vue'

// ---- 登录态 ----
const user = ref(null)
const isLoggedIn = computed(() => !!user.value || auth.isLoggedIn())

function handleLoggedIn(u) {
  user.value = u
}
function logout() {
  auth.logout()
  user.value = null
}

// ---- Tab ----
const tabs = ['行情', '回测', '模拟盘', 'AI中心', '运维']
const activeTab = ref('行情')
function switchTab(name) {
  activeTab.value = name
  if (name === '行情') setTimeout(() => klineChart && klineChart.resize(), 50)
}

// ---- 行情状态 ----
const connected = ref(false)
const realtimePrices = ref(null)   // 黄金ETF + 伦敦金 实时价
const markets = [
  { key: 'gold_etf', label: '黄金ETF华夏', symbol: 'sh518850' },
  { key: 'london_gold', label: '伦敦金(现货黄金)', symbol: 'hf_XAU' },
]
// 每个标的独立的K线配置 (limit: 根数, interval: day/1/5/15/30/60)
const etfCfg = reactive({ limit: 120, interval: 'day' })
const londonCfg = reactive({ limit: 120, interval: 'day' })
const intervals = [
  { v: 'day', label: '日K' },
  { v: '1', label: '1分' },
  { v: '5', label: '5分' },
  { v: '15', label: '15分' },
  { v: '30', label: '30分' },
  { v: '60', label: '60分' },
]

async function loadRealtime() {
  try {
    const d = await api.marketPrices()
    realtimePrices.value = d.data || null
  } catch (e) { /* 忽略, 不阻塞K线 */ }
}

// ---- 回测状态 ----
const bt = reactive({ short_ma: 5, long_ma: 20, initial_cash: 100000, running: false })
const btResult = ref(null)
const btError = ref('')

const klineEtfRef = ref(null)
const klineLondonRef = ref(null)
const equityRef = ref(null)
let klineEtfChart = null
let klineLondonChart = null
let equityChart = null
const klineRanges = ref({ gold_etf: null, london_gold: null })

// ---- 行情/回测 ----
async function loadKline() {
  // 同时加载两个标的的K线 (各自独立配置)
  await Promise.all([
    loadOneKline('gold_etf', klineEtfRef, 'klineEtfChart', etfCfg),
    loadOneKline('london_gold', klineLondonRef, 'klineLondonChart', londonCfg),
  ])
}

async function loadOneKline(marketKey, refObj, chartVar, cfg) {
  try {
    const m = markets.find(x => x.key === marketKey) || markets[0]
    const d = await api.marketKline({ market: marketKey, limit: cfg.limit, interval: cfg.interval })
    let data = Array.isArray(d.data) ? d.data : (d.data?.data || [])
    klineRanges.value[marketKey] = d.data?.range
    renderKline(refObj, chartVar, data)
    connected.value = true
  } catch (e) { connected.value = false }
}

async function runBacktest() {
  bt.running = true; btError.value = ''; btResult.value = null
  try {
    const d = await api.marketKline({ market: 'gold_etf', limit: etfCfg.limit, interval: 'day' })
    const klines = Array.isArray(d.data) ? d.data : (d.data?.data || [])
    // 前端本地双均线回测
    const res = localBacktest(klines, bt.short_ma, bt.long_ma, bt.initial_cash)
    btResult.value = res
    await nextTick()
    renderEquity(res.equity_curve || [])
  } catch (e) { btError.value = String(e) }
  finally { bt.running = false }
}

function localBacktest(klines, short, long, cash0) {
  const closes = klines.map(k => k.close)
  const dates = klines.map(k => k.date)
  const ma = (win) => closes.map((_, i) =>
    i >= win - 1 ? closes.slice(i - win + 1, i + 1).reduce((a, b) => a + b, 0) / win : null)
  const maS = ma(short), maL = ma(long)
  let cash = cash0, shares = 0, inPos = false
  const equityCurve = [], trades = []
  for (let i = 0; i < klines.length; i++) {
    const c = closes[i], ms = maS[i], ml = maL[i]
    if (ms != null && ml != null) {
      if (ms > ml && !inPos) { shares = cash / c * 0.999; cash = 0; inPos = true; trades.push({date: dates[i], type: 'BUY', price: c}) }
      else if (ms < ml && inPos) { cash = shares * c * 0.999; shares = 0; inPos = false; trades.push({date: dates[i], type: 'SELL', price: c}) }
    }
    equityCurve.push({ date: dates[i], equity: cash + shares * c })
  }
  const final = equityCurve.length ? equityCurve[equityCurve.length - 1].equity : cash0
  const totalReturn = (final / cash0 - 1) * 100
  let peak = equityCurve[0]?.equity || cash0, maxDd = 0
  for (const p of equityCurve) { peak = Math.max(peak, p.equity); maxDd = Math.max(maxDd, (peak - p.equity) / peak) }
  const days = Math.max(klines.length, 1)
  const annual = (Math.pow(final / cash0, 365 / days) - 1) * 100
  return {
    range: { start: dates[0], end: dates[dates.length - 1], bars: dates.length },
    final_equity: round2(final), total_return_pct: round2(totalReturn), annual_return_pct: round2(annual),
    buy_hold_return_pct: round2(klines.length > 1 ? ((closes[closes.length-1]/closes[0] - 1) * 100) : 0),
    max_drawdown_pct: round2(maxDd * 100), num_trades: trades.length, trades, equity_curve: equityCurve,
  }
}

function renderKline(refObj, chartVar, data) {
  if (!refObj.value) return
  let chart = chartVar === 'klineEtfChart' ? klineEtfChart : klineLondonChart
  if (!chart) {
    chart = echarts.init(refObj.value)
    if (chartVar === 'klineEtfChart') klineEtfChart = chart
    else klineLondonChart = chart
  }
  const dates = data.map(x => x.date)
  const ohlc = data.map(x => [x.open, x.close, x.low, x.high])
  const vols = data.map((x, i) => [i, x.volume, x.close >= x.open ? 1 : -1])
  chart.setOption({
    backgroundColor: 'transparent',
    tooltip: { trigger: 'axis', axisPointer: { type: 'cross' } },
    legend: { data: ['K线', '成交量'], textStyle: { color: '#8ba0c8' } },
    grid: [
      { left: 55, right: 20, top: 20, height: '62%' },
      { left: 55, right: 20, top: '78%', height: '14%' },
    ],
    xAxis: [
      { type: 'category', data: dates, boundaryGap: true, axisLabel: { color: '#8ba0c8' } },
      { type: 'category', gridIndex: 1, data: dates, axisLabel: { show: false } },
    ],
    yAxis: [
      { scale: true, axisLabel: { color: '#8ba0c8' }, splitLine: { lineStyle: { color: '#1a2540' } } },
      { gridIndex: 1, axisLabel: { show: false }, splitLine: { show: false } },
    ],
    dataZoom: [
      { type: 'inside', xAxisIndex: [0, 1], start: 40, end: 100 },
      { type: 'slider', xAxisIndex: [0, 1], bottom: 0, height: 18, borderColor: '#243453', textStyle: { color: '#8ba0c8' } },
    ],
    series: [
      { name: 'K线', type: 'candlestick', data: ohlc, itemStyle: { color: '#ef5350', color0: '#27c46b', borderColor: '#ef5350', borderColor0: '#27c46b' } },
      { name: '成交量', type: 'bar', xAxisIndex: 1, yAxisIndex: 1, data: vols, itemStyle: { color: p => p.data[2] > 0 ? '#ef5350' : '#27c46b' } },
    ],
  })
}

function renderEquity(curve) {
  if (!equityRef.value) return
  if (!equityChart) equityChart = echarts.init(equityRef.value)
  equityChart.setOption({
    backgroundColor: 'transparent',
    tooltip: { trigger: 'axis' },
    grid: { left: 55, right: 20, top: 20, bottom: 30 },
    xAxis: { type: 'category', data: curve.map(p => p.date), axisLabel: { color: '#8ba0c8' } },
    yAxis: { type: 'value', scale: true, axisLabel: { color: '#8ba0c8' }, splitLine: { lineStyle: { color: '#1a2540' } } },
    series: [{ name: '策略净值', type: 'line', showSymbol: false, data: curve.map(p => p.equity), lineStyle: { color: '#4da8ff', width: 2 }, areaStyle: { color: 'rgba(77,168,255,0.15)' } }],
  })
}

function round2(n) { return Math.round(n * 100) / 100 }
function fmt(n) { return n == null ? '-' : Number(n).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 }) }
function fmtPct(n) { return n == null ? '-' : Number(n).toFixed(2) + '%' }

onMounted(async () => {
  if (auth.isLoggedIn()) user.value = { email: '已登录' }
  await Promise.all([loadKline(), loadRealtime()])
  window.addEventListener('resize', () => {
    klineEtfChart && klineEtfChart.resize()
    klineLondonChart && klineLondonChart.resize()
    equityChart && equityChart.resize()
  })
})
</script>

<template>
  <div class="container">
    <header class="navbar">
      <div class="brand">
        <span class="brand-icon"></span>
        <span class="brand-name">贾维斯 · 金融投研</span>
      </div>
      <div class="nav-right">
        <div v-if="isLoggedIn" class="user-chip">
          <span class="email">{{ user?.email || '已登录' }}</span>
          <button class="btn small" @click="logout">退出</button>
        </div>
        <div v-else class="conn" :class="{ ok: connected }">
          <span class="dot"></span>{{ connected ? '后端已连接' : '行情未连接' }}
        </div>
      </div>
    </header>

    <!-- 未登录: 显示登录页 -->
    <LoginView v-if="!isLoggedIn" @logged-in="handleLoggedIn" />

    <!-- 已登录: 工作台 -->
    <template v-else>
      <nav class="tabs">
        <button v-for="t in tabs" :key="t" class="tab-btn"
                :class="{ active: activeTab === t }" @click="switchTab(t)">{{ t }}</button>
      </nav>

      <!-- 行情: 上排实时价格(左右), 下排K线(各占一整行) -->
      <section v-show="activeTab === '行情'" class="panel-wrap">
        <!-- 上排: 实时价格 Card (左右两个) -->
        <div v-if="realtimePrices" class="dual-grid">
          <div v-for="(m, key) in realtimePrices" :key="key" class="rt-card">
            <div class="rt-name">{{ m.name }}</div>
            <div class="rt-price">{{ fmt(m.price) }}</div>
            <div class="rt-sub">
              <span :class="(m.change || 0) >= 0 ? 'pos' : 'neg'">
                {{ m.change }} ({{ fmtPct(m.change_pct) }})
              </span>
              <span class="rt-muted">昨收 {{ m.prev_close }}</span>
            </div>
          </div>
        </div>

        <!-- 下排: K线 Card (各占一整行, 两排) -->
        <div class="stack-grid">
          <div class="panel kline-card">
            <div class="panel-head">
              <h2>黄金ETF华夏 K线</h2>
              <div class="kline-ctrl">
                <select v-model="etfCfg.interval" @change="loadKline" class="select">
                  <option v-for="iv in intervals" :key="iv.v" :value="iv.v">{{ iv.label }}</option>
                </select>
                <select v-model.number="etfCfg.limit" @change="loadKline" class="select">
                  <option :value="60">60 根</option>
                  <option :value="120">120 根</option>
                  <option :value="250">250 根</option>
                </select>
              </div>
            </div>
            <div ref="klineEtfRef" class="chart tall"></div>
            <div v-if="klineRanges.gold_etf" class="hint">
              区间 {{ klineRanges.gold_etf.min }} ~ {{ klineRanges.gold_etf.max }} ({{ klineRanges.gold_etf.count }} 根)
            </div>
          </div>

          <div class="panel kline-card">
            <div class="panel-head">
              <h2>伦敦金(现货黄金) K线</h2>
              <div class="kline-ctrl">
                <select v-model="londonCfg.interval" @change="loadKline" class="select">
                  <option v-for="iv in intervals" :key="iv.v" :value="iv.v">{{ iv.label }}</option>
                </select>
                <select v-model.number="londonCfg.limit" @change="loadKline" class="select">
                  <option :value="60">60 根</option>
                  <option :value="120">120 根</option>
                  <option :value="250">250 根</option>
                </select>
              </div>
            </div>
            <div ref="klineLondonRef" class="chart tall"></div>
            <div v-if="klineRanges.london_gold" class="hint">
              区间 {{ klineRanges.london_gold.min }} ~ {{ klineRanges.london_gold.max }} ({{ klineRanges.london_gold.count }} 根)
            </div>
          </div>
        </div>
      </section>

      <!-- 回测 -->
      <section v-show="activeTab === '回测'" class="panel-wrap">
        <div class="panel">
          <div class="panel-head">
            <h2>双均线策略回测</h2>
            <div class="controls">
              <label>短期 <input type="number" v-model.number="bt.short_ma" min="1" class="num" /></label>
              <label>长期 <input type="number" v-model.number="bt.long_ma" min="2" class="num" /></label>
              <label>本金 <input type="number" v-model.number="bt.initial_cash" min="1000" class="num wide" /></label>
              <button class="btn primary" @click="runBacktest" :disabled="bt.running">{{ bt.running ? '回测中...' : '运行回测' }}</button>
            </div>
          </div>
          <div v-if="btError" class="error">{{ btError }}</div>
          <template v-if="btResult">
            <div class="metrics">
              <div class="metric"><div class="m-label">期末资金</div><div class="m-value">{{ fmt(btResult.final_equity) }}</div></div>
              <div class="metric"><div class="m-label">总收益率</div><div class="m-value" :class="btResult.total_return_pct >= 0 ? 'pos' : 'neg'">{{ fmtPct(btResult.total_return_pct) }}</div></div>
              <div class="metric"><div class="m-label">年化收益</div><div class="m-value" :class="btResult.annual_return_pct >= 0 ? 'pos' : 'neg'">{{ fmtPct(btResult.annual_return_pct) }}</div></div>
              <div class="metric"><div class="m-label">买入持有</div><div class="m-value" :class="btResult.buy_hold_return_pct >= 0 ? 'pos' : 'neg'">{{ fmtPct(btResult.buy_hold_return_pct) }}</div></div>
              <div class="metric"><div class="m-label">最大回撤</div><div class="m-value neg">{{ fmtPct(btResult.max_drawdown_pct) }}</div></div>
              <div class="metric"><div class="m-label">交易次数</div><div class="m-value">{{ btResult.num_trades }}</div></div>
            </div>
            <h3 class="section-sub">策略净值曲线</h3>
            <div ref="equityRef" class="chart"></div>
            <h3 v-if="btResult.trades.length" class="section-sub">近期交易</h3>
            <table v-if="btResult.trades.length" class="table">
              <thead><tr><th>日期</th><th>方向</th><th>价格</th></tr></thead>
              <tbody><tr v-for="(t, i) in btResult.trades.slice(-10)" :key="i">
                <td>{{ t.date }}</td><td :class="t.type === 'BUY' ? 'pos' : 'neg'">{{ t.type }}</td><td>{{ t.price }}</td>
              </tr></tbody>
            </table>
          </template>
        </div>
      </section>

      <!-- 模拟盘 -->
      <section v-show="activeTab === '模拟盘'">
        <SimTradeView />
      </section>

      <!-- AI 中心 -->
      <section v-show="activeTab === 'AI中心'" class="panel-wrap">
        <AiCenter />
      </section>

      <!-- 运维监控 -->
      <section v-show="activeTab === '运维'" class="panel-wrap">
        <OpsView />
      </section>

      <footer class="foot">
        <span>后端: {{ API_BASE ? API_BASE : '(开发代理 /api → :8200)' }}</span>
      </footer>
    </template>
  </div>
</template>

<style scoped>
.container { max-width: 1200px; margin: 0 auto; padding: 0 20px 40px; }
.navbar { display: flex; align-items: center; justify-content: space-between; padding: 16px 0; }
.brand { display: flex; align-items: center; gap: 10px; font-size: 20px; font-weight: 700; }
.brand-icon { width: 22px; height: 22px; border-radius: 6px; background: linear-gradient(135deg, #4da8ff, #17d5c2); box-shadow: 0 0 18px rgba(54,174,255,.45); }
.nav-right { display: flex; align-items: center; gap: 12px; }
.user-chip { display: flex; align-items: center; gap: 10px; }
.email { color: #8ba0c8; font-size: 13px; }
.conn { display: flex; align-items: center; gap: 8px; color: #8ba0c8; font-size: 13px; }
.conn .dot { width: 8px; height: 8px; border-radius: 50%; background: #ef5350; }
.conn.ok .dot { background: #27c46b; box-shadow: 0 0 8px #27c46b; }
.tabs { display: flex; gap: 8px; margin: 8px 0 20px; }
.tab-btn { background: #121a2d; border: 1px solid #243453; color: #8ba0c8; border-radius: 8px; padding: 9px 22px; cursor: pointer; font-size: 14px; }
.tab-btn.active { background: linear-gradient(135deg, #4da8ff, #a842ff); color: #fff; border-color: transparent; }
.panel-wrap { margin-top: 4px; }
.dual-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; margin-bottom: 16px; }
.stack-grid { display: flex; flex-direction: column; gap: 16px; }
.rt-card { background: #121a2d; border: 1px solid #243453; border-radius: 12px; padding: 18px 20px; position: relative; overflow: hidden; }
.rt-card::before { content: ''; position: absolute; top: 0; left: 0; right: 0; height: 3px; background: linear-gradient(90deg, #4da8ff, #a842ff); }
.rt-name { color: #8ba0c8; font-size: 13px; margin-bottom: 6px; }
.rt-price { font-size: 30px; font-weight: 700; color: #e9effb; font-variant-numeric: tabular-nums; }
.rt-sub { display: flex; align-items: center; gap: 12px; margin-top: 6px; font-size: 13px; }
.rt-muted { color: #5a6b8c; font-size: 12px; }
.kline-card { margin-bottom: 0; }
.kline-ctrl { display: flex; gap: 8px; }
@media (max-width: 900px) { .dual-grid { grid-template-columns: 1fr; } }
.panel { background: #121a2d; border: 1px solid #243453; border-radius: 12px; padding: 20px; }
.panel-head { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 12px; }
.panel-head h2 { margin: 0; font-size: 18px; color: #e9effb; }
.chart { width: 100%; background: #0f1626; border: 1px solid #1a2540; border-radius: 8px; }
.chart.tall { height: 420px; margin-top: 16px; }
.chart:not(.tall) { height: 300px; margin-top: 12px; }
.controls { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
.controls label { display: flex; align-items: center; gap: 6px; font-size: 13px; color: #8ba0c8; }
.num { width: 70px; background: #0f1626; border: 1px solid #243453; color: #e9effb; border-radius: 6px; padding: 6px 8px; }
.num.wide { width: 110px; }
.select { background: #0f1626; border: 1px solid #243453; color: #e9effb; border-radius: 6px; padding: 6px 10px; }
.btn { background: #1a2540; border: 1px solid #243453; color: #e9effb; border-radius: 6px; padding: 8px 16px; cursor: pointer; font-size: 14px; }
.btn.small { padding: 5px 12px; font-size: 12px; }
.btn.primary { background: linear-gradient(135deg, #4da8ff, #a842ff); border: none; }
.metrics { display: grid; grid-template-columns: repeat(auto-fit, minmax(140px, 1fr)); gap: 12px; margin: 16px 0; }
.metric { background: #0f1626; border: 1px solid #1a2540; border-radius: 8px; padding: 12px 14px; }
.m-label { color: #8ba0c8; font-size: 12px; }
.m-value { font-size: 20px; font-weight: 700; margin-top: 4px; }
.pos { color: #27c46b; }
.neg { color: #ef5350; }
.error { color: #ef5350; padding: 10px; background: rgba(239,83,80,.1); border-radius: 6px; margin: 12px 0; }
.hint { color: #8ba0c8; font-size: 12px; margin-top: 10px; }
.section-sub { color: #8ba0c8; font-size: 15px; margin: 20px 0 8px; }
.table { width: 100%; border-collapse: collapse; font-size: 13px; }
.table th, .table td { text-align: left; padding: 8px 10px; border-bottom: 1px solid #1a2540; }
.table th { color: #8ba0c8; font-weight: 600; }
.foot { color: #8ba0c8; font-size: 12px; margin-top: 16px; }
@media (max-width: 700px) { .metrics { grid-template-columns: 1fr 1fr; } }
</style>
