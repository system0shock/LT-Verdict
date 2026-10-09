- Внутренний рефакторинг (W2.1, срез 2, под-срез 2a): findings и evidence семейств policy и resource
  (`metric_summary`, `policy_check`, `diagnostic`, `rule_window_check`, `window_policy_summary`,
  `resource_summary`, `resource_policy_check`, `policy_failure`, `resource_threshold_violation`)
  собираются из `@Serializable`-классов с закрытыми иерархиями `AnalysisFinding` и `AnalysisEvidence`
  вместо ручных `buildJsonObject`; литерал `type` задаётся один раз в `@SerialName` класса. Байты
  `analysis-result.json`, `analysis_id`, формат CLI и HTTP, схемы не меняются; это подтверждено
  сравнением с замороженной копией прежних построителей на широкой матрице и со снимком, снятым до
  правок. Добавлен `ui/src/types.items.generated.ts` (генерируется из Kotlin, проверяется тестом и
  `npm run test:contracts` на реальных элементах); в рукописный `ui/src/types.ts` добавлены только
  необязательные поля `window_id` у `MetricSummaryEvidence` и `sample_count`, `min_samples` у
  `WindowPolicySummaryEvidence`, которые движок уже писал. Не входит: остальные семейства evidence
  (диагностика, ёмкость, тренды, источники), типизация границ `PolicyEvaluation` и ответов HTTP.
