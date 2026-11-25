<script setup lang="ts">
import { computed, nextTick } from 'vue'
import type { AnalysisResult } from '../types'
import { TABLES_NAV_LABELS, type TablesSubTabKey } from './labels.tables'

const props = defineProps<{ result: AnalysisResult }>()
const model = defineModel<TablesSubTabKey>({ required: true })

const availableTabs = computed<TablesSubTabKey[]>(() => {
  const evidence = props.result.evidence
  const hasResources = evidence.some((item) =>
    item.type === 'resource_summary' || item.type === 'window_policy_summary'
    || item.type === 'resource_policy_check' || item.type === 'resource_binding')
  const hasTrends = evidence.some((item) => item.type === 'trend_summary' || item.type === 'trend_check')
  const hasDiagnostics = evidence.some((item) =>
    item.type === 'source_summary' || item.type === 'opensearch_errors' || item.type === 'postgres_context'
    || item.type === 'diagnostic_summary' || item.type === 'correlation_pair' || item.type === 'anomaly_check')
    || props.result.findings.some((item) => item.type === 'anomaly_episode')
  const tabs: TablesSubTabKey[] = ['all', 'rules', 'transactions']
  if (props.result.capacity_summary) tabs.push('capacity')
  if (hasResources) tabs.push('resources')
  if (hasTrends) tabs.push('trends')
  if (hasDiagnostics) tabs.push('diagnostics')
  if (props.result.run_validity !== 'INVALID') tabs.push('normalized')
  return tabs
})

const tabId = (key: TablesSubTabKey) => `tables-subtab-${key}`

async function moveTo(index: number) {
  const list = availableTabs.value
  if (!list.length) return
  const key = list[((index % list.length) + list.length) % list.length]
  model.value = key
  await nextTick()
  document.getElementById(tabId(key))?.focus()
}

function onKeydown(event: KeyboardEvent, index: number) {
  const list = availableTabs.value
  const target: Record<string, number> = {
    ArrowRight: index + 1,
    ArrowDown: index + 1,
    ArrowLeft: index - 1,
    ArrowUp: index - 1,
    Home: 0,
    End: list.length - 1,
  }
  if (!(event.key in target)) return
  event.preventDefault()
  void moveTo(target[event.key])
}
</script>

<template>
  <nav
    class="tables-subtabs"
    data-testid="tables-subtabs"
    lang="ru"
    :aria-label="TABLES_NAV_LABELS.navLabel"
  >
    <div
      class="tables-subtabs__list"
      role="tablist"
      aria-orientation="horizontal"
      :aria-label="TABLES_NAV_LABELS.navLabel"
    >
      <button
        v-for="(key, index) in availableTabs"
        :id="tabId(key)"
        :key="key"
        type="button"
        role="tab"
        class="tables-subtabs__tab"
        :aria-selected="model === key"
        aria-controls="tables-subpanel"
        :tabindex="model === key ? 0 : -1"
        @click="model = key"
        @keydown="onKeydown($event, index)"
      >
        {{ TABLES_NAV_LABELS.tabs[key] }}
      </button>
    </div>
  </nav>
</template>

<style scoped>
.tables-subtabs {
  min-width: 0;
}

.tables-subtabs__list {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  min-width: 0;
}

.tables-subtabs__tab {
  min-height: 44px;
  min-width: 44px;
  max-width: 100%;
  border: 1px solid var(--control-border);
  border-radius: var(--shell-radius, 4px);
  background: var(--surface);
  color: var(--text);
  padding: 8px 14px;
  white-space: normal;
  overflow-wrap: anywhere;
}

.tables-subtabs__tab[aria-selected="true"] {
  border-color: var(--brand);
  background: var(--info-bg);
  color: var(--brand);
  font-weight: 650;
}
</style>
