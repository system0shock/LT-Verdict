import type { SavedAnalytics } from './analyticsTypes'
import type {
  AdviceDocument,
  AdviceJob,
  JenkinsAttempt,
  JenkinsProfile,
  AnalysisResult,
  AnalysisPage,
  AnalysisReference,
  BaselineComparison,
  BaselineCondition,
  BaselineConditionDecision,
  BaselineConditionWindows,
  BaselineRequest,
  BaselineSelection,
  Bootstrap,
  BucketPage,
  JobStatus,
  Policy,
  PolicyValidation,
  PostgresCaptureResponse,
  RunPage,
  RunSummary,
  SourceRequest,
  SourcesResponse,
  WindowComparisonRequest,
} from './types'

let csrfToken = ''
const rawJson = JSON as JSON & { rawJSON(value: string): unknown }

interface JsonParseContext {
  source: string
}

export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
  ) {
    super(message)
  }
}

export async function bootstrap(): Promise<Bootstrap> {
  const value = await request<Bootstrap>('/api/bootstrap')
  csrfToken = value.csrf_token
  return value
}

export function listRuns(after?: string): Promise<RunPage> {
  const query = new URLSearchParams({ limit: '100' })
  if (after) query.set('after', after)
  return request(`/api/runs?${query}`)
}

export function listAnalyses(runId: string, after?: string): Promise<AnalysisPage> {
  const query = new URLSearchParams({ limit: '25' })
  if (after) query.set('after', after)
  return request(`/api/runs/${encodeURIComponent(runId)}/analyses?${query}`)
}

export async function validatePolicy(policy: Policy | File): Promise<PolicyValidation> {
  const response = await fetch('/api/policies/validate', {
    credentials: 'same-origin',
    method: 'POST',
    headers: mutationHeaders({ 'Content-Type': 'application/json' }),
    body: policy instanceof File ? policy : stringifyPolicy(policy),
  })
  const text = await response.text()
  if (response.status === 200 || response.status === 422) {
    return JSON.parse(text, exactThreshold) as PolicyValidation
  }
  throw apiError(response.status, text)
}

export function uploadInput(file: File, progress: (percent: number) => void): Promise<RunSummary> {
  return new Promise((resolve, reject) => {
    const body = new FormData()
    body.append('file', file)
    const xhr = new XMLHttpRequest()
    xhr.open('POST', '/api/inputs')
    xhr.setRequestHeader('X-LTV-CSRF', requireCsrf())
    xhr.upload.addEventListener('progress', (event) => {
      if (event.lengthComputable) progress(Math.round((event.loaded / event.total) * 100))
    })
    xhr.addEventListener('load', () => {
      if (xhr.status >= 200 && xhr.status < 300) resolve(JSON.parse(xhr.responseText) as RunSummary)
      else reject(apiError(xhr.status, xhr.responseText))
    })
    xhr.addEventListener('error', () => reject(new ApiError(0, 'NETWORK_ERROR', 'Local request failed')))
    xhr.send(body)
  })
}

export function capturePostgresPhase(profileId: string, phase: 'pre' | 'post', pre?: File | null): Promise<PostgresCaptureResponse> {
  const body = new FormData()
  body.append('profile_id', profileId)
  if (phase === 'post' && pre) body.append('pre', pre)
  return request(`/api/sources/postgresql/${phase}`, { method: 'POST', headers: mutationHeaders(), body })
}

export function createJob(runId: string, policy: Policy | null, resources?: File | null, diagnostics?: File | null, sourceRequest?: SourceRequest | null, sourceContexts: File[] = [], postgresPre?: File | null, postgresPost?: File | null, pgProfileHtml?: File | null, capacity?: File | null): Promise<JobStatus> {
  const body = new FormData()
  body.append('run_id', runId)
  if (policy) body.append('policy', new Blob([stringifyPolicy(policy)], { type: 'application/json' }), 'policy.json')
  if (sourceRequest) body.append('source_request', new Blob([JSON.stringify(sourceRequest)], { type: 'application/json' }), 'source-request.json')
  else {
    if (resources) body.append('resource_snapshot', resources)
    if (diagnostics) body.append('correlation_plan', diagnostics)
    for (const sourceContext of sourceContexts) body.append('source_context', sourceContext)
  }
  if (postgresPre) body.append('postgres_pre', postgresPre)
  if (postgresPost) body.append('postgres_post', postgresPost)
  if (pgProfileHtml) body.append('pg_profile_html', pgProfileHtml)
  if (capacity) body.append('capacity_plan', capacity)
  return request('/api/jobs', { method: 'POST', headers: mutationHeaders(), body })
}

export function getJob(jobId: string): Promise<JobStatus> {
  return request(`/api/jobs/${encodeURIComponent(jobId)}`)
}

export function cancelJob(jobId: string): Promise<JobStatus> {
  return request(`/api/jobs/${encodeURIComponent(jobId)}`, { method: 'DELETE', headers: mutationHeaders() })
}

export function getResult(runId: string, analysisId: string): Promise<AnalysisResult> {
  return request(`/api/runs/${encodeURIComponent(runId)}/analyses/${encodeURIComponent(analysisId)}/result`)
}

export function listSources(): Promise<SourcesResponse> {
  return request('/api/sources')
}

export function getBaseline(): Promise<{ baseline: BaselineSelection | null }> {
  return request('/api/baseline')
}

export function setBaseline(body: BaselineRequest): Promise<{ baseline: BaselineSelection }> {
  return request('/api/baseline', {
    method: 'POST',
    headers: mutationHeaders({ 'Content-Type': 'application/json' }),
    body: JSON.stringify(body),
  })
}

export function clearBaseline(): Promise<{ baseline: null }> {
  return request('/api/baseline', { method: 'DELETE', headers: mutationHeaders() })
}

export function getBaselineConditions(
  reference: AnalysisReference,
  windows?: BaselineConditionWindows,
): Promise<{ conditions: BaselineCondition | null }> {
  return request(baselineConditionsPath(reference, windows))
}

export function setBaselineConditions(
  reference: AnalysisReference,
  decision: BaselineConditionDecision,
  windows?: BaselineConditionWindows,
): Promise<{ conditions: BaselineCondition }> {
  return request(baselineConditionsPath(reference, windows), {
    method: 'POST',
    headers: mutationHeaders({ 'Content-Type': 'application/json' }),
    body: JSON.stringify({ decision }),
  })
}

function baselineConditionsPath(reference: AnalysisReference, windows?: BaselineConditionWindows): string {
  const query = windows ? `?${new URLSearchParams({ ...windows })}` : ''
  return `/api/runs/${encodeURIComponent(reference.run_id)}/analyses/${encodeURIComponent(reference.analysis_id)}/baseline-conditions${query}`
}

export function compareBaseline(reference: AnalysisReference, windows?: WindowComparisonRequest): Promise<BaselineComparison> {
  const query = windows ? `?${new URLSearchParams({ ...windows })}` : ''
  return request(`/api/runs/${encodeURIComponent(reference.run_id)}/analyses/${encodeURIComponent(reference.analysis_id)}/comparison${query}`)
}

export function getBuckets(
  runId: string,
  analysisId: string,
  rollup: number,
  fromMillis?: number,
  toMillis?: number,
): Promise<BucketPage> {
  const query = new URLSearchParams({ rollup: String(rollup), limit: '500' })
  if (fromMillis !== undefined) query.set('from_ms', String(fromMillis))
  if (toMillis !== undefined) query.set('to_ms', String(toMillis))
  return request(
    `/api/runs/${encodeURIComponent(runId)}/analyses/${encodeURIComponent(analysisId)}/buckets?${query}`,
  )
}

export function stringifyPolicy(policy: Policy, space?: number): string {
  return JSON.stringify(
    policy,
    (key, value: unknown) => {
      if (key !== 'threshold' || typeof value !== 'string') return value
      try {
        return rawJson.rawJSON(value)
      } catch {
        return value
      }
    },
    space,
  )
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(path, { credentials: 'same-origin', ...init })
  const text = await response.text()
  if (!response.ok) throw apiError(response.status, text)
  return JSON.parse(text, exactErrorCounts) as T
}

function mutationHeaders(extra: Record<string, string> = {}): HeadersInit {
  return { 'X-LTV-CSRF': requireCsrf(), ...extra }
}

function requireCsrf(): string {
  if (!csrfToken) throw new Error('API_NOT_BOOTSTRAPPED')
  return csrfToken
}

function apiError(status: number, text: string): ApiError {
  try {
    const body = JSON.parse(text) as { error?: { code?: string; message?: string } }
    return new ApiError(status, body.error?.code ?? 'REQUEST_FAILED', body.error?.message ?? 'Local request failed')
  } catch {
    return new ApiError(status, 'REQUEST_FAILED', 'Local request failed')
  }
}

function exactThreshold(key: string, value: unknown, context?: JsonParseContext): unknown {
  return key === 'threshold' && typeof value === 'number' && context ? context.source : value
}

function exactErrorCounts(this: Record<string, unknown>, key: string, value: unknown, context?: JsonParseContext): unknown {
  const errorCount = (this.type === 'opensearch_errors' && ['total_errors', 'error_rate_per_minute'].includes(key)) ||
    (key === 'count' && typeof this.service === 'string' && typeof this.error_type === 'string')
  const postgresTableCount = ['row_count_delta', 'inserted', 'deleted', 'updated'].includes(key) &&
    typeof this.schema === 'string' && typeof this.table === 'string'
  const postgresStatementCount = ['calls', 'total_exec_time', 'rows', 'shared_blks_hit', 'shared_blks_read', 'temp_blks_written'].includes(key) &&
    typeof this.dbid === 'string' && typeof this.userid === 'string' && typeof this.toplevel === 'boolean'
  return (errorCount || postgresTableCount || postgresStatementCount) && typeof value === 'number' && context ? context.source : value
}

export function getAdvice(reference: AnalysisReference): Promise<{ advice: AdviceDocument | null; job: AdviceJob | null }> {
  return request(`/api/runs/${encodeURIComponent(reference.run_id)}/analyses/${encodeURIComponent(reference.analysis_id)}/advice`)
}

export function startAdvice(reference: AnalysisReference): Promise<AdviceJob> {
  return request(`/api/runs/${encodeURIComponent(reference.run_id)}/analyses/${encodeURIComponent(reference.analysis_id)}/advice`, {
    method: 'POST', headers: mutationHeaders({ 'Content-Type': 'application/json' }),
    body: JSON.stringify({ confirm_external_transfer: true }),
  })
}

export function getAdviceJob(jobId: string): Promise<AdviceJob> {
  return request(`/api/advice-jobs/${encodeURIComponent(jobId)}`)
}

export function cancelAdviceJob(jobId: string): Promise<AdviceJob> {
  return request(`/api/advice-jobs/${encodeURIComponent(jobId)}`, { method: 'DELETE', headers: mutationHeaders() })
}

export function listJenkins(): Promise<{ profiles: JenkinsProfile[] }> { return request('/api/jenkins') }
export function listJenkinsAttempts(profileId: string): Promise<{ attempts: JenkinsAttempt[] }> {
  return request(`/api/jenkins/${encodeURIComponent(profileId)}/attempts`)
}
export function triggerJenkins(profileId: string, parameters: Record<string, string>): Promise<JenkinsAttempt> {
  return request(`/api/jenkins/${encodeURIComponent(profileId)}/trigger`, {
    method: 'POST', headers: mutationHeaders({ 'Content-Type': 'application/json' }), body: JSON.stringify({ parameters }),
  })
}
export function advanceJenkins(profileId: string, attemptId: string, operation: 'advance' | 'reconcile' | 'collect', artifactPath?: string): Promise<{ attempt: JenkinsAttempt; run: RunSummary | null }> {
  return request(`/api/jenkins/${encodeURIComponent(profileId)}/attempts/${encodeURIComponent(attemptId)}/${operation}`, {
    method: 'POST', headers: mutationHeaders({ 'Content-Type': 'application/json' }), body: JSON.stringify(operation === 'collect' ? { artifact_path: artifactPath } : {}),
  })
}

export function getSavedAnalytics(reference: AnalysisReference, limit = 10, transaction = '', transactionLimit = 100): Promise<SavedAnalytics> {
  const query = new URLSearchParams({ limit: String(limit), transaction_limit: String(transactionLimit) })
  if (transaction.trim()) query.set('transaction', transaction.trim())
  return request(`/api/runs/${encodeURIComponent(reference.run_id)}/analyses/${encodeURIComponent(reference.analysis_id)}/analytics?${query}`)
}

export function listGrafana(): Promise<{ profiles: Array<{ id: string; base_url: string }> }> {
  return request('/api/grafana')
}

export function grafanaPanel(reference: AnalysisReference, profile: string, dashboard: string, panel: number, render: boolean): Promise<{ source_link: string; png_base64?: string | null; failure_code?: string | null }> {
  const query = new URLSearchParams({ profile, dashboard, panel: String(panel) })
  return request(`/api/runs/${encodeURIComponent(reference.run_id)}/analyses/${encodeURIComponent(reference.analysis_id)}/grafana-${render ? 'render' : 'link'}?${query}`, render ? {
    method: 'POST', headers: mutationHeaders({ 'Content-Type': 'application/json' }), body: '{}',
  } : undefined)
}
