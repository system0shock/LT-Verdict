import { defineConfig } from '@playwright/test'

// Быстрый прогон карточки вердикта без Gradle: API целиком подменяется в тестах.
export default defineConfig({
  testDir: '.',
  testMatch: /verdict-(summary|first)\.spec\.ts/,
  workers: 1,
  webServer: { command: 'npx vite --host 127.0.0.1 --port 18475', url: 'http://127.0.0.1:18475', cwd: '..' },
  use: { baseURL: 'http://127.0.0.1:18475', storageState: 'e2e/old-shell.storage.json' },
})
