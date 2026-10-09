# W2.5 PR C: видимость окна устойчивого состояния (план реализации)

Подмножество [плана W2.5](2026-10-09-w2-5-steady-state-window.md) и
[ADR 0030](../../adr/0030-load-stages-steady-window.md) (Accepted), разделы «Видимость» и «Публичные контракты». Ядро (PR A),
CLI и API (PR B) слиты. Здесь прогон со стадиями начинает говорить вслух, что вердикт посчитан по окну `steady`. Решения владельца
2026-10-09, обязательные для PR C: метрики «весь прогон, справочно» подписываются везде, где рядом есть `stage_binding` (сравнение с
эталоном, динамика прогонов, сравнение транзакций; подпись, не арифметика и не новый контракт); UI показывает фразу И таблицу стадий
для анализа, созданного через CLI или API (формы загрузки стадий нет, R12).

## Блок для AGENTS.md

```text
REQUESTED: видимость окна steady: фиксированная фраза и таблица стадий в карточке вердикта UI, в HTML-отчёте, AsciiDoc и Confluence,
  summary.txt, cli-summary.v1 (windows[]), junit.xml; предупреждение WHOLE_RUN_METRICS_WITH_STAGES в сравнении с эталоном;
  подписи «весь прогон, справочно» в сравнении с эталоном, динамике и сравнении транзакций; stage_binding в генерации типов UI.
REQUIRED TO ACHIEVE IT:
  - report/StageNotice.kt: одна функция stageNotice(result) (фраза, нейтральная фраза, окна, таблица стадий) для HTML, AsciiDoc, Confluence;
  - report/HtmlReport.kt (dt/dd «Область вердикта», блок), report/AsciiDocReport.kt и integrations/report/ConfluenceReport.kt (примечание);
  - cli/CliArtifacts.kt: summary.txt, windows[] и excluded_ms в cli-summary.v1, junit.xml;
  - core/BaselineComparison.kt: предупреждение; ui: types.ts, shell/labels.ts, подписи в AnalyticsPanel;
  - ui/src/verdictSummary.ts (headline, lead, facts, таблица стадий; логика рядом с formatUtc и formatDuration), VerdictCard.vue, labels.advice.ts;
  - генерация типов: StageEvidence в новый types.stage-items.generated.ts и сверка в verify-generated-types.mjs;
  - тесты (новые файлы, новый e2e), changelog-фрагмент.
NOT REQUIRED:
  - документация, руководство, пример ловушки setUp (PR D); форма загрузки стадий в UI; MCP;
  - пересчёт арифметики сравнения, динамики и транзакций; предупреждение в динамике и транзакциях как поле ответа (полей нет);
  - правки ядра оценки, identity, схем, существующих тестов, замороженных снимков (golden, typed-evidence), существующих e2e;
  - разделы «Резюме для людей» (W2.6) и доля разгона как метрика (W2.4).
EXPECTED FILES TO CHANGE:
  новые: report/StageNotice.kt, fixtures/stages/plain-reports/** (18 золотых файлов), ui/src/types.stage-items.generated.ts, ui/e2e/stage-window.spec.ts, ui/e2e/stage-ui.config.ts (быстрый прогон без Gradle), fixtures/stages/stage-binding.sample.json,
    тесты (report, cli, core), changelog.d/w2-5-stage-window-visibility.changed.md;
  правки: HtmlReport.kt, AsciiDocReport.kt, ConfluenceReport.kt, CliArtifacts.kt, BaselineComparison.kt, ui/src/{types.ts,verdictSummary.ts,
    VerdictCard.vue,AnalyticsPanel.vue,App.vue,shell/labels.ts,shell/labels.advice.ts,shell/labels.export.ts}, ui/scripts/verify-generated-types.mjs.
```

## Ruling'и PR C

**C1. Тексты.** Фраза-константа (R10, генерируется при отрисовке из `stage_binding`, в результате не хранится):
`Вердикт посчитан по окну steady, разгон исключён`. Её выводят только при `run_validity = VALID` и `policy_verdict` `PASS` или `FAIL`.
При `NO_VERDICT` (в том числе `DEGRADED`) и `NO_POLICY` текст: `Окно steady задано (<ids>), разгон исключён из метрик окна; вердикт:
<NO_VERDICT|NO_POLICY>` (вердикта не утверждает). Уточнение с подстановками: `Окно вердикта: <id>, <длительность>, <начало> – <конец> UTC.
Исключено: <длительность>.` Длительность как `formatDuration` UI (`N с` до минуты, иначе `N мин`, до двух знаков, запятая). Если окон
несколько, каждое перечислено через `;`. Почему: так записано в ADR 0030; точное место подстановки ADR не фиксирует. Цена ошибки:
формулировку можно править без смены `analysis_id` (текст не в результате).

**C2. Таблица стадий** (UI и HTML): колонки «Стадия», «Роль», «Смещения, мс», «Границы (UTC)», «Границы (epoch, мс)», «Обрезана до конца
прогона» (`да` у обрезанной `steady`, иначе `—`) и итог `Оценено: X, исключено: Y`. Фактические epoch-границы видны именно для ловушки
начала прогона (AC13): посторонняя ранняя выборка сдвигает все окна, и это видно по границам.

**C3. Подпись «весь прогон, справочно».** Сравнение с эталоном: серверное предупреждение `WHOLE_RUN_METRICS_WITH_STAGES` в `warnings` (оно
уже выводится списком предупреждений, подпись добавляется в `BASELINE_LABELS.warnings`). У динамики прогонов и сравнения транзакций поля
`warnings` нет и контракт ответа не создаётся: UI показывает одну подпись над таблицами аналитики, когда у ТЕКУЩЕГО анализа есть
`stage_binding`. Эталон другой стороны ключ сопоставимости уже сводит к тому же объявлению (иначе `compatible = false` и дельт нет), поэтому
проверки текущей стороны достаточно. Цена ошибки: при несовместимой паре подпись показана рядом с недоступными дельтами (безвредно).

**C4. Типы UI.** `stage_binding` идёт в новый третий генерируемый файл `types.stage-items.generated.ts` (корень `StageEvidence` лежит в
`core/LoadStages.kt`, корни двух прежних файлов закреплены тестами и не меняются). Сверка в `verify-generated-types.mjs` добавляется
отдельным блоком с новым образцом `fixtures/stages/stage-binding.sample.json` (замороженные `fixtures/typed-evidence/*` не трогаются).
Образец сверяется с реальным выводом движка Kotlin-тестом.

**C5. `summary.txt`.** Строка `scope: steady window (<ids>), excluded <N> ms` после `exit_code`; строка `window[<id>]: samples .. errors .. p95_ms ..
p99_ms .. rps ..` на окно после строки `samples/p95/p99`; эта прежняя строка получает суффикс `  whole_run (reference only)`. Без `stage_binding`
файл побайтово прежний.

**C6. `junit.xml`.** Сообщение gate у `failure` и `error` получает суффикс ` scope=steady_window window_ids=<ids> excluded_ms=<N>`; у проходящего
gate (самозакрытый `testcase`) появляется дочерний `<system-out>` с той же строкой `scope=...` без прежнего начала. `INVALID` не имеет
`stage_binding` (R11), поэтому суффикса нет. Без `stage_binding` байты прежние.

**C7. `cli-summary.v1`.** Необязательные `windows[]` (по окну `steady` в порядке `evaluated_window_ids`: `id`, `from_epoch_ms`, `to_epoch_ms`,
`samples`, `errors`, `error_rate`, `p50`, `p95`, `p99`, `max`, `rps`) и число `excluded_ms`; `overall` остаётся «весь прогон». Без `stage_binding` ключей нет.

**C8. Доработки по совету Astra (проверены по коду).** (а) В AsciiDoc фраза идёт в литеральный блок `[subs=specialchars]` как остальные поля
отчёта, не абзацем `NOTE:`: идентификатор стадии это свободный текст, в абзаце он стал бы разметкой (`image:`, `{атрибут}`). (б) Подписи
аналитики и предупреждения сравнения нейтральны («заданы стадии нагрузки»): они включаются по наличию `stage_binding` и не утверждают
вердикт при `NO_POLICY`, `NO_VERDICT`, `DEGRADED`. (в) Экспорт динамики (HTML, AsciiDoc, Confluence) получает примечание, если у текущего
анализа есть `stage_binding` (внутренний параметр `wholeRunWithStages`, публичный JSON динамики не меняется). (г) Плитки «Ключевые метрики
прогона» «Обзора» новой оболочки получают подпись «метрики всего прогона, справочно» (там p95 всего прогона стоит рядом с PASS по окну).
(д) Итог таблицы `Оценено: X, исключено: Y` выводится в UI и HTML. (е) Длительность Kotlin группирует разряды NBSP, как `Intl` ru-RU в UI.
(ж) Побайтовая неизменность без стадий доказана не отсутствием слов, а золотыми файлами `fixtures/stages/plain-reports` (18 файлов: HTML,
AsciiDoc, Confluence, `summary.txt`, `summary.json`, `junit.xml` трёх прогонов), снятыми на коде origin/main ДО правок (тот же тест с
`LTV_UPDATE_PLAIN_REPORTS=1` на дереве PR B), и сравнением текущего вывода с ними. (з) Ручной `StageBindingEvidence` проверяется ещё и на присваиваемость
сгенерированного типа.

## Критерии приёмки

AC11 (видимость: HTML, `summary.txt`, `summary` JSON с двумя окнами, `junit.xml` у FAIL, NO_VERDICT и PASS, карточка вердикта, AsciiDoc и
Confluence; для результата без стадий этих фраз нет, отрицательные тесты), AC11a (`NO_VERDICT`, `DEGRADED`, `NO_POLICY` не утверждают
вердикт), AC13 (ранняя посторонняя выборка видна по epoch-границам в таблице), предупреждение сравнения и подписи динамики и транзакций,
типы UI сверены с реальным `stage_binding`. Прогон без стадий: HTML, `summary.txt`, `cli-summary.v1`, `junit.xml`, AsciiDoc, Confluence и
тексты UI побайтово прежние (новые тесты сравнивают с результатом, у которого убрано свидетельство `stage_binding`, и существующие тесты проходят без правок).

## Проверка (без CI)

На результате слияния со свежим `origin/main`: `gradlew --no-daemon --no-build-cache cleanTest check installDist` (через `Invoke-LtvExclusive`);
`cd ui; npm run typecheck; npm run lint; npm run test:contracts`; оффлайн Playwright (`Invoke-LtvE2E`, обязательно);
`python tools/verify_slice0.py`; `python tools/changelog_assemble.py --check`; markdownlint для изменённых `.md`; совет Codex Astra по диффу до пуша.

Documentation impact: пользовательская документация и руководство в PR D; фрагмент changelog в этом PR есть.
