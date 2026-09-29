# ADR 0006: Ограниченная диагностическая корреляция

Статус: Accepted with amendment, 2026-09-05, по итогам отбора методов Astra.

## Решение

Добавить opt-in `correlation-plan.v1` поверх существующих resource snapshot и
evaluation windows. Явные пары/контроли/topology declarations ограничивают
поиск; ядро строит UTC load series из исходных samples, partial rank
association, bounded lag profile и эпизоды относительно явного reference-окна.

Correlation evidence не меняет SLA verdict. Uncertainty — `NOT_ESTIMATED`;
block-permutation/интервалы/Holm отложены до отдельной калибровки. Никаких causal
claims или HIGH confidence по одному коэффициенту. Аномальные эпизоды требуют
explicit reference, абсолютного эффекта и длительности; двухпрогонное сравнение
расширяется до явно выбранных окон без утверждения воспроизводимой регрессии.

Все влияющие на результат настройки входят в identity; raw plan сохраняется
immutable. Без plan прежние identity/results неизменны. Новых dependencies,
runtime, storage layer или pipeline registry нет.

`POST /api/jobs` требует `Content-Length`: неизвестная длина возвращает
`411 LENGTH_REQUIRED` до чтения multipart. Это сохраняет общий предел
18 MiB + 64 KiB без дополнительного streaming layer; локальный UI передаёт длину.
JSON Schema проверяет форму; UTF-8 byte limits и привязки проверяет общий validator.

## Альтернативы и последствия

Сырая корреляция не покрывает load confounding. Отдельный statistical service
добавляет runtime и не нужен для ограниченных вычислений. Полная incident
synthesis и metric-pack discovery остаются отдельной работой.

Первая поставка связывает overall load и явно выбранные resource series.
Transaction-specific и resource/resource edges пока не реализуются.
Будущая приближённая оценка неопределённости потребует отдельной calibration
для nonstationary workloads; текущие ограничения видны вместе с результатом.

Контракт, caps, алгоритмы и приёмка:
[correlation design](../superpowers/specs/2026-09-05-load-resource-correlation-design.md).
