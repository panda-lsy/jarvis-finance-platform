<script setup>
import { ref, reactive, onMounted, nextTick } from 'vue'
import * as echarts from 'echarts'
import { api, API_BASE } from './api/client'

// ---- 状态 ----
const connected = ref(false)
const healthText = ref('检测中...')
const markets = ref([])
const prices = ref({})
const storage = ref(null)
const limit = ref(120)
const rangeInfo = ref(null)

// 回测参数
const bt = reactive({
  short_ma: 5,
  long_ma: 20,
  initial_cash: 100000,
  running: false,
})

const btResult = ref(null)
const btError = ref('')

const klineRef = ref(null)
const equityRef = ref(null)
let klineChart = null
let equityChart = null

// ---- 数据加载 ----
async function loadAll() {
  try {
    await api.health()
    connected.value = true
    healthText.value = '后端已连接'
  } catch (e) {
    connected.value = false
    healthText.value = '无法连接后端 (通过 ?api=http://<IP>:8100 指定)'
  }
  try { markets.value = (await api.markets()).markets || [] } catch (e) {}
  try { prices.value = (await api.prices()).prices || {} } catch (e) {}
  try { storage.value = (await api.storage()).summary } catch (e) {}
  await loadKline()
}

async function loadKline() {
  try {
    const d = await api.kline({ market: 'gold_etf', limit: limit.value })
    rangeInfo.value = d.range
    renderKline(d.data)
  } catch (e) {
    console.error(e)
  }
}

async function runBacktest() {
  bt.running = true
  btError.value = ''
  btResult.value = null
  try {
    const d = await api.backtest({
      market: 'gold_etf',
      short_ma: bt.short_ma,
      long_ma: bt.long_ma,
      initial_cash: bt.initial_cash,
      limit: limit.value,
    })
    btResult.value = d
    await nextTick()
    renderEquity(d.equity_curve || [])
    renderEntryPoints(d)
  } catch (e) {
    btError.value = String(e)
  } finally {
    bt.running = false
  }
}

// ---- 图表渲染 ----
function renderKline(data) {
  if (!klineRef.value) return
  if (!klineChart) klineChart = echarts.init(klineRef.value)
  const dates = data.map((x) => x.date)
  const ohlc = data.map((x) => [x.open, x.close, x.low, x.high])
  const vols = data.map((x, i) => [i, x.volume, x.close >= x.open ? 1 : -1])
  klineChart.setOption({
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
      {
        name: 'K线', type: 'candlestick', data: ohlc,
        itemStyle: { color: '#ef5350', color0: '#27c46b', borderColor: '#ef5350', borderColor0: '#27c46b' },
      },
      {
        name: '成交量', type: 'bar', xAxisIndex: 1, yAxisIndex: 1, data: vols,
        itemStyle: { color: function (p) { return p.data[2] > 0 ? '#ef5350' : '#27c46b' } },
      },
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
    xAxis: { type: 'category', data: curve.map((p) => p.date), axisLabel: { color: '#8ba0c8' } },
    yAxis: { type: 'value', scale: true, axisLabel: { color: '#8ba0c8' }, splitLine: { lineStyle: { color: '#1a2540' } } },
    series: [{
      name: '策略净值', type: 'line', showSymbol: false,
      data: curve.map((p) => p.equity),
      lineStyle: { color: '#4da8ff', width: 2 },
      areaStyle: { color: 'rgba(77,168,255,0.15)' },
    }],
  })
}

function renderEntryPoints(d) {
  // 在K线图上叠加买卖点
  if (!klineChart) return
  const buyPoints = d.trades.filter((t) => t.type === 'BUY')
  const sellPoints = d.trades.filter((t) => t.type === 'SELL')
  // 简单用价格点标注 (此处省略精确坐标映射, 展示买卖次数即可)
}

function fmt(n) {
  return n == null ? '-' : Number(n).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}
function fmtPct(n) {
  return n == null ? '-' : Number(n).toFixed(2) + '%'
}

onMounted(async () => {
  await loadAll()
  window.addEventListener('resize', () => {
    klineChart && klineChart.resize()
    equityChart && equityChart.resize()
  })
})
</script>

<template>
  <div class="container">
    <header class="navbar">
      <div class="brand">
        <span class="brand-icon"></span>
        <span class="brand-name">贾维斯 · 黄金演示</span>
      </div>
      <div class="conn" :class="{ ok: connected }">
        <span class="dot"></span>{{ healthText }}
      </div>
    </header>

    <main>
      <!-- 概览卡 -->
      <section class="cards">
        <div class="card">
          <div class="card-title">标的</div>
          <div class="card-value">{{ markets.length }} 个</div>
          <div class="card-sub">{{ (markets[0] && markets[0].label) || '黄金ETF华夏' }}</div>
        </div>
        <div class="card">
          <div class="card-title">最新价</div>
          <div class="card-value price">
            {{ prices.gold_etf && prices.gold_etf.realtime ? prices.gold_etf.realtime.price : '-' }}
          </div>
          <div class="card-sub">
            <span v-if="prices.gold_etf && prices.gold_etf.realtime">
              涨跌 {{ prices.gold_etf.realtime.change_pct }}%
            </span>
          </div>
        </div>
        <div class="card">
          <div class="card-title">历史K线</div>
          <div class="card-value">{{ storage && storage.kline ? storage.kline[0].n : 0 }} 根</div>
          <div class="card-sub" v-if="rangeInfo">{{ rangeInfo.min }} ~ {{ rangeInfo.max }}</div>
        </div>
      </section>

      <!-- K线图 -->
      <section class="panel">
        <div class="panel-head">
          <h2>黄金ETF历史K线</h2>
          <select v-model.number="limit" @change="loadKline" class="select">
            <option :value="60">60 日</option>
            <option :value="120">120 日</option>
            <option :value="120">全部(120)</option>
          </select>
        </div>
        <div ref="klineRef" class="chart tall"></div>
      </section>

      <!-- 回测 -->
      <section class="panel">
        <div class="panel-head">
          <h2>双均线策略回测</h2>
          <div class="controls">
            <label>短期 <input type="number" v-model.number="bt.short_ma" min="1" class="num" /></label>
            <label>长期 <input type="number" v-model.number="bt.long_ma" min="2" class="num" /></label>
            <label>本金 <input type="number" v-model.number="bt.initial_cash" min="1000" class="num wide" /></label>
            <button class="btn primary" @click="runBacktest" :disabled="bt.running">
              {{ bt.running ? '回测中...' : '运行回测' }}
            </button>
          </div>
        </div>

        <div v-if="btError" class="error">{{ btError }}</div>

        <template v-if="btResult && !btResult.error">
          <!-- 统计指标 -->
          <div class="metrics">
            <div class="metric">
              <div class="m-label">期末资金</div>
              <div class="m-value">{{ fmt(btResult.final_equity) }}</div>
            </div>
            <div class="metric">
              <div class="m-label">总收益率</div>
              <div class="m-value" :class="btResult.total_return_pct >= 0 ? 'pos' : 'neg'">
                {{ fmtPct(btResult.total_return_pct) }}
              </div>
            </div>
            <div class="metric">
              <div class="m-label">年化收益</div>
              <div class="m-value" :class="btResult.annual_return_pct >= 0 ? 'pos' : 'neg'">
                {{ fmtPct(btResult.annual_return_pct) }}
              </div>
            </div>
            <div class="metric">
              <div class="m-label">买入持有</div>
              <div class="m-value" :class="btResult.buy_hold_return_pct >= 0 ? 'pos' : 'neg'">
                {{ fmtPct(btResult.buy_hold_return_pct) }}
              </div>
            </div>
            <div class="metric">
              <div class="m-label">最大回撤</div>
              <div class="m-value neg">{{ fmtPct(btResult.max_drawdown_pct) }}</div>
            </div>
            <div class="metric">
              <div class="m-label">交易次数</div>
              <div class="m-value">{{ btResult.num_trades }}</div>
            </div>
          </div>

          <!-- 净值曲线 -->
          <h3>策略净值曲线</h3>
          <div ref="equityRef" class="chart"></div>

          <!-- 近期交易 -->
          <h3 v-if="btResult.trades.length">近期交易记录</h3>
          <table v-if="btResult.trades.length" class="table">
            <thead>
              <tr><th>日期</th><th>方向</th><th>价格</th><th>数量</th></tr>
            </thead>
            <tbody>
              <tr v-for="(t, i) in btResult.trades" :key="i">
                <td>{{ t.date }}</td>
                <td :class="t.type === 'BUY' ? 'pos' : 'neg'">{{ t.type }}</td>
                <td>{{ t.price }}</td>
                <td>{{ Math.round(t.qty) }}</td>
              </tr>
            </tbody>
          </table>
        </template>
        <div v-else-if="btResult && btResult.error" class="error">{{ btResult.error }}</div>
      </section>

      <footer class="foot">
        <span>后端地址: {{ API_BASE || '(开发代理 /api)' }}</span>
        <span>生产环境请通过 <code>?api=http://&lt;本机IP&gt;:8100</code> 指定后端</span>
      </footer>
    </main>
  </div>
</template>
