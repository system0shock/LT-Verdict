<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { ApiError, getBuckets, getResourceSeriesCatalog, getResourceSeriesValues, resourceSeriesValuesPath } from '../api'
import type { AnalysisResult, Bucket, ResourceSeriesEntry } from '../types'
import { DEEP_LABELS } from './labels'
import { formatOffset } from './overview'
import {
  MAX_LOAD_PAGES, MAX_SELECTED_SERIES, batchSeriesIds, chooseLoadRollup, defaultSelection,
  gridEndMs, loadBucketRange, loadTracks, mergePages, planResourcePeriod, resourceTrack,
  runStartMs, thresholdsFor, type DeepTrack, type SnapshotGrid, type TimeAxis,
} from './deep'
import DeepCursorChart from './DeepCursorChart.vue'

const props = defineProps<{ result: AnalysisResult; runId: string; analysisId: string }>()
const origin = runStartMs(props.result)
const originMs = origin ?? 0
const hasSnapshot = ref(origin !== null)
const catalog = ref<ResourceSeriesEntry[]>([])
const grid = ref<SnapshotGrid | null>(null)
const selected = ref<string[]>([])
const filter = ref('')
const periodFrom = ref('')
const periodTo = ref('')
const applied = ref<{ from: number; to: number } | null>(null)
const invalidPeriod = ref(false)
const axis = ref<TimeAxis>({ fromMs: originMs, toMs: originMs })
const stepMs = ref(0)
const rollup = ref<1 | 10 | 30 | 60>(60)
const load = ref<DeepTrack[]>([])
const resources = ref<DeepTrack[]>([])
const loading = ref(false)
const error = ref('')
const loadOutside = ref(false)
const truncated = ref<number | null>(null)
let revision = 0
let controller: AbortController | null = null
let loadPhaseDone = false
const tracks = computed(() => [...load.value, ...resources.value])
const filtered = computed(() => catalog.value.filter((entry) => `${entry.id} ${entry.metric}`.toLocaleLowerCase().includes(filter.value.toLocaleLowerCase())))
const offset = (ms: number) => ms < 0 ? `-${formatOffset(-ms)}` : formatOffset(ms)

async function fetchBuckets(range: { fromMs: number; toMs: number } | null, chosenRollup: number, signal: AbortSignal) {
  const buckets: Bucket[] = []
  let from: number | undefined = range?.fromMs
  let next: number | null = null
  for (let page = 0; page < MAX_LOAD_PAGES; page += 1) {
    const response = await getBuckets(props.runId, props.analysisId, chosenRollup, from, range?.toMs, signal)
    buckets.push(...response.buckets)
    next = response.next_from_ms
    if (next === null) break
    from = next
  }
  return { buckets, next }
}

async function refresh(includeLoad: boolean) {
  controller?.abort()
  const current = ++revision
  const abort = new AbortController()
  controller = abort
  loading.value = true
  error.value = ''
  resources.value = []
  if (includeLoad) { load.value = []; loadPhaseDone = false }
  try {
    if (grid.value) {
      const g = grid.value
      const from = applied.value ? originMs + applied.value.from * 60000 : g.startMs
      const to = applied.value ? originMs + applied.value.to * 60000 : gridEndMs(g)
      const plan = planResourcePeriod(g, from, to)
      axis.value = plan.axis
      stepMs.value = plan.stepMs
      rollup.value = chooseLoadRollup(plan.axis.toMs - plan.axis.fromMs)
      if (includeLoad) {
        loadOutside.value = false
        truncated.value = null
        if (props.result.run_validity === 'INVALID') load.value = []
        else {
          const range = loadBucketRange(originMs, plan.axis, rollup.value)
          if (range === null) { loadOutside.value = true; load.value = [] }
          else {
            const data = await fetchBuckets(range, rollup.value, abort.signal)
            if (current !== revision) return
            load.value = loadTracks(data.buckets, rollup.value, originMs)
            if (data.next !== null) truncated.value = data.buckets.length
          }
        }
        loadPhaseDone = true
      }
      const params = { step_ms: String(plan.stepMs), from_ms: String(plan.axis.fromMs), to_ms: String(plan.axis.toMs) }
      const ids = selected.value.filter((id) => catalog.value.some((entry) => entry.id === id))
      const baseLength = resourceSeriesValuesPath(props.runId, props.analysisId, [], params).length
      const byId = new Map<string, DeepTrack>()
      for (const batch of batchSeriesIds(ids, baseLength)) {
        let nextFrom: number | null = plan.axis.fromMs
        for (let page = 0; page < 200 && nextFrom !== null; page += 1) {
          const path = resourceSeriesValuesPath(props.runId, props.analysisId, batch, { ...params, from_ms: String(nextFrom) })
          const response = await getResourceSeriesValues(path, abort.signal)
          if (current !== revision) return
          for (const series of response.series) {
            const entry = catalog.value.find((item) => item.id === series.id)
            if (!entry) continue
            const track = resourceTrack(entry, series, response.grid, thresholdsFor(props.result, series.id))
            if (track.key === 'load-rps' || track.key === 'load-p95' || track.key.startsWith('resource:')) track.key = `resource:${track.key}`
            byId.set(series.id, byId.has(series.id) ? mergePages(byId.get(series.id)!, track) : track)
          }
          nextFrom = response.next_from_ms
        }
      }
      if (current === revision) resources.value = ids.flatMap((id) => byId.get(id) ?? [])
    } else if (includeLoad) {
      loadOutside.value = false
      truncated.value = null
      if (props.result.run_validity === 'INVALID') { load.value = []; return }
      const probe = await getBuckets(props.runId, props.analysisId, 60, 0, undefined, abort.signal)
      if (current !== revision) return
      const span = probe.next_from_ms === null ? (probe.buckets.at(-1)?.bucket_start_ms ?? -60000) + 60000 : Infinity
      rollup.value = chooseLoadRollup(span)
      const data = rollup.value === 60
        ? await (async () => {
          const buckets = [...probe.buckets]
          let next = probe.next_from_ms
          for (let page = 1; page < MAX_LOAD_PAGES && next !== null; page += 1) {
            const response = await getBuckets(props.runId, props.analysisId, 60, next, undefined, abort.signal)
            buckets.push(...response.buckets)
            next = response.next_from_ms
          }
          return { buckets, next }
        })()
        : await fetchBuckets(null, rollup.value, abort.signal)
      if (current !== revision) return
      load.value = loadTracks(data.buckets, rollup.value, originMs)
      axis.value = { fromMs: originMs, toMs: originMs + (data.buckets.length ? data.buckets[data.buckets.length - 1].bucket_start_ms + rollup.value * 1000 : 0) }
      if (data.next !== null) truncated.value = data.buckets.length
    }
  } catch (cause) {
    if (current === revision && !abort.signal.aborted) error.value = DEEP_LABELS.requestFailed(cause instanceof Error ? cause.message : String(cause))
  } finally {
    if (current === revision) { loadPhaseDone = true; loading.value = false }
  }
}

async function initialize() {
  if (origin === null) { await refresh(true); return }
  const current = ++revision
  controller = new AbortController()
  loading.value = true
  try {
    const entries: ResourceSeriesEntry[] = []
    const seen = new Set<string>()
    let after: string | null = null
    let snapshotGrid: SnapshotGrid | null = null
    do {
      const response = await getResourceSeriesCatalog(props.runId, props.analysisId, after ?? undefined, controller.signal)
      if (current !== revision) return
      if (!snapshotGrid) snapshotGrid = { startMs: response.grid.start_epoch_ms, stepMs: response.grid.step_ms, pointCount: response.grid.point_count }
      for (const entry of response.series) if (!seen.has(entry.id)) { seen.add(entry.id); entries.push(entry) }
      after = response.next_after
    } while (after !== null)
    grid.value = snapshotGrid
    catalog.value = entries
    selected.value = defaultSelection(entries, props.result)
    await refresh(true)
  } catch (cause) {
    if (current !== revision || controller?.signal.aborted) return
    if (cause instanceof ApiError && cause.status === 404) { hasSnapshot.value = false; await refresh(true) }
    else error.value = DEEP_LABELS.requestFailed(cause instanceof Error ? cause.message : String(cause))
  } finally {
    if (current === revision) loading.value = false
  }
}

function toggle(id: string, checked: boolean) {
  if (checked) { if (selected.value.length >= MAX_SELECTED_SERIES) return; selected.value = [...selected.value, id] }
  else selected.value = selected.value.filter((item) => item !== id)
  if (loadPhaseDone) void refresh(false)
}
function applyPeriod() {
  const from = Number(periodFrom.value)
  const to = Number(periodTo.value)
  invalidPeriod.value = !periodFrom.value || !periodTo.value || !Number.isFinite(from) || !Number.isFinite(to) || from < 0 || to < 0 || from >= to
  if (invalidPeriod.value) return
  applied.value = { from, to }
  void refresh(true)
}
function wholePeriod() {
  periodFrom.value = ''
  periodTo.value = ''
  applied.value = null
  invalidPeriod.value = false
  void refresh(true)
}
onMounted(() => { void initialize() })
onUnmounted(() => { revision += 1; controller?.abort() })
</script>

<template>
  <div
    class="deep-panel"
    data-testid="deep-panel"
    lang="ru"
  >
    <section
      class="panel"
      aria-labelledby="deep-title"
    >
      <h2
        id="deep-title"
        tabindex="-1"
      >
        {{ DEEP_LABELS.title }}
      </h2>
      <p class="muted">
        {{ DEEP_LABELS.lead }}
      </p>
      <p class="muted">
        {{ DEEP_LABELS.readNote }}
      </p>
      <p
        v-if="!hasSnapshot"
        data-testid="deep-no-snapshot"
      >
        {{ DEEP_LABELS.noSnapshot }}
      </p>
      <p
        v-if="hasSnapshot"
        class="muted"
        data-testid="deep-clock-note"
      >
        {{ DEEP_LABELS.clockNote }}
      </p>
      <p
        v-if="loading"
        role="status"
      >
        {{ DEEP_LABELS.loading }}
      </p>
      <p
        v-if="error"
        role="alert"
        data-testid="deep-error"
      >
        {{ error }}
      </p>
    </section>
    <div class="deep-layout">
      <div class="deep-main">
        <section
          v-if="hasSnapshot && grid"
          class="panel"
          aria-labelledby="deep-period-title"
        >
          <h2 id="deep-period-title">
            {{ DEEP_LABELS.periodTitle }}
          </h2>
          <div class="period-fields">
            <label>{{ DEEP_LABELS.periodFrom }}<input
              v-model="periodFrom"
              data-testid="deep-period-from"
              type="number"
              min="0"
              step="any"
            ></label>
            <label>{{ DEEP_LABELS.periodTo }}<input
              v-model="periodTo"
              data-testid="deep-period-to"
              type="number"
              min="0"
              step="any"
            ></label>
          </div>
          <div class="period-actions">
            <button
              type="button"
              data-testid="deep-period-apply"
              @click="applyPeriod"
            >
              {{ DEEP_LABELS.periodApply }}
            </button>
            <button
              type="button"
              data-testid="deep-period-all"
              @click="wholePeriod"
            >
              {{ DEEP_LABELS.periodAll }}
            </button>
          </div>
          <p
            v-if="invalidPeriod"
            role="alert"
          >
            {{ DEEP_LABELS.periodInvalid }}
          </p>
          <p
            v-if="applied"
            data-testid="deep-period-snapped"
          >
            {{ DEEP_LABELS.periodSnapped(offset(axis.fromMs - originMs), offset(axis.toMs - originMs)) }}
          </p>
          <p
            data-testid="deep-step-note"
            class="muted"
          >
            {{ DEEP_LABELS.cellStep(stepMs / 1000) }}
          </p>
          <p
            data-testid="deep-load-note"
            class="muted"
          >
            {{ DEEP_LABELS.loadRollup(rollup) }}
          </p>
        </section>
        <section
          class="panel"
          aria-labelledby="deep-chart-title"
        >
          <h2 id="deep-chart-title">
            {{ DEEP_LABELS.chartAria }}
          </h2>
          <DeepCursorChart
            v-if="tracks.length && axis.toMs > axis.fromMs"
            :tracks="tracks"
            :axis="axis"
            :origin-ms="originMs"
          />
          <p v-if="loadOutside">
            {{ DEEP_LABELS.loadOutsideRun }}
          </p>
          <p v-else-if="!load.some((track) => track.points.length) && !loading">
            {{ DEEP_LABELS.loadEmpty }}
          </p>
          <p v-if="truncated !== null">
            {{ DEEP_LABELS.loadTruncated(truncated) }}
          </p>
        </section>
      </div>
      <aside
        v-if="hasSnapshot && grid"
        class="panel"
        aria-labelledby="deep-series-title"
      >
        <h2 id="deep-series-title">
          {{ DEEP_LABELS.seriesTitle }}
        </h2>
        <label class="search-label">{{ DEEP_LABELS.seriesFilter }}<input
          v-model="filter"
          type="search"
          data-testid="deep-series-filter"
        ></label>
        <p data-testid="deep-selected-count">
          {{ DEEP_LABELS.seriesCount(selected.length, MAX_SELECTED_SERIES) }}
        </p>
        <p
          v-if="selected.length >= MAX_SELECTED_SERIES"
          data-testid="deep-limit-hint"
        >
          {{ DEEP_LABELS.limitReached(MAX_SELECTED_SERIES) }}
        </p>
        <p v-if="!catalog.length">
          {{ DEEP_LABELS.seriesEmpty }}
        </p>
        <p v-else-if="!filtered.length">
          {{ DEEP_LABELS.seriesNone }}
        </p>
        <div class="series-list">
          <label
            v-for="entry in filtered"
            :key="entry.id"
            class="series-row"
          >
            <input
              type="checkbox"
              :data-testid="'deep-series-' + entry.id"
              :checked="selected.includes(entry.id)"
              :disabled="!selected.includes(entry.id) && selected.length >= MAX_SELECTED_SERIES"
              @change="toggle(entry.id, ($event.target as HTMLInputElement).checked)"
            >
            <span><strong>{{ entry.id }}</strong><br><span class="muted">{{ DEEP_LABELS.seriesMeta(entry.unit, entry.entity) }}</span><span
              v-if="entry.observed_cells === 0"
              class="muted"
            >, {{ DEEP_LABELS.noValuesBadge }}</span></span>
          </label>
        </div>
      </aside>
    </div>
  </div>
</template>

<style scoped>
.deep-panel,.deep-main { display:grid; gap:16px; min-width:0; }
.deep-layout { display:grid; grid-template-columns:minmax(0,1fr) minmax(240px,300px); gap:16px; min-width:0; }
.deep-layout > *, .deep-main > * { min-width:0; }
.period-fields { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:12px; }
.period-fields label,.search-label { display:grid; gap:4px; min-width:0; }
.period-fields input,.search-label input { width:100%; min-width:0; min-height:44px; }
.period-actions { display:flex; flex-wrap:wrap; gap:8px; margin-top:12px; }
button { min-height:44px; }
.series-list { max-height:520px; overflow:auto; }
.series-row { display:flex; align-items:center; gap:8px; min-height:44px; overflow-wrap:anywhere; }
.series-row input { flex:none; width:20px; height:20px; }
.series-row span { min-width:0; }
@media (max-width:960px) { .deep-layout { grid-template-columns:minmax(0,1fr); } }
@media (max-width:375px) { .period-fields { grid-template-columns:minmax(0,1fr); } }
</style>
