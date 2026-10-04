import type { AnalysisResult, Bucket, BucketPage, ResourceSeriesEntry, ResourceSeriesValues } from '../types'
import { OVERVIEW_LABELS } from './labels'
import { formatNumber, loadSeries } from './overview'

export interface SnapshotGrid { startMs: number; stepMs: number; pointCount: number }
export interface DeepPoint { startMs: number; value: number | null; observed: number | null }
export interface DeepThreshold { ruleId: string; operator: 'gt' | 'lt'; value: number; violated: boolean }
export interface DeepTrack {
  key: string
  label: string
  unit: string
  kind: 'load' | 'resource'
  reducer: 'mean' | 'max' | 'min' | null
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
