import { expect, test, type Page } from '@playwright/test'
import AxeBuilder from '@axe-core/playwright'
import { BASELINE_LABELS, SETUP_LABELS, SETUP_MESSAGES, SHELL_LABELS } from '../src/shell/labels'
import { COMPARE_LABELS } from '../src/shell/labels.compare'
import { DOWNLOAD_LABELS } from '../src/shell/labels.export'

// A baseline must be a PASS analysis, so every run here is analyzed with a policy; few samples need the floor lowered to 1.
const policyBody = (id: string, threshold: number) => JSON.stringify({
  schema_version: 'policy.v1',
  policy_id: id,
  defaults: { sample_floor: 1, min_samples: 1 },
  rules: [{ id: 'p95', metric: 'response_time_p95_ms', operator: 'lte', threshold, scope: { kind: 'overall' } }],
})
const POLICIES = {
  permissive: { id: 'permissive', verdict: 'PASS', body: policyBody('permissive', 100000) },
  failing: { id: 'failing', verdict: 'FAIL', body: policyBody('failing', 1) },
}

async function attachPolicy(page: Page, policy: keyof typeof POLICIES) {
  const { id, body } = POLICIES[policy]
  await page.getByTestId('policy-file').setInputFiles({ name: 'policy.json', mimeType: 'application/json', buffer: Buffer.from(body) })
  await expect(page.locator('#run-setup')).toContainText(SETUP_MESSAGES.policyValid(id))
}

async function analyze(page: Page, name: string, elapsed: number, timestamp: number, policy: keyof typeof POLICIES = 'permissive') {
  await page.locator('#shell-tab-setup').click()
  await page.getByTestId('input-file').setInputFiles({
    name,
    mimeType: 'text/csv',
    buffer: Buffer.from(`timeStamp,elapsed,label,success\n${timestamp},${elapsed},baseline-test,true\n`),
  })
  await attachPolicy(page, policy)
  await page.getByRole('button', { name: SETUP_LABELS.startButton, exact: true }).click()
  await expect(page.locator('#verdict')).toContainText(POLICIES[policy].verdict)
  await page.locator('#shell-tab-history').click()
  const href = await page.getByRole('link', { name: DOWNLOAD_LABELS.format('json'), exact: true }).getAttribute('href')
  const match = href?.match(/^\/api\/runs\/([^/]+)\/analyses\/([a-f0-9]{64})\/report/)
  expect(match).toBeTruthy()
  await page.locator('#shell-tab-compare').click()
  return { run_id: match![1]!, analysis_id: match![2]! }
}

async function openAnalysis(page: Page, filename: string, analysisId: string, expectedVerdict = 'PASS') {
  await page.getByTestId('run-list').getByRole('button').filter({ hasText: filename }).click()
  await page.locator(`button[title="${analysisId}"]`).click()
  await expect(page.locator('#verdict')).toContainText(expectedVerdict)
  await page.locator('#shell-tab-compare').click()
}

test.beforeEach(async ({ page }) => {
  await page.goto('/')
  const bootstrap = await (await page.request.get('/api/bootstrap')).json() as { csrf_token: string }
  const headers = { Origin: new URL(page.url()).origin, 'X-LTV-CSRF': bootstrap.csrf_token }
  // Baselines live in slots by series and arm: remove every slot, then the legacy file, so no test sees another test's baseline.
  const listed = await (await page.request.get('/api/baseline')).json() as { baselines: Array<{ series: string; arm: string | null }> }
  for (const slot of listed.baselines) {
    const query = new URLSearchParams({ series: slot.series, ...(slot.arm === null ? {} : { arm: slot.arm }) })
    await page.request.delete(`/api/baseline?${query}`, { headers })
  }
  await page.request.delete('/api/baseline', { headers })
  await page.reload()
  await page.locator('#shell-tab-compare').click()
})

test('pins manual baseline across reload and compares changed achieved load without another job', async ({ page }, testInfo) => {
  const baseline = await analyze(page, 'baseline-manual.jtl', 100, 1767225600000)
  await page.getByLabel(COMPARE_LABELS.seriesLabel, { exact: true }).fill('Checkout / test environment')
  await page.getByRole('button', { name: COMPARE_LABELS.setBaseline, exact: true }).click()
  await expect(page.getByTestId('baseline-selection')).toContainText(baseline.analysis_id)
  await page.reload()
  await page.locator('#shell-tab-compare').click()
  await expect(page.getByTestId('baseline-selection')).toContainText(COMPARE_LABELS.mode('manual'))
  await expect(page.getByTestId('baseline-selection')).toContainText(baseline.analysis_id)
  const current = await analyze(page, 'baseline-current.jtl', 200, 1767225601000)
  let jobs = 0
  page.on('request', (request) => {
    if (request.method() === 'POST' && new URL(request.url()).pathname === '/api/jobs') jobs += 1
  })
  await page.getByLabel(COMPARE_LABELS.conditionConfirmed, { exact: true }).check()
  await page.getByRole('button', { name: COMPARE_LABELS.saveCondition, exact: true }).click()
  await expect(page.getByTestId('baseline-condition-status')).toContainText('подтверждено')
  await page.getByRole('button', { name: COMPARE_LABELS.compare, exact: true }).click()
  const p95 = page.getByTestId('comparison-response_time_p95_ms')
  await expect(p95).toContainText('100')
  await expect(p95).toContainText('200')
  await expect(p95.locator('td').nth(3)).toHaveText('100')
  await expect(p95.locator('td').nth(4)).toHaveText('100 %')
  const throughput = page.getByTestId('comparison-throughput_rps')
  await expect(throughput.locator('td').nth(3)).toHaveText('-5')
  await expect(throughput.locator('td').nth(4)).toHaveText('-50 %')
  await expect(page.getByTestId('comparison-error_rate_ratio')).toContainText('ZERO_BASELINE')
  await expect(page.getByTestId('baseline-comparison')).toContainText(COMPARE_LABELS.statusLine('USER_CONFIRMED'))
  await expect(page.getByTestId('baseline-warnings')).toHaveCount(0)
  await expect(page.getByTestId('compare-cursor-chart')).toBeVisible()
  await expect(page.getByTestId('compare-track-p95')).toBeVisible()
  await expect(page.getByTestId('compare-chart-legend')).toHaveText(COMPARE_LABELS.chartsLegend)
  await page.locator('#shell-tab-overview').click()
  await expect(page.locator('#verdict')).toContainText('PASS')
  await page.locator('#shell-tab-compare').click()
  expect(jobs).toBe(0)

  await page.reload()
  await openAnalysis(page, 'baseline-current.jtl', current.analysis_id)
  await expect(page.getByLabel(COMPARE_LABELS.conditionConfirmed, { exact: true })).toBeChecked()
  await expect(page.getByTestId('baseline-condition-status')).toContainText('подтверждено')

  await openAnalysis(page, 'baseline-manual.jtl', baseline.analysis_id)
  await page.getByLabel(COMPARE_LABELS.conditionNotConfirmed, { exact: true }).check()
  await page.getByRole('button', { name: COMPARE_LABELS.saveCondition, exact: true }).click()
  await page.getByRole('button', { name: COMPARE_LABELS.compare, exact: true }).click()
  await expect(page.getByTestId('baseline-condition-status')).toContainText('не подтверждено')
  await expect(page.getByTestId('baseline-comparison')).toContainText(COMPARE_LABELS.statusLine('UNCONFIRMED'))

  await openAnalysis(page, 'baseline-current.jtl', current.analysis_id)
  await expect(page.getByLabel(COMPARE_LABELS.conditionConfirmed, { exact: true })).toBeChecked()
  await page.getByLabel(COMPARE_LABELS.conditionUnknown, { exact: true }).check()
  await page.getByRole('button', { name: COMPARE_LABELS.saveCondition, exact: true }).click()
  await page.reload()
  await openAnalysis(page, 'baseline-current.jtl', current.analysis_id)
  await expect(page.getByLabel(COMPARE_LABELS.conditionUnknown, { exact: true })).toBeChecked()
  await expect(page.getByTestId('baseline-condition-status')).toContainText('неизвестно')
  await page.getByRole('button', { name: COMPARE_LABELS.compare, exact: true }).click()
  await expect(page.getByTestId('baseline-comparison')).toContainText(COMPARE_LABELS.statusLine('UNCONFIRMED'))

  const audit = await new AxeBuilder({ page }).include('#baseline-panel').analyze()
  expect(audit.violations).toEqual([])
  await page.locator('#baseline-panel').screenshot({ path: testInfo.outputPath('baseline-desktop.png') })
  await page.getByRole('button', { name: SHELL_LABELS.themeToDark, exact: true }).click()
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
  await page.getByRole('button', { name: COMPARE_LABELS.clearBaseline, exact: true }).click()
  await expect(page.getByTestId('baseline-selection')).toHaveCount(0)
  await expect(page.getByTestId('baseline-comparison')).toHaveCount(0)
})

test('warns when a run is compared with itself', async ({ page }) => {
  await analyze(page, 'baseline-self.jtl', 100, 1767225650000)
  await page.getByRole('button', { name: COMPARE_LABELS.setBaseline, exact: true }).click()
  await page.getByRole('button', { name: COMPARE_LABELS.compare, exact: true }).click()
  await expect(page.getByTestId('baseline-comparison')).toContainText(COMPARE_LABELS.statusLine('UNCONFIRMED'))
  await expect(page.getByTestId('baseline-warnings').locator('li')).toHaveText([
    BASELINE_LABELS.warnings.BASELINE_IS_CURRENT_ANALYSIS,
  ])
})

test('selects the middle real run statistically and confirms a new compared pair', async ({ page }) => {
  await page.locator('#baseline-panel summary').click()
  await analyze(page, 'stat-fast.jtl', 100, 1767225700000)
  await page.getByRole('button', { name: COMPARE_LABELS.addCandidate, exact: true }).click()
  const middle = await analyze(page, 'stat-middle.jtl', 110, 1767225701000)
  await page.getByRole('button', { name: COMPARE_LABELS.addCandidate, exact: true }).click()
  await page.getByLabel(COMPARE_LABELS.sameConditions, { exact: true }).check()
  await analyze(page, 'stat-slow.jtl', 1000, 1767225702000)
  await page.getByRole('button', { name: COMPARE_LABELS.addCandidate, exact: true }).click()
  await expect(page.getByLabel(COMPARE_LABELS.sameConditions, { exact: true })).not.toBeChecked()
  await expect(page.getByRole('button', { name: COMPARE_LABELS.selectStatistically, exact: true })).toBeDisabled()
  await page.getByLabel(COMPARE_LABELS.sameConditions, { exact: true }).check()
  await page.getByRole('button', { name: COMPARE_LABELS.selectStatistically, exact: true }).click()
  await expect(page.getByTestId('baseline-selection')).toContainText(middle.analysis_id)
  await expect(page.getByTestId('baseline-selection')).toContainText(COMPARE_LABELS.mode('statistical'))
  await expect(page.getByTestId('baseline-selection')).toContainText('median-rank-v1')
  await page.getByRole('button', { name: COMPARE_LABELS.compare, exact: true }).click()
  await expect(page.getByTestId('baseline-comparison')).toContainText(COMPARE_LABELS.statusLine('UNCONFIRMED'))
  await expect(page.getByTestId('baseline-warnings').locator('li')).toHaveText([
    BASELINE_LABELS.warnings.CURRENT_IN_CANDIDATE_SET,
  ])
  await expect(page.getByTestId('comparison-response_time_p95_ms').locator('td').nth(3)).toHaveText('890')
  await openAnalysis(page, 'stat-middle.jtl', middle.analysis_id)
  await page.getByRole('button', { name: COMPARE_LABELS.compare, exact: true }).click()
  await expect(page.getByTestId('baseline-warnings').locator('li')).toHaveText([
    BASELINE_LABELS.warnings.BASELINE_IS_CURRENT_ANALYSIS,
    BASELINE_LABELS.warnings.CURRENT_IN_CANDIDATE_SET,
  ])
  await analyze(page, 'stat-new.jtl', 2000, 1767225703000)
  await expect(page.getByTestId('baseline-selection')).toContainText(middle.analysis_id)
  await expect(page.getByTestId('baseline-comparison')).toHaveCount(0)
  await page.getByRole('button', { name: COMPARE_LABELS.compare, exact: true }).click()
  await expect(page.getByTestId('baseline-comparison')).toContainText(COMPARE_LABELS.statusLine('UNCONFIRMED'))
  await expect(page.getByTestId('baseline-warnings')).toHaveCount(0)
  await page.getByLabel(COMPARE_LABELS.conditionConfirmed, { exact: true }).check()
  await page.getByRole('button', { name: COMPARE_LABELS.saveCondition, exact: true }).click()
  await expect(page.getByTestId('baseline-condition-status')).toContainText('подтверждено')
  await page.getByRole('button', { name: COMPARE_LABELS.compare, exact: true }).click()
  await expect(page.getByTestId('baseline-comparison')).toContainText(COMPARE_LABELS.statusLine('USER_CONFIRMED'))
  await expect(page.getByTestId('comparison-response_time_p95_ms').locator('td').nth(3)).toHaveText(/1\s?890/)
  await expect(page.getByTestId('baseline-candidates').locator('li')).toHaveCount(3)
  await page.reload()
  await page.locator('#shell-tab-compare').click()
  await expect(page.getByTestId('baseline-selection')).toContainText(middle.analysis_id)
})

test('changing a window id shows the decision of the new pair', async ({ page }) => {
  await analyze(page, 'baseline-window-a.jtl', 100, 1767225750000)
  await page.getByRole('button', { name: COMPARE_LABELS.setBaseline, exact: true }).click()
  await analyze(page, 'baseline-window-b.jtl', 200, 1767225751000)
  await page.getByLabel(COMPARE_LABELS.baselineWindow, { exact: true }).fill('before')
  await page.getByLabel(COMPARE_LABELS.currentWindow, { exact: true }).fill('after')
  await page.getByLabel(COMPARE_LABELS.conditionConfirmed, { exact: true }).check()
  await page.getByRole('button', { name: COMPARE_LABELS.saveCondition, exact: true }).click()
  await expect(page.getByTestId('baseline-condition-status')).toContainText('подтверждено')
  await page.getByLabel(COMPARE_LABELS.currentWindow, { exact: true }).fill('other')
  await expect(page.getByLabel(COMPARE_LABELS.conditionUnknown, { exact: true })).toBeChecked()
  await expect(page.getByTestId('baseline-condition-status')).toContainText(COMPARE_LABELS.conditionNone)
  await page.getByLabel(COMPARE_LABELS.currentWindow, { exact: true }).fill('after')
  await expect(page.getByLabel(COMPARE_LABELS.conditionConfirmed, { exact: true })).toBeChecked()
  await expect(page.getByTestId('baseline-condition-status')).toContainText('подтверждено')
})

test('does not allow replacing the baseline while a condition decision is being saved', async ({ page }) => {
  await analyze(page, 'baseline-busy-a.jtl', 100, 1767225760000)
  await page.getByRole('button', { name: COMPARE_LABELS.setBaseline, exact: true }).click()
  await analyze(page, 'baseline-busy-b.jtl', 200, 1767225761000)
  let started = false
  let release!: () => void
  const held = new Promise<void>((resolve) => { release = resolve })
  await page.route(/\/baseline-conditions(\?|$)/, async (route) => {
    if (route.request().method() !== 'POST') return route.continue()
    started = true
    await held
    await route.continue()
  })
  await page.getByLabel(COMPARE_LABELS.conditionConfirmed, { exact: true }).check()
  await page.getByRole('button', { name: COMPARE_LABELS.saveCondition, exact: true }).click()
  await expect.poll(() => started).toBe(true)
  await expect(page.getByRole('button', { name: COMPARE_LABELS.setBaseline, exact: true })).toBeDisabled()
  await expect(page.getByRole('button', { name: COMPARE_LABELS.clearBaseline, exact: true })).toBeDisabled()
  release()
  await expect(page.getByTestId('baseline-condition-status')).toContainText('подтверждено')
  await expect(page.getByRole('button', { name: COMPARE_LABELS.setBaseline, exact: true })).toBeEnabled()
})

test('shows the empty-window hint for the empty side and the incompatibility hint for an incompatible baseline', async ({ page }) => {
  await analyze(page, 'baseline-hints-a.jtl', 100, 1767225770000)
  await page.getByRole('button', { name: COMPARE_LABELS.setBaseline, exact: true }).click()
  const current = await analyze(page, 'baseline-hints-b.jtl', 200, 1767225771000)
  const baseline = (await (await page.request.get('/api/baseline')).json()).baselines[0].baseline
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
  await page.getByLabel(COMPARE_LABELS.baselineWindow, { exact: true }).fill('steady')
  await page.getByLabel(COMPARE_LABELS.currentWindow, { exact: true }).fill('steady')
  const compare = page.getByRole('button', { name: COMPARE_LABELS.compare, exact: true })
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
  await expect(page.getByTestId('baseline-incompatible')).toContainText('разный набор входных данных')
  scenario = { reasons: [], incompatible: false, windowStatus: 'DESCRIPTIVE', empty: false }
  await compare.click()
  await expect(page.getByTestId('baseline-incompatible')).toHaveCount(0)
})

test('explains the incompatibility hint when a mixed-semantics candidate set is rejected', async ({ page }) => {
  await page.locator('#baseline-panel summary').click()
  await analyze(page, 'mixed-fast.jtl', 100, 1767225790000)
  await page.getByRole('button', { name: COMPARE_LABELS.addCandidate, exact: true }).click()
  await analyze(page, 'mixed-middle.jtl', 110, 1767225791000)
  await page.getByRole('button', { name: COMPARE_LABELS.addCandidate, exact: true }).click()
  await page.getByLabel(COMPARE_LABELS.sameConditions, { exact: true }).check()
  await analyze(page, 'mixed-slow.jtl', 1000, 1767225792000)
  await page.getByRole('button', { name: COMPARE_LABELS.addCandidate, exact: true }).click()
  await page.getByLabel(COMPARE_LABELS.sameConditions, { exact: true }).check()
  await page.route('**/api/baseline', async (route) => {
    if (route.request().method() !== 'POST') return route.continue()
    await route.fulfill({ status: 422, json: {
      error: {
        code: 'BASELINE_MIXED_SEMANTICS',
        message: 'Baseline candidate is unavailable: BASELINE_MIXED_SEMANTICS',
        details: [],
      },
    } })
  })
  await page.getByRole('button', { name: COMPARE_LABELS.selectStatistically, exact: true }).click()
  await expect(page.locator('#baseline-panel [role="alert"]')).toContainText('BASELINE_MIXED_SEMANTICS')
  await expect(page.getByTestId('baseline-incompatible')).toContainText(BASELINE_LABELS.incompatibleHint)
})

test('refuses an analysis that is not PASS as a manual baseline and keeps the pinned one', async ({ page }) => {
  const kept = await analyze(page, 'baseline-pass.jtl', 80, 1767225780000)
  await page.getByRole('button', { name: COMPARE_LABELS.setBaseline, exact: true }).click()
  await expect(page.getByTestId('baseline-selection')).toContainText(kept.analysis_id)
  await analyze(page, 'baseline-fail.jtl', 90, 1767225781000, 'failing')
  await expect(page.getByRole('button', { name: COMPARE_LABELS.setBaseline, exact: true })).toBeDisabled()
  await expect(page.getByTestId('baseline-ineligible')).toHaveText(COMPARE_LABELS.ineligible('BASELINE_CANDIDATE_NOT_PASS'))
  await expect(page.getByTestId('baseline-selection')).toContainText(kept.analysis_id)
})

test('failed replacement keeps the last confirmed baseline visible', async ({ page }) => {
  const original = await analyze(page, 'baseline-keep.jtl', 50, 1767225800000)
  await page.getByRole('button', { name: COMPARE_LABELS.setBaseline, exact: true }).click()
  await expect(page.getByTestId('baseline-selection')).toContainText(original.analysis_id)
  await analyze(page, 'baseline-rejected.jtl', 60, 1767225801000)
  await page.route('**/api/baseline', async (route) => {
    if (route.request().method() !== 'POST') return route.continue()
    await route.fulfill({ status: 403, json: { error: { code: 'FORBIDDEN', message: 'Replacement was rejected', details: [] } } })
  })
  await page.getByRole('button', { name: COMPARE_LABELS.setBaseline, exact: true }).click()
  await expect(page.locator('#baseline-panel [role="alert"]')).toContainText('Replacement was rejected')
  await expect(page.getByTestId('baseline-selection')).toContainText(original.analysis_id)
})

test('ignores a comparison response after selecting another run', async ({ page }) => {
  await analyze(page, 'baseline-stale.jtl', 70, 1767225900000)
  await page.getByRole('button', { name: COMPARE_LABELS.setBaseline, exact: true }).click()
  await expect(page.getByTestId('baseline-selection')).toContainText(COMPARE_LABELS.mode('manual'))
  let started = false
  let finished = false
  let release!: () => void
  const held = new Promise<void>((resolve) => { release = resolve })
  await page.route(/\/comparison(\?|$)/, async (route) => {
    const response = await route.fetch()
    started = true
    await held
    await route.fulfill({ response })
    finished = true
  })
  await page.getByRole('button', { name: COMPARE_LABELS.compare, exact: true }).click()
  await expect.poll(() => started).toBe(true)
  await page.getByRole('button', { name: 'baseline-stale.jtl' }).click()
  release()
  await expect.poll(() => finished).toBe(true)
  await page.locator('#shell-tab-compare').click()
  await expect(page.getByTestId('baseline-comparison')).toHaveCount(0)
})

test('the compare tab shows the same numbers in Russian as the comparison API', async ({ page }) => {
  const baselineFile = 'baseline-newshell-a.jtl'
  const currentFile = 'baseline-newshell-b.jtl'
  const baseline = await analyze(page, baselineFile, 100, 1767225950000)
  await page.getByRole('button', { name: COMPARE_LABELS.setBaseline, exact: true }).click()
  await expect(page.getByTestId('baseline-selection')).toContainText(baseline.analysis_id)
  const current = await analyze(page, currentFile, 200, 1767225951000)
  await page.getByRole('button', { name: COMPARE_LABELS.compare, exact: true }).click()
  await expect(page.getByTestId('baseline-comparison')).toContainText(COMPARE_LABELS.statusLine('UNCONFIRMED'))
  await expect(page.getByTestId('baseline-warnings')).toHaveCount(0)
  const rowCells = async (metric: string) => page.getByTestId(`comparison-${metric}`).locator('td').allInnerTexts()
  const nowP95 = await rowCells('response_time_p95_ms')
  const nowThroughput = await rowCells('throughput_rps')

  const slotSeries = (await (await page.request.get('/api/baseline')).json()).baselines[0].series as string
  const response = await page.request.get(`/api/runs/${current.run_id}/analyses/${current.analysis_id}/comparison?series=${encodeURIComponent(slotSeries)}`)
  expect(response.ok()).toBeTruthy()
  const comparison = await response.json() as { metrics: Array<{ metric: string; baseline: string | null; current: string | null; delta: string | null; delta_percent: string | null }> }
  const asNumbers = (cells: string[]) => cells.map((cell) => Number(cell.replace(/[\s%]/g, '').replace(',', '.')))
  for (const [now, metricName] of [[nowP95, 'response_time_p95_ms'], [nowThroughput, 'throughput_rps']] as const) {
    const apiMetric = comparison.metrics.find((item) => item.metric === metricName)!
    const apiValues = [apiMetric.baseline, apiMetric.current, apiMetric.delta, apiMetric.delta_percent].map((v) => Number(v))
    asNumbers(now.slice(1)).forEach((value, index) => expect(value).toBeCloseTo(apiValues[index]!, 1))
  }
  const p95 = comparison.metrics.find((metric) => metric.metric === 'response_time_p95_ms')
  expect([p95?.delta, `${p95?.delta_percent}%`]).toEqual(['100', '100%'])

  await openAnalysis(page, baselineFile, baseline.analysis_id)
  await page.getByRole('button', { name: COMPARE_LABELS.compare, exact: true }).click()
  await expect(page.getByTestId('baseline-warnings').locator('li')).toHaveText([
    BASELINE_LABELS.warnings.BASELINE_IS_CURRENT_ANALYSIS,
  ])
})

test('keeps one baseline per series and clears only the series that was cleared', async ({ page }) => {
  const first = await analyze(page, 'baseline-slots-a.jtl', 100, 1767226000000)
  await page.getByLabel(COMPARE_LABELS.seriesLabel, { exact: true }).fill('Series A')
  await page.getByRole('button', { name: COMPARE_LABELS.setBaseline, exact: true }).click()
  await expect(page.getByTestId('baseline-selection')).toContainText(first.analysis_id)
  const second = await analyze(page, 'baseline-slots-b.jtl', 120, 1767226001000)
  await page.getByLabel(COMPARE_LABELS.seriesLabel, { exact: true }).fill('Series B')
  await page.getByRole('button', { name: COMPARE_LABELS.setBaseline, exact: true }).click()
  await expect(page.getByTestId('baseline-selection')).toContainText(second.analysis_id)
  await expect(page.getByTestId('baseline-slots').getByRole('radio')).toHaveCount(2)
  const current = await analyze(page, 'baseline-slots-c.jtl', 200, 1767226002000)

  await page.getByTestId('baseline-slots').getByRole('radio', { name: /^Series B\b/ }).check()
  await page.getByLabel(COMPARE_LABELS.conditionConfirmed, { exact: true }).check()
  await page.getByRole('button', { name: COMPARE_LABELS.saveCondition, exact: true }).click()
  await expect(page.getByTestId('baseline-condition-status')).toContainText('подтверждено')
  await page.getByRole('button', { name: COMPARE_LABELS.compare, exact: true }).click()
  await expect(page.getByTestId('baseline-comparison')).toContainText(COMPARE_LABELS.statusLine('USER_CONFIRMED'))
  await expect(page.getByTestId('comparison-response_time_p95_ms').locator('td').nth(3)).toHaveText('80')

  await page.getByTestId('baseline-slots').getByRole('radio', { name: /^Series A\b/ }).check()
  await expect(page.getByTestId('baseline-selection')).toContainText(first.analysis_id)
  await expect(page.getByTestId('baseline-condition-status')).toContainText(COMPARE_LABELS.conditionNone)
  await page.getByRole('button', { name: COMPARE_LABELS.compare, exact: true }).click()
  await expect(page.getByTestId('comparison-response_time_p95_ms').locator('td').nth(3)).toHaveText('100')
  await page.getByRole('button', { name: COMPARE_LABELS.clearBaseline, exact: true }).click()
  await expect(page.getByTestId('baseline-selection')).toHaveCount(0)
  await expect(page.getByTestId('baseline-slots').getByRole('radio')).toHaveCount(1)

  await page.reload()
  await openAnalysis(page, 'baseline-slots-c.jtl', current.analysis_id)
  await expect(page.getByTestId('baseline-slots').getByRole('radio')).toHaveCount(1)
  await expect(page.getByTestId('baseline-selection')).toContainText(second.analysis_id)
  await expect(page.getByTestId('baseline-condition-status')).toContainText('подтверждено')
})

async function mutationHeaders(page: Page) {
  const bootstrap = await (await page.request.get('/api/bootstrap')).json() as { csrf_token: string }
  return { Origin: new URL(page.url()).origin, 'X-LTV-CSRF': bootstrap.csrf_token }
}

async function openInCompare(page: Page, filename: string, analysisId: string) {
  await page.goto('/')
  await page.getByRole('button', { name: filename }).click()
  await page.locator(`button[title="${analysisId}"]`).click()
  await expect(page.locator('#verdict')).toBeVisible()
  await page.locator('#shell-tab-compare').click()
}

test('a failing analysis cannot be pinned from the UI and the server still refuses it', async ({ page }) => {
  const failing = await analyze(page, 'baseline-r9-fail.jtl', 90, 1767226100000, 'failing')
  const refused = await page.request.post('/api/baseline', {
    headers: await mutationHeaders(page),
    data: { mode: 'manual', series: 'R9 series', reference: failing },
  })
  expect(refused.status()).toBe(422)
  expect((await refused.json() as { error: { code: string } }).error.code).toBe('BASELINE_CANDIDATE_NOT_PASS')
  await openInCompare(page, 'baseline-r9-fail.jtl', failing.analysis_id)
  await expect(page.getByRole('button', { name: COMPARE_LABELS.setBaseline, exact: true })).toBeDisabled()
  await expect(page.getByTestId('baseline-ineligible')).toHaveText(COMPARE_LABELS.ineligible('BASELINE_CANDIDATE_NOT_PASS'))
})

test('different policies of the baseline and the current analysis show the policy warning in words', async ({ page }) => {
  await analyze(page, 'baseline-r9-policy-a.jtl', 100, 1767226200000)
  await page.getByRole('button', { name: COMPARE_LABELS.setBaseline, exact: true }).click()
  await expect(page.getByTestId('baseline-selection')).toBeVisible()
  const other = await analyze(page, 'baseline-r9-policy-b.jtl', 120, 1767226201000, 'failing')
  await openInCompare(page, 'baseline-r9-policy-b.jtl', other.analysis_id)
  await page.getByRole('button', { name: COMPARE_LABELS.compare, exact: true }).click()
  await expect(page.getByTestId('baseline-warnings').locator('li')).toHaveText([BASELINE_LABELS.warnings.POLICY_DIFFERS])
})

test('releases with different profiles show the profile warning and the profile line', async ({ page }) => {
  const series = `R9 profile ${Date.now()}`
  const profile = (pacing: string) => ({ scenario_mix: null, environment_dataset: null, load_model: null, targets_stages: null, pacing, generator_limits: null })
  const base = await analyze(page, 'baseline-r9-profile-a.jtl', 100, 1767226300000)
  const current = await analyze(page, 'baseline-r9-profile-b.jtl', 120, 1767226301000)
  const headers = await mutationHeaders(page)
  const created: string[] = []
  try {
    for (const [reference, label, pacing] of [[base, 'a', '10 s'], [current, 'b', '20 s']] as const) {
      const response = await page.request.post('/api/releases', {
        headers,
        data: { series, label, run_id: reference.run_id, analyses: [{ analysis_id: reference.analysis_id }], profile: profile(pacing), notes: null },
      })
      expect(response.status()).toBe(201)
      created.push((await response.json() as { release_id: string }).release_id)
    }
    const pinned = await page.request.post('/api/baseline', { headers, data: { mode: 'manual', series, reference: base } })
    expect(pinned.status()).toBe(200)
    await openInCompare(page, 'baseline-r9-profile-b.jtl', current.analysis_id)
    await page.getByRole('button', { name: COMPARE_LABELS.compare, exact: true }).click()
    await expect(page.getByTestId('baseline-warnings').locator('li')).toHaveText([BASELINE_LABELS.warnings.PROFILE_MISMATCH])
    await expect(page.getByTestId('baseline-profile')).toContainText('паузы (pacing)')
  } finally {
    for (const id of created) await page.request.delete(`/api/releases/${id}`, { headers })
  }
})
