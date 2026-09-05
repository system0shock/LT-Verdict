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
  window_id?: string
  scope?: MetricScopeOverall | MetricScopeTransaction
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

export interface ResourceSummaryEvidence {
  id: string
  type: 'resource_summary'
  series_id: string
  metric: string
  unit: string
  entity: string
  role: 'system' | 'generator'
  aggregation: 'interval_mean' | 'interval_rate'
  window_id: string
  from_epoch_ms: number
  to_epoch_ms: number
  expected_cells: number
  observed_cells: number
  missing_cells: number
  longest_gap_cells: number
  statistics: Record<string, string | null> | null
  reasons: string[]
}

export interface WindowPolicySummaryEvidence {
  id: string
  type: 'window_policy_summary'
  window_id: string
  from_epoch_ms: number
  to_epoch_ms: number
  business_verdict: AnalysisResult['policy_verdict']
  resource_verdict: AnalysisResult['policy_verdict']
  verdict: AnalysisResult['policy_verdict']
}

export interface ResourcePolicyCheckEvidence {
  id: string
  type: 'resource_policy_check'
  window_id: string
  rule_id: string
  series_id: string
  unit: string
  operator: 'gt' | 'lt'
  threshold: string
  effect: 'diagnostic' | 'sla'
  status: 'PASS' | 'FAIL' | 'NO_VERDICT'
  reason: string | null
}

export interface ResourceBindingEvidence {
  id: string
  type: 'resource_binding'
  [key: string]: unknown
}

export interface DiagnosticSummaryEvidence {
  id: string
  type: 'diagnostic_summary'
  status: 'COMPLETE' | 'LIMIT_EXCEEDED' | 'NOT_EVALUATED'
  pairs_tested: number
  pairs_evaluable: number
  anomalies_tested: number
  episodes_reported: number
  suppressed_short_episodes: number
  uncertainty: 'NOT_ESTIMATED'
  reasons: string[]
}

export interface CorrelationPairEvidence {
  id: string
  type: 'correlation_pair'
  pair_id: string
  window_id: string
  resource_series_id: string
  load_metric: string
  entity: string
  resource_unit: string
  load_unit: string
  from_epoch_ms: number
  to_epoch_ms: number
  expected_cells: number
  paired_cells: number
  lag_used_cells: number
  raw_rho: string | null
  partial_rho: string | null
  best_lag_ms: number | null
  best_lag_rho: string | null
  lag_profile: Array<{ lag_ms: number; rho: string | null }>
  status: 'CANDIDATE' | 'DESCRIPTIVE' | 'BELOW_EFFECT' | 'OPPOSITE_SIGN' | 'INSUFFICIENT_DATA'
  controls_requested: unknown[]
  controls_used: unknown[]
  controls_dropped: unknown[]
  sensitivity_without_achieved_rps: unknown
  uncertainty: 'NOT_ESTIMATED'
  reasons: string[]
}

export interface AnomalyCheckEvidence {
  id: string
  type: 'anomaly_check'
  rule_id: string
  window_id: string
  reference_window_id: string
  status: 'CANDIDATE' | 'NO_MATERIAL_CHANGE' | 'INSUFFICIENT_DATA'
  reference_median: string | null
  reference_mad: string | null
  reference_observed_cells: number
  reference_expected_cells: number
  observed_cells: number
  expected_cells: number
  episodes_reported: number
  suppressed_short_episodes: number
  reasons: string[]
}

export interface WindowMetricSummaryEvidence {
  id: string
  type: 'window_metric_summary'
  window_id: string
  from_epoch_ms: number
  to_epoch_ms: number
  sample_count: number
  error_count: number
  error_rate_ratio: ExactRatio | null
  throughput_rps: ExactRatio
  latency_ms: { p50: number | string | null; p95: number | string | null; p99: number | string | null; max: number | string | null }
  resource_bindings: unknown[]
}

export type AnalysisEvidence = MetricSummaryEvidence | PolicyCheckEvidence | DiagnosticEvidence | ResourceSummaryEvidence | WindowPolicySummaryEvidence | ResourcePolicyCheckEvidence | ResourceBindingEvidence | DiagnosticSummaryEvidence | CorrelationPairEvidence | AnomalyCheckEvidence | WindowMetricSummaryEvidence

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

export interface AnalysisReference {
  run_id: string
  analysis_id: string
}

export interface BaselineSelection {
  schema_version: 'local-baseline.v1'
  series: string
  mode: 'manual' | 'statistical'
  reference: AnalysisReference
  algorithm: 'median-rank-v1' | null
  candidates: AnalysisReference[]
  scores: Array<{ reference: AnalysisReference; score: number }>
}

export type BaselineRequest =
  | { mode: 'manual'; series: string; reference: AnalysisReference }
  | { mode: 'statistical'; series: string; candidates: AnalysisReference[]; comparable: true }

export interface BaselineComparison {
  baseline: BaselineSelection
  current: AnalysisReference
  comparability: 'UNCONFIRMED' | 'USER_CONFIRMED'
  metrics: ComparisonMetric[]
  window_comparison?: {
    status: 'NOT_EVALUATED' | 'DESCRIPTIVE' | 'NO_MATERIAL_CHANGE' | 'CANDIDATE' | 'INSUFFICIENT_DATA'
    baseline_window: string
    current_window: string
    baseline_sample_count: number | null
    current_sample_count: number | null
    baseline_duration_ms: number | null
    current_duration_ms: number | null
    min_change_percent: string
    min_error_rate_delta: string
    reasons: string[]
    metrics: Array<ComparisonMetric & {
      status: 'DESCRIPTIVE' | 'NO_MATERIAL_CHANGE' | 'CANDIDATE' | 'INSUFFICIENT_DATA'
      entity?: string
      resource_series_id?: string
      baseline_evidence_id?: string
      current_evidence_id?: string
    }>
  }
}

export interface WindowComparisonRequest {
  baseline_window: string
  current_window: string
  min_change_percent: string
  min_error_rate_delta: string
}

export interface ComparisonMetric {
  metric: string
  unit: string
  current: string | null
  baseline: string | null
  delta: string | null
  delta_percent: string | null
  reason: string | null
  percent_reason: string | null
}
