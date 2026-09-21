# Shared API/UI wiring — 2026-09-22

Root owns LocalApi.kt, CommandLine.kt, App.vue, api.ts and types.ts; agents supply focused components. Existing loopback Host/Origin/session/CSRF checks apply to every new mutation. No new runtime dependencies.

AI contract: GET/POST /api/runs/{runId}/analyses/{analysisId}/advice; POST body exactly {"confirm_external_transfer":true}, missing/false/string rejected before queue. GET returns {advice:null|ai-advice.v1}. POST 202 accepted job, 409 busy, 503 unavailable. GET/DELETE /api/advice-jobs/{jobId} read/cancel; jobs carry run_id/analysis_id. Browser explicitly explains ModelStudio transfer and advisory-only output. Text rendered escaped; model HTML never executed. CLI constructs optional env-configured runner and closes jobs before DataDirectory. Root does not invoke model in local tests.

Verification: API missing-confirmation/CSRF/no runner/no mutation of result tests, worker tests from AI agent, frontend typecheck/lint and focused component fixture. Remaining Jenkins/history contracts appended when stable.

Analytics: GET .../analytics?limit=10&transaction=&transaction_limit=100 combines local run dynamics, fixed-baseline transaction comparison, OpenSearch overlay and metric pack coverage. Limits: displayed runs 1..100, transactions 1..200, UTF-8 filter 256 bytes. Scan up to 1000 stored analyses with explicit history_scan_truncated; current and baseline always included, no external calls. Missing run timestamps => null dynamics/overlay, not fabricated timestamps. UI/export show scan truncation.

Jenkins: standalone --jenkins-config parsed strictly backend-side. GET /api/jenkins returns safe profiles, GET /api/jenkins/{id}/attempts reads durable state; POST trigger/advance/reconcile/collect require existing local CSRF. No browser credentials or arbitrary target URLs. Collect imports verified artifact through existing RunBundleStore.acceptInput. One bounded Jenkins operation at a time; missing artifact remains explicit waiting.

Grafana: configured proxy profiles only; links and explicit PNG rendering use saved run timestamps and shared SourceHttp auth/rate/request budgets. Rendering is bounded to 8 MiB, failures preserve source link. Confluence adds escaped storage-XHTML export. Distribution includes production AI scripts/prompt/schema and offline onboarding tool/skill; credentials and Qwen package remain separately configured, never packaged.

Review correction: history is explicit-load and reads only canonical manifest plus hashed run/result/identity documents, bounded to1000 analyses/4096directory entries/16MiB metadata (8MiB per document). It does not rehash raw sources for historical summaries. Current/baseline open/replay retains full verification. API exposes history_integrity=SAVED_DOCUMENT_HASHES and byte limit; all exports disclose truncation and retain original previous-run deltas after hide selection. Regression checks bounded reads, raw-source independence and rejection of corrupted saved result.

Final regression corrections: checkbox/radio hit targets retain the existing 44px minimum on both axes; comparison remains available while its conditions are read, and is blocked while conditions are saved. Runtime cancellation gives PowerShell 20s for bounded cleanup, then terminates captured descendant processes as well as the parent and waits at most another 20s. A Windows fake parent/child regression demonstrated the old leak before the change.

Static chart export adds `format=svg` to saved report CLI/API/UI. It reads the bounded first500 bins of saved60s rollup; absent data is an explicit unavailable SVG. No new runtime dependencies.

WindowsPowerShell5 correction: retain native process Handle and Refresh before reading ExitCode. Detect Windows through OSVersion, not a stripped OS environment variable. A taskkill probe under the exact Kotlin environment allowlist took3399ms; taskkill bound is5s, JVM cleanup grace20s covers command cleanup. Sanitized fake-provider/hung-process verification is required.
