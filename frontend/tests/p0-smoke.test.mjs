import test from 'node:test'
import assert from 'node:assert/strict'
import { readdir, readFile } from 'node:fs/promises'
import { extname, join, relative } from 'node:path'
import { fileURLToPath } from 'node:url'

const frontendRoot = fileURLToPath(new URL('..', import.meta.url))

globalThis.window = { location: { hostname: 'localhost' } }

class FakeEventSource {
  static instances = []

  constructor(url, options) {
    this.url = url
    this.options = options
    this.listeners = new Map()
    this.closed = false
    this.onerror = null
    FakeEventSource.instances.push(this)
  }

  addEventListener(name, callback) {
    this.listeners.set(name, callback)
  }

  emit(name, data) {
    this.listeners.get(name)?.({ data })
  }

  close() {
    this.closed = true
  }
}

globalThis.EventSource = FakeEventSource

const { API_BASE, api } = await import('../src/api/client.js')
const { useAuthSession } = await import('../src/composables/useAuthSession.js')
const {
  hasMarketPreferences,
  marketPreferencesKey,
  normalizeMarketPreferences,
  readMarketPreferences,
  writeMarketPreferences,
} = await import('../src/utils/marketPreferences.js')

test('local development keeps browser on the Java/Vite same-origin API', () => {
  assert.equal(API_BASE, '')
})

test('market price stream parses JSON events and closes cleanly', () => {
  let received = null
  let errors = 0
  const close = api.marketPriceStream(
    payload => { received = payload },
    () => { errors += 1 },
  )

  const source = FakeEventSource.instances.at(-1)
  assert.ok(source)
  assert.equal(source.url, '/api/market/prices/stream')
  assert.deepEqual(source.options, { withCredentials: true })

  const payload = {
    market: { gold_etf: { price: 7.88 } },
    jd: { zheshang: { price: 812.3 } },
    server_time: '2026-09-08T10:00:00Z',
  }
  source.emit('prices', JSON.stringify(payload))
  assert.deepEqual(received, payload)

  source.onerror?.(new Error('network'))
  assert.equal(errors, 1)

  close()
  assert.equal(source.closed, true)
})

test('authenticated writes refresh CSRF after a 401/403 and retry once', async () => {
  const calls = []
  let preferenceWrites = 0
  const previousFetch = globalThis.fetch
  globalThis.fetch = async (url, options = {}) => {
    calls.push({ url: String(url), method: options.method || 'GET', token: options.headers?.['X-XSRF-TOKEN'] || '' })
    if (String(url).endsWith('/api/auth/csrf')) {
      return { ok: true, status: 200, json: async () => ({ code: 200, data: { token: `csrf-${calls.length}` } }) }
    }
    if (String(url).endsWith('/api/market/preferences')) {
      preferenceWrites += 1
      if (preferenceWrites === 2) {
        return { ok: false, status: 401, json: async () => ({ code: 401, message: '未登录或登录已过期' }) }
      }
      return { ok: true, status: 200, json: async () => ({ code: 200, data: { persisted: true } }) }
    }
    throw new Error(`unexpected request: ${url}`)
  }

  try {
    const body = { watchlist: [], hiddenDefaultKeys: [] }
    await api.saveMarketPreferences(body)
    await api.saveMarketPreferences(body)
  } finally {
    globalThis.fetch = previousFetch
  }

  assert.deepEqual(calls.map(call => call.method), ['GET', 'PUT', 'PUT', 'GET', 'PUT'])
  assert.equal(calls[1].token, 'csrf-1')
  assert.equal(calls[2].token, 'csrf-1')
  assert.equal(calls[3].url, '/api/auth/csrf')
  assert.equal(calls[4].token, 'csrf-4')
})

test('late session restore cannot overwrite a successful login', async () => {
  const previousMe = api.me
  let resolveMe
  api.me = () => new Promise((resolve) => { resolveMe = resolve })

  try {
    const session = useAuthSession()
    const restorePromise = session.restore()
    const loggedInUser = { id: 7, email: 'user@example.com' }
    session.acceptLogin(loggedInUser)
    resolveMe({ code: 401, data: null })
    await restorePromise
    assert.deepEqual(session.user.value, loggedInUser)
  } finally {
    api.me = previousMe
  }
})

test('market preferences persist per user and discard malformed entries', () => {
  let value = null
  const storage = {
    getItem: () => value,
    setItem: (_key, nextValue) => { value = nextValue },
  }
  const key = marketPreferencesKey({ id: 23 })
  assert.equal(writeMarketPreferences(storage, key, {
    watchlist: [
      { market: 'us_stock', symbol: 'AAPL', name: 'Apple', currency: 'USD', source: 'Yahoo Finance' },
      { market: 'us_stock', symbol: 'AAPL', name: 'duplicate' },
      { market: 'us_stock', symbol: 'https://bad.example', name: 'invalid' },
    ],
    hiddenDefaultKeys: ['a_share:sh600519', 'not-a-market-key'],
  }), true)
  assert.deepEqual(readMarketPreferences(storage, key), {
    watchlist: [{ market: 'us_stock', symbol: 'AAPL', name: 'Apple', currency: 'USD', source: 'Yahoo Finance' }],
    hiddenDefaultKeys: ['a_share:sh600519'],
  })
})

test('server market preferences normalize and distinguish an empty saved state', () => {
  const normalized = normalizeMarketPreferences({
    watchlist: [{ market: 'crypto', symbol: 'BTCUSDT', name: 'Bitcoin' }],
    hiddenDefaultKeys: ['crypto:BTCUSDT'],
    persisted: true,
  })
  assert.equal(hasMarketPreferences(normalized), true)
  assert.deepEqual(normalized.watchlist[0], {
    market: 'crypto', symbol: 'BTCUSDT', name: 'Bitcoin', currency: '', source: '',
  })
  assert.deepEqual(normalizeMarketPreferences({ watchlist: [], hiddenDefaultKeys: [] }), {
    watchlist: [], hiddenDefaultKeys: [],
  })
  assert.equal(hasMarketPreferences({ watchlist: [], hiddenDefaultKeys: [] }), false)
})

test('cross-market view persists through the authenticated backend and exposes edit/delete actions', async () => {
  const source = await readFile(join(frontendRoot, 'src/components/CrossMarketView.vue'), 'utf8')
  const listSource = await readFile(join(frontendRoot, 'src/components/market/InstrumentList.vue'), 'utf8')
  assert.match(source, /api\.marketPreferences\(\)/)
  assert.match(source, /api\.saveMarketPreferences\(/)
  assert.match(source, /function saveEditedInstrument\(/)
  assert.match(source, /resetSelectedData\(\)/)
  assert.match(listSource, /edit-watchlist/)
  assert.match(listSource, /修改自选标的/)
})

async function sourceFiles(dir) {
  const entries = await readdir(dir, { withFileTypes: true })
  const output = []
  for (const entry of entries) {
    const path = join(dir, entry.name)
    if (entry.isDirectory()) output.push(...await sourceFiles(path))
    else if (['.js', '.vue'].includes(extname(entry.name))) output.push(path)
  }
  return output
}

test('frontend source cannot bypass the Java security boundary', async () => {
  const forbidden = [
    ['direct Python port', /(?:localhost|127\.0\.0\.1):8100/i],
    ['public Python route', /["'`]\/py(?:\/|["'`])/i],
    ['internal service token header', /X-Internal-Service-Token/i],
    ['Python service secret', /PYTHON_SERVICE_TOKEN/i],
    ['AI provider secret', /(?:AI_API_KEY|DEEPSEEK_API_KEY|OLLAMA_API_KEY)/i],
  ]

  const violations = []
  for (const file of await sourceFiles(join(frontendRoot, 'src'))) {
    const text = await readFile(file, 'utf8')
    for (const [label, pattern] of forbidden) {
      if (pattern.test(text)) violations.push(`${relative(frontendRoot, file)}: ${label}`)
    }
  }
  assert.deepEqual(violations, [])
})

test('public homepage keeps auth callbacks and animation lifecycle safe', async () => {
  const appSource = await readFile(join(frontendRoot, 'src/App.vue'), 'utf8')
  const landingSource = await readFile(join(frontendRoot, 'src/pages/LandingPage.vue'), 'utf8')
  const sessionSource = await readFile(join(frontendRoot, 'src/composables/useAuthSession.js'), 'utf8')

  assert.match(appSource, /params\.has\('oauth'\)/)
  assert.match(appSource, /history\.replaceState\(/)
  assert.match(appSource, /@login="showLogin"/)
  assert.match(landingSource, /src="\/landing\/index\.html"/)
  assert.match(landingSource, /textContent\?\.includes\('进入 JARVIS'\)/)
  assert.match(landingSource, /emit\('login'\)/)
  assert.doesNotMatch(landingSource, /observe\(frame\.value\)/)
  assert.doesNotMatch(landingSource, /const timers = new Set\(\)/)
  assert.match(landingSource, /onBeforeUnmount\(/)
  const landingDocument = await readFile(join(frontendRoot, 'public/landing/index.html'), 'utf8')
  assert.match(landingDocument, /const createObserver = \(callback, options\)/)
  assert.match(landingDocument, /revealHeroFallback/)
  assert.match(landingDocument, /addEventListener\('error', revealHeroFallback\)/)
  assert.match(landingDocument, /playback\.catch\(revealHeroFallback\)/)
  const viteConfig = await readFile(join(frontendRoot, 'vite.config.js'), 'utf8')
  assert.match(viteConfig, /modulePreload:\s*\{[\s\S]*polyfill:\s*false/)
  assert.match(sessionSource, /restoreRequestId/)
  assert.match(sessionSource, /requestId !== restoreRequestId/)
})
