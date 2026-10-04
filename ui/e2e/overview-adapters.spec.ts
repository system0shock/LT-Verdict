import { expect, test } from '@playwright/test'
import type { AnalysisResult, Bucket, BucketPage } from '../src/types'
import { OVERVIEW_LABELS } from '../src/shell/labels'
import { failedLinesOf, summarizeVerdict } from '../src/verdictSummary'
import { MAX_LOAD_PAGES, fetchRunLoad } from '../src/shell/deep'
import {
  attentionItems,
  cursorFraction,
  cursorSummary,
  formatOffset,
  keyMetrics,
  loadSeries,
  nearestIndex,
  trackPoints,
} from '../src/shell/overview'

// Чистые адаптеры вкладки «Обзор»: результат анализа на входе, готовые данные на выходе.
function build(over: Record<string, unknown>): AnalysisResult {
  return {
    schema_version: 'analysis-result.v1',
    run_id: 'run-1',
    analysis_mode: 'standard',
    run_validity: 'VALID',
    policy_verdict: 'NO_POLICY',
    analysis_coverage: { status: 'COMPLETE', reasons: [] },
    findings: [],
    evidence: [],
    ...over,
  } as unknown as AnalysisResult
}

const overall = {
  id: 'm-overall', type: 'metric_summary', scope: { kind: 'overall' }, sample_count: 3650, error_count: 88,
  error_rate_ratio: { numerator: 88, denominator: 3650 }, throughput_rps: { numerator: 3650000, denominator: 30000 },
  latency_ms: { p50: 100, p95: 2340, p99: 3000.5, max: 4000 },
}
const checkout = { id: 'm-checkout', type: 'metric_summary', scope: { kind: 'transaction', group_path: [], label: 'POST /checkout', sample_kind: 'JMETER_SAMPLER' }, sample_count: 10, error_count: 0, error_rate_ratio: null, throughput_rps: { numerator: 1, denominator: 1 }, latency_ms: { p50: 1, p95: 1, p99: 1, max: 1 } }
const p95Rule = (id: string, status: string, observed: number) => ({
  id: `check-${id}`, type: 'policy_check', rule_id: id, metric: 'response_time_p95_ms', operator: 'lte', threshold: 2000,
  status, metric_evidence_id: 'm-checkout', observed,
})
const resourceCheck = (id: string, status: string, effect: string, reason: string | null = null) => ({
  id: `rc-${id}`, type: 'resource_policy_check', window_id: 'Soak', rule_id: id, series_id: 'mem-b', unit: '%', operator: 'gt',
  threshold: '90', effect, status, reason,
})
const violation = (ruleId: string) => ({
  type: 'resource_threshold_violation', rule_id: ruleId, window_id: 'Soak', entity: 'pod-7', from_epoch_ms: 1000, to_epoch_ms: 61000,
  cell_count: 3, observed_min: '95', observed_max: '100',
})
const summary = (seriesId: string, reasons: string[]) => ({
  id: `rs-${seriesId}`, type: 'resource_summary', series_id: seriesId, metric: 'memory', unit: '%', entity: 'pod-7', role: 'system',
  aggregation: 'interval_mean', window_id: 'Soak', from_epoch_ms: 0, to_epoch_ms: 1, expected_cells: 10, observed_cells: reasons.includes('NO_OBSERVATIONS') ? 0 : 10,
  missing_cells: 0, longest_gap_cells: 0, statistics: null, reasons,
})
const trend = (id: string, status: string, extra: Record<string, unknown> = {}) => ({
  id: `t-${id}`, type: 'trend_check', check_id: id, series_id: `cpu-${id}`, metric: 'cpu', unit: '%', entity: 'pod-1', window_id: 'Soak',
  window_from_epoch_ms: 0, window_to_epoch_ms: 1, declared_direction: 'increase', status, min_cells: 5, expected_cells: 10, observed_cells: 10,
  missing_cells: 0, longest_gap_cells: 0, median: '69.3', slope_per_second: '0.0015', split_half_shift: '5.4',
  magnitude_gate: { min_slope_units_per_second: '0', min_split_half_shift_pct: '1', required_split_half_shift_units: '1' },
  observed_direction: 'increase', method: 'slope-materiality.v1', uncertainty: 'NOT_ESTIMATED', reasons: [], ...extra,
})
const correlation = (id: string, status: string, rawRho: string | null = '0.83') => ({
  id: `cp-${id}`, type: 'correlation_pair', pair_id: id, window_id: 'Soak', resource_series_id: 'mem-b', load_metric: 'throughput_rps', entity: 'pod-7',
  resource_unit: '%', load_unit: 'rps', from_epoch_ms: 0, to_epoch_ms: 1, expected_cells: 10, paired_cells: 9, lag_used_cells: 0, raw_rho: rawRho,
  partial_rho: null, best_lag_ms: null, best_lag_rho: null, lag_profile: [], status, controls_requested: [], controls_used: [], controls_dropped: [],
  sensitivity_without_achieved_rps: null, uncertainty: 'NOT_ESTIMATED', reasons: [],
})
const kinds = (result: AnalysisResult) => attentionItems(result).map((item) => item.kind)

test.describe('attention items', () => {
  test('lists every broken rule with its table target, business rules before resource SLA rules', () => {
    const items = attentionItems(build({
      policy_verdict: 'FAIL',
      evidence: [overall, checkout, p95Rule('a-p95', 'FAIL', 2340), p95Rule('b-p95', 'FAIL', 2500), p95Rule('ok', 'PASS', 100), resourceCheck('mem-limit', 'FAIL', 'sla')],
      findings: [violation('mem-limit')],
    }))

    expect(items.map((item) => item.kind)).toEqual(['violation', 'violation', 'violation'])
    expect(items.map((item) => item.key)).toEqual(['violation:check-a-p95', 'violation:check-b-p95', 'violation:rc-mem-limit'])
    expect(items[0].title).toContain('a-p95')
    expect(items[0].detail).toContain('2')
    expect(items.map((item) => item.target)).toEqual([
      { tab: 'tables', targetId: 'policy-results' },
      { tab: 'tables', targetId: 'policy-results' },
      { tab: 'tables', targetId: 'resource-results' },
    ])
    expect(items.map((item) => item.openLabel)).toEqual([OVERVIEW_LABELS.openRules, OVERVIEW_LABELS.openRules, OVERVIEW_LABELS.openResources])
    expect(items.every((item) => !item.diagnostic)).toBe(true)
  })

  test('small-sample reasons lead to the policy table', () => {
    const blocked = build({
      policy_verdict: 'NO_VERDICT',
      analysis_coverage: { status: 'INCOMPLETE', reasons: ['INSUFFICIENT_SAMPLES', 'SMALL_SAMPLE'] },
      evidence: [overall, checkout, { ...p95Rule('rare', 'NO_VERDICT', 0), reason_code: 'INSUFFICIENT_SAMPLES', sample_count: 12, sample_floor: 20 }],
    })

    const targets = attentionItems(blocked).map((entry) => [entry.key, entry.target?.targetId])

    expect(targets).toEqual([
      ['no_verdict:INSUFFICIENT_SAMPLES|Правила', 'policy-results'],
      ['coverage:SMALL_SAMPLE', 'policy-results'],
    ])
  })

  test('INSUFFICIENT_SAMPLES in a capacity result still leads to the policy table, stage reasons to the capacity table', () => {
    const capacity = build({
      analysis_mode: 'capacity_step',
      policy_verdict: 'NO_VERDICT',
      analysis_coverage: { status: 'INCOMPLETE', reasons: ['INSUFFICIENT_SAMPLES'] },
      capacity_summary: {
        schema_version: 'capacity.v1', load_axis: 'rps', unit: 'requests/s', bound_type: 'BOUNDED', lower_inclusive: 100, upper_exclusive: 200,
        policy_verdict: 'NO_VERDICT', reasons: ['CAPACITY_STAGE_NOT_VERIFIED'], capacity_knee: null, knee_reason: 'KNEE_DETECTOR_NOT_IMPLEMENTED', stages: [],
      },
      evidence: [overall],
    })

    expect(attentionItems(capacity).map((entry) => [entry.key, entry.target?.targetId])).toEqual([
      ['no_verdict:CAPACITY_STAGE_NOT_VERIFIED|', 'capacity-results'],
      ['coverage:INSUFFICIENT_SAMPLES', 'policy-results'],
    ])
  })

  test('PASS and INVALID produce no violation and a plain PASS has nothing to flag', () => {
    expect(attentionItems(build({ policy_verdict: 'PASS', evidence: [overall, checkout, p95Rule('ok', 'PASS', 100)] }))).toEqual([])
    expect(kinds(build({ policy_verdict: 'NO_VERDICT', run_validity: 'INVALID', analysis_coverage: { status: 'INCOMPLETE', reasons: ['MALFORMED_JMETER_CSV'] } })))
      .not.toContain('violation')
  })

  test('NO_POLICY points to the policy field and never claims a violation', () => {
    const items = attentionItems(build({ policy_verdict: 'NO_POLICY', evidence: [overall] }))

    expect(items).toHaveLength(1)
    expect(items[0]).toMatchObject({ kind: 'policy', title: OVERVIEW_LABELS.noPolicyTitle, detail: OVERVIEW_LABELS.noPolicyDetail, diagnostic: false })
    expect(items[0].target).toEqual({ tab: 'setup', targetId: 'policy-file' })
    expect(items[0].openLabel).toBe(OVERVIEW_LABELS.openSetup)
  })

  test('a failed diagnostic-effect resource check appears once as a non-causal diagnostic', () => {
    const result = build({
      policy_verdict: 'PASS',
      evidence: [overall, checkout, p95Rule('ok', 'PASS', 100), resourceCheck('mem-diag', 'FAIL', 'diagnostic')],
      findings: [violation('mem-diag')],
    })
    const items = attentionItems(result)
    const diagnostics = items.filter((entry) => entry.kind === 'diagnostic')
    const slaResult = build({ ...result, evidence: [resourceCheck('mem-diag', 'FAIL', 'sla')] })

    expect(kinds(result)).not.toContain('violation')
    expect(diagnostics).toHaveLength(1)
    expect(diagnostics[0]).toMatchObject({ diagnostic: true, target: { tab: 'deep', targetId: 'deep-title' }, openLabel: OVERVIEW_LABELS.openDeep })
    expect(diagnostics[0].key).toMatch(/^diagnostic:resource:/)
    expect(diagnostics[0].title).toBe(failedLinesOf(slaResult)[0].title)
    expect(diagnostics[0].detail).toBe(failedLinesOf(slaResult)[0].detail)
  })

  test('passing and unresolved diagnostic resource checks are omitted, but capacity failures appear', () => {
    const checks = [resourceCheck('passing', 'PASS', 'diagnostic'), resourceCheck('unknown', 'NO_VERDICT', 'diagnostic')]
    expect(attentionItems(build({ policy_verdict: 'PASS', evidence: checks, findings: [violation('unknown')] }))).toEqual([])

    const capacity = build({
      analysis_mode: 'capacity_step', policy_verdict: 'PASS',
      capacity_summary: {
        schema_version: 'capacity.v1', load_axis: 'rps', unit: 'rps', stages: [], bound_type: 'LOWER', lower_inclusive: 100, upper_exclusive: null,
        policy_verdict: 'PASS', reasons: [], capacity_knee: null, knee_reason: 'none',
      },
      evidence: [...checks, resourceCheck('capacity-diag', 'FAIL', 'diagnostic')],
      findings: [violation('capacity-diag')],
    })
    expect(attentionItems(capacity).map((entry) => entry.key)).toEqual(['diagnostic:resource:rc-capacity-diag'])
  })

  test('resource diagnostics lead trends and correlations and do not change verdict counts', () => {
    const evidence = [overall, checkout, p95Rule('failed', 'FAIL', 2340)]
    const baseline = build({ policy_verdict: 'FAIL', evidence })
    const result = build({
      policy_verdict: 'FAIL',
      evidence: [...evidence, correlation('p1', 'CANDIDATE'), trend('a', 'TREND_OBSERVED'), resourceCheck('mem-diag', 'FAIL', 'diagnostic')],
      findings: [violation('mem-diag')],
    })

    expect(attentionItems(result).map((entry) => entry.key)).toEqual([
      'violation:check-failed', 'diagnostic:resource:rc-mem-diag', 'diagnostic:trend:t-a', 'diagnostic:correlation:cp-p1',
    ])
    expect(summarizeVerdict(result).headline).toBe(summarizeVerdict(baseline).headline)
    expect(summarizeVerdict(result).chip).toBe(summarizeVerdict(baseline).chip)
    expect(summarizeVerdict(result).lines).toEqual(summarizeVerdict(baseline).lines)
  })

  test('NO_VERDICT lists causes with a target chosen by reason code and keeps found violations', () => {
    const items = attentionItems(build({
      policy_verdict: 'NO_VERDICT',
      analysis_coverage: { status: 'INCOMPLETE', reasons: ['TRANSACTION_NOT_FOUND', 'MISSING_RESOURCE_CELLS'] },
      evidence: [
        overall, checkout, p95Rule('a-p95', 'FAIL', 2340),
        { ...p95Rule('gone', 'NO_VERDICT', 0), reason_code: 'TRANSACTION_NOT_FOUND' },
        resourceCheck('mem-limit', 'NO_VERDICT', 'sla', 'MISSING_RESOURCE_CELLS'),
      ],
      findings: [violation('mem-limit')],
    }))

    expect(items.map((item) => `${item.kind}:${item.target?.targetId}`)).toEqual([
      'violation:policy-results',
      'violation:resource-results',
      'no_verdict:policy-results',
      'no_verdict:resource-results',
    ])
    const cause = items.find((item) => item.key === 'no_verdict:TRANSACTION_NOT_FOUND|Правила')
    expect(cause?.title).toContain('Транзакция')
    expect(cause?.detail).toBe(OVERVIEW_LABELS.causeSubjects('Правила', ['gone'], 0))
  })

  test('capacity causes point to the capacity table', () => {
    const items = attentionItems(build({
      policy_verdict: 'NO_VERDICT',
      analysis_mode: 'capacity_step',
      analysis_coverage: { status: 'INCOMPLETE', reasons: ['CAPACITY_STAGE_NOT_VERIFIED'] },
      capacity_summary: {
        schema_version: 'capacity.v1', load_axis: 'rps', unit: 'rps', stages: [], bound_type: 'LOWER', lower_inclusive: 100, upper_exclusive: null,
        policy_verdict: 'NO_VERDICT', reasons: ['CAPACITY_STAGE_NOT_VERIFIED'], capacity_knee: null, knee_reason: 'none',
      },
    }))

    expect(items.map((item) => item.kind)).toEqual(['no_verdict'])
    expect(items[0].target).toEqual({ tab: 'tables', targetId: 'capacity-results' })
  })

  test('incomplete coverage and a partly parsed file are reported, complete coverage is not', () => {
    const incomplete = attentionItems(build({
      policy_verdict: 'FAIL', run_validity: 'DEGRADED',
      analysis_coverage: { status: 'INCOMPLETE', reasons: ['SOURCE_ACQUISITION_PARTIAL', 'RESOURCE_GAPS'] },
      evidence: [overall, checkout, p95Rule('a-p95', 'FAIL', 2340)],
    }))

    expect(incomplete.filter((item) => item.kind === 'coverage').map((item) => item.key)).toEqual([
      'coverage:validity', 'coverage:SOURCE_ACQUISITION_PARTIAL', 'coverage:RESOURCE_GAPS',
    ])
    expect(incomplete.find((item) => item.key === 'coverage:validity')?.title).toBe(OVERVIEW_LABELS.validityDegraded)
    expect(incomplete.find((item) => item.key === 'coverage:SOURCE_ACQUISITION_PARTIAL')?.target).toEqual({ tab: 'tables', targetId: 'source-acquisition' })
    expect(incomplete.find((item) => item.key === 'coverage:RESOURCE_GAPS')?.target).toEqual({ tab: 'tables', targetId: 'resource-results' })
    expect(kinds(build({ policy_verdict: 'PASS', evidence: [overall, checkout, p95Rule('ok', 'PASS', 100)] }))).not.toContain('coverage')
  })

  test('series without values are counted only when an SLA rule is bound to them', () => {
    const items = attentionItems(build({
      policy_verdict: 'PASS',
      evidence: [
        overall, resourceCheck('mem-limit', 'PASS', 'sla'),
        summary('mem-b', ['RESOURCE_GAPS', 'NO_OBSERVATIONS']),
        summary('cpu-unbound', ['NO_OBSERVATIONS']),
        summary('mem-gaps', ['RESOURCE_GAPS']),
        { ...summary('gen-cpu', ['NO_OBSERVATIONS']), role: 'generator' },
        { ...resourceCheck('gen-guard', 'PASS', 'sla'), series_id: 'gen-cpu' },
      ],
    }))

    const series = items.filter((item) => item.key === 'coverage:resource-series')
    expect(series).toHaveLength(1)
    expect(series[0].title).toBe(OVERVIEW_LABELS.resourceSeriesTitle(1, 1))
    expect(series[0].detail).toBe(OVERVIEW_LABELS.resourceSeriesDetail(['mem-b'], 0))
    expect(series[0].target).toEqual({ tab: 'tables', targetId: 'resource-results' })
  })

  test('only observed trends and candidate correlations are diagnostics, marked as such and counted once', () => {
    const items = attentionItems(build({
      policy_verdict: 'PASS',
      evidence: [
        overall, checkout, p95Rule('ok', 'PASS', 100),
        trend('a', 'TREND_OBSERVED'), trend('b', 'NO_MATERIAL_TREND', { observed_direction: 'flat' }), trend('c', 'INSUFFICIENT_CELLS', { observed_direction: null }),
        correlation('p1', 'CANDIDATE'), correlation('p2', 'DESCRIPTIVE'), correlation('p3', 'CANDIDATE', null),
      ],
      findings: [{ type: 'resource_trend', check_id: 'a', series_id: 'cpu-a', evidence_id: 't-a' }],
    }))

    expect(items.map((item) => item.key)).toEqual(['diagnostic:trend:t-a', 'diagnostic:correlation:cp-p1'])
    expect(items.every((item) => item.kind === 'diagnostic' && item.diagnostic)).toBe(true)
    expect(items[0].title).toBe(OVERVIEW_LABELS.trendTitle('cpu-a'))
    expect(items[0].detail).toBe(OVERVIEW_LABELS.trendDetail('Soak', 'increase', '5,4', '69,3', '%'))
    expect(items[0].target).toEqual({ tab: 'tables', targetId: 'trend-results' })
    expect(items[1].title).toBe(OVERVIEW_LABELS.correlationTitle('mem-b', 'throughput_rps'))
    expect(items[1].detail).toBe(OVERVIEW_LABELS.correlationDetail('Soak', '0,83', 9, 10))
    expect(items[1].target).toEqual({ tab: 'tables', targetId: 'diagnostic-results' })
  })

  test('the order is violations, causes, policy, coverage, diagnostics', () => {
    const items = attentionItems(build({
      policy_verdict: 'NO_VERDICT',
      analysis_coverage: { status: 'INCOMPLETE', reasons: ['TRANSACTION_NOT_FOUND', 'SOURCE_ACQUISITION_FAILED'] },
      evidence: [
        trend('a', 'TREND_OBSERVED'), overall, checkout, p95Rule('a-p95', 'FAIL', 2340),
        { ...p95Rule('gone', 'NO_VERDICT', 0), reason_code: 'TRANSACTION_NOT_FOUND' },
      ],
    }))

    expect(items.map((item) => item.kind)).toEqual(['violation', 'no_verdict', 'coverage', 'diagnostic'])
  })
})

test.describe('key metrics', () => {
  test('come from the overall summary with the same formulas as the tables', () => {
    const tiles = keyMetrics(build({ evidence: [checkout, overall] }))

    expect(tiles.map((tile) => tile.key)).toEqual(['rps', 'p95', 'p99', 'max'])
    expect(tiles.map((tile) => tile.raw)).toEqual(['121.67', '2340', '3000.5', '4000'])
    expect(tiles[0].label).toBe(OVERVIEW_LABELS.metricRps)
    expect(tiles.map((tile) => tile.value.replace(/\s+/g, ' '))).toEqual(['121,67 RPS', '2 340 мс', '3 000,5 мс', '4 000 мс'])
  })

  test('are empty without an overall summary and skip an undefined throughput', () => {
    expect(keyMetrics(build({ evidence: [checkout] }))).toEqual([])
    const zero = { ...overall, throughput_rps: { numerator: 0, denominator: 0 } }
    expect(keyMetrics(build({ evidence: [zero] })).map((tile) => tile.key)).toEqual(['p95', 'p99', 'max'])
  })
})

const bucket = (start: number, samples: number, errors: number, p95: number): Bucket => ({
  bucket_start_ms: start, sample_count: samples, error_count: errors, p95_latency_ms: p95, max_latency_ms: p95 + 10, hdr_v2_base64: 'AAAA',
})

test.describe('load series', () => {
  test('divides samples by the rollup of the fetched page, not by the current select value', () => {
    const series = loadSeries([bucket(0, 100, 2, 300), bucket(10_000, 50, 0, 250)], 10)

    expect(series.points).toEqual([
      { startMs: 0, rps: 10, errors: 2, p95: 300 },
      { startMs: 10_000, rps: 5, errors: 0, p95: 250 },
    ])
    expect(series.max).toEqual({ rps: 10, errors: 2, p95: 300 })
    expect(series.segments).toEqual([[0, 1]])
    expect(series.missingIntervals).toBe(0)
  })

  test('sorts buckets and never joins the lines across a missing interval', () => {
    const series = loadSeries([bucket(4_000, 10, 0, 100), bucket(0, 10, 0, 100), bucket(1_000, 10, 0, 100), bucket(2_000, 10, 0, 100)], 1)

    expect(series.points.map((point) => point.startMs)).toEqual([0, 1000, 2000, 4000])
    expect(series.segments).toEqual([[0, 1, 2], [3]])
    expect(series.missingIntervals).toBe(1)
    expect(trackPoints(series, 'rps', 100, 50)).toHaveLength(2)
  })

  test('an empty page gives an empty model', () => {
    const series = loadSeries([], 1)

    expect(series.points).toEqual([])
    expect(series.segments).toEqual([])
    expect(series.missingIntervals).toBe(0)
    expect(nearestIndex(series, 0.5)).toBe(-1)
  })

  test('places points on a shared time fraction and finds the nearest one', () => {
    const series = loadSeries([bucket(0, 10, 0, 100), bucket(1_000, 10, 0, 100), bucket(3_000, 10, 0, 100), bucket(4_000, 10, 0, 100)], 1)

    expect([0, 1, 2, 3].map((index) => cursorFraction(series, index))).toEqual([0, 0.25, 0.75, 1])
    expect(nearestIndex(series, 0)).toBe(0)
    expect(nearestIndex(series, 0.4)).toBe(1)
    expect(nearestIndex(series, 0.6)).toBe(2)
    expect(nearestIndex(series, 1.7)).toBe(3)
    expect(nearestIndex(series, -3)).toBe(0)
    expect(cursorFraction(loadSeries([bucket(500, 1, 0, 1)], 1), 0)).toBe(0.5)
  })

  test('scales each track by its own maximum inside the padded frame', () => {
    const series = loadSeries([bucket(0, 100, 0, 200), bucket(1_000, 50, 4, 400)], 1)

    expect(trackPoints(series, 'rps', 100, 50)).toEqual(['0,4 100,25'])
    expect(trackPoints(series, 'errors', 100, 50)).toEqual(['0,46 100,4'])
    expect(trackPoints(series, 'p95', 100, 50)).toEqual(['0,25 100,4'])
  })

  test('formats offsets from run start and the cursor readout', () => {
    expect([0, 65_000, 3_725_000, 999].map(formatOffset)).toEqual(['0:00', '1:05', '1:02:05', '0:00'])
    const series = loadSeries([bucket(65_000, 1234, 7, 2340.5)], 1)
    const readout = cursorSummary(series, 0)

    expect(readout.raw).toEqual({ rps: '1234.00', errors: '7', p95: '2340.5' })
    expect(readout.time).toBe('1:05')
    expect(readout.text.replace(/\s+/g, ' ')).toBe(OVERVIEW_LABELS.cursorText('1:05', '1 234', '7', '2 340,5').replace(/\s+/g, ' '))
  })
})

// Серверная выдача страниц /buckets: файл шага rollup, до 500 интервалов на страницу, next_from_ms указывает на следующий интервал.
function serverFor(totalSeconds: number) {
  const requests: Array<{ rollup: number; from: number | undefined }> = []
  const fetchPage = async (rollup: 1 | 10 | 30 | 60, from: number | undefined): Promise<BucketPage> => {
    requests.push({ rollup, from })
    const width = rollup * 1000
    const all: Bucket[] = []
    for (let start = 0; start < totalSeconds * 1000; start += width) all.push(bucket(start, rollup, 0, 100))
    const rest = all.filter((item) => item.bucket_start_ms >= (from ?? 0))
    return { buckets: rest.slice(0, 500), next_from_ms: rest.length > 500 ? rest[500].bucket_start_ms : null }
  }
  return { fetchPage, requests }
}

test.describe('full run load', () => {
  test('a 4 hour run is read in full at 10 s: one probe at 60 s, then three pages', async () => {
    const server = serverFor(4 * 3600)

    const loaded = await fetchRunLoad(server.fetchPage)

    expect(server.requests).toEqual([{ rollup: 60, from: undefined }, { rollup: 10, from: undefined }, { rollup: 10, from: 5_000_000 }, { rollup: 10, from: 10_000_000 }])
    expect(loaded.rollupSeconds).toBe(10)
    expect(loaded.truncated).toBe(false)
    expect(loaded.buckets).toHaveLength(1440)
    expect(loaded.buckets.at(-1)?.bucket_start_ms).toBe(14_390_000)
    const series = loadSeries(loaded.buckets, loaded.rollupSeconds)
    expect(series.missingIntervals).toBe(0)
    expect(series.segments).toHaveLength(1)
  })

  test('an 8 hour run uses 30 s and a short run keeps 1 s', async () => {
    const long = await fetchRunLoad(serverFor(8 * 3600).fetchPage)
    const short = await fetchRunLoad(serverFor(20 * 60).fetchPage)

    expect([long.rollupSeconds, long.buckets.length, long.truncated]).toEqual([30, 960, false])
    expect([short.rollupSeconds, short.buckets.length, short.truncated]).toEqual([1, 1200, false])
  })

  test('an empty run costs one request and gives no buckets', async () => {
    const server = serverFor(0)

    const loaded = await fetchRunLoad(server.fetchPage)

    expect(loaded.buckets).toEqual([])
    expect(loaded.truncated).toBe(false)
    expect(server.requests).toHaveLength(1)
  })

  test('a run longer than the page limit at 60 s reuses the probe and reports truncation', async () => {
    const server = serverFor(200 * 3600)

    const loaded = await fetchRunLoad(server.fetchPage)

    expect(loaded.rollupSeconds).toBe(60)
    expect(loaded.truncated).toBe(true)
    expect(loaded.buckets).toHaveLength(MAX_LOAD_PAGES * 500)
    expect(server.requests).toHaveLength(MAX_LOAD_PAGES)
    expect(server.requests.every((entry) => entry.rollup === 60)).toBe(true)
  })

  test('stops when the server does not advance next_from_ms', async () => {
    const stuck = async (): Promise<BucketPage> => ({ buckets: [bucket(0, 1, 0, 1)], next_from_ms: 0 })

    const loaded = await fetchRunLoad(stuck)

    expect(loaded.truncated).toBe(true)
    expect(loaded.buckets).toHaveLength(1)
  })
})
