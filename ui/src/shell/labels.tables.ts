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

// Заголовки таблицы проверок ресурсных правил новой оболочки; порядок совпадает с порядком столбцов,
// «Сервис» и «Покрытие» показаны только при платформенных правилах (ADR 0018).
export const RESOURCE_CHECK_LABELS = {
  heads: {
    window: 'Окно',
    rule: 'Правило',
    service: 'Сервис',
    series: 'Ряд',
    operator: 'Оператор',
    threshold: 'Порог',
    effect: 'Эффект',
    status: 'Статус',
    reason: 'Причина',
    coverage: 'Покрытие (наблюдено / ожидалось)',
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

const KNEE_REASON_WORDS: Record<string, string> = {
  KNEE_TOO_FEW_STAGES: 'ступеней меньше пяти',
  KNEE_STAGE_DATA_MISSING: 'по части ступеней нет достаточных данных',
  KNEE_RUN_NOT_VALID: 'прогон не признан пригодным',
  KNEE_LOAD_NOT_INCREASING: 'достигнутая нагрузка не растёт от ступени к ступени',
  KNEE_NO_BREAK: 'излома в кривой p95 не видно',
  KNEE_BREAK_NOT_MATERIAL: 'рост p95 за изломом слишком мал',
  KNEE_BREAK_WITHIN_NOISE: 'рост p95 за изломом не превышает шум',
}

const AXIS_NAMES: Record<string, string> = { rps: 'RPS' }
const UNIT_NAMES: Record<string, string> = { 'requests/s': 'запросов в секунду' }

const CAPACITY_KNEE_METHOD = 'метод: кусочно-линейная регрессия ln(p95) по достигнутой нагрузке'

export const CAPACITY_LABELS = {
  title: 'Ёмкость: ступени теста максимума',
  region: 'Ступени теста максимума',
  axisLabel: 'Ось нагрузки',
  // Ось и единицу ядро называет по-английски (rps, requests/s): известные значения показаны по-русски, остальные как есть.
  axisValue: (axis: string, unit: string): string => `${AXIS_NAMES[axis] ?? axis} (${UNIT_NAMES[unit] ?? unit})`,
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
  kneeDiagnosticLabel: 'Колено (диагностика)',
  kneeDiagnosticDetected: (lower: string, upper: string, unit: string, lowerStage: string, upperStage: string): string =>
    `между ${lower} и ${upper} ${unit}: излом после ступени ${lowerStage}, деградация со ступени ${upperStage}; не откалибровано, не влияет на границу ёмкости и вердикт; ${CAPACITY_KNEE_METHOD}`,
  kneeDiagnosticNone: (reasons: string[]): string =>
    `не определено (${reasons.map((code) => KNEE_REASON_WORDS[code] ?? code).join('; ') || 'причина не указана'}); диагностика, не откалибровано; ${CAPACITY_KNEE_METHOD}`,
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
  pageSummary: (shown: number, fromMs: number, rollup: number): string =>
    `Показано интервалов на странице: ${shown} (с ${fromMs.toLocaleString('ru-RU')} мс, шаг ${rollup} с; не более 500 на странице).`,
  nextPage: 'Следующая страница интервалов',
  charts: {
    legend: 'Сплошная: текущий · пунктир: baseline. Относительное время, общая шкала; пропуски не интерполируются.',
    openSearchNote: 'Пунктир — события OpenSearch на общей временной оси; совпадение во времени не доказывает причину.',
    rpsTitle: 'Запросы в секунду',
    rpsUnit: 'RPS',
    errorsTitle: 'Ошибки за интервал',
    errorsUnit: 'шт/инт',
    p95Title: 'p95 отклика',
    p95Unit: 'мс',
    axisTitle: 'Время от начала прогона (мс)',
    locale: 'ru-RU',
  },
} as const

export type TablesSubTabKey = 'all' | 'rules' | 'transactions' | 'capacity' | 'resources' | 'trends' | 'diagnostics' | 'normalized'

export const TABLES_NAV_LABELS = {
  navLabel: 'Разделы таблиц',
  tabs: {
    all: 'Все',
    rules: 'Правила и сводка',
    transactions: 'Транзакции',
    capacity: 'Ёмкость',
    resources: 'Ресурсы',
    trends: 'Тренды',
    diagnostics: 'Диагностика и источники',
    normalized: 'Интервалы',
  } as Record<TablesSubTabKey, string>,
} as const

export const SUMMARY_METRICS_LABELS = {
  eyebrow: 'Весь прогон',
  title: 'Сводные метрики',
  p50: 'p50',
  p95: 'p95',
  p99: 'p99',
  latencyHint: 'Перцентиль отклика',
  throughput: 'Пропускная способность',
  throughputHint: 'Окно прогона',
  noData: 'нет данных',
  msUnit: 'мс',
  rpsUnit: 'RPS',
} as const

export const RESOURCE_TABLES_LABELS = {
  eyebrow: 'Снимок ресурсов',
  title: 'Статистика ресурсов и SLA',
  bindingSummary: 'Привязка снимка ресурсов',
  windowPolicyRegion: 'Сводка политики по окнам',
  windowPolicyHeads: ['Окно', 'Начало (epoch, мс)', 'Конец (epoch, мс)', 'Бизнес-вердикт', 'Ресурсный вердикт', 'Итог'],
  summariesRegion: 'Сводка по рядам ресурсов',
  summaryHeads: [
    'Ряд', 'Метрика', 'Единица', 'Сущность', 'Роль', 'Агрегация', 'Окно',
    'Покрытие (наблюдено / ожидалось)', 'Пропущено ячеек', 'Наибольший пропуск',
    'Мин.', 'Макс.', 'Среднее', 'Медиана', 'Q05', 'Q25', 'Q75', 'Q95', 'IQR', 'MAD',
    'Станд. отклонение', 'Наклон в секунду', 'Сдвиг половин окна', 'Причины',
  ],
  checksRegion: 'Проверки ресурсных правил',
  platformRule: (id: string): string => `(платформенное правило ${id})`,
  operators: { gt: 'выше', lt: 'ниже', gte: 'не ниже', lte: 'не выше' } as Record<string, string>,
  effects: { sla: 'SLA', diagnostic: 'диагностика' } as Record<string, string>,
  missingCoverage: (missing: number, longest: string): string => `; пропущено ячеек: ${missing}, макс. разрыв: ${longest}`,
  presumedViolation: '; предположительное нарушение (серия достигнута только за счёт пропущенных ячеек)',
} as const

export const SOURCE_ACQUISITION_LABELS = {
  eyebrow: 'Онлайн-источник',
  title: 'Сбор данных из источников',
  region: (profileId: string): string => `Сбор данных из источника: ${profileId}`,
  profileHeads: ['Профиль', 'Источник', 'Статус', 'Запросы / повторы', 'Ожидание (мс)', 'Лимит запросов'],
  capExceeded: 'Превышен',
  capNotExceeded: 'Не превышен',
  queryHeads: ['Запрос', 'Статус', 'Причина'],
  windowProvenanceRegion: 'Происхождение окна',
  windowProvenanceHeads: ['Параметр окна', 'Значение'],
  windowRows: {
    windowOrigin: 'Источник окна',
    recognizedPeriod: 'Распознанный период',
    requestedMargin: 'Запрошенный запас',
    appliedMargin: 'Применённый запас',
    maxIdleGap: 'Макс. простой',
    detectedIdleGaps: 'Найдено простоев',
    longestIdleGap: 'Наибольший простой',
    autoWindowStatus: 'Статус авто-окна',
    stepOrigin: 'Источник шага',
    requestedStep: 'Запрошенный шаг',
    appliedStep: 'Применённый шаг',
    seriesCells: 'Рядов × ячеек',
    ofWord: 'из',
  },
  resolutionReduced: (requested: string, applied: string): string =>
    `шаг увеличен с ${requested} до ${applied}. Короткие всплески внутри более крупного интервала усредняются для следующих рядов:`,
} as const

export const OPENSEARCH_LABELS = {
  region: (profileId: string): string => `Ошибки OpenSearch: ${profileId}`,
  title: (profileId: string): string => `Ошибки OpenSearch — ${profileId}`,
  summary: (total: number | string, rate: number | string, status: string): string =>
    `Ошибок: ${total} · ${rate} ошибок/мин · Покрытие: ${status}`,
  note: 'Контекст ошибок не меняет вердикт SLA. Приведены ограниченные примеры, а не полный журнал ошибок.',
  groupsRegion: (profileId: string): string => `Группы ошибок OpenSearch: ${profileId}`,
  heads: ['Сервис', 'Тип ошибки', 'Количество', 'Первая / последняя (epoch, мс)', 'Примеры'],
  truncated: ' [обрезано]',
  sourceDocument: 'Исходный документ',
} as const

export const POSTGRES_LABELS = {
  unknownProfile: 'профиль не указан',
  region: (profileId: string): string => `Контекст PostgreSQL: ${profileId}`,
  title: (profileId: string): string => `Изменения PostgreSQL — ${profileId}`,
  noGaps: 'Пропусков покрытия нет',
  configTitle: 'Изменения конфигурации',
  configRegion: 'Изменения конфигурации PostgreSQL',
  configHeads: ['Параметр', 'До', 'После'],
  tablesTitle: 'Изменения таблиц',
  tablesRegion: 'Изменения таблиц PostgreSQL',
  tableHeads: ['Таблица', 'Статус / причины', 'Δ строк', 'Вставлено / удалено / обновлено', 'Изменённые ключи'],
  changes: { inserted: 'вставлено', deleted: 'удалено', updated: 'обновлено' } as Record<string, string>,
  truncated: ' [обрезано]',
  noTables: 'Снимков таблиц нет.',
  statementsTitle: 'Дельты запросов (statements)',
  statementsRegion: 'Дельты запросов PostgreSQL',
  unmatched: (pre: number, post: number): string => `не сопоставлено до / после: ${pre} / ${post}`,
  statementHeads: ['БД / пользователь / запрос', 'Вызовы', 'Суммарное время', 'Строки', 'Shared-блоки (hit / read)', 'Записано temp-блоков'],
  na: 'н/д',
  nested: ' (вложенный)',
  noStatements: 'Сопоставленных дельт запросов нет.',
  pgProfileNote: (status: string, reasons: string): string =>
    `pg_profile: ${status} · ${reasons}. Приложенный HTML доступен только для скачивания и не отображается.`,
} as const

export const DIAGNOSTICS_LABELS = {
  title: 'Диагностика прогона',
  lead: 'Наблюдаемые ассоциации и эпизоды отклонений относительно опорного окна. Они не доказывают причинно-следственную связь и не меняют вердикт SLA. Отсутствие находок не доказывает штатную работу.',
  uncertainty: { NOT_ESTIMATED: 'Не оценивалась' } as Record<string, string>,
  summaryStatus: (status: string, uncertainty: string): string => `Статус: ${status} · Неопределённость: ${uncertainty}`,
  summaryCounts: (evaluable: number, tested: number, anomalies: number, episodes: number, suppressed: number): string =>
    `Пар для оценки: ${evaluable} из ${tested}; проверок аномалий: ${anomalies}; эпизодов: ${episodes}; подавлено коротких эпизодов: ${suppressed}.`,
  anomalyChecksRegion: 'Проверки аномалий относительно опорного окна',
  anomalyCheckHeads: ['Правило / окно / опора', 'Опорная медиана / MAD', 'Опорные ячейки', 'Ячейки оценки', 'Эпизоды / подавлено', 'Статус / причины'],
  na: 'н/д',
  episodesRegion: 'Наблюдаемые эпизоды аномалий',
  episodeHeads: ['Правило / окно / опора', 'Метрика / сущность', 'Интервал UTC (epoch, мс)', 'Длительность / направление', 'Диапазон / единица', 'Макс. абс. дельта', 'Причины'],
  directions: { increase: 'рост', decrease: 'снижение', above: 'выше', below: 'ниже' } as Record<string, string>,
  durationSec: (value: string): string => `${value} с`,
  durationMin: (value: string): string => `${value} мин`,
  rawSummary: 'Исходные данные диагностики',
} as const
