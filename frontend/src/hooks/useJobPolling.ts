import { useEffect, useRef, useState } from 'react'
import { api } from '../api/endpoints'
import { ApiError } from '../api/client'
import { TERMINAL_STATUSES, type Job } from '../api/types'

export const POLL_INTERVAL_MS = 3000
const MAX_CONSECUTIVE_FAILURES = 5

export interface PollState {
  job: Job | null
  error: unknown
  /** true while the job is non-terminal and polling is active */
  polling: boolean
  /** Replace the job (e.g. after replay/cancel returned a fresh representation) and resume polling if needed. */
  setJob: (j: Job) => void
}

/**
 * Polls GET /ingestion/jobs/{id} until the job reaches a terminal status.
 * Stops on 401/403/404 (retrying cannot help), and after repeated transient failures.
 * Timers and in-flight requests are cancelled on unmount or when the id changes.
 */
export function useJobPolling(jobId: string | undefined, intervalMs = POLL_INTERVAL_MS): PollState {
  const [job, setJobState] = useState<Job | null>(null)
  const [error, setError] = useState<unknown>(null)
  const [polling, setPolling] = useState(false)
  const [resume, setResume] = useState(0)
  const failures = useRef(0)

  useEffect(() => {
    if (!jobId) return
    const ctl = new AbortController()
    let timer: ReturnType<typeof setTimeout> | undefined
    failures.current = 0

    const tick = async () => {
      try {
        const j = await api.getJob(jobId, { signal: ctl.signal })
        if (ctl.signal.aborted) return
        failures.current = 0
        setError(null)
        setJobState(j)
        if (TERMINAL_STATUSES.includes(j.status)) { setPolling(false); return }
        setPolling(true)
      } catch (e) {
        if (ctl.signal.aborted || (e instanceof DOMException && e.name === 'AbortError')) return
        setError(e)
        failures.current += 1
        const fatal = e instanceof ApiError && [401, 403, 404].includes(e.status)
        if (fatal || failures.current >= MAX_CONSECUTIVE_FAILURES) { setPolling(false); return }
      }
      timer = setTimeout(tick, intervalMs)
    }
    void tick()
    return () => { ctl.abort(); if (timer) clearTimeout(timer) }
  }, [jobId, intervalMs, resume])

  const setJob = (j: Job) => {
    setJobState(j)
    if (!TERMINAL_STATUSES.includes(j.status)) setResume((n) => n + 1)
  }
  return { job, error, polling, setJob }
}
