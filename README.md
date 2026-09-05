# LT Verdict

LT Verdict — платформа детерминированного анализа результатов нагрузочного
тестирования и формирования проверяемого вердикта.

## Статус

Принят local-first baseline v0.6. Slice 0 завершён и отмечен тегом `stage-0`.
Slice 1 реализован как candidate и готов к review; milestone gate остаётся
pending до зелёных runtime/performance jobs.

Первая часть Slices 8–9 добавляет открытие сохранённых analyses, графики
нагрузки и JSON/HTML export через UI и CLI. Полный MVP остаётся в разработке.

## Быстрый запуск

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
- [Архитектура локального runtime Slice 1](docs/architecture/slice-1-local-runtime.md)
- [ADR 0001 — публичные контракты Slice 0](docs/adr/0001-slice-0-public-contracts.md)
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
