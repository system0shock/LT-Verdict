import { expect, test, type Page } from '@playwright/test'

const analysisId = 'a'.repeat(64)
const run = {
  run_id: `jmeter_jtl_csv-${'e'.repeat(64)}`,
  source_type: 'jmeter_jtl_csv',
  sha256: 'e'.repeat(64),
  size_bytes: 100,
  original_filename: 'active.jtl',
}
const processing = {
  job_id: 'job-1',
  state: 'PROCESSING',
  processed_bytes: 10,
  total_bytes: 100,
  run_id: run.run_id,
  analysis_id: null,
  diagnostic: null,
}
const overall = {
  id: 'm-overall',
  type: 'metric_summary',
  scope: { kind: 'overall' },
  sample_count: 100,
  error_count: 0,
  error_rate_ratio: { numerator: 0, denominator: 100 },
  throughput_rps: { numerator: 100000, denominator: 1000 },
  latency_ms: { p50: 10, p95: 20, p99: 30, max: 40 },
}
const result = {
  schema_version: 'analysis-result.v1',
  run_id: run.run_id,
  analysis_mode: 'standard',
  run_validity: 'VALID',
  analysis_coverage: { status: 'COMPLETE', reasons: [] },
  findings: [],
  policy_verdict: 'NO_POLICY',
  evidence: [overall],
}

type CancelState = 'CANCELLED' | 'COMPLETE' | 'PROCESSING'

async function fixtureApi(page: Page, cancelState: CancelState, completeAfterPoll = false, staleFirstDelete: Promise<void> | null = null) {
  const requests: string[] = []
  let deletes = 0
  let completeOnNextPoll = false
  let currentJob: unknown = processing
  await page.route('**/api/**', async (route) => {
    const url = new URL(route.request().url())
    const path = url.pathname
    const method = route.request().method()
    requests.push(`${method} ${path}`)
    let body: unknown
    if (path === '/api/bootstrap') body = { csrf_token: 'ui-test', max_upload_bytes: 1000000 }
    else if (method === 'GET' && path === '/api/jenkins') body = { profiles: [] }
    else if (method === 'GET' && path === '/api/grafana') body = { profiles: [] }
    else if (path === '/api/sources') body = { profiles: [] }
    else if (path === '/api/baseline') body = { baseline: null }
    else if (method === 'GET' && path === '/api/runs') body = { runs: [run], next_after: null }
    else if (method === 'GET' && path === '/api/jobs') {
      if (url.searchParams.get('state') !== 'active') throw new Error(`Unexpected jobs query ${url.search}`)
      body = { jobs: [processing] }
    } else if (method === 'GET' && path === '/api/jobs/job-1') {
      if (completeOnNextPoll) currentJob = { ...processing, state: 'COMPLETE', analysis_id: analysisId }
      completeOnNextPoll = false
      body = currentJob
    } else if (method === 'DELETE' && path === '/api/jobs/job-1') {
      deletes += 1
      if (staleFirstDelete && deletes === 1) {
        await staleFirstDelete
        body = processing
      } else {
        completeOnNextPoll = completeAfterPoll
        currentJob = cancelState === 'COMPLETE'
          ? { ...processing, state: 'COMPLETE', analysis_id: analysisId }
          : { ...processing, state: cancelState }
        body = currentJob
      }
    } else if (method === 'GET' && /^\/api\/runs\/[^/]+\/analyses$/.test(path)) {
      body = { analyses: [{ analysis_id: analysisId, policy_sha256: 'c'.repeat(64), policy_verdict: 'NO_POLICY', run_validity: 'VALID' }], next_after: null }
    } else if (method === 'GET' && /^\/api\/runs\/[^/]+\/analyses\/[^/]+\/result$/.test(path)) body = result
    else if (method === 'GET' && /^\/api\/runs\/[^/]+\/analyses\/[^/]+\/buckets$/.test(path)) body = { buckets: [], next_from_ms: null }
    else if (method === 'GET' && /^\/api\/runs\/[^/]+\/analyses\/[^/]+\/advice$/.test(path)) body = { advice: null, job: null }
    else throw new Error(`Unexpected UI request ${method} ${path}`)
    await route.fulfill({ json: body })
  })
  return requests
}

async function cancelActiveJob(page: Page) {
  await page.goto('/')
  await expect(page.locator('#job-status')).toContainText('PROCESSING')
  await page.getByRole('button', { name: 'Cancel analysis' }).click()
}

test('loads a result returned as complete while cancellation races with publication', async ({ page }) => {
  const requests = await fixtureApi(page, 'COMPLETE')
  await cancelActiveJob(page)

  const resultRequest = `GET /api/runs/${run.run_id}/analyses/${analysisId}/result`
  await expect.poll(() => requests.includes(resultRequest)).toBe(true)
  await expect(page.locator('#verdict')).toBeVisible()
  await expect(page.getByText(/^Completed /)).toBeVisible()
  await expect(page.locator('#job-status')).toContainText('COMPLETE')
  await expect(page.getByRole('button', { name: 'Cancel analysis' })).toHaveCount(0)
})

test('keeps a cancelled job terminal without loading a result', async ({ page }) => {
  const requests = await fixtureApi(page, 'CANCELLED')
  await cancelActiveJob(page)

  await expect(page.locator('#job-status')).toContainText('CANCELLED')
  await expect(page.getByRole('button', { name: 'Cancel analysis' })).toHaveCount(0)
  expect(requests.some((request) => request.endsWith('/result'))).toBe(false)
})

test('resumes polling when cancellation returns processing before publication completes', async ({ page }) => {
  const requests = await fixtureApi(page, 'PROCESSING', true)
  await cancelActiveJob(page)

  const resultRequest = `GET /api/runs/${run.run_id}/analyses/${analysisId}/result`
  await expect.poll(() => requests.includes(resultRequest)).toBe(true)
  await expect(page.locator('#verdict')).toBeVisible()
  await expect(page.getByText(/^Completed /)).toBeVisible()
  await expect(page.locator('#job-status')).toContainText('COMPLETE')
  await expect(page.getByRole('button', { name: 'Cancel analysis' })).toHaveCount(0)
})

test('ignores a stale cancel response that arrives after a newer one loaded the result', async ({ page }) => {
  let release: () => void = () => {}
  const gate = new Promise<void>((resolve) => { release = resolve })
  const requests = await fixtureApi(page, 'COMPLETE', false, gate)
  await page.goto('/')
  await expect(page.locator('#job-status')).toContainText('PROCESSING')
  const cancel = page.getByRole('button', { name: 'Cancel analysis' })
  await cancel.click()
  await cancel.click()

  const resultRequest = `GET /api/runs/${run.run_id}/analyses/${analysisId}/result`
  await expect.poll(() => requests.includes(resultRequest)).toBe(true)
  await expect(page.locator('#job-status')).toContainText('COMPLETE')
  const stale = page.waitForResponse((response) => response.request().method() === 'DELETE')
  release()
  await stale
  await page.evaluate(() => new Promise((resolve) => setTimeout(resolve, 200)))

  await expect(page.locator('#job-status')).toContainText('COMPLETE')
  await expect(page.locator('#verdict')).toBeVisible()
  await expect(cancel).toHaveCount(0)
})