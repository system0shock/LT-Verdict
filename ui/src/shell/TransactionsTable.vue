<script setup lang="ts">
import { computed, ref } from 'vue'
import type { AnalysisResult } from '../types'
import { TABLES_LABELS } from './labels.tables'
import { DEFAULT_TX_QUERY, queryTransactions, transactionRows, type TxQuery, type TxSortKey, type TxStatus } from './tables'

const props = defineProps<{ result: AnalysisResult }>()
const query = ref<TxQuery>({ ...DEFAULT_TX_QUERY })
const rows = computed(() => transactionRows(props.result))
const shown = computed(() => queryTransactions(rows.value, query.value))
const statuses: TxStatus[] = ['FAIL', 'NO_VERDICT', 'PASS', 'NO_POLICY', 'NOT_CHECKED']
const heads = TABLES_LABELS.txHeads

function sortAria(key: TxSortKey): 'ascending' | 'descending' | 'none' {
  return query.value.sort !== key ? 'none' : query.value.dir === 1 ? 'ascending' : 'descending'
}

function toggleSort(key: TxSortKey) {
  query.value = { ...query.value, sort: key, dir: query.value.sort === key ? (query.value.dir === 1 ? -1 : 1) : key === 'label' ? 1 : -1 }
}
</script>

<template>
  <section
    id="transaction-metrics"
    class="panel"
    lang="ru"
    aria-labelledby="transaction-metrics-title"
  >
    <h2 id="transaction-metrics-title">
      {{ TABLES_LABELS.txTitle }}
    </h2>
    <div class="tx-controls">
      <input
        v-model="query.text"
        type="search"
        :aria-label="TABLES_LABELS.searchLabel"
        :placeholder="TABLES_LABELS.searchLabel"
      >
      <select
        v-model="query.status"
        :aria-label="TABLES_LABELS.statusFilterLabel"
      >
        <option value="all">
          {{ TABLES_LABELS.allStatuses }}
        </option>
        <option
          v-for="status in statuses"
          :key="status"
          :value="status"
        >
          {{ TABLES_LABELS.statusText[status] }}
        </option>
      </select>
    </div>
    <p role="status">
      {{ TABLES_LABELS.shownOf(shown.length, rows.length) }}
    </p>
    <p
      v-if="query.sort === 'impact'"
      class="tx-path"
      data-testid="tx-default-order"
    >
      {{ TABLES_LABELS.impactOrder }}
    </p>
    <p
      v-if="!rows.length"
      data-testid="tx-empty"
    >
      {{ TABLES_LABELS.txEmpty }}
    </p>
    <p
      v-else-if="!shown.length"
      role="status"
      data-testid="tx-no-match"
    >
      {{ TABLES_LABELS.txNoMatch }}
    </p>
    <div
      v-else
      class="table-wrap"
      tabindex="0"
      role="region"
      :aria-label="TABLES_LABELS.regionTx"
    >
      <table>
        <thead>
          <tr>
            <th
              scope="col"
              :aria-sort="sortAria('label')"
            >
              <button
                type="button"
                :aria-label="TABLES_LABELS.sortBy(heads.label)"
                @click="toggleSort('label')"
              >
                {{ heads.label }}
              </button>
            </th>
            <th
              scope="col"
              :aria-sort="sortAria('samples')"
            >
              <button
                type="button"
                :aria-label="TABLES_LABELS.sortBy(heads.samples)"
                @click="toggleSort('samples')"
              >
                {{ heads.samples }}
              </button>
            </th>
            <th
              scope="col"
              :aria-sort="sortAria('errors')"
            >
              <button
                type="button"
                :aria-label="TABLES_LABELS.sortBy(heads.errors)"
                @click="toggleSort('errors')"
              >
                {{ heads.errors }}
              </button>
            </th>
            <th
              scope="col"
              :aria-sort="sortAria('errorRate')"
            >
              <button
                type="button"
                :aria-label="TABLES_LABELS.sortBy(heads.errorRate)"
                @click="toggleSort('errorRate')"
              >
                {{ heads.errorRate }}
              </button>
            </th>
            <th scope="col">
              {{ heads.p50 }}
            </th>
            <th
              scope="col"
              :aria-sort="sortAria('p95')"
            >
              <button
                type="button"
                :aria-label="TABLES_LABELS.sortBy(heads.p95)"
                @click="toggleSort('p95')"
              >
                {{ heads.p95 }}
              </button>
            </th>
            <th
              scope="col"
              :aria-sort="sortAria('p99')"
            >
              <button
                type="button"
                :aria-label="TABLES_LABELS.sortBy(heads.p99)"
                @click="toggleSort('p99')"
              >
                {{ heads.p99 }}
              </button>
            </th>
            <th
              scope="col"
              :aria-sort="sortAria('rps')"
            >
              <button
                type="button"
                :aria-label="TABLES_LABELS.sortBy(heads.rps)"
                @click="toggleSort('rps')"
              >
                {{ heads.rps }}
              </button>
            </th>
            <th scope="col">
              {{ heads.status }}
            </th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="row in shown"
            :id="`ev-${row.key}`"
            :key="row.key"
            data-testid="tx-row"
            :data-evidence-id="row.key"
            tabindex="-1"
          >
            <th scope="row">
              <span
                v-if="row.path"
                class="tx-path"
              >{{ row.path }}</span>{{ row.path ? ' / ' : '' }}<span>{{ row.label }}</span>
            </th>
            <td>{{ row.samples }}</td>
            <td>{{ row.errors }}</td>
            <td>{{ row.errorRate }}</td>
            <td>{{ row.p50 }}</td>
            <td>{{ row.p95 }}</td>
            <td>{{ row.p99 }}</td>
            <td>{{ row.rps }}</td>
            <td>
              <span
                class="status-text"
                :data-status="row.status"
              >{{ TABLES_LABELS.statusText[row.status] }}</span>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </section>
</template>

<style scoped>
section { min-width: 0; }
.tx-controls { display: flex; flex-wrap: wrap; gap: 12px; }
.tx-controls input, .tx-controls select { min-height: 44px; min-width: 0; max-width: 100%; }
.tx-controls input { flex: 1 1 220px; }
th button { min-height: 44px; min-width: 44px; border: 0; background: transparent; color: inherit; font: inherit; text-align: left; padding: 0 4px; cursor: pointer; }
th[aria-sort="ascending"] button::after { content: " \2191"; }
th[aria-sort="descending"] button::after { content: " \2193"; }
td, th { overflow-wrap: anywhere; }
tbody th { background: var(--surface); color: var(--text); }
.tx-path { color: var(--text-muted); }
</style>
