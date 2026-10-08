# ADR «Инцидент v0» и контракт `incident.v1` (W2.7) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans. Работа документальная (ADR, JSON Schema,
> примеры, проверка Slice 0); TDD применяется к проверке примеров (красная проверка до схемы), к коду ядра не применяется.

**Goal:** принять в виде ADR 0029 (статус Proposed) детерминированную модель инцидента и зафиксировать контракт
`incident.v1` со схемой и примерами valid/invalid, проверяемыми в CI «Slice 0 contracts».

**Architecture:** инцидент это производная детерминированная группировка существующих findings и evidence одного
анализа по окну, времени и области (транзакция или сущность). Он не содержит утверждений о причине. Блок `incidents`
вычисляется чистой функцией от `findings`, `evidence` и `run_validity` результата и записывается в `analysis-result.v1`
как необязательное верхнеуровневое поле; identity получает привязку `incident_method`.

**Tech Stack:** Markdown (ADR), JSON Schema draft 2020-12, Python stdlib (`tools/verify_slice0.py`).

**Spec:** требования владельца в `docs/superpowers/plans/2026-10-08-review-work-plan.md`, W2.7 и W3.7 (файл не
отслеживается в основном чекауте); `lt-verdict-prc-prd-v0.6.md` (§1.3, §10.4, §11.6, §13.2, §26 п.6, FR-ANA-04, FR-UX-01);
ADR 0006, 0020, 0026.

## Global Constraints

- Статус ADR: `Proposed`. Принятие только по слову владельца.
- Номер ADR 0029: `origin/main` содержит 0001-0027, ветка W1.5 (`docs/repo-hygiene-w1-5`, PR #208) переименовывает
  дубликат ADR 0010 в 0028. Номер перепроверяется по `origin/main` перед слиянием.
- Заморозка ширины D1: новых источников и статметодов нет; корреляционные семьи как evidence отложены в v1.
- Инцидент отвечает «что совпало по времени и где», не «почему». Причинные формулировки запрещены.
- Каноническое вычисление хэшей identity и байты существующих полей результата не меняются.
- Русский язык прозы, английские идентификаторы сохраняются.

## Review Focus

- Контракт разрешает причинный текст в `title`/`summary`/`next_checks`: закрывается deny-list в проверке примеров и
  тест-критерием W3.7.
- Идентификатор инцидента зависит от порядка массивов результата: id считается от `grouping.key`, не от индексов.
- «Пусто» читается как «всё хорошо»: `NOT_EVALUATED` и `NOT_CONFIRMED` разведены, пустой список не является PASS.
- Добавление `incidents` ломает проверку набора полей в `AdvisoryAi.kt:568`: записано как зависимость W2.1/W3.7.
- Повторный анализ тех же входов вернёт из хранилища старый результат без инцидентов: закрыто привязкой identity.

---

## Решения (Ruling)

**R1. Инцидент входит в `analysis-result.v1` как необязательное поле `incidents` (не отдельный файл и не чтение на
лету).** Почему: владелец уже закрепил «раздел инцидентов в `analysis-result`» (W3.7); результат неизменяем, поэтому то,
что показал отчёт, не меняется при смене алгоритма; один документ читают UI, HTML, MCP. Цена ошибки: изменение алгоритма
требует нового `method`, нового `analysis_id` и пересчёта; чтение на лету (как `metric_packs`) дешевле, но две версии
правды о старом анализе. Отклонённая альтернатива описана в ADR.

**R2. `schema_version` остаётся `analysis-result.v1`.** Поле необязательное и добавочное, как `capacity_summary`.
Повышение версии меняло бы `outputs.analysis_result_schema` и ключ сопоставимости всех прогонов. Цена ошибки: строгие
читатели с белым списком полей (`AdvisoryAi.kt:568`) отвергают результат; закрыто явной зависимостью W2.1/W3.7.

**R3. Identity: безусловное верхнеуровневое поле `incident_method: "incident-synthesis.v1"`.** Не в `modules`,
`input_versions`, `outputs`, `limits`: ключ сопоставимости не меняется (прецедент ADR 0020 п. 5, ADR 0026). Условной привязку
сделать нельзя: наличие инцидентов нельзя узнать до анализа, а `analysis_id` проверяется в хранилище до вычислений.
Цена: `analysis_id` всех новых анализов меняется, золотые файлы identity перегенерируются в W3.7.

**R4. Входы: только `findings`, `evidence`, `run_validity` одного результата.** Ни `run.json`, ни артефакты каталога,
ни время часов. Это даёт проверку «убрать `incidents`, пересчитать, получить те же байты». Цена: у прогона без снимка
ресурсов нет времени (интервал `UNKNOWN`).

**R5. Допускаются findings `policy_failure`, `resource_threshold_violation`, `anomaly_episode`, `resource_trend`.**
`correlation_candidate`, `diagnostic`, ёмкость исключены (корреляции отложены в v1 по решению владельца; `diagnostic`
описывает качество прогона и остаётся в validity/coverage). Ошибки по коду ответа и сообщению ждут W2.6: отдельного семейства
нет, группы ошибок войдут в семейство TRANSACTION поправкой к ADR после W2.6 (W3.7 по утверждённому плану требует W2.1
и W2.6; ослабление зависимости только решением владельца). W2.5 (окна без снимка ресурсов) контракт не меняет: правила
опираются на типы evidence.

**R6. Приоритет без оценки влияния.** Ключ: `tier`, число findings, начало интервала, id. Влияние (дельты к baseline)
отложено в v1. `confidence` из PRC §10.4 не включён: калиброванной меры качества evidence нет; `candidate_subsystem` не включён:
это гипотеза о причине.

**R7. Лимиты:** `overview_limit` ровно 7; хранится не более 64 инцидентов; остальные считаются в `omitted_count`.
Для каждого инцидента не более 16 `finding_ids`, 24 `evidence_ids`, 8 `negative_evidence`, 5 `next_checks`, 5 связей.

**R10. `DEGRADED`:** политика по транзакциям не вычисляется (`Policy.kt:104`), поэтому инциденты строятся только по
ресурсной стороне, а каждый получает отрицательное свидетельство `POLICY_NOT_EVALUATED`. `INVALID`: `NOT_EVALUATED`.
Цена ошибки: пустая транзакционная сторона читается как «нарушений нет».

**R11. Особые входы:** `presumed`-нарушения ресурса имеют tier 3; эпизоды по сигналу нагрузки (`entity` = `overall`)
остаются в семействе RESOURCE со словами «нагрузка» в шаблонах.

**R8. Проверка примеров без новых зависимостей:** `jsonschema` в «Slice 0 contracts» нет; в `tools/verify_slice0.py`
добавляется компактный проверяющий для используемого подмножества ключевых слов и семантические проверки (id равен
SHA-256 канонического `grouping.key`, deny-list причинных слов, ограничение обзора). Схема остаётся стандартной JSON Schema.

**R9. `analysis-result.schema.json` в этом PR не меняется.** Изменение публичного контракта записано в ADR (точный diff
схемы) и выполняется в W3.7 вместе с кодом; в Proposed-ADR нельзя менять действующий контракт.

## Публичные контракты

- Новый документ: `docs/contracts/incident/v1/incident.schema.json`, `schema_version: "incident.v1"`.
- Будущее изменение `analysis-result.v1` (в W3.7, не здесь): необязательное `incidents` по ссылке на `incident.v1`.
- Будущее изменение `analysis-identity.v1` (в W3.7): поле `incident_method`.
- Формат вывода CLI и коды выхода не меняются.

## REQUESTED / REQUIRED / NOT REQUIRED / FILES

```text
REQUESTED: ADR «Инцидент v0»; контракт incident.v1 в docs/contracts; примеры valid/invalid; подключение к Slice 0 contracts.
REQUIRED TO ACHIEVE IT: ADR 0029; incident.schema.json; примеры; расширение tools/verify_slice0.py и test_verify_slice0.py;
  фрагмент changelog.d; этот план.
NOT REQUIRED: код ядра; изменение analysis-result.schema.json; Kotlin-валидатор; UI; README; перенумерация чужих ADR;
  корреляционные семьи; влияние и confidence.
EXPECTED FILES TO CHANGE:
  docs/adr/0029-incident-v0.md (new)
  docs/contracts/incident/v1/incident.schema.json (new)
  docs/contracts/incident/v1/examples/valid/*.json, invalid/*.json (new)
  tools/verify_slice0.py, tools/test_verify_slice0.py (modify)
  changelog.d/incident-v0-adr.added.md (new)
  docs/superpowers/plans/2026-10-08-incident-v0-adr.md (new)
```

## Критерии приёмки и проверка

1. `python tools/verify_slice0.py` печатает `slice 0 verification: OK`; проверяет все valid-примеры и отклоняет все invalid.
2. `python -m unittest tools.test_verify_slice0 -v` зелёный, включая новые тесты (красный до появления схемы).
3. `python -m unittest discover -s tools -p "test_*.py"` зелёный.
4. `npx --yes markdownlint-cli2@0.23.2 docs/adr/0029-incident-v0.md docs/superpowers/plans/2026-10-08-incident-v0-adr.md` без ошибок.
5. Относительные ссылки ADR разрешаются в отслеживаемые файлы (проверка скриптом).
6. `git diff --check`, нет `.qwen/`, секретов, чужих файлов.
7. Совет Codex Astra (read-only) получен и учтён, замечания проверены фактами.

## Tasks

### Task 1: Красная проверка

- [ ] В `tools/test_verify_slice0.py` добавить тесты: схема `incident.v1` существует; каждый valid проходит; каждый invalid
  отклоняется; id равен хэшу; причинные слова отклоняются.
- [ ] `python -m unittest tools.test_verify_slice0 -v` и убедиться, что падает (схемы нет).

### Task 2: Схема и примеры

- [ ] Написать `incident.schema.json`, valid-примеры (SLA по транзакции в окне; сигнал ресурса; пустой с `NOT_EVALUATED`;
  прогон без окна) и invalid-примеры (см. ADR, раздел «Контракт»).
- [ ] Расширить `tools/verify_slice0.py`: проверяющий подмножества и семантика. Тесты зелёные.

### Task 3: ADR

- [ ] Написать `docs/adr/0029-incident-v0.md` по Ruling R1-R9, с входами ядра сегодня, зависимостями W2.1/W2.6,
  тест-критериями W3.7, отклонёнными альтернативами.

### Task 4: Совет Astra и сверка

- [ ] Копия плана, ADR и схемы в подкаталог worktree, `codex exec -m gpt-6-astra -s read-only`; замечания проверить по коду.

### Task 5: Завершение

- [ ] Фрагмент журнала, проверки из критериев, коммиты, push, PR без автослияния.
