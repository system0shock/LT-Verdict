# Оставшиеся источники MVP

Статус: границы согласованы пользователем 2026-09-05.
База: `d3def07`; isolated worktree, ветка `feat/remaining-sources`.

## Результат и границы

Завершить acquisition InfluxDB, PostgreSQL и OpenSearch 2.6 в CLI и Web UI.
Несколько источников связываются с одним анализом. Результаты сохраняются
атомарно в RunBundle; ручной импорт и offline replay не требуют подключения.
Отказ источника не отменяет доступный load analysis и не становится PASS.

Используем существующие SourceHttp governor, jobs, resource snapshot validator,
immutable store и JSON/CSV библиотеки. Не создаём connector framework.
Табличные снимки и события сохраняются как versioned context artifacts, не как
фиктивные ресурсные метрики. Capacity, новые методы статистики, ИИ, Jenkins и
адаптация запросов к боевому стенду не входят в эту работу.

## Поставки

1. InfluxDB: InfluxQL GET `/query`, включая v1-compatible API с DBRP mapping.
   Explicit database и metric mappings; результат в `resource-snapshot.v1`.
   Flux, InfluxDB 3 SQL и discovery не входят в эту поставку.
2. OpenSearch 2.6: фиксированный read-only search с server-side aggregations;
   total errors, rate, time distribution, services/types, first/last occurrence,
   ограниченные samples и source links. Timeout, shard failures и неполные
   buckets отражаются в coverage. Без RCA и новой автоматической корреляции.
3. PostgreSQL: отдельный pre capture до нагрузки и post после неё; allowlisted
   tables со stable key и limits, schema/configuration, pg_stat_statements и
   pg_profile artifacts. Нет pre — нет достоверной delta. Reset/неполнота
   понижают coverage, не меняют load verdict. Не вызываем reset/sample mutation
   функций. HTML хранится как недоверенный download, не выполняется в UI origin.
4. Общая интеграция: несколько источников, импорт versioned context artifacts,
   просмотр результатов, сохранение и replay, regression tests и документация.

Каждая поставка имеет отдельный проверяемый план. Общие контракты изменяются
последовательно; декодеры с независимыми файлами допускают параллельную работу.
Новые публичные поля и единственная необходимая JDBC dependency фиксируются
в соответствующем плане до production-кода.

## Безопасность и корректность

Credentials остаются environment references backend; browser выбирает только
настроенные profiles. Read-only роли обязательны; текст SELECT сам по себе
не является защитой от побочных эффектов функций. HTTP redirects запрещены,
TLS verification включена, origin budgets общие. Ограничены ответы, строки,
series, cells и общий размер acquisition. Cancellation освобождает ресурсы.

Пропуски не заполняются нулями. Метрики имеют явные units, aggregation и
временную сетку. Для InfluxQL timestamp обозначает левую границу ячейки;
для уже реализованного PromQL сохраняется right-boundary mapping.
Данные вне окна, duplicate/off-grid timestamps и неоднозначные series не
принимаются молча. Provenance не публикует credentials или raw error bodies.

## Приёмка

- Known fixtures дают вручную рассчитанные metric cells, error aggregates и
  pre/post deltas; отсутствующие/reset/truncated данные явно неполны.
- CLI и Web UI проходят acquire/import → persist → analyze → reload.
- Повторный анализ сохранённых данных не выполняет сетевые запросы.
- Секреты не попадают в artifacts, сообщения и browser; HTML остаётся inert.
- Старые load-only, SLA, baseline и PromQL tests зелёные.
- Реальные HTTP fixtures проверяют протокол; для PostgreSQL нужен отдельный
  настоящий тестовый сервер. Непроведённая проверка не считается успешной.

## Основания

[Первая поставка](2026-09-05-online-sources-design.md),
[v0.6 delta, разделы 13 и 15](2026-08-26-v06-local-mvp-delta-design.md),
[InfluxQL HTTP API](https://docs.influxdata.com/influxdb/v1/api/query/),
[InfluxDB 2 compatibility](https://docs.influxdata.com/influxdb/v2/query-data/execute-queries/influx-api/).
