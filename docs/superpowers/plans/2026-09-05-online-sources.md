# Online sources — implementation plan

> Execute using subagent-driven-development; independent file ownership below.

Цель: законченная первая поставка Prometheus/VM direct и Grafana proxy по
[принятому дизайну](../specs/2026-09-05-online-sources-design.md).
База ae6a076, существующий worktree, ветка feat/online-sources. Baseline JVM test
успешен (UP-TO-DATE). Пользователь разрешил параллельную реализацию и один итоговый
review с адресными исправлениями вместо повторных волн. Worker commits запрещены.

## Контракты и пределы до реализации

Новых production dependencies нет: JDK HttpClient, существующий kotlinx JSON.
Все типы internal в io.ltverdict.sources.

- `SourceRequest(profileId: String, startEpochMillis: Long, endEpochMillis: Long,
  stepMillis: Long)`; JSON source-request.v1: schema_version, profile_id,
  start_epoch_ms, end_epoch_ms, step_ms. Ровная сетка, step >= 1000 ms,
  start >= 0, end > start, не более 100000 точек/500000 cells.
- `SourceProfile(id, sourceKind, transport, baseUrl, datasourceUid, auth,
  allowInsecureHttp, governor, queries, rules)`; конфигурация
  source-connections.v1 содержит connections. Поля snake_case.
- `SourceQuery(id, expression, metric, unit, entity, role, aggregation, labels)`:
  один query = одна серия с явными id/entity; labels — ожидаемые пары ответа.
  Более одной серии — AMBIGUOUS_SERIES, пустой ответ — missing.
  expression содержит `$__interval`, заменяемый на step ms. Это декларация
  семантики пользователем, не доказательство корректности произвольного PromQL.
- profile rules используют существующий ResourceRuleV1 JSON; отсутствие rules=[]
  допустимо. Snapshot содержит окно full на весь запрошенный интервал.
- `readSourceProfiles(InputStream): List<SourceProfile>`,
  `readSourceRequest(InputStream): SourceRequest`; некорректный ввод бросает
  IllegalArgumentException с безопасным SOURCE_* кодом без входных данных.
- `SourceHttp(profiles: List<SourceProfile>)` живёт весь backend lifetime.
  `get(profile, queryParameters: Map<String,String>, budget: SourceBudget,
  checkCancelled: () -> Unit = {}): ByteArray` обращается только к query_range.
  `SourceBudget(maxRequests: Int? = null)` содержит requestCount, retries,
  throttleWaitMillis, capExceeded. SourceHttpFailure(code: String) без raw errors.
- `PromqlSource(profiles: List<SourceProfile>, http: SourceHttp)`;
  `acquire(request: SourceRequest, loadInputSha256: String,
  checkCancelled: () -> Unit = {}): SourceAcquisition`.
  `SourceAcquisition(snapshot: ResourceValidation.Valid, evidence: JsonObject,
  artifacts: Map<String,ByteArray>)`. Артефакты только фиксированные безопасные
  имена source-acquisition.json и source-response-N.json, без credentials/URLs.
  evidence содержит status COMPLETE/PARTIAL/FAILED, profile_id, source_kind,
  transport, queries [{id,status,reason?}], request_count, retries,
  throttle_wait_ms, cap_exceeded. Успешные raw matrix ответы сохраняются;
  ошибки не сохраняют bodies; failed query остаётся all-null серией.
- Profiles <= 1 MiB, 16 profiles, 32 queries/profile; request <= 16 KiB.
  HTTP response <= 16 MiB, aggregate successful responses <= 64 MiB.
  Shared resource snapshot validator остаётся источником остальных limits.
- Governor: normalized scheme/host/effective-port, strictest limits origin,
  defaults .5 RPS/burst1/concurrency1/30s/3 attempts, restart waits interval,
  Retry-After, каждый retry потребляет token, cancellation и bounded body.
  maxRequestsPerRun по умолчанию отсутствует. TLS normal, redirects NEVER;
  env-only none/bearer/basic auth, plaintext credentials только explicit opt-in.
- CLI: analyze --connections FILE --source FILE (вместо --resources),
  ui --connections FILE. Offline flags сохраняются.
- API GET /api/sources возвращает {profiles:[{id,source_kind,transport}]}.
  POST /api/jobs принимает optional source_request JSON part (вместо snapshot).
  Корреляционный план требует конкретный snapshot hash: онлайн сначала
  acquisition, затем повторный offline анализ сохранённого snapshot.
- Optional AnalysisRequest sourceRequest/sourceAcquisition; сетевой wrapper
  до AnalysisService. Acquisition hash входит в identity, raw/provenance
  записываются существующей atomic transaction. evidence.source_summary
  присутствует только для online acquisition; offline bytes не меняются.
- GET /api/runs/{run}/analyses/{id}/resource-snapshot отдаёт проверенный
  сохранённый snapshot, не запрашивает источник. CLI replay --resources из
  сохранённой analysis directory. Не создавать новый storage framework.

## Tasks / ownership / RED–GREEN

1. HTTP и profiles: SourceConfig.kt, SourceHttp.kt, их tests.
   Сначала tests: invalid URLs/env auth, origin sharing, retry/restart/cancel,
   timeout/body caps, safe errors; затем минимальная реализация.
2. PromQL: PromqlSource.kt и tests. Сначала матрица с правыми границами
   1/2 секунды -> snapshot cells 0/1 секунды, gaps/NaN, duplicate/off-grid,
   ambiguous labels, HTTP failures/all-null. Затем collector/shared validator.
3. Root: AnalysisService/identity, CLI/API и интеграционные tests. Сначала
   acquire/persist/reload/no-network tests, offline identity regression;
   затем wrapper, atomic artifacts, source status, snapshot download.
4. UI после фиксации API: существующие RunSetup/App/api/types/AnalysisView,
   выбор профиля, UTC epoch fields, source status и snapshot download;
   existing component style, без redesign. Browser fixture E2E.
5. Документация/schema/examples/CHANGELOG, fresh full verification и один
   independent review безопасности, целостности и требований.

Gradle — один слот, workers запрашивают у root. UI npm проверки независимы.
Workers не создают subagents и не редактируют чужие файлы. Root проверяет diff.
Команды: gradlew -PnpmOffline=true --offline --no-daemon test check installDist
(-x npmCi), UI typecheck/lint/E2E, existing docs/contracts/secret checks.
Live production Grafana route и CI remote не считаются проверенными локально.
Остальные sources, capacity, AI, новые статистические методы вне этого среза.

## Результат локальной поставки — 2026-09-05

Tasks 1–5 выполнены. Production dependencies не добавлены. Общие контракты
уточнены: auth discriminator `type`; source kind `victoria_metrics`; evidence
использует существующие `id`/`type` в массиве `evidence`. Непустые PromQL warnings
отклоняются. При общем snapshot byte limit возвращается preflight all-null
snapshot с `SOURCE_SNAPSHOT_LIMIT_EXCEEDED`, raw artifacts сохраняются.

Один итоговый независимый Sol/max review: 0 Critical, 2 Important и 1 Minor.
Исправлены aggregate snapshot failure isolation, ошибочный cross-origin connect
timeout и ссылка на отсутствующий snapshot для load-only результата.
Для каждого воспроизведён RED, затем GREEN; дополнительных волн review нет.

Свежие проверки финального кода:

- `gradlew -PnpmOffline=true --offline --no-daemon ktlintFormat test check installDist -x npmCi`:
  SUCCESS; 217 JVM tests = 215 passed + 2 прежних Windows symlink skips.
- `npm run e2e`: 34/34, включая реальный local HTTP acquire/persist/download/reload,
  keyboard order, labels, local security и регрессии SLA/diagnostics/baseline.
- UI `typecheck`, `lint`, `test:contracts`: SUCCESS.
- `python tools/verify_slice0.py`: OK; Python unittest discovery: 4/4.
  Первый sandbox запуск unittest не имел доступа к Temp; повтор с доступом
  прошёл без изменений кода.
- Markdown lint изменённых документов, локальные ссылки и `git diff --check`: OK.
- Локальный scan исходников/новых контрактов на private keys/AWS keys: совпадений нет.
  Полный remote Gitleaks/Lychee/CI не запускался; утилит нет в PATH.

Боевой Grafana plugin/auth route и live источники не проверялись, credentials
не запрашивались. Формальный Stage 2 и весь блок источников не закрыты:
InfluxDB/PostgreSQL/OpenSearch ещё впереди. Branch/worktree сохраняются,
push/merge/tag/release не выполнялись. Изменения пользователя в основном
checkout не затронуты.
