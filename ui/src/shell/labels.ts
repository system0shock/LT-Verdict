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
  { key: 'rules', label: 'Правила', pending: true },
  { key: 'advice', label: 'ИИ-разбор', pending: false },
]

// Вкладка при открытии страницы, пока прогон не выбран; после загрузки результата открывается «Обзор».
export const SHELL_DEFAULT_TAB: ShellTabKey = 'setup'

export const SHELL_LABELS = {
  navLabel: 'Разделы',
  pendingBadge: 'в разработке',
  overviewEmpty: 'Прогон не выбран. Выберите прогон в списке слева или откройте вкладку «Новый анализ».',
  rulesPendingTitle: 'Правила: раздел в разработке',
  rulesPendingText: 'Редактор правил появится в одном из следующих срезов. Пока политику можно задать на вкладке «Новый анализ».',
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
