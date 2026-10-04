<script setup lang="ts">
import { computed } from 'vue'
import type { AnalysisResult } from '../types'
import { TREND_LABELS } from './labels.tables'
import { trendView } from './tables'

const props = defineProps<{ result: AnalysisResult }>()
const view = computed(() => trendView(props.result))
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
              v-for="head in TREND_LABELS.heads"
              :key="head"
              scope="col"
            >
              {{ head }}
            </th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="row in view.rows"
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
tbody td:nth-child(2) { min-width: 10rem; }
tbody td:nth-child(3) { min-width: 8rem; }
tbody td:nth-child(5) { min-width: 9rem; }
tbody td:nth-child(12) { min-width: 18rem; }
td, th { overflow-wrap: anywhere; }
tbody th { background: var(--surface); color: var(--text); }
code { color: var(--text-muted); }
</style>
