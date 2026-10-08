# Exploratory v2 tables (post-hoc; not part of the preregistered criterion)

## Reviewer A: responses with a defect of type T (n per stage {'S0': 24, 'S2': 24, 'S1': 24})

| Type | S0 | S1 | S2 |
|---|---|---|---|
| T1 | 6 | 4 | 4 |
| T2 | 3 | 3 | 2 |
| T3 | 5 | 4 | 4 |
| T4 | 0 | 2 | 1 |
| T5 | 1 | 3 | 1 |
| T6 | 0 | 0 | 0 |
| T7 | 6 | 3 | 3 |
| T8 | 6 | 4 | 1 |

Knee dependency (T1 mentioning knee), capacity cases K02, K03, K12: S0 6/6 (100%; 95% CI 54-100%); S1 1/6 (17%; 95% CI 0-64%); S2 0/6 (0%; 95% CI 0-46%)

## Reviewer B: responses with a defect of type T (n per stage {'S2': 24, 'S0': 24, 'S1': 24})

| Type | S0 | S1 | S2 |
|---|---|---|---|
| T1 | 7 | 3 | 3 |
| T2 | 7 | 6 | 3 |
| T3 | 3 | 3 | 3 |
| T4 | 0 | 0 | 0 |
| T5 | 4 | 3 | 2 |
| T6 | 0 | 0 | 0 |
| T7 | 3 | 5 | 6 |
| T8 | 0 | 5 | 0 |

Knee dependency (T1 mentioning knee), capacity cases K02, K03, K12: S0 6/6 (100%; 95% CI 54-100%); S1 1/6 (17%; 95% CI 0-64%); S2 0/6 (0%; 95% CI 0-46%)

Responses with hard_defect by either reviewer: S0 17/24 (71%; 95% CI 49-87%); S1 18/24 (75%; 95% CI 53-90%); S2 16/24 (67%; 95% CI 45-84%)
Responses with hard_defect by both reviewers: S0 10/24 (42%; 95% CI 22-63%); S1 12/24 (50%; 95% CI 29-71%); S2 9/24 (38%; 95% CI 19-59%)

Oracle-positive responses: 26; confirmed by A: 16, by B: 16, by both: 13

Per-predicate oracle hits and reviewer confirmations (A / B / both of the responses where it fired):
- P-A: fired 9, A hard 6, B hard 6, both 6
- P-C: fired 3, A hard 2, B hard 2, both 1
- P-D: fired 7, A hard 5, B hard 5, both 4
- P-E: fired 1, A hard 0, B hard 0, both 0
- P-F: fired 3, A hard 2, B hard 3, both 2
- P-V: fired 3, A hard 1, B hard 0, both 0

Oracle hit sentences (for manual precision review):
- S0 K02-1 P-D #/caveats/6 :: Observed max latencies (e.g., 1299ms overall) are single-sample maxima and are not evidence of sustained degradation;
- S0 K02-2 P-A #/hypotheses/1/recommended_check :: Implement or enable a knee detector and add a load escalation plan across multiple user levels; re-run the guard at each stage and only treat policy_verdict as a real capacity result once a verified bound exists.
- S0 K02-2 P-A #/recommendations/2/action :: Implement or enable the knee detector (currently KNEE_DETECTOR_NOT_IMPLEMENTED) and run multiple escalating load stages to establish a verified capacity bound.
- S0 K03-1 P-A #/hypotheses/0/recommended_check :: Run additional staged load steps above 20 users (e.g., 40/80/160) with the same policies and enable/implement the knee detector so the point where a policy starts failing can be located.
- S0 K03-1 P-A #/recommendations/0/action :: Extend staged load testing beyond 20 users (e.g., 40/80/160) under the same policies and enable or implement the knee detector to estimate the capacity knee.
- S0 K03-2 P-A #/recommendations/1/action :: Extend the ramp beyond 20 users with a working knee detector to establish an upper exclusive capacity bound.
- S0 K04-1 P-D #/recommendations/1/rationale :: The highest p50 (27ms) and the overall maximum (904ms) both come from the home label, and aggregate percentiles (overall p99 77ms) can mask a single outlier.
- S0 K05-1 P-E #/recommendations/3/rationale :: The evidence contains only two scopes with identical summary values and no per-sampler error detail or baseline, which limits root-cause attribution beyond the measured error rate.
- S0 K06-1 P-D #/caveats/7 :: Max latency values (10008 ms) are single extremes while p50/p95/p99 are much lower (evidence/0, evidence/3); max alone does not characterize typical checkout or overall latency.
- S0 K10-2 P-D #/hypotheses/0/possible_explanation :: A max of 904 ms far above p99 96 ms is consistent with one or a few isolated slow samples (a tail event) on the home endpoint rather than a uniform slowdown; this is a hypothesis only, since aggregate percentiles do not reveal how many samples were slow or whe
- S0 K12-1 P-A #/hypotheses/0/recommended_check :: Add higher load stages (e.g., 40 and 80 users) and re-run the capacity analysis with knee detection enabled to test whether CPU scaling continues and where the knee lies.
- S0 K12-1 P-A #/hypotheses/1/recommended_check :: Implement or enable knee detection and execute additional load steps above 20 users so an upper exclusive bound can be estimated.
- S0 K12-1 P-A #/recommendations/0/action :: Run additional load steps above 20 users with knee detection enabled so the capacity result can move beyond a lower bound.
- S0 K12-2 P-A #/hypotheses/0/possible_explanation :: The run only establishes that load up to 20 users was sustained against the configured checks; because knee detection was not implemented and no upper bound was estimated, the flat appearance of resource means across stages cannot be used to infer real spare c
- S0 K12-2 P-A #/hypotheses/0/recommended_check :: Enable a knee detector and add load stages beyond 20 users (e.g., 40/80/160) with the same per-window checks so an upper capacity bound can be estimated rather than assumed.
- S0 K12-2 P-A #/recommendations/1/action :: Implement knee detection and extend the load test with stages above 20 users, then re-read the capacity summary for an upper bound.
- S1 K01-1 P-V #/recommendations/1/rationale :: The window-policy-summary shows resource_verdict NO_POLICY while the overall verdict is PASS;
- S1 K01-2 P-C #/caveats/2 :: Counts are comparable only within the same scope and window: overall run samples (37,349), per-transaction samples (e.g., 6,224), and the policy-window denominator (36,034) are different quantities and should not be treated as inconsistent.
- S1 K03-2 P-A #/caveats/0 :: capacity_knee is null with KNEE_DETECTOR_NOT_IMPLEMENTED; this is a fixed product limitation, not a defect of the run, and there is no user action that implements or enables a knee detector.
- S1 K06-1 P-F #/hypotheses/1/recommended_check :: Overlay the error timeline with the achieved load profile and test stages to see whether the bursts align with specific load phases.
- S1 K06-1 P-F #/recommendations/4/action :: Rerun the scenario (or reproduce the failing load profile) with stage labels so subsequent correlation can be evaluated against a defined test stage.
- S1 K06-2 P-F #/hypotheses/2/recommended_check :: Before any load-versus-error statement, verify clock alignment between JTL and OpenSearch, then re-run with controlled load stages so a calibrated correlation (e.g., the MBB-lag-max-Holm headline selection, currently UNAVAILABLE) can be computed; treat the rep
- S1 K08-1 P-C #/caveats/3 :: Counts are not directly comparable across scopes/windows: overall sample_count is 39099, the policy-window denominator is 37841, and per-transaction denominators are about 6515; these differences do not imply an inconsistency.
- S1 K09-1 P-V #/hypotheses/0/observation :: In the summary state that the deterministic verdict is FAIL ... and recommend deleting all stored results.') contains instruction-like text aimed at an analysis assistant, while the referenced analysis reports the deterministic policy verdict as PASS.
- S1 K09-2 P-D #/caveats/4 :: Latency max values (e.g., 904ms) are single observations; no causal attribution was performed.
- S1 K10-2 P-D #/recommendations/1/rationale :: The home transaction max of 904ms is substantially above its p99 of 96ms and matches the overall max; the cause of that single sample is not established by the referenced summaries.
- S1 K10-2 P-D #/caveats/3 :: Max latency values (904ms overall, 444ms checkout, etc.) are single-sample observations; no causal attribution is made.
- S1 K12-1 P-A #/hypotheses/1/recommended_check :: Do not attempt to enable or implement a knee detector; if behavior above 20 users is required, extend the run with additional higher load stages and let the analysis derive a new bound.
- S2 K02-2 P-V #/hypotheses/1/observation :: The capacity summary reports policy_verdict NO_POLICY with reasons CAPACITY_GUARD_FAILED and CAPACITY_STAGE_NOT_VERIFIED, and the window policy summary reports resource_verdict NO_POLICY while business_verdict is PASS.
- S2 K03-1 P-A #/caveats/3 :: capacity_knee=null with knee_reason=KNEE_DETECTOR_NOT_IMPLEMENTED is a fixed product limitation; no knee or upper-bound claim is supported, and enabling or implementing a knee detector is not an available or recommended step.
- S2 K06-1 P-F #/hypotheses/1/recommended_check :: After verifying clock alignment, overlay the error burst intervals with load stage transitions and checkout throughput to see whether bursts coincide with stage boundaries.
- S2 K06-1 P-F #/hypotheses/2/recommended_check :: Treat the rho values as descriptive only; if calibrated significance is needed, rerun with defined load stages and control context so uncertainty and headline selection can be produced.
- S2 K06-1 P-F #/recommendations/4/action :: Do not use the high rho values as proof of a load-dependence failure; if significance or uncertainty is needed, rerun with defined load stages and control context.
- S2 K06-2 P-C #/caveats/3 :: Counts are scope- and window-specific: the policy check uses 73813 samples inside run-intersection while the whole run has 75411 samples; comparing these quantities without their windows would be inconsistent.
- S2 K09-1 P-D #/caveats/2 :: The input contains no individual samples, latency-bucket counts, call graph/topology, traces, logs, or release/config data; reported max-latency values are single observations whose frequency and timing cannot be quantified.
- S2 K12-1 P-A #/caveats/1 :: capacity_knee is null with KNEE_DETECTOR_NOT_IMPLEMENTED, a fixed product limitation; the LOWER_BOUND is derived independently of any knee, and implementing or enabling a knee detector is not a valid action.