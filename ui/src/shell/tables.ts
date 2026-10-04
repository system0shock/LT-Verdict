import type { AnalysisResult, ExactRatio, MetricSummaryEvidence, PolicyCheckEvidence } from '../types'
import { METRIC_LABELS, scopeLabel, valueText } from '../verdictSummary'
import { reasonText as verdictReasonText } from '../verdictReasons'
import { TABLES_LABELS } from './labels.tables'

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
