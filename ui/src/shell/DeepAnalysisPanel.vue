<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { ApiError, getBuckets, getResourceSeriesCatalog, getResourceSeriesValues, resourceSeriesValuesPath } from '../api'
import type { AnalysisResult, AnalysisSummary, Bucket, ResourceSeriesCatalog, ResourceSeriesEntry } from '../types'
import { DEEP_LABELS } from './labels'
import { formatNumber, formatOffset } from './overview'
import { armAnalyses } from './platformMap'
import { podViewGroups } from './podView'
import {
  LOAD_CATALOG_ITEMS, MAX_LOAD_PAGES, MAX_SELECTED_SERIES, allLoadTracks, batchSeriesIds,
  chooseLoadRollup, deepStages, defaultSelection, gridEndMs, groupMetricCatalog, loadBucketRange,
  mergePages, planResourcePeriod, resourceTrack, runStartMs, stageButtons, thresholdsFor,
  windowMetricStats, type DeepTrack, type SnapshotGrid, type StageButton, type TimeAxis,
} from './deep'
import DeepCursorChart from './DeepCursorChart.vue'

const props = withDefaults(defineProps<{
  result: AnalysisResult
  runId: string
  analysisId: string
  analyses?: AnalysisSummary[]
}>(), { analyses: () => [] })
const emit = defineEmits<{ 'select-analysis': [analysisId: string] }>()

const origin = runStartMs(props.result)
const originMs = origin ?? 0
const hasSnapshot = ref(origin !== null)
const catalog = ref<ResourceSeriesEntry[]>([])
const catalogWindows = ref<ResourceSeriesCatalog['windows']>([])
const grid = ref<SnapshotGrid | null>(null)
const selected = ref<string[]>([])
const pinnedLoad = ref<string[]>(['load-rps', 'load-p95'])
const primaryKey = ref<string>('load-rps')
const filter = ref('')
const serviceFilter = ref('')
const periodFrom = ref('')
const periodTo = ref('')
const applied = ref<{ from: number; to: number } | null>(null)
const activeStageId = ref<string | null>(null)
const invalidPeriod = ref(false)
const axis = ref<TimeAxis>({ fromMs: originMs, toMs: originMs })
const stepMs = ref(0)
const rollup = ref<1 | 10 | 30 | 60>(60)
const load = ref<DeepTrack[]>([])
const resourcesMap = ref<Map<string, DeepTrack>>(new Map())
const loading = ref(false)
const error = ref('')
const loadOutside = ref(false)
const truncated = ref<number | null>(null)
let revision = 0
let controller: AbortController | null = null
let loadPhaseDone = false

const loadKeySet = new Set(LOAD_CATALOG_ITEMS.map((item) => item.key))
const stageList = computed(() => stageButtons(props.result, catalogWindows.value, originMs))
const stages = computed(() => deepStages(props.result, catalogWindows.value, originMs))
const groupedCatalog = computed(() => groupMetricCatalog(catalog.value, filter.value, serviceFilter.value))
const arms = computed(() => armAnalyses(props.analyses, props.analysisId))
const hasPodView = computed(() => podViewGroups(props.analyses, props.analysisId).length > 0)
const showPeriodPanel = computed(() => (hasSnapshot.value ? Boolean(grid.value) : stageList.value.length > 0 || load.value.length > 0))
const showCatalogPanel = computed(() => (hasSnapshot.value ? Boolean(grid.value) : load.value.length > 0))

const primaryTrack = computed<DeepTrack | null>(() => {
  if (loadKeySet.has(primaryKey.value)) {
    const found = load.value.find((track) => track.key === primaryKey.value)
    if (found) return found
  } else {
    const found = resourcesMap.value.get(primaryKey.value)
    if (found) return found
  }
  const fallbackResource = selected.value.map((id) => resourcesMap.value.get(id)).find(Boolean)
  if (fallbackResource) return fallbackResource
  return load.value.find((track) => pinnedLoad.value.includes(track.key)) ?? load.value[0] ?? null
})

const pinnedTracks = computed<DeepTrack[]>(() => {
  const primary = primaryTrack.value?.key ?? null
  const loadItems = load.value.filter((track) => pinnedLoad.value.includes(track.key) && track.key !== primary)
  const resourceItems = selected.value.flatMap((id) => {
    const track = resourcesMap.value.get(id)
    return track && track.key !== primary ? [track] : []
  })
  return [...loadItems, ...resourceItems]
})

const primaryStats = computed(() => (primaryTrack.value ? windowMetricStats(primaryTrack.value, axis.value) : null))
const statTiles = computed(() => {
  const track = primaryTrack.value
  const stats = primaryStats.value
  if (!track || !stats) return []
  const fmtVal = (val: number | null) => (val === null ? DEEP_LABELS.statNoData : `${formatNumber(val, 2)} ${track.unit}`)
  const fmtRaw = (val: number | null) => (val === null ? '' : String(val))
  const fmtThreshold = () => {
    if (!stats.hasThresholds) return DEEP_LABELS.statNoThreshold
    if (stats.timeAboveThresholdMs === null) return DEEP_LABELS.statNoData
    return stats.timeAboveThresholdMs < 60_000
      ? `${formatNumber(stats.timeAboveThresholdMs / 1000, 0)} с`
      : formatOffset(stats.timeAboveThresholdMs)
  }
  return [
    { key: 'min', label: DEEP_LABELS.statMin, value: fmtVal(stats.min), raw: fmtRaw(stats.min) },
    { key: 'avg', label: DEEP_LABELS.statAvg, value: fmtVal(stats.avg), raw: fmtRaw(stats.avg) },
    { key: 'p50', label: DEEP_LABELS.statP50, value: fmtVal(stats.p50), raw: fmtRaw(stats.p50) },
    { key: 'p95', label: DEEP_LABELS.statP95, value: fmtVal(stats.p95), raw: fmtRaw(stats.p95) },
    { key: 'max', label: DEEP_LABELS.statMax, value: fmtVal(stats.max), raw: fmtRaw(stats.max) },
    { key: 'threshold', label: DEEP_LABELS.statAboveThreshold, value: fmtThreshold(), raw: stats.timeAboveThresholdMs === null ? '' : String(stats.timeAboveThresholdMs) },
  ]
})

const offset = (ms: number) => (ms < 0 ? `-${formatOffset(-ms)}` : formatOffset(ms))

async function fetchBuckets(range: { fromMs: number; toMs: number } | null, chosenRollup: number, signal: AbortSignal) {
  const buckets: Bucket[] = []
  let from: number | undefined = range?.fromMs
  let next: number | null = null
  for (let page = 0; page < MAX_LOAD_PAGES; page += 1) {
    const response = await getBuckets(props.runId, props.analysisId, chosenRollup, from, range?.toMs, signal, 'percentiles')
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
  resourcesMap.value = new Map()
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
            load.value = allLoadTracks(data.buckets, rollup.value, originMs, props.result)
            if (data.next !== null) truncated.value = data.buckets.length
          }
        }
        loadPhaseDone = true
      }
      const primaryIsResource = !loadKeySet.has(primaryKey.value)
      const requested = [...new Set([...selected.value, ...(primaryIsResource ? [primaryKey.value] : [])])]
      const ids = requested.filter((id) => catalog.value.some((entry) => entry.id === id))
      const params = { step_ms: String(plan.stepMs), from_ms: String(plan.axis.fromMs), to_ms: String(plan.axis.toMs) }
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
            if (loadKeySet.has(track.key) || track.key.startsWith('resource:')) track.key = `resource:${track.key}`
            byId.set(series.id, byId.has(series.id) ? mergePages(byId.get(series.id)!, track) : track)
          }
          nextFrom = response.next_from_ms
        }
      }
      if (current === revision) resourcesMap.value = byId
    } else if (includeLoad) {
      loadOutside.value = false
      truncated.value = null
      if (props.result.run_validity === 'INVALID') { load.value = []; return }
      if (applied.value) {
        const fromOffset = Math.max(0, Math.round(applied.value.from * 60_000))
        const toOffset = Math.max(fromOffset + 1000, Math.round(applied.value.to * 60_000))
        const windowAxis: TimeAxis = { fromMs: originMs + fromOffset, toMs: originMs + toOffset }
        rollup.value = chooseLoadRollup(windowAxis.toMs - windowAxis.fromMs)
        const range = loadBucketRange(originMs, windowAxis, rollup.value)
        if (range === null) { loadOutside.value = true; load.value = [] }
        else {
          const data = await fetchBuckets(range, rollup.value, abort.signal)
          if (current !== revision) return
          load.value = allLoadTracks(data.buckets, rollup.value, originMs, props.result)
          if (data.next !== null) truncated.value = data.buckets.length
        }
        axis.value = windowAxis
      } else {
        const probe = await getBuckets(props.runId, props.analysisId, 60, 0, undefined, abort.signal, 'percentiles')
        if (current !== revision) return
        const span = probe.next_from_ms === null ? (probe.buckets.at(-1)?.bucket_start_ms ?? -60000) + 60000 : Infinity
        rollup.value = chooseLoadRollup(span)
        const data = rollup.value === 60
          ? await (async () => {
            const buckets = [...probe.buckets]
            let next = probe.next_from_ms
            for (let page = 1; page < MAX_LOAD_PAGES && next !== null; page += 1) {
              const response = await getBuckets(props.runId, props.analysisId, 60, next, undefined, abort.signal, 'percentiles')
              buckets.push(...response.buckets)
              next = response.next_from_ms
            }
            return { buckets, next }
          })()
          : await fetchBuckets(null, rollup.value, abort.signal)
        if (current !== revision) return
        load.value = allLoadTracks(data.buckets, rollup.value, originMs, props.result)
        axis.value = { fromMs: originMs, toMs: originMs + (data.buckets.length ? data.buckets[data.buckets.length - 1].bucket_start_ms + rollup.value * 1000 : 0) }
        if (data.next !== null) truncated.value = data.buckets.length
      }
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
    let windows: ResourceSeriesCatalog['windows'] = []
    do {
      const response = await getResourceSeriesCatalog(props.runId, props.analysisId, after ?? undefined, controller.signal)
      if (current !== revision) return
      if (!snapshotGrid) {
        snapshotGrid = { startMs: response.grid.start_epoch_ms, stepMs: response.grid.step_ms, pointCount: response.grid.point_count }
        windows = response.windows ?? []
      }
      for (const entry of response.series) if (!seen.has(entry.id)) { seen.add(entry.id); entries.push(entry) }
      after = response.next_after
    } while (after !== null)
    grid.value = snapshotGrid
    catalogWindows.value = windows
    catalog.value = entries
    selected.value = defaultSelection(entries, props.result)
    if (props.result.run_validity === 'INVALID' && selected.value.length) primaryKey.value = selected.value[0]
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
function toggleLoadPin(key: string, checked: boolean) {
  if (checked) { if (!pinnedLoad.value.includes(key)) pinnedLoad.value = [...pinnedLoad.value, key] }
  else pinnedLoad.value = pinnedLoad.value.filter((item) => item !== key)
}
function selectPrimaryMetric(key: string) {
  primaryKey.value = key
  const needsFetch = !loadKeySet.has(key) && !resourcesMap.value.has(key) && hasSnapshot.value && grid.value !== null
  if (needsFetch && loadPhaseDone) void refresh(false)
}
function selectStage(stage: StageButton) {
  const fmt = (min: number) => String(Math.round(min * 1000) / 1000)
  periodFrom.value = fmt(Math.max(0, stage.fromMin))
  periodTo.value = fmt(Math.max(0, stage.toMin))
  applied.value = { from: stage.fromMin, to: stage.toMin }
  activeStageId.value = stage.id
  invalidPeriod.value = false
  void refresh(true)
}
function applyPeriod() {
  const from = Number(periodFrom.value)
  const to = Number(periodTo.value)
  invalidPeriod.value = !periodFrom.value || !periodTo.value || !Number.isFinite(from) || !Number.isFinite(to) || from < 0 || to < 0 || from >= to
  if (invalidPeriod.value) return
  applied.value = { from, to }
  activeStageId.value = null
  void refresh(true)
}
function wholePeriod() {
  periodFrom.value = ''
  periodTo.value = ''
  applied.value = null
  activeStageId.value = null
  invalidPeriod.value = false
  void refresh(true)
}
function jumpToPods() {
  const el = document.getElementById('pod-heatmap')
  el?.scrollIntoView()
  document.getElementById('pod-heatmap-title')?.focus?.()
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
      <div class="deep-header-row">
        <h2
          id="deep-title"
          tabindex="-1"
        >
          {{ DEEP_LABELS.title }}
        </h2>
        <div class="deep-nav-actions">
          <div
            v-if="arms.length > 1"
            class="arm-switcher"
            data-testid="deep-arm-switcher"
            role="group"
            :aria-label="DEEP_LABELS.armTitle"
          >
            <button
              v-for="arm in arms"
              :key="arm.arm"
              type="button"
              class="stage-btn"
              :data-testid="'deep-arm-' + arm.arm"
              :aria-pressed="arm.analysisId === analysisId ? 'true' : 'false'"
              @click="emit('select-analysis', arm.analysisId)"
            >
              {{ DEEP_LABELS.armButton(arm.arm) }}
            </button>
          </div>
          <button
            v-if="hasPodView"
            type="button"
            class="stage-btn"
            data-testid="deep-pod-jump"
            @click="jumpToPods"
          >
            {{ DEEP_LABELS.podJump }}
          </button>
        </div>
      </div>
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
          v-if="showPeriodPanel"
          class="panel"
          aria-labelledby="deep-period-title"
        >
          <h2 id="deep-period-title">
            {{ DEEP_LABELS.periodTitle }}
          </h2>
          <div
            v-if="stageList.length"
            class="stage-bar"
            data-testid="deep-stage-bar"
            role="group"
            :aria-label="DEEP_LABELS.stagesTitle"
          >
            <button
              type="button"
              class="stage-btn"
              data-testid="deep-stage-all"
              :aria-pressed="applied === null ? 'true' : 'false'"
              @click="wholePeriod"
            >
              {{ DEEP_LABELS.periodAll }}
            </button>
            <button
              v-for="stage in stageList"
              :key="stage.id"
              type="button"
              class="stage-btn"
              :data-testid="'deep-stage-' + stage.id"
              :aria-pressed="activeStageId === stage.id ? 'true' : 'false'"
              @click="selectStage(stage)"
            >
              {{ stage.id }}
            </button>
          </div>
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
            v-if="hasSnapshot && grid"
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
            v-if="(primaryTrack || pinnedTracks.length) && axis.toMs > axis.fromMs"
            :primary-track="primaryTrack"
            :tracks="pinnedTracks"
            :axis="axis"
            :origin-ms="originMs"
            :stages="stages"
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
        <section
          v-if="primaryTrack && statTiles.length"
          class="panel"
          aria-labelledby="deep-stats-title"
          data-testid="deep-stats"
        >
          <h2 id="deep-stats-title">
            {{ DEEP_LABELS.statsTitle }}: {{ primaryTrack.label }}
          </h2>
          <dl
            class="deep-stat-grid"
            data-testid="deep-stat-tiles"
          >
            <div
              v-for="tile in statTiles"
              :key="tile.key"
              class="deep-stat-tile"
              data-testid="deep-stat-tile"
              :data-stat="tile.key"
              :data-value="tile.raw"
            >
              <dt>{{ tile.label }}</dt>
              <dd>{{ tile.value }}</dd>
            </div>
          </dl>
        </section>
      </div>
      <aside
        v-if="showCatalogPanel"
        class="panel"
        aria-labelledby="deep-series-title"
        data-testid="deep-catalog"
      >
        <h2 id="deep-series-title">
          {{ DEEP_LABELS.catalogTitle }}
        </h2>
        <label class="search-label">{{ DEEP_LABELS.seriesFilter }}<input
          v-model="filter"
          type="search"
          data-testid="deep-series-filter"
        ></label>
        <label
          v-if="hasSnapshot && groupedCatalog.services.length"
          class="search-label"
        >{{ DEEP_LABELS.serviceFilter }}<select
          v-model="serviceFilter"
          data-testid="deep-service-filter"
        >
          <option value="">{{ DEEP_LABELS.serviceAll }}</option>
          <option
            v-for="svc in groupedCatalog.services"
            :key="svc"
            :value="svc"
          >{{ svc }}</option>
        </select></label>

        <div
          v-for="group in groupedCatalog.groups.filter((g) => g.key !== 'platform' && g.loadItems.length)"
          :key="group.key"
          class="catalog-group"
          :data-testid="'deep-group-' + group.key"
        >
          <h3>{{ group.label }}</h3>
          <div class="catalog-rows">
            <div
              v-for="item in group.loadItems"
              :key="item.key"
              class="series-row"
            >
              <input
                type="checkbox"
                :data-testid="'deep-pin-' + item.key"
                :aria-label="`${DEEP_LABELS.pinToggle}: ${item.label}`"
                :checked="pinnedLoad.includes(item.key)"
                @change="toggleLoadPin(item.key, ($event.target as HTMLInputElement).checked)"
              >
              <button
                type="button"
                class="metric-select-btn"
                :data-testid="'deep-metric-select-' + item.key"
                :aria-pressed="primaryKey === item.key ? 'true' : 'false'"
                @click="selectPrimaryMetric(item.key)"
              >
                <span>{{ item.label }}</span>
                <span class="muted"> ({{ item.unit }})</span>
              </button>
            </div>
          </div>
        </div>

        <div
          v-if="hasSnapshot && grid"
          class="catalog-group"
          data-testid="deep-group-platform"
        >
          <h3>{{ DEEP_LABELS.groupLabels.platform }}</h3>
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
          <p v-else-if="!groupedCatalog.platformByRole.system.length && !groupedCatalog.platformByRole.generator.length">
            {{ DEEP_LABELS.seriesNone }}
          </p>
          <div class="series-list">
            <template
              v-for="roleKey in (['system', 'generator'] as const)"
              :key="roleKey"
            >
              <div
                v-if="groupedCatalog.platformByRole[roleKey].length"
                class="role-subgroup"
                :data-testid="'deep-role-' + roleKey"
              >
                <p class="role-heading muted">
                  {{ DEEP_LABELS.roleLabels[roleKey] }}
                </p>
                <div
                  v-for="entry in groupedCatalog.platformByRole[roleKey]"
                  :key="entry.id"
                  class="series-row"
                >
                  <input
                    type="checkbox"
                    :data-testid="'deep-series-' + entry.id"
                    :aria-label="`${DEEP_LABELS.pinToggle}: ${entry.id}`"
                    :checked="selected.includes(entry.id)"
                    :disabled="!selected.includes(entry.id) && selected.length >= MAX_SELECTED_SERIES"
                    @change="toggle(entry.id, ($event.target as HTMLInputElement).checked)"
                  >
                  <button
                    type="button"
                    class="metric-select-btn"
                    :data-testid="'deep-metric-select-' + entry.id"
                    :aria-pressed="primaryKey === entry.id ? 'true' : 'false'"
                    @click="selectPrimaryMetric(entry.id)"
                  >
                    <strong>{{ entry.id }}</strong>
                    <br>
                    <span class="muted">{{ DEEP_LABELS.seriesMeta(entry.unit, entry.entity) }}</span>
                    <span
                      v-if="entry.observed_cells === 0"
                      class="muted"
                    >, {{ DEEP_LABELS.noValuesBadge }}</span>
                  </button>
                </div>
              </div>
            </template>
          </div>
        </div>
      </aside>
    </div>
  </div>
</template>

<style scoped>
.deep-panel,.deep-main { display:grid; gap:16px; min-width:0; }
.deep-layout { display:grid; grid-template-columns:minmax(0,1fr) minmax(240px,320px); gap:16px; min-width:0; }
.deep-layout > *, .deep-main > * { min-width:0; }
.deep-header-row { display:flex; flex-wrap:wrap; align-items:center; justify-content:space-between; gap:12px; }
.deep-header-row h2 { margin:0; }
.deep-nav-actions,.arm-switcher,.stage-bar { display:flex; flex-wrap:wrap; gap:8px; }
.stage-bar { margin-bottom:12px; }
.stage-btn { min-height:44px; min-width:44px; padding:8px 12px; overflow-wrap:anywhere; }
.stage-btn[aria-pressed="true"],.metric-select-btn[aria-pressed="true"] { border-color:var(--brand); background:var(--info-bg); color:var(--brand); font-weight:600; }
.period-fields { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:12px; }
.period-fields label,.search-label { display:grid; gap:4px; min-width:0; }
.period-fields input,.search-label input,.search-label select { width:100%; min-width:0; min-height:44px; }
.period-actions { display:flex; flex-wrap:wrap; gap:8px; margin-top:12px; }
button { min-height:44px; }
.deep-stat-grid { display:grid; grid-template-columns:repeat(auto-fit,minmax(130px,1fr)); gap:12px; margin:0; }
.deep-stat-tile { display:grid; gap:4px; padding:8px 10px; border:1px solid var(--border); border-radius:6px; background:var(--surface-inset); min-width:0; overflow-wrap:anywhere; }
.deep-stat-tile dt { color:var(--text-muted); font-size:13px; }
.deep-stat-tile dd { margin:0; font-size:1.1rem; font-weight:700; }
.catalog-group { display:grid; gap:6px; margin-top:12px; min-width:0; }
.catalog-group h3 { margin:0; font-size:14px; }
.role-heading { margin:4px 0 2px; font-size:12px; font-weight:600; }
.catalog-rows { display:grid; gap:4px; }
.series-list { max-height:420px; overflow:auto; display:grid; gap:6px; }
.series-row { display:flex; align-items:center; gap:8px; min-height:44px; min-width:0; }
.series-row input { flex:none; width:20px; height:20px; }
.metric-select-btn { flex:1; min-width:0; min-height:44px; padding:6px 8px; text-align:left; background:var(--surface-inset); border:1px solid var(--border); border-radius:4px; color:var(--text); overflow-wrap:anywhere; }
@media (max-width:960px) { .deep-layout { grid-template-columns:minmax(0,1fr); } }
@media (max-width:375px) { .period-fields { grid-template-columns:minmax(0,1fr); } }
</style>
