<script setup lang="ts">
import { computed, ref, useId, watch } from 'vue'
import { DEEP_LABELS } from './labels'
import { formatNumber, formatOffset } from './overview'
import { cursorReadouts, cursorStops, deepLane, nearestTime, type DeepTrack, type TimeAxis } from './deep'
import TimeChart from './TimeChart.vue'
import { plotFraction, type ChartStage } from './timeChart'

const props = withDefaults(defineProps<{
  tracks: DeepTrack[]
  primaryTrack?: DeepTrack | null
  axis: TimeAxis
  originMs: number
  stages?: ChartStage[]
}>(), { primaryTrack: null, stages: () => [] })
const cursorMs = ref<number | null>(null)
const hintId = useId()
const allTracks = computed(() => props.primaryTrack ? [props.primaryTrack, ...props.tracks] : props.tracks)
const stops = computed(() => cursorStops(allTracks.value).filter((time) => time >= props.axis.fromMs && time < props.axis.toMs))
const readouts = computed(() => cursorMs.value === null ? [] : cursorReadouts(allTracks.value, cursorMs.value))
const relativeMs = computed(() => cursorMs.value === null ? null : cursorMs.value - props.originMs)
const frame = computed(() => ({ ...props.axis, originMs: props.originMs }))
const lanes = computed(() => allTracks.value.map((track, index) => {
  const isPrimary = Boolean(props.primaryTrack && index === 0)
  const timeLabels = index === allTracks.value.length - 1
  const stageLabels = index === 0
  return {
    track,
    isPrimary,
    lane: deepLane(track, props.axis),
    flags: isPrimary
      ? { timeLabels, stageLabels, plotHeight: timeLabels ? 192 : 236, ySplits: 4 }
      : { timeLabels, stageLabels },
  }
}))
const stopIndex = computed(() => cursorMs.value === null ? 0 : Math.max(0, stops.value.findLastIndex((time) => time <= cursorMs.value!)))
const offset = (ms: number) => ms < 0 ? `-${formatOffset(-ms)}` : formatOffset(ms)
const ariaText = computed(() => {
  if (relativeMs.value === null) return DEEP_LABELS.cursorNone
  const time = DEEP_LABELS.cursorText(formatNumber(relativeMs.value, 0), offset(relativeMs.value))
  const values = allTracks.value.map((track) => {
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
  if (rect.width > 0) cursorMs.value = nearestTime(props.axis, plotFraction(event.clientX, rect))
}
function inputCursor(event: Event) {
  cursorMs.value = stops.value[Number((event.target as HTMLInputElement).value)] ?? null
}
function focusCursor() {
  if (cursorMs.value === null) cursorMs.value = stops.value[stopIndex.value] ?? null
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
      <template
        v-for="({ track, isPrimary, lane, flags }, laneIndex) in lanes"
        :key="track.key"
      >
        <p
          v-if="primaryTrack && tracks.length && laneIndex === 1"
          class="pinned-heading muted"
          data-testid="deep-pinned-heading"
        >
          {{ DEEP_LABELS.pinnedTitle }}
        </p>
        <div
          class="deep-track"
          :class="[track.kind === 'resource' ? 'resource' : track.key, { 'deep-track--primary': isPrimary }]"
          :data-testid="'deep-track-' + track.key"
          :data-primary="isPrimary ? 'true' : 'false'"
        >
          <p class="deep-head">
            <span
              v-if="isPrimary"
              class="primary-badge"
              data-testid="deep-primary-badge"
            >{{ DEEP_LABELS.primaryBadge }}</span>
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
              :class="{ violated: threshold.violated }"
              :data-testid="'deep-threshold-label-' + threshold.ruleId"
              :data-violated="threshold.violated"
            >{{ DEEP_LABELS.thresholdLabel(threshold.operator, formatNumber(threshold.value), track.unit, threshold.violated) }}</span>
          </p>
          <TimeChart
            :lane="lane"
            :frame="frame"
            :stages="stages"
            :flags="flags"
            :cursor-ms="cursorMs"
            :cursor-value="valueText(track)"
            :cursor-time="flags.timeLabels && relativeMs !== null ? offset(relativeMs) : null"
          />
        </div>
      </template>
    </div>
    <p
      class="axis-title"
      data-testid="chart-axis-title"
    >
      {{ DEEP_LABELS.axisLabel }}
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
.deep-track--primary { padding:8px 0; border-bottom:1px solid var(--border); }
.primary-badge { padding:0 6px; border-radius:4px; background:var(--info-bg); color:var(--brand); font-size:12px; font-weight:600; }
.pinned-heading { margin:4px 0 0; font-size:13px; font-weight:600; }
.threshold-text { color:var(--text-muted); }
.threshold-text.violated { color:var(--fail); }
.deep-head { display:flex; flex-wrap:wrap; gap:4px 12px; margin:0 0 4px; overflow-wrap:anywhere; }
.track-name { font-weight:600; }
.axis-title { margin:0 16px 0 56px; text-align:center; }
.slider { width:100%; min-height:44px; margin:0; }
</style>
