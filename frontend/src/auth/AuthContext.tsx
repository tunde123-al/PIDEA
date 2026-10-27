import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'
import { api, detectRole } from '../api/endpoints'
import { clearCredentials, onUnauthorized, setCredentials } from '../api/client'
import type { Role } from '../api/types'

export type Action =
  | 'source.create' | 'source.setStatus' | 'job.submit' | 'job.replay' | 'job.cancel'
  | 'job.viewRejections' | 'schedule.create' | 'schedule.toggle'

// Mirrors the @PreAuthorize rules on the controllers. Used only to decide what to render;
// the server remains the authority and may still answer 403.
const ALLOWED: Record<Action, readonly Role[]> = {
  'source.create': ['ADMIN'],
  'source.setStatus': ['ADMIN', 'OPERATOR'],
  'job.submit': ['ADMIN', 'OPERATOR'],
  'job.replay': ['ADMIN', 'OPERATOR'],
  'job.cancel': ['ADMIN', 'OPERATOR'],
  'job.viewRejections': ['ADMIN', 'OPERATOR'],
  'schedule.create': ['ADMIN'],
  'schedule.toggle': ['ADMIN'],
}

export function roleCan(role: Role | null, action: Action): boolean {
  return role !== null && ALLOWED[action].includes(role)
}

interface AuthState {
  username: string | null
  role: Role | null
  signedOutReason: string | null
  signIn: (username: string, password: string) => Promise<void>
  signOut: (reason?: string) => void
  can: (action: Action) => boolean
}

const Ctx = createContext<AuthState | null>(null)

export function AuthProvider({ children }: { children: ReactNode }) {
  // Only the username and the detected role are kept in React state; the credential is in memory in api/client.
  const [session, setSession] = useState<{ username: string; role: Role } | null>(null)
  const [signedOutReason, setReason] = useState<string | null>(null)

  const signOut = useCallback((reason?: string) => {
    clearCredentials()
    setSession(null)
    setReason(reason ?? null)
  }, [])

  const signIn = useCallback(async (username: string, password: string) => {
    setCredentials(username, password)
    try {
      await api.verifyCredentials()
      const role = await detectRole()
      setReason(null)
      setSession({ username, role })
    } catch (e) {
      clearCredentials()
      throw e
    }
  }, [])

  useEffect(() => {
    onUnauthorized(() => signOut('Your session credentials were rejected by the server. Please sign in again.'))
    return () => onUnauthorized(null)
  }, [signOut])

  const value = useMemo<AuthState>(() => ({
    username: session?.username ?? null,
    role: session?.role ?? null,
    signedOutReason,
    signIn,
    signOut,
    can: (a) => roleCan(session?.role ?? null, a),
  }), [session, signedOutReason, signIn, signOut])

  return <Ctx.Provider value={value}>{children}</Ctx.Provider>
}

export function useAuth(): AuthState {
  const v = useContext(Ctx)
  if (!v) throw new Error('useAuth must be used inside AuthProvider')
  return v
}
