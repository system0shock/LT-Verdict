<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, useId, watch } from 'vue'
import { DEEP_LABELS } from './labels'
import { formatNumber, formatOffset } from './overview'
import { cursorReadouts, cursorStops, deepLane, nearestTime, type DeepTrack, type TimeAxis } from './deep'
import ChartFloatingTooltip, { type FloatingTooltipSection } from './ChartFloatingTooltip.vue'
import TimeChart from './TimeChart.vue'
import { plotFraction, type ChartStage, type ChartToken } from './timeChart'

const props = withDefaults(defineProps<{
  tracks: DeepTrack[]
  primaryTrack?: DeepTrack | null
  axis: TimeAxis
  originMs: number
  stages?: ChartStage[]
  mode?: 'focus' | 'workbench'
}>(), {
  primaryTrack: null,
  stages: () => [],
  mode: 'workbench',
})

const emit = defineEmits<{
  'move-track': [key: string, dir: -1 | 1]
  'remove-track': [key: string]
}>()

const cursorMs = ref<number | null>(null)
const pointerPos = ref({ x: 0, y: 0 })
const hoverActive = ref(false)
const pinned = ref(false)
const hintId = useId()

const LANE_TOKENS: ChartToken[] = ['c0', 'c1', 'c2', 'c3', 'c4', 'c5', 'c6', 'c7']
const TOOLTIP_COLORS = ['#60A5FA', '#FBBF24', '#2DD4BF', '#C084FC', '#818CF8', '#F472B6', '#34D399', '#FF7B72']

const allTracks = computed(() => (props.primaryTrack ? [props.primaryTrack, ...props.tracks] : props.tracks))
const stops = computed(() => cursorStops(allTracks.value).filter((time) => time >= props.axis.fromMs && time < props.axis.toMs))
const readouts = computed(() => (cursorMs.value === null ? [] : cursorReadouts(allTracks.value, cursorMs.value)))
const relativeMs = computed(() => (cursorMs.value === null ? null : cursorMs.value - props.originMs))
const frame = computed(() => ({ ...props.axis, originMs: props.originMs }))

const lanes = computed(() => allTracks.value.map((track, index) => {
  const isPrimary = props.mode === 'focus' && Boolean(props.primaryTrack && index === 0)
  const timeLabels = index === allTracks.value.length - 1
  const stageLabels = index === 0
  const baseLane = deepLane(track, props.axis)
  const colorToken: ChartToken = track.key === 'load-errors' || track.key === 'load-error-rate'
    ? 'fail'
    : LANE_TOKENS[index % LANE_TOKENS.length]
  const lane = {
    ...baseLane,
    color: colorToken,
    area: props.mode === 'workbench' || isPrimary,
  }
  return {
    track,
    index,
    isPrimary,
    colorVar: colorToken === 'fail' ? 'var(--c-err, var(--fail))' : `var(--c-${index % 8})`,
    lane,
    flags: isPrimary
      ? { timeLabels, stageLabels, plotHeight: timeLabels ? 192 : 236, ySplits: 4 }
      : { timeLabels, stageLabels, plotHeight: props.mode === 'workbench' ? 96 : 86, ySplits: 2 },
  }
}))

const stopIndex = computed(() => (cursorMs.value === null ? 0 : Math.max(0, stops.value.findLastIndex((time) => time <= cursorMs.value!))))
const offset = (ms: number) => (ms < 0 ? `-${formatOffset(-ms)}` : formatOffset(ms))

const activeStage = computed(() => {
  if (cursorMs.value === null) return ''
  const st = props.stages.find((s) => cursorMs.value! >= s.fromMs && cursorMs.value! <= s.toMs)
  return st ? st.id : ''
})

const tooltipSections = computed<FloatingTooltipSection[]>(() => {
  if (cursorMs.value === null) return []
  const rows = allTracks.value.map((track, idx) => {
    const item = readouts.value.find((row) => row.key === track.key)
    const rawNum = item && !item.gap ? Number(item.raw) : Number.NaN
    const breached = Number.isFinite(rawNum) && track.thresholds.some((t) => (t.operator === 'gt' ? rawNum > t.value : rawNum < t.value))
    return {
      key: track.key,
      label: track.label,
      value: !item || item.gap ? DEEP_LABELS.gap : `${item.text} ${track.unit}`,
      color: track.key === 'load-errors' || track.key === 'load-error-rate' ? '#FF7B72' : TOOLTIP_COLORS[idx % TOOLTIP_COLORS.length],
      breach: breached,
    }
  })
  return [{ rows }]
})

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

watch(() => [props.axis.fromMs, props.axis.toMs], () => {
  cursorMs.value = null
  pinned.value = false
  hoverActive.value = false
})

function onPointer(event: PointerEvent) {
  if (pinned.value && event.type === 'pointermove') return
  const rect = (event.currentTarget as HTMLElement).getBoundingClientRect()
  if (rect.width > 0) {
    cursorMs.value = nearestTime(props.axis, plotFraction(event.clientX, rect))
    pointerPos.value = { x: event.clientX, y: event.clientY }
    hoverActive.value = true
  }
}

function onPointerLeave() {
  if (!pinned.value) hoverActive.value = false
}

function onPlotClick(event: MouseEvent) {
  if ((event.target as HTMLElement | null)?.closest('button')) return
  if (cursorMs.value === null) return
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

onMounted(() => window.addEventListener('keydown', onKeyDown))
onBeforeUnmount(() => window.removeEventListener('keydown', onKeyDown))
</script>

<template>
  <div
    class="deep-chart"
    data-testid="deep-chart"
    :data-mode="mode"
    role="group"
    :aria-label="DEEP_LABELS.chartAria"
  >
    <div
      class="deep-plot"
      data-testid="deep-plot"
      @pointermove="onPointer"
      @pointerdown="onPointer"
      @pointerleave="onPointerLeave"
      @click="onPlotClick"
    >
      <template
        v-for="({ track, index, isPrimary, colorVar, lane, flags }) in lanes"
        :key="track.key"
      >
        <p
          v-if="mode === 'focus' && primaryTrack && tracks.length && index === 1"
          class="pinned-heading v4-kicker"
          data-testid="deep-pinned-heading"
        >
          {{ DEEP_LABELS.pinnedTitle }}
        </p>
        <div
          class="wb-lane deep-track"
          :class="[track.kind === 'resource' ? 'resource' : track.key, { 'deep-track--primary': isPrimary }]"
          :data-testid="'deep-track-' + track.key"
          :data-primary="isPrimary ? 'true' : 'false'"
        >
          <div class="wb-lane__hd">
            <div class="wb-lane__title">
              <span
                class="wb-lane__swatch"
                :style="{ backgroundColor: colorVar }"
                aria-hidden="true"
              />
              <span
                v-if="isPrimary"
                class="primary-badge"
                data-testid="deep-primary-badge"
              >{{ DEEP_LABELS.primaryBadge }}</span>
              <strong class="track-name">{{ track.label }}<template v-if="track.reducer"> ({{ DEEP_LABELS.reducerLabels[track.reducer] }})</template></strong>
              <span class="wb-lane__kind v4-kicker">{{ track.kind === 'resource' ? 'ПЛАТФОРМА' : 'НАГРУЗКА' }}</span>
              <span
                v-for="threshold in track.thresholds"
                :key="threshold.ruleId"
                class="threshold-text"
                :class="{ violated: threshold.violated }"
                :data-testid="'deep-threshold-label-' + threshold.ruleId"
                :data-violated="threshold.violated"
              >{{ DEEP_LABELS.thresholdLabel(threshold.operator, formatNumber(threshold.value), track.unit, threshold.violated) }}</span>
            </div>
            <div class="wb-lane__right">
              <span class="v4-sub v4-num">{{ DEEP_LABELS.trackMax(formatNumber(track.max)) }} {{ track.unit }}</span>
              <strong
                class="wb-lane__val v4-num"
                :data-testid="'track-value-' + track.key"
                :data-value="readout(track.key)?.raw ?? ''"
              >{{ valueText(track) }}</strong>
              <span
                v-if="readout(track.key)?.partial"
                class="v4-sub"
              >{{ DEEP_LABELS.partialCell(readout(track.key)!.observed!, readout(track.key)!.total!) }}</span>
              <div
                v-if="mode === 'workbench' && lanes.length > 1"
                class="wb-lane__controls"
              >
                <button
                  type="button"
                  class="v4-btn v4-btn--xs"
                  :disabled="index === 0"
                  aria-label="Поднять дорожку выше"
                  title="Выше"
                  @click.stop="emit('move-track', track.key, -1)"
                >
                  ↑
                </button>
                <button
                  type="button"
                  class="v4-btn v4-btn--xs"
                  :disabled="index === lanes.length - 1"
                  aria-label="Опустить дорожку ниже"
                  title="Ниже"
                  @click.stop="emit('move-track', track.key, 1)"
                >
                  ↓
                </button>
                <button
                  type="button"
                  class="v4-btn v4-btn--xs"
                  aria-label="Убрать дорожку из стопки"
                  title="Убрать"
                  @click.stop="emit('remove-track', track.key)"
                >
                  ×
                </button>
              </div>
            </div>
          </div>
          <TimeChart
            :lane="lane"
            :frame="frame"
            :stages="stages"
            :flags="flags"
            :cursor-ms="cursorMs"
            :cursor-value="valueText(track)"
            :cursor-time="flags.timeLabels && relativeMs !== null ? offset(relativeMs) : null"
            :hide-inline-value="hoverActive || pinned"
          />
        </div>
      </template>
    </div>

    <ChartFloatingTooltip
      :visible="(hoverActive || pinned) && relativeMs !== null"
      :client-x="pointerPos.x"
      :client-y="pointerPos.y"
      :title="relativeMs !== null ? offset(relativeMs) : ''"
      :meta="activeStage"
      :sections="tooltipSections"
      :pinned="pinned"
      @unpin="unpin"
    />

    <p
      class="axis-title v4-sub"
      data-testid="chart-axis-title"
    >
      {{ DEEP_LABELS.axisLabel }}
    </p>
    <div class="deep-scrubber">
      <p
        class="v4-sub v4-num"
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
        class="v4-sub deep-scrubber__hint"
      >
        {{ DEEP_LABELS.cursorHint }}
      </p>
    </div>
  </div>
</template>

<style scoped>
.deep-chart,
.deep-plot {
  display: grid;
  gap: 8px;
  min-width: 0;
}

.deep-plot {
  touch-action: pan-y;
  cursor: crosshair;
}

.wb-lane {
  border: 1px solid var(--border);
  border-radius: 8px;
  background: var(--surface-inset);
  padding: 7px 10px 4px;
  display: flex;
  flex-direction: column;
  gap: 3px;
  min-width: 0;
}

.deep-track--primary {
  background: var(--surface);
  border-color: var(--brand);
}

.wb-lane__hd {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  flex-wrap: wrap;
  font-size: 12.5px;
  line-height: 1.3;
}

.wb-lane__title {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  flex-wrap: wrap;
  min-width: 0;
}

.wb-lane__swatch {
  width: 9px;
  height: 9px;
  border-radius: 2px;
  flex: none;
}

.wb-lane__kind {
  font-size: 10px;
}

.wb-lane__right {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.wb-lane__val {
  font-size: 12.5px;
  font-weight: 700;
  color: var(--text);
}

.wb-lane__controls {
  display: inline-flex;
  align-items: center;
  gap: 3px;
}

.primary-badge {
  padding: 1px 6px;
  border-radius: 4px;
  background: var(--info-bg);
  color: var(--brand);
  font-size: 11px;
  font-weight: 650;
}

.pinned-heading {
  margin: 4px 0 0;
}

.threshold-text {
  font-size: 11.5px;
  color: var(--text-muted);
}

.threshold-text.violated {
  color: var(--fail);
  font-weight: 600;
}

.track-name {
  font-weight: 650;
  font-size: 12.5px;
}

.axis-title {
  margin: 0 16px 0 56px;
  text-align: center;
  font-size: 11px;
}

.deep-scrubber {
  display: grid;
  gap: 4px;
  padding-top: 4px;
}

.deep-scrubber p {
  margin: 0;
  font-size: 11.5px;
}

.deep-scrubber__hint {
  color: var(--text-muted);
}

.slider {
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
