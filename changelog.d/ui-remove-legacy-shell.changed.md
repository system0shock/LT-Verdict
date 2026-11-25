- Удалён прежний английский интерфейс (`?shell=old`), переключатель оболочек в
  `localStorage` (`ltv.shell` / `resolveNewShell`), компоненты `RunSetup.vue` и
  `GrafanaPanel.vue`, а также устаревшие резервные ветки и английские словари
  оболочки (`EN_ANALYTICS_LABELS`, `EN_DOWNLOAD_LABELS`, `EN_COMPARE_LABELS`).
  Русская оболочка с вкладками теперь является единственным интерфейсом; API,
  серверные маршруты (включая `/api/grafana`), схемы, канонический JSON и
  вычисление хэшей не менялись.
