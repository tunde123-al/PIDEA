import { Link } from 'react-router-dom'
import { api } from '../api/endpoints'
import type { SourceHealth } from '../api/types'
import { useAsync } from '../hooks/useAsync'
import { Card, EmptyState, ErrorBanner, fmtDate, Loading, PageHeader, StatusBadge } from '../components/ui'

const HEALTH_SOURCE_LIMIT = 25

function Stat({ label, value, hint }: { label: string; value: number | string; hint?: string }) {
  return (
    <div className="rounded-lg border border-slate-200 bg-white p-4 shadow-sm">
      <p className="text-sm text-slate-600">{label}</p>
      <p className="mt-1 text-2xl font-semibold">{value}</p>
      {hint && <p className="mt-1 text-xs text-slate-500">{hint}</p>}
    </div>
  )
}

export function OverviewPage() {
  const { data, error, loading, reload } = useAsync(async (signal) => {
    const [sources, jobs] = await Promise.all([api.listSources(0, 100, { signal }), api.listJobs({ size: 10 }, { signal })])
    const sample = sources.items.slice(0, HEALTH_SOURCE_LIMIT)
    const settled = await Promise.allSettled(sample.map((s) => api.sourceHealth(s.id, { signal })))
    const health = settled.flatMap((r) => (r.status === 'fulfilled' ? [r.value] : []))
    return { sources, jobs, health, sampled: sample.length, healthFailed: settled.length - health.length }
  }, [])

  if (loading) return <Loading />
  if (error) return <ErrorBanner error={error} onRetry={reload} />
  if (!data) return null

  const { sources, jobs, health, sampled, healthFailed } = data
  const sum = (k: keyof Pick<SourceHealth, 'failedJobsLast24h' | 'completedJobsLast24h' | 'recordsAcceptedLast24h' | 'recordsRejectedLast24h'>) =>
    health.reduce((a, h) => a + h[k], 0)
  const active = sources.items.filter((s) => s.status === 'ACTIVE').length
  const scopeNote = sources.totalItems > sampled ? `First ${sampled} of ${sources.totalItems} sources` : `${sampled} source(s)`

  return (
    <>
      <PageHeader title="Overview" subtitle="Live figures from the PIDEA API." />
      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <Stat label="Sources" value={sources.totalItems} hint={sources.items.length ? `${active} active on this page` : undefined} />
        <Stat label="Completed jobs (24h)" value={sum('completedJobsLast24h')} hint={scopeNote} />
        <Stat label="Failed jobs (24h)" value={sum('failedJobsLast24h')} hint={scopeNote} />
        <Stat label="Records accepted / rejected (24h)" value={`${sum('recordsAcceptedLast24h')} / ${sum('recordsRejectedLast24h')}`} hint={scopeNote} />
      </div>
      {healthFailed > 0 && (
        <p className="mt-2 text-xs text-amber-700">Health could not be loaded for {healthFailed} source(s); 24h totals exclude them.</p>
      )}

      <Card title="Most recent runs" className="mt-6">
        {jobs.items.length === 0 ? (
          <EmptyState title="No runs yet" hint="Submit records or upload a file from a source to create the first run." />
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-left text-sm">
              <thead className="text-xs uppercase text-slate-500">
                <tr><th scope="col" className="py-2 pr-3">Run</th><th scope="col" className="pr-3">Status</th><th scope="col" className="pr-3">Entity</th><th scope="col">Submitted</th></tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {jobs.items.map((j) => (
                  <tr key={j.id}>
                    <td className="py-2 pr-3"><Link className="font-mono text-brand-600 hover:underline" to={`/runs/${j.id}`}>{j.id.slice(0, 8)}</Link></td>
                    <td className="pr-3"><StatusBadge status={j.status} /></td>
                    <td className="pr-3">{j.entityType}</td>
                    <td>{fmtDate(j.submittedAt)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        <p className="mt-3 text-sm"><Link className="text-brand-600 hover:underline" to="/runs">View all runs →</Link></p>
      </Card>
    </>
  )
}
