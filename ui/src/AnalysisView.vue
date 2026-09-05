<script setup lang="ts">
import { computed } from 'vue'
import LoadCharts from './LoadCharts.vue'
import type { AnalysisResult, Bucket } from './types'

const props = defineProps<{
  result: AnalysisResult
  buckets: Bucket[]
  rollup: number
  bucketRollup: number
  rangeStart: string
  rangeEnd: string
}>()

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
const resourceBindings = computed(() => evidence.value.filter((item) => item.type === 'resource_binding'))
const diagnosticSummaries = computed(() => props.result.evidence.filter((item) => item.type === 'diagnostic_summary'))
const correlationPairs = computed(() => props.result.evidence.filter((item) => item.type === 'correlation_pair'))
const anomalyChecks = computed(() => props.result.evidence.filter((item) => item.type === 'anomaly_check'))
const anomalyEpisodes = computed(() => props.result.findings.filter((item) => item.type === 'anomaly_episode'))
const diagnosticDetails = computed(() => JSON.stringify([...diagnosticSummaries.value, ...correlationPairs.value, ...anomalyChecks.value, ...anomalyEpisodes.value], null, 2))
const slaResourceChecks = computed(() => resourceChecks.value.filter((item) => item.effect === 'sla'))
const allSlaChecks = computed(() => [...checks.value, ...slaResourceChecks.value])
const overall = computed(() => metrics.value.find((item) => scope(item).kind === 'overall'))
const failedChecks = computed(() => allSlaChecks.value.filter((item) => item.status === 'FAIL'))

const verdict = computed(() => props.result.policy_verdict)
const verdictText = computed(() => {
  if (verdict.value === 'FAIL') return `FAIL — ${failedChecks.value.length} of ${allSlaChecks.value.length} rules failed`
  if (verdict.value === 'PASS') return `PASS — all ${allSlaChecks.value.length} rules passed`
  if (verdict.value === 'NO_POLICY') return 'NO_POLICY — no policy was supplied'
  return 'NO_VERDICT — one or more rules could not be evaluated'
})

const overallMetrics = computed(() => metricValues(overall.value))
const bucketRows = computed(() => {
  const width = props.bucketRollup * 1_000
  const rows: Array<{ id: string; time: string; rps: string; errors: string; p95: string; max: string; status: string }> = []
  let previousStart: number | undefined
  for (const bucket of [...props.buckets].sort((left, right) => left.bucket_start_ms - right.bucket_start_ms)) {
    const start = bucket.bucket_start_ms
    if (previousStart !== undefined && start > previousStart + width) {
      rows.push({
        id: `missing-${previousStart + width}`,
        time: `${previousStart + width}–${start} ms`,
        rps: '—',
        errors: '—',
        p95: '—',
        max: '—',
        status: 'Missing / no samples',
      })
    }
    rows.push({
      id: `bucket-${start}`,
      time: `${start} ms`,
      rps: (bucket.sample_count / props.bucketRollup).toFixed(2),
      errors: bucket.error_count.toLocaleString(),
      p95: formatMillis(bucket.p95_latency_ms),
      max: formatMillis(bucket.max_latency_ms),
      status: 'Available',
    })
    previousStart = start
  }
  return rows
})
const duration = computed(() => {
  const throughput = valueAt(overall.value, 'throughput_rps')
  if (throughput === null || typeof throughput !== 'object' || Array.isArray(throughput)) return 'Not available'
  const milliseconds = numberAt(throughput as Evidence, 'denominator')
  return milliseconds === undefined ? 'Not available' : formatDuration(milliseconds)
})

const policyRows = computed(() =>
  checks.value.map((check) => {
    const metric = metrics.value.find((item) => item.id === check.metric_evidence_id)
    const checkScope = metric ? scope(metric) : scope(check)
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
  return value === undefined ? 'Not available' : `${value.toLocaleString()} ms`
}

function formatRatio(value: unknown) {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) return 'Not available'
  const numerator = numberAt(value as Evidence, 'numerator')
  const denominator = numberAt(value as Evidence, 'denominator')
  return numerator === undefined || denominator === undefined || denominator === 0
    ? 'Not available'
    : `${((numerator / denominator) * 100).toFixed(2)}%`
}

function formatThroughput(value: unknown) {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) return 'Not available'
  const numerator = numberAt(value as Evidence, 'numerator')
  const denominator = numberAt(value as Evidence, 'denominator')
  return numerator === undefined || denominator === undefined || denominator === 0
    ? 'Not available'
    : `${(numerator / denominator).toFixed(2)} RPS`
}

function formatValue(value: unknown) {
  if (typeof value === 'number' || typeof value === 'string') return String(value)
  return 'Not available'
}

function formatOptional(value: unknown) {
  return value === null || value === undefined ? 'Not available (null)' : String(value)
}

function statistic(item: Evidence, key: string) {
  const values = valueAt(item, 'statistics')
  return values !== null && typeof values === 'object' && !Array.isArray(values)
    ? formatOptional(valueAt(values as Evidence, key))
    : 'Not available (null)'
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
    id="verdict"
    class="panel verdict-strip"
    :data-verdict="verdict"
  >
    <p
      class="status-icon"
      aria-hidden="true"
    >
      {{ verdict === 'PASS' ? '✓' : verdict === 'FAIL' ? '×' : '!' }}
    </p>
    <div>
      <p class="eyebrow">
        Policy verdict
      </p>
      <h2>{{ verdictText }}</h2>
      <p
        v-if="result.analysis_coverage.reasons.length"
        class="muted"
      >
        {{ result.analysis_coverage.reasons.join(', ') }}
      </p>
    </div>
    <dl class="verdict-facts">
      <div><dt>Run validity</dt><dd>{{ result.run_validity }}</dd></div>
      <div><dt>Coverage</dt><dd>{{ result.analysis_coverage.status }}</dd></div>
      <div><dt>Duration</dt><dd>{{ duration }}</dd></div>
      <div><dt>Samples</dt><dd>{{ overallMetrics.samples.toLocaleString() }}</dd></div>
      <div><dt>Error rate</dt><dd>{{ overallMetrics.errorRate }}</dd></div>
      <div><dt>Rules</dt><dd>{{ allSlaChecks.length }}</dd></div>
    </dl>
  </section>

  <section
    id="summary-metrics"
    aria-labelledby="summary-metrics-title"
  >
    <div class="section-heading">
      <p class="eyebrow">
        Overall
      </p><h2 id="summary-metrics-title">
        Summary metrics
      </h2>
    </div>
    <div class="metric-grid">
      <article class="metric-card">
        <p>P50</p><strong>{{ overallMetrics.p50 }}</strong><small>Latency percentile</small>
      </article>
      <article class="metric-card">
        <p>P95</p><strong>{{ overallMetrics.p95 }}</strong><small>Latency percentile</small>
      </article>
      <article class="metric-card">
        <p>P99</p><strong>{{ overallMetrics.p99 }}</strong><small>Latency percentile</small>
      </article>
      <article class="metric-card">
        <p>Throughput</p><strong>{{ formatThroughput(valueAt(overall, 'throughput_rps')) }}</strong><small>Run window</small>
      </article>
    </div>
  </section>

  <section
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
    <div class="table-wrap">
      <table>
        <thead><tr><th>Window</th><th>Transaction</th><th>Metric</th><th>Operator</th><th>Threshold</th><th>Measured</th><th>Scope</th><th>Status</th></tr></thead>
        <tbody>
          <tr
            v-for="row in policyRows"
            :key="row.id"
          >
            <td>{{ row.window }}</td><td>{{ row.transaction }}</td><td>{{ row.metric }}</td><td>{{ row.operator }}</td><td>{{ row.threshold }}</td><td>{{ row.observed }}</td><td>{{ row.scope }}</td><td>
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
    v-if="resourceSummaries.length || windowPolicySummaries.length || resourceChecks.length || resourceBindings.length"
    id="resource-results"
    class="panel"
    aria-labelledby="resource-results-title"
  >
    <div class="section-heading">
      <p class="eyebrow">
        Resource snapshot
      </p><h2 id="resource-results-title">
        Resource statistics and SLA
      </h2>
    </div>
    <details
      v-for="item in resourceBindings"
      :key="String(item.id)"
    >
      <summary>Resource binding</summary>
      <pre>{{ bindingText(item) }}</pre>
    </details>
    <div
      v-if="windowPolicySummaries.length"
      class="table-wrap"
    >
      <table>
        <thead><tr><th>Window</th><th>From epoch (ms)</th><th>To epoch (ms)</th><th>Business verdict</th><th>Resource verdict</th><th>Verdict</th></tr></thead>
        <tbody>
          <tr
            v-for="item in windowPolicySummaries"
            :key="String(item.id)"
          >
            <td>{{ formatOptional(item.window_id) }}</td><td>{{ formatOptional(item.from_epoch_ms) }}</td><td>{{ formatOptional(item.to_epoch_ms) }}</td><td>
              <span
                class="status-text"
                :data-status="formatOptional(item.business_verdict)"
              >{{ formatOptional(item.business_verdict) }}</span>
            </td><td>
              <span
                class="status-text"
                :data-status="formatOptional(item.resource_verdict)"
              >{{ formatOptional(item.resource_verdict) }}</span>
            </td><td>
              <span
                class="status-text"
                :data-status="formatOptional(item.verdict)"
              >{{ formatOptional(item.verdict) }}</span>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
    <div
      v-if="resourceSummaries.length"
      class="table-wrap"
    >
      <table>
        <thead><tr><th>Series</th><th>Metric</th><th>Unit</th><th>Entity</th><th>Role</th><th>Aggregation</th><th>Window</th><th>Coverage (observed / expected)</th><th>Missing cells</th><th>Longest gap</th><th>Min</th><th>Max</th><th>Mean</th><th>Median</th><th>Q05</th><th>Q25</th><th>Q75</th><th>Q95</th><th>IQR</th><th>MAD</th><th>Sample standard deviation</th><th>Slope per second</th><th>Split-half shift</th><th>Reasons</th></tr></thead>
        <tbody>
          <tr
            v-for="item in resourceSummaries"
            :key="String(item.id)"
          >
            <td>{{ formatOptional(item.series_id) }}</td><td>{{ formatOptional(item.metric) }}</td><td>{{ formatOptional(item.unit) }}</td><td>{{ formatOptional(item.entity) }}</td><td>{{ formatOptional(item.role) }}</td><td>{{ formatOptional(item.aggregation) }}</td><td>{{ formatOptional(item.window_id) }}</td><td>{{ formatOptional(item.observed_cells) }} / {{ formatOptional(item.expected_cells) }}</td><td>{{ formatOptional(item.missing_cells) }}</td><td>{{ formatOptional(item.longest_gap_cells) }}</td><td>{{ statistic(item, 'min') }}</td><td>{{ statistic(item, 'max') }}</td><td>{{ statistic(item, 'mean') }}</td><td>{{ statistic(item, 'median') }}</td><td>{{ statistic(item, 'q05') }}</td><td>{{ statistic(item, 'q25') }}</td><td>{{ statistic(item, 'q75') }}</td><td>{{ statistic(item, 'q95') }}</td><td>{{ statistic(item, 'iqr') }}</td><td>{{ statistic(item, 'mad') }}</td><td>{{ statistic(item, 'sample_standard_deviation') }}</td><td>{{ statistic(item, 'slope_per_second') }}</td><td>{{ statistic(item, 'split_half_shift') }}</td><td>{{ arrayAt(item, 'reasons').join(', ') || '—' }}</td>
          </tr>
        </tbody>
      </table>
    </div>
    <div
      v-if="resourceChecks.length"
      class="table-wrap"
    >
      <table>
        <thead><tr><th>Window</th><th>Rule</th><th>Series</th><th>Operator</th><th>Threshold</th><th>Effect</th><th>Status</th><th>Reason</th></tr></thead>
        <tbody>
          <tr
            v-for="item in resourceChecks"
            :key="String(item.id)"
          >
            <td>{{ formatOptional(item.window_id) }}</td><td>{{ formatOptional(item.rule_id) }}</td><td>{{ formatOptional(item.series_id) }}</td><td>{{ formatOptional(item.operator) }}</td><td>{{ formatOptional(item.threshold) }} {{ formatOptional(item.unit) }}</td><td>{{ formatOptional(item.effect) }}</td><td>
              <span
                class="status-text"
                :data-status="formatOptional(item.status)"
              >{{ formatOptional(item.status) }}</span>
            </td><td>{{ formatOptional(item.reason) }}</td>
          </tr>
        </tbody>
      </table>
    </div>
  </section>

  <section
    v-if="diagnosticSummaries.length || correlationPairs.length || anomalyChecks.length || anomalyEpisodes.length"
    id="diagnostic-results"
    class="panel"
    aria-labelledby="diagnostic-results-title"
  >
    <h2 id="diagnostic-results-title">
      Run diagnostics
    </h2>
    <p>Observed associations and reference-based episodes. They do not establish causality or change SLA verdicts. No finding does not establish healthy operation.</p>
    <div
      v-for="item in diagnosticSummaries"
      :key="item.id"
    >
      <p>{{ item.status }} · Uncertainty: {{ item.uncertainty }}</p>
      <p>{{ item.pairs_evaluable }} / {{ item.pairs_tested }} pairs evaluable; {{ item.anomalies_tested }} anomaly checks; {{ item.episodes_reported }} episodes; {{ item.suppressed_short_episodes }} short episodes suppressed.</p>
      <p v-if="item.reasons.length">
        {{ item.reasons.join(', ') }}
      </p>
    </div>
    <div
      v-if="correlationPairs.length"
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
            <td>{{ item.raw_rho ?? 'N/A' }}</td>
            <td>{{ item.partial_rho ?? 'N/A' }}</td>
            <td>{{ item.best_lag_ms ?? 'N/A' }} / {{ item.best_lag_rho ?? 'N/A' }}</td>
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
      aria-label="Reference anomaly checks"
    >
      <table>
        <thead>
          <tr>
            <th scope="col">
              Rule / window / reference
            </th><th scope="col">
              Reference median / MAD
            </th><th scope="col">
              Reference cells
            </th><th scope="col">
              Evaluation cells
            </th><th scope="col">
              Episodes / suppressed
            </th><th scope="col">
              Status / reasons
            </th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="item in anomalyChecks"
            :key="item.id"
          >
            <td>{{ item.rule_id }} / {{ item.window_id }} / {{ item.reference_window_id }}</td>
            <td>{{ item.reference_median ?? 'N/A' }} / {{ item.reference_mad ?? 'N/A' }}</td>
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
      aria-label="Observed anomaly episodes"
      data-testid="anomaly-episodes"
    >
      <table>
        <thead>
          <tr>
            <th scope="col">
              Rule / window / reference
            </th><th scope="col">
              Metric / entity
            </th><th scope="col">
              UTC interval (epoch ms)
            </th><th scope="col">
              Duration / direction
            </th><th scope="col">
              Observed range / unit
            </th><th scope="col">
              Maximum absolute delta
            </th><th scope="col">
              Reasons
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
            <td>{{ formatDuration(Number(item.duration_ms)) }} / {{ item.direction }}</td>
            <td>{{ formatOptional(item.observed_min) }}–{{ formatOptional(item.observed_max) }} {{ item.unit }}</td>
            <td>{{ formatOptional(item.max_abs_delta) }}</td>
            <td>{{ arrayAt(item, 'reasons').join(', ') || '—' }}</td>
          </tr>
        </tbody>
      </table>
    </div>
    <details>
      <summary>Raw diagnostic evidence</summary>
      <pre>{{ diagnosticDetails }}</pre>
    </details>
  </section>

  <section
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
    <div class="table-wrap">
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
    id="normalized-data"
    class="panel"
    aria-labelledby="normalized-data-title"
  >
    <div class="section-heading">
      <p class="eyebrow">
        Inspectable source facts
      </p><h2 id="normalized-data-title">
        Normalized data
      </h2>
    </div>
    <div class="bucket-controls">
      <label>Rollup <select
        :value="rollup"
        aria-label="Bucket rollup"
        @change="updateRollup"
      ><option
        v-for="seconds in [1, 10, 30, 60]"
        :key="seconds"
        :value="seconds"
      >{{ seconds }} second{{ seconds === 1 ? '' : 's' }}</option></select></label>
      <label>Start offset (ms) <input
        :value="rangeStart"
        type="number"
        min="0"
        @input="updateRange('update:range-start', $event)"
      ></label>
      <label>End offset (ms) <input
        :value="rangeEnd"
        type="number"
        min="0"
        @input="updateRange('update:range-end', $event)"
      ></label>
      <button
        type="button"
        @click="emit('refresh-buckets')"
      >
        Refresh data
      </button>
    </div>
    <LoadCharts
      :buckets="buckets"
      :rollup="bucketRollup"
    />
    <div class="table-wrap">
      <table>
        <thead><tr><th>Time bin</th><th>RPS</th><th>Errors</th><th>P95</th><th>Max latency</th><th>Data status</th></tr></thead>
        <tbody>
          <tr
            v-for="row in bucketRows"
            :key="row.id"
            :data-status="row.status === 'Available' ? 'available' : 'missing'"
          >
            <td>{{ row.time }}</td><td>{{ row.rps }}</td><td>{{ row.errors }}</td><td>{{ row.p95 }}</td><td>{{ row.max }}</td><td>{{ row.status }}</td>
          </tr>
        </tbody>
      </table>
    </div>
  </section>
</template>
