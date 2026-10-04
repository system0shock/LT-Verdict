import { defineConfig } from '@playwright/test'

export default defineConfig({
  testDir: './e2e',
  testMatch: '**/online-sources.spec.ts',
  webServer: { command: 'npm exec vite -- --host 127.0.0.1 --port 4173', url: 'http://127.0.0.1:4173', reuseExistingServer: false },
  use: { baseURL: 'http://127.0.0.1:4173', storageState: 'e2e/old-shell.storage.json' },
})
