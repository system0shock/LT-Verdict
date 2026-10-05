import { expect, test, type Page } from '@playwright/test'
import { NORMALIZED_LABELS } from '../src/shell/labels.tables'

const reference = { run_id: 'normalized-run', analysis_id: 'a'.repeat(64) }
const run = { ...reference, source_type: 'jmeter', sha256: 'b'.repeat(64), size_bytes: 100, original_filename: 'normalized.jtl' }
const overall = {
  id: 'm-overall', type: 'metric_summary', scope: { kind: 'overall' }, sample_count: 90000, error_count: 0,
  error_rate_ratio: { numerator: 0, denominator: 90000 }, throughput_rps: { numerator: 90000000, denominator: 900000 },
  latency_ms: { p50: 20, p95: 90, p99: 120, max: 200 },
}
const result = {
  schema_version: 'analysis-result.v1', run_id: reference.run_id, analysis_mode: 'standard', run_validity: 'VALID', policy_verdict: 'PASS',
  analysis_coverage: { status: 'COMPLETE', reasons: [] }, findings: [],
  evidence: [overall, { id: 'c1', type: 'policy_check', rule_id: 'p95', metric: 'response_time_p95_ms', operator: 'lte', threshold: 1000, status: 'PASS', metric_evidence_id: 'm-overall', observed: 90 }],
}
const makeBuckets = (count: number) => Array.from({ length: count }, (_, index) => ({
  bucket_start_ms: index * 1000, sample_count: 100, error_count: 0, p95_latency_ms: 90, max_latency_ms: 200, hdr_v2_base64: '',
}))

async function fixtureApi(page: Page, counts: number[]) {
  let call = 0
  await page.route('**/api/**', async (route) => {
    const path = new URL(route.request().url()).pathname
    const method = route.request().method()
    let body: unknown
    if (path === '/api/bootstrap') body = { csrf_token: 'ui-test', max_upload_bytes: 1000000 }
    else if (method === 'GET' && path === '/api/jobs') body = { jobs: [] }
    else if (method === 'GET' && path === '/api/jenkins') body = { profiles: [] }
    else if (method === 'GET' && path === '/api/grafana') body = { profiles: [] }
    else if (method === 'GET' && /^\/api\/runs\/[^/]+\/analyses\/[^/]+\/advice$/.test(path)) body = { advice: null, job: null }
    else if (path === '/api/sources') body = { profiles: [] }
    else if (path === '/api/runs') body = { runs: [run], next_after: null }
    else if (path === '/api/baseline') body = { baseline: null }
    else if (path.endsWith('/analyses')) body = { analyses: [{ analysis_id: reference.analysis_id, policy_sha256: 'c'.repeat(64), policy_verdict: 'PASS', run_validity: 'VALID' }], next_after: null }
    else if (path.endsWith('/result')) body = result
    else if (path.endsWith('/buckets')) {
      body = { buckets: makeBuckets(counts[Math.min(call, counts.length - 1)]), next_from_ms: null }
      call += 1
    } else throw new Error(`Unexpected UI request ${path}`)
    await route.fulfill({ json: body })
  })
}

async function open(page: Page, shell: 'new' | 'old', counts: number[]) {
  await fixtureApi(page, counts)
  await page.goto(`/?shell=${shell}`)
  await page.getByRole('button', { name: 'normalized.jtl' }).click()
  await page.locator(`button[title="${reference.analysis_id}"]`).click()
  await expect(page.locator('#verdict')).toBeVisible()
  if (shell === 'new') await page.locator('#shell-tab-tables').click()
  await expect(page.locator('#normalized-data')).toBeVisible()
}

const rows = (page: Page) => page.locator('#normalized-data tbody tr')

test('new shell: 900 time bins start collapsed to 50 rows and grow on request', async ({ page }) => {
  await open(page, 'new', [900])

  await expect(rows(page)).toHaveCount(50)
  await expect(page.getByTestId('bins-shown')).toHaveText(NORMALIZED_LABELS.shown(50, 900))
  await page.getByRole('button', { name: NORMALIZED_LABELS.more(50) }).click()
  await expect(rows(page)).toHaveCount(100)
  await expect(page.getByTestId('bins-shown')).toHaveText(NORMALIZED_LABELS.shown(100, 900))
  await page.getByRole('button', { name: NORMALIZED_LABELS.all(900) }).click()
  await expect(rows(page)).toHaveCount(900)
  await expect(page.getByTestId('bins-shown')).toHaveCount(0)
  await expect(page.getByRole('button', { name: /Показать/ })).toHaveCount(0)
})

test('new shell: the block is in Russian', async ({ page }) => {
  await open(page, 'new', [900])

  const block = page.locator('#normalized-data')
  await expect(block.getByRole('heading', { level: 2 })).toHaveText(NORMALIZED_LABELS.title)
  await expect(block.getByRole('region', { name: NORMALIZED_LABELS.region })).toBeVisible()
  await expect(block.locator('thead th')).toHaveText(NORMALIZED_LABELS.heads)
  await expect(block.getByRole('button', { name: NORMALIZED_LABELS.refresh })).toBeVisible()
  await expect(block).not.toContainText('Normalized data')
  await expect(block).not.toContainText('Available')
  await expect(block).not.toContainText('Not available')
  await expect(rows(page).first()).toContainText(NORMALIZED_LABELS.available)
})

test('new shell: a short list has no show-more controls and a refreshed list starts collapsed again', async ({ page }) => {
  await open(page, 'new', [30, 120])

  await expect(rows(page)).toHaveCount(30)
  await expect(page.getByRole('button', { name: /Показать/ })).toHaveCount(0)
  await page.getByRole('button', { name: NORMALIZED_LABELS.refresh }).click()
  await expect(rows(page)).toHaveCount(50)
  await page.getByRole('button', { name: NORMALIZED_LABELS.more(50) }).click()
  await expect(rows(page)).toHaveCount(100)
})

test('old interface keeps every row and the English block', async ({ page }) => {
  await open(page, 'old', [900])

  await expect(rows(page)).toHaveCount(900)
  await expect(page.locator('#normalized-data h2')).toHaveText('Normalized data')
  await expect(page.getByRole('button', { name: /Показать/ })).toHaveCount(0)
})
