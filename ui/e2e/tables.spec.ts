import AxeBuilder from '@axe-core/playwright'
import { expect, test, type Page } from '@playwright/test'
import { TABLES_LABELS } from '../src/shell/labels.tables'

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
  await page.goto('/?shell=new')
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

test('old interface retains its English regions', async ({ page }) => {
  await fixtureApi(page)
  await page.goto('/')
  await pickSavedAnalysis(page)
  await expect(page.locator('#policy-results').getByRole('region', { name: 'Policy results' })).toBeVisible()
  await expect(page.locator('#transaction-metrics').getByRole('region', { name: 'Transaction metrics' })).toBeVisible()
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
  const table = page.locator('#resource-results').getByRole('region', { name: 'Resource policy checks' })
  await expect(table.getByRole('columnheader', { name: 'Service' })).toBeVisible()
  await expect(table.getByRole('columnheader', { name: 'Coverage (observed / expected)' })).toBeVisible()
  const orders = table.getByRole('row').filter({ hasText: 'cpu-share/orders' })
  await expect(orders.getByTestId('resource-check-service')).toContainText('orders')
  await expect(orders.getByTestId('resource-check-service')).toContainText('cpu-share')
  await expect(orders.getByTestId('resource-check-coverage')).toContainText('19 / 20 (95 %)')
  await expect(orders.getByTestId('resource-check-coverage')).toContainText('missing cells 1, longest gap 1')
  await expect(orders.getByTestId('resource-check-coverage')).not.toContainText('presumed')
  const payments = table.getByRole('row').filter({ hasText: 'cpu-share/payments' })
  await expect(payments.getByTestId('resource-check-coverage')).toContainText('17 / 20 (85 %)')
  await expect(payments.getByTestId('resource-check-coverage')).toContainText('missing cells 3, longest gap 2')
  await expect(payments.getByTestId('resource-check-coverage')).toContainText('presumed violation')
  const snapshot = table.getByRole('row').filter({ hasText: 'cpu-limit' })
  await expect(snapshot.getByTestId('resource-check-service')).toHaveText('—')
  await expect(snapshot.getByTestId('resource-check-coverage')).toHaveText('—')
})

test('the old interface shows the same service and coverage columns', async ({ page }) => {
  await fixtureApi(page, withPlatformChecks)
  await page.goto('/')
  await pickSavedAnalysis(page)
  const table = page.locator('#resource-results').getByRole('region', { name: 'Resource policy checks' })
  await expect(table.getByRole('columnheader', { name: 'Service' })).toBeVisible()
  await expect(table.getByRole('row').filter({ hasText: 'cpu-share/orders' }).getByTestId('resource-check-coverage')).toContainText('19 / 20 (95 %)')
})

test('resource checks of a snapshot without platform rules keep their columns', async ({ page }) => {
  await openTables(page, snapshotOnly)
  const table = page.locator('#resource-results').getByRole('region', { name: 'Resource policy checks' })
  await expect(table.getByRole('row').filter({ hasText: 'cpu-limit' })).toBeVisible()
  await expect(table.getByRole('columnheader', { name: 'Service' })).toHaveCount(0)
  await expect(table.getByRole('columnheader', { name: /Coverage/ })).toHaveCount(0)
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
    await expect(page.locator('#resource-results').getByRole('region', { name: 'Resource policy checks' })).toBeVisible()
    const size = await page.evaluate(() => ({ scroll: document.documentElement.scrollWidth, client: document.documentElement.clientWidth }))
    expect(size.scroll).toBeLessThanOrEqual(size.client)
  })
}
