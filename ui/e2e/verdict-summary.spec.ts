import { expect, test } from '@playwright/test'
import { readdirSync, readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import type { AnalysisResult } from '../src/types'
import { REASONS, isNoVerdictReason, reasonText } from '../src/verdictReasons'
import { MAX_LINES, MAX_SUBJECTS, summarizeVerdict } from '../src/verdictSummary'

// Собирает результат из произвольных фрагментов; типы доказательств проверяет ядро, а не этот тест.
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
  id: 'm-overall', type: 'metric_summary', scope: { kind: 'overall' }, sample_count: 12345, error_count: 300,
  error_rate_ratio: { numerator: 300, denominator: 12345 }, throughput_rps: { numerator: 12345000, denominator: 90000 },
  latency_ms: { p50: 100, p95: 200, p99: 300, max: 400 },
}
const checkout = { id: 'm-checkout', type: 'metric_summary', scope: { kind: 'transaction', group_path: [], label: 'POST /checkout', sample_kind: 'JMETER_SAMPLER' }, sample_count: 10, error_count: 0, error_rate_ratio: null, throughput_rps: { numerator: 1, denominator: 1 }, latency_ms: { p50: 1, p95: 1, p99: 1, max: 1 } }
const p95Rule = (id: string, status: string, observed: number, extra: Record<string, unknown> = {}) => ({
  id: `check-${id}`, type: 'policy_check', rule_id: id, metric: 'response_time_p95_ms', operator: 'lte', threshold: 2000,
  status, metric_evidence_id: 'm-checkout', observed, ...extra,
})
const errorRule = { id: 'check-errors', type: 'policy_check', rule_id: 'overall-errors', metric: 'error_rate_ratio', operator: 'lte', threshold: 0.01, status: 'FAIL', metric_evidence_id: 'm-overall', observed: { numerator: 24, denominator: 1000 } }
const flat = (text: string) => text.replace(/\s+/g, ' ')

test.describe('verdict summary', () => {
  test('FAIL names the broken rules with value and threshold', () => {
    const summary = summarizeVerdict(build({ policy_verdict: 'FAIL', evidence: [overall, checkout, p95Rule('checkout-p95', 'FAIL', 2340), errorRule, p95Rule('ok-rule', 'PASS', 100)] }))

    expect(summary.headline).toBe('Прогон не проходит — нарушено проверок: 2 из 3')
    expect(summary.chip).toBe('нарушено 2 из 3')
    expect(summary.linesTitle).toBe('Что нарушено')
    expect(summary.lines.map((line) => flat(`${line.title}: ${line.detail}`))).toEqual([
      'Правило checkout-p95 · p95 отклика · POST /checkout: 2 340 мс при пороге ≤ 2 000 мс',
      'Правило overall-errors · доля ошибок · весь прогон: 2,4 % при пороге ≤ 1 %',
    ])
    expect(summary.linesHidden).toBe(0)
    expect(summary.causes).toEqual([])
    expect(summary.facts.map((fact) => `${fact.label}=${flat(fact.value)}`)).toEqual([
      'Валидность прогона=VALID', 'Полнота данных=COMPLETE', 'Длительность=1,5 мин', 'Запросов=12 345', 'Доля ошибок=2,43 %', 'Проверок=3',
    ])
  })

  test('a FAIL never looks like equality after rounding', () => {
    const tightErrors = { ...errorRule, observed: { numerator: 1001, denominator: 100000 } }
    const summary = summarizeVerdict(build({
      policy_verdict: 'FAIL',
      evidence: [overall, checkout, tightErrors, p95Rule('tight-p95', 'FAIL', 101, { threshold: 100.999 })],
    }))

    expect(summary.lines.map((line) => flat(line.detail))).toEqual([
      '1,001 % при пороге ≤ 1 %',
      '101 мс при пороге ≤ 100,999 мс',
    ])
  })

  test('FAIL shows at most three rules and counts the rest', () => {
    const rules = ['a', 'b', 'c', 'd', 'e'].map((id) => p95Rule(id, 'FAIL', 3000))
    const summary = summarizeVerdict(build({ policy_verdict: 'FAIL', evidence: [checkout, ...rules] }))

    expect(summary.lines).toHaveLength(MAX_LINES)
    expect(summary.linesHidden).toBe(2)
  })

  test('PASS lists checked rules without inventing a reason', () => {
    const summary = summarizeVerdict(build({ policy_verdict: 'PASS', evidence: [overall, checkout, p95Rule('checkout-p95', 'PASS', 1500)] }))

    expect(summary.headline).toBe('Прогон проходит — нарушений нет, проверок: 1')
    expect(summary.linesTitle).toBe('Что проверено')
    expect(flat(summary.lines[0].detail)).toBe('1 500 мс при пороге ≤ 2 000 мс')
    expect(summary.causes).toEqual([])
    expect(summary.notes).toEqual([])
  })

  test('PASS with only resource SLA rules states that no violation was found', () => {
    const summary = summarizeVerdict(build({
      policy_verdict: 'PASS',
      evidence: [{ id: 'r1', type: 'resource_policy_check', window_id: 'w', rule_id: 'cpu-limit', series_id: 'cpu', unit: 'ratio', operator: 'gt', threshold: '0.8', effect: 'sla', status: 'PASS', reason: null }],
    }))

    expect(summary.lines[0].title).toBe('Правило cpu-limit · ряд cpu · окно w')
    expect(summary.lines[0].detail).toBe('нарушений нет (нарушение: значение выше 0.8 ratio)')
  })

  test('resource FAIL uses violation findings and the violation operator', () => {
    const summary = summarizeVerdict(build({
      policy_verdict: 'FAIL',
      evidence: [{ id: 'r1', type: 'resource_policy_check', window_id: 'w', rule_id: 'cpu-limit', series_id: 'cpu', unit: 'ratio', operator: 'gt', threshold: '0.8', effect: 'sla', status: 'FAIL', reason: null }],
      findings: [
        { id: 'f1', type: 'resource_threshold_violation', window_id: 'w', rule_id: 'cpu-limit', series_id: 'cpu', entity: 'server-1', unit: 'ratio', from_epoch_ms: 1000, to_epoch_ms: 5000, cell_count: 4, observed_min: '0.85', observed_max: '0.9', evidence_id: 'r1' },
        { id: 'f2', type: 'resource_threshold_violation', window_id: 'w', rule_id: 'cpu-limit', series_id: 'cpu', entity: 'server-1', unit: 'ratio', from_epoch_ms: 9000, to_epoch_ms: 11000, cell_count: 2, observed_min: '0.9', observed_max: '0.9', evidence_id: 'r1' },
      ],
    }))

    expect(summary.lines[0].title).toBe('Правило cpu-limit · ряд cpu (server-1) · окно w')
    expect(summary.lines[0].detail).toBe('значение выше порога 0.8 ratio: наблюдалось 0.85–0.9; ячеек подряд: 4; 1970-01-01 00:00:01 UTC – 1970-01-01 00:00:05 UTC; интервалов нарушения: 2')
  })

  test('NO_POLICY says thresholds were not checked', () => {
    const summary = summarizeVerdict(build({ policy_verdict: 'NO_POLICY', evidence: [overall] }))

    expect(summary.headline).toBe('Вердикта нет — политика не задана')
    expect(summary.linesTitle).toBeNull()
    expect(summary.causes).toEqual([])
  })

  test('NO_VERDICT groups missing transactions by reason and keeps the code', () => {
    const summary = summarizeVerdict(build({
      policy_verdict: 'NO_VERDICT',
      analysis_coverage: { status: 'INCOMPLETE', reasons: ['TRANSACTION_NOT_FOUND'] },
      evidence: [overall, { id: 'c1', type: 'policy_check', rule_id: 'missing-p95', metric: 'response_time_p95_ms', operator: 'lte', threshold: 100, status: 'NO_VERDICT', reason_code: 'TRANSACTION_NOT_FOUND' }, { id: 'c2', type: 'policy_check', rule_id: 'missing-p99', metric: 'response_time_p99_ms', operator: 'lte', threshold: 100, status: 'NO_VERDICT', reason_code: 'TRANSACTION_NOT_FOUND' }],
    }))

    expect(summary.headline).toBe('Вердикт не выдан — не удалось проверить: 2 из 2')
    expect(summary.causes).toHaveLength(1)
    expect(summary.causes[0]).toMatchObject({ code: 'TRANSACTION_NOT_FOUND', subjectsLabel: 'Правила', subjects: ['missing-p95', 'missing-p99'], text: reasonText('TRANSACTION_NOT_FOUND') })
    expect(summary.notes).toEqual([])
  })

  test('a cause lists at most five rules and counts the rest', () => {
    const rules = ['a', 'b', 'c', 'd', 'e', 'f', 'g'].map((id) => ({ id: `c-${id}`, type: 'policy_check', rule_id: id, metric: 'response_time_p95_ms', operator: 'lte', threshold: 1, status: 'NO_VERDICT', reason_code: 'METRIC_NOT_AVAILABLE' }))
    const summary = summarizeVerdict(build({ policy_verdict: 'NO_VERDICT', evidence: rules }))

    expect(summary.causes).toHaveLength(1)
    expect(summary.causes[0].subjects).toEqual(['a', 'b', 'c', 'd', 'e'].slice(0, MAX_SUBJECTS))
    expect(summary.causes[0].subjectsHidden).toBe(2)
  })

  test('NO_VERDICT keeps an already found violation visible', () => {
    const summary = summarizeVerdict(build({
      policy_verdict: 'NO_VERDICT',
      analysis_coverage: { status: 'INCOMPLETE', reasons: ['MISSING_RESOURCE_CELLS', 'RESOURCE_GAPS', 'NO_OBSERVATIONS', 'INSUFFICIENT_OBSERVATIONS'] },
      evidence: [
        { id: 'r1', type: 'resource_policy_check', window_id: 'w', rule_id: 'cpu-limit', series_id: 'cpu', unit: 'ratio', operator: 'gt', threshold: '0.8', effect: 'sla', status: 'FAIL', reason: null },
        { id: 'r2', type: 'resource_policy_check', window_id: 'w', rule_id: 'memory-limit', series_id: 'memory', unit: 'ratio', operator: 'gt', threshold: '0.8', effect: 'sla', status: 'NO_VERDICT', reason: 'MISSING_RESOURCE_CELLS' },
        { id: 'r3', type: 'resource_policy_check', window_id: 'w', rule_id: 'diag-only', series_id: 'io', unit: 'ratio', operator: 'gt', threshold: '0.8', effect: 'diagnostic', status: 'NO_VERDICT', reason: 'RESOURCE_SERIES_NOT_FOUND' },
      ],
      findings: [{ id: 'f1', type: 'resource_threshold_violation', window_id: 'w', rule_id: 'cpu-limit', series_id: 'cpu', entity: 'server-1', unit: 'ratio', from_epoch_ms: 1000, to_epoch_ms: 5000, cell_count: 4, observed_min: '0.9', observed_max: '0.9', evidence_id: 'r1' }],
    }))

    expect(summary.linesTitle).toBe('Найденные нарушения')
    expect(summary.lines).toHaveLength(1)
    expect(summary.lead).toContain('Нарушения уже найдены')
    expect(summary.causes.map((cause) => cause.code)).toEqual(['MISSING_RESOURCE_CELLS'])
    expect(summary.causes[0].subjects).toEqual(['memory-limit (окно w)'])
    expect(summary.notes.map((note) => note.code)).toEqual(['RESOURCE_GAPS', 'NO_OBSERVATIONS', 'INSUFFICIENT_OBSERVATIONS'])
  })

  test('windows without requests are named only by rules over the whole run', () => {
    const noData = (id: string, window: string, scope: Record<string, unknown>) => ({ id: `c-${id}`, type: 'policy_check', rule_id: id, window_id: window, scope, metric: 'response_time_p95_ms', operator: 'lte', threshold: 100, status: 'NO_VERDICT', reason_code: 'METRIC_NOT_AVAILABLE' })
    const reasons = { status: 'INCOMPLETE', reasons: ['METRIC_NOT_AVAILABLE', 'BUSINESS_OBSERVATIONS_NOT_FOUND'] }
    const summary = summarizeVerdict(build({
      policy_verdict: 'NO_VERDICT',
      analysis_coverage: reasons,
      evidence: [noData('p95', 'steady', { kind: 'overall' }), noData('tx-p95', 'ramp', { kind: 'transaction', label: 'POST /checkout' })],
    }))

    expect(summary.causes.map((cause) => `${cause.code}:${cause.subjectsLabel}:${cause.subjects.join(',')}`)).toEqual([
      'METRIC_NOT_AVAILABLE:Правила:p95 (окно steady),tx-p95 (окно ramp)',
      'BUSINESS_OBSERVATIONS_NOT_FOUND:Окна:steady',
    ])

    const onlyTransaction = summarizeVerdict(build({
      policy_verdict: 'NO_VERDICT',
      analysis_coverage: reasons,
      evidence: [noData('tx-p95', 'ramp', { kind: 'transaction', label: 'POST /checkout' })],
    }))
    expect(onlyTransaction.causes.map((cause) => `${cause.code}:${cause.subjects.join(',')}`)).toEqual([
      'METRIC_NOT_AVAILABLE:tx-p95 (окно ramp)',
      'BUSINESS_OBSERVATIONS_NOT_FOUND:',
    ])
  })

  test('INVALID input explains the parser reason with its file offset', () => {
    const summary = summarizeVerdict(build({
      run_validity: 'INVALID',
      policy_verdict: 'NO_VERDICT',
      analysis_coverage: { status: 'INCOMPLETE', reasons: ['MALFORMED_JMETER_CSV'] },
      evidence: [{ id: 'd1', type: 'diagnostic', code: 'MALFORMED_JMETER_CSV', message: 'JMeter CSV input is invalid', source_offset: 128 }],
    }))

    expect(summary.headline).toBe('Вердикт не выдан — файл нагрузки не удалось разобрать')
    expect(summary.chip).toBe('файл не разобран')
    expect(summary.causes).toEqual([{ code: 'MALFORMED_JMETER_CSV', text: reasonText('MALFORMED_JMETER_CSV'), detail: 'Позиция в файле: 128 байт', subjectsLabel: '', subjects: [], subjectsHidden: 0 }])
    expect(summary.notes).toEqual([])
  })

  test('DEGRADED input says the file was read only partly', () => {
    const summary = summarizeVerdict(build({
      run_validity: 'DEGRADED',
      policy_verdict: 'NO_VERDICT',
      analysis_coverage: { status: 'INCOMPLETE', reasons: ['TRUNCATED_GATLING_BINARY'] },
      evidence: [{ id: 'd1', type: 'diagnostic', code: 'TRUNCATED_GATLING_BINARY', message: 'Gatling binary input is invalid' }],
    }))

    expect(summary.headline).toBe('Вердикт не выдан — файл нагрузки разобран не полностью')
    expect(summary.causes[0].detail).toBeNull()
  })

  test('an unknown code is shown raw with a generic sentence', () => {
    const summary = summarizeVerdict(build({
      policy_verdict: 'NO_VERDICT',
      analysis_coverage: { status: 'INCOMPLETE', reasons: ['SOMETHING_NEW'] },
      evidence: [{ id: 'c1', type: 'policy_check', rule_id: 'r', metric: 'response_time_p95_ms', operator: 'lte', threshold: 1, status: 'NO_VERDICT', reason_code: 'SOMETHING_NEW' }],
    }))

    expect(summary.causes).toHaveLength(1)
    expect(summary.causes[0].code).toBe('SOMETHING_NEW')
    expect(summary.causes[0].text).toBe('Причина без расшифровки в этой версии интерфейса.')
  })

  test('NO_VERDICT without any evidence still gives a sentence', () => {
    const summary = summarizeVerdict(build({ policy_verdict: 'NO_VERDICT' }))

    expect(summary.headline).toBe('Вердикт не выдан — ядро не смогло проверить все правила')
    expect(summary.causes).toHaveLength(1)
    expect(summary.causes[0].code).toBeNull()
    expect(summary.causes[0].text).toBe('Ядро не указало причину для этой проверки.')
  })

  test('PASS and FAIL treat coverage reasons as notes, not as causes', () => {
    const summary = summarizeVerdict(build({
      policy_verdict: 'FAIL',
      analysis_coverage: { status: 'INCOMPLETE', reasons: ['SOURCE_ACQUISITION_PARTIAL'] },
      evidence: [checkout, p95Rule('checkout-p95', 'FAIL', 2340)],
    }))

    expect(summary.causes).toEqual([])
    expect(summary.notes.map((note) => note.code)).toEqual(['SOURCE_ACQUISITION_PARTIAL'])
    expect(summary.notesTitle).toContain('Это не отменяет вердикт')
  })

  test('capacity NO_VERDICT with empty reasons explains the bound instead of staying silent', () => {
    const summary = summarizeVerdict(build({
      analysis_mode: 'capacity_step',
      policy_verdict: 'NO_VERDICT',
      capacity_summary: { schema_version: 'capacity.v1', load_axis: 'rps', unit: 'requests/s', bound_type: 'BOUNDED', lower_inclusive: 296, upper_exclusive: 344, policy_verdict: 'NO_VERDICT', reasons: [], capacity_knee: null, knee_reason: 'KNEE_DETECTOR_NOT_IMPLEMENTED', stages: [] },
    }))

    expect(summary.headline).toBe('Вердикт по ёмкости не выдан — границы недостаточно')
    expect(summary.lead).toBe('Граница ёмкости: BOUNDED [296, 344) requests/s. Подробности по ступеням — в таблице ниже.')
    expect(summary.causes).toHaveLength(1)
    expect(summary.causes[0].code).toBeNull()
    expect(summary.causes[0].text).toContain('не позволяет сравнить её с требуемой ёмкостью')
  })

  test('capacity reasons come from the summary and its stages', () => {
    const stage = (id: string, reasons: string[]) => ({ id, target: 1, achieved: null, achieved_statistic: 'p05_10s', observed_min: null, observed_max: null, complete_bins: 0, expected_bins: 30, target_tolerance_ratio: 0.02, verified_bound_load: null, verdict: 'INDETERMINATE', reasons, evidence_refs: [] })
    const summary = summarizeVerdict(build({
      analysis_mode: 'capacity_step',
      policy_verdict: 'NO_VERDICT',
      analysis_coverage: { status: 'INCOMPLETE', reasons: ['CAPACITY_STAGE_NOT_VERIFIED', 'CAPACITY_LOAD_GAPS'] },
      capacity_summary: { schema_version: 'capacity.v1', load_axis: 'rps', unit: 'requests/s', bound_type: 'INDETERMINATE', lower_inclusive: null, upper_exclusive: null, policy_verdict: 'NO_VERDICT', reasons: ['CAPACITY_STAGE_NOT_VERIFIED'], capacity_knee: null, knee_reason: 'KNEE_DETECTOR_NOT_IMPLEMENTED', stages: [stage('ramp-1', ['CAPACITY_LOAD_GAPS']), stage('ramp-2', ['CAPACITY_LOAD_GAPS'])] },
    }))

    expect(summary.causes.map((cause) => `${cause.code}:${cause.subjects.join(',')}`)).toEqual(['CAPACITY_STAGE_NOT_VERIFIED:', 'CAPACITY_LOAD_GAPS:ramp-1,ramp-2'])
    expect(summary.notes).toEqual([])
  })

  test('every documented code has words and still exists in the Kotlin sources', () => {
    const root = fileURLToPath(new URL('../../src/main/kotlin/', import.meta.url))
    const sources = readdirSync(root, { recursive: true, encoding: 'utf8' })
      .filter((name) => name.endsWith('.kt'))
      .map((name) => readFileSync(`${root}${name}`, 'utf8'))
      .join('\n')

    for (const [code, entry] of Object.entries(REASONS)) {
      expect(entry.text.length, code).toBeGreaterThan(10)
      expect(sources, code).toContain(`"${code}"`)
      expect(isNoVerdictReason(code)).toBe(entry.noVerdict)
    }
  })
})
