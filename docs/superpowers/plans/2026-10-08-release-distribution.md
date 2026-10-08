# План W1.2: релиз v0.1.0 (zip-дистрибутив и release-workflow)

**Дата:** 2026-10-08. **Ветка:** `feat/release-distribution` (стековая, от
`origin/feat/cli-analyze-artifacts`, PR #204, W1.1). **Пункт перечня:** W1.2
(`docs/superpowers/plans/2026-10-08-review-work-plan.md`, файл лежит вне репозитория),
основание: продукт R6, README `:53-60`. **Путь brainstorming:** ограниченная правка
(сборка уже умеет `distZip`, версия в сборке есть из W1.1); требования согласованы
владельцем, вопросов владельцу нет, спорное решено ниже записями «Ruling».

```text
REQUESTED:
  Дословно из перечня владельца: «Релиз. Версия в сборке, zip-дистрибутив, тег `v0.1`,
  release-workflow. Критерий готовности: установка без JDK-сборки и Node.»
  Решения владельца: первый релиз v0.1.0; тег НЕ создаётся и НЕ пушится агентом (ставит
  владелец); workflow срабатывает на push тега `v*`, собирает дистрибутив, прогоняет
  `ltv --version` из распакованного архива, прикладывает zip и SHA256 к GitHub Release;
  минимальные permissions (contents: write только в публикующем job); actions закреплены
  по SHA с комментарием версии.
REQUIRED TO ACHIEVE IT:
  - .github/workflows/release.yml (новый): job build (contents: read: сборка, smoke,
    sha256, upload-artifact) и job publish (contents: write: download-artifact и
    gh release create --draft, без checkout и без запуска кода проекта); триггеры
    push tags v* и workflow_dispatch (пробная сборка без публикации).
  - README.md: только раздел установки/сборки (строки Нужны JDK 21 ... Linux использует ...):
    подраздел «Установка из архива» и подраздел «Сборка из исходников».
  - docs/development-process.md: короткий раздел «Выпуск версии» в «Версии продукта»
    (как поставить тег, что делает workflow, как провести пробный запуск).
  - changelog.d/release-distribution.added.md.
  - build.gradle.kts НЕ меняется: distZip, applicationName=ltv и -PltvVersion уже есть.
NOT REQUIRED:
  - jlink/jpackage/встроенный JRE (расширение скоупа, заморозка D1; только идея в отчёте).
  - Другие каналы (Docker, Homebrew, Maven), подпись артефактов, SBOM, notarization.
  - Исправление ltv.bat для длинных путей (pathing jar, wildcard classpath): report-only.
  - Прогон полного check в release-workflow (тесты идут в CI на main; тег ставится на
    проверенный коммит main, workflow это проверяет).
  - CHANGELOG.md (выпуск собирает владелец: tools/changelog_assemble.py --apply).
  - Создание и пуш тега, слияние PR, правки остального README (W1.5).
EXPECTED FILES TO CHANGE:
  .github/workflows/release.yml (новый)
  README.md (только раздел установки)
  docs/development-process.md (раздел «Выпуск версии»)
  changelog.d/release-distribution.added.md (новый)
  docs/superpowers/plans/2026-10-08-release-distribution.md (этот план)
```

## Что выяснено фактами (до реализации)

- `./gradlew -PltvVersion=0.1.0 distZip` даёт `build/distributions/ltv-0.1.0.zip`
  (около 18 МБ), корень архива `ltv-0.1.0/` (`bin/ltv`, `bin/ltv.bat`, `lib/`, `docs/`,
  `skills/`, `tools/`). Бит исполнения у `bin/ltv` в zip сохранён. Время файлов в zip
  нормализовано (1980-02-01).
- UI собран внутри `lib/lt-verdict-0.1.0.jar` (`web/index.html`, `web/assets/*`). `ltv ui` из
  распакованного архива отдаёт `/` и `/assets/index-*.js` с кодом 200.
- Байткод Java 21 (major 65), значит нужна Java 21 или новее. jdeps по классам продукта:
  `java.base`, `java.desktop`, `java.net.http`, `java.sql`, `java.xml`; все входят в
  стандартный JRE, поэтому JDK для запуска не нужен, достаточно JRE 21. Это вывод по
  jdeps, запуск на отдельном «чистом» JRE (без JDK) не проверялся.
- Проверка без Gradle/Node в окружении: Windows (`PATH` только `System32` и `Windows`,
  `JAVA_HOME` на JDK 21) и Git Bash (`PATH=/usr/bin:/bin`): `ltv --version` печатает
  `ltv 0.1.0`; `ltv analyze fixtures/slice1/jmeter/csv-5.6.3/input.jtl --out-dir ...` пишет
  `result.json`, `report.html`, `chart.svg`, `summary.txt`, `junit.xml`; без policy код выхода 0.
- Находка: `bin/ltv.bat` разворачивает все 48 jar в одну командную строку; при пути
  установки около 125 символов `analyze` с длинными аргументами падает с «The syntax of
  the command is incorrect» (лимит cmd.exe 8191 символов), `--version` при этом
  проходит. Путь `F:\ltvt` работает. Решение: документировать «распаковывайте в короткий
  каталог» (Ruling 5), исправление `.bat` не делаем.

## Contracts (публичные, фиксируются до реализации)

- Имя артефакта: `ltv-<версия>.zip`, корневой каталог внутри `ltv-<версия>/`, рядом
  `ltv-<версия>.zip.sha256` в формате `sha256sum` (`<hex>  ltv-<версия>.zip`).
- Версия из тега: тег без ведущей `v` целиком становится версией: `v0.1.0` -> `0.1.0`,
  `v0.1.0-rc.1` -> `0.1.0-rc.1` (Gradle `-PltvVersion`, имя zip, корень архива, вывод
  `ltv --version`). Допустимая грамматика (якорная): `^v[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.]+)?$`;
  build metadata (`+...`) не принимается. Иные теги `v*` (например `v0.1`) отклоняются
  шагом проверки с понятной ошибкой. Версия с суффиксом помечает релиз как pre-release.
- Значения тега и входа `version` попадают в shell только через `env:` и проверяются
  регулярным выражением до первого использования; `${{ ... }}` в `run:` не интерполируется.
- publish скачивает только артефакт `ltv-dist` этого же запуска, проверяет
  `sha256sum -c` и состав (ровно два файла), ничего не исполняет из скачанного.
- `ltv --version` печатает `ltv <версия>` (контракт W1.1); smoke сверяет точное равенство.
- Релиз создаётся как draft; публикует владелец вручную (Ruling 2).
- Нет изменений в CLI, API, схемах, зависимостях продукта.

## Rulings

1. **Тег `v0.1.0`, не `v0.1`.** `docs/development-process.md` уже предписывает SemVer
   `v0.1.0`; «v0.1» в перечне считаем сокращением. Workflow принимает только SemVer-тег.
   Цена ошибки: при пуше `v0.1` job build упадёт на первом шаге, тег удаляется и ставится
   заново; публикации нет.
2. **GitHub Release создаётся как draft.** Репозиторий приватный, это первый релиз,
   владелец хочет проверить вложения до публикации. Цена ошибки: один лишний клик
   «Publish release».
3. **Два job: build (read) и publish (write).** Сборка запускает npm и Gradle (цепочка
   поставок); токену записи там делать нечего. publish не делает checkout и не запускает
   код проекта: только скачивает артефакт и вызывает `gh release create`. Цена: два
   дополнительных закреплённых action (`upload-artifact`, `download-artifact`).
4. **Пробный запуск через `workflow_dispatch` без публикации.** Вход `version` (по
   умолчанию `0.0.0-dryrun`); build и smoke выполняются, publish пропускается
   (`if: github.event_name == 'push'`), zip доступен как артефакт запуска. Булева
   «dry-run» не нужна: публикует только тег.
5. **Windows `.bat` и длинный путь: документируем, не чиним.** Исправление (pathing jar
   или wildcard в `startScripts`) меняет сборку и выходит за W1.2. Цена ошибки: пользователь
   с глубоким каталогом видит невнятную ошибку cmd; README прямо говорит про короткий путь.
6. **Проверка «тег на main».** Шаг build проверяет `git merge-base --is-ancestor
   $GITHUB_SHA origin/main` (только для push тега). Основание: `development-process.md`
   (тег ставится на проверенный коммит `main`). Цена ошибки: тег на коммите вне `main`
   отклоняется; для обхода владелец сначала сливает PR.
7. **Тесты в release-workflow не гоняем.** Полный `check` уже обязателен для PR и main; повтор
   удваивает время и ресурсы релиза. Release собирает только `distZip` и smoke архива.
8. **Минимум Java: 21 (JRE достаточно).** Подтверждено байткодом (major 65), jdeps и
   запуском на урезанном runtime, собранном jlink из `java.se` плюс `jdk.unsupported`,
   `jdk.crypto.ec`, `jdk.localedata`, `jdk.zipfs`, `jdk.naming.dns` (26 модулей, без
   инструментов JDK): `--version`, `analyze --out-dir` и `ui` работают. Именованный
   дистрибутив JRE (например Temurin JRE) не проверялся; README говорит «Java 21 или
   новее» и ссылается на проверку на JDK 21 (Temurin). Встроенный JRE (jlink/jpackage)
   отложен как идея.
9. **Порядок выпуска и CI-гейт.** Workflow не проверяет статус CI (нужен `checks: read` и
   логика ожидания). Это предварительное условие владельца: тег ставится на коммит
   `main` с зелёными проверками; ancestry-проверка (Ruling 6) ловит только тег вне `main`.
   Сборка changelog (`--apply`) идёт отдельным PR в `main`; тег ставится на его
   squash-коммит после зелёного CI. Цена ошибки: релиз из коммита с красным CI; draft
   позволяет не публиковать.
10. **Повторный запуск и частичные релизы.** `concurrency` по тегу без отмены; если draft
   или релиз с этим тегом уже есть, `gh release create` падает, владелец удаляет draft и
   перезапускает job (Re-run). Автовосстановление не делаем.

## Критерии приёмки

1. `release.yml` проходит `actionlint` и YAML-парсинг; все `uses:` закреплены по SHA с
   комментарием версии (как в `docs-quality.yml`); `permissions` верхнего уровня
   `contents: read`, `contents: write` только у job `publish`.
2. Локально повторён сценарий job build: `distZip` с `-PltvVersion=0.1.0`, распаковка,
   `ltv --version` = `ltv 0.1.0`, `ltv analyze` на фикстуре даёт пять артефактов, в `env -i`
   окружении без Gradle/Node (Windows `.bat` и sh).
3. README описывает установку из архива (скачать, проверить SHA256, распаковать в
   короткий путь, `ltv --version`, `ltv ui`), требования к Java и сборку из исходников;
   остальной README не затронут (`git diff --stat README.md` показывает один ханк).
4. `docs/development-process.md` содержит шаги выпуска владельцем и пробного запуска.
5. Негативные случаи проверены локально на bash-фрагменте проверки версии: `v0.1`,
   `0.1.0`, `v0.1.0+b1`, `v0.1.0; rm`, пустой вход отклоняются; `v0.1.0`, `v0.1.0-rc.1`
   принимаются. Негатив smoke (не тот `--version`) блокирует publish за счёт `needs`.
   Полный путь публикации локально не проверяем (см. отчёт: что выполнить владельцу).
6. `python tools/changelog_assemble.py --check` проходит; markdownlint проходит на
   изменённых `.md`; `git diff --check` чист; нет секретов и чужих файлов.

## Команды проверки

```text
.\gradlew.bat --no-daemon '-PltvVersion=0.1.0' distZip           # через Invoke-LtvSlot
actionlint .github/workflows/release.yml                          # бинарь v1.7.12 из scratchpad
python -c "import yaml,sys; yaml.safe_load(open('.github/workflows/release.yml'))"
python tools/changelog_assemble.py --check
npx --yes markdownlint-cli2@0.23.2 README.md docs/development-process.md changelog.d/release-distribution.added.md docs/superpowers/plans/2026-10-08-release-distribution.md
git diff --check origin/feat/cli-analyze-artifacts...HEAD
```

## Для владельца: пробный запуск и выпуск

- Пробный запуск: Actions -> Release -> Run workflow (ветка `main` после слияния) или
  `gh workflow run release.yml -f version=0.0.0-dryrun`; в конце запуска скачать
  артефакт `ltv-dist` и проверить его.
- Выпуск: после слияния #204 и этого PR, `python tools/changelog_assemble.py --apply`
  отдельным PR в `main`; после его слияния и зелёного CI на squash-коммите подписанный
  аннотированный тег `git tag -s v0.1.0 -m "LT Verdict 0.1.0"` и
  `git push origin v0.1.0`. Workflow создаст draft-релиз с `ltv-0.1.0.zip` и
  `ltv-0.1.0.zip.sha256`; проверить и нажать Publish.

## Замечания Astra

Учтено: контракт версии (суффикс сохраняется целиком; в первой редакции плана он терялся,
замечание верно), защита от инъекции через имя тега и вход (env + якорная проверка),
передача артефакта между job (имя, `needs`, `sha256sum -c`, состав), checkout с полной
историей, JDK 21 и Node 24.14.0 в build, `--repo`/`GH_TOKEN` в publish, serialization по
тегу, негативные проверки версии, чек-сумма из каталога zip (в файле только basename),
проверка JRE на урезанном runtime (Ruling 8), порядок выпуска и CI-гейт (Ruling 9).
Не принято: требование проверять успешный CI на коммите внутри workflow (Ruling 9: вне
скоупа, предусловие владельца); автовосстановление частичного draft (Ruling 10);
именованный дистрибутив JRE не проверен, но оговорка в README есть. Параметризованный
`workflow_dispatch` оставлен: это единственный способ пробного запуска без тега (Ruling 4).
