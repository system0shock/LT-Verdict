<script setup lang="ts">
import { computed } from 'vue'
import type { AnalysisResult } from '../types'
import { CAPACITY_LABELS } from './labels.tables'
import { capacityView } from './tables'

const props = defineProps<{ result: AnalysisResult }>()
const view = computed(() => capacityView(props.result))
</script>

<template>
  <section
    v-if="view"
    id="capacity-results"
    data-testid="capacity-results"
    class="panel"
    lang="ru"
    aria-labelledby="capacity-results-title"
  >
    <h2 id="capacity-results-title">
      {{ CAPACITY_LABELS.title }}
    </h2>
    <dl>
      <dt>{{ CAPACITY_LABELS.axisLabel }}</dt>
      <dd>{{ CAPACITY_LABELS.axisValue(view.axis, view.unit) }}</dd>
      <dt>{{ CAPACITY_LABELS.boundLabel }}</dt>
      <dd>{{ view.boundText }}</dd>
      <dt>{{ CAPACITY_LABELS.verdictLabel }}</dt>
      <dd>{{ view.verdictText }}</dd>
      <dt>{{ CAPACITY_LABELS.kneeLabel }}</dt>
      <dd>{{ view.kneeText }}</dd>
      <template v-if="view.kneeDiagnosticText">
        <dt>{{ CAPACITY_LABELS.kneeDiagnosticLabel }}</dt>
        <dd data-testid="capacity-knee-diagnostic">
          {{ view.kneeDiagnosticText }}
        </dd>
      </template>
      <dt>{{ CAPACITY_LABELS.reasonsLabel }}</dt>
      <dd>
        <template v-if="view.reasons.length">
          <span
            v-for="reason in view.reasons"
            :key="reason.code"
          >{{ reason.text }} <code>{{ reason.code }}</code> </span>
        </template>
        <template v-else>
          {{ CAPACITY_LABELS.noReasons }}
        </template>
      </dd>
    </dl>
    <p
      v-if="view.smallSample"
      data-testid="capacity-small-sample"
      role="note"
    >
      {{ CAPACITY_LABELS.smallSampleNote }}
    </p>
    <div
      class="table-wrap"
      tabindex="0"
      role="region"
      :aria-label="CAPACITY_LABELS.region"
    >
      <table>
        <thead>
          <tr>
            <th
              v-for="head in CAPACITY_LABELS.heads"
              :key="head"
              scope="col"
            >
              {{ head }}
            </th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="row in view.stages"
            :id="`stage-${row.key}`"
            :key="row.key"
            data-testid="capacity-stage-row"
          >
            <th scope="row">
              {{ row.stage }}
            </th>
            <td>{{ row.target }}</td>
            <td>{{ row.achieved }}</td>
            <td>{{ row.observed }}</td>
            <td>{{ row.bins }}</td>
            <td>{{ row.verified }}</td>
            <td>
              <span
                class="status-text"
                :data-status="row.verdict"
              >{{ row.verdictText }}</span>
              <span
                v-if="row.smallSample"
                class="stage-mark"
                data-testid="stage-small-sample"
              >{{ CAPACITY_LABELS.smallSampleMark }}</span>
            </td>
            <td>
              <template v-if="row.reasons.length">
                <span
                  v-for="reason in row.reasons"
                  :key="reason.code"
                >{{ reason.text }} <code>{{ reason.code }}</code> </span>
              </template>
              <template v-else>
                {{ CAPACITY_LABELS.noReasons }}
              </template>
            </td>
            <td>
              <details v-if="row.evidence.length">
                <summary>{{ CAPACITY_LABELS.evidenceSummary(row.evidence.length) }}</summary>
                <ul class="capacity-evidence">
                  <li
                    v-for="id in row.evidence"
                    :key="id"
                  >
                    <code>{{ id }}</code>
                  </li>
                </ul>
              </details><template v-else>
                {{ CAPACITY_LABELS.noEvidence }}
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
table { min-width: 80rem; }
tbody td:nth-child(7) { min-width: 10rem; }
tbody td:nth-child(8) { min-width: 18rem; }
tbody td:nth-child(9) { min-width: 12rem; }
td, th, dd { overflow-wrap: anywhere; }
tbody th { background: var(--surface); color: var(--text); }
code { color: var(--text-muted); }
dt { font-weight: 600; }
.capacity-evidence { margin: 0; padding: 0; list-style: none; }
.stage-mark { margin-inline-start: 0.5em; font-style: italic; }
</style>
