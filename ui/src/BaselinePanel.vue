<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import BaselineCharts from './BaselineCharts.vue'
import { ApiError, clearBaseline, compareBaseline, getBaseline, getBaselineConditions, setBaseline, setBaselineConditions } from './api'
import { baselineIneligibility, type BaselineFacts } from './shell/history'
import { BASELINE_LABELS } from './shell/labels'
import { EN_COMPARE_LABELS, type CompareLabels } from './shell/labels.compare'
import type { AnalysisReference, BaselineComparison, BaselineCondition, BaselineConditionDecision, BaselineConditionWindows, BaselineRequest, BaselineSelection, BaselineSlotView } from './types'

// `version` changes when the history tab assigned a baseline; `preferredSeries` is the series of the release opened from the history.
const props = withDefaults(defineProps<{ selection: AnalysisReference | null; filename: string; working: boolean; labels?: CompareLabels; version?: number; preferredSeries?: string; facts?: BaselineFacts | null }>(), { labels: () => EN_COMPARE_LABELS, version: 0, preferredSeries: undefined, facts: null })
// The series of the shown baseline: the saved analytics ask the server for the same baseline.
const emit = defineEmits<{ 'active-series': [series: string | undefined] }>()
const slots = ref<BaselineSlotView[]>([])
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
const errorLang = ref<string | undefined>(undefined)
let baselineRevision = 0
let comparisonRevision = 0
let conditionRevision = 0

const busy = computed(() => loading.value || saving.value || conditionSaving.value || props.working)
const canAdd = computed(() => props.selection && candidates.value.length < 20
  && !candidates.value.some((candidate) => candidate.reference.run_id === props.selection?.run_id))
// Why the open analysis cannot become a baseline (new shell only: the old interface leaves the decision to the server).
const ineligibleCode = computed(() => (props.labels.ineligible && props.facts ? baselineIneligibility(props.facts) : null))
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
const incompatible = computed(() => errorCode.value === 'BASELINE_MIXED_SEMANTICS'
  || comparison.value?.metrics.some((metric) => metric.reason === 'INCOMPATIBLE_METRIC_DEFINITION')
  || comparison.value?.window_comparison?.reasons.includes('INCOMPATIBLE_METRIC_DEFINITION'))

onMounted(loadBaseline)
watch(() => props.selection, conditionBindingChanged)
watch([baselineWindow, currentWindow], conditionBindingChanged)
watch([minChangePercent, minErrorRateDelta], invalidateComparison)
watch(series, () => {
  comparable.value = false
  showSlotOfSeries()
})
// A series without a baseline is still the chosen series: the analytics must not fall back to another baseline then.
watch(() => baseline.value?.series ?? (slots.value.length ? series.value.normalize('NFC').trim() : undefined), (active) => emit('active-series', active), { immediate: true })
watch(() => [props.version, props.preferredSeries], () => {
  if (props.preferredSeries) series.value = props.preferredSeries
  void loadBaseline()
})

// The server stores series normalized (NFC, trimmed), so the field is compared in that form.
function slotOfSeries(name: string): BaselineSlotView | undefined {
  const wanted = name.normalize('NFC').trim()
  return slots.value.find((slot) => slot.series === wanted)
}

// Typing a series or choosing a radio shows the baseline of that series; no slot means no baseline for it.
function showSlotOfSeries() {
  const next = slotOfSeries(series.value)?.baseline ?? null
  const same = next === null ? baseline.value === null : baseline.value !== null && next.series === baseline.value.series
    && next.mode === baseline.value.mode && next.reference.run_id === baseline.value.reference.run_id
    && next.reference.analysis_id === baseline.value.reference.analysis_id
  if (same) return
  baseline.value = next
  conditionBindingChanged()
}

function useSlots(response: Awaited<ReturnType<typeof getBaseline>>) {
  slots.value = response.baselines ?? (response.baseline
    ? [{ series: response.baseline.series, arm: null, source: 'LEGACY', baseline: response.baseline }]
    : [])
}

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
    const response = await getBaselineConditions({ ...props.selection }, selectedConditionWindows(), baseline.value.series)
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
    useSlots(response)
    // On opening, a field that names no baseline takes the first one (a single baseline is the usual case).
    // A series chosen from the history stays even without a baseline: the panel then says so for that series.
    if (!props.preferredSeries && !slotOfSeries(series.value) && slots.value[0]) series.value = slots.value[0].series
    baseline.value = slotOfSeries(series.value)?.baseline ?? null
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
    if (request) {
      const response = await setBaseline(request)
      if (revision !== baselineRevision) return
      series.value = response.baseline.series
    } else {
      // The arm of the shown baseline names its slot together with the series.
      await clearBaseline(baseline.value?.series, slotOfSeries(series.value)?.arm ?? undefined)
    }
    const listed = await getBaseline()
    if (revision !== baselineRevision) return
    useSlots(listed)
    baseline.value = slotOfSeries(series.value)?.baseline ?? null
    conditionRevision += 1
    conditions.value = null
    conditionDecision.value = 'UNKNOWN'
    if (baseline.value) void loadConditions()
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
  // Результат сравнения не сбрасывается: решение меняет только строку условий, поэтому после сохранения
  // сравнение повторяется само, а до ответа на экране остаётся прежний результат.
  const refresh = comparison.value !== null || comparing.value
  // Ответ сравнения, начатого до сохранения, устарел и не должен затереть выбор решения: его отбрасываем, показанный результат оставляем.
  comparisonRevision += 1
  comparing.value = false
  let saved = false
  conditionSaving.value = true
  error.value = ''
  errorCode.value = ''
  try {
    const response = await setBaselineConditions({ ...props.selection }, conditionDecision.value, selectedConditionWindows(), baseline.value.series)
    if (revision !== conditionRevision || stateRevision !== baselineRevision) return
    conditions.value = response.conditions
    conditionDecision.value = response.conditions.decision
    saved = true
  } catch (failure) {
    if (revision === conditionRevision && stateRevision === baselineRevision) showError(failure)
  } finally {
    conditionSaving.value = false
  }
  if (saved && refresh) await compare(true)
}

async function compare(keepResult = false) {
  if (!props.selection || !baseline.value || busy.value || !validWindows.value) {
    // Повтор после сохранения решения не стартует (например, началась пробная проверка): устаревший результат не оставляем.
    if (keepResult) comparison.value = null
    return
  }
  const revision = ++comparisonRevision
  const stateRevision = baselineRevision
  if (!keepResult) comparison.value = null
  comparing.value = true
  error.value = ''
  errorCode.value = ''
  try {
    const response = await compareBaseline({ ...props.selection }, windowSelection.value ? {
      baseline_window: baselineWindow.value.trim(),
      current_window: currentWindow.value.trim(),
      min_change_percent: minChangePercent.value,
      min_error_rate_delta: minErrorRateDelta.value,
    } : undefined, baseline.value.series)
    if (revision !== comparisonRevision || stateRevision !== baselineRevision) return
    comparison.value = response
    baseline.value = response.baseline
    conditions.value = response.conditions
    conditionDecision.value = response.conditions?.decision ?? 'UNKNOWN'
  } catch (failure) {
    if (revision === comparisonRevision && stateRevision === baselineRevision) {
      comparison.value = null
      showError(failure)
    }
  } finally {
    if (revision === comparisonRevision) comparing.value = false
  }
}

function showError(failure: unknown) {
  // A phrase of the dictionary is in the language of the panel; the server text (and the text of a network failure) is English.
  const phrase = failure instanceof ApiError ? props.labels.errorText(failure.code, failure.limit) : null
  error.value = phrase ?? (failure instanceof Error ? failure.message : props.labels.requestFailed)
  errorCode.value = failure instanceof ApiError ? failure.code : ''
  errorLang.value = phrase !== null || !(failure instanceof Error) ? undefined : props.labels.foreignLang
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

    <fieldset
      v-if="!loading && slots.length"
      data-testid="baseline-slots"
      class="field baseline-slots"
      :disabled="busy"
    >
      <legend>{{ labels.slotsLegend }}</legend>
      <label
        v-for="slot in slots"
        :key="`${slot.series}/${slot.arm ?? ''}`"
        class="baseline-slot"
      >
        <input
          type="radio"
          name="baseline-slot"
          :checked="baseline !== null && slot.series === baseline.series"
          @change="series = slot.series"
        >
        <span><strong>{{ slot.series }}</strong> · {{ labels.mode(slot.baseline.mode) }} · {{ labels.analysisWord }} <span class="mono">{{ slot.baseline.reference.analysis_id.slice(0, 12) }}</span></span>
      </label>
    </fieldset>

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
      {{ slots.length ? labels.noBaselineFor(series.normalize('NFC').trim()) : labels.noBaseline }}
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
        :disabled="busy || !selection || !validSeries || ineligibleCode !== null"
        :aria-describedby="ineligibleCode === null ? undefined : 'baseline-ineligible'"
        @click="assignManual"
      >
        {{ labels.setBaseline }}
      </button>
      <button
        type="button"
        :disabled="busy || conditionSaving || !baseline || !selection || comparing || !validWindows"
        @click="compare()"
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

    <p
      v-if="ineligibleCode !== null && labels.ineligible"
      id="baseline-ineligible"
      data-testid="baseline-ineligible"
      class="field__hint"
      role="status"
    >
      {{ labels.ineligible(ineligibleCode) }}
    </p>

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
      :lang="errorLang"
    >
      {{ error }}
    </p>
    <p
      v-if="incompatible"
      data-testid="baseline-incompatible"
      class="notice notice-info"
      role="status"
    >
      {{ BASELINE_LABELS.incompatibleHint }}
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
              <td :title="labels.exact(metric.baseline)">
                {{ labels.value(metric.baseline, metric.current) }}
              </td>
              <td :title="labels.exact(metric.current)">
                {{ labels.value(metric.current, metric.baseline) }}
              </td>
              <td :title="labels.exact(metric.delta)">
                {{ labels.deltaValue(metric.delta, metric.reason) }}
              </td>
              <td :title="labels.exact(metric.delta_percent, true)">
                {{ labels.percentValue(metric.delta_percent, metric.percent_reason) }}
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <p class="field__hint">
        {{ labels.roundingNote }}
      </p>
      <p
        v-if="comparison.profile"
        data-testid="baseline-profile"
      >
        {{ labels.profileLine(comparison.profile) }}
      </p>
      <details v-if="labels.rawMetricsSummary">
        <summary>{{ labels.rawMetricsSummary }}</summary>
        <pre>{{ JSON.stringify(comparison.metrics, null, 2) }}</pre>
      </details>
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
                <td :title="labels.exact(metric.baseline)">
                  {{ labels.value(metric.baseline, metric.current) }}
                </td>
                <td :title="labels.exact(metric.current)">
                  {{ labels.value(metric.current, metric.baseline) }}
                </td>
                <td :title="labels.exact(metric.delta)">
                  {{ labels.deltaValue(metric.delta, metric.reason) }}
                </td>
                <td :title="labels.exact(metric.delta_percent, true)">
                  {{ labels.percentValue(metric.delta_percent, metric.percent_reason) }}
                </td>
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
.baseline-panel[lang='ru'] p { overflow-wrap: anywhere; }
.baseline-panel[lang='ru'] .notice { flex-wrap: wrap; }
.baseline-panel[lang='ru'] pre { max-width: 100%; overflow-x: auto; }
</style>
