// Mirrors the Spring Boot DTOs (IngestionDtos, SourceDtos, ScheduleDtos, ApiError, PageResponse).

export type Role = 'ADMIN' | 'OPERATOR' | 'VIEWER'

export const ENTITY_TYPES = ['CUSTOMER', 'ORDER', 'PAYMENT_EVENT'] as const
export type EntityType = (typeof ENTITY_TYPES)[number]

export const SOURCE_TYPES = ['REST_PUSH', 'CSV_FILE', 'JSON_FILE', 'HTTP_API', 'POSTGRES_TABLE'] as const
export type SourceType = (typeof SOURCE_TYPES)[number]
export type SourceStatus = 'ACTIVE' | 'DISABLED'

export const JOB_STATUSES = [
  'PENDING', 'RUNNING', 'SUCCEEDED', 'PARTIALLY_SUCCEEDED', 'FAILED', 'RETRY_SCHEDULED', 'CANCELLED',
] as const
export type JobStatus = (typeof JOB_STATUSES)[number]
export type JobType = 'RECORDS' | 'FILE_CSV' | 'FILE_JSON' | 'HTTP_PULL' | 'POSTGRES_PULL'

/** Terminal statuses per JobStatus.allowedNext() in the backend. */
export const TERMINAL_STATUSES: readonly JobStatus[] = ['SUCCEEDED', 'PARTIALLY_SUCCEEDED', 'FAILED', 'CANCELLED']
/** Statuses the backend allows to be cancelled (409 otherwise). */
export const CANCELLABLE_STATUSES: readonly JobStatus[] = ['PENDING', 'RETRY_SCHEDULED']

export interface PageResponse<T> {
  items: T[]
  page: number
  size: number
  totalItems: number
  totalPages: number
}

export interface ApiErrorBody {
  timestamp?: string
  status: number
  code: string
  message: string
  correlationId?: string
  violations?: { field: string; message: string }[] | null
}

export type ConfigMap = Record<string, string | number | boolean>

export interface Source {
  id: string
  name: string
  type: SourceType
  status: SourceStatus
  config: ConfigMap
  secretConfigured: boolean
  createdAt: string
  updatedAt: string
}

export interface CreateSourceRequest {
  name: string
  type: SourceType
  config?: ConfigMap
  secretRef?: string
}

export interface SourceHealth {
  sourceId: string
  status: SourceStatus
  lastJobId: string | null
  lastJobStatus: string | null
  lastJobSubmittedAt: string | null
  lastJobCompletedAt: string | null
  failedJobsLast24h: number
  completedJobsLast24h: number
  recordsAcceptedLast24h: number
  recordsRejectedLast24h: number
}

export interface Job {
  id: string
  sourceId: string
  jobType: JobType
  entityType: EntityType
  status: JobStatus
  correlationId: string | null
  recordsReceived: number
  recordsAccepted: number
  recordsRejected: number
  recordsDuplicate: number
  errorSummary: string | null
  retryCount: number
  maxRetries: number
  submittedAt: string
  startedAt: string | null
  completedAt: string | null
  durationMs: number | null
  replayOf: string | null
  scheduleId: string | null
  scheduledFor: string | null
}

export interface JobAttempt {
  attemptNo: number
  outcome: string
  error: string | null
  startedAt: string
  finishedAt: string | null
}

export interface RejectedRecord {
  id: string
  sourceRecordRef: string | null
  reasonCode: string
  details: string | null
  processedAt: string
}

export interface JobFilters {
  sourceId?: string
  status?: JobStatus
  submittedFrom?: string
  submittedTo?: string
  page?: number
  size?: number
}

export interface Schedule {
  id: string
  sourceId: string
  entityType: EntityType
  intervalSeconds: number
  enabled: boolean
  nextRunAt: string | null
  createdAt: string
}

export interface Submission {
  job: Job
  replayed: boolean
}

/** A quarantined record across jobs. Reason code and safe diagnostics only; the API never returns raw payloads. */
export interface QuarantinedRecord {
  id: string
  jobId: string
  sourceId: string
  entityType: EntityType
  sourceRecordRef: string | null
  reasonCode: string
  details: string | null
  processedAt: string
}

export interface ReasonCount {
  reasonCode: string
  count: number
}

export interface QuarantineFilters {
  sourceId?: string
  jobId?: string
  entityType?: EntityType
  reasonCode?: string
  page?: number
  size?: number
}
