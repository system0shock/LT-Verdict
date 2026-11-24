import type {
  AnalysisResult, Bucket, BucketPage, PolicyCheckEvidence, ResourceSeriesCatalog, ResourceSeriesEntry,
  ResourceSeriesValues, StageBindingEvidence, WindowPolicySummaryEvidence,
} from '../types'
import { DEEP_LABELS, OVERVIEW_LABELS } from './labels'
import { formatNumber, loadSeries } from './overview'
import { stageBands, type ChartLane, type ChartStage, type ChartToken } from './timeChart'

export interface SnapshotGrid { startMs: number; stepMs: number; pointCount: number }
export interface DeepPoint { startMs: number; value: number | null; observed: number | null }
export interface DeepThreshold { ruleId: string; operator: 'gt' | 'lt'; value: number; violated: boolean }
export interface DeepTrack {
  key: string
  label: string
  unit: string
  kind: 'load' | 'resource'
  reducer: 'mean' | 'max' | 'min' | null
  integer?: boolean
  stepMs: number
  endMs: number
  sourceCellsPerCell: number
  lastCellSourceCells: number
  points: DeepPoint[]
  segments: number[][]
  min: number
  max: number
  thresholds: DeepThreshold[]
}
export interface TimeAxis { fromMs: number; toMs: number }
export interface StageButton { id: string; role: string; fromMs: number; toMs: number; fromMin: number; toMin: number; steady: boolean }
export interface WindowMetricStats {
  count: number
  min: number | null
  avg: number | null
  p50: number | null
  p95: number | null
  max: number | null
  timeAboveThresholdMs: number | null
  hasThresholds: boolean
}
export type MetricCatalogGroupKey = 'delays' | 'load' | 'errors' | 'platform'
export interface LoadCatalogItem { key: string; label: string; unit: string; group: 'delays' | 'load' | 'errors' }
export interface GroupedMetricCatalog {
  services: string[]
  groups: Array<{ key: MetricCatalogGroupKey; label: string; loadItems: LoadCatalogItem[]; platformItems: ResourceSeriesEntry[] }>
  platformByRole: { system: ResourceSeriesEntry[]; generator: ResourceSeriesEntry[] }
}

export const MAX_SELECTED_SERIES = 6
export const TARGET_CELLS = 1500
export const LOAD_BUCKET_BUDGET = 1500
export const URL_BUDGET_CHARS = 3500
export const MAX_IDS_PER_REQUEST = 32
export const MAX_LOAD_PAGES = 12

export function gridEndMs(grid: SnapshotGrid): number {
  return grid.startMs + grid.stepMs * grid.pointCount
}

export function chooseResourceStep(grid: SnapshotGrid, fromMs: number, toMs: number, target = TARGET_CELLS): number {
  const span = Math.max(toMs - fromMs, grid.stepMs)
  return Math.max(1, Math.ceil(span / (grid.stepMs * target))) * grid.stepMs
}

export function snapPeriod(grid: SnapshotGrid, stepMs: number, fromMs: number, toMs: number): TimeAxis {
  const cells = Math.ceil(grid.pointCount * grid.stepMs / stepMs)
  const first = Math.min(cells - 1, Math.max(0, Math.floor((fromMs - grid.startMs) / stepMs)))
  const last = Math.min(cells, Math.max(first + 1, Math.ceil((toMs - grid.startMs) / stepMs)))
  return { fromMs: grid.startMs + first * stepMs, toMs: last >= cells ? gridEndMs(grid) : grid.startMs + last * stepMs }
}

export function planResourcePeriod(grid: SnapshotGrid, fromMs: number, toMs: number, target = TARGET_CELLS): { stepMs: number; axis: TimeAxis; cells: number } {
  let stepMs = chooseResourceStep(grid, fromMs, toMs, target)
  let axis = snapPeriod(grid, stepMs, fromMs, toMs)
  let cells = Math.ceil((axis.toMs - axis.fromMs) / stepMs)
  while (cells > target) {
    stepMs += grid.stepMs
    axis = snapPeriod(grid, stepMs, fromMs, toMs)
    cells = Math.ceil((axis.toMs - axis.fromMs) / stepMs)
  }
  return { stepMs, axis, cells }
}

export function chooseLoadRollup(spanMs: number, budget = LOAD_BUCKET_BUDGET): 1 | 10 | 30 | 60 {
  for (const rollup of [1, 10, 30] as const) if (spanMs / (rollup * 1000) <= budget) return rollup
  return 60
}

export interface RunLoad { buckets: Bucket[]; rollupSeconds: 1 | 10 | 30 | 60; truncated: boolean }

// Весь прогон для «Обзора»: проба на 60 с узнаёт длительность (до 500 интервалов она умещается в одну страницу),
// затем шаг выбирается так, чтобы интервалов было не больше LOAD_BUCKET_BUDGET, и страницы читаются по next_from_ms.
// Если прогон длиннее пробы, остаётся 60 с, и проба — первая страница. Предел MAX_LOAD_PAGES страниц.
export async function fetchRunLoad(fetchPage: (rollupSeconds: 1 | 10 | 30 | 60, fromMs: number | undefined) => Promise<BucketPage>): Promise<RunLoad> {
  const probe = await fetchPage(60, undefined)
  let rollupSeconds: 1 | 10 | 30 | 60 = 60
  let page: BucketPage | null = probe
  if (probe.next_from_ms === null) {
    if (!probe.buckets.length) return { buckets: [], rollupSeconds, truncated: false }
    rollupSeconds = chooseLoadRollup(probe.buckets[probe.buckets.length - 1].bucket_start_ms + 60_000)
    if (rollupSeconds !== 60) page = null
  }
  const buckets: Bucket[] = []
  let from: number | undefined
  for (let count = 0; count < MAX_LOAD_PAGES; count += 1) {
    page ??= await fetchPage(rollupSeconds, from)
    buckets.push(...page.buckets)
    const next: number | null = page.next_from_ms
    if (next === null) return { buckets, rollupSeconds, truncated: false }
    const last = page.buckets.length ? page.buckets[page.buckets.length - 1].bucket_start_ms : -1
    if (next <= last || (from !== undefined && next <= from)) return { buckets, rollupSeconds, truncated: true }
    from = next
    page = null
  }
  return { buckets, rollupSeconds, truncated: true }
}

export function loadBucketRange(originMs: number, axis: TimeAxis, rollupSeconds: number): { fromMs: number; toMs: number } | null {
  const step = rollupSeconds * 1000
  const fromMs = Math.max(0, Math.floor((axis.fromMs - originMs) / step) * step)
  const toMs = axis.toMs - originMs
  return toMs <= fromMs ? null : { fromMs, toMs }
}

export function batchSeriesIds(ids: string[], baseLength: number, maxChars = URL_BUDGET_CHARS, maxCount = MAX_IDS_PER_REQUEST): string[][] {
  const batches: string[][] = []
  let batch: string[] = []
  let length = baseLength
  for (const id of ids) {
    const cost = 1 + new URLSearchParams({ series_id: id }).toString().length
    if (batch.length && (batch.length >= maxCount || length + cost > maxChars)) {
      batches.push(batch)
      batch = []
      length = baseLength
    }
    batch.push(id)
    length += cost
  }
  if (batch.length) batches.push(batch)
  return batches
}

export function runStartMs(result: AnalysisResult): number | null {
  const binding = result.evidence.find((item) => item.type === 'resource_binding')
  const value = binding?.run_from_epoch_ms
  return typeof value === 'number' && Number.isFinite(value) ? value : null
}

export function thresholdsFor(result: AnalysisResult, seriesId: string): DeepThreshold[] {
  const thresholds: DeepThreshold[] = []
  const seen = new Set<string>()
  for (const item of result.evidence) {
    if (item.type !== 'resource_policy_check' || item.series_id !== seriesId || seen.has(item.rule_id)) continue
    const value = Number(item.threshold)
    if (!Number.isFinite(value)) continue
    seen.add(item.rule_id)
    const violated = result.evidence.some((candidate) => candidate.type === 'resource_policy_check' && candidate.series_id === seriesId && candidate.rule_id === item.rule_id && candidate.status === 'FAIL')
    thresholds.push({ ruleId: item.rule_id, operator: item.operator, value, violated })
  }
  return thresholds
}

export function defaultSelection(catalog: ResourceSeriesEntry[], result: AnalysisResult): string[] {
  const available = new Set(catalog.map((item) => item.id))
  const selected: string[] = []
  for (const item of result.evidence) {
    if (item.type === 'resource_policy_check' && item.status !== 'PASS' && available.has(item.series_id) && !selected.includes(item.series_id)) selected.push(item.series_id)
  }
  for (const item of catalog) if (item.role === 'system' && !selected.includes(item.id)) selected.push(item.id)
  for (const item of catalog) if (!selected.includes(item.id)) selected.push(item.id)
  return selected.slice(0, MAX_SELECTED_SERIES)
}

function pointStats(points: DeepPoint[], stepMs: number): Pick<DeepTrack, 'segments' | 'min' | 'max'> {
  const segments: number[][] = []
  let min = Infinity
  let max = -Infinity
  for (let index = 0; index < points.length; index += 1) {
    const point = points[index]
    if (point.value === null) continue
    if (index === 0 || points[index - 1].value === null || point.startMs !== points[index - 1].startMs + stepMs) segments.push([])
    segments[segments.length - 1].push(index)
    min = Math.min(min, point.value)
    max = Math.max(max, point.value)
  }
  return { segments, min: min === Infinity ? 0 : min, max: max === -Infinity ? 0 : max }
}

export function resourceTrack(entry: ResourceSeriesEntry, series: ResourceSeriesValues['series'][number], grid: ResourceSeriesValues['grid'], thresholds: DeepThreshold[]): DeepTrack {
  const points = series.values.map((value, index) => ({
    startMs: grid.first_cell_start_ms + index * grid.step_ms,
    value,
    observed: series.observed?.[index] ?? (value === null ? 0 : 1),
  }))
  return {
    key: entry.id, label: entry.id, unit: entry.unit, kind: 'resource', reducer: series.reducer,
    stepMs: grid.step_ms,
    endMs: points.length ? points[points.length - 1].startMs + grid.last_cell_source_cells * grid.source_step_ms : grid.first_cell_start_ms,
    sourceCellsPerCell: grid.source_cells_per_cell,
    lastCellSourceCells: grid.last_cell_source_cells,
    points, ...pointStats(points, grid.step_ms), thresholds,
  }
}

export function mergePages(left: DeepTrack, right: DeepTrack): DeepTrack {
  const points = [...left.points, ...right.points]
  return { ...left, points, endMs: right.endMs, lastCellSourceCells: right.lastCellSourceCells, ...pointStats(points, left.stepMs) }
}

export function loadTracks(buckets: Bucket[], rollupSeconds: number, originMs: number): DeepTrack[] {
  const series = loadSeries(buckets, rollupSeconds)
  const stepMs = rollupSeconds * 1000
  return ([
    ['load-rps', OVERVIEW_LABELS.trackRps, OVERVIEW_LABELS.unitRps, 'rps'],
    ['load-p95', OVERVIEW_LABELS.trackP95, OVERVIEW_LABELS.unitMs, 'p95'],
  ] as const).map(([key, label, unit, field]) => {
    const points = series.points.map((point) => ({ startMs: point.startMs + originMs, value: point[field], observed: null }))
    return {
      key, label, unit, kind: 'load', reducer: null, stepMs,
      endMs: points.length ? points[points.length - 1].startMs + stepMs : originMs,
      sourceCellsPerCell: 1, lastCellSourceCells: 1,
      points, ...pointStats(points, stepMs), thresholds: [],
    }
  })
}

export const LOAD_CATALOG_ITEMS: readonly LoadCatalogItem[] = [
  { key: 'load-p50', label: DEEP_LABELS.metricP50, unit: OVERVIEW_LABELS.unitMs, group: 'delays' },
  { key: 'load-p95', label: OVERVIEW_LABELS.trackP95, unit: OVERVIEW_LABELS.unitMs, group: 'delays' },
  { key: 'load-p99', label: DEEP_LABELS.metricP99, unit: OVERVIEW_LABELS.unitMs, group: 'delays' },
  { key: 'load-max', label: DEEP_LABELS.metricMax, unit: OVERVIEW_LABELS.unitMs, group: 'delays' },
  { key: 'load-rps', label: OVERVIEW_LABELS.trackRps, unit: OVERVIEW_LABELS.unitRps, group: 'load' },
  { key: 'load-errors', label: OVERVIEW_LABELS.trackErrors, unit: DEEP_LABELS.unitErrors, group: 'errors' },
  { key: 'load-error-rate', label: DEEP_LABELS.metricErrorRate, unit: DEEP_LABELS.unitPercent, group: 'errors' },
]

const POLICY_METRIC_TRACK: Record<string, string> = {
  response_time_p95_ms: 'load-p95',
  response_time_p99_ms: 'load-p99',
  throughput_rps: 'load-rps',
  error_rate_ratio: 'load-error-rate',
}

function loadThresholdsFor(result: AnalysisResult | undefined, trackKey: string): DeepThreshold[] {
  if (!result) return []
  const checks = result.evidence.filter((item): item is PolicyCheckEvidence =>
    item.type === 'policy_check' && item.scope?.kind === 'overall' && POLICY_METRIC_TRACK[item.metric] === trackKey)
  const thresholds: DeepThreshold[] = []
  const seen = new Set<string>()
  for (const item of checks) {
    if (seen.has(item.rule_id)) continue
    const raw = Number(item.threshold)
    if (!Number.isFinite(raw)) continue
    seen.add(item.rule_id)
    const value = item.metric === 'error_rate_ratio' ? raw * 100 : raw
    const operator: 'gt' | 'lt' = item.operator === 'gte' ? 'lt' : 'gt'
    const violated = checks.some((candidate) => candidate.rule_id === item.rule_id && candidate.status === 'FAIL')
    thresholds.push({ ruleId: item.rule_id, operator, value, violated })
  }
  return thresholds
}

export function allLoadTracks(buckets: Bucket[], rollupSeconds: number, originMs: number, result?: AnalysisResult): DeepTrack[] {
  const sorted = [...buckets].sort((left, right) => left.bucket_start_ms - right.bucket_start_ms)
  const stepMs = rollupSeconds * 1000
  const endMs = sorted.length ? sorted[sorted.length - 1].bucket_start_ms + originMs + stepMs : originMs
  const extract = (key: string, bucket: Bucket): number | null => {
    switch (key) {
      case 'load-p50': return typeof bucket.p50_latency_ms === 'number' ? bucket.p50_latency_ms : null
      case 'load-p95': return bucket.p95_latency_ms
      case 'load-p99': return typeof bucket.p99_latency_ms === 'number' ? bucket.p99_latency_ms : null
      case 'load-max': return bucket.max_latency_ms
      case 'load-rps': return bucket.sample_count / rollupSeconds
      case 'load-errors': return bucket.error_count
      case 'load-error-rate': return bucket.sample_count > 0 ? (bucket.error_count / bucket.sample_count) * 100 : null
      default: return null
    }
  }
  return LOAD_CATALOG_ITEMS.map((item) => {
    const points = sorted.map((bucket) => ({ startMs: bucket.bucket_start_ms + originMs, value: extract(item.key, bucket), observed: null }))
    return {
      key: item.key,
      label: item.label,
      unit: item.unit,
      kind: 'load',
      reducer: null,
      ...(item.key === 'load-errors' ? { integer: true } : {}),
      stepMs,
      endMs,
      sourceCellsPerCell: 1,
      lastCellSourceCells: 1,
      points,
      ...pointStats(points, stepMs),
      thresholds: loadThresholdsFor(result, item.key),
    }
  })
}

export function stageButtons(
  result: AnalysisResult,
  catalogWindows: ResourceSeriesCatalog['windows'] = [],
  originMs = 0,
): StageButton[] {
  const binding = result.evidence.find((item): item is StageBindingEvidence => item.type === 'stage_binding')
  if (binding?.stages?.length) {
    return binding.stages
      .filter((stage) => stage.to_offset_ms > stage.from_offset_ms)
      .map((stage) => ({
        id: stage.id,
        role: stage.role,
        fromMs: originMs + stage.from_offset_ms,
        toMs: originMs + stage.to_offset_ms,
        fromMin: stage.from_offset_ms / 60_000,
        toMin: stage.to_offset_ms / 60_000,
        steady: stage.role === 'steady',
      }))
  }
  if (catalogWindows.length) {
    return catalogWindows
      .filter((win) => win.to_epoch_ms > win.from_epoch_ms)
      .map((win) => ({
        id: win.id,
        role: 'steady',
        fromMs: win.from_epoch_ms,
        toMs: win.to_epoch_ms,
        fromMin: (win.from_epoch_ms - originMs) / 60_000,
        toMin: (win.to_epoch_ms - originMs) / 60_000,
        steady: true,
      }))
  }
  const seen = new Set<string>()
  const summaryWindows: StageButton[] = []
  for (const item of result.evidence) {
    if (item.type !== 'window_policy_summary') continue
    const win = item as WindowPolicySummaryEvidence
    if (seen.has(win.window_id) || !(win.to_epoch_ms > win.from_epoch_ms)) continue
    seen.add(win.window_id)
    summaryWindows.push({
      id: win.window_id,
      role: 'steady',
      fromMs: win.from_epoch_ms,
      toMs: win.to_epoch_ms,
      fromMin: (win.from_epoch_ms - originMs) / 60_000,
      toMin: (win.to_epoch_ms - originMs) / 60_000,
      steady: true,
    })
  }
  return summaryWindows
}

export function deepStages(
  result: AnalysisResult,
  catalogWindows: ResourceSeriesCatalog['windows'] = [],
  originMs = 0,
): ChartStage[] {
  const declared = stageBands(result, originMs)
  if (declared.length) return declared
  return stageButtons(result, catalogWindows, originMs).map((win) => ({
    id: win.id,
    fromMs: win.fromMs,
    toMs: win.toMs,
    steady: win.steady,
  }))
}

export function windowMetricStats(track: DeepTrack, axis: TimeAxis): WindowMetricStats {
  const values: number[] = []
  let violatedMs = 0
  const hasThresholds = track.thresholds.length > 0
  for (let index = 0; index < track.points.length; index += 1) {
    const point = track.points[index]
    const cellEnd = index === track.points.length - 1 ? track.endMs : point.startMs + track.stepMs
    const from = Math.max(point.startMs, axis.fromMs)
    const to = Math.min(cellEnd, axis.toMs)
    if (to <= from || point.value === null || !Number.isFinite(point.value)) continue
    values.push(point.value)
    if (hasThresholds) {
      const violated = track.thresholds.some((t) => (t.operator === 'gt' ? point.value! > t.value : point.value! < t.value))
      if (violated) violatedMs += to - from
    }
  }
  if (!values.length) {
    return { count: 0, min: null, avg: null, p50: null, p95: null, max: null, timeAboveThresholdMs: null, hasThresholds }
  }
  const sorted = [...values].sort((left, right) => left - right)
  const rank = (pct: number) => sorted[Math.max(0, Math.ceil(pct * sorted.length) - 1)]
  const sum = values.reduce((acc, value) => acc + value, 0)
  return {
    count: values.length,
    min: sorted[0],
    avg: sum / values.length,
    p50: rank(0.5),
    p95: rank(0.95),
    max: sorted[sorted.length - 1],
    timeAboveThresholdMs: hasThresholds ? violatedMs : null,
    hasThresholds,
  }
}

export function groupMetricCatalog(
  catalog: ResourceSeriesEntry[],
  textFilter = '',
  serviceFilter = '',
): GroupedMetricCatalog {
  const needle = textFilter.trim().toLocaleLowerCase()
  const services = [...new Set(catalog.map((entry) => entry.entity).filter(Boolean))].sort((a, b) => (a < b ? -1 : a > b ? 1 : 0))
  const loadByGroup = (group: 'delays' | 'load' | 'errors') =>
    LOAD_CATALOG_ITEMS.filter((item) => item.group === group && (!needle || `${item.key} ${item.label}`.toLocaleLowerCase().includes(needle)))
  const platformItems = catalog.filter((entry) =>
    (!serviceFilter || entry.entity === serviceFilter)
    && (!needle || `${entry.id} ${entry.metric} ${entry.entity}`.toLocaleLowerCase().includes(needle)),
  )
  return {
    services,
    groups: [
      { key: 'delays', label: DEEP_LABELS.groupLabels.delays, loadItems: loadByGroup('delays'), platformItems: [] },
      { key: 'load', label: DEEP_LABELS.groupLabels.load, loadItems: loadByGroup('load'), platformItems: [] },
      { key: 'errors', label: DEEP_LABELS.groupLabels.errors, loadItems: loadByGroup('errors'), platformItems: [] },
      { key: 'platform', label: DEEP_LABELS.groupLabels.platform, loadItems: [], platformItems },
    ],
    platformByRole: {
      system: platformItems.filter((entry) => entry.role === 'system'),
      generator: platformItems.filter((entry) => entry.role === 'generator'),
    },
  }
}

export function cellAt(track: DeepTrack, timeMs: number): DeepPoint | null {
  for (let index = 0; index < track.points.length; index += 1) {
    const point = track.points[index]
    const end = index === track.points.length - 1 ? track.endMs : point.startMs + track.stepMs
    if (timeMs >= point.startMs && timeMs < end) return point
  }
  return null
}

export function cursorReadouts(tracks: DeepTrack[], timeMs: number): Array<{ key: string; text: string; raw: string; gap: boolean; partial: boolean; observed: number | null; total: number | null }> {
  return tracks.map((track) => {
    const point = cellAt(track, timeMs)
    const index = point ? track.points.indexOf(point) : -1
    const total = point?.observed === null || !point ? null : index === track.points.length - 1 ? track.lastCellSourceCells : track.sourceCellsPerCell
    const value = point?.value ?? null
    return {
      key: track.key, text: value === null ? '' : formatNumber(value, 2), raw: value === null ? '' : String(value),
      gap: value === null, partial: value !== null && point?.observed !== null && total !== null && point!.observed! < total,
      observed: point?.observed ?? null, total,
    }
  })
}

export function cursorStops(tracks: DeepTrack[]): number[] {
  const withPoints = tracks.filter((track) => track.points.length)
  const finest = Math.min(...withPoints.map((track) => track.stepMs))
  const starts = new Set<number>()
  for (const track of withPoints) if (track.stepMs === finest) for (const point of track.points) starts.add(point.startMs)
  return [...starts].sort((left, right) => left - right)
}

export function nearestTime(axis: TimeAxis, fraction: number): number {
  if (axis.toMs <= axis.fromMs) return axis.fromMs
  return Math.min(axis.toMs - 1, axis.fromMs + Math.round(Math.max(0, Math.min(1, fraction)) * (axis.toMs - axis.fromMs)))
}

export function trackScale(track: DeepTrack): { lo: number; hi: number } {
  const bounds = track.thresholds.map((item) => item.value)
  const lo = Math.min(0, track.min, ...bounds)
  const hi = Math.max(track.max, ...bounds)
  return { lo, hi: hi <= lo ? lo + 1 : hi }
}

export function valueY(scale: { lo: number; hi: number }, value: number, height: number): number {
  return Math.round((height - 4 - (value - scale.lo) / (scale.hi - scale.lo) * (height - 8)) * 100) / 100
}

export function trackPath(track: DeepTrack, axis: TimeAxis, width: number, height: number): string[] {
  const scale = trackScale(track)
  return track.segments.flatMap((segment) => {
    const points = segment.flatMap((index) => {
      const point = track.points[index]
      const end = index === track.points.length - 1 ? track.endMs : point.startMs + track.stepMs
      if (end <= axis.fromMs || point.startMs >= axis.toMs || point.value === null) return []
      const middle = point.startMs + (end - point.startMs) / 2
      const x = Math.round((middle - axis.fromMs) / (axis.toMs - axis.fromMs) * width * 100) / 100
      return [`${x},${valueY(scale, point.value, height)}`]
    })
    return points.length ? [points.join(' ')] : []
  })
}

const deepColors: Record<string, ChartToken> = {
  'load-rps': 'brand',
  'load-p50': 'brand',
  'load-p95': 'warn',
  'load-p99': 'warn',
  'load-max': 'fail',
  'load-errors': 'fail',
  'load-error-rate': 'fail',
}

export function deepLane(track: DeepTrack, axis: TimeAxis): ChartLane {
  const scale = trackScale(track)
  return {
    key: track.key,
    color: deepColors[track.key] ?? 'text',
    ...(track.integer ? { integer: true } : {}),
    segments: track.segments.flatMap((segment) => {
      const points = segment.flatMap((index) => {
        const point = track.points[index]
        const end = index === track.points.length - 1 ? track.endMs : point.startMs + track.stepMs
        if (end <= axis.fromMs || point.startMs >= axis.toMs || point.value === null) return []
        return [{ x: point.startMs + (end - point.startMs) / 2, y: point.value }]
      })
      return points.length ? [points] : []
    }),
    lo: scale.lo,
    hi: scale.hi,
    thresholds: track.thresholds.map((item) => ({ id: item.ruleId, value: item.value, text: DEEP_LABELS.thresholdMark(formatNumber(item.value), track.unit), violated: item.violated })),
  }
}
