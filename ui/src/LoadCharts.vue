<script setup lang="ts">
import { computed } from 'vue'
import type { Bucket } from './types'

const props = defineProps<{ buckets: Bucket[]; baselineBuckets?: Bucket[]; rollup: number; markers?: Array<{ at_ms: number; service: string; error_type: string; message: string }> }>()
const width = 600
const height = 180
const left = 52
const right = 16
const top = 16
const bottom = 32
const sorted = computed(() => [...props.buckets].sort((a, b) => a.bucket_start_ms - b.bucket_start_ms))
const baseline = computed(() => [...(props.baselineBuckets ?? [])].sort((a, b) => a.bucket_start_ms - b.bucket_start_ms))
const start = computed(() => Math.min(sorted.value[0]?.bucket_start_ms ?? Infinity, baseline.value[0]?.bucket_start_ms ?? Infinity, ...(!sorted.value.length && !baseline.value.length ? [0] : [])))
const span = computed(() => Math.max((sorted.value.at(-1)?.bucket_start_ms ?? start.value) - start.value, (baseline.value.at(-1)?.bucket_start_ms ?? start.value) - start.value, props.rollup * 1_000))

function series(value: (bucket: Bucket) => number) {
  const max = Math.max(1, ...sorted.value.map(value), ...baseline.value.map(value))
  return { max, segments: segments(sorted.value, value, max), baselineSegments: segments(baseline.value, value, max) }
}

function segments(buckets: Bucket[], value: (bucket: Bucket) => number, max: number) {
  const segments: string[][] = []
  let segment: string[] = []
  let previous: number | undefined
  for (const bucket of buckets) {
    if (previous !== undefined && bucket.bucket_start_ms > previous + props.rollup * 1_000) {
      if (segment.length) segments.push(segment)
      segment = []
    }
    const x = left + ((bucket.bucket_start_ms - start.value) / span.value) * (width - left - right)
    const y = top + (1 - value(bucket) / max) * (height - top - bottom)
    segment.push(`${x},${y}`)
    previous = bucket.bucket_start_ms
  }
  if (segment.length) segments.push(segment)
  return segments
}

const visibleMarkers = computed(() => (props.markers ?? []).filter(item => item.at_ms >= start.value && item.at_ms <= start.value + span.value))
function markerX(at: number) { return left + ((at - start.value) / span.value) * (width - left - right) }

const rps = computed(() => series((bucket) => bucket.sample_count / props.rollup))
const errors = computed(() => series((bucket) => bucket.error_count))
const p95 = computed(() => series((bucket) => bucket.p95_latency_ms))
</script>

<template>
  <div class="load-charts">
    <p
      v-if="baselineBuckets?.length"
      class="muted"
    >
      Solid: current · dashed: baseline. Relative time, common scale; gaps are not interpolated.
    </p>
    <p
      v-if="visibleMarkers.length"
      class="muted"
    >
      Пунктир — события OpenSearch на общей временной оси; совпадение во времени не доказывает причину.
    </p>
    <figure
      v-for="chart in [{ id: 'rps', label: 'Requests per second', unit: 'RPS', data: rps }, { id: 'errors', label: 'Errors per bin', unit: 'count/bin', data: errors }, { id: 'p95', label: 'P95 latency', unit: 'ms', data: p95 }]"
      :key="chart.id"
    >
      <figcaption>{{ chart.label }}</figcaption>
      <svg
        :aria-label="chart.label"
        role="img"
        :viewBox="`0 0 ${width} ${height}`"
      >
        <title>{{ chart.label }}</title>
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
        <g
          v-for="(marker, index) in visibleMarkers"
          :key="`marker-${index}`"
        >
          <title>{{ marker.service }} · {{ marker.error_type }} · {{ marker.message }}</title>
          <line
            :x1="markerX(marker.at_ms)"
            :x2="markerX(marker.at_ms)"
            :y1="top"
            :y2="height - bottom"
            stroke="var(--warn)"
            stroke-dasharray="3 3"
            opacity="0.65"
          />
        </g>
        <polyline
          v-for="(segment, index) in chart.data.baselineSegments"
          :key="`baseline-${index}`"
          :points="segment.join(' ')"
          class="chart-line"
          stroke-dasharray="7 4"
          opacity="0.6"
          fill="none"
        />
        <circle
          v-for="(segment, index) in chart.data.baselineSegments.filter((value) => value.length === 1)"
          :key="`baseline-point-${index}`"
          :cx="segment[0].split(',')[0]"
          :cy="segment[0].split(',')[1]"
          r="4"
          fill="none"
          stroke="currentColor"
        />
        <polyline
          v-for="(segment, index) in chart.data.segments"
          :key="index"
          :points="segment.join(' ')"
          class="chart-line"
          fill="none"
        />
        <circle
          v-for="(segment, index) in chart.data.segments.filter((value) => value.length === 1)"
          :key="`point-${index}`"
          :cx="segment[0].split(',')[0]"
          :cy="segment[0].split(',')[1]"
          r="3"
          class="chart-point"
        />
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
        >{{ (start + span).toLocaleString() }}</text>
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
        >{{ chart.data.max.toLocaleString() }} {{ chart.unit }}</text>
      </svg>
    </figure>
  </div>
</template>
