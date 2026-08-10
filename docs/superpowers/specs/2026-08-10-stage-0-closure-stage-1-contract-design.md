# Закрытие этапа 0 и контракт этапа 1 — дизайн

**Дата:** 2026-08-10

**Статус:** утверждён пользователем 2026-08-10

**Основание:** PRC v0.5, `docs/superpowers/plans/2026-08-10-development-plan.md`,
`docs/stage-1-spec.md` и exit gate этапа 0.

## Цель и границы

Цель изменения — закрыть полный exit gate этапа 0 и сделать спецификацию
Ingest-ядра однозначным входом для реализации этапа 1. В изменение входят
контракты и fixture/test-support артефакты. Production-код не создаётся.

Готовый набор обязан:

1. зафиксировать lifecycle прогона и единственный сигнал окончания ingest;
2. определить authentication, authorization и идемпотентность каждого API;
3. описать поддерживаемые Gatling/JTL форматы по байтам и полям;
4. закрепить версии и provenance всех оракулов;
5. сделать performance gate воспроизводимым и бинарным;
6. хранить локальные golden fixtures с SHA-256 и ожидаемыми результатами;
7. доказать закрытие этапа 0 milestone-отчётом и локальной проверкой;
8. синхронизировать PRC, основной план, stage-1-spec, README и CHANGELOG.

## Выбранный подход

Используется evidence-bearing closure: небольшие реальные tool-generated
fixtures и их оракулы хранятся в репозитории вместе с manifest и локальным
verifier. Рецепт без артефактов не закрывает gate, а внешний artifact store
создал бы недоступную офлайн-зависимость.

Нормативные документы отделяются от доказательств:

- `docs/stage-1-spec.md` — человекочитаемый нормативный контракт;
- `docs/contracts/` — машиночитаемые схемы и контракты этапа 0;
- `fixtures/golden/` — неизменяемые inputs, oracle outputs и manifests;
- `tools/fixtures/` — только генерация и проверка fixtures, не production-код;
- `docs/milestones/stage-0.md` — проверяемое решение о закрытии этапа.

## Lifecycle и завершение ingest

Нормальный путь этапа 1:

`REGISTERED → COLLECTING → PROCESSING → COMPLETE`.

- `REGISTERED → COLLECTING` происходит при успешном создании первой
  upload-сессии. Это устраняет неопределённость «начался ли ingest».
- Единственный сигнал окончания ingest — идемпотентный
  `POST /api/v1/runs/{run_id}/ingest-completions` с immutable manifest.
- Manifest перечисляет полный закрытый набор upload-артефактов, их роль,
  обязательность, заявленные size/SHA-256 и допустимость partial-данных.
- Сервер принимает manifest только после проверки всех ссылок и финализации
  каждого перечисленного upload. После принятия новые uploads и chunks
  запрещены, ingest token отзывается, состояние становится `PROCESSING`.
- `PROCESSING → COMPLETE` выполняется только после терминального успешного
  результата всех parse/normalize jobs manifest. В этапе 1 `COMPLETE` — потолок;
  `VERDICT` и `NO_VERDICT` добавляет этап 4.

Ошибочные состояния: `EXPIRED`, `FAILED_INGEST`, `FAILED_PARSE`.

- `EXPIRED` применим только к `REGISTERED` без upload-сессий после
  `planned_window.end + 24h`.
- `FAILED_INGEST` возникает после явного abort или неисправимой ошибки каталога.
  Операция `reopen-ingest` создаёт новый ingest token и возвращает прогон в
  `COLLECTING`, сохраняя журнал и старые upload-записи.
- `FAILED_PARSE` означает исчерпание retry хотя бы одной обязательной parse job.
  `retry-parse` возвращает прогон в `PROCESSING`.
- `force-finalize` сохраняет полученные contiguous chunks как immutable объект
  с фактическими size/SHA-256 и `integrity=PARTIAL`. Он не подменяет заявленный
  хэш. Manifest может сослаться на него только с `allow_partial=true`; итоговый
  `data_quality=INCOMPLETE` станет основанием для `NO_VERDICT` на этапе 4.

Каждый переход проходит через state machine и append-only lifecycle journal.

## Authentication, authorization и идемпотентность

Management API использует OIDC access token (`Authorization: Bearer`) с
проверкой issuer, audience `lt-verdict-api`, срока действия и scopes:

- `ltv.run.create` — регистрация;
- `ltv.run.read` — чтение списка и карточки;
- `ltv.run.manage` — phases, recovery и force-операции.

Этап 1 рассчитан на single-team deployment: межкомандной tenant-модели нет.
Локальные и contract-тесты используют фиксированный тестовый issuer/JWKS.

Ingest API использует отдельный opaque 256-bit token, выданный один раз в
ответе регистрации и scoped ровно на один `run_id`. Он не даёт read-доступа,
хранится только как SHA-256 hash, не попадает в журнал и отзывается после
принятия completion manifest или терминального состояния. Resumable upload
означает, что token многократно применим до отзыва; термин «одноразовый token»
в PRC заменяется на «однократно выдаваемый scoped token».

Правила идемпотентности:

- все создающие POST-операции принимают обязательный `Idempotency-Key`;
- уникальность — `(authenticated principal/token id, route template, key)`;
- canonical request hash совпал — возвращается исходный status/body;
- тот же ключ с другим запросом — `409 IDEMPOTENCY_KEY_REUSED`;
- запись ключа хранится не менее 24 часов, а для registration и ingest
  completion — до удаления соответствующего run;
- повторный PUT chunk с тем же index, length и SHA-256 возвращает `204`;
  другое содержимое под тем же index возвращает `409 CHUNK_CONFLICT`;
- finalize, completion, retry и recovery не создают дубликаты artifact/job/event.

Ошибки используют `application/problem+json` и стабильный machine-readable
`code`; status, предусловия и retryability перечисляются в stage-1-spec.

## Форматы и оракулы

### Gatling OSS

Поддерживаются и покрываются fixtures четыре версии:

| Версия | Формат | Official source commit |
| --- | --- | --- |
| 3.9.5 | text | `daf4a8108d17d2faaf583b716eee451b969fcd6c` |
| 3.12.0 | text | `a2e56a0f0e0eca0bcfa29219efc61bce37c62d73` |
| 3.13.5 | binary | `5ba7e63409f5697b91d757bdf56380a7ce2720fb` |
| 3.15.1 | binary | `4483f1fc56625bd0e77862ccbd2caf142c9dc9b8` |

Для text layout фиксируются реально сериализуемые записи:

- `RUN simulationClass simulationId start description gatlingVersion`;
- `USER scenario START|END timestamp`;
- `REQUEST groupHierarchy name start end OK|KO message`;
- `GROUP groupHierarchy start end cumulatedResponseTime OK|KO`;
- `ERROR message timestamp`;
- `ASSERTION base64BoopickleBlob` — валидируется как запись и пропускается.

Разделитель полей — TAB, строк — platform EOL writer'а; encoding берётся из
Gatling config fixture и фиксируется как UTF-8. Writer заменяет TAB/CR/LF в
message пробелом, а запятую в элементе group path — пробелом. `userId` в этом
формате отсутствует. 3.9.x directory bundle передаётся как ZIP без шифрования,
symlink и path traversal; разрешены только root-level `*.log`, чтение идёт в
лексикографическом порядке по Unicode code points.

Binary layout фиксирует big-endian primitives, headers `Run=0`, `Request=1`,
`User=2`, `Group=3`, `Error=4`, Run первой записью, version string, JDK compact
string coder, signed cache index, int millisecond deltas и length-prefixed
assertion blobs. Неизвестная или неравная версии file/declared kind — fail-closed.

Oracle каждого fixture — HTML report, сгенерированный той же версией Gatling из
того же `simulation.log`. Counts и errors совпадают точно. Для контролируемых
fixture latency не превышает 1000 ms; допускается абсолютное расхождение каждого
p50/p75/p90/p95/p99 не более 1 ms из-за целочисленного report/HDR rounding.

### Apache JMeter и jtl-comparator

Input — UTF-8 CSV из Apache JMeter 5.6.3, совместимый с RFC 4180: запятая,
line ending CRLF или LF, header обязателен, epoch milliseconds. Обязательные
колонки в точном регистре:
`timeStamp,elapsed,label,responseCode,responseMessage,threadName,dataType,success,
failureMessage,bytes,sentBytes,grpThreads,allThreads,URL,Latency,IdleTime,Connect`.
Дополнительные колонки разрешены и игнорируются; отсутствующая обязательная,
duplicate header, malformed quoting или неверный тип — fail-closed. `URL` пустой
означает Transaction Controller, непустой — sampler. Режимы `auto`, `tc`,
`samplers` повторяют pinned comparator.

JTL oracle:

- `system0shock/jtl-comparator` commit
  `83726485585577343b7b69f9961d9231a8fb4d49`;
- Python 3.14.3, pandas 3.0.1, numpy 2.4.2;
- Apache JMeter 5.6.3 как producer и независимый dashboard oracle.

Comparator используется для filtering/label aggregation и p50/p90/p95/p99;
JMeter dashboard подтверждает counts/error rate. Counts/errors совпадают точно,
перцентили — с допуском не более 1 ms на golden fixture.

## Golden fixtures и provenance

Каждый fixture содержит raw input, oracle output и `fixture.json`. Manifest
фиксирует schema version, producer/oracle versions и commits, команды генерации,
параметры сценария, timestamps, SHA-256 и expected metrics. Никакой expected
output не вычисляется самим будущим parser'ом LT Verdict.

Минимальный набор:

- Gatling 3.9.5 text directory bundle;
- Gatling 3.12.0 text file;
- Gatling 3.13.5 binary file;
- Gatling 3.15.1 binary file;
- JMeter 5.6.3 mixed TC/sampler CSV;
- JTL missing-required/duplicate-header/malformed-row negative fixtures;
- vanilla Elastic APM v1 success и incompatible-schema responses;
- reference SLA YAML + JSON Schema и valid/invalid examples;
- deterministic AsciiDoc protocol template render fixture.

Verifier проверяет JSON/YAML schemas, наличие файлов, SHA-256, pinned versions,
expected metrics и отсутствие незаполненных полей. Он работает офлайн после
checkout; регенерация является отдельной явной командой с network prerequisites.

## Benchmark methodology

Performance fixture содержит ровно 10 000 000 data rows и одну header row,
генерируется фиксированным seed и имеет SHA-256 в manifest.

Измерение выполняется в Linux amd64 container с JDK 21, cgroup v2 лимитами
`2.0 CPU`, `2048 MiB memory`, `2048 MiB memory+swap`; дополнительный swap
запрещён. Input расположен на локальном filesystem. В измеряемое окно входят
открытие JTL, parse, aggregation, HDR serialization и fsync результата. Не входят
pull/start container, генерация fixture и запуск PostgreSQL.

Последовательность: один excluded warm-up на 1 000 000 строк, затем три отдельных
process runs на полном fixture. Для каждого run сохраняются wall-clock seconds,
exit code, cgroup `memory.peak`, CPU model, OS/kernel, image/toolchain versions и
SHA-256 input/output.

Gate проходит только если все три измерения имеют exit code 0, wall time
`≤ 600.000 s`, `memory.peak < 2147483648 bytes` и одинаковый output SHA-256.
OOM, missing telemetry или изменение fixture hash — безусловный FAIL.

## Полный exit gate этапа 0

Помимо stage-1 prerequisites создаются обязательные PRC-артефакты:

- reference SLA contract и JSON Schema;
- AsciiDoc protocol template с `VERDICT` и `NO_VERDICT` вариантами;
- Elastic APM vanilla schema contract v1 и fixtures;
- решение: Git repository/MR — основной source of truth; internal append-only
  registry остаётся deployment-time fallback и не реализуется на этапе 1;
- решение: Influx intake отложен до deployment discovery;
- pinned fixture/oracle corpus и offline verifier;
- `docs/milestones/stage-0.md` с таблицей требований, evidence, командами,
  результатами, ограничениями и решением `GO` для этапа 1.

Отчёт может объявить этап закрытым только после успешной свежей проверки всех
manifest/schema/hash/oracle checks. Наличие документа без evidence не является
закрытием.

## План проверки изменения

Минимальный verification set документационного изменения:

1. markdownlint по всем изменённым Markdown-файлам;
2. JSON Schema validation valid/invalid examples;
3. YAML parse reference contracts;
4. fixture manifest validation и SHA-256 verification;
5. oracle expected-metrics verification;
6. поиск placeholder-маркеров, диапазонов порогов и непинненных версий;
7. link check локальных ссылок README/spec/plan/milestone;
8. secret scan и review полного diff;
9. подтверждение, что production source tree не создан и production dependency
   не добавлена.

## Документационное влияние

Изменение затрагивает PRC v0.5, основной development plan, stage-1-spec, README,
CHANGELOG, stage-0 decisions и milestone report. Пользовательски заметный итог —
статус проекта меняется с «контрактная подготовка» на «этап 0 закрыт, этап 1
готов к реализации» только после прохождения проверяемого gate.
