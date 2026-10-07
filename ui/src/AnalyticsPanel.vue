<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { getSavedAnalytics } from './api'
import type { AnalysisReference } from './types'
import type { SavedAnalytics } from './analyticsTypes'
import { EN_ANALYTICS_LABELS, type AnalyticsLabels } from './shell/labels.export'
import MetricPackSummary from './MetricPackSummary.vue'
import OpenSearchOverlay from './OpenSearchOverlay.vue'
import RunDynamicsTable from './RunDynamicsTable.vue'
import TransactionComparisonTable from './TransactionComparisonTable.vue'

// `series` is the series of the active baseline (BaselinePanel): the server picks the baseline of the comparison by it.
// `labels` and `lang` switch the panel texts to Russian in the new shell; the tables below stay English and are marked as such.
const props = withDefaults(defineProps<{ selection: AnalysisReference | null; working: boolean; series?: string; labels?: AnalyticsLabels; lang?: string }>(), { series: undefined, labels: () => EN_ANALYTICS_LABELS, lang: undefined })
const foreignLang = computed(() => (props.lang ? 'en' : undefined))
const emit = defineEmits<{ loaded: [analytics: SavedAnalytics | null] }>()
const limit = ref(10)
const transaction = ref('')
const transactionLimit = ref(100)
const analytics = ref<SavedAnalytics | null>(null)
const selectedDynamics = ref<AnalysisReference[]>([])
const loadedOptions = ref<{ limit: number; transaction: string; transactionLimit: number; series: string } | null>(null)
const loading = ref(false)
const error = ref('')
let revision = 0

const disabled = computed(() => props.working || loading.value || !props.selection)
const exportHref = computed(() => {
  if (!props.selection || !loadedOptions.value) return ''
  const query = new URLSearchParams({ limit: String(loadedOptions.value.limit), transaction_limit: String(loadedOptions.value.transactionLimit) })
  if (loadedOptions.value.transaction) query.set('transaction', loadedOptions.value.transaction)
  if (loadedOptions.value.series) query.set('series', loadedOptions.value.series)
  const selected = new Set(selectedDynamics.value.map(referenceKey))
  analytics.value?.dynamics?.rows.forEach((row) => {
    if (!selected.has(referenceKey(row.reference))) query.append('exclude', referenceKey(row.reference))
  })
  return `/api/runs/${encodeURIComponent(props.selection.run_id)}/analyses/${encodeURIComponent(props.selection.analysis_id)}/analytics?${query}`
})

watch(() => [props.selection, props.series], reset, { immediate: true })

function reset() {
  revision++
  analytics.value = null
  selectedDynamics.value = []
  loadedOptions.value = null
  loading.value = false
  error.value = ''
  emit('loaded', null)
}

async function load() {
  const selection = props.selection
  const options = { limit: limit.value, transaction: transaction.value.trim(), transactionLimit: transactionLimit.value, series: props.series ?? '' }
  const requestRevision = ++revision
  const selectionKey = selection ? `${selection.run_id}/${selection.analysis_id}` : null
  analytics.value = null
  selectedDynamics.value = []
  loadedOptions.value = null
  emit('loaded', null)
  error.value = ''
  if (!selection) return
  loading.value = true
  try {
    const response = await getSavedAnalytics({ ...selection }, options.limit, options.transaction, options.transactionLimit, options.series)
    const currentKey = props.selection ? `${props.selection.run_id}/${props.selection.analysis_id}` : null
    if (requestRevision === revision && currentKey === selectionKey) {
      analytics.value = response
      selectedDynamics.value = response.dynamics?.rows.map((row) => row.reference) ?? []
      loadedOptions.value = options
      emit('loaded', response)
    }
  } catch (failure) {
    if (requestRevision === revision) error.value = failure instanceof Error ? failure.message : props.labels.failed
  } finally {
    if (requestRevision === revision) loading.value = false
  }
}

function referenceKey(reference: AnalysisReference) {
  return `${reference.run_id}/${reference.analysis_id}`
}
</script>

<template>
  <section
    id="saved-analytics-panel"
    class="panel"
    :lang="lang"
    aria-labelledby="saved-analytics-title"
  >
    <header class="panel__header">
      <h2 id="saved-analytics-title">
        {{ labels.title }}
      </h2>
      <p>{{ labels.lead }}</p>
    </header>
    <div class="form-grid">
      <div class="field">
        <label for="analytics-run-limit">{{ labels.comparableRuns }}</label>
        <input
          id="analytics-run-limit"
          v-model.number="limit"
          type="number"
          min="1"
          max="100"
          :disabled="disabled"
        >
      </div>
      <div class="field">
        <label for="analytics-transaction">{{ labels.transactionFilter }}</label>
        <input
          id="analytics-transaction"
          v-model="transaction"
          maxlength="256"
          :disabled="disabled"
        >
      </div>
      <div class="field">
        <label for="analytics-transaction-limit">{{ labels.transactionRows }}</label>
        <input
          id="analytics-transaction-limit"
          v-model.number="transactionLimit"
          type="number"
          min="1"
          max="200"
          :disabled="disabled"
        >
      </div>
    </div>
    <div class="policy-editor__actions">
      <button
        type="button"
        :disabled="disabled"
        @click="load"
      >
        {{ loading ? labels.refreshing : labels.refresh }}
      </button>
      <a
        v-if="analytics && exportHref"
        :href="exportHref"
        download="saved-analytics.json"
      >{{ labels.exportJson }}</a>
      <a
        v-if="analytics && exportHref"
        :href="`${exportHref}&format=html`"
        download="run-dynamics.html"
      >{{ labels.exportHtml }}</a>
      <a
        v-if="analytics && exportHref"
        :href="`${exportHref}&format=asciidoc`"
        download="run-dynamics.adoc"
      >{{ labels.exportAsciidoc }}</a>
      <a
        v-if="analytics && exportHref"
        :href="`${exportHref}&format=confluence`"
        download="run-dynamics.xhtml"
      >{{ labels.exportConfluence }}</a>
    </div>
    <p
      v-if="loading"
      role="status"
    >
      {{ labels.loading }}
    </p>
    <p
      v-if="error"
      class="notice notice-fail"
      role="alert"
    >
      {{ error }}
    </p>
    <template v-if="analytics">
      <p
        v-if="analytics.history_scan_truncated"
        class="notice notice-fail"
        role="status"
      >
        {{ labels.scanTruncated(analytics.history_scan_limit, analytics.history_metadata_byte_limit.toLocaleString()) }}
        {{ labels.scanTruncatedNote }}
      </p>
      <RunDynamicsTable
        v-if="analytics.dynamics"
        :dynamics="analytics.dynamics"
        :lang="foreignLang"
        @selection="selectedDynamics = $event"
      />
      <p
        v-else
        class="field__hint"
      >
        {{ labels.noDynamics }}
      </p>
      <TransactionComparisonTable
        v-if="analytics.transactions"
        :comparison="analytics.transactions"
        :lang="foreignLang"
      />
      <p
        v-else
        class="field__hint"
      >
        {{ labels.noBaseline }}
      </p>
      <OpenSearchOverlay
        v-if="analytics.overlay"
        :overlay="analytics.overlay"
        :lang="foreignLang"
      />
      <p
        v-else
        class="field__hint"
      >
        {{ labels.noOverlay }}
      </p>
      <MetricPackSummary
        :analysis="analytics.metric_packs"
        :lang="foreignLang"
      />
    </template>
  </section>
</template>
