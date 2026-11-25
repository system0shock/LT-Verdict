<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
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
  focus?: { stageId?: string; seriesId?: string; nonce: number } | null
}>(), { analyses: () => [], focus: null })
const emit = defineEmits<{
  'select-analysis': [analysisId: string]
  'open-overview': []
}>()

const origin = runStartMs(props.result)
const originMs = origin ?? 0
const hasSnapshot = ref(origin !== null)
const catalog = ref<ResourceSeriesEntry[]>([])
const catalogWindows = ref<ResourceSeriesCatalog['windows']>([])
const grid = ref<SnapshotGrid | null>(null)
const selected = ref<string[]>([])
const pinnedLoad = ref<string[]>(['load-rps', 'load-p95'])
const primaryKey = ref<string>('load-rps')
const deepMode = ref<'workbench' | 'focus'>('workbench')
const rubDomain = ref<'all' | 'load' | 'platform'>('all')
const catalogGrouping = ref<'role' | 'service'>('role')
const onlyDeviations = ref(false)
const openServices = ref<Record<string, boolean>>({})
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
const laneOrder = ref<string[]>([])
let revision = 0
let controller: AbortController | null = null
let loadPhaseDone = false

const loadKeySet = new Set(LOAD_CATALOG_ITEMS.map((item) => item.key))
const stageList = computed(() => stageButtons(props.result, catalogWindows.value, originMs))
const stages = computed(() => deepStages(props.result, catalogWindows.value, originMs))
const groupedCatalog = computed(() => groupMetricCatalog(catalog.value, filter.value, serviceFilter.value))

const violatedSeriesIds = computed(() => {
  const ids = new Set<string>()
  for (const item of props.result.evidence) {
    if (item.type === 'resource_policy_check' && item.status === 'FAIL') ids.add(item.series_id)
    if (item.type === 'trend_check' && item.status === 'TREND_OBSERVED') ids.add(item.series_id)
  }
  return ids
})

const filteredPlatformByRole = computed(() => {
  const filterList = (list: ResourceSeriesEntry[]) => (
    onlyDeviations.value ? list.filter((entry) => violatedSeriesIds.value.has(entry.id)) : list
  )
  return {
    system: filterList(groupedCatalog.value.platformByRole.system),
    generator: filterList(groupedCatalog.value.platformByRole.generator),
  }
})

const filteredPlatformByService = computed(() => {
  const map = new Map<string, { entries: ResourceSeriesEntry[]; badCount: number }>()
  for (const entry of [...filteredPlatformByRole.value.system, ...filteredPlatformByRole.value.generator]) {
    const svc = entry.entity || 'Без сервиса'
    const bucket = map.get(svc) ?? { entries: [], badCount: 0 }
    bucket.entries.push(entry)
    if (violatedSeriesIds.value.has(entry.id)) bucket.badCount += 1
    map.set(svc, bucket)
  }
  return [...map.entries()].sort((a, b) => {
    if (b[1].badCount !== a[1].badCount) return b[1].badCount - a[1].badCount
    return a[0].localeCompare(b[0])
  })
})

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

const workbenchTracks = computed<DeepTrack[]>(() => {
  const map = new Map<string, DeepTrack>()
  const loadByKey = new Map(load.value.map((track) => [track.key, track]))
  for (const key of pinnedLoad.value) {
    const track = loadByKey.get(key)
    if (track) map.set(track.key, track)
  }
  for (const id of selected.value) {
    const track = resourcesMap.value.get(id)
    if (track) map.set(track.key, track)
  }
  if (!map.size && primaryTrack.value) map.set(primaryTrack.value.key, primaryTrack.value)
  const ordered: DeepTrack[] = []
  for (const key of laneOrder.value) {
    const t = map.get(key)
    if (t) { ordered.push(t); map.delete(key) }
  }
  for (const t of map.values()) ordered.push(t)
  return ordered
})

const activeTrackCount = computed(() => (
  deepMode.value === 'workbench'
    ? workbenchTracks.value.length
    : (primaryTrack.value ? 1 : 0) + pinnedTracks.value.length
))

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

function isServiceOpen(svcName: string, idx: number): boolean {
  if (filter.value.trim().length > 0) return true
  if (svcName in openServices.value) return openServices.value[svcName]
  return idx < 3
}

function toggleServiceGroup(svcName: string, idx: number) {
  openServices.value = { ...openServices.value, [svcName]: !isServiceOpen(svcName, idx) }
}

function moveTrack(key: string, dir: -1 | 1) {
  const keys = workbenchTracks.value.map((t) => t.key)
  const idx = keys.indexOf(key)
  const next = idx + dir
  if (idx < 0 || next < 0 || next >= keys.length) return
  ;[keys[idx], keys[next]] = [keys[next], keys[idx]]
  laneOrder.value = keys
}

function removeTrack(key: string) {
  if (loadKeySet.has(key)) {
    pinnedLoad.value = pinnedLoad.value.filter((k) => k !== key)
  } else {
    const rawId = key.startsWith('resource:') ? key.slice('resource:'.length) : key
    selected.value = selected.value.filter((id) => id !== rawId && id !== key)
  }
}

function applyPreset(kind: 'problem' | 'loadOnly' | 'single') {
  if (kind === 'problem') {
    pinnedLoad.value = ['load-rps', 'load-p95']
    selected.value = defaultSelection(catalog.value, props.result)
    laneOrder.value = []
    if (loadPhaseDone && hasSnapshot.value) void refresh(false)
  } else if (kind === 'loadOnly') {
    pinnedLoad.value = ['load-rps', 'load-p95', 'load-errors']
    selected.value = []
    laneOrder.value = []
  } else {
    const first = workbenchTracks.value[0]?.key ?? 'load-rps'
    if (loadKeySet.has(first)) {
      pinnedLoad.value = [first]
      selected.value = []
    } else {
      const rawId = first.startsWith('resource:') ? first.slice('resource:'.length) : first
      pinnedLoad.value = []
      selected.value = [rawId]
    }
    laneOrder.value = [first]
  }
}

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

function applyFocus(target: { stageId?: string; seriesId?: string } | null | undefined) {
  if (!target) return
  if (target.stageId) {
    const stage = stageList.value.find((item) => item.id === target.stageId)
    if (stage) {
      const fmt = (min: number) => String(Math.round(min * 1000) / 1000)
      periodFrom.value = fmt(Math.max(0, stage.fromMin))
      periodTo.value = fmt(Math.max(0, stage.toMin))
      applied.value = { from: stage.fromMin, to: stage.toMin }
      activeStageId.value = stage.id
      invalidPeriod.value = false
    }
  }
  if (target.seriesId && catalog.value.some((entry) => entry.id === target.seriesId)) {
    if (!selected.value.includes(target.seriesId)) {
      selected.value = selected.value.length >= MAX_SELECTED_SERIES
        ? [...selected.value.slice(0, MAX_SELECTED_SERIES - 1), target.seriesId]
        : [...selected.value, target.seriesId]
    }
    primaryKey.value = target.seriesId
  }
}

async function initialize() {
  if (origin === null) {
    applyFocus(props.focus)
    await refresh(true)
    return
  }
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
    const distinctEntities = new Set(entries.map((e) => e.entity).filter(Boolean))
    if (distinctEntities.size > 1) catalogGrouping.value = 'service'
    selected.value = defaultSelection(entries, props.result)
    if (props.result.run_validity === 'INVALID' && selected.value.length) primaryKey.value = selected.value[0]
    applyFocus(props.focus)
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
  if (deepMode.value === 'workbench') {
    if (loadKeySet.has(key) && !pinnedLoad.value.includes(key)) pinnedLoad.value = [...pinnedLoad.value, key]
    else if (!loadKeySet.has(key) && !selected.value.includes(key) && selected.value.length < MAX_SELECTED_SERIES) {
      selected.value = [...selected.value, key]
    }
  }
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
watch(() => props.focus?.nonce, () => {
  if (!loadPhaseDone || !props.focus) return
  applyFocus(props.focus)
  void refresh(true)
})
onMounted(() => { void initialize() })
onUnmounted(() => { revision += 1; controller?.abort() })
</script>

<template>
  <div
    class="deep-panel"
    data-testid="deep-panel"
    lang="ru"
  >
    <div class="deep-top-bar">
      <div class="deep-top-bar__copy">
        <h2
          id="deep-title"
          tabindex="-1"
        >
          {{ DEEP_LABELS.title }}
        </h2>
        <p class="v4-sub">
          Слева — фасетный рубрикатор метрик и сервисов. Справа — свободная стопка дорожек или фокус на одном ряде.
        </p>
      </div>
      <div class="deep-nav-actions">
        <div
          class="v4-seg"
          role="group"
          aria-label="Режим глубокого анализа"
        >
          <button
            type="button"
            data-testid="deep-mode-workbench"
            :aria-pressed="deepMode === 'workbench' ? 'true' : 'false'"
            @click="deepMode = 'workbench'"
          >
            Свободное сравнение (стопка)
          </button>
          <button
            type="button"
            data-testid="deep-mode-focus"
            :aria-pressed="deepMode === 'focus' ? 'true' : 'false'"
            @click="deepMode = 'focus'"
          >
            Фокус на одном ряде
          </button>
        </div>
        <div
          v-if="arms.length > 1"
          class="v4-seg"
          data-testid="deep-arm-switcher"
          role="group"
          :aria-label="DEEP_LABELS.armTitle"
        >
          <button
            v-for="arm in arms"
            :key="arm.arm"
            type="button"
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
          class="v4-btn"
          data-testid="deep-pod-jump"
          @click="jumpToPods"
        >
          {{ DEEP_LABELS.podJump }}
        </button>
        <button
          type="button"
          class="v4-btn"
          @click="emit('open-overview')"
        >
          ← В Обзор
        </button>
      </div>
    </div>

    <div
      v-if="!hasSnapshot || loading || error"
      class="deep-banners"
    >
      <p
        v-if="!hasSnapshot"
        class="v4-sub"
        data-testid="deep-no-snapshot"
      >
        {{ DEEP_LABELS.noSnapshot }}
      </p>
      <p
        v-if="loading"
        class="v4-sub"
        role="status"
      >
        {{ DEEP_LABELS.loading }}
      </p>
      <p
        v-if="error"
        class="notice notice-fail"
        role="alert"
        data-testid="deep-error"
      >
        {{ error }}
      </p>
    </div>

    <div class="deep-layout">
      <!-- LEFT: Faceted Scalable Rubricator (Mockup v4.4) -->
      <aside
        v-if="showCatalogPanel"
        class="panel rubricator-panel"
        aria-labelledby="deep-series-title"
        data-testid="deep-catalog"
      >
        <div class="rub-head">
          <h3 id="deep-series-title">
            {{ DEEP_LABELS.catalogTitle }}
          </h3>
          <span
            v-if="hasSnapshot && grid"
            class="v4-sub v4-num"
            data-testid="deep-selected-count"
          >{{ DEEP_LABELS.seriesCount(selected.length, MAX_SELECTED_SERIES) }}</span>
        </div>

        <div
          v-if="hasSnapshot && grid"
          class="v4-seg rub-domain-seg"
          role="group"
          aria-label="Домен метрик"
        >
          <button
            type="button"
            :aria-pressed="rubDomain === 'all'"
            @click="rubDomain = 'all'"
          >
            Все
          </button>
          <button
            type="button"
            :aria-pressed="rubDomain === 'load'"
            @click="rubDomain = 'load'"
          >
            Нагрузка ({{ LOAD_CATALOG_ITEMS.length }})
          </button>
          <button
            type="button"
            :aria-pressed="rubDomain === 'platform'"
            @click="rubDomain = 'platform'"
          >
            Платформа ({{ catalog.length }})
          </button>
        </div>

        <div
          v-if="hasSnapshot && grid && rubDomain !== 'load'"
          class="rub-subbar"
        >
          <div
            class="v4-seg"
            role="group"
            aria-label="Группировка каталога платформы"
          >
            <button
              type="button"
              data-testid="deep-group-by-service"
              :aria-pressed="catalogGrouping === 'service' ? 'true' : 'false'"
              @click="catalogGrouping = 'service'"
            >
              По сервисам
            </button>
            <button
              type="button"
              data-testid="deep-group-by-role"
              :aria-pressed="catalogGrouping === 'role' ? 'true' : 'false'"
              @click="catalogGrouping = 'role'"
            >
              По ролям
            </button>
          </div>
          <select
            v-if="groupedCatalog.services.length > 1"
            v-model="serviceFilter"
            class="v4-inp rub-svc-select"
            data-testid="deep-service-filter"
            :aria-label="DEEP_LABELS.serviceFilter"
          >
            <option value="">
              {{ DEEP_LABELS.serviceAll }} ({{ groupedCatalog.services.length }})
            </option>
            <option
              v-for="svc in groupedCatalog.services"
              :key="svc"
              :value="svc"
            >
              {{ svc }}
            </option>
          </select>
        </div>

        <input
          v-model="filter"
          type="search"
          class="v4-inp"
          data-testid="deep-series-filter"
          placeholder="Быстрый поиск ряда / сервиса…"
          :aria-label="DEEP_LABELS.seriesFilter"
        >

        <label
          v-if="hasSnapshot && grid && violatedSeriesIds.size > 0"
          class="v4-check"
        >
          <input
            v-model="onlyDeviations"
            type="checkbox"
            data-testid="deep-only-deviations"
          >
          Показывать только с отклонениями ({{ violatedSeriesIds.size }})
        </label>

        <p
          v-if="hasSnapshot && grid && selected.length >= MAX_SELECTED_SERIES"
          class="v4-sub rub-limit-hint"
          data-testid="deep-limit-hint"
        >
          {{ DEEP_LABELS.limitReached(MAX_SELECTED_SERIES) }}
        </p>

        <div class="rub-scroll">
          <!-- Load metric groups -->
          <template v-if="rubDomain !== 'platform'">
            <div
              v-for="group in groupedCatalog.groups.filter((g) => g.key !== 'platform' && g.loadItems.length)"
              :key="group.key"
              class="rub-group"
              :data-testid="'deep-group-' + group.key"
            >
              <div class="rub-group__hd rub-group__hd--static">
                <span>{{ group.label }}</span>
                <span class="v4-sub v4-num">{{ group.loadItems.length }}</span>
              </div>
              <div class="rub-items">
                <div
                  v-for="item in group.loadItems"
                  :key="item.key"
                  class="rub-row"
                  :class="{ 'in-stack': pinnedLoad.includes(item.key), 'is-primary': deepMode === 'focus' && primaryKey === item.key }"
                >
                  <button
                    type="button"
                    class="rub-pick"
                    :data-testid="'deep-metric-select-' + item.key"
                    :aria-pressed="primaryKey === item.key ? 'true' : 'false'"
                    :title="item.label"
                    @click="selectPrimaryMetric(item.key)"
                  >
                    {{ item.label }}
                  </button>
                  <span class="v4-sub v4-num">{{ item.unit }}</span>
                  <label
                    class="add-lane-toggle"
                    :class="{ on: pinnedLoad.includes(item.key) }"
                    :title="pinnedLoad.includes(item.key) ? 'Убрать дорожку' : 'Добавить дорожку'"
                  >
                    <input
                      type="checkbox"
                      class="sr-check"
                      :data-testid="'deep-pin-' + item.key"
                      :aria-label="`${DEEP_LABELS.pinToggle}: ${item.label}`"
                      :checked="pinnedLoad.includes(item.key)"
                      @change="toggleLoadPin(item.key, ($event.target as HTMLInputElement).checked)"
                    >
                    <span aria-hidden="true">{{ pinnedLoad.includes(item.key) ? '✓' : '+' }}</span>
                  </label>
                </div>
              </div>
            </div>
          </template>

          <!-- Platform series groups -->
          <div
            v-if="hasSnapshot && grid && rubDomain !== 'load'"
            class="rub-platform-wrap"
            data-testid="deep-group-platform"
          >
            <p
              v-if="!catalog.length"
              class="v4-sub"
            >
              {{ DEEP_LABELS.seriesEmpty }}
            </p>
            <p
              v-else-if="!filteredPlatformByRole.system.length && !filteredPlatformByRole.generator.length"
              class="v4-sub"
            >
              {{ DEEP_LABELS.seriesNone }}
            </p>

            <!-- Grouped by Service (collapsible accordions — Mockup v4.4) -->
            <template v-else-if="catalogGrouping === 'service'">
              <div
                class="rub-service-groups"
                data-testid="deep-service-groups"
              >
                <div
                  v-for="([svcName, group], idx) in filteredPlatformByService"
                  :key="svcName"
                  class="rub-group"
                >
                  <button
                    type="button"
                    class="rub-group__hd"
                    :aria-expanded="isServiceOpen(svcName, idx)"
                    @click="toggleServiceGroup(svcName, idx)"
                  >
                    <span class="rub-group__name">{{ isServiceOpen(svcName, idx) ? '▾' : '▸' }} {{ svcName }}</span>
                    <span class="rub-group__meta">
                      <span
                        v-if="group.badCount > 0"
                        class="v4-pill v4-pill--crit"
                      >откл.</span>
                      <span
                        v-else
                        class="v4-sub v4-num"
                      >{{ group.entries.length }}</span>
                    </span>
                  </button>
                  <div
                    v-if="isServiceOpen(svcName, idx)"
                    class="rub-items"
                  >
                    <div
                      v-for="entry in group.entries"
                      :key="entry.id"
                      class="rub-row"
                      :class="{ 'in-stack': selected.includes(entry.id), 'is-primary': deepMode === 'focus' && primaryKey === entry.id }"
                    >
                      <button
                        type="button"
                        class="rub-pick"
                        :data-testid="'deep-metric-select-' + entry.id"
                        :aria-pressed="primaryKey === entry.id ? 'true' : 'false'"
                        :title="entry.id"
                        @click="selectPrimaryMetric(entry.id)"
                      >
                        <span
                          v-if="violatedSeriesIds.has(entry.id)"
                          class="rub-dot-crit"
                          aria-hidden="true"
                        >●</span>
                        {{ entry.metric }}
                      </button>
                      <span
                        class="v4-sub v4-num"
                        :class="{ 'rub-meta-crit': violatedSeriesIds.has(entry.id) }"
                      >{{ entry.observed_cells === 0 ? 'нет' : entry.unit }}</span>
                      <label
                        class="add-lane-toggle"
                        :class="{ on: selected.includes(entry.id), disabled: !selected.includes(entry.id) && selected.length >= MAX_SELECTED_SERIES }"
                        :title="selected.includes(entry.id) ? 'Убрать дорожку' : 'Добавить дорожку'"
                      >
                        <input
                          type="checkbox"
                          class="sr-check"
                          :data-testid="'deep-series-' + entry.id"
                          :aria-label="`${DEEP_LABELS.pinToggle}: ${entry.id}`"
                          :checked="selected.includes(entry.id)"
                          :disabled="!selected.includes(entry.id) && selected.length >= MAX_SELECTED_SERIES"
                          @change="toggle(entry.id, ($event.target as HTMLInputElement).checked)"
                        >
                        <span aria-hidden="true">{{ selected.includes(entry.id) ? '✓' : '+' }}</span>
                      </label>
                    </div>
                  </div>
                </div>
              </div>
            </template>

            <!-- Grouped by Role (system / generator) -->
            <template v-else>
              <template
                v-for="roleKey in (['system', 'generator'] as const)"
                :key="roleKey"
              >
                <div
                  v-if="filteredPlatformByRole[roleKey].length"
                  class="rub-group"
                  :data-testid="'deep-role-' + roleKey"
                >
                  <div class="rub-group__hd rub-group__hd--static">
                    <span>{{ DEEP_LABELS.roleLabels[roleKey] }}</span>
                    <span class="v4-sub v4-num">{{ filteredPlatformByRole[roleKey].length }}</span>
                  </div>
                  <div class="rub-items">
                    <div
                      v-for="entry in filteredPlatformByRole[roleKey]"
                      :key="entry.id"
                      class="rub-row"
                      :class="{ 'in-stack': selected.includes(entry.id), 'is-primary': deepMode === 'focus' && primaryKey === entry.id }"
                    >
                      <button
                        type="button"
                        class="rub-pick"
                        :data-testid="'deep-metric-select-' + entry.id"
                        :aria-pressed="primaryKey === entry.id ? 'true' : 'false'"
                        :title="entry.id"
                        @click="selectPrimaryMetric(entry.id)"
                      >
                        <span
                          v-if="violatedSeriesIds.has(entry.id)"
                          class="rub-dot-crit"
                          aria-hidden="true"
                        >●</span>
                        {{ entry.id }}
                      </button>
                      <span
                        class="v4-sub v4-num"
                        :class="{ 'rub-meta-crit': violatedSeriesIds.has(entry.id) }"
                      >{{ entry.observed_cells === 0 ? DEEP_LABELS.noValuesBadge : entry.unit }}</span>
                      <label
                        class="add-lane-toggle"
                        :class="{ on: selected.includes(entry.id), disabled: !selected.includes(entry.id) && selected.length >= MAX_SELECTED_SERIES }"
                        :title="selected.includes(entry.id) ? 'Убрать дорожку' : 'Добавить дорожку'"
                      >
                        <input
                          type="checkbox"
                          class="sr-check"
                          :data-testid="'deep-series-' + entry.id"
                          :aria-label="`${DEEP_LABELS.pinToggle}: ${entry.id}`"
                          :checked="selected.includes(entry.id)"
                          :disabled="!selected.includes(entry.id) && selected.length >= MAX_SELECTED_SERIES"
                          @change="toggle(entry.id, ($event.target as HTMLInputElement).checked)"
                        >
                        <span aria-hidden="true">{{ selected.includes(entry.id) ? '✓' : '+' }}</span>
                      </label>
                    </div>
                  </div>
                </div>
              </template>
            </template>
          </div>
        </div>
      </aside>

      <!-- RIGHT: Workbench / Focus Charts Card (Mockup v4.4) -->
      <div class="deep-main">
        <section
          class="panel deep-workbench-card"
          aria-labelledby="deep-chart-title"
        >
          <div class="deep-card-hd">
            <div>
              <h3 id="deep-chart-title">
                <template v-if="deepMode === 'workbench'">
                  Свободное сопоставление рядов ({{ activeTrackCount }} из {{ MAX_SELECTED_SERIES + pinnedLoad.length }})
                </template>
                <template v-else>
                  {{ primaryTrack ? primaryTrack.label : DEEP_LABELS.chartAria }}
                </template>
              </h3>
              <span class="v4-sub">
                {{ hasSnapshot ? DEEP_LABELS.clockNote : DEEP_LABELS.readNote }}
              </span>
            </div>
            <div class="deep-presets">
              <button
                type="button"
                class="v4-btn v4-btn--xs"
                @click="applyPreset('problem')"
              >
                Пакет проблемных
              </button>
              <button
                type="button"
                class="v4-btn v4-btn--xs"
                @click="applyPreset('loadOnly')"
              >
                Только нагрузка
              </button>
              <button
                type="button"
                class="v4-btn v4-btn--xs"
                @click="applyPreset('single')"
              >
                Оставить 1 ряд
              </button>
            </div>
          </div>

          <!-- Inline proportional stage strip + compact 1-line period bar -->
          <div
            v-if="showPeriodPanel"
            class="deep-period-strip"
            aria-labelledby="deep-period-title"
          >
            <span
              id="deep-period-title"
              class="v4-kicker"
            >{{ DEEP_LABELS.periodTitle }}</span>
            <div
              v-if="stageList.length"
              class="stages-bar"
              data-testid="deep-stage-bar"
              role="group"
              :aria-label="DEEP_LABELS.stagesTitle"
            >
              <button
                type="button"
                class="stg-btn stg-btn--fixed"
                data-testid="deep-stage-all"
                :aria-pressed="applied === null ? 'true' : 'false'"
                @click="wholePeriod"
              >
                <b>{{ DEEP_LABELS.periodAll }}</b>
                <span>весь период</span>
              </button>
              <button
                v-for="stage in stageList"
                :key="stage.id"
                type="button"
                class="stg-btn"
                :class="{ 'stg-btn--steady': stage.steady }"
                :data-testid="'deep-stage-' + stage.id"
                :aria-pressed="activeStageId === stage.id ? 'true' : 'false'"
                @click="selectStage(stage)"
              >
                <b>{{ stage.id }}</b>
                <span>{{ formatOffset(stage.fromMs - originMs) }}–{{ formatOffset(stage.toMs - originMs) }}</span>
              </button>
            </div>

            <div class="deep-period-inline">
              <div class="deep-period-inputs">
                <label class="deep-period-field">
                  <span>С, мин</span>
                  <input
                    v-model="periodFrom"
                    class="v4-inp"
                    data-testid="deep-period-from"
                    type="number"
                    min="0"
                    step="any"
                    :aria-label="DEEP_LABELS.periodFrom"
                  >
                </label>
                <label class="deep-period-field">
                  <span>По, мин</span>
                  <input
                    v-model="periodTo"
                    class="v4-inp"
                    data-testid="deep-period-to"
                    type="number"
                    min="0"
                    step="any"
                    :aria-label="DEEP_LABELS.periodTo"
                  >
                </label>
                <button
                  type="button"
                  class="v4-btn v4-btn--xs"
                  data-testid="deep-period-apply"
                  @click="applyPeriod"
                >
                  {{ DEEP_LABELS.periodApply }}
                </button>
                <button
                  type="button"
                  class="v4-btn v4-btn--xs"
                  data-testid="deep-period-all"
                  @click="wholePeriod"
                >
                  {{ DEEP_LABELS.periodAll }}
                </button>
              </div>
              <div class="deep-period-meta v4-sub v4-num">
                <span
                  v-if="applied"
                  data-testid="deep-period-snapped"
                >{{ DEEP_LABELS.periodSnapped(offset(axis.fromMs - originMs), offset(axis.toMs - originMs)) }}</span>
                <span
                  v-if="hasSnapshot && grid"
                  data-testid="deep-step-note"
                >{{ DEEP_LABELS.cellStep(stepMs / 1000) }}</span>
                <span data-testid="deep-load-note">{{ DEEP_LABELS.loadRollup(rollup) }}</span>
              </div>
            </div>
            <p
              v-if="invalidPeriod"
              class="validation-error"
              role="alert"
            >
              {{ DEEP_LABELS.periodInvalid }}
            </p>
          </div>

          <DeepCursorChart
            v-if="((deepMode === 'workbench' && workbenchTracks.length) || (deepMode === 'focus' && (primaryTrack || pinnedTracks.length))) && axis.toMs > axis.fromMs"
            :primary-track="deepMode === 'focus' ? primaryTrack : null"
            :tracks="deepMode === 'workbench' ? workbenchTracks : pinnedTracks"
            :axis="axis"
            :origin-ms="originMs"
            :stages="stages"
            :mode="deepMode"
            @move-track="moveTrack"
            @remove-track="removeTrack"
          />
          <p
            v-if="loadOutside"
            class="v4-sub"
          >
            {{ DEEP_LABELS.loadOutsideRun }}
          </p>
          <p
            v-else-if="!load.some((track) => track.points.length) && !loading"
            class="v4-sub"
          >
            {{ DEEP_LABELS.loadEmpty }}
          </p>
          <p
            v-if="truncated !== null"
            class="v4-sub"
          >
            {{ DEEP_LABELS.loadTruncated(truncated) }}
          </p>

          <!-- Compact window stats strip -->
          <div
            v-if="primaryTrack && statTiles.length"
            class="deep-stats-bar"
            aria-labelledby="deep-stats-title"
            data-testid="deep-stats"
          >
            <div class="deep-stats-bar__head">
              <span
                id="deep-stats-title"
                class="v4-kicker"
              >{{ DEEP_LABELS.statsTitle }}: {{ primaryTrack.label }}</span>
            </div>
            <dl
              class="sstrip"
              data-testid="deep-stat-tiles"
            >
              <div
                v-for="tile in statTiles"
                :key="tile.key"
                class="sstrip__tile"
                data-testid="deep-stat-tile"
                :data-stat="tile.key"
                :data-value="tile.raw"
              >
                <dt>{{ tile.label }}</dt>
                <dd class="v4-num">
                  {{ tile.value }}
                </dd>
              </div>
            </dl>
          </div>
        </section>
      </div>
    </div>
  </div>
</template>

<style scoped>
.deep-panel {
  display: flex;
  flex-direction: column;
  gap: 14px;
  min-width: 0;
}

.deep-top-bar {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-end;
  justify-content: space-between;
  gap: 10px 14px;
}

.deep-top-bar__copy {
  display: grid;
  gap: 2px;
  min-width: 0;
}

.deep-top-bar__copy h2 {
  margin: 0;
  font-size: 18px;
  line-height: 1.2;
  font-weight: 650;
}

.deep-nav-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

.deep-banners {
  display: grid;
  gap: 6px;
}

.deep-layout {
  display: grid;
  grid-template-columns: 312px minmax(0, 1fr);
  gap: 16px;
  align-items: start;
  min-width: 0;
}

.deep-layout > *,
.deep-main > * {
  min-width: 0;
}

.deep-main {
  display: grid;
  gap: 14px;
  min-width: 0;
}

/* LEFT Rubricator (Mockup v4.4) */
.rubricator-panel {
  position: sticky;
  top: 66px;
  max-height: calc(100vh - 84px);
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 12px;
  overflow: hidden;
}

.rub-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 6px;
}

.rub-head h3 {
  margin: 0;
  font-size: 13px;
  font-weight: 650;
}

.rub-domain-seg {
  width: 100%;
}

.rub-domain-seg button {
  flex: 1 1 0;
}

.rub-subbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 6px;
}

.rub-svc-select {
  flex: 1 1 110px;
  max-width: 100%;
}

.rub-limit-hint {
  margin: 0;
  font-size: 11px;
  color: var(--warn);
}

.rub-scroll {
  overflow-y: auto;
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding-right: 2px;
  min-height: 200px;
}

.rub-platform-wrap,
.rub-service-groups {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.rub-group {
  border: 1px solid var(--border);
  border-radius: 7px;
  overflow: hidden;
  background: var(--surface-inset);
  flex: none;
}

.rub-group__hd {
  width: 100%;
  min-height: 30px;
  padding: 6px 10px;
  border: 0;
  border-radius: 0;
  background: var(--surface-inset);
  color: var(--text);
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 6px;
  font-size: 12px;
  font-weight: 650;
  text-align: left;
}

.rub-group__hd:hover {
  background: var(--surface-muted);
}

.rub-group__hd--static {
  cursor: default;
}

.rub-group__hd--static:hover {
  background: var(--surface-inset);
}

.rub-group__name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.rub-group__meta {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  flex: none;
}

.rub-items {
  background: var(--surface);
  border-top: 1px solid var(--border);
  display: flex;
  flex-direction: column;
}

.rub-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto 24px;
  align-items: center;
  gap: 6px;
  padding: 4px 8px;
  border-bottom: 1px solid var(--border);
  font-size: 12px;
}

.rub-row:last-child {
  border-bottom: 0;
}

.rub-row:hover {
  background: var(--surface-inset);
}

.rub-row.in-stack {
  background: color-mix(in srgb, var(--info-bg) 55%, var(--surface));
}

.rub-row.is-primary {
  box-shadow: inset 2px 0 0 var(--brand);
}

.rub-pick {
  border: 0;
  padding: 2px 0;
  min-height: 22px;
  background: transparent;
  color: var(--text);
  font-size: 12px;
  text-align: left;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.rub-pick:hover {
  color: var(--brand);
}

.rub-dot-crit {
  color: var(--fail);
  margin-right: 3px;
}

.rub-meta-crit {
  color: var(--fail);
  font-weight: 650;
}

.add-lane-toggle {
  position: relative;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  border-radius: 5px;
  border: 1px solid var(--border);
  background: var(--surface-inset);
  color: var(--text-muted);
  font-family: var(--mono-font);
  font-size: 11px;
  font-weight: 700;
  cursor: pointer;
}

.add-lane-toggle:hover:not(.disabled) {
  border-color: var(--brand);
  color: var(--brand);
}

.add-lane-toggle.on {
  border-color: var(--brand);
  background: var(--brand);
  color: var(--brand-contrast);
}

.add-lane-toggle.disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

.sr-check {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  min-width: 0;
  min-height: 0;
  margin: 0;
  padding: 0;
  opacity: 0;
  cursor: inherit;
}

/* RIGHT Workbench Card */
.deep-workbench-card {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.deep-card-hd {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-start;
  justify-content: space-between;
  gap: 8px 12px;
}

.deep-card-hd h3 {
  margin: 0;
  font-size: 15.5px;
  font-weight: 650;
  line-height: 1.2;
}

.deep-presets {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.deep-period-strip {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.deep-period-inline {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 8px 12px;
}

.deep-period-inputs {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
}

.deep-period-field {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 11.5px;
  color: var(--text-muted);
}

.deep-period-field input {
  width: 72px;
  height: 24px;
  min-height: 24px;
  padding: 0 6px;
  font-size: 12px;
}

.deep-period-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 10px;
  font-size: 11.5px;
}

.deep-stats-bar {
  display: grid;
  gap: 6px;
  padding-top: 8px;
  border-top: 1px solid var(--border);
}

.sstrip {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(108px, 100%), 1fr));
  gap: 8px;
  margin: 0;
}

.sstrip__tile {
  padding: 7px 10px;
  border: 1px solid var(--border);
  border-radius: 7px;
  background: var(--surface-inset);
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.sstrip__tile dt {
  font-size: 11px;
  color: var(--text-muted);
}

.sstrip__tile dd {
  margin: 0;
  font-size: 14px;
  font-weight: 650;
}

.sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip: rect(0 0 0 0);
  white-space: nowrap;
}

@media (max-width: 1100px) {
  .deep-layout {
    grid-template-columns: minmax(0, 1fr);
  }

  .rubricator-panel {
    position: static;
    max-height: 420px;
  }
}
</style>
