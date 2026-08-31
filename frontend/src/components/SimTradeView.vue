<script setup>
import { ref, reactive, onMounted } from 'vue'
import { api } from '../api/client'

const account = ref(null)
const trades = ref([])
const loading = ref(true)
const msg = ref('')
const msgType = ref('info')
const realtimePrices = ref(null)
const jdPrices = ref(null)

const order = reactive({
  symbol: 'sh518850',
  type: 'BUY',
  quantity: 100,
  leverage: 1,
})

// 京东积存金直连 (与行情页一致, 经 nginx /jd/ 同域反代)
const JD_SOURCES = [
  { key: 'zheshang', label: '浙商积存金', name: 'gw2/generic/jrm/h5/m/stdLatestPrice', sku: '1961543816' },
  { key: 'minsheng', label: '民生积存金', name: 'gw/generic/hj/h5/m/latestPrice', sku: 'P005' },
]

function parseJdRate(raw) {
  const s = String(raw ?? '0').replace('%', '')
  const n = parseFloat(s)
  return Number.isFinite(n) ? n : 0
}

async function loadJdLive() {
  const out = {}
  await Promise.all(JD_SOURCES.map(async (src) => {
    try {
      const d = await api.jdLive(src.name, src.sku)
      const datas = d?.resultData?.datas
      if (!d?.success || !datas) return
      const price = parseFloat(datas.price)
      if (!Number.isFinite(price)) return
      const tsMs = parseInt(datas.time, 10) || 0
      out[src.key] = {
        source: src.key, label: src.label, price,
        change: parseFloat(datas.upAndDownAmt || 0),
        change_pct: parseJdRate(datas.upAndDownRate),
        time: tsMs ? new Date(tsMs).toLocaleString('zh-CN', { hour12: false }) : '',
      }
    } catch (e) { /* 单源失败不影响另一源 */ }
  }))
  if (Object.keys(out).length) jdPrices.value = out
}

async function load() {
  try {
    const [acc, tr, rt] = await Promise.all([
      api.simAccount(), api.simTrades(20), loadRealtime(),
    ])
    account.value = acc.data
    trades.value = tr.data?.trades || []
  } catch (e) {
    msg.value = '加载失败: ' + e
    msgType.value = 'error'
  } finally {
    loading.value = false
  }
}

async function loadRealtime() {
  try {
    const d = await api.marketPrices()
    realtimePrices.value = d.data || null
  } catch (e) { /* 忽略 */ }
}

async function submitOrder() {
  msg.value = ''
  try {
    const res = await api.simOrder(order.type, order.symbol, Number(order.quantity), Number(order.leverage))
    if (res.code === 200) {
      const d = res.data
      let extra = ''
      if (d.leverage > 1) extra = ` | 保证金 ${d.margin}, 借款 ${d.loan}`
      msg.value = res.message + ' | ' + d.message + extra
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

async function quickSell(symbol, qty) {
  if (!confirm('确认卖出全部 ' + qty + ' 股(' + symbol + ')?')) return
  try {
    const res = await api.simOrder('SELL', symbol, Number(qty), 1)
    msg.value = res.data?.message || res.message
    msgType.value = res.code === 200 ? 'ok' : 'error'
    await load()
  } catch (e) {
    msg.value = '卖出失败: ' + e
    msgType.value = 'error'
  }
}

onMounted(() => { load(); loadJdLive() })
</script>

<template>
  <div class="sim">
    <!-- 京东积存金实时价 (直连京东, 两个Card) -->
    <div v-if="jdPrices" class="rt-grid">
      <div v-for="(p, key) in jdPrices" :key="key" class="rt-card jd">
        <div class="rt-name">{{ p.label }}</div>
        <div class="rt-price jd-price">{{ fmt(p.price) }}</div>
        <div class="rt-sub">
          <span :class="(p.change || 0) >= 0 ? 'pos' : 'neg'">
            {{ p.change }} ({{ fmtPct(p.change_pct) }})
          </span>
          <span class="rt-muted">{{ p.time }}</span>
        </div>
      </div>
    </div>

    <!-- 实时价格 (黄金ETF + 伦敦金) -->
    <div v-if="realtimePrices" class="rt-grid">
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
        <div class="card-title">净资产</div>
        <div class="card-value">{{ fmt(account.netEquity) }}</div>
      </div>
      <div class="card">
        <div class="card-title">借款</div>
        <div class="card-value warn" v-if="account.loanBalance > 0">{{ fmt(account.loanBalance) }}</div>
        <div class="card-value" v-else>0</div>
      </div>
      <div class="card">
        <div class="card-title">总收益率</div>
        <div class="card-value" :class="posClass(account.totalReturnPct)">
          {{ fmtPct(account.totalReturnPct) }}
        </div>
      </div>
      <div class="card" v-if="account.riskStatus && account.riskStatus !== 'NONE'">
        <div class="card-title">维持保证金率
          <span class="risk-badge" :class="'rk-' + String(account.riskStatus).toLowerCase()">{{ account.riskStatus }}</span>
        </div>
        <div class="card-value" :class="account.maintMarginPct < 50 ? 'neg' : ''">{{ fmtPct(account.maintMarginPct) }}</div>
      </div>
    </div>

    <!-- 下单 -->
    <div class="panel">
      <div class="panel-head"><h2>模拟盘下单</h2></div>
      <div class="order-form">
        <select v-model="order.symbol" class="select">
          <option value="sh518850">黄金ETF华夏 (sh518850)</option>
          <option value="hf_XAU">伦敦金 (hf_XAU)</option>
          <option value="jd_zheshang">浙商积存金 (jd_zheshang)</option>
          <option value="jd_minsheng">民生积存金 (jd_minsheng)</option>
        </select>
        <div class="type-toggle">
          <button :class="['btn', order.type === 'BUY' ? 'buy' : '']" @click="order.type = 'BUY'">买入</button>
          <button :class="['btn', order.type === 'SELL' ? 'sell' : '']" @click="order.type = 'SELL'">卖出</button>
        </div>
        <input type="number" v-model.number="order.quantity" class="num wide" min="1" placeholder="数量" />
        <div v-if="order.type === 'BUY'" class="lev-group">
          <label class="lev-label">杠杆</label>
          <div class="lev-btns">
            <button v-for="l in [1, 2, 3, 5]" :key="l"
                    :class="['lev-btn', order.leverage === l ? 'active' : '']"
                    @click="order.leverage = l">{{ l }}x</button>
          </div>
        </div>
        <button class="btn primary" @click="submitOrder">提交 {{ order.type === 'BUY' ? '买入' : '卖出' }}</button>
      </div>
      <div v-if="msg" class="msg" :class="msgType">{{ msg }}</div>
      <div v-if="order.type === 'BUY' && order.leverage > 1" class="lev-tip">
        ⚠️ {{ order.leverage }}x 杠杆：仅冻结 {{ 100 / order.leverage }}% 保证金，其余为借款，价格下跌可能触发强平！
      </div>
    </div>

    <!-- 当前持仓 -->
    <div class="panel" v-if="account && Object.keys(account.positions).length">
      <div class="panel-head"><h2>当前持仓</h2></div>
      <table class="table">
        <thead><tr><th>标的</th><th>数量</th><th>成本</th><th>现价</th><th>市值</th><th>杠杆</th><th>借款</th><th>盈亏</th><th>盈亏%</th><th>操作</th></tr></thead>
        <tbody>
          <tr v-for="(p, sym) in account.positions" :key="sym">
            <td>{{ sym }}</td>
            <td>{{ p.quantity }}</td>
            <td>{{ p.avgCost }}</td>
            <td>{{ p.currentPrice }}</td>
            <td>{{ fmt(p.marketValue) }}</td>
            <td><span v-if="p.leverage > 1" class="lev-chip">{{ p.leverage }}x</span><span v-else>1x</span></td>
            <td :class="p.loan > 0 ? 'warn' : ''">{{ p.loan > 0 ? fmt(p.loan) : '-' }}</td>
            <td :class="posClass(p.profit)">{{ fmt(p.profit) }}</td>
            <td :class="posClass(p.profitPct)">{{ fmtPct(p.profitPct) }}</td>
            <td>
              <button class="btn sell small" @click="quickSell(sym, p.quantity)">全平</button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
    <div v-else class="panel empty">暂无持仓，买入第一笔开始模拟交易</div>

    <!-- 交易记录 -->
    <div class="panel" v-if="trades.length">
      <div class="panel-head"><h2>交易记录</h2></div>
      <table class="table">
        <thead><tr><th>时间</th><th>标的</th><th>方向</th><th>杠杆</th><th>价格</th><th>数量</th><th>金额</th></tr></thead>
        <tbody>
          <tr v-for="t in trades" :key="t.id">
            <td>{{ t.createdAt?.replace('T', ' ').slice(0, 19) }}</td>
            <td>{{ t.symbol }}</td>
            <td :class="t.type === 'BUY' ? 'pos' : 'neg'">{{ t.type === 'BUY' ? '买入' : '卖出' }}</td>
            <td>{{ t.leverage > 1 ? t.leverage + 'x' : '-' }}</td>
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
.rt-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; }
.rt-card.jd::before { background: linear-gradient(90deg, #f5c542, #ff9d42); }
.jd-price { color: #f5c542; }
.rt-card { background: #0f1626; border: 1px solid #243453; border-radius: 12px; padding: 18px 20px; position: relative; overflow: hidden; }
.rt-card::before { content: ''; position: absolute; top: 0; left: 0; right: 0; height: 3px; background: linear-gradient(90deg, #4da8ff, #a842ff); }
.rt-name { color: #8ba0c8; font-size: 13px; margin-bottom: 6px; }
.rt-price { font-size: 28px; font-weight: 700; font-variant-numeric: tabular-nums; }
.rt-sub { display: flex; align-items: center; gap: 12px; margin-top: 6px; font-size: 13px; }
.rt-muted { color: #5a6b8c; font-size: 12px; }
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
.lev-group { display: flex; align-items: center; gap: 8px; }
.lev-label { color: #8ba0c8; font-size: 13px; }
.lev-btns { display: flex; gap: 6px; }
.lev-btn { background: #0f1626; border: 1px solid #243453; color: #8ba0c8; border-radius: 6px; padding: 9px 14px; cursor: pointer; font-size: 13px; }
.lev-btn.active { background: linear-gradient(135deg, #a842ff, #ef5350); border-color: transparent; color: #fff; font-weight: 600; }
.lev-chip { background: rgba(168,66,255,.15); color: #a842ff; border-radius: 4px; padding: 2px 6px; font-size: 12px; font-weight: 600; }
.lev-tip { margin-top: 12px; padding: 10px 12px; border-radius: 6px; background: rgba(241,196,15,.1); color: #f1c40f; font-size: 13px; border: 1px solid rgba(241,196,15,.3); }
.risk-badge { margin-left: 8px; border-radius: 999px; padding: 2px 8px; font-size: 11px; font-weight: 600; }
.rk-safe { background: rgba(39,196,107,.15); color: #27c46b; }
.rk-warn { background: rgba(241,196,15,.15); color: #f1c40f; }
.rk-danger { background: rgba(239,83,80,.2); color: #ef5350; }
.btn.small { padding: 4px 10px; font-size: 12px; }
.warn { color: #f1c40f; }
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
