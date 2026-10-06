# Correlation headline selection contract v1

`correlation-plan.v1` не получает новых input fields. При наличии pairs
`analysis-result.v1.evidence` дополнительно содержит по одному объекту на
pair/window. Семья (family) равна паре «стадия, исход»: все гипотезы с одним
`window_id` и одним `load_metric` (стадия это именованное окно
`snapshot.windows`). План делится на семьи по паре `(window_id, load_metric)`,
каждая семья отбирается отдельным вызовом селектора (ADR 0022, Д2):

```json
{
  "id": "...",
  "type": "correlation_headline_selection",
  "pair_id": "cpu-latency",
  "window_id": "steady",
  "method": "mbb-lag-max-holm.v1",
  "rng": "java-random-sha256-seed.v1",
  "status": "SELECTED",
  "family_hypotheses": 1,
  "family_count": 1,
  "bootstrap_replicates": 999,
  "block_lengths_cells": [10, 20],
  "alpha": "0.05",
  "p_value_b10": "0.001",
  "p_value_b20": "0.012",
  "max_p_value": "0.012",
  "holm_adjusted_p_value": "0.012",
  "selected": true,
  "reasons": []
}
```

`family_hypotheses` это размер семьи `m` (объявленные гипотезы, включая
недоступные), `family_count` это число семей `F` отчёта: число различных пар
`(window_id, load_metric)` в парах плана. `F` определяется планом, а не данными:
короткая, пустая или недоступная семья не меняет долю остальных. Каждая семья
проверяется на уровне `alpha / F` (граница объединения, ADR 0022, Д3), поэтому
`alpha` публикует фактический уровень семьи (`0.05 / F`, canonical decimal до 12
знаков, например `"0.016666666667"` при `F = 3`); при `F = 1` это прежнее `"0.05"`,
и результат совпадает с прежним побитно. Метод остаётся `mbb-lag-max-holm.v1`.

Decimal fields являются canonical decimal strings либо `null`. `SELECTED`
означает Holm-adjusted p `<=alpha` (уровень семьи) и прежний raw
`status=CANDIDATE`.
`NOT_SELECTED` использует `HOLM_NOT_REJECTED` или `MATERIALITY_NOT_MET`.
`UNAVAILABLE` всегда имеет `selected=false` и public p-fields `null`.

Причины `UNAVAILABLE`:

- `GENUINE_PARTIAL_UNCALIBRATED` — хотя бы один control реально использован;
- `PAIR_NOT_EVALUABLE` — observed lag-max statistic отсутствует;
- `FAMILY_SIZE_UNSUPPORTED` — family содержит больше 16 hypotheses; пустой вход
  возвращает пустой список selections раньше этой проверки, поэтому для пустой
  семьи причина не выдаётся и ни одного selection evidence не публикуется;
- `MULTI_WINDOW_FAMILY_UNSUPPORTED` — hypotheses относятся к разным windows
  (защитная проверка: ядро делит план на семьи само, поэтому в выдаче не
  встречается);
- `FAMILY_GRID_MISMATCH` — timestamps/step/complete-case masks различаются;
- `FAMILY_OUTCOME_MISMATCH` — outcome metric или values различаются (защитная
  проверка, как выше);
- `HOLM_RESOLUTION_INSUFFICIENT` — минимальный bootstrap p `1 / (B + 1) = 0.001`
  не может отклонить гипотезу на первом шаге Holm: `alpha / (F * m) < 0.001`, то
  есть `F * m > 50` при `B = 999` (при `m = 16` это четыре семьи и больше).
  Семья получает `UNAVAILABLE`, а не молча теряет мощность; рекомендуемый
  предел плана `F <= 3`;
- `OBSERVATION_COUNT_UNSUPPORTED` — меньше 30 или больше 1 920 continuous cells
  (предел поднят с 240 решением ADR 0022, Д9: 1 920 ячеек это 8 часов при шаге
  15 с; стадии длиннее по-прежнему не принимаются);
- `LAG_ANCHOR_COUNT_UNSUPPORTED` — lag вне `0..10` или anchors меньше 30;
- `BOOTSTRAP_REPLICATE_NOT_EVALUABLE` — surrogate rank correlation вырождена;
- `COMPUTATION_LIMIT_EXCEEDED` — frozen calculation одной семьи превышает
  550 105 344 correlation cell-products (`2 * 999 * сумма по гипотезам
  (2L + 1) * (N - 2L)`; это стоимость целевой формы Д9: 16 гипотез, лаг 4 ячейки,
  1 920 ячеек; раньше потолок был 150 млн). Форма 16 гипотез, лаг 10, 1 920
  ячеек (1 275 523 200) по-прежнему недоступна.

Время отчёта на длинных стадиях растёт вместе с потолком (стоимость линейна по
числу произведений), но у метода `v1` на длинных стадиях нет калибровки:
находки по уровням подвержены дрейфу ряда, а блоки 10 и 20 на рядах в восемь
раз длиннее не измерялись. Потолок действует на одну семью; отчёт из нескольких
длинных семей считает их по очереди, каждую под своим потолком.

Все declared hypotheses семьи участвуют в одном Holm этой семьи. Для unavailable hypothesis
внутреннее correction value равно `1`, даже если его public p остаётся `null`.
Family не сокращается до кандидатов.

Существующий `correlation_pair` evidence не меняется. Существующий
`correlation_candidate` finding сохраняет форму, но появляется только при
`selected=true`. Selector не меняет `policy_verdict`, не доказывает causality и
не даёт universal false-positive guarantee.

JVM использует deterministic `java.util.Random`, seeded через SHA-256. Streams
не bit-identical NumPy PCG64 из disclosed development-repeat, поэтому принятый
там диапазон шумных reports `7.7-13.2%` не является измеренной гарантией этого
port или реальных данных.
