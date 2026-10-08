# План: classpath `bin\ltv.bat` не упирается в лимит cmd.exe

> Исполнитель: один агент, суперсила `superpowers:test-driven-development`. Шаги с чекбоксами.

**Цель:** `bin\ltv.bat` из дистрибутива (`distZip`/`installDist`) работает при любой разумной длине пути установки.

**Архитектура:** в Gradle-задаче `startScripts` строка `set CLASSPATH=...` Windows-скрипта заменяется на `set CLASSPATH=%APP_HOME%\lib\*` (wildcard-classpath JVM). Unix-скрипт `bin/ltv` не меняется.

**Технологии:** Gradle Kotlin DSL (`build.gradle.kts`), JUnit 6.

**Спек:** отдельного спека нет; требования заданы владельцем в перечне `2026-10-08-review-work-plan.md` (W1.2b), дефект найден при W1.2 (PR #207).

## Запрос и границы

```text
REQUESTED: чтобы bin\ltv.bat работал при длинном пути установки (дефект v0.1.0 для Windows).
REQUIRED TO ACHIEVE IT: переписать строку CLASSPATH Windows-скрипта в build.gradle.kts (tasks.startScripts doLast);
  JUnit-проверка сгенерированных скриптов; changelog-фрагмент; этот план.
NOT REQUIRED: pathing jar, новая Gradle-задача/плагин, смена версий kotlin-reflect/kotlin-stdlib, правка Unix-скрипта,
  правка README/docs (ограничения на ltv.bat в main нет), правка release.yml, Windows-CI.
EXPECTED FILES TO CHANGE: build.gradle.kts, src/test/kotlin/io/ltverdict/cli/WindowsLauncherScriptTest.kt (новый),
  changelog.d/windows-launcher-classpath.fixed.md (новый), этот план.
```

Documentation impact: пользовательская документация не меняется (в `main` об ограничении длины пути ничего не сказано);
в README PR #207 строки об ограничении подлежат удалению после слияния (см. отчёт). Журнал: `changelog.d/windows-launcher-classpath.fixed.md`.

## Публичные контракты

- Формат вывода CLI, коды выхода, схемы, API не меняются.
- Меняется только содержимое `bin\ltv.bat` в дистрибутиве: одна строка `set CLASSPATH=` (список из ~48 jar заменён на `%APP_HOME%\lib\*`).
  Остальные строки (поиск Java, `JAVA_OPTS`, `LTV_OPTS`, `DEFAULT_JVM_OPTS`, `%*`, код выхода) не меняются.
- `bin/ltv` (Unix) и состав `lib/` не меняются. Зависимости не добавляются и не обновляются.

## Решения (Ruling)

- Ruling: выбран wildcard-classpath `lib\*`, а не pathing jar. Почему: wildcard это одна замена строки в уже существующей задаче;
  pathing jar требует новой задачи сборки jar, исключения зависимостей из вывода, относительных URL в манифесте и меняет состав
  `lib/`. Java сама разворачивает `dir\*` в jar-файлы каталога (документированная возможность `-classpath`), `cmd.exe` символ `*`
  в кавычках не трогает. Цена ошибки: порядок jar в wildcard не определён, но проверено, что в 48 jar нет ни одного
  совпадающего `.class` и `META-INF/services/*`; из всех 11130 записей совпадают только `META-INF/MANIFEST.MF`, `INDEX.LIST`,
  `LICENSE.txt` и `io.netty.versions.properties` (Netty читает их через `getResources`, все копии), то есть порядок на поведение не влияет.
- Ruling: Unix-скрипт не трогается (там лимита нет, а wildcard в `eval`-цепочке `bin/ltv` лишний риск). Цена ошибки: нулевая.
- Ruling (по совету Astra): замена в `doLast` с жёсткой проверкой, что строка `set CLASSPATH=` найдена ровно одна. Почему: если шаблон
  Gradle изменится, молчаливое отсутствие замены вернуло бы дефект, а `distZip` не обязан гонять тесты. Цена ошибки: сборка падает громко вместо тихого дефекта.
- Ruling: проверка JUnit читает `build/scripts/*` после `startScripts`, а не запускает `cmd.exe`. Почему: тест остаётся
  кроссплатформенным, CI на Ubuntu; реальный запуск на длинном пути выполняется вручную (ниже) и описан в PR. Цена ошибки:
  тест ловит регресс шаблона, но не лимит самого `cmd.exe`; это покрыто ручной проверкой и (при желании) будущим Windows-CI.

## Критерии приёмки

1. Сгенерированный `build/scripts/ltv.bat` содержит ровно одну строку `set CLASSPATH=%APP_HOME%\lib\*` и не содержит `.jar` в
   `CLASSPATH`; CRLF-окончания строк сохранены.
2. `build/scripts/ltv` по-прежнему перечисляет `lt-verdict.jar` и остальные jar в `CLASSPATH=` и не содержит `lib/*`.
3. Распакованный `ltv.zip` на пути более 200 символов: `ltv.bat --version`, `ltv.bat analyze <jtl>` и `ltv.bat ui` работают;
   до правки при 148 символах путь падал с «The input line is too long / The syntax of the command is incorrect».
4. `bin/ltv` в Git Bash: `--version` и `analyze` дают прежний результат.
5. `ltv ui` отдаёт страницу (ресурс `web/` внутри `lt-verdict.jar`).

## Задача 1: проверка и правка

**Файлы:** Create `src/test/kotlin/io/ltverdict/cli/WindowsLauncherScriptTest.kt`; Modify `build.gradle.kts`.

- [ ] Шаг 1. Красный тест: `WindowsLauncherScriptTest` читает `build/scripts/ltv.bat` и `build/scripts/ltv`, проверяет критерии 1-2.
  В `build.gradle.kts`: `tasks.test { inputs.files(tasks.startScripts).withPathSensitivity(PathSensitivity.RELATIVE) }` (ввод неявно даёт зависимость), чтобы скрипты были готовы и
  входили в ключ кэша.
- [ ] Шаг 2. Запуск (ожидается FAIL на критерии 1):
  `. F:\Coding\LT-Verdict\.worktrees\_tools\ltv-slot.ps1; Invoke-LtvSlot { .\gradlew.bat test --tests "io.ltverdict.cli.WindowsLauncherScriptTest" }`
- [ ] Шаг 3. Реализация в `build.gradle.kts`:

```kotlin
tasks.startScripts {
    doLast {
        val classpathLine = Regex("(?m)^set CLASSPATH=.*$")
        val script = windowsScript.readText()
        check(classpathLine.findAll(script).count() == 1) { "ltv.bat: expected exactly one CLASSPATH line, template changed" }
        windowsScript.writeText(
            script.replace(classpathLine, Regex.escapeReplacement("set CLASSPATH=%APP_HOME%\\lib\\*")),
        )
    }
}
```

- [ ] Шаг 4. Тот же запуск, ожидается PASS.
- [ ] Шаг 5. Ручная проверка критериев 3-5 (ниже), затем коммит
  `fix(build): use a wildcard classpath in the Windows launcher` (стейдж явными путями).

## Ручная проверка (критерии 3-5)

1. `.\gradlew.bat distZip`; распаковать `build\distributions\ltv.zip` в `F:\ltvdeep\` + `longdirname_` x10 + `x` (до правки 148 символов
   пути к `ltv.bat`; повторить на пути 200+ символов).
2. `ltv.bat --version`, `ltv.bat analyze` на фикстуре JTL, `ltv.bat ui` (проверить `http://127.0.0.1:<порт>/` и `/api/...`).
3. Git Bash: `bin/ltv --version`, `bin/ltv analyze` на той же фикстуре.
4. Классы: сканированием 48 jar подтверждено отсутствие дублей `.class`/`META-INF/services`; запуск с kotlin-reflect 2.3.21 рядом
   с kotlin-stdlib 2.4.10 фиксируется в отчёте (не чинится).

## Review Focus

- Путь установки с пробелом или не-ASCII: wildcard в кавычках должен работать (проверить на каталоге с пробелом и кириллицей,
  запуск из другого рабочего каталога, код выхода). Метасимволы вроде `&` в пути ломают неэкранированные `set` шаблона Gradle:
  это существующее ограничение, вне скоупа (в отчёт).
- Строка `-classpath "%CLASSPATH%"` в вызове java сохранена; окончания строк CRLF не изменились (тест читает сырой текст).
- Каталог `lib` с лишним jar пользователя: попадёт в classpath (отличие от явного списка; для дистрибутива допустимо, указать в отчёте).
- Изменение шаблона Gradle: `check` в `doLast` и тест падают, а не молчат.

## Результаты проверки (2026-10-08)

- Воспроизведение до правки: путь к `ltv.bat` 148 символов, `--version` и `analyze` падали («The input line is too long. The syntax of the
  command is incorrect.», код 255); строка CLASSPATH в шаблоне 2304 символа до подстановки `%APP_HOME%`.
- Красный тест: `WindowsLauncherScriptTest` падал на `ltv.bat` (Unix-проверка проходила). Зелёный после правки.
- После правки: путь к `ltv.bat` 205 символов, пробелы и кириллица, запуск из другого каталога: `analyze` на фикстуре
  `fixtures/slice1/jmeter/csv-5.6.3/input.jtl` код 0, `ui` отдаёт `/` и `/assets/*.js` (200), `bin/ltv` в Git Bash прежний.
- Рядом лежащие kotlin-reflect 2.3.21 и kotlin-stdlib 2.4.10 на запуск не влияют (дублей классов нет, команды работают); не чинится.
- Сканирование 48 jar: дублей `.class` и `META-INF/services` нет; совпадают только `MANIFEST.MF`, `INDEX.LIST`, `LICENSE.txt`,
  `io.netty.versions.properties`.
- Совет Codex Astra учтён: уникальность строки CLASSPATH, `PathSensitivity.RELATIVE`, проверка CRLF и `-classpath "%CLASSPATH%"`,
  пути с пробелами и не-ASCII, довод про порядок jar уточнён сканом ресурсов. Метасимволы (`&`) в пути установки: существующее
  ограничение шаблона Gradle, вне скоупа.
