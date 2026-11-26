<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { getResult } from '../api'
import type { ArmResult } from '../platformTypes'
import type { AnalysisResult, AnalysisSummary } from '../types'
import { reasonText } from '../verdictReasons'
import { PLATFORM_MAP_LABELS as LABELS } from './platformLabels'
import { armAnalyses, buildServiceArmMap, problemServices } from './platformMap'

const props = defineProps<{
  runId: string
  analyses: AnalysisSummary[]
  analysisId: string
  result: AnalysisResult
}>()

const emit = defineEmits<{ 'open-service': [service: string] }>()

const loaded = ref<Record<string, AnalysisResult>>({})
const failures = ref<Record<string, string>>({})
const loading = ref(false)
const onlyProblems = ref(false)
const search = ref('')
const picks = computed(() => armAnalyses(props.analyses, props.analysisId))
const results = computed<ArmResult[]>(() => picks.value.map((pick) => ({
  arm: pick.arm,
  result: pick.analysisId === props.analysisId ? props.result : loaded.value[pick.analysisId] ?? null,
})))
const map = computed(() => buildServiceArmMap(results.value))
const problems = computed(() => new Set(problemServices(map.value)))
const rows = computed(() => {
  const q = search.value.trim().toLowerCase()
  const base = onlyProblems.value ? map.value.services.filter((service) => problems.value.has(service)) : map.value.services
  return q ? base.filter((service) => service.toLowerCase().includes(q)) : base
})
const duplicates = computed(() => picks.value.filter((pick) => pick.analysesOfArm > 1))
const controller = new AbortController()

watch(() => picks.value.map((pick) => pick.analysisId).join('|'), async () => {
  const missing = picks.value.filter((pick) => pick.analysisId !== props.analysisId && !(pick.analysisId in loaded.value))
  if (!missing.length) return
  loading.value = true
  await Promise.all(missing.map(async (pick) => {
    try {
      const value = await getResult(props.runId, pick.analysisId)
      if (!controller.signal.aborted) loaded.value = { ...loaded.value, [pick.analysisId]: value }
    } catch (failure) {
      if (!controller.signal.aborted) failures.value = { ...failures.value, [pick.arm]: failure instanceof Error ? failure.message : String(failure) }
    }
  }))
  if (!controller.signal.aborted) loading.value = false
}, { immediate: true })
onUnmounted(() => controller.abort())

const cellOf = (service: string, arm: string) => map.value.cells.find((cell) => cell.service === service && cell.arm === arm)
</script>

<template>
  <section
    id="overview-platform-map"
    class="panel platform-map"
    data-testid="platform-map"
    aria-labelledby="overview-platform-map-title"
  >
    <div class="platform-map__head">
      <div>
        <h2 id="overview-platform-map-title">
          {{ LABELS.title }}
        </h2>
        <p class="v4-sub">
          {{ LABELS.lead }}
        </p>
      </div>
      <div
        v-if="map.services.length"
        class="platform-map__controls"
      >
        <input
          v-model="search"
          type="search"
          class="v4-inp"
          data-testid="platform-map-search"
          placeholder="Поиск сервиса…"
          aria-label="Поиск сервиса в матрице плеч"
        >
        <label class="v4-check">
          <input
            v-model="onlyProblems"
            type="checkbox"
            data-testid="platform-map-only-problems"
          >
          {{ LABELS.onlyProblems }}
        </label>
      </div>
    </div>
    <p
      v-if="loading"
      class="v4-sub"
      role="status"
      data-testid="platform-map-loading"
    >
      {{ LABELS.loading }}
    </p>
    <p
      v-for="(message, arm) in failures"
      :key="arm"
      class="notice notice-error"
      role="alert"
      data-testid="platform-map-error"
    >
      {{ LABELS.loadFailed(String(arm), message) }}
    </p>
    <p
      v-if="!map.services.length && !loading"
      class="v4-sub"
      data-testid="platform-map-empty"
    >
      {{ LABELS.empty }}
    </p>
    <template v-else-if="map.services.length">
      <p
        class="v4-sub"
        data-testid="platform-map-summary"
      >
        {{ problems.size ? LABELS.summary(problems.size, map.services.length) : LABELS.noProblems }}
      </p>
      <div
        class="v4-table-wrap platform-map__scroll"
        tabindex="0"
        role="region"
        :aria-label="LABELS.region"
      >
        <table data-testid="platform-map-table">
          <thead>
            <tr>
              <th scope="col">
                {{ LABELS.service }} ({{ rows.length }})
              </th>
              <th
                v-for="arm in map.arms"
                :key="arm"
                scope="col"
                data-testid="platform-map-arm"
              >
                {{ LABELS.arm(arm) }}
              </th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="service in rows"
              :key="service"
              data-testid="platform-map-row"
              :data-service="service"
            >
              <th scope="row">
                <button
                  type="button"
                  class="platform-map__service-btn"
                  @click="emit('open-service', service)"
                >
                  {{ service }}
                </button>
              </th>
              <td
                v-for="arm in map.arms"
                :key="arm"
                data-testid="platform-map-cell"
                :data-arm="arm"
              >
                <template v-if="cellOf(service, arm)">
                  <span
                    class="status-text"
                    :data-status="cellOf(service, arm)!.state"
                    :data-state="cellOf(service, arm)!.state"
                  ><span aria-hidden="true">{{ LABELS.states[cellOf(service, arm)!.state].mark }}</span>&nbsp;{{ LABELS.states[cellOf(service, arm)!.state].text }}</span>
                  <span
                    v-if="cellOf(service, arm)!.failedRuleIds.length"
                    class="muted platform-map__detail"
                  >{{ LABELS.failedRules(cellOf(service, arm)!.failedRuleIds) }}</span>
                  <span
                    v-for="reason in cellOf(service, arm)!.reasons"
                    :key="reason"
                    class="muted platform-map__detail"
                  >{{ reason }}: {{ reasonText(reason) }}</span>
                </template>
                <span
                  v-else
                  class="muted"
                  data-testid="platform-map-no-analysis"
                >{{ LABELS.noAnalysis }}</span>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <p
        v-for="pick in duplicates"
        :key="pick.arm"
        class="v4-sub"
        data-testid="platform-map-duplicate-note"
      >
        {{ LABELS.duplicates(pick.arm, pick.analysesOfArm) }}
      </p>
    </template>
  </section>
</template>

<style scoped>
.platform-map {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.platform-map__head {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-start;
  justify-content: space-between;
  gap: 8px 14px;
}

.platform-map__head h2 {
  font-size: 17px;
  line-height: 1.2;
}

.platform-map__scroll {
  max-height: 380px;
  overflow-y: auto;
}
</style>
