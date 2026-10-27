import { Link } from 'react-router-dom'
import { useJobPolling } from '../hooks/useJobPolling'
import { ErrorBanner, StatusBadge } from './ui'

/** Compact live status for a just-submitted job; polls until terminal and cleans up on unmount. */
export function JobProgress({ jobId, replayed }: { jobId: string; replayed?: boolean }) {
  const { job, error, polling } = useJobPolling(jobId)
  return (
    <div className="rounded-md border border-slate-200 bg-slate-50 p-3 text-sm" aria-live="polite">
      <p>
        Job <Link className="font-mono text-brand-600 hover:underline" to={`/runs/${jobId}`}>{jobId.slice(0, 8)}</Link>{' '}
        {job ? <StatusBadge status={job.status} /> : <span className="text-slate-500">accepted, loading status…</span>}
        {polling && <span className="ml-2 text-xs text-slate-500">updating…</span>}
      </p>
      {replayed && <p className="mt-1 text-xs text-slate-600">This key was already used: the original job was returned (no duplicate created).</p>}
      {job && (
        <p className="mt-1 text-xs text-slate-600">
          Received {job.recordsReceived} · accepted {job.recordsAccepted} · rejected {job.recordsRejected} · duplicate {job.recordsDuplicate}
        </p>
      )}
      {job?.errorSummary && <p className="mt-1 text-xs text-red-700">{job.errorSummary}</p>}
      {error != null && <div className="mt-2"><ErrorBanner error={error} /></div>}
    </div>
  )
}
