// GENERATED from the @Serializable models in src/main/kotlin/io/ltverdict/core/AnalysisDocuments.kt.
// Do not edit. Regenerate: LTV_UPDATE_GENERATED_TYPES=1 gradlew test --tests io.ltverdict.core.TypeScriptGeneratorTest
// Describes the documents the engine writes (optional fields are omitted, never null); it is not a validator.

export interface AnalysisResultDocument {
  schema_version: string
  run_id: string
  analysis_mode: AnalysisMode
  run_validity: RunValidity
  policy_verdict: PolicyVerdict
  analysis_coverage: AnalysisCoverageDocument
  findings: Array<Record<string, unknown>>
  evidence: Array<Record<string, unknown>>
  capacity_summary?: Record<string, unknown>
}

export type AnalysisMode = 'standard' | 'capacity_step'

export type RunValidity = 'VALID' | 'DEGRADED' | 'INVALID'

export type PolicyVerdict = 'PASS' | 'FAIL' | 'NO_POLICY' | 'NO_VERDICT'

export interface AnalysisCoverageDocument {
  status: AnalysisCoverageStatus
  reasons: Array<string>
}

export type AnalysisCoverageStatus = 'COMPLETE' | 'INCOMPLETE'

export interface AnalysisIdentityDocument {
  schema_version: string
  run_id: string
  source_type: string
  input_sha256: string
  policy_sha256: string
  verdict_gates?: Record<string, string>
  resource_snapshot_sha256?: string
  resource_config_sha256?: string
  resource_arm?: string
  diagnostic_plan_sha256?: string
  source_acquisition_sha256?: string
  postgres_input_sha256?: string
  capacity_plan_sha256?: string
  capacity_plan_version?: string
  capacity_knee_method?: string
  trend_plan_sha256?: string
  trend_plan_version?: string
  pod_view_sha256?: string
  pod_view_version?: string
  engine: ComponentRef
  parsers: Array<ComponentRef>
  modules: Array<ComponentRef>
  input_versions: InputVersionsDocument
  outputs: OutputsDocument
  histogram: HistogramDocument
  normalization: NormalizationDocument
  limits: Record<string, string>
}

export interface ComponentRef {
  id: string
  version: string
}

export interface InputVersionsDocument {
  source: string
  policy: string
  resources?: string
  diagnostics?: string
  capacity?: string
  trend?: string
}

export interface OutputsDocument {
  run_schema: string
  analysis_result_schema: string
  normalized_encoding: string
  rollup_encoding: string
  histogram_encoding: string
}

export interface HistogramDocument {
  lowest_discernible_value_ms: string
  highest_trackable_value_ms: string
  significant_digits: string
}

export interface NormalizationDocument {
  bucket_millis: string
  rollup_seconds: Array<string>
}
