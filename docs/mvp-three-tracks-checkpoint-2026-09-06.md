# MVP: checkpoint трёх параллельных треков

Дата: 2026-09-06. Статус: промежуточный, не закрытие MVP gate.

## Реализовано

- Correlation selector: MBB `B=999`, blocks `10/20`, полный lag-max,
  max-p и один Holm по declared family. Raw evidence и SLA не переписываются.
- Версия `load-resource-diagnostics` в analysis identity: `2`; остальные
  модули остаются версии `1`, чтобы не менять кэш несвязанных analyses.
- Baseline conditions: три состояния, персистентная привязка к точной паре
  analyses/окон, API и UI; без повторного analysis и изменения verdict.
- AI: подготовка evidence, валидация output/references, отдельное хранилище
  advice, контракт Qwen `0.21.1`. Исполняющего production runner пока нет;
  AI не подключён к API/UI через заглушку.

## Проверки

- Общий focused Gradle запуск: `BUILD SUCCESSFUL`, 45 s, 64 tests,
  failures/errors/skipped `0`.
- Классы: `AdvisoryAiTest`, `AnalysisIdentityDiagnosticVersionTest`,
  `BaselineComparisonTest`, `CorrelationHeadlineSelectionTest`,
  `DiagnosticAnalysisTest`, `RunBundleStoreTest`, `LocalApiTest`.
- UI build, `npm run lint`, `npm run test:contracts`: PASS.
- `npm run e2e -- baseline.spec.ts`: 3 PASS / 1 FAIL. Причина: новый тест
  использует `current.analysis_id`, но не сохраняет результат `analyze` в
  `const current`. Запрошено разрешение на однострочную тестовую правку.
- Identity regression сначала подтвердил version `1` вместо `2`, затем
  прошёл после точечного обновления версии.
- Изолированные fake probe и ровно один live OpenRouter request: PASS.
  Модель `deepseek/deepseek-v4-flash-0731`, 12.906 s, 3365 tokens total.
  Секреты, raw ответ и prompt в отчёт не включены. Детали:
  [sanitized probe](advisory-ai-qwen-0.21.1-probe.md).

Команда focused JVM проверки:

```powershell
.\gradlew.bat --offline --no-daemon test -x npmCi --tests '*CorrelationHeadlineSelectionTest' --tests '*DiagnosticAnalysisTest' --tests '*AnalysisIdentityDiagnosticVersionTest' --tests '*BaselineComparisonTest' --tests '*RunBundleStoreTest' --tests '*LocalApiTest' --tests '*AdvisoryAiTest'
```

## Ограничения и следующий шаг

- Принятые 7.7–13.2% шумных отчётов относятся к исходному NumPy experiment.
  JVM использует отдельно версионированный `java.util.Random`; полный
  статистический прогон JVM не выполнялся. Оптимизация отложена пользователем.
- Selector: одна window/outcome/grid, 30–240 cells, lag <= 10,
  family <= 16, <= 150 млн cell-products; вне границ явный `UNAVAILABLE`.
- AI требует утверждённого digest-pinned runtime image и concrete launcher
  с проверенной endpoint-only relay topology. Probe image не утверждён как
  production dependency. Второй live request не разрешён и не выполнялся.
- Report-only: чтение advice `manifest.json` пока не имеет отдельного
  size limit перед `Files.readAllBytes`; это требует исправления перед
  production AI, но в этом checkpoint не исправлялось.
- Общий regression suite, полная браузерная suite, финальное code review и
  milestone gate после интеграции не выполнены. Focused GREEN их не заменяет.
- Jenkins не затрагивался. Коммиты, merge/push и очистка пользовательских
  изменений не выполнялись.

## Обновление после разрешённой правки E2E

Однострочная fixture-правка применена с разрешения пользователя.
Повтор `npm run e2e -- baseline.spec.ts`: exit 0, **4 PASS**, 14.8 s.
Предыдущий E2E FAIL выше оставлен как история проверки и больше не блокирует
этот focused checkpoint. Production baseline-код не менялся при исправлении.
Итог: 64 focused JVM tests PASS, 4 baseline E2E PASS, UI build/lint/contracts PASS.
Оставшиеся ограничения AI runner и непроведённый полный milestone gate сохраняются.
