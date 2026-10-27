import AxeBuilder from '@axe-core/playwright'
import { expect, test, type Page } from '@playwright/test'
import { apiAs, signIn, spaGoto, TERMINAL, uniq } from './helpers'

/**
 * Quarantine viewer and accessibility checks against the REAL stack. The data is created through the real API
 * (a source, then a job with one valid and two invalid records that the real worker processes).
 *
 * Automated axe checks catch a useful subset of WCAG 2.0/2.1 A and AA problems (roles, labels, names, contrast,
 * landmarks, duplicate ids...). They do NOT prove accessibility: keyboard flow, focus order, screen-reader wording
 * and cognitive load still need manual review.
 */
test.describe.configure({ mode: 'serial' })

const sourceName = uniq('a11y-src')
let sourceId = ''
let runId = ''

test.beforeAll(async ({ request }) => {
  const admin = apiAs(request, 'admin')
  const operator = apiAs(request, 'operator')
  const created = await admin('POST', '/api/v1/integration-sources', { name: sourceName, type: 'REST_PUSH', config: {} })
  expect(created.status()).toBe(201)
  sourceId = (await created.json()).id
  const submitted = await operator('POST', '/api/v1/ingestion/jobs', {
    sourceId, entityType: 'CUSTOMER',
    records: [
      { customer_id: uniq('V'), full_name: 'Valid', email: 'v@example.test', created_at: '2025-01-01T00:00:00Z' },
      { customer_id: uniq('E'), full_name: 'Bad Email', email: 'not-an-email', created_at: '2025-01-01T00:00:00Z' },
      { customer_id: uniq('D'), full_name: 'Bad Date', email: 'd@example.test', created_at: 'yesterday-ish' },
    ],
  }, { 'Idempotency-Key': uniq('a11y-key') })
  expect(submitted.status()).toBe(202)
  runId = (await submitted.json()).id
  await expect.poll(async () => (await (await operator('GET', `/api/v1/ingestion/jobs/${runId}`)).json()).status,
    { timeout: 60_000 }).toMatch(TERMINAL)
})

async function expectNoViolations(page: Page, name: string) {
  const results = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa']).analyze()
  const summary = results.violations.map((v) =>
    `${v.id} (${v.impact}): ${v.help}\n` + v.nodes.slice(0, 3).map((n) => `    ${n.target.join(' ')}  ${n.failureSummary?.split('\n')[1] ?? ''}`).join('\n'))
  expect(summary, `axe violations on ${name}:\n${summary.join('\n')}`).toEqual([])
}

// ------------------------------------------------------------ quarantine viewer

test('operator: finds the quarantined records of a real run, filters them and jumps to the run', async ({ page }) => {
  await signIn(page, 'operator')
  await page.getByRole('navigation', { name: 'Main' }).getByRole('link', { name: 'Quarantine' }).click()
  await expect(page.getByRole('heading', { name: 'Quarantine' })).toBeVisible()

  const table = page.getByRole('table', { name: 'Quarantined records' })
  await page.getByLabel('Run ID').fill(runId)
  await expect(table.getByRole('row')).toHaveCount(3) // header + the two rejected records
  await expect(table).toContainText('INVALID_EMAIL')
  await expect(table).toContainText('INVALID_DATE')
  await expect(table).not.toContainText('Valid') // accepted records are never listed

  await page.getByLabel('Reason').selectOption('INVALID_DATE')
  await expect(table.getByRole('row')).toHaveCount(2)
  await expect(table).not.toContainText('INVALID_EMAIL')

  await page.getByRole('button', { name: 'Clear filters' }).click()
  await page.getByLabel('Run ID').fill(runId)
  await table.getByRole('link', { name: runId.slice(0, 8) }).first().click()
  await expect(page).toHaveURL(new RegExp(`/runs/${runId}$`))
})

test('quarantine: an empty filter result is explained, not blank', async ({ page }) => {
  await signIn(page, 'operator')
  await spaGoto(page, '/quarantine')
  await page.getByLabel('Run ID').fill('00000000-0000-0000-0000-000000000000')
  await expect(page.getByText('No quarantined records match these filters')).toBeVisible()
})

test('viewer: no menu entry, an explanation, and the server itself answers 403', async ({ page, request }) => {
  await signIn(page, 'viewer')
  await expect(page.getByRole('navigation', { name: 'Main' }).getByRole('link', { name: 'Quarantine' })).toHaveCount(0)
  await spaGoto(page, '/quarantine')
  await expect(page.getByText(/read-only/)).toBeVisible()
  const viewer = apiAs(request, 'viewer')
  expect((await viewer('GET', '/api/v1/ingestion/rejected-records')).status()).toBe(403)
  expect((await viewer('GET', '/api/v1/ingestion/rejected-records/reasons')).status()).toBe(403)
})

test('the API never returns raw payloads for quarantined records', async ({ request }) => {
  const operator = apiAs(request, 'operator')
  const body = await (await operator('GET', `/api/v1/ingestion/rejected-records?jobId=${runId}`)).json()
  expect(body.totalItems).toBe(2)
  const text = JSON.stringify(body)
  expect(text).not.toContain('not-an-email')
  expect(text).not.toContain('yesterday-ish')
  for (const item of body.items) expect(Object.keys(item).sort()).toEqual(
    ['details', 'entityType', 'id', 'jobId', 'processedAt', 'reasonCode', 'sourceId', 'sourceRecordRef'])
})

// -------------------------------------------------------------- accessibility

test('a11y: sign-in page', async ({ page }) => {
  await page.goto('/')
  await expect(page.getByLabel('Username')).toBeVisible()
  await expectNoViolations(page, 'sign-in')
})

test('a11y: operator pages', async ({ page }) => {
  await signIn(page, 'operator')
  const pages: [string, string, string][] = [
    ['/', 'Overview', 'Overview'],
    ['/sources', 'Sources', 'Sources'],
    [`/sources/${sourceId}`, sourceName, 'source detail'],
    ['/runs', 'Runs', 'Runs'],
    [`/runs/${runId}`, 'Run ', 'run detail'],
    ['/quarantine', 'Quarantine', 'Quarantine'],
    ['/schedules', 'Schedules', 'Schedules'],
  ]
  for (const [path, heading, name] of pages) {
    await spaGoto(page, path)
    await expect(page.getByRole('heading', { level: 1, name: new RegExp(heading) })).toBeVisible()
    await expect(page.getByRole('status').filter({ hasText: /^Loading/ })).toHaveCount(0, { timeout: 20_000 })
    await expectNoViolations(page, name)
  }
})

test('a11y: admin source creation form and the viewer role banner', async ({ page }) => {
  await signIn(page, 'admin')
  await spaGoto(page, '/sources')
  await expect(page.getByRole('heading', { level: 1, name: 'Sources' })).toBeVisible()
  await expectNoViolations(page, 'sources (admin)')
  await page.getByRole('button', { name: 'Sign out' }).click()

  await signIn(page, 'viewer')
  await spaGoto(page, `/runs/${runId}`)
  await expect(page.getByText(/read-only/)).toBeVisible()
  await expectNoViolations(page, 'run detail (viewer)')
})

test('a11y: keyboard users can reach the main navigation and the page content', async ({ page }) => {
  await signIn(page, 'operator')
  await page.keyboard.press('Tab')
  // the first tab stop is a way to skip the repeated navigation, and it works
  const first = await page.evaluate(() => document.activeElement?.textContent?.trim() ?? '')
  expect(first).toBe('Skip to main content')
  await page.keyboard.press('Enter')
  await expect(page.getByRole('main')).toBeFocused()
  await page.getByRole('navigation', { name: 'Main' }).getByRole('link', { name: 'Runs' }).focus()
  await page.keyboard.press('Enter')
  await expect(page.getByRole('heading', { level: 1, name: 'Runs' })).toBeVisible()
})
