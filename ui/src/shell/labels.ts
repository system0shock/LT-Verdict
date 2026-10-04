// Все русские строки новой оболочки (срезы U0 и U1) живут только в этом файле.
// Остальной код оболочки ссылается на них по ключам и остаётся ASCII.

export type ShellTabKey = 'overview' | 'tables' | 'compare' | 'rules' | 'advice' | 'setup'

export interface ShellTab {
  key: ShellTabKey
  label: string
  pending: boolean
}

// Порядок как в макете: главное действие «Новый анализ» первой вкладкой.
export const SHELL_TABS: readonly ShellTab[] = [
  { key: 'setup', label: 'Новый анализ', pending: false },
  { key: 'overview', label: 'Обзор', pending: false },
  { key: 'tables', label: 'Таблицы', pending: false },
  { key: 'compare', label: 'Сравнение', pending: false },
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
  },
  emptyWindowHint: 'В окне нет нагрузки: проверьте границы окна и синхронизацию часов генератора и кластера.',
  emptyBaselineWindow: 'Пусто окно эталона (baseline).',
  emptyCurrentWindow: 'Пусто текущее окно (current).',
  oldRulesHint:
    'Эталон создан по старым правилам анализа: пересчитайте его (заново проанализируйте исходные данные и закрепите baseline).',
} as const

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
  diagnosticNote: 'Тренды и корреляции показывают, что значения менялись вместе или в одну сторону. Это диагностика: она не доказывает причину и не влияет на вердикт.',
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
  correlationTitle: (series: string, loadMetric: string) => `Корреляция: ${series} и ${loadMetric}`,
  correlationDetail: (windowId: string, rho: string, paired: number, expected: number) =>
    `окно ${windowId}: коэффициент ${rho}; пар ячеек ${paired} из ${expected}`,
  openRules: 'Открыть таблицу правил',
  openResources: 'Открыть ресурсы',
  openCapacity: 'Открыть ёмкость',
  openTrends: 'Открыть тренды',
  openDiagnostics: 'Открыть диагностику',
  openSources: 'Открыть источники',
  openSetup: 'Открыть «Новый анализ»',

  // Ключевые метрики
  metricsTitle: 'Ключевые метрики прогона',
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
  loadPartial: 'Показаны не все интервалы: страница данных ограничена 500 интервалами. Диапазон и шаг настраиваются на вкладке «Таблицы».',
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
  aiText: 'ИИ-разбор не входит в анализ и отправляется только с вашего явного согласия. Дайте согласие здесь, и разбор запросится сам после завершения анализа; без галки его можно запросить на вкладке «ИИ-разбор».',
  aiConsentLabel: 'Разрешаю отправить evidence этого анализа и системный промпт в Alibaba ModelStudio (Singapore) сразу после его завершения.',

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
  willAdvice: 'После завершения анализа сам запросится ИИ-разбор (данные уйдут во внешний сервис по вашему согласию).',
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
