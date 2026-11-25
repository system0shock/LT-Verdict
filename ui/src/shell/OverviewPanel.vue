<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue'
import { getBuckets, getResourceSeriesCatalog, getResourceSeriesValues, resourceSeriesValuesPath } from '../api'
import {
  MAX_IDS_PER_REQUEST, batchSeriesIds, fetchRunLoad, planResourcePeriod, resourceTrack, runStartMs, stageButtons,
  thresholdsFor, type DeepTrack, type RunLoad,
} from './deep'
import { OVERVIEW_LABELS } from './labels'
import { CAPACITY_LABELS } from './labels.tables'
import CapacityKneeChart from './CapacityKneeChart.vue'
import LogErrorsPanel from './LogErrorsPanel.vue'
import MiniInteractiveChart from './MiniInteractiveChart.vue'
import PlatformMap from './PlatformMap.vue'
import SharedCursorChart from './SharedCursorChart.vue'
import {
  attentionItems, capacityBlock, formatNumber, formatOffset, incidentView, keyMetrics, loadFrame, loadLane,
  loadP95Thresholds, loadSeries, platformTiles, transactionCatalog, trendOverview,
  type AttentionKind, type AttentionTarget, type IncidentAction, type IncidentCard,
} from './overview'
import { stageBands, type ChartFrame, type ChartLane, type ChartThreshold, type ChartToken } from './timeChart'
import type { AnalysisResult, AnalysisSummary, ResourceSeriesCatalog, ResourceSeriesEntry } from '../types'

const props = defineProps<{
  result: AnalysisResult
  runId: string
  analysisId: string
  analyses?: AnalysisSummary[]
}>()
const emit = defineEmits<{ navigate: [target: AttentionTarget] }>()

const armed = computed(() => props.analyses?.some((analysis) => analysis.analysis_id === props.analysisId && !!analysis.resource_arm) ?? false)
const LIMIT = 6
const expanded = ref(false)
const incidentsExpanded = ref(false)
const incidents = computed(() => incidentView(props.result))
const items = computed(() => attentionItems(props.result))
const visibleItems = computed(() => expanded.value ? items.value : items.value.slice(0, LIMIT))
const showAttentionSection = computed(() =>
  incidents.value.status !== 'EVALUATED' || incidents.value.overviewItems.length === 0 || items.value.length > 0,
)
const attentionLeadText = computed(() =>
  incidents.value.status === 'EVALUATED' && incidents.value.overviewItems.length > 0
    ? OVERVIEW_LABELS.attentionResidualLead
    : OVERVIEW_LABELS.attentionLead,
)

const origin = runStartMs(props.result)
const originMs = origin ?? 0
const hasResourceBinding = props.result.evidence.some((item) => item.type === 'resource_binding')
const catalogWindows = ref<ResourceSeriesCatalog['windows']>([])
const sparklineTracks = ref<Map<string, DeepTrack>>(new Map())
const sparklineFrame = ref<ChartFrame | null>(null)

const stages = computed(() => stageBands(props.result, 0))
const stageList = computed(() => stageButtons(props.result, catalogWindows.value, originMs))
const staged = computed(() => props.result.evidence.some((item) => item.type === 'stage_binding'))
const capacity = computed(() => props.result.analysis_mode === 'capacity_step' ? capacityBlock(props.result) : null)
const tiles = computed(() => keyMetrics(props.result))
const trends = computed(() => trendOverview(props.result))
const platform = computed(() => platformTiles(props.result, 8))
const transactions = computed(() => transactionCatalog(props.result))
const p95Thresholds = computed(() => loadP95Thresholds(props.result))

const runLoad = ref<RunLoad>({ buckets: [], rollupSeconds: 60, truncated: false })
const loading = ref(false)
const loadError = ref('')
const series = computed(() => loadSeries(runLoad.value.buckets, runLoad.value.rollupSeconds))
const controller = new AbortController()

function sparklineLane(seriesId: string, color: ChartToken = 'brand'): ChartLane | null {
  const track = sparklineTracks.value.get(seriesId)
  if (!track || !sparklineFrame.value) return null
  const thresholds: ChartThreshold[] = track.thresholds.map((t) => ({
    id: t.ruleId,
    value: t.value,
    text: `${formatNumber(t.value, 2)}${track.unit ? ` ${track.unit}` : ''}`,
    violated: t.violated,
  }))
  const hi = Math.max(track.max, ...thresholds.map((t) => t.value), 1)
  return {
    key: `spark:${seriesId}`,
    color,
    area: true,
    segments: track.segments.flatMap((segment) => {
      const points = segment.flatMap((index) => {
        const point = track.points[index]
        return point.value === null ? [] : [{ x: point.startMs + track.stepMs / 2, y: point.value }]
      })
      return points.length ? [points] : []
    }),
    lo: Math.min(0, track.min),
    hi,
    thresholds,
  }
}

function resolveIncidentSeriesId(card: IncidentCard): string | null {
  if (card.primaryAction?.kind === 'navigate' && card.primaryAction.target.seriesId) {
    return card.primaryAction.target.seriesId
  }
  for (const evId of card.evidenceIds) {
    const found = props.result.evidence.find((e) => 'id' in e && e.id === evId)
    if (found && (found.type === 'resource_policy_check' || found.type === 'resource_summary' || found.type === 'trend_check')) {
      return found.series_id
    }
  }
  if (card.family === 'RESOURCE') {
    const byEntity = props.result.evidence.find(
      (e): e is Extract<AnalysisResult['evidence'][number], { type: 'trend_check' | 'resource_summary' }> =>
        (e.type === 'trend_check' || e.type === 'resource_summary')
        && e.entity === card.scopeLabel,
    )
    if (byEntity) return byEntity.series_id
  }
  return null
}

function incidentChart(card: IncidentCard): {
  lane: ChartLane
  frame: ChartFrame
  stages: typeof stages.value
  label: string
  unit: string
  decimals: number
} | null {
  const color: ChartToken = card.priority === 'HIGH' ? 'fail' : card.priority === 'MEDIUM' ? 'warn' : 'c3'
  const seriesId = resolveIncidentSeriesId(card)
  if (seriesId && sparklineFrame.value) {
    const lane = sparklineLane(seriesId, color)
    const track = sparklineTracks.value.get(seriesId)
    if (lane && track) {
      return {
        lane,
        frame: sparklineFrame.value,
        stages: [],
        label: track.label,
        unit: track.unit,
        decimals: track.unit === 'count' || track.unit === 'bytes' ? 0 : 2,
      }
    }
  }
  if (card.family === 'TRANSACTION' && series.value.points.length) {
    let metric: 'p95' | 'errors' | 'rps' = 'p95'
    const thresholds: ChartThreshold[] = []
    for (const evId of card.evidenceIds) {
      const found = props.result.evidence.find((e) => 'id' in e && e.id === evId)
      if (found?.type === 'policy_check') {
        if (found.metric === 'error_rate_ratio') metric = 'errors'
        else if (found.metric === 'throughput_rps') metric = 'rps'
        else metric = 'p95'
        const val = Number(found.threshold)
        if (Number.isFinite(val) && val > 0 && metric === 'p95') {
          thresholds.push({
            id: found.rule_id,
            value: val,
            text: `порог ${formatNumber(val, 0)} мс`,
            violated: found.status === 'FAIL',
          })
        }
        break
      }
    }
    const base = loadLane(series.value, metric, metric === 'p95' ? (thresholds.length ? thresholds : p95Thresholds.value) : [])
    return {
      lane: { ...base, color: metric === 'errors' ? 'fail' : color, area: metric !== 'errors', bars: metric === 'errors' },
      frame: loadFrame(series.value),
      stages: stages.value,
      label: metric === 'errors' ? OVERVIEW_LABELS.trackErrors : metric === 'rps' ? OVERVIEW_LABELS.trackRps : `${OVERVIEW_LABELS.trackP95} (${card.scopeLabel})`,
      unit: metric === 'errors' ? 'шт' : metric === 'rps' ? OVERVIEW_LABELS.unitRps : OVERVIEW_LABELS.unitMs,
      decimals: metric === 'rps' ? 1 : 0,
    }
  }
  return null
}

async function loadSparklines() {
  if (!hasResourceBinding || props.result.run_validity === 'INVALID') return
  const incidentSeries = [
    ...incidents.value.overviewItems,
    ...incidents.value.moreItems,
  ].flatMap((item) => {
    const id = resolveIncidentSeriesId(item)
    return id ? [id] : []
  })
  const wantedIds = [...new Set([
    ...platform.value.map((tile) => tile.seriesId),
    ...(trends.value?.cards.map((card) => card.seriesId) ?? []),
    ...incidentSeries,
  ])]
  if (!wantedIds.length) return
  try {
    const entries: ResourceSeriesEntry[] = []
    let after: string | null = null
    let grid: { startMs: number; stepMs: number; pointCount: number } | null = null
    let windows: ResourceSeriesCatalog['windows'] = []
    do {
      const catalog = await getResourceSeriesCatalog(props.runId, props.analysisId, after ?? undefined, controller.signal)
      if (controller.signal.aborted) return
      if (!grid) {
        grid = { startMs: catalog.grid.start_epoch_ms, stepMs: catalog.grid.step_ms, pointCount: catalog.grid.point_count }
        windows = catalog.windows ?? []
      }
      entries.push(...catalog.series)
      after = catalog.next_after
    } while (after !== null)
    catalogWindows.value = windows
    if (!grid || !wantedIds.length) return
    const available = wantedIds.filter((id) => entries.some((entry) => entry.id === id))
    if (!available.length) return
    const plan = planResourcePeriod(grid, grid.startMs, grid.startMs + grid.stepMs * grid.pointCount, 120)
    sparklineFrame.value = { fromMs: plan.axis.fromMs, toMs: plan.axis.toMs, originMs: grid.startMs }
    const params = { step_ms: String(plan.stepMs), from_ms: String(plan.axis.fromMs), to_ms: String(plan.axis.toMs) }
    const baseLength = resourceSeriesValuesPath(props.runId, props.analysisId, [], params).length
    const loaded = new Map<string, DeepTrack>()
    for (const batch of batchSeriesIds(available, baseLength, undefined, MAX_IDS_PER_REQUEST)) {
      const path = resourceSeriesValuesPath(props.runId, props.analysisId, batch, params)
      const values = await getResourceSeriesValues(path, controller.signal)
      if (controller.signal.aborted) return
      for (const item of values.series) {
        const entry = entries.find((candidate) => candidate.id === item.id)
        if (!entry) continue
        loaded.set(item.id, resourceTrack(entry, item, values.grid, thresholdsFor(props.result, item.id)))
      }
    }
    if (!controller.signal.aborted) sparklineTracks.value = loaded
  } catch {
    // Non-fatal: Overview stays usable without resource sparklines.
  }
}

onMounted(async () => {
  if (props.result.run_validity === 'INVALID') return
  loading.value = true
  const sparklinePromise = loadSparklines()
  try {
    const loaded = await fetchRunLoad((rollup, from) => getBuckets(props.runId, props.analysisId, rollup, from, undefined, controller.signal))
    if (!controller.signal.aborted) runLoad.value = loaded
  } catch (failure) {
    if (!controller.signal.aborted) loadError.value = OVERVIEW_LABELS.loadFailed(failure instanceof Error ? failure.message : String(failure))
  } finally {
    if (!controller.signal.aborted) loading.value = false
  }
  await sparklinePromise
})
onUnmounted(() => controller.abort())

async function jumpToIncident(incidentId: string) {
  if (incidents.value.moreItems.some((item) => item.id === incidentId)) {
    incidentsExpanded.value = true
  }
  await nextTick()
  const element = document.getElementById(`incident-${incidentId}`)
  if (!element) return
  element.scrollIntoView()
  element.focus({ preventScroll: true })
}

function runAction(action: IncidentAction | null) {
  if (!action) return
  if (action.kind === 'navigate') emit('navigate', action.target)
  else void jumpToIncident(action.incidentId)
}

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
    <!-- 1. HERO LOAD & LATENCY CHART (Mockup v4.4 — right under Verdict!) -->
    <section
      id="overview-load"
      class="panel overview-hero-card"
      aria-labelledby="overview-load-title"
    >
      <div class="overview-section-head">
        <div>
          <h2 id="overview-load-title">
            {{ OVERVIEW_LABELS.loadTitle }}
          </h2>
          <p class="v4-sub">
            {{ OVERVIEW_LABELS.loadLead }}
          </p>
        </div>
        <button
          type="button"
          class="v4-btn v4-btn--xs"
          @click="emit('navigate', { tab: 'deep', targetId: 'deep-title' })"
        >
          Открыть в Глубоком анализе →
        </button>
      </div>

      <!-- Inline proportional Stage Ribbon (Mockup v4.4 .stages-bar) -->
      <div
        v-if="stageList.length"
        id="overview-stages"
        class="stages-bar"
        data-testid="overview-stages"
        role="group"
        :aria-label="OVERVIEW_LABELS.stageRibbonTitle"
      >
        <button
          v-for="stage in stageList"
          :key="stage.id"
          type="button"
          class="stg-btn overview-stage-btn"
          :class="{ 'stg-btn--steady': stage.steady }"
          :style="{ flex: `${Math.max(1, stage.toMs - stage.fromMs)} 1 0` }"
          data-testid="overview-stage"
          :data-stage="stage.id"
          :data-role="stage.role"
          :aria-label="OVERVIEW_LABELS.stageOpenAria(stage.id, `${formatOffset(stage.fromMs - originMs)} – ${formatOffset(stage.toMs - originMs)}`)"
          @click="emit('navigate', { tab: 'deep', targetId: 'deep-title', stageId: stage.id })"
        >
          <b>{{ stage.id }}</b>
          <span>{{ formatOffset(stage.fromMs - originMs) }}–{{ formatOffset(stage.toMs - originMs) }}</span>
        </button>
      </div>

      <p
        v-if="loading"
        class="v4-sub"
        data-testid="load-loading"
      >
        {{ OVERVIEW_LABELS.loadLoading }}
      </p>
      <p
        v-else-if="loadError"
        class="notice notice-fail"
        role="alert"
        data-testid="load-error"
      >
        {{ loadError }}
      </p>
      <SharedCursorChart
        v-else-if="series.points.length"
        :series="series"
        :stages="stages"
        :p95-thresholds="p95Thresholds"
        :transactions="transactions"
        :run-id="runId"
        :analysis-id="analysisId"
      />
      <p
        v-else
        class="v4-sub"
        data-testid="load-empty"
      >
        {{ OVERVIEW_LABELS.loadEmpty }}
      </p>
      <p
        v-if="runLoad.truncated"
        class="v4-sub"
        data-testid="load-partial"
      >
        {{ OVERVIEW_LABELS.loadPartial(runLoad.buckets.length, runLoad.rollupSeconds) }}
      </p>
    </section>

    <!-- 2. INCIDENTS 3-COLUMN GRID (Mockup v4.4) -->
    <section
      v-if="incidents.status !== 'ABSENT'"
      id="overview-incidents"
      class="overview-incidents-section"
      aria-labelledby="overview-incidents-title"
      data-testid="overview-incidents"
    >
      <div class="overview-section-head">
        <div>
          <h2 id="overview-incidents-title">
            {{ OVERVIEW_LABELS.incidentsTitle }}
          </h2>
          <p class="v4-sub">
            {{ OVERVIEW_LABELS.incidentsLead }}
          </p>
        </div>
        <button
          type="button"
          class="v4-btn v4-btn--xs"
          @click="emit('navigate', { tab: 'deep', targetId: 'deep-title' })"
        >
          Открыть ряды инцидентов в Свободном сравнении →
        </button>
      </div>

      <p
        v-if="incidents.status === 'NOT_EVALUATED'"
        class="panel v4-sub"
        data-testid="incidents-not-evaluated"
      >
        {{ OVERVIEW_LABELS.incidentsNotEvaluated(incidents.reasonCode) }}
      </p>
      <p
        v-else-if="!incidents.overviewItems.length && !incidents.moreItems.length && !incidents.omittedCount"
        class="panel v4-sub"
        data-testid="incidents-empty"
      >
        {{ OVERVIEW_LABELS.incidentsEmpty }}
      </p>
      <template v-else>
        <ol
          class="overview-incidents"
          data-testid="incidents-list"
        >
          <li
            v-for="card in incidents.overviewItems"
            :id="`incident-${card.id}`"
            :key="card.id"
            class="inc-card"
            tabindex="-1"
            data-testid="incident-card"
            :data-priority="card.priority"
            :data-family="card.family"
          >
            <div class="inc-card__top">
              <span
                class="v4-pill"
                :class="card.priority === 'HIGH' ? 'v4-pill--crit' : card.priority === 'MEDIUM' ? 'v4-pill--warn' : 'v4-pill--info'"
              >{{ card.priorityLabel }} · {{ OVERVIEW_LABELS.incidentRank(card.rank) }}</span>
              <span class="v4-pill v4-pill--info">{{ card.familyLabel }}</span>
            </div>
            <h3 class="inc-card__title">
              {{ card.title }}
            </h3>
            <p class="inc-card__meta v4-sub v4-num">
              <span>{{ card.scopeLabel }}</span>
              <span>{{ OVERVIEW_LABELS.incidentWindow(card.windowId) }}</span>
              <span>{{ card.intervalText }}</span>
              <span>{{ OVERVIEW_LABELS.incidentFindings(card.findingCount) }}</span>
              <span v-if="card.refsTruncated">{{ OVERVIEW_LABELS.incidentRefsTruncated }}</span>
            </p>
            <div
              v-if="incidentChart(card)"
              class="inc-card__spark"
              data-testid="incident-chart"
            >
              <MiniInteractiveChart
                :lane="incidentChart(card)!.lane"
                :frame="incidentChart(card)!.frame"
                :stages="incidentChart(card)!.stages"
                :label="incidentChart(card)!.label"
                :unit="incidentChart(card)!.unit"
                :decimals="incidentChart(card)!.decimals"
                :plot-height="96"
              />
            </div>
            <p class="inc-card__summary">
              {{ card.summary }}
            </p>
            <div class="inc-card__foot">
              <div class="inc-card__coinc">
                <button
                  v-for="link in card.coincidentWith"
                  :key="link.incidentId"
                  type="button"
                  class="coinc-btn"
                  data-testid="incident-coincident"
                  :disabled="!link.inStored"
                  @click="jumpToIncident(link.incidentId)"
                >
                  ⚡ {{ link.targetTitle ?? link.incidentId.slice(0, 18) }} ({{ link.basisLabel }})
                </button>
              </div>
              <button
                v-if="card.primaryAction"
                type="button"
                class="v4-btn v4-btn--xs"
                data-testid="incident-open"
                :aria-label="`${card.primaryAction.label}: ${card.title}`"
                @click="runAction(card.primaryAction)"
              >
                {{ card.primaryAction.label }} →
              </button>
            </div>
            <details class="inc-card__details">
              <summary>{{ OVERVIEW_LABELS.incidentNegativeSummary(card.negativeEvidence.length) }}</summary>
              <p
                v-if="!card.negativeEvidence.length"
                class="v4-sub"
              >
                {{ OVERVIEW_LABELS.incidentNegativeEmpty }}
              </p>
              <ul v-else>
                <li
                  v-for="(neg, idx) in card.negativeEvidence"
                  :key="`${neg.check}:${idx}`"
                >
                  <strong>{{ OVERVIEW_LABELS.incidentNegativeOutcome[neg.outcome] ?? neg.outcome }}</strong>
                  <code v-if="neg.reason_code">{{ neg.reason_code }}</code>:
                  {{ neg.text }}
                </li>
              </ul>
            </details>
            <details class="inc-card__details">
              <summary>{{ OVERVIEW_LABELS.incidentNextSummary(card.nextChecks.length) }}</summary>
              <ul>
                <li
                  v-for="(next, idx) in card.nextChecks"
                  :key="`${next.check}:${idx}`"
                >
                  <span>{{ next.text }}</span>
                  <button
                    v-if="next.action"
                    type="button"
                    class="v4-btn v4-btn--xs inc-next-btn"
                    data-testid="incident-next-action"
                    @click="runAction(next.action)"
                  >
                    {{ next.action.label }}
                  </button>
                </li>
              </ul>
            </details>
          </li>
        </ol>

        <template v-if="incidents.moreItems.length">
          <button
            type="button"
            class="v4-btn incidents-more-btn"
            data-testid="incidents-more"
            :aria-expanded="incidentsExpanded"
            @click="incidentsExpanded = !incidentsExpanded"
          >
            {{
              incidentsExpanded
                ? OVERVIEW_LABELS.incidentLess
                : OVERVIEW_LABELS.incidentMore(
                  incidents.moreItems.length,
                  incidents.moreResourceCount,
                  incidents.moreTransactionCount,
                  incidents.omittedCount,
                )
            }}
          </button>
          <ol
            v-if="incidentsExpanded"
            class="overview-incidents"
            data-testid="incidents-more-list"
          >
            <li
              v-for="card in incidents.moreItems"
              :id="`incident-${card.id}`"
              :key="card.id"
              class="inc-card"
              tabindex="-1"
              data-testid="incident-overflow-card"
              :data-priority="card.priority"
              :data-family="card.family"
            >
              <div class="inc-card__top">
                <span
                  class="v4-pill"
                  :class="card.priority === 'HIGH' ? 'v4-pill--crit' : card.priority === 'MEDIUM' ? 'v4-pill--warn' : 'v4-pill--info'"
                >{{ card.priorityLabel }} · {{ OVERVIEW_LABELS.incidentRank(card.rank) }}</span>
                <span class="v4-pill v4-pill--info">{{ card.familyLabel }}</span>
              </div>
              <h3 class="inc-card__title">
                {{ card.title }}
              </h3>
              <p class="inc-card__meta v4-sub v4-num">
                <span>{{ card.scopeLabel }}</span>
                <span>{{ OVERVIEW_LABELS.incidentWindow(card.windowId) }}</span>
                <span>{{ card.intervalText }}</span>
                <span>{{ OVERVIEW_LABELS.incidentFindings(card.findingCount) }}</span>
              </p>
              <div
                v-if="incidentChart(card)"
                class="inc-card__spark"
              >
                <MiniInteractiveChart
                  :lane="incidentChart(card)!.lane"
                  :frame="incidentChart(card)!.frame"
                  :stages="incidentChart(card)!.stages"
                  :label="incidentChart(card)!.label"
                  :unit="incidentChart(card)!.unit"
                  :decimals="incidentChart(card)!.decimals"
                  :plot-height="96"
                />
              </div>
              <p class="inc-card__summary">
                {{ card.summary }}
              </p>
              <div class="inc-card__foot">
                <div class="inc-card__coinc" />
                <button
                  v-if="card.primaryAction"
                  type="button"
                  class="v4-btn v4-btn--xs"
                  data-testid="incident-open"
                  :aria-label="`${card.primaryAction.label}: ${card.title}`"
                  @click="runAction(card.primaryAction)"
                >
                  {{ card.primaryAction.label }} →
                </button>
              </div>
            </li>
          </ol>
        </template>
        <p
          v-if="incidents.omittedCount > 0"
          class="v4-sub"
          data-testid="incidents-omitted"
        >
          {{ OVERVIEW_LABELS.incidentOmittedNote(incidents.omittedCount) }}
        </p>
      </template>
    </section>

    <!-- 3. ПВС LOG ERRORS & JTL PROTOCOL ERRORS (Mockup v4.4) -->
    <LogErrorsPanel
      :result="result"
      :run-id="runId"
      :analysis-id="analysisId"
      @open-service="emit('navigate', { tab: 'deep', targetId: 'deep-title' })"
    />

    <!-- 4. PLATFORM ARM MATRIX (Mockup v4.4) -->
    <PlatformMap
      v-if="armed && analyses"
      :run-id="runId"
      :analyses="analyses"
      :analysis-id="analysisId"
      :result="result"
      @open-service="emit('navigate', { tab: 'deep', targetId: 'deep-title' })"
    />

    <!-- 5. TOP PLATFORM METRICS TILES (Mockup v4.4 .grid4 .tile-btn) -->
    <section
      v-if="platform.length"
      id="overview-platform"
      class="panel overview-diag-card"
      aria-labelledby="overview-platform-title"
      data-testid="overview-platform"
    >
      <div class="overview-section-head">
        <div>
          <h2 id="overview-platform-title">
            {{ OVERVIEW_LABELS.platformTitle }}
          </h2>
          <p class="v4-sub">
            {{ OVERVIEW_LABELS.platformLead }}
          </p>
        </div>
      </div>
      <div class="overview-grid4">
        <article
          v-for="tile in platform"
          :key="tile.seriesId"
          class="tile-card"
          data-testid="overview-platform-tile"
          :data-status="tile.status"
        >
          <div class="tile-card__top">
            <strong class="tile-card__name">{{ tile.seriesId }}</strong>
            <span
              class="v4-pill"
              :class="tile.status === 'FAIL' ? 'v4-pill--crit' : tile.status === 'DIAGNOSTIC' || tile.status === 'NO_VERDICT' ? 'v4-pill--warn' : 'v4-pill--good'"
            >{{ tile.statusLabel }}</span>
          </div>
          <div class="tile-card__val v4-num">
            <b>{{ tile.peakText }}</b>
            <span v-if="tile.unit">{{ tile.unit }}</span>
          </div>
          <MiniInteractiveChart
            v-if="sparklineLane(tile.seriesId, tile.status === 'FAIL' ? 'fail' : tile.status === 'DIAGNOSTIC' ? 'warn' : 'brand') && sparklineFrame"
            :lane="sparklineLane(tile.seriesId, tile.status === 'FAIL' ? 'fail' : tile.status === 'DIAGNOSTIC' ? 'warn' : 'brand')!"
            :frame="sparklineFrame"
            :label="tile.seriesId"
            :unit="tile.unit ?? ''"
            :plot-height="78"
            :max-time-intervals="2"
          />
          <p class="v4-sub tile-card__meta">
            {{ tile.entity }} · {{ tile.metric }} · {{ tile.windowId }}
            <span v-if="tile.missingCells > 0"> · {{ OVERVIEW_LABELS.platformMissing(tile.missingCells) }}</span>
          </p>
          <button
            type="button"
            class="v4-btn v4-btn--xs"
            data-testid="overview-platform-open"
            @click="emit('navigate', { tab: 'deep', targetId: 'deep-title', stageId: tile.windowId, seriesId: tile.seriesId })"
          >
            {{ OVERVIEW_LABELS.platformOpenDeep }} →
          </button>
        </article>
      </div>
    </section>

    <!-- 6. TRENDS IN STEADY WINDOW (Mockup v4.4 .grid3) -->
    <section
      v-if="trends"
      id="overview-trends"
      class="panel overview-diag-card"
      aria-labelledby="overview-trends-title"
      data-testid="overview-trends"
    >
      <div class="overview-section-head">
        <div>
          <h2 id="overview-trends-title">
            {{ OVERVIEW_LABELS.trendsTitle }}
          </h2>
          <p class="v4-sub">
            {{ OVERVIEW_LABELS.trendsLead }}
          </p>
        </div>
        <button
          type="button"
          class="v4-btn v4-btn--xs"
          data-testid="overview-trends-table-btn"
          @click="emit('navigate', { tab: 'tables', targetId: 'trend-results' })"
        >
          {{ OVERVIEW_LABELS.openTrends }}
        </button>
      </div>
      <p
        class="v4-sub"
        data-testid="overview-trends-summary"
      >
        {{ OVERVIEW_LABELS.trendsSummaryCounts(trends.total, trends.observed, trends.notMaterial, trends.insufficient, trends.unavailable) }}
      </p>
      <p
        v-if="!trends.cards.length"
        class="v4-sub"
      >
        {{ OVERVIEW_LABELS.trendsEmpty }}
      </p>
      <div
        v-else
        class="overview-grid3"
      >
        <article
          v-for="card in trends.cards"
          :key="card.id"
          class="tile-card"
          data-testid="overview-trend-card"
        >
          <div class="tile-card__top">
            <strong class="tile-card__name">{{ card.seriesId }}</strong>
            <span class="v4-pill v4-pill--warn">{{ card.directionLabel }}</span>
          </div>
          <p class="v4-sub">
            <span v-if="card.entity">{{ card.entity }} · </span>{{ card.windowId }}
          </p>
          <MiniInteractiveChart
            v-if="sparklineLane(card.seriesId, 'c3') && sparklineFrame"
            :lane="sparklineLane(card.seriesId, 'c3')!"
            :frame="sparklineFrame"
            :label="card.seriesId"
            :unit="card.unit ?? ''"
            :plot-height="94"
          />
          <p class="tile-card__stats v4-sub v4-num">
            <span>{{ OVERVIEW_LABELS.trendsSlope(card.slope, card.unit) }}</span>
            <span>{{ OVERVIEW_LABELS.trendsShift(card.shift, card.unit) }}</span>
            <span>{{ OVERVIEW_LABELS.trendsMedian(card.median, card.unit) }}</span>
          </p>
          <button
            type="button"
            class="v4-btn v4-btn--xs"
            data-testid="overview-trend-open"
            @click="emit('navigate', { tab: 'deep', targetId: 'deep-title', stageId: card.windowId, seriesId: card.seriesId })"
          >
            {{ OVERVIEW_LABELS.trendsOpenDeep }} →
          </button>
        </article>
      </div>
    </section>

    <!-- 7. RESIDUAL ATTENTION -->
    <section
      v-if="showAttentionSection"
      id="overview-attention"
      class="panel overview-diag-card"
      aria-labelledby="overview-attention-title"
    >
      <h2 id="overview-attention-title">
        {{ OVERVIEW_LABELS.attentionTitle }}
      </h2>
      <p class="v4-sub">
        {{ attentionLeadText }}
      </p>
      <p
        v-if="!items.length"
        class="v4-sub"
        data-testid="attention-empty"
      >
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
            <div class="overview-attention__row">
              <span
                class="v4-pill"
                :class="attention.kind === 'violation' ? 'v4-pill--crit' : attention.kind === 'diagnostic' ? 'v4-pill--info' : 'v4-pill--warn'"
              >{{ kindLabels[attention.kind] }}</span>
              <span
                v-if="attention.badge || attention.diagnostic"
                class="overview-badge"
                data-testid="diagnostic-badge"
              >{{ attention.badge ?? OVERVIEW_LABELS.diagnosticBadge }}</span>
              <strong>{{ attention.title }}</strong>
            </div>
            <span
              v-if="attention.detail"
              class="v4-sub"
            >{{ attention.detail }}</span>
            <button
              v-if="attention.target"
              type="button"
              class="v4-btn v4-btn--xs"
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
          class="v4-btn v4-btn--xs"
          data-testid="attention-more"
          :aria-expanded="expanded"
          @click="expanded = !expanded"
        >
          {{ expanded ? OVERVIEW_LABELS.attentionLess : OVERVIEW_LABELS.attentionMore(items.length - LIMIT) }}
        </button>
      </template>
      <p
        v-if="items.some((attention) => attention.diagnostic)"
        class="v4-sub"
        data-testid="diagnostic-note"
      >
        {{ OVERVIEW_LABELS.diagnosticNote }}
      </p>
    </section>

    <!-- 8. CAPACITY BLOCK (when capacity_step mode) -->
    <section
      v-if="capacity"
      id="overview-capacity"
      class="panel overview-capacity"
      data-testid="capacity-block"
      aria-labelledby="overview-capacity-title"
    >
      <h2 id="overview-capacity-title">
        {{ OVERVIEW_LABELS.capacityTitle }}
      </h2>
      <p class="v4-sub">
        {{ OVERVIEW_LABELS.capacityLead }}
      </p>
      <p
        class="overview-capacity__verdict"
        data-testid="capacity-block-verdict"
        :data-verdict="capacity.verdict"
      >
        {{ capacity.verdictText }}
      </p>
      <p data-testid="capacity-block-bound">
        {{ CAPACITY_LABELS.boundLabel }}: {{ capacity.boundText }}.
      </p>
      <p
        v-if="capacity.statement"
        data-testid="capacity-block-statement"
      >
        {{ capacity.statement }}
      </p>
      <p
        v-if="capacity.kneeLine"
        data-testid="capacity-block-knee"
      >
        {{ capacity.kneeLine }}
      </p>
      <p
        v-if="capacity.guardWarning"
        role="note"
        data-testid="capacity-block-guard-warning"
      >
        {{ OVERVIEW_LABELS.capacityGuardWarning }}
      </p>
      <h3>{{ OVERVIEW_LABELS.capacityChartTitle }}</h3>
      <p class="v4-sub">
        {{ CAPACITY_LABELS.axisLabel }}: {{ capacity.axisText }}. {{ capacity.counts }}
      </p>
      <div
        class="overview-capacity__chart"
        aria-hidden="true"
        data-testid="capacity-block-chart"
      >
        <div
          v-for="stage in capacity.stages"
          :key="stage.key"
          class="overview-capacity__row"
          data-testid="capacity-block-bar-row"
          :data-kind="stage.kind"
        >
          <p class="overview-capacity__label">
            <strong>{{ stage.label }}</strong>
            <span>{{ stage.target }}</span>
            <span>{{ stage.mark }} {{ stage.verdictText }}</span>
          </p>
          <svg
            viewBox="0 0 1000 24"
            preserveAspectRatio="none"
            focusable="false"
            data-testid="capacity-block-bar"
          >
            <rect
              class="overview-capacity__rect"
              :class="`overview-capacity__rect--${stage.kind}`"
              x="0"
              y="5"
              :width="stage.barX"
              height="14"
              vector-effect="non-scaling-stroke"
            />
            <line
              v-if="stage.achievedX !== null"
              class="overview-capacity__achieved"
              :x1="stage.achievedX"
              :x2="stage.achievedX"
              y1="0"
              y2="24"
              vector-effect="non-scaling-stroke"
            />
            <line
              v-if="capacity.lowerX !== null"
              class="overview-capacity__bound overview-capacity__bound--lower"
              :x1="capacity.lowerX"
              :x2="capacity.lowerX"
              y1="0"
              y2="24"
              vector-effect="non-scaling-stroke"
            />
            <line
              v-if="capacity.upperX !== null"
              class="overview-capacity__bound overview-capacity__bound--upper"
              :x1="capacity.upperX"
              :x2="capacity.upperX"
              y1="0"
              y2="24"
              vector-effect="non-scaling-stroke"
            />
          </svg>
        </div>
      </div>
      <div
        class="v4-table-wrap"
        tabindex="0"
        role="region"
        :aria-label="OVERVIEW_LABELS.capacityTableRegion"
      >
        <table data-testid="capacity-block-table">
          <thead>
            <tr>
              <th
                v-for="head in OVERVIEW_LABELS.capacityHeads"
                :key="head"
                scope="col"
              >
                {{ head }}
              </th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="stage in capacity.stages"
              :key="stage.key"
              data-testid="capacity-block-stage"
              :data-kind="stage.kind"
            >
              <th scope="row">
                {{ stage.label }}
              </th>
              <td>{{ stage.target }}</td>
              <td>{{ stage.achieved }}</td>
              <td>
                <span
                  class="status-text"
                  :data-status="stage.status"
                ><span aria-hidden="true">{{ stage.mark }}</span>&nbsp;{{ stage.verdictText }}</span>
                <span
                  v-if="stage.smallSample"
                  class="overview-capacity__mark"
                  data-testid="capacity-block-small-sample"
                >{{ CAPACITY_LABELS.smallSampleMark }}</span>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <p class="v4-sub">
        {{ capacity.legend }}
      </p>
      <CapacityKneeChart
        :result="result"
        compact
      />
      <p
        v-if="capacity.smallSample"
        class="v4-sub"
        data-testid="capacity-block-small-sample-note"
        role="note"
      >
        {{ CAPACITY_LABELS.smallSampleNote }}
      </p>
      <button
        type="button"
        class="v4-btn"
        data-testid="capacity-block-open"
        :aria-label="OVERVIEW_LABELS.capacityOpenAria"
        @click="emit('navigate', { tab: 'tables', targetId: 'capacity-results' })"
      >
        {{ OVERVIEW_LABELS.openCapacity }}
      </button>
    </section>

    <!-- 9. COMPACT WHOLE-RUN SUMMARY STRIP -->
    <section
      v-if="tiles.length"
      id="overview-metrics"
      class="panel overview-metrics-card"
      aria-labelledby="overview-metrics-title"
    >
      <div class="overview-section-head">
        <h2 id="overview-metrics-title">
          {{ OVERVIEW_LABELS.metricsTitle }}
        </h2>
        <span
          v-if="staged"
          class="v4-sub"
          data-testid="metrics-whole-run-note"
        >{{ OVERVIEW_LABELS.metricsWholeRunNote }}</span>
        <span
          v-if="result.analysis_mode === 'capacity_step'"
          class="v4-sub"
          data-testid="metrics-capacity-note"
        >{{ OVERVIEW_LABELS.metricsCapacityNote }}</span>
      </div>
      <dl class="overview-metrics">
        <div
          v-for="tile in tiles"
          :key="tile.key"
          class="overview-metrics__tile"
          data-testid="metric-tile"
          :data-metric="tile.key"
          :data-value="tile.raw"
        >
          <dt>{{ tile.label }}</dt>
          <dd class="v4-num">
            {{ tile.value }}
          </dd>
        </div>
      </dl>
    </section>
  </div>
</template>

<style scoped>
.overview-panel {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  gap: 18px;
  min-width: 0;
}

.overview-panel > * {
  min-width: 0;
}

.overview-hero-card,
.overview-diag-card,
.overview-metrics-card {
  display: flex;
  flex-direction: column;
  gap: 11px;
}

.overview-section-head {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-end;
  justify-content: space-between;
  gap: 8px 12px;
}

.overview-section-head h2 {
  font-size: 17px;
  line-height: 1.2;
  font-weight: 650;
}

/* Incidents Grid (Mockup v4.4 .grid3 + .inc-card) */
.overview-incidents-section {
  display: flex;
  flex-direction: column;
  gap: 11px;
}

.overview-incidents {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(300px, 100%), 1fr));
  gap: 14px;
  list-style: none;
  margin: 0;
  padding: 0;
  align-items: stretch;
}

.inc-card {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 14px 16px;
  border-radius: 10px;
  background: var(--surface);
  border: 1px solid var(--border);
  border-top: 3px solid var(--border-strong);
  min-width: 0;
  overflow-wrap: anywhere;
}

.inc-card[data-priority="HIGH"] { border-top-color: var(--fail-solid); }
.inc-card[data-priority="MEDIUM"] { border-top-color: var(--warn-solid); }
.inc-card[data-priority="LOW"] { border-top-color: var(--brand); }

.inc-card__top {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}

.inc-card__title {
  margin: 0;
  font-size: 14.5px;
  line-height: 1.3;
  font-weight: 650;
}

.inc-card__meta {
  display: flex;
  flex-wrap: wrap;
  gap: 2px 10px;
  margin: 0;
  font-size: 11.5px;
}

.inc-card__spark {
  border: 1px solid var(--border);
  border-radius: 6px;
  background: var(--surface-inset);
  padding: 4px;
}

.inc-card__summary {
  margin: 0;
  font-size: 12.5px;
  line-height: 1.45;
}

.inc-card__foot {
  margin-top: auto;
  padding-top: 4px;
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
}

.inc-card__coinc {
  display: flex;
  flex-wrap: wrap;
  gap: 5px;
}

.coinc-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  min-height: 24px;
  max-width: 100%;
  padding: 3px 8px;
  border-radius: 5px;
  border: 1px solid var(--border);
  background: var(--surface-inset);
  font-size: 11.5px;
  color: var(--brand);
  font-weight: 500;
  text-align: left;
  white-space: normal;
  overflow-wrap: anywhere;
}

.inc-card__details {
  border-top: 1px solid var(--border);
  padding-top: 6px;
  font-size: 12px;
  color: var(--text-muted);
}

.inc-card__details summary {
  cursor: pointer;
  font-weight: 500;
  min-height: 24px;
  display: flex;
  align-items: center;
}

.inc-card__details ul {
  display: grid;
  gap: 5px;
  margin: 6px 0 0;
  padding-left: 18px;
}

.inc-next-btn {
  margin-left: 6px;
}

.incidents-more-btn {
  align-self: flex-start;
}

/* Platform & Trends Grids (Mockup v4.4 .grid4 / .grid3) */
.overview-grid4 {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(220px, 100%), 1fr));
  gap: 12px;
}

.overview-grid3 {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(280px, 100%), 1fr));
  gap: 12px;
}

.tile-card {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 11px 13px;
  border-radius: 8px;
  border: 1px solid var(--border);
  background: var(--surface-inset);
  min-width: 0;
  overflow-wrap: anywhere;
}

.tile-card[data-status="FAIL"] { border-left: 3px solid var(--fail-solid); }
.tile-card[data-status="NO_VERDICT"],
.tile-card[data-status="DIAGNOSTIC"] { border-left: 3px solid var(--warn-solid); }
.tile-card[data-status="PASS"] { border-left: 3px solid var(--pass-solid); }

.tile-card__top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 6px;
  flex-wrap: wrap;
}

.tile-card__name {
  font-size: 12.5px;
  font-weight: 650;
}

.tile-card__val {
  display: flex;
  align-items: baseline;
  gap: 5px;
}

.tile-card__val b {
  font-size: 20px;
  line-height: 1;
  font-weight: 700;
}

.tile-card__val span {
  font-size: 12px;
  color: var(--text-muted);
}

.tile-card__meta,
.tile-card__stats {
  margin: 0;
  font-size: 11.5px;
  display: flex;
  flex-wrap: wrap;
  gap: 2px 10px;
}

.tile-card button {
  align-self: flex-start;
  margin-top: auto;
}

/* Residual Attention */
.overview-attention {
  display: grid;
  gap: 8px;
  list-style: none;
  margin: 0;
  padding: 0;
}

.overview-attention li {
  display: flex;
  flex-direction: column;
  gap: 4px;
  border: 1px solid var(--border);
  border-left-width: 3px;
  border-radius: 8px;
  padding: 10px 12px;
  background: var(--surface-inset);
}

.overview-attention li[data-kind="violation"] { border-left-color: var(--fail-solid); }
.overview-attention li[data-kind="no_verdict"],
.overview-attention li[data-kind="policy"],
.overview-attention li[data-kind="coverage"] { border-left-color: var(--warn-solid); }
.overview-attention li[data-kind="diagnostic"] { border-left-color: var(--brand); }

.overview-attention__row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
}

.overview-attention li button {
  align-self: flex-start;
  min-height: 44px;
  min-width: 44px;
}

.overview-badge {
  display: inline-flex;
  align-items: center;
  border-radius: 999px;
  padding: 2px 8px;
  font-size: 11px;
  font-weight: 600;
  line-height: 1.25;
  background: var(--info-bg);
  color: var(--brand);
  max-width: 100%;
  white-space: normal;
  overflow-wrap: anywhere;
}

/* Capacity */
.overview-capacity { min-width: 0; display: flex; flex-direction: column; gap: 10px; }
.overview-capacity__verdict { margin: 0; font-size: 1.2rem; font-weight: 700; }
.overview-capacity__chart { display: grid; gap: 8px; }
.overview-capacity__label { display: flex; flex-wrap: wrap; gap: 2px 12px; margin: 0; font-size: 12.5px; }
.overview-capacity__row svg { display: block; width: 100%; height: 22px; background: var(--surface-inset); border-radius: 4px; }
.overview-capacity__rect--pass { fill: var(--pass); }
.overview-capacity__rect--fail { fill: var(--fail); }
.overview-capacity__rect--unverified { fill: none; stroke: var(--text-muted); stroke-width: 2; stroke-dasharray: 4 3; }
.overview-capacity__achieved { stroke: var(--text); stroke-width: 3; }
.overview-capacity__bound { stroke: var(--brand); stroke-width: 2; }
.overview-capacity__bound--lower { stroke-dasharray: 6 3; }
.overview-capacity__bound--upper { stroke-dasharray: 2 3; }
.overview-capacity__mark { margin-inline-start: 0.5em; font-style: italic; }
[data-testid="capacity-block-open"] { align-self: flex-start; }

/* Whole-run compact strip */
.overview-metrics {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(130px, 100%), 1fr));
  gap: 8px;
  margin: 0;
}

.overview-metrics__tile {
  padding: 8px 10px;
  border: 1px solid var(--border);
  border-radius: 7px;
  background: var(--surface-inset);
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.overview-metrics__tile dt {
  font-size: 11px;
  color: var(--text-muted);
}

.overview-metrics__tile dd {
  margin: 0;
  font-size: 15px;
  font-weight: 700;
}
</style>
