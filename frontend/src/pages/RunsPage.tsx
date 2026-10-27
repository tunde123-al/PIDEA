import { useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { api } from '../api/endpoints'
import { JOB_STATUSES, type JobStatus } from '../api/types'
import { useAsync } from '../hooks/useAsync'
import {
  btn, Card, EmptyState, ErrorBanner, Field, fmtDate, fmtDuration, inputCls, Loading, PageHeader, Pagination, StatusBadge,
} from '../components/ui'

/** datetime-local value -> ISO instant (the API expects an Instant). */
function toInstant(local: string): string | undefined {
  if (!local) return undefined
  const d = new Date(local)
  return Number.isNaN(d.getTime()) ? undefined : d.toISOString()
}

export function RunsPage() {
  const [params, setParams] = useSearchParams()
  const sourceId = params.get('sourceId') ?? ''
  const status = (params.get('status') ?? '') as JobStatus | ''
  const from = params.get('from') ?? ''
  const to = params.get('to') ?? ''
  const [page, setPage] = useState(0)

  const sources = useAsync((signal) => api.listSources(0, 100, { signal }), [])
  const jobs = useAsync(
    (signal) => api.listJobs({
      sourceId: sourceId || undefined, status: status || undefined,
      submittedFrom: toInstant(from), submittedTo: toInstant(to), page, size: 20,
    }, { signal }),
    [sourceId, status, from, to, page],
  )
  const names = new Map((sources.data?.items ?? []).map((s) => [s.id, s.name]))

  function setFilter(key: string, value: string) {
    const next = new URLSearchParams(params)
    if (value) next.set(key, value); else next.delete(key)
    setParams(next, { replace: true })
    setPage(0)
  }
  const hasFilters = Boolean(sourceId || status || from || to)

  return (
    <>
      <PageHeader title="Runs" subtitle="Ingestion job history, newest first." />
      <Card className="mb-6">
        <form className="grid gap-4 sm:grid-cols-2 lg:grid-cols-5" onSubmit={(e) => e.preventDefault()}>
          <Field label="Source">{(id) => (
            <select id={id} className={inputCls} value={sourceId} onChange={(e) => setFilter('sourceId', e.target.value)}>
              <option value="">All sources</option>
              {(sources.data?.items ?? []).map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}
            </select>)}
          </Field>
          <Field label="Status">{(id) => (
            <select id={id} className={inputCls} value={status} onChange={(e) => setFilter('status', e.target.value)}>
              <option value="">Any status</option>
              {JOB_STATUSES.map((s) => <option key={s} value={s}>{s.replace(/_/g, ' ')}</option>)}
            </select>)}
          </Field>
          <Field label="Submitted from">{(id) => <input id={id} type="datetime-local" className={inputCls} value={from} onChange={(e) => setFilter('from', e.target.value)} />}</Field>
          <Field label="Submitted to">{(id) => <input id={id} type="datetime-local" className={inputCls} value={to} onChange={(e) => setFilter('to', e.target.value)} />}</Field>
          <div className="flex items-end gap-2">
            <button type="button" className={btn.secondary} onClick={jobs.reload}>Refresh</button>
            {hasFilters && <button type="button" className={btn.secondary} onClick={() => { setParams({}, { replace: true }); setPage(0) }}>Clear filters</button>}
          </div>
        </form>
      </Card>

      {jobs.loading ? <Loading /> : jobs.error ? <ErrorBanner error={jobs.error} onRetry={jobs.reload} /> : !jobs.data || jobs.data.items.length === 0 ? (
        <EmptyState title={hasFilters ? 'No runs match these filters' : 'No runs yet'} hint={hasFilters ? 'Try clearing the filters.' : 'Runs appear after records or files are submitted.'} />
      ) : (
        <Card>
          <div className="overflow-x-auto">
            <table className="w-full text-left text-sm">
              <caption className="sr-only">Ingestion runs</caption>
              <thead className="text-xs uppercase text-slate-500">
                <tr>
                  <th scope="col" className="py-2 pr-3">Run</th><th scope="col" className="pr-3">Source</th><th scope="col" className="pr-3">Entity</th>
                  <th scope="col" className="pr-3">Status</th><th scope="col" className="pr-3">Accepted / rejected</th>
                  <th scope="col" className="pr-3">Submitted</th><th scope="col">Duration</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {jobs.data.items.map((j) => (
                  <tr key={j.id}>
                    <td className="py-2 pr-3"><Link className="font-mono text-brand-600 hover:underline" to={`/runs/${j.id}`}>{j.id.slice(0, 8)}</Link></td>
                    <td className="pr-3">{names.get(j.sourceId) ?? j.sourceId.slice(0, 8)}</td>
                    <td className="pr-3">{j.entityType}</td>
                    <td className="pr-3"><StatusBadge status={j.status} /></td>
                    <td className="pr-3">{j.recordsAccepted} / {j.recordsRejected}</td>
                    <td className="pr-3">{fmtDate(j.submittedAt)}</td>
                    <td>{fmtDuration(j.durationMs)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <Pagination page={jobs.data.page} totalPages={jobs.data.totalPages} totalItems={jobs.data.totalItems} onPage={setPage} />
        </Card>
      )}
    </>
  )
}
