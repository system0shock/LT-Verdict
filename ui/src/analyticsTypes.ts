import type { AnalysisReference } from './types'

export interface DynamicsMetric {
  metric: string
  unit: string
  value: string | null
  delta_previous: string | null
  delta_previous_percent: string | null
  previous_reason: string | null
  delta_baseline: string | null
  delta_baseline_percent: string | null
  baseline_reason: string | null
}

export interface DynamicsRow {
  reference: AnalysisReference
  run_date: string
  jenkins_build: string | null
  commit: string | null
  application_version: string | null
  load_profile: string | null
  verdict: string
  metrics: DynamicsMetric[]
}

export interface RunDynamics {
  schema_version: 'run-dynamics.v1'
  limit: number
  comparable_count: number
  excluded_incompatible_count: number
  baseline: AnalysisReference | null
  rows: DynamicsRow[]
}

export interface TransactionScope {
  kind: 'transaction'
  group_path: string[]
  label: string
  sample_kind: string
}

export interface TransactionMetric {
  metric: string
  unit: string
  current: string | null
  baseline: string | null
  delta: string | null
  delta_percent: string | null
  reason: string | null
  percent_reason: string | null
}

export interface TransactionRow { scope: TransactionScope; metrics: TransactionMetric[] }

export interface TransactionComparison {
  schema_version: 'transaction-comparison.v1'
  compatible: boolean
  filter: string | null
  matched_count: number
  truncated: boolean
  rows: TransactionRow[]
}

export interface OverlayPoint { from_ms: number; to_ms: number; count: number | string; value: number | string }
export interface OverlaySeries { id: string; profile_id: string; label: string; unit: string; points: OverlayPoint[] }
export interface OverlayMarker { profile_id: string; at_ms: number; service: string; error_type: string; message: string; message_truncated: boolean; source_url: string }
export interface ChartOverlay { schema_version: 'chart-overlays.v1'; series: OverlaySeries[]; markers: OverlayMarker[]; reasons: string[] }

export interface MetricPack {
  id: 'jvm' | 'openshift'
  status: 'SUCCESS' | 'DEGRADED' | 'SKIPPED'
  available_capabilities: string[]
  missing_capabilities: string[]
  series_ids: string[]
  finding_refs: string[]
  reasons: string[]
}

export interface MetricPackAnalysis { schema_version: 'metric-packs.v1'; packs: MetricPack[] }

export interface SavedAnalytics {
  schema_version: 'saved-analytics.v1'
  history_scan_truncated: boolean
  history_scan_limit: number
  history_metadata_byte_limit: number
  history_integrity: 'SAVED_DOCUMENT_HASHES'
  dynamics: RunDynamics | null
  transactions: TransactionComparison | null
  overlay: ChartOverlay | null
  metric_packs: MetricPackAnalysis
}
