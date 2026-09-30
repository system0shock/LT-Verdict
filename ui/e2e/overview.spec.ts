import AxeBuilder from '@axe-core/playwright'
import { expect, test, type Page } from '@playwright/test'
import { OVERVIEW_LABELS, SHELL_LABELS } from '../src/shell/labels'

const reference = { run_id: 'overview-run', analysis_id: 'a'.repeat(64) }
const run = { ...reference, source_type: 'jmeter', sha256: 'b'.repeat(64), size_bytes: 100, original_filename: 'overview.jtl' }
const overall = {
  id: 'm-overall', type: 'metric_summary', scope: { kind: 'overall' }, sample_count: 3650, error_count: 88,
  error_rate_ratio: { numerator: 88, denominator: 3650 }, throughput_rps: { numerator: 3650000, denominator: 30000 },
  latency_ms: { p50: 100, p95: 2340, p99: 3000, max: 4000 },
}
const checkout = {
  id: 'm-checkout', type: 'metric_summary', scope: { kind: 'transaction', group_path: [], label: 'POST /checkout', sample_kind: 'JMETER_SAMPLER' },
  sample_count: 900, error_count: 0, error_rate_ratio: { numerator: 0, denominator: 900 }, throughput_rps: { numerator: 900000, denominator: 30000 },
  latency_ms: { p50: 100, p95: 2340, p99: 3000, max: 4000 },
}
const businessRule = (id: string, status = 'FAIL') => ({
  id: `c-${id}`, type: 'policy_check', rule_id: id, metric: 'response_time_p95_ms', operator: 'lte', threshold: 2000, status, metric_evidence_id: 'm-checkout', observed: 2340,
})
const base = {
  schema_version: 'analysis-result.v1', run_id: reference.run_id, analysis_mode: 'standard', run_validity: 'VALID',
  analysis_coverage: { status: 'COMPLETE', reasons: [] }, findings: [] as unknown[],
}
const failing = {
  ...base,
  policy_verdict: 'FAIL',
  findings: [{
    type: 'resource_threshold_violation', rule_id: 'mem-limit', window_id: 'Soak', entity: 'pod-7', from_epoch_ms: 1000, to_epoch_ms: 61000,
    cell_count: 3, observed_min: '95', observed_max: '100',
  }],
  evidence: [
    overall, checkout, businessRule('checkout-p95'),
    { id: 'rc-mem', type: 'resource_policy_check', window_id: 'Soak', rule_id: 'mem-limit', series_id: 'mem-b', unit: '%', operator: 'gt', threshold: '90', effect: 'sla', status: 'FAIL', reason: null },
    {
      id: 't-a', type: 'trend_check', check_id: 'a', series_id: 'cpu-a', metric: 'cpu', unit: '%', entity: 'pod-1', window_id: 'Soak', window_from_epoch_ms: 0, window_to_epoch_ms: 1,
      declared_direction: 'increase', status: 'TREND_OBSERVED', min_cells: 5, expected_cells: 10, observed_cells: 10, missing_cells: 0, longest_gap_cells: 0,
      median: '69.3', slope_per_second: '0.0015', split_half_shift: '5.4',
      magnitude_gate: { min_slope_units_per_second: '0', min_split_half_shift_pct: '1', required_split_half_shift_units: '1' },
      observed_direction: 'increase', method: 'slope-materiality.v1', uncertainty: 'NOT_ESTIMATED', reasons: [],
    },
    {
      id: 'cp-1', type: 'correlation_pair', pair_id: 'p1', window_id: 'Soak', resource_series_id: 'mem-b', load_metric: 'throughput_rps', entity: 'pod-7', resource_unit: '%', load_unit: 'rps',
      from_epoch_ms: 0, to_epoch_ms: 1, expected_cells: 10, paired_cells: 9, lag_used_cells: 0, raw_rho: '0.83', partial_rho: null, best_lag_ms: null, best_lag_rho: null, lag_profile: [],
      status: 'CANDIDATE', controls_requested: [], controls_used: [], controls_dropped: [], sensitivity_without_achieved_rps: null, uncertainty: 'NOT_ESTIMATED', reasons: [],
    },
  ],
}
const noPolicy = { ...base, policy_verdict: 'NO_POLICY', evidence: [overall] }
const manyFailures = { ...base, policy_verdict: 'FAIL', evidence: [overall, checkout, ...Array.from({ length: 9 }, (_, index) => businessRule(`rule-${index + 1}`))] }

// Форма ответа /buckets совпадает с контрактом сервера: все шесть полей, шаг 1 с, пропуск между 2000 и 4000 мс.
const bucket = (start: number, samples: number, errors: number, p95: number) => ({
  bucket_start_ms: start, sample_count: samples, error_count: errors, p95_latency_ms: p95, max_latency_ms: p95 + 100, hdr_v2_base64: 'AAAA',
})
const buckets = [bucket(0, 100, 0, 200), bucket(1000, 120, 1, 220), bucket(2000, 110, 5, 300), bucket(4000, 90, 7, 900), bucket(5000, 80, 2, 850), bucket(6000, 100, 0, 400)]

async function fixtureApi(page: Page, result: unknown = failing, page500: { buckets: unknown[]; next_from_ms: number | null } = { buckets, next_from_ms: null }) {
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
    else if (path.endsWith('/analyses')) body = { analyses: [{ analysis_id: reference.analysis_id, policy_sha256: 'c'.repeat(64), policy_verdict: (result as { policy_verdict: string }).policy_verdict, run_validity: 'VALID' }], next_after: null }
    else if (path.endsWith('/result')) body = result
    else if (path.endsWith('/buckets')) body = page500
    else throw new Error(`Unexpected UI request ${path}`)
    await route.fulfill({ json: body })
  })
  return paths
}

async function openOverview(page: Page, result: unknown = failing, page500?: { buckets: unknown[]; next_from_ms: number | null }) {
  const paths = await fixtureApi(page, result, page500)
  await page.goto('/?shell=new')
  await page.getByRole('button', { name: 'overview.jtl' }).click()
  await page.locator(`button[title="${reference.analysis_id}"]`).click()
  await expect(page.getByTestId('overview-panel')).toBeVisible()
  return paths
}

const items = (page: Page) => page.getByTestId('attention-item')
const chart = (page: Page) => page.getByTestId('shared-cursor-chart')
const slider = (page: Page) => page.getByTestId('chart-cursor')
const lineX = async (page: Page) => Promise.all(['rps', 'errors', 'p95'].map((key) => page.getByTestId(`cursor-line-${key}`).getAttribute('data-x')))

test('the overview lists attention items in a fixed order and marks diagnostics', async ({ page }) => {
  await openOverview(page)

  await expect(page.getByRole('heading', { name: OVERVIEW_LABELS.attentionTitle })).toBeVisible()
  await expect(items(page)).toHaveCount(4)
  expect(await items(page).evaluateAll((nodes) => nodes.map((node) => node.getAttribute('data-kind')))).toEqual(['violation', 'violation', 'diagnostic', 'diagnostic'])
  await expect(items(page).nth(0)).toContainText('checkout-p95')
  await expect(items(page).nth(0)).toContainText(OVERVIEW_LABELS.kindViolation)
  await expect(items(page).nth(1)).toContainText('mem-limit')
  await expect(items(page).nth(2)).toContainText(OVERVIEW_LABELS.trendTitle('cpu-a'))
  await expect(items(page).nth(2)).toContainText(OVERVIEW_LABELS.diagnosticBadge)
  await expect(items(page).nth(3)).toContainText(OVERVIEW_LABELS.correlationTitle('mem-b', 'throughput_rps'))
  await expect(items(page).nth(3)).toContainText(OVERVIEW_LABELS.diagnosticBadge)
  await expect(items(page).nth(0)).not.toContainText(OVERVIEW_LABELS.diagnosticBadge)
  await expect(page.getByTestId('diagnostic-note')).toHaveText(OVERVIEW_LABELS.diagnosticNote)
  await expect(page.locator('#verdict')).toHaveCount(1)
})

test('a result without policy never invents a violation', async ({ page }) => {
  await openOverview(page, noPolicy)

  await expect(items(page)).toHaveCount(1)
  await expect(items(page).first()).toHaveAttribute('data-kind', 'policy')
  await expect(items(page).first()).not.toContainText(OVERVIEW_LABELS.kindViolation)
  await expect(page.getByTestId('diagnostic-note')).toHaveCount(0)
})

test('a passing result says there is nothing to flag', async ({ page }) => {
  await openOverview(page, { ...base, policy_verdict: 'PASS', evidence: [overall, checkout, businessRule('ok', 'PASS')] })

  await expect(page.getByText(OVERVIEW_LABELS.attentionEmpty)).toBeVisible()
  await expect(items(page)).toHaveCount(0)
})

test('a long list is collapsed and can be expanded and collapsed again', async ({ page }) => {
  await openOverview(page, manyFailures)

  await expect(items(page)).toHaveCount(6)
  const more = page.getByTestId('attention-more')
  await expect(more).toHaveText(OVERVIEW_LABELS.attentionMore(3))
  await expect(more).toHaveAttribute('aria-expanded', 'false')
  await more.click()
  await expect(items(page)).toHaveCount(9)
  await expect(more).toHaveText(OVERVIEW_LABELS.attentionLess)
  await expect(more).toHaveAttribute('aria-expanded', 'true')
  await more.click()
  await expect(items(page)).toHaveCount(6)
})

test('each item opens the matching table on the Tables tab and moves focus into it', async ({ page }) => {
  await openOverview(page)
  const tab = (key: string) => page.locator(`#shell-tab-${key}`)

  await items(page).nth(0).getByTestId('attention-open').click()
  await expect(tab('tables')).toHaveAttribute('aria-selected', 'true')
  await expect(page.locator('#policy-results')).toBeInViewport()
  await expect(page.locator('#policy-results [role="region"]')).toBeFocused()

  await tab('overview').click()
  await items(page).nth(1).getByTestId('attention-open').click()
  await expect(page.locator('#resource-results')).toBeInViewport()

  await tab('overview').click()
  await items(page).nth(2).getByTestId('attention-open').click()
  await expect(page.locator('#trend-results')).toBeInViewport()
  await expect(page.locator('#trend-results [role="region"]')).toBeFocused()

  await tab('overview').click()
  await items(page).nth(3).getByTestId('attention-open').click()
  await expect(page.locator('#diagnostic-results')).toBeInViewport()
})

test('the policy hint of a result without policy opens the setup form', async ({ page }) => {
  await openOverview(page, noPolicy)

  await items(page).first().getByTestId('attention-open').click()

  await expect(page.locator('#shell-tab-setup')).toHaveAttribute('aria-selected', 'true')
  await expect(page.locator('#policy-file')).toBeFocused()
})

test('open buttons have a unique accessible name that starts with their visible text', async ({ page }) => {
  await openOverview(page)

  const names = await page.getByTestId('attention-open').evaluateAll((nodes) => nodes.map((node) => ({ name: node.getAttribute('aria-label') ?? '', text: node.textContent?.trim() ?? '' })))
  expect(new Set(names.map((entry) => entry.name)).size).toBe(names.length)
  for (const entry of names) expect(entry.name.startsWith(entry.text)).toBe(true)
})

test('key metrics come from the overall summary and match the old formulas', async ({ page }) => {
  await openOverview(page)

  const tiles = page.getByTestId('metric-tile')
  await expect(page.getByRole('heading', { name: OVERVIEW_LABELS.metricsTitle })).toBeVisible()
  expect(await tiles.evaluateAll((nodes) => nodes.map((node) => `${node.getAttribute('data-metric')}=${node.getAttribute('data-value')}`))).toEqual([
    'rps=121.67', 'p95=2340', 'p99=3000', 'max=4000',
  ])
  await expect(tiles.first()).toContainText(OVERVIEW_LABELS.metricRps)
})

test('the load chart draws three tracks on one time axis and never joins the lines across a gap', async ({ page }) => {
  await openOverview(page)

  await expect(chart(page)).toBeVisible()
  await expect(page.getByRole('group', { name: OVERVIEW_LABELS.chartAria })).toHaveCount(1)
  for (const key of ['rps', 'errors', 'p95']) {
    await expect(page.getByTestId(`track-${key}`)).toBeVisible()
    await expect(page.getByTestId(`track-line-${key}`)).toHaveCount(2)
  }
  await expect(page.getByTestId('load-gaps')).toHaveText(OVERVIEW_LABELS.loadGaps(1))
  await expect(page.getByTestId('load-summary')).toHaveText(OVERVIEW_LABELS.loadSummary(6, 1))
  await expect(page.getByTestId('load-partial')).toHaveCount(0)
  await expect(page.getByTestId('cursor-line-rps')).toHaveCount(0)
})

test('one keyboard cursor moves every track together and announces itself through the slider only', async ({ page }) => {
  await openOverview(page)
  const control = slider(page)

  await expect(control).toHaveAttribute('type', 'range')
  await expect(control).toHaveAccessibleName(OVERVIEW_LABELS.cursorLabel)
  await expect(control).toHaveAttribute('aria-valuetext', OVERVIEW_LABELS.cursorNone)
  await control.focus()
  await expect(control).toHaveValue('0')
  await expect(page.getByTestId('cursor-time')).toHaveText(OVERVIEW_LABELS.cursorTime('0:00'))
  await page.keyboard.press('ArrowRight')
  await expect(control).toHaveValue('1')
  await expect(page.getByTestId('track-value-rps')).toHaveAttribute('data-value', '120.00')
  await expect(page.getByTestId('track-value-errors')).toHaveAttribute('data-value', '1')
  await expect(page.getByTestId('track-value-p95')).toHaveAttribute('data-value', '220')
  const [rps, errors, p95] = await lineX(page)
  expect(rps).toBe(errors)
  expect(errors).toBe(p95)

  await page.keyboard.press('ArrowRight')
  await page.keyboard.press('ArrowRight')
  await expect(control).toHaveValue('3')
  await expect(page.getByTestId('cursor-time')).toHaveText(OVERVIEW_LABELS.cursorTime('0:04'))
  await expect(control).toHaveAttribute('aria-valuetext', /0:04/)
  const afterGap = await lineX(page)
  expect(afterGap[0]).toBe(afterGap[1])
  expect(afterGap[1]).toBe(afterGap[2])
  expect(afterGap[0]).not.toBe(rps)

  await page.keyboard.press('End')
  await expect(control).toHaveValue('5')
  await page.keyboard.press('Home')
  await expect(control).toHaveValue('0')
  await expect(chart(page).locator('[aria-live], [role="status"], [role="alert"]')).toHaveCount(0)
})

test('hovering the plot moves the same cursor to the nearest interval', async ({ page }) => {
  await openOverview(page)
  await page.getByTestId('chart-plot').scrollIntoViewIfNeeded()
  const box = (await page.getByTestId('chart-plot').boundingBox())!

  await page.mouse.move(box.x + box.width - 1, box.y + 8)
  await expect(slider(page)).toHaveValue('5')
  await page.mouse.move(box.x + 1, box.y + 8)
  await expect(slider(page)).toHaveValue('0')
  await page.mouse.move(box.x + box.width * 0.6, box.y + 8)
  await expect(slider(page)).toHaveValue('3')
  const [rps, errors, p95] = await lineX(page)
  expect(rps).toBe(errors)
  expect(errors).toBe(p95)
})

test('an empty page shows a plain message instead of a zero line', async ({ page }) => {
  await openOverview(page, failing, { buckets: [], next_from_ms: null })

  await expect(page.getByTestId('load-empty')).toHaveText(OVERVIEW_LABELS.loadEmpty)
  await expect(chart(page)).toHaveCount(0)
  await expect(slider(page)).toHaveCount(0)
})

test('a truncated page says so and does not fetch more', async ({ page }) => {
  const paths = await openOverview(page, failing, { buckets, next_from_ms: 7000 })

  await expect(page.getByTestId('load-partial')).toHaveText(OVERVIEW_LABELS.loadPartial)
  expect(paths.filter((entry) => entry.endsWith('/buckets'))).toEqual([`GET /api/runs/${reference.run_id}/analyses/${reference.analysis_id}/buckets`])
})

test('the overview asks the server for nothing beyond the existing endpoints', async ({ page }) => {
  const paths = await openOverview(page)
  await slider(page).focus()
  await page.keyboard.press('ArrowRight')

  expect(paths.filter((entry) => entry.startsWith('POST'))).toEqual([])
  expect(paths.filter((entry) => entry.endsWith('/result'))).toHaveLength(1)
  expect(paths.filter((entry) => entry.endsWith('/buckets'))).toHaveLength(1)
})

test('old interface has no overview panel and keeps its own charts', async ({ page }) => {
  await fixtureApi(page)
  await page.goto('/')
  await page.getByRole('button', { name: 'overview.jtl' }).click()
  await page.locator(`button[title="${reference.analysis_id}"]`).click()

  await expect(page.locator('#verdict')).toBeVisible()
  await expect(page.getByTestId('overview-panel')).toHaveCount(0)
  await expect(page.locator('.load-charts')).toHaveCount(1)
})

test('the other tabs do not render the overview panel', async ({ page }) => {
  await openOverview(page)

  for (const key of ['tables', 'compare', 'advice', 'rules', 'setup']) {
    await page.locator(`#shell-tab-${key}`).click()
    await expect(page.getByTestId('overview-panel')).toHaveCount(0)
  }
  await page.locator('#shell-tab-overview').click()
  await expect(page.getByTestId('overview-panel')).toBeVisible()
  await expect(page.getByText(SHELL_LABELS.overviewEmpty)).toBeHidden()
})

for (const theme of ['light', 'dark'] as const) {
  test(`the overview has no serious axe violations with the cursor active: ${theme}`, async ({ page }) => {
    await page.emulateMedia({ colorScheme: theme })
    await openOverview(page)
    await slider(page).focus()
    await page.keyboard.press('ArrowRight')

    const axe = await new AxeBuilder({ page }).analyze()
    expect(axe.violations.filter((item) => item.impact === 'critical' || item.impact === 'serious').map((item) => item.id)).toEqual([])
  })
}

for (const size of [{ width: 1280, height: 800 }, { width: 375, height: 800 }]) {
  test(`the overview fits without horizontal scroll and controls are at least 44px at ${size.width}px`, async ({ page }) => {
    await page.setViewportSize(size)
    await openOverview(page)

    const width = await page.evaluate(() => ({ scroll: document.documentElement.scrollWidth, client: document.documentElement.clientWidth }))
    expect(width.scroll).toBeLessThanOrEqual(width.client)
    for (const control of [slider(page), ...(await page.getByTestId('attention-open').all())]) {
      const box = (await control.boundingBox())!
      expect(box.height).toBeGreaterThanOrEqual(44)
      expect(box.width).toBeGreaterThanOrEqual(44)
    }
    const plot = (await page.getByTestId('chart-plot').boundingBox())!
    expect(plot.width).toBeGreaterThan(size.width * 0.5)
  })
}
