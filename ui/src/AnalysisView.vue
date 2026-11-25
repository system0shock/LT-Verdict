<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import LoadCharts from './LoadCharts.vue'
import { CORRELATION_LABELS } from './shell/labels'
import {
  DIAGNOSTICS_LABELS,
  NORMALIZED_LABELS,
  OPENSEARCH_LABELS,
  POSTGRES_LABELS,
  RESOURCE_CHECK_LABELS,
  RESOURCE_TABLES_LABELS,
  SOURCE_ACQUISITION_LABELS,
  SUMMARY_METRICS_LABELS,
  TABLES_LABELS,
  type TablesSubTabKey,
} from './shell/labels.tables'
import { selectedCorrelations, unavailableFamilies } from './shell/overview'
import {
  sortResourceChecks,
  sortResourceSummaries,
  type ResourceCheckSortKey,
  type ResourceSummarySortKey,
} from './shell/tables'
import { reasonText as verdictReasonText } from './verdictReasons'
import type { AnalysisResult, Bucket, SourceSummaryEvidence, OpenSearchEvidence, PostgresContextEvidence, TrendCheckEvidence, TrendSummaryEvidence } from './types'

const props = defineProps<{
  result: AnalysisResult
  shellTables?: boolean
  visibleGroup?: TablesSubTabKey
  buckets: Bucket[]
  rollup: number
  bucketRollup: number
  markers?: Array<{ at_ms: number; service: string; error_type: string; message: string }>
  rangeStart: string
  rangeEnd: string
}>()

const showGroup = (group: TablesSubTabKey) =>
  !props.shellTables || !props.visibleGroup || props.visibleGroup === 'all' || props.visibleGroup === group

const emit = defineEmits<{
  'update:rollup': [value: number]
  'update:range-start': [value: string]
  'update:range-end': [value: string]
  'refresh-buckets': []
}>()

type Evidence = Record<string, unknown>

const evidence = computed(() => props.result.evidence as unknown as Evidence[])
const metrics = computed(() => evidence.value.filter((item) => item.type === 'metric_summary'))
const checks = computed(() => evidence.value.filter((item) => item.type === 'policy_check'))
const resourceSummaries = computed(() => evidence.value.filter((item) => item.type === 'resource_summary'))
const windowPolicySummaries = computed(() => evidence.value.filter((item) => item.type === 'window_policy_summary'))
const resourceChecks = computed(() => evidence.value.filter((item) => item.type === 'resource_policy_check'))
// Platform SLA checks (ADR 0018) carry the service and the cell coverage of the window; snapshot rules do not.
const platformChecks = computed(() => resourceChecks.value.some((item) => item.service !== undefined))
const presumedCheckIds = computed(() => new Set(props.result.findings
  .filter((finding) => finding.type === 'resource_threshold_violation' && finding.presumed === true)
  .map((finding) => finding.evidence_id)))
const resSummarySort = ref<ResourceSummarySortKey>('default')
const resSummaryDir = ref<1 | -1>(1)
const resCheckSort = ref<ResourceCheckSortKey>('default')
const resCheckDir = ref<1 | -1>(1)
const sortedResourceSummaries = computed(() =>
  props.shellTables ? sortResourceSummaries(resourceSummaries.value, resSummarySort.value, resSummaryDir.value) : resourceSummaries.value)
const sortedResourceChecks = computed(() =>
  props.shellTables ? sortResourceChecks(resourceChecks.value, resCheckSort.value, resCheckDir.value) : resourceChecks.value)

const resSummarySortCols: Array<ResourceSummarySortKey | null> = [
  'series_id', 'metric', null, 'entity', null, null, 'window_id',
  'observed_cells', 'missing_cells', null, 'min', 'max', 'mean', 'median',
  null, null, null, 'q95', null, null, null, 'slope_per_second', null, null,
]

function resSummaryAria(key: ResourceSummarySortKey | null): 'ascending' | 'descending' | 'none' | undefined {
  if (!props.shellTables || !key) return undefined
  return resSummarySort.value !== key ? 'none' : resSummaryDir.value === 1 ? 'ascending' : 'descending'
}

function toggleResSummarySort(key: ResourceSummarySortKey) {
  if (resSummarySort.value === key) resSummaryDir.value = resSummaryDir.value === 1 ? -1 : 1
  else {
    resSummarySort.value = key
    resSummaryDir.value = key === 'series_id' || key === 'metric' || key === 'entity' || key === 'window_id' ? 1 : -1
  }
}

function resCheckAria(key: ResourceCheckSortKey): 'ascending' | 'descending' | 'none' | undefined {
  if (!props.shellTables) return undefined
  return resCheckSort.value !== key ? 'none' : resCheckDir.value === 1 ? 'ascending' : 'descending'
}

function toggleResCheckSort(key: ResourceCheckSortKey) {
  if (resCheckSort.value === key) resCheckDir.value = resCheckDir.value === 1 ? -1 : 1
  else {
    resCheckSort.value = key
    resCheckDir.value = 1
  }
}

function statusLabel(value: unknown): string {
  const raw = formatOptional(value)
  if (!props.shellTables) return raw
  return (TABLES_LABELS.statusText as Record<string, string>)[raw] ?? raw
}

function checkCoverage(item: Evidence): string | null {
  const expected = item.expected_cells
  const observed = item.observed_cells
  if (typeof expected !== 'number' || typeof observed !== 'number' || expected <= 0) return null
  const percent = new Intl.NumberFormat(props.shellTables ? 'ru-RU' : 'en-US', { maximumFractionDigits: 2 }).format((observed / expected) * 100)
  const gaps = typeof item.missing_cells === 'number' && item.missing_cells > 0
    ? props.shellTables
      ? RESOURCE_TABLES_LABELS.missingCoverage(item.missing_cells, formatOptional(item.longest_gap_cells))
      : `; missing cells ${item.missing_cells}, longest gap ${formatOptional(item.longest_gap_cells)}`
    : ''
  return `${observed} / ${expected} (${percent} %)${gaps}`
}
const checkHeads = computed(() => props.shellTables
  ? RESOURCE_CHECK_LABELS.heads
  : { window: 'Window', rule: 'Rule', service: 'Service', series: 'Series', operator: 'Operator', threshold: 'Threshold', effect: 'Effect', status: 'Status', reason: 'Reason', coverage: 'Coverage (observed / expected)' })
const resourceBindings = computed(() => evidence.value.filter((item) => item.type === 'resource_binding'))
const sourceSummaries = computed(() => props.result.evidence
  .filter((item): item is SourceSummaryEvidence => item.type === 'source_summary')
  .flatMap((item) => item.profiles?.length ? item.profiles : [item])
  .sort((left, right) => left.profile_id < right.profile_id ? -1 : left.profile_id > right.profile_id ? 1 : 0))
// The window is one per acquisition, so only the aggregate summary carries its provenance.
const windowProvenance = computed(() => props.result.evidence
  .filter((item): item is SourceSummaryEvidence => item.type === 'source_summary')
  .find((item) => item.window_origin !== undefined))
const windowProvenanceRows = computed(() => {
  const provenance = windowProvenance.value
  if (!provenance) return []
  const w = SOURCE_ACQUISITION_LABELS.windowRows
  const ru = Boolean(props.shellTables)
  const rows = [{ label: ru ? w.windowOrigin : 'Window origin', value: String(provenance.window_origin) }]
  const recognizedStart = provenance.recognized_start_epoch_ms
  const recognizedEnd = provenance.recognized_end_epoch_ms
  if (recognizedStart !== undefined && recognizedEnd !== undefined) {
    rows.push({ label: ru ? w.recognizedPeriod : 'Recognized period', value: `${formatEpochMs(recognizedStart)} – ${formatEpochMs(recognizedEnd)}` })
  }
  if (provenance.requested_margin_ms !== undefined) rows.push({ label: ru ? w.requestedMargin : 'Requested margin', value: formatMillis(provenance.requested_margin_ms) })
  if (provenance.applied_margin_ms !== undefined) rows.push({ label: ru ? w.appliedMargin : 'Applied margin', value: formatMillis(provenance.applied_margin_ms) })
  if (provenance.max_idle_gap_ms !== undefined) rows.push({ label: ru ? w.maxIdleGap : 'Max idle gap', value: formatMillis(provenance.max_idle_gap_ms) })
  if (provenance.detected_idle_gaps !== undefined) rows.push({ label: ru ? w.detectedIdleGaps : 'Detected idle gaps', value: String(provenance.detected_idle_gaps) })
  const longestIdleGap = provenance.longest_idle_gap_ms
  if (longestIdleGap !== undefined) rows.push({ label: ru ? w.longestIdleGap : 'Longest idle gap', value: longestIdleGap === null ? '—' : formatMillis(longestIdleGap) })
  if (provenance.auto_window_status !== undefined) rows.push({ label: ru ? w.autoWindowStatus : 'Auto window status', value: provenance.auto_window_status })
  if (provenance.step_origin !== undefined) rows.push({ label: ru ? w.stepOrigin : 'Step origin', value: provenance.step_origin })
  if (provenance.requested_step_ms !== undefined) rows.push({ label: ru ? w.requestedStep : 'Requested step', value: formatMillis(provenance.requested_step_ms) })
  // The applied step is published only inside the RESOLUTION_REDUCED warning; without a warning it is not stated.
  const reduced = provenance.warnings?.[0]
  if (reduced !== undefined) rows.push({ label: ru ? w.appliedStep : 'Applied step', value: formatMillis(reduced.applied_step_ms) })
  if (provenance.series_count !== undefined && provenance.cells_per_series !== undefined) {
    rows.push({ label: ru ? w.seriesCells : 'Series × cells', value: `${provenance.series_count} × ${provenance.cells_per_series} ${ru ? w.ofWord : 'of'} ${provenance.cell_budget ?? '—'}` })
  }
  return rows
})
const errorContexts = computed(() => props.result.evidence
  .filter((item): item is OpenSearchEvidence => item.type === 'opensearch_errors')
  .sort((left, right) => left.profile_id < right.profile_id ? -1 : left.profile_id > right.profile_id ? 1 : 0))
const postgresContexts = computed(() => props.result.evidence
  .filter((item): item is PostgresContextEvidence => item.type === 'postgres_context'))
const diagnosticSummaries = computed(() => props.result.evidence.filter((item) => item.type === 'diagnostic_summary'))
const correlationPairs = computed(() => props.result.evidence.filter((item) => item.type === 'correlation_pair'))
const selectedHypotheses = computed(() => selectedCorrelations(props.result))
const unavailableHypotheses = computed(() => unavailableFamilies(props.result))
const anomalyChecks = computed(() => props.result.evidence.filter((item) => item.type === 'anomaly_check'))
const anomalyEpisodes = computed(() => props.result.findings.filter((item) => item.type === 'anomaly_episode'))
const diagnosticDetails = computed(() => JSON.stringify([...diagnosticSummaries.value, ...correlationPairs.value, ...anomalyChecks.value, ...anomalyEpisodes.value], null, 2))
const overall = computed(() => metrics.value.find((item) => scope(item).kind === 'overall'))
const capacity = computed(() => props.result.capacity_summary)
const trendChecks = computed(() => props.result.evidence
  .filter((item): item is TrendCheckEvidence => item.type === 'trend_check'))
const trendSummary = computed(() => props.result.evidence
  .find((item): item is TrendSummaryEvidence => item.type === 'trend_summary') ?? null)

const verdict = computed(() => props.result.policy_verdict)
const overallMetrics = computed(() => metricValues(overall.value))
// New shell: the block is in Russian and the table grows in portions (a 15-minute run has 900 one-second bins).
const BUCKET_STEP = 50
const bucketLimit = ref(BUCKET_STEP)
watch(() => props.buckets, () => { bucketLimit.value = BUCKET_STEP })
const bucketRows = computed(() => {
  const width = props.bucketRollup * 1_000
  const ru = props.shellTables
  const millis = (value: number | undefined) => ru ? (value === undefined ? NORMALIZED_LABELS.noValue : `${value.toLocaleString('ru-RU')} ${NORMALIZED_LABELS.unit}`) : formatMillis(value)
  const rows: Array<{ id: string; time: string; rps: string; errors: string; p95: string; max: string; status: string; available: boolean }> = []
  let previousStart: number | undefined
  for (const bucket of [...props.buckets].sort((left, right) => left.bucket_start_ms - right.bucket_start_ms)) {
    const start = bucket.bucket_start_ms
    if (previousStart !== undefined && start > previousStart + width) {
      rows.push({
        id: `missing-${previousStart + width}`,
        time: `${previousStart + width}–${start} ${ru ? NORMALIZED_LABELS.unit : 'ms'}`,
        rps: '—',
        errors: '—',
        p95: '—',
        max: '—',
        status: ru ? NORMALIZED_LABELS.missing : 'Missing / no samples',
        available: false,
      })
    }
    rows.push({
      id: `bucket-${start}`,
      time: `${start} ${ru ? NORMALIZED_LABELS.unit : 'ms'}`,
      rps: ru
        ? (bucket.sample_count / props.bucketRollup).toLocaleString('ru-RU', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
        : (bucket.sample_count / props.bucketRollup).toFixed(2),
      errors: ru ? bucket.error_count.toLocaleString('ru-RU') : bucket.error_count.toLocaleString(),
      p95: millis(bucket.p95_latency_ms),
      max: millis(bucket.max_latency_ms),
      status: ru ? NORMALIZED_LABELS.available : 'Available',
      available: true,
    })
    previousStart = start
  }
  return rows
})
const normText = computed(() => props.shellTables
  ? {
      eyebrow: NORMALIZED_LABELS.eyebrow, title: NORMALIZED_LABELS.title, rollup: NORMALIZED_LABELS.rollupLabel, rollupAria: NORMALIZED_LABELS.rollupAria,
      start: NORMALIZED_LABELS.startOffset, end: NORMALIZED_LABELS.endOffset, refresh: NORMALIZED_LABELS.refresh, region: NORMALIZED_LABELS.region,
      heads: NORMALIZED_LABELS.heads as readonly string[], option: NORMALIZED_LABELS.rollupOption,
    }
  : {
      eyebrow: 'Inspectable source facts', title: 'Normalized data', rollup: 'Rollup', rollupAria: 'Bucket rollup', start: 'Start offset (ms)', end: 'End offset (ms)',
      refresh: 'Refresh data', region: 'Time bins', heads: ['Time bin', 'RPS', 'Errors', 'P95', 'Max latency', 'Data status'] as readonly string[],
      option: (seconds: number) => `${seconds} second${seconds === 1 ? '' : 's'}`,
    })
const bucketRegion = ref<HTMLElement | null>(null)
// The pressed button disappears when everything is shown: keep the keyboard focus inside the block.
async function growBuckets(all: boolean) {
  bucketLimit.value = all ? bucketRows.value.length : bucketLimit.value + BUCKET_STEP
  await nextTick()
  if (!document.activeElement || document.activeElement === document.body) bucketRegion.value?.focus()
}
const shownBucketRows = computed(() => props.shellTables ? bucketRows.value.slice(0, bucketLimit.value) : bucketRows.value)
const policyRows = computed(() =>
  checks.value.map((check) => {
    const metric = metrics.value.find((item) => item.id === check.metric_evidence_id)
    const checkScope = metric ? scope(metric) : scope(check)
    const mode = stringAt(check, 'sample_mode')
    const count = numberAt(check, 'sample_count')
    const limit = numberAt(check, mode === 'INSUFFICIENT' ? 'sample_floor' : 'min_samples')
    return {
      id: stringAt(check, 'id') ?? stringAt(check, 'rule_id') ?? 'policy-check',
      transaction: metric
        ? checkScope.kind === 'transaction' ? stringAt(checkScope, 'label') ?? 'Unknown' : 'Overall'
        : checkScope.kind === 'transaction' ? stringAt(checkScope, 'label') ?? 'Unknown'
          : checkScope.kind === 'overall' ? 'Overall' : 'Unresolved',
      metric: stringAt(check, 'metric') ?? 'Not available',
      operator: stringAt(check, 'operator') ?? '—',
      threshold: formatPolicyValue(check),
      observed: formatPolicyValue(check, 'observed'),
      scope: metric || checkScope.kind ? scopeText(checkScope) : 'Not available',
      window: formatOptional(valueAt(check, 'window_id')),
      sample: mode ? `${count ?? '\u2014'} / ${limit ?? '\u2014'} \u00b7 ${mode}` : '\u2014',
      status: stringAt(check, 'status') ?? 'NO_VERDICT',
    }
  }),
)

const transactions = computed(() =>
  metrics.value
    .filter((item) => scope(item).kind === 'transaction')
    .map((item) => {
      const itemScope = scope(item)
      const values = metricValues(item)
      const relatedChecks = checks.value.filter((check) => check.metric_evidence_id === item.id)
      const policyStatus = relatedChecks.some((check) => check.status === 'FAIL')
        ? 'FAIL'
        : relatedChecks.some((check) => check.status === 'NO_VERDICT')
          ? 'NO_VERDICT'
          : relatedChecks.some((check) => check.status === 'PASS')
            ? 'PASS'
            : verdict.value === 'NO_POLICY' ? 'NO_POLICY' : 'NOT_CHECKED'
      return {
        id: stringAt(item, 'id') ?? 'transaction',
        path: arrayAt(itemScope, 'group_path').join(' / '),
        label: stringAt(itemScope, 'label') ?? 'Unknown',
        kind: stringAt(itemScope, 'sample_kind') ?? '—',
        failed: policyStatus === 'FAIL',
        policyStatus,
        ...values,
      }
    })
    .sort((left, right) => {
      if (left.failed !== right.failed) return left.failed ? -1 : 1
      return right.errorCount - left.errorCount || right.p99Number - left.p99Number || right.samples - left.samples ||
        compareExact(left.path, right.path) || compareExact(left.label, right.label)
    }),
)

function scope(item: Evidence | undefined): Evidence {
  const value = item?.scope
  return value !== null && typeof value === 'object' && !Array.isArray(value) ? value as Evidence : {}
}

function metricValues(item: Evidence | undefined) {
  const latency = valueAt(item, 'latency_ms')
  const latencyValues = latency !== null && typeof latency === 'object' && !Array.isArray(latency) ? latency as Evidence : {}
  const samples = numberAt(item, 'sample_count') ?? 0
  const errorCount = numberAt(item, 'error_count') ?? 0
  const p50 = numberAt(latencyValues, 'p50')
  const p95 = numberAt(latencyValues, 'p95')
  const p99 = numberAt(latencyValues, 'p99')
  return {
    samples,
    errorCount,
    errorRate: formatRatio(valueAt(item, 'error_rate_ratio')),
    throughput: formatThroughput(valueAt(item, 'throughput_rps')),
    p50: formatMillis(p50),
    p95: formatMillis(p95),
    p99: formatMillis(p99),
    p99Number: p99 ?? 0,
  }
}

function valueAt(item: Evidence | Bucket | undefined, key: string): unknown {
  return item?.[key as keyof typeof item]
}

function stringAt(item: Evidence | undefined, key: string): string | undefined {
  const value = valueAt(item, key)
  return typeof value === 'string' ? value : undefined
}

function numberAt(item: Evidence | Bucket | undefined, key: string): number | undefined {
  const value = valueAt(item, key)
  return typeof value === 'number' ? value : undefined
}

function arrayAt(item: Evidence, key: string): string[] {
  const value = valueAt(item, key)
  return Array.isArray(value) ? value.filter((entry): entry is string => typeof entry === 'string') : []
}

function formatMillis(value: number | undefined) {
  if (props.shellTables) {
    return value === undefined ? SUMMARY_METRICS_LABELS.noData : `${value.toLocaleString('ru-RU')} ${SUMMARY_METRICS_LABELS.msUnit}`
  }
  return value === undefined ? 'Not available' : `${value.toLocaleString()} ms`
}

function formatEpochMs(value: number) {
  return new Date(value).toISOString()
}

function formatRatio(value: unknown) {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) return props.shellTables ? SUMMARY_METRICS_LABELS.noData : 'Not available'
  const numerator = numberAt(value as Evidence, 'numerator')
  const denominator = numberAt(value as Evidence, 'denominator')
  return numerator === undefined || denominator === undefined || denominator === 0
    ? (props.shellTables ? SUMMARY_METRICS_LABELS.noData : 'Not available')
    : `${((numerator / denominator) * 100).toFixed(2)}%`
}

function formatThroughput(value: unknown) {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) return props.shellTables ? SUMMARY_METRICS_LABELS.noData : 'Not available'
  const numerator = numberAt(value as Evidence, 'numerator')
  const denominator = numberAt(value as Evidence, 'denominator')
  if (numerator === undefined || denominator === undefined || denominator === 0) {
    return props.shellTables ? SUMMARY_METRICS_LABELS.noData : 'Not available'
  }
  const rps = numerator / denominator
  return props.shellTables
    ? `${new Intl.NumberFormat('ru-RU', { minimumFractionDigits: 2, maximumFractionDigits: 2 }).format(rps)} ${SUMMARY_METRICS_LABELS.rpsUnit}`
    : `${rps.toFixed(2)} RPS`
}

function formatValue(value: unknown) {
  if (typeof value === 'number' || typeof value === 'string') return String(value)
  return 'Not available'
}

// New shell only: exact decimals from the server (up to 34 digits) are shown short, the full value stays in the cell title.
const shortFraction = new Intl.NumberFormat('ru-RU', { maximumFractionDigits: 4 })
const shortSignificant = new Intl.NumberFormat('ru-RU', { maximumSignificantDigits: 4 })
function shortDecimal(value: string) {
  if (!/^-?\d{1,15}\.\d{7,}$/.test(value)) return value
  const number = Number(value)
  if (!Number.isFinite(number)) return value
  return Math.abs(number) >= 1 ? shortFraction.format(number) : shortSignificant.format(number)
}

function formatOptional(value: unknown) {
  if (props.shellTables) return value === null || value === undefined ? '—' : String(value)
  return value === null || value === undefined ? 'Not available (null)' : String(value)
}

function capacityValue(value: number | string | null) {
  return value === null ? '—' : String(value)
}

function shortStatistic(value: unknown) {
  return props.shellTables && typeof value === 'string' ? shortDecimal(value) : formatOptional(value)
}

function statistic(item: Evidence, key: string) {
  const values = valueAt(item, 'statistics')
  return values !== null && typeof values === 'object' && !Array.isArray(values)
    ? shortStatistic(valueAt(values as Evidence, key))
    : props.shellTables ? '—' : 'Not available (null)'
}

function statisticTitle(item: Evidence, key: string) {
  const values = valueAt(item, 'statistics')
  const value = values !== null && typeof values === 'object' && !Array.isArray(values) ? valueAt(values as Evidence, key) : undefined
  return props.shellTables && typeof value === 'string' && shortDecimal(value) !== value ? value : undefined
}

function bindingText(item: Evidence) {
  return JSON.stringify(item, null, 2)
}

function formatPolicyValue(check: Evidence, field: 'threshold' | 'observed' = 'threshold') {
  const value = valueAt(check, field)
  const metric = stringAt(check, 'metric')
  if (metric === 'error_rate_ratio') {
    return typeof value === 'number' ? `${(value * 100).toFixed(2)}%` : formatRatio(value)
  }
  if (metric === 'throughput_rps') {
    return typeof value === 'number' ? `${value} RPS` : formatThroughput(value)
  }
  return typeof value === 'number' ? `${value} ms` : formatValue(value)
}

function scopeText(value: Evidence) {
  const kind = stringAt(value, 'kind')
  return kind === 'transaction' ? [arrayAt(value, 'group_path').join(' / '), stringAt(value, 'label')].filter(Boolean).join(' / ') : 'Overall'
}

function formatDuration(milliseconds: number) {
  if (props.shellTables) {
    const fmt = (val: number) => new Intl.NumberFormat('ru-RU', { minimumFractionDigits: 1, maximumFractionDigits: 1 }).format(val)
    return milliseconds < 60_000
      ? DIAGNOSTICS_LABELS.durationSec(fmt(milliseconds / 1_000))
      : DIAGNOSTICS_LABELS.durationMin(fmt(milliseconds / 60_000))
  }
  return milliseconds < 60_000 ? `${(milliseconds / 1_000).toFixed(1)} s` : `${(milliseconds / 60_000).toFixed(1)} min`
}

function compareExact(left: string, right: string) {
  return left < right ? -1 : left > right ? 1 : 0
}

function updateRollup(event: Event) {
  emit('update:rollup', Number((event.target as HTMLSelectElement).value))
}

function updateRange(name: 'update:range-start' | 'update:range-end', event: Event) {
  const value = (event.target as HTMLInputElement).value
  if (name === 'update:range-start') emit('update:range-start', value)
  else emit('update:range-end', value)
}
</script>

<template>
  <section
    v-if="showGroup('rules')"
    id="summary-metrics"
    tabindex="-1"
    :lang="shellTables ? 'ru' : undefined"
    aria-labelledby="summary-metrics-title"
  >
    <div class="section-heading">
      <p class="eyebrow">
        {{ shellTables ? SUMMARY_METRICS_LABELS.eyebrow : 'Overall' }}
      </p><h2 id="summary-metrics-title">
        {{ shellTables ? SUMMARY_METRICS_LABELS.title : 'Summary metrics' }}
      </h2>
    </div>
    <div class="metric-grid">
      <article class="metric-card">
        <p>{{ shellTables ? SUMMARY_METRICS_LABELS.p50 : 'P50' }}</p><strong>{{ overallMetrics.p50 }}</strong><small>{{ shellTables ? SUMMARY_METRICS_LABELS.latencyHint : 'Latency percentile' }}</small>
      </article>
      <article class="metric-card">
        <p>{{ shellTables ? SUMMARY_METRICS_LABELS.p95 : 'P95' }}</p><strong>{{ overallMetrics.p95 }}</strong><small>{{ shellTables ? SUMMARY_METRICS_LABELS.latencyHint : 'Latency percentile' }}</small>
      </article>
      <article class="metric-card">
        <p>{{ shellTables ? SUMMARY_METRICS_LABELS.p99 : 'P99' }}</p><strong>{{ overallMetrics.p99 }}</strong><small>{{ shellTables ? SUMMARY_METRICS_LABELS.latencyHint : 'Latency percentile' }}</small>
      </article>
      <article class="metric-card">
        <p>{{ shellTables ? SUMMARY_METRICS_LABELS.throughput : 'Throughput' }}</p><strong>{{ formatThroughput(valueAt(overall, 'throughput_rps')) }}</strong><small>{{ shellTables ? SUMMARY_METRICS_LABELS.throughputHint : 'Run window' }}</small>
      </article>
    </div>
  </section>

  <section
    v-if="!shellTables"
    id="policy-results"
    class="panel"
    aria-labelledby="policy-results-title"
  >
    <div class="section-heading">
      <p class="eyebrow">
        Active policy
      </p><h2 id="policy-results-title">
        Policy results
      </h2>
    </div>
    <div
      class="table-wrap"
      tabindex="0"
      role="region"
      aria-label="Policy results"
    >
      <table>
        <thead><tr><th>Window</th><th>Transaction</th><th>Metric</th><th>Operator</th><th>Threshold</th><th>Measured</th><th>Scope</th><th>Sample</th><th>Status</th></tr></thead>
        <tbody>
          <tr
            v-for="row in policyRows"
            :key="row.id"
          >
            <td>{{ row.window }}</td><td>{{ row.transaction }}</td><td>{{ row.metric }}</td><td>{{ row.operator }}</td><td>{{ row.threshold }}</td><td>{{ row.observed }}</td><td>{{ row.scope }}</td><td>{{ row.sample }}</td><td>
              <span
                class="status-text"
                :data-status="row.status"
              >{{ row.status }}</span>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </section>

  <section
    v-if="capacity && !shellTables"
    id="capacity-results"
    data-testid="capacity-results"
    class="panel"
    aria-labelledby="capacity-results-title"
  >
    <div class="section-heading">
      <p class="eyebrow">
        Capacity plan
      </p><h2 id="capacity-results-title">
        Saved capacity facts
      </h2>
    </div>
    <p>Axis: {{ capacity.load_axis }} ({{ capacity.unit }}) · Capacity bound: {{ capacity.bound_type }} [{{ capacityValue(capacity.lower_inclusive) }}, {{ capacityValue(capacity.upper_exclusive) }})</p>
    <p>Policy: {{ capacity.policy_verdict }} · Knee: {{ capacityValue(capacity.capacity_knee) }} · {{ capacity.knee_reason }}</p>
    <p>Reasons: {{ capacity.reasons.join(', ') || '—' }}</p>
    <div
      class="table-wrap"
      tabindex="0"
      role="region"
      aria-label="Capacity stages"
    >
      <table>
        <thead><tr><th>Stage</th><th>Target</th><th>Achieved (p05 10s)</th><th>Observed min / max</th><th>Bins complete / expected</th><th>Verified bound</th><th>Verdict</th><th>Reasons</th><th>Evidence</th></tr></thead>
        <tbody>
          <tr
            v-for="stage in capacity.stages"
            :key="stage.id"
          >
            <td>{{ stage.id }}</td><td>{{ capacityValue(stage.target) }}</td><td>{{ capacityValue(stage.achieved) }}</td><td>{{ capacityValue(stage.observed_min) }} / {{ capacityValue(stage.observed_max) }}</td><td>{{ capacityValue(stage.complete_bins) }} / {{ capacityValue(stage.expected_bins) }}</td><td>{{ capacityValue(stage.verified_bound_load) }}</td><td>{{ stage.verdict }}</td><td>{{ stage.reasons.join(', ') || '—' }}</td><td>{{ stage.evidence_refs.join(', ') || '—' }}</td>
          </tr>
        </tbody>
      </table>
    </div>
  </section>

  <section
    v-if="(trendSummary || trendChecks.length) && !shellTables"
    id="trend-results"
    data-testid="trend-results"
    class="panel"
    aria-labelledby="trend-results-title"
  >
    <div class="section-heading">
      <p class="eyebrow">
        Trend plan
      </p><h2 id="trend-results-title">
        Saved trend facts
      </h2>
    </div>
    <p v-if="trendSummary">
      Checks: {{ trendSummary.checks_total }} · Observed: {{ trendSummary.observed }} · Not material: {{ trendSummary.not_material }} · Insufficient cells: {{ trendSummary.insufficient }} · Unavailable: {{ trendSummary.unavailable }} · Method: {{ trendSummary.method }} · Uncertainty: not estimated ({{ trendSummary.uncertainty }})
    </p>
    <div
      v-if="trendChecks.length"
      class="table-wrap"
      tabindex="0"
      role="region"
      aria-label="Resource trend checks"
    >
      <table>
        <thead>
          <tr>
            <th scope="col">
              Check
            </th><th scope="col">
              Series
            </th><th scope="col">
              Window
            </th><th scope="col">
              Declared direction
            </th><th scope="col">
              Status
            </th><th scope="col">
              Observed direction
            </th><th scope="col">
              Slope per second
            </th><th scope="col">
              Split-half shift
            </th><th scope="col">
              Median
            </th><th scope="col">
              Required split-half shift
            </th><th scope="col">
              Cells observed / expected
            </th><th scope="col">
              Reasons
            </th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="item in trendChecks"
            :key="item.id"
          >
            <td>{{ item.check_id }}</td>
            <td>{{ item.series_id }}</td>
            <td>{{ item.window_id }}</td>
            <td>{{ item.declared_direction }}</td>
            <td>{{ item.status }}</td>
            <td>{{ item.observed_direction ?? 'N/A' }}</td>
            <td>{{ item.slope_per_second ?? 'N/A' }}</td>
            <td>{{ item.split_half_shift ?? 'N/A' }}</td>
            <td>{{ item.median ?? 'N/A' }}</td>
            <td>{{ item.magnitude_gate.required_split_half_shift_units ?? 'N/A' }}</td>
            <td>{{ item.observed_cells }} / {{ item.expected_cells }}</td>
            <td>{{ item.reasons.join(', ') || '—' }}</td>
          </tr>
        </tbody>
      </table>
    </div>
  </section>

  <section
    v-if="showGroup('resources') && (resourceSummaries.length || windowPolicySummaries.length || resourceChecks.length || resourceBindings.length)"
    id="resource-results"
    class="panel"
    :lang="shellTables ? 'ru' : undefined"
    aria-labelledby="resource-results-title"
  >
    <div class="section-heading">
      <p class="eyebrow">
        {{ shellTables ? RESOURCE_TABLES_LABELS.eyebrow : 'Resource snapshot' }}
      </p><h2 id="resource-results-title">
        {{ shellTables ? RESOURCE_TABLES_LABELS.title : 'Resource statistics and SLA' }}
      </h2>
    </div>
    <details
      v-for="item in resourceBindings"
      :key="String(item.id)"
    >
      <summary>{{ shellTables ? RESOURCE_TABLES_LABELS.bindingSummary : 'Resource binding' }}</summary>
      <pre>{{ bindingText(item) }}</pre>
    </details>
    <div
      v-if="windowPolicySummaries.length"
      class="table-wrap"
      tabindex="0"
      role="region"
      :aria-label="shellTables ? RESOURCE_TABLES_LABELS.windowPolicyRegion : 'Window policy summaries'"
    >
      <table>
        <thead>
          <tr>
            <th
              v-for="head in (shellTables ? RESOURCE_TABLES_LABELS.windowPolicyHeads : ['Window', 'From epoch (ms)', 'To epoch (ms)', 'Business verdict', 'Resource verdict', 'Verdict'])"
              :key="head"
              scope="col"
            >
              {{ head }}
            </th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="item in windowPolicySummaries"
            :key="String(item.id)"
          >
            <td>{{ formatOptional(item.window_id) }}</td><td>{{ formatOptional(item.from_epoch_ms) }}</td><td>{{ formatOptional(item.to_epoch_ms) }}</td><td>
              <span
                class="status-text"
                :data-status="formatOptional(item.business_verdict)"
              >{{ statusLabel(item.business_verdict) }}</span>
            </td><td>
              <span
                class="status-text"
                :data-status="formatOptional(item.resource_verdict)"
              >{{ statusLabel(item.resource_verdict) }}</span>
            </td><td>
              <span
                class="status-text"
                :data-status="formatOptional(item.verdict)"
              >{{ statusLabel(item.verdict) }}</span>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
    <div
      v-if="resourceSummaries.length"
      class="table-wrap"
      tabindex="0"
      role="region"
      :aria-label="shellTables ? RESOURCE_TABLES_LABELS.summariesRegion : 'Resource summaries'"
    >
      <table>
        <thead>
          <tr>
            <th
              v-for="(head, index) in (shellTables ? RESOURCE_TABLES_LABELS.summaryHeads : ['Series', 'Metric', 'Unit', 'Entity', 'Role', 'Aggregation', 'Window', 'Coverage (observed / expected)', 'Missing cells', 'Longest gap', 'Min', 'Max', 'Mean', 'Median', 'Q05', 'Q25', 'Q75', 'Q95', 'IQR', 'MAD', 'Sample standard deviation', 'Slope per second', 'Split-half shift', 'Reasons'])"
              :key="head"
              scope="col"
              :aria-sort="resSummaryAria(resSummarySortCols[index])"
            >
              <button
                v-if="shellTables && resSummarySortCols[index]"
                type="button"
                :aria-label="TABLES_LABELS.sortBy(head)"
                @click="toggleResSummarySort(resSummarySortCols[index]!)"
              >
                {{ head }}
              </button>
              <template v-else>
                {{ head }}
              </template>
            </th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="item in sortedResourceSummaries"
            :key="String(item.id)"
          >
            <td>{{ formatOptional(item.series_id) }}</td><td>{{ formatOptional(item.metric) }}</td><td>{{ formatOptional(item.unit) }}</td><td>{{ formatOptional(item.entity) }}</td><td>{{ formatOptional(item.role) }}</td><td>{{ formatOptional(item.aggregation) }}</td><td>{{ formatOptional(item.window_id) }}</td><td>{{ formatOptional(item.observed_cells) }} / {{ formatOptional(item.expected_cells) }}</td><td>{{ formatOptional(item.missing_cells) }}</td><td>{{ formatOptional(item.longest_gap_cells) }}</td><td :title="statisticTitle(item, 'min')">
              {{ statistic(item, 'min') }}
            </td><td :title="statisticTitle(item, 'max')">
              {{ statistic(item, 'max') }}
            </td><td :title="statisticTitle(item, 'mean')">
              {{ statistic(item, 'mean') }}
            </td><td :title="statisticTitle(item, 'median')">
              {{ statistic(item, 'median') }}
            </td><td :title="statisticTitle(item, 'q05')">
              {{ statistic(item, 'q05') }}
            </td><td :title="statisticTitle(item, 'q25')">
              {{ statistic(item, 'q25') }}
            </td><td :title="statisticTitle(item, 'q75')">
              {{ statistic(item, 'q75') }}
            </td><td :title="statisticTitle(item, 'q95')">
              {{ statistic(item, 'q95') }}
            </td><td :title="statisticTitle(item, 'iqr')">
              {{ statistic(item, 'iqr') }}
            </td><td :title="statisticTitle(item, 'mad')">
              {{ statistic(item, 'mad') }}
            </td><td :title="statisticTitle(item, 'sample_standard_deviation')">
              {{ statistic(item, 'sample_standard_deviation') }}
            </td><td :title="statisticTitle(item, 'slope_per_second')">
              {{ statistic(item, 'slope_per_second') }}
            </td><td :title="statisticTitle(item, 'split_half_shift')">
              {{ statistic(item, 'split_half_shift') }}
            </td><td>
              <template v-if="shellTables && arrayAt(item, 'reasons').length">
                <span
                  v-for="code in arrayAt(item, 'reasons')"
                  :key="code"
                >{{ verdictReasonText(code) }} <code>{{ code }}</code> </span>
              </template>
              <template v-else>
                {{ arrayAt(item, 'reasons').join(', ') || '—' }}
              </template>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
    <div
      v-if="resourceChecks.length"
      class="table-wrap"
      tabindex="0"
      role="region"
      :aria-label="shellTables ? RESOURCE_TABLES_LABELS.checksRegion : 'Resource policy checks'"
    >
      <table>
        <thead :lang="shellTables ? 'ru' : undefined">
          <tr>
            <th :aria-sort="resCheckAria('window_id')">
              <button
                v-if="shellTables"
                type="button"
                :aria-label="TABLES_LABELS.sortBy(checkHeads.window)"
                @click="toggleResCheckSort('window_id')"
              >
                {{ checkHeads.window }}
              </button>
              <template v-else>
                {{ checkHeads.window }}
              </template>
            </th>
            <th :aria-sort="resCheckAria('rule_id')">
              <button
                v-if="shellTables"
                type="button"
                :aria-label="TABLES_LABELS.sortBy(checkHeads.rule)"
                @click="toggleResCheckSort('rule_id')"
              >
                {{ checkHeads.rule }}
              </button>
              <template v-else>
                {{ checkHeads.rule }}
              </template>
            </th>
            <th
              v-if="platformChecks"
              :aria-sort="resCheckAria('service')"
            >
              <button
                v-if="shellTables"
                type="button"
                :aria-label="TABLES_LABELS.sortBy(checkHeads.service)"
                @click="toggleResCheckSort('service')"
              >
                {{ checkHeads.service }}
              </button>
              <template v-else>
                {{ checkHeads.service }}
              </template>
            </th>
            <th :aria-sort="resCheckAria('series_id')">
              <button
                v-if="shellTables"
                type="button"
                :aria-label="TABLES_LABELS.sortBy(checkHeads.series)"
                @click="toggleResCheckSort('series_id')"
              >
                {{ checkHeads.series }}
              </button>
              <template v-else>
                {{ checkHeads.series }}
              </template>
            </th>
            <th>
              {{ checkHeads.operator }}
            </th>
            <th>
              {{ checkHeads.threshold }}
            </th>
            <th>
              {{ checkHeads.effect }}
            </th>
            <th :aria-sort="resCheckAria('status')">
              <button
                v-if="shellTables"
                type="button"
                :aria-label="TABLES_LABELS.sortBy(checkHeads.status)"
                @click="toggleResCheckSort('status')"
              >
                {{ checkHeads.status }}
              </button>
              <template v-else>
                {{ checkHeads.status }}
              </template>
            </th>
            <th>
              {{ checkHeads.reason }}
            </th>
            <th v-if="platformChecks">
              {{ checkHeads.coverage }}
            </th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="item in sortedResourceChecks"
            :key="String(item.id)"
          >
            <td>{{ formatOptional(item.window_id) }}</td><td>{{ formatOptional(item.rule_id) }}</td><td
              v-if="platformChecks"
              data-testid="resource-check-service"
            >
              {{ item.service === undefined ? '—' : formatOptional(item.service) }}<template v-if="item.platform_rule_id !== undefined">
                {{ shellTables ? RESOURCE_TABLES_LABELS.platformRule(formatOptional(item.platform_rule_id)) : `(platform rule ${formatOptional(item.platform_rule_id)})` }}
              </template>
            </td><td>{{ formatOptional(item.series_id) }}</td><td>{{ shellTables ? (RESOURCE_TABLES_LABELS.operators[String(item.operator ?? '')] ?? formatOptional(item.operator)) : formatOptional(item.operator) }}</td><td>{{ formatOptional(item.threshold) }} {{ formatOptional(item.unit) }}</td><td>{{ shellTables ? (RESOURCE_TABLES_LABELS.effects[String(item.effect ?? '')] ?? formatOptional(item.effect)) : formatOptional(item.effect) }}</td><td>
              <span
                class="status-text"
                :data-status="formatOptional(item.status)"
              >{{ statusLabel(item.status) }}</span>
            </td><td>
              <template v-if="shellTables && typeof item.reason === 'string' && item.reason">
                {{ verdictReasonText(item.reason) }} <code>{{ item.reason }}</code>
              </template>
              <template v-else>
                {{ formatOptional(item.reason) }}
              </template>
            </td><td
              v-if="platformChecks"
              data-testid="resource-check-coverage"
            >
              {{ checkCoverage(item) ?? '—' }}<template v-if="presumedCheckIds.has(item.id)">
                {{ shellTables ? RESOURCE_TABLES_LABELS.presumedViolation : '; presumed violation (the series is reached only through missing cells)' }}
              </template>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </section>

  <section
    v-if="showGroup('diagnostics') && sourceSummaries.length"
    id="source-acquisition"
    data-testid="source-acquisition"
    class="panel"
    :lang="shellTables ? 'ru' : undefined"
    aria-labelledby="source-acquisition-title"
  >
    <div class="section-heading">
      <p class="eyebrow">
        {{ shellTables ? SOURCE_ACQUISITION_LABELS.eyebrow : 'Online source' }}
      </p><h2 id="source-acquisition-title">
        {{ shellTables ? SOURCE_ACQUISITION_LABELS.title : 'Source acquisition' }}
      </h2>
    </div>
    <div
      v-for="item in sourceSummaries"
      :key="`${item.profile_id}:${item.id}`"
      class="table-wrap"
      tabindex="0"
      role="region"
      :aria-label="shellTables ? SOURCE_ACQUISITION_LABELS.region(item.profile_id) : `Source acquisition: ${item.profile_id}`"
    >
      <table>
        <thead>
          <tr>
            <th
              v-for="head in (shellTables ? SOURCE_ACQUISITION_LABELS.profileHeads : ['Profile', 'Source', 'Status', 'Requests / retries', 'Throttle wait (ms)', 'Request cap'])"
              :key="head"
            >
              {{ head }}
            </th>
          </tr>
        </thead>
        <tbody><tr><td>{{ item.profile_id }}</td><td>{{ item.source_kind }} / {{ item.transport }}</td><td>{{ item.status }}</td><td>{{ item.request_count }} / {{ item.retries }}</td><td>{{ item.throttle_wait_ms }}</td><td>{{ item.cap_exceeded ? (shellTables ? SOURCE_ACQUISITION_LABELS.capExceeded : 'Exceeded') : (shellTables ? SOURCE_ACQUISITION_LABELS.capNotExceeded : 'Not exceeded') }}</td></tr></tbody>
      </table>
      <table>
        <thead>
          <tr>
            <th
              v-for="head in (shellTables ? SOURCE_ACQUISITION_LABELS.queryHeads : ['Query', 'Status', 'Reason'])"
              :key="head"
            >
              {{ head }}
            </th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="query in item.queries"
            :key="query.id"
          >
            <td>{{ query.id }}</td><td>{{ query.status }}</td><td>{{ query.reason ?? '—' }}</td>
          </tr>
        </tbody>
      </table>
    </div>
    <div
      v-if="windowProvenance"
      data-testid="window-provenance"
      class="table-wrap"
      tabindex="0"
      role="region"
      :aria-label="shellTables ? SOURCE_ACQUISITION_LABELS.windowProvenanceRegion : 'Window provenance'"
    >
      <table>
        <thead>
          <tr>
            <th
              v-for="head in (shellTables ? SOURCE_ACQUISITION_LABELS.windowProvenanceHeads : ['Window', 'Value'])"
              :key="head"
            >
              {{ head }}
            </th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="row in windowProvenanceRows"
            :key="row.label"
          >
            <td>{{ row.label }}</td><td>{{ row.value }}</td>
          </tr>
        </tbody>
      </table>
    </div>
    <div
      v-for="warning in windowProvenance?.warnings ?? []"
      :key="warning.code"
      data-testid="resolution-reduced"
      class="notice notice-warn"
      role="note"
    >
      <p>
        <template v-if="shellTables">
          <code>{{ warning.code }}</code>: {{ SOURCE_ACQUISITION_LABELS.resolutionReduced(formatMillis(warning.requested_step_ms), formatMillis(warning.applied_step_ms)) }}
        </template>
        <template v-else>
          {{ warning.code }}: the step was raised from {{ formatMillis(warning.requested_step_ms) }} to {{ formatMillis(warning.applied_step_ms) }}.
          Short spikes inside a coarser interval are averaged out for these series:
        </template>
      </p>
      <ul>
        <li
          v-for="series in warning.series"
          :key="series.id"
        >
          {{ series.id }} ({{ series.aggregation }})
        </li>
      </ul>
    </div>
  </section>

  <template v-if="showGroup('diagnostics')">
    <section
      v-for="context in errorContexts"
      :key="`${context.profile_id}:${context.id}`"
      class="panel"
      data-testid="opensearch-context"
      :lang="shellTables ? 'ru' : undefined"
      :aria-label="shellTables ? OPENSEARCH_LABELS.region(context.profile_id) : `OpenSearch errors: ${context.profile_id}`"
    >
      <h2>{{ shellTables ? OPENSEARCH_LABELS.title(context.profile_id) : `OpenSearch errors — ${context.profile_id}` }}</h2>
      <p>{{ shellTables ? OPENSEARCH_LABELS.summary(context.total_errors, context.error_rate_per_minute, context.coverage.status) : `${context.total_errors} errors · ${context.error_rate_per_minute} errors/min · ${context.coverage.status}` }}</p>
      <p v-if="context.coverage.reasons.length">
        {{ context.coverage.reasons.join(', ') }}
      </p>
      <p>{{ shellTables ? OPENSEARCH_LABELS.note : 'Error context does not change SLA verdicts. Samples are bounded, not an exhaustive error log.' }}</p>
      <div
        class="table-wrap"
        tabindex="0"
        role="region"
        :aria-label="shellTables ? OPENSEARCH_LABELS.groupsRegion(context.profile_id) : `OpenSearch error groups: ${context.profile_id}`"
      >
        <table>
          <thead>
            <tr>
              <th
                v-for="head in (shellTables ? OPENSEARCH_LABELS.heads : ['Service', 'Error type', 'Count', 'First / last (epoch ms)', 'Samples'])"
                :key="head"
              >
                {{ head }}
              </th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="group in context.groups"
              :key="JSON.stringify([group.service, group.error_type])"
            >
              <td>{{ group.service }}</td><td>{{ group.error_type }}</td><td>{{ group.count }}</td>
              <td>{{ group.first_epoch_ms }} / {{ group.last_epoch_ms }}</td>
              <td>
                <div
                  v-for="(sample, index) in group.samples"
                  :key="index"
                >
                  <p>{{ sample.message }}{{ sample.message_truncated ? (shellTables ? OPENSEARCH_LABELS.truncated : ' [truncated]') : '' }}</p>
                  <a
                    :href="sample.source_url"
                    target="_blank"
                    rel="noopener noreferrer"
                  >{{ shellTables ? OPENSEARCH_LABELS.sourceDocument : 'Source document' }}</a>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>

    <section
      v-for="context in postgresContexts"
      :key="context.profile_id ?? 'postgres-context'"
      class="panel"
      data-testid="postgres-context"
      :lang="shellTables ? 'ru' : undefined"
      :aria-label="shellTables ? POSTGRES_LABELS.region(context.profile_id ?? POSTGRES_LABELS.unknownProfile) : `PostgreSQL context: ${context.profile_id ?? 'unknown profile'}`"
    >
      <h2>{{ shellTables ? POSTGRES_LABELS.title(context.profile_id ?? POSTGRES_LABELS.unknownProfile) : `PostgreSQL changes — ${context.profile_id ?? 'unknown profile'}` }}</h2>
      <p>{{ context.status }} · {{ context.reasons.join(', ') || (shellTables ? POSTGRES_LABELS.noGaps : 'No coverage gaps') }}</p>
      <template v-if="context.configuration_changes?.length">
        <h3>{{ shellTables ? POSTGRES_LABELS.configTitle : 'Configuration changes' }}</h3>
        <div
          class="table-wrap"
          tabindex="0"
          role="region"
          :aria-label="shellTables ? POSTGRES_LABELS.configRegion : 'PostgreSQL configuration changes'"
        >
          <table>
            <thead>
              <tr>
                <th
                  v-for="head in (shellTables ? POSTGRES_LABELS.configHeads : ['Setting', 'Pre', 'Post'])"
                  :key="head"
                  scope="col"
                >
                  {{ head }}
                </th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="change in context.configuration_changes"
                :key="change.name"
              >
                <td>{{ change.name }}</td><td>{{ formatOptional(change.pre) }}</td><td>{{ formatOptional(change.post) }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </template>
      <h3>{{ shellTables ? POSTGRES_LABELS.tablesTitle : 'Table changes' }}</h3>
      <div
        v-if="context.tables.length"
        class="table-wrap"
        tabindex="0"
        role="region"
        :aria-label="shellTables ? POSTGRES_LABELS.tablesRegion : 'PostgreSQL table changes'"
      >
        <table>
          <thead>
            <tr>
              <th
                v-for="head in (shellTables ? POSTGRES_LABELS.tableHeads : ['Table', 'Status / reasons', 'Rows Δ', 'Inserted / deleted / updated', 'Changed keys'])"
                :key="head"
                scope="col"
              >
                {{ head }}
              </th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="table in context.tables"
              :key="`${table.schema}.${table.table}`"
            >
              <td>{{ table.schema }}.{{ table.table }}</td>
              <td>{{ table.status }} · {{ table.reasons.join(', ') || '—' }}</td>
              <td>{{ formatOptional(table.row_count_delta) }}</td>
              <td>{{ formatOptional(table.inserted) }} / {{ formatOptional(table.deleted) }} / {{ formatOptional(table.updated) }}</td>
              <td>
                {{ table.changed_keys.map((change) => `${shellTables ? (POSTGRES_LABELS.changes[change.change] ?? change.change) : change.change}: ${change.key.join(' / ')}`).join(', ') || '—' }}{{ table.keys_truncated ? (shellTables ? POSTGRES_LABELS.truncated : ' [truncated]') : '' }}
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <p v-else>
        {{ shellTables ? POSTGRES_LABELS.noTables : 'No table captures.' }}
      </p>
      <h3>{{ shellTables ? POSTGRES_LABELS.statementsTitle : 'Statement deltas' }}</h3>
      <p>
        {{ context.statements.status }} · {{ context.statements.reasons.join(', ') || (shellTables ? POSTGRES_LABELS.noGaps : 'No coverage gaps') }} ·
        {{ shellTables ? POSTGRES_LABELS.unmatched(context.statements.unmatched_pre.length, context.statements.unmatched_post.length) : `unmatched pre/post: ${context.statements.unmatched_pre.length} / ${context.statements.unmatched_post.length}` }}
      </p>
      <div
        v-if="context.statements.rows.length"
        class="table-wrap"
        tabindex="0"
        role="region"
        :aria-label="shellTables ? POSTGRES_LABELS.statementsRegion : 'PostgreSQL statement deltas'"
      >
        <table>
          <thead>
            <tr>
              <th
                v-for="head in (shellTables ? POSTGRES_LABELS.statementHeads : ['Database / user / query', 'Calls', 'Total execution time', 'Rows', 'Shared blocks hit / read', 'Temp blocks written'])"
                :key="head"
                scope="col"
              >
                {{ head }}
              </th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="statement in context.statements.rows"
              :key="JSON.stringify([statement.dbid, statement.userid, statement.queryid, statement.toplevel])"
            >
              <td>{{ statement.dbid }} / {{ statement.userid }} / {{ statement.queryid ?? (shellTables ? POSTGRES_LABELS.na : 'N/A') }}{{ statement.toplevel ? '' : (shellTables ? POSTGRES_LABELS.nested : ' (nested)') }}</td>
              <td>{{ statement.calls }}</td>
              <td>{{ statement.total_exec_time }}</td>
              <td>{{ statement.rows }}</td>
              <td>{{ statement.shared_blks_hit }} / {{ statement.shared_blks_read }}</td>
              <td>{{ statement.temp_blks_written }}</td>
            </tr>
          </tbody>
        </table>
      </div>
      <p v-else>
        {{ shellTables ? POSTGRES_LABELS.noStatements : 'No matched statement deltas.' }}
      </p>
      <p>
        <template v-if="shellTables">
          {{ POSTGRES_LABELS.pgProfileNote(context.pg_profile.status, context.pg_profile.reasons.join(', ') || POSTGRES_LABELS.noGaps) }}
        </template>
        <template v-else>
          pg_profile: {{ context.pg_profile.status }} · {{ context.pg_profile.reasons.join(', ') || 'No coverage gaps' }}.
          Attached HTML is download-only and is never rendered here.
        </template>
      </p>
    </section>

    <section
      v-if="diagnosticSummaries.length || correlationPairs.length || anomalyChecks.length || anomalyEpisodes.length"
      id="diagnostic-results"
      class="panel"
      :lang="shellTables ? 'ru' : undefined"
      aria-labelledby="diagnostic-results-title"
    >
      <h2 id="diagnostic-results-title">
        {{ shellTables ? DIAGNOSTICS_LABELS.title : 'Run diagnostics' }}
      </h2>
      <p>{{ shellTables ? DIAGNOSTICS_LABELS.lead : 'Observed associations and reference-based episodes. They do not establish causality or change SLA verdicts. No finding does not establish healthy operation.' }}</p>
      <div
        v-for="item in diagnosticSummaries"
        :key="item.id"
      >
        <p>{{ shellTables ? DIAGNOSTICS_LABELS.summaryStatus(item.status, DIAGNOSTICS_LABELS.uncertainty[item.uncertainty] ?? item.uncertainty) : `${item.status} · Uncertainty: ${item.uncertainty}` }}</p>
        <p>{{ shellTables ? DIAGNOSTICS_LABELS.summaryCounts(item.pairs_evaluable, item.pairs_tested, item.anomalies_tested, item.episodes_reported, item.suppressed_short_episodes) : `${item.pairs_evaluable} / ${item.pairs_tested} pairs evaluable; ${item.anomalies_tested} anomaly checks; ${item.episodes_reported} episodes; ${item.suppressed_short_episodes} short episodes suppressed.` }}</p>
        <p v-if="item.reasons.length">
          {{ item.reasons.join(', ') }}
        </p>
      </div>
      <div
        v-if="shellTables && correlationPairs.length"
        data-testid="correlation-hypotheses"
      >
        <h3>{{ CORRELATION_LABELS.title }}</h3>
        <p data-testid="correlation-note">
          {{ CORRELATION_LABELS.note }}
        </p>
        <div
          v-if="selectedHypotheses.length"
          class="table-wrap"
          tabindex="0"
          role="region"
          :aria-label="CORRELATION_LABELS.regionAria"
        >
          <table>
            <thead>
              <tr>
                <th
                  v-for="head in CORRELATION_LABELS.heads"
                  :key="head"
                  scope="col"
                >
                  {{ head }}
                </th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="hypothesis in selectedHypotheses"
                :key="hypothesis.key"
              >
                <td>{{ hypothesis.windowId }}</td>
                <td>{{ hypothesis.series }}</td>
                <td>{{ hypothesis.loadMetric }}</td>
                <td>{{ hypothesis.lagSeconds }}</td>
                <td>{{ hypothesis.rho }}</td>
                <td>{{ hypothesis.adjustedP }}</td>
                <td>{{ hypothesis.familySize }}</td>
                <td>{{ hypothesis.note }}</td>
              </tr>
            </tbody>
          </table>
        </div>
        <p v-else>
          {{ CORRELATION_LABELS.empty }}
        </p>
        <ul
          v-if="unavailableHypotheses.length"
          data-testid="correlation-unavailable"
        >
          <li
            v-for="family in unavailableHypotheses"
            :key="family.windowId"
          >
            {{ CORRELATION_LABELS.unavailableLine(family.windowId, family.count, family.total, family.reasons) }}
          </li>
        </ul>
      </div>
      <div
        v-if="correlationPairs.length && !shellTables"
        class="table-wrap"
        tabindex="0"
        role="region"
        aria-label="Observed associations"
      >
        <table>
          <thead>
            <tr>
              <th scope="col">
                Pair / window
              </th><th scope="col">
                Resource / entity
              </th><th scope="col">
                Load metric
              </th><th scope="col">
                Paired / expected cells
              </th><th scope="col">
                Raw rho
              </th><th scope="col">
                Partial rho (zero lag)
              </th><th scope="col">
                Best lag (ms) / rho
              </th><th scope="col">
                Status / uncertainty / reasons
              </th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="item in correlationPairs"
              :key="item.id"
            >
              <td>{{ item.pair_id }} / {{ item.window_id }}</td>
              <td>{{ item.resource_series_id }} ({{ item.resource_unit }}) / {{ item.entity }}</td>
              <td>{{ item.load_metric }} ({{ item.load_unit }})</td>
              <td>{{ item.paired_cells }} / {{ item.expected_cells }}</td>
              <td>{{ item.raw_rho ?? (shellTables ? DIAGNOSTICS_LABELS.na : 'N/A') }}</td>
              <td>{{ item.partial_rho ?? (shellTables ? DIAGNOSTICS_LABELS.na : 'N/A') }}</td>
              <td>{{ item.best_lag_ms ?? (shellTables ? DIAGNOSTICS_LABELS.na : 'N/A') }} / {{ item.best_lag_rho ?? (shellTables ? DIAGNOSTICS_LABELS.na : 'N/A') }}</td>
              <td>{{ item.status }} · {{ item.uncertainty }} · {{ item.reasons.join(', ') || '—' }}</td>
            </tr>
          </tbody>
        </table>
      </div>
      <div
        v-if="anomalyChecks.length"
        class="table-wrap"
        tabindex="0"
        role="region"
        :aria-label="shellTables ? DIAGNOSTICS_LABELS.anomalyChecksRegion : 'Reference anomaly checks'"
      >
        <table>
          <thead>
            <tr>
              <th
                v-for="head in (shellTables ? DIAGNOSTICS_LABELS.anomalyCheckHeads : ['Rule / window / reference', 'Reference median / MAD', 'Reference cells', 'Evaluation cells', 'Episodes / suppressed', 'Status / reasons'])"
                :key="head"
                scope="col"
              >
                {{ head }}
              </th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="item in anomalyChecks"
              :key="item.id"
            >
              <td>{{ item.rule_id }} / {{ item.window_id }} / {{ item.reference_window_id }}</td>
              <td>{{ item.reference_median ?? (shellTables ? DIAGNOSTICS_LABELS.na : 'N/A') }} / {{ item.reference_mad ?? (shellTables ? DIAGNOSTICS_LABELS.na : 'N/A') }}</td>
              <td>{{ item.reference_observed_cells }} / {{ item.reference_expected_cells }}</td>
              <td>{{ item.observed_cells }} / {{ item.expected_cells }}</td>
              <td>{{ item.episodes_reported }} / {{ item.suppressed_short_episodes }}</td>
              <td>{{ item.status }} · {{ item.reasons.join(', ') || '—' }}</td>
            </tr>
          </tbody>
        </table>
      </div>
      <div
        v-if="anomalyEpisodes.length"
        class="table-wrap"
        tabindex="0"
        role="region"
        :aria-label="shellTables ? DIAGNOSTICS_LABELS.episodesRegion : 'Observed anomaly episodes'"
        data-testid="anomaly-episodes"
      >
        <table>
          <thead>
            <tr>
              <th
                v-for="head in (shellTables ? DIAGNOSTICS_LABELS.episodeHeads : ['Rule / window / reference', 'Metric / entity', 'UTC interval (epoch ms)', 'Duration / direction', 'Observed range / unit', 'Maximum absolute delta', 'Reasons'])"
                :key="head"
                scope="col"
              >
                {{ head }}
              </th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="item in anomalyEpisodes"
              :key="String(item.id)"
            >
              <td>{{ item.rule_id }} / {{ item.window_id }} / {{ item.reference_window_id }}</td>
              <td>{{ item.metric }} / {{ item.entity }}</td>
              <td>{{ item.from_epoch_ms }}–{{ item.to_epoch_ms }}</td>
              <td>{{ formatDuration(Number(item.duration_ms)) }} / {{ shellTables ? (DIAGNOSTICS_LABELS.directions[String(item.direction ?? '')] ?? item.direction) : item.direction }}</td>
              <td>{{ formatOptional(item.observed_min) }}–{{ formatOptional(item.observed_max) }} {{ item.unit }}</td>
              <td>{{ formatOptional(item.max_abs_delta) }}</td>
              <td>{{ arrayAt(item, 'reasons').join(', ') || '—' }}</td>
            </tr>
          </tbody>
        </table>
      </div>
      <details>
        <summary>{{ shellTables ? DIAGNOSTICS_LABELS.rawSummary : 'Raw diagnostic evidence' }}</summary>
        <pre>{{ diagnosticDetails }}</pre>
      </details>
    </section>
  </template>

  <section
    v-if="!shellTables"
    id="transaction-metrics"
    class="panel"
    aria-labelledby="transaction-metrics-title"
  >
    <div class="section-heading">
      <p class="eyebrow">
        Impact order
      </p><h2 id="transaction-metrics-title">
        Transaction metrics
      </h2>
    </div>
    <div
      class="table-wrap"
      tabindex="0"
      role="region"
      aria-label="Transaction metrics"
    >
      <table>
        <thead><tr><th>Path</th><th>Transaction</th><th>Kind</th><th>Samples</th><th>Errors</th><th>Error rate</th><th>P99</th><th>Throughput</th><th>Policy</th></tr></thead>
        <tbody>
          <tr
            v-for="row in transactions"
            :key="row.id"
          >
            <td>{{ row.path || '—' }}</td><td>{{ row.label }}</td><td>{{ row.kind }}</td><td>{{ row.samples.toLocaleString() }}</td><td>{{ row.errorCount.toLocaleString() }}</td><td>{{ row.errorRate }}</td><td>{{ row.p99 }}</td><td>{{ row.throughput }}</td><td>
              <span
                class="status-text"
                :data-status="row.policyStatus"
              >{{ row.policyStatus }}</span>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </section>

  <section
    v-if="showGroup('normalized')"
    id="normalized-data"
    class="panel"
    aria-labelledby="normalized-data-title"
    :lang="shellTables ? 'ru' : undefined"
  >
    <div class="section-heading">
      <p class="eyebrow">
        {{ normText.eyebrow }}
      </p><h2 id="normalized-data-title">
        {{ normText.title }}
      </h2>
    </div>
    <div class="bucket-controls">
      <label>{{ normText.rollup }} <select
        :value="rollup"
        :aria-label="normText.rollupAria"
        @change="updateRollup"
      ><option
        v-for="seconds in [1, 10, 30, 60]"
        :key="seconds"
        :value="seconds"
      >{{ normText.option(seconds) }}</option></select></label>
      <label>{{ normText.start }} <input
        :value="rangeStart"
        type="number"
        min="0"
        @input="updateRange('update:range-start', $event)"
      ></label>
      <label>{{ normText.end }} <input
        :value="rangeEnd"
        type="number"
        min="0"
        @input="updateRange('update:range-end', $event)"
      ></label>
      <button
        type="button"
        @click="emit('refresh-buckets')"
      >
        {{ normText.refresh }}
      </button>
    </div>
    <div>
      <LoadCharts
        :markers="markers"
        :buckets="buckets"
        :rollup="bucketRollup"
        :labels="shellTables ? NORMALIZED_LABELS.charts : undefined"
      />
    </div>
    <div
      ref="bucketRegion"
      class="table-wrap"
      tabindex="0"
      role="region"
      :aria-label="normText.region"
    >
      <table>
        <thead>
          <tr>
            <th
              v-for="head in normText.heads"
              :key="head"
            >
              {{ head }}
            </th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="row in shownBucketRows"
            :key="row.id"
            :data-status="row.available ? 'available' : 'missing'"
          >
            <td>{{ row.time }}</td><td>{{ row.rps }}</td><td>{{ row.errors }}</td><td>{{ row.p95 }}</td><td>{{ row.max }}</td><td>{{ row.status }}</td>
          </tr>
        </tbody>
      </table>
    </div>
    <div
      v-if="shellTables && bucketRows.length > BUCKET_STEP"
      class="bucket-more"
    >
      <p
        role="status"
        data-testid="bins-shown"
      >
        {{ NORMALIZED_LABELS.shown(shownBucketRows.length, bucketRows.length) }}
      </p>
      <button
        v-if="shownBucketRows.length < bucketRows.length"
        type="button"
        @click="growBuckets(false)"
      >
        {{ NORMALIZED_LABELS.more(Math.min(BUCKET_STEP, bucketRows.length - shownBucketRows.length)) }}
      </button>
      <button
        v-if="bucketRows.length - shownBucketRows.length > BUCKET_STEP"
        type="button"
        @click="growBuckets(true)"
      >
        {{ NORMALIZED_LABELS.all(bucketRows.length) }}
      </button>
    </div>
  </section>
</template>

<style scoped>
th button { min-height: 44px; min-width: 44px; border: 0; background: transparent; color: inherit; font: inherit; text-align: left; padding: 0 4px; cursor: pointer; }
th[aria-sort="ascending"] button::after { content: " \2191"; }
th[aria-sort="descending"] button::after { content: " \2193"; }
.bucket-more { display: flex; flex-wrap: wrap; align-items: center; gap: 12px; margin-top: 12px; }
.bucket-more p { margin: 0; }
.bucket-more button { width: auto; min-height: 44px; }
</style>
