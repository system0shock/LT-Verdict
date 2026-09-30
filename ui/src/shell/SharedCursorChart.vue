<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { OVERVIEW_LABELS } from './labels'
import { cursorFraction, cursorSummary, formatNumber, formatOffset, nearestIndex, trackPoints, type LoadSeries, type TrackKey } from './overview'

const props = defineProps<{ series: LoadSeries }>()
const cursor = ref<number | null>(null)
const tracks: Array<{ key: TrackKey; label: string }> = [
  { key: 'rps', label: OVERVIEW_LABELS.trackRps },
  { key: 'errors', label: OVERVIEW_LABELS.trackErrors },
  { key: 'p95', label: OVERVIEW_LABELS.trackP95 },
]

watch(() => props.series, () => { cursor.value = null })

const readout = computed(() => cursor.value === null ? null : cursorSummary(props.series, cursor.value))
const cursorX = computed(() => cursor.value === null ? 0 : Math.round(cursorFraction(props.series, cursor.value) * 1000 * 100) / 100)
const firstOffset = computed(() => formatOffset(props.series.points[0]?.startMs ?? 0))
const lastOffset = computed(() => formatOffset(props.series.points[props.series.points.length - 1]?.startMs ?? 0))

function onPointer(event: PointerEvent) {
  const rect = (event.currentTarget as HTMLElement).getBoundingClientRect()
  if (rect.width <= 0) return
  const index = nearestIndex(props.series, (event.clientX - rect.left) / rect.width)
  if (index !== -1) cursor.value = index
}

function focusCursor(event: FocusEvent) {
  if (cursor.value === null) cursor.value = Number((event.target as HTMLInputElement).value)
}

function inputCursor(event: Event) {
  cursor.value = Number((event.target as HTMLInputElement).value)
}
</script>

<template>
  <div
    class="shared-chart"
    data-testid="shared-cursor-chart"
    role="group"
    :aria-label="OVERVIEW_LABELS.chartAria"
  >
    <div
      class="shared-chart__plot"
      data-testid="chart-plot"
      @pointermove="onPointer"
      @pointerdown="onPointer"
    >
      <div
        v-for="track in tracks"
        :key="track.key"
        class="shared-chart__track"
        :class="`shared-chart__track--${track.key}`"
        :data-testid="`track-${track.key}`"
      >
        <p class="shared-chart__head">
          <span>{{ track.label }}</span>
          <strong
            :data-testid="`track-value-${track.key}`"
            :data-value="readout ? readout.raw[track.key] : ''"
          >{{ readout ? readout[track.key] : '-' }}<template v-if="track.key === 'p95' && readout"> {{ OVERVIEW_LABELS.unitMs }}</template></strong>
          <span class="muted">{{ OVERVIEW_LABELS.trackMax(formatNumber(series.max[track.key])) }}</span>
        </p>
        <svg
          viewBox="0 0 1000 80"
          preserveAspectRatio="none"
          aria-hidden="true"
          focusable="false"
        >
          <line
            class="shared-chart__axis"
            x1="0"
            y1="76"
            x2="1000"
            y2="76"
          />
          <polyline
            v-for="(points, index) in trackPoints(series, track.key, 1000, 80)"
            :key="index"
            :data-testid="`track-line-${track.key}`"
            :points="points"
            fill="none"
            vector-effect="non-scaling-stroke"
            stroke-linecap="round"
          />
          <line
            v-if="readout"
            :data-testid="`cursor-line-${track.key}`"
            :data-x="cursorX"
            :x1="cursorX"
            :x2="cursorX"
            y1="0"
            y2="80"
            class="shared-chart__cursor"
            vector-effect="non-scaling-stroke"
          />
        </svg>
      </div>
    </div>
    <p class="shared-chart__axis-labels">
      <span>{{ firstOffset }}</span><span>{{ OVERVIEW_LABELS.axisLabel }}</span><span>{{ lastOffset }}</span>
    </p>
    <p data-testid="cursor-time">
      {{ readout ? OVERVIEW_LABELS.cursorTime(readout.time) : OVERVIEW_LABELS.cursorNone }}
    </p>
    <input
      type="range"
      data-testid="chart-cursor"
      class="shared-chart__slider"
      :aria-label="OVERVIEW_LABELS.cursorLabel"
      aria-describedby="chart-cursor-hint"
      min="0"
      :max="series.points.length - 1"
      step="1"
      :value="cursor ?? 0"
      :aria-valuetext="readout ? readout.text : OVERVIEW_LABELS.cursorNone"
      @focus="focusCursor"
      @input="inputCursor"
    >
    <p
      id="chart-cursor-hint"
      class="muted"
    >
      {{ OVERVIEW_LABELS.cursorHint }}
    </p>
    <p
      class="muted"
      data-testid="load-summary"
    >
      {{ OVERVIEW_LABELS.loadSummary(series.points.length, series.rollupSeconds) }}
    </p>
    <p
      v-if="series.missingIntervals > 0"
      class="muted"
      data-testid="load-gaps"
    >
      {{ OVERVIEW_LABELS.loadGaps(series.missingIntervals) }}
    </p>
  </div>
</template>

<style scoped>
.shared-chart {
  display: grid;
  gap: 8px;
}

.shared-chart__plot {
  display: grid;
  gap: 8px;
  touch-action: pan-y;
}

.shared-chart__track svg {
  display: block;
  width: 100%;
  height: 80px;
  background: var(--surface-inset);
}

.shared-chart__track polyline {
  stroke-width: 2;
}

.shared-chart__track--rps polyline {
  stroke: var(--brand);
}

.shared-chart__track--errors polyline {
  stroke: var(--fail);
}

.shared-chart__track--p95 polyline {
  stroke: var(--warn);
}

.shared-chart__cursor {
  stroke: var(--text);
  stroke-width: 1;
}

.shared-chart__axis {
  stroke: var(--border);
}

.shared-chart__head {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 12px;
  margin: 0;
}

.shared-chart__axis-labels {
  display: flex;
  justify-content: space-between;
  gap: 8px;
  margin: 0;
}

.shared-chart__slider {
  width: 100%;
  min-height: 44px;
  margin: 0;
}
</style>
