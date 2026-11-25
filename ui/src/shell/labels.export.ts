// Русские строки действия «Экспорт» в шапке (срез U7b).

export const EXPORT_LABELS = {
  html: 'Экспорт HTML',
} as const

// Блок ссылок скачивания на вкладке «История».
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

// Панель «Сохранённая аналитика» на вкладке «История»: заголовок, поля и кнопки. Вложенные таблицы остаются английскими.
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
  wholeRunNote: string
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
  wholeRunNote: 'Метрики динамики и сравнения транзакций взяты по всему прогону, справочно: у этого анализа заданы стадии нагрузки, окно steady оценивается отдельно, разгон и остановка входят в эти метрики.',
}
