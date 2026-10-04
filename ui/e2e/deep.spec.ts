import AxeBuilder from '@axe-core/playwright'
import { expect, test, type Page } from '@playwright/test'
import { DEEP_LABELS, SHELL_LABELS, SHELL_TABS } from '../src/shell/labels'
import { formatNumber } from '../src/shell/overview'

const runId = 'deep-run'
const analysisId = 'a'.repeat(64)
const origin = 1_767_225_600_000
const ids = ['queue', 'cpu', 'memory', 'disk', 'network', 'latency', 'spare']
const overall = { id: 'overall', type: 'metric_summary', scope: { kind: 'overall' }, sample_count: 180, error_count: 0, error_rate_ratio: { numerator: 0, denominator: 180 }, throughput_rps: { numerator: 180000, denominator: 180000 }, latency_ms: { p50: 100, p95: 100, p99: 100, max: 100 } }
type Options = { snapshot?: boolean; missingCatalog?: boolean; pointCount?: number; stepMs?: number; startMs?: number; ids?: string[]; catalogPage?: number; valuesPage?: number; invalid?: boolean; mode?: string; loadCount?: number; delayValues?: (call: number) => number; delayDeepBucket?: number; failValuesAt?: number; delayCatalogSecondPage?: number; passRule?: boolean }

function fixtureApi(page: Page, options: Options = {}) {
  const names = options.ids ?? ids
  const start = options.startMs ?? origin
  const step = options.stepMs ?? 1000
  const count = options.pointCount ?? 180
  const calls: URL[] = []
  let valuesCalls = 0
  const evidence = [overall, ...(options.snapshot === false ? [] : [{ id: 'binding', type: 'resource_binding', run_from_epoch_ms: origin }]),
    ...[0, 1].map((n) => ({ id: `rule-${n}`, type: 'resource_policy_check', window_id: `w${n}`, rule_id: 'cpu-high', series_id: 'cpu', unit: '%', operator: 'gt', threshold: '5', effect: 'sla', status: 'FAIL', reason: null })),
    ...(options.passRule ? [{ id: 'queue-rule', type: 'resource_policy_check', window_id: 'w0', rule_id: 'queue-ok', series_id: 'queue', unit: '%', operator: 'gt', threshold: '5000', effect: 'sla', status: 'PASS', reason: null }] : [])]
  const result = { schema_version: 'analysis-result.v1', run_id: runId, analysis_mode: options.mode ?? 'standard', run_validity: options.invalid ? 'INVALID' : 'VALID', policy_verdict: 'FAIL', analysis_coverage: { status: 'COMPLETE', reasons: [] }, findings: [], evidence }
  const entry = (id: string) => ({ id, metric: `metric-${id}`, unit: '%', entity: 'node', role: 'system', aggregation: 'interval_mean', reducer: 'mean', labels: {}, observed_cells: id === 'spare' ? 0 : count })
  const sourceValue = (id: string, index: number) => index === 2 || index === 6 ? null : names.indexOf(id) * 1000 + index + 1
  page.route('**/api/**', async (route) => {
    const url = new URL(route.request().url())
    const path = url.pathname
    let body: unknown
    if (path === '/api/bootstrap') body = { csrf_token: 'test', max_upload_bytes: 1000000 }
    else if (path === '/api/jobs') body = { jobs: [] }
    else if (path === '/api/jenkins' || path === '/api/grafana' || path === '/api/sources') body = { profiles: [] }
    else if (path === '/api/runs') body = { runs: [{ run_id: runId, source_type: 'jmeter', sha256: 'b'.repeat(64), size_bytes: 100, original_filename: 'deep.jtl' }], next_after: null }
    else if (path === '/api/baseline') body = { baseline: null }
    else if (path.endsWith('/analyses')) body = { analyses: [{ analysis_id: analysisId, policy_sha256: 'c'.repeat(64), policy_verdict: 'FAIL', run_validity: result.run_validity }], next_after: null }
    else if (path.endsWith('/result')) body = result
    else if (path.endsWith('/advice')) body = { advice: null, job: null }
    else if (path.endsWith('/resource-series')) {
      calls.push(url)
      if (options.missingCatalog) { await route.fulfill({ status: 404, json: { code: 'NOT_FOUND', message: 'missing' } }); return }
      const after = url.searchParams.get('after')
      if (after && options.delayCatalogSecondPage) await new Promise((resolve) => setTimeout(resolve, options.delayCatalogSecondPage))
      const offset = after ? names.indexOf(after) + 1 : 0
      const size = options.catalogPage ?? 256
      const slice = names.slice(offset, offset + size)
      body = { schema_version: 'resource-series.v1', kind: 'catalog', resource_snapshot_sha256: 'd'.repeat(64), numeric_encoding: 'ieee754-double', grid: { start_epoch_ms: start, step_ms: step, point_count: count }, windows: [], series: slice.map(entry), next_after: offset + size < names.length ? slice.at(-1) : null }
    } else if (path.endsWith('/resource-series/values')) {
      calls.push(url)
      valuesCalls += 1
      if (valuesCalls === options.failValuesAt) { await route.fulfill({ status: 500, json: { code: 'FAILED', message: 'values unavailable' } }); return }
      const requested = url.searchParams.getAll('series_id')
      const outStep = Number(url.searchParams.get('step_ms') ?? step)
      const ratio = outStep / step
      const from = Number(url.searchParams.get('from_ms') ?? start)
      const to = Number(url.searchParams.get('to_ms') ?? start + count * step)
      const first = start + Math.max(0, Math.floor((from - start) / outStep)) * outStep
      const end = Math.min(start + count * step, to)
      const total = Math.max(0, Math.ceil((end - first) / outStep))
      const cells = Math.min(total, options.valuesPage ?? 2000)
      const data = requested.map((id) => {
        const values = Array.from({ length: cells }, (_, i) => sourceValue(id, Math.floor((first - start) / step) + i * ratio))
        return { id, aggregation: 'interval_mean', reducer: 'mean', values, ...(ratio > 1 ? { observed: values.map((v, i) => v === null ? 0 : i === 0 ? ratio - 1 : ratio) } : {}) }
      })
      body = { schema_version: 'resource-series.v1', kind: 'values', resource_snapshot_sha256: 'd'.repeat(64), numeric_encoding: 'ieee754-double', grid: { start_epoch_ms: start, source_step_ms: step, step_ms: outStep, first_cell_start_ms: first, cell_count: cells, source_cells_per_cell: ratio, last_cell_source_cells: Math.min(ratio, count - Math.floor((first - start) / step) - (cells - 1) * ratio) }, series: data, next_from_ms: total > cells ? first + cells * outStep : null }
      if (options.delayValues) await new Promise((resolve) => setTimeout(resolve, options.delayValues!(valuesCalls)))
    } else if (path.endsWith('/buckets')) {
      calls.push(url)
      if (options.delayDeepBucket && url.searchParams.has('to_ms')) await new Promise((resolve) => setTimeout(resolve, options.delayDeepBucket))
      const rollup = Number(url.searchParams.get('rollup'))
      const from = Number(url.searchParams.get('from_ms') ?? 0)
      const to = Number(url.searchParams.get('to_ms') ?? (options.loadCount ?? 180) * 1000)
      const limit = Math.min(500, options.loadCount && options.loadCount > 6000 ? 500 : 500)
      const starts = Array.from({ length: limit }, (_, i) => from + i * rollup * 1000).filter((n) => n < to && n < (options.loadCount ?? 180) * 1000)
      body = { buckets: starts.map((bucket_start_ms) => ({ bucket_start_ms, sample_count: rollup, error_count: 0, p95_latency_ms: 100, max_latency_ms: 100, hdr_v2_base64: '' })), next_from_ms: starts.length === limit && starts.at(-1)! + rollup * 1000 < to ? starts.at(-1)! + rollup * 1000 : null }
    } else throw new Error(`Unexpected UI request ${path}`)
    await route.fulfill({ json: body })
  })
  return { calls, names, start, step, count, sourceValue }
}

async function openDeep(page: Page) {
  await page.goto('/?shell=new')
  await page.getByRole('button', { name: 'deep.jtl' }).click()
  await page.locator(`button[title="${analysisId}"]`).click()
  await page.locator('#shell-tab-deep').click()
  await expect(page.getByTestId('deep-panel')).toBeVisible()
  await expect(page.getByTestId('deep-panel').getByRole('status')).toHaveCount(0)
}

test('tab follows overview and no snapshot uses only load, including catalog 404', async ({ page }) => {
  const mock = fixtureApi(page, { snapshot: false })
  await openDeep(page)
  expect(SHELL_TABS.findIndex((tab) => tab.key === 'deep')).toBe(SHELL_TABS.findIndex((tab) => tab.key === 'overview') + 1)
  await expect(page.getByTestId('deep-no-snapshot')).toHaveText(DEEP_LABELS.noSnapshot)
  await expect(page.getByTestId('deep-track-load-rps')).toBeVisible()
  expect(mock.calls.filter((url) => url.pathname.includes('resource-series'))).toHaveLength(0)
  await page.reload()
  const noCatalog = fixtureApi(page, { missingCatalog: true })
  await openDeep(page)
  await expect(page.getByTestId('deep-no-snapshot')).toBeVisible()
  await expect(page.getByTestId('deep-error')).toHaveCount(0)
  await expect(page.getByTestId('track-line-load-rps')).toHaveCount(1)
  await page.getByTestId('deep-cursor').focus()
  await page.keyboard.press('Home')
  await expect(page.getByTestId('deep-cursor-time')).toHaveAttribute('data-epoch-ms', String(origin))
  await expect(page.getByTestId('track-value-load-rps')).toHaveAttribute('data-value', '1')
  expect(noCatalog.calls.some((url) => url.pathname.endsWith('/resource-series'))).toBe(true)
})

test('failure-first selection, six-series cap, filtering and thresholds', async ({ page }) => {
  fixtureApi(page)
  await openDeep(page)
  await expect(page.getByTestId('deep-track-cpu')).toBeVisible()
  expect(await page.locator('[data-testid^="deep-track-"]').evaluateAll((nodes) => nodes.map((node) => node.getAttribute('data-testid')).slice(0, 3))).toEqual(['deep-track-load-rps', 'deep-track-load-p95', 'deep-track-cpu'])
  await expect(page.getByTestId('deep-series-spare')).toBeDisabled()
  await expect(page.getByTestId('deep-limit-hint')).toBeVisible()
  await expect(page.getByTestId('deep-selected-count')).toHaveText(DEEP_LABELS.seriesCount(6, 6))
  await expect(page.getByTestId('deep-threshold-cpu-high')).toHaveCount(1)
  await expect(page.getByTestId('deep-threshold-label-cpu-high')).toHaveCount(1)
  await page.getByTestId('deep-series-filter').fill('spa')
  await expect(page.getByTestId('deep-series-spare')).toBeVisible()
  await expect(page.getByTestId('deep-series-cpu')).toHaveCount(0)
})

test('only failed rule thresholds use violation text and color', async ({ page }) => {
  fixtureApi(page, { passRule: true })
  await openDeep(page)
  const failedLabel = page.getByTestId('deep-threshold-label-cpu-high')
  const passedLabel = page.getByTestId('deep-threshold-label-queue-ok')
  const failedLine = page.getByTestId('deep-threshold-cpu-high')
  const passedLine = page.getByTestId('deep-threshold-queue-ok')
  await expect(failedLabel).toHaveAttribute('data-violated', 'true')
  await expect(failedLabel).toHaveText(DEEP_LABELS.thresholdLabel('gt', formatNumber(5), '%', true))
  await expect(passedLabel).toHaveAttribute('data-violated', 'false')
  await expect(passedLabel).toHaveText(DEEP_LABELS.thresholdLabel('gt', formatNumber(5000), '%', false))
  await expect(failedLine).toHaveAttribute('data-violated', 'true')
  await expect(passedLine).toHaveAttribute('data-violated', 'false')
  expect(await failedLabel.evaluate((element) => getComputedStyle(element).color)).not.toBe(await passedLabel.evaluate((element) => getComputedStyle(element).color))
  expect(await failedLine.evaluate((element) => getComputedStyle(element).stroke)).not.toBe(await passedLine.evaluate((element) => getComputedStyle(element).stroke))
})

test('shared cursor reads every track and keyboard navigates stops', async ({ page }) => {
  const mock = fixtureApi(page)
  await openDeep(page)
  const slider = page.getByTestId('deep-cursor')
  await slider.focus()
  await page.keyboard.press('Home')
  await expect(page.getByTestId('deep-cursor-time')).toHaveAttribute('data-epoch-ms', String(origin))
  await expect(slider).toHaveAttribute('aria-valuetext', /cpu: 1\s001/)
  for (const id of mock.names.slice(0, 6)) await expect(page.getByTestId(`track-value-${id}`)).toHaveAttribute('data-value', String(mock.sourceValue(id, 0)))
  await page.keyboard.press('ArrowRight')
  await expect(page.getByTestId('deep-cursor-time')).toHaveAttribute('data-epoch-ms', String(origin + 1000))
  await page.keyboard.press('End')
  await expect(page.getByTestId('deep-cursor-time')).toHaveAttribute('data-epoch-ms', String(origin + 179000))
  const plot = page.getByTestId('deep-plot')
  await plot.locator('svg').first().hover({ position: { x: 200, y: 30 } })
  const box = (await plot.boundingBox())!
  await page.mouse.move(box.x + box.width / 2, box.y + 30)
  await expect(page.getByTestId('deep-cursor-time')).toHaveAttribute('data-ms', '90000')
  for (const id of mock.names.slice(0, 6)) await expect(page.getByTestId(`track-value-${id}`)).toHaveAttribute('data-value', String(mock.sourceValue(id, 90)))
})

test('a failed replacement does not display stale resource tracks', async ({ page }) => {
  fixtureApi(page, { failValuesAt: 2 })
  await openDeep(page)
  await expect(page.getByTestId('deep-track-cpu')).toBeVisible()
  await page.getByTestId('deep-period-from').fill('0.5')
  await page.getByTestId('deep-period-to').fill('1')
  await page.getByTestId('deep-period-apply').click()
  await expect(page.getByTestId('deep-error')).toBeVisible()
  await expect(page.getByTestId('deep-track-cpu')).toHaveCount(0)
})

test('changing series during the initial bucket request preserves load tracks', async ({ page }) => {
  const mock = fixtureApi(page, { delayDeepBucket: 400 })
  await page.goto('/?shell=new')
  await page.getByRole('button', { name: 'deep.jtl' }).click()
  await page.locator(`button[title="${analysisId}"]`).click()
  await page.locator('#shell-tab-deep').click()
  await expect(page.getByTestId('deep-series-cpu')).toBeVisible()
  await expect.poll(() => mock.calls.filter((url) => url.pathname.endsWith('/buckets') && url.searchParams.has('to_ms')).length).toBe(1)
  await page.getByTestId('deep-series-cpu').uncheck()
  await expect(page.getByTestId('deep-track-load-rps')).toBeVisible()
  await expect(page.getByTestId('track-value-load-rps')).toBeVisible()
})

test('resource ids that match load track names retain distinct readouts', async ({ page }) => {
  fixtureApi(page, { ids: ['load-rps', 'resource:load-rps'] })
  await openDeep(page)
  await expect(page.getByTestId('deep-track-resource:load-rps')).toBeVisible()
  await expect(page.getByTestId('deep-track-resource:resource:load-rps')).toBeVisible()
  await page.getByTestId('deep-cursor').focus()
  await page.keyboard.press('Home')
  await page.keyboard.press('ArrowRight')
  await expect(page.getByTestId('track-value-load-rps')).toHaveAttribute('data-value', '1')
  await expect(page.getByTestId('track-value-resource:load-rps')).toHaveAttribute('data-value', '2')
  await expect(page.getByTestId('track-value-resource:resource:load-rps')).toHaveAttribute('data-value', '1002')
})

test('pre-run axis and cursor offsets are formatted as signed times', async ({ page }) => {
  fixtureApi(page, { startMs: origin - 61000 })
  await openDeep(page)
  await expect(page.getByTestId('deep-chart')).toContainText('-1:01')
  const plot = page.getByTestId('deep-plot')
  const box = (await plot.boundingBox())!
  await page.mouse.move(box.x + 1, box.y + 30)
  await expect(page.getByTestId('deep-cursor-time')).toContainText(/-1:0[01]/)
})

test('null cells break paths and coarse partial cells show observed count', async ({ page }) => {
  fixtureApi(page, { pointCount: 3001 })
  await openDeep(page)
  await expect(page.getByTestId('track-line-cpu')).toHaveCount(2)
  await page.getByTestId('deep-cursor').focus()
  await page.keyboard.press('Home')
  await expect(page.getByTestId('deep-track-cpu')).toContainText(DEEP_LABELS.partialCell(2, 3))
  await expect(page.getByTestId('deep-cursor')).toHaveAttribute('aria-valuetext', new RegExp(DEEP_LABELS.partialCell(2, 3)))
  await page.getByTestId('deep-period-from').fill('0.02')
  await page.getByTestId('deep-period-to').fill('0.06')
  await page.getByTestId('deep-period-apply').click()
  await page.getByTestId('deep-cursor').focus()
  await page.keyboard.press('Home')
  await page.keyboard.press('ArrowRight')
  await expect(page.getByTestId('track-value-cpu')).toHaveText(DEEP_LABELS.gap)
})

test('deep chart polylines remain unfilled with visible strokes', async ({ page }) => {
  fixtureApi(page)
  await openDeep(page)
  for (const key of ['load-rps', 'load-p95', 'cpu']) {
    const line = page.getByTestId(`deep-track-${key}`).locator('polyline').first()
    expect(await line.evaluate((el) => getComputedStyle(el).fill), key).toBe('none')
    expect(await line.evaluate((el) => getComputedStyle(el).stroke), key).not.toBe('none')
  }
})

test('period snaps query bounds, whole run restores, invalid input does not fetch', async ({ page }) => {
  const mock = fixtureApi(page, { pointCount: 3001 })
  await openDeep(page)
  await page.getByTestId('deep-period-from').fill('5')
  await page.getByTestId('deep-period-to').fill('5.2')
  await page.getByTestId('deep-period-apply').click()
  await expect(page.getByTestId('deep-period-snapped')).toBeVisible()
  await expect.poll(() => mock.calls.filter((url) => url.pathname.endsWith('/values')).at(-1)?.searchParams.get('from_ms')).toBe(String(origin + 300000))
  const values = mock.calls.filter((url) => url.pathname.endsWith('/values')).at(-1)!
  expect(values.searchParams.get('from_ms')).toBe(String(origin + 300000))
  expect(values.searchParams.get('to_ms')).toBe(String(origin + 312000))
  expect(mock.calls.filter((url) => url.pathname.endsWith('/buckets')).at(-1)!.searchParams.get('from_ms')).toBe('300000')
  await page.getByTestId('deep-period-all').click()
  await expect(page.getByTestId('deep-period-from')).toHaveValue('')
  await expect(page.getByTestId('deep-panel').getByRole('status')).toHaveCount(0)
  const before = mock.calls.length
  await page.getByTestId('deep-period-from').fill('7')
  await page.getByTestId('deep-period-to').fill('5')
  await page.getByTestId('deep-period-apply').click()
  await expect(page.getByRole('alert')).toContainText(DEEP_LABELS.periodInvalid)
  expect(mock.calls.length).toBe(before)
})

test('unapplied period edits do not leak into a later series change', async ({ page }) => {
  const mock = fixtureApi(page, { pointCount: 3001 })
  await openDeep(page)
  await page.getByTestId('deep-period-from').fill('5')
  await page.getByTestId('deep-period-to').fill('5.2')
  await page.getByTestId('deep-period-apply').click()
  await expect(page.getByTestId('deep-period-snapped')).toBeVisible()
  await page.getByTestId('deep-period-from').fill('10')
  await page.getByTestId('deep-period-to').fill('11')
  await page.getByTestId('deep-series-cpu').uncheck()
  await expect(page.getByTestId('deep-track-cpu')).toHaveCount(0)
  const values = mock.calls.filter((url) => url.pathname.endsWith('/values')).at(-1)!
  expect(values.searchParams.get('from_ms')).toBe(String(origin + 300000))
  expect(values.searchParams.get('to_ms')).toBe(String(origin + 312000))
})

test('a slow second catalog page keeps the period controls hidden and all series listed', async ({ page }) => {
  const many = Array.from({ length: 300 }, (_, i) => `s${String(i).padStart(3, '0')}`)
  fixtureApi(page, { ids: many, catalogPage: 256, delayCatalogSecondPage: 600 })
  await page.goto('/?shell=new')
  await page.getByRole('button', { name: 'deep.jtl' }).click()
  await page.locator(`button[title="${analysisId}"]`).click()
  await page.locator('#shell-tab-deep').click()
  await expect(page.getByTestId('deep-panel')).toBeVisible()
  await expect(page.getByTestId('deep-period-apply')).toHaveCount(0)
  await expect(page.getByTestId('deep-series-s299')).toBeAttached()
  await expect(page.getByTestId('deep-period-apply')).toBeVisible()
  await expect(page.getByTestId('deep-selected-count')).toHaveText(DEEP_LABELS.seriesCount(6, 6))
})

test('catalog and values pages are merged', async ({ page }) => {
  const mock = fixtureApi(page, { catalogPage: 4, valuesPage: 50 })
  await openDeep(page)
  await expect(page.getByTestId('deep-series-spare')).toBeVisible()
  await expect(page.getByTestId('deep-track-cpu').getByTestId('track-line-cpu').last()).toBeVisible()
  expect(mock.calls.filter((url) => url.pathname.endsWith('/resource-series'))).toHaveLength(2)
  expect(mock.calls.filter((url) => url.pathname.endsWith('/values')).length).toBeGreaterThan(1)
})

test('selection refetches resources without reloading buckets and stale pages cannot win', async ({ page }) => {
  const mock = fixtureApi(page, { delayValues: (call) => call === 2 ? 300 : 0 })
  await openDeep(page)
  const bucketsBefore = mock.calls.filter((url) => url.pathname.endsWith('/buckets')).length
  await page.getByTestId('deep-series-cpu').uncheck()
  await page.getByTestId('deep-series-spare').check()
  await expect(page.getByTestId('deep-track-spare')).toBeVisible()
  await page.waitForTimeout(350)
  await expect(page.getByTestId('deep-track-cpu')).toHaveCount(0)
  expect(mock.calls.filter((url) => url.pathname.endsWith('/buckets'))).toHaveLength(bucketsBefore)
})

test('request URLs encode qualified ids and remain below budget', async ({ page }) => {
  const longIds = Array.from({ length: 7 }, (_, i) => `prom/${'\u043f'.repeat(125)} ${i}%`)
  const mock = fixtureApi(page, { ids: longIds })
  await openDeep(page)
  const values = mock.calls.filter((url) => url.pathname.endsWith('/values'))
  expect(values.length).toBeGreaterThan(0)
  for (const url of values) {
    expect(url.pathname.length + url.search.length).toBeLessThanOrEqual(3500)
    for (const [key] of url.searchParams) if (key !== 'series_id') expect(url.searchParams.getAll(key)).toHaveLength(1)
    expect(url.search).toContain('%2F')
    expect(url.search).toContain('+')
  }
})

test('load and resource epochs align, including a pre-run grid', async ({ page }) => {
  const mock = fixtureApi(page, { startMs: origin - 60000, pointCount: 180 })
  await openDeep(page)
  expect(mock.calls.filter((url) => url.pathname.endsWith('/buckets')).at(-1)!.searchParams.get('from_ms')).toBe('0')
  await page.getByTestId('deep-cursor').focus()
  await page.keyboard.press('End')
  await expect(page.getByTestId('deep-cursor-time')).toHaveAttribute('data-epoch-ms', String(origin + 119000))
})

test('load buckets and resource cells share epoch cursor time', async ({ page }) => {
  fixtureApi(page, { startMs: origin + 60000, pointCount: 180 })
  await openDeep(page)
  await page.getByTestId('deep-cursor').focus()
  await page.keyboard.press('Home')
  await expect(page.getByTestId('deep-cursor-time')).toHaveAttribute('data-epoch-ms', String(origin + 60000))
  await expect(page.getByTestId('track-value-load-rps')).toHaveAttribute('data-value', '1')
  await expect(page.getByTestId('track-value-cpu')).toHaveAttribute('data-value', '1001')
})

test('a grid entirely before the run skips bucket loading', async ({ page }) => {
  const mock = fixtureApi(page, { startMs: origin - 300000, pointCount: 180 })
  await openDeep(page)
  await expect(page.getByText(DEEP_LABELS.loadOutsideRun)).toBeVisible()
  expect(mock.calls.filter((url) => url.pathname.endsWith('/buckets') && url.searchParams.has('from_ms'))).toHaveLength(0)
})

test('capacity and online-shaped snapshots open; invalid runs skip load', async ({ page }) => {
  const qualified = 'prom-main/memory-limit-ratio'
  const mock = fixtureApi(page, { mode: 'capacity_step', stepMs: 5000, ids: [qualified], invalid: true })
  await openDeep(page)
  await expect(page.getByTestId(`deep-track-${qualified}`)).toBeVisible()
  await expect(page.getByText(DEEP_LABELS.loadEmpty)).toBeVisible()
  expect(mock.calls.filter((url) => url.pathname.endsWith('/buckets'))).toHaveLength(0)
})

test('load pagination cap shows truncation notice', async ({ page }) => {
  fixtureApi(page, { pointCount: 720000, loadCount: 720000 })
  await openDeep(page)
  await expect(page.getByText(DEEP_LABELS.loadTruncated(6000))).toBeVisible()
})

for (const theme of ['light', 'dark'] as const) {
  test(`deep tab has no axe violations in ${theme} and reflows`, async ({ page }) => {
    await page.emulateMedia({ colorScheme: theme })
    fixtureApi(page)
    await openDeep(page)
    await page.setViewportSize({ width: 1280, height: 900 })
    expect((await new AxeBuilder({ page }).include('[data-testid="deep-panel"]').analyze()).violations).toEqual([])
    for (const width of [375, 320]) {
      await page.setViewportSize({ width, height: 900 })
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true)
      const tabs = await page.getByRole('tablist').boundingBox()
      expect(tabs!.x).toBeGreaterThanOrEqual(0)
      expect(tabs!.x + tabs!.width).toBeLessThanOrEqual(width)
      expect(await page.getByRole('tablist').evaluate((node) => node.scrollWidth <= node.clientWidth)).toBe(true)
    }
  })
}

test('empty deep tab repeats the overview notice', async ({ page }) => {
  fixtureApi(page)
  await page.goto('/?shell=new')
  await page.locator('#shell-tab-deep').click()
  await expect(page.getByText(SHELL_LABELS.overviewEmpty)).toBeVisible()
})
