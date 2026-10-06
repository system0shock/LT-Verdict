import type { AnalysisResult, CapacityStage, ExactRatio, MetricSummaryEvidence, PolicyCheckEvidence, TrendCheckEvidence, TrendSummaryEvidence } from '../types'
import { METRIC_LABELS, scopeLabel, valueText } from '../verdictSummary'
import { reasonText as verdictReasonText } from '../verdictReasons'
import { CAPACITY_LABELS, TABLES_LABELS, TREND_LABELS } from './labels.tables'

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
  return result.evidence
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
        metric: METRIC_LABELS[check.metric] ?? check.metric,
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
  sort: { samples: number; errors: number; errorRate: number; p95: number; p99: number; rps: number }
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
          p95: finite(p95),
          p99: finite(p99),
          rps: finite(ratioNumber(item.throughput_rps)),
        },
      }
    })
}

export type TxSortKey = 'impact' | 'label' | 'samples' | 'errors' | 'errorRate' | 'p95' | 'p99' | 'rps'
export interface TxQuery { text: string; status: 'all' | TxStatus; sort: TxSortKey; dir: 1 | -1 }
export const DEFAULT_TX_QUERY: TxQuery = { text: '', status: 'all', sort: 'impact', dir: 1 }

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
      const order = query.sort === 'label'
        ? compareExact(`${left.path}/${left.label}`, `${right.path}/${right.label}`)
        : left.sort[query.sort] - right.sort[query.sort]
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
  smallSample: boolean
  stages: CapacityStageRow[]
}

const capacityGroups = new Intl.NumberFormat('ru-RU')
// Capacity values are exact decimals: the digits are kept as the server sent them (no rounding), only grouped and with a decimal comma.
export function capacityNumber(value: number | string | null | undefined): string {
  if (value == null) return TABLES_LABELS.noData
  const text = String(value)
  const match = /^(-?)(\d+)(?:\.(\d+))?$/.exec(text)
  if (!match) return Number.isFinite(Number(value)) ? new Intl.NumberFormat('ru-RU', { maximumFractionDigits: 20 }).format(Number(value)) : text
  const fraction = (match[3] ?? '').replace(/0+$/, '')
  return `${match[1]}${capacityGroups.format(BigInt(match[2]))}${fraction ? `,${fraction}` : ''}`
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

function trendMeasure(value: string | null | undefined, unit: string | null, slope = false): string {
  return value == null ? TREND_LABELS.noData : `${value.replace('.', ',')}${unit == null ? '' : ` ${unit}${slope ? '/\u0441' : ''}`}`
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
    rows: checks.map((check) => ({
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
    })),
  }
}
