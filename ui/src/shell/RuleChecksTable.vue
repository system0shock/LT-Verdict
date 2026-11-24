<script setup lang="ts">
import { computed, ref } from 'vue'
import type { AnalysisResult } from '../types'
import { TABLES_LABELS } from './labels.tables'
import { ruleRows, sortRuleRows, type RuleSortKey } from './tables'

const props = defineProps<{ result: AnalysisResult }>()
const sortKey = ref<RuleSortKey>('default')
const sortDir = ref<1 | -1>(1)
const rows = computed(() => sortRuleRows(ruleRows(props.result), sortKey.value, sortDir.value))

const sortColumns: Array<RuleSortKey | null> = ['ruleId', 'scope', 'metric', null, null, 'window', null, 'status', null]

function sortAria(key: RuleSortKey | null): 'ascending' | 'descending' | 'none' | undefined {
  if (!key) return undefined
  return sortKey.value !== key ? 'none' : sortDir.value === 1 ? 'ascending' : 'descending'
}

function toggleSort(key: RuleSortKey) {
  if (sortKey.value === key) sortDir.value = sortDir.value === 1 ? -1 : 1
  else {
    sortKey.value = key
    sortDir.value = 1
  }
}
</script>

<template>
  <section
    id="policy-results"
    class="panel"
    lang="ru"
    aria-labelledby="policy-results-title"
  >
    <h2 id="policy-results-title">
      {{ TABLES_LABELS.rulesTitle }}
    </h2>
    <p
      v-if="!rows.length"
      data-testid="rules-empty"
    >
      {{ TABLES_LABELS.rulesEmpty }}
    </p>
    <div
      v-else
      class="table-wrap"
      tabindex="0"
      role="region"
      :aria-label="TABLES_LABELS.regionRules"
    >
      <table>
        <thead>
          <tr>
            <th
              v-for="(head, index) in TABLES_LABELS.ruleHeads"
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
            :id="`ev-${row.key}`"
            :key="row.key"
            data-testid="rule-row"
            :data-evidence-id="row.key"
            tabindex="-1"
          >
            <th scope="row">
              {{ row.ruleId }}
            </th>
            <td>{{ row.scope }}</td>
            <td>{{ row.metric }}</td>
            <td>{{ row.condition }} {{ row.threshold }}</td>
            <td>{{ row.observed }}</td>
            <td>{{ row.window ?? TABLES_LABELS.noWindow }}</td>
            <td data-testid="rule-sample">
              {{ row.sample }}
            </td>
            <td>
              <span
                class="status-text"
                :data-status="row.status"
              >{{ TABLES_LABELS.statusText[row.status] }}</span>
            </td>
            <td>
              <template v-if="row.reasonCode">
                {{ row.reasonText }} <code>{{ row.reasonCode }}</code>
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
th button { min-height: 44px; min-width: 44px; border: 0; background: transparent; color: inherit; font: inherit; text-align: left; padding: 0 4px; cursor: pointer; }
th[aria-sort="ascending"] button::after { content: " \2191"; }
th[aria-sort="descending"] button::after { content: " \2193"; }
td, th { overflow-wrap: anywhere; }
.status-text { white-space: nowrap; overflow-wrap: normal; }
tbody th { background: var(--surface); color: var(--text); }
code { color: var(--text-muted); }
</style>
