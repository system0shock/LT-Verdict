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
  accepted_at?: string | null
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
  // Метка плеча профиля (source-connections.v3); у профилей без неё поля нет.
  arm?: string
}

export interface SourcesResponse {
  profiles: SourceProfile[]
}

export interface SourceReloadResponse {
  schema_version: 'source-reload.v1'
  status: 'RELOADED'
  profile_count: number
  profiles: string[]
  added: string[]
  removed: string[]
  changed: string[]
  revision: string
}

export interface SourceCheckItem {
  id: 'config' | 'url' | 'credentials' | 'tls' | 'rules' | 'governor'
  status: 'OK' | 'WARN' | 'FAIL' | 'SKIPPED'
  code: string | null
  auth_kind?: 'none' | 'bearer' | 'token' | 'basic'
}

export interface SourceCheckProfile {
  profile_id: string
  source_kind: string
  transport: string | null
  arm: string | null
  query_count: number
  rule_count: number
  checks: SourceCheckItem[]
}

export interface SourceCheckResponse {
  schema_version: 'source-check.v1'
  profiles: SourceCheckProfile[]
}

// Зонд принимает только сохранённый query_id из профиля (ADR 0033, Q1).
export interface SourceProbeRequest {
  profile_id: string
  query_id: string
  window_ms?: number
  step_ms?: number
  end_epoch_ms?: number
}

export interface SourceProbeSeries {
  labels: Record<string, string>
  observed_cells: number
  expected_cells: number
}

export interface SourceProbeLabelKey {
  key: string
  distinct_values: number
  sample_values: string[]
  truncated: boolean
}

export interface SourceProbeResponse {
  schema_version: 'source-probe.v1'
  profile_id: string
  source_kind: string
  transport: string
  mode: 'profile_query'
  query_id: string
  expression_sha256: string
  window: {
    start_epoch_ms: number
    end_epoch_ms: number
    step_ms: number
    cells: number
  }
  status: 'OK' | 'FAILED'
  code: string | null
  http_status: number | null
  series_count: number | null
  series: SourceProbeSeries[]
  series_truncated: boolean
  label_keys: SourceProbeLabelKey[]
  label_keys_truncated: boolean
  redacted_count: number
  warnings: string[]
  decoder_check: { accepted: boolean; code: string | null } | null
  request_count: number
  elapsed_ms: number
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

export type SourceStepMode = 'fixed' | 'auto'

export type SourceWindowV4 =
  | {
    origin: 'explicit'
    start_epoch_ms: number
    end_epoch_ms: number
    step_ms: number
    step_mode: SourceStepMode
  }
  | {
    origin: 'auto'
    step_ms: number
    step_mode: SourceStepMode
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
  | {
    schema_version: 'source-request.v4'
    profile_ids: string[]
    window: SourceWindowV4
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
  window_id?: string
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
  // The engine writes both now (min_samples only when the policy sets a default); stored results of older engines lack them.
  sample_count?: number
  min_samples?: number
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

// ADR 0030: the declared load stages of a run whose verdict is by the steady window. Checked against types.stage-items.generated.ts.
export interface StageBindingEvidence {
  id: string
  type: 'stage_binding'
  mode: string
  declaration_sha256: string
  run_from_epoch_ms: number
  run_to_epoch_ms: number
  evaluated_window_ids: string[]
  evaluated_millis: number
  excluded_millis: number
  stages: Array<{
    id: string
    role: string
    from_offset_ms: number
    to_offset_ms: number
    from_epoch_ms: number
    to_epoch_ms: number
    clipped_to_run_end?: boolean
    // ADR 0037: only for steady stages of an analysis with a resource snapshot
    resource_from_epoch_ms?: number
    resource_to_epoch_ms?: number
    resource_partial?: boolean
  }>
  verdict_scope: string
}

export interface SourceSummaryEvidence {
  id: string
  type: 'source_summary'
  status: 'COMPLETE' | 'PARTIAL' | 'FAILED'
  profile_id: string
  source_kind: string
  transport: string
  arm?: string
  start_epoch_ms?: number
  end_epoch_ms?: number
  step_ms?: number
  queries: Array<{ id: string; status: string; reason?: string; expression_sha256?: string }>
  rule_spans?: Array<{ rule_id: string; declared_span_ms: number; step_ms: number; cells: number; effective_span_ms: number }>
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
  // Поля source-request.v4 (ADR 0014); applied_step_ms есть только в предупреждении об огрублении.
  step_origin?: 'auto' | 'explicit'
  requested_step_ms?: number
  series_count?: number
  cell_budget?: number
  cells_per_series?: number
  warnings?: Array<{ code: string; requested_step_ms: number; applied_step_ms: number; series: Array<{ id: string; aggregation: string }> }>
  profiles?: SourceSummaryEvidence[]
}

export interface OpenSearchEvidence {
  id: string
  type: 'opensearch_errors'
  schema_version?: string
  load_input_sha256?: string
  profile_id: string
  start_epoch_ms?: number
  end_epoch_ms?: number
  step_ms?: number
  total_errors: number | string
  error_rate_per_minute: number | string
  timeline?: Array<{ from_epoch_ms: number; to_epoch_ms: number; count: number | string; rate_per_minute: number | string }>
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

// ADR 0026: calibrated diagnostic of the capacity knee; it never changes the capacity bound or the verdict.
export interface CapacityKneeDiagnosticEvidence {
  id: string
  type: 'capacity_knee_diagnostic'
  method: string
  metric?: string
  load_axis?: string
  unit?: string
  status: 'DETECTED' | 'NOT_DETECTED'
  confidence: string
  calibrated: boolean
  diagnostic_only?: boolean
  last_stable_stage_id: string | null
  last_stable_load: number | string | null
  first_degraded_stage_id: string | null
  first_degraded_load: number | string | null
  sse_ratio?: number | string | null
  excess_factor?: number | string | null
  reasons: string[]
  points?: Array<{ stage_id: string; load: number | string; value: number }>
  parameters?: {
    min_stages: number
    min_points_before_knee: number
    max_sse_ratio: string
    min_excess_factor: string
    noise_multiplier: number
  }
}

export type AnalysisEvidence = MetricSummaryEvidence | PolicyCheckEvidence | DiagnosticEvidence | ResourceSummaryEvidence | WindowPolicySummaryEvidence | ResourcePolicyCheckEvidence | RuleWindowCheckEvidence | ResourceBindingEvidence | DiagnosticSummaryEvidence | CorrelationPairEvidence | CorrelationHeadlineSelectionEvidence | AnomalyCheckEvidence | WindowMetricSummaryEvidence | StageBindingEvidence | SourceSummaryEvidence | OpenSearchEvidence | PostgresContextEvidence | TrendCheckEvidence | TrendSummaryEvidence | CapacityKneeDiagnosticEvidence

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

export type IncidentScope =
  | { kind: 'overall' }
  | {
    kind: 'transaction'
    label: string
    group_path?: string[]
    sample_kind?: 'JMETER_SAMPLER' | 'JMETER_CONTAINER' | 'GATLING_REQUEST' | 'GATLING_GROUP'
  }
  | { kind: 'entity'; entity: string }

export interface IncidentNegativeEvidence {
  check:
    | 'OTHER_POLICY_CHECKS_PASSED'
    | 'RESOURCE_RULES_WITHIN_LIMITS'
    | 'GENERATOR_RESOURCES_WITHIN_LIMITS'
    | 'NO_ANOMALY_EPISODES'
    | 'NO_MATERIAL_TREND'
    | 'POLICY_NOT_EVALUATED'
    | 'RESOURCE_DATA_NOT_PROVIDED'
    | 'CHECKS_NOT_EVALUATED'
  outcome: 'NOT_CONFIRMED' | 'NOT_EVALUATED'
  reason_code?: string
  text: string
  evidence_ids: string[]
}

export interface IncidentNextCheck {
  check:
    | 'COMPARE_WITH_BASELINE'
    | 'OPEN_RESOURCE_SERIES'
    | 'OPEN_SAME_WINDOW_SIGNALS'
    | 'PROVIDE_RESOURCE_SNAPSHOT'
    | 'COMPLETE_NOT_EVALUATED_CHECKS'
  text: string
  evidence_ids: string[]
}

export interface IncidentCoincidentLink {
  incident_id: string
  basis: 'SAME_WINDOW' | 'INTERVAL_OVERLAP'
}

export interface Incident {
  id: string
  rank: number
  in_overview: boolean
  priority: 'HIGH' | 'MEDIUM' | 'LOW'
  priority_key: {
    tier: number
    finding_count: number
    first_epoch_ms: number | null
  }
  title: string
  summary: string
  scope: IncidentScope
  window_id: string | null
  interval_basis: 'FINDINGS' | 'WINDOW' | 'UNKNOWN'
  interval: { from_epoch_ms: number; to_epoch_ms: number } | null
  grouping: {
    rule: 'incident-grouping.v1'
    key: {
      family: 'TRANSACTION' | 'RESOURCE'
      window_id: string | null
      scope: IncidentScope
      cluster_from_epoch_ms: number | null
    }
  }
  finding_count: number
  finding_types: string[]
  finding_ids: string[]
  evidence_ids: string[]
  refs_truncated: boolean
  negative_evidence: IncidentNegativeEvidence[]
  next_checks: IncidentNextCheck[]
  coincident_with: IncidentCoincidentLink[]
}

export interface Incidents {
  schema_version: 'incident.v1'
  method: 'incident-synthesis.v1'
  status: 'EVALUATED' | 'NOT_EVALUATED'
  reason_code?: 'RUN_NOT_VALID' | string
  overview_limit: number
  total_count: number
  omitted_count: number
  items: Incident[]
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
  incidents?: Incidents
}

export interface Bucket {
  bucket_start_ms: number
  sample_count: number
  error_count: number
  p50_latency_ms?: number
  p95_latency_ms: number
  p99_latency_ms?: number
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
  | 'BASELINE_NOT_PASS'
  | 'BASELINE_SMALL_SAMPLE'
  | 'POLICY_DIFFERS'
  | 'PROFILE_MISMATCH'
  | 'WHOLE_RUN_METRICS_WITH_STAGES'

export interface BaselineComparison {
  baseline: BaselineSelection
  current: AnalysisReference
  comparability: 'UNCONFIRMED' | 'USER_CONFIRMED'
  warnings: BaselineComparisonWarning[]
  // Заявленные профили условий релизов (ADR 0019); null, если анализы не сопоставлены с релизами или профиль не заявлен.
  profile?: {
    status: 'MATCH' | 'MISMATCH'
    differing_fields: string[]
    baseline_release_id: string
    current_release_id: string
  } | null
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
  detail?: string | null
  model_id?: string | null
}

export interface AdviceDocument {
  advisory: true
  run_id: string
  analysis_id: string
  // Необязательные поля: схема ai-advice.v1 расширяется аддитивно (ADR 0021, Д4).
  provenance?: {
    invocation_id?: string
    provenance_kind?: 'runner' | 'self-reported'
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

// Релизы (ADR 0019): приватная запись о релизе протокола с копиями фактов анализов; метрики в записи не хранятся.
export type ReleaseAnalysisState = 'OK' | 'MISSING' | 'CORRUPT'

export interface ReleaseProfile {
  scenario_mix: string | null
  environment_dataset: string | null
  load_model: string | null
  targets_stages: string | null
  pacing: string | null
  generator_limits: string | null
}

export interface ReleaseAnalysis {
  analysis_id: string
  arm: string | null
  coverage_reasons: string[]
  coverage_status: 'COMPLETE' | 'INCOMPLETE'
  policy_sha256: string
  policy_verdict: 'PASS' | 'FAIL' | 'NO_POLICY' | 'NO_VERDICT'
  run_validity: 'VALID' | 'DEGRADED' | 'INVALID'
  analysis_state: ReleaseAnalysisState
  baseline_eligible: boolean
  ineligible_reasons: string[]
}

export interface Release {
  schema_version: 'local-release.v1'
  release_id: string
  series: string
  label: string
  run_id: string
  started_at: string
  analyses: ReleaseAnalysis[]
  profile: ReleaseProfile | null
  notes: string | null
  created_at: string
  updated_at: string
  baseline_eligible: boolean
  ineligible_reasons: string[]
}

export interface ReleaseList {
  releases: Release[]
  next_after: string | null
  series_summary: Array<{ series: string; count: number }>
  corrupt_count: number
  corrupt_names: Array<{ name: string; reason: string }>
}

export interface ReleaseRequest {
  series: string
  label: string
  run_id: string
  analyses: Array<{ analysis_id: string }>
  profile: ReleaseProfile | null
  notes: string | null
}

export type ReleaseUpdate = Omit<ReleaseRequest, 'series' | 'run_id'>

// Предложение стадий нагрузки (ADR 0036, D10, D12, D14): локальный маршрут без изменения analysis-result.v1.
export type LoadStageRole = 'steady' | 'excluded'

export interface LoadStageV1 {
  id: string
  role: LoadStageRole
  from_offset_ms: number
  to_offset_ms: number
}

export interface LoadStagesV1 {
  schema_version: 'load-stages.v1'
  stages: LoadStageV1[]
}

export type StageProposalStatus = 'DETECTED' | 'NOT_DETECTED'
export type StageProposalConfidence = 'HIGH' | 'MEDIUM' | 'LOW'
export type StageSegmentKind = 'IDLE' | 'FLAT' | 'RISING' | 'FALLING' | 'UNSTABLE'
export type StageStretchVerdict = 'DEGRADED' | 'HEALTHY' | 'NOT_JUDGED'

export interface StageStretchFact {
  kind: 'TAIL' | 'AFTER_STEADY' | 'GAP' | 'STEP' | string
  from_offset_ms: number
  to_offset_ms: number
  reference_id: string
  samples: number
  failures: number
  error_ratio: number
  mean_ms: number
  reference_samples: number
  reference_failures: number
  reference_error_ratio: number
  reference_mean_ms: number
  verdict: StageStretchVerdict
}

export interface StageProposalResponse {
  status: StageProposalStatus
  reason: string | null
  detail: string | null
  method: string
  steady_steps: 'top' | 'all'
  confidence: StageProposalConfidence | null
  calibrated: boolean
  run_start_epoch_ms: number | null
  run_end_epoch_ms: number | null
  cell_ms: number | null
  cells: number | null
  noise_rps: number | null
  segments: Array<{
    from_offset_ms: number
    to_offset_ms: number
    kind: StageSegmentKind
    level_rps: number
    drift: number
    residual_cv: number
  }>
  boundaries: Array<{
    stage_id: string
    basis: string
    level_rps: number | null
    from_contrast_sigma: number | null
    to_contrast_sigma: number | null
    trimmed_cells: number
  }>
  stretches: StageStretchFact[]
  warnings: Array<{
    code: string
    stage_id: string | null
    at_offset_ms: number
    from_rps: number | null
    to_rps: number | null
    duration_ms: number | null
    stretch: StageStretchFact | null
  }>
  proposal: LoadStagesV1 | null
}
