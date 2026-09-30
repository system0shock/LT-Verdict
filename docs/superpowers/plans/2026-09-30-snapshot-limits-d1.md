# Подъём лимитов снимка ресурсов (срез D1) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** продукт принимает снимок `resource-snapshot.v1` до 1 024 рядов, до 1 500 000 ячеек и размером до 32 MiB (принятые значения ADR 0014), не меняя форму данных и не ломая ни один прежний валидный файл.

**Architecture:** три константы в `ResourceSnapshot.kt` остаются единственным источником пределов; блок `limits` identity, онлайн-цепочка (`SourceAnalysis.kt`, `PromqlSource.kt`) и загрузка через API читают их напрямую, поэтому меняются вместе с ними. Отдельно правятся только места, где предел продублирован числом: `maxItems` схемы и производный `MAX_JOB_REQUEST_BYTES` в `LocalApi.kt`. Абстракций и зависимостей нет.

**Tech Stack:** Kotlin, JUnit 5, JSON Schema; новых зависимостей нет.

**Spec:** этот файл (раздел «Brainstorming: допущения и вопросы») и принятый ADR 0014 (`docs/adr/0014-resource-series-limits-autostep-arm-api.md`, разделы «Значения лимитов», часть 7, «Следствия», п. 1). Замер: `docs/ui-mockup/adr-a-measurement-2026-09-30.md` в основном чекауте (untracked, в репозиторий не входит); константы, поднимавшиеся в замерочной ветке `test/adr-a-measurement` (`a4e11ca`), использованы как ориентир, замерочный код не переносится.

## REQUESTED / REQUIRED / NOT REQUIRED / EXPECTED FILES (AGENTS.md)

```text
REQUESTED: продукт принимает снимок ресурсов до 1 024 рядов, до 1 500 000
  ячеек и размером до 32 MiB (S_max, C_max, B_max из ADR 0014).
REQUIRED TO ACHIEVE IT:
  - ResourceSnapshot.kt: MAX_RESOURCE_SERIES 64 -> 1024, MAX_RESOURCE_CELLS
    500 000 -> 1 500 000, MAX_RESOURCE_SNAPSHOT_BYTES 16 -> 32 MiB;
  - схема resource-snapshot.schema.json: series.maxItems 64 -> 1024 и
    пояснение про ячейки и байты (в схеме они не выражены);
  - LocalApi.kt: formFieldLimit загрузки job и производный
    MAX_JOB_REQUEST_BYTES учитывают снимок 32 MiB (остальные 16 MiB-пределы
    файла не трогаются, см. допущение 2);
  - блок limits identity меняется сам (AnalysisResult.kt читает константы,
    правок кода нет); новая golden-фикстура identity со снимком
    (ADR 0014, часть 7, п. 2) фиксирует блок ресурсных пределов;
  - тесты границ (1 024/1 025 рядов, 1 500 000/1 500 001 ячеек, 32 MiB и
    32 MiB + 1 на валидаторе и на multipart), закрепление хэшей прежнего
    примера (старые файлы остаются валидными с тем же semanticSha256);
  - документация: docs/user/slice-1-local-analysis.md (лимиты, требование к
    ОЗУ и JAVA_OPTS), docs/architecture/slice-1-local-runtime.md,
    docs/user/online-sources.md, docs/analytics-scale-triage.md (таблица
    конверта), CHANGELOG.md (Changed), отметка «реализовано в D1» в ADR 0014.
NOT REQUIRED (report-only): автошаг, interval_max/interval_min, метка arm,
  API рядов и каталог; E_max (в коде его нет, ADR оставляет значение
  предварительным); число запросов на профиль источника (SourceConfig.kt:844,
  32 -> 64) и лимит сырых ответов 64 MiB; -Xmx в коде; UI; пределы точек в
  ряду, окон, правил, шага, меток; пределы source_context (16 MiB) и
  PostgreSQL-артефактов; ограничение памяти разбора до построения JSON-дерева.
EXPECTED FILES TO CHANGE:
  modify src/main/kotlin/io/ltverdict/core/ResourceSnapshot.kt
  modify src/main/kotlin/io/ltverdict/web/LocalApi.kt
  modify docs/contracts/resources/v1/resource-snapshot.schema.json
  modify src/test/kotlin/io/ltverdict/core/ResourceSnapshotTest.kt
  modify src/test/kotlin/io/ltverdict/core/AnalysisResultGoldenTest.kt
  modify src/test/kotlin/io/ltverdict/web/LocalApiTest.kt
  modify src/test/kotlin/io/ltverdict/sources/PromqlSourceTest.kt
  modify src/test/kotlin/io/ltverdict/fixtures/FixtureManifestTest.kt
  modify fixtures/slice1/manifest.json
  create fixtures/slice1/identity/analysis-identity-resources.v1.json
  create fixtures/slice1/identity/analysis-identity-resources.sha256
  modify docs/user/slice-1-local-analysis.md
  modify docs/architecture/slice-1-local-runtime.md
  modify docs/user/online-sources.md
  modify docs/analytics-scale-triage.md
  modify docs/adr/0014-resource-series-limits-autostep-arm-api.md
  modify CHANGELOG.md
  create docs/superpowers/plans/2026-09-30-snapshot-limits-d1.md
```

Если реализация становится заметно больше ожидаемого, остановиться и объяснить (AGENTS.md, п. 10).

## Brainstorming: допущения и вопросы

Классификация: bounded (меняются существующие константы и их дубли; форма данных, API и контракты по структуре не меняются). Интерактивного диалога нет: решения владельца из задания и принятый ADR 0014 взяты как исходные, остальное записано допущениями.

### Сверка ADR 0014 с кодом (origin/main = 9d388d7)

| Утверждение ADR | Факт в коде | Итог |
| --- | --- | --- |
| Ряды 64: `ResourceSnapshot.kt:652`, проверка `:274` | совпадает | верно |
| Ячейки 500 000: `:654`, проверка `:275-277` | совпадает | верно |
| Размер 16 MiB: `:651`, умолчание разбора `:115` | совпадает | верно |
| `MAX_RESOURCE_BYTES` `LocalApi.kt:1736`, производный `MAX_JOB_REQUEST_BYTES` `:1739-1742` | совпадает, но `MAX_RESOURCE_BYTES` служит не только снимку (см. ниже) | верно с оговоркой |
| Схема `maxItems: 64` `resource-snapshot.schema.json:14` | совпадает | верно |
| Онлайн-предел `SourceAnalysis.kt:400-405`, `PromqlSource.kt:58-60` читает те же константы | совпадает, менять не нужно | верно |
| Онлайн, 32 запроса на профиль, 16 профилей: `SourceConfig.kt:844`, `:259` | совпадает; эффективный онлайн-потолок остаётся 512 рядов | верно; вне D1 |
| Блок `limits` identity `AnalysisResult.kt:131`, `:193-209`, только при снимке | совпадает; ключи `resource_snapshot_bytes_max`, `resource_series_max`, `resource_cells_total_max` | верно |
| Golden identity без снимка `AnalysisResultGoldenTest.kt:40`; ресурсные пределы нигде не закреплены | подтверждено поиском по `src/test`, `fixtures`, `docs` | верно; нужна новая фикстура |
| `E_max`: пределы в коде отсутствуют | подтверждено: `ResourceStatistics.kt:33-40` эмитирует сводки без предела, `RESOURCE_FINDINGS_MAX = 10_000` (`:418`) относится к findings, `maxWindowHistograms = 10_000` (`Metrics.kt:23`) к гистограммам нагрузки; констант `E_max` нет | ADR не противоречит коду; D1 `E_max` не вводит |

Расхождения, найденные при сверке:

1. `MAX_RESOURCE_BYTES` (`LocalApi.kt:1736`) не относится только к снимку: им заданы предел части `source_context` (`:1207-1208`), части `postgres_pre` и `postgres_post` (`:1745-1746`), запроса `capture` (`:954`, `:959`) и `formFieldLimit` (`:959`, `:1194`). Сам снимок в multipart ограничивается не им, а `validateResourceSnapshot` (умолчание `MAX_RESOURCE_SNAPSHOT_BYTES`). Если поднять `MAX_RESOURCE_BYTES` до 32 MiB, как буквально написано в ADR, молча расширятся публичные пределы `source_context` и PostgreSQL-артефактов (в CLI они жёстко 16 MiB: `CommandLine.kt:246-249`, `:353-354`; `PostgresCapture.kt:1168`), то есть API и CLI разойдутся, а память запроса вырастет без нужды.
2. Ktor `receiveMultipart(formFieldLimit)` ограничивает не только поля формы, но и размер файловой части: это выяснено экспериментом (часть 17 MiB при `formFieldLimit` 16 MiB + 1 даёт `400 MALFORMED_REQUEST`, а часть 20 MiB и больше подвешивает клиента до таймаута, потому что сервер отвечает, не дочитав тело). Поэтому `receiveMultipart` в `receiveJob` (`LocalApi.kt:1194`) обязан получить предел снимка, иначе снимок больше 16 MiB не загрузить через API. Побочный эффект: поле формы `run_id` читается в память до проверки длины, его потолок вырастает с 16 до 32 MiB (report-only).
3. В ADR `:1207-1208` названы частью multipart снимка: это `source_context`, не `resource_snapshot`.
4. Два теста онлайн-цепочки (`PromqlSourceTest`, `multiple profile resource caps are checked before HTTP` и `aggregate snapshot byte limit degrades the source and retains raw artifacts`) были привязаны к прежним пределам (96 рядов при кэпе 64; снимок чуть больше 16 MiB). Они переписываются на новые пределы (окно 20 000 ячеек на ряд и 32 000 ячеек на ряд в 32 рядах), production-код онлайн-цепочки не меняется.
5. Разбор снимка строит целое JSON-дерево до проверок рядов и ячеек (`Json.parseToJsonElement`, `ResourceSnapshot.kt:129`, проверки `:274-277` позже), поэтому худший случай памяти для враждебного файла в 32 MiB (десятки миллионов однобайтовых элементов) вдвое хуже прежнего для 16 MiB. ADR это отмечает как стоимость отказа; исправление вне D1 (report-only, вопрос владельцу).

### Допущения

1. Значения `S_max`, `C_max`, `B_max` принятые (ADR, «Значения лимитов»); менять их нельзя. Коды ошибок прежние: `RESOURCE_LIMIT_EXCEEDED` с указателем `/series` (ряды и ячейки) и `""` (размер), через API `413`.
2. Отклонение от буквы ADR: `MAX_RESOURCE_BYTES` в `LocalApi.kt` остаётся 16 MiB (пределы `source_context`, PostgreSQL и capture не расширяются). Единственное, что требует нового значения, это производный `MAX_JOB_REQUEST_BYTES`: он должен допускать корректный запрос со снимком 32 MiB рядом с максимальными PostgreSQL-частями. Формула меняется с `3 * MAX_RESOURCE_BYTES` на `MAX_RESOURCE_SNAPSHOT_BYTES + 2 * MAX_RESOURCE_BYTES` (снимок + `postgres_pre` + `postgres_post`), итог растёт ровно на 16 MiB. Само значение 32 MiB для снимка берётся из одной константы ядра, дубля в `LocalApi.kt` нет. Тот же предел (`MAX_RESOURCE_SNAPSHOT_BYTES + 1`) задаётся `formFieldLimit` в `receiveJob`; `receivePostgresCapture` (`:959`) остаётся на 16 MiB.
3. Онлайн-источники: `SourceAnalysis.kt` и `PromqlSource.kt` читают константы ядра и поднимаются автоматически. Число запросов на профиль (`MAX_SOURCE_QUERIES = 32`) для лимитов снимка не обязательно: эффективный онлайн-потолок остаётся 16 x 32 = 512 рядов (меньше `S_max`). ADR относит подъём до 16 x 64 к срезу автошага (контракт конфигурации). Документация должна прямо называть 512, а не 1 024.
4. Identity: блок `limits` меняется только у анализов со снимком; анализ без снимка остаётся байт-в-байт прежним (существующий golden). Значения в блоке: `resource_series_max` "1024", `resource_cells_total_max` "1500000", `resource_snapshot_bytes_max` "33554432". Прежние анализы со снимком не находятся кэшем и считаются заново; сохранённые остаются как есть; перезакрепление baseline отдельно (решение владельца).
5. Новая golden-фикстура: `analysis-identity-resources.v1.json` (identity с примером `basic.json` как снимком), `.sha256` рядом; регистрируется в `fixtures/slice1/manifest.json` и в `requiredArtifacts` `FixtureManifestTest`. Фикстура порождается однократно выводом самого кода и проверяется глазами против существующей фикстуры без снимка и против значений ADR (в тесте значения пределов заданы литералами независимо от констант).
6. Нагрузочный тест на 1 024 x 1 464 ячеек с настоящими числами в юнит-тесты не входит: удержание около 200-250 байт на ячейку (замер) упирается в кучу рабочего процесса Gradle. Граничные тесты идут на `null`-ячейках (одиночный `JsonNull`) и на коротких рядах при большом `point_count`; тяжёлая проверка выполняется вручную через CLI с генерацией файла на лету (`tools/perf/adr-a` из замерочной ветки, в репозиторий не коммитится), результат в отчёте.
7. Пример `examples/valid/basic.json` не меняется; отдельный пример на 1 024 рядов не добавляется (файл на мегабайты; границы покрыты тестами).
8. `docs/analytics-scale-triage.md:81` («адаптер выдаёт <=64 series на сервисную область») это критерий готовности адаптера свёртки подов, а не предел ядра; его смысл решает владелец. Строка `:27` (таблица текущего конверта) обновляется.
9. Требование к ОЗУ пишется в `docs/user/slice-1-local-analysis.md` по ADR 0014 (следствие (c)): проверенный минимум кучи 384 МБ, куча по умолчанию четверть ОЗУ, значит около 1,5 ГБ ОЗУ нижняя граница; рекомендация с запасом кучи 512 МБ (ОЗУ не менее 2 ГБ) на один анализ, ориентир 1 GiB кучи (4 ГБ ОЗУ) для одного анализа со снимком до 1,5 млн ячеек, вдвое больше при двух одновременных; при меньшем ОЗУ `-Xmx` задаётся через `JAVA_OPTS`. `-Xmx` в коде не меняется.

### Вопросы владельцу (не блокируют D1)

1. Согласны ли с отклонением от буквы ADR (допущение 2): `MAX_RESOURCE_BYTES` остаётся 16 MiB для `source_context`, PostgreSQL и capture, снимок 32 MiB задаётся константой ядра?
2. `E_max` в коде отсутствует, а D1 поднимает ряды в 16 раз: результат одного анализа при 1 024 рядах и 64 окнах достигает около 66 МБ и читается целиком (`LocalApi.kt:841-845`). Вводить ли предел `E_max` отдельным срезом до открытия D1 пользователям (ADR держит его предварительным до замера открытия результата в UI)?
3. Онлайн: поднять `MAX_SOURCE_QUERIES` 32 -> 64 (16 x 64 = 1 024) отдельным срезом сейчас или в срезе автошага, как написано в ADR?
4. Худший случай памяти разбора (JSON-дерево строится до проверок числа ячеек): добавить ли дешёвую предпроверку числа элементов в сканере до `parseToJsonElement`? Измеренный худший случай приведён в отчёте.
5. `docs/analytics-scale-triage.md:81`: критерий «<=64 series на сервисную область» остаётся как есть (критерий адаптера) или заменяется лимитом 1 024?

## Global Constraints

- Новых зависимостей нет; `-Xmx` и `JAVA_OPTS` в коде и скриптах сборки не меняются.
- Публичный контракт `resource-snapshot.v1` меняется аддитивно: ни один предел не уменьшается, `git diff origin/main -- docs/contracts/resources/v1/resource-snapshot.schema.json` содержит только `maxItems` и описание.
- Пределы точек в ряду (100 000), окон (64), правил (256), шага (1-60 с), меток и значений не меняются; `E_max` не вводится.
- Пределы `source_context` (16 MiB на часть, 32 MiB всего), PostgreSQL-частей и capture (16 MiB) не меняются.
- Файлы правятся только из EXPECTED FILES; замерочный код (тайминги на stderr, `tools/perf/adr-a/`) в ветку не переносится.
- Код и тесты содержат только ASCII (русский допустим в документации и комментариях уже существующего вида).
- Коммиты атомарные, Conventional Commits, явные пути, без `git add .` и `-A`; push, PR, merge запрещены.

## Review Focus

- Полнота: все ли места, где предел продублирован числом (`grep -rn "64\|500_000\|16 \* 1024 \* 1024"` по `src/main` для ресурсных пределов, схема, документы).
- Граничные значения и коды ошибок на валидаторе и на multipart (`413`, а не `422`).
- Identity: меняется только `limits` и только со снимком; golden без снимка не тронут; хэши `basic.json` (`semanticSha256`, `configSha256`) не изменились.
- Безопасность: память при 32 MiB и 1,5 млн ячеек; чтение тела запроса ограничено (`Content-Length`, `readNBytes(limit + 1)`); отсутствие расширения пределов `source_context` и PostgreSQL.
- Документация не обещает онлайн 1 024 рядов.

## Tasks

### Task 1: пределы ядра и схема (RED сначала)

**Files:**

- Modify: `src/test/kotlin/io/ltverdict/core/ResourceSnapshotTest.kt`
- Modify: `src/main/kotlin/io/ltverdict/core/ResourceSnapshot.kt` (строки 651-654)
- Modify: `docs/contracts/resources/v1/resource-snapshot.schema.json` (строка 14 и описание)

**Interfaces:** `validateResourceSnapshot(source, maxBytes = MAX_RESOURCE_SNAPSHOT_BYTES): ResourceValidation` не меняется; константы `internal const val` меняют только значения. В тесте используются `ResourceValidation.Valid.snapshot.series`, `.semanticSha256`, `.configSha256`, `.rawBytes()` (уже есть).

- [ ] **Step 1: тесты (RED).** В `ResourceSnapshotTest` (a) заменить литерал `16 * 1024 * 1024` в `assertInvalid` (строка 180) на `MAX_RESOURCE_SNAPSHOT_BYTES`; (b) добавить тесты и вспомогательные функции:

```kotlin
    @Test
    fun `limits equal the values accepted by ADR 0014 and the schema`() {
        assertEquals(1024, MAX_RESOURCE_SERIES)
        assertEquals(1_500_000L, MAX_RESOURCE_CELLS)
        assertEquals(32 * 1024 * 1024, MAX_RESOURCE_SNAPSHOT_BYTES)
        assertEquals(100_000, MAX_POINTS_PER_SERIES)
        val schema = Json.parseToJsonElement(Files.readString(Path.of("docs/contracts/resources/v1/resource-snapshot.schema.json"))).jsonObject
        val series = schema.getValue("properties").jsonObject.getValue("series").jsonObject
        assertEquals(MAX_RESOURCE_SERIES, series.getValue("maxItems").jsonPrimitive.int)
    }

    @Test
    fun `series count boundary accepts 64 and 1024 and rejects 1025 with the previous code`() {
        assertEquals(64, valid(gridSnapshot(seriesCount = 64, pointCount = 1)).snapshot.series.size)
        assertEquals(1024, valid(gridSnapshot(seriesCount = 1024, pointCount = 1)).snapshot.series.size)

        val invalid = invalid(gridSnapshot(seriesCount = 1025, pointCount = 1))

        assertEquals("RESOURCE_LIMIT_EXCEEDED", invalid.errors.first().code)
        assertEquals("/series", invalid.errors.first().jsonPointer)
        assertEquals("too many series", invalid.errors.first().message)
    }

    @Test
    fun `cell boundary accepts 1500000 and rejects the next reachable product`() {
        val accepted = valid(gridSnapshot(seriesCount = 1000, pointCount = 1500))
        assertEquals(1500, accepted.snapshot.pointCount)
        assertEquals(1000, accepted.snapshot.series.size)

        // 557 x 2693 = 1 500 001 is the smallest reachable product above the limit (1500001 = 557 * 2693).
        val invalid = invalid(gridSnapshot(seriesCount = 557, pointCount = 2693, valuesPerSeries = 1))

        assertEquals("RESOURCE_LIMIT_EXCEEDED", invalid.errors.first().code)
        assertEquals("/series", invalid.errors.first().jsonPointer)
        assertEquals("too many resource cells", invalid.errors.first().message)
    }

    @Test
    fun `size boundary accepts exactly 32 MiB and rejects one byte more`() {
        val limit = 32 * 1024 * 1024
        val json = gridSnapshot(seriesCount = 1, pointCount = 1)

        val accepted = valid(padded(json, limit))
        assertEquals(limit, accepted.rawBytes().size)

        val invalid = invalid(padded(json, limit + 1))
        assertEquals("RESOURCE_LIMIT_EXCEEDED", invalid.errors.first().code)
        assertEquals("", invalid.errors.first().jsonPointer)
    }

    @Test
    fun `the previous contract example keeps its semantic and config hashes`() {
        val valid = valid(Files.readAllBytes(Path.of("docs/contracts/resources/v1/examples/valid/basic.json")))

        assertEquals("fbb4c398ae0d40bca8c6dc2f2a3d0b9502f1c27515e702853599aa6b6d572097", valid.semanticSha256)
        assertEquals("7fc69b1a3fd9b3e26363d2041efb2d4a57e2c9e786996303f33f03ba99590325", valid.configSha256)
    }

    private fun invalid(bytes: ByteArray): ResourceValidation.Invalid =
        assertInstanceOf(ResourceValidation.Invalid::class.java, validateResourceSnapshot(ByteArrayInputStream(bytes)))

    /** All-null grid: one shared JsonNull keeps a 1.5M-cell fixture small enough for the test heap. */
    private fun gridSnapshot(
        seriesCount: Int,
        pointCount: Int,
        valuesPerSeries: Int = pointCount,
    ): ByteArray {
        val values = List(valuesPerSeries) { "null" }.joinToString(",", "[", "]")
        val series =
            (0 until seriesCount).joinToString(",") { index ->
                """{"id":"s$index","metric":"cpu_used","unit":"ratio","entity":"host","role":"system","aggregation":"interval_mean","values":$values}"""
            }
        return (
            """{"schema_version":"resource-snapshot.v1","load_input_sha256":"$HASH","start_epoch_ms":0,"step_ms":1000,""" +
                """"point_count":$pointCount,"series":[$series]}"""
        ).encodeToByteArray()
    }

    private fun padded(
        json: ByteArray,
        size: Int,
    ): ByteArray = ByteArray(size) { ' '.code.toByte() }.also { json.copyInto(it) }
```

Добавить импорты `kotlinx.serialization.json.Json`, `kotlinx.serialization.json.int`, `kotlinx.serialization.json.jsonObject` (`jsonPrimitive` уже есть). Если валидатор отвергнет all-null ряды по другой причине (например, требование значения), заменить `null` на `0` только для случая `1000 x 1500` (6 байт на ячейку, 9 MB) и сообщить.

- [ ] **Step 2: RED.** Запустить `.\gradlew.bat test --tests "io.ltverdict.core.ResourceSnapshotTest" --offline`. Ожидаемо красные: пределы (`MAX_*`), 1024 ряда (отвергается), 1000 x 1500 (отвергается), 32 MiB (отвергается). Тест хэшей зелёный (закрепляет прежнее поведение).
- [ ] **Step 3: реализация.** В `ResourceSnapshot.kt`:

```kotlin
internal const val MAX_RESOURCE_SNAPSHOT_BYTES = 32 * 1024 * 1024
internal const val MAX_RESOURCE_SERIES = 1024
internal const val MAX_POINTS_PER_SERIES = 100_000
internal const val MAX_RESOURCE_CELLS = 1_500_000L
```

В схеме `"series": { "type": "array", "minItems": 1, "maxItems": 1024, ... }` и добавить в `series` описание: `"description": "Runtime additionally enforces at most 1500000 cells (series x point_count) and a 32 MiB file."` (по образцу описания `values`).

- [ ] **Step 4: GREEN.** Тот же запуск, затем `node ui/scripts/verify-policy-schema.mjs` и `npm --prefix ui run test:contracts`.
- [ ] **Step 5: коммит.** `git add src/main/kotlin/io/ltverdict/core/ResourceSnapshot.kt docs/contracts/resources/v1/resource-snapshot.schema.json src/test/kotlin/io/ltverdict/core/ResourceSnapshotTest.kt`, `feat(core): raise resource snapshot limits to 1024 series, 1.5M cells, 32 MiB`.

### Task 2: multipart-загрузка и `MAX_JOB_REQUEST_BYTES`

**Files:**

- Modify: `src/test/kotlin/io/ltverdict/web/LocalApiTest.kt` (строка 1151 и новый тест рядом)
- Modify: `src/main/kotlin/io/ltverdict/web/LocalApi.kt` (строки 1736-1742)

**Interfaces:** приватная `MAX_JOB_REQUEST_BYTES`; в `LocalApi.kt` добавить `import io.ltverdict.core.MAX_RESOURCE_SNAPSHOT_BYTES` (константа `internal`, тот же модуль). Публичные маршруты и коды не меняются.

- [ ] **Step 1: тесты (RED).** В тесте `invalid resources return structured errors before job submission` заменить `ByteArray(16 * 1024 * 1024 + 1) { 32 }` на `ByteArray(MAX_RESOURCE_SNAPSHOT_BYTES + 1) { 32 }` (импорт `io.ltverdict.core.MAX_RESOURCE_SNAPSHOT_BYTES`). Добавить тест по образцу соседних (`withServer` с `jobsFactory`, как в тесте на строке 1095-1130):

```kotlin
    @Test
    fun `resource snapshot upload accepts exactly 32 MiB`() {
        val submissions = AtomicInteger()
        withServer(
            jobsFactory = {
                AnalysisJobs(1) { request, _, _ ->
                    submissions.incrementAndGet()
                    check(request.resources != null)
                    AnalysisOutcome(request.input.runId, FAKE_ANALYSIS_ID, byteArrayOf(), tempDir)
                }
            },
        ) { store, api ->
            val input = store.acceptInput(ByteArrayInputStream(SPIKE_DROP.bytes()), SPIKE_DROP.filename)
            val json =
                """{"schema_version":"resource-snapshot.v1","load_input_sha256":"${input.sha256}","start_epoch_ms":0,"step_ms":1000,"point_count":2,"series":[{"id":"cpu","metric":"cpu_used","unit":"ratio","entity":"vm","role":"system","aggregation":"interval_mean","values":[0.1,0.9]}]}"""
                    .encodeToByteArray()
            val exact = ByteArray(32 * 1024 * 1024) { ' '.code.toByte() }.also { json.copyInto(it) }
            api.bootstrap()

            assertEquals(FAKE_ANALYSIS_ID, api.createJob(input.runId, resources = exact).analysisId(api))
            assertEquals(1, submissions.get())
        }
    }
```

Проверить, что `SPIKE_DROP`, `FAKE_ANALYSIS_ID`, `tempDir`, `analysisId(api)` доступны так же, как в соседних тестах (копировать сигнатуры оттуда).

- [ ] **Step 2: RED.** `.\gradlew.bat test --tests "io.ltverdict.web.LocalApiTest" --offline`. Новый тест с ядром из Task 1, но без правки `formFieldLimit`, падает по таймауту клиента (часть больше 16 MiB отвергается 400, см. расхождение 2). Изменение `MAX_JOB_REQUEST_BYTES` производное и наблюдаемого теста при разумной памяти не имеет (тело 32 MiB меньше прежней суммы; см. допущение 2 и Review Focus).
- [ ] **Step 3: реализация.** В `LocalApi.kt` в `receiveJob` заменить `formFieldLimit = (MAX_RESOURCE_BYTES + 1).toLong()` на `(MAX_RESOURCE_SNAPSHOT_BYTES + 1).toLong()` и:

```kotlin
// 16 MiB: source_context, PostgreSQL parts and capture. The resource snapshot has its own limit in the core.
private const val MAX_RESOURCE_BYTES = 16 * 1024 * 1024
...
private const val MAX_JOB_REQUEST_BYTES =
    MAX_RESOURCE_SNAPSHOT_BYTES + 2 * MAX_RESOURCE_BYTES + MAX_CONTEXT_BYTES + 4 * 1024 * 1024 +
        MAX_POLICY_BYTES + MAX_DIAGNOSTIC_BYTES + MAX_CAPACITY_PLAN_BYTES + MAX_TREND_PLAN_BYTES +
        MAX_MULTIPART_OVERHEAD_BYTES
```

- [ ] **Step 4: GREEN.** Тот же запуск.
- [ ] **Step 5: коммит.** `feat(api): let job uploads carry a 32 MiB resource snapshot`, файлы: `LocalApi.kt`, `LocalApiTest.kt`.

### Task 3: identity и golden-фикстура со снимком

**Files:**

- Modify: `src/test/kotlin/io/ltverdict/core/AnalysisResultGoldenTest.kt`
- Create: `fixtures/slice1/identity/analysis-identity-resources.v1.json`, `fixtures/slice1/identity/analysis-identity-resources.sha256`
- Modify: `fixtures/slice1/manifest.json`, `src/test/kotlin/io/ltverdict/fixtures/FixtureManifestTest.kt`

**Interfaces:** `analysisIdentity(input, policy, config, resources = ...)` (уже есть); код identity не меняется.

- [ ] **Step 1: тест (RED, файла фикстуры ещё нет).** Рядом с `analysis identity matches the committed bytes and hash` добавить:

```kotlin
    @Test
    fun `analysis identity with a snapshot pins the resource limits`() {
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
        val resources =
            validateResourceSnapshot(
                ByteArrayInputStream(Files.readAllBytes(Path.of("docs/contracts/resources/v1/examples/valid/basic.json"))),
            ) as ResourceValidation.Valid
        val expected = Files.readAllBytes(Path.of("fixtures/slice1/identity/analysis-identity-resources.v1.json"))
        val expectedHash =
            Files
                .readString(Path.of("fixtures/slice1/identity/analysis-identity-resources.sha256"))
                .trim()

        val actual = analysisIdentity(input, policy, EngineConfig(), resources = resources)

        assertArrayEquals(expected, actual)
        assertEquals(expectedHash, sha256Hex(actual))
        val limits = Json.parseToJsonElement(actual.decodeToString()).jsonObject.getValue("limits").jsonObject
        assertEquals("1024", limits.getValue("resource_series_max").jsonPrimitive.content)
        assertEquals("1500000", limits.getValue("resource_cells_total_max").jsonPrimitive.content)
        assertEquals("33554432", limits.getValue("resource_snapshot_bytes_max").jsonPrimitive.content)
        assertEquals("100000", limits.getValue("resource_points_per_series_max").jsonPrimitive.content)
    }
```

- [ ] **Step 2: фикстура (делает оркестратор, не Codex).** Однократно сгенерировать байты фактическим выводом `analysisIdentity`, сравнить глазами с `analysis-identity.v1.json` (должны добавиться только ключи `resource_*`, `modules`, `input_versions.resources` и ресурсные `limits`), записать `.json` (канонический JSON без перевода строки, как существующая) и `.sha256` (hex + `\n` как существующая), добавить обе записи в `manifest.json` (SHA-256 файлов) и в `requiredArtifacts` `FixtureManifestTest`.
- [ ] **Step 3: GREEN.** `.\gradlew.bat test --tests "io.ltverdict.core.AnalysisResultGoldenTest" --tests "io.ltverdict.fixtures.FixtureManifestTest" --tests "io.ltverdict.core.CanonicalJsonTest" --offline`.
- [ ] **Step 4: коммит.** `test(core): pin the resource limits in a golden identity fixture`, явные пути пяти файлов.

### Task 4: документация и CHANGELOG

**Files:** `docs/user/slice-1-local-analysis.md` (около 466-467), `docs/architecture/slice-1-local-runtime.md` (около 165), `docs/user/online-sources.md` (около 127, 254, 355-356), `docs/analytics-scale-triage.md` (27), `docs/adr/0014-...md` (отметка), `CHANGELOG.md` (Changed).

- [ ] Обновить лимиты (файл 32 MiB, 1 024 series, 100 000 точек на series, 1 500 000 cells, 64 окна, 256 правил); добавить в пользовательскую документацию подраздел о памяти по допущению 9 (числа из ADR, что проверено и что нет); в архитектуре: «Snapshot ограничен 32 MiB; declared общий body ограничен суммой максимальных частей (snapshot 32 MiB + PostgreSQL-части и contexts) + overhead»; в `online-sources.md`: общий cap 1 024 series и 1 500 000 cells на снимок, но эффективно до 512 рядов (16 профилей x 32 запроса); источник 16 MiB на context и 64 MiB raw не менялись; snapshot <= 32 MiB.
- [ ] `CHANGELOG.md`, Changed: пределы, identity меняется у анализов со снимком (прежние анализы со снимком не найдутся в кэше и считаются заново, сохранённые остаются), baseline перезакрепляется отдельно; схема v1 обновлена на месте (старая сборка отвергает файлы больше 64 рядов).
- [ ] ADR 0014: одна строка «Реализовано в D1 (PR: ...)» после блока статуса; статус не менять; не править остальное.
- [ ] `markdownlint-cli2@0.23.2` по изменённым `.md`, `git diff --check`.
- [ ] Коммит `docs: document the raised resource snapshot limits`.

## Verification (после всех задач)

1. `.\gradlew.bat test` для затронутых классов, затем полный `.\gradlew.bat check` под мьютексом `Global\ltv-heavy` с приоритетом ниже обычного.
2. `node ui/scripts/verify-policy-schema.mjs`, `npm --prefix ui run test:contracts` (e2e не нужны: UI не менялся).
3. Сравнение старой и новой сборок `ltv analyze` на одном входе (JTL + снимок на 64 ряда): `analysis-result.json` байт-идентичен, `identity.json` различается только тремя значениями блока `limits`.
4. Вручную через CLI: снимки 1 024 x 1 464 (1 499 136 ячеек), 1 024 x 1 466 (отказ) и большой размер; куча 512 МБ; худший случай памяти для 32 MiB однобайтовых элементов (без коммита файлов).
5. Независимое ревью Codex `git diff origin/main...HEAD` против ADR 0014.
