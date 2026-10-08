# План W2.1: типизированная граница (первый срез)

**Дата:** 2026-10-08. **Ветка:** `refactor/typed-boundary` (от `origin/main` @ `42f7c73`).
**Пункт перечня:** W2.1 (`docs/superpowers/plans/2026-10-08-review-work-plan.md`), основание: архитектура R3, R5;
`AdvisoryAi.kt:65`. **Путь brainstorming:** `refactor/`-PR по явному списку владельца (исключение «бюджет
консолидации» в `AGENTS.md`); требования согласованы владельцем, вопросов владельцу нет, спорное решено ниже
записями «Ruling».

**Статус плана: ЧАСТИЧНОЕ выполнение W2.1.** Полный W2.1 (типизировать каждый вариант findings/evidence,
все ответы HTTP и saved-analytics, заменить рукописный `types.ts` целиком) по размеру не помещается в один
`refactor/`-PR под условиями исключения (см. «Размер полной работы»). Этот PR берёт первый срез, который
закрывает пункт `AdvisoryAi.kt:65` и даёт компилятору держать форму верхнего уровня `analysis-result` и
`analysis-identity`. Остаток явно перечислен в «Не входит» и вернётся оркестратору отдельными срезами.

## Исключение «бюджет консолидации»: условия

1. Нет изменений поведения, публичных контрактов и схем: байты `analysis-result.json` и
   `analysis-identity.json`, `analysis_id`, коды ошибок, форма HTTP не меняются. Доказательство: тест
   байтовой эквивалентности (ниже).
2. Существующие тесты не редактируются и не удаляются; добавляются новые файлы тестов.
3. Одна цель: W2.1.
4. Канонический JSON (`canonicalJson`, `canonicalDecimal`, `sha256Hex`) и вычисление хэшей identity не
   затрагиваются: типизированная модель кодируется в `JsonElement` и уходит в прежний `canonicalJson`.
5. Блок REQUESTED / REQUIRED с перечнем символов приведён ниже до реализации.

Примечание: в основном рабочем дереве `AGENTS.md` изменён и не закоммичен, но раздел «бюджет консолидации»
есть и в `origin/main` (строка 47), то есть исключение в силе.

```text
REQUESTED:
  Дословно из перечня владельца (W2.1): «@Serializable для analysis-result, identity, saved-analytics и
  ответов HTTP; генерация ui/src/types.ts из Kotlin; schema_version по набору поддерживаемых версий вместо
  == v1; fixture bundle текущей версии в тестах. Канонический хэш-путь не трогать». Критерий: «Дрейф формы
  документа ловит компилятор; старые bundle читаются тестом».
  Уточнение оркестратора: поле incidents (ADR 0029) в этой работе НЕ вводится, но проверка в AdvisoryAi
  должна расширяться на него одной строкой (четыре сочетания incidents и capacity_summary);
  произвольные неизвестные поля разрешать нельзя.

REQUIRED TO ACHIEVE IT (этот PR, срез 1):
  Новый файл core/AnalysisDocuments.kt (internal), символы:
    - AnalysisResultDocument (@Serializable): schema_version, run_id, analysis_mode, run_validity,
      policy_verdict, analysis_coverage, findings, evidence, capacity_summary?
    - AnalysisCoverageDocument (@Serializable): status, reasons
    - AnalysisIdentityDocument (@Serializable) и вложенные: EngineRef, ParserRef, ModuleRef,
      InputVersionsDocument, OutputsDocument, HistogramDocument, NormalizationDocument; limits и
      verdict_gates как Map<String, String>
    - SUPPORTED_ANALYSIS_RESULT_VERSIONS (набор поддерживаемых версий) и
      hasSupportedAnalysisResultKeys(JsonObject): ключи документа это все обязательные поля модели плюс
      любое подмножество необязательных (имена берутся из SerialDescriptor модели, а не из рукописного списка)
    - ANALYSIS_DOCUMENT_JSON: один экземпляр Json (explicitNulls=false, encodeDefaults=true) только для
      кодирования; декодирование в production-коде не используется
  core/AnalysisResult.kt: analysisIdentity(...) и analysisResult(...) собирают документы выше и кодируют их
    через ANALYSIS_DOCUMENT_JSON.encodeToJsonElement в прежний canonicalJson. Сигнатуры не меняются.
    verdictGates() и limits() возвращают Map<String, String> вместо JsonObject (порядок ключей не важен:
    canonicalJson сортирует).
  ingest/LoadSample.kt (RunValidity) и core/Policy.kt (PolicyVerdict): добавляется @Serializable к enum;
    core/AnalysisResult.kt (AnalysisMode): @Serializable и @SerialName на значениях.
  ai/AdvisoryAi.kt: строки 65 и 568. ANALYSIS_RESULT_FIELD_SETS и сравнение == "analysis-result.v1"
    заменяются на hasSupportedAnalysisResultKeys и SUPPORTED_ANALYSIS_RESULT_VERSIONS. Значения полей
    проверяются теми же вспомогательными функциями, что и сейчас (те же коды INVALID_OUTPUT/INVALID_ANALYSIS);
    ai-evidence строится из исходного JsonObject (его байты входят в evidence_input_sha256).
  Генератор TypeScript в тестовых исходниках (без production-зависимостей): обход SerialDescriptor,
    вывод в ui/src/types.generated.ts. ui/src/types.ts НЕ меняется (уточнение при реализации): соответствие
    рукописного AnalysisResult сгенерированному (те же ключи, значения enum принимаются) проверяет скрипт
    ui/scripts/verify-generated-types.mjs на виртуальном файле, без кода в бандле UI.
  Тесты (только новые файлы):
    - AnalysisDocumentsEquivalenceTest: эталон прежних билдеров (дословная копия старого кода в тестовых
      исходниках) против новых функций, байты равны на матрице входов; золотые файлы
      fixtures/slice1/identity/* читаются через строгое декодирование и кодируются обратно в те же байты
      (в том числе legacy-pre-adr-0016.v1.json).
    - TypedBoundaryGoldenBytesTest (пакет cli, запускает CLI): фикстуры fixtures/typed-boundary/golden/* (analysis-result.json и
      analysis-identity.json, снятые ДО рефакторинга по CLI-прогонам fixtures/slice1/*) равны байтам новых
      функций.
    - AdvisoryAnalysisResultGateTest: дифференциальный тест старого предиката (копия: два набора ключей и
      сравнение версии) и нового на матрице документов (все подмножества ключей, лишний ключ, чужая версия,
      явный null в capacity_summary, неверные типы значений), с одинаковым исходом: принят, либо тот же
      AdviceFailure; принятый документ строится без ошибок (код после ворот не менялся; байты ai-evidence и evidence_input_sha256 отдельным тестом не закреплены, так как код их построения этим PR не затронут). Набор ключей, который
      новый код выводит из модели, закреплён тестом (8 обязательных + capacity_summary).
    - Fixture bundle текущей версии: fixtures/typed-boundary/bundle-v1 (каталог анализа целиком в реальной
      раскладке хранилища: manifest.json, analysis-result.json, identity.json, run.json и прочие артефакты
      CLI-прогона), читается через RunBundleStore.readVerifiedAnalysis и строго декодируется в типизированные
      модели (декодирование только в тесте).
    - TypeScriptGeneratorTest: вывод генератора равен закоммиченному ui/src/types.generated.ts; обновление
      по переменной окружения LTV_UPDATE_GENERATED_TYPES=1; отдельные проверки отображения типов (enum в
      сериализованные имена, Map<String,String> в Record<string,string>, JsonObject в Record<string,unknown>,
      обязательное/необязательное/nullable).
    - ui/scripts/verify-generated-types.mjs (в npm run test:contracts): золотые analysis-result и identity
      как литералы, присвоенные сгенерированным типам, проходят tsc; отрицательные случаи (@ts-expect-error:
      лишнее поле, неверное значение enum, пропущенное обязательное поле) обязаны давать ошибку, чтобы тип
      не был слишком широким.
  changelog.d/w2-1-typed-boundary.changed.md.

NOT REQUIRED (остаётся в W2.1, вне этого PR; отчёт оркестратору):
  - Типизированные варианты findings (JsonObject) и 19 вариантов evidence (36 мест `put("type", ...)` в 13
    файлах core/sources). В этом срезе findings, evidence и capacity_summary остаются JsonObject.
  - Ответы HTTP: LocalApi.kt 2718 строк, 50 мест buildJsonObject, 73 маршрута. W2.2 разрежет этот файл
    следующим PR; типизация ответов до него даст гарантированный конфликт слияния.
  - Saved-analytics (run-dynamics.v1, transaction-comparison.v1 в RunComparison.kt), baseline, release,
    manifest, run.json, pod-view, source-request: остаются JsonObject.
  - Замена рукописных 884 строк ui/src/types.ts: файл не меняется.
  - Поле incidents, схема incident.v1, любой новый функционал (заморозка ширины D1).
  - Изменение поведения валидации AdvisoryAi: ни приём, ни коды отказа не меняются (Ruling R3).
  - Новые зависимости Gradle/npm, правка build.gradle.kts, eslint-конфигурации.

EXPECTED FILES TO CHANGE:
  src/main/kotlin/io/ltverdict/core/AnalysisDocuments.kt (новый)
  src/main/kotlin/io/ltverdict/core/AnalysisResult.kt
  src/main/kotlin/io/ltverdict/core/Policy.kt (одна аннотация)
  src/main/kotlin/io/ltverdict/ingest/LoadSample.kt (одна аннотация)
  src/main/kotlin/io/ltverdict/ai/AdvisoryAi.kt
  src/test/kotlin/io/ltverdict/core/AnalysisDocumentsEquivalenceTest.kt (новый)
  src/test/kotlin/io/ltverdict/cli/TypedBoundaryGoldenBytesTest.kt (новый)
  src/test/kotlin/io/ltverdict/core/TypeScriptGenerator.kt (новый, генератор TS из SerialDescriptor)
  .gitattributes (одна строка: fixtures/typed-boundary/** -text, байты фикстур не нормализуются)
  src/test/kotlin/io/ltverdict/ai/AdvisoryAnalysisResultGateTest.kt (новый)
  src/test/kotlin/io/ltverdict/core/TypedBoundaryBundleTest.kt (новый)
  src/test/kotlin/io/ltverdict/core/LegacyAnalysisDocuments.kt (новый, замороженная копия прежних билдеров для эталона)
  src/test/kotlin/io/ltverdict/core/TypeScriptGeneratorTest.kt (новый)
  fixtures/typed-boundary/** (новые: golden/<случай>/, bundle-v1/ в плоской раскладке)
  ui/src/types.generated.ts (новый, генерируемый)
  ui/scripts/verify-generated-types.mjs (новый), ui/package.json (скрипт test:contracts)
  changelog.d/w2-1-typed-boundary.changed.md (новый)
  docs/superpowers/plans/2026-10-08-w2-1-typed-boundary.md (этот план)
```

## Размер полной работы (почему это срез)

- `LocalApi.kt`: 2718 строк, 50 `buildJsonObject`, 73 маршрута; W2.2 (следующий в очереди) переносит эти
  функции целиком.
- evidence: 19 вариантов в `ui/src/types.ts` (строки 182-564), 36 мест `put("type", ...)` в 13 файлах
  (`Policy.kt`, `AnalysisService.kt`, `DiagnosticAnalysis.kt`, `ResourceStatistics.kt`, `TrendAnalysis.kt`,
  `WindowPolicy.kt`, `SourceAnalysis.kt`, `SourceConfig.kt` и другие).
- 545 `JsonObject` в core (ревью), 0 `@Serializable` до этого PR.
- `ui/src/types.ts`: 884 строки руками.
- Полный объём оценивается в 60+ новых типов, перепись продьюсеров в 13+ файлах и четыре отдельных PR; без
  изменения байтов каждый из них требует своей матрицы эквивалентности. Это «заметно больше ожидаемого»
  (правило 10), поэтому PR сужен, а граница проведена открыто.

## Что выяснено фактами (до реализации)

- Идентичность и результат строятся вручную `buildJsonObject` в `AnalysisResult.kt` и канонизируются
  `canonicalJson` (ключи сортируются, числа идут через `BigDecimal`). Порядок полей модели поэтому не влияет
  на байты; `Double` в модели недопустим (изменил бы `canonicalDecimal`).
- Все значения identity (`limits`, `histogram`, `normalization`, `version`) сегодня строки; остаются
  `String`. Число ключей `limits` зависит от входов (24 у legacy, 42 с ресурсами), поэтому `limits` и
  `verdict_gates` это `Map<String, String>`.
- `AdvisoryAi.kt` принимает результат ровно двух наборов ключей (8 базовых или 8 + `capacity_summary`) и
  `schema_version == "analysis-result.v1"`. Явный `"capacity_summary": null` сегодня отвергается строкой
  `it as? JsonObject ?: invalidAnalysis()`; эта строка сохраняется.
- Схема `docs/contracts/result/v1/analysis-result.schema.json` допускает `analysis_coverage` любым объектом,
  `findings` и `evidence` массивами произвольных объектов. Строгое декодирование типизированной модели
  отвергало бы схемно допустимые документы, поэтому декодер в `AdvisoryAi` не вводится (Ruling R3).
- Вспомогательные функции `AdvisoryAi` (`string`, `objectValue`, `array`) на неверном типе бросают
  `INVALID_OUTPUT`, а проверка набора ключей, версии и `run_id` даёт `INVALID_ANALYSIS`. Оба кода сохраняются.
- Файл идентичности в хранилище называется `identity.json` (`IDENTITY_FILE`), результат `analysis-result.json`.
- Поле `incidents` по ADR 0029 это объект `incident.v1` (метаданные и `items`), а не массив.
- «saved-analytics» в перечне это run-dynamics (`RunComparison.kt`, `AnalyticsExport.kt`), не хэшируется,
  отдаётся через HTTP.

## Ruling

- **R1. Срез вместо полного W2.1.** Почему: см. «Размер полной работы». Цена ошибки: если владелец ожидал
  всё в одном PR, получит три-четыре PR вместо одного; зато каждый проверяем побайтово и не конфликтует с
  W2.2. Обратимо.
- **R2. Типизированная модель кодируется в `JsonElement`, хэш-путь прежний.** `analysisResult` и
  `analysisIdentity` по-прежнему возвращают `canonicalJson(JsonElement)`. Почему: самое узкое место, где
  `@Serializable` не может молча изменить байты (числа, порядок, значения по умолчанию, null). Модель без
  значений по умолчанию, кроме `= null` у необязательных полей; `explicitNulls=false`, `encodeDefaults=true`.
  Цена ошибки: сдвиг `analysis_id` всех анализов; поэтому тест эквивалентности с копией старого кода.
- **R3. `AdvisoryAi` не получает декодер; набор ключей выводится из модели.** Замена `== "analysis-result.v1"`
  на `SUPPORTED_ANALYSIS_RESULT_VERSIONS` и рукописного списка ключей на ключи из `SerialDescriptor`
  `AnalysisResultDocument` (обязательные поля плюс любое подмножество необязательных). Значения полей
  проверяются прежними функциями. Почему: строгое декодирование (enum, обязательные поля `analysis_coverage`,
  `List<JsonObject>`) отвергало бы документы, которые принимает сегодня и которые допускает схема, и
  меняло бы коды отказа (`INVALID_OUTPUT` на `INVALID_ANALYSIS`); это нарушило бы условие «нет изменений
  поведения». Цена: проверка значений остаётся рукописной, компилятор держит только набор ключей и их
  необязательность; полная типизированная проверка значений это отдельный срез с решением о миграции
  кодов отказа. `ai-evidence` строится из исходного `JsonObject`, байты `evidence_input_sha256` не
  двигаются. Явный `null` в `capacity_summary` по-прежнему отвергается (строка сохраняется).
- **R4. Набор версий и `incidents`.** `SUPPORTED_ANALYSIS_RESULT_VERSIONS = setOf("analysis-result.v1")`,
  `schema_version` в модели `String`. В W3.7 добавление поля это одна строка
  `val incidents: JsonObject? = null` в `AnalysisResultDocument` (корень `incident.v1` это объект); четыре
  сочетания с `capacity_summary` принимаются выводом ключей из модели без правки проверки. Это не вводит
  поле сейчас. Ограничение: необязательное поле со значением по умолчанию компилятор не заставит заполнить
  в `analysisResult`; такой дрейф ловят тест эквивалентности и тест закреплённого набора ключей.
- **R5. Генератор в тестовых исходниках, вывод закоммичен.** Почему: ни одной production-зависимости и
  правки `build.gradle.kts` (AGENTS.md: зависимости фиксируются заранее; здесь их нет). Обновление файла по
  `LTV_UPDATE_GENERATED_TYPES=1`; без переменной тест падает при расхождении. Отображение: свойство со
  значением по умолчанию это `field?: T` (так же в рукописных типах), nullable без значения по умолчанию
  `field: T | null`; типы описывают документы, которые пишет движок (явный null в `capacity_summary` как
  вход отвергается и в TS не описывается). `String` остаётся `string` (литерал `'analysis-result.v1'` живёт
  в рукописном типе; проверка соответствия на него не опирается). `JsonObject` это `Record<string, unknown>`,
  не `any`. Рукописный `AnalysisResult` не переписывается: добавляется проверка ключей и скалярных полей.
  Цена ошибки: сгенерированные типы расходятся с рукописным UI; ловят `vue-tsc`, проверка соответствия и
  `verify-generated-types.mjs`.
- **R7. Полезная находка реализации: полезная нагрузка не идёт через сериализатор.** Тест эквивалентности со
  старым построителем показал, что kotlinx пишет число `JsonElement` через Long/Double: `12345678901234567890.5`
  превращалось в `12345678901234567000`, а `1e400` дало бы бесконечность. Поэтому `findings`, `evidence` и
  `capacity_summary` вклеиваются в закодированное дерево после сериализации модели (`encodeAnalysisResult`).
  Цена ошибки без этого: молчаливый сдвиг байтов результата и `evidence_input_sha256`. Любое будущее
  поле-нагрузка (`incidents`) надо вклеивать так же; широкие числа есть в каждой нагрузке эталонного теста.
- **R8. Фикстура bundle в плоской раскладке.** Путь `runs/<run_id>/analyses/<analysis_id>/…` (около 270
  символов) не помещается в лимит пути Windows при `git add`; фикстура хранится как `source.json`, `inputs/`,
  `analysis/`, а тест раскладывает её в формат хранилища. Старые bundle: тестом читаются документы старых форм
  (identity `legacy-pre-adr-0016.v1.json` без `verdict_gates` и с 24 лимитами; результаты без
  `capacity_summary`) через строгие модели; целого старого bundle с историческим manifest в репозитории нет,
  это предел проверки.
- **R6. Золотые файлы снимаются до рефакторинга.** Первым коммитом идут `fixtures/typed-boundary/*` и
  тест, зелёные на коде `origin/main`; затем рефакторинг при зелёном тесте.

## Контракты: что фиксируется

- Формат `analysis-result.v1` и `analysis-identity.v1`: не меняется ни один байт.
- CLI (вывод, коды выхода), HTTP (маршруты, тела), схемы в `docs/contracts`: не меняются.
- Новый внутренний контракт: `SUPPORTED_ANALYSIS_RESULT_VERSIONS`, `hasSupportedAnalysisResultKeys`, `encodeAnalysisResult`, `encodeAnalysisIdentity`, `ANALYSIS_DOCUMENT_JSON` (все internal; декодирование в production не используется).

## Критерии приёмки

1. Байты `analysis-result.json` и `analysis-identity.json`, `analysis_id` совпадают с эталоном на всех
   фикстурах и на матрице входов старого/нового кода.
2. Существующие тесты проходят без правок; `git diff origin/main -- src/test` содержит только новые файлы.
3. `AdvisoryAi` принимает и отвергает ровно то же, что до PR, с теми же кодами отказа (дифференциальный
   тест старого и нового предиката); явный null в `capacity_summary` и неизвестный ключ отвергаются.
4. Дрейф: новое обязательное поле в `AnalysisResultDocument` или `AnalysisIdentityDocument` без правки
   билдера не компилируется; новое необязательное поле ловят тесты эквивалентности и закреплённого набора
   ключей; расхождение `types.generated.ts` ловит тест генератора. Компилятор не ловит изменения внутри
   `findings`/`evidence`/`capacity_summary` (остаются `JsonObject`): это граница среза.
5. `ui`: `typecheck`, `lint`, `test:contracts` зелёные.
6. `git diff --exit-code fixtures/slice1` пуст.

## Команды проверки

```text
. F:\Coding\LT-Verdict\.worktrees\_tools\ltv-slot.ps1
Invoke-LtvSlot { .\gradlew.bat test --tests "io.ltverdict.core.*" --tests "io.ltverdict.ai.*" --tests "io.ltverdict.storage.*" ktlintCheck }
Invoke-LtvSlot { npm --prefix ui run typecheck; npm --prefix ui run lint; npm --prefix ui run test:contracts }
Invoke-LtvExclusive { .\gradlew.bat check }
git diff --exit-code origin/main -- fixtures/slice1
git diff --stat origin/main -- src/test
```

Documentation impact: короткий абзац в `docs/development-process.md` о перегенерации
`types.generated.ts` не нужен до тех пор, пока генерация охватывает только верхний уровень; инструкция в
шапке самого сгенерированного файла и в сообщении теста. Журнал: фрагмент `changelog.d`.
