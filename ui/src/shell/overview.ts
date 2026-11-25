import type {
  AnalysisResult, Bucket, CorrelationPairEvidence, Incident, IncidentNegativeEvidence, IncidentNextCheck,
  MetricSummaryEvidence, PolicyCheckEvidence, ResourcePolicyCheckEvidence, ResourceSummaryEvidence, TrendCheckEvidence,
  TrendSummaryEvidence,
} from '../types'
import { CORRELATION_LABELS, OVERVIEW_LABELS, type ShellTabKey, type TrendDirection } from './labels'
import { diagnosticFailedLinesOf, failedLinesOf, summarizeVerdict } from '../verdictSummary'
import { CAPACITY_LABELS } from './labels.tables'
import { capacityNumber, capacityView, type CapacityView } from './tables'
import type { ChartFrame, ChartLane, ChartThreshold, ChartToken } from './timeChart'

export type TrackKey = 'rps' | 'errors' | 'p95'

export interface AttentionTarget {
  tab: ShellTabKey
  targetId: string
  stageId?: string
  seriesId?: string
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
  // Targets of the form ev-<evidence id> are rows of the rule table.
  const openLabel = target ? (target.targetId.startsWith('ev-') ? OVERVIEW_LABELS.openRules : openLabels[target.targetId]) : null
  return { key, kind, title, detail, diagnostic, badge: null, target, openLabel }
}

function noVerdictTarget(result: AnalysisResult, code: string | null): AttentionTarget | null {
  if (result.analysis_mode === 'capacity_step' && result.capacity_summary) return { tab: 'tables', targetId: 'capacity-results' }
  if (code === 'METRIC_NOT_AVAILABLE' || code === 'TRANSACTION_NOT_FOUND' || code === 'AMBIGUOUS_TRANSACTION' || code === 'BUSINESS_OBSERVATIONS_NOT_FOUND' || code === 'INSUFFICIENT_SAMPLES') {
    return { tab: 'tables', targetId: 'policy-results' }
  }
  if (code === 'RULE_WINDOW_NOT_FOUND') {
    const row = result.evidence.find((evidence) => evidence.type === 'rule_window_check')
    return { tab: 'tables', targetId: row ? `ev-${row.id}` : 'policy-results' }
  }
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
  // Reasons of diagnostic platform rules land in the coverage with a PASS verdict and have no other route to the table.
  if (code === 'RESOURCE_GAPS' || code === 'NO_OBSERVATIONS' || code === 'INSUFFICIENT_OBSERVATIONS' || code === 'RULE_WINDOW_TOO_SHORT' || code?.startsWith('RESOURCE_') || code?.startsWith('PLATFORM_')) {
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
  const coveredEvidenceIds = result.incidents?.status === 'EVALUATED' && result.incidents.items.length > 0
    ? new Set(result.incidents.items.flatMap((incident) => incident.evidence_ids))
    : null
  if (result.analysis_mode !== 'capacity_step') {
    for (const line of failedLinesOf(result)) {
      if (coveredEvidenceIds?.has(line.key)) continue
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
    if (coveredEvidenceIds?.has(line.key)) continue
    items.push(item(`diagnostic:resource:${line.key}`, 'diagnostic', line.title, line.detail, { tab: 'deep', targetId: 'deep-title' }, true))
  }
  for (const evidence of result.evidence) {
    if (evidence.type === 'trend_check' && evidence.status === 'TREND_OBSERVED') {
      if (coveredEvidenceIds?.has(evidence.id)) continue
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

export type CapacityStageKind = 'pass' | 'fail' | 'unverified'

export interface CapacityBlockStage {
  key: string
  label: string
  target: string
  achieved: string
  verdictText: string
  kind: CapacityStageKind
  status: string
  mark: string
  smallSample: boolean
  barX: number
  achievedX: number | null
}

export interface CapacityBlock {
  verdict: string
  verdictText: string
  boundText: string
  statement: string
  kneeLine: string | null
  guardWarning: boolean
  axisText: string
  counts: string
  legend: string
  smallSample: boolean
  lowerX: number | null
  upperX: number | null
  stages: CapacityBlockStage[]
}

const stageKinds: Record<string, [CapacityStageKind, string]> = { PASS: ['pass', 'PASS'], FAIL: ['fail', 'FAIL'], NO_POLICY: ['unverified', 'NO_POLICY'] }

// Требуемой ёмкости в capacity_summary нет, поэтому её связь с границей выводится из вердикта по правилу ядра:
// PASS - нижняя граница не меньше требуемой, FAIL - верхняя не больше, NO_VERDICT - границы недостаточно.
function capacityStatement(view: CapacityView, lower: string | null, upper: string | null, blocked: boolean): string {
  const unit = view.unit
  if (view.verdict === 'PASS' && lower !== null) {
    const base = OVERVIEW_LABELS.capacityStatementPass(lower, unit)
    return view.stages.some((stage) => stage.verdict === 'FAIL') ? `${base} ${OVERVIEW_LABELS.capacityPassFailedAbove}` : base
  }
  if (view.verdict === 'FAIL' && upper !== null) return OVERVIEW_LABELS.capacityStatementFail(upper, unit)
  if (view.verdict === 'NO_POLICY') return OVERVIEW_LABELS.capacityStatementNoPolicy
  if (view.verdict !== 'NO_VERDICT') return ''
  if (blocked) return OVERVIEW_LABELS.capacityStatementBlocked
  if (view.bound === 'BOUNDED' && lower !== null && upper !== null) return OVERVIEW_LABELS.capacityStatementBounded(lower, upper, unit)
  if (view.bound === 'LOWER_BOUND' && lower !== null) return OVERVIEW_LABELS.capacityStatementLower(lower, unit)
  if (view.bound === 'UPPER_BOUND' && upper !== null) return OVERVIEW_LABELS.capacityStatementUpper(upper, unit)
  return OVERVIEW_LABELS.capacityStatementIndeterminate
}

// Блок ёмкости «Обзора»: границы и ступени из capacity_summary. Все значения для графика лежат на одной шкале 0..1000.
export function capacityBlock(result: AnalysisResult): CapacityBlock | null {
  const summary = result.capacity_summary
  const view = capacityView(result)
  if (!summary || !view) return null
  const nonNegative = (value: number | string | null | undefined): number | null => {
    const number = value == null ? NaN : Number(value)
    return Number.isFinite(number) && number >= 0 ? number : null
  }
  const positive = (value: number | string | null | undefined): number | null => {
    const number = nonNegative(value)
    return number !== null && number > 0 ? number : null
  }
  const lowerValue = positive(summary.lower_inclusive)
  const upperValue = positive(summary.upper_exclusive)
  const stages = summary.stages ?? []
  const maximum = Math.max(1, lowerValue ?? 0, upperValue ?? 0, ...stages.flatMap((stage) => [positive(stage.target) ?? 0, nonNegative(stage.achieved) ?? 0]))
  const scale = (value: number | null) => value === null ? null : Math.round((value / maximum) * 100000) / 100
  const rows = view.stages.map((row, index): CapacityBlockStage => {
    const [kind, status] = stageKinds[row.verdict] ?? ['unverified', 'NO_VERDICT']
    return {
      key: row.key,
      label: row.stage,
      target: row.target,
      achieved: row.achieved,
      verdictText: row.verdictText,
      kind,
      status,
      mark: OVERVIEW_LABELS.stageMark[kind],
      smallSample: row.smallSample,
      barX: scale(positive(stages[index].target)) ?? 0,
      achievedX: scale(nonNegative(stages[index].achieved)),
    }
  })
  const lower = lowerValue === null ? null : capacityNumber(summary.lower_inclusive)
  const upper = upperValue === null ? null : capacityNumber(summary.upper_exclusive)
  const count = (kind: CapacityStageKind) => rows.filter((row) => row.kind === kind).length
  const guardWarning = stages.some((stage) =>
    (stage.reasons ?? []).some((code) => code === 'CAPACITY_GUARD_FAILED' || code === 'CAPACITY_GUARD_MISSING'),
  )
  const kneeLine = summary.capacity_knee == null
    ? null
    : OVERVIEW_LABELS.capacityKneeLine(capacityNumber(summary.capacity_knee), view.unit)
  return {
    verdict: view.verdict,
    verdictText: OVERVIEW_LABELS.capacityVerdict[view.verdict] ?? view.verdictText,
    boundText: view.boundText,
    statement: capacityStatement(view, lower, upper, view.reasons.some((reason) => reason.code === 'RULE_WINDOW_NOT_FOUND')),
    kneeLine,
    guardWarning,
    axisText: CAPACITY_LABELS.axisValue(view.axis, view.unit),
    counts: OVERVIEW_LABELS.capacityCounts(rows.length, count('pass'), count('fail'), count('unverified')),
    legend: OVERVIEW_LABELS.capacityLegend(lower, upper, view.unit),
    smallSample: view.smallSample,
    lowerX: scale(lowerValue),
    upperX: scale(upperValue),
    stages: rows,
  }
}

export function loadSeries(buckets: Bucket[], rollupSeconds: number): LoadSeries {
  const safeRollup = Number.isFinite(rollupSeconds) && rollupSeconds > 0 ? rollupSeconds : 0
  const finiteOrZero = (value: number) => (Number.isFinite(value) ? value : 0)
  const points = [...buckets].sort((left, right) => left.bucket_start_ms - right.bucket_start_ms).map((bucket) => ({
    startMs: bucket.bucket_start_ms,
    rps: safeRollup > 0 ? finiteOrZero(bucket.sample_count) / safeRollup : 0,
    errors: finiteOrZero(bucket.error_count),
    p95: finiteOrZero(bucket.p95_latency_ms),
  }))
  const segments: number[][] = []
  let missingIntervals = 0
  for (let index = 0; index < points.length; index += 1) {
    const previous = points[index - 1]
    const point = points[index]
    if (!previous || safeRollup <= 0 || point.startMs > previous.startMs + safeRollup * 1000) {
      segments.push([index])
      if (previous && safeRollup > 0) missingIntervals += Math.round((point.startMs - previous.startMs) / (safeRollup * 1000)) - 1
    } else {
      segments[segments.length - 1].push(index)
    }
  }
  return {
    points,
    segments,
    missingIntervals,
    rollupSeconds: safeRollup,
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

const laneColors: Record<TrackKey, ChartToken> = { rps: 'brand', errors: 'fail', p95: 'warn' }

export function loadP95Thresholds(result: AnalysisResult): ChartThreshold[] {
  const checks = result.evidence.filter((candidate): candidate is PolicyCheckEvidence =>
    candidate.type === 'policy_check' && candidate.metric === 'response_time_p95_ms' && candidate.scope?.kind === 'overall')
  const thresholds: ChartThreshold[] = []
  const seen = new Set<string>()
  for (const check of checks) {
    if (seen.has(check.rule_id)) continue
    const value = Number(check.threshold)
    if (!Number.isFinite(value) || value < 0) continue
    seen.add(check.rule_id)
    const violated = checks.some((candidate) => candidate.rule_id === check.rule_id && candidate.status === 'FAIL')
    thresholds.push({
      id: check.rule_id,
      value,
      text: OVERVIEW_LABELS.loadThresholdMark(formatNumber(value, 2), OVERVIEW_LABELS.unitMs),
      violated,
    })
  }
  return thresholds
}

export function loadLane(series: LoadSeries, key: TrackKey, thresholds: ChartThreshold[] = []): ChartLane {
  const hi = thresholds.length ? Math.max(series.max[key], ...thresholds.map((t) => t.value)) : series.max[key]
  return {
    key,
    color: laneColors[key],
    segments: series.segments.map((segment) => segment.map((index) => ({ x: series.points[index].startMs, y: series.points[index][key] }))),
    lo: 0,
    hi,
    ...(key === 'errors' ? { integer: true } : {}),
    ...(thresholds.length ? { thresholds } : {}),
  }
}

export function loadFrame(series: LoadSeries): ChartFrame {
  const first = series.points[0]?.startMs ?? 0
  const last = series.points[series.points.length - 1]?.startMs ?? 0
  if (last > first) return { fromMs: first, toMs: last, originMs: 0 }
  const half = series.rollupSeconds * 500
  return { fromMs: first - half, toMs: first + half, originMs: 0 }
}

export type IncidentAction =
  | { kind: 'navigate'; target: AttentionTarget; label: string }
  | { kind: 'scroll-coincident'; incidentId: string; label: string }

export interface IncidentCoincidentView {
  incidentId: string
  basis: 'SAME_WINDOW' | 'INTERVAL_OVERLAP'
  basisLabel: string
  targetTitle: string | null
  inStored: boolean
  inOverflow: boolean
}

export interface IncidentNextCheckView {
  check: IncidentNextCheck['check']
  text: string
  evidenceIds: string[]
  action: IncidentAction | null
}

export interface IncidentCard {
  id: string
  rank: number
  inOverview: boolean
  priority: Incident['priority']
  priorityLabel: string
  family: 'TRANSACTION' | 'RESOURCE'
  familyLabel: string
  title: string
  summary: string
  scopeLabel: string
  windowId: string | null
  intervalText: string
  findingCount: number
  findingTypes: string[]
  findingIds: string[]
  evidenceIds: string[]
  refsTruncated: boolean
  negativeEvidence: IncidentNegativeEvidence[]
  nextChecks: IncidentNextCheckView[]
  coincidentWith: IncidentCoincidentView[]
  primaryAction: IncidentAction | null
}

export interface IncidentView {
  status: 'ABSENT' | 'NOT_EVALUATED' | 'EVALUATED'
  reasonCode: string | null
  totalCount: number
  omittedCount: number
  overviewItems: IncidentCard[]
  moreItems: IncidentCard[]
  moreResourceCount: number
  moreTransactionCount: number
}

function formatUtcEpoch(ms: number): string {
  if (!Number.isFinite(ms)) return '—'
  try {
    const date = new Date(ms)
    if (!Number.isFinite(date.getTime())) return '—'
    return `${date.toISOString().replace('T', ' ').slice(0, 19)} UTC`
  } catch {
    return '—'
  }
}

function incidentScopeLabel(incident: Incident): string {
  const scope = incident.scope
  if (scope.kind === 'overall') return OVERVIEW_LABELS.incidentScopeOverall
  if (scope.kind === 'entity') return scope.entity
  return [...(scope.group_path ?? []), scope.label].join(' / ')
}

function targetForEvidence(result: AnalysisResult, evidenceId: string | undefined, windowId: string | null): AttentionTarget | null {
  if (!evidenceId) return null
  const found = result.evidence.find((candidate) => 'id' in candidate && candidate.id === evidenceId)
  if (!found) return null
  if (found.type === 'policy_check') return { tab: 'tables', targetId: 'policy-results' }
  if (found.type === 'resource_policy_check') return { tab: 'tables', targetId: 'resource-results' }
  if (found.type === 'trend_check') return { tab: 'tables', targetId: 'trend-results' }
  if (found.type === 'anomaly_check') return { tab: 'deep', targetId: 'deep-title', ...(windowId ? { stageId: windowId } : {}) }
  return null
}

function labelForTarget(target: AttentionTarget): string {
  return openLabels[target.targetId] ?? OVERVIEW_LABELS.openDeep
}

function seriesIdFromEvidence(result: AnalysisResult, evidenceId: string | undefined): string | undefined {
  if (!evidenceId) return undefined
  const found = result.evidence.find((candidate) => 'id' in candidate && candidate.id === evidenceId)
  if (!found) return undefined
  if (found.type === 'resource_policy_check' || found.type === 'resource_summary' || found.type === 'trend_check') {
    return found.series_id
  }
  return undefined
}

export function incidentView(result: AnalysisResult): IncidentView {
  const doc = result.incidents
  if (!doc) {
    return {
      status: 'ABSENT',
      reasonCode: null,
      totalCount: 0,
      omittedCount: 0,
      overviewItems: [],
      moreItems: [],
      moreResourceCount: 0,
      moreTransactionCount: 0,
    }
  }
  if (doc.status === 'NOT_EVALUATED') {
    return {
      status: 'NOT_EVALUATED',
      reasonCode: doc.reason_code ?? null,
      totalCount: 0,
      omittedCount: 0,
      overviewItems: [],
      moreItems: [],
      moreResourceCount: 0,
      moreTransactionCount: 0,
    }
  }

  const byId = new Map(doc.items.map((entry) => [entry.id, entry]))
  const cards: IncidentCard[] = doc.items.map((entry) => {
    const family = entry.grouping.key.family
    const intervalText = entry.interval_basis === 'UNKNOWN' || entry.interval === null
      ? OVERVIEW_LABELS.incidentIntervalUnknown
      : `${formatUtcEpoch(entry.interval.from_epoch_ms)} – ${formatUtcEpoch(entry.interval.to_epoch_ms)} (${OVERVIEW_LABELS.incidentIntervalBasis[entry.interval_basis] ?? entry.interval_basis})`

    const coincidentWith: IncidentCoincidentView[] = entry.coincident_with.map((link) => {
      const target = byId.get(link.incident_id)
      return {
        incidentId: link.incident_id,
        basis: link.basis,
        basisLabel: OVERVIEW_LABELS.incidentCoincidentBasis[link.basis] ?? link.basis,
        targetTitle: target?.title ?? null,
        inStored: Boolean(target),
        inOverflow: Boolean(target && !target.in_overview),
      }
    })

    const nextChecks: IncidentNextCheckView[] = entry.next_checks.map((check) => {
      let action: IncidentAction | null = null
      const firstEvId = check.evidence_ids[0] ?? entry.evidence_ids[0]
      if (check.check === 'COMPARE_WITH_BASELINE') {
        action = { kind: 'navigate', target: { tab: 'compare', targetId: 'baseline-panel' }, label: OVERVIEW_LABELS.incidentOpenCompare }
      } else if (check.check === 'OPEN_RESOURCE_SERIES') {
        const seriesId = seriesIdFromEvidence(result, firstEvId)
        action = {
          kind: 'navigate',
          target: {
            tab: 'deep',
            targetId: 'deep-title',
            ...(entry.window_id ? { stageId: entry.window_id } : {}),
            ...(seriesId ? { seriesId } : {}),
          },
          label: OVERVIEW_LABELS.openDeep,
        }
      } else if (check.check === 'OPEN_SAME_WINDOW_SIGNALS') {
        const storedTarget = coincidentWith.find((link) => link.inStored)
        if (storedTarget) {
          action = { kind: 'scroll-coincident', incidentId: storedTarget.incidentId, label: OVERVIEW_LABELS.incidentOpenSignal }
        } else {
          action = {
            kind: 'navigate',
            target: { tab: 'deep', targetId: 'deep-title', ...(entry.window_id ? { stageId: entry.window_id } : {}) },
            label: OVERVIEW_LABELS.openDeep,
          }
        }
      } else if (check.check === 'PROVIDE_RESOURCE_SNAPSHOT') {
        action = { kind: 'navigate', target: { tab: 'setup', targetId: 'resource-snapshot-file' }, label: OVERVIEW_LABELS.openSetup }
      } else if (check.check === 'COMPLETE_NOT_EVALUATED_CHECKS') {
        const target = targetForEvidence(result, firstEvId, entry.window_id) ?? { tab: 'tables', targetId: 'policy-results' }
        action = { kind: 'navigate', target, label: labelForTarget(target) }
      }
      return {
        check: check.check,
        text: check.text,
        evidenceIds: check.evidence_ids,
        action,
      }
    })

    const primaryTarget = targetForEvidence(result, entry.evidence_ids[0], entry.window_id)
      ?? (family === 'TRANSACTION'
        ? { tab: 'tables' as const, targetId: 'policy-results' }
        : { tab: 'deep' as const, targetId: 'deep-title', ...(entry.window_id ? { stageId: entry.window_id } : {}) })

    return {
      id: entry.id,
      rank: entry.rank,
      inOverview: entry.in_overview,
      priority: entry.priority,
      priorityLabel: OVERVIEW_LABELS.incidentPriority[entry.priority] ?? entry.priority,
      family,
      familyLabel: OVERVIEW_LABELS.incidentFamily[family] ?? family,
      title: entry.title,
      summary: entry.summary,
      scopeLabel: incidentScopeLabel(entry),
      windowId: entry.window_id,
      intervalText,
      findingCount: entry.finding_count,
      findingTypes: entry.finding_types,
      findingIds: entry.finding_ids,
      evidenceIds: entry.evidence_ids,
      refsTruncated: entry.refs_truncated,
      negativeEvidence: entry.negative_evidence,
      nextChecks,
      coincidentWith,
      primaryAction: { kind: 'navigate', target: primaryTarget, label: labelForTarget(primaryTarget) },
    }
  })

  const overviewItems = cards.filter((card) => card.inOverview)
  const moreItems = cards.filter((card) => !card.inOverview)
  return {
    status: 'EVALUATED',
    reasonCode: null,
    totalCount: doc.total_count,
    omittedCount: doc.omitted_count,
    overviewItems,
    moreItems,
    moreResourceCount: moreItems.filter((card) => card.family === 'RESOURCE').length,
    moreTransactionCount: moreItems.filter((card) => card.family === 'TRANSACTION').length,
  }
}

export interface TrendOverviewCard {
  id: string
  checkId: string
  seriesId: string
  entity: string | null
  unit: string | null
  windowId: string
  direction: TrendDirection | null
  directionLabel: string
  slope: string
  shift: string
  median: string
}

export interface TrendOverview {
  total: number
  observed: number
  notMaterial: number
  insufficient: number
  unavailable: number
  cards: TrendOverviewCard[]
}

export function trendOverview(result: AnalysisResult): TrendOverview | null {
  const summary = result.evidence.find((candidate): candidate is TrendSummaryEvidence => candidate.type === 'trend_summary')
  const checks = result.evidence.filter((candidate): candidate is TrendCheckEvidence => candidate.type === 'trend_check')
  if (!summary && checks.length === 0) return null

  const observedChecks = checks.filter((check) => check.status === 'TREND_OBSERVED')
  const cards: TrendOverviewCard[] = observedChecks.slice(0, 6).map((check) => {
    const dir = check.observed_direction
    const directionLabel = dir === 'increase' ? 'рост' : dir === 'decrease' ? 'снижение' : dir === 'flat' ? 'без изменений' : 'не определено'
    return {
      id: check.id,
      checkId: check.check_id,
      seriesId: check.series_id,
      entity: check.entity,
      unit: check.unit,
      windowId: check.window_id,
      direction: dir,
      directionLabel,
      slope: decimal(check.slope_per_second) || '—',
      shift: decimal(check.split_half_shift) || '—',
      median: decimal(check.median) || '—',
    }
  })

  return {
    total: summary?.checks_total ?? checks.length,
    observed: summary?.observed ?? observedChecks.length,
    notMaterial: summary?.not_material ?? checks.filter((c) => c.status === 'NO_MATERIAL_TREND').length,
    insufficient: summary?.insufficient ?? checks.filter((c) => c.status === 'INSUFFICIENT_CELLS').length,
    unavailable: summary?.unavailable ?? checks.filter((c) => c.status === 'UNAVAILABLE').length,
    cards,
  }
}

export type PlatformTileStatus = 'FAIL' | 'NO_VERDICT' | 'DIAGNOSTIC' | 'PASS'

export interface PlatformTile {
  seriesId: string
  entity: string
  metric: string
  unit: string
  role: 'system' | 'generator'
  windowId: string
  status: PlatformTileStatus
  statusLabel: string
  peakText: string
  missingCells: number
}

const PLATFORM_STATUS_ORDER: Record<PlatformTileStatus, number> = { FAIL: 0, NO_VERDICT: 1, DIAGNOSTIC: 2, PASS: 3 }

export function platformTiles(result: AnalysisResult, limit = 8): PlatformTile[] {
  const summaries = result.evidence.filter((candidate): candidate is ResourceSummaryEvidence => candidate.type === 'resource_summary')
  if (!summaries.length) return []
  const checks = result.evidence.filter((candidate): candidate is ResourcePolicyCheckEvidence => candidate.type === 'resource_policy_check')

  const bestBySeries = new Map<string, PlatformTile>()
  for (const summary of summaries) {
    const related = checks.filter((check) => check.series_id === summary.series_id && check.window_id === summary.window_id)
    let status: PlatformTileStatus = 'PASS'
    if (related.some((check) => check.effect === 'sla' && check.status === 'FAIL')) status = 'FAIL'
    else if (related.some((check) => check.effect === 'sla' && check.status === 'NO_VERDICT')) status = 'NO_VERDICT'
    else if (related.some((check) => check.effect === 'diagnostic' && check.status === 'FAIL')) status = 'DIAGNOSTIC'

    const stats = summary.statistics ?? {}
    const peakRaw = stats.max ?? stats.p95 ?? stats.mean ?? null
    const peakNum = peakRaw !== null ? Number(peakRaw) : NaN
    const peakText = peakRaw === null ? '—' : Number.isFinite(peakNum) ? formatNumber(peakNum, 2) : peakRaw

    const candidate: PlatformTile = {
      seriesId: summary.series_id,
      entity: summary.entity,
      metric: summary.metric,
      unit: summary.unit,
      role: summary.role,
      windowId: summary.window_id,
      status,
      statusLabel: OVERVIEW_LABELS.platformStatus[status] ?? status,
      peakText,
      missingCells: summary.missing_cells,
    }
    const existing = bestBySeries.get(summary.series_id)
    if (!existing || PLATFORM_STATUS_ORDER[candidate.status] < PLATFORM_STATUS_ORDER[existing.status]) {
      bestBySeries.set(summary.series_id, candidate)
    }
  }

  return [...bestBySeries.values()]
    .sort((a, b) => {
      const byStatus = PLATFORM_STATUS_ORDER[a.status] - PLATFORM_STATUS_ORDER[b.status]
      if (byStatus !== 0) return byStatus
      if (a.role !== b.role) return a.role === 'system' ? -1 : 1
      return a.seriesId < b.seriesId ? -1 : a.seriesId > b.seriesId ? 1 : 0
    })
    .slice(0, limit)
}
