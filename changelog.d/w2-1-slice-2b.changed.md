- Внутренний рефакторинг (W2.1, срез 2, под-срез 2b): findings и evidence диагностики
  (`correlation_pair`, `correlation_candidate`, `anomaly_episode`, `anomaly_check`,
  `window_metric_summary`, `diagnostic_summary`, `correlation_headline_selection`), ёмкости
  (`capacity_summary`, `capacity_knee_diagnostic`) и трендов (`trend_check`, `resource_trend`,
  `trend_summary`) собираются из `@Serializable`-классов с закрытыми иерархиями `DerivedFinding` и
  `DerivedEvidence` вместо ручных `buildJsonObject`; литерал `type` задаётся один раз в `@SerialName`
  класса. Числа произвольной точности (`target`, границы ёмкости, нагрузки knee) печатаются
  дословно. Байты `analysis-result.json`, `capacity.json`, `trend.json`, `identity.json`,
  `analysis_id`, формат CLI и HTTP, схемы не меняются; это подтверждено снимком, снятым до правок
  (продьюсеры и целые прогоны), и сравнением с замороженными копиями прежних построителей на широкой
  матрице. Добавлен `ui/src/types.derived-items.generated.ts` (генерируется из Kotlin, проверяется
  тестом и `npm run test:contracts` на реальных элементах); в рукописный `ui/src/types.ts` добавлены
  только поля `metric`, `load_axis`, `unit`, `diagnostic_only`, `sse_ratio`, `excess_factor`,
  `points`, `parameters` у `CapacityKneeDiagnosticEvidence`, которые движок уже писал. Не входит:
  evidence источников (запросы Prometheus, OpenSearch, PostgreSQL), `resource_binding`, типизация границ
  `DiagnosticEvaluation`, `CapacityAnalysis`, `TrendAnalysis` и ответов HTTP.
