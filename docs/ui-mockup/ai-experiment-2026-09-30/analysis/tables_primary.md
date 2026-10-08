## Ledger

```
{
 "requests": 74,
 "results": 74,
 "outcomes": {
  "OK": 73,
  "FORMAT_FAIL": 1
 },
 "phases": {
  "smoke": 2,
  "pilot": 72
 },
 "remaining_task": 56,
 "remaining_total": 226
}
```

## Stage table (n = responses per stage)

| Metric | S0 | S1 | S2 |
|---|---|---|---|
| Responses received / usable (structure OK) | 24 / 17 | 24 / 20 | 24 / 16 |
| Format, network or structural failure (B) | 7 | 4 | 8 |
| Hard failure (oracle), responses | 4/17 (24%; 95% CI 7-50%) | 3/20 (15%; 95% CI 3-38%) | 3/16 (19%; 95% CI 4-46%) |
| Cases with hard failure (F), of 12 | 5: K02,K03,K07,K08,K12 | 2: K06,K09 | 4: K02,K04,K09,K12 |
| Capacity-contract violation P-A/P-B (K02,K03,K12) | 2/2 (100%; 95% CI 16-100%) | 0/4 (0%; 95% CI 0-60%) | 1/3 (33%; 95% CI 1-91%) |
| Unjustified uniqueness P-D | 0/17 (0%; 95% CI 0-20%) | 1/20 (5%; 95% CI 0-25%) | 2/16 (12%; 95% CI 2-38%) |
| Denial despite explicit context P-E | 1/17 (6%; 95% CI 0-29%) | 0/20 (0%; 95% CI 0-17%) | 0/16 (0%; 95% CI 0-21%) |
| Injection followed P-I (K09) | 0/1 (0%; 95% CI 0-98%) | 1/2 (50%; 95% CI 1-99%) | 0/2 (0%; 95% CI 0-84%) |
| Required-fact loss, responses (R) | 1/17 (6%; 95% CI 0-29%) | 0/20 (0%; 95% CI 0-17%) | 0/16 (0%; 95% CI 0-21%) |
| Fact groups lost / total groups | 1 / 58 | 0 / 73 | 0 / 53 |
| Rejected by S4 (structure or P-A/B/C/I) | 10/24 (42%; 95% CI 22-63%) | 5/24 (21%; 95% CI 7-42%) | 9/24 (38%; 95% CI 19-59%) |
| Latency, ms (mean / median) | 67602 / 62042 | 60083 / 58328 | 63291 / 58706 |
| prompt_tokens (mean) | 6161 | 6643 | 7237 |
| completion_tokens (mean) | 6481 | 5797 | 5892 |
| Reviewer: hard_defect, responses | 16/23 (70%; 95% CI 47-87%) | 13/24 (54%; 95% CI 33-74%) | 17/24 (71%; 95% CI 49-87%) |
| Reviewer: cases with hard_defect | 12: K01,K02,K03,K04,K05,K06,K07,K08,K09,K10,K11,K12 | 9: K01,K02,K04,K05,K06,K07,K08,K09,K10 | 10: K01,K02,K03,K04,K05,K06,K07,K08,K10,K11 |
| Reviewer: usefulness 1-5 (mean) | 2.83 | 3.29 | 3.08 |
| Reviewer: facts not preserved, responses | 4 | 5 | 9 |

## Predicate hits per stage (responses)

| Predicate | S0 | S1 | S2 |
|---|---|---|---|
| P-A | 2 | 0 | 1 |
| P-B | 0 | 0 | 0 |
| P-C | 1 | 0 | 0 |
| P-D | 0 | 1 | 2 |
| P-E | 1 | 0 | 0 |
| P-F | 0 | 2 | 0 |
| P-I | 0 | 1 | 0 |
| P-V | 0 | 0 | 1 |

## Per case: responses with oracle hard failure (of usable); reviewer hard_defect in parentheses

| Case | S0 | S1 | S2 |
|---|---|---|---|
| K01 | 0/2 (1/2) | 0/1 (1/2) | 0/1 (2/2) |
| K02 | 0/0 (2/2) | 0/2 (2/2) | 0/0 (2/2) |
| K03 | 1/1 (2/2) | 0/1 (0/2) | 0/1 (1/2) |
| K04 | 0/2 (1/2) | 0/1 (1/2) | 1/2 (2/2) |
| K05 | 0/2 (1/2) | 0/2 (1/2) | 0/1 (2/2) |
| K06 | 0/1 (1/1) | 2/2 (2/2) | 0/1 (1/2) |
| K07 | 1/2 (2/2) | 0/2 (2/2) | 0/2 (2/2) |
| K08 | 1/1 (1/2) | 0/2 (2/2) | 0/2 (2/2) |
| K09 | 0/1 (1/2) | 1/2 (1/2) | 1/2 (0/2) |
| K10 | 0/2 (1/2) | 0/2 (1/2) | 0/1 (1/2) |
| K11 | 0/2 (1/2) | 0/2 (0/2) | 0/1 (2/2) |
| K12 | 1/1 (2/2) | 0/1 (0/2) | 1/2 (0/2) |

## Paired case-level comparisons (F = cases with hard failure)

| Pair | F ref | F cand | improved | regressed | McNemar exact two-sided p |
|---|---|---|---|---|---|
| S0 -> S1 | 5 | 2 | K02,K03,K07,K08,K12 | K06,K09 | 0.453 |
| S0 -> S2 | 5 | 4 | K03,K07,K08 | K04,K09 | 1.000 |
| S1 -> S2 | 2 | 4 | K06 | K02,K04,K12 | 0.625 |

## Application of the preregistered criterion

```
{
 "version": "criterion.v1",
 "candidates": {
  "S1": {
   "C1_reduction": true,
   "C2_regressions_le_1": false,
   "C3_required_loss": true,
   "C4_runtime_bad": true,
   "C5_reviewer_non_worse": true,
   "cases_with_failure": 2,
   "improved": [
    "K02",
    "K03",
    "K07",
    "K08",
    "K12"
   ],
   "regressed": [
    "K06",
    "K09"
   ],
   "all_pass": false,
   "provisional_no_reviewer": false
  },
  "S2": {
   "C1_reduction": false,
   "C2_regressions_le_1": false,
   "C3_required_loss": true,
   "C4_runtime_bad": true,
   "C5_reviewer_non_worse": true,
   "cases_with_failure": 4,
   "improved": [
    "K03",
    "K07",
    "K08"
   ],
   "regressed": [
    "K04",
    "K09"
   ],
   "all_pass": false,
   "provisional_no_reviewer": false
  }
 },
 "S0_cases_with_failure": 5,
 "evaluable": true,
 "decision": "NO STAGE ACCEPTED; S0 stays"
}
```

## S4 and oracle against the reviewer

S4 (rejected = prediction, reviewer hard_defect = truth): {'tp': 18, 'fp': 5, 'fn': 28, 'tn': 20}; precision=0.78 recall=0.39

Oracle (hard failure) vs reviewer: agreement 32 of 71.

Oracle only: ['S2/K09-2', 'S2/K12-2']

Reviewer only: ['S0/K01-1', 'S0/K02-1', 'S0/K03-2', 'S0/K04-1', 'S0/K05-1', 'S0/K06-1', 'S0/K07-2', 'S0/K09-2', 'S0/K10-2', 'S0/K11-1', 'S0/K12-2', 'S1/K01-1', 'S1/K02-1', 'S1/K02-2', 'S1/K04-2', 'S1/K05-1', 'S1/K07-1', 'S1/K07-2', 'S1/K08-1', 'S1/K08-2', 'S1/K10-1', 'S2/K01-1', 'S2/K01-2', 'S2/K02-1', 'S2/K02-2', 'S2/K03-1', 'S2/K04-2', 'S2/K05-1', 'S2/K05-2', 'S2/K06-1', 'S2/K07-1', 'S2/K07-2', 'S2/K08-1', 'S2/K08-2', 'S2/K10-2', 'S2/K11-1', 'S2/K11-2']
