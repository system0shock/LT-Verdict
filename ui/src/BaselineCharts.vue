<script setup lang="ts">
import { ref, watch } from 'vue'
import { getBuckets } from './api'
import type { BaselineComparison, Bucket } from './types'
import LoadCharts from './LoadCharts.vue'

const props = defineProps<{ comparison: BaselineComparison }>()
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
  } catch (failure) { if (token === revision) error.value = failure instanceof Error ? failure.message : 'Chart comparison unavailable.' }
  finally { if (token === revision) busy.value = false }
}
</script>

<template>
  <details>
    <summary>Baseline/current charts</summary>
    <p>Relative time from each load start; this view does not align stages or prove equal test conditions. OpenSearch events remain in their individual run views.</p>
    <label>Comparison bin width <select
      v-model.number="rollup"
      :disabled="busy"
      @change="current = []; baseline = []"
    ><option
      v-for="step in [1, 10, 30, 60]"
      :key="step"
      :value="step"
    >{{ step }} s</option></select></label>
    <button
      type="button"
      :disabled="busy || comparison.metrics.some(item => item.reason === 'INCOMPATIBLE_METRIC_DEFINITION')"
      @click="load"
    >
      Load comparison charts
    </button>
    <p
      v-if="busy"
      role="status"
    >
      Loading saved buckets…
    </p>
    <p
      v-if="error"
      role="alert"
    >
      {{ error }}
    </p>
    <p v-if="truncated && current.length">
      Showing the first 500 bins per run. Later bins are omitted; use a wider bin or the individual run view.
    </p>
    <LoadCharts
      v-if="current.length || baseline.length"
      :buckets="current"
      :baseline-buckets="baseline"
      :rollup="rollup"
    />
  </details>
</template>
