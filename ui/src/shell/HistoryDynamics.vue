<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts/core'
import { LineChart } from 'echarts/charts'
import { GridComponent, MarkLineComponent } from 'echarts/components'
import { SVGRenderer } from 'echarts/renderers'
import { historyDynamicsOption, type DynamicsPoint, type HistoryChartColors } from './history'
import { HISTORY_LABELS } from './labels.history'
import { formatNumber } from './overview'
import ChartFloatingTooltip, { type FloatingTooltipSection } from './ChartFloatingTooltip.vue'
import { PLOT_INSET, plotFraction } from './timeChart'

echarts.use([LineChart, GridComponent, MarkLineComponent, SVGRenderer])

const props = withDefaults(defineProps<{
  points: readonly DynamicsPoint[]
  hidden: readonly string[]
  baselineValue?: number | null
  metricLabel?: string
  unit?: string
}>(), {
  baselineValue: null,
  metricLabel: 'p95 отклика',
  unit: 'мс',
})

const L = HISTORY_LABELS
const host = ref<HTMLElement | null>(null)
const selected = ref<number | null>(null)
const pointerPos = ref({ x: 0, y: 0 })
const hoverActive = ref(false)
const pinned = ref(false)
let chart: echarts.ECharts | null = null
let resizeObserver: ResizeObserver | null = null
let themeObserver: MutationObserver | null = null
const systemTheme = window.matchMedia('(prefers-color-scheme: dark)')

const summaries = computed(() =>
  props.points.map((point) => L.dynamicsPoint(point.release.label, point.verdict, formatNumber(point.value))),
)
const activePoint = computed(() => (selected.value === null ? null : props.points[selected.value] ?? null))
const activeSummary = computed(() => (selected.value === null ? null : summaries.value[selected.value] ?? null))

const tooltipSections = computed<FloatingTooltipSection[]>(() => {
  const pt = activePoint.value
  if (!pt) return []
  const unitSuffix = props.unit ? ` ${props.unit}` : ''
  const verdictColor = pt.verdict === 'PASS' ? '#34D399' : pt.verdict === 'FAIL' ? '#FF7B72' : '#FBBF24'
  let deltaSub = ''
  if (props.baselineValue !== null && Number.isFinite(props.baselineValue) && props.baselineValue !== 0) {
    const pct = Math.round(((pt.value - props.baselineValue) / Math.abs(props.baselineValue)) * 100)
    deltaSub = `(${pct > 0 ? '+' : ''}${pct}% к baseline)`
  }
  const rows = [
    {
      key: 'metric',
      label: props.metricLabel,
      value: `${formatNumber(pt.value)}${unitSuffix}`,
      sub: deltaSub,
      color: verdictColor,
      breach: pt.verdict === 'FAIL',
    },
  ]
  if (props.baselineValue !== null && Number.isFinite(props.baselineValue)) {
    rows.push({
      key: 'baseline',
      label: 'Baseline',
      value: `${formatNumber(props.baselineValue)}${unitSuffix}`,
      sub: '',
      color: '#94A3B8',
      breach: false,
    })
  }
  return [{ rows }]
})

function colors(): HistoryChartColors {
  const sourceEl = host.value?.closest('.shell') ?? document.documentElement
  const css = getComputedStyle(sourceEl)
  const token = (name: string) => css.getPropertyValue(name).trim()
  return {
    brand: token('--brand'),
    pass: token('--pass'),
    fail: token('--fail'),
    warn: token('--warn'),
    text: token('--text'),
    muted: token('--text-muted'),
    border: token('--border'),
    fontFamily: getComputedStyle(document.body).fontFamily,
  }
}

function draw() {
  if (!host.value || !props.points.length) {
    chart?.dispose()
    chart = null
    return
  }
  if (!chart) chart = echarts.init(host.value, null, { renderer: 'svg' })
  chart.setOption(historyDynamicsOption(props.points, colors(), props.baselineValue), { notMerge: true })
}

function onPointer(event: PointerEvent) {
  if (!props.points.length) return
  if (pinned.value && event.type === 'pointermove') return
  const rect = (event.currentTarget as HTMLElement).getBoundingClientRect()
  if (rect.width <= 0) return
  const fraction = plotFraction(event.clientX, rect)
  const index = Math.min(props.points.length - 1, Math.max(0, Math.round(fraction * (props.points.length - 1))))
  selected.value = index
  pointerPos.value = { x: event.clientX, y: event.clientY }
  hoverActive.value = true
}

function onPointerLeave() {
  if (!pinned.value) hoverActive.value = false
}

function onChartClick(event: MouseEvent) {
  if (selected.value === null) return
  if (pinned.value) {
    pinned.value = false
    return
  }
  pointerPos.value = { x: event.clientX, y: event.clientY }
  hoverActive.value = true
  pinned.value = true
}

function onKeyDown(event: KeyboardEvent) {
  if (event.key === 'Escape' && pinned.value) {
    pinned.value = false
    hoverActive.value = false
  }
}

function focusCursor(event: FocusEvent) {
  if (selected.value === null && props.points.length) selected.value = Number((event.target as HTMLInputElement).value)
}

function inputCursor(event: Event) {
  selected.value = Number((event.target as HTMLInputElement).value)
}

onMounted(() => {
  draw()
  if (host.value) {
    resizeObserver = new ResizeObserver(() => chart?.resize())
    resizeObserver.observe(host.value)
  }
  themeObserver = new MutationObserver(draw)
  themeObserver.observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme'] })
  systemTheme.addEventListener('change', draw)
  window.addEventListener('keydown', onKeyDown)
})

watch(() => [props.points, props.baselineValue], () => {
  selected.value = null
  pinned.value = false
  hoverActive.value = false
  draw()
  if (host.value && resizeObserver) {
    resizeObserver.disconnect()
    resizeObserver.observe(host.value)
  }
}, { flush: 'post' })

onBeforeUnmount(() => {
  resizeObserver?.disconnect()
  themeObserver?.disconnect()
  systemTheme.removeEventListener('change', draw)
  window.removeEventListener('keydown', onKeyDown)
  chart?.dispose()
  chart = null
})

void PLOT_INSET
</script>

<template>
  <div
    class="history-dynamics"
    data-testid="history-dynamics"
  >
    <h3>{{ L.dynamicsTitle }}</h3>
    <p
      v-if="!points.length"
      role="status"
    >
      {{ L.dynamicsEmpty }}
    </p>
    <template v-else>
      <div
        class="history-dynamics__chart"
        role="img"
        :aria-label="L.dynamicsAria(points.length, summaries.join('; '))"
        @pointermove="onPointer"
        @pointerdown="onPointer"
        @pointerleave="onPointerLeave"
        @click="onChartClick"
      >
        <div
          ref="host"
          class="history-dynamics__host"
          aria-hidden="true"
        />
      </div>
      <ChartFloatingTooltip
        :visible="(hoverActive || pinned) && activePoint !== null"
        :client-x="pointerPos.x"
        :client-y="pointerPos.y"
        :title="activePoint?.release.label ?? ''"
        :meta="activePoint?.verdict ?? ''"
        :sections="tooltipSections"
        :pinned="pinned"
        @unpin="pinned = false; hoverActive = false"
      />
      <p
        class="history-dynamics__readout"
        data-testid="history-dynamics-readout"
      >
        {{ activeSummary ?? L.dynamicsCursorNone }}
      </p>
      <input
        v-if="points.length > 1"
        type="range"
        class="history-dynamics__slider"
        data-testid="history-dynamics-cursor"
        :aria-label="L.dynamicsCursorLabel"
        min="0"
        :max="points.length - 1"
        step="1"
        :value="selected ?? 0"
        :aria-valuetext="activeSummary ?? summaries[0] ?? L.dynamicsCursorNone"
        @focus="focusCursor"
        @input="inputCursor"
      >
    </template>
    <p class="field__hint">
      {{ L.dynamicsLegend }}<template v-if="baselineValue !== null">
        {{ ' ' + L.dynamicsBaselineLegend }}
      </template>
    </p>
    <p
      v-if="hidden.length"
      class="field__hint"
    >
      {{ L.dynamicsHidden(hidden.join(', ')) }}
    </p>
  </div>
</template>

<style scoped>
.history-dynamics {
  display: grid;
  gap: 8px;
  min-width: 0;
}

.history-dynamics__chart {
  width: 100%;
  min-width: 0;
  overflow: hidden;
  border: 1px solid var(--border);
  border-radius: 6px;
  background: var(--surface-inset);
  touch-action: pan-y;
  cursor: crosshair;
}

.history-dynamics__host {
  width: 100%;
  height: 230px;
  min-width: 0;
  overflow: hidden;
}

.history-dynamics__readout {
  margin: 0;
  font-size: 13px;
}

.history-dynamics__slider {
  width: 100%;
  min-height: 44px;
  margin: 0;
}
</style>
