import { useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { api } from '../api/endpoints'
import { ENTITY_TYPES, type EntityType } from '../api/types'
import { useAuth } from '../auth/AuthContext'
import { useAsync } from '../hooks/useAsync'
import {
  btn, Card, EmptyState, ErrorBanner, Field, fmtDate, InfoBanner, inputCls, Loading, PageHeader, Pagination,
} from '../components/ui'

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

/**
 * Quarantined (rejected) records across all jobs. The server restricts this to ADMIN and OPERATOR and returns
 * reason codes and safe diagnostics only, so there is nothing sensitive to hide here, but viewers never even
 * trigger the request. Records are not replayable one by one: replay works on a whole FAILED job from its run page,
 * so each row links to its run instead of offering an action that does not exist.
 */
export function QuarantinePage() {
  const { can } = useAuth()
  const allowed = can('job.viewRejections')
  const [params, setParams] = useSearchParams()
  const sourceId = params.get('sourceId') ?? ''
  const entityType = (params.get('entityType') ?? '') as EntityType | ''
  const reasonCode = params.get('reason') ?? ''
  const jobId = params.get('jobId') ?? ''
  const jobIdInvalid = jobId !== '' && !UUID.test(jobId)
  const [page, setPage] = useState(0)

  const sources = useAsync((signal) => (allowed ? api.listSources(0, 100, { signal }) : Promise.resolve(null)), [allowed])
  const reasons = useAsync(
    (signal) => (allowed ? api.quarantineReasons(sourceId || undefined, { signal }) : Promise.resolve(null)),
    [allowed, sourceId],
  )
  const records = useAsync(
    (signal) => (allowed && !jobIdInvalid
      ? api.quarantine({
        sourceId: sourceId || undefined, entityType: entityType || undefined, reasonCode: reasonCode || undefined,
        jobId: jobId || undefined, page, size: 25,
      }, { signal })
      : Promise.resolve(null)),
    [allowed, jobIdInvalid, sourceId, entityType, reasonCode, jobId, page],
  )
  const names = new Map((sources.data?.items ?? []).map((s) => [s.id, s.name]))

  function setFilter(key: string, value: string) {
    const next = new URLSearchParams(params)
    if (value) next.set(key, value); else next.delete(key)
    if (key === 'sourceId') next.delete('reason') // the reason list is per source
    setParams(next, { replace: true })
    setPage(0)
  }
  const hasFilters = Boolean(sourceId || entityType || reasonCode || jobId)

  if (!allowed) {
    return (
      <>
        <PageHeader title="Quarantine" subtitle="Records that failed validation." />
        <InfoBanner>Your role is read-only, so quarantined records are not available to you. The server enforces this.</InfoBanner>
      </>
    )
  }

  return (
    <>
      <PageHeader title="Quarantine" subtitle="Records that failed validation, newest first. Reason codes and safe diagnostics only; raw source data is never stored here." />
      <Card className="mb-6">
        <form className="grid gap-4 sm:grid-cols-2 lg:grid-cols-5" onSubmit={(e) => e.preventDefault()}>
          <Field label="Source">{(id) => (
            <select id={id} className={inputCls} value={sourceId} onChange={(e) => setFilter('sourceId', e.target.value)}>
              <option value="">All sources</option>
              {(sources.data?.items ?? []).map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}
            </select>)}
          </Field>
          <Field label="Entity type">{(id) => (
            <select id={id} className={inputCls} value={entityType} onChange={(e) => setFilter('entityType', e.target.value)}>
              <option value="">Any entity</option>
              {ENTITY_TYPES.map((t) => <option key={t} value={t}>{t}</option>)}
            </select>)}
          </Field>
          <Field label="Reason">{(id) => (
            <select id={id} className={inputCls} value={reasonCode} onChange={(e) => setFilter('reason', e.target.value)}>
              <option value="">Any reason</option>
              {(reasons.data ?? []).map((r) => <option key={r.reasonCode} value={r.reasonCode}>{r.reasonCode} ({r.count})</option>)}
              {reasonCode && !(reasons.data ?? []).some((r) => r.reasonCode === reasonCode) && <option value={reasonCode}>{reasonCode}</option>}
            </select>)}
          </Field>
          <Field label="Run ID" hint={jobIdInvalid ? 'Enter a full run ID (UUID).' : undefined}>{(id, d) => (
            <input id={id} aria-describedby={d} aria-invalid={jobIdInvalid} className={`${inputCls} font-mono`} value={jobId}
              placeholder="optional" onChange={(e) => setFilter('jobId', e.target.value.trim())} />)}
          </Field>
          <div className="flex items-end gap-2">
            <button type="button" className={btn.secondary} onClick={() => { records.reload(); reasons.reload() }}>Refresh</button>
            {hasFilters && <button type="button" className={btn.secondary} onClick={() => { setParams({}, { replace: true }); setPage(0) }}>Clear filters</button>}
          </div>
        </form>
      </Card>

      {jobIdInvalid ? (
        <InfoBanner>Fix the run ID filter to see results.</InfoBanner>
      ) : records.loading ? <Loading label="Loading quarantined records…" /> : records.error ? (
        <ErrorBanner error={records.error} onRetry={records.reload} />
      ) : !records.data || records.data.items.length === 0 ? (
        <EmptyState
          title={hasFilters ? 'No quarantined records match these filters' : 'Nothing is quarantined'}
          hint={hasFilters ? 'Try clearing the filters.' : 'Records that fail validation will appear here with the reason they were rejected.'} />
      ) : (
        <Card>
          <div className="overflow-x-auto">
            <table className="w-full text-left text-sm">
              <caption className="sr-only">Quarantined records</caption>
              <thead className="text-xs uppercase text-slate-500">
                <tr>
                  <th scope="col" className="py-2 pr-3">Processed</th><th scope="col" className="pr-3">Reason</th>
                  <th scope="col" className="pr-3">Details</th><th scope="col" className="pr-3">Record ref</th>
                  <th scope="col" className="pr-3">Entity</th><th scope="col" className="pr-3">Source</th><th scope="col">Run</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {records.data.items.map((r) => (
                  <tr key={r.id}>
                    <td className="py-2 pr-3 whitespace-nowrap">{fmtDate(r.processedAt)}</td>
                    <td className="pr-3 font-mono text-xs">{r.reasonCode}</td>
                    <td className="pr-3">{r.details ?? '—'}</td>
                    <td className="pr-3 font-mono text-xs">{r.sourceRecordRef ?? '—'}</td>
                    <td className="pr-3">{r.entityType}</td>
                    <td className="pr-3"><Link className="text-brand-700 hover:underline" to={`/sources/${r.sourceId}`}>{names.get(r.sourceId) ?? r.sourceId.slice(0, 8)}</Link></td>
                    <td><Link className="font-mono text-brand-700 hover:underline" to={`/runs/${r.jobId}`}>{r.jobId.slice(0, 8)}</Link></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <Pagination page={records.data.page} totalPages={records.data.totalPages} totalItems={records.data.totalItems} onPage={setPage} />
        </Card>
      )}
    </>
  )
}
