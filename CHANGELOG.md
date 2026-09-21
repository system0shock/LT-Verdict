# Changelog

Все значимые пользовательские изменения LT Verdict фиксируются в этом файле.
Формат основан на Keep a Changelog, версии следуют Semantic Versioning.

## [Unreleased]

### Added

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

### Fixed

- Устранено переполнение памяти при завершении анализа больших JTL со
  множеством sparse one-second buckets.
