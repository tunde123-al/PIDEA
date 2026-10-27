import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { Role } from '../api/types'
import { err, installFetch, job, page, renderApp, roleBackend, source, type Handler } from './fakeApi'

const SRC = '/api/v1/integration-sources'
const ING = '/api/v1/ingestion'

const health = (id: string) => ({
  sourceId: id, status: 'ACTIVE', lastJobId: null, lastJobStatus: null, lastJobSubmittedAt: null, lastJobCompletedAt: null,
  failedJobsLast24h: 2, completedJobsLast24h: 5, recordsAcceptedLast24h: 40, recordsRejectedLast24h: 3,
})

/** Common read endpoints so pages can render. */
const reads = (jobs = [job()]): Handler => ({ method, path }) => {
  if (method !== 'GET') return undefined
  if (path === SRC) return { body: page([source()]) }
  if (path === `${SRC}/${source().id}`) return { body: source() }
  if (path === `${SRC}/${source().id}/health`) return { body: health(source().id) }
  if (path === `${ING}/jobs`) return { body: page(jobs) }
  if (path === '/api/v1/integration-schedules') return { body: page([]) }
  return undefined
}

async function signIn(role: Role = 'ADMIN', user = 'someone') {
  const u = userEvent.setup()
  await u.type(await screen.findByLabelText('Username'), user)
  await u.type(screen.getByLabelText('Password'), 'pw-for-tests')
  await u.click(screen.getByRole('button', { name: 'Sign in' }))
  await screen.findByRole('navigation', { name: 'Main' })
  expect(role).toBeTruthy()
  return u
}

beforeEach(() => { sessionStorage.clear(); localStorage.clear() })
afterEach(() => { vi.unstubAllGlobals(); vi.useRealTimers() })

describe('authentication', () => {
  it('shows a clear message for a 401 at sign-in and keeps the user on the login form', async () => {
    installFetch(() => err(401, 'UNAUTHORIZED', 'Bad credentials'))
    renderApp()
    const u = userEvent.setup()
    await u.type(await screen.findByLabelText('Username'), 'admin')
    await u.type(screen.getByLabelText('Password'), 'wrong')
    await u.click(screen.getByRole('button', { name: 'Sign in' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Invalid username or password.')
    expect(screen.queryByRole('navigation', { name: 'Main' })).not.toBeInTheDocument()
  })

  it('never persists the password or Authorization value in web storage, and sends Basic auth', async () => {
    const f = installFetch(roleBackend('ADMIN'), reads())
    renderApp()
    await signIn('ADMIN', 'admin')
    expect(JSON.stringify({ ...localStorage })).not.toMatch(/pw-for-tests|Basic|cHctZm9y/)
    expect(JSON.stringify({ ...sessionStorage })).not.toMatch(/pw-for-tests|Basic|cHctZm9y/)
    expect(localStorage.length + sessionStorage.length).toBe(0)
    expect(f.calls[0].headers.get('Authorization')).toBe(`Basic ${btoa('admin:pw-for-tests')}`)
  })

  it.each([['ADMIN'], ['OPERATOR'], ['VIEWER']] as const)('detects the %s role via harmless probes', async (role) => {
    const f = installFetch(roleBackend(role), reads())
    renderApp()
    await signIn(role)
    expect(await screen.findByText(`Role: ${role}`)).toBeInTheDocument()
    expect(f.calls.every((c) => c.method === 'GET' || c.method === 'PATCH')).toBe(true)
  })

  it('returns to sign-in with an explanation when a later request gets 401', async () => {
    let revoked = false
    installFetch(
      ({ path }) => (revoked && path === `${ING}/jobs` ? err(401, 'UNAUTHORIZED', 'x') : undefined),
      roleBackend('ADMIN'), reads(),
    )
    renderApp()
    const u = await signIn('ADMIN')
    revoked = true
    await u.click(screen.getByRole('link', { name: 'Runs' }))
    expect(await screen.findByText(/credentials were rejected by the server/i)).toBeInTheDocument()
  })
})

describe('navigation and pages', () => {
  it('renders Overview with real figures, then navigates to Sources, Runs and Schedules', async () => {
    installFetch(roleBackend('ADMIN'), reads([job({ status: 'SUCCEEDED' })]))
    renderApp()
    const u = await signIn()
    expect(await screen.findByRole('heading', { name: 'Overview' })).toBeInTheDocument()
    expect(await screen.findByText('Completed jobs (24h)')).toBeInTheDocument()
    expect(await screen.findByText('40 / 3')).toBeInTheDocument()

    await u.click(screen.getByRole('link', { name: 'Sources' }))
    expect(await screen.findByRole('heading', { name: 'Sources' })).toBeInTheDocument()
    expect(await screen.findByRole('link', { name: 'demo-customers' })).toBeInTheDocument()

    await u.click(screen.getByRole('link', { name: 'Runs' }))
    expect(await screen.findByRole('heading', { name: 'Runs' })).toBeInTheDocument()
    expect(await screen.findByRole('table', { name: 'Ingestion runs' })).toBeInTheDocument()

    await u.click(screen.getByRole('link', { name: 'Schedules' }))
    expect(await screen.findByRole('heading', { name: 'Schedules' })).toBeInTheDocument()
    expect(await screen.findByText('No schedules')).toBeInTheDocument()
  })

  it('shows an empty state and an error state with correlation id and retry', async () => {
    installFetch(({ method, path }) => {
      if (method === 'GET' && path === SRC) return { body: page([]) }
      if (method === 'GET' && path === `${ING}/jobs`) return err(500, 'INTERNAL', 'Something broke')
      return undefined
    }, roleBackend('VIEWER'))
    renderApp('/sources')
    const u = await signIn('VIEWER')
    expect(await screen.findByText('No sources registered')).toBeInTheDocument()
    await u.click(screen.getByRole('link', { name: 'Runs' }))
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('Something broke')
    expect(alert).toHaveTextContent('cid-123')
    expect(within(alert).getByRole('button', { name: 'Retry' })).toBeInTheDocument()
  })

  it('passes supported filter parameters to the jobs endpoint', async () => {
    const f = installFetch(roleBackend('ADMIN'), reads())
    renderApp('/runs')
    const u = await signIn()
    await u.selectOptions(await screen.findByLabelText('Status'), 'FAILED')
    await u.selectOptions(screen.getByLabelText('Source'), source().id)
    await waitFor(() => {
      const last = f.calls.filter((c) => c.path === `${ING}/jobs`).at(-1)!
      expect(last.search.get('status')).toBe('FAILED')
      expect(last.search.get('sourceId')).toBe(source().id)
    })
  })
})

describe('source creation', () => {
  it('posts the form to the API and confirms success', async () => {
    const f = installFetch(
      roleBackend('ADMIN'),
      ({ method, path }) => (method === 'POST' && path === SRC ? { status: 201, body: source({ name: 'new-source' }) } : undefined),
      reads(),
    )
    renderApp('/sources')
    const u = await signIn('ADMIN')
    await u.type(await screen.findByLabelText('Name'), 'new-source')
    await u.type(screen.getByLabelText('Secret reference (optional)'), 'CRM_API_KEY')
    await u.click(screen.getByRole('button', { name: 'Create source' }))
    expect(await screen.findByText(/created\./)).toBeInTheDocument()
    const post = f.calls.find((c) => c.method === 'POST')!
    expect(JSON.parse(post.body as string)).toEqual({ name: 'new-source', type: 'REST_PUSH', config: {}, secretRef: 'CRM_API_KEY' })
  })

  it('surfaces a 409 conflict from the server', async () => {
    installFetch(roleBackend('ADMIN'), ({ method, path }) =>
      (method === 'POST' && path === SRC ? err(409, 'CONFLICT', 'Source name already exists') : undefined), reads())
    renderApp('/sources')
    const u = await signIn('ADMIN')
    await u.type(await screen.findByLabelText('Name'), 'demo-customers')
    await u.click(screen.getByRole('button', { name: 'Create source' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Source name already exists')
  })

  it('does not render the creation form for non-admin roles', async () => {
    installFetch(roleBackend('OPERATOR'), reads())
    renderApp('/sources')
    await signIn('OPERATOR')
    await screen.findByRole('link', { name: 'demo-customers' })
    expect(screen.queryByRole('button', { name: 'Create source' })).not.toBeInTheDocument()
  })

  it('maps a server 403 to a clear message even if the UI offered the action', async () => {
    installFetch(roleBackend('ADMIN'), ({ method, path }) =>
      (method === 'POST' && path === SRC ? err(403, 'FORBIDDEN', 'Access denied') : undefined), reads())
    renderApp('/sources')
    const u = await signIn('ADMIN')
    await u.type(await screen.findByLabelText('Name'), 'another-one')
    await u.click(screen.getByRole('button', { name: 'Create source' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('not permitted to do this (403)')
  })
})

describe('submission and uploads', () => {
  it('submits JSON records with an Idempotency-Key and shows the accepted job status', async () => {
    const f = installFetch(
      roleBackend('OPERATOR'),
      ({ method, path }) => (method === 'POST' && path === `${ING}/jobs` ? { status: 202, body: job(), headers: { 'Idempotent-Replayed': 'false' } } : undefined),
      ({ method, path }) => (method === 'GET' && path === `${ING}/jobs/${job().id}` ? { body: job({ status: 'SUCCEEDED', recordsReceived: 1, recordsAccepted: 1 }) } : undefined),
      reads(),
    )
    renderApp(`/sources/${source().id}`)
    const u = await signIn('OPERATOR')
    const box = await screen.findByLabelText(/Records \(JSON array\)/)
    await userEventPaste(u, box, '[{"customer_id":"C1"}]')
    await u.click(screen.getByRole('button', { name: 'Submit records' }))
    expect(await screen.findByText('SUCCEEDED')).toBeInTheDocument()
    const post = f.calls.find((c) => c.method === 'POST' && c.path === `${ING}/jobs`)!
    expect(post.headers.get('Idempotency-Key')).toMatch(/^[A-Za-z0-9._:-]{8,100}$/)
    expect(JSON.parse(post.body as string)).toEqual({ sourceId: source().id, entityType: 'CUSTOMER', records: [{ customer_id: 'C1' }] })
  })

  it('rejects invalid JSON client-side without calling the API', async () => {
    const f = installFetch(roleBackend('OPERATOR'), reads())
    renderApp(`/sources/${source().id}`)
    const u = await signIn('OPERATOR')
    await userEventPaste(u, await screen.findByLabelText(/Records \(JSON array\)/), 'not json')
    await u.click(screen.getByRole('button', { name: 'Submit records' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('valid JSON')
    expect(f.calls.some((c) => c.method === 'POST')).toBe(false)
  })

  it('uploads a CSV as multipart form data with source, entity type and key', async () => {
    const csvSource = source({ type: 'CSV_FILE', name: 'csv-src' })
    const f = installFetch(
      roleBackend('OPERATOR'),
      ({ method, path }) => (method === 'POST' && path === `${ING}/jobs/files` ? { status: 202, body: job({ jobType: 'FILE_CSV' }) } : undefined),
      ({ method, path }) => (method === 'GET' && path === `${SRC}/${csvSource.id}` ? { body: csvSource } : undefined),
      reads(),
    )
    renderApp(`/sources/${csvSource.id}`)
    const u = await signIn('OPERATOR')
    const input = await screen.findByLabelText(/File \(\.csv\)/)
    await u.upload(input, new File(['customer_id\nC1\n'], 'customers.csv', { type: 'text/csv' }))
    await u.click(screen.getByRole('button', { name: 'Upload file' }))
    await screen.findByText(/accepted, loading status|PENDING/)
    const post = f.calls.find((c) => c.path === `${ING}/jobs/files`)!
    expect(post.search.get('sourceId')).toBe(csvSource.id)
    expect(post.search.get('entityType')).toBe('CUSTOMER')
    expect(post.headers.get('Idempotency-Key')).toBeTruthy()
    expect((post.body as FormData).get('file')).toBeInstanceOf(File)
    expect(post.headers.get('Content-Type')).toBeNull() // browser must set the multipart boundary
  })

  it('refuses a wrong file extension for the source type', async () => {
    const jsonSource = source({ type: 'JSON_FILE' })
    const f = installFetch(({ method, path }) => (method === 'GET' && path === `${SRC}/${jsonSource.id}` ? { body: jsonSource } : undefined), roleBackend('OPERATOR'), reads())
    renderApp(`/sources/${jsonSource.id}`)
    const u = await signIn('OPERATOR')
    fireEvent.change(await screen.findByLabelText(/File \(\.json\)/), { target: { files: [new File(['a'], 'x.csv')] } })
    await u.click(screen.getByRole('button', { name: 'Upload file' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('.json files only')
    expect(f.calls.some((c) => c.method === 'POST')).toBe(false)
  })

  it('hides submission for viewers and says why', async () => {
    installFetch(roleBackend('VIEWER'), reads())
    renderApp(`/sources/${source().id}`)
    await signIn('VIEWER')
    expect(await screen.findByText(/read-only\) cannot submit data/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Submit records' })).not.toBeInTheDocument()
  })
})

async function userEventPaste(u: ReturnType<typeof userEvent.setup>, el: HTMLElement, text: string) {
  await u.click(el)
  await u.paste(text) // paste avoids user-event's special handling of "[" and "{"
}

describe('run detail: actions, roles and error states', () => {
  const failed = job({ status: 'FAILED', recordsRejected: 2, errorSummary: 'boom' })
  const detail: Handler = ({ method, path }) => {
    if (method !== 'GET') return undefined
    if (path === `${ING}/jobs/${failed.id}`) return { body: failed }
    if (path === `${ING}/jobs/${failed.id}/attempts`) return { body: page([{ attemptNo: 1, outcome: 'FAILED', error: 'timeout', startedAt: '2025-01-01T00:00:00Z', finishedAt: '2025-01-01T00:00:05Z' }]) }
    if (path === `${ING}/jobs/${failed.id}/errors`) return { body: page([{ id: 'r1', sourceRecordRef: 'row-7', reasonCode: 'INVALID_EMAIL', details: 'bad format', processedAt: '2025-01-01T00:00:01Z' }]) }
    return undefined
  }

  it('shows attempts and rejection diagnostics to operators, with replay offered for FAILED jobs', async () => {
    installFetch(roleBackend('OPERATOR'), detail, reads())
    renderApp(`/runs/${failed.id}`)
    await signIn('OPERATOR')
    expect(await screen.findByText('timeout')).toBeInTheDocument()
    expect(await screen.findByText('INVALID_EMAIL')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Replay' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Cancel job' })).not.toBeInTheDocument()
  })

  it('never requests or shows rejected records for viewers, and offers no replay/cancel', async () => {
    const f = installFetch(roleBackend('VIEWER'), detail, reads())
    renderApp(`/runs/${failed.id}`)
    await signIn('VIEWER')
    await screen.findByText('timeout')
    expect(screen.queryByText('INVALID_EMAIL')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Replay' })).not.toBeInTheDocument()
    expect(f.calls.some((c) => c.path === `${ING}/jobs/${failed.id}/errors`)).toBe(false)
    expect(screen.getByText(/read-only/)).toBeInTheDocument()
  })

  it('shows the server error inside the replay dialog when the backend refuses (409)', async () => {
    installFetch(
      roleBackend('OPERATOR'),
      ({ method, path }) => (method === 'POST' && path === `${ING}/jobs/${failed.id}/replay` ? err(409, 'CONFLICT', 'Integration source is disabled') : undefined),
      detail, reads(),
    )
    renderApp(`/runs/${failed.id}`)
    const u = await signIn('OPERATOR')
    await u.click(await screen.findByRole('button', { name: 'Replay' }))
    const dialog = screen.getByRole('dialog')
    await u.click(within(dialog).getByRole('button', { name: 'Replay job' }))
    expect(await within(dialog).findByRole('alert')).toHaveTextContent('Integration source is disabled')
    expect(screen.getByRole('dialog')).toBeInTheDocument() // stays open so the user sees why
  })

  it('cancels a pending job after confirmation, and reports a 403 if the server denies it', async () => {
    const pending = job({ status: 'PENDING' })
    let deny = true
    installFetch(
      roleBackend('OPERATOR'),
      ({ method, path }) => {
        if (method === 'POST' && path === `${ING}/jobs/${pending.id}/cancel`) {
          return deny ? err(403, 'FORBIDDEN', 'Access denied') : { body: job({ status: 'CANCELLED' }) }
        }
        if (method === 'GET' && path === `${ING}/jobs/${pending.id}`) return { body: pending }
        if (method === 'GET' && path === `${ING}/jobs/${pending.id}/attempts`) return { body: page([]) }
        return undefined
      },
      reads(),
    )
    renderApp(`/runs/${pending.id}`)
    const u = await signIn('OPERATOR')
    await u.click(await screen.findByRole('button', { name: 'Cancel job' }))
    await u.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Cancel job' }))
    expect(await within(screen.getByRole('dialog')).findByRole('alert')).toHaveTextContent('not permitted to do this (403)')
    deny = false
    await u.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Cancel job' }))
    expect(await screen.findByText('Job cancelled.')).toBeInTheDocument()
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })
})

describe('quarantine viewer', () => {
  const rec = (over = {}) => ({
    id: 'q1', jobId: job().id, sourceId: source().id, entityType: 'CUSTOMER', sourceRecordRef: 'row-7',
    reasonCode: 'INVALID_EMAIL', details: 'bad format', processedAt: '2025-01-01T00:00:01Z', ...over,
  })
  const quarantine = (items: unknown[] = [rec()]): Handler => ({ method, path }) => {
    if (method !== 'GET') return undefined
    if (path === `${ING}/rejected-records`) return { body: page(items) }
    if (path === `${ING}/rejected-records/reasons`) return { body: [{ reasonCode: 'INVALID_EMAIL', count: 3 }, { reasonCode: 'INVALID_DATE', count: 1 }] }
    return undefined
  }

  it('lists quarantined records with links to the run and source, and filters through the API', async () => {
    const f = installFetch(roleBackend('OPERATOR'), quarantine(), reads())
    renderApp('/quarantine')
    const u = await signIn('OPERATOR')
    expect(await screen.findByText('INVALID_EMAIL', { selector: 'td' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: job().id.slice(0, 8) })).toHaveAttribute('href', `/runs/${job().id}`)
    expect(screen.getByRole('link', { name: 'demo-customers' })).toHaveAttribute('href', `/sources/${source().id}`)
    expect(screen.queryByRole('button', { name: /replay/i })).not.toBeInTheDocument() // no per-record action exists

    await u.selectOptions(screen.getByLabelText('Reason'), 'INVALID_DATE')
    await waitFor(() => expect(f.calls.some((c) => c.path === `${ING}/rejected-records` && c.search.get('reasonCode') === 'INVALID_DATE')).toBe(true))
    await u.selectOptions(screen.getByLabelText('Entity type'), 'ORDER')
    await waitFor(() => expect(f.calls.some((c) => c.search.get('entityType') === 'ORDER')).toBe(true))
  })

  it('explains an empty result, with and without filters', async () => {
    installFetch(roleBackend('ADMIN'), quarantine([]), reads())
    renderApp('/quarantine')
    const u = await signIn('ADMIN')
    expect(await screen.findByText('Nothing is quarantined')).toBeInTheDocument()
    await u.selectOptions(screen.getByLabelText('Reason'), 'INVALID_EMAIL')
    expect(await screen.findByText('No quarantined records match these filters')).toBeInTheDocument()
  })

  it('shows a server error with correlation id and a retry', async () => {
    let fail = true
    installFetch(roleBackend('OPERATOR'), ({ method, path }) => {
      if (method === 'GET' && path === `${ING}/rejected-records` && fail) return err(500, 'INTERNAL_ERROR', 'Something went wrong')
      return undefined
    }, quarantine(), reads())
    renderApp('/quarantine')
    const u = await signIn('OPERATOR')
    expect(await screen.findByRole('alert')).toHaveTextContent('Something went wrong')
    expect(screen.getByText('cid-123')).toBeInTheDocument()
    fail = false
    await u.click(screen.getByRole('button', { name: 'Retry' }))
    expect(await screen.findByText('INVALID_EMAIL', { selector: 'td' })).toBeInTheDocument()
  })

  it('does not call the API or show the menu entry for viewers', async () => {
    const f = installFetch(roleBackend('VIEWER'), quarantine(), reads())
    renderApp('/quarantine')
    await signIn('VIEWER')
    expect(await screen.findByText(/read-only/)).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Quarantine' })).not.toBeInTheDocument()
    expect(f.calls.some((c) => c.path.startsWith(`${ING}/rejected-records`))).toBe(false)
  })

  it('does not send a malformed run id and says what to fix', async () => {
    const f = installFetch(roleBackend('OPERATOR'), quarantine(), reads())
    renderApp('/quarantine?jobId=not-a-uuid')
    await signIn('OPERATOR')
    expect(await screen.findByText('Enter a full run ID (UUID).')).toBeInTheDocument()
    expect(f.calls.some((c) => c.path === `${ING}/rejected-records` && c.search.has('jobId'))).toBe(false)
  })
})

describe('keyboard and screen-reader navigation', () => {
  it('offers a skip link that moves focus to the main content', async () => {
    installFetch(roleBackend('OPERATOR'), reads())
    renderApp('/')
    const u = await signIn('OPERATOR')
    const skip = screen.getByRole('link', { name: 'Skip to main content' })
    expect(skip).toHaveAttribute('href', '#main')
    await u.click(skip)
    expect(screen.getByRole('main')).toHaveFocus()
  })

  it('gives every page its own title and moves focus to the content after in-app navigation', async () => {
    installFetch(roleBackend('OPERATOR'), reads())
    renderApp('/')
    const u = await signIn('OPERATOR')
    expect(document.title).toBe('Overview · PIDEA Console')
    await u.click(screen.getByRole('link', { name: 'Runs' }))
    await waitFor(() => expect(document.title).toBe('Runs · PIDEA Console'))
    expect(screen.getByRole('main')).toHaveFocus()
    await u.click(screen.getByRole('link', { name: 'Schedules' }))
    await waitFor(() => expect(document.title).toBe('Schedules · PIDEA Console'))
  })
})
