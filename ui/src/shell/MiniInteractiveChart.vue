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
  syncOffsetMs?: number | null
  syncPinned?: boolean
  syncActive?: boolean
}>(), {
  stages: () => [],
  unit: '',
  plotHeight: 92,
  timeLabels: true,
  ySplits: 2,
  decimals: 2,
  maxTimeIntervals: 3,
  syncOffsetMs: undefined,
  syncPinned: undefined,
  syncActive: undefined,
})

const emit = defineEmits<{
  'sync-hover': [offsetMs: number]
  'sync-leave': []
  'sync-pin': [offsetMs: number]
  'sync-unpin': []
}>()

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

const localCursorMs = ref<number | null>(null)
const pointerPos = ref({ x: 0, y: 0 })
const hoverActive = ref(false)
const localPinned = ref(false)

const isSynced = computed(() => props.syncOffsetMs !== undefined)
const isPinned = computed(() => (props.syncPinned !== undefined ? props.syncPinned : localPinned.value))
const isActive = computed(() => (props.syncActive !== undefined ? props.syncActive : true))

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

function nearestPointX(targetMs: number): number {
  const pts = allPoints.value
  if (!pts.length) return targetMs
  let best = pts[0].x
  let bestDist = Math.abs(best - targetMs)
  for (let i = 1; i < pts.length; i += 1) {
    const d = Math.abs(pts[i].x - targetMs)
    if (d < bestDist) {
      bestDist = d
      best = pts[i].x
    }
  }
  return best
}

const cursorMs = computed<number | null>(() => {
  if (!isSynced.value) return localCursorMs.value
  if (props.syncOffsetMs === null || props.syncOffsetMs === undefined) return null
  const target = props.frame.originMs + props.syncOffsetMs
  const pad = minStepMs.value
  if (target < props.frame.fromMs - pad || target > props.frame.toMs + pad) return null
  const clamped = Math.max(props.frame.fromMs, Math.min(props.frame.toMs, target))
  return nearestPointX(clamped)
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

const tooltipVisible = computed(() =>
  isActive.value && (hoverActive.value || isPinned.value) && cursorMs.value !== null,
)

const tooltipSections = computed<FloatingTooltipSection[]>(() => {
  if (!tooltipVisible.value || cursorMs.value === null) return []
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

function nearestXFromFraction(fraction: number): number | null {
  const span = props.frame.toMs - props.frame.fromMs
  if (span <= 0) return null
  const target = props.frame.fromMs + fraction * span
  return nearestPointX(target)
}

function chartRect(currentTarget: EventTarget | null): DOMRect | null {
  const el = currentTarget as HTMLElement | null
  if (!el) return null
  return (el.querySelector('.time-chart') ?? el).getBoundingClientRect()
}

function onPointer(event: PointerEvent) {
  if (isPinned.value && event.type === 'pointermove') return
  const rect = chartRect(event.currentTarget)
  if (!rect || rect.width <= 0) return
  const snapped = nearestXFromFraction(plotFraction(event.clientX, rect))
  if (snapped !== null) {
    localCursorMs.value = snapped
    pointerPos.value = { x: event.clientX, y: event.clientY }
    hoverActive.value = true
    if (isSynced.value) emit('sync-hover', snapped - props.frame.originMs)
  }
}

function onPointerLeave() {
  if (!isPinned.value) {
    hoverActive.value = false
    localCursorMs.value = null
    if (isSynced.value) emit('sync-leave')
  }
}

function onClick(event: MouseEvent) {
  const rect = chartRect(event.currentTarget)
  const snapped = rect && rect.width > 0
    ? nearestXFromFraction(plotFraction(event.clientX, rect))
    : cursorMs.value
  if (snapped === null) return
  pointerPos.value = { x: event.clientX, y: event.clientY }
  hoverActive.value = true
  if (isSynced.value) {
    emit('sync-pin', snapped - props.frame.originMs)
    return
  }
  if (localPinned.value) {
    localPinned.value = false
    return
  }
  localCursorMs.value = snapped
  localPinned.value = true
}

function unpin() {
  hoverActive.value = false
  if (isSynced.value) {
    emit('sync-unpin')
    return
  }
  localPinned.value = false
  localCursorMs.value = null
}

function onKeyDown(event: KeyboardEvent) {
  if (!isSynced.value && event.key === 'Escape' && localPinned.value) unpin()
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
      :hide-inline-value="true"
    />
    <ChartFloatingTooltip
      :visible="tooltipVisible"
      :client-x="pointerPos.x"
      :client-y="pointerPos.y"
      :title="formattedTime"
      :meta="activeStage"
      :sections="tooltipSections"
      :pinned="isPinned"
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
