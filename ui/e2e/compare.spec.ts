import AxeBuilder from '@axe-core/playwright'
import { expect, test, type Page } from '@playwright/test'
import { COMPARE_LABELS, EN_COMPARE_LABELS } from '../src/shell/labels.compare'
import { BASELINE_LABELS } from '../src/shell/labels'
import type { BaselineCondition, BaselineSelection } from '../src/types'

const current = { run_id: 'cmp-run', analysis_id: 'a'.repeat(64) }
const reference = { run_id: 'base-run', analysis_id: 'b'.repeat(64) }
const candidate = { run_id: 'c2-run', analysis_id: 'c'.repeat(64) }
const run = { ...current, source_type: 'jmeter', sha256: 'd'.repeat(64), size_bytes: 100, original_filename: 'cmp.jtl' }
const updatedAt = '2026-10-04T10:00:00Z'
const result = {
  schema_version: 'analysis-result.v1', run_id: current.run_id, analysis_mode: 'standard', run_validity: 'VALID', policy_verdict: 'NO_POLICY',
  analysis_coverage: { status: 'COMPLETE', reasons: [] }, findings: [], evidence: [],
}

type CompareOptions = {
  warnings?: string[]
  comparability?: 'UNCONFIRMED' | 'USER_CONFIRMED'
  windowReasons?: string[]
  windowStatus?: 'CANDIDATE'
  baselineMode?: 'manual' | 'statistical'
  noBaseline?: boolean
  postBaselineFails?: boolean
  buckets?: boolean
  shell?: 'new' | 'old'
}

const metric = (
  name: string, unit: string, baseline: string | null, value: string | null,
  delta: string | null, percent: string | null, reason: string | null, percentReason: string | null,
) => ({ metric: name, unit, baseline, current: value, delta, delta_percent: percent, reason, percent_reason: percentReason })

async function openCompare(page: Page, opts: CompareOptions = {}) {
  const paths: string[] = []
  const posts: Array<{ path: string; body: unknown }> = []
  const baselineReference = opts.warnings?.includes('BASELINE_IS_CURRENT_ANALYSIS') ? current
    : opts.warnings?.includes('BASELINE_IS_CURRENT_RUN') ? { ...reference, run_id: current.run_id } : reference
  let baseline: BaselineSelection | null = opts.noBaseline ? null : {
    schema_version: 'local-baseline.v1', series: 'S', mode: opts.baselineMode ?? 'statistical', reference: baselineReference,
    algorithm: opts.baselineMode === 'manual' ? null : 'median-rank-v1',
    candidates: opts.baselineMode === 'manual' ? [baselineReference] : [baselineReference, candidate, current],
    scores: opts.baselineMode === 'manual' ? [] : [{ reference: baselineReference, score: 1.5 }],
  }
  let conditions: BaselineCondition | null = null

  await page.route('**/api/**', async (route) => {
    const url = new URL(route.request().url())
    const path = url.pathname
    const method = route.request().method()
    paths.push(`${method} ${path}`)
    if (method === 'POST') posts.push({ path, body: route.request().postDataJSON() as unknown })
    let body: unknown
    let status = 200
    if (path === '/api/bootstrap') body = { csrf_token: 'ui-test', max_upload_bytes: 1000000 }
    else if (method === 'GET' && path === '/api/jobs') body = { jobs: [] }
    else if (path === '/api/jenkins' || path === '/api/grafana' || path === '/api/sources') body = { profiles: [] }
    else if (path.endsWith('/advice')) body = { advice: null, job: null }
    else if (path === '/api/runs') body = { runs: [run], next_after: null }
    else if (path === '/api/baseline' && method === 'GET') body = { baseline }
    else if (path === '/api/baseline' && method === 'POST') {
      if (opts.postBaselineFails) {
        status = 422
        body = { error: { code: 'BASELINE_CANDIDATE_NOT_PASS', message: 'Server says no.' } }
      } else {
        baseline = {
          schema_version: 'local-baseline.v1', series: 'S', mode: 'manual', reference: current,
          algorithm: null, candidates: [current], scores: [],
        }
        body = { baseline }
      }
    } else if (path === '/api/baseline' && method === 'DELETE') {
      baseline = null
      body = { baseline: null }
    } else if (path.endsWith('/baseline-conditions') && method === 'GET') body = { conditions }
    else if (path.endsWith('/baseline-conditions') && method === 'POST') {
      conditions = {
        schema_version: 'local-baseline-conditions.v1', baseline: baselineReference, current, windows: null,
        decision: 'CONFIRMED', provenance: 'EXPLICIT_LOCAL_ACTION', updated_at: updatedAt,
      }
      body = { conditions }
    } else if (path.endsWith('/comparison')) {
      body = {
        baseline, current, comparability: opts.comparability ?? 'UNCONFIRMED', warnings: opts.warnings ?? [], conditions,
        metrics: [
          metric('response_time_p95_ms', 'ms', '100', '200', '100', '100', null, null),
          metric('error_rate_ratio', 'ratio', '0', '0.1', '0.1', null, null, 'ZERO_BASELINE'),
        ],
        ...(opts.windowReasons || opts.windowStatus ? {
          window_comparison: {
            status: opts.windowStatus ?? 'INSUFFICIENT_DATA',
            baseline_window: url.searchParams.get('baseline_window') ?? 'w1',
            current_window: url.searchParams.get('current_window') ?? 'w2',
            baseline_sample_count: opts.windowStatus ? 10 : 0, current_sample_count: opts.windowStatus ? 12 : null,
            baseline_duration_ms: opts.windowStatus ? 1000 : null, current_duration_ms: 1000,
            min_change_percent: '5', min_error_rate_delta: '0.001', reasons: opts.windowReasons ?? [],
            metrics: [
              { ...metric('response_time_p95_ms', 'ms', '100', '200', '100', '100', null, null), status: 'CANDIDATE' },
              { ...metric('response_time_p50_ms', 'ms', null, '3', null, null, 'EMPTY_WINDOW', 'EMPTY_WINDOW'), status: 'INSUFFICIENT_DATA' },
            ],
          },
        } : {}),
      }
    } else if (path.endsWith('/analyses')) {
      body = {
        analyses: [{ analysis_id: current.analysis_id, policy_sha256: 'e'.repeat(64), policy_verdict: 'NO_POLICY', run_validity: 'VALID' }],
        next_after: null,
      }
    } else if (path.endsWith('/result')) body = result
    else if (path.endsWith('/buckets')) {
      if (opts.buckets) {
        body = {
          buckets: [
            { bucket_start_ms: 1000, sample_count: 10, error_count: 0, p95_latency_ms: 100, max_latency_ms: 110, hdr_v2_base64: 'AAAA' },
            { bucket_start_ms: 2000, sample_count: 12, error_count: 1, p95_latency_ms: 120, max_latency_ms: 130, hdr_v2_base64: 'AAAA' },
          ],
          next_from_ms: null,
        }
      } else {
        status = 500
        body = { error: { code: 'X', message: 'Buckets exploded' } }
      }
    } else throw new Error(`Unexpected UI request ${method} ${path}`)
    await route.fulfill({ status, json: body })
  })

  await page.goto(opts.shell === 'old' ? '/?shell=old' : '/?shell=new')
  await page.getByRole('button', { name: run.original_filename }).click()
  await page.locator(`button[title="${current.analysis_id}"]`).click()
  await expect(page.locator('#verdict')).toBeVisible()
  if (opts.shell !== 'old') await page.locator('#shell-tab-compare').click()
  return { paths, posts }
}

async function compareWindows(page: Page, baselineId = 'w1', currentId = 'w2') {
  await page.getByLabel(COMPARE_LABELS.baselineWindow, { exact: true }).fill(baselineId)
  await page.getByLabel(COMPARE_LABELS.currentWindow, { exact: true }).fill(currentId)
  await page.getByRole('button', { name: COMPARE_LABELS.compare, exact: true }).click()
  await expect(page.getByTestId('window-comparison')).toBeVisible()
}

test('compare tab is Russian, keeps the server warning order and says the verdict is untouched', async ({ page }) => {
  const requests = await openCompare(page, {
    warnings: ['BASELINE_IS_CURRENT_RUN', 'CURRENT_IN_CANDIDATE_SET'], comparability: 'UNCONFIRMED',
  })
  await page.getByRole('button', { name: COMPARE_LABELS.compare, exact: true }).click()
  const panel = page.locator('#baseline-panel')
  await expect(panel).toHaveAttribute('lang', 'ru')
  await expect(panel.getByRole('heading', { name: COMPARE_LABELS.title, exact: true })).toBeVisible()
  await expect(page.getByTestId('baseline-warnings').locator('li')).toHaveText([
    BASELINE_LABELS.warnings.BASELINE_IS_CURRENT_RUN,
    BASELINE_LABELS.warnings.CURRENT_IN_CANDIDATE_SET,
  ])
  await expect(page.getByTestId('baseline-warnings')).toContainText(BASELINE_LABELS.warningsTitle)
  await expect(page.getByTestId('baseline-comparison')).toContainText(COMPARE_LABELS.statusLine('UNCONFIRMED'))
  await expect(page.getByTestId('baseline-comparison')).toContainText(COMPARE_LABELS.deltasNote)
  await expect(panel.locator('header p')).toHaveText(COMPARE_LABELS.intro)
  await page.locator('#shell-tab-overview').click()
  await expect(page.locator('#verdict')).toContainText('NO_POLICY')
  expect(requests.paths).not.toContain('POST /api/jobs')
})

test('USER_CONFIRMED shows the confirmed status line', async ({ page }) => {
  await openCompare(page, { comparability: 'USER_CONFIRMED' })
  await page.getByRole('button', { name: COMPARE_LABELS.compare, exact: true }).click()
  const comparison = page.getByTestId('baseline-comparison')
  await expect(comparison).toContainText(COMPARE_LABELS.statusLine('USER_CONFIRMED'))
  await expect(comparison).not.toContainText(COMPARE_LABELS.statusLine('UNCONFIRMED'))
})

test('a comparison with the same analysis shows the self-comparison warning in words', async ({ page }) => {
  await openCompare(page, { warnings: ['BASELINE_IS_CURRENT_ANALYSIS'], baselineMode: 'manual' })
  await page.getByRole('button', { name: COMPARE_LABELS.compare, exact: true }).click()
  await expect(page.getByTestId('baseline-warnings').locator('li')).toHaveText([
    BASELINE_LABELS.warnings.BASELINE_IS_CURRENT_ANALYSIS,
  ])
})

test('an unknown warning code is shown as the code', async ({ page }) => {
  await openCompare(page, { warnings: ['FUTURE_CODE'] })
  await page.getByRole('button', { name: COMPARE_LABELS.compare, exact: true }).click()
  await expect(page.getByTestId('baseline-warnings').locator('li')).toHaveText(['FUTURE_CODE'])
})

test('an empty window keeps its hint and Russian notes', async ({ page }) => {
  await openCompare(page, { windowReasons: ['BASELINE_WINDOW_EMPTY'] })
  await compareWindows(page)
  const empty = page.getByTestId('baseline-empty-window')
  await expect(empty).toContainText(BASELINE_LABELS.emptyWindowHint)
  await expect(empty).toContainText(BASELINE_LABELS.emptyBaselineWindow)
})

test('window status and reasons are shown in words, unknown codes as the code', async ({ page }) => {
  await openCompare(page, { windowReasons: ['CONDITIONS_UNCONFIRMED', 'FUTURE_REASON'], windowStatus: 'CANDIDATE' })
  await compareWindows(page)
  const window = page.getByTestId('window-comparison')
  await expect(window).toContainText(COMPARE_LABELS.windowStatus('CANDIDATE'))
  await expect(window).toContainText(COMPARE_LABELS.reasons(['CONDITIONS_UNCONFIRMED', 'FUTURE_REASON']))
  await expect(window).toContainText(COMPARE_LABELS.windowStatus('INSUFFICIENT_DATA'))
  await expect(window).toContainText(COMPARE_LABELS.uncertaintyNote)
  await expect(page.getByTestId('comparison-response_time_p95_ms')).toContainText(COMPARE_LABELS.metric('response_time_p95_ms'))
  await expect(page.getByTestId('comparison-error_rate_ratio').locator('td').nth(4)).toHaveText(COMPARE_LABELS.naReason('ZERO_BASELINE'))
})

test('the condition form saves the explicit decision for the pair in Russian', async ({ page }) => {
  const requests = await openCompare(page, { baselineMode: 'manual' })
  await expect(page.getByText(COMPARE_LABELS.conditionsLegend, { exact: true })).toBeVisible()
  await page.getByLabel(COMPARE_LABELS.conditionConfirmed, { exact: true }).check()
  await page.getByRole('button', { name: COMPARE_LABELS.saveCondition, exact: true }).click()
  await expect(page.getByTestId('baseline-condition-status')).toContainText(COMPARE_LABELS.conditionSaved('CONFIRMED', updatedAt))
  expect(requests.posts.filter((post) => post.path.endsWith('/baseline-conditions'))).toEqual([{
    path: `/api/runs/${current.run_id}/analyses/${current.analysis_id}/baseline-conditions`,
    body: { decision: 'CONFIRMED' },
  }])
})

test('no baseline shows the Russian hint', async ({ page }) => {
  await openCompare(page, { noBaseline: true })
  await expect(page.locator('#baseline-panel')).toContainText(COMPARE_LABELS.noBaseline)
  await expect(page.getByRole('button', { name: COMPARE_LABELS.compare, exact: true })).toBeDisabled()
})

test('a server error stays as the server text marked as English', async ({ page }) => {
  await openCompare(page, { postBaselineFails: true })
  await page.getByRole('button', { name: COMPARE_LABELS.setBaseline, exact: true }).click()
  await expect(page.getByRole('alert').filter({ hasText: 'Server says no.' })).toHaveAttribute('lang', 'en')
})

test('the old interface keeps the English panel text and no lang attributes', async ({ page }) => {
  await openCompare(page, { shell: 'old' })
  await expect(page.getByRole('heading', { name: EN_COMPARE_LABELS.title, exact: true })).toBeVisible()
  expect(await page.locator('#baseline-panel').getAttribute('lang')).toBeNull()
  await expect(page.getByRole('button', { name: EN_COMPARE_LABELS.compare, exact: true })).toBeVisible()
})

test('the comparison chart block is Russian around the English chart internals', async ({ page }) => {
  await openCompare(page, { comparability: 'UNCONFIRMED', buckets: true })
  await page.getByRole('button', { name: COMPARE_LABELS.compare, exact: true }).click()
  await page.getByText(COMPARE_LABELS.chartsSummary, { exact: true }).click()
  const load = page.getByRole('button', { name: COMPARE_LABELS.chartsLoad, exact: true })
  await expect(load).toBeVisible()
  await expect(page.getByText(EN_COMPARE_LABELS.chartsLoad, { exact: true })).toHaveCount(0)
  await load.click()
  await expect(page.locator('#baseline-panel .load-charts')).toBeVisible()
  await expect(page.locator('#baseline-panel .load-charts')).toHaveAttribute('lang', 'en')
})

test('changing a window id drops the shown comparison', async ({ page }) => {
  await openCompare(page)
  await page.getByRole('button', { name: COMPARE_LABELS.compare, exact: true }).click()
  await expect(page.getByTestId('baseline-comparison')).toBeVisible()
  await page.getByLabel(COMPARE_LABELS.baselineWindow, { exact: true }).fill('w1')
  await expect(page.getByTestId('baseline-comparison')).toHaveCount(0)
})

for (const theme of ['light', 'dark'] as const) {
  test(`the compare tab has no serious axe violations, ${theme}`, async ({ page }) => {
    await page.emulateMedia({ colorScheme: theme })
    await openCompare(page, { warnings: ['BASELINE_IS_CURRENT_RUN'], windowReasons: ['BASELINE_WINDOW_EMPTY'] })
    await compareWindows(page)
    const audit = await new AxeBuilder({ page }).include('#baseline-panel').analyze()
    expect(audit.violations.filter((item) => item.impact === 'critical' || item.impact === 'serious').map((item) => item.id)).toEqual([])
  })
}

for (const width of [1280, 375, 320]) {
  test(`the compare tab does not overflow at ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 })
    await openCompare(page, { warnings: ['BASELINE_IS_CURRENT_RUN'], windowReasons: ['BASELINE_WINDOW_EMPTY'] })
    await compareWindows(page, `window-${'x'.repeat(60)}`, `window-${'y'.repeat(60)}`)
    const size = await page.evaluate(() => ({
      scrollWidth: document.documentElement.scrollWidth,
      innerWidth: window.innerWidth,
      wide: [...document.querySelectorAll('body *')]
        .filter((element) => element.scrollWidth > element.clientWidth + 1 && element.clientWidth > 0 && element.tagName !== 'INPUT' && !element.closest('.table-wrap'))
        .slice(0, 6).map((element) => `${element.tagName}#${element.id}.${element.className} ${Math.round(element.getBoundingClientRect().right)}`),
    }))
    expect(size.scrollWidth, `scrollWidth ${size.scrollWidth}, innerWidth ${size.innerWidth}, wide ${JSON.stringify(size.wide)}`).toBeLessThanOrEqual(size.innerWidth)
  })
}
