// Все русские строки новой оболочки (срез U0) живут только в этом файле.
// Остальной код оболочки ссылается на них по ключам и остаётся ASCII.

export type ShellTabKey = 'overview' | 'tables' | 'compare' | 'rules' | 'advice' | 'setup'

export interface ShellTab {
  key: ShellTabKey
  label: string
  pending: boolean
}

export const SHELL_TABS: readonly ShellTab[] = [
  { key: 'overview', label: 'Обзор', pending: false },
  { key: 'tables', label: 'Таблицы', pending: false },
  { key: 'compare', label: 'Сравнение', pending: false },
  { key: 'rules', label: 'Правила', pending: true },
  { key: 'advice', label: 'ИИ-разбор', pending: false },
  { key: 'setup', label: 'Новый анализ', pending: false },
]

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
} as const
