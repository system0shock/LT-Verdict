# ADR 0017 — кандидаты baseline, явное подтверждение сопоставимости, самосравнение и пустое окно

**Дата:** 2026-09-30

**Статус:** Proposed. Решения Q1-Q6 и два решения по пустому окну и
повторному закреплению baseline приняты владельцем 2026-09-30 и зафиксированы
ниже; ADR переходит в Accepted после его ревью, до принятия код
не меняется. Частично отменяет ADR 0004 (раздел «Сравнение и
интерпретация») и ADR 0010 (ограничение «только manual baseline»).

Материалы ADR (аудит `docs/ui-mockup/audit-2026-09-29.md`, план
`docs/superpowers/plans/2026-09-30-baseline-candidates-and-confirmation.md`,
черновик `2026-09-29-p2-metric-semantics-DRAFT.md`) пока не в `origin/main`:
лежат неотслеживаемыми в основном рабочем дереве владельца. Ссылки `файл:строка`
на код относятся к `b086b3a`.

Номер 0017: 0016 зарезервирован планом «числа ядра». На `origin/main`
(`b086b3a`) заняты 0001-0015, причём 0010 занят дважды.

## Контекст

Аудит 2026-09-29 (`docs/ui-mockup/audit-2026-09-29.md`, п. 5 критических и
«Модель анализа», Major) и проверка по коду `origin/main` (`b086b3a`,
`docs/superpowers/plans/2026-09-30-baseline-candidates-and-confirmation.md`)
подтвердили четыре дефекта сравнения с baseline:

1. Сравниваемый (тестируемый) прогон может входить в набор кандидатов
   statistical baseline. Дефект проявляется при сравнении, а не при выборе:
   baseline глобален (`readBaseline`), запрос `POST /api/baseline` не содержит
   current (`LocalApi.kt:1040-1057`). Проверка членства в
   `BaselineComparison.kt:155` идёт по полной паре `run_id + analysis_id`.
2. Сопоставимость подтверждается автоматически:
   `conditionsConfirmed ?: (mode == STATISTICAL && current in candidates)`
   (`BaselineComparison.kt:155`), а `LocalApi.kt:450-455` для statistical всегда
   передаёт `null`; `BASELINE_MANUAL_REQUIRED` (`LocalApi.kt:1074-1075`,
   `:1102-1103`) запрещает явное решение. Для manual это уже исправлено ADR 0010:
   `null` даёт `UNCONFIRMED`.
3. Самосравнение не сопровождается предупреждением: `compareAnalyses`
   (`BaselineComparison.kt:143-189`) не сверяет `parsedSelection.reference` с
   `current`. Сценарий по умолчанию («Set as baseline», затем «Compare selected
   analysis» на том же analysis) даёт нулевые дельты без единого слова.
4. Пустое окно (`sample_count == 0`): производитель evidence пишет латентность
   `0` (`Metrics.kt:317-323`, `DiagnosticAnalysis.kt:758-768`), потребитель
   принимает её как число (`BaselineComparison.kt:418-431`, `:450-457`,
   `:257-276`). Пустое текущее окно даёт `delta_percent = -100` и статус
   `CANDIDATE` при подтверждённых условиях. Это не путь `ZERO_BASELINE`
   (`:261-262`, `:486`): тот корректно даёт `delta_percent = null` для
   настоящего нулевого baseline. Достижимо только через явно заданное окно
   без сэмплов: `overall` пустым быть не может (`JtlCsvParser.kt:68,100`).
   Вердикт по SLA от baseline не зависит.

## Решение

Решения владельца от 2026-09-30, принятые этим ADR.

### Q1. Текущий прогон среди кандидатов: предупреждение, не отказ

Если `run_id` сравниваемого analysis входит в `candidates` statistical
baseline, ответ `comparison` содержит предупреждение
`CURRENT_IN_CANDIDATE_SET`. Сравнение не блокируется и не получает 422.
Запрос `POST /api/baseline`, `statisticalBaselineSelection` и формат
`local-baseline.v1` не меняются: при выборе тестируемый прогон не определён.

Членство проверяется по `run_id`, а не по паре `run_id + analysis_id`: так же,
как уникальность прогонов среди кандидатов (`BASELINE_DUPLICATE_RUN`,
`LocalApi.kt:1049`, `BaselineComparison.kt:115`). Другой analysis того же
прогона (другая policy или окна) остаётся членом. Для manual baseline код не
выдаётся (`candidates` там состоит из одного baseline, случай покрыт
самосравнением).

### Q2. Подтверждение сопоставимости открыто и для statistical baseline

`GET|POST /api/runs/{runId}/analyses/{analysisId}/baseline-conditions`
работают для обоих режимов. Для statistical binding строится по
`selection.reference` (выбранный победитель) и текущему analysis, при наличии
окон и по паре окон, ровно как для manual (ADR 0010: binding = точные
`baseline` и `current` ссылки и либо `null`, либо `baseline_window +
current_window`). Ответ `comparison` читает `conditions` из хранилища для
любого режима. Ограничение «только для active manual baseline» (ADR 0010,
раздел «Решение») отменяется; код ошибки `BASELINE_MANUAL_REQUIRED` больше не
возвращается.

Без этого после Q3 statistical-сравнение осталось бы навсегда `UNCONFIRMED`, а
оконные строки не могли бы стать `CANDIDATE`.

### Q3. Автоподтверждение по членству в серии отменяется

- `comparability = USER_CONFIRMED` выдаётся только по явно сохранённому
  решению `CONFIRMED` (запись `local-baseline-conditions.v1`) для точной пары
  baseline/current и, при наличии, пары окон. `compareAnalyses` получает
  `conditionsConfirmed == true`; `null` и `false` дают `UNCONFIRMED`.
- Членство в серии ничего не подтверждает. Флажок `comparable` при выборе
  statistical baseline подтверждает только однородность набора кандидатов
  (ADR 0004), но не пару «baseline и это сравнение».
- Существующие statistical-сравнения станут `UNCONFIRMED` до явного решения по
  паре. Файлы не мигрируются (см. «Совместимость и миграция»).
- Это частично отменяет ADR 0004, раздел «Сравнение и интерпретация»: фраза
  «Для членов подтверждённой statistical candidate series можно показать
  `USER_CONFIRMED`» больше не действует.

Основание: данные владельца. Обычно не более десятка протоколов (чаще пять),
сравнение в серии из 2-4 релизов, то есть решений по парам единицы на релиз.
Риск «усталости от подтверждений» (когда подтверждение нажимается
автоматически, не читая) для такого объёма низок; цена автоподтверждения выше:
оно молча превращает неполную информацию об условиях в утверждение
пользователя.

Наблюдение, не решение: statistical-режим на серии из 2-4 прогонов (минимум
кандидатов 3, ADR 0004) статистически слабый, вероятно, на практике чаще
будет использоваться ручной эталон. Это не меняет режимы и не влияет на
решения ADR.

### Q4. Самосравнение предупреждается на двух уровнях

В ответ `comparison` добавляется всегда присутствующее поле
`warnings: string[]`. Значения:

| Код | Условие |
| --- | --- |
| `BASELINE_IS_CURRENT_ANALYSIS` | `selection.reference == current` (`run_id` и `analysis_id` совпадают) |
| `BASELINE_IS_CURRENT_RUN` | `run_id` совпадает, `analysis_id` разный (тот же вход, другая policy, окна или снимок) |
| `CURRENT_IN_CANDIDATE_SET` | режим statistical, `run_id` current входит в `candidates` (Q1) |

Первые два взаимоисключающие. Порядок в массиве детерминирован: сначала
`BASELINE_IS_CURRENT_ANALYSIS` или `BASELINE_IS_CURRENT_RUN`, затем
`CURRENT_IN_CANDIDATE_SET`. Победитель statistical baseline, сравниваемый сам с
собой, получает `["BASELINE_IS_CURRENT_ANALYSIS", "CURRENT_IN_CANDIDATE_SET"]`.

Предупреждения не блокируют сравнение и не меняют числа, `metrics`,
`window_comparison`, `comparability`, `policy_verdict`, validity и coverage.
Сравнение двух окон одного прогона (baseline = тот же analysis, другие окна)
допустимо и не блокируется; предупреждение при этом остаётся, потому что общие
overall-дельты у такой пары нулевые. Это принятая цена простоты правила: код
`warnings` описывает пару ссылок, а не набор запрошенных окон.

### Q5. Пустое окно: N/A для нагрузки, статус окна `INSUFFICIENT_DATA`

Если `sample_count == 0` у baseline-окна или у текущего окна (нагрузки в окне
нет), окно сравнивается так:

- Нагрузочные строки (P50, P95, P99, throughput, error rate), включая
  throughput: значения такого окна, дельта и относительная дельта `null`,
  статус строки `INSUFFICIENT_DATA`, причина `EMPTY_WINDOW`.
- Ресурсные строки (`resource_summary`, `BaselineComparison.kt:293-398`):
  статус `INSUFFICIENT_DATA`, причина `EMPTY_WINDOW`, дельта и относительная
  дельта `null`; значения ресурсов baseline и current остаются видимыми (они
  измерены независимо от нагрузки). `EMPTY_WINDOW` имеет приоритет над
  `RESOURCE_BINDING_MISSING`, `RESOURCE_BINDING_AMBIGUOUS` и `MISSING_METRIC`
  (для отсутствующей привязки значений нет, показываются `null`).
- Статус `window_comparison` всегда `INSUFFICIENT_DATA`: все строки
  `INSUFFICIENT_DATA`, поэтому по приоритету `CANDIDATE > DESCRIPTIVE >
  INSUFFICIENT_DATA` (`:213-219`) отдельного переопределения не требуется, а
  ресурсная строка `CANDIDATE` статус окна с пустой нагрузкой поднять не может.
- В `window_comparison.reasons` добавляются `BASELINE_WINDOW_EMPTY` и/или
  `CURRENT_WINDOW_EMPTY`; порядок: `CONDITIONS_UNCONFIRMED`,
  `BASELINE_WINDOW_EMPTY`, `CURRENT_WINDOW_EMPTY`, `INCOMPLETE_METRICS`.
- Показатель не превращается ни в `0`, ни в «улучшение на 100 %». Пустое окно
  чаще всего означает неверные границы окна или расхождение часов генератора и
  кластера, реже прогрев/остывание либо отказ генератора, чем измеренную
  нулевую нагрузку.
- `ZERO_BASELINE` не трогается: настоящий 0 мс (или нулевой throughput) при
  непустом окне (`sample_count > 0`) остаётся `ZERO_BASELINE` с
  `delta_percent = null` и абсолютной дельтой.

Приоритет: техническая несовместимость и отсутствующее окно
(`INCOMPATIBLE_METRIC_DEFINITION`, `BASELINE_WINDOW_NOT_FOUND`,
`CURRENT_WINDOW_NOT_FOUND`) по-прежнему возвращают `NOT_EVALUATED` до расчёта
строк (`BaselineComparison.kt:200-206`); защита пустого окна действует только
в ветке оценённого окна, поэтому сочетание «пустое окно и несовместимость»
остаётся `NOT_EVALUATED`.

Правило читает только `sample_count` и потому одинаково работает для
сохранённых analyses (латентность `0` в evidence) и для новых (латентность
`null`, план «числа ядра»).

### Повторное закрепление baseline при смене identity

Решение владельца: изменения identity не совмещаются. ADR 0016 (версия
diagnostic-модуля, фаза 2) и ADR 0014 (`limits`, фаза 4) вводятся отдельно,
каждое со своим повторным закреплением baseline. Старые analyses остаются
читаемыми (неизменяемы, читаются по своей identity); сравнение старого baseline
с новым current возвращает `INCOMPATIBLE_METRIC_DEFINITION`, смешанный набор
кандидатов отвергается `BASELINE_MIXED_SEMANTICS`. Интерфейс объясняет причину
(см. требования к UI).

### Q6. Номер

ADR получает номер 0017. Ссылки плана
(`2026-09-30-baseline-candidates-and-confirmation.md`) на 0016 при реализации
заменяются на 0017.

## API-контракт

Приватный API, схем JSON Schema нет; `local-baseline.v1` и
`local-baseline-conditions.v1` не меняются.

```text
GET /api/runs/{runId}/analyses/{analysisId}/comparison
  + warnings: string[]           // всегда; [] при отсутствии предупреждений
  ~ conditions: ConditionRecord|null   // читается для manual И statistical
  ~ comparability                // USER_CONFIRMED только из записи CONFIRMED

GET|POST .../baseline-conditions
  ~ работают при mode = manual и mode = statistical
  ~ binding по selection.reference; BASELINE_MANUAL_REQUIRED удалён

window_comparison
  + нагрузочные строки при sample_count == 0: current/baseline/delta/
    delta_percent = null (у пустой стороны), status = INSUFFICIENT_DATA,
    reason = EMPTY_WINDOW
  + ресурсные строки: status = INSUFFICIENT_DATA, reason = EMPTY_WINDOW,
    delta = delta_percent = null, значения ресурсов видимы
  + status окна = INSUFFICIENT_DATA
  + reasons: BASELINE_WINDOW_EMPTY, CURRENT_WINDOW_EMPTY
```

Новых production-зависимостей, endpoints, сервисов и хранилищ нет. Потребитель
единственный: `ui/src/BaselinePanel.vue`. Требования к нему (иначе Q1 и Q4
остаются невидимыми, а решение по паре может быть сохранено не для той пары):

- каждый код `warnings` показывается пользователю читаемым текстом рядом с
  таблицей сравнения, в порядке ответа; e2e проверяет текст и порядок;
- форма условий показывается для обоих режимов;
- при смене идентификаторов окон форма сбрасывает и заново загружает решение по
  новому binding. Сейчас `watch` окон только скрывает comparison
  (`BaselinePanel.vue:49`), а `loadConditions` вызывается лишь при смене
  выбора (`:48`, `:58-61`); для manual это существующий дефект, здесь он
  становится существенным: решение по паре окон единственный источник
  `USER_CONFIRMED`. E2e: смена окон показывает `UNKNOWN`, а не решение прежней
  пары;
- при `BASELINE_WINDOW_EMPTY` или `CURRENT_WINDOW_EMPTY` показывается подсказка
  «В окне нет нагрузки: проверьте границы окна и синхронизацию часов
  генератора и кластера» с указанием, какое окно пусто (baseline или current);
  e2e проверяет текст;
- при `INCOMPATIBLE_METRIC_DEFINITION` (в строках `metrics` или в причинах
  окна) показывается подсказка «Эталон создан по старым правилам анализа:
  пересчитайте его (заново проанализируйте исходные данные и закрепите
  baseline)»; та же подсказка сопровождает отказ выбора
  `BASELINE_MIXED_SEMANTICS`. Старые анализы остаются читаемыми.

## Изменение поведения

- Существующее statistical-сравнение перестаёт показывать `USER_CONFIRMED`,
  пока пользователь не сохранит решение по паре (при окнах: по паре окон, это
  отдельные записи). Числа, формулы, `min_change_percent`,
  `min_error_rate_delta` и SLA-вердикт не меняются: меняются интерпретация
  (`comparability`, статус и причина оконных строк) и предупреждения.
- Оконные строки `CANDIDATE` для statistical baseline требуют явного решения,
  а не членства. До решения они `DESCRIPTIVE` с причиной
  `CONDITIONS_UNCONFIRMED`.
- Пустое окно: вместо `-100 %` и `CANDIDATE` N/A с причиной.

## Совместимость и миграция

- Файлы не мигрируются. `baseline.json` остаётся валидным, валидация
  `toSelection` не меняется, analysis identity и `analysis-result.v1` не
  затронуты: сравнение считается по запросу и в identity не входит.
- Записи `baseline-conditions/<sha256>.json` ключуются только парой ссылок и
  окнами; режим в ключ не входит. Уже сохранённая manual-запись для той же
  пары применяется к statistical baseline с тем же победителем. Это
  согласуется с ADR 0010 («безопасно недоступны для другого binding и снова
  применимы при явном возврате к той же immutable паре»). Решение относится к
  паре analyses, а не к составу серии: смена набора кандидатов при том же
  победителе старое решение не аннулирует.
- `DELETE /api/baseline` по-прежнему удаляет все записи условий (ADR 0010),
  теперь и для statistical.

## Граница с планом «числа ядра»

Пересечение одно: пустое окно. План «числа ядра» (производитель, черновик
`2026-09-29-p2-metric-semantics-DRAFT.md`) меняет данные evidence:
`window_metric_summary.latency_ms.*` = `null` при `sample_count == 0` в новых
analyses, версию diagnostic-модуля identity и golden. Этот ADR (потребитель)
только читает `sample_count` в `BaselineComparison.kt`.

- Этот ADR не трогает `DiagnosticAnalysis.kt`, `Metrics.kt`, identity и
  golden.
- Защита потребителя нужна независимо: сохранённые analyses неизменяемы и
  навсегда содержат нулевую латентность пустых окон.
- Порядок слияния любой. Guard идемпотентен: при JSON `null` в `latency_ms.*`
  значение и так недоступно, при `0` его перекрывает `sample_count == 0`.
  Тесты покрывают обе формы evidence.
- Заметка для того плана: `modules` с версиями входит в identity
  (`AnalysisResult.kt:77-94`, `load-resource-diagnostics` версии `"2"`) и в
  `SEMANTIC_FIELDS` (`BaselineComparison.kt:921-922`), поэтому повышение версии
  модуля делает старые и новые analyses с diagnostics технически
  несопоставимыми: сравнение старого baseline с новым current даёт
  `INCOMPATIBLE_METRIC_DEFINITION`, смешанный набор кандидатов
  `BASELINE_MIXED_SEMANTICS`. Это последствие для всех сохранённых baseline с
  diagnostics. Решение владельца: это изменение identity вводится отдельно от
  ADR 0014, со своим повторным закреплением baseline (раздел «Повторное
  закрепление baseline при смене identity»).

## Граница с ADR 0014 (ADR-A: снимок на плечо)

ADR 0014 (статус Proposed) вводит `resource_arm` в identity и в
`SEMANTIC_FIELDS`, поднимает ресурсные пределы в блоке `limits` identity и
оставляет число активных baseline на плечо ADR-C.

1. **Baseline по плечу.** Этот ADR не задаёт число активных baseline и правила
   замены при плечах: binding по точной паре `run_id + analysis_id`
   плечо-нейтрален, потому что analyses плеч различаются `analysis_id`
   (разный `resource_snapshot_sha256`). Пара «плечо A против плеча B» после добавления
   `resource_arm` в ключ технически несовместима
   (`INCOMPATIBLE_METRIC_DEFINITION`); подтверждение пользователя это не
   отменяет: `comparability` (решение пользователя) и `compatible` (технический
   ключ) независимы (`BaselineComparison.kt:156`, `:201`, `:548`).
   `BASELINE_IS_CURRENT_RUN` на такой паре сработает (общий `run_id`): шум, но
   безвредный, пара и так несовместима. Оговорка для ADR 0014: `semanticKey` сейчас возвращает `null` (несовместимо),
   если поля identity из `SEMANTIC_FIELDS` нет (`BaselineComparison.kt:582-588`),
   а `resource_arm` у ADR 0014 условное. Без явного правила «отсутствует и
   отсутствует равны» все анализы без плеча стали бы несовместимы со всеми;
   проверки пар без плеча, A/A, A/B и A/без плеча входят в тесты ADR 0014, не
   этого ADR. Область удаления записей условий при двух активных baseline
   решает ADR-C.
2. **Повышение `limits` и ключ сопоставимости.** Подъём ресурсных пределов
   меняет блок `limits` identity только анализов со снимком, поэтому ключ
   сопоставимости старых и новых анализов со снимком различается. Влияние на
   этот ADR:
   - Записи условий не ломаются: `analysis_id` равен SHA-256 канонической identity
     (`AnalysisService.kt:132`), поэтому повторный анализ того же входа с
     другой identity даёт новый `analysis_id`, то есть новую пару, и старое решение к ней не применяется.
     Ложного подтверждения нет; цена: одно повторное решение по паре после
     повторного закрепления baseline, тот же порядок величины, что уже принят в
     Q3.
   - `CURRENT_IN_CANDIDATE_SET` и `BASELINE_IS_CURRENT_RUN` определяются по
     `run_id` и потому не обходятся повторным анализом (другая версия identity
     при том же входе).
   - Смешанный набор кандидатов старых и новых анализов по-прежнему
     отвергается `BASELINE_MIXED_SEMANTICS` (`BaselineComparison.kt:116`), а
     сравнение старого baseline с новым current даёт
     `INCOMPATIBLE_METRIC_DEFINITION`. Обе реакции принадлежат ADR 0014 и
     здесь не меняются.
   - Анализы без снимка (типичный случай «только нагрузка») не затронуты.
   Что делать: ничего сверх решения ADR 0014 (явное повторное закрепление
   baseline с ресурсным снимком, отдельно от повторного закрепления после
   ADR 0016; изменения identity не совмещаются); после него пользователь один
   раз сохраняет решение по новой паре, а интерфейс объясняет причину
   несовместимости подсказкой из требований к UI. Исключение ресурсных потолков из ключа (смягчение
   защиты) остаётся отдельным решением ADR 0014 и здесь не принимается.

## Отклонённые альтернативы

- **422 при выборе, если тестируемый прогон среди кандидатов** (обязательное
  поле `current` в `POST /api/baseline`, код вида
  `BASELINE_CURRENT_IN_CANDIDATES`). Отклонено (Q1): меняет запрос и порядок
  работы в UI («сначала откройте тестируемый прогон»), добавляет контракт,
  ломает существующие тесты на точный набор ключей запроса; при этом baseline
  глобален и должен переживать смену тестируемых прогонов, то есть проверка
  на выборе всё равно не покрывает последующие сравнения. Предупреждение при
  сравнении закрывает случай без изменения запроса.
- **Оставить автоподтверждение по членству в серии.** Отклонено (Q3):
  подтверждение «однородности набора» подменяет подтверждение «условий этой
  пары», а тестируемый прогон, попавший в серию, подтверждается против baseline,
  выбранного с его участием. Цена явного решения (единицы решений на релиз)
  низкая.
- **Оставить statistical всегда `UNCONFIRMED` (закрыть подтверждение).**
  Отклонено (Q2): оконные строки `CANDIDATE` для statistical были бы
  недостижимы навсегда.
- **Throughput пустого окна остаётся числом `0`, N/A только латентность и
  error rate.** Отклонено (Q5): `0 rps` при отсутствии сэмплов выглядит как
  измеренная нулевая нагрузка, а не как неверное окно.
- **Оставить ресурсные строки пустого окна без изменений.** Отклонено
  (решение владельца 1): ресурсная строка `CANDIDATE` поднимала бы статус окна,
  в котором нет нагрузки.
- **Совместить повторное закрепление baseline для ADR 0016 и ADR 0014.**
  Отклонено (решение владельца 2): изменения identity вводятся отдельно, чтобы
  причина каждой несовместимости была понятна.
- **Блокировать сравнение при самосравнении.** Отклонено (Q4): оконное
  сравнение двух окон одного прогона легитимно; предупреждение информирует, не
  запрещает.

## Тесты и golden

Golden-фикстуры, identity и `analysis-result.v1` не меняются: в `fixtures/`
слов baseline/comparison нет; `npm run test:contracts` проверяет только policy
schema. Изменяются тесты (перечень плана, строки относятся к `b086b3a`):

- `BaselineComparisonTest.kt:104, :201, :277, :330` (statistical, ожидают
  `CANDIDATE` только из-за автоподтверждения): добавить
  `conditionsConfirmed = true`; `:163` (точный набор ключей): добавить
  `"warnings"`; `:523` (`statistical candidate comparison carries user
  confirmation`, утверждает старую политику): заменить.
- `LocalApiTest.kt` ~618-632 (точное множество ключей `comparison`): добавить
  `warnings`; новый тест статистического подтверждения по паре.
- `ui/e2e/baseline.spec.ts:112-135` (statistical ожидает `USER_CONFIRMED` по
  членству): переписать; `ui/e2e/diagnostics.spec.ts:98` (мок без `warnings`):
  добавить `warnings: []`.
- Новые тесты: пустое текущее и пустое baseline-окно в обеих формах evidence
  (нули и `JsonNull`); регрессия «настоящий 0 мс при `sample_count > 0` остаётся
  `ZERO_BASELINE`» (уже есть, `BaselineComparisonTest.kt:201`); пустое окно
  вместе с несовместимостью или отсутствующим окном остаётся `NOT_EVALUATED`;
  ресурсная строка, которая без пустого окна была бы `CANDIDATE`, при пустом
  окне получает `INSUFFICIENT_DATA` и `EMPTY_WINDOW`, значения ресурсов
  видимы, статус окна `INSUFFICIENT_DATA`; e2e подсказок «в окне нет нагрузки»
  и «эталон создан по старым правилам»;
  перенос старой manual-записи на statistical baseline с тем же победителем,
  смена набора кандидатов при том же победителе и последующий
  `DELETE /api/baseline` (`LocalApiTest`); все три
  предупреждения, включая другой analysis того же прогона и члена серии не
  победителя; явное `CONFIRMED` меняет только `comparability`, не `metrics`.

Валидационные обвязки (`StatisticalValidationTest`, `UsefulnessValidationTest`,
Python-оракулы `tools/stats_validation.py`, `tools/applicability_validation.py`)
сверяют точные JSON-пути; лишний ключ `/comparison/warnings` их не ломает, но
`window_comparison/reasons` изменятся, если у какого-либо случая окно без
сэмплов (`applicability_validation.py:346`). Заключение «не затронуты»
допустимо только после прогона (см. план проверки).

## План проверки

Реализация выполняется отдельным PR после принятия ADR. Наблюдаемые критерии:

1. Statistical baseline без сохранённого решения: `comparability =
   UNCONFIRMED`; после `POST baseline-conditions {"decision":"CONFIRMED"}` для
   пары: `USER_CONFIRMED`, `metrics` неизменны.
2. Три предупреждения выдаются по таблице Q4, включая `run_id`-членство для
   другого analysis того же прогона; ключ `warnings` присутствует всегда.
3. Пустое окно (нули и `null` в evidence): все строки, нагрузочные и ресурсные,
   `INSUFFICIENT_DATA` с `EMPTY_WINDOW`, значения ресурсов видимы, статус окна
   `INSUFFICIENT_DATA`; `ZERO_BASELINE` при непустом окне сохраняется; UI
   показывает подсказку о границах окна и часах.
4. Команды: `.\gradlew.bat test --tests "io.ltverdict.core.BaselineComparisonTest"
   --tests "io.ltverdict.web.LocalApiTest"`, затем
   `StatisticalValidationTest`, `UsefulnessValidationTest`,
   `StatisticalValidationIntegrationTest`;
   `python -m unittest tools.test_stats_validation
   tools.test_applicability_validation tools.test_usefulness_validation`;
   `npm --prefix ui run typecheck|lint|e2e`; `.\gradlew.bat --no-daemon clean
   check installDist`; `npx --yes markdownlint-cli2@0.23.2 "**/*.md"`;
   `git diff --check`; `rg -n "manualBaselineReference|baselineManualRequired|
   BASELINE_MANUAL_REQUIRED" src ui/src docs/user` пуст.
5. Документация в том же PR: `docs/user/slice-1-local-analysis.md`
   (~строки 356-386), `docs/development-plan-v0.6.md` (~310-314),
   `CHANGELOG.md` (Changed), строки статуса ADR 0004 и 0010 со ссылкой на этот
   ADR.

## Что не входит

- Изменение `POST /api/baseline`, `statisticalBaselineSelection`, форматов
  `local-baseline.v1` и `local-baseline-conditions.v1`; миграция файлов.
- Изменение `DiagnosticAnalysis.kt`, `Metrics.kt`, analysis identity,
  `analysis-result.v1`, golden, `RunBundle` (план «числа ядра», ADR 0014).
- Автоматическое доказательство одинаковых условий, влияние baseline на
  verdict, экспорт comparison, Jenkins, единый идентификатор «тестируемого»
  прогона (он вне выбора baseline).
- Число активных baseline и область baseline по плечу (ADR-C).
- Обнаружение того же теста, экспортированного заново (другой `run_id`):
  предупреждения работают по идентичности входа, а не по содержанию
  эксперимента.

## Решения владельца (2026-09-30)

1. Пустое окно и ресурсные строки (Q5): принят вариант B. При пустом окне
   нагрузки статус окна всегда `INSUFFICIENT_DATA`, ресурсные строки получают
   причину `EMPTY_WINDOW` и статус `INSUFFICIENT_DATA`, значения ресурсов
   остаются видимыми. Причины пустого окна чаще всего: неверные границы окна
   или расхождение часов генератора и кластера, реже прогрев/остывание либо
   отказ генератора; поэтому интерфейс подсказывает «в окне нет нагрузки,
   проверьте границы окна и часы».
2. Повторное закрепление baseline: изменения identity не совмещаются. ADR 0016
   (версия diagnostic-модуля, фаза 2) и ADR 0014 (`limits`, фаза 4) вводятся
   отдельно, каждое со своим повторным закреплением. Интерфейс подсказывает
   «эталон создан по старым правилам, пересчитайте»; старые анализы остаются
   читаемыми.

Открытых вопросов к владельцу не осталось; ADR остаётся Proposed до принятия
владельцем, код по нему не начинается.
