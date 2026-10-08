# Выравнивание kotlin-reflect с kotlin-stdlib: план

> Для исполнителя: задача малая, один шаг-ограничение в `build.gradle.kts` плюс регенерация lockfile и
> verification-metadata. Шаги с чекбоксами.

**Цель:** `kotlin-reflect` в runtime-дистрибутиве той же версии, что `kotlin-stdlib` (2.4.10), вместо 2.3.21.

**Архитектура:** `kotlin-reflect:2.3.21` приходит транзитивно из `io.ktor:ktor-server-core-jvm:3.5.2` (Ktor 3.5.2 собран
на Kotlin 2.3.21). Плагин Kotlin выравнивает только `kotlin-stdlib` (2.4.10), `kotlin-reflect` не выравнивает.
Минимальный способ: constraint на `kotlin-reflect` в блоке `dependencies` и штатное обновление lockfile.

**Tech Stack:** Gradle 9.5 (dependency locking, dependency verification), Kotlin 2.4.10, Ktor 3.5.2.

## Установленные факты (до изменений)

- `./gradlew dependencyInsight --dependency kotlin-reflect --configuration runtimeClasspath`: единственный путь
  `ktor-server-core-jvm:3.5.2 -> kotlin-reflect:2.3.21`; версия закреплена lockfile ("By constraint: Dependency version
  enforced by Dependency Locking").
- `build/install/ltv/lib` содержит `kotlin-reflect-2.3.21.jar` и `kotlin-stdlib-2.4.10.jar`: то есть расхождение
  попадает в runtime-дистрибутив, это не только инструмент сборки. Значит "записать, что не нужно" не подходит.
- Прямых использований `kotlin.reflect` в `src` нет (grep); reflect нужен только Ktor.
- До изменений `ltv analyze fixtures/slice0/jmeter.jtl` из `build/install/ltv` работает (exit 0).
- `gradle/verification-metadata.xml` проверяет sha256 артефактов: для 2.4.10 нужны новые записи.

## REQUESTED / REQUIRED / NOT REQUIRED / EXPECTED FILES

```text
REQUESTED: найти источник kotlin-reflect 2.3.21 и привести его к версии kotlin-stdlib 2.4.10 минимальным способом
           либо обосновать, что не нужно; проверить installDist и запуск ltv analyze.
REQUIRED TO ACHIEVE IT: constraint kotlin-reflect:2.4.10 в dependencies { constraints { implementation(...) } };
           регенерация gradle.lockfile штатной командой (--write-locks); добавление sha256 для kotlin-reflect 2.4.10
           в gradle/verification-metadata.xml штатной командой (--write-verification-metadata sha256);
           фрагмент changelog.d.
NOT REQUIRED: смена версии Ktor / BOM; exclude kotlin-reflect (нужен Ktor); принудительное выравнивание всех
           org.jetbrains.kotlin:* через платформу (шире); правка ktlint/build-tool classpath (lockfile их не затрагивает
           в runtime); любые изменения src.
EXPECTED FILES TO CHANGE: build.gradle.kts, gradle.lockfile, gradle/verification-metadata.xml,
           changelog.d/kotlin-reflect-version.changed.md, этот план.
```

## Публичные контракты

Формат вывода CLI, коды выхода, схемы: не меняются. Меняется только версия транзитивной библиотеки в
`lib/` дистрибутива (kotlin-reflect 2.3.21 -> 2.4.10). Новая production-зависимость не добавляется: артефакт
`kotlin-reflect` уже в runtime, меняется его версия (constraint, не dependency). Запись об этом здесь же.

## Ruling'и

- Ruling: чинить constraint'ом, а не "не нужно" - kotlin-reflect попадает в runtime-дистрибутив, а расхождение
  reflect < stdlib (`kotlin-reflect` читает метаданные классов, скомпилированных Kotlin 2.4.10; reflect рассчитан на
  свою версию языка и новее не гарантирует) - риск скрытый, цена исправления мала. Выравнивание - профилактическое
  (воспроизводимой поломки с 2.3.21 нет: `ltv analyze` и тесты работают). Цена ошибки: Ktor 3.5.2 собран с reflect
  2.3.21; при регрессии откат - revert всего коммита (constraint, lockfile, verification-metadata), а не удаление
  одного constraint (lockfile сохранил бы 2.4.10). Полный `check` это проверяет, но не гарантирует.
- Ruling: constraint без `because`-кода динамики: версия фиксирована литералом "2.4.10" рядом с версией плагинов
  (`kotlin("jvm") version "2.4.10"`). Динамические версии запрещены, так что `latest`/`+` не используются.
- Ruling: версионный каталог не вводится (в проекте его нет, правило "не добавлять слои").

## Критерии приёмки

1. `dependencyInsight --dependency kotlin-reflect --configuration runtimeClasspath` показывает 2.4.10.
2. `gradle.lockfile`: в `compileClasspath,runtimeClasspath,testCompileClasspath,testRuntimeClasspath` kotlin-reflect 2.4.10;
   остальные строки lockfile без изменений (diff только по kotlin-reflect).
3. `build/install/ltv/lib/kotlin-reflect-2.4.10.jar` есть, 2.3.21 нет.
4. `ltv analyze fixtures/slice0/jmeter.jtl --out-dir <dir>` из дистрибутива: exit 0, `result.json` побайтово равен
   результату до изменения (канонический вывод не меняется).
5. Полный `gradlew check` зелёный, в том числе `ltv ui` серверные тесты (Ktor).

## Задача

- [ ] Шаг 1 (красный): зафиксировать, что `dependencyInsight` показывает 2.3.21 (сделано, см. факты); сохранить
  `result.json` до изменения для сравнения.
- [ ] Шаг 2: в `build.gradle.kts` в `dependencies { ... }` добавить

```kotlin
    constraints {
        implementation("org.jetbrains.kotlin:kotlin-reflect:2.4.10") {
            because("Ktor 3.5.2 pulls kotlin-reflect 2.3.21; keep it equal to kotlin-stdlib 2.4.10")
        }
    }
```

- [ ] Шаг 3: `./gradlew --no-daemon --write-verification-metadata sha256 --update-locks org.jetbrains.kotlin:kotlin-reflect installDist test`
  (селективное обновление lockfile только для kotlin-reflect; метаданные верификации пишутся в том же запуске, иначе
  отсутствующий sha256 для 2.4.10 блокирует резолв при `verify-metadata=true`). Проверить `git diff gradle.lockfile`
  (только строка kotlin-reflect 2.4.10; запись 1.6.10 инструментов сохранена, 2.3.21 уходит из application-конфигураций)
  и `git diff gradle/verification-metadata.xml` (только добавление kotlin-reflect 2.4.10: jar и pom). Затем повторный
  запуск без write-флагов. Записи 2.3.21 в verification-metadata не удалять (удаление вне нужного скоупа).
- [ ] Шаг 4:  `installDist`, критерии 1-4; затем `Invoke-LtvExclusive { ./gradlew --no-daemon clean check installDist }`.
- [ ] Шаг 5: фрагмент `changelog.d/kotlin-reflect-version.changed.md`; Documentation impact: none (README/docs версий
  зависимостей не перечисляют - проверить grep'ом).
- [ ] Шаг 6: просмотр полного diff, проверка документации и секретов (скрипты из CI), стейдж явными путями; коммит `build: align kotlin-reflect with kotlin-stdlib 2.4.10`.
