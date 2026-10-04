import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { expect, test } from '@playwright/test'
import type { AnalysisResult } from '../src/types'
import { RULES_LABELS } from '../src/shell/labels.rules'
import {
  ID_SUFFIX_BYTES, MAX_ID_BYTES, MAX_POLICY_BYTES, MAX_POLICY_RULES, MIN_SAMPLES_FLOOR,
  POLICY_TEMPLATES, expandPerTransaction, isGeneratedRule, summarizePolicy, templateById,
  thresholdHint, thresholdHintText, transactionRefs,
} from '../src/shell/rules'

const basic = () => templateById('api-basic')!
const ref = (label: string, sampleCount = 100, ambiguous = false) => ({ label, sampleCount, ambiguous })

test('templates have string thresholds, exact owner SLA, and independent copies', () => {
  expect(POLICY_TEMPLATES.map((item) => item.id)).toEqual(['api-basic', 'api-strict', 'api-throughput'])
  for (const { id, policy } of POLICY_TEMPLATES) {
    expect(policy.schema_version).toBe('policy.v1')
    expect(policy.policy_id).toBe(`template-${id}`)
    expect(policy.rules.every((rule) => typeof rule.threshold === 'string')).toBe(true)
    const copy = templateById(id)!
    copy.rules[0].threshold = '1'
    expect(templateById(id)!.rules[0].threshold).not.toBe('1')
  }
  expect(templateById('missing')).toBeNull()
  expect(basic().rules.map((rule) => [rule.id, rule.metric, rule.operator, rule.threshold, rule.scope.kind])).toEqual([
    ['overall-p95', 'response_time_p95_ms', 'lte', '1000', 'overall'],
    ['overall-errors', 'error_rate_ratio', 'lte', '0.05', 'overall'],
  ])
  expect(summarizePolicy(basic())).toEqual({ id: 'template-api-basic', rules: 2, transactions: 0 })
  expect(templateById('api-strict')!.rules.map((rule) => rule.threshold)).toEqual(['500', '1500', '0.001'])
  expect(templateById('api-throughput')!.rules.map((rule) => [rule.metric, rule.operator, rule.threshold])).toEqual([
    ['response_time_p95_ms', 'lte', '1000'], ['error_rate_ratio', 'lte', '0.01'], ['throughput_rps', 'gte', '100'],
  ])
})

test('threshold hints use decimal text for ratio previews', () => {
  for (const [value, preview] of [['0.07', '7 %'], ['0.001', '0.1 %'], ['0.123', '12.3 %'], ['1', '100 %'], ['0', '0 %']]) {
    expect(thresholdHint('error_rate_ratio', value)).toMatchObject({ unit: RULES_LABELS.unitRatio, hint: RULES_LABELS.hintRatio, preview })
    expect(thresholdHintText('error_rate_ratio', value)).toBe(`${RULES_LABELS.preview(preview)}. ${RULES_LABELS.hintRatio}`)
  }
  for (const [value, preview] of [['5e-2', '5 %'], ['1e-3', '0.1 %'], ['1E0', '100 %']]) expect(thresholdHint('error_rate_ratio', value).preview).toBe(preview)
  for (const value of ['', 'abc', '1.5', '-1', '2e-0']) expect(thresholdHint('error_rate_ratio', value).preview).toBeNull()
  expect(thresholdHint('response_time_p95_ms', '1000')).toMatchObject({ unit: RULES_LABELS.unitMs, preview: null })
  expect(thresholdHint('throughput_rps', '100')).toMatchObject({ unit: RULES_LABELS.unitRps, preview: null })
})

test('transaction refs aggregate labels and mark multiple identities ambiguous', () => {
  const metric = (label: string, count: number, path: string[], kind = 'JMETER_SAMPLER') => ({
    type: 'metric_summary', scope: { kind: 'transaction', label, group_path: path, sample_kind: kind }, sample_count: count,
  })
  const result = { evidence: [
    metric('z', 20, []), metric('A', 8, ['one']), metric('A', 12, ['two']), metric('a', 30, []),
    metric('kind', 10, [], 'JMETER_SAMPLER'), metric('kind', 15, [], 'JMETER_TRANSACTION'),
  ] } as AnalysisResult
  expect(transactionRefs(null)).toEqual([])
  expect(transactionRefs(result)).toEqual([ref('A', 20, true), ref('a', 30), ref('kind', 25, true), ref('z', 20)])
})

test('expansion skips small and ambiguous labels, stays idempotent, and never copies throughput', () => {
  const refs = [ref('search', 300), ref('rare', 12), ref('dup', 400, true), ref('login', 500)]
  const original = basic()
  const snapshot = structuredClone(original)
  const plan = expandPerTransaction(original, refs)
  expect(original).toEqual(snapshot)
  expect(plan.added).toBe(4)
  expect(plan.skippedSmall).toEqual([ref('rare', 12)])
  expect(plan.skippedAmbiguous).toEqual([ref('dup', 400, true)])
  expect(plan.policy.rules.map((rule) => rule.id)).toContain('overall-p95--tx001')
  expect(plan.policy.rules.every((rule) => new TextEncoder().encode(rule.id).length <= MAX_ID_BYTES)).toBe(true)
  expect(plan.policy.rules.filter((rule) => rule.scope.kind === 'transaction').every((rule) =>
    rule.scope.kind === 'transaction' && new TextEncoder().encode(rule.scope.name).length <= 4096)).toBe(true)
  expect(expandPerTransaction(plan.policy, refs).added).toBe(0)
  const throughput = expandPerTransaction(templateById('api-throughput')!, [ref('api')])
  expect(throughput.added).toBe(2)
  expect(throughput.policy.rules.filter((rule) => rule.scope.kind === 'transaction').map((rule) => rule.metric)).not.toContain('throughput_rps')
})

test('sample floor override and manual rules preserve the draft', () => {
  const low = basic()
  low.defaults = { sample_floor: 5 }
  expect(expandPerTransaction(low, [ref('small', 12)]).added).toBe(2)
  low.defaults.sample_floor = 50
  expect(expandPerTransaction(low, [ref('small', 12), ref('big', 300)]).skippedSmall).toEqual([ref('small', 12)])
  const manual = basic()
  manual.rules.push({ ...structuredClone(manual.rules[0]), id: 'manual', threshold: '777', scope: { kind: 'transaction', name: 'api' } })
  const plan = expandPerTransaction(manual, [ref('api')])
  expect(plan.added).toBe(1)
  expect(plan.policy.rules.find((rule) => rule.id === 'manual')?.threshold).toBe('777')
  expect(plan.policy.rules.filter((rule) => rule.scope.kind === 'transaction' && rule.metric === 'response_time_p95_ms')).toHaveLength(1)
})

test('ids avoid collisions and refusal leaves an unchanged copy', () => {
  const policy = basic()
  policy.rules.push({ ...structuredClone(policy.rules[0]), id: 'overall-p95--tx001', scope: { kind: 'overall' } })
  const plan = expandPerTransaction(policy, [ref('api')])
  expect(plan.policy.rules.map((rule) => rule.id).filter((id, index, ids) => ids.indexOf(id) === index)).toHaveLength(plan.policy.rules.length)
  expect(plan.policy.rules.some((rule) => rule.id === 'overall-p95--tx002')).toBe(true)
  expect(expandPerTransaction(basic(), []).refused).toBe('NO_TRANSACTIONS')
  const onlyThroughput = templateById('api-throughput')!
  onlyThroughput.rules = onlyThroughput.rules.filter((rule) => rule.metric === 'throughput_rps')
  expect(expandPerTransaction(onlyThroughput, [ref('api')]).refused).toBe('NO_BASE_RULES')
  const long = basic()
  long.rules[0].id = 'x'.repeat(MAX_ID_BYTES - ID_SUFFIX_BYTES + 1)
  const refused = expandPerTransaction(long, [ref('api')])
  expect(refused).toMatchObject({ refused: 'BASE_ID_TOO_LONG', added: 0, skippedSmall: [], skippedAmbiguous: [] })
  expect(refused.policy).toEqual(long)
  expect(refused.policy).not.toBe(long)
  const crowded = basic()
  crowded.rules.push(...Array.from({ length: MAX_POLICY_RULES - 3 }, (_, i) => ({ ...structuredClone(crowded.rules[0]), id: `extra-${i}` })))
  expect(expandPerTransaction(crowded, [ref('api')]).refused).toBe('TOO_MANY_RULES')
  expect(expandPerTransaction(basic(), Array.from({ length: 128 }, (_, i) => ref(`tx-${i}`))).refused).toBe('TOO_MANY_RULES')
})

test('expanding onto a different run replaces earlier generated rules', () => {
  const policy = expandPerTransaction(basic(), [ref('a'), ref('b')]).policy
  policy.rules.push({ ...structuredClone(policy.rules[0]), id: 'manual', metric: 'throughput_rps', operator: 'gte', threshold: '777', scope: { kind: 'transaction', name: 'a' } })
  policy.rules.push({ ...structuredClone(policy.rules[0]), id: 'manual-overall', metric: 'throughput_rps', operator: 'gte' })
  const plan = expandPerTransaction(policy, [ref('c')])
  expect(plan).toMatchObject({ refused: null, added: 2, removed: 4 })
  expect(plan.policy.rules).toHaveLength(6)
  expect(plan.policy.rules.filter((rule) => rule.scope.kind === 'overall')).toHaveLength(3)
  expect(plan.policy.rules.filter((rule) => rule.scope.kind === 'transaction').map((rule) => rule.scope.name)).toEqual(['a', 'c', 'c'])
  expect(plan.policy.rules.find((rule) => rule.id === 'manual')?.threshold).toBe('777')
  expect(plan.policy.rules.filter((rule) => rule.scope.kind === 'transaction' && rule.scope.name === 'b')).toEqual([])
})

test('expanding again on the same refs preserves rule order and policy fields', () => {
  const policy = expandPerTransaction(basic(), [ref('a'), ref('b')]).policy
  policy.rules.push({ ...structuredClone(policy.rules[0]), id: 'manual', metric: 'throughput_rps', operator: 'gte', scope: { kind: 'transaction', name: 'a' } })
  const plan = expandPerTransaction(policy, [ref('a'), ref('b')])
  expect(plan).toMatchObject({ refused: null, added: 0, removed: 0 })
  expect(plan.policy).toEqual(policy)
  expect(plan.policy).not.toBe(policy)
})

test('generated rules require transaction scope and a padded terminal suffix', () => {
  const rule = { ...structuredClone(basic().rules[0]), id: 'x--tx001', scope: { kind: 'transaction' as const, name: 'a' } }
  expect(isGeneratedRule(rule)).toBe(true)
  expect(isGeneratedRule({ ...rule, id: 'x--tx1000' })).toBe(true)
  expect(isGeneratedRule({ ...rule, scope: { kind: 'overall' } })).toBe(false)
  for (const id of ['x--tx01', 'x--tx001-copy', 'manual']) expect(isGeneratedRule({ ...rule, id })).toBe(false)
})

test('refusal preserves generated rules and the rule limit is checked after removal', () => {
  const generated = expandPerTransaction(basic(), [ref('a')]).policy
  const crowded = structuredClone(generated)
  crowded.rules.push(...Array.from({ length: 253 }, (_, i) => ({
    ...structuredClone(crowded.rules[0]), id: `throughput-${i}`, metric: 'throughput_rps' as const, operator: 'gte' as const,
  })))
  const refused = expandPerTransaction(crowded, [ref('b')])
  expect(refused).toMatchObject({ refused: 'TOO_MANY_RULES', added: 0, removed: 0 })
  expect(refused.policy).toEqual(crowded)
  expect(refused.policy).not.toBe(crowded)

  const full = expandPerTransaction(basic(), Array.from({ length: 127 }, (_, i) => ref(`tx-${i}`))).policy
  expect(full.rules).toHaveLength(256)
  const plan = expandPerTransaction(full, [ref('new')])
  expect(plan).toMatchObject({ refused: null, added: 2, removed: 254 })
  expect(plan.policy.rules).toHaveLength(4)
})

test('editing a generated threshold is reset by expansion', () => {
  const policy = expandPerTransaction(basic(), [ref('a')]).policy
  policy.rules[2].threshold = '777'
  const plan = expandPerTransaction(policy, [ref('a')])
  expect(plan).toMatchObject({ refused: null, added: 1, removed: 1 })
  expect(plan.policy.rules.find((rule) => rule.id === policy.rules[2].id)?.threshold).toBe('1000')
})

test('core and UI bounds stay aligned', () => {
  const core = readFileSync(fileURLToPath(new URL('../../src/main/kotlin/io/ltverdict/core/Policy.kt', import.meta.url)), 'utf8')
  expect(core).toContain(`MIN_SAMPLES_FLOOR = ${MIN_SAMPLES_FLOOR}L`)
  expect(core).toContain(`MAX_POLICY_RULES = ${MAX_POLICY_RULES}`)
  expect(core).toContain(`MAX_IDENTIFIER_BYTES = ${MAX_ID_BYTES}`)
  const api = readFileSync(fileURLToPath(new URL('../../src/main/kotlin/io/ltverdict/web/LocalApi.kt', import.meta.url)), 'utf8')
  expect(api).toContain(`MAX_POLICY_BYTES = ${String(MAX_POLICY_BYTES).replace(/\B(?=(\d{3})+(?!\d))/g, '_')}`)
  expect(ID_SUFFIX_BYTES).toBe(7)
  expect(new TextEncoder().encode('api').length).toBeLessThan(4096)
})

test('an empty transaction name is never turned into a rule', () => {
  const plan = expandPerTransaction(basic(), [ref(''), ref('api')])
  expect(plan.added).toBe(2)
  expect(plan.skippedUnnamed).toEqual([ref('')])
  expect(plan.skippedSmall).toEqual([])
  expect(plan.policy.rules.every((rule) => rule.scope.kind === 'overall' || rule.scope.name !== '')).toBe(true)
})

test('an expansion above the server policy size limit is refused as a whole', () => {
  const refs = Array.from({ length: 127 }, (_, i) => ref(`${String(i).padStart(3, '0')}${'x'.repeat(4090)}`))
  const plan = expandPerTransaction(basic(), refs)
  expect(plan.refused).toBe('TOO_LARGE')
  expect(plan.added).toBe(0)
  expect(plan.policy.rules).toHaveLength(2)
  const fits = expandPerTransaction(basic(), refs.slice(0, 100))
  expect(fits.refused).toBeNull()
  expect(new TextEncoder().encode(JSON.stringify(fits.policy)).length).toBeLessThanOrEqual(MAX_POLICY_BYTES)
})
