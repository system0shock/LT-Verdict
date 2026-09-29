# Пилот корреляционного отбора на раскрытых данных v1

**Статус:** development-only; это не statistical acceptance и не основание менять production.

## Замороженный дизайн

- Данные: 140 отчетов, seeds `1000..1019`, семь заранее выбранных correlation configurations.
- Null: independent non-circular moving-block bootstrap, `B=999`, `b=10/20`, `alpha=0.05`.
- В каждой реплике resource-вектор пересэмплируется совместно, outcome независимо; ranks строятся заново по полному ряду.
- Статистика пары: максимум `|rho|` по полному declared lag search с fixed anchors. Семейство: все 1/16 объявленных pairs.
- Итог: `q_j=max(p_j,b10,p_j,b20)`, затем один Holm по `q`; production sign/materiality остаются обязательными.
- Все v1 cases запрашивают constant target. Production его отбрасывает, поэтому `partial_rho == raw_rho`; genuine partial не проверялся.

## Исполнение и parity

- Execution status: `COMPLETE`; completed 140/140; errors 0.
- Observed parity: 59020 checks, mismatches 0.
- Runtime: 50.404 s; budget 600 s проверяется между отчетами, поэтому уже начатый report может завершиться после границы.

## До/после

| Configuration | Reports | v1 extras reports / extras / detected | b10 | b20 | max-p + Holm |
| --- | ---: | --- | --- | --- | --- |
| N02-p1-l0 | 20 | 0 / 0 / - | 0 / 0 / - | 0 / 0 / - | 0 / 0 / - |
| N02-p1-l10 | 20 | 2 / 2 / - | 2 / 2 / - | 1 / 1 / - | 1 / 1 / - |
| N02-p16-l0 | 20 | 4 / 5 / - | 3 / 3 / - | 1 / 2 / - | 1 / 1 / - |
| N02-p16-l10 | 20 | 18 / 39 / - | 4 / 5 / - | 3 / 3 / - | 3 / 3 / - |
| P01-p16-l0 | 20 | 2 / 3 / 20/20 | 2 / 2 / 20/20 | 2 / 2 / 20/20 | 2 / 2 / 20/20 |
| P02-p16-l10 | 20 | 18 / 51 / 20/20 | 1 / 1 / 20/20 | 0 / 0 / 20/20 | 0 / 0 / 20/20 |
| P03-p16-l10 | 20 | 19 / 43 / 20/20 | 7 / 8 / 20/20 | 4 / 4 / 20/20 | 3 / 3 / 20/20 |

Итого:

- Report-level extras: 63 -> 10; unrelated headlines: 143 -> 10.
- Injected detection: 60/60 -> 60/60.
- Решения b10/b20 различались в 13 отчетах.

## Ограничения

- Это повторное использование раскрытых v1 seeds для development; оценка подвержена selection bias и не независима.
- Двадцать reports на configuration не дают мощности для acceptance gate и широки для оценки редких событий.
- `B=999` дает шаг p-value 0.001. При `m=16` первый Holm cutoff 0.003125 допускает только 0, 1 или 2 превышения; пограничные решения грубы.
- Две фиксированные длины блока показывают sensitivity, но не доказывают корректность moving-block null или стационарность.
- В v1 каждый cell содержит 20 одинаковых latency; runner проверяет это и не претендует на общий HdrHistogram oracle.
- Genuine partial correlations, comparisons, mixed windows/outcomes, missingness и новый независимый seed range исключены.

## Артефакты

- Design: `build\stats-validation\correlation-pilot-v1-design.json` (`697cbc9a779595f2f047e6636bbe629ee3fc267927c78ad854c4e8ba1ad3d98b`).
- Cases: `build\stats-validation\correlation-pilot-v1-cases.jsonl` (`28c5c3cb1b66e45d11bbbac3072dad83a524e940baa351bd75603c526f6e5253`).
- Summary: `build\stats-validation\correlation-pilot-v1-summary.json`.
- Benchmark: `build\stats-validation\correlation-pilot-benchmark.json` (`089d172224ef9ef1cc76668ebc8b0fabddc11d174e7e85cc98ed88591798dbea`).

## Воспроизведение

Runner создает файлы эксклюзивно. Для replay нужны новые имена, например:

```powershell
python -m unittest tools/test_correlation_pilot.py
python tools/correlation_pilot.py freeze --corpus build/stats-validation/v1-usefulness --output-prefix build/stats-validation/correlation-pilot-v1-replay-01
python tools/correlation_pilot.py benchmark --corpus build/stats-validation/v1-usefulness --design build/stats-validation/correlation-pilot-v1-replay-01-design.json --output build/stats-validation/correlation-pilot-benchmark-replay-01.json
python tools/correlation_pilot.py run --corpus build/stats-validation/v1-usefulness --output-prefix build/stats-validation/correlation-pilot-v1-replay-01 --benchmark build/stats-validation/correlation-pilot-benchmark-replay-01.json --report docs/statistical-validation-correlation-pilot-v1-replay-01.md
```

Использован уже установленный test-only NumPy `2.4.2`; зависимости не устанавливались.

## Проверки и границы review

- Свежий `unittest`: 1 test, `OK`.
- Независимая scalar-проверка root: 102 bootstrap maxima, explicit average ranks и Pearson, 17 replicas × 2 pairs × lag `0/1/2`, tolerance `1e-12`; расхождений нет.
- Root независимо проверил hashes process truth для 140 records, пересчитал step-down Holm из сохраненных exceedances и получил те же per-configuration/overall counts; сумма parity checks 59020, cases SHA совпал с summary.
- Bounded review не выявил material correctness findings. Он не заменяет независимую acceptance, full test suite, проверку genuine partial correlation или доказательство корректности выбранного bootstrap null.

Documentation impact: добавлен только отчет development-пилота; production API, contracts, user behavior и CHANGELOG не менялись.
