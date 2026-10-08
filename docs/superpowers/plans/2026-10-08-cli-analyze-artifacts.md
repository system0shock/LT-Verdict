# План W1.1: гигиена CLI (`analyze`, `summary`, `--help`, `--version`)

**Дата:** 2026-10-08. **Ветка:** `feat/cli-analyze-artifacts` (от `origin/main` `6d9e0ac`).
**Пункт перечня:** W1.1 (`docs/superpowers/plans/2026-10-08-review-work-plan.md`, файл лежит вне
репозитория), основание: usability B4, `CommandLine.kt:767-781`. **Путь brainstorming:**
ограниченная правка существующего CLI; требования согласованы владельцем в перечне, вопросов
владельцу нет, спорное решено ниже записями «Ruling». Совет Codex Astra по плану учтён (раздел
«Замечания Astra»).

```text
REQUESTED:
  Дословно из перечня владельца: «Гигиена CLI. `analysis_id` в выводе `analyze`; `--out-dir` с
  `result.json`, `report.html`, `chart.svg`, `summary.txt`, `junit.xml`; `--help`, `--version`;
  policy из stdin; команда `summary` с транзакциями и перцентилями в компактном JSON. Критерий
  готовности: один вызов в CI даёт все артефакты; `report` не требует ручного поиска id.»
  Плюс по поручению оркестратора: версия в сборке (Gradle пишет, CLI читает) для --version.
REQUIRED TO ACHIEVE IT:
  - cli/CommandLine.kt: разбор --out-dir, --policy -, --help/-h/help, --version, команда summary,
    строка идентификаторов в stderr, usage вынесен в функцию usageText().
  - новый cli/CliArtifacts.kt: функции без новых абстракций (summaryJson, summaryText, junitXml,
    описание проверки), чистые функции над байтами analysis-result.json; существующие
    renderHtmlReport и renderSavedLoadChart вызываются как есть.
  - build.gradle.kts: version и ресурс ltv-version.properties (processResources, expand).
  - src/main/resources/ltv-version.properties: version=${version}.
  - тесты: CommandLineTest.kt (+ правка проверок «stderr пуст» у analyze), новый CliArtifactsTest.kt,
    ShellParityTest.kt (одна проверка stderr).
  - документация: docs/user/slice-1-local-analysis.md (раздел CLI), changelog.d фрагменты.
NOT REQUIRED:
  - baseline в CLI (W2.3), политика и пропавшая транзакция (W1.3), релиз/zip/тег (W1.2),
    схема JSON-контракта summary в docs/contracts, ввод summary из файла или stdin,
    формат report --format junit, общий фреймворк отчётов, русская локализация вывода CLI,
    изменение canonical-байт analysis-result.json или identity, изменение engineVersion.
EXPECTED FILES TO CHANGE:
  src/main/kotlin/io/ltverdict/cli/CommandLine.kt (изменён)
  src/main/kotlin/io/ltverdict/cli/CliArtifacts.kt (новый)
  src/main/resources/ltv-version.properties (новый)
  build.gradle.kts
  src/test/kotlin/io/ltverdict/cli/CommandLineTest.kt, CliArtifactsTest.kt (новый)
  src/test/kotlin/io/ltverdict/integration/ShellParityTest.kt (одна проверка stderr)
  docs/user/slice-1-local-analysis.md, changelog.d/cli-analyze-artifacts.added.md,
  changelog.d/cli-analyze-artifacts.changed.md, этот план
```

## Публичный контракт CLI (фиксируется до кода)

Зависимости production не добавляются. Меняется публичный контракт CLI (ниже). Схемы
`analysis-result.v1`, `analysis-identity.v1`, API и хранилище не меняются.

### Синтаксис

```text
ltv --help | -h | help                     usage в stdout, exit 0 (также `ltv <команда> --help`)
ltv --version                              `ltv <версия>` в stdout, exit 0
ltv analyze <input> ... [--policy <policy.json>|-] [--out-dir <dir>]
ltv policy validate <policy.json>|-
ltv summary <run-id> <analysis-id> [--data-dir <path>]
```

`--help` и `--version` обрабатываются первыми: не читают stdin, не трогают файловую систему.

### Коды выхода

Прежние без изменений: `0` PASS/NO_POLICY/успешный export, `2` FAIL, `3` NO_VERDICT/DEGRADED,
`4` недопустимый вход, `5` недопустимая policy, `6` data dir занят, `64` usage, `70` внутренняя
ошибка. Новые случаи используют существующие коды:

| Ситуация | Код | stderr |
| --- | ---: | --- |
| `--out-dir`: путь не каталог/симлинк, целевой файл симлинк/не обычный файл/совпадает с `<input>` | 4 | `OUT_DIR_INVALID` (до анализа, ничего не записано) |
| не удалось записать файл в `--out-dir` | 4 | `OUT_DIR_WRITE_FAILED` (stdout пуст) |
| `summary` для отсутствующего run/analysis | 4 | `ANALYSIS_NOT_FOUND` (как `report`) |
| `summary`: data dir занят / повреждён | 6 / 70 | как `report` |
| `--help`, `--version` | 0 | пусто |

Код выхода `analyze` с `--out-dir` определяется вердиктом, как и без него, кроме отказа экспорта
(ниже).

### Идентификаторы в выводе `analyze`

stdout `analyze` остаётся байт-в-байт canonical `analysis-result.v1` (контракт `report --format
json` и `ShellParityTest`). Идентификаторы выводятся одной строкой в stderr сразу после
сохранения анализа (при любом коде выхода, если анализ сохранён: `0`, `2`, `3`, а также `4` при
`INVALID` run):

```text
analysis_id=<64 hex> run_id=<id>
```

Те же значения лежат в `summary.txt` и в JSON команды `summary`.

### `--policy -` и `policy validate -`

Значение `-` означает чтение policy из stdin (до 1 MiB, как для файла). Файл с именем `-` в
текущем каталоге задаётся как `./-`. Хэш policy считается по байтам, поэтому `analysis_id`
совпадает с запуском из файла с тем же содержимым. Ошибки: код `5`, как для файла.

### `--out-dir <dir>`

Каталог создаётся (включая родителей), если его нет. Пишутся пять файлов с фиксированными
именами; существующие файлы этих имён перезаписываются (повторный прогон в тот же каталог
допустим). stdout `analyze` при этом не меняется (результат печатается как раньше).

**Проверка до анализа** (код `4`, `OUT_DIR_INVALID`, ничего не записано, data dir не тронут):
путь существует и не каталог либо симлинк; любой из пяти целевых файлов существует и является
симлинком, не обычным файлом или тем же файлом, что `<input>`. Запись файлов идёт без следования
по ссылкам (`NOFOLLOW_LINKS`, `TRUNCATE_EXISTING`).

**Порядок и отказ записи.** Все артефакты строятся в памяти внутри открытого DataDirectory (как
`report`), затем DataDirectory закрывается, в stderr печатается строка идентификаторов, затем
артефакты пишутся на диск, последним печатается stdout. Если запись не удалась: код `4`,
`OUT_DIR_WRITE_FAILED`, stdout пуст, анализ остаётся сохранённым, часть файлов могла быть
записана или перезаписана (вердикт в коде выхода при этом не отражается; ошибка экспорта
приоритетнее, потому что гейт без артефактов нельзя считать пройденным).

| Файл | Содержимое |
| --- | --- |
| `result.json` | canonical `analysis-result.v1`, те же байты, что stdout и `report --format json` |
| `report.html` | `renderHtmlReport(result, analysisId)`, как `report --format html` |
| `chart.svg` | `renderSavedLoadChart(rollup-60s.ndjson)`, как `report --format svg` |
| `summary.txt` | текст для логов CI (ниже) |
| `junit.xml` | JUnit-совместимый XML (ниже) |

**summary.txt** (UTF-8, `\n`):

```text
LT Verdict summary
run_id: <id>
analysis_id: <id>
run_validity: VALID|DEGRADED|INVALID
policy_verdict: PASS|FAIL|NO_POLICY|NO_VERDICT
exit_code: <0|2|3|4>
samples: <n>  errors: <n>  p95_ms: <v>  p99_ms: <v>
rules: <N> (PASS <a>, FAIL <b>, NO_VERDICT <c>)
FAIL <rule_id>[ @ <window_id>]: <описание>
NO_VERDICT <rule_id>[ @ <window_id>]: <reason>
```

Строки `FAIL`/`NO_VERDICT` идут только для непройденных проверок (порядок evidence); строка
`rules:` опускается, если проверок нет. Метрика без данных выводится как `n/a`.

**Описание проверки** (общее для summary.txt и junit.xml): для `policy_check`:
`<metric>: observed <observed> (rule <operator> <threshold>)`, причина NO_VERDICT: `reason_code`;
для SLA `resource_policy_check`: `series <series_id>, window <window_id> (violation <operator>
<threshold> <unit>)`, причина NO_VERDICT: `reason`. Оператор ресурсного правила описывает нарушение,
а не условие прохождения (как в HtmlReport), поэтому слово «required» не используется. Ratio
`{numerator, denominator}` выводится частным с шестью знаками.

**junit.xml**: один `testsuite name="lt-verdict"` на прогон (Ruling 3), атрибуты `tests`,
`failures`, `errors`, `skipped="0"`, `time="0"`. Каждый `testcase` имеет `classname` и `time="0"`.
Диагностика пишется и в атрибут `message`, и в текстовое содержимое элемента `failure`/`error`.
Классы (пространства имён):

- `lt-verdict.gate`, `name="gate"`: один случай, отражает итог гейта и совпадает с кодом выхода.
  Проходит только при `run_validity == VALID` и `policy_verdict in {PASS, NO_POLICY}`;
  `FAIL` даёт `<failure>`; `NO_VERDICT`, `DEGRADED`, `INVALID` дают `<error>`; сообщение
  `policy_verdict=<..> run_validity=<..> reasons=<analysis_coverage.reasons>`. Так счётчики файла
  согласованы с гейтом даже в режиме `capacity_step`, где вердикт выводится отдельно от проверок.
- `lt-verdict.policy`, по одному случаю на `policy_check`: `name = <rule_id>` или
  `<rule_id> @ <window_id>`.
- `lt-verdict.resource-sla`, по одному случаю на `resource_policy_check` с `effect == "sla"`:
  `name = <rule_id> @ <window_id>`.

`PASS` проходит; `FAIL` даёт `<failure>`; `NO_VERDICT` даёт `<error>`. Значения экранируются для
XML 1.0 (символы вне допустимого диапазона заменяются на `?`). Каждый прогон `analyze` пишет свой
отдельный файл; объединение нескольких прогонов в CI-отчёте выполняет сам CI. Публикация в CI
требует настройки и должна идти после ненулевого кода выхода: Jenkins `junit` в
`post { always }`, GitLab `artifacts:reports:junit` с `when: always`, GitHub Actions: reporter-шаг
с `if: always()`. Код выхода `ltv` сохраняется и сам проваливает шаг; JUnit-отчёт его не заменяет.

### Команда `summary`

`ltv summary <run-id> <analysis-id> [--data-dir <path>]` читает сохранённый анализ (как `report`)
и печатает в stdout один компактный canonical JSON (ключи отсортированы, без пробелов,
без завершающего перевода строки), `cli-summary.v1`:

```json
{"schema_version":"cli-summary.v1","run_id":"...","analysis_id":"...",
 "run_validity":"VALID","policy_verdict":"FAIL",
 "overall":{"samples":3,"errors":1,"error_rate":0.333333,"p50":1,"p95":26,"p99":26,"max":26,"rps":34.883721},
 "transactions":[{"group_path":["a"],"label":"b","kind":"JMETER_SAMPLER","samples":1,"errors":0,
                  "error_rate":0,"p50":1,"p95":1,"p99":1,"max":1,"rps":11.627907}],
 "rules":[{"rule_id":"overall-p95","status":"FAIL","metric":"response_time_p95_ms",
           "operator":"lte","threshold":250,"observed":451}]}
```

(выше ключи показаны в логическом порядке; фактический вывод отсортирован канонически.)

- `overall` и `transactions` берутся из evidence `metric_summary` (без `window_id`); `error_rate`
  и `rps` вычисляются как `numerator/denominator` с шестью знаками (округление HALF_UP, нули справа
  убираются), `null`, если знаменатель 0 или поля нет. Значения `p50|p95|p99|max` и счётчики
  копируются без изменений. Порядок `transactions` как в результате.
- `rules` — по одному элементу на `policy_check` и SLA `resource_policy_check`. Общие поля:
  `rule_id`, `status`, `operator`, `threshold`, `window_id` (если есть), `reason` (причина
  NO_VERDICT: `reason_code` или `reason`, если есть). У `policy_check` добавляются `metric` и
  `observed` (ratio выводится числом, как выше); у ресурсных — `series_id` и `unit`. Без проверок
  `rules` — пустой массив. Итоговый вердикт анализа лежит в `policy_verdict`, не выводится из `rules`.
- Код выхода `0` при успешном чтении, независимо от вердикта (как `report`).

### Версия в сборке

`build.gradle.kts`: `version = providers.gradleProperty("ltvVersion").getOrElse("0.1.0-SNAPSHOT")`;
`processResources` подставляет `version` в `ltv-version.properties` (`version=${version}`) только в
этом файле (`filesMatching("ltv-version.properties") { expand(...) }`, UI-ассеты `web/` не
фильтруются, `dependsOn(uiBuild)` сохраняется) и объявляет `inputs.property("ltvVersion", ...)`,
чтобы смена версии без `clean` пересобирала ресурс. CLI читает ресурс `/ltv-version.properties` и
печатает `ltv <version>`. Если ресурса нет, значение пустое или осталось неразвёрнутое `${version}`
(запуск из IDE), печатается `ltv unknown`, код 0. W1.2 (release) задаёт версию тега ключом
`-PltvVersion=0.1.0`. Тег не создаётся.

## Ruling

1. **Ruling: идентификаторы в stderr, а не в stdout.** stdout `analyze` — канонические байты
   результата, на которых держатся `report --format json` и ShellParityTest; `analysis_id` не часть
   результата (хэш identity). Добавлять поле в результат нельзя (контракт и хэш). Строка в stderr
   не ломает конвейеры `> result.json`. Цена ошибки: проверки «stderr пуст» у `analyze` в тестах
   обновлены; скрипты, считающие любой stderr ошибкой, надо поправить (редкий случай, отмечено в
   changelog).
2. **Ruling: `summary` принимает `<run-id> <analysis-id>`, как `report`.** Единый стиль, без нового
   способа ввода. Критерий «report не требует ручного поиска id» закрывается строкой в stderr, файлом
   `summary.txt` и тем, что `--out-dir` выдаёт все артефакты одним вызовом (report вообще не нужен).
   Цена ошибки: ввод из файла/stdin добавляется позже без ломки контракта.
3. **Ruling: junit.xml — один testsuite на прогон, testcase на проверку плюс случай `gate`.**
   CI показывает правила как тесты; случай `gate` гарантирует, что счётчики файла согласованы с
   кодом выхода (в том числе для ёмкости и DEGRADED/INVALID). Цена ошибки: смена группировки
   меняет имена тестов в истории CI (GitLab группирует по classname+name), поэтому имена
   фиксируются здесь.
4. **Ruling: `-` как stdin, файл `-` задаётся `./-`.** Общепринятое соглашение CLI, минимум кода.
5. **Ruling: перезапись файлов в `--out-dir`**, но только обычных файлов, не ссылок. Имена
   фиксированы, повторный запуск в том же рабочем каталоге CI штатен.
6. **Ruling: версия `0.1.0-SNAPSHOT` по умолчанию с переопределением `-PltvVersion`.**
   `development-process.md` фиксирует SemVer `v0.1.0`; релиз ещё заморожен (D1), поэтому
   неотрелизенная сборка помечена `-SNAPSHOT`. Эффект: имя zip станет `ltv-0.1.0-SNAPSHOT.zip`, jar
   `lt-verdict-0.1.0-SNAPSHOT.jar` (ссылок на эти имена в репозитории нет, проверено поиском).
7. **Ruling: `chart.svg` и HTML строятся внутри открытого DataDirectory** (читают
   `rollup-60s.ndjson` анализа), так же как `report`.
8. **Ruling: `--help` не считается usage error** и печатает в stdout; usage при ошибках по-прежнему
   в stderr с кодом `64`.
9. **Ruling: отказ экспорта приоритетнее вердикта** (код 4): гейт CI без артефактов считается
   проваленным; анализ при этом сохранён, а идентификаторы уже напечатаны.

## Замечания Astra (read-only совет по плану) и что с ними сделано

Все замечания проверены по коду; принято всё, кроме расширений вне скоупа.

- Gradle: `expand` только для `ltv-version.properties`, `inputs.property`, проверка двух версий
  без clean (принято, см. раздел версии).
- Симлинки отдельных артефактов, алиас с входным файлом (принято, предпроверка и `NOFOLLOW_LINKS`).
  Атомарная запись через временный файл и rename не вводится: избыточно для CI-каталога.
- Семантика отказа записи (принято, раздел `--out-dir`, Ruling 9).
- Ресурсные проверки: поля `reason`, `series_id`, оператор описывает нарушение (принято).
- JUnit как итог гейта: случай `gate` (принято, Ruling 3).
- Коллизии имён: пространства `classname` (принято).
- Текст внутри `failure`/`error`, `time="0"` (принято).
- Документировать публикацию JUnit в Jenkins/GitLab/GitHub с `always` (принято: короткий абзац
  в документации; настройку CI-шаблонов не добавляем).
- Тесты границ: INVALID без rollup, ошибка записи, повторный каталог со старым `junit.xml`,
  help без stdin, summary busy/corrupt, лимит stdin (принято).
- Версия: `-PltvVersion`, фолбэк на `unknown` для пустого/неразвёрнутого значения (принято).
- Файл перечня вне репозитория: требования процитированы в REQUESTED (принято).
- Не принято: проверка JUnit на конкретных версиях сторонних репортёров (вне доступных средств,
  фиксируется как непроверенное допущение в отчёте).

## Критерии приёмки

1. `ltv --version` печатает `ltv <версия сборки>` (по умолчанию `0.1.0-SNAPSHOT`, с
   `-PltvVersion=X` равна `X` без `clean`), код 0; `ltv --help` печатает usage со всеми командами
   и новыми флагами, код 0, stderr пуст; help и version не читают stdin и не трогают ФС; `ltv`
   без аргументов по-прежнему код 64.
2. `analyze ... --out-dir D` создаёт `D/result.json` (байты равны stdout и
   `report --format json`), `D/report.html` (равен `report --format html`), `D/chart.svg`
   (равен `report --format svg`), `D/summary.txt`, `D/junit.xml`; код выхода равен коду без
   `--out-dir`.
3. `analyze` печатает в stderr ровно одну строку `analysis_id=<hex64> run_id=<id>`; значения
   совпадают с каталогом анализа и `summary.txt`.
4. `--policy -` с содержимым файла даёт тот же `analysis_id` и stdout, что `--policy <file>`; невалидная
   policy из stdin даёт код 5; `policy validate -` работает так же.
5. `summary <run> <id>` печатает компактный JSON из контракта: транзакции с p50/p95/p99/max,
   overall, rules; отсутствующий анализ даёт 4; занятый data dir 6.
6. `junit.xml` — валидный XML (парсится стандартным `DocumentBuilder`, в том числе с
   недопустимыми для XML символами в метке); `failures + errors == 0` тогда и только тогда, когда
   код выхода `0`, для случаев PASS, FAIL, NO_VERDICT, DEGRADED, INVALID, NO_POLICY.
7. `--out-dir` на существующий файл, симлинк (каталога или любого из пяти файлов) или на файл,
   совпадающий с `<input>`, даёт 4 без анализа и без записи в data dir; сбой записи даёт 4,
   `OUT_DIR_WRITE_FAILED`, пустой stdout, строку идентификаторов в stderr; `--out-dir` при
   `INVALID` run (нет rollup) отрабатывает с кодом 4 и пишет артефакты; повторный запуск в тот же
   каталог перезаписывает старый `junit.xml`.
8. Все прежние тесты зелёные (кроме обновлённых проверок stderr у `analyze`); `ktlintCheck`
   зелёный; `changelog_assemble.py --check` зелёный.

## Задачи (TDD: красный тест, затем код)

1. **Версия.** Тест `--version` (красный: нет команды) → Gradle + ресурс + чтение версии.
2. **Help.** Тесты `--help`, `-h`, `help`, `analyze --help` → usageText().
3. **stdin policy.** Тесты `--policy -` и `policy validate -` (stdin подаётся через новый параметр
   `stdin: InputStream = System.in` в `runCli`) → `readPolicy`.
4. **Артефакты.** Тесты `CliArtifactsTest` (summaryJson/summaryText/junitXml на сохранённом
   результате с FAIL и PASS политикой, XML парсится) → `CliArtifacts.kt`.
5. **`--out-dir` и stderr-строка.** Тесты в `CommandLineTest` → `analyze`.
6. **`summary`.** Тесты → команда.
7. **Документация и changelog.** Раздел CLI в `slice-1-local-analysis.md`, фрагменты журнала.

## Проверка

```powershell
. F:\Coding\LT-Verdict\.worktrees\_tools\ltv-slot.ps1
Invoke-LtvSlot { .\gradlew.bat test --tests "io.ltverdict.cli.*" --tests "io.ltverdict.integration.ShellParityTest" --offline }
Invoke-LtvSlot { .\gradlew.bat ktlintCheck --offline }
Invoke-LtvExclusive { .\gradlew.bat check --offline }          # полный прогон перед PR
python tools/changelog_assemble.py --check
git diff --stat origin/main...HEAD
```

Ручная проверка: `installDist` (также с `-PltvVersion=9.9.9` без clean), затем
`ltv analyze fixtures/slice1/jmeter/xml-5.6.3/input.xml --policy fixtures/slice1/policies/fail.json --out-dir out --data-dir d`
и `ltv summary <run> <id> --data-dir d`.

Documentation impact: `docs/user/slice-1-local-analysis.md` (раздел CLI) обновляется в этом PR.
