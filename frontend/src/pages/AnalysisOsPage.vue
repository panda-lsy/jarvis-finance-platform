<script setup>
import { computed, defineAsyncComponent, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { api } from '../api/client'
import { JARVIS_MODULES, moduleByKey, wrap } from '../analysis-os/data/modules'
import { useArchiveAudio } from '../analysis-os/audio/useArchiveAudio'
import { useArchiveIdle } from '../analysis-os/motion/useArchiveIdle'
import { useArchiveTransition } from '../analysis-os/motion/useArchiveTransition'

const emit = defineEmits(['navigate', 'focus-change', 'toggle-night-mode'])
const AnalysisArchiveScene = defineAsyncComponent(() => import('../components/analysis/AnalysisArchiveScene.vue'))
const props = defineProps({
  active: { type: Boolean, default: false },
  nightMode: { type: Boolean, default: false },
  requestedModuleKey: { type: String, default: '' },
  workspacePreload: { type: Function, default: null },
})

const modules = JARVIS_MODULES
const focusedKey = ref(modules[0].key)
const retrievalState = ref('FOCUSED')
const retrievalDirection = ref(0)
const indexOpen = ref(false)
const moduleIndexExpanded = ref(false)
const query = ref('')
const booting = ref(true)
const bootPhase = ref('WAKE')
const dataState = ref('loading')
const dataError = ref('')
const lastUpdated = ref('')
const clock = ref('—')
const moduleIndexRef = ref(null)
const archiveSceneRef = ref(null)
const motionAmount = ref(0)
const detailDensity = ref(0.42)
const settleProgress = ref(1)
const handoffProgress = ref(0)
const handoffPhase = ref('IDLE')
let bootTimer = 0
const bootTimers = []
let clockTimer = 0
let syncVersion = 0
let retrievalTimer = 0
let matchTimer = 0
let handoffFrame = 0
let handoffRevision = 0
let lastNavigationDirection = 1
const reducedMotion = typeof window !== 'undefined'
  && window.matchMedia?.('(prefers-reduced-motion: reduce)').matches
const archiveIdle = useArchiveIdle({ reduced: reducedMotion })
const { environmentState, sleepAmount, hudDim } = archiveIdle
const archiveTransition = useArchiveTransition()
const { transitionState, extractionProgress } = archiveTransition
const archiveAudio = useArchiveAudio()
const { enabled: soundEnabled } = archiveAudio
// The post-extraction beat is an animated handoff rather than a dead timeout.
// This keeps the archive visibly alive while the destination chunk is already
// being fetched/parsed in the background.
const WORKSPACE_HANDOFF_MS = 1420
const WORKSPACE_HANDOFF_REDUCED_MS = 180

const focusedModule = computed(() => moduleByKey(focusedKey.value, modules))
const focusedIndex = computed(() => Math.max(0, modules.findIndex(module => module.key === focusedKey.value)))
const moduleNumber = computed(() => String((focusedModule.value?.no || 1)).padStart(2, '0'))
const moduleTotal = computed(() => String(modules.length).padStart(2, '0'))
const filteredModules = computed(() => {
  const needle = query.value.trim().toLowerCase()
  if (!needle) return modules
  return modules.filter(module => [
    module.labelEn, module.labelZh, module.category, module.code, module.summary,
    ...module.capabilities,
  ].some(value => String(value || '').toLowerCase().includes(needle)))
})
const dataStateLabel = computed(() => ({
  loading: 'SYNCING', live: 'LIVE API', catalog: 'CATALOG', fallback: 'FALLBACK',
}[dataState.value] || 'UNKNOWN'))
const documentRevealAmount = computed(() => Math.max(0, Math.min(1, (extractionProgress.value - 0.72) / 0.28)))
const clamp01 = value => Math.max(0, Math.min(1, value))
const rangeProgress = (value, start, end) => clamp01((value - start) / Math.max(0.001, end - start))
const easeProgress = value => {
  const t = clamp01(value)
  return t * t * (3 - 2 * t)
}
const handoffVisual = computed(() => {
  const active = handoffPhase.value !== 'IDLE'
  const progress = active ? handoffProgress.value : 0
  const transfer = easeProgress(rangeProgress(progress, 0.04, 1))
  return {
    archiveOpacity: 1 - transfer * 0.06,
    panelOpacity: active ? 0.86 + easeProgress(rangeProgress(progress, 0.03, 0.82)) * 0.14 : 0.86,
    line: active ? easeProgress(rangeProgress(progress, 0.08, 0.56)) : 0,
    scanX: active ? -120 + easeProgress(rangeProgress(progress, 0.18, 0.92)) * 640 : -120,
    readyOpacity: active ? 0.70 + easeProgress(rangeProgress(progress, 0.28, 0.76)) * 0.30 : 0.70,
  }
})
const analysisStyle = computed(() => ({
  '--hud-opacity': (1 - hudDim.value * 0.45).toFixed(3),
  '--handoff-archive-opacity': handoffVisual.value.archiveOpacity.toFixed(3),
  '--handoff-line': handoffVisual.value.line.toFixed(3),
  '--handoff-scan-x': `${handoffVisual.value.scanX.toFixed(1)}%`,
  '--handoff-ready-opacity': handoffVisual.value.readyOpacity.toFixed(3),
}))
const documentRevealStyle = computed(() => ({
  opacity: documentRevealAmount.value * handoffVisual.value.panelOpacity,
  // A large animated clip-path repainted the entire reveal surface on every
  // extraction frame. Use compositor-only opacity/translation instead so the
  // card-to-handoff boundary does not stall the main thread.
  transform: `translate3d(${((1 - documentRevealAmount.value) * 12).toFixed(2)}px, 0, 0)`,
}))
const calloutFileOpacity = computed(() => {
  if (retrievalState.value === 'FLOW') return 0.28 - motionAmount.value * 0.16
  if (retrievalState.value === 'QUERY') return 0.42
  if (retrievalState.value === 'MATCH') return 0.62
  return 0.70 + 0.30 * rangeProgress(settleProgress.value, 0, 0.45)
})
const calloutNameOpacity = computed(() => {
  if (retrievalState.value === 'FLOW') return 0.45 - motionAmount.value * 0.20
  if (retrievalState.value === 'QUERY') return 0.52
  if (retrievalState.value === 'MATCH') return 0.72
  return 0.56 + 0.44 * rangeProgress(settleProgress.value, 0.16, 0.72)
})
const calloutCtaOpacity = computed(() => {
  if (retrievalState.value !== 'FOCUSED') return 0
  return 0.82 * rangeProgress(settleProgress.value, 0.62, 1)
})
const calloutCtaReady = computed(() => retrievalState.value === 'FOCUSED'
  && settleProgress.value >= 0.72
  && motionAmount.value < 0.14)
const calloutStyle = computed(() => ({
  '--file-opacity': calloutFileOpacity.value.toFixed(3),
  '--name-opacity': calloutNameOpacity.value.toFixed(3),
  '--cta-opacity': calloutCtaOpacity.value.toFixed(3),
  '--callout-blur': `${(motionAmount.value * 0.72).toFixed(3)}px`,
  '--callout-shift': `${(motionAmount.value * 4.8 + (1 - calloutNameOpacity.value) * 1.8).toFixed(2)}px`,
  '--rule-scale': (0.30 + 0.70 * Math.max(calloutNameOpacity.value, settleProgress.value)).toFixed(3),
}))
const accessStage = computed(() => {
  const progress = extractionProgress.value
  if (progress < 0.16) return { code: 'RELEASE LOCK', detail: 'ARCHIVE LOCK RELEASED' }
  if (progress < 0.43) return { code: 'VERTICAL EXTRACTION', detail: 'ARCHIVE LIFT / CAMERA HOLD' }
  if (progress < 0.68) return { code: 'CAMERA APPROACH', detail: 'SPATIAL CONTEXT ALIGNING' }
  if (progress < 0.9) return { code: 'GLASS DECRYPT', detail: 'INTERNAL STRUCTURE REVEALED' }
  return { code: 'DOCUMENT REVEAL', detail: 'RESEARCH CONTEXT READY' }
})
const bootCopy = computed(() => ({
  WAKE: { code: 'SYSTEM WAKE', detail: 'POWER BUS · RENDER CORE · INPUT GRID', progress: 18 },
  IDENTITY: { code: 'IDENTITY RESOLVED', detail: 'JARVIS ANALYSIS OS · LOCAL SESSION', progress: 42 },
  PERMISSION: { code: 'PERMISSION SCAN', detail: 'MODULE INDEX · RESEARCH CONTEXT · WORKSPACE', progress: 68 },
  ARCHIVE: { code: 'MODULE ARCHIVE ONLINE', detail: 'SPATIAL ARRAY · MOTION FIELD · ACCESS SURFACE', progress: 92 },
  READY: { code: 'SYSTEM READY', detail: 'ARCHIVE CONTROL TRANSFERRED', progress: 100 },
}[bootPhase.value] || { code: 'SYSTEM WAKE', detail: 'INITIALIZING', progress: 0 }))

async function syncSystemStatus() {
  const version = ++syncVersion
  dataState.value = 'loading'
  dataError.value = ''
  try {
    const response = await api.marketInstruments()
    if (version !== syncVersion) return
    if (response?.code !== 200 || !Array.isArray(response?.data)) {
      throw new Error(response?.message || '市场目录连接失败')
    }
    dataState.value = response.data.length ? 'live' : 'catalog'
    lastUpdated.value = new Date().toLocaleTimeString('zh-CN', { hour12: false })
  } catch (error) {
    if (version !== syncVersion) return
    dataState.value = 'fallback'
    dataError.value = error?.message || '系统数据通道连接失败'
    lastUpdated.value = ''
  }
}

function clearRetrievalTimers() {
  if (retrievalTimer) window.clearTimeout(retrievalTimer)
  if (matchTimer) window.clearTimeout(matchTimer)
  retrievalTimer = 0
  matchTimer = 0
}

function bindModule(key, source = 'external') {
  const module = moduleByKey(key, modules)
  if (!module || extractionProgress.value > 0.001) return
  clearRetrievalTimers()
  const changed = focusedKey.value !== module.key
  focusedKey.value = module.key
  retrievalState.value = 'FOCUSED'
  transitionState.value = 'FOCUSED'
  if (changed) emit('focus-change', module.key)
  if (source !== 'scene') archiveIdle.activity(source)
  scrollActiveIndexIntoView()
}

function handleSceneFlow(direction = 0) {
  clearRetrievalTimers()
  retrievalState.value = 'FLOW'
  transitionState.value = 'BROWSING'
  if (direction) {
    retrievalDirection.value = Math.sign(direction)
    lastNavigationDirection = Math.sign(direction)
  }
}

function handleSceneMotion(payload = {}) {
  if (Number.isFinite(payload.motionAmount)) motionAmount.value = clamp01(payload.motionAmount)
  if (Number.isFinite(payload.detailDensity)) detailDensity.value = clamp01(payload.detailDensity)
  if (Number.isFinite(payload.settleProgress)) settleProgress.value = clamp01(payload.settleProgress)
}

function handleSceneStep(delta) {
  if (!delta || extractionProgress.value > 0.001) return
  handleSceneFlow(delta)
  const nextIndex = wrap(focusedIndex.value + delta, modules.length)
  const next = modules[nextIndex]
  if (!next) return
  focusedKey.value = next.key
  emit('focus-change', next.key)
}

function beginRetrieval() {
  clearRetrievalTimers()
  retrievalState.value = 'QUERY'
  const queryDelay = reducedMotion ? 35 : 180
  const matchDelay = reducedMotion ? 45 : 230
  retrievalTimer = window.setTimeout(() => {
    retrievalState.value = 'MATCH'
    archiveAudio.play('focus')
    matchTimer = window.setTimeout(() => {
      retrievalState.value = 'FOCUSED'
      transitionState.value = 'FOCUSED'
    }, matchDelay)
  }, queryDelay)
}

function handleSceneSettled() {
  if (extractionProgress.value > 0.001) return
  beginRetrieval()
}

function navigateBySteps(steps, source = 'index') {
  if (!steps || extractionProgress.value > 0.001) return
  archiveIdle.activity(source)
  handleSceneFlow(steps)
  const scene = archiveSceneRef.value
  if (scene?.shiftRows) {
    scene.shiftRows(steps, source)
    return
  }
  handleSceneStep(steps)
  beginRetrieval()
}

function shortestModuleDelta(targetKey) {
  const targetIndex = modules.findIndex(module => module.key === targetKey)
  if (targetIndex < 0) return 0
  const current = focusedIndex.value
  const positive = wrap(targetIndex - current, modules.length)
  const negative = positive - modules.length
  if (Math.abs(positive) < Math.abs(negative)) return positive
  if (Math.abs(negative) < Math.abs(positive)) return negative
  return lastNavigationDirection >= 0 ? positive : negative
}

function navigateToModule(module, source = 'index') {
  if (!module) return
  if (module.key === focusedKey.value && retrievalState.value === 'FOCUSED') {
    activateModule(module.key)
    return
  }
  const delta = shortestModuleDelta(module.key)
  if (!delta) {
    bindModule(module.key, source)
    return
  }
  navigateBySteps(delta, source)
}

function cancelWorkspaceHandoff(reset = true) {
  handoffRevision += 1
  if (handoffFrame) cancelAnimationFrame(handoffFrame)
  handoffFrame = 0
  if (reset) {
    handoffProgress.value = 0
    handoffPhase.value = 'IDLE'
  }
}

function animateWorkspaceHandoff(reduced = false) {
  cancelWorkspaceHandoff(true)
  const runRevision = handoffRevision
  const duration = reduced ? WORKSPACE_HANDOFF_REDUCED_MS : WORKSPACE_HANDOFF_MS
  const start = performance.now()
  handoffPhase.value = 'SETTLING'
  handoffProgress.value = 0
  return new Promise(resolve => {
    const tick = now => {
      if (runRevision !== handoffRevision) {
        resolve(false)
        return
      }
      const progress = duration <= 0 ? 1 : clamp01((now - start) / duration)
      handoffProgress.value = progress
      handoffPhase.value = progress < 0.19
        ? 'SETTLING'
        : progress < 0.72
          ? 'PRESENTING'
          : 'HANDOFF'
      if (progress >= 1) {
        handoffProgress.value = 1
        handoffFrame = 0
        resolve(true)
        return
      }
      handoffFrame = requestAnimationFrame(tick)
    }
    handoffFrame = requestAnimationFrame(tick)
  })
}

function waitForPaint() {
  return new Promise(resolve => {
    requestAnimationFrame(() => requestAnimationFrame(resolve))
  })
}

async function activateModule(key = focusedKey.value) {
  const module = moduleByKey(key, modules)
  if (!module) return
  if (extractionProgress.value > 0.001 || transitionState.value === 'EXTRACTING') return
  if (retrievalState.value === 'FLOW' || retrievalState.value === 'QUERY') return
  archiveIdle.activity('activate')
  focusedKey.value = module.key
  await nextTick()

  archiveAudio.play('extract')
  const entered = await archiveTransition.enter(reducedMotion)
  if (!entered) return
  const handedOff = await animateWorkspaceHandoff(reducedMotion)
  if (!handedOff) return

  // Keep destination parsing and chart setup out of the visible extraction.
  // If idle preload has not finished yet, complete it only after motion has
  // settled on the final handoff frame.
  const workspaceReady = props.workspacePreload?.(module.routeKey, 'immediate')
  if (workspaceReady?.then) {
    await workspaceReady
    if (!reducedMotion) await waitForPaint()
  }

  archiveAudio.play('reveal')
  if (!reducedMotion) await waitForPaint()
  emit('navigate', module.routeKey)
  archiveTransition.workspaceActive()
}

function handleIndexClick(module) {
  navigateToModule(module, 'index')
}

function moveLinear(delta) {
  navigateBySteps(delta, 'module-step')
}

function moveVertical(delta) {
  navigateBySteps(delta, 'module-row-step')
}

function scrollActiveIndexIntoView() {
  if (!moduleIndexExpanded.value) return
  nextTick(() => {
    const root = moduleIndexRef.value
    const active = root?.querySelector?.(`[data-module-key="${focusedKey.value}"]`)
    active?.scrollIntoView?.({ behavior: 'smooth', block: 'nearest', inline: 'center' })
  })
}

function onKeydown(event) {
  if (!props.active) return
  if (indexOpen.value && event.key === 'Escape') {
    event.preventDefault()
    indexOpen.value = false
    archiveIdle.activity('escape')
    return
  }
  if (indexOpen.value) return
  const target = event.target
  if (target instanceof HTMLInputElement || target instanceof HTMLTextAreaElement) return
  if (event.key === 'ArrowLeft') {
    event.preventDefault()
    archiveIdle.activity('key')
    moveLinear(-1)
  } else if (event.key === 'ArrowRight') {
    event.preventDefault()
    archiveIdle.activity('key')
    moveLinear(1)
  } else if (event.key === 'ArrowUp') {
    event.preventDefault()
    archiveIdle.activity('key')
    moveVertical(-1)
  } else if (event.key === 'ArrowDown') {
    event.preventDefault()
    archiveIdle.activity('key')
    moveVertical(1)
  } else if (event.key === 'Enter') {
    event.preventDefault()
    archiveIdle.activity('key')
    activateModule()
  } else if (event.key === '/') {
    event.preventDefault()
    archiveIdle.activity('search')
    indexOpen.value = true
    nextTick(() => document.querySelector('.module-search input')?.focus())
  }
}

function completeBoot() {
  bootPhase.value = 'READY'
  booting.value = false
}

function skipBoot() {
  bootTimers.splice(0).forEach(timer => window.clearTimeout(timer))
  completeBoot()
}

function scheduleBootSequence(reduced = false) {
  bootTimers.splice(0).forEach(timer => window.clearTimeout(timer))
  bootPhase.value = reduced ? 'READY' : 'WAKE'
  if (reduced) {
    bootTimer = window.setTimeout(completeBoot, 80)
    return
  }
  const steps = [
    [260, 'IDENTITY'],
    [720, 'PERMISSION'],
    [1320, 'ARCHIVE'],
    [1900, 'READY'],
  ]
  for (const [delay, phase] of steps) {
    const timer = window.setTimeout(() => {
      bootPhase.value = phase
    }, delay)
    bootTimers.push(timer)
  }
  bootTimers.push(window.setTimeout(completeBoot, 2250))
}

function toggleSound() {
  archiveAudio.toggle()
}

watch(focusedKey, key => {
  scrollActiveIndexIntoView()
  const module = moduleByKey(key, modules)
  if (module?.routeKey) props.workspacePreload?.(module.routeKey, 'idle')
}, { flush: 'post', immediate: true })
watch(moduleIndexExpanded, expanded => {
  if (expanded) scrollActiveIndexIntoView()
})
watch(() => accessStage.value.code, code => {
  if (extractionProgress.value <= 0.001) return
  if (code === 'GLASS DECRYPT') archiveAudio.play('decrypt')
  if (code === 'DOCUMENT REVEAL') archiveAudio.play('reveal')
})
watch(() => props.requestedModuleKey, key => {
  if (!key || key === focusedKey.value || !moduleByKey(key, modules)) return
  bindModule(key, 'external')
})
watch(() => props.active, active => {
  if (!active) {
    cancelWorkspaceHandoff(true)
    archiveIdle.setEnabled(false)
    return
  }
  if (transitionState.value === 'WORKSPACE_ACTIVE') {
    archiveIdle.setEnabled(false)
    nextTick(async () => {
      archiveAudio.play('return')
      await archiveTransition.returnToArchive(reducedMotion)
      if (props.requestedModuleKey
        && props.requestedModuleKey !== focusedKey.value
        && moduleByKey(props.requestedModuleKey, modules)) {
        bindModule(props.requestedModuleKey, 'external')
      }
      archiveIdle.setEnabled(true)
      archiveIdle.activity('return-to-archive')
      nextTick(() => {
        moduleIndexRef.value
          ?.querySelector?.(`[data-module-key="${focusedKey.value}"]`)
          ?.focus?.({ preventScroll: true })
      })
    })
    return
  }
  archiveIdle.setEnabled(true)
  archiveIdle.activity('archive-visible')
})

onMounted(() => {
  const reduced = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches
  scheduleBootSequence(reduced)
  const updateClock = () => { clock.value = new Date().toLocaleTimeString('zh-CN', { hour12: false }) }
  updateClock()
  clockTimer = window.setInterval(updateClock, 1000)
  window.addEventListener('keydown', onKeydown)
  syncSystemStatus()
  scrollActiveIndexIntoView()
  if (props.active) archiveIdle.start()
  if (props.requestedModuleKey && moduleByKey(props.requestedModuleKey, modules)) {
    bindModule(props.requestedModuleKey, 'external')
  } else {
    emit('focus-change', focusedKey.value)
  }
  if (typeof window !== 'undefined') {
    const debug = {}
    Object.defineProperties(debug, {
      focusedKey: { enumerable: true, get: () => focusedKey.value },
      environmentState: { enumerable: true, get: () => environmentState.value },
      sleepAmount: { enumerable: true, get: () => sleepAmount.value },
      extractionProgress: { enumerable: true, get: () => extractionProgress.value },
      transitionState: { enumerable: true, get: () => transitionState.value },
      retrievalState: { enumerable: true, get: () => retrievalState.value },
      retrievalDirection: { enumerable: true, get: () => retrievalDirection.value },
      motionAmount: { enumerable: true, get: () => motionAmount.value },
      detailDensity: { enumerable: true, get: () => detailDensity.value },
      settleProgress: { enumerable: true, get: () => settleProgress.value },
      handoffProgress: { enumerable: true, get: () => handoffProgress.value },
      handoffPhase: { enumerable: true, get: () => handoffPhase.value },
      scene: { enumerable: true, get: () => archiveSceneRef.value?.getDebugState?.() || null },
    })
    window.__jarvisArchiveDebug = debug
  }
})

onBeforeUnmount(() => {
  syncVersion += 1
  cancelWorkspaceHandoff(true)
  clearRetrievalTimers()
  if (bootTimer) window.clearTimeout(bootTimer)
  bootTimers.splice(0).forEach(timer => window.clearTimeout(timer))
  if (clockTimer) window.clearInterval(clockTimer)
  window.removeEventListener('keydown', onKeydown)
  if (typeof window !== 'undefined') delete window.__jarvisArchiveDebug
})
</script>

<template>
  <section
    class="analysis-os"
    :class="{
      'is-idle': environmentState !== 'AWAKE',
      'is-sleeping': environmentState === 'SLEEP_DRIFT',
      'is-extracting': extractionProgress > 0.001,
      'is-browsing': retrievalState !== 'FOCUSED' && extractionProgress < 0.01,
      'is-handoff': handoffPhase !== 'IDLE',
      'is-night': props.nightMode,
    }"
    :style="analysisStyle"
    aria-label="JARVIS Analysis OS 模块档案终端"
    @pointerdown.capture="archiveIdle.activity('pointer')"
  >
    <div v-if="booting" class="boot-layer" :data-phase="bootPhase" @click="skipBoot">
      <div class="boot-scan-grid" aria-hidden="true"></div>
      <div class="boot-system-mark" aria-hidden="true">
        <div class="boot-orbit"><span></span><i></i></div>
        <div class="boot-axis"></div>
      </div>
      <div class="boot-copy">
        <span>JARVIS / FINANCIAL RESEARCH</span>
        <p>{{ bootCopy.code }}</p>
        <h1>ANALYSIS <b>OS</b></h1>
        <small>{{ bootCopy.detail }}</small>
        <div class="boot-line"><i :style="{ width: `${bootCopy.progress}%` }"></i></div>
        <div class="boot-readout">
          <span>{{ String(bootCopy.progress).padStart(3, '0') }}%</span>
          <strong>{{ bootPhase }}</strong>
        </div>
      </div>
      <div class="boot-permission" aria-hidden="true">
        <span :class="{ active: ['IDENTITY','PERMISSION','ARCHIVE','READY'].includes(bootPhase) }">IDENTITY</span>
        <span :class="{ active: ['PERMISSION','ARCHIVE','READY'].includes(bootPhase) }">ACCESS</span>
        <span :class="{ active: ['ARCHIVE','READY'].includes(bootPhase) }">ARCHIVE</span>
      </div>
      <button type="button" @click.stop="skipBoot">SKIP / ENTER</button>
    </div>

    <div class="archive-stage">
      <AnalysisArchiveScene
        ref="archiveSceneRef"
        :modules="modules"
        :focused-key="focusedKey"
        :retrieval-state="retrievalState"
        :sleep-amount="sleepAmount"
        :extraction-progress="extractionProgress"
        :active="props.active"
        :night-mode="props.nightMode"
        @flow="handleSceneFlow"
        @step="handleSceneStep"
        @settled="handleSceneSettled"
        @activate="activateModule"
        @interaction="archiveIdle.activity"
        @motion="handleSceneMotion"
      />
    </div>

    <div class="archive-aurora" aria-hidden="true">
      <i class="archive-aurora-band"></i>
    </div>

    <header class="terminal-brand" aria-label="JARVIS Analysis OS">
      <h1>JARVIS</h1>
      <div>FINANCIAL RESEARCH</div>
      <p>ANALYSIS <b>OS</b></p>
    </header>

    <section class="module-index-shell" :class="{ expanded: moduleIndexExpanded }" aria-label="Module Index">
      <button
        type="button"
        class="module-index-label"
        :aria-expanded="moduleIndexExpanded"
        @click="moduleIndexExpanded = !moduleIndexExpanded"
      >
        MODULE INDEX
        <span>{{ moduleNumber }} / {{ focusedModule?.labelEn }}</span>
      </button>
      <div ref="moduleIndexRef" class="module-index-track" role="tablist" aria-label="系统模块索引">
        <button
          v-for="module in modules"
          :key="module.key"
          type="button"
          role="tab"
          :data-module-key="module.key"
          :aria-selected="module.key === focusedKey"
          :class="{ active: module.key === focusedKey }"
          @click="handleIndexClick(module)"
        >
          <span>{{ String(module.no).padStart(2, '0') }}</span>
          <strong>{{ module.labelEn }}</strong>
          <small>{{ module.labelZh }}</small>
        </button>
      </div>
      <div class="module-index-tools">
        <button type="button" @click="indexOpen = true">⌕ SEARCH</button>
        <button type="button" @click="emit('navigate', '智能报价')">QUOTE</button>
        <button type="button" :disabled="dataState === 'loading'" @click="syncSystemStatus">↻ SYNC</button>
        <button type="button" :aria-pressed="soundEnabled" @click="toggleSound">{{ soundEnabled ? '◉ SOUND' : '○ SOUND' }}</button>
        <button
          type="button"
          class="archive-theme-toggle"
          :aria-pressed="props.nightMode"
          :aria-label="props.nightMode ? '切换到日间模式' : '切换到夜间模式'"
          @click="emit('toggle-night-mode')"
        >{{ props.nightMode ? '● NIGHT' : '○ NIGHT' }}</button>
        <span>{{ dataStateLabel }}</span>
        <time>{{ clock }}</time>
      </div>
    </section>

    <p v-if="dataError" class="data-warning">
      DATA CHANNEL INTERRUPTED · {{ dataError }}。模块入口仍可使用。
    </p>

    <section
      v-if="focusedModule && extractionProgress < 0.01"
      class="archive-callout"
      :class="{ 'is-settled': retrievalState === 'FOCUSED' }"
      :data-state="retrievalState"
      :style="calloutStyle"
      aria-label="当前聚焦模块"
    >
      <p class="file-number">FILE NUMBER: {{ focusedModule.code }}</p>
      <div class="callout-meta">
        <strong>{{ focusedModule.labelEn }}</strong>
        <span>{{ focusedModule.labelZh }}</span>
      </div>
      <div class="callout-rule"><span></span></div>
      <button
        type="button"
        :disabled="!calloutCtaReady"
        @click="activateModule(focusedModule.key)"
      >ACCESS FILE <span>→</span></button>
    </section>

    <section
      v-if="extractionProgress > 0.01 && focusedModule"
      class="access-sequence"
      aria-live="polite"
      :style="{ opacity: Math.max(.04, 1 - documentRevealAmount * 1.55) }"
    >
      <span>ACCESSING MODULE / {{ moduleNumber }}</span>
      <strong>{{ focusedModule.labelEn }}</strong>
      <small>{{ accessStage.code }}</small>
      <div class="access-progress"><i :style="{ width: `${Math.round(extractionProgress * 100)}%` }"></i></div>
      <p>{{ accessStage.detail }}</p>
    </section>

    <section
      v-if="focusedModule && documentRevealAmount > 0"
      class="document-reveal"
      :data-handoff-phase="handoffPhase"
      aria-hidden="true"
      :style="documentRevealStyle"
    >
      <header>
        <span>MODULE WORKSPACE / {{ moduleNumber }}</span>
        <strong>{{ focusedModule.labelEn }}</strong>
        <small>{{ focusedModule.labelZh }}</small>
      </header>
      <div class="document-grid">
        <span v-for="item in focusedModule.capabilities" :key="item">{{ item }}</span>
      </div>
      <footer>RESEARCH SURFACE / READY</footer>
    </section>

    <div class="archive-counter" aria-live="polite">
      <span>ARCHIVE / SELECT</span>
      <div class="counter-line"><strong>{{ moduleNumber }}</strong><i>/ {{ moduleTotal }}</i></div>
      <div class="archive-navigation">
        <button type="button" aria-label="上一个模块" @click="moveLinear(-1)">←</button>
        <button type="button" aria-label="下一个模块" @click="moveLinear(1)">→</button>
      </div>
    </div>

    <div class="archive-hint" aria-hidden="true">
      <span>DRAG / FREE PLANE</span><i></i><span>WHEEL / ROW</span>
    </div>

    <div class="column-navigation" aria-label="当前模块列">
      <span>MODULE {{ moduleNumber }} / {{ moduleTotal }}</span>
      <strong>{{ focusedModule?.labelZh }} / {{ focusedModule?.labelEn }}</strong>
    </div>

    <section v-if="indexOpen" class="module-directory" aria-label="模块目录">
      <header>
        <div><span>MODULE DIRECTORY</span><strong>{{ filteredModules.length }} / {{ modules.length }}</strong></div>
        <button type="button" aria-label="关闭模块目录" @click="indexOpen = false">×</button>
      </header>
      <label class="module-search">
        <span aria-hidden="true"></span>
        <input v-model="query" type="search" placeholder="搜索模块、能力或分类" autocomplete="off" />
        <kbd>ESC</kbd>
      </label>
      <div class="module-directory-list">
        <button
          v-for="module in filteredModules"
          :key="module.key"
          type="button"
          :class="{ active: module.key === focusedKey }"
          @click="navigateToModule(module, 'search'); indexOpen = false"
        >
          <span>{{ String(module.no).padStart(2, '0') }}</span>
          <strong>{{ module.labelEn }}</strong>
          <small>{{ module.labelZh }} · {{ module.category }}</small>
        </button>
      </div>
    </section>

    <div class="powered">POWERED BY <b>JARVIS</b><i></i></div>
  </section>
</template>

<style scoped>
.analysis-os {
  --paper: #e8e5e1;
  --ink: #20221d;
  --muted-ink: #77736a;
  --faint-ink: #aaa398;
  --rule: #bcb6ab;
  --accent: #8a7657;
  position: relative; width: 100%; height: 100dvh; min-height: 620px; overflow: hidden;
  color: var(--ink); background: var(--paper);
  font-family: "MiSans", "Mi Sans", "PingFang SC", "Microsoft YaHei", system-ui, sans-serif;
  transition: background-color .65s cubic-bezier(.22,1,.36,1), color .42s ease;
}
.analysis-os.is-night {
  /* Midnight Atelier: near-monochrome graphite, ivory type and scarce champagne metal. */
  --paper: #0c1013;
  --ink: #eee8de;
  --muted-ink: #aaa69f;
  --faint-ink: #666a6c;
  --rule: rgba(205,201,193,.115);
  --accent: #ad956d;
}
.archive-stage {
  position: absolute; inset: 0; z-index: 0;
  opacity: var(--handoff-archive-opacity, 1);
  will-change: opacity;
}
.archive-aurora {
  position: absolute;
  inset: -7%;
  z-index: 1;
  overflow: hidden;
  pointer-events: none;
  opacity: 0;
  transform: translate3d(0, 0, 0) scale(1.015);
  transition: opacity .8s cubic-bezier(.22,1,.36,1);
  will-change: opacity, transform;
}
.archive-aurora::before {
  content: '';
  position: absolute;
  inset: 0;
  background:
    radial-gradient(ellipse at 55% 46%, rgba(212,184,137,.105) 0%, rgba(148,121,82,.032) 26%, transparent 50%),
    linear-gradient(180deg, rgba(139,153,154,.022) 0%, rgba(111,126,129,.008) 20%, transparent 40%);
  opacity: .62;
  transform: translate3d(-.45%, -.15%, 0) scale(1.012);
  animation: archive-aurora-drift 28s ease-in-out infinite alternate;
  will-change: transform, opacity;
}
.archive-aurora::after {
  content: '';
  position: absolute;
  left: -10%;
  top: 3%;
  width: 120%;
  height: 26%;
  background: linear-gradient(116deg, transparent 35%, rgba(147,158,158,.012) 47%, rgba(203,177,130,.012) 54%, transparent 68%);
  transform: rotate(-.8deg) translate3d(-.5%, 0, 0);
  opacity: .34;
  animation: archive-dawn-band 30s ease-in-out infinite alternate;
  will-change: transform, opacity;
}
.archive-aurora-band {
  position: absolute;
  right: 13%;
  top: 25%;
  width: 14%;
  height: 18%;
  border-radius: 50%;
  background: radial-gradient(ellipse, rgba(197,180,150,.019), rgba(145,149,147,.007) 48%, transparent 72%);
  opacity: .18;
  transform: translate3d(0, 0, 0);
  animation: archive-assistant-glow 20s ease-in-out infinite alternate;
  will-change: opacity, transform;
}
.analysis-os.is-night .archive-aurora { opacity: .62; }
.analysis-os.is-night::after {
  content: '';
  position: absolute;
  inset: 0;
  z-index: 2;
  pointer-events: none;
  background:
    radial-gradient(ellipse at 54% 47%, transparent 35%, rgba(3,5,6,.075) 72%, rgba(2,3,4,.16) 100%),
    linear-gradient(180deg, rgba(255,247,232,.018), transparent 8%, transparent 88%, rgba(0,0,0,.08));
  box-shadow: inset 0 1px rgba(236,223,200,.035);
}
.boot-layer {
  position: fixed; inset: 0; z-index: 200; overflow: hidden;
  background: #e6e2da; color: #171914; cursor: pointer;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  opacity: 1; transition: opacity .34s ease, background .55s ease;
}
.boot-layer::after {
  content: ''; position: absolute; inset: 0; pointer-events: none;
  background: radial-gradient(circle at 50% 48%, rgba(255,255,255,.32), transparent 36%), linear-gradient(180deg, rgba(255,255,255,.18), transparent 42%);
}
.boot-scan-grid {
  position: absolute; inset: 0; opacity: .46;
  background-image:
    linear-gradient(rgba(87,83,75,.08) 1px, transparent 1px),
    linear-gradient(90deg, rgba(87,83,75,.08) 1px, transparent 1px);
  background-size: 48px 48px;
  mask-image: linear-gradient(180deg, #000, transparent 82%);
  transform: scale(1.06); transition: opacity .45s ease, transform .8s cubic-bezier(.22,1,.36,1);
}
.boot-system-mark {
  position: absolute; left: 11vw; top: 50%; width: 170px; height: 170px; transform: translateY(-50%);
  transition: transform .7s cubic-bezier(.22,1,.36,1), opacity .45s ease;
}
.boot-orbit { position: absolute; inset: 0; border: 1px solid rgba(74,72,66,.5); border-radius: 50%; }
.boot-orbit::before, .boot-orbit::after { content: ''; position: absolute; border: 1px solid rgba(117,111,101,.34); border-radius: 50%; }
.boot-orbit::before { inset: 21px; }
.boot-orbit::after { inset: 51px; }
.boot-orbit span { position: absolute; left: 50%; top: -17px; width: 1px; height: 204px; background: #716b60; transform: rotate(31deg); transform-origin: center; animation: orbit-line 1.2s cubic-bezier(.22,1,.36,1) both; }
.boot-orbit i { position: absolute; left: 50%; top: 50%; width: 8px; height: 8px; margin: -4px; background: #252720; border-radius: 50%; box-shadow: 0 0 0 11px rgba(49,48,44,.06); }
.boot-axis { position: absolute; left: -26px; right: -26px; top: 50%; height: 1px; background: rgba(82,79,73,.28); }
.boot-copy { position: absolute; left: 36vw; right: 14vw; top: 50%; transform: translateY(-50%); }
.boot-copy > span { color: #8b857a; font-size: 8px; letter-spacing: .18em; }
.boot-copy p { margin: 20px 0 8px; color: #4e4c46; font-size: 10px; letter-spacing: .23em; }
.boot-copy h1 { margin: 0; display: flex; gap: .34em; align-items: baseline; font: 300 clamp(42px, 5.2vw, 76px)/.95 system-ui, sans-serif; letter-spacing: -.045em; }
.boot-copy h1 b { font-weight: 700; letter-spacing: .04em; }
.boot-copy small { display: block; margin-top: 15px; color: #777168; font-size: 8px; letter-spacing: .12em; }
.boot-line { width: min(560px, 58vw); height: 1px; margin-top: 28px; overflow: hidden; background: rgba(126,119,108,.28); }
.boot-line i { display: block; height: 100%; background: #34352f; transition: width .48s cubic-bezier(.22,1,.36,1); }
.boot-readout { width: min(560px, 58vw); margin-top: 8px; display: flex; justify-content: space-between; color: #8f887e; font-size: 7px; letter-spacing: .13em; }
.boot-readout strong { color: #4f5049; font-weight: 600; }
.boot-permission { position: absolute; left: 36vw; right: 14vw; bottom: 15vh; display: grid; grid-template-columns: repeat(3,1fr); border-top: 1px solid rgba(109,104,95,.24); }
.boot-permission span { position: relative; padding-top: 10px; color: #aaa398; font-size: 7px; letter-spacing: .15em; }
.boot-permission span::before { content: ''; position: absolute; left: 0; top: -1px; width: 0; height: 1px; background: #403f39; transition: width .45s ease; }
.boot-permission span.active { color: #57574f; }
.boot-permission span.active::before { width: 68%; }
.boot-layer > button { position: absolute; right: 4vw; bottom: 4vh; border: 0; background: transparent; color: #8b857b; font: 600 7px/1 ui-monospace, monospace; letter-spacing: .15em; cursor: pointer; }
.boot-layer[data-phase="PERMISSION"] .boot-system-mark { transform: translateY(-50%) scale(.92) rotate(8deg); }
.boot-layer[data-phase="ARCHIVE"] .boot-system-mark { transform: translateY(-50%) scale(.72); opacity: .64; }
.boot-layer[data-phase="ARCHIVE"] .boot-scan-grid { opacity: .22; transform: scale(1); }
.boot-layer[data-phase="ARCHIVE"] { background: rgba(230,226,218,.93); }
.boot-layer[data-phase="READY"] { opacity: 0; background: rgba(230,226,218,.36); pointer-events: none; }

.terminal-brand { position: absolute; z-index: 8; left: 44px; top: 78px; width: 205px; line-height: 1; user-select: none; }
.terminal-brand h1 { margin: 0; font-size: 25px; line-height: 28px; letter-spacing: 1.65px; font-weight: 760; }
.terminal-brand > div { font-size: 10px; line-height: 15px; letter-spacing: .6px; font-weight: 620; }
.terminal-brand p { margin: 1px 0 0; width: 145px; display: flex; justify-content: space-between; font-size: 17px; line-height: 22px; }
.terminal-brand p b { font-weight: 760; letter-spacing: 2px; }

.module-index-shell {
  position: absolute; z-index: 12; left: auto; right: 42px; top: 83px; width: auto;
  display: flex; align-items: center; justify-content: flex-end; gap: 18px; padding-bottom: 7px;
}
.module-index-shell.expanded { min-width: min(930px, calc(100vw - 390px)); }
.terminal-brand, .module-index-shell, .archive-hint, .column-navigation, .powered {
  transition: opacity .55s cubic-bezier(.22,1,.36,1);
}
.analysis-os.is-idle .module-index-shell,
.analysis-os.is-idle .archive-hint,
.analysis-os.is-idle .column-navigation,
.analysis-os.is-idle .powered { opacity: var(--hud-opacity, 1); }
.analysis-os.is-sleeping .archive-hint { opacity: .24; }
.analysis-os.is-browsing .module-index-shell { opacity: .82; }
.analysis-os.is-browsing .archive-hint { opacity: .22; }
.analysis-os.is-browsing .column-navigation strong { opacity: .34; }
.analysis-os.is-browsing .powered { opacity: .52; }
.module-index-label { display: flex; align-items: center; gap: 9px; padding: 0; border: 0; background: transparent; color: #777269; font: 650 7px/1 ui-monospace, monospace; letter-spacing: .15em; white-space: nowrap; cursor: pointer; }
.module-index-label span { color: #aaa398; font-size: 6px; letter-spacing: .09em; }
.module-index-track { position: absolute; right: 0; top: 27px; display: flex; width: 0; opacity: 0; pointer-events: none; overflow: hidden; scrollbar-width: none; scroll-behavior: smooth; background: rgba(236,231,224,.94); border-top: 1px solid rgba(112,108,99,.25); border-bottom: 1px solid rgba(112,108,99,.25); mask-image: linear-gradient(90deg, transparent, #000 2%, #000 97%, transparent); transition: width .42s cubic-bezier(.22,1,.36,1), opacity .22s ease; }
.module-index-shell.expanded .module-index-track { width: min(930px, calc(100vw - 390px)); opacity: 1; pointer-events: auto; overflow-x: auto; }
.module-index-shell.expanded .module-index-label span { display: none; }
.module-index-track::-webkit-scrollbar { display: none; }
.module-index-track button {
  position: relative; flex: 0 0 auto; min-width: 104px; height: 40px; padding: 3px 12px 5px;
  border: 0; border-left: 1px solid rgba(133,129,120,.22); background: transparent; color: #9b958b;
  text-align: left; cursor: pointer; transition: color .18s ease, background .18s ease;
}
.module-index-track button::after { content: ''; position: absolute; left: 12px; right: 12px; bottom: -8px; height: 1px; background: #6e6049; transform: scaleX(0); transition: transform .2s ease; }
.module-index-track button > span { display: block; font: 600 7px/1 ui-monospace, monospace; color: #b1aba1; }
.module-index-track button strong { display: block; margin-top: 5px; font: 650 9px/1 ui-monospace, monospace; letter-spacing: .075em; white-space: nowrap; }
.module-index-track button small { display: block; margin-top: 3px; font-size: 8px; color: #aaa399; }
.module-index-track button.active { color: #292b25; background: rgba(210,202,189,.14); }
.module-index-track button.active::after { transform: scaleX(1); }
.module-index-track button.active > span, .module-index-track button.active small { color: #706a60; }
.module-index-tools { display: flex; align-items: center; gap: 13px; white-space: nowrap; color: #807b72; font: 600 8px/1 ui-monospace, monospace; letter-spacing: .07em; }
.module-index-tools button { border: 0; background: transparent; color: inherit; cursor: pointer; font: inherit; letter-spacing: inherit; }
.module-index-tools button:hover { color: #20221d; }
.module-index-tools button:disabled { opacity: .4; }
.archive-theme-toggle[aria-pressed="true"] { color: #796644; }
.module-index-tools time { color: #4d4c46; }

.data-warning { position: absolute; z-index: 11; right: 42px; top: 118px; margin: 0; max-width: 470px; color: #8b6944; font: 600 7px/1.5 ui-monospace, monospace; text-align: right; letter-spacing: .05em; opacity: .56; transition: opacity .18s ease; }
.analysis-os.is-browsing .data-warning { opacity: 0; }
.archive-callout {
  position: absolute; z-index: 8; left: 50.5%; right: auto; top: 43%; width: min(430px, 32vw);
  color: #20221d; pointer-events: none; filter: blur(var(--callout-blur, 0));
  transform: translate3d(0, var(--callout-shift, 0), 0);
  transition: filter .09s linear, transform .09s linear;
}
.archive-callout .file-number {
  margin: 0; color: #1f211d; font: 690 18px/.98 ui-monospace, monospace; letter-spacing: -.025em;
  opacity: var(--file-opacity, .7); transition: opacity .09s linear;
}
.callout-meta {
  margin-top: 12px; display: flex; align-items: baseline; gap: 11px; color: #77736a;
  opacity: var(--name-opacity, .56); transform: translateY(calc((1 - var(--name-opacity, .56)) * 3px));
  transition: opacity .09s linear, transform .09s linear;
}
.callout-meta strong { color: #5e5c55; font: 680 9px/1 ui-monospace, monospace; letter-spacing: .09em; }
.callout-meta span { font-size: 10px; }
.callout-rule {
  position: relative; height: 1px; margin: 18px 0 0 36px; background: rgba(78,77,70,.54);
  transform-origin: left center; transform: scaleX(var(--rule-scale, .3)); opacity: var(--name-opacity, .56);
  transition: transform .09s linear, opacity .09s linear;
}
.callout-rule::before { content: ''; position: absolute; left: -36px; top: -1px; width: 3px; height: 3px; background: #252721; }
.archive-callout > button {
  pointer-events: auto; margin: 16px 0 0 36px; border: 0; background: transparent; color: #252721; padding: 0;
  font: 660 8px/1 ui-monospace, monospace; letter-spacing: .08em; cursor: pointer;
  opacity: var(--cta-opacity, 0); transform: translateY(calc((1 - var(--cta-opacity, 0)) * 3px));
  transition: opacity .035s linear, transform .09s linear;
}
.archive-callout > button:disabled { pointer-events: none; cursor: default; }
.archive-callout > button span { margin-left: 34px; font-size: 15px; vertical-align: -2px; }
.archive-callout > button:hover { color: #8a7657; }
.access-sequence {
  position: absolute; z-index: 13; right: 7%; top: 37%; width: min(330px, 28vw);
  padding: 16px 0; color: #292b25; pointer-events: none;
}
.access-sequence > span { color: #89847a; font: 650 7px/1 ui-monospace, monospace; letter-spacing: .13em; }
.access-sequence strong { display: block; margin-top: 11px; font: 650 clamp(22px, 1.9vw, 31px)/1 ui-monospace, monospace; letter-spacing: -.03em; }
.access-sequence small { display: block; margin-top: 7px; color: #979187; font: 600 7px/1 ui-monospace, monospace; letter-spacing: .11em; }
.access-progress { height: 1px; margin-top: 20px; background: #c0baaf; overflow: hidden; }
.access-progress i { display: block; height: 100%; background: #34362f; transition: width .06s linear; }
.access-sequence p { margin: 11px 0 0; color: #777269; font: 600 7px/1 ui-monospace, monospace; letter-spacing: .095em; }
.document-reveal {
  position: absolute; z-index: 6; right: 3.5%; top: 18%; width: min(560px, 39vw); height: 58%;
  display: grid; grid-template-rows: auto 1fr auto; padding: 26px 28px 20px;
  border-top: 1px solid rgba(111,106,97,.4); border-bottom: 1px solid rgba(111,106,97,.32);
  background: linear-gradient(90deg, rgba(231,226,217,.34), rgba(239,235,227,.78));
  overflow: hidden; pointer-events: none; contain: paint;
  will-change: opacity, transform;
}
.document-reveal::before {
  content: ''; position: absolute; left: 28px; right: 28px; top: 96px; height: 1px;
  background: rgba(119,111,98,.42); transform: scaleX(var(--handoff-line, 0));
  transform-origin: left center; pointer-events: none;
}
.document-reveal::after {
  content: ''; position: absolute; top: 0; bottom: 0; left: 0; width: 24%;
  background: linear-gradient(90deg, transparent, rgba(255,255,255,.20), transparent);
  transform: translate3d(var(--handoff-scan-x, -120%), 0, 0);
  opacity: var(--handoff-line, 0); pointer-events: none; will-change: transform;
}
.document-reveal header { align-self: start; display: grid; gap: 7px; }
.document-reveal header span { color: #89847a; font: 650 7px/1 ui-monospace, monospace; letter-spacing: .13em; }
.document-reveal header strong { color: #2d2f29; font: 650 24px/1 ui-monospace, monospace; letter-spacing: -.025em; }
.document-reveal header small { color: #747068; font-size: 11px; }
.document-grid { align-self: center; display: grid; grid-template-columns: repeat(3,minmax(0,1fr)); border-top: 1px solid rgba(124,119,109,.25); border-left: 1px solid rgba(124,119,109,.25); }
.document-grid span { min-height: 62px; display: grid; place-items: center start; padding: 0 11px; border-right: 1px solid rgba(124,119,109,.25); border-bottom: 1px solid rgba(124,119,109,.25); color: #777269; font: 600 7px/1.3 ui-monospace, monospace; letter-spacing: .08em; }
.document-reveal footer {
  color: #999287; font: 600 7px/1 ui-monospace, monospace; letter-spacing: .11em;
  opacity: var(--handoff-ready-opacity, .7);
}
.analysis-os.is-extracting .module-index-shell,
.analysis-os.is-extracting .archive-counter,
.analysis-os.is-extracting .archive-hint,
.analysis-os.is-extracting .column-navigation { opacity: .28; pointer-events: none; }

.archive-counter { position: absolute; z-index: 9; left: 43px; bottom: 72px; opacity: .82; }
.archive-counter > span { color: #89847b; font: 600 7px/1 ui-monospace, monospace; letter-spacing: .13em; }
.counter-line { display: flex; align-items: baseline; gap: 14px; margin-top: 12px; }
.counter-line strong { font-size: 52px; line-height: .9; font-weight: 340; letter-spacing: -2px; }
.counter-line i { font-style: normal; color: #989288; font-size: 18px; font-weight: 300; }
.archive-navigation { display: flex; gap: 4px; margin-top: 11px; }
.archive-navigation button { width: 31px; height: 29px; border: 1px solid #c0baaf; color: #706d65; background: transparent; cursor: pointer; font-size: 14px; }
.archive-navigation button:hover { background: #ddd3c4; color: #20221d; }
.archive-hint { position: absolute; z-index: 7; left: 345px; bottom: 44px; display: flex; align-items: center; gap: 11px; color: #918c82; font: 500 8px/1 ui-monospace, monospace; letter-spacing: .11em; pointer-events: none; }
.archive-hint i { width: 20px; height: 1px; background: #bcb6ab; }
.column-navigation { position: absolute; z-index: 7; left: 52%; bottom: 67px; display: grid; gap: 6px; min-width: 220px; }
.column-navigation span { color: #99948a; font: 600 8px/1 ui-monospace, monospace; letter-spacing: .11em; }
.column-navigation strong { color: #44463f; font-size: 11px; font-weight: 500; }

.module-directory { position: absolute; z-index: 40; right: 34px; top: 92px; width: min(820px, 72vw); padding: 22px 24px 24px; background: rgba(239,236,228,.985); border: 1px solid #c7c1b6; box-shadow: 0 30px 100px rgba(83,72,55,.17); }
.module-directory > header { display: flex; justify-content: space-between; align-items: center; }
.module-directory > header div { display: flex; gap: 16px; color: #5e5b54; font: 650 10px/1 ui-monospace, monospace; letter-spacing: .12em; }
.module-directory > header strong { color: #99948a; font-weight: 500; }
.module-directory > header button { width: 30px; height: 30px; border: 0; background: transparent; color: #77736a; font-size: 21px; cursor: pointer; }
.module-search { height: 54px; margin-top: 10px; display: flex; align-items: center; gap: 14px; border-bottom: 1px solid #7e796f; }
.module-search > span { position: relative; width: 17px; height: 17px; border: 1px solid #77736a; border-radius: 50%; }
.module-search > span::after { content: ''; position: absolute; width: 7px; height: 1px; background: #77736a; left: -5px; bottom: -2px; transform: rotate(-45deg); }
.module-search input { min-width: 0; flex: 1; height: 100%; border: 0; outline: 0; background: transparent; color: #20221d; font-size: 13px; }
.module-search kbd { color: #9a958b; font: 600 8px/1 ui-monospace, monospace; }
.module-directory-list { margin-top: 17px; display: grid; grid-template-columns: repeat(3, minmax(0,1fr)); border-top: 1px solid #d0cabf; border-left: 1px solid #d0cabf; }
.module-directory-list button { min-width: 0; min-height: 76px; padding: 12px 13px; border: 0; border-right: 1px solid #d0cabf; border-bottom: 1px solid #d0cabf; background: transparent; color: #6b675f; text-align: left; cursor: pointer; }
.module-directory-list button:hover, .module-directory-list button.active { background: #dcd2c3; color: #20221d; }
.module-directory-list button > span { display: block; color: #99948a; font: 600 8px/1 ui-monospace, monospace; }
.module-directory-list strong { display: block; margin-top: 7px; font: 650 11px/1.2 ui-monospace, monospace; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.module-directory-list small { display: block; margin-top: 6px; color: #948f85; font-size: 9px; }

.powered { position: absolute; z-index: 8; right: 45px; bottom: 82px; display: flex; align-items: center; gap: 5px; color: #67655e; font-size: 11px; }
.powered b { color: #20221d; font-weight: 760; }
.powered i { display: inline-block; width: 18px; height: 3px; margin-left: 8px; background: #34362f; }

/* Midnight Atelier keeps the scene nearly monochrome. Lighting and material
   finish create hierarchy; champagne metal is reserved for the active file. */
.analysis-os.is-night .boot-layer {
  background: #0d1114;
  color: #ece7de;
}
.analysis-os.is-night .boot-layer::after {
  background:
    radial-gradient(circle at 50% 48%, rgba(199,168,111,.085), transparent 38%),
    radial-gradient(circle at 20% 12%, rgba(116,160,173,.035), transparent 38%),
    linear-gradient(180deg, rgba(139,157,161,.018), transparent 46%);
}
.analysis-os.is-night .boot-scan-grid {
  background-image:
    linear-gradient(rgba(188,190,185,.035) 1px, transparent 1px),
    linear-gradient(90deg, rgba(188,190,185,.035) 1px, transparent 1px);
}
.analysis-os.is-night .boot-orbit { border-color: rgba(218,204,174,.38); }
.analysis-os.is-night .boot-orbit::before,
.analysis-os.is-night .boot-orbit::after { border-color: rgba(200,167,106,.28); }
.analysis-os.is-night .boot-orbit span { background: #b99a61; }
.analysis-os.is-night .boot-orbit i { background: #e0d7c7; box-shadow: 0 0 0 11px rgba(200,167,106,.06); }
.analysis-os.is-night .boot-axis,
.analysis-os.is-night .boot-line { background: rgba(230,213,181,.17); }
.analysis-os.is-night .boot-line i { background: #c8a76a; }
.analysis-os.is-night .boot-copy > span,
.analysis-os.is-night .boot-copy p,
.analysis-os.is-night .boot-copy small,
.analysis-os.is-night .boot-readout,
.analysis-os.is-night .boot-permission span,
.analysis-os.is-night .boot-layer > button { color: #9b9387; }
.analysis-os.is-night .boot-copy h1,
.analysis-os.is-night .boot-readout strong,
.analysis-os.is-night .boot-permission span.active { color: #f0ebe1; }
.analysis-os.is-night .boot-permission { border-color: rgba(230,213,181,.16); }
.analysis-os.is-night .boot-permission span::before { background: #c8a76a; }
.analysis-os.is-night .boot-layer[data-phase="ARCHIVE"] { background: rgba(13,17,20,.94); }
.analysis-os.is-night .boot-layer[data-phase="READY"] { background: rgba(13,17,20,.34); }

.analysis-os.is-night .terminal-brand,
.analysis-os.is-night .archive-callout,
.analysis-os.is-night .access-sequence,
.analysis-os.is-night .archive-counter { color: var(--ink); }
.analysis-os.is-night .module-index-label,
.analysis-os.is-night .module-index-tools,
.analysis-os.is-night .module-index-tools time,
.analysis-os.is-night .archive-counter > span,
.analysis-os.is-night .archive-hint,
.analysis-os.is-night .column-navigation span,
.analysis-os.is-night .powered { color: var(--muted-ink); }
.analysis-os.is-night .module-index-label span,
.analysis-os.is-night .module-index-track button > span,
.analysis-os.is-night .module-index-track button small,
.analysis-os.is-night .counter-line i { color: var(--faint-ink); }
.analysis-os.is-night .module-index-tools button:hover,
.analysis-os.is-night .module-index-track button.active,
.analysis-os.is-night .module-index-track button:hover,
.analysis-os.is-night .archive-theme-toggle[aria-pressed="true"] { color: var(--accent); }
.analysis-os.is-night .module-index-tools > button:not(.archive-theme-toggle) { opacity: .68; }
.analysis-os.is-night .module-index-tools > button:not(.archive-theme-toggle):hover { opacity: 1; }
.analysis-os.is-night .module-index-track {
  background: rgba(16,20,23,.95);
  border-color: rgba(195,198,194,.14);
}
.analysis-os.is-night .module-index-track button {
  border-color: rgba(195,198,194,.10);
  color: #9d9d98;
}
.analysis-os.is-night .module-index-track button.active { background: rgba(184,151,99,.075); }
.analysis-os.is-night .module-index-track button::after { background: var(--accent); }
.analysis-os.is-night .data-warning { color: #d0af70; opacity: .76; }
.analysis-os.is-night .archive-theme-toggle[aria-pressed="true"] {
  text-shadow: 0 0 8px rgba(203,177,128,.12);
}
.analysis-os.is-night .archive-callout {
  text-shadow: 0 1px 0 rgba(0,0,0,.28);
}

.analysis-os.is-night .archive-callout .file-number,
.analysis-os.is-night .archive-callout > button,
.analysis-os.is-night .access-sequence strong { color: var(--ink); }
.analysis-os.is-night .archive-callout .file-number {
  color: #f1ebe1;
  font-weight: 600;
  letter-spacing: .005em;
  text-shadow: 0 0 10px rgba(203,177,128,.095);
}
.analysis-os.is-night .archive-callout.is-settled .file-number {
  animation: archive-focus-breathe 4.4s ease-in-out infinite;
}
.analysis-os.is-night .callout-meta,
.analysis-os.is-night .callout-meta span,
.analysis-os.is-night .access-sequence p { color: var(--muted-ink); }
.analysis-os.is-night .callout-meta strong,
.analysis-os.is-night .column-navigation strong { color: #c8c2b8; }
.analysis-os.is-night .archive-callout > button {
  color: #cbc5bb;
  letter-spacing: .12em;
  text-shadow: none;
}
.analysis-os.is-night .callout-rule {
  background: linear-gradient(90deg, rgba(203,177,128,.31), rgba(184,181,173,.11) 58%, rgba(184,181,173,.015));
}
.analysis-os.is-night .callout-rule::before {
  background: var(--accent);
  box-shadow: 0 0 6px rgba(203,177,128,.14);
}
.analysis-os.is-night .archive-callout > button:hover {
  color: #ceb480;
  text-shadow: 0 0 9px rgba(203,177,128,.10);
}
.analysis-os.is-night .access-sequence > span,
.analysis-os.is-night .access-sequence small { color: #9a9286; }
.analysis-os.is-night .access-progress { background: rgba(230,213,181,.18); }
.analysis-os.is-night .access-progress i { background: var(--accent); }

.analysis-os.is-night .document-reveal {
  border-color: rgba(195,198,194,.15);
  background: linear-gradient(90deg, rgba(15,19,23,.68), rgba(27,31,34,.94));
}
.analysis-os.is-night .document-reveal::before { background: rgba(200,167,106,.38); }
.analysis-os.is-night .document-reveal::after {
  background: linear-gradient(90deg, transparent, rgba(197,190,178,.045), rgba(199,168,111,.04), transparent);
}
.analysis-os.is-night .document-reveal header span,
.analysis-os.is-night .document-reveal footer { color: #9a9286; }
.analysis-os.is-night .document-reveal header strong { color: var(--ink); }
.analysis-os.is-night .document-reveal header small,
.analysis-os.is-night .document-grid span { color: var(--muted-ink); }
.analysis-os.is-night .document-grid,
.analysis-os.is-night .document-grid span { border-color: rgba(230,213,181,.15); }

.analysis-os.is-night .archive-navigation button {
  border-color: rgba(195,198,194,.20);
  color: #b9b5ac;
}
.analysis-os.is-night .archive-navigation button:hover {
  background: rgba(184,151,99,.09);
  color: var(--ink);
}
.analysis-os.is-night .archive-hint i { background: rgba(195,198,194,.18); }
.analysis-os.is-night .counter-line strong {
  color: #ddd7cd;
  text-shadow: none;
}

.analysis-os.is-night .module-directory {
  background: rgba(17,21,24,.985);
  border-color: rgba(195,198,194,.18);
  box-shadow: 0 30px 100px rgba(0,0,0,.34);
}
.analysis-os.is-night .module-directory > header div,
.analysis-os.is-night .module-directory > header button { color: #d2cabd; }
.analysis-os.is-night .module-directory > header strong,
.analysis-os.is-night .module-search kbd,
.analysis-os.is-night .module-directory-list button > span,
.analysis-os.is-night .module-directory-list small { color: #91897e; }
.analysis-os.is-night .module-search { border-color: rgba(230,213,181,.28); }
.analysis-os.is-night .module-search > span { border-color: #a79d8f; }
.analysis-os.is-night .module-search > span::after { background: #a79d8f; }
.analysis-os.is-night .module-search input { color: var(--ink); }
.analysis-os.is-night .module-directory-list { border-color: rgba(230,213,181,.15); }
.analysis-os.is-night .module-directory-list button {
  border-color: rgba(230,213,181,.15);
  color: #bbb2a5;
}
.analysis-os.is-night .module-directory-list button:hover,
.analysis-os.is-night .module-directory-list button.active {
  background: rgba(184,151,99,.09);
  color: var(--ink);
}
.analysis-os.is-night .powered { opacity: .72; }
.analysis-os.is-night .powered { opacity: .54; }
.analysis-os.is-night .powered b { color: #c6c0b7; }
.analysis-os.is-night .powered i {
  background: var(--accent);
  box-shadow: 0 0 6px rgba(203,177,128,.10);
}

@keyframes boot-line { from { transform: translateX(-100%); } to { transform: translateX(0); } }
@keyframes orbit-line { from { opacity: 0; transform: rotate(-70deg) scaleY(.2); } to { opacity: 1; transform: rotate(36deg) scaleY(1); } }
@keyframes retrieval-scan { 0% { transform: translateX(-120%); } 100% { transform: translateX(420%); } }
@keyframes archive-aurora-drift {
  from { transform: translate3d(-.45%, -.15%, 0) scale(1.012); opacity: .56; }
  to { transform: translate3d(.45%, .28%, 0) scale(1.019); opacity: .66; }
}
@keyframes archive-dawn-band {
  from { transform: rotate(-.8deg) translate3d(-.5%, 0, 0); opacity: .28; }
  to { transform: rotate(-.8deg) translate3d(.45%, .25%, 0); opacity: .38; }
}
@keyframes archive-assistant-glow {
  from { transform: translate3d(-.4%, 0, 0) scale(.99); opacity: .34; }
  to { transform: translate3d(.45%, -.35%, 0) scale(1.015); opacity: .44; }
}
@keyframes archive-focus-breathe {
  0%, 100% { text-shadow: 0 0 9px rgba(203,177,128,.07); }
  50% { text-shadow: 0 0 12px rgba(203,177,128,.12); }
}

@media (max-width: 1100px) {
  .terminal-brand { left: 28px; top: 52px; transform: scale(.82); transform-origin: top left; }
  .module-index-shell { left: auto; right: 24px; top: 58px; width: auto; }
  .module-index-shell.expanded { min-width: min(720px, calc(100vw - 270px)); }
  .module-index-shell.expanded .module-index-track { width: min(720px, calc(100vw - 270px)); }
  .module-index-tools > span, .module-index-tools time { display: none; }
  .archive-callout { left: 52%; right: auto; top: 39%; width: 42vw; }
  .archive-counter { left: 30px; }
  .archive-hint { left: 275px; }
  .column-navigation { left: 49%; }
}

@media (max-width: 700px) {
  .analysis-os { min-height: 100dvh; }
  .boot-system-mark { left: 50%; top: 22%; width: 118px; height: 118px; transform: translate(-50%, -50%); }
  .boot-copy { left: 24px; right: 24px; top: 48%; transform: translateY(-50%); }
  .boot-copy h1 { font-size: 42px; }
  .boot-line, .boot-readout { width: 100%; }
  .boot-permission { left: 24px; right: 24px; bottom: 17vh; }
  .boot-layer[data-phase="PERMISSION"] .boot-system-mark { transform: translate(-50%, -50%) scale(.92) rotate(8deg); }
  .boot-layer[data-phase="ARCHIVE"] .boot-system-mark { transform: translate(-50%, -50%) scale(.72); }
  .terminal-brand { left: 18px; top: 16px; transform: scale(.58); }
  .module-index-shell { left: 0; right: 0; top: auto; bottom: 0; grid-template-columns: 1fr; gap: 0; padding: 0 0 env(safe-area-inset-bottom); background: rgba(232,229,225,.94); border-top: 1px solid #c3bdb2; border-bottom: 0; }
  .analysis-os.is-night .module-index-shell { background: rgba(15,19,23,.96); border-top-color: rgba(195,198,194,.15); }
  .module-index-label, .module-index-tools { display: none; }
  .module-index-track { position: static; width: 100%; max-width: none; opacity: 1; pointer-events: auto; overflow-x: auto; background: transparent; border: 0; }
  .module-index-track button { min-width: 102px; height: 54px; padding: 7px 12px; }
  .module-index-track button::after { bottom: 0; }
  .archive-callout { left: 18px; right: 18px; top: 23%; width: auto; }
  .archive-callout .file-number { font-size: 10px; }
  .archive-counter { left: 18px; bottom: 77px; transform: scale(.72); transform-origin: bottom left; }
  .archive-hint, .column-navigation, .powered { display: none; }
  .module-directory { left: 14px; right: 14px; top: 70px; width: auto; max-height: 74vh; overflow: auto; padding: 17px; }
  .module-directory-list { grid-template-columns: 1fr 1fr; }
  .data-warning { left: 18px; right: 18px; top: 86px; max-width: none; text-align: left; }
  .access-sequence { left: 18px; right: 18px; top: 28%; width: auto; }
  .document-reveal { display: none; }
}

@media (prefers-reduced-motion: reduce) {
  *, *::before, *::after { animation-duration: .01ms !important; transition-duration: .01ms !important; scroll-behavior: auto !important; }
}
</style>
