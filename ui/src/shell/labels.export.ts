// Русские строки действия «Экспорт» в шапке новой оболочки (срез U7b).

export const EXPORT_LABELS = {
  html: 'Экспорт HTML',
} as const

// Блок ссылок скачивания «Обзора» (новая оболочка). Английские значения остаются в прежнем интерфейсе.
export interface DownloadLabels {
  group: string
  format: (format: string) => string
  resourceSnapshot: string
  capacityPlan: string
  capacityResult: string
  trendPlan: string
  trendResult: string
  openSearchContext: (profileId: string | null) => string
  postgresPre: string
  postgresPost: string
  postgresContext: string
  pgProfile: string
}

export const DOWNLOAD_LABELS: DownloadLabels = {
  group: 'Скачивание анализа',
  format: (format) => `Скачать ${format === 'asciidoc' ? 'AsciiDoc' : format.toUpperCase()}`,
  resourceSnapshot: 'Скачать снимок ресурсов',
  capacityPlan: 'Скачать план ёмкости',
  capacityResult: 'Скачать результат ёмкости',
  trendPlan: 'Скачать план трендов',
  trendResult: 'Скачать результат трендов',
  openSearchContext: (profileId) => `Скачать контекст OpenSearch${profileId === null ? '' : ` — ${profileId}`}`,
  postgresPre: 'Скачать снимок PostgreSQL до теста',
  postgresPost: 'Скачать снимок PostgreSQL после теста',
  postgresContext: 'Скачать контекст PostgreSQL',
  pgProfile: 'Скачать отчёт pg_profile',
}

export const EN_DOWNLOAD_LABELS: DownloadLabels = {
  group: 'Analysis downloads',
  format: (format) => `Download ${format === 'asciidoc' ? 'AsciiDoc' : format.toUpperCase()}`,
  resourceSnapshot: 'Download resource snapshot',
  capacityPlan: 'Download capacity plan',
  capacityResult: 'Download capacity result',
  trendPlan: 'Download trend plan',
  trendResult: 'Download trend result',
  openSearchContext: (profileId) => `Download OpenSearch context${profileId === null ? '' : ` — ${profileId}`}`,
  postgresPre: 'Download PostgreSQL pre capture',
  postgresPost: 'Download PostgreSQL post capture',
  postgresContext: 'Download PostgreSQL context',
  pgProfile: 'Download pg_profile report',
}

// Панель «Сохранённая аналитика» «Обзора» (новая оболочка): заголовок, поля и кнопки. Вложенные таблицы остаются английскими.
export interface AnalyticsLabels {
  title: string
  lead: string
  comparableRuns: string
  transactionFilter: string
  transactionRows: string
  refresh: string
  refreshing: string
  loading: string
  failed: string
  exportJson: string
  exportHtml: string
  exportAsciidoc: string
  exportConfluence: string
  scanTruncated: (limit: number, bytes: string) => string
  scanTruncatedNote: string
  noDynamics: string
  noBaseline: string
  noOverlay: string
}

export const ANALYTICS_LABELS: AnalyticsLabels = {
  title: 'Сохранённая аналитика',
  lead: 'Динамика и сравнения строятся только по сохранённым локальным анализам. Обновление не обращается к внешним источникам.',
  comparableRuns: 'Сопоставимых прогонов',
  transactionFilter: 'Фильтр по транзакции',
  transactionRows: 'Строк транзакций',
  refresh: 'Обновить аналитику',
  refreshing: 'Загрузка аналитики…',
  loading: 'Загружаем сохранённую аналитику…',
  failed: 'Не удалось загрузить сохранённую аналитику.',
  exportJson: 'Экспорт аналитики в JSON',
  exportHtml: 'Экспорт динамики в HTML',
  exportAsciidoc: 'Экспорт динамики в AsciiDoc',
  exportConfluence: 'Экспорт динамики в Confluence',
  scanTruncated: (limit, bytes) => `Просмотр локальной истории остановлен на заданных пределах (до ${limit} анализов или ${bytes} байт метаданных).`,
  scanTruncatedNote: 'Сопоставимые прогоны могут быть пропущены: последние N берутся только из просмотренной части истории.',
  noDynamics: 'Динамика N прогонов недоступна: у этого анализа нет корректных метаданных прогона.',
  noBaseline: 'Выберите сохранённый baseline, чтобы сравнить транзакции.',
  noOverlay: 'Наложение OpenSearch недоступно: у этого анализа нет корректных метаданных прогона.',
}

export const EN_ANALYTICS_LABELS: AnalyticsLabels = {
  title: 'Saved-run analytics',
  lead: 'Dynamics and comparisons use saved local bundles only. Refreshing this view does not query external sources.',
  comparableRuns: 'Comparable runs',
  transactionFilter: 'Transaction filter',
  transactionRows: 'Transaction rows',
  refresh: 'Refresh analytics',
  refreshing: 'Loading analytics…',
  loading: 'Loading saved analytics…',
  failed: 'Saved analytics request failed.',
  exportJson: 'Export analytics JSON',
  exportHtml: 'Export dynamics HTML',
  exportAsciidoc: 'Export dynamics AsciiDoc',
  exportConfluence: 'Export dynamics Confluence',
  scanTruncated: (limit, bytes) => `Local history scan stopped at configured bounds (up to ${limit} analyses or ${bytes} metadata bytes).`,
  scanTruncatedNote: 'It may omit comparable runs, so latest-N is limited to the scanned history.',
  noDynamics: 'N-run dynamics are unavailable because this analysis has no valid run metadata.',
  noBaseline: 'Select a saved baseline to compare transactions.',
  noOverlay: 'OpenSearch overlay is unavailable because this analysis has no valid run metadata.',
}
