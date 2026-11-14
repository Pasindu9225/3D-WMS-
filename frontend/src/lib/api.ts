/**
 * The only way the app talks to the backend.
 * - Auth is cookie based (HttpOnly, SEC-01): this code never sees or stores a token.
 * - Every non-GET sends the XSRF-TOKEN cookie back as X-XSRF-TOKEN (SEC-11).
 * - A 401 triggers ONE shared refresh for all waiting requests, then each retries once.
 *   Two parallel refreshes would look like token theft to the server and end the session (SEC-04).
 *   Tabs share the cookies, so the refresh is also serialised across tabs with a Web Lock (F2).
 */

const BASE = '/api/v1'
const NO_REFRESH = ['/auth/login', '/auth/refresh', '/auth/logout', '/auth/invite/accept', '/auth/csrf']

export interface FieldError {
  field: string
  message: string
}

/** A problem the server found in a whole document (e.g. a layout); ref names the zone or rack. */
export interface Issue {
  severity: 'ERROR' | 'WARNING'
  code: string
  message: string
  ref: string
}

/** A ProblemDetail from the backend, or a network failure (status 0, code NETWORK). */
export class ApiError extends Error {
  readonly status: number
  readonly code: string
  readonly correlationId?: string
  readonly errors: FieldError[]
  readonly issues: Issue[]

  constructor(status: number, code: string, message: string, correlationId?: string, errors: FieldError[] = [],
    issues: Issue[] = []) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
    this.correlationId = correlationId
    this.errors = errors
    this.issues = issues
  }
}

type Method = 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE'

interface RequestOptions {
  method?: Method
  body?: unknown
}

let refreshing: Promise<boolean> | null = null
let onSessionExpired: () => void = () => {}

/** The app registers what to do when the session cannot be renewed (go to the login page). */
export function setSessionExpiredHandler(handler: () => void): void {
  onSessionExpired = handler
}

export function api<T>(path: string, options: RequestOptions = {}): Promise<T> {
  return send<T>(path, options, true)
}

async function send<T>(path: string, { method = 'GET', body }: RequestOptions, mayRetry: boolean): Promise<T> {
  const res = await rawFetch(path, method, body)
  if (res.ok) {
    return (res.status === 204 ? undefined : await res.json()) as T
  }
  const problem = await toApiError(res)
  if (mayRetry && problem.code === 'CSRF_INVALID') {
    await primeCsrf(true)
    return send<T>(path, { method, body }, false)
  }
  if (mayRetry && res.status === 401 && !NO_REFRESH.includes(path)) {
    if (await refreshOnce()) {
      return send<T>(path, { method, body }, false)
    }
    onSessionExpired()
  }
  throw problem
}

async function rawFetch(path: string, method: Method, body: unknown): Promise<Response> {
  const headers: Record<string, string> = {}
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  if (method !== 'GET') {
    await primeCsrf(false)
    headers['X-XSRF-TOKEN'] = readCookie('XSRF-TOKEN') ?? ''
  }
  try {
    return await fetch(BASE + path, {
      method,
      headers,
      credentials: 'same-origin',
      body: body === undefined ? undefined : JSON.stringify(body),
    })
  } catch {
    throw new ApiError(0, 'NETWORK', 'Network error')
  }
}

function refreshOnce(): Promise<boolean> {
  refreshing ??= acrossTabs(() => rawFetch('/auth/refresh', 'POST', undefined).then((res) => res.ok))
    .catch(() => false)
    .finally(() => {
      refreshing = null
    })
  return refreshing
}

/**
 * Runs the refresh while holding a browser-wide lock. Without it, two tabs whose shared access cookie
 * expires at the same moment would both send the same refresh token, and the second would look like a
 * stolen token and end the session. A tab that waited sends the cookie the first tab just received.
 */
function acrossTabs<T>(work: () => Promise<T>): Promise<T> {
  const locks = typeof navigator === 'undefined' ? undefined : navigator.locks
  // request() resolves with the callback's own result; its typing nests the Promise one level too deep.
  return locks ? (locks.request('wms-auth-refresh', work) as unknown as Promise<T>) : work()
}

/** Makes sure the XSRF-TOKEN cookie exists (or is renewed) before a state-changing request. */
async function primeCsrf(force: boolean): Promise<void> {
  if (!force && readCookie('XSRF-TOKEN')) return
  try {
    await fetch(BASE + '/auth/csrf', { credentials: 'same-origin' })
  } catch {
    throw new ApiError(0, 'NETWORK', 'Network error')
  }
}

async function toApiError(res: Response): Promise<ApiError> {
  try {
    const p = (await res.json()) as {
      code?: string
      detail?: string
      correlationId?: string
      errors?: FieldError[]
      issues?: Issue[]
    }
    return new ApiError(res.status, p.code ?? 'ERROR', p.detail ?? res.statusText, p.correlationId, p.errors ?? [],
      p.issues ?? [])
  } catch {
    return new ApiError(res.status, 'ERROR', res.statusText)
  }
}

function readCookie(name: string): string | undefined {
  const prefix = name + '='
  const hit = document.cookie.split('; ').find((c) => c.startsWith(prefix))
  return hit ? decodeURIComponent(hit.slice(prefix.length)) : undefined
}
