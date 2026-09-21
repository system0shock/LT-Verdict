# Multiple Sources Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Несколько согласованных источников одного анализа с offline replay.

**Architecture:** Последовательный сбор через существующий SourceHttp с общим
origin governor; один объединённый resource snapshot, отдельные context JSON.
Повторный импорт использует те же файлы, дополнительный bundle format не нужен.

**Tech Stack:** Existing Kotlin JSON/JDK HTTP/Vue; без новых dependencies.

**Spec:** `docs/superpowers/specs/2026-09-05-remaining-sources-design.md`.

## Public contracts and limits

- `source-request.v1` остаётся неизменным. `source-request.v2` содержит
  `profile_ids` (1..16 unique HTTP profile IDs), start/end/step как v1.
  Профили нормализуются по ID; неизвестные IDs отклоняются до HTTP.
- Внутренний SourceRequest сохраняет существующий constructor и получает
  `additionalProfileIds: List<String> = emptyList()`; single-profile path
  сохраняет текущую identity и имена файлов.
- Для нескольких профилей series/rule IDs получают prefix
  `escapedProfileId/`: percent и slash в profile ID экранируются как `%25` и
  `%2F`. Original query/rule IDs сохраняются после prefix. Полный ID <=128
  UTF-8 bytes, общий series/cell/rule cap проверяется до сети. Duplicate
  qualified IDs запрещены. Metric/unit/entity/labels/aggregation не меняются.
- Одна общая сетка, единственное окно `full`; SLA rules ссылаются на qualified
  series IDs. Объединённый snapshot проходит existing validator. Если actual
  labels/values превышают 16 MiB, сохранить bounded all-null snapshot и
  explicit failure summary, не терять load analysis.
- `source-acquisition.json` содержит aggregate source_summary, budget totals,
  qualified query IDs и `profiles` с исходными summaries. Source-level request
  caps остаются отдельными; rate/concurrency/retry limits общие по origin.
- Raw responses нумеруются последовательно `source-response-N.json` (1..512).
  Persisted raw total <=64 MiB. Не поместившийся raw artifact пропускается с
  explicit `SOURCE_RAW_ARTIFACT_LIMIT_EXCEEDED` diagnostic в summary; валидные
  нормализованные факты не удаляются. Responses обрабатываются по одному
  профилю, не собираются параллельно в памяти.
- При одном OpenSearch context остаётся `opensearch-errors.json`; при нескольких
  — `opensearch-errors-N.json`, 1..16, sorted profile ID. Duplicate profile IDs
  при manual import запрещены. Каждый context <=16 MiB; общий context <=32 MiB.
  При превышении online aggregate context cap источник отмечается FAILED и
  его context не сохраняется; не создавать fake zero context.
- CLI `--source-context FILE` допускается несколько раз. UI input `multiple`,
  multipart repeated `source_context` (до16) с aggregate context cap32 MiB.
  Совместим с offline resources/correlation; несовместим с HTTP online request.
- Existing single-context download сохраняется. Для нескольких контекстов
  fixed route `/source-context/{index}` допускает integer1..16 и только
  существующий manifest-validated artifact. UI строит ссылки по sorted context
  order; download никогда не принимает произвольный filename/path.
- PostgreSQL pre/post capture и отдельные immutable artifacts входят в его
  собственный Task3. One PostgreSQL pair per analysis в первом MVP; он может
  сосуществовать с перечисленными HTTP sources. Автоматически pre не снимается.

## Task 1: Multi-profile HTTP collection

**Files:** Existing SourceConfig/PromqlSource/SourceAnalysis/AnalysisService,
CommandLine/LocalApi, UI RunSetup/App/api/types; focused source/CLI/API tests,
source request example, user guide, CHANGELOG.

- [ ] RED v2 request parse, duplicate/unknown IDs/preflight caps, real HTTP
  metric+error acquisition with independent failures and same-origin governor.
- [ ] Add bounded existing collector branch for multiple profiles; preserve
  single-profile path. Reuse resource snapshot JSON validation, do not add
  connector registry or hierarchy.
- [ ] GREEN qualified rule binding, deterministic selection order, gaps,
  aggregate caps, immutable artifacts and partial failure with load analysis.
- [ ] UI multi-selection and real browser metric+context workflow; document
  qualified IDs and unchanged physical units/time semantics.

## Task 2: Multi-context manual replay

**Files:** Existing SourceAnalysis/AnalysisService, CommandLine/LocalApi,
UI RunSetup/App/AnalysisView/api/types and matching tests/docs.

- [ ] RED multiple validated contexts plus resource snapshot; wrong hashes,
  duplicate profiles, too many/oversized contexts, indexed download bounds.
- [ ] Extend existing import helper to bounded list; retain single-file API.
  Persist exact normalized facts with manual summary/no network.
- [ ] GREEN CLI/API/browser replay and reopen saved analysis. Snapshot and
  contexts must retain identical analytical facts, not online request counters.
- [ ] Fresh full test/build/lint/browser/docs/secret checks and one scoped
  spec/quality review. PostgreSQL live gate remains separately explicit.

## Progress

Implemented in `feat/remaining-sources`: v2 parsing/preflight, metric+error
collection, qualified SLA binding, capped artifacts, repeated manual contexts,
fixed downloads and UI. TDD covers selection order, unknown/duplicate IDs,
preflight caps, independent HTTP failure, CLI/API replay and download bounds.
Query expression hashes remain in combined provenance; semantic metric identity
still deliberately ignores provenance, matching the existing snapshot contract.

2026-09-06: full `test check installDist -x npmCi` PASS (289 tests,
286 passed/3 skipped); full browser suite41/41 (including real-backend sources3/3) PASS.
Markdown and UI lint passed. Final independent integration review: COMPLIANT /
APPROVED, no Critical/Important findings. Subsequent PostgreSQL16.15 live suite
11/11 and pg_profile4.8 CLI/hash smoke passed; final full test/check289 tests,
287 passed/2 Windows skips. See PostgreSQL plan. External
CI/secret scanner, PostgreSQL15 and TLS remain unverified.
No capacity/statistical methods/AI or new export format added.
