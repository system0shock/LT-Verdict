# План W2.2, PR 3 из 3: разделить RunBundleStore и разорвать циклы core <-> storage

**Дата:** 2026-10-09. **Ветка:** `refactor/split-run-bundle-store` (от `origin/main` @ `ec3413e`, PR 1 #226 и PR 2 #228 влиты).
**Пункт перечня:** W2.2 (`docs/superpowers/plans/2026-10-08-review-work-plan.md`); планы PR 1 и PR 2:
`docs/superpowers/plans/2026-10-08-w2-2-split-http.md`, `docs/superpowers/plans/2026-10-09-w2-2-rules-to-core.md`.
**Путь brainstorming:** ограниченная правка существующего кода; требования заданы оркестратором, вопросов нет, спорное решено
записями «Ruling».

**Политика:** AGENTS.md, «Исключение: бюджет консолидации» (`refactor/`, цель названа владельцем в W2.2).

| № | Условие | Как выполняется |
| --- | --- | --- |
| 1 | нет изменений поведения, публичных контрактов и схем | снимок раскладки данных `fixtures/storage-layout/store-tree.txt` (164 строки: файлы после каждого шага, документы чтения, класс и сообщение 40 отказов) и `StoreLockingTest` сняты первыми коммитами на неизменённом коде; снимки `fixtures/http-layer/{routes,responses,rules}.txt` проходят без обновления |
| 2 | существующие тесты не редактируются и не удаляются | `git diff origin/main --diff-filter=MD -- src/test` пуст; тесты импортируют только `storage.DataDirectory`, `storage.RunBundleStore`, `storage.AcceptedInput` и две константы, поэтому эти имена остаются |
| 3 | одна цель | «`RunBundleStore` на хранилища анализов, baseline и релизов поверх `DataDirectory` и разрыв циклов `core` <-> `storage`». Типизация ошибок хранилища и перенос `baselineOperation`/`releaseOperation` в core: PR 4 (R6) |
| 4 | канонический JSON и хэши identity не затронуты | тела методов переносятся дословно; `canonicalJson`, `sha256Hex`, `AnalysisIdentity` и файлы `core/` с ними не меняются |
| 5 | блок REQUESTED / REQUIRED с перечнем символов | ниже |

## Что выяснено фактами (до реализации)

- `RunBundleStore.kt` 1838 строк: один класс на ~1330 строк под общим `dataDirectory.operationLock` и ~350 строк закрытых
  функций верхнего уровня. Методы делятся на три несвязанные области: запуски и анализы (приём входа, список запусков и
  анализов, запись и чтение анализа, период запуска, история), baseline (устаревший файл, слоты, записи условий), релизы
  (реестр `releases/`). Связь одна: слоты baseline читают identity анализа (`readIdentityUnlocked`); релизы анализы не читают.
- Циклы (по импортам main): `core/AnalysisResult.kt`, `core/RunPeriod.kt`, `core/AnalysisService.kt` -> `storage.AcceptedInput`;
  `core/AnalysisService.kt` -> `storage.RunBundleStore` (использует только `readAnalysis` и `writeAnalysisAtomically`; ещё
  `sources/SourceAnalysis.kt` через `service.store` вызывает `readRunPeriod` и `replaceRunPeriod`); `ingest/FormatDetector.kt` ->
  `storage.AcceptedInput` при том, что `storage` импортирует `ingest.SourceType` и `ingest.detectSource` (цикл ingest <-> storage);
  `storage/RunBundleStore.kt` -> `core.*` (валидаторы, `canonicalJson`, `sha256Hex`): это допустимое направление storage -> core.
- Конструкторы и вызовы в тестах: `RunBundleStore(directory, clock)`, `AnalysisService(store, config)`, десятки методов
  `RunBundleStore`, `StoredAnalysis` не импортируется в тестах. Поэтому `RunBundleStore` остаётся фасадом с тем же публичным
  интерфейсом, значениями по умолчанию и сигнатурами; тело каждого метода живёт в своём хранилище.
- Блокировка: все хранилища делят один `DataDirectory.operationLock` (монитор Java реентерабелен), поэтому вызов
  `AnalysisStore.readIdentityUnlocked` из `BaselineStore` под уже взятой блокировкой работает так же, как вызов закрытого
  метода внутри одного класса.

## Блок (AGENTS.md, пункт 5 исключения)

```text
REQUESTED:
  Строка W2.2: «RunBundleStore на хранилища анализов, baseline и релизов поверх DataDirectory», критерий «нет циклов
  core <-> storage». По поручению оркестратора это PR 3 из 3: разделить хранилище и разорвать циклы без изменения поведения.

REQUIRED TO ACHIEVE IT (перемещение: символ, откуда -> куда; тела дословно, где не сказано иное):
  Разрыв циклов:
    AcceptedInput                 storage/RunBundleStore.kt -> ingest/AcceptedInput.kt (рядом с SourceType). В storage остаётся
                                  `internal typealias AcceptedInput` (тесты импортируют storage.AcceptedInput); main-код импортирует ingest.
    StoredArtifact, StoredAnalysis  storage/RunBundleStore.kt -> core/AnalysisArtifacts.kt (данные без зависимостей); в storage
                                  остаются псевдонимы (RunBundleStoreTest называет StoredAnalysis без импорта, замечание Astra)
    AnalysisArtifacts (интерфейс)  новый, core/AnalysisArtifacts.kt: readAnalysis, writeAnalysisAtomically, readRunPeriod,
                                  replaceRunPeriod - ровно то, что core и sources берут у хранилища. AnalysisService берёт
                                  AnalysisArtifacts вместо RunBundleStore; RunBundleStore его реализует. Интерфейс нужен задаче:
                                  без него core остаётся зависимым от storage.
    Остальные импорты storage в core/ingest исчезают (core: AnalysisResult, RunPeriod, AnalysisService; ingest: FormatDetector).
  Новый storage/StorageSupport.kt (internal; были private внизу RunBundleStore.kt): writeForced, forceFile, forceDirectory,
    FileChannel.writeFully, parseObject, JsonObject.string/optionalString/long, ensureOwnedDirectory, requireOwnedDirectory,
    requireOwnedFile, sha256(Path), Path.invariantPath, isSafeFilename, requireRunId, requireAnalysisId, corrupt, константы
    SHA256, RUN_ID; typealias AcceptedInput.
  Новый storage/AnalysisStore.kt: данные RunSummary, RunPage, AnalysisSummary, AnalysisPage, VerifiedAnalysis, ComparisonDocuments,
    ComparisonHistoryEntry, ComparisonHistory; класс AnalysisStore(dataDirectory, clock): acceptInput, requireInput, listRuns,
    readAnalysis, listAnalyses, readPolicyId, writeAnalysisAtomically, readAnalysisIdentity, readRunPeriod, replaceRunPeriod,
    analysisExists, analysisState, readAnalysisDocuments, readVerifiedAnalysis, readPodViewBytes, readComparisonDocuments,
    readComparisonHistory; закрытые readIdentityUnlocked (internal: нужен baseline), readRunPeriodUnlocked, requireInputUnlocked,
    readAnalysisUnlocked, inspectStagedArtifacts, inspectPublishedArtifacts; функции copyInput, sourceMetadata, analysisManifest,
    RunListingKey, RUN_LISTING_ORDER, readAcceptedAtForListing, isCanonicalAcceptedAt, toSummary, StoredAnalysis.verifiedBytes,
    requireRunPeriodFile, corruptRunPeriod; константы файлов и пределов анализа, MAX_VERIFIED_RESULT_BYTES.
  Новый storage/BaselineStore.kt: BaselineSlot; класс BaselineStore(dataDirectory, analyses): readBaseline, replaceBaseline,
    readBaselineCondition, replaceBaselineCondition, clearBaseline, listBaselineSlots, readBaselineSlotWithCondition,
    replaceBaselineSlot, clearBaselineSlot и закрытые readBaselineUnlocked, SlotState, slotStateUnlocked, slotOf, identityArm,
    slotFilesUnlocked, ensureBaselineSlotsDirectory, baselineConditionDeletionUnlocked, readSelectionFile,
    readBaselineConditionUnlocked, readConditionRecord; baselineSlotKey, requireBaseline*, baselineConditionPath,
    JsonObject.baselineReference, corruptBaseline, константы MAX_BASELINE_SLOTS, MAX_BASELINE_CONDITION_FILES и остальные baseline.
  Новый storage/ReleaseStore.kt: ReleaseCorruptName, ReleasePage, ReleaseLookup; класс ReleaseStore(dataDirectory): createRelease,
    readRelease, replaceRelease, deleteRelease, listReleases, findReleasesByAnalysis и закрытые ReleaseEntry, ReleaseScan,
    PreparedRelease, ensureReleasesDirectory, releaseTargetOrNull, scanReleasesUnlocked, readReleaseEntry, prepareRelease;
    releaseAnalysisIds, randomReleaseSuffix, requireReleaseId, requireReleasesDirectory, corruptRelease, corruptReleaseRegistry,
    константы реестра, MAX_RELEASES.
  storage/RunBundleStore.kt: остаётся класс RunBundleStore(dataDirectory, clock) как фасад: те же публичные методы с теми же
    параметрами по умолчанию, каждый вызывает метод своего хранилища; реализует AnalysisArtifacts.
  Тесты (только новые файлы):
    - src/test/kotlin/io/ltverdict/storage/StoreTreeSnapshotTest.kt + fixtures/storage-layout/store-tree.txt (первый коммит,
      на неизменённом коде).
    - src/test/kotlin/io/ltverdict/architecture/PackageDependencyTest.kt: ни один файл core/ и ingest/ в main не ссылается на
      io.ltverdict.storage (импорт или полное имя); ingest не ссылается на core (сторож против возврата цикла).
    - src/test/kotlin/io/ltverdict/storage/StoreLockingTest.kt (второй коммит, на неизменённом коде): beforePublish под блокировкой
      каталога данных и ожидание чтений всех областей, обратный вызов replaceRelease вне блокировки, запись анализа после закрытия
      каталога (DATA_DIR_CLOSED без остатков), независимость двух каталогов.
  changelog.d/w2-2-split-run-bundle-store.changed.md

NOT REQUIRED (не делается; в отчёт как PR 4):
  - Типизация ошибок хранилища (сейчас IllegalArgumentException/IllegalStateException с кодом в сообщении) и перенос
    baselineOperation/releaseOperation в core (R6).
  - Любое изменение формата файлов, порядка записи, блокировок, сообщений и типов исключений; публичного интерфейса
    RunBundleStore; существующих файлов core/ (кроме AnalysisService.kt, AnalysisResult.kt, RunPeriod.kt: смена импорта и типа
    параметра), DataDirectory, ui/, схем, docs/contracts.
  - Перевод вызывающих (web, ai, cli, jobs) с фасада RunBundleStore на отдельные хранилища.
  - Файлы W2.5 PR B (флаг --stages, API): путь записи load-stages.json через AnalysisService/хранилище не меняется.

EXPECTED FILES TO CHANGE:
  src/main/kotlin/io/ltverdict/ingest/AcceptedInput.kt, FormatDetector.kt
  src/main/kotlin/io/ltverdict/core/AnalysisArtifacts.kt (новый), AnalysisService.kt, AnalysisResult.kt, RunPeriod.kt (импорт, тип)
  src/main/kotlin/io/ltverdict/storage/RunBundleStore.kt, StorageSupport.kt, AnalysisStore.kt, BaselineStore.kt, ReleaseStore.kt
  src/main/kotlin/io/ltverdict/web/ApiSupport.kt, JobRoutes.kt и другие (только импорт AcceptedInput / StoredAnalysis)
  src/main/kotlin/io/ltverdict/sources/SourceAnalysis.kt (если тип `service.store` меняет используемые вызовы - нет)
  src/test/kotlin/io/ltverdict/storage/StoreTreeSnapshotTest.kt, architecture/PackageDependencyTest.kt (новые)
  fixtures/storage-layout/store-tree.txt (новый)
  docs/superpowers/plans/2026-10-09-w2-2-split-run-bundle-store.md (этот план)
  changelog.d/w2-2-split-run-bundle-store.changed.md
```

Documentation impact: none для пользовательской документации. Внутреннее: фрагмент `changelog.d`; ADR не нужен (новых
зависимостей и публичных контрактов нет; формат данных на диске не меняется).

## Ruling

- **R1. `RunBundleStore` остаётся фасадом.** Почему: тесты (и их 22 файла) конструируют `RunBundleStore(directory, clock)` и
  вызывают его методы; править существующие тесты нельзя. Цена: ещё один слой делегирования (~100 строк); вызывающие можно
  переводить на отдельные хранилища позже без риска.
- **R2. Хранилища делят `DataDirectory` и его `operationLock`.** Почему: сохраняет сериализацию операций, которую давал один
  класс (в том числе блокировку, под которой `BaselineStore` читает identity). Цена ошибки: гонки между областями; все тесты
  параллельности (`RunBundleStoreTest`) проходят без правок.
- **R3. Направление зависимостей: storage -> core -> ingest.** Почему: storage использует валидаторы core (storage -> core
  допустимо и существует), а обратная зависимость core -> storage лишняя. Для неё `AcceptedInput` уходит в `ingest` (там уже
  `SourceType`), а `AnalysisService` получает порт `AnalysisArtifacts` в `core`. Альтернатива «перенести `AnalysisService`» ломает
  импорты 20 тестов (нельзя править). Цена: новый интерфейс на 4 метода (нужен задаче, MINIMAL-CHANGE п. 5).
- **R4. `typealias AcceptedInput` в storage.** Почему: 18 тестов импортируют `io.ltverdict.storage.AcceptedInput`. Цена: один
  псевдоним, который можно удалить вместе с правкой тестов.
- **R5. Видимость `private` -> `internal` у общих помощников, остальное остаётся `private` в своём файле.**
- **R6. Типизация ошибок хранилища и перенос `baselineOperation`/`releaseOperation` это PR 4.** Почему: поручение оркестратора
  допускало PR 4 при росте объёма; текущий PR уже перемещает около 2000 строк плюс разрыв циклов, добавление типизации (около 25
  мест `throw` с кодом в сообщении, типы должны жить в core, чтобы core мог их ловить) и переноса отображения ошибок
  (коды HTTP 404/409/422/500 с `limit`) в одном PR не проверяется той же прямой сверкой «ничего не изменилось». Цена: PR 4.
- **R7. Совет Codex Astra (read-only, gpt-6-astra) учтён.** Принято и проверено по коду: псевдоним `StoredAnalysis` (и
  `StoredArtifact`) в storage, потому что `RunBundleStoreTest` называет `StoredAnalysis?` без импорта; `randomReleaseSuffix`
  остаётся в `ReleaseStore.kt` как `internal` (по умолчанию у `createRelease` фасада); шесть констант и все закрытые помощники
  размещены по фактическому использованию (общие в `StorageSupport.kt` как `internal`, остальные `private` в своём файле);
  `beforePublish` с умолчанием `{}` объявлен в интерфейсе, у переопределения умолчания нет, `writeStagingDirectory` остаётся
  последним (подтверждено компиляцией вызовов с завершающей лямбдой в `AnalysisService`, тестах и `SourceAnalysis`);
  фасад не синхронизирует методы целиком, все хранилища получают один и тот же `DataDirectory`; сторож зависимостей ловит и полные
  имена, не только импорты; добавлен `StoreLockingTest` (общая блокировка, обратные вызовы вне блокировки, закрытие каталога) и
  порядковая сверка тел (см. критерий 4: сверка идёт по порядку строк каждого метода, а не по мультимножеству, поэтому
  перестановка операторов её нарушила бы). Ограничения, которые принимаются и записываются: снимок раскладки фиксирует состояния
  после операций, а не порядок fsync внутри операции (порядок защищает дословная порядковая сверка), в `AcceptedInput` снимка
  разделители путей системы, поэтому снимок снят на Windows и на Linux потребует нормализации. Совет подтвердил отложить
  типизацию ошибок (R6) и напомнил, что PR 4 должен учесть `NoSuchElementException` (`RUN_NOT_FOUND`, `RELEASE_NOT_FOUND`).

## Контракты

- Формат на диске (структура каталогов, имена файлов, канонические байты, манифест анализа, атомарные переименования,
  `.staging`, `.ltv.lock`), публичный интерфейс `RunBundleStore`, типы и сообщения исключений: не меняются. Фиксируются
  `StoreTreeSnapshotTest` и существующими `RunBundleStoreTest`, `DataDirectoryTest`.
- Зависимости пакетов: `ingest` не зависит ни от кого из проекта; `core` зависит от `ingest`; `storage` зависит от `core` и `ingest`.
  `build.gradle.kts` не меняется.

## Критерии приёмки

1. `StoreTreeSnapshotTest` и все снимки `fixtures/http-layer` проходят без обновления файлов.
2. Все существующие тесты зелёные без правок: `git diff origin/main --diff-filter=MD --name-only -- src/test` пуст.
3. `rg "^import io.ltverdict.storage" src/main/kotlin/io/ltverdict/{core,ingest}` пуст; `PackageDependencyTest` зелёный.
4. Тела перемещённых методов дословны и в том же порядке: сверка по порядку нормализованных строк каждого из 57 членов класса и
   90 объявлений верхнего уровня `RunBundleStore.kt` с новыми файлами (отчёт скрипта; допустимые различия: `private` -> `internal`,
   `analyses.readIdentityUnlocked`, три перенесённых типа).
5. Полный набор «Без CI» на результате слияния с `origin/main`: `Invoke-LtvExclusive { gradlew --no-daemon --no-build-cache
   cleanTest check installDist }`; `cd ui; npm run typecheck; npm run lint; npm run test:contracts`; оффлайн Playwright
   (`Invoke-LtvE2E`); `python tools/verify_slice0.py`; `python tools/changelog_assemble.py --check`; markdownlint.

## Порядок коммитов

1. `test(storage): snapshot the data directory ...` (готов) + этот план.
2. `refactor: break the core <-> storage and ingest <-> storage cycles (W2.2)`: AcceptedInput в ingest, порт AnalysisArtifacts,
   сторож зависимостей.
3. `refactor(storage): split RunBundleStore into analysis, baseline and release stores (W2.2)`.
4. `docs: changelog fragment`.

## Следующий PR 4 (не в этом)

Типизированные ошибки хранилища (в `core`, подклассы тех же `IllegalArgumentException`/`IllegalStateException`/
`NoSuchElementException` с теми же сообщениями; около 25 мест `throw` и `require` с кодом в сообщении, пять функций `corrupt*`) и
перенос в core отображения этих ошибок в `RuleFailure`/HTTP-коды (сейчас `baselineOperation`, `releaseOperation` в `web`),
включая виды NOT_FOUND и CONFLICT и поле `limit`. Типизация без потребителя в этом PR была бы абстракцией «про запас»
(MINIMAL-CHANGE п. 3-4): её потребитель это перенос.
