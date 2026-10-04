import AxeBuilder from '@axe-core/playwright'
import { expect, test, type Page } from '@playwright/test'
import { OVERVIEW_LABELS } from '../src/shell/labels'
import { CAPACITY_LABELS, TREND_LABELS } from '../src/shell/labels.tables'

const reference = { run_id: 'capacity-trend-run', analysis_id: 'a'.repeat(64) }
const run = { ...reference, source_type: 'jmeter', sha256: 'b'.repeat(64), size_bytes: 100, original_filename: 'capacity-trend.jtl' }
const capacity = {
  schema_version: 'capacity.v1', load_axis: 'rps', unit: 'requests/s', bound_type: 'INDETERMINATE', lower_inclusive: null, upper_exclusive: null,
  policy_verdict: 'NO_VERDICT', reasons: ['CAPACITY_STAGE_NOT_VERIFIED'], capacity_knee: null, knee_reason: 'KNEE_DETECTOR_NOT_IMPLEMENTED',
  stages: [
    { id: 'ramp-300', target: 300, achieved: 296, achieved_statistic: 'p05_10s', observed_min: 296, observed_max: 296, complete_bins: 30, expected_bins: 30, target_tolerance_ratio: 0.02, verified_bound_load: 296, verdict: 'PASS', reasons: [], evidence_refs: ['ref-1'] },
    { id: 'ramp-350', target: 350, achieved: 344, achieved_statistic: 'p05_10s', observed_min: 344, observed_max: 344, complete_bins: 30, expected_bins: 30, target_tolerance_ratio: 0.02, verified_bound_load: null, verdict: 'INDETERMINATE', reasons: ['CAPACITY_INSUFFICIENT_SAMPLES'], evidence_refs: [] },
  ],
}
const trendSummary = { id: 'trend-summary', type: 'trend_summary', checks_total: 1, observed: 1, not_material: 0, insufficient: 0, unavailable: 0, method: 'slope-materiality.v1', uncertainty: 'NOT_ESTIMATED' }
const trendCheck = {
  id: 'trend-check-1', type: 'trend_check', check_id: 'cpu-trend', series_id: 'host-1:cpu.usage', metric: 'cpu.usage', unit: 'percent', entity: 'host-1',
  window_id: 'steady-300', window_from_epoch_ms: 1767225600000, window_to_epoch_ms: 1767225900000,
  declared_direction: 'either', status: 'TREND_OBSERVED', min_cells: 10, expected_cells: 30, observed_cells: 30, missing_cells: 0, longest_gap_cells: 0,
  median: '41.25', slope_per_second: '0.0135', split_half_shift: '6.40',
  magnitude_gate: { min_slope_units_per_second: '0.001', min_split_half_shift_pct: '5', required_split_half_shift_units: '2.0625' },
  observed_direction: 'increase', method: 'slope-materiality.v1', uncertainty: 'NOT_ESTIMATED', reasons: ['STATIONARITY_NOT_EVALUATED'],
}
const result = {
  schema_version: 'analysis-result.v1', run_id: reference.run_id, analysis_mode: 'capacity_step', run_validity: 'VALID', policy_verdict: 'NO_VERDICT',
  analysis_coverage: { status: 'COMPLETE', reasons: [] }, findings: [], evidence: [trendSummary, trendCheck], capacity_summary: capacity,
}

async function fixtureApi(page: Page, saved: Record<string, unknown> = result) {
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

async function pickSavedAnalysis(page: Page) {
  await page.getByRole('button', { name: 'capacity-trend.jtl' }).click()
  await page.locator(`button[title="${reference.analysis_id}"]`).click()
}

async function openNewShellWithResult(page: Page, saved: Record<string, unknown> = result) {
  await fixtureApi(page, saved)
  await page.goto('/?shell=new')
  await pickSavedAnalysis(page)
  await expect(page.locator('#verdict')).toBeVisible()
}

async function openTables(page: Page, saved: Record<string, unknown> = result) {
  await openNewShellWithResult(page, saved)
  await page.locator('#shell-tab-tables').click()
}

test('new shell shows capacity stages in Russian with sample notes', async ({ page }) => {
  await openTables(page)
  const region = page.getByTestId('capacity-results').getByRole('region', { name: CAPACITY_LABELS.region })
  await expect(region).toBeVisible()
  await expect(page.getByTestId('capacity-stage-row')).toHaveCount(2)
  await expect(page.locator('#stage-ramp-300')).toContainText(CAPACITY_LABELS.stageVerdict.PASS)
  await expect(page.locator('#stage-ramp-350')).toContainText(CAPACITY_LABELS.stageVerdict.INDETERMINATE)
  await expect(page.getByTestId('capacity-small-sample')).toHaveText(CAPACITY_LABELS.smallSampleNote)
  await expect(page.locator('#stage-ramp-350').getByTestId('stage-small-sample')).toHaveText(CAPACITY_LABELS.smallSampleMark)
  await expect(page.getByRole('region', { name: 'Capacity stages' })).toHaveCount(0)
})

test('unknown capacity bound and reason still render', async ({ page }) => {
  await openTables(page, { ...result, capacity_summary: { ...capacity, bound_type: 'UNKNOWN_BOUND', lower_inclusive: 296, upper_exclusive: 344, reasons: ['UNKNOWN_REASON'], stages: [{ ...capacity.stages[0], reasons: ['UNKNOWN_REASON'] }] } })
  await expect(page.locator('#capacity-results')).toContainText(CAPACITY_LABELS.boundText('UNKNOWN_BOUND', '296', '344', 'requests/s'))
  await expect(page.locator('#capacity-results')).toContainText('UNKNOWN_REASON')
})

test('new shell shows trend checks in Russian and hides the old region', async ({ page }) => {
  await openTables(page)
  await expect(page.getByRole('region', { name: TREND_LABELS.region })).toBeVisible()
  await expect(page.getByTestId('trend-row')).toHaveCount(1)
  await expect(page.getByTestId('trend-row')).toContainText(TREND_LABELS.statusText.TREND_OBSERVED)
  await expect(page.getByRole('region', { name: 'Resource trend checks' })).toHaveCount(0)
})

test('new shell omits capacity and trend sections without their data', async ({ page }) => {
  await openTables(page, { ...result, analysis_mode: 'standard', policy_verdict: 'NO_POLICY', evidence: [], capacity_summary: undefined })
  await expect(page.locator('#capacity-results')).toHaveCount(0)
  await expect(page.locator('#trend-results')).toHaveCount(0)
})

test('old shell retains English capacity and trend regions', async ({ page }) => {
  await fixtureApi(page)
  await page.goto('/?shell=old')
  await pickSavedAnalysis(page)
  await expect(page.getByRole('region', { name: 'Capacity stages' })).toBeVisible()
  await expect(page.getByRole('region', { name: 'Resource trend checks' })).toBeVisible()
})

test('overview capacity attention opens and focuses the capacity region', async ({ page }) => {
  await openNewShellWithResult(page)
  await page.getByTestId('attention-item').getByRole('button', { name: OVERVIEW_LABELS.openCapacity }).first().click()
  await expect(page.locator('#shell-tab-tables')).toHaveAttribute('aria-selected', 'true')
  await expect(page.locator('#capacity-results .table-wrap')).toBeFocused()
})

for (const theme of ['light', 'dark'] as const) {
  test(`capacity and trend tables have no serious axe violations in ${theme}`, async ({ page }) => {
    await page.emulateMedia({ colorScheme: theme })
    await openTables(page)
    const axe = await new AxeBuilder({ page }).include('#capacity-results').include('#trend-results').analyze()
    expect(axe.violations.filter((item) => item.impact === 'critical' || item.impact === 'serious').map((item) => item.id)).toEqual([])
  })
}

for (const size of [{ width: 1280, height: 800 }, { width: 375, height: 800 }, { width: 320, height: 800 }]) {
  test(`capacity and trend tables have no horizontal page scroll at ${size.width}px`, async ({ page }) => {
    await page.setViewportSize(size)
    await openTables(page)
    const width = await page.evaluate(() => ({ scroll: document.documentElement.scrollWidth, client: document.documentElement.clientWidth }))
    expect(width.scroll).toBeLessThanOrEqual(width.client)
  })
}
