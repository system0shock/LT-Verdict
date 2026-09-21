<script setup lang="ts">
import { computed, ref } from 'vue'
import type { TransactionComparison, TransactionMetric, TransactionScope } from './analyticsTypes'

const props = defineProps<{ comparison: TransactionComparison }>()
const filter = ref('')
const rows = computed(() => {
  const query = filter.value.trim().toLocaleLowerCase()
  if (!query) return props.comparison.rows
  return props.comparison.rows.filter((row) => [...row.scope.group_path, row.scope.label, row.scope.sample_kind].join('/').toLocaleLowerCase().includes(query))
})

function name(scope: TransactionScope) {
  return [...scope.group_path, scope.label].join(' / ')
}

function value(value: string | null, reason?: string | null) {
  return value === null ? `N/A${reason ? ` (${reason})` : ''}` : value
}

function delta(metric: TransactionMetric) {
  if (metric.delta === null) return value(null, metric.reason)
  return metric.delta_percent === null ? `${metric.delta}${metric.percent_reason ? ` (${metric.percent_reason})` : ''}` : `${metric.delta} (${metric.delta_percent}%)`
}
</script>

<template>
  <section aria-labelledby="transaction-comparison-title">
    <h3 id="transaction-comparison-title">
      Transactions against baseline
    </h3>
    <p
      v-if="!comparison.compatible"
      class="notice notice-fail"
      role="status"
    >
      Metric definitions are incompatible. Values remain visible, but deltas are unavailable.
    </p>
    <p
      v-if="comparison.truncated"
      class="field__hint"
      role="status"
    >
      Showing a bounded subset of {{ comparison.matched_count }} matching transactions. Narrow the server-side filter for more detail.
    </p>
    <div class="field">
      <label for="transaction-comparison-filter">Filter transactions</label>
      <input
        id="transaction-comparison-filter"
        v-model="filter"
        type="search"
      >
    </div>
    <div
      class="table-wrap"
      tabindex="0"
      role="region"
      aria-label="Transaction metric comparison"
    >
      <table>
        <thead>
          <tr>
            <th scope="col">
              Transaction
            </th><th scope="col">
              Kind
            </th><th scope="col">
              Metric / unit
            </th><th scope="col">
              Baseline
            </th><th scope="col">
              Current
            </th><th scope="col">
              Delta
            </th>
          </tr>
        </thead>
        <tbody>
          <template
            v-for="row in rows"
            :key="`${row.scope.group_path.join('/')}/${row.scope.label}/${row.scope.sample_kind}`"
          >
            <tr
              v-for="metric in row.metrics"
              :key="metric.metric"
            >
              <th scope="row">
                {{ name(row.scope) }}
              </th>
              <td>{{ row.scope.sample_kind }}</td>
              <td>{{ metric.metric }} / {{ metric.unit }}</td>
              <td>{{ value(metric.baseline, metric.reason) }}</td>
              <td>{{ value(metric.current, metric.reason) }}</td>
              <td>{{ delta(metric) }}</td>
            </tr>
          </template>
        </tbody>
      </table>
    </div>
  </section>
</template>
