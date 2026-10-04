import { defineConfig } from '@playwright/test'
import { e2ePort, oldShellStorageState } from './e2e/e2e-env'

const port = e2ePort(4173)
const baseURL = `http://127.0.0.1:${port}`

export default defineConfig({
  testDir: './e2e',
  testMatch: '**/online-sources.spec.ts',
  webServer: { command: `npm exec vite -- --host 127.0.0.1 --port ${port}`, url: baseURL, reuseExistingServer: false },
  use: { baseURL, storageState: oldShellStorageState(baseURL) },
})
