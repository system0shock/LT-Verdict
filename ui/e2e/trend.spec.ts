import { expect, test, type Page } from '@playwright/test'

const reference = { run_id: 'trend-run', analysis_id: 'a'.repeat(64) }
const run = { ...reference, source_type: 'jmeter', sha256: 'b'.repeat(64), size_bytes: 100, original_filename: 'trend.jtl' }
const trendSummary = {
  id: 'trend-summary', type: 'trend_summary', checks_total: 2, observed: 1, not_material: 1, insufficient: 0, unavailable: 0,
  method: 'slope-materiality.v1', uncertainty: 'NOT_ESTIMATED',
}
const trendChecks = [
  {
    id: 'trend-check-1', type: 'trend_check', check_id: 'cpu-trend', series_id: 'host-1:cpu.usage', metric: 'cpu.usage', unit: 'percent', entity: 'host-1',
    window_id: 'steady-300', window_from_epoch_ms: 1767225600000, window_to_epoch_ms: 1767225900000,
    declared_direction: 'either', status: 'TREND_OBSERVED', min_cells: 10, expected_cells: 30, observed_cells: 30, missing_cells: 0, longest_gap_cells: 0,
    median: '41.25', slope_per_second: '0.0135', split_half_shift: '6.40',
    magnitude_gate: { min_slope_units_per_second: '0.001', min_split_half_shift_pct: '5', required_split_half_shift_units: '2.0625' },
    observed_direction: 'increase', method: 'slope-materiality.v1', uncertainty: 'NOT_ESTIMATED', reasons: ['SLOPE_ABOVE_GATE', 'SPLIT_HALF_SHIFT_ABOVE_GATE'],
  },
  {
    id: 'trend-check-2', type: 'trend_check', check_id: 'heap-trend', series_id: 'host-1:jvm.heap', metric: 'jvm.heap', unit: 'bytes', entity: 'host-1',
    window_id: 'steady-300', window_from_epoch_ms: 1767225600000, window_to_epoch_ms: 1767225900000,
    declared_direction: 'increase', status: 'NO_MATERIAL_TREND', min_cells: 10, expected_cells: 30, observed_cells: 30, missing_cells: 0, longest_gap_cells: 0,
    median: '536870912', slope_per_second: '0', split_half_shift: '0',
    magnitude_gate: { min_slope_units_per_second: '1024', min_split_half_shift_pct: '5', required_split_half_shift_units: '26843545.6' },
    observed_direction: 'flat', method: 'slope-materiality.v1', uncertainty: 'NOT_ESTIMATED', reasons: ['SLOPE_BELOW_GATE'],
  },
]
const resourceTrend = {
  id: 'resource-trend-1', type: 'resource_trend', check_id: 'cpu-trend', series_id: 'host-1:cpu.usage', metric: 'cpu.usage', unit: 'percent', entity: 'host-1',
  window_id: 'steady-300', observed_direction: 'increase', from_epoch_ms: 1767225600000, to_epoch_ms: 1767225900000,
  expected_cells: 30, observed_cells: 30, median: '41.25', slope_per_second: '0.0135', split_half_shift: '6.40',
  effect: 'diagnostic', uncertainty: 'NOT_ESTIMATED', evidence_id: 'trend-check-1',
}
const result = {
  schema_version: 'analysis-result.v1', run_id: reference.run_id, analysis_mode: 'standard', run_validity: 'VALID', policy_verdict: 'NO_POLICY',
  analysis_coverage: { status: 'COMPLETE', reasons: [] }, findings: [resourceTrend], evidence: [trendSummary, ...trendChecks],
}

async function fixtureApi(page: Page) {
  await page.route('**/api/**', async (route) => {
    const path = new URL(route.request().url()).pathname
    const method = route.request().method()
    let body: unknown
    if (path === '/api/bootstrap') body = { csrf_token: 'ui-test', max_upload_bytes: 1000000 }
    else if (method === 'GET' && path === '/api/jenkins') body = { profiles: [] }
    else if (method === 'GET' && path === '/api/grafana') body = { profiles: [] }
    else if (method === 'GET' && /^\/api\/runs\/[^/]+\/analyses\/[^/]+\/advice$/.test(path)) body = { advice: null, job: null }
    else if (path === '/api/sources') body = { profiles: [] }
    else if (path === '/api/runs') body = { runs: [run], next_after: null }
    else if (path === '/api/baseline') body = { baseline: null }
    else if (path.endsWith('/analyses')) body = { analyses: [{ analysis_id: reference.analysis_id, policy_sha256: 'c'.repeat(64), policy_verdict: 'NO_POLICY', run_validity: 'VALID' }], next_after: null }
    else if (path.endsWith('/result')) body = result
    else if (path.endsWith('/buckets')) body = { buckets: [], next_from_ms: null }
    else if (path === '/api/inputs') body = run
    else if (method === 'GET' && path === '/api/jobs') body = { jobs: [] }
    else if (path === '/api/jobs') body = { job_id: 'job-1', state: 'COMPLETE', processed_bytes: 100, total_bytes: 100, ...reference, diagnostic: null }
    else throw new Error(`Unexpected UI request ${path}`)
    await route.fulfill({ json: body })
  })
}

test.beforeEach(async ({ page }) => { await fixtureApi(page) })

test('submits a trend plan with its resource snapshot and blocks a plan without one', async ({ page }) => {
  await page.goto('/')
  await page.getByTestId('input-file').setInputFiles({ name: 'trend.jtl', mimeType: 'text/csv', buffer: Buffer.from('load') })
  await page.getByTestId('trend-plan-file').setInputFiles({ name: 'trend.json', mimeType: 'application/json', buffer: Buffer.from('{"trend":"selected"}') })
  await page.getByRole('button', { name: 'Analyze run', exact: true }).click()
  await expect(page.getByRole('alert')).toContainText('Trend plan requires a matching resource snapshot.')

  await page.getByTestId('resource-snapshot-file').setInputFiles({ name: 'resource.json', mimeType: 'application/json', buffer: Buffer.from('{"resource":"selected"}') })
  const request = page.waitForRequest((value) => value.method() === 'POST' && new URL(value.url()).pathname === '/api/jobs')
  await page.getByRole('button', { name: 'Analyze run', exact: true }).click()
  const submitted = (await request).postDataBuffer()!.toString()
  expect(submitted).toContain('name="resource_snapshot"; filename="resource.json"')
  expect(submitted).toContain('name="trend_plan"; filename="trend.json"')
})

test('renders saved trend facts unchanged after reload', async ({ page }) => {
  await page.goto('/')
  await page.getByRole('button', { name: 'trend.jtl' }).click()
  await page.locator(`button[title="${reference.analysis_id}"]`).click()
  const saved = page.getByTestId('trend-results')
  await expect(saved.locator('#trend-results-title')).toHaveText('Saved trend facts')
  await expect(saved).toContainText('Checks: 2 · Observed: 1 · Not material: 1 · Insufficient cells: 0 · Unavailable: 0')
  await expect(saved).toContainText('Method: slope-materiality.v1 · Uncertainty: not estimated (NOT_ESTIMATED)')
  await expect(saved.getByRole('row', { name: /cpu-trend/ })).toContainText('TREND_OBSERVED')
  await expect(saved.getByRole('row', { name: /cpu-trend/ })).toContainText('0.0135')
  await expect(saved.getByRole('row', { name: /cpu-trend/ })).toContainText('2.0625')
  await expect(saved.getByRole('row', { name: /heap-trend/ })).toContainText('NO_MATERIAL_TREND')
  await expect(page.getByRole('link', { name: 'Download trend plan' })).toHaveAttribute('href', `/api/runs/${reference.run_id}/analyses/${reference.analysis_id}/trend-plan`)
  await expect(page.getByRole('link', { name: 'Download trend result' })).toHaveAttribute('href', `/api/runs/${reference.run_id}/analyses/${reference.analysis_id}/trend`)

  await page.reload()
  await page.getByRole('button', { name: 'trend.jtl' }).click()
  await page.locator(`button[title="${reference.analysis_id}"]`).click()
  await expect(page.getByTestId('trend-results')).toContainText('Checks: 2 · Observed: 1 · Not material: 1 · Insufficient cells: 0 · Unavailable: 0')
  await expect(page.getByTestId('trend-results').getByRole('row', { name: /cpu-trend/ })).toContainText('0.0135')
})
