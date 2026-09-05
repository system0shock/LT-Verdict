<script setup lang="ts">
import { computed } from 'vue'
import type { Bucket } from './types'

const props = defineProps<{ buckets: Bucket[]; rollup: number }>()
const width = 600
const height = 180
const left = 52
const right = 16
const top = 16
const bottom = 32
const sorted = computed(() => [...props.buckets].sort((a, b) => a.bucket_start_ms - b.bucket_start_ms))
const start = computed(() => sorted.value[0]?.bucket_start_ms ?? 0)
const span = computed(() => Math.max((sorted.value.at(-1)?.bucket_start_ms ?? start.value) - start.value, props.rollup * 1_000))

function series(value: (bucket: Bucket) => number) {
  const max = Math.max(1, ...sorted.value.map(value))
  const segments: string[][] = []
  let segment: string[] = []
  let previous: number | undefined
  for (const bucket of sorted.value) {
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
  return { max, segments }
}

const rps = computed(() => series((bucket) => bucket.sample_count / props.rollup))
const errors = computed(() => series((bucket) => bucket.error_count))
const p95 = computed(() => series((bucket) => bucket.p95_latency_ms))
</script>

<template>
  <div class="load-charts">
    <figure
      v-for="chart in [{ id: 'rps', label: 'Requests per second', unit: 'RPS', data: rps }, { id: 'errors', label: 'Errors per bin', unit: 'count/bin', data: errors }, { id: 'p95', label: 'P95 latency', unit: 'ms', data: p95 }]"
      :key="chart.id"
    >
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
          :y="height - 8"
          class="chart-label"
        >Time from run start (ms)</text>
        <text
          x="4"
          :y="top + 10"
          class="chart-label"
        >{{ chart.data.max.toLocaleString() }} {{ chart.unit }}</text>
      </svg>
    </figure>
  </div>
</template>
