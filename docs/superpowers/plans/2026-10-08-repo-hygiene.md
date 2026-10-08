# W1.5: гигиена документации и репозитория

**Дата:** 2026-10-08. **Статус:** план, реализация в ветке `docs/repo-hygiene-w1-5`
(от `origin/main` `9e81e32`). Только документация, продуктовый код не меняется.

**Основание:** пункт W1.5 [перечня работ по итогам ревью](2026-10-08-review-work-plan.md)
(архитектура R8, R10; продукт R5). Решения владельца: D1 (заморозка ширины)
действует; D3 подтверждён 2026-10-08: direct-runner (ADR 0027) единственный
боевой путь ИИ, Docker и relay остаются харнессом для экспериментов.

## REQUESTED / REQUIRED / NOT REQUIRED / EXPECTED FILES (AGENTS.md)

```text
REQUESTED: W1.5 «Гигиена документации и репозитория»: README с «что умеет
  сегодня» и quickstart на один экран; архив выполненных планов; исправить
  docs/architecture/slice-1-local-runtime.md; дедуплицировать ADR 0010;
  принять ADR 0027; рекомендации по ADR 0025 и 0026; синхронизировать
  docs/development-plan-v0.6.md с кодом. Критерий: архитектурный документ не
  противоречит коду.
REQUIRED TO ACHIEVE IT:
  1. README: разделы «Что умеет сегодня» (с границами) и «Быстрый старт»
     (analyze -> report); правка устаревшей фразы о pod-view в «Статусе».
     Блок установки (строки 53-60) не трогается: его меняет ветка W1.2.
  2. Архив: git mv выполненных планов в docs/superpowers/plans/archive/ и
     правка ссылок на них (проверка Links в CI), включая .markdownlint-cli2.yaml.
  3. slice-1-local-runtime.md: правки только там, где текст расходится с кодом.
  4. ADR 0010: второй ADR с номером 0010 (подтверждение условий baseline-пары)
     переименован в 0028, ссылки на него обновлены.
  5. development-plan-v0.6.md: статусы срезов 2-10 и оговорка об исторических
     очередях; противоречие про BASELINE-CONDITIONS-01.
  6. ADR 0027 -> Accepted со ссылкой на слово владельца и на статус харнесса
     Docker/relay.
  7. Журнал: фрагмент changelog.d/repo-hygiene.changed.md.
NOT REQUIRED (REPORT-ONLY): удаление веток; удаление worktree (сделано
  оркестратором); смена статуса ADR 0025 и 0026; перенос PRC и exec-summary из
  корня; правка пользовательских документов docs/user/*, кроме упоминаний
  перенесённых файлов; переписывание исторических разделов плана v0.6;
  дубли хелперов и мёртвый код (ConfluencePublisher) из ревью архитектуры;
  README установки и release-workflow (W1.2).
EXPECTED FILES TO CHANGE: README.md; docs/architecture/slice-1-local-runtime.md;
  docs/development-plan-v0.6.md; docs/adr/0027-advisory-ai-direct-local-runner.md;
  docs/adr/0010-baseline-conditions-confirmation.md -> 0028-... и ссылки на него
  (ADR 0014, 0017, 0019, 0020, архитектурный документ, план v0.6,
  session-handoff, statistical-validation-results-v1, один план);
  36 файлов планов (git mv) и файлы со ссылками на них;
  .markdownlint-cli2.yaml; changelog.d/repo-hygiene.changed.md; этот план.
```

Публичные контракты, схемы, форматы вывода CLI и коды выхода не меняются.
Production-зависимостей нет. Единственное «публичное» изменение: идентификатор
ADR (0010 -> 0028 для одного из двух); в коде и контрактах ссылок на него нет
(проверено `grep`).

## Rulings

- **Ruling R1. ADR 0010 переименовывается, а не удаляется.** Два файла с номером
  0010 это два разных решения (граница advisory AI, 2026-09-06; подтверждение
  условий baseline-пары, 2026-09-06), оба Accepted и оба на них ссылаются.
  Удалить один нельзя без потери решения. Переименован второй (baseline-пара)
  в 0028: на него ссылок около 15 против около 90 на ADR о границе ИИ. Цена
  ошибки: ссылка «ADR 0010» в чужой ветке на условия пары станет двусмысленной;
  в самом файле оставлена строка «ранее — второй ADR 0010».
- **Ruling R2. Критерий архива.** В `archive/` уходит план, у которого (а) все срезы
  влиты в `main` (подтверждение: фрагмент `changelog.d`/запись `CHANGELOG.md`,
  принятый ADR, код в `src`/`ui`/`tools`, статус в самом плане), либо (б) он
  заменён другим документом (`Superseded`: оба плана 2026-08-10), либо (в) срезы влиты,
  а оставшаяся приёмка ведётся в другом документе (`2026-09-06-mvp-three-tracks`:
  приёмка в `docs/mvp-acceptance-checklist.md`; `2026-09-05-influxdb-source`: строка
  «Task 1 not started» в конце файла устарела, InfluxQL влит). Остаются на месте:
  планы с невлитыми или непроверенными срезами, планы, на файл которых ссылается
  код или протокол воспроизводимости, и нормативные. Цена ошибки: план лежит не в
  том каталоге; `git mv` сохраняет историю, возврат это обратный `git mv`.
- **Ruling R3. `2026-09-06-statistical-validation.md` не архивируется.** Его путь
  зашит в белом списке исходников `tools/stats_validation.py:844` и
  `tools/applicability_validation.py:443`, из которого строится хэшированный
  `source.zip` приёмки. Перенос изменил бы состав и хэш архива (воспроизводимость).
  Цена ошибки при переносе выше, чем при оставлении.
- **Ruling R4. `2026-08-10-development-governance-implementation.md` не
  архивируется:** у него статус «нормативный».
- **Ruling R5. ADR 0027 -> Accepted по слову владельца D3.** Слово владельца
  2026-10-08 подтвердило D3 (direct-runner единственный боевой путь ИИ). В ADR
  записана ссылка на это решение. Принимается решение целиком: режим direct, его
  потери относительно ADR 0010/0021/0023 и открытый вопрос про `--bare`. Открытый
  вопрос остаётся открытым (он про настройку на боевой машине, не про решение).
  Таблица решений перечня ревью (раздел 1) всё ещё показывает D3 как «ждёт
  подтверждения»; перечень не правится этим PR (report-only), ADR ссылается на
  подтверждение владельца 2026-10-08.
  Цена ошибки: ADR, принятый шире слова владельца; поэтому в тексте явно
  перечислено, что именно принято (режим, не проверка настоящего GigaCode).
- **Ruling R6. ADR 0025 и 0026 не трогаются** (слова владельца нет). Рекомендации
  даны в отчёте.
- **Ruling R7. Документ плана v0.6 правится минимально.** Исторические разделы
  («Подготовка к приёмке», очереди) не переписываются: добавляется одна
  оговорка со ссылкой на таблицу и обновляются статусы таблицы срезов и два
  противоречащих абзаца.
- **Ruling R8. Архитектурный документ.** Документ остаётся
  «implemented candidate» по Slice 1, но получает раздел о том, что вне Slice 1
  runtime делает исходящие вызовы (источники, Jenkins, Grafana, ИИ) и какие CLI-команды
  есть; перечень маршрутов объявлен неполным со ссылкой на `LocalApi.kt`.
  Переписывать документ в полный справочник API не требуется (NOT REQUIRED).

## Что устарело в slice-1-local-runtime.md (проверено по коду)

| Утверждение документа | Факт в коде |
| --- | --- |
| «Runtime не содержит database, broker, outbound HTTP/DNS client» | `sources/*` (Prometheus, InfluxQL, OpenSearch, PostgreSQL JDBC), `integrations/jenkins`, `integrations/grafana`, relay и direct-runner ИИ делают исходящие вызовы по явному запросу |
| «`ltv ui`, `ltv analyze` и `ltv policy validate`» | В `CommandLine.kt:72-77` команды `analyze`, `policy`, `report`, `ui`, `source`, `opensearch` |
| Перечень Private loopback API из 13 маршрутов | В `LocalApi.kt` более 40 маршрутов (`baseline`, `releases`, `advice`, `sources`, `jenkins`, `grafana`, `analytics`, `resource-series`, ...) |
| Дерево data directory без `baseline.json`, `baselines/`, `baseline-conditions/`, `releases/`, `runs/<id>/advice/` и необязательных артефактов анализа | `RunBundleStore.kt:1807-1817`, `AiAdviceStore.kt:206-207`, `AnalysisService.kt:783-798` |
| «VM/Grafana transport отсутствует» (resource snapshot) | `PromqlSource.kt`, `SourceHttp.kt`, `GrafanaEvidence.kt` |

Остальные утверждения документа (identity, нормализация, executor, security,
baseline, release history) сверены с кодом при чтении и расхождений не дали.

## Acceptance (наблюдаемые критерии)

1. `git worktree list` (проверка orchestrator'а) без записей этого PR не меняется;
   в PR нет удалений веток.
2. `ls docs/superpowers/plans` содержит только активные планы; `archive/`
   содержит 36 перенесённых файлов; `git log --follow` доступен для каждого.
3. Нет битых внутренних относительных ссылок в `*.md` и `*.html` репозитория
   (скрипт проверки ниже, пустой вывод).
4. В `docs/adr` один файл с номером 0010 и один с 0028; `grep -rn "0010-baseline"`
   пуст.
5. Статус ADR 0027 `Accepted`, ADR 0025 и 0026 `Proposed` без изменений.
6. `python tools/verify_slice0.py` проходит; `markdownlint-cli2` по изменённым
   `*.md` без ошибок.
7. Раздел «Что умеет сегодня» README совпадает с таблицей возможностей и
   границ из раздела 0 перечня ревью (синтеза инцидентов, релизов, многопользовательского
   режима нет), quickstart выполним: `ltv analyze <jtl>` печатает JSON и код
   выхода, `ltv report <run_id> <analysis_id> --format html`.

## Перемещения планов (36 файлов) -> `docs/superpowers/plans/archive/`

Критерий R2. Остаются на месте (13): `2026-08-10-development-governance-implementation`
(нормативный, R4), `2026-09-06-statistical-validation` (R3),
`2026-10-04-deep-analysis-d0-d2-d3` (влит только срез D2-min; D2-full и D3a-c,
то есть ручной шаг, закрепление, таблица агрегатов и полоса стадий, не влиты:
`ui/src/shell/stages.ts` нет),
`2026-09-06-advisory-ai-acceptance` (приёмка NOT_ACCEPTED, открытые S2),
`2026-10-04-ai-v2-implementation` (срезы B1-B4 не влиты, промпт v2 не отгружается),
`2026-10-04-correlations-c1-c6` (приёмка C5 открыта), `2026-10-04-platform-p0-p5`
(P4d, P5 в бэклоге), `2026-10-04-live-demo` и два отчёта сквозной проверки демо
(`2026-10-05-demo-walkthrough-report`, `2026-10-06-demo-walkthrough-report-2`; демо
2026-10-09 впереди), `2026-10-05-agentic-investigation-pilot` и отчёт пробы
`2026-10-06-agentic-investigation-pilot-agp-probe-report` (G3 не пройден, ADR 0024
в рамке пилота), `2026-10-08-review-work-plan` (активный перечень).

Переносятся (все срезы влиты):

```text
2026-08-10-development-plan, 2026-08-10-stage-0-closure-stage-1-contract,
2026-08-26-v06-slice-0-contracts-evidence, 2026-08-31-slice-1-local-usable-shell,
2026-09-05-histogram-encoding-memory, 2026-09-05-influxdb-source,
2026-09-05-load-resource-correlation, 2026-09-05-local-asciidoc-report,
2026-09-05-local-baseline-comparison, 2026-09-05-local-review-pilot,
2026-09-05-multiple-sources, 2026-09-05-online-sources,
2026-09-05-opensearch-source, 2026-09-05-postgresql-source,
2026-09-05-resource-statistics, 2026-09-06-advisory-ai,
2026-09-06-baseline-conditions-confirmation, 2026-09-06-capacity,
2026-09-06-correlation-headline-selection, 2026-09-06-mvp-three-tracks,
2026-09-21-mvp-acceptance-readiness, 2026-09-22-advisory-ai-production-readiness,
2026-09-22-analytics-readiness, 2026-09-22-readiness-api-wiring,
2026-09-22-test-onboarding-preparation, 2026-09-27-source-auto-window,
2026-09-27-trend-plan-l0, 2026-09-30-baseline-confirmation-impl,
2026-09-30-snapshot-limits-d1, 2026-09-30-ui-overview-u1,
2026-09-30-ui-shell-u0, 2026-10-02-adr-0018-implementation,
2026-10-02-autostep-adr-0014, 2026-10-02-ui-new-analysis-u2,
2026-10-04-deep-analysis-d0-d2-d3, 2026-10-04-release-history-and-pod-view,
2026-10-04-ui-u3-u7
```

Правило правки ссылок: относительные ссылки внутри перенесённых файлов
пересчитываются на новое расположение (на один уровень глубже), ссылки на
перенесённые файлы из остальных документов получают `archive/`, упоминания
вида `docs/superpowers/plans/<файл>` заменяются на путь с `archive/`.

## ADR 0010 -> 0028

Номер 0028 свободен: `git ls-tree origin/main docs/adr` (последний 0027), открытые PR
(#204, #205, #207) новых ADR не добавляют (#205 правит только ADR 0018).

`git mv docs/adr/0010-baseline-conditions-confirmation.md
docs/adr/0028-baseline-conditions-confirmation.md`; заголовок «ADR 0028 ...» и строка
«Номер» с историей. Ссылки классифицированы по контексту, а не заменены механически.
Заменены на 0028 ссылки на подтверждение условий пары: ADR 0014 (строки про
`run_id`/`analysis_id` и «правки ADR 0004 и 0010»), ADR 0017, ADR 0019, ADR 0020,
архитектурный документ, план v0.6, `session-handoff.md`,
`statistical-validation-results-v1.md`, два архивных плана. Не менялись ссылки на ADR
о границе ИИ (`0010-advisory-ai-boundary.md`: ADR 0021, 0023, 0024, 0027, планы ИИ,
комментарий в `AdvisoryAiTest.kt`) и историческая запись `CHANGELOG.md` (строка 439,
правка `CHANGELOG.md` вне релиза запрещена; в ADR 0028 записано, как её читать). Упоминание
«0010 занят дважды» в ADR 0014 и 0017 осталось как история; в ADR 0014 и 0020 добавлено «позже
переименован в 0028».

## Проверка

```text
npx --yes markdownlint-cli2@0.23.2 <изменённые *.md>
python tools/verify_slice0.py
python tools/check_links_w1_5.py   # временный скрипт в scratchpad, не в коммите
git diff --stat origin/main..HEAD ; git status --short
```

Документация: сам PR документационный. `Documentation impact:` обновлены README,
архитектурный документ, план v0.6, ADR 0027/0028.

## Советы Codex Astra по плану (проверены по коду)

Консультация read-only, `gpt-6-astra`, до правок. Принято:

- **Архитектурный документ, три пропущенных расхождения.** Верно. Identity содержит
  `source_acquisition_sha256` (хэш evidence сбора, включая профиль и транспорт:
  `AnalysisService.kt:113-128`, `AnalysisResult.kt:62`), поэтому фраза «transport
  provenance в identity не входят» неверна; результат одинаков в CLI и UI только при
  равных необязательных входах; бюджет гистограмм 10 000 включает окна ёмкости
  (`AnalysisService.kt:323-365`). Все три исправлены. Дополнительно найдено мной:
  `localStorage` используется (`ui/src/shell/shell.ts:24`, выбор старой оболочки).
- **Планы с оставшейся приёмкой.** Верно для `mvp-three-tracks` (статус «execution in
  progress»), оба плана 2026-08-10 `Superseded`, у `influxdb-source` устаревшая строка
  о незапущенной задаче: критерий R2 расширен, закрывающие пометки в 36 файлов не
  добавляются.
- **Линки.** Верно: выходящие ссылки перенесённых файлов пересчитаны, ссылки между
  двумя перенесёнными файлами сохранили относительный вид. Директорная ссылка на
  `archive/` из плана v0.6 заменена на упоминание в обратных кавычках. Lychee в этой
  среде нет, использована собственная проверка (она проходит весь `*.md`/`*.html`);
  внешние URL не менялись.
- **Результат-ячейки плана v0.6.** Уже обновлены (строки 8 и 9 таблицы); строка с
  `mbb-lag-max-holm.v1` в историческом разделе получила пометку о v2.
- **Quickstart и `analysis_id`.** Принято: id берётся из имени каталога
  `data/runs/<run>/analyses/<id>`; в README «Чего нет» записано, что `ltv analyze` id
  не печатает (W1.1).
- **ADR 0025 и 0026.** Замечание Codex верно в том, что родительский пункт W1.5
  требует решения «принять или отклонить»; слова владельца по ним нет, поэтому статус
  не меняется, это часть W1.5, не закрытая этим PR. Рекомендации в отчёте.
- **Отвергнуто:** архивировать отчёт `2026-10-06-demo-walkthrough-report-2.md`: он нужен
  скрипту демо 2026-10-09 (`docs/user/demo-script.md`), остаётся.
