// GENERATED from the @Serializable models in src/main/kotlin/io/ltverdict/core/DerivedItems.kt.
// Do not edit. Regenerate with
// LTV_UPDATE_GENERATED_TYPES=1 gradlew test --tests io.ltverdict.core.TypeScriptGeneratorDerivedItemsTest
// Describes the diagnostic, capacity and trend findings and evidence items the engine writes (a field is never
// omitted: an absent value is an explicit null); it is not a validator. The policy and resource items are in
// types.items.generated.ts.

export type DerivedFinding = AnomalyEpisodeFinding | CorrelationCandidateFinding | ResourceTrendFinding

export interface AnomalyEpisodeFinding {
  type: 'anomaly_episode'
  id: string
  rule_id: string
  window_id: string
  reference_window_id: string
  metric: string
  unit: string
  entity: string
  from_epoch_ms: number
  to_epoch_ms: number
  duration_ms: number
  direction: string
  reference_median: string
  reference_mad: string
  observed_min: string
  observed_max: string
  max_abs_delta: string
  evidence_id: string
  reasons: Array<string>
}

export interface CorrelationCandidateFinding {
  type: 'correlation_candidate'
  id: string
  pair_id: string
  window_id: string
  evidence_id: string
  uncertainty: string
}

export interface ResourceTrendFinding {
  type: 'resource_trend'
  id: string
  check_id: string
  series_id: string
  metric: string
  unit: string
  entity: string
  window_id: string
  observed_direction: string
  from_epoch_ms: number
  to_epoch_ms: number
  expected_cells: number
  observed_cells: number
  median: string
  slope_per_second: string
  split_half_shift: string
  effect: string
  uncertainty: string
  evidence_id: string
}

export type DerivedEvidence = AnomalyCheckEvidence | CapacityKneeDiagnosticEvidence | CapacitySummaryEvidence | CorrelationHeadlineSelectionEvidence | CorrelationPairEvidence | DiagnosticSummaryEvidence | TrendCheckEvidence | TrendSummaryEvidence | WindowMetricSummaryEvidence

export interface AnomalyCheckEvidence {
  type: 'anomaly_check'
  id: string
  rule_id: string
  window_id: string
  reference_window_id: string
  status: string
  reference_median: string | null
  reference_mad: string | null
  reference_observed_cells: number
  reference_expected_cells: number
  observed_cells: number
  expected_cells: number
  episodes_reported: number
  suppressed_short_episodes: number
  reasons: Array<string>
}

export interface CapacityKneeDiagnosticEvidence {
  type: 'capacity_knee_diagnostic'
  id: string
  method: string
  metric: string
  load_axis: string
  unit: string
  status: string
  confidence: string
  calibrated: boolean
  diagnostic_only: boolean
  last_stable_stage_id: string | null
  last_stable_load: number | null
  first_degraded_stage_id: string | null
  first_degraded_load: number | null
  sse_ratio: number | null
  excess_factor: number | null
  reasons: Array<string>
  points: Array<KneePointDocument>
  parameters: KneeParametersDocument
}

export interface KneePointDocument {
  stage_id: string
  load: number
  value: number
}

export interface KneeParametersDocument {
  min_stages: number
  min_points_before_knee: number
  max_sse_ratio: string
  min_excess_factor: string
  noise_multiplier: number
}

export interface CapacitySummaryEvidence {
  type: 'capacity_summary'
  id: string
  schema_version: string
  load_axis: string
  unit: string
  stages: Array<CapacityStageDocument>
  bound_type: string
  lower_inclusive: number | null
  upper_exclusive: number | null
  policy_verdict: PolicyVerdict
  reasons: Array<string>
  capacity_knee: string | null
  knee_reason: string
}

export interface CapacityStageDocument {
  id: string
  target: number
  achieved: number | null
  achieved_statistic: string
  observed_min: number | null
  observed_max: number | null
  complete_bins: number
  expected_bins: number
  target_tolerance_ratio: number
  verified_bound_load: number | null
  verdict: string
  reasons: Array<string>
  evidence_refs: Array<string>
}

export type PolicyVerdict = 'PASS' | 'FAIL' | 'NO_POLICY' | 'NO_VERDICT'

export interface CorrelationHeadlineSelectionEvidence {
  type: 'correlation_headline_selection'
  id: string
  pair_id: string
  window_id: string
  method: string
  rng: string
  status: string
  family_hypotheses: number
  family_count: number
  representation: string
  source_cells: number
  analysed_points: number
  bootstrap_replicates: number
  block_lengths_cells: Array<number>
  alpha: string
  p_value_b10: string | null
  p_value_b20: string | null
  max_p_value: string | null
  holm_adjusted_p_value: string | null
  selected: boolean
  reasons: Array<string>
}

export interface CorrelationPairEvidence {
  type: 'correlation_pair'
  id: string
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
  best_lag_ms: number
  best_lag_rho: string | null
  lag_profile: Array<LagProfileEntry>
  status: string
  controls_requested: Array<string>
  controls_used: Array<string>
  controls_dropped: Array<string>
  sensitivity_without_achieved_rps: string | null
  reasons: Array<string>
  uncertainty: string
}

export interface LagProfileEntry {
  lag_ms: number
  rho: string | null
}

export interface DiagnosticSummaryEvidence {
  type: 'diagnostic_summary'
  id: string
  status: string
  pairs_tested: number
  pairs_evaluable: number
  anomalies_tested: number
  episodes_reported: number
  suppressed_short_episodes: number
  uncertainty: string
  reasons: Array<string>
}

export interface TrendCheckEvidence {
  type: 'trend_check'
  id: string
  check_id: string
  series_id: string
  metric: string | null
  unit: string | null
  entity: string | null
  window_id: string
  window_from_epoch_ms: number | null
  window_to_epoch_ms: number | null
  declared_direction: string
  status: string
  min_cells: number
  expected_cells: number
  observed_cells: number
  missing_cells: number
  longest_gap_cells: number
  median: string | null
  slope_per_second: string | null
  split_half_shift: string | null
  magnitude_gate: MagnitudeGateDocument
  observed_direction: string | null
  method: string
  uncertainty: string
  reasons: Array<string>
}

export interface MagnitudeGateDocument {
  min_slope_units_per_second: string
  min_split_half_shift_pct: string
  required_split_half_shift_units: string | null
}

export interface TrendSummaryEvidence {
  type: 'trend_summary'
  id: string
  checks_total: number
  observed: number
  not_material: number
  insufficient: number
  unavailable: number
  method: string
  uncertainty: string
}

export interface WindowMetricSummaryEvidence {
  type: 'window_metric_summary'
  id: string
  window_id: string
  from_epoch_ms: number
  to_epoch_ms: number
  sample_count: number
  error_count: number
  error_rate_ratio: ExactRatioDocument | null
  throughput_rps: ExactRatioDocument
  latency_ms: NullableLatencyDocument
  resource_bindings: Array<WindowResourceBindingDocument>
}

export interface ExactRatioDocument {
  numerator: number
  denominator: number
}

export interface NullableLatencyDocument {
  p50: number | null
  p95: number | null
  p99: number | null
  max: number | null
}

export interface WindowResourceBindingDocument {
  series_id: string
  metric: string
  unit: string
  entity: string
  role: string
  aggregation: string
  labels: Record<string, string>
}
