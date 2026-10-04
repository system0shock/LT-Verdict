import { expect, test } from '@playwright/test'
import type { AdviceDocument, AdviceJob, AnalysisResult } from '../src/types'
import { ADVICE_LABELS } from '../src/shell/labels.advice'
import { apiFailureView, evidenceRef, jobView, provenanceLines } from '../src/shell/advice'

// Чистые функции ИИ-разбора: происхождение совета и русские тексты ошибок. Данные на входе, готовые строки на выходе.
function advice(over: Record<string, unknown>): AdviceDocument {
  return {
    advisory: true,
    run_id: 'run-1',
    analysis_id: 'a'.repeat(64),
    output: { summary: 'x', hypotheses: [], recommendations: [], caveats: [] },
    ...over,
  } as unknown as AdviceDocument
}

function job(over: Record<string, unknown>): AdviceJob {
  return {
    job_id: 'job-1',
    run_id: 'run-1',
    analysis_id: 'a'.repeat(64),
    state: 'PROCESSING',
    reused: false,
    failure: null,
    unavailable_reason: null,
    ...over,
  } as unknown as AdviceJob
}

test('provenance shows the model and the prompt version and marks the legacy prompt', () => {
  const lines = provenanceLines(advice({ provenance: {
    model_id: 'deepseek-v4-flash-0731', prompt_version: 'advisory-system.v1', duration_ms: 12400,
    runner_id: 'gigacode-qwen-code', runner_version: '0.21.1', invocation_id: 'x',
  } }))
  expect(lines.find((line) => line.label === ADVICE_LABELS.model)?.value).toBe('deepseek-v4-flash-0731')
  expect(lines.find((line) => line.label === ADVICE_LABELS.promptVersion)?.value).toContain('прежняя версия')
  expect(lines.find((line) => line.label === ADVICE_LABELS.duration)?.value).toBe('12,4 с')
  expect(lines.find((line) => line.label === ADVICE_LABELS.runner)?.value).toBe('gigacode-qwen-code 0.21.1')
  expect(lines.find((line) => line.label === ADVICE_LABELS.invocation)?.value).toBe('x')
  // Поля provider_requests в старом документе нет: строки про число запросов не выдумываем.
  expect(lines.find((line) => line.label === ADVICE_LABELS.requests)).toBeUndefined()
  expect(lines.map((line) => line.label)).toEqual([
    ADVICE_LABELS.model, ADVICE_LABELS.promptVersion, ADVICE_LABELS.duration, ADVICE_LABELS.runner, ADVICE_LABELS.invocation,
  ])
})

test('a future prompt version and request count are shown as they are', () => {
  const lines = provenanceLines(advice({ provenance: { model_id: 'm', prompt_version: 'advisory-system.v2', provider_requests: 2, duration_ms: 1 } }))
  expect(lines.map((line) => line.value)).toContain('advisory-system.v2')
  const requests = lines.find((line) => line.label === ADVICE_LABELS.requests)
  expect(requests?.value).toBe('2')
  expect(requests?.hint).toBe(ADVICE_LABELS.requestsRetry)
  expect(lines.find((line) => line.label === ADVICE_LABELS.model)?.value).toBe('m')
  const single = provenanceLines(advice({ provenance: { model_id: 'm', prompt_version: 'advisory-system.v2', provider_requests: 1 } }))
  expect(single.find((line) => line.label === ADVICE_LABELS.requests)).toMatchObject({ value: '1' })
  expect(single.find((line) => line.label === ADVICE_LABELS.requests)?.hint).toBeUndefined()
})

test('an unknown or empty prompt version is shown as it is or marked as not stated', () => {
  const unknown = provenanceLines(advice({ provenance: { model_id: 'm', prompt_version: 'advisory-system.v9' } }))
  expect(unknown.find((line) => line.label === ADVICE_LABELS.promptVersion)?.value).toBe('advisory-system.v9')
  const empty = provenanceLines(advice({ provenance: { model_id: 'm', prompt_version: '' } }))
  expect(empty.find((line) => line.label === ADVICE_LABELS.promptVersion)?.value).toBe(ADVICE_LABELS.notStated)
  const bare = provenanceLines(advice({ provenance: {} }))
  expect(bare.find((line) => line.label === ADVICE_LABELS.model)?.value).toBe(ADVICE_LABELS.notStated)
})

test('missing provenance gives no lines instead of throwing', () => {
  expect(provenanceLines(advice({}))).toEqual([])
})

test('every known failure and unavailable reason has words, an unknown one keeps its code', () => {
  for (const failure of ['INPUT_LIMIT', 'OUTPUT_LIMIT', 'INVALID_ANALYSIS', 'INVALID_OUTPUT', 'UNKNOWN_EVIDENCE_REFERENCE', 'TIMEOUT', 'PROCESS_FAILED']) {
    const view = jobView(job({ state: 'FAILED', failure }))
    expect(view.code).toBe(failure)
    expect(ADVICE_LABELS.failure[failure]).toBeTruthy()
    expect(ADVICE_LABELS.failureHint[failure]).toBeTruthy()
    expect(view.text).toBe(ADVICE_LABELS.failure[failure])
    expect(view.hint).toBe(ADVICE_LABELS.failureHint[failure])
    expect(view.tone).toBe('fail')
    expect(view.state).toBe(ADVICE_LABELS.states.FAILED)
  }
  expect(jobView(job({ state: 'FAILED', failure: 'NEW_CODE' }))).toMatchObject({ code: 'NEW_CODE', text: ADVICE_LABELS.unknownFailure })
  expect(jobView(job({ state: 'FAILED', failure: null }))).toMatchObject({ code: null, text: ADVICE_LABELS.unknownFailure })
  for (const reason of ['CREDENTIAL_NOT_CONFIGURED', 'DOCKER_UNAVAILABLE', 'RUNTIME_IMAGE_MISSING', 'OS_ISOLATION_NOT_PROVEN', 'RUNNER_ARTIFACT_MISSING', 'RUNNER_ARTIFACT_MISMATCH', 'MODEL_ENDPOINT_UNAVAILABLE']) {
    const view = jobView(job({ state: 'UNAVAILABLE', unavailable_reason: reason }))
    expect(view.code).toBe(reason)
    expect(ADVICE_LABELS.unavailable[reason]).toBeTruthy()
    expect(ADVICE_LABELS.unavailableHint[reason]).toBeTruthy()
    expect(view.text).toBe(ADVICE_LABELS.unavailable[reason])
    expect(view.hint).toBe(ADVICE_LABELS.unavailableHint[reason])
    expect(view.tone).toBe('warn')
  }
  expect(jobView(job({ state: 'UNAVAILABLE', unavailable_reason: 'NEW_REASON' }))).toMatchObject({ code: 'NEW_REASON', text: ADVICE_LABELS.unknownUnavailable })
})

test('job states without an error read as plain Russian words with no code', () => {
  for (const state of ['QUEUED', 'PROCESSING', 'COMPLETE', 'CANCELLED']) {
    const view = jobView(job({ state }))
    expect(view).toMatchObject({ state: ADVICE_LABELS.states[state], text: '', code: null, hint: null, tone: 'info' })
  }
  expect(jobView(job({ state: 'SOMETHING_NEW' }))).toMatchObject({ state: ADVICE_LABELS.stateUnknown, code: 'SOMETHING_NEW' })
})

test('API errors AI_BUSY and AI_UNAVAILABLE read as words and other codes are not rewritten', () => {
  expect(apiFailureView('AI_BUSY', 'An AI task is already running')).toMatchObject({ text: ADVICE_LABELS.apiBusy, code: 'AI_BUSY', hint: ADVICE_LABELS.apiBusyHint })
  expect(apiFailureView('AI_UNAVAILABLE', 'AI runner is not configured')).toMatchObject({ text: ADVICE_LABELS.apiUnavailable, code: 'AI_UNAVAILABLE' })
  expect(apiFailureView('CANCEL_FAILED', 'Cancel request failed')).toMatchObject({ text: 'Cancel request failed', code: 'CANCEL_FAILED', hint: null })
})

// Основания гипотез (срез U6b): подпись evidence словами и цель перехода по якорям вкладки «Таблицы».
const refResult = {
  schema_version: 'analysis-result.v1', run_id: 'run-1', analysis_mode: 'standard', run_validity: 'VALID', policy_verdict: 'FAIL',
  analysis_coverage: { status: 'COMPLETE', reasons: [] }, findings: [],
  evidence: [
    { id: 'm-all', type: 'metric_summary', scope: { kind: 'overall' } },
    { id: 'm-pay', type: 'metric_summary', scope: { kind: 'transaction', group_path: ['Оплата'], label: 'POST /pay', sample_kind: 'JMETER_SAMPLER' } },
    { id: 'c1', type: 'policy_check', rule_id: 'checkout-p95', metric: 'response_time_p95_ms', operator: 'lte', threshold: 1, status: 'FAIL' },
    { id: 'rc1', type: 'resource_policy_check', rule_id: 'mem-limit' },
    { id: 'x1', type: 'brand_new_type' },
  ],
} as unknown as AnalysisResult

test('a policy check ref is a rule label that opens its table row', () => {
  expect(evidenceRef(refResult, 'c1')).toEqual({ label: `${ADVICE_LABELS.evidence.rule} checkout-p95`, target: { tab: 'tables', targetId: 'ev-c1' } })
})

test('a transaction metric ref opens its row and the overall metric ref opens the summary cards', () => {
  expect(evidenceRef(refResult, 'm-pay')).toEqual({ label: `${ADVICE_LABELS.evidence.metrics}: Оплата / POST /pay`, target: { tab: 'tables', targetId: 'ev-m-pay' } })
  expect(evidenceRef(refResult, 'm-all')).toEqual({ label: ADVICE_LABELS.evidence.overall, target: { tab: 'tables', targetId: 'summary-metrics' } })
})

test('other known evidence is named by type without a link and an unknown id stays a code', () => {
  expect(evidenceRef(refResult, 'rc1')).toEqual({ label: ADVICE_LABELS.evidence.types.resource_policy_check, target: null })
  expect(evidenceRef(refResult, 'x1')).toEqual({ label: 'x1', target: null })
  expect(evidenceRef(refResult, 'zzz')).toEqual({ label: 'zzz', target: null })
  expect(evidenceRef(null, 'c1')).toEqual({ label: 'c1', target: null })
  expect(evidenceRef(undefined, '')).toEqual({ label: '', target: null })
})
