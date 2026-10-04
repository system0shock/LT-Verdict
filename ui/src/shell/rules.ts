import type { AnalysisResult, Policy, PolicyRule } from '../types'
import { RULES_LABELS } from './labels.rules'
import basic from './policy-templates/api-basic.json' with { type: 'json' }
import strict from './policy-templates/api-strict.json' with { type: 'json' }
import throughput from './policy-templates/api-throughput.json' with { type: 'json' }

export const MIN_SAMPLES_FLOOR = 20
export const MAX_POLICY_RULES = 256
export const MAX_ID_BYTES = 128
export const MAX_POLICY_BYTES = 1_048_576
export const ID_SUFFIX_BYTES = 7

export interface PolicyTemplate { id: string; policy: Policy }
export const POLICY_TEMPLATES: readonly PolicyTemplate[] = [
  { id: 'api-basic', policy: basic },
  { id: 'api-strict', policy: strict },
  { id: 'api-throughput', policy: throughput },
].map(({ id, policy }) => ({ id, policy: { ...policy, schema_version: 'policy.v1' as const, rules: policy.rules.map((rule) => ({
  ...rule, metric: rule.metric as PolicyRule['metric'], operator: rule.operator as PolicyRule['operator'],
  threshold: String(rule.threshold), scope: { kind: 'overall' as const },
})) } }))

export function templateById(id: string): Policy | null {
  const template = POLICY_TEMPLATES.find((item) => item.id === id)
  return template ? structuredClone(template.policy) : null
}

export interface ThresholdHint { unit: string; hint: string; preview: string | null }
export function thresholdHint(metric: PolicyRule['metric'], value: string): ThresholdHint {
  if (metric === 'error_rate_ratio') {
    const valid = /^(?:0(?:\.\d+)?|1(?:\.0+)?)$/.test(value)
    let preview: string | null = null
    if (valid) {
      const [whole, fraction = ''] = value.split('.')
      const shifted = fraction.padEnd(2, '0')
      const integer = shifted.slice(0, 2).replace(/^0+(?=\d)/, '')
      const decimal = shifted.slice(2).replace(/0+$/, '')
      preview = `${whole === '1' ? '100' : `${integer}${decimal ? `.${decimal}` : ''}`} %`
    }
    // Exponent notation (5e-2) is a valid number too: the float path is display-only and rounded to 12 digits.
    const number = Number(value)
    if (!valid && /^\d*\.?\d+e[+-]?\d+$/i.test(value) && number >= 0 && number <= 1) preview = `${Number((number * 100).toPrecision(12))} %`
    return { unit: RULES_LABELS.unitRatio, hint: RULES_LABELS.hintRatio, preview }
  }
  if (metric === 'throughput_rps') return { unit: RULES_LABELS.unitRps, hint: RULES_LABELS.hintRps, preview: null }
  return { unit: RULES_LABELS.unitMs, hint: RULES_LABELS.hintMs, preview: null }
}

export function thresholdHintText(metric: PolicyRule['metric'], value: string): string | null {
  const { hint, preview } = thresholdHint(metric, value)
  return preview ? `${RULES_LABELS.preview(preview)}. ${hint}` : hint
}

export function summarizePolicy(policy: Policy): { id: string; rules: number; transactions: number } {
  return { id: policy.policy_id, rules: policy.rules.length, transactions: policy.rules.filter((rule) => rule.scope.kind === 'transaction').length }
}

export interface TransactionRef { label: string; sampleCount: number; ambiguous: boolean }
export function transactionRefs(result: AnalysisResult | null): TransactionRef[] {
  const refs = new Map<string, { sampleCount: number; entries: number }>()
  for (const item of result?.evidence ?? []) {
    if (item.type !== 'metric_summary' || item.scope.kind !== 'transaction') continue
    const current = refs.get(item.scope.label) ?? { sampleCount: 0, entries: 0 }
    current.sampleCount += item.sample_count
    current.entries += 1
    refs.set(item.scope.label, current)
  }
  return [...refs].sort(([a], [b]) => a < b ? -1 : a > b ? 1 : 0)
    .map(([label, value]) => ({ label, sampleCount: value.sampleCount, ambiguous: value.entries > 1 }))
}

export interface PerTransactionPlan {
  policy: Policy
  added: number
  skippedSmall: TransactionRef[]
  skippedAmbiguous: TransactionRef[]
  skippedUnnamed: TransactionRef[]
  refused: 'NO_TRANSACTIONS' | 'TOO_MANY_RULES' | 'TOO_LARGE' | 'NO_BASE_RULES' | 'BASE_ID_TOO_LONG' | null
}

export function expandPerTransaction(policy: Policy, refs: TransactionRef[]): PerTransactionPlan {
  const copy = structuredClone(policy)
  const refuse = (refused: NonNullable<PerTransactionPlan['refused']>): PerTransactionPlan =>
    ({ policy: copy, added: 0, skippedSmall: [], skippedAmbiguous: [], skippedUnnamed: [], refused })
  if (!refs.length) return refuse('NO_TRANSACTIONS')
  const base = policy.rules.filter((rule) => rule.scope.kind === 'overall' && rule.metric !== 'throughput_rps')
  if (!base.length) return refuse('NO_BASE_RULES')
  const floor = policy.defaults?.sample_floor ?? MIN_SAMPLES_FLOOR
  const ordered = [...refs].sort((a, b) => a.label < b.label ? -1 : a.label > b.label ? 1 : 0)
  const skippedUnnamed = ordered.filter((item) => item.label === '')
  const named = ordered.filter((item) => item.label !== '')
  const skippedAmbiguous = named.filter((item) => item.ambiguous)
  const skippedSmall = named.filter((item) => !item.ambiguous && item.sampleCount < floor)
  const eligible = named.filter((item) => !item.ambiguous && item.sampleCount >= floor)
  if (eligible.length && base.some((rule) => new TextEncoder().encode(rule.id).length > MAX_ID_BYTES - ID_SUFFIX_BYTES)) return refuse('BASE_ID_TOO_LONG')
  const used = new Set(copy.rules.map((rule) => rule.id))
  let counter = 1
  let added = 0
  for (const item of eligible) {
    for (const rule of base) {
      if (copy.rules.some((existing) => existing.metric === rule.metric && existing.operator === rule.operator &&
        existing.scope.kind === 'transaction' && existing.scope.name === item.label)) continue
      let id = `${rule.id}--tx${String(counter).padStart(3, '0')}`
      while (used.has(id)) { counter++; id = `${rule.id}--tx${String(counter).padStart(3, '0')}` }
      used.add(id)
      copy.rules.push({ ...structuredClone(rule), id, scope: { kind: 'transaction', name: item.label } })
      added++
    }
    counter++
  }
  if (copy.rules.length > MAX_POLICY_RULES) return { ...refuse('TOO_MANY_RULES'), policy: structuredClone(policy) }
  if (new TextEncoder().encode(JSON.stringify(copy)).length > MAX_POLICY_BYTES) return { ...refuse('TOO_LARGE'), policy: structuredClone(policy) }
  return { policy: copy, added, skippedSmall, skippedAmbiguous, skippedUnnamed, refused: null }
}
