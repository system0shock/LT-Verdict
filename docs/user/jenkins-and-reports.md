# Jenkins, Grafana и Confluence-ready отчёты

Этот сценарий запускает существующую Jenkins job, отслеживает queue/build,
импортирует архивированный JTL или `simulation.log` и готовит evidence для
отчёта. LT Verdict не создаёт job, не подключается к agent по SSH и не хранит
Jenkins credentials в browser, RunBundle или transport journal.

## Jenkins configuration

Credentials задаются только именами environment variables. Значения читает
loopback backend непосредственно перед HTTP request.

```json
{
  "schema_version": "jenkins-connections.v1",
  "profiles": [
    {
      "id": "perf",
      "controller": "https://jenkins.example/",
      "job_path": "job/performance-test",
      "auth": {
        "username_env": "JENKINS_USER",
        "api_token_env": "JENKINS_API_TOKEN"
      },
      "parameter_names": ["SCENARIO", "PASSWORD"],
      "sensitive_parameter_names": ["PASSWORD"],
      "artifact_paths": ["run/results.jtl", "run/simulation.log"],
      "correlation_parameter": "LT_VERDICT_TRIGGER_ATTEMPT_ID",
      "timeout_ms": 30000,
      "poll_interval_ms": 1000,
      "reconciliation_polls": 3,
      "max_artifact_bytes": 3221225472
    }
  ]
}
```

Job должна принимать `correlation_parameter` и показывать его значение в
Jenkins queue/build API. Она также должна архивировать один из разрешённых
`artifact_paths`. Имена parameters ограничивают поля Web UI; sensitive values
передаются Jenkins, но не входят в journal. Config parser отклоняет неизвестные
поля, embedded credentials, path traversal, повторяющиеся id и значения вне
limits. HTTPS обязателен по умолчанию. `allow_insecure_http: true` — явный
opt-in только для закрытого тестового контура.

После задания `JENKINS_USER` и `JENKINS_API_TOKEN` backend запускается так:

```powershell
ltv ui --data-dir .lt-verdict --jenkins-config jenkins-connections.json
```

Если нужны online sources и Grafana, добавляется существующий config:

```powershell
ltv ui --data-dir .lt-verdict --connections source-connections.json --jenkins-config jenkins-connections.json
```

## Trigger и recovery

Перед `POST buildWithParameters` LT Verdict принудительно записывает trigger
intent в `<data-dir>/transport/jenkins/<profile-id>/<attempt-id>.jsonl`.
Transport journal содержит hash несекретных parameters, но не их значения,
username или API token.

Если ответ на `POST` потерян, состояние становится `RECONCILING`, а после
неоднозначного или безрезультатного bounded поиска — `TRIGGER_UNKNOWN`. В этом
состоянии автоматический повторный `POST` запрещён. Нужно выбрать «Сверить
запуск с Jenkins», проверить job вручную либо явно создать новую попытку после
разрешения неизвестного исхода.

После появления build LT Verdict получает allowlisted artifact streaming,
ограничивает размер, вычисляет SHA-256 и публикует файл atomic move в отдельный
каталог attempt. Отсутствующий файл оставляет `AWAITING_ARTIFACT`; это не
означает `FAIL` нагрузочного теста. После исправления archive configuration
можно повторить получение. Повтор операции после потери browser response
переиспользует уже проверенный immutable файл; импорт в RunBundle остаётся
content-addressed. Ручная загрузка JTL/`simulation.log` сохраняется как fallback.

## Grafana evidence

Grafana rendering доступен только для настроенного source profile с
`transport: "grafana_proxy"`. Browser передаёт profile id, dashboard UID,
panel id и сохранённое окно run; URL и auth из browser не принимаются.
Dashboard UID ограничен safe identifier, panel id должен быть положительным,
окно — не более 31 дня. Render идёт через общий `SourceHttp` governor и auth,
не следует redirects и принимает не более 8 MiB.

Ошибка render не меняет verdict: credential-free source link остаётся
доступной, PNG помечается unavailable. Grafana не становится первичным
источником raw metrics.

## Confluence-ready output

Confluence storage XHTML строится из того же canonical result, что JSON, HTML
и AsciiDoc, с contextual escaping acquired strings:

```powershell
ltv report <run-id> <analysis-id> --format confluence --data-dir .lt-verdict
```

В Web UI тот же output скачивается как `.xhtml`. Это локальный canonical
artifact для ручной загрузки.

`<run-id>` и `<analysis-id>` печатает `ltv analyze` в stderr строкой
`analysis_id=<id> run_id=<id>`; искать каталог анализа вручную не нужно.
В CI достаточно `ltv analyze ... --out-dir <dir>`: один вызов кладёт
`report.html`, `chart.svg`, `junit.xml` и остальные артефакты (см. раздел CLI
в [руководстве локального анализа](slice-1-local-analysis.md)).

REST publisher оставлен fail-soft skeleton со статусами `NOT_CONFIGURED`,
`PUBLISHING`, `PUBLISHED` и `FAILED`. Он работает только с явно переданной
Cloud/Data Center publish strategy; endpoint, auth и page update semantics не
угадываются. Ошибка публикации не удаляет локальный output и не меняет analysis
или verdict.

Реальная Jenkins job, Grafana render и Confluence publication не выполнялись
при подготовке. Их credentials, permissions, controller/plugin versions,
artifact conventions и network routes проверяются в отдельной live acceptance.

## Статический load chart

CLI `ltv report <run-id> <analysis-id> --format svg --data-dir .lt-verdict`
и ссылка SVG в UI дают самостоятельный график из сохранённого `rollup-60s.ndjson`:
RPS, errors/bin и p95 на общей относительной оси. До 500 bins, gaps не соединяются;
усечение и отсутствие данных явно показаны. Генерация выполняется JVM без browser,
Grafana и новых зависимостей. Это отдельный SVG artifact; PNG и встраивание графика
в HTML/AsciiDoc не входят в эту поставку.
