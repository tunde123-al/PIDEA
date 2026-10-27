import { render } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { vi } from 'vitest'
import { App } from '../App'
import { AuthProvider } from '../auth/AuthContext'
import { clearCredentials } from '../api/client'
import type { Job, Role, Source } from '../api/types'

export interface Call { method: string; path: string; search: URLSearchParams; headers: Headers; body: BodyInit | null | undefined }
export type Handler = (call: Call) => { status?: number; body?: unknown; headers?: Record<string, string> } | undefined
export const NIL = '00000000-0000-0000-0000-000000000000'

export const page = <T,>(items: T[]) => ({ items, page: 0, size: 20, totalItems: items.length, totalPages: items.length ? 1 : 0 })
export const err = (status: number, code: string, message: string) =>
  ({ status, body: { status, code, message, correlationId: 'cid-123', violations: null } })

export const source = (over: Partial<Source> = {}): Source => ({
  id: 's1111111-0000-0000-0000-000000000000', name: 'demo-customers', type: 'REST_PUSH', status: 'ACTIVE', config: {},
  secretConfigured: false, createdAt: '2025-01-01T00:00:00Z', updatedAt: '2025-01-01T00:00:00Z', ...over,
})

export const job = (over: Partial<Job> = {}): Job => ({
  id: 'j2222222-0000-0000-0000-000000000000', sourceId: source().id, jobType: 'RECORDS', entityType: 'CUSTOMER', status: 'PENDING',
  correlationId: null, recordsReceived: 0, recordsAccepted: 0, recordsRejected: 0, recordsDuplicate: 0, errorSummary: null,
  retryCount: 0, maxRetries: 3, submittedAt: '2025-01-01T00:00:00Z', startedAt: null, completedAt: null, durationMs: null,
  replayOf: null, scheduleId: null, scheduledFor: null, ...over,
})

/** Behaviour of the real backend's role checks that the console relies on (login + role probes). */
export function roleBackend(role: Role): Handler {
  return ({ method, path }) => {
    if (method === 'GET' && path === '/api/v1/integration-sources' ) return { body: page([source()]) }
    if (path === `/api/v1/ingestion/jobs/${NIL}/errors`) return role === 'VIEWER' ? err(403, 'FORBIDDEN', 'Access denied') : err(404, 'NOT_FOUND', 'Job not found')
    if (method === 'PATCH' && path === `/api/v1/integration-sources/${NIL}`) return role === 'ADMIN' ? err(404, 'NOT_FOUND', 'nf') : err(403, 'FORBIDDEN', 'Access denied')
    return undefined
  }
}

/** Installs a fetch stub. Handlers are tried in order; the first to return a value wins. */
export function installFetch(...handlers: Handler[]) {
  const calls: Call[] = []
  const fn = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = new URL(String(input), 'http://localhost')
    const call: Call = {
      method: init?.method ?? 'GET', path: url.pathname, search: url.searchParams,
      headers: new Headers(init?.headers), body: init?.body,
    }
    calls.push(call)
    for (const h of handlers) {
      const r = h(call)
      if (r) {
        const status = r.status ?? 200
        const text = r.body === undefined ? '' : JSON.stringify(r.body)
        return new Response(status === 204 ? null : text, { status, headers: { 'Content-Type': 'application/json', ...r.headers } })
      }
    }
    return new Response(JSON.stringify({ status: 404, code: 'NOT_FOUND', message: `unhandled ${call.method} ${call.path}` }), { status: 404 })
  })
  vi.stubGlobal('fetch', fn)
  return { calls, fn }
}

export function renderApp(route = '/') {
  clearCredentials()
  return render(
    <MemoryRouter initialEntries={[route]}>
      <AuthProvider><App /></AuthProvider>
    </MemoryRouter>,
  )
}
