# ADR 0008 — PostgreSQL pre/post capture

Статус: принято в рамках согласованного блока источников, 2026-09-05.

## Контекст

После нагрузочного теста нельзя восстановить предыдущее состояние таблиц или
счётчиков. PostgreSQL context должен опираться на действительно снятые pre/post,
а не на две выборки, выполненные подряд после нагрузки.

## Решение

- Отдельные команды/UI-действия снимают pre до нагрузки и post после неё.
  Pre скачивается как immutable файл, затем передаётся при post/анализе;
  отдельное хранилище незавершённых capture-сессий не вводится.
- `postgres-phase.v1` содержит времена capture, secret-free database identity,
  revision hash профиля, schema/configuration, allowlisted tables и direct
  pg_stat_statements. Post связывается с SHA-256 canonical pre.
- `postgres-context.v1` получается одной pure функцией из validated phases
  и load window. Online и manual import используют один алгоритм.
- Несовпадение binding, поздний pre, ранний post, truncation, reset и регрессия
  счётчиков дают явную неполноту. Точные DML-дельты требуют stable non-null
  unique key; без ключа допустима только разница количества строк.
- pg_profile — supplementary artifact; report HTML сохраняется с SHA-256 и
  скачивается как недоверенный файл, не выполняется в origin приложения.
  Reset/sample/management-функции connector не вызывает.
- JDBC использует фиксированный SQL catalogue, read-only role/transaction,
  explicit identifier allowlists, TLS verify-full и bounded timeouts/rows/bytes.
  Пользователь не передаёт SQL, JDBC URL, properties или class names через UI.
- Capture требует отдельной read-only роли, не используемой нагрузкой.
  Её `userid` исключается из pg_stat_statements, чтобы SQL самого connector
  не становился нагрузочной delta. Измеренная конфигурация явно содержит
  `lt_verdict.excluded_statement_userid`; счётчики остальных ролей сохраняются.
- Wire materialization ограничена fixed pgJDBC `maxResultBuffer`16 MiB.
  SQL возвращает только bounded prefix ячейки/report (limit+1 byte); sentinel
  позволяет отметить превышение, не передавая неограниченный value в JVM.
  Fetch sizing согласуется с этими limits; client-side validation сохраняется.
- Добавляется только `org.postgresql:postgresql:42.7.13`, с lockfile и strict
  checksum verification. Connection pool, ORM и Testcontainers не нужны.
  Его declared runtime dependency `org.checkerframework:checker-qual:3.55.1`
  также закреплена; optional Waffle/JNA не подключается.

## Альтернативы и последствия

Два снимка после теста не измеряют его последствия и отвергнуты. Logical
decoding требует другого workflow и не нужен для согласованного pre/post.
JDBC driver заменяет самостоятельную реализацию PostgreSQL protocol.

Capture требует действий до нагрузки; без pre отчёт остаётся доступным, но
не выдаёт недостоверную delta. Unit fixtures не заменяют реальный PostgreSQL
integration gate. Поддержка pg_profile report signatures и sample coverage
проверяется отдельно и может быть явно DEGRADED.

[План и точные limits](../superpowers/plans/archive/2026-09-05-postgresql-source.md),
[pgJDBC version](https://jdbc.postgresql.org/download/),
[PostgreSQL statement statistics](https://www.postgresql.org/docs/15/pgstatstatements.html).
