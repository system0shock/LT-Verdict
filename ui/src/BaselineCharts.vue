<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { getBuckets } from './api'
import type { AnalysisResult, BaselineComparison, Bucket } from './types'
import LoadCharts from './LoadCharts.vue'
import CompareCursorChart from './shell/CompareCursorChart.vue'
import { fetchRunLoad } from './shell/deep'
import { EN_COMPARE_LABELS, type CompareLabels } from './shell/labels.compare'
import { loadSeries, type LoadSeries } from './shell/overview'

const props = withDefaults(defineProps<{
  comparison: BaselineComparison
  labels?: CompareLabels
  result?: AnalysisResult | null
  useEcharts?: boolean
}>(), {
  labels: () => EN_COMPARE_LABELS,
  result: null,
  useEcharts: false,
})

const current = ref<Bucket[]>([])
const baseline = ref<Bucket[]>([])
const currentSeries = ref<LoadSeries | null>(null)
const baselineSeries = ref<LoadSeries | null>(null)
const error = ref('')
const truncated = ref(false)
const rollup = ref(60)
const busy = ref(false)
let revision = 0

const incompatible = computed(() => props.comparison.metrics.some((item) => item.reason === 'INCOMPATIBLE_METRIC_DEFINITION'))

watch(() => [props.comparison, props.useEcharts] as const, () => {
  revision += 1
  current.value = []
  baseline.value = []
  currentSeries.value = null
  baselineSeries.value = null
  error.value = ''
  truncated.value = false
  busy.value = false
  if (props.useEcharts && !incompatible.value) void loadEcharts()
}, { immediate: true })

async function load() {
  const token = ++revision
  busy.value = true; error.value = ''; current.value = []; baseline.value = []
  try {
    const a = props.comparison.current
    const b = props.comparison.baseline.reference
    const [left, right] = await Promise.all([getBuckets(a.run_id, a.analysis_id, rollup.value), getBuckets(b.run_id, b.analysis_id, rollup.value)])
    if (token !== revision) return
    current.value = left.buckets; baseline.value = right.buckets
    truncated.value = left.next_from_ms !== null || right.next_from_ms !== null
  } catch (failure) { if (token === revision) error.value = failure instanceof Error ? failure.message : props.labels.chartsUnavailable }
  finally { if (token === revision) busy.value = false }
}

async function loadEcharts() {
  const token = ++revision
  busy.value = true
  error.value = ''
  try {
    const a = props.comparison.current
    const b = props.comparison.baseline.reference
    const [left, right] = await Promise.all([
      fetchRunLoad((step, fromMs) => getBuckets(a.run_id, a.analysis_id, step, fromMs)),
      fetchRunLoad((step, fromMs) => getBuckets(b.run_id, b.analysis_id, step, fromMs)),
    ])
    if (token !== revision) return
    currentSeries.value = loadSeries(left.buckets, left.rollupSeconds)
    baselineSeries.value = loadSeries(right.buckets, right.rollupSeconds)
    truncated.value = left.truncated || right.truncated
  } catch (failure) {
    if (token === revision) error.value = failure instanceof Error ? failure.message : props.labels.chartsUnavailable
  } finally {
    if (token === revision) busy.value = false
  }
}
</script>

<template>
  <section
    v-if="useEcharts"
    class="compare-charts"
    data-testid="compare-charts"
    aria-labelledby="compare-charts-title"
  >
    <h4 id="compare-charts-title">
      {{ labels.chartsSummary }}
    </h4>
    <p class="muted">
      {{ labels.chartsNote }}
    </p>
    <p
      v-if="incompatible"
      role="status"
      data-testid="compare-charts-incompatible"
    >
      {{ labels.chartsIncompatible }}
    </p>
    <p
      v-else-if="busy"
      role="status"
      data-testid="compare-charts-loading"
    >
      {{ labels.chartsLoading }}
    </p>
    <p
      v-else-if="error"
      role="alert"
      data-testid="compare-charts-error"
      :lang="labels.foreignLang"
    >
      {{ error }}
    </p>
    <template v-else-if="currentSeries && baselineSeries && (currentSeries.points.length || baselineSeries.points.length)">
      <p
        v-if="truncated"
        class="muted"
        data-testid="compare-charts-partial"
      >
        {{ labels.chartsPartial }}
      </p>
      <CompareCursorChart
        :current-series="currentSeries"
        :baseline-series="baselineSeries"
        :result="result"
        :labels="labels"
      />
    </template>
    <p
      v-else
      role="status"
      data-testid="compare-charts-empty"
    >
      {{ labels.chartsEmpty }}
    </p>
  </section>
  <details v-else>
    <summary>{{ labels.chartsSummary }}</summary>
    <p>{{ labels.chartsNote }}</p>
    <label>{{ labels.chartsBin }} <select
      v-model.number="rollup"
      :disabled="busy"
      @change="current = []; baseline = []"
    ><option
      v-for="step in [1, 10, 30, 60]"
      :key="step"
      :value="step"
    >{{ step }} {{ labels.chartsStepUnit }}</option></select></label>
    <button
      type="button"
      :disabled="busy || comparison.metrics.some(item => item.reason === 'INCOMPATIBLE_METRIC_DEFINITION')"
      @click="load"
    >
      {{ labels.chartsLoad }}
    </button>
    <p
      v-if="busy"
      role="status"
    >
      {{ labels.chartsLoading }}
    </p>
    <p
      v-if="error"
      role="alert"
      :lang="labels.foreignLang"
    >
      {{ error }}
    </p>
    <p v-if="truncated && current.length">
      {{ labels.chartsTruncated }}
    </p>
    <LoadCharts
      v-if="current.length || baseline.length"
      :buckets="current"
      :baseline-buckets="baseline"
      :rollup="rollup"
      :lang="labels.foreignLang"
    />
  </details>
</template>

<style scoped>
.compare-charts {
  display: grid;
  gap: 8px;
  margin: 16px 0;
  min-width: 0;
}

.compare-charts h4 {
  margin: 0;
}
</style>
