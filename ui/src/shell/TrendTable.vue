<script setup lang="ts">
import { computed, ref } from 'vue'
import type { AnalysisResult } from '../types'
import { TABLES_LABELS, TREND_LABELS } from './labels.tables'
import { sortTrendRows, trendView, type TrendSortKey } from './tables'

const props = defineProps<{ result: AnalysisResult }>()
const sortKey = ref<TrendSortKey>('default')
const sortDir = ref<1 | -1>(1)
const view = computed(() => trendView(props.result))
const rows = computed(() => view.value ? sortTrendRows(view.value.rows, sortKey.value, sortDir.value) : [])

const sortColumns: Array<TrendSortKey | null> = ['check', 'series', 'window', null, 'status', null, 'slope', 'shift', 'median', null, null, null]

function sortAria(key: TrendSortKey | null): 'ascending' | 'descending' | 'none' | undefined {
  if (!key) return undefined
  return sortKey.value !== key ? 'none' : sortDir.value === 1 ? 'ascending' : 'descending'
}

function toggleSort(key: TrendSortKey) {
  if (sortKey.value === key) sortDir.value = sortDir.value === 1 ? -1 : 1
  else {
    sortKey.value = key
    sortDir.value = key === 'slope' || key === 'shift' || key === 'median' ? -1 : 1
  }
}
</script>

<template>
  <section
    v-if="view"
    id="trend-results"
    data-testid="trend-results"
    class="panel"
    lang="ru"
    aria-labelledby="trend-results-title"
  >
    <h2 id="trend-results-title">
      {{ TREND_LABELS.title }}
    </h2>
    <p data-testid="trend-summary">
      {{ view.summaryText }}
    </p>
    <p>{{ TREND_LABELS.method }}</p>
    <div
      v-if="view.rows.length"
      class="table-wrap"
      tabindex="0"
      role="region"
      :aria-label="TREND_LABELS.region"
    >
      <table>
        <thead>
          <tr>
            <th
              v-for="(head, index) in TREND_LABELS.heads"
              :key="head"
              scope="col"
              :aria-sort="sortAria(sortColumns[index])"
            >
              <button
                v-if="sortColumns[index]"
                type="button"
                :aria-label="TABLES_LABELS.sortBy(head)"
                @click="toggleSort(sortColumns[index]!)"
              >
                {{ head }}
              </button>
              <template v-else>
                {{ head }}
              </template>
            </th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="row in rows"
            :key="row.key"
            data-testid="trend-row"
          >
            <th scope="row">
              {{ row.check }}
            </th>
            <td>{{ row.series }}</td>
            <td>{{ row.window }}</td>
            <td>{{ row.declared }}</td>
            <td>
              <span
                class="status-text"
                :data-status="row.status"
              >{{ row.statusText }}</span>
            </td>
            <td>{{ row.observed }}</td>
            <td>{{ row.slope }}</td>
            <td>{{ row.shift }}</td>
            <td>{{ row.median }}</td>
            <td>{{ row.required }}</td>
            <td>{{ row.cells }}</td>
            <td>
              <template v-if="row.reasons.length">
                <span
                  v-for="reason in row.reasons"
                  :key="reason.code"
                >{{ reason.text }} <code>{{ reason.code }}</code> </span>
              </template>
              <template v-else>
                {{ TREND_LABELS.noReasons }}
              </template>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </section>
</template>

<style scoped>
section { min-width: 0; }
table { min-width: 100rem; }
th button { min-height: 44px; min-width: 44px; border: 0; background: transparent; color: inherit; font: inherit; text-align: left; padding: 0 4px; cursor: pointer; }
th[aria-sort="ascending"] button::after { content: " \2191"; }
th[aria-sort="descending"] button::after { content: " \2193"; }
tbody td:nth-child(2) { min-width: 10rem; }
tbody td:nth-child(3) { min-width: 8rem; }
tbody td:nth-child(5) { min-width: 9rem; }
tbody td:nth-child(12) { min-width: 18rem; }
td, th { overflow-wrap: anywhere; }
tbody th { background: var(--surface); color: var(--text); }
code { color: var(--text-muted); }
</style>
