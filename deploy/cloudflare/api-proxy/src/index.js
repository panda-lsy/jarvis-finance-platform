const DEFAULT_UPSTREAM = 'https://agent.shengxia.me'

export default {
  async fetch(request, env) {
    const incomingUrl = new URL(request.url)
    if (!incomingUrl.pathname.startsWith('/api/')) {
      return new Response('Not Found', { status: 404 })
    }

    const upstreamOrigin = env.API_UPSTREAM_ORIGIN || DEFAULT_UPSTREAM
    const upstreamUrl = new URL(incomingUrl.pathname + incomingUrl.search, upstreamOrigin)

    // Clone the browser request so Cookie, CSRF header, body and Origin are preserved.
    // The outer response remains f.shengxia.me, therefore host-only Set-Cookie values
    // from the Java backend become first-party cookies for the frontend host.
    const upstreamRequest = new Request(upstreamUrl, request)
    return fetch(upstreamRequest)
  },
}
