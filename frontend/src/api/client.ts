import type { ApiErrorBody } from './types'

/**
 * Low-level HTTP client. The Basic credential lives only in this module's memory:
 * it is never written to localStorage/sessionStorage/cookies and is lost on reload by design.
 */
let authHeader: string | null = null
let unauthorizedHandler: (() => void) | null = null

export function setCredentials(username: string, password: string): void {
  // btoa only handles Latin-1, so encode as UTF-8 bytes first.
  const bytes = new TextEncoder().encode(`${username}:${password}`)
  let bin = ''
  bytes.forEach((b) => (bin += String.fromCharCode(b)))
  authHeader = `Basic ${btoa(bin)}`
}

export function clearCredentials(): void {
  authHeader = null
}

/** Called when an authenticated request receives 401 (credentials rejected or revoked). */
export function onUnauthorized(handler: (() => void) | null): void {
  unauthorizedHandler = handler
}

export class ApiError extends Error {
  readonly status: number
  readonly code: string
  readonly correlationId?: string
  readonly violations: { field: string; message: string }[]

  constructor(status: number, code: string, message: string, correlationId?: string,
              violations: { field: string; message: string }[] = []) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
    this.correlationId = correlationId
    this.violations = violations
  }
}

/** Turns any thrown value into a message that is safe and useful to show the user. */
export function describeError(e: unknown): string {
  if (e instanceof ApiError) {
    if (e.status === 401) return 'Your credentials were rejected (401). Sign in again.'
    if (e.status === 403) return 'Your role is not permitted to do this (403). The server enforced this restriction.'
    if (e.status === 0) return e.message
    const v = e.violations.length ? ` (${e.violations.map((x) => `${x.field}: ${x.message}`).join('; ')})` : ''
    return `${e.message}${v}`
  }
  return e instanceof Error ? e.message : 'Unexpected error'
}

export interface RequestOptions {
  method?: 'GET' | 'POST' | 'PATCH'
  query?: Record<string, string | number | undefined | null>
  json?: unknown
  form?: FormData
  headers?: Record<string, string>
  signal?: AbortSignal
}

export interface RawResponse<T> {
  data: T
  status: number
  headers: Headers
}

export async function requestRaw<T>(path: string, opts: RequestOptions = {}): Promise<RawResponse<T>> {
  const url = new URL(path, window.location.origin)
  for (const [k, v] of Object.entries(opts.query ?? {})) {
    if (v !== undefined && v !== null && v !== '') url.searchParams.set(k, String(v))
  }
  const headers: Record<string, string> = { Accept: 'application/json', ...opts.headers }
  if (authHeader) headers.Authorization = authHeader
  let body: BodyInit | undefined
  if (opts.json !== undefined) {
    headers['Content-Type'] = 'application/json'
    body = JSON.stringify(opts.json)
  } else if (opts.form) {
    body = opts.form // the browser sets the multipart boundary
  }

  let res: Response
  try {
    res = await fetch(url.pathname + url.search, { method: opts.method ?? 'GET', headers, body, signal: opts.signal })
  } catch (e) {
    if (e instanceof DOMException && e.name === 'AbortError') throw e
    throw new ApiError(0, 'NETWORK',
      'Cannot reach the PIDEA API. Check that the backend is running and the proxy target is correct.')
  }

  if (!res.ok) {
    let parsed: Partial<ApiErrorBody> = {}
    try { parsed = await res.json() } catch { /* non-JSON error body */ }
    const err = new ApiError(
      res.status,
      parsed.code ?? `HTTP_${res.status}`,
      parsed.message ?? (res.statusText || `Request failed with status ${res.status}`),
      parsed.correlationId,
      parsed.violations ?? [],
    )
    if (res.status === 401 && authHeader) unauthorizedHandler?.()
    throw err
  }

  const text = await res.text()
  return { data: (text ? JSON.parse(text) : undefined) as T, status: res.status, headers: res.headers }
}

export async function request<T>(path: string, opts: RequestOptions = {}): Promise<T> {
  return (await requestRaw<T>(path, opts)).data
}
