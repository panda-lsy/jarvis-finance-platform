import {
  BoxGeometry,
  Color,
  CylinderGeometry,
  Group,
  Mesh,
  MeshPhysicalMaterial,
  MeshStandardMaterial,
  PlaneGeometry,
  TorusGeometry,
} from 'three'
import { RoundedBoxGeometry } from 'three/addons/geometries/RoundedBoxGeometry.js'

function shadow(mesh, cast = true, receive = true) {
  mesh.castShadow = cast
  mesh.receiveShadow = receive
  return mesh
}

export const ARCHIVE_RENDER_THEMES = Object.freeze({
  day: Object.freeze({
    body: Object.freeze({ color: '#f7f2eb', roughness: 0.36, metalness: 0.04, emissive: '#000000', emissiveIntensity: 0 }),
    inset: Object.freeze({ color: '#fcf8f2', roughness: 0.46, metalness: 0.02, emissive: '#000000', emissiveIntensity: 0 }),
    frame: Object.freeze({ color: '#c9bcad', roughness: 0.54, metalness: 0.08, emissive: '#000000', emissiveIntensity: 0 }),
    rail: Object.freeze({ color: '#d2c5b6', roughness: 0.40, metalness: 0.16 }),
    inner: Object.freeze({ color: '#666860', roughness: 0.34, metalness: 0.16 }),
    accent: Object.freeze({ color: '#92764d', roughness: 0.28, metalness: 0.34, emissive: '#000000', emissiveIntensity: 0 }),
    focusFrame: Object.freeze({ color: '#bca578', roughness: 0.42, metalness: 0.18, emissive: '#000000', emissiveIntensity: 0 }),
    focusAccent: Object.freeze({ color: '#92764d', roughness: 0.28, metalness: 0.34, emissive: '#000000', emissiveIntensity: 0 }),
    focusMetal: Object.freeze({ color: '#c9bcad', roughness: 0.48, metalness: 0.14, emissive: '#000000', emissiveIntensity: 0 }),
    labelCarrier: Object.freeze({ color: '#f8f3ec', roughness: 0.54, metalness: 0.02 }),
    glass: Object.freeze({ color: '#fffdfa', roughness: 0.21, transmission: 0.68, opacity: 0.92 }),
    decrypt: Object.freeze({ color: '#9b7d52', emissive: '#6c5434', emissiveIntensity: 0.18 }),
    gasket: Object.freeze({ color: '#4c4c47', opacity: 0.72 }),
    focusedGlass: Object.freeze({ color: '#ece9e2', roughness: 0.42, transmission: 0.36, opacity: 0.98 }),
  }),
  night: Object.freeze({
    // Midnight Atelier: ordinary archives are almost monochrome graphite.
    // Champagne metal appears only on the active file, so material hierarchy
    // carries the premium look instead of a scene-wide glow.
    body: Object.freeze({ color: '#33373a', roughness: 0.61, metalness: 0.018, emissive: '#000000', emissiveIntensity: 0 }),
    inset: Object.freeze({ color: '#292d30', roughness: 0.65, metalness: 0.012, emissive: '#000000', emissiveIntensity: 0 }),
    frame: Object.freeze({ color: '#514c45', roughness: 0.59, metalness: 0.08, emissive: '#000000', emissiveIntensity: 0 }),
    rail: Object.freeze({ color: '#474844', roughness: 0.53, metalness: 0.11 }),
    inner: Object.freeze({ color: '#707476', roughness: 0.46, metalness: 0.07 }),
    // Identity hardware only appears on the focused file. Lift it modestly so
    // focus reads immediately without raising the exposure of the archive sea.
    accent: Object.freeze({ color: '#927d5b', roughness: 0.37, metalness: 0.22, emissive: '#2b2014', emissiveIntensity: 0.055 }),
    focusFrame: Object.freeze({ color: '#c2a777', roughness: 0.33, metalness: 0.32, emissive: '#49341d', emissiveIntensity: 0.085 }),
    focusAccent: Object.freeze({ color: '#d9bd87', roughness: 0.24, metalness: 0.38, emissive: '#5a4022', emissiveIntensity: 0.145 }),
    focusMetal: Object.freeze({ color: '#9f9077', roughness: 0.36, metalness: 0.26, emissive: '#2b2218', emissiveIntensity: 0.045 }),
    labelCarrier: Object.freeze({ color: '#394045', roughness: 0.58, metalness: 0.018 }),
    glass: Object.freeze({ color: '#666c6f', roughness: 0.41, transmission: 0.21, opacity: 0.90 }),
    decrypt: Object.freeze({ color: '#c9ac78', emissive: '#4e3b24', emissiveIntensity: 0.095 }),
    gasket: Object.freeze({ color: '#111416', opacity: 0.74 }),
    focusedGlass: Object.freeze({ color: '#454e53', roughness: 0.41, transmission: 0.20, opacity: 0.975 }),
  }),
})

function applyMaterialTheme(material, values) {
  if (!material || !values) return
  if (values.color && material.color) material.color.set(values.color)
  if (values.emissive && material.emissive) material.emissive.set(values.emissive)
  for (const key of ['roughness', 'metalness', 'transmission', 'opacity', 'emissiveIntensity']) {
    if (Number.isFinite(values[key]) && key in material) material[key] = values[key]
  }
}

export function applyArchiveTheme(library, mode = 'day', focusedGlassMaterial = null) {
  if (!library?.materials) return
  const theme = ARCHIVE_RENDER_THEMES[mode] || ARCHIVE_RENDER_THEMES.day
  for (const key of ['body', 'inset', 'frame', 'rail', 'inner', 'accent', 'focusFrame', 'focusAccent', 'focusMetal', 'labelCarrier', 'glass', 'decrypt', 'gasket']) {
    applyMaterialTheme(library.materials[key], theme[key])
  }
  applyMaterialTheme(focusedGlassMaterial, theme.focusedGlass)
}

export function createArchiveAssetLibrary() {
  const geometries = {
    body: new RoundedBoxGeometry(5.00, 3.70, 0.31, 3, 0.075),
    inset: new RoundedBoxGeometry(4.72, 3.42, 0.06, 3, 0.065),
    // Keep the transmissive pane inside the warm frame. The old pane overlapped
    // the frame by a few hundredths of a world unit; at the very long browse
    // camera distance that overlap produced unstable sub-pixel coverage on the
    // gold edge while the archive field moved.
    glass: new RoundedBoxGeometry(4.46, 3.18, 0.05, 3, 0.07),
    sideBar: new BoxGeometry(0.10, 3.34, 0.075),
    topBar: new BoxGeometry(4.56, 0.10, 0.075),
    rail: new BoxGeometry(0.07, 2.40, 0.04),
    bridge: new BoxGeometry(0.05, 1.95, 0.05),
    latch: new RoundedBoxGeometry(0.78, 0.16, 0.08, 2, 0.032),
    marker: new RoundedBoxGeometry(0.14, 0.44, 0.07, 2, 0.022),
    labelCarrier: new RoundedBoxGeometry(4.28, 0.70, 0.04, 2, 0.032),
    label: new PlaneGeometry(4.10, 0.56),
    ring: new TorusGeometry(0.43, 0.03, 8, 40),
    fastener: new CylinderGeometry(0.055, 0.055, 0.04, 12),
    decrypt: new BoxGeometry(1.16, 0.022, 0.018),
    glassSide: new BoxGeometry(0.03, 3.10, 0.022),
    glassTop: new BoxGeometry(4.42, 0.03, 0.022),
  }

  const materials = {
    body: new MeshStandardMaterial({ color: new Color('#f7f2eb'), roughness: 0.36, metalness: 0.04 }),
    inset: new MeshStandardMaterial({ color: new Color('#fcf8f2'), roughness: 0.46, metalness: 0.02 }),
    frame: new MeshStandardMaterial({
      color: new Color('#c9bcad'),
      roughness: 0.54,
      metalness: 0.08,
    }),
    rail: new MeshStandardMaterial({ color: new Color('#d2c5b6'), roughness: 0.4, metalness: 0.16 }),
    inner: new MeshStandardMaterial({ color: new Color('#666860'), roughness: 0.34, metalness: 0.16 }),
    accent: new MeshStandardMaterial({ color: new Color('#92764d'), roughness: 0.28, metalness: 0.34 }),
    focusFrame: new MeshStandardMaterial({ color: new Color('#bca578'), roughness: 0.42, metalness: 0.18 }),
    focusAccent: new MeshStandardMaterial({ color: new Color('#92764d'), roughness: 0.28, metalness: 0.34 }),
    focusMetal: new MeshStandardMaterial({ color: new Color('#c9bcad'), roughness: 0.48, metalness: 0.14 }),
    labelCarrier: new MeshStandardMaterial({ color: new Color('#f8f3ec'), roughness: 0.54, metalness: 0.02 }),
    glass: new MeshPhysicalMaterial({
      color: new Color('#fffdfa'),
      roughness: 0.21,
      metalness: 0,
      transmission: 0.68,
      thickness: 0.10,
      ior: 1.46,
      transparent: true,
      opacity: 0.92,
      depthWrite: false,
    }),
    decrypt: new MeshStandardMaterial({
      color: new Color('#9b7d52'),
      emissive: new Color('#6c5434'),
      emissiveIntensity: 0.18,
      roughness: 0.32,
      metalness: 0.2,
      transparent: true,
      opacity: 0.72,
    }),
    gasket: new MeshStandardMaterial({
      color: new Color('#4c4c47'),
      roughness: 0.42,
      metalness: 0.18,
      transparent: true,
      opacity: 0.72,
    }),
  }

  return {
    geometries,
    materials,
    dispose() {
      Object.values(geometries).forEach(item => item?.dispose?.())
      Object.values(materials).forEach(item => item?.dispose?.())
    },
  }
}

export function createFocusedGlassMaterial(library) {
  return new MeshPhysicalMaterial({
    color: new Color('#ece9e2'),
    roughness: 0.42,
    metalness: 0,
    transmission: 0.36,
    thickness: 0.13,
    ior: 1.46,
    transparent: true,
    opacity: 0.98,
    depthWrite: false,
  })
}

export function createArchiveAssembly(module, labelMaterial, library) {
  const { geometries: g, materials: m } = library
  const group = new Group()
  const baseGroup = new Group()
  const identityGroup = new Group()
  const frameGroup = new Group()
  const nearGroup = new Group()
  const focusGroup = new Group()

  const body = shadow(new Mesh(g.body, m.body), false, true)
  body.userData.moduleKey = module.key
  const inset = shadow(new Mesh(g.inset, m.inset), false, true)
  inset.position.z = 0.205
  const marker = shadow(new Mesh(g.marker, m.accent), false, true)
  marker.position.set(2.12, -1.26, 0.245)
  const archiveMark = shadow(new Mesh(g.marker, m.inner), false, true)
  archiveMark.scale.set(0.70, 0.58, 0.70)
  archiveMark.position.set(2.16, -0.72, 0.37)
  const labelCarrier = shadow(new Mesh(g.labelCarrier, m.labelCarrier), false, true)
  labelCarrier.position.set(0, 1.18, 0.275)
  const label = new Mesh(g.label, labelMaterial)
  label.position.set(0, 1.18, 0.305)
  label.userData.moduleKey = module.key
  baseGroup.add(body, inset, archiveMark)
  identityGroup.add(marker, labelCarrier, label)

  const left = shadow(new Mesh(g.sideBar, m.frame), false, true)
  const right = shadow(new Mesh(g.sideBar, m.frame), false, true)
  const top = shadow(new Mesh(g.topBar, m.frame), false, true)
  const bottom = shadow(new Mesh(g.topBar, m.frame), false, true)
  // Keep the warm frame physically in front of the inset instead of relying on
  // polygon offset. The old z=0.245 placement made the 0.075-deep bar overlap
  // the inset volume (front face z≈0.235). At the long browse-camera distance
  // that interpenetration rasterized as the regular triangular/saw-tooth edge
  // visible in motion. z=0.285 leaves a real depth gap behind the frame.
  left.position.set(-2.30, 0, 0.285)
  right.position.set(2.30, 0, 0.285)
  top.position.set(0, 1.65, 0.285)
  bottom.position.set(0, -1.65, 0.285)

  const railL = shadow(new Mesh(g.rail, m.rail), false, true)
  const railR = shadow(new Mesh(g.rail, m.rail), false, true)
  // The rails had the same shallow overlap with the inset. Keep them behind
  // the glass but entirely in front of the recessed face as well.
  railL.position.set(-1.06, -0.18, 0.275)
  railR.position.set(1.06, -0.18, 0.275)

  const ringTop = shadow(new Mesh(g.ring, m.inner), false, true)
  const ringBottom = shadow(new Mesh(g.ring, m.inner), false, true)
  ringTop.position.set(0, 0.36, 0.278)
  ringBottom.position.set(0, -0.48, 0.278)

  const glass = new Mesh(g.glass, m.glass)
  glass.position.z = 0.33
  glass.renderOrder = 2
  // The transmissive pane creates a dark grey/green bevel at this extreme
  // long-lens angle. When a newly selected card lifts, that refractive edge
  // changes sub-pixel coverage and appears to shake into place like a refresh.
  // Keep the pane out of the browse/focus/extraction transition; the frame,
  // rails and label still provide the intended layered archive construction.
  glass.visible = false
  // Keep the warm outer frame on its own visibility layer. Rails/rings/glass
  // may still follow the original near-detail budget, but the gold frame no
  // longer has to blink with those heavier details during wheel motion.
  frameGroup.add(left, right, top, bottom)
  nearGroup.add(railL, railR, ringTop, ringBottom, glass)

  const bridge = shadow(new Mesh(g.bridge, m.focusMetal), false, true)
  bridge.position.set(0, -0.06, 0.292)
  const latch = shadow(new Mesh(g.latch, m.focusAccent), false, true)
  latch.position.set(0, 1.48, 0.318)

  const fasteners = [
    [-2.12, 1.50], [2.12, 1.50], [-2.12, -1.50], [2.12, -1.50],
  ].map(([x, y]) => {
    const bolt = shadow(new Mesh(g.fastener, m.focusMetal), false, true)
    bolt.rotation.x = Math.PI / 2
    bolt.position.set(x, y, 0.342)
    return bolt
  })

  const glassEdges = [
    new Mesh(g.glassSide, m.gasket), new Mesh(g.glassSide, m.gasket),
    new Mesh(g.glassTop, m.gasket), new Mesh(g.glassTop, m.gasket),
  ]
  glassEdges.forEach(edge => {
    // These dark gasket strips are too high-contrast at the archive camera's
    // long focal length. Making them appear during extraction creates the
    // grey-green edge "flash" reported on the selected card, so keep them out
    // of the visible focus transition while retaining the geometry for future
    // close-up use.
    edge.userData.archiveGlassEdge = true
    edge.visible = false
  })
  glassEdges[0].position.set(-2.24, 0, 0.365)
  glassEdges[1].position.set(2.24, 0, 0.365)
  glassEdges[2].position.set(0, 1.58, 0.365)
  glassEdges[3].position.set(0, -1.58, 0.365)

  const decryptA = new Mesh(g.decrypt, m.decrypt)
  const decryptB = new Mesh(g.decrypt, m.decrypt)
  decryptA.position.set(-1.10, 0.08, 0.415)
  decryptB.position.set(1.10, 0.08, 0.415)
  decryptA.rotation.z = 0.22
  decryptB.rotation.z = -0.22
  decryptA.visible = false
  decryptB.visible = false

  focusGroup.add(bridge, latch, ...fasteners, ...glassEdges, decryptA, decryptB)
  frameGroup.visible = false
  nearGroup.visible = false
  focusGroup.visible = false
  group.add(baseGroup, identityGroup, frameGroup, nearGroup, focusGroup)

  return {
    group,
    body,
    marker,
    labelCarrier,
    label,
    baseGroup,
    identityGroup,
    frameGroup,
    nearGroup,
    focusGroup,
    glass,
    decryptA,
    decryptB,
    hitTargets: [body, label],
  }
}

