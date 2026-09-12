<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { api } from '../../api/client'
import { useMarketChart } from '../../composables/useMarketChart'

const props = defineProps({
  account: { type: Object, default: null },
  realtimePrices: { type: Object, default: null },
  jdPrices: { type: Object, default: null },
  openOrders: { type: Array, default: () => [] },
  submitting: { type: Boolean, default: false },
  message: { type: String, default: '' },
  messageType: { type: String, default: 'info' },
})

const emit = defineEmits(['submit', 'update-order', 'cancel-order'])

const instruments = [
  { symbol: 'sh518850', ticker: '518850', name: '黄金ETF华夏', market: 'gold_etf', quoteKey: 'gold_etf', kind: 'market', currency: '¥' },
  { symbol: 'hf_XAU', ticker: 'XAU', name: '伦敦金', market: 'london_gold', quoteKey: 'london_gold', kind: 'market', currency: '$' },
  { symbol: 'jd_zheshang', ticker: 'JD-ZS', name: '浙商积存金', market: 'zheshang', quoteKey: 'zheshang', kind: 'jd', currency: '¥' },
  { symbol: 'jd_minsheng', ticker: 'JD-MS', name: '民生积存金', market: 'minsheng', quoteKey: 'minsheng', kind: 'jd', currency: '¥' },
]

const rangeOptions = [
  { key: '1D', limit: 40 },
  { key: '1W', limit: 80 },
  { key: '1M', limit: 120 },
  { key: '3M', limit: 160 },
  { key: 'YTD', limit: 200 },
  { key: '1Y', limit: 220 },
  { key: '5Y', limit: 250 },
  { key: 'All', limit: 250 },
]

const intervalOptions = [
  { value: '1', label: '1m' },
  { value: '5', label: '5m' },
  { value: '15', label: '15m' },
  { value: '30', label: '30m' },
  { value: '60', label: '1h' },
  { value: 'day', label: '1D' },
]

const activeSymbol = ref('sh518850')
const selectedRange = ref('1D')
const selectedInterval = ref('15')
const klineData = ref([])
const chartError = ref('')
const chartLoading = ref(false)
const modalOpen = ref(false)
const editingOrderId = ref(null)
const positionMarkerY = ref(null)
const stopMarkerY = ref(null)
const orderState = reactive({
  side: 'SELL',
  orderType: 'STOP_MARKET',
  quantity: 1,
  leverage: 1,
  stopPrice: 0,
  timeInForce: 'DAY',
})

const marketChart = useMarketChart()
const chartRef = marketChart.elementRef

const activeInstrument = computed(() => instruments.find(item => item.symbol === activeSymbol.value) || instruments[0])
const currentQuote = computed(() => activeInstrument.value.kind === 'jd'
  ? props.jdPrices?.[activeInstrument.value.quoteKey] || null
  : props.realtimePrices?.[activeInstrument.value.quoteKey] || null)
const currentPrice = computed(() => Number(currentQuote.value?.price || 0))
const quoteStale = computed(() => Boolean(currentQuote.value?.stale))
const activePosition = computed(() => props.account?.positions?.[activeSymbol.value] || null)
const availableQuantity = computed(() => Number(activePosition.value?.quantity || 0))
const activeOpenOrder = computed(() => props.openOrders.find(order =>
  order?.symbol === activeSymbol.value && ['OPEN', 'TRIGGERING'].includes(String(order?.status || '').toUpperCase())) || null)
const activeRange = computed(() => rangeOptions.find(item => item.key === selectedRange.value) || rangeOptions[0])
const visibleIntervals = computed(() => activeInstrument.value.kind === 'jd'
  ? intervalOptions.filter(item => item.value !== 'day')
  : intervalOptions)
const latestBar = computed(() => klineData.value.at(-1) || {})
const livePnl = computed(() => {
  const position = activePosition.value
  if (!position || currentPrice.value <= 0) return Number(position?.profit || 0)
  return (currentPrice.value - Number(position.avgCost || 0)) * Number(position.quantity || 0)
})
const previewStop = computed(() => modalOpen.value && orderState.orderType === 'STOP_MARKET'
  ? Number(orderState.stopPrice || 0)
  : Number(activeOpenOrder.value?.stopPrice || 0))
const positionPrice = computed(() => Number(activePosition.value?.avgCost || activePosition.value?.currentPrice || 0))
const estimatedAmount = computed(() => {
  const px = orderState.orderType === 'STOP_MARKET' ? Number(orderState.stopPrice || 0) : currentPrice.value
  return px * Math.max(0, Number(orderState.quantity || 0))
})
const stopRelationError = computed(() => {
  if (orderState.orderType !== 'STOP_MARKET' || currentPrice.value <= 0 || Number(orderState.stopPrice || 0) <= 0) return ''
  if (orderState.side === 'SELL' && Number(orderState.stopPrice) >= currentPrice.value) return '卖出止损价需低于当前价'
  if (orderState.side === 'BUY' && Number(orderState.stopPrice) <= currentPrice.value) return '买入止损价需高于当前价'
  return ''
})
const canSubmit = computed(() => {
  const qty = Number(orderState.quantity || 0)
  if (props.submitting || qty <= 0 || currentPrice.value <= 0 || quoteStale.value) return false
  if (orderState.side === 'SELL' && qty > availableQuantity.value) return false
  if (orderState.orderType === 'STOP_MARKET' && (Number(orderState.stopPrice || 0) <= 0 || stopRelationError.value)) return false
  return true
})

function priceDigits() {
  return activeInstrument.value.kind === 'jd' ? 2 : 3
}

function formatPrice(value) {
  const number = Number(value)
  if (!Number.isFinite(number) || number <= 0) return '—'
  return number.toFixed(priceDigits())
}

function formatMoney(value) {
  const number = Number(value || 0)
  if (!Number.isFinite(number)) return `${activeInstrument.value.currency}0.00`
  return `${activeInstrument.value.currency}${number.toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`
}

function signed(value, digits = 2) {
  const number = Number(value || 0)
  const prefix = number > 0 ? '+' : ''
  return `${prefix}${number.toFixed(digits)}`
}

function signedMoney(value) {
  const number = Number(value || 0)
  const prefix = number > 0 ? '+' : number < 0 ? '−' : ''
  return `${prefix}${activeInstrument.value.currency}${Math.abs(number).toFixed(3)}`
}

function cycleSymbol() {
  const index = instruments.findIndex(item => item.symbol === activeSymbol.value)
  activeSymbol.value = instruments[(index + 1) % instruments.length].symbol
}

function setRange(key) {
  selectedRange.value = key
}

async function loadKline() {
  chartLoading.value = true
  chartError.value = ''
  try {
    let rows = []
    const instrument = activeInstrument.value
    const limit = activeRange.value.limit
    if (instrument.kind === 'jd') {
      const response = await api.jdKline(instrument.market, Number(selectedInterval.value), Math.min(limit, 500))
      const body = response?.data?.data ? response.data : response
      rows = body?.data || []
    } else {
      const response = await api.marketKline({ market: instrument.market, interval: selectedInterval.value, limit })
      const body = response?.data
      rows = Array.isArray(body) ? body : (body?.data || [])
    }
    klineData.value = Array.isArray(rows) ? rows : []
    await nextTick()
    await renderChart()
  } catch (error) {
    chartError.value = error?.message || String(error)
    klineData.value = []
    positionMarkerY.value = null
    stopMarkerY.value = null
    marketChart.clear()
  } finally {
    chartLoading.value = false
  }
}

async function renderChart() {
  if (!klineData.value.length) return
  await marketChart.renderCandles(klineData.value, {
    withVolume: true,
    visibleCount: activeRange.value.limit,
    riseColor: '#b9ff22',
    fallColor: '#ff4d52',
    showLegend: false,
    showSlider: false,
    xLabelsOnVolume: true,
    gridColor: 'rgba(255,255,255,.032)',
    axisColor: '#8c9299',
  })
  attachChartEvents()
  renderAnnotations()
}

function attachChartEvents() {
  const instance = marketChart.getChart()
  if (!instance) return
  instance.off('click', handleChartClick)
  instance.off('datazoom', syncAnnotationPixels)
  instance.off('finished', syncAnnotationPixels)
  instance.on('click', handleChartClick)
  instance.on('datazoom', syncAnnotationPixels)
  instance.on('finished', syncAnnotationPixels)
}

function handleChartClick(params) {
  if (params?.dataType === 'markLine' && params?.name === 'stop-order') editActiveStop()
}

function toYAxisPixel(price) {
  const instance = marketChart.getChart()
  if (!instance || !Number.isFinite(Number(price)) || Number(price) <= 0) return null
  const pixel = instance.convertToPixel({ yAxisIndex: 0 }, Number(price))
  const value = Array.isArray(pixel) ? pixel.at(-1) : pixel
  return Number.isFinite(Number(value)) ? Number(value) : null
}

function syncAnnotationPixels() {
  positionMarkerY.value = activePosition.value ? toYAxisPixel(positionPrice.value) : null
  stopMarkerY.value = previewStop.value > 0 ? toYAxisPixel(previewStop.value) : null
}

function renderAnnotations() {
  const instance = marketChart.getChart()
  if (!instance) return
  const lines = []

  if (activePosition.value && Number(activePosition.value.quantity || 0) > 0 && positionPrice.value > 0) {
    lines.push({
      name: 'position',
      yAxis: positionPrice.value,
      lineStyle: { color: '#5d6d35', width: 1, type: 'dashed' },
      label: { show: false },
    })
  }

  if (previewStop.value > 0) {
    lines.push({
      name: 'stop-order',
      yAxis: previewStop.value,
      lineStyle: { color: '#6a383b', width: 1, type: 'dashed' },
      label: { show: false },
    })
  }

  const bandHalf = previewStop.value > 0
    ? Math.max(previewStop.value * 0.00035, activeInstrument.value.kind === 'jd' ? 0.04 : 0.004)
    : 0

  instance.setOption({
    series: [{
      name: 'K线',
      markLine: {
        silent: false,
        animation: false,
        symbol: ['none', 'none'],
        data: lines,
      },
      markArea: {
        silent: true,
        animation: false,
        itemStyle: { color: 'rgba(255,255,255,.035)' },
        data: previewStop.value > 0 ? [[
          { yAxis: previewStop.value - bandHalf },
          { yAxis: previewStop.value + bandHalf },
        ]] : [],
      },
    }],
  }, false)
  requestAnimationFrame(syncAnnotationPixels)
}

function resetOrder(side) {
  const positionQty = availableQuantity.value
  orderState.side = side
  orderState.orderType = side === 'SELL' && positionQty > 0 ? 'STOP_MARKET' : 'MARKET'
  orderState.quantity = side === 'SELL' && positionQty > 0 ? Math.max(1, Math.min(positionQty, 8)) : 1
  orderState.leverage = 1
  const px = currentPrice.value
  orderState.stopPrice = px > 0
    ? Number((side === 'SELL' ? px * 0.993 : px * 1.007).toFixed(priceDigits()))
    : 0
  orderState.timeInForce = 'DAY'
  editingOrderId.value = null
}

function openOrder(side) {
  resetOrder(side)
  modalOpen.value = true
  nextTick(renderAnnotations)
}

function closeModal() {
  modalOpen.value = false
  editingOrderId.value = null
  nextTick(renderAnnotations)
}

function editActiveStop() {
  const order = activeOpenOrder.value
  if (!order || String(order.status).toUpperCase() !== 'OPEN') return
  orderState.side = order.side
  orderState.orderType = order.orderType
  orderState.quantity = Number(order.quantity)
  orderState.leverage = Number(order.leverage || 1)
  orderState.stopPrice = Number(order.stopPrice)
  orderState.timeInForce = order.timeInForce || 'DAY'
  editingOrderId.value = order.id
  modalOpen.value = true
  nextTick(renderAnnotations)
}

function stepQuantity(delta) {
  const next = Math.max(1, Number(orderState.quantity || 0) + delta)
  orderState.quantity = orderState.side === 'SELL' && availableQuantity.value > 0
    ? Math.min(next, availableQuantity.value)
    : next
}

function stepStop(delta) {
  const step = activeInstrument.value.kind === 'jd' ? 0.01 : 0.001
  orderState.stopPrice = Math.max(step, Number((Number(orderState.stopPrice || 0) + delta * step).toFixed(priceDigits())))
}

function submitOrder() {
  if (!canSubmit.value) return
  if (editingOrderId.value) {
    emit('update-order', { id: editingOrderId.value, stopPrice: Number(orderState.stopPrice) })
    return
  }
  emit('submit', {
    side: orderState.side,
    symbol: activeSymbol.value,
    quantity: Number(orderState.quantity),
    leverage: Number(orderState.leverage || 1),
    orderType: orderState.orderType,
    stopPrice: orderState.orderType === 'STOP_MARKET' ? Number(orderState.stopPrice) : undefined,
    timeInForce: orderState.timeInForce,
  })
}

function cancelEditingOrder() {
  if (!editingOrderId.value) return
  emit('cancel-order', editingOrderId.value)
}

watch([activeSymbol, selectedInterval, selectedRange], async ([, interval]) => {
  if (activeInstrument.value.kind === 'jd' && interval === 'day') {
    selectedInterval.value = '15'
    return
  }
  await loadKline()
})

watch(() => [props.account, props.openOrders, currentQuote.value?.price, modalOpen.value, orderState.stopPrice], () => {
  renderAnnotations()
}, { deep: false })

watch(() => props.submitting, (busy, wasBusy) => {
  if (wasBusy && !busy && props.messageType === 'ok') closeModal()
})

onMounted(loadKline)
onBeforeUnmount(() => {
  const instance = marketChart.getChart()
  instance?.off('click', handleChartClick)
  instance?.off('datazoom', syncAnnotationPixels)
  instance?.off('finished', syncAnnotationPixels)
})
</script>

<template>
  <section class="trade-terminal">
    <header class="terminal-topbar">
      <button type="button" class="symbol-block" title="切换标的" @click="cycleSymbol">
        <span class="symbol-square"></span>
        <b>{{ activeInstrument.ticker }}</b>
        <span class="symbol-price">{{ activeInstrument.currency }}{{ formatPrice(currentPrice) }}</span>
        <span class="symbol-change" :class="Number(currentQuote?.change || 0) >= 0 ? 'up' : 'down'">
          {{ Number(currentQuote?.change || 0) >= 0 ? '▲' : '▼' }}
          {{ signed(currentQuote?.change, 2) }} ({{ signed(currentQuote?.change_pct, 2) }}%)
        </span>
      </button>

      <div class="chart-toolbar" aria-label="图表工具">
        <button type="button" title="趋势线">⌁</button>
        <button type="button" title="矩形">▭</button>
        <button type="button" title="水平线">≋</button>
        <button type="button" title="标注">◯</button>
        <button type="button" title="吸附">∪</button>
        <button type="button" title="指标">ƒ</button>
        <button type="button" title="设置">⚙</button>
        <button type="button" title="更多">⋮</button>
      </div>
    </header>

    <div class="quote-row">
      <div class="trade-actions">
        <button type="button" class="quick buy" @click="openOrder('BUY')">Buy</button>
        <button type="button" class="quick sell" @click="openOrder('SELL')">Sell</button>
      </div>
      <div class="ohlcv">
        <span>O <b>{{ formatPrice(latestBar.open) }}</b></span>
        <span>H <b>{{ formatPrice(latestBar.high) }}</b></span>
        <span>L <b>{{ formatPrice(latestBar.low) }}</b></span>
        <span>C <b>{{ formatPrice(latestBar.close) }}</b></span>
        <span>V <b>{{ Number(latestBar.volume || 0).toLocaleString() }}</b></span>
      </div>
      <span v-if="quoteStale" class="stale-badge">行情陈旧</span>
    </div>

    <div class="chart-stage">
      <div ref="chartRef" class="terminal-chart"></div>

      <template v-if="positionMarkerY !== null && activePosition">
        <div class="chart-marker position-marker" :style="{ top: `${positionMarkerY}px` }">
          <span>{{ Number(activePosition.quantity || 0).toFixed(0) }}</span>
          <strong :class="livePnl >= 0 ? 'up' : 'down'">{{ signedMoney(livePnl) }}</strong>
          <span class="marker-close" aria-hidden="true">×</span>
        </div>
        <div class="axis-price-tag position-price-tag" :style="{ top: `${positionMarkerY}px` }">
          {{ formatPrice(positionPrice) }}
        </div>
      </template>

      <template v-if="stopMarkerY !== null && previewStop > 0">
        <button type="button" class="chart-marker stop-marker" :style="{ top: `${stopMarkerY}px` }" @click="editActiveStop">
          Stop
        </button>
        <div class="axis-price-tag stop-price-tag" :style="{ top: `${stopMarkerY}px` }">
          {{ formatPrice(previewStop) }}
        </div>
      </template>

      <div v-if="chartLoading" class="chart-state">正在加载行情…</div>
      <div v-else-if="chartError" class="chart-state error">
        <span>{{ chartError }}</span>
        <button type="button" @click="loadKline">重试</button>
      </div>
      <div v-else-if="!klineData.length" class="chart-state">暂无可绘制的 K 线数据</div>
    </div>

    <footer class="terminal-rangebar">
      <nav class="range-list" aria-label="图表范围">
        <button v-for="item in rangeOptions" :key="item.key" type="button"
                :class="{ active: selectedRange === item.key }" @click="setRange(item.key)">
          {{ item.key }}
        </button>
      </nav>
      <label class="interval-picker">
        <span>Interval:</span>
        <select v-model="selectedInterval" aria-label="K线周期">
          <option v-for="item in visibleIntervals" :key="item.value" :value="item.value">{{ item.label }}</option>
        </select>
      </label>
      <button v-if="activeOpenOrder" type="button" class="open-order-chip" @click="editActiveStop">
        {{ activeOpenOrder.status === 'TRIGGERING' ? '触发中' : 'Stop' }} {{ formatPrice(activeOpenOrder.stopPrice) }}
      </button>
    </footer>

    <div v-if="modalOpen" class="modal-layer" @mousedown.self="closeModal">
      <section class="order-modal" role="dialog" aria-modal="true" aria-label="模拟交易订单">
        <button type="button" class="modal-close" aria-label="关闭" @click="closeModal">×</button>
        <div class="modal-title">
          <b>{{ activeInstrument.ticker }}</b>
          <span>{{ activeInstrument.currency }}{{ formatPrice(currentPrice) }}</span>
        </div>

        <div class="side-switch">
          <button type="button" :class="{ active: orderState.side === 'BUY', buy: orderState.side === 'BUY' }"
                  :disabled="Boolean(editingOrderId)" @click="orderState.side = 'BUY'">Buy</button>
          <button type="button" :class="{ active: orderState.side === 'SELL', sell: orderState.side === 'SELL' }"
                  :disabled="Boolean(editingOrderId)" @click="orderState.side = 'SELL'">Sell</button>
        </div>

        <div class="order-grid">
          <label>Order type</label>
          <select v-model="orderState.orderType" :disabled="Boolean(editingOrderId)">
            <option value="MARKET">Market</option>
            <option value="STOP_MARKET">Stop market</option>
          </select>

          <div class="field-label">
            <label>Quantity</label>
            <small v-if="orderState.side === 'SELL'">{{ availableQuantity }} available</small>
          </div>
          <div class="step-input">
            <input v-model.number="orderState.quantity" type="number" min="1" step="1" :disabled="Boolean(editingOrderId)" />
            <div class="step-buttons">
              <button type="button" :disabled="Boolean(editingOrderId)" @click="stepQuantity(1)">+</button>
              <button type="button" :disabled="Boolean(editingOrderId)" @click="stepQuantity(-1)">−</button>
            </div>
          </div>

          <div v-if="orderState.orderType === 'STOP_MARKET'" class="field-label">
            <label>Stop price</label>
            <small>Bid {{ formatPrice(currentQuote?.bid || currentPrice) }} · Ask {{ formatPrice(currentQuote?.ask || currentPrice) }}</small>
          </div>
          <div v-if="orderState.orderType === 'STOP_MARKET'" class="step-input" :class="{ invalid: stopRelationError }">
            <input v-model.number="orderState.stopPrice" type="number" min="0" :step="activeInstrument.kind === 'jd' ? '0.01' : '0.001'" />
            <div class="step-buttons">
              <button type="button" @click="stepStop(1)">+</button>
              <button type="button" @click="stepStop(-1)">−</button>
            </div>
          </div>

          <label>Time in force</label>
          <select v-model="orderState.timeInForce" :disabled="Boolean(editingOrderId)">
            <option value="DAY">Good for day</option>
            <option value="GTC">Good till cancelled</option>
          </select>
        </div>

        <div class="order-summary">
          <div class="summary-line">
            <b>{{ orderState.side === 'SELL' ? 'Estimated credit' : 'Estimated debit' }}</b>
            <strong>{{ formatMoney(estimatedAmount) }}</strong>
          </div>
          <p v-if="orderState.orderType === 'STOP_MARKET'">
            当 {{ activeInstrument.ticker }} 到达 {{ formatPrice(orderState.stopPrice) }}，订单将转为市价单。
            挂单保存在服务端，关闭页面后仍会继续监控。当前行情 {{ formatPrice(currentPrice) }}。
          </p>
          <p v-else>市价单将使用当前可用实时行情成交。行情陈旧时系统会拒绝成交。</p>
          <p v-if="stopRelationError" class="validation-hint">{{ stopRelationError }}</p>
          <button v-if="editingOrderId" type="button" class="cancel-order-link" @click="cancelEditingOrder">撤销该挂单</button>
        </div>

        <div v-if="message" class="order-message" :class="messageType">{{ message }}</div>

        <footer class="modal-actions">
          <button type="button" class="cancel" @click="closeModal">Cancel</button>
          <button type="button" class="confirm" :class="orderState.side === 'SELL' ? 'sell' : 'buy'"
                  :disabled="!canSubmit" @click="submitOrder">
            {{ props.submitting ? 'Submitting…' : editingOrderId ? 'Update order' : `${orderState.side === 'SELL' ? 'Sell' : 'Buy'} ${activeInstrument.ticker}` }}
          </button>
        </footer>
      </section>
    </div>
  </section>
</template>

<style scoped>
.trade-terminal {
  --trade-bg: #111214;
  --trade-panel: #1b1c1f;
  --trade-border: #303236;
  --trade-text: #e7e8ea;
  --trade-muted: #8b8e94;
  --trade-subtle: #64696f;
  --trade-buy: #b9ff22;
  --trade-sell: #ff4d52;
  position: relative;
  min-height: 720px;
  overflow: hidden;
  border: 1px solid var(--trade-border);
  border-radius: 13px;
  background: var(--trade-bg);
  color: var(--trade-text);
  box-shadow: 0 24px 70px rgba(0,0,0,.28);
}
.terminal-topbar { height: 45px; display: flex; align-items: center; justify-content: space-between; border-bottom: 1px solid var(--trade-border); background: #1b1c20; }
.symbol-block { height: 100%; display: flex; align-items: center; gap: 8px; padding: 0 16px; color: var(--trade-text); background: transparent; border: 0; border-right: 1px solid var(--trade-border); cursor: pointer; font-variant-numeric: tabular-nums; }
.symbol-square { width: 16px; height: 16px; border-radius: 3px; background: #ff8a00; box-shadow: 0 0 0 1px rgba(255,255,255,.08) inset; }
.symbol-block b { font-size: 15px; letter-spacing: .01em; }
.symbol-price { color: #a7abb0; font-size: 14px; }
.symbol-change { font-size: 13px; font-weight: 650; }
.symbol-change.up, .position-marker strong.up { color: var(--trade-buy); }
.symbol-change.down, .position-marker strong.down { color: var(--trade-sell); }
.chart-toolbar { height: 100%; display: flex; align-items: center; gap: 3px; padding-right: 10px; }
.chart-toolbar button { width: 38px; height: 34px; border: 0; border-radius: 4px; background: transparent; color: #a5a8ad; font-size: 18px; cursor: pointer; }
.chart-toolbar button:hover { color: #f1f2f3; background: rgba(255,255,255,.05); }
.quote-row { height: 51px; display: flex; align-items: center; gap: 18px; padding: 0 12px; border-bottom: 1px solid #22252a; background: #17181b; }
.trade-actions { display: flex; gap: 6px; }
.quick { min-width: 96px; height: 32px; border: 0; border-radius: 5px; background: #2a2b2f; font-size: 13px; font-weight: 750; cursor: pointer; }
.quick.buy { color: var(--trade-buy); }
.quick.sell { color: var(--trade-sell); }
.quick:hover { background: #323338; }
.ohlcv { display: flex; align-items: center; gap: 10px; color: #d9dbde; font-size: 12px; font-variant-numeric: tabular-nums; }
.ohlcv b { color: #ff666b; }
.stale-badge { margin-left: auto; color: #e1b25d; font-size: 10px; }
.chart-stage { position: relative; height: clamp(535px, calc(100vh - 285px), 700px); min-height: 535px; background: #111214; }
.terminal-chart { width: 100%; height: 100%; border: 0; background: transparent; }
.chart-state { position: absolute; inset: 0; z-index: 12; display: flex; align-items: center; justify-content: center; gap: 10px; color: var(--trade-muted); background: rgba(17,18,20,.72); font-size: 12px; }
.chart-state.error { color: #ef777b; }
.chart-state button { border: 1px solid var(--trade-border); border-radius: 4px; background: #232529; color: var(--trade-text); padding: 5px 9px; cursor: pointer; }
.chart-marker, .axis-price-tag { position: absolute; z-index: 9; transform: translateY(-50%); font-variant-numeric: tabular-nums; }
.position-marker { right: 78px; display: flex; align-items: center; gap: 6px; min-height: 29px; padding: 0 8px; border: 1px solid #303438; border-radius: 5px; background: #24272a; color: #e5e7e9; font-size: 11px; font-weight: 700; pointer-events: none; box-shadow: 0 6px 18px rgba(0,0,0,.18); }
.marker-close { color: #777c82; font-size: 15px; font-weight: 500; margin-left: 1px; }
.stop-marker { right: 150px; min-width: 68px; height: 29px; border: 1px solid #3a3d41; border-radius: 5px; background: #25272a; color: var(--trade-sell); font-size: 11px; font-weight: 760; cursor: pointer; box-shadow: 0 6px 18px rgba(0,0,0,.18); }
.axis-price-tag { right: 4px; min-width: 56px; padding: 4px 6px; border-radius: 4px; background: #394047; color: #f3f4f5; text-align: center; font-size: 11px; font-weight: 750; pointer-events: none; }
.stop-price-tag { background: #343940; }
.terminal-rangebar { min-height: 49px; display: flex; align-items: center; gap: 18px; padding: 0 18px; border-top: 1px solid #25282c; background: #151619; }
.range-list { display: flex; align-items: center; gap: 4px; }
.range-list button { border: 0; background: transparent; color: #8f9399; padding: 7px 8px; font-size: 11px; font-weight: 650; cursor: pointer; }
.range-list button.active { color: #f4f4f4; }
.interval-picker { display: flex; align-items: center; gap: 5px; border-left: 1px solid #35383d; padding-left: 17px; color: #8f9399; font-size: 11px; }
.interval-picker select { appearance: none; border: 0; outline: none; background: transparent; color: #f0f1f2; font-weight: 750; padding: 4px 18px 4px 2px; cursor: pointer; }
.interval-picker select option { color: #f0f1f2; background: #202226; }
.open-order-chip { margin-left: auto; border: 1px solid #553338; border-radius: 5px; background: #241c1e; color: #ff656a; padding: 5px 9px; font-size: 10px; font-weight: 700; cursor: pointer; }
.modal-layer { position: absolute; inset: 0; z-index: 20; display: grid; place-items: center; background: rgba(0,0,0,.10); backdrop-filter: blur(1.5px); }
.order-modal { position: relative; width: min(432px, calc(100% - 28px)); transform: translateY(-2%); border: 1px solid #34363a; border-radius: 7px; background: rgba(28,29,31,.972); box-shadow: 0 28px 80px rgba(0,0,0,.58); overflow: hidden; }
.modal-close { position: absolute; top: 10px; right: 13px; width: 30px; height: 30px; border: 0; background: transparent; color: #b9bcc0; font-size: 25px; line-height: 1; cursor: pointer; }
.modal-title { display: flex; align-items: baseline; gap: 8px; padding: 36px 22px 15px; font-variant-numeric: tabular-nums; }
.modal-title b { font-size: 20px; }
.modal-title span { color: #aaaeb3; font-size: 20px; }
.side-switch { display: grid; grid-template-columns: 1fr 1fr; gap: 4px; margin: 0 22px 18px; padding: 4px; border-radius: 6px; background: #26272b; }
.side-switch button { height: 38px; border: 0; border-radius: 5px; background: transparent; color: #e1e2e4; font-size: 13px; cursor: pointer; }
.side-switch button.active.sell { background: var(--trade-sell); color: #111; font-weight: 800; }
.side-switch button.active.buy { background: var(--trade-buy); color: #111; font-weight: 800; }
.side-switch button:disabled { cursor: default; }
.order-grid { display: grid; grid-template-columns: 1fr 190px; gap: 14px 18px; align-items: center; padding: 0 22px 18px; }
.order-grid > label, .field-label > label { color: #eff0f2; font-size: 13px; }
.field-label { display: flex; flex-direction: column; gap: 2px; }
.field-label small { color: #8e9298; font-size: 11px; }
.order-grid select, .step-input { min-height: 42px; border: 1px solid #35373b; border-radius: 5px; background: #303135; color: #f0f1f2; }
.order-grid select { padding: 0 12px; outline: none; }
.step-input { display: grid; grid-template-columns: 1fr 34px; overflow: hidden; }
.step-input.invalid { border-color: #784046; }
.step-input input { width: 100%; border: 0; outline: none; background: transparent; color: #f2f2f2; padding: 0 11px; font-variant-numeric: tabular-nums; }
.step-buttons { display: grid; grid-template-rows: 1fr 1fr; border-left: 1px solid #44464b; }
.step-buttons button { border: 0; background: #3a3b3f; color: #d9dcdf; line-height: 1; cursor: pointer; }
.step-buttons button + button { border-top: 1px solid #4b4d52; }
.order-summary { border-top: 1px solid #393b3f; border-bottom: 1px solid #393b3f; padding: 18px 22px; }
.summary-line { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.summary-line b, .summary-line strong { color: #f2f2f3; font-size: 14px; }
.order-summary p { margin: 15px 0 0; color: #989ca2; font-size: 11px; line-height: 1.55; }
.order-summary .validation-hint { margin-top: 8px; color: #ef777b; }
.cancel-order-link { margin-top: 10px; padding: 0; border: 0; background: transparent; color: #e76569; text-decoration: underline; font-size: 10px; cursor: pointer; }
.order-message { margin: 12px 22px 0; border-radius: 4px; padding: 8px 10px; background: #222428; color: #a7abb0; font-size: 10px; }
.order-message.ok { color: #8ad7aa; }
.order-message.error { color: #ef777b; }
.modal-actions { display: flex; justify-content: flex-end; gap: 10px; padding: 14px 14px 14px 22px; }
.modal-actions button { min-width: 92px; height: 40px; border: 0; border-radius: 5px; font-weight: 750; cursor: pointer; }
.modal-actions .cancel { background: #343539; color: #f0f1f2; }
.modal-actions .confirm.sell { background: var(--trade-sell); color: #101112; }
.modal-actions .confirm.buy { background: var(--trade-buy); color: #101112; }
.modal-actions .confirm:disabled { opacity: .45; cursor: not-allowed; }
@media (max-width: 760px) {
  .trade-terminal { min-height: 650px; border-radius: 8px; }
  .symbol-block { padding-left: 10px; }
  .symbol-change { display: none; }
  .chart-toolbar button:nth-child(-n+4) { display: none; }
  .quote-row { gap: 10px; }
  .quick { min-width: 70px; }
  .ohlcv { overflow: hidden; gap: 6px; font-size: 10px; }
  .chart-stage { height: 500px; min-height: 500px; }
  .terminal-rangebar { overflow-x: auto; gap: 10px; padding: 0 10px; }
  .range-list { min-width: max-content; }
  .open-order-chip { display: none; }
  .order-grid { grid-template-columns: 1fr 160px; gap: 12px; }
  .position-marker { right: 70px; }
  .stop-marker { right: 132px; }
}
@media (max-width: 500px) {
  .chart-toolbar { display: none; }
  .symbol-block { border-right: 0; }
  .symbol-price { font-size: 12px; }
  .quote-row { height: auto; align-items: flex-start; flex-direction: column; padding: 8px; }
  .ohlcv { width: 100%; }
  .chart-stage { height: 450px; min-height: 450px; }
  .order-grid { grid-template-columns: 1fr; }
  .order-grid > label, .field-label { margin-bottom: -6px; }
  .chart-marker, .axis-price-tag { display: none; }
}
</style>
