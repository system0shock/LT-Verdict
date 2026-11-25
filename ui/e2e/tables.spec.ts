import AxeBuilder from '@axe-core/playwright'
import { expect, test, type Page } from '@playwright/test'
import { DIAGNOSTICS_LABELS, POSTGRES_LABELS, RESOURCE_CHECK_LABELS, RESOURCE_TABLES_LABELS, TABLES_LABELS, TABLES_NAV_LABELS, TREND_LABELS } from '../src/shell/labels.tables'

const reference = { run_id: 'tables-run', analysis_id: 'a'.repeat(64) }
const run = { ...reference, source_type: 'jmeter', sha256: 'b'.repeat(64), size_bytes: 100, original_filename: 'tables.jtl' }
const overall = {
  id: 'm-overall', type: 'metric_summary', scope: { kind: 'overall' }, sample_count: 3650, error_count: 88,
  error_rate_ratio: { numerator: 88, denominator: 3650 }, throughput_rps: { numerator: 3650000, denominator: 30000 },
  latency_ms: { p50: 100, p95: 2340, p99: 3000, max: 4000 },
}
const checkout = {
  id: 'm-checkout', type: 'metric_summary', scope: { kind: 'transaction', group_path: [], label: 'POST /checkout', sample_kind: 'JMETER_SAMPLER' },
  sample_count: 900, error_count: 4, error_rate_ratio: { numerator: 4, denominator: 900 }, throughput_rps: { numerator: 900000, denominator: 30000 },
  latency_ms: { p50: 100, p95: 2340, p99: 3000, max: 4000 },
}
const login = {
  id: 'm-login', type: 'metric_summary', scope: { kind: 'transaction', group_path: [], label: 'POST /login', sample_kind: 'JMETER_SAMPLER' },
  sample_count: 100, error_count: 0, error_rate_ratio: { numerator: 0, denominator: 100 }, throughput_rps: { numerator: 100000, denominator: 30000 },
  latency_ms: { p50: 10, p95: 20, p99: 30, max: 40 },
}
const failing = {
  schema_version: 'analysis-result.v1', run_id: reference.run_id, analysis_mode: 'standard', run_validity: 'VALID', policy_verdict: 'FAIL',
  analysis_coverage: { status: 'COMPLETE', reasons: [] }, findings: [],
  evidence: [
    overall, checkout, login,
    { id: 'c-checkout', type: 'policy_check', rule_id: 'checkout-p95', metric: 'response_time_p95_ms', operator: 'lte', threshold: 2000, status: 'FAIL', metric_evidence_id: checkout.id, observed: 2340 },
    { id: 'c-login', type: 'policy_check', rule_id: 'login-p95', metric: 'response_time_p95_ms', operator: 'lte', threshold: 100, status: 'PASS', metric_evidence_id: login.id, observed: 20 },
    { id: 'c-overall', type: 'policy_check', rule_id: 'overall-errors', metric: 'error_rate_ratio', operator: 'lte', threshold: 0.05, status: 'PASS', metric_evidence_id: overall.id, observed: overall.error_rate_ratio },
  ],
}
const noPolicy = { ...failing, policy_verdict: 'NO_POLICY', evidence: [overall, checkout, login] }

async function fixtureApi(page: Page, result: typeof failing | typeof noPolicy = failing) {
  const paths: string[] = []
  await page.route('**/api/**', async (route) => {
    const path = new URL(route.request().url()).pathname
    const method = route.request().method()
    paths.push(`${method} ${path}`)
    let body: unknown
    if (path === '/api/bootstrap') body = { csrf_token: 'ui-test', max_upload_bytes: 1000000 }
    else if (method === 'GET' && path === '/api/jobs') body = { jobs: [] }
    else if (method === 'GET' && path === '/api/jenkins') body = { profiles: [] }
    else if (method === 'GET' && path === '/api/grafana') body = { profiles: [] }
    else if (method === 'GET' && /^\/api\/runs\/[^/]+\/analyses\/[^/]+\/advice$/.test(path)) body = { advice: null, job: null }
    else if (path === '/api/sources') body = { profiles: [] }
    else if (path === '/api/runs') body = { runs: [run], next_after: null }
    else if (path === '/api/baseline') body = { baseline: null }
    else if (path.endsWith('/analyses')) body = { analyses: [{ analysis_id: reference.analysis_id, policy_sha256: 'c'.repeat(64), policy_verdict: result.policy_verdict, run_validity: 'VALID' }], next_after: null }
    else if (path.endsWith('/result')) body = result
    else if (path.endsWith('/buckets')) body = { buckets: [], next_from_ms: null }
    else throw new Error(`Unexpected UI request ${path}`)
    await route.fulfill({ json: body })
  })
  return paths
}

async function pickSavedAnalysis(page: Page) {
  await page.getByRole('button', { name: 'tables.jtl' }).click()
  await page.locator(`button[title="${reference.analysis_id}"]`).click()
}

async function openNewShellWithResult(page: Page, result: typeof failing | typeof noPolicy = failing) {
  const paths = await fixtureApi(page, result)
  await page.goto('/')
  await pickSavedAnalysis(page)
  await expect(page.locator('#verdict')).toBeVisible()
  return paths
}

async function openTables(page: Page, result: typeof failing | typeof noPolicy = failing) {
  const paths = await openNewShellWithResult(page, result)
  await page.locator('#shell-tab-tables').click()
  return paths
}

test('new shell shows Russian rule and transaction tables once', async ({ page }) => {
  await openTables(page)
  await expect(page.locator('#policy-results')).toContainText('checkout-p95')
  await expect(page.locator('#policy-results')).toContainText(TABLES_LABELS.statusText.FAIL)
  await expect(page.getByRole('region', { name: TABLES_LABELS.regionRules })).toBeVisible()
  await expect(page.getByRole('region', { name: 'Policy results' })).toHaveCount(0)
  await expect(page.getByRole('region', { name: 'Transaction metrics' })).toHaveCount(0)
})

test('rule sample cells show the mode and legacy fallback', async ({ page }) => {
  const withSample = {
    ...failing,
    evidence: [
      ...failing.evidence,
      { ...failing.evidence[3], id: 'c-small', rule_id: 'small-sample', sample_count: 30, sample_floor: 20, min_samples: 50, sample_mode: 'SMALL_SAMPLE' },
    ],
  }
  await openTables(page, withSample)
  await expect(page.locator('#ev-c-small').getByTestId('rule-sample')).toContainText(TABLES_LABELS.sampleModeText.SMALL_SAMPLE)
  await expect(page.locator('#ev-c-login').getByTestId('rule-sample')).toHaveText(TABLES_LABELS.noSample)
})

test('the status of a rule stays on one line at 1280px next to a long reason', async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 800 })
  const withReason = {
    ...failing,
    evidence: [
      ...failing.evidence,
      { ...failing.evidence[3], id: 'c-reason', rule_id: 'no-data', status: 'NO_VERDICT', reason_code: 'INSUFFICIENT_SAMPLES', sample_count: 3, sample_floor: 20, min_samples: 50, sample_mode: 'INSUFFICIENT' },
    ],
  }
  await openTables(page, withReason)
  const heights = await page.locator('#policy-results .status-text').evaluateAll((nodes) => nodes.map((node) => node.getBoundingClientRect().height))
  expect(heights.length).toBeGreaterThan(1)
  for (const height of heights) expect(height).toBeLessThan(30)
})

test('transaction search stays local', async ({ page }) => {
  const paths = await openTables(page)
  const before = paths.length
  await page.getByRole('searchbox', { name: TABLES_LABELS.searchLabel }).fill('login')
  await expect(page.locator('#transaction-metrics tbody tr')).toHaveCount(1)
  await expect(page.locator('#transaction-metrics tbody tr')).toContainText('login')
  expect(paths).toHaveLength(before)
})

test('status filter and no match keep controls visible', async ({ page }) => {
  await openTables(page)
  await page.getByRole('combobox', { name: TABLES_LABELS.statusFilterLabel }).selectOption('FAIL')
  await expect(page.getByTestId('tx-row')).toHaveCount(1)
  await expect(page.getByTestId('tx-row')).toContainText('checkout')
  await page.getByRole('searchbox', { name: TABLES_LABELS.searchLabel }).fill('no such transaction')
  await expect(page.getByTestId('tx-no-match')).toBeVisible()
  await expect(page.getByRole('searchbox', { name: TABLES_LABELS.searchLabel })).toBeVisible()
})

test('samples sorting changes row order and sort state', async ({ page }) => {
  await openTables(page)
  const button = page.getByRole('button', { name: TABLES_LABELS.sortBy(TABLES_LABELS.txHeads.samples) })
  const head = page.locator('#transaction-metrics th', { has: button })
  await button.click()
  await expect(head).toHaveAttribute('aria-sort', 'descending')
  await expect(page.getByTestId('tx-row').first()).toContainText('checkout')
  await button.click()
  await expect(head).toHaveAttribute('aria-sort', 'ascending')
  await expect(page.getByTestId('tx-row').first()).toContainText('login')
})

test('overview attention opens and focuses the rules region', async ({ page }) => {
  await openNewShellWithResult(page)
  await page.getByTestId('attention-item').first().getByTestId('attention-open').click()
  await expect(page.locator('#shell-tab-tables')).toHaveAttribute('aria-selected', 'true')
  await expect(page.locator('#policy-results .table-wrap')).toBeFocused()
})

test('no policy shows the rules empty state', async ({ page }) => {
  await openTables(page, noPolicy)
  await expect(page.getByTestId('rules-empty')).toBeVisible()
  await expect(page.getByRole('region', { name: TABLES_LABELS.regionRules })).toHaveCount(0)
})

for (const theme of ['light', 'dark'] as const) {
  test(`tables have no serious axe violations after search in ${theme}`, async ({ page }) => {
    await page.emulateMedia({ colorScheme: theme })
    await openTables(page)
    await page.getByRole('searchbox', { name: TABLES_LABELS.searchLabel }).fill('login')
    const axe = await new AxeBuilder({ page }).include('#policy-results').include('#transaction-metrics').analyze()
    expect(axe.violations.filter((item) => item.impact === 'critical' || item.impact === 'serious').map((item) => item.id)).toEqual([])
  })
}

for (const size of [{ width: 1280, height: 800 }, { width: 375, height: 800 }]) {
  test(`tables have no horizontal page scroll at ${size.width}px`, async ({ page }) => {
    await page.setViewportSize(size)
    await openTables(page)
    const width = await page.evaluate(() => ({ scroll: document.documentElement.scrollWidth, client: document.documentElement.clientWidth }))
    expect(width.scroll).toBeLessThanOrEqual(width.client)
  })
}

// Platform SLA checks (ADR 0018): the resource checks table adds the service and the cell coverage of the window.
const resourceCheck = { type: 'resource_policy_check', window_id: 'steady-1', unit: 'ratio', operator: 'gt', threshold: '0.4', effect: 'sla' }
const withPlatformChecks = {
  ...failing,
  findings: [{ id: 'f1', type: 'resource_threshold_violation', window_id: 'steady-1', rule_id: 'cpu-share/payments', series_id: 'cpu-payments', entity: 'payments', unit: 'ratio', from_epoch_ms: 1000, to_epoch_ms: 5000, cell_count: 4, observed_min: '0.5', observed_max: '0.5', evidence_id: 'r-payments', presumed: true }],
  evidence: [
    ...failing.evidence,
    { ...resourceCheck, id: 'r-orders', rule_id: 'cpu-share/orders', series_id: 'cpu-orders', status: 'PASS', reason: 'RESOURCE_GAPS', platform_rule_id: 'cpu-share', service: 'orders', expected_cells: 20, observed_cells: 19, missing_cells: 1, longest_gap_cells: 1 },
    { ...resourceCheck, id: 'r-payments', rule_id: 'cpu-share/payments', series_id: 'cpu-payments', status: 'NO_VERDICT', reason: 'MISSING_RESOURCE_CELLS', platform_rule_id: 'cpu-share', service: 'payments', expected_cells: 20, observed_cells: 17, missing_cells: 3, longest_gap_cells: 2 },
    { ...resourceCheck, id: 'r-snapshot', rule_id: 'cpu-limit', series_id: 'cpu', status: 'PASS', reason: null },
  ],
}
const snapshotOnly = { ...failing, evidence: [...failing.evidence, { ...resourceCheck, id: 'r-snapshot', rule_id: 'cpu-limit', series_id: 'cpu', status: 'PASS', reason: null }] }

test('resource checks show the service and the coverage of platform rules', async ({ page }) => {
  await openTables(page, withPlatformChecks)
  const table = page.locator('#resource-results').getByRole('region', { name: RESOURCE_TABLES_LABELS.checksRegion })
  await expect(table.getByRole('columnheader')).toHaveText(Object.values(RESOURCE_CHECK_LABELS.heads))
  await expect(table.getByRole('columnheader', { name: 'Service' })).toHaveCount(0)
  const orders = table.getByRole('row').filter({ hasText: 'cpu-share/orders' })
  await expect(orders.getByTestId('resource-check-service')).toContainText('orders')
  await expect(orders.getByTestId('resource-check-service')).toContainText('cpu-share')
  await expect(orders.getByTestId('resource-check-coverage')).toContainText('19 / 20 (95 %)')
  await expect(orders.getByTestId('resource-check-coverage')).toContainText(RESOURCE_TABLES_LABELS.missingCoverage(1, '1'))
  await expect(orders.getByTestId('resource-check-coverage')).not.toContainText('предположительное')
  const payments = table.getByRole('row').filter({ hasText: 'cpu-share/payments' })
  await expect(payments.getByTestId('resource-check-coverage')).toContainText('17 / 20 (85 %)')
  await expect(payments.getByTestId('resource-check-coverage')).toContainText(RESOURCE_TABLES_LABELS.missingCoverage(3, '2'))
  await expect(payments.getByTestId('resource-check-coverage')).toContainText('предположительное нарушение')
  const snapshot = table.getByRole('row').filter({ hasText: 'cpu-limit' })
  await expect(snapshot.getByTestId('resource-check-service')).toHaveText('—')
  await expect(snapshot.getByTestId('resource-check-coverage')).toHaveText('—')
})

test('resource checks of a snapshot without platform rules keep their columns', async ({ page }) => {
  await openTables(page, snapshotOnly)
  const table = page.locator('#resource-results').getByRole('region', { name: RESOURCE_TABLES_LABELS.checksRegion })
  await expect(table.getByRole('row').filter({ hasText: 'cpu-limit' })).toBeVisible()
  await expect(table.getByRole('columnheader', { name: RESOURCE_CHECK_LABELS.heads.service })).toHaveCount(0)
  await expect(table.getByRole('columnheader', { name: RESOURCE_CHECK_LABELS.heads.coverage })).toHaveCount(0)
  await expect(table.getByRole('columnheader')).toHaveCount(8)
})

for (const theme of ['light', 'dark'] as const) {
  test(`platform resource checks have no serious axe violations in ${theme}`, async ({ page }) => {
    await page.emulateMedia({ colorScheme: theme })
    await openTables(page, withPlatformChecks)
    const axe = await new AxeBuilder({ page }).include('#resource-results').analyze()
    expect(axe.violations.filter((item) => item.impact === 'critical' || item.impact === 'serious').map((item) => item.id)).toEqual([])
  })
}

for (const width of [1280, 375, 320]) {
  test(`platform resource checks have no horizontal page scroll at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 800 })
    await openTables(page, withPlatformChecks)
    await expect(page.locator('#resource-results').getByRole('region', { name: RESOURCE_TABLES_LABELS.checksRegion })).toBeVisible()
    const size = await page.evaluate(() => ({ scroll: document.documentElement.scrollWidth, client: document.documentElement.clientWidth }))
    expect(size.scroll).toBeLessThanOrEqual(size.client)
  })
}

test('tables sub-tabs switch sections with keyboard and keep 100% Russian labels across all domains', async ({ page }) => {
  const richResult = {
    ...withPlatformChecks,
    evidence: [
      ...withPlatformChecks.evidence,
      { id: 'rs-1', type: 'resource_summary', series_id: 'cpu-orders', metric: 'cpu', unit: 'ratio', entity: 'orders', role: 'system', aggregation: 'interval_mean', window_id: 'steady-1', from_epoch_ms: 1000, to_epoch_ms: 4000, expected_cells: 3, observed_cells: 3, missing_cells: 0, longest_gap_cells: 0, statistics: { min: '0.1', max: '0.9', mean: '0.5', median: '0.5' }, reasons: ['RESOURCE_GAPS'] },
      { id: 'ts-1', type: 'trend_summary', checks_total: 2, observed: 1, not_material: 1, insufficient: 0, unavailable: 0, method: 'slope-materiality.v1', uncertainty: 'NOT_ESTIMATED' },
      { id: 'tc-1', type: 'trend_check', check_id: 'z-trend', series_id: 'cpu-orders', metric: 'cpu', unit: '%', entity: 'orders', window_id: 'steady-1', declared_direction: 'increase', status: 'TREND_OBSERVED', min_cells: 5, expected_cells: 10, observed_cells: 10, missing_cells: 0, longest_gap_cells: 0, median: '40', slope_per_second: '0.02', split_half_shift: '5', observed_direction: 'increase', method: 'slope-materiality.v1', uncertainty: 'NOT_ESTIMATED', reasons: [] },
      { id: 'tc-2', type: 'trend_check', check_id: 'a-trend', series_id: 'mem-orders', metric: 'mem', unit: '%', entity: 'orders', window_id: 'steady-1', declared_direction: 'increase', status: 'NO_MATERIAL_TREND', min_cells: 5, expected_cells: 10, observed_cells: 10, missing_cells: 0, longest_gap_cells: 0, median: '20', slope_per_second: '0.001', split_half_shift: '0.2', observed_direction: 'flat', method: 'slope-materiality.v1', uncertainty: 'NOT_ESTIMATED', reasons: [] },
      { id: 'src-1', type: 'source_summary', profile_id: 'prom-main', source_kind: 'prometheus', transport: 'http', status: 'COMPLETE', request_count: 2, retries: 0, throttle_wait_ms: 0, cap_exceeded: false, queries: [{ id: 'q1', status: 'OK', reason: null }], window_origin: 'auto', requested_margin_ms: 1000, applied_margin_ms: 1000, max_idle_gap_ms: 5000, detected_idle_gaps: 0, longest_idle_gap_ms: null, auto_window_status: 'OK', step_origin: 'fixed', requested_step_ms: 15000, series_count: 1, cells_per_series: 10, cell_budget: 1500, warnings: [] },
      { id: 'os-1', type: 'opensearch_errors', profile_id: 'os-main', total_errors: 4, error_rate_per_minute: 1.5, coverage: { status: 'COMPLETE', reasons: [] }, groups: [{ service: 'orders', error_type: '500', count: 4, first_epoch_ms: 1000, last_epoch_ms: 2000, samples: [{ message: 'boom', message_truncated: true, source_url: 'https://example.invalid/doc' }] }] },
      { id: 'pg-1', type: 'postgres_context', profile_id: 'pg-main', status: 'COMPLETE', reasons: [], configuration_changes: [{ name: 'work_mem', pre: '4MB', post: '8MB' }], tables: [], statements: { status: 'COMPLETE', reasons: [], unmatched_pre: [], unmatched_post: [], rows: [] }, pg_profile: { status: 'COMPLETE', reasons: [] } },
      { id: 'diag-1', type: 'diagnostic_summary', status: 'OK', uncertainty: 'NOT_ESTIMATED', pairs_evaluable: 1, pairs_tested: 2, anomalies_tested: 1, episodes_reported: 1, suppressed_short_episodes: 0, reasons: [] },
      { id: 'anom-1', type: 'anomaly_check', rule_id: 'cpu-spike', window_id: 'steady-1', reference_window_id: 'baseline-1', reference_median: '10', reference_mad: '1', reference_observed_cells: 10, reference_expected_cells: 10, observed_cells: 10, expected_cells: 10, episodes_reported: 1, suppressed_short_episodes: 0, status: 'ANOMALY_FOUND', reasons: [] },
    ],
    findings: [
      ...withPlatformChecks.findings,
      { id: 'ep-1', type: 'anomaly_episode', rule_id: 'cpu-spike', window_id: 'steady-1', reference_window_id: 'baseline-1', metric: 'cpu', entity: 'orders', from_epoch_ms: 1000, to_epoch_ms: 26000, duration_ms: 25000, direction: 'above', observed_min: '10', observed_max: '90', unit: '%', max_abs_delta: '80', reasons: [] },
    ],
  }
  await openTables(page, richResult as never)

  const subtabs = page.getByTestId('tables-subtabs')
  await expect(subtabs).toBeVisible()
  await expect(subtabs.getByRole('tab', { name: TABLES_NAV_LABELS.tabs.all })).toHaveAttribute('aria-selected', 'true')

  // Verify 100% Russian headings and zero leftover English section copy across all sections.
  const panelText = await page.locator('#tables-subpanel').innerText()
  for (const english of [
    'Summary metrics', 'Latency percentile', 'Throughput', 'Resource statistics and SLA', 'Resource binding',
    'Source acquisition', 'OpenSearch errors', 'PostgreSQL changes', 'Run diagnostics',
    'Raw diagnostic evidence', 'Requests per second', 'Errors per bin', 'P95 latency', 'Next bucket page',
  ]) {
    expect(panelText).not.toContain(english)
  }

  // Test sorting in RuleChecksTable, TransactionsTable (p50), TrendTable, and resource checks.
  await page.locator('#policy-results').getByRole('button', { name: TABLES_LABELS.sortBy(TABLES_LABELS.ruleHeads[0]) }).click()
  await expect(page.getByTestId('rule-row').first()).toContainText('checkout-p95')
  await page.locator('#transaction-metrics').getByRole('button', { name: TABLES_LABELS.sortBy(TABLES_LABELS.txHeads.p50) }).click()
  await expect(page.getByTestId('tx-row').first()).toContainText('checkout')
  await page.locator('#trend-results').getByRole('button', { name: TABLES_LABELS.sortBy(TREND_LABELS.heads[0]) }).click()
  await expect(page.getByTestId('trend-row').first()).toContainText('a-trend')
  await page.locator('#resource-results').getByRole('button', { name: TABLES_LABELS.sortBy(RESOURCE_CHECK_LABELS.heads.rule) }).click()
  await expect(page.locator('#resource-results').getByRole('region', { name: RESOURCE_TABLES_LABELS.checksRegion }).locator('tbody tr').first()).toContainText('cpu-limit')

  // Keyboard navigation across sub-tabs filters sections cleanly.
  const allTab = subtabs.getByRole('tab', { name: TABLES_NAV_LABELS.tabs.all })
  await allTab.focus()
  await page.keyboard.press('ArrowRight')
  await expect(subtabs.getByRole('tab', { name: TABLES_NAV_LABELS.tabs.rules })).toHaveAttribute('aria-selected', 'true')
  await expect(page.locator('#policy-results')).toBeVisible()
  await expect(page.locator('#transaction-metrics')).toHaveCount(0)
  await page.keyboard.press('End')
  await expect(page.locator('#normalized-data')).toBeVisible()
  await page.keyboard.press('Home')
  await expect(page.locator('#policy-results')).toBeVisible()
  await expect(page.locator('#normalized-data')).toBeVisible()
})

test('regression: formats bucket rps/errors in ru-RU and localizes postgres changes & anomaly direction', async ({ page }) => {
  const withBucketsAndContext = {
    ...failing,
    evidence: [
      ...failing.evidence,
      {
        id: 'pg-1', type: 'postgres_context', profile_id: 'pg-main', status: 'COMPLETE', reasons: [],
        configuration_changes: [],
        tables: [{ schema: 'public', table: 'orders', stable_key: ['id'], status: 'COMPLETE', reasons: [], row_count_delta: 1, inserted: 1, deleted: 0, updated: 1, changed_keys: [{ change: 'inserted', key: ['10'] }, { change: 'updated', key: ['42'] }, { change: 'deleted', key: ['7'] }], keys_truncated: false }],
        statements: { status: 'COMPLETE', reasons: [], unmatched_pre: [], unmatched_post: [], rows: [] },
        pg_profile: { status: 'COMPLETE', reasons: [] },
      },
    ],
    findings: [
      { id: 'ep-1', type: 'anomaly_episode', rule_id: 'cpu-spike', window_id: 'steady-1', reference_window_id: 'baseline-1', metric: 'cpu', entity: 'orders', from_epoch_ms: 1000, to_epoch_ms: 26000, duration_ms: 25000, direction: 'increase', observed_min: '10', observed_max: '90', unit: '%', max_abs_delta: '80', reasons: [] },
    ],
  }
  await page.route('**/api/**', async (route) => {
    const path = new URL(route.request().url()).pathname
    const method = route.request().method()
    let body: unknown
    if (path === '/api/bootstrap') body = { csrf_token: 'ui-test', max_upload_bytes: 1000000 }
    else if (method === 'GET' && path === '/api/jobs') body = { jobs: [] }
    else if (method === 'GET' && (path === '/api/jenkins' || path === '/api/grafana' || path === '/api/sources')) body = { profiles: [] }
    else if (method === 'GET' && /\/advice$/.test(path)) body = { advice: null, job: null }
    else if (path === '/api/runs') body = { runs: [run], next_after: null }
    else if (path === '/api/baseline') body = { baseline: null }
    else if (path.endsWith('/analyses')) body = { analyses: [{ analysis_id: reference.analysis_id, policy_sha256: 'c'.repeat(64), policy_verdict: 'FAIL', run_validity: 'VALID' }], next_after: null }
    else if (path.endsWith('/result')) body = withBucketsAndContext
    else if (path.endsWith('/buckets')) body = { buckets: [{ bucket_start_ms: 0, sample_count: 1500, error_count: 1200, p95_latency_ms: 100, max_latency_ms: 200, hdr_v2_base64: 'AAAA' }], next_from_ms: null }
    else throw new Error(`Unexpected UI request ${path}`)
    await route.fulfill({ json: body })
  })

  await page.goto('/')
  await pickSavedAnalysis(page)
  await page.locator('#shell-tab-tables').click()
  const rowCells = page.locator('#normalized-data tbody tr').first().locator('td')
  await expect(rowCells.nth(1)).toHaveText((1500).toLocaleString('ru-RU', { minimumFractionDigits: 2, maximumFractionDigits: 2 }))
  await expect(rowCells.nth(2)).toHaveText((1200).toLocaleString('ru-RU'))
  await expect(page.getByTestId('postgres-context')).toContainText(`${POSTGRES_LABELS.changes.inserted}: 10, ${POSTGRES_LABELS.changes.updated}: 42, ${POSTGRES_LABELS.changes.deleted}: 7`)
  await expect(page.getByTestId('anomaly-episodes')).toContainText(DIAGNOSTICS_LABELS.directions.increase)
})
