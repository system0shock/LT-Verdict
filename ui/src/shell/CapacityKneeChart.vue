<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as echarts from 'echarts/core'
import { LineChart } from 'echarts/charts'
import { GridComponent, MarkAreaComponent, MarkLineComponent } from 'echarts/components'
import { SVGRenderer } from 'echarts/renderers'
import type { AnalysisResult } from '../types'
import {
  capacityKneeChartModel,
  capacityKneeOption,
  capacityKneeReadout,
  type CapacityChartColors,
} from './capacityChart'
import { CAPACITY_CHART_LABELS } from './labels.tables'
import { capacityNumber } from './tables'
import { plotFraction } from './timeChart'

echarts.use([LineChart, GridComponent, MarkLineComponent, MarkAreaComponent, SVGRenderer])

const props = withDefaults(defineProps<{
  result: AnalysisResult
  compact?: boolean
}>(), {
  compact: false,
})

const L = CAPACITY_CHART_LABELS
const host = ref<HTMLElement | null>(null)
const selected = ref<number | null>(null)
let chart: echarts.ECharts | null = null
let resizeObserver: ResizeObserver | null = null
let themeObserver: MutationObserver | null = null
const systemTheme = window.matchMedia('(prefers-color-scheme: dark)')

const model = computed(() => capacityKneeChartModel(props.result))
const plottablePoints = computed(() => (model.value?.points ?? []).filter((point) => point.p95Ms !== null))
const canDraw = computed(() => (model.value?.plottableCount ?? 0) >= 2)
const showBlock = computed(() => {
  if (!model.value) return false
  if (props.compact) return canDraw.value
  return canDraw.value || model.value.hasGuards || model.value.guardAlert || model.value.points.length > 0
})

const summaries = computed(() => {
  const unit = model.value?.unit ?? ''
  return plottablePoints.value.map((point) => capacityKneeReadout(point, unit))
})
const activeSummary = computed(() => (selected.value === null ? null : summaries.value[selected.value] ?? null))

const ariaDescription = computed(() => {
  if (!model.value) return ''
  const parts = [summaries.value.join('; ')]
  if (model.value.guardAlert) parts.push(L.guardsAlert)
  return L.aria(plottablePoints.value.length, parts.filter(Boolean).join(' '))
})

const guardRows = computed(() => {
  if (!model.value) return []
  return model.value.points
    .filter((point) => point.guardStatus !== 'NONE' || point.guardChecks.length > 0)
    .map((point) => {
      const statusText = L.guardStatus[point.guardStatus] ?? point.guardStatus
      const detail = point.guardChecks
        .map((check) => {
          const peak = check.observedPeak ? `пик ${capacityNumber(check.observedPeak)} ${check.unit}` : ''
          const limit = `${check.ruleId} (${check.seriesId} ${check.operator} ${capacityNumber(check.threshold)} ${check.unit})`
          return peak ? `${limit}, ${peak}` : limit
        })
        .join('; ')
      return {
        stageId: point.stageId,
        status: point.guardStatus,
        text: L.guardLine(point.stageId, statusText, detail),
      }
    })
})

function colors(): CapacityChartColors {
  const css = getComputedStyle(document.documentElement)
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
  if (!host.value || !model.value || !canDraw.value) {
    chart?.dispose()
    chart = null
    return
  }
  if (!chart) chart = echarts.init(host.value, null, { renderer: 'svg' })
  chart.setOption(capacityKneeOption(model.value, colors()), { notMerge: true })
}

function onPointer(event: PointerEvent) {
  const count = plottablePoints.value.length
  if (!count) return
  const rect = (event.currentTarget as HTMLElement).getBoundingClientRect()
  if (rect.width <= 0) return
  const fraction = plotFraction(event.clientX, rect)
  selected.value = Math.min(count - 1, Math.max(0, Math.round(fraction * (count - 1))))
}

function focusCursor(event: FocusEvent) {
  if (selected.value === null && plottablePoints.value.length) {
    selected.value = Number((event.target as HTMLInputElement).value)
  }
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
})

watch(() => [model.value, props.compact], () => {
  selected.value = null
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
  chart?.dispose()
  chart = null
})
</script>

<template>
  <div
    v-if="showBlock && model"
    class="capacity-knee-chart"
    data-testid="capacity-knee-chart-block"
  >
    <h3>{{ L.title }}</h3>
    <template v-if="canDraw">
      <div
        class="capacity-knee-chart__frame"
        role="img"
        :aria-label="ariaDescription"
        @pointermove="onPointer"
        @pointerdown="onPointer"
      >
        <div
          ref="host"
          class="capacity-knee-chart__host"
          data-testid="capacity-knee-chart-host"
          aria-hidden="true"
        />
      </div>
      <p
        class="capacity-knee-chart__readout"
        data-testid="capacity-knee-readout"
      >
        {{ activeSummary ?? L.cursorNone }}
      </p>
      <input
        type="range"
        class="capacity-knee-chart__slider"
        data-testid="capacity-knee-cursor"
        :aria-label="L.cursorLabel"
        min="0"
        :max="plottablePoints.length - 1"
        step="1"
        :value="selected ?? 0"
        :aria-valuetext="activeSummary ?? summaries[0] ?? L.cursorNone"
        @focus="focusCursor"
        @input="inputCursor"
      >
      <p class="field__hint">
        {{ L.legend }}
      </p>
    </template>
    <p
      v-else-if="!compact"
      class="field__hint"
      data-testid="capacity-knee-chart-empty"
    >
      {{ L.empty }}
    </p>
    <div
      v-if="!compact && (model.hasGuards || model.guardAlert)"
      class="capacity-knee-chart__guards"
      data-testid="capacity-guards-summary"
    >
      <h4>{{ L.guardsTitle }}</h4>
      <p
        v-if="model.guardAlert"
        role="note"
        class="capacity-knee-chart__guard-alert"
        data-testid="capacity-guards-alert"
      >
        {{ L.guardsAlert }}
      </p>
      <p
        v-else
        class="field__hint"
        data-testid="capacity-guards-ok"
      >
        {{ L.guardsOk }}
      </p>
      <ul
        v-if="guardRows.length"
        class="capacity-knee-chart__guard-list"
      >
        <li
          v-for="item in guardRows"
          :key="item.stageId"
          :data-guard-status="item.status"
        >
          {{ item.text }}
        </li>
      </ul>
    </div>
  </div>
</template>

<style scoped>
.capacity-knee-chart {
  display: grid;
  gap: 8px;
  min-width: 0;
  margin-block: 12px;
}

.capacity-knee-chart h3,
.capacity-knee-chart h4 {
  margin: 0;
}

.capacity-knee-chart__frame {
  width: 100%;
  max-width: 720px;
  min-width: 0;
  overflow: hidden;
  border: 1px solid var(--border);
  background: var(--surface-inset);
  touch-action: pan-y;
}

.capacity-knee-chart__host {
  width: 100%;
  height: 240px;
  min-width: 0;
  overflow: hidden;
}

.capacity-knee-chart__readout {
  margin: 0;
  font-size: 13px;
}

.capacity-knee-chart__slider {
  width: 100%;
  max-width: 720px;
  min-height: 44px;
  margin: 0;
}

.capacity-knee-chart__guards {
  display: grid;
  gap: 6px;
}

.capacity-knee-chart__guard-alert {
  margin: 0;
  padding: 8px 10px;
  border-inline-start: 3px solid var(--warn);
  background: var(--surface-inset);
}

.capacity-knee-chart__guard-list {
  margin: 0;
  padding-inline-start: 18px;
  font-size: 13px;
}
</style>
