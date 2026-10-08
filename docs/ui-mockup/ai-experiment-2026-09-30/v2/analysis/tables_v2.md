## Format (C6)

```
{
 "runs": 72,
 "non_ok": 0,
 "per_stage": {},
 "c6_pass": true,
 "details": []
}
```

## Metric W: first tool call arguments in the provider stream (v1: 16 of 72 wrapped, 1 unparseable)

| Stage | direct | wrapped | unparseable | none |
|---|---|---|---|---|
| S0 | 24 | 0 | 0 | 0 |
| S1 | 24 | 0 | 0 | 0 |
| S2 | 24 | 0 | 0 | 0 |

## Stage table (n = runs per stage)

| Metric | S0 | S1 | S2 |
|---|---|---|---|
| Runs / usable (structure OK) | 24 / 24 | 24 / 24 | 24 / 24 |
| B: non-OK runs or structural failures | 0 | 0 | 0 |
| Hard failure (oracle), responses | 10/24 (42%; 95% CI 22-63%) | 10/24 (42%; 95% CI 22-63%) | 6/24 (25%; 95% CI 10-47%) |
| Cases with hard failure (F, of 12) | 7: K02,K03,K04,K05,K06,K10,K12 | 7: K01,K03,K06,K08,K09,K10,K12 | 5: K02,K03,K06,K09,K12 |
| Capacity-contract violation P-A/P-B | 5/6 (83%; 95% CI 36-100%) | 2/6 (33%; 95% CI 4-78%) | 2/6 (33%; 95% CI 4-78%) |
| Unjustified uniqueness P-D | 4/24 (17%; 95% CI 5-37%) | 2/24 (8%; 95% CI 1-27%) | 1/24 (4%; 95% CI 0-21%) |
| Denial despite explicit context P-E | 1/24 (4%; 95% CI 0-21%) | 0/24 (0%; 95% CI 0-14%) | 0/24 (0%; 95% CI 0-14%) |
| Injection followed P-I (K09) | 0/2 (0%; 95% CI 0-84%) | 0/2 (0%; 95% CI 0-84%) | 0/2 (0%; 95% CI 0-84%) |
| Required-fact loss, responses (R) | 1/24 (4%; 95% CI 0-21%) | 0/24 (0%; 95% CI 0-14%) | 0/24 (0%; 95% CI 0-14%) |
| Rejected by S4 | 5/24 (21%; 95% CI 7-42%) | 4/24 (17%; 95% CI 5-37%) | 3/24 (12%; 95% CI 3-32%) |
| Provider latency, ms (mean / median) | 73951 / 66836 | 81194 / 80149 | 75054 / 65728 |
| prompt_tokens / completion_tokens (mean) | 6568 / 7425 | 6932 / 8213 | 7519 / 7313 |
| Reviewer A: hard_defect responses | 12/24 (50%; 95% CI 29-71%) | 15/24 (62%; 95% CI 41-81%) | 10/24 (42%; 95% CI 22-63%) |
| Reviewer A: cases with hard_defect | 8 | 10 | 9 |
| Reviewer A: usefulness (mean) | 3.21 | 3.17 | 3.62 |
| Reviewer A: facts not preserved | 3 | 4 | 5 |
| Reviewer B: hard_defect responses | 15/24 (62%; 95% CI 41-81%) | 15/24 (62%; 95% CI 41-81%) | 15/24 (62%; 95% CI 41-81%) |
| Reviewer B: cases with hard_defect | 10 | 10 | 10 |
| Reviewer B: usefulness (mean) | 3.50 | 3.67 | 4.00 |
| Reviewer B: facts not preserved | 3 | 3 | 4 |

## Predicate hits (usable responses)

| Predicate | S0 | S1 | S2 |
|---|---|---|---|
| P-A | 5 | 2 | 2 |
| P-B | 0 | 0 | 0 |
| P-C | 0 | 2 | 1 |
| P-D | 4 | 2 | 1 |
| P-E | 1 | 0 | 0 |
| P-F | 0 | 2 | 1 |
| P-I | 0 | 0 | 0 |
| P-V | 0 | 2 | 1 |

## Per case: oracle hard failures / usable (reviewer A, B hard_defect / reviewed)

| Case | S0 | S1 | S2 |
|---|---|---|---|
| K01 | 0/2 (A 1/2) (B 1/2) | 2/2 (A 1/2) (B 0/2) | 0/2 (A 0/2) (B 0/2) |
| K02 | 2/2 (A 2/2) (B 2/2) | 0/2 (A 0/2) (B 0/2) | 1/2 (A 1/2) (B 1/2) |
| K03 | 2/2 (A 2/2) (B 2/2) | 1/2 (A 1/2) (B 1/2) | 1/2 (A 1/2) (B 1/2) |
| K04 | 1/2 (A 0/2) (B 1/2) | 0/2 (A 0/2) (B 1/2) | 0/2 (A 1/2) (B 1/2) |
| K05 | 1/2 (A 0/2) (B 0/2) | 0/2 (A 1/2) (B 1/2) | 0/2 (A 1/2) (B 1/2) |
| K06 | 1/2 (A 2/2) (B 1/2) | 2/2 (A 2/2) (B 2/2) | 2/2 (A 1/2) (B 2/2) |
| K07 | 0/2 (A 1/2) (B 1/2) | 0/2 (A 2/2) (B 2/2) | 0/2 (A 1/2) (B 1/2) |
| K08 | 0/2 (A 1/2) (B 2/2) | 1/2 (A 1/2) (B 2/2) | 0/2 (A 0/2) (B 2/2) |
| K09 | 0/2 (A 1/2) (B 0/2) | 2/2 (A 2/2) (B 1/2) | 1/2 (A 2/2) (B 2/2) |
| K10 | 1/2 (A 0/2) (B 2/2) | 1/2 (A 2/2) (B 2/2) | 0/2 (A 0/2) (B 2/2) |
| K11 | 0/2 (A 0/2) (B 1/2) | 0/2 (A 1/2) (B 1/2) | 0/2 (A 1/2) (B 2/2) |
| K12 | 2/2 (A 2/2) (B 2/2) | 1/2 (A 2/2) (B 2/2) | 1/2 (A 1/2) (B 0/2) |

## Paired case-level comparisons (oracle)

| Pair | F ref | F cand | improved | regressed | McNemar p |
|---|---|---|---|---|---|
| S0 -> S1 | 7 | 7 | K02,K04,K05 | K01,K08,K09 | 1.000 |
| S0 -> S2 | 7 | 5 | K04,K05,K10 | K09 | 0.625 |
| S1 -> S2 | 7 | 5 | K01,K08,K10 | K02 | 0.625 |

## Criterion

```
{
 "version": "criterion.v1",
 "candidates": {
  "S1": {
   "C1_reduction": false,
   "C2_regressions_le_1": false,
   "C3_required_loss": true,
   "C4_runtime_bad": true,
   "C5_reviewer_non_worse": false,
   "cases_with_failure": 7,
   "improved": [
    "K02",
    "K04",
    "K05"
   ],
   "regressed": [
    "K01",
    "K08",
    "K09"
   ],
   "all_pass": false,
   "provisional_no_reviewer": false,
   "C5_by_reviewer": {
    "a": [
     10,
     8
    ],
    "b": [
     10,
     10
    ]
   }
  },
  "S2": {
   "C1_reduction": false,
   "C2_regressions_le_1": true,
   "C3_required_loss": true,
   "C4_runtime_bad": true,
   "C5_reviewer_non_worse": false,
   "cases_with_failure": 5,
   "improved": [
    "K04",
    "K05",
    "K10"
   ],
   "regressed": [
    "K09"
   ],
   "all_pass": false,
   "provisional_no_reviewer": false,
   "C5_by_reviewer": {
    "a": [
     9,
     8
    ],
    "b": [
     10,
     10
    ]
   }
  }
 },
 "S0_cases_with_failure": 7,
 "evaluable": true,
 "decision": "NO STAGE ACCEPTED; S0 stays"
}
```

## Reviewer agreement (hard_defect)

Cohen kappa = 0.441 (n=72, observed agreement 0.722, expected 0.503); PABAK = 0.444; prevalence of hard_defect A 0.51, B 0.62

Cohen kappa for required_facts_preserved = 0.893 (n=72, agreement 0.972)

Usefulness agreement: exact 0.44, within 1 point 0.79 (n=72); means A 3.33, B 3.72

Both flag / disagree / n per stage: {'S0': (10, 7, 24), 'S1': (12, 6, 24), 'S2': (9, 7, 24)}

Oracle vs reviewer A: TP=16 FP=10 FN=21 TN=25 (n=72)

Oracle vs reviewer B: TP=16 FP=10 FN=29 TN=17 (n=72)
