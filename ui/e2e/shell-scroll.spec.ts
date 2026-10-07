import { expect, test, type Page } from '@playwright/test'
import { JOB_LABELS, SHELL_TABS } from '../src/shell/labels'

const analysisId = 'a'.repeat(64)
const manyRuns = Array.from({ length: 40 }, (_, index) => {
  const hash = String(index).padStart(2, '0').repeat(32)
  return { run_id: `jmeter_jtl_csv-${hash}`, source_type: 'jmeter_jtl_csv', sha256: hash, size_bytes: 100, original_filename: `load-${index}.jtl` }
})
const manyAnalyses = Array.from({ length: 40 }, (_, index) => ({
  analysis_id: String(index).padStart(2, '0').repeat(32), policy_sha256: 'c'.repeat(64), policy_verdict: 'FAIL', run_validity: 'VALID',
}))
const overall = {
  id: 'm-overall', type: 'metric_summary', scope: { kind: 'overall' }, sample_count: 3650, error_count: 88,
  error_rate_ratio: { numerator: 88, denominator: 3650 }, throughput_rps: { numerator: 3650000, denominator: 30000 },
  latency_ms: { p50: 100, p95: 2340, p99: 3000, max: 4000 },
}
const failing = {
  schema_version: 'analysis-result.v1', run_id: manyRuns[0].run_id, analysis_mode: 'standard', run_validity: 'VALID', policy_verdict: 'FAIL',
  analysis_coverage: { status: 'COMPLETE', reasons: [] }, findings: [],
  evidence: [
    overall,
    { id: 'c1', type: 'policy_check', rule_id: 'overall-p95', metric: 'response_time_p95_ms', operator: 'lte', threshold: 2000, status: 'FAIL', metric_evidence_id: 'm-overall', observed: 2340 },
  ],
}

async function fixtureApi(page: Page, options: { job?: () => unknown } = {}) {
  await page.route('**/api/**', async (route) => {
    const path = new URL(route.request().url()).pathname
    const method = route.request().method()
    let body: unknown
    if (path === '/api/bootstrap') body = { csrf_token: 'ui-test', max_upload_bytes: 1000000 }
    else if (method === 'GET' && path === '/api/jobs') body = { jobs: options.job ? [options.job()] : [] }
    else if (method === 'GET' && path === '/api/jobs/job-1') body = options.job?.()
    else if (method === 'GET' && path === '/api/jenkins') body = { profiles: [] }
    else if (method === 'GET' && path === '/api/grafana') body = { profiles: [] }
    else if (method === 'GET' && /^\/api\/runs\/[^/]+\/analyses\/[^/]+\/advice$/.test(path)) body = { advice: null, job: null }
    else if (path === '/api/sources') body = { profiles: [] }
    else if (path === '/api/runs') body = { runs: manyRuns, next_after: null }
    else if (path === '/api/baseline') body = { baseline: null }
    else if (path.endsWith('/analyses')) body = { analyses: manyAnalyses, next_after: null }
    else if (path.endsWith('/result')) body = failing
    else if (path.endsWith('/buckets')) body = { buckets: [], next_from_ms: null }
    else throw new Error(`Unexpected UI request ${method} ${path}`)
    await route.fulfill({ json: body })
  })
}

const scrollY = (page: Page) => page.evaluate(() => Math.round(window.scrollY))

test.use({ viewport: { width: 1280, height: 720 } })

test('switching a tab scrolls the page back to the top', async ({ page }) => {
  await fixtureApi(page)
  await page.goto('/?shell=new')
  await page.getByRole('button', { name: 'load-0.jtl' }).click()
  await page.locator(`button[title="${manyAnalyses[0].analysis_id}"]`).click()
  await expect(page.locator('#verdict')).toBeVisible()

  await page.evaluate(() => window.scrollTo(0, 300))
  expect(await scrollY(page)).toBeGreaterThan(100)
  await page.locator('#shell-tab-tables').dispatchEvent('click')
  await expect(page.locator('#shell-tab-tables')).toHaveAttribute('aria-selected', 'true')
  await expect.poll(() => scrollY(page)).toBe(0)

  await page.evaluate(() => window.scrollTo(0, 300))
  expect(await scrollY(page)).toBeGreaterThan(100)
  await page.locator(`#shell-tab-${SHELL_TABS[0].key}`).dispatchEvent('click')
  await expect.poll(() => scrollY(page)).toBe(0)
})

test('a finished analysis opens with the verdict on the first screen', async ({ page }) => {
  let done = false
  const job = () => ({
    job_id: 'job-1', state: done ? 'COMPLETE' : 'PROCESSING', processed_bytes: 10, total_bytes: 100,
    run_id: manyRuns[0].run_id, analysis_id: done ? analysisId : null, diagnostic: null,
  })
  await fixtureApi(page, { job })
  await page.goto('/?shell=new')
  await expect(page.locator('#job-status')).toContainText(JOB_LABELS.states.PROCESSING)

  await page.evaluate(() => window.scrollTo(0, 400))
  expect(await scrollY(page)).toBeGreaterThan(100)
  done = true
  await expect(page.locator('#verdict')).toBeVisible()

  await expect.poll(() => scrollY(page)).toBe(0)
  await expect(page.locator('#verdict-title')).toBeInViewport()
})

test('the side column keeps its own scroll and a bounded height', async ({ page }) => {
  await fixtureApi(page)
  await page.goto('/?shell=new')
  await page.getByRole('button', { name: 'load-0.jtl' }).click()
  await expect(page.locator('ul.run-list').nth(1).locator('li')).toHaveCount(40)

  const side = page.locator('aside.side-navigation')
  const metrics = await side.evaluate((element) => ({
    height: element.getBoundingClientRect().height,
    position: getComputedStyle(element).position,
    scrolls: element.scrollHeight > element.clientHeight,
    lists: Array.from(element.querySelectorAll('ul.run-list')).map((list) => list.getBoundingClientRect().height),
  }))
  expect(metrics.position).toBe('sticky')
  expect(metrics.height).toBeLessThanOrEqual(720)
  expect(metrics.scrolls).toBe(true)
  for (const height of metrics.lists) expect(height).toBeLessThanOrEqual(720 * 0.3)

  await page.evaluate(() => window.scrollTo(0, 300))
  expect(await scrollY(page)).toBeGreaterThan(100)
  expect(await side.evaluate((element) => Math.round(element.getBoundingClientRect().top))).toBe(0)
})

test('under 960 px the stacked side column does not hide the verdict after a tab switch', async ({ page }) => {
  await page.setViewportSize({ width: 800, height: 720 })
  await fixtureApi(page)
  await page.goto('/?shell=new')
  await page.getByRole('button', { name: 'load-0.jtl' }).click()
  await page.locator(`button[title="${manyAnalyses[0].analysis_id}"]`).click()
  await expect(page.locator('#verdict')).toBeVisible()

  await page.locator('#shell-tab-tables').dispatchEvent('click')
  await expect(page.locator('#shell-tab-tables')).toHaveAttribute('aria-selected', 'true')
  await expect.poll(() => page.locator('.workspace').evaluate((element) => Math.round(element.getBoundingClientRect().top))).toBe(0)
  await page.locator('#shell-tab-overview').dispatchEvent('click')
  await expect(page.locator('#verdict-title')).toBeInViewport()
})
