import { expect, test, type Page } from '@playwright/test'
import AxeBuilder from '@axe-core/playwright'

const reference = { run_id: 'diagnostic-run', analysis_id: 'a'.repeat(64) }
const run = { ...reference, source_type: 'jmeter', sha256: 'b'.repeat(64), size_bytes: 100, original_filename: 'diagnostic.jtl' }
const baseline = { schema_version: 'local-baseline.v1', series: 'Checkout', mode: 'manual', reference, algorithm: null, candidates: [], scores: [] }
const episode = {
  id: 'episode-1', type: 'anomaly_episode', rule_id: 'cpu-episode', window_id: 'steady', reference_window_id: 'reference',
  metric: 'cpu', unit: 'ratio', entity: '<img src=x onerror=alert(1)>', from_epoch_ms: 150000, to_epoch_ms: 175000,
  duration_ms: 25000, direction: 'increase', reference_median: '0.2', reference_mad: '0.01',
  observed_min: '0.8', observed_max: '0.9', max_abs_delta: '0.7', evidence_id: 'check-1', reasons: [],
}
const result = {
  schema_version: 'analysis-result.v1', run_id: reference.run_id, analysis_mode: 'standard', run_validity: 'VALID', policy_verdict: 'NO_POLICY',
  analysis_coverage: { status: 'COMPLETE', reasons: [] }, findings: [episode],
  evidence: [
    { id: 'summary', type: 'diagnostic_summary', status: 'COMPLETE', pairs_tested: 2, pairs_evaluable: 1, anomalies_tested: 1, episodes_reported: 1, suppressed_short_episodes: 3, uncertainty: 'NOT_ESTIMATED', reasons: [] },
    { id: 'pair-1', type: 'correlation_pair', pair_id: 'cpu-latency', window_id: 'steady', resource_series_id: 'cpu', load_metric: 'response_time_p95_ms', entity: 'server-1', resource_unit: 'ratio', load_unit: 'ms', from_epoch_ms: 150000, to_epoch_ms: 300000, expected_cells: 30, paired_cells: 30, lag_used_cells: 0, raw_rho: '0.9', partial_rho: '0.8', best_lag_ms: null, best_lag_rho: null, lag_profile: [], status: 'DESCRIPTIVE', controls_requested: [], controls_used: [], controls_dropped: [], sensitivity_without_achieved_rps: null, uncertainty: 'NOT_ESTIMATED', reasons: ['PLANNED_LOAD_UNAVAILABLE'] },
    { id: 'pair-2', type: 'correlation_pair', pair_id: 'memory-latency', window_id: 'steady', resource_series_id: 'memory', load_metric: 'response_time_p95_ms', entity: 'server-1', resource_unit: 'bytes', load_unit: 'ms', from_epoch_ms: 150000, to_epoch_ms: 300000, expected_cells: 30, paired_cells: 2, lag_used_cells: 0, raw_rho: null, partial_rho: null, best_lag_ms: null, best_lag_rho: null, lag_profile: [], status: 'INSUFFICIENT_DATA', controls_requested: [], controls_used: [], controls_dropped: [], sensitivity_without_achieved_rps: null, uncertainty: 'NOT_ESTIMATED', reasons: ['INSUFFICIENT_OBSERVATIONS'] },
    { id: 'check-1', type: 'anomaly_check', rule_id: 'cpu-episode', window_id: 'steady', reference_window_id: 'reference', status: 'CANDIDATE', reference_median: '0.2', reference_mad: '0.01', reference_observed_cells: 30, reference_expected_cells: 30, observed_cells: 30, expected_cells: 30, episodes_reported: 1, suppressed_short_episodes: 3, reasons: [] },
  ],
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
    else if (path === '/api/baseline') body = { baseline }
    else if (method === 'GET' && path === `/api/runs/${reference.run_id}/analyses/${reference.analysis_id}/baseline-conditions`) body = { conditions: null }
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

async function openSaved(page: Page) {
  await page.goto('/')
  await page.getByRole('button', { name: 'diagnostic.jtl' }).click()
  await page.locator(`button[title="${reference.analysis_id}"]`).click()
  await expect(page.locator('#verdict')).toContainText('NO_POLICY')
}

test.beforeEach(async ({ page }) => { await fixtureApi(page) })

test('submits the optional diagnostic plan with the resource snapshot', async ({ page }) => {
  await page.goto('/')
  await page.getByTestId('input-file').setInputFiles({ name: 'diagnostic.jtl', mimeType: 'text/csv', buffer: Buffer.from('load') })
  await page.getByTestId('resource-snapshot-file').setInputFiles({ name: 'resource.json', mimeType: 'application/json', buffer: Buffer.from('{"resource":"selected"}') })
  await page.getByLabel('Correlation plan', { exact: false }).setInputFiles({ name: 'plan.json', mimeType: 'application/json', buffer: Buffer.from('{"plan":"selected"}') })
  const request = page.waitForRequest((request) => request.method() === 'POST' && new URL(request.url()).pathname === '/api/jobs')
  await page.getByRole('button', { name: 'Analyze run', exact: true }).click()
  const submitted = (await request).postDataBuffer()!.toString()
  expect(submitted).toContain('name="correlation_plan"; filename="plan.json"')
  expect(submitted).toContain('{"plan":"selected"}')
  expect(submitted).toContain('name="resource_snapshot"; filename="resource.json"')
})

test('renders diagnostic coverage, abstention and one safe episode with expandable evidence', async ({ page }, testInfo) => {
  await openSaved(page)
  const diagnostics = page.locator('#diagnostic-results')
  await expect(diagnostics).toBeVisible()
  await expect(diagnostics).toContainText('NOT_ESTIMATED')
  await expect(diagnostics).toContainText('3 short episodes suppressed')
  await expect(diagnostics.getByRole('row', { name: /cpu-latency/ })).toContainText('0.9')
  await expect(diagnostics.getByRole('row', { name: /memory-latency/ })).toContainText('INSUFFICIENT_DATA')
  await expect(diagnostics).toContainText('PLANNED_LOAD_UNAVAILABLE')
  await expect(diagnostics.getByRole('row', { name: /server-1.*memory|memory.*server-1/ })).toContainText('N/A')
  await expect(page.getByTestId('anomaly-episodes').locator('tbody tr')).toHaveCount(1)
  await expect(page.getByTestId('anomaly-episodes')).toContainText('25.0 s')
  await expect(page.getByTestId('anomaly-episodes')).toContainText('<img src=x onerror=alert(1)>')
  await expect(diagnostics.locator('img')).toHaveCount(0)
  await diagnostics.getByText('Raw diagnostic evidence', { exact: true }).click()
  await expect(diagnostics.locator('pre')).toContainText('reference_mad')
  expect((await new AxeBuilder({ page }).include('#diagnostic-results').analyze()).violations).toEqual([])
  await page.setViewportSize({ width: 500, height: 1000 })
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(500)
  expect((await diagnostics.locator('summary').boundingBox())!.height).toBeGreaterThanOrEqual(44)
  await diagnostics.screenshot({ path: testInfo.outputPath('diagnostics-mobile.png') })
})

test('compares explicit windows and materiality while preserving unconfirmed and zero-baseline reasons', async ({ page }) => {
  await page.route('**/comparison?**', async (route) => {
    const query = new URL(route.request().url()).searchParams
    expect(Object.fromEntries(query)).toEqual({ baseline_window: 'reference', current_window: 'steady', min_change_percent: '10', min_error_rate_delta: '0.002' })
    await route.fulfill({ json: {
      baseline, current: reference, comparability: 'UNCONFIRMED', warnings: [], metrics: [],
      window_comparison: { status: 'DESCRIPTIVE', baseline_window: 'reference', current_window: 'steady', baseline_sample_count: 600, current_sample_count: 800, baseline_duration_ms: 30000, current_duration_ms: 40000, min_change_percent: '10', min_error_rate_delta: '0.002', reasons: ['CONDITIONS_UNCONFIRMED'],
        metrics: [{ metric: 'resource_median', entity: 'server-1', resource_series_id: 'cpu', unit: 'ratio', baseline: '0', current: '0.2', delta: '0.2', delta_percent: null, reason: null, percent_reason: 'ZERO_BASELINE', status: 'DESCRIPTIVE', baseline_evidence_id: 'baseline-cpu', current_evidence_id: 'current-cpu' }],
      },
    } })
  })
  await openSaved(page)
  const action = page.getByRole('button', { name: 'Compare selected analysis', exact: true })
  await page.getByLabel('Baseline window ID', { exact: true }).fill('reference')
  await expect(action).toBeDisabled()
  await page.getByLabel('Current window ID', { exact: true }).fill('steady')
  await page.getByLabel('Minimum change (%)', { exact: true }).fill('10')
  await page.getByLabel('Minimum error-rate delta (ratio)', { exact: true }).fill('0.002')
  await action.click()
  await expect(page.getByTestId('window-comparison')).toContainText('CONDITIONS_UNCONFIRMED')
  await expect(page.getByTestId('window-comparison')).toContainText('ZERO_BASELINE')
  await expect(page.getByTestId('window-comparison')).toContainText('server-1')
  await expect(page.getByTestId('window-comparison')).toContainText('600 samples / 30000 ms')
  await expect(page.getByTestId('window-comparison')).toContainText('800 samples / 40000 ms')
  await page.getByTestId('window-comparison').getByText('Raw window comparison evidence', { exact: true }).click()
  await page.setViewportSize({ width: 500, height: 1000 })
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(500)
  await page.getByLabel('Current window ID', { exact: true }).fill('another-window')
  await expect(page.getByTestId('window-comparison')).toHaveCount(0)
})

const selectionOf = (pairId: string, status: string, extra: Record<string, unknown> = {}) => ({
  id: `selection-${pairId}`, type: 'correlation_headline_selection', pair_id: pairId, window_id: 'steady', method: 'mbb-lag-max-holm.v1', rng: 'java-random-sha256-seed.v1',
  status, family_hypotheses: 3, bootstrap_replicates: 999, block_lengths_cells: [10, 20], alpha: '0.05', p_value_b10: '0.001', p_value_b20: '0.002',
  max_p_value: '0.002', holm_adjusted_p_value: '0.006', selected: status === 'SELECTED', reasons: [], ...extra,
})
const pairOf = (pairId: string, series: string, extra: Record<string, unknown> = {}) => ({
  ...result.evidence[1], id: `pair-${pairId}`, pair_id: pairId, resource_series_id: series, entity: 'server-1', status: 'CANDIDATE', raw_rho: '0.9', best_lag_ms: 5000, best_lag_rho: '0.9', reasons: [], ...extra,
})
const withSelection = {
  ...result,
  evidence: [
    result.evidence[0],
    pairOf('cpu-latency', 'cpu'), pairOf('memory-latency', 'memory'), pairOf('disk-latency', 'disk'),
    selectionOf('cpu-latency', 'SELECTED'),
    selectionOf('memory-latency', 'NOT_SELECTED', { selected: false, reasons: ['HOLM_NOT_REJECTED'], holm_adjusted_p_value: '0.4' }),
    selectionOf('disk-latency', 'UNAVAILABLE', { selected: false, reasons: ['GENUINE_PARTIAL_UNCALIBRATED'], holm_adjusted_p_value: null }),
  ],
}

test('the new shell lists only the selected correlation as a hypothesis to check and says what could not be checked', async ({ page }, testInfo) => {
  await page.route('**/result', (route) => route.fulfill({ json: withSelection }))
  await page.goto('/?shell=new')
  await page.getByRole('button', { name: 'diagnostic.jtl' }).click()
  await page.locator(`button[title="${reference.analysis_id}"]`).click()
  await page.locator('#shell-tab-tables').click()
  const diagnostics = page.locator('#diagnostic-results')
  const table = diagnostics.getByRole('region', { name: 'Гипотезы для проверки' })

  await expect(diagnostics.getByRole('heading', { name: 'Гипотезы для проверки' })).toBeVisible()
  await expect(table.locator('tbody tr')).toHaveCount(1)
  await expect(table.locator('tbody tr td')).toHaveText(['steady', 'cpu', 'response_time_p95_ms', '5', '0,9', '0,006', '3', 'метод v1; при дрейфе ряда ненадёжно'])
  await expect(diagnostics.getByTestId('correlation-hypotheses').getByText('memory')).toHaveCount(0)
  await expect(diagnostics.getByTestId('correlation-unavailable')).toContainText('Стадия «steady»: не удалось проверить гипотез: 1 из 3')
  await expect(diagnostics.getByTestId('correlation-unavailable')).toContainText('нагрузка менялась внутри стадии')
  const note = diagnostics.getByTestId('correlation-note')
  await expect(note).toContainText('ассоциация, не причина; не откалибровано', { ignoreCase: true })
  await expect(diagnostics.getByRole('region', { name: 'Observed associations' })).toHaveCount(0)
  const text = (await diagnostics.getByTestId('correlation-hypotheses').innerText()).toLowerCase()
  expect(text.replaceAll('ассоциация, не причина; не откалибровано', '')).not.toMatch(/причин|утечк|из-за|доказан/)
  expect((await new AxeBuilder({ page }).include('#diagnostic-results').analyze()).violations).toEqual([])
  await page.setViewportSize({ width: 375, height: 900 })
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(375)
  await diagnostics.screenshot({ path: testInfo.outputPath('correlation-hypotheses-mobile.png') })
})

test('the new shell says so when no pair was selected, and the old interface still shows every pair in English', async ({ page }) => {
  await page.route('**/result', (route) => route.fulfill({ json: { ...withSelection, evidence: withSelection.evidence.filter((item) => item.id !== 'selection-cpu-latency') } }))
  await page.goto('/?shell=new')
  await page.getByRole('button', { name: 'diagnostic.jtl' }).click()
  await page.locator(`button[title="${reference.analysis_id}"]`).click()
  await page.locator('#shell-tab-tables').click()

  await expect(page.getByTestId('correlation-hypotheses')).toContainText('Ассоциаций, прошедших отбор, нет')
  await expect(page.getByTestId('correlation-hypotheses').getByRole('table')).toHaveCount(0)
  await page.goto('/?shell=old')
  await page.getByRole('button', { name: 'diagnostic.jtl' }).click()
  await page.locator(`button[title="${reference.analysis_id}"]`).click()
  await expect(page.locator('#diagnostic-results').getByRole('row', { name: /memory-latency/ })).toBeVisible()
  await expect(page.getByTestId('correlation-hypotheses')).toHaveCount(0)
})
