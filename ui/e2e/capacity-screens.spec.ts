import AxeBuilder from '@axe-core/playwright'
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

// Блок ёмкости на «Обзоре»: граница словами, итог, ступени графиком и таблицей.
const blockStage = (id: string, target: number, verdict: string, over: Record<string, unknown> = {}) => ({
  id, target, achieved: String(target), achieved_statistic: 'p05_10s', observed_min: target, observed_max: target, complete_bins: 30, expected_bins: 30,
  target_tolerance_ratio: 0.02, verified_bound_load: verdict === 'PASS' || verdict === 'FAIL' ? String(target) : null, verdict, reasons: [], evidence_refs: [], ...over,
})
const blockStages = [blockStage('step-40', 40, 'PASS'), blockStage('step-96', 96, 'PASS', { achieved: '95.745', verified_bound_load: '95.745' }), blockStage('step-104', 104, 'FAIL', { achieved: '103.745', verified_bound_load: '103.745' })]
const blockResult = (verdict: string, over: Record<string, unknown> = {}, stages: unknown[] = blockStages, reasons: string[] = []) => ({
  ...result(verdict),
  capacity_summary: { ...capacity(verdict), stages, reasons, ...over },
})
const smallSampleStages = [blockStage('step-40', 40, 'PASS'), blockStage('step-96', 96, 'INDETERMINATE', { achieved: null, reasons: ['CAPACITY_INSUFFICIENT_SAMPLES'] })]

test('capacity PASS: the block gives the bound in words, the verdict and the requirement without contradiction', async ({ page }) => {
  await open(page, blockResult('PASS'))

  const block = page.getByTestId('capacity-block')
  await expect(block).toBeVisible()
  await expect(block.getByTestId('capacity-block-verdict')).toHaveText(OVERVIEW_LABELS.capacityVerdict.PASS)
  await expect(block.getByTestId('capacity-block-bound')).toContainText('От 95,745 до 103,745 requests/s')
  await expect(block.getByTestId('capacity-block-statement')).toContainText('Требуемая ёмкость не выше 95,745 requests/s')
  await expect(block.getByTestId('capacity-block-statement')).toContainText(OVERVIEW_LABELS.capacityPassFailedAbove)
  await expect(block).not.toContainText(OVERVIEW_LABELS.capacityVerdict.FAIL)
  const rows = block.getByTestId('capacity-block-stage')
  await expect(rows).toHaveCount(3)
  await expect(rows.nth(0)).toContainText('step-40')
  await expect(rows.nth(1)).toContainText('95,745')
  await expect(rows.nth(1)).toContainText('Выдержана')
  await expect(rows.nth(2)).toContainText('Нарушение')
  await expect(rows.nth(2)).toHaveAttribute('data-kind', 'fail')
  await expect(block).toContainText('Ступеней: 3; выдержано: 2, нарушено: 1')
})

test('capacity block: the chart is hidden from assistive tech, the table is its text equivalent and the verdict is not only a colour', async ({ page }) => {
  await open(page, blockResult('PASS', {}, [...blockStages, blockStage('step-110', 110, 'INDETERMINATE')]))

  const block = page.getByTestId('capacity-block')
  await expect(block.getByRole('region', { name: OVERVIEW_LABELS.capacityTableRegion })).toBeVisible()
  await expect(block.getByRole('columnheader')).toHaveText([...OVERVIEW_LABELS.capacityHeads])
  await expect(block.getByTestId('capacity-block-bar')).toHaveCount(4)
  await expect(block.getByTestId('capacity-block-chart')).toHaveAttribute('aria-hidden', 'true')
  await expect(block.getByTestId('capacity-block-table').getByRole('row')).toHaveCount(5)
  const dashes = (kind: string) => block.locator(`[data-kind="${kind}"] rect`).first().evaluate((node) => getComputedStyle(node).strokeDasharray)
  expect(await dashes('pass')).toBe('none')
  expect(await dashes('unverified')).not.toBe('none')
  await expect(block.locator('[data-kind="unverified"] .status-text')).toContainText('Не подтверждена')
  await expect(block.locator('[data-kind="unverified"] .status-text')).toContainText('?')
  const widths = await block.locator('rect').evaluateAll((nodes) => nodes.map((node) => Number(node.getAttribute('width'))))
  expect(widths).toEqual([...widths].sort((left, right) => left - right))
  expect(widths[widths.length - 1]).toBe(1000)
})

test('capacity FAIL: one reason under attention, the block says the requirement is not below the upper bound', async ({ page }) => {
  await open(page, blockResult('FAIL'))

  await expect(page.getByTestId('attention-item').filter({ hasText: OVERVIEW_LABELS.capacityFailTitle })).toHaveCount(1)
  const block = page.getByTestId('capacity-block')
  await expect(block.getByTestId('capacity-block-verdict')).toHaveText(OVERVIEW_LABELS.capacityVerdict.FAIL)
  await expect(block.getByTestId('capacity-block-statement')).toContainText('Требуемая ёмкость не ниже 103,745 requests/s')
  await expect(block).not.toContainText(OVERVIEW_LABELS.capacityVerdict.PASS)
})

test('capacity NO_VERDICT: the bound is words, the requirement sits inside it', async ({ page }) => {
  await open(page, blockResult('NO_VERDICT'))

  const block = page.getByTestId('capacity-block')
  await expect(block.getByTestId('capacity-block-verdict')).toHaveText(OVERVIEW_LABELS.capacityVerdict.NO_VERDICT)
  await expect(block.getByTestId('capacity-block-statement')).toContainText('лежит между 95,745 и 103,745 requests/s')
})

test('capacity small sample: the stage is unverified and marked, the bound lines are absent', async ({ page }) => {
  await open(page, blockResult('NO_VERDICT', { bound_type: 'INDETERMINATE', lower_inclusive: null, upper_exclusive: null }, smallSampleStages, ['CAPACITY_INSUFFICIENT_SAMPLES', 'CAPACITY_STAGE_NOT_VERIFIED']))

  const block = page.getByTestId('capacity-block')
  await expect(block.getByTestId('capacity-block-bound')).toContainText('Граница ёмкости не определена')
  await expect(block.getByTestId('capacity-block-statement')).toHaveText(OVERVIEW_LABELS.capacityStatementIndeterminate)
  const row = block.getByTestId('capacity-block-stage').nth(1)
  await expect(row).toContainText('Не подтверждена')
  await expect(row.getByTestId('capacity-block-small-sample')).toBeVisible()
  await expect(row).toContainText('нет данных')
  await expect(block.getByTestId('capacity-block-small-sample-note')).toBeVisible()
  await expect(block.locator('.overview-capacity__bound')).toHaveCount(0)
})

test('capacity block opens the capacity table on the tables tab', async ({ page }) => {
  await open(page, blockResult('PASS'))

  await page.getByTestId('capacity-block-open').click()
  await expect(page.locator('#shell-tab-tables')).toHaveAttribute('aria-selected', 'true')
  await expect(page.locator('#capacity-results')).toBeVisible()
})

test('a standard result has no capacity block', async ({ page }) => {
  await open(page, { ...result('PASS'), analysis_mode: 'standard', capacity_summary: undefined })

  await expect(page.getByTestId('metric-tile').first()).toBeVisible()
  await expect(page.getByTestId('capacity-block')).toHaveCount(0)
})

test('old interface does not render the capacity block', async ({ page }) => {
  await open(page, blockResult('PASS'), 'old')

  await expect(page.getByTestId('capacity-block')).toHaveCount(0)
})

for (const theme of ['light', 'dark'] as const) {
  test('capacity block has no axe violations: ' + theme, async ({ page }) => {
    await page.emulateMedia({ colorScheme: theme })
    await open(page, blockResult('PASS', {}, [...blockStages, blockStage('step-110', 110, 'INDETERMINATE')]))
    await expect(page.getByTestId('capacity-block')).toBeVisible()

    const axe = await new AxeBuilder({ page }).include('[data-testid="capacity-block"]').analyze()
    expect(axe.violations.map((item) => item.id)).toEqual([])
  })
}

for (const size of [{ width: 1280, height: 800 }, { width: 375, height: 800 }, { width: 320, height: 568 }]) {
  test('capacity block fits without page scroll and the button is 44px at ' + size.width + 'px', async ({ page }) => {
    await page.setViewportSize(size)
    await open(page, blockResult('PASS'))

    const block = page.getByTestId('capacity-block')
    await expect(block).toBeVisible()
    const width = await page.evaluate(() => ({ scroll: document.documentElement.scrollWidth, client: document.documentElement.clientWidth }))
    expect(width.scroll).toBeLessThanOrEqual(width.client)
    const box = (await block.getByTestId('capacity-block-open').boundingBox())!
    expect(box.height).toBeGreaterThanOrEqual(44)
    expect(box.width).toBeGreaterThanOrEqual(44)
    const clipped = await block.locator('h2, h3, p, button').evaluateAll((nodes) => nodes.filter((node) => node.scrollWidth > node.clientWidth + 1).length)
    expect(clipped).toBe(0)
  })
}
