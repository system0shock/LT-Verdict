# W3.1: зонды источников, перезагрузка профилей и `ltv doctor` (план реализации)

> **Для исполнителя:** обязательный под-навык `superpowers:subagent-driven-development` (рекомендуется) или `superpowers:executing-plans`.
> Шаги отмечены чекбоксами. Реализация по `superpowers:test-driven-development`: красный тест, зелёный код. Gradle только через
> `Invoke-LtvSlot`/`Invoke-LtvExclusive` из `.worktrees/_tools/ltv-slot.ps1`.

**Цель:** настроить профиль Prometheus (VictoriaMetrics, InfluxQL, OpenSearch, PostgreSQL) без перезапуска сервера и без ручного хэша: выполнить
запрос через сохранённое подключение и увидеть число серий и метки, проверить профиль, посчитать семантический хэш снимка, собрать всё в
`ltv doctor`. Агент не видит учётных данных.

**Архитектура:** новый файл `sources/SourceProbe.kt` (зонд поверх существующих `SourceHttp` и декодеров, отдельный ослабленный декодер ответа),
новый `cli/CliProbe.kt` и `cli/CliDoctor.kt`, три-четыре маршрута в `web/SourceRoutes.kt`, небольшой `SourceRegistry` для атомарной смены
набора (профили, `SourceHttp`, `PromqlSource`). Ни одна строка анализа (`AnalysisService`, `decodePromqlMatrix`, identity, результат) не меняется.

**Технологии:** Kotlin, Ktor, существующий `java.net.http` в `SourceHttp`. Новых зависимостей нет.

**Строка перечня:** W3.1 в [перечне работ](2026-10-08-review-work-plan.md). Основание: usability B5, B7 (в scratchpad ревью, в репозиторий не
перенесено); PRC v0.6 FR-MOD-06 и критерий приёмки 13 (`ltv doctor` заранее показывает доступные и пропущенные возможности). Связанные ADR:
[0007](../../adr/0007-opt-in-online-sources.md) (источники только по явному включению), [0008](../../adr/0008-postgresql-pre-post-capture.md),
[0014](../../adr/0014-resource-series-limits-autostep-arm-api.md), [0025](../../adr/0025-mtls-on-source-connector.md).

Класс задачи (brainstorming): архитектурный по границе доверия (новые маршруты API, достижимые агентом, и перезагрузка конфигурации), но
малый по коду: готовые узлы (`SourceHttp`, декодеры, `validateResourceSnapshot`, `readSourceConnections`) используются как есть. Вопросы
владельцу не задаются; спорное решено ruling'ами ниже, подлинные решения владельца вынесены в раздел «Вопросы владельцу» с рекомендацией.
Заморозка ширины D1 соблюдена: новых источников нет, только зонды существующих.

## Блок для AGENTS.md

```text
REQUESTED: (1) команды и API: выполнить запрос через сохранённое подключение и вернуть число серий и метки; проверить профиль; посчитать
  семантический хэш снимка; (2) `ltv doctor` собирает проверки в один отчёт; (3) агент не видит учётных данных; (4) критерий:
  настройка Prometheus-профиля не требует перезапуска сервера и ручного хэша.
REQUIRED TO ACHIEVE IT:
  - sources/SourceProbe.kt (новый): запрос зонда, ослабленный декодер `query_range` (счёт серий и метки вместо "ровно одна серия"),
    очистка вывода, проверка "принял бы анализ" вызовом нетронутого `decodePromqlMatrix`/`decodeInfluxqlResponse`;
  - sources/SourceHttp.kt: ограничение числа попыток, ответа и времени для одного запроса зонда; код состояния HTTP в `SourceHttpFailure`;
  - sources/PromqlSource.kt: вынос построения параметров `query_range` в функцию, общую для анализа и зонда (запрос зонда равен
    боевому по построению); sources/SourceConfig.kt: `validateInfluxqlExpression` и проверка `$__interval` становятся `internal`;
  - sources/SourceRegistry.kt (новый, малый): текущий набор и атомарная замена из файла `--connections`, заданного при старте;
  - cli/CliProbe.kt (новый): `ltv source validate|probe|hash`; cli/CliDoctor.kt (новый): `ltv doctor`; cli/CommandLine.kt: диспетчер,
    usage, передача пути `--connections` и реестра в `LocalApiContext`;
  - web/SourceRoutes.kt: `POST /api/sources/reload`, `/api/sources/validate`, `/api/sources/probe`, `POST /api/resources/semantic-hash`;
    web/LocalApi.kt (необязательное поле реестра в контексте), web/JobRoutes.kt и web/GrafanaRoutes.kt (чтение профилей через реестр);
  - docs/contracts/sources/probe/v1 (схемы `source-probe.v1`, `source-check.v1`, `doctor-report.v1`, `source-reload.v1`, `resource-hash.v1`
    и примеры), ADR 0033, docs/user/online-sources.md, тесты, fixtures/http-layer (новые маршруты);
  - ui/src: кнопка "Перечитать профили" (PR 4, отделимо).
NOT REQUIRED:
  - новые источники и виды запросов (D1), запись профилей через API, произвольные адреса и учётные данные в запросе;
  - ослабление `decodePromqlMatrix` и "по подам" в боевом анализе (боевой анализ по-прежнему требует ровно одну серию, ruling P1);
  - предложение запроса по метрик-паку, авто-подбор меток, генератор профилей, "capability preview" по пакам PRC 17.2 (отдельная работа);
  - текст ошибок источника в ответе (ruling P6), чтение профилей Jenkins (`--jenkins-config`), горячая перезагрузка по изменению файла;
  - изменение `analysis-result`, identity, `analysis_id`, `SourceRequest`/`source-request.v4`, UI-экрана выбора источников;
  - инструмент MCP (W3.2): здесь только контракт, под который он ляжет.
EXPECTED FILES TO CHANGE:
  новые: src/main/kotlin/io/ltverdict/sources/SourceProbe.kt, .../sources/SourceRegistry.kt, .../cli/CliProbe.kt, .../cli/CliDoctor.kt,
    docs/adr/0033-source-probes-and-profile-reload.md, docs/contracts/sources/probe/v1/*.schema.json и examples/*,
    src/test/kotlin/io/ltverdict/sources/SourceProbeTest.kt, SourceRegistryTest.kt, cli/CommandLineProbeTest.kt, cli/CommandLineDoctorTest.kt,
    web/SourceProbeApiTest.kt, changelog.d/w3-1-*.added.md, этот план;
  правки: sources/SourceHttp.kt, sources/PromqlSource.kt, sources/SourceConfig.kt, cli/CommandLine.kt, web/SourceRoutes.kt, web/LocalApi.kt,
    web/JobRoutes.kt, web/GrafanaRoutes.kt, fixtures/http-layer/routes.txt и responses.txt (флаг LTV_UPDATE_HTTP_SNAPSHOT),
    docs/user/online-sources.md, README.md (строка команд), ui/src/shell/NewAnalysisPanel.vue или RunSetup.vue и labels.ts (PR 4).
```

## Что нашлось в коде (факты, на которых стоят ruling'и)

- Профили читаются один раз при старте: `ltv ui --connections` (`cli/CommandLine.kt:620-657`) строит `SourceHttp(profiles)` и
  `PromqlSource(profiles, it)`; замыкание `AnalysisJobs` держит этот `PromqlSource`, `LocalApiContext.sourceProfiles` неизменяемый список.
  `SourceHttp.execute` отказывает любому профилю, не равному своей копии при создании (`SOURCE_PROFILE_NOT_CONFIGURED`,
  `sources/SourceHttp.kt:105-107`). Поэтому "без перезапуска" требует новой тройки (профили, `SourceHttp`, `PromqlSource`), а не правки списка.
  CLI-путь (`ltv analyze --connections`) файл читает при каждом запуске, ему перезагрузка не нужна.
- Боевой декодер требует ровно одну серию: `decodePromqlMatrix` даёт `AMBIGUOUS_SERIES` при `results.size != 1`
  (`sources/PromqlSource.kt:258`; в перечне ссылка `PromqlSource.kt:254`, строка сдвинулась), а `decodeInfluxqlResponse` то же для `series`
  и `statement` (`sources/InfluxqlSource.kt`). Ослаблять их нельзя: от них зависит содержимое снимка и identity (`source_acquisition_sha256`).
- Учётные данные в профиле это только имена переменных окружения (`SourceAuth.Bearer(tokenEnv)` и т. д., `sources/SourceConfig.kt:47-63`).
  Значение читается в момент запроса (`credential`, `sources/SourceHttp.kt:508-515`), ошибки источника выходят кодом
  (`SourceHttpFailure(code)`), тела ответов и тексты исключений наружу не идут. URL профиля не допускает userinfo, query и fragment
  (`validateProfileUrl`, `sources/SourceHttp.kt:451-458`), редиректы выключены (`Redirect.NEVER`).
- Лимиты `SourceHttp` по умолчанию: ответ 16 МиБ (`MAX_HTTP_RESPONSE_BYTES`, приватный параметр `responseLimit` у `execute`), таймаут минимум по
  профилям одного origin (по умолчанию 30 с, до 300 с), попытки `maxAttempts` до 10. Состояние лимитов (токены, параллельность) живёт в
  `OriginState` внутри экземпляра `SourceHttp`.
- Семантический хэш снимка это SHA-256 от `semanticJson()` в `core/ResourceSnapshot.kt:464`: `schema_version`, `load_input_sha256`,
  `start_epoch_ms`, `step_ms`, `point_count`, **все значения рядов**, окна и правила. Значит, хэш можно посчитать только по готовому снимку (файл или
  сохранённый анализ); по зонду (число серий и метки) он не считается. Ручной шаг сегодня: "анализ с источником, хэш из `identity.json`, план,
  повторный анализ с `--resources`" (usability 1.6).
- Существующая проверка выражений профиля: PromQL должен содержать `$__interval` (`parseQueries`, `sources/SourceConfig.kt:614`), InfluxQL проходит
  `validateInfluxqlExpression` (один `SELECT`, без `;`, комментариев и `INTO`, `fill` только `null`/`none`; `sources/SourceConfig.kt:633`).
- `GET /api/sources` отдаёт только `id`, `source_kind`, `transport`, `arm` (`web/SourceRoutes.kt:36-57`): модель для всего, что видит агент.
- Снимок HTTP-слоя `fixtures/http-layer/routes.txt` и `responses.txt` (флаг `LTV_UPDATE_HTTP_SNAPSHOT`) перечисляет маршруты: новые маршруты
  меняют оба файла; `W3.7` меняет `responses.txt` позже (см. "Пересечения").

## Ruling'и

**P1. Боевой анализ не ослабляется; зонд читает ответ отдельным декодером.** `decodePromqlMatrix` и `decodeInfluxqlResponse` остаются
байт-в-байт; `SourceProbe.kt` получает свой разбор `data.result` (число серий, метки, число ненулевых ячеек на серию) и вызывает боевой
декодер на тех же байтах, чтобы заполнить `production_check` (`accepted` или код отказа). Так пользователь видит одновременно "запрос вернул 7 серий"
и "анализ принял бы его: нет, `AMBIGUOUS_SERIES`; сузьте селектор или агрегируйте". Почему: B7 именно в этом: нужно видеть, что вернулось, не меняя то, что принимает
анализ. Цена ошибки: расхождение зонда с боевым правилом (зонд сказал "принял бы", анализ отказал). Закрыто тестом, который гоняет оба
декодера на одних и тех же байтах для всех случаев, и общим построением запроса (P3).

**P2. Допустимые запросы.** (a) `profile_query`: `query_id` из профиля, выражение берётся из сохранённого профиля; (b) `ad_hoc`: выражение
в запросе для видов `prometheus`, `victoria_metrics`, `influxdb`, с теми же проверками, что у профиля (PromQL содержит `$__interval`; InfluxQL
проходит `validateInfluxqlExpression`), длиной не более 4096 байт UTF-8 (профиль допускает 65 536; зонду нужно меньше), без управляющих символов
кроме `\n` и `\t`. Для `opensearch` и PostgreSQL выражений нет: зонд это проверка профиля (`profile_check`, P8). Адрес только из сохранённого
профиля (`profile_id`); URL, путь, заголовки, учётные данные и параметры HTTP в запросе зонда не принимаются, неизвестное поле тела это
`INVALID_PROBE`. Почему ad hoc нужен: смысл настройки профиля в том, чтобы испытать выражение до записи в файл. Цена ошибки:
агент получает возможность выполнять любые read-only запросы к Prometheus владельца; смягчение это лимиты P4 и то, что API слушает только
`127.0.0.1` и мутирующие вызовы закрыты CSRF/Origin. Это решение владельца: вопрос Q1.

**P3. Запрос зонда равен боевому по построению.** Построение параметров (`query`, `start`, `end`, `step` для PromQL; `db`, `q`, `epoch` для InfluxQL)
выносится из `PromqlSource.acquire` (`sources/PromqlSource.kt:96-115`) в одну `internal` функцию, которую вызывают и анализ, и зонд;
`$__interval` и плейсхолдеры InfluxQL подставляет та же `resolvedExpression`. Это единственная правка боевого кода: перенос без изменения
поведения, `PromqlSourceTest` и `SourceAnalysisTest` не редактируются. Окно зонда: `window_ms` по умолчанию 300 000 (максимум 3 600 000),
`step_ms` по умолчанию 15 000 (целые секунды 1000..60000, как требует сетка снимка `requireSnapshotGridStep`), число ячеек `window/step` целое от 1 до
240, `end_epoch_ms` по умолчанию "сейчас" (часы внедряются, в тестах фиксированы). Результаты зонда никуда не сохраняются.

**P4. Лимиты зонда (все обязательные и проверяемые).** Один HTTP-запрос и одна попытка (без повторов и ожидания `Retry-After`); ответ не более
4 МиБ (`SOURCE_RESPONSE_TOO_LARGE`); таймаут запроса `min(таймаут профиля, 15 с)`; число подробно описанных серий не более 20, ключей меток не более 16, образцов значений на ключ
не более 5, каждая строка не более 128 байт после очистки; тело запроса API не более 16 КиБ; одновременно не более двух зондов
(`409 BUSY`). Зонд идёт через тот же `OriginState` (лимит частоты и параллельности профиля), то есть не обходит governor. Число серий считается по
всему ответу (он ограничен 4 МиБ), в отчёт идёт точное `series_count`. Цена: ответ крупнее 4 МиБ даёт отказ, а не счёт; пользователь сужает
селектор, что и нужно для боевого запроса.

**P5. Что видит агент и что нет.** Агент видит: `id`, вид, транспорт, плечо профиля; коды и числа; метки и числа ячеек серий зонда после очистки.
Не видит: `base_url` (даже origin), имена и значения переменных окружения, тип и параметры TLS, заголовки, тела ответов, тексты исключений.
Метки очищает `cleanErrorText(raw, 128)` (`core/ErrorGroups.kt:204`: управляющие, форматные, bidi, неназначенные символы заменяются пробелом);
значение метки заменяется на `***`, если ключ содержит `pass`, `secret`, `token`, `key`, `auth`, `cred` (без учёта регистра), если значение равно
любому значению учётных данных профиля, прочитанному из окружения для этого запроса, или начинается со слов Bearer, Basic или Token с пробелом, с `-----BEGIN` или `eyJ`;
число замен идёт в `redacted_count`. Тот же вывод читает человек в `ltv doctor`: `ltv` может запустить сам агент (Claude Code, Qwen Code),
поэтому CLI-вывод проходит ту же очистку и не содержит `base_url`/имён переменных. Почему origin скрыт: стоимость нулевая (файл профиля у человека
есть), а граница "агент не видит инфраструктуру, кроме меток собственного Prometheus" проще проверяется. Цена ошибки: маскирование по именам
даёт ложные срабатывания (метка `monitoring_key` станет `***`); это безопасная ошибка.

**P6. Тексты ошибок источника не пересылаются.** Отказ источника это код из закрытого списка (ниже) и необязательное число `http_status`
(для 4xx/5xx; код состояния не секрет и отличает неверный префикс 404 от разбора 400). Текст `error` Prometheus (например, фрагмент разобранного выражения) не пересылается:
он недоверенный и мог бы стать каналом инъекции в агента. Цена: агент правит выражение без подсказки парсера. Пересмотр после пилота (Q2).

**P7. Офлайн по умолчанию.** PRC критерий 11 (локальный режим без исходящих запросов) и ADR 0007 (источники только по явному включению):
`ltv doctor` и `ltv source validate` по умолчанию делают только локальные проверки (разбор файла, URL профиля, наличие переменных окружения с
учётными данными, файлы TLS и срок клиентского сертификата, согласованность правил и рядов); сетевые проверки только с флагом `--online`. API:
`POST /api/sources/validate` офлайн всегда; `POST /api/sources/probe` онлайн по определению (его вызов и есть явное действие). Цена: `ltv doctor`
без `--online` не поймает неверный адрес; это сказано в выводе ("сеть не проверялась: нужен --online").

**P8. Зонды OpenSearch и PostgreSQL как "проверка профиля".** `opensearch`: запрос строит `buildOpenSearchQuery` по отображению профиля за окно зонда
(`group_limit` не больше 10), ответ разбирает `decodeOpenSearchResponse`; в отчёт идут число групп, число шардов, коды покрытия; **никаких сообщений
и образцов** (тексты журналов недоверенные и могут нести секреты). `postgresql`: `capturePostgresPhase(profile, pre = null, post = false)` под тем же
`postgresCapturePermit`, что и `/api/sources/postgresql/pre`; в отчёт идут `connected`, `read_only`, `database_matches`, наличие расширений
(`pg_stat_statements`, `pg_profile`), число таблиц и их статусы-коды; содержимое фазы не возвращается и не сохраняется. Цена: PostgreSQL-зонд
выполняет тот же набор read-only запросов, что реальный захват "pre" (читает до лимитов профиля), поэтому он не лёгкий; в документации сказано.

**P9. Перезагрузка профилей: явная, из того же файла, атомарная.** `POST /api/sources/reload` без тела; сервер перечитывает ровно тот путь, который
получил при старте (`--connections`), через `readSourceFile` (обычный файл, без ссылок, предел 1 МиБ; как в CLI), строит **новую** тройку
(профили, `SourceHttp`, `PromqlSource`) и заменяет ссылку в `AtomicReference`. Путь из запроса не принимается никогда. Разбор не удался: старый набор
остаётся, ответ `422 SOURCE_CONFIG_INVALID` с кодом (без текста файла). Сервер запущен без `--connections`: `404 SOURCE_RELOAD_NOT_CONFIGURED`. Параллельная
перезагрузка: `409 BUSY` (`tryLock`). Задание берёт набор в момент **старта** выполнения (`registry.current()` в замыкании `AnalysisJobs`) и держит его
до конца; приём задания (`bindJob`) проверяет профиль по набору на момент приёма. Если профиль исчез между приёмом и стартом, задание
завершается `SOURCE_PROFILE_NOT_FOUND` (честный отказ). Если изменился, анализ идёт по новому определению и это видно в evidence: хэши выражений и
`query_set_sha256` уже входят в `source_summary` и identity, дрейф не тихий. Гонка, которую нужно назвать: состояние лимитов (`OriginState`)
живёт в `SourceHttp`; пока идёт задание на старом экземпляре, зонды и новые задания идут на новом, и один origin может получить до двух
полных потоков лимита. Принято: окно перекрытия равно длительности сбора одного задания; перенос `OriginState` между экземплярами усложнил
бы `SourceHttp` без нужды. Цена ошибки: кратковременное удвоение нагрузки на источник при перезагрузке посреди сбора; запрет перезагрузки при
активных заданиях был бы хуже (долгие загрузки блокировали бы настройку).

**P10. Хэш снимка: две формы входа.** `ltv source hash <resource-snapshot.json>` (и API с телом `snapshot`) вызывает `validateResourceSnapshot` и печатает
`semantic_sha256`, `config_sha256`, `load_input_sha256`, число рядов и ячеек; `ltv source hash --run <run-id> --analysis <analysis-id> [--data-dir]`
(и API с `run_id`/`analysis_id`) читает сохранённый снимок анализа через `RunBundleStore`, пересчитывает хэш и сверяет его с `resource_snapshot_sha256` identity (расхождение это
`RESOURCE_HASH_MISMATCH`, признак повреждения). Вторая форма убирает ручное чтение `identity.json` в двухпроходном процессе. Что не решается здесь: планы
capacity/trend/pod-view по-прежнему привязаны к хэшу снимка и не сочетаются с онлайн-сбором в одном вызове (`SOURCE_INPUT_CONFLICT`,
`cli/CommandLine.kt:245`): это вторая половина usability B5 и отдельная работа (отчёт об этом ниже, вопрос Q3). Критерий W3.1 "без ручного хэша"
выполняется для хэша как действия (команда вместо чтения JSON), но не для самого двухпроходного процесса.

**P11. Размещение команд.** `ltv source` уже существует (`pre|post`, `captureSource`); добавляются `validate`, `probe`, `hash`. `ltv doctor` отдельный
верх (так он назван в PRC 17.2). Новая логика в двух новых файлах `cli/`, как `PrepareOpenSearchCommand.kt`; в `CommandLine.kt` только диспетчер и usage.

**P12. Коды выхода `ltv doctor`.** 0: отчёт построен, провалов нет (допустимы `WARN` и `SKIPPED`); 7: отчёт построен, есть хотя бы один `FAIL`
(новая константа `EXIT_DOCTOR_FAILED`; значения 2/3 заняты вердиктом анализа, 4 это ошибка входа, 6 занято каталогом данных); 4: файл
подключений не читается или не проходит разбор; 64: usage. Цена ошибки: CI, использующий `doctor` как шлюз, различает "нет окружения" (7) и "неверная команда" (64).

**P13. ADR 0033 нужен.** Новая граница доверия (маршруты, достижимые агентом; чтение конфигурации по команде; новый класс исходящих запросов), новые
публичные контракты и исключение из "профили читаются при старте" (`docs/user/online-sources.md`). ADR 0033 пишется и принимается владельцем до кода
(PR 0). Что в него выносится: P1-P10, лимиты, коды, схемы, отличие от ADR 0007 (зонд не включает анализ и ничего не сохраняет, но делает
исходящий запрос по явной команде), совместимость с MCP.

## Публичные контракты (запись до кода)

### CLI

```text
ltv source validate --connections <profiles.json> [--profile <id>] [--format text|json]
ltv source probe    --connections <profiles.json> --profile <id> (--query-id <id> | --expression <text> | --expression-file <file>)
                    [--window-ms <n>] [--step-ms <n>] [--end-epoch-ms <n>] [--format text|json]
ltv source hash     <resource-snapshot.json>
ltv source hash     --run <run-id> --analysis <analysis-id> [--data-dir <path>]
ltv doctor          --connections <profiles.json> [--profile <id>] [--online] [--format text|json]
```

`--expression` и `--expression-file` взаимоисключающие и не сочетаются с `--query-id`. `--format` по умолчанию `text`. Повтор флага или неизвестный флаг:
usage (64). `ltv source pre|post` не меняется. `validate` и `hash` офлайн; `probe` и `doctor --online` делают исходящие запросы.
Коды выхода: `validate` и `hash`: 0 успех, 4 вход не разобран, 64 usage; `probe`: 0 запрос выполнен (даже если `production_check.accepted` ложно), 7 запрос не
удался (`status: FAILED`), 4 профиль или `query_id` не найден или файл не разобран, 64 usage; `doctor`: см. P12.

### JSON `source-probe.v1` (вывод `probe` и тело ответа `POST /api/sources/probe`)

```json
{
  "schema_version": "source-probe.v1",
  "profile_id": "prom-main",
  "source_kind": "prometheus",
  "transport": "direct",
  "mode": "ad_hoc",
  "query_id": null,
  "expression_sha256": "<64 hex>",
  "window": { "start_epoch_ms": 1767225000000, "end_epoch_ms": 1767225300000, "step_ms": 15000, "cells": 20 },
  "status": "OK",
  "code": null,
  "http_status": 200,
  "series_count": 7,
  "series": [
    { "labels": { "pod": "api-0", "namespace": "shop" }, "observed_cells": 20, "expected_cells": 20 }
  ],
  "series_truncated": true,
  "label_keys": [
    { "key": "pod", "distinct_values": 7, "sample_values": ["api-0", "api-1", "api-2", "api-3", "api-4"], "truncated": true }
  ],
  "label_keys_truncated": false,
  "redacted_count": 0,
  "warnings": [],
  "production_check": { "accepted": false, "code": "AMBIGUOUS_SERIES" },
  "request_count": 1,
  "elapsed_ms": 412
}
```

`status`: `OK` (ответ разобран; `series_count` может быть 0) или `FAILED` (`code` из списка, `series_count`, `series`, `label_keys` равны `null`/пусты). `mode`:
`profile_query` | `ad_hoc` | `profile_check` (OpenSearch, PostgreSQL; вместо `series` поля `check` с числами и кодами, без текстов). `production_check.code`:
код боевого декодера (`AMBIGUOUS_SERIES`, `LABEL_MISMATCH`, `UNSUPPORTED_RESULT_TYPE`, `SOURCE_WARNINGS`, `OFF_GRID_TIMESTAMP`, ...), `EMPTY_RESULT` для нуля серий.
`warnings`: коды `SOURCE_WARNINGS`, `SOURCE_PARTIAL_RESPONSE` (ответ принят зондом, но боевой анализ его бы отверг). Поля `base_url`, `auth`, `tls`, тела и
тексты ошибок в документе отсутствуют; схема `additionalProperties: false`.

Закрытый список `code` при `status: FAILED`: `SOURCE_PROFILE_NOT_CONFIGURED`, `SOURCE_AUTH_UNAVAILABLE`, `SOURCE_AUTH_INVALID`, `SOURCE_HTTP_AUTH`,
`SOURCE_HTTP_429`, `SOURCE_HTTP_5XX`, `SOURCE_HTTP_STATUS`, `SOURCE_HTTP_ERROR`, `SOURCE_TIMEOUT`, `SOURCE_RESPONSE_TOO_LARGE`, `SOURCE_TLS_CONFIG_INVALID`,
`SOURCE_TLS_HANDSHAKE_FAILED`, `SOURCE_TLS_CLIENT_CERT_EXPIRED`, `SOURCE_CANCELLED`, `MALFORMED_RESPONSE`, `PROMQL_QUERY_FAILED`, `INFLUXQL_QUERY_FAILED`,
`UNSUPPORTED_RESULT_TYPE`, `UNSUPPORTED_HISTOGRAM`, `RESOURCE_LIMIT_EXCEEDED`, `OPENSEARCH_INVALID_MAPPING`, `PG_CONNECTION_FAILED`, `PG_READ_ONLY_REQUIRED`,
`PG_DATABASE_MISMATCH`, `PG_CAPTURE_FAILED`. Ошибки запроса (до сети, HTTP 400): `INVALID_PROBE`, `SOURCE_QUERY_INVALID`, `SOURCE_REQUEST_INVALID`
(окно), `SOURCE_PROFILE_NOT_FOUND` (404), `QUERY_NOT_FOUND` (404), `BUSY` (409).

### JSON `source-check.v1` (вывод `validate`, ответ `POST /api/sources/validate`, часть `doctor-report.v1`)

```json
{
  "schema_version": "source-check.v1",
  "profiles": [
    {
      "profile_id": "prom-main", "source_kind": "prometheus", "transport": "direct", "arm": null, "query_count": 12, "rule_count": 3,
      "checks": [
        { "id": "config", "status": "OK", "code": null },
        { "id": "url", "status": "OK", "code": null },
        { "id": "credentials", "status": "FAIL", "code": "SOURCE_AUTH_UNAVAILABLE", "auth_kind": "bearer" },
        { "id": "tls", "status": "SKIPPED", "code": null }
      ]
    }
  ]
}
```

`status`: `OK`, `WARN`, `FAIL`, `SKIPPED`. Проверки `id`: `config` (профиль разобран), `url` (`validateProfileUrl`), `credentials` (переменные заданы и без управляющих
символов; `auth_kind`: `none|bearer|token|basic`), `tls` (материал читается, клиентский сертификат не истёк, `SOURCE_TLS_*`), `rules` (каждое правило
ссылается на ряд, `rule_count`), `governor` (WARN `GOVERNOR_STRICT`, если `requests_per_second` < 0,1 при > 20 запросов: сбор займёт больше 200 с).
Имён переменных окружения и URL в документе нет.

### JSON `doctor-report.v1` (вывод `doctor --format json`)

`{ schema_version, online: bool, connections: { status, profile_count }, profiles: [source-check профиль + "online": { "queries": [ { "query_id", "status", "series_count",
"observed_cells", "expected_cells", "production_check": {...} } ] } ], summary: { ok, warn, fail, skipped }, expected_verdict: "available"|"degraded"|"unavailable" }`.
`online` присутствует только с `--online`; для OpenSearch и PostgreSQL `profile_check`. Профили по `id`, запросы по порядку в профиле. Текст (`--format text`):

```text
LT Verdict doctor (online: no, сеть не проверялась: нужен --online)
connections        OK  3 profiles
prom-main          prometheus/direct  12 queries
  config           OK
  url              OK
  credentials      FAIL SOURCE_AUTH_UNAVAILABLE (bearer)
  tls              SKIPPED
summary: ok 2, warn 0, fail 1, skipped 1
expected verdict: unavailable (нет учётных данных для prom-main)
```

`expected_verdict` это оценка по проверкам профилей: `available`, если все профили, выбранные для анализа, без `FAIL`; иначе `unavailable` для ресурсной стороны (нагрузочный
вердикт всегда доступен: источники необязательны, PRC критерий 4). Паков метрик и "глубины RCA" из примера PRC здесь нет (NOT REQUIRED).

### HTTP API

| Маршрут | Тело | Успех | Ошибки |
| --- | --- | --- | --- |
| `POST /api/sources/reload` | нет | `200` `source-reload.v1`: `status`, `profile_count`, `profiles` (id), `added`, `removed`, `changed` (id), `revision` (SHA-256 байт файла) | `404 SOURCE_RELOAD_NOT_CONFIGURED`, `422 SOURCE_CONFIG_INVALID`, `409 BUSY` |
| `POST /api/sources/validate` | `{"profile_id"?: "<id>"}` | `200` `source-check.v1` | `404 SOURCE_PROFILE_NOT_FOUND`, `400 INVALID_PROBE` |
| `POST /api/sources/probe` | `{"profile_id", "query_id"?, "expression"?, "window_ms"?, "step_ms"?, "end_epoch_ms"?}` | `200` `source-probe.v1` (в том числе `status: FAILED`) | `400 INVALID_PROBE`/`SOURCE_QUERY_INVALID`/`SOURCE_REQUEST_INVALID`, `404`, `409 BUSY` |
| `POST /api/resources/semantic-hash` | `{"snapshot": {...}}` или `{"run_id", "analysis_id"}` | `200` `resource-hash.v1`: `semantic_sha256`, `config_sha256`, `load_input_sha256`, `series_count`, `point_count` | `422 INVALID_RESOURCES`, `404`, `422 RESOURCE_HASH_MISMATCH` |

Все четыре маршрута мутирующие по методу (`POST`) и проходят защиту `installLocalApi` (Host, Origin, cookie, `X-LTV-CSRF`). `probe` без `query_id` и без `expression` это
`400 INVALID_PROBE`; ровно одно из двух. `GET /api/sources` не меняется (байты ответа прежние).

## Задачи по PR

Порядок: PR 0 (ADR) до кода; PR 1 затем PR 2 и PR 3 (параллельны, пересекаются только в `cli/CommandLine.kt` и `docs/user/online-sources.md`); PR 4 после PR 3. Файловые
пересечения: PR 1 и PR 2 правят `cli/CommandLine.kt` (диспетчер, usage); PR 3 правит `cli/CommandLine.kt` в `ui()` (другая область) и `fixtures/http-layer/*`.

### PR 0 (docs): ADR 0033 `Proposed`

- [ ] Написать `docs/adr/0033-source-probes-and-profile-reload.md` по P1-P13 и схемам; статус `Proposed`, принятие по слову владельца.
- [ ] `markdownlint-cli2`, `lychee`, `verify_slice0`, `changelog_assemble --check`; `Documentation impact: ADR`.

### PR 1 (feat/sources, около 2 дней): зонд, `ltv source validate|probe|hash`

**Файлы:** `sources/SourceProbe.kt` (новый), `sources/SourceHttp.kt`, `sources/PromqlSource.kt` (вынос параметров), `sources/SourceConfig.kt`
(`internal`), `cli/CliProbe.kt` (новый), `cli/CommandLine.kt` (диспетчер, usage), `docs/contracts/sources/probe/v1/*`, тесты `SourceProbeTest`,
`CommandLineProbeTest`, `changelog.d/w3-1-probe.added.md`.

- [ ] Красные тесты `SourceProbeTest` (фиктивный сервер как в `SourceHttpTest`/`OnlineSourceFixture`):
  - три серии PromQL: `series_count` 3, метки, `production_check` `AMBIGUOUS_SERIES`; нуль серий: `series_count` 0, `EMPTY_RESULT`;
    одна серия с чужой меткой профиля: `LABEL_MISMATCH`; ответ с `warnings`: `status: OK`, `warnings: ["SOURCE_WARNINGS"]`;
    `resultType: vector`: `FAILED` `UNSUPPORTED_RESULT_TYPE`; гистограмма: `UNSUPPORTED_HISTOGRAM`;
  - **боевой декодер не ослаблен**: `decodePromqlMatrix` на тех же байтах трёх серий по-прежнему бросает `AMBIGUOUS_SERIES` (ссылка на `PromqlSourceTest`);
  - ограничения вывода: 25 серий дают 20 подробных и `series_truncated`; 20 ключей дают 16; значение 300 байт режется до 128; управляющие и bidi-символы
    заменяются пробелом; метка с ключом `api_token` и значение, равное переменной окружения профиля, маскируются, `redacted_count` верен;
  - утечки: ответ 401 с телом `{"error":"SECRET-BODY"}` и заголовком `Set-Cookie`: в JSON отчёта нет `SECRET-BODY`, значения токена, имён переменных
    окружения, `base_url`, фрагмента URL; исключение с текстом профиля не выходит; `http_status` 401, `code` `SOURCE_HTTP_AUTH`;
  - лимиты: ответ 5 МиБ даёт `SOURCE_RESPONSE_TOO_LARGE`; сервер, который не отвечает, даёт `SOURCE_TIMEOUT` не позже 15 с (часы тестового governor);
    5xx не повторяется (`request_count` 1);
  - запрос равен боевому: фиктивный сервер записывает путь и параметры; для `query_id` параметры (`query`, `start`, `end`, `step`) побайтно равны параметрам
    `PromqlSource.acquire` на том же окне; другого пути и второго запроса нет; редирект не выполняется;
  - ad hoc: без `$__interval` (PromQL) и InfluxQL с `INTO`/`;`/комментарием: `SOURCE_QUERY_INVALID` **без сетевого запроса**; выражение 4097 байт; окно 3 600 001 мс;
    шаг 1500 мс; 241 ячейка: `SOURCE_REQUEST_INVALID`;
  - зонд не меняет анализ: после зонда анализ того же входа с источником даёт тот же `analysis_id` и те же байты (`SourceProbe` не принимает хранилище; тест сравнивает
    дерево каталога данных до и после зонда, оно пусто);
  - OpenSearch: `profile_check` без сообщений и образцов (в ответе фиктивного сервера есть маркер `SECRET-LOG`, его нет в отчёте); PostgreSQL: `profile_check` с
    `connected/read_only/database_matches` и без содержимого фазы (на `PostgresSourceTest`-фикстуре);
  - `ltv source hash`: файл снимка даёт `semantic_sha256`, равный `resource_snapshot_sha256` из `identity.json` анализа с тем же снимком; форма `--run/--analysis`
    даёт тот же хэш; подмена `identity.json` даёт `RESOURCE_HASH_MISMATCH`.
- [ ] Красные тесты `CommandLineProbeTest`: вывод text/json, коды выхода (0/7/4/64), `validate` не делает сетевых запросов (счётчик сервера 0), ни один вывод
  не содержит значения переменных окружения и URL.
- [ ] Реализация (минимальная): `SourceProbe.kt` (`probeSource(profile, spec, http, environment, clock)`; `ProbeSpec`; разбор `data.result` строгим сканером
  `StrictJsonScanner`, как в `PromqlSource.kt:241-247`), `SourceHttp.getBounded(profile, params, maxBytes, maxAttempts, timeoutCap)` (обёртка над `execute`,
  `responseLimit` уже параметр), `SourceHttpFailure.httpStatus`, вынос `queryRangeParameters(...)`, `internal` для `validateInfluxqlExpression`/проверки
  `$__interval`, `CliProbe.kt`.
- [ ] Схемы и примеры в `docs/contracts/sources/probe/v1`; тест Kotlin читает примеры (valid принимаются, каждый invalid отвергается по названной в имени причине),
  как `incident.v1`.
- [ ] Полный прогон по разделу "Без CI" общего брифа; сверка с `ltv-0.1.0.zip`: `ltv analyze` без зонда даёт те же `analysis_id`, `result.json`, stdout (изменений в
  анализе нет).
- [ ] Коммиты атомарные: `feat(sources): source probes`, `feat(cli): ltv source validate, probe and hash`.

### PR 2 (feat/cli, около 1 дня): `ltv doctor`

**Файлы:** `cli/CliDoctor.kt` (новый), `cli/CommandLine.kt` (диспетчер, usage, `EXIT_DOCTOR_FAILED`), `docs/contracts/sources/probe/v1/doctor-report.schema.json`,
тест `CommandLineDoctorTest`, `docs/user/online-sources.md`, `README.md` (строка команд), `changelog.d/w3-1-doctor.added.md`.

- [ ] Красные тесты: без `--online` сервер видит 0 запросов; нет переменной окружения: `FAIL SOURCE_AUTH_UNAVAILABLE`, выход 7, `expected_verdict: unavailable`;
  истёкший клиентский сертификат (`TestPki`): `SOURCE_TLS_CLIENT_CERT_EXPIRED`; `--online` по запросам профиля: на каждый запрос строка с `series_count` и
  `production_check`; профиль с `AMBIGUOUS_SERIES` даёт `WARN` с кодом, выход 0; вывод и JSON не содержат URL и значений окружения; JSON проходит схему;
  `--profile` выбирает профиль, неизвестный даёт 4; файл подключений битый даёт 4.
- [ ] Реализация: `CliDoctor.kt` собирает `source-check` (общая функция с `validate`) и при `--online` вызывает `probeSource` по запросам профиля с бюджетом
  `maxRequestsPerRun` профиля, последовательно, с governor.
- [ ] `docs/user/online-sources.md`: раздел "Проверка профиля: `ltv source validate`, `ltv source probe`, `ltv doctor`".

### PR 3 (feat/api, около 2 дней): реестр, перезагрузка, зонд и хэш в API

**Файлы:** `sources/SourceRegistry.kt` (новый), `web/SourceRoutes.kt`, `web/LocalApi.kt`, `web/JobRoutes.kt`, `web/GrafanaRoutes.kt`, `cli/CommandLine.kt`
(`ui()`: путь и реестр), тест `SourceRegistryTest`, `SourceProbeApiTest`, `fixtures/http-layer/routes.txt` и `responses.txt`, `changelog.d/w3-1-api.added.md`.

- [ ] Красные тесты `SourceRegistryTest`: замена набора атомарна (читатель видит старую или новую тройку, не смесь; два потока); неверный файл оставляет старый набор;
  параллельная перезагрузка даёт `BUSY`; симлинк и необычный файл отклоняются; новая тройка использует новый `SourceHttp` (старый профиль больше не принимается новым
  экземпляром, но держится старым заданием).
- [ ] Красные тесты `SourceProbeApiTest` (образец `LocalApiTest`): правка файла, `POST /api/sources/reload`, `GET /api/sources` показывает новый профиль, задание с новым
  профилем принимается без перезапуска; задание, поставленное до перезагрузки, завершается по старому набору (`ScriptedSource` с воротами); профиль исчез между
  приёмом и стартом даёт `SOURCE_PROFILE_NOT_FOUND`; `404 SOURCE_RELOAD_NOT_CONFIGURED` без `--connections`; `POST` без CSRF-токена `403` для всех четырёх маршрутов
  (дополнить перечень в `LocalSecurityTest`, если он перечисляет маршруты поимённо); путь в теле reload игнорируется или отвергается; тела ответов не содержат
  `base_url`, имён переменных окружения; `GET /api/sources` побайтно прежний; `probe` под лимитом двух параллельных: третий `409 BUSY`; каталог данных до и после
  зонда не изменился.
- [ ] Реализация: `SourceRegistry(path: Path?, load: (Path) -> SourceConnections)` с `current()`, `reload()`, `tryLock`; `LocalApiContext` получает необязательное поле
  `sourceRegistry` (конструкторы позиционные в тестах, поэтому поле последнее и необязательное), хелперы `profiles()`/`http()` в маршрутах; `ui()` строит реестр из
  `--connections`; замыкание `AnalysisJobs` читает `registry.current().promql`.
- [ ] `LTV_UPDATE_HTTP_SNAPSHOT=1` для `LocalApiSnapshotTest`; проверка `git diff fixtures/http-layer`: только добавленные строки (новые маршруты и их ответы
  `403/400` свипа), ни одна прежняя строка не изменена. `BaselineReleaseRulesSnapshotTest` не трогается.
- [ ] Сверка с `ltv-0.1.0.zip` не нужна (байты анализа не меняются), но прогон включает `e2e` онлайн-источников
  (`npx playwright test --config playwright.online-sources.config.ts`).

### PR 4 (feat/ui, около 0,5 дня, отделим): кнопка "Перечитать профили"

- [ ] Красный Playwright (адаптерный): кнопка в блоке выбора источника вызывает `POST /api/sources/reload`, обновляет список, показывает код ошибки без текста файла.
- [ ] `ui/src/api.ts` (метод), компонент и `labels.ts` (русские строки), полный `npm`-набор и оффлайн Playwright.

### Документация и ADR

- `docs/user/online-sources.md`: убрать "Профили загружаются при запуске; изменение файла требует перезапуска backend", описать reload и зонды, границы (P5-P7).
- ADR 0033 (PR 0). `CHANGELOG.md` не трогать; фрагменты `changelog.d/w3-1-*.added.md` в PR 1-4 (в фрагментах обычный текст без ссылок).
- ADR не нужен для PR 1-4 сверх 0033; в 0033 выносятся: P1, P2, P5, P6, P7, P9, P10, коды и схемы.

## Критерии приёмки

1. Фиктивный Prometheus, ответ из 7 серий: `ltv source probe` и `POST /api/sources/probe` возвращают `series_count: 7`, метки, `production_check` `AMBIGUOUS_SERIES`;
   боевой `decodePromqlMatrix` по-прежнему отвергает те же байты (`PromqlSourceTest` без правок зелёный).
2. Правка файла профиля и `POST /api/sources/reload` без перезапуска: новый профиль виден в `GET /api/sources` и принимается заданием; неверный файл оставляет старый набор.
3. `ltv source hash` и API `semantic-hash` дают тот же хэш, что `identity.json` анализа с тем же снимком; ручного чтения `identity.json` не нужно.
4. `ltv doctor` без `--online` не делает ни одного сетевого запроса (счётчик фиктивного сервера 0), выход 7 при `FAIL`; с `--online` отчёт по каждому запросу профиля.
5. Ни JSON зонда, ни вывод CLI, ни ответы API не содержат значений и имён переменных окружения, `base_url`, тел ответов, текстов исключений (тесты на маркерах
   `SECRET-*`); метки очищены и ограничены (20 серий, 16 ключей, 5 значений, 128 байт).
6. Один запрос, один попытка, ответ не более 4 МиБ, таймаут не более 15 с, зонд идёт через `OriginState` профиля; два параллельных зонда, третий `409 BUSY`.
7. Зонд не меняет анализ: `analysis_id`, `analysis-result.json`, identity и дерево каталога данных не зависят от зонда; полный `check` и сверка с `ltv-0.1.0.zip`
   (стандартные входы без новых входов) совпадают по `analysis_id`, `result.json`, stdout.
8. Схемы `source-probe.v1`, `source-check.v1`, `doctor-report.v1`, `source-reload.v1`, `resource-hash.v1` с примерами проверены тестом; `additionalProperties: false`.

## Проверка

- Красные прогоны перед кодом каждого PR: `Invoke-LtvSlot { gradlew test --tests '*SourceProbe*' --tests '*CommandLineProbe*' }` (PR 1),
  `... '*CommandLineDoctor*'` (PR 2), `... '*SourceRegistry*' --tests '*SourceProbeApi*' --tests '*LocalApiSnapshot*'` (PR 3).
- Полный прогон по разделу "Без CI" общего брифа: `Invoke-LtvExclusive { gradlew --no-daemon --no-build-cache cleanTest check installDist }`;
  `cd ui; npm run typecheck; npm run lint; npm run test:contracts; npm run build`; оффлайн Playwright весь набор и `--config playwright.online-sources.config.ts`;
  `python tools/verify_slice0.py`; `python -m unittest discover -s tools -p "test_*.py"`; `node --test tools/test_advisory_ai_runtime_relay.mjs`;
  `python tools/changelog_assemble.py --check`; `npx --yes markdownlint-cli2@0.23.2 "**/*.md" "#.worktrees/**" "#ui/node_modules/**"`; lychee по отслеживаемым файлам;
  `git diff --check`; поиск ключей; gitleaks по `origin/main..HEAD`.
- Сверка с архивом `ltv-0.1.0.zip` на девяти сценариях: `analysis_id`, `result.json`, stdout без различий (изменений анализа нет).

## Пересечения с другими работами

- **W3.2 (MCP).** Инструмент `probe_source` это тонкий прокси над `POST /api/sources/probe` (и `validate`): аргументы `profile_id`, `query_id` или `expression`, `window_ms`,
  `step_ms`, `operation` (`probe` по умолчанию, `validate`), `reload_profiles` (булево, по умолчанию ложь; истинное значение сначала вызывает `POST /api/sources/reload`). Инструмент
  не принимает URL и учётные данные; ответ это `source-probe.v1`/`source-check.v1` как есть. Мутирующие вызовы требуют сессионного cookie и CSRF-токена из `GET /api/bootstrap`:
  получает их MCP-прокси, не агент. Хэш снимка в список W3.2 не входит (агент берёт `resource_snapshot_sha256` из identity результата).
- **W3.7.** Оба правят `fixtures/http-layer/*`. PR 3 добавляет строки маршрутов (меняет `routes.txt` и добавляет строки в `responses.txt`); W3.7 PR 2 (identity) и PR HTML
  меняют строки ответов. Последовательность: PR 3 W3.1 вливается до W3.7 PR 2 либо после, но **не параллельно**; после rebase второго снимок регенерируется (флаг
  `LTV_UPDATE_HTTP_SNAPSHOT=1`), а не сливается вручную.
- **W3.3** меняет identity (`jenkins_build`), к W3.1 отношения не имеет; **W3.4, W3.5, W3.6** без пересечений. `cli/CommandLine.kt` общий файл: правки W3.1 в диспетчере,
  usage и `ui()`; при параллельных PR конфликт разрешается в usage-строке.

## Риски

| Риск | Смягчение |
| --- | --- |
| Расхождение зонда и боевого правила | общее построение запроса (P3), боевой декодер вызывается на тех же байтах (P1), тест на одинаковые параметры |
| Утечка учётных данных через метки или ошибки | коды вместо текстов, очистка и маскирование (P5, P6), тесты на маркерах `SECRET-*` в каждом выводе |
| Агент создаёт нагрузку на Prometheus | лимиты P4, `OriginState` профиля, один запрос, `409 BUSY` при двух параллельных, окно до часа и до 240 ячеек |
| Дрейф профиля между приёмом и стартом задания | отказ `SOURCE_PROFILE_NOT_FOUND` или видимые хэши выражений (P9) |
| Двойной поток к origin при перезагрузке | принято и названо (P9); окно равно длительности сбора одного задания |
| `LocalApiContext` позиционный в тестах | поле реестра последнее и необязательное; существующие вызовы не меняются |
| Снимок HTTP-слоя конфликтует с W3.7 | порядок слияния (см. "Пересечения") и регенерация после rebase |
| Зонд PostgreSQL тяжёлый | тот же `postgresCapturePermit`, документация, не входит в `doctor` без `--online` |

## Вопросы владельцу

**Q1. Можно ли агенту выполнять произвольное (ad hoc) read-only выражение к Prometheus владельца через зонд?** Рекомендация по умолчанию: да, с лимитами P4. Альтернатива:
только `query_id` из файла профиля (агент правит файл, перезагружает профили и зондирует сохранённые запросы), это строже, но добавляет круг на каждую итерацию настройки. Если
ответ "нет": ad hoc из контрактов убирается, остальное без изменений.

**Q2. Пересылать ли агенту сообщение об ошибке запроса от Prometheus (`errorType` и текст разбора)?** Рекомендация: нет в v1 (ruling P6), вернуться после пилота.

**Q3. Нужна ли отдельная работа "планы без хэш-привязки" (вторая половина usability B5: capacity/trend/pod-view не сочетаются с онлайн-сбором)?** Рекомендация: да, как
отдельная строка перечня после волны 3; W3.1 даёт только команду хэша (P10). Критерий W3.1 "без ручного хэша" в строгом смысле (одним вызовом анализ с источником и планом) без неё не достижим.

## Если объём вырастет (правило 10)

Оценка: около 700 строк production (SourceProbe 300, CliProbe 150, CliDoctor 130, SourceRegistry 60, маршруты 100, правки 60) и около 1500 строк тестов. Если зонд
OpenSearch/PostgreSQL или перезагрузка окажутся заметно больше, они выделяются в отдельный PR (профильная проверка `profile_check` отделима от зонда рядов без изменения контрактов).

## Совет Codex Astra (read-only) и что учтено

Заполняется после совета (см. следующий коммит).
