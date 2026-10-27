import { request, requestRaw, ApiError } from './client'
import type {
  CreateSourceRequest, EntityType, Job, JobAttempt, JobFilters, PageResponse, QuarantineFilters, QuarantinedRecord,
  ReasonCount, RejectedRecord, Role,
  Schedule, Source, SourceHealth, SourceStatus, Submission,
} from './types'

const ING = '/api/v1/ingestion'
const SRC = '/api/v1/integration-sources'
const SCH = '/api/v1/integration-schedules'
type Sig = { signal?: AbortSignal }

export function newIdempotencyKey(): string {
  // The backend accepts 8-100 chars of A-Z a-z 0-9 . _ : -
  if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) return crypto.randomUUID()
  return `k-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 12)}`
}

async function submission(path: string, opts: Parameters<typeof requestRaw>[1]): Promise<Submission> {
  const r = await requestRaw<Job>(path, opts)
  return { job: r.data, replayed: r.headers.get('Idempotent-Replayed') === 'true' }
}

export const api = {
  // --- sources ---
  listSources: (page = 0, size = 100, o: Sig = {}) =>
    request<PageResponse<Source>>(SRC, { query: { page, size }, ...o }),
  getSource: (id: string, o: Sig = {}) => request<Source>(`${SRC}/${id}`, o),
  createSource: (body: CreateSourceRequest) => request<Source>(SRC, { method: 'POST', json: body }),
  setSourceStatus: (id: string, status: SourceStatus) =>
    request<Source>(`${SRC}/${id}/status`, { method: 'PATCH', json: { status } }),
  sourceHealth: (id: string, o: Sig = {}) => request<SourceHealth>(`${SRC}/${id}/health`, o),

  // --- ingestion ---
  submitRecords: (sourceId: string, entityType: EntityType, records: Record<string, unknown>[], key: string) =>
    submission(`${ING}/jobs`, {
      method: 'POST', json: { sourceId, entityType, records }, headers: { 'Idempotency-Key': key },
    }),
  uploadFile: (sourceId: string, entityType: EntityType, file: File, key: string) => {
    const form = new FormData()
    form.set('file', file)
    return submission(`${ING}/jobs/files`, {
      method: 'POST', query: { sourceId, entityType }, form, headers: { 'Idempotency-Key': key },
    })
  },
  triggerPull: (sourceId: string, entityType: EntityType, key: string) =>
    submission(`${ING}/sources/${sourceId}/jobs`, {
      method: 'POST', query: { entityType }, headers: { 'Idempotency-Key': key },
    }),
  listJobs: (f: JobFilters = {}, o: Sig = {}) =>
    request<PageResponse<Job>>(`${ING}/jobs`, { query: { ...f }, ...o }),
  getJob: (id: string, o: Sig = {}) => request<Job>(`${ING}/jobs/${id}`, o),
  jobAttempts: (id: string, page = 0, o: Sig = {}) =>
    request<PageResponse<JobAttempt>>(`${ING}/jobs/${id}/attempts`, { query: { page, size: 50 }, ...o }),
  jobErrors: (id: string, page = 0, o: Sig = {}) =>
    request<PageResponse<RejectedRecord>>(`${ING}/jobs/${id}/errors`, { query: { page, size: 50 }, ...o }),
  quarantine: (f: QuarantineFilters = {}, o: Sig = {}) =>
    request<PageResponse<QuarantinedRecord>>(`${ING}/rejected-records`, { query: { ...f }, ...o }),
  quarantineReasons: (sourceId?: string, o: Sig = {}) =>
    request<ReasonCount[]>(`${ING}/rejected-records/reasons`, { query: { sourceId }, ...o }),
  replayJob: (id: string) => request<Job>(`${ING}/jobs/${id}/replay`, { method: 'POST' }),
  cancelJob: (id: string) => request<Job>(`${ING}/jobs/${id}/cancel`, { method: 'POST' }),

  // --- schedules ---
  listSchedules: (sourceId?: string, o: Sig = {}) =>
    request<PageResponse<Schedule>>(SCH, { query: { sourceId, size: 100 }, ...o }),
  createSchedule: (sourceId: string, entityType: EntityType, intervalSeconds: number) =>
    request<Schedule>(SCH, { method: 'POST', json: { sourceId, entityType, intervalSeconds } }),
  setScheduleEnabled: (id: string, enabled: boolean) =>
    request<Schedule>(`${SCH}/${id}`, { method: 'PATCH', json: { enabled } }),

  /** Cheapest authenticated call valid for every role; rejects with 401 on bad credentials. */
  verifyCredentials: () => request<PageResponse<Source>>(SRC, { query: { page: 0, size: 1 } }),
}

const NIL_UUID = '00000000-0000-0000-0000-000000000000'

async function isForbidden(fn: () => Promise<unknown>): Promise<boolean> {
  try {
    await fn()
    return false
  } catch (e) {
    // 403 = role denied; 404/400 = the role passed authorisation (the probe targets a nonexistent resource).
    // Anything else (network failure, 401, 5xx) says nothing about the role, so it must not be guessed.
    if (e instanceof ApiError && (e.status === 403 || e.status === 404 || e.status === 400)) return e.status === 403
    throw e
  }
}

/**
 * The API has no "who am I" endpoint, so the console infers the role by sending two harmless probes at a
 * nonexistent resource and observing 403 (role denied) versus 404 (role allowed). Nothing is created or changed.
 * The result only drives what the UI shows; the server still authorises every call.
 */
export async function detectRole(): Promise<Role> {
  if (await isForbidden(() => api.jobErrors(NIL_UUID))) return 'VIEWER'
  const adminDenied = await isForbidden(() =>
    request(`${SRC}/${NIL_UUID}`, { method: 'PATCH', json: {} }))
  return adminDenied ? 'OPERATOR' : 'ADMIN'
}
