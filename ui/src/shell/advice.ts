import type { AdviceDocument, AdviceJob, AnalysisResult } from '../types'
import { scopeLabel } from '../verdictSummary'
import { ADVICE_LABELS } from './labels.advice'
import type { AttentionTarget } from './overview'

export interface ProvenanceLine { label: string; value: string; hint?: string }
export interface EvidenceRef { label: string; target: AttentionTarget | null }
export interface FailureView { state: string; text: string; code: string | null; hint: string | null; tone: 'info' | 'warn' | 'fail' }

export function provenanceLines(advice: AdviceDocument): ProvenanceLine[] {
  const source = advice.provenance
  if (!source) return []

  const lines: ProvenanceLine[] = [
    { label: ADVICE_LABELS.model, value: source.model_id || ADVICE_LABELS.notStated },
    { label: ADVICE_LABELS.promptVersion, value: source.prompt_version === 'advisory-system.v1'
      ? ADVICE_LABELS.promptLegacy(source.prompt_version)
      : source.prompt_version || ADVICE_LABELS.notStated },
  ]
  if (typeof source.provider_requests === 'number') {
    lines.push(source.provider_requests > 1
      ? { label: ADVICE_LABELS.requests, value: String(source.provider_requests), hint: ADVICE_LABELS.requestsRetry }
      : { label: ADVICE_LABELS.requests, value: String(source.provider_requests) })
  }
  if (typeof source.duration_ms === 'number' && Number.isFinite(source.duration_ms)) {
    lines.push({ label: ADVICE_LABELS.duration, value: ADVICE_LABELS.durationText(source.duration_ms) })
  }
  const runner = [source.runner_id, source.runner_version].filter((part) => typeof part === 'string' && part.length > 0).join(' ')
  if (runner) lines.push({ label: ADVICE_LABELS.runner, value: runner })
  if (source.invocation_id) lines.push({ label: ADVICE_LABELS.invocation, value: source.invocation_id })
  return lines
}

export function jobView(job: AdviceJob): FailureView {
  const state = Object.hasOwn(ADVICE_LABELS.states, job.state) ? ADVICE_LABELS.states[job.state]! : ADVICE_LABELS.stateUnknown
  if (job.state === 'FAILED') {
    const code = job.failure ?? null
    const known = code !== null && Object.hasOwn(ADVICE_LABELS.failure, code)
    return { state, text: known ? ADVICE_LABELS.failure[code]! : ADVICE_LABELS.unknownFailure,
      code, hint: known && Object.hasOwn(ADVICE_LABELS.failureHint, code) ? ADVICE_LABELS.failureHint[code]! : ADVICE_LABELS.unknownFailureHint, tone: 'fail' }
  }
  if (job.state === 'UNAVAILABLE') {
    const code = job.unavailable_reason ?? null
    const known = code !== null && Object.hasOwn(ADVICE_LABELS.unavailable, code)
    return { state, text: known ? ADVICE_LABELS.unavailable[code]! : ADVICE_LABELS.unknownUnavailable,
      code, hint: known && Object.hasOwn(ADVICE_LABELS.unavailableHint, code) ? ADVICE_LABELS.unavailableHint[code]! : ADVICE_LABELS.unknownFailureHint, tone: 'warn' }
  }
  return { state, text: '', code: Object.hasOwn(ADVICE_LABELS.states, job.state) ? null : String(job.state), hint: null, tone: 'info' }
}

export function apiFailureView(code: string, message: string): FailureView {
  if (code === 'AI_BUSY') return { state: '', text: ADVICE_LABELS.apiBusy, code, hint: ADVICE_LABELS.apiBusyHint, tone: 'warn' }
  if (code === 'AI_UNAVAILABLE') return { state: '', text: ADVICE_LABELS.apiUnavailable, code, hint: ADVICE_LABELS.apiUnavailableHint, tone: 'warn' }
  return { state: '', text: message, code, hint: null, tone: 'fail' }
}

// Основание гипотезы: подпись evidence словами и цель перехода (якоря ev-<id> из U3a). Неизвестный id остаётся кодом без цели.
export function evidenceRef(result: AnalysisResult | null | undefined, id: string): EvidenceRef {
  const item = result?.evidence.find((candidate) => 'id' in candidate && candidate.id === id)
  if (!item) return { label: id, target: null }
  const labels = ADVICE_LABELS.evidence
  if (item.type === 'policy_check') return { label: `${labels.rule} ${item.rule_id}`, target: { tab: 'tables', targetId: `ev-${id}` } }
  if (item.type === 'metric_summary') {
    return item.scope.kind === 'overall'
      ? { label: labels.overall, target: { tab: 'tables', targetId: 'summary-metrics' } }
      : { label: `${labels.metrics}: ${scopeLabel(item.scope)}`, target: { tab: 'tables', targetId: `ev-${id}` } }
  }
  return { label: Object.hasOwn(labels.types, item.type) ? labels.types[item.type]! : id, target: null }
}
