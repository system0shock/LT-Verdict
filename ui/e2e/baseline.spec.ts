import { expect, test, type Page } from '@playwright/test'
import AxeBuilder from '@axe-core/playwright'
import { BASELINE_LABELS } from '../src/shell/labels'
import { COMPARE_LABELS } from '../src/shell/labels.compare'

async function analyze(page: Page, name: string, elapsed: number, timestamp: number) {
  await page.getByTestId('input-file').setInputFiles({
    name,
    mimeType: 'text/csv',
    buffer: Buffer.from(`timeStamp,elapsed,label,success\n${timestamp},${elapsed},baseline-test,true\n`),
  })
  await page.getByRole('button', { name: 'Analyze run', exact: true }).click()
  await expect(page.locator('#verdict')).toContainText('NO_POLICY')
  const href = await page.getByRole('link', { name: 'Download JSON', exact: true }).getAttribute('href')
  const match = href?.match(/^\/api\/runs\/([^/]+)\/analyses\/([a-f0-9]{64})\/report/)
  expect(match).toBeTruthy()
  return { run_id: match![1]!, analysis_id: match![2]! }
}

async function openAnalysis(page: Page, filename: string, analysisId: string) {
  await page.getByTestId('run-list').getByRole('button').filter({ hasText: filename }).click()
  await page.getByRole('button', { name: `Analysis ${analysisId.slice(0, 12)}`, exact: false }).click()
  await expect(page.locator('#verdict')).toContainText('NO_POLICY')
}

test.beforeEach(async ({ page }) => {
  await page.goto('/')
  const bootstrap = await (await page.request.get('/api/bootstrap')).json() as { csrf_token: string }
  await page.request.delete('/api/baseline', {
    headers: { Origin: new URL(page.url()).origin, 'X-LTV-CSRF': bootstrap.csrf_token },
  })
  await page.reload()
})

test('pins manual baseline across reload and compares changed achieved load without another job', async ({ page }, testInfo) => {
  const baseline = await analyze(page, 'baseline-manual.jtl', 100, 1767225600000)
  await page.getByLabel('Comparison series', { exact: true }).fill('Checkout / test environment')
  await page.getByRole('button', { name: 'Set as baseline', exact: true }).click()
  await expect(page.getByTestId('baseline-selection')).toContainText(baseline.analysis_id)
  await page.reload()
  await expect(page.getByTestId('baseline-selection')).toContainText('manual')
  await expect(page.getByTestId('baseline-selection')).toContainText(baseline.analysis_id)
  const current = await analyze(page, 'baseline-current.jtl', 200, 1767225601000)
  let jobs = 0
  page.on('request', (request) => {
    if (request.method() === 'POST' && new URL(request.url()).pathname === '/api/jobs') jobs += 1
  })
  await page.getByLabel('Confirmed same planned test conditions', { exact: true }).check()
  await page.getByRole('button', { name: 'Save condition decision', exact: true }).click()
  await expect(page.getByTestId('baseline-condition-status')).toContainText('Saved CONFIRMED')
  await page.getByRole('button', { name: 'Compare selected analysis', exact: true }).click()
  const p95 = page.getByTestId('comparison-response_time_p95_ms')
  await expect(p95).toContainText('100')
  await expect(p95).toContainText('200')
  await expect(p95.locator('td').nth(3)).toHaveText('100')
  await expect(p95.locator('td').nth(4)).toHaveText('100%')
  const throughput = page.getByTestId('comparison-throughput_rps')
  await expect(throughput.locator('td').nth(3)).toHaveText('-5')
  await expect(throughput.locator('td').nth(4)).toHaveText('-50%')
  await expect(page.getByTestId('comparison-error_rate_ratio')).toContainText('ZERO_BASELINE')
  await expect(page.getByTestId('baseline-comparison')).toContainText('USER_CONFIRMED')
  await expect(page.getByTestId('baseline-warnings')).toHaveCount(0)
  await page.getByText('Baseline/current charts', { exact: true }).click()
  await page.getByRole('button', { name: 'Load comparison charts', exact: true }).click()
  await expect(page.getByTestId('baseline-comparison').getByRole('img', { name: 'P95 latency', exact: true })).toBeVisible()
  await expect(page.getByTestId('baseline-comparison')).toContainText('Solid: current · dashed: baseline')
  await expect(page.locator('#verdict')).toContainText('NO_POLICY')
  expect(jobs).toBe(0)

  await page.reload()
  await openAnalysis(page, 'baseline-current.jtl', current.analysis_id)
  await expect(page.getByLabel('Confirmed same planned test conditions', { exact: true })).toBeChecked()
  await expect(page.getByTestId('baseline-condition-status')).toContainText('Saved CONFIRMED')

  await openAnalysis(page, 'baseline-manual.jtl', baseline.analysis_id)
  await page.getByLabel('Not confirmed', { exact: true }).check()
  await page.getByRole('button', { name: 'Save condition decision', exact: true }).click()
  await page.getByRole('button', { name: 'Compare selected analysis', exact: true }).click()
  await expect(page.getByTestId('baseline-condition-status')).toContainText('Saved NOT_CONFIRMED')
  await expect(page.getByTestId('baseline-comparison')).toContainText('UNCONFIRMED')

  await openAnalysis(page, 'baseline-current.jtl', current.analysis_id)
  await expect(page.getByLabel('Confirmed same planned test conditions', { exact: true })).toBeChecked()
  await page.getByLabel('Unknown', { exact: true }).check()
  await page.getByRole('button', { name: 'Save condition decision', exact: true }).click()
  await page.reload()
  await openAnalysis(page, 'baseline-current.jtl', current.analysis_id)
  await expect(page.getByLabel('Unknown', { exact: true })).toBeChecked()
  await expect(page.getByTestId('baseline-condition-status')).toContainText('Saved UNKNOWN')
  await page.getByRole('button', { name: 'Compare selected analysis', exact: true }).click()
  await expect(page.getByTestId('baseline-comparison')).toContainText('UNCONFIRMED')

  const audit = await new AxeBuilder({ page }).include('#baseline-panel').analyze()
  expect(audit.violations).toEqual([])
  await page.locator('#baseline-panel').screenshot({ path: testInfo.outputPath('baseline-desktop.png') })
  await page.getByRole('button', { name: 'Dark theme', exact: true }).click()
  await page.setViewportSize({ width: 500, height: 1000 })
  const layout = await page.evaluate(() => ({
    viewport: window.innerWidth,
    width: document.documentElement.scrollWidth,
    panel: [...document.querySelectorAll('#baseline-panel, #baseline-panel > *')].map((element) => ({
      tag: element.tagName,
      width: element.getBoundingClientRect().width,
      right: element.getBoundingClientRect().right,
      columns: getComputedStyle(element).gridTemplateColumns,
    })),
  }))
  expect(layout.width, JSON.stringify(layout)).toBeLessThanOrEqual(layout.viewport)
  await page.locator('#baseline-panel').screenshot({ path: testInfo.outputPath('baseline-dark-mobile.png') })
  await page.getByRole('button', { name: 'Clear baseline', exact: true }).click()
  await expect(page.getByTestId('baseline-selection')).toHaveCount(0)
  await expect(page.getByTestId('baseline-comparison')).toHaveCount(0)
})

test('warns when a run is compared with itself', async ({ page }) => {
  await analyze(page, 'baseline-self.jtl', 100, 1767225650000)
  await page.getByRole('button', { name: 'Set as baseline', exact: true }).click()
  await page.getByRole('button', { name: 'Compare selected analysis', exact: true }).click()
  await expect(page.getByTestId('baseline-comparison')).toContainText('UNCONFIRMED')
  await expect(page.getByTestId('baseline-warnings').locator('li')).toHaveText([
    BASELINE_LABELS.warnings.BASELINE_IS_CURRENT_ANALYSIS,
  ])
})

test('selects the middle real run statistically and confirms a new compared pair', async ({ page }) => {
  await page.locator('#baseline-panel summary').click()
  await analyze(page, 'stat-fast.jtl', 100, 1767225700000)
  await page.getByRole('button', { name: 'Add selected candidate', exact: true }).click()
  const middle = await analyze(page, 'stat-middle.jtl', 110, 1767225701000)
  await page.getByRole('button', { name: 'Add selected candidate', exact: true }).click()
  await page.getByLabel('Same planned test conditions', { exact: true }).check()
  await analyze(page, 'stat-slow.jtl', 1000, 1767225702000)
  await page.getByRole('button', { name: 'Add selected candidate', exact: true }).click()
  await expect(page.getByLabel('Same planned test conditions', { exact: true })).not.toBeChecked()
  await expect(page.getByRole('button', { name: 'Select statistically', exact: true })).toBeDisabled()
  await page.getByLabel('Same planned test conditions', { exact: true }).check()
  await page.getByRole('button', { name: 'Select statistically', exact: true }).click()
  await expect(page.getByTestId('baseline-selection')).toContainText(middle.analysis_id)
  await expect(page.getByTestId('baseline-selection')).toContainText('statistical')
  await expect(page.getByTestId('baseline-selection')).toContainText('median-rank-v1')
  await page.getByRole('button', { name: 'Compare selected analysis', exact: true }).click()
  await expect(page.getByTestId('baseline-comparison')).toContainText('UNCONFIRMED')
  await expect(page.getByTestId('baseline-warnings').locator('li')).toHaveText([
    BASELINE_LABELS.warnings.CURRENT_IN_CANDIDATE_SET,
  ])
  await expect(page.getByTestId('comparison-response_time_p95_ms').locator('td').nth(3)).toHaveText('890')
  await openAnalysis(page, 'stat-middle.jtl', middle.analysis_id)
  await page.getByRole('button', { name: 'Compare selected analysis', exact: true }).click()
  await expect(page.getByTestId('baseline-warnings').locator('li')).toHaveText([
    BASELINE_LABELS.warnings.BASELINE_IS_CURRENT_ANALYSIS,
    BASELINE_LABELS.warnings.CURRENT_IN_CANDIDATE_SET,
  ])
  await analyze(page, 'stat-new.jtl', 2000, 1767225703000)
  await expect(page.getByTestId('baseline-selection')).toContainText(middle.analysis_id)
  await expect(page.getByTestId('baseline-comparison')).toHaveCount(0)
  await page.getByRole('button', { name: 'Compare selected analysis', exact: true }).click()
  await expect(page.getByTestId('baseline-comparison')).toContainText('UNCONFIRMED')
  await expect(page.getByTestId('baseline-warnings')).toHaveCount(0)
  await page.getByLabel('Confirmed same planned test conditions', { exact: true }).check()
  await page.getByRole('button', { name: 'Save condition decision', exact: true }).click()
  await expect(page.getByTestId('baseline-condition-status')).toContainText('Saved CONFIRMED')
  await page.getByRole('button', { name: 'Compare selected analysis', exact: true }).click()
  await expect(page.getByTestId('baseline-comparison')).toContainText('USER_CONFIRMED')
  await expect(page.getByTestId('comparison-response_time_p95_ms').locator('td').nth(3)).toHaveText('1890')
  await expect(page.getByTestId('baseline-candidates').locator('li')).toHaveCount(3)
  await page.reload()
  await expect(page.getByTestId('baseline-selection')).toContainText(middle.analysis_id)
})

test('changing a window id shows the decision of the new pair', async ({ page }) => {
  await analyze(page, 'baseline-window-a.jtl', 100, 1767225750000)
  await page.getByRole('button', { name: 'Set as baseline', exact: true }).click()
  await analyze(page, 'baseline-window-b.jtl', 200, 1767225751000)
  await page.getByLabel('Baseline window ID', { exact: true }).fill('before')
  await page.getByLabel('Current window ID', { exact: true }).fill('after')
  await page.getByLabel('Confirmed same planned test conditions', { exact: true }).check()
  await page.getByRole('button', { name: 'Save condition decision', exact: true }).click()
  await expect(page.getByTestId('baseline-condition-status')).toContainText('Saved CONFIRMED')
  await page.getByLabel('Current window ID', { exact: true }).fill('other')
  await expect(page.getByLabel('Unknown', { exact: true })).toBeChecked()
  await expect(page.getByTestId('baseline-condition-status')).toContainText('No saved decision for this exact pair.')
  await page.getByLabel('Current window ID', { exact: true }).fill('after')
  await expect(page.getByLabel('Confirmed same planned test conditions', { exact: true })).toBeChecked()
  await expect(page.getByTestId('baseline-condition-status')).toContainText('Saved CONFIRMED')
})

test('does not allow replacing the baseline while a condition decision is being saved', async ({ page }) => {
  await analyze(page, 'baseline-busy-a.jtl', 100, 1767225760000)
  await page.getByRole('button', { name: 'Set as baseline', exact: true }).click()
  await analyze(page, 'baseline-busy-b.jtl', 200, 1767225761000)
  let started = false
  let release!: () => void
  const held = new Promise<void>((resolve) => { release = resolve })
  await page.route('**/baseline-conditions', async (route) => {
    if (route.request().method() !== 'POST') return route.continue()
    started = true
    await held
    await route.continue()
  })
  await page.getByLabel('Confirmed same planned test conditions', { exact: true }).check()
  await page.getByRole('button', { name: 'Save condition decision', exact: true }).click()
  await expect.poll(() => started).toBe(true)
  await expect(page.getByRole('button', { name: 'Set as baseline', exact: true })).toBeDisabled()
  await expect(page.getByRole('button', { name: 'Clear baseline', exact: true })).toBeDisabled()
  release()
  await expect(page.getByTestId('baseline-condition-status')).toContainText('Saved CONFIRMED')
  await expect(page.getByRole('button', { name: 'Set as baseline', exact: true })).toBeEnabled()
})

test('shows the empty-window hint for the empty side and the incompatibility hint for an incompatible baseline', async ({ page }) => {
  await analyze(page, 'baseline-hints-a.jtl', 100, 1767225770000)
  await page.getByRole('button', { name: 'Set as baseline', exact: true }).click()
  const current = await analyze(page, 'baseline-hints-b.jtl', 200, 1767225771000)
  const baseline = (await (await page.request.get('/api/baseline')).json()).baseline
  const emptyWindowMetrics = [
    { metric: 'response_time_p50_ms', unit: 'ms' },
    { metric: 'response_time_p95_ms', unit: 'ms' },
    { metric: 'response_time_p99_ms', unit: 'ms' },
    { metric: 'throughput_rps', unit: 'rps' },
    { metric: 'error_rate_ratio', unit: 'ratio' },
  ].map((metric) => ({
    ...metric,
    baseline: null,
    current: null,
    delta: null,
    delta_percent: null,
    reason: 'EMPTY_WINDOW',
    percent_reason: 'EMPTY_WINDOW',
    status: 'INSUFFICIENT_DATA',
  }))
  const compatibleWindowMetrics = emptyWindowMetrics.map((metric) => ({
    ...metric,
    baseline: '100',
    current: '200',
    delta: '100',
    delta_percent: '100',
    reason: null,
    percent_reason: null,
    status: 'DESCRIPTIVE',
  }))
  const overallMetrics = [
    { metric: 'response_time_p95_ms', unit: 'ms' },
    { metric: 'response_time_p99_ms', unit: 'ms' },
    { metric: 'throughput_rps', unit: 'rps' },
    { metric: 'error_rate_ratio', unit: 'ratio' },
  ]
  let scenario = {
    reasons: ['BASELINE_WINDOW_EMPTY', 'INCOMPLETE_METRICS'],
    incompatible: false,
    windowStatus: 'INSUFFICIENT_DATA',
    empty: true,
  }
  await page.route(/\/api\/runs\/[^/]+\/analyses\/[^/]+\/comparison/, async (route) => {
    const reason = scenario.incompatible ? 'INCOMPATIBLE_METRIC_DEFINITION' : null
    await route.fulfill({ json: {
      baseline,
      current,
      comparability: 'UNCONFIRMED',
      warnings: [],
      conditions: null,
      metrics: overallMetrics.map((metric) => ({
        ...metric,
        baseline: null,
        current: null,
        delta: null,
        delta_percent: null,
        reason,
        percent_reason: reason,
      })),
      window_comparison: {
        status: scenario.windowStatus,
        baseline_window: 'steady',
        current_window: 'steady',
        baseline_sample_count: 0,
        current_sample_count: 0,
        baseline_duration_ms: 1000,
        current_duration_ms: 1000,
        min_change_percent: '5',
        min_error_rate_delta: '0.001',
        reasons: scenario.reasons,
        metrics: scenario.empty ? emptyWindowMetrics : compatibleWindowMetrics,
      },
    } })
  })
  await page.getByLabel('Baseline window ID', { exact: true }).fill('steady')
  await page.getByLabel('Current window ID', { exact: true }).fill('steady')
  const compare = page.getByRole('button', { name: 'Compare selected analysis', exact: true })
  await compare.click()
  await expect(page.getByTestId('baseline-empty-window')).toContainText(BASELINE_LABELS.emptyWindowHint)
  await expect(page.getByTestId('baseline-empty-window')).toContainText(BASELINE_LABELS.emptyBaselineWindow)
  await expect(page.getByTestId('baseline-empty-window')).not.toContainText(BASELINE_LABELS.emptyCurrentWindow)
  scenario = { reasons: ['CURRENT_WINDOW_EMPTY', 'INCOMPLETE_METRICS'], incompatible: false, windowStatus: 'INSUFFICIENT_DATA', empty: true }
  await compare.click()
  await expect(page.getByTestId('baseline-empty-window')).toContainText(BASELINE_LABELS.emptyCurrentWindow)
  await expect(page.getByTestId('baseline-empty-window')).not.toContainText(BASELINE_LABELS.emptyBaselineWindow)
  scenario = { reasons: ['BASELINE_WINDOW_EMPTY', 'CURRENT_WINDOW_EMPTY', 'INCOMPLETE_METRICS'], incompatible: false, windowStatus: 'INSUFFICIENT_DATA', empty: true }
  await compare.click()
  await expect(page.getByTestId('baseline-empty-window')).toContainText(BASELINE_LABELS.emptyBaselineWindow)
  await expect(page.getByTestId('baseline-empty-window')).toContainText(BASELINE_LABELS.emptyCurrentWindow)
  scenario = { reasons: ['INCOMPLETE_METRICS'], incompatible: false, windowStatus: 'INSUFFICIENT_DATA', empty: true }
  await compare.click()
  await expect(page.getByTestId('baseline-empty-window')).toHaveCount(0)
  scenario = { reasons: ['INCOMPATIBLE_METRIC_DEFINITION'], incompatible: true, windowStatus: 'NOT_EVALUATED', empty: true }
  await compare.click()
  await expect(page.getByTestId('baseline-incompatible')).toContainText(BASELINE_LABELS.incompatibleHint)
  await expect(page.getByTestId('baseline-incompatible')).toContainText('разным набором данных')
  scenario = { reasons: [], incompatible: false, windowStatus: 'DESCRIPTIVE', empty: false }
  await compare.click()
  await expect(page.getByTestId('baseline-incompatible')).toHaveCount(0)
})

test('explains the incompatibility hint when a mixed-semantics candidate set is rejected', async ({ page }) => {
  await page.locator('#baseline-panel summary').click()
  await analyze(page, 'mixed-fast.jtl', 100, 1767225790000)
  await page.getByRole('button', { name: 'Add selected candidate', exact: true }).click()
  await analyze(page, 'mixed-middle.jtl', 110, 1767225791000)
  await page.getByRole('button', { name: 'Add selected candidate', exact: true }).click()
  await page.getByLabel('Same planned test conditions', { exact: true }).check()
  await analyze(page, 'mixed-slow.jtl', 1000, 1767225792000)
  await page.getByRole('button', { name: 'Add selected candidate', exact: true }).click()
  await page.getByLabel('Same planned test conditions', { exact: true }).check()
  await page.route('**/api/baseline', async (route) => {
    if (route.request().method() !== 'POST') return route.continue()
    await route.fulfill({ status: 422, json: {
      error: {
        code: 'BASELINE_MIXED_SEMANTICS',
        message: 'Statistical baseline is unavailable: BASELINE_MIXED_SEMANTICS',
        details: [],
      },
    } })
  })
  await page.getByRole('button', { name: 'Select statistically', exact: true }).click()
  await expect(page.locator('#baseline-panel [role="alert"]')).toContainText('BASELINE_MIXED_SEMANTICS')
  await expect(page.getByTestId('baseline-incompatible')).toContainText(BASELINE_LABELS.incompatibleHint)
})

test('failed replacement keeps the last confirmed baseline visible', async ({ page }) => {
  const original = await analyze(page, 'baseline-keep.jtl', 50, 1767225800000)
  await page.getByRole('button', { name: 'Set as baseline', exact: true }).click()
  await expect(page.getByTestId('baseline-selection')).toContainText(original.analysis_id)
  await analyze(page, 'baseline-rejected.jtl', 60, 1767225801000)
  await page.route('**/api/baseline', async (route) => {
    if (route.request().method() !== 'POST') return route.continue()
    await route.fulfill({ status: 403, json: { error: { code: 'FORBIDDEN', message: 'Replacement was rejected', details: [] } } })
  })
  await page.getByRole('button', { name: 'Set as baseline', exact: true }).click()
  await expect(page.locator('#baseline-panel [role="alert"]')).toContainText('Replacement was rejected')
  await expect(page.getByTestId('baseline-selection')).toContainText(original.analysis_id)
})

test('ignores a comparison response after selecting another run', async ({ page }) => {
  await analyze(page, 'baseline-stale.jtl', 70, 1767225900000)
  await page.getByRole('button', { name: 'Set as baseline', exact: true }).click()
  await expect(page.getByTestId('baseline-selection')).toContainText('manual')
  let started = false
  let finished = false
  let release!: () => void
  const held = new Promise<void>((resolve) => { release = resolve })
  await page.route('**/comparison', async (route) => {
    const response = await route.fetch()
    started = true
    await held
    await route.fulfill({ response })
    finished = true
  })
  await page.getByRole('button', { name: 'Compare selected analysis', exact: true }).click()
  await expect.poll(() => started).toBe(true)
  await page.getByRole('button', { name: 'baseline-stale.jtl' }).click()
  release()
  await expect.poll(() => finished).toBe(true)
  await expect(page.getByTestId('baseline-comparison')).toHaveCount(0)
})

test('the new shell compare tab shows the same numbers in Russian', async ({ page }) => {
  const baselineFile = 'baseline-newshell-a.jtl'
  const currentFile = 'baseline-newshell-b.jtl'
  const baseline = await analyze(page, baselineFile, 100, 1767225950000)
  await page.getByRole('button', { name: 'Set as baseline', exact: true }).click()
  await expect(page.getByTestId('baseline-selection')).toContainText(baseline.analysis_id)
  const current = await analyze(page, currentFile, 200, 1767225951000)
  await page.getByRole('button', { name: 'Compare selected analysis', exact: true }).click()
  await expect(page.getByTestId('baseline-comparison')).toContainText('UNCONFIRMED')
  const rowCells = async (metric: string) => page.getByTestId(`comparison-${metric}`).locator('td').allInnerTexts()
  const oldP95 = (await rowCells('response_time_p95_ms')).slice(1)
  const oldThroughput = (await rowCells('throughput_rps')).slice(1)
  expect(oldP95).toEqual(['100', '200', '100', '100%'])

  await page.goto('/?shell=new')
  await page.getByRole('button', { name: currentFile }).click()
  await page.locator(`button[title="${current.analysis_id}"]`).click()
  await expect(page.locator('#verdict')).toBeVisible()
  await page.locator('#shell-tab-compare').click()
  await page.getByRole('button', { name: COMPARE_LABELS.compare, exact: true }).click()
  await expect(page.getByTestId('baseline-comparison')).toContainText(COMPARE_LABELS.statusLine('UNCONFIRMED'))
  await expect(page.getByTestId('baseline-warnings')).toHaveCount(0)
  expect((await rowCells('response_time_p95_ms')).slice(1)).toEqual(oldP95)
  expect((await rowCells('throughput_rps')).slice(1)).toEqual(oldThroughput)

  const response = await page.request.get(`/api/runs/${current.run_id}/analyses/${current.analysis_id}/comparison`)
  expect(response.ok()).toBeTruthy()
  const comparison = await response.json() as { metrics: Array<{ metric: string; delta: string | null; delta_percent: string | null }> }
  const p95 = comparison.metrics.find((metric) => metric.metric === 'response_time_p95_ms')
  expect([p95?.delta, `${p95?.delta_percent}%`]).toEqual(oldP95.slice(2))

  await page.locator('#shell-tab-setup').click()
  await page.getByRole('button', { name: baselineFile }).click()
  await page.locator(`button[title="${baseline.analysis_id}"]`).click()
  await expect(page.locator('#verdict')).toBeVisible()
  await page.locator('#shell-tab-compare').click()
  await page.getByRole('button', { name: COMPARE_LABELS.compare, exact: true }).click()
  await expect(page.getByTestId('baseline-warnings').locator('li')).toHaveText([
    BASELINE_LABELS.warnings.BASELINE_IS_CURRENT_ANALYSIS,
  ])
})
