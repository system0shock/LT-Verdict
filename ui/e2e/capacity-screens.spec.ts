import { expect, test, type Page } from '@playwright/test'
import { OVERVIEW_LABELS } from '../src/shell/labels'
import { CAPACITY_LABELS } from '../src/shell/labels.tables'

const reference = { run_id: 'capacity-screens-run', analysis_id: 'a'.repeat(64) }
const run = { ...reference, source_type: 'jmeter', sha256: 'b'.repeat(64), size_bytes: 100, original_filename: 'capacity-screens.jtl' }
const longRatio = '0.9500000000000000000000000000000000'
const evidenceIds = ['window-policy-summary-' + '1'.repeat(40), 'resource-summary-' + '2'.repeat(40), 'capacity-guard-' + '3'.repeat(40)]
const capacity = (verdict: string) => ({
  schema_version: 'capacity.v1', load_axis: 'rps', unit: 'requests/s', bound_type: 'BOUNDED', lower_inclusive: '95.745', upper_exclusive: '103.745',
  policy_verdict: verdict, reasons: [], capacity_knee: null, knee_reason: 'KNEE_DETECTOR_NOT_IMPLEMENTED',
  stages: [
    { id: 'step-96', target: 96, achieved: '96.1', achieved_statistic: 'p05_10s', observed_min: 95, observed_max: 97, complete_bins: 30, expected_bins: 30, target_tolerance_ratio: 0.02, verified_bound_load: '95.745', verdict: 'PASS', reasons: [], evidence_refs: evidenceIds },
  ],
})
const overall = {
  id: 'm-overall', type: 'metric_summary', scope: { kind: 'overall' }, sample_count: 141000, error_count: 0,
  error_rate_ratio: { numerator: 0, denominator: 141000 }, throughput_rps: { numerator: 141000000, denominator: 1800000 },
  latency_ms: { p50: 20, p95: 9431, p99: 11000, max: 12000 },
}
const resourceSummary = {
  id: 'rs1', type: 'resource_summary', series_id: '1.234567890123', metric: 'cpu', unit: 'ratio', entity: 'app', role: 'system', aggregation: 'interval_mean', window_id: 'w',
  from_epoch_ms: 1000, to_epoch_ms: 4000, expected_cells: 3, observed_cells: 3, missing_cells: 0, longest_gap_cells: 0,
  statistics: { min: '0.5', max: longRatio, mean: '0.123456789012345678901234567890', median: null, q05: '0.0000001234567890123', q95: '1234567.8912345678901234' },
}
const result = (verdict: string, extra: unknown[] = []) => ({
  schema_version: 'analysis-result.v1', run_id: reference.run_id, analysis_mode: 'capacity_step', run_validity: 'VALID', policy_verdict: verdict,
  analysis_coverage: { status: 'COMPLETE', reasons: [] }, findings: [], evidence: [overall, ...extra], capacity_summary: capacity(verdict),
})

async function fixtureApi(page: Page, saved: Record<string, unknown>) {
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
    else if (path.endsWith('/analyses')) body = { analyses: [{ analysis_id: reference.analysis_id, policy_sha256: 'c'.repeat(64), policy_verdict: saved.policy_verdict, run_validity: 'VALID' }], next_after: null }
    else if (path.endsWith('/result')) body = saved
    else if (path.endsWith('/buckets')) body = { buckets: [], next_from_ms: null }
    else throw new Error(`Unexpected UI request ${path}`)
    await route.fulfill({ json: body })
  })
}

async function open(page: Page, saved: Record<string, unknown>, shell: 'new' | 'old' = 'new') {
  await fixtureApi(page, saved)
  await page.goto(`/?shell=${shell}`)
  await page.getByRole('button', { name: 'capacity-screens.jtl' }).click()
  await page.locator(`button[title="${reference.analysis_id}"]`).click()
  await expect(page.locator('#verdict')).toBeVisible()
}

for (const verdict of ['PASS', 'FAIL', 'NO_VERDICT']) {
  test(`capacity ${verdict}: the verdict card has no empty list heading`, async ({ page }) => {
    await open(page, result(verdict))

    await expect(page.locator('#verdict h3').filter({ hasText: /Что проверено|Что нарушено|Найденные нарушения/ })).toHaveCount(0)
    await expect(page.getByTestId('verdict-lines')).toHaveCount(0)
  })
}

test('capacity FAIL: the reason is under attention and opens the capacity table', async ({ page }) => {
  await open(page, result('FAIL'))

  const item = page.getByTestId('attention-item').filter({ hasText: OVERVIEW_LABELS.capacityFailTitle })
  await expect(item).toHaveCount(1)
  await expect(item).toContainText('95,745')
  await item.getByTestId('attention-open').click()
  await expect(page.locator('#shell-tab-tables')).toHaveAttribute('aria-selected', 'true')
  await expect(page.locator('#capacity-results')).toBeVisible()
})

test('capacity overview explains that key metrics cover the whole run', async ({ page }) => {
  await open(page, result('PASS'))

  await expect(page.getByTestId('metrics-capacity-note')).toHaveText(OVERVIEW_LABELS.metricsCapacityNote)
})

test('a standard result has no capacity note under key metrics', async ({ page }) => {
  await open(page, { ...result('PASS'), analysis_mode: 'standard', capacity_summary: undefined })

  await expect(page.getByTestId('metric-tile').first()).toBeVisible()
  await expect(page.getByTestId('metrics-capacity-note')).toHaveCount(0)
})

test('capacity table folds evidence ids into a collapsible basis block', async ({ page }) => {
  await open(page, result('PASS'))
  await page.locator('#shell-tab-tables').click()

  const row = page.locator('#stage-step-96')
  const details = row.locator('details')
  await expect(details).toHaveCount(1)
  await expect(details.locator('summary')).toHaveText(CAPACITY_LABELS.evidenceSummary(3))
  await expect(row.getByText(evidenceIds[0])).toBeHidden()
  await details.locator('summary').click()
  for (const id of evidenceIds) await expect(row.getByText(id)).toBeVisible()
})

test('new shell: resource statistics are formatted and empty values are a dash', async ({ page }) => {
  await open(page, result('PASS', [resourceSummary]))
  await page.locator('#shell-tab-tables').click()

  const row = page.getByRole('region', { name: 'Resource summaries' }).locator('tbody tr').first()
  await expect(row).not.toContainText('Not available')
  await expect(row).not.toContainText(longRatio)
  await expect(row).toContainText('1.234567890123')
  await expect(row).toContainText('0,95')
  await expect(row).toContainText('1 234 567,8912')
  await expect(row.locator('td', { hasText: '0,95' }).first()).toHaveAttribute('title', longRatio)
  await expect(row.locator('td').nth(13)).toHaveText('—')
})

test('old shell keeps the raw resource values', async ({ page }) => {
  await open(page, result('PASS', [resourceSummary]), 'old')

  const row = page.getByRole('region', { name: 'Resource summaries' }).locator('tbody tr').first()
  await expect(row).toContainText(longRatio)
  await expect(row).toContainText('Not available (null)')
})
