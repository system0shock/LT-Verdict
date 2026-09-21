# Подготовка и отдельная приёмка MVP

Статус: пакет локальной подготовки готов. Этот документ не является протоколом успешной приёмки.

## Граница подготовки

Локальные unit/contract/component проверки используют fixtures и локальные fake servers. Сквозной пользовательский прогон с настоящими внешними системами выполняется отдельно по решению пользователя. Не запускать дополнительные model requests, Jenkins jobs или публикацию отчётов при подготовке.

## Воспроизводимая локальная проверка

Сохранить существующие build-артефакты: **не запускать clean** в рабочем каталоге с корпусами приёмки.

```powershell
.\gradlew.bat --offline --no-daemon check installDist -x npmCi --no-parallel
npm --prefix ui run typecheck
npm --prefix ui run lint
npm --prefix ui run test:contracts
python tools/verify_slice0.py
python -m unittest tools.test_verify_slice0 tools.test_generate_jtl tools.test_onboard_test -v
node --test tools/test_advisory_ai_runtime_relay.mjs
powershell -NoProfile -File tools/test_advisory_ai_runtime.ps1
npm --prefix ui run e2e
```

Зависимости должны быть предварительно установлены по lockfiles; offline failure из-за отсутствующего cache не означает дефект продукта. Доступ к Gradle cache и loopback тестовым серверам может требоваться вне sandbox. Не выводить секреты при диагностике конфигурации.

Полный performance gate существует в `.github/workflows/runtime-quality.yml` и `tools/perf/jtl_probe.sh`: Linux, два CPU, 10 млн строк, три измерения. Уменьшенный локальный smoke не подменяет этот gate. Реальные CI результаты фиксируются отдельно, без вывода об успехе по наличию workflow.

## Сценарии отдельной приёмки

| Сценарий | Проверяемый результат |
| --- | --- |
| JMeter/Gatling import → analysis → reload | Сохранённый result воспроизводим; исходные данные и identity проверяются; replay не повторяет external queries. |
| Каждый заявленный online source и файловый fallback | Совпадают аналитические факты; отказ источника оставляет честное coverage и load-only результат. |
| Jenkins trigger → queue → build → artifact | Intent сохранён до POST; неизвестный исход не приводит к повторному trigger; missing artifact означает ожидание, не ложный verdict. |
| Resource/SLA/capacity | Различаются PASS/FAIL/NO_POLICY/NO_VERDICT; ступени и bounds основаны на наблюдённой нагрузке, ограничение генератора не выдаётся за capacity продукта. |
| Baseline/current и история | Подтверждение условий связано с точной парой; history использует локальные сохранённые analyses; нет растяжения времени или ложной гарантии регрессии. |
| AI по явному запуску | Передача данных видна пользователю; output привязан к analysis; ошибки/отмена не меняют SLA; гипотезы и ограничения видны отдельно. |
| Reports, Grafana, publishing | Локальный отчёт остаётся доступным при отказе render/publish; ссылки и содержимое безопасно экранированы. |
| Onboarding skill | Audit/patch не исполняет код тестового репозитория и не меняет его; apply требует отдельного подтверждения точного patch. |
| Перезапуск и отмена | Нет потерянного успешного результата, повторного внешнего действия или зависшей активной задачи. |

Для каждого сценария записать версию сборки, конфигурацию без секретов, hashes входов, ожидаемое/фактическое, команды и результат. Сохранять неудачные случаи. Не подгонять эталоны по ответам.

## Известные ограничения

- Пользователь принял остаточный шум correlation development-repeat 7.7–13.2%; это не переносится автоматически на genuine partial и two-run p50.
- AI пилот остановлен на 20 запросах: 18 структурно валидных ответов, 2 сбоя. Быстрый просмотр выявил ошибки содержания; формальная приёмка и повторяемость не подтверждены.
- Ошибка oracle/evidence review022 требует отдельного исправления версии проверочного набора; замороженный набор не перезаписывать.
- PostgreSQL 15/TLS и внешние runtime/performance gates нельзя считать закрытыми по локальным fixtures.

Текущая реализация и свежие результаты: [отчёт подготовки](mvp-readiness-2026-09-22.md), [передача сессии](session-handoff.md) и ledger плана `2026-09-21-mvp-acceptance-readiness`.
