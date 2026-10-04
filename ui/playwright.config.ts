import { defineConfig } from '@playwright/test'
import { e2ePort, oldShellStorageState } from './e2e/e2e-env'

const port = e2ePort(18473)
const baseURL = `http://127.0.0.1:${port}`

export default defineConfig({
  testDir: './e2e',
  workers: 1,
  webServer: {
    command: 'npm run e2e:server',
    url: `${baseURL}/api/bootstrap`,
    timeout: 120_000,
    reuseExistingServer: false,
  },
  use: {
    baseURL,
    storageState: oldShellStorageState(baseURL),
  },
})
