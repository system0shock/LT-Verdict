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

**Спецификация:** этот план (контракты и ruling'и) и ADR 0033, который пишется и принимается до кода (PR 0).

## Глобальные ограничения

- Анализ не меняется: `decodePromqlMatrix`, `decodeInfluxqlResponse`, `AnalysisService`, identity, `analysis_id`, `analysis-result.json` остаются байт-в-байт (сверка с `ltv-0.1.0.zip`).
- Источники только по явному действию (ADR 0007, PRC критерий 11): `ltv doctor` и `ltv source validate` офлайн по умолчанию, сеть только с `--online` или явным вызовом зонда.
- Агент не видит учётных данных, адресов профилей, тел ответов и текстов исключений источника; наружу идут закрытые коды и очищенные метки.
- Новых зависимостей и новых видов источников нет (D1); MINIMAL-CHANGE: новые файлы только `SourceProbe.kt`, `SourceRegistry.kt`, `CliProbe.kt`, `CliDoctor.kt`.

## Review Focus

- Профиль в файле правят во время работы `ltv ui`: файл изменён наполовину, или поменялся между чтением и разбором (ожидание: старый набор остаётся, код ошибки без текста файла).
- Запрос возвращает тысячи серий с длинными метками, управляющими символами и похожими на секреты значениями (ожидание: счёт точный, подробности усечены и очищены, секреты замаскированы до усечения).
- Источник отвечает медленно, 429, 5xx или без конца (ожидание: один запрос, общий срок 15 с с ожиданием лимитера, слот зонда освобождается).
- Задание стоит в очереди, пока профиль перезагружается (ожидание: честный отказ `SOURCE_PROFILE_NOT_FOUND` или видимый дрейф выражений, а не тихая подмена).
- Агент просит ad hoc выражение без `$__interval`, с `INTO`, длиннее 4096 байт или окно больше часа (ожидание: отказ до сети).

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
  - sources/SourceRegistry.kt (новый, малый): текущий неизменяемый набор (профили HTTP, профили PostgreSQL, `SourceHttp`, `PromqlSource`, ревизия) и атомарная
    замена из файла `--connections`, заданного при старте; каждая операция берёт набор один раз;
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
  - проверка целостности сохранённого пакета в `ltv source hash` (используется уже существующая `readVerifiedAnalysis`), хэширование сохранённого снимка через API
    (для этого есть маршруты identity и снимка анализа);
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
декодер на тех же байтах, чтобы заполнить `decoder_check` (`accepted` или код отказа боевого декодера). Так пользователь видит одновременно "запрос вернул 7 серий"
и "анализ принял бы его: нет, `AMBIGUOUS_SERIES`; сузьте селектор или агрегируйте". Почему: B7 именно в этом: нужно видеть, что вернулось, не меняя то, что принимает
анализ. Цена ошибки: расхождение зонда с боевым правилом (зонд сказал "принял бы", анализ отказал). Закрыто тестом, который гоняет оба
декодера на одних и тех же байтах для всех случаев, и общим построением запроса (P3).

**P2. Допустимые запросы.** (a) `profile_query`: `query_id` из профиля, выражение берётся из сохранённого профиля; (b) `ad_hoc`: выражение
в запросе для видов `prometheus`, `victoria_metrics`, `influxdb`, с теми же проверками, что у профиля (PromQL содержит `$__interval`; InfluxQL
проходит `validateInfluxqlExpression`), длиной не более 4096 байт UTF-8 (профиль допускает 65 536; зонду нужно меньше), без управляющих символов
кроме `\n` и `\t`. Для `opensearch` и PostgreSQL выражений нет: зонд это проверка профиля (`profile_check`, P8). Адрес только из сохранённого
профиля (`profile_id`); URL, путь, заголовки, учётные данные и параметры HTTP в запросе зонда не принимаются, неизвестное поле тела это
`INVALID_PROBE`. Почему ad hoc нужен: смысл настройки профиля в том, чтобы испытать выражение до записи в файл. Цена ошибки:
агент получает возможность выполнять любые read-only запросы к Prometheus владельца; смягчение это лимиты P4, `127.0.0.1` и CSRF/Origin, но они не ограничивают
агента, который получил токен через `GET /api/bootstrap` (так делает MCP-прокси), и не ограничивают **стоимость** выражения на стороне Prometheus (длина выражения, размер
ответа и число подробных серий её не бьют; настоящая защита это окно до часа, один запрос и собственные лимиты Prometheus). Поэтому ad hoc это **явная выдача возможности**, а
не побочное свойство; решение владельца: вопрос Q1, и реализация ad hoc идёт отдельным коммитом в PR 1 после ответа (режим `profile_query` от ответа не зависит).

**P3. Запрос зонда равен боевому по построению.** Построение параметров (`query`, `start`, `end`, `step` для PromQL; `db`, `q`, `epoch` для InfluxQL)
выносится из `PromqlSource.acquire` (`sources/PromqlSource.kt:96-115`) в одну `internal` функцию, которую вызывают и анализ, и зонд;
`$__interval` и плейсхолдеры InfluxQL подставляет та же `resolvedExpression`. Это единственная правка боевого кода: перенос без изменения
поведения, `PromqlSourceTest` и `SourceAnalysisTest` не редактируются. Окно зонда: `window_ms` по умолчанию 300 000 (максимум 3 600 000),
`step_ms` по умолчанию 15 000 (целые секунды 1000..60000, как требует сетка снимка `requireSnapshotGridStep`), число ячеек `window/step` целое от 1 до
240, `end_epoch_ms` по умолчанию "сейчас" (часы внедряются, в тестах фиксированы). Результаты зонда никуда не сохраняются.

**P4. Лимиты зонда (все обязательные и проверяемые).** Один HTTP-запрос и одна попытка (без повторов и ожидания `Retry-After`); ответ не более
4 МиБ (`SOURCE_RESPONSE_TOO_LARGE`); общий срок зонда 15 с **включая ожидание лимитера** (токены и параллельность `OriginState` ждут без срока, поэтому зонд передаёт в `SourceHttp` функцию
`checkCancelled`, которая бросает `SOURCE_TIMEOUT` после срока; иначе низкий `requests_per_second` занял бы оба слота зонда навсегда), таймаут одного запроса `min(таймаут origin, 15 с)`
(более строгий таймаут origin сохраняется); число подробно описанных серий не более 20, ключей меток не более 16, образцов значений на ключ
не более 5, каждая строка не более 128 байт после очистки; тело запроса API не более 16 КиБ; одновременно не более двух зондов
(`409 BUSY`). Зонд идёт через тот же `OriginState` (лимит частоты и параллельности профиля), то есть не обходит governor. Число серий считается по
всему ответу (он ограничен 4 МиБ), в отчёт идёт точное `series_count`. Цена: ответ крупнее 4 МиБ даёт отказ, а не счёт; пользователь сужает
селектор, что и нужно для боевого запроса.

**P5. Что видит агент и что нет.** Агент видит: `id`, вид, транспорт, плечо профиля; коды и числа; метки и числа ячеек серий зонда после очистки.
Не видит: `base_url` (даже origin), имена и значения переменных окружения, тип и параметры TLS, заголовки, тела ответов, тексты исключений.
Порядок обработки каждой строки метки (ключа и значения): (1) **маскирование до усечения**: строка заменяется на `***`, если содержит как подстроку (не только равна) любое значение
учётных данных профиля, прочитанное из окружения для этого запроса, или начинается со слов Bearer, Basic, Token с пробелом, с `-----BEGIN`, `eyJ`; значение заменяется на `***`, если
ключ содержит `pass`, `secret`, `token`, `key`, `auth`, `cred` (без учёта регистра); (2) очистка `cleanErrorText(raw, 128)` (`core/ErrorGroups.kt:204`: управляющие, форматные,
bidi, неназначенные символы в пробел; предел это **кодовые точки**, поэтому следом строка режется до 256 байт UTF-8 по границе символа); число замен идёт в `redacted_count`, ключи
и образцы значений в `label_keys` проходят тот же порядок. Гарантия узкая и названа: зонд не выдаёт учётные данные профиля и известные формы секретов; печатный текст метки
остаётся недоверенными данными собственного Prometheus владельца (возможная инъекция в агента), поэтому навык MCP (W3.2) обязан помечать метки как данные. Отдельная находка вне
скоупа (только в отчёт): существующий `GET /api/grafana` уже отдаёт `base_url` профилей `grafana_proxy` (`web/GrafanaRoutes.kt:37`); его контракт здесь не меняется. Тот же вывод читает человек в `ltv doctor`: `ltv` может запустить сам агент (Claude Code, Qwen Code),
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

**P8. Зонды OpenSearch и PostgreSQL как "проверка профиля" (отдельный PR 5).** Это другие виды запроса, а не частный случай: тело `POST /api/sources/probe` для видов `opensearch` и `postgresql`
содержит только `profile_id` (и необязательное окно для `opensearch`); `query_id`/`expression` для них `INVALID_PROBE`, а для видов рядов ровно одно из двух обязательно. `opensearch`: запрос
строит `buildOpenSearchQuery` по отображению профиля (`group_limit` не больше 10), метод POST через новый `SourceHttp.searchBounded` (те же ограничения попыток, ответа и срока, что у `getBounded`);
ответ разбирает `decodeOpenSearchResponse`; в отчёт идут число групп, шардов и коды покрытия, **никаких сообщений и образцов** (тексты журналов недоверенные и могут нести секреты).
`postgresql`: `capturePostgresPhase(profile, pre = null, post = false)` под тем же `postgresCapturePermit`, что и `/api/sources/postgresql/pre`; лимиты P4 (один HTTP-запрос, 4 МиБ, 15 с) к нему **не применимы**:
это набор JDBC-запросов со своими таймаутами (до 30 с), выполняемый в режиме read-only; в отчёт идут `connected`, `read_only`, `database_matches`, наличие расширений (`pg_stat_statements`,
`pg_profile`), число таблиц и коды статусов, содержимое фазы не возвращается и не сохраняется. Коды: `PG_CREDENTIALS_MISSING`, `PG_CREDENTIALS_INVALID`, `PG_CONNECTION_FAILED`,
`PG_READ_ONLY_REQUIRED`, `PG_DATABASE_MISMATCH`, `PG_CAPTURE_FAILED`. Цена: PostgreSQL-зонд читает до лимитов профиля, он не лёгкий; в документации сказано. Эта часть отделима: без неё PR 1-4 выполняют критерий
(Prometheus, VictoriaMetrics, InfluxQL).

**P9. Перезагрузка профилей: явная, из того же файла, атомарная.** `POST /api/sources/reload` без тела; сервер перечитывает ровно тот путь, который
получил при старте (`--connections`), через `readSourceFile` (обычный файл, без ссылок, предел 1 МиБ; как в CLI), строит **новую** тройку
(профили, `SourceHttp`, `PromqlSource`) и заменяет ссылку в `AtomicReference`. Путь из запроса не принимается никогда. **Доверие к файловой системе то же, что при старте**: проверяется только последний компонент пути (обычный файл, не ссылка, `readSourceFile`), а не
родительские каталоги и владельца, поэтому профилями управляет тот, кто может заменить файл или его каталог; путь нормализуется в абсолютный при старте; байты читаются **один раз** в ограниченный
буфер, и от этого же буфера считается ревизия (`revision`, SHA-256) и идёт разбор (атомарная публикация файла не делает его чтение атомарным). Разбор не удался: старый набор
остаётся, ответ `422 SOURCE_CONFIG_INVALID` с кодом (без текста файла). Сервер запущен без `--connections`: `404 SOURCE_RELOAD_NOT_CONFIGURED`. Параллельная
перезагрузка: `409 BUSY` (`tryLock`). Задание берёт набор в момент **старта** выполнения (`registry.current()` в замыкании `AnalysisJobs`) и держит его
до конца; приём задания (`bindJob`) проверяет профиль по набору на момент приёма. Если профиль исчез между приёмом и стартом, задание
завершается `SOURCE_PROFILE_NOT_FOUND` (честный отказ). Если изменился, анализ идёт по новому определению и это видно в evidence: хэши выражений и
`query_set_sha256` уже входят в `source_summary` и identity, дрейф не тихий. **Один набор на операцию:** каждая операция (приём задания, старт задания, зонд, проверка, маршрут Grafana, захват PostgreSQL) вызывает `registry.current()` один раз и дальше работает только с этим набором: иначе
раздельные запросы профиля и `SourceHttp` могли бы попасть в разные поколения, а `SourceHttp.execute` отвергает профиль, не равный своей копии (`SOURCE_PROFILE_NOT_CONFIGURED`). Профили PostgreSQL входят в тот же набор.
Гонка, которую нужно назвать: состояние лимитов (`OriginState`) живёт в `SourceHttp` каждого поколения; поколение живёт, пока им пользуется активное задание или зонд, поэтому один origin получает **до числа живых поколений**
полных потоков лимита, а не только два. Ограничение: успешная перезагрузка не чаще одной в 5 с по внедряемым часам (`Clock` у `SourceRegistry`, в тестах он двигается; `429 RELOAD_TOO_FREQUENT`), что при длительности сбора в минуты держит число поколений малым; перенос `OriginState` между
экземплярами усложнил бы `SourceHttp` без нужды. Дрейф определения: хэши выражений и `query_set_sha256` входят в `source_summary` и identity, а **адрес профиля не входит** (так и сегодня при перезапуске с новым файлом), поэтому
смена только `base_url` в доказательствах не видна; это принято и записано в документации. Цена ошибки: кратковременная повышенная нагрузка на источник при перезагрузке посреди сбора; запрет перезагрузки при активных
заданиях был бы хуже (долгие загрузки блокировали бы настройку).

**P10. Хэш снимка: две формы, обе без новой логики.** `ltv source hash <resource-snapshot.json>` и API `POST /api/resources/semantic-hash` (тело: сам снимок) вызывают `validateResourceSnapshot` и выдают
`semantic_sha256`, `config_sha256`, `load_input_sha256`, число рядов и ячеек. `ltv source hash --run <run-id> --analysis <analysis-id> [--data-dir]` читает `resource_snapshot_sha256` из identity сохранённого анализа через
существующую `RunBundleStore.readVerifiedAnalysis` (проверка манифеста и хэшей уже в ней; пересчёта и сверки по значениям рядов нет). Ограничение, названное прямо: форма `--run/--analysis` открывает каталог данных с
исключающей блокировкой и при запущенном `ltv ui` на том же каталоге даёт `DATA_DIR_BUSY` (выход 6); при запущенном UI/MCP хэш берётся из существующих маршрутов identity и снимка анализа. Исходы формы: анализ не найден
(выход 4, `ANALYSIS_NOT_FOUND`), у анализа нет снимка ресурсов (`RESOURCE_SNAPSHOT_NOT_PROVIDED`, 4), пакет повреждён (`ANALYSIS_CORRUPT`, 4). Что не решается здесь: планы capacity/trend/pod-view по-прежнему
привязаны к хэшу снимка и не сочетаются с онлайн-сбором в одном вызове (`SOURCE_INPUT_CONFLICT`, `cli/CommandLine.kt:245`): это вторая половина usability B5 и отдельная работа (вопрос Q3). Критерий W3.1 "без ручного
хэша" выполняется для хэша как действия (команда вместо чтения JSON), но не для самого двухпроходного процесса.

**P11. Размещение команд.** `ltv source` уже существует (`pre|post`, `captureSource`); добавляются `validate`, `probe`, `hash`. `ltv doctor` отдельный
верх (так он назван в PRC 17.2). Новая логика в двух новых файлах `cli/`, как `PrepareOpenSearchCommand.kt`; в `CommandLine.kt` только диспетчер и usage.

**P12. Коды выхода `ltv doctor` и агрегация.** Статус проверки: `OK`, `WARN`, `FAIL`, `SKIPPED`. Запрос профиля с `decoder_check.accepted = false` это `FAIL` (боевой анализ отметит его `FAILED`), кроме
кода `EMPTY_RESULT`: пустой ответ в окне "сейчас" означает `WARN` (теста может не идти). Запрос, не выполненный из-за исчерпания `maxRequestsPerRun` профиля, это `SKIPPED` с кодом `SOURCE_REQUEST_CAP_EXCEEDED` и
`WARN` на профиль. Поле `resource_side` (вместо недоопределённого "expected verdict") на профиль и в целом (худшее по порядку `READY`, `DEGRADED`, `UNAVAILABLE`): `NOT_PROBED` при отсутствии `--online`
и отсутствии `FAIL` офлайн; `UNAVAILABLE` при любом офлайн-`FAIL` или если все запросы `FAIL`; `DEGRADED` если часть запросов `FAIL`/`WARN`; `READY` если все запросы приняты. Нагрузочный вердикт доступен всегда
(источники необязательны, PRC критерий 4). Коды выхода: 0: отчёт построен, провалов нет (допустимы `WARN` и `SKIPPED`); 7: отчёт построен, есть хотя бы один `FAIL`
(константа `EXIT_DOCTOR_FAILED` вводится в PR 1, потому что `ltv source probe` тоже выходит 7; значения 2/3 заняты вердиктом анализа, 4 это ошибка входа, 6 занято каталогом данных); 4: файл
подключений не читается или не проходит разбор; 64: usage. Цена ошибки: CI, использующий `doctor` как шлюз, различает "нет окружения" (7) и "неверная команда" (64).

**P13. ADR 0033 нужен** (номер 0033 свободен на `origin/main`, в удалённых ветках и в открытых PR на 2026-10-09; в PR 0 берётся следующий свободный, в репозитории уже был дубль номера ADR). Новая граница доверия (маршруты, достижимые агентом; чтение конфигурации по команде; новый класс исходящих запросов), новые
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
Коды выхода: `validate` и `hash`: 0 успех, 4 вход не разобран, 64 usage; `probe`: 0 запрос выполнен (даже если `decoder_check.accepted` ложно), 7 запрос не
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
  "decoder_check": { "accepted": false, "code": "AMBIGUOUS_SERIES" },
  "request_count": 1,
  "elapsed_ms": 412
}
```

`status`: `OK` (ответ разобран; `series_count` может быть 0) или `FAILED` (`code` из списка, `series_count`, `series`, `label_keys` равны `null`/пусты). `mode`:
`profile_query` | `ad_hoc` | `profile_check` (OpenSearch, PostgreSQL; вместо `series` поля `check` с числами и кодами, без текстов). `decoder_check.code`:
код боевого декодера (`AMBIGUOUS_SERIES`, `LABEL_MISMATCH`, `UNSUPPORTED_RESULT_TYPE`, `SOURCE_WARNINGS`, `OFF_GRID_TIMESTAMP`, ...), `EMPTY_RESULT` для нуля серий.
`warnings`: коды `SOURCE_WARNINGS`, `SOURCE_PARTIAL_RESPONSE` (ответ принят зондом, но боевой анализ его бы отверг). Поля `base_url`, `auth`, `tls`, тела и
тексты ошибок в документе отсутствуют; схема `additionalProperties: false`.

`decoder_check` отвечает на узкий вопрос "принял бы боевой декодер этот ответ": число серий, метки профиля, сетка времени, значения. Он **не** проверяет конфликт метки плеча (`arm`),
отрицательные значения событий (`non_negative_events`) и лимиты снимка после разбора (`SOURCE_SNAPSHOT_LIMIT_EXCEEDED`): это знание из метаданных запроса профиля или шаги после декодера. Для `ad_hoc` метаданных нет,
поэтому поле `decoder_check` там означает то же самое, и документация говорит, что "принято" не равно "анализ примет".

Закрытый список `code` при `status: FAILED`: `SOURCE_PROFILE_NOT_CONFIGURED`, `SOURCE_AUTH_UNAVAILABLE`, `SOURCE_AUTH_INVALID`, `SOURCE_HTTP_AUTH`,
`SOURCE_HTTP_429`, `SOURCE_HTTP_5XX`, `SOURCE_HTTP_STATUS`, `SOURCE_HTTP_ERROR`, `SOURCE_TIMEOUT`, `SOURCE_RESPONSE_TOO_LARGE`, `SOURCE_TLS_CONFIG_INVALID`,
`SOURCE_TLS_HANDSHAKE_FAILED`, `SOURCE_TLS_CLIENT_CERT_EXPIRED`, `SOURCE_CANCELLED`, `MALFORMED_RESPONSE`, `PROMQL_QUERY_FAILED`, `INFLUXQL_QUERY_FAILED`,
`UNSUPPORTED_RESULT_TYPE`, `UNSUPPORTED_HISTOGRAM`, `RESOURCE_LIMIT_EXCEEDED`, `OPENSEARCH_INVALID_MAPPING`, `PG_CONNECTION_FAILED`, `PG_READ_ONLY_REQUIRED`,
`PG_DATABASE_MISMATCH`, `PG_CAPTURE_FAILED`, `PG_CREDENTIALS_MISSING`, `PG_CREDENTIALS_INVALID` (коды PostgreSQL и `OPENSEARCH_INVALID_MAPPING` приходят только из PR 5). Ошибки запроса (до сети, HTTP 400): `INVALID_PROBE`, `SOURCE_QUERY_INVALID`, `SOURCE_REQUEST_INVALID`
(окно), `SOURCE_PROFILE_NOT_FOUND` (404), `QUERY_NOT_FOUND` (404), `BUSY` (409), `RELOAD_TOO_FREQUENT` (429, только reload).

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

`{ schema_version, online: bool, connections: { status, profile_count }, profiles: [source-check профиль + "resource_side" + "online": { "queries": [ { "query_id", "status", "series_count",
"observed_cells", "expected_cells", "decoder_check": {...} } ] } ], summary: { ok, warn, fail, skipped }, resource_side }`.
`online` присутствует только с `--online`; для OpenSearch и PostgreSQL (PR 5) `profile_check`. Профили по `id`, запросы по порядку в профиле. Агрегация статусов и `resource_side`: ruling P12. Текст (`--format text`):

```text
LT Verdict doctor (online: no, сеть не проверялась: нужен --online)
connections        OK  3 profiles
prom-main          prometheus/direct  12 queries
  config           OK
  url              OK
  credentials      FAIL SOURCE_AUTH_UNAVAILABLE (bearer)
  tls              SKIPPED
summary: ok 2, warn 0, fail 1, skipped 1
resource side: UNAVAILABLE (prom-main: нет учётных данных)
```

Паков метрик и "глубины RCA" из примера PRC здесь нет (NOT REQUIRED).

### HTTP API

| Маршрут | Тело | Успех | Ошибки |
| --- | --- | --- | --- |
| `POST /api/sources/reload` | нет | `200` `source-reload.v1`: `status`, `profile_count`, `profiles` (id), `added`, `removed`, `changed` (id), `revision` (SHA-256 прочитанных байт файла) | `404 SOURCE_RELOAD_NOT_CONFIGURED`, `422 SOURCE_CONFIG_INVALID`, `409 BUSY`, `429 RELOAD_TOO_FREQUENT` |
| `POST /api/sources/validate` | `{"profile_id"?: "<id>"}` | `200` `source-check.v1` | `404 SOURCE_PROFILE_NOT_FOUND`, `400 INVALID_PROBE` |
| `POST /api/sources/probe` | виды рядов: `{"profile_id", "query_id"` или `"expression", "window_ms"?, "step_ms"?, "end_epoch_ms"?}`; `opensearch`, `postgresql` (PR 5): `{"profile_id"}` | `200` `source-probe.v1` (в том числе `status: FAILED`) | `400 INVALID_PROBE`/`SOURCE_QUERY_INVALID`/`SOURCE_REQUEST_INVALID`, `404`, `409 BUSY` |
| `POST /api/resources/semantic-hash` | сам снимок `resource-snapshot.v1` (до 32 МиБ, `Content-Length` обязателен) | `200` `resource-hash.v1`: `semantic_sha256`, `config_sha256`, `load_input_sha256`, `series_count`, `point_count` | `422 INVALID_RESOURCES`, `413`, `411` |

Все четыре маршрута мутирующие по методу (`POST`) и проходят защиту `installLocalApi` (Host, Origin, cookie, `X-LTV-CSRF`). Для видов рядов `probe` принимает ровно одно из `query_id`/`expression`, для `opensearch`
и `postgresql` ни одного; нарушение это `400 INVALID_PROBE`. Хэш сохранённого анализа через API не вычисляется: `resource_snapshot_sha256` лежит в identity результата. `GET /api/sources` не меняется (байты ответа прежние).

## Задачи по PR

Порядок: PR 0 (ADR) до кода; PR 1 затем PR 2 и PR 3 (параллельны, пересекаются только в `cli/CommandLine.kt` и `docs/user/online-sources.md`); PR 4 и PR 5 после PR 3 (оба необязательны для критерия).
Файловые пересечения: PR 1 и PR 2 правят `cli/CommandLine.kt` (диспетчер, usage); PR 3 правит `cli/CommandLine.kt` в `ui()` (другая область) и `fixtures/http-layer/*`; PR 5 правит `SourceProbe.kt`, `SourceHttp.kt`,
`SourceRoutes.kt`, `CliDoctor.kt`. Принадлежность файлов: схемы `source-probe`, `source-check`, `resource-hash` и константа `EXIT_DOCTOR_FAILED` в PR 1; `doctor-report` в PR 2; `source-reload` в PR 3;
пользовательская документация каждого PR в том же PR (PR 1: раздел зонда, `validate`, `hash`; PR 2: `ltv doctor`; PR 3: reload и снятие фразы "требует перезапуска").

### PR 0 (docs): ADR 0033 `Proposed`

- [ ] Написать `docs/adr/0033-source-probes-and-profile-reload.md` по P1-P13 и схемам; статус `Proposed`, принятие по слову владельца.
- [ ] `markdownlint-cli2`, `lychee`, `verify_slice0`, `changelog_assemble --check`; `Documentation impact: ADR`.

### PR 1 (feat/sources, около 2 дней): зонд, `ltv source validate|probe|hash`

**Файлы:** `sources/SourceProbe.kt` (новый), `sources/SourceHttp.kt`, `sources/PromqlSource.kt` (вынос параметров), `sources/SourceConfig.kt`
(`internal`), `cli/CliProbe.kt` (новый), `cli/CommandLine.kt` (диспетчер, usage, `EXIT_DOCTOR_FAILED`), `docs/contracts/sources/probe/v1/` (схемы `source-probe`, `source-check`, `resource-hash`), `docs/user/online-sources.md`
(раздел зонда), тесты `SourceProbeTest`, `CommandLineProbeTest`, `changelog.d/w3-1-probe.added.md`. Режим `profile_query` и `validate`/`hash` идут первыми коммитами; ad hoc (P2) отдельным коммитом после ответа на Q1.

- [ ] Красные тесты `SourceProbeTest` (фиктивный сервер как в `SourceHttpTest`/`OnlineSourceFixture`):
  - три серии PromQL: `series_count` 3, метки, `decoder_check` `AMBIGUOUS_SERIES`; нуль серий: `series_count` 0, `EMPTY_RESULT`;
    одна серия с чужой меткой профиля: `LABEL_MISMATCH`; ответ с `warnings`: `status: OK`, `warnings: ["SOURCE_WARNINGS"]`;
    `resultType: vector`: `FAILED` `UNSUPPORTED_RESULT_TYPE`; гистограмма: `UNSUPPORTED_HISTOGRAM`;
  - **боевой декодер не ослаблен**: `decodePromqlMatrix` на тех же байтах трёх серий по-прежнему бросает `AMBIGUOUS_SERIES` (ссылка на `PromqlSourceTest`);
  - ограничения вывода: 25 серий дают 20 подробных и `series_truncated`; 20 ключей дают 16; значение 300 байт режется (128 кодовых точек и 256 байт); управляющие и bidi-символы
    заменяются пробелом; метка с ключом `api_token`, значение, равное переменной окружения профиля, и значение, **содержащее** её как подстроку, и ключ, содержащий её, маскируются **до усечения**,
    `redacted_count` верен;
  - утечки: ответ 401 с телом `{"error":"SECRET-BODY"}` и заголовком `Set-Cookie`: в JSON отчёта нет `SECRET-BODY`, значения токена, имён переменных
    окружения, `base_url`, фрагмента URL; исключение с текстом профиля не выходит; `http_status` 401, `code` `SOURCE_HTTP_AUTH`;
  - лимиты: ответ 5 МиБ даёт `SOURCE_RESPONSE_TOO_LARGE`; сервер, который не отвечает, даёт `SOURCE_TIMEOUT` не позже 15 с (часы тестового governor); профиль с `requests_per_second` 0,01 и занятым токеном
    даёт `SOURCE_TIMEOUT` по общему сроку (ожидание лимитера входит в срок), слот зонда освобождается; более строгий таймаут origin сохраняется; 5xx не повторяется (`request_count` 1);
  - запрос равен боевому: фиктивный сервер записывает путь и параметры; для `query_id` параметры (`query`, `start`, `end`, `step`) побайтно равны параметрам
    `PromqlSource.acquire` на том же окне; другого пути и второго запроса нет; редирект не выполняется;
  - ad hoc: без `$__interval` (PromQL) и InfluxQL с `INTO`/`;`/комментарием: `SOURCE_QUERY_INVALID` **без сетевого запроса**; выражение 4097 байт; окно 3 600 001 мс;
    шаг 1500 мс; 241 ячейка: `SOURCE_REQUEST_INVALID`;
  - зонд не меняет анализ: после зонда анализ того же входа с источником даёт тот же `analysis_id` и те же байты (`SourceProbe` не принимает хранилище; тест сравнивает
    дерево каталога данных до и после зонда, оно пусто);
  - `ltv source hash`: файл снимка даёт `semantic_sha256`, равный `resource_snapshot_sha256` из `identity.json` анализа с тем же снимком; форма `--run/--analysis` даёт то же значение; анализ без снимка даёт
    `RESOURCE_SNAPSHOT_NOT_PROVIDED`, неизвестный анализ `ANALYSIS_NOT_FOUND`, повреждённый манифест `ANALYSIS_CORRUPT` (выход 4); при открытом каталоге данных другим процессом `DATA_DIR_BUSY` (6).
- [ ] Красные тесты `CommandLineProbeTest`: вывод text/json, коды выхода (0/7/4/64), `validate` не делает сетевых запросов (счётчик сервера 0), ни один вывод
  не содержит значения переменных окружения и URL.
- [ ] Реализация (минимальная): `SourceProbe.kt` (`probeSource(profile, spec, http, environment, clock)`; `ProbeSpec`; разбор `data.result` строгим сканером
  `StrictJsonScanner`, как в `PromqlSource.kt:241-247`; общий срок через `checkCancelled`), `SourceHttp.getBounded(profile, params, maxBytes, maxAttempts, timeoutCap, checkCancelled)` (обёртка над `execute`,
  `responseLimit` уже параметр; `maxAttempts` и `timeoutCap` становятся параметрами `execute` и только уменьшают настройки origin), `SourceHttpFailure.httpStatus`, вынос `queryRangeParameters(...)`, `internal` для `validateInfluxqlExpression`/проверки
  `$__interval`, `CliProbe.kt`.
- [ ] Схемы и примеры в `docs/contracts/sources/probe/v1`; тест Kotlin читает примеры (valid принимаются, каждый invalid отвергается по названной в имени причине),
  как `incident.v1`.
- [ ] Полный прогон по разделу "Без CI" общего брифа; сверка с `ltv-0.1.0.zip`: `ltv analyze` без зонда даёт те же `analysis_id`, `result.json`, stdout (изменений в
  анализе нет).
- [ ] Коммиты атомарные: `feat(sources): source probes`, `feat(cli): ltv source validate, probe and hash`.

### PR 2 (feat/cli, около 1 дня): `ltv doctor`

**Файлы:** `cli/CliDoctor.kt` (новый), `cli/CommandLine.kt` (диспетчер, usage, `EXIT_DOCTOR_FAILED`), `docs/contracts/sources/probe/v1/doctor-report.schema.json`,
тест `CommandLineDoctorTest`, `docs/user/online-sources.md`, `README.md` (строка команд), `changelog.d/w3-1-doctor.added.md`.

- [ ] Красные тесты: без `--online` сервер видит 0 запросов, `resource_side: NOT_PROBED`; нет переменной окружения: `FAIL SOURCE_AUTH_UNAVAILABLE`, выход 7, `resource_side: UNAVAILABLE`;
  истёкший клиентский сертификат (`TestPki`): `SOURCE_TLS_CLIENT_CERT_EXPIRED`; `--online` по запросам профиля: на каждый запрос строка с `series_count` и
  `decoder_check`; запрос с `AMBIGUOUS_SERIES` это `FAIL` (выход 7, `resource_side: DEGRADED`), с `EMPTY_RESULT` это `WARN` (выход 0); исчерпанный `maxRequestsPerRun` даёт `SKIPPED` и `WARN`; вывод и JSON не содержат URL и значений окружения; JSON проходит схему;
  `--profile` выбирает профиль, неизвестный даёт 4; файл подключений битый даёт 4.
- [ ] Реализация: `CliDoctor.kt` собирает `source-check` (общая функция с `validate`) и при `--online` вызывает `probeSource` по запросам профиля с бюджетом
  `maxRequestsPerRun` профиля, последовательно, с governor.
- [ ] `docs/user/online-sources.md`: раздел "Проверка профиля: `ltv source validate`, `ltv source probe`, `ltv doctor`".

### PR 3 (feat/api, около 2 дней): реестр, перезагрузка, зонд и хэш в API

**Файлы:** `sources/SourceRegistry.kt` (новый), `web/SourceRoutes.kt`, `web/LocalApi.kt`, `web/JobRoutes.kt`, `web/GrafanaRoutes.kt`, `cli/CommandLine.kt`
(`ui()`: путь и реестр), `docs/contracts/sources/probe/v1/source-reload.schema.json`, `docs/user/online-sources.md` (reload; убрать "требует перезапуска"), тесты `SourceRegistryTest`,
`SourceProbeApiTest`, `fixtures/http-layer/routes.txt` и `responses.txt`, `changelog.d/w3-1-api.added.md`.

- [ ] Красные тесты `SourceRegistryTest`: замена набора атомарна (читатель видит старую или новую тройку, не смесь; два потока); неверный файл оставляет старый набор;
  параллельная перезагрузка даёт `BUSY`, вторая в течение 5 с `RELOAD_TOO_FREQUENT`; симлинк и необычный файл отклоняются; ревизия считается от тех же байтов, что разбираются (подмена файла между чтением и разбором не создаёт расхождения); новая тройка использует новый `SourceHttp` (старый профиль больше не принимается новым
  экземпляром, но держится старым заданием).
- [ ] Красные тесты `SourceProbeApiTest` (образец `LocalApiTest`): правка файла, `POST /api/sources/reload`, `GET /api/sources` показывает новый профиль, задание с новым
  профилем принимается без перезапуска; маршрут Grafana и захват PostgreSQL берут профиль и `SourceHttp` из одного набора (перезагрузка посреди запроса не даёт `SOURCE_PROFILE_NOT_CONFIGURED`); задание, поставленное до перезагрузки, завершается по старому набору (`ScriptedSource` с воротами); профиль исчез между
  приёмом и стартом даёт `SOURCE_PROFILE_NOT_FOUND`; `404 SOURCE_RELOAD_NOT_CONFIGURED` без `--connections`; `POST` без CSRF-токена `403` для всех четырёх маршрутов
  (дополнить перечень в `LocalSecurityTest`, если он перечисляет маршруты поимённо); путь в теле reload игнорируется или отвергается; тела ответов не содержат
  `base_url`, имён переменных окружения; `GET /api/sources` побайтно прежний; `probe` под лимитом двух параллельных: третий `409 BUSY`; `semantic-hash` принимает снимок и отвечает хэшем, равным `resource_snapshot_sha256` анализа; битый снимок `422 INVALID_RESOURCES`;
  каталог данных до и после зонда не изменился.
- [ ] Реализация: `SourceRegistry(path: Path?, load: (ByteArray) -> SourceConnections, clock: Clock)` с `current()`, `reload()`, `tryLock`, ограничением частоты; набор `SourceState(profiles, postgres, http, promql, revision)`;
  `LocalApiContext` получает необязательное поле `sourceRegistry` (конструкторы позиционные в тестах, поэтому поле последнее и необязательное), маршруты вызывают `context.sourceState()` **один раз** на операцию; `ui()` строит реестр из
  `--connections`; замыкание `AnalysisJobs` читает `registry.current().promql`.
- [ ] `LTV_UPDATE_HTTP_SNAPSHOT=1` для `LocalApiSnapshotTest`; проверка `git diff fixtures/http-layer`: только добавленные строки (новые маршруты и их ответы
  `403/400` свипа), ни одна прежняя строка не изменена. `BaselineReleaseRulesSnapshotTest` не трогается.
- [ ] Сверка с `ltv-0.1.0.zip` не нужна (байты анализа не меняются), но прогон включает `e2e` онлайн-источников
  (`npx playwright test --config playwright.online-sources.config.ts`).

### PR 4 (feat/ui, около 0,5 дня, отделим): кнопка "Перечитать профили"

- [ ] Красный Playwright (адаптерный): кнопка в блоке выбора источника вызывает `POST /api/sources/reload`, обновляет список, показывает код ошибки без текста файла.
- [ ] `ui/src/api.ts` (метод), компонент и `labels.ts` (русские строки), полный `npm`-набор и оффлайн Playwright.

### PR 5 (feat/sources, около 1,5 дня, отделим): проверка профилей OpenSearch и PostgreSQL

**Файлы:** `sources/SourceProbe.kt`, `sources/SourceHttp.kt` (`searchBounded`), `web/SourceRoutes.kt`, `cli/CliProbe.kt`, `cli/CliDoctor.kt`, схемы `source-probe` (вариант `profile_check`), тесты, `changelog.d/w3-1-profile-check.added.md`.

- [ ] Красные тесты: OpenSearch `profile_check` без сообщений и образцов (в ответе фиктивного сервера маркер `SECRET-LOG`, его нет в отчёте), метод POST ограничен теми же лимитами; PostgreSQL `profile_check` с
  `connected/read_only/database_matches`, расширениями, статусами таблиц и без содержимого фазы (фикстура `PostgresSourceTest`); общий `postgresCapturePermit` (параллельный захват даёт `409 BUSY`);
  `query_id`/`expression` для этих видов дают `INVALID_PROBE`; коды `PG_CREDENTIALS_*`; `doctor --online` включает эти проверки.
- [ ] Реализация и документация.

### Документация и ADR

- `docs/user/online-sources.md`: убрать "Профили загружаются при запуске; изменение файла требует перезапуска backend", описать reload и зонды, границы (P5-P7).
- ADR 0033 (PR 0). `CHANGELOG.md` не трогать; фрагменты `changelog.d/w3-1-*.added.md` в PR 1-5 (в фрагментах обычный текст без ссылок).
- ADR не нужен для PR 1-5 сверх 0033; в 0033 выносятся: P1, P2, P5, P6, P7, P8, P9, P10, P12, коды и схемы.

## Критерии приёмки

1. Фиктивный Prometheus, ответ из 7 серий: `ltv source probe` и `POST /api/sources/probe` возвращают `series_count: 7`, метки, `decoder_check` `AMBIGUOUS_SERIES`;
   боевой `decodePromqlMatrix` по-прежнему отвергает те же байты (`PromqlSourceTest` без правок зелёный); `decoder_check` не обещает проверок плеча, отрицательных событий и лимитов снимка (документировано).
2. Правка файла профиля и `POST /api/sources/reload` без перезапуска: новый профиль виден в `GET /api/sources` и принимается заданием; неверный файл оставляет старый набор.
3. `ltv source hash <файл>` и API `semantic-hash` дают тот же хэш, что `identity.json` анализа с тем же снимком; форма `--run/--analysis` выдаёт его без ручного чтения `identity.json`
   (при незанятом каталоге данных).
4. `ltv doctor` без `--online` не делает ни одного сетевого запроса (счётчик фиктивного сервера 0), выход 7 при `FAIL`; с `--online` отчёт по каждому запросу профиля.
5. Ни JSON зонда, ни вывод CLI, ни ответы API не содержат значений и имён переменных окружения, `base_url`, тел ответов, текстов исключений (тесты на маркерах
   `SECRET-*`); метки очищены и ограничены (20 серий, 16 ключей, 5 значений, 128 байт).
6. Один запрос, одна попытка, ответ не более 4 МиБ, общий срок не более 15 с **с ожиданием лимитера**, зонд идёт через `OriginState` профиля; два параллельных зонда, третий `409 BUSY`.
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
| Операция видит смесь поколений (профиль нового, `SourceHttp` старого) | один вызов `registry.current()` на операцию, тест на маршрутах Grafana и PostgreSQL |
| Профили управляются тем, кто может заменить файл или его каталог | то же доверие, что при старте; названо в P9; чтение и ревизия от одного буфера |
| Низкий `requests_per_second` занимает слоты зонда | общий срок 15 с включает ожидание лимитера, `checkCancelled` |
| Снимок HTTP-слоя конфликтует с W3.7 | порядок слияния (см. "Пересечения") и регенерация после rebase |
| Зонд PostgreSQL тяжёлый | тот же `postgresCapturePermit`, документация, не входит в `doctor` без `--online` |

## Вопросы владельцу

**Q1. Можно ли агенту выполнять произвольное (ad hoc) read-only выражение к Prometheus владельца через зонд?** Это явная выдача возможности: ни localhost, ни CSRF не ограничивают агента с токеном
bootstrap, а стоимость выражения на стороне Prometheus лимиты зонда не ограничивают. Рекомендация по умолчанию: да, с лимитами P4 (окно до часа, один запрос); ad hoc реализуется последним коммитом PR 1. Альтернатива:
только `query_id` из файла профиля (агент правит файл, перезагружает профили и зондирует сохранённые запросы), это строже, но добавляет круг на каждую итерацию настройки. Если
ответ "нет": ad hoc из контрактов убирается, остальное без изменений.

**Q2. Пересылать ли агенту сообщение об ошибке запроса от Prometheus (`errorType` и текст разбора)?** Рекомендация: нет в v1 (ruling P6), вернуться после пилота.

**Q3. Нужна ли отдельная работа "планы без хэш-привязки" (вторая половина usability B5: capacity/trend/pod-view не сочетаются с онлайн-сбором)?** Рекомендация: да, как
отдельная строка перечня после волны 3; W3.1 даёт только команду хэша (P10). Критерий W3.1 "без ручного хэша" в строгом смысле (одним вызовом анализ с источником и планом) без неё не достижим.

## Если объём вырастет (правило 10)

Оценка: около 700 строк production в PR 1-4 (SourceProbe 300, CliProbe 150, CliDoctor 130, SourceRegistry 80, маршруты 100, правки 60) плюс около 250 в PR 5 и около 1800 строк тестов; всего 7 рабочих дней
(PR 0 0,5; PR 1 2; PR 2 1; PR 3 2; PR 4 0,5; PR 5 1,5). Профильная проверка OpenSearch/PostgreSQL уже выделена в PR 5; если реестр и перезагрузка окажутся заметно больше, PR 3 делится на реестр с `reload` и на `probe`/`validate`/`hash` в API.

## Совет Codex Astra (read-only) и что учтено

Совет получен по плану до кода (12 замечаний), каждое проверено по коду.

Принято и внесено в план:

- (1) ad hoc это явная выдача возможности, лимиты не ограничивают стоимость выражения на стороне Prometheus: P2, вопрос Q1, ad hoc последним коммитом PR 1.
- (2) маскирование до усечения, подстрока вместо равенства, ключи и образцы значений, предел в байтах поверх кодовых точек, узкая формулировка гарантии; находка вне скоупа: `GET /api/grafana` уже отдаёт `base_url` (`web/GrafanaRoutes.kt:37`), контракт не меняется: P5.
- (3) доверие к файловой системе (проверяется только последний компонент пути, `CommandLine.kt` `readSourceFile`), один буфер для ревизии и разбора: P9.
- (4) один набор на операцию, профили PostgreSQL в наборе (маршрут Grafana действительно берёт профиль и `SourceHttp` раздельно, `GrafanaRoutes.kt:53`): P9, `SourceRegistry`.
- (5) нагрузка при перезагрузке растёт с числом живых поколений, а не вдвое: ограничение частоты 5 с, `429 RELOAD_TOO_FREQUENT`; хэши выражений не показывают смену только адреса (так и сегодня при перезапуске): P9.
- (6) `production_check` переименован в `decoder_check` с узким определением (плечо, отрицательные события, лимиты снимка не проверяются): контракты.
- (7) срок включает ожидание лимитера (`acquireConcurrency`/`acquireToken` ждут без срока, `SourceHttp.kt:149`), более строгий таймаут origin сохраняется: P4.
- (8) OpenSearch и PostgreSQL выделены в PR 5 с отдельными вариантами запроса и без универсальных лимитов P4; коды `PG_CREDENTIALS_*`: P8.
- (9) агрегация статусов и `resource_side` вместо недоопределённого `expected_verdict`: P12.
- (10) форма `--run/--analysis` требует исключающей блокировки каталога (`DATA_DIR_BUSY` при запущенном UI), исходы без снимка и с повреждённым пакетом названы: P10.
- (11) пересчёт хэша сохранённого анализа и хэширование сохранённого снимка через API убраны (читается `resource_snapshot_sha256` через `readVerifiedAnalysis`).
- (12) принадлежность файлов по PR: `EXIT_DOCTOR_FAILED` и три схемы в PR 1, схема doctor в PR 2, схема reload и документация reload в PR 3.

Не принято: нет. Замечание (1) о доступе агента с токеном bootstrap принято как вопрос владельцу, а не как отказ от ad hoc.
