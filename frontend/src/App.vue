<script setup>
import { defineAsyncComponent, nextTick, onMounted, ref, watch } from 'vue'
import { api } from './api/client'
import LoginView from './components/LoginView.vue'
import AppHeader from './components/common/AppHeader.vue'
import AppTabs from './components/common/AppTabs.vue'
import LandingPage from './pages/LandingPage.vue'
import { useAuthSession } from './composables/useAuthSession'
import { useWorkspaceTabs } from './composables/useWorkspaceTabs'

const MarketPage = defineAsyncComponent(() => import('./pages/MarketPage.vue'))
const BacktestPage = defineAsyncComponent(() => import('./pages/BacktestPage.vue'))
const CrossMarketView = defineAsyncComponent(() => import('./components/CrossMarketView.vue'))
const SimTradeView = defineAsyncComponent(() => import('./components/SimTradeView.vue'))
const AiCenter = defineAsyncComponent(() => import('./components/AiCenter.vue'))
const SentimentPage = defineAsyncComponent(() => import('./pages/SentimentPage.vue'))
const FinancialReportPage = defineAsyncComponent(() => import('./pages/FinancialReportPage.vue'))
const ChainPage = defineAsyncComponent(() => import('./pages/ChainPage.vue'))
const RiskPage = defineAsyncComponent(() => import('./pages/RiskPage.vue'))
const StrategyPage = defineAsyncComponent(() => import('./pages/StrategyPage.vue'))
const OpsView = defineAsyncComponent(() => import('./components/OpsView.vue'))
const AdminView = defineAsyncComponent(() => import('./components/AdminView.vue'))

const session = useAuthSession()
const { user, isLoggedIn, sessionState } = session
const workspace = useWorkspaceTabs(user)
const { activeTab, visitedTabs, tabs, switchTab } = workspace
const publicView = ref('landing')

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
  await session.logout()
  workspace.reset()
  publicView.value = 'landing'
  replacePublicQuery((params) => params.delete('view'))
}

async function updateProfile(displayName) {
  const response = await api.updateProfile(displayName)
  if (response.code === 200 && response.data) session.acceptLogin(response.data)
}

watch(sessionState, (state) => {
  if (state !== 'expired') return
  publicView.value = 'login'
  replacePublicQuery((params) => params.set('view', 'login'))
})

onMounted(() => {
  const params = new URLSearchParams(window.location.search)
  const hasOAuthResult = params.has('oauth')
  const wantsLogin = params.get('view') === 'login'
  if (hasOAuthResult || wantsLogin) publicView.value = 'login'
  session.restore()
  // 等 LoginView 读取完 OAuth 结果后再清理地址栏，避免返回官网后重复显示旧错误。
  if (hasOAuthResult) nextTick(clearOAuthQuery)
})
</script>

<template>
  <LandingPage v-if="!isLoggedIn && publicView === 'landing'" @login="showLogin" />

  <div v-else-if="!isLoggedIn" class="auth-shell">
    <button type="button" class="home-back" @click="showLanding">← 返回官网</button>
    <LoginView @logged-in="handleLoggedIn" />
  </div>

  <div v-else class="container">
    <AppHeader :user="user" @logout="logout" @update-profile="updateProfile" />
    <AppTabs :tabs="tabs" :active="activeTab" @change="switchTab" />

    <MarketPage v-if="visitedTabs.has('行情')" v-show="activeTab === '行情'" :active="activeTab === '行情'" />

    <section v-if="activeTab === '多市场'" class="panel-wrap">
      <CrossMarketView :user="user" />
    </section>

    <BacktestPage v-if="visitedTabs.has('回测')" v-show="activeTab === '回测'" :active="activeTab === '回测'" />

    <section v-if="activeTab === '模拟盘'">
      <SimTradeView />
    </section>

    <section v-if="activeTab === '研究助手'" class="panel-wrap">
      <AiCenter />
    </section>

    <SentimentPage v-if="visitedTabs.has('多空研报')" v-show="activeTab === '多空研报'" />
    <FinancialReportPage v-if="visitedTabs.has('财报解析')" v-show="activeTab === '财报解析'" />
    <ChainPage v-if="visitedTabs.has('产业链图谱')" v-show="activeTab === '产业链图谱'" />
    <RiskPage v-if="visitedTabs.has('风险预警')" v-show="activeTab === '风险预警'" />
    <StrategyPage v-if="visitedTabs.has('策略生成')" v-show="activeTab === '策略生成'" />

    <section v-if="activeTab === '运维'" class="panel-wrap">
      <OpsView />
    </section>

    <section v-if="user?.role === 'ADMIN' && activeTab === '管理'" class="panel-wrap">
      <AdminView />
    </section>

    <footer class="foot">
      <span>贾维斯金融投研平台 · 仅供研究参考，不构成投资建议</span>
    </footer>
  </div>
</template>

<style scoped>
.container { max-width: 1580px; margin: 0 auto; padding: 0 20px 32px; }
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
