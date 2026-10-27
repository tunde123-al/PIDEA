import { useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { api } from '../api/endpoints'
import { ApiError } from '../api/client'
import { CANCELLABLE_STATUSES, type Job } from '../api/types'
import { useAuth } from '../auth/AuthContext'
import { useAsync } from '../hooks/useAsync'
import { useJobPolling } from '../hooks/useJobPolling'
import {
  btn, Card, ConfirmDialog, EmptyState, ErrorBanner, fmtDate, fmtDuration, InfoBanner, Loading, PageHeader, StatusBadge, SuccessBanner,
} from '../components/ui'

type Pending = 'replay' | 'cancel' | null

function RejectionsCard({ jobId, rejected }: { jobId: string; rejected: number }) {
  const { data, error, loading, reload } = useAsync((signal) => api.jobErrors(jobId, 0, { signal }), [jobId, rejected])
  return (
    <Card title="Rejected records">
      <p className="mb-3 text-xs text-slate-500">Reason codes and safe diagnostics only; raw source payloads are never returned by the API.</p>
      {loading ? <Loading /> : error ? <ErrorBanner error={error} onRetry={reload} /> : !data || data.items.length === 0 ? (
        <EmptyState title="No rejected records" />
      ) : (
        <div className="overflow-x-auto">
          <table className="w-full text-left text-sm">
            <caption className="sr-only">Rejected records</caption>
            <thead className="text-xs uppercase text-slate-500">
              <tr><th scope="col" className="py-2 pr-3">Record ref</th><th scope="col" className="pr-3">Reason</th><th scope="col" className="pr-3">Details</th><th scope="col">Processed</th></tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {data.items.map((r) => (
                <tr key={r.id}>
                  <td className="py-2 pr-3 font-mono text-xs">{r.sourceRecordRef ?? '—'}</td>
                  <td className="pr-3 font-mono text-xs">{r.reasonCode}</td>
                  <td className="pr-3">{r.details ?? '—'}</td>
                  <td>{fmtDate(r.processedAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
          {data.totalItems > data.items.length && <p className="mt-2 text-xs text-slate-500">Showing first {data.items.length} of {data.totalItems}.</p>}
        </div>
      )}
    </Card>
  )
}

export function RunDetailPage() {
  const { id = '' } = useParams()
  const navigate = useNavigate()
  const { can, role } = useAuth()
  const { job, error, polling, setJob } = useJobPolling(id)
  const status = job?.status
  const attempts = useAsync((signal) => api.jobAttempts(id, 0, { signal }), [id, status])
  const [pending, setPending] = useState<Pending>(null)
  const [busy, setBusy] = useState(false)
  const [actionError, setActionError] = useState<unknown>(null)
  const [notice, setNotice] = useState<string | null>(null)

  async function confirm() {
    if (!pending) return
    setBusy(true); setActionError(null)
    try {
      if (pending === 'cancel') {
        const updated: Job = await api.cancelJob(id)
        setJob(updated); setNotice('Job cancelled.'); setPending(null)
      } else {
        const created = await api.replayJob(id)
        setPending(null)
        navigate(`/runs/${created.id}`)
      }
    } catch (e) {
      setActionError(e)
      // A 409 means the job moved on (e.g. the worker picked it up): refresh the real state.
      if (e instanceof ApiError && e.status === 409) setJob({ ...(job as Job) })
    } finally {
      setBusy(false)
    }
  }

  if (!job) {
    if (error) return <ErrorBanner error={error} />
    return <Loading />
  }

  const canReplay = can('job.replay') && job.status === 'FAILED'
  const canCancel = can('job.cancel') && CANCELLABLE_STATUSES.includes(job.status)

  return (
    <>
      <p className="mb-2 text-sm"><Link className="text-brand-600 hover:underline" to="/runs">← Runs</Link></p>
      <PageHeader title={`Run ${job.id.slice(0, 8)}`} subtitle={`${job.jobType} · ${job.entityType}`}
        actions={
          <div className="flex flex-wrap items-center gap-2">
            <StatusBadge status={job.status} />
            {polling && <span className="text-xs text-slate-500" role="status">Live: refreshing every few seconds</span>}
            {canReplay && <button type="button" className={btn.secondary} onClick={() => { setActionError(null); setPending('replay') }}>Replay</button>}
            {canCancel && <button type="button" className={btn.danger} onClick={() => { setActionError(null); setPending('cancel') }}>Cancel job</button>}
          </div>
        } />

      {notice && <div className="mb-4"><SuccessBanner>{notice}</SuccessBanner></div>}
      {error != null && <div className="mb-4"><ErrorBanner error={error} /></div>}
      {!can('job.replay') && <div className="mb-4"><InfoBanner>Your role is read-only: replay, cancel and rejected-record diagnostics are not available.</InfoBanner></div>}

      <div className="grid gap-6 lg:grid-cols-2">
        <Card title="Summary">
          <dl className="grid grid-cols-2 gap-3 text-sm">
            <div><dt className="text-slate-500">Received</dt><dd className="font-semibold">{job.recordsReceived}</dd></div>
            <div><dt className="text-slate-500">Accepted</dt><dd className="font-semibold">{job.recordsAccepted}</dd></div>
            <div><dt className="text-slate-500">Rejected</dt><dd className="font-semibold">{job.recordsRejected}</dd></div>
            <div><dt className="text-slate-500">Duplicate</dt><dd className="font-semibold">{job.recordsDuplicate}</dd></div>
            <div><dt className="text-slate-500">Retries</dt><dd>{job.retryCount} / {job.maxRetries}</dd></div>
            <div><dt className="text-slate-500">Duration</dt><dd>{fmtDuration(job.durationMs)}</dd></div>
            <div><dt className="text-slate-500">Submitted</dt><dd>{fmtDate(job.submittedAt)}</dd></div>
            <div><dt className="text-slate-500">Completed</dt><dd>{fmtDate(job.completedAt)}</dd></div>
            <div className="col-span-2"><dt className="text-slate-500">Source</dt><dd><Link className="text-brand-600 hover:underline" to={`/sources/${job.sourceId}`}>{job.sourceId}</Link></dd></div>
            {job.replayOf && <div className="col-span-2"><dt className="text-slate-500">Replay of</dt><dd><Link className="font-mono text-brand-600 hover:underline" to={`/runs/${job.replayOf}`}>{job.replayOf}</Link></dd></div>}
            {job.correlationId && <div className="col-span-2"><dt className="text-slate-500">Correlation ID</dt><dd className="font-mono text-xs">{job.correlationId}</dd></div>}
          </dl>
          {job.errorSummary && <p className="mt-3 rounded bg-red-50 p-2 text-sm text-red-800">{job.errorSummary}</p>}
        </Card>

        <Card title="Attempt history">
          {attempts.loading && !attempts.data ? <Loading /> : attempts.error ? <ErrorBanner error={attempts.error} onRetry={attempts.reload} /> : !attempts.data || attempts.data.items.length === 0 ? (
            <EmptyState title="No attempts yet" hint="Attempts are recorded once the worker picks up the job." />
          ) : (
            <ol className="space-y-3 text-sm">
              {attempts.data.items.map((a) => (
                <li key={a.attemptNo} className="rounded border border-slate-200 p-2">
                  <p className="font-medium">Attempt {a.attemptNo} · <StatusBadge status={a.outcome} /></p>
                  <p className="text-xs text-slate-500">{fmtDate(a.startedAt)} → {fmtDate(a.finishedAt)}</p>
                  {a.error && <p className="mt-1 text-xs text-red-700">{a.error}</p>}
                </li>
              ))}
            </ol>
          )}
        </Card>
      </div>

      {role !== null && can('job.viewRejections') && job.recordsRejected > 0 && (
        <div className="mt-6"><RejectionsCard jobId={job.id} rejected={job.recordsRejected} /></div>
      )}

      {pending && (
        <ConfirmDialog
          title={pending === 'replay' ? 'Replay this failed job?' : 'Cancel this job?'}
          message={pending === 'replay'
            ? 'A new job will be created from the same input. A failed job can be replayed only once.'
            : 'The job will not be processed. This cannot be undone.'}
          confirmLabel={pending === 'replay' ? 'Replay job' : 'Cancel job'}
          danger={pending === 'cancel'}
          busy={busy}
          error={actionError}
          onConfirm={confirm}
          onCancel={() => setPending(null)}
        />
      )}
    </>
  )
}
