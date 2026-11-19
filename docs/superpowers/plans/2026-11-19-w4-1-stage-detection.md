# W4.1: предложение стадий нагрузки по данным прогона (план реализации)

> **Для исполнителей-агентов:** ОБЯЗАТЕЛЬНЫЙ ПОДНАВЫК: `superpowers:subagent-driven-development` (рекомендуется) или
> `superpowers:executing-plans`. Шаги отмечаются флажками (`- [ ]`).

**Цель:** команда `ltv stages detect` (затем кнопка в форме нового анализа) строит по ряду RPS прогона документ
`load-stages.v1` и объяснение; человек проверяет его и передаёт в `ltv analyze --stages` как обычный явный вход.

**Архитектура:** отдельный проход по входу (по образцу `recognizeRunPeriod`) строит ряд числа запросов по секундам от начала
прогона; чистая функция `detectLoadStages` делит ряд на кусочно-линейные отрезки со штрафом, выбирает плато и строит стадии или
отказ с причиной. CLI печатает канонические байты `load-stages.v1` в stdout или `--out` и объяснение `ключ=значение` в stderr.
Анализ, identity и канонический JSON не меняются.

**Стек:** Kotlin (JVM 17+), kotlinx.serialization, JUnit 5; во втором PR Ktor, Vue 3, Playwright.

**Спецификация:** [ADR 0036](../../adr/0036-load-stage-proposal.md) (Proposed). Решения владельца 2026-11-19 (обязательность,
предложение с подтверждением человеком, без автоприменения) не пересматриваются.

**Решения владельца 2026-11-19 по открытым вопросам** (ADR 0036, раздел «Решения владельца 2026-11-19»): обязательность
автоопределения это явное исключение из заморозки D1 перечня ревью 2026-10-08, PR 1 реализуется при ADR в статусе Proposed;
ступенчатый профиль получает переключатель `--steady-steps top|all` (D12, умолчание `top` выбрал исполнитель, ждёт слова
владельца); `--like` одобрен следующим срезом (PR 1b, D13); код выхода `3` для отказа остаётся.

## Глобальные ограничения

- `ltv analyze`, `AnalysisService`, identity, `analysis_id`, ключ сопоставимости, `CanonicalJson.kt`, схемы
  `analysis-result.v1` и `load-stages.v1` не меняются; существующие тесты и золотые файлы не правятся.
- Детерминизм: те же байты входа дают те же байты stdout и stderr; нет `Random`, часов, `HashMap` в порядке вывода.
- Новых зависимостей production нет.
- Проза документов на русском, идентификаторы на английском; пользовательские изменения записываются фрагментом
  `changelog.d/<slug>.added.md`, `CHANGELOG.md` не правится.
- Константы метода берутся из таблицы раздела «Метод» как есть. Если допуск фикстуры не выполняется, константы не подгоняются
  молча: исполнитель останавливается и докладывает (AGENTS.md, п. 10).
- UI-часть это отдельный PR после слияния PR 1.

## Блок для AGENTS.md

PR 1 (`feat/stage-proposal-cli`):

```text
REQUESTED: команда ltv stages detect <input> [--out <file>] [--steady-steps top|all], которая по данным JTL/Gatling предлагает
  load-stages.v1 с объяснением и уверенностью либо явно отказывает с причиной; режим ступеней (D12); документация и фрагмент
  журнала.
REQUIRED TO ACHIEVE IT:
  - core/StageProposal.kt (новый): проход по входу (ряд запросов по секундам), метод rps-plateau-segmentation.v1, модель результата;
  - core/LoadStages.kt: private fun stagesJson -> internal (одно слово), чтобы предложение сериализовалось той же функцией;
  - cli/CliStages.kt (новый): разбор аргументов, вывод, коды выхода; cli/CommandLine.kt: ветка "stages" в runCli, строка в usageText,
    EXIT_NO_VERDICT private -> internal;
  - тесты: core/StageProfiles.kt (генератор синтетики), core/StageProposalSeriesTest.kt, core/StageProposalTest.kt,
    cli/CommandLineStageDetectTest.kt;
  - docs/user/slice-1-local-analysis.md, README.md, docs/architecture/slice-1-local-runtime.md (строка команды),
    docs/adr/0030 (строка-ссылка), docs/adr/0036 (раздел «Реализовано»), changelog.d/w4-1-stage-proposal-cli.added.md.
NOT REQUIRED:
  - любые правки AnalysisService, identity, результата, отчётов, summary.txt, junit.xml;
  - маршрут API и UI (PR 2); MCP; сохранение предложения в каталоге данных; схема stage-proposal.v1;
  - ltv stages validate, --format json, локализация вывода CLI; --like (следующий срез, PR 1b);
  - правка tools/perf/generate_jtl.py и новые закоммиченные JTL-фикстуры (синтетика строится в тестах).
EXPECTED FILES TO CHANGE:
  новые: src/main/kotlin/io/ltverdict/core/StageProposal.kt, src/main/kotlin/io/ltverdict/cli/CliStages.kt,
    src/test/kotlin/io/ltverdict/core/StageProfiles.kt, StageProposalSeriesTest.kt, StageProposalTest.kt,
    src/test/kotlin/io/ltverdict/cli/CommandLineStageDetectTest.kt, changelog.d/w4-1-stage-proposal-cli.added.md;
  правки: src/main/kotlin/io/ltverdict/core/LoadStages.kt (видимость), src/main/kotlin/io/ltverdict/cli/CommandLine.kt,
    docs/user/slice-1-local-analysis.md, README.md, docs/architecture/slice-1-local-runtime.md, docs/adr/0030-load-stages-steady-window.md,
    docs/adr/0036-load-stage-proposal.md.
```

PR 2 (`feat/stage-proposal-ui`, после слияния PR 1):

```text
REQUESTED: кнопка «Предложить стадии» в форме нового анализа, которая заполняет поле стадий предложением для подтверждения человеком.
REQUIRED TO ACHIEVE IT:
  - поле стадий в форме и часть stages в createJob (сегодня их нет: api.ts:120-135, ADR 0030 R12);
  - маршрут POST /api/runs/{runId}/stage-proposal (RunRoutes.kt), разрешение Semaphore(1) в LocalApi.kt,
    stageProposalJson в core/StageProposal.kt;
  - ui/src/api.ts (proposeStages, параметр stages у createJob), ui/src/shell/NewAnalysisPanel.vue, ui/src/App.vue (проводка),
    подписи в ui/src/shell/setup.ts (или файле подписей формы), типы ответа в ui/src/types.ts;
  - тесты: web/LocalApiStageProposalTest.kt, ui/e2e/stage-proposal.spec.ts;
  - документация: руководство (раздел стадий и вкладка «Новый анализ»), docs/architecture/slice-1-local-runtime.md (маршрут),
    ADR 0030 (пометка у R12), ADR 0036, changelog.d/w4-1-stage-proposal-ui.added.md.
NOT REQUIRED: визуальный редизайн формы, загрузка файла стадий с диска (достаточно поля JSON), MCP, сохранение предложения.
EXPECTED FILES TO CHANGE:
  новые: src/test/kotlin/io/ltverdict/web/LocalApiStageProposalTest.kt, ui/e2e/stage-proposal.spec.ts,
    changelog.d/w4-1-stage-proposal-ui.added.md;
  правки: src/main/kotlin/io/ltverdict/web/RunRoutes.kt, src/main/kotlin/io/ltverdict/web/LocalApi.kt,
    src/main/kotlin/io/ltverdict/core/StageProposal.kt, ui/src/api.ts, ui/src/types.ts, ui/src/App.vue,
    ui/src/shell/NewAnalysisPanel.vue, ui/src/shell/setup.ts (или файл подписей формы), docs/user/slice-1-local-analysis.md,
    docs/architecture/slice-1-local-runtime.md, docs/adr/0030-load-stages-steady-window.md, docs/adr/0036-load-stage-proposal.md.
```

## Находки в коде

1. **Начало прогона.** `AnalysisService.kt:277-311`: первый проход `parseInput` берёт `firstStart` как минимум
   `sample.startedAtEpochMillis` по всем выборкам (любой `SampleKind`, в том числе setUp-сэмплеры и контейнеры JMeter), конец
   `firstEnd` как максимум `endedAtEpochMillis`. От этого `runStart` отсчитываются стадии (`AnalysisService.kt:319`,
   `LoadStages.kt:111-144`). Предложение обязано использовать то же правило, иначе его смещения будут означать другое.
2. **Ряд нагрузки ядра.** `Metrics.kt:202-232`: `MetricsAccumulator` раскладывает только `JMETER_SAMPLER` и `GATLING_REQUEST`
   по секундным корзинам `((start - runStart) / 1000) * 1000` (строки 212-215), с HDR-гистограммой на корзину; свёртки 10, 30,
   60 с (`Metrics.kt:255-262`, `:364`; `AnalysisResult.kt:125`: `bucket_millis = 1000`). Разрешение ряда ядра 1 с от начала
   прогона. Переиспользовать аккумулятор незачем: гистограммы на корзину не нужны, а он требует уже известный `runStart`.
3. **Образец лёгкого прохода.** `RunPeriod.kt:41-103`: `recognizeRunPeriod` строит `AcceptedInput` с фиктивным `runId`
   (`RECOGNITION_RUN_ID`, строка 184), вызывает `parseInput` и копит занятые секунды запросов (строки 61-63), без каталога данных.
   Парсеры поле `sha256` не читают (проверено поиском по `ingest/`). `detectSource(path)` (`FormatDetector.kt:24`) определяет
   формат без каталога данных, `parseInput` (`FormatDetector.kt:51`) читает любой путь.
4. **Сериализация стадий.** `LoadStages.kt:78-104` (`validateLoadStages`) строит канонические байты через приватную
   `stagesJson` (`LoadStages.kt:284`) и `canonicalJson`. Предложение должно сериализоваться той же функцией: тогда его байты
   совпадают с `canonicalBytes()` валидатора. Пределы контракта: 16 стадий, смещение до 604800000 мс
   (`LoadStages.kt:390-393`).
5. **CLI.** `CommandLine.kt:80-101` диспетчер команд; `:614-622` `ltv policy validate` печатает канонические байты в stdout
   (образец вывода документа); `:951-976` `usageText`; `:985-995` коды выхода (`EXIT_NO_VERDICT = 3` приватный, строка 987);
   `:489` отказ `PG_REPORT_EXISTS` для существующего выходного файла (образец для `--out`). Помощники `requireRegularFile`,
   `path`, `usage` уже `internal` (`CommandLine.kt:928-949`) и используются из `CliProbe.kt`, видимость менять не нужно.
   Тест справки `CliArtifactsTest.kt:70` проверяет только начало строки `Usage: ltv`, новая строка его не ломает.
6. **API и UI.** `JobRoutes.kt:376` уже принимает часть `stages` задания; `:472` `store.requireInput(runId)`. `RunRoutes.kt:39`
   маршруты прогона; `LocalApi.kt:53-54` разрешения `Semaphore(1)`; `SourceRoutes.kt:73` отказ `conflict("BUSY", ...)`. В UI поля
   стадий нет: `createJob` (`ui/src/api.ts:120-135`) не отправляет `stages`; вход загружается только при запуске анализа
   (`ui/src/App.vue:457-494`).
7. **Опорная фикстура.** `fixtures/stages/ramp-steady-rampdown.jtl`: «разгон» это ровные 2 rps на 0-40 с (80 выборок), плато
   10 rps на 40-100 с (600), хвост 2 rps на 100-120 с (40); конец прогона 119800 мс. Пример
   `docs/contracts/stages/v1/examples/valid/ramp-steady-down.json` объявляет ровно 0-40, 40-100, 100-120 с.
8. **Документация, которую опровергнет PR 1.** `docs/user/slice-1-local-analysis.md:2107-2108` и `:2385` («Автоопределения
   плато нет»), `:2263-2264` (формы стадий в UI нет, правит PR 2), раздел «CLI» `:2453-2463`; `README.md:19-22`, `:113-117`;
   `docs/architecture/slice-1-local-runtime.md:21` (перечень команд), `:330-343` (перечень маршрутов, правит PR 2).
9. **Генераторы.** `tools/perf/generate_jtl.py` пишет равномерные выборки для perf-проверки CI, профилей нагрузки не умеет;
   `fixtures/stages/` содержит одну JTL. Новые профили строятся в тестах (см. «Фикстуры»), инструменты не трогаются.

## Метод `rps-plateau-segmentation.v1`

Вход: `LoadSecondSeries` (начало и конец прогона, `requestsPerSecond[i]` это число стартов запросов в
`[runStart + 1000 i, runStart + 1000 (i + 1))`, длина `floor((runEnd - runStart) / 1000) + 1`, признак `DEGRADED`).

1. **Ширина ячейки `W`.** Перебираются `CELL_LADDER_SECONDS` по возрастанию. Для `W` число полных ячеек
   `n = floor((runEnd - runStart) / (1000 W))`; остаток в конце в подбор не идёт и достаётся последней стадии. Берётся первая `W`,
   у которой `n <= MAX_CELLS` и верхний квартиль (ранг `ceil(0,75 n)` по возрастанию) числа запросов в ячейке не меньше
   `MIN_CELL_COUNT_P75`. Нет такой `W`: отказ `RATE_TOO_LOW`.
2. **Шум.** `d_k = c_{k+1} - c_k`; `m` нижняя медиана `d`; `MAD` нижняя медиана `|d_k - m|`;
   `σ = max(NOISE_FLOOR_COUNTS, MAD_TO_SIGMA * MAD / sqrt(2))` (в запросах на ячейку).
3. **Сегментация.** Минимизируется `Σ SSE(отрезок) + β · (число отрезков)`, `β = PENALTY_BIC_FACTOR · σ² · ln n`
   (`StrictMath.log`), где `SSE` это сумма квадратов остатков прямой МНК по ячейкам отрезка. Динамическое программирование
   `F(t) = min_s F(s) + SSE(s, t) + β` по `s`, при котором оба отрезка не короче `MIN_SEGMENT_CELLS`; при равенстве берётся
   меньшая `s`. Суммы `Σc`, `Σc²`, `Σkc` префиксные, целые (`Math.addExact`, `Math.multiplyExact`; переполнение даёт отказ
   `RATE_OUT_OF_RANGE`), значения перед накоплением центрируются на нижнюю медиану `c`; `SSE` считается в `double` из точных
   сумм и обрезается снизу нулём. При `n < 2 · MIN_SEGMENT_CELLS` отрезок один.
4. **Классификация отрезка** (уровень `ℓ` это среднее, наклон `b` в запросах на ячейку за ячейку, длина `m` ячеек; `peak` это
   наибольший `ℓ` среди всех отрезков): `IDLE`, если `ℓ <= IDLE_LEVEL_RATIO · peak`; иначе `FLAT`, если
   `|b| · m / ℓ <= MAX_DRIFT_RATIO` и `sqrt(SSE / m) / ℓ <= MAX_RESIDUAL_CV`, либо (удержание, уточнение 2026-11-19) `b < 0`,
   длина не меньше `DROP_MIN_MILLIS`, `|b| · m / ℓ <= HOLD_MAX_DRIFT_RATIO` и тот же предел разброса; иначе `UNSTABLE`, если разброс остатков больше
   `MAX_RESIDUAL_CV`; иначе `RISING` при `b > 0` и `FALLING` при `b < 0`. Соседние `FLAT` сливаются (слева направо, до
   неподвижной точки), если `|ℓ_a - ℓ_b| <= max(LEVEL_MERGE_RATIO · max(ℓ_a, ℓ_b), LEVEL_MERGE_SIGMAS · σ · sqrt(1/m_a + 1/m_b))`
   и объединённый отрезок по пересчёту остаётся `FLAT`. Поправка при реализации PR 1: сливаются только отрезки не короче
   `MIN_STEADY_MILLIS` (иначе короткий переход между ступенями сливается со следующим плато и попадает в его окно).
5. **Кандидаты в steady.** `FLAT` длиной не меньше `MIN_STEADY_MILLIS`, у которых `ℓ >= STEADY_LEVEL_RATIO · ℓ_max` или длина
   не меньше `STEADY_DURATION_RATIO` длины самого длинного `FLAT` (`ℓ_max` по `FLAT`). Нет кандидатов: отказ `NO_PLATEAU`.
6. **Простой внутри.** `IDLE` длиной не меньше `IDLE_GAP_MIN_MILLIS` между началом первого и концом последнего кандидата: отказ
   `IDLE_GAP_INSIDE_RUN`.
7. **Остановка.** От конца ряда назад берётся наибольший суффикс после последнего кандидата из отрезков `IDLE`, любых отрезков
   короче `DROP_MIN_MILLIS` и `FALLING`, у которых значение прямой в конце меньше `STEADY_LEVEL_RATIO` значения в начале
   (уточнение 2026-11-19: долгий отрезок, который держит уровень, в остановку не попадает). Всё между концом последнего кандидата и началом этого суффикса присоединяется к
   последней стадии steady (D4 ADR 0036).
8. **Стадии steady.** Кандидаты идут по времени. Следующий кандидат открывает новую стадию, только если его уровень выше уровня
   текущей стадии больше чем на порог слияния п. 4 и между ними нет отрезков, кроме `RISING` и (поправка при реализации PR 1)
   растущих `FLAT` короче `MIN_STEADY_MILLIS`; переход между ними становится зазором. Без поправки переход +10 % за 10 с
   (дрейф 0,09 не больше `MAX_DRIFT_RATIO`) классифицируется `FLAT`, и ступени 80 и 90 rps сливаются в одно окно. Кандидат
   открывает новую стадию и тогда, когда между ними есть `FALLING` не короче `DROP_MIN_MILLIS` (уточнение 2026-11-19, D4):
   спад становится зазором с предупреждением `LOAD_DECLINE_BETWEEN_STEPS` (`from_rps`, `to_rps` по прямым спада,
   `duration_ms`). Иначе всё от текущей стадии до конца следующего кандидата сливается в одну стадию. Если внутри стадии есть отрезок с
   уровнем ниже `(1 - LEVEL_MERGE_RATIO) ·` уровня первого плато стадии, ставится `LOAD_DROP_INSIDE_STEADY` (смещение начала
   самого раннего такого отрезка, уровни до и после в rps).
8a. **Режим ступеней (D12).** Пункты 1-8 строят стадии-ступени одинаково в обоих режимах. `--steady-steps all`: роль `steady`
   у каждой ступени. `--steady-steps top` (умолчание): роль `steady` только у наивысшей ступени (из равных последней),
   остальные получают роль `excluded` с теми же именами `step-K`; ступень после наивысшей получает предупреждение
   `HOLD_NOT_EVALUATED` (`from_rps` наивысшей, `to_rps` своей, `duration_ms`) и уверенность `LOW`. Пункты 6, 9, 10, 11, 12 и знаменатель `LONG_RAMP_DOWN` считаются по
   всем ступеням; `LOAD_DROP_INSIDE_STEADY`, `STEADY_DRIFT`, контраст границ и разброс плато в уверенности только по стадиям с
   ролью `steady`. При одной ступени режимы неразличимы.
9. **Края.** Для каждой стадии steady: пока крайняя ячейка отличается больше чем на `EDGE_TOLERANCE_SIGMAS · σ` от значения
   прямой того отрезка, которому эта ячейка принадлежит (не от уровня первого плато стадии: у стадии с падением конец лежит на
   пониженном уровне), и сдвиг меньше `EDGE_TRIM_MAX_CELLS`, граница сдвигается внутрь на ячейку.
10. **Начало и конец.** Начальные `IDLE` до первой стадии steady дают `lead-in` `[0, начало первого не-IDLE)` и предупреждение
    `LEAD_IN_IDLE`; остаток до первой стадии steady даёт `ramp-up` (если не пуст). После последней стадии steady идёт
    `ramp-down` до `ceil((runEnd - runStart) / (1000 W)) · 1000 W`. Если после последней стадии steady ничего нет, её конец
    сам округляется вверх до этой сетки (анализ обрежет его до конца прогона, `clipped_to_run_end`).
11. **Доля плато.** `Σ длин steady / (длина прогона - длина lead-in) < MIN_STEADY_SHARE`: отказ `NO_PLATEAU`.
12. **Предел.** Стадий больше 16: отказ `TOO_MANY_STAGES`.
13. **Предупреждения и уверенность.** `LONG_RAMP_DOWN`: `ramp-down` длиннее `max(DROP_MIN_MILLIS, LONG_RAMP_DOWN_RATIO · Σ steady)`.
    `STEADY_DRIFT`: дрейф стадии steady больше `STEADY_DRIFT_WARN_RATIO`. `DEGRADED_INPUT`: вход `DEGRADED`. Уверенность `LOW`
    при `LOAD_DROP_INSIDE_STEADY` или `LONG_RAMP_DOWN`; `MEDIUM` при `LEAD_IN_IDLE`, `STEADY_DRIFT`, `DEGRADED_INPUT`, контрасте
    любой границы steady (`|ℓ_steady - ℓ_соседа| / σ`) меньше `HIGH_EDGE_SIGMAS` или разбросе остатков плато больше
    `HIGH_RESIDUAL_CV`; иначе `HIGH`. Всегда `calibrated=false`.

Порядок отказов: `INPUT_INVALID`, `NO_REQUEST_SAMPLES`, `RUN_TOO_LONG` (в проходе, до выделения массива), `RUN_TOO_SHORT`
(`runEnd - runStart < MIN_RUN_MILLIS`), `RATE_TOO_LOW`, `RUN_TOO_SHORT` (`n < MIN_RUN_CELLS`), `RATE_OUT_OF_RANGE`,
`IDLE_GAP_INSIDE_RUN`, `NO_PLATEAU`, `TOO_MANY_STAGES`.

### Константы

| Имя | Значение | Обоснование |
| --- | --- | --- |
| `CELL_LADDER_SECONDS` | 1, 2, 5, 10, 15, 30, 60, 120, 300 | делители минуты и часа: границы ложатся на круглые смещения; 300 с дают 2016 ячеек на 7 сутках |
| `MAX_CELLS` | 2400 | динамика `O(n²)`: около 2,9 млн пар, доли секунды |
| `MIN_CELL_COUNT_P75` | 50 | пуассоновский шум ячейки плато `1/√50 ≈ 14 %`; на опорной фикстуре даёт `W = 5` |
| `MIN_RUN_MILLIS` | 60000 | короче окно steady бессмысленно для p95 и разгона с остановкой |
| `MIN_RUN_CELLS` | 12 | четыре отрезка по три ячейки (lead-in, разгон, плато, остановка) |
| `MIN_SEGMENT_CELLS` | 3 | у прямой по двум точкам нет остатков |
| `MAD_TO_SIGMA` | 1,4826 | согласованность MAD с σ нормального распределения |
| `NOISE_FLOOR_COUNTS` | 1 | разрешение ряда: один запрос на ячейку |
| `PENALTY_BIC_FACTOR` | 3,0 | BIC при известной σ: по `ln n` на параметр, у отрезка уровень, наклон и точка смены |
| `MAX_DRIFT_RATIO` | 0,10 | плато, уровень которого уходит больше чем на 10 %, это уже рост или спад |
| `MAX_RESIDUAL_CV` | 0,25 | примерно 1,8 пуассоновского шума при `MIN_CELL_COUNT_P75` (запас на неравномерность генератора) |
| `IDLE_LEVEL_RATIO` | 0,02 | одиночные setUp и health-check на фоне нагрузки |
| `LEVEL_MERGE_RATIO` | 0,05 | ступени ближе 5 % для вердикта неразличимы |
| `LEVEL_MERGE_SIGMAS` | 4,0 | разность средних двух отрезков меньше четырёх стандартных ошибок это шум |
| `MIN_STEADY_MILLIS` | 30000 | p95 по окну короче 30 с неустойчив |
| `STEADY_LEVEL_RATIO` | 0,5 | прогрев на 20 % максимума (опорная фикстура) не steady |
| `STEADY_DURATION_RATIO` | 0,75 | ступени ёмкости одинаковой длины остаются steady; прогрев опорной фикстуры (40 с против 45) нет |
| `EDGE_TOLERANCE_SIGMAS` | 2,0 | крайняя ячейка в пределах шума плато принадлежит плато |
| `EDGE_TRIM_MAX_CELLS` | 2 | ошибка точки смены больше двух ячеек это признак другого профиля, а не края |
| `DROP_MIN_MILLIS` | 60000 | хвост опорной фикстуры (20 с) остаётся остановкой, падение на минуту и дольше нет |
| `HOLD_MAX_DRIFT_RATIO` | 0,25 | добавлена 2026-11-19: долгий (от `DROP_MIN_MILLIS`) падающий отрезок, теряющий не больше четверти уровня, это удержание (плато), а не спад; спад в остановку теряет больше половины (`STEADY_LEVEL_RATIO`), промежуток 25-50 % остаётся в steady по D4 |
| `LONG_RAMP_DOWN_RATIO` | 0,25 | плановая остановка обычно короче четверти плато |
| `IDLE_GAP_MIN_MILLIS` | 30000 | простой полминуты и больше внутри плато похож на два теста |
| `MIN_STEADY_SHARE` | 0,30 | опорная фикстура 0,5; разгон, плато и остановка по 10 мин дают 0,33; непрерывный рост 0 |
| `STEADY_DRIFT_WARN_RATIO` | 0,05 | половина `MAX_DRIFT_RATIO` |
| `HIGH_EDGE_SIGMAS` | 6,0 | граница уверенно видна |
| `HIGH_RESIDUAL_CV` | 0,15 | около одного пуассоновского шума при 50 запросах |

Все значения не калибровались на корпусе реальных прогонов (ADR 0036, D3).

### Ручной расчёт на опорной фикстуре

`fixtures/stages/ramp-steady-rampdown.jtl`, `runStart = 1767225600000`, `runEnd - runStart = 119800`.

- `W = 1`: P75 = 10; `W = 2`: 20; `W = 5`: `n = 23`, счётчики 8 по 10, 12 по 50, 3 по 10; ранг 18 даёт 50, `W = 5`.
- `d`: 20 нулей, +40, −40; `MAD = 0`, `σ = 1`; `β = 3 · ln 23 ≈ 9,41`.
- Отрезки `[0, 8)` уровень 10 (2 rps), `[8, 20)` 50 (10 rps), `[20, 23)` 10, все `FLAT`, `SSE = 0`.
- Кандидаты: `[8, 20)` (60 с, уровень максимальный); `[0, 8)` отклонён (0,2 < 0,5 и 40 с < 45 с); `[20, 23)` короче 30 с.
- Края не сдвигаются (`|50 - 50| <= 2`). Хвост 15 с короче `DROP_MIN_MILLIS`: остановка до `ceil(119,8 / 5) · 5 = 120` с.
- Предложение: `ramp-up` 0-40000, `steady` 40000-100000, `ramp-down` 100000-120000, то есть канонические байты примера
  `ramp-steady-down.json`; контраст границ 40σ, уверенность `HIGH`. Доля плато `60 / 119,8 = 0,50`.
- С выборкой setUp за 30 с до нагрузки (пример раздела «Ловушка начала прогона»): `n = 29`, ячейка 0 содержит 1 запрос,
  1-5 пусты, затем профиль; P75 (ранг 22) = 50, `W = 5`. Предложение: `lead-in` 0-30000, `ramp-up` 30000-70000, `steady`
  70000-130000, `ramp-down` 130000-150000, предупреждение `LEAD_IN_IDLE`, уверенность `MEDIUM`. Окно steady при анализе:
  `[t0 + 40000, t0 + 100000)`, 600 выборок, p95 194, `PASS`, как поправленное вручную объявление руководства.

## Контракт и вывод

- stdout или `--out`: канонические байты `load-stages.v1` (`canonicalJson(stagesJson(stages))`), без перевода строки, как
  `ltv policy validate`. Только при `DETECTED`.
- stderr, порядок строк фиксирован (числа `HALF_EVEN`, три знака):

  ```text
  stage_proposal status=DETECTED method=rps-plateau-segmentation.v1 steady_steps=top confidence=HIGH calibrated=false
  run start_epoch_ms=1767225600000 end_epoch_ms=1767225719800 cell_ms=5000 cells=23 noise_rps=0.200
  segment from_offset_ms=0 to_offset_ms=40000 kind=FLAT level_rps=2.000 drift=0.000 residual_cv=0.000
  segment from_offset_ms=40000 to_offset_ms=100000 kind=FLAT level_rps=10.000 drift=0.000 residual_cv=0.000
  segment from_offset_ms=100000 to_offset_ms=115000 kind=FLAT level_rps=2.000 drift=0.000 residual_cv=0.000
  stage id=ramp-up role=excluded from_offset_ms=0 to_offset_ms=40000 basis=BEFORE_FIRST_STEADY
  stage id=steady role=steady from_offset_ms=40000 to_offset_ms=100000 basis=PLATEAU level_rps=10.000 from_contrast_sigma=40.000 to_contrast_sigma=40.000 trimmed_cells=0
  stage id=ramp-down role=excluded from_offset_ms=100000 to_offset_ms=120000 basis=AFTER_LAST_STEADY
  next: review the stages, then run ltv analyze <input> --stages <file>
  ```

  Отказ: первая строка `stage_proposal status=NOT_DETECTED method=rps-plateau-segmentation.v1 steady_steps=<режим> reason=<код>`,
  затем строки
  `run` и `segment`, если они уже вычислены, и `detail` с измеренным фактом (`steady_share=0.120`, `idle_gap_ms=60000`,
  `stages=18`). Предупреждение: `warning code=<код> stage_id=<id> at_offset_ms=<n> [from_rps=.. to_rps=..] [duration_ms=..]`.
  Контраст границы без соседа (начало прогона) печатается как `-`.

- Коды выхода: `0` предложение; `3` отказ метода (все причины, кроме `INPUT_INVALID`); `4` вход непригоден (`INPUT_INVALID`,
  как `INVALID` у `ltv analyze`), не найден, не обычный файл, формат не распознан, `--out` существует или не записан
  (`STAGES_OUT_EXISTS`, `STAGES_OUT_WRITE_FAILED`); `64` использование (нет входа, повтор `--out` или `--steady-steps`,
  значение `--steady-steps` не `top` и не `all`, лишний аргумент, подкоманда не `detect`).
- Схема `stage-proposal.v1` не вводится (ADR 0036, D7): предложение не сохраняется и не входит в артефакты; документ сразу
  годится для `--stages`, объяснение нужно человеку один раз. Ответ API во втором PR это добавочный маршрут локального API
  (ADR 0036, D10).

## Разбиение на PR

| PR | Ветка | Содержание | Зависимость |
| --- | --- | --- | --- |
| 0 | `docs/w4-1-stage-detection-plan` | ADR 0036 (Proposed) и этот план | нет |
| 1 | `feat/stage-proposal-cli` | задачи 1-5: проход, метод, CLI, документация, фрагмент журнала | слияние PR 0; вопросы 1-3 решены владельцем 2026-11-19 |
| 1b | `feat/stage-proposal-like` | задача 5b: режим `--like <load-stages.json>` (ADR 0036, D13) | слияние PR 1 |
| 2 | `feat/stage-proposal-ui` | задачи 6-8: маршрут API, поле стадий и кнопка в UI, документация | слияние PR 1, слово владельца по вопросу 4 |

## Фокус ревью

1. **Умирающая система в конце прогона.** В замкнутой модели JMeter RPS плавно падает до нуля, когда система умирает; метод
   считает это остановкой. Ожидание: предупреждение `LONG_RAMP_DOWN` и уверенность `LOW`, а не тихое исключение. Тест в задаче 3
   (`long decline at the end`, профиль F10).
2. **Строки JTL не по порядку старта.** JMeter пишет строку по завершении; начало прогона должно быть минимумом, а не первой
   строкой. Ожидание: перестановка строк не меняет stdout и stderr. Тест в задаче 4 (`row order does not change the proposal`).
3. **Ранний контейнер или setUp.** Контейнер JMeter раньше всех запросов сдвигает начало, но не даёт запросов. Ожидание: `lead-in`
   и предупреждение, а окно steady при анализе ложится на настоящее плато. Тесты в задачах 1 и 4.
4. **Граница семи суток.** Ожидание: прогон ровно 604800000 мс обрабатывается, на миллисекунду длиннее отказ `RUN_TOO_LONG` без
   выделения массива. Тест в задаче 1.
5. **Steady до конца прогона.** Конец steady округляется вверх за конец прогона. Ожидание: `ltv analyze --stages` с таким
   предложением проходит с `clipped_to_run_end = true`, без `STAGE_OUTSIDE_RUN`. Тест в задаче 4.

## Структура файлов

| Файл | Ответственность |
| --- | --- |
| `src/main/kotlin/io/ltverdict/core/StageProposal.kt` | модель (`LoadSecondSeries`, `StageProposal` и др.), проход `readLoadSecondSeries`, чистая `detectLoadStages`, обёртка `proposeLoadStages`; во втором PR `stageProposalJson` |
| `src/main/kotlin/io/ltverdict/cli/CliStages.kt` | `ltv stages detect`: аргументы, `--out`, строки stderr, коды выхода |
| `src/test/kotlin/io/ltverdict/core/StageProfiles.kt` | синтетические профили: ряд и JTL, истинные границы |
| `src/test/kotlin/io/ltverdict/core/StageProposalSeriesTest.kt` | проход по входу: начало прогона, виды выборок, отказы прохода |
| `src/test/kotlin/io/ltverdict/core/StageProposalTest.kt` | метод на профилях, допуски, отказы, уверенность, детерминизм, совместимость с валидатором |
| `src/test/kotlin/io/ltverdict/cli/CommandLineStageDetectTest.kt` | CLI и сквозная проверка с `ltv analyze --stages` |

Если `StageProposal.kt` вырастает больше 600 строк, метод выносится в `StageSegmentation.kt` (сегментация и классификация)
без изменения интерфейсов.

## Интерфейсы (общие для задач)

```kotlin
internal data class LoadSecondSeries(
    val runStartEpochMillis: Long,
    val runEndEpochMillis: Long,
    val requestsPerSecond: LongArray,
    val degraded: Boolean,
)

internal enum class StageProposalStatus { DETECTED, NOT_DETECTED }
internal enum class StageSegmentKind { IDLE, FLAT, RISING, FALLING, UNSTABLE }
internal enum class StageProposalConfidence { HIGH, MEDIUM, LOW }

internal data class StageSegment(
    val fromOffsetMillis: Long, val toOffsetMillis: Long, val kind: StageSegmentKind,
    val levelRps: Double, val driftRatio: Double, val residualCv: Double,
)
internal data class StageBoundaryFact(
    val stageId: String, val basis: String, val levelRps: Double?,
    val fromContrastSigma: Double?, val toContrastSigma: Double?, val trimmedCells: Int,
)
internal data class StageProposalWarning(
    val code: String, val stageId: String?, val atOffsetMillis: Long,
    val fromRps: Double? = null, val toRps: Double? = null, val durationMillis: Long? = null,
)
internal data class StageProposal(
    val status: StageProposalStatus,
    val reason: String?,                  // код отказа D5 или null
    val detail: String?,                  // "steady_share=0.120" и т.п. или null
    val confidence: StageProposalConfidence?,
    val runStartEpochMillis: Long?, val runEndEpochMillis: Long?,
    val cellMillis: Long?, val cells: Int?, val noiseRps: Double?,
    val segments: List<StageSegment>,
    val stages: List<StageV1>,            // пусто при отказе
    val boundaries: List<StageBoundaryFact>,
    val warnings: List<StageProposalWarning>,
)

internal fun readLoadSecondSeries(sourceType: SourceType, source: Path, checkCancelled: () -> Unit = {}): LoadSecondSeriesRead
internal sealed interface LoadSecondSeriesRead {
    data class Series(val series: LoadSecondSeries) : LoadSecondSeriesRead
    data class Refused(val reason: String) : LoadSecondSeriesRead   // INPUT_INVALID, NO_REQUEST_SAMPLES, RUN_TOO_LONG
}
internal enum class SteadySteps(val wireName: String) { TOP("top"), ALL("all") }   // D12, умолчание TOP
internal fun detectLoadStages(series: LoadSecondSeries, steadySteps: SteadySteps = SteadySteps.TOP): StageProposal
internal fun proposeLoadStages(
    sourceType: SourceType, source: Path, steadySteps: SteadySteps = SteadySteps.TOP, checkCancelled: () -> Unit = {},
): StageProposal
internal fun StageProposal.proposalBytes(): ByteArray   // canonicalJson(stagesJson(stages)); только при DETECTED
internal const val STAGE_PROPOSAL_METHOD = "rps-plateau-segmentation.v1"
```

---

## PR 1: ядро и CLI

### Задача 1: проход по входу (`readLoadSecondSeries`)

**Файлы:**

- Создать: `src/main/kotlin/io/ltverdict/core/StageProposal.kt` (модель и проход)
- Создать: `src/test/kotlin/io/ltverdict/core/StageProfiles.kt`, `src/test/kotlin/io/ltverdict/core/StageProposalSeriesTest.kt`

**Интерфейсы:** производит `LoadSecondSeries`, `LoadSecondSeriesRead`, `readLoadSecondSeries`; в тестах
`StageProfile.writeJtl(path: Path, t0: Long = 1767225600000)` и `StageProfile.requestsPerSecond(): LongArray`.

- [ ] **Шаг 1: генератор профилей в тестах.** `StageProfiles.kt`: `data class RatePiece(val seconds: Int, val fromRps: Double,
  val toRps: Double)`, `data class StageProfile(val name: String, val pieces: List<RatePiece>, val jitter: Double = 0.0,
  val seed: Long = 1L)`. Число запросов в секунде `s` это `floor(F(s + 1)) - floor(F(s))`, где `F` интеграл кусочно-линейной
  скорости; при `jitter > 0` скорость секунды умножается на `1 + jitter · u`, `u` из xorshift64 с `seed` в `[-1, 1]`. JTL
  пишется заголовком `fixtures/stages/ramp-steady-rampdown.jtl` (17 колонок), старты равномерно внутри секунды, `elapsed`
  100 мс, метка `GET /items`. Функции `truthSteady(): List<LongRange>` (истинные плато в миллисекундах смещения) и
  `trueRpsAt(offsetMillis: Long): Double` для проверок допуска.
- [ ] **Шаг 2: падающие тесты** в `StageProposalSeriesTest`:
  - `the run start is the earliest sample of any kind` — JTL со строкой контейнера за 30 с до первого запроса (формат строки
    контейнера как в `fixtures/slice1/jmeter/csv-tc-parent-subresults-true-5.6.3/input.jtl`: пустой `dataType`,
    `responseMessage` «Number of samples in transaction : 1, number of failing samples : 0»): `runStartEpochMillis = t0 - 30000`,
    `requestsPerSecond[0..29]` нули; тот же тест с setUp-сэмплером даёт `requestsPerSecond[0] = 1`;
  - `requests are counted by start in seconds from the run start` — три запроса со стартами `t0`, `t0 + 999`, `t0 + 1000`:
    `[2, 1, ...]`;
  - `row order does not change the series` — те же строки в обратном порядке дают равные массивы;
  - `an invalid input is refused` — JTL с нечисловым `timeStamp` в средней строке (парсер даёт `INVALID`,
    `JtlCsvParser.kt:289-292`): `Refused("INPUT_INVALID")`;
  - `a file without requests is refused` — журнал Gatling только из строк GROUP: `Refused("NO_REQUEST_SAMPLES")` (поправка при
    реализации: JTL только из строк контейнера парсер не распознаёт как контейнеры, он отмечает их только в смеси с другими
    строками);
  - `a run of exactly seven days is read and one millisecond more is refused` — две выборки с разницей 604800000 мс
    (минус `elapsed`) дают `Series` длины 604801; разница на 1 мс больше даёт `Refused("RUN_TOO_LONG")`.
- [ ] **Шаг 3: запуск, ожидается FAIL** (нет `readLoadSecondSeries`):
  `./gradlew test --tests 'io.ltverdict.core.StageProposalSeriesTest'`.
- [ ] **Шаг 4: реализация** `readLoadSecondSeries` в `StageProposal.kt`: `AcceptedInput(PROPOSAL_RUN_ID, sourceType, "",
  Files.size(source), source.fileName.toString(), source)`; первый `parseInput` копит минимум старта и максимум конца по всем
  выборкам и признак наличия запросов; проверки `INVALID`, нет запросов, длина больше `MAX_LOAD_STAGE_OFFSET_MS`; второй
  `parseInput` увеличивает `requestsPerSecond[((start - runStart) / 1000).toInt()]` для `JMETER_SAMPLER` и `GATLING_REQUEST`.
  `IOException` пробрасывается (CLI превращает в код 4). Расхождение первого и второго прохода (другой минимум) это
  `IllegalStateException("PARSER_PASS_MISMATCH")`, как в `AnalysisService`.
- [ ] **Шаг 5: запуск, ожидается PASS**, та же команда.
- [ ] **Шаг 6: коммит** `feat(core): read the per-second request series for a stage proposal`.

### Задача 2: ширина ячейки, шум, сегментация

**Файлы:** изменить `StageProposal.kt`; создать `src/test/kotlin/io/ltverdict/core/StageProposalTest.kt`.

**Интерфейсы:** внутренние функции (видимость `internal` для тестов):
`chooseCellSeconds(series: LoadSecondSeries): Int?`, `cellCounts(series: LoadSecondSeries, cellSeconds: Int): LongArray`,
`noiseCounts(counts: LongArray): Double`, `segmentCounts(counts: LongArray, noise: Double): List<IntRange>` (полуоткрытые
диапазоны индексов ячеек как `from until to`).

- [ ] **Шаг 1: падающие тесты:**
  - `the anchor fixture uses five second cells` — ряд опорной фикстуры (через `readLoadSecondSeries` по
    `fixtures/stages/ramp-steady-rampdown.jtl`): `chooseCellSeconds = 5`, `cellCounts` равен `8 × 10, 12 × 50, 3 × 10`;
  - `the cell ladder respects the cell limit` — ряд 86400 с по 1000 rps: `chooseCellSeconds = 60` (`1440 ≤ 2400`);
    ряд 604800 с по 1000 rps: `300`;
  - `a slow run has no cell width` — 0,1 rps на 3600 с: `chooseCellSeconds = null`;
  - `noise is the robust spread of first differences` — `[50, 50, 50, 90, 90, 90]`: `noiseCounts = 1.0` (порог);
    `[100, 94, 92, 92, 94, 100]` (разности `−6, −2, 0, 2, 6`, медиана 0, MAD 2) даёт `1.4826 · 2 / sqrt(2) ≈ 2.0967` с
    точностью `1e-9`;
  - `segmentation finds the anchor steps` — `segmentCounts(cellCounts(anchor, 5), 1.0) == [0 until 8, 8 until 20, 20 until 23]`;
  - `a straight ramp is one segment` — `c_k = 2k` для `k < 200`: один отрезок;
  - `segments are never shorter than three cells` — ступенька длиной две ячейки внутри плато не даёт отрезка короче трёх.
- [ ] **Шаг 2: запуск, ожидается FAIL:** `./gradlew test --tests 'io.ltverdict.core.StageProposalTest'`.
- [ ] **Шаг 3: реализация** по пунктам 1-3 раздела «Метод»; константы из таблицы как `private const val` с теми же именами.
- [ ] **Шаг 4: запуск, ожидается PASS.**
- [ ] **Шаг 5: коммит** `feat(core): segment the request series into linear pieces`.

### Задача 3: классификация, стадии, отказы, уверенность (`detectLoadStages`)

**Файлы:** изменить `StageProposal.kt`, `LoadStages.kt` (`private fun stagesJson` на `internal`), `StageProposalTest.kt`,
`StageProfiles.kt` (профили из раздела «Фикстуры»).

**Интерфейсы:** производит `detectLoadStages`, `proposeLoadStages`, `StageProposal.proposalBytes()`, `STAGE_PROPOSAL_METHOD`.

- [ ] **Шаг 1: падающие тесты по таблице фикстур** (по тесту на строку, имена из колонки «Тест»), с допуском раздела «Допуск
  границ», плюс:
  - `the anchor fixture proposal equals the declared example` — `proposalBytes()` опорной фикстуры равны
    `validateLoadStages(ramp-steady-down.json).canonicalBytes()`; `confidence = HIGH`; `boundaries` у `steady`
    `fromContrastSigma = 40.0`, `trimmedCells = 0`;
  - `every proposal round-trips through the validator` — для каждого `DETECTED` профиля `validateLoadStages(bytes)` это
    `Valid`, и его `canonicalBytes()` равны `bytes`;
  - `the proposal is deterministic` — `detectLoadStages` дважды на одном ряде даёт равные `StageProposal` и байты;
  - `a single plateau is named steady and several are steps` — профили F1 и F2: id `steady` и `step-1`..`step-5`;
  - `the steady steps mode changes only roles` — F2 в режиме `all`: роль `steady` у `step-1`..`step-5`; в режиме `top`:
    `steady` только у `step-5`, остальные `excluded`, границы всех стадий те же; F0 и F1: байты предложения в обоих режимах
    равны;
  - `refusals carry their reason` — F4 `NO_PLATEAU` (`detail` начинается с `steady_share=`), F6 `RUN_TOO_SHORT`, F8
    `IDLE_GAP_INSIDE_RUN`, F11 `TOO_MANY_STAGES` (`detail = "stages=18"`), ряд из нулей `NO_REQUEST_SAMPLES`, 0,1 rps
    `RATE_TOO_LOW`; у всех `stages` пусто и `proposalBytes()` бросает `IllegalStateException`.
- [ ] **Шаг 2: запуск, ожидается FAIL:** `./gradlew test --tests 'io.ltverdict.core.StageProposalTest'`.
- [ ] **Шаг 3: реализация** пунктов 4-13 раздела «Метод». `proposeLoadStages` вызывает `readLoadSecondSeries` и при `Refused`
  возвращает `StageProposal` со статусом `NOT_DETECTED` и причиной, иначе `detectLoadStages`.
- [ ] **Шаг 4: запуск, ожидается PASS**; затем полный `./gradlew test --tests 'io.ltverdict.core.*Stage*'` (старые тесты
  стадий проходят без правок).
- [ ] **Шаг 5: коммит** `feat(core): propose load stages from the request series`.

### Задача 4: команда `ltv stages detect`

**Файлы:** создать `src/main/kotlin/io/ltverdict/cli/CliStages.kt`, `src/test/kotlin/io/ltverdict/cli/CommandLineStageDetectTest.kt`;
изменить `src/main/kotlin/io/ltverdict/cli/CommandLine.kt` (`:89-101` ветка `"stages" -> stagesCommand(args.drop(1), stdout, stderr)`,
`:951-976` строка `ltv stages detect <input> [--out <file>] [--steady-steps top|all]` перед `ltv policy validate`, `:987` `EXIT_NO_VERDICT` на `internal`).

**Интерфейсы:** `internal fun stagesCommand(args: List<String>, stdout: PrintStream, stderr: PrintStream): Int`,
`internal fun stageProposalLines(proposal: StageProposal): List<String>`.

- [ ] **Шаг 1: падающие тесты** (запуск через `runCli`, как в `CommandLineStagesTest`):
  - `help names the detect command` — `--help` содержит `ltv stages detect <input> [--out <file>] [--steady-steps top|all]`;
  - `the steady steps mode is printed and only flips roles` — опорная фикстура с `--steady-steps all` и без флага: stdout
    побайтно равны, stderr отличается только значением `steady_steps=`;
  - `the anchor fixture prints the example declaration` — stdout равен каноническим байтам `ramp-steady-down.json`, код 0,
    stderr равен блоку строк раздела «Контракт и вывод» (строка в строку);
  - `the proposal drives the analysis to the plateau` — предложение из stdout записано в файл, `ltv analyze` опорной фикстуры
    с `--stages` этим файлом и политикой руководства (`p95 lte 250`, `throughput_rps gte 9`): код 0, `window_metric_summary`
    окна `steady` с `sample_count = 600`, `latency_ms.p95 = 194`, `throughput_rps = {600000, 60000}`;
  - `an early setUp sample becomes a lead-in and the window stays on the plateau` — фикстура с выборкой setUp за 30 с
    (строка руководства `1767225570000,50,setUp,...`): stdout содержит `lead-in` 0-30000 и `steady` 70000-130000, stderr
    строку `warning code=LEAD_IN_IDLE`, `confidence=MEDIUM`; анализ с предложением даёт `PASS`, 600 выборок, p95 194;
  - `a steady to the end is clipped by the analysis` — профиль без остановки (F1 без последнего куска): анализ с предложением
    не падает с `STAGE_OUTSIDE_RUN`, у `steady` `clipped_to_run_end = true`;
  - `row order does not change the proposal` — опорная фикстура с переставленными строками данных (обратный порядок): stdout
    и stderr побайтно равны исходным;
  - `a refusal writes nothing and exits 3` — профиль F4 в `@TempDir`: код 3, stdout пуст, файл `--out` не создан, первая
    строка stderr `stage_proposal status=NOT_DETECTED method=rps-plateau-segmentation.v1 reason=NO_PLATEAU`;
  - `--out writes the file and refuses to overwrite` — первый запуск создаёт файл с байтами предложения и пустым stdout;
    повтор даёт код 4 и `STAGES_OUT_EXISTS`, файл не изменён; `--out` на ссылку тоже `STAGES_OUT_EXISTS`;
  - `usage errors exit 64` — `ltv stages`, `ltv stages detect`, `ltv stages validate x`, повтор `--out`, лишний аргумент,
    `--steady-steps middle`, повтор `--steady-steps`;
  - `a missing or unsupported input exits 4` — нет файла: stderr `INVALID_INPUT`; текстовый файл не JTL: stderr
    `UNSUPPORTED_INPUT` (литерал `FormatDetector.kt:135`);
  - `the gatling log is accepted` — `fixtures/slice0/simulation.log`: код 4 и `reason=INPUT_INVALID`, как у `ltv analyze`
    (поправка при реализации: строки REQUEST этой фикстуры Slice 0 не в формате парсера); журналы
    `fixtures/slice1/gatling/text-3.12.0` и `binary-3.15.1`: код 0 или 3, первая строка stderr начинается с `stage_proposal status=`;
  - `the command takes no data directory` — `--data-dir` не принимается (код 64); `CliStages.kt` не обращается к
    `DataDirectory` (проверяется ревью диффа).
- [ ] **Шаг 2: запуск, ожидается FAIL:** `./gradlew test --tests 'io.ltverdict.cli.CommandLineStageDetectTest'`.
- [ ] **Шаг 3: реализация.** `requireRegularFile(input, EXIT_INVALID_INPUT, "INVALID_INPUT")`, `detectSource`
  (`IllegalArgumentException` даёт код 4 с сообщением ошибки), `proposeLoadStages`; строки stderr печатаются всегда, затем
  при `DETECTED` байты в stdout или в `--out` (проверка `Files.exists(out, NOFOLLOW_LINKS)` до чтения входа, запись
  `CREATE_NEW`, ошибка записи `STAGES_OUT_WRITE_FAILED`, код 4). Числа форматируются
  `BigDecimal(value).setScale(3, RoundingMode.HALF_EVEN).toPlainString()`.
- [ ] **Шаг 4: запуск, ожидается PASS;** затем `./gradlew test --tests 'io.ltverdict.cli.*'`.
- [ ] **Шаг 5: коммит** `feat(cli): add ltv stages detect`.

### Задача 5: документация и журнал PR 1

**Файлы:** `docs/user/slice-1-local-analysis.md`, `README.md`, `docs/architecture/slice-1-local-runtime.md`,
`docs/adr/0030-load-stages-steady-window.md`, `docs/adr/0036-load-stage-proposal.md`, `changelog.d/w4-1-stage-proposal-cli.added.md`.

- [ ] **Шаг 1: руководство.** В разделе «Стадии нагрузки и вердикт по окну steady» заменить фразу «Автоопределения плато нет:
  окна задаёте вы, ядро их не угадывает» (`:2107-2108`) на «Ядро окна не угадывает: стадии всегда задаёте вы; черновик можно
  получить командой `ltv stages detect`». Новый подраздел «Предложение стадий: `ltv stages detect`» после «Запуск из CLI»:
  команда, вывод на опорной фикстуре (stdout и stderr из теста), коды выхода, таблица причин отказа и предупреждений,
  объяснение уверенности (`calibrated=false`), правило D4 (падение не вырезается), порядок работы D9 («определить один раз,
  сохранить файл рядом со сценарием, переиспользовать»), пример с setUp (`lead-in`) со ссылкой на «Ловушка начала прогона».
  В «Ограничения» (`:2385`) заменить первую строку. В разделе «CLI» (`:2455-2463`) добавить строку команды.
- [ ] **Шаг 2: README.** У строк про стадии (`:19-22`, `:113-117`) одна фраза и команда `ltv stages detect`.
- [ ] **Шаг 3: архитектура.** `docs/architecture/slice-1-local-runtime.md:21`: добавить `ltv stages detect` в перечень команд.
- [ ] **Шаг 4: ADR.** В ADR 0030, раздел «Следствия», строка: «Подсказка границ реализована как предложение
  `ltv stages detect`, см. ADR 0036» (ссылкой на `0036-load-stage-proposal.md`) и «анализ по-прежнему не угадывает окна». В ADR 0036
  раздел «Реализовано» с номером PR (статус не меняется без слова владельца).
- [ ] **Шаг 5: фрагмент** `changelog.d/w4-1-stage-proposal-cli.added.md`, одна запись по `changelog.d/README.md`: команда,
  что она выводит, что анализ и identity не меняются, ограничение «константы не откалиброваны».
- [ ] **Шаг 6: проверки.** Команды руководства прогнать вручную на сборке (`./gradlew installDist`, затем
  `build/install/ltv/bin/ltv stages detect fixtures/stages/ramp-steady-rampdown.jtl`) и сверить вывод с текстом;
  `npx markdownlint-cli2 docs/user/slice-1-local-analysis.md docs/adr/0030-load-stages-steady-window.md docs/adr/0036-load-stage-proposal.md README.md changelog.d/*.md`;
  `python tools/changelog_assemble.py --check`; полный `./gradlew check` (один раз, через `Invoke-LtvExclusive`).
  Ожидается: всё зелёное, `git status` показывает только файлы блока AGENTS.md.
- [ ] **Шаг 7: коммит** `docs(stages): document ltv stages detect`.

## PR 1b: режим `--like` (после слияния PR 1; набросок, ADR 0036, D13)

### Задача 5b: `ltv stages detect <input> --like <load-stages.json>`

Одобрено владельцем 2026-11-19 следующим срезом; в PR 1 не реализуется. Перед началом нужен подробный план задачи по образцу
задач 1-5 (тесты, команды, ожидания).

**Цель:** проверить, подходит ли уже принятое объявление профиля этому прогону, и при совпадении вывести его байты без
изменений (тот же `load_stages_sha256`, ADR 0030 R6), а не новое предложение, которое может сдвинуться на ячейку (D9).

- Вход: прогон и действующий `load-stages.v1` (через `validateLoadStages`, ошибки как у `--stages`, код 4).
- Проверка: предложение по прогону строится как в PR 1; объявление подходит, если число и порядок стадий-ступеней совпадают
  и каждая граница стадии `steady` объявления лежит в допуске от границы предложения (кандидат допуска: `max(W, EDGE_TRIM_MAX_CELLS · W)`,
  уточняется в плане задачи), а окна `steady` объявления не захватывают отрезки `RISING` и `IDLE` предложения.
- Вывод: подходит — stdout это канонические байты объявления (не предложения), stderr строка
  `stage_proposal status=MATCHED ...` и расхождения границ в мс; не подходит — код `3`, причина (`LIKE_SHAPE_DIFFERS`,
  `LIKE_BOUNDARY_SHIFTED` с величиной сдвига), stdout пуст, предложение по прогону печатается в stderr для справки.
- Тесты: опорная фикстура с `ramp-steady-down.json` (подходит, байты равны файлу); F1 со сдвинутым на 2 W объявлением
  (подходит), на 10 W (не подходит); F2 с объявлением для F1 (форма другая); детерминизм; `--like` вместе с `--out`.
- Документация: раздел руководства «Предложение стадий» (порядок D9 через `--like`), фрагмент журнала, ADR 0036 «Реализовано».

## PR 2: маршрут API и UI (после слияния PR 1)

### Задача 6: маршрут `POST /api/runs/{runId}/stage-proposal`

**Файлы:** изменить `src/main/kotlin/io/ltverdict/web/RunRoutes.kt`, `src/main/kotlin/io/ltverdict/web/LocalApi.kt`
(поле `stageProposalPermit = Semaphore(1)` рядом с `:53-54`), `src/main/kotlin/io/ltverdict/core/StageProposal.kt`
(`internal fun stageProposalJson(proposal: StageProposal): JsonObject`); создать
`src/test/kotlin/io/ltverdict/web/LocalApiStageProposalTest.kt`.

**Интерфейсы:** ответ `200`:
`{status, reason, detail, method, confidence, calibrated:false, run_start_epoch_ms, run_end_epoch_ms, cell_ms, noise_rps,
segments:[{from_offset_ms,to_offset_ms,kind,level_rps,drift,residual_cv}], boundaries:[{stage_id,basis,level_rps,
from_contrast_sigma,to_contrast_sigma,trimmed_cells}], warnings:[{code,stage_id,at_offset_ms,from_rps,to_rps,duration_ms}],
proposal: <объект load-stages.v1> | null}`; числа это JSON-числа с тремя знаками (`HALF_EVEN`), отсутствующие значения `null`.

- [ ] **Шаг 1: падающие тесты** (по образцу `LocalApiStagesTest`): `a stored run gets a proposal` (опорная фикстура,
  `proposal` равен объекту примера, `confidence = HIGH`); `a refusal is a 200 with a reason` (F4, `status = NOT_DETECTED`,
  `proposal = null`); `an unknown run is 404`; `a second request while one runs is 409 BUSY` (разрешение занято вручную в
  тесте); `the route needs the CSRF header` (без `X-LTV-CSRF` даёт 403); `the proposal is not stored` (после вызова в
  каталоге прогона нет новых файлов).
- [ ] **Шаг 2: запуск, ожидается FAIL:** `./gradlew test --tests 'io.ltverdict.web.LocalApiStageProposalTest'`.
- [ ] **Шаг 3: реализация.** `requireInput(runId)` в `Dispatchers.IO`, `NoSuchElementException` даёт `notFound`;
  `tryAcquire()` или `conflict("BUSY", "Stage proposal is already running")`; `proposeLoadStages` в `Dispatchers.IO` с
  `release` в `finally`; ответ `respondJson(stageProposalJson(...))`.
- [ ] **Шаг 4: запуск, ожидается PASS.**
- [ ] **Шаг 5: коммит** `feat(api): propose load stages for a stored run`.

### Задача 7: поле стадий и кнопка в форме нового анализа

**Файлы:** `ui/src/api.ts` (`proposeStages(runId: string): Promise<StageProposalResponse>`, параметр `stages?: Blob | null` у
`createJob`, часть `stages` с именем файла `load-stages.json`), `ui/src/types.ts` (тип ответа), `ui/src/shell/NewAnalysisPanel.vue`
(поле `textarea` `data-testid="stages-json"`, кнопка `data-testid="stages-propose"`, блок объяснения
`data-testid="stages-explanation"`), `ui/src/App.vue` (проводка), подписи формы (`ui/src/shell/setup.ts` или соседний файл
подписей); создать `ui/e2e/stage-proposal.spec.ts`.

Поведение (решения U1-U3):

- **U1.** Кнопка загружает выбранный вход тем же `uploadInput` (загрузка идемпотентна по хэшу), запоминает пару «файл,
  `run_id`» и вызывает `proposeStages`. Запуск анализа с тем же объектом `File` не загружает вход второй раз.
- **U2.** При `DETECTED` поле заполняется предложением в читаемом виде (`JSON.stringify(proposal, null, 2)`), показываются
  уверенность, предупреждения и границы по-русски (подписи по кодам, текст строит UI). При `NOT_DETECTED` поле не меняется,
  показывается причина. Поле остаётся редактируемым; анализ запускает только кнопка запуска.
- **U3.** Непустое поле отправляется частью `stages`; ошибки `422 INVALID_STAGES` показываются как ошибки политики
  (`json_pointer` и сообщение). Пустое поле означает анализ без стадий (поведение прежнее).

- [ ] **Шаг 1: падающий e2e** `ui/e2e/stage-proposal.spec.ts`: выбрать опорную фикстуру, нажать «Предложить стадии», поле
  содержит `"id": "steady"` и `40000`, видна уверенность; запустить анализ, карточка вердикта показывает фразу «по окну steady»;
  отказ на F4 (файл из `@TempDir` не годится для Playwright, поэтому для отказа маршрут подменяется `page.route` с ответом
  `NOT_DETECTED`) показывает причину и не меняет поле.
- [ ] **Шаг 2: запуск, ожидается FAIL:** `Invoke-LtvE2E { npm --prefix ui run e2e -- stage-proposal.spec.ts }`.
- [ ] **Шаг 3: реализация** по U1-U3.
- [ ] **Шаг 4: запуск, ожидается PASS;** `npm --prefix ui run typecheck`, `npm --prefix ui run lint`, существующий
  `local-flow.spec.ts` без правок.
- [ ] **Шаг 5: коммит** `feat(ui): propose load stages in the new analysis form`.

### Задача 8: документация и журнал PR 2

- [ ] Руководство: вкладка «Новый анализ» (поле стадий и кнопка), раздел стадий (`:2263-2264` и «Ограничения»: форма теперь
  есть), раздел «API» (маршрут и ответ); `docs/architecture/slice-1-local-runtime.md:330-343` строка маршрута; ADR 0030 у R12
  пометка «снято ADR 0036, D10, по решению владельца <дата>»; ADR 0036 «Реализовано»; фрагмент
  `changelog.d/w4-1-stage-proposal-ui.added.md`.
- [ ] Проверки: markdownlint, `python tools/changelog_assemble.py --check`, полный `./gradlew check`, e2e.
- [ ] Коммит `docs(stages): document the stage proposal in the UI`.

## Фикстуры

Профили строятся `StageProfiles.kt` (скорость кусочно-линейная, rps); закоммиченная фикстура одна, существующая опорная.

| № | Тест | Профиль (длительность в с: rps) | Ожидание |
| --- | --- | --- | --- |
| F0 | `anchor fixture` | `fixtures/stages/ramp-steady-rampdown.jtl` | байты `ramp-steady-down.json`, `HIGH` |
| F1 | `ramp steady ramp-down` | 120: 0→100; 600: 100; 60: 100→0 | `ramp-up`, `steady`, `ramp-down`; `HIGH` |
| F2 | `capacity steps` | 30: 0→20; 300: 20; 30: 20→40; 300: 40; ...; 300: 100; 30: 100→0 | `step-1`..`step-5`, переходы зазорами, 7 стадий |
| F3 | `soak with a mid-run drop` | 60: 0→50; 2340: 50; 1200: 35; 60: 35→0 | одна `steady` 60-3600 с, `LOAD_DROP_INSIDE_STEADY` около 2400 с, `LOW` |
| F4 | `continuous ramp` | 1200: 0→200 | `NO_PLATEAU` |
| F5 | `noisy plateau` | F1 с `jitter = 0.15`, `seed = 42` | `steady` в допуске для шума |
| F6 | `very short run` | 45: 50 | `RUN_TOO_SHORT` |
| F6b | `short but valid run` | 20: 0→60; 50: 60; 20: 60→0 | `steady`, длина не меньше 30 с |
| F7 | `setUp lead-in` | F0 с выборкой setUp за 30 с | `lead-in` 0-30000, `LEAD_IN_IDLE`, `MEDIUM` |
| F8 | `two tests in one file` | 60: 0→100; 300: 100; 60: 0; 300: 100; 60: 100→0 | `IDLE_GAP_INSIDE_RUN` |
| F9 | `steady to the end` | 60: 0→80; 600: 80 | `steady` до конца, конец округлён вверх |
| F10 | `long decline at the end` | 60: 0→100; 600: 100; 600: 100→0 | `ramp-down` 600 с, `LONG_RAMP_DOWN`, `LOW` |
| F11 | `too many steps` | 10: 0→10; 16 ступеней по 60 с с уровнями 10, 20, ..., 160 и переходами 10 с; 10: 160→0 | `TOO_MANY_STAGES`, `detail = "stages=18"` |
| F12 | `warm-up below half` | 120: 30; 30: 30→100; 1200: 100; 60: 100→0 | прогрев в `ramp-up`, одна `steady` |
| F13 | `a long soak after a decline is its own stage` | 1800: 0→24; 7200: 24; 1800: 24→36; 5400: 36; 1800: 36→23,0; 10800: 23,0→20,0 | `step-1`..`step-3`, спад зазором с `LOAD_DECLINE_BETWEEN_STEPS`; `all`: все `steady`, `STEADY_DRIFT` у `step-3`; `top`: `steady` только `step-2`, `HOLD_NOT_EVALUATED`, `LOW` |
| F13a | `a soak just under the hold drift cap ...` / `... over the hold drift cap ...` | F13 с дрейфом soak 0,22 и 0,30 | 0,22: `step-3` есть; 0,30: soak в последней `steady` с `LOAD_DROP_INSIDE_STEADY`, `LOW`, не в `ramp-down` |

### Допуск границ

`T` это истинная граница плато по генератору, `W` ширина ячейки, `D` длина истинного плато.

- **Без шума (F0-F2, F6b, F7, F9, F11, F12):** начало steady в `[T - W, T + 2W]`, конец в `[T_end - 2W, T_end + W]`, и
  каждая ячейка внутри steady имеет истинный уровень (`trueRpsAt` в середине ячейки) в пределах 5 % уровня своего плато
  (у профиля ступеней это уровень ступени).
- **С шумом (F5):** начало в `[T - W, T + max(3W, 0,02 D)]`, конец в `[T_end - max(3W, 0,02 D), T_end + W]`, истинный уровень
  каждой ячейки steady в пределах 10 % уровня плато.
- **Пологий склон (F5, F10), поправка при реализации PR 1:** ячейки склона в пределах шума плато неотличимы от него, поэтому
  наружный допуск равен внутреннему, `max(3W, 0,02 D)`; проверка истинного уровня каждой ячейки остаётся. Замер: F5 начало
  −7 с, конец +3 с; F10 конец +3 с.
- **С падением (F3, F10), правило D4:** правило 5 % не применяется, потому что падение остаётся в steady намеренно. F3: одна
  стадия steady, начало в `[60 с - W, 60 с + 2W]`, конец в `[3600 с - 2W, 3600 с + W]`, момент падения 2400 с лежит внутри
  неё, предупреждение `LOAD_DROP_INSIDE_STEADY` с `at_offset_ms` в `[2400000 - 2W, 2400000 + W]` (поправка при реализации:
  момент это начало переходного отрезка не короче трёх ячеек; замер −3 с при `W` = 2 с). F10: steady в
  `[60 с - W, 660 с + W]` по тем же правилам начала и конца, `ramp-down` начинается в `[660 с - 2W, 660 с + W]`.

## Риски

1. **Неоткалиброванные константы.** Метод проверен только на синтетике и одной реальной форме; на корпусе возможны ложные
   ступени (автокоррелированный шум генератора) и пропуски. Смягчение: предложение всегда подтверждает человек, константы и
   факты печатаются, ADR остаётся Proposed до прогона на корпусе.
2. **Сопоставимость.** Предложение для каждого прогона может отличаться на ячейку, и ключ сопоставимости разведёт прогоны
   (ADR 0030 R6). Смягчение: порядок работы D9 в руководстве; режим `--like` (PR 1b).
3. **Смесь ролей у ступеней ёмкости.** Правило п. 5 исключает короткие низкие ступени; профиль с удлинённой последней ступенью
   получит нижние ступени как разгон. Режим `--steady-steps` (D12) решает роли отобранных ступеней, но не это правило.
4. **Деградация, похожая на остановку.** Плавное умирание в конце исключается как `ramp-down` (только предупреждение).
   Ошибки в хвосте метод не смотрит; это кандидат на следующий срез.
5. **Стоимость на больших входах.** Два полных чтения (вход до 4 GiB); в UI запрос длится столько же, сколько разбор при
   анализе. Смягчение: вне event loop, одно вычисление за раз, `409 BUSY`.
6. **Граница опорной фикстуры по длине прогрева.** 40 с против порога 45 с: изменение `STEADY_DURATION_RATIO` ломает опорный
   тест, это и есть его назначение.
7. **Формат контейнера JMeter в тестовом JTL.** Если парсер распознаёт контейнер иначе, тест задачи 1 использует setUp-сэмплер
   (оба случая покрывают «раннюю постороннюю выборку»).

## Открытые вопросы владельцу

Решены владельцем 2026-11-19: заморозка D1 (явное исключение), роли ступеней (два режима, D12), код выхода `3`, `--like`
(PR 1b). Остаются:

1. Умолчание `--steady-steps`: исполнитель выбрал `top` (окно из ступеней разного уровня делает перцентили бессмысленными).
   Подтверждаете или нужно `all`?
1a. Нижняя ступень после наивысшей (soak) в режиме `top`: сейчас `excluded` с `HOLD_NOT_EVALUATED` и `LOW`. Рекомендация
   исполнителя (D4): `steady` наивысшая ступень и все после неё. Какой вариант?
2. Поле стадий в форме UI снимает ADR 0030 R12. Подтверждаете? Достаточно ли поля JSON без загрузки файла с диска?
3. Есть ли корпус реальных прогонов (в том числе со ступенями и падениями), на котором можно прогнать метод до перевода ADR в
   Accepted?
