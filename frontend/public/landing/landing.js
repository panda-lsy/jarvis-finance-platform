const io = new IntersectionObserver((entries) => {
  entries.forEach((entry) => { if (entry.isIntersecting) entry.target.classList.add('visible') })
}, { threshold: .13 })
document.querySelectorAll('.reveal').forEach(el => io.observe(el))

// Activate each visual language only when its own module enters view.
const motionReduced = window.matchMedia('(prefers-reduced-motion: reduce)').matches
const fxPanels = document.querySelectorAll('[data-fx-panel]')
const marketIntervals = []

const animateConfidence = (panel) => {
  const value = panel.querySelector('.confidence-value')
  if (!value || value.dataset.done) return
  value.dataset.done = '1'
  const target = Number(value.dataset.countTo || 82)
  const duration = 920
  const start = performance.now()
  const ease = (t) => 1 - Math.pow(1 - t, 3)
  const step = (now) => {
    const p = Math.min(1, (now - start) / duration)
    value.textContent = String(Math.round(target * ease(p)))
    if (p < 1) requestAnimationFrame(step)
  }
  requestAnimationFrame(step)
}

const startMarketTicks = (panel) => {
  if (panel.dataset.ticksStarted || motionReduced) return
  panel.dataset.ticksStarted = '1'
  panel.querySelectorAll('.market-card').forEach((card, index) => {
    const pulse = () => {
      card.classList.remove('quote-tick')
      void card.offsetWidth
      card.classList.add('quote-tick')
      window.setTimeout(() => card.classList.remove('quote-tick'), 620)
    }
    const initial = window.setTimeout(() => {
      pulse()
      const interval = window.setInterval(pulse, 4300 + index * 760)
      marketIntervals.push(interval)
    }, 1600 + index * 560)
    marketIntervals.push(initial)
  })
}

const fxObserver = new IntersectionObserver((entries) => {
  entries.forEach((entry) => {
    if (!entry.isIntersecting) return
    const panel = entry.target
    panel.classList.add('fx-active')
    if (panel.dataset.fxPanel === 'ai') animateConfidence(panel)
    if (panel.dataset.fxPanel === 'market') startMarketTicks(panel)
    fxObserver.unobserve(panel)
  })
}, { threshold: .28 })
fxPanels.forEach((panel) => fxObserver.observe(panel))

// Backtest: a quiet inspection cursor follows the actual SVG path.
const equity = document.querySelector('[data-equity-inspector]')
if (equity && !motionReduced) {
  const svg = equity.querySelector('.eq-svg')
  const path = equity.querySelector('.eq-line')
  const cursor = equity.querySelector('.eq-cursor-line')
  const dot = equity.querySelector('.eq-cursor-dot')
  if (svg && path && cursor && dot) {
    const findPointForX = (targetX) => {
      let lo = 0, hi = path.getTotalLength()
      for (let i = 0; i < 18; i++) {
        const mid = (lo + hi) / 2
        const point = path.getPointAtLength(mid)
        if (point.x < targetX) lo = mid
        else hi = mid
      }
      return path.getPointAtLength((lo + hi) / 2)
    }
    svg.addEventListener('pointerenter', () => equity.classList.add('is-inspecting'))
    svg.addEventListener('pointerleave', () => equity.classList.remove('is-inspecting'))
    svg.addEventListener('pointermove', (event) => {
      const rect = svg.getBoundingClientRect()
      const x = Math.max(0, Math.min(900, ((event.clientX - rect.left) / rect.width) * 900))
      const point = findPointForX(x)
      cursor.setAttribute('x1', point.x.toFixed(2))
      cursor.setAttribute('x2', point.x.toFixed(2))
      dot.setAttribute('cx', point.x.toFixed(2))
      dot.setAttribute('cy', point.y.toFixed(2))
    })
  }
}

// Connected workspace: focus only the route that belongs to the hovered node.
const workspaceFx = document.querySelector('[data-fx-panel="workspace"]')
if (workspaceFx) {
  workspaceFx.querySelectorAll('[data-node]').forEach((node) => {
    const key = node.dataset.node
    node.addEventListener('pointerenter', () => {
      workspaceFx.classList.add('has-focus', `focus-${key}`)
    })
    node.addEventListener('pointerleave', () => {
      workspaceFx.classList.remove('has-focus', `focus-${key}`)
    })
  })
}

// Load the hero simulated-click demo after shared runtime state exists.
if (!motionReduced) {
  const demoScript = document.createElement('script')
  demoScript.src = './landing-demo.js'
  demoScript.defer = true
  document.body.appendChild(demoScript)
}
