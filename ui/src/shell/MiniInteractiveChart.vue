<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import ChartFloatingTooltip, { type FloatingTooltipSection } from './ChartFloatingTooltip.vue'
import TimeChart from './TimeChart.vue'
import { formatNumber, formatOffset } from './overview'
import {
  plotFraction,
  valueAtTime,
  type ChartFrame,
  type ChartLane,
  type ChartStage,
  type ChartToken,
} from './timeChart'

const props = withDefaults(defineProps<{
  lane: ChartLane
  frame: ChartFrame
  stages?: ChartStage[]
  label: string
  unit?: string
  plotHeight?: number
  timeLabels?: boolean
  ySplits?: number
  decimals?: number
  maxTimeIntervals?: number
}>(), {
  stages: () => [],
  unit: '',
  plotHeight: 92,
  timeLabels: true,
  ySplits: 2,
  decimals: 2,
  maxTimeIntervals: 3,
})

const TOKEN_HEX: Partial<Record<ChartToken, string>> = {
  brand: '#60A5FA',
  warn: '#FBBF24',
  fail: '#FF7B72',
  c0: '#60A5FA',
  c1: '#FBBF24',
  c2: '#2DD4BF',
  c3: '#C084FC',
  c4: '#818CF8',
  c5: '#F472B6',
  c6: '#34D399',
  c7: '#A78BFA',
}

const cursorMs = ref<number | null>(null)
const pointerPos = ref({ x: 0, y: 0 })
const hoverActive = ref(false)
const pinned = ref(false)

const allPoints = computed(() => {
  if (props.lane.series?.length) {
    return props.lane.series.flatMap((s) => s.segments.flat())
  }
  return props.lane.segments.flat()
})

const minStepMs = computed(() => {
  const xs = allPoints.value.map((p) => p.x).sort((a, b) => a - b)
  let step = Infinity
  for (let i = 1; i < xs.length; i += 1) {
    const d = xs[i] - xs[i - 1]
    if (d > 0 && d < step) step = d
  }
  return Number.isFinite(step) ? step : Math.max(1000, (props.frame.toMs - props.frame.fromMs) / 60)
})

const flags = computed(() => ({
  timeLabels: props.timeLabels,
  stageLabels: false,
  sparkline: false,
  plotHeight: props.plotHeight,
  ySplits: props.ySplits,
  maxTimeIntervals: props.maxTimeIntervals,
  compactTimeAxis: true,
}))

const relativeMs = computed(() => (cursorMs.value === null ? null : cursorMs.value - props.frame.originMs))

const formattedTime = computed(() => {
  if (relativeMs.value === null) return ''
  return relativeMs.value < 0 ? `-${formatOffset(-relativeMs.value)}` : `+${formatOffset(relativeMs.value)}`
})

const activeStage = computed(() => {
  if (cursorMs.value === null) return ''
  const st = props.stages.find((s) => cursorMs.value! >= s.fromMs && cursorMs.value! <= s.toMs)
  return st ? st.id : ''
})

const cursorVal = computed(() => {
  if (cursorMs.value === null) return null
  const tol = minStepMs.value * 0.75
  return valueAtTime(props.lane.segments, cursorMs.value, tol)
})

const formattedVal = computed(() => {
  if (cursorVal.value === null) return '—'
  const num = formatNumber(cursorVal.value, props.lane.integer ? 0 : props.decimals)
  return props.unit ? `${num} ${props.unit}` : num
})

const tooltipSections = computed<FloatingTooltipSection[]>(() => {
  if (cursorMs.value === null) return []
  const tol = minStepMs.value * 0.75
  if (props.lane.series?.length) {
    const rows = props.lane.series.map((s) => {
      const val = valueAtTime(s.segments, cursorMs.value!, tol)
      const num = val === null ? '—' : formatNumber(val, props.lane.integer ? 0 : props.decimals)
      const unitStr = s.unit ?? props.unit
      return {
        key: s.key,
        label: s.label ?? props.label,
        value: val === null ? '—' : unitStr ? `${num} ${unitStr}` : num,
        color: TOKEN_HEX[s.color] ?? '#60A5FA',
        dashed: s.dashed,
      }
    })
    return [{ rows }]
  }
  const breached = cursorVal.value !== null && Boolean(
    props.lane.thresholds?.some((t) => t.violated && cursorVal.value! > t.value),
  )
  return [{
    rows: [{
      key: props.lane.key,
      label: props.label,
      value: formattedVal.value,
      color: TOKEN_HEX[props.lane.color] ?? '#60A5FA',
      breach: breached,
    }],
  }]
})

function nearestX(fraction: number): number | null {
  const span = props.frame.toMs - props.frame.fromMs
  if (span <= 0) return null
  const target = props.frame.fromMs + fraction * span
  const pts = allPoints.value
  if (!pts.length) return target
  let best = pts[0].x
  let bestDist = Math.abs(best - target)
  for (let i = 1; i < pts.length; i += 1) {
    const d = Math.abs(pts[i].x - target)
    if (d < bestDist) {
      bestDist = d
      best = pts[i].x
    }
  }
  return best
}

function onPointer(event: PointerEvent) {
  if (pinned.value && event.type === 'pointermove') return
  const rect = (event.currentTarget as HTMLElement).getBoundingClientRect()
  if (rect.width <= 0) return
  const snapped = nearestX(plotFraction(event.clientX, rect))
  if (snapped !== null) {
    cursorMs.value = snapped
    pointerPos.value = { x: event.clientX, y: event.clientY }
    hoverActive.value = true
  }
}

function onPointerLeave() {
  if (!pinned.value) {
    hoverActive.value = false
    cursorMs.value = null
  }
}

function onClick(event: MouseEvent) {
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
  cursorMs.value = null
}

function onKeyDown(event: KeyboardEvent) {
  if (event.key === 'Escape' && pinned.value) unpin()
}

onMounted(() => window.addEventListener('keydown', onKeyDown))
onBeforeUnmount(() => window.removeEventListener('keydown', onKeyDown))
</script>

<template>
  <div
    class="mini-chart"
    data-testid="mini-interactive-chart"
    @pointermove="onPointer"
    @pointerdown="onPointer"
    @pointerleave="onPointerLeave"
    @click="onClick"
  >
    <TimeChart
      :lane="lane"
      :frame="frame"
      :stages="stages"
      :flags="flags"
      :cursor-ms="cursorMs"
      :cursor-value="formattedVal"
      :hide-inline-value="hoverActive || pinned"
    />
    <ChartFloatingTooltip
      :visible="(hoverActive || pinned) && cursorMs !== null"
      :client-x="pointerPos.x"
      :client-y="pointerPos.y"
      :title="formattedTime"
      :meta="activeStage"
      :sections="tooltipSections"
      :pinned="pinned"
      @unpin="unpin"
    />
  </div>
</template>

<style scoped>
.mini-chart {
  position: relative;
  min-width: 0;
  cursor: crosshair;
  touch-action: pan-y;
}
</style>
