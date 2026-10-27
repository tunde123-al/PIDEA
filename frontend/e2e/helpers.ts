import { expect, type APIRequestContext, type Page } from '@playwright/test'

export type User = 'admin' | 'operator' | 'viewer'

export function password(user: User): string {
  const v = process.env[`PIDEA_E2E_${user.toUpperCase()}_PASSWORD`]
  if (!v) throw new Error(`Set PIDEA_E2E_${user.toUpperCase()}_PASSWORD to run the e2e suite`)
  return v
}

export const apiBase = process.env.PIDEA_API_TARGET ?? 'http://127.0.0.1:18080'

export async function signIn(page: Page, user: User) {
  await page.goto('/')
  await page.getByLabel('Username').fill(user)
  await page.getByLabel('Password').fill(password(user))
  await page.getByRole('button', { name: 'Sign in' }).click()
  await expect(page.getByRole('navigation', { name: 'Main' })).toBeVisible()
  await expect(page.getByText(`Role: ${user.toUpperCase()}`)).toBeVisible()
}

/** Direct API call as a given user (used to assert real backend status codes). */
export function apiAs(request: APIRequestContext, user: User) {
  const auth = 'Basic ' + Buffer.from(`${user}:${password(user)}`).toString('base64')
  return (method: string, path: string, data?: unknown, headers: Record<string, string> = {}) =>
    request.fetch(`${apiBase}${path}`, { method, data, headers: { Authorization: auth, ...headers } })
}

export const TERMINAL = /SUCCEEDED|PARTIALLY SUCCEEDED|FAILED|CANCELLED/
export const uniq = (p: string) => `${p}-${Date.now().toString(36)}`

/** In-app navigation. page.goto() reloads the page, which (by design) drops the in-memory credentials. */
export async function spaGoto(page: Page, path: string) {
  await page.evaluate((p) => {
    history.pushState({}, '', p)
    dispatchEvent(new PopStateEvent('popstate'))
  }, path)
}
