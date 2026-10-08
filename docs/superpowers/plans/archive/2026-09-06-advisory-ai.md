# Advisory AI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Добавить bounded advisory AI поверх одного сохранённого immutable analysis без влияния на deterministic verdict.

**Architecture:** Backend проецирует проверенный analysis в bounded allowlisted evidence, вызывает один узкий runner и валидирует closed JSON с известными references. Advice публикуется отдельным atomic bundle, привязанным к SHA-256 analysis manifest; runtime остаётся `UNAVAILABLE`, пока OS isolation и endpoint-only egress не подтверждены фактически.

**Tech Stack:** Kotlin/JVM 21, kotlinx.serialization JSON, Java NIO, Qwen Code 0.21.1, Docker Desktop; новых dependencies нет.

**Spec:** `docs/superpowers/specs/2026-09-06-advisory-ai-design.md`

## Global Constraints

- Exact package: `@qwen-code/qwen-code@0.21.1`; CLI SHA-256: `1db9709bf1753611ca2fec234cf5adf517376efeb1540fcf9e309da010f9ed38`.
- Wrapper id: `gigacode-qwen-code`; global Qwen 0.21.5 не используется.
- Model: `deepseek/deepseek-v4-flash-0731`; endpoint: `https://openrouter.ai/api/v1`.
- Evidence/output/stderr limits: `262144`/`131072`/`16384` bytes; timeouts: `60s`/`65s`; retries: `0`.
- Secret передаётся только как `OPENAI_API_KEY` environment value, не в argv/log/artifact/provenance.
- No repository/user-home/Docker-socket access; ephemeral state; read-only input; no tools/hooks/extensions/MCP/context; endpoint-only egress.
- Неподтверждённая boundary даёт `UNAVAILABLE`, не запуск Qwen.
- Оркестратор запретил commit/staging; commit steps намеренно отсутствуют.

---

### Task 1: Evidence, validation и service contract

**Files:** Create `src/main/kotlin/io/ltverdict/ai/AdvisoryAi.kt`; test `src/test/kotlin/io/ltverdict/ai/AdvisoryAiTest.kt`; create contracts under `docs/contracts/advice/v1/`.

**Interfaces:** `AdvisoryEvidenceBuilder.build(runId, analysisId, analysisManifestSha256, analysisResult): AdvisoryEvidence`; `AdviceOutputValidator.validate(bytes, allowedReferences): JsonObject`; `AdvisoryAiService.generate/read` over `AdvisoryRunner.invoke`.

- [ ] Write tests for allowlisting/redaction, limits, closed output, rank and known refs.
- [ ] Root runs `gradlew.bat test --tests io.ltverdict.ai.AdvisoryAiTest`; expected RED: missing production symbols.
- [ ] Implement the minimum with existing kotlinx JSON; rerun filter for PASS.

### Task 2: Separate immutable advice storage

**Files:** Create `src/main/kotlin/io/ltverdict/ai/AiAdviceStore.kt`; test in `AdvisoryAiTest.kt`.

**Interfaces:** `AiAdviceStore.write(runId, analysisId, analysisManifestSha256, document): StoredAdvice`; `AiAdviceStore.read(runId, analysisId): StoredAdvice?`.

- [ ] Test save/reload, manifest binding, tamper rejection and unchanged analysis bytes.
- [ ] Publish `runs/<run_id>/advice/<analysis_id>` through existing operation lock/shared staging, owned-path checks and atomic move.
- [ ] Root reruns the advisory filter for PASS.

### Task 3: Exact Qwen 0.21.1 boundary

**Files:** Create `src/main/kotlin/io/ltverdict/ai/QwenCode0211.kt`; test in `AdvisoryAiTest.kt`; create ADR 0010.

**Interfaces:** `QwenCode0211.invocation(nodePath, cliEntryPath, schemaPath, systemPrompt, apiKey, runtimePaths): QwenInvocation`; argv and nine-name environment allowlist are explicit.

- [ ] Test exact version/model/endpoint/flags, explicit ordinary-tool excludes
  and secret absence from argv.
- [ ] Implement only the fixed descriptor and bounded outcomes; no provider registry.
- [ ] With verification permission, probe downloaded CLI against an in-container fake endpoint using `--network none`, read-only mounts, tmpfs state, cleared environment and external timeout.
- [ ] Fake success does not authorize live execution. Live call requires a fixed OpenRouter relay or equivalent proven endpoint-only policy.

### Task 4: Root-owned API/UI hook

**Files:** Root only: `LocalApi.kt`, `ui/src/api.ts`, `ui/src/types.ts`, `ui/src/AnalysisView.vue`.

**Interfaces:** Construct `AdvisoryAiService(runBundles, AiAdviceStore(dataDirectory, runBundles), runner)`; call `generate(runId, analysisId): AdviceRunResult` only from explicit POST and `read(runId, analysisId): StoredAdvice?` from GET/reload.

- [ ] Add explicit generate/read routes after shared files are released.
- [ ] Show advisory/caveats/safe failure separately from deterministic verdict.
- [ ] Verify ordinary analysis/open/export never invokes the runner.
