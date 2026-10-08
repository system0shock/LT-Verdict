// GENERATED from the @Serializable models in src/main/kotlin/io/ltverdict/core/AnalysisItems.kt.
// Do not edit. Regenerate: LTV_UPDATE_GENERATED_TYPES=1 gradlew test --tests io.ltverdict.core.TypeScriptGeneratorItemsTest
// Describes the findings and evidence items the engine writes (optional fields are omitted, never null);
// it is not a validator. Only the families already typed in Kotlin are here.

export type AnalysisFinding = DiagnosticFinding | PolicyFailureFinding | ResourceThresholdViolationFinding

export interface DiagnosticFinding {
  type: 'diagnostic'
  id: string
  code: string
  evidence_id: string
}

export interface PolicyFailureFinding {
  type: 'policy_failure'
  id: string
  window_id?: string
  rule_id: string
  evidence_id: string
}

export interface ResourceThresholdViolationFinding {
  type: 'resource_threshold_violation'
  id: string
  window_id: string
  rule_id: string
  series_id: string
  entity: string
  unit: string
  from_epoch_ms: number
  to_epoch_ms: number
  cell_count: number
  observed_min: string
  observed_max: string
  presumed?: boolean
  evidence_id: string
}

export type AnalysisEvidence = DiagnosticEvidence | MetricSummaryEvidence | PolicyCheckEvidence | ResourcePolicyCheckEvidence | ResourceSummaryEvidence | RuleWindowCheckEvidence | WindowPolicySummaryEvidence

export interface DiagnosticEvidence {
  type: 'diagnostic'
  id: string
  code: string
  message: string
  source_offset?: number
}

export interface MetricSummaryEvidence {
  type: 'metric_summary'
  id: string
  window_id?: string
  scope: MetricScope
  sample_count: number
  error_count: number
  error_rate_ratio: ExactRatioDocument | null
  throughput_rps: ExactRatioDocument
  latency_ms: LatencyDocument
}

export type MetricScope = OverallMetricScope | TransactionMetricScope

export interface OverallMetricScope {
  kind: 'overall'
}

export interface TransactionMetricScope {
  kind: 'transaction'
  label: string
  group_path?: Array<string>
  sample_kind?: string
}

export interface ExactRatioDocument {
  numerator: number
  denominator: number
}

export interface LatencyDocument {
  p50: number
  p95: number
  p99: number
  max: number
}

export interface PolicyCheckEvidence {
  type: 'policy_check'
  id: string
  window_id?: string
  scope?: MetricScope
  rule_id: string
  metric: string
  operator: string
  threshold: number
  status: string
  metric_evidence_id?: string
  observed?: unknown
  reason_code?: string
  sample_count?: number
  sample_floor?: number
  min_samples?: number
  sample_mode?: string
}

export interface ResourcePolicyCheckEvidence {
  type: 'resource_policy_check'
  id: string
  window_id: string
  rule_id: string
  series_id: string
  unit: string
  operator: string
  threshold: string
  effect: string
  status: string
  reason: string | null
  platform_rule_id?: string
  service?: string
  expected_cells?: number
  observed_cells?: number
  missing_cells?: number
  longest_gap_cells?: number
}

export interface ResourceSummaryEvidence {
  type: 'resource_summary'
  id: string
  series_id: string
  metric: string
  unit: string
  entity: string
  role: string
  aggregation: string
  window_id: string
  from_epoch_ms: number
  to_epoch_ms: number
  expected_cells: number
  observed_cells: number
  missing_cells: number
  longest_gap_cells: number
  statistics: ResourceStatisticsDocument
  reasons: Array<string>
}

export interface ResourceStatisticsDocument {
  min: string | null
  max: string | null
  mean: string | null
  median: string | null
  q05: string | null
  q25: string | null
  q75: string | null
  q95: string | null
  iqr: string | null
  mad: string | null
  sample_standard_deviation: string | null
  slope_per_second: string | null
  split_half_shift: string | null
}

export interface RuleWindowCheckEvidence {
  type: 'rule_window_check'
  id: string
  rule_id: string
  window_id: string
  status: string
  reason_code: string
}

export interface WindowPolicySummaryEvidence {
  type: 'window_policy_summary'
  id: string
  window_id: string
  from_epoch_ms: number
  to_epoch_ms: number
  business_verdict: PolicyVerdict
  resource_verdict: PolicyVerdict
  verdict: PolicyVerdict
  sample_count: number
  min_samples?: number
}

export type PolicyVerdict = 'PASS' | 'FAIL' | 'NO_POLICY' | 'NO_VERDICT'
