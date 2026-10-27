import { useRef } from 'react'
import { newIdempotencyKey } from '../api/endpoints'

/**
 * Keeps one Idempotency-Key per logical submission. If a submit fails (e.g. network drop) and the user
 * retries the identical payload, the same key is reused so the backend can de-duplicate it.
 * The key is renewed after a success or when the payload fingerprint changes.
 */
export function useIdempotencyKey() {
  const ref = useRef<{ fingerprint: string; key: string } | null>(null)
  return {
    keyFor(fingerprint: string): string {
      if (!ref.current || ref.current.fingerprint !== fingerprint) {
        ref.current = { fingerprint, key: newIdempotencyKey() }
      }
      return ref.current.key
    },
    reset() { ref.current = null },
  }
}
