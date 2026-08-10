# LT Verdict agent instructions

## Instruction priority

- Follow direct user instructions first.
- Use every applicable skill before acting.
- Superpowers owns process workflows; these rules add LT Verdict constraints.
- Read `docs/development-process.md` for the full policy.

## Before changing files

1. Read relevant PRC, specs, ADRs, plans, and the current milestone gate.
2. Run `git status --short --branch` and preserve unrelated changes.
3. Define observable acceptance criteria and verification commands.
4. Use an isolated branch/worktree for implementation when supported.

## Superpowers workflow

- New behavior: `superpowers:brainstorming`, then `superpowers:writing-plans`.
- Plan execution: `superpowers:using-git-worktrees`, then
  `superpowers:subagent-driven-development` or `superpowers:executing-plans`.
- Features, fixes, refactors: `superpowers:test-driven-development`.
- Bugs and unexpected failures: `superpowers:systematic-debugging`.
- Completion: `superpowers:verification-before-completion`, then
  `superpowers:requesting-code-review` and
  `superpowers:finishing-a-development-branch`.

## Subagents and model routing

- Delegate only when Superpowers or the user calls for delegation.
- Independent tasks may run in parallel; shared-state or sequential tasks may not.
- Mechanical work with an exact output contract: `gpt-5.6-luna`, effort `low`.
- Normal engineering work and simple review: `gpt-5.6-terra`, effort `medium`.
- Complex engineering work: `gpt-5.6-terra`, effort `high`.
- Security, concurrency, data integrity, migrations, public contracts,
  architecture, performance, and milestone-gate review:
  `gpt-5.6-sol`, effort `max`.
- If a model is unavailable, use the nearest stronger available model with no
  lower effort. Never downgrade complex review to Luna.
- The root agent independently verifies subagent output and owns the result.

## Git

- Branch names: `feat/`, `fix/`, `docs/`, `refactor/`, `test/`, or `chore/`
  plus a short kebab-case description.
- One branch and one PR contain one finished concern.
- Use atomic Conventional Commits.
- Stage only explicit task files; never use broad staging in a dirty tree.
- Never rewrite user commits or discard user changes.
- Do not push, merge, rebase shared branches, tag, or release without explicit
  user permission.

## Documentation

- Update technical and user documentation in the same PR as behavior changes.
- Record significant architecture, API, schema, dependency, and operations
  decisions in an ADR.
- Update `CHANGELOG.md` for user-visible changes.
- If documentation is not needed, state `Documentation impact: none` and why.
- Russian is the default prose language; preserve English identifiers and
  standard technical terms.

## Completion gate

- Re-read requirements and inspect the full diff.
- Run fresh applicable tests, build, lint, documentation, and secret checks.
- Confirm no unrelated files are staged or modified.
- Report commands, results, limitations, and unverified assumptions.
- A task is not complete until its Definition of Done passes.
- A stage is not complete until its exit gate and milestone report pass.

## User commands

- On `/graphify`, invoke the available `graphify` skill before any other action.
