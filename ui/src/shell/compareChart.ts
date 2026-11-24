import type { AnalysisResult, MetricSummaryEvidence, PolicyCheckEvidence } from '../types'
import { OVERVIEW_LABELS } from './labels'
import { COMPARE_LABELS, type CompareLabels } from './labels.compare'
import { formatNumber, type LoadPoint, type LoadSeries, type TrackKey } from './overview'
import { signedOffset, type ChartFrame, type ChartLane, type ChartPoint, type ChartThreshold, type ChartToken } from './timeChart'

const laneColors: Record<TrackKey, ChartToken> = { rps: 'brand', errors: 'fail', p95: 'warn' }

function seriesSegments(series: LoadSeries, key: TrackKey): ChartPoint[][] {
  return series.segments.map((segment) =>
    segment.map((index) => ({ x: series.points[index].startMs, y: series.points[index][key] })),
  )
}

// Overall policy thresholds only: transaction-scoped rules and ratio rules (error_rate_ratio)
// must never be drawn onto aggregate count/bin or overall latency/RPS axes.
export function compareThresholds(
  result: AnalysisResult | null | undefined,
  labels: CompareLabels = COMPARE_LABELS,
): { rps: ChartThreshold[]; p95: ChartThreshold[] } {
  if (!result) return { rps: [], p95: [] }
  const metrics = new Map(
    result.evidence
      .filter((item): item is MetricSummaryEvidence => item.type === 'metric_summary')
      .map((item) => [item.id, item]),
  )
  const checks = result.evidence.filter((item): item is PolicyCheckEvidence => {
    if (item.type !== 'policy_check') return false
    const scope = item.scope ?? (item.metric_evidence_id ? metrics.get(item.metric_evidence_id)?.scope : undefined)
    return scope?.kind !== 'transaction'
  })
  const rps: ChartThreshold[] = []
  const p95: ChartThreshold[] = []
  const seen = new Set<string>()
  for (const check of checks) {
    if (check.metric !== 'throughput_rps' && check.metric !== 'response_time_p95_ms') continue
    if (!Number.isFinite(check.threshold)) continue
    const key = `${check.metric}:${check.rule_id}:${check.threshold}`
    if (seen.has(key)) continue
    seen.add(key)
    const violated = checks.some(
      (candidate) =>
        candidate.metric === check.metric
        && candidate.rule_id === check.rule_id
        && candidate.threshold === check.threshold
        && candidate.status === 'FAIL',
    )
    const unit = check.metric === 'throughput_rps' ? OVERVIEW_LABELS.unitRps : OVERVIEW_LABELS.unitMs
    const entry: ChartThreshold = {
      id: check.rule_id,
      value: check.threshold,
      text: labels.chartsThresholdMark(formatNumber(check.threshold), unit),
      violated,
    }
    if (check.metric === 'throughput_rps') rps.push(entry)
    else p95.push(entry)
  }
  return { rps, p95 }
}

export function compareLanes(
  currentSeries: LoadSeries,
  baselineSeries: LoadSeries,
  result?: AnalysisResult | null,
  labels: CompareLabels = COMPARE_LABELS,
): Record<TrackKey, ChartLane> {
  const thresholds = compareThresholds(result, labels)
  const build = (key: TrackKey): ChartLane => {
    const laneThresholds = key === 'rps' ? thresholds.rps : key === 'p95' ? thresholds.p95 : []
    const baseline = seriesSegments(baselineSeries, key)
    const hi = Math.max(
      0,
      ...[
        currentSeries.max[key],
        baselineSeries.max[key],
        ...laneThresholds.map((item) => item.value),
      ].filter(Number.isFinite),
    )
    return {
      key,
      color: laneColors[key],
      segments: seriesSegments(currentSeries, key),
      ...(baseline.length ? { baseline } : {}),
      lo: 0,
      hi,
      ...(key === 'errors' ? { integer: true } : {}),
      ...(laneThresholds.length ? { thresholds: laneThresholds } : {}),
    }
  }
  return { rps: build('rps'), errors: build('errors'), p95: build('p95') }
}

export function compareFrame(currentSeries: LoadSeries, baselineSeries: LoadSeries): ChartFrame {
  const times = [
    ...currentSeries.points.map((point) => point.startMs),
    ...baselineSeries.points.map((point) => point.startMs),
  ]
  if (!times.length) return { fromMs: 0, toMs: Math.max(currentSeries.rollupSeconds, baselineSeries.rollupSeconds) * 1000, originMs: 0 }
  const first = Math.min(...times)
  const last = Math.max(...times)
  if (last > first) return { fromMs: first, toMs: last, originMs: 0 }
  const half = Math.max(currentSeries.rollupSeconds, baselineSeries.rollupSeconds) * 500
  return { fromMs: first - half, toMs: first + half, originMs: 0 }
}

export function compareStops(currentSeries: LoadSeries, baselineSeries: LoadSeries): number[] {
  const times = new Set<number>()
  for (const point of currentSeries.points) times.add(point.startMs)
  for (const point of baselineSeries.points) times.add(point.startMs)
  return [...times].sort((left, right) => left - right)
}

export function nearestStopIndex(stops: readonly number[], frame: ChartFrame, fraction: number): number {
  if (!stops.length) return -1
  const clamped = Math.max(0, Math.min(1, fraction))
  const target = frame.fromMs + clamped * (frame.toMs - frame.fromMs)
  let bestIndex = 0
  let bestDistance = Math.abs(stops[0] - target)
  for (let index = 1; index < stops.length; index += 1) {
    const distance = Math.abs(stops[index] - target)
    if (distance < bestDistance) {
      bestIndex = index
      bestDistance = distance
    }
  }
  return bestIndex
}

function pointAt(series: LoadSeries, ms: number): LoadPoint | null {
  if (!series.points.length) return null
  const tolerance = series.rollupSeconds * 500
  let best: LoadPoint | null = null
  let bestDistance = Infinity
  for (const point of series.points) {
    const distance = Math.abs(point.startMs - ms)
    if (distance <= tolerance && distance < bestDistance) {
      best = point
      bestDistance = distance
    }
  }
  return best
}

export interface CompareReadout {
  time: string
  current: Record<TrackKey, string>
  baseline: Record<TrackKey, string>
  text: string
}

export function compareReadout(
  currentSeries: LoadSeries,
  baselineSeries: LoadSeries,
  ms: number,
  labels: CompareLabels = COMPARE_LABELS,
): CompareReadout {
  const cur = pointAt(currentSeries, ms)
  const base = pointAt(baselineSeries, ms)
  const fmt = (point: LoadPoint | null, key: TrackKey) => {
    if (!point || !Number.isFinite(point[key])) return '—'
    return formatNumber(point[key], key === 'errors' ? 0 : 2)
  }
  const time = signedOffset(ms)
  const current = { rps: fmt(cur, 'rps'), errors: fmt(cur, 'errors'), p95: fmt(cur, 'p95') }
  const baseline = { rps: fmt(base, 'rps'), errors: fmt(base, 'errors'), p95: fmt(base, 'p95') }
  const pair = (key: TrackKey) => `${current[key]} / ${baseline[key]}`
  return {
    time,
    current,
    baseline,
    text: labels.chartsCursorText(time, pair('rps'), pair('errors'), pair('p95')),
  }
}
