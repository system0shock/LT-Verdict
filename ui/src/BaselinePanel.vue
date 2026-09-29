<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import BaselineCharts from './BaselineCharts.vue'
import { clearBaseline, compareBaseline, getBaseline, getBaselineConditions, setBaseline, setBaselineConditions } from './api'
import type { AnalysisReference, BaselineComparison, BaselineCondition, BaselineConditionDecision, BaselineConditionWindows, BaselineRequest, BaselineSelection } from './types'

const props = defineProps<{ selection: AnalysisReference | null; filename: string; working: boolean }>()
const baseline = ref<BaselineSelection | null>(null)
const comparison = ref<BaselineComparison | null>(null)
const series = ref('Selected test series')
const candidates = ref<Array<{ reference: AnalysisReference; filename: string }>>([])
const comparable = ref(false)
const conditions = ref<BaselineCondition | null>(null)
const conditionDecision = ref<BaselineConditionDecision>('UNKNOWN')
const conditionLoading = ref(false)
const conditionSaving = ref(false)
const loading = ref(true)
const saving = ref(false)
const comparing = ref(false)
const baselineWindow = ref('')
const currentWindow = ref('')
const minChangePercent = ref('5')
const minErrorRateDelta = ref('0.001')
const error = ref('')
let baselineRevision = 0
let comparisonRevision = 0
let conditionRevision = 0

const busy = computed(() => loading.value || saving.value || props.working)
const canAdd = computed(() => props.selection && candidates.value.length < 20
  && !candidates.value.some((candidate) => candidate.reference.run_id === props.selection?.run_id))
const validSeries = computed(() => series.value.trim().length > 0 && new TextEncoder().encode(series.value).length <= 128)
const windowSelection = computed(() => baselineWindow.value.trim() !== '' || currentWindow.value.trim() !== '')
const validWindows = computed(() => !windowSelection.value || (
  baselineWindow.value.trim() !== '' && currentWindow.value.trim() !== ''
  && Number(minChangePercent.value) > 0 && Number(minChangePercent.value) <= 1000
  && Number(minErrorRateDelta.value) > 0 && Number(minErrorRateDelta.value) <= 1
))
const conditionBusy = computed(() => conditionLoading.value || conditionSaving.value)
const metricLabels: Record<string, string> = {
  response_time_p95_ms: 'P95 latency',
  response_time_p99_ms: 'P99 latency',
  throughput_rps: 'Throughput',
  error_rate_ratio: 'Error rate',
}

onMounted(loadBaseline)
watch(() => props.selection, conditionBindingChanged)
watch([baselineWindow, currentWindow, minChangePercent, minErrorRateDelta], invalidateComparison)
watch(series, () => { comparable.value = false })

function invalidateComparison() {
  comparisonRevision += 1
  comparison.value = null
  comparing.value = false
}

function conditionBindingChanged() {
  invalidateComparison()
  void loadConditions()
}

function selectedConditionWindows(): BaselineConditionWindows | undefined {
  return windowSelection.value ? {
    baseline_window: baselineWindow.value.trim(),
    current_window: currentWindow.value.trim(),
  } : undefined
}

async function loadConditions() {
  const revision = ++conditionRevision
  conditions.value = null
  conditionDecision.value = 'UNKNOWN'
  conditionLoading.value = false
  if (baseline.value?.mode !== 'manual' || !props.selection || !validWindows.value) return
  conditionLoading.value = true
  error.value = ''
  try {
    const response = await getBaselineConditions({ ...props.selection }, selectedConditionWindows())
    if (revision !== conditionRevision) return
    conditions.value = response.conditions
    conditionDecision.value = response.conditions?.decision ?? 'UNKNOWN'
  } catch (failure) {
    if (revision === conditionRevision) showError(failure)
  } finally {
    if (revision === conditionRevision) conditionLoading.value = false
  }
}

async function loadBaseline() {
  const revision = ++baselineRevision
  loading.value = true
  error.value = ''
  try {
    const response = await getBaseline()
    if (revision !== baselineRevision) return
    baseline.value = response.baseline
    if (response.baseline) series.value = response.baseline.series
    void loadConditions()
  } catch (failure) {
    if (revision === baselineRevision) showError(failure)
  } finally {
    if (revision === baselineRevision) loading.value = false
  }
}

function addCandidate() {
  if (!canAdd.value || !props.selection) return
  candidates.value.push({ reference: { ...props.selection }, filename: props.filename })
  comparable.value = false
}

function removeCandidate(runId: string) {
  candidates.value = candidates.value.filter((candidate) => candidate.reference.run_id !== runId)
  comparable.value = false
}

function assignManual() {
  if (!props.selection || !validSeries.value || busy.value) return
  void save({ mode: 'manual', series: series.value.trim(), reference: { ...props.selection } })
}

function assignStatistical() {
  if (!comparable.value || candidates.value.length < 3 || !validSeries.value || busy.value) return
  void save({ mode: 'statistical', series: series.value.trim(), candidates: candidates.value.map((candidate) => ({ ...candidate.reference })), comparable: true })
}

async function save(request: BaselineRequest | null) {
  const revision = ++baselineRevision
  saving.value = true
  error.value = ''
  invalidateComparison()
  try {
    const response = request ? await setBaseline(request) : await clearBaseline()
    if (revision !== baselineRevision) return
    baseline.value = response.baseline
    conditionRevision += 1
    conditions.value = null
    conditionDecision.value = 'UNKNOWN'
    if (response.baseline) void loadConditions()
  } catch (failure) {
    if (revision === baselineRevision) showError(failure)
  } finally {
    if (revision === baselineRevision) saving.value = false
  }
}

async function saveConditions() {
  if (baseline.value?.mode !== 'manual' || !props.selection || busy.value || conditionBusy.value || !validWindows.value) return
  const revision = ++conditionRevision
  const stateRevision = baselineRevision
  conditionSaving.value = true
  error.value = ''
  invalidateComparison()
  try {
    const response = await setBaselineConditions({ ...props.selection }, conditionDecision.value, selectedConditionWindows())
    if (revision !== conditionRevision || stateRevision !== baselineRevision) return
    conditions.value = response.conditions
    conditionDecision.value = response.conditions.decision
  } catch (failure) {
    if (revision === conditionRevision && stateRevision === baselineRevision) showError(failure)
  } finally {
    if (revision === conditionRevision) conditionSaving.value = false
  }
}

async function compare() {
  if (!props.selection || !baseline.value || busy.value || !validWindows.value) return
  const revision = ++comparisonRevision
  const stateRevision = baselineRevision
  comparison.value = null
  comparing.value = true
  error.value = ''
  try {
    const response = await compareBaseline({ ...props.selection }, windowSelection.value ? {
      baseline_window: baselineWindow.value.trim(),
      current_window: currentWindow.value.trim(),
      min_change_percent: minChangePercent.value,
      min_error_rate_delta: minErrorRateDelta.value,
    } : undefined)
    if (revision !== comparisonRevision || stateRevision !== baselineRevision) return
    comparison.value = response
    baseline.value = response.baseline
    conditions.value = response.conditions
    conditionDecision.value = response.conditions?.decision ?? 'UNKNOWN'
  } catch (failure) {
    if (revision === comparisonRevision && stateRevision === baselineRevision) showError(failure)
  } finally {
    if (revision === comparisonRevision) comparing.value = false
  }
}

function showError(failure: unknown) {
  error.value = failure instanceof Error ? failure.message : 'Baseline request failed.'
}
</script>

<template>
  <section
    id="baseline-panel"
    class="panel baseline-panel"
    aria-labelledby="baseline-title"
  >
    <header class="panel__header">
      <h2 id="baseline-title">
        Baseline comparison
      </h2>
      <p>A fixed saved analysis, selected manually or statistically. New runs do not replace it.</p>
    </header>

    <div class="field">
      <label for="baseline-series">Comparison series</label>
      <input
        id="baseline-series"
        v-model="series"
        :disabled="busy"
        aria-describedby="baseline-series-hint"
      >
      <p
        id="baseline-series-hint"
        class="field__hint"
      >
        Name the scenario and test conditions (up to 128 UTF-8 bytes). A name alone does not prove comparability.
      </p>
    </div>

    <p
      v-if="loading"
      role="status"
    >
      Loading baseline…
    </p>
    <div
      v-else-if="baseline"
      data-testid="baseline-selection"
      class="baseline-selection"
      role="status"
    >
      <p><strong>{{ baseline.series }}</strong> · {{ baseline.mode }}</p>
      <p>Run <span class="mono">{{ baseline.reference.run_id }}</span></p>
      <p>Analysis <span class="mono">{{ baseline.reference.analysis_id }}</span></p>
      <template v-if="baseline.algorithm">
        <p>{{ baseline.algorithm }} · {{ baseline.candidates.length }} candidates</p>
        <details>
          <summary>Selection scores</summary>
          <ul>
            <li
              v-for="score in baseline.scores"
              :key="score.reference.run_id"
              :title="`${score.reference.run_id} / ${score.reference.analysis_id}`"
            >
              {{ score.reference.analysis_id.slice(0, 12) }}: {{ score.score }} (lower is more central)
            </li>
          </ul>
        </details>
      </template>
    </div>
    <p v-else>
      No baseline selected. Open a saved analysis to assign one.
    </p>

    <div class="form-grid">
      <div class="field">
        <label for="baseline-window">Baseline window ID</label>
        <input
          id="baseline-window"
          v-model="baselineWindow"
          :disabled="busy"
          aria-describedby="window-comparison-hint"
          @change="conditionBindingChanged"
        >
      </div>
      <div class="field">
        <label for="current-window">Current window ID</label>
        <input
          id="current-window"
          v-model="currentWindow"
          :disabled="busy"
          aria-describedby="window-comparison-hint"
          @change="conditionBindingChanged"
        >
      </div>
      <div class="field">
        <label for="minimum-change">Minimum change (%)</label>
        <input
          id="minimum-change"
          v-model="minChangePercent"
          type="number"
          min="0"
          max="1000"
          step="any"
          :disabled="busy || !windowSelection"
        >
      </div>
      <div class="field">
        <label for="minimum-error-delta">Minimum error-rate delta (ratio)</label>
        <input
          id="minimum-error-delta"
          v-model="minErrorRateDelta"
          type="number"
          min="0"
          max="1"
          step="any"
          :disabled="busy || !windowSelection"
        >
      </div>
    </div>
    <p
      id="window-comparison-hint"
      class="field__hint"
    >
      Optional: enter both window IDs to compare their saved metrics. Leave both empty for overall metrics.
      Matching names do not establish the same planned load, request mix or test conditions.
      Materiality thresholds must be greater than zero; an error-rate delta of 0.001 is 0.1 percentage points.
    </p>

    <template v-if="baseline?.mode === 'manual'">
      <fieldset
        class="field"
        :disabled="busy || conditionBusy || !selection || !validWindows"
        aria-describedby="manual-conditions-hint"
      >
        <legend>Planned conditions for this exact pair</legend>
        <label>
          <input
            v-model="conditionDecision"
            type="radio"
            value="CONFIRMED"
          >
          Confirmed same planned test conditions
        </label>
        <label>
          <input
            v-model="conditionDecision"
            type="radio"
            value="NOT_CONFIRMED"
          >
          Not confirmed
        </label>
        <label>
          <input
            v-model="conditionDecision"
            type="radio"
            value="UNKNOWN"
          >
          Unknown
        </label>
      </fieldset>
      <p
        id="manual-conditions-hint"
        class="field__hint"
      >
        This decision is saved only for the displayed baseline/current analyses and, when entered, both window IDs.
        It changes interpretation, not metric deltas, SLA or the policy verdict.
      </p>
      <p
        v-if="conditionLoading"
        role="status"
      >
        Loading saved condition decision…
      </p>
      <p
        v-else
        data-testid="baseline-condition-status"
        role="status"
      >
        {{ conditions ? `Saved ${conditions.decision} at ${conditions.updated_at}` : 'No saved decision for this exact pair.' }}
      </p>
      <button
        type="button"
        :disabled="busy || conditionBusy || !selection || !validWindows"
        @click="saveConditions"
      >
        {{ conditionSaving ? 'Saving condition decision…' : 'Save condition decision' }}
      </button>
    </template>

    <div class="policy-editor__actions">
      <button
        type="button"
        :disabled="busy || !selection || !validSeries"
        @click="assignManual"
      >
        Set as baseline
      </button>
      <button
        type="button"
        :disabled="busy || conditionSaving || !baseline || !selection || comparing || !validWindows"
        @click="compare"
      >
        {{ comparing ? 'Comparing…' : 'Compare selected analysis' }}
      </button>
      <button
        v-if="baseline"
        type="button"
        :disabled="busy"
        @click="save(null)"
      >
        Clear baseline
      </button>
    </div>

    <details class="baseline-statistics">
      <summary>Statistical selection</summary>
      <p>
        Select 3–20 different runs from the same planned test conditions.
        The central real run is chosen using P95, throughput and error-rate ranks.
        This heuristic does not establish a stable norm or statistical significance.
      </p>
      <button
        type="button"
        :disabled="busy || !canAdd"
        @click="addCandidate"
      >
        Add selected candidate
      </button>
      <ul data-testid="baseline-candidates">
        <li
          v-for="candidate in candidates"
          :key="candidate.reference.run_id"
        >
          <span :title="`${candidate.reference.run_id} / ${candidate.reference.analysis_id}`">
            {{ candidate.filename }} · {{ candidate.reference.analysis_id.slice(0, 12) }}
          </span>
          <button
            type="button"
            :disabled="busy"
            :aria-label="`Remove candidate ${candidate.filename}`"
            @click="removeCandidate(candidate.reference.run_id)"
          >
            Remove
          </button>
        </li>
      </ul>
      <label class="baseline-confirmation">
        <input
          v-model="comparable"
          type="checkbox"
          :disabled="busy"
          aria-describedby="baseline-conditions-hint"
        >
        Same planned test conditions
      </label>
      <p
        id="baseline-conditions-hint"
        class="field__hint"
      >
        Confirm scenario/mix, environment/dataset, load model, targets, pacing and generator limits.
        Achieved RPS may differ. Invalid or incomplete candidates are rejected, not silently omitted.
      </p>
      <button
        type="button"
        :disabled="busy || !validSeries || !comparable || candidates.length < 3"
        @click="assignStatistical"
      >
        Select statistically
      </button>
    </details>

    <p
      v-if="error"
      class="notice notice-fail"
      role="alert"
    >
      {{ error }}
    </p>

    <section
      v-if="comparison"
      data-testid="baseline-comparison"
      aria-labelledby="baseline-metrics-title"
    >
      <h3 id="baseline-metrics-title">
        Overall metrics against baseline
      </h3>
      <p>Planned conditions: {{ comparison.comparability }}. Deltas alone do not prove a version regression or change the policy verdict.</p>
      <BaselineCharts :comparison="comparison" />
      <div
        class="table-wrap"
        tabindex="0"
        role="region"
        aria-label="Baseline metric deltas"
      >
        <table>
          <thead>
            <tr>
              <th scope="col">
                Metric / unit
              </th>
              <th scope="col">
                Baseline
              </th>
              <th scope="col">
                Current
              </th>
              <th scope="col">
                Absolute delta
              </th>
              <th scope="col">
                Relative delta
              </th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="metric in comparison.metrics"
              :key="metric.metric"
              :data-testid="`comparison-${metric.metric}`"
            >
              <td>{{ metricLabels[metric.metric] ?? metric.metric }} / {{ metric.unit }}</td>
              <td>{{ metric.baseline ?? 'N/A' }}</td>
              <td>{{ metric.current ?? 'N/A' }}</td>
              <td>{{ metric.delta ?? `N/A (${metric.reason})` }}</td>
              <td>{{ metric.delta_percent === null ? `N/A (${metric.percent_reason})` : `${metric.delta_percent}%` }}</td>
            </tr>
          </tbody>
        </table>
      </div>
      <p class="field__hint">
        Display rounded to 6 decimal places. Error rate uses ratio units: 0.01 = 1%.
      </p>
      <section
        v-if="comparison.window_comparison"
        data-testid="window-comparison"
        aria-labelledby="window-comparison-title"
      >
        <h3 id="window-comparison-title">
          Selected-window observations
        </h3>
        <p>{{ comparison.window_comparison.baseline_window }} → {{ comparison.window_comparison.current_window }} · {{ comparison.window_comparison.status }}</p>
        <p>{{ comparison.window_comparison.reasons.join(', ') || '—' }}</p>
        <p>
          Baseline: {{ comparison.window_comparison.baseline_sample_count ?? 'N/A' }} samples / {{ comparison.window_comparison.baseline_duration_ms ?? 'N/A' }} ms.
          Current: {{ comparison.window_comparison.current_sample_count ?? 'N/A' }} samples / {{ comparison.window_comparison.current_duration_ms ?? 'N/A' }} ms.
        </p>
        <p>Uncertainty: NOT_ESTIMATED. These two observations do not establish a reproducible version regression.</p>
        <div
          class="table-wrap"
          tabindex="0"
          role="region"
          aria-label="Selected-window metric deltas"
        >
          <table>
            <thead>
              <tr>
                <th scope="col">
                  Metric / entity / unit
                </th><th scope="col">
                  Baseline
                </th><th scope="col">
                  Current
                </th><th scope="col">
                  Absolute delta
                </th><th scope="col">
                  Relative delta
                </th><th scope="col">
                  Status / reason
                </th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="(metric, index) in comparison.window_comparison.metrics"
                :key="`${metric.metric}-${metric.entity}-${metric.resource_series_id}-${index}`"
              >
                <td>{{ metricLabels[metric.metric] ?? metric.metric }} / {{ metric.entity ?? 'Overall' }} / {{ metric.resource_series_id ?? '—' }} / {{ metric.unit }}</td>
                <td>{{ metric.baseline ?? 'N/A' }}</td>
                <td>{{ metric.current ?? 'N/A' }}</td>
                <td>{{ metric.delta ?? `N/A (${metric.reason})` }}</td>
                <td>{{ metric.delta_percent === null ? `N/A (${metric.percent_reason})` : `${metric.delta_percent}%` }}</td>
                <td>{{ metric.status }} · {{ metric.reason ?? '—' }}</td>
              </tr>
            </tbody>
          </table>
        </div>
        <details>
          <summary>Raw window comparison evidence</summary>
          <pre>{{ JSON.stringify(comparison.window_comparison, null, 2) }}</pre>
        </details>
      </section>
    </section>
  </section>
</template>
