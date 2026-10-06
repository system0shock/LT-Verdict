// Все русские строки новой оболочки (срезы U0 и U1) живут только в этом файле.
// Остальной код оболочки ссылается на них по ключам и остаётся ASCII.

export type ShellTabKey = 'overview' | 'deep' | 'tables' | 'compare' | 'history' | 'rules' | 'advice' | 'setup'

export interface ShellTab {
  key: ShellTabKey
  label: string
  pending: boolean
}

// Порядок как в макете: главное действие «Новый анализ» первой вкладкой.
export const SHELL_TABS: readonly ShellTab[] = [
  { key: 'setup', label: 'Новый анализ', pending: false },
  { key: 'overview', label: 'Обзор', pending: false },
  { key: 'deep', label: 'Глубокий анализ', pending: false },
  { key: 'tables', label: 'Таблицы', pending: false },
  { key: 'compare', label: 'Сравнение', pending: false },
  { key: 'history', label: 'История', pending: false },
  { key: 'rules', label: 'Правила', pending: false },
  { key: 'advice', label: 'ИИ-разбор', pending: false },
]

// Вкладка при открытии страницы, пока прогон не выбран; после загрузки результата открывается «Обзор».
export const SHELL_DEFAULT_TAB: ShellTabKey = 'setup'

export const SHELL_LABELS = {
  navLabel: 'Разделы',
  pendingBadge: 'в разработке',
  overviewEmpty: 'Прогон не выбран. Выберите прогон в списке слева или откройте вкладку «Новый анализ».',
  noRun: 'Прогон не выбран',
  completed: 'Завершён',
  themeToDark: 'Тёмная тема',
  themeToLight: 'Светлая тема',
  legacyLink: 'Старый интерфейс',
  runsTitle: 'Принятые прогоны',
  runsEmpty: 'Прогонов пока нет',
  runsMore: 'Ещё прогоны',
  analysesTitle: 'Сохранённые анализы',
  analysisItem: 'Анализ',
  analysesEmpty: 'Для этого прогона нет сохранённых анализов.',
  analysesMore: 'Ещё анализы',
} as const

// Строки сравнения с эталоном (ADR 0017): предупреждения и подсказки для BaselinePanel.vue.
export const BASELINE_LABELS = {
  warningsTitle: 'Предупреждения',
  warnings: {
    BASELINE_IS_CURRENT_ANALYSIS: 'Эталон и сравниваемый анализ совпадают: это сравнение прогона с самим собой.',
    BASELINE_IS_CURRENT_RUN: 'Эталон и сравниваемый анализ относятся к одному прогону: нагрузочные данные у них одинаковые.',
    CURRENT_IN_CANDIDATE_SET: 'Сравниваемый прогон входил в набор кандидатов статистического эталона: эталон выбран с его участием.',
    BASELINE_NOT_PASS: 'У эталона нет вердикта PASS (прежний эталон, выбранный до правила «только PASS»): сравнение с ним не показывает регрессии относительно успешного прогона.',
    BASELINE_SMALL_SAMPLE: 'Эталон получен на малой выборке: у части правил запросов наблюдений меньше рекомендуемого минимума, PASS эталона менее надёжен.',
    POLICY_DIFFERS: 'Политики эталона и сравниваемого анализа различаются: вердикт эталона получен по другой политике.',
    PROFILE_MISMATCH: 'Заявленные профили условий релизов различаются: нагрузка или окружение могли быть заданы по-разному.',
  },
  emptyWindowHint: 'В окне нет нагрузки: проверьте границы окна и синхронизацию часов генератора и кластера.',
  emptyBaselineWindow: 'Пусто окно эталона (baseline).',
  emptyCurrentWindow: 'Пусто текущее окно (current).',
  // Смысловой ключ анализа (ядро, `comparisonSemanticKey`): режим анализа, источник, разбор, модули и версии входов, выходы,
  // настройки гистограммы и нормализации, лимиты и плечо ресурсов. Саму политику ключ не содержит: разные политики дают
  // несопоставимость только через другой набор входов (например, правила по ресурсам требуют снимок ресурсов).
  incompatibleHint:
    'Анализы несопоставимы: у них разный набор входных данных (снимок ресурсов, диагностика, ёмкость, тренды), настройки анализа '
    + 'или версии правил анализа. Сравнивайте анализы с одинаковым набором данных и настройками; если эталон создан давно, '
    + 'пересчитайте его (заново проанализируйте исходные данные и закрепите baseline).',
} as const

// Отказы 422 при работе с baseline (ADR 0019): русская фраза по коду; серверное сообщение остаётся запасным для неизвестного кода.
// `limit` приходит из `error.limit` ответа и может отсутствовать.
export const BASELINE_ERROR_LABELS: Record<string, (limit: number | null) => string> = {
  BASELINE_SERIES_CONFLICT: () => 'Серия сравнения не совпадает с серией релиза этого анализа. '
    + 'Выберите baseline серии релиза этого анализа или откройте анализ из другого релиза.',
  BASELINE_SLOTS_LIMIT_REACHED: (limit) => `Достигнут предел активных baseline${limit === null ? '' : `: ${limit}`}. `
    + 'Сбросьте baseline одной из серий, чтобы закрепить новый.',
  BASELINE_CONDITIONS_LIMIT_REACHED: (limit) => `Достигнут предел сохранённых решений об условиях${limit === null ? '' : `: ${limit}`}. `
    + 'Сбросьте baseline ненужной серии: вместе с ним удаляются решения об условиях его пар.',
}

// Русское склонение: 1 пункт, 2 пункта, 5 пунктов.
export function pluralRu(count: number, one: string, few: string, many: string): string {
  const mod100 = Math.abs(count) % 100
  const mod10 = mod100 % 10
  if (mod100 >= 11 && mod100 <= 14) return many
  if (mod10 === 1) return one
  if (mod10 >= 2 && mod10 <= 4) return few
  return many
}

export type TrendDirection = 'increase' | 'decrease' | 'flat'

// Срез U1 корреляций (ADR 0022, Д10): показ отобранных ассоциаций. Слово «причина» допустимо только внутри
// фиксированной пометки `mark` (решение владельца 2026-10-05); слова «утечка», «из-за», «доказано» не используются.
const SELECTION_REPRESENTATIONS: Record<string, string> = { levels: 'уровни', first_difference: 'первые разности' }

export const CORRELATION_LABELS = {
  mark: 'ассоциация, не причина; не откалибровано',
  advice: 'Это повод для проверки. Метод не откалиброван на реальных данных вашего стенда.',
  note: 'Ассоциация, не причина; не откалибровано. Это повод для проверки. Метод не откалиброван на реальных данных вашего стенда.',
  title: 'Гипотезы для проверки',
  regionAria: 'Гипотезы для проверки',
  empty: 'Ассоциаций, прошедших отбор, нет. Это не значит, что связей нет: отбор строгий и не откалиброван.',
  // Порядок столбцов: стадия, ряд ресурса, исход, лаг, ранговая корреляция, скорректированная вероятность, проверено гипотез, метод.
  heads: ['Стадия', 'Ряд ресурса', 'Исход', 'Лаг, с', 'Ранговая корреляция', 'Скорректированная вероятность', 'Проверено гипотез', 'Метод'],
  noValue: '—',
  methodNote: (method: string, representation: string | undefined, stageCount: number | undefined): string => {
    const version = /\.(v\d+)$/.exec(method)?.[1]
    const parts = [`метод ${version ?? method}`]
    if (representation) parts.push(`представление: ${SELECTION_REPRESENTATIONS[representation] ?? representation}`)
    if (stageCount !== undefined) parts.push(`стадий: ${stageCount}`)
    if (version === 'v1') parts.push('при дрейфе ряда ненадёжно')
    return parts.join('; ')
  },
  unavailableLine: (windowId: string, count: number, total: number, reasons: readonly string[]): string =>
    `Стадия «${windowId}»: не удалось проверить гипотез: ${count} из ${total}. Что помешало: ${reasons.join('; ')}.`,
  // Что помешало проверке семьи (контракт correlation-headline-selection, ADR 0022); неизвестный код выводится как есть.
  unavailable: {
    GENUINE_PARTIAL_UNCALIBRATED: 'нагрузка менялась внутри стадии, такая поправка не откалибрована',
    PAIR_NOT_EVALUABLE: 'для пары нельзя посчитать корреляцию',
    FAMILY_SIZE_UNSUPPORTED: 'в семье больше 16 гипотез',
    MULTI_WINDOW_FAMILY_UNSUPPORTED: 'гипотезы относятся к разным стадиям',
    FAMILY_GRID_MISMATCH: 'сетки времени или пропуски у гипотез различаются',
    FAMILY_OUTCOME_MISMATCH: 'исходы гипотез различаются',
    OBSERVATION_COUNT_UNSUPPORTED: 'число ячеек стадии вне допустимого диапазона',
    LAG_ANCHOR_COUNT_UNSUPPORTED: 'лаг вне допустимого диапазона или слишком мало точек для лага',
    BOOTSTRAP_REPLICATE_NOT_EVALUABLE: 'перестановка дала вырожденный ряд',
    COMPUTATION_LIMIT_EXCEEDED: 'превышен предел вычисления',
    HOLM_RESOLUTION_INSUFFICIENT: 'гипотез слишком много: число перестановок не позволяет подтвердить находку',
  } as Record<string, string>,
}

// Срез U1: вкладка «Обзор». Функции получают уже отформатированные строки и числа-счётчики.
export const OVERVIEW_LABELS = {
  // Требует внимания
  attentionTitle: 'Требует внимания',
  attentionLead: 'Сначала нарушения правил, затем причины, по которым нет вердикта, неполные данные и диагностика. Список собран из готового результата анализа, ничего не пересчитывается.',
  attentionEmpty: 'Нечего отметить: в результате нет нарушений правил, причин отсутствия вердикта, предупреждений о неполных данных и найденных трендов.',
  attentionMore: (hidden: number) => `Показать ещё ${hidden}`,
  attentionLess: 'Свернуть список',
  kindViolation: 'Нарушение',
  kindNoVerdict: 'Нет вердикта',
  kindPolicy: 'Политика',
  kindCoverage: 'Данные',
  kindDiagnostic: 'Диагностика',
  diagnosticBadge: 'диагностика, не причина',
  diagnosticNote: 'Тренды, корреляции и срабатывания диагностических правил ресурсов показывают, что значения менялись вместе, в одну сторону или вышли за порог. Это диагностика: она не объясняет, чем вызвано изменение, и не меняет вердикт SLA-правил.',
  noPolicyTitle: 'Политика не задана, пороги не проверялись',
  noPolicyDetail: 'Добавьте правила на вкладке «Новый анализ» и запустите анализ заново.',
  validityDegraded: 'Файл нагрузки разобран не полностью',
  validityInvalid: 'Файл нагрузки не удалось разобрать',
  causeSubjects: (label: string, subjects: readonly string[], hidden: number) =>
    `${label}: ${subjects.join(', ')}${hidden > 0 ? ` и ещё ${hidden}` : ''}`,
  resourceSeriesTitle: (count: number, total: number) =>
    `Рядов ресурсов без значений в окне SLA-правила: ${count} из ${total}`,
  resourceSeriesDetail: (series: readonly string[], hidden: number) =>
    `${series.join(', ')}${hidden > 0 ? ` и ещё ${hidden}` : ''}`,
  trendTitle: (series: string) => `Тренд: ${series}`,
  trendDetail: (windowId: string, direction: TrendDirection | null, shift: string, median: string, unit: string | null) => {
    const word = direction === 'increase' ? 'рост' : direction === 'decrease' ? 'снижение' : direction === 'flat' ? 'без изменений' : 'направление не определено'
    const suffix = unit ? ` ${unit}` : ''
    return `окно ${windowId}: ${word}; сдвиг медианы половин окна ${shift}${suffix}; медиана окна ${median}${suffix}`
  },
  correlationTitle: (windowId: string, series: string, loadMetric: string) => `Ассоциация в стадии «${windowId}»: ${series} и ${loadMetric}`,
  correlationDetail: (lag: string, rho: string, adjustedP: string, hypotheses: number, note: string) =>
    `лаг ${lag} с, ранговая корреляция ${rho}, скорректированная вероятность ${adjustedP}, проверено гипотез: ${hypotheses} (${note}). ${CORRELATION_LABELS.advice}`,
  openRules: 'Открыть таблицу правил',
  openResources: 'Открыть ресурсы',
  openCapacity: 'Открыть ёмкость',
  capacityFailTitle: 'Ёмкость недостаточна: верхняя граница не выше требуемой',
  openTrends: 'Открыть тренды',
  openDiagnostics: 'Открыть диагностику',
  openDeep: 'Открыть глубокий анализ',
  openSources: 'Открыть источники',
  openSetup: 'Открыть «Новый анализ»',

  // Ёмкость по ступеням (тест максимума)
  capacityTitle: 'Ёмкость по ступеням',
  capacityLead: 'Граница ёмкости и нагрузка по ступеням теста максимума. Всё взято из готового результата, ничего не пересчитывается; причины и основания по ступеням в таблице на вкладке «Таблицы».',
  capacityVerdict: {
    PASS: 'Ёмкость подтверждена',
    FAIL: 'Ёмкость недостаточна',
    NO_VERDICT: 'Вердикт по ёмкости не выдан',
    NO_POLICY: 'Вердикта по ёмкости нет',
  } as Record<string, string>,
  // Требуемая ёмкость в результате не хранится, поэтому говорим о ней через вердикт: так считает ядро (PASS: нижняя граница не меньше требуемой, FAIL: верхняя не больше).
  capacityStatementPass: (lower: string, unit: string) => `Требуемая ёмкость не выше ${lower} ${unit}: нижняя граница её покрывает.`,
  capacityPassFailedAbove: 'Нарушенные ступени выше границы вердикт не меняют: для итога нужна только нижняя граница.',
  capacityStatementFail: (upper: string, unit: string) => `Требуемая ёмкость не ниже ${upper} ${unit}: на этой нагрузке требования нарушены.`,
  capacityStatementBounded: (lower: string, upper: string, unit: string) =>
    `Требуемая ёмкость лежит между ${lower} и ${upper} ${unit}: границы недостаточно, чтобы сравнить её с требованием.`,
  capacityStatementLower: (lower: string, unit: string) => `Нижняя граница ${lower} ${unit} ниже требуемой ёмкости, верхней границы нет: итог не выдан.`,
  capacityStatementUpper: (upper: string, unit: string) => `Верхняя граница ${upper} ${unit} выше требуемой ёмкости, нижней границы нет: итог не выдан.`,
  capacityStatementIndeterminate: 'Граница ёмкости не определена, сравнить её с требуемой ёмкостью нельзя.',
  capacityStatementBlocked: 'Итог не выдан: проверка не завершена, и вердикт по границе заблокирован. Причины указаны в списке «Требует внимания».',
  capacityStatementNoPolicy: 'Итог не выдан: в плане не задана требуемая ёмкость или у ступеней нет применимых правил SLA.',
  capacityChartTitle: 'Нагрузка по ступеням',
  capacityCounts: (total: number, passed: number, failed: number, other: number) =>
    `Ступеней: ${total}; выдержано: ${passed}, нарушено: ${failed}, не подтверждено или без правил: ${other}.`,
  capacityChartAria: (counts: string) => `График нагрузки по ступеням. ${counts} Те же данные в таблице под графиком.`,
  capacityTableRegion: 'Нагрузка по ступеням: таблица',
  capacityHeads: ['Ступень', 'Цель', 'Достигнуто (p05 за 10 с)', 'Итог ступени'],
  stageMark: { pass: '✓', fail: '✕', unverified: '?' } as Record<string, string>,
  capacityLegend: (lower: string | null, upper: string | null, unit: string) => {
    const parts = ['Полоса под строкой ступени: цель ступени. Вертикальная черта на полосе: достигнутая нагрузка.']
    if (lower !== null) parts.push(`Штриховая линия: нижняя граница ${lower} ${unit}.`)
    if (upper !== null) parts.push(`Точечная линия: верхняя граница ${upper} ${unit} (не включается).`)
    parts.push('Значок в столбце «Итог ступени» и слово дублируют цвет.')
    return parts.join(' ')
  },
  capacityOpenAria: 'Открыть ёмкость: таблица ступеней, причины и основания',

  // Ключевые метрики
  metricsTitle: 'Ключевые метрики прогона',
  metricsCapacityNote: 'Значения посчитаны по всему прогону, включая ступени нагрузки выше границы ёмкости, поэтому p95 и максимум могут быть высокими при подтверждённой ёмкости. Вердикт по ёмкости определяют ступени теста: они в таблице на вкладке «Таблицы».',
  metricRps: 'Пропускная способность',
  metricP95: 'p95 отклика',
  metricP99: 'p99 отклика',
  metricMax: 'Максимум отклика',
  unitRps: 'RPS',
  unitMs: 'мс',

  // Нагрузка по времени
  loadTitle: 'Нагрузка по времени',
  loadLead: 'Три ряда стоят друг под другом на общей шкале времени: пропускная способность, ошибки и p95 отклика. Курсор общий для всех рядов.',
  loadReadNote: 'Как читать: одновременное изменение рядов подсказывает, где искать. Причину оно не доказывает.',
  loadEmpty: 'Данных нагрузки по интервалам нет.',
  loadLoading: 'Загрузка нагрузки по интервалам...',
  loadFailed: (detail: string) => `Не удалось загрузить нагрузку по интервалам: ${detail}`,
  loadPartial: (shown: number, rollup: number) =>
    `Показан не весь прогон: достигнут предел загрузки, ${shown} ${pluralRu(shown, 'интервал', 'интервала', 'интервалов')} по ${rollup} с. Остальная часть прогона на графике не показана.`,
  loadStep: (rollup: number) =>
    `Шаг графика ${rollup} с подобран автоматически из 1, 10, 30 и 60 с, чтобы показать весь прогон. Запросы в секунду усреднены за шаг, ошибки сложены, p95 посчитан по всем запросам шага; точное время внутри шага не видно, короткие всплески могут сгладиться.`,
  loadSummary: (count: number, rollup: number) =>
    `${count} ${pluralRu(count, 'интервал', 'интервала', 'интервалов')} по ${rollup} с.`,
  loadGaps: (missing: number) =>
    `Пропущено ${missing} ${pluralRu(missing, 'интервал', 'интервала', 'интервалов')} без данных: линии через них не соединяются.`,
  chartAria: 'Ряды нагрузки на общей шкале времени',
  trackRps: 'Запросы в секунду',
  trackErrors: 'Ошибки за интервал',
  trackP95: 'p95 отклика',
  trackMax: (value: string) => `максимум ${value}`,
  axisLabel: 'Время от начала прогона',
  cursorLabel: 'Курсор по времени',
  cursorHint: 'Стрелки влево и вправо двигают курсор по интервалам, Home и End переходят к краям. Курсор можно двигать и мышью над графиками.',
  cursorNone: 'курсор не выбран',
  cursorTime: (time: string) => `Время ${time}`,
  cursorText: (time: string, rps: string, errors: string, p95: string) =>
    `${time}: ${rps} запросов в секунду, ошибок за интервал ${errors}, p95 ${p95} мс`,
} as const

// Срез D2-min: вкладка «Глубокий анализ». Нагрузка и выбранные ряды ресурсов на общей шкале времени.
export const DEEP_LABELS = {
  title: 'Глубокий анализ',
  lead: 'Нагрузка и выбранные ряды ресурсов стоят друг под другом на общей шкале времени. Курсор общий: он показывает значение каждого ряда в один и тот же момент.',
  readNote: 'Как читать: совпадение по времени подсказывает, где искать. Причину оно не доказывает.',
  clockNote: 'Часы генератора нагрузки и кластера ядро не сверяет: сдвиг между ними на графике неотличим от причинной связи.',
  noSnapshot: 'Ряды ресурсов не загружены: у этого анализа нет снимка ресурсов. Показана только нагрузка.',
  loading: 'Загрузка данных...',
  requestFailed: (detail: string) => `Не удалось загрузить данные: ${detail}`,

  // Выбор рядов
  seriesTitle: 'Ряды ресурсов',
  seriesFilter: 'Фильтр по имени',
  seriesNone: 'Нет рядов с таким именем.',
  seriesEmpty: 'В снимке нет рядов ресурсов.',
  seriesCount: (selected: number, max: number) => `Выбрано ${selected} из ${max}`,
  limitReached: (max: number) => `Выбрано максимум рядов: ${max}. Снимите один, чтобы выбрать другой.`,
  seriesMeta: (unit: string, entity: string) => `${unit}, ${entity}`,
  violatedBadge: 'правило нарушено',
  noValuesBadge: 'нет значений',

  // Период и шаг
  periodTitle: 'Период',
  periodFrom: 'С, мин от начала прогона',
  periodTo: 'По, мин от начала прогона',
  periodApply: 'Применить',
  periodAll: 'Весь прогон',
  periodInvalid: 'Период задан неверно: значения должны быть неотрицательными числами минут, а «с» меньше «по».',
  periodSnapped: (from: string, to: string) => `Период привязан к границам ячеек: от ${from} до ${to} от начала прогона.`,
  cellStep: (seconds: number) => `Шаг ячейки ресурсов: ${seconds} с (подобран автоматически, не более 1500 ячеек).`,
  loadRollup: (seconds: number) => `Шаг нагрузки: ${seconds} с.`,
  loadOutsideRun: 'Выбранный период целиком расположен до начала прогона: нагрузки в нём нет.',
  loadTruncated: (shown: number) => `Нагрузка показана не на весь период: загружено ${shown} интервалов, это предел. Сузьте период.`,
  loadEmpty: 'Данных нагрузки за период нет.',

  // График и курсор
  chartAria: 'Нагрузка и ряды ресурсов на общей шкале времени',
  axisLabel: 'Время от начала прогона',
  cursorLabel: 'Курсор по времени',
  cursorHint: 'Стрелки влево и вправо двигают курсор по ячейкам самого мелкого ряда, Home и End переходят к краям. Курсор можно двигать и мышью над графиками.',
  cursorNone: 'курсор не выбран',
  cursorTime: (ms: string, offset: string) => `Курсор: ${ms} мс от начала прогона (${offset})`,
  cursorText: (ms: string, offset: string) => `${ms} мс от начала прогона, ${offset}`,
  gap: 'нет данных',
  partialCell: (observed: number, total: number) => `наблюдено ${observed} из ${total}`,
  trackMax: (value: string) => `максимум ${value}`,
  reducerLabels: {
    mean: 'среднее за интервал',
    max: 'максимум за интервал',
    min: 'минимум за интервал',
  },
  thresholdLabel: (operator: 'gt' | 'lt', value: string, unit: string, violated: boolean) =>
    `${violated ? 'нарушение' : 'порог правила:'} ${operator === 'gt' ? 'выше' : 'ниже'} ${value} ${unit}`,
} as const

// Отмена загрузки входного файла (JobStatus.vue): кнопка и сообщение после отмены.
export const UPLOAD_LABELS = {
  cancel: 'Отменить загрузку',
  cancelled: 'Загрузка отменена. Анализ не запускался, файл можно выбрать заново.',
} as const

// Состояние связи при опросе задачи (JobStatus.vue): повтор и потеря связи.
export const JOB_LABELS = {
  retrying: 'Проблема со связью. Повторяем запрос статуса задачи…',
  lost: 'Связь потеряна. Статус задачи не обновляется, но сама задача на сервере могла продолжиться.',
  retry: 'Повторить',
} as const

// Срез U2: вкладка «Новый анализ». Подписи полей, состояние готовности и сообщения проверок.
export const SETUP_LABELS = {
  title: 'Новый анализ',
  lead: 'Загрузите файл нагрузочного теста и при желании добавьте политику, данные о ресурсах и источники. Обязателен только файл теста: остальное повышает глубину анализа.',
  optional: '(необязательно)',

  inputTitle: '1. Нагрузочный тест',
  inputLabel: 'Файл нагрузочного теста',
  inputHint: 'JMeter JTL (CSV или XML) или Gatling simulation.log.',
  inputNone: 'Файл не выбран.',

  rulesTitle: '2. Правила',
  policyLabel: 'Файл политики',
  policyNone: 'Политика не выбрана. Без правил вердикт не выдаётся: результат получит статус NO_POLICY.',

  systemTitle: '3. Данные о системе',
  systemLead: 'Снимок ресурсов, планы и контекст OpenSearch берутся из файлов. Если выбрать онлайн-источники, эти файлы не используются: снимок создаётся во время анализа.',
  resourcesLabel: 'Снимок ресурсов',
  resourcesNone: 'Снимок ресурсов не выбран.',
  contextLabel: 'Контекст OpenSearch',
  contextNone: 'Файлы контекста не выбраны.',
  contextHint: 'Должен относиться к тому же файлу нагрузки.',
  sourceProfileLabel: 'Онлайн-источники',
  sourceProfileNone: 'Без онлайн-источника',
  sourceProfileHint: 'Выберите до 16 профилей. Они используют одну временную сетку и дают метрики или контекст ошибок для скачивания.',
  sourceProfilesEmpty: 'На сервере не настроено ни одного профиля онлайн-источника.',
  windowLabel: 'Окно источника',
  windowAuto: 'Авто (по файлу нагрузки)',
  windowExplicit: 'Явный период',
  startLabel: 'Начало окна источника (UTC epoch, с)',
  endLabel: 'Конец окна источника (UTC epoch, с)',
  stepLabel: 'Шаг источника (с)',
  marginLabel: 'Запас по краям (с)',
  marginHint: 'Расширяет распознанный период прогона с обеих сторон до выравнивания по сетке; кратен шагу.',
  idleLabel: 'Максимальный простой (с)',
  idleHint: 'Авто-окно отклоняется, если в файле нагрузки простой длиннее; не меньше шага и кратен ему.',
  postgresProfileLabel: 'Профиль PostgreSQL',
  postgresProfileNone: 'Без снимков PostgreSQL',
  captureBefore: 'Снять состояние до',
  captureAfter: 'Снять состояние после',
  postgresHint: 'Скачайте снимок «до» перед тестом, приложите его ниже, затем снимите «после» после теста.',
  postgresPreLabel: 'Снимок PostgreSQL до',
  postgresPreNone: 'Снимок «до» не выбран.',
  postgresPreHint: 'Также привязывает явный снимок «после».',
  postgresPostLabel: 'Снимок PostgreSQL после',
  postgresPostNone: 'Снимок «после» не выбран.',
  pgHtmlLabel: 'Отчёт pg_profile (HTML)',
  pgHtmlOptional: '(необязательно, только для скачивания)',
  pgHtmlNone: 'Отчёт pg_profile не выбран.',
  pgHtmlHint: 'Сохраняется как неактивное вложение для скачивания и никогда не отображается.',

  plansTitle: '4. Планы анализа',
  plansLead: 'Планы задаются файлами и работают только со снимком ресурсов из файла, который относится к тому же файлу нагрузки.',
  correlationLabel: 'План корреляций',
  correlationNone: 'План корреляций не выбран.',
  capacityLabel: 'План ёмкости',
  capacityNone: 'План ёмкости не выбран.',
  trendLabel: 'План трендов',
  trendNone: 'План трендов не выбран.',
  planNeedsSnapshot: 'Нужен подходящий снимок ресурсов.',

  aiTitle: '5. ИИ-разбор',
  aiText: 'ИИ-разбор не входит в анализ и запрашивается только по вашему действию. Включите переключатель, и разбор запросится сам после завершения анализа; без него его можно запросить на вкладке «ИИ-разбор».',
  aiRequestedLabel: 'Запросить ИИ-разбор после анализа',

  readinessTitle: 'Готовность к запуску',
  willTitle: 'Что получится',
  startButton: 'Запустить анализ',
  startBlocked: 'Запуск недоступен, пока не выполнено:',
  startReady: 'Всё необходимое выбрано, можно запускать.',
  levelOk: 'Готово',
  levelInfo: 'Не задано',
  levelWarn: 'Внимание',
  levelBlock: 'Нужно',

  itemInput: 'Файл нагрузочного теста',
  itemBusy: 'Состояние',
  itemPolicy: 'Правила',
  itemResources: 'Снимок ресурсов',
  itemPlans: 'Планы',
  itemSources: 'Источники',
  itemPostgres: 'PostgreSQL',

  inputMissing: 'выберите файл JMeter JTL или Gatling simulation.log',
  busy: 'идёт загрузка файла, анализ или снимок PostgreSQL: дождитесь завершения',
  policyOk: (id: string) => `политика ${id}, вердикт по её правилам`,
  policyInvalid: 'в черновике политики есть ошибки: исправьте их, иначе запуск не выполнится',
  policyRejected: 'файл политики не принят: анализ запустится без правил, вердикта не будет (NO_POLICY)',
  policyNoneItem: 'не задана: результат получит статус NO_POLICY, метрики останутся доступны',
  resourcesOnline: 'будет создан из онлайн-источников во время анализа',
  resourcesRequired: (plans: string) => `${plans}: нужен снимок ресурсов, выберите файл снимка или уберите план`,
  resourcesNoneItem: 'не выбран: ряды ресурсов не анализируются',
  plansNoneItem: 'не выбраны',
  planNames: { diagnostic: 'план корреляций', capacity: 'план ёмкости', trend: 'план трендов' },
  sourcesOnline: (count: number) => `${count} ${pluralRu(count, 'профиль', 'профиля', 'профилей')} онлайн-источника`,
  sourcesContext: (count: number) => `контекст OpenSearch: ${count} ${pluralRu(count, 'файл', 'файла', 'файлов')}`,
  sourcesNoneItem: 'только файл нагрузки',
  postgresNames: { pre: 'снимок до', post: 'снимок после', html: 'отчёт pg_profile' },
  postgresNoneItem: 'не используется',

  willVerdict: (id: string) => `Вердикт по правилам политики ${id}.`,
  willNoVerdict: 'Метрики без вердикта (NO_POLICY).',
  willResourcesFile: 'Ряды ресурсов из файла снимка.',
  willResourcesOnline: 'Ряды ресурсов и контекст из онлайн-источников.',
  willPlans: (plans: string) => `Диагностика по планам: ${plans}.`,
  willContext: 'Контекст ошибок из OpenSearch.',
  willPostgres: (parts: string) => `Контекст PostgreSQL: ${parts}.`,
  willAdvice: 'После завершения анализа сам запросится ИИ-разбор.',
} as const

// Сообщения проверок на вкладке «Новый анализ» (App.vue); в прежнем интерфейсе остаются английские тексты.
export const SETUP_MESSAGES = {
  contextTooMany: 'Контекст OpenSearch принимает не более 16 файлов.',
  profilesTooMany: 'Онлайн-источники принимают не более 16 профилей.',
  autoRequired: 'Онлайн-источнику нужны шаг, запас по краям и максимальный простой в секундах.',
  autoNotInteger: 'Шаг, запас по краям и максимальный простой должны быть целыми числами секунд.',
  stepWholeSeconds: 'Шаг источника: целые секунды от 1 до 60.',
  marginRange: 'Запас по краям: не более 3600 с и кратен шагу.',
  idleGap: 'Максимальный простой: не меньше шага и кратен ему.',
  explicitRequired: 'Онлайн-источнику нужны начало, конец и шаг в секундах UTC epoch.',
  explicitNotInteger: 'Начало, конец и шаг окна должны быть целыми числами секунд.',
  explicitOrder: 'Конец окна должен быть позже неотрицательного начала.',
  explicitDivisible: 'Длина окна должна делиться на шаг без остатка.',
  explicitTooManyCells: 'В окне может быть не более 100000 ячеек (длина окна, делённая на шаг): увеличьте шаг или сократите период.',
  diagnosticNeedsSnapshot: 'План корреляций требует подходящего снимка ресурсов.',
  capacityNeedsSnapshot: 'План ёмкости требует подходящего снимка ресурсов.',
  trendNeedsSnapshot: 'План трендов требует подходящего снимка ресурсов.',
  policyValidating: 'Проверка политики…',
  policyInvalid: 'Политика не принята',
  policyMalformed: 'Политика не является корректным JSON.',
  policyValid: (id: string) => `Политика принята — ${id}`,
} as const
