# Сводка проверок при `missing_transaction=warn` (план)

Дата: 2026-10-08. Ветка: `fix/warn-check-count`. Основание: ревью последствий W1.3 (PR #205), известное ограничение
из `changelog.d/policy-missing-transaction.added.md`. Классификация: bounded (существующие тексты сводки, без контрактов).

## Проблема

В режиме `defaults.missing_transaction = warn` правило по пропавшей транзакции даёт `policy_check` со статусом `NO_VERDICT`
(причина `TRANSACTION_NOT_FOUND`), а вердикт остаётся `PASS` (нет нарушений) или `FAIL` (есть нарушение другого правила).
Сводки считают число проверок как общее число `policy_check` и поэтому завышают его:
`Прогон проходит — нарушений нет, проверок: 3`, когда реально вычислено 2, а одно пропущено; в `FAIL` -
`нарушено проверок: 1 из 3` при двух вычисленных.

## Места подсчёта (проверено по коду)

| Место | Считает общее число | Решение |
| --- | --- | --- |
| `HtmlReport.kt:156,157` (заголовок вердикта PASS/FAIL) | `rules.size` | править |
| `HtmlReport.kt:213` (строка «Нарушено правил: X из N») | `rules.size` | править |
| `ui/src/verdictSummary.ts:307,309,315` (заголовок и чип PASS/FAIL) | `total` | править |
| `ui/src/verdictSummary.ts:363` (факт «Проверок») | `total` | править |
| `HtmlReport.kt:163`, `verdictSummary.ts:332,337` (NO_VERDICT: «не удалось проверить: U из N») | N с явным U | не менять: уже показывает пропущенные отдельно |
| `CliArtifacts.kt:92` `summary.txt`: `rules: N (PASS a, FAIL b, NO_VERDICT c)` | N с разбивкой по статусам | не менять: пропущенные уже названы отдельно (`NO_VERDICT c`) |
| `CliArtifacts.kt` JUnit: `tests`/`errors` | один testcase на проверку плюс один gate | не менять: счёт честен; но warn+PASS даёт `<error>` на пропавшую проверку - семантика вне скоупа, в отчёт |
| AsciiDoc/Confluence | нет счётчика | дефекта нет |

## Дизайн

Вычислено = `всего - NO_VERDICT`; пропущено = число проверок со статусом `NO_VERDICT`. Только если пропущено > 0, к тексту
добавляется хвост `, не вычислено: U`; при U = 0 тексты байт-в-байт прежние (существующие тесты и привычные отчёты не меняются).

- HTML заголовок PASS: `Прогон проходит — нарушений нет, проверок: E` + `, не вычислено: U`.
- HTML заголовок FAIL: `Прогон не проходит — нарушено проверок: F из E` + `, не вычислено: U`.
- HTML строка счётчика: `Нарушено правил: F из E.` + ` Не вычислено: U.`
- UI: те же заголовок и чип (`нарушено F из E`, чип PASS остаётся `нарушений нет`); факт «Проверок»: `E, не вычислено: U`
  для PASS/FAIL при U > 0, иначе прежнее `total`.
- Для NO_VERDICT ничего не меняется: новая вычисленная база и хвост применяются только при вердикте PASS/FAIL, включая
  счётчик `Нарушено правил` (он в HTML печатается и при NO_VERDICT, там прежнее `F из N`) (замечание Codex Astra, принято).
- Подсчёт идёт по бизнес-проверкам и SLA-проверкам ресурсов вместе (диагностические исключены) - как `rules` сейчас.

Ruling: хвост добавляется только при U > 0 - почему: не менять тексты для всех прогонов без `warn`; цена ошибки: низкая
(только формулировка).
Ruling: не трогать JUnit - почему: testcase на каждую проверку плюс gate, `error` для NO_VERDICT осознанно; изменение
семантики - решение владельца (отчёт); цена ошибки: изменение поведения CI-парсеров.

## Публичные контракты

Не меняются: вердикт, exit code, схема `analysis-result`, `policy.v1`, формат `summary.txt`/JUnit/JSON. Меняются только
человекочитаемые строки HTML-отчёта и UI.

## REQUESTED / REQUIRED / NOT REQUIRED / FILES

```text
REQUESTED:
  При warn+PASS/FAIL в сводке HTML-отчёта и UI показывать отдельно вычисленные и пропущенные (не вычисленные) проверки.
REQUIRED TO ACHIEVE IT:
  1. HtmlReport.kt: три строки подсчёта (заголовки PASS/FAIL, счётчик).
  2. ui/src/verdictSummary.ts: заголовки/чип PASS/FAIL и факт «Проверок».
  3. Тесты (красные до кода): HtmlReportTest (warn+PASS, warn+FAIL, все пропущены), verdict-summary.spec.ts (те же случаи).
  4. changelog.d/warn-check-count.fixed.md; снять фразу «Известное ограничение» не требуется (фрагмент уже вмержен).
NOT REQUIRED:
  - Изменение вердикта, analysis-result, схем, ядра (Policy.kt), CLI summary.txt, JUnit, AsciiDoc/Confluence.
  - Новые поля в analysis-result (E и U считаются из evidence).
  - Рефакторинг общей функции подсчёта между Kotlin и TS (две реализации - существующий паттерн).
EXPECTED FILES TO CHANGE:
  src/main/kotlin/io/ltverdict/report/HtmlReport.kt, src/test/kotlin/io/ltverdict/report/HtmlReportTest.kt,
  ui/src/verdictSummary.ts, ui/e2e/verdict-summary.spec.ts, changelog.d/warn-check-count.fixed.md, этот план.
```

## Критерии приёмки и проверка

- warn+PASS (2 PASS + 1 NO_VERDICT): HTML и UI: `проверок: 2, не вычислено: 1`.
- warn+FAIL (1 FAIL + 1 PASS + 1 NO_VERDICT): `нарушено проверок: 1 из 2, не вычислено: 1`.
- Все транзакции отсутствуют: вердикт NO_VERDICT, заголовок прежний `не удалось проверить: U из N` (регрессия на неизменность).
- Без NO_VERDICT тексты прежние (существующие тесты не правятся).
- Команды: `./gradlew test --tests '*HtmlReportTest'` через Invoke-LtvSlot; `npx playwright test e2e/verdict-summary.spec.ts`,
  `npm run typecheck`, `npm run lint` в `ui`; затем ktlint/полная проверка по правилам проекта.

Documentation impact: none (тексты отчёта не описаны в пользовательской документации; проверить grep перед PR).
