<script setup>
import {
  AmbientLight,
  CanvasTexture,
  Color,
  DirectionalLight,
  DynamicDrawUsage,
  Fog,
  Group,
  HemisphereLight,
  InstancedMesh,
  Mesh,
  MeshBasicMaterial,
  MeshStandardMaterial,
  Object3D,
  PerspectiveCamera,
  PlaneGeometry,
  Raycaster,
  Scene,
  SRGBColorSpace,
  Vector2,
  Vector3,
  WebGLRenderer,
} from 'three'
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ArchiveDrag, ArchivePlaneMomentum, dampSpring } from '../../analysis-os/motion/archiveMomentum'
import {
  LOOP_POOL,
  nearestPeriodicCoordinate,
  poolKeyForCell,
} from '../../analysis-os/motion/archiveLoop'
import {
  applyArchiveTheme,
  createArchiveAssembly,
  createArchiveAssetLibrary,
  createFocusedGlassMaterial,
} from '../../analysis-os/render/archiveAssembly'
import {
  archiveQualityProfile,
  configureArchiveRenderer,
  createArchiveComposer,
  disposeArchiveComposer,
  probeArchiveComposer,
  resizeArchiveComposer,
} from '../../analysis-os/render/renderQuality'

const LANE_SPACING = 5.20
const ROW_SPACING = 0.62
const LANE_ROW_SKEW = 0
const CENTER_LANE = 2
const CENTER_ROW = 12
const ARCHIVE_ORIGIN_ROW = 15.5
const FOCUS_Z = (CENTER_ROW - ARCHIVE_ORIGIN_ROW) * ROW_SPACING
const POOL_OPTIONS = LOOP_POOL
// 31 fps is intentional: on a nominal 60 Hz display a 33.33 ms threshold can
// miss the second RAF by a fraction of a millisecond and accidentally render
// every third frame (~20 fps). 32.26 ms reliably lands on every second RAF.
const IDLE_RENDER_INTERVAL_MS = 1000 / 31
const DETAIL_RETENTION_BIAS = 0.34
const FRAME_DETAIL_BUDGET = 6
const FRAME_RETENTION_BIAS = 0.48
// Keep one geometry representation on screen for the whole browse -> focus ->
// extraction path. The standalone GLB uses a darker graphite/seal silhouette;
// swapping to it while a card is selected produces the grey-green edge pop and
// a refresh-like shimmer reported during extraction. The loader remains in the
// codebase for future/offscreen use, but the visible transition stays procedural.
const USE_FOCUSED_GLB_IN_TRANSITION = false
// The extraction sequence must stay on one renderer path. Switching to SSAO
// or allocating a shadow map mid-animation produces a refresh-like hitch and a
// visible "sharpening" step on some GPUs. Keep extraction on direct rendering
// and pre-resident focused geometry instead.
const STABLE_DIRECT_EXTRACTION = true
const ARCHIVE_SCENE_THEMES = Object.freeze({
  day: Object.freeze({
    background: '#eae5e1',
    exposure: 0.94,
    ambientColor: 0xfffbf5,
    ambientIntensity: 0.24,
    hemisphereSky: 0xfffbf4,
    hemisphereGround: 0xa79f92,
    hemisphereIntensity: 0.82,
    keyColor: 0xfffbf1,
    keyIntensity: 2.35,
    fillColor: 0xcabda9,
    fillIntensity: 0.72,
    rimColor: 0xe6d8c5,
    rimIntensity: 0.66,
    auroraColor: 0x75b7c6,
    auroraIntensity: 0,
    floorColor: '#d8c9b9',
  }),
  night: Object.freeze({
    background: '#0c1013',
    exposure: 1.26,
    ambientColor: 0xd9d6d0,
    ambientIntensity: 0.38,
    hemisphereSky: 0xb7bab9,
    hemisphereGround: 0x15191c,
    hemisphereIntensity: 0.66,
    keyColor: 0xffe7c8,
    keyIntensity: 1.72,
    fillColor: 0x929899,
    fillIntensity: 0.27,
    rimColor: 0xb99e72,
    rimIntensity: 0.34,
    auroraColor: 0x8aa2a8,
    auroraIntensity: 0.06,
    floorColor: '#101417',
  }),
})

const ANONYMOUS_ARCHIVE = {
  id: 'archive:anonymous',
  key: 'archive-anonymous',
  no: 0,
  code: 'ARCHIVE',
  labelEn: 'ARCHIVE',
  labelZh: '档案',
  category: 'ARCHIVE',
  capabilities: ['INDEX', 'RECORD', 'SEA'],
}

const props = defineProps({
  modules: { type: Array, default: () => [] },
  focusedKey: { type: String, default: '' },
  retrievalState: { type: String, default: 'FOCUSED' },
  sleepAmount: { type: Number, default: 0 },
  extractionProgress: { type: Number, default: 0 },
  active: { type: Boolean, default: true },
  nightMode: { type: Boolean, default: false },
})

const emit = defineEmits(['step', 'settled', 'flow', 'activate', 'interaction', 'motion'])
const mountRef = ref(null)
const failed = ref(false)

let renderer = null
let scene = null
let camera = null
let root = null
let raycaster = null
let pointer = null
let resizeObserver = null
let animationFrame = 0
let rendering = false
let reducedMotion = false
let disposed = false
let hoveredEntry = null
let activePointer = null
let dragStart = { lane: 0, row: 0 }
let momentum = null
let wheelTotal = 0
let wheelTime = 0
let lastFrameTime = 0
let lastRenderedAt = 0
let initializedTrack = false
let lastReportedRow = CENTER_ROW
let navigationActive = false
let settledAt = 0
let suppressSleepMotion = false
let archiveLibrary = null
let focusedGlassMaterial = null
let composerBundle = null
let qualityProfile = null
let keyLight = null
let ambientLight = null
let hemisphereLight = null
let fillLight = null
let rimLight = null
let auroraLight = null
let floorMaterial = null
let renderedFrames = 0
let composerRevision = 0
let postProbeTimer = 0
let postProbeAttempted = false
let postProbeFailed = false
let postProcessingStatus = 'direct'
let detailAsset = null
let detailAssetStatus = 'idle'
let prewarmTimer = 0
let labelPrewarmTimer = 0
let motionAmount = 0
let detailDensity = 0.42
let settleProgress = 1
let lastMotionLane = CENTER_LANE
let lastMotionRow = CENTER_ROW
let lastMotionPayload = { motionAmount: -1, detailDensity: -1, settleProgress: -1 }
let baseInstances = null
const nearDetailEntries = new Set()
const frameDetailEntries = new Set()

const drag = new ArchiveDrag()
const laneTrack = { value: CENTER_LANE, velocity: 0, target: CENTER_LANE }
const rowTrack = { value: CENTER_ROW, velocity: 0, target: CENTER_ROW }
const entries = []
const entriesByPoolKey = new Map()
const textureCache = new Map()
const materialCache = new Map()
const sceneDisposables = []
const baseInstanceGeometries = []
const hiddenInstanceTransform = new Object3D()
const neutralInstanceColor = new Color('#ffffff')
// Per-instance colors multiply the shared material. Keep the focused body near
// white so it stops being slightly dimmer than the surrounding neutral files,
// while the small archive marker receives the stronger champagne cue.
const focusedBodyInstanceColor = new Color('#fffdf8')
const focusedInsetInstanceColor = new Color('#fff8ec')
const focusedMarkInstanceColor = new Color('#dec08a')
const cameraAim = new Vector3(-5.13, -2.03, 0.481)
const cameraBase = new Vector3(-101.635, 11.023, 23.205)
const cameraAimBase = new Vector3(-5.13, -2.03, 0.481)
const cameraDetailBase = new Vector3(-14.8, 7.5, 22.3)
const cameraDetailAim = new Vector3(3.25, 0.3, -0.2)
let cameraBaseFov = 3.0
let cameraDetailFov = 13.6

function smoothstep(value) {
  const t = Math.max(0, Math.min(1, value))
  return t * t * (3 - 2 * t)
}

function clamp01(value) {
  return Math.max(0, Math.min(1, value))
}

function emitMotionState(force = false) {
  const payload = {
    motionAmount: clamp01(motionAmount),
    detailDensity: clamp01(detailDensity),
    settleProgress: clamp01(settleProgress),
  }
  const changed = Math.abs(payload.motionAmount - lastMotionPayload.motionAmount) > 0.012
    || Math.abs(payload.detailDensity - lastMotionPayload.detailDensity) > 0.012
    || Math.abs(payload.settleProgress - lastMotionPayload.settleProgress) > 0.012
  if (!force && !changed) return
  lastMotionPayload = payload
  emit('motion', payload)
}

function detailBudgetForState(extraction) {
  if (extraction > 0.01) return 6
  const densityBudget = Math.max(2, Math.min(6, Math.round(1 + detailDensity * 12)))
  // Keep one browse-detail count for the whole wheel gesture. The previous
  // 2 <-> 3 threshold crossed twice on every scroll (accelerate/decelerate),
  // so a gold-framed card was abruptly removed and re-added while in motion.
  if (props.retrievalState === 'FLOW') return Math.min(3, densityBudget)
  if (props.retrievalState === 'QUERY' || props.retrievalState === 'MATCH') return Math.min(4, densityBudget)
  return Math.min(4 + Math.round(settleProgress * 2), densityBudget)
}

function updateMotionState(dt, visualLane, visualRow) {
  const laneSpeed = Math.abs(visualLane - lastMotionLane) / Math.max(dt, 0.001)
  const rowSpeed = Math.abs(visualRow - lastMotionRow) / Math.max(dt, 0.001)
  lastMotionLane = visualLane
  lastMotionRow = visualRow
  const momentumSpeed = momentum
    ? Math.hypot(momentum.velocity.lane, momentum.velocity.row)
    : Math.hypot(laneTrack.velocity || 0, rowTrack.velocity || 0)
  const measuredSpeed = Math.max(Math.hypot(laneSpeed, rowSpeed), momentumSpeed)
  const motionTarget = clamp01(measuredSpeed / 5.2)
  const motionRate = motionTarget > motionAmount ? 18 : 5.4
  motionAmount += (motionTarget - motionAmount) * (1 - Math.exp(-dt * motionRate))

  const settleTarget = props.retrievalState === 'FOCUSED'
    && !navigationActive
    && !momentum
    && activePointer === null
    && motionAmount < 0.11
    ? 1
    : 0
  const settleRate = settleTarget > settleProgress ? 11.5 : 20
  settleProgress += (settleTarget - settleProgress) * (1 - Math.exp(-dt * settleRate))

  let detailTarget = 0.42
  if (props.retrievalState === 'FLOW') detailTarget = 0.22 - 0.07 * motionAmount
  else if (props.retrievalState === 'QUERY') detailTarget = 0.26
  else if (props.retrievalState === 'MATCH') detailTarget = 0.30
  else detailTarget = 0.35 + 0.07 * settleProgress
  detailTarget = clamp01(detailTarget)
  const detailRate = detailTarget < detailDensity ? 10.5 : 6.2
  detailDensity += (detailTarget - detailDensity) * (1 - Math.exp(-dt * detailRate))
  emitMotionState()
}

function gaussian(distance, width) {
  const scaled = distance / Math.max(0.0001, width)
  return Math.exp(-0.5 * scaled * scaled)
}

function archiveShoulderField(rowOffset, laneOffset) {
  const envelope = Math.max(-0.42, 2.15 - 0.17 * (Math.sqrt(rowOffset * rowOffset + 1) - 1))
  const column = 0.25 + 0.75 * gaussian(laneOffset, 0.55)
  return envelope * column
}

function setDesktopBrowseCamera(width, height) {
  const baseSpan = 7.33
  // Keep the same long-lens vertical framing across desktop aspect ratios.
  // A taller viewport may crop slightly more of the archive field laterally;
  // that is preferable to shrinking the files and exposing a blank top band.
  const span = baseSpan
  const distance = 100
  const referenceDistance = 140
  // Calibrated from the supplied Rhine terminal reference: the dominant
  // archive long-edge projects at roughly 29deg on screen in Browse mode.
  // The browse/slide keyframes keep this camera orientation fixed while the
  // archive field moves; only the later file-open transition rotates away.
  const yaw = 76.75 * Math.PI / 180
  const elevation = 7.5 * Math.PI / 180
  const viewX = -Math.sin(yaw) * Math.cos(elevation)
  const viewY = Math.sin(elevation)
  const viewZ = Math.cos(yaw) * Math.cos(elevation)
  // 55s keyframe alignment: keep the selected file in the left-middle
  // reading zone instead of leaving the aim point above the archive sea.
  cameraAimBase.set(-5.13, -2.16, 0.481)
  cameraBase.set(
    cameraAimBase.x + viewX * distance,
    cameraAimBase.y + viewY * distance,
    cameraAimBase.z + viewZ * distance,
  )
  cameraBaseFov = 2 * Math.atan(span / (2 * referenceDistance)) * 180 / Math.PI
}

function paintLabelTexture(canvas, module) {
  const ctx = canvas.getContext('2d')
  const night = props.nightMode
  ctx.clearRect(0, 0, canvas.width, canvas.height)
  ctx.fillStyle = night ? 'rgba(35, 39, 42, .965)' : 'rgba(244, 241, 234, .78)'
  ctx.fillRect(0, 0, canvas.width, canvas.height)
  ctx.strokeStyle = night ? 'rgba(184, 158, 112, .34)' : 'rgba(112, 108, 99, .72)'
  ctx.lineWidth = 3
  ctx.strokeRect(8, 8, canvas.width - 16, canvas.height - 16)

  ctx.fillStyle = night ? '#969692' : '#817c72'
  ctx.font = '600 24px ui-monospace, SFMono-Regular, Menlo, monospace'
  ctx.fillText(`MODULE / ${String(module.no).padStart(2, '0')}`, 40, 52)
  ctx.textAlign = 'right'
  ctx.fillText(module.code, 975, 52)
  ctx.textAlign = 'left'

  ctx.fillStyle = night ? '#eee8de' : '#1e211c'
  ctx.font = '700 54px ui-monospace, SFMono-Regular, Menlo, monospace'
  ctx.fillText(module.labelEn, 40, 126)

  ctx.fillStyle = night ? '#c8c1b6' : '#4b4d46'
  ctx.font = '600 28px system-ui, sans-serif'
  ctx.fillText(module.labelZh, 40, 178)

  ctx.fillStyle = night ? '#7b7d7c' : '#8c877d'
  ctx.font = '500 19px ui-monospace, SFMono-Regular, Menlo, monospace'
  ctx.fillText(module.capabilities.slice(0, 3).join(' · '), 40, 224)
}

function makeLabelTexture(module) {
  if (textureCache.has(module.id)) return textureCache.get(module.id)
  const canvas = document.createElement('canvas')
  canvas.width = 1024
  canvas.height = 260
  paintLabelTexture(canvas, module)

  const texture = new CanvasTexture(canvas)
  texture.colorSpace = SRGBColorSpace
  texture.anisotropy = Math.min(renderer?.capabilities?.getMaxAnisotropy?.() || 1, 8)
  textureCache.set(module.id, texture)
  return texture
}

function refreshLabelTextures() {
  for (const module of [ANONYMOUS_ARCHIVE, ...(props.modules || [])]) {
    const texture = textureCache.get(module.id)
    if (!texture?.image) continue
    paintLabelTexture(texture.image, module)
    texture.needsUpdate = true
  }
}

function labelMaterial(module) {
  if (materialCache.has(module.id)) return materialCache.get(module.id)
  const material = new MeshBasicMaterial({
    map: makeLabelTexture(module), transparent: true, opacity: 0.94, toneMapped: false,
  })
  materialCache.set(module.id, material)
  return material
}

function disposeBaseInstances() {
  if (baseInstances && root) {
    root.remove(baseInstances.body, baseInstances.inset, baseInstances.mark)
  }
  baseInstances = null
  for (const geometry of baseInstanceGeometries.splice(0)) geometry?.dispose?.()
}

function createBaseInstances() {
  if (!root || !archiveLibrary || !entries.length) return
  disposeBaseInstances()
  const { geometries: g, materials: m } = archiveLibrary
  const insetGeometry = g.inset.clone()
  insetGeometry.translate(0, 0, 0.205)
  const markGeometry = g.marker.clone()
  markGeometry.scale(0.70, 0.58, 0.70)
  markGeometry.translate(2.16, -0.72, 0.37)
  baseInstanceGeometries.push(insetGeometry, markGeometry)

  const count = entries.length
  const body = new InstancedMesh(g.body, m.body, count)
  const inset = new InstancedMesh(insetGeometry, m.inset, count)
  const mark = new InstancedMesh(markGeometry, m.inner, count)
  for (const mesh of [body, inset, mark]) {
    mesh.instanceMatrix.setUsage(DynamicDrawUsage)
    mesh.frustumCulled = false
    mesh.receiveShadow = false
    root.add(mesh)
  }
  body.name = 'ARCHIVE_BASE_BODY_INSTANCED'
  inset.name = 'ARCHIVE_BASE_INSET_INSTANCED'
  mark.name = 'ARCHIVE_BASE_MARK_INSTANCED'
  body.castShadow = false
  baseInstances = { body, inset, mark }
  updateBaseInstanceFocusColors()
}

function updateBaseInstanceFocusColors() {
  if (!baseInstances || !entries.length) return
  const focused = focusedEntry()
  for (const entry of entries) {
    const highlight = props.nightMode && entry === focused
    baseInstances.body.setColorAt(entry.instanceIndex, highlight ? focusedBodyInstanceColor : neutralInstanceColor)
    baseInstances.inset.setColorAt(entry.instanceIndex, highlight ? focusedInsetInstanceColor : neutralInstanceColor)
    baseInstances.mark.setColorAt(entry.instanceIndex, highlight ? focusedMarkInstanceColor : neutralInstanceColor)
  }
  if (baseInstances.body.instanceColor) baseInstances.body.instanceColor.needsUpdate = true
  if (baseInstances.inset.instanceColor) baseInstances.inset.instanceColor.needsUpdate = true
  if (baseInstances.mark.instanceColor) baseInstances.mark.instanceColor.needsUpdate = true
}

function clearArchive() {
  disposeBaseInstances()
  while (root?.children?.length) root.remove(root.children[0])
  entries.splice(0, entries.length)
  entriesByPoolKey.clear()
  nearDetailEntries.clear()
  frameDetailEntries.clear()
  hoveredEntry = null
  for (const material of materialCache.values()) material.dispose()
  for (const texture of textureCache.values()) texture.dispose()
  materialCache.clear()
  textureCache.clear()
}

function prewarmLabelTextures() {
  if (!renderer || !Array.isArray(props.modules) || !props.modules.length) return
  for (const module of props.modules) {
    const texture = makeLabelTexture(module)
    labelMaterial(module)
    renderer.initTexture?.(texture)
  }
}

function createCard(physicalLane, physicalRow) {
  const assembly = createArchiveAssembly(ANONYMOUS_ARCHIVE, labelMaterial(ANONYMOUS_ARCHIVE), archiveLibrary)
  const group = assembly.group

  const baseY = -4.60
  const baseX = (physicalLane - CENTER_LANE) * LANE_SPACING
  const baseZ = FOCUS_Z + (physicalRow - CENTER_ROW) * ROW_SPACING
  group.position.set(baseX, baseY, baseZ)
  group.rotation.y = 0
  group.userData = {
    baseY,
    targetY: baseY,
    physicalLane,
    physicalRow,
    slotKey: `slot:${physicalLane}:${physicalRow}`,
  }
  if (assembly.baseGroup) assembly.baseGroup.visible = false
  root.add(group)
  const entry = {
    ...assembly,
    group,
    physicalLane,
    physicalRow,
    virtualLane: physicalLane,
    virtualRow: physicalRow,
    instanceIndex: entries.length,
  }
  entries.push(entry)
  entriesByPoolKey.set(`${physicalLane}:${physicalRow}`, entry)
}

function buildArchiveArray() {
  if (!root || !renderer || !archiveLibrary) return
  clearArchive()

  for (let physicalRow = LOOP_POOL.rowMin; physicalRow <= LOOP_POOL.rowMax; physicalRow += 1) {
    for (let physicalLane = LOOP_POOL.laneMin; physicalLane <= LOOP_POOL.laneMax; physicalLane += 1) {
      createCard(physicalLane, physicalRow)
    }
  }
  createBaseInstances()

  if (!initializedTrack) {
    laneTrack.value = laneTrack.target = CENTER_LANE
    rowTrack.value = rowTrack.target = CENTER_ROW
    lastReportedRow = CENTER_ROW
    initializedTrack = true
  }
  updateFocusVisuals()
}

function focusedModuleData() {
  return props.modules.find(module => module.key === props.focusedKey) || props.modules[0] || ANONYMOUS_ARCHIVE
}

function currentCell() {
  return { lane: Math.round(laneTrack.value), row: Math.round(rowTrack.value) }
}

function focusedEntry() {
  const cell = currentCell()
  return entriesByPoolKey.get(poolKeyForCell(cell.lane, cell.row, POOL_OPTIONS)) || null
}

function updateWrappedArchivePositions(centerLane, centerRow) {
  for (const entry of entries) {
    const virtualLane = nearestPeriodicCoordinate(entry.physicalLane, centerLane, LOOP_POOL.laneCount)
    const virtualRow = nearestPeriodicCoordinate(entry.physicalRow, centerRow, LOOP_POOL.rowCount)
    entry.virtualLane = virtualLane
    entry.virtualRow = virtualRow
    entry.group.position.x = (virtualLane - centerLane) * LANE_SPACING
    entry.group.position.z = FOCUS_Z + (virtualRow - centerRow) * ROW_SPACING
  }
}

function updateFocusVisuals() {
  const focused = focusedEntry()
  const extraction = Math.max(0, Math.min(1, Number(props.extractionProgress) || 0))
  const identified = extraction > 0.001 || props.retrievalState === 'MATCH' || props.retrievalState === 'FOCUSED'
  // Keep the focused GLB resident before extraction starts. The previous
  // procedural -> GLB swap at extraction ~= 0.035 could trigger first-use
  // shader compilation and looked exactly like a brief refresh followed by a
  // sharper frame.
  const detailVisible = USE_FOCUSED_GLB_IN_TRANSITION
    && (extraction > 0.035 || props.retrievalState === 'FOCUSED')
  const previewLift = props.retrievalState === 'QUERY'
    ? 0.12
    : props.retrievalState === 'MATCH'
      ? 0.28
      : props.retrievalState === 'FOCUSED'
        ? 0.40
        : 0.08
  const module = focusedModuleData()
  updateBaseInstanceFocusColors()
  for (const entry of entries) {
    const isFocused = entry === focused
    const isHovered = entry === hoveredEntry
    const focusedLift = extraction > 0.001
      ? 0.40 + extraction * (4.05 - 0.40)
      : previewLift
    entry.group.userData.targetY = entry.group.userData.baseY + (isFocused ? focusedLift : isHovered ? 0.16 : 0)
    // Reference browse keyframes show the selected file translating/lifting,
    // not scaling up. Keep one physical archive scale through FLOW/QUERY/
    // MATCH/FOCUSED; only the actual file-open extraction may add scale.
    entry.group.userData.targetScale = isFocused && extraction > 0.001
      ? 0.965 + extraction * 0.091
      : isHovered ? 0.985 : 0.965
    if (entry.glass) {
      // Keep the same glass material before and during extraction. Changing to
      // the focused transmissive material altered edge contrast in a single
      // frame and read as another small refresh/sharpen step.
      entry.glass.material = archiveLibrary.materials.glass
    }
    if (entry.label) entry.label.material = isFocused && identified ? labelMaterial(module) : labelMaterial(ANONYMOUS_ARCHIVE)
    if (entry.baseGroup) entry.baseGroup.visible = !baseInstances && !(isFocused && detailAsset && detailVisible)
    if (entry.identityGroup) {
      entry.identityGroup.position.z = isFocused && identified ? (detailVisible ? 0.22 : 0.09) : 0
      entry.identityGroup.visible = Boolean(isFocused && identified)
    }
    if (entry.frameGroup) {
      const frameMaterial = isFocused && identified
        ? archiveLibrary.materials.focusFrame
        : archiveLibrary.materials.frame
      for (const child of entry.frameGroup.children) child.material = frameMaterial
    }
    if (entry.nearGroup && isFocused && detailAsset && detailVisible) entry.nearGroup.visible = false
  }
  if (detailAsset && USE_FOCUSED_GLB_IN_TRANSITION) {
    if (focused && detailAsset.parent !== focused.group) {
      focused.group.add(detailAsset)
      detailAsset.position.set(0, 0, 0)
      detailAsset.rotation.set(0, 0, 0)
    }
    detailAsset.visible = Boolean(focused && detailVisible)
  }
}

async function loadFocusedArchiveAsset() {
  if (detailAsset || detailAssetStatus === 'loading' || detailAssetStatus === 'ready') return
  detailAssetStatus = 'loading'
  try {
    const { GLTFLoader } = await import('three/addons/loaders/GLTFLoader.js')
    const loader = new GLTFLoader()
    const gltf = await loader.loadAsync('/assets/analysis-os/jarvis-archive-v1.glb')
    if (disposed) return
    detailAsset = gltf.scene
    detailAsset.name = 'JARVIS_FOCUSED_ARCHIVE_GLB'
    detailAsset.traverse(node => {
      if (!node.isMesh) return
      node.castShadow = false
      node.receiveShadow = false
    })
    detailAssetStatus = 'ready'
    updateFocusVisuals()
    if (!prewarmTimer && renderer && scene && camera) {
      const prewarm = () => {
        prewarmTimer = 0
        if (disposed || !renderer || !scene || !camera) return
        try { renderer.compile(scene, camera) } catch (_) { /* direct rendering remains available */ }
      }
      if (typeof window.requestIdleCallback === 'function') {
        prewarmTimer = window.requestIdleCallback(prewarm, { timeout: 900 })
      } else {
        prewarmTimer = window.setTimeout(prewarm, 120)
      }
    }
  } catch (error) {
    console.warn('Analysis OS focused GLB unavailable; using procedural fallback', error)
    detailAssetStatus = 'fallback'
  }
}

function disposeFocusedArchiveAsset() {
  if (!detailAsset) return
  detailAsset.traverse(node => {
    if (!node.isMesh) return
    node.geometry?.dispose?.()
    const materials = Array.isArray(node.material) ? node.material : [node.material]
    materials.forEach(material => material?.dispose?.())
  })
  detailAsset.removeFromParent?.()
  detailAsset = null
}

function emitStepFromTrack() {
  const row = Math.round(rowTrack.value)
  const delta = row - lastReportedRow
  if (delta) {
    lastReportedRow = row
    emit('step', delta)
  }
  updateFocusVisuals()
}

function beginFlow(direction = 0) {
  if (!navigationActive) emit('flow', Math.sign(direction || 0))
  navigationActive = true
  settledAt = 0
  motionAmount = Math.max(motionAmount, 0.38)
  settleProgress = 0
  emitMotionState(true)
  // Do not carry a stationary hover preview into wheel/keyboard navigation.
  // The moving archive sea should read as simple volumes until the pointer
  // intentionally settles over a card again.
  hoveredEntry = null
}

function shiftRows(steps, source = 'index') {
  if (!Number.isFinite(steps) || !steps || props.extractionProgress > 0.001) return
  stopMomentum()
  beginFlow(steps)
  rowTrack.target = Math.round(rowTrack.target) + steps
  laneTrack.velocity = 0
  rowTrack.velocity = 0
  emit('interaction', source)
}

function rebaseTracksIfNeeded() {
  const laneShift = Math.round((laneTrack.value - CENTER_LANE) / LOOP_POOL.laneCount) * LOOP_POOL.laneCount
  if (Math.abs(laneShift) >= LOOP_POOL.laneCount) {
    laneTrack.value -= laneShift
    laneTrack.target -= laneShift
    lastMotionLane -= laneShift
    if (momentum) {
      momentum.lane.value -= laneShift
      momentum.lane.target -= laneShift
    }
  }
  const rowShift = Math.round((rowTrack.value - CENTER_ROW) / LOOP_POOL.rowCount) * LOOP_POOL.rowCount
  if (Math.abs(rowShift) >= LOOP_POOL.rowCount) {
    rowTrack.value -= rowShift
    rowTrack.target -= rowShift
    lastReportedRow -= rowShift
    lastMotionRow -= rowShift
    if (momentum) {
      momentum.row.value -= rowShift
      momentum.row.target -= rowShift
    }
  }
}

function screenPoint(world) {
  const rect = renderer.domElement.getBoundingClientRect()
  const point = world.clone().project(camera)
  return { x: (point.x + 1) * rect.width * 0.5, y: (1 - point.y) * rect.height * 0.5 }
}

function dragProjection() {
  const center = new Vector3(0, -2.75, -2.17)
  const base = screenPoint(center)
  const lane = screenPoint(center.clone().add(new Vector3(-LANE_SPACING, 0, -LANE_ROW_SKEW)))
  const row = screenPoint(center.clone().add(new Vector3(0, 0, -ROW_SPACING)))
  return {
    lane: { x: lane.x - base.x, y: lane.y - base.y },
    row: { x: row.x - base.x, y: row.y - base.y },
  }
}

function pointerFromEvent(event) {
  const rect = renderer.domElement.getBoundingClientRect()
  pointer.x = ((event.clientX - rect.left) / rect.width) * 2 - 1
  pointer.y = -((event.clientY - rect.top) / rect.height) * 2 + 1
}

function pickEntry(event) {
  pointerFromEvent(event)
  raycaster.setFromCamera(pointer, camera)
  const identityTargets = entries.flatMap(entry => entry.label?.visible ? [entry.label] : [])
  const targets = baseInstances?.body ? [baseInstances.body, ...identityTargets] : entries.flatMap(entry => entry.hitTargets)
  const hit = raycaster.intersectObjects(targets, false)[0]
  if (!hit) return null
  if (baseInstances?.body && hit.object === baseInstances.body && Number.isInteger(hit.instanceId)) {
    return entries[hit.instanceId] || null
  }
  return entries.find(entry => entry.hitTargets.includes(hit.object)) || null
}

function stopMomentum() {
  momentum = null
  laneTrack.velocity = 0
  rowTrack.velocity = 0
}

function sleepOffsets(time = performance.now()) {
  const amount = suppressSleepMotion ? 0 : Math.max(0, Math.min(1, Number(props.sleepAmount) || 0))
  return {
    amount,
    // Sleep is a single slow field drift, not hundreds of independent card
    // oscillations. Keep the amplitude deliberately small so long parallel
    // archive edges do not crawl across the pixel grid while idle.
    lane: Math.sin(time / 13_000) * 0.18 * amount,
    row: (Math.sin(time / 9_500) * 0.72 + Math.sin(time / 21_000) * 0.28) * amount,
  }
}

function captureSleepPosition() {
  if (suppressSleepMotion || props.sleepAmount <= 0.001) return
  const offset = sleepOffsets()
  laneTrack.value += offset.lane
  laneTrack.target += offset.lane
  rowTrack.value += offset.row
  rowTrack.target += offset.row
  suppressSleepMotion = true
  emitStepFromTrack()
}

function onPointerDown(event) {
  if (props.extractionProgress > 0.001) return
  if (event.pointerType === 'mouse' && event.button !== 0) return
  captureSleepPosition()
  activePointer = event.pointerId
  stopMomentum()
  dragStart = { lane: laneTrack.value, row: rowTrack.value }
  drag.start(event.clientX, event.clientY, dragProjection(), event.timeStamp)
  renderer.domElement.setPointerCapture?.(event.pointerId)
  emit('interaction', 'pointer')
}

function onPointerMove(event) {
  if (props.extractionProgress > 0.001 && activePointer === null) return
  if (activePointer === null) {
    const entry = pickEntry(event)
    if (entry !== hoveredEntry) {
      hoveredEntry = entry
      updateFocusVisuals()
    }
    renderer.domElement.style.cursor = entry ? 'pointer' : 'grab'
    return
  }
  if (event.pointerId !== activePointer) return
  drag.move(event.clientX, event.clientY, event.timeStamp)
  if (!drag.active) return
  beginFlow(drag.value.row || drag.value.lane)
  hoveredEntry = null
  laneTrack.value = laneTrack.target = dragStart.lane + drag.value.lane
  rowTrack.value = rowTrack.target = dragStart.row + drag.value.row
  laneTrack.velocity = rowTrack.velocity = 0
  renderer.domElement.style.cursor = 'grabbing'
  emitStepFromTrack()
}

function finishPointer(event, cancelled = false) {
  if (event.pointerId !== activePointer) return
  activePointer = null
  if (renderer.domElement.hasPointerCapture?.(event.pointerId)) renderer.domElement.releasePointerCapture(event.pointerId)
  renderer.domElement.style.cursor = 'grab'

  if (!cancelled && drag.active) {
    if (reducedMotion) {
      laneTrack.target = Math.round(laneTrack.value)
      rowTrack.target = Math.round(rowTrack.value)
    } else {
      momentum = new ArchivePlaneMomentum(
        { lane: laneTrack.value, row: rowTrack.value },
        drag.releaseVelocity(event.timeStamp, false),
      )
    }
    return
  }

  if (!cancelled && !drag.moved) {
    const entry = pickEntry(event)
    if (entry) {
      const current = focusedEntry()
      if (current && current.physicalLane === entry.physicalLane && current.physicalRow === entry.physicalRow) {
        emit('activate', props.focusedKey)
      } else {
        const cell = currentCell()
        beginFlow(entry.virtualRow - cell.row)
        laneTrack.target = entry.virtualLane
        rowTrack.target = entry.virtualRow
      }
    }
  }
}

function onWheel(event) {
  if (props.extractionProgress > 0.001) return
  if (event.ctrlKey || Math.abs(event.deltaX) > Math.abs(event.deltaY)) return
  event.preventDefault()
  captureSleepPosition()
  emit('interaction', 'wheel')
  stopMomentum()
  const now = performance.now()
  const normalized = Math.max(-300, Math.min(300, event.deltaY * (event.deltaMode === 1 ? 40 : event.deltaMode === 2 ? renderer.domElement.clientHeight : 1)))
  if (now - wheelTime > 180 || Math.sign(normalized) !== Math.sign(wheelTotal)) wheelTotal = 0
  wheelTime = now
  wheelTotal += normalized
  const steps = Math.min(3, Math.floor(Math.abs(wheelTotal) / 100))
  if (!steps) return
  const direction = Math.sign(wheelTotal)
  wheelTotal -= direction * steps * 100
  beginFlow(direction)
  rowTrack.target = Math.round(rowTrack.target) + direction * steps
}

function resize() {
  const element = mountRef.value
  if (!element || !renderer || !camera) return
  const width = Math.max(1, element.clientWidth)
  const height = Math.max(1, element.clientHeight)
  const previousQuality = qualityProfile
  const nextQuality = archiveQualityProfile(width, window.devicePixelRatio || 1)
  const qualityChanged = !qualityProfile || qualityProfile.name !== nextQuality.name
  qualityProfile = nextQuality
  configureArchiveRenderer(renderer, qualityProfile)
  renderer.shadowMap.enabled = false
  const nativeDpr = window.devicePixelRatio || 1
  const renderDpr = Math.min(nativeDpr * (qualityProfile.renderScale || 1), qualityProfile.maxDpr)
  renderer.setPixelRatio(renderDpr)
  renderer.setSize(width, height, false)
  if (USE_FOCUSED_GLB_IN_TRANSITION
    && qualityProfile.name !== 'MOBILE'
    && detailAssetStatus === 'idle') loadFocusedArchiveAsset()
  if (qualityChanged && !qualityProfile.postCandidate && composerBundle) {
    composerRevision += 1
    disposeArchiveComposer(composerBundle)
    composerBundle = null
    postProcessingStatus = 'direct'
  } else if (qualityChanged && qualityProfile.post && composerBundle) {
    rebuildComposer(width, height, qualityProfile)
  } else if (qualityChanged && qualityProfile.postCandidate && !composerBundle && !postProbeFailed) {
    postProbeAttempted = false
    postProcessingStatus = previousQuality?.postCandidate ? postProcessingStatus : 'direct'
  }
  if (qualityProfile.post && !composerBundle) rebuildComposer(width, height, qualityProfile)
  resizeArchiveComposer(composerBundle, width, height)
  if (keyLight) {
    const shadowSize = qualityProfile.name === 'HIGH' ? 2048 : 1024
    keyLight.castShadow = false
    keyLight.shadow.mapSize.set(shadowSize, shadowSize)
  }
  camera.aspect = width / height
  if (width < 700) {
    cameraBase.set(-23.5, 14.5, 30.5)
    cameraBaseFov = 14.5
    cameraDetailBase.set(-8.9, 6.8, 25.7)
    cameraDetailAim.set(1.2, 0.4, 0)
    cameraDetailFov = 18
    cameraAimBase.set(-0.2, -1.0, -1.1)
  } else if (width < 1100) {
    cameraBase.set(-44.0, 25.0, 35.0)
    cameraBaseFov = 8.6
    cameraDetailBase.set(-12.5, 7.2, 23.2)
    cameraDetailAim.set(2.5, 0.25, 0)
    cameraDetailFov = 15
    cameraAimBase.set(-0.4, 0.2, -0.2)
  } else {
    setDesktopBrowseCamera(width, height)
    cameraDetailBase.set(-14.8, 7.5, 22.3)
    cameraDetailAim.set(3.25, 0.3, -0.2)
    cameraDetailFov = 13.6
  }
  camera.fov = cameraBaseFov
  camera.position.copy(cameraBase)
  cameraAim.copy(cameraAimBase)
  camera.lookAt(cameraAim)
  camera.updateProjectionMatrix()
}

async function rebuildComposer(width, height, profile) {
  const revision = ++composerRevision
  disposeArchiveComposer(composerBundle)
  composerBundle = null
  if (!profile?.post || !renderer || !scene || !camera) return
  const next = await createArchiveComposer({ renderer, scene, camera, width, height, profile })
  if (revision !== composerRevision || disposed || !next) {
    disposeArchiveComposer(next)
    return
  }
  composerBundle = next
  resizeArchiveComposer(composerBundle, width, height)
}

function schedulePostProcessingProbe(width, height, profile) {
  if (postProbeAttempted || postProbeTimer || reducedMotion || !profile?.postCandidate) return
  if (typeof navigator !== 'undefined' && navigator.webdriver) {
    postProbeAttempted = true
    postProcessingStatus = 'skipped-automation'
    return
  }

  postProbeTimer = window.setTimeout(async () => {
    postProbeTimer = 0
    if (disposed || !renderer || !scene || !camera || !props.active || postProbeAttempted) return
    postProbeAttempted = true
    postProcessingStatus = 'probing'
    const revision = ++composerRevision
    const result = await probeArchiveComposer({ renderer, scene, camera, width, height, profile })
    if (revision !== composerRevision || disposed) {
      disposeArchiveComposer(result?.bundle)
      return
    }
    composerBundle = result?.bundle || null
    postProcessingStatus = result?.status || 'fallback'
    postProbeFailed = !composerBundle && String(postProcessingStatus).startsWith('fallback')
    resizeArchiveComposer(composerBundle, width, height)
  }, 700)
}

function maybeEmitSettled() {
  if (!navigationActive || momentum || activePointer !== null) return
  const laneSettled = Math.abs(laneTrack.value - laneTrack.target) < 0.002
  const rowSettled = Math.abs(rowTrack.value - rowTrack.target) < 0.002
  if (!laneSettled || !rowSettled) return
  navigationActive = false
  settledAt = performance.now()
  emit('settled')
}

function renderFrame(time) {
  if (disposed || !renderer || !scene || !camera || !root || !props.active) {
    rendering = false
    animationFrame = 0
    return
  }
  const extraction = Math.max(0, Math.min(1, Number(props.extractionProgress) || 0))
  const tracksResting = Math.abs(laneTrack.value - laneTrack.target) < 0.00002
    && Math.abs(rowTrack.value - rowTrack.target) < 0.00002
    && motionAmount < 0.0008
    && settleProgress > 0.998
  const staticBrowse = tracksResting
    && settledAt > 0
    && time - settledAt > 1200
    && !momentum
    && activePointer === null
    && !navigationActive
    && props.retrievalState === 'FOCUSED'
    && extraction < 0.001
    && Number(props.sleepAmount || 0) <= 0.001
  if (staticBrowse && lastRenderedAt && time - lastRenderedAt < IDLE_RENDER_INTERVAL_MS) {
    animationFrame = requestAnimationFrame(renderFrame)
    return
  }
  lastRenderedAt = time

  const dt = Math.min(Math.max((time - lastFrameTime) / 1000, 0.001), 0.05)
  lastFrameTime = time
  renderedFrames += 1

  if (momentum) {
    momentum.step(dt)
    laneTrack.value = laneTrack.target = momentum.value.lane
    rowTrack.value = rowTrack.target = momentum.value.row
    emitStepFromTrack()
    rebaseTracksIfNeeded()
    if (momentum.phase === 'idle') {
      momentum = null
      laneTrack.target = Math.round(laneTrack.value)
      rowTrack.target = Math.round(rowTrack.value)
      rebaseTracksIfNeeded()
    }
  } else if (activePointer === null) {
    const previousCell = currentCell()
    dampSpring(laneTrack, laneTrack.target, reducedMotion ? 30 : 9, dt)
    dampSpring(rowTrack, rowTrack.target, reducedMotion ? 30 : 9, dt)
    const nextCell = currentCell()
    if (nextCell.row !== previousCell.row) emitStepFromTrack()
    else if (nextCell.lane !== previousCell.lane) updateFocusVisuals()
    if (Math.abs(laneTrack.value - laneTrack.target) < 0.0004 && Math.abs(rowTrack.value - rowTrack.target) < 0.0004) rebaseTracksIfNeeded()
  }
  maybeEmitSettled()

  const detail = smoothstep(Math.max(0, (extraction - 0.08) / 0.92))
  const sleep = sleepOffsets(time)
  sleep.amount *= (1 - detail)
  sleep.lane *= (1 - detail)
  sleep.row *= (1 - detail)
  root.position.set(0, 0, 0)
  const visualLane = laneTrack.value + sleep.lane
  const visualRow = rowTrack.value + sleep.row
  updateWrappedArchivePositions(visualLane, visualRow)
  updateMotionState(dt, visualLane, visualRow)

  camera.position.copy(cameraBase).lerp(cameraDetailBase, detail)
  cameraAim.copy(cameraAimBase).lerp(cameraDetailAim, detail)
  const nextFov = cameraBaseFov + (cameraDetailFov - cameraBaseFov) * detail
  if (Math.abs(camera.fov - nextFov) > 0.0001) {
    camera.fov = nextFov
    camera.updateProjectionMatrix()
  }
  if (sleep.amount > 0) {
    camera.position.x += Math.sin(time / 11_500) * 0.12 * sleep.amount
    camera.position.y += Math.sin(time / 16_000) * 0.06 * sleep.amount
    cameraAim.x += Math.sin(time / 14_000) * 0.045 * sleep.amount
    cameraAim.y += Math.sin(time / 19_000) * 0.025 * sleep.amount
  }
  camera.lookAt(cameraAim)

  // Browse + extraction deliberately stay on one stable render path. A prior
  // version enabled SSAO and shadow maps part-way through extraction; each
  // switch forced render-target/shader work and the next frame looked sharper,
  // which users perceived as repeated refresh hitches.
  const extractionDetail = smoothstep(Math.max(0, (extraction - 0.46) / 0.54))
  if (composerBundle?.ssaoPass) composerBundle.ssaoPass.enabled = false
  if (keyLight) keyLight.castShadow = false

  const fog = scene.fog
  if (fog instanceof Fog) {
    const renderedDistance = camera.position.distanceTo(cameraAim)
    // Reference keyframes keep edge/card detail much crisper than our old
    // washed vignette. Browse fog must not react to wheel velocity: coupling
    // the full-screen fog field to motionAmount changed the luminance of most
    // pixels whenever scrolling started/stopped, which reads as a screen flash
    // even when card geometry itself is stable. Only extraction is allowed to
    // tighten the fog range.
    fog.near = renderedDistance + (8 - 7 * detail)
    fog.far = renderedDistance + (34 - 18 * detail)
  }

  const easing = reducedMotion ? 1 : 1 - Math.exp(-dt * 10)
  const focused = focusedEntry()
  const identified = extraction > 0.001 || props.retrievalState === 'MATCH' || props.retrievalState === 'FOCUSED'
  if (focusedGlassMaterial) {
    const decrypt = smoothstep(Math.max(0, (extraction - 0.38) / 0.52))
    focusedGlassMaterial.roughness = 0.42 + (0.12 - 0.42) * decrypt
    focusedGlassMaterial.transmission = 0.36 + (0.88 - 0.36) * decrypt
    focusedGlassMaterial.thickness = 0.13 + (0.075 - 0.13) * decrypt
    focusedGlassMaterial.opacity = 0.98 + (0.9 - 0.98) * decrypt
  }

  const detailBudget = detailBudgetForState(extraction)
  const retainedNearDetail = new Set(nearDetailEntries)
  const detailCandidates = entries
    .map(entry => {
      const laneDistance = Math.abs(entry.virtualLane - visualLane)
      const rowDistance = Math.abs(entry.virtualRow - visualRow)
      return {
        entry,
        laneDistance,
        rowDistance,
        score: rowDistance + laneDistance * 2.8
          - (retainedNearDetail.has(entry) ? DETAIL_RETENTION_BIAS : 0),
      }
    })
    .filter(item => item.laneDistance <= 1.35 && item.rowDistance <= 4.2)
    .sort((a, b) => a.score - b.score)
    .slice(0, detailBudget)
  const nextNearDetailEntries = new Set(detailCandidates.map(item => item.entry))
  if (focused && !nextNearDetailEntries.has(focused)) {
    if (nextNearDetailEntries.size >= detailBudget) {
      const last = detailCandidates.at(-1)?.entry
      if (last) nextNearDetailEntries.delete(last)
    }
    nextNearDetailEntries.add(focused)
  }
  nearDetailEntries.clear()
  nextNearDetailEntries.forEach(entry => nearDetailEntries.add(entry))

  const retainedFrames = new Set(frameDetailEntries)
  const frameCandidates = entries
    .map(entry => {
      const laneDistance = Math.abs(entry.virtualLane - visualLane)
      const rowDistance = Math.abs(entry.virtualRow - visualRow)
      return {
        entry,
        laneDistance,
        rowDistance,
        score: rowDistance + laneDistance * 2.8
          - (retainedFrames.has(entry) ? FRAME_RETENTION_BIAS : 0),
      }
    })
    .filter(item => item.laneDistance <= 1.35 && item.rowDistance <= 4.8)
    .sort((a, b) => a.score - b.score || a.entry.instanceIndex - b.entry.instanceIndex)
    .slice(0, FRAME_DETAIL_BUDGET)
  const nextFrameEntries = new Set(frameCandidates.map(item => item.entry))
  if (focused && !nextFrameEntries.has(focused)) {
    if (nextFrameEntries.size >= FRAME_DETAIL_BUDGET) {
      const last = frameCandidates.at(-1)?.entry
      if (last) nextFrameEntries.delete(last)
    }
    nextFrameEntries.add(focused)
  }
  frameDetailEntries.clear()
  nextFrameEntries.forEach(entry => frameDetailEntries.add(entry))

  const shoulderLane = sleep.amount > 0 ? laneTrack.value : visualLane
  const shoulderRow = sleep.amount > 0 ? rowTrack.value : visualRow

  entries.forEach(entry => {
    const data = entry.group.userData
    // Avoid sub-pixel per-card oscillation in browse/sleep. The archive field
    // already has global track/camera drift; independent bob/tilt on hundreds
    // of long parallel edges creates temporal aliasing (edge crawl/shimmer).
    const laneRelative = entry.virtualLane - shoulderLane
    const rowRelative = entry.virtualRow - shoulderRow
    const isFocused = entry === focused
    const isNear = nearDetailEntries.has(entry)
    const hasFrame = frameDetailEntries.has(entry)
    const focusedDetailActive = Boolean(
      isFocused
      && USE_FOCUSED_GLB_IN_TRANSITION
      && detailAsset
      && (extraction > 0.035 || props.retrievalState === 'FOCUSED')
    )
    const showIdentity = Boolean(isFocused && identified)
    const shoulder = archiveShoulderField(rowRelative, laneRelative) * (1 - detail)
    const targetY = data.targetY + shoulder
    const yDelta = targetY - entry.group.position.y
    entry.group.position.y = Math.abs(yDelta) < 0.00035
      ? targetY
      : entry.group.position.y + yDelta * easing

    const targetScale = data.targetScale || 1
    const scaleDelta = targetScale - entry.group.scale.x
    const scale = Math.abs(scaleDelta) < 0.00005
      ? targetScale
      : entry.group.scale.x + scaleDelta * easing
    entry.group.scale.setScalar(scale)
    const targetTilt = 0
    entry.group.rotation.x += (targetTilt - entry.group.rotation.x) * easing
    entry.group.updateMatrix()

    if (baseInstances) {
      let matrix = entry.group.matrix
      if (focusedDetailActive) {
        hiddenInstanceTransform.position.copy(entry.group.position)
        hiddenInstanceTransform.rotation.copy(entry.group.rotation)
        hiddenInstanceTransform.scale.setScalar(0.0001)
        hiddenInstanceTransform.updateMatrix()
        matrix = hiddenInstanceTransform.matrix
      }
      baseInstances.body.setMatrixAt(entry.instanceIndex, matrix)
      baseInstances.inset.setMatrixAt(entry.instanceIndex, matrix)
      baseInstances.mark.setMatrixAt(entry.instanceIndex, matrix)
    }

    if (entry.identityGroup) entry.identityGroup.visible = showIdentity
    if (entry.marker) entry.marker.visible = showIdentity && !focusedDetailActive
    if (entry.labelCarrier) entry.labelCarrier.visible = showIdentity && !focusedDetailActive
    if (entry.label) entry.label.visible = showIdentity
    // Never render the procedural shell on top of the focused GLB. After the
    // previous prewarm change the GLB becomes visible already in FOCUSED state,
    // while these layers used to stay visible until extraction > 0.035. The two
    // nearly-coplanar representations fought in the depth buffer, producing the
    // saw-tooth / refresh-like shimmer visible along the selected file edges.
    if (entry.frameGroup) entry.frameGroup.visible = hasFrame && !focusedDetailActive
    entry.nearGroup.visible = isNear && !focusedDetailActive
    // Full latch/fastener/decrypt hardware is an "open file" detail, not a
    // browse-state decoration. Hover may still preview it for affordance.
    // Do not introduce the focus hardware after the click has already started
    // moving the card. Keep the stable hardware visible as soon as the archive
    // reaches FOCUSED so extraction itself only changes transforms, not the
    // selected card's silhouette.
    const focusPreviewVisible = (isFocused && (props.retrievalState === 'FOCUSED' || extraction > 0.001))
      || entry === hoveredEntry
    entry.focusGroup.visible = focusPreviewVisible
    if (entry.focusGroup) {
      for (const child of entry.focusGroup.children) {
        if (child === entry.decryptA || child === entry.decryptB) continue
        child.visible = !focusedDetailActive && !child.userData?.archiveGlassEdge
      }
    }
    if (entry.body) entry.body.castShadow = false

    if (entry.decryptA && entry.decryptB) {
      const decryptProgress = isFocused ? smoothstep(Math.max(0, (extraction - 0.46) / 0.36)) : 0
      entry.decryptA.visible = decryptProgress > 0.01
      entry.decryptB.visible = decryptProgress > 0.01
      entry.decryptA.position.x = -0.82 + decryptProgress * 0.42
      entry.decryptB.position.x = 0.82 - decryptProgress * 0.42
      entry.decryptA.scale.x = 0.62 + decryptProgress * 0.58
      entry.decryptB.scale.x = 0.62 + decryptProgress * 0.58
    }
  })

  if (baseInstances) {
    baseInstances.body.instanceMatrix.needsUpdate = true
    baseInstances.inset.instanceMatrix.needsUpdate = true
    baseInstances.mark.instanceMatrix.needsUpdate = true
  }

  renderer.render(scene, camera)
  // Keep optional post-processing completely off the browse path. Allocating
  // and probing fullscreen render targets while the user is simply browsing
  // can still cause a one-frame GPU/compositor disturbance on some drivers.
  // If post is ever needed, defer that work until the file is actually being
  // extracted; direct rendering remains the stable fallback throughout browse.
  if (!STABLE_DIRECT_EXTRACTION
    && extraction > 0.52
    && !postProbeAttempted
    && qualityProfile?.postCandidate
    && renderedFrames > 24) {
    const rect = renderer.domElement.getBoundingClientRect()
    schedulePostProcessingProbe(Math.max(1, rect.width), Math.max(1, rect.height), qualityProfile)
  }
  animationFrame = requestAnimationFrame(renderFrame)
}

function startRendering() {
  if (disposed || !renderer || rendering || !props.active) return
  rendering = true
  lastFrameTime = performance.now()
  animationFrame = requestAnimationFrame(renderFrame)
}

function stopRendering() {
  if (animationFrame) cancelAnimationFrame(animationFrame)
  animationFrame = 0
  rendering = false
}

function applySceneTheme() {
  if (!scene || !renderer) return
  const mode = props.nightMode ? 'night' : 'day'
  const theme = ARCHIVE_SCENE_THEMES[mode]

  scene.background?.set?.(theme.background)
  if (scene.fog?.color) scene.fog.color.set(theme.background)
  renderer.setClearColor(theme.background, 1)
  renderer.toneMappingExposure = theme.exposure

  if (ambientLight) {
    ambientLight.color.setHex(theme.ambientColor)
    ambientLight.intensity = theme.ambientIntensity
  }
  if (hemisphereLight) {
    hemisphereLight.color.setHex(theme.hemisphereSky)
    hemisphereLight.groundColor.setHex(theme.hemisphereGround)
    hemisphereLight.intensity = theme.hemisphereIntensity
  }
  if (keyLight) {
    keyLight.color.setHex(theme.keyColor)
    keyLight.intensity = theme.keyIntensity
  }
  if (fillLight) {
    fillLight.color.setHex(theme.fillColor)
    fillLight.intensity = theme.fillIntensity
  }
  if (rimLight) {
    rimLight.color.setHex(theme.rimColor)
    rimLight.intensity = theme.rimIntensity
  }
  if (auroraLight) {
    auroraLight.color.setHex(theme.auroraColor)
    auroraLight.intensity = theme.auroraIntensity
  }
  if (floorMaterial) floorMaterial.color.set(theme.floorColor)

  applyArchiveTheme(archiveLibrary, mode, focusedGlassMaterial)
  refreshLabelTextures()
  updateBaseInstanceFocusColors()
  lastRenderedAt = 0
}

function init() {
  const element = mountRef.value
  if (!element) return
  try {
    reducedMotion = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches || false
    scene = new Scene()
    scene.background = new Color('#eae5e1')
    // The reference project uses custom archive shaders with a much shorter fog
    // range. Standard materials need a longer falloff to keep the foreground
    // files crisp while still dissolving the distant rows.
    scene.fog = new Fog('#eae5e1', 145, 165)
    camera = new PerspectiveCamera(3.0, 1, 0.1, 220)
    renderer = new WebGLRenderer({ antialias: true, alpha: false, powerPreference: 'high-performance' })
    qualityProfile = archiveQualityProfile(element.clientWidth || 1440, window.devicePixelRatio || 1)
    configureArchiveRenderer(renderer, qualityProfile)
    renderer.shadowMap.enabled = false
    renderer.setClearColor('#eae5e1', 1)
    renderer.domElement.style.touchAction = 'none'
    element.appendChild(renderer.domElement)

    root = new Group()
    scene.add(root)
    archiveLibrary = createArchiveAssetLibrary()
    focusedGlassMaterial = createFocusedGlassMaterial(archiveLibrary)

    ambientLight = new AmbientLight(0xfffbf5, 0.24)
    hemisphereLight = new HemisphereLight(0xfffbf4, 0xa79f92, 0.82)
    scene.add(ambientLight)
    scene.add(hemisphereLight)
    keyLight = new DirectionalLight(0xfffbf1, 2.35)
    keyLight.position.set(-11, 18, 13)
    keyLight.castShadow = false
    keyLight.shadow.mapSize.set(qualityProfile.name === 'HIGH' ? 2048 : 1024, qualityProfile.name === 'HIGH' ? 2048 : 1024)
    keyLight.shadow.camera.left = -28
    keyLight.shadow.camera.right = 28
    keyLight.shadow.camera.top = 26
    keyLight.shadow.camera.bottom = -18
    keyLight.shadow.camera.near = 1
    keyLight.shadow.camera.far = 85
    keyLight.shadow.bias = -0.0004
    keyLight.shadow.radius = 2.2
    scene.add(keyLight)
    fillLight = new DirectionalLight(0xcabda9, 0.72)
    fillLight.position.set(14, 8, -10)
    scene.add(fillLight)
    rimLight = new DirectionalLight(0xe6d8c5, 0.66)
    rimLight.position.set(7, 4, 18)
    scene.add(rimLight)
    // Allocate the distant dawn fill once. Day keeps it at zero intensity;
    // Night only updates color/intensity so theme changes never rebuild WebGL
    // resources or wash the near archive field in cyan.
    auroraLight = new DirectionalLight(0x8aa2a8, 0)
    auroraLight.position.set(-20, 14, -11)
    scene.add(auroraLight)

    const floorGeometry = new PlaneGeometry(200, 200)
    floorMaterial = new MeshStandardMaterial({ color: '#d8c9b9', roughness: 0.95, metalness: 0.02 })
    const floor = new Mesh(floorGeometry, floorMaterial)
    floor.rotation.x = -Math.PI / 2
    floor.position.y = -4.63
    floor.receiveShadow = false
    scene.add(floor)
    sceneDisposables.push(floorGeometry, floorMaterial)
    applySceneTheme()

    raycaster = new Raycaster()
    pointer = new Vector2()
    buildArchiveArray()
    resize()
    updateFocusVisuals()
    const labelPrewarm = () => {
      labelPrewarmTimer = 0
      if (!disposed) prewarmLabelTextures()
    }
    if (typeof window.requestIdleCallback === 'function') {
      labelPrewarmTimer = window.requestIdleCallback(labelPrewarm, { timeout: 650 })
    } else {
      labelPrewarmTimer = window.setTimeout(labelPrewarm, 180)
    }
    if (USE_FOCUSED_GLB_IN_TRANSITION && qualityProfile?.name !== 'MOBILE') loadFocusedArchiveAsset()

    renderer.domElement.addEventListener('pointerdown', onPointerDown)
    renderer.domElement.addEventListener('pointermove', onPointerMove)
    renderer.domElement.addEventListener('pointerup', event => finishPointer(event, false))
    renderer.domElement.addEventListener('pointercancel', event => finishPointer(event, true))
    renderer.domElement.addEventListener('lostpointercapture', event => finishPointer(event, true))
    renderer.domElement.addEventListener('pointerleave', () => {
      if (activePointer === null) {
        hoveredEntry = null
        updateFocusVisuals()
      }
    })
    renderer.domElement.addEventListener('wheel', onWheel, { passive: false })
    resizeObserver = new ResizeObserver(resize)
    resizeObserver.observe(element)
    startRendering()
  } catch (error) {
    console.error('Analysis OS WebGL scene failed to initialize', error)
    failed.value = true
  }
}

function dispose() {
  disposed = true
  composerRevision += 1
  if (prewarmTimer) {
    if (typeof window.cancelIdleCallback === 'function') window.cancelIdleCallback(prewarmTimer)
    else window.clearTimeout(prewarmTimer)
  }
  prewarmTimer = 0
  if (labelPrewarmTimer) {
    if (typeof window.cancelIdleCallback === 'function') window.cancelIdleCallback(labelPrewarmTimer)
    else window.clearTimeout(labelPrewarmTimer)
  }
  labelPrewarmTimer = 0
  if (postProbeTimer) window.clearTimeout(postProbeTimer)
  postProbeTimer = 0
  stopRendering()
  resizeObserver?.disconnect()
  clearArchive()
  for (const item of sceneDisposables.splice(0)) item?.dispose?.()
  disposeArchiveComposer(composerBundle)
  composerBundle = null
  focusedGlassMaterial?.dispose?.()
  focusedGlassMaterial = null
  archiveLibrary?.dispose?.()
  archiveLibrary = null
  disposeFocusedArchiveAsset()
  renderer?.dispose()
  renderer?.forceContextLoss?.()
  renderer?.domElement?.remove()
  ambientLight = hemisphereLight = keyLight = fillLight = rimLight = null
  floorMaterial = null
  renderer = scene = camera = root = raycaster = pointer = null
}

function getDebugState() {
  const visibleNear = entries.filter(entry => entry.nearGroup?.visible).length
  const visibleFrames = entries.filter(entry => entry.frameGroup?.visible).length
  const visibleFocus = entries.filter(entry => entry.focusGroup?.visible).length
  return {
    cell: currentCell(),
    lane: laneTrack.value,
    row: rowTrack.value,
    laneTarget: laneTrack.target,
    rowTarget: rowTrack.target,
    laneVelocity: momentum?.velocity?.lane ?? laneTrack.velocity,
    rowVelocity: momentum?.velocity?.row ?? rowTrack.velocity,
    momentumPhase: momentum?.phase || 'idle',
    dragging: activePointer !== null && drag.active,
    focusedKey: props.focusedKey,
    retrievalState: props.retrievalState,
    focusedSlot: focusedEntry()?.group?.userData?.slotKey || null,
    extractionProgress: Number(props.extractionProgress) || 0,
    sleepAmount: Number(props.sleepAmount) || 0,
    quality: qualityProfile?.name || 'UNKNOWN',
    pixelRatio: renderer?.getPixelRatio?.() ?? null,
    drawingBufferSize: renderer?.domElement
      ? [renderer.domElement.width, renderer.domElement.height]
      : null,
    postProcessing: Boolean(composerBundle),
    postCandidate: Boolean(qualityProfile?.postCandidate),
    postProcessingStatus,
    detailAssetStatus,
    cameraPosition: camera?.position?.toArray?.() || null,
    cameraAim: cameraAim?.toArray?.() || null,
    cameraFov: camera?.fov ?? null,
    canvasSize: renderer?.domElement
      ? [renderer.domElement.clientWidth, renderer.domElement.clientHeight]
      : null,
    motionAmount,
    detailDensity,
    settleProgress,
    browseDetailDensity: detailDensity,
    baseInstancing: Boolean(baseInstances),
    instanceCount: baseInstances?.body?.count || 0,
    visibleNear,
    visibleFrames,
    visibleFocus,
    drawCalls: renderer?.info?.render?.calls ?? 0,
    triangles: renderer?.info?.render?.triangles ?? 0,
    renderedFrames,
    canvasCount: renderer?.domElement?.isConnected ? 1 : 0,
  }
}

watch(() => props.modules, updateFocusVisuals, { deep: true })
watch(() => props.focusedKey, updateFocusVisuals)
watch(() => props.retrievalState, updateFocusVisuals)
watch(() => props.active, async active => {
  if (active) {
    await nextTick()
    if (props.active) {
      resize()
      startRendering()
    }
  } else {
    stopMomentum()
    stopRendering()
  }
})
watch(() => props.sleepAmount, amount => {
  if (amount <= 0.001) suppressSleepMotion = false
})
watch(() => props.extractionProgress, updateFocusVisuals)
watch(() => props.nightMode, () => {
  applySceneTheme()
  if (props.active) startRendering()
})

onMounted(init)
onBeforeUnmount(dispose)

defineExpose({ getDebugState, shiftRows })
</script>

<template>
  <div ref="mountRef" class="analysis-scene" :class="{ 'is-night': props.nightMode }" aria-hidden="true">
    <div v-if="failed" class="scene-fallback">
      <strong>WEBGL FALLBACK</strong>
      <span>三维档案场不可用，仍可使用顶部 MODULE INDEX 进入各业务模块。</span>
    </div>
  </div>
</template>

<style scoped>
.analysis-scene {
  position: absolute;
  inset: 0;
  overflow: hidden;
  background: #e8e5e1;
  transition: background-color .65s cubic-bezier(.22,1,.36,1);
}
.analysis-scene.is-night { background: #0c1013; }
.analysis-scene::after {
  content: '';
  position: absolute;
  inset: 0;
  pointer-events: none;
  background:
    linear-gradient(180deg, rgba(248,246,240,.08), transparent 20%, transparent 82%, rgba(234,229,225,.03)),
    radial-gradient(ellipse at 48% 48%, transparent 62%, rgba(234,229,225,.025) 82%, rgba(234,229,225,.08) 100%);
}
.analysis-scene.is-night::after {
  background:
    radial-gradient(ellipse at 56% 47%, rgba(218,190,143,.052) 0%, rgba(158,131,88,.018) 31%, transparent 56%),
    linear-gradient(180deg, rgba(151,161,160,.012), transparent 22%, transparent 80%, rgba(5,7,8,.07));
}
.analysis-scene :deep(canvas) { width: 100%; height: 100%; display: block; }
.scene-fallback {
  position: absolute; inset: 0; display: grid; place-content: center; gap: 8px;
  color: #625f57; text-align: center; font-size: 12px;
}
.scene-fallback strong { color: #292b25; letter-spacing: .12em; }
.analysis-scene.is-night .scene-fallback { color: #96938a; }
.analysis-scene.is-night .scene-fallback strong { color: #e8e4da; }
</style>
