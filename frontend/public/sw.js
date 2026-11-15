// Minimal service worker for the installable worker app (hand-written, no plugin; decision 2026-11-14).
// - Never touches /api or /ws: stock data is always live, never served from a cache.
// - Hashed build files (/assets/*) and icons: cache first; they never change under the same name.
// - Pages: network first, so a deploy shows at once; the last good page is kept for a flaky warehouse Wi-Fi.
const CACHE = 'wms-shell-v1'

self.addEventListener('install', () => self.skipWaiting())

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k))))
      .then(() => self.clients.claim()),
  )
})

self.addEventListener('fetch', (event) => {
  const request = event.request
  const url = new URL(request.url)
  if (request.method !== 'GET' || url.origin !== self.location.origin) return
  if (url.pathname.startsWith('/api/') || url.pathname.startsWith('/ws')) return

  if (url.pathname.startsWith('/assets/') || url.pathname.startsWith('/icons/')) {
    event.respondWith(
      caches.match(request).then((hit) => hit || fetch(request).then((res) => {
        if (res.ok) {
          const copy = res.clone()
          caches.open(CACHE).then((c) => c.put(request, copy))
        }
        return res
      })),
    )
    return
  }

  if (request.mode === 'navigate') {
    event.respondWith(
      fetch(request).then((res) => {
        if (res.ok) {
          const copy = res.clone()
          caches.open(CACHE).then((c) => c.put('/', copy)) // every route serves the same index.html
        }
        return res
      }).catch(() => caches.match('/').then((hit) => hit || Response.error())),
    )
  }
})
