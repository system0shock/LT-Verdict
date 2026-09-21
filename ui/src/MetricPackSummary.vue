<script setup lang="ts">
import type { MetricPackAnalysis } from './analyticsTypes'

defineProps<{ analysis: MetricPackAnalysis }>()
</script>

<template>
  <section aria-labelledby="metric-packs-title">
    <h3 id="metric-packs-title">
      JVM and OpenShift packs
    </h3>
    <div
      class="table-wrap"
      tabindex="0"
      role="region"
      aria-label="Metric pack capability summary"
    >
      <table>
        <thead>
          <tr>
            <th scope="col">
              Pack
            </th><th scope="col">
              Status
            </th><th scope="col">
              Available
            </th><th scope="col">
              Missing
            </th><th scope="col">
              Threshold findings
            </th><th scope="col">
              Reasons
            </th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="pack in analysis.packs"
            :key="pack.id"
            :data-testid="`metric-pack-${pack.id}`"
          >
            <th scope="row">
              {{ pack.id === 'jvm' ? 'JVM' : 'OpenShift' }}
            </th>
            <td>{{ pack.status }}</td>
            <td>{{ pack.available_capabilities.join(', ') || 'None' }}</td>
            <td>{{ pack.missing_capabilities.join(', ') || 'None' }}</td>
            <td>{{ pack.finding_refs.join(', ') || 'None' }}</td>
            <td>{{ pack.reasons.join(', ') || '—' }}</td>
          </tr>
        </tbody>
      </table>
    </div>
    <p class="field__hint">
      Pack findings reference existing explicit-threshold evidence. Missing telemetry is never treated as healthy.
    </p>
  </section>
</template>
