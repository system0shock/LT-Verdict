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
