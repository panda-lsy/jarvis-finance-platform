<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { api } from '../api/client'
import DataState from './common/DataState.vue'
import TradingTerminal from './trading/TradingTerminal.vue'
import { usePolling } from '../composables/usePolling'

const account = ref(null)
const realtimePrices = ref(null)
const jdPrices = ref(null)
const openOrders = ref([])
const initializing = ref(true)
const submitting = ref(false)
const msg = ref('')
const msgType = ref('info')
let pendingOrderAttempt = null
let closePriceStream = null

function startPriceStream() {
  if (closePriceStream) return
  closePriceStream = api.marketPriceStream(payload => {
    if (payload?.market && Object.keys(payload.market).length) realtimePrices.value = payload.market
    if (payload?.jd && Object.keys(payload.jd).length) jdPrices.value = payload.jd
  })
}

async function loadWorkspace() {
  const [accountResponse, ordersResponse, marketResponse, jdResponse] = await Promise.all([
    api.simAccount(), api.simOpenOrders(), api.marketPrices().catch(() => null), api.jdPrices().catch(() => null),
  ])
  if (accountResponse?.code !== 200) throw new Error(accountResponse?.message || '模拟账户加载失败')
  if (ordersResponse?.code !== 200) throw new Error(ordersResponse?.message || '挂单加载失败')
  account.value = accountResponse.data
  openOrders.value = Array.isArray(ordersResponse.data) ? ordersResponse.data : []
  if (marketResponse?.data) realtimePrices.value = marketResponse.data
  if (jdResponse?.code === 200 && jdResponse.data) jdPrices.value = jdResponse.data
}

function sameAttempt(a, b) {
  return a && a.side === b.side && a.symbol === b.symbol && a.quantity === b.quantity
    && a.leverage === b.leverage && a.orderType === b.orderType
    && Number(a.stopPrice || 0) === Number(b.stopPrice || 0) && a.timeInForce === b.timeInForce
}

async function submitOrder(payload) {
  if (submitting.value) return
  msg.value = ''
  submitting.value = true
  const current = {
    side: payload.side, symbol: payload.symbol, quantity: Number(payload.quantity),
    leverage: payload.side === 'SELL' ? 1 : Number(payload.leverage || 1),
    orderType: payload.orderType || 'MARKET', stopPrice: payload.stopPrice,
    timeInForce: payload.timeInForce || 'DAY',
  }
  if (!sameAttempt(pendingOrderAttempt, current)) pendingOrderAttempt = { ...current, id: crypto.randomUUID() }
  try {
    const response = await api.simOrder(
      current.side, current.symbol, current.quantity, current.leverage, pendingOrderAttempt.id,
      { orderType: current.orderType, stopPrice: current.stopPrice, timeInForce: current.timeInForce },
    )
    if (response.code !== 200) throw new Error(response.message || '订单提交失败')
    pendingOrderAttempt = null
    msg.value = current.orderType === 'STOP_MARKET' ? '止损挂单已提交' : (response.data?.message || response.message || '成交成功')
    msgType.value = 'ok'
    await loadWorkspace()
  } catch (error) {
    msg.value = error?.message || String(error)
    msgType.value = 'error'
  } finally { submitting.value = false }
}

async function updateOrder(payload) {
  if (submitting.value) return
  submitting.value = true
  msg.value = ''
  try {
    const response = await api.simUpdateOrder(payload.id, Number(payload.stopPrice))
    if (response.code !== 200) throw new Error(response.message || '挂单更新失败')
    msg.value = '止损价已更新'
    msgType.value = 'ok'
    await loadWorkspace()
  } catch (error) {
    msg.value = error?.message || String(error)
    msgType.value = 'error'
  } finally { submitting.value = false }
}

async function cancelOrder(orderId) {
  if (submitting.value) return
  submitting.value = true
  msg.value = ''
  try {
    const response = await api.simCancelOrder(orderId)
    if (response.code !== 200) throw new Error(response.message || '撤单失败')
    msg.value = '挂单已撤销'
    msgType.value = 'ok'
    await loadWorkspace()
  } catch (error) {
    msg.value = error?.message || String(error)
    msgType.value = 'error'
  } finally { submitting.value = false }
}

const polling = usePolling(async () => { try { await loadWorkspace() } catch (_) { /* 保留最后一次有效账户与行情 */ } }, 30000)
async function initialize() {
  initializing.value = true
  try { await loadWorkspace() }
  catch (error) { msg.value = error?.message || String(error); msgType.value = 'error' }
  finally { initializing.value = false }
}

onMounted(async () => { await initialize(); startPriceStream(); polling.start() })
onBeforeUnmount(() => { polling.stop(); closePriceStream?.(); closePriceStream = null })
</script>

<template>
  <div class="sim-terminal-page">
    <DataState v-if="initializing && !account" state="loading" title="正在加载模拟交易终端" message="正在同步账户、实时行情与挂单。" />
    <DataState v-else-if="!account" state="error" title="模拟交易终端加载失败" :message="msg || '无法读取模拟账户。'" retryable @retry="initialize" />
    <TradingTerminal v-else :account="account" :realtime-prices="realtimePrices" :jd-prices="jdPrices"
      :open-orders="openOrders" :submitting="submitting" :message="msg" :message-type="msgType"
      @submit="submitOrder" @update-order="updateOrder" @cancel-order="cancelOrder" />
  </div>
</template>

<style scoped>
.sim-terminal-page { margin-top: 2px; }
</style>
