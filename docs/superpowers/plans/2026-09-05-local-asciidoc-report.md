# Локальный AsciiDoc export — implementation plan

**Goal:** скачать читаемый AsciiDoc report из сохранённого analysis через CLI
и UI. Основание — approved v0.6 delta design, раздел 19, и команда пользователя
продолжить roadmap после публикации pilot. Это небольшой следующий инкремент
Slice 9, независимый от решения о comparability metadata для baseline.

**Architecture:** существующие RunBundle, report command, private report API.
Одна чистая функция `renderAsciiDocReport(resultBytes, analysisId): ByteArray`.
Никаких новых dependencies, renderer registry, public schema или identity.

## Global Constraints

- Base: `a56e9f9`, сохранённый local-review pilot с CI metadata correction.
- Не менять parser, metrics, policy, storage, analysis-result или identity.
- Не создавать analysis jobs при export и не менять сохранённые bytes.
- AsciiDoc — UTF-8, deterministic, English headings, без текущей даты.
- Acquired text не становится directives, attributes, links или markup.
- JSON export остаётся byte-identical, HTML остаётся без изменения.
- Missing values — `unavailable`, не нули; числа не преобразуются в Double.
- Существующие CLI exit codes и API query/path validation сохраняются.
- Один review всего маленького инкремента; исправления проверять в их scope.
- Не менять пользовательские/root/другие worktrees. Worker не push/merge/rebase.

## Task 1: AsciiDoc renderer и существующие export boundaries

**Files:**

- Create: `src/main/kotlin/io/ltverdict/report/AsciiDocReport.kt`
- Create: `src/test/kotlin/io/ltverdict/report/AsciiDocReportTest.kt`
- Modify: `src/main/kotlin/io/ltverdict/cli/CommandLine.kt`
- Modify: `src/main/kotlin/io/ltverdict/web/LocalApi.kt`
- Modify: `src/test/kotlin/io/ltverdict/cli/CommandLineTest.kt`
- Modify: `src/test/kotlin/io/ltverdict/web/LocalApiTest.kt`
- Modify: `ui/src/App.vue`, `ui/e2e/report-export.spec.ts`
- Modify: `docs/user/slice-1-local-analysis.md`, `CHANGELOG.md`
- Modify: `docs/architecture/slice-1-local-runtime.md`, `docs/development-plan-v0.6.md`

### Output and safety

Report has constant document title `LT Verdict report`, sections for run and
analysis IDs, three status axes, overall/transaction metrics, policy checks,
findings, evidence IDs and canonical JSON. Preserve metric scope (path, label,
sample kind), explicit units/names, exact integer/decimal tokens and ratios.
Empty sections say `unavailable`. Canonical JSON retains the original text.

Use literal blocks with only `specialchars` substitutions. The shortest safe
representation is to emit acquired field values as compact JSON tokens using
existing kotlinx.serialization, with constant field labels. JSON strings keep
quotes and escaped newlines, so user text cannot become a delimiter line or
a preprocessor directive. Never decode a JSON string into an unprefixed raw
AsciiDoc line; never put acquired text in a heading or attribute list.
Fields in generic findings/check objects can remain compact JSON objects in
literal blocks. No table/markup escaping framework is needed for this report.
Do not enable callouts, macros, attributes or passthrough substitutions.
Document that escaped strings are deliberate; native JSON remains the exact
machine-readable export. Conversion/publishing is outside this increment.

### Existing boundaries

`ltv report <run-id> <analysis-id> --format asciidoc [--data-dir <path>]`
extends only the existing format allowlist; success returns 0 even for FAIL.
Unknown run/analysis 4, busy 6, invalid args 64, corruption 70, no partial stdout.
Update usage text; no shorthand aliases.

`GET /api/runs/{runId}/analyses/{analysisId}/report?format=asciidoc`
returns the same renderer bytes, `text/plain; charset=UTF-8`, attachment name
`lt-verdict-<analysisId>.adoc`. Keep strict single format/no extra queries,
existing validated store read, status codes, Host/origin/security headers.
Add `Download AsciiDoc` beside existing JSON/HTML links; no UI redesign.
This private API/CLI extension is recorded here before implementation; public
schemas, dependency set and component boundaries do not change, no ADR needed.

### Tests and verification

Use existing JUnit and Playwright patterns. First prove RED through existing
CLI or API format rejection, then implement renderer and wire both callers.
One compact renderer test covers exact large numbers, statuses and adversarial
acquired strings (newlines, `....`, `include::`, conditionals, attributes,
passthrough, image/link markup); assert values remain JSON-encoded in literal
blocks. Cover unavailable metrics without invented zeroes. Extend boundary
tests for successful AsciiDoc, original bytes unchanged and error handling.
Browser test clicks the actual link, checks `.adoc`, report identity and no
POST jobs; existing JSON/HTML checks still pass.

Focused: `gradlew.bat -PnpmOffline=true --offline --no-daemon test --tests '*ReportTest' --tests '*CommandLineTest' --tests '*LocalApiTest'`.
Final: `gradlew.bat -PnpmOffline=true --offline --no-daemon check installDist`,
UI typecheck/lint, `npm --prefix ui run e2e`, markdownlint, slice0 verifier,
`git diff --check`, tracked-secret scan. No new test framework or fixture tree.
Root verifies actual AsciiDoc conversion as a one-off QA when available;
converter is not bundled as a project dependency.
