# ADR 0006: Ограниченная диагностическая корреляция

Статус: Proposed, 2026-09-05; ждёт согласования письменной спецификации.

## Решение

Добавить opt-in `correlation-plan.v1` поверх существующих resource snapshot и
evaluation windows. Явные пары/контроли/topology declarations ограничивают
поиск; ядро строит UTC load series из исходных samples, partial rank
association, bounded lag profile и candidate change ordering.

Correlation evidence не меняет SLA verdict. Uncertainty — только явно
ограниченная block-permutation approximation с учётом поиска лага и Holm
correction; без объявленных предпосылок p-values отсутствуют. Никаких causal
claims или HIGH confidence по одному коэффициенту.

Все влияющие на результат настройки входят в identity; raw plan сохраняется
immutable. Без plan прежние identity/results неизменны. Новых dependencies,
runtime, storage layer или pipeline registry нет.

## Альтернативы и последствия

Сырая корреляция не покрывает load confounding. Отдельный statistical service
добавляет runtime и не нужен для ограниченных вычислений. Полная incident
synthesis и metric-pack discovery остаются отдельной работой.

Первая поставка связывает overall load и явно выбранные resource series.
Transaction-specific и resource/resource edges пока не реализуются.
Приближённая оценка неопределённости не гарантирует calibration для arbitrary
nonstationary workloads; ограничения видны пользователю вместе с результатом.

Контракт, caps, алгоритмы и приёмка:
[correlation design](../superpowers/specs/2026-09-05-load-resource-correlation-design.md).
