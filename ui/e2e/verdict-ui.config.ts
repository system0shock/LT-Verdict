import { defineConfig } from '@playwright/test'
import { e2ePort, oldShellStorageState } from './e2e-env'

const port = e2ePort(18475)
const baseURL = `http://127.0.0.1:${port}`

// Быстрый прогон карточки вердикта без Gradle: API целиком подменяется в тестах.
export default defineConfig({
  testDir: '.',
  testMatch: /verdict-(summary|first)\.spec\.ts/,
  workers: 1,
  webServer: { command: `npx vite --host 127.0.0.1 --port ${port}`, url: baseURL, cwd: '..' },
  use: { baseURL, storageState: oldShellStorageState(baseURL) },
})
