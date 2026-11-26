import AxeBuilder from '@axe-core/playwright'
import { expect, test, type Page } from '@playwright/test'
import { OVERVIEW_LABELS, SETUP_LABELS } from '../src/shell/labels'
import {
  attentionItems,
  incidentView,
  loadFrame,
  loadLane,
  loadP95Thresholds,
  loadSeries,
  platformTiles,
  trendOverview,
} from '../src/shell/overview'
import { laneGrid, laneOption, type ChartColors } from '../src/shell/timeChart'
import { thresholdKpis } from '../src/verdictSummary'
import type { AnalysisResult, Incident, Incidents } from '../src/types'

const reference = { run_id: 'inc-run', analysis_id: 'a'.repeat(64) }
const run = { ...reference, source_type: 'jmeter', sha256: 'b'.repeat(64), size_bytes: 100, original_filename: 'incidents.jtl' }
const origin = 1_767_225_600_000

const overall = {
  id: 'm-overall', type: 'metric_summary', scope: { kind: 'overall' }, sample_count: 1000, error_count: 20,
  error_rate_ratio: { numerator: 20, denominator: 1000 }, throughput_rps: { numerator: 100000, denominator: 60000 },
  latency_ms: { p50: 80, p95: 420, p99: 600, max: 900 },
}

const stageBinding = {
  id: 'sb-1', type: 'stage_binding', mode: 'declared', declaration_sha256: 'd'.repeat(64),
  run_from_epoch_ms: origin, run_to_epoch_ms: origin + 120_000,
  evaluated_window_ids: ['steady'], evaluated_millis: 60_000, excluded_millis: 60_000, verdict_scope: 'steady',
  stages: [
    { id: 'ramp-up', role: 'excluded', from_offset_ms: 0, to_offset_ms: 40_000, from_epoch_ms: origin, to_epoch_ms: origin + 40_000 },
    { id: 'steady', role: 'steady', from_offset_ms: 40_000, to_offset_ms: 100_000, from_epoch_ms: origin + 40_000, to_epoch_ms: origin + 100_000 },
    { id: 'ramp-down', role: 'excluded', from_offset_ms: 100_000, to_offset_ms: 120_000, from_epoch_ms: origin + 100_000, to_epoch_ms: origin + 120_000 },
  ],
}

function makeIncident(rank: number, family: 'TRANSACTION' | 'RESOURCE', over: Partial<Incident> = {}): Incident {
  const hex = String(rank).padStart(64, '0')
  const scope = family === 'TRANSACTION'
    ? { kind: 'transaction' as const, label: `POST /tx-${rank}` }
    : { kind: 'entity' as const, entity: `pod-${rank}` }
  return {
    id: `incident-${hex}`,
    rank,
    in_overview: rank <= 7,
    priority: rank <= 2 ? 'HIGH' : rank <= 6 ? 'MEDIUM' : 'LOW',
    priority_key: { tier: rank <= 2 ? 1 : 3, finding_count: 2, first_epoch_ms: origin + 40_000 },
    title: family === 'TRANSACTION' ? `Нарушение SLA: транзакция POST /tx-${rank} в окне steady` : `Сигналы ресурса: pod-${rank} в окне steady`,
    summary: `В окне steady найдено находок: 2.`,
    scope,
    window_id: 'steady',
    interval_basis: family === 'TRANSACTION' ? 'WINDOW' : 'FINDINGS',
    interval: { from_epoch_ms: origin + 40_000, to_epoch_ms: origin + 100_000 },
    grouping: {
      rule: 'incident-grouping.v1',
      key: { family, window_id: 'steady', scope, cluster_from_epoch_ms: family === 'RESOURCE' ? origin + 40_000 : null },
    },
    finding_count: 2,
    finding_types: [family === 'TRANSACTION' ? 'policy_failure' : 'resource_threshold_violation'],
    finding_ids: [`f-${rank}`],
    evidence_ids: [family === 'TRANSACTION' ? `pc-${rank}` : `rc-${rank}`],
    refs_truncated: false,
    negative_evidence: [
      { check: 'NO_ANOMALY_EPISODES', outcome: 'NOT_CONFIRMED', text: 'Проверки отклонений в окне steady не нашли эпизодов: 1.', evidence_ids: ['ac-1'] },
    ],
    next_checks: [
      family === 'TRANSACTION'
        ? { check: 'COMPARE_WITH_BASELINE', text: 'Сравнить метрики с baseline.', evidence_ids: [`pc-${rank}`] }
        : { check: 'OPEN_RESOURCE_SERIES', text: `Открыть ряды сущности pod-${rank} за интервал находок.`, evidence_ids: [`rc-${rank}`] },
    ],
    coincident_with: [],
    ...over,
  }
}

const showcaseIncidents: Incidents = {
  schema_version: 'incident.v1',
  method: 'incident-synthesis.v1',
  status: 'EVALUATED',
  overview_limit: 7,
  total_count: 10,
  omitted_count: 2,
  items: [
    makeIncident(1, 'TRANSACTION', {
      evidence_ids: ['pc-overall'],
      coincident_with: [{ incident_id: `incident-${'8'.padStart(64, '0')}`, basis: 'SAME_WINDOW' }],
      next_checks: [
        { check: 'COMPARE_WITH_BASELINE', text: 'Сравнить метрики всего прогона с baseline.', evidence_ids: ['pc-overall'] },
        { check: 'OPEN_SAME_WINDOW_SIGNALS', text: 'Открыть сигналы окна steady, совпавшие по времени: 1.', evidence_ids: [] },
      ],
    }),
    makeIncident(2, 'RESOURCE', { evidence_ids: ['rc-cpu'] }),
    makeIncident(3, 'TRANSACTION', {
      window_id: null,
      interval_basis: 'UNKNOWN',
      interval: null,
      priority_key: { tier: 1, finding_count: 1, first_epoch_ms: null },
      evidence_ids: ['pc-tx3'],
    }),
    makeIncident(4, 'RESOURCE', { evidence_ids: ['tc-cpu'] }),
    makeIncident(5, 'TRANSACTION', { evidence_ids: ['pc-tx5'] }),
    makeIncident(6, 'RESOURCE', { evidence_ids: ['rc-mem-covered'] }),
    makeIncident(7, 'RESOURCE', { evidence_ids: ['rc-disk'] }),
    makeIncident(8, 'RESOURCE', { in_overview: false, evidence_ids: ['rc-net'] }),
  ],
}

const showcaseResult = {
  schema_version: 'analysis-result.v1',
  run_id: reference.run_id,
  analysis_mode: 'standard',
  run_validity: 'VALID',
  policy_verdict: 'NO_VERDICT',
  analysis_coverage: { status: 'INCOMPLETE', reasons: ['SMALL_SAMPLE'] },
  findings: [
    { type: 'resource_threshold_violation', rule_id: 'cpu-limit', window_id: 'steady', entity: 'pod-2', from_epoch_ms: origin + 40_000, to_epoch_ms: origin + 100_000, cell_count: 4, observed_min: '91', observed_max: '98' },
  ],
  evidence: [
    overall,
    stageBinding,
    { id: 'rb-1', type: 'resource_binding', run_from_epoch_ms: origin },
    { id: 'pc-overall', type: 'policy_check', rule_id: 'overall-p95', metric: 'response_time_p95_ms', operator: 'lte', threshold: 250, status: 'FAIL', metric_evidence_id: 'm-overall', observed: 420, scope: { kind: 'overall' } },
    { id: 'pc-missing', type: 'policy_check', rule_id: 'rare-tx', metric: 'response_time_p95_ms', operator: 'lte', threshold: 300, status: 'NO_VERDICT', reason_code: 'INSUFFICIENT_SAMPLES', sample_count: 5, sample_floor: 20 },
    { id: 'pc-pass', type: 'policy_check', rule_id: 'overall-errors', metric: 'error_rate_ratio', operator: 'lte', threshold: 0.05, status: 'PASS', metric_evidence_id: 'm-overall', observed: { numerator: 20, denominator: 1000 }, scope: { kind: 'overall' } },
    { id: 'rc-cpu', type: 'resource_policy_check', window_id: 'steady', rule_id: 'cpu-limit', series_id: 'cpu', unit: '%', operator: 'gt', threshold: '90', effect: 'sla', status: 'FAIL', reason: null },
    { id: 'rc-mem', type: 'resource_policy_check', window_id: 'steady', rule_id: 'mem-diag', series_id: 'mem', unit: '%', operator: 'gt', threshold: '85', effect: 'diagnostic', status: 'FAIL', reason: null },
    { id: 'rs-cpu', type: 'resource_summary', series_id: 'cpu', metric: 'cpu_usage', unit: '%', entity: 'pod-2', role: 'system', aggregation: 'interval_mean', window_id: 'steady', from_epoch_ms: origin + 40_000, to_epoch_ms: origin + 100_000, expected_cells: 4, observed_cells: 4, missing_cells: 0, longest_gap_cells: 0, statistics: { max: '98' }, reasons: [] },
    { id: 'rs-mem', type: 'resource_summary', series_id: 'mem', metric: 'mem_usage', unit: '%', entity: 'pod-2', role: 'system', aggregation: 'interval_mean', window_id: 'steady', from_epoch_ms: origin + 40_000, to_epoch_ms: origin + 100_000, expected_cells: 4, observed_cells: 4, missing_cells: 1, longest_gap_cells: 1, statistics: { max: '70' }, reasons: [] },
    { id: 'ts-1', type: 'trend_summary', checks_total: 2, observed: 1, not_material: 1, insufficient: 0, unavailable: 0, method: 'slope-materiality.v1', uncertainty: 'NOT_ESTIMATED' },
    { id: 'tc-cpu', type: 'trend_check', check_id: 'trend-cpu', series_id: 'cpu', metric: 'cpu_usage', unit: '%', entity: 'pod-2', window_id: 'steady', window_from_epoch_ms: origin + 40_000, window_to_epoch_ms: origin + 100_000, declared_direction: 'increase', status: 'TREND_OBSERVED', min_cells: 4, expected_cells: 4, observed_cells: 4, missing_cells: 0, longest_gap_cells: 0, median: '88', slope_per_second: '0.25', split_half_shift: '12', magnitude_gate: { min_slope_units_per_second: '0.01', min_split_half_shift_pct: '5', required_split_half_shift_units: '2' }, observed_direction: 'increase', method: 'slope-materiality.v1', uncertainty: 'NOT_ESTIMATED', reasons: [] },
    { id: 'cp-1', type: 'correlation_pair', pair_id: 'p1', window_id: 'steady', resource_series_id: 'cpu', load_metric: 'response_time_p95_ms', entity: 'pod-2', resource_unit: '%', load_unit: 'ms', from_epoch_ms: origin + 40_000, to_epoch_ms: origin + 100_000, expected_cells: 4, paired_cells: 4, lag_used_cells: 0, raw_rho: '0.9', partial_rho: null, best_lag_ms: 0, best_lag_rho: '0.9', lag_profile: [], status: 'CANDIDATE', controls_requested: [], controls_used: [], controls_dropped: [], sensitivity_without_achieved_rps: null, uncertainty: 'NOT_ESTIMATED', reasons: [] },
    { id: 'sel-1', type: 'correlation_headline_selection', pair_id: 'p1', window_id: 'steady', method: 'mbb-lag-max-holm.v1', rng: 'seed', status: 'SELECTED', family_hypotheses: 1, bootstrap_replicates: 100, block_lengths_cells: [2], alpha: '0.05', p_value_b10: '0.01', p_value_b20: '0.01', max_p_value: '0.01', holm_adjusted_p_value: '0.01', selected: true, reasons: [] },
  ],
  incidents: showcaseIncidents,
} as unknown as AnalysisResult

async function fixtureApi(page: Page, result: unknown) {
  await page.route('**/api/**', async (route) => {
    const path = new URL(route.request().url()).pathname
    const method = route.request().method()
    let body: unknown
    if (path === '/api/bootstrap') body = { csrf_token: 'ui-test', max_upload_bytes: 1000000 }
    else if (method === 'GET' && path === '/api/jobs') body = { jobs: [] }
    else if (method === 'GET' && (path === '/api/jenkins' || path === '/api/grafana' || path === '/api/sources')) body = { profiles: [] }
    else if (path.endsWith('/advice')) body = { advice: null, job: null }
    else if (path === '/api/runs') body = { runs: [run], next_after: null }
    else if (path === '/api/baseline') body = { baseline: null }
    else if (path.endsWith('/analyses')) body = { analyses: [{ analysis_id: reference.analysis_id, policy_sha256: 'c'.repeat(64), policy_verdict: (result as AnalysisResult).policy_verdict, run_validity: (result as AnalysisResult).run_validity }], next_after: null }
    else if (path.endsWith('/result')) body = result
    else if (path.endsWith('/buckets')) body = { buckets: [{ bucket_start_ms: 0, sample_count: 100, error_count: 1, p95_latency_ms: 200, max_latency_ms: 300, hdr_v2_base64: 'AAAA' }, { bucket_start_ms: 40_000, sample_count: 120, error_count: 3, p95_latency_ms: 420, max_latency_ms: 500, hdr_v2_base64: 'AAAA' }, { bucket_start_ms: 100_000, sample_count: 90, error_count: 0, p95_latency_ms: 180, max_latency_ms: 250, hdr_v2_base64: 'AAAA' }], next_from_ms: null }
    else if (path.endsWith('/resource-series')) body = { schema_version: 'resource-series.v1', kind: 'catalog', resource_snapshot_sha256: 'e'.repeat(64), numeric_encoding: 'ieee754-double', grid: { start_epoch_ms: origin, step_ms: 15_000, point_count: 8 }, windows: [{ id: 'steady', from_epoch_ms: origin + 40_000, to_epoch_ms: origin + 100_000 }], series: [{ id: 'cpu', metric: 'cpu_usage', unit: '%', entity: 'pod-2', role: 'system', aggregation: 'interval_mean', reducer: 'mean', labels: {}, observed_cells: 8 }, { id: 'mem', metric: 'mem_usage', unit: '%', entity: 'pod-2', role: 'system', aggregation: 'interval_mean', reducer: 'mean', labels: {}, observed_cells: 8 }], next_after: null }
    else if (path.endsWith('/resource-series/values')) body = { schema_version: 'resource-series.v1', kind: 'values', resource_snapshot_sha256: 'e'.repeat(64), numeric_encoding: 'ieee754-double', grid: { start_epoch_ms: origin, source_step_ms: 15_000, step_ms: 15_000, first_cell_start_ms: origin, cell_count: 8, source_cells_per_cell: 1, last_cell_source_cells: 1 }, series: [{ id: 'cpu', aggregation: 'interval_mean', reducer: 'mean', values: [40, 50, 60, 80, 92, 98, 95, 70], observed: [1, 1, 1, 1, 1, 1, 1, 1] }, { id: 'mem', aggregation: 'interval_mean', reducer: 'mean', values: [50, 55, 60, 62, 65, 68, 70, 66], observed: [1, 1, 1, 1, 1, 1, 1, 1] }], next_from_ms: null }
    else throw new Error(`Unexpected UI request ${method} ${path}`)
    await route.fulfill({ json: body })
  })
}

async function openOverview(page: Page, result: unknown) {
  await fixtureApi(page, result)
  await page.goto('/')
  await page.getByRole('button', { name: 'incidents.jtl' }).click()
  await page.locator(`button[title="${reference.analysis_id}"]`).click()
  await expect(page.getByTestId('overview-panel')).toBeVisible()
}

test('pure adapters: SETUP_LABELS, U1 dedup, incidentView, trends, platformTiles, KPIs, sparkline option', () => {
  expect(SETUP_LABELS.stagesRefusals.RUN_TOO_SHORT).toBeTruthy()
  expect(SETUP_LABELS.stagesRefusals.NO_REQUEST_SAMPLES).toBeTruthy()
  expect(SETUP_LABELS.stagesRefusals.RUN_TOO_LONG).toBeTruthy()
  expect(SETUP_LABELS.stagesRefusals.IDLE_GAP_INSIDE_RUN).toBeTruthy()
  expect(SETUP_LABELS.stagesWarnings.DEGRADED_INPUT).toBeTruthy()

  const view = incidentView(showcaseResult)
  expect(view.status).toBe('EVALUATED')
  expect(view.overviewItems).toHaveLength(7)
  expect(view.moreItems).toHaveLength(1)
  expect(view.moreResourceCount).toBe(1)
  expect(view.moreTransactionCount).toBe(0)
  expect(view.omittedCount).toBe(2)
  expect(view.overviewItems[2].intervalText).toBe(OVERVIEW_LABELS.incidentIntervalUnknown)

  const badEpochView = incidentView({
    ...showcaseResult,
    incidents: {
      ...showcaseIncidents,
      items: [makeIncident(1, 'RESOURCE', { interval: { from_epoch_ms: 1e20, to_epoch_ms: Number.NaN } })],
    },
  })
  expect(badEpochView.overviewItems[0].intervalText).toBe('— – — (находки)')

  // Ruling U1: pc-overall, rc-cpu, tc-cpu covered by incidents are omitted, while no_verdict, coverage, correlation, and uncovered rc-mem stay.
  const attention = attentionItems(showcaseResult)
  expect(attention.map((a) => a.key)).toEqual([
    'no_verdict:INSUFFICIENT_SAMPLES|Правила',
    'coverage:SMALL_SAMPLE',
    'diagnostic:resource:rc-mem',
    'diagnostic:correlation:cp-1',
  ])

  const trends = trendOverview(showcaseResult)
  expect(trends?.observed).toBe(1)
  expect(trends?.cards).toHaveLength(1)
  expect(platformTiles(showcaseResult).map((t) => [t.seriesId, t.status])).toEqual([['cpu', 'FAIL'], ['mem', 'DIAGNOSTIC']])
  expect(thresholdKpis(showcaseResult).map((k) => [k.ruleId, k.status])).toEqual([['overall-p95', 'FAIL'], ['cpu-limit', 'FAIL'], ['rare-tx', 'NO_VERDICT'], ['overall-errors', 'PASS']])
  expect(loadP95Thresholds(showcaseResult)).toHaveLength(1)

  const series = loadSeries([{ bucket_start_ms: 0, sample_count: 10, error_count: 0, p95_latency_ms: 100, max_latency_ms: 120, hdr_v2_base64: '' }], 1)
  const lane = loadLane(series, 'p95', loadP95Thresholds(showcaseResult))
  expect(lane.hi).toBe(250)
  expect(laneGrid({ timeLabels: false, stageLabels: false, sparkline: true })).toEqual({ left: 2, right: 2, top: 4, bottom: 4, height: 44 })
  const colors: ChartColors = { brand: '#1', warn: '#2', fail: '#3', text: '#4', muted: '#5', border: '#6', fontFamily: 'sans-serif' }
  const opt = laneOption(lane, loadFrame(series), [], { timeLabels: false, stageLabels: false, sparkline: true }, colors)
  expect(opt.xAxis).toMatchObject({ show: false })
  expect(opt.yAxis).toMatchObject({ show: false })
})

test('renders <=7 incident cards, U1 residual attention, overflow disclosure, coincident jump, and showcase sections', async ({ page }) => {
  await page.addInitScript(() => {
    (window as never as { csp: string[] }).csp = []
    document.addEventListener('securitypolicyviolation', (event) => (window as never as { csp: string[] }).csp.push(`${event.violatedDirective} ${event.blockedURI}`))
  })
  await openOverview(page, showcaseResult)

  // Verdict KPIs
  await expect(page.getByTestId('verdict-kpis')).toBeVisible()
  await expect(page.getByTestId('verdict-kpi')).toHaveCount(4)

  // Incidents <= 7 + overflow + UNKNOWN interval + collapsed details + coincident link to rank 8
  await expect(page.getByTestId('incident-card')).toHaveCount(7)
  await expect(page.getByTestId('incident-card').nth(2)).toContainText(OVERVIEW_LABELS.incidentIntervalUnknown)
  await expect(page.getByTestId('incidents-omitted')).toContainText('2')
  const moreBtn = page.getByTestId('incidents-more')
  await expect(moreBtn).toHaveAttribute('aria-expanded', 'false')
  await page.getByTestId('incident-coincident').first().click()
  await expect(moreBtn).toHaveAttribute('aria-expanded', 'true')
  await expect(page.getByTestId('incident-overflow-card')).toHaveCount(1)
  await expect(page.locator(`#incident-incident-${'8'.padStart(64, '0')}`)).toBeFocused()

  // U1 residual attention items (no pc-overall / rc-cpu / tc-cpu)
  const kinds = await page.getByTestId('attention-item').evaluateAll((nodes) => nodes.map((node) => node.getAttribute('data-kind')))
  expect(kinds).toEqual(['no_verdict', 'coverage', 'diagnostic', 'diagnostic'])

  // Trends, Platform tiles & sparklines, Load p95 threshold markLine
  await expect(page.getByTestId('overview-trends')).toBeVisible()
  await expect(page.getByTestId('overview-trend-card')).toHaveCount(1)
  await expect(page.getByTestId('overview-platform')).toBeVisible()
  await expect(page.getByTestId('overview-platform-tile')).toHaveCount(2)
  await expect.poll(() => page.getByTestId('track-p95').locator('svg text').allTextContents()).toEqual(expect.arrayContaining([expect.stringContaining('порог 250')]))

  // Bottom tail removed from Overview
  await expect(page.getByLabel('Скачивание анализа')).toBeHidden()
  await expect(page.getByRole('heading', { name: 'Saved-run analytics' })).toHaveCount(0)

  // Stage ribbon click jumps to Deep Analysis pre-selecting steady stage
  await page.locator('[data-testid="overview-stage"][data-stage="steady"]').click()
  await expect(page.getByTestId('deep-panel')).toBeVisible()
  await expect(page.getByTestId('deep-stage-steady')).toHaveAttribute('aria-pressed', 'true')

  expect(await page.evaluate(() => (window as never as { csp: string[] }).csp)).toEqual([])
})

test('handles NOT_EVALUATED, empty EVALUATED, and absent incidents backward compatibility', async ({ page }) => {
  await openOverview(page, { ...showcaseResult, run_validity: 'INVALID', policy_verdict: 'NO_VERDICT', incidents: { schema_version: 'incident.v1', method: 'incident-synthesis.v1', status: 'NOT_EVALUATED', reason_code: 'RUN_NOT_VALID', overview_limit: 7, total_count: 0, omitted_count: 0, items: [] } })
  await expect(page.getByTestId('incidents-not-evaluated')).toContainText('RUN_NOT_VALID')

  await openOverview(page, { ...showcaseResult, policy_verdict: 'PASS', findings: [], evidence: [overall], incidents: { schema_version: 'incident.v1', method: 'incident-synthesis.v1', status: 'EVALUATED', overview_limit: 7, total_count: 0, omitted_count: 0, items: [] } })
  await expect(page.getByTestId('incidents-empty')).toContainText(OVERVIEW_LABELS.incidentsEmpty)

  await openOverview(page, { ...showcaseResult, policy_verdict: 'PASS', findings: [], evidence: [overall], incidents: { schema_version: 'incident.v1', method: 'incident-synthesis.v1', status: 'EVALUATED', overview_limit: 0, total_count: 2, omitted_count: 1, items: [makeIncident(1, 'RESOURCE', { in_overview: false })] } })
  await expect(page.getByTestId('incidents-empty')).toHaveCount(0)
  await expect(page.getByTestId('incidents-more')).toBeVisible()
  await expect(page.getByTestId('incidents-omitted')).toContainText('1')

  const withoutIncidents = { ...showcaseResult, evidence: showcaseResult.evidence.filter((e) => (e as { type: string }).type !== 'resource_binding') }
  delete (withoutIncidents as { incidents?: unknown }).incidents
  await openOverview(page, withoutIncidents)
  await expect(page.getByTestId('overview-incidents')).toHaveCount(0)
  await expect(page.getByTestId('attention-item').first()).toHaveAttribute('data-kind', 'violation')
})

for (const theme of ['light', 'dark'] as const) {
  for (const width of [1280, 375, 320]) {
    test(`incidents overview has no serious axe violations, no horizontal scroll, and >=44px controls (${width}px, ${theme})`, async ({ page }) => {
      await page.setViewportSize({ width, height: 800 })
      await page.emulateMedia({ colorScheme: theme })
      await openOverview(page, showcaseResult)

      const scrollOk = await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)
      expect(scrollOk).toBe(true)

      for (const control of await page.getByTestId('overview-stage').all()) {
        const box = (await control.boundingBox())!
        expect(box.height).toBeGreaterThanOrEqual(44)
        expect(box.width).toBeGreaterThanOrEqual(44)
      }
      for (const control of [
        ...(await page.getByTestId('incident-open').all()),
        page.getByTestId('incidents-more'),
      ]) {
        const box = (await control.boundingBox())!
        expect(box.height).toBeGreaterThanOrEqual(24)
        expect(box.width).toBeGreaterThanOrEqual(44)
      }

      const axe = await new AxeBuilder({ page }).analyze()
      expect(axe.violations.filter((item) => item.impact === 'critical' || item.impact === 'serious').map((item) => item.id)).toEqual([])
    })
  }
}
