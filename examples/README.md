# Примеры LT Verdict

[Базовый сценарий](basic/run.md) показывает анализ вымышленного микросервиса
`catalog`, обслуживающего `GET /items`, с CPU и RSS из Prometheus.
В каталоге `basic/` есть все входы для воспроизводимого офлайн-анализа и профиль
для подключения к вашему Prometheus.

| Файл | Назначение |
| --- | --- |
| [basic/input.jtl](basic/input.jtl) | Синтетический JMeter CSV: разгон, плато, снижение нагрузки |
| [basic/connections.json](basic/connections.json) | Один профиль Prometheus, запросы CPU и RSS, два ресурсных SLA |
| [basic/source-request.json](basic/source-request.json) | Окно онлайн-сбора: 2026-01-01 00:00:00–00:01:50 UTC, шаг 10 секунд |
| [basic/policy.json](basic/policy.json) | Правила p95, p99, доли ошибок и throughput для `GET /items` |
| [basic/resource-snapshot.json](basic/resource-snapshot.json) | Синтетические ряды CPU/RSS, ресурсные правила и окно `steady` |
| [basic/correlation-plan.json](basic/correlation-plan.json) | Проверка связи CPU с p95 в окне `steady` |
| [basic/pod-view.json](basic/pod-view.json) | Один под `catalog-01`, контейнер `app`, две строки метрик |
| [basic/load-stages.json](basic/load-stages.json) | Стадии: 0–40 секунд разгон, 40–100 плато, 100–120 снижение |
| [basic/run.md](basic/run.md) | Подготовка, команды по порядку и расположение результатов |

Сценарий использует JTL из `fixtures/stages/ramp-steady-rampdown.jtl`.
Ресурсные значения и pod-view иллюстративные: это не запись реального стенда.
Хэши в снимке, плане и pod-view привязаны к файлам этого примера.
Тест `BasicExampleTest` читает каждый JSON существующим парсером и проверяет
согласованность привязок. Для JTL закреплены переводы строк LF: изменение байтов
нагрузочного входа требует обновления его SHA-256 в связанных документах.

Описание форматов:
[онлайн-источники](../docs/user/online-sources.md),
[policy.v1](../docs/contracts/policy/v1/policy.schema.json),
[resource-snapshot.v1](../docs/contracts/resources/v1/resource-snapshot.schema.json),
[correlation-plan.v1](../docs/contracts/diagnostics/v1/correlation-plan.schema.json),
[pod-view](../docs/user/pod-view.md),
[load-stages.v1](../docs/contracts/stages/v1/load-stages.schema.json).
