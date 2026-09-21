# Воспроизводимая проверка статанализа — методика v1

Дата: 2026-09-06. Статус: APPROVED WITH APPLICABILITY AMENDMENT.
Пользователь согласовал дополнение Applicability; generator/oracle/corpora
реализованы и frozen по [плану](superpowers/plans/2026-09-06-statistical-validation.md).
Полное исследование выполнено: CORRECTNESS/APPLICABILITY PASS, USEFULNESS FAIL;
подробности и артефакты в [результатах](statistical-validation-results-v1.md).
Approval методики не означает успешную приёмку.
Ответственный за методику, эталоны и заключение: root agent.
Пользователь потребовал независимые ожидания, отсутствие подгонки и сохранение
методики вместе с результатами. Этот документ не утверждает, что проверки пройдены.

## 1. Объект и три независимых результата

Проверяем действующие descriptive statistics ресурсов, median/MAD episodes,
Spearman/partial ranks, lag profile, оконные business/resource SLA и сравнение
двух прогонов. Основание — принятая поправка в
[correlation spec](superpowers/specs/2026-09-05-load-resource-correlation-design.md),
[точный контракт](superpowers/plans/2026-09-05-load-resource-correlation.md) и
[resource spec](superpowers/specs/2026-09-05-resource-statistics-design.md).
Отложенные p-values, bootstrap, Holm и change-point algorithms не тестируем
как будто они реализованы. Capacity получает собственные fixtures после
фиксации его контракта, не меняет эталоны этого набора.

Заключения независимы:

1. **CORRECTNESS:** совпадение чисел, границ, статусов и evidence с контрактом.
2. **USEFULNESS:** число лишних главных находок, обнаружение внедрённых эффектов,
   непроверяемые случаи и объём вывода на целый отчёт.
3. **APPLICABILITY:** полезная и допустимая по доступной telemetry картина
   деградации в synthetic service, а не только обнаружение нарисованной связи.

Верная формула с большим количеством шума не даёт общий PASS.
CANDIDATE — наблюдаемая ассоциация/эпизод, не значимость и не причинность.
Шумовая доля ниже — инженерная характеристика фиксированного генератора данных,
не универсальная statistical false-positive guarantee.

Поправка добавлена до запуска новых acceptance seeds. Математические эталоны
сохраняются. Системные сценарии выполняются до массовой calibration;28 000
reports не заменяют Applicability и не расширяются новыми statistical families.
Если системная проверка выявляет блокирующий пробел, массовая серия остаётся
NOT_RUN до явного решения, а не объявляется пройденной. Все три результата
публикуются раздельно; общий PASS требует каждого обязательного gate.

## 2. Предварительная фиксация и запрет подгонки

- До первого acceptance запуска сохраняем утверждённую методику, generator,
  независимый oracle, все параметры и manifest с SHA-256 каждого входа/эталона.
  Фиксируем Git HEAD, dirty diff, source archive с manifest, версии JVM/Python,
  dependency locks и команду запуска. Один HEAD в dirty worktree недостаточен.
- Архив содержит production/test/generator sources и schemas, но не credentials,
  локальные подключения, `.git`, node_modules или произвольные пользовательские файлы.
- Существующая реализация и старые тесты уже известны авторам: это НЕ blind study.
  Проспективная независимость относится к новым seed-выходам и эталонам до их запуска.
- Debug seeds: 0..99. Acceptance seeds: 1000..1999 включительно, каждый используется
  один раз на каждую заранее указанную конфигурацию. Seeds 100..999 не используются.
  Нельзя выбирать удачные seeds, метрики, окно, число пар или lag после просмотра.
- Ошибка harness исправляется с обоснованием и сохранением прежнего результата.
  Ошибка production, неожиданная находка и timeout — не повод исправлять oracle.
- После просмотра acceptance seed он больше не скрытый. Повтор того же corpus —
  regression, не независимое подтверждение. Любая настройка по acceptance требует
  новой версии методики и нового заранее зафиксированного диапазона seeds;
  старые failures остаются в отчёте. Автоматической смены seeds «до зелёного» нет.
- Все planned/completed/error/timeout/unevaluable cases учитываются. Нельзя
  исключать отказавшие отчёты из denominator и называть оставшиеся успешной серией.
- Oracle не импортирует production-код и не использует его outputs как expected.
  Luna разрешены генерация по зафиксированным формулам, запуск и сбор артефактов;
  менять критерии или утверждать математическую корректность она не может.

## 3. Независимые эталоны и точность

Эталонный расчёт — отдельный Python stdlib script, Python3.14.3, без нового
production runtime/dependency. `Fraction`/`Decimal` для exact arithmetic,
Decimal precision50 для sqrt/division. Не переносить implementation Kotlin.

- Quantiles: type7 по отсортированным значениям, с рациональной интерполяцией.
  Median/MAD, counts, intervals и ratios — прямо из определений.
- Sample variance: сумма квадратов различий по парам i<j, делённая на n(n-1),
  а не копия production алгоритма. Slope — точные центрированные суммы
  по исходным timestamp; gaps не сжимают время.
- Spearman: каждый rank равен `1 + count(smaller) + (count(equal)-1)/2`.
  Медленный O(n²) reference допустим на контрольных коротких векторах.
  Pearson этих ranks вычисляется exact sums + высокоточный sqrt.
- Partial ranks: независимое решение normal equations с рациональным
  Gaussian elimination на небольших невырожденных fixtures; production
  orthogonalization не копируется. Singular controls и zero residual variance
  проверяются отдельными точно вырожденными примерами, а не псевдоинверсией.
- Lag oracle следует объявленному estimand: ранги/остатки всего выбранного
  непрерывного участка, затем сдвиг, а НЕ новый Spearman каждого lag subset.
  Сравниваются все lag points, anchor mask и tie-break, не только лучший lag.
  Для N точек и L=max_lag/step anchors одинаковы: t=L..N-L-1,
  сравниваем X[t] и Y[t+k]. Variable-overlap oracle не соответствует estimand.
- P95 нагрузки вычисляется из исходных requests. Для точных literals latency
  целочисленная в1..1000ms (точная область текущего HDR), expected — order statistic
  с nearest-rank индексом ceil(0.95*n). Нельзя усреднять p95 разных ячеек.

Integer counts, ratios numerator/denominator, timestamps, masks, statuses,
reasons и hash равны точно. Decimal numerical values сравниваются в едином условии
`abs(actual-expected) <= 1e-9 + 1e-9*abs(expected)`.
Null не равен0. Пороговые решения exact и не используют numerical tolerance.
Fixtures на границе сравнивают точное threshold, значение ниже и выше на1e-6.
Для ill-conditioned control cases требуем честный reason/abstention и отсутствие
non-finite outputs, не стабильный произвольный коэффициент.

## 4. Детерминированная матрица CORRECTNESS

Каждая строка раскрывается в named cases manifest до запуска; положительные
и отрицательные варианты обязательны. Старые fixtures — regression, новые
контрольные данные и oracle остаются отдельными.

| ID | Данные и проверяемое ожидание |
| --- | --- |
| S01 | `[0,1,2,3]`,10s: mean/median1.5, q05=.15,q95=2.85,IQR1.5,MAD1,variance5/3,slope.1,half-shift2 |
| S02 | `[5,5,5,5]`: нулевой spread; empty/all-null → null, one point → stddev null, не здоровье/SLA PASS |
| S03 | `[1,null,3,null]`,10s: coverage2/4, slope.1, half-shift2; недостающие timestamps не удаляются |
| S04 | Перенос всех timestamps, positive affine transform, permutation series IDs: предсказуемые invariants; step seconds учитывается в slope |
| A01 | Reference `[99,100,101]`×10: median100,MAD1; evaluation100×10,160×5,100×10; step1s,delta20,duration3s,z3.5 → один эпизод `[10,15)` относительно evaluation |
| A02 | Тот же reference;160×2 → suppressed1,episodes0;160×2,null,160×2 → suppressed2,episodes0; знак160×3,40×3 → два эпизода |
| A03 | Reference100×30: MAD0, абсолютный порог действует, ZERO_MAD явен; reference29 → INSUFFICIENT_DATA; equality abs/z/duration проверяется отдельно |
| C01 | X=`[1,2,3,4,5]`×6,Y=`[1,3,2,5,4]`×6: rho.8; отражённый Y → -.8; X=Y с ties →1; constant outcome → null |
| C02 | Невырожденные rank vectors +1/+2 controls сверяются с independent oracle; constant/redundant controls видимы; X=Y=target → NO_RESIDUAL_VARIATION |
| C03 | Циклически перемешанный непериодический signal с известным сдвигом±3cells: все lag values сверяются с oracle; положительный lag означает resource раньше load |
| C04 | Longest complete segment выбирается по длине/времени, не по rho; gaps и соседние windows не склеиваются;29/30 paired cells; unknown clocks не получают time-order claim |
| C05 | rho1 при submaterial ranges не даёт finding; wrong sign сохраняет отрицательное evidence; отсутствие target/concurrency → DESCRIPTIVE; achieved RPS не добавляется сам |
| C06 | Explicit achieved control не подменяет target, sensitivity без него видна; min-effect equality; permutation IDs не меняет вывод; no p-values/HIGH confidence/causal claim |
| W01 | Baseline p95100,current120 → +20ms,+20%; equal rate при разных durations → delta0; zero baseline → относительная delta null, не0 |
| W02 | Одинаковые matched stages при разных долях warm-up/ступеней → matched delta0 независимо от global mixture; несовпадающие labels/unit/aggregation → явная несопоставимость |
| W03 | Unconfirmed conditions сохраняют числа, но не подтверждённую регрессию; error ratio0→.001 на границе; missing window/metric не превращается в NO_MATERIAL_CHANGE |
| W04 | Неравные sample counts: window p95 из объединённых requests, а не среднего cell p95;19/20 requests для diagnostic cell latency; empty cell RPS0,latency/error null |
| V01 | Business и resource mandatory SLA: pass/fail/missing/no-policy; null разрывает threshold run; diagnostic effect не меняет verdict |
| V02 | Диагностика disabled/enabled/limit-exceeded при одинаковых SLA inputs → одинаковый verdict; observed violation + missing mandatory capability → NO_VERDICT |

Exact branch fixtures не заменяются большим Monte Carlo прогоном.
Cases A01..A03 выполняются для resource signal и каждого supported overall
load signal: p95,throughput,error_rate. Для error_rate все значения и абсолютные
пороги делятся на1000, counts/denominators задают эти ratios точно; определения
median/MAD и интервалы не меняются. Conversion проверяется в ingest cases.

## 5. Генератор серий для USEFULNESS

Единица оценки — целый independent report, не отдельная точка временного ряда.
Для каждой комбинации ниже1000 acceptance reports; ошибочные и непроверяемые
считаются отдельно. Shared seeds между конфигурациями допустимы как paired
experiment; нельзя считать их независимыми при объединении configurations.

Генератор до запуска фиксирует JSON bytes и expected metadata. Для каждой
семьи/seed/stream отдельный `random.Random(int.from_bytes(SHA256(UTF8(
"ltv-stats-v1/<family>/<seed>/<stream>")), "big"))`. Поток Gaussian —
последовательные `gauss(0,1)` Python3.14.3. Вызовы разных streams не смешиваются.
Все generated floats сериализуются через Decimal(str(x)), HALF_EVEN до6 знаков;
после сериализации вычисляется oracle для numerical контрольных cases. Для
Monte Carlo истина задаётся процессом и injected intervals/edges; O(n²) reference
не пересчитывается28000раз. Смена Python/math platform требует
сверки input hashes, не заявления о побитовой тождественности на любой платформе.

Grid1s, reference120cells и evaluation240cells, disjoint explicit windows.
AR(1): Z0=0, Zt=phi*Z(t-1)+sqrt(1-phi²)*epsilon_t; discard первых512 значений.
Student-t3: N0/sqrt((N1²+N2²+N3²)/3), независимые normal draws.
Для correlation CPU X=.5+.2*tanh(Zx), latency Y=200+round(40*tanh(Zy))ms;
round — Python ties-to-even. Target control constant20requests/s, explicit window,
declared_aligned clocks. Twenty identical-latency requests/cell дают точный Y p95.
Это фиксированный workload, не способ увеличить significance числом requests.

Pair параметры: min_abs_effect=.3 (текущий default), min_resource_delta=.1,
min_load_delta=20ms, expected_sign=either. Не поднимать cutoff после просмотра.
Размер families:1 и16pairs; lag limits0 и10cells — четыре configurations каждой
семьи. При16pairs resources независимы друг от друга и используют один общий
load outcome этого отчёта; зависимость находок внутри отчёта не игнорируется.

| Семья | Истина/сценарий |
| --- | --- |
| N01 | Independent IID Gaussian latent X/Y; injected association отсутствует |
| N02 | Independent AR(1),phi=.8 для X/Y; injected association отсутствует |
| N03 | Independent IID t3 latent X/Y с теми же bounded transforms; stress тяжёлых хвостов latent, не утверждение о неограниченных CPU tails |
| P01 | Zy=Zx+.25*independent Gaussian,phi=.8; одна injected positive pair, остальные15 null |
| P02 | Как P01, resource опережает load на3cells; использован causal shift из расширенного ряда, не wrap-around |
| P03 | Как P02, знак отрицательный и lag=-3cells; direction convention проверяется отдельно |

N01..N03: все4 configurations. P01: lag0, family16; P02/P03: lag10,family16.
Extra adversarial cases C02/C05/W02 не объявляются независимым null: например,
общий ramp действительно создаёт marginal association. Проверяем correct
conditioning/limitations, а не требуем rho0 для произвольной общей причины.

Для episodes отдельные families E01 IID Gaussian,E02 AR(.8),E03 IID t3:
resource signal=100+noise, одинаковое распределение reference/evaluation,
no injected episodes.1 и32 независимых anomaly rules/report; unit=synthetic_unit,
delta5,duration3s,z3.5,direction=either. P04/P05 используют E02 и добавляют
+10/-10 к evaluation cells[80,100); один injected rule, остальные31 null.
Сравниваются reported interval и истинный интервал; gaps не добавляются в
этот power experiment, чтобы не менять denominator post hoc.

Для двух прогонов T01 IID Gaussian,T02 AR(.8),T03 IID t3 — независимые
baseline/current того же процесса: resource=100+noise, latency=
200+round(40*tanh(noise)),240cells,20requests/cell, одинаковые matched windows,
условия USER_CONFIRMED,min_change_percent5,min_error_rate_delta.001.
Правила errors: ровно1 error каждые100requests в обоих runs; rate одинаков.
P06/P07 используют T02, меняя current latency всех requests на+40/-40ms.
Никаких baseline selection из нескольких «наиболее удобных» прогонов.

Итого:12000 null-correlation reports,3000 positive-correlation,
6000 null-episode,2000 positive-episode,3000 null-two-run и2000 positive-two-run.
Это28000 reports, не28000 independent clinical/general-purpose experiments.
Нельзя уменьшить объём при неудобном результате или заменить completion timeout
на PASS; при ограничении ресурсов серия остаётся INCOMPLETE.

## 6. Метрики и заранее предлагаемые gates

Для каждой семьи/configuration отдельно публикуются:

- planned/completed/error/unevaluable counts;
- reports с хотя бы одной unexpected headline и их доля;
- число headlines/report: median,p95,max; число descriptive rows отдельно;
- injected effect detection, missing detection и unrelated extra findings;
- для episodes: interval intersection-over-union (IoU), fragmentation,
  start/end error; для lag: signed error и доля верного направления;
- для двух runs: знак/величина ошибки и material-candidate доля на null.

Headline = emitted correlation/anomaly finding; в сравнении — metric row
CANDIDATE. Raw coefficient row DESCRIPTIVE не headline, но его объём считаем.
Нельзя после результата переименовать шумную находку в descriptive ради метрики.
В null-two-run материальная случайная разница не математическая ошибка:
она считается operational noise при отсутствии injected change и отчёт это поясняет.

Предлагаемые engineering acceptance gates до запуска:

1. CORRECTNESS:100% обязательных cases,0 unexplained mismatches/errors/non-finite.
2. Null families: unexpected headline reports<=5%, верхняя граница двустороннего
   Wilson95% interval<=7%, для КАЖДОЙ configuration, не только pooled average.
3. Positive families: effect detection>=90%, нижняя Wilson95% bound>=85%;
   episode detection требует IoU>=.5, lag detection допускает±1cell с верным знаком.
4. Positive reports с unrelated extra headlines: те же<=5%/upper<=7%.
5. Data insufficiency, unknown controls/clocks и нулевые bases не получают
   confidence/causality/health claims; детерминированные abstention cases точны.
6. Planned-completed mismatch/errors/timeouts → INCOMPLETE, никогда общий PASS.

Wilson применяется к counts независимых reports внутри одной configuration,
z=1.959963984540054. Это uncertainty измеренной частоты в synthetic experiment,
а НЕ p-value production-корреляции и НЕ confidence отдельной находки.
Несколько families не объединяются в ложный simultaneous95% interval.
Строгие шумовые gates могут не пройти для текущего descriptive cutoff. Тогда
фиксируем USEFULNESS FAIL, даже если CORRECTNESS PASS; настройки не меняем молча.

Конкретизация scorer до первого acceptance output: P01..P03 detection требует
headline именно injected pair, правильный знак coefficient и signed lag в
допуске; observed coefficient без headline не считается обнаружением.
Для P04/P05 выбирается эпизод injected rule с максимальным IoU; требуется
правильное направление и IoU >= .5. Число пересекающих истинный интервал
эпизодов публикуется как fragmentation; эпизоды вне него — extra findings.
Для P06/P07 сдвиг добавлен ко всем requests, поэтому detection требует
`CANDIDATE` правильного направления у всех трёх p50/p95/p99; их численные
ошибки относительно injected ±40 ms публикуются отдельно. Resource candidates
в этих positive runs считаются unrelated. Выбор не зависит от actual outputs.

## 7. Applicability: генерируем систему, измеряем telemetry

### 7.1. Механизм вместо заданного коэффициента

Минимальный discrete-event synthetic service: arrivals → admission pool →
CPU service → DB service → downstream wait → completion. У каждого ограниченного
ресурса FIFO queue и заданная обслуживающая мощность; ожидающие запросы не
потребляют CPU service time. Таймауты/отказы задаются явно. Latency получается
из request start/end, CPU utilization — из busy time/capacity, RPS — из событий.
Нельзя задавать latency формулой от вычисленного CPU utilization ради высокого rho.
Не вводим универсальный порог saturation70%/85%: очередь определяется поступлением,
service demand, concurrency и доступностью ресурса. CPU utilization — показатель,
не самостоятельное вмешательство или доказанная причина.

Симулятор — test-only, не новый runtime продукта и не цифровой двойник стенда.
Исходные параметры base scenario: CPU1worker,demand10ms; DB8workers,demand2ms;
downstream timer38ms; admission pool256; open arrival stages40/70/90/110rps,
по300s; request timeout60s, после arrivals drain не более60s. Arrival times
строятся из cumulative rate, без Poisson bursts в базовом варианте. По умолчанию
service demands постоянны; stochastic вариант использует отдельные streams
U[.8,1.2] multiplier для CPU/DB demands, arrival interarrival multiplier
U[.9,1.1] с нормировкой в каждой заранее заданной ступени. События в integer
microseconds, ties: completion/resource release перед arrival, затем request ID.
Каждый сценарий явно переопределяет параметры из таблицы ниже.

Сохраняются true event trace, параметры механизмов, очередь, rejected/timed-out/
completed/in-flight counts и полный request stream. Exporter формирует JTL и
interval_mean/interval_rate snapshot; никакие cells не генерируются из rho.
Ожидания SLA/дельт считаются независимым oracle из request/event trace.
Скрытая причина используется только evaluator, не input приложения.

Сам симулятор сначала проходит независимые checks:

- arrivals = completed + rejected + timed_out + remaining_in_flight;
- CPU busy time не превышает available worker time; очереди неотрицательны,
  каждый request сохраняет порядок стадий, durations неотрицательны;
- single-worker constant-demand/no-noise case сверяется с ручной рекурсией
  finish[i]=max(arrival[i],finish[i-1])+service_time;
- при тех же requests/demands увеличение только CPU workers не увеличивает
  CPU queue wait в isolated CPU fixture; аналогично DB и pool isolated fixtures;
- censored/in-flight requests не исчезают из учёта и не превращаются в successes.

Simulation failure → INVALID_FIXTURE, Applicability INCOMPLETE, не ошибка
платформы и не PASS. Исправление сохраняет прежний trace и причину изменения.

### 7.2. Десять семейств нагрузочных сценариев

Для каждого семейства base и paired intervention используют одни arrivals,
request mix, demands и noise streams. Изменяется только названный механизм.
Нужные ресурсы/контроли доступны одинаково обоим членам пары; имена metric IDs
не содержат correct-cause/spurious/expected-bottleneck подсказок.

| ID | Механизм и контрфактическое изменение | Обязательная полезная картина / запрещённый вывод |
| --- | --- | --- |
| NT01 CPU limitation | Base model; paired CPU1→2workers | Latency/SLA/queue degradation отражены; расширение мощности снимает CPU queue. Flat saturated CPU не требует высокого rho; нельзя назвать число85% универсальной причиной |
| NT02 Common workload driver | CPU4workers; downstream delay=38ms+0.5ms*planned_rps; paired CPU4→8 при том же downstream | Raw CPU/latency association может быть высокой, но CPU capacity не управляет downstream delay. Учесть target/control limitations; не объявлять CPU доказанной причиной и не требовать partial rho0 при неидентифицируемости |
| NT03 DB limitation | CPU4workers,DB1worker с demand20ms,arrival80rps300s; paired DB1→2 | Видны DB wait и latency/SLA при умеренном app CPU. Нельзя перенести объяснение на CPU лишь из-за workload correlation |
| NT04 Pool limitation | CPU4workers,arrival60rps; в t=300s pool256→2 на300s; paired pool остаётся256 | Queue wait/latency растут при относительно flat CPU; отсутствие CPU correlation не означает здоровья |
| NT05 CPU throttling | CPU4workers,arrival80rps; в t=300s budget CPU service уменьшается до25% на300s; paired budget100% | Throttled time/queue/latency видны при неоднозначном utilization. Utilization denominator и quota сохранены, низкий normalized CPU не доказывает запас |
| NT06 GC pressure | CPU4workers,arrival60rps,allocation1MiB/request; heap64→128MiB вызывает200ms stop-the-world и возврат к64MiB; paired allocation0 | Проверяются heap/GC pauses и реальные latency spikes/SLA. Whole-run Spearman не обязан быть высоким; suppressed short episodes и ограниченная чувствительность не выдаются за отсутствие spikes |
| NT07 Downstream step | CPU4workers,arrival60rps; в t=300s downstream+50ms на300s; paired без step | App latency повторяет изменение downstream при почти постоянных CPU/RPS; per-run/window delta корректна, shared timestamp не доказывает причинность в выводе платформы |
| NT08 Autoscaling | Base model; в t=900s CPU1→2workers; paired остаётся1 | Изменение replicas/capacity и recovery видны; units per-core/aggregate не смешиваются, весь run не объявляется единым stationary режимом |
| NT09 Generator limitation | Service CPU4workers; generator planned100rps,не более20in-flight threads; paired100threads, одинаковый downstream step+500ms в t=300s | Target, achieved request starts, in-flight threads и service time различаются. Падение achieved RPS не становится доказанной верхней границей продукта; влияние генератора на capacity требует его guards |
| NT10 Stage mixture | Два runs одинаковых service stages40/70/90rps,но durations300/300/300 против300/600/900s; paired одинаковые durations | Matched-window comparisons не подменяются global mixture; изменение global percentiles не объявляется ухудшением одинаковых стадий |

Для NT04..NT07 и NT09 общая длительность600s, изменение начинается в300s.
В NT09 запланированный request при занятых threads ждёт внутри генератора;
app request start отсчитывается только после получения thread. Generator wait
и planned/issued counts сохраняются отдельно от app latency и app queue.
Для всех inputs actual load measurements и обязательные role/units явны.
Reference — заранее выбранное pre-change окно, не участок, найденный по output.
При необходимости guards/SLA/pairs выбираются по объявленной topology и workload
плану, а не по скрытому bottleneck. Одинаковое разрешённое множество pairs и
materiality используется для original/intervention. Допустимые inputs не
содержат intervention flag; truth лежит в отдельном evaluator manifest.

### 7.3. Наблюдаемость, variants и temporal ordering

Не требуется восстановить causal graph из observational telemetry. Для
наблюдательно неразличимых механизмов правильный ответ — ограниченный вывод;
знание evaluator о причине не делает её доступной алгоритму. Удаление связи
после контроля также не доказывает отсутствие causal path. Achieved RPS не
добавляется автоматически как безопасный exogenous control.

Для каждого NT семейства заранее предусмотрены clean и noisy варианты,20seeds
2000..2019. Это400paired cases/800simulated runs; не1000-repeat calibration.
Seed namespace `ltv-applicability-v1/<scenario>/<seed>/<stream>` и PRNG как§5;
члены пары переиспользуют streams, варианты не считаются независимыми репликациями.
Их частоты публикуются, но не получают Wilson-based population gate из§6.

Дополнительные stress варианты фиксируются только для указанных семейств:

- NT01: warm-up первые60s,CPU demand×2; measured reference после warm-up;
- NT02: periodic background CPU job20ms каждые100ms на интервалах
  `[120+120k,140+120k)`s, без изменения downstream function;
- NT03: block missing DB telemetry `[290,320)`s и отдельно независимый5% mask;
- NT07: resource clock offset+5s, сначала unknown, отдельно correct adapter
  correction с provenance; no automatic shift fitting;
- NT08: coarse10s sampling при сохранённых mean/rate semantics;
- NT10: same-stage analysis с USER_CONFIRMED и UNCONFIRMED comparability.

Каждый такой вариант использует fixed seed2000, оба члена пары; их9paired
cases/18runs считаются отдельно от400. Missing маски не заполняются; noisy
sampling jitter±200ms применяется к моментам observations до aggregation,
true timestamps сохраняются. Unknown aggregation/clock semantics не объявляются
корректными mean/rate inputs. Unsupported export → явный invalid fixture/input.

Отдельный temporal challenge:20s busy episode и20s downstream-latency episode,
расписания A одновременно,B resource раньше на5s,C load раньше на5s, в окне120s.
Его источник — external event schedule, а не доказательство причинной связи
между CPU и downstream. Base arrival60rps,CPU4workers; background CPU занимает
дополнительно1worker,downstream delay+50ms; интервалы начинаются в40/45s.
Variants1s/10s grid × declared_aligned/unknown clocks:12cases, без seed search.
При1s/aligned сравниваются весь lag profile и signed best lag с независимым
oracle, допустим error±1cell. При coarse sampling, periodic ambiguity или
unknown clocks exact5s order claim запрещён; отсутствие identifiable lag
не превращается в «событий не было». Это challenge lag mechanics, не causal proof.
Итого Applicability:400base/noisy paired cases +9stress paired cases +12temporal
cases,830simulated runs; количественная calibration из§5 учитывается отдельно.

### 7.4. Applicability rubric и защита от красивого, но пустого отчёта

До production execution каждый named case получает applicability manifest:
mechanism parameters, available signals/units/topology, expected observable
facts, allowed claims, forbidden claims, expected abstentions и capability gaps.
Точные численные parameters/expected facts и hashes фиксируются prepare;
если scenario требует уточнения модели, это делается до acceptance outputs.
Нельзя после просмотра назначить неудачному case expected abstention.

Для каждого case отдельно оцениваем:

1. **Observed facts:** SLA, load/resource statistics и two-run deltas совпадают
   с independent trace oracle; degradation не пропадает из-за отсутствия rho.
2. **Localization:** episode/window/evidence относится к правильному измеренному
   интервалу, без hindsight segmentation; условия duration/support видимы.
3. **Interpretation safety:** нет causal proof/HIGH confidence, false health,
   скрытого generator limit или выдуманной межисточниковой синхронизации.
4. **Counterfactual consistency:** observable changes между pair members отражены;
   неизменные quantities не получают выдуманную regression. От платформы не
   требуется вывод о вмешательстве, flag которого ей не передавался.
5. **Applicability limits:** GC nonmonotonicity, saturated flat CPU, autoscaling
   и missing data фиксируются как реальные границы метода, не как оправдание PASS.

Статусы case: PASS,FAIL,EXPECTED_ABSTENTION,CAPABILITY_GAP,INVALID_FIXTURE.
EXPECTED_ABSTENTION засчитывается только в заранее обозначенном safety challenge;
обязательное observable SLA failure нельзя закрыть abstention.
CAPABILITY_GAP не является PASS; известный gap в обязательном scenario оставляет
общий APPLICABILITY PARTIAL. Неизвестный/необъяснённый mismatch → FAIL,
invalid fixture или незавершённые planned cases → INCOMPLETE.
APPLICABILITY PASS требует все обязательные факты,0 forbidden claims и отсутствие
незакрытых обязательных gaps. Единого opaque score или «угадал причину top1» нет.

Сохраняются observed/expected numeric evidence и пояснения по каждому case,
а также contamination checks: platform input не содержит truth labels, request
names/metric IDs neutral, без выбора pairs/reference windows по hidden events.
Предварительно настроенные windows по workload schedule разрешены; окна,
подобранные по идеальной скрытой границе деградации, запрещены.

## 8. Harness, сквозные проверки и воспроизведение

Файлы реализации (текущий прогресс — в отдельном отчёте; полный harness ещё
НЕ реализован):

- `tools/stats_validation.py`: fixed generator, independent oracle, manifest,
  агрегирование results.jsonl; stdlib, без imports production.
- `tools/synthetic_service.py`: test-only event model и telemetry exporter
  по§7; отдельный независимый self-check conservation/queue before freeze.
- `src/test/kotlin/io/ltverdict/core/StatisticalValidationTest.kt`: читает frozen
  cases, вызывает существующий core, сохраняет actual outputs, ничего не подбирает.
- `src/test/kotlin/io/ltverdict/core/StatisticalValidationIntegrationTest.kt`:
  selected cases через настоящий ingest/AnalysisService/store/reload/compare,
  без mocks математических функций.

Все Applicability inputs проходят настоящий ingest→analysis→save→reload;
парные inputs дополнительно проходят existing two-run comparison. Результаты
сравниваются с trace oracle, не с напрямую переданными internal UtcLoadCells.
Сначала этот путь и CORRECTNESS; затем массовый USEFULNESS batch.
Непосредственный core путь используется для28000calibration reports ради скорости;
это не выдаётся за28000CLI/браузерных тестов. Во втором пути прогоняются все
детерминированные cases с load inputs и первые3 acceptance seeds каждой семьи:
те же generated signals → JTL → persisted analysis → comparison/reload.
Выбор3seeds фиксирован ДО просмотра. Проверяем числа, units, window membership,
findings/reasons и semantic output parity, а не только HTTP200.

Повторный offline анализ и reload не делают source calls; input/expected hashes
не меняются. Отдельные transforms проверяют порядок файлов/series, перенос
времени и format-equivalent inputs; byte identity нужна только где обещана.

План команд после реализации harness, НЕ evidence существующих запусков:

```powershell
python tools/stats_validation.py prepare --method v1 --output build/stats-validation/v1
.\gradlew.bat --no-daemon test --tests '*StatisticalValidation*' -x npmCi --rerun-tasks
python tools/stats_validation.py report --input build/stats-validation/v1
```

`prepare` сначала самостоятельно проверяет oracle на hand literals, затем
фиксирует manifest/expected без production execution. JUnit пишет actual.jsonl
в тот же directory без изменения inputs/expected. `report` проверяет manifests,
полноту planned case IDs, mismatch details и выходит ненулевым кодом при FAIL
или INCOMPLETE. Абсолютные пути/окружение входят в run log, не в semantic hashes.

Результат сохраняется в
[отдельном документе](statistical-validation-results-v1.md), machine-readable
summary/cases/actual.jsonl и reproducibility bundle; все расхождения перечислены,
не только удачные примеры. Capacity integration после freeze проверяется на том
же corpus отдельным run, оригинальный baseline не перезаписывается.

## 9. Основания и ограничения

Реализованный MC harness фиксируется отдельным source archive после PASS
Applicability; production files должны совпадать с Applicability freeze.
Manifest криптографически связывает inputs, process truth, source, inventory
и предшествующий gate. До массового исполнения обязательны 84 parity cases:
первые три seeds каждой из 28 конфигураций, полный semantic projection
core/persisted, включая reload. Подмножество не выбирается по результатам.
В T01–T03/P06–P07 diagnostic pair `window-summary` с постоянным throughput
включает существующий расчёт окон сравнения; из-за постоянного ряда он не
создаёт headline. Значения генерируемых load/resource рядов не меняются.

[NIST modified Z](https://www.itl.nist.gov/div898/handbook/eda/section3/eda35h.htm)
описывает potential-outlier labeling; наш inclusive threshold и duration —
явные продуктовые правила, не автоматически доказанный statistical test.
[R quantile type7](https://stat.ethz.ch/R-manual/R-devel/library/stats/html/quantile.html)
задаёт определение interpolation.
[SciPy Spearman](https://docs.scipy.org/doc/scipy/reference/generated/scipy.stats.spearmanr.html)
служит справкой о rank association, не источником p-values для наших рядов.
[NIST proportion intervals](https://www.itl.nist.gov/div898/handbook/prc/section2/prc241.htm)
— основание интервалов report-level frequencies.
[Pearl, causal identification](https://pmc.ncbi.nlm.nih.gov/articles/PMC2836213/)
— основание разграничения hidden mechanism, observational evidence и
допустимых claims; синтетическая известность причины не устраняет эту границу.

Проверка не доказывает причинность, стационарность боевого стенда, межпрогонную
воспроизводимость по двум runs или качество будущего ИИ. Не охваченные процессы
и несовпадение synthetic/real workload прямо остаются ограничениями.

### Согласованное уточнение: контекст сравнения без хранения

Статистическая приёмка отделена от пользовательского workflow подтверждения.
Для двух прогонов manifest заранее задаёт `conditions_confirmed` boolean,
основанный на условиях генерации, не на полученных метриках. Runner передаёт
его в настоящий `compareAnalyses`; не подменяет selection третьим прогоном и
не переписывает output. Отсутствие поля сохраняет прежнее поведение.
Внутренний необязательный контекст действует только на этот вызов: true
подтверждает условия, false явно не подтверждает (в том числе вместо
унаследованного statistical candidate context). Формулы, пороги, SLA и проверки
технической совместимости не меняются. Невалидные типы поля отклоняются.

UI/API и постоянное хранение подтверждения не входят в это уточнение.
Их отсутствие остаётся продуктовым `BASELINE-CONDITIONS-01`, но не блокирует
проверку вычислительных механизмов через real ingest и saved analysis results.
Приёмочный контекст хранится в manifest, не в run bundle. Старые frozen cases
и их ожидания не меняются; численные gates и количество сценариев прежние.
