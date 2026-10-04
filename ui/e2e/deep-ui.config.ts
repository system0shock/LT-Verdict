import { defineConfig } from '@playwright/test'
import { e2ePort } from './e2e-env'

const port = e2ePort(18476)
const baseURL = `http://127.0.0.1:${port}`

export default defineConfig({
  testDir: '.',
  testMatch: /deep(-adapters)?\.spec\.ts/,
  workers: 1,
  webServer: { command: `npx vite --host 127.0.0.1 --port ${port}`, url: baseURL, cwd: '..' },
  use: { baseURL },
})
