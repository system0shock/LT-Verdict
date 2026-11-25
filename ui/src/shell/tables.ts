import type { AnalysisResult, CapacityKneeDiagnosticEvidence, CapacityStage, ExactRatio, MetricSummaryEvidence, PolicyCheckEvidence, RuleWindowCheckEvidence, TrendCheckEvidence, TrendSummaryEvidence } from '../types'
import { metricLabel, scopeLabel, valueText } from '../verdictSummary'
import { reasonText as verdictReasonText } from '../verdictReasons'
import { CAPACITY_LABELS, TABLES_LABELS, TREND_LABELS, type TablesSubTabKey } from './labels.tables'

export type RuleStatus = 'PASS' | 'FAIL' | 'NO_VERDICT'

export interface RuleRow {
  key: string
  ruleId: string
  scope: string
  metric: string
  condition: string
  threshold: string
  observed: string
  window: string | null
  sample: string
  status: RuleStatus
  reasonCode: string | null
  reasonText: string | null
}

const sampleFormatter = new Intl.NumberFormat('ru-RU')
const sampleNumber = (value: number | undefined): string =>
  typeof value === 'number' && Number.isFinite(value) ? sampleFormatter.format(value) : TABLES_LABELS.noSample

export function ruleRows(result: AnalysisResult): RuleRow[] {
  const metrics = new Map(result.evidence
    .filter((item): item is MetricSummaryEvidence => item.type === 'metric_summary')
    .map((item) => [item.id, item]))
  const rows = result.evidence
    .filter((item): item is PolicyCheckEvidence => item.type === 'policy_check')
    .map((check) => {
      const scope = check.scope ?? (check.metric_evidence_id ? metrics.get(check.metric_evidence_id)?.scope : undefined)
      let digits = 2
      let observed = check.observed == null ? TABLES_LABELS.noData : valueText(check.metric, check.observed, digits)
      let threshold = valueText(check.metric, check.threshold, digits)
      while (observed === threshold && digits < 20) {
        digits = Math.min(digits * 2, 20)
        observed = check.observed == null ? TABLES_LABELS.noData : valueText(check.metric, check.observed, digits)
        threshold = valueText(check.metric, check.threshold, digits)
      }
      if (check.status === 'FAIL' && observed === threshold) observed += TABLES_LABELS.belowDisplayPrecision
      const reasonCode = check.reason_code ?? null
      const mode = check.sample_mode
      const modeText = mode ? (TABLES_LABELS.sampleModeText[mode as keyof typeof TABLES_LABELS.sampleModeText] ?? mode) : null
      const count = sampleNumber(check.sample_count)
      const limit = sampleNumber(mode === 'INSUFFICIENT' ? check.sample_floor : check.min_samples)
      const sample = !mode ? TABLES_LABELS.noSample
        : mode === 'NOT_GATED' ? `${count} \u00B7 ${modeText}`
          : `${TABLES_LABELS.sampleOf(count, limit)} \u00B7 ${modeText}`
      return {
        key: check.id,
        ruleId: check.rule_id,
        scope: !scope ? TABLES_LABELS.scopeMissing : scope.kind === 'overall' ? TABLES_LABELS.scopeOverall : scopeLabel(scope),
        metric: metricLabel(check.metric, check.window_id),
        condition: check.operator === 'lte' ? TABLES_LABELS.conditionLte : TABLES_LABELS.conditionGte,
        threshold,
        observed,
        window: check.window_id ?? null,
        sample,
        status: check.status,
        reasonCode,
        reasonText: reasonCode ? verdictReasonText(reasonCode) : null,
      }
    })
  // The rule names a window the snapshot does not have: there is no metric to show, only the rule, the window and the reason.
  const unknownWindows = result.evidence
    .filter((item): item is RuleWindowCheckEvidence => item.type === 'rule_window_check')
    .map((check): RuleRow => ({
      key: check.id,
      ruleId: check.rule_id,
      scope: TABLES_LABELS.noWindow,
      metric: TABLES_LABELS.noWindow,
      condition: TABLES_LABELS.noWindow,
      threshold: '',
      observed: TABLES_LABELS.noWindow,
      window: check.window_id,
      sample: TABLES_LABELS.noSample,
      status: check.status,
      reasonCode: check.reason_code,
      reasonText: verdictReasonText(check.reason_code),
    }))
  return [...rows, ...unknownWindows]
}

export type RuleSortKey = 'default' | 'ruleId' | 'scope' | 'metric' | 'window' | 'status'

const RULE_STATUS_RANK: Record<RuleStatus, number> = { FAIL: 0, NO_VERDICT: 1, PASS: 2 }

export function sortRuleRows(rows: readonly RuleRow[], sort: RuleSortKey, dir: 1 | -1): RuleRow[] {
  if (sort === 'default') return [...rows]
  return rows.map((row, index) => ({ row, index })).sort((left, right) => {
    let order = 0
    if (sort === 'status') order = RULE_STATUS_RANK[left.row.status] - RULE_STATUS_RANK[right.row.status]
    else if (sort === 'window') order = compareExact(left.row.window ?? '', right.row.window ?? '')
    else order = compareExact(left.row[sort], right.row[sort])
    return order * dir || left.index - right.index
  }).map((item) => item.row)
}

export type TxStatus = 'FAIL' | 'NO_VERDICT' | 'PASS' | 'NO_POLICY' | 'NOT_CHECKED'

export interface TxRow {
  key: string
  path: string
  label: string
  samples: number
  errors: number
  errorRate: string
  p50: string
  p95: string
  p99: string
  rps: string
  status: TxStatus
  sort: { samples: number; errors: number; errorRate: number; p50: number; p95: number; p99: number; rps: number }
}

function ratioNumber(value: ExactRatio | null | undefined): number {
  return value && value.denominator !== 0 ? value.numerator / value.denominator : -1
}

function finite(value: number | null | undefined): number {
  return value != null && Number.isFinite(value) ? value : -1
}

function display(metric: string, value: number | ExactRatio | null | undefined): string {
  return value == null ? TABLES_LABELS.noData : valueText(metric, value)
}

function sameTransaction(left: PolicyCheckEvidence['scope'], right: MetricSummaryEvidence['scope']): boolean {
  if (left?.kind !== 'transaction' || right.kind !== 'transaction') return false
  return left.label === right.label && left.sample_kind === right.sample_kind && (left.group_path ?? []).join('\u0000') === (right.group_path ?? []).join('\u0000')
}

export function transactionRows(result: AnalysisResult): TxRow[] {
  const checks = result.evidence.filter((item): item is PolicyCheckEvidence => item.type === 'policy_check')
  return result.evidence
    .filter((item): item is MetricSummaryEvidence => item.type === 'metric_summary' && item.scope.kind === 'transaction')
    .map((item) => {
      const scope = item.scope
      if (scope.kind !== 'transaction') throw new Error('Expected transaction scope')
      // Windowed checks carry the full transaction scope instead of a metric reference.
      const related = checks.filter((check) => check.metric_evidence_id === item.id || (!check.metric_evidence_id && sameTransaction(check.scope, scope)))
      const status: TxStatus = related.some((check) => check.status === 'FAIL') ? 'FAIL'
        : related.some((check) => check.status === 'NO_VERDICT') ? 'NO_VERDICT'
          : related.some((check) => check.status === 'PASS') ? 'PASS'
            : result.policy_verdict === 'NO_POLICY' ? 'NO_POLICY' : 'NOT_CHECKED'
      const p50 = item.latency_ms?.p50
      const p95 = item.latency_ms?.p95
      const p99 = item.latency_ms?.p99
      return {
        key: item.id,
        path: (scope.group_path ?? []).join(' / '),
        label: scope.label,
        samples: item.sample_count,
        errors: item.error_count,
        errorRate: display('error_rate_ratio', item.error_rate_ratio),
        p50: display('response_time_p95_ms', p50),
        p95: display('response_time_p95_ms', p95),
        p99: display('response_time_p95_ms', p99),
        rps: display('throughput_rps', item.throughput_rps),
        status,
        sort: {
          samples: finite(item.sample_count),
          errors: finite(item.error_count),
          errorRate: finite(ratioNumber(item.error_rate_ratio)),
          p50: finite(p50),
          p95: finite(p95),
          p99: finite(p99),
          rps: finite(ratioNumber(item.throughput_rps)),
        },
      }
    })
}

export type TxSortKey = 'impact' | 'label' | 'samples' | 'errors' | 'errorRate' | 'p50' | 'p95' | 'p99' | 'rps' | 'status'
export interface TxQuery { text: string; status: 'all' | TxStatus; sort: TxSortKey; dir: 1 | -1 }
export const DEFAULT_TX_QUERY: TxQuery = { text: '', status: 'all', sort: 'impact', dir: 1 }
const TX_STATUS_RANK: Record<TxStatus, number> = { FAIL: 0, NO_VERDICT: 1, PASS: 2, NOT_CHECKED: 3, NO_POLICY: 4 }

function compareExact(left: string, right: string): number {
  return left < right ? -1 : left > right ? 1 : 0
}

function impact(left: TxRow, right: TxRow): number {
  return Number(right.status === 'FAIL') - Number(left.status === 'FAIL')
    || right.sort.errors - left.sort.errors
    || right.sort.p99 - left.sort.p99
    || right.sort.samples - left.sort.samples
    || compareExact(left.path, right.path)
    || compareExact(left.label, right.label)
}

export function queryTransactions(rows: readonly TxRow[], query: TxQuery): TxRow[] {
  const text = query.text.toLowerCase()
  return rows
    .filter((row) => (`${row.path} ${row.label}`).toLowerCase().includes(text) && (query.status === 'all' || row.status === query.status))
    .sort((left, right) => {
      if (query.sort === 'impact') return impact(left, right)
      if (query.sort === 'label' || query.sort === 'status') {
        const order = query.sort === 'label'
          ? compareExact(`${left.path}/${left.label}`, `${right.path}/${right.label}`)
          : TX_STATUS_RANK[left.status] - TX_STATUS_RANK[right.status]
        return order * query.dir || impact(left, right)
      }
      const leftVal = left.sort[query.sort]
      const rightVal = right.sort[query.sort]
      const leftMissing = !Number.isFinite(leftVal) || leftVal < 0
      const rightMissing = !Number.isFinite(rightVal) || rightVal < 0
      if (leftMissing !== rightMissing) return leftMissing ? 1 : -1
      const order = leftMissing ? 0 : leftVal - rightVal
      return order * query.dir || impact(left, right)
    })
}

export interface CapacityStageRow {
  key: string
  stage: string
  target: string
  achieved: string
  observed: string
  bins: string
  verified: string
  verdict: string
  verdictText: string
  smallSample: boolean
  reasons: Array<{ code: string; text: string }>
  evidence: string[]
}

export interface CapacityView {
  axis: string
  unit: string
  bound: string
  boundText: string
  verdict: string
  verdictText: string
  reasons: Array<{ code: string; text: string }>
  kneeText: string
  kneeDiagnosticText?: string
  smallSample: boolean
  stages: CapacityStageRow[]
}

const capacityGroups = new Intl.NumberFormat('ru-RU')
// Capacity values are exact decimals: the digits are kept as the server sent them (no rounding), only grouped and with a decimal comma.
export function capacityNumber(value: number | string | null | undefined): string {
  if (value == null) return TABLES_LABELS.noData
  if (typeof value === 'number' && !Number.isFinite(value)) return TABLES_LABELS.noData
  const text = String(value).trim()
  if (text === '') return TABLES_LABELS.noData
  const match = /^(-?)(\d+)(?:\.(\d+))?$/.exec(text)
  if (!match) {
    const numeric = Number(text)
    if (!Number.isFinite(numeric)) return text
    return new Intl.NumberFormat('ru-RU', { maximumFractionDigits: 20 }).format(numeric === 0 ? 0 : numeric)
  }
  const integer = BigInt(match[2])
  const fraction = (match[3] ?? '').replace(/0+$/, '')
  const sign = integer === 0n && fraction === '' ? '' : match[1]
  return `${sign}${capacityGroups.format(integer)}${fraction ? `,${fraction}` : ''}`
}
const capacityReasons = (codes: string[] | undefined) => (codes ?? []).map((code) => ({ code, text: verdictReasonText(code) }))

export function capacityView(result: AnalysisResult): CapacityView | null {
  const summary = result.capacity_summary
  if (!summary) return null
  const stages = (summary.stages ?? []).map((stage: CapacityStage): CapacityStageRow => ({
    key: stage.id,
    stage: stage.id,
    target: capacityNumber(stage.target),
    achieved: capacityNumber(stage.achieved),
    observed: `${capacityNumber(stage.observed_min)} / ${capacityNumber(stage.observed_max)}`,
    bins: `${capacityNumber(stage.complete_bins)} / ${capacityNumber(stage.expected_bins)}`,
    verified: capacityNumber(stage.verified_bound_load),
    verdict: stage.verdict,
    verdictText: (CAPACITY_LABELS.stageVerdict as Record<string, string>)[stage.verdict] ?? stage.verdict,
    smallSample: (stage.reasons ?? []).includes('CAPACITY_INSUFFICIENT_SAMPLES'),
    reasons: capacityReasons(stage.reasons),
    evidence: stage.evidence_refs ?? [],
  }))
  const knee = result.evidence.find((item): item is CapacityKneeDiagnosticEvidence => item.type === 'capacity_knee_diagnostic')
  return {
    axis: summary.load_axis,
    unit: summary.unit,
    bound: summary.bound_type,
    boundText: CAPACITY_LABELS.boundText(summary.bound_type, capacityNumber(summary.lower_inclusive), capacityNumber(summary.upper_exclusive), summary.unit),
    verdict: summary.policy_verdict,
    verdictText: (TABLES_LABELS.statusText as Record<string, string>)[summary.policy_verdict] ?? summary.policy_verdict,
    reasons: capacityReasons(summary.reasons),
    kneeText: summary.capacity_knee == null
      ? summary.knee_reason === 'KNEE_DETECTOR_NOT_IMPLEMENTED' ? CAPACITY_LABELS.kneeNotImplemented : CAPACITY_LABELS.kneeNone(summary.knee_reason)
      : CAPACITY_LABELS.kneeValue(capacityNumber(summary.capacity_knee), summary.unit),
    kneeDiagnosticText: knee == null
      ? undefined
      : knee.status === 'DETECTED'
        ? CAPACITY_LABELS.kneeDiagnosticDetected(capacityNumber(knee.last_stable_load), capacityNumber(knee.first_degraded_load), summary.unit, knee.last_stable_stage_id ?? '', knee.first_degraded_stage_id ?? '')
        : CAPACITY_LABELS.kneeDiagnosticNone(knee.reasons ?? []),
    smallSample: stages.some((stage) => stage.smallSample) || (summary.reasons ?? []).includes('CAPACITY_INSUFFICIENT_SAMPLES'),
    stages,
  }
}

export interface TrendRow {
  key: string
  check: string
  series: string
  window: string
  declared: string
  status: string
  statusText: string
  observed: string
  slope: string
  shift: string
  median: string
  required: string
  cells: string
  reasons: Array<{ code: string; text: string }>
}

export interface TrendView { summaryText: string; rows: TrendRow[] }
export type TrendSortKey = 'default' | 'check' | 'series' | 'window' | 'status' | 'slope' | 'shift' | 'median'

const TREND_STATUS_RANK: Record<string, number> = {
  TREND_OBSERVED: 0,
  NO_MATERIAL_TREND: 1,
  INSUFFICIENT_CELLS: 2,
  UNAVAILABLE: 3,
}

const trendSortValues = new WeakMap<TrendRow, { slope: number; shift: number; median: number }>()

function decimalSort(value: string | null | undefined): number {
  if (value == null || value.trim() === '') return Number.NEGATIVE_INFINITY
  const parsed = Number(value)
  return Number.isFinite(parsed) ? parsed : Number.NEGATIVE_INFINITY
}

function compareNumbers(left: number, right: number): number {
  if (left === right) return 0
  return left < right ? -1 : 1
}

function parseTrendNumber(display: string): number {
  const trimmed = display.trim()
  if (!trimmed || trimmed === TREND_LABELS.noData) return Number.NEGATIVE_INFINITY
  const match = /^([+-]?\d+(?:[.,]\d+)?(?:[eE][+-]?\d+)?)(?:\s|$)/.exec(trimmed)
  if (!match) return Number.NEGATIVE_INFINITY
  const parsed = Number(match[1].replace(',', '.'))
  return Number.isFinite(parsed) ? parsed : Number.NEGATIVE_INFINITY
}

export function sortTrendRows(rows: readonly TrendRow[], sort: TrendSortKey, dir: 1 | -1): TrendRow[] {
  if (sort === 'default') return [...rows]
  return rows.map((row, index) => ({ row, index })).sort((left, right) => {
    let order = 0
    if (sort === 'status') order = (TREND_STATUS_RANK[left.row.status] ?? 9) - (TREND_STATUS_RANK[right.row.status] ?? 9)
    else if (sort === 'slope' || sort === 'shift' || sort === 'median') {
      const leftVal = trendSortValues.get(left.row)?.[sort] ?? parseTrendNumber(left.row[sort])
      const rightVal = trendSortValues.get(right.row)?.[sort] ?? parseTrendNumber(right.row[sort])
      order = compareNumbers(leftVal, rightVal)
    } else order = compareExact(left.row[sort], right.row[sort])
    return order * dir || left.index - right.index
  }).map((item) => item.row)
}

function trendMeasure(value: string | null | undefined, unit: string | null, slope = false): string {
  const trimmed = value?.trim()
  return !trimmed ? TREND_LABELS.noData : `${trimmed.replace('.', ',')}${unit == null ? '' : ` ${unit}${slope ? '/\u0441' : ''}`}`
}

export function trendView(result: AnalysisResult): TrendView | null {
  const summary = result.evidence.find((item): item is TrendSummaryEvidence => item.type === 'trend_summary')
  const checks = result.evidence.filter((item): item is TrendCheckEvidence => item.type === 'trend_check')
  if (!summary && !checks.length) return null
  const count = (status: TrendCheckEvidence['status']) => checks.filter((check) => check.status === status).length
  return {
    summaryText: TREND_LABELS.summary(
      summary?.checks_total ?? checks.length,
      summary?.observed ?? count('TREND_OBSERVED'),
      summary?.not_material ?? count('NO_MATERIAL_TREND'),
      summary?.insufficient ?? count('INSUFFICIENT_CELLS'),
      summary?.unavailable ?? count('UNAVAILABLE'),
    ),
    rows: checks.map((check) => {
      const row: TrendRow = {
        key: check.id,
        check: check.check_id,
        series: check.series_id,
        window: check.window_id,
        declared: (TREND_LABELS.declaredText as Record<string, string>)[check.declared_direction] ?? check.declared_direction,
        status: check.status,
        statusText: (TREND_LABELS.statusText as Record<string, string>)[check.status] ?? check.status,
        observed: check.observed_direction == null ? TREND_LABELS.noData : (TREND_LABELS.observedText as Record<string, string>)[check.observed_direction] ?? check.observed_direction,
        slope: trendMeasure(check.slope_per_second, check.unit, true),
        shift: trendMeasure(check.split_half_shift, check.unit),
        median: trendMeasure(check.median, check.unit),
        required: trendMeasure(check.magnitude_gate?.required_split_half_shift_units, check.unit),
        cells: `${capacityNumber(check.observed_cells)} / ${capacityNumber(check.expected_cells)}`,
        reasons: (check.reasons ?? []).map((code) => ({ code, text: TREND_LABELS.reasonWords[code] ?? verdictReasonText(code) })),
      }
      trendSortValues.set(row, {
        slope: decimalSort(check.slope_per_second),
        shift: decimalSort(check.split_half_shift),
        median: decimalSort(check.median),
      })
      return row
    }),
  }
}

export type ResourceSummarySortKey =
  | 'default' | 'series_id' | 'metric' | 'entity' | 'window_id'
  | 'observed_cells' | 'missing_cells' | 'min' | 'max' | 'mean' | 'median' | 'q95' | 'slope_per_second'

export type ResourceCheckSortKey = 'default' | 'window_id' | 'rule_id' | 'service' | 'series_id' | 'status'

function numOrNegInf(value: unknown): number {
  if (typeof value === 'number') return Number.isFinite(value) ? value : Number.NEGATIVE_INFINITY
  if (typeof value === 'string') {
    if (value.trim() === '') return Number.NEGATIVE_INFINITY
    const parsed = Number(value)
    return Number.isFinite(parsed) ? parsed : Number.NEGATIVE_INFINITY
  }
  return Number.NEGATIVE_INFINITY
}

export function sortResourceSummaries<T extends Record<string, unknown>>(
  rows: readonly T[],
  sort: ResourceSummarySortKey,
  dir: 1 | -1,
): T[] {
  if (sort === 'default') return [...rows]
  return rows.map((row, index) => ({ row, index })).sort((left, right) => {
    let order = 0
    if (sort === 'observed_cells' || sort === 'missing_cells') {
      order = compareNumbers(numOrNegInf(left.row[sort]), numOrNegInf(right.row[sort]))
    } else if (sort === 'min' || sort === 'max' || sort === 'mean' || sort === 'median' || sort === 'q95' || sort === 'slope_per_second') {
      const leftStats = left.row.statistics as Record<string, unknown> | undefined
      const rightStats = right.row.statistics as Record<string, unknown> | undefined
      order = compareNumbers(numOrNegInf(leftStats?.[sort]), numOrNegInf(rightStats?.[sort]))
    } else {
      order = compareExact(String(left.row[sort] ?? ''), String(right.row[sort] ?? ''))
    }
    return order * dir || left.index - right.index
  }).map((item) => item.row)
}

const CHECK_STATUS_RANK: Record<string, number> = { FAIL: 0, NO_VERDICT: 1, PASS: 2, NO_POLICY: 3, NOT_CHECKED: 4 }

export function sortResourceChecks<T extends Record<string, unknown>>(
  rows: readonly T[],
  sort: ResourceCheckSortKey,
  dir: 1 | -1,
): T[] {
  if (sort === 'default') return [...rows]
  return rows.map((row, index) => ({ row, index })).sort((left, right) => {
    const order = sort === 'status'
      ? (CHECK_STATUS_RANK[String(left.row.status ?? '')] ?? 9) - (CHECK_STATUS_RANK[String(right.row.status ?? '')] ?? 9)
      : compareExact(String(left.row[sort] ?? ''), String(right.row[sort] ?? ''))
    return order * dir || left.index - right.index
  }).map((item) => item.row)
}

export function tablesTargetSubTab(result: AnalysisResult | null, targetId: string): TablesSubTabKey {
  if (targetId === 'summary-metrics' || targetId === 'policy-results') return 'rules'
  if (targetId === 'transaction-metrics') return 'transactions'
  if (targetId === 'capacity-results') return 'capacity'
  if (targetId === 'resource-results') return 'resources'
  if (targetId === 'trend-results') return 'trends'
  if (targetId === 'diagnostic-results' || targetId === 'source-acquisition') return 'diagnostics'
  if (targetId === 'normalized-data') return 'normalized'
  if (targetId.startsWith('ev-') && result) {
    const evidenceId = targetId.slice(3)
    const match = result.evidence.find((item) => 'id' in item && item.id === evidenceId)
    if (match?.type === 'metric_summary' && match.scope.kind === 'transaction') return 'transactions'
    if (match?.type === 'resource_policy_check') return 'resources'
    return 'rules'
  }
  return 'all'
}
