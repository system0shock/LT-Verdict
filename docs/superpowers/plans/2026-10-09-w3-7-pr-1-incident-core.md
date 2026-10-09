# W3.7, PR 1: ядро синтеза инцидентов и схема, без смены identity (мини-план)

> Это срез PR 1 влитого плана [W3.7](2026-10-09-w3-7-incidents.md); ruling'и S1-S9, U1-U2, H1, раздел «PR 1» и таблица
> тест-критериев берутся оттуда без изменений. Здесь только то, что относится к PR 1, и решения, принятые при чтении кода.
> Контракт: [ADR 0029](../../adr/0029-incident-v0.md) (Accepted) и [`incident.v1`](../../contracts/incident/v1/incident.schema.json).

## Блок для AGENTS.md

```text
REQUESTED: чистая функция synthesizeIncidents(run_validity, findings, evidence) в core с выводом JsonObject, лимитами, отрицательными
  свидетельствами, следующими проверками и шаблонами текстов по ADR 0029; тесты по таблице критериев плана для PR 1; $ref из
  схемы результата на схему инцидента; параметр check_wording в tools/verify_slice0.py.
REQUIRED TO ACHIEVE IT:
  - core/IncidentSynthesis.kt и core/IncidentTexts.kt (новые): атомы, кластеры, идентификаторы, приоритет, связи, отрицательные
    свидетельства, следующие проверки, шаблоны; зависимости только kotlinx.serialization.json, canonicalJson, sha256Hex;
  - docs/contracts/result/v1/analysis-result.schema.json: свойство incidents по $ref (не в required);
  - tools/verify_slice0.py: проверка, что $ref равен $id схемы инцидента; параметр check_wording у verify_incident_document;
  - tools/test_incident_fixtures.py и tools/test_verify_slice0.py (тесты Python-стороны);
  - fixtures/incidents/<case>/{input,expected}.json; тесты IncidentSynthesisTest и IncidentContractTest;
  - docs/contracts/incident/v1/incident.schema.json: только слова описания "added with the implementation" на "added by W3.7";
  - changelog.d/w3-7-core.added.md; этот план.
NOT REQUIRED:
  - любое подключение к AnalysisService, analysisResult(), encodeAnalysisResult, identity, UI, HTML-отчёту, MCP, советнику ИИ (PR 2-4);
  - analysis_id, identity, analysis-result.json, golden и существующие тесты (остаются без правок);
  - группы ошибок, корреляционные находки, confidence, impact, межпрогонные инциденты;
  - docs/user/incidents.md, README, ADR 0029 "Реализовано" (PR 5).
EXPECTED FILES TO CHANGE:
  новые: src/main/kotlin/io/ltverdict/core/IncidentSynthesis.kt, IncidentTexts.kt;
    src/test/kotlin/io/ltverdict/core/IncidentSynthesisTest.kt, IncidentContractTest.kt;
    fixtures/incidents/* ; tools/test_incident_fixtures.py; changelog.d/w3-7-core.added.md; этот план;
  правки: docs/contracts/result/v1/analysis-result.schema.json, docs/contracts/incident/v1/incident.schema.json (слова),
    tools/verify_slice0.py, tools/test_verify_slice0.py.
```

## Публичные контракты, затрагиваемые срезом (запись до кода)

- `analysis-result.schema.json`: необязательное свойство `incidents` со ссылкой `$ref` на `$id` схемы инцидента. `required`, `schema_version`,
  `additionalProperties` не меняются. Ничего в продукте поле не пишет: схема лишь перестаёт отвергать его; результаты ядра не меняются.
- `verify_incident_document(document, schema, check_wording=True)`: значение по умолчанию сохраняет поведение (примеры контракта).
- Внутренний (не публичный) контракт ядра: `internal fun synthesizeIncidents(validity: RunValidity, findings: List<JsonObject>,
  evidence: List<JsonObject>): JsonObject`; повтор `id` в `findings` или `evidence` даёт `IllegalArgumentException` (до синтеза).
- Формат вывода CLI, коды выхода, `identity`, `analysis_id`, `analysis-result.json` не меняются.

## Ruling'и этого среза (чтение кода и ADR)

**P1. Эталоны из примеров контракта.** Для восьми valid-примеров `docs/contracts/incident/v1/examples/valid` пишутся входы
`fixtures/incidents/contract-<имя>/input.json`, а ожидаемым результатом служит сам пример: он написан вручную автором ADR, то есть независим
от кода синтеза. Тест сравнивает выход ядра с примером (канонические байты). Расхождение означает, что или код, или пример неверно читает ADR;
разбирается по тексту ADR. Остальные случаи (длинные имена, окна, кластеры, 20 и 70 групп, ручной ввод) имеют `expected.json`, создаваемый
флагом `LTV_UPDATE_INCIDENTS=1` и проверяемый тремя способами: независимым Python-валидатором (`tools/test_incident_fixtures.py`), явными
утверждениями в тесте и ручным чтением малых файлов. Цена ошибки: `expected.json`, сгенерированный кодом, сам по себе не доказывает правильность.

**P2. Kotlin-тест не запускает Python.** Задача Gradle в CI не настраивает Python, а процесс-зависимость сделала бы тест нестабильным.
Независимость обеспечивает `tools/test_incident_fixtures.py` (задача Python), а Kotlin-тест гарантирует равенство выхода файлам `expected.json`.

**P3. Сущность проверки ресурсов.** У `resource_policy_check` нет сущности; она берётся из `resource_summary` по паре (`window_id`,
`series_id`). Если сводки нет, сущность неизвестна: такая проверка не попадает в `RESOURCE_RULES_WITHIN_LIMITS` ресурсного инцидента (нельзя
утверждать, что это другая сущность), а в TRANSACTION-инциденте учитывается, роль неизвестна и считается «не generator». Цена ошибки:
чуть меньше (или больше) строк в отрицательных свидетельствах при неполном ручном вводе; ядро всегда пишет сводку.

**P4. Исключение собственной сущности** применяется только к `RESOURCE_RULES_WITHIN_LIMITS` (буквально по ADR); у
`GENERATOR_RESOURCES_WITHIN_LIMITS` его нет.

**P5. `POLICY_NOT_EVALUATED`** при `DEGRADED` получают только RESOURCE-инциденты (так читает контракт `verify_incident_semantics`).

**P6. Допуск атома строгий (S8).** Атом отбрасывается без ошибки, если нет `evidence_id` в `evidence`, не выводится область, границы
не читаются как `Long` вне диапазона схемы `0..253402300799999` или `from >= to`, у оконной TRANSACTION-находки нет `window_policy_summary`
окна, у RESOURCE-находки нет `window_id` или `entity`. Область транзакции пересобирается из полей `kind`, `label`, `group_path` (массив
строк), `sample_kind` (лишние поля отбрасываются), чтобы схема не ломалась на ручном вводе; в ядре область уже такая.

**P7. Шаблоны подставляются за один проход.** Значение подстановки не сканируется повторно (имя `{n}` не должно ломать текст).

**P8. `evidence_id` атома** может указывать на evidence любого типа (ссылка должна разрешаться; это критерий 4). Уровень `tier` ресурсного
нарушения зависит от типа и `effect` найденного evidence (`resource_policy_check` с `sla` и находка не `presumed`: 2, иначе 3).

**P9. Нет `window_id` у RESOURCE и в мостовых случаях** обработано по P6; `window_policy_summary` с повторным `window_id` берётся с
наименьшим `id` по байтам UTF-8 (детерминизм).

## Критерии приёмки

1. Красный прогон перед кодом: `IncidentSynthesisTest` и `IncidentContractTest` падают (функции нет, затем заглушка).
2. Зелёные: критерии ADR 0029 № 1 (уровень функции), 2, 3, 4, 5, 9, 10, 11, 12, 13 по таблице плана W3.7.
3. `python tools/verify_slice0.py` и `tools/test_incident_fixtures.py` зелёные; `check_wording=False` у фикстур с именами пользователя.
4. `git diff --stat` не содержит `AnalysisService.kt`, `AnalysisResult.kt`, `AnalysisDocuments.kt`, golden и существующих тестов.
5. Сверка с 0.1.0 тривиальна: функция не подключена, `analysis_id`, `result.json`, stdout неизменны (подтверждается полным `check` и
   тем, что ни один закреплённый файл не изменился).
6. Полный прогон всех job'ов CI локально (раздел «Без CI» общего брифа).

## Проверка

```text
Invoke-LtvSlot { gradlew test --tests '*IncidentSynthesis*' --tests '*IncidentContract*' }
python tools/verify_slice0.py
python -m unittest tools.test_incident_fixtures tools.test_verify_slice0
```

Полный прогон (Gradle `cleanTest check installDist`, UI typecheck/lint/test:contracts/build, оффлайн Playwright, Python и Node,
`changelog_assemble --check`, markdownlint, lychee, `git diff --check`, поиск ключей, gitleaks) выполняется перед пушем.
