export type Theme = 'light' | 'dark'

// Конфигурация моделей ИИ-разбора (ADR 0023): адрес и подпись endpoint интерфейс не получает и не показывает.
export interface AdvisoryAiModel {
  id: string
  label: string
  measured: boolean
}

export interface AdvisoryAiConfig {
  default_model_id: string
  models: AdvisoryAiModel[]
}

export interface Bootstrap {
  csrf_token: string
  max_upload_bytes: number
  advisory_ai?: AdvisoryAiConfig | null
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
  policy_id?: string | null
  policy_verdict: AnalysisResult['policy_verdict']
  run_validity: AnalysisResult['run_validity']
  resource_arm?: string
  resource_snapshot_sha256?: string
}

export interface AnalysisPage {
  analyses: AnalysisSummary[]
  next_after: string | null
}

export interface SourceProfile {
  id: string
  source_kind: string
  transport: string
}

export interface SourcesResponse {
  profiles: SourceProfile[]
}

export interface PostgresCaptureResponse {
  schema_version: 'postgres-capture.v1'
  phase_json: string
  pg_profile_html_base64: string | null
}

export type SourceWindowV3 =
  | {
    origin: 'explicit'
    start_epoch_ms: number
    end_epoch_ms: number
    step_ms: number
  }
  | {
    origin: 'auto'
    step_ms: number
    margin_ms: number
    max_idle_gap_ms: number
  }

export type SourceRequest =
  | {
    schema_version: 'source-request.v1'
    profile_id: string
    start_epoch_ms: number
    end_epoch_ms: number
    step_ms: number
  }
  | {
    schema_version: 'source-request.v2'
    profile_ids: string[]
    start_epoch_ms: number
    end_epoch_ms: number
    step_ms: number
  }
  | {
    schema_version: 'source-request.v3'
    profile_ids: string[]
    window: SourceWindowV3
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
  min_samples?: number
}

export interface Policy {
  schema_version: 'policy.v1'
  policy_id: string
  defaults?: { sample_floor?: number; min_samples?: number; max_missing_fraction?: number; max_gap_cells?: number }
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
  sample_count?: number
  sample_floor?: number
  min_samples?: number
  sample_mode?: 'FULL' | 'SMALL_SAMPLE' | 'INSUFFICIENT' | 'NOT_GATED'
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
  aggregation: 'interval_mean' | 'interval_rate' | 'interval_max' | 'interval_min'
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
  // Только у проверок платформенных правил (ADR 0018): правило-источник, сервис и покрытие ячеек окна.
  platform_rule_id?: string
  service?: string
  expected_cells?: number
  observed_cells?: number
  missing_cells?: number
  longest_gap_cells?: number
}

export interface RuleWindowCheckEvidence {
  id: string
  type: 'rule_window_check'
  rule_id: string
  window_id: string
  status: 'NO_VERDICT'
  reason_code: string
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

export interface CorrelationHeadlineSelectionEvidence {
  id: string
  type: 'correlation_headline_selection'
  pair_id: string
  window_id: string
  method: string
  rng: string
  status: 'SELECTED' | 'NOT_SELECTED' | 'UNAVAILABLE'
  family_hypotheses: number
  bootstrap_replicates: number
  block_lengths_cells: number[]
  alpha: string
  p_value_b10: string | null
  p_value_b20: string | null
  max_p_value: string | null
  holm_adjusted_p_value: string | null
  selected: boolean
  reasons: string[]
  // Поля методов v2 и срезов K1, K2 (ADR 0022): у результатов прежних версий их нет.
  representation?: 'levels' | 'first_difference' | string
  stage_count?: number
  family_count?: number
  source_cells?: number
  analysed_points?: number
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

export interface SourceSummaryEvidence {
  id: string
  type: 'source_summary'
  status: 'COMPLETE' | 'PARTIAL' | 'FAILED'
  profile_id: string
  source_kind: string
  transport: string
  queries: Array<{ id: string; status: string; reason?: string }>
  request_count: number
  retries: number
  throttle_wait_ms: number
  cap_exceeded: boolean
  window_origin?: 'auto' | 'explicit'
  recognized_start_epoch_ms?: number
  recognized_end_epoch_ms?: number
  requested_margin_ms?: number
  applied_margin_ms?: number
  max_idle_gap_ms?: number
  detected_idle_gaps?: number
  longest_idle_gap_ms?: number | null
  auto_window_status?: string
  profiles?: SourceSummaryEvidence[]
}

export interface OpenSearchEvidence {
  id: string
  type: 'opensearch_errors'
  profile_id: string
  total_errors: number | string
  error_rate_per_minute: number | string
  coverage: { status: string; reasons: string[] }
  groups: Array<{
    service: string
    error_type: string
    count: number | string
    first_epoch_ms: number
    last_epoch_ms: number
    samples: Array<{ timestamp_epoch_ms: number; message: string; message_truncated: boolean; source_url: string }>
  }>
}

export interface PostgresContextEvidence {
  schema_version: 'postgres-context.v1'
  type: 'postgres_context'
  profile_id: string | null
  load_input_sha256: string
  start_epoch_ms: number | null
  end_epoch_ms: number | null
  pre_sha256: string | null
  post_sha256: string | null
  pg_profile_html_sha256?: string | null
  status: 'COMPLETE' | 'DEGRADED'
  reasons: string[]
  configuration_changes?: Array<{ name: string; pre: string | null; post: string | null }>
  tables: Array<{
    schema: string
    table: string
    stable_key: string[]
    status: 'COMPLETE' | 'DEGRADED'
    reasons: string[]
    row_count_delta: number | string | null
    inserted: number | string | null
    deleted: number | string | null
    updated: number | string | null
    changed_keys: Array<{ change: 'inserted' | 'deleted' | 'updated'; key: string[] }>
    keys_truncated: boolean
  }>
  statements: {
    status: 'COMPLETE' | 'DEGRADED'
    reasons: string[]
    rows: Array<{
      dbid: string
      userid: string
      queryid: string | null
      toplevel: boolean
      calls: number | string
      total_exec_time: number | string
      rows: number | string
      shared_blks_hit: number | string
      shared_blks_read: number | string
      temp_blks_written: number | string
    }>
    unmatched_pre: Array<{ dbid: string; userid: string; queryid: string | null; toplevel: boolean }>
    unmatched_post: Array<{ dbid: string; userid: string; queryid: string | null; toplevel: boolean }>
  }
  pg_profile: {
    status: 'COMPLETE' | 'DEGRADED'
    reasons: string[]
    pre_report_sha256: string | null
    post_report_sha256: string | null
  }
}

export interface TrendCheckEvidence {
  id: string
  type: 'trend_check'
  check_id: string
  series_id: string
  metric: string | null
  unit: string | null
  entity: string | null
  window_id: string
  window_from_epoch_ms: number | null
  window_to_epoch_ms: number | null
  declared_direction: 'increase' | 'decrease' | 'either'
  status: 'TREND_OBSERVED' | 'NO_MATERIAL_TREND' | 'INSUFFICIENT_CELLS' | 'UNAVAILABLE'
  min_cells: number
  expected_cells: number
  observed_cells: number
  missing_cells: number
  longest_gap_cells: number
  median: string | null
  slope_per_second: string | null
  split_half_shift: string | null
  magnitude_gate: { min_slope_units_per_second: string; min_split_half_shift_pct: string; required_split_half_shift_units: string | null }
  observed_direction: 'increase' | 'decrease' | 'flat' | null
  method: 'slope-materiality.v1'
  uncertainty: 'NOT_ESTIMATED'
  reasons: string[]
}

export interface TrendSummaryEvidence {
  id: string
  type: 'trend_summary'
  checks_total: number
  observed: number
  not_material: number
  insufficient: number
  unavailable: number
  method: 'slope-materiality.v1'
  uncertainty: 'NOT_ESTIMATED'
}

export interface ResourceTrendFinding {
  id: string
  type: 'resource_trend'
  check_id: string
  series_id: string
  metric: string
  unit: string
  entity: string
  window_id: string
  observed_direction: 'increase' | 'decrease' | 'flat'
  from_epoch_ms: number
  to_epoch_ms: number
  expected_cells: number
  observed_cells: number
  median: string
  slope_per_second: string
  split_half_shift: string
  effect: 'diagnostic'
  uncertainty: 'NOT_ESTIMATED'
  evidence_id: string
}

export type AnalysisEvidence = MetricSummaryEvidence | PolicyCheckEvidence | DiagnosticEvidence | ResourceSummaryEvidence | WindowPolicySummaryEvidence | ResourcePolicyCheckEvidence | RuleWindowCheckEvidence | ResourceBindingEvidence | DiagnosticSummaryEvidence | CorrelationPairEvidence | CorrelationHeadlineSelectionEvidence | AnomalyCheckEvidence | WindowMetricSummaryEvidence | SourceSummaryEvidence | OpenSearchEvidence | PostgresContextEvidence | TrendCheckEvidence | TrendSummaryEvidence

export interface CapacityStage {
  id: string
  target: number | string
  achieved: number | string | null
  achieved_statistic: string
  observed_min: number | string | null
  observed_max: number | string | null
  complete_bins: number | null
  expected_bins: number | null
  target_tolerance_ratio: number | string
  verified_bound_load: number | string | null
  verdict: string
  reasons: string[]
  evidence_refs: string[]
}

export interface CapacitySummary {
  schema_version: 'capacity.v1'
  load_axis: string
  unit: string
  stages: CapacityStage[]
  bound_type: string
  lower_inclusive: number | string | null
  upper_exclusive: number | string | null
  policy_verdict: AnalysisResult['policy_verdict']
  reasons: string[]
  capacity_knee: number | string | null
  knee_reason: string
}

export interface AnalysisResult {
  schema_version: 'analysis-result.v1'
  run_id: string
  analysis_mode: string
  run_validity: 'VALID' | 'DEGRADED' | 'INVALID'
  policy_verdict: 'PASS' | 'FAIL' | 'NO_POLICY' | 'NO_VERDICT'
  analysis_coverage: { status: 'COMPLETE' | 'INCOMPLETE'; reasons: string[] }
  findings: Array<Record<string, unknown>>
  evidence: AnalysisEvidence[]
  capacity_summary?: CapacitySummary
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

// One entry of `GET /api/baseline` `baselines`: the active baseline of a (series, arm) pair.
export interface BaselineSlotView {
  series: string
  arm: string | null
  source: 'SLOT' | 'LEGACY'
  baseline: BaselineSelection
}

export type BaselineRequest =
  | { mode: 'manual'; series: string; reference: AnalysisReference }
  | { mode: 'statistical'; series: string; candidates: AnalysisReference[]; comparable: true }

export type BaselineConditionDecision = 'CONFIRMED' | 'NOT_CONFIRMED' | 'UNKNOWN'

export interface BaselineConditionWindows {
  baseline_window: string
  current_window: string
}

export interface BaselineCondition {
  schema_version: 'local-baseline-conditions.v1'
  baseline: AnalysisReference
  current: AnalysisReference
  windows: BaselineConditionWindows | null
  decision: BaselineConditionDecision
  provenance: 'EXPLICIT_LOCAL_ACTION'
  updated_at: string
}

export type BaselineComparisonWarning =
  | 'BASELINE_IS_CURRENT_ANALYSIS'
  | 'BASELINE_IS_CURRENT_RUN'
  | 'CURRENT_IN_CANDIDATE_SET'
  | 'BASELINE_SMALL_SAMPLE'

export interface BaselineComparison {
  baseline: BaselineSelection
  current: AnalysisReference
  comparability: 'UNCONFIRMED' | 'USER_CONFIRMED'
  warnings: BaselineComparisonWarning[]
  conditions: BaselineCondition | null
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

export interface AdviceJob {
  job_id: string
  run_id: string
  analysis_id: string
  state: 'QUEUED' | 'PROCESSING' | 'COMPLETE' | 'FAILED' | 'UNAVAILABLE' | 'CANCELLED'
  reused: boolean | null
  failure: string | null
  unavailable_reason: string | null
  model_id?: string | null
}

export interface AdviceDocument {
  advisory: true
  run_id: string
  analysis_id: string
  // Необязательные поля: схема ai-advice.v1 расширяется аддитивно (ADR 0021, Д4).
  provenance?: {
    invocation_id?: string
    runner_id?: string
    runner_version?: string
    model_id?: string
    prompt_version?: string
    prompt_sha256?: string
    duration_ms?: number
    provider_requests?: number
  }
  output: {
    summary: string
    hypotheses: Array<{ rank: number; observation: string; possible_explanation: string; recommended_check: string; evidence_refs: string[] }>
    recommendations: Array<{ rank: number; action: string; rationale: string; evidence_refs: string[] }>
    caveats: string[]
  }
}

export interface JenkinsProfile {
  id: string
  controller: string
  job_path: string
  parameter_names: string[]
  artifact_paths: string[]
}
export interface JenkinsAttempt {
  attempt_id: string
  status: string
  build_number: number | null
  failure_code: string | null
  artifact: { relative_path: string; size_bytes: number; sha256: string } | null
}

export interface ResourceSeriesEntry {
  id: string
  metric: string
  unit: string
  entity: string
  role: 'system' | 'generator'
  aggregation: 'interval_mean' | 'interval_rate' | 'interval_max' | 'interval_min'
  reducer: 'mean' | 'max' | 'min'
  labels: Record<string, string>
  observed_cells: number
}

export interface ResourceSeriesCatalog {
  schema_version: 'resource-series.v1'
  kind: 'catalog'
  resource_snapshot_sha256: string
  numeric_encoding: 'ieee754-double'
  grid: { start_epoch_ms: number; step_ms: number; point_count: number }
  windows: Array<{ id: string; from_epoch_ms: number; to_epoch_ms: number }>
  series: ResourceSeriesEntry[]
  next_after: string | null
}

export interface ResourceSeriesValuesSeries {
  id: string
  aggregation: 'interval_mean' | 'interval_rate' | 'interval_max' | 'interval_min'
  reducer: 'mean' | 'max' | 'min'
  values: Array<number | null>
  observed?: number[]
}

export interface ResourceSeriesValues {
  schema_version: 'resource-series.v1'
  kind: 'values'
  resource_snapshot_sha256: string
  numeric_encoding: 'ieee754-double'
  grid: {
    start_epoch_ms: number
    source_step_ms: number
    step_ms: number
    first_cell_start_ms: number
    cell_count: number
    source_cells_per_cell: number
    last_cell_source_cells: number
  }
  series: ResourceSeriesValuesSeries[]
  next_from_ms: number | null
}
