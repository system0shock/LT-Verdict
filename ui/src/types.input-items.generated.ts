// GENERATED from the @Serializable models in src/main/kotlin/io/ltverdict/core/InputItems.kt.
// Do not edit. Regenerate with
// LTV_UPDATE_GENERATED_TYPES=1 gradlew test --tests io.ltverdict.core.TypeScriptGeneratorInputItemsTest
// Describes the resource_binding, source_summary and opensearch_errors evidence items the engine writes (an optional
// key is omitted, never null); the window provenance that a source_summary may carry is merged after the typed
// item and is not described here. It is not a validator.

export type InputEvidence = OpensearchErrorsEvidence | ResourceBindingEvidence | SourceSummaryEvidence

export interface OpensearchErrorsEvidence {
  type: 'opensearch_errors'
  id: string
  schema_version: string
  load_input_sha256: string
  profile_id: string
  start_epoch_ms: number
  end_epoch_ms: number
  step_ms: number
  total_errors: number
  error_rate_per_minute: number
  timeline: Array<OpenSearchTimelineCellDocument>
  groups: Array<OpenSearchGroupDocument>
  coverage: OpenSearchCoverageDocument
}

export interface OpenSearchTimelineCellDocument {
  from_epoch_ms: number
  to_epoch_ms: number
  count: number
  rate_per_minute: number
}

export interface OpenSearchGroupDocument {
  service: string
  error_type: string
  count: number
  first_epoch_ms: number
  last_epoch_ms: number
  samples: Array<OpenSearchSampleDocument>
}

export interface OpenSearchSampleDocument {
  timestamp_epoch_ms: number
  index: string
  document_id: string
  message: string
  message_truncated: boolean
  source_url: string
}

export interface OpenSearchCoverageDocument {
  status: string
  reasons: Array<string>
  timed_out: boolean
  total_relation: string
  shards: OpenSearchShardsDocument
  terms: OpenSearchTermsDocument
  samples_per_group_limit: number
  sample_message_bytes_max: number
}

export interface OpenSearchShardsDocument {
  total: number
  successful: number
  skipped: number
  failed: number
}

export interface OpenSearchTermsDocument {
  group_limit: number
  returned_groups: number
  sum_other_doc_count: number
  doc_count_error_upper_bound: number
}

export interface ResourceBindingEvidence {
  type: 'resource_binding'
  id: string
  mode: string
  snapshot_from_epoch_ms: number
  snapshot_to_epoch_ms: number
  run_from_epoch_ms: number
  run_to_epoch_ms: number
  evaluation_from_epoch_ms: number
  evaluation_to_epoch_ms: number
  dropped_leading_cells: number
  dropped_leading_millis: number
  dropped_trailing_cells: number
  dropped_trailing_millis: number
  clock_alignment: string
}

export interface SourceSummaryEvidence {
  type: 'source_summary'
  id: string
  status: string
  profile_id: string
  arm?: string
  source_kind: string
  transport: string
  start_epoch_ms?: number
  end_epoch_ms?: number
  step_ms?: number
  profiles?: Array<Record<string, unknown>>
  queries: Array<SourceQueryDocument>
  rule_spans?: Array<SourceRuleSpanDocument>
  request_count: number
  retries: number
  throttle_wait_ms: number
  cap_exceeded: boolean
}

export interface SourceQueryDocument {
  id: string
  status: string
  reason?: string
  expression_sha256?: string
}

export interface SourceRuleSpanDocument {
  rule_id: string
  declared_span_ms: number
  step_ms: number
  cells: number
  effective_span_ms: number
}
