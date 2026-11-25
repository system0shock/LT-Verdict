import AxeBuilder from '@axe-core/playwright'
import { expect, test, type Locator, type Page } from '@playwright/test'
import { COMPARE_LABELS } from '../src/shell/labels.compare'

const reference = { run_id: 'zoom-run', analysis_id: 'a'.repeat(64) }
const baselineRef = { run_id: 'zoom-base', analysis_id: 'd'.repeat(64) }
const run = { ...reference, source_type: 'jmeter', sha256: 'b'.repeat(64), size_bytes: 100, original_filename: 'zoom.jtl' }
const origin = 1_767_225_600_000
const baseResult = {
  schema_version: 'analysis-result.v1', run_id: reference.run_id, analysis_mode: 'standard', run_validity: 'VALID', policy_verdict: 'FAIL',
  analysis_coverage: { status: 'COMPLETE', reasons: [] }, findings: [],
  evidence: [
    { id: 'overall', type: 'metric_summary', scope: { kind: 'overall' }, sample_count: 100, error_count: 2,
      error_rate_ratio: { numerator: 2, denominator: 100 }, throughput_rps: { numerator: 1000, denominator: 10 },
      latency_ms: { p50: 100, p95: 200, p99: 300, max: 400 } },
    { id: 'check', type: 'policy_check', rule_id: 'p95-limit', metric: 'response_time_p95_ms', operator: 'lte', threshold: 150,
      status: 'FAIL', metric_evidence_id: 'overall', observed: 200 },
  ],
}
const deepResult = {
  ...baseResult,
  evidence: [
    ...baseResult.evidence,
    { id: 'binding', type: 'resource_binding', run_from_epoch_ms: origin },
    { id: 'stage', type: 'stage_binding', mode: 'declared', declaration_sha256: 'e'.repeat(64), run_from_epoch_ms: origin, run_to_epoch_ms: origin + 60_000, evaluated_window_ids: ['steady'], evaluated_millis: 40_000, excluded_millis: 20_000, verdict_scope: 'steady', stages: [{ id: 'steady', role: 'steady', from_offset_ms: 0, to_offset_ms: 60_000, from_epoch_ms: origin, to_epoch_ms: origin + 60_000 }] },
  ],
}
const releaseRow = (n: number, label: string, verdict: 'PASS' | 'FAIL', ref: typeof reference) => ({
  schema_version: 'local-release.v1', release_id: `2026010100000${n}-${String(n).repeat(8)}`, series: 'Checkout', label, run_id: ref.run_id,
  started_at: `2026-01-0${n}T10:00:00Z`,
  analyses: [{ analysis_id: ref.analysis_id, arm: null, coverage_reasons: [], coverage_status: 'COMPLETE', policy_sha256: 'c'.repeat(64), policy_verdict: verdict, run_validity: 'VALID', analysis_state: 'OK', baseline_eligible: verdict === 'PASS', ineligible_reasons: [] }],
  profile: null, notes: null, created_at: `2026-01-0${n}T11:00:00Z`, updated_at: `2026-01-0${n}T11:00:00Z`,
  baseline_eligible: verdict === 'PASS', ineligible_reasons: [],
})

type ZoomScreen = 'setup' | 'overview' | 'deep' | 'tables' | 'compare' | 'history'

async function fixtureApi(page: Page, screen: ZoomScreen) {
  await page.route('**/api/**', async (route) => {
    const url = new URL(route.request().url())
    const path = url.pathname
    const method = route.request().method()
    let body: unknown
    if (path === '/api/bootstrap') body = { csrf_token: 'ui-test', max_upload_bytes: 1000000 }
    else if (method === 'GET' && path === '/api/jobs') body = { jobs: [] }
    else if (method === 'GET' && (path === '/api/jenkins' || path === '/api/grafana' || path === '/api/sources')) body = { profiles: [] }
    else if (path === '/api/runs') body = { runs: [run], next_after: null }
    else if (path === '/api/baseline') body = {
      baseline: { schema_version: 'local-baseline.v1', series: COMPARE_LABELS.seriesDefault, mode: 'manual', reference: baselineRef, algorithm: null, candidates: [], scores: [] },
      baselines: [{ series: COMPARE_LABELS.seriesDefault, arm: null, source: 'SLOT', baseline: { schema_version: 'local-baseline.v1', series: COMPARE_LABELS.seriesDefault, mode: 'manual', reference: baselineRef, algorithm: null, candidates: [], scores: [] } }],
    }
    else if (path === '/api/releases') body = {
      releases: [releaseRow(2, 'v1.1', 'FAIL', reference), releaseRow(1, 'v1.0', 'PASS', baselineRef)],
      next_after: null, series_summary: [{ series: 'Checkout', count: 2 }], corrupt_count: 0, corrupt_names: [],
    }
    else if (path.endsWith('/analytics')) body = {
      schema_version: 'saved-analytics.v1', history_scan_truncated: false, history_scan_limit: 1000, history_metadata_byte_limit: 16777216,
      history_integrity: 'SAVED_DOCUMENT_HASHES', transactions: null, overlay: null, metric_packs: { schema_version: 'metric-packs.v1', packs: [] },
      dynamics: {
        schema_version: 'run-dynamics.v1', limit: 100, comparable_count: 2, excluded_incompatible_count: 0, baseline: baselineRef,
        rows: [
          { reference, run_date: '2026-01-02T10:00:00Z', jenkins_build: null, commit: null, application_version: 'v1.1', load_profile: null, verdict: 'FAIL', metrics: [{ metric: 'response_time_p95_ms', unit: 'ms', value: '200', delta_previous: null, delta_previous_percent: null, previous_reason: null, delta_baseline: null, delta_baseline_percent: null, baseline_reason: null }] },
          { reference: baselineRef, run_date: '2026-01-01T10:00:00Z', jenkins_build: null, commit: null, application_version: 'v1.0', load_profile: null, verdict: 'PASS', metrics: [{ metric: 'response_time_p95_ms', unit: 'ms', value: '120', delta_previous: null, delta_previous_percent: null, previous_reason: null, delta_baseline: null, delta_baseline_percent: null, baseline_reason: null }] },
        ],
      },
    }
    else if (path.endsWith('/baseline-conditions')) body = { decision: 'UNKNOWN', updated_at: null }
    else if (path.endsWith('/comparison')) body = {
      schema_version: 'baseline-comparison.v1', comparability: 'UNCONFIRMED', warnings: [],
      baseline: { series: COMPARE_LABELS.seriesDefault, mode: 'manual', reference: baselineRef },
      current: reference,
      metrics: [{ metric: 'response_time_p95_ms', unit: 'ms', baseline_value: '120', current_value: '200', absolute_delta: '80', relative_delta_percent: '66.67', reason: null }],
    }
    else if (path.endsWith('/analyses')) body = { analyses: [{ analysis_id: reference.analysis_id, policy_sha256: 'c'.repeat(64), policy_verdict: 'FAIL', run_validity: 'VALID' }], next_after: null }
    else if (path.endsWith('/result')) body = screen === 'deep' ? deepResult : baseResult
    else if (path.endsWith('/buckets')) {
      const includePercentiles = url.searchParams.get('include') === 'percentiles'
      body = screen === 'deep'
        ? {
          buckets: [0, 10_000, 20_000].map((bucket_start_ms) => ({
            bucket_start_ms,
            sample_count: 10,
            error_count: 1,
            ...(includePercentiles ? { p50_latency_ms: 100, p99_latency_ms: 300 } : {}),
            p95_latency_ms: 200,
            max_latency_ms: 400,
            hdr_v2_base64: '',
          })),
          next_from_ms: null,
        }
        : {
          buckets: [
            { bucket_start_ms: 0, sample_count: 10, error_count: 0, p95_latency_ms: 120, max_latency_ms: 150, hdr_v2_base64: 'AAAA' },
            { bucket_start_ms: 1000, sample_count: 12, error_count: 1, p95_latency_ms: 200, max_latency_ms: 240, hdr_v2_base64: 'AAAA' },
          ],
          next_from_ms: null,
        }
    } else if (path.endsWith('/resource-series')) {
      body = {
        schema_version: 'resource-series.v1',
        kind: 'catalog',
        resource_snapshot_sha256: 'd'.repeat(64),
        numeric_encoding: 'ieee754-double',
        grid: { start_epoch_ms: origin, step_ms: 15_000, point_count: 4 },
        windows: [{ id: 'steady', from_epoch_ms: origin, to_epoch_ms: origin + 60_000 }],
        series: [{ id: 'cpu', metric: 'cpu_usage', unit: '%', entity: 'checkout-api', role: 'system', aggregation: 'interval_mean', reducer: 'mean', labels: {}, observed_cells: 4 }],
        next_after: null,
      }
    } else if (path.endsWith('/resource-series/values')) {
      body = {
        schema_version: 'resource-series.v1',
        kind: 'values',
        resource_snapshot_sha256: 'd'.repeat(64),
        numeric_encoding: 'ieee754-double',
        grid: { start_epoch_ms: origin, source_step_ms: 15_000, step_ms: 15_000, first_cell_start_ms: origin, cell_count: 4, source_cells_per_cell: 1, last_cell_source_cells: 1 },
        series: [{ id: 'cpu', aggregation: 'interval_mean', reducer: 'mean', values: [10, 20, 30, 40], observed: [1, 1, 1, 1] }],
        next_from_ms: null,
      }
    } else if (path.endsWith('/advice')) body = { advice: null, job: null }
    else throw new Error(`Unexpected UI request ${method} ${path}`)
    await route.fulfill({ json: body })
  })
}

async function openScreen(page: Page, screen: ZoomScreen) {
  await fixtureApi(page, screen)
  await page.goto('/')
  if (screen === 'setup') {
    await expect(page.locator('#input-file')).toBeVisible()
    return
  }
  await page.getByRole('button', { name: 'zoom.jtl' }).click()
  await expect(page.getByTestId('overview-panel')).toBeVisible()
  await expect(page.locator('#verdict')).toBeVisible()
  if (screen === 'deep') {
    await page.locator('#shell-tab-deep').click()
    await expect(page.getByTestId('deep-panel')).toBeVisible()
    await expect(page.getByTestId('deep-panel').getByRole('status')).toHaveCount(0)
    await expect(page.getByTestId('deep-stat-tiles')).toBeVisible()
  } else if (screen === 'tables') {
    await page.locator('#shell-tab-tables').click()
    await expect(page.getByTestId('tables-subtabs')).toBeVisible()
  } else if (screen === 'compare') {
    await page.locator('#shell-tab-compare').click()
    await page.getByRole('button', { name: COMPARE_LABELS.compare, exact: true }).click()
    await expect(page.getByTestId('compare-charts')).toBeVisible()
  } else if (screen === 'history') {
    await page.locator('#shell-tab-history').click()
    await expect(page.getByTestId('history-dynamics')).toBeVisible()
  }
}

async function expectKeyboardReachable(page: Page, control: Locator) {
  await expect(control).toBeVisible()
  await page.evaluate(() => (document.activeElement as HTMLElement | null)?.blur())
  for (let step = 0; step < 100; step += 1) {
    await page.keyboard.press('Tab')
    if (await control.evaluate((node) => node === document.activeElement)) break
  }
  await expect(control).toBeFocused()
  await expect(control).toBeInViewport()
}

async function expectNoHorizontalScroll(page: Page) {
  const width = await page.evaluate(() => ({ scroll: document.documentElement.scrollWidth, client: document.documentElement.clientWidth }))
  expect(width.scroll).toBeLessThanOrEqual(width.client)
}

async function expectNoClippedText(page: Page, selectors: string[]) {
  for (const selector of selectors) expect(await page.locator(selector).count(), `${selector} must exist`).toBeGreaterThan(0)
  const clipped = await page.locator(selectors.join(', ')).evaluateAll((nodes) => nodes.flatMap((node) => {
    const hides = (element: Element) => ['hidden', 'clip'].includes(getComputedStyle(element).overflowX)
    const right = node.getBoundingClientRect().right
    const found: Array<{ node: string; clippedBy: string }> = []
    if (hides(node) && node.scrollWidth > node.clientWidth + 1) found.push({ node: node.tagName, clippedBy: 'itself' })
    for (let parent = node.parentElement; parent; parent = parent.parentElement) {
      if (hides(parent) && right > parent.getBoundingClientRect().right + 1) found.push({ node: node.tagName, clippedBy: `${parent.tagName}#${parent.id}` })
    }
    return found
  }))
  expect(clipped).toEqual([])
}

for (const size of [{ width: 640, height: 360 }, { width: 320, height: 568 }]) {
  for (const screen of ['setup', 'overview', 'deep', 'tables', 'compare', 'history'] as const) {
    test(`${screen} reflows at ${size.width}x${size.height} CSS px`, async ({ page }) => {
      await page.setViewportSize(size)
      await openScreen(page, screen)

      await expectNoHorizontalScroll(page)
      if (screen === 'setup') {
        const input = page.locator('#input-file')
        await expectKeyboardReachable(page, input)
        await input.setInputFiles({ name: 'zoom.jtl', mimeType: 'text/csv', buffer: Buffer.from('load') })
        const analyze = page.getByTestId('start-analysis')
        await expect(analyze).toBeEnabled()
        await expectKeyboardReachable(page, analyze)
        await expectNoClippedText(page, ['#run-setup h2', '#run-setup h3', '#run-setup .field__hint', '#readiness-status', '[data-testid="start-analysis"]'])
      } else if (screen === 'overview') {
        const chip = page.getByTestId('verdict-chip')
        await expectKeyboardReachable(page, chip)
        await page.keyboard.press('Enter')
        await expect(page.locator('#verdict')).toBeInViewport()
        await expectNoClippedText(page, ['#verdict h2', '#verdict p', '#verdict li', '#overview-attention h2', '#overview-attention li', '#overview-metrics h2', '#overview-metrics [data-testid="metric-tile"]'])
      } else if (screen === 'deep') {
        const stageBtn = page.getByTestId('deep-stage-all')
        await expectKeyboardReachable(page, stageBtn)
        await expectNoClippedText(page, ['#deep-title', '#deep-period-title', '#deep-stats-title', '#deep-chart-title', '#deep-series-title', '[data-testid="deep-stat-tile"]'])
      } else if (screen === 'tables') {
        await expectKeyboardReachable(page, page.locator('#tables-subtab-all'))
        await expectNoClippedText(page, ['#summary-metrics h2', '#policy-results h2', '#transaction-metrics h2'])
      } else if (screen === 'compare') {
        await expectKeyboardReachable(page, page.getByTestId('compare-chart-cursor'))
        await expectNoClippedText(page, ['#baseline-panel h2', '#compare-charts-title'])
      } else {
        await expectKeyboardReachable(page, page.getByTestId('history-dynamics-cursor'))
        await expectNoClippedText(page, ['#history-panel h2', '[data-testid="history-dynamics"] h3'])
      }
    })

    for (const theme of ['light', 'dark'] as const) {
      test(`${screen} has no axe violations at ${size.width}x${size.height} in ${theme}`, async ({ page }) => {
        await page.setViewportSize(size)
        await page.emulateMedia({ colorScheme: theme })
        await openScreen(page, screen)
        await expect(page.locator('html')).toHaveAttribute('data-theme', theme)
        const axe = await new AxeBuilder({ page }).analyze()
        expect(axe.violations.map((item) => item.id)).toEqual([])
      })
    }
  }
}

for (const size of [{ width: 640, height: 360 }, { width: 320, height: 568 }]) {
  test(`the verdict card itself accepts keyboard focus at ${size.width}px`, async ({ page }) => {
    await page.setViewportSize(size)
    await openScreen(page, 'overview')
    await page.getByTestId('verdict-chip').focus()
    await page.keyboard.press('Enter')
    await expect(page.locator('#verdict')).toBeFocused()
  })
}

test('setup has no horizontal scroll at 320x568 CSS px', async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 568 })
  await openScreen(page, 'setup')
  await expectNoHorizontalScroll(page)
})
