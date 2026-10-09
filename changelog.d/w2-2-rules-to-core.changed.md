- Внутренний рефакторинг (W2.2, PR 2 из 3): доменные правила baseline и release вынесены из `web/BaselineRoutes.kt` и
  `web/ReleaseRoutes.kt` в `core/BaselineRules.kt`, `core/ReleaseRules.kt` и `core/RuleFailure.kt`: разбор и проверка
  тела выбора baseline (режимы manual и statistical), допуск кандидата, слот анализа по серии и arm, разбор окон
  сравнения и порогов, разбор и нормализация полей release, факты анализов release (по одному анализу за раз),
  представление release. Нарушение правила в `core` это `RuleFailure` без HTTP, `web` превращает его в тот же статус
  (400, 422, 500) и то же тело ошибки одним `catch`. Чтения хранилища и отображение его исключений в коды
  (`baselineOperation`, `releaseOperation`) остаются в маршрутах: перенос добавил бы зависимость `core` от `storage`
  (это PR 3). Пути, статусы, заголовки, тела ответов и коды ошибок HTTP не меняются: подтверждено снимками
  `fixtures/http-layer` (маршруты, ответы, правила), снятыми до правок.
