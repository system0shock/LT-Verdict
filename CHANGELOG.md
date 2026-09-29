# Changelog

Все значимые пользовательские изменения LT Verdict фиксируются в этом файле.
Формат основан на Keep a Changelog, версии следуют Semantic Versioning.

## [Unreleased]

### Added

- Авто-окно выборки источника: `source-request.v3` с `window.origin` `auto`
  выводит окно из периода, распознанного по timestamps самой нагрузки, и
  отказывает до внешних запросов с `AUTO_WINDOW_UNAVAILABLE`,
  `AUTO_WINDOW_MULTI_TEST_SUSPECTED` или `AUTO_WINDOW_SPAN_UNSUPPORTED`,
  требуя явное окно. Распознанный период сохраняется run-артефактом
  `runs/<runId>/run-period.json` (`run-period.v1`), привязан к hash нагрузки и
  переиспользуется при повторном открытии. `v1` и `v2` принимаются без
  изменений; CLI `--source` и file part `source_request` принимают все три
  версии, UI отправляет только `v3` и по умолчанию предлагает авто-окно.
  Смещение часов генератора и системы мониторинга не компенсируется (ADR 0012).
- Provenance окна в `source_summary` для `source-request.v3`: `window_origin`,
  а для авто-окна также распознанный период, заявленный и фактически
  применённый margin, допуск простоя, число и длительность простоев и
  `auto_window_status`. UI показывает эти поля таблицей `Window` в секции
  source acquisition; summaries `v1`/`v2` и ручной импорт OpenSearch-контекста
  полей окна не получают.
- Опубликована схема `source-request.v2` с valid/invalid примерами,
  подключёнными к Ajv contract check: рантайм принимал `v2` с появлением
  multi-profile selection, но опубликованные контракты фиксировали только `v1`,
  поэтому проверять документы `v2` было нечем.
- Optional `trend-plan.v1` и L0-детектор роста ресурсных метрик в пределах SLA:
  объявленные проверки (не более 32) на уже публикуемых `slope_per_second` и
  `split_half_shift` с двумя заранее объявленными порогами материальности и
  требованием согласия знаков. Статусы `TREND_OBSERVED`, `NO_MATERIAL_TREND`,
  `INSUFFICIENT_CELLS`, `UNAVAILABLE` с точными reason-кодами; finding
  `resource_trend` с `effect=diagnostic`. Вердикт и `analysis_coverage` не
  меняются, p-values и оценка неопределённости отсутствуют
  (`uncertainty=NOT_ESTIMATED`), рост не трактуется как утечка или причина.
  Доступно через CLI (`--trend`), API (part `trend_plan`) и UI; артефакты
  `trend-plan.json` и `trend.json`, в identity — модуль
  `resource-trend-evaluation`.
- Подготовка к приёмке: advisory AI jobs/API/UI и изолированный ModelStudio/Qwen
  runtime, consent, отмена и fail-soft без изменения deterministic verdict.
  Изменённый prompt требует отдельной оценки качества; пилот не возобновлялся.
- Jenkins profiles и журнал trigger/queue/build/artifact, восстановление неизвестного
  исхода без автоматического повторного POST; импорт проверенного artifact в RunBundle.
- Локальная N-run/transaction аналитика, OpenSearch chart markers и capability coverage
  JVM/OpenShift; Grafana source links и bounded PNG render по явному запросу.
- Confluence-ready XHTML export и offline onboarding skill/wrapper с проверкой
  подтверждённого hash и добавлением нового внешнего metadata manifest.
- Статический SVG load chart, выбор строк и HTML/AsciiDoc/Confluence exports
  истории; offline `opensearch prepare` для явно включаемой корреляции.

- Сохраняемое ручное подтверждение одинаковых условий baseline/current:
  `CONFIRMED`/`NOT_CONFIRMED`/`UNKNOWN`, с привязкой к точной паре analyses и окон.
- Отбор correlation headlines через bounded MBB, lag-max и Holm; raw evidence
  и SLA verdict не меняются. Версия диагностического модуля identity повышена до `2`.
- Capacity через CLI/API/UI: явные ступени `rps`/`concurrency`/`users`, observed `p05_10s`,
  консервативные границы, совместные SLA и generator guards; raw plan и расчёт
  сохраняются неизменно. Для concurrency/users нужна telemetry, для каждой
  оценки — минимум 300s; автоматический поиск knee не реализован.
- PostgreSQL pre/post: read-only capture, сравнение таблиц/configuration и
  pg_stat_statements, явные ограничения binding/coverage, offline phases и
  download-only pg_profile HTML.
- Несколько HTTP-источников на общей сетке: qualified series/SLA IDs,
  раздельные OpenSearch contexts и повторный offline import через CLI/UI.
- OpenSearch error context: bounded read-only search, counts/rates и
  service/type groups, явная coverage, сохранение и manual offline import.
- Opt-in InfluxDB acquisition через read-only InfluxQL GET `/query`: direct и
  Grafana proxy profiles, env token, left-boundary cells, strict response
  validation, snapshot persistence и offline replay.
- Opt-in Prometheus/VictoriaMetrics acquisition напрямую и через Grafana proxy:
  профили с env credentials, общий bounded HTTP governor, snapshot/provenance,
  статусы неполноты, CLI/UI и offline replay сохранённого snapshot.

- Opt-in correlation plan: описательные Spearman/partial-rank связи,
  ограниченные лаги и explicit-reference аномальные эпизоды с порогами эффекта
  и длительности. Доверительные интервалы raw-оценок не строятся; диагностика
  не меняет SLA.
- Оконное сравнение baseline/current с load/resource deltas, явными порогами
  материальности и ограничениями сопоставимости двух наблюдаемых прогонов.
- `POST /api/jobs` требует `Content-Length` для общего multipart limit;
  запрос неизвестной длины возвращает `411 LENGTH_REQUIRED`.
- Resource snapshot: оконные статистики аппаратных метрик и совместная проверка
  бизнес-/ресурсных SLA через UI и CLI, с coverage, отдельными результатами
  правил и сохранением evidence в JSON/HTML/AsciiDoc.
- Ручное назначение baseline и deterministic статистический выбор одного
  реального прогона из подтверждённой серии; сохранение выбора и overall
  metric deltas в UI без повторного analysis или изменения verdict.
- Local AsciiDoc export сохранённого analysis через UI и `ltv report`, с
  безопасными literal blocks и без создания нового analysis.
- Открытие сохранённых analyses после reload UI, графики RPS/errors/P95 с
  сохранением gaps и пагинацией normalized data.
- Экспорт сохранённого результата в canonical JSON и автономный HTML через UI
  и `ltv report`, без повторного анализа и изменения вердикта.
- Принят local-first baseline PRC/PRD v0.6 и план MVP по Slices 0–10.
- Добавлен минимальный Slice 0: два контракта, два fixtures, offline verifier и
  CI gate.
- Зафиксированы ADR публичных контрактов и отображение Slice 0 на Stage 0.
- Регламент разработки и правила работы AI-агентов.
- Добавлен local-only Slice 1: Web UI и CLI для потокового анализа JMeter JTL
  CSV/XML и Gatling logs, deterministic metrics/verdict, strict `policy.v1`,
  immutable RunBundle, light/dark themes и offline/runtime quality gates.

### Changed

- `step_ms` запроса источника: `source-request.v3` и профили метрических семей
  (`prometheus`, `victoria_metrics`, `influxdb`) требуют целых секунд от 1000 до
  60000, и проверка выполняется до внешних обращений. Профили `opensearch`
  сохраняют прежнее правило — шаг от 1000 ms, делящий окно нацело, — поэтому
  множество их успешных сценариев не изменилось. Запрос `v1`/`v2` к метрическому
  профилю с шагом вроде 1500 ms раньше проходил валидацию, выполнял реальную
  HTTP-выборку и только затем отклонялся сеткой snapshot; теперь он отклоняется
  до выборки.

### Fixed

- UI снова показывает состояние и кнопку `Cancel analysis` активной задачи
  анализа после перезагрузки страницы (UI-JOB-RELOAD-01). Для этого добавлен
  read-only маршрут `GET /api/jobs?state=active` (ADR 0015); контракт
  `JobStatus` не менялся. Показывается самая старая активная задача.
- AI-ENV-01: на Windows имена переменных окружения (`Path`, `SystemRoot`, `WINDIR`) сопоставляются без учёта регистра, поэтому
  дочерний AI runtime получает `PATH` и находит Docker вместо ложного `UNAVAILABLE — DOCKER_UNAVAILABLE`.
- Проверка роста метрики (`trend-plan.v1`) теперь отказывает с
  `INSUFFICIENT_CELLS` и reason `TREND_HALF_CELLS_NOT_MET`, если в одной из
  половин окна меньше `floor(min_cells / 2)` наблюдаемых ячеек: раньше минимум
  проверялся только по всему окну, и сдвиг половин мог опираться на единичную
  точку. `resource_summary` не меняется.
- Timestamps в диапазоне epoch-seconds `1000000000..99999999999` отклоняются как
  `INVALID_SAMPLE_TIMESTAMP` вместо тихой интерпретации как миллисекунды 1970
  года с валидным `PASS`/`FAIL`. Границы опубликованы в `limits`
  `analysis-identity.v1`, поэтому прежние `analysis_id` не переиспользуются.
- InfluxQL-выражения с `fill(...)`, фабрикующим значения (`0`, `previous`,
  `linear`, число), отклоняются как `SOURCE_CONFIG_INVALID`; разрешены только
  сохраняющие пропуски `fill(null)` и `fill(none)`.
- `analysis_coverage` отражает деградацию онлайн-сбора: `PARTIAL`/`FAILED`
  статус и исчерпанный request budget дают `SOURCE_ACQUISITION_PARTIAL`,
  `SOURCE_ACQUISITION_FAILED` и `SOURCE_REQUEST_CAP_EXCEEDED`, поэтому
  `COMPLETE` больше не маскирует неполный сбор.
- Устранено переполнение памяти при завершении анализа больших JTL со
  множеством sparse one-second buckets.
