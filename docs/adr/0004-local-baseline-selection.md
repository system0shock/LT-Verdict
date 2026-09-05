# ADR 0004 — ручной и статистический baseline

**Дата:** 2026-09-05

**Статус:** Accepted — пользователь согласовал оба режима в MVP и запись ADR.

## Контекст

PRC/PRD v0.6 предусматривает manual baseline как первый вариант и оставляет
точную automatic baseline selection policy открытым вопросом (§28).
Утверждённый local MVP delta design §18.3 фиксировал только ручной выбор.
Это решение расширяет MVP статистическим автовыбором; N-run history остаётся
отдельной функцией, а не другим названием автовыбора.

Фактический RPS может снижаться вследствие роста отклика и занятости тредов.
Равенство достигнутого RPS не является условием сопоставимости: отбор только
одинаковой фактической нагрузки способен скрыть деградацию. Заданные условия
эксперимента и полученные результаты должны оставаться разными понятиями.

## Решение

### Один фиксированный реальный прогон

Оба режима назначают конкретный immutable `run_id + analysis_id`, а не
синтетический набор метрик. Ручной режим используется по умолчанию.
Статистический режим запускается явным действием пользователя. Новый run не
переназначает baseline; повторный автовыбор или ручная замена — новое действие.
Переанализ исходного input также не подменяет закреплённый analysis.

Первый local flow хранит один активный baseline с пользовательским названием
серии. Сравнение выбранного analysis запускается явно; назначение не объявляет
все runs в data directory сопоставимыми. Дополнительный реестр групп не нужен.

### Статистический выбор `median-rank-v1`

Пользователь выбирает от 3 до 20 сохранённых analyses разных runs и подтверждает,
что заданные условия серии сопоставимы: scenario/mix, environment/dataset,
load model, targets/stages, pacing и ограничения генератора. Это утверждение
пользователя, а не автоматически восстановленная из JTL метаинформация.
Фактический RPS, latency и error rate в эту проверку равенства не входят.

Все кандидаты должны иметь `VALID`, `COMPLETE`, одинаковую техническую
семантику метрик и доступные overall P95, throughput и error rate. Отсутствие
SLA (`NO_POLICY`) допустимо; `FAIL` не исключается по одному только verdict.
Неподходящий кандидат отклоняет запрос с причиной, а не молча исчезает.
Повтор одного run с разными policy не увеличивает число независимых прогонов.

Алгоритм фиксирован и не использует randomness, timestamps или ML:

1. Для каждого из трёх показателей вычислить ascending ranks с усреднением
   рангов равных значений. Значения рациональных метрик сравниваются точно.
2. Для каждого кандидата сложить абсолютные отклонения его рангов от
   центрального ранга `(n + 1) / 2`, с одинаковым весом показателей.
3. Выбрать минимальный score; при равенстве — лексикографический
   `(run_id, analysis_id)`. Порядок кандидатов в запросе не влияет на результат.

Для целочисленной реализации используем `rank2 = 2 * countLess + countEqual + 1`
и `score = sum(abs(rank2 - (n + 1)))`; score равен удвоенному отклонению.
Средние ранги для ties — стандартная операция
[sample ranking](https://stat.ethz.ch/R-manual/R-devel/library/base/html/rank.html).
Сам выбор по сумме отклонений — локальная versioned эвристика продукта, а не
тест статистической значимости и не гарантия устойчивой нормы. Минимум 3 —
ограничение первой реализации, не доверительный уровень.

Сохраняются версия алгоритма, полный отсортированный candidate set, scores и
выбранная ссылка. Нет усреднения готовых percentiles, смешивания histogram
разных запусков или удаления выбросов. При малой/смешанной/нестабильной серии
выбор может быть плохим: состав серии и limitation видимы пользователю.

### Сравнение и интерпретация

Первая поставка сравнивает overall P95/P99 (ms), throughput (requests/second)
и error rate (ratio). Значения baseline/current, абсолютная и относительная
дельты выводятся отдельно. Относительная дельта при нулевом baseline — `N/A`
с `ZERO_BASELINE`; абсолютная дельта остаётся доступной. Missing/null не
превращается в ноль. Display rounding: максимум 6 fractional digits,
`HALF_UP`; вычисление дельт предшествует округлению.

Техническая сопоставимость требует равенства `analysis_mode`, `source_type`,
`engine`, `parsers`, `modules`, `input_versions`, `outputs`, `histogram`,
`normalization`, `limits` из сохранённой identity. `run_id`, input hash и
policy hash не сравниваются как условия равенства. При различии определения
метрик raw values видны, дельты получают `INCOMPATIBLE_METRIC_DEFINITION`.
Неизвестные заданные условия обозначаются `UNCONFIRMED`, а не ложной
несовместимостью. Для членов подтверждённой statistical candidate series
можно показать `USER_CONFIRMED`; это не машинная проверка load profile.

Дельта не является причинным выводом о версии продукта. Baseline selection и
comparison не меняют validity, coverage, policy verdict или canonical result,
не запускают jobs и не входят в существующую analysis identity. Будущая
baseline policy, влияющая на verdict, потребует отдельного versioned контракта.

### Хранение и private API

Один mutable `<data>/baseline.json` хранится отдельно от immutable RunBundles.
Запись использует существующие process/data-directory locks, UUID staging,
force и atomic replace; symlinks и special files запрещены. Ошибка валидации
или записи не уничтожает предыдущий baseline. Повреждение stored selection
показывается явно; отсутствующая ссылка не подменяется другим прогоном.

Private API получает `GET/POST/DELETE /api/baseline` и
`GET /api/runs/{runId}/analyses/{analysisId}/comparison`.
POST/DELETE используют существующие Host/Origin/session/CSRF проверки.
JSON requests ограничены 16 KiB, depth 8; series — 128 UTF-8 bytes.
Selection file ограничен 32 KiB. Формы, ids, число кандидатов и unknown fields
проверяются до мутации. Все source references читаются через существующую
проверку RunBundle manifests и hashes.

Новых production dependencies и public schemas нет. Формат local selection
имеет private version `local-baseline.v1`. Public `run.v1`, `policy.v1`,
`analysis-result.v1`, input bytes и immutable analyses остаются прежними.

## Альтернативы и границы

- Только manual baseline проще, но не выполняет новое требование обоих режимов.
- Составной статистический эталон не выбран: он не является одним реальным
  прогоном и потребовал бы отдельной семантики графиков/агрегации.
- Автоматический rolling baseline не выбран: он может включить деградацию в
  новую норму без явного решения пользователя.
- ML/clustering/автовосстановление сценариев не нужны для bounded candidate set.

Таблица N-run history, transaction-level comparison, baseline chart overlays,
CLI baseline commands, comparison exports и baseline policy gates остаются
отдельными шагами полного MVP. Эта поставка их не объявляет выполненными.
