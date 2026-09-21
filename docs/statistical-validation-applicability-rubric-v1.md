# Applicability v1 — предварительная rubric

Эта конкретизация §7 зафиксирована до запуска acceptance seeds. Пороги не
подбираются по результатам платформы. Debug использует seed0, acceptance —
неизменные seeds2000..2019 и весь inventory421cases/830runs.

## Общие входы и эталоны

- Telemetry сериализуется с точностью 6 десятичных знаков (`HALF_EVEN`),
  одинаково для всех сценариев и сторон. Это разрешение измерений; полный
  event trace сохраняется без такого округления. Эталон статистик использует
  именно сериализованные значения, а не скрытую дополнительную точность.
- Duration 3 s отображается на grid как `ceil(3000 / step_ms) * step_ms`:
  3 s на секундной grid, 10 s на coarse grid. Это тот же минимальный целый
  интервал, удовлетворяющий порогу, без выдуманной субклеточной локализации.
- Окна задаются границами workload stages; первое окно reference — первые120s,
  temporal — первые30s. Warm-up исключает первые60s из reference. Остаток
  каждой ступени — отдельное окно. Окна не подбираются по hidden degradation.
- Окна ограничиваются общим измеренным временным диапазоном на заданной grid.
  Clock offset не подгоняется: сдвинутая telemetry остаётся сдвинутой, unknown
  явно передаётся диагностике. Сам adapter не заявляет time-order.
- Одинаковые SLA обеим сторонам: business window p95<=200ms; CPU и DB utilization
  >0.9 не менее3cells — resource SLA failure. Это объявленные fixture SLA,
  не универсальный порог saturation или причинности.
- Полный request trace задаёт window sample/error counts, p50/p95/p99, RPS,
  error ratio, SLA и двухпрогонные deltas. Window membership по request start;
  timeout остаётся ошибкой. HDR three-digit quantization учитывается явно.
- По каждой доступной telemetry series проверяются median/q95 и observed/missing
  cells независимыми определениями на сериализованной telemetry. Связь telemetry
  с event trace отдельно проверяется тестами exporter и сохранённым полным trace.
- Fixed pairs: CPU, DB, admission/CPU/DB queues, GC pause, CPU quota, downstream
  occupancy, generator threads против latency; explicit target control, rho cutoff
  .3, ranges .1/20ms, lag<=10s. Achieved RPS не является control.
- Anomalies: CPU/DB/admission queue delta1; GC pause delta.05; p95 delta20ms;
  duration3s, z3.5, direction either, reference фиксирован. Проверяются количества
  и точные границы каждого эпизода через независимый trace/telemetry oracle.
- Для temporal1s проверяется полный lag profile и best lag по независимому
  rank oracle. Coarse10s имеет недостаточную поддержку: явное abstention,
  а не точное восстановление5s. Unknown clocks не получают causal/time proof.

## Применение к сценариям

| Families | Обязательные наблюдения | Ограничение интерпретации |
| --- | --- | --- |
| NT01/03/04/05 | Latency/queue/resource statistics, SLA и изменение при intervention | Flat CPU не обязан коррелировать; CPU quota не запас мощности |
| NT02 | Те же факты при общем workload driver | Marginal association не доказательство CPU bottleneck |
| NT06 | Heap/GC telemetry, latency и SLA, эпизоды с явными duration/reference limits | Короткие и немонотонные spikes могут не стать эпизодами |
| NT07 | Downstream occupancy и изменение latency, matched delta | Совпадение времени не causal proof |
| NT08 | Replicas/utilization по единым units, окна и recovery | Нет whole-run stationary или universal CPU claims |
| NT09 | Target/issued RPS, threads/queues, latency | Генераторное ограничение не capacity bound продукта |
| NT10 | Каждая сторона и matched-window deltas | Global mixture не заменяет matched stages |
| Temporal | Полный профиль, signed lag, support/clock limitations | External schedule не причинная связь CPU→downstream |

## Итоговый gate

Численные ошибки, потеря обязательного SLA, несоответствие эпизодов или
запрещённый claim — FAIL, не expected abstention. Ошибка симулятора/input
или незавершённый inventory — INCOMPLETE. Заранее обнаруженный обязательный
capability gap — PARTIAL. Нет opaque score или требования «угадать причину».
Все данные, expected и фактические выводы сохраняются; corpus MATCH сам по себе
не заменяет оценку interpretation safety и проверки completeness inventory.

### Уточнения до acceptance freeze

Debug seed 0 выявил ошибки формата адаптера: нормализацию CRLF в JTL,
float-хвосты за пределами разрешённых 12 дробных знаков snapshot и duration,
не кратную coarse grid. Исходные debug outputs сохраняются; исправляются
входы стенда, не production и не пороги обнаружения.

Требование `unknown clock => DESCRIPTIVE`, предложенное при review, отозвано
после сверки с correlation design §4 и implementation plan: разрешены
наблюдаемый lag profile и association `CANDIDATE`. Обязательны причина
`CLOCK_ALIGNMENT_UNKNOWN` и отсутствие утверждения межисточникового порядка.
Это не capability gap и не исключение из численных проверок. Уточнение принято
до просмотра numerical debug outputs и до генерации acceptance seeds.

NT05 при объявленных 80 requests/s и четырёх workers с quota 25% не обязан
создавать CPU queue: оставшаяся пропускная способность равна 100 requests/s.
Нулевая очередь — проверяемое наблюдение, а не причина менять workload.
Preflight требует реального увеличения CPU service duration у общих requests
после quota change, а не только записи quota в metadata. Параметры не меняются.

Ранг latency percentile соответствует закреплённому Java HdrHistogram 2.2.2:
округление вверх, затем upper-equivalent bucket. Это проверено также по
bytecode установленного JAR; правило старых реализаций с `+0.5` неприменимо.
[Исходник закреплённой версии](https://github.com/HdrHistogram/HdrHistogram/blob/HdrHistogram-2.2.2/src/main/java/org/HdrHistogram/AbstractHistogram.java#L1321).

Численные проверки не доказывают UI/API workflow, permanent confirmations,
качество будущего ИИ и переносимость выводов на произвольный боевой стенд.
