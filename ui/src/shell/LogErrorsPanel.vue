<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { getErrorGroups } from '../api'
import type { AnalysisResult, ErrorGroupItem, ErrorGroupsResponse, OpenSearchEvidence } from '../types'
import { formatNumber, formatOffset } from './overview'
import { runStartMs } from './deep'

const props = defineProps<{
  result: AnalysisResult
  runId: string
  analysisId: string
}>()

const emit = defineEmits<{
  'open-service': [service: string]
}>()

const errorGroups = ref<ErrorGroupsResponse | null>(null)
const activeTab = ref<'pvs' | 'jtl'>('pvs')
const query = ref('')
const expanded = ref(false)
const controller = new AbortController()

const originMs = computed(() => runStartMs(props.result))

const openSearchContexts = computed<OpenSearchEvidence[]>(() =>
  props.result.evidence
    .filter((item): item is OpenSearchEvidence => item.type === 'opensearch_errors')
    .sort((left, right) => (left.profile_id < right.profile_id ? -1 : left.profile_id > right.profile_id ? 1 : 0)),
)

interface PvsLogRow {
  key: string
  profileId: string
  service: string
  errorType: string
  count: number
  firstEpochMs: number
  lastEpochMs: number
  message: string
  truncated: boolean
  sourceUrl: string | null
}

function numeric(value: number | string | undefined): number {
  if (typeof value === 'number') return Number.isFinite(value) ? value : 0
  if (typeof value === 'string') {
    const parsed = Number(value)
    return Number.isFinite(parsed) ? parsed : 0
  }
  return 0
}

function safeHttpUrl(url: string | undefined): string | null {
  if (!url) return null
  try {
    const parsed = new URL(url)
    return parsed.protocol === 'http:' || parsed.protocol === 'https:' ? parsed.toString() : null
  } catch {
    return null
  }
}

function formatEpochWindow(fromMs: number, toMs: number): string {
  const fmt = (ms: number) => {
    if (originMs.value !== null && ms >= originMs.value) return `+${formatOffset(ms - originMs.value)}`
    const d = new Date(ms)
    return Number.isNaN(d.getTime()) ? '—' : d.toISOString().slice(11, 19)
  }
  return `${fmt(fromMs)}–${fmt(toMs)}`
}

const pvsRows = computed<PvsLogRow[]>(() => {
  const rows: PvsLogRow[] = []
  for (const ctx of openSearchContexts.value) {
    for (const group of ctx.groups) {
      const sample = group.samples[0]
      rows.push({
        key: `${ctx.profile_id}:${group.service}:${group.error_type}`,
        profileId: ctx.profile_id,
        service: group.service,
        errorType: group.error_type,
        count: numeric(group.count),
        firstEpochMs: group.first_epoch_ms,
        lastEpochMs: group.last_epoch_ms,
        message: sample?.message ?? '—',
        truncated: Boolean(sample?.message_truncated),
        sourceUrl: safeHttpUrl(sample?.source_url),
      })
    }
  }
  return rows.sort((a, b) => b.count - a.count)
})

const totalPvsErrors = computed(() =>
  openSearchContexts.value.reduce((sum, ctx) => sum + numeric(ctx.total_errors), 0),
)

const totalPvsRate = computed(() =>
  openSearchContexts.value.reduce((sum, ctx) => sum + numeric(ctx.error_rate_per_minute), 0),
)

const pvsCoverage = computed(() => openSearchContexts.value[0]?.coverage.status ?? null)

const jtlGroups = computed<ErrorGroupItem[]>(() => errorGroups.value?.groups ?? [])
const totalJtlErrors = computed(() =>
  errorGroups.value?.total_error_count ?? jtlGroups.value.reduce((sum, item) => sum + item.count, 0),
)

const hasPvs = computed(() => openSearchContexts.value.length > 0 && (pvsRows.value.length > 0 || totalPvsErrors.value > 0))
const hasJtl = computed(() => jtlGroups.value.length > 0)
const showPanel = computed(() => hasPvs.value || hasJtl.value)

const filteredPvs = computed(() => {
  const q = query.value.trim().toLowerCase()
  if (!q) return pvsRows.value
  return pvsRows.value.filter(
    (row) =>
      row.service.toLowerCase().includes(q)
      || row.errorType.toLowerCase().includes(q)
      || row.message.toLowerCase().includes(q),
  )
})

const visiblePvs = computed(() => (expanded.value ? filteredPvs.value : filteredPvs.value.slice(0, 5)))

const filteredJtl = computed(() => {
  const q = query.value.trim().toLowerCase()
  if (!q) return jtlGroups.value
  return jtlGroups.value.filter(
    (item) =>
      (item.response_code ?? '').toLowerCase().includes(q)
      || item.scope.label.toLowerCase().includes(q)
      || (item.message ?? '').toLowerCase().includes(q),
  )
})

const visibleJtl = computed(() => (expanded.value ? filteredJtl.value : filteredJtl.value.slice(0, 5)))

onMounted(async () => {
  if (!hasPvs.value) activeTab.value = 'jtl'
  if (props.result.run_validity === 'INVALID') return
  try {
    const response = await getErrorGroups(props.runId, props.analysisId, controller.signal)
    if (!controller.signal.aborted) {
      errorGroups.value = response
      if (!hasPvs.value && response.groups.length > 0) activeTab.value = 'jtl'
    }
  } catch {
    // Non-fatal: Overview stays usable when error-groups artifact is absent.
  }
})

onUnmounted(() => controller.abort())
</script>

<template>
  <section
    v-if="showPanel"
    id="overview-log-errors"
    class="panel log-errors-panel"
    data-testid="overview-log-errors"
    aria-labelledby="overview-log-errors-title"
  >
    <div class="log-errors-panel__head">
      <div class="log-errors-panel__title-block">
        <div class="log-errors-panel__badges">
          <h2 id="overview-log-errors-title">
            Ошибки ПВС из логов и ответы протокола
          </h2>
          <span
            v-if="hasPvs"
            class="log-pill log-pill--crit mono"
            data-testid="pvs-errors-total"
          >
            {{ formatNumber(totalPvsErrors, 0) }} в логах ПВС · {{ formatNumber(totalPvsRate, 1) }}/мин
          </span>
          <span
            v-if="hasJtl"
            class="log-pill log-pill--warn mono"
            data-testid="jtl-errors-total"
          >
            {{ formatNumber(totalJtlErrors, 0) }} ошибок генератора
          </span>
          <span
            v-if="pvsCoverage"
            class="log-pill log-pill--info"
          >
            OpenSearch: {{ pvsCoverage }}
          </span>
        </div>
        <p class="muted">
          Сгруппированные исключения прикладных и внешних сервисов из логов OpenSearch и коды ответов генератора (справочный контекст, вердикт ядра не меняют).
        </p>
      </div>

      <div class="log-errors-panel__controls">
        <input
          v-model="query"
          type="search"
          class="log-errors-panel__search"
          data-testid="log-errors-search"
          placeholder="Фильтр по сервису ПВС / ошибке…"
          aria-label="Фильтр ошибок ПВС и протокола"
        >
        <div
          v-if="hasPvs && hasJtl"
          class="log-errors-panel__tabs"
          role="tablist"
          aria-label="Источник ошибок"
        >
          <button
            type="button"
            role="tab"
            :aria-selected="activeTab === 'pvs'"
            data-testid="log-errors-tab-pvs"
            @click="activeTab = 'pvs'"
          >
            Логи ПВС · OpenSearch ({{ pvsRows.length }})
          </button>
          <button
            type="button"
            role="tab"
            :aria-selected="activeTab === 'jtl'"
            data-testid="log-errors-tab-jtl"
            @click="activeTab = 'jtl'"
          >
            Ответы генератора · JTL ({{ jtlGroups.length }})
          </button>
        </div>
      </div>
    </div>

    <div
      v-if="activeTab === 'pvs' && hasPvs"
      class="table-wrap"
      tabindex="0"
      role="region"
      aria-label="Ошибки ПВС из логов OpenSearch"
    >
      <table data-testid="pvs-log-errors-table">
        <thead>
          <tr>
            <th scope="col">
              Сервис / Узел ПВС
            </th>
            <th scope="col">
              Тип ошибки / Исключение
            </th>
            <th scope="col">
              Событий (доля)
            </th>
            <th scope="col">
              Окно
            </th>
            <th scope="col">
              Образец сообщения из лога
            </th>
            <th scope="col">
              Действия
            </th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="row in visiblePvs"
            :key="row.key"
            data-testid="pvs-log-error-row"
          >
            <td><strong>{{ row.service }}</strong></td>
            <td class="mono log-errors-panel__type">
              {{ row.errorType }}
            </td>
            <td class="mono">
              <strong>{{ formatNumber(row.count, 0) }}</strong>
              <span
                v-if="totalPvsErrors > 0"
                class="muted"
              > ({{ formatNumber((row.count / totalPvsErrors) * 100, 1) }}%)</span>
            </td>
            <td class="mono muted">
              {{ formatEpochWindow(row.firstEpochMs, row.lastEpochMs) }}
            </td>
            <td class="mono log-errors-panel__msg">
              {{ row.message }}{{ row.truncated ? '…' : '' }}
            </td>
            <td>
              <div class="log-errors-panel__actions">
                <a
                  v-if="row.sourceUrl"
                  :href="row.sourceUrl"
                  target="_blank"
                  rel="noopener noreferrer"
                  class="log-action-link"
                >Лог ↗</a>
                <button
                  type="button"
                  class="log-action-btn"
                  @click="emit('open-service', row.service)"
                >
                  На график →
                </button>
              </div>
            </td>
          </tr>
          <tr v-if="!visiblePvs.length">
            <td
              colspan="6"
              class="muted"
            >
              По фильтру «{{ query }}» групп ошибок не найдено.
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <div
      v-else-if="hasJtl"
      class="table-wrap"
      tabindex="0"
      role="region"
      aria-label="Ошибки ответов генератора"
    >
      <table data-testid="jtl-error-groups-table">
        <thead>
          <tr>
            <th scope="col">
              Код ответа
            </th>
            <th scope="col">
              Транзакция
            </th>
            <th scope="col">
              Ошибок (доля)
            </th>
            <th scope="col">
              Окно
            </th>
            <th scope="col">
              Сообщение генератора
            </th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="item in visibleJtl"
            :key="item.id"
            data-testid="jtl-error-group-row"
          >
            <td>
              <span class="log-pill log-pill--crit mono">{{ item.response_code ?? '—' }}</span>
            </td>
            <td class="mono">
              <strong>{{ item.scope.label }}</strong>
            </td>
            <td class="mono">
              <strong>{{ formatNumber(item.count, 0) }}</strong>
              <span
                v-if="totalJtlErrors > 0"
                class="muted"
              > ({{ formatNumber((item.count / totalJtlErrors) * 100, 1) }}%)</span>
            </td>
            <td class="mono muted">
              {{ formatEpochWindow(item.from_epoch_ms, item.to_epoch_ms) }}
            </td>
            <td class="mono log-errors-panel__msg">
              {{ item.message ?? '—' }}
            </td>
          </tr>
          <tr v-if="!visibleJtl.length">
            <td
              colspan="5"
              class="muted"
            >
              По фильтру «{{ query }}» групп ошибок не найдено.
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <div
      v-if="(activeTab === 'pvs' ? filteredPvs.length : filteredJtl.length) > 5"
      class="log-errors-panel__foot"
    >
      <span class="muted">
        Показано {{ activeTab === 'pvs' ? visiblePvs.length : visibleJtl.length }} из {{ activeTab === 'pvs' ? filteredPvs.length : filteredJtl.length }} групп
      </span>
      <button
        type="button"
        class="log-action-btn"
        data-testid="log-errors-expand"
        @click="expanded = !expanded"
      >
        {{ expanded ? 'Свернуть до топ-5' : `Показать все ${activeTab === 'pvs' ? filteredPvs.length : filteredJtl.length}` }}
      </button>
    </div>
  </section>
</template>

<style scoped>
.log-errors-panel {
  display: grid;
  gap: 12px;
  border-left: 3px solid var(--fail);
}

.log-errors-panel__head {
  display: flex;
  flex-wrap: wrap;
  justify-content: space-between;
  align-items: flex-start;
  gap: 10px 14px;
}

.log-errors-panel__title-block {
  display: grid;
  gap: 4px;
  min-width: 0;
}

.log-errors-panel__badges {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

.log-errors-panel__controls {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

.log-errors-panel__search {
  min-width: 220px;
}

.log-errors-panel__tabs {
  display: inline-flex;
  gap: 4px;
  padding: 2px;
  border: 1px solid var(--border);
  border-radius: 6px;
  background: var(--surface-inset);
}

.log-errors-panel__tabs button {
  border: 0;
  border-radius: 5px;
  background: transparent;
  color: var(--text-muted);
}

.log-errors-panel__tabs button[aria-selected="true"] {
  background: var(--surface);
  color: var(--text);
  font-weight: 600;
}

.log-pill {
  display: inline-flex;
  align-items: center;
  padding: 2px 8px;
  border-radius: 999px;
  font-size: 11.5px;
  font-weight: 650;
}

.log-pill--crit {
  background: var(--fail-bg);
  color: var(--fail);
}

.log-pill--warn {
  background: var(--warn-bg);
  color: var(--warn);
}

.log-pill--info {
  background: var(--surface-muted);
  color: var(--text-muted);
}

.log-errors-panel__type {
  color: var(--fail);
  font-weight: 600;
}

.log-errors-panel__msg {
  max-width: 420px;
  white-space: normal;
  overflow-wrap: anywhere;
  font-size: 12px;
  color: var(--text-muted);
}

.log-errors-panel__actions {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.log-action-link {
  white-space: nowrap;
  font-size: 12px;
}

.log-action-btn {
  white-space: nowrap;
  font-size: 12px;
}

.log-errors-panel__foot {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
}
</style>
