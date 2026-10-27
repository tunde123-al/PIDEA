import { useCallback, useEffect, useRef, useState, type DependencyList } from 'react'

export interface AsyncState<T> {
  data: T | null
  error: unknown
  loading: boolean
  reload: () => void
}

interface Settled<T> { sig: string; data: T | null; error: unknown }

/**
 * Loads data when deps change (or reload() is called); aborts the in-flight request on change/unmount.
 * `loading` is derived: it is true until a result for the current deps/reload signature has settled.
 * Deps must be JSON-serialisable (strings, numbers, booleans).
 */
export function useAsync<T>(fn: (signal: AbortSignal) => Promise<T>, deps: DependencyList): AsyncState<T> {
  const [tick, setTick] = useState(0)
  const [settled, setSettled] = useState<Settled<T> | null>(null)
  const fnRef = useRef(fn)
  useEffect(() => { fnRef.current = fn })

  const sig = JSON.stringify([...deps, tick])

  useEffect(() => {
    const ctl = new AbortController()
    fnRef.current(ctl.signal)
      .then((data) => { if (!ctl.signal.aborted) setSettled({ sig, data, error: null }) })
      .catch((error) => {
        if (ctl.signal.aborted || (error instanceof DOMException && error.name === 'AbortError')) return
        setSettled((prev) => ({ sig, data: prev?.data ?? null, error }))
      })
    return () => ctl.abort()
  }, [sig])

  const reload = useCallback(() => setTick((t) => t + 1), [])
  const current = settled?.sig === sig
  return { data: settled?.data ?? null, error: current ? settled.error : null, loading: !current, reload }
}
