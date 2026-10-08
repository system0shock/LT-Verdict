- Версия `kotlin-reflect` в дистрибутиве выровнена с `kotlin-stdlib`: 2.4.10 вместо
  2.3.21, которую транзитивно тянул Ktor 3.5.2 (ограничение зависимости в
  `build.gradle.kts`, обновлены `gradle.lockfile` и `gradle/verification-metadata.xml`).
  Поведение CLI, API и схемы не менялись.
