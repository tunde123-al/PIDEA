import { useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { api } from '../api/endpoints'
import { describeError } from '../api/client'
import { ENTITY_TYPES, type EntityType, type Schedule } from '../api/types'
import { useAuth } from '../auth/AuthContext'
import { useAsync } from '../hooks/useAsync'
import {
  btn, Card, EmptyState, ErrorBanner, Field, fmtDate, fmtInterval, inputCls, Loading, PageHeader, StatusBadge, SuccessBanner,
} from '../components/ui'

const UNITS = { minutes: 60, hours: 3600, days: 86400 } as const

export function SchedulesPage() {
  const { can } = useAuth()
  const schedules = useAsync((signal) => api.listSchedules(undefined, { signal }), [])
  const sources = useAsync((signal) => api.listSources(0, 100, { signal }), [])
  const httpSources = (sources.data?.items ?? []).filter((s) => s.type === 'HTTP_API')
  const names = new Map((sources.data?.items ?? []).map((s) => [s.id, s.name]))

  const [sourceId, setSourceId] = useState('')
  const [entity, setEntity] = useState<EntityType>('CUSTOMER')
  const [amount, setAmount] = useState(1)
  const [unit, setUnit] = useState<keyof typeof UNITS>('hours')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<unknown>(null)
  const [ok, setOk] = useState<string | null>(null)
  const [toggleBusy, setToggleBusy] = useState<string | null>(null)

  async function create(e: FormEvent) {
    e.preventDefault()
    setBusy(true); setError(null); setOk(null)
    try {
      await api.createSchedule(sourceId || httpSources[0]?.id, entity, amount * UNITS[unit])
      setOk('Schedule created.')
      schedules.reload()
    } catch (err) { setError(err) } finally { setBusy(false) }
  }

  async function toggle(s: Schedule) {
    setToggleBusy(s.id); setError(null); setOk(null)
    try { await api.setScheduleEnabled(s.id, !s.enabled); schedules.reload() }
    catch (err) { setError(new Error(describeError(err))) } finally { setToggleBusy(null) }
  }

  return (
    <>
      <PageHeader title="Schedules" subtitle="Recurring pulls from HTTP_API sources (minimum interval 60 seconds)." />

      {can('schedule.create') && (
        <Card title="Create a schedule" className="mb-6">
          {httpSources.length === 0 ? (
            <p className="text-sm text-slate-600">Schedules need an HTTP_API source. {sources.loading ? '' : 'None is registered yet.'}</p>
          ) : (
            <form onSubmit={create} className="grid gap-4 sm:grid-cols-2 lg:grid-cols-5">
              <Field label="Source">{(id) => (
                <select id={id} className={inputCls} value={sourceId || httpSources[0].id} onChange={(e) => setSourceId(e.target.value)}>
                  {httpSources.map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}
                </select>)}
              </Field>
              <Field label="Entity type">{(id) => (
                <select id={id} className={inputCls} value={entity} onChange={(e) => setEntity(e.target.value as EntityType)}>
                  {ENTITY_TYPES.map((t) => <option key={t} value={t}>{t}</option>)}
                </select>)}
              </Field>
              <Field label="Run every">{(id) => <input id={id} type="number" min={1} step={1} required className={inputCls} value={amount}
                onChange={(e) => setAmount(Number(e.target.value))} />}</Field>
              <Field label="Unit">{(id) => (
                <select id={id} className={inputCls} value={unit} onChange={(e) => setUnit(e.target.value as keyof typeof UNITS)}>
                  {Object.keys(UNITS).map((u) => <option key={u} value={u}>{u}</option>)}
                </select>)}
              </Field>
              <div className="flex items-end"><button type="submit" className={btn.primary} disabled={busy}>{busy ? 'Creating…' : 'Create schedule'}</button></div>
            </form>
          )}
        </Card>
      )}

      {ok && <div className="mb-4"><SuccessBanner>{ok}</SuccessBanner></div>}
      {error != null && <div className="mb-4"><ErrorBanner error={error} /></div>}

      {schedules.loading ? <Loading /> : schedules.error ? <ErrorBanner error={schedules.error} onRetry={schedules.reload} /> : !schedules.data || schedules.data.items.length === 0 ? (
        <EmptyState title="No schedules" hint={can('schedule.create') ? 'Create one above.' : 'An administrator can create schedules.'} />
      ) : (
        <Card>
          <div className="overflow-x-auto">
            <table className="w-full text-left text-sm">
              <caption className="sr-only">Integration schedules</caption>
              <thead className="text-xs uppercase text-slate-500">
                <tr><th scope="col" className="py-2 pr-3">Source</th><th scope="col" className="pr-3">Entity</th><th scope="col" className="pr-3">Interval</th>
                  <th scope="col" className="pr-3">State</th><th scope="col" className="pr-3">Next run</th><th scope="col"><span className="sr-only">Actions</span></th></tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {schedules.data.items.map((s) => (
                  <tr key={s.id}>
                    <td className="py-2 pr-3"><Link className="text-brand-600 hover:underline" to={`/sources/${s.sourceId}`}>{names.get(s.sourceId) ?? s.sourceId.slice(0, 8)}</Link></td>
                    <td className="pr-3">{s.entityType}</td>
                    <td className="pr-3">{fmtInterval(s.intervalSeconds)}</td>
                    <td className="pr-3"><StatusBadge status={s.enabled ? 'ACTIVE' : 'DISABLED'} /></td>
                    <td className="pr-3">{s.enabled ? fmtDate(s.nextRunAt) : '—'}</td>
                    <td className="text-right">
                      {can('schedule.toggle') && (
                        <button type="button" className={btn.secondary} disabled={toggleBusy === s.id} onClick={() => toggle(s)}
                          aria-label={`${s.enabled ? 'Disable' : 'Enable'} schedule for ${names.get(s.sourceId) ?? s.sourceId}`}>
                          {s.enabled ? 'Disable' : 'Enable'}
                        </button>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </Card>
      )}
    </>
  )
}
