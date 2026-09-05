export type Theme = 'light' | 'dark'

export interface Bootstrap {
  csrf_token: string
  max_upload_bytes: number
}

export interface RunSummary {
  run_id: string
  source_type: string
  sha256: string
  size_bytes: number
  original_filename: string
}

export interface RunPage {
  runs: RunSummary[]
  next_after: string | null
}

export interface AnalysisSummary {
  analysis_id: string
  policy_sha256: string
  policy_verdict: AnalysisResult['policy_verdict']
  run_validity: AnalysisResult['run_validity']
}

export interface AnalysisPage {
  analyses: AnalysisSummary[]
  next_after: string | null
}

export interface PolicyError {
  code: string
  json_pointer: string
  message: string
}

export type PolicyScope = { kind: 'overall' } | { kind: 'transaction'; name: string }

export interface PolicyRule {
  id: string
  metric: 'response_time_p95_ms' | 'response_time_p99_ms' | 'error_rate_ratio' | 'throughput_rps'
  operator: 'lte' | 'gte'
  threshold: string
  scope: PolicyScope
}

export interface Policy {
  schema_version: 'policy.v1'
  policy_id: string
  rules: PolicyRule[]
}

export type PolicyValidation =
  | { valid: true; policy: Policy; sha256: string }
  | { valid: false; errors: PolicyError[] }

export type JobState = 'QUEUED' | 'PROCESSING' | 'COMPLETE' | 'FAILED' | 'CANCELLED'

export interface JobStatus {
  job_id: string
  state: JobState
  processed_bytes: number
  total_bytes: number
  run_id: string
  analysis_id: string | null
  diagnostic: { code: string; message: string; source_offset: number | null } | null
}

export interface ExactRatio {
  numerator: number
  denominator: number
}

export interface MetricScopeOverall {
  kind: 'overall'
}

export interface MetricScopeTransaction {
  kind: 'transaction'
  group_path: string[]
  label: string
  sample_kind: string
}

export interface MetricSummaryEvidence {
  id: string
  type: 'metric_summary'
  scope: MetricScopeOverall | MetricScopeTransaction
  sample_count: number
  error_count: number
  error_rate_ratio: ExactRatio | null
  throughput_rps: ExactRatio
  latency_ms: { p50: number; p95: number; p99: number; max: number }
}

export interface PolicyCheckEvidence {
  id: string
  type: 'policy_check'
  rule_id: string
  metric: PolicyRule['metric']
  operator: PolicyRule['operator']
  threshold: number
  status: 'PASS' | 'FAIL' | 'NO_VERDICT'
  metric_evidence_id?: string
  observed?: number | ExactRatio
  reason_code?: string
}

export interface DiagnosticEvidence {
  id: string
  type: 'diagnostic'
  code: string
  message: string
  source_offset?: number
}

export type AnalysisEvidence = MetricSummaryEvidence | PolicyCheckEvidence | DiagnosticEvidence

export interface AnalysisResult {
  schema_version: 'analysis-result.v1'
  run_id: string
  analysis_mode: string
  run_validity: 'VALID' | 'DEGRADED' | 'INVALID'
  policy_verdict: 'PASS' | 'FAIL' | 'NO_POLICY' | 'NO_VERDICT'
  analysis_coverage: { status: 'COMPLETE' | 'INCOMPLETE'; reasons: string[] }
  findings: Array<Record<string, unknown>>
  evidence: AnalysisEvidence[]
}

export interface Bucket {
  bucket_start_ms: number
  sample_count: number
  error_count: number
  p95_latency_ms: number
  max_latency_ms: number
  hdr_v2_base64: string
}

export interface BucketPage {
  buckets: Bucket[]
  next_from_ms: number | null
}
