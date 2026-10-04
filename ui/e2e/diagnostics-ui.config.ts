import { defineConfig } from '@playwright/test'
import { e2ePort, oldShellStorageState } from './e2e-env'

const port = e2ePort(18474)
const baseURL = `http://127.0.0.1:${port}`

export default defineConfig({
  testDir: '.',
  testMatch: 'diagnostics.spec.ts',
  workers: 1,
  webServer: { command: `npx vite --host 127.0.0.1 --port ${port}`, url: baseURL, cwd: '..' },
  use: { baseURL, storageState: oldShellStorageState(baseURL) },
})
