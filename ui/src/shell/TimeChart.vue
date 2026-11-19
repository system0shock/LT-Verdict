<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts/core'
import { LineChart } from 'echarts/charts'
import { GridComponent, MarkAreaComponent, MarkLineComponent } from 'echarts/components'
import { SVGRenderer } from 'echarts/renderers'
import { PLOT_INSET, cursorX, laneGrid, laneOption, type ChartColors, type ChartFrame, type ChartLane, type ChartStage, type LaneFlags } from './timeChart'

echarts.use([LineChart, GridComponent, MarkAreaComponent, MarkLineComponent, SVGRenderer])

const props = defineProps<{
  lane: ChartLane
  frame: ChartFrame
  stages: ChartStage[]
  flags: LaneFlags
  cursorMs: number | null
  cursorValue: string
  cursorTime: string | null
}>()

const host = ref<HTMLElement | null>(null)
// Not reactive: the instance is driven only by the props watcher, never by the cursor.
let chart: echarts.ECharts | null = null
let resizeObserver: ResizeObserver | null = null
let themeObserver: MutationObserver | null = null
const systemTheme = window.matchMedia('(prefers-color-scheme: dark)')

const grid = computed(() => laneGrid(props.flags))
const x = computed(() => props.cursorMs === null ? 0 : cursorX(props.frame, props.cursorMs))
const edge = computed(() => x.value < 80 ? 'start' : x.value > 920 ? 'end' : 'middle')

function colors(): ChartColors {
  const css = getComputedStyle(document.documentElement)
  const token = (name: string) => css.getPropertyValue(name).trim()
  return {
    brand: token('--brand'),
    warn: token('--warn'),
    fail: token('--fail'),
    text: token('--text'),
    muted: token('--text-muted'),
    border: token('--border'),
    fontFamily: getComputedStyle(document.body).fontFamily,
  }
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
      v-if="cursorMs !== null"
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
        class="time-chart__value"
        :class="[`time-chart__value--${edge}`, { 'time-chart__value--below-stage': flags.stageLabels }]"
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
  stroke-width: 1;
}

.time-chart__value,
.time-chart__time {
  position: absolute;
  padding: 0 4px;
  font-size: 12px;
  line-height: 16px;
  white-space: nowrap;
  color: var(--text);
  background: var(--surface-inset);
}

.time-chart__value {
  top: 0;
  background: color-mix(in srgb, var(--surface-inset) 75%, transparent);
}

.time-chart__value--below-stage {
  top: 21px;
}

.time-chart__time {
  top: 100%;
  margin-top: 30px;
}

.time-chart__value--middle {
  transform: translateX(-50%);
}

.time-chart__value--end {
  transform: translateX(-100%);
}
</style>
