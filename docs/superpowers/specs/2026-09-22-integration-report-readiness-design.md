# Integration and report readiness design

**Статус:** scoped design для плана
[`2026-09-21-mvp-acceptance-readiness.md`](../plans/archive/2026-09-21-mvp-acceptance-readiness.md).

## Граница

Этот блок добавляет локально проверяемые адаптеры Jenkins, Grafana и
Confluence. Общие HTTP routes, CLI, Web UI и внешняя приёмка подключаются
отдельно владельцем shared-файлов. Новые production dependencies не нужны.

## Jenkins

`readJenkinsConnections(InputStream)` читает не более 1 MiB strict JSON
`jenkins-connections.v1`. В нём не более 16 profiles; неизвестные поля,
повторяющиеся id/list values и значения вне bounds отклоняются с
`JENKINS_CONFIG_INVALID`. Profile содержит controller/job/artifact allowlists,
разрешённые parameter names, subset sensitive parameter names, bounded limits
и только имена environment variables для username/API token. Его browser-safe
summary не содержит auth references и sensitive names.
HTTPS обязателен по умолчанию; HTTP доступен только через явный
`allow_insecure_http` для закрытого тестового контура.

```json
{"schema_version":"jenkins-connections.v1","profiles":[{"id":"perf","controller":"https://jenkins.example/","job_path":"job/performance-test","auth":{"username_env":"JENKINS_USER","api_token_env":"JENKINS_API_TOKEN"},"parameter_names":["SCENARIO","PASSWORD"],"sensitive_parameter_names":["PASSWORD"],"artifact_paths":["run/results.jtl","run/simulation.log"],"correlation_parameter":"LT_VERDICT_TRIGGER_ATTEMPT_ID","timeout_ms":30000,"poll_interval_ms":1000,"reconciliation_polls":3,"max_artifact_bytes":3221225472}]}
```

`JenkinsConnections.workflow(id, DataDirectory, environment)` создаёт workflow
с journal в `<data-dir>/transport/jenkins`. `JenkinsWorkflow` получает
валидированный `JenkinsProfile` и callback для чтения environment. Credentials
разрешаются непосредственно перед HTTP auth и не записываются в journal,
result или exception text.

Публичная для текущего JVM-модуля граница:

```kotlin
JenkinsWorkflow.trigger(request): JenkinsRunState
JenkinsWorkflow.reconcile(attemptId): JenkinsRunState
JenkinsWorkflow.advance(attemptId): JenkinsRunState
JenkinsWorkflow.collectArtifact(attemptId, expectation, destinationRoot): JenkinsRunState
JenkinsWorkflow.listStates(limit = 20): List<JenkinsRunState>
```

До `POST buildWithParameters` workflow создаёт и принудительно сбрасывает на
диск append-only journal с `trigger_attempt_id`, job id, SHA-256 canonical
несекретных parameters, timestamp и correlation parameter. Trigger `POST`
выполняется ровно один раз. Любой неопределённый transport outcome переводит
attempt в reconciliation; повторный `POST` этим API невозможен. Ноль или
несколько совпадений после bounded reconciliation дают `TRIGGER_UNKNOWN`.

HTTP redirects отключены. Controller, queue/build URL и artifact path проходят
same-origin/allowlist validation. Crumb получается отдельным bounded `GET` и
добавляется только к trigger request. Queue/build polling ограничено profile
limits. Отсутствующий artifact даёт `AWAITING_ARTIFACT`.

Artifact читается streaming во временный файл в каталоге назначения, с лимитом
bytes и SHA-256. Только совпавшие size/hash публикуются atomic move; при отказе
staging удаляется, существующий immutable destination не перезаписывается.

## Reports and Confluence

`renderConfluenceReport(resultBytes, analysisId)` строит escaped Confluence
storage XHTML из того же canonical result JSON, что HTML и AsciiDoc. Renderer
не выполняет сеть и не принимает credentials.

`ConfluencePublisher(strategy?).publish(ConfluencePublishRequest)` возвращает
`NOT_CONFIGURED`, `PUBLISHED` или `FAILED`. Adapter принимает явно заданный
endpoint/request builder; Cloud/Data Center page strategy не угадывается.
Transport/auth failure остаётся значением результата, не exception для
analysis и не удаляет локальный report.

`renderSavedLoadChart(path, rollup)` строит deterministic UTF-8 SVG из
сохранённого `rollup-60s.ndjson` без новой зависимости. Renderer читает не
более 501 bounded rows, показывает первые 500 bins и явно отмечает truncation.
RPS, errors и P95 используют общую относительную временную ось; пропущенные
bins не соединяются. Отсутствующий или пустой artifact даёт честный
`Load chart unavailable`, а malformed row — контролируемый
`SAVED_BUCKETS_INVALID`; SVG не влияет на verdict.

## Grafana

`grafanaPanelLink(profile, request)` и `grafanaRenderUri(profile, request)`
принимают только credential-free `http`/`https` base URL и bounded
identifiers/time range. `renderGrafanaPanel(profile, request, fetch)` получает
optional PNG через общий `SourceHttp` governor и возвращает
success/unavailable, не меняя verdict. Grafana не становится источником
canonical raw metrics.

## Локальная проверка

JUnit tests с loopback fake HTTP server проверяют: intent-before-POST,
отсутствие blind retry, однозначный/неоднозначный reconcile, queue/build
transition, missing/corrupt/oversize artifact, traversal и cross-origin reject,
отсутствие secrets в journal, Confluence escaping/fail-soft и Grafana safe URL /
bounded render. Реальные внешние jobs, публикации и render не запускаются.
