<script setup lang="ts">
import { computed, ref, useId, watch } from 'vue'
import { DEEP_LABELS } from './labels'
import { formatNumber, formatOffset } from './overview'
import { cursorReadouts, cursorStops, nearestTime, trackPath, trackScale, valueY, type DeepTrack, type TimeAxis } from './deep'

const props = defineProps<{ tracks: DeepTrack[]; axis: TimeAxis; originMs: number }>()
const cursorMs = ref<number | null>(null)
const hintId = useId()
const stops = computed(() => cursorStops(props.tracks).filter((time) => time >= props.axis.fromMs && time < props.axis.toMs))
const readouts = computed(() => cursorMs.value === null ? [] : cursorReadouts(props.tracks, cursorMs.value))
const relativeMs = computed(() => cursorMs.value === null ? null : cursorMs.value - props.originMs)
const cursorX = computed(() => cursorMs.value === null || props.axis.toMs <= props.axis.fromMs ? 0 : (cursorMs.value - props.axis.fromMs) / (props.axis.toMs - props.axis.fromMs) * 1000)
const stopIndex = computed(() => cursorMs.value === null ? 0 : Math.max(0, stops.value.findLastIndex((time) => time <= cursorMs.value!)))
const offset = (ms: number) => ms < 0 ? `-${formatOffset(-ms)}` : formatOffset(ms)
const ariaText = computed(() => {
  if (relativeMs.value === null) return DEEP_LABELS.cursorNone
  const time = DEEP_LABELS.cursorText(formatNumber(relativeMs.value, 0), offset(relativeMs.value))
  const values = props.tracks.map((track) => {
    const item = readouts.value.find((row) => row.key === track.key)
    const value = item?.gap ? DEEP_LABELS.gap : `${item?.text ?? DEEP_LABELS.gap} ${track.unit}`
    const partial = item?.partial ? ` (${DEEP_LABELS.partialCell(item.observed!, item.total!)})` : ''
    return `${track.label}: ${value}${partial}`
  })
  return [time, ...values].join('; ')
})
watch(() => [props.axis.fromMs, props.axis.toMs], () => { cursorMs.value = null })

function onPointer(event: PointerEvent) {
  const rect = (event.currentTarget as HTMLElement).getBoundingClientRect()
  if (rect.width > 0) cursorMs.value = nearestTime(props.axis, (event.clientX - rect.left) / rect.width)
}
function inputCursor(event: Event) {
  cursorMs.value = stops.value[Number((event.target as HTMLInputElement).value)] ?? null
}
function focusCursor() {
  if (cursorMs.value === null) cursorMs.value = stops.value[stopIndex.value] ?? null
}
function paths(track: DeepTrack) {
  return trackPath(track, props.axis, 1000, 80)
}
function valueText(track: DeepTrack) {
  const item = readout(track.key)
  if (!item) return DEEP_LABELS.cursorNone
  return item.gap ? DEEP_LABELS.gap : `${item.text} ${track.unit}`
}
function readout(key: string) {
  return readouts.value.find((item) => item.key === key)
}
</script>

<template>
  <div
    class="deep-chart"
    data-testid="deep-chart"
    role="group"
    :aria-label="DEEP_LABELS.chartAria"
  >
    <div
      class="deep-plot"
      data-testid="deep-plot"
      @pointermove="onPointer"
      @pointerdown="onPointer"
    >
      <div
        v-for="track in tracks"
        :key="track.key"
        class="deep-track"
        :class="track.kind === 'resource' ? 'resource' : track.key"
        :data-testid="'deep-track-' + track.key"
      >
        <p class="deep-head">
          <span class="track-name">{{ track.label }}<template v-if="track.reducer"> ({{ DEEP_LABELS.reducerLabels[track.reducer] }})</template></span>
          <strong
            :data-testid="'track-value-' + track.key"
            :data-value="readout(track.key)?.raw ?? ''"
          >{{ valueText(track) }}</strong>
          <span
            v-if="readout(track.key)?.partial"
            class="muted"
          >{{ DEEP_LABELS.partialCell(readout(track.key)!.observed!, readout(track.key)!.total!) }}</span>
          <span class="muted">{{ DEEP_LABELS.trackMax(formatNumber(track.max)) }} {{ track.unit }}</span>
          <span
            v-for="threshold in track.thresholds"
            :key="threshold.ruleId"
            class="threshold-text"
            :data-testid="'deep-threshold-label-' + threshold.ruleId"
          >{{ DEEP_LABELS.thresholdLabel(threshold.operator, formatNumber(threshold.value), track.unit) }}</span>
        </p>
        <svg
          viewBox="0 0 1000 80"
          preserveAspectRatio="none"
          aria-hidden="true"
          focusable="false"
        >
          <line
            class="axis"
            x1="0"
            :y1="valueY(trackScale(track), 0, 80)"
            x2="1000"
            :y2="valueY(trackScale(track), 0, 80)"
          />
          <template
            v-for="(points, index) in paths(track)"
            :key="index"
          >
            <polyline
              :data-testid="'track-line-' + track.key"
              :points="points"
              fill="none"
              vector-effect="non-scaling-stroke"
              stroke-linecap="round"
            />
            <circle
              v-if="!points.includes(' ')"
              :data-testid="'track-dot-' + track.key"
              :cx="Number(points.split(',')[0])"
              :cy="Number(points.split(',')[1])"
              r="3"
              vector-effect="non-scaling-stroke"
            />
          </template>
          <line
            v-for="threshold in track.thresholds"
            :key="threshold.ruleId"
            class="threshold"
            :data-testid="'deep-threshold-' + threshold.ruleId"
            x1="0"
            :y1="valueY(trackScale(track), threshold.value, 80)"
            x2="1000"
            :y2="valueY(trackScale(track), threshold.value, 80)"
            stroke-dasharray="5 4"
            vector-effect="non-scaling-stroke"
          />
          <line
            v-if="cursorMs !== null"
            class="cursor"
            :data-testid="'cursor-line-' + track.key"
            :x1="cursorX"
            y1="0"
            :x2="cursorX"
            y2="80"
            vector-effect="non-scaling-stroke"
          />
        </svg>
      </div>
    </div>
    <p class="axis-labels">
      <span>{{ offset(axis.fromMs - originMs) }}</span><span>{{ DEEP_LABELS.axisLabel }}</span><span>{{ offset(axis.toMs - originMs) }}</span>
    </p>
    <p
      data-testid="deep-cursor-time"
      :data-ms="relativeMs"
      :data-epoch-ms="cursorMs"
    >
      {{ relativeMs === null ? DEEP_LABELS.cursorNone : DEEP_LABELS.cursorTime(formatNumber(relativeMs, 0), offset(relativeMs)) }}
    </p>
    <input
      v-if="stops.length"
      type="range"
      class="slider"
      data-testid="deep-cursor"
      min="0"
      :max="stops.length - 1"
      step="1"
      :value="stopIndex"
      :aria-label="DEEP_LABELS.cursorLabel"
      :aria-describedby="hintId"
      :aria-valuetext="ariaText"
      @focus="focusCursor"
      @input="inputCursor"
    >
    <p
      :id="hintId"
      class="muted"
    >
      {{ DEEP_LABELS.cursorHint }}
    </p>
  </div>
</template>

<style scoped>
.deep-chart,.deep-plot { display:grid; gap:8px; min-width:0; }
.deep-plot { touch-action:pan-y; }
.deep-track { min-width:0; }
.deep-track svg { display:block; width:100%; height:80px; background:var(--surface-inset); }
.deep-track polyline { stroke-width:2; }
.deep-track.load-rps polyline,.deep-track.load-rps circle { stroke:var(--brand); fill:var(--brand); }
.deep-track.load-p95 polyline,.deep-track.load-p95 circle { stroke:var(--warn); fill:var(--warn); }
.deep-track.resource polyline,.deep-track.resource circle { stroke:var(--text); fill:var(--text); }
.axis { stroke:var(--border); }
.threshold { stroke:var(--fail); stroke-width:1.5; }
.threshold-text { color:var(--fail); }
.cursor { stroke:var(--text); stroke-width:1; }
.deep-head { display:flex; flex-wrap:wrap; gap:4px 12px; margin:0 0 4px; overflow-wrap:anywhere; }
.track-name { font-weight:600; }
.axis-labels { display:flex; justify-content:space-between; flex-wrap:wrap; gap:8px; margin:0; }
.slider { width:100%; min-height:44px; margin:0; }
</style>
