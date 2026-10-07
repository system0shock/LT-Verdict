import { expect, test, type Page } from '@playwright/test'
import { JOB_LABELS, SETUP_LABELS } from '../src/shell/labels'

const run = { run_id: `jmeter_jtl_csv-${'b'.repeat(64)}`, source_type: 'jmeter_jtl_csv', sha256: 'b'.repeat(64), size_bytes: 1, original_filename: 'big.jtl' }
const failed = { job_id: 'job-1', state: 'FAILED', processed_bytes: 1, total_bytes: 1, run_id: run.run_id, analysis_id: null, diagnostic: { code: 'TEST_DONE', message: 'finished by the test' } }

interface Fixture {
  uploads: () => number
  jobPosts: () => number
}

// The first POST /api/inputs never answers (a slow upload); later ones are accepted.
async function fixtureApi(page: Page): Promise<Fixture> {
  let uploads = 0
  let jobPosts = 0
  await page.route('**/api/**', async (route) => {
    const url = new URL(route.request().url())
    const path = url.pathname
    const method = route.request().method()
    if (path === '/api/bootstrap') return route.fulfill({ json: { csrf_token: 'ui-test', max_upload_bytes: 1000000 } })
    if (method === 'GET' && (path === '/api/jenkins' || path === '/api/grafana' || path === '/api/sources')) return route.fulfill({ json: { profiles: [] } })
    if (path === '/api/baseline') return route.fulfill({ json: { baseline: null } })
    if (path === '/api/runs') return route.fulfill({ json: { runs: [], next_after: null } })
    if (method === 'GET' && path === '/api/jobs') return route.fulfill({ json: { jobs: [] } })
    if (path === '/api/inputs') {
      uploads += 1
      if (uploads === 1) return new Promise<void>(() => {})
      return route.fulfill({ status: 201, json: run })
    }
    if (method === 'POST' && path === '/api/jobs') {
      jobPosts += 1
      return route.fulfill({ status: 202, json: { ...failed, state: 'PROCESSING', diagnostic: null } })
    }
    if (method === 'GET' && path === '/api/jobs/job-1') return route.fulfill({ json: failed })
    if (path.endsWith('/analyses')) return route.fulfill({ json: { analyses: [], next_after: null } })
    throw new Error(`Unexpected UI request ${method} ${path}`)
  })
  return { uploads: () => uploads, jobPosts: () => jobPosts }
}

for (const scenario of [
  { name: 'legacy', path: '/', analyze: 'Analyze run', cancel: 'Cancel upload', cancelled: 'Upload cancelled', uploading: 'UPLOADING', failed: 'FAILED' },
  { name: 'new shell', path: '/?shell=new', analyze: SETUP_LABELS.startButton, cancel: 'Отменить загрузку', cancelled: 'Загрузка отменена', uploading: JOB_LABELS.uploading, failed: JOB_LABELS.states.FAILED },
]) {
  test(`cancels a pending upload without creating a job (${scenario.name})`, async ({ page }) => {
    await page.addInitScript(() => {
      const aborts = { count: 0 }
      ;(window as unknown as { __xhrAborts: { count: number } }).__xhrAborts = aborts
      const original = XMLHttpRequest.prototype.abort
      XMLHttpRequest.prototype.abort = function (this: XMLHttpRequest) {
        aborts.count += 1
        return original.call(this)
      }
    })
    const api = await fixtureApi(page)
    await page.goto(scenario.path)
    await page.getByTestId('input-file').setInputFiles({ name: 'big.jtl', mimeType: 'text/plain', buffer: Buffer.from('x') })
    await page.getByRole('button', { name: scenario.analyze }).click()

    await expect(page.locator('#job-status')).toContainText(scenario.uploading)
    await expect(page.getByTestId('input-file')).toBeDisabled()
    await expect.poll(api.uploads).toBe(1)

    await page.getByRole('button', { name: scenario.cancel }).click()

    await expect(page.getByRole('button', { name: scenario.cancel })).toHaveCount(0)
    await expect(page.locator('#job-status')).toContainText(scenario.cancelled)
    await expect(page.getByRole('alert')).toHaveCount(0)
    await expect(page.getByTestId('input-file')).toBeEnabled()
    await expect(page.getByTestId('input-file')).toBeFocused()
    await expect(page.getByRole('button', { name: scenario.analyze })).toBeEnabled()
    expect(await page.evaluate(() => (window as unknown as { __xhrAborts: { count: number } }).__xhrAborts.count)).toBe(1)
    expect(api.jobPosts()).toBe(0)

    await page.getByRole('button', { name: scenario.analyze }).click()
    await expect(page.locator('#job-status')).toContainText(scenario.failed)
    await expect(page.locator('#job-status')).not.toContainText(scenario.cancelled)
    expect(api.uploads()).toBe(2)
    expect(api.jobPosts()).toBe(1)
  })
}
