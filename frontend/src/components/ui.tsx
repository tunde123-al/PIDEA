import { useEffect, useId, useRef, type ReactNode } from 'react'
import { describeError, ApiError } from '../api/client'

export const btn = {
  primary: 'inline-flex items-center justify-center rounded-md bg-brand-600 px-3 py-2 text-sm font-medium text-white hover:bg-brand-700 disabled:cursor-not-allowed disabled:opacity-50',
  secondary: 'inline-flex items-center justify-center rounded-md border border-slate-300 bg-white px-3 py-2 text-sm font-medium text-slate-700 hover:bg-slate-50 disabled:cursor-not-allowed disabled:opacity-50',
  danger: 'inline-flex items-center justify-center rounded-md bg-red-600 px-3 py-2 text-sm font-medium text-white hover:bg-red-700 disabled:cursor-not-allowed disabled:opacity-50',
}
export const inputCls = 'block w-full rounded-md border border-slate-300 bg-white px-3 py-2 text-sm shadow-sm focus:border-brand-500 disabled:bg-slate-100'

export function PageHeader({ title, subtitle, actions }: { title: string; subtitle?: string; actions?: ReactNode }) {
  return (
    <div className="mb-6 flex flex-wrap items-start justify-between gap-3">
      <div>
        <h1 className="text-2xl font-semibold tracking-tight">{title}</h1>
        {subtitle && <p className="mt-1 text-sm text-slate-600">{subtitle}</p>}
      </div>
      {actions}
    </div>
  )
}

export function Card({ title, children, className = '' }: { title?: string; children: ReactNode; className?: string }) {
  return (
    <section className={`rounded-lg border border-slate-200 bg-white p-4 shadow-sm ${className}`}>
      {title && <h2 className="mb-3 text-base font-semibold">{title}</h2>}
      {children}
    </section>
  )
}

export function Loading({ label = 'Loading…' }: { label?: string }) {
  return (
    <div role="status" className="flex items-center gap-2 py-8 text-sm text-slate-600">
      <span className="h-4 w-4 animate-spin rounded-full border-2 border-slate-300 border-t-brand-600" aria-hidden />
      {label}
    </div>
  )
}

export function EmptyState({ title, hint }: { title: string; hint?: string }) {
  return (
    <div className="rounded-lg border border-dashed border-slate-300 bg-white px-4 py-10 text-center">
      <p className="font-medium text-slate-700">{title}</p>
      {hint && <p className="mt-1 text-sm text-slate-500">{hint}</p>}
    </div>
  )
}

export function ErrorBanner({ error, onRetry }: { error: unknown; onRetry?: () => void }) {
  const cid = error instanceof ApiError ? error.correlationId : undefined
  return (
    <div role="alert" className="rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-800">
      <p>{describeError(error)}</p>
      {cid && <p className="mt-1 text-xs text-red-700">Correlation ID: <span className="font-mono">{cid}</span></p>}
      {onRetry && <button type="button" onClick={onRetry} className="mt-2 font-medium underline">Retry</button>}
    </div>
  )
}

export function SuccessBanner({ children }: { children: ReactNode }) {
  return <div role="status" className="rounded-md border border-green-200 bg-green-50 p-3 text-sm text-green-800">{children}</div>
}

export function InfoBanner({ children }: { children: ReactNode }) {
  return <div className="rounded-md border border-slate-300 bg-slate-100 p-3 text-sm text-slate-800">{children}</div>
}

const STATUS_STYLES: Record<string, string> = {
  SUCCEEDED: 'bg-green-100 text-green-800',
  ACTIVE: 'bg-green-100 text-green-800',
  PARTIALLY_SUCCEEDED: 'bg-amber-100 text-amber-800',
  RETRY_SCHEDULED: 'bg-amber-100 text-amber-800',
  FAILED: 'bg-red-100 text-red-800',
  RUNNING: 'bg-purple-100 text-purple-800',
  PENDING: 'bg-slate-200 text-slate-700',
  CANCELLED: 'bg-slate-200 text-slate-600',
  DISABLED: 'bg-slate-200 text-slate-600',
}

/** Status is conveyed by text as well as colour. */
export function StatusBadge({ status }: { status: string }) {
  return (
    <span className={`inline-block whitespace-nowrap rounded-full px-2 py-0.5 text-xs font-medium ${STATUS_STYLES[status] ?? 'bg-slate-100 text-slate-700'}`}>
      {status.replace(/_/g, ' ')}
    </span>
  )
}

export function Field({ label, hint, children }: { label: string; hint?: string; children: (id: string, describedBy?: string) => ReactNode }) {
  const id = useId()
  const hintId = hint ? `${id}-hint` : undefined
  return (
    <div>
      <label htmlFor={id} className="mb-1 block text-sm font-medium text-slate-700">{label}</label>
      {children(id, hintId)}
      {hint && <p id={hintId} className="mt-1 text-xs text-slate-500">{hint}</p>}
    </div>
  )
}

export function ConfirmDialog(props: {
  title: string
  message: ReactNode
  confirmLabel: string
  danger?: boolean
  busy?: boolean
  error?: unknown
  onConfirm: () => void
  onCancel: () => void
}) {
  const titleId = useId()
  const cancelRef = useRef<HTMLButtonElement>(null)
  const { onCancel, busy } = props
  useEffect(() => {
    cancelRef.current?.focus()
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape' && !busy) onCancel() }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [onCancel, busy])
  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/50 p-4">
      <div role="dialog" aria-modal="true" aria-labelledby={titleId} className="w-full max-w-md rounded-lg bg-white p-5 shadow-xl">
        <h2 id={titleId} className="text-lg font-semibold">{props.title}</h2>
        <div className="mt-2 text-sm text-slate-600">{props.message}</div>
        {props.error != null && <div className="mt-3"><ErrorBanner error={props.error} /></div>}
        <div className="mt-5 flex justify-end gap-2">
          <button ref={cancelRef} type="button" className={btn.secondary} onClick={props.onCancel} disabled={props.busy}>Keep as is</button>
          <button type="button" className={props.danger ? btn.danger : btn.primary} onClick={props.onConfirm} disabled={props.busy}>
            {props.busy ? 'Working…' : props.confirmLabel}
          </button>
        </div>
      </div>
    </div>
  )
}

export function Pagination({ page, totalPages, totalItems, onPage }: { page: number; totalPages: number; totalItems: number; onPage: (p: number) => void }) {
  if (totalPages <= 1) return <p className="mt-3 text-xs text-slate-500">{totalItems} total</p>
  return (
    <nav aria-label="Pagination" className="mt-3 flex items-center justify-between text-sm">
      <span className="text-slate-600">Page {page + 1} of {totalPages} · {totalItems} total</span>
      <span className="flex gap-2">
        <button type="button" className={btn.secondary} disabled={page <= 0} onClick={() => onPage(page - 1)}>Previous</button>
        <button type="button" className={btn.secondary} disabled={page + 1 >= totalPages} onClick={() => onPage(page + 1)}>Next</button>
      </span>
    </nav>
  )
}

export function fmtDate(iso: string | null | undefined): string {
  if (!iso) return '—'
  const d = new Date(iso)
  return Number.isNaN(d.getTime()) ? iso : d.toLocaleString()
}

export function fmtDuration(ms: number | null | undefined): string {
  if (ms === null || ms === undefined) return '—'
  return ms < 1000 ? `${ms} ms` : `${(ms / 1000).toFixed(1)} s`
}

export function fmtInterval(seconds: number): string {
  if (seconds % 86400 === 0) return `every ${seconds / 86400} d`
  if (seconds % 3600 === 0) return `every ${seconds / 3600} h`
  if (seconds % 60 === 0) return `every ${seconds / 60} min`
  return `every ${seconds} s`
}
