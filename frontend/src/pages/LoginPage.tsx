import { useState, type FormEvent } from 'react'
import { useAuth } from '../auth/AuthContext'
import { ApiError, describeError } from '../api/client'
import { btn, ErrorBanner, Field, inputCls, InfoBanner } from '../components/ui'

export function LoginPage() {
  const { signIn, signedOutReason } = useAuth()
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<unknown>(null)

  async function submit(e: FormEvent) {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      await signIn(username.trim(), password)
    } catch (err) {
      setError(err instanceof ApiError && err.status === 401
        ? new Error('Invalid username or password.')
        : new Error(describeError(err)))
    } finally {
      setBusy(false)
      setPassword('')
    }
  }

  return (
    <div className="flex min-h-screen items-center justify-center bg-brand-900 p-4">
      <div className="w-full max-w-sm rounded-lg bg-white p-6 shadow-xl">
        <h1 className="text-2xl font-bold tracking-wide text-brand-900">PIDEA</h1>
        <p className="mb-5 text-sm text-slate-600">Sign in with your PIDEA API account.</p>
        <form onSubmit={submit} className="space-y-4">
          {signedOutReason && <InfoBanner>{signedOutReason}</InfoBanner>}
          <Field label="Username">
            {(id) => <input id={id} className={inputCls} autoComplete="username" required value={username}
              onChange={(e) => setUsername(e.target.value)} />}
          </Field>
          <Field label="Password">
            {(id) => <input id={id} type="password" className={inputCls} autoComplete="current-password" required
              value={password} onChange={(e) => setPassword(e.target.value)} />}
          </Field>
          {error != null && <ErrorBanner error={error} />}
          <button type="submit" className={`${btn.primary} w-full`} disabled={busy}>{busy ? 'Signing in…' : 'Sign in'}</button>
        </form>
        <p className="mt-4 text-xs text-slate-500">
          Credentials are held in memory only and are cleared on reload or sign-out. Use HTTPS in any shared environment.
        </p>
      </div>
    </div>
  )
}
