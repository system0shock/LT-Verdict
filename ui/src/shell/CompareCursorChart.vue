<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import type { AnalysisResult } from '../types'
import { compareFrame, compareLanes, compareReadout, compareStops, nearestStopIndex } from './compareChart'
import { OVERVIEW_LABELS } from './labels'
import { COMPARE_LABELS, type CompareLabels } from './labels.compare'
import { formatNumber, type LoadSeries, type TrackKey } from './overview'
import ChartFloatingTooltip, { type FloatingTooltipSection } from './ChartFloatingTooltip.vue'
import TimeChart from './TimeChart.vue'
import { plotFraction, stageBands, valueAtTime, type ChartLane } from './timeChart'

export interface CompareResourceLane {
  id: string
  label: string
  unit: string
  lane: ChartLane
}

const props = withDefaults(defineProps<{
  currentSeries: LoadSeries
  baselineSeries: LoadSeries
  resourceLanes?: CompareResourceLane[]
  result?: AnalysisResult | null
  labels?: CompareLabels
}>(), {
  resourceLanes: () => [],
  result: null,
  labels: () => COMPARE_LABELS,
})

const cursor = ref<number | null>(null)
const pointerPos = ref({ x: 0, y: 0 })
const hoverActive = ref(false)
const pinned = ref(false)
const viewMode = ref<'combined' | 'resources' | 'load'>('combined')

const tracks: Array<{ key: TrackKey; label: string; unit: string; color: string }> = [
  { key: 'rps', label: OVERVIEW_LABELS.trackRps, unit: ` ${OVERVIEW_LABELS.unitRps}`, color: '#60A5FA' },
  { key: 'errors', label: OVERVIEW_LABELS.trackErrors, unit: '', color: '#FF7B72' },
  { key: 'p95', label: OVERVIEW_LABELS.trackP95, unit: ` ${OVERVIEW_LABELS.unitMs}`, color: '#FBBF24' },
]

watch(() => [props.currentSeries, props.baselineSeries], () => {
  cursor.value = null
  pinned.value = false
  hoverActive.value = false
})

const hasResources = computed(() => props.resourceLanes.length > 0)
const effectiveMode = computed(() => (hasResources.value ? viewMode.value : 'load'))
const stages = computed(() => (props.result ? stageBands(props.result, 0) : []))
const frame = computed(() => compareFrame(props.currentSeries, props.baselineSeries))
const lanesMap = computed(() => compareLanes(props.currentSeries, props.baselineSeries, props.result, props.labels))
const stops = computed(() => compareStops(props.currentSeries, props.baselineSeries))
const cursorMs = computed(() => (cursor.value === null ? null : stops.value[cursor.value] ?? null))
const readout = computed(() => (cursorMs.value === null ? null : compareReadout(props.currentSeries, props.baselineSeries, cursorMs.value, props.labels)))

const visibleLoadLanes = computed(() => {
  if (effectiveMode.value === 'resources') return []
  const list = tracks.map((track) => ({
    ...track,
    lane: {
      ...lanesMap.value[track.key],
      bars: track.key === 'errors',
    },
  }))
  const showResourcesBelow = effectiveMode.value === 'combined' && hasResources.value
  return list.map((item, index) => ({
    ...item,
    flags: {
      timeLabels: !showResourcesBelow && index === list.length - 1,
      stageLabels: index === 0,
      plotHeight: item.key === 'errors' ? 76 : 108,
    },
  }))
})

const visibleResourceLanes = computed(() => {
  if (effectiveMode.value === 'load' || !hasResources.value) return []
  const list = props.resourceLanes
  return list.map((item, index) => ({
    ...item,
    flags: {
      timeLabels: index === list.length - 1,
      stageLabels: visibleLoadLanes.value.length === 0 && index === 0,
      plotHeight: 100,
    },
  }))
})

function formatDeltaPct(cur: number | null, base: number | null): string {
  if (cur === null || base === null || !Number.isFinite(cur) || !Number.isFinite(base) || base === 0) return ''
  const pct = Math.round(((cur - base) / Math.abs(base)) * 100)
  return `(${pct > 0 ? '+' : ''}${pct}%)`
}

const tooltipSections = computed<FloatingTooltipSection[]>(() => {
  if (cursorMs.value === null || !readout.value) return []
  const sections: FloatingTooltipSection[] = []
  if (visibleLoadLanes.value.length) {
    const rows = visibleLoadLanes.value.flatMap((track) => {
      const curVal = valueAtTime(track.lane.segments, cursorMs.value!)
      const baseVal = track.lane.baseline ? valueAtTime(track.lane.baseline, cursorMs.value!) : null
      const curText = readout.value!.current[track.key]
      const baseText = readout.value!.baseline[track.key]
      return [
        {
          key: `cur-${track.key}`,
          label: `${track.label} (${props.labels.chartsCurrent})`,
          value: curText === '—' ? '—' : `${curText}${track.unit}`,
          sub: formatDeltaPct(curVal, baseVal),
          color: track.color,
        },
        {
          key: `base-${track.key}`,
          label: `${track.label} (${props.labels.chartsBaseline})`,
          value: baseText === '—' ? '—' : `${baseText}${track.unit}`,
          color: '#94A3B8',
          dashed: true,
        },
      ]
    })
    sections.push({ title: 'Нагрузка и отклик', rows })
  }
  if (visibleResourceLanes.value.length) {
    const rows = visibleResourceLanes.value.flatMap((item) => {
      const curVal = valueAtTime(item.lane.segments, cursorMs.value!)
      const baseVal = item.lane.baseline ? valueAtTime(item.lane.baseline, cursorMs.value!) : null
      const unitSuffix = item.unit ? ` ${item.unit}` : ''
      return [
        {
          key: `rcur-${item.id}`,
          label: `${item.label} (${props.labels.chartsCurrent})`,
          value: curVal === null ? '—' : `${formatNumber(curVal, 1)}${unitSuffix}`,
          sub: formatDeltaPct(curVal, baseVal),
          color: '#C084FC',
        },
        {
          key: `rbase-${item.id}`,
          label: `${item.label} (${props.labels.chartsBaseline})`,
          value: baseVal === null ? '—' : `${formatNumber(baseVal, 1)}${unitSuffix}`,
          color: '#94A3B8',
          dashed: true,
        },
      ]
    })
    sections.push({ title: 'Аппаратные ресурсы', rows })
  }
  return sections
})

function onPointer(event: PointerEvent) {
  if (pinned.value && event.type === 'pointermove') return
  const hostEl = event.currentTarget as HTMLElement
  const rect = (hostEl.querySelector('.time-chart') ?? hostEl).getBoundingClientRect()
  if (rect.width <= 0) return
  const index = nearestStopIndex(stops.value, frame.value, plotFraction(event.clientX, rect))
  if (index !== -1) {
    cursor.value = index
    pointerPos.value = { x: event.clientX, y: event.clientY }
    hoverActive.value = true
  }
}

function onPointerLeave() {
  if (!pinned.value) hoverActive.value = false
}

function onPlotClick(event: MouseEvent) {
  if (cursor.value === null) return
  if (pinned.value) {
    pinned.value = false
    return
  }
  pointerPos.value = { x: event.clientX, y: event.clientY }
  hoverActive.value = true
  pinned.value = true
}

function unpin() {
  pinned.value = false
  hoverActive.value = false
}

function onKeyDown(event: KeyboardEvent) {
  if (event.key === 'Escape' && pinned.value) unpin()
}

function focusCursor(event: FocusEvent) {
  if (cursor.value === null && stops.value.length) cursor.value = Number((event.target as HTMLInputElement).value)
}

function inputCursor(event: Event) {
  cursor.value = Number((event.target as HTMLInputElement).value)
}

onMounted(() => window.addEventListener('keydown', onKeyDown))
onBeforeUnmount(() => window.removeEventListener('keydown', onKeyDown))
</script>

<template>
  <div
    class="compare-chart"
    data-testid="compare-cursor-chart"
    role="group"
    :aria-label="labels.chartsAria"
  >
    <div class="compare-chart__toolbar">
      <p
        class="muted"
        data-testid="compare-chart-legend"
      >
        {{ labels.chartsLegend }}
      </p>
      <div
        v-if="hasResources"
        class="compare-chart__modes"
        role="tablist"
        aria-label="Разрез графиков сравнения"
      >
        <button
          type="button"
          role="tab"
          :aria-selected="viewMode === 'combined'"
          data-testid="compare-mode-combined"
          @click="viewMode = 'combined'"
        >
          Отклик + Ресурсы
        </button>
        <button
          type="button"
          role="tab"
          :aria-selected="viewMode === 'resources'"
          data-testid="compare-mode-resources"
          @click="viewMode = 'resources'"
        >
          Аппаратные ресурсы ({{ resourceLanes.length }})
        </button>
        <button
          type="button"
          role="tab"
          :aria-selected="viewMode === 'load'"
          data-testid="compare-mode-load"
          @click="viewMode = 'load'"
        >
          Нагрузка
        </button>
      </div>
    </div>
    <div
      class="compare-chart__plot"
      data-testid="compare-chart-plot"
      @pointermove="onPointer"
      @pointerdown="onPointer"
      @pointerleave="onPointerLeave"
      @click="onPlotClick"
    >
      <div
        v-for="track in visibleLoadLanes"
        :key="track.key"
        class="compare-chart__track"
        :data-testid="`compare-track-${track.key}`"
      >
        <p class="compare-chart__head">
          <span>{{ track.label }}</span>
          <strong :data-testid="`compare-value-${track.key}`">
            {{ labels.chartsCurrent }}: {{ readout ? `${readout.current[track.key]}${readout.current[track.key] === '—' ? '' : track.unit}` : '—' }}
          </strong>
          <span
            class="muted"
            :data-testid="`compare-baseline-value-${track.key}`"
          >
            {{ labels.chartsBaseline }}: {{ readout ? `${readout.baseline[track.key]}${readout.baseline[track.key] === '—' ? '' : track.unit}` : '—' }}
          </span>
          <span class="muted">
            {{ OVERVIEW_LABELS.trackMax(formatNumber(Math.max(currentSeries.max[track.key], baselineSeries.max[track.key]))) }}
          </span>
        </p>
        <TimeChart
          :lane="track.lane"
          :frame="frame"
          :stages="stages"
          :flags="track.flags"
          :cursor-ms="cursorMs"
          :cursor-value="readout ? `${readout.current[track.key]}${readout.current[track.key] === '—' ? '' : track.unit}` : ''"
          :cursor-time="track.flags.timeLabels ? readout?.time ?? null : null"
          :hide-inline-value="hoverActive || pinned"
        />
      </div>

      <div
        v-for="res in visibleResourceLanes"
        :key="res.id"
        class="compare-chart__track"
        :data-testid="`compare-resource-track-${res.id}`"
      >
        <p class="compare-chart__head">
          <span>{{ res.label }}</span>
          <strong class="mono">
            {{ labels.chartsCurrent }}: {{ cursorMs !== null && valueAtTime(res.lane.segments, cursorMs) !== null ? `${formatNumber(valueAtTime(res.lane.segments, cursorMs)!, 1)} ${res.unit}` : '—' }}
          </strong>
          <span class="muted mono">
            {{ labels.chartsBaseline }}: {{ cursorMs !== null && res.lane.baseline && valueAtTime(res.lane.baseline, cursorMs) !== null ? `${formatNumber(valueAtTime(res.lane.baseline, cursorMs)!, 1)} ${res.unit}` : '—' }}
          </span>
        </p>
        <TimeChart
          :lane="res.lane"
          :frame="frame"
          :stages="stages"
          :flags="res.flags"
          :cursor-ms="cursorMs"
          :cursor-time="res.flags.timeLabels ? readout?.time ?? null : null"
          :hide-inline-value="hoverActive || pinned"
        />
      </div>
    </div>

    <ChartFloatingTooltip
      :visible="(hoverActive || pinned) && cursorMs !== null"
      :client-x="pointerPos.x"
      :client-y="pointerPos.y"
      :title="readout ? `+${readout.time}` : ''"
      meta="Текущий vs Baseline"
      :sections="tooltipSections"
      :pinned="pinned"
      @unpin="unpin"
    />

    <p class="compare-chart__axis-title">
      {{ labels.chartsAxisLabel }}
    </p>
    <p data-testid="compare-cursor-time">
      {{ readout ? labels.chartsCursorTime(readout.time) : labels.chartsCursorNone }}
    </p>
    <input
      v-if="stops.length"
      type="range"
      data-testid="compare-chart-cursor"
      class="compare-chart__slider"
      :aria-label="labels.chartsCursorLabel"
      aria-describedby="compare-chart-cursor-hint"
      min="0"
      :max="stops.length - 1"
      step="1"
      :value="cursor ?? 0"
      :aria-valuetext="readout ? readout.text : labels.chartsCursorNone"
      @focus="focusCursor"
      @input="inputCursor"
    >
    <p
      id="compare-chart-cursor-hint"
      class="muted"
    >
      {{ labels.chartsCursorHint }}
    </p>
  </div>
</template>

<style scoped>
.compare-chart {
  display: grid;
  gap: 8px;
  min-width: 0;
}

.compare-chart__toolbar {
  display: flex;
  flex-wrap: wrap;
  justify-content: space-between;
  align-items: center;
  gap: 8px 12px;
}

.compare-chart__modes {
  display: inline-flex;
  flex-wrap: wrap;
  max-width: 100%;
  gap: 2px;
  padding: 2px;
  border: 1px solid var(--border);
  border-radius: 7px;
  background: var(--surface-inset);
}

.compare-chart__modes button {
  height: 25px;
  min-height: 25px;
  min-width: 0;
  padding: 0 9px;
  border: 0;
  border-radius: 5px;
  background: transparent;
  color: var(--text-muted);
  font-size: 12px;
  font-weight: 500;
}

.compare-chart__modes button[aria-selected="true"] {
  background: var(--surface);
  color: var(--text);
  font-weight: 600;
}

.compare-chart__plot {
  display: grid;
  gap: 8px;
  min-width: 0;
  touch-action: pan-y;
  cursor: crosshair;
}

.compare-chart__track {
  min-width: 0;
  border: 1px solid var(--border);
  border-radius: 8px;
  background: var(--surface-inset);
  padding: 7px 10px 4px;
}

.compare-chart__head {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 12px;
  margin: 0 0 3px;
  font-size: 12.5px;
  min-width: 0;
}

.compare-chart__axis-title {
  margin: 0 16px 0 56px;
  text-align: center;
  font-size: 11px;
}

.compare-chart__slider {
  width: 100%;
  height: 22px;
  min-height: 22px;
  padding: 0;
  margin: 0;
  border: 0;
  background: transparent;
  accent-color: var(--brand);
}
</style>
