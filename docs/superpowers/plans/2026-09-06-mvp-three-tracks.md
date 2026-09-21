# MVP: три параллельных трека

Дата: 2026-09-06. Статус: execution in progress.
Пользователь согласовал запуск ИИ-анализа, интеграции correlation selector
и подтверждения условий baseline/current. Jenkins исключён из активной очереди.

## Общие ограничения

- Минимальные изменения существующей архитектуры; без новых production
  dependencies без явного обоснования в плане соответствующего трека.
- Deterministic SLA/verdict не зависит от AI или correlation selector.
- Старые analyses и статистические artifacts не перезаписываются.
- Принятые 7.7-13.2% относятся к development-эксперименту корреляций;
  параметры не оптимизируются в этой поставке. Two-run p50 и genuine partial
  не получают неподтверждённую гарантию из этого эксперимента.
- Qwen Code0.21.1 разрешён вместо GigaCode; глобальный0.21.5 не заменяется.
- Credentials не входят в evidence/advice/logs; реальный model endpoint
  и OS isolation проверяются отдельно от fake-runner tests.
- Работа в существующем worktree `feat/remaining-sources`; ранее накопленные
  изменения пользователя сохраняются. Без commit, staging, push или clean.
- Gradle выполняет только root, последовательно. UI/API shared edits имеют
  одного владельца в каждый момент времени.

## Владение и результаты

| Трек | Владение | Проверяемый результат |
| --- | --- | --- |
| AI | Новые `ai` production/test files, advice contracts/prompt, собственные spec/plan/ADR | Bounded evidence, проверка advice/references, отдельное atomic storage с hash binding, runner failure handling и изолированный actual CLI probe |
| Correlation | Diagnostic core, новый selector/test, diagnostic contracts и собственные spec/plan/ADR | Принятая MBB/lag-max/max-p/Holm policy в production path, evidence/limitations и unchanged SLA |
| Baseline | BaselineComparison, baseline storage/contracts/tests; временно LocalApi и UI для confirmation | Явное confirmed/not-confirmed/unknown для конкретной пары, API/UI, сохранение/reload без утечки на другую пару |
| Root | Общая интеграция после release файлов, AnalysisService/identity при необходимости, общие docs, последовательные проверки/review | Согласованные контракты и совместная работа трёх треков |

Каждый исполнитель фиксирует точные callable interfaces, новые public
contracts и scoped implementation plan перед production edits. Общие файлы
не изменяются другим агентом без явной передачи владения.

## Порядок исполнения

1. Сохранить pre-existing diff и исходные snapshots; выполнить baseline tests.
2. Зафиксировать scoped design каждого трека и написать RED tests.
3. Root последовательно запускает RED; исполнители реализуют свой scope.
4. Root запускает targeted GREEN, затем интегрирует released shared files.
5. Проверить UI/API workflow, policy identity/reload и отказные сценарии.
6. Выполнить ограниченный независимый review task diffs и общий final review;
   устранить blocking findings без расширения scope.
7. Обновить пользовательские docs/CHANGELOG/handoff и сообщить реальные
   результаты, ограничения и оставшиеся gates. Это не объявление MVP готовым.

## Критерии приёмки

- Baseline confirmation переживает reload, не подтверждает другую пару;
  false/unknown не превращаются в true. Формулы сравнения не меняются.
- Selector сохраняет raw coefficients/lag/support, не использует только
  отобранных кандидатов как Holm family и не меняет SLA.
- Идентичность нового diagnostic result исключает ошибочный cache hit v1.
- AI advice связан с проверенным analysis/manifest; неизвестные evidence refs
  и oversized/invalid JSON отвергаются; ошибки AI fail-soft.
- Actual Qwen capability probe не подменяется успешным fake-runner unit test;
  неподтверждённая runtime isolation даёт честный UNAVAILABLE.
- Targeted JVM tests, UI typecheck/lint/contracts/build и соответствующие
  browser workflows проходят; большие statistical corpora не перезапускаются.

## Baseline

`./gradlew.bat --no-daemon test -x npmCi --rerun-tasks`:
BUILD SUCCESSFUL, 2m4s, включая UI build. Первый sandbox-запуск не получил
доступ к Gradle wrapper cache; разрешённый повтор выполнил проверки.
Известное предупреждение Kotlin в старом `UsefulnessValidationTest` оставлено
вне scope. Это исходный baseline, не доказательство новых функций.

Локальный ledger и исходные snapshots:
`.superpowers/sdd/2026-09-06-mvp-three-tracks/`.
