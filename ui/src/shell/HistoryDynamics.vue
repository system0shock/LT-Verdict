<script setup lang="ts">
import { computed } from 'vue'
import type { DynamicsPoint } from './history'
import { HISTORY_LABELS } from './labels.history'
import { formatNumber } from './overview'

const props = defineProps<{ points: readonly DynamicsPoint[]; hidden: readonly string[] }>()
const L = HISTORY_LABELS
const WIDTH = 640
const HEIGHT = 220
const LEFT = 64
const RIGHT = 16
const TOP = 16
const BOTTOM = 44

const maxValue = computed(() => Math.max(1, ...props.points.map((point) => point.value)) * 1.1)
const plotted = computed(() => props.points.map((point, index) => ({
  point,
  x: props.points.length === 1 ? (LEFT + WIDTH - RIGHT) / 2 : LEFT + (index * (WIDTH - LEFT - RIGHT)) / (props.points.length - 1),
  y: TOP + (HEIGHT - TOP - BOTTOM) * (1 - point.value / maxValue.value),
  title: L.dynamicsPoint(point.release.label, point.verdict, formatNumber(point.value)),
})))
const shortLabel = (label: string) => (label.length > 10 ? `${label.slice(0, 9)}…` : label)
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
    <svg
      v-else
      class="history-dynamics__plot"
      :viewBox="`0 0 ${WIDTH} ${HEIGHT}`"
      role="img"
      :aria-label="L.dynamicsAria(points.length)"
    >
      <line
        :x1="LEFT"
        :y1="HEIGHT - BOTTOM"
        :x2="WIDTH - RIGHT"
        :y2="HEIGHT - BOTTOM"
        class="history-dynamics__axis"
      />
      <line
        :x1="LEFT"
        :y1="TOP"
        :x2="LEFT"
        :y2="HEIGHT - BOTTOM"
        class="history-dynamics__axis"
      />
      <text
        :x="LEFT - 6"
        :y="HEIGHT - BOTTOM"
        text-anchor="end"
        class="history-dynamics__text"
      >0</text>
      <text
        :x="LEFT - 6"
        :y="TOP + 10"
        text-anchor="end"
        class="history-dynamics__text"
      >{{ formatNumber(maxValue) }}</text>
      <polyline
        v-if="plotted.length > 1"
        :points="plotted.map((item) => `${item.x},${item.y}`).join(' ')"
        class="history-dynamics__line"
      />
      <g
        v-for="item in plotted"
        :key="item.point.release.release_id"
        :class="`history-dynamics__mark history-dynamics__mark--${item.point.verdict === 'PASS' ? 'pass' : item.point.verdict === 'FAIL' ? 'fail' : 'other'}`"
      >
        <title>{{ item.title }}</title>
        <circle
          v-if="item.point.verdict === 'PASS'"
          :cx="item.x"
          :cy="item.y"
          r="6"
        />
        <rect
          v-else-if="item.point.verdict === 'FAIL'"
          :x="item.x - 6"
          :y="item.y - 6"
          width="12"
          height="12"
        />
        <polygon
          v-else
          :points="`${item.x},${item.y - 7} ${item.x + 7},${item.y + 6} ${item.x - 7},${item.y + 6}`"
        />
        <text
          :x="item.x"
          :y="HEIGHT - BOTTOM + 16"
          text-anchor="middle"
          class="history-dynamics__text"
        >{{ shortLabel(item.point.release.label) }}</text>
      </g>
    </svg>
    <p class="field__hint">
      {{ L.dynamicsLegend }}
    </p>
    <p
      v-if="hidden.length"
      class="field__hint"
    >
      {{ L.dynamicsHidden(hidden.join(', ')) }}
    </p>
  </div>
</template>

<style>
.history-dynamics { min-width: 0; }
.history-dynamics__plot { display: block; width: 100%; max-width: 640px; height: auto; border: 1px solid var(--border); background: var(--surface-inset); }
.history-dynamics__axis { stroke: var(--border-strong); stroke-width: 1; }
.history-dynamics__line { fill: none; stroke: var(--text-muted); stroke-width: 1.5; }
.history-dynamics__text { fill: var(--text-muted); font-size: 11px; }
.history-dynamics__mark--pass { fill: var(--pass); stroke: var(--pass); }
.history-dynamics__mark--fail { fill: var(--fail); stroke: var(--fail); }
.history-dynamics__mark--other { fill: var(--warn); stroke: var(--warn); }
.history-dynamics__mark text { stroke: none; fill: var(--text-muted); }
</style>
