# Синтетический набор данных «8 часов» для показа всех экранов UI: план реализации

> **Для исполнителей:** обязательный навык: `superpowers:subagent-driven-development` (рекомендуется) или `superpowers:executing-plans`. Шаги отмечены чекбоксами `- [ ]`.

**Цель:** детерминированный генератор входов и скрипт сборки каталога данных, после которых `ltv ui --data-dir <dir>` открывает каждую вкладку заполненной по сценарию макета «Checkout, релиз 2026.09.3».

**Архитектура:** два скрипта на стандартной библиотеке Python в `tools/`: `demo_dataset.py` порождает входы (JTL, политики, снимки по плечам, планы, pod-view, контекст OpenSearch), `demo_dataset_build.py` запускает `ltv analyze` по матрице анализов и через локальный HTTP API сохраняет релизы и baseline. Параметры сценария лежат в небольшом `examples/demo-8h/scenario.json`. Сгенерированные данные в репозиторий не попадают. Ядро и UI не меняются.

**Стек:** Python 3 (stdlib, `unittest`), `ltv` из `./gradlew installDist`, `urllib`, `subprocess`.

**Спецификация:** макет `docs/ui-mockup/lt-verdict-ui-mockup.html` (сценарий в начале `<script>`), опись `docs/superpowers/plans/2026-11-19-ui-mockup-gap.md`, ADR 0014, 0019, 0020, 0011, 0030.

Дата плана: 2026-11-19. Язык документов проекта русский.

## Решения владельца 2026-11-19

1. Профиль по умолчанию `demo` (RPS в 5 раз ниже, около 1,3 ГБ); `full` остаётся как опция.
2. История из шести релизов достаточна; необязательная Task 10 (ещё три релиза) не выполняется.
3. Экран ИИ-разбора остаётся пустым, записанный совет не создаётся.
4. Запрет совмещения `--stages` и `--resources` будет снят отдельным ADR. Пока остаётся подход плана (отдельный анализ S только со стадиями), но он изолирован в одной именованной функции и одном флаге скрипта сборки, чтобы позже перейти на один совмещённый анализ; это отмечено в README.

## Блок AGENTS.md

```text
REQUESTED: синтетический набор данных на 8 часов по сценарию макета, чтобы приёмка UI шла по экранам.
REQUIRED TO ACHIEVE IT: генератор входов; скрипт сборки каталога данных (анализы, релизы, baseline);
  небольшой файл сценария; тест генератора в малом режиме; README примера; фрагмент журнала изменений.
NOT REQUIRED: изменения ядра, схем, UI; новые команды CLI (release/baseline); новые зависимости;
  анонимизация реальных прогонов; коммит сгенерированных данных; ADR (контракты и зависимости не меняются).
EXPECTED FILES TO CHANGE:
  create tools/demo_dataset.py, tools/demo_dataset_build.py, tools/test_demo_dataset.py,
         examples/demo-8h/scenario.json, examples/demo-8h/README.md, changelog.d/demo-8h-dataset.added.md;
  modify examples/README.md (одна строка в таблицу).
```

Documentation impact: `examples/demo-8h/README.md`, строка в `examples/README.md`, фрагмент журнала. ADR не нужен: публичные контракты, схемы и production-зависимости не меняются.

## Global Constraints

- Только стандартная библиотека Python; тесты на `unittest` (CI: `python -m unittest discover -s tools -p "test_*.py"`). Пакета `jsonschema` в CI нет: тест проверяет пределы и структуру вручную.
- Детерминизм: фиксированные seed в `scenario.json`, без времени и `random` без seed, без итерации по `set`, JSON с `sort_keys`, числа форматируются явно, запись байтами с `\n`. Два запуска дают побайтово одинаковые файлы.
- Пределы снимка (ADR 0014, принят): не более 1 024 рядов, 1 500 000 ячеек, 32 MiB, шаг целые секунды 1-60, не более 100 000 точек на ряд.
- Пределы pod-view (`tools/pod_view_adapter.py`): не более 240 колонок, 256 подов, 2 560 строк, файл до 12 MiB, шаг пода кратен шагу снимка.
- Один ряд на пару (сигнал, сервис); агрегация и единицы ряда совпадают с `platform_rules`; `rules` в снимке пусты или `diagnostic` (порогами SLA владеет политика, ADR 0018).
- Снимок и pod-view привязаны к JTL (`load_input_sha256` = SHA-256 файла JTL) и друг к другу (`resource_snapshot_sha256` = семантический хэш, `tools/stats_validation.snapshot_hash`).
- Один JTL на оба плеча; для каждого запуска два анализа (метка `arm` = `A` и `B` во всех рядах снимка).
- Единый формат JTL, одна политика и один набор флагов для всех релизов серии, иначе история покажет «анализ создан по другим правилам» и baseline будет несовместим.
- Один писатель на каталог данных (`DATA_DIR_BUSY`): все `ltv analyze` завершаются до старта `ltv ui`.
- Коды выхода `ltv analyze`: 0 PASS, 2 FAIL, 3 NO_VERDICT; скрипт сборки принимает ожидаемый код каждого анализа и падает на любом другом.
- Никаких изменений в `src/`, `ui/`, `docs/contracts/`.

## Review Focus

Вход или состояние, которые сценарий подразумевает, но задачи иначе не проверили бы; тест каждого пункта вписан в владеющую задачу.

1. Ряд не соответствует правилу: у `restarts` агрегация `interval_mean` вместо `interval_rate`. Ожидание: тест генератора сверяет таблицу сигналов с `docs/user/platform-metric-packs.md` (Task 3).
2. Шаг снимка 15 с и 8 часов: 1 920 ячеек на ряд; при 800 рядах предел ячеек превышен (потолок 781). Ожидание: генератор отказывает до записи с понятной ошибкой (Task 3).
3. Окно `soak` без единой ячейки в малом режиме. Ожидание: границы стадий считаются долями длительности, малый режим сохраняет не менее 30 ячеек в `soak` (нижняя граница `min_cells` плана трендов, ADR 0011) (Task 2).
4. Релиз без транзакции `POST /checkout` (2026.09.0): правило даёт `NO_VERDICT`, а не падение генератора или пустой JTL (Task 2).
5. Повторный запуск сборки на непустом каталоге данных: анализы идемпотентны (тот же `analysis_id`), а повторное сохранение релиза даёт «уже входит в релиз». Ожидание: сборка отказывает на непустом каталоге без `--force-new` (Task 7).

## Что воспроизводится и что нет

### Сценарий макета (извлечено из макета)

- Тест 29.09.2026 10:00-18:00 (8 ч), шаг ряда 15 с (в макете 1 921 точка), «Checkout, релиз 2026.09.3».
- Стадии (мин от начала): Разгон 0-30, Плато 1 30-150, Ступень +50% 150-180, Плато 2 180-270, Спад 270-300, Soak 300-480. Множитель нагрузки: 0,25 до 1 на разгоне, 1 на плато 1, до 1,5 на ступени, 1,5 на плато 2, обратно к 1 на спаде, 1 на Soak. Пик 180 зпр/с, плато 1 около 121 зпр/с.
- Восемь транзакций и доли: `POST /checkout` 0,22; `POST /payment/confirm` 0,10; `GET /cart` 0,16; `POST /login` 0,09; `GET /catalog` 0,20; `GET /product/{id}` 0,15; `GET /orders` 0,05; `GET /health` 0,03.
- Колено деградации на 224-й минуте (13:44): дальше p95 `POST /checkout` растёт до порога 2000 мс (в Soak), доля ошибок checkout до порога 1 %, RPS падает на 17 %; ошибки OpenSearch растут после колена.
- Плечи A и B (OCP-A, OCP-B), 20 сервисов и БД (21 рабочая нагрузка), 156 подов (78 на плечо: 76 у сервисов и 2 у БД).
- Сюжет плеча B: `payments-svc`, под 2 упирается в память и перезапускается 3 раза (пила памяти и пауз GC); `checkout-api`, два пода в троттлинге; `catalog-svc`, один горячий под на обоих плечах.
- Тренды в Soak: Old Gen, память сайдкара, пул соединений БД, потоки JVM, CPU.
- Baseline 2026.09.2 (PASS), история из 9 релизов, у части другой профиль нагрузки.

### Матрица экранов макета

Анализы: `A` и `B` ресурсные по плечам; `S` стадии без ресурсов (ADR 0030, R3: стадии и снимок взаимоисключающи, `STAGES_RESOURCES_CONFLICT`).

| Блок макета | Экран | Входы | Какой анализ открыть |
| --- | --- | --- | --- |
| Шапка: релиз, окно, плечи, вердикт | все | JTL, политика, метка `arm` | B (вердикт FAIL, 3 нарушения) |
| Карточка вердикта, пять чисел | Обзор | JTL + политика | B и A |
| Лента стадий, окна метрик | Обзор | `load-stages.v1` | S |
| «Где проблема» сервис × плечо | Обзор | два снимка, platform_rules | A и B |
| Наложение рядов, статистики окна | Обзор, Глубокий анализ | снимок, окна | B |
| Платформа: 8 плиток | Обзор | снимок (сигналы пакета openshift) | B |
| Тренды в Soak | Обзор, Таблицы | `trend-plan.v1` | B |
| Ошибки OpenSearch | Глубокий анализ | `opensearch-errors.v1` через `--source-context` | B |
| Поды: тепловая карта, худший под | Глубокий анализ | `pod-view.v1` на плечо | B (под 2 `payments-svc`) |
| Таблицы: правила, транзакции, сервисы | Таблицы | политика, снимок | B |
| Корреляции | Глубокий анализ | `correlation-plan.v1` | B |
| Сравнение, прошлый релиз пунктиром | Сравнение | baseline 2026.09.2 (слоты A, B, без плеча) | A, B, S |
| История, динамика | История | 6 релизов с метками, профилем | любой |
| Новый анализ (загрузка файла) | Новый анализ | любой JTL из набора | офлайн-ветка |
| Правила | Правила | `policy.json` | — |
| Ёмкость по ступеням | Обзор | `capacity-plan.v1`, снимок 10 с | C (необязательный, Task 9) |

### Чего воспроизвести нельзя (разрывы, не выдумывать функции)

1. **Лента стадий и платформа на одном экране.** `--stages` несовместим с `--resources`, `--capacity`, `--source`. Лента видна на анализе S, платформа на A и B. Совмещение требует изменения продукта (ADR 0030, R3).
2. **Карточки «Требует внимания» с evidence.** Инциденты v0 есть в ядре, но не в `analysis-result` и UI (опись, раздел 4). Набор данных их не создаёт.
3. **ИИ-разбор.** Нужен рантайм модели; офлайн ничего не показывается. Экран 9 остаётся пустым состоянием (вопрос владельцу 4).
4. **График колена ёмкости.** `capacity_knee` не откалиброван (опись, раздел 4). Блок ёмкости показывает ступени, а не колено.
5. **Данные по подам с шагом 15 с.** pod-view допускает 240 колонок: 8 ч дают шаг 120 с. Пила рестартов (период около 84 мин) видна, но тоньше, чем в макете.
6. **Онлайн-источники.** Профили Prometheus/OpenSearch/PostgreSQL на экране «Новый анализ» (автоокно, поля `margin`, `step`) офлайн недоступны: показывается ветка загрузки файла.
7. **Вердикт «3 из 9 нарушены» на обоих плечах.** Нагрузка одна, поэтому бизнес-правила нарушены и на плече A. Ожидаемо: A нарушает 2 правила, B нарушает 3. Макет считает только B.
8. **Число 9 правил.** Платформенное правило разворачивается по сервисам (20 проверок на правило). Счёт «3 из 9» верен для шаблонов правил политики; как UI считает развёрнутые проверки, проверяется в Task 8 (вопрос владельцу 3).
9. **Статистический baseline.** Нужно не менее трёх релизов PASS с одинаковым заявленным профилем из разных прогонов: PASS у 2026.09.2, 2026.09.1, 2026.08.2.
10. **Время в UI.** JTL хранит UTC; макет показывает 10:00-18:00. Начало запусков задано в UTC (10:00Z).

## Состав набора данных

Серия релизов `Checkout`; профиль нагрузки (шесть полей релиза) одинаков у всех релизов, кроме необязательного 2026.08.4.

| Релиз | Дата начала (UTC) | Вердикт A / B | Особенность JTL |
| --- | --- | --- | --- |
| 2026.09.3 (текущий) | 2026-09-29 10:00 | FAIL (2 правила) / FAIL (3) | сюжет макета |
| 2026.09.2 (baseline) | 2026-09-22 10:00 | PASS / PASS | p95 checkout около 820 мс |
| 2026.09.1 | 2026-09-15 10:00 | PASS / PASS | p95 около 860 мс |
| 2026.09.0 | 2026-09-08 10:00 | NO_VERDICT / NO_VERDICT | в JTL нет `POST /checkout` |
| 2026.08.3 | 2026-08-25 10:00 | FAIL (1) / FAIL (1) | p95 checkout около 2110 мс, ошибки 0,3 % |
| 2026.08.2 | 2026-08-18 10:00 | PASS / PASS | третий PASS для статистического baseline |

Необязательно (Task 10): остальные строки истории макета (2026.08.4 на 4 часа с другим профилем, 2026.08.1, 2026.07.4).

Анализов 14: по два (A, B) на каждый из шести запусков и один S у 2026.09.3 и 2026.09.2.

### Файлы запуска (каталог `<out>/<релиз>/`)

| Файл | Содержимое | Размер (профиль demo) |
| --- | --- | --- |
| `load.jtl` | JMeter CSV, 17 колонок (формат realistic-csv из `tools/perf/generate_jtl.py`), 8 транзакций, коды 200/201/204, ошибки 500/503 с `failureMessage` | около 105 МБ |
| `arm-A/resource-snapshot.json`, `arm-B/...` | снимок плеча, шаг 15 с, 1 920 ячеек, около 250 рядов | 3-4 МБ |
| `arm-X/trend-plan.json` | проверки трендов в окне `soak` | менее 10 КБ |
| `arm-X/pod-view.json` | 78 подов, шаг 120 с, 240 колонок | 3-5 МБ |
| `arm-X/correlation-plan.json` | 2-3 пары на плечо | менее 10 КБ |
| `opensearch-errors.json` | `opensearch-errors.v1`, 1 920 бинов, группы по сервису и типу ошибки | менее 1 МБ |
| `load-stages.json` | `load-stages.v1`, 6 стадий (Разгон и Спад `excluded`, остальные `steady`) | менее 2 КБ |

Общие файлы: `policy.json` (9 правил, анализы A и B), `policy-stages.json` (5 бизнес-правил, анализ S), `capacity-plan.json` и `arm-A-cap/*` (только Task 9).

### Политики

`policy.json`, 9 правил (ожидаемый итог на B: нарушены 3, на A: нарушены 2):

| # | Правило | Окно | Ожидание |
| --- | --- | --- | --- |
| 1 | p95 `POST /checkout` не более 2000 мс | `soak` | нарушено (A и B) |
| 2 | доля ошибок `POST /checkout` не более 0,01 | `soak` | нарушено (A и B) |
| 3 | p95 `POST /payment/confirm` не более 3000 мс | `soak` | выполнено |
| 4 | доля ошибок overall не более 0,02 | `soak` | выполнено |
| 5 | throughput overall не менее 90 зпр/с | `soak` | выполнено (порог уточнить по факту, Task 4) |
| 6 | `restarts` (`openshift_restarts`, `interval_rate`, `sla`, все сервисы) | `soak` | нарушено только B, `payments-svc` |
| 7 | `memory-limit` (`openshift_container_memory_limit_ratio`, `interval_max`, `diagnostic`) | `soak` | признак для объяснения, не SLA |
| 8 | `cpu-throttling` (`diagnostic`) | `soak` | признак (два пода `checkout-api` на B) |
| 9 | покрытие `openshift_unavailable_replicas` (`sla`, `interval_max`, порог 0) | все окна | выполнено (в ряду нули) |

`platform_services`: 21 имя сервиса и БД. `defaults.sample_floor` и `min_samples` ниже, чем в `examples/basic`, чтобы малые транзакции (`GET /health`) в Soak имели достаточную выборку при `--rps-scale 0,2`.

`policy-stages.json`: правила 1-5 без платформенных секций. Причина: политика с `platform_rules` уровня `sla` при отсутствии снимка даёт причину `RESOURCE_SNAPSHOT_REQUIRED` (`Policy.kt:119-125`) и блокирует вердикт; поведение в режиме стадий не проверено, поэтому анализ S получает свою политику. Его ключ сопоставимости отличается от A и B: для S baseline задаётся отдельным слотом.

### Снимок плеча: размер и шаг

| Вариант | Шаг | Ячеек на ряд | Рядов влезает (1,5 млн ячеек) | Годится для |
| --- | --- | --- | --- | --- |
| Выбранный по умолчанию | 15 с | 1 920 | не более 781 | A, B (как в макете) |
| Для ёмкости (Task 9) | 10 с | 2 880 | не более 520 | отдельный анализ C с малым числом рядов (ADR 0009: шаг 1, 2, 5 или 10 с) |

Состав одного снимка: 21 нагрузка × 6 сигналов политики (`cpu_limit_ratio`, `memory_limit_ratio` с `interval_max`, `oom` нулевой, `restarts`, `cpu_throttling`, `unavailable_replicas` с `interval_max`) + 3 признака атрибуции (`pod_imbalance`, `sidecar_memory_limit_ratio`, `sidecar_cpu_throttling`) + 3 ряда JVM для сервисов (Old Gen, пауза GC, потоки) + пулы соединений БД у зависящих сервисов (около 8) = около 250 рядов, 480 тыс. ячеек, 3-4 МБ (в 3 раза меньше предела ячеек, на порядок меньше предела байтов). Поды в снимок не кладутся: 78 подов × 7 метрик добавили бы 546 ряда и вышли бы за 781 ряд. Поды идут в pod-view.

### JTL: объём и время

- Нагрузка макета: около 126 зпр/с в среднем за 8 часов (121 на плато 1, 181 на плато 2, минус 17 % в хвосте) ≈ 3,6 млн строк. Оценка по формулам макета, точное число даст Task 2.
- 17 колонок × около 146 Б/строку (замер 10 млн строк в `2026-10-08-w3-6a-realistic-inputs.md`) ≈ 530 МБ на запуск. Каждый запуск копируется в каталог данных (`inputs/source.bin`): шесть полных запусков дают около 3,2 ГБ, вместе с копиями около 6,5 ГБ.
- Разбор по замеру 10 млн строк (70-97 с) ≈ 25-35 с на анализ: 14 анализов ≈ 8-10 мин. Генерация на Python: оценка 15-30 с на запуск.
- Поэтому профили: `full` (`--rps-scale 1`, истинная нагрузка), `demo` по умолчанию (`--rps-scale 0.2`, около 0,72 млн строк, около 105 МБ на запуск, около 1,3 ГБ вместе с копией в каталоге данных, 14 анализов ≈ 2-3 мин), `tiny` для тестов (24 мин, `--rps-scale 0.02`, 4 нагрузки). Что остаётся верным в `demo`: ось 10:00-18:00, границы и множители стадий, колено, наклоны трендов, процент ошибок, форма p95, число сервисов, подов и рядов. Что меняется: абсолютные RPS в пять раз ниже (выбор: меньше RPS, а не короче длительность, чтобы окна и тренды Soak не менялись). Минимальная выборка на ячейку в 15 с остаётся: `POST /checkout` около 80 сэмплов.
- Формат колонок один для всех релизов. Если спайк Task 2 покажет, что парсер принимает 9-10 колонок без изменения числа, формат можно сузить для всех релизов сразу (около 65 МБ на запуск).

## Структура файлов

- `tools/demo_dataset.py`: генератор. Функции и сигнатуры в задачах ниже.
- `tools/demo_dataset_build.py`: сборка каталога данных (запуск `ltv`, HTTP API).
- `tools/test_demo_dataset.py`: тесты малого режима.
- `examples/demo-8h/scenario.json`: стадии, транзакции и доли, сервисы с числом подов, релизы (дата, вердикт, особенность, seed), метаданные плеч, константы сюжета.
- `examples/demo-8h/README.md`: команды.

Вывод генератора (`--out`) и каталог данных лежат вне репозитория (например, `%TEMP%\ltv-demo-8h`).

---

### Task 1: Каркас, `scenario.json` и разбор профилей

**Files:**

- Create: `examples/demo-8h/scenario.json`, `tools/demo_dataset.py`, `tools/test_demo_dataset.py`

**Interfaces:**

- Produces: `load_scenario(path: Path) -> dict`; `resolve_profile(name: str) -> Profile` где `Profile = {"name", "duration_s", "rps_scale", "workloads", "snapshot_step_ms", "pod_step_ms"}` для `full`, `demo`, `tiny`; `stage_bounds(profile) -> list[Stage]` с `Stage = (id, role, from_ms, to_ms)` в долях длительности 30/480, 120/480, 30/480, 90/480, 30/480, 180/480.

- [ ] **Step 1: Тест `test_stage_bounds_full` и `test_stage_bounds_tiny`.** Для `full` границы в минутах 0, 30, 150, 180, 270, 300, 480; для `tiny` (24 мин) 1,5/6/1,5/4,5/1,5/9 мин, все границы кратны 15 000 мс, `soak` содержит не менее 36 ячеек.
- [ ] **Step 2:** `python -m unittest tools.test_demo_dataset -v`. Ожидание: FAIL (модуля нет).
- [ ] **Step 3:** написать `scenario.json` (значения из раздела «Сценарий макета») и реализовать `load_scenario`, `resolve_profile`, `stage_bounds`. Роли: `ramp` и `recovery` = `excluded`, остальные `steady` (id совпадают с id окон снимка: `ramp`, `plateau-1`, `step`, `plateau-2`, `recovery`, `soak`).
- [ ] **Step 4:** тест проходит.
- [ ] **Step 5: Commit** `feat(tools): demo dataset scenario skeleton`.

### Task 2: Генератор JTL

**Files:** Modify `tools/demo_dataset.py`, `tools/test_demo_dataset.py`

**Interfaces:**

- Consumes: `Profile`, `Stage`, `load_scenario`.
- Produces: `write_jtl(path: Path, release: dict, profile: Profile, scenario: dict) -> str` (возвращает SHA-256 файла); `load_multiplier(profile, t_ms) -> float` (кусочно-линейная функция стадий макета); `p95_model(tx: dict, t_ms, kind: str) -> float` (форма p95 макета: базовая 760 мс, рост после колена на 1900 мс × `kf` × `ramp^1,25`, множитель транзакции `f`).

- [ ] **Step 1: Тесты.** (а) `test_jtl_deterministic`: два запуска в `tiny` дают равные байты и SHA-256; (б) `test_jtl_header_and_first_timestamp`: заголовок 17 колонок, как `REALISTIC_CSV_HEADER` в `tools/perf/generate_jtl.py`, первая метка времени равна `start_epoch_ms` релиза; (в) `test_jtl_shares`: доли транзакций в `tiny` отличаются от `scenario.json` не более чем на 2 п.п.; (г) `test_jtl_checkout_p95_after_knee`: p95 `POST /checkout` по последним 20 ячейкам `soak` выше 2000 мс у релиза 2026.09.3 и ниже 1000 мс у 2026.09.2; (д) `test_release_without_checkout` (Review Focus 4): у 2026.09.0 в файле нет метки `POST /checkout`, остальные 7 на месте; (е) `test_error_rows_have_failure_message`.
- [ ] **Step 2:** запуск, FAIL.
- [ ] **Step 3:** реализация. Алгоритм (не определён сигнатурой): внутри каждой секунды число запросов транзакции равно разности округлённых накопленных интенсивностей (без RNG); `elapsed` берётся обратной функцией распределения по точкам (0,5 → 0,45·p95; 0,95 → p95; 0,99 → 1,35·p95), аргумент из splitmix64 от `(seed релиза, номер строки)`; ошибка по порогу доли ошибок транзакции (форма макета: 0,1-0,2 % плюс `2,6·k` у checkout, `0,5·k` у payment/confirm); коды `200/201/204`, ошибки `500` и `503` с `failureMessage` как в `generate_jtl.py`; запись потоком, без накопления строк.
- [ ] **Step 4:** тесты проходят.
- [ ] **Step 5: Спайк (результат записать в README задачи 11).** Сгенерировать JTL профиля `demo` и `full` во временный каталог, записать число строк, размер и время генерации; прогнать `ltv analyze` без политики, записать время и пик памяти. Проверить: принимает ли парсер 9-10 колонок с тем же результатом. Результат уточняет таблицу «JTL: объём и время».
- [ ] **Step 6: Commit** `feat(tools): demo dataset JTL generator`.

### Task 3: Снимки ресурсов по плечам

**Files:** Modify `tools/demo_dataset.py`, `tools/test_demo_dataset.py`

**Interfaces:**

- Produces: `build_snapshot(arm: str, release: dict, profile: Profile, scenario: dict, load_sha256: str) -> dict` (объект `resource-snapshot.v1`, ряды отсортированы по `id`, 15 с, метка `arm` в каждом ряду); `snapshot_hash(snapshot) -> str` (обёртка над `tools.stats_validation.snapshot_hash`, как в `docs/ui-mockup/core-demo/tools/gen.py`); `SIGNALS: dict` (таблица сигнал → `unit`, `aggregation`, свёртка).

- [ ] **Step 1: Тесты.** (а) `test_signals_match_pack_doc` (Review Focus 1): таблица `SIGNALS` совпадает с таблицей «Сигналы» в `docs/user/platform-metric-packs.md` по метрике, единице и агрегации (`restarts` = `interval_rate`, `memory_limit_ratio` в политике = `interval_max`); (б) `test_snapshot_limits` (Review Focus 2): `demo` и `full` имеют не более 1 024 рядов, не более 1 500 000 ячеек, не более 32 MiB, один ряд на пару (сигнал, `entity`); при искусственно заданных 800 рядах на 15 с `build_snapshot` бросает `ValueError` до записи; (в) `test_arm_label_uniform`: `arm` один и тот же в каждом ряду; (г) `test_story_arm_b`: ряд `restarts` для `payments-svc` на B неубывающий и заканчивается 3, на A нулевой; `memory_limit_ratio` `payments-svc` на B достигает не менее 0,99 (максимум по подам) и на A не более 0,75; (д) `test_unavailable_replicas_zero`; (е) `test_windows_match_stages`: окна снимка совпадают со стадиями `steady` из Task 1 (id и границы); (ж) `test_snapshot_rules_empty`.
- [ ] **Step 2:** FAIL.
- [ ] **Step 3:** реализовать по формулам макета (`podVal`, `wlAgg`, `raw`), приведённым к сервисному уровню (`interval_max` по подам для памяти, среднее для остальных, детерминированный шум splitmix64 в промилле, как в `tools/platform_synthetic.py`). Ряды Soak с трендом: Old Gen `checkout-api` и `orders-svc`, память сайдкара `gateway`, пул БД, потоки JVM, CPU: наклон из `scenario.json` (поле `trend_slopes`).
- [ ] **Step 4:** тесты проходят.
- [ ] **Step 5: Commit** `feat(tools): demo dataset resource snapshots per arm`.

### Task 4: Политики, стадии, планы трендов и корреляций

**Files:** Modify `tools/demo_dataset.py`, `tools/test_demo_dataset.py`

**Interfaces:**

- Consumes: `build_snapshot`, `snapshot_hash`, `stage_bounds`.
- Produces: `build_policy(scenario) -> dict` (9 правил), `build_policy_stages(scenario) -> dict` (5 правил), `build_load_stages(profile) -> dict` (`load-stages.v1`), `build_trend_plan(snapshot_sha256: str, scenario) -> dict` (`trend-plan.v1`, окно `soak`, 5 проверок: Old Gen, сайдкар, пул, потоки, CPU), `build_correlation_plan(snapshot_sha256: str, arm: str, scenario) -> dict` (`correlation-plan.v1`, 2-3 пары, элемент `controls` `achieved_rps`).

- [ ] **Step 1: Тесты.** (а) `test_policy_rule_count`: 9 правил в сумме `rules` и `platform_rules`; 5 бизнес-правил имеют `window_ids` `["soak"]`; (б) `test_platform_coverage_present`: есть `platform_coverage` и правило покрытия `openshift_unavailable_replicas`, `sla`, без `window_ids`; (в) `test_trend_plan_binds_hash`: `resource_snapshot_sha256` плана равен `snapshot_hash` снимка того же плеча, у `min_cells` не менее 30, `window_id` существует в снимке; (г) `test_trend_magnitude_gate_below_planted_slope`: порог `min_slope_units_per_second` равен половине посаженного наклона ряда; (д) `test_stages_valid`: 6 стадий, не более 16, не пересекаются, есть `steady`; (е) `test_policy_validates_offline`: если задана `LTV_BIN`, `ltv policy validate` возвращает 0 для обеих политик, иначе тест пропускается.
- [ ] **Step 2:** FAIL.
- [ ] **Step 3:** реализация. Эталонные формы: `examples/basic/*.json` и `docs/contracts/{policy,trend,diagnostics,stages}/v1/examples/valid/`.
- [ ] **Step 4:** тесты проходят. Порог правила 5 (throughput) уточняется по факту первого прогона Task 8; изменение вносится в `scenario.json`.
- [ ] **Step 5: Commit** `feat(tools): demo dataset policies and plans`.

### Task 5: pod-view и контекст OpenSearch

**Files:** Modify `tools/demo_dataset.py`, `tools/test_demo_dataset.py`

**Interfaces:**

- Produces: `build_pod_view(arm: str, snapshot: dict, snapshot_sha256: str, load_sha256: str, scenario, profile) -> dict` (`pod-view.v1`, шаг 120 с, 240 колонок, подам даны имена как в макете `<сервис>-<rs>-<a|b><n>`; число подов 78 в `full` и `demo`); `build_opensearch_errors(load_sha256: str, scenario, profile) -> dict` (`opensearch-errors.v1`).

- [ ] **Step 1: Тесты.** (а) `test_pod_view_limits`: не более 240 колонок, 256 подов, 2 560 строк, 12 MiB, `step_ms` кратен шагу снимка; (б) `test_pod_totals`: 78 подов на плечо в `full` и `demo`; (в) `test_pod_view_story`: на B в `payments-svc` ровно один под (второй) имеет `restarts` 3 и пилу памяти, другой под `catalog-svc` горячий на обоих плечах, два пода `checkout-api` троттлят на B; (г) `test_pod_view_binds`: `resource_snapshot_sha256` и `load_input_sha256` совпадают; (д) `test_opensearch_shape`: `timeline` не более 100 000 бинов, `total_errors` равен сумме бинов, после колена доля ошибок выше 80 %, группы по сервису и типу ошибки, `coverage.status` = `COMPLETE`.
- [ ] **Step 2:** FAIL.
- [ ] **Step 3:** реализация. Перед работой прочитать `tools/pod_view_adapter.py` (`assemble`, `dumps`, `format_value`) и `tools/platform_synthetic.py`: если сигнатуры подходят, вызвать их (как делает синтетический набор P2d), иначе записать JSON по схеме `docs/contracts/pod-view/v1/pod-view.schema.json` напрямую (дублирование нескольких строк предпочтительнее новой абстракции). Контекст OpenSearch берёт форму из `docs/contracts/sources/v1/opensearch-errors.example.json`.
- [ ] **Step 4:** тесты проходят.
- [ ] **Step 5: Commit** `feat(tools): demo dataset pod view and OpenSearch context`.

### Task 6: Команда `generate` и детерминизм набора

**Files:** Modify `tools/demo_dataset.py`, `tools/test_demo_dataset.py`

**Interfaces:**

- Produces: CLI `python tools/demo_dataset.py generate --out DIR [--profile tiny|demo|full] [--scenario PATH] [--only RELEASE]`; `generate(out: Path, profile: Profile, scenario: dict, only: str | None) -> dict` (манифест: путь, размер, SHA-256 каждого файла, `load_sha256` по релизам).

Порядок цепочки хэшей внутри `generate`: JTL → `load_sha256` → снимок плеча → семантический хэш → планы трендов и корреляций → pod-view. Контекст OpenSearch в снимок не сливается (`ltv opensearch prepare` перезаписал бы снимок и сломал привязки, а у него собственный план корреляций; на `analyze` подаётся один `--correlation`). Слияние ошибок OpenSearch в снимок остаётся необязательным шагом README.

- [ ] **Step 1: Тесты.** (а) `test_generate_tiny_all_files`: в `tiny` для каждого из 6 релизов созданы все файлы таблицы «Файлы запуска», нет файлов вне `--out`; (б) `test_generate_byte_identical`: два запуска в разные каталоги дают одинаковые SHA-256 всех файлов; (в) `test_generate_golden_hashes`: SHA-256 файлов `tiny` для релиза 2026.09.3 равны зафиксированным в тесте (обновляются только осознанно); (г) `test_no_wall_clock`: подмена `time.time` и `datetime.now` исключением не ломает запуск; (д) `test_bindings_consistent`: во всех файлах плеча `load_input_sha256` равен SHA-256 `load.jtl`, а `resource_snapshot_sha256` планов и pod-view равен семантическому хэшу снимка этого же плеча.
- [ ] **Step 2:** FAIL.
- [ ] **Step 3:** реализация команды и манифеста (`manifest.json` в `--out`).
- [ ] **Step 4:** `python -m unittest tools.test_demo_dataset -v` проходит; `python -m unittest discover -s tools -p "test_*.py"` не ломает соседние тесты.
- [ ] **Step 5: Commit** `feat(tools): demo dataset generate command`.

### Task 7: Скрипт сборки каталога данных

**Files:**

- Create: `tools/demo_dataset_build.py`
- Modify: `tools/test_demo_dataset.py`

**Interfaces:**

- Consumes: каталог `--out` из Task 6, `manifest.json`.
- Produces: CLI `python tools/demo_dataset_build.py --ltv PATH --out DIR --data-dir DIR [--profile ...] [--skip-generate] [--force-new]`; `run_analyze(ltv: Path, args: list[str], expected_exit: int, env: dict) -> tuple[str, str]` (возвращает `run_id`, `analysis_id` из строки stderr `analysis_id=<id> run_id=<id>`, `CommandLine.kt:413`); `ApiClient(origin: str)` с методами `bootstrap()`, `post(path: str, body: dict) -> dict`; `start_ui(ltv, data_dir) -> (process, origin)`; `save_releases(...)`, `set_baselines(...)`.

Что известно и что проверить (не предположение, шаги ниже):

- Релизы и baseline создаются только через HTTP API (`POST /api/releases`, `POST /api/baseline`), в CLI их нет; CLI-команд не добавляем.
- Мутации требуют `Host: 127.0.0.1:<порт>`, `Origin: http://127.0.0.1:<порт>`, cookie `ltv_session` и заголовок `X-LTV-CSRF` из `GET /api/bootstrap` (`LocalApi.kt:62-76`, `:112-123`).
- `ltv ui` берёт случайный порт и открывает браузер; происхождение печатается в stderr только когда `Desktop` недоступен (`LocalServer.kt:39-55`). Для скрипта задаётся `JAVA_OPTS=-Djava.awt.headless=true`.
- Релиз с несколькими анализами требует разные непустые плечи (`LocalRelease.kt:125-126`), поэтому анализ S (без плеча) в релиз не входит, а его baseline задаётся слотом `(series, без плеча)` через поле `series`.
- Идентификаторы релизов выдаёт сервер (`ReleaseStore.kt:62`): скрипт читает `release_id` из ответа POST.

- [ ] **Step 1: Тесты (без запуска `ltv`).** (а) `test_analysis_matrix`: матрица из 14 анализов, у каждого ожидаемый код выхода (0, 2, 3) и набор флагов (`--policy`, `--resources`, `--trend`, `--pod-view`, `--correlation`, `--source-context` у A и B; `--stages` и `policy-stages.json` у S); (б) `test_unexpected_exit_code_fails`: подмена `subprocess.run` с кодом 4 вместо 2 даёт `BuildError`; (в) `test_parse_ids_from_stderr`; (г) `test_nonempty_data_dir_refused` (Review Focus 5): без `--force-new` отказ, если каталог данных не пуст; (д) `test_release_bodies`: тело `POST /api/releases` содержит `series`, `label`, `run_id`, `analyses` (`[A, B]`), `profile` (6 полей), `notes` (ровно эти ключи, `RELEASE_POST_FIELDS`); (е) `test_baseline_bodies`: два тела `mode: manual`, `series`, `reference {run_id, analysis_id}` для 2026.09.2 (по одному на A и B) плюс тело для S.
- [ ] **Step 2:** FAIL.
- [ ] **Step 3:** реализация. Порядок в `main`: (1) `generate`, если нет `--skip-generate`; (2) для каждого релиза по порядку дат все `ltv analyze` с `--data-dir`; ожидаемые коды: 2026.09.3 A=2, B=2, S=2; 2026.09.2 0; 2026.09.1 0; 2026.09.0 3; 2026.08.3 2; 2026.08.2 0 (итог сверяется с `policy_verdict` из `analysis-result.json` в каталоге данных); (3) `ltv ui` с `JAVA_OPTS=-Djava.awt.headless=true`, чтение происхождения из stderr с тайм-аутом 60 с; (4) `GET /api/bootstrap`, `POST /api/releases` для 6 релизов (профиль одинаков, `label` равен номеру релиза, `series` = `Checkout`), `POST /api/baseline` для 2026.09.2: A, B и S; (5) остановка `ltv ui`; (6) печать итоговой таблицы релизов и `ltv ui --data-dir ...` для запуска вручную.
- [ ] **Step 4:** тесты проходят.
- [ ] **Step 5: Верификация предположений (записать результат в README):** запуск `ltv ui` с `JAVA_OPTS=-Djava.awt.headless=true` печатает происхождение в stderr и не открывает браузер; если нет, запасной путь: `-Djava.awt.headless=true` через `JAVA_TOOL_OPTIONS`.
- [ ] **Step 6: Commit** `feat(tools): demo dataset data directory builder`.

### Task 8: Сквозная сборка и приёмка по экранам

**Files:** без новых файлов; правки `scenario.json` по результатам (пороги, наклоны)

- [ ] **Step 1:** `./gradlew installDist`, затем `python tools/demo_dataset_build.py --ltv build/install/ltv/bin/ltv(.bat) --out %TEMP%\ltv-demo-8h --data-dir %TEMP%\ltv-demo-8h-data --profile demo`. Ожидание: код 0, таблица из 6 релизов, время сборки записано.
- [ ] **Step 2: Проверка вердиктов.** По `policy_verdict` и `policy_check`: 2026.09.3 B нарушены ровно `p95 POST /checkout`, `error_rate POST /checkout`, `restarts/payments-svc`; A нарушены первые два; 2026.09.2 и 2026.09.1 и 2026.08.2 PASS; 2026.09.0 NO_VERDICT с причиной «транзакция не найдена»; 2026.08.3 нарушено одно правило. Расхождения правят `scenario.json`, не код продукта.
- [ ] **Step 3: Приёмка по экранам** по матрице раздела «Матрица экранов макета»: `ltv ui --data-dir ...`, открыть каждый экран на указанном анализе и сравнить со снимками `docs/ui-mockup/screens/01-10`. Результат записать таблицей «блок, есть/нет, причина» в README (в том числе разрывы 1-10). Проверить отдельно: как UI считает «n из 9» (разрыв 8); платформенные плитки и «Где проблема» заполнены; блок трендов Soak не пуст; корреляции не показывают пустое состояние (если движок вернул «недостаточно данных», это записывается как разрыв, а не исправляется подгонкой).
- [ ] **Step 4:** повторить сборку в чистом каталоге: SHA-256 `load.jtl` и снимков совпадают с первым запуском; `analysis_id` совпадают.
- [ ] **Step 5: Commit** `chore(demo): tune demo dataset scenario` (только если менялся `scenario.json`).

### Task 9 (необязательная): анализ ёмкости C

**Files:** Modify `tools/demo_dataset.py`, `tools/demo_dataset_build.py`, `tools/test_demo_dataset.py`

Ёмкость требует шаг снимка не более 10 с и несовместима со стадиями, поэтому это третий ресурсный анализ запуска 2026.09.3 с отдельным снимком (плечо A, 2 880 ячеек, около 10 рядов: RPS `interval_mean`, CPU генератора, CPU и троттлинг `checkout-api`). Блок «Ёмкость по ступеням» показывает ступени; график колена не калиброван (разрыв 4).

**Interfaces:** `build_capacity_snapshot(...) -> dict`, `build_capacity_plan(snapshot_sha256, load_sha256, scenario) -> dict` (`capacity-plan.v1`, `load_axis` `rps`, ступени плато 1, ступень, плато 2).

- [ ] **Step 1: Тесты:** шаг 10 000 мс; 2 880 ячеек; число рядов в пределе (не более 520 на шаге 10 с); привязки хэшей.
- [ ] **Step 2-4:** реализация, тесты, `ltv analyze ... --resources ... --capacity ...`; в релиз анализ C не входит (в релизе один анализ на плечо).
- [ ] **Step 5: Commit** `feat(tools): demo dataset capacity analysis`.

### Task 10 (необязательная): полная история из 9 релизов

**Files:** Modify `examples/demo-8h/scenario.json`, `tools/test_demo_dataset.py`

Добавить 2026.08.4 (4 часа, другой профиль, `cmp` ложь), 2026.08.1, 2026.07.4 как строки сценария (профиль и вердикты из макета, строки 694-702). Проверить: 4-часовой запуск показывает «другой профиль» в истории.

- [ ] **Step 1:** тест `test_history_has_nine_releases`.
- [ ] **Step 2-4:** строки сценария, прогон сборки, проверка экрана «История».
- [ ] **Step 5: Commit** `feat(tools): demo dataset full release history`.

### Task 11: Документация и журнал изменений

**Files:**

- Create: `examples/demo-8h/README.md`, `changelog.d/demo-8h-dataset.added.md`
- Modify: `examples/README.md`

- [ ] **Step 1:** README: назначение, профили (`tiny`, `demo`, `full`) с размерами и временем из спайка Task 2 и сборки Task 8, команды сборки для Windows и Linux, как открыть UI, таблица приёмки по экранам, список разрывов 1-10, как слить ошибки OpenSearch в снимок (`ltv opensearch prepare --resources`, необязательно), предупреждение о диске, замечание «данные придуманы, не реальный прогон».
- [ ] **Step 2:** строка в таблицу `examples/README.md`: `demo-8h/README.md` с назначением.
- [ ] **Step 3:** фрагмент журнала `changelog.d/demo-8h-dataset.added.md` в формате README каталога (маркер `-`, русский, без заголовков): добавлен генератор синтетического набора на 8 часов и скрипт сборки каталога данных; ядро, API, схемы и UI не менялись; ограничения: стадии и платформа в разных анализах, ИИ-разбор не воспроизводится.
- [ ] **Step 4:** `python tools/changelog_assemble.py --check` (проверка имени и формата, как в README каталога). Ожидание: код 0.
- [ ] **Step 5:** полная проверка перед слиянием: `python -m unittest discover -s tools -p "test_*.py"`; поиск секретов (`git diff origin/main | grep -i -E "password|token|secret"` не находит значений); `git status --short` показывает только файлы из EXPECTED FILES.
- [ ] **Step 6: Commit** `docs(examples): demo-8h data set`.

## Команды сборки кратко

```text
# 1. Дистрибутив
./gradlew installDist

# 2. Всё одной командой (генерация, 14 анализов, релизы, baseline)
python tools/demo_dataset_build.py --ltv build/install/ltv/bin/ltv --out <tmp>/ltv-demo-8h \
  --data-dir <tmp>/ltv-demo-8h-data --profile demo

# 3. Показ
ltv ui --data-dir <tmp>/ltv-demo-8h-data
```

Что делает шаг 2 (для каждого релиза по порядку дат): `ltv analyze load.jtl --policy policy.json --resources arm-A/resource-snapshot.json --trend arm-A/trend-plan.json --pod-view arm-A/pod-view.json --correlation arm-A/correlation-plan.json --source-context opensearch-errors.json --data-dir D`; то же для `arm-B`; для 2026.09.3 и 2026.09.2 дополнительно `ltv analyze load.jtl --policy policy-stages.json --stages load-stages.json --data-dir D`; затем `ltv ui` без браузера, `POST /api/releases` по релизу (`analyses`: A и B), `POST /api/baseline` (`mode: manual`, `series: Checkout`) для анализов A, B и S релиза 2026.09.2.

## Риски

- **Объём диска.** `full` для шести запусков около 3,2 ГБ плюс столько же копий в каталоге данных; по умолчанию `demo`. Смягчение: предупреждение в README, проверка свободного места в скрипте сборки.
- **Парсер или анализ отвергает вход** (формат колонок, предел выборки, окно вне прогона). Смягчение: спайк Task 2, тест `ltv policy validate` и сквозная сборка Task 8.
- **Порог throughput и правила окон не совпадут с формулами макета.** Смягчение: пороги в `scenario.json`, сверка вердиктов в Task 8, расхождения с макетом записываются как разрыв.
- **Корреляции и тренды дают «недостаточно данных».** Движок честно может не выдать вывод; набор не подгоняется под результат, разрыв фиксируется.
- **`JAVA_OPTS` не отключает браузер.** Смягчение: запасной путь `JAVA_TOOL_OPTIONS`, проверка в Task 7.
- **Время UI.** Если UI показывает UTC, оси идут с 10:00Z; макет локальный. Записано как разрыв 10.
- **Хэши привязок.** Любое изменение генератора меняет SHA-256 и `analysis_id`; golden-тест (Task 6) обновляется осознанно.

## Вопросы владельцу

1. **Профиль по умолчанию.** `demo` (RPS в пять раз ниже истинного, около 1,3 ГБ на диске, сборка 2-3 мин) или `full` (истинные 126 зпр/с, около 3,6 млн строк на запуск, около 6,5 ГБ на диске вместе с копиями, 8-10 мин)? План берёт `demo`.
2. **Стадии и платформа на одном экране.** Принимается ли показ ленты стадий на отдельном анализе S, пока продукт запрещает стадии вместе со снимком (ADR 0030, R3)?
3. **Счёт «3 из 9».** Считать нарушенные шаблоны правил (как в плане) или развёрнутые платформенные проверки (20 на правило)?
4. **ИИ-разбор.** Экран показывается пустым (нет рантайма) или нужен заранее записанный совет, сохранённый как пример (потребует отдельной проработки, не входит в этот план)?
5. **Глубина истории.** Достаточно шести релизов (три PASS для статистического baseline) или нужны все девять строк макета (Task 10)?

## Что не проверено при составлении плана

- Ничего не запускалось: ни `ltv`, ни существующие генераторы, ни тесты. Размеры, время генерации и разбора это оценки по замерам 10 млн строк (`2026-10-08-w3-6a-realistic-inputs.md`) и по формулам макета.
- Не проверено, что парсер принимает суженный набор колонок JTL и что UI считает «n из 9» по шаблонам.
- Не проверено поведение политики с `platform_rules` в режиме стадий (поэтому отдельная `policy-stages.json`).
- Не проверена совместимость флагов `--trend`, `--pod-view`, `--correlation`, `--source-context` и `--capacity` в одном вызове; `basic/run.md` перечисляет только конфликты со стадиями и онлайн-источником.
- Не проверено, что `JAVA_OPTS=-Djava.awt.headless=true` доходит до JVM через стартовые скрипты и что `ltv ui` тогда печатает происхождение (см. Task 7, шаг 5).
- Не проверено, возвращает ли движок корреляций и трендов содержательный результат на синтетике.
- Сигнатуры `tools/pod_view_adapter.assemble` и `tools/platform_synthetic` не читались полностью; Task 5 начинается с их чтения.
- Макет: данные получены чтением формул `raw()`, `podVal()` и таблицы релизов, браузер не запускался.

## Факты и отклонения при исполнении (2026-11-19)

Проверено запусками (сборка `./gradlew installDist -x test`, профиль `demo`):

- Парсер JTL принимает 4, 6 и 7 колонок. Формат сужен до шести колонок
  `timeStamp,elapsed,label,responseCode,success,failureMessage` для всех релизов
  (вместо 17): JTL 2026.09.3 занимает 31,2 МБ (735 211 строк), а не около 105 МБ.
  Группы ошибок JTL (`error-groups.json`) при этом создаются.
- Флаги `--policy --resources --trend --pod-view --correlation --source-context`
  работают в одном вызове `ltv analyze`; все 14 анализов вернули ожидаемые коды.
- `JAVA_OPTS=-Djava.awt.headless=true` доходит до JVM через `ltv.bat`; `ltv ui`
  не открывает браузер и печатает `http://127.0.0.1:<порт>` отдельной строкой в
  stderr. На Windows процесс останавливается `taskkill /F /T` (дочерний java).
- `tools/platform_synthetic` не используется: он закрыт под одно плечо и
  фиксированный набор метрик. Из `tools/pod_view_adapter` взяты `assemble`,
  `dumps`, `Raw`, `Observation`. В pod-view допустимы только четыре метрики
  (CPU, память, троттлинг, CPU пода): рестартов в нём нет.
- Тренды: движок возвращает `TREND_OBSERVED` по всем пяти проверкам. Корреляции:
  пары считаются, но слабые (`raw_rho` от -0,014 до 0,107), заголовок
  `UNAVAILABLE` (`GENUINE_PARTIAL_UNCALIBRATED`); данные не подгонялись.
- Размеры и время сильно ниже оценок плана: входы шести релизов 221 МБ,
  каталог данных 351 МБ (план: около 1,3 ГБ), 14 анализов 228 с, сборка 4 мин 27 с.

Отклонения от текста задач:

- Сигнатуры: `build_policy`, `build_policy_stages`, `build_trend_plan`
  принимают профиль (порог throughput и `platform_services` зависят от него);
  `build_pod_view(model, snapshot_sha256, load_sha256, release, profile, scenario)`
  берёт результат `simulate_arm`; `build_snapshot` принимает необязательный
  `model`; `p95_model(tx, release, profile, t_ms, scenario)`.
- `restarts` в снимке это ряд `interval_rate` (события в секунду), а не
  накопительный счётчик: тест проверяет, что сумма ряда на шаг равна 3.
- Тесты p95 после колена: окно `soak` целиком, а не последние 20 ячеек (в `tiny`
  там слишком мало выборок для устойчивого p95).
- Последняя секунда запуска всегда содержит сэмпл каждой транзакции: без этого
  окно снимка выходит за конец JTL (`RESOURCE_WINDOW_OUTSIDE_RUN`).
- Текстовые поля профиля релиза не длиннее 128 байт UTF-8 (предел API).
- Наклоны трендов в коротких профилях масштабируются на `480 мин / длительность`.
- Пул соединений с трендом отнесён к `checkout-api` (в `tiny` нет `orders-svc`).
- Переключатель анализа стадий вместе со снимком: `--combined-stages` и функция
  `analysis_matrix` (решение владельца 4).
- Task 9 (ёмкость) и Task 10 (девять релизов) не выполнялись: Task 10 исключена
  решением владельца, Task 9 необязательная и не входила в поручение.
- Экраны UI глазами не просматривались: таблица в README выведена из результатов
  анализов и файлов каталога данных.
