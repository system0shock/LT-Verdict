# W3.6a: реалистичные входы perf-пробы и строгий gate PostgreSQL

Статус: реализовано, ждёт слияния. Ветка `test/realistic-perf-and-strict-pg`. Строка перечня:
W3.6 в `docs/superpowers/plans/2026-10-08-review-work-plan.md`. Эта часть (a)
охватывает только то, что проверяется локально. Часть (b) (service container
PostgreSQL, дефолтный shell Playwright, правка workflow) ждёт восстановления
GitHub Actions и в этот PR не входит.

## REQUESTED

- Perf-вход, похожий на реальный JMeter: CSV с 12-16 колонками (здесь 17, как
  в стандартном JTL JMeter 5.6.3, в `fixtures/slice1/jmeter/csv-5.6.3`) и
  200-500 различных label, плюс JMeter XML сопоставимого размера.
- Опциональный реалистичный профиль в `tools/perf/jtl_probe.sh`; дефолтный путь
  (его исполняет CI) не меняется.
- Режим «строгий gate» для PostgreSQL-интеграционного теста: при заданной
  переменной окружения отсутствие настройки роняет тест вместо пропуска.
- Предложение диффа для `.github/workflows/runtime-quality.yml` (двойной
  полный `check` в шардах `e2e-offline`) в виде текста в плане, без применения.

## REQUIRED TO ACHIEVE IT

1. `tools/perf/generate_jtl.py`: новые функции `generate_realistic_csv` и
   `generate_realistic_xml`, `main(argv)` принимает список аргументов для теста
   CLI, аргумент CLI `--profile` (`basic` по умолчанию,
   `realistic-csv`, `realistic-xml`), `--labels` (200-500, по умолчанию 300) и
   `--error-permille` (0-200, по умолчанию 20, то есть 2%; вне диапазона
   ошибка аргументов). Реалистичные данные: часть label с запятой (CSV-кавычки)
   и не-ASCII символами (UTF-8), сообщения об ошибках с `failureMessage`. Функция `generate` и её
   байты не меняются.
2. `tools/test_generate_jtl.py`: тесты на новые профили (детерминизм по seed,
   число колонок и label, доля ошибок, кавычки и не-ASCII, разбор XML,
   сопоставимость размера CSV/XML, неизменность байтов basic по зафиксированному
   SHA-256 первых 100 строк, в том числе через CLI без `--profile`).
   Существующие тесты не правятся.
3. `tools/perf/jtl_probe.sh`: переменная `LTV_PROBE_PROFILE`
   (`basic` по умолчанию | `realistic-csv` | `realistic-xml`). Число строк
   профилей фиксировано в скрипте (отдельной переменной нет: Astra, п. 8).
   Без переменной выполняются те же команды, что и раньше (число вхождений
   очищенного окружения Java остаётся 2, существующий тест это проверяет;
   добавляется текстовая проверка параметров дефолтной пробы).
4. `src/test/kotlin/io/ltverdict/sources/PostgresSourceTest.kt`: приватная
   проверка `assumeIntegration(condition, message, strict)` и
   `strictPostgresRequired(environment)`; единственный opt-in тест
   `dedicated PostgreSQL captures exact synthetic table and statement delta`
   использует её вместо `assumeTrue`/`integrationEnvironment`-пропуска. Новые
   юнит-тесты на механизм (строгий режим падает, обычный пропускает).
5. Фрагмент `changelog.d/w3-6a-realistic-inputs.changed.md` (путь `tools/`
   затронут, правило Changelog требует фрагмент).
6. Строка в `docs/mvp-acceptance-checklist.md` про профили пробы и
   `LTV_REQUIRE_POSTGRES=1` (документация поведения, которое видно оператору).

## NOT REQUIRED

- Смена default shell Playwright (массовая правка e2e, отложено).
- Правка workflow и service container PostgreSQL (проверка только после
  восстановления Actions; ниже лишь предложение диффа).
- Любые изменения `src/main`, `analysis_id`, golden, существующих тестов
  (кроме перечисленного в п. 4, где меняется только условие пропуска).
- Новые зависимости, абстракции для других тестов, единая «strict»-библиотека.
- Перевод других `assumeTrue` (symlink, bash, C5, usefulness) на строгий режим:
  они про среду исполнения, а не про отсутствие PostgreSQL.

## EXPECTED FILES TO CHANGE

- `tools/perf/generate_jtl.py`
- `tools/test_generate_jtl.py`
- `tools/perf/jtl_probe.sh`
- `src/test/kotlin/io/ltverdict/sources/PostgresSourceTest.kt`
- `docs/mvp-acceptance-checklist.md`
- `changelog.d/w3-6a-realistic-inputs.changed.md`
- этот план

## Публичные контракты

- Выход `generate_jtl.py` без `--profile` побайтно прежний. Новые профили
  детерминированы по `(rows, seed, labels, error_permille)`: целочисленная
  арифметика xorshift64, без `random`, без float и без зависимости от
  платформы; переводы строк только `\n`.
- Формат CSV: заголовок `timeStamp,elapsed,label,responseCode,responseMessage,
  threadName,dataType,success,failureMessage,bytes,sentBytes,grpThreads,
  allThreads,URL,Latency,IdleTime,Connect`, как в JMeter 5.6.3.
- Формат XML: `<testResults version="1.2">` с плоскими `<httpSample>` (атрибуты
  `t it lt ct ts s lb rc rm tn dt by sby ng na`) и дочерним
  `<java.net.URL>`; у упавших выборок `<assertionResult>` с `<failureMessage>`.
- Переменные окружения: `LTV_PROBE_PROFILE` (проба); `LTV_REQUIRE_POSTGRES=1`
  (тест). Коды выхода и вывод CLI продукта не меняются.
- `LTV_REQUIRE_POSTGRES`: не задана или пуста - режим выключен; `1` - строгий;
  любое иное значение (`true`, `0`, `1` с пробелом) роняет тест явной ошибкой, а не
  выключает gate молча (Astra, п. 3). Существующий `LT_VERDICT_PG_IT_DEDICATED=true`
  остаётся отдельным обязательным условием настройки.
- `LTV_PROBE_PROFILE`: пусто или `basic` - прежний путь; неизвестное значение -
  `exit 1` с сообщением.

## Rulings

- Ruling: колонок 17, а не 12-16. Это полный набор JTL JMeter 5.6.3 (его
  заголовок в фикстуре), а нижняя граница 12 нужна лишь чтобы вход не был
  игрушечным. Цена ошибки: низкая. Но наличие `responseMessage` и `dataType`
  включает в парсере `previewMixedParents`/`containsParentPrefix`, то есть
  лишний полный проход по файлу, когда префикса parent-строк нет (Astra, п. 5).
  Это как раз реалистичная нагрузка, парсер не меняется; замеры читать с этой
  поправкой.
- Ruling: XML-профиль в пробе берёт меньше строк, чем CSV, чтобы размер файла
  был сопоставим (допуск +-25% по байтам; число строк подбирается по измерению,
  значение и фактические размеры будут в отчёте). Warmup реалистичных профилей
  - 1/10 строк измерения, тот же профиль и seed 1. Цена ошибки: проба либо
  длиннее, либо недогружает парсер.
- Ruling: strict-режим включается значением `1`, а не `true`, и затрагивает
  только условия настройки PostgreSQL (флаг `LT_VERDICT_PG_IT_DEDICATED`,
  обязательные `LT_VERDICT_PG_IT_*`, порт-число, разные роли, расширение
  `pg_stat_statements`), по одному механизму `assumeIntegration`. Отсутствие расширения тоже роняет: это требование gate,
  а не среда исполнения. Цена ошибки: ложное падение в CI с настроенной БД без
  расширения; тогда оно показывает реальную дыру gate.
- Ruling: Python-генератор остаётся без внешних библиотек, XML пишется
  строками (с экранированием `&<>"`), а не через `xml.etree`: поток без
  удержания строк в памяти, как у существующей функции.
- Ruling: JTL JMeter не гарантирует порядок timestamp, но парсер тоже не
  требует; профиль сохраняет шаг 10 мс, как basic, чтобы длина прогона для
  10 млн строк (100 000 с) оставалась в уже проверенных пределах.

## Критерии приёмки и проверка

1. `python -m unittest tools.test_generate_jtl -v` зелёный; новый тест, что
   basic побайтно прежний (SHA-256 от 100 строк, значение снято с `origin/main`
   до правок), проходит.
2. Realistic CSV на 20 000 строк принимает `ltv analyze` (JMETER_CSV, 200-500
   различных label, доля ошибок в пределах 1-3% при настройке по умолчанию);
   XML аналогично (JMETER_XML).
3. Замер на сгенерированных входах: время analyze не больше 600 с, пик RSS ниже
   2 ГиБ при `JAVA_TOOL_OPTIONS=-Xmx1536m`, SHA-256 `result.json` одинаков на
   трёх прогонах. Лучше всего запуск самого `tools/perf/jtl_probe.sh` в WSL
   (Linux, `taskset`, `/usr/bin/time`) с профилями basic, realistic-csv,
   realistic-xml; числа в отчёте.
4. Gradle: целевой тест `PostgresSourceTest` с `LTV_REQUIRE_POSTGRES=1` и без,
   оба раза с `cleanTest` (переменная окружения не вход задачи `test`, иначе
   `UP-TO-DATE`; Astra, п. 1). Без переменной и без настройки БД тест реальной
   БД пропускается; с `LTV_REQUIRE_POSTGRES=1` падает на первом незаданном
   условии (ручной запуск, результат в отчёте). Строгость каждой ветки
   (флаг, обязательные значения, порт, роли, расширение) доказывается
   юнит-тестами на `assumeIntegration`, потому что реальной БД локально нет.
   Если `LT_VERDICT_PG_IT_*` настроены, тест выполняется как и раньше.
5. Полный прогон «Без CI» из общего брифа целиком, на результате слияния со
   свежим `origin/main`.

## Предложение (НЕ применяется): убрать двойной check в e2e-offline

Статус: НЕПРОВЕРЕННЫЙ GATE до восстановления GitHub Actions. Не применено,
потому что локально нельзя проверить поведение раннера и кэшей.

Сейчас каждый из 4 шардов делает `clean check installDist` онлайн (прогрев
локальных кэшей), затем `clean check installDist` ещё раз офлайн. Оба прогона
выполняют одни и те же тесты Kotlin. Предлагается оставить онлайн-`check`,
а в офлайн-перестройке заменить `check` на `testClasses`: она всё равно
резолвит `testRuntimeClasspath` без сети и собирает UI, то есть сохраняет
проверку «пересборка в офлайне возможна», но не гоняет тесты второй раз.

```diff
       - name: Rebuild runtime offline
-        run: ./gradlew -PnpmOffline=true --offline --no-daemon clean check installDist
+        run: ./gradlew -PnpmOffline=true --offline --no-daemon clean testClasses installDist
```

Утверждение, что `testClasses` резолвит `testRuntimeClasspath` без сети, НЕ
доказано (в `build.gradle.kts` runtime classpath подключается отдельно к
исполняющим задачам; Astra, п. 7), поэтому дифф - гипотеза. Риск: если `check` включает задачи (линтеры, ktlint, зависимости плагинов),
которые резолвятся только в нём, офлайн-проверка их больше не покроет. Перед
применением нужен один зелёный прогон Actions с дифом. Дополнительная
экономия (не предлагается сейчас): запускать `check` онлайн только в шарде 1.

Для остальных пунктов W3.6 в workflow (не применять до Actions):

```yaml
# job performance: опциональный запуск реалистичных профилей
#   - run: LTV_PROBE_PROFILE=realistic-csv bash tools/perf/jtl_probe.sh
#   - run: LTV_PROBE_PROFILE=realistic-xml bash tools/perf/jtl_probe.sh
# job со Stage 1 gate PostgreSQL: services: postgres + env LTV_REQUIRE_POSTGRES: "1"
#   и LT_VERDICT_PG_IT_* (HOST, PORT, DATABASE, ADMIN_USER, ADMIN_PASSWORD,
#   CAPTURE_USER, CAPTURE_PASSWORD, DEDICATED=true, ALLOW_INSECURE=true)
```

## Результаты локальных замеров (2026-10-09)

Среда: WSL2 Ubuntu на 32 логических процессорах, JDK Temurin 21.0.12, сама
`tools/perf/jtl_probe.sh` без правок, `taskset -c 0,1`, `-Xmx1536m`,
`/usr/bin/time`. Это не раннер GitHub (иное железо), поэтому числа
ориентировочные, но пределы проверки те же: <=600 с, RSS <2 ГиБ, три
одинаковых SHA-256.

| Профиль | Вход | Время трёх прогонов, с | Пик RSS, КиБ | SHA-256 результата |
| --- | --- | --- | --- | --- |
| basic (CI) | 10 млн строк, 4 колонки | 58,87 / 56,99 / 56,47 | 579440 / 572416 / 580616 | `583a46a9...b56c82` x3 |
| realistic-csv | 10 млн строк, 17 колонок, 300 label, 2% ошибок (около 1,46 ГБ) | 74,14 / 97,23 / 69,11 | 588968 / 588296 / 588624 | `0a629cda...e5364dc6` x3 |
| realistic-xml | 5 млн строк, около 1,43 ГБ | 54,56 / 47,43 / 54,06 | 508408 / 508728 / 509080 | `b58b2cd0...b87bf56` x3 |

Реалистичный CSV медленнее basic примерно на 20-70% (больше колонок и
дополнительный проход `previewMixedParents`), запас до пределов остаётся
большим (по времени около 6x, по памяти более 3x).

## Находка для части (b): кэш сборки и строгий gate

`org.gradle.caching=true`, а `LTV_REQUIRE_POSTGRES` и `LT_VERDICT_PG_IT_*` не
входят в ключ кэша задачи `test`. Проверено: повторный запуск
`cleanTest test` с `LTV_REQUIRE_POSTGRES=1` без `--no-build-cache` вернул
`BUILD SUCCESSFUL` из кэша (строгий режим не сработал), с `--no-build-cache`
тест упал, как задумано. В CI `setup-java cache: gradle` кэширует
`~/.gradle/caches`, куда входит `build-cache-1`. Поэтому job со строгим gate
PostgreSQL обязан запускать `./gradlew --no-build-cache cleanTest test ...`
(или часть (b) добавит эти переменные во входы задачи в `build.gradle.kts`).
В этот PR `build.gradle.kts` не входит (вне запрошенного).

## Неподтверждённое после этого PR

- Запуск реалистичных профилей и service container PostgreSQL в Actions.
- Время и RSS на раннере GitHub (два vCPU); локальный замер идёт на WSL с
  привязкой `taskset -c 0,1`, но на другом железе.
