# ADR 0018: реализация (правила платформы, малая выборка, окна правил) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** реализовать принятый ADR 0018 десятью независимыми срезами (один PR = одна законченная задача): минимум выборки с меткой `SMALL_SAMPLE`, окна правил `window_ids`, платформенные правила по сервисам с допуском пропусков, базовый профиль SLA и их отображение в карточке вердикта.

**Architecture:** `policy.v1` расширяется на месте необязательными полями (`defaults`, `min_samples`, `window_ids`, `platform_services`, `platform_rules`, `platform_coverage`). Ядро остаётся одним: бизнес-правила проходят общий `evaluatePolicy` с gate выборки, платформенное правило разворачивается по сервисам в обычные `ResourceRuleV1` и оценивается существующим `evaluateRule`. Новое поведение, меняющее исход для уже допустимого входа, фиксируется условным блоком `verdict_gates` в identity анализа (вне ключа сопоставимости baseline) в том же PR, что и поведение.

**Tech Stack:** Kotlin (JUnit 5, Gradle `gradlew.bat`), Vue 3 + TypeScript (Playwright, `npm --prefix ui`), JSON Schema (Ajv), Markdown (markdownlint). Новых production-зависимостей нет.

**Spec:** `docs/adr/0018-policy-platform-rules-small-samples.md` (ветка `docs/adr-0018-policy-platform-rules`, коммит `b043695`, статус Accepted 2026-10-02); согласующие: `docs/adr/0019-release-history-and-baseline-eligibility.md` (ветка `docs/adr-0019-0020-release-history-pod-data`), `docs/adr/0014-resource-series-limits-autostep-arm-api.md`, `docs/adr/0017-baseline-candidates-and-confirmation.md`.

База проверки кода: `origin/main` `8357635`. Все ссылки `файл:строка` ниже сверены с ней; перед стартом каждого среза сверить заново (`git diff --stat origin/main`).

## REQUESTED / REQUIRED / NOT REQUIRED / EXPECTED FILES (AGENTS.md)

```text
REQUESTED: план реализации ADR 0018 (без кода), разбитый на минимально
  связные срезы; для каждого: цель, файлы, красные тесты, контракты и версии,
  порядок, размер, риски, что не входит, документация.
REQUIRED TO ACHIEVE IT:
  - S1  min_samples/defaults, gate выборки, метка SMALL_SAMPLE, verdict_gates;
  - S2  UI: метки и объяснения малой выборки;
  - S3  ступень capacity со SMALL_SAMPLE не подтверждается (+ ключи identity);
  - S4  видимость выбранной политики (хэш) и тест «несколько политик»;
  - S5  window_ids у бизнес-правил, NO_POLICY для окна без правил;
  - S6  platform_rules: схема, разворачивание по сервисам, покрытие, окна;
  - S7  допуск пропусков, RESOURCE_GAPS, серия через пропуск;
  - S8  UI: платформенные правила и покрытие;
  - S9  базовый профиль SLA и независимые плечи (число плеч любое);
  - S10 отбор кандидатов statistical baseline по verdict_gates.
NOT REQUIRED (report-only, см. раздел «Не входит»): ADR 0016, срезы ADR 0014
  (arm, interval_max, автошаг), ADR 0019/0020, редактор политики U4, библиотека
  политик, общий заголовок «все плечи», исправление ложного PASS на коротком
  окне у правил снимка и профиля, калибровка 20/100/5 %/3 на стенде.
EXPECTED FILES TO CHANGE: по срезам, см. «Карта срезов» и блок Files каждого
  среза. Если срез разрастается сверх списка, остановиться и объяснить
  (AGENTS.md, п. 10).
```

## Global Constraints

- `policy.v1` расширяется на месте: `schema_version` и `input_versions.policy` остаются `policy.v1` (`AnalysisResult.kt:99`). Старый валидный файл сохраняет те же canonical bytes и `policy_sha256` (`Policy.kt:67-70`); меняется только его оценка из-за gate по умолчанию (ADR 0018, раздел 7).
- Ключ сопоставимости baseline не меняется ни одним срезом: `SEMANTIC_FIELDS` (`BaselineComparison.kt:942-943`) и `COMPARISON_SEMANTIC_FIELDS` (`RunComparison.kt:424-425`) остаются прежними; `limits`, `modules`, `input_versions` не трогаются. Golden «ключ до и после» обязателен в S1.
- Правило идентичности: срез, меняющий исход для уже допустимого входа, меняет identity в том же PR (иначе `AnalysisService.kt:133-142` вернёт устаревший сохранённый результат под прежним `analysis_id`). Каждый ключ `verdict_gates` появляется вместе с поведением, которое он фиксирует. Значения ключей — строки (как в `limits`).
- Срез S1 меняет identity и потому выпускается отдельным PR, не совмещаемым с ADR 0016 и `resource_arm` (ADR 0017:173-180).
- Коды выхода CLI не меняются: 0 (PASS/NO_POLICY), 2 (FAIL), 3 (NO_VERDICT/DEGRADED), 4 (INVALID/ошибка ввода), 5 (невалидная политика) (`CommandLine.kt:277-290`, `:720-724`). Метка малой выборки читается только из JSON.
- Порядок свёртки остаётся `NO_VERDICT > FAIL > PASS` (`Policy.kt:137-142`, `WindowPolicy.kt:46-63`, `ResourceStatistics.kt:66-72`). Информационная причина (`SMALL_SAMPLE`, `RESOURCE_GAPS`) не превращает правило в `NO_VERDICT`, но делает `analysis_coverage.status = INCOMPLETE` (`AnalysisResult.kt:152`).
- Каждый код причины, добавленный в Kotlin, получает запись в `ui/src/verdictReasons.ts` в том же PR: тест `e2e/verdict-summary.spec.ts:307-319` требует, чтобы каждый код из `REASONS` встречался в `src/main/kotlin` как `"CODE"`. Блокирующие причины — `noVerdict: true`, информационные — `false`.
- Новые поля evidence и поля существующих типов только аддитивны; верхний уровень `analysis-result.v1` закрыт и не меняется (`docs/contracts/result/v1/analysis-result.schema.json`).
- Значения умолчаний — запасные константы ядра: `MIN_SAMPLES_FLOOR = 20`, `MIN_SAMPLES_DEFAULT = 100`, `MAX_MISSING_FRACTION_DEFAULT = 0.05`, `MAX_GAP_CELLS_DEFAULT = 3`. Это допущения владельца, не калибровка стенда; настраиваются полями политики.
- Тестовые фрагменты ниже показывают новый код; недостающие импорты (`Json`, `jsonObject`, `jsonArray`, `jsonPrimitive`, `assertNotEquals`, `Files`, `Path`, `SampleKind`, `TransactionIdentity`, `TransactionSummary`) добавляются по месту, ошибку компиляции укажет Gradle.
- Проза и документация на русском, идентификаторы и коды на английском. Коммиты атомарные, Conventional Commits, в индекс только файлы задачи; push, merge, rebase — только по явному разрешению владельца.
- Перед каждым срезом: `git status --short --branch`, отдельный воркстри и ветка (`git worktree add .worktrees/<имя> -b <ветка> origin/main`), базовый прогон `.\gradlew.bat test` и `npm --prefix ui run typecheck` с записью числа тестов.

## Review Focus

Входы и условия, которые спецификация подразумевает, но задачи не покрывают отдельно; для каждого есть тест в указанном срезе.

1. Редкая транзакция в одном окне: счёт `n` берётся по области правила в оцениваемом окне, а не по всему прогону; `n = floor - 1`, `floor`, `min - 1`, `min` дают соответственно `INSUFFICIENT`, `SMALL_SAMPLE`, `SMALL_SAMPLE`, `FULL` (S1, S5).
2. Малая выборка не скрывает достоверный `FAIL` и не маскирует блокирующую причину: `SMALL_SAMPLE` + `FAIL` остаётся `FAIL`, код 2; `INSUFFICIENT_SAMPLES` у одного правила при `FAIL` у другого даёт `NO_VERDICT`, находка видна (S1).
3. Старый сохранённый результат и повторный анализ: тот же вход и тот же файл политики после S1 получают новый `analysis_id`; старый анализ не перезаписывается; без политики identity не меняется (S1).
4. Серия через пропуск: `[нарушение, нарушение, пропуск, нарушение]` при `min_consecutive_cells = 3` и включённом допуске даёт `NO_VERDICT` с «предположительной» находкой, а не `PASS` и не недоказанный `FAIL` (S7).
5. Сервис исчез целиком, дублируется, имеет другую единицу или агрегацию, окно короче серии, `except` или `window_ids` с опечаткой: каждое даёт ошибку валидации либо `NO_VERDICT`, ни один случай не даёт молчаливого `PASS` (S5, S6).
6. Три плеча одного прогона с одной политикой: три независимых результата, общего вердикта нет, `analysis_id` различаются по хэшу снимка (S9).

## Карта срезов

Порядок номеров = рекомендуемый порядок слияния. Размер: S (до 150 строк diff без тестов), M (150–400), L (400–800), XL (больше 800 или больше 20 файлов).

| Срез | Ветка | Что | ADR | Зависит от | Меняет identity | Размер |
| --- | --- | --- | --- | --- | --- | --- |
| S1 | `feat/policy-min-samples-gate` | `defaults`, `min_samples`, gate, `SMALL_SAMPLE`, `verdict_gates` | D1, D2, D9 | ADR 0016 (внешний, не в этом PR) | да, новый `verdict_gates` (с политикой) | L, по правке фикстур рискует перейти в XL |
| S2 | `feat/ui-small-sample-labels` | карточка F1, обзор, таблица правил | D2 | S1 | нет | M |
| S3 | `feat/capacity-stage-sample-gate` | ступень не подтверждается при малой выборке | Q14 | S1 | да, ключи capacity в `verdict_gates` | M |
| S4 | `feat/ui-policy-hash` | хэш политики рядом с вердиктом, тест «две политики» | D8, раздел 2 | S1 | нет | S |
| S5 | `feat/policy-window-ids` | `window_ids`, окно без правил = `NO_POLICY`, `RULE_WINDOW_NOT_FOUND` | D5 | S1 | нет | M |
| S6 | `feat/policy-platform-rules` | `platform_rules` в строгом режиме | D3, D4, D8 | S5; ADR 0014 `interval_max` (внешний, жёстко) | нет | XL, один PR (см. риск) |
| S7 | `feat/platform-gap-tolerance` | допуск пропусков, `RESOURCE_GAPS`, серия через пропуск | D6 | S6 | да, 2 условных ключа | M |
| S8 | `feat/ui-platform-rule-lines` | строки платформенных правил, покрытие, причины | D3, D6 | S2, S7 | нет | M |
| S9 | `feat/platform-base-profile` | предустановка SLA, независимые снимки плеч | D10 | S6, S7 | нет | S–M |
| S10 | `feat/baseline-candidates-need-gates` | `verdict_gates` обязателен для statistical baseline | D11 | S1; согласовать с ADR 0019 D5a | нет | S + правка тестов M |

Зависимости вне плана (не реализуются здесь, их срезы выпускаются отдельными PR):

- ADR 0016: ограничение перцентиля наблюдаемым максимумом. Рекомендуется до S1: без него SMALL_SAMPLE-вердикт по p95 на 20–99 наблюдениях может дать ложный `FAIL` (ADR 0018, «Контекст»). Не блокирует код S1 технически; решение владельца.
- ADR 0014: поле `arm`/`resource_arm` и агрегация `interval_max` (и `interval_min`). В `src/main` их нет (`ResourceSnapshot.kt:92-96` содержит только `INTERVAL_MEAN`, `INTERVAL_RATE`; `grep resource_arm|interval_max src/main` пуст). **`interval_max` — жёсткая предпосылка S6, S7 и S9:** ADR 0018 (раздел 3, строка `platform_coverage`) требует у правила покрытия агрегацию `interval_max`, без неё валидатор отвергает любую политику с платформенным `sla`-правилом. Допустимые агрегации платформенного правила берутся из `ResourceAggregation.entries`, поэтому после появления `interval_max` и `interval_min` код S6 их подхватывает; перечень в схеме синхронизируется тестом S6. S1–S5 и S10 от этого среза не зависят. `arm`/`resource_arm` в этом плане не требуются.

## Решения плана и отличия от первоначальной декомпозиции

Предположенная декомпозиция (a)–(f) уточнена по коду; каждое отличие обосновано.

1. **`verdict_gates` входит в S1, а не в отдельный срез.** Gate включается по умолчанию, а `AnalysisService.analyze` возвращает сохранённый результат при совпадении `analysis_id` (`AnalysisService.kt:133-142`). Без нового блока identity тот же вход и та же политика отдали бы результат старого поведения. Отдельным остаётся только фильтр кандидатов baseline (S10): он зависит от наличия блока.
2. **Блок `verdict_gates` растёт вместе с поведением** (отличие от текста ADR, где перечислены сразу пять ключей): S1 при `policy != null` пишет `min_samples_floor`, `min_samples_default`, `throughput_exempt`; S3 при `capacity != null` добавляет `capacity_stage_sample_gate` и, если политики нет, `min_samples_default`; S7 при непустом `platform_rules` добавляет `max_missing_fraction_default` и `max_gap_cells_default`. Так ключи не обещают поведения, которого ещё нет, а анализы без платформенных правил не меняют identity второй раз. Итоговый блок совпадает с ADR. Правка ADR 0018, раздел 8, внесена (решение владельца 2026-10-04, п. 1 и 2: поэтапный блок принят, capacity без политики пишет блок).
3. **Добавлен срез S5 (`window_ids`).** Решение D5 не входило в предположенную декомпозицию, но платформенные правила (S6) и сценарий «редкая транзакция в стадии» зависят от него; он не меняет исход существующих политик (все правила применимы во всех окнах), поэтому identity не затрагивает.
4. **Срез «политика на запуск и несколько политик» сжат до S4.** В ядре выбор политики уже есть (`--policy`, `CommandLine.kt:116-119`; часть `policy` в `LocalApi.kt:1216-1227`; поле `Policy file`). Кода для нескольких политик не нужно. Реально отсутствует только отображение: в результате и в списке анализов нет `policy_id`, есть лишь `policy_sha256` (`RunBundleStore.kt:235`, `LocalApi.kt:903`). S4 показывает хэш и фиксирует тестом, что две политики дают два `analysis_id` при одном ключе сопоставимости. `policy_id` в этом срезе не вводится: поле в результате или списке анализов принято владельцем (решение 2026-10-04, п. 5) и оформляется отдельным пунктом при реализации S1.
5. **Срез «платформенные правила» разделён на S6 (строгий режим) и S7 (допуск).** S6 с допуском 0/0 равен нынешнему строгому поведению и безопасен как самостоятельный PR; S7 добавляет допуск и меняет identity только для политик с платформенными правилами. Разделять S6 дальше нельзя: принятая, но не вычисляемая секция `platform_rules` дала бы молчаливый `PASS`.
6. **UI разделён на S2 (после S1) и S8 (после S7).** Метки малой выборки не должны ждать платформенных правил. Группировка по плечам в UI не входит: в `src/main` нет `arm`, а анализ одного плеча уже отображается как есть.
7. **S6, S7 и S9 заблокированы внешней зависимостью.** Редакция ADR 0018 от 2026-10-02 (`b043695`) требует у правила покрытия агрегацию `interval_max` для каждой пары «сервис × окно» и отвергает `gt 0` с `interval_min`; в коде `interval_max` нет. Промежуточного варианта с `interval_mean` для покрытия ADR не допускает, поэтому S6 стартует после среза `interval_max` ADR 0014 (решение владельца 2026-10-04, п. 4).
8. **Автошаг (Q12) в код не входит.** Автошаг ADR 0014 не реализован; правка касается только текста ADR 0014 (отдельный docs-PR владельца).

## Решения владельца 2026-10-04

Блокировки слияния, перечисленные ранее для вопросов 1-4, сняты. Ответ владельца передал оркестратор: «Малая выборка пригодна с варном по решению юзера. Политику включаем, нужна наблюдаемость. Остальное по рекомендациям». Решения записаны в ADR 0018, раздел «Решения владельца (2026-10-04)».

1. Решение владельца 2026-10-04 (по рекомендации): ключи `verdict_gates` вводятся поэтапно, по мере появления поведения (решение 2 выше), вместо пяти сразу. Правка ADR 0018, раздел 8, внесена поправкой.
2. Решение владельца 2026-10-04 (по рекомендации): capacity без политики (Q14): блок пишется и при `capacity != null` (S3).
3. Решение владельца 2026-10-04: конфликт ADR 0018 и ADR 0019 решён в пользу предупреждения: малая выборка пригодна как baseline с предупреждением `BASELINE_SMALL_SAMPLE` по решению пользователя. `SMALL_SAMPLE` не делает кандидата неподходящим автоматически: `INCOMPLETE` только из-за причины `SMALL_SAMPLE` не исключает его ни из statistical-отбора, ни по `BASELINE_CANDIDATE_INCOMPLETE`; `INSUFFICIENT` и иные причины `INCOMPLETE` исключают. Сигнал для ADR 0019: `sample_mode` в evidence `policy_check` и причина `SMALL_SAMPLE` в `analysis_coverage.reasons`. Условие отбора правится в S10 вместе с D5a ADR 0019; ADR 0019, раздел 6, согласован.
4. Решение владельца 2026-10-04 (по рекомендации): порядок внешних срезов - ADR 0016, затем `interval_max` ADR 0014, затем S6-S9 (S1-S5 и S10 `interval_max` не ждут).
5. Решение владельца 2026-10-04: `policy_id` рядом с вердиктом нужен («политику включаем, нужна наблюдаемость»; трактовка подтверждена владельцем: «понял верно»). Для него потребуется поле в результате или списке анализов, то есть изменение публичного контракта: оно оформляется отдельным пунктом при реализации S1 (до кода), вместе со срезом для него. Срез S1a предложен в разделе «Пункт контракта: `policy_id` рядом с вердиктом (вне S1)»; до его принятия S4 ограничивается хэшем.
6. Решение владельца 2026-10-04 (по рекомендации): имена контракта подтверждены - поле evidence `sample_floor`; коды `MIN_SAMPLES_OUT_OF_RANGE`, `FIELD_NOT_APPLICABLE`, `BASELINE_CANDIDATE_GATES_UNKNOWN`, `CAPACITY_INSUFFICIENT_SAMPLES`; тип evidence `rule_window_check`.
7. Решение владельца 2026-10-04 (по рекомендации): S10 выпускается вместе с D5a ADR 0019; statistical baseline для анализов без политики (нет `verdict_gates`) перестаёт работать.

## Пункт контракта: `policy_id` рядом с вердиктом (вне S1)

Решение владельца 2026-10-04, п. 5: `policy_id` рядом с вердиктом нужен («политику включаем, нужна наблюдаемость»; трактовка подтверждена). Запись по AGENTS.md, п. 5, перед кодом S1.

Состояние кода: `policy_id` есть только в файле политики. В `analysis-result.v1` его нет (верхний уровень закрыт, `additionalProperties: false`), в `identity.json` хранится только `policy_sha256` (`AnalysisResult.kt:43`), список `GET /api/runs/{runId}/analyses` отдаёт `analysis_id`, `policy_sha256`, `policy_verdict`, `run_validity` (`LocalApi.kt:903`, `RunBundleStore.kt:235`).

Решение: `policy_id` в S1 не реализуется. Причины: (1) место поля не определено ни в ADR 0018, ни в плане (поле в верхнем уровне результата, аддитивное поле списка анализов или чтение сохранённой политики из набора анализа); (2) это изменение публичного контракта, независимое от gate выборки: оно трогает схему результата или API, golden-результаты и общие фикстуры (параллельные срезы ADR 0016 и автошага правят те же файлы); (3) п. 10 MINIMAL-CHANGE: S1 уже L-XL.

Предложение отдельного среза S1a (`feat/ui-policy-identification`, зависит от S1, размер S-M, объединяется с S4): добавить `policy_id` (строка или `null` без политики) необязательным аддитивным полем элемента списка анализов `GET /api/runs/{runId}/analyses`; значение берётся из сохранённой канонической политики анализа, `analysis-result.v1` и identity не меняются, поэтому `analysis_id` не пересчитывается; интерфейс показывает `policy_id` и короткий хэш в карточке вердикта. Вариант с полем в верхнем уровне `analysis-result.v1` отвергается как более широкое изменение (закрытая схема, golden). Окончательный выбор места поля остаётся за владельцем до кода S1a.

## S1. Минимум выборки, метка SMALL_SAMPLE, verdict_gates

**Цель:** бизнес-правила политики получают пол и минимум наблюдений (по умолчанию 20 и 100, настраиваемые), расчётный `PASS`/`FAIL` при `floor <= n < min` сохраняется с меткой `SMALL_SAMPLE`, при `0 < n < floor` правило даёт `NO_VERDICT` с `INSUFFICIENT_SAMPLES`, `throughput_rps` из gate исключён; запасные константы фиксируются в identity.

**Ветка:** `feat/policy-min-samples-gate`. **Размер:** L; по числу правок фикстур и тестов может дойти до XL. По AGENTS.md п. 10: если после шага 4.3 список затронутых файлов тестов превысит 25, остановиться и сообщить владельцу, а не продолжать молча.

**Что не входит:** окна правил (S5), UI (S2), capacity (S3), фильтр baseline (S10), допуск пропусков, платформенные правила, изменение кода выхода CLI, `policy_id` в результате (принят владельцем 2026-10-04; контракт и срез для него оформляются отдельным пунктом перед кодом S1, в этом плане среза для него пока нет).

**Контракты и версии:**

- `policy.v1` на месте: необязательный `defaults` {`sample_floor`, `min_samples`}, необязательное `min_samples` у правила (кроме `throughput_rps`). Целые 1..1 000 000.
- Новые коды валидации: `MIN_SAMPLES_OUT_OF_RANGE` (значение вне 1..1 000 000), `MIN_SAMPLES_BELOW_FLOOR` (действующий минимум ниже действующего пола), `FIELD_NOT_APPLICABLE` (`min_samples` у `throughput_rps`; код общий, его же используют S7).
- Результат: к `policy_check` аддитивно добавляются `sample_count`, `sample_floor`, `min_samples`, `sample_mode` (`FULL`, `SMALL_SAMPLE`, `INSUFFICIENT`, `NOT_GATED`). При `n = 0` полей нет; у `NOT_GATED` только `sample_count` и `sample_mode`. Новые причины: `INSUFFICIENT_SAMPLES` (блокирует), `SMALL_SAMPLE` (информационная, только `analysis_coverage.reasons`).
- Identity: верхнеуровневый объект `verdict_gates` при `policy != null`: `{"min_samples_floor":"20","min_samples_default":"100","throughput_exempt":"true"}`. Не входит в `SEMANTIC_FIELDS`. Анализ без политики не меняется. Старые сохранённые анализы неизменны; тот же вход и тот же файл политики дают новый `analysis_id`.
- Golden: `fixtures/slice1/identity/analysis-identity.v1.json` и `analysis-identity-resources.v1.json` получают в конец `,"verdict_gates":{"min_samples_default":"100","min_samples_floor":"20","throughput_exempt":"true"}`. Ожидаемые SHA-256 после правки (пересчитаны 2026-10-04 от байт `origin/main` `135ef5a`, после ADR 0016 срезов 1-2: прежние значения плана относились к базе `8357635` и устарели; расхождение — повод расследовать, а не подгонять): `analysis-identity.v1.json` — `cdcc45e4cac5bddfdbfda2ea934bc321564de4d0a8bbc3fd38b082bb5bba3f2f`; `analysis-identity-resources.v1.json` — `be4bcb97e8b1202cfc02a5a7f11a4973c566552c5670e164eacb2fd5821414a4`.
- CLI: коды 0/2/3 без изменений; `SMALL_SAMPLE` и `sample_mode` — только в JSON (stdout).

**Files:**

- Modify: `src/main/kotlin/io/ltverdict/core/Model.kt:23-35` (поля `defaults`, `minSamples`, новый `PolicyDefaultsV1`)
- Modify: `src/main/kotlin/io/ltverdict/core/Policy.kt:81-144` (gate, разделение причин), `:233-263` (`policyCheck`), `:427-469` (разбор `defaults`, `min_samples`), константы рядом с `:30-35`
- Modify: `src/main/kotlin/io/ltverdict/core/AnalysisResult.kt:43` (блок `verdict_gates`)
- Modify: `docs/contracts/policy/v1/policy.schema.json`
- Create: `docs/contracts/policy/v1/examples/valid/sample-gate.json`, `docs/contracts/policy/v1/examples/invalid/min-samples-below-floor.json`
- Modify: `fixtures/slice1/manifest.json` (`policy_examples`, `artifacts`: хэши схемы, двух goldens, двух `.sha256`, `pass.json`, `fail.json`, новых примеров)
- Modify: `fixtures/slice1/identity/analysis-identity.v1.json`, `analysis-identity.sha256`, `analysis-identity-resources.v1.json`, `analysis-identity-resources.sha256`
- Modify: `fixtures/slice1/policies/pass.json`, `fail.json` (блок `defaults` 1/1, см. задачу 4)
- Modify: `ui/src/verdictReasons.ts` (две записи), `ui/src/types.ts` (необязательные поля `Policy`, `PolicyRule`, `PolicyCheckEvidence`)
- Modify (тесты): `src/test/kotlin/io/ltverdict/core/PolicyTest.kt`, `PolicyEvaluationTest.kt`, `AnalysisResultGoldenTest.kt`, `BaselineComparisonTest.kt`, `src/test/kotlin/io/ltverdict/cli/CommandLineTest.kt`, `src/test/kotlin/io/ltverdict/fixtures/FixtureManifestTest.kt`, `src/test/kotlin/io/ltverdict/web/LocalApiTest.kt` (константа `PASS_POLICY_SHA256`); точный список остальных — по шагу 4.3
- Modify: `docs/user/slice-1-local-analysis.md`, `CHANGELOG.md`

**Interfaces:**

- Produces (Kotlin, `package io.ltverdict.core`):
  - `internal data class PolicyDefaultsV1(val sampleFloor: Long? = null, val minSamples: Long? = null)`
  - `PolicyV1(schemaVersion, policyId, rules, defaults: PolicyDefaultsV1? = null)`
  - `PolicyRuleV1(id, metric, operator, threshold, scope, minSamples: Long? = null)`
  - `internal const val MIN_SAMPLES_FLOOR = 20L`, `internal const val MIN_SAMPLES_DEFAULT = 100L`
  - `evaluatePolicy(...)`: сигнатура не меняется; `PolicyEvaluation.coverageReasons` теперь содержит и блокирующие, и информационные причины, а `verdict` определяется только блокирующими.
- Consumes: `MetricSummary.sampleCount: Long` (`Metrics.kt:49-55`).
- Для S3, S5, S7: поле `defaults` расширяется (S7 добавляет `maxMissingFraction`, `maxGapCells`); константы `MIN_SAMPLES_*` используются в `CapacityAnalysis.kt`.

### Task 1: Контракт политики (разбор, схема, примеры)

- [ ] **Step 1.1: Красные тесты разбора**

В `src/test/kotlin/io/ltverdict/core/PolicyTest.kt` добавить (приватные помощники `assertInvalid`, `validPolicy` уже есть в классе; новый помощник `policyJson` добавить рядом с `policyWithRules`):

```kotlin
    @Test
    fun `defaults and rule minimum parse and an old file keeps its shape`() {
        val old = validatePolicy(ByteArrayInputStream(validPolicy().encodeToByteArray())) as PolicyValidation.Valid
        assertEquals(null, old.policy.defaults)
        assertEquals(null, old.policy.rules.single().minSamples)

        val source =
            """{"schema_version":"policy.v1","policy_id":"p","defaults":{"sample_floor":10,"min_samples":50},"rules":[""" +
                """{"id":"r","metric":"response_time_p95_ms","operator":"lte","threshold":100,"scope":{"kind":"overall"},"min_samples":200}]}"""
        val valid = validatePolicy(ByteArrayInputStream(source.encodeToByteArray())) as PolicyValidation.Valid

        assertEquals(PolicyDefaultsV1(sampleFloor = 10, minSamples = 50), valid.policy.defaults)
        assertEquals(200L, valid.policy.rules.single().minSamples)
    }

    @Test
    fun `sample limits fail closed at their exact fields`() {
        listOf(
            Case("floor zero", policyJson(defaults = """{"sample_floor":0}"""), "/defaults/sample_floor") to "MIN_SAMPLES_OUT_OF_RANGE",
            Case("minimum too large", policyJson(defaults = """{"min_samples":1000001}"""), "/defaults/min_samples") to
                "MIN_SAMPLES_OUT_OF_RANGE",
            Case("fraction", policyJson(defaults = """{"sample_floor":1.5}"""), "/defaults/sample_floor") to "INVALID_TYPE",
            Case("unknown defaults field", policyJson(defaults = """{"x":1}"""), "/defaults/x") to "UNKNOWN_FIELD",
            Case("minimum below explicit floor", policyJson(defaults = """{"sample_floor":50,"min_samples":20}"""), "/defaults/min_samples") to
                "MIN_SAMPLES_BELOW_FLOOR",
            Case("floor above the default minimum", policyJson(defaults = """{"sample_floor":150}"""), "/defaults/sample_floor") to
                "MIN_SAMPLES_BELOW_FLOOR",
            Case("rule minimum below the default floor", policyJson(ruleExtra = ""","min_samples":10"""), "/rules/0/min_samples") to
                "MIN_SAMPLES_BELOW_FLOOR",
            Case(
                "throughput has no minimum",
                policyJson(ruleExtra = ""","min_samples":50""", metric = "throughput_rps", operator = "gte"),
                "/rules/0/min_samples",
            ) to "FIELD_NOT_APPLICABLE",
        ).forEach { (case, code) ->
            assertInvalid(case.source.encodeToByteArray(), code, case.pointer, message = case.name)
        }
        assertTrue(
            validatePolicy(
                ByteArrayInputStream(policyJson(defaults = """{"sample_floor":1,"min_samples":1}""").encodeToByteArray()),
            ) is PolicyValidation.Valid,
        )
    }
```

Помощник (рядом с `policyWithRules`; raw-строки с кавычкой в начале допустимы):

```kotlin
    private fun policyJson(
        defaults: String? = null,
        ruleExtra: String = "",
        metric: String = "response_time_p95_ms",
        operator: String = "lte",
    ) = buildString {
        append("""{"schema_version":"policy.v1","policy_id":"p",""")
        if (defaults != null) append(""""defaults":$defaults,""")
        append(""""rules":[{"id":"r","metric":"$metric","operator":"$operator","threshold":1,"scope":{"kind":"overall"}$ruleExtra}]}""")
    }
```

В тот же файл в `contractExamples` добавить два пути:

```kotlin
                "docs/contracts/policy/v1/examples/valid/sample-gate.json" to Expectation(true, true),
                "docs/contracts/policy/v1/examples/invalid/min-samples-below-floor.json" to
                    Expectation(true, false, "MIN_SAMPLES_BELOW_FLOOR", "/rules/0/min_samples"),
```

- [ ] **Step 1.2: Создать примеры и запись в манифесте**

`docs/contracts/policy/v1/examples/valid/sample-gate.json`:

```json
{
  "schema_version": "policy.v1",
  "policy_id": "sample-gate",
  "defaults": {
    "sample_floor": 20,
    "min_samples": 100
  },
  "rules": [
    {
      "id": "checkout-p99",
      "metric": "response_time_p99_ms",
      "operator": "lte",
      "threshold": 800,
      "scope": {
        "kind": "transaction",
        "name": "POST /checkout"
      },
      "min_samples": 1000
    },
    {
      "id": "overall-errors",
      "metric": "error_rate_ratio",
      "operator": "lte",
      "threshold": 0.01,
      "scope": {
        "kind": "overall"
      }
    }
  ]
}
```

`docs/contracts/policy/v1/examples/invalid/min-samples-below-floor.json` (схема допускает, рантайм отвергает: межполевая проверка):

```json
{
  "schema_version": "policy.v1",
  "policy_id": "min-samples-below-floor",
  "rules": [
    {
      "id": "latency",
      "metric": "response_time_p95_ms",
      "operator": "lte",
      "threshold": 100,
      "scope": {
        "kind": "overall"
      },
      "min_samples": 10
    }
  ]
}
```

В `fixtures/slice1/manifest.json` добавить в `policy_examples` записи (`valid`: `schema_valid: true, runtime_valid: true`; `invalid`: `schema_valid: true, runtime_valid: false, expected_diagnostic: "MIN_SAMPLES_BELOW_FLOOR"`) и в `artifacts` обе записи с `sha256` из `Get-FileHash -Algorithm SHA256 <файл>`. В `FixtureManifestTest.kt:58` заменить условие и расширить множество `policyExamples` (`:173-182`):

```kotlin
            if (path.contains("/examples/valid/")) {
```

```kotlin
                "docs/contracts/policy/v1/examples/valid/sample-gate.json",
                "docs/contracts/policy/v1/examples/invalid/min-samples-below-floor.json",
```

- [ ] **Step 1.3: Запустить, убедиться в красном**

Run: `.\gradlew.bat test --tests "io.ltverdict.core.PolicyTest" --tests "io.ltverdict.fixtures.FixtureManifestTest"`
Expected: FAIL (`PolicyDefaultsV1` не определён; примеры отвергаются как `UNKNOWN_FIELD`).

- [ ] **Step 1.4: Минимальная реализация разбора**

`Model.kt` (оба новых параметра имеют значения по умолчанию, существующие вызовы компилируются):

```kotlin
internal data class PolicyV1(
    val schemaVersion: String,
    val policyId: String,
    val rules: List<PolicyRuleV1>,
    val defaults: PolicyDefaultsV1? = null,
)

internal data class PolicyDefaultsV1(
    val sampleFloor: Long? = null,
    val minSamples: Long? = null,
)

internal data class PolicyRuleV1(
    val id: String,
    val metric: PolicyMetric,
    val operator: PolicyOperator,
    val threshold: BigDecimal,
    val scope: PolicyScope,
    val minSamples: Long? = null,
)
```

`Policy.kt`, константы рядом со строками 30-35:

```kotlin
internal const val MIN_SAMPLES_FLOOR = 20L
internal const val MIN_SAMPLES_DEFAULT = 100L
private const val MAX_SAMPLES_BOUND = 1_000_000L
```

`parsePolicy` (около `Policy.kt:427-469`): разрешить ключ `defaults` у корня, разобрать его до правил, передать эффективный пол в разбор правил:

```kotlin
    root.rejectUnknown(setOf("schema_version", "policy_id", "rules", "defaults"), "")
    ...
    val defaults = root["defaults"]?.let { parseDefaults(it, "/defaults") }
    val effectiveFloor = defaults?.sampleFloor ?: MIN_SAMPLES_FLOOR
    defaults?.let { checkDefaultsOrder(it, effectiveFloor) }
```

В цикле правил: `rule.rejectUnknown(setOf("id", "metric", "operator", "threshold", "scope", "min_samples"), pointer)`, затем после проверки метрики:

```kotlin
            val minSamples = rule.longInRangeAt("min_samples", pointer)
            if (minSamples != null) {
                if (metric == PolicyMetric.THROUGHPUT_RPS) {
                    fail("FIELD_NOT_APPLICABLE", "$pointer/min_samples", "min_samples does not apply to throughput_rps")
                }
                if (minSamples < effectiveFloor) {
                    fail("MIN_SAMPLES_BELOW_FLOOR", "$pointer/min_samples", "min_samples is below the effective sample floor")
                }
            }
            ...
            PolicyRuleV1(id, metric, operator, threshold, scope, minSamples)
```

и `return PolicyV1(schemaVersion, policyId, rules, defaults)`. Новые функции:

```kotlin
private fun parseDefaults(
    element: JsonElement,
    pointer: String,
): PolicyDefaultsV1 {
    val value = element.objectAt(pointer)
    value.rejectUnknown(setOf("sample_floor", "min_samples"), pointer)
    return PolicyDefaultsV1(value.longInRangeAt("sample_floor", pointer), value.longInRangeAt("min_samples", pointer))
}

private fun checkDefaultsOrder(
    defaults: PolicyDefaultsV1,
    effectiveFloor: Long,
) {
    val minimum = defaults.minSamples ?: MIN_SAMPLES_DEFAULT
    if (minimum < effectiveFloor) {
        val field = if (defaults.minSamples != null) "min_samples" else "sample_floor"
        fail("MIN_SAMPLES_BELOW_FLOOR", "/defaults/$field", "default minimum is below the sample floor")
    }
}

private fun JsonObject.longInRangeAt(
    name: String,
    pointer: String,
): Long? {
    val value = get(name) ?: return null
    if (value !is JsonPrimitive || value.isString || value === JsonNull || value.content in setOf("true", "false")) {
        fail("INVALID_TYPE", pointer.child(name), "$name must be an integer")
    }
    val number =
        try {
            BigDecimal(value.content)
        } catch (_: NumberFormatException) {
            fail("INVALID_TYPE", pointer.child(name), "$name must be an integer")
        }
    if (number.stripTrailingZeros().scale() > 0) fail("INVALID_TYPE", pointer.child(name), "$name must be an integer")
    if (number < BigDecimal.ONE || number > BigDecimal.valueOf(MAX_SAMPLES_BOUND)) {
        fail("MIN_SAMPLES_OUT_OF_RANGE", pointer.child(name), "$name must be between 1 and $MAX_SAMPLES_BOUND")
    }
    return number.longValueExact()
}
```

`policy.schema.json`: в корне `properties` добавить

```json
    "defaults": {
      "type": "object",
      "additionalProperties": false,
      "properties": {
        "sample_floor": { "type": "integer", "minimum": 1, "maximum": 1000000 },
        "min_samples": { "type": "integer", "minimum": 1, "maximum": 1000000 }
      }
    }
```

в `$defs.rule.properties` — `"min_samples": { "type": "integer", "minimum": 1, "maximum": 1000000 }`, а в четвёртую ветку `oneOf` (метрика `throughput_rps`) — `"min_samples": false` внутри её `properties`.

- [ ] **Step 1.5: Зелёное**

Run: `.\gradlew.bat test --tests "io.ltverdict.core.PolicyTest" --tests "io.ltverdict.fixtures.FixtureManifestTest"` и `npm --prefix ui run test:contracts`
Expected: PASS (Ajv подтверждает `schema_valid` для обоих новых примеров).

- [ ] **Step 1.6: Commit**

```powershell
git add src/main/kotlin/io/ltverdict/core/Model.kt src/main/kotlin/io/ltverdict/core/Policy.kt docs/contracts/policy/v1 fixtures/slice1/manifest.json src/test/kotlin/io/ltverdict/core/PolicyTest.kt src/test/kotlin/io/ltverdict/fixtures/FixtureManifestTest.kt
git commit -m "feat(policy): parse defaults and min_samples in policy.v1"
```

(Манифест и хэши `artifacts` обновляются в этом же коммите; если `FixtureManifestTest` красный из-за хэша — пересчитать `Get-FileHash`, не править тест.)

### Task 2: Gate выборки и метка

- [ ] **Step 2.1: Красные тесты оценки**

В `src/test/kotlin/io/ltverdict/core/PolicyEvaluationTest.kt` добавить тесты и помощники (существующий `policy(...)` в шаге 4.1 получит `defaults` 1/1, поэтому новые тесты строят `PolicyV1` напрямую):

```kotlin
    @Test
    fun `sample gate follows the floor and the minimum at their boundaries`() {
        val policy = PolicyV1("policy.v1", "gate", listOf(rule("p95", PolicyMetric.RESPONSE_TIME_P95_MS, PolicyOperator.LTE, "100")))

        listOf(
            19L to Triple(PolicyVerdict.NO_VERDICT, "INSUFFICIENT", listOf("INSUFFICIENT_SAMPLES")),
            20L to Triple(PolicyVerdict.PASS, "SMALL_SAMPLE", listOf("SMALL_SAMPLE")),
            99L to Triple(PolicyVerdict.PASS, "SMALL_SAMPLE", listOf("SMALL_SAMPLE")),
            100L to Triple(PolicyVerdict.PASS, "FULL", emptyList()),
        ).forEach { (samples, expected) ->
            val result = evaluatePolicy(policy, RunValidity.VALID, metricsWith(samples))
            val check = result.evidence.single { it["type"]?.jsonPrimitive?.content == "policy_check" }

            assertEquals(expected.first, result.verdict, "n=$samples")
            assertEquals(expected.third, result.coverageReasons, "n=$samples")
            assertEquals(expected.second, check.getValue("sample_mode").jsonPrimitive.content, "n=$samples")
            assertEquals(samples.toString(), check.getValue("sample_count").jsonPrimitive.content, "n=$samples")
            assertEquals("20", check.getValue("sample_floor").jsonPrimitive.content)
            assertEquals("100", check.getValue("min_samples").jsonPrimitive.content)
        }
    }

    @Test
    fun `zero observations keep the existing reason and carry no sample mode`() {
        val policy = PolicyV1("policy.v1", "gate", listOf(rule("p95", PolicyMetric.RESPONSE_TIME_P95_MS, PolicyOperator.LTE, "100")))

        val result = evaluatePolicy(policy, RunValidity.VALID, metricsWith(0))
        val check = result.evidence.single { it["type"]?.jsonPrimitive?.content == "policy_check" }

        assertEquals(PolicyVerdict.NO_VERDICT, result.verdict)
        assertEquals(listOf("METRIC_NOT_AVAILABLE"), result.coverageReasons)
        assertEquals(null, check["sample_mode"])
        assertEquals(null, check["sample_count"])
    }

    @Test
    fun `the rule minimum beats the policy default and the default beats the constant`() {
        val rules = listOf(rule("p95", PolicyMetric.RESPONSE_TIME_P95_MS, PolicyOperator.LTE, "100"))
        val byRule = PolicyV1("policy.v1", "gate", listOf(rules.single().copy(minSamples = 30)), PolicyDefaultsV1(10, 500))
        val byDefault = PolicyV1("policy.v1", "gate", rules, PolicyDefaultsV1(sampleFloor = 10, minSamples = 40))

        fun mode(
            policy: PolicyV1,
            samples: Long,
        ) = evaluatePolicy(policy, RunValidity.VALID, metricsWith(samples))
            .evidence
            .single { it["type"]?.jsonPrimitive?.content == "policy_check" }
            .getValue("sample_mode")
            .jsonPrimitive.content

        assertEquals("FULL", mode(byRule, 30))
        assertEquals("SMALL_SAMPLE", mode(byRule, 29))
        assertEquals("INSUFFICIENT", mode(byRule, 9))
        assertEquals("FULL", mode(byDefault, 40))
        assertEquals("SMALL_SAMPLE", mode(byDefault, 39))
    }

    @Test
    fun `throughput is not gated and can still fail on a small positive count`() {
        val policy = PolicyV1("policy.v1", "gate", listOf(rule("rps", PolicyMetric.THROUGHPUT_RPS, PolicyOperator.GTE, "50")))

        val result = evaluatePolicy(policy, RunValidity.VALID, metricsWith(3))
        val check = result.evidence.single { it["type"]?.jsonPrimitive?.content == "policy_check" }

        assertEquals(PolicyVerdict.FAIL, result.verdict)
        assertEquals(emptyList<String>(), result.coverageReasons)
        assertEquals("NOT_GATED", check.getValue("sample_mode").jsonPrimitive.content)
        assertEquals(null, check["min_samples"])
    }

    @Test
    fun `small sample keeps a real failure while an insufficient rule blocks it`() {
        val checkout = rule("checkout-p95", PolicyMetric.RESPONSE_TIME_P95_MS, PolicyOperator.LTE, "1000", "checkout").copy(minSamples = 50)
        val errors = rule("errors", PolicyMetric.ERROR_RATE_RATIO, PolicyOperator.LTE, "0.01")
        val rare = rule("rare-p95", PolicyMetric.RESPONSE_TIME_P95_MS, PolicyOperator.LTE, "1000", "rare")
        val metrics =
            NormalizedMetrics(
                summaryOf(5_000, errorCount = 500),
                listOf(transactionOf("checkout", 30), transactionOf("rare", 12)),
                emptyList(),
                emptyMap(),
            )

        val small = evaluatePolicy(PolicyV1("policy.v1", "gate", listOf(checkout, errors)), RunValidity.VALID, metrics)
        val blocked = evaluatePolicy(PolicyV1("policy.v1", "gate", listOf(checkout, errors, rare)), RunValidity.VALID, metrics)

        assertEquals(PolicyVerdict.FAIL, small.verdict)
        assertEquals(listOf("SMALL_SAMPLE"), small.coverageReasons)
        assertEquals(listOf("errors"), small.findings.map { it.getValue("rule_id").jsonPrimitive.content })
        assertEquals(PolicyVerdict.NO_VERDICT, blocked.verdict)
        assertEquals(listOf("INSUFFICIENT_SAMPLES", "SMALL_SAMPLE"), blocked.coverageReasons)
        assertEquals(listOf("errors"), blocked.findings.map { it.getValue("rule_id").jsonPrimitive.content })
    }
```

Помощники в конце класса:

```kotlin
    private fun metricsWith(samples: Long) =
        NormalizedMetrics(
            MetricSummary(
                samples,
                0,
                if (samples == 0L) null else ExactRatio(0, samples),
                ExactRatio(10, 1),
                LatencySummary(50, 100, 100, 100),
            ),
            emptyList(),
            emptyList(),
            emptyMap(),
        )

    private fun summaryOf(
        samples: Long,
        errorCount: Long = 0,
    ) = MetricSummary(samples, errorCount, ExactRatio(errorCount, samples), ExactRatio(10, 1), LatencySummary(50, 100, 100, 100))

    private fun transactionOf(
        label: String,
        samples: Long,
    ) = TransactionSummary(TransactionIdentity(emptyList(), label, SampleKind.GATLING_REQUEST), summaryOf(samples))
```

Одновременно, чтобы старые юнит-тесты не покраснели от gate, в `PolicyEvaluationTest.kt` заменить помощник на
`private fun policy(vararg rules: PolicyRuleV1) = PolicyV1("policy.v1", "test", rules.toList(), PolicyDefaultsV1(sampleFloor = 1, minSamples = 1))`, а в `WindowPolicyEvaluationTest.kt` в `policy(threshold)` добавить четвёртым аргументом `PolicyDefaultsV1(sampleFloor = 1, minSamples = 1)`.

Порядок `coverageReasons` в последних проверках — блокирующие в порядке правил, затем информационные; именно его закрепляет реализация ниже.

- [ ] **Step 2.2: Запустить, убедиться в красном**

Run: `.\gradlew.bat test --tests "io.ltverdict.core.PolicyEvaluationTest"`
Expected: FAIL (поля `sample_mode` отсутствуют; для 19 наблюдений получается `PASS`).

- [ ] **Step 2.3: Минимальная реализация**

`Policy.kt`: рядом с `METRIC_NOT_AVAILABLE` (`:392`) добавить

```kotlin
private const val REASON_INSUFFICIENT_SAMPLES = "INSUFFICIENT_SAMPLES"
private const val REASON_SMALL_SAMPLE = "SMALL_SAMPLE"

private enum class SampleMode { FULL, SMALL_SAMPLE, INSUFFICIENT, NOT_GATED }

private data class SampleGate(
    val mode: SampleMode?,
    val sampleCount: Long,
    val floor: Long,
    val minSamples: Long,
)

private fun sampleGate(
    policy: PolicyV1,
    rule: PolicyRuleV1,
    sampleCount: Long,
): SampleGate {
    val floor = policy.defaults?.sampleFloor ?: MIN_SAMPLES_FLOOR
    val minimum = rule.minSamples ?: policy.defaults?.minSamples ?: MIN_SAMPLES_DEFAULT
    val mode =
        when {
            sampleCount == 0L -> null
            rule.metric == PolicyMetric.THROUGHPUT_RPS -> SampleMode.NOT_GATED
            sampleCount < floor -> SampleMode.INSUFFICIENT
            sampleCount < minimum -> SampleMode.SMALL_SAMPLE
            else -> SampleMode.FULL
        }
    return SampleGate(mode, sampleCount, floor, minimum)
}
```

В `evaluatePolicy` (`:101-143`): рядом с `val checks` завести `val informational = mutableListOf<String>()`; после успешного `binding` и `val metric = ...`:

```kotlin
        val gate = sampleGate(policy, rule, metric.summary.sampleCount)
        if (gate.mode == SampleMode.INSUFFICIENT) {
            reasons += REASON_INSUFFICIENT_SAMPLES
            checks += policyCheck(rule, metric, null, REASON_INSUFFICIENT_SAMPLES, windowId, includeMetricEvidence, gate)
            return@forEach
        }
```

Остальные вызовы `policyCheck` получают последним аргументом `gate` (для отказа binding — `null`). После вычисления `passed`: `if (gate.mode == SampleMode.SMALL_SAMPLE) informational += REASON_SMALL_SAMPLE`. Итог: `PolicyEvaluation(verdict, (reasons + informational).distinct(), findings, evidence)`; условие `verdict` остаётся по `reasons.isNotEmpty()`. `policyCheck` получает параметр `gate: SampleGate?` и в конце `buildJsonObject`:

```kotlin
        gate?.mode?.let { mode ->
            put("sample_count", gate.sampleCount)
            if (mode != SampleMode.NOT_GATED) {
                put("sample_floor", gate.floor)
                put("min_samples", gate.minSamples)
            }
            put("sample_mode", mode.name)
        }
```

Ветвь `observed == null` при `n = 0` сохраняет `METRIC_NOT_AVAILABLE` (проверка `sampleCount == 0L` в `observed`, `Policy.kt:213`, остаётся).

- [ ] **Step 2.4: Зелёное**

Run: `.\gradlew.bat test --tests "io.ltverdict.core.PolicyEvaluationTest" --tests "io.ltverdict.core.PolicyTest"`
Expected: PASS (старые тесты этих классов проходят на явном `defaults` 1/1). Сквозные тесты на малых фикстурах (CLI, API, e2e) краснеют до задачи 4; задачи 2–4 выполняются подряд, PR ревьюится целиком.

- [ ] **Step 2.5: Commit**

```powershell
git add src/main/kotlin/io/ltverdict/core/Policy.kt src/test/kotlin/io/ltverdict/core/PolicyEvaluationTest.kt
git commit -m "feat(policy): gate business rules by sample size and label small samples"
```

### Task 3: Блок verdict_gates в identity

- [ ] **Step 3.1: Красные тесты**

В `AnalysisResultGoldenTest.kt` оба golden-теста уже сравнивают байты. Сначала обновить файлы: дописать к `fixtures/slice1/identity/analysis-identity.v1.json` и `analysis-identity-resources.v1.json` перед последней `}` строку `,"verdict_gates":{"min_samples_default":"100","min_samples_floor":"20","throughput_exempt":"true"}` (файлы однострочные, без перевода строки в конце), записать в `analysis-identity.sha256` значение `cdcc45e4cac5bddfdbfda2ea934bc321564de4d0a8bbc3fd38b082bb5bba3f2f`, в `analysis-identity-resources.sha256` — `be4bcb97e8b1202cfc02a5a7f11a4973c566552c5670e164eacb2fd5821414a4` (без перевода строки), обновить четыре хэша в `fixtures/slice1/manifest.json`. Добавить тест в `AnalysisResultGoldenTest.kt`:

```kotlin
    @Test
    fun `verdict gates exist only with a policy`() {
        val inputHash = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        val input =
            AcceptedInput(
                runId = "jmeter_jtl_csv-$inputHash",
                sourceType = SourceType.JMETER_CSV,
                sha256 = inputHash,
                sizeBytes = 1,
                originalFilename = "input.jtl",
                path = Path.of("unused"),
            )
        val policy =
            validatePolicy(
                ByteArrayInputStream(Files.readAllBytes(Path.of("fixtures/slice1/identity/policy.canonical.json"))),
            ) as PolicyValidation.Valid

        val withPolicy = Json.parseToJsonElement(analysisIdentity(input, policy, EngineConfig()).decodeToString()).jsonObject
        val withoutPolicy = Json.parseToJsonElement(analysisIdentity(input, null, EngineConfig()).decodeToString()).jsonObject

        assertEquals(
            mapOf("min_samples_default" to "100", "min_samples_floor" to "20", "throughput_exempt" to "true"),
            withPolicy.getValue("verdict_gates").jsonObject.mapValues { it.value.jsonPrimitive.content },
        )
        assertEquals(null, withoutPolicy["verdict_gates"])
    }
```

В `BaselineComparisonTest.kt` (импорты `JsonObject`, `JsonNull`, `buildJsonObject`, `put` там уже есть) добавить тест неизменности ключа:

```kotlin
    @Test
    fun `verdict gates do not change the comparability key`() {
        val withGates = JsonObject(identity() + ("verdict_gates" to buildJsonObject { put("min_samples_floor", "20") }))

        val comparison =
            compareAnalyses(manualBaselineSelection("release", reference('a')), reference('b'), result(), identity(), result(), withGates)

        comparison.getValue("metrics").jsonArray.forEach { assertEquals(JsonNull, it.jsonObject.getValue("reason")) }
    }
```

- [ ] **Step 3.2: Красное**

Run: `.\gradlew.bat test --tests "io.ltverdict.core.AnalysisResultGoldenTest" --tests "io.ltverdict.core.BaselineComparisonTest" --tests "io.ltverdict.fixtures.FixtureManifestTest"`
Expected: FAIL: golden-тесты расходятся с байтами (блока нет), `verdict gates exist only with a policy` падает; тест ключа уже зелёный (характеризационный: фиксирует, что блок не влияет на сопоставимость).

- [ ] **Step 3.3: Реализация**

`AnalysisResult.kt`, сразу после строки 43 (`put("policy_sha256", ...)`):

```kotlin
            if (policy != null) put("verdict_gates", verdictGates())
```

и рядом с `limits(...)`:

```kotlin
private fun verdictGates() =
    buildJsonObject {
        put("min_samples_floor", MIN_SAMPLES_FLOOR.toString())
        put("min_samples_default", MIN_SAMPLES_DEFAULT.toString())
        put("throughput_exempt", "true")
    }
```

- [ ] **Step 3.4: Зелёное**

Run: те же три класса.
Expected: PASS; SHA-256 совпадают с ожидаемыми значениями из «Контрактов».

- [ ] **Step 3.5: Commit**

```powershell
git add src/main/kotlin/io/ltverdict/core/AnalysisResult.kt src/test/kotlin/io/ltverdict/core/AnalysisResultGoldenTest.kt src/test/kotlin/io/ltverdict/core/BaselineComparisonTest.kt fixtures/slice1/identity fixtures/slice1/manifest.json
git commit -m "feat(identity): record verdict gate constants outside the comparability key"
```

### Task 4: CLI, UI-причины, пересчёт существующих тестов и фикстур

- [ ] **Step 4.1: Красный тест CLI и правка помощников**

В `CommandLineTest.kt` добавить (`tempDir`, `run`, `fixture` уже есть; вход xml-5.6.3 содержит три запроса, один с ошибкой: `fixtures/slice1/jmeter/xml-5.6.3/oracle.json`, поэтому доля ошибок равна 1/3):

```kotlin
    @Test
    fun `small samples keep the pass and fail exit codes and label the result`() {
        val input = fixture("jmeter/xml-5.6.3/input.xml")

        fun policy(
            name: String,
            threshold: String,
            defaults: String = "",
        ): Path =
            tempDir.resolve("$name.json").also {
                Files.writeString(
                    it,
                    """{"schema_version":"policy.v1","policy_id":"$name",$defaults"rules":[""" +
                        """{"id":"errors","metric":"error_rate_ratio","operator":"lte","threshold":$threshold,"scope":{"kind":"overall"}}]}""",
                )
            }

        fun analyze(
            name: String,
            policy: Path,
        ): Pair<Int, kotlinx.serialization.json.JsonObject> {
            val result = run("analyze", input.toString(), "--policy", policy.toString(), "--data-dir", tempDir.resolve("data-$name").toString())
            return result.exitCode to Json.parseToJsonElement(result.stdout).jsonObject
        }

        val small = """"defaults":{"sample_floor":1,"min_samples":5},"""
        val (blockedCode, blocked) = analyze("blocked", policy("blocked", "0.5"))
        val (passCode, pass) = analyze("pass", policy("pass", "0.5", small))
        val (failCode, fail) = analyze("fail", policy("fail", "0.1", small))
        val (fullCode, full) = analyze("full", policy("full", "0.5", """"defaults":{"sample_floor":1,"min_samples":3},"""))

        assertEquals(3, blockedCode)
        assertEquals("NO_VERDICT", blocked.getValue("policy_verdict").jsonPrimitive.content)
        assertEquals(0, passCode)
        assertEquals("PASS", pass.getValue("policy_verdict").jsonPrimitive.content)
        assertEquals(listOf("SMALL_SAMPLE"), pass.getValue("analysis_coverage").jsonObject.getValue("reasons").jsonArray.map { it.jsonPrimitive.content })
        assertEquals(2, failCode)
        assertEquals("FAIL", fail.getValue("policy_verdict").jsonPrimitive.content)
        assertEquals(0, fullCode)
        assertEquals("COMPLETE", full.getValue("analysis_coverage").jsonObject.getValue("status").jsonPrimitive.content)
    }
```

(Импорты `jsonArray`, `Path`, `Json` уточнить по заголовку файла.) Правка фикстур, чтобы существующие сквозные тесты проверяли то же, что и раньше, на малых входах (помощники юнит-тестов `PolicyEvaluationTest` и `WindowPolicyEvaluationTest` правятся ещё в шаге 2.1):

- `fixtures/slice1/policies/pass.json` и `fail.json`: после строки `"policy_id"` вставить блок

```json
  "defaults": {
    "sample_floor": 1,
    "min_samples": 1
  },
```

  Новые SHA-256 (LF): `pass.json` — `9395c4d2d731779ff3739b181603f0c3493f2567e383307254b1170451840dea`, `fail.json` — `8bcb65de442222bb9ed1fa934376bf398717385c01e3889661dd2cd3d98f73fe`; обновить `fixtures/slice1/manifest.json:109-119`. `missing-transaction.json` не трогать (отказ binding наступает до gate). Канонический хэш `pass.json` меняется (блок `defaults` входит в canonical JSON): в `LocalApiTest.kt` (companion, `PASS_POLICY_SHA256`) заменить `22c2036369dcd547643909dee86e2b43f6287fcc5fc21d4e5b113c417c4cf307` на `f35d1e8a110bca3d1457e780e5e32751fc91467e9a29d0ced7808822c118aa2b`; `fixtures/slice1/identity/policy.canonical.json` и его golden не затрагиваются (это отдельный файл).

- [ ] **Step 4.2: Записи причин для интерфейса**

В `ui/src/verdictReasons.ts` рядом с причинами привязки правил (после `BUSINESS_OBSERVATIONS_NOT_FOUND`, строка 27) добавить:

```ts
  INSUFFICIENT_SAMPLES: { noVerdict: true, text: 'В области правила слишком мало запросов (меньше минимального пола): порог не сравнивался, вердикта по правилу нет.' },
  SMALL_SAMPLE: { noVerdict: false, text: 'У части правил запросов меньше рекомендуемого минимума: PASS или FAIL рассчитан, но помечен как «малая выборка».' },
```

В `ui/src/types.ts`: в `PolicyRule` добавить `min_samples?: number`; в `Policy` — `defaults?: { sample_floor?: number; min_samples?: number }`; в `PolicyCheckEvidence` — `sample_count?: number`, `sample_floor?: number`, `min_samples?: number`, `sample_mode?: 'FULL' | 'SMALL_SAMPLE' | 'INSUFFICIENT' | 'NOT_GATED'`.

- [ ] **Step 4.3: Полный прогон и перечень красных тестов**

Run: `.\gradlew.bat test` и `Push-Location ui; npx playwright test; Pop-Location` (e2e требует собранного сервера: `npm --prefix ui run e2e`, как в CI).
Записать в описание PR точный перечень упавших тестов. Для каждого выбрать одну из двух правок, не трогая production-код:

1. вход слишком мал для gate, а проверяется не gate: добавить в политику теста `"defaults":{"sample_floor":1,"min_samples":1}`;
2. тест проверяет `analysis_coverage` или `reasons`: обновить ожидание (при `20 <= n < 100` статус `INCOMPLETE` с причиной `SMALL_SAMPLE`).

Известные места для проверки (по `grep`): `AnalysisServiceTest.kt` (`passPolicy()`, строки 686, 693, 764, 800), `DiagnosticIntegrationTest.kt:144`, `LocalSecurityTest.kt:416`, `LocalApiTest.kt` (задания с `pass.json`/`fail.json`), `ConcurrencyAcceptanceTest.kt`, `AdvisoryAcceptanceCorpusTest.kt:1064`, `StatisticalValidationTest.kt`, `UsefulnessValidationTest.kt`, корпус `tools/stats_validation.py:716`, e2e `local-flow.spec.ts`, `overview-live.spec.ts`, `resource-statistics.spec.ts`, `security-a11y.spec.ts`. Оценка до прогона: 15–40 тестов в 10–14 файлах. Одно сохранённое исключение: оставить один тест на реальном малом входе без `defaults`, ожидающий `NO_VERDICT` и `INSUFFICIENT_SAMPLES` (регрессия gate по умолчанию; его роль выполняет `blocked` в шаге 4.1).

- [ ] **Step 4.4: Зелёное и commit**

Run: `.\gradlew.bat test`, `npm --prefix ui run typecheck`, `npm --prefix ui run lint`, `npm --prefix ui run test:contracts`, `npm --prefix ui run e2e`
Expected: все PASS; число тестов не уменьшилось против базового (новых больше).

```powershell
git add src/test ui/src/verdictReasons.ts ui/src/types.ts fixtures/slice1/policies fixtures/slice1/manifest.json tools
git commit -m "test(policy): keep existing small-input tests on an explicit sample floor"
```

(Добавлять в индекс только фактически изменённые файлы по перечню шага 4.3.)

### Task 5: Документация

- [ ] **Step 5.1: Пользовательская документация**

В `docs/user/slice-1-local-analysis.md`:

- после таблицы «Четыре разрешённые метрики» (около строки 392) добавить раздел «Минимум выборки» с таблицей: `n = 0` → `NO_VERDICT`/`METRIC_NOT_AVAILABLE`; `1..floor-1` → `NO_VERDICT`/`INSUFFICIENT_SAMPLES`; `floor..min-1` → `PASS`/`FAIL` с `SMALL_SAMPLE` и `analysis_coverage = INCOMPLETE`; `min` и выше → обычный результат; `throughput_rps` не проверяется gate. Указать умолчания 20 и 100, приоритет «правило, затем `defaults`, затем запасная константа», предупреждение, что при поле ниже 20 p95 может совпасть с максимумом, и что код выхода при малой выборке прежний (0 или 2), а метку читать из JSON (`policy_check.sample_mode`, `analysis_coverage.reasons`);
- в таблицу кодов валидации (строки 470-484) добавить `MIN_SAMPLES_OUT_OF_RANGE`, `MIN_SAMPLES_BELOW_FLOOR`, `FIELD_NOT_APPLICABLE`;
- в раздел «Причины NO_VERDICT и что делать» (строка 230) — `INSUFFICIENT_SAMPLES`; в таблицу кодов выхода (строки 676-680) — оговорку про метку;
- в нормативный prompt (строки 492-506) добавить: «Необязательные поля `defaults.sample_floor`, `defaults.min_samples` и `min_samples` у правила указывай только если пользователь назвал значения; не придумывай их», убрать из запрета слово «новые поля» в части этих трёх.

`CHANGELOG.md`, раздел `[Unreleased]`, подраздел `Changed`:

```markdown
- Бизнес-правила политики проверяют размер выборки: при `0 < n < 20` правило даёт
  `NO_VERDICT` (`INSUFFICIENT_SAMPLES`), при `20 <= n < 100` `PASS`/`FAIL`
  сохраняется с меткой `SMALL_SAMPLE` (статус покрытия `INCOMPLETE`), `throughput_rps`
  не проверяется. Значения настраиваются полями `defaults` и `min_samples`.
  Существующие политики на малых прогонах теперь дают `NO_VERDICT` либо метку;
  анализ с политикой получает новый `analysis_id` (блок `verdict_gates` в identity).
```

- [ ] **Step 5.2: Проверка документации**

Run: `npx --yes markdownlint-cli2@0.23.2 "docs/user/slice-1-local-analysis.md" "CHANGELOG.md"`
Expected: без ошибок.

- [ ] **Step 5.3: Commit**

```powershell
git add docs/user/slice-1-local-analysis.md CHANGELOG.md
git commit -m "docs(policy): document sample floor, minimum and the SMALL_SAMPLE label"
```

**Риски S1:** (1) ложный `FAIL` p95 на малой выборке до ADR 0016; (2) объём правок тестов (оценка выше), риск незаметно ослабить проверку: при правке тестов менять только вход или ожидание покрытия, не вердикт; (3) default-on gate меняет вердикты существующих политик на малых прогонах: пользовательский CHANGELOG и CI-сообщение обязательны; (4) `Case` в `PolicyTest` уже определён как `Case(name, source, pointer)`; в тесте шага 1.1 он используется как пара с кодом.

**Documentation impact:** `docs/user/slice-1-local-analysis.md`, `CHANGELOG.md`, схема и примеры `policy.v1`; правка ADR 0018 (раздел 8 и evidence `sample_floor`) внесена docs-PR решений владельца 2026-10-04 (п. 1 и 6).

## S2. UI: метки и объяснения малой выборки

**Цель:** карточка вердикта F1, вкладка «Обзор» и таблица правил показывают малую выборку словами: «PASS/FAIL — режим малой выборки: 30 сэмплов при минимуме 50» и причину блокировки «12 сэмплов, нужно не меньше 20»; информационная метка не выдаётся за причину отсутствия вердикта.

**Ветка:** `feat/ui-small-sample-labels`. **Размер:** M. **Зависит от:** S1 (поля `sample_*` в evidence, коды в `REASONS`). **Identity:** не меняется. **Контракты:** не меняются (читаются поля S1).

**Что не входит:** группировка по плечам, редактор политики (U4), строки платформенных правил (S8), любые изменения ядра и API.

**Files:**

- Modify: `ui/src/verdictSummary.ts` (`businessLine` около `:107-125`, `causesOf` около `:178-200`, ветки `PASS` и `FAIL` в `summarizeVerdict` около `:262-270`)
- Modify: `ui/src/shell/overview.ts:76-87` (`noVerdictTarget`, `noteTarget`)
- Modify: `ui/src/AnalysisView.vue:107-126` (`policyRows`), `:336-348` (таблица «Policy results», столбец `Sample`)
- Test: `ui/e2e/verdict-summary.spec.ts`, `ui/e2e/overview-adapters.spec.ts`
- Modify: `docs/user/slice-1-local-analysis.md` (разделы о карточке вердикта и причинах), `CHANGELOG.md`

**Interfaces:**

- Consumes (S1): `PolicyCheckEvidence.sample_count`, `sample_floor`, `min_samples`, `sample_mode`; причины `SMALL_SAMPLE` (`noVerdict: false`), `INSUFFICIENT_SAMPLES` (`noVerdict: true`).
- Produces: тексты строк правил и причин (ниже); `summarizeVerdict(result)` сохраняет сигнатуру (расширяется в S4).

### Task 1: Тексты карточки

- [ ] **Step 1.1: Красные тесты**

В `ui/e2e/verdict-summary.spec.ts` (помощники `build`, `overall`, `checkout`, `p95Rule`, `flat` уже определены) добавить внутрь `test.describe('verdict summary', ...)`:

```ts
  test('a small-sample PASS stays a PASS and says how small the sample is', () => {
    const summary = summarizeVerdict(build({
      policy_verdict: 'PASS',
      analysis_coverage: { status: 'INCOMPLETE', reasons: ['SMALL_SAMPLE'] },
      evidence: [overall, checkout, p95Rule('checkout-p95', 'PASS', 1500, { sample_count: 30, sample_floor: 20, min_samples: 50, sample_mode: 'SMALL_SAMPLE' })],
    }))

    expect(summary.headline).toBe('Прогон проходит — нарушений нет, проверок: 1')
    expect(summary.chip).toBe('нарушений нет · малая выборка')
    expect(summary.lead).toContain('«малая выборка»')
    expect(summary.lines.map((line) => flat(`${line.title}: ${line.detail}`))).toEqual([
      'Правило checkout-p95 · p95 отклика · POST /checkout: 1 500 мс при пороге ≤ 2 000 мс · режим малой выборки: 30 сэмплов при минимуме 50',
    ])
    expect(summary.notes.map((note) => note.code)).toEqual(['SMALL_SAMPLE'])
  })

  test('a small-sample FAIL keeps the violation and the label', () => {
    const summary = summarizeVerdict(build({
      policy_verdict: 'FAIL',
      analysis_coverage: { status: 'INCOMPLETE', reasons: ['SMALL_SAMPLE'] },
      evidence: [overall, checkout, p95Rule('checkout-p95', 'FAIL', 2340, { sample_count: 30, sample_floor: 20, min_samples: 50, sample_mode: 'SMALL_SAMPLE' })],
    }))

    expect(summary.chip).toBe('нарушено 1 из 1 · малая выборка')
    expect(flat(summary.lines[0].detail)).toBe('2 340 мс при пороге ≤ 2 000 мс · режим малой выборки: 30 сэмплов при минимуме 50')
  })

  test('INSUFFICIENT_SAMPLES names the rule with its count and keeps the real violation visible', () => {
    const summary = summarizeVerdict(build({
      policy_verdict: 'NO_VERDICT',
      analysis_coverage: { status: 'INCOMPLETE', reasons: ['INSUFFICIENT_SAMPLES', 'SMALL_SAMPLE'] },
      evidence: [
        overall, checkout,
        errorRule,
        p95Rule('rare-p95', 'NO_VERDICT', 0, { reason_code: 'INSUFFICIENT_SAMPLES', observed: undefined, window_id: 'steady-1', sample_count: 12, sample_floor: 20, min_samples: 100, sample_mode: 'INSUFFICIENT' }),
      ],
    }))

    expect(summary.linesTitle).toBe('Найденные нарушения')
    expect(summary.causes.map((cause) => cause.code)).toEqual(['INSUFFICIENT_SAMPLES'])
    expect(summary.causes[0].subjects).toEqual(['rare-p95 (окно steady-1, сэмплов: 12, нужно не меньше 20)'])
    expect(summary.notes.map((note) => note.code)).toEqual(['SMALL_SAMPLE'])
  })
```

В `ui/e2e/overview-adapters.spec.ts` добавить:

```ts
test('small-sample reasons lead to the policy table', () => {
  const blocked = build({
    policy_verdict: 'NO_VERDICT',
    analysis_coverage: { status: 'INCOMPLETE', reasons: ['INSUFFICIENT_SAMPLES', 'SMALL_SAMPLE'] },
    evidence: [overall, checkout, { ...p95Rule('rare', 'NO_VERDICT', 0), reason_code: 'INSUFFICIENT_SAMPLES', sample_count: 12, sample_floor: 20 }],
  })

  const targets = attentionItems(blocked).map((entry) => [entry.key, entry.target?.targetId])

  expect(targets).toEqual([
    ['no_verdict:INSUFFICIENT_SAMPLES|Правила', 'policy-results'],
    ['coverage:SMALL_SAMPLE', 'policy-results'],
  ])
})
```

- [ ] **Step 1.2: Запустить, убедиться в красном**

Run: `Push-Location ui; npx playwright test --config e2e/verdict-ui.config.ts; Pop-Location` и `Push-Location ui; npx playwright test e2e/overview-adapters.spec.ts; Pop-Location`
Expected: FAIL (в строках нет текста малой выборки, цели переходов пустые).

- [ ] **Step 1.3: Реализация**

`ui/src/verdictSummary.ts`. Рядом с `businessLine`:

```ts
function sampleText(check: PolicyCheckEvidence): string {
  if (check.sample_mode !== 'SMALL_SAMPLE' || check.sample_count === undefined || check.min_samples === undefined) return ''
  return ` · режим малой выборки: ${numbers.format(check.sample_count)} сэмплов при минимуме ${numbers.format(check.min_samples)}`
}
```

и в `businessLine` строку `detail` дополнить вызовом `sampleText(check)` в конце (после `tie`). В `causesOf` заменить формирование подписи бизнес-правила:

```ts
  for (const check of business) {
    if (check.status !== 'NO_VERDICT') continue
    const window = check.window_id ? `окно ${check.window_id}` : ''
    const subject = check.reason_code === 'INSUFFICIENT_SAMPLES' && check.sample_count !== undefined && check.sample_floor !== undefined
      ? `${check.rule_id} (${window ? `${window}, ` : ''}сэмплов: ${numbers.format(check.sample_count)}, нужно не меньше ${numbers.format(check.sample_floor)})`
      : window ? `${check.rule_id} (${window})` : check.rule_id
    items.push({ code: check.reason_code ?? null, label: 'Правила', subject })
  }
```

В `summarizeVerdict` после вычисления `unresolved`: `const small = business.filter((check) => check.sample_mode === 'SMALL_SAMPLE').length`; в ветках `verdict === 'FAIL'` и `verdict === 'PASS'` дописать к `lead` предложение «Для N проверок выборка меньше рекомендуемой: результат рассчитан, но помечен как «малая выборка».» (N равно `small`), а к `chip` суффикс «· малая выборка» (через пробел), только при `small > 0`.

`ui/src/shell/overview.ts`: в `noVerdictTarget` добавить `|| code === 'INSUFFICIENT_SAMPLES'` в условие, ведущее к `policy-results`; в `noteTarget` первой строкой `if (code === 'SMALL_SAMPLE') return { tab: 'tables', targetId: 'policy-results' }`.

`ui/src/AnalysisView.vue`: в `policyRows` добавить поле

```ts
      sample: stringAt(check, 'sample_mode')
        ? `${numberAt(check, 'sample_count') ?? '—'} / ${(stringAt(check, 'sample_mode') === 'INSUFFICIENT' ? numberAt(check, 'sample_floor') : numberAt(check, 'min_samples')) ?? '—'} · ${stringAt(check, 'sample_mode')}`
        : '—',
```

в `<thead>` добавить `<th>Sample</th>` перед `<th>Status</th>`, в строке `<td>{{ row.sample }}</td>` перед ячейкой статуса.

- [ ] **Step 1.4: Зелёное**

Run: те же два прогона, затем `npm --prefix ui run typecheck` и `npm --prefix ui run lint`.
Expected: PASS. Проверить `grep -rn "Scope</th><th>Status" ui/e2e` и e2e, считающие столбцы таблицы правил; обновить найденные ожидания.

- [ ] **Step 1.5: Commit**

```powershell
git add ui/src/verdictSummary.ts ui/src/shell/overview.ts ui/src/AnalysisView.vue ui/e2e/verdict-summary.spec.ts ui/e2e/overview-adapters.spec.ts
git commit -m "feat(ui): explain small-sample results in the verdict card"
```

### Task 2: Документация

- [ ] **Step 2.1:** В `docs/user/slice-1-local-analysis.md` в описание карточки вердикта (около строки 224) добавить строки про малую выборку: что показывает карточка при `PASS`/`FAIL` с меткой, что `INSUFFICIENT_SAMPLES` блокирует вердикт по правилу, что метка не меняет код выхода. В `CHANGELOG.md` (`Added`): «Карточка вердикта и таблица правил показывают режим малой выборки».

- [ ] **Step 2.2:** Run: `npx --yes markdownlint-cli2@0.23.2 "docs/user/slice-1-local-analysis.md" "CHANGELOG.md"`. Commit: `docs(ui): describe the small-sample labels`.

**Риски S2:** подписи таблицы «Policy results» английские (остальной экран тоже): новый столбец `Sample` оставлен английским ради единообразия; карточка и обзор русские. Различие в Intl-разделителях тысяч: тесты используют `flat`.

**Documentation impact:** `docs/user/slice-1-local-analysis.md`, `CHANGELOG.md`.

## S3. Ступень capacity не подтверждается при малой выборке

**Цель:** ступень capacity получает причину `CAPACITY_INSUFFICIENT_SAMPLES` и вердикт `INDETERMINATE`, если число запросов в окне ступени (область `overall`, независимо от применимости бизнес-правил) ниже действующего минимума либо у правила окна `sample_mode` равен `SMALL_SAMPLE` или `INSUFFICIENT`.

**Ветка:** `feat/capacity-stage-sample-gate`. **Размер:** M. **Зависит от:** S1. **Identity:** меняется для анализов с capacity (новые ключи, ниже).

**Что не входит:** правка текста ADR 0009 (отдельный docs-PR владельца), UI-текст ступени (запись в `REASONS` входит), изменение границы ёмкости или схемы `capacity.v1`.

**Контракты и версии:**

- `window_policy_summary` получает аддитивные поля `sample_count` (число запросов `overall` в окне; всегда) и `min_samples` (только если политика задаёт `defaults.min_samples`). Запасной минимум ступени — `MIN_SAMPLES_DEFAULT` (100).
- Новая причина ступени и сводки: `CAPACITY_INSUFFICIENT_SAMPLES` (блокирует, `noVerdict: true`).
- Identity: блок `verdict_gates` появляется и при `capacity != null` без политики. Ключи: при политике (S1) `min_samples_floor`, `throughput_exempt`; при политике или capacity `min_samples_default`; при capacity `capacity_stage_sample_gate: "true"`. Ключ сопоставимости не меняется. Политика без capacity: блок прежний (golden S1 не меняется).
- Ступень без `sample_count` в сводке (старые сохранённые данные, юнит-тесты) не отбрасывается: условие по счёту применяется только при наличии поля.

**Files:**

- Modify: `src/main/kotlin/io/ltverdict/core/WindowPolicy.kt:39,65-80` (поля сводки окна)
- Modify: `src/main/kotlin/io/ltverdict/core/CapacityAnalysis.kt:32-34,36-51,85-148` (условие ступени)
- Modify: `src/main/kotlin/io/ltverdict/core/AnalysisResult.kt` (блок `verdict_gates`, S1)
- Modify: `ui/src/verdictReasons.ts` (запись `CAPACITY_INSUFFICIENT_SAMPLES`)
- Test: `src/test/kotlin/io/ltverdict/core/WindowPolicyEvaluationTest.kt`, `CapacityAnalysisTest.kt`, `AnalysisServiceTest.kt`
- Modify: `docs/user/slice-1-local-analysis.md`, `CHANGELOG.md`

**Interfaces:**

- Consumes (S1): `MIN_SAMPLES_DEFAULT`, `PolicyV1.defaults`, evidence `policy_check.sample_mode`.
- Produces: `window_policy_summary.sample_count: Long`, `window_policy_summary.min_samples: Long?`; `evaluateCapacity` сигнатуры не меняет (читает `windowPolicy.evidence`).

### Task 1: Счёт окна в сводке

- [ ] **Step 1.1: Красный тест** (в `WindowPolicyEvaluationTest.kt`; `policy`, `metrics`, `resource`, `WINDOWS` — существующие помощники):

```kotlin
    @Test
    fun `window summary carries the window sample count and the default minimum only when set`() {
        val withDefault =
            evaluateSharedWindowPolicy(
                policy("100").copy(defaults = PolicyDefaultsV1(sampleFloor = 1, minSamples = 40)),
                RunValidity.VALID,
                metrics(90),
                mapOf("first" to metrics(90, 25), "second" to metrics(90, 7)),
                resource(PolicyVerdict.PASS, PolicyVerdict.PASS),
                WINDOWS,
            )
        val noPolicy =
            evaluateSharedWindowPolicy(
                null,
                RunValidity.VALID,
                metrics(90),
                mapOf("first" to metrics(90, 25), "second" to metrics(90, 7)),
                resource(PolicyVerdict.PASS, PolicyVerdict.PASS),
                WINDOWS,
            )

        fun summaries(evaluation: PolicyEvaluation) =
            evaluation.evidence.filter { it["type"]?.jsonPrimitive?.content == "window_policy_summary" }

        assertEquals(listOf("25", "7"), summaries(withDefault).map { it.getValue("sample_count").jsonPrimitive.content })
        assertEquals(listOf("40", "40"), summaries(withDefault).map { it.getValue("min_samples").jsonPrimitive.content })
        assertEquals(listOf("25", "7"), summaries(noPolicy).map { it.getValue("sample_count").jsonPrimitive.content })
        assertEquals(listOf(null, null), summaries(noPolicy).map { it["min_samples"] })
    }
```

- [ ] **Step 1.2:** Run: `.\gradlew.bat test --tests "io.ltverdict.core.WindowPolicyEvaluationTest"`. Expected: FAIL (`sample_count` отсутствует).

- [ ] **Step 1.3: Реализация.** В `WindowPolicy.kt` цикл окон передаёт в `windowPolicySummary(window, business.verdict, resourceVerdict, verdict, metrics.overall.sampleCount, policy?.defaults?.minSamples)`, функция получает два параметра и пишет

```kotlin
        put("sample_count", sampleCount)
        minSamples?.let { put("min_samples", it) }
```

- [ ] **Step 1.4:** Run тот же тест: PASS. Commit: `feat(policy): carry window sample counts in the window summary`.

### Task 2: Условие ступени

- [ ] **Step 2.1: Красные тесты** (в `CapacityAnalysisTest.kt`; новый помощник рядом с `summary`):

```kotlin
    @Test
    fun `a stage with fewer samples than the minimum is not confirmed`() {
        val resources = resources()
        val plan = plan(CapacityLoadAxis.RPS, required = BigDecimal("300"), stages = listOf(stage("300", 300, 0)))
        val load = load("300" to List(30) { 300 })

        fun stageOf(vararg evidence: JsonObject): Pair<String, List<String>> {
            val policy = PolicyEvaluation(PolicyVerdict.PASS, emptyList(), emptyList(), evidence.toList() + guard("300", "PASS"))
            val stage = evaluateCapacity(plan, resources, load, RunValidity.VALID, policy).capacityJson["stages"]!!.jsonArray[0].jsonObject
            return stage.string("verdict") to stage.getValue("reasons").jsonArray.map { it.jsonPrimitive.content }
        }

        assertEquals("INDETERMINATE" to listOf("CAPACITY_INSUFFICIENT_SAMPLES"), stageOf(summaryWithSamples("300", 40, null)))
        assertEquals("PASS" to emptyList<String>(), stageOf(summaryWithSamples("300", 40, 40)))
        assertEquals("INDETERMINATE" to listOf("CAPACITY_INSUFFICIENT_SAMPLES"), stageOf(summaryWithSamples("300", 120, 500)))
        assertEquals("PASS" to emptyList<String>(), stageOf(summaryWithSamples("300", 120, null)))
        assertEquals("PASS" to emptyList<String>(), stageOf(summary("300", "PASS")))
        assertEquals(
            "INDETERMINATE" to listOf("CAPACITY_INSUFFICIENT_SAMPLES"),
            stageOf(summaryWithSamples("300", 5_000, 100), smallRule("300")),
        )
    }

    @Test
    fun `a stage without business rules is still gated by the window sample count`() {
        val resources = resources()
        val plan = plan(CapacityLoadAxis.RPS, required = BigDecimal("300"), stages = listOf(stage("300", 300, 0)))
        val load = load("300" to List(30) { 300 })

        fun stageVerdict(samples: Long): String {
            val summary =
                JsonObject(
                    summary("300", "PASS") +
                        buildJsonObject {
                            put("business_verdict", "NO_POLICY")
                            put("resource_verdict", "PASS")
                            put("sample_count", samples)
                        },
                )
            val slaCheck =
                buildJsonObject {
                    put("id", "sla-300")
                    put("type", "resource_policy_check")
                    put("window_id", "300")
                    put("rule_id", "cpu")
                    put("effect", "sla")
                    put("status", "PASS")
                }
            val policy = PolicyEvaluation(PolicyVerdict.PASS, emptyList(), emptyList(), listOf(summary, slaCheck, guard("300", "PASS")))
            return evaluateCapacity(plan, resources, load, RunValidity.VALID, policy).capacityJson["stages"]!!.jsonArray[0].jsonObject.string("verdict")
        }

        assertEquals("INDETERMINATE", stageVerdict(99))
        assertEquals("PASS", stageVerdict(100))
    }

    private fun summaryWithSamples(
        window: String,
        sampleCount: Long,
        minSamples: Long?,
    ) = JsonObject(
        summary(window, "PASS") +
            buildJsonObject {
                put("sample_count", sampleCount)
                minSamples?.let { put("min_samples", it) }
            },
    )

    private fun smallRule(window: String) =
        buildJsonObject {
            put("id", "check-$window")
            put("type", "policy_check")
            put("window_id", window)
            put("rule_id", "p95")
            put("status", "PASS")
            put("sample_mode", "SMALL_SAMPLE")
        }
```

В `AnalysisServiceTest.kt` (помощники `capacityCsv`, `capacityResourceJson`, `capacityPlanJson`, `resources`, `withService`, `accept` существуют):

```kotlin
    @Test
    fun `a capacity analysis records the stage sample gate even without a policy`() =
        withService { store, service ->
            val input = accept(store, capacityCsv().encodeToByteArray(), "capacity-gates.jtl")
            val resource = resources(capacityResourceJson(input.sha256).encodeToByteArray())
            val plan =
                assertInstanceOf(
                    CapacityPlanValidation.Valid::class.java,
                    validateCapacityPlan(ByteArrayInputStream(capacityPlanJson(input.sha256, resource.semanticSha256).encodeToByteArray())),
                )

            val outcome = service.analyze(AnalysisRequest(input, null, resources = resource, capacity = plan))
            val gates =
                Json
                    .parseToJsonElement(Files.readString(outcome.analysisDirectory.resolve("identity.json")))
                    .jsonObject
                    .getValue("verdict_gates")
                    .jsonObject

            assertEquals(
                mapOf("capacity_stage_sample_gate" to "true", "min_samples_default" to "100"),
                gates.mapValues { it.value.jsonPrimitive.content },
            )
        }
```

(`summary(...)` в `CapacityAnalysisTest` возвращает `JsonObject`, поэтому `summary(window, "PASS") + buildJsonObject {...}` — слияние карт; импорты `JsonObject`, `buildJsonObject`, `put` в файле уже есть, для `Json` в `AnalysisServiceTest` проверить импорт.)

- [ ] **Step 2.2:** Run: `.\gradlew.bat test --tests "io.ltverdict.core.CapacityAnalysisTest" --tests "io.ltverdict.core.AnalysisServiceTest"`. Expected: FAIL (причины нет; блока `verdict_gates` у анализа без политики нет).

- [ ] **Step 2.3: Реализация.**

`CapacityAnalysis.kt`: в `evaluateCapacity` вычислить `val businessChecks = windowPolicy.evidence.filter { it.string("type") == "policy_check" }` и передать в `evaluateStage` новым параметром после `checks`. В `evaluateStage` перед `val policyStatus = ...` (`:139`):

```kotlin
    val sampleCount = (summary?.get("sample_count") as? JsonPrimitive)?.longOrNull
    val minSamples = (summary?.get("min_samples") as? JsonPrimitive)?.longOrNull ?: MIN_SAMPLES_DEFAULT
    val smallRule =
        businessChecks.any {
            it["window_id"]?.jsonPrimitive?.content == stage.evaluationWindowId &&
                it["sample_mode"]?.jsonPrimitive?.content in setOf("SMALL_SAMPLE", "INSUFFICIENT")
        }
    if ((sampleCount != null && sampleCount < minSamples) || smallRule) reasons += "CAPACITY_INSUFFICIENT_SAMPLES"
```

(импорт `kotlinx.serialization.json.longOrNull`). Вердикт ступени: `reasons.isNotEmpty() -> "INDETERMINATE"` уже есть (`:143-148`).

`AnalysisResult.kt`: заменить условие и функцию S1:

```kotlin
            if (policy != null || capacity != null) put("verdict_gates", verdictGates(policy != null, capacity != null))
```

```kotlin
private fun verdictGates(
    hasPolicy: Boolean,
    hasCapacity: Boolean,
) = buildJsonObject {
    if (hasPolicy) {
        put("min_samples_floor", MIN_SAMPLES_FLOOR.toString())
        put("throughput_exempt", "true")
    }
    put("min_samples_default", MIN_SAMPLES_DEFAULT.toString())
    if (hasCapacity) put("capacity_stage_sample_gate", "true")
}
```

`ui/src/verdictReasons.ts` (блок «Оценка ёмкости»): `CAPACITY_INSUFFICIENT_SAMPLES: { noVerdict: true, text: 'В окне ступени мало запросов (меньше минимума) либо у правила окна малая выборка: ступень не подтверждена.' },`.

- [ ] **Step 2.4:** Run: `.\gradlew.bat test` и `npx playwright test --config e2e/verdict-ui.config.ts` (из `ui`). Expected: PASS. Если существующие capacity-тесты `AnalysisServiceTest` (строки 452-570, `capacityCsv()`) краснеют из-за малого счёта окна, добавить в их политику `defaults` с `min_samples`, равным фактическому счёту, и записать это в описание PR; production не менять.

- [ ] **Step 2.5:** Commit: `feat(capacity): do not confirm a stage on a small sample`.

### Task 3: Документация

- [ ] **Step 3.1:** `docs/user/slice-1-local-analysis.md` (раздел о capacity, около строки 250): условие неподтверждённой ступени и причина `CAPACITY_INSUFFICIENT_SAMPLES`; `CHANGELOG.md` (`Changed`). Run markdownlint для двух файлов. Commit: `docs(capacity): describe the stage sample condition`.

**Риски S3:** (1) счёт окна считается по `overall` всех запросов, включая служебные, как и остальной `window_policy_summary`; (2) для ступеней обычной длины (не меньше 20 минут) условие редкое, но при очень низком RPS (<0,1 запроса в секунду) ступень перестанет подтверждаться: подсказка в документации о `defaults.min_samples`; (3) правка текста ADR 0009 выполняется владельцем отдельно.

**Documentation impact:** `docs/user/slice-1-local-analysis.md`, `CHANGELOG.md`; ADR 0009 (вне этого PR).

## S4. Видимость выбранной политики

**Цель:** рядом с вердиктом показан хэш политики, по которой он получен; тестом закреплено, что две разные политики на одной нагрузке дают два анализа с разными `analysis_id` и одним ключом сопоставимости.

**Ветка:** `feat/ui-policy-hash`. **Размер:** S. **Зависит от:** S1 (стабильность identity). **Identity и контракты:** не меняются; новых полей в API нет (хэш уже отдаётся: `LocalApi.kt:903`, `ui/src/types.ts:21-26`).

**Что не входит:** `policy_id` рядом с вердиктом (в результате и списке анализов его нет; поле принято владельцем 2026-10-04, п. 5, и оформляется при реализации S1), библиотека и версионирование политик, привязка политики к протоколу.

**Files:**

- Modify: `ui/src/verdictSummary.ts` (`summarizeVerdict`, необязательный второй аргумент)
- Modify: `ui/src/App.vue:110` (передача хэша выбранного анализа)
- Test: `ui/e2e/verdict-summary.spec.ts`, `src/test/kotlin/io/ltverdict/core/AnalysisServiceTest.kt`
- Modify: `docs/user/slice-1-local-analysis.md`, `CHANGELOG.md`

**Interfaces:** `summarizeVerdict(result: AnalysisResult, context: { policySha256?: string } = {}): VerdictSummary`; при переданном хэше в конец `facts` добавляется `{ label: 'Политика (хэш)', value }`, где `value` равен первым 12 символам хэша либо `не задана` для значения `NO_POLICY`.

### Task 1: Тест двух политик (ядро)

- [ ] **Step 1.1: Тест** (характеризационный, проходит сразу: фиксирует контракт; помощники `withService`, `accept`, `policy`, `result` существуют):

```kotlin
    @Test
    fun `two policies for one load give separate analyses that stay comparable`() =
        withService { store, service ->
            val input = accept(store, OUT_OF_ORDER_CSV.encodeToByteArray(), "two-policies.jtl")
            fun policyFor(threshold: String) =
                policy(
                    """{"schema_version":"policy.v1","policy_id":"p-$threshold","defaults":{"sample_floor":1,"min_samples":1},""" +
                        """"rules":[{"id":"p95","metric":"response_time_p95_ms","operator":"lte","threshold":$threshold,"scope":{"kind":"overall"}}]}""",
                )

            val lenient = service.analyze(AnalysisRequest(input, policyFor("1000000")))
            val strict = service.analyze(AnalysisRequest(input, policyFor("0")))
            fun identity(outcome: AnalysisOutcome) =
                Json.parseToJsonElement(Files.readString(outcome.analysisDirectory.resolve("identity.json"))).jsonObject

            assertNotEquals(lenient.analysisId, strict.analysisId)
            assertNotEquals(identity(lenient).getValue("policy_sha256"), identity(strict).getValue("policy_sha256"))
            listOf("source_type", "engine", "parsers", "modules", "input_versions", "outputs", "histogram", "normalization", "limits")
                .forEach { assertEquals(identity(lenient).getValue(it), identity(strict).getValue(it), it) }
            assertEquals("PASS", result(lenient, "policy_verdict"))
            assertEquals("FAIL", result(strict, "policy_verdict"))
        }
```

- [ ] **Step 1.2:** Run: `.\gradlew.bat test --tests "io.ltverdict.core.AnalysisServiceTest"`. Expected: PASS (S1 уже дала нужную identity; если красный, остановиться: сломан контракт ключа сопоставимости). Commit: `test(policy): pin separate analyses for separate policies`.

### Task 2: Хэш в карточке

- [ ] **Step 2.1: Красный тест** (`verdict-summary.spec.ts`):

```ts
  test('the policy hash is a fact only when the saved list provides it', () => {
    const result = build({ policy_verdict: 'PASS' })

    expect(summarizeVerdict(result).facts.map((fact) => fact.label)).not.toContain('Политика (хэш)')
    expect(summarizeVerdict(result, { policySha256: 'ab12'.repeat(16) }).facts.at(-1)).toEqual({ label: 'Политика (хэш)', value: 'ab12ab12ab12' })
    expect(summarizeVerdict(result, { policySha256: 'NO_POLICY' }).facts.at(-1)).toEqual({ label: 'Политика (хэш)', value: 'не задана' })
  })
```

- [ ] **Step 2.2:** Run: `Push-Location ui; npx playwright test --config e2e/verdict-ui.config.ts; Pop-Location`. Expected: FAIL (аргумента нет).

- [ ] **Step 2.3: Реализация.** `summarizeVerdict(result, context = {})`; перед `return` добавить `const policyFact = context.policySha256 ? [{ label: 'Политика (хэш)', value: context.policySha256 === 'NO_POLICY' ? 'не задана' : context.policySha256.slice(0, 12) }] : []` и дописать `...policyFact` в конец `facts`. В `App.vue:110`:

```ts
const verdictSummary = computed(() => (result.value
  ? summarizeVerdict(result.value, { policySha256: analyses.value.find((item) => item.analysis_id === selectedAnalysisId.value)?.policy_sha256 })
  : null))
```

- [ ] **Step 2.4:** Run: тот же прогон, `npm --prefix ui run typecheck`, `npm --prefix ui run lint`, затем `npm --prefix ui run e2e -- e2e/verdict-first.spec.ts e2e/local-flow.spec.ts` (существующие проверки фактов должны остаться зелёными: дополнительный факт не нарушает точечные `getByText`). Expected: PASS.

- [ ] **Step 2.5:** Документация: `docs/user/slice-1-local-analysis.md` — в описании фактов карточки строка «Политика (хэш)», в разделе про выбор политики фраза, что несколько политик (по протоколам и системам) допустимы и различаются хэшем; `CHANGELOG.md` (`Added`). Run markdownlint; commit: `feat(ui): show the policy hash next to the verdict`.

**Риски S4:** факт появляется только если список анализов уже загружен для выбранного анализа; после свежего задания список подгружается позже, до загрузки факта нет (допустимо, не ошибка). Хэш не заменяет `policy_id`: пользователь по-прежнему не видит имя политики.

**Documentation impact:** `docs/user/slice-1-local-analysis.md`, `CHANGELOG.md`.

## S5. window_ids: стадии и окна правил

**Цель:** бизнес-правило может быть привязано к именованным окнам снимка (`window_ids`); вне этих окон оно не проверяется; окно, в котором нет применимых бизнес-правил, получает бизнес-вердикт `NO_POLICY`, а не пустой `PASS`; неизвестный id окна даёт `NO_VERDICT` с `RULE_WINDOW_NOT_FOUND`.

**Ветка:** `feat/policy-window-ids`. **Размер:** M. **Зависит от:** S1 (`PolicyRuleV1`, схема). **Identity:** не меняется: политики без `window_ids` оцениваются как раньше (все правила применимы во всех окнах); новый файл политики имеет новый `policy_sha256`.

**Что не входит:** платформенные правила (S6 использует тот же разбор `window_ids`), автоопределение стадий, уровневые `default_window_ids`, исправление ложного `PASS` на коротком окне у правил снимка (REPORT-ONLY, ADR 0018, «Не входит»).

**Контракты и версии:**

- `policy.v1` на месте: необязательный `window_ids` у бизнес-правила: непустой массив уникальных непустых строк до 128 байт UTF-8; ошибка формы — `WINDOW_IDS_INVALID` (указатель `/rules/<i>/window_ids`). Существование id проверяется при оценке.
- Новая блокирующая причина `RULE_WINDOW_NOT_FOUND`. Новый тип evidence `rule_window_check`: `{id, type, rule_id, window_id, status: "NO_VERDICT", reason_code: "RULE_WINDOW_NOT_FOUND"}` (по одному на пару «правило, неизвестный id»). Без снимка правило с `window_ids` даёт `policy_check` со статусом `NO_VERDICT` и той же причиной (названных окон нет).
- Свёртка не меняется: `jointVerdict` и `overallVerdict` (`WindowPolicy.kt:46-63`) принимают `NO_POLICY` бизнес-части как сейчас.

**Files:**

- Modify: `src/main/kotlin/io/ltverdict/core/Model.kt` (поле `windowIds` в `PolicyRuleV1`)
- Modify: `src/main/kotlin/io/ltverdict/core/Policy.kt:101-143` (применимость, `NO_POLICY`, windowless-ветка), `:427-469` (разбор)
- Modify: `src/main/kotlin/io/ltverdict/core/WindowPolicy.kt:25-43` (условие пустого окна, неизвестные id)
- Modify: `docs/contracts/policy/v1/policy.schema.json`; Create: `docs/contracts/policy/v1/examples/valid/window-ids.json`, `invalid/window-ids-empty.json`; Modify: `fixtures/slice1/manifest.json`
- Modify: `ui/src/verdictReasons.ts` (`RULE_WINDOW_NOT_FOUND`)
- Test: `PolicyTest.kt`, `PolicyEvaluationTest.kt`, `WindowPolicyEvaluationTest.kt`, `FixtureManifestTest.kt`
- Modify: `docs/user/slice-1-local-analysis.md`, `CHANGELOG.md`

**Interfaces:**

- Produces: `PolicyRuleV1.windowIds: List<String>? = null`; `private fun JsonObject.windowIdsAt(pointer: String): List<String>?` в `Policy.kt` (S6 использует её в том же файле для платформенных правил); `internal fun ruleWindowCheck(ruleId: String, windowId: String): JsonObject` в `WindowPolicy.kt`.

### Task 1: Разбор и схема

- [ ] **Step 1.1: Красные тесты** (`PolicyTest.kt`; помощник `policyJson` с параметром `ruleExtra` добавлен в S1):

```kotlin
    @Test
    fun `window ids parse and malformed lists fail at the field`() {
        val valid =
            validatePolicy(
                ByteArrayInputStream(policyJson(ruleExtra = ""","window_ids":["steady-1","steady-2"]""").encodeToByteArray()),
            ) as PolicyValidation.Valid
        assertEquals(listOf("steady-1", "steady-2"), valid.policy.rules.single().windowIds)

        listOf(
            ",\"window_ids\":[]" to "empty",
            ",\"window_ids\":[\"a\",\"a\"]" to "duplicate",
            ",\"window_ids\":[1]" to "not a string",
            ",\"window_ids\":\"a\"" to "not an array",
            ",\"window_ids\":[\"${"é".repeat(65)}\"]" to "too long",
        ).forEach { (extra, name) ->
            assertInvalid(policyJson(ruleExtra = extra).encodeToByteArray(), "WINDOW_IDS_INVALID", "/rules/0/window_ids", message = name)
        }
    }
```

Добавить в `contractExamples` (`PolicyTest`) и в `policyExamples` (`FixtureManifestTest`) пути `.../examples/valid/window-ids.json` (`Expectation(true, true)`) и `.../examples/invalid/window-ids-empty.json` (`Expectation(false, false, "WINDOW_IDS_INVALID", "/rules/0/window_ids")`: схема отвергает `minItems: 1`).

- [ ] **Step 1.2:** Run: `.\gradlew.bat test --tests "io.ltverdict.core.PolicyTest"`. Expected: FAIL (`UNKNOWN_FIELD` у `window_ids`).

- [ ] **Step 1.3: Реализация.** `Model.kt`: в `PolicyRuleV1` добавить последним `val windowIds: List<String>? = null`. `Policy.kt`: допустить ключ `window_ids` в `rejectUnknown` правила, после `minSamples` добавить `val windowIds = rule.windowIdsAt(pointer)`, передать в конструктор. Функция:

```kotlin
private fun JsonObject.windowIdsAt(pointer: String): List<String>? {
    val field = pointer.child("window_ids")
    val value = get("window_ids") ?: return null
    val array = value as? JsonArray ?: fail("WINDOW_IDS_INVALID", field, "window_ids must be an array")
    if (array.isEmpty()) fail("WINDOW_IDS_INVALID", field, "window_ids must not be empty")
    val ids =
        array.map { item ->
            (item as? JsonPrimitive)?.takeIf { it.isString }?.content ?: fail("WINDOW_IDS_INVALID", field, "window id must be a string")
        }
    if (ids.any { it.isEmpty() || it.encodeToByteArray().size > MAX_IDENTIFIER_BYTES } || ids.toSet().size != ids.size) {
        fail("WINDOW_IDS_INVALID", field, "window ids must be unique, non-empty and at most 128 UTF-8 bytes")
    }
    return ids
}
```

Разбор платформенных правил (S6) остаётся в `Policy.kt`, поэтому приватная `fail` доступна `windowIdsAt`.

Схема: в `$defs` добавить `window_ids`: `{"type":"array","minItems":1,"uniqueItems":true,"items":{"type":"string","minLength":1,"maxLength":128}}`, в `rule.properties` — `"window_ids": {"$ref": "#/$defs/window_ids"}`. Примеры:

`docs/contracts/policy/v1/examples/valid/window-ids.json`:

```json
{
  "schema_version": "policy.v1",
  "policy_id": "window-ids",
  "rules": [
    {
      "id": "checkout-p95-steady",
      "metric": "response_time_p95_ms",
      "operator": "lte",
      "threshold": 800,
      "scope": {
        "kind": "transaction",
        "name": "POST /checkout"
      },
      "window_ids": ["steady-1", "steady-2"]
    }
  ]
}
```

`invalid/window-ids-empty.json` — то же правило с `"window_ids": []`, `policy_id` `window-ids-empty`. Записи в манифест (`policy_examples`: для invalid `schema_valid: false, runtime_valid: false, expected_diagnostic: "WINDOW_IDS_INVALID"`; `artifacts` с `sha256`).

- [ ] **Step 1.4:** Run: `.\gradlew.bat test --tests "io.ltverdict.core.PolicyTest" --tests "io.ltverdict.fixtures.FixtureManifestTest"` и `npm --prefix ui run test:contracts`. Expected: PASS.

- [ ] **Step 1.5:** Commit: `feat(policy): parse window_ids on business rules`.

### Task 2: Применимость и свёртка

- [ ] **Step 2.1: Красные тесты.** `WindowPolicyEvaluationTest.kt` (помощники `policy`, `metrics`, `resource`, `WINDOWS` существуют):

```kotlin
    @Test
    fun `a rule bound to named windows is skipped elsewhere and a window without rules has no business policy`() {
        val bound = policy("100").let { base -> base.copy(rules = base.rules.map { it.copy(windowIds = listOf("second")) }) }

        val evaluation =
            evaluateSharedWindowPolicy(
                bound,
                RunValidity.VALID,
                metrics(95),
                mapOf("first" to metrics(500, 0), "second" to metrics(90)),
                resource(PolicyVerdict.PASS, PolicyVerdict.PASS),
                WINDOWS,
            )
        val summaries = evaluation.evidence.filter { it["type"]?.jsonPrimitive?.content == "window_policy_summary" }
        val checks = evaluation.evidence.filter { it["type"]?.jsonPrimitive?.content == "policy_check" }

        assertEquals(PolicyVerdict.PASS, evaluation.verdict)
        assertEquals(emptyList<String>(), evaluation.coverageReasons)
        assertEquals(listOf("NO_POLICY", "PASS"), summaries.map { it.getValue("business_verdict").jsonPrimitive.content })
        assertEquals(listOf("second"), checks.map { it.getValue("window_id").jsonPrimitive.content })
    }

    @Test
    fun `a transaction rule counts its own samples inside its named window`() {
        val rule =
            PolicyRuleV1(
                "rare-p95",
                PolicyMetric.RESPONSE_TIME_P95_MS,
                PolicyOperator.LTE,
                BigDecimal("100"),
                PolicyScope.Transaction("rare"),
                windowIds = listOf("second"),
            )
        val bound = PolicyV1("policy.v1", "p", listOf(rule), PolicyDefaultsV1(sampleFloor = 20, minSamples = 100))

        fun modeFor(samples: Long): String {
            val evaluation =
                evaluateSharedWindowPolicy(
                    bound,
                    RunValidity.VALID,
                    metrics(95),
                    mapOf("first" to withRare(500), "second" to withRare(samples)),
                    resource(PolicyVerdict.PASS, PolicyVerdict.PASS),
                    WINDOWS,
                )
            val checks = evaluation.evidence.filter { it["type"]?.jsonPrimitive?.content == "policy_check" }
            assertEquals(listOf("second"), checks.map { it.getValue("window_id").jsonPrimitive.content })
            return checks.single()["sample_mode"]?.jsonPrimitive?.content ?: "NONE"
        }

        assertEquals(listOf("NONE", "INSUFFICIENT", "SMALL_SAMPLE", "SMALL_SAMPLE", "FULL"), listOf(0L, 19L, 20L, 99L, 100L).map(::modeFor))
    }

    private fun withRare(samples: Long) =
        NormalizedMetrics(
            MetricSummary(1_000, 0, ExactRatio(0, 1_000), ExactRatio(1_000, 1_000), LatencySummary(90, 90, 90, 90)),
            listOf(
                TransactionSummary(
                    TransactionIdentity(emptyList(), "rare", SampleKind.GATLING_REQUEST),
                    MetricSummary(
                        samples,
                        0,
                        if (samples == 0L) null else ExactRatio(0, samples),
                        ExactRatio(samples * 1_000, 1_000),
                        LatencySummary(90, 90, 90, 90),
                    ),
                ),
            ),
            emptyList(),
            emptyMap(),
        )

    @Test
    fun `an unknown window id blocks the verdict and names the rule and the id`() {
        val ghost = policy("100").let { base -> base.copy(rules = base.rules.map { it.copy(windowIds = listOf("second", "ghost")) }) }

        val evaluation =
            evaluateSharedWindowPolicy(
                ghost,
                RunValidity.VALID,
                metrics(95),
                mapOf("first" to metrics(90), "second" to metrics(90)),
                resource(PolicyVerdict.PASS, PolicyVerdict.PASS),
                WINDOWS,
            )
        val unbound = evaluation.evidence.filter { it["type"]?.jsonPrimitive?.content == "rule_window_check" }

        assertEquals(PolicyVerdict.NO_VERDICT, evaluation.verdict)
        assertEquals(listOf("RULE_WINDOW_NOT_FOUND"), evaluation.coverageReasons)
        assertEquals(listOf("p95"), unbound.map { it.getValue("rule_id").jsonPrimitive.content })
        assertEquals(listOf("ghost"), unbound.map { it.getValue("window_id").jsonPrimitive.content })
        assertEquals("NO_VERDICT", unbound.single().getValue("status").jsonPrimitive.content)
    }
```

`PolicyEvaluationTest.kt` (без снимка названных окон нет):

```kotlin
    @Test
    fun `without a snapshot a rule bound to windows has no window to run in`() {
        val bound = PolicyV1("policy.v1", "gate", listOf(rule("p95", PolicyMetric.RESPONSE_TIME_P95_MS, PolicyOperator.LTE, "100").copy(windowIds = listOf("steady"))), PolicyDefaultsV1(1, 1))

        val result = evaluatePolicy(bound, RunValidity.VALID, metricsWith(100))
        val check = result.evidence.single { it["type"]?.jsonPrimitive?.content == "policy_check" }

        assertEquals(PolicyVerdict.NO_VERDICT, result.verdict)
        assertEquals(listOf("RULE_WINDOW_NOT_FOUND"), result.coverageReasons)
        assertEquals("RULE_WINDOW_NOT_FOUND", check.getValue("reason_code").jsonPrimitive.content)
    }
```

- [ ] **Step 2.2:** Run: `.\gradlew.bat test --tests "io.ltverdict.core.WindowPolicyEvaluationTest" --tests "io.ltverdict.core.PolicyEvaluationTest"`. Expected: FAIL.

- [ ] **Step 2.3: Реализация.** `Policy.kt`, `evaluatePolicy`: после проверки `policy == null` заменить обход `policy.rules.forEach` на

```kotlin
    val applicable = if (windowId == null) policy.rules else policy.rules.filter { it.windowIds == null || windowId in it.windowIds }
    if (applicable.isEmpty()) return PolicyEvaluation(PolicyVerdict.NO_POLICY, reasons.distinct(), findings, evidence)
    applicable.forEach { rule ->
        if (windowId == null && rule.windowIds != null) {
            reasons += REASON_RULE_WINDOW_NOT_FOUND
            checks += policyCheck(rule, null, null, REASON_RULE_WINDOW_NOT_FOUND, null, includeMetricEvidence, null)
            return@forEach
        }
        ...прежнее тело...
```

(константа `private const val REASON_RULE_WINDOW_NOT_FOUND = "RULE_WINDOW_NOT_FOUND"`; `applicable.isEmpty()` без `windowId` невозможна, так как `rules` непусты). `WindowPolicy.kt`: условие пустого окна

```kotlin
        val applies = policy != null && policy.rules.any { it.windowIds == null || window.id in it.windowIds }
        if (applies && validity == RunValidity.VALID && metrics.overall.sampleCount == 0L) {
```

и после цикла по окнам:

```kotlin
    val known = windows.map(ResourceWindowV1::id).toSet()
    val declared = policy?.rules.orEmpty().map { it.id to it.windowIds }
    val unbound = declared.flatMap { (ruleId, ids) -> ids.orEmpty().filter { it !in known }.map { ruleId to it } }.distinct()
    if (validity == RunValidity.VALID && unbound.isNotEmpty()) {
        unbound.forEach { (ruleId, windowId) -> evidence += ruleWindowCheck(ruleId, windowId) }
        reasons += RULE_WINDOW_NOT_FOUND
        windowVerdicts += PolicyVerdict.NO_VERDICT
    }
```

```kotlin
internal fun ruleWindowCheck(
    ruleId: String,
    windowId: String,
): JsonObject =
    buildJsonObject {
        put("id", "rule-window-check-${sha256Hex("$ruleId\u0000$windowId".encodeToByteArray())}")
        put("type", "rule_window_check")
        put("rule_id", ruleId)
        put("window_id", windowId)
        put("status", "NO_VERDICT")
        put("reason_code", RULE_WINDOW_NOT_FOUND)
    }

private const val RULE_WINDOW_NOT_FOUND = "RULE_WINDOW_NOT_FOUND"
```

`ui/src/verdictReasons.ts`: `RULE_WINDOW_NOT_FOUND: { noVerdict: true, text: 'Окно, названное в правиле, не найдено в снимке ресурсов: правило не проверено.' },`.

- [ ] **Step 2.4:** Run: `.\gradlew.bat test` и `npx playwright test --config e2e/verdict-ui.config.ts` (из `ui`). Expected: PASS; существующие тесты без `window_ids` не меняются (регрессия).

- [ ] **Step 2.5:** Commit: `feat(policy): bind business rules to named windows`.

### Task 3: Документация

- [ ] **Step 3.1:** `docs/user/slice-1-local-analysis.md`: раздел «Окна правил» (поле `window_ids`, поведение без поля, `NO_POLICY` окна без правил, `RULE_WINDOW_NOT_FOUND`, пример стадий, рекомендация привязывать редкие транзакции к окнам, где они достигают `min_samples`; подчеркнуть, что разгон и завершение входят в оценку при неявном окне); таблица кодов: `WINDOW_IDS_INVALID`; нормативный prompt (раздел «Нормативный prompt для внешней нейросети») разрешает `window_ids`, только если пользователь назвал окна; `CHANGELOG.md` (`Added`). Run markdownlint для двух файлов. Commit: `docs(policy): describe window_ids`.

**Риски S5:** окна по умолчанию (`run-intersection`) уже существуют и включают разгон; `window_ids` не меняет их. Пустое окно в стадии с применимым правилом по-прежнему даёт `BUSINESS_OBSERVATIONS_NOT_FOUND`. Количество гистограмм окон (`MAX_WINDOW_HISTOGRAMS`) считается по всем окнам снимка и от `window_ids` не зависит.

**Documentation impact:** `docs/user/slice-1-local-analysis.md`, `CHANGELOG.md`, схема и примеры `policy.v1`.

## S6. platform_rules: схема, разворачивание по сервисам, покрытие (строгий режим)

**Цель:** политика может содержать платформенные SLA-правила по ожидаемым сервисам; правило-шаблон разворачивается по сервисам в обычные `ResourceRuleV1` и оценивается существующим `evaluateRule`; отсутствие сервиса, неоднозначный ряд, чужая единица или агрегация, слишком короткое окно и пропавший сигнал покрытия дают `NO_VERDICT`, а не `PASS`; один владелец SLA-порога.

**Ветка:** `feat/policy-platform-rules`. **Размер:** XL (оценка: до 800 строк в `src/main`, больше 900 в тестах, около 20 файлов). Дробить нельзя: принятая политикой, но не вычисляемая секция `platform_rules` дала бы молчаливый `PASS`. По AGENTS.md п. 10: после задачи 2 остановиться и сообщить владельцу фактический размер diff. До задачи 3 ветка не сливается (парсер принимает поля, а оценка и конфликт-проверка подключаются позже).

**Зависит от:** S5 (`windowIdsAt`, `RULE_WINDOW_NOT_FOUND`), S1 (`defaults`) и среза `interval_max` ADR 0014 (жёсткая предпосылка: у правила покрытия обязана быть агрегация `interval_max`, `ResourceAggregation.INTERVAL_MAX` должен существовать до старта; тесты ниже на него опираются). **Identity:** не меняется (новые поля политики попадают в `policy_sha256`; ресурсный снимок и его semantic hash не меняются, пороги живут в политике).

**Что не входит:** допуск пропусков (S7: здесь строго, как у правил снимка), реализация `interval_max` (она в срезе ADR 0014; допустимые агрегации берутся из `ResourceAggregation.entries`), артефакт pod-view (ADR 0020), плечи в правиле, wildcard/regex, UI (S8), предустановка профиля (S9), миграция существующих профилей источников.

**Контракты и версии:**

- `policy.v1` на месте. Верхний уровень: `platform_services` (массив уникальных имён, 1..64), `platform_coverage` ({`signal`}), `platform_rules` (1..256). Элемент `platform_rules[]`: `id`, `signal`, `scope`, `operator` (`gt|lt`, условие нарушения), `threshold`, `unit`, `aggregation`, `min_consecutive_cells` (1..100 000), `effect` (`sla|diagnostic`), необязательное `window_ids`. Область: `{"kind":"service","services":[...]}` либо `{"kind":"all_services","except":[...]}` (каталог — `platform_services`).
- Новые коды валидации: `UNKNOWN_AGGREGATION`; существующие `INVALID_SCOPE`, `DUPLICATE_RULE_ID` (общий реестр `rules` и `platform_rules`, плюс коллизия развёрнутых `<id>/<service>`), `UNKNOWN_OPERATOR`, `UNKNOWN_EFFECT`, `INVALID_MINIMUM`, `MISSING_FIELD`, `RESOURCE_LIMIT_EXCEEDED` (развёрнутых проверок больше 256 или развёрнутый id длиннее 128 байт UTF-8); новые `PLATFORM_COVERAGE_MISSING` и `PLATFORM_AGGREGATION_OPERATOR_MISMATCH` (`gt` с порогом `0` и агрегацией `interval_min`). `INVALID_SCOPE` также при пустой области после вычитания `except`. Покрытие: для **каждой пары «сервис × окно»**, в которой действует платформенное `sla`-правило, есть правило с сигналом `platform_coverage.signal`, `gt`, порогом `0`, `min_consecutive_cells = 1`, `effect = sla`, агрегацией `interval_max`, чья область включает сервис, а `window_ids` включают окно (отсутствие `window_ids` у правила покрытия означает все окна; у покрываемого правила без `window_ids` покрытие обязано быть без `window_ids`).
- Ошибки привязки (до анализа, код выхода CLI 4, API 422 с телом `{valid:false, errors}`): `PLATFORM_RULES_CONFLICT` (политика содержит `platform_rules`, а снимок или профиль источника поставляет ресурсное правило `effect=sla`; diagnostic-правила снимка допустимы), `DUPLICATE_RULE_ID` (развёрнутый id совпал с id правила снимка), `RESOURCE_LIMIT_EXCEEDED` (развёрнутых и правил снимка больше 256).
- Новые причины (все блокирующие): `PLATFORM_SERIES_AMBIGUOUS`, `PLATFORM_UNIT_MISMATCH`, `PLATFORM_AGGREGATION_MISMATCH`, `RULE_WINDOW_TOO_SHORT`, `RESOURCE_SNAPSHOT_REQUIRED` (политика с платформенным `sla` и без снимка; для diagnostic-only причина информационная); существующая `RESOURCE_SERIES_NOT_FOUND`; новая `PLATFORM_SERVICE_NOT_IN_CATALOG` (для `all_services`: в снимке есть ряд сигнала у сервиса, которого нет ни в каталоге, ни в `except`).
- Evidence: `resource_policy_check` развёрнутого правила получает `platform_rule_id` и `service`; id проверки строится от id развёрнутого правила `<rule.id>/<service>`.
- Ряд привязывается по точным `metric = signal`, `entity = service`, `role = system`; нужен ровно один ряд.
- Identity: без изменений. `RunComparison.kt:424-425` и `BaselineComparison.kt:942-943` не затронуты.

**Files:**

- Modify: `src/main/kotlin/io/ltverdict/core/Model.kt` (`PlatformRuleV1`, `PlatformScope`, `PlatformCoverageV1`, поля `PolicyV1`)
- Modify: `src/main/kotlin/io/ltverdict/core/Policy.kt:30-35,81-99,427-469` (разбор, валидация, `RESOURCE_SNAPSHOT_REQUIRED`)
- Create: `src/main/kotlin/io/ltverdict/core/PlatformRules.kt` (`resolveServices`, `PlatformExpansion`, `expandPlatformRules`, `validatePlatformBinding`)
- Modify: `src/main/kotlin/io/ltverdict/core/ResourceSnapshot.kt:69-77` (`ResourceRuleV1`: `windowIds`, `platform`)
- Modify: `src/main/kotlin/io/ltverdict/core/ResourceStatistics.kt:21-75,322-341` (параметр `platform`, пропуск неприменимых окон, исходы привязки, evidence)
- Modify: `src/main/kotlin/io/ltverdict/core/WindowPolicy.kt` (неизвестные `window_ids` платформенных правил)
- Modify: `src/main/kotlin/io/ltverdict/core/AnalysisService.kt:84-100,388-411`
- Modify: `src/main/kotlin/io/ltverdict/cli/CommandLine.kt:225-240`, `src/main/kotlin/io/ltverdict/web/LocalApi.kt:1380-1395`, `src/main/kotlin/io/ltverdict/jobs/AnalysisJobs.kt:206-241`
- Modify: `docs/contracts/policy/v1/policy.schema.json`; Create: `examples/valid/platform-services.json`, `examples/invalid/platform-except-outside-catalog.json`, `platform-all-services-without-catalog.json`, `platform-coverage-missing.json`; Modify: `fixtures/slice1/manifest.json`
- Modify: `ui/src/verdictReasons.ts` (шесть записей)
- Create (тесты): `src/test/kotlin/io/ltverdict/core/PlatformRulesTest.kt`; Modify: `PolicyTest.kt`, `FixtureManifestTest.kt`, `AnalysisServiceTest.kt`, `src/test/kotlin/io/ltverdict/web/LocalApiTest.kt`, `src/test/kotlin/io/ltverdict/jobs/AnalysisJobsTest.kt`, `src/test/kotlin/io/ltverdict/cli/CommandLineTest.kt`
- Modify: `docs/user/slice-1-local-analysis.md`, `CHANGELOG.md`

**Interfaces:**

- Produces:
  - `PolicyV1(..., defaults, platformServices: List<String>? = null, platformRules: List<PlatformRuleV1> = emptyList(), platformCoverage: PlatformCoverageV1? = null)`
  - `PlatformRuleV1(id, signal, scope: PlatformScope, operator: ResourceOperator, threshold: BigDecimal, unit, aggregation: ResourceAggregation, minConsecutiveCells: Int, effect: ResourceRuleEffect, windowIds: List<String>? = null)`
  - `sealed interface PlatformScope { data class Services(val names: List<String>); data class AllServices(val except: List<String>) }`; `PlatformCoverageV1(val signal: String)`
  - `internal fun resolveServices(scope: PlatformScope, catalog: List<String>?): List<String>`
  - `ResourceRuleV1(..., effect, windowIds: List<String>? = null, platform: PlatformRuleRef? = null)`; `data class PlatformRuleRef(val ruleId: String, val service: String)`
  - `internal data class PlatformExpansion(val rules: List<ResourceRuleV1>, val bindingFailures: Map<String, String>)` с `companion object { val EMPTY }`
  - `internal fun expandPlatformRules(policy: PolicyV1, snapshot: ResourceSnapshotV1): PlatformExpansion`
  - `internal fun validatePlatformBinding(policy: PolicyV1, snapshot: ResourceSnapshotV1): List<PolicyValidationError>`
  - `evaluateResources(snapshot, windows, checkCancelled = {}, platform: PlatformExpansion = PlatformExpansion.EMPTY)`
- Consumes: `ResourceAggregation.entries` (допустимые агрегации), `MAX_RESOURCE_RULES` (`ResourceSnapshot.kt:656`), `windowIdsAt` (S5).
- Для S7: `ResourceRuleV1` и `PlatformRuleV1` получают поля допуска; для S9: пример и документация.

### Task 1: Разбор и валидация (тесты красные)

- [ ] **Step 1.1: Красные тесты разбора.** Создать `src/test/kotlin/io/ltverdict/core/PlatformRulesTest.kt`:

```kotlin
package io.ltverdict.core

import io.ltverdict.ingest.RunValidity
import io.ltverdict.metrics.ExactRatio
import io.ltverdict.metrics.LatencySummary
import io.ltverdict.metrics.MetricSummary
import io.ltverdict.metrics.NormalizedMetrics
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path

class PlatformRulesTest {
    @Test
    fun `platform sections fail closed at their exact fields`() {
        listOf(
            Triple(policyJson(catalog = ""), "INVALID_SCOPE", "/platform_rules/0/scope"),
            Triple(policyJson(cpuScope = """{"kind":"all_services","except":["ghost"]}"""), "INVALID_SCOPE", "/platform_rules/0/scope/except/0"),
            Triple(policyJson(coverage = ""), "MISSING_FIELD", "/platform_coverage"),
            Triple(policyJson(coverScope = """{"kind":"service","services":["orders"]}"""), "PLATFORM_COVERAGE_MISSING", "/platform_coverage"),
            Triple(policyJson(coverMin = 2), "PLATFORM_COVERAGE_MISSING", "/platform_coverage"),
            Triple(policyJson(coverThreshold = "1"), "PLATFORM_COVERAGE_MISSING", "/platform_coverage"),
            Triple(policyJson(coverAggregation = "interval_mean"), "PLATFORM_COVERAGE_MISSING", "/platform_coverage"),
            Triple(policyJson(coverExtra = ""","window_ids":["w1"]"""), "PLATFORM_COVERAGE_MISSING", "/platform_coverage"),
            Triple(policyJson(cpuScope = """{"kind":"all_services","except":["orders","payments"]}"""), "INVALID_SCOPE", "/platform_rules/0/scope"),
            Triple(policyJson(cpuThreshold = "0", cpuAggregation = "interval_min"), "PLATFORM_AGGREGATION_OPERATOR_MISMATCH", "/platform_rules/0/aggregation"),
            Triple(policyJson(coverId = "p95"), "DUPLICATE_RULE_ID", "/platform_rules/1/id"),
            Triple(policyJson(cpuAggregation = "weekly"), "UNKNOWN_AGGREGATION", "/platform_rules/0/aggregation"),
            Triple(policyJson(cpuExtra = ""","window_ids":[]"""), "WINDOW_IDS_INVALID", "/platform_rules/0/window_ids"),
            Triple(policyJson(cpuId = "c".repeat(125)), "RESOURCE_LIMIT_EXCEEDED", "/platform_rules/0/id"),
            Triple(policyJson(cpuScope = """{"kind":"service","services":[]}"""), "INVALID_SCOPE", "/platform_rules/0/scope/services"),
            Triple(policyJson(cpuScope = """{"kind":"region"}"""), "INVALID_SCOPE", "/platform_rules/0/scope/kind"),
        ).forEach { (source, code, pointer) ->
            val errors = (validatePolicy(ByteArrayInputStream(source.encodeToByteArray())) as PolicyValidation.Invalid).errors
            assertEquals(code to pointer, errors.first().code to errors.first().jsonPointer, source)
        }
    }

    @Test
    fun `a complete platform policy parses and resolves its services`() {
        val policy = policy(policyJson(cpuScope = """{"kind":"all_services","except":["payments"]}"""))

        assertEquals(listOf("orders", "payments"), policy.platformServices)
        assertEquals(listOf("orders"), resolveServices(policy.platformRules[0].scope, policy.platformServices))
        assertEquals(listOf("orders", "payments"), resolveServices(policy.platformRules[1].scope, policy.platformServices))
        assertEquals("unavailable", policy.platformCoverage?.signal)
        assertTrue(policy.platformRules.all { it.effect == ResourceRuleEffect.SLA })
    }

    @Test
    fun `coverage for a window needs a coverage rule that includes that window`() {
        val both = policyJson(cpuExtra = ""","window_ids":["w1"]""", coverExtra = ""","window_ids":["w1","w2"]""")
        val missing = policyJson(cpuExtra = ""","window_ids":["w2"]""", coverExtra = ""","window_ids":["w1"]""")

        assertTrue(validatePolicy(ByteArrayInputStream(both.encodeToByteArray())) is PolicyValidation.Valid)
        assertEquals(
            "PLATFORM_COVERAGE_MISSING",
            (validatePolicy(ByteArrayInputStream(missing.encodeToByteArray())) as PolicyValidation.Invalid).errors.first().code,
        )
    }

    @Test
    fun `platform aggregation values in the schema match the runtime enum`() {
        val schema = Json.parseToJsonElement(Files.readString(Path.of("docs/contracts/policy/v1/policy.schema.json"))).jsonObject
        val values =
            schema
                .getValue("\$defs")
                .jsonObject
                .getValue("platform_rule")
                .jsonObject
                .getValue("properties")
                .jsonObject
                .getValue("aggregation")
                .jsonObject
                .getValue("enum")
                .jsonArray
                .map { it.jsonPrimitive.content }

        assertEquals(ResourceAggregation.entries.map { it.wireName }.toSet(), values.toSet())
    }

    private fun policy(json: String = policyJson()) =
        (validatePolicy(ByteArrayInputStream(json.encodeToByteArray())) as PolicyValidation.Valid).policy

    private fun policyJson(
        catalog: String = """"platform_services":["orders","payments"],""",
        coverage: String = """"platform_coverage":{"signal":"unavailable"},""",
        cpuScope: String = """{"kind":"all_services"}""",
        coverScope: String = """{"kind":"all_services"}""",
        coverMin: Int = 1,
        coverId: String = "cover",
        cpuId: String = "cpu",
        cpuAggregation: String = "interval_mean",
        cpuThreshold: String = "0.4",
        cpuExtra: String = "",
        cpuEffect: String = "sla",
        coverEffect: String = "sla",
        coverThreshold: String = "0",
        coverAggregation: String = "interval_max",
        coverExtra: String = "",
    ) = """{"schema_version":"policy.v1","policy_id":"platform","defaults":{"sample_floor":1,"min_samples":1},""" +
        catalog +
        coverage +
        """"rules":[{"id":"p95","metric":"response_time_p95_ms","operator":"lte","threshold":100,"scope":{"kind":"overall"}}],""" +
        """"platform_rules":[""" +
        """{"id":"$cpuId","signal":"cpu_ratio","scope":$cpuScope,"operator":"gt","threshold":$cpuThreshold,"unit":"ratio",""" +
        """"aggregation":"$cpuAggregation","min_consecutive_cells":2,"effect":"$cpuEffect"$cpuExtra},""" +
        """{"id":"$coverId","signal":"unavailable","scope":$coverScope,"operator":"gt","threshold":$coverThreshold,"unit":"count",""" +
        """"aggregation":"$coverAggregation","min_consecutive_cells":$coverMin,"effect":"$coverEffect"$coverExtra}]}"""
}
```

В `PolicyTest.kt` (`contractExamples`) и `FixtureManifestTest.kt` (`policyExamples`) добавить четыре примера: `valid/platform-services.json` (`Expectation(true, true)`), `invalid/platform-except-outside-catalog.json` (`Expectation(true, false, "INVALID_SCOPE", "/platform_rules/0/scope/except/0")`), `invalid/platform-all-services-without-catalog.json` (`Expectation(true, false, "INVALID_SCOPE", "/platform_rules/0/scope")`), `invalid/platform-coverage-missing.json` (`Expectation(true, false, "PLATFORM_COVERAGE_MISSING", "/platform_coverage")`): межполевые проверки схема выразить не может. Тело `valid/platform-services.json`:

```json
{
  "schema_version": "policy.v1",
  "policy_id": "platform-services",
  "rules": [
    {
      "id": "overall-errors",
      "metric": "error_rate_ratio",
      "operator": "lte",
      "threshold": 0.01,
      "scope": {
        "kind": "overall"
      }
    }
  ],
  "platform_services": ["orders", "payments"],
  "platform_coverage": {
    "signal": "openshift_unavailable_replicas"
  },
  "platform_rules": [
    {
      "id": "cpu-share",
      "signal": "openshift_container_cpu_limit_ratio",
      "scope": {
        "kind": "all_services",
        "except": ["payments"]
      },
      "operator": "gt",
      "threshold": 0.4,
      "unit": "ratio",
      "aggregation": "interval_mean",
      "min_consecutive_cells": 2,
      "effect": "sla"
    },
    {
      "id": "replicas",
      "signal": "openshift_unavailable_replicas",
      "scope": {
        "kind": "all_services"
      },
      "operator": "gt",
      "threshold": 0,
      "unit": "count",
      "aggregation": "interval_max",
      "min_consecutive_cells": 1,
      "effect": "sla"
    }
  ]
}
```

Три `invalid`-файла получаются из него: (1) `except` равен `["ghost"]`, `policy_id` `platform-except-outside-catalog`; (2) удалён `platform_services`, `policy_id` `platform-all-services-without-catalog`; (3) у правила `replicas` область `{"kind":"service","services":["orders"]}`, `policy_id` `platform-coverage-missing`. Манифест: записи в `policy_examples` (`schema_valid: true`, `runtime_valid: false` для invalid, с `expected_diagnostic`) и `artifacts`.

- [ ] **Step 1.2:** Run: `.\gradlew.bat test --tests "io.ltverdict.core.PlatformRulesTest"`. Expected: FAIL (`UNKNOWN_FIELD` у `platform_services`).

- [ ] **Step 1.3: Реализация модели.** `Model.kt`:

```kotlin
internal data class PlatformRuleV1(
    val id: String,
    val signal: String,
    val scope: PlatformScope,
    val operator: ResourceOperator,
    val threshold: BigDecimal,
    val unit: String,
    val aggregation: ResourceAggregation,
    val minConsecutiveCells: Int,
    val effect: ResourceRuleEffect,
    val windowIds: List<String>? = null,
)

internal sealed interface PlatformScope {
    data class Services(
        val names: List<String>,
    ) : PlatformScope

    data class AllServices(
        val except: List<String>,
    ) : PlatformScope
}

internal data class PlatformCoverageV1(
    val signal: String,
)
```

`PolicyV1` получает последними параметрами `val platformServices: List<String>? = null, val platformRules: List<PlatformRuleV1> = emptyList(), val platformCoverage: PlatformCoverageV1? = null`.

- [ ] **Step 1.4: Реализация разбора** (`Policy.kt`). Допустить ключи корня `platform_services`, `platform_rules`, `platform_coverage`; `longInRangeAt` (S1) расширить параметрами `max: Long = MAX_SAMPLES_BOUND, code: String = "MIN_SAMPLES_OUT_OF_RANGE"`. После цикла бизнес-правил (в нём реестр `ids` уже ведётся) добавить:

```kotlin
    val catalog = root["platform_services"]?.let { parseNames(it, "/platform_services") }
    val platformRules = root["platform_rules"]?.let { parsePlatformRules(it, catalog, ids) }.orEmpty()
    val coverage = parsePlatformCoverage(root["platform_coverage"], platformRules, catalog)
    return PolicyV1(schemaVersion, policyId, rules, defaults, catalog, platformRules, coverage)
```

```kotlin
private const val MAX_PLATFORM_NAMES = 64

private fun parseNames(
    element: JsonElement,
    pointer: String,
): List<String> {
    val array = element as? JsonArray ?: fail("INVALID_TYPE", pointer, "expected an array of names")
    if (array.isEmpty() || array.size > MAX_PLATFORM_NAMES) fail("INVALID_SCOPE", pointer, "expected 1..$MAX_PLATFORM_NAMES names")
    val names =
        array.mapIndexed { index, value ->
            val name = (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: fail("INVALID_TYPE", pointer.child("$index"), "name must be a string")
            validateIdentifier(name, pointer.child("$index"))
            name
        }
    if (names.toSet().size != names.size) fail("INVALID_SCOPE", pointer, "names must be unique")
    return names
}

private fun parsePlatformScope(
    element: JsonElement,
    pointer: String,
    catalog: List<String>?,
): PlatformScope {
    val scope = element.objectAt(pointer)
    return when (scope.stringAt("kind", pointer)) {
        "service" -> {
            scope.rejectUnknown(setOf("kind", "services"), pointer)
            PlatformScope.Services(parseNames(scope.required("services", pointer), "$pointer/services"))
        }

        "all_services" -> {
            scope.rejectUnknown(setOf("kind", "except"), pointer)
            if (catalog == null) fail("INVALID_SCOPE", pointer, "all_services requires platform_services")
            val except =
                scope["except"]?.let { raw ->
                    if (raw is JsonArray && raw.isEmpty()) emptyList() else parseNames(raw, "$pointer/except")
                }.orEmpty()
            except.forEachIndexed { index, name ->
                if (name !in catalog) fail("INVALID_SCOPE", "$pointer/except/$index", "except must name a platform service")
            }
            if (resolveServices(PlatformScope.AllServices(except), catalog).isEmpty()) {
                fail("INVALID_SCOPE", pointer, "scope is empty after except")
            }
            PlatformScope.AllServices(except)
        }

        else -> fail("INVALID_SCOPE", "$pointer/kind", "unknown scope kind")
    }
}

private fun parsePlatformRules(
    element: JsonElement,
    catalog: List<String>?,
    ids: MutableSet<String>,
): List<PlatformRuleV1> {
    val array = element as? JsonArray ?: fail("INVALID_TYPE", "/platform_rules", "platform_rules must be an array")
    if (array.isEmpty()) fail("EMPTY_RULES", "/platform_rules", "at least one platform rule is required")
    if (array.size > MAX_POLICY_RULES) fail("RESOURCE_LIMIT_EXCEEDED", "/platform_rules", "too many platform rules")
    val rules =
        array.mapIndexed { index, value ->
            val pointer = "/platform_rules/$index"
            val item = value.objectAt(pointer)
            item.rejectUnknown(
                setOf("id", "signal", "scope", "operator", "threshold", "unit", "aggregation", "min_consecutive_cells", "effect", "window_ids"),
                pointer,
            )
            val id = item.stringAt("id", pointer)
            validateIdentifier(id, "$pointer/id")
            if (!ids.add(id)) fail("DUPLICATE_RULE_ID", "$pointer/id", "rule id must be unique")
            val signal = item.stringAt("signal", pointer)
            validateIdentifier(signal, "$pointer/signal")
            val scope = parsePlatformScope(item.required("scope", pointer), "$pointer/scope", catalog)
            val operatorName = item.stringAt("operator", pointer)
            val operator = ResourceOperator.entries.find { it.wireName == operatorName } ?: fail("UNKNOWN_OPERATOR", "$pointer/operator", "unknown operator")
            val threshold = item.numberAt("threshold", pointer)
            val unit = item.stringAt("unit", pointer)
            validateIdentifier(unit, "$pointer/unit")
            val aggregationName = item.stringAt("aggregation", pointer)
            val aggregation =
                ResourceAggregation.entries.find { it.wireName == aggregationName } ?: fail("UNKNOWN_AGGREGATION", "$pointer/aggregation", "unknown aggregation")
            if (operator == ResourceOperator.GT && threshold.signum() == 0 && aggregation.wireName == "interval_min") {
                fail("PLATFORM_AGGREGATION_OPERATOR_MISMATCH", "$pointer/aggregation", "gt 0 must not use interval_min")
            }
            val minimum =
                item.longInRangeAt("min_consecutive_cells", pointer, MAX_POINTS_PER_SERIES.toLong(), "INVALID_MINIMUM")
                    ?: fail("MISSING_FIELD", "$pointer/min_consecutive_cells", "required field is missing")
            val effectName = item.stringAt("effect", pointer)
            val effect = ResourceRuleEffect.entries.find { it.wireName == effectName } ?: fail("UNKNOWN_EFFECT", "$pointer/effect", "unknown effect")
            PlatformRuleV1(id, signal, scope, operator, threshold, unit, aggregation, minimum.toInt(), effect, item.windowIdsAt(pointer))
        }
    val expanded = HashSet<String>()
    rules.forEachIndexed { index, rule ->
        resolveServices(rule.scope, catalog).forEach { service ->
            val expandedId = "${rule.id}/$service"
            if (expandedId.encodeToByteArray().size > MAX_IDENTIFIER_BYTES) {
                fail("RESOURCE_LIMIT_EXCEEDED", "/platform_rules/$index/id", "expanded rule id exceeds 128 UTF-8 bytes")
            }
            if (!expanded.add(expandedId)) fail("DUPLICATE_RULE_ID", "/platform_rules/$index/id", "expanded rule id collides")
        }
    }
    if (expanded.size > MAX_POLICY_RULES) fail("RESOURCE_LIMIT_EXCEEDED", "/platform_rules", "too many expanded platform checks")
    return rules
}

private fun parsePlatformCoverage(
    element: JsonElement?,
    rules: List<PlatformRuleV1>,
    catalog: List<String>?,
): PlatformCoverageV1? {
    val coverage =
        element?.let {
            val value = it.objectAt("/platform_coverage")
            value.rejectUnknown(setOf("signal"), "/platform_coverage")
            val signal = value.stringAt("signal", "/platform_coverage")
            validateIdentifier(signal, "/platform_coverage/signal")
            PlatformCoverageV1(signal)
        }
    val sla = rules.filter { it.effect == ResourceRuleEffect.SLA }
    if (sla.isNotEmpty()) {
        if (coverage == null) fail("MISSING_FIELD", "/platform_coverage", "platform_coverage is required when an SLA platform rule exists")
        val coverers =
            sla.filter {
                it.signal == coverage.signal &&
                    it.operator == ResourceOperator.GT &&
                    it.threshold.signum() == 0 &&
                    it.minConsecutiveCells == 1 &&
                    it.aggregation.wireName == "interval_max"
            }
        sla.forEach { rule ->
            resolveServices(rule.scope, catalog).forEach { service ->
                val covered =
                    coverers.any { cover ->
                        service in resolveServices(cover.scope, catalog) &&
                            (cover.windowIds == null || rule.windowIds?.let(cover.windowIds::containsAll) == true)
                    }
                if (!covered) {
                    fail(
                        "PLATFORM_COVERAGE_MISSING",
                        "/platform_coverage",
                        "every service and window of an SLA platform rule needs an SLA coverage rule (gt 0, interval_max, min_consecutive_cells = 1)",
                    )
                }
            }
        }
    }
    return coverage
}
```

Новый файл `PlatformRules.kt`, начало:

```kotlin
package io.ltverdict.core

internal fun resolveServices(
    scope: PlatformScope,
    catalog: List<String>?,
): List<String> =
    when (scope) {
        is PlatformScope.Services -> scope.names
        is PlatformScope.AllServices -> catalog.orEmpty().filter { it !in scope.except }
    }
```

Схема: в корне `properties` добавить `platform_services`, `platform_coverage`, `platform_rules`; в `$defs` — `platform_rule`, `service_scope`, `all_services_scope`:

```json
    "platform_services": { "type": "array", "minItems": 1, "maxItems": 64, "uniqueItems": true, "items": { "type": "string", "minLength": 1, "maxLength": 128 } },
    "platform_coverage": { "type": "object", "additionalProperties": false, "required": ["signal"], "properties": { "signal": { "type": "string", "minLength": 1, "maxLength": 128 } } },
    "platform_rules": { "type": "array", "minItems": 1, "maxItems": 256, "items": { "$ref": "#/$defs/platform_rule" } }
```

```json
    "platform_rule": {
      "type": "object",
      "additionalProperties": false,
      "required": ["id", "signal", "scope", "operator", "threshold", "unit", "aggregation", "min_consecutive_cells", "effect"],
      "properties": {
        "id": { "type": "string", "minLength": 1, "maxLength": 128 },
        "signal": { "type": "string", "minLength": 1, "maxLength": 128 },
        "scope": { "oneOf": [{ "$ref": "#/$defs/service_scope" }, { "$ref": "#/$defs/all_services_scope" }] },
        "operator": { "enum": ["gt", "lt"] },
        "threshold": { "type": "number" },
        "unit": { "type": "string", "minLength": 1, "maxLength": 128 },
        "aggregation": { "enum": ["interval_mean", "interval_rate", "interval_max"] },
        "min_consecutive_cells": { "type": "integer", "minimum": 1, "maximum": 100000 },
        "effect": { "enum": ["diagnostic", "sla"] },
        "window_ids": { "$ref": "#/$defs/window_ids" }
      }
    },
    "service_scope": {
      "type": "object",
      "additionalProperties": false,
      "required": ["kind", "services"],
      "properties": {
        "kind": { "const": "service" },
        "services": { "type": "array", "minItems": 1, "maxItems": 64, "uniqueItems": true, "items": { "type": "string", "minLength": 1, "maxLength": 128 } }
      }
    },
    "all_services_scope": {
      "type": "object",
      "additionalProperties": false,
      "required": ["kind"],
      "properties": {
        "kind": { "const": "all_services" },
        "except": { "type": "array", "maxItems": 64, "uniqueItems": true, "items": { "type": "string", "minLength": 1, "maxLength": 128 } }
      }
    }
```

(Перечень `aggregation` в схеме равен значениям `ResourceAggregation` на момент среза; если срез ADR 0014 добавил и `interval_min`, он добавляется сюда же, а тест `platform aggregation values in the schema match the runtime enum` это проверяет. `MAX_POINTS_PER_SERIES` = 100 000, `ResourceSnapshot.kt`.)

- [ ] **Step 1.5:** Run: `.\gradlew.bat test --tests "io.ltverdict.core.PlatformRulesTest" --tests "io.ltverdict.core.PolicyTest" --tests "io.ltverdict.fixtures.FixtureManifestTest"` и `npm --prefix ui run test:contracts`. Expected: PASS. Commit: `feat(policy): parse platform rules, services and coverage`.

### Task 2: Разворачивание, привязка и оценка

- [ ] **Step 2.1: Красные тесты оценки** (добавить в `PlatformRulesTest.kt`; помощники в конце класса):

```kotlin
    @Test
    fun `every expected service gets a check and one violating service fails the window`() {
        val orders = healthy("orders", cpu = listOf("0.5", "0.5", "0.1", "0.1"))
        val snapshot = snapshot(orders + healthy("payments"))

        val result = evaluate(snapshot)

        assertEquals(PolicyVerdict.FAIL, result.windowVerdicts.getValue("steady"))
        assertEquals(
            listOf("cpu/orders" to "FAIL", "cpu/payments" to "PASS", "cover/orders" to "PASS", "cover/payments" to "PASS"),
            result.checks().map { it.str("rule_id") to it.str("status") },
        )
        assertEquals(listOf("orders", "payments", "orders", "payments"), result.checks().map { it.str("service") })
        assertEquals(listOf("cpu", "cpu", "cover", "cover"), result.checks().map { it.str("platform_rule_id") })
    }

    @Test
    fun `a missing duplicate or foreign series never passes silently`() {
        val missing = evaluate(snapshot(healthy("orders")))
        val ambiguous =
            evaluate(snapshot(healthy("orders") + healthy("payments") + series("cpu-orders-2", "cpu_ratio", "orders", "ratio", List(4) { "0.1" })))
        val wrongUnit =
            evaluate(snapshot(healthy("orders") + listOf(series("cpu-payments", "cpu_ratio", "payments", "percent", List(4) { "10" }), healthy("payments")[1])))
        val wrongAggregation =
            evaluate(
                snapshot(
                    healthy("orders") +
                        listOf(
                            series("cpu-payments", "cpu_ratio", "payments", "ratio", List(4) { "0.1" }, ResourceAggregation.INTERVAL_RATE),
                            healthy("payments")[1],
                        ),
                ),
            )
        val generatorOnly =
            evaluate(
                snapshot(
                    healthy("orders") +
                        listOf(
                            series("cpu-payments", "cpu_ratio", "payments", "ratio", List(4) { "0.1" }, role = ResourceRole.GENERATOR),
                            healthy("payments")[1],
                        ),
                ),
            )

        listOf(
            missing to "RESOURCE_SERIES_NOT_FOUND",
            ambiguous to "PLATFORM_SERIES_AMBIGUOUS",
            wrongUnit to "PLATFORM_UNIT_MISMATCH",
            wrongAggregation to "PLATFORM_AGGREGATION_MISMATCH",
            generatorOnly to "RESOURCE_SERIES_NOT_FOUND",
        ).forEach { (result, reason) ->
            assertEquals(PolicyVerdict.NO_VERDICT, result.windowVerdicts.getValue("steady"), reason)
            assertTrue(reason in result.coverageReasons, reason)
        }
    }

    @Test
    fun `except removes a service from one rule while coverage still applies to it`() {
        val policy = policy(policyJson(cpuScope = """{"kind":"all_services","except":["payments"]}"""))
        val snapshot = snapshot(healthy("orders") + listOf(series("unavailable-payments", "unavailable", "payments", "count", List(4) { "0" }, ResourceAggregation.INTERVAL_MAX)))

        val result = evaluate(snapshot, policy)

        assertEquals(PolicyVerdict.PASS, result.windowVerdicts.getValue("steady"))
        assertEquals(listOf("cpu/orders", "cover/orders", "cover/payments"), result.checks().map { it.str("rule_id") })
    }

    @Test
    fun `a window shorter than the required series cannot pass`() {
        val result = evaluate(snapshot(healthy("orders", cpu = listOf("0.1")) + healthy("payments", cpu = listOf("0.1")), cells = 1))

        assertEquals(PolicyVerdict.NO_VERDICT, result.windowVerdicts.getValue("steady"))
        assertEquals(setOf("cpu/orders", "cpu/payments"), result.checks().filter { it.str("reason") == "RULE_WINDOW_TOO_SHORT" }.map { it.str("rule_id") }.toSet())
    }

    @Test
    fun `a missing coverage series on one cell blocks the verdict`() {
        val snapshot = snapshot(healthy("orders") + listOf(series("cpu-payments", "cpu_ratio", "payments", "ratio", List(4) { "0.1" }), series("unavailable-payments", "unavailable", "payments", "count", listOf("0", "1", "0", "0"), ResourceAggregation.INTERVAL_MAX)))

        val result = evaluate(snapshot)

        assertEquals(PolicyVerdict.FAIL, result.windowVerdicts.getValue("steady"))
        assertEquals("FAIL", result.checks().single { it.str("rule_id") == "cover/payments" }.str("status"))
    }

    @Test
    fun `platform rules without a snapshot block the verdict only when they are SLA rules`() {
        val sla = evaluatePolicy(policy(), RunValidity.VALID, metricsWith(100))
        val diagnostic =
            evaluatePolicy(policy(policyJson(coverage = "", cpuEffect = "diagnostic", coverEffect = "diagnostic")), RunValidity.VALID, metricsWith(100))

        assertEquals(PolicyVerdict.NO_VERDICT, sla.verdict)
        assertEquals(listOf("RESOURCE_SNAPSHOT_REQUIRED"), sla.coverageReasons)
        assertEquals(PolicyVerdict.PASS, diagnostic.verdict)
        assertEquals(listOf("RESOURCE_SNAPSHOT_REQUIRED"), diagnostic.coverageReasons)
    }

    @Test
    fun `one owner for SLA thresholds and a bounded number of checks`() {
        val policy = policy()
        val sla = rule("snapshot-sla", ResourceRuleEffect.SLA)
        val diagnostic = rule("snapshot-diagnostic", ResourceRuleEffect.DIAGNOSTIC)

        assertEquals(listOf("PLATFORM_RULES_CONFLICT"), validatePlatformBinding(policy, snapshot(healthy("orders"), listOf(sla))).map { it.code })
        assertEquals(emptyList<String>(), validatePlatformBinding(policy, snapshot(healthy("orders"), listOf(diagnostic))).map { it.code })
        assertEquals(
            listOf("DUPLICATE_RULE_ID"),
            validatePlatformBinding(policy, snapshot(healthy("orders"), listOf(rule("cpu/orders", ResourceRuleEffect.DIAGNOSTIC)))).map { it.code },
        )
        val many = List(254) { rule("many-$it", ResourceRuleEffect.DIAGNOSTIC) }
        assertEquals(listOf("RESOURCE_LIMIT_EXCEEDED"), validatePlatformBinding(policy, snapshot(healthy("orders"), many)).map { it.code })
    }

    @Test
    fun `a series of a service outside the catalog blocks an all_services rule`() {
        val result = evaluate(snapshot(healthy("orders") + healthy("payments") + healthy("ghost-svc")))

        assertEquals(PolicyVerdict.NO_VERDICT, result.windowVerdicts.getValue("steady"))
        assertTrue("PLATFORM_SERVICE_NOT_IN_CATALOG" in result.coverageReasons)
        assertEquals(
            setOf("cpu/ghost-svc", "cover/ghost-svc"),
            result.checks().filter { it.str("reason") == "PLATFORM_SERVICE_NOT_IN_CATALOG" }.map { it.str("rule_id") }.toSet(),
        )
    }

    @Test
    fun `an unknown window id of a platform rule blocks the verdict`() {
        val policy = policy(policyJson(cpuExtra = ""","window_ids":["ghost"]"""))
        val snapshot = snapshot(healthy("orders") + healthy("payments"))

        val evaluation =
            evaluateSharedWindowPolicy(
                policy,
                RunValidity.VALID,
                metricsWith(100),
                mapOf("steady" to metricsWith(100)),
                evaluate(snapshot, policy),
                snapshot.windows,
            )

        assertEquals(PolicyVerdict.NO_VERDICT, evaluation.verdict)
        assertTrue("RULE_WINDOW_NOT_FOUND" in evaluation.coverageReasons)
        assertEquals(
            listOf("cpu"),
            evaluation.evidence.filter { it.getValue("type").jsonPrimitive.content == "rule_window_check" }.map { it.str("rule_id") },
        )
    }

    private fun series(
        id: String,
        signal: String,
        service: String,
        unit: String,
        values: List<String?>,
        aggregation: ResourceAggregation = ResourceAggregation.INTERVAL_MEAN,
        role: ResourceRole = ResourceRole.SYSTEM,
    ) = ResourceSeriesV1(id, signal, unit, service, role, aggregation, emptyMap(), values.map { it?.let(::BigDecimal) })

    private fun healthy(
        service: String,
        cpu: List<String?> = List(4) { "0.1" },
    ) = listOf(
        series("cpu-$service", "cpu_ratio", service, "ratio", cpu),
        series("unavailable-$service", "unavailable", service, "count", List(cpu.size) { "0" }, ResourceAggregation.INTERVAL_MAX),
    )

    private fun snapshot(
        series: List<ResourceSeriesV1>,
        rules: List<ResourceRuleV1> = emptyList(),
        cells: Int = 4,
    ) = ResourceSnapshotV1(
        "resource-snapshot.v1",
        "0".repeat(64),
        0,
        10_000,
        cells,
        series,
        listOf(ResourceWindowV1("steady", 0, cells * 10_000L)),
        rules,
        null,
    )

    private fun rule(
        id: String,
        effect: ResourceRuleEffect,
    ) = ResourceRuleV1(id, "cpu-orders", "ratio", ResourceOperator.GT, BigDecimal("0.9"), 1, effect)

    private fun evaluate(
        snapshot: ResourceSnapshotV1,
        policy: PolicyV1 = policy(),
    ) = evaluateResources(snapshot, snapshot.windows, platform = expandPlatformRules(policy, snapshot))

    private fun ResourceEvaluation.checks() = evidence.filter { it.getValue("type").jsonPrimitive.content == "resource_policy_check" }

    private fun kotlinx.serialization.json.JsonObject.str(name: String) = getValue(name).jsonPrimitive.content

    private fun metricsWith(samples: Long) =
        NormalizedMetrics(
            MetricSummary(samples, 0, ExactRatio(0, samples), ExactRatio(10, 1), LatencySummary(50, 100, 100, 100)),
            emptyList(),
            emptyList(),
            emptyMap(),
        )
```

Замечания к тестам: в `a missing duplicate or foreign series` для `wrongUnit`/`wrongAggregation` второй элемент `healthy("payments")[1]` — ряд покрытия payments; в `a window shorter…` правило `cover` (1 ячейка) допустимо и проходит; `ambiguous` — два ряда `cpu_ratio` у `orders`.

- [ ] **Step 2.2:** Run: `.\gradlew.bat test --tests "io.ltverdict.core.PlatformRulesTest"`. Expected: FAIL (нет `expandPlatformRules`, `validatePlatformBinding`, параметра `platform`).

- [ ] **Step 2.3: Реализация.**

`ResourceSnapshot.kt` (`ResourceRuleV1`, добавить в конец два параметра по умолчанию):

```kotlin
internal data class ResourceRuleV1(
    val id: String,
    val seriesId: String,
    val unit: String,
    val operator: ResourceOperator,
    val threshold: BigDecimal,
    val minConsecutiveCells: Int,
    val effect: ResourceRuleEffect,
    val windowIds: List<String>? = null,
    val platform: PlatformRuleRef? = null,
)

internal data class PlatformRuleRef(
    val ruleId: String,
    val service: String,
)
```

`PlatformRules.kt`, продолжение:

```kotlin
internal data class PlatformExpansion(
    val rules: List<ResourceRuleV1>,
    val bindingFailures: Map<String, String>,
) {
    companion object {
        val EMPTY = PlatformExpansion(emptyList(), emptyMap())
    }
}

internal fun expandPlatformRules(
    policy: PolicyV1,
    snapshot: ResourceSnapshotV1,
): PlatformExpansion {
    if (policy.platformRules.isEmpty()) return PlatformExpansion.EMPTY
    val rules = mutableListOf<ResourceRuleV1>()
    val failures = linkedMapOf<String, String>()
    policy.platformRules.forEach { rule ->
        resolveServices(rule.scope, policy.platformServices).forEach { service ->
            val id = "${rule.id}/$service"
            val candidates = snapshot.series.filter { it.metric == rule.signal && it.entity == service && it.role == ResourceRole.SYSTEM }
            val series = candidates.singleOrNull()
            val failure =
                when {
                    candidates.isEmpty() -> "RESOURCE_SERIES_NOT_FOUND"
                    series == null -> "PLATFORM_SERIES_AMBIGUOUS"
                    series.unit != rule.unit -> "PLATFORM_UNIT_MISMATCH"
                    series.aggregation != rule.aggregation -> "PLATFORM_AGGREGATION_MISMATCH"
                    else -> null
                }
            rules +=
                ResourceRuleV1(
                    id,
                    series?.id.orEmpty(),
                    rule.unit,
                    rule.operator,
                    rule.threshold,
                    rule.minConsecutiveCells,
                    rule.effect,
                    rule.windowIds,
                    PlatformRuleRef(rule.id, service),
                )
            failure?.let { failures[id] = it }
        }
        (rule.scope as? PlatformScope.AllServices)?.let { scope ->
            val known = policy.platformServices.orEmpty().toSet() + scope.except
            snapshot.series
                .filter { it.metric == rule.signal && it.role == ResourceRole.SYSTEM && it.entity !in known }
                .map(ResourceSeriesV1::entity)
                .distinct()
                .forEach { entity ->
                    val id = "${rule.id}/$entity"
                    rules +=
                        ResourceRuleV1(
                            id,
                            "",
                            rule.unit,
                            rule.operator,
                            rule.threshold,
                            rule.minConsecutiveCells,
                            rule.effect,
                            rule.windowIds,
                            PlatformRuleRef(rule.id, entity),
                        )
                    failures[id] = "PLATFORM_SERVICE_NOT_IN_CATALOG"
                }
        }
    }
    return PlatformExpansion(rules, failures)
}

internal fun validatePlatformBinding(
    policy: PolicyV1,
    snapshot: ResourceSnapshotV1,
): List<PolicyValidationError> {
    if (policy.platformRules.isEmpty()) return emptyList()
    val errors = mutableListOf<PolicyValidationError>()
    if (snapshot.rules.any { it.effect == ResourceRuleEffect.SLA }) {
        errors += PolicyValidationError("PLATFORM_RULES_CONFLICT", "/platform_rules", "SLA thresholds come from the policy or from the snapshot, not from both")
    }
    val expanded = expandPlatformRules(policy, snapshot).rules
    val taken = snapshot.rules.map(ResourceRuleV1::id).toSet()
    if (expanded.any { it.id in taken }) {
        errors += PolicyValidationError("DUPLICATE_RULE_ID", "/platform_rules", "an expanded platform rule id equals a snapshot rule id")
    }
    if (expanded.size + snapshot.rules.size > MAX_RESOURCE_RULES) {
        errors += PolicyValidationError("RESOURCE_LIMIT_EXCEEDED", "/platform_rules", "expanded platform checks and snapshot rules exceed $MAX_RESOURCE_RULES")
    }
    return errors
}
```

`ResourceStatistics.kt`: сигнатура `evaluateResources(snapshot, windows, checkCancelled = {}, platform: PlatformExpansion = PlatformExpansion.EMPTY)`; внутри цикла окон заменить обход `snapshot.rules.forEach` на

```kotlin
        (snapshot.rules + platform.rules).forEach { rule ->
            checkCancelled()
            if (rule.windowIds != null && window.id !in rule.windowIds) return@forEach
            val checkId = resourceId("resource-policy-check", window.id, rule.id)
            val series = seriesById[rule.seriesId]
            val cells = snapshot.cellIndex(window.toEpochMillis) - snapshot.cellIndex(window.fromEpochMillis)
            val bindingFailure = platform.bindingFailures[rule.id]
            val outcome =
                when {
                    bindingFailure != null -> RuleOutcome("NO_VERDICT", bindingFailure, emptyList())
                    rule.platform != null && cells < rule.minConsecutiveCells -> RuleOutcome("NO_VERDICT", "RULE_WINDOW_TOO_SHORT", emptyList())
                    else -> evaluateRule(snapshot, window, rule, series, checkId, RESOURCE_FINDINGS_MAX - findings.size, checkCancelled)
                }
            ...прежние checks += resourceCheck(...), findings, reasons, slaStatuses...
        }
```

`resourceCheck` (`:322-341`): после `put("reason", ...)`:

```kotlin
        rule.platform?.let {
            put("platform_rule_id", it.ruleId)
            put("service", it.service)
        }
```

`Policy.kt`, `evaluatePolicy`: после проверки `policy == null` (`:99`) и до `val checks`:

```kotlin
    if (windowId == null && policy.platformRules.isNotEmpty()) {
        if (policy.platformRules.any { it.effect == ResourceRuleEffect.SLA }) reasons += REASON_RESOURCE_SNAPSHOT_REQUIRED else informational += REASON_RESOURCE_SNAPSHOT_REQUIRED
    }
```

(объявить `val informational` выше этой точки, перенеся объявление из S1; константа `REASON_RESOURCE_SNAPSHOT_REQUIRED = "RESOURCE_SNAPSHOT_REQUIRED"`).

`WindowPolicy.kt` (код S5): `val declared = policy?.rules.orEmpty().map { it.id to it.windowIds } + policy?.platformRules.orEmpty().map { it.id to it.windowIds }`; остальное без изменений.

`AnalysisService.kt:84-100` (после проверок ресурсов и перед capacity):

```kotlin
        request.policy?.policy?.takeIf { it.platformRules.isNotEmpty() }?.let { policy ->
            request.resources?.let { resources ->
                validatePlatformBinding(policy, resources.snapshot).firstOrNull()?.let { throw IllegalArgumentException(it.code) }
            }
        }
```

`AnalysisService.kt:394`: `evaluateResources(request.resources.snapshot, windows, checkCancelled, request.policy?.policy?.let { expandPlatformRules(it, request.resources.snapshot) } ?: PlatformExpansion.EMPTY)`.

Подключения: `CommandLine.kt:232` (после проверки capacity) — `policy?.policy?.takeIf { it.platformRules.isNotEmpty() }?.let { p -> resources?.let { r -> validatePlatformBinding(p, r.snapshot).takeIf(List<PolicyValidationError>::isNotEmpty)?.let { errors -> throw CliFailure(EXIT_INVALID_INPUT, errors.joinToString("\n") { "${it.code} ${it.jsonPointer}: ${it.message}" }) } } }` (рядом с блоком `capacity?.let`; импорт `validatePlatformBinding`); `LocalApi.kt` после блока `trend?.let` (около `:1394`): то же условие с `throw InvalidPolicy(PolicyValidation.Invalid(errors))`; `AnalysisJobs.kt:206-241`: в `when` по сообщению исключения (перед веткой `else`) добавить

```kotlin
                                        "PLATFORM_RULES_CONFLICT" ->
                                            Diagnostic("PLATFORM_RULES_CONFLICT", "SLA thresholds come from the policy or from the snapshot, not from both")
                                        "DUPLICATE_RULE_ID" ->
                                            Diagnostic("DUPLICATE_RULE_ID", "An expanded platform rule id equals a snapshot rule id")
                                        "RESOURCE_LIMIT_EXCEEDED" ->
                                            Diagnostic("RESOURCE_LIMIT_EXCEEDED", "Expanded platform checks and snapshot rules exceed 256")
```

`ui/src/verdictReasons.ts` (блок «Привязка правил политики» и «SLA-правила ресурсов»): `RESOURCE_SNAPSHOT_REQUIRED`, `PLATFORM_SERIES_AMBIGUOUS`, `PLATFORM_UNIT_MISMATCH`, `PLATFORM_AGGREGATION_MISMATCH`, `RULE_WINDOW_TOO_SHORT`, `PLATFORM_SERVICE_NOT_IN_CATALOG`, все `noVerdict: true`, тексты по-русски, одно предложение каждый; `overview.ts` (S8) ведёт последние пять на `resource-results`.

- [ ] **Step 2.4:** Run: `.\gradlew.bat test --tests "io.ltverdict.core.PlatformRulesTest" --tests "io.ltverdict.core.ResourceStatisticsTest" --tests "io.ltverdict.core.WindowPolicyEvaluationTest"` и `npx playwright test --config e2e/verdict-ui.config.ts`. Expected: PASS; существующие ресурсные тесты не меняются (параметр `platform` по умолчанию пуст).

- [ ] **Step 2.5:** Commit: `feat(policy): evaluate platform rules per expected service`.

### Task 3: Сквозные проверки конфликта

- [ ] **Step 3.1: Красные тесты.** `AnalysisServiceTest.kt` (помощники `withService`, `accept`, `policy`, `resources`, `resourceJson` — в `resourceJson` уже есть SLA-правило `cpu-high`):

```kotlin
    @Test
    fun `platform rules and an SLA rule of the snapshot are rejected before an analysis exists`() =
        withService { store, service ->
            val input = accept(store, OUT_OF_ORDER_CSV.encodeToByteArray(), "platform-conflict.jtl")
            val analyses = input.path.parent.parent.resolve("analyses")
            val conflicting =
                policy(
                    """{"schema_version":"policy.v1","policy_id":"conflict","defaults":{"sample_floor":1,"min_samples":1},""" +
                        """"rules":[{"id":"p95","metric":"response_time_p95_ms","operator":"lte","threshold":1000,"scope":{"kind":"overall"}}],""" +
                        """"platform_rules":[{"id":"cpu","signal":"cpu_used","scope":{"kind":"service","services":["host"]},"operator":"gt",""" +
                        """"threshold":0.8,"unit":"ratio","aggregation":"interval_mean","min_consecutive_cells":1,"effect":"diagnostic"}]}""",
                )

            val failure =
                assertThrows(IllegalArgumentException::class.java) {
                    service.analyze(AnalysisRequest(input, conflicting, resources = resources(resourceJson(input.sha256, "conflict", "0.8").encodeToByteArray())))
                }

            assertEquals("PLATFORM_RULES_CONFLICT", failure.message)
            assertFalse(Files.exists(analyses))
        }
```

`AnalysisJobsTest.kt`: в список `resource binding failures are actionable...` добавить пары `"PLATFORM_RULES_CONFLICT" to "PLATFORM_RULES_CONFLICT"`, `"DUPLICATE_RULE_ID" to "DUPLICATE_RULE_ID"`, `"RESOURCE_LIMIT_EXCEEDED" to "RESOURCE_LIMIT_EXCEEDED"`. `LocalApiTest.kt` (по образцу теста с четырьмя частями; `SPIKE_DROP`, `withServer`, `createJob` существуют):

```kotlin
    @Test
    fun `a platform rule policy with an SLA snapshot rule is rejected before a job exists`() {
        withServer { store, api ->
            val input = store.acceptInput(ByteArrayInputStream(SPIKE_DROP.bytes()), SPIKE_DROP.filename)
            val resources =
                """{"schema_version":"resource-snapshot.v1","load_input_sha256":"${input.sha256}","start_epoch_ms":0,"step_ms":1000,"point_count":2,"series":[{"id":"cpu","metric":"cpu_used","unit":"ratio","entity":"vm","role":"system","aggregation":"interval_mean","values":[0.1,0.9]}],"windows":[{"id":"steady","from_epoch_ms":0,"to_epoch_ms":2000}],"rules":[{"id":"cpu-high","series_id":"cpu","unit":"ratio","operator":"gt","threshold":0.8,"min_consecutive_cells":1,"effect":"sla"}]}"""
                    .encodeToByteArray()
            val policy =
                """{"schema_version":"policy.v1","policy_id":"conflict","defaults":{"sample_floor":1,"min_samples":1},"rules":[{"id":"p95","metric":"response_time_p95_ms","operator":"lte","threshold":1000,"scope":{"kind":"overall"}}],"platform_rules":[{"id":"cpu","signal":"cpu_used","scope":{"kind":"service","services":["vm"]},"operator":"gt","threshold":0.8,"unit":"ratio","aggregation":"interval_mean","min_consecutive_cells":1,"effect":"diagnostic"}]}"""
                    .encodeToByteArray()
            api.bootstrap()

            val response = api.createJob(input.runId, policy, resources)

            assertEquals(422, response.statusCode())
            assertTrue(response.body().contains("PLATFORM_RULES_CONFLICT"))
        }
    }
```

`CommandLineTest.kt` (код выхода 4 на пути CLI; предпроверка срабатывает до разбора окон, поэтому окна снимка значения не имеют; `fixture`, `run`, `tempDir` существуют):

```kotlin
    @Test
    fun `platform rules with an SLA snapshot rule exit with the invalid input code`() {
        val input = fixture("jmeter/xml-5.6.3/input.xml")
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(input)).joinToString("") { "%02x".format(it) }
        val snapshot = tempDir.resolve("snapshot.json")
        Files.writeString(
            snapshot,
            """{"schema_version":"resource-snapshot.v1","load_input_sha256":"$hash","start_epoch_ms":0,"step_ms":1000,"point_count":2,""" +
                """"series":[{"id":"cpu","metric":"cpu_used","unit":"ratio","entity":"vm","role":"system","aggregation":"interval_mean","values":[0.1,0.9]}],""" +
                """"windows":[{"id":"steady","from_epoch_ms":0,"to_epoch_ms":2000}],""" +
                """"rules":[{"id":"cpu-high","series_id":"cpu","unit":"ratio","operator":"gt","threshold":0.8,"min_consecutive_cells":1,"effect":"sla"}]}""",
        )
        val policy = tempDir.resolve("policy.json")
        Files.writeString(
            policy,
            """{"schema_version":"policy.v1","policy_id":"conflict","defaults":{"sample_floor":1,"min_samples":1},""" +
                """"rules":[{"id":"errors","metric":"error_rate_ratio","operator":"lte","threshold":0.5,"scope":{"kind":"overall"}}],""" +
                """"platform_rules":[{"id":"cpu","signal":"cpu_used","scope":{"kind":"service","services":["vm"]},"operator":"gt","threshold":0.8,""" +
                """"unit":"ratio","aggregation":"interval_mean","min_consecutive_cells":1,"effect":"diagnostic"}]}""",
        )

        val result = run("analyze", input.toString(), "--policy", policy.toString(), "--resources", snapshot.toString(), "--data-dir", tempDir.resolve("data").toString())

        assertEquals(4, result.exitCode)
        assertTrue(result.stderr.contains("PLATFORM_RULES_CONFLICT"), result.stderr)
    }
```

Путь онлайн-источника (снимок появляется после сбора) проходит через тот же `AnalysisService.analyze` (`SourceAnalysis.kt:115-125`), поэтому покрыт тестом `AnalysisServiceTest` и отображением кода в `AnalysisJobsTest`.

- [ ] **Step 3.2:** Run: `.\gradlew.bat test --tests "io.ltverdict.core.AnalysisServiceTest" --tests "io.ltverdict.jobs.AnalysisJobsTest" --tests "io.ltverdict.web.LocalApiTest"`. Expected: FAIL до подключения (шаг 2.3 уже подключил `AnalysisService`; если тест AnalysisService зелёный сразу, красными остаются Jobs и API-тест; не подгонять тесты).

- [ ] **Step 3.3:** Довести подключения шага 2.3 (CLI, LocalApi, Jobs), если они ещё не сделаны. Run: `.\gradlew.bat test`, `npm --prefix ui run typecheck`, `npm --prefix ui run lint`, `npm --prefix ui run e2e`. Expected: PASS (включая `CommandLineTest`: добавить класс в команду шага 3.2).

- [ ] **Step 3.4:** Commit: `feat(policy): reject a second owner of SLA thresholds before analysis`.

### Task 4: Документация

- [ ] **Step 4.1:** `docs/user/slice-1-local-analysis.md`: раздел «Правила платформы»: форма `platform_rules`, области `service` и `all_services` с каталогом, смысл `gt|lt` как условия нарушения (в отличие от `lte|gte` у бизнес-правил), обязательное совпадение единицы и агрегации, `platform_coverage` и его граница (ядро не проверяет, что запрос профиля честно считает `desired - available`), единый владелец порога и миграция из снимка или профиля, предел 256 проверок, `RESOURCE_SNAPSHOT_REQUIRED`; таблица ошибок привязки с кодом выхода 4; допуск пропусков в этом срезе строгий (любой пропуск даёт `MISSING_RESOURCE_CELLS`). `CHANGELOG.md` (`Added`). Run markdownlint для двух файлов. Нормативный prompt получает разрешение на `platform_*` только если пользователь дал сервисы, сигналы и пороги, иначе модель их не составляет. Commit: `docs(policy): describe platform rules`.

**Риски S6:** (1) размер: единственный XL; (2) имена сигналов (`openshift_*`) рабочие и в ядре не зашиты: ядро сопоставляет только точную строку; (3) агрегация сверяется с рядом, но сама агрегация не проверяется ядром (граница П-1 ADR 0014); (4) окно короче серии у правил снимка и профиля по-прежнему может дать ложный `PASS` (REPORT-ONLY); (5) сброс счётчиков OOM/restart на стенде не проверен; (6) число правил 256 на оценку — предел ресурсных правил, а не бюджет evidence `E_max` ADR 0014 (он предварительный и не реализован).

**Documentation impact:** `docs/user/slice-1-local-analysis.md`, `CHANGELOG.md`, схема и примеры `policy.v1`.

## S7. Допуск пропусков, RESOURCE_GAPS, серия через пропуск

**Цель:** платформенные `sla`-правила переносят настраиваемый допуск пропусков (умолчание 5 % ячеек окна и разрыв до 3 ячеек): в допуске правило оценивается по наблюдённым ячейкам с информационной причиной `RESOURCE_GAPS`; за допуском остаётся `NO_VERDICT` с `MISSING_RESOURCE_CELLS`; серия, достигнутая только за счёт пропущенных ячеек, даёт `NO_VERDICT` с «предположительной» находкой.

**Ветка:** `feat/platform-gap-tolerance`. **Размер:** M. **Зависит от:** S6. **Identity:** `verdict_gates` получает `max_missing_fraction_default: "0.05"` и `max_gap_cells_default: "3"` только при непустом `platform_rules`; политики без платформенных правил и ключ сопоставимости не меняются.

**Что не входит:** допуск для правил снимка и профиля (остаются строгими; остаточный риск принят владельцем, Q9), исправление ложного `PASS` на коротком окне у них, калибровка 5 % и 3 на стенде.

**Контракты и версии:**

- `defaults.max_missing_fraction` (число, `0 <= x < 1`), `defaults.max_gap_cells` (целое `0..100000`); те же поля у `platform_rules[]` только при `effect=sla`. Новый код `TOLERANCE_OUT_OF_RANGE`; `FIELD_NOT_APPLICABLE` для поля допуска у `effect=diagnostic`. Приоритет: правило, затем `defaults`, затем константы `MAX_MISSING_FRACTION_DEFAULT = 0.05`, `MAX_GAP_CELLS_DEFAULT = 3`. Значения `0` и `0` дают строгое поведение S6.
- Правило допуска: окно оценивается по наблюдённым ячейкам, если `observed_cells > 0`, `missing / expected <= max_missing_fraction` (точное сравнение `BigDecimal`) и `longest_gap <= max_gap_cells`; иначе строгий путь (`MISSING_RESOURCE_CELLS`, найденные нарушения сохраняются).
- Серия через пропуск: нарушающие ячейки по обе стороны пропуска образуют цепочку-гипотезу, а каждый непрерывный отрезок наблюдённых нарушающих ячеек оценивается отдельно. `FAIL` выдаётся только если подряд идут `min_consecutive_cells` наблюдённых нарушающих ячеек; если серия достигает минимума лишь с пропущенными ячейками, правило получает `NO_VERDICT` (`MISSING_RESOURCE_CELLS`), находка сохраняется с полем `presumed: true`. Это единственное изменение вычислителя; оно действует только в режиме допуска.
- Evidence `resource_policy_check` платформенных правил: `expected_cells`, `observed_cells`, `missing_cells`, `longest_gap_cells`; доля покрытия вычисляется интерфейсом.
- `PASS` в допуске не доказывает отсутствия нарушения внутри допущенного пропуска (при шаге 15 с и трёх ячейках это до 45 с): принятый риск, он описывается в документации.

**Files:**

- Modify: `src/main/kotlin/io/ltverdict/core/Model.kt` (`PolicyDefaultsV1`: `maxMissingFraction`, `maxGapCells`; `PlatformRuleV1`: те же два поля)
- Modify: `src/main/kotlin/io/ltverdict/core/Policy.kt` (разбор `defaults` и платформенных правил)
- Modify: `src/main/kotlin/io/ltverdict/core/ResourceSnapshot.kt:69-77` (`ResourceRuleV1`: `maxMissingFraction`, `maxGapCells`)
- Modify: `src/main/kotlin/io/ltverdict/core/PlatformRules.kt` (разрешение допуска при разворачивании)
- Modify: `src/main/kotlin/io/ltverdict/core/ResourceStatistics.kt:251-320,322-371` (допуск, `thresholdFinding`, evidence)
- Modify: `src/main/kotlin/io/ltverdict/core/AnalysisResult.kt` (условные ключи `verdict_gates`)
- Modify: `docs/contracts/policy/v1/policy.schema.json`; Create: `examples/valid/platform-gap-tolerance.json`, `examples/invalid/platform-tolerance-diagnostic.json`; Modify: `fixtures/slice1/manifest.json`, `PolicyTest.kt`, `FixtureManifestTest.kt`
- Test: `PlatformRulesTest.kt`, `AnalysisResultGoldenTest.kt`
- Modify: `docs/user/slice-1-local-analysis.md`, `CHANGELOG.md`

**Interfaces:**

- Produces: `PolicyDefaultsV1(sampleFloor, minSamples, maxMissingFraction: BigDecimal? = null, maxGapCells: Int? = null)`; `PlatformRuleV1(..., windowIds, maxMissingFraction: BigDecimal? = null, maxGapCells: Int? = null)`; `ResourceRuleV1(..., platform, maxMissingFraction: BigDecimal? = null, maxGapCells: Int? = null)`; `internal val MAX_MISSING_FRACTION_DEFAULT = BigDecimal("0.05")`, `internal const val MAX_GAP_CELLS_DEFAULT = 3`.
- Consumes: S6 (`expandPlatformRules`, `evaluateResources`).

### Task 1: Разбор допуска

- [ ] **Step 1.1: Красные тесты** (в `PlatformRulesTest.kt`; в помощнике `policyJson` вынести блок `defaults` в параметр `defaults: String = """{"sample_floor":1,"min_samples":1}"""` и подставлять `"defaults":$defaults,` вместо жёсткой строки):

```kotlin
    @Test
    fun `tolerance fields parse and fail closed at their exact fields`() {
        val valid = policy(policyJson(defaults = """{"sample_floor":1,"min_samples":1,"max_missing_fraction":0.1,"max_gap_cells":5}""", cpuExtra = ""","max_gap_cells":0"""))
        assertEquals(BigDecimal("0.1"), valid.defaults?.maxMissingFraction)
        assertEquals(5, valid.defaults?.maxGapCells)
        assertEquals(0, valid.platformRules[0].maxGapCells)

        listOf(
            Triple(policyJson(defaults = """{"max_missing_fraction":1}"""), "TOLERANCE_OUT_OF_RANGE", "/defaults/max_missing_fraction"),
            Triple(policyJson(defaults = """{"max_missing_fraction":-0.1}"""), "TOLERANCE_OUT_OF_RANGE", "/defaults/max_missing_fraction"),
            Triple(policyJson(defaults = """{"max_gap_cells":-1}"""), "TOLERANCE_OUT_OF_RANGE", "/defaults/max_gap_cells"),
            Triple(policyJson(defaults = """{"max_gap_cells":1.5}"""), "INVALID_TYPE", "/defaults/max_gap_cells"),
            Triple(policyJson(cpuEffect = "diagnostic", cpuExtra = ""","max_gap_cells":2"""), "FIELD_NOT_APPLICABLE", "/platform_rules/0/max_gap_cells"),
        ).forEach { (source, code, pointer) ->
            val errors = (validatePolicy(ByteArrayInputStream(source.encodeToByteArray())) as PolicyValidation.Invalid).errors
            assertEquals(code to pointer, errors.first().code to errors.first().jsonPointer, source)
        }
    }
```

- [ ] **Step 1.2:** Run: `.\gradlew.bat test --tests "io.ltverdict.core.PlatformRulesTest"`. Expected: FAIL (`UNKNOWN_FIELD`).

- [ ] **Step 1.3: Реализация разбора.** `Model.kt`: добавить поля (значения по умолчанию `null`). `Policy.kt`: в `parseDefaults` разрешить `max_missing_fraction`, `max_gap_cells` и разобрать их функцией

```kotlin
private fun JsonObject.fractionAt(
    name: String,
    pointer: String,
): BigDecimal? {
    if (get(name) == null) return null
    val value = numberAt(name, pointer)
    if (value.signum() < 0 || value >= BigDecimal.ONE) fail("TOLERANCE_OUT_OF_RANGE", pointer.child(name), "$name must be at least 0 and below 1")
    return value
}
```

`max_gap_cells` читается `longInRangeAt("max_gap_cells", pointer, 100_000, "TOLERANCE_OUT_OF_RANGE", 0)`: в `longInRangeAt` добавить последним параметр `min: Long = 1` и заменить нижнюю границу `BigDecimal.ONE` в проверке диапазона на `BigDecimal.valueOf(min)`. В `parsePlatformRules`: ключи `max_missing_fraction`, `max_gap_cells` разрешены; если `effect` не `sla` и любое из них задано, `fail("FIELD_NOT_APPLICABLE", "$pointer/<поле>", ...)`. Схема: `defaults.max_missing_fraction` `{"type":"number","minimum":0,"exclusiveMaximum":1}`, `defaults.max_gap_cells` `{"type":"integer","minimum":0,"maximum":100000}`; `$defs.platform_rule.properties` получает оба свойства с теми же схемами (закрытая схема иначе отвергла бы допустимый для рантайма файл).

- [ ] **Step 1.4:** Run тот же тест, затем `.\gradlew.bat test --tests "io.ltverdict.core.PolicyTest"`. Expected: PASS. Примеры: `valid/platform-gap-tolerance.json` (копия `platform-services.json` с `defaults` из теста выше и `max_missing_fraction: 0.2` у правила `cpu-share`, `policy_id` `platform-gap-tolerance`), `invalid/platform-tolerance-diagnostic.json` (`effect: "diagnostic"` у `cpu-share` плюс `max_gap_cells: 2`; схема допускает, рантайм: `FIELD_NOT_APPLICABLE`, `/platform_rules/0/max_gap_cells`); добавить их в `PolicyTest.contractExamples`, `FixtureManifestTest.policyExamples`, манифест (`policy_examples`, `artifacts`). Commit: `feat(policy): parse gap tolerance for platform rules`.

### Task 2: Допуск в вычислителе

- [ ] **Step 2.1: Красные тесты** (в `PlatformRulesTest.kt`; параметр `cpuMin: Int = 2` добавить в `policyJson` вместо жёсткого `min_consecutive_cells":2` у `cpu`):

```kotlin
    @Test
    fun `a gap inside the tolerance is judged on the observed cells and says so`() {
        val cpu = List<String?>(20) { if (it == 7) null else "0.1" }

        val result = evaluate(snapshot(healthy("orders", cpu) + healthy("payments", List(20) { "0.1" }), cells = 20))
        val check = result.checks().single { it.str("rule_id") == "cpu/orders" }

        assertEquals("PASS", check.str("status"))
        assertEquals("RESOURCE_GAPS", check.str("reason"))
        assertEquals(listOf("20", "19", "1", "1"), listOf("expected_cells", "observed_cells", "missing_cells", "longest_gap_cells").map(check::str))
        assertEquals(PolicyVerdict.PASS, result.windowVerdicts.getValue("steady"))
        assertTrue("RESOURCE_GAPS" in result.coverageReasons)
    }

    @Test
    fun `the tolerance boundaries hold and anything beyond falls back to no verdict`() {
        fun verdict(cpu: List<String?>) =
            evaluate(snapshot(healthy("orders", cpu) + healthy("payments", List(cpu.size) { "0.1" }), cells = cpu.size)).windowVerdicts.getValue("steady")

        assertEquals(PolicyVerdict.PASS, verdict(List(100) { if (it in 10..12) null else "0.1" }))
        assertEquals(PolicyVerdict.PASS, verdict(List(100) { if (it % 20 == 3) null else "0.1" }))
        assertEquals(PolicyVerdict.NO_VERDICT, verdict(List(100) { if (it in 10..13) null else "0.1" }))
        assertEquals(PolicyVerdict.NO_VERDICT, verdict(List(50) { if (it in setOf(3, 20, 40)) null else "0.1" }))
        assertEquals(PolicyVerdict.NO_VERDICT, verdict(List(20) { null }))
    }

    @Test
    fun `zero tolerance restores the strict behaviour and an all-missing window never passes`() {
        val strict = policy(policyJson(cpuExtra = ""","max_missing_fraction":0,"max_gap_cells":0"""))
        val wide = policy(policyJson(defaults = """{"sample_floor":1,"min_samples":1,"max_missing_fraction":0.99,"max_gap_cells":100}"""))
        val oneGap = snapshot(healthy("orders", List(20) { if (it == 7) null else "0.1" }) + healthy("payments", List(20) { "0.1" }), cells = 20)
        val allGone = snapshot(healthy("orders", List(20) { null }) + healthy("payments", List(20) { "0.1" }), cells = 20)

        assertEquals("MISSING_RESOURCE_CELLS", evaluate(oneGap, strict).checks().single { it.str("rule_id") == "cpu/orders" }.str("reason"))
        assertEquals(PolicyVerdict.NO_VERDICT, evaluate(allGone, wide).windowVerdicts.getValue("steady"))
    }

    @Test
    fun `a series bridged by a missing cell is a presumption and never a proven failure`() {
        val tolerant = policyJson(cpuMin = 3, cpuExtra = ""","max_missing_fraction":0.2,"max_gap_cells":1""")

        fun withCpu(cpu: List<String?>) = evaluate(snapshot(healthy("orders", cpu) + healthy("payments", List(6) { "0.1" }), cells = 6), policy(tolerant))
        val presumed = withCpu(listOf("0.5", "0.5", null, "0.5", "0.1", "0.1"))
        val proven = withCpu(listOf("0.5", "0.5", "0.5", null, "0.1", "0.1"))
        val clean = withCpu(listOf("0.1", "0.1", null, "0.1", "0.1", "0.1"))
        val mixed = withCpu(listOf("0.5", "0.5", "0.5", null, "0.5", "0.1"))

        assertEquals("NO_VERDICT", presumed.checks().single { it.str("rule_id") == "cpu/orders" }.str("status"))
        assertEquals("MISSING_RESOURCE_CELLS", presumed.checks().single { it.str("rule_id") == "cpu/orders" }.str("reason"))
        assertEquals(1, presumed.findings.size)
        assertEquals("true", presumed.findings.single().str("presumed"))
        assertEquals(PolicyVerdict.FAIL, proven.windowVerdicts.getValue("steady"))
        assertEquals(null, proven.findings.single()["presumed"])
        assertEquals(PolicyVerdict.PASS, clean.windowVerdicts.getValue("steady"))
        assertEquals(PolicyVerdict.FAIL, mixed.windowVerdicts.getValue("steady"))
        assertEquals("3", mixed.findings.single().str("cell_count"))
        assertEquals(null, mixed.findings.single()["presumed"])
    }
```

В `AnalysisResultGoldenTest.kt`:

```kotlin
    @Test
    fun `tolerance defaults are recorded only for policies with platform rules`() {
        val inputHash = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        val input =
            AcceptedInput(
                runId = "jmeter_jtl_csv-$inputHash",
                sourceType = SourceType.JMETER_CSV,
                sha256 = inputHash,
                sizeBytes = 1,
                originalFilename = "input.jtl",
                path = Path.of("unused"),
            )

        fun gates(path: String): Map<String, String> {
            val policy = validatePolicy(ByteArrayInputStream(Files.readAllBytes(Path.of(path)))) as PolicyValidation.Valid
            return Json
                .parseToJsonElement(analysisIdentity(input, policy, EngineConfig()).decodeToString())
                .jsonObject
                .getValue("verdict_gates")
                .jsonObject
                .mapValues { it.value.jsonPrimitive.content }
        }

        assertEquals(null, gates("fixtures/slice1/identity/policy.canonical.json")["max_missing_fraction_default"])
        assertEquals(
            mapOf("max_missing_fraction_default" to "0.05", "max_gap_cells_default" to "3"),
            gates("docs/contracts/policy/v1/examples/valid/platform-services.json").filterKeys { it.startsWith("max_") },
        )
    }
```

- [ ] **Step 2.2:** Run: `.\gradlew.bat test --tests "io.ltverdict.core.PlatformRulesTest" --tests "io.ltverdict.core.AnalysisResultGoldenTest"`. Expected: FAIL.

- [ ] **Step 2.3: Реализация.**

`PlatformRules.kt`, `expandPlatformRules`: для правил `effect = SLA` разрешить допуск и передать в `ResourceRuleV1`:

```kotlin
            val sla = rule.effect == ResourceRuleEffect.SLA
            val fraction = if (sla) rule.maxMissingFraction ?: policy.defaults?.maxMissingFraction ?: MAX_MISSING_FRACTION_DEFAULT else null
            val gap = if (sla) rule.maxGapCells ?: policy.defaults?.maxGapCells ?: MAX_GAP_CELLS_DEFAULT else null
```

(последние два параметра конструктора `ResourceRuleV1` — `fraction`, `gap`). Константы объявить рядом с `MIN_SAMPLES_*` в `Policy.kt`: `internal val MAX_MISSING_FRACTION_DEFAULT = BigDecimal("0.05")`, `internal const val MAX_GAP_CELLS_DEFAULT = 3`.

`ResourceStatistics.kt`. В `evaluateRule` сразу после проверки `series == null`:

```kotlin
    val fraction = rule.maxMissingFraction
    if (fraction != null) {
        val stats = cellStats(snapshot, window, series)
        if (stats.missing > 0 &&
            stats.observed > 0 &&
            BigDecimal(stats.missing) <= fraction.multiply(BigDecimal(stats.expected)) &&
            stats.longestGap <= checkNotNull(rule.maxGapCells)
        ) {
            return evaluateBridged(snapshot, window, rule, series, checkId, findingsLimit, checkCancelled)
        }
    }
```

Новые функции:

```kotlin
private data class CellStats(
    val expected: Int,
    val observed: Int,
    val longestGap: Int,
) {
    val missing: Int get() = expected - observed
}

private fun cellStats(
    snapshot: ResourceSnapshotV1,
    window: ResourceWindowV1,
    series: ResourceSeriesV1,
): CellStats {
    val from = snapshot.cellIndex(window.fromEpochMillis)
    val to = snapshot.cellIndex(window.toEpochMillis)
    var observed = 0
    var gap = 0
    var longest = 0
    for (index in from until to) {
        if (series.values[index] == null) {
            gap++
            longest = maxOf(longest, gap)
        } else {
            gap = 0
            observed++
        }
    }
    return CellStats(to - from, observed, longest)
}

private class Segment(
    val start: Int,
    var end: Int,
    var min: BigDecimal,
    var max: BigDecimal,
)

private fun evaluateBridged(
    snapshot: ResourceSnapshotV1,
    window: ResourceWindowV1,
    rule: ResourceRuleV1,
    series: ResourceSeriesV1,
    checkId: String,
    findingsLimit: Int,
    checkCancelled: () -> Unit,
): RuleOutcome {
    val findings = mutableListOf<JsonObject>()
    var presumed = false
    val chain = mutableListOf<Segment>()
    val minimum = rule.minConsecutiveCells

    fun flush() {
        if (chain.isEmpty()) return
        val proven = chain.filter { it.end + 1 - it.start >= minimum }
        if (proven.isNotEmpty()) {
            proven.forEach { segment ->
                require(findings.size < findingsLimit) { "RESOURCE_FINDINGS_LIMIT_EXCEEDED" }
                findings += thresholdFinding(snapshot, window, series, rule, checkId, segment.start, segment.end + 1, segment.min, segment.max)
            }
        } else if (chain.last().end + 1 - chain.first().start >= minimum) {
            require(findings.size < findingsLimit) { "RESOURCE_FINDINGS_LIMIT_EXCEEDED" }
            presumed = true
            findings +=
                thresholdFinding(
                    snapshot,
                    window,
                    series,
                    rule,
                    checkId,
                    chain.first().start,
                    chain.last().end + 1,
                    chain.minOf { it.min },
                    chain.maxOf { it.max },
                    true,
                )
        }
        chain.clear()
    }

    for (index in snapshot.cellIndex(window.fromEpochMillis) until snapshot.cellIndex(window.toEpochMillis)) {
        checkCancelled()
        val value = series.values[index] ?: continue
        val violates =
            when (rule.operator) {
                ResourceOperator.GT -> value > rule.threshold
                ResourceOperator.LT -> value < rule.threshold
            }
        if (!violates) {
            flush()
            continue
        }
        val last = chain.lastOrNull()
        if (last != null && last.end + 1 == index) {
            last.end = index
            last.min = minOf(last.min, value)
            last.max = maxOf(last.max, value)
        } else {
            chain += Segment(index, index, value, value)
        }
    }
    flush()
    return when {
        presumed -> RuleOutcome("NO_VERDICT", "MISSING_RESOURCE_CELLS", findings)
        findings.isNotEmpty() -> RuleOutcome("FAIL", "RESOURCE_GAPS", findings)
        else -> RuleOutcome("PASS", "RESOURCE_GAPS", findings)
    }
}
```

`thresholdFinding` получает последний параметр `presumed: Boolean = false` и пишет `if (presumed) put("presumed", true)`; существующий вызов остаётся без аргумента. В `evaluateResources` для правил `rule.platform != null` с найденным рядом после вычисления `outcome` вызвать `cellStats` и передать счётчики в `resourceCheck` (новый необязательный параметр `cells: CellStats?`):

```kotlin
        cells?.let {
            put("expected_cells", it.expected)
            put("observed_cells", it.observed)
            put("missing_cells", it.missing)
            put("longest_gap_cells", it.longestGap)
        }
```

`AnalysisResult.kt`, в `verdictGates(hasPolicy, hasCapacity)` (S3) добавить параметр `hasPlatform: Boolean` (`policy?.policy?.platformRules?.isNotEmpty() == true`) и

```kotlin
    if (hasPlatform) {
        put("max_missing_fraction_default", MAX_MISSING_FRACTION_DEFAULT.toPlainString())
        put("max_gap_cells_default", MAX_GAP_CELLS_DEFAULT.toString())
    }
```

Условие записи блока: `policy != null || capacity != null` (прежнее) — платформа без политики невозможна.

- [ ] **Step 2.4:** Run: `.\gradlew.bat test` и `npm --prefix ui run typecheck`. Expected: PASS. Перепроверить, что строгие тесты `ResourceStatisticsTest` и `WindowPolicyEvaluationTest` (правила снимка) не изменились: допуск включается только при непустом `maxMissingFraction`, которое выставляют лишь платформенные `sla`-правила.

- [ ] **Step 2.5:** Commit: `feat(policy): tolerate bounded gaps in platform rules`.

### Task 3: Документация

- [ ] **Step 3.1:** `docs/user/slice-1-local-analysis.md`: раздел «Допуск пропусков» (поля, умолчания, `RESOURCE_GAPS` с долей покрытия, `INCOMPLETE` и исключение из кандидатов statistical baseline, граница `PASS` при допуске, «предположительная» находка, правила снимка остаются строгими); `CHANGELOG.md` (`Added`). Run markdownlint для двух файлов. Commit: `docs(policy): describe gap tolerance`.

**Риски S7:** (1) допуск 5 % и 3 ячейки — допущения владельца, на стенде не измерены; (2) выбор между «пропуск разрывает серию» (S6) и «гипотеза серии» (S7) меняет исход одной и той же политики: переключатель — `max_missing_fraction = 0` и `max_gap_cells = 0`; (3) `RESOURCE_GAPS` делает анализ `INCOMPLETE`, поэтому такой анализ не станет кандидатом statistical baseline.

**Documentation impact:** `docs/user/slice-1-local-analysis.md`, `CHANGELOG.md`, схема и примеры `policy.v1`.

## S8. UI: платформенные правила, покрытие, причины

**Цель:** карточка вердикта и обзор называют сервис, окно и долю покрытия для платформенных проверок, показывают «предположительное» нарушение и ведут к нужной таблице для новых причин.

**Ветка:** `feat/ui-platform-rule-lines`. **Размер:** M. **Зависит от:** S2, S7. **Identity и контракты:** не меняются.

**Что не входит:** группировка по плечам (в `src/main` нет `arm`; анализ одного плеча отображается как есть, число плеч значения не имеет), редактор политики (U4), изменение таблиц ресурсов.

**Files:**

- Modify: `ui/src/types.ts` (`ResourcePolicyCheckEvidence`: `platform_rule_id?`, `service?`, `expected_cells?`, `observed_cells?`, `missing_cells?`, `longest_gap_cells?`; новый `RuleWindowCheckEvidence`; в `AnalysisEvidence`)
- Modify: `ui/src/verdictSummary.ts` (`isViolation`/`Violation`, `resourceLine`, `causesOf`)
- Modify: `ui/src/shell/overview.ts:76-87` (цели переходов)
- Test: `ui/e2e/verdict-summary.spec.ts`, `ui/e2e/overview-adapters.spec.ts`
- Modify: `docs/user/slice-1-local-analysis.md`, `CHANGELOG.md`

**Interfaces:** consumes поля S6 и S7; тексты строк (ниже) — публичный контракт интерфейса.

### Task 1: Строки платформенных правил

- [ ] **Step 1.1: Красные тесты** (`verdict-summary.spec.ts`):

```ts
  test('a platform check names its service and shows coverage under a tolerated gap', () => {
    const summary = summarizeVerdict(build({
      policy_verdict: 'PASS',
      analysis_coverage: { status: 'INCOMPLETE', reasons: ['RESOURCE_GAPS'] },
      evidence: [overall, { id: 'r1', type: 'resource_policy_check', window_id: 'steady-1', rule_id: 'cpu-share/orders', series_id: 'cpu-orders', unit: 'ratio', operator: 'gt', threshold: '0.4', effect: 'sla', status: 'PASS', reason: 'RESOURCE_GAPS', platform_rule_id: 'cpu-share', service: 'orders', expected_cells: 20, observed_cells: 19, missing_cells: 1, longest_gap_cells: 1 }],
    }))

    expect(summary.lines.map((line) => flat(`${line.title}: ${line.detail}`))).toEqual([
      'Правило cpu-share/orders · сервис orders · ряд cpu-orders · окно steady-1: нарушений нет (нарушение: значение выше 0.4 ratio) · покрытие данных: 95 % (19 из 20 ячеек)',
    ])
  })

  test('a presumed violation is shown as presumed next to the missing cells', () => {
    const summary = summarizeVerdict(build({
      policy_verdict: 'NO_VERDICT',
      analysis_coverage: { status: 'INCOMPLETE', reasons: ['MISSING_RESOURCE_CELLS', 'RESOURCE_GAPS'] },
      evidence: [{ id: 'r1', type: 'resource_policy_check', window_id: 'w', rule_id: 'cpu-share/orders', series_id: 'cpu-orders', unit: 'ratio', operator: 'gt', threshold: '0.4', effect: 'sla', status: 'NO_VERDICT', reason: 'MISSING_RESOURCE_CELLS', platform_rule_id: 'cpu-share', service: 'orders', expected_cells: 20, observed_cells: 19, missing_cells: 1, longest_gap_cells: 1 }],
      findings: [{ id: 'f1', type: 'resource_threshold_violation', window_id: 'w', rule_id: 'cpu-share/orders', series_id: 'cpu-orders', entity: 'orders', unit: 'ratio', from_epoch_ms: 1000, to_epoch_ms: 5000, cell_count: 4, observed_min: '0.5', observed_max: '0.5', evidence_id: 'r1' }, { id: 'f2', type: 'resource_threshold_violation', window_id: 'w', rule_id: 'cpu-share/orders', series_id: 'cpu-orders', entity: 'orders', unit: 'ratio', from_epoch_ms: 9000, to_epoch_ms: 12000, cell_count: 3, observed_min: '0.6', observed_max: '0.6', evidence_id: 'r1', presumed: true }],
    }))

    expect(flat(summary.lines[0].detail)).toContain('предположительное нарушение')
    expect(flat(summary.lines[0].detail)).toContain('покрытие данных: 95 % (19 из 20 ячеек)')
    expect(summary.causes.map((cause) => cause.code)).toEqual(['MISSING_RESOURCE_CELLS'])
  })

  test('an unknown window id of a rule is explained by the rule and the id', () => {
    const summary = summarizeVerdict(build({
      policy_verdict: 'NO_VERDICT',
      analysis_coverage: { status: 'INCOMPLETE', reasons: ['RULE_WINDOW_NOT_FOUND'] },
      evidence: [overall, { id: 'u1', type: 'rule_window_check', rule_id: 'cpu', window_id: 'ghost', status: 'NO_VERDICT', reason_code: 'RULE_WINDOW_NOT_FOUND' }],
    }))

    expect(summary.causes.map((cause) => cause.code)).toEqual(['RULE_WINDOW_NOT_FOUND'])
    expect(summary.causes[0].subjects).toEqual(['cpu (окно ghost)'])
  })
```

`overview-adapters.spec.ts`:

```ts
test('platform reasons lead to the resource and policy tables', () => {
  const result = build({
    policy_verdict: 'NO_VERDICT',
    analysis_coverage: { status: 'INCOMPLETE', reasons: ['PLATFORM_SERIES_AMBIGUOUS', 'RULE_WINDOW_NOT_FOUND', 'RESOURCE_SNAPSHOT_REQUIRED'] },
    evidence: [overall],
  })

  expect(attentionItems(result).map((entry) => [entry.key, entry.target?.targetId])).toEqual([
    ['no_verdict:PLATFORM_SERIES_AMBIGUOUS|', 'resource-results'],
    ['no_verdict:RULE_WINDOW_NOT_FOUND|', 'policy-results'],
    ['no_verdict:RESOURCE_SNAPSHOT_REQUIRED|', 'resource-results'],
  ])
})
```

- [ ] **Step 1.2:** Run: `Push-Location ui; npx playwright test --config e2e/verdict-ui.config.ts; npx playwright test e2e/overview-adapters.spec.ts; Pop-Location`. Expected: FAIL.

- [ ] **Step 1.3: Реализация.** `verdictSummary.ts`: в `Violation` добавить `presumed?: boolean`; в `resourceLine` в заголовок после `Правило <rule_id>` добавить фрагмент «· сервис <service>» при наличии `check.service`; для любого статуса при наличии `expected_cells` добавить

```ts
const coverage = check.expected_cells && check.observed_cells !== undefined && check.observed_cells < check.expected_cells
  ? ` · покрытие данных: ${numbers.format((check.observed_cells / check.expected_cells) * 100)} % (${check.observed_cells} из ${check.expected_cells} ячеек)`
  : ''
```

а к описанию нарушения, если среди нарушений этого правила и окна есть хотя бы одно с `presumed` (`violations.some((item) => item.presumed)`), дописать «· предположительное нарушение (серия достигнута только за счёт пропущенных ячеек)». В `causesOf` добавить обработку `rule_window_check` (код из `reason_code`, подпись `${rule_id} (окно ${window_id})`, метка `Правила`). `types.ts`: `RuleWindowCheckEvidence { id: string; type: 'rule_window_check'; rule_id: string; window_id: string; status: 'NO_VERDICT'; reason_code: string }`. `overview.ts`: в `noVerdictTarget` коды `PLATFORM_SERIES_AMBIGUOUS`, `PLATFORM_UNIT_MISMATCH`, `PLATFORM_AGGREGATION_MISMATCH`, `RULE_WINDOW_TOO_SHORT`, `RESOURCE_SNAPSHOT_REQUIRED` ведут на `resource-results`, `RULE_WINDOW_NOT_FOUND` — на `policy-results`.

- [ ] **Step 1.4:** Run: те же прогоны, `npm --prefix ui run typecheck`, `npm --prefix ui run lint`. Expected: PASS. Commit: `feat(ui): explain platform rule results and coverage`.

- [ ] **Step 1.5: Документация.** `docs/user/slice-1-local-analysis.md` (раздел карточки вердикта: сервис, покрытие, предположительное нарушение); `CHANGELOG.md` (`Added`). Run markdownlint. Commit: `docs(ui): describe platform rule lines`.

**Риски S8:** `check.service` и счётчики есть только у платформенных проверок: строки правил снимка не меняются; длинный список из 20 сервисов сжимается существующим `MAX_LINES = 3` и `MAX_SUBJECTS = 5`.

**Documentation impact:** `docs/user/slice-1-local-analysis.md`, `CHANGELOG.md`.

## S9. Базовый профиль SLA платформы и независимые плечи

**Цель:** поставить предустановку политики с базовыми SLA владельца (по контейнеру: CPU не выше 40 % от лимита, память 80 % от лимита, OOM и рестарты 0, throttling diagnostic, покрытие по доступности) и закрепить тестом, что одна политика даёт независимый результат каждому плечу при любом числе плеч. Плечо в ядре — это отдельный снимок и отдельный анализ; поле `arm` и `resource_arm` в identity вводит срез ADR 0014, здесь они не проверяются (тест доказывает независимость анализов и различие по хэшу снимка).

**Ветка:** `feat/platform-base-profile`. **Размер:** S–M. **Зависит от:** S6 и S7 (а через S6 и от среза `interval_max` ADR 0014: он нужен памяти и покрытию профиля). **Identity и контракты:** не меняются (пример и документация).

**Что не входит:** `arm`/`resource_arm` (срез ADR 0014), группировка по плечам в интерфейсе, общий заголовок «все плечи» (решение владельца Q10), артефакт pod-view с контейнерными строками (ADR 0020), запросы профиля источника для OpenShift-сигналов (P0).

**Контракты и версии:** `docs/contracts/policy/v1/examples/valid/platform-base-profile.json` — валидная политика `policy.v1` (в ней хотя бы одно бизнес-правило, решение Q13; каталог `platform_services` — подменяемые имена; `defaults` с 20/100, `max_missing_fraction` 0.05 и `max_gap_cells` 3 записаны явно). Рабочие имена сигналов — из ADR 0018, раздел 3. `min_consecutive_cells` — параметр шаблона (владелец его не задавал): 3 для CPU и памяти, 1 для OOM, рестартов и покрытия. Порог throttling `0.25` — заменяемый placeholder (владелец порог не задавал).

**Files:**

- Create: `docs/contracts/policy/v1/examples/valid/platform-base-profile.json`
- Modify: `fixtures/slice1/manifest.json` (`policy_examples`, `artifacts`), `PolicyTest.kt` (`contractExamples`), `FixtureManifestTest.kt` (`policyExamples`), `docs/contracts/policy/v1/policy.schema.json` (`aggregation` включает `interval_max`, если срез ADR 0014 его добавил)
- Test: `src/test/kotlin/io/ltverdict/core/AnalysisServiceTest.kt`
- Modify: `docs/user/slice-1-local-analysis.md`, `CHANGELOG.md`

### Task 1: Предустановка и независимые плечи

- [ ] **Step 1.1: Предустановка.** Создать `platform-base-profile.json` (фрагмент; остальные правила повторяют структуру `cpu-limit` с указанными параметрами):

```json
{
  "schema_version": "policy.v1",
  "policy_id": "platform-base-profile",
  "defaults": {
    "sample_floor": 20,
    "min_samples": 100,
    "max_missing_fraction": 0.05,
    "max_gap_cells": 3
  },
  "rules": [
    {
      "id": "overall-errors",
      "metric": "error_rate_ratio",
      "operator": "lte",
      "threshold": 0.01,
      "scope": { "kind": "overall" }
    }
  ],
  "platform_services": ["orders", "payments"],
  "platform_coverage": { "signal": "openshift_unavailable_replicas" },
  "platform_rules": [
    { "id": "cpu-limit", "signal": "openshift_container_cpu_limit_ratio", "scope": { "kind": "all_services" }, "operator": "gt", "threshold": 0.4, "unit": "ratio", "aggregation": "interval_mean", "min_consecutive_cells": 3, "effect": "sla" },
    { "id": "memory-limit", "signal": "openshift_container_memory_limit_ratio", "scope": { "kind": "all_services" }, "operator": "gt", "threshold": 0.8, "unit": "ratio", "aggregation": "interval_max", "min_consecutive_cells": 3, "effect": "sla" },
    { "id": "oom", "signal": "openshift_oom", "scope": { "kind": "all_services" }, "operator": "gt", "threshold": 0, "unit": "events/s", "aggregation": "interval_rate", "min_consecutive_cells": 1, "effect": "sla" },
    { "id": "restarts", "signal": "openshift_restarts", "scope": { "kind": "all_services" }, "operator": "gt", "threshold": 0, "unit": "events/s", "aggregation": "interval_rate", "min_consecutive_cells": 1, "effect": "sla" },
    { "id": "cpu-throttling", "signal": "openshift_cpu_throttling", "scope": { "kind": "all_services" }, "operator": "gt", "threshold": 0.25, "unit": "ratio", "aggregation": "interval_mean", "min_consecutive_cells": 3, "effect": "diagnostic" },
    { "id": "replicas", "signal": "openshift_unavailable_replicas", "scope": { "kind": "all_services" }, "operator": "gt", "threshold": 0, "unit": "count", "aggregation": "interval_max", "min_consecutive_cells": 1, "effect": "sla" }
  ]
}
```

Добавить файл в `PolicyTest.contractExamples` (`Expectation(true, true)`), `FixtureManifestTest.policyExamples`, манифест.

- [ ] **Step 1.2: Красный тест плеч** (`AnalysisServiceTest.kt`; `trendCsv()` — 40 запросов, по одному в секунду; снимок шагом 1 с и 10 ячейками помещается в прогон):

```kotlin
    @Test
    fun `one base profile policy gives every arm its own independent result`() =
        withService { store, service ->
            val input = accept(store, trendCsv().encodeToByteArray(), "arms.jtl")
            val base = policy(Files.readString(Path.of("docs/contracts/policy/v1/examples/valid/platform-base-profile.json")))

            fun analyze(
                cpuOrders: String,
                services: List<String> = listOf("orders", "payments"),
                using: PolicyValidation.Valid = base,
            ) = service.analyze(AnalysisRequest(input, using, resources = resources(platformSnapshotJson(input.sha256, cpuOrders, services).encodeToByteArray())))

            val first = analyze("0.5")
            val second = analyze("0.1")
            val third = analyze("0.1", services = listOf("orders"))
            val relaxed =
                analyze(
                    "0.5",
                    using = policy(Files.readString(Path.of("docs/contracts/policy/v1/examples/valid/platform-base-profile.json")).replace("\"threshold\": 0.4", "\"threshold\": 0.6")),
                )

            assertEquals(listOf("FAIL", "PASS", "NO_VERDICT", "PASS"), listOf(first, second, third, relaxed).map { result(it, "policy_verdict") })
            assertEquals(3, listOf(first, second, third).map { it.analysisId }.toSet().size)
            val policyHashes =
                listOf(first, second, third).map {
                    Json.parseToJsonElement(Files.readString(it.analysisDirectory.resolve("identity.json"))).jsonObject.getValue("policy_sha256")
                }
            assertEquals(1, policyHashes.toSet().size)
        }

    private fun platformSnapshotJson(
        loadHash: String,
        cpuOrders: String,
        services: List<String>,
    ): String {
        fun series(
            id: String,
            metric: String,
            entity: String,
            unit: String,
            aggregation: String,
            value: String,
        ) = """{"id":"$id","metric":"$metric","unit":"$unit","entity":"$entity","role":"system","aggregation":"$aggregation",""" +
            """"values":[${List(30) { value }.joinToString(",")}]}"""
        val all =
            services.flatMap { service ->
                listOf(
                    series("cpu-$service", "openshift_container_cpu_limit_ratio", service, "ratio", "interval_mean", if (service == "orders") cpuOrders else "0.1"),
                    series("memory-$service", "openshift_container_memory_limit_ratio", service, "ratio", "interval_max", "0.3"),
                    series("oom-$service", "openshift_oom", service, "events/s", "interval_rate", "0"),
                    series("restarts-$service", "openshift_restarts", service, "events/s", "interval_rate", "0"),
                    series("throttling-$service", "openshift_cpu_throttling", service, "ratio", "interval_mean", "0"),
                    series("replicas-$service", "openshift_unavailable_replicas", service, "count", "interval_max", "0"),
                )
            }
        return """{"schema_version":"resource-snapshot.v1","load_input_sha256":"$loadHash","start_epoch_ms":1767225600000,"step_ms":1000,""" +
            """"point_count":30,"series":[${all.joinToString(",")}],"windows":[{"id":"steady","from_epoch_ms":1767225600000,"to_epoch_ms":1767225630000}],""" +
            """"provenance":{"source_kind":"fixture","query_semantics":"interval","clock_alignment":"arm"}}"""
    }
```

Результат: три плеча (разные снимки одной политики) дают `FAIL`, `PASS`, `NO_VERDICT` (у третьего нет сервиса `payments`), три разных `analysis_id`, один `policy_sha256`; общего вердикта нет; изменение порога в политике (`0.4` на `0.6`) меняет исход без правки снимка.

- [ ] **Step 1.3:** Run: `.\gradlew.bat test --tests "io.ltverdict.core.AnalysisServiceTest" --tests "io.ltverdict.core.PolicyTest" --tests "io.ltverdict.fixtures.FixtureManifestTest"` и `npm --prefix ui run test:contracts`. Expected: PASS после появления `interval_max` в ядре (до этого тест красный на разборе снимка: это и есть блокировка среза). Commit: `feat(policy): ship the base platform SLA profile example`.

- [ ] **Step 1.4: Документация.** `docs/user/slice-1-local-analysis.md`: раздел «Базовый профиль SLA платформы»: таблица правил (как в ADR 0018, раздел 3), как скопировать пример, подставить `platform_services` и `except`, что единица профиля — контейнер (ряд сервиса несёт максимум по контейнерам и подам), что CPU при `interval_mean` — среднее за интервал, а не пик, что throttling остаётся diagnostic с порогом-заглушкой, что границы единицы и охвата подлежат пересмотру после прогонов, и как анализировать N плеч: один файл политики, отдельный снимок и анализ на каждое плечо, независимые вердикты; `CHANGELOG.md` (`Added`). Run markdownlint. Commit: `docs(policy): document the base platform SLA profile`.

**Риски S9:** (1) зависимость от `interval_max`; (2) сигналы и их запросы не определены профилем источника (P0): имена рабочие, ядро их смысла не знает; (3) единица (контейнер или под) и охват (все сервисы или выбранные) подлежат пересмотру на стенде; (4) сброс счётчиков OOM/restart и потеря первого приращения не проверены.

**Documentation impact:** `docs/user/slice-1-local-analysis.md`, `CHANGELOG.md`, пример `policy.v1`.

## S10. Отбор кандидатов statistical baseline по verdict_gates

**Цель:** statistical baseline принимает только анализы с блоком `verdict_gates` в identity; анализ без блока (создан до S1 или без политики) считается «режим выборки неизвестен» и отклоняется до повторного анализа. Кроме того, `PASS` с `INCOMPLETE` только из-за причины `SMALL_SAMPLE` перестаёт отклоняться (решение владельца 2026-10-04, п. 3).

**Ветка:** `feat/baseline-candidates-need-gates`. **Размер:** S для кода, M для правки тестов. **Зависит от:** S1; согласовать с ADR 0019 D5a (оба меняют отбор кандидатов и одни и те же тесты); ответы владельца 2026-10-04 на пункты 3 и 7 получены. **Identity и контракты:** identity не меняется; ключ сопоставимости не меняется. Публичное изменение: новая причина 422 `BASELINE_CANDIDATE_GATES_UNKNOWN` в ответе `POST /api/baseline` (режим `statistical`); ручной режим (`manual`) не меняется.

**Что не входит:** правило «baseline только из PASS», предупреждение `BASELINE_SMALL_SAMPLE`, список релизов и кандидатов (ADR 0019); но допуск `SMALL_SAMPLE` (шаг 1.3) и предупреждение `BASELINE_SMALL_SAMPLE` выпускаются вместе в одном релизе с D5a, допуск без предупреждения не включается; `SMALL_SAMPLE` не исключает кандидата (решение владельца 2026-10-04, п. 3): условие `analysis_coverage = COMPLETE` (`BaselineComparison.kt:101-104`) правится так, чтобы `INCOMPLETE` только из-за причины `SMALL_SAMPLE` не отказывал (вместе с D5a ADR 0019).

**Files:**

- Modify: `src/main/kotlin/io/ltverdict/core/BaselineComparison.kt:88-141` (проверка в `statisticalBaselineSelection`)
- Test: `src/test/kotlin/io/ltverdict/core/BaselineComparisonTest.kt` (помощник `identity()` и новый тест), `src/test/kotlin/io/ltverdict/web/LocalApiTest.kt` (`statisticalRuns`), `ui/e2e/baseline.spec.ts` (вызовы `analyze` в сценариях со статистическим выбором)
- Modify: `docs/user/slice-1-local-analysis.md` (раздел Baseline), `CHANGELOG.md`

**Interfaces:** consumes `verdict_gates` из identity (`JsonObject`); `statisticalBaselineSelection(series, references, results, identities)` сохраняет сигнатуру; ошибка — `IllegalArgumentException("BASELINE_CANDIDATE_GATES_UNKNOWN")`, которую `LocalApi.kt:1049-1050` уже превращает в 422.

### Task 1: Фильтр кандидатов

- [ ] **Step 1.1: Красный тест** (`BaselineComparisonTest.kt`; помощник `identity` получает параметр `gates: Boolean = true` и по умолчанию добавляет `put("verdict_gates", buildJsonObject { put("min_samples_floor", "20") })`):

```kotlin
    @Test
    fun `statistical selection rejects a candidate analysed before verdict gates existed`() {
        val valid = listOf(candidate('a'), candidate('b'), candidate('c'))
        val unknown = valid.toMutableList().also { it[1] = candidate('b', identity = identity(gates = false)) }

        val failure =
            assertThrows(IllegalArgumentException::class.java) {
                statisticalBaselineSelection("gates", unknown.references(), unknown.results(), unknown.identities())
            }

        assertEquals("BASELINE_CANDIDATE_GATES_UNKNOWN", failure.message)
        val capacityOnly = valid.toMutableList().also { it[1] = candidate('b', identity = identity(policy = false)) }
        assertEquals(
            "BASELINE_CANDIDATE_GATES_UNKNOWN",
            assertThrows(IllegalArgumentException::class.java) {
                statisticalBaselineSelection("gates", capacityOnly.references(), capacityOnly.results(), capacityOnly.identities())
            }.message,
        )
        assertEquals(
            valid.references().map { it.getValue("run_id") }.toSet().size,
            statisticalBaselineSelection("gates", valid.references(), valid.results(), valid.identities()).getValue("candidates").jsonArray.size,
        )
    }
```

Правка помощника:

```kotlin
    private fun identity(
        version: String = "same",
        gates: Boolean = true,
        policy: Boolean = true,
    ) = buildJsonObject {
        put("source_type", "jmeter_jtl_csv")
        put("engine", buildJsonObject { put("version", version) })
        put("parsers", JsonArray(emptyList()))
        put("modules", JsonArray(emptyList()))
        put("input_versions", buildJsonObject {})
        put("outputs", buildJsonObject {})
        put("histogram", buildJsonObject {})
        put("normalization", buildJsonObject {})
        put("limits", buildJsonObject {})
        put("policy_sha256", if (policy) "a".repeat(64) else "NO_POLICY")
        if (gates) put("verdict_gates", buildJsonObject { put("min_samples_floor", "20") })
    }
```

- [ ] **Step 1.1a: Красный тест допуска `SMALL_SAMPLE`** (`BaselineComparisonTest.kt`, по образцу теста выше; помощник `candidate` получает параметр `coverageReasons: List<String> = emptyList()`, статус покрытия `INCOMPLETE`, если список непуст): три кандидата, у одного `analysis_coverage = INCOMPLETE` с причинами `["SMALL_SAMPLE"]` — выбор проходит; у одного причины `["SMALL_SAMPLE", "RESOURCE_GAPS"]` или любая иная причина без `SMALL_SAMPLE` — `BASELINE_CANDIDATE_INCOMPLETE`; `INSUFFICIENT_SAMPLES` (`NO_VERDICT`) отклоняется прежним образом.

- [ ] **Step 1.2:** Run: `.\gradlew.bat test --tests "io.ltverdict.core.BaselineComparisonTest"`. Expected: FAIL (отбор принимает кандидата без `verdict_gates`; отклоняет кандидата с единственной причиной `SMALL_SAMPLE`).

- [ ] **Step 1.3: Реализация.** В `statisticalBaselineSelection`, внутри `references.indices.map { index -> ... }` проверку покрытия (`:102-104`, сейчас строго `status == "COMPLETE"`) заменить допуском `INCOMPLETE` только из-за `SMALL_SAMPLE` (решение владельца 2026-10-04, п. 3; импорты `jsonArray`/`jsonPrimitive` сверить при реализации):

```kotlin
            val coverage = result.objectOrNull("analysis_coverage")
            val reasons = coverage?.get("reasons")?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
            require(
                coverage?.stringOrNull("status") == "COMPLETE" ||
                    (coverage?.stringOrNull("status") == "INCOMPLETE" && reasons.isNotEmpty() && reasons.all { it == "SMALL_SAMPLE" }),
            ) { "BASELINE_CANDIDATE_INCOMPLETE" }
```

Затем сразу после неё:

```kotlin
            require(
                identities[index]["verdict_gates"] is JsonObject &&
                    identities[index].stringOrNull("policy_sha256")?.let { it != "NO_POLICY" } == true,
            ) { "BASELINE_CANDIDATE_GATES_UNKNOWN" }
```

- [ ] **Step 1.4: Правка сквозных тестов.** `LocalApiTest.statisticalRuns` создаёт анализы без политики; заменить на анализы с политикой, допускающей одиночные запросы:

```kotlin
    private val statisticalPolicy =
        """{"schema_version":"policy.v1","policy_id":"statistical","defaults":{"sample_floor":1,"min_samples":1},"rules":[{"id":"p95","metric":"response_time_p95_ms","operator":"lte","threshold":100000,"scope":{"kind":"overall"}}]}"""
            .encodeToByteArray()
```

и в `statisticalRuns`: `input.runId to api.createJob(input.runId, statisticalPolicy).analysisId(api)`. В `ui/e2e/baseline.spec.ts` помощнику `analyze(page, name, elapsed, timestamp)` добавить необязательный параметр `withPolicy = false`: при `true` перед запуском в поле `policy-file` загружается разрешительная политика из памяти (`page.getByTestId('policy-file').setInputFiles({ name: 'policy.json', mimeType: 'application/json', buffer: Buffer.from(permissivePolicy) })`, где `permissivePolicy` — JSON из `statisticalPolicy` выше) и ожидается строка статуса `Policy is valid` (проверка политики асинхронна); готовые `pass.json`/`fail.json` не годятся: у них порог p95 100 мс, а сценарии используют 110 и 1000 мс; `withPolicy = true` передать во всех вызовах сценариев со статистическим выбором (строки 124-152 и 312-331 на `origin/main` `8357635`; точный список даёт запуск `npm --prefix ui run e2e -- e2e/baseline.spec.ts` после изменения ядра) и заменить ожидание `NO_POLICY` на фактический вердикт политики там, где анализ теперь с политикой (помощник `analyze` сегодня безусловно ждёт `NO_POLICY`, `baseline.spec.ts:5-23`). Кроме того, добавить в `LocalApiTest` проверку: statistical-выбор из трёх анализов без политики возвращает 422 `BASELINE_CANDIDATE_GATES_UNKNOWN`.

- [ ] **Step 1.5:** Run: `.\gradlew.bat test`, `npm --prefix ui run e2e -- e2e/baseline.spec.ts`. Expected: PASS. Commit: `feat(baseline): require verdict gates for statistical candidates`.

### Task 2: Документация

- [ ] **Step 2.1:** `docs/user/slice-1-local-analysis.md` (раздел Baseline, статистический автовыбор): кандидатом может быть только анализ с политикой, выполненный версией, записывающей `verdict_gates`; старые анализы и анализы без политики перепроверяются повторным анализом; код `BASELINE_CANDIDATE_GATES_UNKNOWN`. `CHANGELOG.md` (`Changed`). Run markdownlint для двух файлов. Commit: `docs(baseline): describe the candidate gate requirement`.

**Риски S10:** (1) statistical baseline из анализов без политики перестаёт работать (ожидаемо по Q11, совпадает с правилом «только PASS» ADR 0019, где `NO_POLICY` тоже исключён; после D5a для таких анализов приоритет у `BASELINE_CANDIDATE_NOT_PASS`, и тест `GATES_UNKNOWN` на анализах без политики в `LocalApiTest` перепишется на анализ с политикой, но без блока); блок `verdict_gates` создаёт и capacity без политики (S3), поэтому проверяется ещё и `policy_sha256 != NO_POLICY`; (2) ручной выбор baseline проверку не проходит (ADR 0018, раздел 1); (3) конфликт ADR 0018 и ADR 0019 про `SMALL_SAMPLE` и `COMPLETE` решён владельцем 2026-10-04 (п. 3): тесты отбора кандидатов проверяют, что `PASS` с `INCOMPLETE` только из-за `SMALL_SAMPLE` проходит, а `INCOMPLETE` по иной причине отвергается.

**Documentation impact:** `docs/user/slice-1-local-analysis.md`, `CHANGELOG.md`.

## Результат независимого ревью плана

Ревью: Codex `gpt-6-sol`, `xhigh`, read-only, по воркстри `origin/main` `8357635`. Принято и внесено: расхождение с редакцией ADR `b043695` (имена `openshift_container_*`, `PLATFORM_SERVICE_NOT_IN_CATALOG`, пустая область после `except`, покрытие по паре «сервис × окно» с `interval_max`, `PLATFORM_AGGREGATION_OPERATOR_MISMATCH`, жёсткая зависимость S6 от `interval_max`); тест синхронности перечня агрегаций схемы и ядра; алгоритм «серии через пропуск» по отрезкам (раньше смешанная серия давала ложно доказанную находку); константа `PASS_POLICY_SHA256` в `LocalApiTest`; оконный тест транзакционного правила и тест ступени без бизнес-правил; автоматический тест CLI для кода 4; допустимые поля допуска в схеме платформенного правила; отдельная политика и ожидание статуса в e2e S10; проверка `policy_sha256` в S10; покрытие и «предположительное» для любого статуса в S8; floor для `INSUFFICIENT` в таблице S2; обновление нормативного prompt в S5 и S6. Вынесено владельцу, а не решено в плане: конфликт ADR 0018 и ADR 0019 (вопрос 3) и поэтапность ключей `verdict_gates` против текста ADR (вопросы 1, 2). Не принято: добавление `policy_check` малой выборки в `evidenceRefs` ступени (не требуется ADR); тест плеч оставлен на отдельных снимках, потому что `arm` и `resource_arm` вводит срез ADR 0014, а не этот план.

## Порядок слияния и граф зависимостей

```text
ADR 0016 (внешний) --> S1 --> S2 --> S8
                        |--> S3
                        |--> S4
                        |--> S5 --> S6 --> S7 --> S8
                        |                    \--> S9 <-- ADR 0014 interval_max (внешний)
                        \--> S10 <-- согласование с ADR 0019 D5a
```

Срезы S6-S9 стартуют после среза `interval_max` ADR 0014 (внешний; решение владельца 2026-10-04, п. 4): на схеме он показан у S9, но S6 тоже заблокирован им.

Параллельно допустимы только срезы, не делящие файлов: S3, S4 и S5 после S1 трогают разные части `WindowPolicy.kt`/`CapacityAnalysis.kt`/UI; S3 и S5 оба правят `WindowPolicy.kt`, поэтому сливаются последовательно. Срезы S1 и S6 общие по `Policy.kt` и `Model.kt`: S6 стартует от слитого S5.

## Не входит (report-only)

- ADR 0016 (ограничение перцентиля максимумом), срезы ADR 0014 (`arm`/`resource_arm`, `interval_max`, автошаг и его исключение для событийных рядов, Q12), ADR 0019 и ADR 0020, правки текстов ADR 0009 (условие ступени, Q14) и ADR 0014.
- Редактор политики U4 (новые поля сохраняются как есть: `JSON.parse`/`stringifyPolicy` не отбрасывают неизвестные ключи, но формы для них нет), библиотека и версионирование политик, `policy_id` в результате.
- Группировка плеч в интерфейсе и общий заголовок «все плечи».
- Ложный `PASS` для правил снимка и профиля при окне короче `min_consecutive_cells` (`ResourceStatistics.kt:269-270`, `:315-319`): остаточный риск принят владельцем (Q9).
- Калибровка 20, 100, 5 % и 3 на стенде; проверка сброса счётчиков OOM/restart и покрытия подов на реальных прогонах; предварительный бюджет evidence `E_max` ADR 0014.

## Проверка перед сдачей каждого среза

1. `git diff origin/main --stat` и полный просмотр diff; в индексе только файлы среза.
2. `.\gradlew.bat clean check installDist` (как в CI, `.github/workflows/runtime-quality.yml:71`), `npm --prefix ui run typecheck`, `npm --prefix ui run lint`, `npm --prefix ui run test:contracts`, `npm --prefix ui run build`, `npm --prefix ui run e2e`.
3. `python tools/verify_slice0.py` и `python -m unittest discover -s tools -p "test_*.py"`, если среди затронутых фикстур есть `fixtures/slice1` или корпус `tools/`.
4. `npx --yes markdownlint-cli2@0.23.2 "**/*.md"` и `git diff --check`.
5. Отчёт по AGENTS.md: команды, результаты, ограничения, непроверенные допущения (в частности: значения 20/100/5 %/3 не откалиброваны; поведение на реальном OpenShift не проверено).
