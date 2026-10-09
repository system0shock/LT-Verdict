- Внутренний рефакторинг (W2.2, PR 1 из 3): `installLocalApi` (около 2700 строк в `LocalApi.kt`) разрезан на
  файлы маршрутов по ресурсам (`RunRoutes`, `ArtifactRoutes`, `JobRoutes`, `SourceRoutes`, `GrafanaRoutes`,
  `JenkinsRoutes`, `BaselineRoutes`, `ReleaseRoutes`, `AnalyticsRoutes`, `AdviceRoutes`, `ResourceSeriesRoutes`)
  и общие помощники в `ApiSupport.kt`; перехватчик безопасности, `/api/bootstrap` и замыкающий `/api/{...}`
  остаются в `installLocalApi`. `receiveJob` (320 строк) разделён на чтение частей, проверку привязок и сборку
  запроса. Самая длинная функция в `web/` теперь около 175 строк (было 1101 и 319). Пути, методы, статусы,
  заголовки и тела ответов HTTP не меняются: это подтверждено снимком таблицы маршрутов и ответов всех
  маршрутов, снятым до правок (`fixtures/http-layer`, `LocalApiSnapshotTest`). Не входит: доменные правила
  baseline и release в `core` и разделение `RunBundleStore` (отдельные PR W2.2).
