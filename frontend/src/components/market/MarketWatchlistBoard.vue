<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { api } from '../../api/client'
import {
  hasMarketPreferences,
  marketPreferencesKey,
  normalizeMarketPreferences,
  readMarketPreferences,
  writeMarketPreferences,
} from '../../utils/marketPreferences'

const props = defineProps({
  user: { type: Object, default: null },
  active: { type: Boolean, default: true },
})
const emit = defineEmits(['context-change'])

const instruments = ref([])
const watchlist = ref([])
const hiddenDefaultKeys = ref([])
const selectedKey = ref('')
const loading = ref(true)
const error = ref('')
const importInputRef = ref(null)
const codeInput = ref('')
const addingCode = ref(false)
const importing = ref(false)
const importMessage = ref('')

const marketMeta = Object.freeze({
  a_share: { label: 'A股', glyph: 'CN' },
  us_stock: { label: '美股', glyph: 'US' },
  crypto: { label: '加密', glyph: 'CR' },
})

function instrumentKey(item) {
  return `${item.market}:${item.symbol}`
}

const watchlistKeys = computed(() => new Set(watchlist.value.map(instrumentKey)))
const effective = computed(() => {
  const defaults = instruments.value.filter(item =>
    !hiddenDefaultKeys.value.includes(instrumentKey(item))
    && !watchlistKeys.value.has(instrumentKey(item)))
  return [...watchlist.value, ...defaults]
})
const grouped = computed(() => Object.keys(marketMeta)
  .map(market => ({
    market,
    ...marketMeta[market],
    items: effective.value.filter(item => item.market === market),
  }))
  .filter(group => group.items.length))

function currentPreferences() {
  return normalizeMarketPreferences({
    watchlist: watchlist.value,
    hiddenDefaultKeys: hiddenDefaultKeys.value,
  })
}

function applyPreferences(value) {
  const normalized = normalizeMarketPreferences(value)
  watchlist.value = normalized.watchlist
  hiddenDefaultKeys.value = normalized.hiddenDefaultKeys
}

function persistLocal(value = currentPreferences()) {
  if (typeof window === 'undefined') return false
  return writeMarketPreferences(window.localStorage, marketPreferencesKey(props.user), value)
}

async function persistPreferences() {
  const value = currentPreferences()
  persistLocal(value)
  const response = await api.saveMarketPreferences(value)
  if (response?.code !== 200) throw new Error(response?.message || '自选同步失败')
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const local = typeof window === 'undefined'
      ? { watchlist: [], hiddenDefaultKeys: [] }
      : readMarketPreferences(window.localStorage, marketPreferencesKey(props.user))
    applyPreferences(local)

    const [instrumentResponse, preferenceResponse] = await Promise.all([
      api.marketInstruments(),
      api.marketPreferences().catch(() => null),
    ])
    if (instrumentResponse?.code !== 200 || !Array.isArray(instrumentResponse?.data)) {
      throw new Error(instrumentResponse?.message || '自选标的目录加载失败')
    }
    instruments.value = instrumentResponse.data

    if (preferenceResponse?.code === 200 && preferenceResponse.data) {
      const serverValue = normalizeMarketPreferences(preferenceResponse.data)
      if (preferenceResponse.data.persisted) {
        applyPreferences(serverValue)
        persistLocal(serverValue)
      } else if (hasMarketPreferences(local)) {
        await persistPreferences()
      }
    }
  } catch (e) {
    error.value = e?.message || '多市场自选加载失败'
  } finally {
    loading.value = false
  }
}

function choose(item) {
  if (!item) return
  selectedKey.value = instrumentKey(item)
  emit('context-change', {
    market: item.market,
    symbol: item.symbol,
    name: item.name || item.symbol,
    sourceModule: 'market-watchlist',
  })
}

function openImporter() {
  importMessage.value = ''
  importInputRef.value?.click()
}

async function addCode() {
  const symbol = codeInput.value.trim()
  if (!symbol || addingCode.value) return
  addingCode.value = true
  importMessage.value = ''
  try {
    const market = inferMarket(symbol)
    const response = await api.resolveMarketInstrument(market, symbol)
    if (response?.code !== 200 || !response?.data?.symbol) {
      throw new Error(response?.message || '代码解析失败')
    }
    const item = response.data
    const key = instrumentKey(item)
    const merged = [...watchlist.value]
    const index = merged.findIndex(existing => instrumentKey(existing) === key)
    if (index >= 0) merged.splice(index, 1, item)
    else merged.push(item)
    watchlist.value = merged
    hiddenDefaultKeys.value = hiddenDefaultKeys.value.filter(existing => existing !== key)
    await persistPreferences()
    codeInput.value = ''
    importMessage.value = `${item.name || item.symbol} 已加入自选`
    choose(item)
  } catch (e) {
    importMessage.value = e?.message || '添加标的失败'
  } finally {
    addingCode.value = false
  }
}

function inferMarket(symbol) {
  const raw = String(symbol || '').trim().toUpperCase()
  if (/^(SH|SZ|BJ)?\d{6}$/.test(raw)) return 'a_share'
  if (/^[A-Z0-9-]{2,15}(USDT)?$/.test(raw) && (raw.endsWith('USDT') || ['BTC', 'ETH', 'SOL'].includes(raw))) return 'crypto'
  return 'us_stock'
}

function parseImport(text) {
  const raw = String(text || '').trim()
  if (!raw) return []
  try {
    const json = JSON.parse(raw)
    if (Array.isArray(json)) {
      return json.map(item => ({
        market: String(item?.market || '').trim() || inferMarket(item?.symbol),
        symbol: String(item?.symbol || '').trim(),
        name: String(item?.name || '').trim(),
      })).filter(item => item.symbol)
    }
  } catch (_) {
    // 继续按 CSV/TXT 解析。
  }

  return raw.split(/\r?\n/)
    .map(line => line.trim())
    .filter(line => line && !line.startsWith('#'))
    .map(line => {
      const pair = line.match(/^(a_share|us_stock|crypto)\s*:\s*(.+)$/i)
      if (pair) return { market: pair[1].toLowerCase(), symbol: pair[2].trim(), name: '' }
      const parts = line.split(/[\t,;]/).map(value => value.trim()).filter(Boolean)
      if (!parts.length) return null
      if (marketMeta[parts[0]]) {
        return { market: parts[0], symbol: parts[1] || '', name: parts[2] || '' }
      }
      return { market: inferMarket(parts[0]), symbol: parts[0], name: parts[1] || '' }
    })
    .filter(item => item?.symbol)
}

async function importRows(rows) {
  if (!rows.length) throw new Error('文件中没有可识别的标的')
  if (rows.length > 25) throw new Error('单次最多导入 25 个标的，请分批导入')
  const resolved = []
  const failures = []

  for (const row of rows) {
    if (!marketMeta[row.market]) {
      failures.push(`${row.symbol}: 市场类型不支持`)
      continue
    }
    try {
      const response = await api.resolveMarketInstrument(row.market, row.symbol)
      if (response?.code !== 200 || !response?.data?.symbol) {
        throw new Error(response?.message || '解析失败')
      }
      resolved.push({
        ...response.data,
        name: row.name || response.data.name,
      })
    } catch (e) {
      failures.push(`${row.symbol}: ${e?.message || '解析失败'}`)
    }
  }

  const merged = [...watchlist.value]
  for (const item of resolved) {
    const key = instrumentKey(item)
    const index = merged.findIndex(existing => instrumentKey(existing) === key)
    if (index >= 0) merged.splice(index, 1, item)
    else merged.push(item)
  }
  watchlist.value = merged
  await persistPreferences()

  importMessage.value = failures.length
    ? `已导入 ${resolved.length} 个，${failures.length} 个失败`
    : `已导入 ${resolved.length} 个标的`
}

async function handleImportFile(event) {
  const file = event.target?.files?.[0]
  if (!file || importing.value) return
  importing.value = true
  importMessage.value = ''
  try {
    const text = await file.text()
    await importRows(parseImport(text))
  } catch (e) {
    importMessage.value = e?.message || '导入失败'
  } finally {
    importing.value = false
    if (event.target) event.target.value = ''
  }
}

onMounted(load)
watch(() => props.user?.id ?? props.user?.email, load)
watch(() => props.active, active => {
  if (active && !instruments.value.length) load()
})
</script>

<template>
  <section class="watchlist-board">
    <header class="watchlist-head">
      <div>
        <span>MULTI-MARKET WATCHLIST</span>
        <h2>多市场自选</h2>
        <p>点击标的即可切换整个工作台的研究目标。</p>
      </div>
      <div class="watchlist-actions">
        <form class="code-add" @submit.prevent="addCode">
          <input v-model="codeInput" type="text" autocomplete="off" aria-label="输入股票代码" placeholder="输入代码：600519 / AAPL / BTCUSDT" />
          <button type="submit" :disabled="addingCode || !codeInput.trim()">{{ addingCode ? '添加中…' : '添加' }}</button>
        </form>
        <input ref="importInputRef" class="file-input" type="file" accept=".json,.csv,.txt,text/plain,text/csv,application/json" @change="handleImportFile" />
        <button type="button" :disabled="importing" @click="openImporter">{{ importing ? '导入中…' : '从文件导入' }}</button>
        <button type="button" :disabled="loading" @click="load">刷新</button>
      </div>
    </header>

    <p v-if="importMessage" class="import-message">{{ importMessage }}</p>
    <p v-if="error && !effective.length" class="watchlist-empty">{{ error }}</p>
    <p v-else-if="loading && !effective.length" class="watchlist-empty">正在同步多市场自选…</p>

    <div v-else class="watch-groups">
      <section v-for="group in grouped" :key="group.market" class="watch-group">
        <header>
          <span>{{ group.glyph }}</span>
          <b>{{ group.label }}</b>
          <small>{{ group.items.length }}</small>
        </header>
        <div class="watch-cards">
          <button
            v-for="item in group.items"
            :key="instrumentKey(item)"
            type="button"
            :class="{ active: selectedKey === instrumentKey(item) }"
            @click="choose(item)"
          >
            <span>
              <strong>{{ item.name || item.symbol }}</strong>
              <small>{{ item.symbol }}</small>
            </span>
            <em>{{ watchlistKeys.has(instrumentKey(item)) ? '自选' : '默认' }}</em>
          </button>
        </div>
      </section>
    </div>

    <footer class="watchlist-foot">
      <span>导入格式支持 JSON 数组、CSV/TXT：market,symbol,name；也支持单列代码自动识别市场。</span>
      <span>完整增删改查仍可在「市场 → 多市场」进入专业工作台。</span>
    </footer>
  </section>
</template>

<style scoped>
.watchlist-board { margin: 2px 0 14px; padding: 14px; border: 1px solid color-mix(in srgb, var(--line) 88%, transparent); border-radius: 11px; background: color-mix(in srgb, var(--workspace-panel-wash, var(--panel)) 88%, transparent); }
.watchlist-head { display: flex; align-items: flex-end; justify-content: space-between; gap: 16px; padding-bottom: 11px; border-bottom: 1px solid var(--line); }
.watchlist-head span { color: var(--subtle); font: 650 7px/1 ui-monospace, monospace; letter-spacing: .12em; }
.watchlist-head h2 { margin: 4px 0 0; color: var(--text); font-size: 15px; font-weight: 680; }
.watchlist-head p { margin: 4px 0 0; color: var(--muted); font-size: 9px; }
.watchlist-actions { display: flex; align-items: center; gap: 6px; }
.watchlist-actions button { height: 30px; padding: 0 10px; border: 1px solid var(--line); border-radius: 7px; background: transparent; color: var(--muted); cursor: pointer; font-size: 9px; }
.watchlist-actions button:hover:not(:disabled) { color: var(--text); border-color: var(--line-strong); background: var(--workspace-hover-bg); }
.watchlist-actions button:disabled { opacity: .45; cursor: default; }
.code-add { display: flex; align-items: center; gap: 5px; min-width: 0; }
.code-add input { flex: 1 1 auto; min-width: 0; width: 238px; height: 30px; padding: 0 9px; border: 1px solid var(--line); border-radius: 7px; outline: none; background: var(--workspace-control-bg, transparent); color: var(--text); font-size: 9px; }
.code-add button { flex: 0 0 auto; min-width: 56px; white-space: nowrap; }
.code-add input:focus { border-color: var(--workspace-focus, var(--accent)); }
.file-input { display: none; }
.import-message { margin: 10px 0 0; color: var(--accent-strong); font-size: 9px; }
.watchlist-empty { margin: 12px 0 0; color: var(--muted); font-size: 10px; }
.watch-groups { display: grid; gap: 12px; margin-top: 12px; }
.watch-group > header { display: flex; align-items: center; gap: 7px; margin-bottom: 7px; }
.watch-group > header span { color: var(--accent-strong); font: 650 7px/1 ui-monospace, monospace; }
.watch-group > header b { color: var(--text); font-size: 10px; }
.watch-group > header small { color: var(--subtle); font: 600 7px/1 ui-monospace, monospace; }
.watch-cards { display: grid; grid-template-columns: repeat(auto-fill, minmax(170px, 1fr)); gap: 7px; }
.watch-cards button { min-width: 0; min-height: 54px; display: flex; align-items: center; justify-content: space-between; gap: 8px; padding: 8px 10px; border: 1px solid var(--line); border-radius: 8px; background: transparent; color: var(--text); cursor: pointer; text-align: left; transition: border-color .16s ease, background .16s ease, transform .12s ease; }
.watch-cards button:hover { border-color: var(--line-strong); background: var(--workspace-hover-bg); }
.watch-cards button:active { transform: scale(.985); }
.watch-cards button.active { border-color: color-mix(in srgb, var(--accent) 48%, var(--line)); background: var(--workspace-accent-wash); }
.watch-cards button > span { min-width: 0; display: grid; gap: 4px; }
.watch-cards strong { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 10px; font-weight: 650; }
.watch-cards small { color: var(--subtle); font: 600 7px/1 ui-monospace, monospace; }
.watch-cards em { flex: 0 0 auto; color: var(--subtle); font-size: 7px; font-style: normal; }
.watch-cards button.active em { color: var(--accent-strong); }
.watchlist-foot { display: flex; justify-content: space-between; gap: 18px; margin-top: 12px; padding-top: 10px; border-top: 1px solid var(--line); color: var(--subtle); font-size: 8px; line-height: 1.5; }
@media (max-width: 900px) { .watchlist-head { align-items: flex-start; flex-direction: column; } .watchlist-actions { width: 100%; flex-wrap: wrap; } .code-add { flex: 1 1 320px; } .code-add input { min-width: 0; width: 100%; } }
@media (max-width: 760px) { .watchlist-foot { flex-direction: column; gap: 4px; } }
</style>
