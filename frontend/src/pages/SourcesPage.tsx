import { useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { api } from '../api/endpoints'
import { describeError } from '../api/client'
import { SOURCE_TYPES, type ConfigMap, type Source, type SourceType } from '../api/types'
import { useAuth } from '../auth/AuthContext'
import { useAsync } from '../hooks/useAsync'
import {
  btn, Card, EmptyState, ErrorBanner, Field, fmtDate, inputCls, Loading, PageHeader, Pagination, StatusBadge, SuccessBanner,
} from '../components/ui'

/** Parses "key=value" lines into a flat config map (the backend accepts only string/number/boolean values). */
export function parseConfigLines(text: string): ConfigMap {
  const out: ConfigMap = {}
  for (const raw of text.split('\n')) {
    const line = raw.trim()
    if (!line) continue
    const i = line.indexOf('=')
    if (i < 1) throw new Error(`Config line "${line}" must look like key=value`)
    const key = line.slice(0, i).trim()
    const val = line.slice(i + 1).trim()
    if (val === 'true' || val === 'false') out[key] = val === 'true'
    else if (val !== '' && !Number.isNaN(Number(val))) out[key] = Number(val)
    else out[key] = val
  }
  return out
}

function CreateSourceForm({ onCreated }: { onCreated: (s: Source) => void }) {
  const [name, setName] = useState('')
  const [type, setType] = useState<SourceType>('REST_PUSH')
  const [config, setConfig] = useState('')
  const [secretRef, setSecretRef] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<unknown>(null)

  async function submit(e: FormEvent) {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      const created = await api.createSource({
        name: name.trim(), type, config: parseConfigLines(config), ...(secretRef.trim() ? { secretRef: secretRef.trim() } : {}),
      })
      setName(''); setConfig(''); setSecretRef('')
      onCreated(created)
    } catch (err) {
      setError(err)
    } finally {
      setBusy(false)
    }
  }

  return (
    <Card title="Register a source">
      <form onSubmit={submit} className="grid gap-4 md:grid-cols-2">
        <Field label="Name" hint="Lowercase letters, digits and hyphens (3–100 characters).">
          {(id, d) => <input id={id} aria-describedby={d} className={inputCls} required minLength={3} maxLength={100}
            pattern="^[a-z0-9][a-z0-9\-]*[a-z0-9]$" value={name} onChange={(e) => setName(e.target.value)} />}
        </Field>
        <Field label="Type">
          {(id) => (
            <select id={id} className={inputCls} value={type} onChange={(e) => setType(e.target.value as SourceType)}>
              {SOURCE_TYPES.map((t) => <option key={t} value={t}>{t}</option>)}
            </select>
          )}
        </Field>
        <Field label="Config (optional)" hint={type === 'HTTP_API'
          ? 'One key=value per line. HTTP_API requires baseUrl (https), e.g. baseUrl=https://crm.example.com. Credential-like keys are rejected.'
          : type === 'POSTGRES_TABLE'
            ? 'One key=value per line. Required: host, database, user, schema, table, keyColumn (a UNIQUE NOT NULL integer/date/timestamp column). Optional: port, sslmode (default require), columns, batchSize, maxRows, timeoutSeconds. Set the Secret reference to a PIDEA_SRC_* variable holding the password.'
          : 'One key=value per line. Non-secret settings only; credential-like keys are rejected.'}>
          {(id, d) => <textarea id={id} aria-describedby={d} rows={3} className={`${inputCls} font-mono`} value={config} onChange={(e) => setConfig(e.target.value)} />}
        </Field>
        <Field label="Secret reference (optional)" hint="The NAME of an environment variable on the server, e.g. CRM_API_KEY. Never enter a secret value.">
          {(id, d) => <input id={id} aria-describedby={d} className={`${inputCls} font-mono`} pattern="^[A-Z][A-Z0-9_]{0,99}$"
            value={secretRef} onChange={(e) => setSecretRef(e.target.value)} />}
        </Field>
        <div className="space-y-3 md:col-span-2">
          {error != null && <ErrorBanner error={error} />}
          <button type="submit" className={btn.primary} disabled={busy}>{busy ? 'Creating…' : 'Create source'}</button>
        </div>
      </form>
    </Card>
  )
}

export function SourcesPage() {
  const { can } = useAuth()
  const [page, setPage] = useState(0)
  const { data, error, loading, reload } = useAsync((signal) => api.listSources(page, 20, { signal }), [page])
  const [created, setCreated] = useState<Source | null>(null)
  const [toggleError, setToggleError] = useState<string | null>(null)
  const [busyId, setBusyId] = useState<string | null>(null)

  async function toggle(s: Source) {
    setBusyId(s.id)
    setToggleError(null)
    try {
      await api.setSourceStatus(s.id, s.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE')
      reload()
    } catch (e) {
      setToggleError(describeError(e))
    } finally {
      setBusyId(null)
    }
  }

  return (
    <>
      <PageHeader title="Sources" subtitle="Registered integration sources and their current status." />
      {can('source.create') && (
        <div className="mb-6">
          <CreateSourceForm onCreated={(s) => { setCreated(s); setPage(0); reload() }} />
        </div>
      )}
      {created && <div className="mb-4"><SuccessBanner>Source <strong>{created.name}</strong> created.</SuccessBanner></div>}
      {toggleError && <div className="mb-4"><ErrorBanner error={new Error(toggleError)} /></div>}

      {loading ? <Loading /> : error ? <ErrorBanner error={error} onRetry={reload} /> : !data || data.items.length === 0 ? (
        <EmptyState title="No sources registered" hint={can('source.create') ? 'Use the form above to register your first source.' : 'An administrator needs to register a source.'} />
      ) : (
        <Card>
          <div className="overflow-x-auto">
            <table className="w-full text-left text-sm">
              <caption className="sr-only">Integration sources</caption>
              <thead className="text-xs uppercase text-slate-500">
                <tr><th scope="col" className="py-2 pr-3">Name</th><th scope="col" className="pr-3">Type</th><th scope="col" className="pr-3">Status</th>
                  <th scope="col" className="pr-3">Credential ref</th><th scope="col" className="pr-3">Updated</th><th scope="col"><span className="sr-only">Actions</span></th></tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {data.items.map((s) => (
                  <tr key={s.id}>
                    <td className="py-2 pr-3"><Link className="font-medium text-brand-600 hover:underline" to={`/sources/${s.id}`}>{s.name}</Link></td>
                    <td className="pr-3 font-mono text-xs">{s.type}</td>
                    <td className="pr-3"><StatusBadge status={s.status} /></td>
                    <td className="pr-3">{s.secretConfigured ? 'Configured' : 'None'}</td>
                    <td className="pr-3">{fmtDate(s.updatedAt)}</td>
                    <td className="text-right">
                      {can('source.setStatus') && (
                        <button type="button" className={btn.secondary} disabled={busyId === s.id} onClick={() => toggle(s)}
                          aria-label={`${s.status === 'ACTIVE' ? 'Disable' : 'Enable'} source ${s.name}`}>
                          {s.status === 'ACTIVE' ? 'Disable' : 'Enable'}
                        </button>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <Pagination page={data.page} totalPages={data.totalPages} totalItems={data.totalItems} onPage={setPage} />
        </Card>
      )}
    </>
  )
}
