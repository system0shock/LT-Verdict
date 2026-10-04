<script setup lang="ts">
import { computed } from 'vue'
import type { AnalysisResult } from '../types'
import { TABLES_LABELS } from './labels.tables'
import { ruleRows } from './tables'

const props = defineProps<{ result: AnalysisResult }>()
const rows = computed(() => ruleRows(props.result))
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
              v-for="head in TABLES_LABELS.ruleHeads"
              :key="head"
              scope="col"
            >
              {{ head }}
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
td, th { overflow-wrap: anywhere; }
tbody th { background: var(--surface); color: var(--text); }
code { color: var(--text-muted); }
</style>
