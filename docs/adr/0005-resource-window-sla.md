# ADR 0005: Resource snapshot и совместные оконные SLA

Статус: Accepted, 2026-09-05.

## Решение

Первая аналитическая поставка использует existing Kotlin core, без production
dependencies. Optional `resource-snapshot.v1` содержит load SHA-256, UTC grid,
series, windows, explicit resource rules и ограниченный provenance.
Контракт и численные определения: [design](../superpowers/specs/2026-09-05-resource-statistics-design.md).

`policy.v1` остаётся неизменным: в enriched analysis его бизнес-правила проверяются
на каждом evaluation window вместе с resource SLA. Membership по sample start,
latency без обрезания, throughput denominator — полная длительность окна.
Ресурсные правила имеют effect `sla` либо `diagnostic`; только первые обязательны.
Недостаток необходимых данных имеет приоритет NO_VERDICT над наблюдаемым FAIL;
нет обязательных правил — NO_POLICY. Findings сохраняют наблюдаемые нарушения
даже при общем NO_VERDICT. Correlation не является основанием SLA verdict.

Обогащённая identity включает canonical semantic snapshot/config hash, версии
алгоритмов и limits. Provenance исключён из semantic hash. Hash считается по
canonical bytes ADR 0003: ключи объектов и `id` рядов сортируются по возрастанию
UTF-16 code units, числа пишутся canonical decimal (точное значение без exponent,
незначащих нулей и знака нуля, `-0.0` даёт `0`), строки пишутся UTF-8, не-ASCII
не экранируется, отсутствующие `windows` и `rules` равны пустым спискам, а
`labels` ряда без меток равен `{}`.
Клиент, воспроизводящий hash сам, обязан совпасть с ядром; исходный файл
сохраняется в immutable bundle и защищён manifest. Cache с тем же semantic
hash вправе вернуть уже сохранённый эквивалентный analysis с исходным provenance.
Без snapshot старые identity/result bytes не меняются. Existing evidence slots
`analysis-result.v1` принимают resource statistics и оконные policy checks.

## Следствия

CLI и multipart API получают optional resources input; UI и exports показывают
общие окна, статистики и SLA. Адаптеры VM/Grafana, correlation и capacity bounds
не входят в эту поставку. Не вводим policy.v2, отдельный сервис или storage layer.
