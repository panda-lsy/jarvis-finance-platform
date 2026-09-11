<script setup>
import { computed, ref } from 'vue'
import { api } from '../api/client'
import DataState from '../components/common/DataState.vue'

// ---- 风险偏好问卷（FR-11）：全部为固定选项，保证问卷口径可复现 ----
const horizonYears = ref(3)
const maxDrawdownPct = ref(10)
const targetReturnPct = ref(5)
const experience = ref('basic')
const capital = ref(null) // 可选：资金规模，用于换算黄金 ETF 建议金额
const generating = ref(false)
const result = ref(null) // { profile, content }
const error = ref('')

const EXPERIENCE_LABELS = { none: '无经验', basic: '有一定经验', rich: '经验丰富' }

// 等级 → 展示色带的映射（与风险预警页同一套语义）
const LEVEL_CLASS = { conservative: 'low', balanced: 'medium', aggressive: 'high' }
const LEVEL_LABEL = { conservative: '保守型', balanced: '稳健型', aggressive: '积极型' }

const levelClass = computed(() => LEVEL_CLASS[result.value?.profile?.level] || '')
const levelLabel = computed(
  () => result.value?.profile?.level_label || LEVEL_LABEL[result.value?.profile?.level] || '—',
)

const allocation = computed(() => result.value?.profile?.allocation || [])
const subScores = computed(() => result.value?.profile?.sub_scores || [])
const reasons = computed(() => result.value?.profile?.reasons || [])

async function generate() {
  if (generating.value) return
  generating.value = true
  error.value = ''
  result.value = null
  try {
    const response = await api.aiStrategy({
      horizon_years: Number(horizonYears.value),
      max_drawdown_pct: Number(maxDrawdownPct.value),
      target_return_pct: Number(targetReturnPct.value),
      experience: experience.value,
      capital: capital.value && Number(capital.value) > 0 ? Number(capital.value) : null,
    })
    if (response.code !== 200 || !response.data) throw new Error(response.message || '策略生成失败')
    if (response.data.available === false) {
      error.value = response.data.reason === 'invalid_questionnaire'
        ? '问卷数据不完整，请重新选择风险偏好'
        : '策略生成暂不可用'
      return
    }
    result.value = response.data
  } catch (e) {
    error.value = e?.message || String(e)
  } finally {
    generating.value = false
  }
}

function clearAll() {
  result.value = null
  error.value = ''
}

function fmtPct(value) {
  if (value == null || value === '') return '—'
  return `${value}%`
}

function fmtMoney(value) {
  if (value == null || value === '') return '—'
  return `¥${Number(value).toLocaleString('zh-CN', { maximumFractionDigits: 2 })}`
}

function barWidth(value) {
  const numeric = Number(value)
  return Number.isFinite(numeric) ? `${Math.max(0, Math.min(numeric, 100))}%` : '0%'
}
</script>

<template>
  <section class="sg-workspace">
    <div class="section-bar">
      <div>
        <h1>策略生成</h1>
        <span>风险偏好问卷 · 风险等级 · 建议配置比例 · AI 策略说明</span>
      </div>
      <span class="section-status"><i class="ok"></i>{{ generating ? '正在生成策略' : '问卷已就绪' }}</span>
    </div>

    <div class="sg-layout">
      <aside class="panel sg-input-panel">
        <div class="sg-panel-title">风险偏好问卷</div>

        <div class="sg-field">
          <label>投资期限</label>
          <select v-model.number="horizonYears" class="sg-input" aria-label="投资期限">
            <option :value="1">1 年以内</option>
            <option :value="3">1 - 3 年</option>
            <option :value="5">3 - 5 年</option>
            <option :value="10">5 年以上</option>
          </select>
          <span>期限越长，越能承受短期波动。</span>
        </div>

        <div class="sg-field">
          <label>可承受最大回撤</label>
          <select v-model.number="maxDrawdownPct" class="sg-input" aria-label="可承受最大回撤">
            <option :value="5">5%（几乎不想亏）</option>
            <option :value="10">10%（小幅波动可接受）</option>
            <option :value="20">20%（能扛中等回撤）</option>
            <option :value="30">30%（追求收益愿担风险）</option>
          </select>
          <span>账户浮亏到这个幅度时你仍能持有。</span>
        </div>

        <div class="sg-field">
          <label>目标年化收益</label>
          <select v-model.number="targetReturnPct" class="sg-input" aria-label="目标年化收益">
            <option :value="3">3%（稳健保值）</option>
            <option :value="5">5%（略高于存款）</option>
            <option :value="8">8%（平衡增值）</option>
            <option :value="12">12%（追求成长）</option>
          </select>
          <span>收益目标越激进，配置中的权益比例越高。</span>
        </div>

        <div class="sg-field">
          <label>投资经验</label>
          <select v-model="experience" class="sg-input" aria-label="投资经验">
            <option value="none">无经验</option>
            <option value="basic">有一定经验</option>
            <option value="rich">经验丰富</option>
          </select>
          <span>用于校准建议的风险敞口。</span>
        </div>

        <div class="sg-field">
          <label>资金规模（可选）</label>
          <input
            v-model.number="capital"
            class="sg-input"
            type="number"
            min="0"
            aria-label="资金规模"
            placeholder="如 100000，用于换算配置金额"
          />
          <span>填写后给出黄金 ETF 的建议金额。</span>
        </div>

        <button class="btn primary sg-run" type="button" :disabled="generating" @click="generate">
          {{ generating ? '正在生成策略…' : '生成个性化策略' }}
        </button>
        <button v-if="result || error" type="button" class="text-action" @click="clearAll">清空结果</button>
        <div class="sg-note">风险等级与配置比例由确定性计算层按问卷计算，模型仅负责解读；建议为展示型参考，不构成投资建议。</div>
      </aside>

      <div class="sg-main">
        <template v-if="result">
          <div class="sg-banner" :class="levelClass">
            <span class="sg-banner-dot"></span>
            <div>
              <b>风险等级：{{ levelLabel }}</b>
              <span>
                综合得分 {{ result.profile.score }} / 100 ·
                期限 {{ horizonYears }} 年 · 可承受回撤 {{ maxDrawdownPct }}% · 目标年化 {{ targetReturnPct }}%
              </span>
            </div>
          </div>

          <div class="panel sg-card">
            <div class="sg-card-head">
              <div><b>建议资产配置</b><span>合计 100% · 由问卷确定性映射，AI 不改写比例</span></div>
              <span v-if="result.profile.gold_amount != null" class="sg-amount">
                黄金 ETF 建议金额 {{ fmtMoney(result.profile.gold_amount) }}
              </span>
            </div>
            <div class="sg-alloc">
              <div v-for="item in allocation" :key="item.id" class="sg-alloc-row">
                <span class="sg-alloc-label">{{ item.label }}</span>
                <div class="sg-alloc-track">
                  <i :style="{ width: barWidth(item.pct) }" :class="{ gold: item.id === 'gold_etf' }"></i>
                </div>
                <b>{{ fmtPct(item.pct) }}</b>
              </div>
            </div>
          </div>

          <div class="sg-metrics">
            <div v-for="item in subScores" :key="item.id" class="sg-metric">
              <span>{{ item.label }}</span>
              <b>{{ item.score }}</b>
              <small>权重 {{ fmtPct(item.weight_pct) }}</small>
            </div>
          </div>

          <div v-if="reasons.length" class="sg-reasons">
            <div v-for="item in reasons" :key="item.id" class="sg-reason">
              <span>{{ item.text }}</span>
            </div>
          </div>

          <div class="panel sg-report-panel">
            <div class="sg-report-head">
              <div><b>AI 策略说明</b><span>基于确定性等级与配置比例生成，数值口径以配置卡为准</span></div>
            </div>
            <div class="sg-output">{{ result.content?.content || result.content || '（暂无策略说明）' }}</div>
          </div>
        </template>

        <DataState
          v-else-if="generating"
          state="loading"
          title="正在生成个性化策略"
          message="根据问卷计算风险等级与配置比例，并生成策略说明，通常需要数十秒"
        />

        <DataState v-else-if="error" state="error" title="策略生成失败" :message="error" retryable @retry="generate" />

        <div v-else class="panel sg-empty">
          <div class="sg-empty-mark">策略</div>
          <b>等待生成策略</b>
          <span>回答左侧风险偏好问卷后点击生成，即可查看风险等级、建议资产配置比例与 AI 策略说明。</span>
        </div>
      </div>
    </div>
  </section>
</template>

<style scoped>
.sg-workspace { display: flex; flex-direction: column; gap: 10px; margin-top: 4px; }
.section-bar { display: flex; align-items: center; justify-content: space-between; gap: 16px; min-height: 38px; }
.section-bar h1 { margin: 0; color: var(--text); font-size: 16px; font-weight: 680; letter-spacing: .01em; }
.section-bar > div > span { display: block; margin-top: 3px; color: var(--subtle); font-size: 10px; }
.section-status { display: inline-flex; align-items: center; gap: 7px; color: var(--muted); font-size: 11px; }
.section-status i { width: 6px; height: 6px; border-radius: 50%; background: var(--bad); }
.section-status i.ok { background: var(--ok); }
.sg-layout { display: grid; grid-template-columns: 320px minmax(0, 1fr); gap: 10px; align-items: start; }
.sg-input-panel { position: sticky; top: 10px; }
.sg-panel-title { color: var(--text); font-size: 12px; font-weight: 680; padding-bottom: 10px; border-bottom: 1px solid var(--line); }
.sg-field { display: flex; flex-direction: column; gap: 5px; margin-top: 12px; }
.sg-field label { color: var(--muted); font-size: 10px; }
.sg-field > span { color: var(--subtle); font-size: 9px; line-height: 1.5; }
.sg-input { width: 100%; height: 34px; background: var(--surface); border: 1px solid var(--line-strong); border-radius: var(--radius-sm); color: var(--text); padding: 0 9px; font-size: 12px; outline: none; }
.sg-input:focus { border-color: #6a5b40; }
.sg-run { width: 100%; min-height: 36px; margin-top: 14px; }
.sg-input-panel .text-action { margin-top: 8px; border: 0; background: transparent; color: var(--subtle); font-size: 9px; cursor: pointer; }
.sg-input-panel .text-action:hover { color: var(--text); }
.sg-note { margin-top: 9px; color: var(--subtle); font-size: 9px; line-height: 1.5; }
.sg-main { display: flex; flex-direction: column; gap: 10px; min-width: 0; }
.sg-banner { display: flex; align-items: center; gap: 10px; padding: 10px 13px; border-radius: var(--radius-sm); border: 1px solid var(--line); background: var(--panel); }
.sg-banner .sg-banner-dot { width: 8px; height: 8px; border-radius: 50%; background: #5b6066; flex: 0 0 auto; }
.sg-banner.low .sg-banner-dot { background: var(--ok); }
.sg-banner.medium { border-color: rgba(227,180,102,.4); background: rgba(227,180,102,.06); }
.sg-banner.medium .sg-banner-dot { background: #e3b466; }
.sg-banner.high { border-color: rgba(239,83,80,.45); background: rgba(239,83,80,.07); }
.sg-banner.high .sg-banner-dot { background: #ef5350; }
.sg-banner b { color: var(--text); font-size: 12px; font-weight: 680; }
.sg-banner span { display: block; margin-top: 2px; color: var(--subtle); font-size: 9px; }
.sg-card { display: flex; flex-direction: column; gap: 12px; padding: 14px 16px; }
.sg-card-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; flex-wrap: wrap; }
.sg-card-head b { color: var(--text); font-size: 12px; font-weight: 680; }
.sg-card-head span { display: block; margin-top: 3px; color: var(--subtle); font-size: 9px; }
.sg-card-head .sg-amount { margin-top: 0; color: var(--accent); font-size: 11px; }
.sg-alloc { display: flex; flex-direction: column; gap: 9px; }
.sg-alloc-row { display: grid; grid-template-columns: 84px minmax(0, 1fr) 62px; align-items: center; gap: 10px; }
.sg-alloc-label { color: var(--muted); font-size: 10px; }
.sg-alloc-track { position: relative; height: 9px; border-radius: 999px; background: var(--surface); border: 1px solid var(--line); overflow: hidden; }
.sg-alloc-track i { display: block; height: 100%; border-radius: 999px; background: #4d5460; transition: width .45s ease; }
.sg-alloc-track i.gold { background: linear-gradient(90deg, #a8863f, var(--accent-strong)); }
.sg-alloc-row b { color: var(--text); font-size: 11px; font-weight: 650; text-align: right; font-variant-numeric: tabular-nums; }
.sg-metrics { display: grid; grid-template-columns: repeat(auto-fit, minmax(150px, 1fr)); gap: 7px; }
.sg-metric { background: var(--panel); border: 1px solid var(--line); border-radius: var(--radius-sm); padding: 10px 12px; display: flex; flex-direction: column; gap: 4px; }
.sg-metric span { color: var(--subtle); font-size: 9px; }
.sg-metric b { color: var(--text); font-size: 16px; font-weight: 680; font-variant-numeric: tabular-nums; line-height: 1; }
.sg-metric small { color: var(--muted); font-size: 8px; }
.sg-reasons { display: flex; flex-direction: column; gap: 6px; }
.sg-reason { border-left: 3px solid var(--line-strong); background: var(--panel); border-radius: var(--radius-sm); padding: 8px 11px; }
.sg-reason span { color: var(--muted); font-size: 10px; line-height: 1.55; }
.sg-report-panel { display: flex; flex-direction: column; }
.sg-report-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding-bottom: 10px; border-bottom: 1px solid var(--line); }
.sg-report-head b { color: var(--text); font-size: 12px; font-weight: 680; }
.sg-report-head span { display: block; margin-top: 3px; color: var(--subtle); font-size: 9px; }
.sg-output { margin-top: 10px; max-height: 420px; overflow: auto; background: var(--surface); border: 1px solid var(--line); border-radius: var(--radius-sm); padding: 12px 14px; color: var(--text); font-size: 11px; line-height: 1.75; white-space: pre-wrap; overflow-wrap: anywhere; }
.sg-empty { display: flex; flex-direction: column; align-items: center; gap: 7px; padding: 46px 20px; text-align: center; }
.sg-empty-mark { color: var(--accent); border: 1px solid var(--line-strong); border-radius: 50%; width: 54px; height: 54px; display: grid; place-items: center; font-size: 12px; letter-spacing: .1em; }
.sg-empty b { color: var(--text); font-size: 12px; font-weight: 650; }
.sg-empty span { color: var(--subtle); font-size: 10px; line-height: 1.6; max-width: 380px; }
@media (max-width: 980px) { .sg-layout { grid-template-columns: 280px minmax(0, 1fr); } }
@media (max-width: 760px) { .sg-layout { grid-template-columns: 1fr; } .sg-input-panel { position: static; } }
</style>
