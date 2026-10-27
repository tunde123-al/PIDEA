import { expect, test } from '@playwright/test'
import { apiAs, signIn, spaGoto, TERMINAL, uniq } from './helpers'

const sourceName = uniq('e2e-src')
const csvName = uniq('e2e-csv')
let sourceId = ''
let runId = ''

test.describe.configure({ mode: 'serial' })

test('login: wrong password is rejected with a clear message (real 401)', async ({ page }) => {
  await page.goto('/')
  await page.getByLabel('Username').fill('admin')
  await page.getByLabel('Password').fill('definitely-wrong-password')
  await page.getByRole('button', { name: 'Sign in' }).click()
  await expect(page.getByRole('alert')).toContainText('Invalid username or password.')
  await expect(page.getByRole('navigation', { name: 'Main' })).toHaveCount(0)
})

test('login: credentials are never written to web storage; reload signs out', async ({ page }) => {
  await signIn(page, 'admin')
  const stored = await page.evaluate(() => JSON.stringify({ ...localStorage, ...sessionStorage }) + document.cookie)
  expect(stored).not.toMatch(/Basic|admin:/)
  expect(await page.evaluate(() => localStorage.length + sessionStorage.length)).toBe(0)
  await page.reload()
  await expect(page.getByLabel('Username')).toBeVisible()
})

test('admin: creates a source, and a duplicate name is refused by the real backend', async ({ page }) => {
  await signIn(page, 'admin')
  await page.getByRole('link', { name: 'Sources' }).click()
  await page.getByLabel('Name', { exact: true }).fill(sourceName)
  await page.getByRole('button', { name: 'Create source' }).click()
  await expect(page.getByText(/created\./)).toBeVisible()
  await expect(page.getByRole('link', { name: sourceName })).toBeVisible()

  await page.getByLabel('Name', { exact: true }).fill(sourceName)
  await page.getByRole('button', { name: 'Create source' }).click()
  await expect(page.getByRole('alert')).toBeVisible()
  await expect(page.getByRole('alert')).toContainText(/exist|taken|already|conflict/i)

  await page.getByRole('link', { name: sourceName }).click()
  sourceId = page.url().split('/sources/')[1]
  expect(sourceId).toMatch(/^[0-9a-f-]{36}$/)
})

test('operator: submits JSON, worker processes it, run details show attempts and rejections', async ({ page }) => {
  await signIn(page, 'operator')
  await spaGoto(page, `/sources/${sourceId}`)
  const records = [
    { customer_id: uniq('C'), full_name: 'Valid Customer', email: 'valid@example.test', created_at: '2025-01-01T00:00:00Z' },
    { customer_id: uniq('C'), full_name: 'Invalid Customer', email: 'not-an-email', created_at: '2025-01-01T00:00:00Z' },
  ]
  await page.getByLabel(/Records \(JSON array\)/).fill(JSON.stringify(records))
  await page.getByRole('button', { name: 'Submit records' }).click()

  const progress = page.getByText(/^Job /).locator('..')
  await expect(progress.getByRole('link')).toBeVisible()
  await expect(progress).toContainText(TERMINAL, { timeout: 45_000 }) // the worker picked it up
  await progress.getByRole('link').click()

  await expect(page).toHaveURL(/\/runs\/[0-9a-f-]{36}$/)
  runId = page.url().split('/runs/')[1]
  await expect(page.getByRole('heading', { name: /^Run / })).toBeVisible()
  await expect(page.getByText('Attempt 1')).toBeVisible()
  await expect(page.getByRole('heading', { name: 'Rejected records' })).toBeVisible()
  const summary = page.getByRole('heading', { name: 'Summary' }).locator('..')
  await expect(summary.locator('div', { has: page.getByText('Received', { exact: true }) }).last()).toContainText('2')
  await expect(summary.locator('div', { has: page.getByText('Accepted', { exact: true }) }).last()).toContainText('1')
  await expect(summary.locator('div', { has: page.getByText('Rejected', { exact: true }) }).last()).toContainText('1')
})

test('runs list shows the run and honours the filters', async ({ page }) => {
  await signIn(page, 'admin')
  await page.getByRole('link', { name: 'Runs' }).click()
  await expect(page.getByRole('link', { name: runId.slice(0, 8) })).toBeVisible()
  await page.getByLabel('Source').selectOption({ label: sourceName })
  await expect(page.getByRole('table', { name: 'Ingestion runs' }).getByRole('row')).toHaveCount(2) // header + 1
  await page.getByLabel('Status').selectOption('CANCELLED')
  await expect(page.getByText('No runs match these filters')).toBeVisible()
})

test('admin: CSV upload is processed by the worker', async ({ page }) => {
  await signIn(page, 'admin')
  await page.getByRole('link', { name: 'Sources' }).click()
  await page.getByLabel('Name', { exact: true }).fill(csvName)
  await page.getByLabel('Type').selectOption('CSV_FILE')
  await page.getByRole('button', { name: 'Create source' }).click()
  await page.getByRole('link', { name: csvName }).click()
  const csv = `customer_id,full_name,email,created_at\n${uniq('U')},Csv Customer,csv@example.test,2025-01-01T00:00:00Z\n`
  await page.getByLabel(/File \(\.csv\)/).setInputFiles({ name: 'customers.csv', mimeType: 'text/csv', buffer: Buffer.from(csv) })
  await page.getByRole('button', { name: 'Upload file' }).click()
  const progress = page.getByText(/^Job /).locator('..')
  await expect(progress).toContainText(TERMINAL, { timeout: 45_000 })
  await expect(progress).toContainText('SUCCEEDED')
})

test('viewer: read-only UI, no rejections requested, and the server itself returns 403', async ({ page, request }) => {
  const errorsRequests: string[] = []
  page.on('request', (r) => { if (r.url().includes('/errors') && !r.url().includes('0000-0000-0000-000000000000')) errorsRequests.push(r.url()) })
  await signIn(page, 'viewer')
  await page.getByRole('link', { name: 'Sources' }).click()
  await expect(page.getByRole('link', { name: sourceName })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Create source' })).toHaveCount(0)
  await expect(page.getByRole('button', { name: /^(Disable|Enable) source/ })).toHaveCount(0)

  await page.getByRole('link', { name: sourceName }).click()
  await expect(page.getByText(/cannot submit data/)).toBeVisible()
  await expect(page.getByRole('button', { name: 'Submit records' })).toHaveCount(0)

  await page.getByRole('link', { name: 'Runs' }).click()
  await page.getByRole('link', { name: runId.slice(0, 8) }).click()
  await expect(page.getByText('Attempt 1')).toBeVisible()
  await expect(page.getByRole('heading', { name: 'Rejected records' })).toHaveCount(0)
  await expect(page.getByRole('button', { name: 'Replay' })).toHaveCount(0)
  expect(errorsRequests).toEqual([])

  // Hidden UI is not the control: the backend refuses the same operations.
  const viewer = apiAs(request, 'viewer')
  expect((await viewer('GET', `/api/v1/ingestion/jobs/${runId}/errors`)).status()).toBe(403)
  expect((await viewer('POST', '/api/v1/integration-sources', { name: 'viewer-should-fail', type: 'REST_PUSH' })).status()).toBe(403)
  expect((await viewer('POST', `/api/v1/ingestion/jobs/${runId}/cancel`)).status()).toBe(403)
})

test('operator: no source creation UI, and the server returns 403 for it', async ({ page, request }) => {
  await signIn(page, 'operator')
  await page.getByRole('link', { name: 'Sources' }).click()
  await expect(page.getByRole('link', { name: sourceName })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Create source' })).toHaveCount(0)
  const op = apiAs(request, 'operator')
  expect((await op('POST', '/api/v1/integration-sources', { name: 'operator-should-fail', type: 'REST_PUSH' })).status()).toBe(403)
})

test('404 and 409 from the real backend', async ({ page, request }) => {
  await signIn(page, 'admin')
  await spaGoto(page, '/runs/00000000-0000-0000-0000-000000000000')
  await expect(page.getByRole('alert')).toContainText(/not found/i)

  const admin = apiAs(request, 'admin')
  expect((await admin('POST', `/api/v1/ingestion/jobs/${runId}/cancel`)).status()).toBe(409) // already finished
  expect((await admin('POST', `/api/v1/ingestion/jobs/${runId}/replay`)).status()).toBe(409) // not FAILED
})

test('UI offers no Replay/Cancel on a finished job', async ({ page }) => {
  await signIn(page, 'admin')
  await page.getByRole('link', { name: 'Runs' }).click()
  await page.getByRole('link', { name: runId.slice(0, 8) }).click()
  await expect(page.getByRole('heading', { name: /^Run / })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Replay' })).toHaveCount(0)
  await expect(page.getByRole('button', { name: 'Cancel job' })).toHaveCount(0)
})
