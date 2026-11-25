<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue'
import { getBuckets, getResourceSeriesCatalog, getResourceSeriesValues, resourceSeriesValuesPath } from '../api'
import {
  MAX_IDS_PER_REQUEST, batchSeriesIds, fetchRunLoad, planResourcePeriod, resourceTrack, runStartMs, stageButtons,
  thresholdsFor, type DeepTrack, type RunLoad,
} from './deep'
import { OVERVIEW_LABELS } from './labels'
import { CAPACITY_LABELS } from './labels.tables'
import PlatformMap from './PlatformMap.vue'
import SharedCursorChart from './SharedCursorChart.vue'
import TimeChart from './TimeChart.vue'
import {
  attentionItems, capacityBlock, formatNumber, formatOffset, incidentView, keyMetrics, loadP95Thresholds, loadSeries,
  platformTiles, trendOverview, type AttentionKind, type AttentionTarget, type IncidentAction,
} from './overview'
import { stageBands, type ChartFrame, type ChartLane, type ChartToken } from './timeChart'
import type { AnalysisResult, AnalysisSummary, ResourceSeriesCatalog, ResourceSeriesEntry } from '../types'

const props = defineProps<{
  result: AnalysisResult
  runId: string
  analysisId: string
  analyses?: AnalysisSummary[]
}>()
const emit = defineEmits<{ navigate: [target: AttentionTarget] }>()

// Карта «сервис x плечо» нужна, только если у выбранного анализа есть метка плеча (resource_arm в списке анализов).
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
const p95Thresholds = computed(() => loadP95Thresholds(props.result))

const runLoad = ref<RunLoad>({ buckets: [], rollupSeconds: 60, truncated: false })
const loading = ref(false)
const loadError = ref('')
const series = computed(() => loadSeries(runLoad.value.buckets, runLoad.value.rollupSeconds))
const controller = new AbortController()

function sparklineLane(seriesId: string, color: ChartToken = 'brand'): ChartLane | null {
  const track = sparklineTracks.value.get(seriesId)
  if (!track || !sparklineFrame.value) return null
  return {
    key: `spark:${seriesId}`,
    color,
    segments: track.segments.flatMap((segment) => {
      const points = segment.flatMap((index) => {
        const point = track.points[index]
        return point.value === null ? [] : [{ x: point.startMs + track.stepMs / 2, y: point.value }]
      })
      return points.length ? [points] : []
    }),
    lo: Math.min(0, track.min),
    hi: track.max > 0 ? track.max : 1,
  }
}

async function loadSparklines() {
  if (!hasResourceBinding || props.result.run_validity === 'INVALID') return
  const wantedIds = [...new Set([
    ...platform.value.map((tile) => tile.seriesId),
    ...(trends.value?.cards.map((card) => card.seriesId) ?? []),
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

// Обзор грузит весь прогон сам: шаг и диапазон вкладки «Таблицы» на него не влияют.
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
    <PlatformMap
      v-if="armed && analyses"
      :run-id="runId"
      :analyses="analyses"
      :analysis-id="analysisId"
      :result="result"
    />

    <section
      v-if="stageList.length"
      id="overview-stages"
      class="panel"
      aria-labelledby="overview-stages-title"
      data-testid="overview-stages"
    >
      <h2 id="overview-stages-title">
        {{ OVERVIEW_LABELS.stageRibbonTitle }}
      </h2>
      <p class="muted">
        {{ OVERVIEW_LABELS.stageRibbonLead }}
      </p>
      <div
        class="overview-stage-ribbon"
        role="group"
        :aria-label="OVERVIEW_LABELS.stageRibbonTitle"
      >
        <button
          v-for="stage in stageList"
          :key="stage.id"
          type="button"
          class="overview-stage-pill"
          :class="{ 'overview-stage-pill--steady': stage.steady }"
          data-testid="overview-stage"
          :data-stage="stage.id"
          :data-role="stage.role"
          :aria-label="OVERVIEW_LABELS.stageOpenAria(stage.id, `${formatOffset(stage.fromMs - originMs)} – ${formatOffset(stage.toMs - originMs)}`)"
          @click="emit('navigate', { tab: 'deep', targetId: 'deep-title', stageId: stage.id })"
        >
          <strong>{{ stage.id }}</strong>
          <span class="overview-stage-pill__meta">{{ OVERVIEW_LABELS.stageRoleLabels[stage.role] ?? stage.role }} · {{ formatOffset(stage.fromMs - originMs) }}–{{ formatOffset(stage.toMs - originMs) }}</span>
        </button>
      </div>
    </section>

    <section
      v-if="incidents.status !== 'ABSENT'"
      id="overview-incidents"
      class="panel"
      aria-labelledby="overview-incidents-title"
      data-testid="overview-incidents"
    >
      <h2 id="overview-incidents-title">
        {{ OVERVIEW_LABELS.incidentsTitle }}
      </h2>
      <p
        v-if="incidents.status === 'NOT_EVALUATED'"
        data-testid="incidents-not-evaluated"
      >
        {{ OVERVIEW_LABELS.incidentsNotEvaluated(incidents.reasonCode) }}
      </p>
      <p
        v-else-if="!incidents.overviewItems.length && !incidents.moreItems.length && !incidents.omittedCount"
        data-testid="incidents-empty"
      >
        {{ OVERVIEW_LABELS.incidentsEmpty }}
      </p>
      <template v-else>
        <p class="muted">
          {{ OVERVIEW_LABELS.incidentsLead }}
        </p>
        <ol
          class="overview-incidents"
          data-testid="incidents-list"
        >
          <li
            v-for="card in incidents.overviewItems"
            :id="`incident-${card.id}`"
            :key="card.id"
            class="incident-card"
            tabindex="-1"
            data-testid="incident-card"
            :data-priority="card.priority"
            :data-family="card.family"
          >
            <div class="incident-card__head">
              <span class="incident-card__rank">{{ OVERVIEW_LABELS.incidentRank(card.rank) }}</span>
              <span
                class="incident-card__priority"
                :data-priority="card.priority"
              >{{ card.priorityLabel }}</span>
              <span class="incident-card__family">{{ card.familyLabel }}</span>
              <strong class="incident-card__title">{{ card.title }}</strong>
            </div>
            <p class="incident-card__summary">
              {{ card.summary }}
            </p>
            <p class="incident-card__meta muted">
              <span>{{ card.scopeLabel }}</span>
              <span>{{ OVERVIEW_LABELS.incidentWindow(card.windowId) }}</span>
              <span>{{ card.intervalText }}</span>
              <span>{{ OVERVIEW_LABELS.incidentFindings(card.findingCount) }}</span>
              <span v-if="card.refsTruncated">{{ OVERVIEW_LABELS.incidentRefsTruncated }}</span>
            </p>
            <div
              v-if="card.coincidentWith.length"
              class="incident-card__coincident"
            >
              <span class="muted">{{ OVERVIEW_LABELS.incidentCoincidentLabel }}</span>
              <button
                v-for="link in card.coincidentWith"
                :key="link.incidentId"
                type="button"
                class="incident-chip"
                data-testid="incident-coincident"
                :disabled="!link.inStored"
                @click="jumpToIncident(link.incidentId)"
              >
                {{ link.targetTitle ?? link.incidentId.slice(0, 18) }} ({{ link.basisLabel }})
              </button>
            </div>
            <div class="incident-card__actions">
              <button
                v-if="card.primaryAction"
                type="button"
                data-testid="incident-open"
                :aria-label="`${card.primaryAction.label}: ${card.title}`"
                @click="runAction(card.primaryAction)"
              >
                {{ card.primaryAction.label }}
              </button>
            </div>
            <details class="incident-card__details">
              <summary>{{ OVERVIEW_LABELS.incidentNegativeSummary(card.negativeEvidence.length) }}</summary>
              <p
                v-if="!card.negativeEvidence.length"
                class="muted"
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
            <details class="incident-card__details">
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
                    class="incident-next-btn"
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
            class="incidents-more-btn"
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
            class="overview-incidents overview-incidents--overflow"
            data-testid="incidents-overflow-list"
          >
            <li
              v-for="card in incidents.moreItems"
              :id="`incident-${card.id}`"
              :key="card.id"
              class="incident-card incident-card--compact"
              tabindex="-1"
              data-testid="incident-overflow-card"
              :data-priority="card.priority"
              :data-family="card.family"
            >
              <div class="incident-card__head">
                <span class="incident-card__rank">{{ OVERVIEW_LABELS.incidentRank(card.rank) }}</span>
                <span
                  class="incident-card__priority"
                  :data-priority="card.priority"
                >{{ card.priorityLabel }}</span>
                <span class="incident-card__family">{{ card.familyLabel }}</span>
                <strong class="incident-card__title">{{ card.title }}</strong>
              </div>
              <p class="incident-card__summary">
                {{ card.summary }}
              </p>
              <p class="incident-card__meta muted">
                <span>{{ card.scopeLabel }}</span>
                <span>{{ OVERVIEW_LABELS.incidentWindow(card.windowId) }}</span>
                <span>{{ card.intervalText }}</span>
                <span>{{ OVERVIEW_LABELS.incidentFindings(card.findingCount) }}</span>
              </p>
              <div class="incident-card__actions">
                <button
                  v-if="card.primaryAction"
                  type="button"
                  data-testid="incident-overflow-open"
                  @click="runAction(card.primaryAction)"
                >
                  {{ card.primaryAction.label }}
                </button>
              </div>
            </li>
          </ol>
        </template>
        <p
          v-if="incidents.omittedCount > 0"
          class="muted"
          data-testid="incidents-omitted"
        >
          {{ OVERVIEW_LABELS.incidentOmittedNote(incidents.omittedCount) }}
        </p>
      </template>
    </section>

    <section
      v-if="showAttentionSection"
      id="overview-attention"
      class="panel"
      aria-labelledby="overview-attention-title"
    >
      <h2 id="overview-attention-title">
        {{ OVERVIEW_LABELS.attentionTitle }}
      </h2>
      <p class="muted">
        {{ attentionLeadText }}
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
      v-if="capacity"
      id="overview-capacity"
      class="panel overview-capacity"
      data-testid="capacity-block"
      aria-labelledby="overview-capacity-title"
    >
      <h2 id="overview-capacity-title">
        {{ OVERVIEW_LABELS.capacityTitle }}
      </h2>
      <p class="muted">
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
      <h3>{{ OVERVIEW_LABELS.capacityChartTitle }}</h3>
      <p class="muted">
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
        class="table-wrap"
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
      <p class="muted">
        {{ capacity.legend }}
      </p>
      <p
        v-if="capacity.smallSample"
        class="muted"
        data-testid="capacity-block-small-sample-note"
        role="note"
      >
        {{ CAPACITY_LABELS.smallSampleNote }}
      </p>
      <button
        type="button"
        data-testid="capacity-block-open"
        :aria-label="OVERVIEW_LABELS.capacityOpenAria"
        @click="emit('navigate', { tab: 'tables', targetId: 'capacity-results' })"
      >
        {{ OVERVIEW_LABELS.openCapacity }}
      </button>
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
        v-if="staged"
        class="muted"
        data-testid="metrics-whole-run-note"
      >
        {{ OVERVIEW_LABELS.metricsWholeRunNote }}
      </p>
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
      v-if="trends"
      id="overview-trends"
      class="panel"
      aria-labelledby="overview-trends-title"
      data-testid="overview-trends"
    >
      <div class="overview-section-head">
        <h2 id="overview-trends-title">
          {{ OVERVIEW_LABELS.trendsTitle }}
        </h2>
        <button
          type="button"
          data-testid="overview-trends-table-btn"
          @click="emit('navigate', { tab: 'tables', targetId: 'trend-results' })"
        >
          {{ OVERVIEW_LABELS.openTrends }}
        </button>
      </div>
      <p class="muted">
        {{ OVERVIEW_LABELS.trendsLead }}
      </p>
      <p data-testid="overview-trends-summary">
        {{ OVERVIEW_LABELS.trendsSummaryCounts(trends.total, trends.observed, trends.notMaterial, trends.insufficient, trends.unavailable) }}
      </p>
      <p
        v-if="!trends.cards.length"
        class="muted"
      >
        {{ OVERVIEW_LABELS.trendsEmpty }}
      </p>
      <div
        v-else
        class="overview-card-grid"
      >
        <article
          v-for="card in trends.cards"
          :key="card.id"
          class="overview-mini-card"
          data-testid="overview-trend-card"
        >
          <div class="overview-mini-card__head">
            <strong>{{ card.seriesId }}</strong>
            <span class="overview-badge">{{ card.directionLabel }}</span>
          </div>
          <p class="muted">
            <span v-if="card.entity">{{ card.entity }} · </span>{{ card.windowId }}
          </p>
          <p class="overview-mini-card__stats">
            <span>{{ OVERVIEW_LABELS.trendsSlope(card.slope, card.unit) }}</span>
            <span>{{ OVERVIEW_LABELS.trendsShift(card.shift, card.unit) }}</span>
            <span>{{ OVERVIEW_LABELS.trendsMedian(card.median, card.unit) }}</span>
          </p>
          <TimeChart
            v-if="sparklineLane(card.seriesId, 'warn') && sparklineFrame"
            :lane="sparklineLane(card.seriesId, 'warn')!"
            :frame="sparklineFrame"
            :stages="[]"
            :flags="{ timeLabels: false, stageLabels: false, sparkline: true }"
          />
          <button
            type="button"
            data-testid="overview-trend-open"
            @click="emit('navigate', { tab: 'deep', targetId: 'deep-title', stageId: card.windowId, seriesId: card.seriesId })"
          >
            {{ OVERVIEW_LABELS.trendsOpenDeep }}
          </button>
        </article>
      </div>
    </section>

    <section
      v-if="platform.length"
      id="overview-platform"
      class="panel"
      aria-labelledby="overview-platform-title"
      data-testid="overview-platform"
    >
      <h2 id="overview-platform-title">
        {{ OVERVIEW_LABELS.platformTitle }}
      </h2>
      <p class="muted">
        {{ OVERVIEW_LABELS.platformLead }}
      </p>
      <div class="overview-card-grid">
        <article
          v-for="tile in platform"
          :key="tile.seriesId"
          class="overview-mini-card"
          data-testid="overview-platform-tile"
          :data-status="tile.status"
        >
          <div class="overview-mini-card__head">
            <strong>{{ tile.seriesId }}</strong>
            <span
              class="status-text"
              :data-status="tile.status === 'DIAGNOSTIC' ? 'WARN' : tile.status"
            >{{ tile.statusLabel }}</span>
          </div>
          <p class="muted">
            {{ tile.entity }} · {{ tile.metric }} · {{ tile.windowId }}
          </p>
          <p class="overview-mini-card__stats">
            <span>{{ OVERVIEW_LABELS.platformPeak(tile.peakText, tile.unit) }}</span>
            <span v-if="tile.missingCells > 0">{{ OVERVIEW_LABELS.platformMissing(tile.missingCells) }}</span>
          </p>
          <TimeChart
            v-if="sparklineLane(tile.seriesId, tile.status === 'FAIL' ? 'fail' : tile.status === 'DIAGNOSTIC' ? 'warn' : 'brand') && sparklineFrame"
            :lane="sparklineLane(tile.seriesId, tile.status === 'FAIL' ? 'fail' : tile.status === 'DIAGNOSTIC' ? 'warn' : 'brand')!"
            :frame="sparklineFrame"
            :stages="[]"
            :flags="{ timeLabels: false, stageLabels: false, sparkline: true }"
          />
          <button
            type="button"
            data-testid="overview-platform-open"
            @click="emit('navigate', { tab: 'deep', targetId: 'deep-title', stageId: tile.windowId, seriesId: tile.seriesId })"
          >
            {{ OVERVIEW_LABELS.platformOpenDeep }}
          </button>
        </article>
      </div>
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
        :stages="stages"
        :p95-thresholds="p95Thresholds"
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
  grid-template-columns: minmax(0, 1fr);
  gap: 24px;
  min-width: 0;
}

.overview-panel > * {
  min-width: 0;
}

.overview-section-head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 8px 12px;
}

.overview-section-head button {
  min-width: 44px;
  min-height: 44px;
}

.overview-stage-ribbon {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 8px;
}

.overview-stage-pill {
  display: grid;
  gap: 2px;
  min-width: 44px;
  min-height: 44px;
  padding: 8px 12px;
  text-align: left;
  border: 1px solid var(--border);
  border-radius: var(--shell-radius, 8px);
  background: var(--surface-inset);
  color: var(--text);
  overflow-wrap: anywhere;
}

.overview-stage-pill--steady {
  border-color: var(--brand);
  background: var(--info-bg);
}

.overview-stage-pill__meta {
  font-size: 12px;
  color: var(--text-muted);
}

.overview-incidents {
  display: grid;
  gap: 12px;
  list-style: none;
  margin: 12px 0 0;
  padding: 0;
}

.incident-card {
  display: grid;
  gap: 8px;
  border: 1px solid var(--border);
  border-left: 3px solid var(--border-strong);
  border-radius: var(--shell-radius, 8px);
  padding: 12px;
  background: var(--surface);
  min-width: 0;
  overflow-wrap: anywhere;
}

.incident-card[data-priority="HIGH"] {
  border-left-color: var(--fail);
}

.incident-card[data-priority="MEDIUM"] {
  border-left-color: var(--warn);
}

.incident-card[data-priority="LOW"] {
  border-left-color: var(--brand);
}

.incident-card__head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px 10px;
}

.incident-card__rank {
  font-family: "Cascadia Mono", Consolas, monospace;
  font-weight: 700;
}

.incident-card__priority,
.incident-card__family {
  border-radius: 999px;
  padding: 2px 8px;
  font-size: 12px;
  font-weight: 700;
}

.incident-card__priority[data-priority="HIGH"] {
  color: var(--fail);
  background: var(--fail-bg);
}

.incident-card__priority[data-priority="MEDIUM"] {
  color: var(--warn);
  background: var(--warn-bg);
}

.incident-card__priority[data-priority="LOW"] {
  color: var(--brand);
  background: var(--info-bg);
}

.incident-card__family {
  color: var(--text-muted);
  background: var(--surface-muted);
}

.incident-card__summary {
  margin: 0;
}

.incident-card__meta {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 12px;
  margin: 0;
  font-size: 12px;
}

.incident-card__coincident {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px 8px;
}

.incident-card__actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.incident-card button,
.incidents-more-btn {
  justify-self: start;
  min-width: 44px;
  min-height: 44px;
}

.incident-chip {
  min-height: 44px;
  max-width: 100%;
  padding: 6px 10px;
  font-size: 12px;
  text-align: left;
  overflow-wrap: anywhere;
}

.incident-card__details {
  border-top: 1px solid var(--border);
  padding-top: 8px;
}

.incident-card__details summary {
  display: flex;
  align-items: center;
  min-height: 44px;
  cursor: pointer;
  font-weight: 600;
}

.incident-card__details ul {
  display: grid;
  gap: 6px;
  margin: 8px 0 0;
  padding-left: 20px;
}

.incident-next-btn {
  margin-top: 4px;
  display: inline-flex;
  align-items: center;
}

.incidents-more-btn {
  margin-top: 12px;
}

.overview-card-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: 12px;
  margin-top: 12px;
}

.overview-mini-card {
  display: grid;
  gap: 8px;
  padding: 12px;
  border: 1px solid var(--border);
  border-radius: var(--shell-radius, 8px);
  background: var(--surface-inset);
  min-width: 0;
  overflow-wrap: anywhere;
}

.overview-mini-card[data-status="FAIL"] {
  border-left: 3px solid var(--fail);
}

.overview-mini-card[data-status="NO_VERDICT"],
.overview-mini-card[data-status="DIAGNOSTIC"] {
  border-left: 3px solid var(--warn);
}

.overview-mini-card[data-status="PASS"] {
  border-left: 3px solid var(--pass);
}

.overview-mini-card__head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 6px;
}

.overview-mini-card__stats {
  display: grid;
  gap: 2px;
  margin: 0;
  font-size: 12px;
}

.overview-mini-card button {
  justify-self: start;
  min-width: 44px;
  min-height: 44px;
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

.overview-capacity__verdict {
  margin: 8px 0;
  font-size: 1.25rem;
  font-weight: 700;
}

.overview-capacity {
  min-width: 0;
}

.overview-capacity table {
  min-width: 24rem;
}

.overview-capacity__chart {
  display: grid;
  gap: 8px;
  margin-bottom: 12px;
}

.overview-capacity__label {
  display: flex;
  flex-wrap: wrap;
  gap: 2px 12px;
  margin: 0;
}

.overview-capacity__row svg {
  display: block;
  width: 100%;
  height: 24px;
  background: var(--surface-inset);
}

.overview-capacity__rect--pass {
  fill: var(--pass);
}

.overview-capacity__rect--fail {
  fill: var(--fail);
}

.overview-capacity__rect--unverified {
  fill: none;
  stroke: var(--text-muted);
  stroke-width: 2;
  stroke-dasharray: 4 3;
}

.overview-capacity__achieved {
  stroke: var(--text);
  stroke-width: 3;
}

.overview-capacity__bound {
  stroke: var(--brand);
  stroke-width: 2;
}

.overview-capacity__bound--lower {
  stroke-dasharray: 6 3;
}

.overview-capacity__bound--upper {
  stroke-dasharray: 2 3;
}

.overview-capacity__mark {
  margin-inline-start: 0.5em;
  font-style: italic;
}

[data-testid="capacity-block-open"] {
  justify-self: start;
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
