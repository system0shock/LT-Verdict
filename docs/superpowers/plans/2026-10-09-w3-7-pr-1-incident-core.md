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
  - core/IncidentSynthesis.kt, core/IncidentNegativeEvidence.kt (вынесено при росте файла сверх 600 строк, как предписывает план W3.7) и
    core/IncidentTexts.kt (новые): атомы, кластеры, идентификаторы, приоритет, связи, отрицательные
    свидетельства, следующие проверки, шаблоны; зависимости только kotlinx.serialization.json, canonicalJson, sha256Hex;
  - docs/contracts/result/v1/analysis-result.schema.json: свойство incidents по $ref (не в required);
  - tools/verify_slice0.py: проверка, что $ref равен $id схемы инцидента; параметр check_wording у verify_incident_document;
  - tools/test_incident_fixtures.py и добавленные тесты в tools/test_verify_slice0.py (Python-сторона; существующие тесты не меняются);
  - fixtures/incidents/<case>/{input,expected}.json; тесты IncidentSynthesisTest и IncidentContractTest;
  - docs/contracts/incident/v1/incident.schema.json: только слова описания "added with the implementation" на "added by W3.7";
  - changelog.d/w3-7-core.added.md; этот план.
NOT REQUIRED:
  - любое подключение к AnalysisService, analysisResult(), encodeAnalysisResult, identity, UI, HTML-отчёту, MCP, советнику ИИ (PR 2-4);
  - analysis_id, identity, analysis-result.json, golden и существующие тесты (остаются без правок);
  - группы ошибок, корреляционные находки, confidence, impact, межпрогонные инциденты;
  - docs/user/incidents.md, README, ADR 0029 "Реализовано" (PR 5).
EXPECTED FILES TO CHANGE:
  новые: src/main/kotlin/io/ltverdict/core/IncidentSynthesis.kt, IncidentNegativeEvidence.kt, IncidentTexts.kt;
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

**P1. Эталоны из примеров контракта.** Для шести valid-примеров `docs/contracts/incident/v1/examples/valid`, которые один вход воспроизводит
точно (`degraded-run-resource-only`, `evaluated-without-incidents`, `load-signal-episode`, `not-evaluated-invalid-run`,
`overview-limit-nine-incidents`, `run-without-window`), пишутся входы `fixtures/incidents/contract-<имя>/input.json`, а ожидаемым результатом
служит сам пример: он написан вручную автором ADR, то есть независим от кода синтеза. Остальные случаи имеют `expected.json`, создаваемый
флагом `LTV_UPDATE_INCIDENTS=1` из кода ядра и проверяемый тремя способами: независимым Python-валидатором (`tools/test_incident_fixtures.py`,
в нём же шаблоны текстов ADR записаны ещё раз и каждое число и ссылка сверены со входом), явными утверждениями в `IncidentSynthesisTest` и
чтением малых файлов вручную. Расхождение с планом W3.7 («малые expected.json пишутся вручную»): они сгенерированы и затем прочитаны. Цена
ошибки: `expected.json`, созданный кодом, сам по себе не доказывает правильность; для него это компенсируется тремя способами выше.

**P2. Два примера контракта не воспроизводимы точно.** `transaction-and-resource-in-window` и `overlapping-resource-incidents` показывают
проверки окна (`RESOURCE_RULES_WITHIN_LIMITS`, `GENERATOR_RESOURCES_WITHIN_LIMITS`, `NO_ANOMALY_EPISODES`, `CHECKS_NOT_EVALUATED`) на одном
инциденте окна, а по правилам ADR они относятся к окну целиком и должны стоять на каждом инциденте окна (совет Astra, подтверждено по данным:
вход, дающий пример, невозможен). Решено следовать тексту принятого ADR, а не примерам (Ruling: ADR принят владельцем и в плане W3.7 «правила
ADR не меняются»; цена ошибки: выход ядра богаче примера на явно перечисленные записи). Для них есть варианты входа
(`both-families-in-window`, `entity-neighbours-in-window`), а тест проверяет, что выход отличается от примера ровно на эти записи и
соответствующие им `next_checks`, и больше ничем. Вопрос владельцу: поправить примеры или текст ADR.

**P3. Сущность проверки ресурсов.** У `resource_policy_check` нет сущности и роли; они берутся из `resource_summary` по паре (`window_id`,
`series_id`). `RESOURCE_RULES_WITHIN_LIMITS` и `GENERATOR_RESOURCES_WITHIN_LIMITS` вычисляются для обоих семейств (ADR их по семейству не
ограничивает). В RESOURCE-инциденте `RESOURCE_RULES_WITHIN_LIMITS` не включает проверки собственной сущности и проверки, чья сущность неизвестна
(нельзя утверждать, что это другая сущность). В TRANSACTION-инциденте проверка без сводки считается «не generator». Несколько сводок одной
пары: побеждает сводка с наименьшим `id` по байтам UTF-8 (детерминизм, P9).

**P4. Исключение собственной сущности** применяется только к `RESOURCE_RULES_WITHIN_LIMITS` (буквально по ADR); у
`GENERATOR_RESOURCES_WITHIN_LIMITS` его нет.

**P5. `POLICY_NOT_EVALUATED`** при `DEGRADED` получают только RESOURCE-инциденты (так читает контракт `verify_incident_semantics`).

**P6. Допуск атома строгий (S8).** Атом отбрасывается без ошибки, если нет `evidence_id` в `evidence`, не выводится область, границы не читаются
как `Long` (строка `"123"`, `1.0`, `1e3` не принимаются) вне диапазона схемы `0..253402300799999` или `from >= to`, у оконной
TRANSACTION-находки нет `window_policy_summary` окна, у RESOURCE-находки нет `window_id` или `entity`. Область транзакции пересобирается из
полей `kind`, `label`, `group_path` (массив строк), `sample_kind` (лишние поля отбрасываются), чтобы схема не ломалась на ручном вводе; в ядре
область уже такая.

**P7. Шаблоны подставляются за один проход.** Значение подстановки не сканируется повторно (имя `{n}` не должно ломать текст).

**P8. `evidence_id` атома** может указывать на evidence любого типа (ссылка должна разрешаться; это критерий 4). Уровень `tier` ресурсного
нарушения зависит от типа и `effect` найденного evidence (`resource_policy_check` с `sla` и находка не `presumed`: 2, иначе 3).

**P9. Повторы.** `window_policy_summary` и `resource_summary` с повторным ключом: берётся запись с наименьшим `id` по байтам UTF-8.

**P10. Падежи в `COMPARE_WITH_BASELINE`.** Шаблон ADR `Сравнить метрики {область} с baseline.` при `{область}` = `транзакция X` или
`весь прогон` грамматически неверен; примеры контракта пишут «транзакции X» и «всего прогона». Выбраны примеры (родительный падеж только
в этом шаблоне; заголовок и сводка по ADR). Цена ошибки: байты одного текста; вопрос владельцу.

**P11. Проверка схемы (критерий 12) выполняется на стороне Python.** Задача Gradle в CI не настраивает Python, поэтому Kotlin-тест
`IncidentContractTest` проверяет файлы контракта, идентификаторы, ссылку `$ref` и то, что выход ядра использует ровно поля и словарь схемы, а
валидация по ключевым словам схемы, порядок, ранги, связи и причинные слова выполняются `tools/verify_slice0.py` (job «Slice 0 contracts» и
`tools/test_incident_fixtures.py`) над теми же файлами `expected.json`, которые Kotlin-тест сверяет с выходом ядра побайтно. Отказ каждого
invalid-примера по названной причине проверяют `tools/verify_slice0.py` и `tools/test_verify_slice0.py` (уже были).

**P12. Правка существующего теста.** В `tools/test_verify_slice0.py` только добавлены два теста (`check_wording`, ссылка `$ref`); существующие
тесты не менялись.

## Совет Codex Astra (read-only) по плану и что учтено

Совет получен до кода (`gpt-6-astra`, read-only). Принято: (1) два примера контракта не воспроизводимы точно (P2; вместо исключения без проверки
добавлены варианты и тест на точную разницу); (2) независимость проверки переоценена: Python-валидатор не знает входа, поэтому добавлены
сверка со входом и шаблоны ADR в `tools/test_incident_fixtures.py` (P1, P11); (3) падеж в `COMPARE_WITH_BASELINE` (P10); (4) сущность по
`resource_summary` и выбор при нескольких сводках (P3, P9); (5) строгость чисел (P6); (6) тесты, которые легко пропустить: границы 16/17,
24/25, 8/9, касание не создаёт `INTERVAL_OVERLAP` между разными сущностями, мост, вложение, `U+FF5E` против `U+1F600`, 63/64/65 кодовых
точек, `{n}` в имени, `presumed` с `sla`, нагрузка `entity=overall`, окно без снимка, пустой `reasons[]`, чужое окно: добавлены. Не принято:
общий JSON Schema-движок (лишний объём, `verify_slice0.py` уже достаточен). Противоречие «существующие тесты не редактируются» и правка
`tools/test_verify_slice0.py` снято формулировкой P12.

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
