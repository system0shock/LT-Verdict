import { expect, test } from '@playwright/test'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import type { AnalysisResult } from '../src/types'
import { COMPARE_LABELS, EN_COMPARE_LABELS } from '../src/shell/labels.compare'
import { ruleRows } from '../src/shell/tables'
import { summarizeVerdict, WINDOW_SHARE_LABEL } from '../src/verdictSummary'

// W2.4: the display of the CANDIDATE status says what it measures; the verdict card says how much of the run lies outside the
// verdict window; a throughput rule on a window is marked. Only the display changes, the wire values stay.
const binding = JSON.parse(readFileSync(fileURLToPath(new URL('../../fixtures/stages/stage-binding.sample.json', import.meta.url)), 'utf8'))
const overall = {
  id: 'm-overall', type: 'metric_summary', scope: { kind: 'overall' }, sample_count: 720, error_count: 0,
  error_rate_ratio: { numerator: 0, denominator: 720 }, throughput_rps: { numerator: 720000, denominator: 119800 },
  latency_ms: { p50: 149, p95: 821, p99: 836, max: 839 },
}
const check = (metric: string, windowId: string | null, status = 'FAIL') => ({
  id: `check-${metric}`, type: 'policy_check', rule_id: 'r1', metric, operator: 'gte', threshold: 100, scope: { kind: 'overall' },
  status, observed: 50, ...(windowId ? { window_id: windowId } : {}),
})
function build(verdict: string, evidence: unknown[]): AnalysisResult {
  return {
    schema_version: 'analysis-result.v1', run_id: 'r', analysis_mode: 'standard', run_validity: 'VALID', policy_verdict: verdict,
    analysis_coverage: { status: 'COMPLETE', reasons: [] }, findings: [], evidence,
  } as unknown as AnalysisResult
}
const resourceWindows = (runFrom: number, runTo: number, ...windows: Array<[number, number]>) => [
  { id: 'resource-binding', type: 'resource_binding', run_from_epoch_ms: runFrom, run_to_epoch_ms: runTo },
  ...windows.map(([from, to], index) => ({ id: `w${index}`, type: 'window_policy_summary', window_id: `w${index}`, from_epoch_ms: from, to_epoch_ms: to })),
]
const share = (result: AnalysisResult) => summarizeVerdict(result).facts.find((fact) => fact.label === WINDOW_SHARE_LABEL)?.value

test.describe('the CANDIDATE status is shown as a material delta whose significance is not assessed', () => {
  test('the Russian labels never show a bare candidate for the status', () => {
    expect(COMPARE_LABELS.windowStatus('CANDIDATE')).toBe('материальная дельта, значимость не оценена')
    expect(COMPARE_LABELS.intro).toContain('«материальная дельта, значимость не оценена»')
    expect(COMPARE_LABELS.intro).not.toMatch(/кандидат/i)
    expect(COMPARE_LABELS.statusLine('NOT_CONFIRMED' as never)).not.toMatch(/кандидат/i)
    expect(COMPARE_LABELS.statusLine('NOT_CONFIRMED' as never)).toContain('материальная дельта')
  })

  test('the English labels explain the raw status and leave other statuses as they are', () => {
    expect(EN_COMPARE_LABELS.windowStatus('CANDIDATE')).toBe('CANDIDATE (material delta, significance not assessed)')
    expect(EN_COMPARE_LABELS.windowStatus('DESCRIPTIVE')).toBe('DESCRIPTIVE')
  })

  test('the baseline candidates (the set of reference runs) keep their name', () => {
    expect(COMPARE_LABELS.candidatesLine('median-rank', 3)).toContain('3 кандидата')
    expect(COMPARE_LABELS.addCandidate).toContain('кандидаты')
  })
})

test.describe('the share of the run outside the verdict window', () => {
  test('stages: the numbers of stage_binding', () => {
    expect(share(build('PASS', [overall, binding]))).toBe('49,9 % прогона (59,8 с из 2 мин)')
  })

  test('resource windows: the run bounds of resource_binding and the window_policy_summary windows', () => {
    expect(share(build('PASS', [overall, ...resourceWindows(0, 120_000, [30_000, 60_000], [70_000, 100_000])]))).toBe('50,0 % прогона (1 мин из 2 мин)')
    expect(share(build('PASS', [overall, ...resourceWindows(0, 60_000, [0, 60_000])]))).toBe('0,0 % прогона (0 с из 1 мин)')
    expect(share(build('PASS', [overall, ...resourceWindows(0, 1_000_000, [0, 999_600])]))).toBe('меньше 0,1 % прогона (0,4 с из 16,67 мин)')
  })

  test('no windows or no run: no fact', () => {
    expect(share(build('PASS', [overall]))).toBeUndefined()
    expect(share(build('PASS', [overall, ...resourceWindows(0, 60_000)]))).toBeUndefined()
    expect(share(build('PASS', [overall, ...resourceWindows(5, 5, [5, 5])]))).toBeUndefined()
    // The window items without resource_binding or stage_binding are what a run without windows can carry.
    expect(share(build('PASS', [overall, ...resourceWindows(0, 60_000, [0, 30_000]).slice(1)]))).toBeUndefined()
  })

  test('a decided FAIL by stages keeps the old facts first, the share comes after the window facts', () => {
    const labels = summarizeVerdict(build('FAIL', [overall, binding, check('response_time_p95_ms', 'steady')])).facts.map((fact) => fact.label)

    expect(labels.slice(-3)).toEqual(['Окно вердикта', 'Исключено', WINDOW_SHARE_LABEL])
  })
})

test.describe('a throughput rule evaluated on a window is marked', () => {
  const marked = 'пропускная способность (throughput на окне)'

  test('the verdict card and the rule table mark a windowed throughput rule only', () => {
    const windowed = build('FAIL', [overall, check('throughput_rps', 'steady')])
    const whole = build('FAIL', [overall, check('throughput_rps', null)])
    const p95 = build('FAIL', [overall, check('response_time_p95_ms', 'steady')])

    expect(summarizeVerdict(windowed).lines[0]!.title).toContain(marked)
    expect(ruleRows(windowed)[0]!.metric).toBe(marked)
    expect(summarizeVerdict(whole).lines[0]!.title).not.toContain('throughput на окне')
    expect(ruleRows(whole)[0]!.metric).toBe('пропускная способность')
    expect(summarizeVerdict(p95).lines[0]!.title).toContain('p95 отклика')
    expect(ruleRows(p95)[0]!.metric).toBe('p95 отклика')
  })
})
