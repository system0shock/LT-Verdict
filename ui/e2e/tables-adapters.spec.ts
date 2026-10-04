import { expect, test } from '@playwright/test'
import type { AnalysisResult } from '../src/types'
import { DEFAULT_TX_QUERY, queryTransactions, ruleRows, transactionRows } from '../src/shell/tables'
import { TABLES_LABELS } from '../src/shell/labels.tables'

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

test('search, status filter and impact order', () => {
  const rows = transactionRows(build({ policy_verdict: 'FAIL', evidence: [overall, login, checkout, check({}), check({ id: 'c2', rule_id: 'l', status: 'PASS', metric_evidence_id: 'm-login' })] }))

  expect(queryTransactions(rows, DEFAULT_TX_QUERY).map((row) => row.label)).toEqual(['POST /checkout', 'POST /login'])
  expect(queryTransactions(rows, { ...DEFAULT_TX_QUERY, text: 'LOGIN' }).map((row) => row.label)).toEqual(['POST /login'])
  expect(queryTransactions(rows, { ...DEFAULT_TX_QUERY, status: 'PASS' }).map((row) => row.label)).toEqual(['POST /login'])
  expect(queryTransactions(rows, { ...DEFAULT_TX_QUERY, sort: 'label', dir: -1 }).map((row) => row.label)).toEqual(['POST /login', 'POST /checkout'])
  expect(queryTransactions(rows, { ...DEFAULT_TX_QUERY, sort: 'samples', dir: 1 }).map((row) => row.label)).toEqual(['POST /login', 'POST /checkout'])
  expect(queryTransactions(rows, { ...DEFAULT_TX_QUERY, text: 'nothing like this' })).toEqual([])
})
