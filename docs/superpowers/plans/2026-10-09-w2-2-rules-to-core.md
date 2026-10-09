# План W2.2, PR 2 из 3: доменные правила baseline и release из web в core

**Дата:** 2026-10-09. **Ветка:** `refactor/baseline-release-rules-to-core` (от `origin/main` @ `c3f40ed`, PR 1 #226 влит).
**Пункт перечня:** W2.2 (`docs/superpowers/plans/2026-10-08-review-work-plan.md`); план PR 1:
`docs/superpowers/plans/2026-10-08-w2-2-split-http.md`. **Путь brainstorming:** ограниченная правка существующего кода;
требования заданы оркестратором, вопросов нет, спорное решено записями «Ruling».

**Политика:** AGENTS.md, «Исключение: бюджет консолидации» (`refactor/`, цель названа владельцем в W2.2).

| № | Условие | Как выполняется |
| --- | --- | --- |
| 1 | нет изменений поведения, публичных контрактов и схем | снимок `fixtures/http-layer/{routes,responses}.txt` PR 1 проходит без обновления; новый снимок правил `fixtures/http-layer/rules.txt` (229 записей) снят первым коммитом на неизменённом коде |
| 2 | существующие тесты не редактируются и не удаляются | `git diff origin/main --diff-filter=MD -- src/test` пуст; добавляются новые файлы |
| 3 | одна цель | только «доменные правила baseline и release в core». `RunBundleStore` и циклы `core` <-> `storage` это PR 3 |
| 4 | канонический JSON и хэши identity не затронуты | существующие файлы `core/` не меняются вообще (только новые файлы); `canonicalJson`, `AnalysisIdentity` не трогаются |
| 5 | блок REQUESTED / REQUIRED с перечнем символов | ниже |

## Что выяснено фактами (до реализации)

- Правила сейчас в `web/BaselineRoutes.kt` (≈400 строк) и `web/ReleaseRoutes.kt` (≈450 строк) вперемешку с HTTP: каждое
  нарушение бросает `ApiFailure(HttpStatusCode, code, message)` (`malformed` 400 `MALFORMED_REQUEST`, `baselineIneligible`
  422, `corruptBaseline` 500 и т. д.). В `core` такой тип использовать нельзя (core не знает HTTP).
- Правила разбираются на чистые (вход `JsonObject`/строки, выход значение или нарушение) и связанные с `RunBundleStore`
  (чтение анализов, реестр). В `core` уходят только чистые части; чтения хранилища остаются в маршрутах. Это важно: `core`
  не получает новых зависимостей от `storage` (циклы core <-> storage остаются PR 3).
- Функции отображения исключений хранилища в HTTP (`baselineOperation`, `releaseOperation`) ссылаются на константы
  `storage.MAX_BASELINE_SLOTS`, `MAX_BASELINE_CONDITION_FILES`, `MAX_RELEASES` и на тексты сообщений `RunBundleStore`
  (`"BASELINE_SLOTS_LIMIT_REACHED"`, `"RELEASE_CHANGED"`, ...). Перенос их в `core` добавил бы ребро core -> storage, то есть
  углубил цикл, который должен разорвать PR 3. Ruling R3 ниже.
- Потоковость `releaseFacts` (читать один анализ за раз и отбрасывать дерево, пик памяти один результат) должна
  сохраниться: чистая часть оформляется накопителем, который вызывается по одному анализу.
- Порядок проверок и порядок исключений наблюдаем (какой отказ придёт первым). Новый снимок правил фиксирует его для
  baseline (режимы manual и statistical, 3-6 кандидатов, повторяющийся запуск, малая выборка), для release (поля тела, нормализация,
  профиль, заметки, факты анализов, оба порядка частей, PUT), для запросов окон сравнения и порогов, для DELETE baseline.

## Блок (AGENTS.md, пункт 5 исключения)

```text
REQUESTED:
  Строка W2.2: «доменные правила baseline и release в core». По поручению оркестратора это PR 2 из 3: правила baseline и
  release (выбор baseline, допуск кандидата, факты release, виды release, разбор и нормализация полей release) перенести
  из web/BaselineRoutes.kt и web/ReleaseRoutes.kt в core; маршруты остаются тонкими. Без изменения поведения, контрактов
  и схем.

REQUIRED TO ACHIEVE IT (перемещение: символ, откуда -> куда; тела дословно, где не сказано иное):
  Новый core/RuleFailure.kt:
    RuleFailureKind (MALFORMED, UNPROCESSABLE, CORRUPT), RuleFailure, ruleMalformed, ruleUnprocessable, ruleCorrupt
      (замена web ApiFailure для нарушений правил: статус выбирает web по виду; сообщения и коды те же).
  Новый core/BaselineRules.kt (из web/BaselineRoutes.kt):
    JsonObject.baselineString            web/BaselineRoutes.kt -> core (malformed -> ruleMalformed)
    JsonObject.baselineReference         web/BaselineRoutes.kt -> core (RUN_ID/SHA-256 регулярные выражения: частные копии в файле)
    baselineIneligible                   web/BaselineRoutes.kt -> core
    requireBaselineEligible              web/BaselineRoutes.kt -> core (вход List<JsonObject> результатов, а не VerifiedAnalysis)
    JsonObject.knownVerdict, KNOWN_VERDICTS  web/BaselineRoutes.kt -> core (private)
    selectBaseline                       web/BaselineRoutes.kt -> core, разделён на planBaselineSelection (разбор и проверки до
                                         чтений) и selectBaseline (допуск и статистика после чтений); класс BaselineSelectionPlan
    JsonObject.baselineArm, corruptBaseline  web/BaselineRoutes.kt -> core (corruptBaseline -> ruleCorrupt)
    BaselineScope                        web/BaselineRoutes.kt -> core
    resolveBaselineScope (чистая часть)  web/BaselineRoutes.kt -> core: baselineScope(explicit, registered, identity)
                                         (конфликт серии 422 BASELINE_SERIES_CONFLICT, затем arm из identity)
    ApplicationCall.baselineSeriesQuery  (разбор значения) -> core baselineSeriesParameter
    разбор arm в DELETE /api/baseline    (разбор значения) -> core baselineArmParameter
    BASELINE_CONDITION_DECISIONS, разбор тела решения -> core baselineConditionDecision
    ApplicationCall.windowComparisonQuery, boundedDecimalQuery -> core windowComparisonRequest(строки) (парсинг окон и порогов)
  Новый core/ReleaseRules.kt (из web/ReleaseRoutes.kt):
    releaseTextField, releaseProfile(element), releaseNotes, releaseRunId, releaseAnalysisIds -> core (private; через
      parseReleaseCreate / parseReleaseUpdate, классы ReleaseDraftRequest, ReleaseUpdateRequest; порядок проверок прежний)
    RELEASE_POST_FIELDS, RELEASE_PUT_FIELDS, ANALYSIS_ID -> core (private)
    ApplicationCall.releaseIdParameter (разбор значения), разбор `series` и `after` списка -> core releaseIdParameter(String?),
      releaseSeriesParameter, releaseAfterParameter
    JsonObject.releaseField, releaseStateKeys, releaseOkStates, releaseIneligibleReasons (private), releaseView -> core
    releaseFacts (чистая часть)          -> core: ReleaseFactsCollector.add / finish (чтение хранилища остаётся в web)
    сборка черновика release (POST) и обновлённой записи (PUT), проверка совпадения started_at (PUT) -> core releaseDraft,
      releaseUpdated, requireReleaseStart
  В web остаётся (тонкие маршруты):
    BaselineRoutes.kt: baselineRoutes/comparisonRoutes, baselineDocuments, verifiedBaselineDocuments, resolveBaselineScope (чтение
      identity и реестра, затем core baselineScope), baselineOperation (отображение исключений хранилища; R3).
    ReleaseRoutes.kt: releaseRoutes, releaseFacts (чтение хранилища и сообщение RELEASE_RESULT_TOO_LARGE; затем накопитель),
      releaseOperation (R3), releasePageJson (использует storage.ReleasePage), releasesOfAnalyses, releaseLabel,
      releaseProfile() с nullable-получателем.
    LocalApi.kt: один новый catch (RuleFailure) в интерцепторе: вид -> 400 / 422 / 500, тело ошибки то же, что у ApiFailure.
  Тесты (только новые файлы):
    - src/test/kotlin/io/ltverdict/web/BaselineReleaseRulesSnapshotTest.kt + fixtures/http-layer/rules.txt (первый коммит,
      на неизменённом коде): правила в ответах HTTP (229 записей).
    - src/test/kotlin/io/ltverdict/core/BaselineReleaseRulesTest.kt: прямые тесты новых функций core (виды нарушений, накопитель
      фактов, представление release).
  changelog.d/w2-2-rules-to-core.changed.md

NOT REQUIRED (не делается; в отчёт):
  - Перенос baselineOperation / releaseOperation в core (R3), releasePageJson (использует storage.ReleasePage).
  - Любое изменение существующих файлов core/ (BaselineComparison.kt, LocalRelease.kt и другие), storage/, ui/, схем,
    docs/contracts, ADR; изменение сообщений, кодов, статусов, порядка проверок.
  - Разделение RunBundleStore и разрыв циклов core <-> storage (PR 3); файлы W2.5 PR A (core/AnalysisService, identity, LoadStages).
  - Новые абстракции сверх перечисленного (интерфейсы, реестры), новые зависимости, build.gradle.kts.

EXPECTED FILES TO CHANGE:
  src/main/kotlin/io/ltverdict/core/RuleFailure.kt, BaselineRules.kt, ReleaseRules.kt (новые)
  src/main/kotlin/io/ltverdict/web/BaselineRoutes.kt, ReleaseRoutes.kt, LocalApi.kt (один catch)
  src/test/kotlin/io/ltverdict/web/BaselineReleaseRulesSnapshotTest.kt, src/test/kotlin/io/ltverdict/core/BaselineReleaseRulesTest.kt (новые)
  fixtures/http-layer/rules.txt (новый)
  docs/superpowers/plans/2026-10-09-w2-2-rules-to-core.md (этот план)
  changelog.d/w2-2-rules-to-core.changed.md
```

Documentation impact: none для пользовательской документации (поведение и контракты не меняются). Внутреннее: фрагмент
`changelog.d`; ADR не нужен (новых зависимостей, схем, публичных контрактов нет).

## Ruling

- **R1. Нарушение правила это `RuleFailure(kind, code, message)` в `core`, web превращает его в HTTP одним `catch` в
  интерцепторе.** Почему: `core` не может бросать `ApiFailure` (HTTP). Альтернативы: возвращать `Result` (меняет все вызовы,
  больше диффа) или `IllegalArgumentException` с кодом (смешивается с исключениями хранилища, которые `baselineOperation`
  ловит по `IllegalArgumentException`/`IllegalStateException`, и перехватывает чужое). Поэтому отдельный класс, не
  наследующий от `IllegalArgumentException`, `IllegalStateException`, `NoSuchElementException`: `baselineOperation` и
  `releaseOperation` его не перехватывают, как не перехватывали `ApiFailure`. Цена ошибки: другой статус или тело
  ошибки; ловит снимок правил. Три вида (400/422/500) соответствуют ровно трём статусам, которые бросали эти правила.
- **R2. Чистое в core, чтения хранилища в web; порядок «проверки до чтений, затем допуск» сохраняется** разбиением
  `selectBaseline` на `planBaselineSelection` и `selectBaseline`, а `releaseFacts` на накопитель. Почему: `core` не должен
  принимать `RunBundleStore`. Цена ошибки: изменится порядок отказов (что приходит первым); ловит снимок правил.
- **R3. `baselineOperation` и `releaseOperation` остаются в web.** Почему: они отображают исключения хранилища (по тексту
  сообщения) и константы `storage` в HTTP-коды; в `core` это добавило бы импорт `storage` и углубило бы цикл, который снимает
  PR 3 (правило 10: стоп, если неизбежно трогать циклы). Это отображение ошибок транспорта, а не доменное правило. Цена:
  оркестратор просил включить «отображение исключений хранилища в коды»; здесь оно сознательно не входит, после PR 3
  (хранилища разделены, коды ошибок хранилища типизированы) его можно перенести.
- **R4. Существующие файлы `core/` не меняются, регулярные выражения идентификаторов дублируются в новых файлах как
  `private`** (в коде уже по 5-6 таких копий: `BaselineComparison.kt`, `LocalRelease.kt`, ...). Почему: правило 7 MINIMAL-CHANGE
  и непересечение с W2.5 PR A.
- **R5. Сообщения, коды, порядок остаются дословно; `malformed(...)` меняется на `ruleMalformed(...)`**, `ApiFailure(422, ...)` на
  `ruleUnprocessable(...)`, `corruptBaseline` на `ruleCorrupt`. Тексты сообщений на английском (контракт API), не меняются.
- **R6. Совет Codex Astra учтён** (раздел ниже заполняется после совета).

## Контракты

- HTTP (пути, методы, статусы, заголовки, тела, коды ошибок), CLI, схемы `docs/contracts`, формат release и baseline на диске:
  не меняются. Фиксируются снимками.
- Внутренние `internal`-символы `core` перечислены в блоке. Зависимости (`build.gradle.kts`) не меняются.

## Критерии приёмки

1. `LocalApiSnapshotTest` и `BaselineReleaseRulesSnapshotTest` проходят без обновления файлов снимков.
2. Все существующие тесты зелёные без правок: `git diff origin/main --diff-filter=MD --name-only -- src/test` пуст.
3. `git diff origin/main --stat -- src/main/kotlin/io/ltverdict/storage` пуст; существующие файлы `core/` не изменены (только новые).
4. `rg "^import io.ltverdict.storage" src/main/kotlin/io/ltverdict/core/{RuleFailure,BaselineRules,ReleaseRules}.kt` пуст; `rg` по
   `web/BaselineRoutes.kt` и `web/ReleaseRoutes.kt` не находит перенесённых правил.
5. Полный набор «Без CI» на результате слияния с `origin/main`: `Invoke-LtvExclusive { gradlew --no-daemon --no-build-cache
   cleanTest check installDist }`; `cd ui; npm run typecheck; npm run lint; npm run test:contracts`; оффлайн Playwright
   (`Invoke-LtvE2E`); `python tools/verify_slice0.py`; `python tools/changelog_assemble.py --check`; markdownlint.

## Порядок коммитов

1. `test(web): snapshot baseline and release rule answers before moving them to core (W2.2)` - снимок правил + этот план.
2. `refactor(core): move baseline and release rules out of the web routes (W2.2)` - новые файлы core, тонкие маршруты,
   `catch (RuleFailure)`, тесты core.
3. `docs: changelog fragment for W2.2 PR 2`.
