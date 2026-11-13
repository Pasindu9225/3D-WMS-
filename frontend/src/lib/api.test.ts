import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { api, ApiError, setSessionExpiredHandler } from './api'

type Handler = (url: string, init: RequestInit) => Response | Promise<Response>

function json(status: number, body?: unknown): Response {
  return new Response(body === undefined ? null : JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

let calls: { url: string; init: RequestInit }[]

function mockFetch(handler: Handler): void {
  calls = []
  vi.stubGlobal('fetch', vi.fn(async (url: string, init: RequestInit = {}) => {
    calls.push({ url, init })
    return handler(url, init)
  }))
}

beforeEach(() => {
  document.cookie = 'XSRF-TOKEN=tok-1'
})

afterEach(() => {
  vi.unstubAllGlobals()
  setSessionExpiredHandler(() => {})
})

describe('api', () => {
  it('sends JSON and the CSRF header on writes, nothing extra on reads', async () => {
    mockFetch(() => json(200, { ok: true }))
    await api('/users', { method: 'POST', body: { a: 1 } })
    await api('/users')
    const [write, read] = calls
    expect(write?.init.headers).toEqual({ 'Content-Type': 'application/json', 'X-XSRF-TOKEN': 'tok-1' })
    expect(write?.init.body).toBe('{"a":1}')
    expect(write?.init.credentials).toBe('same-origin')
    expect(read?.init.headers).toEqual({})
  })

  it('returns undefined for 204', async () => {
    mockFetch(() => new Response(null, { status: 204 }))
    await expect(api('/auth/logout', { method: 'POST' })).resolves.toBeUndefined()
  })

  it('refreshes once for many parallel 401s, then retries each request once', async () => {
    let refreshed = false
    mockFetch((url) => {
      if (url.endsWith('/auth/refresh')) {
        refreshed = true
        return json(200, {})
      }
      return refreshed ? json(200, { url }) : json(401, { code: 'UNAUTHENTICATED' })
    })
    const results = await Promise.all([api('/users'), api('/roles'), api('/auth/me')])
    expect(results).toHaveLength(3)
    expect(calls.filter((c) => c.url.endsWith('/auth/refresh'))).toHaveLength(1)
  })

  it('ends the session when refresh fails', async () => {
    const expired = vi.fn()
    setSessionExpiredHandler(expired)
    mockFetch((url) => (url.endsWith('/auth/refresh') ? json(401, { code: 'AUTH_FAILED' }) : json(401, { code: 'UNAUTHENTICATED' })))
    await expect(api('/users')).rejects.toMatchObject({ status: 401, code: 'UNAUTHENTICATED' })
    expect(expired).toHaveBeenCalledOnce()
  })

  it('never refreshes for the login call itself', async () => {
    mockFetch(() => json(401, { code: 'AUTH_FAILED', detail: 'Authentication failed' }))
    const err = await api('/auth/login', { method: 'POST', body: {} }).catch((e: unknown) => e)
    expect(err).toBeInstanceOf(ApiError)
    expect((err as ApiError).message).toBe('Authentication failed')
    expect(calls.some((c) => c.url.endsWith('/auth/refresh'))).toBe(false)
  })

  it('gets a fresh CSRF token and retries once on CSRF_INVALID', async () => {
    let csrfFetched = false
    mockFetch((url) => {
      if (url.endsWith('/auth/csrf')) {
        csrfFetched = true
        document.cookie = 'XSRF-TOKEN=tok-2'
        return new Response(null, { status: 204 })
      }
      return csrfFetched ? json(200, {}) : json(403, { code: 'CSRF_INVALID' })
    })
    await api('/users', { method: 'POST', body: {} })
    expect(calls.at(-1)?.init.headers).toMatchObject({ 'X-XSRF-TOKEN': 'tok-2' })
  })

  it('keeps validation errors for the form', async () => {
    mockFetch(() => json(400, { code: 'VALIDATION_FAILED', detail: 'Invalid', errors: [{ field: 'email', message: 'must be a well-formed email address' }] }))
    const err = (await api('/users', { method: 'POST', body: {} }).catch((e: unknown) => e)) as ApiError
    expect(err.errors[0]?.field).toBe('email')
  })

  it('turns a network failure into code NETWORK', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => {
      throw new TypeError('Failed to fetch')
    }))
    await expect(api('/users')).rejects.toMatchObject({ status: 0, code: 'NETWORK' })
  })
})
