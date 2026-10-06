import AxeBuilder from '@axe-core/playwright'
import { expect, test, type Page } from '@playwright/test'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import type { AnalysisSummary } from '../src/types'
import { POD_LABELS } from '../src/shell/podLabels'
import { gridKey, podViewGroups, windowStats, worstPods, type PodRow } from '../src/shell/podView'

const fixture = (name: string) => JSON.parse(readFileSync(fileURLToPath(new URL(`../../fixtures/platform/pod-view-synthetic/${name}`, import.meta.url)), 'utf-8'))
const podViewFile = fixture('pod-view.json') as {
  arm: string; start_epoch_ms: number; step_ms: number; column_count: number; resource_snapshot_sha256: string
  coverage: Record<string, unknown>; pods: Array<{ pod: string; service: string }>; rows: PodRow[]
}
const expected = fixture('expected.json') as {
  rows: Array<{ id: string; max: number | null; mean: number | null; last: number | null }>
  worst_pod_by_max: Record<string, string>
}

// Чистые функции без сервера: статистики окна равны эталону expected.json синтетики P2d.
test('window statistics equal the reference of the synthetic scenario', () => {
  for (const row of podViewFile.rows) {
    const reference = expected.rows.find((item) => item.id === row.id)!
    const stats = windowStats(row)!
    expect(stats.max).toBe(reference.max)
    expect(stats.last).toBe(reference.last)
    expect(Math.abs(stats.mean - reference.mean!)).toBeLessThan(1e-6)
  }
})

test('the worst pod of every metric equals the reference', () => {
  for (const [metric, pod] of Object.entries(expected.worst_pod_by_max)) {
    const rows = podViewFile.rows.filter((row) => row.metric === metric)
    expect(worstPods(rows, 1)[0].pod).toBe(pod)
  }
})

test('null is a gap, not a zero: it is left out of max, mean and last', () => {
  const row = { values: [null, 4, null, 2, null] }

  expect(windowStats(row)).toEqual({ max: 4, mean: 3, last: 2, count: 2 })
  expect(windowStats(row, 0, 2)).toEqual({ max: 4, mean: 4, last: 4, count: 1 })
  expect(windowStats({ values: [null, null] })).toBeNull()
  expect(windowStats({ values: [-3, -1] })).toEqual({ max: -1, mean: -2, last: -1, count: 2 })
})

test('worst pods order by window maximum, put rows without data last and keep the filter a pure view', () => {
  const row = (id: string, pod: string, values: Array<number | null>): PodRow => ({ id, pod, container: null, metric: 'm', unit: 'u', aggregation: 'interval_mean', values })
  const rows = [row('r1', 'a', [1, 2]), row('r2', 'b', [null, null]), row('r3', 'c', [9, 1]), row('r4', 'd', [9, 0])]
  const before = JSON.stringify(rows)

  expect(worstPods(rows, 3).map((item) => item.id)).toEqual(['r3', 'r4', 'r1'])
  expect(worstPods(rows, 10).map((item) => item.id)).toEqual(['r3', 'r4', 'r1', 'r2'])
  expect(JSON.stringify(rows)).toBe(before)
})

test('grid key tells grids apart', () => {
  const meta = (step: number) => ({ grid: { start_epoch_ms: 1, step_ms: step, column_count: 6 } })

  expect(gridKey(meta(10))).toBe(gridKey(meta(10)))
  expect(gridKey(meta(10))).not.toBe(gridKey(meta(20)))
})

test('pod-view lookup groups analyses by arm and snapshot and needs a snapshot hash', () => {
  const summary = (id: string, arm: string | undefined, hash: string | undefined): AnalysisSummary => ({
    analysis_id: id, policy_sha256: 'p', policy_verdict: 'PASS', run_validity: 'VALID',
    ...(arm ? { resource_arm: arm } : {}), ...(hash ? { resource_snapshot_sha256: hash } : {}),
  })
  const list = [summary('a1', 'A', 'h1'), summary('a2', 'A', 'h1'), summary('a3', 'A', 'h2'), summary('b1', 'B', 'h3'), summary('x1', undefined, 'h4')]

  expect(podViewGroups(list, 'a2')).toEqual([
    { arm: 'A', snapshotSha256: 'h1', analysisIds: ['a2', 'a1'] },
    { arm: 'B', snapshotSha256: 'h3', analysisIds: ['b1'] },
  ])
  expect(podViewGroups(list, 'x1')).toEqual([{ arm: null, snapshotSha256: 'h4', analysisIds: ['x1'] }])
  expect(podViewGroups([summary('n1', 'A', undefined)], 'n1')).toEqual([])
})

const runId = 'pods-run'
const hashA = 'a'.repeat(64)
const hashB = 'b'.repeat(64)
const analysisA = '1'.repeat(64)
const analysisA2 = '2'.repeat(64)
const analysisB = '3'.repeat(64)
const overall = { id: 'overall', type: 'metric_summary', scope: { kind: 'overall' }, sample_count: 180, error_count: 0, error_rate_ratio: { numerator: 0, denominator: 180 }, throughput_rps: { numerator: 180000, denominator: 180000 }, latency_ms: { p50: 100, p95: 100, p99: 100, max: 100 } }
const analysisResult = { schema_version: 'analysis-result.v1', run_id: runId, analysis_mode: 'standard', run_validity: 'VALID', policy_verdict: 'PASS', analysis_coverage: { status: 'COMPLETE', reasons: [] }, findings: [], evidence: [overall] }
const summary = (id: string, arm: string | undefined, hash: string | undefined) => ({
  analysis_id: id, policy_sha256: 'c'.repeat(64), policy_verdict: 'PASS', run_validity: 'VALID',
  ...(arm ? { resource_arm: arm } : {}), ...(hash ? { resource_snapshot_sha256: hash } : {}),
})

type PodFile = { arm: string | null; start: number; step: number; columns: number; hash: string; pods: Array<{ pod: string; service: string }>; rows: PodRow[]; coverage?: Record<string, unknown> }
const fromFixture = (over: Partial<PodFile> = {}): PodFile => ({
  arm: 'B', start: podViewFile.start_epoch_ms, step: podViewFile.step_ms, columns: podViewFile.column_count, hash: hashA,
  pods: podViewFile.pods, rows: podViewFile.rows, ...over,
})
type Answer = { status: number; body: unknown }
type Behaviour = Record<string, PodFile | Answer>

function metaOf(file: PodFile) {
  const services = [...new Set(file.pods.map((pod) => pod.service))].sort()
  return {
    schema_version: 'pod-view.v1', arm: file.arm, grid: { start_epoch_ms: file.start, step_ms: file.step, column_count: file.columns },
    coverage: file.coverage ?? { pods_observed_total: file.pods.length, pods_included: file.pods.length, rows_observed_total: file.rows.length, rows_included: file.rows.length, selection: { kind: 'ALL' } },
    services: services.map((service) => {
      const pods = new Set(file.pods.filter((pod) => pod.service === service).map((pod) => pod.pod))
      return { service, pods: pods.size, rows: file.rows.filter((row) => pods.has(row.pod)).length }
    }),
    pod_view_sha256: 'e'.repeat(64), resource_snapshot_sha256: file.hash,
  }
}

async function podApi(page: Page, analyses: unknown[], behaviour: Behaviour) {
  const calls: string[] = []
  await page.route('**/api/**', async (route) => {
    const url = new URL(route.request().url())
    const path = url.pathname
    let body: unknown
    if (path === '/api/bootstrap') body = { csrf_token: 'test', max_upload_bytes: 1000000 }
    else if (path === '/api/jobs') body = { jobs: [] }
    else if (path === '/api/jenkins' || path === '/api/grafana' || path === '/api/sources') body = { profiles: [] }
    else if (path === '/api/runs') body = { runs: [{ run_id: runId, source_type: 'jmeter', sha256: 'b'.repeat(64), size_bytes: 100, original_filename: 'pods.jtl' }], next_after: null }
    else if (path === '/api/baseline') body = { baseline: null }
    else if (path.endsWith('/analyses')) body = { analyses, next_after: null }
    else if (path.endsWith('/result')) body = analysisResult
    else if (path.endsWith('/advice')) body = { advice: null, job: null }
    else if (path.endsWith('/buckets')) body = { buckets: [], next_from_ms: null }
    else if (/\/pod-view(\/values)?$/.test(path)) {
      calls.push(`${path.split('/')[5]}${path.endsWith('/values') ? ' values' : ' meta'}`)
      const id = path.split('/')[5]
      const entry = behaviour[id]
      if (!entry) {
        await route.fulfill({ status: 404, json: { error: { code: 'POD_VIEW_NOT_FOUND', message: 'no pod view' } } })
        return
      }
      if ('status' in entry) {
        await route.fulfill({ status: entry.status, json: entry.body })
        return
      }
      if (!path.endsWith('/values')) body = metaOf(entry)
      else {
        const service = url.searchParams.get('service')!
        const pods = new Set(entry.pods.filter((pod) => pod.service === service).map((pod) => pod.pod))
        const selected = entry.rows.filter((row) => pods.has(row.pod))
        const limit = Number(url.searchParams.get('limit'))
        const after = url.searchParams.get('after')
        const start = after ? selected.findIndex((row) => row.id === after) + 1 : 0
        const rows = selected.slice(start, start + limit)
        body = {
          schema_version: 'pod-view.v1', pod_view_sha256: 'e'.repeat(64), numeric_encoding: 'ieee754-double', service,
          grid: { start_epoch_ms: entry.start, step_ms: entry.step, from_ms: entry.start, to_ms: entry.start + entry.step * entry.columns, column_count: entry.columns },
          rows, next_after: start + rows.length < selected.length ? rows.at(-1)!.id : null,
        }
      }
    } else throw new Error(`Unexpected UI request ${path}`)
    await route.fulfill({ json: body })
  })
  return calls
}

async function openDeep(page: Page, selected = analysisA) {
  await page.goto('/?shell=new')
  await page.getByRole('button', { name: 'pods.jtl' }).click()
  await page.locator(`button[title="${selected}"]`).click()
  await page.locator('#shell-tab-deep').click()
  await expect(page.getByTestId('deep-panel')).toBeVisible()
}

const armed = [summary(analysisA, 'B', hashA)]
const show = (page: Page) => page.getByTestId('pod-show').click()

test('the pods block waits for a click, then shows the map with the permanent note and the file coverage', async ({ page }) => {
  const calls = await podApi(page, armed, { [analysisA]: fromFixture() })
  await openDeep(page)

  await expect(page.getByTestId('pod-note')).toHaveText(POD_LABELS.note)
  expect(calls).toEqual([])
  await show(page)

  await expect(page.getByTestId('pod-map')).toBeVisible()
  await expect(page.getByTestId('pod-note')).toHaveText(POD_LABELS.note)
  await expect(page.getByTestId('pod-coverage')).toContainText(POD_LABELS.coverage(5, 5))
  await expect(page.getByTestId('pod-coverage-reduced')).toHaveCount(0)
  // svc-01 has three pods; the default metric is the memory ratio: one app row per pod and the sidecar rows are listed too.
  const memoryRows = podViewFile.rows.filter((row) => row.metric === 'openshift_container_memory_limit_ratio' && row.pod.startsWith('svc-01'))
  await expect(page.getByTestId('pod-cell')).toHaveCount(memoryRows.length * podViewFile.column_count)
  await expect(page.getByTestId('pod-shown')).toContainText(POD_LABELS.shown(memoryRows.length, memoryRows.length))
  expect(calls.filter((call) => call.endsWith('meta'))).toHaveLength(1)
})

test('the table lists every pod row of the metric with client statistics equal to the reference', async ({ page }) => {
  await podApi(page, armed, { [analysisA]: fromFixture() })
  await openDeep(page)
  await show(page)
  await expect(page.getByTestId('pod-map')).toBeVisible()
  await page.getByTestId('pod-metric').selectOption('openshift_container_memory_limit_ratio')
  await page.getByTestId('pod-view-table').click()

  const memoryRows = podViewFile.rows.filter((row) => row.metric === 'openshift_container_memory_limit_ratio' && row.pod.startsWith('svc-01'))
  await expect(page.getByTestId('pod-table-row')).toHaveCount(memoryRows.length)
  const format = (value: number) => new Intl.NumberFormat('ru-RU', { maximumFractionDigits: 4 }).format(value)
  for (const row of memoryRows) {
    const reference = expected.rows.find((item) => item.id === row.id)!
    const line = page.locator(`[data-row-id="${row.id}"]`)
    await expect(line.locator('[data-col="max"]')).toHaveText(format(reference.max!))
    await expect(line.locator('[data-col="last"]')).toHaveText(format(reference.last!))
  }
  // The worst pod of the scenario (the planted leak) is the first row.
  await expect(page.getByTestId('pod-table-row').first()).toHaveAttribute('data-row-id', memoryRows.find((row) => row.pod === expected.worst_pod_by_max.openshift_container_memory_limit_ratio && row.container === 'app')!.id)
})

test('the map keeps only the 24 worst rows while the table keeps all of them', async ({ page }) => {
  const pods = Array.from({ length: 40 }, (_, index) => ({ pod: `big-${String(index).padStart(2, '0')}`, service: 'big' }))
  const rows: PodRow[] = pods.map((pod, index) => ({ id: `r${index}`, pod: pod.pod, container: null, metric: 'openshift_pod_cpu_usage', unit: 'cores', aggregation: 'interval_mean', values: [index, index + 1, index + 2] }))
  await podApi(page, armed, { [analysisA]: fromFixture({ pods, rows, columns: 3 }) })
  await openDeep(page)
  await show(page)

  await expect(page.getByTestId('pod-cell')).toHaveCount(24 * 3)
  await expect(page.getByTestId('pod-shown')).toContainText(POD_LABELS.shown(24, 40))
  await page.getByTestId('pod-view-table').click()
  await expect(page.getByTestId('pod-table-row')).toHaveCount(40)
  await page.getByTestId('pod-view-map').click()
  await expect(page.getByTestId('pod-cell')).toHaveCount(24 * 3)
})

test('gaps are hatched and the readout and the table say "no data" instead of a number', async ({ page }) => {
  const rows: PodRow[] = [
    { id: 'g1', pod: 'p1', container: null, metric: 'openshift_pod_cpu_usage', unit: 'cores', aggregation: 'interval_mean', values: [1, null, 3] },
    { id: 'g2', pod: 'p2', container: null, metric: 'openshift_pod_cpu_usage', unit: 'cores', aggregation: 'interval_mean', values: [null, null, null] },
  ]
  await podApi(page, armed, { [analysisA]: fromFixture({ pods: [{ pod: 'p1', service: 's' }, { pod: 'p2', service: 's' }], rows, columns: 3 }) })
  await openDeep(page)
  await show(page)

  await expect(page.locator('[data-testid="pod-cell"][data-null="1"]')).toHaveCount(4)
  await page.getByTestId('pod-map').hover({ position: { x: 5, y: 27 } })
  await expect(page.getByTestId('pod-readout')).toContainText(POD_LABELS.missing)
  await page.getByTestId('pod-view-table').click()
  await expect(page.locator('[data-row-id="g2"] [data-col="max"]')).toHaveText(POD_LABELS.missing)
  await expect(page.locator('[data-row-id="g1"] [data-col="mean"]')).toHaveText('2')
})

test('a file with fewer pods than observed warns that the coverage is reduced', async ({ page }) => {
  const coverage = { pods_observed_total: 78, pods_included: 5, rows_observed_total: 100, rows_included: 35, selection: { kind: 'WORST_BY_METRIC', metric: 'memory', limit: 5 } }
  await podApi(page, armed, { [analysisA]: fromFixture({ coverage }) })
  await openDeep(page)
  await show(page)

  await expect(page.getByTestId('pod-coverage')).toContainText(POD_LABELS.coverage(5, 78))
  await expect(page.getByTestId('pod-coverage-reduced')).toHaveText(POD_LABELS.coverageReduced)
})

test('with no pod-view in any analysis of the arm the block says there is no pod data and shows no empty map', async ({ page }) => {
  await podApi(page, [summary(analysisA, 'B', hashA), summary(analysisA2, 'B', hashA)], {})
  await openDeep(page)
  await show(page)

  await expect(page.getByTestId('pod-none')).toContainText(POD_LABELS.none)
  await expect(page.getByTestId('pod-map')).toHaveCount(0)
})

test('the pod-view of a second analysis of the same arm and snapshot is found', async ({ page }) => {
  const calls = await podApi(page, [summary(analysisA, 'B', hashA), summary(analysisA2, 'B', hashA)], { [analysisA2]: fromFixture() })
  await openDeep(page)
  await show(page)

  await expect(page.getByTestId('pod-map')).toBeVisible()
  expect(calls.slice(0, 2)).toEqual([`${analysisA} meta`, `${analysisA2} meta`])
})

test('a corrupt file is an error, not "no pod data"', async ({ page }) => {
  await podApi(page, armed, { [analysisA]: { status: 500, body: { error: { code: 'CORRUPT_POD_VIEW', message: 'pod view is damaged' } } } })
  await openDeep(page)
  await show(page)

  await expect(page.getByTestId('pod-error')).toContainText('pod view is damaged')
  await expect(page.getByTestId('pod-none')).toHaveCount(0)
})

test('a file of another snapshot is refused', async ({ page }) => {
  await podApi(page, armed, { [analysisA]: fromFixture({ hash: hashB }) })
  await openDeep(page)
  await show(page)

  await expect(page.getByTestId('pod-error')).toContainText(POD_LABELS.mismatch)
  await expect(page.getByTestId('pod-map')).toHaveCount(0)
})

test('an analysis without a snapshot hash shows no pods block and sends no pod-view request', async ({ page }) => {
  const calls = await podApi(page, [summary(analysisA, 'B', undefined)], {})
  await openDeep(page)

  await expect(page.getByTestId('pod-heatmap')).toHaveCount(0)
  expect(calls).toEqual([])
})

test('arms with different grids are drawn apart and never overlaid', async ({ page }) => {
  await podApi(page, [summary(analysisA, 'A', hashA), summary(analysisB, 'B', hashB)], {
    [analysisA]: fromFixture({ arm: 'A' }),
    [analysisB]: fromFixture({ arm: 'B', hash: hashB, step: podViewFile.step_ms * 2 }),
  })
  await openDeep(page)
  await show(page)

  await expect(page.getByTestId('pod-arm')).toHaveCount(2)
  await expect(page.getByTestId('pod-map')).toHaveCount(2)
  await expect(page.getByTestId('pod-grid-mismatch')).toHaveText(POD_LABELS.gridMismatch)
})

test('one arm without data does not hide the arm that has it', async ({ page }) => {
  await podApi(page, [summary(analysisA, 'A', hashA), summary(analysisB, 'B', hashB)], { [analysisB]: fromFixture({ arm: 'B', hash: hashB }) })
  await openDeep(page)
  await show(page)

  await expect(page.locator('[data-testid="pod-arm"][data-arm="A"] [data-testid="pod-none"]')).toBeVisible()
  await expect(page.locator('[data-testid="pod-arm"][data-arm="B"] [data-testid="pod-map"]')).toBeVisible()
})

// Нагрузка: 78 и 256 подов одного сервиса, 240 колонок. Измеряется число элементов SVG и время от клика до карты.
for (const podCount of [78, 256]) {
  test(`load: ${podCount} pods render at most 24 x 240 cells without a noticeable pause`, async ({ page }, info) => {
    const pods = Array.from({ length: podCount }, (_, index) => ({ pod: `svc-${String(index).padStart(3, '0')}`, service: 'svc' }))
    const rows: PodRow[] = pods.map((pod, index) => ({
      id: `r${String(index).padStart(5, '0')}`, pod: pod.pod, container: null, metric: 'openshift_pod_cpu_usage', unit: 'cores', aggregation: 'interval_mean',
      values: Array.from({ length: 240 }, (_, column) => ((index * 7 + column * 3) % 100) / 100),
    }))
    await podApi(page, armed, { [analysisA]: fromFixture({ pods, rows, columns: 240 }) })
    await openDeep(page)

    const started = Date.now()
    await show(page)
    await expect(page.getByTestId('pod-map')).toBeVisible()
    const elapsed = Date.now() - started
    const cells = await page.getByTestId('pod-cell').count()
    const svgElements = await page.getByTestId('pod-map').evaluate((svg) => svg.querySelectorAll('*').length)

    info.annotations.push({ type: 'measured', description: `${podCount} pods: ${cells} cells, ${svgElements} svg elements, ${elapsed} ms from click to the map` })
    expect(cells).toBe(24 * 240)
    expect(svgElements).toBeLessThanOrEqual(24 * 240 + 8)
    expect(elapsed).toBeLessThan(5000)
    await page.getByTestId('pod-view-table').click()
    await expect(page.getByTestId('pod-table-row')).toHaveCount(50)
  })
}

for (const theme of ['light', 'dark'] as const) {
  test(`the pods block has no serious axe violations and keyboard reaches its controls: ${theme}`, async ({ page }) => {
    // Colour is sampled by axe: without the transition a pressed button has its final colours.
    await page.emulateMedia({ colorScheme: theme, reducedMotion: 'reduce' })
    await podApi(page, armed, { [analysisA]: fromFixture() })
    await openDeep(page)
    await page.getByTestId('pod-show').focus()
    await page.keyboard.press('Enter')
    await expect(page.getByTestId('pod-map')).toBeVisible()
    await page.getByTestId('pod-view-table').focus()
    await page.keyboard.press('Enter')
    const region = page.getByRole('region', { name: POD_LABELS.tableRegion('B') })
    await region.focus()
    await expect(region).toBeFocused()

    const axe = await new AxeBuilder({ page }).include('#pod-heatmap').analyze()
    expect(axe.violations.filter((item) => item.impact === 'critical' || item.impact === 'serious').map((item) => item.id)).toEqual([])
    await page.getByTestId('pod-view-map').click()
    const mapAxe = await new AxeBuilder({ page }).include('#pod-heatmap').analyze()
    expect(mapAxe.violations.filter((item) => item.impact === 'critical' || item.impact === 'serious').map((item) => item.id)).toEqual([])
  })
}

test('the pods block fits a 375px screen without horizontal page scroll', async ({ page }) => {
  await page.setViewportSize({ width: 375, height: 800 })
  await podApi(page, armed, { [analysisA]: fromFixture() })
  await openDeep(page)
  await show(page)
  await expect(page.getByTestId('pod-map')).toBeVisible()

  const width = await page.evaluate(() => ({ scroll: document.documentElement.scrollWidth, client: document.documentElement.clientWidth }))
  expect(width.scroll).toBeLessThanOrEqual(width.client)
})
