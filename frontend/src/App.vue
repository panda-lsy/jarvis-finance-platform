<script setup>
import { computed, defineAsyncComponent, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { api } from './api/client'
import LoginView from './components/LoginView.vue'
import LandingPage from './pages/LandingPage.vue'
import { useAuthSession } from './composables/useAuthSession'
import { useWorkspaceTabs } from './composables/useWorkspaceTabs'
import ArchiveWorkspaceShell from './analysis-os/components/ArchiveWorkspaceShell.vue'
import { ADMIN_WORKSPACE_MODULE, JARVIS_MODULES } from './analysis-os/data/modules'
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
  '定时任务': () => import('./pages/ScheduledTasksPage.vue'),
  'RSS资讯': () => import('./pages/NewsCenterPage.vue'),
  '社区': () => import('./pages/CommunityPage.vue'),
  '个人中心': () => import('./pages/ProfilePage.vue'),
  '管理后台': () => import('./components/AdminView.vue'),
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
const ScheduledTasksPage = defineAsyncComponent(workspaceLoaders['定时任务'])
const NewsCenterPage = defineAsyncComponent(workspaceLoaders['RSS资讯'])
const CommunityPage = defineAsyncComponent(workspaceLoaders['社区'])
const ProfilePage = defineAsyncComponent(workspaceLoaders['个人中心'])
const AdminView = defineAsyncComponent(workspaceLoaders['管理后台'])
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
const { activeTab, visitedTabs, workspaceTabs, switchTab, closeWorkspaceTab } = workspace
const splitViewRoute = ref('')
const SPLIT_VIEW_ROUTES = new Set(['行情', '研究助手', '财报解析', '风险预警'])
const publicView = ref('landing')
const workspaceModules = computed(() => (
  user.value?.role === 'ADMIN'
    ? [...JARVIS_MODULES, ADMIN_WORKSPACE_MODULE]
    : JARVIS_MODULES
))
const activeModule = computed(() => workspaceModules.value.find(module => module.routeKey === activeTab.value) || null)
const preparedModule = computed(() => (
  activeModule.value
  || workspaceModules.value.find(module => module.routeKey === preparedWorkspaceRoute.value)
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
  replacePublicQuery((params) => {
    params.delete('view')
    params.delete('oauth')
  })
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

async function refreshProfileSession() {
  await session.restore()
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
  const module = workspaceModules.value.find(item => item.routeKey === routeKey)
  if (module) {
    archiveModuleKey.value = module.key
    preparedWorkspaceRoute.value = routeKey
  }
  if (archiveHandoffTimer) window.clearTimeout(archiveHandoffTimer)
  archiveHandoffTimer = 0
  archiveHandoffHold.value = fromArchive
  workspaceRevealReady.value = !fromArchive
  switchTab(routeKey)

  if (splitViewRoute.value === routeKey) splitViewRoute.value = ''
  if (fromArchive && !['行情', '多市场'].includes(routeKey)) {
    nextTick(async () => {
      await waitForTwoPaints()
      handleWorkspaceReady(routeKey)
    })
  }
}

function openSplitView(routeKey) {
  if (!SPLIT_VIEW_ROUTES.has(routeKey) || routeKey === activeTab.value) return
  splitViewRoute.value = routeKey
  preloadWorkspace(routeKey, 'immediate')
}

function closeSplitView() {
  splitViewRoute.value = ''
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

function returnToArchive() {
  if (activeModule.value && JARVIS_MODULES.some(module => module.key === activeModule.value.key)) {
    archiveModuleKey.value = activeModule.value.key
  }
  if (archiveHandoffTimer) window.clearTimeout(archiveHandoffTimer)
  archiveHandoffTimer = 0
  archiveHandoffHold.value = false
  workspaceRevealReady.value = true
  splitViewRoute.value = ''
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
      :modules="workspaceModules"
      :workspace-tabs="workspaceTabs"
      :split-route="splitViewRoute"
      :user="user"
      :context="researchContext"
      :night-mode="nightMode"
      @return="returnToArchive"
      @navigate-module="navigateWorkspace"
      @close-workspace-tab="closeWorkspaceTab"
      @open-split="openSplitView"
      @close-split="closeSplitView"
      @logout="logout"
      @update-profile="updateProfile"
      @toggle-night-mode="toggleNightMode"
    >
      <div class="workspace-view-stack" :class="{ 'is-split': splitViewRoute }">
        <section class="workspace-primary-pane">
          <MarketPage
            v-if="workspaceRenderRoute === '行情'"
            :active="activeTab === '行情' && workspaceRevealReady"
            :user="user"
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
        <SimTradeView :user="user" @context-change="setResearchContext" @navigate-module="navigateWorkspace" />
      </section>

      <section v-else-if="workspaceRenderRoute === '研究助手'" class="panel-wrap">
        <AiCenter :research-context="researchContext" @navigate-module="navigateWorkspace" />
      </section>

      <QuotePage v-else-if="workspaceRenderRoute === '智能报价'" />
      <SentimentPage v-else-if="workspaceRenderRoute === '多空研报'" />
      <FinancialReportPage v-else-if="workspaceRenderRoute === '财报解析'" :research-context="researchContext" />
      <ChainPage v-else-if="workspaceRenderRoute === '产业链图谱'" :research-context="researchContext" />
      <RiskPage v-else-if="workspaceRenderRoute === '风险预警'" :research-context="researchContext" />
      <StrategyPage v-else-if="workspaceRenderRoute === '策略生成'" @send-backtest="sendStrategyToBacktest" />
      <TrendPage v-else-if="workspaceRenderRoute === '市场趋势预测'" />
      <ScheduledTasksPage v-else-if="workspaceRenderRoute === '定时任务'" />
      <NewsCenterPage v-else-if="workspaceRenderRoute === 'RSS资讯'" @navigate-module="navigateWorkspace" />
      <CommunityPage v-else-if="workspaceRenderRoute === '社区'" />
      <ProfilePage v-else-if="workspaceRenderRoute === '个人中心'" @profile-updated="refreshProfileSession" />
      <section v-else-if="workspaceRenderRoute === '管理后台'" class="panel-wrap">
        <AdminView :current-user-id="user?.id" />
      </section>

      <section v-else-if="workspaceRenderRoute === '运维'" class="panel-wrap">
        <OpsView />
      </section>
        </section>

        <aside v-if="splitViewRoute" class="workspace-secondary-pane" :aria-label="`并排查看 ${splitViewRoute}`">
          <header class="split-pane-head">
            <div>
              <span>并排查看</span>
              <strong>{{ splitViewRoute }}</strong>
            </div>
            <button type="button" aria-label="关闭并排视图" @click="closeSplitView">×</button>
          </header>
          <div class="split-pane-body">
            <MarketPage
              v-if="splitViewRoute === '行情'"
              :active="true"
              :user="user"
              @context-change="setResearchContext"
            />
            <AiCenter
              v-else-if="splitViewRoute === '研究助手'"
              :research-context="researchContext"
              @navigate-module="navigateWorkspace"
            />
            <FinancialReportPage
              v-else-if="splitViewRoute === '财报解析'"
              :research-context="researchContext"
            />
            <RiskPage
              v-else-if="splitViewRoute === '风险预警'"
              :research-context="researchContext"
            />
          </div>
        </aside>
      </div>
    </ArchiveWorkspaceShell>

  </div>
</template>

<style scoped>
.container { max-width: 1580px; margin: 0 auto; padding: 0 20px 32px; }
.container--trading { max-width: none; padding-left: 12px; padding-right: 12px; padding-bottom: 12px; }
.container--analysis { max-width: none; padding: 0; }
.container--workspace { max-width: none; padding: 0; }
.panel-wrap { margin-top: 4px; }
.workspace-view-stack { min-width: 0; }
.workspace-view-stack.is-split {
  display: grid;
  grid-template-columns: minmax(0, 1.12fr) minmax(380px, .88fr);
  gap: 12px;
  align-items: start;
  transition: grid-template-columns var(--motion-layout, 300ms) var(--motion-ease, cubic-bezier(.22,1,.36,1));
}
.workspace-primary-pane { min-width: 0; }
.workspace-secondary-pane {
  min-width: 0;
  overflow: hidden;
  border: 0;
  border-left: 1px solid var(--line);
  border-radius: 0;
  background: transparent;
  box-shadow: none;
  animation: split-pane-in var(--motion-layout, 300ms) var(--motion-ease, cubic-bezier(.22,1,.36,1));
}
@keyframes split-pane-in {
  from { opacity: 0; transform: translateX(8px); }
  to { opacity: 1; transform: translateX(0); }
}
.split-pane-head {
  min-height: 48px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 0 12px 0 14px;
  border-bottom: 1px solid var(--line);
}
.split-pane-head > div { display: grid; gap: 3px; }
.split-pane-head span { color: var(--subtle); font-size: 8px; }
.split-pane-head strong { color: var(--text); font-size: 11px; font-weight: 650; }
.split-pane-head button {
  width: 28px;
  height: 28px;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: var(--subtle);
  cursor: pointer;
  font-size: 15px;
  transition: color var(--motion-fast, 110ms) ease, background var(--motion-fast, 110ms) ease, transform var(--motion-fast, 110ms) ease;
}
.split-pane-head button:hover { color: var(--text); background: rgba(255,255,255,.045); }
.split-pane-head button:active { transform: scale(.94); }
.split-pane-body { min-width: 0; max-height: calc(100dvh - 190px); overflow: auto; padding: 12px; }
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
@media (max-width: 1100px) {
  .workspace-view-stack.is-split { grid-template-columns: minmax(0, 1fr) minmax(330px, .72fr); gap: 9px; }
  .split-pane-body { padding: 9px; }
}
@media (max-width: 980px) {
  .workspace-view-stack.is-split { display: block; }
  .workspace-secondary-pane { display: none; }
}
@media (prefers-reduced-motion: reduce) {
  .workspace-view-stack.is-split { transition: none !important; }
  .workspace-secondary-pane { animation: none !important; }
}
</style>
