// Строки вкладки «Сравнение» (срез U5a): панель baseline и график сравнения.
// EN_COMPARE_LABELS дословно повторяет прежний английский текст старого интерфейса,
// COMPARE_LABELS подставляется только в новой оболочке. Предупреждения, подсказка пустого окна
// и подсказка старых правил остаются в BASELINE_LABELS (labels.ts) и здесь не дублируются.
// Коды причин и статусов, которых нет в таблицах ниже, выводятся как есть.
import type { BaselineComparison } from '../types'
import { pluralRu } from './labels'

export interface CompareLabels {
  title: string
  intro: string
  seriesLabel: string
  seriesHint: string
  seriesDefault: string
  loading: string
  noBaseline: string
  mode: (mode: string) => string
  runWord: string
  analysisWord: string
  candidatesLine: (algorithm: string, count: number) => string
  scoresSummary: string
  scoreLine: (id: string, score: number) => string
  baselineWindow: string
  currentWindow: string
  minChange: string
  minErrorDelta: string
  windowHint: string
  conditionsLegend: string
  conditionConfirmed: string
  conditionNotConfirmed: string
  conditionUnknown: string
  conditionsHint: string
  conditionLoading: string
  conditionSaved: (decision: string, updatedAt: string) => string
  conditionNone: string
  saveCondition: string
  savingCondition: string
  setBaseline: string
  compare: string
  comparing: string
  clearBaseline: string
  statSummary: string
  statText: string
  addCandidate: string
  remove: string
  removeAria: (filename: string) => string
  sameConditions: string
  candidatesHint: string
  selectStatistically: string
  requestFailed: string
  metricsTitle: string
  statusLine: (comparability: BaselineComparison['comparability']) => string
  deltasNote: string
  deltasRegion: string
  metricHead: string
  baselineHead: string
  currentHead: string
  absoluteHead: string
  relativeHead: string
  roundingNote: string
  // Блок точных значений общих метрик; в старом интерфейсе null: блока нет.
  rawMetricsSummary: string | null
  windowTitle: string
  windowStatus: (status: string) => string
  windowStats: (side: 'baseline' | 'current', samples: number | null, durationMs: number | null) => string
  uncertaintyNote: string
  windowRegion: string
  windowMetricHead: string
  statusHead: string
  rawSummary: string
  overall: string
  metric: (metric: string) => string
  unit: (unit: string) => string
  na: string
  naReason: (reason: string | null) => string
  // Числа сравнения приходят строками с точностью сервера: в русской панели они показаны коротко, точное значение лежит в подсказке ячейки.
  value: (value: string | null, peer?: string | null) => string
  deltaValue: (value: string | null, reason: string | null) => string
  percentValue: (value: string | null, reason: string | null) => string
  exact: (value: string | null, percent?: boolean) => string | undefined
  reasons: (reasons: string[]) => string
  reasonOrDash: (reason: string | null) => string
  chartsSummary: string
  chartsNote: string
  chartsBin: string
  chartsStepUnit: string
  chartsLoad: string
  chartsLoading: string
  chartsUnavailable: string
  chartsTruncated: string
  // Значение атрибута lang для английских фрагментов внутри русской панели (ошибки сервера, внутренности графика).
  // В старом интерфейсе не задано: атрибута нет.
  foreignLang: string | undefined
}

const EN_METRICS: Record<string, string> = {
  response_time_p95_ms: 'P95 latency',
  response_time_p99_ms: 'P99 latency',
  throughput_rps: 'Throughput',
  error_rate_ratio: 'Error rate',
}

export const EN_COMPARE_LABELS: CompareLabels = {
  title: 'Baseline comparison',
  intro: 'A fixed saved analysis, selected manually or statistically. New runs do not replace it.',
  seriesLabel: 'Comparison series',
  seriesHint: 'Name the scenario and test conditions (up to 128 UTF-8 bytes). A name alone does not prove comparability.',
  seriesDefault: 'Selected test series',
  loading: 'Loading baseline…',
  noBaseline: 'No baseline selected. Open a saved analysis to assign one.',
  mode: (mode) => mode,
  runWord: 'Run',
  analysisWord: 'Analysis',
  candidatesLine: (algorithm, count) => `${algorithm} · ${count} candidates`,
  scoresSummary: 'Selection scores',
  scoreLine: (id, score) => `${id}: ${score} (lower is more central)`,
  baselineWindow: 'Baseline window ID',
  currentWindow: 'Current window ID',
  minChange: 'Minimum change (%)',
  minErrorDelta: 'Minimum error-rate delta (ratio)',
  windowHint: 'Optional: enter both window IDs to compare their saved metrics. Leave both empty for overall metrics. '
    + 'Matching names do not establish the same planned load, request mix or test conditions. '
    + 'Materiality thresholds must be greater than zero; an error-rate delta of 0.001 is 0.1 percentage points.',
  conditionsLegend: 'Planned conditions for this exact pair',
  conditionConfirmed: 'Confirmed same planned test conditions',
  conditionNotConfirmed: 'Not confirmed',
  conditionUnknown: 'Unknown',
  conditionsHint: 'This decision is saved only for the displayed baseline/current analyses and, when entered, both window IDs. '
    + 'It changes interpretation, not metric deltas, SLA or the policy verdict.',
  conditionLoading: 'Loading saved condition decision…',
  conditionSaved: (decision, updatedAt) => `Saved ${decision} at ${updatedAt}`,
  conditionNone: 'No saved decision for this exact pair.',
  saveCondition: 'Save condition decision',
  savingCondition: 'Saving condition decision…',
  setBaseline: 'Set as baseline',
  compare: 'Compare selected analysis',
  comparing: 'Comparing…',
  clearBaseline: 'Clear baseline',
  statSummary: 'Statistical selection',
  statText: 'Select 3–20 different runs from the same planned test conditions. '
    + 'The central real run is chosen using P95, throughput and error-rate ranks. '
    + 'This heuristic does not establish a stable norm or statistical significance. Do not add the run you are testing as a candidate.',
  addCandidate: 'Add selected candidate',
  remove: 'Remove',
  removeAria: (filename) => `Remove candidate ${filename}`,
  sameConditions: 'Same planned test conditions',
  candidatesHint: 'Confirm scenario/mix, environment/dataset, load model, targets, pacing and generator limits. '
    + 'Achieved RPS may differ. Invalid or incomplete candidates are rejected, not silently omitted. '
    + 'This confirms the candidate set only; confirm each compared pair in the planned-conditions form.',
  selectStatistically: 'Select statistically',
  requestFailed: 'Baseline request failed.',
  metricsTitle: 'Overall metrics against baseline',
  statusLine: (comparability) => `Planned conditions: ${comparability}`,
  deltasNote: 'Deltas alone do not prove a version regression or change the policy verdict.',
  deltasRegion: 'Baseline metric deltas',
  metricHead: 'Metric / unit',
  baselineHead: 'Baseline',
  currentHead: 'Current',
  absoluteHead: 'Absolute delta',
  relativeHead: 'Relative delta',
  roundingNote: 'Display rounded to 6 decimal places. Error rate uses ratio units: 0.01 = 1%.',
  rawMetricsSummary: null,
  windowTitle: 'Selected-window observations',
  windowStatus: (status) => status,
  windowStats: (side, samples, durationMs) =>
    `${side === 'baseline' ? 'Baseline' : 'Current'}: ${samples ?? 'N/A'} samples / ${durationMs ?? 'N/A'} ms.`,
  uncertaintyNote: 'Uncertainty: NOT_ESTIMATED. These two observations do not establish a reproducible version regression.',
  windowRegion: 'Selected-window metric deltas',
  windowMetricHead: 'Metric / entity / unit',
  statusHead: 'Status / reason',
  rawSummary: 'Raw window comparison evidence',
  overall: 'Overall',
  metric: (metric) => EN_METRICS[metric] ?? metric,
  unit: (unit) => unit,
  na: 'N/A',
  naReason: (reason) => `N/A (${reason})`,
  value: (value) => value ?? 'N/A',
  deltaValue: (value, reason) => value ?? `N/A (${reason})`,
  percentValue: (value, reason) => (value === null ? `N/A (${reason})` : `${value}%`),
  exact: () => undefined,
  reasons: (reasons) => reasons.join(', ') || '—',
  reasonOrDash: (reason) => reason ?? '—',
  chartsSummary: 'Baseline/current charts',
  chartsNote: 'Relative time from each load start; this view does not align stages or prove equal test conditions. '
    + 'OpenSearch events remain in their individual run views.',
  chartsBin: 'Comparison bin width',
  chartsStepUnit: 's',
  chartsLoad: 'Load comparison charts',
  chartsLoading: 'Loading saved buckets…',
  chartsUnavailable: 'Chart comparison unavailable.',
  chartsTruncated: 'Showing the first 500 bins per run. Later bins are omitted; use a wider bin or the individual run view.',
  foreignLang: undefined,
}

const RU_METRICS: Record<string, string> = {
  response_time_p50_ms: 'p50 отклика',
  response_time_p95_ms: 'p95 отклика',
  response_time_p99_ms: 'p99 отклика',
  throughput_rps: 'Пропускная способность',
  error_rate_ratio: 'Доля ошибок',
}
const RU_UNITS: Record<string, string> = { ms: 'мс', rps: 'зпр/с', ratio: 'доля' }
const RU_MODES: Record<string, string> = { manual: 'ручной выбор', statistical: 'статистический выбор' }
const RU_DECISIONS: Record<string, string> = {
  CONFIRMED: 'подтверждено',
  NOT_CONFIRMED: 'не подтверждено',
  UNKNOWN: 'неизвестно',
}
// Статусы окна и его строк: ядро выдаёт эти пять значений, остальное выводится как есть.
const RU_WINDOW_STATUS: Record<string, string> = {
  NOT_EVALUATED: 'не оценивалось',
  DESCRIPTIVE: 'описательно',
  NO_MATERIAL_CHANGE: 'без заметных изменений',
  CANDIDATE: 'существенное изменение (кандидат)',
  INSUFFICIENT_DATA: 'недостаточно данных',
}
// Причины сравнения: код остаётся в скобках, чтобы его можно было сверить с ответом сервера.
const RU_REASONS: Record<string, string> = {
  ZERO_BASELINE: 'нулевой baseline',
  EMPTY_WINDOW: 'пустое окно',
  MISSING_METRIC: 'нет значения метрики',
  CONDITIONS_UNCONFIRMED: 'условия не подтверждены',
  INCOMPATIBLE_METRIC_DEFINITION: 'несовместимое определение метрики',
  BASELINE_WINDOW_NOT_FOUND: 'окно baseline не найдено',
  CURRENT_WINDOW_NOT_FOUND: 'текущее окно не найдено',
  BASELINE_WINDOW_EMPTY: 'окно baseline пусто',
  CURRENT_WINDOW_EMPTY: 'текущее окно пусто',
  INCOMPLETE_METRICS: 'по части метрик данных недостаточно',
  RESOURCE_BINDING_MISSING: 'нет привязки ресурса',
  RESOURCE_BINDING_AMBIGUOUS: 'привязка ресурса неоднозначна',
}
const ruReason = (code: string): string => (RU_REASONS[code] ? `${RU_REASONS[code]} (${code})` : code)

// Числа сравнения приходят строками с точностью сервера (до шести знаков). Целое и число от 1 показаны с двумя знаками после запятой,
// малое число (доля ошибок) с тремя значащими цифрами; строка, не похожая на десятичное число, остаётся как есть.
const RU_DECIMAL = new Intl.NumberFormat('ru-RU', { maximumFractionDigits: 2 })
const RU_SMALL = new Intl.NumberFormat('ru-RU', { maximumSignificantDigits: 3 })
const RU_FULL = new Intl.NumberFormat('ru-RU', { maximumFractionDigits: 6 })
function shortNumber(value: string): string | null {
  if (!/^-?\d+(\.\d+)?$/.test(value)) return null
  const number = Number(value)
  if (!Number.isFinite(number)) return null
  return Math.abs(number) >= 1 || number === 0 ? RU_DECIMAL.format(number) : RU_SMALL.format(number)
}
// Округление не должно делать два разных значения одинаковыми: если пара baseline и текущее совпала бы в показе, берутся все знаки сервера.
function ruNumber(value: string, peer?: string | null): string {
  const short = shortNumber(value)
  if (short === null) return value
  if (peer != null && peer !== value && shortNumber(peer) === short) return RU_FULL.format(Number(value))
  return short
}
// Метка времени сервера ('2026-10-05T21:56:29.572852600Z') показана до секунды в UTC; иная форма остаётся как есть.
function ruTime(value: string): string {
  const match = /^(\d{4}-\d{2}-\d{2})T(\d{2}:\d{2}:\d{2})(?:\.\d+)?Z$/.exec(value)
  return match ? `${match[1]} ${match[2]} UTC` : value
}
const ruNa = (reason: string | null): string => (reason ? `н/д (${ruReason(reason)})` : 'н/д')
const ruExact = (value: string | null, suffix = ''): string | undefined =>
  value !== null && ruNumber(value) !== value ? `${value}${suffix}` : undefined

export const COMPARE_LABELS: CompareLabels = {
  title: 'Сравнение с baseline',
  intro: 'Baseline это зафиксированный сохранённый анализ, выбранный вручную или статистически: новые прогоны его не заменяют. '
    + 'Сравнение показывает изменения, но вердикт не меняет. '
    + 'Статус «кандидат» появляется только после вашего подтверждения, что условия прогонов сопоставимы.',
  seriesLabel: 'Серия сравнения',
  seriesHint: 'Назовите сценарий и условия теста (до 128 байт UTF-8). Одно имя не доказывает сопоставимость.',
  seriesDefault: 'Выбранная серия тестов',
  loading: 'Загрузка baseline…',
  noBaseline: 'Baseline не выбран. Откройте сохранённый анализ, чтобы назначить его.',
  mode: (mode) => RU_MODES[mode] ?? mode,
  runWord: 'Прогон',
  analysisWord: 'Анализ',
  candidatesLine: (algorithm, count) => `${algorithm} · ${count} ${pluralRu(count, 'кандидат', 'кандидата', 'кандидатов')}`,
  scoresSummary: 'Оценки выбора',
  scoreLine: (id, score) => `${id}: ${score} (чем меньше, тем центральнее)`,
  baselineWindow: 'ID окна baseline',
  currentWindow: 'ID текущего окна',
  minChange: 'Минимальное изменение (%)',
  minErrorDelta: 'Минимальная разница доли ошибок (доля)',
  windowHint: 'Необязательно: введите оба ID окон, чтобы сравнить их сохранённые метрики. Оставьте оба пустыми для общих метрик прогона. '
    + 'Совпадение имён не доказывает ту же плановую нагрузку, состав запросов и условия теста. '
    + 'Пороги существенности должны быть больше нуля; разница доли ошибок 0.001 это 0,1 процентного пункта.',
  conditionsLegend: 'Плановые условия для этой пары анализов',
  conditionConfirmed: 'Подтверждаю: плановые условия теста те же',
  conditionNotConfirmed: 'Не подтверждено',
  conditionUnknown: 'Неизвестно',
  conditionsHint: 'Решение сохраняется только для показанных анализов baseline и текущего и, если они введены, для обоих ID окон. '
    + 'Оно меняет трактовку, а не дельты метрик, SLA или вердикт политики.',
  conditionLoading: 'Загрузка сохранённого решения об условиях…',
  conditionSaved: (decision, updatedAt) => `Сохранено: ${RU_DECISIONS[decision] ?? decision} (${ruTime(updatedAt)})`,
  conditionNone: 'Для этой пары решение не сохранено.',
  saveCondition: 'Сохранить решение об условиях',
  savingCondition: 'Сохранение решения…',
  setBaseline: 'Назначить baseline',
  compare: 'Сравнить выбранный анализ',
  comparing: 'Сравнение…',
  clearBaseline: 'Сбросить baseline',
  statSummary: 'Статистический выбор',
  statText: 'Выберите от 3 до 20 разных прогонов с одними и теми же плановыми условиями теста. '
    + 'Центральный реальный прогон выбирается по рангам p95, пропускной способности и доли ошибок. '
    + 'Это эвристика: она не доказывает устойчивую норму или статистическую значимость. Не добавляйте в кандидаты тестируемый прогон.',
  addCandidate: 'Добавить выбранный анализ в кандидаты',
  remove: 'Убрать',
  removeAria: (filename) => `Убрать кандидата ${filename}`,
  sameConditions: 'Те же плановые условия теста',
  candidatesHint: 'Подтвердите сценарий и состав запросов, стенд и набор данных, модель нагрузки, цели, паузы и ограничения генератора. '
    + 'Достигнутый RPS может отличаться. Неверные или неполные кандидаты отклоняются, а не отбрасываются молча. '
    + 'Это подтверждает только набор кандидатов; каждую сравниваемую пару подтверждайте в форме плановых условий выше.',
  selectStatistically: 'Выбрать статистически',
  requestFailed: 'Не удалось выполнить запрос baseline.',
  metricsTitle: 'Общие метрики относительно baseline',
  statusLine: (comparability) => (comparability === 'USER_CONFIRMED'
    ? 'Условия подтверждены вами'
    : 'Условия не подтверждены: изменения только описательные, статус «кандидат» невозможен'),
  deltasNote: 'Одни дельты не доказывают регрессию версии и не меняют вердикт политики.',
  deltasRegion: 'Отклонения общих метрик от baseline',
  metricHead: 'Метрика / единица',
  baselineHead: 'Baseline',
  currentHead: 'Текущий',
  absoluteHead: 'Абсолютная разница',
  relativeHead: 'Относительная разница',
  roundingNote: 'Значения округлены для показа: до 2 знаков после запятой, малые числа до 3 значащих цифр; точное значение видно в подсказке ячейки (при наведении мыши). Доля ошибок в долях: 0.01 = 1 %.',
  rawMetricsSummary: 'Точные значения общих метрик',
  windowTitle: 'Наблюдения в выбранных окнах',
  windowStatus: (status) => RU_WINDOW_STATUS[status] ?? status,
  windowStats: (side, samples, durationMs) =>
    `${side === 'baseline' ? 'Baseline' : 'Текущее'}: ${samples ?? 'н/д'} наблюдений / ${durationMs ?? 'н/д'} мс.`,
  uncertaintyNote: 'Неопределённость не оценивалась. Эти два наблюдения не доказывают воспроизводимую регрессию версии.',
  windowRegion: 'Отклонения метрик в выбранных окнах',
  windowMetricHead: 'Метрика / сущность / единица',
  statusHead: 'Статус / причина',
  rawSummary: 'Исходные данные сравнения окон',
  overall: 'Весь прогон',
  metric: (metric) => RU_METRICS[metric] ?? metric,
  unit: (unit) => RU_UNITS[unit] ?? unit,
  na: 'н/д',
  naReason: (reason) => (reason ? `н/д (${ruReason(reason)})` : 'н/д'),
  value: (value, peer) => (value === null ? 'н/д' : ruNumber(value, peer)),
  deltaValue: (value, reason) => (value === null ? ruNa(reason) : ruNumber(value)),
  percentValue: (value, reason) => (value === null ? ruNa(reason) : `${ruNumber(value)} %`),
  exact: (value, percent) => ruExact(value, percent ? ' %' : ''),
  reasons: (reasons) => reasons.map(ruReason).join(', ') || '—',
  reasonOrDash: (reason) => (reason === null ? '—' : ruReason(reason)),
  chartsSummary: 'Графики baseline и текущего прогона',
  chartsNote: 'Время отсчитывается от начала нагрузки каждого прогона; вид не совмещает ступени и не доказывает равенство условий теста. '
    + 'События OpenSearch остаются в видах отдельных прогонов.',
  chartsBin: 'Шаг сравнения',
  chartsStepUnit: 'с',
  chartsLoad: 'Загрузить графики сравнения',
  chartsLoading: 'Загрузка сохранённых интервалов…',
  chartsUnavailable: 'Сравнение графиков недоступно.',
  chartsTruncated: 'Показаны первые 500 интервалов каждого прогона. Остальные не показаны: возьмите шаг шире или откройте вид отдельного прогона.',
  foreignLang: 'en',
}
