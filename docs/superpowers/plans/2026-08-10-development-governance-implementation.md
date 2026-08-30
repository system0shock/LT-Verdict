# Development Governance Implementation Plan

<!-- markdownlint-disable MD013 -->

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Внедрить утверждённый регламент разработки LT Verdict для Codex и AI-агентов через корневой `AGENTS.md`, нормативную документацию, GitHub-шаблоны и CI-проверки без отдельных проектных скриптов.

**Architecture:** Корневой `AGENTS.md` хранит краткие обязательные инструкции и модельную политику субагентов; `docs/development-process.md` служит полным нормативным источником; `README.md` и `CHANGELOG.md` обеспечивают навигацию и историю изменений; `.github/` реализует review contract и независимые проверки Markdown, ссылок, PR-title и секретов. Superpowers остаётся владельцем процессных workflow, а правила LT Verdict только добавляют проектные ограничения.

**Tech Stack:** Markdown, Git, GitHub Pull Requests, GitHub Actions, markdownlint-cli2, lychee, Gitleaks, Conventional Commits.

## Global Constraints

- Утверждённый источник требований: `docs/superpowers/specs/2026-08-10-project-development-governance-design.md`.
- Не создавать project skills в этом rollout: проектные соглашения принадлежат `AGENTS.md`, механические проверки — CI.
- Не создавать файлы в `scripts/` и не добавлять локальные Git hooks.
- Не изменять и не добавлять в индекс существующие пользовательские untracked-файлы: `docs/stage-1-spec.md`, `docs/superpowers/plans/2026-08-10-development-plan.md`, `k3-prc-review.md`, `prc-lt-verdict-v0.5.md`.
- Использовать только явный `git add -- <paths>`; не использовать `git add .` и `git add -A`.
- Любое изменение поведения процесса должно обновлять `AGENTS.md` и `docs/development-process.md` в одном PR.
- GitHub Actions закрепляются полным commit SHA с комментарием исходной версии.
- Вызов субагента следует Superpowers; модель выбирается по проектной матрице: Luna low для механики, Terra medium/high для инженерной работы и простого ревью, Sol max для сложного ревью.
- Если Luna недоступна в рантайме, использовать ближайшую более сильную доступную модель; сложное ревью не понижать ниже Sol max без явного согласования.
- Push, merge, rebase общей ветки, tag и GitHub Release выполняются только после явного разрешения пользователя.

---

### Task 1: Корневые инструкции AI-агентов

**Files:**

- Create: `AGENTS.md`
- Reference: `docs/superpowers/specs/2026-08-10-project-development-governance-design.md`

**Interfaces:**

- Consumes: утверждённую спецификацию процесса и действующие Superpowers skills.
- Produces: краткий обязательный instruction layer, автоматически загружаемый Codex из корня репозитория.

- [ ] **Step 1: Зафиксировать исходное отсутствие проектного файла**

Run:

```powershell
Test-Path -LiteralPath 'AGENTS.md'
```

Expected: `False`. Если файл появился после составления плана, остановиться, прочитать его и объединить требования без перезаписи пользовательского содержимого.

- [ ] **Step 2: Создать `AGENTS.md` с обязательными разделами**

Создать файл следующей структуры и смысла:

```markdown
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
```

- [ ] **Step 3: Проверить полноту и размер инструкции**

Run:

```powershell
rg -n "Superpowers workflow|Subagents and model routing|gpt-5.6-luna|gpt-5.6-terra|gpt-5.6-sol|Completion gate|/graphify" AGENTS.md
(Get-Item -LiteralPath 'AGENTS.md').Length
```

Expected: все семь смысловых элементов найдены; размер меньше `32768` bytes.

- [ ] **Step 4: Проверить чистоту diff**

Run:

```powershell
git add -- AGENTS.md
git diff --cached --check -- AGENTS.md
git diff --cached --name-only
git status --short
```

Expected: staged diff check без вывода; staged list содержит только `AGENTS.md`; пользовательские untracked-файлы остаются untracked и неизменными.

- [ ] **Step 5: Commit**

```powershell
git commit -m "docs: add agent development rules"
```

---

### Task 2: Нормативный регламент разработки

**Files:**

- Create: `docs/development-process.md`
- Reference: `docs/superpowers/specs/2026-08-10-project-development-governance-design.md`

**Interfaces:**

- Consumes: краткие обязательства из `AGENTS.md` и утверждённый дизайн.
- Produces: полный нормативный источник для разработчика, агентов и PR-review.

- [ ] **Step 1: Подтвердить, что нормативный файл ещё не существует**

Run:

```powershell
Test-Path -LiteralPath 'docs/development-process.md'
```

Expected: `False`. При `True` остановиться и выполнить содержательное merge-review вместо перезаписи.

- [ ] **Step 2: Создать структуру регламента**

Документ должен начинаться так:

```markdown
# LT Verdict — регламент разработки

**Статус:** нормативный

**Владелец:** проект LT Verdict

Этот документ определяет обязательный процесс разработки. Краткие инструкции
для AI-агентов находятся в [`AGENTS.md`](../AGENTS.md), а обоснование решений —
в [дизайне регламента](superpowers/specs/2026-08-10-project-development-governance-design.md).
```

Затем добавить следующие нормативные разделы с указанным содержанием:

1. `Принципы` — `main` всегда работоспособна; доказательства предшествуют заявлениям; документация входит в изменение; пользователь управляет необратимыми и remote-операциями.
2. `Жизненный цикл задачи` — brief → brainstorming/spec → plan → worktree/branch → TDD → verification → review → решение пользователя о merge.
3. `Ветки и worktree` — допустимые префиксы, kebab-case, одна задача на ветку, актуализация относительно `main`, запрет прямого push.
4. `Коммиты` — Conventional Commits, допустимые типы, атомарность, явный staging, запрет переписывать пользовательскую историю.
5. `Pull Request` — связь с issue/milestone, обязательные поля, squash merge, зелёный CI, review gate.
6. `Майлоуны и версии` — GitHub Milestones `Stage N`, exit gate, `docs/milestones/stage-N.md`, подписанные аннотированные `stage-N`, SemVer и подписанные version tags.
7. `Документация` — целевая структура `README`, `architecture`, `adr`, `api`, `operations`, `user`, `milestones`, `superpowers/specs`, `superpowers/plans`; same-PR rule; русский prose и English identifiers.
8. `AI-агенты и Superpowers` — порядок skills и запрет создавать project skills для соглашений или механических ограничений.
9. `Матрица моделей субагентов` — точная таблица Luna low / Terra medium / Terra high / Sol max из утверждённой спецификации и fallback только вверх.
10. `Definition of Done` — требования, RED–GREEN–REFACTOR, свежий CI, diff review, docs/ADR/changelog, отсутствие секретов и посторонних изменений, явные риски.
11. `Stage gate` — DoD всех задач, exit gate, milestone-report и разрешённый пользователем подписанный tag.
12. `Исключения и сбои` — недоступная проверка не считается пройденной; красный baseline требует решения пользователя; срочность не отменяет gates.

В разделе моделей использовать эту таблицу без смысловых изменений:

```markdown
| Класс работы | Модель | Effort |
|---|---|---|
| Механическая задача с однозначным результатом | `gpt-5.6-luna` | `low` |
| Обычная инженерная задача и простое ревью | `gpt-5.6-terra` | `medium` |
| Сложная инженерная задача | `gpt-5.6-terra` | `high` |
| Сложное риск-ориентированное ревью | `gpt-5.6-sol` | `max` |
```

- [ ] **Step 3: Проверить соответствие спецификации**

Run:

```powershell
rg -n "^## |Conventional Commits|Squash|stage-N|Semantic Versioning|Definition of Done|gpt-5.6-luna|gpt-5.6-sol|подписан" docs/development-process.md
rg -n -i "\b(TBD|TODO|FIXME)\b" docs/development-process.md
```

Expected: все обязательные темы найдены; второй поиск не возвращает совпадений и завершается с кодом `1`.

- [ ] **Step 4: Проверить Markdown и diff**

Run:

```powershell
npx --yes markdownlint-cli2@0.23.2 "docs/development-process.md"
git add -- docs/development-process.md
git diff --cached --check -- docs/development-process.md
git diff --cached --name-only
```

Expected: lint и staged diff check завершаются с кодом `0`; staged list содержит только `docs/development-process.md`. Если загрузка npm заблокирована, запросить сетевое разрешение; не объявлять lint пройденным без запуска.

- [ ] **Step 5: Commit**

```powershell
git commit -m "docs: define development process"
```

---

### Task 3: Точка входа и журнал изменений

**Files:**

- Create: `README.md`
- Create: `CHANGELOG.md`
- Modify: `docs/development-process.md`

**Interfaces:**

- Consumes: tracked-документы репозитория и нормативный регламент.
- Produces: безопасную навигацию только на committed artifacts и журнал пользовательски заметных изменений.

- [ ] **Step 1: Зафиксировать исходное отсутствие файлов**

Run:

```powershell
Test-Path -LiteralPath 'README.md'
Test-Path -LiteralPath 'CHANGELOG.md'
```

Expected: оба результата `False`. Если любой файл существует, сохранить его содержание и интегрировать требования вместо перезаписи.

- [ ] **Step 2: Создать `README.md`**

Использовать следующий текст:

```markdown
# LT Verdict

LT Verdict — платформа детерминированного анализа результатов нагрузочного
тестирования и формирования проверяемого вердикта.

## Статус

Проект находится на стадии подготовки к реализации. Основные архитектурные и
процессные решения фиксируются в Git до начала разработки компонентов.

## Документация

- [Краткое резюме](exec-summary-lt-verdict.pdf)
- [Историческая базовая версия PRC](prc-lt-verdict-v0.4.md)
- [Протокол решений](docs/decisions-2026-07-20.md)
- [Регламент разработки](docs/development-process.md)
- [Дизайн регламента для AI-агентов](docs/superpowers/specs/2026-08-10-project-development-governance-design.md)
- [Анкета инфраструктуры](docs/admin-questionnaire.md)

## Разработка

Перед изменениями прочитайте [AGENTS.md](AGENTS.md) и
[регламент разработки](docs/development-process.md). Пользовательски заметные
изменения фиксируются в [CHANGELOG.md](CHANGELOG.md).
```

Не добавлять ссылки на текущие untracked-файлы до их отдельного согласованного коммита.

- [ ] **Step 3: Создать `CHANGELOG.md`**

Использовать следующий текст:

```markdown
# Changelog

Все значимые пользовательские изменения LT Verdict фиксируются в этом файле.
Формат основан на Keep a Changelog, версии следуют Semantic Versioning.

## [Unreleased]

### Added

- Регламент разработки и правила работы AI-агентов.
```

Ссылку сравнения для `[Unreleased]` добавить после появления первого version tag; до этого не публиковать искусственную self-ссылку.

- [ ] **Step 4: Добавить в регламент правило навигации**

В `docs/development-process.md` явно указать:

```markdown
`README.md` ссылается только на tracked-документы. Новый нормативный документ
добавляется в навигацию в том же PR, где он впервые коммитится.
```

- [ ] **Step 5: Проверить ссылки и Markdown**

Run:

```powershell
$links = @('exec-summary-lt-verdict.pdf','prc-lt-verdict-v0.4.md','docs/decisions-2026-07-20.md','docs/development-process.md','docs/superpowers/specs/2026-08-10-project-development-governance-design.md','docs/admin-questionnaire.md','AGENTS.md','CHANGELOG.md'); $links | ForEach-Object { if (-not (Test-Path -LiteralPath $_)) { throw "Missing link target: $_" } }
npx --yes markdownlint-cli2@0.23.2 "README.md" "CHANGELOG.md" "docs/development-process.md"
git add -- README.md CHANGELOG.md docs/development-process.md
git diff --cached --check -- README.md CHANGELOG.md docs/development-process.md
git diff --cached --name-only
```

Expected: все цели существуют; lint и staged diff check завершаются с кодом `0`; staged list содержит ровно три перечисленных файла.

- [ ] **Step 6: Commit**

```powershell
git commit -m "docs: add project documentation entry points"
```

---

### Task 4: Pull Request contract

**Files:**

- Create: `.github/pull_request_template.md`

**Interfaces:**

- Consumes: Definition of Done и Git-процесс.
- Produces: одинаковую структуру PR для человека и AI-агентов.

- [ ] **Step 1: Подтвердить отсутствие шаблона**

Run:

```powershell
Test-Path -LiteralPath '.github/pull_request_template.md'
```

Expected: `False`. При `True` объединить требования без потери существующих полей.

- [ ] **Step 2: Создать шаблон PR**

Использовать следующий текст:

```markdown
## Цель

<!-- Какую одну законченную задачу решает PR? -->

## Изменения

<!-- Краткий список существенных изменений без пересказа diff. -->

## Связи

- Issue:
- Milestone:
- Spec/plan:

## Проверка

<!-- Точные команды и фактический результат. -->

- [ ] Тесты
- [ ] Сборка
- [ ] Статический анализ
- [ ] Документальные проверки
- [ ] Полный diff просмотрен

## Документация

<!-- Перечислите обновлённые документы или напишите
`Documentation impact: none` и объясните почему. -->

## Риски и ограничения

<!-- Известные риски, непроверенные предположения и follow-up. -->

## Definition of Done

- [ ] Критерии приёмки выполнены
- [ ] RED–GREEN–REFACTOR подтверждён для изменения поведения
- [ ] CI зелёный
- [ ] ADR добавлен или обоснованно не требуется
- [ ] CHANGELOG обновлён или изменение не пользовательское
- [ ] Нет секретов, временных и несвязанных файлов
- [ ] Для milestone выполнен exit gate и подготовлен отчёт
```

- [ ] **Step 3: Проверить обязательные поля**

Run:

```powershell
rg -n "Цель|Issue:|Milestone:|Spec/plan:|Проверка|Documentation impact: none|Риски|Definition of Done|RED–GREEN–REFACTOR|CHANGELOG|exit gate" .github/pull_request_template.md
npx --yes markdownlint-cli2@0.23.2 ".github/pull_request_template.md"
git add -- .github/pull_request_template.md
git diff --cached --check -- .github/pull_request_template.md
git diff --cached --name-only
```

Expected: все поля найдены; lint и staged diff check завершаются с кодом `0`; staged list содержит только шаблон PR.

- [ ] **Step 4: Commit**

```powershell
git commit -m "docs: add pull request checklist"
```

---

### Task 5: CI для Markdown, ссылок и секретов

**Files:**

- Create: `.markdownlint-cli2.yaml`
- Create: `.github/workflows/docs-quality.yml`

**Interfaces:**

- Consumes: все tracked Markdown/HTML-файлы и Git history PR.
- Produces: обязательные checks `Markdown`, `Links`, `Secrets` без изменения файлов.

- [ ] **Step 1: Создать конфигурацию Markdown lint**

Создать `.markdownlint-cli2.yaml`:

```yaml
config:
  default: true
  MD013: false
  MD024:
    siblings_only: true
  MD033: false

ignores:
  - prc-lt-verdict-v0.4.md
  - prc-nt-analysis-platform.md
```

`MD013` отключается для технических таблиц и длинных ссылок; `MD024` допускает одинаковые заголовки только в разных разделах; `MD033` допускает существующий обоснованный inline HTML.

- [ ] **Step 2: Создать workflow документального качества**

Создать `.github/workflows/docs-quality.yml`:

```yaml
name: Docs quality

on:
  pull_request:
  push:
    branches:
      - main

permissions:
  contents: read

concurrency:
  group: docs-quality-${{ github.workflow }}-${{ github.ref }}
  cancel-in-progress: true

jobs:
  markdown:
    name: Markdown
    runs-on: ubuntu-latest
    steps:
      - name: Checkout
        uses: actions/checkout@de0fac2e4500dabe0009e67214ff5f5447ce83dd # v6.0.2
      - name: Lint Markdown
        uses: DavidAnson/markdownlint-cli2-action@1628d9b2c73e580b4cb9b6b34303457a72478c5e # v23
        with:
          globs: |
            **/*.md
            #.worktrees/**

  links:
    name: Links
    runs-on: ubuntu-latest
    steps:
      - name: Checkout
        uses: actions/checkout@de0fac2e4500dabe0009e67214ff5f5447ce83dd # v6.0.2
      - name: Check links
        uses: lycheeverse/lychee-action@e7477775783ea5526144ba13e8db5eec57747ce8 # v2
        with:
          args: >-
            --verbose
            --no-progress
            --max-retries 3
            './**/*.md'
            './**/*.html'
          fail: true

  secrets:
    name: Secrets
    runs-on: ubuntu-latest
    steps:
      - name: Checkout full history
        uses: actions/checkout@de0fac2e4500dabe0009e67214ff5f5447ce83dd # v6.0.2
        with:
          fetch-depth: 0
      - name: Scan secrets
        uses: gitleaks/gitleaks-action@e0c47f4f8be36e29cdc102c57e68cb5cbf0e8d1e # v3.0.0
        env:
          GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
```

Не добавлять write permissions и не включать auto-fix. Если репозиторий будет перенесён из личного аккаунта в GitHub Organization, до required-check необходимо проверить лицензионные условия Gitleaks Action v3 и при необходимости заменить Action на официальный Gitleaks CLI workflow.

- [ ] **Step 3: Проверить локальный Markdown baseline**

Run:

```powershell
$markdownFiles = git ls-files | Where-Object { $_ -like '*.md' }; npx --yes markdownlint-cli2@0.23.2 $markdownFiles
```

Expected: код `0`. Конфигурация исключает две исторические PRC; остальные tracked-документы проходят lint. Untracked-документы пользователя не входят в команду и не изменяются.

- [ ] **Step 4: Проверить закреплённые Actions и запрет write permissions**

Run:

```powershell
rg -n "uses: .+@[0-9a-f]{40}(\s+#\s+.+)?$" .github/workflows/docs-quality.yml
rg -n "write|@v[0-9]|@main|@master" .github/workflows/docs-quality.yml
```

Expected: первый поиск находит семь `uses` со SHA; второй не находит write permissions или плавающих refs. Комментарии `# v...` не должны ошибочно считаться refs — проверить совпадения вручную, если они появились.

- [ ] **Step 5: Проверить diff**

Run:

```powershell
git add -- .markdownlint-cli2.yaml .github/workflows/docs-quality.yml
git diff --cached --check -- .markdownlint-cli2.yaml .github/workflows/docs-quality.yml
git diff --cached --name-only
git status --short
```

Expected: staged diff check без вывода; staged list содержит только два CI-файла; untracked-файлы пользователя не staged.

- [ ] **Step 6: Commit**

```powershell
git commit -m "ci: validate documentation and secrets"
```

---

### Task 6: Проверка Conventional Commit заголовка PR

**Files:**

- Create: `.github/workflows/pr-title.yml`

**Interfaces:**

- Consumes: metadata Pull Request без checkout кода.
- Produces: required check `PR title` для squash-коммита в `main`.

- [ ] **Step 1: Создать изолированный metadata-only workflow**

Создать `.github/workflows/pr-title.yml`:

```yaml
name: PR title

on:
  pull_request_target:
    types:
      - opened
      - edited
      - reopened
      - synchronize

permissions:
  pull-requests: read

jobs:
  validate:
    name: PR title
    runs-on: ubuntu-latest
    steps:
      - name: Validate Conventional Commit title
        uses: amannn/action-semantic-pull-request@48f256284bd46cdaab1048c3721360e808335d50 # v6
        with:
          types: |
            feat
            fix
            docs
            refactor
            test
            build
            ci
            chore
            perf
            revert
          requireScope: false
        env:
          GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
```

Этот workflow намеренно не использует checkout и не исполняет код PR, поскольку запускается на `pull_request_target`.

- [ ] **Step 2: Проверить границы безопасности**

Run:

```powershell
rg -n "pull_request_target|pull-requests: read|48f256284bd46cdaab1048c3721360e808335d50" .github/workflows/pr-title.yml
rg -n "checkout|\brun:|contents: write|pull-requests: write" .github/workflows/pr-title.yml
```

Expected: первый поиск находит событие, read-only permission и SHA; второй не возвращает совпадений.

- [ ] **Step 3: Проверить diff**

Run:

```powershell
git add -- .github/workflows/pr-title.yml
git diff --cached --check -- .github/workflows/pr-title.yml
git diff --cached --name-only
```

Expected: staged diff check без вывода; staged list содержит только `.github/workflows/pr-title.yml`.

- [ ] **Step 4: Commit**

```powershell
git commit -m "ci: validate pull request titles"
```

---

### Task 7: Итоговая проверка и тестовый PR

**Files:**

- Modify only if verification finds defects: `AGENTS.md`, `README.md`, `CHANGELOG.md`, `docs/development-process.md`, `.github/pull_request_template.md`, `.markdownlint-cli2.yaml`, `.github/workflows/docs-quality.yml`, `.github/workflows/pr-title.yml`

**Interfaces:**

- Consumes: все артефакты Tasks 1–6.
- Produces: проверенный набор изменений и фактические GitHub check results перед merge.

- [ ] **Step 1: Проверить scope коммитов и рабочее дерево**

Run:

```powershell
git log --oneline --decorate -8
git status --short
git diff --stat origin/main...HEAD
git diff --name-only origin/main...HEAD
```

Expected: изменения регламента отделены тематическими коммитами; пользовательские untracked-файлы не входят в diff и не staged.

- [ ] **Step 2: Запустить полный локальный документальный gate**

Run:

```powershell
$markdownFiles = git ls-files | Where-Object { $_ -like '*.md' }; npx --yes markdownlint-cli2@0.23.2 $markdownFiles
git diff origin/main...HEAD --check
rg -n -i "\b(TBD|TODO|FIXME)\b" AGENTS.md README.md CHANGELOG.md docs/development-process.md .github/pull_request_template.md .github/workflows
```

Expected: markdownlint и diff check возвращают `0`; placeholder search возвращает `1` без совпадений.

- [ ] **Step 3: Выполнить requirements review**

Построчно сверить итоговый diff с разделами 4–12 спецификации. Зафиксировать в отчёте:

- GitHub Flow и Conventional Commits;
- milestones, signed annotated tags и SemVer;
- same-PR documentation rule;
- Superpowers compatibility;
- Luna/Terra/Sol Max routing и fallback;
- условия допустимости project skills и отсутствие project scripts;
- Definition of Done и remote-operation consent;
- Markdown, link, PR-title и secret checks.

Expected: у каждого требования есть точный файл и раздел; пробелы исправлены до review.

- [ ] **Step 4: Запросить code review**

Использовать `superpowers:requesting-code-review`. Простое документальное ревью выполняет Terra medium. Если reviewer выявляет риск безопасности workflow, permission model, supply chain или обход branch gate, повторное сложное ревью выполняет Sol max.

Expected: Critical и Important замечания исправлены; Minor либо исправлены, либо явно записаны.

- [ ] **Step 5: При необходимости сделать один исправляющий коммит**

```powershell
git add -- AGENTS.md README.md CHANGELOG.md docs/development-process.md .github/pull_request_template.md .markdownlint-cli2.yaml .github/workflows/docs-quality.yml .github/workflows/pr-title.yml
git commit -m "docs: address governance review findings"
```

Выполнять этот шаг только при фактических изменениях. Не создавать пустой коммит.

- [ ] **Step 6: Повторить свежую проверку перед публикацией**

Run:

```powershell
$markdownFiles = git ls-files | Where-Object { $_ -like '*.md' }; npx --yes markdownlint-cli2@0.23.2 $markdownFiles
git diff origin/main...HEAD --check
git status --short
```

Expected: проверки зелёные; staged-файлов нет; пользовательские untracked-файлы сохранены.

- [ ] **Step 7: Получить разрешение пользователя и создать тестовый PR**

Использовать `superpowers:finishing-a-development-branch`. Выбрать push/PR только после явного решения пользователя. Заголовок PR:

```text
docs: establish development governance
```

`PR title` использует `pull_request_target`, поэтому workflow должен сначала
появиться в default branch. Rollout выполняется в два PR после явного разрешения
пользователя:

1. Минимальный bootstrap PR добавляет только `.github/workflows/pr-title.yml` с
   заголовком `ci: bootstrap pull request title check`. Отсутствие `PR title` на
   этом bootstrap PR фиксируется как наблюдаемое ограничение.
2. После merge bootstrap PR governance PR заполняет шаблон, дожидается checks
   `Markdown`, `Links`, `Secrets`, `PR title` и записывает фактические
   ссылки/результаты.

При недоступном или красном обязательном check не считать регламент внедрённым.

- [ ] **Step 8: Настроить branch protection вручную**

После первого успешного PR пользователь или администратор GitHub включает для `main`:

- запрет прямого push;
- обязательный Pull Request;
- required checks `Markdown`, `Links`, `Secrets`, `PR title`;
- Squash and Merge;
- использование PR title как default squash commit message.

Это внешнее изменение состояния репозитория выполняется только с разрешения пользователя и подтверждается скриншотом либо повторным чтением настроек GitHub.
