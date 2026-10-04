import type { AnalysisResult, Bucket, MetricSummaryEvidence, ResourcePolicyCheckEvidence, ResourceSummaryEvidence } from '../types'
import { OVERVIEW_LABELS, type ShellTabKey, type TrendDirection } from './labels'
import { diagnosticFailedLinesOf, failedLinesOf, summarizeVerdict } from '../verdictSummary'

export type TrackKey = 'rps' | 'errors' | 'p95'

export interface AttentionTarget {
  tab: ShellTabKey
  targetId: string
}

export type AttentionKind = 'violation' | 'no_verdict' | 'policy' | 'coverage' | 'diagnostic'

export interface AttentionItem {
  key: string
  kind: AttentionKind
  title: string
  detail: string
  diagnostic: boolean
  target: AttentionTarget | null
  openLabel: string | null
}

export interface MetricTile {
  key: 'rps' | 'p95' | 'p99' | 'max'
  label: string
  value: string
  raw: string
}

export interface LoadPoint {
  startMs: number
  rps: number
  errors: number
  p95: number
}

export interface LoadSeries {
  points: LoadPoint[]
  segments: number[][]
  missingIntervals: number
  rollupSeconds: number
  max: Record<TrackKey, number>
}

export interface CursorReadout {
  time: string
  rps: string
  errors: string
  p95: string
  raw: { rps: string; errors: string; p95: string }
  text: string
}

const openLabels: Record<string, string> = {
  'policy-results': OVERVIEW_LABELS.openRules,
  'resource-results': OVERVIEW_LABELS.openResources,
  'capacity-results': OVERVIEW_LABELS.openCapacity,
  'trend-results': OVERVIEW_LABELS.openTrends,
  'deep-title': OVERVIEW_LABELS.openDeep,
  'diagnostic-results': OVERVIEW_LABELS.openDiagnostics,
  'source-acquisition': OVERVIEW_LABELS.openSources,
  'policy-file': OVERVIEW_LABELS.openSetup,
}

export function formatNumber(value: number, maxFractionDigits = 2): string {
  return new Intl.NumberFormat('ru-RU', { maximumFractionDigits: maxFractionDigits }).format(value)
}

function item(key: string, kind: AttentionKind, title: string, detail: string, target: AttentionTarget | null, diagnostic = false): AttentionItem {
  return { key, kind, title, detail, diagnostic, target, openLabel: target ? openLabels[target.targetId] : null }
}

function noVerdictTarget(result: AnalysisResult, code: string | null): AttentionTarget | null {
  if (result.analysis_mode === 'capacity_step' && result.capacity_summary) return { tab: 'tables', targetId: 'capacity-results' }
  if (code === 'METRIC_NOT_AVAILABLE' || code === 'TRANSACTION_NOT_FOUND' || code === 'AMBIGUOUS_TRANSACTION' || code === 'BUSINESS_OBSERVATIONS_NOT_FOUND' || code === 'INSUFFICIENT_SAMPLES') {
    return { tab: 'tables', targetId: 'policy-results' }
  }
  if (code === 'RESOURCE_SERIES_NOT_FOUND' || code === 'MISSING_RESOURCE_CELLS') return { tab: 'tables', targetId: 'resource-results' }
  return null
}

function noteTarget(code: string | null): AttentionTarget | null {
  if (code === 'SMALL_SAMPLE' || code === 'INSUFFICIENT_SAMPLES') return { tab: 'tables', targetId: 'policy-results' }
  if (code?.startsWith('SOURCE_')) return { tab: 'tables', targetId: 'source-acquisition' }
  if (code === 'RESOURCE_GAPS' || code === 'NO_OBSERVATIONS' || code === 'INSUFFICIENT_OBSERVATIONS' || code?.startsWith('RESOURCE_')) {
    return { tab: 'tables', targetId: 'resource-results' }
  }
  return null
}

function decimal(value: string | null): string {
  if (value === null || value === '') return ''
  const number = Number(value)
  return Number.isFinite(number) ? formatNumber(number, 2) : value
}

export function attentionItems(result: AnalysisResult): AttentionItem[] {
  const summary = summarizeVerdict(result)
  const items: AttentionItem[] = []
  if (result.analysis_mode !== 'capacity_step') {
    for (const line of failedLinesOf(result)) {
      items.push(item(`violation:${line.key}`, 'violation', line.title, line.detail, { tab: 'tables', targetId: line.source === 'business' ? 'policy-results' : 'resource-results' }))
    }
  }
  if (result.policy_verdict === 'NO_VERDICT') {
    for (const cause of summary.causes) {
      const detail = cause.subjects.length
        ? OVERVIEW_LABELS.causeSubjects(cause.subjectsLabel, cause.subjects, cause.subjectsHidden)
        : (cause.detail ?? '')
      items.push(item(`no_verdict:${cause.code}|${cause.subjectsLabel}`, 'no_verdict', cause.text, detail, noVerdictTarget(result, cause.code)))
    }
  }
  if (result.policy_verdict === 'NO_POLICY') {
    items.push(item('policy:none', 'policy', OVERVIEW_LABELS.noPolicyTitle, OVERVIEW_LABELS.noPolicyDetail, { tab: 'setup', targetId: 'policy-file' }))
  }
  if (result.run_validity !== 'VALID' && result.policy_verdict !== 'NO_VERDICT') {
    items.push(item('coverage:validity', 'coverage', result.run_validity === 'DEGRADED' ? OVERVIEW_LABELS.validityDegraded : OVERVIEW_LABELS.validityInvalid, '', null))
  }
  for (const note of summary.notes) items.push(item(`coverage:${note.code}`, 'coverage', note.text, '', noteTarget(note.code)))

  const bound = new Set(result.evidence
    .filter((evidence): evidence is ResourcePolicyCheckEvidence => evidence.type === 'resource_policy_check' && evidence.effect === 'sla')
    .map((evidence) => `${evidence.series_id}|${evidence.window_id}`))
  const resourceSummaries = result.evidence.filter((evidence): evidence is ResourceSummaryEvidence =>
    evidence.type === 'resource_summary' && evidence.role !== 'generator' && bound.has(`${evidence.series_id}|${evidence.window_id}`))
  const empty = resourceSummaries.filter((evidence) => evidence.reasons.includes('NO_OBSERVATIONS'))
  if (empty.length) {
    const seriesIds = [...new Set(empty.map((evidence) => evidence.series_id))]
    items.push(item(
      'coverage:resource-series',
      'coverage',
      OVERVIEW_LABELS.resourceSeriesTitle(empty.length, resourceSummaries.length),
      OVERVIEW_LABELS.resourceSeriesDetail(seriesIds.slice(0, 3), Math.max(0, seriesIds.length - 3)),
      { tab: 'tables', targetId: 'resource-results' },
    ))
  }
  for (const line of diagnosticFailedLinesOf(result)) {
    items.push(item(`diagnostic:resource:${line.key}`, 'diagnostic', line.title, line.detail, { tab: 'deep', targetId: 'deep-title' }, true))
  }
  for (const evidence of result.evidence) {
    if (evidence.type === 'trend_check' && evidence.status === 'TREND_OBSERVED') {
      const direction: TrendDirection | null = evidence.observed_direction
      items.push(item(
        `diagnostic:trend:${evidence.id}`,
        'diagnostic',
        OVERVIEW_LABELS.trendTitle(evidence.series_id),
        OVERVIEW_LABELS.trendDetail(evidence.window_id, direction, decimal(evidence.split_half_shift), decimal(evidence.median), evidence.unit),
        { tab: 'tables', targetId: 'trend-results' },
        true,
      ))
    }
  }
  for (const evidence of result.evidence) {
    if (evidence.type === 'correlation_pair' && evidence.status === 'CANDIDATE' && evidence.raw_rho !== null) {
      items.push(item(
        `diagnostic:correlation:${evidence.id}`,
        'diagnostic',
        OVERVIEW_LABELS.correlationTitle(evidence.resource_series_id, evidence.load_metric),
        OVERVIEW_LABELS.correlationDetail(evidence.window_id, decimal(evidence.raw_rho), evidence.paired_cells, evidence.expected_cells),
        { tab: 'tables', targetId: 'diagnostic-results' },
        true,
      ))
    }
  }
  return items
}

export function keyMetrics(result: AnalysisResult): MetricTile[] {
  const overall = result.evidence.find((evidence): evidence is MetricSummaryEvidence => evidence.type === 'metric_summary' && evidence.scope.kind === 'overall')
  if (!overall) return []
  const tiles: MetricTile[] = []
  if (overall.throughput_rps.denominator !== 0) {
    const value = overall.throughput_rps.numerator / overall.throughput_rps.denominator
    tiles.push({ key: 'rps', label: OVERVIEW_LABELS.metricRps, raw: value.toFixed(2), value: `${formatNumber(value, 2)} ${OVERVIEW_LABELS.unitRps}` })
  }
  for (const [key, label, value] of [
    ['p95', OVERVIEW_LABELS.metricP95, overall.latency_ms.p95],
    ['p99', OVERVIEW_LABELS.metricP99, overall.latency_ms.p99],
    ['max', OVERVIEW_LABELS.metricMax, overall.latency_ms.max],
  ] as const) {
    tiles.push({ key, label, raw: String(value), value: `${formatNumber(value, 2)} ${OVERVIEW_LABELS.unitMs}` })
  }
  return tiles
}

export function loadSeries(buckets: Bucket[], rollupSeconds: number): LoadSeries {
  const points = [...buckets].sort((left, right) => left.bucket_start_ms - right.bucket_start_ms).map((bucket) => ({
    startMs: bucket.bucket_start_ms,
    rps: bucket.sample_count / rollupSeconds,
    errors: bucket.error_count,
    p95: bucket.p95_latency_ms,
  }))
  const segments: number[][] = []
  let missingIntervals = 0
  for (let index = 0; index < points.length; index += 1) {
    const previous = points[index - 1]
    const point = points[index]
    if (!previous || point.startMs > previous.startMs + rollupSeconds * 1000) {
      segments.push([index])
      if (previous) missingIntervals += Math.round((point.startMs - previous.startMs) / (rollupSeconds * 1000)) - 1
    } else {
      segments[segments.length - 1].push(index)
    }
  }
  return {
    points,
    segments,
    missingIntervals,
    rollupSeconds,
    max: {
      rps: Math.max(0, ...points.map((point) => point.rps)),
      errors: Math.max(0, ...points.map((point) => point.errors)),
      p95: Math.max(0, ...points.map((point) => point.p95)),
    },
  }
}

export function cursorFraction(series: LoadSeries, index: number): number {
  const first = series.points[0]
  const last = series.points[series.points.length - 1]
  if (!first || !last || last.startMs === first.startMs) return 0.5
  return (series.points[index].startMs - first.startMs) / (last.startMs - first.startMs)
}

export function nearestIndex(series: LoadSeries, fraction: number): number {
  if (!series.points.length) return -1
  const clamped = Math.max(0, Math.min(1, fraction))
  let nearest = 0
  let distance = Math.abs(cursorFraction(series, 0) - clamped)
  for (let index = 1; index < series.points.length; index += 1) {
    const candidate = Math.abs(cursorFraction(series, index) - clamped)
    if (candidate < distance) {
      nearest = index
      distance = candidate
    }
  }
  return nearest
}

function rounded(value: number): string {
  return String(Math.round(value * 100) / 100)
}

export function trackPoints(series: LoadSeries, key: TrackKey, width: number, height: number): string[] {
  const maximum = series.max[key] || 1
  return series.segments.map((segment) => segment.map((index) => {
    const point = series.points[index]
    const x = cursorFraction(series, index) * width
    const y = height - 4 - (point[key] / maximum) * (height - 8)
    return `${rounded(x)},${rounded(y)}`
  }).join(' '))
}

export function formatOffset(ms: number): string {
  const total = Math.floor(ms / 1000)
  const seconds = String(total % 60).padStart(2, '0')
  const minutes = Math.floor(total / 60)
  if (minutes < 60) return `${minutes}:${seconds}`
  return `${Math.floor(minutes / 60)}:${String(minutes % 60).padStart(2, '0')}:${seconds}`
}

export function cursorSummary(series: LoadSeries, index: number): CursorReadout {
  const point = series.points[index]
  const time = formatOffset(point.startMs)
  const rps = formatNumber(point.rps, 2)
  const errors = formatNumber(point.errors, 0)
  const p95 = formatNumber(point.p95, 2)
  return {
    time,
    rps,
    errors,
    p95,
    raw: { rps: point.rps.toFixed(2), errors: String(point.errors), p95: String(point.p95) },
    text: OVERVIEW_LABELS.cursorText(time, rps, errors, p95),
  }
}
