# LT Verdict

LT Verdict — платформа детерминированного анализа результатов нагрузочного
тестирования и формирования проверяемого вердикта.

## Что умеет сегодня

Сегодня это воспроизводимый SLA-гейт для результатов JMeter (JTL, CSV и XML) и
Gatling (`simulation.log`, текстовый и бинарный). «Проверяемый вердикт» означает
воспроизводимость из байтов входа и политики, а не статистическую обоснованность.

Умеет (код в `main`; проверено на синтетике и в тестах, реальных прогонов и
пользователей кроме владельца пока нет):

- вердикт `PASS`, `FAIL` или `NO_VERDICT` по явной политике `policy.v1`
  (задержки, ошибки, throughput по транзакциям, окнам и сервисам платформы);
  валидность прогона отдельна от вердикта, нехватка данных даёт `NO_VERDICT`, а не `PASS`;
- снимок ресурсов (CPU, память и др.), оконные SLA ресурсов, эпизоды, L0-тренд,
  корреляции по явному плану и ёмкость по ступеням с диагностикой колена;
- источники: Prometheus, VictoriaMetrics (в том числе через Grafana proxy),
  InfluxQL, OpenSearch и PostgreSQL pre/post, ручной импорт и offline replay;
  запуск и сбор артефактов через Jenkins реализованы, живая job не проверялась;
- сравнение с baseline по серии релизов и плечу, история релизов, динамика
  нескольких прогонов, тепловая карта подов;
- отчёты: JSON, HTML, AsciiDoc, Confluence-ready XHTML и SVG-график;
- локальный Web UI (русская оболочка, `?shell=new`) и CLI на одном ядре;
- совет ИИ: отдельный артефакт, вердикт не меняет. Боевой путь по решению владельца
  direct-runner ([ADR 0027](docs/adr/0027-advisory-ai-direct-local-runner.md)); он
  включается только `LT_VERDICT_AI_RUNNER_MODE=local`, без переменной код по-прежнему
  идёт путём Docker и relay (харнесс для экспериментов). С настоящим GigaCode на Linux
  direct-runner не проверялся. Отгружается промпт v1, измеренный v2 не включён.

Чего нет:

- синтеза инцидентов («3–7 инцидентов вместо графиков»): «Обзор» показывает плоский
  список; детерминированный инцидент v0 запланирован;
- статистической обоснованности выводов: сравнение двух прогонов использует
  фиксированные 5 % (на автокоррелированном нуле 36,8 % ложных `CANDIDATE`);
  статистическая приёмка корреляций v1 не пройдена;
- вердикта по окну устойчивого состояния: без окон ресурсов вердикт считается по
  всему прогону, включая разгон;
- baseline в CLI (только в UI);
- многопользовательского и серверного режима: один процесс на каталог данных,
  сервер слушает только `127.0.0.1`;
- выпущенной версии: тега пока нет. Версия есть в сборке (`ltv --version`), zip-дистрибутив
  собирает workflow `release.yml` (см. «Установка из архива»); до первого релиза
  запуск только из сборки (нужны JDK и Node.js).

Подробности границ: [alignment review v0.6](docs/prc-v0.6-alignment-review.md) и
[нормативный PRC/PRD v0.6](lt-verdict-prc-prd-v0.6.md).

## Быстрый старт: analyze, затем report

После сборки (см. «Быстрый запуск» ниже). Сохраните политику в `policy.json`:

```json
{
  "schema_version": "policy.v1",
  "policy_id": "quickstart",
  "defaults": { "sample_floor": 1, "min_samples": 1 },
  "rules": [
    { "id": "overall-p95", "metric": "response_time_p95_ms", "operator": "lte",
      "threshold": 500, "scope": { "kind": "overall" } }
  ]
}
```

Анализ и HTML-отчёт. `ltv` здесь это `./build/install/ltv/bin/ltv` (Windows:
`.\build\install\ltv\bin\ltv.bat`):

```bash
ltv analyze results.jtl --policy policy.json --data-dir data --out-dir out
echo $?        # 0 PASS, 2 FAIL, 3 NO_VERDICT, 4 неверный вход, 5 неверная политика
```

В `out/` появятся `result.json` (`analysis-result.v1`: вердикт, причины, evidence),
`report.html`, `chart.svg`, `summary.txt` и `junit.xml`; stdout по-прежнему несёт тот
же JSON результата. В stderr печатается
`analysis_id=... run_id=...`; по ним формат отчёта выбирается отдельно:
`ltv report <run_id> <analysis_id> --format asciidoc --data-dir data > report.adoc`.
`--format` принимает `json`, `html`, `asciidoc`, `confluence`, `svg`. Без
`--policy` вердикт `NO_POLICY`, метрики считаются. Пример входа:
`fixtures/slice1/jmeter/csv-5.6.3/input.jtl`.

## Статус

Принят local-first baseline v0.6. Slice 0 завершён и отмечен тегом `stage-0`.
Slice 1 реализован как candidate и готов к review; milestone gate остаётся
pending до зелёных runtime/performance jobs. После него в `main` влиты русская
оболочка интерфейса по умолчанию (`?shell=new`), история релизов с baseline по
паре «серия, плечо», платформенные правила политики, контракт `pod-view.v1` и
тепловая карта подов на вкладке «Глубокий анализ», а ИИ-разбор запрашивается без
согласия на отправку.

Первая часть Slices 8–9 добавляет открытие сохранённых analyses, графики
нагрузки и JSON/HTML export через UI и CLI. Полный MVP остаётся в разработке.

Resource snapshot добавляет статистики аппаратных метрик и совместные оконные
бизнес-/ресурсные SLA. По явному плану доступны эпизоды median/MAD,
Spearman/partial rank correlation и сравнение окон двух прогонов.
Capacity доступен по явным ступеням и SLA с generator guards; рядом с ним
выдаётся диагностика колена по p95 ступеней (не откалибровано, не влияет на
вердикт, ADR 0026), откалиброванный `capacity_knee` не реализован. По явному `trend-plan.v1` доступен L0-детектор роста
ресурсных метрик в пределах SLA: это наблюдение с объявленной величиной, а не
статистический вывод и не диагноз. Статистическая неопределённость raw-оценок
пока не оценивается (`NOT_ESTIMATED`). Исключение — выбор главных корреляционных
находок: `correlation_candidate` публикуется только после moving-block bootstrap
p-values с одной поправкой Holm (`mbb-lag-max-holm.v2` по первым разностям рядов;
семья — пара «стадия, исход», до 16 гипотез, 31–1 921 ячейка; вне полосы —
`UNAVAILABLE`).
Статистическая приёмка v1 не пройдена: USEFULNESS FAIL, 8 из 28 конфигураций
по шуму ([результаты](docs/statistical-validation-results-v1.md)). Диапазон шума
корреляций метода v1 по уровням 7.7–13.2% (гейт ≤5% не пройден) измерен в
Python/NumPy development-прогоне на раскрытых seeds; для метода v2 есть только
development-замер H3 (3,2–3,3 % на независимых AR(1)); продуктовый JVM-код
(`java.util.Random`) в них не участвовал и отдельно не калибровался, независимой
приёмки нет
([контракт](docs/contracts/diagnostics/v1/correlation-headline-selection.md)).
Шум сравнения двух прогонов (T02 36.8%, T03 5.4%) остаётся.

Источники реализованы: PromQL/VictoriaMetrics/Grafana proxy, InfluxQL,
OpenSearch и PostgreSQL pre/post. Поддерживаются несколько источников,
ручной импорт и offline replay. Живая проверка PostgreSQL 16.15 и pg_profile 4.8
прошла на синтетике; PostgreSQL 15, TLS и внешние CI gates остаются непроверенными.

## Быстрый запуск

Подготовка к отдельной приёмке добавляет advisory AI через ModelStudio, Jenkins
workflow, сохранённую аналитику, Grafana evidence и Confluence-ready export.
Локальная реализация и проверки не означают завершённую сквозную приёмку.
Порядок проверки и ограничения: [checklist](docs/mvp-acceptance-checklist.md).

### Установка из архива

Сборка не нужна: Gradle и Node.js не требуются, UI уже внутри архива. Нужна только
Java 21 или новее (JRE достаточно, JDK не обязателен; работа проверена на JDK 21
Temurin и на урезанном runtime без инструментов JDK).

1. Скачайте `ltv-<версия>.zip` и `ltv-<версия>.zip.sha256` со страницы GitHub Release
   (репозиторий приватный, нужен доступ), например
   `gh release download v0.1.0 -R system0shock/LT-Verdict`.
2. Проверьте сумму: `sha256sum -c ltv-0.1.0.zip.sha256` (Linux) или
   `(Get-FileHash ltv-0.1.0.zip).Hash` в PowerShell и сравните с содержимым `.sha256`.
3. Распакуйте архив в любой каталог, например `C:\ltv` или `~/ltv`.
4. Проверьте установку и запустите UI:

```powershell
.\ltv-0.1.0\bin\ltv.bat --version
.\ltv-0.1.0\bin\ltv.bat ui
```

В Linux те же команды: `./ltv-0.1.0/bin/ltv --version` и `./ltv-0.1.0/bin/ltv ui`.

### Сборка из исходников

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
- [История релизов и выбор baseline](docs/user/release-history.md)
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
- [ADR 0011 — L0-тренд по объявленному плану](docs/adr/0011-trend-plan-l0-materiality.md)
- [Контракт trend-plan.v1](docs/contracts/trend/v1/trend-plan.schema.json)
- [Границы трендового детектора](docs/analytics-trend-detection.md)
- [Границы масштабирования аналитики](docs/analytics-scale-triage.md)
- [Утверждённый дизайн Slice 1](docs/superpowers/specs/2026-08-31-slice-1-local-usable-shell-design.md)
- [План реализации Slice 1](docs/superpowers/plans/archive/2026-08-31-slice-1-local-usable-shell.md)
- [План локального просмотра и экспорта](docs/superpowers/plans/archive/2026-09-05-local-review-pilot.md)
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
