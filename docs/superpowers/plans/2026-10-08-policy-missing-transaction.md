# W1.3 Policy: пропавшая транзакция (план)

Дата: 2026-10-08. Ветка: `fix/policy-missing-transaction-fail`. Основание: usability B3; `Policy.kt:169,258,528`.
Классификация: bounded (существующий поток `evaluatePolicy`), с изменением публичного контракта `policy.v1`.

## Проблема

Правило с `scope.kind = transaction` и именем, которого нет в результатах, даёт причину `TRANSACTION_NOT_FOUND`.
Любая причина в `reasons` переводит вердикт в `NO_VERDICT` (`Policy.kt:169`), поэтому найденное нарушение
другого правила (p95 339 при пороге 300) не даёт `FAIL` и exit 2: CLI отдаёт 3. Это принятое владельцем поведение
по умолчанию (ADR 0003, ADR 0018 раздел 6, вариант K): структурная привязка не восстанавливается нарушением.
Задача: **опциональный** режим политики, где пропавший label остаётся предупреждением, а нарушение по присутствующим
транзакциям даёт `FAIL`. Поведение по умолчанию не меняется.

## REQUESTED / REQUIRED / NOT REQUIRED / FILES

```text
REQUESTED:
  Режим policy, где отсутствие transaction label даёт предупреждение, а найденное нарушение даёт FAIL и exit 2.
  Подсказка допустимых значений в ошибках валидации перечислимых полей.
  Критерий: «p95 339 при пороге 300 плюс пропавшая транзакция» возвращает exit 2.
REQUIRED TO ACHIEVE IT:
  1. Необязательное поле defaults.missing_transaction ("no_verdict" | "warn") в policy.v1: схема, валидатор,
     модель PolicyDefaultsV1, ветка TRANSACTION_NOT_FOUND в evaluatePolicy.
  2. Вспомогательная функция сообщения «allowed: ...» в Policy.kt и её применение в местах разбора перечислимых
     полей (metric, operator, aggregation, effect, scope.kind, новое поле): это одна общая функция валидации policy.
  3. Контракты: policy.schema.json, один valid и один invalid пример, строки в таблице PolicyTest.contractExamples.
  4. Тесты (красные до кода): ядро, валидатор, подсказки, CLI exit 2 / 3 / 0.
  5. fixtures/slice1/manifest.json: новые примеры и хэш схемы (его читают PolicyTest, FixtureManifestTest, verify-policy-schema.mjs).
  6. CapacityAnalysis.kt (одна строка): ступень с пропавшей транзакцией в окне остаётся INDETERMINATE (иначе warn подтвердил бы
     ёмкость при непроверенном SLA; результат Astra, P2).
  7. Документация: docs/user/slice-1-local-analysis.md (режим, коды), абзац-исключение в ADR 0018 раздел 6, changelog.d фрагмент.
NOT REQUIRED (отчёт, не делаю):
  - Поле в UI PolicyEditor (редактор сохраняет defaults целиком; поле правится в JSON). Тип в ui/src/types.ts не меняю.
  - Режим для AMBIGUOUS_TRANSACTION, RULE_WINDOW_NOT_FOUND, INSUFFICIENT_SAMPLES, METRIC_NOT_AVAILABLE: остаются блокирующими.
  - Режим на уровне отдельного правила, смена вердикта по умолчанию, версия policy.v2, новый ADR.
  - Изменение CommandLine.kt (коды выхода берутся из policy_verdict; W1.1 правит файл параллельно).
  - Подсказки допустимых значений в других валидаторах (resource snapshot, sources, capacity и т.д.).
EXPECTED FILES TO CHANGE:
  src/main/kotlin/io/ltverdict/core/Policy.kt, src/main/kotlin/io/ltverdict/core/Model.kt,
  docs/contracts/policy/v1/policy.schema.json, docs/contracts/policy/v1/examples/valid/missing-transaction-warn.json,
  docs/contracts/policy/v1/examples/invalid/unknown-missing-transaction-mode.json,
  src/test/kotlin/io/ltverdict/core/PolicyEvaluationTest.kt, src/test/kotlin/io/ltverdict/core/PolicyTest.kt,
  src/test/kotlin/io/ltverdict/cli/CommandLineTest.kt, fixtures/slice1/manifest.json,
  src/main/kotlin/io/ltverdict/core/CapacityAnalysis.kt (+ тест в CapacityAnalysisTest), docs/adr/0018-policy-platform-rules-small-samples.md,
  docs/user/slice-1-local-analysis.md, changelog.d/policy-missing-transaction.added.md,
  этот план.
```

## Публичный контракт (фиксируется до кода)

### Схема policy.v1: аддитивное поле

`defaults.missing_transaction`: необязательная строка, `enum ["no_verdict", "warn"]`. Отсутствие поля равно `no_verdict`
(прежнее поведение, fail-closed). Остальные поля `defaults` не меняются. `schema_version` остаётся `policy.v1`
(по образцу ADR 0018 раздел 7, вариант A: аддитивное расширение на месте; старый строгий читатель отвергнет новый файл
как `UNKNOWN_FIELD`, что безопаснее тихого игнорирования). Старые файлы сохраняют прежние canonical bytes и `policy_sha256`.
Ключ сопоставимости и `input_versions.policy` не меняются.

### Семантика `warn`

Касается только причины `TRANSACTION_NOT_FOUND` у бизнес-правила со `scope.kind = transaction` (ноль подходящих идентичностей):

- правило не оценивается; его `policy_check` остаётся (`status = NO_VERDICT`, `reason_code = TRANSACTION_NOT_FOUND`, без `observed`),
  то есть пропуск виден в evidence, отчёте и UI как и раньше;
- причина попадает в `analysis_coverage.reasons` (статус `INCOMPLETE`), но **не блокирует** вердикт (как `SMALL_SAMPLE`);
- нарушения остальных правил дают `FAIL` -> exit 2; если нарушений нет, вердикт `PASS` с `INCOMPLETE` и предупреждением -> exit 0;
- страховка от пустого PASS: если ни одно правило не получило решение `PASS`/`FAIL` (все применимые правила пропали или иначе не оценены),
  `TRANSACTION_NOT_FOUND` остаётся блокирующей и вердикт `NO_VERDICT`;
- `AMBIGUOUS_TRANSACTION`, `INSUFFICIENT_SAMPLES`, `METRIC_NOT_AVAILABLE`, `RULE_WINDOW_NOT_FOUND`, невалидный вход: блокируют, как прежде;
- тот же путь работает для оконной оценки (`evaluatePolicy` с `windowId`). Страховка от пустого PASS действует в каждом вызове отдельно:
  окно, где пропали все его применимые правила, остаётся `NO_VERDICT` и (как и сегодня) перекрывает `FAIL` другого окна в `overallVerdict`
  (`WindowPolicy.kt:57-63`); режим улучшает только окна, где часть правил оценена;
- capacity: ступень, в окне которой есть business-check с `reason_code = TRANSACTION_NOT_FOUND`, получает `CAPACITY_SLA_NO_VERDICT` и
  остаётся `INDETERMINATE` (как при `NO_VERDICT` без режима): режим не подтверждает ёмкость при непроверенном SLA;
- UI/HTML «нарушений нет, проверок: N» считает и пропущенные checks (`verdictSummary.ts:266,315`, `HtmlReport.kt:157`): вне скоупа, в отчёт;
- baseline: `INCOMPLETE` с причиной, отличной от `SMALL_SAMPLE`, уже не допускается как baseline (`BaselineComparison.kt:114`), поэтому
  PASS с пропавшей транзакцией не станет эталоном. Код baseline не меняется.

### Коды выхода CLI

Без изменений: PASS/NO_POLICY 0, FAIL 2, NO_VERDICT 3, ошибки входа 4. Exit 2 получается из `policy_verdict = FAIL`.

### Ошибки валидации

Новый код `UNKNOWN_MISSING_TRANSACTION_MODE` (pointer `/defaults/missing_transaction`). Формат сообщений для перечислимых
полей: `unknown <what>; allowed: a, b, c` (английский, как остальные сообщения; коды и указатели не меняются).
Поле не-строка даёт существующий `INVALID_TYPE`.

## Ruling

- Ruling: имя `defaults.missing_transaction`, значения `no_verdict`/`warn` - `defaults` уже хранит политико-уровневые режимы оценки
  (`sample_floor`, `min_samples`, допуски), wire-имена enum в схеме snake_case (`effect`, `aggregation`) - поле на уровне правила
  не нужно, режим один на файл (как и допуски по умолчанию); цена ошибки: имя придётся менять до первого использования, данных нет.
- Ruling: `warn` при отсутствии нарушений даёт `PASS` с `INCOMPLETE`, а не `NO_VERDICT` - формулировка владельца «отсутствие label даёт
  предупреждение»; fail-closed сохранён по умолчанию и страховкой от пустого PASS; цена ошибки: ложный PASS в CI при опечатке в имени,
  если владелец включил режим, смягчена видимым предупреждением в coverage/evidence и запретом baseline.
- Ruling: новый ADR не пишется - решение узкое, аддитивное и опциональное, записано здесь. Строка таблицы ADR 0018 раздела 6 про
  `TRANSACTION_NOT_FOUND` относится к поведению по умолчанию; в ADR добавляется абзац-исключение об opt-in режиме (замечание Astra: иначе
  документ противоречит коду). Цена ошибки: если владелец захочет отдельный ADR, это правка документа без кода.
- Ruling: capacity остаётся fail-closed в режиме `warn` (одна строка в `CapacityAnalysis.kt`) - подтверждение ёмкости при непроверенном SLA
  опаснее, чем лишняя ступень INDETERMINATE; цена ошибки: пользователь режима не получит verifiedLoad, пока имя транзакции не исправлено.
- Ruling: подсказки допустимых значений ставлю во все места разбора перечислимых полей в `Policy.kt` (metric, operator x2, aggregation,
  effect, scope.kind x2, новое поле) через одну функцию; другие валидаторы не трогаю.

## Критерии приёмки

1. Политика с `defaults.missing_transaction = warn`, правилом overall p95 <= 300 (факт 339) и правилом на отсутствующую транзакцию:
   `policy_verdict = FAIL`, exit 2, в `analysis_coverage.reasons` есть `TRANSACTION_NOT_FOUND`, `policy_check` пропавшего правила на месте.
2. Та же политика без нарушения: `PASS`, exit 0, coverage `INCOMPLETE` с `TRANSACTION_NOT_FOUND`.
3. Та же политика без поля: `NO_VERDICT`, exit 3 (регрессия не появилась).
4. Все применимые правила пропали при `warn`: `NO_VERDICT`.
5. `AMBIGUOUS_TRANSACTION` при `warn` по-прежнему `NO_VERDICT`.
6. Сообщения ошибок для неизвестных metric/operator/aggregation/effect/scope.kind/missing_transaction содержат список допустимых значений.
7. Существующие тесты, golden-фикстуры и хэши существующих политик/результатов не меняются (манифест получает только новые примеры и новый хэш схемы); проверка контрактов (PolicyTest.contractExamples, schema vs runtime) зелёная.

## Задачи (TDD)

1. Красные тесты: `PolicyEvaluationTest` (критерии 1, 2, 4, 5, 3), `PolicyTest` (парсинг поля, неверное значение, подсказки, примеры контрактов,
   совпадение enum схемы и рантайма), `CommandLineTest` (exit 2 / 0 / 3 на `jmeter/xml-5.6.3/input.xml`).
2. `Model.kt`: `MissingTransactionMode` и поле `PolicyDefaultsV1.missingTransaction` (по умолчанию null, в конец списка, существующие вызовы не меняются).
3. `Policy.kt`: разбор в `parseDefaults`, ветка в `evaluatePolicy`, функция сообщения «allowed».
4. Схема и примеры. 5. Документация и фрагмент changelog. 6. Полная проверка.

## Команды проверки

```text
. F:\Coding\LT-Verdict\.worktrees\_tools\ltv-slot.ps1
Invoke-LtvSlot ... gradlew test --tests "io.ltverdict.core.PolicyEvaluationTest" --tests "io.ltverdict.core.PolicyTest" --tests "io.ltverdict.cli.CommandLineTest"
Invoke-LtvExclusive ... gradlew check   (полный check: тесты, ktlint, документация, контракты)
npm --prefix ui run test:contracts ; python tools/changelog_assemble.py --check ; python tools/verify_slice0.py
git diff --stat ; секреты: git diff | grep -i "api[_-]key\|secret"
```

Documentation impact: обновляется `docs/user/slice-1-local-analysis.md` (режим и коды) и контракт `docs/contracts/policy/v1`.
