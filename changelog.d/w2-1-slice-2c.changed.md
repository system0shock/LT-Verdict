- Внутренний рефакторинг (W2.1, срез 2, под-срез 2c): evidence привязки ресурсов `resource_binding`, сводка источников
  `source_summary` (Prometheus и InfluxQL, живой и импортированный OpenSearch, несколько профилей в одном snapshot) и контекст
  `opensearch_errors` собираются из `@Serializable`-классов с закрытой иерархией `InputEvidence` вместо ручных
  `buildJsonObject`; литерал `type` задаётся один раз в `@SerialName` класса. Ставки OpenSearch произвольной точности
  (`error_rate_per_minute`, `rate_per_minute`) печатаются дословно. Байты `analysis-result.json`, `identity.json`,
  `analysis_id`, `source-acquisition.json`, `opensearch-errors.json`, формат CLI и HTTP, схемы не меняются; это подтверждено
  снимком, снятым до правок (продьюсеры и целые прогоны с онлайн-источником и импортом контекста), и сравнением с замороженными
  копиями прежних построителей на широкой матрице. Добавлен `ui/src/types.input-items.generated.ts` (генерируется из Kotlin,
  проверяется тестом и `npm run test:contracts` на реальных элементах); в рукописный `ui/src/types.ts` добавлены только
  необязательные поля `arm`, `start_epoch_ms`, `end_epoch_ms`, `step_ms`, `rule_spans`, `expression_sha256` у
  `SourceSummaryEvidence` и `schema_version`, `load_input_sha256`, `start_epoch_ms`, `end_epoch_ms`, `step_ms`, `timeline` у
  `OpenSearchEvidence`, которые движок уже писал. Не входит: `postgres_context`, provenance окна (подмешивается к сводке как
  `JsonObject`), вложенные сводки профилей в агрегате `multiple`, правка сводок после построения (`withSourceLimit`, импорт
  нескольких контекстов).
