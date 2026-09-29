# Подготовка MVP к отдельной приёмке

Рабочее дерево: `.worktrees/local-baseline-comparison`, ветка `feat/remaining-sources`.
Статус: локальная подготовка завершена в описанных ниже границах; MVP ещё не принят.

## Подготовленные возможности

| Область | Результат | Граница |
| --- | --- | --- |
| Advisory AI | Production ModelStudio/Qwen runner, consent, jobs/status/cancel, сохранённый advice и reload | Отдельный artifact; deterministic verdict не меняется. Новые model calls не запускались. |
| Аналитика | N-run dynamics, transaction/baseline deltas, ручное скрытие строк, OpenSearch markers, JVM/OpenShift coverage | Только сохранённые данные. Отсутствующие metadata остаются N/A. |
| Сравнение графиков | Baseline/current на общей шкале относительного времени, без заполнения gaps | До 500 bins на run; это не подтверждение одинаковых условий и не stage alignment. |
| Jenkins | Allowlisted profiles, durable intent, queue/build/reconciliation, проверка и импорт artifact | Неизвестный исход не вызывает автоматический повторный trigger. Живая job не запускалась. |
| Grafana | Source link и PNG по явному запросу через общий transport governor | Отказ render сохраняет ссылку и локальные графики. |
| Отчёты | JSON/HTML/AsciiDoc, статический SVG и Confluence-ready XHTML; bounded history exports | Confluence REST — fail-soft skeleton, конкретная стратегия Cloud/DC не настроена. |
| Onboarding | Локальный skill, allowlisted/secret-filtered view, audit, reviewable metadata proposal, exact-hash apply | Только новый внешний manifest. Модель и код тестового repository не запускаются. |
| Поставка | Runtime scripts, prompt/schema, onboarding tool/skill и инструкции входят в installDist | Credentials, Docker image и Qwen package задаются отдельно. |

N-run history читается только по явному Refresh, не более 1000 analyses,
4096 directory entries и 16 MiB metadata. Проверяются manifest и hashes
сохранённых документов; current/baseline проходят полную проверку RunBundle.
Усечённая история не гарантирует глобально последние N прогонов. Форматированные
analytics exports содержат N-run table; transactions доступны в UI/JSON.
OpenSearch correlation готовится opt-in offline CLI; отдельного UI action нет.

JVM/OpenShift packs используют распознанные canonical metric names, coverage и
существующие explicit-threshold findings. Они не назначают универсальные пороги
и не считают отсутствующую метрику признаком здоровья.

## Локальные проверки

Журнал команд: `.superpowers/sdd/2026-09-21-mvp-acceptance-readiness/`.
Общий `check installDist -x npmCi --offline --no-daemon --no-parallel` прошёл.

- JVM: 381 tests, 0 failures/errors, 9 skipped; пропуски относятся к optional/manual gates и platform-dependent сценариям.
- Kotlin lint, UI build/typecheck/ESLint и contract checks прошли. Slice0 verification прошла.
- Python: 9 проверок onboarding/fixture tools прошли.
- Node relay contract и Docker fake preflight прошли без ModelStudio requests.
  Runtime проверен под Windows PowerShell 5.1 с точным ограниченным окружением приложения; hung-process regression прошёл, оставшихся owned Docker resources нет.
- Browser regression: все 47 локальных сценариев прошли (38.8s), включая accessibility, exports, reload и отказ интеграций.
- Performance smoke установленного CLI: 100000 JTL rows, heap 256 MiB,
  1.34 s, exit 0; `VALID`, 100000 samples, 5000 errors, `NO_POLICY`.
  Это однократный небольшой smoke, не сравнимый с полным Linux performance gate.
- Установленный CLI SVG smoke: корректный XML, 17 bins и три metric series из сохранённого прогона.
- Локальные structural/link checks документации и scoped secret scan прошли; при подготовке коммита официальный markdownlint проверен отдельно.
- Существующие pilot/statistical artifacts сохранены. `clean`, staging,
  commit, push, merge и внешняя публикация не выполнялись.

## Что остаётся отдельной приёмке

1. Сквозной путь с настоящими Jenkins и источниками, рестартами и отказами.
2. Семантическая AI-оценка: прошлый пилот остановлен после 20 запросов;
   18 ответов структурно валидны, но screening выявил ошибки содержания.
   Изменение prompt не является доказательством улучшения качества.
3. PostgreSQL 15/TLS и внешние CI/runtime/performance gates, которые локальная подготовка не заменяет.
4. Проверка применимости metric packs к реальным именам/единицам метрик стенда.

Автоматическая AI-адаптация произвольного тестового кода не объявляется готовой:
onboarding поставляется в ограниченном manifest-only режиме. Confluence publish
требует явно выбранной site/page/auth strategy. Это ограничения подготовленной
поставки, а не успешно пройденные проверки приёмки.

Сценарии и порядок: [checklist](mvp-acceptance-checklist.md).
Настройка: [AI](user/advisory-ai.md), [интеграции](user/jenkins-and-reports.md),
[onboarding](user/test-onboarding.md).
