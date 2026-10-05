<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { getBuckets } from '../api'
import { fetchRunLoad, type RunLoad } from './deep'
import { OVERVIEW_LABELS } from './labels'
import SharedCursorChart from './SharedCursorChart.vue'
import { attentionItems, keyMetrics, loadSeries, type AttentionKind, type AttentionTarget } from './overview'
import type { AnalysisResult } from '../types'

const props = defineProps<{
  result: AnalysisResult
  runId: string
  analysisId: string
}>()
const emit = defineEmits<{ navigate: [target: AttentionTarget] }>()

const LIMIT = 6
const expanded = ref(false)
const items = computed(() => attentionItems(props.result))
const visibleItems = computed(() => expanded.value ? items.value : items.value.slice(0, LIMIT))
const tiles = computed(() => keyMetrics(props.result))
const runLoad = ref<RunLoad>({ buckets: [], rollupSeconds: 60, truncated: false })
const loading = ref(false)
const loadError = ref('')
const series = computed(() => loadSeries(runLoad.value.buckets, runLoad.value.rollupSeconds))
const controller = new AbortController()

// Обзор грузит весь прогон сам: шаг и диапазон вкладки «Таблицы» на него не влияют.
onMounted(async () => {
  if (props.result.run_validity === 'INVALID') return
  loading.value = true
  try {
    const loaded = await fetchRunLoad((rollup, from) => getBuckets(props.runId, props.analysisId, rollup, from, undefined, controller.signal))
    if (!controller.signal.aborted) runLoad.value = loaded
  } catch (failure) {
    if (!controller.signal.aborted) loadError.value = OVERVIEW_LABELS.loadFailed(failure instanceof Error ? failure.message : String(failure))
  } finally {
    if (!controller.signal.aborted) loading.value = false
  }
})
onUnmounted(() => controller.abort())
const kindLabels: Record<AttentionKind, string> = {
  violation: OVERVIEW_LABELS.kindViolation,
  no_verdict: OVERVIEW_LABELS.kindNoVerdict,
  policy: OVERVIEW_LABELS.kindPolicy,
  coverage: OVERVIEW_LABELS.kindCoverage,
  diagnostic: OVERVIEW_LABELS.kindDiagnostic,
}
</script>

<template>
  <div
    class="overview-panel"
    data-testid="overview-panel"
    lang="ru"
  >
    <section
      id="overview-attention"
      class="panel"
      aria-labelledby="overview-attention-title"
    >
      <h2 id="overview-attention-title">
        {{ OVERVIEW_LABELS.attentionTitle }}
      </h2>
      <p class="muted">
        {{ OVERVIEW_LABELS.attentionLead }}
      </p>
      <p v-if="!items.length">
        {{ OVERVIEW_LABELS.attentionEmpty }}
      </p>
      <template v-else>
        <ul
          class="overview-attention"
          data-testid="attention-list"
        >
          <li
            v-for="attention in visibleItems"
            :key="attention.key"
            data-testid="attention-item"
            :data-kind="attention.kind"
          >
            <span class="overview-kind">{{ kindLabels[attention.kind] }}</span>
            <span
              v-if="attention.diagnostic"
              class="overview-badge"
            >{{ attention.badge ?? OVERVIEW_LABELS.diagnosticBadge }}</span>
            <strong>{{ attention.title }}</strong>
            <span
              v-if="attention.detail"
              class="muted"
            >{{ attention.detail }}</span>
            <button
              v-if="attention.target"
              type="button"
              data-testid="attention-open"
              :aria-label="attention.openLabel + ': ' + attention.title"
              @click="emit('navigate', attention.target)"
            >
              {{ attention.openLabel }}
            </button>
          </li>
        </ul>
        <button
          v-if="items.length > LIMIT"
          type="button"
          data-testid="attention-more"
          :aria-expanded="expanded"
          @click="expanded = !expanded"
        >
          {{ expanded ? OVERVIEW_LABELS.attentionLess : OVERVIEW_LABELS.attentionMore(items.length - LIMIT) }}
        </button>
      </template>
      <p
        v-if="items.some((attention) => attention.diagnostic)"
        class="muted"
        data-testid="diagnostic-note"
      >
        {{ OVERVIEW_LABELS.diagnosticNote }}
      </p>
    </section>

    <section
      v-if="tiles.length"
      id="overview-metrics"
      class="panel"
      aria-labelledby="overview-metrics-title"
    >
      <h2 id="overview-metrics-title">
        {{ OVERVIEW_LABELS.metricsTitle }}
      </h2>
      <p
        v-if="result.analysis_mode === 'capacity_step'"
        class="muted"
        data-testid="metrics-capacity-note"
      >
        {{ OVERVIEW_LABELS.metricsCapacityNote }}
      </p>
      <dl class="overview-metrics">
        <div
          v-for="tile in tiles"
          :key="tile.key"
          data-testid="metric-tile"
          :data-metric="tile.key"
          :data-value="tile.raw"
        >
          <dt>{{ tile.label }}</dt>
          <dd>{{ tile.value }}</dd>
        </div>
      </dl>
    </section>

    <section
      id="overview-load"
      class="panel"
      aria-labelledby="overview-load-title"
    >
      <h2 id="overview-load-title">
        {{ OVERVIEW_LABELS.loadTitle }}
      </h2>
      <p class="muted">
        {{ OVERVIEW_LABELS.loadLead }}
      </p>
      <p class="muted">
        {{ OVERVIEW_LABELS.loadReadNote }}
      </p>
      <p
        v-if="loading"
        data-testid="load-loading"
      >
        {{ OVERVIEW_LABELS.loadLoading }}
      </p>
      <p
        v-else-if="loadError"
        role="alert"
        data-testid="load-error"
      >
        {{ loadError }}
      </p>
      <SharedCursorChart
        v-else-if="series.points.length"
        :series="series"
      />
      <p
        v-else
        data-testid="load-empty"
      >
        {{ OVERVIEW_LABELS.loadEmpty }}
      </p>
      <p
        v-if="runLoad.truncated"
        class="muted"
        data-testid="load-partial"
      >
        {{ OVERVIEW_LABELS.loadPartial(runLoad.buckets.length, runLoad.rollupSeconds) }}
      </p>
    </section>
  </div>
</template>

<style scoped>
.overview-panel {
  display: grid;
  gap: 24px;
}

.overview-attention {
  display: grid;
  gap: 8px;
  list-style: none;
  margin: 0;
  padding: 0;
}

.overview-attention li {
  display: grid;
  gap: 4px;
  border: 1px solid var(--border);
  border-radius: var(--shell-radius, 8px);
  padding: 12px;
}

.overview-attention li[data-kind="violation"] {
  border-left-color: var(--fail);
}

.overview-attention li[data-kind="no_verdict"],
.overview-attention li[data-kind="policy"],
.overview-attention li[data-kind="coverage"] {
  border-left-color: var(--warn);
}

.overview-attention li[data-kind="diagnostic"] {
  border-left-color: var(--brand);
}

.overview-kind {
  font-size: 12px;
  font-weight: 700;
}

.overview-badge {
  justify-self: start;
  border-radius: 999px;
  background: var(--info-bg);
  color: var(--brand);
  padding: 2px 8px;
}

.overview-attention button,
[data-testid="attention-more"] {
  min-width: 44px;
  min-height: 44px;
}

.overview-metrics {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(140px, 1fr));
  gap: 12px;
  margin: 0;
}

.overview-metrics div {
  display: grid;
  gap: 4px;
}

.overview-metrics dt {
  color: var(--text-muted);
}

.overview-metrics dd {
  margin: 0;
  font-size: 1.25rem;
  font-weight: 700;
}
</style>
