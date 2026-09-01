import { defineConfig } from '@playwright/test'

export default defineConfig({
  testDir: './e2e',
  workers: 1,
  webServer: {
    command: 'npm run e2e:server',
    url: 'http://127.0.0.1:18473/api/bootstrap',
    timeout: 120_000,
    reuseExistingServer: false,
  },
  use: {
    baseURL: 'http://127.0.0.1:18473',
  },
})
