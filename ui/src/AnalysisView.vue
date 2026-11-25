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
import type { AnalysisResult, Bucket, SourceSummaryEvidence, OpenSearchEvidence, PostgresContextEvidence } from './types'

const props = defineProps<{
  result: AnalysisResult
  visibleGroup?: TablesSubTabKey
  buckets: Bucket[]
  rollup: number
  bucketRollup: number
  markers?: Array<{ at_ms: number; service: string; error_type: string; message: string }>
  rangeStart: string
  rangeEnd: string
}>()

const showGroup = (group: TablesSubTabKey) =>
  !props.visibleGroup || props.visibleGroup === 'all' || props.visibleGroup === group

const emit = defineEmits<{
  'update:rollup': [value: number]
  'update:range-start': [value: string]
  'update:range-end': [value: string]
  'refresh-buckets': []
}>()

type Evidence = Record<string, unknown>

const evidence = computed(() => props.result.evidence as unknown as Evidence[])
const metrics = computed(() => evidence.value.filter((item) => item.type === 'metric_summary'))
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
  sortResourceSummaries(resourceSummaries.value, resSummarySort.value, resSummaryDir.value))
const sortedResourceChecks = computed(() =>
  sortResourceChecks(resourceChecks.value, resCheckSort.value, resCheckDir.value))

const resSummarySortCols: Array<ResourceSummarySortKey | null> = [
  'series_id', 'metric', null, 'entity', null, null, 'window_id',
  'observed_cells', 'missing_cells', null, 'min', 'max', 'mean', 'median',
  null, null, null, 'q95', null, null, null, 'slope_per_second', null, null,
]

function resSummaryAria(key: ResourceSummarySortKey | null): 'ascending' | 'descending' | 'none' | undefined {
  if (!key) return undefined
  return resSummarySort.value !== key ? 'none' : resSummaryDir.value === 1 ? 'ascending' : 'descending'
}

function toggleResSummarySort(key: ResourceSummarySortKey) {
  if (resSummarySort.value === key) resSummaryDir.value = resSummaryDir.value === 1 ? -1 : 1
  else {
    resSummarySort.value = key
    resSummaryDir.value = key === 'series_id' || key === 'metric' || key === 'entity' || key === 'window_id' ? 1 : -1
  }
}

function resCheckAria(key: ResourceCheckSortKey): 'ascending' | 'descending' | 'none' {
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
  return (TABLES_LABELS.statusText as Record<string, string>)[raw] ?? raw
}

function checkCoverage(item: Evidence): string | null {
  const expected = item.expected_cells
  const observed = item.observed_cells
  if (typeof expected !== 'number' || typeof observed !== 'number' || expected <= 0) return null
  const percent = new Intl.NumberFormat('ru-RU', { maximumFractionDigits: 2 }).format((observed / expected) * 100)
  const gaps = typeof item.missing_cells === 'number' && item.missing_cells > 0
    ? RESOURCE_TABLES_LABELS.missingCoverage(item.missing_cells, formatOptional(item.longest_gap_cells))
    : ''
  return `${observed} / ${expected} (${percent} %)${gaps}`
}
const checkHeads = RESOURCE_CHECK_LABELS.heads
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
  const rows: Array<{ label: string; value: string }> = [{ label: w.windowOrigin, value: String(provenance.window_origin) }]
  const recognizedStart = provenance.recognized_start_epoch_ms
  const recognizedEnd = provenance.recognized_end_epoch_ms
  if (recognizedStart !== undefined && recognizedEnd !== undefined) {
    rows.push({ label: w.recognizedPeriod, value: `${formatEpochMs(recognizedStart)} – ${formatEpochMs(recognizedEnd)}` })
  }
  if (provenance.requested_margin_ms !== undefined) rows.push({ label: w.requestedMargin, value: formatMillis(provenance.requested_margin_ms) })
  if (provenance.applied_margin_ms !== undefined) rows.push({ label: w.appliedMargin, value: formatMillis(provenance.applied_margin_ms) })
  if (provenance.max_idle_gap_ms !== undefined) rows.push({ label: w.maxIdleGap, value: formatMillis(provenance.max_idle_gap_ms) })
  if (provenance.detected_idle_gaps !== undefined) rows.push({ label: w.detectedIdleGaps, value: String(provenance.detected_idle_gaps) })
  const longestIdleGap = provenance.longest_idle_gap_ms
  if (longestIdleGap !== undefined) rows.push({ label: w.longestIdleGap, value: longestIdleGap === null ? '—' : formatMillis(longestIdleGap) })
  if (provenance.auto_window_status !== undefined) rows.push({ label: w.autoWindowStatus, value: provenance.auto_window_status })
  if (provenance.step_origin !== undefined) rows.push({ label: w.stepOrigin, value: provenance.step_origin })
  if (provenance.requested_step_ms !== undefined) rows.push({ label: w.requestedStep, value: formatMillis(provenance.requested_step_ms) })
  // The applied step is published only inside the RESOLUTION_REDUCED warning; without a warning it is not stated.
  const reduced = provenance.warnings?.[0]
  if (reduced !== undefined) rows.push({ label: w.appliedStep, value: formatMillis(reduced.applied_step_ms) })
  if (provenance.series_count !== undefined && provenance.cells_per_series !== undefined) {
    rows.push({ label: w.seriesCells, value: `${provenance.series_count} × ${provenance.cells_per_series} ${w.ofWord} ${provenance.cell_budget ?? '—'}` })
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
const overallMetrics = computed(() => metricValues(overall.value))
// The block is in Russian and the table grows in portions (a 15-minute run has 900 one-second bins).
const BUCKET_STEP = 50
const bucketLimit = ref(BUCKET_STEP)
watch(() => props.buckets, () => { bucketLimit.value = BUCKET_STEP })
const bucketRows = computed(() => {
  const width = props.bucketRollup * 1_000
  const millis = (value: number | undefined) => value === undefined ? NORMALIZED_LABELS.noValue : `${value.toLocaleString('ru-RU')} ${NORMALIZED_LABELS.unit}`
  const rows: Array<{ id: string; time: string; rps: string; errors: string; p95: string; max: string; status: string; available: boolean }> = []
  let previousStart: number | undefined
  for (const bucket of [...props.buckets].sort((left, right) => left.bucket_start_ms - right.bucket_start_ms)) {
    const start = bucket.bucket_start_ms
    if (previousStart !== undefined && start > previousStart + width) {
      rows.push({
        id: `missing-${previousStart + width}`,
        time: `${previousStart + width}–${start} ${NORMALIZED_LABELS.unit}`,
        rps: '—',
        errors: '—',
        p95: '—',
        max: '—',
        status: NORMALIZED_LABELS.missing,
        available: false,
      })
    }
    rows.push({
      id: `bucket-${start}`,
      time: `${start} ${NORMALIZED_LABELS.unit}`,
      rps: (bucket.sample_count / props.bucketRollup).toLocaleString('ru-RU', { minimumFractionDigits: 2, maximumFractionDigits: 2 }),
      errors: bucket.error_count.toLocaleString('ru-RU'),
      p95: millis(bucket.p95_latency_ms),
      max: millis(bucket.max_latency_ms),
      status: NORMALIZED_LABELS.available,
      available: true,
    })
    previousStart = start
  }
  return rows
})
const bucketRegion = ref<HTMLElement | null>(null)
// The pressed button disappears when everything is shown: keep the keyboard focus inside the block.
async function growBuckets(all: boolean) {
  bucketLimit.value = all ? bucketRows.value.length : bucketLimit.value + BUCKET_STEP
  await nextTick()
  if (!document.activeElement || document.activeElement === document.body) bucketRegion.value?.focus()
}
const shownBucketRows = computed(() => bucketRows.value.slice(0, bucketLimit.value))

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

function numberAt(item: Evidence | Bucket | undefined, key: string): number | undefined {
  const value = valueAt(item, key)
  return typeof value === 'number' ? value : undefined
}

function arrayAt(item: Evidence, key: string): string[] {
  const value = valueAt(item, key)
  return Array.isArray(value) ? value.filter((entry): entry is string => typeof entry === 'string') : []
}

function formatMillis(value: number | undefined) {
  return value === undefined ? SUMMARY_METRICS_LABELS.noData : `${value.toLocaleString('ru-RU')} ${SUMMARY_METRICS_LABELS.msUnit}`
}

function formatEpochMs(value: number) {
  return new Date(value).toISOString()
}

function formatRatio(value: unknown) {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) return SUMMARY_METRICS_LABELS.noData
  const numerator = numberAt(value as Evidence, 'numerator')
  const denominator = numberAt(value as Evidence, 'denominator')
  return numerator === undefined || denominator === undefined || denominator === 0
    ? SUMMARY_METRICS_LABELS.noData
    : `${((numerator / denominator) * 100).toFixed(2)}%`
}

function formatThroughput(value: unknown) {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) return SUMMARY_METRICS_LABELS.noData
  const numerator = numberAt(value as Evidence, 'numerator')
  const denominator = numberAt(value as Evidence, 'denominator')
  if (numerator === undefined || denominator === undefined || denominator === 0) {
    return SUMMARY_METRICS_LABELS.noData
  }
  const rps = numerator / denominator
  return `${new Intl.NumberFormat('ru-RU', { minimumFractionDigits: 2, maximumFractionDigits: 2 }).format(rps)} ${SUMMARY_METRICS_LABELS.rpsUnit}`
}

// Exact decimals from the server (up to 34 digits) are shown short, the full value stays in the cell title.
const shortFraction = new Intl.NumberFormat('ru-RU', { maximumFractionDigits: 4 })
const shortSignificant = new Intl.NumberFormat('ru-RU', { maximumSignificantDigits: 4 })
function shortDecimal(value: string) {
  if (!/^-?\d{1,15}\.\d{7,}$/.test(value)) return value
  const number = Number(value)
  if (!Number.isFinite(number)) return value
  return Math.abs(number) >= 1 ? shortFraction.format(number) : shortSignificant.format(number)
}

function formatOptional(value: unknown) {
  return value === null || value === undefined ? '—' : String(value)
}

function shortStatistic(value: unknown) {
  return typeof value === 'string' ? shortDecimal(value) : formatOptional(value)
}

function statistic(item: Evidence, key: string) {
  const values = valueAt(item, 'statistics')
  return values !== null && typeof values === 'object' && !Array.isArray(values)
    ? shortStatistic(valueAt(values as Evidence, key))
    : '—'
}

function statisticTitle(item: Evidence, key: string) {
  const values = valueAt(item, 'statistics')
  const value = values !== null && typeof values === 'object' && !Array.isArray(values) ? valueAt(values as Evidence, key) : undefined
  return typeof value === 'string' && shortDecimal(value) !== value ? value : undefined
}

function bindingText(item: Evidence) {
  return JSON.stringify(item, null, 2)
}

function formatDuration(milliseconds: number) {
  const fmt = (val: number) => new Intl.NumberFormat('ru-RU', { minimumFractionDigits: 1, maximumFractionDigits: 1 }).format(val)
  return milliseconds < 60_000
    ? DIAGNOSTICS_LABELS.durationSec(fmt(milliseconds / 1_000))
    : DIAGNOSTICS_LABELS.durationMin(fmt(milliseconds / 60_000))
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
    lang="ru"
    aria-labelledby="summary-metrics-title"
  >
    <div class="section-heading">
      <p class="eyebrow">
        {{ SUMMARY_METRICS_LABELS.eyebrow }}
      </p><h2 id="summary-metrics-title">
        {{ SUMMARY_METRICS_LABELS.title }}
      </h2>
    </div>
    <div class="metric-grid">
      <article class="metric-card">
        <p>{{ SUMMARY_METRICS_LABELS.p50 }}</p><strong>{{ overallMetrics.p50 }}</strong><small>{{ SUMMARY_METRICS_LABELS.latencyHint }}</small>
      </article>
      <article class="metric-card">
        <p>{{ SUMMARY_METRICS_LABELS.p95 }}</p><strong>{{ overallMetrics.p95 }}</strong><small>{{ SUMMARY_METRICS_LABELS.latencyHint }}</small>
      </article>
      <article class="metric-card">
        <p>{{ SUMMARY_METRICS_LABELS.p99 }}</p><strong>{{ overallMetrics.p99 }}</strong><small>{{ SUMMARY_METRICS_LABELS.latencyHint }}</small>
      </article>
      <article class="metric-card">
        <p>{{ SUMMARY_METRICS_LABELS.throughput }}</p><strong>{{ formatThroughput(valueAt(overall, 'throughput_rps')) }}</strong><small>{{ SUMMARY_METRICS_LABELS.throughputHint }}</small>
      </article>
    </div>
  </section>

  <section
    v-if="showGroup('resources') && (resourceSummaries.length || windowPolicySummaries.length || resourceChecks.length || resourceBindings.length)"
    id="resource-results"
    class="panel"
    lang="ru"
    aria-labelledby="resource-results-title"
  >
    <div class="section-heading">
      <p class="eyebrow">
        {{ RESOURCE_TABLES_LABELS.eyebrow }}
      </p><h2 id="resource-results-title">
        {{ RESOURCE_TABLES_LABELS.title }}
      </h2>
    </div>
    <details
      v-for="item in resourceBindings"
      :key="String(item.id)"
    >
      <summary>{{ RESOURCE_TABLES_LABELS.bindingSummary }}</summary>
      <pre>{{ bindingText(item) }}</pre>
    </details>
    <div
      v-if="windowPolicySummaries.length"
      class="table-wrap"
      tabindex="0"
      role="region"
      :aria-label="RESOURCE_TABLES_LABELS.windowPolicyRegion"
    >
      <table>
        <thead>
          <tr>
            <th
              v-for="head in RESOURCE_TABLES_LABELS.windowPolicyHeads"
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
      :aria-label="RESOURCE_TABLES_LABELS.summariesRegion"
    >
      <table>
        <thead>
          <tr>
            <th
              v-for="(head, index) in RESOURCE_TABLES_LABELS.summaryHeads"
              :key="head"
              scope="col"
              :aria-sort="resSummaryAria(resSummarySortCols[index])"
            >
              <button
                v-if="resSummarySortCols[index]"
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
              <template v-if="arrayAt(item, 'reasons').length">
                <span
                  v-for="code in arrayAt(item, 'reasons')"
                  :key="code"
                >{{ verdictReasonText(code) }} <code>{{ code }}</code> </span>
              </template>
              <template v-else>
                —
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
      :aria-label="RESOURCE_TABLES_LABELS.checksRegion"
    >
      <table>
        <thead>
          <tr>
            <th :aria-sort="resCheckAria('window_id')">
              <button
                type="button"
                :aria-label="TABLES_LABELS.sortBy(checkHeads.window)"
                @click="toggleResCheckSort('window_id')"
              >
                {{ checkHeads.window }}
              </button>
            </th>
            <th :aria-sort="resCheckAria('rule_id')">
              <button
                type="button"
                :aria-label="TABLES_LABELS.sortBy(checkHeads.rule)"
                @click="toggleResCheckSort('rule_id')"
              >
                {{ checkHeads.rule }}
              </button>
            </th>
            <th
              v-if="platformChecks"
              :aria-sort="resCheckAria('service')"
            >
              <button
                type="button"
                :aria-label="TABLES_LABELS.sortBy(checkHeads.service)"
                @click="toggleResCheckSort('service')"
              >
                {{ checkHeads.service }}
              </button>
            </th>
            <th :aria-sort="resCheckAria('series_id')">
              <button
                type="button"
                :aria-label="TABLES_LABELS.sortBy(checkHeads.series)"
                @click="toggleResCheckSort('series_id')"
              >
                {{ checkHeads.series }}
              </button>
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
                type="button"
                :aria-label="TABLES_LABELS.sortBy(checkHeads.status)"
                @click="toggleResCheckSort('status')"
              >
                {{ checkHeads.status }}
              </button>
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
                {{ RESOURCE_TABLES_LABELS.platformRule(formatOptional(item.platform_rule_id)) }}
              </template>
            </td><td>{{ formatOptional(item.series_id) }}</td><td>{{ RESOURCE_TABLES_LABELS.operators[String(item.operator ?? '')] ?? formatOptional(item.operator) }}</td><td>{{ formatOptional(item.threshold) }} {{ formatOptional(item.unit) }}</td><td>{{ RESOURCE_TABLES_LABELS.effects[String(item.effect ?? '')] ?? formatOptional(item.effect) }}</td><td>
              <span
                class="status-text"
                :data-status="formatOptional(item.status)"
              >{{ statusLabel(item.status) }}</span>
            </td><td>
              <template v-if="typeof item.reason === 'string' && item.reason">
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
                {{ RESOURCE_TABLES_LABELS.presumedViolation }}
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
    lang="ru"
    aria-labelledby="source-acquisition-title"
  >
    <div class="section-heading">
      <p class="eyebrow">
        {{ SOURCE_ACQUISITION_LABELS.eyebrow }}
      </p><h2 id="source-acquisition-title">
        {{ SOURCE_ACQUISITION_LABELS.title }}
      </h2>
    </div>
    <div
      v-for="item in sourceSummaries"
      :key="`${item.profile_id}:${item.id}`"
      class="table-wrap"
      tabindex="0"
      role="region"
      :aria-label="SOURCE_ACQUISITION_LABELS.region(item.profile_id)"
    >
      <table>
        <thead>
          <tr>
            <th
              v-for="head in SOURCE_ACQUISITION_LABELS.profileHeads"
              :key="head"
            >
              {{ head }}
            </th>
          </tr>
        </thead>
        <tbody><tr><td>{{ item.profile_id }}</td><td>{{ item.source_kind }} / {{ item.transport }}</td><td>{{ item.status }}</td><td>{{ item.request_count }} / {{ item.retries }}</td><td>{{ item.throttle_wait_ms }}</td><td>{{ item.cap_exceeded ? SOURCE_ACQUISITION_LABELS.capExceeded : SOURCE_ACQUISITION_LABELS.capNotExceeded }}</td></tr></tbody>
      </table>
      <table>
        <thead>
          <tr>
            <th
              v-for="head in SOURCE_ACQUISITION_LABELS.queryHeads"
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
      :aria-label="SOURCE_ACQUISITION_LABELS.windowProvenanceRegion"
    >
      <table>
        <thead>
          <tr>
            <th
              v-for="head in SOURCE_ACQUISITION_LABELS.windowProvenanceHeads"
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
        <code>{{ warning.code }}</code>: {{ SOURCE_ACQUISITION_LABELS.resolutionReduced(formatMillis(warning.requested_step_ms), formatMillis(warning.applied_step_ms)) }}
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
      lang="ru"
      :aria-label="OPENSEARCH_LABELS.region(context.profile_id)"
    >
      <h2>{{ OPENSEARCH_LABELS.title(context.profile_id) }}</h2>
      <p>{{ OPENSEARCH_LABELS.summary(context.total_errors, context.error_rate_per_minute, context.coverage.status) }}</p>
      <p v-if="context.coverage.reasons.length">
        {{ context.coverage.reasons.join(', ') }}
      </p>
      <p>{{ OPENSEARCH_LABELS.note }}</p>
      <div
        class="table-wrap"
        tabindex="0"
        role="region"
        :aria-label="OPENSEARCH_LABELS.groupsRegion(context.profile_id)"
      >
        <table>
          <thead>
            <tr>
              <th
                v-for="head in OPENSEARCH_LABELS.heads"
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
                  <p>{{ sample.message }}{{ sample.message_truncated ? OPENSEARCH_LABELS.truncated : '' }}</p>
                  <a
                    :href="sample.source_url"
                    target="_blank"
                    rel="noopener noreferrer"
                  >{{ OPENSEARCH_LABELS.sourceDocument }}</a>
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
      lang="ru"
      :aria-label="POSTGRES_LABELS.region(context.profile_id ?? POSTGRES_LABELS.unknownProfile)"
    >
      <h2>{{ POSTGRES_LABELS.title(context.profile_id ?? POSTGRES_LABELS.unknownProfile) }}</h2>
      <p>{{ context.status }} · {{ context.reasons.join(', ') || POSTGRES_LABELS.noGaps }}</p>
      <template v-if="context.configuration_changes?.length">
        <h3>{{ POSTGRES_LABELS.configTitle }}</h3>
        <div
          class="table-wrap"
          tabindex="0"
          role="region"
          :aria-label="POSTGRES_LABELS.configRegion"
        >
          <table>
            <thead>
              <tr>
                <th
                  v-for="head in POSTGRES_LABELS.configHeads"
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
      <h3>{{ POSTGRES_LABELS.tablesTitle }}</h3>
      <div
        v-if="context.tables.length"
        class="table-wrap"
        tabindex="0"
        role="region"
        :aria-label="POSTGRES_LABELS.tablesRegion"
      >
        <table>
          <thead>
            <tr>
              <th
                v-for="head in POSTGRES_LABELS.tableHeads"
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
                {{ table.changed_keys.map((change) => `${POSTGRES_LABELS.changes[change.change] ?? change.change}: ${change.key.join(' / ')}`).join(', ') || '—' }}{{ table.keys_truncated ? POSTGRES_LABELS.truncated : '' }}
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <p v-else>
        {{ POSTGRES_LABELS.noTables }}
      </p>
      <h3>{{ POSTGRES_LABELS.statementsTitle }}</h3>
      <p>
        {{ context.statements.status }} · {{ context.statements.reasons.join(', ') || POSTGRES_LABELS.noGaps }} ·
        {{ POSTGRES_LABELS.unmatched(context.statements.unmatched_pre.length, context.statements.unmatched_post.length) }}
      </p>
      <div
        v-if="context.statements.rows.length"
        class="table-wrap"
        tabindex="0"
        role="region"
        :aria-label="POSTGRES_LABELS.statementsRegion"
      >
        <table>
          <thead>
            <tr>
              <th
                v-for="head in POSTGRES_LABELS.statementHeads"
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
              <td>{{ statement.dbid }} / {{ statement.userid }} / {{ statement.queryid ?? POSTGRES_LABELS.na }}{{ statement.toplevel ? '' : POSTGRES_LABELS.nested }}</td>
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
        {{ POSTGRES_LABELS.noStatements }}
      </p>
      <p>
        {{ POSTGRES_LABELS.pgProfileNote(context.pg_profile.status, context.pg_profile.reasons.join(', ') || POSTGRES_LABELS.noGaps) }}
      </p>
    </section>

    <section
      v-if="diagnosticSummaries.length || correlationPairs.length || anomalyChecks.length || anomalyEpisodes.length"
      id="diagnostic-results"
      class="panel"
      lang="ru"
      aria-labelledby="diagnostic-results-title"
    >
      <h2 id="diagnostic-results-title">
        {{ DIAGNOSTICS_LABELS.title }}
      </h2>
      <p>{{ DIAGNOSTICS_LABELS.lead }}</p>
      <div
        v-for="item in diagnosticSummaries"
        :key="item.id"
      >
        <p>{{ DIAGNOSTICS_LABELS.summaryStatus(item.status, DIAGNOSTICS_LABELS.uncertainty[item.uncertainty] ?? item.uncertainty) }}</p>
        <p>{{ DIAGNOSTICS_LABELS.summaryCounts(item.pairs_evaluable, item.pairs_tested, item.anomalies_tested, item.episodes_reported, item.suppressed_short_episodes) }}</p>
        <p v-if="item.reasons.length">
          {{ item.reasons.join(', ') }}
        </p>
      </div>
      <div
        v-if="correlationPairs.length"
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
        v-if="anomalyChecks.length"
        class="table-wrap"
        tabindex="0"
        role="region"
        :aria-label="DIAGNOSTICS_LABELS.anomalyChecksRegion"
      >
        <table>
          <thead>
            <tr>
              <th
                v-for="head in DIAGNOSTICS_LABELS.anomalyCheckHeads"
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
              <td>{{ item.reference_median ?? DIAGNOSTICS_LABELS.na }} / {{ item.reference_mad ?? DIAGNOSTICS_LABELS.na }}</td>
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
        :aria-label="DIAGNOSTICS_LABELS.episodesRegion"
        data-testid="anomaly-episodes"
      >
        <table>
          <thead>
            <tr>
              <th
                v-for="head in DIAGNOSTICS_LABELS.episodeHeads"
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
              <td>{{ formatDuration(Number(item.duration_ms)) }} / {{ DIAGNOSTICS_LABELS.directions[String(item.direction ?? '')] ?? item.direction }}</td>
              <td>{{ formatOptional(item.observed_min) }}–{{ formatOptional(item.observed_max) }} {{ item.unit }}</td>
              <td>{{ formatOptional(item.max_abs_delta) }}</td>
              <td>{{ arrayAt(item, 'reasons').join(', ') || '—' }}</td>
            </tr>
          </tbody>
        </table>
      </div>
      <details>
        <summary>{{ DIAGNOSTICS_LABELS.rawSummary }}</summary>
        <pre>{{ diagnosticDetails }}</pre>
      </details>
    </section>
  </template>

  <section
    v-if="showGroup('normalized')"
    id="normalized-data"
    class="panel"
    aria-labelledby="normalized-data-title"
    lang="ru"
  >
    <div class="section-heading">
      <p class="eyebrow">
        {{ NORMALIZED_LABELS.eyebrow }}
      </p><h2 id="normalized-data-title">
        {{ NORMALIZED_LABELS.title }}
      </h2>
    </div>
    <div class="bucket-controls">
      <label>{{ NORMALIZED_LABELS.rollupLabel }} <select
        :value="rollup"
        :aria-label="NORMALIZED_LABELS.rollupAria"
        @change="updateRollup"
      ><option
        v-for="seconds in [1, 10, 30, 60]"
        :key="seconds"
        :value="seconds"
      >{{ NORMALIZED_LABELS.rollupOption(seconds) }}</option></select></label>
      <label>{{ NORMALIZED_LABELS.startOffset }} <input
        :value="rangeStart"
        type="number"
        min="0"
        @input="updateRange('update:range-start', $event)"
      ></label>
      <label>{{ NORMALIZED_LABELS.endOffset }} <input
        :value="rangeEnd"
        type="number"
        min="0"
        @input="updateRange('update:range-end', $event)"
      ></label>
      <button
        type="button"
        @click="emit('refresh-buckets')"
      >
        {{ NORMALIZED_LABELS.refresh }}
      </button>
    </div>
    <div>
      <LoadCharts
        :markers="markers"
        :buckets="buckets"
        :rollup="bucketRollup"
        :labels="NORMALIZED_LABELS.charts"
      />
    </div>
    <div
      ref="bucketRegion"
      class="table-wrap"
      tabindex="0"
      role="region"
      :aria-label="NORMALIZED_LABELS.region"
    >
      <table>
        <thead>
          <tr>
            <th
              v-for="head in NORMALIZED_LABELS.heads"
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
      v-if="bucketRows.length > BUCKET_STEP"
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
