import { defineConfig } from '@playwright/test'

export default defineConfig({
  testDir: '.',
  testMatch: /deep(-adapters)?\.spec\.ts/,
  workers: 1,
  webServer: { command: 'npx vite --host 127.0.0.1 --port 18476', url: 'http://127.0.0.1:18476', cwd: '..' },
  use: { baseURL: 'http://127.0.0.1:18476' },
})
