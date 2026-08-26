# LT Verdict v0.6 Minimal Slice 0 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Принять нормативный baseline v0.6 и оставить перед runtime-разработкой
только два минимальных контракта с одной offline-проверкой.

**Architecture:** Slice 0 остаётся документационно-контрактным. Два JSON Schema
содержат примеры, которые ссылаются на два маленьких синтетических файла;
stdlib-only Python script проверяет JSON, required-поля и SHA-256. Production
runtime, connectors и framework для контрактов не создаются.

**Tech Stack:** Markdown, JSON Schema Draft 2020-12, Python 3 standard library.

**Spec:** `docs/superpowers/specs/2026-08-26-v06-local-mvp-delta-design.md`

## Global Constraints

- Сеть доступна; серверное развёртывание не является prerequisite MVP.
- Slice 0 не добавляет production code, dependencies, registry или framework.
- Данные fixtures синтетические и могут храниться в репозитории.
- Входные файлы сохраняют ручной fallback в owning runtime slices.
- Публичные контракты Slice 0: только `run.v1` и `analysis-result.v1`.
- Push, PR, merge, tag и release требуют отдельного разрешения пользователя.

## Deferred-feature ledger

Упрощение Slice 0 не удаляет согласованные фичи:

| Owning slice | Сохранённые требования |
| --- | --- |
| Slice 1 | Local one-command runtime, обязательный Web UI, JTL и `simulation.log`, ручная загрузка |
| Slice 2 | VictoriaMetrics, Prometheus, InfluxDB, PostgreSQL online snapshots/DML/`pg_stat_statements`/`pg_profile`, общий governor с default `0.5 RPS` |
| Slice 3 | Jenkins REST skeleton, запуск существующих jobs, queue/build tracking |
| Slice 4 | Автоматическое получение JTL/`simulation.log`, ручной fallback каждого файла |
| Slice 5 | Отдельный `capacity_step` режим и консервативная оценка максимума |
| Slice 6 | JVM и OpenShift analysis packs |
| Slice 7 | OpenSearch 2.6 errors, overlay на графики, optional correlation |
| Slice 8 | Local rendering, Grafana links, сравнение прогонов, таблица динамики за N тестов |
| Slice 9 | JSON, self-contained HTML, AsciiDoc, Confluence-ready и REST skeleton |
| Slice 10 | Grafana rendered evidence, рекомендательный headless GigaCode analysis и Skill адаптации НТ-скриптов |
| Post-MVP | Server deployment, shared history/RBAC, code-aware RCA |

---

### Task 1 / PR 1: Adopt the normative v0.6 baseline

**Branch:** `docs/v06-baseline`

**Files:**

- Create: `lt-verdict-prc-prd-v0.6.md`
- Create: `docs/prc-v0.6-alignment-review.md`
- Create: `docs/development-plan-v0.6.md`
- Modify: `.markdownlint-cli2.yaml`
- Modify: `README.md`
- Modify: `CHANGELOG.md`
- Modify: `prc-lt-verdict-v0.5.md`
- Modify: `docs/stage-1-spec.md`
- Modify: `docs/superpowers/plans/2026-08-10-development-plan.md`

**Interfaces:**

- Consumes: утверждённые пользовательские документы
  `F:\Coding\LT-Verdict\lt-verdict-prc-prd-v0.6.md` и
  `F:\Coding\LT-Verdict\docs\prc-v0.6-alignment-review.md`.
- Produces: нормативный PRC v0.6 и единственную текущую карту Slices 0–10.

- [ ] **Step 1: Add the reviewed baseline without rewriting it**

  Добавить утверждённое содержимое двух source-файлов. В tracked PRC изменить
  статус на нормативный со ссылкой на delta design и заменить три
  trailing-space hard breaks в metadata на пустые строки, чтобы пройти
  `git diff --check`; alignment review переносится побайтово.

- [ ] **Step 2: Write the compact roadmap**

  `docs/development-plan-v0.6.md` должен содержать только: статус документа,
  ссылку на PRC и delta-spec, таблицу Slices 0–10 из ledger выше, текущий статус
  `Slice 0 — IN PROGRESS`, exit gate каждого slice одной строкой и post-MVP.

- [ ] **Step 3: Remove ambiguity with v0.5**

  Сразу после первого заголовка каждого legacy-документа добавить:

  ```markdown
  > **Superseded:** нормативный baseline и текущий план находятся в
  > `lt-verdict-prc-prd-v0.6.md` и `docs/development-plan-v0.6.md`.
  ```

  README должен ссылаться на PRC v0.6, roadmap, delta-spec и alignment review.
  В `CHANGELOG.md` добавить одну строку о принятии local-first baseline v0.6.
  Accepted PRC и superseded legacy documents сохраняют reviewed formatting и
  явно исключаются в `.markdownlint-cli2.yaml`; новые активные документы
  остаются под lint.

- [ ] **Step 4: Verify documentation**

  ```powershell
  npx --yes markdownlint-cli2@0.23.2 "**/*.md"
  git diff --check
  git grep -n -I -E "(BEGIN (RSA|OPENSSH|EC) PRIVATE KEY|AKIA[0-9A-Z]{16})" -- .
  ```

  Expected: markdownlint проверяет все non-ignored Markdown files и вместе с
  diff check завершается с exit `0`; secret scan не находит совпадений.

- [ ] **Step 5: Commit only the listed files**

  ```powershell
  git commit -m "docs: adopt local-first v0.6 baseline"
  ```

### Task 2 / PR 2: Add the two contracts and one verifier

**Branch:** `test/v06-slice0-contracts`

**Files:**

- Create: `docs/contracts/run/v1/run.schema.json`
- Create: `docs/contracts/result/v1/analysis-result.schema.json`
- Create: `fixtures/slice0/jmeter.jtl`
- Create: `fixtures/slice0/simulation.log`
- Create: `tools/verify_slice0.py`
- Create: `.gitattributes`

**Interfaces:**

- Consumes: Python 3 standard library only.
- Produces: `run.v1`, `analysis-result.v1` and command
  `python tools/verify_slice0.py`.

- [ ] **Step 1: Write the verifier first and confirm RED**

  The script loads the two fixed schema paths, reads `examples[0]`, checks every
  top-level name from `required`, accepts each `run.v1.inputs[].path` only as a
  portable repository-relative regular file and compares its lowercase
  SHA-256. It prints
  `slice 0 verification: OK` only after every check succeeds.

  ```powershell
  python tools/verify_slice0.py
  ```

  Expected: non-zero because schemas and fixtures do not exist yet.

- [ ] **Step 2: Add the minimal contracts**

  `run.schema.json` requires `schema_version = run.v1`, `run_id`,
  `analysis_mode` (`standard | capacity_step`), RFC 3339 `started_at`/`ended_at`
  and non-empty `inputs` with `type`, portable relative `path` and 64-char
  lowercase `sha256`. Its embedded example references both fixtures.

  `analysis-result.schema.json` requires
  `schema_version = analysis-result.v1`, `run_id`, the same `analysis_mode`,
  independent `run_validity`, `policy_verdict`, `analysis_coverage`, plus arrays
  `findings` and `evidence`. Its embedded example is a valid empty analysis.

- [ ] **Step 3: Add two tiny synthetic fixtures and record their hashes**

  JTL contains a header, one successful sample and one synthetic error.
  `simulation.log` contains one synthetic run, one user and one successful
  request. Store both as UTF-8 with LF and record their actual SHA-256 values in
  the embedded `run.v1` example. Exact-path `.gitattributes` rules preserve LF
  after checkout.

- [ ] **Step 4: Run GREEN checks**

  ```powershell
  python tools/verify_slice0.py
  python -m py_compile tools/verify_slice0.py
  npx --yes markdownlint-cli2@0.23.2 "**/*.md"
  git diff --check
  ```

  Expected: all commands exit `0`; verifier prints its single success line.

- [ ] **Step 5: Commit only the six created files**

  ```powershell
  git commit -m "test: add minimal slice 0 contracts"
  ```

### Task 3 / PR 3: Record the local Slice 0 gate candidate

**Branch:** `docs/v06-slice0-gate`

**Files:**

- Create: `docs/milestones/slice-0.md`
- Modify: `docs/development-plan-v0.6.md`
- Modify: `README.md`

**Interfaces:**

- Consumes: Task 1 documentation and Task 2 verifier output.
- Produces: reviewable local evidence; remote CI/merge/tag remain user actions.

- [ ] **Step 1: Run fresh local verification**

  ```powershell
  python tools/verify_slice0.py
  npx --yes markdownlint-cli2@0.23.2 "**/*.md"
  git diff --check
  git grep -n -I -E "(BEGIN (RSA|OPENSSH|EC) PRIVATE KEY|AKIA[0-9A-Z]{16})" -- .
  ```

- [ ] **Step 2: Record only observed evidence**

  The report contains result, commands with exit codes, changed-file scope,
  limitations, unverified assumptions and decision `PENDING_USER_REVIEW`.
  Roadmap marks Slice 0 `READY FOR REVIEW`; README links the report. Do not call
  Slice 0 complete before permitted remote CI and user acceptance.

- [ ] **Step 3: Re-run the same gate and inspect the full diff**

  Expected: verifier, markdownlint and diff checks exit `0`; secret scan has no
  matches; only the three listed files changed in this PR.

- [ ] **Step 4: Commit the gate candidate**

  ```powershell
  git commit -m "docs: record slice 0 gate candidate"
  ```

## Definition of Done

- [ ] PRC v0.6 and compact roadmap are tracked; v0.5 documents are superseded.
- [ ] Exactly two Slice 0 schemas, two synthetic inputs and one verifier exist.
- [ ] `python tools/verify_slice0.py` passes offline without dependencies.
- [ ] Deferred-feature ledger still maps every agreed MVP feature to a slice.
- [ ] Gate report contains fresh local evidence and no unsupported completion claim.
