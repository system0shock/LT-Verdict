- Внутренний рефакторинг (W2.2, PR 3 из 3): `RunBundleStore` (1838 строк) разделён на хранилища `AnalysisStore` (запуски,
  анализы, период запуска, история), `BaselineStore` (устаревший файл, слоты, записи условий) и `ReleaseStore` (реестр
  релизов) поверх одного `DataDirectory` с общей блокировкой; `RunBundleStore` остаётся фасадом с прежним интерфейсом.
  Циклы `core` <-> `storage` и `ingest` <-> `storage` разорваны: `AcceptedInput` перенесён в `ingest`, `StoredAnalysis` и
  порт `AnalysisArtifacts` (4 метода, нужны `AnalysisService` и источникам) в `core`; направление зависимостей
  `storage -> core -> ingest` защищает `PackageDependencyTest`. Формат данных на диске, порядок записи, блокировки, типы и
  сообщения исключений не меняются: подтверждено снимком раскладки каталога данных и ответов хранилища
  (`fixtures/storage-layout`), снятым до правок, тестами блокировок и порядковой сверкой тел перенесённых методов. Не входит:
  типизация ошибок хранилища и перенос `baselineOperation`/`releaseOperation` в `core` (PR 4).
