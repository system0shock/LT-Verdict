import { defineConfig } from '@playwright/test'

export default defineConfig({
  testDir: '.',
  testMatch: 'diagnostics.spec.ts',
  workers: 1,
  webServer: { command: 'npx vite --host 127.0.0.1 --port 18474', url: 'http://127.0.0.1:18474', cwd: '..' },
  use: { baseURL: 'http://127.0.0.1:18474' },
})
