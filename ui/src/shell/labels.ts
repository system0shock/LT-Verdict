// Все русские строки новой оболочки (срез U0) живут только в этом файле.
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
