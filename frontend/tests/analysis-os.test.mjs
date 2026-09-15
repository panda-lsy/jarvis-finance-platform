import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const here = path.dirname(fileURLToPath(import.meta.url))
const src = path.resolve(here, '../src')

function read(relativePath) {
  return fs.readFileSync(path.join(src, relativePath), 'utf8')
}

test('analysis OS is the authenticated workspace entry without replacing existing routes', () => {
  const tabs = read('composables/useWorkspaceTabs.js')
  const app = read('App.vue')

  assert.match(tabs, /const DEFAULT_TAB = '研究终端'/)
  assert.match(tabs, /'行情'/)
  assert.match(tabs, /'模拟盘'/)
  assert.match(tabs, /'研究助手'/)
  assert.match(app, /AnalysisOsPage/)
  assert.match(app, /v-show="activeTab === '研究终端' \|\| archiveHandoffHold"/)
  assert.match(app, /:active="activeTab === '研究终端'"/)
  assert.match(app, /@navigate="navigateWorkspace"/)
  assert.match(app, /:requested-module-key="archiveModuleKey"/)
  assert.match(app, /@focus-change="syncArchiveModule"/)
  assert.match(app, /<SimTradeView :user="user"/)
})

test('analysis OS uses Three.js while preserving original JARVIS branding', () => {
  const page = read('pages/AnalysisOsPage.vue')
  const scene = read('components/analysis/AnalysisArchiveScene.vue')

  assert.match(page, /SYSTEM WAKE/)
  assert.match(page, /FINANCIAL RESEARCH/)
  assert.match(page, /ANALYSIS <b>OS<\/b>/)
  assert.match(page, /AnalysisArchiveScene/)
  assert.match(scene, /from 'three'/)
  assert.match(scene, /WebGLRenderer/)
  assert.match(scene, /Raycaster/)
  assert.match(scene, /ResizeObserver/)
  assert.match(scene, /function startRendering/)
  assert.match(scene, /function stopRendering/)
  assert.match(scene, /watch\(\(\) => props\.active/)
  assert.match(scene, /forceContextLoss/)
  assert.match(scene, /ArchivePlaneMomentum/)
  assert.match(scene, /dragProjection/)
  assert.match(scene, /wheelTotal/)
  assert.match(scene, /emit\('activate'/)
  assert.match(scene, /scene\.background = new Color\('#eae5e1'\)/)
  assert.match(scene, /createArchiveAssembly/)
  assert.match(scene, /createArchiveComposer/)
  assert.match(scene, /focusedGlassMaterial/)
  assert.match(scene, /postProcessing/)
  assert.doesNotMatch(page, /莱茵生命|明日方舟|RHINE LAB/i)
  assert.doesNotMatch(scene, /莱茵生命|明日方舟|RHINE LAB/i)
})

test('analysis OS uses module archives while retaining a truthful API status channel', () => {
  const page = read('pages/AnalysisOsPage.vue')

  assert.match(page, /JARVIS_MODULES/)
  assert.match(page, /MODULE INDEX/)
  assert.match(page, /ACCESS FILE/)
  assert.match(page, /api\.marketInstruments\(\)/)
  assert.match(page, /DATA CHANNEL INTERRUPTED/)
  assert.doesNotMatch(page, /marketAssetQuote/)
  assert.doesNotMatch(page, /贵州茅台|Bitcoin|Apple/)
})

test('analysis OS exposes existing research and trading routes', () => {
  const page = read('pages/AnalysisOsPage.vue')
  const modules = read('analysis-os/data/modules.js')
  for (const route of ['多市场', '研究助手', '财报解析', '产业链图谱', '风险预警', '模拟盘', '智能报价', '市场趋势预测']) {
    assert.match(`${page}\n${modules}`, new RegExp(route))
  }
  assert.match(modules, /routeKey: '智能报价'/)
})
