import type {
  AnalysisResult,
  AnalysisPage,
  Bootstrap,
  BucketPage,
  JobStatus,
  Policy,
  PolicyValidation,
  RunPage,
  RunSummary,
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

export function createJob(runId: string, policy: Policy | null): Promise<JobStatus> {
  const body = new FormData()
  body.append('run_id', runId)
  if (policy) body.append('policy', new Blob([stringifyPolicy(policy)], { type: 'application/json' }), 'policy.json')
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
  return JSON.parse(text) as T
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
