<script setup>
import { ref, reactive, onMounted } from 'vue'
import { api } from '../api/client'

const account = ref(null)
const trades = ref([])
const loading = ref(true)
const msg = ref('')
const msgType = ref('info')

const order = reactive({
  symbol: 'sh518850',
  type: 'BUY',
  quantity: 100,
})

async function load() {
  try {
    const [acc, tr] = await Promise.all([api.simAccount(), api.simTrades(20)])
    account.value = acc.data
    trades.value = tr.data?.trades || []
  } catch (e) {
    msg.value = '加载失败: ' + e
    msgType.value = 'error'
  } finally {
    loading.value = false
  }
}

async function submitOrder() {
  msg.value = ''
  try {
    const res = await api.simOrder(order.type, order.symbol, Number(order.quantity))
    if (res.code === 200) {
      msg.value = res.message + ' | ' + res.data.message
      msgType.value = 'ok'
      await load()
    } else {
      msg.value = res.message
      msgType.value = 'error'
    }
  } catch (e) {
    msg.value = '下单失败: ' + e
    msgType.value = 'error'
  }
}

function fmt(n) {
  return n == null ? '-' : Number(n).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}
function fmtPct(n) {
  return n == null ? '-' : Number(n).toFixed(2) + '%'
}
function posClass(n) { return n >= 0 ? 'pos' : 'neg' }

onMounted(load)
</script>

<template>
  <div class="sim">
    <!-- 账户概览 -->
    <div v-if="account" class="cards">
      <div class="card">
        <div class="card-title">总资产</div>
        <div class="card-value">{{ fmt(account.totalAssets) }}</div>
      </div>
      <div class="card">
        <div class="card-title">可用资金</div>
        <div class="card-value accent">{{ fmt(account.cash) }}</div>
      </div>
      <div class="card">
        <div class="card-title">持仓市值</div>
        <div class="card-value">{{ fmt(account.marketValue) }}</div>
      </div>
      <div class="card">
        <div class="card-title">总收益率</div>
        <div class="card-value" :class="posClass(account.totalReturnPct)">
          {{ fmtPct(account.totalReturnPct) }}
        </div>
      </div>
    </div>

    <!-- 下单 -->
    <div class="panel">
      <div class="panel-head"><h2>模拟盘下单</h2></div>
      <div class="order-form">
        <select v-model="order.symbol" class="select">
          <option value="sh518850">黄金ETF华夏 (sh518850)</option>
        </select>
        <div class="type-toggle">
          <button :class="['btn', order.type === 'BUY' ? 'buy' : '']" @click="order.type = 'BUY'">买入</button>
          <button :class="['btn', order.type === 'SELL' ? 'sell' : '']" @click="order.type = 'SELL'">卖出</button>
        </div>
        <input type="number" v-model.number="order.quantity" class="num wide" min="1" placeholder="数量" />
        <button class="btn primary" @click="submitOrder">提交 {{ order.type === 'BUY' ? '买入' : '卖出' }}</button>
      </div>
      <div v-if="msg" class="msg" :class="msgType">{{ msg }}</div>
    </div>

    <!-- 当前持仓 -->
    <div class="panel" v-if="account && Object.keys(account.positions).length">
      <div class="panel-head"><h2>当前持仓</h2></div>
      <table class="table">
        <thead><tr><th>标的</th><th>数量</th><th>成本</th><th>现价</th><th>市值</th><th>盈亏</th><th>盈亏%</th></tr></thead>
        <tbody>
          <tr v-for="(p, sym) in account.positions" :key="sym">
            <td>{{ sym }}</td>
            <td>{{ p.quantity }}</td>
            <td>{{ p.avgCost }}</td>
            <td>{{ p.currentPrice }}</td>
            <td>{{ fmt(p.marketValue) }}</td>
            <td :class="posClass(p.profit)">{{ fmt(p.profit) }}</td>
            <td :class="posClass(p.profitPct)">{{ fmtPct(p.profitPct) }}</td>
          </tr>
        </tbody>
      </table>
    </div>
    <div v-else class="panel empty">暂无持仓，买入第一笔开始模拟交易</div>

    <!-- 交易记录 -->
    <div class="panel" v-if="trades.length">
      <div class="panel-head"><h2>交易记录</h2></div>
      <table class="table">
        <thead><tr><th>时间</th><th>标的</th><th>方向</th><th>价格</th><th>数量</th><th>金额</th></tr></thead>
        <tbody>
          <tr v-for="t in trades" :key="t.id">
            <td>{{ t.createdAt?.replace('T', ' ').slice(0, 19) }}</td>
            <td>{{ t.symbol }}</td>
            <td :class="t.type === 'BUY' ? 'pos' : 'neg'">{{ t.type === 'BUY' ? '买入' : '卖出' }}</td>
            <td>{{ t.price }}</td>
            <td>{{ t.quantity }}</td>
            <td>{{ fmt(t.amount) }}</td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>

<style scoped>
.sim { display: flex; flex-direction: column; gap: 20px; }
.cards { display: grid; grid-template-columns: repeat(auto-fit, minmax(160px, 1fr)); gap: 16px; }
.card { background: #121a2d; border: 1px solid #243453; border-radius: 12px; padding: 18px 20px; }
.card-title { color: #8ba0c8; font-size: 13px; margin-bottom: 8px; }
.card-value { font-size: 26px; font-weight: 700; }
.card-value.accent { color: #4da8ff; }
.panel { background: #121a2d; border: 1px solid #243453; border-radius: 12px; padding: 20px; }
.panel-head h2 { margin: 0 0 16px; font-size: 18px; }
.panel.empty { color: #5a6b8c; text-align: center; padding: 30px; }
.order-form { display: flex; gap: 12px; flex-wrap: wrap; align-items: center; }
.select, .num { background: #0f1626; border: 1px solid #243453; color: #e9effb; border-radius: 6px; padding: 9px 12px; }
.num.wide { width: 120px; }
.type-toggle { display: flex; gap: 8px; }
.btn.buy { background: rgba(39,196,107,.15); color: #27c46b; border-color: #27c46b; }
.btn.sell { background: rgba(239,83,80,.15); color: #ef5350; border-color: #ef5350; }
.btn.primary { background: linear-gradient(135deg, #4da8ff, #a842ff); border: none; color: #fff; padding: 9px 18px; }
.msg { margin-top: 12px; padding: 10px; border-radius: 6px; font-size: 13px; }
.msg.ok { background: rgba(39,196,107,.12); color: #27c46b; }
.msg.error { background: rgba(239,83,80,.12); color: #ef5350; }
.table { width: 100%; border-collapse: collapse; font-size: 13px; }
.table th, .table td { text-align: left; padding: 9px 10px; border-bottom: 1px solid #1a2540; }
.table th { color: #8ba0c8; font-weight: 600; }
.pos { color: #27c46b; }
.neg { color: #ef5350; }
@media (max-width: 700px) { .cards { grid-template-columns: 1fr 1fr; } }
</style>
