<script setup lang="ts">
import { computed, onUnmounted, ref } from 'vue'
import { ApiError, getPodViewMeta, getPodViewValues } from '../api'
import type { AnalysisSummary } from '../types'
import PodTable from './PodTable.vue'
import { POD_LABELS } from './podLabels'
import {
  cellLightness, gridKey, podViewGroups, rowLabel, valueRange, worstPods,
  type PodRow, type PodViewGroup, type PodViewMeta,
} from './podView'

const props = defineProps<{ runId: string; analyses: AnalysisSummary[]; analysisId: string }>()

const MAP_ROWS = 24
const LEVELS = 12
const MAX_PAGES = 20
const ROW_PX = 18

type Found = { state: 'loading' } | { state: 'none' } | { state: 'error'; message: string } | { state: 'ready'; analysisId: string; meta: PodViewMeta }

const groups = computed(() => podViewGroups(props.analyses, props.analysisId))
const started = ref(false)
const found = ref<Record<string, Found>>({})
const rows = ref<Record<string, PodRow[]>>({})
const rowsLoading = ref(false)
const rowsError = ref('')
const service = ref('')
const metric = ref('')
const view = ref<'map' | 'table'>('map')
const hover = ref('')
const controller = new AbortController()
let revision = 0
const key = (group: PodViewGroup) => group.arm ?? ''

const ready = computed(() => groups.value.flatMap((group) => {
  const item = found.value[key(group)]
  return item?.state === 'ready' ? [{ group, analysisId: item.analysisId, meta: item.meta }] : []
}))
const services = computed(() => [...new Set(ready.value.flatMap((item) => item.meta.services.map((entry) => entry.service)))].sort())
const metrics = computed(() => [...new Set(Object.values(rows.value).flatMap((list) => list.map((row) => row.metric)))].sort())
const gridsDiffer = computed(() => new Set(ready.value.map((item) => gridKey(item.meta))).size > 1)
const metricRows = (group: PodViewGroup) => (rows.value[key(group)] ?? []).filter((row) => row.metric === metric.value)
const units = computed(() => [...new Set(ready.value.flatMap((item) => metricRows(item.group).map((row) => row.unit)))])
const sharedScale = computed(() => units.value.length <= 1)
const sharedRange = computed(() => valueRange(ready.value.flatMap((item) => metricRows(item.group))))

function rangeOf(group: PodViewGroup) {
  return sharedScale.value ? sharedRange.value : valueRange(metricRows(group))
}

async function lookup(group: PodViewGroup) {
  for (const analysisId of group.analysisIds) {
    try {
      const meta = await getPodViewMeta(props.runId, analysisId, controller.signal)
      if (controller.signal.aborted) return
      const sameArm = (meta.arm ?? null) === group.arm
      found.value = { ...found.value, [key(group)]: sameArm && meta.resource_snapshot_sha256 === group.snapshotSha256
        ? { state: 'ready', analysisId, meta }
        : { state: 'error', message: POD_LABELS.mismatch } }
      return
    } catch (failure) {
      if (controller.signal.aborted) return
      // Только отсутствие артефакта ведёт к следующему кандидату: остальные ошибки показываются, а не прячутся.
      if (failure instanceof ApiError && failure.code === 'POD_VIEW_NOT_FOUND') continue
      found.value = { ...found.value, [key(group)]: { state: 'error', message: failure instanceof Error ? failure.message : String(failure) } }
      return
    }
  }
  found.value = { ...found.value, [key(group)]: { state: 'none' } }
}

async function loadRows() {
  const current = ++revision
  rowsLoading.value = true
  rowsError.value = ''
  const next: Record<string, PodRow[]> = {}
  try {
    await Promise.all(ready.value.filter((item) => item.meta.services.some((entry) => entry.service === service.value)).map(async (item) => {
      const collected: PodRow[] = []
      let after: string | undefined
      for (let page = 0; page < MAX_PAGES; page += 1) {
        const response = await getPodViewValues(props.runId, item.analysisId, service.value, after, controller.signal)
        collected.push(...response.rows)
        if (response.next_after === null) break
        after = response.next_after
      }
      next[key(item.group)] = collected
    }))
  } catch (failure) {
    if (current === revision && !controller.signal.aborted) rowsError.value = failure instanceof Error ? failure.message : String(failure)
  }
  if (current !== revision || controller.signal.aborted) return
  rows.value = next
  rowsLoading.value = false
  if (!metrics.value.includes(metric.value)) metric.value = metrics.value.find((name) => name === 'openshift_container_memory_limit_ratio') ?? metrics.value[0] ?? ''
}

async function start() {
  started.value = true
  found.value = Object.fromEntries(groups.value.map((group) => [key(group), { state: 'loading' } as Found]))
  await Promise.all(groups.value.map(lookup))
  if (controller.signal.aborted) return
  service.value = services.value[0] ?? ''
  if (service.value) await loadRows()
}

async function chooseService(event: Event) {
  service.value = (event.target as HTMLSelectElement).value
  hover.value = ''
  await loadRows()
}

const shownRows = (group: PodViewGroup) => worstPods(metricRows(group), MAP_ROWS)
const orderedRows = (group: PodViewGroup) => worstPods(metricRows(group), Number.MAX_SAFE_INTEGER)
const level = (value: number | null, group: PodViewGroup) => {
  const range = rangeOf(group)
  return value === null || !range ? -1 : Math.min(LEVELS - 1, Math.floor(cellLightness(value, range.min, range.max) * LEVELS))
}
const time = (meta: PodViewMeta, column: number) => new Date(meta.grid.start_epoch_ms + column * meta.grid.step_ms).toISOString().slice(11, 19)
const number = (value: number) => new Intl.NumberFormat('ru-RU', { maximumFractionDigits: 4 }).format(value)

function pointer(event: MouseEvent, group: PodViewGroup, meta: PodViewMeta) {
  const target = event.currentTarget as SVGSVGElement
  const box = target.getBoundingClientRect()
  const list = shownRows(group)
  const column = Math.min(meta.grid.column_count - 1, Math.max(0, Math.floor((event.clientX - box.left) / box.width * meta.grid.column_count)))
  const row = list[Math.min(list.length - 1, Math.max(0, Math.floor((event.clientY - box.top) / box.height * list.length)))]
  if (!row) return
  const value = row.values[column]
  hover.value = POD_LABELS.readout(rowLabel(row), time(meta, column), value === null ? POD_LABELS.missing : `${number(value)} ${row.unit}`)
}

onUnmounted(() => controller.abort())
</script>

<template>
  <section
    v-if="groups.length"
    id="pod-heatmap"
    class="panel pod-heatmap"
    data-testid="pod-heatmap"
    aria-labelledby="pod-heatmap-title"
    lang="ru"
  >
    <h2 id="pod-heatmap-title">
      {{ POD_LABELS.title }}
    </h2>
    <p
      class="pod-heatmap__note"
      data-testid="pod-note"
    >
      {{ POD_LABELS.note }}
    </p>
    <p class="muted">
      {{ POD_LABELS.lead }}
    </p>
    <template v-if="!started">
      <p class="muted">
        {{ POD_LABELS.hint }}
      </p>
      <button
        type="button"
        data-testid="pod-show"
        @click="start"
      >
        {{ POD_LABELS.show }}
      </button>
    </template>
    <template v-else>
      <p
        v-if="Object.values(found).some((item) => item.state === 'loading')"
        role="status"
        data-testid="pod-loading"
      >
        {{ POD_LABELS.loading }}
      </p>
      <div
        v-if="services.length"
        class="pod-heatmap__controls"
      >
        <label>
          {{ POD_LABELS.service }}
          <select
            :value="service"
            data-testid="pod-service"
            @change="chooseService"
          >
            <option
              v-for="name in services"
              :key="name"
              :value="name"
            >{{ name }}</option>
          </select>
        </label>
        <label>
          {{ POD_LABELS.metric }}
          <select
            v-model="metric"
            data-testid="pod-metric"
          >
            <option
              v-for="name in metrics"
              :key="name"
              :value="name"
            >{{ name }}</option>
          </select>
        </label>
        <div
          role="group"
          :aria-label="POD_LABELS.view"
          class="pod-heatmap__views"
        >
          <button
            type="button"
            data-testid="pod-view-map"
            :aria-pressed="view === 'map'"
            @click="view = 'map'"
          >
            {{ POD_LABELS.map }}
          </button>
          <button
            type="button"
            data-testid="pod-view-table"
            :aria-pressed="view === 'table'"
            @click="view = 'table'"
          >
            {{ POD_LABELS.table }}
          </button>
        </div>
      </div>
      <p
        v-if="rowsLoading"
        role="status"
      >
        {{ POD_LABELS.loadingRows }}
      </p>
      <p
        v-if="rowsError"
        class="notice notice-error"
        role="alert"
        data-testid="pod-rows-error"
      >
        {{ POD_LABELS.failed(rowsError) }}
      </p>
      <p
        v-if="gridsDiffer"
        class="notice notice-info"
        data-testid="pod-grid-mismatch"
      >
        {{ POD_LABELS.gridMismatch }}
      </p>
      <p
        v-if="!sharedScale"
        class="notice notice-info"
        data-testid="pod-unit-mismatch"
      >
        {{ POD_LABELS.unitMismatch(units.join(', ')) }}
      </p>

      <article
        v-for="group in groups"
        :key="key(group)"
        class="pod-heatmap__arm"
        data-testid="pod-arm"
        :data-arm="group.arm ?? ''"
      >
        <h3>{{ POD_LABELS.armTitle(group.arm) }}</h3>
        <p
          v-if="found[key(group)]?.state === 'none'"
          data-testid="pod-none"
        >
          {{ POD_LABELS.none }}
          <span class="muted">{{ POD_LABELS.noneHow }}</span>
        </p>
        <p
          v-else-if="found[key(group)]?.state === 'error'"
          class="notice notice-error"
          role="alert"
          data-testid="pod-error"
        >
          {{ POD_LABELS.failed((found[key(group)] as { message: string }).message) }}
        </p>
        <template v-else-if="found[key(group)]?.state === 'ready'">
          <template
            v-for="item in ready.filter((entry) => entry.group === group)"
            :key="item.analysisId"
          >
            <p data-testid="pod-coverage">
              {{ POD_LABELS.coverage(item.meta.coverage.pods_included, item.meta.coverage.pods_observed_total) }}
              {{ POD_LABELS.selection(item.meta.coverage.selection.kind, item.meta.coverage.selection.metric, item.meta.coverage.selection.limit) }}
            </p>
            <p
              v-if="item.meta.coverage.pods_included < item.meta.coverage.pods_observed_total"
              class="notice notice-info"
              data-testid="pod-coverage-reduced"
            >
              {{ POD_LABELS.coverageReduced }}
            </p>
            <template v-if="metricRows(group).length">
              <template v-if="view === 'map'">
                <p
                  class="muted"
                  data-testid="pod-shown"
                >
                  {{ POD_LABELS.shown(shownRows(group).length, metricRows(group).length) }}
                </p>
                <div class="pod-heatmap__plot">
                  <ul
                    class="pod-heatmap__labels"
                    aria-hidden="true"
                  >
                    <li
                      v-for="row in shownRows(group)"
                      :key="row.id"
                    >
                      {{ rowLabel(row) }}
                    </li>
                  </ul>
                  <svg
                    :viewBox="`0 0 ${item.meta.grid.column_count} ${shownRows(group).length}`"
                    preserveAspectRatio="none"
                    role="img"
                    :aria-label="POD_LABELS.mapLabel(group.arm, service, metric, shownRows(group).length, item.meta.grid.column_count)"
                    :style="{ height: shownRows(group).length * ROW_PX + 'px' }"
                    shape-rendering="crispEdges"
                    data-testid="pod-map"
                    @mousemove="pointer($event, group, item.meta)"
                  >
                    <defs>
                      <pattern
                        :id="`pod-null-${key(group) || 'none'}`"
                        width="0.5"
                        height="0.5"
                        patternUnits="userSpaceOnUse"
                        patternTransform="rotate(45)"
                      >
                        <rect
                          width="0.5"
                          height="0.5"
                          class="pod-cell--null"
                        />
                        <line
                          x1="0"
                          y1="0"
                          x2="0"
                          y2="0.5"
                          class="pod-hatch"
                        />
                      </pattern>
                    </defs>
                    <template
                      v-for="(row, rowIndex) in shownRows(group)"
                      :key="row.id"
                    >
                      <rect
                        v-for="(value, column) in row.values"
                        :key="column"
                        :x="column"
                        :y="rowIndex"
                        width="1"
                        height="1"
                        :class="value === null ? 'pod-cell--hatch' : `pod-cell pod-cell--${level(value, group)}`"
                        :data-null="value === null ? '1' : undefined"
                        :fill="value === null ? `url(#pod-null-${key(group) || 'none'})` : undefined"
                        data-testid="pod-cell"
                      />
                    </template>
                  </svg>
                </div>
                <p class="muted">
                  <span>{{ time(item.meta, 0) }}</span> -
                  <span>{{ time(item.meta, item.meta.grid.column_count) }}</span> UTC
                  <template v-if="rangeOf(group)">
                    · {{ POD_LABELS.scale(number(rangeOf(group)!.min), number(rangeOf(group)!.max), metricRows(group)[0].unit) }}
                  </template>
                </p>
                <p
                  class="muted"
                  data-testid="pod-readout"
                >
                  {{ hover || POD_LABELS.readoutHint }}
                </p>
              </template>
              <PodTable
                v-else
                :rows="orderedRows(group)"
                :arm="group.arm"
              />
            </template>
            <p
              v-else-if="!rowsLoading"
              class="muted"
            >
              {{ POD_LABELS.noMetric }}
            </p>
          </template>
        </template>
      </article>
    </template>
  </section>
</template>

<style scoped>
.pod-heatmap__note {
  font-weight: 650;
  color: var(--info);
}

.pod-heatmap__controls {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  align-items: end;
  margin: 8px 0;
}

.pod-heatmap__views {
  display: inline-flex;
  gap: 4px;
}

.pod-heatmap__views button[aria-pressed="true"] {
  background: var(--brand);
  color: var(--brand-contrast);
}

.pod-heatmap__arm {
  margin-top: 16px;
  min-width: 0;
}

.pod-heatmap__plot {
  display: grid;
  grid-template-columns: minmax(80px, 200px) minmax(0, 1fr);
  gap: 8px;
  align-items: start;
}

.pod-heatmap__labels {
  display: grid;
  margin: 0;
  padding: 0;
  list-style: none;
  font-size: 12px;
}

.pod-heatmap__labels li {
  height: 18px;
  line-height: 18px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

svg[data-testid="pod-map"] {
  width: 100%;
  display: block;
  background: var(--surface-muted);
}

.pod-cell--null {
  fill: var(--surface);
}

.pod-hatch {
  stroke: var(--text-muted);
  stroke-width: 0.12;
}

.pod-cell--0 { fill: color-mix(in srgb, var(--brand) 8%, var(--surface)); }
.pod-cell--1 { fill: color-mix(in srgb, var(--brand) 16%, var(--surface)); }
.pod-cell--2 { fill: color-mix(in srgb, var(--brand) 24%, var(--surface)); }
.pod-cell--3 { fill: color-mix(in srgb, var(--brand) 32%, var(--surface)); }
.pod-cell--4 { fill: color-mix(in srgb, var(--brand) 40%, var(--surface)); }
.pod-cell--5 { fill: color-mix(in srgb, var(--brand) 48%, var(--surface)); }
.pod-cell--6 { fill: color-mix(in srgb, var(--brand) 56%, var(--surface)); }
.pod-cell--7 { fill: color-mix(in srgb, var(--brand) 64%, var(--surface)); }
.pod-cell--8 { fill: color-mix(in srgb, var(--brand) 72%, var(--surface)); }
.pod-cell--9 { fill: color-mix(in srgb, var(--brand) 80%, var(--surface)); }
.pod-cell--10 { fill: color-mix(in srgb, var(--brand) 90%, var(--surface)); }
.pod-cell--11 { fill: var(--brand); }
</style>
