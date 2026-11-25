<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts/core'
import { BarChart, LineChart } from 'echarts/charts'
import { GridComponent, MarkAreaComponent, MarkLineComponent } from 'echarts/components'
import { SVGRenderer } from 'echarts/renderers'
import {
  PLOT_INSET,
  cursorDots,
  cursorX,
  laneGrid,
  laneOption,
  resolveTokenColor,
  type ChartColors,
  type ChartFrame,
  type ChartLane,
  type ChartStage,
  type LaneFlags,
} from './timeChart'

echarts.use([LineChart, BarChart, GridComponent, MarkAreaComponent, MarkLineComponent, SVGRenderer])

const props = withDefaults(defineProps<{
  lane: ChartLane
  frame: ChartFrame
  stages: ChartStage[]
  flags: LaneFlags
  cursorMs?: number | null
  cursorValue?: string
  cursorTime?: string | null
  hideInlineValue?: boolean
}>(), {
  cursorMs: null,
  cursorValue: '',
  cursorTime: null,
  hideInlineValue: false,
})

const host = ref<HTMLElement | null>(null)
const palette = ref<ChartColors>({
  brand: '#1D5FA8',
  warn: '#8A5A00',
  fail: '#B3261E',
  text: '#13202B',
  muted: '#56667A',
  border: '#DCE2E8',
  fontFamily: 'system-ui, sans-serif',
})

// Not reactive: the instance is driven only by the props watcher, never by the cursor.
let chart: echarts.ECharts | null = null
let resizeObserver: ResizeObserver | null = null
let themeObserver: MutationObserver | null = null
const systemTheme = window.matchMedia('(prefers-color-scheme: dark)')

const grid = computed(() => laneGrid(props.flags))
const x = computed(() => (props.cursorMs === null ? 0 : cursorX(props.frame, props.cursorMs)))
const edge = computed(() => (x.value < 80 ? 'start' : x.value > 920 ? 'end' : 'middle'))
const dots = computed(() => cursorDots(props.lane, props.flags, props.cursorMs))

function colors(): ChartColors {
  const sourceEl = host.value?.closest('.shell') ?? document.documentElement
  const css = getComputedStyle(sourceEl)
  const token = (name: string) => css.getPropertyValue(name).trim()
  const resolved: ChartColors = {
    brand: token('--brand'),
    warn: token('--warn'),
    fail: token('--fail'),
    text: token('--text'),
    muted: token('--text-muted'),
    border: token('--border'),
    surface: token('--surface'),
    c0: token('--c-0') || token('--brand'),
    c1: token('--c-1') || '#D97706',
    c2: token('--c-2') || '#0E8F86',
    c3: token('--c-3') || '#7B4FB3',
    c4: token('--c-4') || '#2563EB',
    c5: token('--c-5') || '#DB2777',
    c6: token('--c-6') || '#059669',
    c7: token('--c-7') || '#4F46E5',
    rest: token('--c-rest') || token('--text-muted'),
    fontFamily: getComputedStyle(document.body).fontFamily,
  }
  palette.value = resolved
  return resolved
}

function draw() {
  chart?.setOption(laneOption(props.lane, props.frame, props.stages, props.flags, colors()), { notMerge: true })
}

onMounted(() => {
  if (!host.value) return
  chart = echarts.init(host.value, null, { renderer: 'svg' })
  draw()
  resizeObserver = new ResizeObserver(() => chart?.resize())
  resizeObserver.observe(host.value)
  themeObserver = new MutationObserver(draw)
  themeObserver.observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme'] })
  systemTheme.addEventListener('change', draw)
})

watch(() => [props.lane, props.frame, props.stages, props.flags], draw)
watch(() => grid.value.height, async () => {
  await nextTick()
  chart?.resize()
})

onBeforeUnmount(() => {
  resizeObserver?.disconnect()
  themeObserver?.disconnect()
  systemTheme.removeEventListener('change', draw)
  chart?.dispose()
  chart = null
})
</script>

<template>
  <div
    class="time-chart"
    data-testid="time-chart"
    :data-segments="lane.segments.length"
    :data-cursor-ms="cursorMs ?? ''"
  >
    <div
      ref="host"
      class="time-chart__host"
      aria-hidden="true"
      :style="{ height: `${grid.height}px` }"
    />
    <div
      v-if="cursorMs !== null && !flags.sparkline"
      class="time-chart__cursor"
      aria-hidden="true"
      :style="{ left: `${PLOT_INSET.left}px`, right: `${PLOT_INSET.right}px`, top: `${grid.top}px`, bottom: `${grid.bottom}px` }"
    >
      <svg
        viewBox="0 0 1000 100"
        preserveAspectRatio="none"
        focusable="false"
      >
        <line
          :data-testid="`cursor-line-${lane.key}`"
          :data-x="x"
          :x1="x"
          :x2="x"
          y1="0"
          y2="100"
          vector-effect="non-scaling-stroke"
        />
      </svg>
      <span
        v-for="dot in dots"
        :key="dot.key"
        class="time-chart__dot"
        :class="{ 'time-chart__dot--small': dot.dashed }"
        :style="{ left: `${x / 10}%`, top: `${dot.yPercent}%`, backgroundColor: resolveTokenColor(dot.color, palette) }"
      />
      <span
        class="time-chart__value"
        :class="[
          `time-chart__value--${edge}`,
          {
            'time-chart__value--below-stage': flags.stageLabels,
            'time-chart__value--sr-only': hideInlineValue,
          },
        ]"
        :data-testid="`cursor-value-${lane.key}`"
        :style="{ left: `${x / 10}%` }"
      >{{ cursorValue }}</span>
      <span
        v-if="cursorTime !== null"
        class="time-chart__time"
        :class="`time-chart__value--${edge}`"
        data-testid="cursor-time-mark"
        :style="{ left: `${x / 10}%` }"
      >{{ cursorTime }}</span>
    </div>
  </div>
</template>

<style scoped>
.time-chart {
  position: relative;
  min-width: 0;
  overflow: hidden;
  border-radius: 6px;
  background: var(--surface-inset);
}

.time-chart__host {
  min-width: 0;
  overflow: hidden;
}

.time-chart__cursor {
  position: absolute;
  pointer-events: none;
}

.time-chart__cursor svg {
  display: block;
  width: 100%;
  height: 100%;
}

.time-chart__cursor line {
  stroke: var(--text);
  stroke-width: 1.25;
  stroke-dasharray: 3 2;
  opacity: 0.72;
}

.time-chart__dot {
  position: absolute;
  width: 9px;
  height: 9px;
  border-radius: 999px;
  border: 2px solid var(--surface);
  transform: translate(-50%, -50%);
  pointer-events: none;
}

.time-chart__dot--small {
  width: 7px;
  height: 7px;
}

.time-chart__value,
.time-chart__time {
  position: absolute;
  padding: 0 5px;
  border-radius: 4px;
  font-family: "Cascadia Mono", Consolas, monospace;
  font-size: 11.5px;
  line-height: 16px;
  white-space: nowrap;
  color: var(--text);
  background: var(--surface);
}

.time-chart__value {
  top: 2px;
  border: 1px solid var(--border);
}

.time-chart__value--sr-only {
  opacity: 0;
  pointer-events: none;
}

.time-chart__value--below-stage {
  top: 21px;
}

.time-chart__time {
  top: 100%;
  margin-top: 30px;
  background: var(--text);
  color: var(--surface);
  font-weight: 600;
}

.time-chart__value--middle {
  transform: translateX(-50%);
}

.time-chart__value--end {
  transform: translateX(-100%);
}
</style>
