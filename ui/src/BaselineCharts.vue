<script setup lang="ts">
import { ref, watch } from 'vue'
import { getBuckets } from './api'
import type { BaselineComparison, Bucket } from './types'
import LoadCharts from './LoadCharts.vue'
import { EN_COMPARE_LABELS, type CompareLabels } from './shell/labels.compare'

const props = withDefaults(defineProps<{ comparison: BaselineComparison; labels?: CompareLabels }>(), { labels: () => EN_COMPARE_LABELS })
const current = ref<Bucket[]>([])
const baseline = ref<Bucket[]>([])
const error = ref('')
const truncated = ref(false)
const rollup = ref(60)
const busy = ref(false)
let revision = 0
watch(() => props.comparison, () => { revision++; current.value = []; baseline.value = []; error.value = ''; busy.value = false })
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
</script>

<template>
  <details>
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
