# Результаты проверки статанализа v1

Архивы `.zip`, упомянутые ниже, хранятся локально и не включены в Git.
Они сохранены без изменений; исторические проверки требуют отдельного доступа
к этим evidence-артефактам. Свежий clone содержит код и отчёты, но не архивы.

Дата: 2026-09-06. Методика: [v1 с Applicability](statistical-validation-methodology-v1.md).

## Текущий статус

Продолжение полной приёмки: whole-report C06 audit выполнен на всех 109
сохранённых outputs — 0 запрещённых claims по фиксированным правилам;
[правила, hashes и ограничения audit](statistical-validation/claims-audit-v1.md).
Applicability debug использовал только seed 0 и завершился PASS. Полный корпус
421 cases / 830 runs на seeds 2000–2019 прошёл реальный persisted-анализ:
APPLICABILITY PASS. Полный USEFULNESS рассчитан: 28000/28000, errors 0,
8 из 28 configurations FAIL по шуму. Исследование завершено; итог приёмки FAIL.

Согласованное уточнение: UI/API и хранение подтверждения остаются продуктовым
gap, но не блокируют проверку вычислительного сравнения с явным контекстом.
Предыдущие утверждения ниже о необходимости реализовать хранение для
статистического gate superseded этим решением; исходные результаты сохранены.

- Methodology approval: APPROVED WITH APPLICABILITY AMENDMENT,2026-09-06.
- Generator/oracle/corpus freeze: все три корпуса FROZEN.
- Independent numerical oracle: IMPLEMENTED; lag correction после root review.
- Harness: runner и freeze/checker IMPLEMENTED; полный corpus FROZEN.
- CORRECTNESS: PASS — 109/109 MATCH, 0 errors/mismatches, claims audit 0/109.
- USEFULNESS/28000reports: FAIL — 20 configurations PASS, 8 FAIL; parity 84/84 PASS.
- APPLICABILITY/10system families,830simulated runs: PASS, 421/421 MATCH.
- Итог новой приёмки: FAIL. Исполнение завершено полностью, продукт gate не прошёл.

Первый deterministic production batch выполнен. Пороги, данные и эталоны
не подбирались по результатам acceptance.
Capacity подключён к CLI/API/UI и проверяется отдельно; локальные gates и
ограничения описаны в [capacity плане](superpowers/plans/2026-09-06-capacity.md).

## Поправка Applicability до запусков

По предложению пользователя добавлена центральная прикладная проверка:
event-driven service вместо заданной связи X/Y,10system families,paired
interventions,наблюдаемость/clock/missing-data variants,independent simulator
checks и отдельный rubric допустимых/запрещённых выводов. Общая причина и
неразличимые observational models не требуют угадывания hidden causal graph.
830system runs и CORRECTNESS выполняются до массовой calibration;28 000reports
не расширяются и не подменяют системные сценарии. CAPABILITY_GAP/PARTIAL и
INCOMPLETE не переименовываются в PASS. Новых результатов пока нет.

## Исходный regression baseline

Worktree: `.worktrees/local-baseline-comparison`, branch `feat/remaining-sources`.
HEAD: `60ac85a87b2170d4a48b923ad69002124782616c`, dirty source integration.
Это идентификация текущей проверки, НЕ достаточный reproducibility bundle для
будущей приёмки: перед freeze нужен source archive и file hashes по методике.

```powershell
.\gradlew.bat --no-daemon test --tests '*ResourceStatisticsTest' --tests '*DiagnosticAnalysisTest' --tests '*DiagnosticPlanTest' --tests '*BaselineComparisonTest' --tests '*UtcLoadMetricsTest' --tests '*WindowPolicyEvaluationTest' -x npmCi --rerun-tasks
```

Запуск через Luna, root независимо прочитал XML totals:39tests,0failures,
0errors,0skipped, exit0. Первый sandbox-запуск не дошёл до тестов из-за доступа
к Gradle wrapper cache; повтор выполнен с разрешённым доступом. Ошибку среды
не выдаём за математическую ошибку или отдельный успешный тест.

| Suite | Tests | Failures/errors/skips |
| --- | ---: | --- |
| ResourceStatisticsTest | 6 | 0/0/0 |
| DiagnosticAnalysisTest | 7 | 0/0/0 |
| DiagnosticPlanTest | 4 | 0/0/0 |
| BaselineComparisonTest | 15 | 0/0/0 |
| UtcLoadMetricsTest | 3 | 0/0/0 |
| WindowPolicyEvaluationTest | 4 | 0/0/0 |

В существующем DiagnosticAnalysisTest есть descriptive synthetic fixture
на20seeds. Он не заменяет report-level calibration новой методики; seeded
multi-report calibration двух прогонов пока не найдена.

## Дальнейшая фиксация

### Начало реализации, 2026-09-06

План: [statistical validation](superpowers/plans/2026-09-06-statistical-validation.md).
Добавлены test-only определения type7, pairwise sample variance, slope с
исходными timestamps, ranks, partial через rational normal equations и signed
lag profile после ранжирования всего участка. Production код не импортируется.

```powershell
python -m unittest discover -s tools -p test_stats_validation.py
```

RED: exit1, oracle module ещё отсутствовал. GREEN: exit0,6tests,OK.
Это self-check oracle по ручным числам, НЕ CORRECTNESS gate продукта.
В частности, partial fixture даёт `3/sqrt(14)`, lag fixture — `13/14`, тогда
как ошибочное повторное ранжирование lag subset дало бы1.
Acceptance seeds/expected/production outputs ещё не создавались.
Независимый Sol max review численного Task1: mathematical correctness и quality
PASS, замечаний нет. Это ограниченный review определений, не аудит всей методики
и не приёмка будущего harness/corpus.

Повторный исходный Gradle regression baseline той же командой выше:
exit0,39tests,0failures,0errors,0skipped. Это regression, не новая приёмка.

Добавлены низкоуровневые `freeze_cases`/`check_cases`: canonical JSON и hashes,
запрет overwrite, externally retained manifest digest, exact case IDs,
численная tolerance, missing/error/duplicate → INCOMPLETE. Их `MATCH` означает
только совпадение assertions данного manifest, не PASS исследования.
Те же self-checks теперь:11tests,exit0,OK;5 новых filesystem tests используют
только одноразовые ручные fixtures. Sandbox запрещал cleanup Python temp;
повтор с разрешённым доступом дал сначала ожидаемый RED (функции отсутствуют),
после реализации GREEN. Full corpus, source archive и Kotlin runner ещё отсутствуют.

Первый review freeze/checker выявил3дефекта: raw `1e999` в непроверяемом поле
мог дать MATCH; numeric expectations допускали JSON numbers вместо decimal
strings; array pointer принимал отрицательный/неканонический индекс. Исправлены
до первого acceptance freeze. История11зелёных self-checks не считается
доказательством отсутствия этих дефектов.
После дополнительных regression checks:14tests,exit0,OK. Для array pointer
отдельно проверена чувствительность теста: без guard результат MATCH вместо
ожидаемого FAIL; после восстановления guard тест проходит. Scoped re-review:
все3finding ADDRESSED, checker correctness/task quality PASS, новых поломок
в изменённых путях не найдено. Acceptance gates по-прежнему NOT_RUN.
Текущий freeze helper держит короткий corpus в памяти; массовый generator
потребует потоковой записи до запуска28000reports. Это не benchmark готовности
к массовой серии и не основание уменьшать её объём.

### Совместный regression checkpoint после standalone capacity validator

```powershell
.\gradlew.bat --no-daemon test --tests '*CapacityPlanTest' --tests '*ResourceStatisticsTest' --tests '*DiagnosticAnalysisTest' --tests '*DiagnosticPlanTest' --tests '*BaselineComparisonTest' --tests '*UtcLoadMetricsTest' --tests '*WindowPolicyEvaluationTest' ktlintMainSourceSetCheck ktlintTestSourceSetCheck -x npmCi --rerun-tasks
```

Root execution: BUILD SUCCESSFUL,17s; XML48tests,0failures,0errors,0skipped
(39existing regression +9capacity validation). UI build/typecheck, Kotlin compile
и оба ktlint checks выполнены заново. Python self-checks14/14; Markdown5files
без замечаний; git diff --check без ошибок. Это не full-project CI, не browser
acceptance и не frozen statistical baseline. Shared capacity integration
не начиналась; capacity evaluator и simulator пока отсутствуют.

После freeze сюда добавляются точные команды, artifact hashes,
configuration/seed counts, CORRECTNESS mismatches, USEFULNESS rates/intervals
по каждой семье, APPLICABILITY expected/actual/forbidden claims и status каждого
case, failures/timeouts/unevaluable и ограничения. История исходного
baseline и любых неудачных acceptance запусков сохраняется.

### До первого deterministic batch, параллельный расчёт capacity

Root обнаружил ошибку Task1 oracle, несмотря на прежний ограниченный review:
lag использовал variable overlap вместо одинаковых anchors по принятой spec
§4.3 (`t=L..N-L-1`). Это ошибка harness, не production. До первого acceptance
исправлено определение; ручной RED показал `0.4` вместо `-0.5`, GREEN проверяет
профиль `[-0.5,sqrt(3/7),0.5]` для X=`[1,2,4,3,5]`,Y=`[2,1,3,4,5]`,L=1.
Прежний пример13/14 не доказывал соответствие anchor contract и отозван.

До получения новых outputs установлено ограничение публичного workflow:
manual baseline из двух настоящих прогонов всегда `UNCONFIRMED`;
`USER_CONFIRMED` доступен только statistical selection из3..20кандидатов.
Для приёмки двух прогонов не подставляем третий run и не фабрикуем selection.
Числа и безопасный descriptive вывод проверяются через manual pair;
недостающий путь подтверждения условий — CAPABILITY_GAP. Он не исправляется
в рамках измерения и не маскируется искусственными входами.

Первый batch частичный и deterministic: полная §4,830system runs и28000reports
им не заменяются. Пропущенные варианты перечисляет frozen preparation metadata.
Simulator ещё проходит physical/debug checks; его зелёные self-tests не
подтверждают Applicability. При root review уже найдены и возвращены на
исправление RNG contract, отсутствующий jitter, planned-arrival GC и omission
timeout requests; acceptance seeds не запускались.

Первый frozen batch (до production outputs),48cases:
`build/stats-validation/v1-correctness-initial`.
Manifest SHA-256: `2f5f76587104e15446627bed9d016e36286677bcbfa63570865d01516486f8a2`.
Source ZIP SHA-256: `3cd995c33868f4c33da0440ce8c2e5cf904226e7e76cc7dd57d4bab122ded2e4`.
Source diff SHA-256: `b948a06b4e90a965915475449218f08fd148e4c64cb6a6953724998559ec389a`.
Python3.14.3, JVM21.0.9+7-LTS-338; file hashes и exact environment в
`preparation.json`. Это immutable отдельный batch, не full-matrix freeze.

### Первый production результат:48/48 MATCH

Повторная подготовка в `build/stats-validation/v1-correctness-repro` дала
тот же manifest SHA-256 и hashes всех inputs/expected. ZIP bytes различаются
из-за timestamps упаковки; исходные file hashes совпадают. Проверка до actual
вернула ожидаемые INCOMPLETE,planned48,completed0,exit1 (`preflight.json`).

```powershell
$env:LTV_STATS_CORPUS = (Resolve-Path build/stats-validation/v1-correctness-initial).Path
$env:LTV_STATS_ACTUAL = Join-Path $env:LTV_STATS_CORPUS actual.jsonl
.\gradlew.bat --no-daemon test --tests '*StatisticalValidation*' -x npmCi --rerun-tasks
python tools/stats_validation.py report --input build/stats-validation/v1-correctness-initial --manifest-sha256 2f5f76587104e15446627bed9d016e36286677bcbfa63570865d01516486f8a2 --actual build/stats-validation/v1-correctness-initial/actual.jsonl --output build/stats-validation/v1-correctness-initial/summary.json
```

Gradle BUILD SUCCESSFUL,21s; opt-in acceptance test выполнен, не skipped.
Checker: exit0,MATCH,planned48,completed48,errors0,mismatches0.
8resource cases вызвали настоящий evaluator;36analysis cases и4comparison
cases прошли JTL ingest/AnalysisService/save/fresh-store reload. Все40case
assertions `persisted_equal=true` выполнены. Comparison использовал ровно
два реальных runs и manual selection: UNCONFIRMED, без фиктивного третьего.
Проверены численные значения, полные lag profiles на fixed anchors,
anomaly boundaries и suppressed counts, SLA и оконные deltas, не только вызовы.

Долговечная копия frozen inputs/expected, manifest, source archive/diff,
environment, raw actual, checker summary и JUnit XML:
v1-correctness-initial.zip (`statistical-validation/v1-correctness-initial.zip`, локальный архив).
SHA-256: `e8f7fe49f848b400974195014687a9a14c48247c42e7af2093ea3c0de7959d1d`.
Для повторения распаковать в новый каталог; actual писать под новым именем.
Повтор — regression, не новый независимый эксперимент. Bundle содержит
allowlisted JVM/test/oracle sources; UI build assets не входят в него, поэтому
для полной команды Gradle также нужен checkout указанного HEAD и его UI dependencies.

Непокрытое перечислено в `preparation.json`: дополнительные S/A/C variants,
matched stages/bindings/missing windows, combined business SLA/diagnostic limits,
вся Applicability и USEFULNESS. Поэтому48MATCH не означают CORRECTNESS PASS.

Simulator checkpoint:14self-checks проходят; full-size debug seed0 NT01
93000completed/93000arrivals (2.06s),NT06 36000/36000 (6.08s),562GC pauses;
physical checker не нашёл нарушений в этих двух traces. Это debug, не acceptance.
Остаются полный exporter диагностических signals (queue/heap/GC/quota/replicas),
stress/temporal fixtures, независимые trace expectations и frozen830-run rubric.
Отсутствующую telemetry нельзя заменить знанием hidden mechanism.

### Финальный regression checkpoint этого batch

Свежая совместная команда (`--rerun-tasks`): CapacityPlan/CapacityAnalysis,
ResourceStatistics/DiagnosticAnalysis/DiagnosticPlan/BaselineComparison,
UtcLoadMetrics/WindowPolicyEvaluation и оба StatisticalValidation test classes,
плюс `ktlintMainSourceSetCheck ktlintTestSourceSetCheck`: BUILD SUCCESSFUL,18s.
XML totals62tests,0failures,0errors,1skip. Единственный skip — opt-in corpus
без env в regression-команде; первый frozen запуск выше имел0skips.
UI build/typecheck и Kotlin compile выполнены заново.
Python oracle/checker15/15,simulator14/14; Markdown4files и git diff --check PASS.
Ничего не staged; прежние source/UI изменения сохранены, push/commit не было.
131source file hashes между двумя prepare совпали. Ограниченный поиск private
keys/AWS/Slack token patterns в source ZIP не нашёл совпадений; это не полный
secret scan. Gitleaks локально отсутствует, его CI gate НЕ проверен.

Изменения текущего checkpoint test-only или standalone internal capacity;
новое пользовательское CLI/API/UI поведение не включено, поэтому отдельный
user-facing changelog entry отложен до capacity integration. Полный CI/browser
и повторный внешний review исправлений не выполнены; субагенты достигли лимита.

## Supplemental CORRECTNESS: freeze до первого actual

Отдельный набор из 50 cases: `build/stats-validation/v1-correctness-supplemental`.
Ожидания зафиксированы до запуска production, первоначальные 48 cases неизменны.
Manifest SHA-256: `17f81a11f43ed9c298821348f4586ea56e8bd92af91028f2d34e994842c43041`.
Source ZIP SHA-256: `d09a2ae8fa226fae8ad864e6d240bb7c22ea231a0cb943873558224126b5234f`.
Source diff SHA-256: `7149dda5c577667c510ac4260cef61076d85341085055288f7f29225e860038d`.
Статус перед запуском: NOT_RUN. Остаток матрицы перечислен в `preparation.json`;
этот batch сам по себе не закрывает CORRECTNESS.

Пробел ручного подтверждения условий пары зарегистрирован как
`BASELINE-CONDITIONS-01` в [плане MVP](development-plan-v0.6.md).
Он остаётся OPEN; capacity не меняет UNCONFIRMED на USER_CONFIRMED.

### Результат supplemental и regression после capacity integration

Первый запуск supplemental: Gradle BUILD SUCCESSFUL,21s; checker
MATCH,planned50,completed50,errors0,mismatches0. Проверены A02 gaps,
A03 абсолютные/z границы ±0.000001, constant/redundant/achieved controls,
lag support/clock/longest segment, effect/sign gates, binding mismatches,
matched windows,19/20 cell support, combined SLA и diagnostic invariance.
Входы и ожидания после получения actual не изменялись.

```powershell
python tools/stats_validation.py prepare --method v1 --batch supplemental --output build/stats-validation/v1-correctness-supplemental
$env:LTV_STATS_CORPUS = (Resolve-Path build/stats-validation/v1-correctness-supplemental).Path
$env:LTV_STATS_ACTUAL = Join-Path $env:LTV_STATS_CORPUS actual.jsonl
.\gradlew.bat --no-daemon test --tests '*StatisticalValidation*' -x npmCi --rerun-tasks
python tools/stats_validation.py report --input build/stats-validation/v1-correctness-supplemental --manifest-sha256 17f81a11f43ed9c298821348f4586ea56e8bd92af91028f2d34e994842c43041 --actual build/stats-validation/v1-correctness-supplemental/actual.jsonl --output build/stats-validation/v1-correctness-supplemental/summary.json
```

Команды создания требуют новых путей; для replay готового archive выбрать
новые actual/summary filenames. Первоначальный48-case manifest повторён без
изменений, actual сохранён как `actual-capacity-integration.jsonl`, report —
`summary-capacity-integration.json`. Результат48/48 MATCH. Дополнительно полные
parsed `output` каждого case сравнены с первоначальным actual:48равны,
0изменений, включая identity/result/evidence/comparison. Это regression,
не дополнительные независимые48наблюдений. Gradle21s, opt-in test выполнен.

Raw regression actual/summary/JUnit сохранены отдельно:
v1-correctness-capacity-regression.zip (`statistical-validation/v1-correctness-capacity-regression.zip`, локальный архив).
SHA-256: `2b2b1c13821cee163e7613f14c748d6dfe568b20c1f1a2799c69e02857366d55`.
Inputs/expected берутся из первоначального archive; source этого replay —
`source.zip` supplemental archive (Task3 до последующей invalid-load fix wave).

Остаются empty-input contract case, longest-segment tie-break, ID permutation
и whole-report forbidden claims, missing metric/empty-cell semantics,
diagnostic limit-exceeded. Нулевой request count означает RPS0, не missing
throughput: подмена этой семантики для получения нужного теста запрещена.
APPLICABILITY830 и USEFULNESS28000 не запускались.

Долговечная копия inputs/expected/manifest/source/environment/raw actual/summary:
v1-correctness-supplemental.zip (`statistical-validation/v1-correctness-supplemental.zip`, локальный архив).
SHA-256: `00153f4dffbd69f26a70b19fb5fe0330017e163903066adf0b7f34e73efd4564`.
В отличие от первоначального archive, source allowlist включает UI и wrapper.
JUnit XML этого supplemental запуска не был сохранён до следующего Gradle run;
raw actual/checker summary сохранены полностью. Это ограничение evidence,
не основание приписывать batch неподтверждённый JUnit count.

Повторный `freeze_cases` в `v1-correctness-supplemental-repro` воспроизвёл
тот же manifest SHA-256, следовательно те же inputs/expected hashes.
Root повторно проверил hashes обоих исходных durable archives и всех100
inputs/expected supplemental archive. Python self-checks: oracle/checker16/16,
simulator14/14. Markdown четырёх изменённых plan/result документов чист.

Независимый bounded review supplemental (Sol max): PASS в пределах этих50cases,
блокирующих замечаний нет. Проверены143содержательных assertions (не считая
`persisted_equal`, минимум1на case), A02 gap semantics и независимые A03
abs/z decisions, C/W/V expectations, source ZIP151/151 hashes/no extras и
точная106-file durable копия, повторный checker MATCH. Reviewer также выполнил
Python16/16; Gradle повторно не запускал. Это не review всей непокрытой матрицы
и не изменение итогового INCOMPLETE. Общий Markdown gate:59файлов,0ошибок;
`verify_slice0.py` и4существующих Python CI tests PASS. Ограниченный scan
src/tools/ui/contracts на private keys/AWS patterns:0совпадений, не Gitleaks.

## Финальный capacity regression: freeze до запуска

`build/stats-validation/v1-capacity-final-regression`: те же48cases, manifest
`2f5f76587104e15446627bed9d016e36286677bcbfa63570865d01516486f8a2`.
Source ZIP после Task4: `f24680a91086f67ee62d670266b2c43747f7f06f08af167a2e179355195f1bd4`.
Source diff: `3a37a9d03d62c5f418dab9d1d6699b45f670054936fc4a06ff9a69f60f2b6e88`.
Это повтор, не новое независимое наблюдение. До запуска: NOT_RUN.
`remaining` в preparation этого initial-batch replay описывает только его
покрытие, не отменяет дополнительных50cases выше.

Результат:48/48 MATCH,0errors/mismatches; все48полных parsed outputs равны
первоначальному actual. Общий `test check installDist -x npmCi --rerun-tasks`
BUILD SUCCESSFUL,90s:317tests,0failures,0errors,3skips. Пропущены две symlink
проверки (Windows не разрешил создание symlinks) и opt-in real PostgreSQL
gate без `LT_VERDICT_PG_IT_DEDICATED=true`. Statistical runner выполнен,
не skipped. Core/CLI capacity numeric replay входит в этот общий запуск.

```powershell
$env:LTV_STATS_CORPUS = (Resolve-Path build/stats-validation/v1-capacity-final-regression).Path
$env:LTV_STATS_ACTUAL = Join-Path $env:LTV_STATS_CORPUS actual.jsonl
.\gradlew.bat --no-daemon test check installDist -x npmCi --rerun-tasks
python tools/stats_validation.py report --input build/stats-validation/v1-capacity-final-regression --manifest-sha256 2f5f76587104e15446627bed9d016e36286677bcbfa63570865d01516486f8a2 --actual build/stats-validation/v1-capacity-final-regression/actual.jsonl --output build/stats-validation/v1-capacity-final-regression/summary.json
```

Полный reproducible statistical bundle с JUnit XML общего runtime запуска:
v1-capacity-final-regression.zip (`statistical-validation/v1-capacity-final-regression.zip`, локальный архив).
SHA-256: `c20680bc90482761a28d08e98b489ae443321f3d6387eff99919e8a6e0c8da86`.
Source freeze предшествует последующей UI-only правке capacity verdict banner;
она не меняет JVM/statistical code. Archive предназначен для указанного
statistical/runtime replay, не для воспроизведения полного browser gate.

Task4 review затем обнаружил ложное стандартное SLA-объяснение capacity
NO_VERDICT/PASS/NO_POLICY в UI. Root browser RED воспроизвёл его; одна mode
ветка теперь показывает saved verdict и отсылает к bounds/reasons без
выдуманной причины. Scoped Sol review этой правки PASS. Численные эталоны
не менялись. Full browser gate проверяется отдельно в capacity плане.

## Completion batch: freeze до запуска

Девять дополнительных deterministic cases: empty-input validation rejection,
earliest longest-segment tie-break, ID permutation, missing resource metric,
empty-cell p95/RPS/error semantics и diagnostic episode limit с сохранением SLA.
Ожидания получены из определений и независимого oracle, не из actual.
Повторный freeze воспроизвёл тот же manifest. До запуска: NOT_RUN.

Путь: `build/stats-validation/v1-correctness-completion`.
Manifest SHA-256: `92849ff91e37f1ad1c9def925be60152fb72357051156c4040328601492d46ad`.
Source ZIP SHA-256: `38b655241be54f1f3254c237e5c68cd643dea50ac8463c76419846d231d5bcb0`.
Source diff SHA-256: `45b829cc3d2aebb0563a88c4a5c0c3886361111d501eef0a49da74bac225a6ca`.

Runner добавляет проекцию исходного `diagnostic_summary` без пересчёта и
явный результат wire validation для отрицательных resource fixtures.
Production код и старые frozen ожидания не меняются. Дополнительная проекция
означает, что полная форма runner output отличается от старых архивов;
это не изменение сохранённых production result/identity.

Первый запуск: Gradle BUILD SUCCESSFUL, 12 s; 9/9 MATCH, errors0,
mismatches0. Восемь analysis/comparison cases дополнительно проверяют
сохранение и повторное чтение result/identity; отрицательный resource case
проверяет отказ валидатора. Общий накопленный результат: 107 cases MATCH,
не 107 независимых статистических наблюдений и не полный gate PASS.

```powershell
python tools/stats_validation.py prepare --method v1 --batch completion --output build/stats-validation/v1-correctness-completion
$env:LTV_STATS_CORPUS = (Resolve-Path build/stats-validation/v1-correctness-completion).Path
$env:LTV_STATS_ACTUAL = Join-Path $env:LTV_STATS_CORPUS actual.jsonl
.\gradlew.bat --no-daemon test --tests '*StatisticalValidation*' -x npmCi
python tools/stats_validation.py report --input build/stats-validation/v1-correctness-completion --manifest-sha256 92849ff91e37f1ad1c9def925be60152fb72357051156c4040328601492d46ad --actual build/stats-validation/v1-correctness-completion/actual.jsonl --output build/stats-validation/v1-correctness-completion/summary.json
```

Для повторного запуска использовать новые output/actual/summary пути.
Архив включает inputs, expected, manifest, source, raw actual, summary и JUnit:
v1-correctness-completion.zip (`statistical-validation/v1-correctness-completion.zip`, локальный архив).
SHA-256: `ff9722df0a11d8031cf198f1827c6904765a9a7ee8faa9d9de2d1870049e5b9c`.
Root повторил Python oracle/checker 17/17 и simulator 17/17; это self-checks,
не замена реальной статистической приёмке.

### Незакрытые условия итоговой приёмки

Итог остаётся INCOMPLETE. Требуются whole-report forbidden-claims audit,
830 APPLICABILITY runs с заранее зафиксированной rubric и затем, если gate
разрешает, 28000 USEFULNESS reports. Входной контракт не представляет
missing throughput через нулевое число requests; этот вариант нельзя
подменить RPS0 (W04 теперь проверяет различие).

`BASELINE-CONDITIONS-01` остаётся OPEN: нельзя подтвердить условия для
конкретной ручной пары baseline/current. Поэтому NT10 confirmed и confirmed
two-run families пока не исполнимы через публичный путь. Для полного PASS
нужно отдельное разрешение на реализацию этого контракта; сохранить пробел
можно только с явным ограничением итогового заключения, не выдавая PARTIAL
за PASS и не называя незапущенные проверки выполненными.

Симулятор получил дополнительные neutral operational series и background CPU
schedule. Эти изменения ещё не прошли APPLICABILITY; физические self-checks
не доказывают диагностическую полезность платформы.

Свежий общий `gradlew.bat --no-daemon test check -x npmCi`: BUILD SUCCESSFUL,
81 s, 318 tests, 0 failures/errors, 4 skips: две недоступные Windows symlink
проверки, opt-in real PostgreSQL и corpus runner без env. Последний уже
выполнен отдельным completion запуском выше: 5 JUnit tests, 0 skips.
Markdown отчёта и `git diff --check` чисты. Проверено равенство всех `src/main/`
файлов между source ZIP completion и финального capacity regression.
Remote CI, live PostgreSQL и новый browser gate в этом этапе не запускались.

Scoped Sol max review completion: блокеров нет. Независимо пересчитаны
lag profile, partial correlation, три empty-cell коэффициента и 1001 episode;
проверены 18 input/expected hashes, 151 source hashes и 28 файлов archive.
Генерация старых 48+50 cases byte-identical supplemental source snapshot.
Заключение относится только к девяти completion cases; общий INCOMPLETE
и перечисленные ограничения остаются без изменений.

## Контекст одного сравнения: freeze до запуска

Два cases одной реальной пары (p95 baseline100/current120), контекст false/true.
Ожидания +20ms/+20% одинаковы; DESCRIPTIVE/CANDIDATE заданы до actual.
Source и manifest: `build/stats-validation/v1-confirmation-context`.
Manifest: `db69c8194997b7155c7b67e16c283a875549feed9b33b01bf238f80f80cecc0c`.
Source ZIP: `e41159634c911e9ec6d900e47a6926bdbafee62c12d4057a9b27b812010236d4`.
Source diff: `f5ef9e0f1fe0740fd5b3c6a03e7f62cb926bcdc92267ef6578d8b4a65ece5331`.
До запуска NOT_RUN. Python18/18; JVM RED для игнорируемого контекста и
невалидного типа подтверждён, затем focused GREEN. UI/API/schema не меняются.

До regression запуска зафиксированы все прежние 107 cases без изменения
inputs/expected: `build/stats-validation/v1-context-regression`, manifest
`1d8202b05567e7379da0051c4774a0b45dbdde47356173cf8efe889b583c4362`.
Source для regression — тот же context source ZIP выше. Это повтор,
не дополнительные независимые наблюдения.

Context результат: 2/2 MATCH, errors0/mismatches0. Повторный freeze воспроизвёл
manifest. Общий JVM `test check -x npmCi`: BUILD SUCCESSFUL, 82 s,
320 tests, 0 failures/errors, 3 skips (две Windows symlink и opt-in PostgreSQL).
Statistical runner выполнен. Regression107: 107/107 MATCH, Gradle18s.
Сохранённые baseline/current outputs (включая SLA/identity) и все численные
поля сравнения совпадают между false/true. Различаются только интерпретация
и её пояснения; дополнительная проверка первоначально не исключала
`percent_reason`, затем различие было исследовано — численного расхождения нет.
Исходные frozen inputs/expected не менялись.

```powershell
python tools/stats_validation.py prepare --method v1 --batch context --output build/stats-validation/v1-confirmation-context
$env:LTV_STATS_CORPUS = (Resolve-Path build/stats-validation/v1-confirmation-context).Path
$env:LTV_STATS_ACTUAL = Join-Path $env:LTV_STATS_CORPUS actual.jsonl
.\gradlew.bat --no-daemon test check -x npmCi
python tools/stats_validation.py report --input build/stats-validation/v1-confirmation-context --manifest-sha256 db69c8194997b7155c7b67e16c283a875549feed9b33b01bf238f80f80cecc0c --actual build/stats-validation/v1-confirmation-context/actual.jsonl
```

Replay использует новые actual/output paths. Regression исполняется тем же
runner с corpus `v1-context-regression` и фильтром `--tests '*StatisticalValidation*'`.
Архивы raw inputs/expected/actual/summary/JUnit:

- Context и полный source (`statistical-validation/v1-confirmation-context.zip`, локальный архив),
  SHA-256 `e08e4702e13f349e1f82ab2084b9518855a16cd747e5237b2d381f852c7b748a`.
- Regression107 (`statistical-validation/v1-context-regression.zip`, локальный архив),
  SHA-256 `b3c730652877ff00eee796dc9f563bf4b30859e3f916202b7ced303c24a6b33d`;
  source — context archive.

Scoped Sol max review: блокеров в контекстном изменении нет. Reviewer проверил
tri-state, строгую валидацию, forwarding и одинаковые реальные входы; его
unittest ограничен sandbox tempfile permissions, root Python18/18 прошёл.
Markdown и Kotlin lint чисты. Новых production dependencies или публичных
UI/API/schema изменений нет; CHANGELOG не меняется для внутреннего контекста.
APPLICABILITY830, USEFULNESS28000 и whole-report audit ещё не выполнены.

## Подготовка полной системной приёмки: debug, не acceptance

Три debug-корпуса сохраняются отдельно, предыдущие не перезаписываются:

| Корпус | Запланировано | Наблюдение |
| --- | ---: | --- |
| applicability-debug-v1 | 41 cases / 70 runs | Все 41 отклонены: чтение JTL нормализовало CRLF и нарушило binding hash |
| applicability-debug-v2 | 41 cases / 70 runs | 27 MATCH; 3 расхождения ошибочного reviewer-ожидания clock status; 11 отказов валидации входа |
| applicability-debug-v3 | 41 cases / 70 runs | PASS: 41 MATCH, 0 execution errors, 0 запрещённых claims |

Во втором корпусе 4 отказа связаны с float-хвостами сверх 12 дробных знаков
snapshot, 7 — с duration 3 s, не кратной grid 10 s. Стенд исправлен:
исходные JTL bytes сохраняются, telemetry округляется до 6 знаков HALF_EVEN,
duration отображается в минимальное целое число cells. Production не менялся.
Численные эталоны дополнены resource deltas, binding/window identity,
sample counts/durations и anomaly support/suppressed intervals.

Требование reviewer `unknown clock => DESCRIPTIVE` отозвано до просмотра
numerical outputs: действующий correlation contract разрешает наблюдаемые
association и lag profile, но запрещает утверждение межисточникового порядка.
Никакого whitelist/PARTIAL по этому статусу нет; проверяется clock reason и
отсутствие causal/time-order claims. Старый debug-v2 expected сохранён как есть.

Debug-v2 manifest: `bfeaf4cf6bf4c3745465d292d158479a69f14a97553fc0e9b1c6adf2a131353f`.
Debug-v3 manifest: `d3764a6ec59204e86c93def32c729d169623cc0136ff04d4950d433bec60ab59`.
Debug-v3 source ZIP: `94e82bd3eb6d480af009a2c969aeda513b2db61cd2c528ef6b00dbc55c9a6581`.

Для полного корпуса подготовка независимых cases распараллелена на ограниченное
число процессов; порядок manifest сохраняется. Корпус не получает freeze.json,
пока не завершены все cases. Root подтвердил worker-check: 10 Python tests,
0 failures, 0 skips вне ограничений sandbox.

Повтор debug seed 0 с тремя workers воспроизвёл manifest debug-v3 побайтно.
Score debug-v3 SHA-256:
`1fce5f40b619e2fcdff657fc3a58161d4705dc312ec98def0b97efc28f4cf831`.
Raw outputs, source и JUnit сохранены в соответствующих каталогах
`build/stats-validation/`; debug failures не удалялись.

## Полный Applicability freeze

До product execution зафиксирован полный manifest 421 cases / 830 runs:
`289420995a4c3160ee5cfa24b36356903872d54176f0e19db1793d0568de6900`.
Source ZIP SHA-256:
`aa3b68caf3b2f5246f02038e9ae7d4c8abab7f6b74bbb4ba61f89d7021082ab1`.
Корпус: `build/stats-validation/v1-applicability`. Все contracts/preflight
прошли при генерации; это ещё не результат проверки продукта.

Общий свежий `test check -x npmCi --rerun-tasks`: BUILD SUCCESSFUL, 1m 55s.
JUnit сохранён отдельно в `build/stats-validation/full-verification-junit`.
Sol max bounded prefreeze review Applicability и MC: PASS. Acceptance seeds
reviewer не исполнял. Seed-0 parity core/persisted для N02, E02, T02: три
JUnit-проверки PASS, raw inputs и XML сохранены в `usefulness-debug-parity`.
Обязательная acceptance parity 84 cases ещё NOT_RUN; её не заменяет debug.

Независимая сверка закрытия CORRECTNESS подтвердила всю матрицу §4: immutable
batches 48+50+9+2, final-source replay первых 107 с неизменными input/expected
hashes и отдельно context 2. Missing throughput не изображается нулём:
W04 проверяет RPS 0 против null latency/error. Это согласованная граница
наблюдаемости, не пропущенный обязательный кейс. Исторические записи о
незавершённом CORRECTNESS выше superseded; остальные два gates независимы.

Полный Applicability исполнен: Gradle BUILD SUCCESSFUL за 7m 11s, JUnit
7 tests / 0 failures / 0 errors / 0 skips. Независимый scorer: PASS,
421/421 MATCH, execution errors 0, claims 0/421; все 830 trace hashes,
contracts/preflight и clock requirements проверены. Actual SHA-256:
`26bc1fc7eef7699f19da6fd19fd0a5f273134282fd8b211599807bfa8a2d3f65`.
Score SHA-256:
`33b862dbfb1affdd62a10d05f497baef7dc6222bbd75c263905e4ec114d9bc51`.

```powershell
python tools/applicability_validation.py --output build/stats-validation/v1-applicability --workers 3
$env:LTV_STATS_CORPUS = (Resolve-Path build/stats-validation/v1-applicability).Path
$env:LTV_STATS_ACTUAL = Join-Path $env:LTV_STATS_CORPUS actual.jsonl
.\gradlew.bat --no-daemon test --tests '*StatisticalValidation*' -x npmCi --rerun-tasks
python tools/applicability_score.py --corpus build/stats-validation/v1-applicability --manifest-sha256 289420995a4c3160ee5cfa24b36356903872d54176f0e19db1793d0568de6900 --actual build/stats-validation/v1-applicability/actual.jsonl --output build/stats-validation/v1-applicability/score.json
```

Существующие output paths не перезаписываются; для воспроизведения нужны новые
каталоги и source соответствующего freeze. `--rerun-tasks` обязателен:
переменные corpus не являются Gradle task inputs, cached/UP-TO-DATE не запуск.

Compact Applicability evidence (`statistical-validation/v1-applicability-evidence.zip`, локальный архив):
1694 entries, 201436576 bytes, SHA-256
`0f1fc726df1547572d3b78f968b6be447e76722359a9655efb0dd234bb4a16e9`.
Root повторно выполнил `python -m zipfile -t` и SHA-256 check. В ZIP входят
inputs/expected/contracts, actual/score, source/freeze/manifest и JUnit.
Это НЕ полный trace archive: все 830 `.gz` traces (6580658326 bytes) сохранены
отдельно в `build/stats-validation/v1-applicability`; `clean` уничтожит эту
локальную копию. Для переноса всех первичных данных копировать весь каталог.
Параметры/seed/source для регенерации и uncompressed trace hashes есть в evidence.
Архив не staged и не опубликован; его размер требует отдельного решения о
хранилище артефактов перед push, а не случайного добавления в обычный Git.

Дополнительные свежие проверки: Python 86/86 PASS; JVM 324 tests,
0 failures/errors, 6 skips (opt-in corpus/debug 3, PostgreSQL 1, Windows
symlink 2); `verify_slice0.py`, UI lint/contracts и `git diff --check` PASS.
Markdown: проверены 4 файла, 0 issues; локальные links 17/17 существуют.
Full gitleaks и внешний link checker локально не запускались: инструменты
недоступны. Ограниченный redacted secret-pattern scan новых harness files
не нашёл совпадений, но не заменяет CI gitleaks с полной историей.

## USEFULNESS freeze до product execution

Подготовлены ровно 28000 reports (28 configurations × 1000 seeds 1000–1999).
Manifest SHA-256, сохранённый вне корпуса до запуска:
`4de8f99f692fde334716d15b5ad7354f3784dd6f868fd2f01aa6a67f74ba004d`.
MC source ZIP SHA-256:
`1b71d6f4d36b3d294015abe69779c29eda8a317d60b57659ab4cf7b246eeb085`.
84-case parity manifest SHA-256:
`01bc2b5c860a5d01ee49e613275ffaf57a28838ebf3fc36c2db8ba91b1f371a2`.
Подготовка повторно проверила Applicability report/actual, все его trace hashes
и неизменность production sources; собственный MC freeze включает завершённый
scorer. Никакие outputs MC ещё не использовались при фиксации.

Acceptance parity завершена ДО массового запуска: 84/84 PASS, mismatches 0,
projection schema 84/84 valid. Gradle 1m 17s, raw/JUnit/score сохранены в
`build/stats-validation/v1-usefulness/parity`. Core actual SHA-256:
`57a2c11bc3b9b74171025f8fede39cb76a2d5fc8d1c3006cd2147c8d1e367c54`;
persisted actual SHA-256:
`94d8cc68369e85d79f0a28f31be283117c786609beecf218e086b6976edd07c6`.
Только после этого запущены все 28000 reports; численные пороги не менялись.

Команды MC (выполнены последовательно, один Gradle process):

```powershell
python tools/usefulness_validation.py prepare --output build/stats-validation/v1-usefulness --applicability build/stats-validation/v1-applicability --report build/stats-validation/v1-applicability/score.json --report-sha256 33b862dbfb1affdd62a10d05f497baef7dc6222bbd75c263905e4ec114d9bc51
$env:LTV_STATS_CORPUS = (Resolve-Path build/stats-validation/v1-usefulness/parity).Path
$env:LTV_STATS_ACTUAL = Join-Path $env:LTV_STATS_CORPUS persisted-actual.jsonl
$env:LTV_USEFULNESS_CORPUS = $env:LTV_STATS_CORPUS
$env:LTV_USEFULNESS_ACTUAL = Join-Path $env:LTV_STATS_CORPUS core-actual.jsonl
.\gradlew.bat --no-daemon test --tests '*StatisticalValidation*' --tests '*UsefulnessValidation*' -x npmCi --rerun-tasks
```

До следующей команды функция `usefulness_validation.parity` проверяет оба
actual файла, а `validate_output` — все 84 projections. Сохранённый
`parity/score.json` должен иметь PASS, completed 84 и пустой mismatches.
Первый запуск этих 84 не оценивается как отдельная независимая MC выборка:
финальный scorer сверяет те же 84 внутри полного actual с persisted outputs.

```powershell
Remove-Item Env:LTV_STATS_CORPUS, Env:LTV_STATS_ACTUAL
$env:LTV_USEFULNESS_CORPUS = (Resolve-Path build/stats-validation/v1-usefulness).Path
$env:LTV_USEFULNESS_ACTUAL = Join-Path $env:LTV_USEFULNESS_CORPUS actual.jsonl
.\gradlew.bat --no-daemon test --tests '*UsefulnessValidation*' -x npmCi --rerun-tasks
python tools/usefulness_validation.py score --corpus build/stats-validation/v1-usefulness --manifest-sha256 4de8f99f692fde334716d15b5ad7354f3784dd6f868fd2f01aa6a67f74ba004d --actual build/stats-validation/v1-usefulness/actual.jsonl --persisted-actual build/stats-validation/v1-usefulness/parity/persisted-actual.jsonl --output build/stats-validation/v1-usefulness/score.json
```

Окружение: Windows 11 10.0.26200, Python 3.14.3, Java 21.0.9+7-LTS-338,
Gradle 9.5.0, Node v24.14.0. Dependency locks и source сохранены в ZIP.
В v1 MC freeze поле `applicability_actual_path` привязано к абсолютному пути
выше: перенос scorer на другую машину требует разместить verified upstream
actual по этому пути. Это ограничение переносимости harness, не численная
неопределённость; original manifest нельзя тихо переписать для нового пути.

## Итог USEFULNESS: полный отрицательный результат

Все 28000/28000 reports завершены: errors 0, missing 0, unevaluable 0,
forbidden claims 0. В каждой из 28 configurations ровно 1000 completed;
20 configurations PASS, 8 FAIL. Общий USEFULNESS — FAIL, не INCOMPLETE
и не PASS «по большинству». Итог всех трёх statistical gates — FAIL.
Gradle исполнение: 1h 15m 5s; JUnit 3 tests, 0 failures/errors, 1 debug skip.
Это зелёный execution gate, не зелёная статистическая приёмка.
Финальная parity на 84 внутри полного actual повторно PASS.

MC actual SHA-256:
`b72cb19302d0c834dca155db6e793046ba9b945db4a860f4c02488a82bacdd12`.
Score SHA-256:
`690c229800ec3db8499dea752eafa979c4fdf148c4fcdb6ccea329fd44004cfd`.
После исполнения src/tools совпадают с MC source freeze, mismatches 0.
Ни thresholds, ни seeds, ни expected после результатов не менялись.

Ниже проценты и двусторонние Wilson 95% intervals; округление только для
отображения, gate использует исходные числа. L — max lag в cells 1 s.
Шум — доля reports с хотя бы одной unrelated headline, не доля всех пар.
В положительных families true injected finding не считается шумом.

| Configuration | Шум, % [95%] | Detection, % [95%] | Headlines median/p95/max | Gate |
| --- | --- | --- | --- | --- |
| E01 / rules=1 | 0.0 [0.00; 0.38] | — | 0/0/0 | PASS |
| E01 / rules=32 | 0.0 [0.00; 0.38] | — | 0/0/0 | PASS |
| E02 / rules=1 | 0.0 [0.00; 0.38] | — | 0/0/0 | PASS |
| E02 / rules=32 | 0.0 [0.00; 0.38] | — | 0/0/0 | PASS |
| E03 / rules=1 | 0.0 [0.00; 0.38] | — | 0/0/0 | PASS |
| E03 / rules=32 | 0.7 [0.34; 1.44] | — | 0/0/1 | PASS |
| N01 / pairs=1, L=0 | 0.0 [0.00; 0.38] | — | 0/0/0 | PASS |
| N01 / pairs=16, L=0 | 0.0 [0.00; 0.38] | — | 0/0/0 | PASS |
| N01 / pairs=1, L=10 | 0.0 [0.00; 0.38] | — | 0/0/0 | PASS |
| N01 / pairs=16, L=10 | 0.1 [0.02; 0.56] | — | 0/0/1 | PASS |
| N02 / pairs=1, L=0 | 1.8 [1.14; 2.83] | — | 0/0/1 | PASS |
| N02 / pairs=16, L=0 | 27.8 [25.11; 30.66] | — | 0/1/3 | FAIL |
| N02 / pairs=1, L=10 | 13.1 [11.15; 15.33] | — | 0/1/1 | FAIL |
| N02 / pairs=16, L=10 | 91.7 [89.83; 93.25] | — | 2/5/9 | FAIL |
| N03 / pairs=1, L=0 | 0.0 [0.00; 0.38] | — | 0/0/0 | PASS |
| N03 / pairs=16, L=0 | 0.1 [0.02; 0.56] | — | 0/0/1 | PASS |
| N03 / pairs=1, L=10 | 0.0 [0.00; 0.38] | — | 0/0/0 | PASS |
| N03 / pairs=16, L=10 | 0.3 [0.10; 0.88] | — | 0/0/1 | PASS |
| P01 / pairs=16, L=0 | 24.3 [21.74; 27.05] | 100.0 [99.62; 100.00] | 1/2/4 | FAIL |
| P02 / pairs=16, L=10 | 86.9 [84.67; 88.85] | 100.0 [99.62; 100.00] | 3/6/9 | FAIL |
| P03 / pairs=16, L=10 | 86.1 [83.82; 88.11] | 100.0 [99.62; 100.00] | 3/6/9 | FAIL |
| P04 / rules=32 | 0.0 [0.00; 0.38] | 100.0 [99.62; 100.00] | 1/1/1 | PASS |
| P05 / rules=32 | 0.0 [0.00; 0.38] | 100.0 [99.62; 100.00] | 1/1/1 | PASS |
| P06 | 0.0 [0.00; 0.38] | 99.6 [98.98; 99.84] | 3/3/3 | PASS |
| P07 | 0.0 [0.00; 0.38] | 99.7 [99.12; 99.90] | 3/3/3 | PASS |
| T01 | 2.0 [1.30; 3.07] | — | 0/0/1 | PASS |
| T02 | 36.8 [33.87; 39.83] | — | 0/1/1 | FAIL |
| T03 | 5.4 [4.16; 6.98] | — | 0/1/1 | FAIL |

### Что не прошло и что прошло

- N02 AR(.8): одна pair без lag проходит (1.8%), но 16 pairs без lag дают
  27.8% шумных reports; одна pair с L=10 — 13.1%; 16 pairs с L=10 — 91.7%.
  В последнем случае median 2, p95 5, max 9 лишних headlines на null report.
- P01–P03: injected pair обнаружена во всех 3000 reports, знак верный,
  signed lag error ровно 0 cells. Но unrelated extras в 24.3%, 86.9%, 86.1%
  reports нарушают обязательный шумовой gate. Detection не компенсирует шум.
- T02: независимые прогоны одного AR-процесса дают 36.8% unexpected reports.
  T03: 5.4% также FAIL, хотя верхняя Wilson граница 6.98% проходит отдельное
  условие <=7%; требуются ОБА условия, point estimate <=5% не выполнен.
  Во всех 368 и 54 срабатываниях соответственно единственная candidate metric
  — `response_time_p50_ms`. Знаки delta: T02 +191/-177, T03 +34/-20;
  p95/p99, resource, throughput и error metrics не создавали headlines.
- E01–E03: все 6 configurations проходят; максимум 0.7% (t3, 32 rules).
  P04/P05: detection 100%, IoU 1, один целый episode, ошибки начала/конца 0,
  extras 0. Это сильные фиксированные эффекты ±10 на 20 cells, не гарантия
  обнаружения коротких/слабых/немонотонных эпизодов в произвольной telemetry.
- P06/P07: detection 99.6% и 99.7%; missed 4 и 3 reports, extras 0.
  В каждом detection обязательны все три p50/p95/p99 правильного направления.
  Mean deviations observed delta от injected ±40 ms: P06 p50 -0.319,
  p95 -0.181, p99 -0.085 ms; P07 p50 -0.240, p95 -0.069, p99 +0.008 ms.
  Эти отклонения включают вариацию двух независимых synthetic runs, а не
  ошибку вычисления percentile относительно фактических requests.

### Решение по приёмке

Численные механизмы соответствуют проверенным контрактам, а системные сценарии
не дали запрещённых claims. Однако текущая выдача главных кандидатов не прошла
согласованный контроль шума. Нельзя объявлять stat-analysis готовым к MVP
на основании CORRECTNESS/APPLICABILITY или передавать кандидаты ИИ как
подтверждённые причины/регрессии. Следующий предмет работы — контроль шумных
correlation/two-run headlines; реализация исправлений в эту приёмку не входит.

Исследование v1 завершено с FAIL и сохранено. Любая настройка по этим outcomes
должна явно считаться development на раскрытых seeds; новое независимое
подтверждение требует версии методики и нового заранее фиксированного диапазона,
а не повторения v1 до зелёного. Product gap BASELINE-CONDITIONS-01 остаётся OPEN.

Полный MC evidence archive (`statistical-validation/v1-usefulness-evidence.zip`, локальный архив):
56185 entries, 2032144196 bytes; SHA-256
`0c2c8551af48eea55171acb8fba001a71a38239ec65d4000d3c6b0db64479975`.
Включены все frozen inputs/process truths, manifests/source/freeze, raw actual,
score с каждым из 28000 outcomes, parity inputs/outputs и JUnit. Root независимо
проверил ZIP integrity и SHA-256. Исходный каталог также сохранён; ничего
не удалялось. Архив около 2 GB не staged и не предназначен для обычного Git push:
перед публикацией требуется выбрать artifact storage. Это не reason скрывать FAIL.

Документация пользовательского поведения и CHANGELOG в этом продолжении не
менялись: изменения test-only, production formulas и contracts не изменялись.
Runtime/performance CI, gitleaks/full-history и end-to-end MVP acceptance
этим исследованием не закрываются. Branch/worktree сохранены без commit/push/merge.

Финальная независимая bounded проверка Sol max: все 28000 per-case measurements
повторно агрегированы без вызова scorer aggregate. Counts, Wilson intervals,
type-7 headline summaries и diagnostic aggregates совпали, расхождений 0;
20 PASS / 8 FAIL подтверждены. Все 2000 raw T02/T03 outputs отдельно сверены
с per-case классификацией и указанной candidate metric. Hashes manifest/actual/
score подтверждены. Report/harness blockers не обнаружены; review PASS относится
к достоверности отрицательного отчёта, не превращает statistical gate в PASS.
Финальный Markdown lint: 6 документов, 0 issues; исправлен только лишний пустой
ряд в документации. Source-код после acceptance freeze не изменялся.
