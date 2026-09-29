# ADR 0007 — opt-in онлайн-источники

Дата: 2026-09-05. Статус: Accepted.

## Решение

Расширяем только запрет исходящей сети ADR 0002: явный локальный connections
file разрешает read-only acquisition до pure AnalysisService. Без файла
приложение остаётся offline. Новых production dependencies нет (JDK HttpClient).

Контракты и caps зафиксированы до production code в
[implementation plan](../superpowers/plans/2026-09-05-online-sources.md).
UI выбирает только backend profile ID; env credentials не передаются в браузер.
Redirects отключены, TLS проверяется, credentialed HTTP требует explicit opt-in.
Один governor на backend разделяет origin budget между jobs, profiles и retries.

Нормализованный snapshot проходит существующий validator; all-null значения
сохраняют отсутствие данных и не превращаются в успешный SLA. Raw responses и
source-acquisition.json входят в существующий atomic immutable manifest.
Hash acquisition входит в identity; offline identity не меняется.

## Последствия

Повторное открытие и offline replay не требуют сети. Корреляционный план
привязан к уже полученному snapshot hash: сначала acquisition/download, затем
offline correlation. Сначала direct Prometheus/VM и Grafana datasource proxy;
framework, dashboard discovery и автоматическая адаптация запросов не нужны.
Каждый mapping ожидает одну серию; множественный ответ не агрегируется неявно.
