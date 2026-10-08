# JUnit: пропущенные проверки как skipped (план)

Дата: 2026-10-09. Ветка: `fix/junit-skipped-warn`. Основание: решение владельца по находке PR `fix/warn-check-count`
(`changelog.d/warn-check-count.fixed.md`: «JUnit показывал статусы отдельно»). Классификация: bounded, один файл
`CliArtifacts.kt`, публичный артефакт `junit.xml` меняется (запись здесь до реализации).

## REQUESTED

При решённом гейте (`run_validity=VALID`, `policy_verdict` `PASS` или `FAIL`) проверка со статусом `NO_VERDICT`
(типично `missing_transaction=warn`) пишется в `junit.xml` как `<skipped message="не вычислено: причина"/>`, а не
`<error>`; атрибут `skipped` в шапке `testsuite` считается по факту. Красное в JUnit только при ненулевом коде выхода.

## REQUIRED TO ACHIEVE IT

- `junitXml` в `src/main/kotlin/io/ltverdict/cli/CliArtifacts.kt`: признак «гейт решён»; для `NO_VERDICT` при решённом
  гейте вид проблемы `skipped` с сообщением `не вычислено: <reasonOf ?: description>`; `skipped="..."` в шапке по факту;
  отдельная ветка вывода `<skipped message="..."/>` (без тела).
- Тесты в `CliArtifactsTest.kt`: warn+PASS, warn+FAIL, NO_VERDICT-гейт без изменений, шапка, валидный XML с экранированием причины.
- Существующий тест `junit and summary cover resource sla checks...` (verdict FAIL с двумя `NO_VERDICT`) фиксирует прежнюю
  семантику (`errors=2`); он обновляется на `errors=0, skipped=2` - это и есть меняемое поведение.
- `changelog.d/junit-skipped-warn.changed.md`, абзац в `docs/user/slice-1-local-analysis.md` (описание `junit.xml`).

## NOT REQUIRED

Контракты `analysis-result`, вердикт, коды выхода, `summary.txt`, `summary.json`, HTML/UI; элемент `testsuites`
(сейчас корень `testsuite`, обёртки нет, не добавляем); `system-out`; рефакторинг `JunitCase`.

## EXPECTED FILES TO CHANGE

`src/main/kotlin/io/ltverdict/cli/CliArtifacts.kt`, `src/test/kotlin/io/ltverdict/cli/CliArtifactsTest.kt`,
`docs/user/slice-1-local-analysis.md`, `changelog.d/junit-skipped-warn.changed.md`, этот план.

## Публичные контракты (фиксация до реализации)

- Коды выхода, `result.json`, `summary.txt`: без изменений.
- `junit.xml`: у проверки `NO_VERDICT` при гейте `VALID` + `PASS`/`FAIL` вместо `<error message=...>` теперь
  `<skipped message="не вычислено: причина"/>`; `errors` уменьшается, `skipped` растёт на то же число; `tests` прежний.
  При гейте `NO_VERDICT`/`DEGRADED`/`INVALID` и при `NO_POLICY` (проверок нет) вывод прежний (`<error>`).
- Инвариант: `failures + errors > 0` тогда и только тогда, когда код выхода ненулевой (при решённом гейте
  `NO_VERDICT`-проверки красными не считаются; FAIL-гейт даёт `failure` на gate).

## Ruling

- Ruling: «гейт решён» = `run_validity == VALID` и `policy_verdict` в `{PASS, FAIL}` - ровно условие, при котором gate-случай
  уже не `error`; отдельной новой логики нет. Цена ошибки: при расхождении проверка осталась бы красной или стала бы
  жёлтой не по месту; расхождение исключено общим условием и тестом на все исходы.
- Ruling: `<skipped message=.../>` без тела (в отличие от `failure`/`error`, где тело дублирует сообщение) - так задано
  владельцем; пустой элемент валиден для JUnit-парсеров.
- Ruling: Codex Astra по плану не привлекался - правка в одном файле без новых контрактов, формулировка и правило заданы
  владельцем; цена пропуска - низкая, проверяется тестами и просмотром диффа.

## Критерии приёмки и проверка

1. Тесты красные до правки, зелёные после: `CliArtifactsTest`.
2. Полный прогон без CI по разделу брифа «Без CI» на результате слияния с `origin/main`.
