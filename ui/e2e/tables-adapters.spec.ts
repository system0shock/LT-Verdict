import { expect, test } from '@playwright/test'
import type { AnalysisResult, CapacitySummary } from '../src/types'
import { capacityView, DEFAULT_TX_QUERY, queryTransactions, ruleRows, transactionRows, trendView } from '../src/shell/tables'
import { CAPACITY_LABELS, TABLES_LABELS, TREND_LABELS } from '../src/shell/labels.tables'
import { reasonText } from '../src/verdictReasons'

// Чистые адаптеры таблиц «Правила» и «Транзакции»: результат анализа на входе, готовые строки на выходе.
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
const transaction = (id: string, label: string, over: Record<string, unknown> = {}) => ({
  id, type: 'metric_summary', scope: { kind: 'transaction', group_path: [], label, sample_kind: 'JMETER_SAMPLER' },
  sample_count: 900, error_count: 0, error_rate_ratio: { numerator: 0, denominator: 900 }, throughput_rps: { numerator: 900000, denominator: 30000 },
  latency_ms: { p50: 100, p95: 2340, p99: 3000, max: 4000 }, ...over,
})
const checkout = transaction('m-checkout', 'POST /checkout')
const login = transaction('m-login', 'POST /login', { sample_count: 100, latency_ms: { p50: 10, p95: 20, p99: 30, max: 40 } })
const check = (over: Record<string, unknown>) => ({
  id: 'c1', type: 'policy_check', rule_id: 'checkout-p95', metric: 'response_time_p95_ms', operator: 'lte',
  threshold: 2000, status: 'FAIL', metric_evidence_id: 'm-checkout', observed: 2340, ...over,
})
const flat = (text: string) => text.replace(/\s/g, ' ')

test('a failing rule keeps its id, units and operator words', () => {
  const [row] = ruleRows(build({ policy_verdict: 'FAIL', evidence: [overall, checkout, check({})] }))

  expect(row).toMatchObject({ key: 'c1', ruleId: 'checkout-p95', condition: TABLES_LABELS.conditionLte, status: 'FAIL', reasonCode: null, window: null })
  expect(row.scope).toBe('POST /checkout')
  expect(row.metric).toBe('p95 отклика')
  expect(flat(row.threshold)).toBe('2 000 мс')
  expect(flat(row.observed)).toBe('2 340 мс')
})

test('a gte rule on the whole run uses the other operator word and units', () => {
  const [row] = ruleRows(build({ evidence: [overall, check({ rule_id: 'rps-min', metric: 'throughput_rps', operator: 'gte', threshold: 100, status: 'PASS', metric_evidence_id: 'm-overall', observed: { numerator: 3650000, denominator: 30000 }, window_id: 'steady-1' })] }))

  expect(row.condition).toBe(TABLES_LABELS.conditionGte)
  expect(row.scope).toBe(TABLES_LABELS.scopeOverall)
  expect(flat(row.threshold)).toBe('100 RPS')
  expect(row.window).toBe('steady-1')
})

test('two rules on the same metric stay distinguishable by rule id', () => {
  const rows = ruleRows(build({ evidence: [overall, checkout, check({ id: 'c1', rule_id: 'a' }), check({ id: 'c2', rule_id: 'b' })] }))

  expect(rows.map((row) => row.ruleId)).toEqual(['a', 'b'])
  expect(new Set(rows.map((row) => row.key)).size).toBe(2)
})

test('a NO_VERDICT rule shows its reason in words and the raw code', () => {
  const [row] = ruleRows(build({ evidence: [overall, check({ status: 'NO_VERDICT', observed: undefined, reason_code: 'TRANSACTION_NOT_FOUND', metric_evidence_id: undefined })] }))

  expect(row.observed).toBe(TABLES_LABELS.noData)
  expect(row.scope).toBe(TABLES_LABELS.scopeMissing)
  expect(row.reasonCode).toBe('TRANSACTION_NOT_FOUND')
  expect(row.reasonText).toContain('Транзакция')
})

test('an unknown reason code keeps the code and gets the neutral text', () => {
  const [row] = ruleRows(build({ evidence: [check({ status: 'NO_VERDICT', reason_code: 'SOMETHING_NEW' })] }))

  expect(row.reasonCode).toBe('SOMETHING_NEW')
  expect(row.reasonText).toBe('Причина без расшифровки в этой версии интерфейса.')
})

test('an unknown rule window is a NO_VERDICT row with the rule, the window and the reason', () => {
  const rows = ruleRows(build({ evidence: [overall, check({}), { id: 'u1', type: 'rule_window_check', rule_id: 'cpu', window_id: 'ghost', status: 'NO_VERDICT', reason_code: 'RULE_WINDOW_NOT_FOUND' }] }))

  expect(rows.map((row) => row.key)).toEqual(['c1', 'u1'])
  expect(rows[1]).toMatchObject({ ruleId: 'cpu', window: 'ghost', status: 'NO_VERDICT', reasonCode: 'RULE_WINDOW_NOT_FOUND', reasonText: reasonText('RULE_WINDOW_NOT_FOUND') })
  expect(JSON.stringify(rows[1])).not.toMatch(/NaN|undefined|Infinity/)
})

test('rounding never turns a violation into equality', () => {
  const [row] = ruleRows(build({ evidence: [check({ threshold: 2000, observed: 2000.004, metric_evidence_id: undefined })] }))
  const digits = (value: string) => value.replace(/[^\d,]/g, '')

  expect(digits(row.observed)).not.toBe(digits(row.threshold))
})

test('no policy checks give no rule rows; a null latency stays readable', () => {
  expect(ruleRows(build({ evidence: [overall] }))).toEqual([])
  const rows = transactionRows(build({ evidence: [{ ...checkout, latency_ms: { p50: null, p95: null, p99: null, max: null }, error_rate_ratio: null }] }))

  expect(rows[0].p95).toBe(TABLES_LABELS.noData)
  expect(rows[0].errorRate).toBe(TABLES_LABELS.noData)
  expect(Object.values(rows[0].sort).every(Number.isFinite)).toBe(true)
  expect(JSON.stringify(rows)).not.toMatch(/NaN|undefined|Infinity/)
})

test('transactions keep the statuses of the old table and stay unique by id', () => {
  const nested = transaction('m-nested', 'POST /checkout', { scope: { kind: 'transaction', group_path: ['flow'], label: 'POST /checkout', sample_kind: 'JMETER_SAMPLER' } })
  const failing = transactionRows(build({ policy_verdict: 'FAIL', evidence: [overall, checkout, login, nested, check({}), check({ id: 'c2', rule_id: 'l', status: 'PASS', metric_evidence_id: 'm-login' })] }))

  expect(failing.map((row) => [row.key, row.path, row.status])).toEqual([
    ['m-checkout', '', 'FAIL'], ['m-login', '', 'PASS'], ['m-nested', 'flow', 'NOT_CHECKED'],
  ])
  expect(transactionRows(build({ evidence: [overall, checkout] }))[0].status).toBe('NO_POLICY')
  expect(transactionRows(build({ policy_verdict: 'PASS', evidence: [overall, checkout] }))[0].status).toBe('NOT_CHECKED')
  expect(failing.every((row) => !row.key.includes('overall'))).toBe(true)
})

test('the sample cell says how many requests the rule saw and what that means', () => {
  const rows = ruleRows(build({
    evidence: [
      overall, checkout,
      check({ id: 'c1', rule_id: 'full', status: 'PASS', sample_count: 1500, sample_floor: 20, min_samples: 50, sample_mode: 'FULL' }),
      check({ id: 'c2', rule_id: 'small', status: 'PASS', sample_count: 30, sample_floor: 20, min_samples: 50, sample_mode: 'SMALL_SAMPLE' }),
      check({ id: 'c3', rule_id: 'few', status: 'NO_VERDICT', observed: undefined, reason_code: 'INSUFFICIENT_SAMPLES', sample_count: 12, sample_floor: 20, min_samples: 100, sample_mode: 'INSUFFICIENT' }),
      check({ id: 'c4', rule_id: 'rps', status: 'PASS', sample_count: 3650, sample_mode: 'NOT_GATED' }),
      check({ id: 'c5', rule_id: 'legacy', status: 'PASS' }),
      check({ id: 'c6', rule_id: 'future', status: 'PASS', sample_count: 7, min_samples: 9, sample_mode: 'SOMETHING_NEW' }),
    ],
  }))

  expect(rows.map((row) => flat(row.sample))).toEqual([
    '1 500 из 50 · достаточно',
    '30 из 50 · малая выборка',
    '12 из 20 · недостаточно',
    '3 650 · без порога',
    TABLES_LABELS.noSample,
    '7 из 9 · SOMETHING_NEW',
  ])
  expect(JSON.stringify(rows)).not.toMatch(/NaN|undefined/)
})

test('a windowed check without a metric reference still marks its own transaction only', () => {
  const nested = transaction('m-nested', 'POST /checkout', { scope: { kind: 'transaction', group_path: ['flow'], label: 'POST /checkout', sample_kind: 'JMETER_SAMPLER' } })
  const rows = transactionRows(build({
    policy_verdict: 'FAIL',
    evidence: [overall, checkout, nested, check({ id: 'w1', metric_evidence_id: undefined, window_id: 'steady-1', scope: checkout.scope })],
  }))

  expect(rows.map((row) => [row.key, row.status])).toEqual([['m-checkout', 'FAIL'], ['m-nested', 'NOT_CHECKED']])
})

test('search, status filter and impact order', () => {
  const rows = transactionRows(build({ policy_verdict: 'FAIL', evidence: [overall, login, checkout, check({}), check({ id: 'c2', rule_id: 'l', status: 'PASS', metric_evidence_id: 'm-login' })] }))

  expect(queryTransactions(rows, DEFAULT_TX_QUERY).map((row) => row.label)).toEqual(['POST /checkout', 'POST /login'])
  expect(queryTransactions(rows, { ...DEFAULT_TX_QUERY, text: 'LOGIN' }).map((row) => row.label)).toEqual(['POST /login'])
  expect(queryTransactions(rows, { ...DEFAULT_TX_QUERY, status: 'PASS' }).map((row) => row.label)).toEqual(['POST /login'])
  expect(queryTransactions(rows, { ...DEFAULT_TX_QUERY, sort: 'label', dir: -1 }).map((row) => row.label)).toEqual(['POST /login', 'POST /checkout'])
  expect(queryTransactions(rows, { ...DEFAULT_TX_QUERY, sort: 'samples', dir: 1 }).map((row) => row.label)).toEqual(['POST /login', 'POST /checkout'])
  expect(queryTransactions(rows, { ...DEFAULT_TX_QUERY, text: 'nothing like this' })).toEqual([])
})

const capacitySummary: CapacitySummary = {
  schema_version: 'capacity.v1', load_axis: 'rps', unit: 'requests/s', bound_type: 'BOUNDED', lower_inclusive: 296, upper_exclusive: 344,
  policy_verdict: 'NO_VERDICT', reasons: [], capacity_knee: null, knee_reason: 'KNEE_DETECTOR_NOT_IMPLEMENTED',
  stages: [
    { id: 'ramp-300', target: 300, achieved: 296, achieved_statistic: 'p05_10s', observed_min: 295, observed_max: 301, complete_bins: 30, expected_bins: 30, target_tolerance_ratio: 0.02, verified_bound_load: 296, verdict: 'PASS', reasons: [], evidence_refs: ['ref-1'] },
    { id: 'ramp-350', target: 350, achieved: 344, achieved_statistic: 'p05_10s', observed_min: 340, observed_max: 345, complete_bins: 30, expected_bins: 30, target_tolerance_ratio: 0.02, verified_bound_load: 344, verdict: 'FAIL', reasons: ['CAPACITY_INSUFFICIENT_SAMPLES'], evidence_refs: [] },
    { id: 'ramp-400', target: 400, achieved: null, achieved_statistic: 'p05_10s', observed_min: null, observed_max: null, complete_bins: null, expected_bins: null, target_tolerance_ratio: 0.02, verified_bound_load: null, verdict: 'INDETERMINATE', reasons: ['CAPACITY_TARGET_MISSED'], evidence_refs: [] },
  ],
}

test('capacity stages keep verdicts, reason words and missing values', () => {
  const view = capacityView(build({ analysis_mode: 'capacity_step', capacity_summary: capacitySummary }))!
  expect(view.stages.map((stage) => stage.verdict)).toEqual(['PASS', 'FAIL', 'INDETERMINATE'])
  expect(view.stages.map((stage) => stage.verdictText)).toEqual([CAPACITY_LABELS.stageVerdict.PASS, CAPACITY_LABELS.stageVerdict.FAIL, CAPACITY_LABELS.stageVerdict.INDETERMINATE])
  expect(view.stages[2].reasons[0]).toEqual({ code: 'CAPACITY_TARGET_MISSED', text: reasonText('CAPACITY_TARGET_MISSED') })
  expect(view.stages[2].verified).toBe(TABLES_LABELS.noData)
  expect(view.stages[2].observed).toBe(`${TABLES_LABELS.noData} / ${TABLES_LABELS.noData}`)
  expect(view.stages[1].smallSample).toBe(true)
  expect(view.stages[0].smallSample).toBe(false)
  expect(view.smallSample).toBe(true)
  expect(view.boundText).toBe(CAPACITY_LABELS.boundText('BOUNDED', '296', '344', 'requests/s'))
  expect(view.kneeText).toBe(CAPACITY_LABELS.kneeNotImplemented)
  expect(view.verdictText).toBe(TABLES_LABELS.statusText.NO_VERDICT)
  expect(JSON.stringify(view)).not.toMatch(/null|undefined|NaN|Infinity/)
  expect(capacityView(build({}))).toBeNull()
})

test('capacity numbers keep every digit the server sent', () => {
  const view = capacityView(build({ capacity_summary: { ...capacitySummary, lower_inclusive: '1.0000001', upper_exclusive: '1.0000002', capacity_knee: '1234.50', stages: [] } }))!
  expect(view.boundText).toBe(CAPACITY_LABELS.boundText('BOUNDED', '1,0000001', '1,0000002', 'requests/s'))
  expect(view.kneeText.replace(/\s/g, ' ')).toBe(CAPACITY_LABELS.kneeValue('1 234,5', 'requests/s'))
})

test('capacity bound and knee variants retain unknown codes', () => {
  for (const bound of ['UPPER_BOUND', 'LOWER_BOUND', 'INDETERMINATE', 'SOMETHING_NEW']) {
    const view = capacityView(build({ capacity_summary: { ...capacitySummary, bound_type: bound, lower_inclusive: '1.25', upper_exclusive: '2.5', stages: [] } }))!
    expect(view.boundText).toBe(CAPACITY_LABELS.boundText(bound, '1,25', '2,5', 'requests/s'))
  }
  const unknown = capacityView(build({ capacity_summary: { ...capacitySummary, reasons: ['UNKNOWN_REASON'], stages: [{ ...capacitySummary.stages[0], verdict: 'WEIRD', reasons: ['UNKNOWN_REASON'] }], capacity_knee: null, knee_reason: 'UNKNOWN_KNEE' } }))!
  expect(unknown.stages[0].verdictText).toBe('WEIRD')
  expect(unknown.reasons).toEqual([{ code: 'UNKNOWN_REASON', text: reasonText('UNKNOWN_REASON') }])
  expect(unknown.kneeText).toBe(CAPACITY_LABELS.kneeNone('UNKNOWN_KNEE'))
  expect(capacityView(build({ capacity_summary: { ...capacitySummary, capacity_knee: '12.5' } }))?.kneeText).toBe(CAPACITY_LABELS.kneeValue('12,5', 'requests/s'))
  expect(capacityView(build({ capacity_summary: { ...capacitySummary, reasons: ['CAPACITY_INSUFFICIENT_SAMPLES'], stages: [] } }))?.smallSample).toBe(true)
  expect(capacityView(build({ capacity_summary: { ...capacitySummary, stages: undefined, reasons: undefined } }))?.stages).toEqual([])
})

const trendCheck = {
  id: 'trend-1', type: 'trend_check', check_id: 'cpu-trend', series_id: 'host:cpu', metric: 'cpu', unit: 'percent', entity: 'host',
  window_id: 'steady', window_from_epoch_ms: null, window_to_epoch_ms: null, declared_direction: 'either', status: 'TREND_OBSERVED',
  min_cells: 10, expected_cells: 30, observed_cells: 29, missing_cells: 1, longest_gap_cells: 1,
  median: '41.25', slope_per_second: '0.0135', split_half_shift: '6.40',
  magnitude_gate: { min_slope_units_per_second: '0.001', min_split_half_shift_pct: '5', required_split_half_shift_units: '2.0625' },
  observed_direction: 'increase', method: 'slope-materiality.v1', uncertainty: 'NOT_ESTIMATED', reasons: ['TREND_MIN_CELLS_NOT_MET', 'UNKNOWN_REASON'],
}

test('trend adapter translates status, direction, decimals and reason words', () => {
  const view = trendView(build({ evidence: [trendCheck] }))!
  expect(view.summaryText).toBe(TREND_LABELS.summary(1, 1, 0, 0, 0))
  expect(view.rows[0]).toMatchObject({ key: 'trend-1', check: 'cpu-trend', declared: TREND_LABELS.declaredText.either, status: 'TREND_OBSERVED', statusText: TREND_LABELS.statusText.TREND_OBSERVED, observed: TREND_LABELS.observedText.increase, slope: `0,0135 percent/\u0441`, shift: '6,40 percent', median: '41,25 percent', required: '2,0625 percent', cells: '29 / 30' })
  expect(view.rows[0].reasons).toEqual([
    { code: 'TREND_MIN_CELLS_NOT_MET', text: TREND_LABELS.reasonWords.TREND_MIN_CELLS_NOT_MET },
    { code: 'UNKNOWN_REASON', text: reasonText('UNKNOWN_REASON') },
  ])
})

test('trend missing values and summary-only evidence stay readable', () => {
  const sparse = { ...trendCheck, id: 'trend-2', status: 'INSUFFICIENT_CELLS', declared_direction: 'decrease', observed_direction: null, slope_per_second: null, split_half_shift: null, median: null, magnitude_gate: { ...trendCheck.magnitude_gate, required_split_half_shift_units: null }, unit: null, reasons: ['RESOURCE_GAPS'] }
  const view = trendView(build({ evidence: [sparse] }))!
  expect(view.summaryText).toBe(TREND_LABELS.summary(1, 0, 0, 1, 0))
  expect(view.rows[0]).toMatchObject({ declared: TREND_LABELS.declaredText.decrease, statusText: TREND_LABELS.statusText.INSUFFICIENT_CELLS, observed: TREND_LABELS.noData, slope: TREND_LABELS.noData, shift: TREND_LABELS.noData, median: TREND_LABELS.noData, required: TREND_LABELS.noData })
  expect(view.rows[0].reasons).toEqual([{ code: 'RESOURCE_GAPS', text: reasonText('RESOURCE_GAPS') }])
  expect(JSON.stringify(view)).not.toMatch(/null|undefined|NaN|Infinity/)
  const summary = { id: 'trend-summary', type: 'trend_summary', checks_total: 4, observed: 1, not_material: 1, insufficient: 1, unavailable: 1, method: 'slope-materiality.v1', uncertainty: 'NOT_ESTIMATED' }
  expect(trendView(build({ evidence: [summary] }))).toEqual({ summaryText: TREND_LABELS.summary(4, 1, 1, 1, 1), rows: [] })
  expect(trendView(build({}))).toBeNull()
})
