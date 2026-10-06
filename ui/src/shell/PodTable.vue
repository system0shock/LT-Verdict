<script setup lang="ts">
import { computed, ref } from 'vue'
import { POD_LABELS } from './podLabels'
import { rowLabel, windowStats, type PodRow } from './podView'

const props = defineProps<{ rows: PodRow[]; arm: string | null }>()
const PAGE = 50
const shown = ref(PAGE)
const format = (value: number) => new Intl.NumberFormat('ru-RU', { maximumFractionDigits: 4 }).format(value)
const lines = computed(() => props.rows.slice(0, shown.value).map((row) => ({ row, stats: windowStats(row) })))
</script>

<template>
  <div>
    <div
      class="table-wrap"
      tabindex="0"
      role="region"
      :aria-label="POD_LABELS.tableRegion(arm)"
    >
      <table data-testid="pod-table">
        <thead>
          <tr>
            <th
              v-for="head in POD_LABELS.heads"
              :key="head"
              scope="col"
            >
              {{ head }}
            </th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="line in lines"
            :key="line.row.id"
            data-testid="pod-table-row"
            :data-row-id="line.row.id"
          >
            <th scope="row">
              {{ line.row.pod }}
            </th>
            <td>{{ line.row.container ?? '' }}</td>
            <td data-col="max">
              {{ line.stats ? format(line.stats.max) : POD_LABELS.missing }}
            </td>
            <td data-col="mean">
              {{ line.stats ? format(line.stats.mean) : POD_LABELS.missing }}
            </td>
            <td data-col="last">
              {{ line.stats ? format(line.stats.last) : POD_LABELS.missing }}
            </td>
          </tr>
        </tbody>
      </table>
    </div>
    <p class="muted">
      {{ rows.length ? POD_LABELS.tableNote : POD_LABELS.noMetric }}
    </p>
    <button
      v-if="shown < rows.length"
      type="button"
      data-testid="pod-table-more"
      @click="shown += PAGE"
    >
      {{ POD_LABELS.more(Math.min(PAGE, rows.length - shown)) }}
    </button>
  </div>
</template>
