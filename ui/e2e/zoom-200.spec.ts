import AxeBuilder from '@axe-core/playwright'
import { expect, test, type Locator, type Page } from '@playwright/test'

const reference = { run_id: 'zoom-run', analysis_id: 'a'.repeat(64) }
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

async function fixtureApi(page: Page, screen: 'setup' | 'overview' | 'deep') {
  await page.route('**/api/**', async (route) => {
    const url = new URL(route.request().url())
    const path = url.pathname
    const method = route.request().method()
    let body: unknown
    if (path === '/api/bootstrap') body = { csrf_token: 'ui-test', max_upload_bytes: 1000000 }
    else if (method === 'GET' && path === '/api/jobs') body = { jobs: [] }
    else if (method === 'GET' && (path === '/api/jenkins' || path === '/api/grafana' || path === '/api/sources')) body = { profiles: [] }
    else if (path === '/api/runs') body = { runs: [run], next_after: null }
    else if (path === '/api/baseline') body = { baseline: null }
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
        : { buckets: [], next_from_ms: null }
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

async function openScreen(page: Page, screen: 'setup' | 'overview' | 'deep') {
  await fixtureApi(page, screen)
  await page.goto('/?shell=new')
  if (screen === 'overview' || screen === 'deep') {
    await page.getByRole('button', { name: 'zoom.jtl' }).click()
    await page.locator(`button[title="${reference.analysis_id}"]`).click()
    await expect(page.getByTestId('overview-panel')).toBeVisible()
    await expect(page.locator('#verdict')).toBeVisible()
    if (screen === 'deep') {
      await page.locator('#shell-tab-deep').click()
      await expect(page.getByTestId('deep-panel')).toBeVisible()
      await expect(page.getByTestId('deep-panel').getByRole('status')).toHaveCount(0)
      await expect(page.getByTestId('deep-stat-tiles')).toBeVisible()
    }
  } else {
    await expect(page.locator('#input-file')).toBeVisible()
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
  for (const screen of ['setup', 'overview', 'deep'] as const) {
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
      } else {
        const stageBtn = page.getByTestId('deep-stage-all')
        await expectKeyboardReachable(page, stageBtn)
        await expectNoClippedText(page, ['#deep-title', '#deep-period-title', '#deep-stats-title', '#deep-chart-title', '#deep-series-title', '[data-testid="deep-stat-tile"]'])
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
