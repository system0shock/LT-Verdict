<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { AnalysisResult } from '../types'
import { compareFrame, compareLanes, compareReadout, compareStops, nearestStopIndex } from './compareChart'
import { OVERVIEW_LABELS } from './labels'
import { COMPARE_LABELS, type CompareLabels } from './labels.compare'
import { formatNumber, type LoadSeries, type TrackKey } from './overview'
import TimeChart from './TimeChart.vue'
import { plotFraction } from './timeChart'

const props = withDefaults(defineProps<{
  currentSeries: LoadSeries
  baselineSeries: LoadSeries
  result?: AnalysisResult | null
  labels?: CompareLabels
}>(), {
  result: null,
  labels: () => COMPARE_LABELS,
})

const cursor = ref<number | null>(null)
const tracks: Array<{ key: TrackKey; label: string; unit: string }> = [
  { key: 'rps', label: OVERVIEW_LABELS.trackRps, unit: ` ${OVERVIEW_LABELS.unitRps}` },
  { key: 'errors', label: OVERVIEW_LABELS.trackErrors, unit: '' },
  { key: 'p95', label: OVERVIEW_LABELS.trackP95, unit: ` ${OVERVIEW_LABELS.unitMs}` },
]

watch(() => [props.currentSeries, props.baselineSeries], () => { cursor.value = null })

const frame = computed(() => compareFrame(props.currentSeries, props.baselineSeries))
const lanesMap = computed(() => compareLanes(props.currentSeries, props.baselineSeries, props.result, props.labels))
const stops = computed(() => compareStops(props.currentSeries, props.baselineSeries))
const cursorMs = computed(() => cursor.value === null ? null : stops.value[cursor.value] ?? null)
const readout = computed(() => cursorMs.value === null ? null : compareReadout(props.currentSeries, props.baselineSeries, cursorMs.value, props.labels))
const lanes = computed(() => tracks.map((track) => ({
  ...track,
  lane: lanesMap.value[track.key],
  flags: { timeLabels: track.key === 'p95', stageLabels: false },
})))

function onPointer(event: PointerEvent) {
  const rect = (event.currentTarget as HTMLElement).getBoundingClientRect()
  if (rect.width <= 0) return
  const index = nearestStopIndex(stops.value, frame.value, plotFraction(event.clientX, rect))
  if (index !== -1) cursor.value = index
}

function focusCursor(event: FocusEvent) {
  if (cursor.value === null && stops.value.length) cursor.value = Number((event.target as HTMLInputElement).value)
}

function inputCursor(event: Event) {
  cursor.value = Number((event.target as HTMLInputElement).value)
}
</script>

<template>
  <div
    class="compare-chart"
    data-testid="compare-cursor-chart"
    role="group"
    :aria-label="labels.chartsAria"
  >
    <p
      class="muted"
      data-testid="compare-chart-legend"
    >
      {{ labels.chartsLegend }}
    </p>
    <div
      class="compare-chart__plot"
      data-testid="compare-chart-plot"
      @pointermove="onPointer"
      @pointerdown="onPointer"
    >
      <div
        v-for="track in lanes"
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
          :stages="[]"
          :flags="track.flags"
          :cursor-ms="cursorMs"
          :cursor-value="readout ? `${readout.current[track.key]}${readout.current[track.key] === '—' ? '' : track.unit}` : ''"
          :cursor-time="track.key === 'p95' ? readout?.time ?? null : null"
        />
      </div>
    </div>
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

.compare-chart__plot {
  display: grid;
  gap: 8px;
  min-width: 0;
  touch-action: pan-y;
}

.compare-chart__track {
  min-width: 0;
}

.compare-chart__head {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 12px;
  margin: 0;
  min-width: 0;
}

.compare-chart__axis-title {
  margin: 0 16px 0 56px;
  text-align: center;
}

.compare-chart__slider {
  width: 100%;
  min-height: 44px;
  margin: 0;
}
</style>
