# Stage 0 / Slice 0 — milestone report

**Дата:** 2026-08-30

**Governance-идентификатор:** `Stage 0 — Minimal foundation`

**Плановый срез:** `Slice 0`

**Локальный статус:** `READY FOR REVIEW`

**Решение:** `PENDING_USER_REVIEW`

## Результат

- Принят нормативный local-first
  [PRC/PRD v0.6](../../lt-verdict-prc-prd-v0.6.md) и компактный roadmap.
- Добавлены ровно две минимальные JSON Schema: `run.v1` и
  `analysis-result.v1`.
- Их назначение и evolution policy зафиксированы в
  [`ADR 0001`](../adr/0001-slice-0-public-contracts.md).
- Добавлены синтетические JMeter JTL и Gatling `simulation.log` с закреплёнными
  LF и SHA-256.
- Один stdlib-only verifier проверяет JSON, обязательные верхнеуровневые поля
  примеров, LT Verdict RFC 3339 profile timestamps, безопасность путей и
  SHA-256 входов.
- Deferred-feature ledger сохраняет каждую согласованную MVP-фичу за Slice
  1–10 или Post-MVP.

## Локальные evidence

- `python tools/verify_slice0.py` — exit `0`,
  `slice 0 verification: OK`.
- Targeted negative probes: verifier отклоняет Windows `NUL` с exit `1`,
  schema отклоняет backslash traversal, rooted/UNC и URL paths.
- `python -m py_compile tools/verify_slice0.py tools/test_verify_slice0.py` —
  exit `0`.
- `python -m unittest tools.test_verify_slice0 -v` — exit `0`, regression tests
  portable-path и RFC 3339 contracts проходят.
- `npx --yes markdownlint-cli2@0.23.2 "**/*.md"` — exit `0`, ошибок среди
  non-ignored Markdown files нет.
- `git diff --check` — exit `0`.
- `git grep -n -I -E "(BEGIN (RSA|OPENSSH|EC) PRIVATE KEY|AKIA[0-9A-Z]{16})" -- .`
  — exit `1` без вывода, то есть совпадений нет.

Проверки выполнены локально на Python `3.14.3`.

## Scope изменений

- baseline и roadmap: нормативный PRC v0.6, alignment review, superseded-маркеры
  v0.5, README, CHANGELOG и явные lint exclusions для accepted/superseded
  документов;
- public contract decision: ADR 0001 с evolution policy и обязательной RFC 3339
  validation;
- executable evidence: две schemas, два fixtures, один verifier, regression
  test, CI gate и точечные LF-правила;
- gate candidate: отчёт `stage-0.md`, отображение Slice 0 на Stage 0, статус в
  roadmap и ссылка из README.

Production runtime и зависимости не добавлены.

## Ограничения

- Результаты remote CI не входят в локальные evidence этого отчёта; merge и tag
  не выполнялись.
- Verifier намеренно не является полным JSON Schema engine: Slice 0 проверяет
  только JSON, required-поля примеров, timestamps, пути и SHA-256.
- Full Markdown gate соблюдает exclusions из `.markdownlint-cli2.yaml`:
  нормативный PRC v0.6 и superseded legacy documents сохраняют reviewed
  formatting и не входят в число linted files.

## Непроверенные предположения

- Cross-platform checkout отдельно не выполнялся; `eol=lf` проверен через Git
  attributes в текущем Windows worktree.
- Решение о приёмке и переходе к Slice 1 остаётся за пользователем.
