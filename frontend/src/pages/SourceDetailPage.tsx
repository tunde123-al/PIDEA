import { useState, type FormEvent } from 'react'
import { Link, useParams } from 'react-router-dom'
import { api } from '../api/endpoints'
import { ENTITY_TYPES, type EntityType, type Source, type Submission } from '../api/types'
import { useAuth } from '../auth/AuthContext'
import { useAsync } from '../hooks/useAsync'
import { useIdempotencyKey } from '../hooks/useIdempotencyKey'
import { JobProgress } from '../components/JobProgress'
import {
  btn, Card, ErrorBanner, Field, fmtDate, InfoBanner, inputCls, Loading, PageHeader, StatusBadge,
} from '../components/ui'

const MAX_RECORDS = 1000
const MAX_UPLOAD_BYTES = 5 * 1024 * 1024

/** Accepts a JSON array of objects, or an object with a "records" array (as the file upload format does). */
export function parseRecords(text: string): Record<string, unknown>[] {
  let parsed: unknown
  try { parsed = JSON.parse(text) } catch { throw new Error('Records must be valid JSON.') }
  const arr = Array.isArray(parsed) ? parsed
    : parsed && typeof parsed === 'object' && Array.isArray((parsed as { records?: unknown }).records)
      ? (parsed as { records: unknown[] }).records : null
  if (!arr) throw new Error('Provide a JSON array of record objects.')
  if (arr.length === 0) throw new Error('At least one record is required.')
  if (arr.length > MAX_RECORDS) throw new Error(`At most ${MAX_RECORDS} records per request (got ${arr.length}).`)
  if (!arr.every((r) => r !== null && typeof r === 'object' && !Array.isArray(r))) {
    throw new Error('Every record must be a JSON object.')
  }
  return arr as Record<string, unknown>[]
}

function EntitySelect({ id, value, onChange }: { id: string; value: EntityType; onChange: (e: EntityType) => void }) {
  return (
    <select id={id} className={inputCls} value={value} onChange={(e) => onChange(e.target.value as EntityType)}>
      {ENTITY_TYPES.map((t) => <option key={t} value={t}>{t}</option>)}
    </select>
  )
}

function useSubmitter() {
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<unknown>(null)
  const [result, setResult] = useState<Submission | null>(null)
  const run = async (fn: () => Promise<Submission>, onDone?: () => void) => {
    setBusy(true); setError(null); setResult(null)
    try { setResult(await fn()); onDone?.() } catch (e) { setError(e) } finally { setBusy(false) }
  }
  return { busy, error, result, run, setError }
}

function JsonSubmitCard({ source }: { source: Source }) {
  const [entity, setEntity] = useState<EntityType>('CUSTOMER')
  const [text, setText] = useState('')
  const s = useSubmitter()
  const key = useIdempotencyKey()

  function submit(e: FormEvent) {
    e.preventDefault()
    let records: Record<string, unknown>[]
    try { records = parseRecords(text) } catch (err) { s.setError(err); return }
    void s.run(() => api.submitRecords(source.id, entity, records, key.keyFor(`${entity}:${text}`)), key.reset)
  }

  return (
    <Card title="Submit JSON records">
      <form onSubmit={submit} className="space-y-4">
        <Field label="Entity type">{(id) => <EntitySelect id={id} value={entity} onChange={setEntity} />}</Field>
        <Field label="Records (JSON array)" hint={`Up to ${MAX_RECORDS} objects. Each record is validated by the worker; invalid records are quarantined, not rejected here.`}>
          {(id, d) => <textarea id={id} aria-describedby={d} rows={8} className={`${inputCls} font-mono`} required spellCheck={false}
            placeholder='[{"customer_id": "C100", "full_name": "Example", "email": "a@example.test", "created_at": "2025-01-01T00:00:00Z"}]'
            value={text} onChange={(e) => setText(e.target.value)} />}
        </Field>
        {s.error != null && <ErrorBanner error={s.error} />}
        <button type="submit" className={btn.primary} disabled={s.busy}>{s.busy ? 'Submitting…' : 'Submit records'}</button>
      </form>
      {s.result && <div className="mt-4"><JobProgress jobId={s.result.job.id} replayed={s.result.replayed} /></div>}
    </Card>
  )
}

function FileUploadCard({ source }: { source: Source }) {
  const ext = source.type === 'CSV_FILE' ? '.csv' : '.json'
  const [entity, setEntity] = useState<EntityType>('CUSTOMER')
  const [file, setFile] = useState<File | null>(null)
  const [inputKey, setInputKey] = useState(0)
  const s = useSubmitter()
  const key = useIdempotencyKey()

  function submit(e: FormEvent) {
    e.preventDefault()
    if (!file) { s.setError(new Error('Choose a file to upload.')); return }
    if (!file.name.toLowerCase().endsWith(ext)) { s.setError(new Error(`This source accepts ${ext} files only.`)); return }
    if (file.size > MAX_UPLOAD_BYTES) { s.setError(new Error('Files are limited to 5 MiB.')); return }
    void s.run(
      () => api.uploadFile(source.id, entity, file, key.keyFor(`${entity}:${file.name}:${file.size}:${file.lastModified}`)),
      () => { key.reset(); setFile(null); setInputKey((k) => k + 1) },
    )
  }

  return (
    <Card title={`Upload ${ext.slice(1).toUpperCase()} file`}>
      <form onSubmit={submit} className="space-y-4">
        <Field label="Entity type">{(id) => <EntitySelect id={id} value={entity} onChange={setEntity} />}</Field>
        <Field label={`File (${ext})`} hint="Up to 5 MiB and 100,000 records, UTF-8. The file format must match the source type.">
          {(id, d) => <input key={inputKey} id={id} aria-describedby={d} type="file" accept={ext} className="block w-full text-sm"
            onChange={(e) => setFile(e.target.files?.[0] ?? null)} />}
        </Field>
        {s.error != null && <ErrorBanner error={s.error} />}
        <button type="submit" className={btn.primary} disabled={s.busy}>{s.busy ? 'Uploading…' : 'Upload file'}</button>
      </form>
      {s.result && <div className="mt-4"><JobProgress jobId={s.result.job.id} replayed={s.result.replayed} /></div>}
    </Card>
  )
}

function PullCard({ source }: { source: Source }) {
  const [entity, setEntity] = useState<EntityType>('CUSTOMER')
  const s = useSubmitter()
  const key = useIdempotencyKey()
  return (
    <Card title={source.type === 'POSTGRES_TABLE' ? 'Pull from PostgreSQL table' : 'Trigger HTTPS pull'}>
      <form onSubmit={(e) => { e.preventDefault(); void s.run(() => api.triggerPull(source.id, entity, key.keyFor(entity)), key.reset) }} className="space-y-4">
        <Field label="Entity type" hint={source.type === 'POSTGRES_TABLE' ? 'Queues a read-only pull of the next batch of rows after the last successful checkpoint.' : 'Queues a read-only pull from the configured baseUrl.'}>{(id, d) => (
          <select id={id} aria-describedby={d} className={inputCls} value={entity} onChange={(e) => setEntity(e.target.value as EntityType)}>
            {ENTITY_TYPES.map((t) => <option key={t} value={t}>{t}</option>)}
          </select>
        )}</Field>
        {s.error != null && <ErrorBanner error={s.error} />}
        <button type="submit" className={btn.primary} disabled={s.busy}>{s.busy ? 'Queuing…' : 'Queue pull'}</button>
      </form>
      {s.result && <div className="mt-4"><JobProgress jobId={s.result.job.id} replayed={s.result.replayed} /></div>}
    </Card>
  )
}

export function SourceDetailPage() {
  const { id = '' } = useParams()
  const { can } = useAuth()
  const src = useAsync((signal) => api.getSource(id, { signal }), [id])
  const health = useAsync((signal) => api.sourceHealth(id, { signal }), [id])

  if (src.loading) return <Loading />
  if (src.error) return <ErrorBanner error={src.error} onRetry={src.reload} />
  const source = src.data
  if (!source) return null

  const canSubmit = can('job.submit')
  const disabled = source.status !== 'ACTIVE'
  const h = health.data

  return (
    <>
      <p className="mb-2 text-sm"><Link className="text-brand-600 hover:underline" to="/sources">← Sources</Link></p>
      <PageHeader title={source.name} subtitle={`${source.type} · created ${fmtDate(source.createdAt)}`}
        actions={<StatusBadge status={source.status} />} />

      <div className="grid gap-6 lg:grid-cols-2">
        <Card title="Health (last 24 hours)">
          {health.loading ? <Loading /> : health.error ? <ErrorBanner error={health.error} onRetry={health.reload} /> : h && (
            <dl className="grid grid-cols-2 gap-3 text-sm">
              <div><dt className="text-slate-500">Completed jobs</dt><dd className="text-lg font-semibold">{h.completedJobsLast24h}</dd></div>
              <div><dt className="text-slate-500">Failed jobs</dt><dd className="text-lg font-semibold">{h.failedJobsLast24h}</dd></div>
              <div><dt className="text-slate-500">Records accepted</dt><dd className="text-lg font-semibold">{h.recordsAcceptedLast24h}</dd></div>
              <div><dt className="text-slate-500">Records rejected</dt><dd className="text-lg font-semibold">{h.recordsRejectedLast24h}</dd></div>
              <div className="col-span-2">
                <dt className="text-slate-500">Latest job</dt>
                <dd>{h.lastJobId ? <><Link className="font-mono text-brand-600 hover:underline" to={`/runs/${h.lastJobId}`}>{h.lastJobId.slice(0, 8)}</Link>{' '}
                  {h.lastJobStatus && <StatusBadge status={h.lastJobStatus} />} <span className="text-xs text-slate-500">submitted {fmtDate(h.lastJobSubmittedAt)}</span></> : 'No jobs yet'}</dd>
              </div>
            </dl>
          )}
        </Card>
        <Card title="Configuration">
          <dl className="space-y-2 text-sm">
            <div><dt className="text-slate-500">Credential reference</dt><dd>{source.secretConfigured ? 'Configured (name not shown)' : 'None'}</dd></div>
            <div><dt className="text-slate-500">Config</dt>
              <dd>{Object.keys(source.config).length === 0 ? 'Empty' : (
                <ul className="font-mono text-xs">{Object.entries(source.config).map(([k, v]) => <li key={k}>{k} = {String(v)}</li>)}</ul>)}</dd></div>
          </dl>
        </Card>
      </div>

      <div className="mt-6 space-y-6">
        {!canSubmit ? (
          <InfoBanner>Your role (read-only) cannot submit data. Ask an administrator or operator.</InfoBanner>
        ) : disabled ? (
          <InfoBanner>This source is disabled, so the API will not accept new submissions. Enable it from the Sources page.</InfoBanner>
        ) : source.type === 'REST_PUSH' ? <JsonSubmitCard source={source} />
          : source.type === 'CSV_FILE' || source.type === 'JSON_FILE' ? <FileUploadCard source={source} />
            : <PullCard source={source} />}
      </div>
    </>
  )
}
