# Stage 0 Closure and Stage 1 Contract Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Закрыть полный exit gate этапа 0 и подготовить проверяемый,
однозначный контракт реализации этапа 1 без создания production-кода.

**Architecture:** Нормативные Markdown/OpenAPI/JSON Schema контракты отделены от
доказательств: tool-generated golden fixtures, oracle outputs и SHA-256 manifests.
Один Python test-support verifier валидирует schemas, provenance, hashes и
expected metrics; milestone report публикует только результаты его свежего запуска.

**Tech Stack:** Markdown, OpenAPI 3.1, JSON Schema 2020-12, YAML 1.2, Python
3.14.3, PyYAML 6.0.3, jsonschema 4.26.0, Maven 3.9.12, Gatling Maven Plugin
4.21.10, Gatling OSS 3.9.5/3.12.0/3.13.5/3.15.1, Apache JMeter 5.6.3,
jtl-comparator commit `83726485585577343b7b69f9961d9231a8fb4d49`,
AsciidoctorJ 3.0.0.

## Global Constraints

- Production-код, production source tree и production dependency не создаются.
- Russian — основной язык prose; identifiers и стандартные technical terms не
  переводятся.
- Сохранять все существующие untracked/user changes; stage только explicit files.
- Gatling — только OSS; Enterprise вне scope.
- Gatling source commits:
  `daf4a8108d17d2faaf583b716eee451b969fcd6c` (3.9.5),
  `a2e56a0f0e0eca0bcfa29219efc61bce37c62d73` (3.12.0),
  `5ba7e63409f5697b91d757bdf56380a7ce2720fb` (3.13.5),
  `4483f1fc56625bd0e77862ccbd2caf142c9dc9b8` (3.15.1).
- Gatling Maven Plugin 4.21.10 source commit:
  `d197190a3453af9c5d331787423a2221bdd1ffdb`.
- Apache JMeter `rel/v5.6.3` source commit:
  `34a2785748e9e0b14702595e8682c387869deda3`.
- Lifecycle normal path is exactly
  `REGISTERED → COLLECTING → PROCESSING → COMPLETE`.
- The only ingest closure signal is
  `POST /api/v1/runs/{run_id}/ingest-completions` with an immutable manifest.
- Counts/errors match oracles exactly; controlled-fixture p50/p75/p90/p95/p99
  absolute error is at most 1 ms.
- Benchmark has exactly 10,000,000 data rows; all three full measurements must
  be `≤ 600.000 s`, use `< 2147483648 bytes` peak memory and produce identical
  output SHA-256 under 2.0 CPU/2048 MiB cgroup limits.
- Significant API/security/storage decisions are recorded in the stage-0
  decision record before stage-1 implementation.

---

### Task 1: Создать test-support verifier и fixture manifest contract

**Files:**

- Create: `docs/contracts/fixtures/v1/fixture-manifest.schema.json`
- Create: `tools/fixtures/requirements.lock.txt`
- Create: `tools/fixtures/verify_stage0.py`
- Create: `tools/fixtures/tests/test_verify_stage0.py`
- Create: `fixtures/golden/README.md`
- Create: `fixtures/golden/corpus.json`

**Interfaces:**

- Consumes: approved design
  `docs/superpowers/specs/2026-08-10-stage-0-closure-stage-1-contract-design.md`.
- Produces: CLI `python tools/fixtures/verify_stage0.py --root .` with exit code
  `0` on a complete corpus and non-zero on schema/hash/oracle/provenance failure.
- Produces: manifest fields `schema_version`, `fixture_id`, `kind`, `producer`,
  `oracle`, `generation`, `files`, `expected_metrics`; `files[*]` contains
  `path`, lowercase 64-hex `sha256`, non-negative integer `size_bytes`, and `role`.
- Produces: corpus index requiring fixture ids `apm-v1`,
  `protocol-v1-verdict`, `protocol-v1-no-verdict`,
  `gatling-3.9.5-text-bundle`, `gatling-3.12.0-text`,
  `gatling-3.13.5-binary`, `gatling-3.15.1-binary`,
  `jmeter-5.6.3-mixed`, and `jmeter-5.6.3-10m-benchmark-spec`.

- [ ] **Step 1: Write failing verifier tests**

Create tests that build temporary fixture trees and assert exact diagnostics:

```python
import json
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory

from tools.fixtures.verify_stage0 import verify_root


class VerifierTests(unittest.TestCase):
    def setUp(self):
        self.temp = TemporaryDirectory()
        self.root = Path(self.temp.name)
        (self.root / "fixtures/golden").mkdir(parents=True)
        self.write_corpus([])

    def tearDown(self):
        self.temp.cleanup()

    def write_corpus(self, required):
        path = self.root / "fixtures/golden/corpus.json"
        path.write_text(json.dumps({"schema_version": "1.0.0", "required": required}))

    def write_manifest(self, manifest):
        directory = self.root / "fixtures/golden/sample"
        directory.mkdir()
        (directory / "manifest.json").write_text(json.dumps(manifest))

    @staticmethod
    def valid_manifest(**overrides):
        value = {
            "schema_version": "1.0.0",
            "fixture_id": "sample",
            "kind": "jmeter-jtl",
            "producer": {"name": "jmeter", "version": "5.6.3"},
            "oracle": {"name": "jtl-comparator", "version": "8372648"},
            "generation": {"command": "fixed-command"},
            "files": [{"path": "input.jtl", "sha256": "0" * 64,
                       "size_bytes": 1, "role": "input"},
                      {"path": "expected.json", "sha256": "0" * 64,
                       "size_bytes": 1, "role": "oracle"}],
            "expected_metrics": {"count": 1},
        }
        value.update(overrides)
        return value

    def test_missing_file_fails(self):
        self.write_manifest(self.valid_manifest())
        result = verify_root(self.root)
        self.assertEqual(result.errors[0], "sample: missing file expected.json")

    def test_hash_mismatch_fails(self):
        directory = self.root / "fixtures/golden/sample"
        directory.mkdir()
        (directory / "input.jtl").write_bytes(b"actual")
        (directory / "expected.json").write_bytes(b"x")
        (directory / "manifest.json").write_text(json.dumps(self.valid_manifest()))
        result = verify_root(self.root)
        self.assertTrue(any(error.startswith("sample: sha256 mismatch")
                            for error in result.errors))

    def test_unpinned_oracle_fails(self):
        manifest = self.valid_manifest(
            oracle={"name": "gatling", "version": "3.15.x"})
        self.write_manifest(manifest)
        result = verify_root(self.root)
        self.assertTrue(any("oracle.version" in error for error in result.errors))

    def test_missing_required_fixture_fails(self):
        self.write_corpus(["gatling-3.15.1-binary"])
        result = verify_root(self.root)
        self.assertIn("missing required fixture gatling-3.15.1-binary", result.errors)
```

- [ ] **Step 2: Run tests to verify RED**

Run:

```powershell
python -m unittest discover -s tools/fixtures/tests -v
```

Expected: import failure for `tools.fixtures.verify_stage0`.

- [ ] **Step 3: Define the JSON Schema**

Use draft 2020-12, `additionalProperties: false`, exact-version pattern that
rejects `x`, `+`, ranges and empty strings, and `sha256` pattern
`^[0-9a-f]{64}$`. Require at least one `input` file and one `oracle` file for a
golden fixture. Allow `storage: generated` only for `kind=benchmark-spec`.
`corpus.json` is the closed-world list: duplicate fixture ids, unlisted manifests
and missing required ids are errors.

- [ ] **Step 4: Implement the minimal verifier**

Implement dataclasses `VerificationResult(errors: list[str], checked: int)` and
functions `load_manifest(path)`, `sha256_file(path)`, `verify_manifest(path,
schema)`, `verify_root(root)`, and `main()`. Sort directory traversal and errors
lexicographically; never follow symlinks. Validate JSON with jsonschema and YAML
with `yaml.safe_load` when file role is `contract`.

- [ ] **Step 5: Pin test-support dependencies**

Write exactly:

```text
jsonschema==4.26.0
PyYAML==6.0.3
```

- [ ] **Step 6: Run GREEN and negative mutation checks**

Run:

```powershell
python -m unittest discover -s tools/fixtures/tests -v
python tools/fixtures/verify_stage0.py --root .
```

Expected: unit tests PASS; root verification FAILS with a sorted list of the
still-missing stage-0 contracts/fixtures, proving the gate is fail-closed.

- [ ] **Step 7: Commit the verifier concern**

```powershell
git add -- docs/contracts/fixtures/v1/fixture-manifest.schema.json fixtures/golden/README.md fixtures/golden/corpus.json tools/fixtures/requirements.lock.txt tools/fixtures/verify_stage0.py tools/fixtures/tests/test_verify_stage0.py
git commit -m "test: add stage 0 evidence verifier"
```

### Task 2: Зафиксировать обязательные контракты этапа 0

**Files:**

- Create: `docs/contracts/sla/v1/sla-contract.schema.json`
- Create: `docs/contracts/sla/v1/reference.yaml`
- Create: `docs/contracts/sla/v1/examples/valid-minimal.yaml`
- Create: `docs/contracts/sla/v1/examples/invalid-unknown-metric.yaml`
- Create: `docs/contracts/apm/v1/elastic-apm-schema-contract.json`
- Create: `docs/contracts/protocol/v1/protocol-template.adoc`
- Create: `docs/decisions-2026-08-10-stage-0.md`
- Create: `tools/fixtures/protocol/pom.xml`
- Create: `tools/fixtures/protocol/render.ps1`
- Create: `fixtures/golden/apm/v1/compatible/response.json`
- Create: `fixtures/golden/apm/v1/incompatible/response.json`
- Create: `fixtures/golden/apm/v1/manifest.json`
- Create: `fixtures/golden/protocol/v1/verdict/expected.html`
- Create: `fixtures/golden/protocol/v1/verdict/manifest.json`
- Create: `fixtures/golden/protocol/v1/no-verdict/expected.html`
- Create: `fixtures/golden/protocol/v1/no-verdict/manifest.json`
- Test: `tools/fixtures/tests/test_contracts.py`

**Interfaces:**

- Consumes: manifest schema/verifier from Task 1.
- Produces: rules schema v1 for stage 4, protocol template for stage 5, APM
  schema contract for stage 3, and explicit stage-0 architecture decisions.

- [ ] **Step 1: Write failing contract tests**

Tests must prove: reference and valid-minimal pass; unknown metric fails;
protocol contains deterministic `VERDICT` and `NO_VERDICT` branches; compatible
APM response passes and incompatible response fails with code
`APM_SCHEMA_INCOMPATIBLE`.

- [ ] **Step 2: Run tests to verify RED**

```powershell
python -m unittest tools.fixtures.tests.test_contracts -v
```

Expected: FAIL because contract files do not exist.

- [ ] **Step 3: Write SLA Schema and examples**

Define `schema_version: "1.0.0"`, `contract_id`, exact `scenario_id`, metric
registry `lt.response_time`, `lt.error_rate`, `lt.throughput`, comparators
`lte`, `gte`, phases limited to `steady`, and explicit units `ms`, `ratio`, `rps`.
Reference rules: p95 `lte 500 ms`, p99 `lte 800 ms`, error rate `lte 0.01 ratio`,
throughput `gte 1000 rps`.

- [ ] **Step 4: Write protocol and APM contracts**

Protocol deterministic sections: provenance, data completeness, load profile,
SLA checks, findings, final status, evidence hashes. APM v1 requires
`service.name`, `transaction.name`, `transaction.duration.histogram`,
`event.outcome`, `@timestamp`, and rejects missing/wrong-type mappings.
Render both branches with AsciidoctorJ 3.0.0, safe mode `SECURE`, embedded output,
fixed attributes and no current-time attribute; normalize only final LF and store
the raw deterministic HTML plus manifest.

- [ ] **Step 5: Record decisions**

Record: Git/MR is source of truth; internal append-only registry is a
deployment-time fallback; Influx intake is deferred; Gatling is OSS-only;
stage-1 auth/lifecycle/idempotency decisions are public-contract changes.

- [ ] **Step 6: Build APM manifest and hashes**

```powershell
python tools/fixtures/verify_stage0.py --root .
```

Update only actual SHA-256/size values reported for the APM corpus; rerun until
the APM fixture passes and remaining failures concern later tasks only.

- [ ] **Step 7: Run GREEN and commit**

```powershell
python -m unittest tools.fixtures.tests.test_contracts -v
git add -- docs/contracts/sla/v1 docs/contracts/apm/v1 docs/contracts/protocol/v1 docs/decisions-2026-08-10-stage-0.md fixtures/golden/apm/v1 fixtures/golden/protocol/v1 tools/fixtures/protocol tools/fixtures/tests/test_contracts.py
git commit -m "docs: freeze stage 0 reference contracts"
```

### Task 3: Generate Gatling OSS golden fixtures and pinned HTML oracles

**Files:**

- Create: `tools/fixtures/gatling/pom.xml`
- Create: `tools/fixtures/gatling/src/test/java/ltverdict/fixtures/Stage0Simulation.java`
- Create: `tools/fixtures/gatling/generate.ps1`
- Create: `tools/fixtures/gatling/extract_oracle.py`
- Create: `fixtures/golden/gatling/<version>/<case>/input/*`
- Create: `fixtures/golden/gatling/<version>/<case>/oracle/report.zip`
- Create: `fixtures/golden/gatling/<version>/<case>/oracle/expected.json`
- Create: `fixtures/golden/gatling/<version>/<case>/manifest.json`
- Test: `tools/fixtures/tests/test_gatling_corpus.py`

**Interfaces:**

- Consumes: fixture manifest/verifier from Task 1 and immutable versions in
  Global Constraints.
- Produces: four real logs and same-version HTML reports for `3.9.5/text-bundle`,
  `3.12.0/text`, `3.13.5/binary`, `3.15.1/binary`.

- [ ] **Step 1: Write failing corpus tests**

Assert exact four version/case pairs, manifest producer/source commit mapping,
binary first header `0`, text `RUN` field count `6`, no `userId` contract, and
presence of expected counts plus p50/p75/p90/p95/p99 for `allRequests` and each
request/group.

- [ ] **Step 2: Run tests to verify RED**

```powershell
python -m unittest tools.fixtures.tests.test_gatling_corpus -v
```

Expected: FAIL listing four missing manifests.

- [ ] **Step 3: Create a deterministic simulation**

Use a Java simulation with one scenario, groups `checkout` and `checkout,pay`,
requests `GET /ok`, `GET /slow`, `GET /ko`, five users, fixed pauses, and a local
loopback server returning fixed status/body/delay. Disable assertions and set
UTF-8. The nested comma deliberately proves group-name sanitization.

- [ ] **Step 4: Pin Maven profiles**

Use Gatling Maven Plugin `4.21.10` and four Maven profiles setting
`gatling.version` exactly. Invoke only Maven Central artifacts; record resolved
artifact checksums in each manifest. Do not use Gatling Enterprise packages.

- [ ] **Step 5: Generate each run and same-version report**

```powershell
pwsh tools/fixtures/gatling/generate.ps1 -Version 3.9.5
pwsh tools/fixtures/gatling/generate.ps1 -Version 3.12.0
pwsh tools/fixtures/gatling/generate.ps1 -Version 3.13.5
pwsh tools/fixtures/gatling/generate.ps1 -Version 3.15.1
```

The script must fail if reported Gatling version differs, more than one result
directory exists, report generation fails, or logs have unexpected format.
Normalize ZIP entry timestamps to `1980-01-01T00:00:00Z`, sort entries and use
deflate level 9. Preserve raw log bytes inside archives.

- [ ] **Step 6: Extract independent expected metrics**

`extract_oracle.py` reads only same-version report assets, never raw logs. Emit
canonical UTF-8 JSON with sorted keys and integer millisecond percentiles.
Manifest generation records commands, OS/JDK/Maven/plugin versions and SHA-256.

- [ ] **Step 7: Run corpus verification**

```powershell
python -m unittest tools.fixtures.tests.test_gatling_corpus -v
python tools/fixtures/verify_stage0.py --root .
```

Expected: Gatling tests PASS; no Gatling-related verifier errors.

- [ ] **Step 8: Commit generated evidence**

```powershell
git add -- tools/fixtures/gatling tools/fixtures/tests/test_gatling_corpus.py fixtures/golden/gatling
git commit -m "test: add pinned Gatling golden corpus"
```

### Task 4: Generate JMeter/JTL golden fixtures and comparator oracle

**Files:**

- Create: `tools/fixtures/jmeter/stage0.jmx`
- Create: `tools/fixtures/jmeter/generate.ps1`
- Create: `tools/fixtures/jmeter/run_comparator_oracle.py`
- Create: `tools/fixtures/jmeter/jtl-comparator.lock.json`
- Create: `fixtures/golden/jmeter/5.6.3/mixed/input.jtl`
- Create: `fixtures/golden/jmeter/5.6.3/mixed/oracle/jtl-comparator.json`
- Create: `fixtures/golden/jmeter/5.6.3/mixed/oracle/dashboard.zip`
- Create: `fixtures/golden/jmeter/5.6.3/mixed/manifest.json`
- Create: `fixtures/golden/jmeter/5.6.3/negative/*`
- Test: `tools/fixtures/tests/test_jmeter_corpus.py`

**Interfaces:**

- Consumes: Task 1 manifest/verifier.
- Produces: JMeter 5.6.3 CSV with TC and sampler rows; expected metrics for
  `auto`, `tc`, `samplers`; malformed, duplicate-header and missing-required
  fail-closed fixtures.

- [ ] **Step 1: Write failing JMeter corpus tests**

Assert exact 17-column header, UTF-8, accepted LF/CRLF, modes and expected
labels, exact producer/comparator commits, exact Python/pandas/numpy versions,
and three negative fixture diagnostics.

- [ ] **Step 2: Run tests to verify RED**

```powershell
python -m unittest tools.fixtures.tests.test_jmeter_corpus -v
```

- [ ] **Step 3: Build deterministic JMX and generation command**

Use JMeter `rel/v5.6.3`, one Thread Group with 10 users × 3 loops, Transaction
Controller `checkout` with parent sample enabled, HTTP samplers `/ok` and `/ko`,
fixed timers and local loopback responses. Set all 17 SaveService properties
explicitly; never depend on installation defaults.

- [ ] **Step 4: Pin and run comparator**

Checkout commit `83726485585577343b7b69f9961d9231a8fb4d49` into a temporary
directory, verify commit, run with Python 3.14.3/pandas 3.0.1/numpy 2.4.2, and
serialize `aggregate(parse_jtl(input, mode))` for all three modes. The lock file
records repository, commit, runtime versions and source SHA-256.

- [ ] **Step 5: Generate JMeter dashboard oracle**

Run JMeter non-GUI dashboard generation from the same JTL. Store a deterministic
ZIP and canonical expected metrics. Comparator owns filter/percentile semantics;
dashboard independently confirms sample and error counts.

- [ ] **Step 6: Run GREEN and commit**

```powershell
python -m unittest tools.fixtures.tests.test_jmeter_corpus -v
python tools/fixtures/verify_stage0.py --root .
git add -- tools/fixtures/jmeter tools/fixtures/tests/test_jmeter_corpus.py fixtures/golden/jmeter
git commit -m "test: add pinned JMeter golden corpus"
```

### Task 5: Replace stage-1-spec with an executable lifecycle and API contract

**Files:**

- Modify: `docs/stage-1-spec.md`
- Create: `docs/contracts/stage-1/openapi.yaml`
- Create: `docs/contracts/stage-1/ingest-completion.schema.json`
- Create: `docs/contracts/stage-1/problem.schema.json`
- Test: `tools/fixtures/tests/test_stage1_contract.py`

**Interfaces:**

- Consumes: approved design and Tasks 1–4 manifests/oracle semantics.
- Produces: complete endpoint matrix, lifecycle transition table, auth scopes,
  idempotency rules, exact Gatling/JTL formats and stable error codes.

- [ ] **Step 1: Write failing contract assertions**

Assert every OpenAPI operation has `operationId`, security, documented success
and problem responses; every creating POST requires `Idempotency-Key`; ingest
paths use only `ingestBearer`; management paths use only `oidc`; state enum and
manifest schema match the Markdown spec.

- [ ] **Step 2: Run RED**

```powershell
python -m unittest tools.fixtures.tests.test_stage1_contract -v
```

- [ ] **Step 3: Rewrite lifecycle and data model**

Add `PROCESSING`, explicit `reopen-ingest`, immutable accepted manifest,
`data_quality`, upload `forced` state, uniqueness constraints for idempotency,
and transactional conditions for `COMPLETE`. Remove transition on first
artifact finalize and every claim that completion can be inferred.

- [ ] **Step 4: Define endpoint-level auth and idempotency**

Document OIDC issuer/audience/scopes, single-team authorization, 256-bit
run-scoped ingest token hashing/revocation, `Idempotency-Key` retention and
request hashing, digest-protected chunks, replay statuses and stable conflicts.

- [ ] **Step 5: Define exact formats and errors**

Replace the incorrect text record examples with exact six/four/seven/six/three
field layouts and `ASSERTION`; define binary headers/primitives/cache; define
JTL 17-column CSV and modes. Add stable errors including
`IDEMPOTENCY_KEY_REUSED`, `CHUNK_CONFLICT`, `UPLOAD_INCOMPLETE`,
`MANIFEST_MISMATCH`, `TOKEN_REVOKED`, `FORMAT_UNSUPPORTED`.

- [ ] **Step 6: Write OpenAPI and JSON schemas**

OpenAPI 3.1 references the two local schemas. Completion manifest requires
`schema_version: "1.0.0"`, non-empty unique `artifacts`, declared and actual
hash/size, `required`, `allow_partial`, and rejects unknown fields.

- [ ] **Step 7: Run GREEN and commit**

```powershell
python -m unittest tools.fixtures.tests.test_stage1_contract -v
npx --yes markdownlint-cli2 docs/stage-1-spec.md
git add -- docs/stage-1-spec.md docs/contracts/stage-1 tools/fixtures/tests/test_stage1_contract.py
git commit -m "docs: freeze stage 1 ingest contract"
```

### Task 6: Freeze benchmark specification and reproducible generator

**Files:**

- Create: `docs/contracts/stage-1/jtl-benchmark.md`
- Create: `fixtures/benchmark/jmeter-5.6.3-10m/generator-spec.json`
- Create: `fixtures/benchmark/jmeter-5.6.3-10m/manifest.json`
- Create: `tools/fixtures/jmeter/generate_benchmark.py`
- Test: `tools/fixtures/tests/test_benchmark_spec.py`

**Interfaces:**

- Consumes: exact JTL format from Task 5.
- Produces: deterministic 10M-row generator and binary pass/fail measurement
  contract. The generated multi-gigabyte CSV is not committed; its expected
  byte count, line count and SHA-256 are committed as `storage: generated`.

- [ ] **Step 1: Write failing benchmark-spec tests**

Assert seed `20260810`, header count 1, data row count 10,000,000, exact
distribution/label/error parameters, expected hash shape, three measured runs,
limits and exclusion boundaries.

- [ ] **Step 2: Run RED**

```powershell
python -m unittest tools.fixtures.tests.test_benchmark_spec -v
```

- [ ] **Step 3: Implement deterministic streaming generator**

Write rows directly to a binary file with explicit UTF-8 and LF; use integer
arithmetic and a specified `xorshift64*` PRNG, never locale/time/random module.
CLI: `python tools/fixtures/jmeter/generate_benchmark.py --rows 10000000
--seed 20260810 --output <path>`; print row count, bytes and SHA-256 JSON.

- [ ] **Step 4: Generate once and freeze expected evidence**

Generate to a temporary workspace path, independently count LF bytes and hash
with `Get-FileHash -Algorithm SHA256`. Record exact expected byte count/hash in
the manifest, then remove only the validated temporary generated file.

- [ ] **Step 5: Write measurement protocol**

Specify Linux amd64, cgroup v2, JDK 21, 2.0 CPU, 2048 MiB memory+swap, no extra
swap, 1M excluded warm-up, three new-process 10M runs, measured parse through
fsync, required telemetry fields and exact fail predicates.

- [ ] **Step 6: Run GREEN and commit**

```powershell
python -m unittest tools.fixtures.tests.test_benchmark_spec -v
python tools/fixtures/verify_stage0.py --root .
git add -- docs/contracts/stage-1/jtl-benchmark.md fixtures/benchmark/jmeter-5.6.3-10m tools/fixtures/jmeter/generate_benchmark.py tools/fixtures/tests/test_benchmark_spec.py
git commit -m "test: freeze JTL benchmark methodology"
```

### Task 7: Synchronize PRC, development plan, README and CHANGELOG

**Files:**

- Modify: `prc-lt-verdict-v0.5.md`
- Modify: `docs/superpowers/plans/2026-08-10-development-plan.md`
- Modify: `README.md`
- Modify: `CHANGELOG.md`

**Interfaces:**

- Consumes: all frozen contracts and evidence from Tasks 1–6.
- Produces: one consistent status and navigation surface; Task 0/A1–A4 checked
  only where evidence exists; Task 1 Step 1 checked; implementation steps remain
  unchecked.

- [ ] **Step 1: Update PRC public-contract decisions**

Replace old lifecycle/token wording, pin exact producer/oracle versions, replace
`≤ 5–10 min` with `≤ 600.000 s`, and point stage gates to manifests/report.
Preserve PRC version `v0.5` and add an amendment note dated 2026-08-10 rather
than silently rewriting history.

- [ ] **Step 2: Update plan status truthfully**

Check Task 0 Steps 1–5 and A1–A4 only after verifier evidence. Check Task 1 Step
1 because the contract is frozen. Leave Task 1 implementation Steps 2–11 open.
Replace `3.15.x`, ranges and oracle ambiguity with exact versions/commits.

- [ ] **Step 3: Update README and CHANGELOG**

README status: Stage 0 closed; Stage 1 contract ready; no production
implementation exists. Add direct links to PRC v0.5, development plan,
stage-1-spec, contracts, golden fixtures and milestone report. CHANGELOG
`Unreleased/Added` records contracts, fixtures and milestone evidence.

- [ ] **Step 4: Run consistency searches**

```powershell
rg -n -i "5.?10|3\.15\.x|3\.13\+|одноразов|первый finalize|уточнить|t[b]d|t[o]do" prc-lt-verdict-v0.5.md docs/stage-1-spec.md docs/superpowers/plans/2026-08-10-development-plan.md README.md
```

Expected: no ambiguous active requirements; historical text is explicitly
labelled and links to the amendment.

- [ ] **Step 5: Lint and commit**

```powershell
npx --yes markdownlint-cli2 prc-lt-verdict-v0.5.md docs/superpowers/plans/2026-08-10-development-plan.md README.md CHANGELOG.md
git add -- prc-lt-verdict-v0.5.md docs/superpowers/plans/2026-08-10-development-plan.md README.md CHANGELOG.md
git commit -m "docs: mark stage 0 contracts complete"
```

### Task 8: Produce the verifiable stage-0 milestone report

**Files:**

- Create: `docs/milestones/stage-0.md`
- Test: `tools/fixtures/tests/test_milestone_report.py`

**Interfaces:**

- Consumes: every requirement and verification output from Tasks 1–7.
- Produces: requirement-by-requirement evidence matrix and explicit `GO` only
  when every mandatory row passes.

- [ ] **Step 1: Write failing report test**

Require report sections `Результат`, `Матрица exit gate`, `Команды и результаты`,
`Ограничения`, `Открытые риски`, `Решение`; validate every matrix row contains
requirement, evidence path, command and `PASS`; validate decision exactly
`GO — этап 1 разрешён к реализации`.

- [ ] **Step 2: Run RED**

```powershell
python -m unittest tools.fixtures.tests.test_milestone_report -v
```

- [ ] **Step 3: Run fresh complete gate**

```powershell
python -m unittest discover -s tools/fixtures/tests -v
python tools/fixtures/verify_stage0.py --root .
npx --yes markdownlint-cli2 "**/*.md"
git diff --check
```

Record command, UTC timestamp, exit code and concise output totals. Do not copy
an older run. If any command fails, report decision remains `NO-GO` and the
failure is fixed before proceeding.

- [ ] **Step 4: Write evidence matrix and limitations**

Rows: SLA/schema, protocol, APM contract, Git/MR decision, four Gatling versions,
JMeter/comparator, lifecycle/manifest, auth/authz, idempotency, benchmark,
README/plan state, secret/no-production-code check. Document that actual 10M
parser benchmark runs in Stage 1 because no parser exists yet; Stage 0 freezes
methodology and input hash, not performance results.

- [ ] **Step 5: Run GREEN and commit**

```powershell
python -m unittest tools.fixtures.tests.test_milestone_report -v
python tools/fixtures/verify_stage0.py --root .
git add -- docs/milestones/stage-0.md tools/fixtures/tests/test_milestone_report.py
git commit -m "docs: record stage 0 milestone gate"
```

### Task 9: Completion audit and branch handoff

**Files:**

- Review: every file committed by Tasks 1–8
- Review: `docs/superpowers/specs/2026-08-10-stage-0-closure-stage-1-contract-design.md`

**Interfaces:**

- Consumes: completed plan tasks and Git history.
- Produces: fresh proof that the original user objective is met without
  unrelated changes or production code.

- [ ] **Step 1: Re-read original requirements and design**

Build a nine-row audit: full Stage 0, lifecycle/manifest, formats/oracles,
auth/authz, idempotency, benchmark, fixtures, milestone, README/plan. Link each
row to authoritative current-state evidence.

- [ ] **Step 2: Inspect full branch diff and file inventory**

```powershell
git diff --stat origin/agent/add-admin-questionnaire...HEAD
git diff --check origin/agent/add-admin-questionnaire...HEAD
git status --short --branch
rg --files | rg "(^src/|build\.gradle|settings\.gradle|application\.ya?ml$)"
```

Expected: only task documentation, fixtures and `tools/fixtures` test-support;
no production tree/dependency. Existing unrelated untracked files are reported,
not staged or discarded.

- [ ] **Step 3: Run fresh verification suite**

```powershell
python -m unittest discover -s tools/fixtures/tests -v
python tools/fixtures/verify_stage0.py --root .
npx --yes markdownlint-cli2 "**/*.md"
git diff --check origin/agent/add-admin-questionnaire...HEAD
git grep -n -I -E "(BEGIN (RSA|OPENSSH|EC) PRIVATE KEY|AKIA[0-9A-Z]{16})" -- .
```

Expected: all tests/checks exit 0; secret grep returns no matches.

- [ ] **Step 4: Use required review and finishing skills**

Invoke `superpowers:verification-before-completion`, then
`superpowers:requesting-code-review`, resolve findings, and invoke
`superpowers:finishing-a-development-branch`. Do not push, merge, rebase, tag or
release without explicit user permission.
