import { defineConfig } from '@playwright/test'

/**
 * End-to-end tests run against a REAL stack (API + PostgreSQL + worker), never mocks.
 * Start an isolated stack first (run ../scripts/e2e.sh). Credentials come from the environment only:
 * PIDEA_E2E_ADMIN_PASSWORD, PIDEA_E2E_OPERATOR_PASSWORD, PIDEA_E2E_VIEWER_PASSWORD.
 */
const apiTarget = process.env.PIDEA_API_TARGET ?? 'http://127.0.0.1:18080'
const port = Number(process.env.PIDEA_E2E_WEB_PORT ?? 5174)

export default defineConfig({
  testDir: './e2e',
  timeout: 90_000,
  expect: { timeout: 15_000 },
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: [['list']],
  use: { baseURL: `http://127.0.0.1:${port}`, trace: 'retain-on-failure' },
  webServer: {
    command: `npx vite --host 127.0.0.1 --port ${port} --strictPort`,
    url: `http://127.0.0.1:${port}`,
    reuseExistingServer: false,
    env: { PIDEA_API_TARGET: apiTarget },
    timeout: 180_000,
  },
})
