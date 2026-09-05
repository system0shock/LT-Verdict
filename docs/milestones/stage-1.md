# Stage 1 / Slice 1 — candidate milestone report

**Дата:** 2026-09-05

**Governance-идентификатор:** `Stage 1 — Local usable shell`

**Плановый срез:** `Slice 1`

**Статус gate:** `GATE_PENDING`

**Решение:** `PENDING_USER_REVIEW`

Этот отчёт не разрешает merge, tag или release. Stage 1 не может перейти в
accepted state без зелёных `runtime` и `performance` jobs и отдельного решения
пользователя.

## Candidate result

- Один JDK 21 process предоставляет CLI и Vue Web UI на случайном loopback
  port без outbound network client.
- Streaming parsers поддерживают JMeter JTL CSV/XML и Gatling text/binary
  versions, закреплённые Slice 1 fixtures.
- Двухпроходный analytical core строит deterministic metrics, sparse one-second
  evidence и rollups 10/30/60 seconds.
- Strict `policy.v1` поддерживает четыре metric, exact transaction matching и
  deterministic `PASS`/`FAIL`/`NO_POLICY`/`NO_VERDICT`.
- Immutable filesystem RunBundle использует content identity, manifests,
  exclusive data-directory lock и atomic publication.
- Approved light/dark local UI показывает upload/job progress, cancel/`BUSY`,
  validity, coverage, verdict, policy checks, transactions и normalized data.

## Fresh local evidence

Финальный набор команд повторён после branch review и исправлений. Получены
следующие наблюдаемые результаты на Windows, JDK `21.0.9`, Node.js `24.14.0` и
Python `3.14.3`:

| Команда | Exit | Наблюдение |
| --- | ---: | --- |
| `python tools/verify_slice0.py` | `0` | `slice 0 verification: OK` |
| `python -m unittest tools.test_verify_slice0 tools.test_generate_jtl -v` | `0` | `4` tests passed |
| `python -m unittest tools.test_generate_jtl -v` | `0` | `2` generator/probe tests passed |
| `.\gradlew.bat --no-daemon clean check installDist` | `0` | `BUILD SUCCESSFUL` |
| `npm --prefix ui ci` | `0` | `145` packages, `0` vulnerabilities |
| `npm --prefix ui run typecheck` | `0` | Vue/TypeScript typecheck passed |
| `npm --prefix ui run lint` | `0` | ESLint passed |
| `npm --prefix ui run test:contracts` | `0` | Policy-schema contract check passed |
| `npm --prefix ui run build` | `0` | Vite production build passed |
| `npm --prefix ui run e2e` | `0` | Fresh full run: `13/13` tests passed |
| `npm --prefix ui ci --offline` | `0` | Locked npm reinstall completed without network |
| `.\gradlew.bat -PnpmOffline=true --offline --no-daemon clean check installDist` | `0` | Offline `BUILD SUCCESSFUL` |
| `npx --yes markdownlint-cli2@0.23.2 "**/*.md"` | `0` | Project Markdown files, `0` issues |
| `git diff --check` | `0` | No whitespace errors |
| tracked secret scan | `0` | No credential-like assignment found |

Branch review подтвердил семь замечаний: cached-analysis identity, unknown run,
raw policy import, metrics/CSV resource ceilings, buckets для invalid result и
полноту runtime workflow. Они исправлены targeted tests; fresh full E2E прошёл
`13/13`. Замечание о неограниченном chunked upload отклонено: Ktor применяет
переданный `formFieldLimit` и к file parts, а превышение лимита завершается 413.
Дополнительно final gate выявил только Kotlin formatting в новых tests; после
исправления fresh Gradle gate прошёл полностью.

Sandboxed попытки Python/Vite/Playwright/Gradle, которым Windows sandbox запретил
system temp, child process или Gradle cache, не учитываются как code failures;
те же команды были повторены с необходимым filesystem/process access.

## CI evidence

Branch не push-ился: пользователь не разрешал внешнюю интеграцию. Поэтому URL и
job conclusions пока отсутствуют.

| Job | Run URL | Conclusion |
| --- | --- | --- |
| `runtime` | not available | `NOT_RUN` |
| `performance` | not available | `NOT_RUN` |

Новый workflow закрепляет checkout, JDK и Node actions полными commit SHA,
выполняет runtime, browser, offline, Markdown, diff и secret gates, а затем Linux
performance probe.

## Performance gate

Probe создаёт отдельный warm-up на `1 000 000` rows и три measured runs на
`10 000 000` rows. Каждый measured analysis получает fresh process/data
directory, two-CPU affinity, effective `-Xmx1536m` и timeout `600 s`.
Локальный запуск в WSL 2 использовал JRE `21.0.9+10` и свежий `installDist` из
ветки исправления histogram encoding memory.

| Run | Elapsed | Peak RSS | Result SHA-256 | Статус |
| --- | ---: | ---: | --- | --- |
| Warm-up, 1M | excluded | not recorded | not recorded | `PASS (local WSL)` |
| Measured 1, 10M | 48.53 s | 582388 KiB | `583a46a9844089889fdaeaae13ad2878a963e2016fea6d70ed53d0aea9b56c82` | `PASS (local WSL)` |
| Measured 2, 10M | 56.01 s | 591292 KiB | `583a46a9844089889fdaeaae13ad2878a963e2016fea6d70ed53d0aea9b56c82` | `PASS (local WSL)` |
| Measured 3, 10M | 55.55 s | 588152 KiB | `583a46a9844089889fdaeaae13ad2878a963e2016fea6d70ed53d0aea9b56c82` | `PASS (local WSL)` |

Required ceilings: elapsed `<= 600 s`, peak RSS `< 2 GiB`, одинаковый canonical
result SHA-256 во всех трёх measured runs. Локальный WSL run выполнил predicates
probe, но не заменяет обязательный green `performance` CI job для закрытия gate.

## Scope изменений

- build/runtime: Gradle wrapper, locked JVM/npm dependencies и single-process
  application distribution;
- contracts/evidence: accepted ADR 0002/0003, `policy.v1`, parser/security/
  normalization fixtures и deterministic results;
- production core: secure ingestion, metrics/policy evaluation, RunBundle,
  bounded jobs, CLI и private loopback API;
- Web UI: approved shell, light/dark themes, policy editor, progress/verdict/
  evidence views and security/accessibility behavior;
- quality gates: Kotlin/unit/integration/browser tests, deterministic JTL
  generator, Linux bounded probe, runtime/offline/performance workflow;
- documentation: architecture, user guide, normative external-AI prompt,
  README, CHANGELOG, roadmap и этот candidate report.

Gate-found changes outside the original Task 14 file list ограничены regression
fixes и tests для storage/API, parser/resource limits, policy import, invalid UI,
accessibility и workflow checks. Новых production dependencies и public
contracts не добавлено.

## Known limits

- Runtime остаётся local-only: bind только `127.0.0.1`, один writer process на
  data directory, без RBAC/tenancy/server deployment.
- Charts, widget movement/layout persistence, remote sources, reports и
  publishing остаются в назначенных будущих slices.
- Manual 1440 px visual pass через in-app browser не выполнен из-за отсутствия
  доступного browser connection; automated Chromium, accessibility и structural
  theme tests входят в final gate.
- GitHub job conclusions отсутствуют; приведённые Linux 10M performance numbers
  получены локально в WSL 2, а не в CI.

## Непроверенные предположения

- GitHub-hosted `ubuntu-latest` предоставляет два доступных CPU ids для
  `taskset -c 0,1` и GNU `/usr/bin/time`.
- Cross-platform source distribution проверена на Windows локально и должна
  быть подтверждена Linux runtime job.

## Следующее решение

После пройденного local gate branch можно push/open PR только по отдельной
команде пользователя. Даже после green CI milestone остаётся
`PENDING_USER_REVIEW`, пока пользователь явно не примет Slice 1.
