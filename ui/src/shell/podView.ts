// Данные по подам (pod-view.v1, платформа P4c): типы ответов API и чистые функции показа.
// Это диагностическая проекция: она вердикт не определяет, а значения `null` - пропуски, а не нули.
import type { AnalysisSummary } from '../types'

export interface PodViewGrid {
  start_epoch_ms: number
  step_ms: number
  column_count: number
}

export interface PodViewCoverage {
  pods_observed_total: number
  pods_included: number
  rows_observed_total: number
  rows_included: number
  selection: { kind: string; metric?: string; limit?: number }
}

export interface PodViewMeta {
  schema_version: 'pod-view.v1'
  arm: string | null
  grid: PodViewGrid
  coverage: PodViewCoverage
  services: Array<{ service: string; pods: number; rows: number }>
  pod_view_sha256: string
  resource_snapshot_sha256: string
}

export interface PodRow {
  id: string
  pod: string
  container: string | null
  metric: string
  unit: string
  aggregation: string
  values: Array<number | null>
}

export interface PodValuesPage {
  schema_version: 'pod-view.v1'
  pod_view_sha256: string
  numeric_encoding: 'ieee754-double'
  service: string
  grid: PodViewGrid & { from_ms: number; to_ms: number }
  rows: PodRow[]
  next_after: string | null
}

export interface WindowStats {
  max: number
  mean: number
  last: number
  count: number
}

// Статистика окна колонок [from, to) по наблюдённым значениям: `null` не считается нулём; строка без наблюдений даёт null.
export function windowStats(row: Pick<PodRow, 'values'>, from = 0, to = row.values.length): WindowStats | null {
  let max = -Infinity
  let sum = 0
  let count = 0
  let last: number | null = null
  for (let column = Math.max(0, from); column < Math.min(to, row.values.length); column += 1) {
    const value = row.values[column]
    if (value === null || value === undefined) continue
    if (value > max) max = value
    sum += value
    count += 1
    last = value
  }
  return count === 0 || last === null ? null : { max, mean: sum / count, last, count }
}

// Худшие по максимуму окна; строки без наблюдений идут в конец. Порядок устойчив: при равенстве - по pod, container, id.
export function worstPods<T extends PodRow>(rows: T[], limit: number, from = 0, to?: number): T[] {
  const keyed = rows.map((row) => ({ row, stats: windowStats(row, from, to) }))
  keyed.sort((left, right) => {
    if (left.stats === null || right.stats === null) return left.stats === right.stats ? 0 : left.stats === null ? 1 : -1
    return right.stats.max - left.stats.max
      || compare(left.row.pod, right.row.pod)
      || compare(left.row.container ?? '', right.row.container ?? '')
      || compare(left.row.id, right.row.id)
  })
  return keyed.slice(0, limit).map((item) => item.row)
}

function compare(left: string, right: string): number {
  return left < right ? -1 : left > right ? 1 : 0
}

export function gridKey(meta: Pick<PodViewMeta, 'grid'>): string {
  return `${meta.grid.start_epoch_ms}:${meta.grid.step_ms}:${meta.grid.column_count}`
}

export function rowLabel(row: Pick<PodRow, 'pod' | 'container'>): string {
  return row.container ? `${row.pod} / ${row.container}` : row.pod
}

export interface PodViewGroup {
  arm: string | null
  snapshotSha256: string
  analysisIds: string[]
}

// Где искать pod-view: анализы с тем же плечом и тем же хэшем снимка (проход 2 лежит в другом анализе, чем онлайн-вердикт).
// Выбранный анализ идёт первым. Без хэша снимка pod-view привязать не к чему: групп нет и запросов нет.
export function podViewGroups(analyses: AnalysisSummary[], selectedAnalysisId: string): PodViewGroup[] {
  const selected = analyses.find((item) => item.analysis_id === selectedAnalysisId)
  if (!selected?.resource_snapshot_sha256) return []
  const arms = new Map<string | null, AnalysisSummary[]>()
  for (const analysis of analyses) {
    const arm = analysis.resource_arm ?? null
    arms.set(arm, [...(arms.get(arm) ?? []), analysis])
  }
  const selectedArm = selected.resource_arm ?? null
  const groups: PodViewGroup[] = []
  for (const [arm, items] of [...arms.entries()].sort(([left], [right]) => compare(left ?? '', right ?? ''))) {
    if ((arm === null) !== (selectedArm === null)) continue
    const anchor = arm === selectedArm ? selected : items.find((item) => item.resource_snapshot_sha256)
    const hash = anchor?.resource_snapshot_sha256
    if (!anchor || !hash) continue
    const sameSnapshot = items.filter((item) => item.resource_snapshot_sha256 === hash && item.analysis_id !== anchor.analysis_id)
    groups.push({ arm, snapshotSha256: hash, analysisIds: [anchor.analysis_id, ...sameSnapshot.map((item) => item.analysis_id)] })
  }
  return groups
}

// Цвет клетки: линейная шкала от min до max выбранной метрики; пропуск (null) рисуется штриховкой, а не цветом.
export function cellLightness(value: number, min: number, max: number): number {
  if (max <= min) return 0.5
  return Math.min(1, Math.max(0, (value - min) / (max - min)))
}

export function valueRange(rows: PodRow[]): { min: number; max: number } | null {
  let min = Infinity
  let max = -Infinity
  for (const row of rows) {
    for (const value of row.values) {
      if (value === null) continue
      if (value < min) min = value
      if (value > max) max = value
    }
  }
  return min === Infinity ? null : { min, max }
}
