import { expect, test, type Page } from '@playwright/test'

const firstPageRun = { run_id: `jmeter_jtl_csv-${'1'.repeat(64)}`, source_type: 'jmeter_jtl_csv', sha256: '1'.repeat(64), size_bytes: 100, original_filename: 'early.jtl' }
const activeRun = { run_id: `jmeter_jtl_csv-${'e'.repeat(64)}`, source_type: 'jmeter_jtl_csv', sha256: 'e'.repeat(64), size_bytes: 100, original_filename: 'late.jtl' }
const processing = { job_id: 'job-1', state: 'PROCESSING', processed_bytes: 10, total_bytes: 100, run_id: activeRun.run_id, analysis_id: null, diagnostic: null }

async function fixtureApi(page: Page, activeJobs: 'active' | 'fail' | 'runs-fail' | 'slow', gate: Promise<void> = Promise.resolve()) {
  let cancelled = false
  await page.route('**/api/**', async (route) => {
    const url = new URL(route.request().url())
    const path = url.pathname
    const method = route.request().method()
    let status = 200
    let body: unknown
    if (path === '/api/bootstrap') body = { csrf_token: 'ui-test', max_upload_bytes: 1000000 }
    else if (method === 'GET' && path === '/api/jenkins') body = { profiles: [] }
    else if (method === 'GET' && path === '/api/grafana') body = { profiles: [] }
    else if (path === '/api/sources') body = { profiles: [] }
    else if (path === '/api/baseline') body = { baseline: null }
    else if (path === '/api/runs') {
      if (activeJobs === 'runs-fail' && url.searchParams.get('after')) {
        status = 500
        body = { error: { code: 'TEST_FAILURE', message: 'Runs page failed', details: [] } }
      } else {
        body = url.searchParams.get('after')
          ? { runs: [activeRun], next_after: null }
          : { runs: [firstPageRun], next_after: firstPageRun.run_id }
      }
    } else if (method === 'GET' && path === '/api/jobs') {
      await gate
      if (activeJobs === 'fail') {
        status = 500
        body = { error: { code: 'TEST_FAILURE', message: 'Active job listing failed', details: [] } }
      } else body = { jobs: [processing] }
    } else if (method === 'GET' && path === '/api/jobs/job-1') body = cancelled || activeJobs === 'runs-fail' ? { ...processing, state: 'CANCELLED' } : processing
    else if (method === 'DELETE' && path === '/api/jobs/job-1') {
      cancelled = true
      body = { ...processing, state: 'CANCELLED' }
    } else if (path.endsWith('/analyses')) body = { analyses: [], next_after: null }
    else throw new Error(`Unexpected UI request ${method} ${path}`)
    await route.fulfill({ status, json: body })
  })
}

test('restores an active job whose run is on a later runs page and cancels it', async ({ page }) => {
  await fixtureApi(page, 'active')
  await page.goto('/')

  await expect(page.locator('#job-status')).toContainText('PROCESSING')
  await expect(page.getByRole('button', { name: 'Cancel analysis' })).toBeVisible()
  await expect(page.getByTestId('run-list').locator('button[aria-pressed="true"]')).toContainText('late.jtl')
  await expect(page.getByTestId('run-list').getByRole('button', { name: /early\.jtl/ })).toBeDisabled()

  await page.getByRole('button', { name: 'Cancel analysis' }).click()
  await expect(page.locator('#job-status')).toContainText('CANCELLED')
  await expect(page.getByRole('button', { name: 'Cancel analysis' })).toHaveCount(0)
  await expect(page.getByTestId('run-list').getByRole('button', { name: /early\.jtl/ })).toBeEnabled()
})

test('keeps the run list usable and reports a failed active job listing', async ({ page }) => {
  await fixtureApi(page, 'fail')
  await page.goto('/')

  await expect(page.getByRole('alert')).toContainText('Active job listing failed')
  await expect(page.getByTestId('run-list')).toContainText('early.jtl')
  await expect(page.locator('#job-status')).toHaveCount(0)
})

test('keeps polling the restored job when a later runs page fails to load', async ({ page }) => {
  await fixtureApi(page, 'runs-fail')
  await page.goto('/')

  await expect(page.getByRole('alert')).toContainText('Runs page failed')
  await expect(page.locator('#job-status')).toContainText('CANCELLED')
})

test('does not override a run selected while the active job listing is pending', async ({ page }) => {
  let release: () => void = () => {}
  const gate = new Promise<void>((resolve) => { release = resolve })
  await fixtureApi(page, 'slow', gate)
  await page.goto('/')

  await page.getByTestId('run-list').getByRole('button', { name: /early\.jtl/ }).click()
  await expect(page.getByTestId('run-list').locator('button[aria-pressed="true"]')).toContainText('early.jtl')
  const listing = page.waitForResponse((response) => response.url().endsWith('/api/jobs?state=active'))
  release()
  await listing
  await page.evaluate(() => new Promise((resolve) => setTimeout(resolve, 300)))

  await expect(page.locator('#job-status')).toHaveCount(0)
  await expect(page.getByTestId('run-list').locator('button[aria-pressed="true"]')).toContainText('early.jtl')
})
