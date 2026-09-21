# Полный disclosed прогон корреляционного отбора на раскрытых данных v1

## Решение пользователя, 2026-09-06

Эксперимент признан **удачным**. Остаточный шум принимается как известное
ограничение; дальнейшая оптимизация отложена, не блокирует продолжение
фичевого бэклога MVP. Прежние результаты и численные критерии не переписываются.

Полный development-repeat: 15000/15000 reports, 5702.097 s, errors/missing 0,
5135000 parity checks без расхождений по сохранённому summary. Отчёты с extras:
3322 -> 630; unrelated headlines: 7315 -> 661; detection: 3000/3000 -> 3000/3000.
Независимый итоговый пересчёт полного корпуса после исполнения не выполнялся.

Принятое ограничение относится к шести correlation configurations, по 1000
reports в каждой: N02 p1/L10 9.2%, N02 p16/L0 7.7%, N02 p16/L10 13.2%,
P01 7.8%, P02 11.1%, P03 11.7%. Краткое обозначение: **7-13% шумных отчётов**;
точный диапазон **7.7-13.2%**. Общие 630/15000 = 4.2% не заменяют показатели
каждой конфигурации и не дают универсальной гарантии на реальных данных.

Это принятие результата эксперимента с ограничениями, не PASS прежнего gate
<=5% по каждой configuration и не независимая acceptance на новых seeds.
Исторический USEFULNESS FAIL v1 сохраняется. Новая policy пока реализована
только в test-only runner; её подключение к продукту остаётся отдельной задачей.
SLA/verdict не менялись. Genuine partial и two-run comparisons не исследовались
новым фильтром; прежний шум T02/T03 не покрывается принятым диапазоном 7-13%.

**Статус:** development-only; это не statistical acceptance и не основание менять production.

## Замороженный дизайн

- Данные: 15000 отчетов, seeds `1000..1999`, все 15 correlation configurations v1.
- Null: independent non-circular moving-block bootstrap, `B=999`, `b=10/20`, `alpha=0.05`.
- В каждой реплике resource-вектор пересэмплируется совместно, outcome независимо; ranks строятся заново по полному ряду.
- Статистика пары: максимум `|rho|` по полному declared lag search с fixed anchors. Семейство: все 1/16 объявленных pairs.
- Итог: `q_j=max(p_j,b10,p_j,b20)`, затем один Holm по `q`; production sign/materiality остаются обязательными.
- Все v1 cases запрашивают constant target. Production его отбрасывает, поэтому `partial_rho == raw_rho`; genuine partial не проверялся.

## Исполнение и parity

- Execution status: `COMPLETE`; completed 15000/15000; errors 0.
- Observed parity: 5135000 checks, mismatches 0.
- Runtime: 5702.097 s ; budget 10800 s проверяется между отчетами, поэтому начатый report может завершиться позже.

## До/после

| Configuration | Reports | v1 extras reports / extras / detected | b10 | b20 | max-p + Holm |
| --- | ---: | --- | --- | --- | --- |
| N01-p1-l0 | 1000 | 0 / 0 / - | 0 / 0 / - | 0 / 0 / - | 0 / 0 / - |
| N01-p1-l10 | 1000 | 0 / 0 / - | 0 / 0 / - | 0 / 0 / - | 0 / 0 / - |
| N01-p16-l0 | 1000 | 0 / 0 / - | 0 / 0 / - | 0 / 0 / - | 0 / 0 / - |
| N01-p16-l10 | 1000 | 1 / 1 / - | 1 / 1 / - | 1 / 1 / - | 1 / 1 / - |
| N02-p1-l0 | 1000 | 18 / 18 / - | 18 / 18 / - | 18 / 18 / - | 18 / 18 / - |
| N02-p1-l10 | 1000 | 131 / 131 / - | 111 / 111 / - | 92 / 92 / - | 92 / 92 / - |
| N02-p16-l0 | 1000 | 278 / 323 / - | 119 / 126 / - | 89 / 94 / - | 77 / 81 / - |
| N02-p16-l10 | 1000 | 917 / 2419 / - | 236 / 258 / - | 147 / 157 / - | 132 / 138 / - |
| N03-p1-l0 | 1000 | 0 / 0 / - | 0 / 0 / - | 0 / 0 / - | 0 / 0 / - |
| N03-p1-l10 | 1000 | 0 / 0 / - | 0 / 0 / - | 0 / 0 / - | 0 / 0 / - |
| N03-p16-l0 | 1000 | 1 / 1 / - | 1 / 1 / - | 1 / 1 / - | 1 / 1 / - |
| N03-p16-l10 | 1000 | 3 / 3 / - | 3 / 3 / - | 3 / 3 / - | 3 / 3 / - |
| P01-p16-l0 | 1000 | 243 / 281 / 1000/1000 | 122 / 134 / 1000/1000 | 90 / 99 / 1000/1000 | 78 / 85 / 1000/1000 |
| P02-p16-l10 | 1000 | 869 / 2088 / 1000/1000 | 206 / 233 / 1000/1000 | 123 / 132 / 1000/1000 | 111 / 119 / 1000/1000 |
| P03-p16-l10 | 1000 | 861 / 2050 / 1000/1000 | 220 / 244 / 1000/1000 | 129 / 137 / 1000/1000 | 117 / 123 / 1000/1000 |

Итого:

- Report-level extras: 3322 -> 630; unrelated headlines: 7315 -> 661.
- Injected detection: 3000/3000 -> 3000/3000.
- Решения b10/b20 различались в 521 отчетах.

## Ограничения

- Это повторное использование раскрытых v1 seeds для development; оценка подвержена selection bias и не независима.
- 1000 reports на configuration дают полный disclosed v1 repeat, но не независимую acceptance после выбора метода по v1.
- `B=999` дает шаг p-value 0.001. При `m=16` первый Holm cutoff 0.003125 допускает только 0, 1 или 2 превышения; пограничные решения грубы.
- Две фиксированные длины блока показывают sensitivity, но не доказывают корректность moving-block null или стационарность.
- В v1 каждый cell содержит 20 одинаковых latency; runner проверяет это и не претендует на общий HdrHistogram oracle.
- Genuine partial correlations, comparisons, mixed windows/outcomes, missingness и новый независимый seed range исключены.

## Артефакты

- Design: `F:\Coding\LT-Verdict\.worktrees\local-baseline-comparison\build\stats-validation\correlation-full-v1-design.json` (`8714b095126954adf1a43bafa13e0a602797044edc3c74850691e90a32cced3b`).
- Cases: `F:\Coding\LT-Verdict\.worktrees\local-baseline-comparison\build\stats-validation\correlation-full-v1-cases.jsonl` (`50956cd71be2764bc67bcccd991fa6fcaca423b4e0b266c9e2de145e68501358`).
- Summary: `F:\Coding\LT-Verdict\.worktrees\local-baseline-comparison\build\stats-validation\correlation-full-v1-summary.json`.
- Benchmark: `F:\Coding\LT-Verdict\.worktrees\local-baseline-comparison\build\stats-validation\correlation-full-v1-benchmark.json` (`91f1b47d25068b4e0426e3bb760cef420838d539d1653281648199502113fdbb`).

Documentation impact: добавлен только отчет development-пилота; production API, contracts, user behavior и CHANGELOG не менялись.

## Scope и воспроизведение

- Включены только 15000 correlation reports. 13000 episode/comparison reports сохраняют прежние v1 результаты: новый selector к ним не применяется.
- Background PID: `61008`; command: `C:\Python314\python.exe F:\Coding\LT-Verdict\.worktrees\local-baseline-comparison\tools\correlation_full.py run --corpus F:\Coding\LT-Verdict\.worktrees\local-baseline-comparison\build\stats-validation\v1-usefulness --output-prefix F:\Coding\LT-Verdict\.worktrees\local-baseline-comparison\build\stats-validation\correlation-full-v1 --benchmark F:\Coding\LT-Verdict\.worktrees\local-baseline-comparison\build\stats-validation\correlation-full-v1-benchmark.json --prefix-check F:\Coding\LT-Verdict\.worktrees\local-baseline-comparison\build\stats-validation\correlation-full-v1-prefix-check.json --report F:\Coding\LT-Verdict\.worktrees\local-baseline-comparison\docs\statistical-validation-correlation-full-v1.md --stdout-log F:\Coding\LT-Verdict\.worktrees\local-baseline-comparison\build\stats-validation\correlation-full-v1.stdout.retry2.log --stderr-log F:\Coding\LT-Verdict\.worktrees\local-baseline-comparison\build\stats-validation\correlation-full-v1.stderr.retry2.log --process-metadata F:\Coding\LT-Verdict\.worktrees\local-baseline-comparison\build\stats-validation\correlation-full-v1-process-retry2.json`.
- Prefix consistency: `F:\Coding\LT-Verdict\.worktrees\local-baseline-comparison\build\stats-validation\correlation-full-v1-prefix-check.json` (`2b67b310827db0182c2a3643b6d1310b747c4402ff96f7559d288a0143a5581f`).

Runner создает outputs эксклюзивно; replay использует новые пути:

```powershell
python -m unittest tools/test_correlation_full.py
python tools/correlation_full.py freeze --corpus build/stats-validation/v1-usefulness --output-prefix build/stats-validation/correlation-full-v1-replay-01
python tools/correlation_full.py prefix-check --corpus build/stats-validation/v1-usefulness --design build/stats-validation/correlation-full-v1-replay-01-design.json --old-design build/stats-validation/correlation-pilot-v1-design.json --old-cases build/stats-validation/correlation-pilot-v1-cases.jsonl --output build/stats-validation/correlation-full-v1-replay-01-prefix-check.json
python tools/correlation_full.py benchmark --corpus build/stats-validation/v1-usefulness --design build/stats-validation/correlation-full-v1-replay-01-design.json --pilot-summary build/stats-validation/correlation-pilot-v1-summary.json --output build/stats-validation/correlation-full-v1-replay-01-benchmark.json
python tools/correlation_full.py run --corpus build/stats-validation/v1-usefulness --output-prefix build/stats-validation/correlation-full-v1-replay-01 --benchmark build/stats-validation/correlation-full-v1-replay-01-benchmark.json --prefix-check build/stats-validation/correlation-full-v1-replay-01-prefix-check.json --report docs/correlation-full-v1-replay-01.md --stdout-log build/stats-validation/correlation-full-v1-replay-01.stdout.log --stderr-log build/stats-validation/correlation-full-v1-replay-01.stderr.log --process-metadata build/stats-validation/correlation-full-v1-replay-01-process.json
```
