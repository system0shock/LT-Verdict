# Correlation headline selection contract v1

`correlation-plan.v1` не получает новых input fields. При наличии pairs
`analysis-result.v1.evidence` дополнительно содержит по одному объекту на
pair/window:

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

Decimal fields являются canonical decimal strings либо `null`. `SELECTED`
означает Holm-adjusted p `<=0.05` и прежний raw `status=CANDIDATE`.
`NOT_SELECTED` использует `HOLM_NOT_REJECTED` или `MATERIALITY_NOT_MET`.
`UNAVAILABLE` всегда имеет `selected=false` и public p-fields `null`.

Причины `UNAVAILABLE`:

- `GENUINE_PARTIAL_UNCALIBRATED` — хотя бы один control реально использован;
- `PAIR_NOT_EVALUABLE` — observed lag-max statistic отсутствует;
- `FAMILY_SIZE_UNSUPPORTED` — family пуста или содержит больше 16 hypotheses;
- `MULTI_WINDOW_FAMILY_UNSUPPORTED` — hypotheses относятся к разным windows;
- `FAMILY_GRID_MISMATCH` — timestamps/step/complete-case masks различаются;
- `FAMILY_OUTCOME_MISMATCH` — outcome metric или values различаются;
- `OBSERVATION_COUNT_UNSUPPORTED` — меньше 30 или больше 240 continuous cells;
- `LAG_ANCHOR_COUNT_UNSUPPORTED` — lag вне `0..10` или anchors меньше 30;
- `BOOTSTRAP_REPLICATE_NOT_EVALUABLE` — surrogate rank correlation вырождена;
- `COMPUTATION_LIMIT_EXCEEDED` — frozen calculation превышает 150 млн
  correlation cell-products.

Все declared hypotheses участвуют в одном Holm. Для unavailable hypothesis
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
