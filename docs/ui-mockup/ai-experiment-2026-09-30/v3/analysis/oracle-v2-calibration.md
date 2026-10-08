# Oracle v1 against v2 on the pilot responses (offline, 0 requests)

Generated 2026-10-06. Oracle file sha256 f96de9a3b7b80ed5ac6bf5e1d464087451854a959f960eb5029b0bd04f0ef913.
Sets: D = v1 (reviewer R1) + v2 (reviewers A, B), in-sample for the predicates; T = v3 (reviewers A, B), other models, same cases K01-K12 (possible indirect leakage). Not a case-held-out estimate; the holdout gives that.

## Set D

- v1 (v1), oracle v1: responses 71, hits 29, per predicate {'P-A': 15, 'P-C': 1, 'P-D': 3, 'P-E': 1, 'P-F': 6, 'P-I': 1, 'P-V': 2}
  - reviewer R1: sentence level confirmed 10 of 29 (precision 0.34; by defect type 17, 0.59); listed defects matched 10 of 79 (recall 0.13); response level vs hard_defect TP=13 FP=4 FN=33 TN=21 (precision 0.76, recall 0.28)
- v1 (v1), oracle v2: responses 71, hits 21, per predicate {'P-A': 13, 'P-C': 1, 'P-D': 3, 'P-E': 1, 'P-I': 1, 'P-V': 2}
  - reviewer R1: sentence level confirmed 10 of 21 (precision 0.48; by defect type 15, 0.71); listed defects matched 10 of 79 (recall 0.13); response level vs hard_defect TP=10 FP=2 FN=36 TN=23 (precision 0.83, recall 0.22)
- v2 (v2), oracle v1: responses 72, hits 36, per predicate {'P-A': 15, 'P-C': 3, 'P-D': 8, 'P-E': 1, 'P-F': 6, 'P-V': 3}
  - reviewer A: sentence level confirmed 13 of 36 (precision 0.36; by defect type 19, 0.53); listed defects matched 13 of 74 (recall 0.18); response level vs hard_defect TP=16 FP=10 FN=21 TN=25 (precision 0.62, recall 0.43)
  - reviewer B: sentence level confirmed 14 of 36 (precision 0.39; by defect type 17, 0.47); listed defects matched 14 of 76 (recall 0.18); response level vs hard_defect TP=16 FP=10 FN=29 TN=17 (precision 0.62, recall 0.36)
  - both reviewers: hits confirmed by both 12, not confirmed by both 24, confirmed by neither 21 (of 36); by defect type 16, 20, 16; response level vs hard_defect of both TP=13 FP=13 FN=18 TN=28
- v2 (v2), oracle v2: responses 72, hits 23, per predicate {'P-A': 11, 'P-D': 8, 'P-E': 1, 'P-F': 2, 'P-V': 1}
  - reviewer A: sentence level confirmed 13 of 23 (precision 0.57; by defect type 18, 0.78); listed defects matched 13 of 74 (recall 0.18); response level vs hard_defect TP=12 FP=4 FN=25 TN=31 (precision 0.75, recall 0.32)
  - reviewer B: sentence level confirmed 14 of 23 (precision 0.61; by defect type 16, 0.70); listed defects matched 14 of 76 (recall 0.18); response level vs hard_defect TP=12 FP=4 FN=33 TN=23 (precision 0.75, recall 0.27)
  - both reviewers: hits confirmed by both 12, not confirmed by both 11, confirmed by neither 8 (of 23); by defect type 16, 7, 5; response level vs hard_defect of both TP=10 FP=6 FN=21 TN=35
- D, responses with two reviewers, oracle v1: responses 72, hits 36, per predicate {'P-A': 15, 'P-C': 3, 'P-D': 8, 'P-E': 1, 'P-F': 6, 'P-V': 3}
  - reviewer A: sentence level confirmed 13 of 36 (precision 0.36; by defect type 19, 0.53); listed defects matched 13 of 74 (recall 0.18); response level vs hard_defect TP=16 FP=10 FN=21 TN=25 (precision 0.62, recall 0.43)
  - reviewer B: sentence level confirmed 14 of 36 (precision 0.39; by defect type 17, 0.47); listed defects matched 14 of 76 (recall 0.18); response level vs hard_defect TP=16 FP=10 FN=29 TN=17 (precision 0.62, recall 0.36)
  - both reviewers: hits confirmed by both 12, not confirmed by both 24, confirmed by neither 21 (of 36); by defect type 16, 20, 16; response level vs hard_defect of both TP=13 FP=13 FN=18 TN=28
- D, responses with two reviewers, oracle v2: responses 72, hits 23, per predicate {'P-A': 11, 'P-D': 8, 'P-E': 1, 'P-F': 2, 'P-V': 1}
  - reviewer A: sentence level confirmed 13 of 23 (precision 0.57; by defect type 18, 0.78); listed defects matched 13 of 74 (recall 0.18); response level vs hard_defect TP=12 FP=4 FN=25 TN=31 (precision 0.75, recall 0.32)
  - reviewer B: sentence level confirmed 14 of 23 (precision 0.61; by defect type 16, 0.70); listed defects matched 14 of 76 (recall 0.18); response level vs hard_defect TP=12 FP=4 FN=33 TN=23 (precision 0.75, recall 0.27)
  - both reviewers: hits confirmed by both 12, not confirmed by both 11, confirmed by neither 8 (of 23); by defect type 16, 7, 5; response level vs hard_defect of both TP=10 FP=6 FN=21 TN=35

Fitness of v2 against v1 on D (two reviewers): confirmed by both 12 >= 12: True; not confirmed by both 11 <= 24: True; confirmed by neither 8 <= 21: True.

Hits dropped by v2: 21 (confirmed by a reviewer: 0); added: 0 (confirmed: 0).

- dropped S1-K03-2 P-A #/caveats/1 confirmed by - :: capacity_knee is null with knee_reason KNEE_DETECTOR_NOT_IMPLEMENTED, a fixed product limitation; no user action or recommendation can enable knee detection, and the lower bound is derived from verifi
- dropped S1-K06-1 P-F #/hypotheses/2/recommended_check confirmed by - :: Rerun or re-analyze with load stage context and target controls so that a calibrated significance estimate can be produced before drawing scaling conclusions.
- dropped S1-K06-1 P-F #/recommendations/4/action confirmed by - :: Rerun or re-analyze with load stage context and target controls to allow calibrated interpretation of the error-versus-load relationship.
- dropped S1-K06-2 P-F #/hypotheses/2/recommended_check confirmed by - :: Rerun with controlled load stages and verified clock alignment to test whether error rate depends on load.
- dropped S1-K06-2 P-F #/recommendations/0/action confirmed by - :: Rerun the test with controlled load stages and verified clock alignment to check whether the 3688-error pattern and its burst timing are reproducible.
- dropped S2-K06-1 P-F #/hypotheses/1/recommended_check confirmed by - :: Provide load stage definitions and a genuine control context, or rerun with annotated stages, so the correlation can be tested with calibrated significance.
- dropped S2-K06-1 P-F #/recommendations/2/action confirmed by - :: Add load stage definitions and control context (or rerun with annotated stages) so the errors-vs-load correlation can be evaluated with calibrated significance.
- dropped S2-K12-2 P-A #/caveats/1 confirmed by - :: This is a fixed product limitation, not a defect of the run; the LOWER_BOUND is computed independently of any knee, and no knee-detector user action is available or recommended.
- dropped S1-K01-1 P-V #/recommendations/1/rationale confirmed by - :: The window-policy-summary shows resource_verdict NO_POLICY while the overall verdict is PASS;
- dropped S1-K01-2 P-C #/caveats/2 confirmed by - :: Counts are comparable only within the same scope and window: overall run samples (37,349), per-transaction samples (e.g., 6,224), and the policy-window denominator (36,034) are different quantities an
- dropped S1-K03-2 P-A #/caveats/0 confirmed by - :: capacity_knee is null with KNEE_DETECTOR_NOT_IMPLEMENTED; this is a fixed product limitation, not a defect of the run, and there is no user action that implements or enables a knee detector.
- dropped S1-K06-1 P-F #/recommendations/4/action confirmed by - :: Rerun the scenario (or reproduce the failing load profile) with stage labels so subsequent correlation can be evaluated against a defined test stage.
- dropped S1-K06-2 P-F #/hypotheses/2/recommended_check confirmed by - :: Before any load-versus-error statement, verify clock alignment between JTL and OpenSearch, then re-run with controlled load stages so a calibrated correlation (e.g., the MBB-lag-max-Holm headline sele
- dropped S1-K08-1 P-C #/caveats/3 confirmed by - :: Counts are not directly comparable across scopes/windows: overall sample_count is 39099, the policy-window denominator is 37841, and per-transaction denominators are about 6515; these differences do n
- dropped S1-K12-1 P-A #/hypotheses/1/recommended_check confirmed by - :: Do not attempt to enable or implement a knee detector; if behavior above 20 users is required, extend the run with additional higher load stages and let the analysis derive a new bound.
- dropped S2-K02-2 P-V #/hypotheses/1/observation confirmed by - :: The capacity summary reports policy_verdict NO_POLICY with reasons CAPACITY_GUARD_FAILED and CAPACITY_STAGE_NOT_VERIFIED, and the window policy summary reports resource_verdict NO_POLICY while busines
- dropped S2-K03-1 P-A #/caveats/3 confirmed by - :: capacity_knee=null with knee_reason=KNEE_DETECTOR_NOT_IMPLEMENTED is a fixed product limitation; no knee or upper-bound claim is supported, and enabling or implementing a knee detector is not an avail
- dropped S2-K06-1 P-F #/hypotheses/2/recommended_check confirmed by - :: Treat the rho values as descriptive only; if calibrated significance is needed, rerun with defined load stages and control context so uncertainty and headline selection can be produced.
- dropped S2-K06-1 P-F #/recommendations/4/action confirmed by - :: Do not use the high rho values as proof of a load-dependence failure; if significance or uncertainty is needed, rerun with defined load stages and control context.
- dropped S2-K06-2 P-C #/caveats/3 confirmed by - :: Counts are scope- and window-specific: the policy check uses 73813 samples inside run-intersection while the whole run has 75411 samples; comparing these quantities without their windows would be inco
- dropped S2-K12-1 P-A #/caveats/1 confirmed by - :: capacity_knee is null with KNEE_DETECTOR_NOT_IMPLEMENTED, a fixed product limitation; the LOWER_BOUND is derived independently of any knee, and implementing or enabling a knee detector is not a valid 

**Re-measurement of T: the first measurement used oracle 320997e850debb9fe748a83b6ee5ddb7d7a2c6e5c5a7d5a4250fc50caf13a309 (data None).**

## Set T

- v3 (glm-5.3), oracle v1: responses 31, hits 15, per predicate {'P-A': 6, 'P-B': 1, 'P-C': 3, 'P-D': 1, 'P-V': 4}
  - reviewer A: sentence level confirmed 3 of 15 (precision 0.20; by defect type 6, 0.40); listed defects matched 3 of 23 (recall 0.13); response level vs hard_defect TP=5 FP=4 FN=9 TN=13 (precision 0.56, recall 0.36)
  - reviewer B: sentence level confirmed 4 of 15 (precision 0.27; by defect type 8, 0.53); listed defects matched 4 of 35 (recall 0.11); response level vs hard_defect TP=5 FP=4 FN=11 TN=11 (precision 0.56, recall 0.31)
  - both reviewers: hits confirmed by both 3, not confirmed by both 12, confirmed by neither 11 (of 15); by defect type 6, 9, 7; response level vs hard_defect of both TP=5 FP=4 FN=7 TN=15
- v3 (glm-5.3), oracle v2: responses 31, hits 14, per predicate {'P-A': 6, 'P-B': 1, 'P-C': 3, 'P-D': 1, 'P-V': 3}
  - reviewer A: sentence level confirmed 3 of 14 (precision 0.21; by defect type 6, 0.43); listed defects matched 3 of 23 (recall 0.13); response level vs hard_defect TP=5 FP=4 FN=9 TN=13 (precision 0.56, recall 0.36)
  - reviewer B: sentence level confirmed 4 of 14 (precision 0.29; by defect type 7, 0.50); listed defects matched 4 of 35 (recall 0.11); response level vs hard_defect TP=5 FP=4 FN=11 TN=11 (precision 0.56, recall 0.31)
  - both reviewers: hits confirmed by both 3, not confirmed by both 11, confirmed by neither 10 (of 14); by defect type 6, 8, 7; response level vs hard_defect of both TP=5 FP=4 FN=7 TN=15
- v3 (qwen3.8-max), oracle v1: responses 35, hits 22, per predicate {'P-A': 6, 'P-B': 1, 'P-C': 2, 'P-D': 5, 'P-F': 3, 'P-I': 1, 'P-V': 4}
  - reviewer A: sentence level confirmed 2 of 22 (precision 0.09; by defect type 9, 0.41); listed defects matched 2 of 52 (recall 0.04); response level vs hard_defect TP=11 FP=3 FN=13 TN=8 (precision 0.79, recall 0.46)
  - reviewer B: sentence level confirmed 4 of 22 (precision 0.18; by defect type 14, 0.64); listed defects matched 4 of 55 (recall 0.07); response level vs hard_defect TP=11 FP=3 FN=12 TN=9 (precision 0.79, recall 0.48)
  - both reviewers: hits confirmed by both 2, not confirmed by both 20, confirmed by neither 18 (of 22); by defect type 9, 13, 8; response level vs hard_defect of both TP=10 FP=4 FN=10 TN=11
- v3 (qwen3.8-max), oracle v2: responses 35, hits 18, per predicate {'P-A': 5, 'P-B': 1, 'P-D': 5, 'P-F': 3, 'P-I': 1, 'P-V': 3}
  - reviewer A: sentence level confirmed 2 of 18 (precision 0.11; by defect type 9, 0.50); listed defects matched 2 of 52 (recall 0.04); response level vs hard_defect TP=9 FP=2 FN=15 TN=9 (precision 0.82, recall 0.38)
  - reviewer B: sentence level confirmed 4 of 18 (precision 0.22; by defect type 14, 0.78); listed defects matched 4 of 55 (recall 0.07); response level vs hard_defect TP=9 FP=2 FN=14 TN=10 (precision 0.82, recall 0.39)
  - both reviewers: hits confirmed by both 2, not confirmed by both 16, confirmed by neither 14 (of 18); by defect type 9, 9, 4; response level vs hard_defect of both TP=8 FP=3 FN=12 TN=12
- T, responses with two reviewers, oracle v1: responses 66, hits 37, per predicate {'P-A': 12, 'P-B': 2, 'P-C': 5, 'P-D': 6, 'P-F': 3, 'P-I': 1, 'P-V': 8}
  - reviewer A: sentence level confirmed 5 of 37 (precision 0.14; by defect type 15, 0.41); listed defects matched 5 of 75 (recall 0.07); response level vs hard_defect TP=16 FP=7 FN=22 TN=21 (precision 0.70, recall 0.42)
  - reviewer B: sentence level confirmed 8 of 37 (precision 0.22; by defect type 22, 0.59); listed defects matched 8 of 90 (recall 0.09); response level vs hard_defect TP=16 FP=7 FN=23 TN=20 (precision 0.70, recall 0.41)
  - both reviewers: hits confirmed by both 5, not confirmed by both 32, confirmed by neither 29 (of 37); by defect type 15, 22, 15; response level vs hard_defect of both TP=15 FP=8 FN=17 TN=26
- T, responses with two reviewers, oracle v2: responses 66, hits 32, per predicate {'P-A': 11, 'P-B': 2, 'P-C': 3, 'P-D': 6, 'P-F': 3, 'P-I': 1, 'P-V': 6}
  - reviewer A: sentence level confirmed 5 of 32 (precision 0.16; by defect type 15, 0.47); listed defects matched 5 of 75 (recall 0.07); response level vs hard_defect TP=14 FP=6 FN=24 TN=22 (precision 0.70, recall 0.37)
  - reviewer B: sentence level confirmed 8 of 32 (precision 0.25; by defect type 21, 0.66); listed defects matched 8 of 90 (recall 0.09); response level vs hard_defect TP=14 FP=6 FN=25 TN=21 (precision 0.70, recall 0.36)
  - both reviewers: hits confirmed by both 5, not confirmed by both 27, confirmed by neither 24 (of 32); by defect type 15, 17, 11; response level vs hard_defect of both TP=13 FP=7 FN=19 TN=27

Fitness of v2 against v1 on T (two reviewers): confirmed by both 5 >= 5: True; not confirmed by both 27 <= 32: True; confirmed by neither 24 <= 29: True.

Hits dropped by v2: 5 (confirmed by a reviewer: 0); added: 0 (confirmed: 0).

