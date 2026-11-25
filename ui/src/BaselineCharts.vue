<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { getBuckets, getResourceSeriesCatalog, getResourceSeriesValues, resourceSeriesValuesPath } from './api'
import type { AnalysisResult, BaselineComparison, ResourceSeriesEntry } from './types'
import CompareCursorChart, { type CompareResourceLane } from './shell/CompareCursorChart.vue'
import { batchSeriesIds, fetchRunLoad, planResourcePeriod, resourceTrack } from './shell/deep'
import { COMPARE_LABELS, type CompareLabels } from './shell/labels.compare'
import { loadSeries, type LoadSeries } from './shell/overview'
import type { ChartPoint } from './shell/timeChart'

const props = withDefaults(defineProps<{
  comparison: BaselineComparison
  labels?: CompareLabels
  result?: AnalysisResult | null
}>(), {
  labels: () => COMPARE_LABELS,
  result: null,
})

const currentSeries = ref<LoadSeries | null>(null)
const baselineSeries = ref<LoadSeries | null>(null)
const resourceLanes = ref<CompareResourceLane[]>([])
const error = ref('')
const truncated = ref(false)
const busy = ref(false)
let revision = 0

const incompatible = computed(() => props.comparison.metrics.some((item) => item.reason === 'INCOMPATIBLE_METRIC_DEFINITION'))

watch(() => props.comparison, () => {
  revision += 1
  currentSeries.value = null
  baselineSeries.value = null
  resourceLanes.value = []
  error.value = ''
  truncated.value = false
  busy.value = false
  if (!incompatible.value) void loadEcharts()
}, { immediate: true })

async function loadResourceOverlay(
  refA: { run_id: string; analysis_id: string },
  refB: { run_id: string; analysis_id: string },
): Promise<CompareResourceLane[]> {
  try {
    const [catA, catB] = await Promise.all([
      getResourceSeriesCatalog(refA.run_id, refA.analysis_id),
      getResourceSeriesCatalog(refB.run_id, refB.analysis_id),
    ])
    const mapB = new Map<string, ResourceSeriesEntry>(catB.series.map((item) => [item.id, item]))
    const candidateIds = new Set(
      (props.comparison.window_comparison?.metrics ?? [])
        .filter((m) => m.status === 'CANDIDATE' && m.resource_series_id)
        .map((m) => m.resource_series_id!),
    )
    const common = catA.series
      .filter((entry) => mapB.has(entry.id))
      .sort((a, b) => Number(candidateIds.has(b.id)) - Number(candidateIds.has(a.id)))
      .slice(0, 4)
    if (!common.length) return []

    const fetchSide = async (refObj: { run_id: string; analysis_id: string }, gridRaw: typeof catA.grid, entries: ResourceSeriesEntry[]) => {
      const grid = { startMs: gridRaw.start_epoch_ms, stepMs: gridRaw.step_ms, pointCount: gridRaw.point_count }
      const plan = planResourcePeriod(grid, grid.startMs, grid.startMs + grid.stepMs * grid.pointCount, 240)
      const params = { step_ms: String(plan.stepMs), from_ms: String(plan.axis.fromMs), to_ms: String(plan.axis.toMs) }
      const ids = entries.map((e) => e.id)
      const baseLen = resourceSeriesValuesPath(refObj.run_id, refObj.analysis_id, [], params).length
      const out = new Map<string, { segments: ChartPoint[][]; min: number; max: number }>()
      for (const batch of batchSeriesIds(ids, baseLen)) {
        const path = resourceSeriesValuesPath(refObj.run_id, refObj.analysis_id, batch, params)
        const resp = await getResourceSeriesValues(path)
        for (const s of resp.series) {
          const entry = entries.find((e) => e.id === s.id)
          if (!entry) continue
          const track = resourceTrack(entry, s, resp.grid, [])
          const segments: ChartPoint[][] = track.segments.flatMap((seg) => {
            const pts = seg.flatMap((idx) => {
              const pt = track.points[idx]
              return pt.value === null ? [] : [{ x: pt.startMs - grid.startMs, y: pt.value }]
            })
            return pts.length ? [pts] : []
          })
          out.set(s.id, { segments, min: track.min, max: track.max })
        }
      }
      return out
    }

    const [curTracks, baseTracks] = await Promise.all([
      fetchSide(refA, catA.grid, common),
      fetchSide(refB, catB.grid, common),
    ])

    return common.flatMap((entry): CompareResourceLane[] => {
      const cur = curTracks.get(entry.id)
      const base = baseTracks.get(entry.id)
      if (!cur || !cur.segments.length) return []
      const hi = Math.max(cur.max, base?.max ?? 0, 1)
      const lo = Math.min(cur.min, base?.min ?? cur.min)
      return [{
        id: entry.id,
        label: entry.entity ? `${entry.entity} · ${entry.metric}` : entry.id,
        unit: entry.unit,
        lane: {
          key: `res-${entry.id}`,
          color: 'c3',
          segments: cur.segments,
          ...(base?.segments.length ? { baseline: base.segments } : {}),
          lo: lo > 0 ? lo : 0,
          hi,
          tightMin: lo > 25,
        },
      }]
    })
  } catch {
    return []
  }
}

async function loadEcharts() {
  const token = ++revision
  busy.value = true
  error.value = ''
  try {
    const a = props.comparison.current
    const b = props.comparison.baseline.reference
    const [left, right, resources] = await Promise.all([
      fetchRunLoad((step, fromMs) => getBuckets(a.run_id, a.analysis_id, step, fromMs)),
      fetchRunLoad((step, fromMs) => getBuckets(b.run_id, b.analysis_id, step, fromMs)),
      loadResourceOverlay(a, b),
    ])
    if (token !== revision) return
    currentSeries.value = loadSeries(left.buckets, left.rollupSeconds)
    baselineSeries.value = loadSeries(right.buckets, right.rollupSeconds)
    resourceLanes.value = resources
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
        :resource-lanes="resourceLanes"
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
