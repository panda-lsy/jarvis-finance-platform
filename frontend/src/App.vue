<script setup>
import { computed, defineAsyncComponent, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { api } from './api/client'
import LoginView from './components/LoginView.vue'
import AppHeader from './components/common/AppHeader.vue'
import AppTabs from './components/common/AppTabs.vue'
import LandingPage from './pages/LandingPage.vue'
import { useAuthSession } from './composables/useAuthSession'
import { useWorkspaceTabs } from './composables/useWorkspaceTabs'
import ArchiveWorkspaceShell from './analysis-os/components/ArchiveWorkspaceShell.vue'
import { JARVIS_MODULES } from './analysis-os/data/modules'
import { useResearchContext } from './analysis-os/state/researchContext'
import { useWorkflowHandoff } from './analysis-os/state/workflowHandoff'

const AnalysisOsPage = defineAsyncComponent(() => import('./pages/AnalysisOsPage.vue'))
const workspaceLoaders = Object.freeze({
  '行情': () => import('./pages/MarketPage.vue'),
  '多市场': () => import('./components/CrossMarketView.vue'),
  '回测': () => import('./pages/BacktestPage.vue'),
  '模拟盘': () => import('./components/SimTradeView.vue'),
  '研究助手': () => import('./components/AiCenter.vue'),
  '多空研报': () => import('./pages/SentimentPage.vue'),
  '财报解析': () => import('./pages/FinancialReportPage.vue'),
  '产业链图谱': () => import('./pages/ChainPage.vue'),
  '风险预警': () => import('./pages/RiskPage.vue'),
  '智能报价': () => import('./pages/QuotePage.vue'),
  '策略生成': () => import('./pages/StrategyPage.vue'),
  '运维': () => import('./components/OpsView.vue'),
  '市场趋势预测': () => import('./pages/TrendPage.vue'),
})
const MarketPage = defineAsyncComponent(workspaceLoaders['行情'])
const BacktestPage = defineAsyncComponent(workspaceLoaders['回测'])
const CrossMarketView = defineAsyncComponent(workspaceLoaders['多市场'])
const SimTradeView = defineAsyncComponent(workspaceLoaders['模拟盘'])
const AiCenter = defineAsyncComponent(workspaceLoaders['研究助手'])
const SentimentPage = defineAsyncComponent(workspaceLoaders['多空研报'])
const FinancialReportPage = defineAsyncComponent(workspaceLoaders['财报解析'])
const ChainPage = defineAsyncComponent(workspaceLoaders['产业链图谱'])
const RiskPage = defineAsyncComponent(workspaceLoaders['风险预警'])
const StrategyPage = defineAsyncComponent(workspaceLoaders['策略生成'])
const QuotePage = defineAsyncComponent(workspaceLoaders['智能报价'])
const TrendPage = defineAsyncComponent(workspaceLoaders['市场趋势预测'])
const OpsView = defineAsyncComponent(workspaceLoaders['运维'])
const AdminView = defineAsyncComponent(() => import('./components/AdminView.vue'))
const workspacePreloads = new Map()
const preparedWorkspaceRoute = ref('')
const archiveHandoffHold = ref(false)
const workspaceRevealReady = ref(true)
let archiveHandoffTimer = 0

const workspaceWarmers = Object.freeze({
  '行情': () => import('./charts/echarts'),
  '多市场': () => import('./charts/echarts'),
  '回测': () => import('./charts/echarts'),
  '模拟盘': () => import('./charts/echarts'),
})

const session = useAuthSession()
const { user: sessionUser, isLoggedIn: sessionLoggedIn, sessionState } = session
const previewMode = ref(false)
const THEME_MODE_KEY = 'jarvis-theme'
const LEGACY_NIGHT_MODE_KEY = 'jarvis-ui-night-mode'
function readNightModePreference() {
  try {
    const storedTheme = window.localStorage.getItem(THEME_MODE_KEY)
    if (storedTheme === 'night') return true
    if (storedTheme === 'day') return false

    const legacyNightMode = window.localStorage.getItem(LEGACY_NIGHT_MODE_KEY)
    if (legacyNightMode === 'true' || legacyNightMode === 'false') {
      const migratedTheme = legacyNightMode === 'true' ? 'night' : 'day'
      window.localStorage.setItem(THEME_MODE_KEY, migratedTheme)
      return migratedTheme === 'night'
    }
  } catch (_) {
    // Fall through to the first-paint theme applied by index.html.
  }
  return document.documentElement.dataset.theme === 'night'
}
const nightMode = ref(readNightModePreference())

function applyThemePreference(value) {
  const theme = value ? 'night' : 'day'
  document.documentElement.dataset.theme = theme
  try {
    window.localStorage.setItem(THEME_MODE_KEY, theme)
  } catch (_) {
    // 当前会话仍可切换主题；存储受限时不阻塞界面。
  }
}

function toggleNightMode() {
  nightMode.value = !nightMode.value
  applyThemePreference(nightMode.value)
}
applyThemePreference(nightMode.value)

const LOCAL_PREVIEW_USER = Object.freeze({
  id: -1,
  email: 'preview@local.test',
  displayName: 'Local Preview',
  role: 'USER',
  preview: true,
})
const user = computed(() => previewMode.value ? LOCAL_PREVIEW_USER : sessionUser.value)
const isLoggedIn = computed(() => previewMode.value || sessionLoggedIn.value)
const workspace = useWorkspaceTabs(user)
const { activeTab, visitedTabs, tabs, switchTab } = workspace
const publicView = ref('landing')
const activeModule = computed(() => JARVIS_MODULES.find(module => module.routeKey === activeTab.value) || null)
const preparedModule = computed(() => (
  activeModule.value
  || JARVIS_MODULES.find(module => module.routeKey === preparedWorkspaceRoute.value)
  || null
))
const workspaceRenderRoute = computed(() => activeModule.value?.routeKey || preparedWorkspaceRoute.value)
const archiveModuleKey = ref('market')
const research = useResearchContext()
const { context: researchContext, setContext: setResearchContext, clearContext: clearResearchContext } = research
const workflow = useWorkflowHandoff()
const { backtestHandoff, setBacktestHandoff, clearBacktestHandoff } = workflow

function replacePublicQuery(mutator) {
  const url = new URL(window.location.href)
  mutator(url.searchParams)
  const query = url.searchParams.toString()
  window.history.replaceState(window.history.state, document.title, `${url.pathname}${query ? `?${query}` : ''}${url.hash}`)
}

function showLanding() {
  publicView.value = 'landing'
  replacePublicQuery((params) => params.delete('view'))
  window.scrollTo({ top: 0, behavior: 'smooth' })
}

function showLogin() {
  publicView.value = 'login'
  replacePublicQuery((params) => params.set('view', 'login'))
  window.scrollTo({ top: 0, behavior: 'auto' })
}

function clearOAuthQuery() {
  replacePublicQuery((params) => params.delete('oauth'))
}

function handleLoggedIn(value) {
  session.acceptLogin(value)
  workspace.reset()
}

async function logout() {
  if (previewMode.value) {
    previewMode.value = false
    workspace.reset()
    clearResearchContext()
    clearBacktestHandoff()
    publicView.value = 'landing'
    replacePublicQuery((params) => {
      params.delete('preview')
      params.delete('view')
    })
    return
  }
  await session.logout()
  workspace.reset()
  clearResearchContext()
  clearBacktestHandoff()
  publicView.value = 'landing'
  replacePublicQuery((params) => params.delete('view'))
}

async function updateProfile(displayName) {
  const response = await api.updateProfile(displayName)
  if (response.code === 200 && response.data) session.acceptLogin(response.data)
}

function syncArchiveModule(key) {
  if (JARVIS_MODULES.some(module => module.key === key)) archiveModuleKey.value = key
}

function waitForTwoPaints() {
  if (typeof window === 'undefined') return Promise.resolve()
  return new Promise(resolve => {
    requestAnimationFrame(() => requestAnimationFrame(resolve))
  })
}

function preloadWorkspace(routeKey, priority = 'idle') {
  const loader = workspaceLoaders[routeKey]
  if (!loader) return Promise.resolve(false)

  const existing = workspacePreloads.get(routeKey)
  if (existing) {
    if (priority === 'immediate') {
      existing.start()
      return existing.promise
    }
    return existing.promise
  }

  let started = false
  let idleHandle = 0
  let resolvePromise
  const promise = new Promise(resolve => { resolvePromise = resolve })
  const start = () => {
    if (started) return
    started = true
    if (idleHandle && typeof window !== 'undefined' && typeof window.cancelIdleCallback === 'function') {
      window.cancelIdleCallback(idleHandle)
    }
    const warmer = workspaceWarmers[routeKey]
    Promise.all([
      Promise.resolve(loader()),
      warmer ? Promise.resolve(warmer()) : Promise.resolve(),
    ])
      .then(() => {
        resolvePromise(true)
      })
      .catch(() => {
        workspacePreloads.delete(routeKey)
        resolvePromise(false)
      })
  }

  const entry = { promise, start }
  workspacePreloads.set(routeKey, entry)

  if (priority === 'immediate' || typeof window === 'undefined') {
    start()
  } else if (typeof window.requestIdleCallback === 'function') {
    // Warm the focused workspace only when the browser is genuinely idle. Do
    // not force a timeout while the archive is moving: that would move chunk
    // parse/layout work back into the animation path we are trying to protect.
    idleHandle = window.requestIdleCallback(start)
  } else {
    idleHandle = window.setTimeout(start, 480)
  }

  return promise
}

function navigateWorkspace(routeKey) {
  const fromArchive = activeTab.value === '研究终端'
  const module = JARVIS_MODULES.find(item => item.routeKey === routeKey)
  if (module) {
    archiveModuleKey.value = module.key
    preparedWorkspaceRoute.value = routeKey
  }
  if (archiveHandoffTimer) window.clearTimeout(archiveHandoffTimer)
  archiveHandoffTimer = 0
  archiveHandoffHold.value = fromArchive
  workspaceRevealReady.value = !fromArchive
  switchTab(routeKey)

  if (fromArchive && !['行情', '多市场'].includes(routeKey)) {
    nextTick(async () => {
      await waitForTwoPaints()
      handleWorkspaceReady(routeKey)
    })
  }
}

async function handleWorkspaceReady(routeKey = activeTab.value) {
  if (routeKey !== activeTab.value || activeTab.value === '研究终端') return
  await waitForTwoPaints()
  workspaceRevealReady.value = true
  if (archiveHandoffTimer) window.clearTimeout(archiveHandoffTimer)
  archiveHandoffTimer = window.setTimeout(() => {
    archiveHandoffTimer = 0
    archiveHandoffHold.value = false
  }, 320)
}

function openLegacyAdmin() {
  if (user.value?.role !== 'ADMIN') return
  switchTab('管理')
}

function returnToArchive() {
  if (activeModule.value) archiveModuleKey.value = activeModule.value.key
  if (archiveHandoffTimer) window.clearTimeout(archiveHandoffTimer)
  archiveHandoffTimer = 0
  archiveHandoffHold.value = false
  workspaceRevealReady.value = true
  switchTab('研究终端')
}

function sendStrategyToBacktest(handoff) {
  setBacktestHandoff(handoff)
  navigateWorkspace('回测')
}

watch(sessionState, (state) => {
  if (previewMode.value) return
  if (state !== 'expired') return
  publicView.value = 'login'
  replacePublicQuery((params) => params.set('view', 'login'))
})

onMounted(() => {
  const params = new URLSearchParams(window.location.search)
  const host = window.location.hostname
  const localPreview = import.meta.env.DEV
    && (host === '127.0.0.1' || host === 'localhost')
    && params.get('preview') === '1'
  if (localPreview) {
    previewMode.value = true
    workspace.reset()
    replacePublicQuery((query) => {
      query.delete('view')
      query.delete('oauth')
      query.set('preview', '1')
    })
    return
  }
  const hasOAuthResult = params.has('oauth')
  const wantsLogin = params.get('view') === 'login'
  if (hasOAuthResult || wantsLogin) publicView.value = 'login'
  session.restore()
  // 等 LoginView 读取完 OAuth 结果后再清理地址栏，避免返回官网后重复显示旧错误。
  if (hasOAuthResult) nextTick(clearOAuthQuery)
})

onBeforeUnmount(() => {
  if (archiveHandoffTimer) window.clearTimeout(archiveHandoffTimer)
  archiveHandoffHold.value = false
})
</script>

<template>
  <LandingPage v-if="!isLoggedIn && publicView === 'landing'" @login="showLogin" />

  <div v-else-if="!isLoggedIn" class="auth-shell">
    <button type="button" class="home-back" @click="showLanding">← 返回官网</button>
    <LoginView @logged-in="handleLoggedIn" />
  </div>

  <div
    v-else
    class="container"
    :class="{
      'container--trading': activeTab === '模拟盘',
      'container--analysis': activeTab === '研究终端',
      'container--workspace': Boolean(activeModule),
    }"
  >
    <AppHeader v-if="activeTab === '管理'" :user="user" @logout="logout" @update-profile="updateProfile" />
    <AppTabs v-if="activeTab === '管理'" :tabs="tabs" :active="activeTab" @change="switchTab" />

    <AnalysisOsPage
      v-if="visitedTabs.has('研究终端')"
      v-show="activeTab === '研究终端' || archiveHandoffHold"
      :active="activeTab === '研究终端'"
      :night-mode="nightMode"
      :requested-module-key="archiveModuleKey"
      :workspace-preload="preloadWorkspace"
      @focus-change="syncArchiveModule"
      @navigate="navigateWorkspace"
      @toggle-night-mode="toggleNightMode"
    />

    <ArchiveWorkspaceShell
      v-if="preparedModule"
      :module="preparedModule"
      :active="Boolean(activeModule)"
      :revealed="workspaceRevealReady"
      :modules="JARVIS_MODULES"
      :user="user"
      :context="researchContext"
      :night-mode="nightMode"
      @return="returnToArchive"
      @navigate-module="navigateWorkspace"
      @legacy-admin="openLegacyAdmin"
      @logout="logout"
      @update-profile="updateProfile"
      @toggle-night-mode="toggleNightMode"
    >
          <MarketPage
            v-if="workspaceRenderRoute === '行情'"
            :active="activeTab === '行情' && workspaceRevealReady"
            @context-change="setResearchContext"
            @ready="handleWorkspaceReady('行情')"
          />

          <section v-else-if="workspaceRenderRoute === '多市场'" class="panel-wrap">
            <CrossMarketView
              :user="user"
              :active="activeTab === '多市场' && workspaceRevealReady"
              @context-change="setResearchContext"
              @ready="handleWorkspaceReady('多市场')"
            />
          </section>

      <BacktestPage
        v-else-if="workspaceRenderRoute === '回测'"
        :active="activeTab === '回测'"
        :handoff="backtestHandoff"
        @clear-handoff="clearBacktestHandoff"
      />

      <section v-else-if="workspaceRenderRoute === '模拟盘'">
        <SimTradeView :user="user" @context-change="setResearchContext" />
      </section>

      <section v-else-if="workspaceRenderRoute === '研究助手'" class="panel-wrap">
        <AiCenter :research-context="researchContext" />
      </section>

      <QuotePage v-else-if="workspaceRenderRoute === '智能报价'" />
      <SentimentPage v-else-if="workspaceRenderRoute === '多空研报'" />
      <FinancialReportPage v-else-if="workspaceRenderRoute === '财报解析'" :research-context="researchContext" />
      <ChainPage v-else-if="workspaceRenderRoute === '产业链图谱'" :research-context="researchContext" />
      <RiskPage v-else-if="workspaceRenderRoute === '风险预警'" :research-context="researchContext" />
      <StrategyPage v-else-if="workspaceRenderRoute === '策略生成'" @send-backtest="sendStrategyToBacktest" />
      <TrendPage v-else-if="workspaceRenderRoute === '市场趋势预测'" />

      <section v-else-if="workspaceRenderRoute === '运维'" class="panel-wrap">
        <OpsView />
      </section>
    </ArchiveWorkspaceShell>

    <section v-if="user?.role === 'ADMIN' && activeTab === '管理'" class="panel-wrap">
      <AdminView />
    </section>

    <footer v-if="activeTab === '管理'" class="foot">
      <span>贾维斯金融投研平台 · 仅供研究参考，不构成投资建议</span>
    </footer>
  </div>
</template>

<style scoped>
.container { max-width: 1580px; margin: 0 auto; padding: 0 20px 32px; }
.container--trading { max-width: none; padding-left: 12px; padding-right: 12px; padding-bottom: 12px; }
.container--analysis { max-width: none; padding: 0; }
.container--workspace { max-width: none; padding: 0; }
.panel-wrap { margin-top: 4px; }
.foot { color: var(--subtle); font-size: 11px; margin-top: 16px; }
.auth-shell { position: relative; min-height: 100vh; background: var(--bg); }
.home-back {
  position: fixed; top: 18px; left: 20px; z-index: 20;
  border: 1px solid var(--line); border-radius: 999px;
  color: var(--muted); background: rgba(18, 20, 22, .86);
  padding: 8px 13px; cursor: pointer; font-size: 12px;
  backdrop-filter: blur(10px);
  transition: color .15s ease, border-color .15s ease, background .15s ease;
}
.home-back:hover { color: var(--text); border-color: var(--line-strong); background: #1c1f22; }
@media (max-width: 620px) {
  .container { padding-left: 12px; padding-right: 12px; }
  .home-back { top: 12px; left: 12px; }
}
</style>
