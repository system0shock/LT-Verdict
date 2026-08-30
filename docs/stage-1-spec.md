# Спецификация этапа 1 — Ingest-ядро LT Verdict

> **Superseded:** нормативный baseline и текущий план находятся в
> `lt-verdict-prc-prd-v0.6.md` и `docs/development-plan-v0.6.md`.

**Статус:** v1 (август 2026), базовая версия; уточняется по образцам данных этапа 0 до старта реализации.
**Основание:** PRC v0.5 §2.1–2.3, §2.6; план разработки Task 1.
**Критерий выхода (из PRC v0.5):** simulation.log (текстовый 3.9–3.12 и бинарный 3.13–3.15.1 форматы) — перцентили совпадают с HTML-отчётом Gatling на контрольных логах; JTL 10M+ строк — < 2 GiB RAM и ≤ 5–10 мин на 2 vCPU, перцентили сходятся с jtl-comparator.

---

## 1. Скоуп

**Входит:** каркас модульного монолита; модель прогона + Registration API + lifecycle (вкл. EXPIRED-TTL и FAILED_*-операции); ingest push API (chunked, resume, квоты, клиентский SHA-256); content-addressed каталог артефактов; потоковые парсеры simulation.log (текстовый формат 3.9–3.12, бинарный формат 3.13–3.15.1); потоковый JTL-парсер; HDR-нормализация (бакеты интервал×транзакция×фаза, V2-снапшоты); очередь парсинга (SKIP LOCKED + семафор); генератор синтетических данных; PostgreSQL + Flyway. Разработка ведётся вне боевой среды: эталонные фикстуры генерируются локальными прогонами Gatling OSS (3.9.5/3.12/3.15.x) и JMeter, боевые доступы не требуются.

**Не входит:** коллекторы VM/ELK (этапы 2–3), verdict engine (этап 4), GUI и live-view intake (этап 5), Confluence/протоколы (этап 5), поддержка Gatling Enterprise (строго OSS).

---

## 2. Каркас проекта

Gradle Kotlin DSL, Kotlin 2.x, Spring Boot 3.x, JDK 21. Модули Gradle (границы будущей сервисной нарезки):

| Модуль | Ответственность |
|---|---|
| `ltv-run` | Модель прогона, регистрация, lifecycle state machine, фазовая разметка (версионирование), comparability key |
| `ltv-ingest` | Chunked upload, ingest-токены, finalize, квоты |
| `ltv-catalog` | Content-addressed каталог (staging/objects), SHA-256, дедупликация, журнал доставки |
| `ltv-parse` | Очередь задач парсинга (SKIP LOCKED), семафор параллелизма, retry |
| `ltv-parser-gatling` | Потоковый парсер simulation.log: текст 3.9–3.12 + бинарный 3.13–3.15.1, версионный роутинг |
| `ltv-parser-jtl` | Потоковый парсер JTL |
| `ltv-normalize` | HDR-гистограммы, бакетизация, V2-снапшоты |
| `ltv-api` | REST-контроллеры, DTO, модель ошибок |
| `ltv-db` | Flyway-миграции, репозитории (Spring Data JDBC + NamedParameterJdbcTemplate) |
| `ltv-testkit` | Генератор синтетических прогонов, фикстуры |
| `ltv-app` | Spring Boot application, конфигурация, композиция модулей |

Зависимости: HdrHistogram, Jackson (+ Jackson CSV для JTL — выбор между Jackson CSV и univocity зафиксировать spike'ом в начале этапа по итогам теста на 10M строк), Testcontainers (PostgreSQL), JUnit 5 + AssertJ. CI: сборка, unit-тесты, golden-тесты, перф-тест JTL (отдельная job).

---

## 3. Модель прогона и регистрация

### 3.1 Lifecycle

Состояния: `REGISTERED → COLLECTING → COMPLETE → VERDICT | NO_VERDICT`, терминальные ошибки `FAILED_PARSE`, `FAILED_INGEST`, терминальный `EXPIRED`.

Переходы:

| Из | В | Триггер |
|---|---|---|
| REGISTERED | COLLECTING | первый finalize артефакта |
| REGISTERED | EXPIRED | TTL: плановое окно прошло + configurable margin (default 24h), артефактов нет |
| COLLECTING | COMPLETE | все заявленные артефакты загружены и распарсены, HDR-нормализация выполнена |
| COLLECTING | FAILED_PARSE | задача парсинга исчерпала retry (операция retry-parse возвращает в COLLECTING) |
| COLLECTING | FAILED_INGEST | неустранимая ошибка доставки (force-finalize переводит в COLLECTING с частичными данными либо в FAILED_INGEST с диагностикой) |
| COMPLETE | VERDICT / NO_VERDICT | (этап 4; в этапе 1 состояние COMPLETE — потолок) |

Правила: переходы только через state machine (никаких прямых UPDATE статуса); каждый переход — запись в журнал доставки/lifecycle; терминальные состояния неизменяемы кроме явных операций восстановления.

### 3.2 Registration API

`POST /api/v1/runs`:

```json
{
  "scenario_id": "orders-checkout",
  "stand": "perf-stand-2",
  "vcs_commit": "9f86d081...",
  "scenario_config_hash": "sha256:4c8a2e...",
  "dataset": "orders-1m-prod-like",
  "trigger": "jenkins",
  "run_class": "official",
  "planned_window": { "start": "2026-08-12T09:00:00Z", "end": "2026-08-12T10:30:00Z" },
  "planned_intensity": { "rps": 1200 },
  "phases": [
    { "name": "ramp_up",   "start": "2026-08-12T09:00:00Z", "end": "2026-08-12T09:15:00Z" },
    { "name": "steady",    "start": "2026-08-12T09:15:00Z", "end": "2026-08-12T10:15:00Z" },
    { "name": "ramp_down", "start": "2026-08-12T10:15:00Z", "end": "2026-08-12T10:30:00Z" }
] }
```

Ответ `201`: `{ "run_id": "...", "ingest_token": "ltv_..." }`.

Валидация: фазы не пересекаются и покрывают плановое окно без дыр; `steady` обязателен; `run_class ∈ {official, adhoc}`; `trigger ∈ {manual, jenkins, ci}`; `planned_intensity.rps > 0`. Ошибки — `400` с структурированной диагностикой.

**Comparability key** = нормализованный кортеж `(scenario_id, stand, dataset, planned_intensity)`; хранится как отдельное поле (для будущего baseline-поиска), вычисляется детерминированно (каноническая сериализация интенсивности).

**Ingest token:** случайный 256-бит, scope = run_id, одноразовый по смыслу (отзывается при COMPLETE/терминальном состоянии), хранится хэш (SHA-256), не plaintext.

### 3.3 Фазовая разметка

`PUT /api/v1/runs/{id}/phases` — замена разметки; каждая правка инкрементирует `phase_version` (monotonic int, старт 1). Все версии сохраняются (таблица `run_phases`, PK включает версию) — версия разметки войдёт в хэши входов журнала решений (этап 4). Правка допустима до COMPLETE.

### 3.4 Прочие операции

- `GET /api/v1/runs/{id}` — состояние, артефакты, фазы (текущая версия), диагностика.
- `GET /api/v1/runs` — список с фильтрами (состояние, сценарий, стенд, класс).
- `POST /api/v1/runs/{id}/ops/retry-parse` — перезапуск задачи парсинга (FAILED_PARSE → COLLECTING).
- `POST /api/v1/runs/{id}/artifacts/{upload_id}/ops/force-finalize` — фиксация частичной загрузки с диагностикой.
- TTL-воркер: периодический scan REGISTERED с истёкшим окном → EXPIRED + журнал.

---

## 4. Ingest API и каталог артефактов

### 4.1 Протокол загрузки

1. `POST /api/v1/runs/{id}/artifacts` (заголовок `Authorization: Bearer <ingest_token>`):

```json
{ "filename": "simulation.log", "kind": "gatling_simulation_log", "size": 3221225472, "sha256": "ab12..." }
```

`kind ∈ { gatling_simulation_log, gatling_log_dir_bundle, gatling_simulation_log_bin, jmeter_jtl }` (для 3.9.x каталог `.log` упаковывается в один бандл — формат бандла фиксируется в этапе 0 по результатам локальных прогонов Gatling OSS; 3.12 и бинарный 3.13+ — одиночные файлы). Ответ `201`: `{ "upload_id": "..." }`. Заявленные `size` и `sha256` сохраняются (T8: ожидаемый хэш декларирует клиент).

2. `PUT /api/v1/runs/{id}/artifacts/{upload_id}/chunks/{index}` — бинарный чанк (размер по умолчанию 8 MiB, configurable). Чанки пишутся в `<artifact-root>/staging/<upload_id>/<index>`. Идемпотентность: повторная загрузка чанка с тем же index и size перезаписывает.

3. `GET .../artifacts/{upload_id}` — прогресс (полученные индексы, байты) — основа resume.

4. `POST .../artifacts/{upload_id}/finalize`:
   - сборка staging-чанков в единый файл (streaming, без полного чтения в память);
   - проверка size == заявленному;
   - проверка SHA-256 == заявленному; несовпадение → `409` с фактическим хэшем, файл остаётся в staging до повторной загрузки/очистки;
   - атомарный перенос (rename в пределах одного тома) в `<artifact-root>/objects/sha256/<2 символа хэша>/<полный хэш>`;
   - объект с таким хэшем уже существует → дедупликация: связка run↔content-hash в БД, staging удаляется;
   - запись в журнал доставки (кто/token, когда, run_id, hash, size);
   - постановка задачи парсинга в очередь;
   - переход прогона REGISTERED → COLLECTING (если первый артефакт).

### 4.2 Квоты и отказы

Configurable: максимальный размер артефакта, суммарный бюджет на прогон, watermark свободного места тома. Превышение → `413` (артефакт/прогон) или `507` (том) с явной диагностикой; новые загрузки останавливаются, уже начатые finalize'ы доводятся. Недоступность каталога → `503` + health-check деградирует.

### 4.3 Контракт каталога

```
<artifact-root>/
  staging/<upload_id>/<chunk-index>      # до finalize
  objects/sha256/<xx>/<full-sha256>      # content-addressed, immutable в течение retention
```

Retention сырых артефактов — configurable (default ≥ 2 месяцев по опроснику); TTL-удаление объектов без ссылок на прогоны + запись в журнал доставки (реализация TTL-воркера допустима в конце этапа; layout и журнал — обязательны сразу).

---

## 5. Схема PostgreSQL (Flyway)

Таблицы:

- `runs` (id pk, scenario_id, stand, vcs_commit, scenario_config_hash, dataset, trigger, run_class, planned_start, planned_end, planned_intensity jsonb, comparability_key, state, phase_version, created_at, updated_at, terminal_reason)
- `run_phases` (run_id fk, version, name, start_ts, end_ts; PK (run_id, version, name))
- `ingest_tokens` (id, run_id fk, token_hash, created_at, revoked_at)
- `uploads` (id pk, run_id fk, filename, kind, declared_size, declared_sha256, state (open|finalizing|finalized|failed), received_bytes, created_at, finalized_at)
- `upload_chunks` (upload_id fk, chunk_index, size; PK (upload_id, chunk_index))
- `artifacts` (id pk, run_id fk, sha256, object_path, kind, size, created_at; UNIQUE (run_id, sha256))
- `parse_jobs` (id pk, artifact_id fk, run_id fk, state (queued|running|done|failed), attempts, last_error, locked_by, locked_at, created_at, finished_at)
- `hdr_snapshots` (id pk, run_id fk, phase_version, object_path, created_at) — производные артефакты нормализации
- `delivery_journal` (id bigserial pk, ts, actor, run_id, event, details jsonb) — append-only
- `lifecycle_journal` (id bigserial pk, ts, run_id, from_state, to_state, reason, details jsonb) — append-only

Роли БД: `ltv_app` (DML; **REVOKE UPDATE, DELETE на delivery_journal, lifecycle_journal** + триггер-страж, бросающий исключение на UPDATE/DELETE — защита от обхода на уровне роли), `ltv_migrator` (DDL, только Flyway). Таблицу `decision_journal` создаёт этап 4, но роль и паттерн закладываются сейчас.

Индексы: `parse_jobs (state) WHERE state IN ('queued','running')`, `runs (state)`, `runs (scenario_id, stand, planned_start)`, `artifacts (sha256)`.

Очередь: `SELECT ... FROM parse_jobs WHERE state='queued' ORDER BY created_at FOR UPDATE SKIP LOCKED LIMIT 1` в транзакции воркера.

---

## 6. Парсер simulation.log (Gatling OSS 3.9–3.15.1)

Общий контур: потоковая обработка без загрузки файла в память, RAM-профиль плоский. Детектор формата: текстовый (валидация структуры строки) vs бинарный (структура первой Run-записи). Иерархия групп (group → request) сохраняется в обоих форматах. Неизвестная версия/структура — fail-closed: парсинг останавливается, диагностика «формат не поддерживается: <детали>» (на уровне вердикта — NO_VERDICT).

### 6.1 Текстовый формат (3.9–3.12)

**Вход:** 3.12 — один файл; 3.9.x — бандл каталога с несколькими `.log` (file discovery — единственное различие; порядок обработки файлов бандла детерминирован: сортировка по имени).

**Формат записей** (tab-separated, абсолютные таймстемпы epoch millis):

```
RUN     <simulation class>  <start ts>  <end ts>  <description>
USER    <scenario>  <userId>  <start ts>  <end ts>
REQUEST <scenario>  <userId>  <name>  <start ts>  <end ts>  <status OK|KO>  <message>
GROUP   <scenario>  <userId>  <path>  <start ts>  <end ts>  <cumulativeResponseTime>  <status>
ERROR   <scenario>  <userId>  <message>
```

Точный порядок полей фиксируется golden-фикстурами и сверкой с исходниками `LogFileWriter`/`LogFileReader` тегов 3.9.5 и 3.12.0 в первой задаче реализации парсера; расхождение с фикстурой = баг спецификации, не парсера.

**Требования:**
- Потоковый (построчно), RAM-профиль плоский, без загрузки файла.
- Иерархия групп сохраняется: GROUP-записи несут path; связь group → request восстанавливается по userId + временным интервалам (уточнить по оракулу — HTML-отчёту).
- Экранированные табы/переносы в message/path обрабатываются по поведению Gatling (проверить по исходникам).
- Выход парсера — поток нормализованных событий (см. §8).

### 6.2 Бинарный формат (3.13–3.15.1)

Фактура из исследования исходников (`LogFileReader.scala`, теги 3.13–3.15.1 — reader идентичен, формат стабилен с момента введения):

- DataInputStream, big-endian; 5 типов записей с 1-байтным заголовком: Run/User/Request/Group/Error; **Run — всегда первая и содержит версию Gatling** → версионный роутинг: известная версия (3.13–3.15.1) — парсинг, неизвестная — fail-closed с диагностикой.
- Таймстемпы — int-дельты от старта прогона (из Run-записи) → абсолютное время восстанавливается детерминированно.
- Строки — length-prefixed + байт JDK compact-string coder (LATIN1/UTF16); кэш строк по индексу (дедупликация внутри файла) — парсер ведёт такой же кэш.
- Assertions — boopickle-блобы: пропускаются по length-prefix (платформе не нужны).

**Требования:** потоковый; выход — тот же поток нормализованных событий (§8), семантически идентичный выходу текстового парсера; golden-фикстуры для 3.13/3.15.x из локальных прогонов Gatling OSS.

### 6.3 Оракул (оба формата)

Для контрольных логов перцентили и счётчики, посчитанные платформой из HDR-бакетов, совпадают с HTML-отчётом Gatling (допуск: точность отчёта — целые ms). Эталонные логи и HTML-отчёты генерируются локальными прогонами Gatling OSS 3.9.5/3.12/3.15.x (Трек A плана разработки).

---

## 7. Парсер JTL

**Вход:** CSV JTL (формат колонок фиксируется по образцам этапа 0; минимум: timeStamp, elapsed, label, responseCode, success, allThreads, bytes, latency, connect).

**Требования:**
- Потоковый, чанками, без загрузки файла в память; бюджет: 10M+ строк < 2 GiB RAM и ≤ 5–10 мин на 2 vCPU (перф-тест в CI).
- Иерархия: Transaction Controllers и вложенные сэмплеры, связь parent→child (контракт поведения наследуется от jtl-comparator: нормализация лейблов, уровни auto/tc/samplers).
- Неизвестные/повреждённые строки — счётчик + сэмплирование первых N для диагностики; решение о fail-closed пороге — по контракту jtl-comparator.
- Выход — тот же поток нормализованных событий (§8), transaction name = нормализованный лейбл.

---

## 8. HDR-нормализация

- Бакеты: **интервал × транзакция × фаза**; интервал — configurable (default 10 s), границы выровнены по плановому окну.
- Фазовая разметка — из регистрации (текущая `phase_version`); событие относится к фазе по timestamp начала; **в вердиктные бакеты попадает только steady** (warmup/ramp-down пишутся в отдельные бакеты с флагом — для полноты данных и будущих сравнений, вердикт их не использует).
- Запись в HdrHistogram: точность 3 значащие цифры, диапазон — auto по максиму выборки с детерминированным округлением верхней границы (фиксировать в снапшоте).
- Слияние гистограмм — штатным merge HdrHistogram (без потери точности).
- Выход нормализации — HDR-снапшоты (V2 encoding, сжатые), сохраняются в каталог артефактов как производный артефакт (`hdr_snapshots`), детерминированы относительно входов: повторная нормализация того же артефакта с той же версией фаз даёт байт-в-байт идентичный снапшот (тест).
- По каждой транзакции сохраняются счётчики (count, error count по KO/responseCode) — основа throughput и error rate.

---

## 9. Очередь парсинга

- Воркер в ядре (не Job): цикл выборки SKIP LOCKED, обработка, отметка done/failed.
- Семафор параллелизма — configurable (default 2) — ограничение одновременных парсингов ради плоского RAM-профиля.
- Retry: configurable attempts (default 3) с экспоненциальной задержкой; исчерпание → `FAILED_PARSE` + `last_error` + доступность retry-parse.
- Прогресс парсинга (обработано строк/байт) — в `parse_jobs` для GUI/диагностики (обновление троттлить, не на каждую строку).

---

## 10. Генератор синтетических данных (`ltv-testkit`)

Генерирует детерминированно (seed): прогоны с фазами; simulation.log и JTL с известными распределениями латентностей (нормальное/логнормальное/смеси + инъекция ошибок), известными перцентилями (аналитический контроль); большие JTL (10M+ строк) для перф-теста. Использование: golden-тесты этапа 1, фикстуры этапов 2–5, demo-режим, нагрузочные тесты самой платформы. Эталонные (не синтетические) фикстуры — из локальных прогонов Gatling OSS 3.9.5/3.12/3.15.x и JMeter (Трек A плана разработки); синтетика дополняет их контролируемыми распределениями.

---

## 11. Конфигурация (application.yaml, ключи)

```
ltv:
  artifact-root: /var/lib/ltverdict/artifacts
  ingest:
    chunk-size: 8MiB
    max-artifact-size: 4GiB
    max-run-budget: 32GiB
    disk-watermark-free: 10GiB
  parsing:
    parallelism: 2
    retry-attempts: 3
    bucket-interval: 10s
  lifecycle:
    expired-margin: 24h
  retention:
    raw-artifacts: 60d
```

Секреты (БД) — через окружение/OpenShift Secrets/Vault, не в yaml.

---

## 12. Тесты и критерии выхода

| Группа | Содержание |
|---|---|
| Golden Gatling | фикстуры 3.9.5 (бандл), 3.12 (файл), 3.13/3.15.x (бинарные): перцентили/счётчики = HTML-отчёт Gatling; неизвестная версия/структура → fail-closed |
| Golden JTL | фикстуры vs jtl-comparator (перцентили, нормализация лейблов, иерархия) |
| Perf JTL | 10M+ строк: < 2 GiB RAM, ≤ 5–10 мин на 2 vCPU (CI job с лимитами) |
| Ingest | resume после обрыва, дедупликация, quota-отказы, SHA-256 mismatch → 409, атомарность finalize (краш-тест на rename) |
| Lifecycle | все переходы + EXPIRED-TTL + retry-parse + force-finalize; журнал только append |
| Нормализация | детерминированность снапшотов (повтор = байт-в-байт); steady-only срез |
| БД | Testcontainers PostgreSQL; REVOKE-проверка (UPDATE/DELETE на журналах под ролью ltv_app → ошибка) |

**Definition of done:** критерий выхода PRC v0.5 для этапа 1 выполнен; сквозной сценарий проходит: регистрация → chunked upload → finalize → парсинг → HDR-снапшоты в каталоге, прогон в COMPLETE; CI зелёный.
