<script setup lang="ts">
import { computed } from 'vue'
import type { ChartOverlay, OverlayPoint } from './analyticsTypes'

const props = withDefaults(defineProps<{ overlay: ChartOverlay; width?: number; height?: number }>(), { width: 600, height: 180 })
const left = 52
const right = 16
const top = 16
const bottom = 32
const colors = ['#2563eb', '#7c3aed', '#0891b2', '#15803d']
const points = computed(() => props.overlay.series.flatMap((series) => series.points))
const start = computed(() => Math.min(0, ...points.value.map((point) => point.from_ms), ...props.overlay.markers.map((marker) => marker.at_ms)))
const end = computed(() => Math.max(1, ...points.value.map((point) => point.to_ms), ...props.overlay.markers.map((marker) => marker.at_ms)))
const span = computed(() => Math.max(1, end.value - start.value))
const maximum = computed(() => Math.max(1, ...points.value.map((point) => numeric(point.value))))
const plotted = computed(() => props.overlay.series.map((item) => ({
  ...item,
  segments: segments(item.points),
})))

function numeric(value: number | string) {
  const parsed = Number(value)
  return Number.isFinite(parsed) ? parsed : 0
}

function x(value: number) {
  return left + ((value - start.value) / span.value) * (props.width - left - right)
}

function y(value: number | string) {
  return top + (1 - numeric(value) / maximum.value) * (props.height - top - bottom)
}

function segments(values: OverlayPoint[]) {
  const result: string[][] = []
  let current: string[] = []
  let previous: OverlayPoint | undefined
  for (const point of [...values].sort((a, b) => a.from_ms - b.from_ms)) {
    if (previous && point.from_ms > previous.to_ms) {
      if (current.length) result.push(current)
      current = []
    }
    current.push(`${x(point.from_ms)},${y(point.value)}`)
    previous = point
  }
  if (current.length) result.push(current)
  return result
}
</script>

<template>
  <figure aria-labelledby="opensearch-overlay-title">
    <figcaption id="opensearch-overlay-title">
      OpenSearch error overlay
    </figcaption>
    <p
      v-if="!overlay.series.length"
      role="status"
    >
      N/A ({{ overlay.reasons.join(', ') || 'OPENSEARCH_NOT_AVAILABLE' }})
    </p>
    <template v-else>
      <svg
        role="img"
        aria-label="OpenSearch errors over relative run time"
        :viewBox="`0 0 ${width} ${height}`"
      >
        <title>OpenSearch errors over relative run time</title>
        <line
          :x1="left"
          :x2="left"
          :y1="top"
          :y2="height - bottom"
          class="chart-axis"
        />
        <line
          :x1="left"
          :x2="width - right"
          :y1="height - bottom"
          :y2="height - bottom"
          class="chart-axis"
        />
        <template
          v-for="(item, seriesIndex) in plotted"
          :key="item.id"
        >
          <polyline
            v-for="(segment, index) in item.segments"
            :key="`${item.id}-${index}`"
            :points="segment.join(' ')"
            class="chart-line"
            fill="none"
            :style="{ stroke: colors[seriesIndex % colors.length] }"
          />
        </template>
        <g
          v-for="(marker, index) in overlay.markers"
          :key="`${marker.profile_id}-${marker.at_ms}-${index}`"
        >
          <line
            :x1="x(marker.at_ms)"
            :x2="x(marker.at_ms)"
            :y1="top"
            :y2="height - bottom"
            stroke="#b45309"
            stroke-dasharray="3 3"
          />
          <title>{{ marker.profile_id }} / {{ marker.service }} / {{ marker.error_type }}: {{ marker.message }}</title>
        </g>
        <text
          :x="left"
          :y="height - bottom + 13"
          class="chart-label"
        >{{ start.toLocaleString() }}</text>
        <text
          :x="width - right"
          :y="height - bottom + 13"
          text-anchor="end"
          class="chart-label"
        >{{ end.toLocaleString() }}</text>
        <text
          :x="width / 2"
          :y="height - 8"
          text-anchor="middle"
          class="chart-label"
        >Time from run start (ms)</text>
        <text
          :x="left"
          :y="top - 4"
          class="chart-label"
        >{{ maximum.toLocaleString() }} errors/minute</text>
      </svg>
      <ul aria-label="OpenSearch overlay legend">
        <li
          v-for="(item, index) in plotted"
          :key="item.id"
        >
          <span :style="{ color: colors[index % colors.length] }">●</span> {{ item.profile_id }} — {{ item.label }} ({{ item.unit }})
        </li>
      </ul>
      <p
        v-if="overlay.reasons.length"
        class="field__hint"
      >
        {{ overlay.reasons.join(', ') }}
      </p>
    </template>
  </figure>
</template>
