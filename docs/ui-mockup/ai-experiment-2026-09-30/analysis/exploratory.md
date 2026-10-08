# Exploratory tables (post-hoc; not part of the preregistered criterion)

## Payload form per stage

| Stage | direct | wrapped in one key | unparseable | reference outside the input (incl. wrapped) |
|---|---|---|---|---|
| S0 | 18 | 5 | 1 | 2 |
| S1 | 21 | 3 | 0 | 2 |
| S2 | 16 | 8 | 0 | 0 |

## Reviewer: responses with a defect of type T (any severity, reviewed responses)

| Type | S0 (n=23) | S1 (n=24) | S2 (n=24) |
|---|---|---|---|
| T1 | 7 | 3 | 3 |
| T2 | 3 | 5 | 8 |
| T3 | 4 | 4 | 2 |
| T4 | 5 | 0 | 4 |
| T5 | 2 | 2 | 2 |
| T6 | 0 | 0 | 0 |
| T7 | 2 | 1 | 5 |
| T8 | 4 | 3 | 2 |

## Knee dependency (reviewer T1 mentioning knee) in capacity cases K02, K03, K12

| Stage | responses with a knee defect (reviewer) |
|---|---|
| S0 | 6/6 (100%; 95% CI 54-100%) |
| S1 | 0/6 (0%; 95% CI 0-46%) |
| S2 | 0/6 (0%; 95% CI 0-46%) |

## Oracle and S4 against the reviewer (parsed responses, lenient extraction)

- Oracle (any predicate) vs reviewer hard_defect: TP=13 FP=4 FN=33 TN=21; precision=0.76 recall=0.28 (n=71)
- S4 semantic part (P-A, P-B, P-C, P-I) vs reviewer hard_defect: TP=8 FP=2 FN=38 TN=23; precision=0.80 recall=0.17 (n=71)

| Predicate | fired on responses the reviewer marks hard_defect | fired on responses the reviewer does not mark (likely oracle FP) |
|---|---|---|
| P-A | 6 | 2 |
| P-B | 0 | 0 |
| P-C | 1 | 0 |
| P-D | 2 | 1 |
| P-E | 1 | 0 |
| P-F | 3 | 0 |
| P-I | 1 | 0 |
| P-V | 0 | 2 |

## Case-level comparison with the reviewer hard_defect in place of the oracle

- S0 -> S1: cases with hard_defect 12 -> 9; improved ['K03', 'K11', 'K12']; regressed -; McNemar p=0.250
- S0 -> S2: cases with hard_defect 12 -> 10; improved ['K09', 'K12']; regressed -; McNemar p=0.500
- S1 -> S2: cases with hard_defect 9 -> 10; improved ['K09']; regressed ['K03', 'K11']; McNemar p=1.000

## Cost (usage from the responses)

| Stage | prompt_tokens sum | completion_tokens sum | of which reasoning | mean reasoning per response |
|---|---|---|---|---|
| S0 | 150694 | 158453 | 105959 | 4415 |
| S1 | 159430 | 139123 | 93322 | 3888 |
| S2 | 173692 | 141413 | 95804 | 3992 |
