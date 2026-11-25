// Строки вкладки «Сравнение» (срез U5a): панель baseline и график сравнения.
// Предупреждения, подсказка пустого окна и подсказка старых правил остаются в BASELINE_LABELS (labels.ts) и здесь не дублируются.
// Коды причин и статусов, которых нет в таблицах ниже, выводятся как есть.
import type { BaselineComparison } from '../types'
import { BASELINE_ERROR_LABELS, pluralRu } from './labels'

export interface CompareLabels {
  title: string
  intro: string
  seriesLabel: string
  seriesHint: string
  seriesDefault: string
  loading: string
  noBaseline: string
  // Список активных baseline (по одному на серию) и пояснение, когда у выбранной серии baseline нет.
  slotsLegend: string
  noBaselineFor: (series: string) => string
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
  // Пояснение рядом с недоступной кнопкой «Назначить baseline» по коду допуска.
  ineligible: (code: string) => string
  // Строка о заявленных профилях условий пары релизов (поле comparison.profile); null, если профиля пары нет.
  profileLine: (profile: NonNullable<BaselineComparison['profile']>) => string
  // Фраза словаря для кода 422 (`limit` из `error.limit`) или null: тогда показывается сообщение сервера.
  errorText: (code: string, limit: number | null) => string | null
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
  // Блок точных значений общих метрик.
  rawMetricsSummary: string
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
  chartsAria: string
  chartsAxisLabel: string
  chartsLegend: string
  chartsCurrent: string
  chartsBaseline: string
  chartsEmpty: string
  chartsIncompatible: string
  chartsPartial: string
  chartsCursorLabel: string
  chartsCursorHint: string
  chartsCursorNone: string
  chartsCursorTime: (time: string) => string
  chartsCursorText: (time: string, rps: string, errors: string, p95: string) => string
  chartsThresholdMark: (value: string, unit: string) => string
  // Значение атрибута lang для английских фрагментов внутри русской панели (ошибки сервера, внутренности графика).
  foreignLang: string
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
const RU_PROFILE_FIELDS: Record<string, string> = {
  scenario_mix: 'сценарии и состав запросов',
  environment_dataset: 'стенд и набор данных',
  load_model: 'модель нагрузки',
  targets_stages: 'цели и ступени',
  pacing: 'паузы (pacing)',
  generator_limits: 'ограничения генератора',
}
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
  CANDIDATE: 'материальная дельта, значимость не оценена',
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
    + 'Статус «материальная дельта, значимость не оценена» появляется только после вашего подтверждения, что условия прогонов сопоставимы.',
  seriesLabel: 'Серия сравнения',
  seriesHint: 'Назовите сценарий и условия теста (до 128 байт UTF-8). Одно имя не доказывает сопоставимость.',
  seriesDefault: 'Выбранная серия тестов',
  loading: 'Загрузка baseline…',
  noBaseline: 'Baseline не выбран. Откройте сохранённый анализ, чтобы назначить его.',
  slotsLegend: 'Активные baseline (по одному на серию)',
  noBaselineFor: (series) => `Для серии «${series}» baseline не выбран.`,
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
  errorText: (code, limit) => BASELINE_ERROR_LABELS[code]?.(limit) ?? null,
  ineligible: (code) => BASELINE_ERROR_LABELS[code]?.(null) ?? code,
  profileLine: (profile) => (profile.status === 'MATCH'
    ? 'Профиль условий релизов: совпадает'
    : `Профиль условий релизов: различается: ${profile.differing_fields.map((name) => RU_PROFILE_FIELDS[name] ?? name).join(', ')}`),
  metricsTitle: 'Общие метрики относительно baseline',
  statusLine: (comparability) => (comparability === 'USER_CONFIRMED'
    ? 'Условия подтверждены вами'
    : 'Условия не подтверждены: изменения только описательные, статус «материальная дельта, значимость не оценена» невозможен'),
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
  chartsAria: 'Графики сравнения нагрузки текущего прогона и baseline на общей шкале времени',
  chartsAxisLabel: 'Время от начала прогона',
  chartsLegend: 'Сплошная линия: текущий прогон · пунктир: baseline. Относительное время от начала каждого прогона; пропуски не интерполируются.',
  chartsCurrent: 'Текущий',
  chartsBaseline: 'Baseline',
  chartsEmpty: 'Данных нагрузки по интервалам для сравнения нет.',
  chartsIncompatible: 'Сравнение графиков недоступно: определения метрик несовместимы.',
  chartsPartial: 'Показана не вся история интервалов: достигнут предел загрузки.',
  chartsCursorLabel: 'Курсор сравнения по времени',
  chartsCursorHint: 'Стрелки влево и вправо двигают курсор по интервалам, Home и End переходят к краям. Курсор можно двигать и мышью над графиками.',
  chartsCursorNone: 'курсор не выбран',
  chartsCursorTime: (time) => `Отметка времени от начала прогона: ${time}`,
  chartsCursorText: (time, rps, errors, p95) => `${time}: RPS ${rps}, ошибки ${errors}, p95 ${p95}`,
  chartsThresholdMark: (value, unit) => `порог ${value} ${unit}`,
  foreignLang: 'en',
}
