<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import BaselineCharts from './BaselineCharts.vue'
import { ApiError, clearBaseline, compareBaseline, getBaseline, getBaselineConditions, setBaseline, setBaselineConditions } from './api'
import { BASELINE_LABELS } from './shell/labels'
import { EN_COMPARE_LABELS, type CompareLabels } from './shell/labels.compare'
import type { AnalysisReference, BaselineComparison, BaselineCondition, BaselineConditionDecision, BaselineConditionWindows, BaselineRequest, BaselineSelection } from './types'

const props = withDefaults(defineProps<{ selection: AnalysisReference | null; filename: string; working: boolean; labels?: CompareLabels }>(), { labels: () => EN_COMPARE_LABELS })
const baseline = ref<BaselineSelection | null>(null)
const comparison = ref<BaselineComparison | null>(null)
const series = ref(props.labels.seriesDefault)
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
const errorCode = ref('')
let baselineRevision = 0
let comparisonRevision = 0
let conditionRevision = 0

const busy = computed(() => loading.value || saving.value || conditionSaving.value || props.working)
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
const emptyWindowNotes = computed(() => {
  const reasons = comparison.value?.window_comparison?.reasons ?? []
  return [
    ...(reasons.includes('BASELINE_WINDOW_EMPTY') ? [BASELINE_LABELS.emptyBaselineWindow] : []),
    ...(reasons.includes('CURRENT_WINDOW_EMPTY') ? [BASELINE_LABELS.emptyCurrentWindow] : []),
  ]
})
const oldRules = computed(() => errorCode.value === 'BASELINE_MIXED_SEMANTICS'
  || comparison.value?.metrics.some((metric) => metric.reason === 'INCOMPATIBLE_METRIC_DEFINITION')
  || comparison.value?.window_comparison?.reasons.includes('INCOMPATIBLE_METRIC_DEFINITION'))

onMounted(loadBaseline)
watch(() => props.selection, conditionBindingChanged)
watch([baselineWindow, currentWindow], conditionBindingChanged)
watch([minChangePercent, minErrorRateDelta], invalidateComparison)
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
  if (!baseline.value || !props.selection || !validWindows.value) return
  conditionLoading.value = true
  error.value = ''
  errorCode.value = ''
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
  errorCode.value = ''
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
  errorCode.value = ''
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
  if (!baseline.value || !props.selection || busy.value || conditionBusy.value || !validWindows.value) return
  const revision = ++conditionRevision
  const stateRevision = baselineRevision
  conditionSaving.value = true
  error.value = ''
  errorCode.value = ''
  invalidateComparison()
  try {
    const response = await setBaselineConditions({ ...props.selection }, conditionDecision.value, selectedConditionWindows())
    if (revision !== conditionRevision || stateRevision !== baselineRevision) return
    conditions.value = response.conditions
    conditionDecision.value = response.conditions.decision
  } catch (failure) {
    if (revision === conditionRevision && stateRevision === baselineRevision) showError(failure)
  } finally {
    conditionSaving.value = false
  }
}

async function compare() {
  if (!props.selection || !baseline.value || busy.value || !validWindows.value) return
  const revision = ++comparisonRevision
  const stateRevision = baselineRevision
  comparison.value = null
  comparing.value = true
  error.value = ''
  errorCode.value = ''
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
  error.value = failure instanceof Error ? failure.message : props.labels.requestFailed
  errorCode.value = failure instanceof ApiError ? failure.code : ''
}

function warningText(code: string): string {
  return (BASELINE_LABELS.warnings as Record<string, string>)[code] ?? code
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
        {{ labels.title }}
      </h2>
      <p>{{ labels.intro }}</p>
    </header>

    <div class="field">
      <label for="baseline-series">{{ labels.seriesLabel }}</label>
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
        {{ labels.seriesHint }}
      </p>
    </div>

    <p
      v-if="loading"
      role="status"
    >
      {{ labels.loading }}
    </p>
    <div
      v-else-if="baseline"
      data-testid="baseline-selection"
      class="baseline-selection"
      role="status"
    >
      <p><strong>{{ baseline.series }}</strong> · {{ labels.mode(baseline.mode) }}</p>
      <p>{{ labels.runWord }} <span class="mono">{{ baseline.reference.run_id }}</span></p>
      <p>{{ labels.analysisWord }} <span class="mono">{{ baseline.reference.analysis_id }}</span></p>
      <template v-if="baseline.algorithm">
        <p>{{ labels.candidatesLine(baseline.algorithm, baseline.candidates.length) }}</p>
        <details>
          <summary>{{ labels.scoresSummary }}</summary>
          <ul>
            <li
              v-for="score in baseline.scores"
              :key="score.reference.run_id"
              :title="`${score.reference.run_id} / ${score.reference.analysis_id}`"
            >
              {{ labels.scoreLine(score.reference.analysis_id.slice(0, 12), score.score) }}
            </li>
          </ul>
        </details>
      </template>
    </div>
    <p v-else>
      {{ labels.noBaseline }}
    </p>

    <div class="form-grid">
      <div class="field">
        <label for="baseline-window">{{ labels.baselineWindow }}</label>
        <input
          id="baseline-window"
          v-model="baselineWindow"
          :disabled="busy"
          aria-describedby="window-comparison-hint"
        >
      </div>
      <div class="field">
        <label for="current-window">{{ labels.currentWindow }}</label>
        <input
          id="current-window"
          v-model="currentWindow"
          :disabled="busy"
          aria-describedby="window-comparison-hint"
        >
      </div>
      <div class="field">
        <label for="minimum-change">{{ labels.minChange }}</label>
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
        <label for="minimum-error-delta">{{ labels.minErrorDelta }}</label>
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
      {{ labels.windowHint }}
    </p>

    <template v-if="baseline">
      <fieldset
        class="field"
        :disabled="busy || conditionBusy || !selection || !validWindows"
        aria-describedby="manual-conditions-hint"
      >
        <legend>{{ labels.conditionsLegend }}</legend>
        <label>
          <input
            v-model="conditionDecision"
            type="radio"
            value="CONFIRMED"
          >
          {{ labels.conditionConfirmed }}
        </label>
        <label>
          <input
            v-model="conditionDecision"
            type="radio"
            value="NOT_CONFIRMED"
          >
          {{ labels.conditionNotConfirmed }}
        </label>
        <label>
          <input
            v-model="conditionDecision"
            type="radio"
            value="UNKNOWN"
          >
          {{ labels.conditionUnknown }}
        </label>
      </fieldset>
      <p
        id="manual-conditions-hint"
        class="field__hint"
      >
        {{ labels.conditionsHint }}
      </p>
      <p
        v-if="conditionLoading"
        role="status"
      >
        {{ labels.conditionLoading }}
      </p>
      <p
        v-else
        data-testid="baseline-condition-status"
        role="status"
      >
        {{ conditions ? labels.conditionSaved(conditions.decision, conditions.updated_at) : labels.conditionNone }}
      </p>
      <button
        type="button"
        :disabled="busy || conditionBusy || !selection || !validWindows"
        @click="saveConditions"
      >
        {{ conditionSaving ? labels.savingCondition : labels.saveCondition }}
      </button>
    </template>

    <div class="policy-editor__actions">
      <button
        type="button"
        :disabled="busy || !selection || !validSeries"
        @click="assignManual"
      >
        {{ labels.setBaseline }}
      </button>
      <button
        type="button"
        :disabled="busy || conditionSaving || !baseline || !selection || comparing || !validWindows"
        @click="compare"
      >
        {{ comparing ? labels.comparing : labels.compare }}
      </button>
      <button
        v-if="baseline"
        type="button"
        :disabled="busy"
        @click="save(null)"
      >
        {{ labels.clearBaseline }}
      </button>
    </div>

    <details class="baseline-statistics">
      <summary>{{ labels.statSummary }}</summary>
      <p>
        {{ labels.statText }}
      </p>
      <button
        type="button"
        :disabled="busy || !canAdd"
        @click="addCandidate"
      >
        {{ labels.addCandidate }}
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
            :aria-label="labels.removeAria(candidate.filename)"
            @click="removeCandidate(candidate.reference.run_id)"
          >
            {{ labels.remove }}
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
        {{ labels.sameConditions }}
      </label>
      <p
        id="baseline-conditions-hint"
        class="field__hint"
      >
        {{ labels.candidatesHint }}
      </p>
      <button
        type="button"
        :disabled="busy || !validSeries || !comparable || candidates.length < 3"
        @click="assignStatistical"
      >
        {{ labels.selectStatistically }}
      </button>
    </details>

    <p
      v-if="error"
      class="notice notice-fail"
      role="alert"
      :lang="labels.foreignLang"
    >
      {{ error }}
    </p>
    <p
      v-if="oldRules"
      data-testid="baseline-old-rules"
      class="notice notice-info"
      role="status"
    >
      {{ BASELINE_LABELS.oldRulesHint }}
    </p>

    <section
      v-if="comparison"
      data-testid="baseline-comparison"
      aria-labelledby="baseline-metrics-title"
    >
      <h3 id="baseline-metrics-title">
        {{ labels.metricsTitle }}
      </h3>
      <p>{{ labels.statusLine(comparison.comparability) }}. {{ labels.deltasNote }}</p>
      <div
        v-if="comparison.warnings.length"
        data-testid="baseline-warnings"
        class="notice notice-warn"
        role="status"
      >
        <strong>{{ BASELINE_LABELS.warningsTitle }}</strong>
        <ul>
          <li
            v-for="warning in comparison.warnings"
            :key="warning"
          >
            {{ warningText(warning) }}
          </li>
        </ul>
      </div>
      <BaselineCharts
        :comparison="comparison"
        :labels="labels"
      />
      <div
        class="table-wrap"
        tabindex="0"
        role="region"
        :aria-label="labels.deltasRegion"
      >
        <table>
          <thead>
            <tr>
              <th scope="col">
                {{ labels.metricHead }}
              </th>
              <th scope="col">
                {{ labels.baselineHead }}
              </th>
              <th scope="col">
                {{ labels.currentHead }}
              </th>
              <th scope="col">
                {{ labels.absoluteHead }}
              </th>
              <th scope="col">
                {{ labels.relativeHead }}
              </th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="metric in comparison.metrics"
              :key="metric.metric"
              :data-testid="`comparison-${metric.metric}`"
            >
              <td>{{ labels.metric(metric.metric) }} / {{ labels.unit(metric.unit) }}</td>
              <td>{{ metric.baseline ?? labels.na }}</td>
              <td>{{ metric.current ?? labels.na }}</td>
              <td>{{ metric.delta ?? labels.naReason(metric.reason) }}</td>
              <td>{{ metric.delta_percent === null ? labels.naReason(metric.percent_reason) : `${metric.delta_percent}%` }}</td>
            </tr>
          </tbody>
        </table>
      </div>
      <p class="field__hint">
        {{ labels.roundingNote }}
      </p>
      <section
        v-if="comparison.window_comparison"
        data-testid="window-comparison"
        aria-labelledby="window-comparison-title"
      >
        <h3 id="window-comparison-title">
          {{ labels.windowTitle }}
        </h3>
        <p>{{ comparison.window_comparison.baseline_window }} → {{ comparison.window_comparison.current_window }} · {{ labels.windowStatus(comparison.window_comparison.status) }}</p>
        <p>{{ labels.reasons(comparison.window_comparison.reasons) }}</p>
        <div
          v-if="emptyWindowNotes.length"
          data-testid="baseline-empty-window"
          class="notice notice-warn"
          role="status"
        >
          <p>{{ BASELINE_LABELS.emptyWindowHint }}</p>
          <ul>
            <li
              v-for="note in emptyWindowNotes"
              :key="note"
            >
              {{ note }}
            </li>
          </ul>
        </div>
        <p>
          {{ labels.windowStats('baseline', comparison.window_comparison.baseline_sample_count, comparison.window_comparison.baseline_duration_ms) }}
          {{ labels.windowStats('current', comparison.window_comparison.current_sample_count, comparison.window_comparison.current_duration_ms) }}
        </p>
        <p>{{ labels.uncertaintyNote }}</p>
        <div
          class="table-wrap"
          tabindex="0"
          role="region"
          :aria-label="labels.windowRegion"
        >
          <table>
            <thead>
              <tr>
                <th scope="col">
                  {{ labels.windowMetricHead }}
                </th><th scope="col">
                  {{ labels.baselineHead }}
                </th><th scope="col">
                  {{ labels.currentHead }}
                </th><th scope="col">
                  {{ labels.absoluteHead }}
                </th><th scope="col">
                  {{ labels.relativeHead }}
                </th><th scope="col">
                  {{ labels.statusHead }}
                </th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="(metric, index) in comparison.window_comparison.metrics"
                :key="`${metric.metric}-${metric.entity}-${metric.resource_series_id}-${index}`"
              >
                <td>{{ labels.metric(metric.metric) }} / {{ metric.entity ?? labels.overall }} / {{ metric.resource_series_id ?? '—' }} / {{ labels.unit(metric.unit) }}</td>
                <td>{{ metric.baseline ?? labels.na }}</td>
                <td>{{ metric.current ?? labels.na }}</td>
                <td>{{ metric.delta ?? labels.naReason(metric.reason) }}</td>
                <td>{{ metric.delta_percent === null ? labels.naReason(metric.percent_reason) : `${metric.delta_percent}%` }}</td>
                <td>{{ labels.windowStatus(metric.status) }} · {{ labels.reasonOrDash(metric.reason) }}</td>
              </tr>
            </tbody>
          </table>
        </div>
        <details>
          <summary>{{ labels.rawSummary }}</summary>
          <pre>{{ JSON.stringify(comparison.window_comparison, null, 2) }}</pre>
        </details>
      </section>
    </section>
  </section>
</template>

<style>
.baseline-panel p { overflow-wrap: anywhere; }
</style>
