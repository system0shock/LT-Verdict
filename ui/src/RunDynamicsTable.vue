<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { AnalysisReference } from './types'
import type { DynamicsRow, RunDynamics } from './analyticsTypes'

const props = defineProps<{ dynamics: RunDynamics }>()
const emit = defineEmits<{ selection: [references: AnalysisReference[]] }>()
const excluded = ref<string[]>([])
const metricLabels: Record<string, string> = {
  response_time_p95_ms: 'P95',
  response_time_p99_ms: 'P99',
  throughput_rps: 'Throughput',
  error_rate_ratio: 'Error rate',
}
const metrics = computed(() => props.dynamics.rows[0]?.metrics.map((metric) => ({ metric: metric.metric, unit: metric.unit })) ?? [])
const selectedRows = computed(() => props.dynamics.rows.filter((row) => included(row.reference)))

watch(
  () => props.dynamics.rows.map((row) => key(row.reference)).join('|'),
  () => {
    excluded.value = []
    emit('selection', props.dynamics.rows.map((row) => row.reference))
  },
  { immediate: true },
)

function key(reference: AnalysisReference) {
  return `${reference.run_id}/${reference.analysis_id}`
}

function included(reference: AnalysisReference) {
  return !excluded.value.includes(key(reference))
}

function toggle(row: DynamicsRow) {
  const rowKey = key(row.reference)
  excluded.value = included(row.reference) ? [...excluded.value, rowKey] : excluded.value.filter((value) => value !== rowKey)
  emit('selection', props.dynamics.rows.filter((candidate) => included(candidate.reference)).map((candidate) => candidate.reference))
}

function metric(row: DynamicsRow, name: string) {
  return row.metrics.find((value) => value.metric === name)
}

function value(value: string | null | undefined, reason?: string | null) {
  return value === null || value === undefined ? `N/A${reason ? ` (${reason})` : ''}` : value
}

function delta(value: string | null | undefined, percent: string | null | undefined, reason: string | null | undefined) {
  if (value === null || value === undefined) return `N/A${reason ? ` (${reason})` : ''}`
  return percent === null || percent === undefined ? value : `${value} (${percent}%)`
}

function deltaClass(metricName: string, deltaValue: string | null | undefined) {
  if (deltaValue === null || deltaValue === undefined) return 'delta--unknown'
  const parsed = Number(deltaValue)
  if (!Number.isFinite(parsed) || parsed === 0) return 'delta--neutral'
  const increaseIsBetter = metricName === 'throughput_rps'
  return (parsed > 0) === increaseIsBetter ? 'delta--improved' : 'delta--regressed'
}
</script>

<template>
  <section aria-labelledby="run-dynamics-title">
    <h3 id="run-dynamics-title">
      N-run dynamics
    </h3>
    <p>
      Showing {{ selectedRows.length }} selected analyses from {{ dynamics.rows.length }} loaded rows and {{ dynamics.comparable_count }} local comparable analyses.
      {{ dynamics.excluded_incompatible_count }} incompatible analyses excluded; no source queries were made.
    </p>
    <details>
      <summary>Select runs shown and exported</summary>
      <p class="field__hint">
        Hiding a row does not recalculate deltas; they still refer to the original preceding comparable run.
      </p>
      <label
        v-for="row in dynamics.rows"
        :key="`choice-${key(row.reference)}`"
        class="run-choice"
      >
        <input
          type="checkbox"
          :checked="included(row.reference)"
          @change="toggle(row)"
        >
        {{ row.run_date }} · {{ row.reference.run_id.slice(0, 20) }}
      </label>
    </details>
    <div
      class="table-wrap"
      tabindex="0"
      role="region"
      aria-label="Run dynamics table"
    >
      <table>
        <thead>
          <tr>
            <th scope="col">
              Run
            </th><th scope="col">
              Date
            </th><th scope="col">
              Build / version
            </th><th scope="col">
              Profile
            </th><th scope="col">
              Verdict
            </th>
            <template
              v-for="item in metrics"
              :key="item.metric"
            >
              <th scope="col">
                {{ metricLabels[item.metric] ?? item.metric }} / {{ item.unit }}
              </th>
              <th scope="col">
                Δ previous
              </th>
              <th scope="col">
                Δ baseline
              </th>
            </template>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="row in selectedRows"
            :key="key(row.reference)"
          >
            <td
              class="mono"
              :title="key(row.reference)"
            >
              {{ row.reference.run_id.slice(0, 20) }}
            </td>
            <td>{{ row.run_date }}</td>
            <td>{{ row.jenkins_build ?? 'N/A' }} / {{ row.application_version ?? row.commit ?? 'N/A' }}</td>
            <td>{{ row.load_profile ?? 'N/A' }}</td>
            <td>{{ row.verdict }}</td>
            <template
              v-for="item in metrics"
              :key="`${key(row.reference)}-${item.metric}`"
            >
              <td>{{ value(metric(row, item.metric)?.value, metric(row, item.metric)?.value === null ? 'METRIC_NOT_AVAILABLE' : null) }}</td>
              <td :class="deltaClass(item.metric, metric(row, item.metric)?.delta_previous)">
                {{ delta(metric(row, item.metric)?.delta_previous, metric(row, item.metric)?.delta_previous_percent, metric(row, item.metric)?.previous_reason) }}
              </td>
              <td :class="deltaClass(item.metric, metric(row, item.metric)?.delta_baseline)">
                {{ delta(metric(row, item.metric)?.delta_baseline, metric(row, item.metric)?.delta_baseline_percent, metric(row, item.metric)?.baseline_reason) }}
              </td>
            </template>
          </tr>
          <tr v-if="!selectedRows.length">
            <td :colspan="6 + metrics.length * 3">
              N/A (NO_RUNS_SELECTED)
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </section>
</template>

<style scoped>
.run-choice {
  display: block;
  margin-block: 0.35rem;
}

.delta--improved {
  color: var(--pass);
  background: var(--pass-bg);
}

.delta--regressed {
  color: var(--fail);
  background: var(--fail-bg);
}

.delta--unknown {
  font-style: italic;
}
</style>
