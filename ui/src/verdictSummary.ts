// Тексты и порядок карточки вердикта. Чистые функции без Vue: результат анализа
// на входе, готовые строки на выходе. Данные берутся только из полей
// analysis-result.v1, контракт бэкенда не меняется.
import type {
  AnalysisResult,
  ExactRatio,
  MetricSummaryEvidence,
  PolicyCheckEvidence,
  ResourcePolicyCheckEvidence,
  RuleWindowCheckEvidence,
  StageBindingEvidence,
  WindowPolicySummaryEvidence,
} from './types'
import { isNoVerdictReason, reasonText, SAMPLE_TEXT } from './verdictReasons'

export type Verdict = AnalysisResult['policy_verdict']

export interface RuleLine {
  key: string
  title: string
  detail: string
}

export interface FailedLine extends RuleLine {
  source: 'business' | 'resource'
}

export interface CauseGroup {
  code: string | null
  text: string
  detail: string | null
  subjectsLabel: string
  subjects: string[]
  subjectsHidden: number
}

// Таблица стадий для анализа со стадиями (ADR 0030): строки в порядке объявления.
export interface StageTable {
  title: string
  heads: string[]
  rows: string[][]
  totals: string
  note: string
}

export interface VerdictSummary {
  verdict: Verdict
  headline: string
  lead: string
  chip: string
  linesTitle: string | null
  lines: RuleLine[]
  linesHidden: number
  causes: CauseGroup[]
  notesTitle: string | null
  notes: CauseGroup[]
  facts: Array<{ label: string; value: string }>
  stages: StageTable | null
}

// Фраза генерируется при отрисовке из stage_binding и в результате не хранится (R10); те же слова в HTML-отчёте (report/StageNotice.kt).
export const STAGE_DECIDED_PHRASE = 'Вердикт посчитан по окну steady, разгон исключён'
export const STAGE_MARKER = 'по окну steady, разгон исключён'
export const STAGE_REFERENCE_NOTE = 'Метрики по всему прогону справочные: они включают разгон и остановку и не определяют вердикт.'

export const MAX_LINES = 3
export const MAX_SUBJECTS = 5

export const METRIC_LABELS: Record<string, string> = {
  response_time_p95_ms: 'p95 отклика',
  response_time_p99_ms: 'p99 отклика',
  error_rate_ratio: 'доля ошибок',
  throughput_rps: 'пропускная способность',
}

// W2.4: a throughput rule on a window is the rate of the window, not of the whole run (ramp-up and idle time are not in it).
export function metricLabel(metric: string, windowId?: string | null): string {
  const label = METRIC_LABELS[metric] ?? metric
  return metric === 'throughput_rps' && windowId ? `${label} (throughput на окне)` : label
}

const numbers = new Intl.NumberFormat('ru-RU', { maximumFractionDigits: 2 })

interface Violation {
  rule_id: string
  window_id: string
  entity: string
  from_epoch_ms: number
  to_epoch_ms: number
  cell_count: number
  observed_min: string
  observed_max: string
  presumed?: boolean
}

function isViolation(finding: Record<string, unknown>): finding is Record<string, unknown> & Violation {
  return finding.type === 'resource_threshold_violation' && typeof finding.rule_id === 'string' && typeof finding.window_id === 'string'
}

function ratioValue(value: number | ExactRatio | undefined): number | null {
  if (typeof value === 'number') return value
  if (value && value.denominator !== 0) return value.numerator / value.denominator
  return null
}

export function valueText(metric: string, value: number | ExactRatio | undefined, digits = 2): string {
  const number = ratioValue(value)
  if (number === null) return 'нет данных'
  const format = (amount: number) => new Intl.NumberFormat('ru-RU', { maximumFractionDigits: digits }).format(amount)
  if (metric === 'error_rate_ratio') return `${format(number * 100)} %`
  if (metric === 'throughput_rps') return `${format(number)} RPS`
  return `${format(number)} мс`
}

export function scopeLabel(scope: MetricSummaryEvidence['scope'] | undefined): string {
  if (!scope) return 'область не указана'
  if (scope.kind === 'overall') return 'весь прогон'
  return [...(scope.group_path ?? []), scope.label].join(' / ')
}

function formatUtc(milliseconds: number): string {
  return `${new Date(milliseconds).toISOString().replace('T', ' ').slice(0, 19)} UTC`
}

function formatDuration(milliseconds: number): string {
  return milliseconds < 60_000 ? `${numbers.format(milliseconds / 1_000)} с` : `${numbers.format(milliseconds / 60_000)} мин`
}

// W2.4: the part of the run outside the verdict window, from numbers the result already carries (the same rule as windowShareText of
// report/StageNotice.kt): stage_binding, or resource_binding with the window_policy_summary of every window. Whole tenths of a percent,
// rounded half up in integers, so the text equals the one of the reports.
export const WINDOW_SHARE_LABEL = 'Вне окна вердикта (разгон, остановка, простои и прочее)'

function windowShare(result: AnalysisResult): string | null {
  let run: number
  let excluded: number
  const stage = stageBindingOf(result)
  if (stage) {
    excluded = stage.excluded_millis
    run = stage.evaluated_millis + excluded
  } else {
    const binding = result.evidence.find((item) => item.type === 'resource_binding')
    const windows = result.evidence.filter((item): item is WindowPolicySummaryEvidence => item.type === 'window_policy_summary')
    if (!binding || windows.length === 0) return null
    const from = Number(binding.run_from_epoch_ms)
    const to = Number(binding.run_to_epoch_ms)
    run = to - from
    excluded = run - windows.reduce((sum, window) => sum + (window.to_epoch_ms - window.from_epoch_ms), 0)
  }
  if (!Number.isFinite(run) || !Number.isFinite(excluded) || run <= 0 || excluded < 0 || excluded > run) return null
  const tenths = Math.floor((excluded * 2000 + run) / (2 * run))
  const percent = tenths === 0 && excluded > 0 ? 'меньше 0,1 %' : `${Math.floor(tenths / 10)},${tenths % 10} %`
  return `${percent} прогона (${formatDuration(excluded)} из ${formatDuration(run)})`
}

function stageBindingOf(result: AnalysisResult): StageBindingEvidence | undefined {
  return result.evidence.find((item): item is StageBindingEvidence => item.type === 'stage_binding')
}

function stageNotice(result: AnalysisResult, binding: StageBindingEvidence) {
  const verdict = result.policy_verdict
  const decided = result.run_validity === 'VALID' && (verdict === 'PASS' || verdict === 'FAIL')
  const steady = binding.stages.filter((stage) => stage.role === 'steady')
  const windows = steady
    .map((stage) => `${stage.id}, ${formatDuration(stage.to_epoch_ms - stage.from_epoch_ms)}, ${formatUtc(stage.from_epoch_ms)} – ${formatUtc(stage.to_epoch_ms)}`)
    .join('; ')
  const ids = binding.evaluated_window_ids.join(', ')
  return {
    decided,
    phrase: decided ? STAGE_DECIDED_PHRASE : `Окно steady задано (${ids}), разгон исключён из метрик окна; вердикт: ${verdict}`,
    detail: `Окно вердикта: ${windows}. Исключено: ${formatDuration(binding.excluded_millis)}.`,
    windows,
    excluded: formatDuration(binding.excluded_millis),
  }
}

function stageTable(binding: StageBindingEvidence): StageTable {
  return {
    title: 'Область вердикта',
    heads: ['Стадия', 'Роль', 'Смещения, мс', 'Границы (UTC)', 'Границы (epoch, мс)', 'Обрезана до конца прогона'],
    rows: binding.stages.map((stage) => [
      stage.id,
      stage.role,
      `${stage.from_offset_ms} – ${stage.to_offset_ms}`,
      `${formatUtc(stage.from_epoch_ms)} – ${formatUtc(stage.to_epoch_ms)}`,
      `${stage.from_epoch_ms} – ${stage.to_epoch_ms}`,
      stage.clipped_to_run_end ? 'да' : '—',
    ]),
    totals: `Оценено: ${formatDuration(binding.evaluated_millis)}, исключено: ${formatDuration(binding.excluded_millis)}.`,
    note: STAGE_REFERENCE_NOTE,
  }
}

function businessLine(check: PolicyCheckEvidence, metrics: Map<string, MetricSummaryEvidence>): RuleLine {
  const scope = check.scope ?? (check.metric_evidence_id ? metrics.get(check.metric_evidence_id)?.scope : undefined)
  const sign = check.operator === 'lte' ? '≤' : '≥'
  const window = check.window_id ? ` · окно ${check.window_id}` : ''
  // Округление не должно превращать нарушение в «равенство»: добавляем знаки, пока строки не разойдутся.
  let digits = 2
  let observed = valueText(check.metric, check.observed, digits)
  let threshold = valueText(check.metric, check.threshold, digits)
  while (observed === threshold && digits < 20) {
    digits = Math.min(digits * 2, 20)
    observed = valueText(check.metric, check.observed, digits)
    threshold = valueText(check.metric, check.threshold, digits)
  }
  // Если и 20 знаков не различают значения (для FAIL), прямо говорим, что порог нарушен.
  const tie = check.status === 'FAIL' && observed === threshold ? ' (нарушение меньше точности отображения)' : ''
  const line = {
    key: check.id,
    title: `Правило ${check.rule_id} · ${metricLabel(check.metric, check.window_id)} · ${scopeLabel(scope)}${window}`,
    detail: `${observed} при пороге ${sign} ${threshold}${tie}`,
  }
  if (check.sample_mode === 'SMALL_SAMPLE' && typeof check.sample_count === 'number' && typeof check.min_samples === 'number') {
    line.detail += SAMPLE_TEXT.detail(check.sample_count, check.min_samples)
  }
  return line
}

// Оператор ресурсного правила описывает нарушение (gt: значение выше порога), а не условие прохождения.
function resourceLine(check: ResourcePolicyCheckEvidence, violations: Violation[]): RuleLine {
  const side = check.operator === 'gt' ? 'выше' : 'ниже'
  const first = violations[0]
  const service = check.service ? ` · сервис ${check.service}` : ''
  const title = `Правило ${check.rule_id}${service} · ряд ${check.series_id}${first ? ` (${first.entity})` : ''} · окно ${check.window_id}`
  // Доля покрытия нужна только при пропусках; проверки без счётчиков ячеек (правила снимка) остаются как были.
  const coverage = check.expected_cells && check.observed_cells !== undefined && check.observed_cells < check.expected_cells
    ? ` · покрытие данных: ${numbers.format((check.observed_cells / check.expected_cells) * 100)} % (${check.observed_cells} из ${check.expected_cells} ячеек)`
    : ''
  if (check.status === 'PASS' || !first) {
    return { key: check.id, title, detail: `нарушений нет (нарушение: значение ${side} ${check.threshold} ${check.unit})${coverage}` }
  }
  const range = first.observed_min === first.observed_max ? first.observed_min : `${first.observed_min}–${first.observed_max}`
  const episodes = violations.length > 1 ? `; интервалов нарушения: ${violations.length}` : ''
  const presumed = violations.some((item) => item.presumed) ? ' · предположительное нарушение (серия достигнута только за счёт пропущенных ячеек)' : ''
  return {
    key: check.id,
    title,
    detail: `значение ${side} порога ${check.threshold} ${check.unit}: наблюдалось ${range}; ячеек подряд: ${first.cell_count}; ${formatUtc(first.from_epoch_ms)} – ${formatUtc(first.to_epoch_ms)}${episodes}${presumed}${coverage}`,
  }
}

function group(items: Array<{ code: string | null; detail?: string | null; label?: string; subject?: string }>): CauseGroup[] {
  const groups = new Map<string, CauseGroup>()
  const subjects = new Map<string, string[]>()
  for (const item of items) {
    const label = item.label ?? ''
    const id = `${item.code ?? ''}|${label}`
    if (!groups.has(id)) {
      groups.set(id, {
        code: item.code,
        text: item.code ? reasonText(item.code) : 'Ядро не указало причину для этой проверки.',
        detail: item.detail ?? null,
        subjectsLabel: label,
        subjects: [],
        subjectsHidden: 0,
      })
    }
    const list = subjects.get(id) ?? []
    if (item.subject && !list.includes(item.subject)) list.push(item.subject)
    subjects.set(id, list)
  }
  return [...groups].map(([id, entry]) => {
    const list = subjects.get(id) ?? []
    return { ...entry, subjects: list.slice(0, MAX_SUBJECTS), subjectsHidden: Math.max(0, list.length - MAX_SUBJECTS) }
  })
}

function capacityCauses(result: AnalysisResult): CauseGroup[] {
  const summary = result.capacity_summary
  if (!summary) return []
  const items: Array<{ code: string; label?: string; subject?: string }> = summary.reasons.map((code) => ({ code }))
  for (const stage of summary.stages) for (const code of stage.reasons) items.push({ code, label: 'Ступени', subject: stage.id })
  return group(items)
}

function checksOf(result: AnalysisResult) {
  const business = result.evidence.filter((item): item is PolicyCheckEvidence => item.type === 'policy_check')
  const resource = result.evidence.filter((item): item is ResourcePolicyCheckEvidence => item.type === 'resource_policy_check' && item.effect === 'sla')
  return { business, resource }
}

function causesOf(result: AnalysisResult): CauseGroup[] {
  const items: Array<{ code: string | null; detail?: string | null; label?: string; subject?: string }> = []
  if (result.run_validity !== 'VALID') {
    for (const item of result.evidence) {
      if (item.type !== 'diagnostic') continue
      items.push({ code: item.code, detail: item.source_offset === undefined ? null : `Позиция в файле: ${item.source_offset} байт` })
    }
  }
  if (result.analysis_mode === 'capacity_step') return [...group(items), ...capacityCauses(result)]
  const { business, resource } = checksOf(result)
  for (const check of business) {
    if (check.status !== 'NO_VERDICT') continue
    const subject = check.reason_code === 'INSUFFICIENT_SAMPLES' && typeof check.sample_count === 'number' && typeof check.sample_floor === 'number'
      ? SAMPLE_TEXT.insufficientSubject(check.rule_id, check.window_id, check.sample_count, check.sample_floor)
      : check.window_id ? `${check.rule_id} (окно ${check.window_id})` : check.rule_id
    items.push({ code: check.reason_code ?? null, label: 'Правила', subject })
  }
  for (const check of resource) {
    if (check.status === 'NO_VERDICT') items.push({ code: check.reason, label: 'Правила', subject: `${check.rule_id} (окно ${check.window_id})` })
  }
  for (const check of result.evidence.filter((item): item is RuleWindowCheckEvidence => item.type === 'rule_window_check')) {
    items.push({ code: check.reason_code, label: 'Правила', subject: `${check.rule_id} (окно ${check.window_id})` })
  }
  if (result.analysis_coverage.reasons.includes('BUSINESS_OBSERVATIONS_NOT_FOUND')) {
    // Пустым окно называем только по правилу на весь прогон: у транзакции нулевой счёт не значит пустое окно.
    const empty = business.filter((check) => check.reason_code === 'METRIC_NOT_AVAILABLE' && check.window_id && check.scope?.kind === 'overall')
    if (empty.length) for (const check of empty) items.push({ code: 'BUSINESS_OBSERVATIONS_NOT_FOUND', label: 'Окна', subject: check.window_id })
    else items.push({ code: 'BUSINESS_OBSERVATIONS_NOT_FOUND' })
  }
  return group(items)
}

function overallMetrics(result: AnalysisResult): MetricSummaryEvidence | undefined {
  return result.evidence.find(
    (item): item is MetricSummaryEvidence => item.type === 'metric_summary' && item.scope.kind === 'overall',
  )
}

function violationsOf(violations: Violation[], check: ResourcePolicyCheckEvidence): Violation[] {
  return violations.filter((item) => item.rule_id === check.rule_id && item.window_id === check.window_id)
}

export function diagnosticFailedLinesOf(result: AnalysisResult): Array<RuleLine & { source: 'resource' }> {
  const violations = result.findings.filter(isViolation)
  return result.evidence
    .filter((item): item is ResourcePolicyCheckEvidence => item.type === 'resource_policy_check' && item.effect === 'diagnostic' && item.status === 'FAIL')
    .map((check) => ({ ...resourceLine(check, violationsOf(violations, check)), source: 'resource' as const }))
}

export function failedLinesOf(result: AnalysisResult): FailedLine[] {
  const { business, resource } = checksOf(result)
  const metrics = new Map(result.evidence.filter((item): item is MetricSummaryEvidence => item.type === 'metric_summary').map((item) => [item.id, item]))
  const violations = result.findings.filter(isViolation)
  return [
    ...business.filter((check) => check.status === 'FAIL').map((check) => ({ ...businessLine(check, metrics), source: 'business' as const })),
    ...resource
      .filter((check) => check.status === 'FAIL' || (check.status === 'NO_VERDICT' && violationsOf(violations, check).length > 0))
      .map((check) => ({ ...resourceLine(check, violationsOf(violations, check)), source: 'resource' as const })),
  ]
}

export function summarizeVerdict(result: AnalysisResult, context: { policySha256?: string; policyId?: string | null; tabs?: boolean } = {}): VerdictSummary {
  const verdict = result.policy_verdict
  const capacity = result.analysis_mode === 'capacity_step' ? result.capacity_summary : undefined
  const { business, resource } = checksOf(result)
  const metrics = new Map(result.evidence.filter((item): item is MetricSummaryEvidence => item.type === 'metric_summary').map((item) => [item.id, item]))
  const failedLines = failedLinesOf(result).map(({ key, title, detail }) => ({ key, title, detail }))
  const passedLines = [
    ...business.filter((check) => check.status === 'PASS').map((check) => businessLine(check, metrics)),
    ...resource.filter((check) => check.status === 'PASS').map((check) => resourceLine(check, [])),
  ]
  const total = business.length + resource.length
  const failed = failedLines.length
  const unresolved = business.filter((check) => check.status === 'NO_VERDICT').length + resource.filter((check) => check.status === 'NO_VERDICT').length
  // With a decided PASS/FAIL, an unresolved rule is a warned skip (missing_transaction=warn): it is not an evaluated check.
  const decided = verdict === 'PASS' || verdict === 'FAIL'
  const evaluated = decided ? total - unresolved : total
  const skipped = decided && unresolved > 0 ? `, не вычислено: ${unresolved}` : ''
  const small = business.filter((check) => check.sample_mode === 'SMALL_SAMPLE').length

  let causes = verdict === 'NO_VERDICT' ? causesOf(result) : []
  if (verdict === 'NO_VERDICT' && causes.length === 0) {
    const known = result.analysis_coverage.reasons.filter(isNoVerdictReason)
    causes = capacity && !known.length
      ? [{
          code: null,
          text: `Граница ёмкости ${capacity.bound_type} [${capacity.lower_inclusive ?? '—'}, ${capacity.upper_exclusive ?? '—'}) ${capacity.unit} не позволяет сравнить её с требуемой ёмкостью.`,
          detail: null,
          subjectsLabel: '',
          subjects: [],
          subjectsHidden: 0,
        }]
      : group(known.length ? known.map((code) => ({ code })) : [{ code: null }])
  }
  const causeCodes = new Set(causes.map((cause) => cause.code))
  const notes = result.analysis_coverage.status === 'INCOMPLETE'
    ? group(result.analysis_coverage.reasons.filter((code) => !causeCodes.has(code)).map((code) => ({ code })))
    : []

  const shownLines = verdict === 'PASS' ? passedLines : failedLines
  const invalid = result.run_validity === 'INVALID'
  const degraded = result.run_validity === 'DEGRADED'
  let headline: string
  let lead: string
  let chip: string
  if (capacity) {
    const bound = `${capacity.bound_type} [${capacity.lower_inclusive ?? '—'}, ${capacity.upper_exclusive ?? '—'}) ${capacity.unit}`
    headline = {
      PASS: 'Ёмкость подтверждена — требуемая нагрузка выдержана',
      FAIL: 'Ёмкость недостаточна — верхняя граница не выше требуемой',
      NO_VERDICT: 'Вердикт по ёмкости не выдан — границы недостаточно',
      NO_POLICY: 'Вердикта нет — не задана требуемая ёмкость или SLA-правила',
    }[verdict]
    lead = `Граница ёмкости: ${bound}. Подробности по ступеням — ${context.tabs ? 'в таблице на вкладке «Таблицы»' : 'в таблице ниже'}.`
    chip = 'оценка ёмкости'
  } else if (verdict === 'FAIL') {
    headline = `Прогон не проходит — нарушено проверок: ${failed} из ${evaluated}${skipped}`
    lead = 'Измеренные значения вышли за пороги правил. Первые нарушения показаны ниже, остальные — в таблицах правил.'
    chip = `нарушено ${failed} из ${evaluated}`
    if (small > 0) {
      lead += SAMPLE_TEXT.lead(small)
      chip += SAMPLE_TEXT.chipSuffix
    }
  } else if (verdict === 'PASS') {
    headline = `Прогон проходит — нарушений нет, проверок: ${evaluated}${skipped}`
    lead = 'Ни одно правило не нарушено. Ниже первые проверки с порогом; полный список — в таблицах правил.'
    chip = 'нарушений нет'
    if (small > 0) {
      lead += SAMPLE_TEXT.lead(small)
      chip += SAMPLE_TEXT.chipSuffix
    }
  } else if (verdict === 'NO_POLICY') {
    headline = 'Вердикта нет — политика не задана'
    lead = 'Метрики посчитаны, но пороги не проверялись: не заданы ни политика, ни SLA-правила ресурсов. Добавьте их в форме анализа и запустите анализ заново.'
    chip = 'политика не задана'
  } else {
    headline = invalid
      ? 'Вердикт не выдан — файл нагрузки не удалось разобрать'
      : degraded
        ? 'Вердикт не выдан — файл нагрузки разобран не полностью'
        : unresolved > 0
          ? `Вердикт не выдан — не удалось проверить: ${unresolved} из ${total}`
          : 'Вердикт не выдан — ядро не смогло проверить все правила'
    lead = failed > 0
      ? 'Это не PASS и не FAIL. Нарушения уже найдены (ниже), но вердикт по ним не выдаётся, пока не проверены остальные правила.'
      : 'Это не PASS и не FAIL: пороги проверены не полностью. Причины ниже.'
    chip = invalid ? 'файл не разобран' : degraded ? 'файл разобран не полностью' : unresolved > 0 ? `не проверено: ${unresolved}` : 'причины ниже'
  }

  const binding = stageBindingOf(result)
  const notice = binding ? stageNotice(result, binding) : null
  if (notice && !capacity) {
    // Заголовок меняется только у вынесенного вердикта; иначе маркер стоит в lead и не утверждает вердикт.
    if (notice.decided) headline += ` · ${STAGE_MARKER}`
    lead += ` ${notice.phrase}. ${notice.detail}`
  }

  const share = windowShare(result)
  const overall = overallMetrics(result)
  const denominator = overall?.throughput_rps.denominator
  const errorRate = overall ? ratioValue(overall.error_rate_ratio ?? undefined) : null
  const policyFact = context.policySha256
    ? [{ label: 'Политика (хэш)', value: context.policySha256 === 'NO_POLICY' ? 'не задана' : context.policySha256.slice(0, 12) }]
    : []
  return {
    verdict,
    headline,
    lead,
    chip,
    linesTitle: capacity || shownLines.length === 0 ? null : verdict === 'FAIL' ? 'Что нарушено' : verdict === 'NO_VERDICT' && failed > 0 ? 'Найденные нарушения' : verdict === 'PASS' ? 'Что проверено' : null,
    lines: capacity ? [] : shownLines.slice(0, MAX_LINES),
    linesHidden: capacity ? 0 : Math.max(0, shownLines.length - MAX_LINES),
    causes,
    notesTitle: notes.length ? (verdict === 'NO_VERDICT' ? 'Ещё по покрытию данных' : 'Покрытие данных неполное. Это не отменяет вердикт, но часть контекста могла не попасть в анализ') : null,
    notes,
    facts: [
      { label: 'Валидность прогона', value: result.run_validity },
      { label: 'Полнота данных', value: result.analysis_coverage.status },
      { label: 'Длительность', value: denominator ? formatDuration(denominator) : '—' },
      { label: 'Запросов', value: overall ? numbers.format(overall.sample_count) : '—' },
      { label: 'Доля ошибок', value: errorRate === null ? '—' : `${numbers.format(errorRate * 100)} %` },
      { label: 'Проверок', value: `${evaluated}${skipped}` },
      ...(notice ? [{ label: 'Окно вердикта', value: notice.windows }, { label: 'Исключено', value: notice.excluded }] : []),
      ...(share ? [{ label: WINDOW_SHARE_LABEL, value: share }] : []),
      ...(context.policyId ? [{ label: 'Политика (id)', value: context.policyId }] : []),
      ...policyFact,
    ],
    stages: binding ? stageTable(binding) : null,
  }
}
