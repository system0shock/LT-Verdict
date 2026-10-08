# H0: what is left for the freeze (not part of the preregistration)

Done: corpus built (38 cases, 30 OK + 8 WAIVED, check_built clean), preregistration candidate `preregistration-holdout.md`, dry run (152 attempts, audit clean).
Nothing is frozen: there is no `PREREG_HOLDOUT_SHA256.txt`, no `plan-holdout.json`, no run in the live results directory.

1. Owner decisions written as open in the candidate: the fallback model id, provider and config hash (section 5); the P2 tolerance for 19 independent matrix cases
   (section 9, keep +3 or change); the author's proposals on restart classes and fallback pair exclusion (sections 4 and 5).
2. Put prompt B where the runner finds it, or pass `--prompt-b <file>`: `docs/contracts/advice/v1/system-prompt-v2.md` of origin/main (PR #170), LF sha256 in section 10.
   The worktree of the harness has no such file.
3. Confirm the harness HEAD in section 10 (a commit after the candidate changes the hashes; regenerate the candidate with `make_prereg.py`-style hashing and re-check).
4. Freeze: write the candidate's SHA-256 into `PREREG_HOLDOUT_SHA256.txt` (first token), then `run_holdout.py freeze-list --seed 20261007` must print hashes that all occur in the
   candidate; `plan --seed 20261007` writes the plan to the live directory (same hash as the candidate). Only then `run --live`.
5. Before the first request: the owner's go, ModelStudio limits (question B6 of the ADR amendment: availability is checked only by a paid request), the key in the environment of the developer runner.
