import { act, renderHook, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError, describeError, request } from '../api/client'
import { detectRole } from '../api/endpoints'
import { roleCan } from '../auth/AuthContext'
import { useJobPolling } from '../hooks/useJobPolling'
import { parseRecords } from '../pages/SourceDetailPage'
import { parseConfigLines } from '../pages/SourcesPage'
import { err, installFetch, job, roleBackend } from './fakeApi'

afterEach(() => { vi.unstubAllGlobals(); vi.useRealTimers() })

describe('API client error handling', () => {
  it('parses the backend ApiError shape', async () => {
    installFetch(() => ({ status: 422, body: { status: 422, code: 'VALIDATION', message: 'Invalid', correlationId: 'c1', violations: [{ field: 'name', message: 'bad' }] } }))
    const e = (await request('/x').catch((x) => x)) as ApiError
    expect(e).toBeInstanceOf(ApiError)
    expect(e.status).toBe(422)
    expect(describeError(e)).toBe('Invalid (name: bad)')
  })
  it('maps 401, 403 and network failure to distinct messages', async () => {
    expect(describeError(new ApiError(401, 'U', 'x'))).toMatch(/rejected \(401\)/)
    expect(describeError(new ApiError(403, 'F', 'x'))).toMatch(/not permitted to do this \(403\)/)
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('failed')))
    const e = (await request('/x').catch((x) => x)) as ApiError
    expect(e.status).toBe(0)
    expect(describeError(e)).toMatch(/Cannot reach the PIDEA API/)
  })
  it('tolerates a non-JSON error body', async () => {
    installFetch(() => ({ status: 502, body: undefined }))
    const e = (await request('/x').catch((x) => x)) as ApiError
    expect(e.status).toBe(502)
  })
})

describe('role probing and presentation rules', () => {
  it.each(['ADMIN', 'OPERATOR', 'VIEWER'] as const)('detectRole -> %s', async (r) => {
    installFetch(roleBackend(r))
    expect(await detectRole()).toBe(r)
  })
  it('propagates non-HTTP failures instead of guessing a role', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('down')))
    await expect(detectRole()).rejects.toMatchObject({ status: 0 })
  })
  it('mirrors the backend permission matrix', () => {
    expect(roleCan('VIEWER', 'job.viewRejections')).toBe(false)
    expect(roleCan('VIEWER', 'job.cancel')).toBe(false)
    expect(roleCan('OPERATOR', 'job.replay')).toBe(true)
    expect(roleCan('OPERATOR', 'source.create')).toBe(false)
    expect(roleCan('OPERATOR', 'schedule.create')).toBe(false)
    expect(roleCan('ADMIN', 'schedule.toggle')).toBe(true)
    expect(roleCan(null, 'job.submit')).toBe(false)
  })
})

describe('input parsing', () => {
  it('parseRecords accepts arrays and {records}, and enforces limits', () => {
    expect(parseRecords('[{"a":1}]')).toEqual([{ a: 1 }])
    expect(parseRecords('{"records":[{"a":1}]}')).toEqual([{ a: 1 }])
    expect(() => parseRecords('[]')).toThrow(/At least one/)
    expect(() => parseRecords('[1]')).toThrow(/object/)
    expect(() => parseRecords(JSON.stringify(Array.from({ length: 1001 }, () => ({}))))).toThrow(/At most 1000/)
  })
  it('parseConfigLines builds a flat typed map', () => {
    expect(parseConfigLines('baseUrl=https://x.test\npageSize=50\nflag=true\n')).toEqual({ baseUrl: 'https://x.test', pageSize: 50, flag: true })
    expect(() => parseConfigLines('nonsense')).toThrow(/key=value/)
  })
})

describe('useJobPolling', () => {
  it('polls until a terminal status, then stops', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    const seq = ['PENDING', 'RUNNING', 'SUCCEEDED'] as const
    let n = 0
    const f = installFetch(({ path }) => (path.endsWith(`/jobs/${job().id}`) ? { body: job({ status: seq[Math.min(n++, 2)] }) } : undefined))
    const { result } = renderHook(() => useJobPolling(job().id, 1000))
    await waitFor(() => expect(result.current.job?.status).toBe('PENDING'))
    expect(result.current.polling).toBe(true)
    await act(async () => { await vi.advanceTimersByTimeAsync(1000) })
    await waitFor(() => expect(result.current.job?.status).toBe('RUNNING'))
    await act(async () => { await vi.advanceTimersByTimeAsync(1000) })
    await waitFor(() => expect(result.current.job?.status).toBe('SUCCEEDED'))
    expect(result.current.polling).toBe(false)
    const callsAtTerminal = f.calls.length
    await act(async () => { await vi.advanceTimersByTimeAsync(10_000) })
    expect(f.calls.length).toBe(callsAtTerminal) // no further polling
  })

  it('stops permanently on 403/404 rather than hammering the API', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    const f = installFetch(() => err(404, 'NOT_FOUND', 'Job not found'))
    const { result } = renderHook(() => useJobPolling('missing-job', 500))
    await waitFor(() => expect(result.current.error).toBeTruthy())
    await act(async () => { await vi.advanceTimersByTimeAsync(5000) })
    expect(f.calls.length).toBe(1)
    expect(result.current.polling).toBe(false)
  })

  it('gives up after repeated transient failures', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    const f = installFetch(() => err(503, 'UNAVAILABLE', 'down'))
    const { result } = renderHook(() => useJobPolling('some-job', 100))
    await act(async () => { await vi.advanceTimersByTimeAsync(2000) })
    expect(f.calls.length).toBe(5)
    expect(result.current.polling).toBe(false)
  })

  it('cancels its timer on unmount', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    const f = installFetch(() => ({ body: job({ status: 'RUNNING' }) }))
    const { result, unmount } = renderHook(() => useJobPolling(job().id, 1000))
    await waitFor(() => expect(result.current.job).not.toBeNull())
    const before = f.calls.length
    unmount()
    await act(async () => { await vi.advanceTimersByTimeAsync(10_000) })
    expect(f.calls.length).toBe(before)
  })
})
