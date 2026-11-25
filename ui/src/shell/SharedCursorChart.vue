<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { getTransactionBuckets } from '../api'
import type { TransactionBucket } from '../types'
import { OVERVIEW_LABELS } from './labels'
import {
  cursorSummary,
  formatNumber,
  loadFrame,
  loadLane,
  nearestIndex,
  type LoadSeries,
  type TrackKey,
  type TransactionCatalogItem,
} from './overview'
import ChartFloatingTooltip, { type FloatingTooltipSection } from './ChartFloatingTooltip.vue'
import TimeChart from './TimeChart.vue'
import {
  SERIES_PALETTE,
  plotFraction,
  valueAtTime,
  type ChartLane,
  type ChartPoint,
  type ChartSeries,
  type ChartStage,
  type ChartThreshold,
} from './timeChart'

const MAX_CHART_TX = 8

const props = withDefaults(defineProps<{
  series: LoadSeries
  stages?: ChartStage[]
  p95Thresholds?: ChartThreshold[]
  transactions?: TransactionCatalogItem[]
  runId?: string
  analysisId?: string
}>(), {
  stages: () => [],
  p95Thresholds: () => [],
  transactions: () => [],
  runId: '',
  analysisId: '',
})

const cursor = ref<number | null>(null)
const pointerPos = ref({ x: 0, y: 0 })
const hoverActive = ref(false)
const pinned = ref(false)

const rpsMode = ref<'total' | 'tx'>('total')
const txStacked = ref(true)
const txShowRest = ref(true)
const latPercentile = ref<'p50' | 'p95' | 'p99'>('p95')
const txDrawerOpen = ref(false)
const txQuery = ref('')
const txFilter = ref<'all' | 'bad' | 'selected'>('all')
const txSort = ref<'rps' | 'p95' | 'err'>('rps')
const selectedTx = ref<string[]>([])
const txBucketsMap = ref<Map<string, TransactionBucket[]>>(new Map())
const txLoading = ref(false)
const txError = ref('')
let txAbort: AbortController | null = null

const tracks: Array<{ key: TrackKey; label: string; color: string; colorVar: string }> = [
  { key: 'rps', label: OVERVIEW_LABELS.trackRps, color: '#60A5FA', colorVar: 'var(--c-0)' },
  { key: 'errors', label: OVERVIEW_LABELS.trackErrors, color: '#FF7B72', colorVar: 'var(--c-err)' },
  { key: 'p95', label: OVERVIEW_LABELS.trackP95, color: '#FBBF24', colorVar: 'var(--c-1)' },
]

watch(() => props.series, () => {
  cursor.value = null
  pinned.value = false
  hoverActive.value = false
})

watch(() => props.transactions, (list) => {
  if (!list.length) {
    selectedTx.value = []
    rpsMode.value = 'total'
    return
  }
  selectedTx.value = list.slice(0, Math.min(5, MAX_CHART_TX)).map((item) => item.label)
}, { immediate: true })

async function fetchSelectedTxBuckets() {
  if (rpsMode.value !== 'tx' || !props.runId || !props.analysisId || !selectedTx.value.length) return
  txAbort?.abort()
  const ctrl = new AbortController()
  txAbort = ctrl
  txLoading.value = true
  txError.value = ''
  try {
    const resp = await getTransactionBuckets(
      props.runId,
      props.analysisId,
      props.series.rollupSeconds,
      selectedTx.value.slice(0, MAX_CHART_TX),
      undefined,
      undefined,
      ctrl.signal,
    )
    if (ctrl.signal.aborted) return
    const next = new Map<string, TransactionBucket[]>()
    for (const s of resp.series) next.set(s.tx, s.buckets)
    txBucketsMap.value = next
  } catch (err) {
    if (!ctrl.signal.aborted) {
      txError.value = err instanceof Error ? err.message : 'Не удалось загрузить ряды транзакций'
    }
  } finally {
    if (!ctrl.signal.aborted) txLoading.value = false
  }
}

watch(() => [rpsMode.value, selectedTx.value.join('\u0000'), props.series.rollupSeconds], () => {
  if (rpsMode.value === 'tx') void fetchSelectedTxBuckets()
})

const canUseTxMode = computed(() => props.transactions.length > 0 && Boolean(props.runId) && Boolean(props.analysisId))

const selectedTxMeta = computed(() =>
  selectedTx.value.slice(0, MAX_CHART_TX).map((label, idx) => ({
    label,
    colorVar: `var(--c-${idx % 8})`,
    item: props.transactions.find((t) => t.label === label) ?? null,
  })),
)

const filteredTxCatalog = computed(() => {
  const q = txQuery.value.trim().toLowerCase()
  return props.transactions
    .filter((t) => {
      if (q && !t.label.toLowerCase().includes(q)) return false
      if (txFilter.value === 'bad') return t.status !== 'PASS'
      if (txFilter.value === 'selected') return selectedTx.value.includes(t.label)
      return true
    })
    .sort((a, b) => (
      txSort.value === 'p95' ? b.p95Ms - a.p95Ms : txSort.value === 'err' ? b.errorRatePct - a.errorRatePct : b.avgRps - a.avgRps
    ))
})

function toggleTx(label: string) {
  const idx = selectedTx.value.indexOf(label)
  if (idx >= 0) {
    if (selectedTx.value.length > 1) selectedTx.value = selectedTx.value.filter((item) => item !== label)
  } else if (selectedTx.value.length < MAX_CHART_TX) {
    selectedTx.value = [...selectedTx.value, label]
  }
  rpsMode.value = 'tx'
}

function removeTx(label: string) {
  if (selectedTx.value.length <= 1) return
  selectedTx.value = selectedTx.value.filter((item) => item !== label)
}

function applyTxPreset(preset: 'bad' | 'topRps' | 'topP95') {
  if (preset === 'bad') {
    const bad = props.transactions.filter((t) => t.status !== 'PASS').slice(0, MAX_CHART_TX).map((t) => t.label)
    selectedTx.value = bad.length ? bad : props.transactions.slice(0, 5).map((t) => t.label)
  } else if (preset === 'topRps') {
    selectedTx.value = [...props.transactions].sort((a, b) => b.avgRps - a.avgRps).slice(0, 5).map((t) => t.label)
  } else {
    selectedTx.value = [...props.transactions].sort((a, b) => b.p95Ms - a.p95Ms).slice(0, 5).map((t) => t.label)
  }
  rpsMode.value = 'tx'
}

const readout = computed(() => (cursor.value === null ? null : cursorSummary(props.series, cursor.value)))
const frame = computed(() => loadFrame(props.series))
const cursorMs = computed(() => (cursor.value === null ? null : props.series.points[cursor.value]?.startMs ?? null))
const units: Record<TrackKey, string> = { rps: ` ${OVERVIEW_LABELS.unitRps}`, errors: '', p95: ` ${OVERVIEW_LABELS.unitMs}` }

function buildTxRpsLane(): ChartLane {
  const rollup = Math.max(1, props.series.rollupSeconds)
  const chosen = selectedTx.value.slice(0, MAX_CHART_TX)
  const seriesList: ChartSeries[] = []
  const byTimeSelected = new Map<number, number>()
  let maxVal = 0

  chosen.forEach((tx, idx) => {
    const buckets = txBucketsMap.value.get(tx) ?? []
    const pts: ChartPoint[] = buckets.map((b) => {
      const rps = b.sample_count / rollup
      byTimeSelected.set(b.bucket_start_ms, (byTimeSelected.get(b.bucket_start_ms) ?? 0) + rps)
      if (!txStacked.value && rps > maxVal) maxVal = rps
      return { x: b.bucket_start_ms, y: rps }
    })
    seriesList.push({
      key: `tx-rps-${idx}`,
      label: tx,
      color: SERIES_PALETTE[idx % SERIES_PALETTE.length],
      segments: pts.length ? [pts] : [],
      stack: txStacked.value ? 'rps' : undefined,
      area: !txStacked.value && chosen.length === 1,
      unit: OVERVIEW_LABELS.unitRps,
    })
  })

  const restCount = Math.max(0, props.transactions.length - chosen.length)
  if (txStacked.value && txShowRest.value && restCount > 0) {
    const restPts: ChartPoint[] = props.series.points.map((pt) => {
      const rest = Math.max(0, pt.rps - (byTimeSelected.get(pt.startMs) ?? 0))
      return { x: pt.startMs, y: rest }
    })
    seriesList.unshift({
      key: 'tx-rps-rest',
      label: `Остальные ${restCount} транз.`,
      color: 'rest',
      segments: restPts.length ? [restPts] : [],
      stack: 'rps',
      unit: OVERVIEW_LABELS.unitRps,
    })
  }
  const hi = txStacked.value ? Math.max(props.series.max.rps, 1) : Math.max(maxVal, 1)
  return {
    key: 'rps',
    color: 'brand',
    segments: [],
    series: seriesList,
    lo: 0,
    hi,
  }
}

function buildTxLatencyLane(): ChartLane {
  const chosen = selectedTx.value.slice(0, MAX_CHART_TX)
  let maxVal = 0
  const pctField = latPercentile.value === 'p50' ? 'p50_latency_ms' : latPercentile.value === 'p99' ? 'p99_latency_ms' : 'p95_latency_ms'
  const seriesList: ChartSeries[] = chosen.map((tx, idx) => {
    const buckets = txBucketsMap.value.get(tx) ?? []
    const pts: ChartPoint[] = buckets.map((b) => {
      const val = b[pctField]
      if (val > maxVal) maxVal = val
      return { x: b.bucket_start_ms, y: val }
    })
    return {
      key: `tx-lat-${idx}`,
      label: `${latPercentile.value} · ${tx}`,
      color: SERIES_PALETTE[idx % SERIES_PALETTE.length],
      segments: pts.length ? [pts] : [],
      area: chosen.length === 1,
      unit: OVERVIEW_LABELS.unitMs,
    }
  })
  return {
    key: 'p95',
    color: 'warn',
    segments: [],
    series: seriesList,
    lo: 0,
    hi: Math.max(maxVal, ...props.p95Thresholds.map((t) => t.value), 1),
    thresholds: latPercentile.value === 'p95' ? props.p95Thresholds : [],
  }
}

const lanes = computed(() => {
  const useTx = rpsMode.value === 'tx' && txBucketsMap.value.size > 0
  return tracks.map((track) => {
    let lane: ChartLane
    if (useTx && track.key === 'rps') lane = buildTxRpsLane()
    else if (useTx && track.key === 'p95') lane = buildTxLatencyLane()
    else {
      const base = loadLane(props.series, track.key, track.key === 'p95' ? props.p95Thresholds : [])
      lane = { ...base, bars: track.key === 'errors', area: track.key !== 'errors' }
    }
    return {
      ...track,
      lane,
      flags: {
        timeLabels: track.key === 'p95',
        stageLabels: track.key === 'rps',
        plotHeight: track.key === 'errors' ? 68 : 132,
      },
    }
  })
})

const tooltipStage = computed(() => {
  if (cursorMs.value === null) return ''
  const st = props.stages.find((s) => cursorMs.value! >= s.fromMs && cursorMs.value! <= s.toMs)
  return st ? st.id : ''
})

const TX_HEX = ['#60A5FA', '#FBBF24', '#2DD4BF', '#C084FC', '#818CF8', '#F472B6', '#34D399', '#A78BFA']

const tooltipSections = computed<FloatingTooltipSection[]>(() => {
  if (cursorMs.value === null || !readout.value) return []
  const sections: FloatingTooltipSection[] = []
  for (const track of lanes.value) {
    if (track.lane.series?.length) {
      const rows = track.lane.series.map((s, idx) => {
        const val = valueAtTime(s.segments, cursorMs.value!, props.series.rollupSeconds * 500)
        return {
          key: `${track.key}:${s.key}`,
          label: s.label ?? s.key,
          value: val === null ? '—' : `${formatNumber(val, track.key === 'rps' ? 1 : 0)}${s.unit ? ` ${s.unit}` : ''}`,
          color: s.color === 'rest' ? '#94A3B8' : TX_HEX[idx % TX_HEX.length],
        }
      })
      sections.push({ title: track.label, rows })
    } else {
      sections.push({
        rows: [{
          key: track.key,
          label: track.label,
          value: `${readout.value[track.key]}${units[track.key]}`,
          color: track.color,
        }],
      })
    }
  }
  return sections
})

function onPointer(event: PointerEvent) {
  if (pinned.value && event.type === 'pointermove') return
  const rect = (event.currentTarget as HTMLElement).getBoundingClientRect()
  if (rect.width <= 0) return
  const index = nearestIndex(props.series, plotFraction(event.clientX, rect))
  if (index !== -1) {
    cursor.value = index
    pointerPos.value = { x: event.clientX, y: event.clientY }
    hoverActive.value = true
  }
}

function onPointerLeave() {
  if (!pinned.value) hoverActive.value = false
}

function onPlotClick(event: MouseEvent) {
  if (cursor.value === null) return
  if (pinned.value) {
    pinned.value = false
    return
  }
  pointerPos.value = { x: event.clientX, y: event.clientY }
  hoverActive.value = true
  pinned.value = true
}

function unpin() {
  pinned.value = false
  hoverActive.value = false
}

function onKeyDown(event: KeyboardEvent) {
  if (event.key === 'Escape' && pinned.value) unpin()
}

function focusCursor(event: FocusEvent) {
  if (cursor.value === null) cursor.value = Number((event.target as HTMLInputElement).value)
}

function inputCursor(event: Event) {
  cursor.value = Number((event.target as HTMLInputElement).value)
}

onMounted(() => window.addEventListener('keydown', onKeyDown))
onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKeyDown)
  txAbort?.abort()
})
</script>

<template>
  <div
    class="shared-chart"
    data-testid="shared-cursor-chart"
    role="group"
    :aria-label="OVERVIEW_LABELS.chartAria"
  >
    <!-- Toolbar (Mockup v4.4) -->
    <div
      v-if="canUseTxMode"
      class="shared-chart__toolbar"
      data-testid="overview-tx-toolbar"
    >
      <div
        class="v4-seg"
        role="group"
        aria-label="Режим отображения интенсивности"
      >
        <button
          type="button"
          :aria-pressed="rpsMode === 'total'"
          data-testid="overview-mode-total"
          @click="rpsMode = 'total'"
        >
          Общий RPS
        </button>
        <button
          type="button"
          :aria-pressed="rpsMode === 'tx'"
          data-testid="overview-mode-tx"
          @click="rpsMode = 'tx'"
        >
          По транзакциям ({{ selectedTx.length }}/{{ transactions.length }})
        </button>
      </div>

      <template v-if="rpsMode === 'tx'">
        <div
          class="v4-seg"
          role="group"
          aria-label="Способ наложения RPS"
        >
          <button
            type="button"
            :aria-pressed="txStacked"
            @click="txStacked = true"
          >
            Стек RPS
          </button>
          <button
            type="button"
            :aria-pressed="!txStacked"
            @click="txStacked = false"
          >
            Линии
          </button>
        </div>
        <div
          class="v4-seg"
          role="group"
          aria-label="Перцентиль отклика"
        >
          <button
            v-for="pct in (['p50', 'p95', 'p99'] as const)"
            :key="pct"
            type="button"
            :aria-pressed="latPercentile === pct"
            @click="latPercentile = pct"
          >
            {{ pct }}
          </button>
        </div>
      </template>
    </div>

    <!-- Active transactions compact bar (Mockup v4.4 .active-tx-bar) -->
    <div
      v-if="canUseTxMode"
      class="active-tx-bar"
    >
      <div class="active-tx-bar__pills">
        <span class="v4-kicker">Выборка ({{ selectedTxMeta.length }} из {{ transactions.length }}):</span>
        <span
          v-for="pill in selectedTxMeta"
          :key="pill.label"
          class="sel-pill"
        >
          <i
            class="sel-pill__sw"
            :style="{ backgroundColor: pill.colorVar }"
          />
          <span class="v4-num">{{ pill.label }}</span>
          <b
            v-if="pill.item && pill.item.status === 'FAIL'"
            class="sel-pill__fail"
          >FAIL</b>
          <button
            v-if="selectedTxMeta.length > 1"
            type="button"
            title="Убрать с графика"
            aria-label="Убрать транзакцию"
            @click="removeTx(pill.label)"
          >×</button>
        </span>
        <label
          v-if="rpsMode === 'tx' && txStacked && transactions.length > selectedTx.length"
          class="v4-check"
        >
          <input
            v-model="txShowRest"
            type="checkbox"
          >
          + остальные {{ transactions.length - selectedTx.length }} фоном
        </label>
      </div>
      <button
        type="button"
        class="v4-btn v4-btn--xs"
        :class="{ 'v4-btn--primary': txDrawerOpen }"
        data-testid="overview-tx-drawer-toggle"
        @click="txDrawerOpen = !txDrawerOpen"
      >
        Выбрать из {{ transactions.length }} транзакций {{ txDrawerOpen ? '▴' : '▾' }}
      </button>
    </div>

    <!-- Scalable Transaction Table Drawer (Mockup v4.4 .tx-drawer) -->
    <div
      v-if="canUseTxMode && txDrawerOpen"
      class="tx-drawer"
      data-testid="overview-tx-drawer"
      role="region"
      aria-label="Рубрикатор транзакций"
    >
      <div class="tx-drawer__bar">
        <div class="tx-drawer__group">
          <input
            v-model="txQuery"
            type="search"
            class="v4-inp tx-drawer__search"
            data-testid="overview-tx-search"
            placeholder="Поиск среди транзакций…"
            aria-label="Поиск транзакций"
          >
          <div class="v4-seg">
            <button
              type="button"
              :aria-pressed="txFilter === 'all'"
              @click="txFilter = 'all'"
            >
              Все ({{ transactions.length }})
            </button>
            <button
              type="button"
              :aria-pressed="txFilter === 'bad'"
              @click="txFilter = 'bad'"
            >
              Отклонения ({{ transactions.filter((t) => t.status !== 'PASS').length }})
            </button>
            <button
              type="button"
              :aria-pressed="txFilter === 'selected'"
              @click="txFilter = 'selected'"
            >
              Выбранные ({{ selectedTx.length }})
            </button>
          </div>
          <div class="v4-seg">
            <button
              type="button"
              :aria-pressed="txSort === 'rps'"
              @click="txSort = 'rps'"
            >
              RPS ↓
            </button>
            <button
              type="button"
              :aria-pressed="txSort === 'p95'"
              @click="txSort = 'p95'"
            >
              p95 ↓
            </button>
            <button
              type="button"
              :aria-pressed="txSort === 'err'"
              @click="txSort = 'err'"
            >
              Ошибки ↓
            </button>
          </div>
        </div>
        <div class="tx-drawer__group">
          <span class="v4-sub">До {{ MAX_CHART_TX }} кривых</span>
          <button
            type="button"
            class="v4-btn v4-btn--xs"
            @click="applyTxPreset('bad')"
          >
            Проблемные
          </button>
          <button
            type="button"
            class="v4-btn v4-btn--xs"
            @click="applyTxPreset('topRps')"
          >
            Топ-5 RPS
          </button>
          <button
            type="button"
            class="v4-btn v4-btn--xs"
            @click="applyTxPreset('topP95')"
          >
            Топ-5 p95
          </button>
          <button
            type="button"
            class="v4-btn v4-btn--xs v4-btn--primary"
            @click="txDrawerOpen = false"
          >
            Готово
          </button>
        </div>
      </div>

      <div class="v4-table-wrap tx-drawer__table">
        <table>
          <thead>
            <tr>
              <th scope="col">
                Выбор
              </th>
              <th scope="col">
                Транзакция ({{ filteredTxCatalog.length }} из {{ transactions.length }})
              </th>
              <th scope="col">
                RPS
              </th>
              <th scope="col">
                p95, мс
              </th>
              <th scope="col">
                Ошибки, %
              </th>
              <th scope="col">
                Статус
              </th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="tx in filteredTxCatalog"
              :key="tx.label"
              :class="{ 'is-selected': selectedTx.includes(tx.label) }"
            >
              <td>
                <label class="v4-check">
                  <input
                    type="checkbox"
                    :checked="selectedTx.includes(tx.label)"
                    :disabled="!selectedTx.includes(tx.label) && selectedTx.length >= MAX_CHART_TX"
                    :aria-label="`Выбрать транзакцию ${tx.label}`"
                    @change="toggleTx(tx.label)"
                  >
                </label>
              </td>
              <td class="v4-num">
                <strong>{{ tx.label }}</strong>
              </td>
              <td class="v4-num">
                {{ formatNumber(tx.avgRps, 1) }}
              </td>
              <td class="v4-num">
                {{ formatNumber(tx.p95Ms, 0) }}
              </td>
              <td class="v4-num">
                {{ formatNumber(tx.errorRatePct, 2) }}%
              </td>
              <td>
                <span
                  class="v4-pill"
                  :class="tx.status === 'FAIL' ? 'v4-pill--crit' : tx.status === 'WARN' ? 'v4-pill--warn' : 'v4-pill--good'"
                >{{ tx.status }}</span>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <p
      v-if="txLoading"
      class="v4-sub"
      role="status"
    >
      Загрузка рядов транзакций…
    </p>
    <p
      v-if="txError"
      class="notice notice-fail"
      role="alert"
    >
      {{ txError }}
    </p>

    <!-- Synchronized Chart Panes (Mockup v4.4) -->
    <div
      class="shared-chart__plot"
      data-testid="chart-plot"
      @pointermove="onPointer"
      @pointerdown="onPointer"
      @pointerleave="onPointerLeave"
      @click="onPlotClick"
    >
      <div
        v-for="track in lanes"
        :key="track.key"
        class="shared-chart__track"
        :class="`shared-chart__track--${track.key}`"
        :data-testid="`track-${track.key}`"
      >
        <p class="shared-chart__head">
          <span class="shared-chart__head-left">
            <i
              class="shared-chart__swatch"
              :style="{ backgroundColor: track.colorVar }"
            />
            <span>{{ track.label }}</span>
          </span>
          <span class="shared-chart__head-right">
            <span class="v4-sub v4-num">{{ OVERVIEW_LABELS.trackMax(formatNumber(series.max[track.key])) }}</span>
            <strong
              class="v4-num"
              :data-testid="`track-value-${track.key}`"
              :data-value="readout ? readout.raw[track.key] : ''"
            >{{ readout ? readout[track.key] : '-' }}<template v-if="track.key === 'p95' && readout"> {{ OVERVIEW_LABELS.unitMs }}</template></strong>
          </span>
        </p>
        <TimeChart
          :lane="track.lane"
          :frame="frame"
          :stages="stages"
          :flags="track.flags"
          :cursor-ms="cursorMs"
          :cursor-value="readout ? `${readout[track.key]}${units[track.key]}` : ''"
          :cursor-time="track.key === 'p95' ? readout?.time ?? null : null"
          :hide-inline-value="hoverActive || pinned"
        />
      </div>
    </div>

    <ChartFloatingTooltip
      :visible="(hoverActive || pinned) && cursorMs !== null"
      :client-x="pointerPos.x"
      :client-y="pointerPos.y"
      :title="readout ? `+${readout.time}` : ''"
      :meta="tooltipStage"
      :sections="tooltipSections"
      :pinned="pinned"
      @unpin="unpin"
    />

    <p
      class="shared-chart__axis-title v4-sub"
      data-testid="chart-axis-title"
    >
      {{ OVERVIEW_LABELS.axisLabel }}
    </p>
    <div class="shared-chart__footer">
      <div class="shared-chart__meta-row">
        <p
          class="v4-sub v4-num"
          data-testid="cursor-time"
        >
          {{ readout ? OVERVIEW_LABELS.cursorTime(readout.time) : OVERVIEW_LABELS.cursorNone }}
        </p>
        <div class="shared-chart__notes v4-sub">
          <span data-testid="load-summary">{{ OVERVIEW_LABELS.loadSummary(series.points.length, series.rollupSeconds) }}</span>
          <template v-if="series.rollupSeconds > 1">
            <span aria-hidden="true">·</span>
            <span data-testid="load-step">{{ OVERVIEW_LABELS.loadStep(series.rollupSeconds) }}</span>
          </template>
          <template v-if="series.missingIntervals > 0">
            <span aria-hidden="true">·</span>
            <span data-testid="load-gaps">{{ OVERVIEW_LABELS.loadGaps(series.missingIntervals) }}</span>
          </template>
        </div>
      </div>
      <input
        type="range"
        data-testid="chart-cursor"
        class="shared-chart__slider"
        :aria-label="OVERVIEW_LABELS.cursorLabel"
        aria-describedby="chart-cursor-hint"
        min="0"
        :max="series.points.length - 1"
        step="1"
        :value="cursor ?? 0"
        :aria-valuetext="readout ? readout.text : OVERVIEW_LABELS.cursorNone"
        @focus="focusCursor"
        @input="inputCursor"
      >
      <p
        id="chart-cursor-hint"
        class="v4-sub shared-chart__hint"
      >
        {{ OVERVIEW_LABELS.cursorHint }}
      </p>
    </div>
  </div>
</template>

<style scoped>
.shared-chart {
  display: flex;
  flex-direction: column;
  gap: 10px;
  min-width: 0;
  max-width: 100%;
}

.shared-chart > * {
  min-width: 0;
  max-width: 100%;
}

.shared-chart__toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
}

.active-tx-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  flex-wrap: wrap;
  padding: 7px 10px;
  border-radius: 7px;
  background: var(--surface-inset);
  border: 1px solid var(--border);
}

.active-tx-bar__pills {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
  min-width: 0;
}

.sel-pill {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  max-width: 100%;
  overflow: hidden;
  height: 24px;
  padding: 0 7px 0 8px;
  border-radius: 5px;
  background: var(--surface);
  border: 1px solid var(--border);
  font-size: 12px;
}

.sel-pill > span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.sel-pill__sw {
  width: 9px;
  height: 9px;
  border-radius: 2px;
  flex: none;
}

.sel-pill__fail {
  color: var(--fail);
  font-size: 10px;
}

.sel-pill button {
  border: 0;
  min-height: 18px;
  min-width: 18px;
  padding: 0 2px;
  background: transparent;
  color: var(--text-muted);
  font-size: 13px;
  line-height: 1;
}

.sel-pill button:hover {
  color: var(--fail);
}

.tx-drawer {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 12px;
  border: 1px solid var(--brand);
  border-radius: 8px;
  background: var(--surface);
  box-shadow: 0 6px 20px rgba(0, 0, 0, 0.14);
}

.tx-drawer__bar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.tx-drawer__group {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
}

.tx-drawer__search {
  width: min(240px, 100%);
}

.tx-drawer__table {
  max-height: 240px;
  overflow-y: auto;
}

.tx-drawer__table tr.is-selected td {
  background: color-mix(in srgb, var(--info-bg) 55%, var(--surface));
}

.shared-chart__plot {
  display: grid;
  gap: 8px;
  touch-action: pan-y;
  cursor: crosshair;
}

.shared-chart__track {
  border: 1px solid var(--border);
  border-radius: 8px;
  background: var(--surface-inset);
  padding: 7px 10px 4px;
  min-width: 0;
}

.shared-chart__head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 4px 12px;
  margin: 0 0 2px;
  font-size: 12.5px;
}

.shared-chart__head-left,
.shared-chart__head-right {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}

.shared-chart__swatch {
  width: 9px;
  height: 9px;
  border-radius: 2px;
  flex: none;
}

.shared-chart__axis-title {
  margin: 0 16px 0 56px;
  text-align: center;
  font-size: 11px;
}

.shared-chart__footer {
  display: grid;
  gap: 4px;
}

.shared-chart__meta-row {
  display: flex;
  flex-wrap: wrap;
  justify-content: space-between;
  align-items: center;
  gap: 4px 12px;
}

.shared-chart__meta-row p {
  margin: 0;
  font-size: 11.5px;
}

.shared-chart__notes {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 6px;
  font-size: 11.5px;
}

.shared-chart__slider {
  width: 100%;
  height: 44px;
  min-height: 44px;
  padding: 10px 0;
  margin: 0;
  border: 0;
  background: transparent;
  accent-color: var(--brand);
}

.shared-chart__hint {
  margin: 0;
  font-size: 11px;
}
</style>
