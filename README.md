# LT Verdict

LT Verdict — платформа детерминированного анализа результатов нагрузочного
тестирования и формирования проверяемого вердикта.

## Статус

Принят local-first baseline v0.6. Slice 0 завершён и отмечен тегом `stage-0`.
Slice 1 реализован как candidate и готов к review; milestone gate остаётся
pending до зелёных runtime/performance jobs.

Первая часть Slices 8–9 добавляет открытие сохранённых analyses, графики
нагрузки и JSON/HTML export через UI и CLI. Полный MVP остаётся в разработке.

Resource snapshot добавляет статистики аппаратных метрик и совместные оконные
бизнес-/ресурсные SLA. По явному плану доступны эпизоды median/MAD,
Spearman/partial rank correlation и сравнение окон двух прогонов.
Capacity доступен по явным ступеням и SLA с generator guards; автоматический
поиск knee не реализован. Статистическая неопределённость raw-оценок
пока не оценивается (`NOT_ESTIMATED`).

Источники реализованы: PromQL/VictoriaMetrics/Grafana proxy, InfluxQL,
OpenSearch и PostgreSQL pre/post. Поддерживаются несколько источников,
ручной импорт и offline replay. Живая проверка PostgreSQL 16.15 и pg_profile 4.8
прошла на синтетике; PostgreSQL 15, TLS и внешние CI gates остаются непроверенными.

## Быстрый запуск

Подготовка к отдельной приёмке добавляет advisory AI через ModelStudio, Jenkins
workflow, сохранённую аналитику, Grafana evidence и Confluence-ready export.
Локальная реализация и проверки не означают завершённую сквозную приёмку.
Порядок проверки и ограничения: [checklist](docs/mvp-acceptance-checklist.md).

Нужны JDK 21 и Node.js 24.14.0.

```powershell
.\gradlew.bat installDist
.\build\install\ltv\bin\ltv.bat ui
```

Linux использует `./gradlew installDist` и
`./build/install/ltv/bin/ltv ui`. Полный flow, supported formats, policy и
ошибки описаны в [руководстве локального анализа](docs/user/slice-1-local-analysis.md).

## Документация

- [Историческое краткое резюме (до v0.6)](exec-summary-lt-verdict.pdf)
- [Нормативный PRC/PRD v0.6](lt-verdict-prc-prd-v0.6.md)
- [Текущий план v0.6](docs/development-plan-v0.6.md)
- [Milestone report Stage 0 / Slice 0](docs/milestones/stage-0.md)
- [Milestone report Stage 1 / Slice 1](docs/milestones/stage-1.md)
- [Руководство локального анализа Slice 1](docs/user/slice-1-local-analysis.md)
- [Онлайн-источники и offline replay](docs/user/online-sources.md)
- [Advisory AI: prerequisites и настройка ModelStudio](docs/user/advisory-ai.md)
- [Jenkins, Grafana и Confluence-ready output](docs/user/jenkins-and-reports.md)
- [Подготовка нагрузочного теста и onboarding skill](docs/user/test-onboarding.md)
- [Сохранённая аналитика и exports](docs/user/saved-analytics.md)
- [Opt-in подготовка OpenSearch correlation](docs/user/opensearch-correlation-preparation.md)
- [Checklist отдельной приёмки MVP](docs/mvp-acceptance-checklist.md)
- [ADR 0007 — opt-in онлайн-источники](docs/adr/0007-opt-in-online-sources.md)
- [ADR 0008 — PostgreSQL pre/post capture](docs/adr/0008-postgresql-pre-post-capture.md)
- [Архитектура локального runtime Slice 1](docs/architecture/slice-1-local-runtime.md)
- [ADR 0001 — публичные контракты Slice 0](docs/adr/0001-slice-0-public-contracts.md)
- [ADR 0005 — resource snapshot и оконные SLA](docs/adr/0005-resource-window-sla.md)
- [Дизайн корреляционного среза](docs/superpowers/specs/2026-09-05-load-resource-correlation-design.md)
- [ADR 0006 — ограниченная корреляция](docs/adr/0006-bounded-load-resource-correlation.md)
- [Выбранные и отложенные методы статистического анализа](docs/statistical-method-roadmap.md)
- [Методика проверки статанализа v1 и Applicability](docs/statistical-validation-methodology-v1.md)
- [Результаты проверки статанализа v1](docs/statistical-validation-results-v1.md)
- [Согласованный дизайн capacity](docs/superpowers/specs/2026-09-06-capacity-design.md)
- [Утверждённый дизайн Slice 1](docs/superpowers/specs/2026-08-31-slice-1-local-usable-shell-design.md)
- [План реализации Slice 1](docs/superpowers/plans/2026-08-31-slice-1-local-usable-shell.md)
- [План локального просмотра и экспорта](docs/superpowers/plans/2026-09-05-local-review-pilot.md)
- [Уточнения local-first MVP](docs/superpowers/specs/2026-08-26-v06-local-mvp-delta-design.md)
- [Alignment review v0.6](docs/prc-v0.6-alignment-review.md)
- [Исторический PRC v0.5](prc-lt-verdict-v0.5.md)
- [Исторический PRC v0.4](prc-lt-verdict-v0.4.md)
- [Протокол решений](docs/decisions-2026-07-20.md)
- [Регламент разработки](docs/development-process.md)
- [Дизайн регламента для AI-агентов](docs/superpowers/specs/2026-08-10-project-development-governance-design.md)
- [Анкета инфраструктуры](docs/admin-questionnaire.md)

## Разработка

Перед изменениями прочитайте [AGENTS.md](AGENTS.md) и
[регламент разработки](docs/development-process.md). Пользовательски заметные
изменения фиксируются в [CHANGELOG.md](CHANGELOG.md).
