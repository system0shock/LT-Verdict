import type { AnalysisResult, Bucket, CorrelationPairEvidence, MetricSummaryEvidence, ResourcePolicyCheckEvidence, ResourceSummaryEvidence } from '../types'
import { CORRELATION_LABELS, OVERVIEW_LABELS, type ShellTabKey, type TrendDirection } from './labels'
import { diagnosticFailedLinesOf, failedLinesOf, summarizeVerdict } from '../verdictSummary'
import { capacityView } from './tables'

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
  badge: string | null
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
  return { key, kind, title, detail, diagnostic, badge: null, target, openLabel: target ? openLabels[target.targetId] : null }
}

function noVerdictTarget(result: AnalysisResult, code: string | null): AttentionTarget | null {
  if (result.analysis_mode === 'capacity_step' && result.capacity_summary) return { tab: 'tables', targetId: 'capacity-results' }
  if (code === 'METRIC_NOT_AVAILABLE' || code === 'TRANSACTION_NOT_FOUND' || code === 'AMBIGUOUS_TRANSACTION' || code === 'BUSINESS_OBSERVATIONS_NOT_FOUND' || code === 'INSUFFICIENT_SAMPLES') {
    return { tab: 'tables', targetId: 'policy-results' }
  }
  if (code === 'RULE_WINDOW_NOT_FOUND') return { tab: 'tables', targetId: 'policy-results' }
  if (
    code === 'RESOURCE_SERIES_NOT_FOUND' || code === 'MISSING_RESOURCE_CELLS' || code === 'RESOURCE_SNAPSHOT_REQUIRED'
    || code === 'RULE_WINDOW_TOO_SHORT' || code === 'PLATFORM_SERIES_AMBIGUOUS' || code === 'PLATFORM_UNIT_MISMATCH'
    || code === 'PLATFORM_AGGREGATION_MISMATCH' || code === 'PLATFORM_SERVICE_NOT_IN_CATALOG'
  ) return { tab: 'tables', targetId: 'resource-results' }
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

export interface SelectedCorrelation {
  key: string
  windowId: string
  series: string
  loadMetric: string
  lagSeconds: string
  rho: string
  adjustedP: string
  familySize: number
  note: string
}

export interface UnavailableFamily {
  windowId: string
  count: number
  total: number
  reasons: string[]
}

// Минус всегда обычный дефис: Intl в разных средах пишет то дефис, то U+2212.
function signed(value: number, maxFractionDigits: number): string {
  return formatNumber(value, maxFractionDigits).replace(/−/g, '-')
}

function coefficient(value: string | null): string {
  const number = Number(value)
  return value === null || value === '' ? CORRELATION_LABELS.noValue : Number.isFinite(number) ? signed(number, 2) : value
}

function probability(value: string | null): string {
  if (value === null || value === '') return CORRELATION_LABELS.noValue
  const number = Number(value)
  if (!Number.isFinite(number)) return value
  return number > 0 && number < 0.0001 ? '< 0,0001' : formatNumber(number, 4)
}

// Только пары, отобранные методом (selected=true), со строкой пары по (pair_id, window_id): ряд, исход, лаг и коэффициент
// берутся из correlation_pair (evidence отбора их не содержит). Результат без evidence отбора не даёт ни одной записи.
export function selectedCorrelations(result: AnalysisResult): SelectedCorrelation[] {
  // Ключ-кортеж в JSON: идентификаторы пары и окна могут содержать любой разделитель.
  const pairs = new Map<string, CorrelationPairEvidence>()
  for (const evidence of result.evidence) {
    if (evidence.type === 'correlation_pair') pairs.set(JSON.stringify([evidence.pair_id, evidence.window_id]), evidence)
  }
  const selected: SelectedCorrelation[] = []
  for (const evidence of result.evidence) {
    if (evidence.type !== 'correlation_headline_selection' || evidence.selected !== true) continue
    const pair = pairs.get(JSON.stringify([evidence.pair_id, evidence.window_id]))
    if (!pair) continue
    selected.push({
      key: pair.id,
      windowId: evidence.window_id,
      series: pair.resource_series_id,
      loadMetric: pair.load_metric,
      lagSeconds: pair.best_lag_ms === null ? CORRELATION_LABELS.noValue : signed(pair.best_lag_ms / 1000, 3),
      rho: coefficient(pair.best_lag_rho),
      adjustedP: probability(evidence.holm_adjusted_p_value),
      familySize: evidence.family_hypotheses,
      note: CORRELATION_LABELS.methodNote(evidence.method, evidence.representation, evidence.stage_count),
    })
  }
  return selected
}

// Семьи, которые не удалось проверить: словами, отдельно от находок.
export function unavailableFamilies(result: AnalysisResult): UnavailableFamily[] {
  const families = new Map<string, UnavailableFamily>()
  for (const evidence of result.evidence) {
    if (evidence.type !== 'correlation_headline_selection' || evidence.status !== 'UNAVAILABLE') continue
    const family = families.get(evidence.window_id) ?? { windowId: evidence.window_id, count: 0, total: 0, reasons: [] }
    family.count += 1
    family.total = Math.max(family.total, evidence.family_hypotheses)
    for (const code of evidence.reasons) {
      const text = CORRELATION_LABELS.unavailable[code] ?? code
      if (!family.reasons.includes(text)) family.reasons.push(text)
    }
    families.set(evidence.window_id, family)
  }
  return [...families.values()]
}

export function attentionItems(result: AnalysisResult): AttentionItem[] {
  const summary = summarizeVerdict(result)
  const items: AttentionItem[] = []
  if (result.analysis_mode !== 'capacity_step') {
    for (const line of failedLinesOf(result)) {
      items.push(item(`violation:${line.key}`, 'violation', line.title, line.detail, { tab: 'tables', targetId: line.source === 'business' ? 'policy-results' : 'resource-results' }))
    }
  }
  if (result.analysis_mode === 'capacity_step' && result.policy_verdict === 'FAIL') {
    items.push(item('violation:capacity', 'violation', OVERVIEW_LABELS.capacityFailTitle, capacityView(result)?.boundText ?? '', { tab: 'tables', targetId: 'capacity-results' }))
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
  for (const correlation of selectedCorrelations(result)) {
    items.push({
      ...item(
        `diagnostic:correlation:${correlation.key}`,
        'diagnostic',
        OVERVIEW_LABELS.correlationTitle(correlation.windowId, correlation.series, correlation.loadMetric),
        OVERVIEW_LABELS.correlationDetail(correlation.lagSeconds, correlation.rho, correlation.adjustedP, correlation.familySize, correlation.note),
        { tab: 'tables', targetId: 'diagnostic-results' },
        true,
      ),
      badge: CORRELATION_LABELS.mark,
    })
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
