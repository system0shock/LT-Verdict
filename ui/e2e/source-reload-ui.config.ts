import { defineConfig } from '@playwright/test'
import { e2ePort } from './e2e-env'

const port = e2ePort(18477)
const baseURL = `http://127.0.0.1:${port}`

export default defineConfig({
  testDir: '.',
  testMatch: /source-reload\.spec\.ts/,
  workers: 1,
  webServer: { command: `node ./node_modules/vite/bin/vite.js --host 127.0.0.1 --port ${port}`, url: baseURL, cwd: '..' },
  use: { baseURL },
})
