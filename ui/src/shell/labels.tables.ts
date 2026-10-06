// Русские строки таблиц «Правила» и «Транзакции» новой оболочки (срез U3a).
// Компоненты и адаптеры остаются ASCII и ссылаются на эти строки по ключам.

export const TABLES_LABELS = {
  noData: 'нет данных',
  noWindow: '—',
  conditionLte: 'не более',
  conditionGte: 'не менее',
  belowDisplayPrecision: ' (нарушение меньше точности отображения)',
  scopeOverall: 'весь прогон',
  scopeMissing: 'область не указана',
  statusText: {
    PASS: 'В норме',
    FAIL: 'Нарушение',
    NO_VERDICT: 'Нет вердикта',
    NO_POLICY: 'Без правил',
    NOT_CHECKED: 'Не проверялось',
  },
  rulesTitle: 'Правила',
  regionRules: 'Результаты проверки правил',
  rulesEmpty: 'В результате нет проверок правил по метрикам нагрузки. Правила ресурсов, если они заданы, показаны ниже.',
  // Порядок столбцов: идентификатор, область, метрика, условие с порогом, измерено, окно, статус, причина.
  ruleHeads: ['Правило', 'Область', 'Метрика', 'Порог', 'Измерено', 'Окно', 'Выборка', 'Статус', 'Причина'],
  // Выборка проверки (ADR 0018): «30 из 50 · малая выборка»; без поля режима в ячейке прочерк.
  noSample: '—',
  sampleOf: (count: string, limit: string): string => `${count} из ${limit}`,
  sampleModeText: {
    FULL: 'достаточно',
    SMALL_SAMPLE: 'малая выборка',
    INSUFFICIENT: 'недостаточно',
    NOT_GATED: 'без порога',
  },
  txTitle: 'Транзакции',
  regionTx: 'Метрики транзакций',
  txEmpty: 'В результате нет метрик по транзакциям.',
  txNoMatch: 'Ничего не найдено. Измените поиск или фильтр статуса.',
  impactOrder: 'Порядок по умолчанию: сначала нарушения, затем по числу ошибок, p99 и выборке.',
  searchLabel: 'Найти транзакцию',
  statusFilterLabel: 'Статус',
  allStatuses: 'Все статусы',
  shownOf: (shown: number, total: number): string => `Показано ${shown} из ${total}`,
  sortBy: (column: string): string => `Сортировать по столбцу «${column}»`,
  // Подписи столбцов таблицы транзакций; ключи совпадают с TxSortKey, кроме p50 и status (без сортировки).
  txHeads: {
    label: 'Транзакция',
    samples: 'Выборка',
    errors: 'Ошибки',
    errorRate: 'Доля ошибок',
    p50: 'p50',
    p95: 'p95',
    p99: 'p99',
    rps: 'RPS',
    status: 'Статус',
  },
} as const

// Русские строки таблиц «Ёмкость» и «Тренды» новой оболочки (срез U3b).

const CAPACITY_BOUND_WORDS = {
  BOUNDED: (lower: string, upper: string, unit: string): string =>
    `От ${lower} до ${upper} ${unit} (верхняя граница не включается): на ${lower} требования выполнены, на ${upper} нарушены`,
  UPPER_BOUND: (_lower: string, upper: string, unit: string): string =>
    `Ниже ${upper} ${unit}: на ${upper} требования нарушены, ступени без нарушений нет`,
  LOWER_BOUND: (lower: string, _upper: string, unit: string): string =>
    `Не менее ${lower} ${unit}: нарушений на проверенных ступенях нет, верхняя граница не найдена`,
  INDETERMINATE: (): string => 'Граница ёмкости не определена',
} as const

export const CAPACITY_LABELS = {
  title: 'Ёмкость: ступени теста максимума',
  region: 'Ступени теста максимума',
  axisLabel: 'Ось нагрузки',
  axisValue: (axis: string, unit: string): string => `${axis} (${unit})`,
  boundLabel: 'Граница ёмкости',
  boundText: (bound: string, lower: string, upper: string, unit: string): string => {
    const words = (CAPACITY_BOUND_WORDS as Record<string, ((lower: string, upper: string, unit: string) => string) | undefined>)[bound]
    return words ? words(lower, upper, unit) : `Тип границы без расшифровки: ${bound}`
  },
  verdictLabel: 'Вердикт по ёмкости',
  kneeLabel: 'Точка перегиба',
  kneeNotImplemented: 'Точка перегиба в этой версии не определяется',
  kneeNone: (reason: string): string => `Точка перегиба не найдена (${reason})`,
  kneeValue: (value: string, unit: string): string => `${value} ${unit}`,
  reasonsLabel: 'Причины',
  noReasons: 'нет',
  smallSampleNote: 'Малая выборка: у ступеней с этой пометкой запросов в окне меньше минимума или у правила окна малая выборка. Такая ступень не подтверждена и не определяет границу ёмкости. Сама метка не означает нарушения SLA, а результат SLA в этом окне для границы не используется.',
  smallSampleMark: 'малая выборка',
  heads: ['Ступень', 'Цель', 'Достигнуто (p05 за 10 с)', 'Наблюдалось, мин. / макс.', 'Интервалы, полных / ожидалось', 'Подтверждённая нагрузка', 'Итог ступени', 'Причины', 'Данные'],
  stageVerdict: {
    PASS: 'Выдержана',
    FAIL: 'Нарушение',
    NO_POLICY: 'Без правил',
    INDETERMINATE: 'Не подтверждена',
  },
  noEvidence: 'нет',
  evidenceSummary: (count: number): string => `Основания (${count})`,
  empty: 'В результате нет сводки по ёмкости.',
} as const

const TREND_REASON_WORDS: Record<string, string> = {
  TREND_SERIES_NOT_FOUND: 'Ряд из проверки тренда отсутствует в снимке ресурсов.',
  TREND_WINDOW_NOT_FOUND: 'Окно из проверки тренда не найдено в снимке ресурсов.',
  TREND_MIN_CELLS_NOT_MET: 'В окне меньше значений, чем требует проверка.',
  TREND_HALF_CELLS_NOT_MET: 'В одной из половин окна слишком мало значений, чтобы сравнить половины.',
  TREND_MEDIAN_ZERO: 'Медиана равна нулю, относительный порог сдвига не определён.',
  TREND_DIRECTION_MISMATCH: 'Направление наклона не совпадает с заявленным.',
  TREND_DIRECTION_DISAGREEMENT: 'Знак сдвига половин окна не совпадает со знаком наклона (сдвиг может быть равен нулю).',
  TREND_SLOPE_BELOW_MINIMUM: 'Наклон ниже минимального порога.',
  TREND_SHIFT_BELOW_MINIMUM: 'Сдвиг половин окна ниже порога.',
  STATIONARITY_NOT_EVALUATED: 'Стационарность ряда не оценивалась.',
}

export const TREND_LABELS = {
  title: 'Тренды ресурсов',
  region: 'Проверки трендов ресурсов',
  method: 'Метод slope-materiality.v1: наклон и сдвиг медиан половин окна должны пройти порог и совпасть по знаку. Неопределённость не оценивается. Отсутствие находки не доказывает отсутствие деградации. Тренд это диагностика, на вердикт он не влияет.',
  summary: (total: number, observed: number, notMaterial: number, insufficient: number, unavailable: number): string =>
    `Проверок: ${total}. Наблюдается: ${observed}. Не существенно: ${notMaterial}. Мало данных: ${insufficient}. Недоступно: ${unavailable}.`,
  heads: ['Проверка', 'Ряд', 'Окно', 'Заявленное направление', 'Статус', 'Наблюдаемое направление', 'Наклон в секунду', 'Сдвиг половин окна', 'Медиана', 'Требуемый сдвиг', 'Ячеек, наблюдалось / ожидалось', 'Причины'],
  noData: 'нет данных',
  noReasons: 'нет',
  statusText: {
    TREND_OBSERVED: 'Наблюдается',
    NO_MATERIAL_TREND: 'Не существенно',
    INSUFFICIENT_CELLS: 'Мало данных',
    UNAVAILABLE: 'Недоступно',
  },
  declaredText: { increase: 'рост', decrease: 'снижение', either: 'любое' },
  observedText: { increase: 'рост', decrease: 'снижение', flat: 'без изменения' },
  reasonWords: TREND_REASON_WORDS,
} as const

// Блок «Нормализованные данные» вкладки «Таблицы» новой оболочки (интервалы времени, порциями).
export const NORMALIZED_LABELS = {
  eyebrow: 'Исходные факты для проверки',
  title: 'Нормализованные данные',
  rollupLabel: 'Шаг',
  rollupAria: 'Шаг интервалов',
  rollupOption: (seconds: number): string => `${seconds} с`,
  startOffset: 'Начало, смещение (мс)',
  endOffset: 'Конец, смещение (мс)',
  refresh: 'Обновить данные',
  region: 'Интервалы времени',
  heads: ['Интервал', 'RPS', 'Ошибки', 'p95', 'Максимум отклика', 'Данные'],
  available: 'Есть',
  missing: 'Нет данных (пропуск)',
  unit: 'мс',
  noValue: '—',
  shown: (shown: number, total: number): string => `Показано ${shown} из ${total} интервалов`,
  more: (step: number): string => `Показать ещё ${step}`,
  all: (total: number): string => `Показать все (${total})`,
} as const
