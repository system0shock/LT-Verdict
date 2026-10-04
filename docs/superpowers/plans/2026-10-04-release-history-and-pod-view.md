# История релизов, выбор baseline и данные по подам (ADR 0019 и ADR 0020): Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** реализовать принятые ADR 0019 (история релизов как приватная запись `local-release.v1`, baseline только из `PASS`, динамика и выбор baseline по серии и плечу) набором независимых срезов, один PR на срез, и сверить с этим планом реализацию ADR 0020 (диагностический артефакт `pod-view.v1` по подам), которая расписана в плане платформы (срезы P2a-P2e). Рабочий масштаб, заданный владельцем: до десятка протоколов (`series`), чаще пять; в протоколе 2-4 релиза; число плеч не фиксировано.

**Architecture:** запись релиза живёт вне RunBundle, как `baseline.json` и `run-period.json`: каталог `<data>/releases/<release_id>.json`, канонический JSON, staging, `ATOMIC_MOVE`, `operationLock` только на короткие проверки и публикацию. Все дорогие чтения (хэш `analysis-result.json` до 64 MiB) выполняются вне замка: анализы неизменяемы, хранилище их не удаляет (аргумент дополнения ADR 0002 от 2026-10-01). Допуск baseline считает одна чистая функция ядра `baselineCandidateRejection`, её вызывают оба режима `POST /api/baseline` и сводка релиза: два места с правилом разошлись бы. `pod-view.v1` реализует план платформы (P2a-P2e); этот план сверяет его с историей релизов и хранилищем.

**Tech Stack:** Kotlin 2 (JUnit 5, Gradle `gradlew.bat`), Ktor, kotlinx.serialization; Vue 3 + TypeScript (Playwright, `npm --prefix ui`); Markdown (markdownlint). Новых production-зависимостей нет.

**Spec:** `docs/adr/0019-release-history-and-baseline-eligibility.md`, `docs/adr/0020-pod-view-artifact.md` (оба Accepted 2026-10-02, поправка ADR 0019 от 2026-10-04 по малой выборке); согласующие: `docs/adr/0017-baseline-candidates-and-confirmation.md`, `docs/adr/0018-policy-platform-rules-small-samples.md`, `docs/adr/0016-metric-semantics-percentile-empty-window-jmeter-parents.md`, `docs/adr/0014-resource-series-limits-autostep-arm-api.md`, `docs/adr/0002-slice-1-runtime-filesystem-security.md` (дополнения от 2026-09-30 и 2026-10-01). Рабочие материалы вне git (`docs/ui-mockup/implementation-plan.md`, раздел D5, `docs/ui-mockup/feature-gap.md`, макет) лежат в основном рабочем дереве владельца и не входят в `origin/main`; на них здесь ссылки только в коде.

База проверки кода: `origin/main` `e36a6d2` (после #62: ADR 0018 S1, ADR 0016 S3, `interval_max`/`interval_min`, автошаг S2-S3 влиты; `arm`/P1 не влит). Все ссылки `файл:строка` сверены с ней; ссылки ADR 0019 на `0dce94d` устарели: перед стартом каждого среза сверить заново (`git diff --stat origin/main`).

## REQUESTED / REQUIRED / NOT REQUIRED / EXPECTED FILES (AGENTS.md)

```text
REQUESTED: план реализации ADR 0019 и ADR 0020 (без кода): хранилище и
  контракты релиза, API, правило baseline только из PASS, предупреждения
  сравнения, динамика, UI истории и выбора baseline, слоты (series, arm),
  артефакт pod-view; срезы с размерами, зависимостями, очередью identity,
  выделенным минимумом для живого демо, вопросами владельцу и проверкой.
REQUIRED TO ACHIEVE IT (13 срезов этого плана; pod-view реализуют срезы P2a-P2e плана платформы, здесь его сверка):
  R0  docs  - поправка ADR 0019 (поля копий, порядок списка, коды), только
              после ответа владельца;
  R1  core  - baseline только из PASS в обоих режимах, чтение результата с
              проверкой SHA-256 и пределом 64 MiB;
  R2  store - local-release.v1: проверка записи, RunBundleStore, пределы;
  R3  api   - маршруты /api/releases, PUT в проверке Origin/CSRF;
  R4  core  - допуск SMALL_SAMPLE и предупреждение BASELINE_SMALL_SAMPLE;
  R5  core  - comparison: profile и предупреждения BASELINE_NOT_PASS,
              BASELINE_SERIES_DIFFERS, POLICY_DIFFERS, PROFILE_MISMATCH;
  R6  api   - /analytics заполняет application_version и load_profile;
  R7  ui    - вкладка «История», «Сохранить как релиз», действия строки;
  R8  ui    - профиль (подстановка, несовпадение), точки динамики,
              перепривязка анализа после смены identity;
  R9  ui    - BaselinePanel: коды 422 и новые предупреждения;
  B1  store - слоты (series, arm), legacy baseline.json, одна операция;
  B2  api   - слот в comparison и baseline-conditions, адресный DELETE;
  B3  ui    - baseline по серии в истории и в BaselinePanel;
  Pod-view (ADR 0020): срезы P2a-P2e плана платформы; здесь сверка с ADR и
  кодом, замечания к P2b, P2c, P2e и стыки с историей релизов.
NOT REQUIRED (report-only, вне этого плана):
  - срезы ADR 0014 (arm/resource_arm P1, D0 API рядов; interval_max и автошаг
    уже влиты), ADR 0016 S3 (влит), ADR 0018 S1 (влит) и S2-S10 (кроме
    согласований ниже), реализация pod-view (срезы P2a-P2e плана платформы);
  - экран тепловой карты и таблицы подов (срез P4 плана платформы), онлайн-
    производитель pod-view, адаптер, который пишет pod-view;
  - общий вердикт «все плечи», принудительный baseline не из PASS (отложен
    владельцем), rolling baseline, автоматический импорт анализов в релизы;
  - перенос релизов во внешнее хранилище, Jenkins, копирование метрик в запись
    релиза, серверные статистики окна pod-view;
  - приёмка на реальном стенде (в МВП не входит, риски названы отдельно).
EXPECTED FILES TO CHANGE: по срезам, см. блок Files каждого среза. Если срез
  разрастается сверх списка, остановиться и объяснить (AGENTS.md, п. 10).
```

## Global Constraints

Каждая задача неявно включает этот раздел.

- Формат `local-release.v1` приватный, публичной схемы в `docs/contracts` нет (ADR 0019, раздел 1).
- Ключ сопоставимости baseline и динамики не меняется ни одним срезом: `SEMANTIC_FIELDS` (`BaselineComparison.kt:942-943`) и `COMPARISON_SEMANTIC_FIELDS` (`RunComparison.kt:424-425`) остаются прежними; `pod_view` не входит в `modules`, `input_versions`, `limits` (ADR 0020, раздел 5).
- Правило identity: ни один срез этого плана не меняет identity анализа и ключ сопоставимости. Условные поля pod-view вводит P2b плана платформы (в общей очереди identity-срезов); существующие golden-фикстуры `fixtures/slice1/identity/analysis-identity*.json` и `analysis-identity.sha256` этот план не правит.
- Короткий замок (ADR 0002, дополнение 2026-10-01): под `operationLock` остаются проверки открытого каталога, перечисление и разбор файлов записей (до 1 001 файла по 8 KiB), чтение identity слотов (до 65 небольших файлов), чтение записей условий (до 4 096 файлов по 4 KiB при адресном удалении), проверка уникальности, лимиты, `exists`-проверка и атомарная публикация; все они ограничены константами и замеряются. Чтение и SHA-256 `analysis-result.json` (до 64 MiB), сборка фактов и любые сетевые или CPU-тяжёлые действия выполняются вне замка. Допущение «проверил вне замка, опубликовал внутри» опирается на неизменяемость анализа: код хранилища не удаляет каталоги анализов (`DataDirectory.deleteTree` применяется только к собственному staging).
- Любой документ, прочитанный из каталога данных, проходит строгую форму: известный набор ключей, типы, пределы, каноничность (`bytes == canonicalJson(validated)`), как у `baseline.json` и `run-period.json` (`RunBundleStore.kt:557-635`). Неизвестная версия не мигрируется молча.
- Пользовательский текст (`label`, `notes`, поля `profile`, `series`) нормализуется на границе API (NFC, перевод строки `\n`, обрезка пробелов), хранится как есть и не попадает в сообщения об ошибках, журналы, ИИ-разбор и имена файлов. В экспорты динамики попадают только `label` и краткое представление профиля, через существующее экранирование (`AnalyticsExport.kt:225-236`, `:212-223`); `notes` не экспортируются никогда.
- Коды выхода CLI не меняются. CLI релизов не получает (ADR 0019 о нём не говорит; MINIMAL-CHANGE).
- Предупреждения comparison только дописываются в `warnings` и никогда не блокируют сравнение и не меняют метрики, verdict, validity, coverage и статусы окон (ADR 0019, раздел 5).
- Пределы хранилища (`MAX_RELEASES = 1 000`, `MAX_RELEASE_ANALYSES = 8`, `MAX_RELEASE_BYTES = 8 KiB`, `MAX_BASELINE_SLOTS = 64`, `MAX_VERIFIED_RESULT_BYTES = 64 MiB`) - константы хранилища, а не формата; тесты границ параметризованы константой, а не литералом (ADR 0019, раздел 2).
- Каждый новый код ответа API проверяется и в тесте API, и в e2e, если его показывает интерфейс. Все русские строки интерфейса живут в `ui/src/shell/labels.ts`; остальной код новой оболочки остаётся ASCII (договорённость файла).
- Форматирование: `ktlint` входит в `check`; фрагменты кода в плане показывают суть и могут не соответствовать правилам (длина строки, сигнатуры в одну строку): исполнитель запускает `.\gradlew.bat ktlintFormat`, затем `ktlintCheck`.
- Новых production-зависимостей нет. Если срез требует зависимость, остановиться и записать в план или ADR до кода (AGENTS.md, «Before changing files», п. 5).
- Проза и документация на русском, идентификаторы и коды на английском. Коммиты атомарные, Conventional Commits, в конце `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>`; в индекс только файлы задачи; push, merge, rebase только по явному разрешению владельца.
- Перед каждым срезом: `git status --short --branch`, отдельный воркстри и ветка (`git worktree add .worktrees/<имя> -b <ветка> origin/main`), базовый прогон `.\gradlew.bat test` и `npm --prefix ui run typecheck` с записью числа тестов. Тесты Gradle выполняет корень или субагент Claude: из песочницы Codex Gradle не запускается (нет записи в `~/.gradle`).

## Review Focus

Условия, которые ADR подразумевают, но задачи не покрывают отдельно. Для каждого есть тест в указанном срезе.

1. Гонка двух вкладок: два `POST /api/releases` с одним `analysis_id` дают один `201` и один `409 RELEASE_ANALYSIS_ALREADY_REGISTERED`; `PUT`, переносящий чужой `analysis_id`, даёт `409`; `PUT`, оставляющий собственный `analysis_id` записи, проходит (запись исключается из проверки уникальности) (R2, R3).
2. Подмена `analysis-result.json` при сохранённом размере (замена `FAIL` на `PASS`): выбор baseline и регистрация релиза отвергают её (ошибка целостности), потому что обычное чтение хэш результата не проверяет (`RunBundleStore.kt:697-704`) (R1, R3).
3. Граница 64 MiB: результат ровно 64 MiB читается, на байт больше даёт `422 BASELINE_CANDIDATE_TOO_LARGE` (для релиза `RELEASE_RESULT_TOO_LARGE`) до чтения тела, по размеру из манифеста (R1, R3).
4. Граница реестра: 999 корректных записей плюс одна даёт `201`; следующий `POST` даёт `422 RELEASE_LIMIT_REACHED` с `error.limit`; постороннее имя файла, повреждённая запись, symlink считаются в 1 000; при 1 001 элементе список и `POST` дают `500 CORRUPT_RELEASE_REGISTRY` и ничего не усекают (R2, R3).
5. Время анализа: `started_at` без дробной части (`2026-01-01T00:00:00Z`) и с миллисекундами дают разные 15-значные метки; метка не короче 15 знаков для эпохи 0 и не длиннее для `253 402 300 799 999` (R2).
6. Границы текстов: `series`, `label`, поле профиля ровно 128 байт UTF-8 с многобайтными символами проходят, 129 отвергаются; заметка 1 024 байта проходит; запись, чей канонический JSON превысил 8 KiB из-за экранирования кавычек, отвечает `422 RELEASE_TOO_LARGE`, а не `500` (в коде baseline здесь стоит `check`, дающий 500) (R2, R3).
7. Профили: композитные и разложенные символы дают равный профиль после NFC; объект из шести `null` сохраняется как `profile: null`; два `null` не дают `MATCH` (R2, R5).
8. Дубли в каталоге вне API: один `analysis_id` в двух корректных файлах (ручное копирование) не ломает сравнение: поиск считает его неоднозначным, `profile` равен `null`, список показывает обе записи, `POST` с этим `analysis_id` даёт `409` (R2, R5).
9. `DELETE` повреждённой записи по безопасному идентификатору удаляет файл без разбора; `DELETE` несуществующей даёт `404`; `DELETE` не трогает baseline, условия и анализы (R2, R3).
10. Старый `baseline.json` с анализом не `PASS` читается и даёт `BASELINE_NOT_PASS` при comparison, без отказа и без миграции (R1, R5).
11. `PUT` без Origin, без cookie или без CSRF-заголовка даёт `403`, как `POST` и `DELETE` (R3, `LocalSecurityTest`).
12. Метка с `<script>`, `|`, `----` и переводом строки не разрушает HTML, Confluence и AsciiDoc экспорт динамики (R6).
13. Медленная проверка хэша результата не блокирует `listRuns`, `readAnalysis`, чтение baseline (R1; по образцу «a slow analysis writer does not block store reads», `RunBundleStoreTest.kt:156`).
14. Статистический и ручной режимы отвергают `INVALID`, `INCOMPLETE`, `FAIL`, `NO_POLICY`, `NO_VERDICT` в одном и том же порядке кодов (раздел «Порядок кодов 422») (R1).
15. Идентичность pod-view (P2b плана платформы; эта сверка требует тех же оракулов): golden без pod-view байт-идентичен прежнему; с pod-view добавлены только два условных поля; пара анализов с проекцией и без неё остаётся совместимой по ключу.
16. Pod-view сверх любого предела отвергается без частичной записи (`POD_VIEW_LIMIT_EXCEEDED`), `coverage` честно показывает усечённый охват (P2a, P2b).
17. Подмена `pod-view.json` при сохранённом размере обнаруживается при каждом чтении API (P2c); конец сетки с неполной последней колонкой принимается как `to_ms`.

## Порядок кодов 422 при выборе baseline

Фактический приоритет на HTTP-пути (R1 сохраняет существующие проверки запроса на своих местах, `selectBaseline`, `LocalApi.kt:1028-1061`), одинаковый для тестов, e2e и ответов:

1. Проверки самого запроса до чтения результатов: форма тела и серия (`400`), в режиме statistical `BASELINE_COMPARABILITY_UNCONFIRMED` (`comparable=false`), `BASELINE_CANDIDATE_COUNT` (не 3-20), `BASELINE_DUPLICATE_RUN`.
2. Чтение результата каждого кандидата по порядку запроса: `BASELINE_CANDIDATE_TOO_LARGE` (предел 64 MiB) и ошибки целостности (`500 CORRUPT_BASELINE`).
3. Допуск кандидата, первый нарушенный пункт: `BASELINE_CANDIDATE_INVALID` (`run_validity != VALID`), `BASELINE_CANDIDATE_INCOMPLETE` (`analysis_coverage.status != COMPLETE`; после R4 допускается `INCOMPLETE` только из-за `SMALL_SAMPLE`), `BASELINE_CANDIDATE_NOT_PASS` (`policy_verdict != PASS`; сообщение называет проверенное значение verdict в обоих режимах). Для statistical проверка идёт по кандидатам в порядке запроса до расчёта.
4. Только statistical: `BASELINE_CANDIDATE_GATES_UNKNOWN` (S10 ADR 0018), `BASELINE_CANDIDATE_MISSING_METRIC`, `BASELINE_CANDIDATE_INVALID_IDENTITY`.
5. Только statistical, на весь набор: `BASELINE_MIXED_SEMANTICS`.

Запрос `comparable=false` с кандидатом `FAIL` поэтому отвечает `BASELINE_COMPARABILITY_UNCONFIRMED`: это существующий порядок (проверка запроса до чтения), тест на API-уровне фиксирует сочетания «запросный отказ плюс неподходящий кандидат».

## Карта срезов

Размер: S (до 150 строк diff без тестов), M (150-400), L (400-800), XL (больше 800 или больше 20 файлов). «Меняет identity»: попадает ли срез в общую очередь identity-срезов. Строки P2a-P2e справочные: их реализует план платформы (раздел «Pod-view»). «Параллельно» назван в разделе «Параллельность и очередь слияния».

| Срез | Ветка | Что | ADR | Зависит от | Identity | Размер |
| --- | --- | --- | --- | --- | --- | --- |
| R0 | `docs/adr-0019-amendment` | поправка ADR 0019 (поля копий, порядок списка, коды) | 0019 | ответ владельца на В1 | нет | S |
| R1 | `feat/baseline-pass-only` | PASS-only в обоих режимах, чтение результата с хэшем | 0019 §1, §6 | нет | нет | L (правка тестов) |
| R2 | `feat/release-record-store` | `local-release.v1`, `RunBundleStore`, пределы | 0019 §1, §2 | R0 | нет | L |
| R3 | `feat/release-api` | пять маршрутов, PUT в защите, `error.limit` | 0019 §3 | R1, R2 | нет | M |
| R4 | `feat/baseline-small-sample` | допуск `SMALL_SAMPLE` и `BASELINE_SMALL_SAMPLE` | 0019 §6 (поправка 2026-10-04), 0018 S10 | R1; мягко ADR 0018 S1 | нет | S |
| R5 | `feat/comparison-profile-warnings` | `profile` и четыре предупреждения | 0019 §4, §5 | R1, R2 | нет | M |
| R6 | `feat/analytics-release-fields` | `application_version`, `load_profile` | 0019 §8 | R2 | нет | S |
| R7 | `feat/ui-release-history` | вкладка «История», сохранение, действия | 0019 §8 | R3, R6 | нет | L |
| R8 | `feat/ui-release-profile-dynamics` | профиль, точки динамики, перепривязка | 0019 §4, §8 | R7, R5 | нет | M |
| R9 | `feat/ui-baseline-codes` | коды 422 и предупреждения в BaselinePanel | 0019 §5, §6 | R1, R4, R5 | нет | M |
| B1 | `feat/baseline-slots-store` | слоты `(series, arm)`, legacy, одна операция | 0019 §7 | R1, R2 | нет | L |
| B2 | `feat/baseline-slots-api` | слот в comparison, conditions, адресный DELETE | 0019 §7 | B1, R5 | нет | L |
| B3 | `feat/ui-baseline-slots` | baseline по серии в UI | 0019 §7, §8 | B2, R7 | нет | M |
| P2a | `feat/pod-view-contract` (план платформы) | контракт и валидатор pod-view | 0020 §2, §3 | P1a (порядок ADR 0020) | нет | L |
| P2b | `feat/pod-view-job-input` (план платформы) | вход job, привязка, identity, хранение | 0020 §4, §5 | P2a, P1a | да, условно | L |
| P2c | `feat/pod-view-api` (план платформы) | чтение метаданных и значений | 0020 §6 | P2b, D0 | нет | M |
| P2e | `test/pod-view-limits-measurement` (план платформы) | замер пределов | 0020 §2 | P2c, P2d | нет | S |

Внешние зависимости (не реализуются здесь, их срезы выпускаются отдельными PR других планов):

- **ADR 0014, P1** (`resource_arm`, плечо в ключе сопоставимости; не влит): до него все анализы без плеча, релиз содержит ровно один анализ (ADR 0019, раздел 1). R2/R3 реализуют проверки плеч полностью и тестируют их на синтетических identity с `resource_arm` (identity читается как `JsonObject`, поле условное). P2b ждёт P1a (ADR 0020, раздел 7).
- **ADR 0014, `interval_max` и автошаг** (влиты: #45, #61, #62): допустимые значения `aggregation` pod-view берутся из `ResourceAggregation.entries` (четыре значения).
- **ADR 0014, D0** (конвенции API рядов; кода в `origin/main` нет, план `docs/superpowers/plans/2026-10-04-deep-analysis-d0-d2-d3.md`): P2c платформенного плана ждёт D0; на историю релизов D0 не влияет.
- **ADR 0018, S1** (влит, `a2547f2`): пометка `SMALL_SAMPLE` производится ядром, R4 наблюдаем сразу. **ADR 0018, S10** (`verdict_gates` у кандидатов statistical): выпускается после R4 и несёт только проверку блока; D5a выпускается вместе с S10 (см. «Согласование с планом ADR 0018» и гейты).
- **ADR 0016, S3 (влит), ADR 0018 S3/S7, ADR 0014 P1**: срезы, меняющие общую identity. Они не блокируют этот план, но делают сохранённые релизы «устаревшими» для сравнения с новыми анализами; сценарий перепривязки описан в разделе «Перепривязка после смены identity».

## Минимум для живого демо и полный объём МВП

Демо (минимальный коннектор Grafana, базовый анализ по SLA, анализ теста максимума, глубокий анализ в урезанном виде) не требует ни ADR 0019, ни ADR 0020: история релизов, динамика, выбор baseline и pod-view в перечне демо не названы (`docs/superpowers/plans/2026-10-04-live-demo.md` ставит платформенные срезы в «демо+»). Допущение, которое нужно подтвердить (вопрос В1): демонстрация идёт по одному-двум прогонам и не включает назначение baseline; если включает, R1 становится обязательным.

| Уровень | Срезы | Зачем |
| --- | --- | --- |
| Минимум для демо (рекомендуется; обязателен, если сценарий включает назначение baseline) | **R1** | демонстрация не должна позволить закрепить в роли эталона анализ с `FAIL`, `NO_POLICY`, неполный или невалидный: это внешне выглядит как ошибка продукта; срез самостоятелен, не требует записи релиза и не меняет identity |
| Желательно, если в сценарий демо войдёт «сравнение с прошлым релизом» | R0, R2, R3, R6, R7 | история 2-4 релизов, «Сохранить как релиз», «Сделать baseline» из строки; без профиля, точек динамики и перепривязки |
| Полный объём МВП | все срезы R и B этого плана; pod-view срезами P2a-P2e плана платформы | профиль, предупреждения, слоты по серии и плечу; pod-view с замером пределов |

Порядок «демо-пути» не зависит от остальных: R1 вливается первым и не блокирует ни одну демонстрируемую возможность. Приёмка на реальном стенде в МВП не входит (локальный стенд не в счёт); зависящие от реального стенда риски собраны в разделе «Риски».

## Параллельность и очередь слияния

Рекомендуемая очередь слияния (один PR за раз, остальное можно разрабатывать параллельно в отдельных воркстри):

```text
R1 -> R0 (docs, в любой момент после ответа В1) -> R2 -> R3 -> R6 -> R4 -> R5 -> R7 -> R9 -> R8 -> B1 -> B2 -> B3
```

Параллельно разрабатываются без общих файлов кода (общий только `CHANGELOG.md`): **R1 и R0** вместе; затем **R2** (ядро и `RunBundleStore.kt`) и UI-срез **R7** (против контракта из этого плана) не пересекаются по файлам с серверными срезами R3-R6. Срезы **R3, R4, R5, R6, B2** все правят `LocalApi.kt` (около 1 860 строк; P2b и P2c плана платформы правят его тоже), поэтому разрабатываются параллельно, но вливаются строго по очереди, с перебазированием: правки сосредоточены в разных обработчиках, конфликты ожидаются локальные. Срезы **R2, R1(читатель), B1** правят `RunBundleStore.kt`: порядок R1, R2, B1.

Очередь identity-срезов (общая для всех планов, `AnalysisResult.kt`, golden в `fixtures/slice1/identity/`): этот план в неё ничего не вносит, потому что запись релиза, слоты и динамика лежат вне identity анализа. Pod-view (P2b плана платформы) условно добавляет два поля и ставится в очередь после остальных identity-срезов; окно повторного закрепления baseline он не создаёт, потому что анализ без pod-view не меняется. Окна пересчёта для внешних identity-срезов (ADR 0016 S3 влит, ADR 0018 S3/S7, ADR 0014 P1) объявляет владелец.

### Горячие точки конфликтов

| Файл | Срезы этого плана | Правило |
| --- | --- | --- |
| `fixtures/slice1/identity/*`, `fixtures/slice1/manifest.json` | этот план их не меняет (P2b плана платформы добавляет новый файл) | существующие golden не править |
| `CHANGELOG.md` | каждый срез | запись в `[Unreleased]` одной строкой-абзацем в конце подраздела; при конфликте объединить, не терять чужие записи |
| `ui/src/App.vue` | R7, R8, B3 | минимум правок: импорт, монтаж панели `v-if="apiReady && shellNew"` с `v-show="shownIn('history')"` (в прежнем интерфейсе `shownIn` всегда истинно, `App.vue:83`, поэтому без `shellNew` панель появилась бы и там), обработчик `openReference`; не переносить существующий код |
| `ui/src/shell/labels.ts` | R7 (вкладка, `HISTORY_LABELS`), R9 (`BASELINE_LABELS`), B3 | только добавление ключей; общий словарь вкладок `SHELL_TABS` правит один срез R7 |
| `ui/src/types.ts`, `ui/src/api.ts` | R7, R9, B3 | типы и функции добавляются в конец раздела «релизы»; `BaselineComparison` правит R9 |
| `ui/e2e/baseline.spec.ts`, `ui/e2e/shell.spec.ts` | R1, R9, B2 (baseline); R7 (shell: название и состав вкладок) | R1 перестраивает помощник `analyze` под политику и общий сброс; другие срезы наследуют его |
| `src/main/kotlin/io/ltverdict/web/LocalApi.kt`, `LocalApiTest.kt` | R1, R3, R4, R5, R6, B2 (и P2b, P2c плана платформы) | сериализация слияния по очереди выше |
| `src/main/kotlin/io/ltverdict/storage/RunBundleStore.kt` | R1, R2, B1 | сначала читатель (R1), затем релизы (R2), затем слоты (B1) |
| `src/main/kotlin/io/ltverdict/core/BaselineComparison.kt` | R1, R4, R5, B2 | функция допуска (R1), затем предупреждения (R4, R5) |
| `src/main/kotlin/io/ltverdict/core/AnalysisResult.kt`, `AnalysisService.kt` | этот план не меняет (P1a и P2b плана платформы) | очередь identity выше |

## Согласование с планом ADR 0018 (S10 и допуск малой выборки)

Оба плана правят условие отбора кандидатов в `statisticalBaselineSelection` (`BaselineComparison.kt:101-104`). Чтобы условие не менялось двумя PR и допуск не выпускался без предупреждения, принято (вопрос В3, требует подтверждения владельца):

- **R1** заменяет проверки `run_validity` и `COMPLETE` вызовом единой функции допуска и добавляет `NOT_PASS`; R1 требует строго `COMPLETE`, допуск `SMALL_SAMPLE` вводит R4.
- **R4** меняет эту функцию: `INCOMPLETE` только из-за `SMALL_SAMPLE` допускается в обоих режимах, и в одном PR добавляется предупреждение `BASELINE_SMALL_SAMPLE`. Допуск без предупреждения невозможен: это один коммит и один PR.
- **S10 ADR 0018** после R4 вводит только проверку блока `verdict_gates` и код `BASELINE_CANDIDATE_GATES_UNKNOWN`. Шаги 1.1a и 1.3 S10 (допуск `SMALL_SAMPLE` и его красный тест) переходят в R4. Эту правку в плане ADR 0018 вносит его владелец: она перечислена здесь, а не выполняется молча.
- Порядок слияния: R1, затем R4, затем S10. ADR 0018 S1 уже влит, поэтому допуск и предупреждение наблюдаемы на реальных анализах; D5a (R1-R9) выпускается вместе с S10 (гейт ниже), как требуют ADR 0019 (раздел 6) и план ADR 0018 (S10).

## Сверка ADR с кодом (origin/main = e36a6d2)

| Утверждение ADR | Факт в коде | Итог для плана |
| --- | --- | --- |
| Мутации защищены проверкой Origin/session/CSRF только для POST и DELETE | `LocalApi.kt:149` проверяет `HttpMethod.Post` и `HttpMethod.Delete` | верно; R3 добавляет `Put` (и защитно `Patch`), тест `LocalSecurityTest` на все три отсутствующих признака |
| Тело JSON до 16 KiB, глубина 8 | `receiveBaselineRequest` (`LocalApi.kt:993-1026`), `MAX_BASELINE_REQUEST_BYTES = 16_384` (`:1763`); сообщения называют «Baseline request» | верно; R3 добавляет параметр с предметом сообщения (по умолчанию прежнее), чтобы ответы релизов не говорили «Baseline» |
| Чтение результата без хэша и предела | `readAnalysisDocuments` читает `analysis-result.json` целиком под замком (`RunBundleStore.kt:420-430`); `readAnalysisUnlocked` проверяет путь и размер, не хэш (`:697-704`) | верно; R1 вводит `readVerifiedAnalysis` с хэшем и пределом вне замка |
| `POST /api/baseline` читает документы обоих режимов | `selectBaseline` (`LocalApi.kt:1028-1061`); manual-ветка вызывает `baselineDocuments` и **отбрасывает** документы (`:1033-1034`) | верно; manual сегодня не проверяет ничего кроме существования (ADR 0019, раздел 6) |
| `baselineIneligible` говорит о statistical baseline | `LocalApi.kt:1096-1097`; текст ошибки закреплён e2e (`baseline.spec.ts:325`) | R1 меняет текст и e2e |
| Список динамики усечён | `readComparisonHistory` 1 000 записей, 16 MiB, 4 096 элементов, документ до 8 MiB (`RunBundleStore.kt:454-555`); ответ `/analytics` несёт `history_scan_truncated` (`LocalApi.kt:730`) | верно; в истории релизов строка без чисел объясняется (R7), а не показывается как ноль |
| `/analytics` не заполняет `application_version`, `load_profile` | строки строятся из `SavedAnalysisForComparison` без этих полей (`LocalApi.kt:696-722`); `RunDynamicsTable.vue:138-139` уже выводит `application_version ?? commit` и `load_profile` | верно; R6 передаёт значения |
| `run.json` есть только у валидного запуска | `runMetadata` пишет `started_at` как `Instant.ofEpochMilli(runStart).toString()` (`AnalysisService.kt:665`); `runStart` вычисляется по сэмплам входа (`AnalysisService.kt:249`, `:277`), поэтому одинаков у всех анализов одного входа | верно; сверка `started_at` между анализами корректна; формат строки может не содержать дробной части |
| ADR 0019 раздел 5: `BASELINE_SMALL_SAMPLE` позиция 4 | пометка введена ADR 0018 S1: причина `SMALL_SAMPLE` в `analysis_coverage.reasons` (`Policy.kt:132`, `:157`, `:178`, `:442`); `compareAnalyses` предупреждения пока не знает | R4 читает `reasons` результата baseline-анализа |
| Канонический JSON не экранирует не-ASCII | `canonicalJson` через `Json.encodeToString(JsonPrimitive)` (`CanonicalJson.kt:67-69`) | размер записи ≈ размеру содержимого; удвоение даёт только `"` и `\`; худший случай записи около 8,9 KiB при 8 анализах и сплошных кавычках: тест границы и `422 RELEASE_TOO_LARGE` |
| ADR 0004 и 0010 «частично отменены ADR 0019» | пометки в строках статуса уже стоят в `origin/main` | работа не нужна; в R0 не входит |

Найденные пробелы ADR 0019 (вынесены в R0 и вопрос В2, пока кода это не касается):

1. Копии `analyses[]` хранят `coverage_status`, но не причины, поэтому «допустим ли кандидат» (после поправки 2026-10-04 `INCOMPLETE` из-за одной `SMALL_SAMPLE` допустим) нельзя вычислить по копиям для сводки списка. Предложение: добавить `coverage_reasons` и `policy_sha256` (для подписи «PASS относителен политике», ADR 0019 раздел 6).
2. Направление списка не определено; экран «последние N» требует новых первыми. Предложение: по убыванию `release_id`, `after` исключительно возвращает меньшие.
3. `analysis_state` в списке должен быть дешёвым (существование манифеста), полная проверка (`OK`, `MISSING`, `CORRUPT`) только в `GET` по идентификатору.
4. `RELEASE_LIMIT_REACHED` называет предел «в поле `limit`»; конверт ошибок `{error:{code,message,details}}` такого поля не имеет. Предложение: необязательное целое `error.limit`.
5. `corrupt_names` лучше объектами `{name, reason}`, где `reason` один из `CORRUPT`, `UNSUPPORTED_VERSION`, `UNSAFE_ENTRY`, `TOO_LARGE`.
6. Нет кодов для переполнения записи (`RELEASE_TOO_LARGE`) и конфликта плеч (`RELEASE_ARM_CONFLICT`).

## R0. Поправка ADR 0019 (docs)

**Цель:** зафиксировать в ADR 0019 решения реализации, которые план нашёл при сверке с кодом (список «Найденные пробелы ADR 0019»), до того как они станут кодом. Срез нужен только если владелец ответит на вопрос В2 «да»; при ответе «нет» R2 и R3 пересматриваются (см. В2).

**Ветка:** `docs/adr-0019-amendment`. **Размер:** S. **Зависит от:** ответ владельца на В2. **Identity и контракты:** нет; меняет текст принятого ADR (приватный формат, ещё не выпущенный).

**Что не входит:** любые изменения кода; правка ADR 0004 и 0010 (пометки уже стоят в `origin/main`); пересмотр решений владельца 2026-10-02.

**Files:**

- Modify: `docs/adr/0019-release-history-and-baseline-eligibility.md` (новый раздел «Поправка реализации» перед «Решения владельца (2026-10-02)» и правка строки статуса)

### Task 1: текст поправки

- [ ] **Step 1.1:** Прочитать ADR 0019 целиком и убедиться, что пробелы 1-6 из раздела «Сверка ADR с кодом» этого плана не закрыты другим PR (`git log origin/main -- docs/adr/0019-release-history-and-baseline-eligibility.md`).

- [ ] **Step 1.2:** Добавить в конец строки «Статус» фразу «Поправка реализации (разделы ниже) Proposed до ответа владельца, затем Accepted с датой.» и вставить раздел:

```markdown
## Поправка реализации (Proposed)

Найдена при составлении плана реализации (`docs/superpowers/plans/2026-10-04-release-history-and-pod-view.md`); формат `local-release.v1` ещё не выпущен, поэтому правка не требует миграции.

1. **Копии фактов.** Элемент `analyses[]` получает два поля: `coverage_reasons` (массив строк до 16 элементов по 64 байта, копия `analysis_coverage.reasons`) и `policy_sha256` (64 hex-символа или `NO_POLICY`, копия поля identity). Они сверяются с документами анализа при POST и PUT. Причина: допуск baseline после поправки 2026-10-04 зависит от причин покрытия (`INCOMPLETE` только из-за `SMALL_SAMPLE` допустим), и сводка списка вычисляет допуск той же функцией, что и сервер при выборе baseline; политика нужна подписи «`PASS` относителен политике» (раздел 6).
2. **Направление списка.** `GET /api/releases` возвращает записи по убыванию `release_id` (новые первыми); `after` исключительно, следующая страница содержит строго меньшие идентификаторы; `next_after` равен последнему выданному `release_id`.
3. **Состояние ссылок.** В списке `analysis_state` равен `OK` или `MISSING` по существованию манифеста анализа (стоимость O(1) на ссылку, до 800 проверок на страницу). `GET /api/releases/{releaseId}` проверяет путь, размер и манифест анализа полностью и возвращает `OK`, `MISSING` или `CORRUPT`.
4. **Предел в ответе.** Конверт ошибки получает необязательное целое поле `error.limit`; его несёт ответ `RELEASE_LIMIT_REACHED`.
5. **Повреждённые записи.** `corrupt_names` содержит объекты `{name, reason}`, где `reason` один из `CORRUPT`, `UNSUPPORTED_VERSION`, `UNSAFE_ENTRY`, `TOO_LARGE`; имя обрезается до 128 символов.
6. **Коды.** Добавлены `RELEASE_CHANGED` (409, запись заменена параллельным `PUT` между чтением и публикацией), `RELEASE_TOO_LARGE` (422, канонический JSON записи больше 8 KiB), `RELEASE_ARM_CONFLICT` (422, повтор плеча либо анализ без плеча вместе с другими), `RELEASE_RESULT_TOO_LARGE` (422, результат анализа больше 64 MiB). `RELEASE_RUN_MISMATCH` относится к расхождению `run_id` в документах найденного анализа с запросом; анализ другого запуска по паре `(run_id, analysis_id)` не находится и даёт 404.
7. **Замок.** Чтение результата для проверки (хэш, предел 64 MiB) и подготовка записи (канонические байты, staging, `fsync`) выполняются вне `operationLock` (допущение дополнения ADR 0002 от 2026-10-01 о неизменяемости анализов); под замком остаются перечисление записей, проверки лимита и уникальности, проверка цели и публикация. Копии фактов описывают анализ на момент проверки; порчу файлов анализа после неё (вне доверенного контура, ADR 0002, дополнение 2026-09-30) запись не отслеживает, `GET` по идентификатору проверяет путь, размер и манифест.
```

- [ ] **Step 1.3:** Run: `npx --yes markdownlint-cli2@0.23.2 docs/adr/0019-release-history-and-baseline-eligibility.md`. Expected: 0 errors.

- [ ] **Step 1.4:** `git diff --check`; в индексе только ADR. Commit: `docs(adr): amend ADR 0019 with release record implementation details`.

**Documentation impact:** только ADR 0019; CHANGELOG не нужен (нет пользовательских изменений).

---

## R1. Baseline только из PASS в обоих режимах и чтение результата с хэшем

**Цель:** `POST /api/baseline` в режимах `manual` и `statistical` принимает кандидата, только если настоящий сохранённый результат имеет `run_validity == VALID`, `analysis_coverage.status == COMPLETE` и `policy_verdict == PASS`; результат читается с проверкой SHA-256 по манифесту и пределом 64 MiB вне `operationLock`. Это единственный срез плана, нужный для живого демо.

**Ветка:** `feat/baseline-pass-only`. **Размер:** L, из-за правки существующих тестов: анализы без политики (`NO_POLICY`) больше не годятся в baseline ни в одном режиме. По AGENTS.md п. 10: если после шага 4.2 список затронутых файлов тестов превысит 12, остановиться и сообщить владельцу.

**Зависит от:** ничего. **Identity и контракты:** identity и ключ сопоставимости не меняются. Публично меняется поведение приватного API: новые коды 422 `BASELINE_CANDIDATE_NOT_PASS` и `BASELINE_CANDIDATE_TOO_LARGE` у `POST /api/baseline`; ручной режим теперь отказывает для не-`PASS`. Общий текст ошибки `baselineIneligible` больше не говорит только о statistical baseline.

**Что не входит:** запись релиза, предупреждения comparison (`BASELINE_NOT_PASS` в comparison принадлежит R5), допуск `SMALL_SAMPLE` (R4), проверка `verdict_gates` (S10 ADR 0018), UI-тексты новых кодов (R9; до R9 интерфейс показывает серверное сообщение как сейчас). Старый `<data>/baseline.json` читается без миграции и новых запретов на чтение (ADR 0019, раздел 6).

**Files:**

- Modify: `src/main/kotlin/io/ltverdict/core/BaselineComparison.kt` (функция допуска; её вызов в `statisticalBaselineSelection`, строки 97-106)
- Modify: `src/main/kotlin/io/ltverdict/storage/RunBundleStore.kt` (`VerifiedAnalysis`, `readVerifiedAnalysis`, `MAX_VERIFIED_RESULT_BYTES`)
- Modify: `src/main/kotlin/io/ltverdict/web/LocalApi.kt` (`selectBaseline` 1028-1061, `baselineIneligible` 1096-1097, новая обёртка чтения)
- Modify (тесты): `src/test/kotlin/io/ltverdict/core/BaselineComparisonTest.kt`, `src/test/kotlin/io/ltverdict/storage/RunBundleStoreTest.kt`, `src/test/kotlin/io/ltverdict/web/LocalApiTest.kt`, `ui/e2e/baseline.spec.ts`
- Modify: `docs/user/slice-1-local-analysis.md` (раздел про baseline), `docs/architecture/slice-1-local-runtime.md` (раздел «Local baseline state и comparison», строки 268-300), `CHANGELOG.md`

**Interfaces:**

- Produces (Kotlin, `package io.ltverdict.core`, файл `BaselineComparison.kt`):
  - `internal fun baselineCandidateRejection(policyVerdict: String?, runValidity: String?, coverageStatus: String?): String?` возвращает код `BASELINE_CANDIDATE_INVALID`, `BASELINE_CANDIDATE_INCOMPLETE`, `BASELINE_CANDIDATE_NOT_PASS` в указанном порядке либо `null`;
  - `internal fun baselineCandidateRejection(result: JsonObject): String?` читает три поля результата и делегирует.
- Produces (`package io.ltverdict.storage`):
  - `internal data class VerifiedAnalysis(val result: JsonObject, val identity: JsonObject, val run: JsonObject?)`
  - `internal const val MAX_VERIFIED_RESULT_BYTES = 64 * 1024 * 1024`
  - `RunBundleStore.readVerifiedAnalysis(runId: String, analysisId: String, maxResultBytes: Int = MAX_VERIFIED_RESULT_BYTES, outsideLock: () -> Unit = {}): VerifiedAnalysis?` - `null`, если анализа нет; `NoSuchElementException("RUN_NOT_FOUND")`, если нет запуска; `IllegalArgumentException("RESULT_TOO_LARGE")`; `IllegalStateException("CORRUPT_RUN_BUNDLE: ...")`. Параметр `outsideLock` вызывается после выхода из замка и до чтения (образец `beforePublish` в `writeAnalysisAtomically`), только для теста замка.
- Consumes: `readAnalysisUnlocked` (манифест, путь, размер, привязка identity), `sha256Hex`, `canonicalJson`.

### Task 1: функция допуска (ядро)

- [ ] **Step 1.1: Красные тесты** (`BaselineComparisonTest.kt`). Помощник `result(...)` получает `verdict: String = "PASS"` и пишет `put("policy_verdict", verdict)` рядом с `run_validity`; тем самым существующие тесты `compareAnalyses` остаются зелёными после R5. Добавить:

```kotlin
    @Test
    fun `candidate rejection follows one fixed order`() {
        fun rejection(
            verdict: String? = "PASS",
            validity: String? = "VALID",
            coverage: String? = "COMPLETE",
        ) = baselineCandidateRejection(verdict, validity, coverage)

        assertEquals(null, rejection())
        assertEquals("BASELINE_CANDIDATE_INVALID", rejection(verdict = "FAIL", validity = "INVALID", coverage = "INCOMPLETE"))
        assertEquals("BASELINE_CANDIDATE_INVALID", rejection(validity = "DEGRADED"))
        assertEquals("BASELINE_CANDIDATE_INVALID", rejection(validity = null))
        assertEquals("BASELINE_CANDIDATE_INCOMPLETE", rejection(verdict = "FAIL", coverage = "INCOMPLETE"))
        assertEquals("BASELINE_CANDIDATE_INCOMPLETE", rejection(coverage = null))
        listOf("FAIL", "NO_POLICY", "NO_VERDICT", "pass", null).forEach {
            assertEquals("BASELINE_CANDIDATE_NOT_PASS", rejection(verdict = it), it)
        }
    }

    @Test
    fun `statistical selection rejects every verdict other than PASS before looking at metrics`() {
        val valid = listOf(candidate('a'), candidate('b'), candidate('c'))
        listOf("FAIL", "NO_POLICY", "NO_VERDICT").forEach { verdict ->
            val mixed = valid.toMutableList().also { it[1] = candidate('b', result(verdict = verdict, p95 = null)) }
            val failure =
                assertThrows(IllegalArgumentException::class.java) {
                    statisticalBaselineSelection("verdicts", mixed.references(), mixed.results(), mixed.identities())
                }
            assertEquals("BASELINE_CANDIDATE_NOT_PASS", failure.message, verdict)
        }
    }
```

В существующем тесте `statistical selection rejects every ineligible series instead of filtering candidates` порядок не проверяется (только тип исключения); он остаётся как есть.

- [ ] **Step 1.2:** Run: `.\gradlew.bat test --tests "io.ltverdict.core.BaselineComparisonTest"`. Expected: FAIL (`baselineCandidateRejection` не существует; компиляция).

- [ ] **Step 1.3: Реализация** (`BaselineComparison.kt`, рядом с `statisticalBaselineSelection`):

```kotlin
internal fun baselineCandidateRejection(
    policyVerdict: String?,
    runValidity: String?,
    coverageStatus: String?,
): String? =
    when {
        runValidity != "VALID" -> "BASELINE_CANDIDATE_INVALID"
        coverageStatus != "COMPLETE" -> "BASELINE_CANDIDATE_INCOMPLETE"
        policyVerdict != "PASS" -> "BASELINE_CANDIDATE_NOT_PASS"
        else -> null
    }

internal fun baselineCandidateRejection(result: JsonObject): String? =
    baselineCandidateRejection(
        result.stringOrNull("policy_verdict"),
        result.stringOrNull("run_validity"),
        result.objectOrNull("analysis_coverage")?.stringOrNull("status"),
    )
```

В `statisticalBaselineSelection` заменить две проверки (`run_validity == VALID` и `COMPLETE`, строки 100-105 на `e36a6d2`) на одну:

```kotlin
            baselineCandidateRejection(result)?.let { throw IllegalArgumentException(it) }
```

остальные проверки кандидата (`BASELINE_CANDIDATE_MISSING_METRIC`, `BASELINE_CANDIDATE_INVALID_IDENTITY`) остаются ниже и по порядку идут после допуска.

- [ ] **Step 1.4:** Run: `.\gradlew.bat test --tests "io.ltverdict.core.BaselineComparisonTest"`. Expected: PASS. Commit: `feat(baseline): reject candidates that are not VALID COMPLETE PASS`.

### Task 2: чтение результата с хэшем и пределом (хранилище)

- [ ] **Step 2.1: Красные тесты** (`RunBundleStoreTest.kt`). Помощник сохранения анализа с документами (общий для R1 и R2; добавить в конец класса рядом с `reference`):

```kotlin
    private fun saveAnalysis(
        store: RunBundleStore,
        input: AcceptedInput,
        tag: String,
        verdict: String = "PASS",
        validity: String = "VALID",
        startedAt: String? = "2026-01-01T00:00:00Z",
        arm: String? = null,
    ): String {
        val identity =
            canonicalJson(
                buildJsonObject {
                    put("run_id", input.runId)
                    put("policy_sha256", "a".repeat(64))
                    put("tag", tag)
                    if (arm != null) put("resource_arm", arm)
                },
            )
        val result =
            canonicalJson(
                buildJsonObject {
                    put("run_id", input.runId)
                    put("run_validity", validity)
                    put("policy_verdict", verdict)
                    put(
                        "analysis_coverage",
                        buildJsonObject {
                            put("status", "COMPLETE")
                            put("reasons", kotlinx.serialization.json.JsonArray(emptyList()))
                        },
                    )
                },
            )
        val analysisId = sha256Hex(identity)
        store.writeAnalysisAtomically(input.runId, analysisId) { staging ->
            Files.write(staging.resolve("identity.json"), identity)
            Files.write(staging.resolve("analysis-result.json"), result)
            if (startedAt != null) {
                Files.write(
                    staging.resolve("run.json"),
                    canonicalJson(buildJsonObject { put("run_id", input.runId); put("started_at", startedAt) }),
                )
            }
        }
        return analysisId
    }
```

Тесты:

```kotlin
    @Test
    fun `verified read returns the documents and detects a same-size substitution of the result`() =
        withStore { store, root ->
            val input = store.acceptInput(Files.newInputStream(Path.of(CSV_FIXTURE)), "verified.jtl")
            val analysisId = saveAnalysis(store, input, "x", verdict = "FAIL")
            val verified = store.readVerifiedAnalysis(input.runId, analysisId) ?: error("missing analysis")
            assertEquals("FAIL", (verified.result["policy_verdict"] as JsonPrimitive).content)
            assertEquals("2026-01-01T00:00:00Z", (verified.run?.get("started_at") as JsonPrimitive).content)

            val resultPath = root.resolve("runs/${input.runId}/analyses/$analysisId/analysis-result.json")
            val tampered = Files.readString(resultPath).replace("\"FAIL\"", "\"PASS\"")
            assertEquals(Files.size(resultPath), tampered.encodeToByteArray().size.toLong())
            Files.writeString(resultPath, tampered)

            // The ordinary read checks only path and size and so accepts the substitution; the verified read must not.
            assertEquals("PASS", (store.readAnalysisDocuments(input.runId, analysisId)!!.first["policy_verdict"] as JsonPrimitive).content)
            val failure = assertThrows(IllegalStateException::class.java) { store.readVerifiedAnalysis(input.runId, analysisId) }
            assertTrue(failure.message!!.startsWith("CORRUPT_RUN_BUNDLE"))
        }

    @Test
    fun `verified read enforces the result size bound at the exact byte`() =
        withStore { store, _ ->
            val input = store.acceptInput(Files.newInputStream(Path.of(CSV_FIXTURE)), "bound.jtl")
            val analysisId = saveAnalysis(store, input, "y")
            val size = store.readAnalysisDocuments(input.runId, analysisId)!!.first.let { canonicalJson(it).size }

            assertTrue(store.readVerifiedAnalysis(input.runId, analysisId, maxResultBytes = size) != null)
            val failure =
                assertThrows(IllegalArgumentException::class.java) {
                    store.readVerifiedAnalysis(input.runId, analysisId, maxResultBytes = size - 1)
                }
            assertEquals("RESULT_TOO_LARGE", failure.message)
            assertEquals(null, store.readVerifiedAnalysis(input.runId, "f".repeat(64)))
        }

    @Test
    fun `verified read does not hold the store lock while it reads and hashes`() =
        withStore { store, _ ->
            val input = store.acceptInput(Files.newInputStream(Path.of(CSV_FIXTURE)), "lock.jtl")
            val analysisId = saveAnalysis(store, input, "z")
            val inside = CountDownLatch(1)
            val release = CountDownLatch(1)
            val executor = Executors.newSingleThreadExecutor()
            try {
                val reader =
                    executor.submit {
                        store.readVerifiedAnalysis(input.runId, analysisId, outsideLock = {
                            inside.countDown()
                            check(release.await(10, TimeUnit.SECONDS))
                        })
                    }
                assertTrue(inside.await(10, TimeUnit.SECONDS))
                // The callback runs after the manifest check and before the heavy read and hash: if the mutex were still held
                // here, the other store operations below would not answer; the test proves the read and the SHA-256 are outside.
                assertEquals(1, store.listRuns(null, 10).runs.size)
                assertEquals(1, store.listAnalyses(input.runId, null, 10).analyses.size)
                release.countDown()
                reader.get(10, TimeUnit.SECONDS)
            } finally {
                release.countDown()
                executor.shutdownNow()
            }
        }
```

(Если `listRuns` в чужом потоке при удержанном замке повисло бы, `assertEquals` выполняется в потоке теста и блокировался бы; общий таймаут JUnit фиксируется `@Timeout(30)` на этом тесте.)

- [ ] **Step 2.2:** Run: `.\gradlew.bat test --tests "io.ltverdict.storage.RunBundleStoreTest"`. Expected: FAIL (метода нет).

- [ ] **Step 2.3: Реализация** (`RunBundleStore.kt`; метод рядом с `readAnalysisDocuments`, константа рядом с остальными в конце файла):

```kotlin
    fun readVerifiedAnalysis(
        runId: String,
        analysisId: String,
        maxResultBytes: Int = MAX_VERIFIED_RESULT_BYTES,
        outsideLock: () -> Unit = {},
    ): VerifiedAnalysis? {
        require(maxResultBytes in 1..MAX_VERIFIED_RESULT_BYTES)
        val stored =
            synchronized(dataDirectory.operationLock) {
                dataDirectory.requireOpen()
                readAnalysisUnlocked(runId, analysisId)
            } ?: return null
        // Published analyses are immutable and this store never deletes them (ADR 0002, addendum 2026-10-01),
        // so the heavy read and the SHA-256 run outside the mutex.
        outsideLock()
        val result = stored.verifiedBytes(RESULT_FILE, maxResultBytes, tooLarge = true)
        val identity = stored.verifiedBytes(IDENTITY_FILE, MAX_VERIFIED_DOCUMENT_BYTES, tooLarge = false)
        val run =
            if (stored.artifacts.any { it.path == "run.json" }) {
                stored.verifiedBytes("run.json", MAX_VERIFIED_DOCUMENT_BYTES, tooLarge = false)
            } else {
                null
            }
        return VerifiedAnalysis(
            parseObject(result, "analysis result"),
            parseObject(identity, "analysis identity"),
            run?.let { parseObject(it, "run metadata") },
        )
    }
```

и файловая функция:

```kotlin
private fun StoredAnalysis.verifiedBytes(
    name: String,
    maximum: Int,
    tooLarge: Boolean,
): ByteArray {
    val artifact = artifacts.singleOrNull { it.path == name } ?: corrupt("analysis artifact $name is missing")
    if (artifact.sizeBytes > maximum) {
        if (tooLarge) throw IllegalArgumentException("RESULT_TOO_LARGE")
        corrupt("analysis artifact $name exceeds $maximum bytes")
    }
    val file = requireOwnedFile(path.resolve(name))
    val bytes = Files.newInputStream(file).use { it.readNBytes(artifact.sizeBytes.toInt() + 1) }
    if (bytes.size.toLong() != artifact.sizeBytes || sha256Hex(bytes) != artifact.sha256) {
        corrupt("analysis artifact $name differs from its manifest")
    }
    return bytes
}

internal data class VerifiedAnalysis(
    val result: JsonObject,
    val identity: JsonObject,
    val run: JsonObject?,
)

internal const val MAX_VERIFIED_RESULT_BYTES = 64 * 1024 * 1024
private const val MAX_VERIFIED_DOCUMENT_BYTES = 8 * 1024 * 1024
```

`VerifiedAnalysis` и константа объявляются рядом с другими `internal data class` в начале файла (стр. 36-96). Манифест уже хранит SHA-256 каждого артефакта, поэтому хэширование не требует новых данных.

- [ ] **Step 2.4:** Run: `.\gradlew.bat test --tests "io.ltverdict.storage.RunBundleStoreTest"`. Expected: PASS. Commit: `feat(storage): read analysis documents with manifest hash and a size bound outside the lock`.

### Task 3: выбор baseline в API

- [ ] **Step 3.1: Красные тесты** (`LocalApiTest.kt`; помощник `ApiClient` и `withServer` уже есть). Анализы создаются заданием с политикой: константа

```kotlin
    private val permissivePolicy =
        """{"schema_version":"policy.v1","policy_id":"permissive","rules":[{"id":"p95","metric":"response_time_p95_ms","operator":"lte","threshold":100000,"scope":{"kind":"overall"}}]}"""
            .encodeToByteArray()
    private val failingPolicy =
        """{"schema_version":"policy.v1","policy_id":"failing","rules":[{"id":"p95","metric":"response_time_p95_ms","operator":"lte","threshold":1,"scope":{"kind":"overall"}}]}"""
            .encodeToByteArray()
```

Тесты (форма вызовов `selectManual`/`ApiClient.postJson` взять у существующих baseline-тестов файла, строки 874-902; код запроса в теле `{"mode":"manual","series":"s","reference":{...}}`):

1. `manual baseline accepts only a PASS analysis`: анализ с `permissivePolicy` принимается (200, `GET /api/baseline` его возвращает); анализ без политики даёт 422 `BASELINE_CANDIDATE_NOT_PASS`; анализ с `failingPolicy` даёт 422 `BASELINE_CANDIDATE_NOT_PASS`, сообщение содержит `policy_verdict=FAIL`; после отказов `GET /api/baseline` по-прежнему возвращает прежний выбор (отказ ничего не записал).
2. `statistical baseline refuses a FAIL candidate with the same code as manual`: три прогона, один с `failingPolicy`, ответ 422 `BASELINE_CANDIDATE_NOT_PASS`.
3. `baseline refuses a result larger than the bound`: анализ записать напрямую `store.writeAnalysisAtomically` с результатом `MAX_VERIFIED_RESULT_BYTES + 1` байт (разреженный файл: `RandomAccessFile(path, "rw").setLength(...)` в staging; содержимое не JSON, поэтому проверка размера обязана сработать до разбора), запрос выбора даёт 422 `BASELINE_CANDIDATE_TOO_LARGE`. Тест один; по времени допустим (запись и хэш 64 MiB нулей порядка секунды).
4. Подмена результата при сохранённом размере (`PASS`→`FAIL` не годится по длине; использовать `"FAIL"`→`"PASS"` на результате анализа с `failingPolicy` через прямое изменение файла) даёт 500 `CORRUPT_BASELINE`, а не принятие эталона.
5. Прежний `baseline.json` (записан через `store.replaceBaseline(manualBaselineSelection(...))` на анализ без политики) читается `GET /api/baseline` без ошибки.

Существующие тесты, создающие baseline из анализа без политики (`statisticalRuns` строка 874 и вызовы ручного выбора), переключить на `createJob(runId, permissivePolicy)`; `PASS_POLICY_SHA256` в `LocalApiTest` относится к другой политике и не используется.

- [ ] **Step 3.2:** Run: `.\gradlew.bat test --tests "io.ltverdict.web.LocalApiTest"`. Expected: FAIL (ручной режим принимает не-`PASS`; нет кода `TOO_LARGE`).

- [ ] **Step 3.3: Реализация** (`LocalApi.kt`):

```kotlin
private suspend fun RunBundleStore.verifiedBaselineDocuments(reference: JsonObject): VerifiedAnalysis =
    baselineOperation {
        try {
            readVerifiedAnalysis(reference.baselineString("run_id"), reference.baselineString("analysis_id"))
                ?: notFound("Referenced analysis was not found")
        } catch (failure: IllegalArgumentException) {
            if (failure.message == "RESULT_TOO_LARGE") baselineIneligible("BASELINE_CANDIDATE_TOO_LARGE")
            throw failure
        }
    }

private fun baselineIneligible(
    code: String,
    verdict: String? = null,
): Nothing =
    throw ApiFailure(
        HttpStatusCode.UnprocessableEntity,
        code,
        "Baseline candidate is unavailable: $code" + (verdict?.let { " (policy_verdict=$it)" } ?: ""),
    )

private fun JsonObject.knownVerdict(): String =
    (this["policy_verdict"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it in KNOWN_VERDICTS } ?: "UNKNOWN"

private val KNOWN_VERDICTS = setOf("PASS", "FAIL", "NO_POLICY", "NO_VERDICT")
```

В `selectBaseline`: manual-ветка

```kotlin
            val documents = store.verifiedBaselineDocuments(reference)
            baselineCandidateRejection(documents.result)?.let { baselineIneligible(it, documents.result.knownVerdict()) }
            manualBaselineSelection(series, reference)
```

statistical-ветка: `val documents = references.map { store.verifiedBaselineDocuments(it) }`; затем один и тот же цикл допуска для обоих режимов, до расчёта и до вызова ядра:

```kotlin
    documents.forEach { document ->
        baselineCandidateRejection(document.result)?.let { baselineIneligible(it, document.result.knownVerdict()) }
    }
```

после чего `statisticalBaselineSelection(series, references, documents.map { it.result }, documents.map { it.identity })`; существующий `catch (failure: IllegalArgumentException) { baselineIneligible(failure.message ?: "BASELINE_CANDIDATE_INVALID") }` остаётся для остальных кодов набора. Так сообщение `BASELINE_CANDIDATE_NOT_PASS` называет фактический verdict (проверенное значение из `KNOWN_VERDICTS`) и в statistical (ADR 0019, раздел 6). Остальные места, где вызывается `baselineDocuments` (comparison, conditions), не меняются. Тест API-уровня: statistical с кандидатом `FAIL` отвечает `422 BASELINE_CANDIDATE_NOT_PASS`, сообщение содержит `policy_verdict=FAIL`; `comparable=false` вместе с кандидатом `FAIL` отвечает `BASELINE_COMPARABILITY_UNCONFIRMED`.

Память: statistical читает до 20 результатов подряд и держит разобранные документы, как и сейчас; срез не расширяет это поведение. Риск записан в «Рисках».

- [ ] **Step 3.4:** Run: `.\gradlew.bat test --tests "io.ltverdict.web.LocalApiTest" --tests "io.ltverdict.storage.RunBundleStoreTest" --tests "io.ltverdict.core.BaselineComparisonTest"`. Expected: PASS. Commit: `feat(baseline): accept only PASS analyses as manual or statistical baseline`.

### Task 4: e2e, документация, полный прогон

- [ ] **Step 4.1: e2e** (`ui/e2e/baseline.spec.ts`). Помощник `analyze(page, name, elapsed, timestamp)` получает необязательный `policy: 'permissive' | 'failing' | null = 'permissive'`: при непустом значении перед запуском загружает политику из памяти и ждёт готовности, затем ожидает вердикт `PASS` (или `FAIL`) вместо безусловного `NO_POLICY`:

```ts
const PERMISSIVE_POLICY = JSON.stringify({ schema_version: 'policy.v1', policy_id: 'permissive', rules: [{ id: 'p95', metric: 'response_time_p95_ms', operator: 'lte', threshold: 100000, scope: { kind: 'overall' } }] })
const FAILING_POLICY = JSON.stringify({ schema_version: 'policy.v1', policy_id: 'failing', rules: [{ id: 'p95', metric: 'response_time_p95_ms', operator: 'lte', threshold: 1, scope: { kind: 'overall' } }] })

async function attachPolicy(page: Page, body: string) {
  await page.getByTestId('policy-file').setInputFiles({ name: 'policy.json', mimeType: 'application/json', buffer: Buffer.from(body) })
  await expect(page.locator('#run-setup')).toContainText('Policy is valid')
}
```

`openAnalysis` ждёт `PASS`, если анализ создан с `permissive`. Во всех сценариях со `Set as baseline` и статистическим выбором использовать `permissive` (точные строки даёт запуск `npm --prefix ui run e2e -- e2e/baseline.spec.ts` после правки ядра). Сообщение в ожидании строки 325 заменить на `'Baseline candidate is unavailable: BASELINE_MIXED_SEMANTICS'`. Новый сценарий: анализ с `failing` -> `Set as baseline` -> `role=alert` содержит `BASELINE_CANDIDATE_NOT_PASS`, блок `baseline-selection` не появился (или остался прежним).

- [ ] **Step 4.2:** Run: `.\gradlew.bat test`, затем `npm --prefix ui run typecheck`, `npm --prefix ui run lint`, `npm --prefix ui run e2e -- e2e/baseline.spec.ts`. Expected: PASS. Записать число тестов до и после.

- [ ] **Step 4.3: Документация.** `docs/user/slice-1-local-analysis.md` (раздел про baseline): эталоном становится только анализ `VALID`, `COMPLETE`, `PASS` в обоих режимах; для анализа без политики сначала нужна повторная обработка с политикой (новый `analysis_id`); коды `BASELINE_CANDIDATE_INVALID|INCOMPLETE|NOT_PASS|TOO_LARGE`; ранее сохранённый эталон не из `PASS` продолжает читаться и помечается предупреждением сравнения (после R5). `docs/architecture/slice-1-local-runtime.md` (раздел «Local baseline state и comparison»): правило допуска, чтение результата с хэшем вне замка, предел 64 MiB. `CHANGELOG.md`, `[Unreleased]`, `Changed`: одна запись. Run: `npx --yes markdownlint-cli2@0.23.2 docs/user/slice-1-local-analysis.md docs/architecture/slice-1-local-runtime.md CHANGELOG.md`.

- [ ] **Step 4.4:** Run: `.\gradlew.bat clean check installDist`, `npm --prefix ui run build`, `npm --prefix ui run test:contracts`, `git diff --check`. Commit: `docs(baseline): describe the PASS-only baseline rule`. Исход PR: `feat(baseline): accept only PASS analyses as baseline`.

**Риски R1:** (1) анализы без политики перестают быть эталоном: сознательное решение владельца 2026-10-02 (`NO_POLICY` не исключение), интерфейс до R9 показывает серверный текст; (2) тестовый хэш 64 MiB добавляет около 0,2 с в один тест; (3) память при statistical на 20 кандидатах по 32-55 МБ результата не улучшена: каждый результат разбирается целиком в `JsonObject` и все документы живут до конца запроса; отдельный срез мог бы извлекать только `metrics` и поля допуска (отметка report-only); (4) конфликт с ADR 0018 S10 в одном отборе: решён порядком R1 -> R4 -> S10.

**Documentation impact:** `docs/user/slice-1-local-analysis.md`, `docs/architecture/slice-1-local-runtime.md`, `CHANGELOG.md`.

---

## R2. Запись релиза `local-release.v1` и хранилище

**Цель:** запись релиза, её строгая проверка, идентификатор, каталог `<data>/releases/`, пределы и операции хранилища (создать, прочитать, заменить, удалить, полный список, поиск по `analysis_id`), без API.

**Ветка:** `feat/release-record-store`. **Размер:** L (около 250 строк ядра, около 300 строк хранилища, тесты вдвое больше). **Зависит от:** R0 (набор полей копий). **Identity и контракты:** identity не меняется; формат `local-release.v1` приватный, версия `1`, публичной схемы нет; каталог `<data>/releases/` создаётся при первой записи (старые версии программы его не читают и не ломаются).

**Что не входит:** HTTP-маршруты (R3), поиск релиза для comparison (используется в R5, но метод `findReleasesByAnalysis` создаётся здесь), UI, миграция, автоимпорт анализов, индекс.

**Files:**

- Create: `src/main/kotlin/io/ltverdict/core/LocalRelease.kt` (отдельный файл по прецеденту `RunPeriod.kt`: приватные форматы живут в ядре, хранилище вызывает их валидаторы)
- Modify: `src/main/kotlin/io/ltverdict/storage/RunBundleStore.kt` (методы релизов и анализа; приватные помощники файла `requireOwnedDirectory`, `writeForced`, `forceDirectory` доступны только внутри файла, поэтому методы добавляются сюда, а не в новый класс)
- Create (тесты): `src/test/kotlin/io/ltverdict/core/LocalReleaseTest.kt`
- Modify (тесты): `src/test/kotlin/io/ltverdict/storage/RunBundleStoreTest.kt`

**Interfaces:**

- Produces (`package io.ltverdict.core`, `LocalRelease.kt`):
  - константы `RELEASE_SCHEMA`, `MAX_RELEASE_ANALYSES = 8`, `MAX_RELEASE_BYTES = 8 * 1024`, `MAX_RELEASE_TEXT_BYTES = 128`, `MAX_RELEASE_NOTES_BYTES = 1024`, `RELEASE_ID` (regex), `RELEASE_PROFILE_FIELDS`, `RELEASE_DRAFT_FIELDS`, `RELEASE_IMMUTABLE_FIELDS`;
  - `normalizeReleaseText(value: String): String` (NFC, `\r\n` в `\n`, обрезка пробелов);
  - `validateRelease(record: JsonObject): JsonObject` (бросает `IllegalArgumentException("INVALID_RELEASE")`);
  - `releaseStartedAtMillis(startedAt: String): Long`, `releaseId(startedAtMillis: Long, suffix: String): String`;
  - `releaseAnalysisFacts(analysisId: String, result: JsonObject, identity: JsonObject): JsonObject`;
  - `compareReleaseProfiles(baseline: JsonObject?, current: JsonObject?): JsonObject?`, `releaseProfileSummary(profile: JsonObject?): String?`.
- Produces (`package io.ltverdict.storage`): `ReleaseCorruptName(name, reason)`, `ReleasePage`, `ReleaseLookup`, `MAX_RELEASES = 1_000` и методы `RunBundleStore.createRelease`, `readRelease`, `replaceRelease`, `deleteRelease`, `listReleases`, `findReleasesByAnalysis`, `analysisExists`, `analysisState` (сигнатуры в шагах ниже).
- Consumes: `canonicalJson`, `MAX_TIMESTAMP_EPOCH_MILLIS` (`LoadSample.kt:51`), `readVerifiedAnalysis` (R1, только в R3).

### Task 1: запись релиза (ядро)

- [ ] **Step 1.1: Красные тесты** (`LocalReleaseTest.kt`, новый файл; общий помощник записи):

```kotlin
package io.ltverdict.core

import io.ltverdict.ingest.MAX_TIMESTAMP_EPOCH_MILLIS
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class LocalReleaseTest {
    private val runId = "jmeter_jtl_csv-${"c".repeat(64)}"

    private fun analysis(
        id: Char = 'a',
        arm: String? = null,
        verdict: String = "PASS",
        reasons: List<String> = emptyList(),
    ) = buildJsonObject {
        put("analysis_id", id.toString().repeat(64))
        put("arm", arm?.let(::JsonPrimitive) ?: JsonNull)
        put("coverage_reasons", buildJsonArray { reasons.forEach { add(JsonPrimitive(it)) } })
        put("coverage_status", if (reasons.isEmpty()) "COMPLETE" else "INCOMPLETE")
        put("policy_sha256", "b".repeat(64))
        put("policy_verdict", verdict)
        put("run_validity", "VALID")
    }

    private fun release(
        analyses: List<JsonObject> = listOf(analysis()),
        profile: JsonElement = JsonNull,
        notes: JsonElement = JsonNull,
        label: String = "1.2.3",
        series: String = "checkout",
    ) = buildJsonObject {
        put("schema_version", "local-release.v1")
        put("release_id", "001767225600000-0123abcd")
        put("series", series)
        put("label", label)
        put("run_id", runId)
        put("started_at", "2026-01-01T00:00:00Z")
        put("analyses", JsonArray(analyses))
        put("profile", profile)
        put("notes", notes)
        put("created_at", "2026-01-02T03:04:05.006Z")
        put("updated_at", "2026-01-02T03:04:05.006Z")
    }

    @Test
    fun `canonical record bytes are pinned`() {
        val expected =
            """{"analyses":[{"analysis_id":"${"a".repeat(64)}","arm":null,"coverage_reasons":[],"coverage_status":"COMPLETE",""" +
                """"policy_sha256":"${"b".repeat(64)}","policy_verdict":"PASS","run_validity":"VALID"}],"created_at":"2026-01-02T03:04:05.006Z",""" +
                """"label":"1.2.3","notes":null,"profile":null,"release_id":"001767225600000-0123abcd","run_id":"$runId",""" +
                """"schema_version":"local-release.v1","series":"checkout","started_at":"2026-01-01T00:00:00Z","updated_at":"2026-01-02T03:04:05.006Z"}"""
        assertArrayEquals(expected.encodeToByteArray(), canonicalJson(validateRelease(release())))
    }

    @Test
    fun `release id carries the test start and orders chronologically`() {
        assertEquals("000000000000000-00000000", releaseId(0, "00000000"))
        assertEquals("001767225600000-0123abcd", releaseId(releaseStartedAtMillis("2026-01-01T00:00:00Z"), "0123abcd"))
        assertEquals("253402300799999-ffffffff", releaseId(MAX_TIMESTAMP_EPOCH_MILLIS, "ffffffff"))
        assertEquals(1_767_225_600_500L, releaseStartedAtMillis("2026-01-01T00:00:00.500Z"))
        listOf("2026-01-01T00:00:00", "garbage", "1969-12-31T23:59:59Z", "+99999-01-01T00:00:00Z", "").forEach {
            assertThrows(IllegalArgumentException::class.java) { releaseStartedAtMillis(it) }
        }
        assertThrows(IllegalArgumentException::class.java) { releaseId(0, "XYZ") }
        assertThrows(IllegalArgumentException::class.java) { releaseId(-1, "00000000") }
    }
```

Остальные тесты файла (каждый фиксирует одну границу, все через `assertThrows(IllegalArgumentException::class.java) { validateRelease(...) }` и сообщение `INVALID_RELEASE`):

1. `validator rejects every structural defect`: лишний ключ; другая `schema_version`; `release_id` не по маске; метка времени в `release_id` не равна `started_at`; пустой `analyses`; девять анализов; повторный `analysis_id`; два анализа без плеча; анализ без плеча вместе с анализом с плечом; повторное плечо; `policy_verdict = "MAYBE"`; `coverage_status = "PARTIAL"`; `policy_sha256` не 64 hex и не `NO_POLICY`; 17 причин покрытия; `updated_at` раньше `created_at`; `profile = {}`; профиль с неизвестным ключом; профиль из шести `null` (нормализованная форма запрещена в записи); профиль со строкой `""`.
2. `validator accepts the documented boundaries`: `label`, `series`, поле профиля по 128 байт UTF-8, собранные из двухбайтных символов (64 символа `я`); `notes` 1 024 байта с переводами строк; ровно `MAX_RELEASE_ANALYSES` анализов с разными плечами; профиль с одним непустым полем.
3. `validator rejects text that is not in its normalized form`: `label` с хвостовым пробелом, с разложенной `ӗ` вместо `ё`... (любой NFD), с символом `\u0007`; `notes` с `\r\n`; `series` из 129 байт.
4. `normalization makes composed and decomposed profile values equal`: `normalizeReleaseText("é") == "é"`, `compareReleaseProfiles` от двух профилей, различающихся только формой нормализации после `normalizeReleaseText`, даёт `MATCH`.
5. `profile comparison`: любой из двух `null` даёт `null`; равные профили `{"status":"MATCH","differing_fields":[]}`; различие в `pacing` и `scenario_mix` даёт `MISMATCH` и `differing_fields = ["scenario_mix","pacing"]` (порядок полей фиксирован `RELEASE_PROFILE_FIELDS`); `notes` в сравнении не участвуют; `releaseProfileSummary` выводит только непустые поля как `name=value`, через точку с запятой и пробел, в фиксированном порядке, `null` для `null`.
6. `facts are copied from the result and identity documents`: `releaseAnalysisFacts` из результата с `analysis_coverage = {"status":"INCOMPLETE","reasons":["SMALL_SAMPLE"]}` и identity с `resource_arm = "blue"` возвращает те же значения; без `resource_arm` плечо `null`; отсутствие `policy_verdict`, `analysis_coverage` или `reasons`, `resource_arm` не строкой даёт `IllegalArgumentException`.
7. `release text normalization` не меняет ASCII.

- [ ] **Step 1.2:** Run: `.\gradlew.bat test --tests "io.ltverdict.core.LocalReleaseTest"`. Expected: FAIL (компиляция: файла нет).

- [ ] **Step 1.3: Реализация** (`src/main/kotlin/io/ltverdict/core/LocalRelease.kt`):

```kotlin
package io.ltverdict.core

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import io.ltverdict.ingest.MAX_TIMESTAMP_EPOCH_MILLIS
import java.text.Normalizer
import java.time.Instant
import java.time.format.DateTimeParseException

internal const val RELEASE_SCHEMA = "local-release.v1"
internal const val MAX_RELEASE_ANALYSES = 8
internal const val MAX_RELEASE_BYTES = 8 * 1024
internal const val MAX_RELEASE_TEXT_BYTES = 128
internal const val MAX_RELEASE_NOTES_BYTES = 1024
private const val MAX_RELEASE_REASONS = 16
private const val MAX_RELEASE_REASON_BYTES = 64
internal val RELEASE_ID = Regex("[0-9]{15}-[0-9a-f]{8}")
private val RELEASE_RUN_ID = Regex("(?:jmeter_jtl_csv|jmeter_jtl_xml|gatling_text|gatling_binary)-[0-9a-f]{64}")
private val RELEASE_SHA256 = Regex("[0-9a-f]{64}")
internal val RELEASE_PROFILE_FIELDS =
    listOf("scenario_mix", "environment_dataset", "load_model", "targets_stages", "pacing", "generator_limits")
private val RELEASE_FIELDS =
    setOf("schema_version", "release_id", "series", "label", "run_id", "started_at", "analyses", "profile", "notes", "created_at", "updated_at")
internal val RELEASE_DRAFT_FIELDS = RELEASE_FIELDS - setOf("release_id", "created_at", "updated_at")
internal val RELEASE_IMMUTABLE_FIELDS = listOf("schema_version", "release_id", "series", "run_id", "started_at", "created_at")
private val RELEASE_ANALYSIS_FIELDS =
    setOf("analysis_id", "arm", "coverage_reasons", "coverage_status", "policy_sha256", "policy_verdict", "run_validity")
private val RELEASE_VERDICTS = setOf("PASS", "FAIL", "NO_POLICY", "NO_VERDICT")
private val RELEASE_VALIDITIES = setOf("VALID", "DEGRADED", "INVALID")
private val RELEASE_COVERAGE = setOf("COMPLETE", "INCOMPLETE")

internal fun normalizeReleaseText(value: String): String =
    Normalizer.normalize(value, Normalizer.Form.NFC).replace("\r\n", "\n").trim()

private fun invalid(): Nothing = throw IllegalArgumentException("INVALID_RELEASE")

private fun checkedText(
    value: String,
    maxBytes: Int,
    multiline: Boolean,
): String {
    if (value.isEmpty() || value != normalizeReleaseText(value) || value.encodeToByteArray().size > maxBytes) invalid()
    if (value.any { it.isISOControl() && !(multiline && it == '\n') }) invalid()
    return value
}

private fun JsonObject.text(
    name: String,
    maxBytes: Int = MAX_RELEASE_TEXT_BYTES,
    multiline: Boolean = false,
): String {
    val primitive = this[name] as? JsonPrimitive ?: invalid()
    if (!primitive.isString) invalid()
    return checkedText(primitive.content, maxBytes, multiline)
}

private fun JsonObject.textOrNull(
    name: String,
    maxBytes: Int,
    multiline: Boolean = false,
): String? =
    when (val value = this[name]) {
        null -> invalid()
        JsonNull -> null
        is JsonPrimitive -> if (value.isString) checkedText(value.content, maxBytes, multiline) else invalid()
        else -> invalid()
    }

internal fun releaseStartedAtMillis(startedAt: String): Long {
    val instant =
        try {
            Instant.parse(startedAt)
        } catch (_: DateTimeParseException) {
            invalid()
        }
    val millis =
        try {
            instant.toEpochMilli()
        } catch (_: ArithmeticException) {
            invalid()
        }
    if (millis !in 0..MAX_TIMESTAMP_EPOCH_MILLIS) invalid()
    return millis
}

internal fun releaseId(
    startedAtMillis: Long,
    suffix: String,
): String {
    require(startedAtMillis in 0..MAX_TIMESTAMP_EPOCH_MILLIS && Regex("[0-9a-f]{8}").matches(suffix)) { "INVALID_RELEASE" }
    return startedAtMillis.toString().padStart(15, '0') + "-" + suffix
}

internal fun validateRelease(record: JsonObject): JsonObject {
    if (record.keys != RELEASE_FIELDS) invalid()
    if (record.text("schema_version", 32) != RELEASE_SCHEMA) invalid()
    val releaseId = record.text("release_id", 24)
    if (!RELEASE_ID.matches(releaseId)) invalid()
    record.text("series")
    record.text("label")
    if (!RELEASE_RUN_ID.matches(record.text("run_id", 160))) invalid()
    val millis = releaseStartedAtMillis(record.text("started_at", 40))
    if (!releaseId.startsWith(millis.toString().padStart(15, '0') + "-")) invalid()
    val analyses = record["analyses"] as? JsonArray ?: invalid()
    if (analyses.size !in 1..MAX_RELEASE_ANALYSES) invalid()
    val items = analyses.map { validateReleaseAnalysis(it as? JsonObject ?: invalid()) }
    if (items.map { it.text("analysis_id", 64) }.toSet().size != items.size) invalid()
    val arms = items.map { it.textOrNull("arm", MAX_RELEASE_TEXT_BYTES) }
    if (items.size > 1 && (arms.any { it == null } || arms.toSet().size != arms.size)) invalid()
    validateReleaseProfile(record["profile"] ?: invalid())
    record.textOrNull("notes", MAX_RELEASE_NOTES_BYTES, multiline = true)
    val created = releaseInstant(record.text("created_at", 40))
    if (releaseInstant(record.text("updated_at", 40)) < created) invalid()
    return record
}

private fun releaseInstant(value: String): Instant =
    try {
        Instant.parse(value)
    } catch (_: DateTimeParseException) {
        invalid()
    }

private fun validateReleaseAnalysis(item: JsonObject): JsonObject {
    if (item.keys != RELEASE_ANALYSIS_FIELDS) invalid()
    if (!RELEASE_SHA256.matches(item.text("analysis_id", 64))) invalid()
    item.textOrNull("arm", MAX_RELEASE_TEXT_BYTES)
    if (item.text("policy_verdict", 16) !in RELEASE_VERDICTS) invalid()
    if (item.text("run_validity", 16) !in RELEASE_VALIDITIES) invalid()
    if (item.text("coverage_status", 16) !in RELEASE_COVERAGE) invalid()
    val reasons = item["coverage_reasons"] as? JsonArray ?: invalid()
    if (reasons.size > MAX_RELEASE_REASONS) invalid()
    reasons.forEach { value ->
        val primitive = value as? JsonPrimitive ?: invalid()
        if (!primitive.isString) invalid()
        checkedText(primitive.content, MAX_RELEASE_REASON_BYTES, multiline = false)
    }
    val policy = item.text("policy_sha256", 64)
    if (policy != "NO_POLICY" && !RELEASE_SHA256.matches(policy)) invalid()
    return item
}

private fun validateReleaseProfile(profile: JsonElement) {
    if (profile == JsonNull) return
    val fields = profile as? JsonObject ?: invalid()
    if (fields.keys != RELEASE_PROFILE_FIELDS.toSet()) invalid()
    if (RELEASE_PROFILE_FIELDS.map { fields.textOrNull(it, MAX_RELEASE_TEXT_BYTES) }.all { it == null }) invalid()
}

internal fun releaseAnalysisFacts(
    analysisId: String,
    result: JsonObject,
    identity: JsonObject,
): JsonObject {
    val coverage = result["analysis_coverage"] as? JsonObject ?: invalid()
    val arm =
        when (val value = identity["resource_arm"]) {
            null -> JsonNull
            is JsonPrimitive -> if (value.isString) value else invalid()
            else -> invalid()
        }
    return validateReleaseAnalysis(
        buildJsonObject {
            put("analysis_id", analysisId)
            put("arm", arm)
            put("coverage_reasons", coverage["reasons"] ?: invalid())
            put("coverage_status", coverage["status"] ?: invalid())
            put("policy_sha256", identity["policy_sha256"] ?: invalid())
            put("policy_verdict", result["policy_verdict"] ?: invalid())
            put("run_validity", result["run_validity"] ?: invalid())
        },
    )
}

internal fun compareReleaseProfiles(
    baseline: JsonObject?,
    current: JsonObject?,
): JsonObject? {
    if (baseline == null || current == null) return null
    val differing = RELEASE_PROFILE_FIELDS.filter { baseline[it] != current[it] }
    return buildJsonObject {
        put("status", if (differing.isEmpty()) "MATCH" else "MISMATCH")
        put("differing_fields", buildJsonArray { differing.forEach { add(JsonPrimitive(it)) } })
    }
}

internal fun releaseProfileSummary(profile: JsonObject?): String? =
    profile?.let {
        RELEASE_PROFILE_FIELDS
            .mapNotNull { name -> (profile[name] as? JsonPrimitive)?.takeIf { value -> value.isString }?.let { "$name=${it.content}" } }
            .joinToString("; ")
    }
```

`compareReleaseProfiles` сравнивает значения как записаны: нормализация выполнена на границе API до записи, поэтому в хранимых профилях равные утверждения побайтно равны. Объект из шести `null` валидатор запрещает, значит «два пустых» профиля существуют только как `profile: null`.

- [ ] **Step 1.4:** Run: `.\gradlew.bat test --tests "io.ltverdict.core.LocalReleaseTest"` и `.\gradlew.bat ktlintCheck` (ktlint входит в `check`). Expected: PASS. Commit: `feat(core): add the local-release.v1 record and its validation`.

### Task 2: хранилище релизов

- [ ] **Step 2.1: Красные тесты** (`RunBundleStoreTest.kt`; помощник `saveAnalysis` из R1; здесь же помощники записи и засева):

```kotlin
    private fun releaseDraft(
        runId: String,
        analysisIds: List<String>,
        series: String = "checkout",
        label: String = "1.0.0",
        startedAt: String = "2026-01-01T00:00:00Z",
        arms: List<String?> = analysisIds.map { null },
    ): JsonObject =
        buildJsonObject {
            put("schema_version", "local-release.v1")
            put("series", series)
            put("label", label)
            put("run_id", runId)
            put("started_at", startedAt)
            put(
                "analyses",
                kotlinx.serialization.json.JsonArray(
                    analysisIds.mapIndexed { index, id ->
                        buildJsonObject {
                            put("analysis_id", id)
                            put("arm", arms[index]?.let(::JsonPrimitive) ?: JsonNull)
                            put("coverage_reasons", kotlinx.serialization.json.JsonArray(emptyList()))
                            put("coverage_status", "COMPLETE")
                            put("policy_sha256", "a".repeat(64))
                            put("policy_verdict", "PASS")
                            put("run_validity", "VALID")
                        }
                    },
                ),
            )
            put("profile", JsonNull)
            put("notes", JsonNull)
        }

    /** Writes valid canonical records straight into the registry directory, bypassing the store. */
    private fun seedReleases(
        root: Path,
        count: Int,
        firstStamp: Long = 1_767_225_600_000L,
    ) {
        val directory = Files.createDirectories(root.resolve("releases"))
        repeat(count) { index ->
            val millis = firstStamp + index
            val id = releaseId(millis, "%08x".format(index))
            val analysis = "%064x".format(index + 1)
            val record =
                JsonObject(
                    releaseDraft(SEED_RUN_ID, listOf(analysis), startedAt = Instant.ofEpochMilli(millis).toString()) +
                        mapOf(
                            "release_id" to JsonPrimitive(id),
                            "created_at" to JsonPrimitive("2026-01-02T00:00:00Z"),
                            "updated_at" to JsonPrimitive("2026-01-02T00:00:00Z"),
                        ),
                )
            Files.write(directory.resolve("$id.json"), canonicalJson(validateRelease(record)))
        }
    }
```

(`SEED_RUN_ID` - константа `"jmeter_jtl_csv-" + "0".repeat(64)` в `companion object` теста; засев записывает записи без реальных анализов: хранилище релизов существование анализов не проверяет, это делает API.) Тесты:

```kotlin
    @Test
    fun `create writes one canonical record outside the bundle and survives reopen`() {
        val root = tempDir.resolve("release-create")
        val created =
            DataDirectory.open(root).use { directory ->
                val store = RunBundleStore(directory)
                val input = store.acceptInput(Files.newInputStream(Path.of(CSV_FIXTURE)), "r.jtl")
                val analysisId = saveAnalysis(store, input, "a")
                val now = Instant.parse("2026-01-02T03:04:05.006789Z")
                val record = store.createRelease(releaseDraft(input.runId, listOf(analysisId)), now) { "0123abcd" }

                assertEquals("001767225600000-0123abcd", (record["release_id"] as JsonPrimitive).content)
                assertEquals("2026-01-02T03:04:05.006Z", (record["created_at"] as JsonPrimitive).content)
                val path = root.resolve("releases/001767225600000-0123abcd.json")
                assertArrayEquals(canonicalJson(record), Files.readAllBytes(path))
                assertStagingEmpty(root)
                // the analysis bundle is untouched: exactly the files the writer put there plus its manifest
                Files.list(root.resolve("runs/${input.runId}/analyses/$analysisId")).use { assertEquals(4L, it.count()) }
                record
            }
        DataDirectory.open(root).use { directory ->
            assertEquals(created, RunBundleStore(directory).readRelease("001767225600000-0123abcd"))
        }
    }

    @Test
    fun `one analysis belongs to at most one release and a replacement excludes its own record`() =
        withStore { store, root ->
            val input = store.acceptInput(Files.newInputStream(Path.of(CSV_FIXTURE)), "u.jtl")
            val first = saveAnalysis(store, input, "1")
            val second = saveAnalysis(store, input, "2")
            val now = Instant.parse("2026-01-02T00:00:00Z")
            val a = store.createRelease(releaseDraft(input.runId, listOf(first)), now) { "00000001" }
            store.createRelease(releaseDraft(input.runId, listOf(second), label = "other"), now) { "00000002" }
            val failure =
                assertThrows(IllegalArgumentException::class.java) {
                    store.createRelease(releaseDraft(input.runId, listOf(first), label = "dup"), now) { "00000003" }
                }
            assertEquals("RELEASE_ANALYSIS_ALREADY_REGISTERED", failure.message)
            assertEquals(2, store.listReleases(null, null, 100).releases.size)

            // the record's own analysis is excluded from the uniqueness check: renaming passes
            val id = (a["release_id"] as JsonPrimitive).content
            store.replaceRelease(id) { JsonObject(it + ("label" to JsonPrimitive("renamed"))) }
            // taking the analysis of another release is refused and leaves the file byte-identical
            val path = root.resolve("releases/$id.json")
            val before = Files.readAllBytes(path)
            val refused =
                assertThrows(IllegalArgumentException::class.java) {
                    store.replaceRelease(id) { current ->
                        JsonObject(current + ("analyses" to releaseDraft(input.runId, listOf(second)).getValue("analyses")))
                    }
                }
            assertEquals("RELEASE_ANALYSIS_ALREADY_REGISTERED", refused.message)
            assertArrayEquals(before, Files.readAllBytes(path))
            assertStagingEmpty(root)
        }
```

Остальные тесты хранилища (каждый с конкретными входами и ожиданиями):

1. `concurrent creates of the same analysis produce exactly one record`: два потока, `CyclicBarrier(2)`, один `analysisId`; исходы `{успех, IAE RELEASE_ANALYSIS_ALREADY_REGISTERED}`; в каталоге один файл. Повторить в цикле 20 раз на разных `analysisId` (нет флаки по барьеру).
2. `registry limit counts every directory entry and never truncates`: `seedReleases(root, MAX_RELEASES - 1)`; `createRelease` проходит (теперь `MAX_RELEASES`); следующий даёт IAE `RELEASE_LIMIT_REACHED`; запись постороннего файла `x.txt` делает `listReleases` и `createRelease` ISE с префиксом `CORRUPT_RELEASE_REGISTRY` (1 001 элемент), и ни одна запись не усечена; удаление постороннего файла возвращает `MAX_RELEASES` корректных записей. Граница параметризована константой.
3. `list is newest first with an exclusive cursor and complete series summary`: 5 записей с разными `started_at`, две серии; `limit = 2` даёт страницы по убыванию `release_id`, `next_after` равен последнему выданному, последняя страница `next_after == null`; `after` с идентификатором, не входящим в каталог, работает по сравнению строк; `series_summary` считает все записи независимо от фильтра `series` и страницы; фильтр по серии возвращает только её записи.
4. `list reports unreadable entries without hiding the valid ones`: рядом с двумя корректными записями лежат: файл с мусором JSON, запись с `schema_version = "local-release.v2"` (причина `UNSUPPORTED_VERSION`), файл неверного имени, файл на 8 KiB + 1 байт (`TOO_LARGE`), запись, у которой `release_id` не равен имени, `symlink` (тест пропускается через `Assumptions.assumeTrue`, если символическая ссылка не создаётся, как в `DataDirectoryTest.kt:38`): `corruptCount` равен числу таких элементов, `corruptNames` не длиннее 20, причины совпадают, корректные записи возвращены. 25 повреждённых файлов дают `corruptCount = 25` и 20 имён.
5. `read of a corrupt record fails closed and delete removes it without parsing`: `readRelease` повреждённой даёт ISE `CORRUPT_RELEASE`; `deleteRelease` удаляет файл (`true`), повторный даёт `false`, неизвестный идентификатор `false`, небезопасный (`../x`, пустой, `0` x 15 без суффикса) даёт IAE `INVALID_RELEASE_ID` и ничего не удаляет.
6. `replace keeps immutable fields and refuses an oversize record`: попытка изменить `series`, `run_id`, `started_at`, `created_at`, `release_id` внутри лямбды даёт IAE `INVALID_RELEASE` и файл не меняется; запись из восьми анализов с плечами по 128 символов `"` (каждое экранируется до 256 байт), заметкой из 1 024 символов `"`, профилем из шести полей по 128 символов `"`, `series` и `label` по 128 символов `"` превышает 8 KiB в каноническом виде (тест сначала утверждает `canonicalJson(record).size > MAX_RELEASE_BYTES`, расчёт около 8,7 KiB) и даёт IAE `RELEASE_TOO_LARGE`, старый файл побайтно цел; замена несуществующей даёт `NoSuchElementException`; staging пуст после каждого исхода.
7. `find by analysis resolves unique ids and reports duplicates as ambiguous`: две записи с разными анализами находятся по обоим идентификаторам; вручную скопированный файл (тот же `analysis_id` в двух корректных записях) попадает в `ambiguous`, в `byAnalysis` отсутствует; неизвестный id отсутствует в обоих.
8. `release id retries the suffix and gives up after eight collisions`: `suffix`, возвращающий один и тот же текст, при уже занятом имени вызывается ровно восемь раз, затем ISE `RELEASE_ID_COLLISION`; `suffix`, дающий второй раз другое значение, проходит.
9. `registry scan stays fast for a full directory`: `seedReleases(root, MAX_RELEASES)` и замер `listReleases(null, null, 100)` и `findReleasesByAnalysis`: утверждение `< 5 с` (грубая проверка зависания, не оценка), фактическое время выводится в вывод теста и переносится в описание PR (ADR 0019 требует замера на 1 000 записях).
10. `model-based registry test`: 200 случайных операций (`Random(42)`) `create`/`replace`/`delete`/`list` над восемью `analysis_id` и тремя сериями сверяются с моделью в памяти (`Map<releaseId, record>`): после каждой операции `listReleases` постранично равен отсортированной по убыванию модели, `series_summary` равен подсчёту по модели, ни один `analysis_id` не встречается дважды, число записей не больше `MAX_RELEASES`; отказы хранилища (`RELEASE_ANALYSIS_ALREADY_REGISTERED`, `RELEASE_NOT_FOUND`) совпадают с отказами модели.
11. `concurrent replacement is detected`: лямбда `update` первой замены сама вызывает вторую замену того же файла (она выполняется вне замка, вызов допустим); первая замена получает IAE `RELEASE_CHANGED`, файл содержит результат второй, staging пуст; `update`, бросающая исключение, оставляет файл побайтно цельным.
12. `an unreadable entry is one damaged element`: в каталоге записей лежат корректная запись и элемент, чтение которого даёт `IOException` (на POSIX каталог с именем `<id>.json`, на Windows файл, открытый другим потоком с эксклюзивным доступом, если ОС это допускает; иначе тест пропускается `Assumptions`): `listReleases` возвращает корректную запись и учитывает элемент в `corruptCount`, а не падает.
13. `analysis state`: `analysisExists` для сохранённого анализа `true`, для неизвестного идентификатора `false`, для анализа, чей каталог (`runs/<run>`, `analyses`, `analyses/<id>`) заменён символической ссылкой (тест пропускается без привилегий), `false`; `analysisState` даёт `OK`, `MISSING` (нет анализа и нет запуска), `CORRUPT` (подмена размера артефакта).

- [ ] **Step 2.2:** Run: `.\gradlew.bat test --tests "io.ltverdict.storage.RunBundleStoreTest"`. Expected: FAIL (методов нет).

- [ ] **Step 2.3: Реализация** (`RunBundleStore.kt`). Новые типы рядом с остальными (`internal data class`):

```kotlin
internal data class ReleaseCorruptName(val name: String, val reason: String)

internal data class ReleasePage(
    val releases: List<JsonObject>,
    val nextAfter: String?,
    val seriesSummary: List<Pair<String, Int>>,
    val corruptCount: Int,
    val corruptNames: List<ReleaseCorruptName>,
)

internal data class ReleaseLookup(
    val byAnalysis: Map<String, JsonObject>,
    val ambiguous: Set<String>,
)
```

Методы класса:

```kotlin
    fun createRelease(
        draft: JsonObject,
        now: Instant,
        suffix: () -> String = ::randomReleaseSuffix,
    ): JsonObject {
        require(draft.keys == RELEASE_DRAFT_FIELDS) { "INVALID_RELEASE" }
        val millis = releaseStartedAtMillis(draft.string("started_at"))
        val stamp = JsonPrimitive(now.truncatedTo(ChronoUnit.MILLIS).toString())
        val registered = releaseAnalysisIds(draft)
        repeat(RELEASE_ID_ATTEMPTS) {
            val id = releaseId(millis, suffix())
            val record = JsonObject(draft + mapOf("release_id" to JsonPrimitive(id), "created_at" to stamp, "updated_at" to stamp))
            // validation, canonical bytes, the size check and the forced staged write run outside the mutex
            // (ADR 0002, addendum 2026-10-01); the mutex keeps the checks and the publication only
            val prepared = prepareRelease(record)
            try {
                val published =
                    synchronized(dataDirectory.operationLock) {
                        dataDirectory.requireOpen()
                        val directory = ensureReleasesDirectory()
                        val scan = scanReleasesUnlocked()
                        if (scan.total >= MAX_RELEASES) throw IllegalArgumentException("RELEASE_LIMIT_REACHED")
                        if (scan.valid.any { existing -> releaseAnalysisIds(existing).any(registered::contains) }) {
                            throw IllegalArgumentException("RELEASE_ANALYSIS_ALREADY_REGISTERED")
                        }
                        val target = directory.resolve("$id.json")
                        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                            false
                        } else {
                            Files.move(prepared.staged, target, StandardCopyOption.ATOMIC_MOVE)
                            forceDirectory(directory)
                            true
                        }
                    }
                if (published) return prepared.record
            } finally {
                prepared.discard()
            }
        }
        throw IllegalStateException("RELEASE_ID_COLLISION")
    }

    fun readRelease(releaseId: String): JsonObject? =
        synchronized(dataDirectory.operationLock) {
            dataDirectory.requireOpen()
            requireReleaseId(releaseId)
            val target = releaseTargetOrNull(releaseId) ?: return@synchronized null
            when (val entry = readReleaseEntry(target)) {
                is ReleaseEntry.Valid -> entry.record
                is ReleaseEntry.Rejected -> corruptRelease(entry.reason)
            }
        }

    fun replaceRelease(
        releaseId: String,
        update: (JsonObject) -> JsonObject,
    ): JsonObject {
        requireReleaseId(releaseId)
        val (existing, existingBytes) =
            synchronized(dataDirectory.operationLock) {
                dataDirectory.requireOpen()
                val target = releaseTargetOrNull(releaseId) ?: throw NoSuchElementException("RELEASE_NOT_FOUND")
                val entry = readReleaseEntry(target) as? ReleaseEntry.Valid ?: corruptRelease("record cannot be replaced")
                entry.record to Files.readAllBytes(target)
            }
        val next = update(existing)
        if (RELEASE_IMMUTABLE_FIELDS.any { next[it] != existing[it] }) throw IllegalArgumentException("INVALID_RELEASE")
        val prepared = prepareRelease(next)
        try {
            synchronized(dataDirectory.operationLock) {
                dataDirectory.requireOpen()
                val target = releaseTargetOrNull(releaseId) ?: throw NoSuchElementException("RELEASE_NOT_FOUND")
                // optimistic check: the record was neither replaced nor removed while the new bytes were written
                if (!Files.readAllBytes(target).contentEquals(existingBytes)) throw IllegalArgumentException("RELEASE_CHANGED")
                val registered = releaseAnalysisIds(next)
                val others = scanReleasesUnlocked().valid.filter { (it["release_id"] as JsonPrimitive).content != releaseId }
                if (others.any { other -> releaseAnalysisIds(other).any(registered::contains) }) {
                    throw IllegalArgumentException("RELEASE_ANALYSIS_ALREADY_REGISTERED")
                }
                Files.move(prepared.staged, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                forceDirectory(target.parent)
            }
            return prepared.record
        } finally {
            prepared.discard()
        }
    }

    fun deleteRelease(releaseId: String): Boolean =
        synchronized(dataDirectory.operationLock) {
            dataDirectory.requireOpen()
            requireReleaseId(releaseId)
            val directory = dataDirectory.root.resolve(RELEASES_DIRECTORY)
            if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return@synchronized false
            requireReleasesDirectory(directory)
            val target = directory.resolve("$releaseId.json")
            if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return@synchronized false
            if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(target)) {
                corruptReleaseRegistry("unsafe entry $releaseId")
            }
            Files.delete(target)
            forceDirectory(directory)
            true
        }

    fun listReleases(
        series: String?,
        afterReleaseId: String?,
        limit: Int,
    ): ReleasePage =
        synchronized(dataDirectory.operationLock) {
            dataDirectory.requireOpen()
            require(limit in 1..100) { "INVALID_PAGE_LIMIT" }
            if (afterReleaseId != null) requireReleaseId(afterReleaseId)
            val scan = scanReleasesUnlocked()
            val summary =
                scan.valid.groupingBy { (it["series"] as JsonPrimitive).content }.eachCount().toList().sortedBy { it.first }
            val selected =
                scan.valid
                    .filter { series == null || (it["series"] as JsonPrimitive).content == series }
                    .filter { afterReleaseId == null || (it["release_id"] as JsonPrimitive).content < afterReleaseId }
            val page = selected.take(limit)
            ReleasePage(
                page,
                if (selected.size > limit) (page.last()["release_id"] as JsonPrimitive).content else null,
                summary,
                scan.corrupt.size,
                scan.corrupt.take(20),
            )
        }

    fun findReleasesByAnalysis(analysisIds: Set<String>): ReleaseLookup =
        synchronized(dataDirectory.operationLock) {
            dataDirectory.requireOpen()
            val found = mutableMapOf<String, MutableList<JsonObject>>()
            scanReleasesUnlocked().valid.forEach { record ->
                releaseAnalysisIds(record).filter { it in analysisIds }.forEach { found.getOrPut(it) { mutableListOf() } += record }
            }
            ReleaseLookup(
                found.filterValues { it.size == 1 }.mapValues { it.value.single() },
                found.filterValues { it.size > 1 }.keys,
            )
        }

    fun analysisExists(
        runId: String,
        analysisId: String,
    ): Boolean =
        synchronized(dataDirectory.operationLock) {
            dataDirectory.requireOpen()
            requireRunId(runId)
            requireAnalysisId(analysisId)
            val run = dataDirectory.runs.resolve(runId)
            val analyses = run.resolve("analyses")
            val analysis = analyses.resolve(analysisId)
            // every ancestor is checked without following links: a link must not turn a copy into an "OK" state
            listOf(run, analyses, analysis).all { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) } &&
                Files.isRegularFile(analysis.resolve("manifest.json"), LinkOption.NOFOLLOW_LINKS)
        }

    fun analysisState(runId: String, analysisId: String): String =
        try {
            if (readAnalysis(runId, analysisId) != null) "OK" else "MISSING"
        } catch (_: NoSuchElementException) {
            "MISSING"
        } catch (_: IllegalStateException) {
            "CORRUPT"
        }
```

Приватные части (в классе и в конце файла):

```kotlin
    private sealed interface ReleaseEntry {
        data class Valid(val record: JsonObject) : ReleaseEntry
        data class Rejected(val reason: String) : ReleaseEntry
    }

    private data class ReleaseScan(val valid: List<JsonObject>, val corrupt: List<ReleaseCorruptName>, val total: Int)

    private fun ensureReleasesDirectory(): Path {
        val target = dataDirectory.root.resolve(RELEASES_DIRECTORY)
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return requireReleasesDirectory(target)
        Files.createDirectory(target)
        forceDirectory(dataDirectory.root)
        return target
    }

    private fun scanReleasesUnlocked(): ReleaseScan {
        val directory = dataDirectory.root.resolve(RELEASES_DIRECTORY)
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return ReleaseScan(emptyList(), emptyList(), 0)
        requireReleasesDirectory(directory)
        val valid = mutableListOf<JsonObject>()
        val corrupt = mutableListOf<ReleaseCorruptName>()
        var total = 0
        Files.newDirectoryStream(directory).use { entries ->
            for (entry in entries) {
                // Every directory element counts, damaged or foreign ones included; nothing is truncated silently.
                if (++total > MAX_RELEASES) corruptReleaseRegistry("more than $MAX_RELEASES entries")
                when (val outcome = readReleaseEntry(entry)) {
                    is ReleaseEntry.Valid -> valid += outcome.record
                    is ReleaseEntry.Rejected -> corrupt += ReleaseCorruptName(entry.fileName.toString().take(128), outcome.reason)
                }
            }
        }
        return ReleaseScan(
            valid.sortedByDescending { (it["release_id"] as JsonPrimitive).content },
            corrupt.sortedBy { it.name },
            total,
        )
    }

    private fun readReleaseEntry(entry: Path): ReleaseEntry {
        val name = entry.fileName.toString()
        if (!RELEASE_FILE.matches(name) ||
            Files.isSymbolicLink(entry) ||
            !Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)
        ) {
            return ReleaseEntry.Rejected("UNSAFE_ENTRY")
        }
        // an unreadable or concurrently replaced element is one damaged entry, never a failure of the whole listing
        val bytes =
            try {
                if (Files.size(entry) > MAX_RELEASE_BYTES) return ReleaseEntry.Rejected("TOO_LARGE")
                Files.newInputStream(entry, LinkOption.NOFOLLOW_LINKS).use { it.readNBytes(MAX_RELEASE_BYTES + 1) }
            } catch (_: IOException) {
                return ReleaseEntry.Rejected("UNSAFE_ENTRY")
            }
        if (bytes.size > MAX_RELEASE_BYTES) return ReleaseEntry.Rejected("TOO_LARGE")
        val document =
            try {
                Json.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true)).jsonObject
            } catch (_: Exception) {
                return ReleaseEntry.Rejected("CORRUPT")
            }
        val version = (document["schema_version"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (version != null && version != RELEASE_SCHEMA) return ReleaseEntry.Rejected("UNSUPPORTED_VERSION")
        val validated =
            try {
                validateRelease(document)
            } catch (_: RuntimeException) {
                return ReleaseEntry.Rejected("CORRUPT")
            }
        if (!bytes.contentEquals(canonicalJson(validated)) || "${(validated["release_id"] as JsonPrimitive).content}.json" != name) {
            return ReleaseEntry.Rejected("CORRUPT")
        }
        return ReleaseEntry.Valid(validated)
    }

    private class PreparedRelease(
        val record: JsonObject,
        val staging: Path,
        val staged: Path,
    ) {
        fun discard() = DataDirectory.deleteTree(staging)
    }

    private fun prepareRelease(record: JsonObject): PreparedRelease {
        val validated = validateRelease(record)
        val bytes = canonicalJson(validated)
        if (bytes.size > MAX_RELEASE_BYTES) throw IllegalArgumentException("RELEASE_TOO_LARGE")
        val staging =
            synchronized(dataDirectory.operationLock) {
                dataDirectory.requireOpen()
                requireOwnedDirectory(dataDirectory.staging)
                Files.createDirectory(dataDirectory.staging.resolve(UUID.randomUUID().toString()))
            }
        try {
            val staged = staging.resolve("${(validated["release_id"] as JsonPrimitive).content}.json")
            writeForced(staged, bytes)
            forceDirectory(staging)
            return PreparedRelease(validated, staging, staged)
        } catch (failure: Throwable) {
            DataDirectory.deleteTree(staging)
            throw failure
        }
    }

    private fun releaseTargetOrNull(releaseId: String): Path? {
        val directory = dataDirectory.root.resolve(RELEASES_DIRECTORY)
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return null
        requireReleasesDirectory(directory)
        val target = directory.resolve("$releaseId.json")
        return target.takeIf { Files.exists(it, LinkOption.NOFOLLOW_LINKS) }
    }
```

Файловые функции и константы (в конце файла):

```kotlin
private fun releaseAnalysisIds(record: JsonObject): List<String> =
    (record["analyses"] as JsonArray).map { ((it as JsonObject)["analysis_id"] as JsonPrimitive).content }

private fun randomReleaseSuffix(): String = HexFormat.of().formatHex(ByteArray(4).also(RELEASE_RANDOM::nextBytes))

private fun requireReleaseId(releaseId: String) {
    require(RELEASE_ID.matches(releaseId)) { "INVALID_RELEASE_ID" }
}

private fun requireReleasesDirectory(path: Path): Path {
    if (Files.isSymbolicLink(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) corruptReleaseRegistry("unsafe releases directory")
    return path
}

private fun corruptRelease(message: String): Nothing = throw IllegalStateException("CORRUPT_RELEASE: $message")

private fun corruptReleaseRegistry(message: String): Nothing = throw IllegalStateException("CORRUPT_RELEASE_REGISTRY: $message")

private const val RELEASES_DIRECTORY = "releases"
private const val RELEASE_ID_ATTEMPTS = 8
internal const val MAX_RELEASES = 1_000
private val RELEASE_FILE = Regex("[0-9]{15}-[0-9a-f]{8}\\.json")
private val RELEASE_RANDOM = java.security.SecureRandom()
```

Нужные импорты (`ChronoUnit`, `Instant`, `IOException`, `JsonObject`-расширения, константы ядра, `releaseId`, `validateRelease`, `RELEASE_*`) добавляет компилятор. Протокол замка: под `operationLock` остаются перечисление каталога (до 1 001 файла по 8 KiB), проверки предела и уникальности, существования цели и сама публикация `ATOMIC_MOVE`; проверка записи, канонические байты, создание staging, запись и `fsync` готовятся вне замка (`prepareRelease`). Гарантия «создание без перезаписи» держится на единственном писателе: `.ltv.lock` исключает другой процесс, `operationLock` сериализует потоки, проверка `exists` и перемещение идут под одним замком. `ATOMIC_MOVE` при существующей цели ведёт себя по реализации платформы (замещает, а не отказывает), поэтому `exists` внутри замка единственная защита, а внешний процесс в модель угроз не входит (доверенный контур, дополнение ADR 0002 от 2026-09-30). Замена использует `ATOMIC_MOVE` и по контракту платформы замещает цель (как `replaceBaseline`); конкурентная замена того же файла определяется сравнением байт и даёт `RELEASE_CHANGED`. Время под замком фиксируется замером шага 9.

- [ ] **Step 2.4:** Run: `.\gradlew.bat test --tests "io.ltverdict.storage.RunBundleStoreTest" --tests "io.ltverdict.core.LocalReleaseTest"`, затем `.\gradlew.bat ktlintCheck`. Expected: PASS. Commit: `feat(storage): store release records with bounded complete listing`.

- [ ] **Step 2.5:** Run: `.\gradlew.bat clean check installDist`. Commit (если правились форматирование или тесты): `test(storage): cover release registry limits and damaged entries`.

**Риски R2:** (1) полный скан под замком: до 1 001 файла и 8 MiB на вызов; замер на 1 000 записях обязателен (шаг 9), при задержке выше сотен миллисекунд по ADR 0019 вводится индекс (не в этом срезе); (2) символические ссылки недоступны без привилегий на Windows: тест пропускается явно; (3) каноническая запись до 8 KiB, но худший допустимый по полям случай (кавычки, восемь анализов) превышает предел и получает `RELEASE_TOO_LARGE`: не тихая потеря, а отказ с кодом; (4) `ATOMIC_MOVE` без `REPLACE_EXISTING` на Windows при существующей цели: имя проверено под тем же замком, одиночный процесс гарантирован `.ltv.lock`.

**Documentation impact:** none для этого среза, если он вливается до R3 (поведения для пользователя нет); иначе общая запись CHANGELOG в R3.

---

## R3. Маршруты `/api/releases`, PUT в защите изменений, `error.limit`

**Цель:** пять приватных маршрутов (список, чтение, создание, замена, удаление), проверка фактов по настоящим документам анализов (хэш результата, `run.json`, `started_at`, плечи), `PUT` в проверке Origin/session/CSRF, необязательное поле `error.limit`.

**Ветка:** `feat/release-api`. **Размер:** M (около 300 строк `LocalApi.kt`, тесты крупнее). **Зависит от:** R1 (читатель с хэшем, функция допуска), R2. **Identity и контракты:** identity не меняется; новые приватные маршруты и коды ответов.

**Что не входит:** UI (R7), comparison (R5), `/analytics` (R6), CLI.

**Files:**

- Modify: `src/main/kotlin/io/ltverdict/web/LocalApi.kt` (перехватчик 149; маршруты после baseline-маршрутов 384-403; `receiveBaselineRequest` 993; `ApiFailure` 1710; `respondError` 1668; новые частные функции)
- Modify (тесты): `src/test/kotlin/io/ltverdict/web/LocalApiTest.kt`, `src/test/kotlin/io/ltverdict/web/LocalSecurityTest.kt`
- Modify: `docs/architecture/slice-1-local-runtime.md` (новый раздел «Release history»: хранилище, лимиты, маршруты, защита PUT), `CHANGELOG.md`

**Interfaces:**

- Routes: `GET /api/releases?series&after&limit`, `GET|PUT|DELETE /api/releases/{releaseId}`, `POST /api/releases` (ADR 0019, раздел 3 с поправкой R0).
- Produces: `ApiFailure(status, code, message, limit: Int? = null)`; конверт `{"error":{"code","message","details","limit"?}}` с необязательным `limit`.
- Response shape (запись плюс вычисляемые поля): к каждому элементу `analyses[]` добавляются `analysis_state` (`OK|MISSING` в списке, `OK|MISSING|CORRUPT` в `GET` по идентификатору), `baseline_eligible` (boolean), `ineligible_reasons` (массив: `ANALYSIS_MISSING`, `ANALYSIS_CORRUPT` или первый код `baselineCandidateRejection`); к записи добавляются `baseline_eligible` (все анализы допустимы) и `ineligible_reasons` (объединение, отсортировано). Список: `{releases, next_after, series_summary:[{series,count}], corrupt_count, corrupt_names:[{name,reason}]}`; `POST` отвечает `201` записью; `DELETE` отвечает `{"release": null}`.

### Task 1: защита PUT и `error.limit`

- [ ] **Step 1.1: Красные тесты.** `LocalSecurityTest.kt`: для `PUT /api/releases/001767225600000-0123abcd` без Origin, с чужим Origin, без cookie сессии, без заголовка CSRF, с неверным CSRF каждый запрос даёт `403 FORBIDDEN` (по образцу существующих проверок `POST`/`DELETE` в этом файле); запрос с верными признаками доходит до обработчика (ответ `404`, записи нет). То же для `PATCH` (маршрута нет, но защита срабатывает до маршрутизации: `403` без признаков).
`LocalApiTest.kt`: `error.limit` отсутствует в прочих ошибках (проверить на `404` существующего маршрута, набор ключей `{code,message,details}`).

- [ ] **Step 1.2:** Run: `.\gradlew.bat test --tests "io.ltverdict.web.LocalSecurityTest"`. Expected: FAIL (PUT не защищён).

- [ ] **Step 1.3: Реализация.** В перехватчике заменить условие строки 149 на

```kotlin
        if (call.request.httpMethod in MUTATING_METHODS) {
```

и объявить `private val MUTATING_METHODS = setOf(HttpMethod.Post, HttpMethod.Put, HttpMethod.Delete, HttpMethod.Patch)`. `ApiFailure` получает `val limit: Int? = null`; `catch (failure: ApiFailure)` передаёт `failure.limit` в `respondError(..., limit = failure.limit)`; `respondError` дописывает `limit?.let { put("limit", it) }` в объект `error`. `receiveBaselineRequest(call)` получает параметр `subject: String = "Baseline"` и подставляет его в четыре сообщения («Baseline request exceeds 16 KiB» и т. п.).

- [ ] **Step 1.4:** Run: `.\gradlew.bat test --tests "io.ltverdict.web.LocalSecurityTest" --tests "io.ltverdict.web.LocalApiTest"`. Expected: PASS. Commit: `feat(api): require session and CSRF for PUT and carry limits in errors`.

### Task 2: создание, чтение, список, замена, удаление

- [ ] **Step 2.1: Красные тесты** (`LocalApiTest.kt`; создание анализов через `createJob(runId, permissivePolicy)`; прямая запись анализа без `run.json` или с другим `started_at` через `store.writeAnalysisAtomically`, как в тестах R1). Каждый пункт отдельный тест с точными ожиданиями:

1. `release is created from verified documents`: `POST /api/releases` с `{"series":"checkout","label":"1.0","run_id":R,"analyses":[{"analysis_id":A}],"profile":null,"notes":null}` даёт `201`; `release_id` по маске; `started_at` равен `run.json`; `analyses[0].policy_verdict == "PASS"`, `analysis_state == "OK"`, `baseline_eligible == true`; метка в NFD (`"é"`) возвращается как `"é"`; профиль из шести пустых строк и пробелов сохраняется как `null`.
2. `release body is validated field by field`: лишний ключ, отсутствующий `notes`, `analyses: []`, девять анализов, повтор `analysis_id`, `label` из 129 байт, `series` из пробелов, `run_id` не по маске, `analysis_id` не 64 hex, профиль с неизвестным ключом, `Content-Type: text/plain`: `400 MALFORMED_REQUEST` (для типа `415`); тело 16 KiB + 1 даёт `413 RESOURCE_LIMIT_EXCEEDED`. Ни один отказ не создаёт файл в `<data>/releases/`.
3. `unknown analysis or run is 404 and another run's analysis is not found`: идентификатор несуществующего анализа и запуск, которого нет, дают `404 NOT_FOUND`; `analysis_id` из другого запуска при `run_id` первого даёт `404` (пара не находится).
4. `registration refuses analyses without run metadata and with different test starts`: анализ без `run.json` (прямая запись) даёт `422 RELEASE_ANALYSIS_NO_RUN_METADATA`; два анализа одного запуска с разными `started_at` в `run.json` (прямая запись второго) дают `422 RELEASE_STARTED_AT_MISMATCH`; результат с чужим `run_id` внутри документа даёт `422 RELEASE_RUN_MISMATCH`; два анализа без плеча и повтор плеча дают `422 RELEASE_ARM_CONFLICT`.
5. `registration refuses a tampered or oversized result`: подмена `analysis-result.json` при сохранённом размере даёт `500 CORRUPT_RUN_BUNDLE`; результат на байт больше `MAX_VERIFIED_RESULT_BYTES` (разреженный файл, как в R1) даёт `422 RELEASE_RESULT_TOO_LARGE`.
6. `one analysis registers once`: два параллельных `POST` с одним `analysis_id` (два потока, барьер) дают ровно `{201, 409 RELEASE_ANALYSIS_ALREADY_REGISTERED}`; повторный `POST` после удаления первой записи проходит.
7. `list is complete, newest first and paginated`: три релиза в двух сериях; `GET /api/releases?limit=2` возвращает по убыванию, `next_after` равен последнему; вторая страница с `after` даёт остаток и `null`; `series=` фильтрует; `series_summary` считает все записи; лишний параметр даёт `400`; `limit=0`, `limit=101`, `after=garbage` дают `400`. Каталог с постороннего файла: `corrupt_count == 1`, имя в `corrupt_names` с причиной `UNSAFE_ENTRY`.
8. `list reports MISSING cheaply and GET reports the full state`: после удаления каталога анализа вручную список даёт `analysis_state: "MISSING"`, `baseline_eligible: false`, `ineligible_reasons: ["ANALYSIS_MISSING"]`; `GET` по идентификатору для повреждённого анализа (изменённый размер артефакта) даёт `analysis_state: "CORRUPT"`.
9. `eligibility mirrors the baseline rule`: релиз с анализом `FAIL` или без политики (`NO_POLICY`) имеет `baseline_eligible: false` и `ineligible_reasons: ["BASELINE_CANDIDATE_NOT_PASS"]`; с `permissivePolicy` `true`.
10. `replace changes only the editable fields`: `PUT` меняет `label`, `notes`, `profile`, `updated_at`; ответ сохраняет `series`, `run_id`, `release_id`, `started_at`, `created_at`; тело с `series` или `run_id` даёт `400`; замена `analyses` анализом того же запуска (переанализ с другой политикой) проходит и сохраняет `started_at`; анализ другого релиза даёт `409`; свой же анализ проходит; `started_at` нового анализа другой даёт `422 RELEASE_STARTED_AT_MISMATCH`; повреждённая запись даёт `500 CORRUPT_RELEASE`; неизвестный идентификатор `404`; небезопасный идентификатор `400`.
11. `delete removes only the record`: после `DELETE` анализ читается, выбранный baseline и записи условий неизменны (сравнить `GET /api/baseline` до и после), повторный `DELETE` даёт `404`; повреждённый файл удаляется по идентификатору без разбора; `DELETE` неизвестного `404`.
12. `limit and corruption of the registry`: засев `MAX_RELEASES` корректных записей (прямая запись файлов); `POST` даёт `422 RELEASE_LIMIT_REACHED`, `error.limit == 1000`; лишний файл даёт на `GET /api/releases` и `POST` `500 CORRUPT_RELEASE_REGISTRY`.
13. `record that overflows 8 KiB after escaping is a clear refusal`: восемь анализов (разные плечи по 128 символов `"`, плечи задаются прямой записью `identity.resource_arm` в `store`), заметка 1 024 символа `"`, профиль из шести полей по 128 символов `"`, `series` и `label` по 128 символов `"` дают `422 RELEASE_TOO_LARGE`, не `500`; тест до вызова API утверждает размер канонического JSON записи больше `MAX_RELEASE_BYTES`.
14. `a concurrent replacement is refused`: два параллельных `PUT` одной записи с разным `label` дают `{200, 409 RELEASE_CHANGED}` либо оба `200` (если выполнились последовательно), но файл всегда равен результату одного из них и валиден.
15. `errors do not echo user text`: ответ на отказ (например `409` и `422`) не содержит ни `label`, ни `notes`, ни полей профиля из запроса (тест ищет уникальную строку-маркер в теле ответа).

- [ ] **Step 2.2:** Run: `.\gradlew.bat test --tests "io.ltverdict.web.LocalApiTest"`. Expected: FAIL (маршрутов нет).

- [ ] **Step 2.3: Реализация** (`LocalApi.kt`). Маршруты (внутри `routing { ... }` после baseline-маршрутов):

```kotlin
        get("/api/releases") {
            call.requireOnlyQueries("series", "after", "limit")
            val series = call.singleQuery("series")?.let { releaseTextField(it, "series", MAX_RELEASE_TEXT_BYTES) }
            val after = call.singleQuery("after")?.also { if (!RELEASE_ID.matches(it)) malformed("after is invalid") }
            val limit = call.intQuery("limit", 50, 1..100)
            val page = releaseOperation { context.store.listReleases(series, after, limit) }
            val states = releaseOperation { page.releases.flatMap { releaseStateKeys(it) }.associateWith { (runId, analysisId) -> if (context.store.analysisExists(runId, analysisId)) "OK" else "MISSING" } }
            call.respondJson(releasePageJson(page, states))
        }

        get("/api/releases/{releaseId}") {
            call.requireOnlyQueries()
            val id = call.releaseIdParameter()
            val record = releaseOperation { context.store.readRelease(id) } ?: notFound("Release was not found")
            val states = releaseOperation { releaseStateKeys(record).associateWith { (runId, analysisId) -> context.store.analysisState(runId, analysisId) } }
            call.respondJson(releaseView(record, states))
        }

        post("/api/releases") {
            call.requireOnlyQueries()
            call.requireJson()
            val body = receiveBaselineRequest(call, "Release")
            if (body.keys != RELEASE_POST_FIELDS) malformed("Release fields are invalid")
            // cheap field checks first: a malformed body never triggers the expensive verified reads
            val series = releaseTextField(body.baselineString("series"), "series", MAX_RELEASE_TEXT_BYTES) ?: malformed("series is required")
            val label = releaseTextField(body.baselineString("label"), "label", MAX_RELEASE_TEXT_BYTES) ?: malformed("label is required")
            val runId = releaseRunId(body)
            val analysisIds = releaseAnalysisIds(body)
            val profile = releaseProfile(body["profile"]) ?: JsonNull
            val notes = releaseNotes(body["notes"])
            val (startedAt, analyses) = releaseFacts(context.store, runId, analysisIds)
            val draft = buildJsonObject {
                put("schema_version", RELEASE_SCHEMA)
                put("series", series)
                put("label", label)
                put("run_id", runId)
                put("started_at", startedAt)
                put("analyses", JsonArray(analyses))
                put("profile", profile)
                put("notes", notes)
            }
            val created = releaseOperation { context.store.createRelease(draft, Instant.now()) }
            call.respondJson(releaseView(created, releaseOkStates(created)), HttpStatusCode.Created)
        }

        put("/api/releases/{releaseId}") {
            call.requireOnlyQueries()
            call.requireJson()
            val id = call.releaseIdParameter()
            val body = receiveBaselineRequest(call, "Release")
            if (body.keys != RELEASE_PUT_FIELDS) malformed("Release fields are invalid")
            val label = releaseTextField(body.baselineString("label"), "label", MAX_RELEASE_TEXT_BYTES) ?: malformed("label is required")
            val analysisIds = releaseAnalysisIds(body)
            val profile = releaseProfile(body["profile"]) ?: JsonNull
            val notes = releaseNotes(body["notes"])
            val existing = releaseOperation { context.store.readRelease(id) } ?: notFound("Release was not found")
            val (startedAt, analyses) = releaseFacts(context.store, existing.releaseField("run_id"), analysisIds)
            if (startedAt != existing.releaseField("started_at")) {
                throw ApiFailure(HttpStatusCode.UnprocessableEntity, "RELEASE_STARTED_AT_MISMATCH", "Analyses start at a different time than the release")
            }
            val stamp = JsonPrimitive(Instant.now().truncatedTo(ChronoUnit.MILLIS).toString())
            val updated = releaseOperation {
                context.store.replaceRelease(id) { current ->
                    JsonObject(current + mapOf("label" to JsonPrimitive(label), "analyses" to JsonArray(analyses),
                        "profile" to profile, "notes" to notes, "updated_at" to stamp))
                }
            }
            call.respondJson(releaseView(updated, releaseOkStates(updated)))
        }

        delete("/api/releases/{releaseId}") {
            call.requireOnlyQueries()
            val id = call.releaseIdParameter()
            if (!releaseOperation { context.store.deleteRelease(id) }) notFound("Release was not found")
            call.respondJson(buildJsonObject { put("release", JsonNull) })
        }
```

`releaseOkStates(record)` в ответах `POST`/`PUT` берёт только что записанные факты: состояние `OK`, потому что документы только что проверены. Вспомогательные функции:

```kotlin
private val RELEASE_POST_FIELDS = setOf("series", "label", "run_id", "analyses", "profile", "notes")
private val RELEASE_PUT_FIELDS = setOf("label", "analyses", "profile", "notes")

private fun releaseTextField(raw: String, name: String, maxBytes: Int, multiline: Boolean = false): String? {
    val value = normalizeReleaseText(raw)
    if (value.isEmpty()) return null
    if (value.encodeToByteArray().size > maxBytes || value.any { it.isISOControl() && !(multiline && it == '\n') }) {
        malformed("$name is invalid")
    }
    return value
}

private fun releaseProfile(element: JsonElement?): JsonObject? {
    if (element == null) malformed("profile is required")
    if (element == JsonNull) return null
    val fields = element as? JsonObject ?: malformed("profile is invalid")
    if (fields.keys != RELEASE_PROFILE_FIELDS.toSet()) malformed("profile fields are invalid")
    val values = RELEASE_PROFILE_FIELDS.map { name ->
        when (val value = fields.getValue(name)) {
            JsonNull -> null
            is JsonPrimitive -> if (value.isString) releaseTextField(value.content, name, MAX_RELEASE_TEXT_BYTES) else malformed("profile is invalid")
            else -> malformed("profile is invalid")
        }
    }
    if (values.all { it == null }) return null
    return JsonObject(RELEASE_PROFILE_FIELDS.zip(values).associate { (name, value) -> name to (value?.let(::JsonPrimitive) ?: JsonNull) })
}

private fun releaseNotes(element: JsonElement?): JsonElement =
    when (element) {
        null -> malformed("notes is required")
        JsonNull -> JsonNull
        is JsonPrimitive -> if (element.isString) {
            releaseTextField(element.content, "notes", MAX_RELEASE_NOTES_BYTES, multiline = true)?.let(::JsonPrimitive) ?: JsonNull
        } else malformed("notes is invalid")
        else -> malformed("notes is invalid")
    }

private fun releaseRunId(body: JsonObject): String =
    body.baselineString("run_id").takeIf { RUN_ID.matches(it) } ?: malformed("run_id is invalid")

private fun releaseAnalysisIds(body: JsonObject): List<String> {
    val values = body["analyses"] as? JsonArray ?: malformed("analyses must be an array")
    if (values.size !in 1..MAX_RELEASE_ANALYSES) malformed("analyses must hold 1-$MAX_RELEASE_ANALYSES items")
    val ids = values.map { item ->
        val entry = item as? JsonObject ?: malformed("analysis entry is invalid")
        if (entry.keys != setOf("analysis_id")) malformed("analysis entry is invalid")
        entry.baselineString("analysis_id").takeIf { Regex("[0-9a-f]{64}").matches(it) } ?: malformed("analysis_id is invalid")
    }
    if (ids.toSet().size != ids.size) malformed("analysis_id values must differ")
    return ids
}

private fun ApplicationCall.releaseIdParameter(): String =
    parameters["releaseId"]?.takeIf { RELEASE_ID.matches(it) } ?: malformed("Release id is invalid")
```

(Пустые `series`, `label` дают `malformed` через `?: malformed(...)` в местах вызова; в профиле и `notes` пустое значение нормализуется в `null`.) Проверка фактов (по одному анализу за раз, чтобы не держать несколько разобранных результатов; отбрасывание дерева после извлечения фактов):

```kotlin
private suspend fun releaseFacts(
    store: RunBundleStore,
    runId: String,
    analysisIds: List<String>,
): Pair<String, List<JsonObject>> {
    var startedAt: String? = null
    val facts = mutableListOf<JsonObject>()
    for (analysisId in analysisIds) {
        val verified =
            releaseOperation {
                try {
                    store.readVerifiedAnalysis(runId, analysisId) ?: notFound("Analysis was not found")
                } catch (failure: IllegalArgumentException) {
                    if (failure.message == "RESULT_TOO_LARGE") {
                        throw ApiFailure(HttpStatusCode.UnprocessableEntity, "RELEASE_RESULT_TOO_LARGE", "Analysis result exceeds the verification limit")
                    }
                    throw failure
                }
            }
        val analysisStart =
            (verified.run?.get("started_at") as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw ApiFailure(HttpStatusCode.UnprocessableEntity, "RELEASE_ANALYSIS_NO_RUN_METADATA", "Analysis has no run metadata")
        if ((verified.result["run_id"] as? JsonPrimitive)?.content != runId) {
            throw ApiFailure(HttpStatusCode.UnprocessableEntity, "RELEASE_RUN_MISMATCH", "Analysis documents belong to another run")
        }
        if (startedAt != null && startedAt != analysisStart) {
            throw ApiFailure(HttpStatusCode.UnprocessableEntity, "RELEASE_STARTED_AT_MISMATCH", "Analyses start at different times")
        }
        startedAt = analysisStart
        facts +=
            try {
                releaseAnalysisFacts(analysisId, verified.result, verified.identity)
            } catch (_: IllegalArgumentException) {
                throw ApiFailure(HttpStatusCode.UnprocessableEntity, "RELEASE_FACTS_INVALID", "Analysis facts are unavailable")
            }
    }
    val arms = facts.map { it["arm"] }
    if (facts.size > 1 && (arms.any { it == JsonNull } || arms.toSet().size != arms.size)) {
        throw ApiFailure(HttpStatusCode.UnprocessableEntity, "RELEASE_ARM_CONFLICT", "Arms must be distinct and present when a release has several analyses")
    }
    releaseStartedAtMillisOrReject(startedAt)
    return checkNotNull(startedAt) to facts.sortedBy { (it["analysis_id"] as JsonPrimitive).content }
}
```

`releaseStartedAtMillisOrReject` преобразует `IllegalArgumentException` разбора `started_at` (повреждённая запись `run.json`) в `422 RELEASE_STARTED_AT_MISMATCH`? Корректнее код `RELEASE_ANALYSIS_NO_RUN_METADATA` (метка времени непригодна): использовать его. Обёртки состояний и представления:

```kotlin
private fun JsonObject.releaseField(name: String): String = (getValue(name) as JsonPrimitive).content

private fun releaseStateKeys(record: JsonObject): List<Pair<String, String>> =
    (record.getValue("analyses") as JsonArray).map { record.releaseField("run_id") to ((it as JsonObject).getValue("analysis_id") as JsonPrimitive).content }

private fun releaseOkStates(record: JsonObject): Map<Pair<String, String>, String> = releaseStateKeys(record).associateWith { "OK" }

private fun releaseIneligibleReasons(analysis: JsonObject, state: String): List<String> =
    when (state) {
        "OK" ->
            listOfNotNull(
                baselineCandidateRejection(
                    (analysis["policy_verdict"] as JsonPrimitive).content,
                    (analysis["run_validity"] as JsonPrimitive).content,
                    (analysis["coverage_status"] as JsonPrimitive).content,
                ),
            )
        "MISSING" -> listOf("ANALYSIS_MISSING")
        else -> listOf("ANALYSIS_CORRUPT")
    }

private fun releaseView(record: JsonObject, states: Map<Pair<String, String>, String>): JsonObject {
    val runId = record.releaseField("run_id")
    val analyses =
        (record.getValue("analyses") as JsonArray).map { item ->
            val analysis = item as JsonObject
            val id = (analysis.getValue("analysis_id") as JsonPrimitive).content
            val state = states.getValue(runId to id)
            val reasons = releaseIneligibleReasons(analysis, state)
            JsonObject(
                analysis +
                    mapOf(
                        "analysis_state" to JsonPrimitive(state),
                        "baseline_eligible" to JsonPrimitive(reasons.isEmpty()),
                        "ineligible_reasons" to JsonArray(reasons.map(::JsonPrimitive)),
                    ),
            )
        }
    val all = analyses.flatMap { (it.getValue("ineligible_reasons") as JsonArray).map { reason -> (reason as JsonPrimitive).content } }.distinct().sorted()
    return JsonObject(
        record +
            mapOf(
                "analyses" to JsonArray(analyses),
                "baseline_eligible" to JsonPrimitive(all.isEmpty()),
                "ineligible_reasons" to JsonArray(all.map(::JsonPrimitive)),
            ),
    )
}
```

`releasePageJson(page, states)` собирает объект списка (поля из раздела Interfaces). Сопоставление ошибок хранилища — единая обёртка:

```kotlin
private suspend fun <T> releaseOperation(action: () -> T): T =
    withContext(Dispatchers.IO) {
        try {
            action()
        } catch (_: NoSuchElementException) {
            notFound("Release or referenced analysis was not found")
        } catch (failure: IllegalArgumentException) {
            when (failure.message) {
                "RELEASE_LIMIT_REACHED" ->
                    throw ApiFailure(HttpStatusCode.UnprocessableEntity, "RELEASE_LIMIT_REACHED", "Release limit is reached", MAX_RELEASES)
                "RELEASE_ANALYSIS_ALREADY_REGISTERED" -> conflict("RELEASE_ANALYSIS_ALREADY_REGISTERED", "An analysis already belongs to a release")
                "RELEASE_TOO_LARGE" -> throw ApiFailure(HttpStatusCode.UnprocessableEntity, "RELEASE_TOO_LARGE", "Release record exceeds its size limit")
                "RELEASE_CHANGED" -> conflict("RELEASE_CHANGED", "Release was changed concurrently; reload and retry")
                "INVALID_RELEASE_ID" -> malformed("Release id is invalid")
                else -> throw failure
            }
        } catch (failure: IllegalStateException) {
            val message = failure.message.orEmpty()
            when {
                message.startsWith("CORRUPT_RELEASE_REGISTRY") ->
                    throw ApiFailure(HttpStatusCode.InternalServerError, "CORRUPT_RELEASE_REGISTRY", "Release registry is corrupt")
                message.startsWith("CORRUPT_RELEASE") ->
                    throw ApiFailure(HttpStatusCode.InternalServerError, "CORRUPT_RELEASE", "Release record is corrupt")
                message.startsWith("CORRUPT_RUN_BUNDLE") ->
                    throw ApiFailure(HttpStatusCode.InternalServerError, "CORRUPT_RUN_BUNDLE", "Referenced analysis is corrupt")
                else -> throw failure
            }
        }
    }
```

Сообщения ответов не содержат пользовательский текст запроса (метки, заметки, профиль). Строки, прошедшие нормализацию, попадают только в тело успешного ответа. Хэширование результата (до 64 MiB) идёт в `Dispatchers.IO` вне замка хранилища (R1); под `operationLock` остаются только `createRelease`/`replaceRelease`.

- [ ] **Step 2.4:** Run: `.\gradlew.bat test --tests "io.ltverdict.web.LocalApiTest" --tests "io.ltverdict.web.LocalSecurityTest"`, `.\gradlew.bat ktlintCheck`. Expected: PASS. Commit: `feat(api): add release history routes with verified analysis facts`.

### Task 3: документация и полный прогон

- [ ] **Step 3.1:** `docs/architecture/slice-1-local-runtime.md`: раздел «Release history» (схема каталога `<data>/releases/<release_id>.json`, пределы, роли замка, перечень маршрутов и кодов, правило `PUT` в защите изменений, граница «копии фактов, а не источник истины»). Рядом с описанием baseline и ссылкой на ADR 0019. `CHANGELOG.md`, `[Unreleased]`, `Added`: приватные маршруты `/api/releases` без изменений публичных контрактов; `Changed`: `PUT` защищён так же, как `POST` и `DELETE`.
- [ ] **Step 3.2:** Run: `npx --yes markdownlint-cli2@0.23.2 docs/architecture/slice-1-local-runtime.md CHANGELOG.md`, `.\gradlew.bat clean check installDist`, `npm --prefix ui run typecheck`, `npm --prefix ui run lint`. Expected: PASS. Commit: `docs(release): describe the release history storage and API`. Исход PR: `feat(api): add release history storage and routes`.

**Риски R3:** (1) `LocalApi.kt` растёт примерно на 300 строк: файл уже 1 816 строк; вынос в отдельный файл возможен без смены поведения, но не входит в срез (MINIMAL-CHANGE: прецедент baseline-маршрутов в том же файле); (2) память: каждый анализ релиза разбирается целиком в `JsonObject` и освобождается до следующего; пик равен одному результату (до 64 MiB файла, порядка сотен МБ дерева) - замеряется на этапе приёмки как отдельный пункт, измерение на стенде не входит в МВП; (3) `GET` списка делает до `limit x 8` проверок существования на страницу под замком по одной: измеряется вместе с шагом 9 R2.

**Documentation impact:** `docs/architecture/slice-1-local-runtime.md`, `CHANGELOG.md`.

---

## R4. Допуск `SMALL_SAMPLE` и предупреждение `BASELINE_SMALL_SAMPLE`

**Цель:** `PASS` с покрытием `INCOMPLETE` только из-за причины `SMALL_SAMPLE` допускается как baseline в обоих режимах (решение владельца 2026-10-04, ADR 0018 «Решения владельца (2026-10-04)», п. 3; поправка ADR 0019 от 2026-10-04), и в том же PR comparison предупреждает `BASELINE_SMALL_SAMPLE`. Допуск без предупреждения невозможен: это один срез.

**Ветка:** `feat/baseline-small-sample`. **Размер:** S. **Зависит от:** R1. ADR 0018 S1 влит (`a2547f2`, #58): причина `SMALL_SAMPLE` в `analysis_coverage.reasons` и `sample_mode` в evidence `policy_check` уже производятся ядром (`Policy.kt:132`, `:157`, `:178`), поэтому допуск наблюдаем на реальных результатах; S10 ADR 0018 остаётся впереди и выпускается после R4. **Identity и контракты:** нет; ключ сопоставимости не меняется; новое предупреждение в `warnings` (дополнение массива).

**Что не входит:** предупреждение о малой выборке у текущего анализа (определяет ADR 0018), проверка `verdict_gates` (S10 ADR 0018: после R4), UI-текст (R9).

**Files:**

- Modify: `src/main/kotlin/io/ltverdict/core/BaselineComparison.kt` (функция допуска из R1; `compareAnalyses` строки 143-166)
- Modify: `src/main/kotlin/io/ltverdict/web/LocalApi.kt` (вызовы функции допуска: передают причины)
- Modify (тесты): `BaselineComparisonTest.kt`, `LocalApiTest.kt`
- Modify: `docs/user/slice-1-local-analysis.md` (раздел про baseline), `CHANGELOG.md`

**Interfaces:** `baselineCandidateRejection(policyVerdict: String?, runValidity: String?, coverageStatus: String?, coverageReasons: List<String>): String?` заменяет трёхаргументную форму R1; вариант по `JsonObject` читает `analysis_coverage.reasons` и делегирует. Допуск по покрытию:

```kotlin
private fun coverageAdmitted(status: String?, reasons: List<String>): Boolean =
    status == "COMPLETE" || (status == "INCOMPLETE" && reasons.isNotEmpty() && reasons.all { it == "SMALL_SAMPLE" })
```

### Task 1: функция допуска и предупреждение

- [ ] **Step 1.1: Красные тесты** (`BaselineComparisonTest.kt`; помощник `result(...)` получает `reasons: List<String> = emptyList()` и пишет `analysis_coverage = {status, reasons}`, статус `INCOMPLETE`, если список непуст и `coverage` не задан явно):

```kotlin
    @Test
    fun `incomplete coverage is admitted only when its single reason is a small sample`() {
        fun rejection(
            verdict: String = "PASS",
            status: String = "INCOMPLETE",
            reasons: List<String>,
        ) = baselineCandidateRejection(verdict, "VALID", status, reasons)

        assertEquals(null, rejection(reasons = listOf("SMALL_SAMPLE")))
        assertEquals(null, rejection(reasons = listOf("SMALL_SAMPLE", "SMALL_SAMPLE")))
        assertEquals("BASELINE_CANDIDATE_INCOMPLETE", rejection(reasons = listOf("SMALL_SAMPLE", "RESOURCE_GAPS")))
        assertEquals("BASELINE_CANDIDATE_INCOMPLETE", rejection(reasons = listOf("RESOURCE_GAPS")))
        assertEquals("BASELINE_CANDIDATE_INCOMPLETE", rejection(reasons = emptyList()))
        assertEquals("BASELINE_CANDIDATE_INCOMPLETE", rejection(status = "PARTIAL", reasons = listOf("SMALL_SAMPLE")))
        // an insufficient sample gives NO_VERDICT and stays excluded by the PASS rule
        assertEquals("BASELINE_CANDIDATE_NOT_PASS", rejection(verdict = "NO_VERDICT", reasons = listOf("SMALL_SAMPLE")))
        assertEquals("BASELINE_CANDIDATE_NOT_PASS", rejection(verdict = "FAIL", reasons = listOf("SMALL_SAMPLE")))
        assertEquals(null, baselineCandidateRejection("PASS", "VALID", "COMPLETE", emptyList()))
    }

    @Test
    fun `comparison warns when the baseline analysis is a small sample`() {
        val selection = manualBaselineSelection("release", reference('a'))
        val small = result(reasons = listOf("SMALL_SAMPLE"))

        val warned = compareAnalyses(selection, reference('b'), small, identity(), result(), identity())
        assertEquals(listOf("BASELINE_SMALL_SAMPLE"), warnings(warned))
        // the mark on the current analysis alone does not warn here (ADR 0018 decides that case)
        val currentOnly = compareAnalyses(selection, reference('b'), result(), identity(), small, identity())
        assertEquals(emptyList<String>(), warnings(currentOnly))
        // metrics, statuses and comparability are not touched by the warning
        val plain = compareAnalyses(selection, reference('b'), result(), identity(), result(), identity())
        assertEquals(plain.getValue("metrics"), warned.getValue("metrics"))
        assertEquals(plain.getValue("comparability"), warned.getValue("comparability"))
    }

    @Test
    fun `statistical selection admits a small sample candidate`() {
        val set = listOf(candidate('a'), candidate('b', result(reasons = listOf("SMALL_SAMPLE"))), candidate('c'))
        val selection = statisticalBaselineSelection("small", set.references(), set.results(), set.identities())
        assertEquals(3, selection.getValue("candidates").jsonArray.size)
    }
```

Значение `"PARTIAL"` подчёркивает, что любой статус кроме `COMPLETE` и `INCOMPLETE` отвергается. В `LocalApiTest`: анализ с синтетическим результатом `INCOMPLETE`/`["SMALL_SAMPLE"]` и `PASS` (прямая запись `store.writeAnalysisAtomically` с identity, содержащей `policy_sha256`, результат с `run_validity`, `policy_verdict`, `analysis_coverage`) принимается `POST /api/baseline` в режиме `manual` (200), а `GET .../comparison` текущего анализа содержит `BASELINE_SMALL_SAMPLE`; такой же результат с причинами `["SMALL_SAMPLE","RESOURCE_GAPS"]` даёт `422 BASELINE_CANDIDATE_INCOMPLETE`.

- [ ] **Step 1.2:** Run: `.\gradlew.bat test --tests "io.ltverdict.core.BaselineComparisonTest" --tests "io.ltverdict.web.LocalApiTest"`. Expected: FAIL (сигнатура, предупреждение).

- [ ] **Step 1.3: Реализация.** Заменить `baselineCandidateRejection` из R1 на четырёхаргументную (тело: порядок `INVALID`, `INCOMPLETE` через `coverageAdmitted`, `NOT_PASS`); вариант по `JsonObject` читает

```kotlin
    val reasons = result.objectOrNull("analysis_coverage")?.get("reasons")?.let { (it as? JsonArray)?.mapNotNull { r -> (r as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content } }.orEmpty()
```

В `compareAnalyses` после блока `CURRENT_IN_CANDIDATE_SET` добавить

```kotlin
            if ("SMALL_SAMPLE" in coverageReasons(baselineResult)) add("BASELINE_SMALL_SAMPLE")
```

где `coverageReasons(result)` тот же разбор причин. Вызов в сводке релиза (R3) передаёт копию `coverage_reasons` записи: правка одной строки `releaseIneligibleReasons`.

- [ ] **Step 1.4:** Run: `.\gradlew.bat test`. Expected: PASS. Документация: раздел baseline пользовательского руководства (допуск малой выборки с предупреждением, решение владельца 2026-10-04), `CHANGELOG.md` (`Changed`). Commit: `feat(baseline): admit small-sample PASS with a baseline warning`. После слияния S10 ADR 0018 берёт на себя только проверку `verdict_gates` (см. «Согласование с планом ADR 0018»).

**Риски R4:** формат причины (`SMALL_SAMPLE`) закреплён кодом ADR 0018 S1; результат с малой выборкой получается реальным анализом с политикой, у которой число наблюдений между полом и минимумом (тест API использует его, а не синтетический документ).

**Documentation impact:** `docs/user/slice-1-local-analysis.md`, `CHANGELOG.md`.

---

## R5. Comparison: профиль и предупреждения

**Цель:** ответ `GET /api/runs/{runId}/analyses/{analysisId}/comparison` получает поле `profile` и в фиксированном порядке дополняется предупреждениями `BASELINE_NOT_PASS`, `BASELINE_SERIES_DIFFERS`, `POLICY_DIFFERS`, `PROFILE_MISMATCH` (ADR 0019, разделы 4 и 5). Порядок семи позиций: `BASELINE_IS_CURRENT_ANALYSIS|BASELINE_IS_CURRENT_RUN`, `CURRENT_IN_CANDIDATE_SET`, `BASELINE_NOT_PASS`, `BASELINE_SMALL_SAMPLE`, `BASELINE_SERIES_DIFFERS`, `POLICY_DIFFERS`, `PROFILE_MISMATCH`.

**Ветка:** `feat/comparison-profile-warnings`. **Размер:** M. **Зависит от:** R1 (вердикт baseline в сравнении), R2 (поиск релиза); порядок слияния после R4, чтобы позиция 4 уже существовала. **Identity и контракты:** identity и ключ не меняются; ответ comparison получает необязательное поле `profile` и четыре кода в `warnings` (приватный API, аддитивно).

**Что не входит:** блокировка baseline из-за `PROFILE_MISMATCH` (отклонено ADR 0019), слоты (B2), UI (R9), статусы окон остаются прежними.

**Files:**

- Modify: `src/main/kotlin/io/ltverdict/core/BaselineComparison.kt` (`compareAnalyses`: параметр, предупреждения, поле `profile`)
- Modify: `src/main/kotlin/io/ltverdict/web/LocalApi.kt` (обработчик comparison 443-473: один поиск по двум идентификаторам)
- Modify (тесты): `BaselineComparisonTest.kt`, `LocalApiTest.kt`
- Modify: `docs/user/slice-1-local-analysis.md`, `CHANGELOG.md`

**Interfaces:**

```kotlin
internal data class ReleaseComparisonContext(
    val baseline: JsonObject?,   // запись релиза, содержащая анализ baseline, либо null (нет или неоднозначно)
    val current: JsonObject?,    // то же для текущего анализа
)

internal fun compareAnalyses(
    selection: JsonObject, currentReference: JsonObject,
    baselineResult: JsonObject, baselineIdentity: JsonObject,
    currentResult: JsonObject, currentIdentity: JsonObject,
    windows: WindowComparisonRequest? = null,
    conditionsConfirmed: Boolean? = null,
    releases: ReleaseComparisonContext? = null,
): JsonObject
```

Поле `profile`: `null`, если любая запись не найдена, неоднозначна или её `profile == null`; иначе `{"status":"MATCH|MISMATCH","differing_fields":[...],"baseline_release_id":"...","current_release_id":"..."}`.

### Task 1: ядро

- [ ] **Step 1.1: Красные тесты** (`BaselineComparisonTest.kt`):

```kotlin
    private fun releaseRecord(
        id: String,
        series: String,
        profile: JsonObject?,
    ) = buildJsonObject {
        put("release_id", id)
        put("series", series)
        put("profile", profile ?: JsonNull)
    }

    private fun profile(vararg pairs: Pair<String, String?>) =
        buildJsonObject { RELEASE_PROFILE_FIELDS.forEach { name -> put(name, pairs.toMap()[name]?.let(::JsonPrimitive) ?: JsonNull) } }

    @Test
    fun `warnings keep one fixed order and never change numbers`() {
        val selection = manualBaselineSelection("blue", reference('a'))
        val baselineResult = result(verdict = "FAIL", reasons = listOf("SMALL_SAMPLE"))
        val baselineIdentity = identity().let { JsonObject(it + ("policy_sha256" to JsonPrimitive("a".repeat(64)))) }
        val currentIdentity = identity().let { JsonObject(it + ("policy_sha256" to JsonPrimitive("b".repeat(64)))) }
        val releases =
            ReleaseComparisonContext(
                releaseRecord("000000000000001-aaaaaaaa", "blue", profile("pacing" to "10 s")),
                releaseRecord("000000000000002-bbbbbbbb", "green", profile("pacing" to "20 s")),
            )

        val full = compareAnalyses(selection, reference('a', analysis = 'z'), baselineResult, baselineIdentity, result(), currentIdentity, releases = releases)
        assertEquals(
            listOf(
                "BASELINE_IS_CURRENT_RUN", "BASELINE_NOT_PASS", "BASELINE_SMALL_SAMPLE",
                "BASELINE_SERIES_DIFFERS", "POLICY_DIFFERS", "PROFILE_MISMATCH",
            ),
            warnings(full),
        )
        assertEquals(
            buildJsonObject {
                put("status", "MISMATCH")
                put("differing_fields", buildJsonArray { add(JsonPrimitive("pacing")) })
                put("baseline_release_id", "000000000000001-aaaaaaaa")
                put("current_release_id", "000000000000002-bbbbbbbb")
            },
            full.getValue("profile"),
        )
        val bare = compareAnalyses(selection, reference('a', analysis = 'z'), baselineResult, baselineIdentity, result(), currentIdentity)
        assertEquals(JsonNull, bare.getValue("profile"))
        assertEquals(bare.getValue("metrics"), full.getValue("metrics"))
        assertEquals(bare.getValue("comparability"), full.getValue("comparability"))
    }

    @Test
    fun `profile is null unless both releases are found and both declare a profile`() {
        val selection = manualBaselineSelection("blue", reference('a'))
        val declared = releaseRecord("000000000000001-aaaaaaaa", "blue", profile("load_model" to "open"))
        val silent = releaseRecord("000000000000002-bbbbbbbb", "blue", null)
        listOf(
            ReleaseComparisonContext(declared, null),
            ReleaseComparisonContext(null, declared),
            ReleaseComparisonContext(declared, silent),
            ReleaseComparisonContext(silent, silent),
        ).forEach { context ->
            val comparison = compareAnalyses(selection, reference('b'), result(), identity(), result(), identity(), releases = context)
            assertEquals(JsonNull, comparison.getValue("profile"))
            assertEquals(emptyList<String>(), warnings(comparison))
        }
        val equal = compareAnalyses(selection, reference('b'), result(), identity(), result(), identity(), releases = ReleaseComparisonContext(declared, declared))
        assertEquals("MATCH", equal.getValue("profile").jsonObject.getValue("status").jsonPrimitive.content)
        assertEquals(emptyList<String>(), warnings(equal))
    }
```

Дополнительно (отдельные тесты): `BASELINE_NOT_PASS` для `NO_POLICY` и `NO_VERDICT`, но не для `PASS`; `POLICY_DIFFERS` для хэша против `NO_POLICY`, не для двух `NO_POLICY`; серия текущего релиза равна серии baseline не даёт `BASELINE_SERIES_DIFFERS`; незарегистрированный текущий анализ не даёт его же; совпадение профиля не меняет `USER_CONFIRMED` и числа (сравнение ответа с `releases = null` и без профилей); `PROFILE_MISMATCH` не меняет `window_comparison.status` (сравнение с тем же запросом окон).

- [ ] **Step 1.2:** Run: `.\gradlew.bat test --tests "io.ltverdict.core.BaselineComparisonTest"`. Expected: FAIL.

- [ ] **Step 1.3: Реализация** (`compareAnalyses`; блок `warnings` (`BaselineComparison.kt:156-165`) дополняется в порядке ADR; `BASELINE_SMALL_SAMPLE` из R4 уже стоит после `CURRENT_IN_CANDIDATE_SET`, `BASELINE_NOT_PASS` вставляется перед ним):

```kotlin
    val profile = releases?.let { context ->
        val baselineRelease = context.baseline
        val currentRelease = context.current
        val compared = compareReleaseProfiles(baselineRelease?.get("profile") as? JsonObject, currentRelease?.get("profile") as? JsonObject)
        if (compared == null || baselineRelease == null || currentRelease == null) null
        else JsonObject(compared + mapOf(
            "baseline_release_id" to baselineRelease.getValue("release_id"),
            "current_release_id" to currentRelease.getValue("release_id"),
        ))
    }
    val warnings = buildList {
        when {
            parsedSelection.reference == current -> add("BASELINE_IS_CURRENT_ANALYSIS")
            parsedSelection.reference.runId == current.runId -> add("BASELINE_IS_CURRENT_RUN")
        }
        if (parsedSelection.mode == Mode.STATISTICAL && parsedSelection.candidates.any { it.runId == current.runId }) {
            add("CURRENT_IN_CANDIDATE_SET")
        }
        if (baselineResult.stringOrNull("policy_verdict") != "PASS") add("BASELINE_NOT_PASS")
        if ("SMALL_SAMPLE" in coverageReasons(baselineResult)) add("BASELINE_SMALL_SAMPLE")
        val currentSeries = (releases?.current?.get("series") as? JsonPrimitive)?.content
        if (currentSeries != null && currentSeries != parsedSelection.series) add("BASELINE_SERIES_DIFFERS")
        if (baselineIdentity["policy_sha256"] != currentIdentity["policy_sha256"]) add("POLICY_DIFFERS")
        if (profile?.get("status")?.jsonPrimitive?.content == "MISMATCH") add("PROFILE_MISMATCH")
    }
```

и `put("profile", profile ?: JsonNull)` в ответ рядом с `warnings`.

- [ ] **Step 1.4:** Run: `.\gradlew.bat test --tests "io.ltverdict.core.BaselineComparisonTest"`. Expected: PASS.

### Task 2: обработчик comparison

- [ ] **Step 2.1: Красные тесты** (`LocalApiTest.kt`): после регистрации релизов (R3) comparison содержит `profile`: `null` без релизов; `MATCH` и `MISMATCH` с `differing_fields`; серии разные дают `BASELINE_SERIES_DIFFERS`; прежний `baseline.json` (записан `store.replaceBaseline(manualBaselineSelection(...))` на анализ `FAIL`) читается, comparison отвечает `200` и `BASELINE_NOT_PASS`; `GET /api/baseline` его возвращает; разные `policy_sha256` дают `POLICY_DIFFERS`; ручное копирование файла релиза (дубликат `analysis_id`) даёт `profile: null` и ответ `200`.

- [ ] **Step 2.2: Реализация** (`LocalApi.kt`, обработчик 439-469): после чтения документов

```kotlin
            val lookup = releaseOperation {
                context.store.findReleasesByAnalysis(setOf(baselineReference.getValue("analysis_id").jsonPrimitive.content, current.getValue("analysis_id").jsonPrimitive.content))
            }
            val releases = ReleaseComparisonContext(
                lookup.byAnalysis[baselineReference.getValue("analysis_id").jsonPrimitive.content],
                lookup.byAnalysis[current.getValue("analysis_id").jsonPrimitive.content],
            )
```

и передать `releases` в `compareAnalyses`. Поиск идёт одним проходом по каталогу (до 1 001 файла, вне цены чтения результатов). Ошибка реестра релизов (`CORRUPT_RELEASE_REGISTRY`, превышение предела) не должна ломать сравнение: обёртка ловит `CORRUPT_RELEASE_REGISTRY` и подставляет пустой контекст (`profile: null`), потому что профиль и серия вспомогательны (ADR 0019: предупреждения не блокируют); тест на повреждённый каталог (1 001 файл): comparison `200`, `profile: null`.

- [ ] **Step 2.3:** Run: `.\gradlew.bat test`. Документация: `docs/user/slice-1-local-analysis.md` (описание `profile`, предупреждений и их порядка), `CHANGELOG.md`. Commit: `feat(baseline): compare release profiles and warn about policy, series and verdict differences`.

**Риски R5:** (1) `BASELINE_NOT_PASS` у анализа без `policy_verdict` в синтетических тестах: помощник `result()` получает `PASS` по умолчанию (R1); (2) устойчивость к повреждённому каталогу релизов решена подстановкой пустого контекста; (3) `BASELINE_SERIES_DIFFERS` исчезает после B2 (код удаляется там).

**Documentation impact:** `docs/user/slice-1-local-analysis.md`, `CHANGELOG.md`.

---

## R6. `/analytics`: версия и профиль зарегистрированных анализов

**Цель:** для анализов, входящих в релиз, строки динамики получают `application_version` (из `label`) и `load_profile` (краткое представление шести полей профиля в фиксированном порядке); `jenkins_build` не заполняется. Отбор строк по техническому ключу не меняется (ADR 0019, раздел 8).

**Ветка:** `feat/analytics-release-fields`. **Размер:** S. **Зависит от:** R2. **Identity и контракты:** нет; значения уже существующих полей ответа.

**Что не входит:** метрики в записи релиза (отклонено ADR), `jenkins_build`, изменение `readComparisonHistory` и его пределов.

**Files:**

- Modify: `src/main/kotlin/io/ltverdict/web/LocalApi.kt` (обработчик `/analytics` 664-791: построение `SavedAnalysisForComparison` для истории, текущего и baseline-анализа)
- Modify (тесты): `LocalApiTest.kt` (динамика), `src/test/kotlin/io/ltverdict/core/AnalyticsExportTest.kt` (экранирование)
- Modify: `docs/user/saved-analytics.md`, `CHANGELOG.md`

- [ ] **Step 1: Красные тесты.** (1) Релиз с `label = "2.4.1"` и профилем `{"pacing":"10 s","load_model":"open"}` для анализа A: строка динамики A содержит `application_version = "2.4.1"` и `load_profile = "load_model=open; pacing=10 s"` (порядок полей `RELEASE_PROFILE_FIELDS`: `scenario_mix`, `environment_dataset`, `load_model`, `targets_stages`, `pacing`, `generator_limits`); у незарегистрированного анализа оба поля `null`; `jenkins_build` у всех `null`. (2) Неоднозначный `analysis_id` (дубликат файла) даёт `null`, не метку одного из файлов. (3) Релиз с повреждённым реестром (1 001 файл) не ломает `/analytics`: поля `null`, код `200`. (4) Экспорт: метка `<img src=x onerror=alert(1)>`, метка с `|`, метка с последовательностью `----` и метка с переводом строки рендерятся во всех трёх форматах (`format=html`, `asciidoc`, `confluence`) без неэкранированной разметки (в HTML и Confluence нет `<img`, есть `&lt;img`; в AsciiDoc ячейка остаётся внутри блока `[subs=specialchars]`, ограждение длиннее самой длинной серии дефисов). `notes` релиза не встречаются ни в JSON динамики, ни в одном экспорте (поиск маркера в ответах).

- [ ] **Step 2:** Run: `.\gradlew.bat test --tests "io.ltverdict.web.LocalApiTest" --tests "io.ltverdict.core.AnalyticsExportTest"`. Expected: FAIL (поля `null`).

- [ ] **Step 3: Реализация.** В обработчике до цикла по `history.entries` один раз: `val lookup = findReleasesByAnalysis(all ids)` (идентификаторы истории, текущего и baseline-анализа; повреждённый реестр даёт пустой результат, как в R5). Конструктор `SavedAnalysisForComparison(reference, run, result, identity)` получает значения через именованные аргументы `applicationVersion = release?.label`, `loadProfile = releaseProfileSummary(release?.profile)` (поля уже есть, `RunComparison.kt:20-26`); аналитика не копирует документы релиза в ответ.

- [ ] **Step 4:** Run: `.\gradlew.bat test`. Документация: `docs/user/saved-analytics.md` (откуда берутся версия и профиль, что `notes` в экспорт не попадают), `CHANGELOG.md`. Commit: `feat(analytics): show release label and profile in run dynamics`.

**Риски R6:** значения пользовательского текста впервые попадают в экспорты; экранирование уже существует (`AnalyticsExport.kt:212-236`), тест закрепляет его.

**Documentation impact:** `docs/user/saved-analytics.md`, `CHANGELOG.md`.

---

## R7. UI: вкладка «История», «Сохранить как релиз», действия строки

**Цель:** новая вкладка «История» в новой оболочке (`?shell=new`): выбор протокола, таблица последних четырёх релизов (с кнопкой «Показать все»), сохранение выбранного анализа как релиза, действия «Открыть», «Сделать baseline», «Сравнить», недоступные кнопки с причиной, пустые и ошибочные состояния. Прежний интерфейс (без флага) вкладку не получает: он остаётся до ворот фазы 4 (вопрос В7).

**Ветка:** `feat/ui-release-history`. **Размер:** L. **Зависит от:** R3 (API), R6 (числа динамики). **Identity и контракты:** нет.

**Что не входит:** подстановка профиля, знак «другой профиль», точки динамики, перепривязка (R8); коды 422 и предупреждения в BaselinePanel (R9); слоты (B3); тепловая карта подов (P4 плана платформы).

**Files:**

- Create: `ui/src/shell/HistoryPanel.vue`, `ui/src/shell/history.ts`, `ui/e2e/history.spec.ts`, `docs/user/release-history.md`
- Modify: `ui/src/shell/labels.ts` (`ShellTabKey`, `SHELL_TABS`, `HISTORY_LABELS`), `ui/src/types.ts` (раздел «релизы»), `ui/src/api.ts` (функции релизов), `ui/src/App.vue` (монтаж, `openReference`, счётчик версии baseline), `ui/src/BaselinePanel.vue` (необязательное свойство `version`, перезагрузка `loadBaseline`), `ui/e2e/shell.spec.ts` (название теста «six tabs», состав берётся из `SHELL_TABS`)
- Modify: `README.md` (ссылка в перечне руководств), `CHANGELOG.md`

**Interfaces (TypeScript):**

```ts
// types.ts
export type ReleaseAnalysisState = 'OK' | 'MISSING' | 'CORRUPT'
export interface ReleaseProfile {
  scenario_mix: string | null; environment_dataset: string | null; load_model: string | null
  targets_stages: string | null; pacing: string | null; generator_limits: string | null
}
export interface ReleaseAnalysis {
  analysis_id: string; arm: string | null; coverage_reasons: string[]; coverage_status: 'COMPLETE' | 'INCOMPLETE'
  policy_sha256: string; policy_verdict: 'PASS' | 'FAIL' | 'NO_POLICY' | 'NO_VERDICT'
  run_validity: 'VALID' | 'DEGRADED' | 'INVALID'
  analysis_state: ReleaseAnalysisState; baseline_eligible: boolean; ineligible_reasons: string[]
}
export interface Release {
  schema_version: 'local-release.v1'; release_id: string; series: string; label: string; run_id: string
  started_at: string; analyses: ReleaseAnalysis[]; profile: ReleaseProfile | null; notes: string | null
  created_at: string; updated_at: string; baseline_eligible: boolean; ineligible_reasons: string[]
}
export interface ReleaseList {
  releases: Release[]; next_after: string | null; series_summary: Array<{ series: string; count: number }>
  corrupt_count: number; corrupt_names: Array<{ name: string; reason: string }>
}
export interface ReleaseRequest {
  series: string; label: string; run_id: string; analyses: Array<{ analysis_id: string }>
  profile: ReleaseProfile | null; notes: string | null
}
export type ReleaseUpdate = Omit<ReleaseRequest, 'series' | 'run_id'>

// api.ts
export function listReleases(query?: { series?: string; after?: string; limit?: number }): Promise<ReleaseList>
export function getRelease(releaseId: string): Promise<Release>
export function createRelease(body: ReleaseRequest): Promise<Release>
export function updateRelease(releaseId: string, body: ReleaseUpdate): Promise<Release>   // method: 'PUT'
export function deleteRelease(releaseId: string): Promise<{ release: null }>
```

Функции `api.ts` строятся по образцу `setBaseline`/`clearBaseline` (`mutationHeaders({'Content-Type': 'application/json'})`), строка запроса через `URLSearchParams`.

### Task 1: чистые помощники и словарь

- [ ] **Step 1.1:** Создать `ui/src/shell/history.ts` (только ASCII, русские строки через `labels.ts`):

```ts
import type { Release, ReleaseAnalysis } from '../types'

export const DEFAULT_VISIBLE_RELEASES = 4

export type ProfileRelation = 'match' | 'mismatch' | 'unknown'

const PROFILE_FIELDS = ['scenario_mix', 'environment_dataset', 'load_model', 'targets_stages', 'pacing', 'generator_limits'] as const

// Same rule as the server (compareReleaseProfiles): unknown unless both releases declare a profile.
export function profileRelation(a: Release, b: Release): ProfileRelation {
  if (!a.profile || !b.profile) return 'unknown'
  return PROFILE_FIELDS.every((name) => a.profile![name] === b.profile![name]) ? 'match' : 'mismatch'
}

export function profileSummary(release: Release): string | null {
  if (!release.profile) return null
  const parts = PROFILE_FIELDS.flatMap((name) => (release.profile![name] ? [`${name}=${release.profile![name]}`] : []))
  return parts.join('; ')
}

// Rows arrive newest first; the table shows the newest N unless the user expands it.
export function visibleReleases(releases: readonly Release[], showAll: boolean): readonly Release[] {
  return showAll ? releases : releases.slice(0, DEFAULT_VISIBLE_RELEASES)
}

export interface BaselineChoice { release: Release; analysis: ReleaseAnalysis }

// Newest eligible release older than the opened one with a MATCHING declared profile; same arm only.
// Two releases without a declared profile are not a match (ADR 0019, section 4): no suggestion until a profile is declared.
export function suggestManualBaseline(releases: readonly Release[], opened: Release, arm: string | null): BaselineChoice | null {
  for (const release of releases) {
    if (release.release_id >= opened.release_id || release.series !== opened.series) continue
    if (profileRelation(release, opened) !== 'match') continue
    const analysis = release.analyses.find((item) => item.arm === arm && item.baseline_eligible)
    if (analysis) return { release, analysis }
  }
  return null
}

export interface StatisticalAvailability { available: boolean; eligibleRuns: number; needed: number }

// Statistical selection needs 3..20 eligible releases of one DECLARED profile and arm from distinct runs;
// the opened release must declare the profile too.
export function statisticalAvailability(releases: readonly Release[], opened: Release, arm: string | null): StatisticalAvailability {
  const runs = new Set<string>()
  for (const release of releases) {
    if (release.series !== opened.series || profileRelation(release, opened) !== 'match') continue
    if (release.analyses.some((item) => item.arm === arm && item.baseline_eligible)) runs.add(release.run_id)
  }
  return { available: runs.size >= 3, eligibleRuns: runs.size, needed: 3 }
}
```

- [ ] **Step 1.2:** Добавить в `labels.ts` вкладку `{ key: 'history', label: 'История', pending: false }` после `compare` (тип `ShellTabKey` расширить значением `'history'`) и словарь `HISTORY_LABELS` (русские строки): заголовок, подпись «Диагностическая запись о релизах; метрики берутся из сохранённых анализов», подписи колонок, кнопки («Открыть», «Сделать baseline», «Сравнить», «Показать все релизы», «Показать последние 4»), состояния («Релизов этого протокола нет», «История недоступна: есть повреждённые записи: N», «Анализ не найден», «Нет чисел: выше предел сканирования истории», «Нет чисел: анализ создан по другим правилам»), причины недоступности (`BASELINE_CANDIDATE_INVALID` → «Прогон разобран не полностью», `BASELINE_CANDIDATE_INCOMPLETE` → «Анализ неполный», `BASELINE_CANDIDATE_NOT_PASS` → «Вердикт не PASS», `ANALYSIS_MISSING` → «Анализ не найден», `ANALYSIS_CORRUPT` → «Анализ повреждён»), подсказка про статистический режим («Нужно не менее трёх релизов PASS одного профиля из разных прогонов; на 3-4 прогонах оценка слабая (ADR 0017)»), тексты формы сохранения, коды ошибок API (`RELEASE_LIMIT_REACHED` с подстановкой предела из `error.limit`, `RELEASE_ANALYSIS_ALREADY_REGISTERED`, `RELEASE_TOO_LARGE`, `RELEASE_ARM_CONFLICT`, `RELEASE_RESULT_TOO_LARGE`, `RELEASE_ANALYSIS_NO_RUN_METADATA`, `CORRUPT_RELEASE_REGISTRY`).

- [ ] **Step 1.3:** Run: `npm --prefix ui run typecheck`, `npm --prefix ui run lint`. Expected: PASS. Commit: `feat(ui): add release history types, client and helpers`.

### Task 2: панель

- [ ] **Step 2.1: Красные e2e** (`ui/e2e/history.spec.ts`; сервер e2e общий, каталог данных один на все файлы: у каждого теста своя серия `history-<тест>-<Date.now()>`, `beforeEach` удаляет все релизы: страницы `GET /api/releases?limit=100` проходятся по `next_after` до `null` (одной страницы мало, остатки ломают предел и порядок строк), и `DELETE` с заголовками `Origin` и `X-LTV-CSRF`, как сброс baseline в `baseline.spec.ts:30-36`, и сбрасывает baseline). Анализы создаются через вкладку «Новый анализ» (`input-file`, `policy-file`, `start-analysis`, `overview-panel`, по образцу `new-analysis-live.spec.ts:23-36`) с разрешающей и с проваливающей политикой (константы из R1). Сценарии:

1. `empty series shows the empty state`: открыть `?shell=new`, вкладка «История» (`#shell-tab-history`), панель `[data-testid="history-panel"]`, текст пустого состояния.
2. `a finished analysis is saved as a release and listed newest first`: два анализа разных запусков (разные временные метки входа), «Сохранить как релиз» с серией и меткой; таблица `[data-testid="history-table"]` показывает строки в порядке новые сверху, колонка «Вердикт» содержит `PASS`; по умолчанию не больше четырёх строк, пять релизов дают кнопку «Показать все релизы» и после неё пять строк.
3. `hostile label is text`: метка `<img src=x onerror=window.__pwn=1>` показана текстом; `window.__pwn` не определён; дубль метки допустим, различаются датой и коротким `release_id`.
4. `baseline can be assigned only from an eligible release`: у релиза с `FAIL` кнопка «Сделать baseline» `disabled` и связана `aria-describedby` с текстом «Вердикт не PASS»; у релиза `PASS` кнопка выбирает baseline, вкладка «Сравнение» показывает `data-testid="baseline-selection"` с этим анализом без перезагрузки страницы.
5. `compare and open act on the release analysis`: «Открыть» делает анализ выбранным (вердикт на «Обзоре»), «Сравнить» открывает вкладку «Сравнение» с тем же анализом.
6. `damaged records are reported`: порчу файла из браузера не воспроизвести, поэтому сценарий покрыт интеграционным тестом API (R3); в e2e проверяется только текст сообщения при `corrupt_count > 0`, для чего ответ `GET /api/releases` подменяется через `page.route` (единственный мок в файле: тот же ответ с `corrupt_count: 2`).
7. `accessibility`: `AxeBuilder` на панели без нарушений (как в `shell.spec.ts`).

`ui/e2e/shell.spec.ts`: название теста «seven tabs», остальное берётся из `SHELL_TABS` (assertion `toHaveText(SHELL_TABS.map(...))` уже динамический).

- [ ] **Step 2.2:** Run: `npm --prefix ui run e2e -- e2e/history.spec.ts`. Expected: FAIL (нет вкладки).

- [ ] **Step 2.3: Реализация.** Компонент `ui/src/shell/HistoryPanel.vue` (`<script setup lang="ts">`, свойства `{ selection: AnalysisReference | null; working: boolean }`, события `open({ release, analysis })`, `compare({ release, analysis })` (обе полезные нагрузки типа `{ release: Release; analysis: ReleaseAnalysis }`), `baseline-changed`). Структура:

- Заголовок `h2`, подпись о назначении, выбор протокола (`<select data-testid="history-series">` из `series_summary` плюс пункт ввода нового имени).
- `status`/`alert` области (`role="status"` для загрузки и пустого состояния, `role="alert"` для ошибок) с теми же приёмами отмены устаревших ответов, что в `BaselinePanel.vue` (счётчик ревизии `revision`, игнорирование ответа старой ревизии).
- Таблица: `caption`, заголовки колонок `scope="col"`, строка релиза: `label` и короткий `release_id` (последние 8 символов), дата теста (`started_at`, UTC явно), вердикты по анализам (текст `arm: VERDICT`, цвет только дополняет текст), профиль (`profileSummary` или «не заявлен»), числа p95/ошибки/RPS из динамики (`getSavedAnalytics(anchor, 100, '', 1)`, где `anchor` ссылка анализа самого нового релиза; строка без совпадения в `dynamics.rows` показывает причину, а не ноль), действия. Таблица в контейнере с `tabindex="0"` и `role="region"` (правило из `table-focus.spec.ts`).
- Форма «Сохранить как релиз» (видна, если `selection` не `null`): `series` (input с `datalist` из `series_summary`), `label`, шесть полей профиля (пустые по умолчанию; подстановка в R8), `notes` (`textarea`, 1 024 байта), кнопка. После `201` список перезагружается, фокус на новую строку.
- «Показать все»: `listReleases({series, limit: 100})` и страницы по `next_after`.
- Кнопка «Сделать baseline» вызывает `setBaseline({mode: 'manual', series: release.series, reference})`, затем `emit('baseline-changed')`; ошибка 422 показывается текстом кода из `HISTORY_LABELS`/`BASELINE_ERROR_LABELS` (R9) либо серверным сообщением.

В `App.vue`: `<HistoryPanel v-if="apiReady && shellNew" v-show="shownIn('history')" :selection="selectedReference" ... @open="openReference($event.release, $event.analysis, 'overview')" @compare="openReference($event.release, $event.analysis, 'compare')" @baseline-changed="baselineVersion += 1" />`; `openReference(release, analysis, tab)` собирает `RunSummary` из `runs.value` либо из `run_id` (`source_type` и `sha256` разбираются из строки, `original_filename` пусто, `size_bytes` 0), вызывает `selectRun`, затем `selectAnalysis` с `AnalysisSummary`, собранным из копий записи (`analysis_id`, `policy_sha256`, `policy_verdict`, `run_validity`), и переключает `activeTab = tab`. `BaselinePanel` получает `:version="baselineVersion"`; в нём `watch(() => props.version, loadBaseline)`.

- [ ] **Step 2.4:** Run: `npm --prefix ui run typecheck`, `lint`, `build`, `e2e` (весь набор; сервер e2e общий). Expected: PASS.

- [ ] **Step 2.5: Документация.** `docs/user/release-history.md` (новое руководство: что такое протокол и релиз, как сохранить, как выбрать baseline из истории, ограничения: до 1 000 записей, до 8 анализов, числа берутся из динамики и могут отсутствовать при пределе сканирования, заметки не экспортируются, перепривязка после смены правил - в R8); `README.md` (строка в перечень руководств); `CHANGELOG.md` (`Added`). Run: `npx --yes markdownlint-cli2@0.23.2 docs/user/release-history.md README.md CHANGELOG.md`. Commit: `feat(ui): add the release history tab`.

**Риски R7:** (1) `openReference` не знает `original_filename` запуска, если запуска нет на загруженной странице списка: заголовок показывает пустое имя до `refreshRuns`; допустимо, фиксируется как известное ограничение; (2) числа динамики для анализов, чьи документы крупнее 8 MiB, отсутствуют (`history_scan_truncated` или строки нет): типичный 4-8-часовой тест с рядами ресурсов даёт результат 32-55 МБ (ADR 0014), поэтому на реальном стенде таблица может остаться без чисел: см. «Риски» и вопрос В10; (3) общий каталог данных e2e: сброс релизов в `beforeEach` обязателен, иначе предел 1 000 и порядок строк зависят от соседних тестов.

**Documentation impact:** `docs/user/release-history.md`, `README.md`, `CHANGELOG.md`.

---

## R8. UI: профиль, точки динамики, перепривязка анализа

**Цель:** форма сохранения подставляет профиль предыдущего релиза серии для явного подтверждения; таблица показывает знак «другой профиль»; над таблицей график p95 по релизам с точками по verdict; действие «Перепривязать» заменяет анализ релиза новым анализом того же запуска (путь возобновления после смены правил, см. «Перепривязка после смены identity»).

**Ветка:** `feat/ui-release-profile-dynamics`. **Размер:** M. **Зависит от:** R7, R5 (ответ comparison несёт `profile`; клиентская функция `profileRelation` зеркалит правило сервера). **Identity и контракты:** нет.

**Что не входит:** серверная блокировка по профилю, автоматическая перепривязка, рисование рядов нагрузки, любые пересчёты метрик.

**Files:**

- Modify: `ui/src/shell/HistoryPanel.vue`, `ui/src/shell/history.ts`, `ui/src/shell/labels.ts` (`HISTORY_LABELS`), `ui/e2e/history.spec.ts`
- Create (если график выносится): `ui/src/shell/HistoryDynamics.vue` (SVG, без новых зависимостей; образцы - `SharedCursorChart.vue`)
- Modify: `docs/user/release-history.md`, `CHANGELOG.md`

- [ ] **Step 1: Красные e2e и юнит-проверки** (`history.spec.ts`, `history.ts` покрывается через e2e: отдельного модульного раннера в `ui/` нет, поэтому правила проверяются через страницу):

1. `profile is prefilled for confirmation, never carried silently`: первый релиз серии сохранён с профилем (`pacing: "10 s"`); при выборе той же серии в форме поля профиля заполнены, виден текст «Подставлен профиль релиза ...; проверьте», кнопка сохранения недоступна, пока не отмечен `[data-testid="profile-confirm"]` («Условия теста соответствуют заявленному профилю»); изменение любого поля снимает подстановку, но флажок остаётся обязательным при непустом профиле; пустой профиль сохраняется без флажка (`profile: null`).
2. `a different profile is flagged against the newest release`: второй релиз с `pacing: "20 s"`: строка старшего релиза помечена знаком «другой профиль» с подписью, какой релиз взят за эталон сравнения (самый новый в таблице); релиз без профиля помечен «профиль не заявлен», знака «другой профиль» нет.
3. `statistical selection explains why it is unavailable`: при двух релизах `PASS` текст «Нужно не менее трёх релизов PASS одного профиля из разных прогонов; сейчас 2»; при трёх подходящих кнопка «Статистический baseline» доступна и открывает вкладку «Сравнение» (выбор кандидатов остаётся в `BaselinePanel`, панель истории предзаполнять кандидатов не обязана).
4. `suggestions need a declared profile`: два релиза `PASS` без профиля не дают ни предложения, ни доступного статистического режима (подсказка «Заявите профиль релиза, чтобы получить предложение»); после заявления одинакового профиля предложение появляется. `manual suggestion`: у строки есть подпись «Предлагаемый baseline», у самого нового релиза с подходящим предыдущим (`suggestManualBaseline`) на предыдущем релизе; предложение не выбирается автоматически (нет автоматического продвижения, ADR 0004).
5. `dynamics points carry verdict as text and shape`: график p95 (`[data-testid="history-dynamics"]`) содержит `circle` для `PASS`, `rect` для `FAIL`, `polygon` для прочих и текстовую легенду; та же информация есть в таблице (график не единственный носитель).
6. `re-pin after re-analysis`: анализ того же запуска с другой политикой создан заново; в строке релиза действие «Перепривязать анализ» открывает выбор из анализов этого запуска (`listAnalyses`), `PUT` заменяет `analyses`, строка показывает новые вердикты; попытка выбрать анализ другого релиза показывает `RELEASE_ANALYSIS_ALREADY_REGISTERED` текстом.

- [ ] **Step 2: Реализация.** В форме: при смене серии `listReleases({series, limit: 1})`; если вернулась запись с профилем, поля заполняются и показывается текст подстановки (`HISTORY_LABELS.profilePrefilled(label)`); флажок `profile-confirm` входит в условие кнопки при хотя бы одном непустом поле. Знак «другой профиль» вычисляется `profileRelation(row, newest)` (первая строка таблицы). График: точки по возрастанию `started_at`, ось Y p95 из строки динамики (`DynamicsMetric` с `metric == 'response_time_p95_ms'`), форма точки по verdict; релиз без числа не рисуется и объясняется в подписи. «Перепривязать анализ»: список `listAnalyses(run_id)` (страницы по 25 через `after`, кнопка «Ещё анализы», как в боковой панели), выбор одного, `updateRelease(release_id, { label, analyses: [{analysis_id}], profile, notes })`; для релиза с несколькими плечами действие выключено с подсказкой (после P1 выбирается анализ на каждое плечо; вне объёма МВП-среза).
- [ ] **Step 3:** Run: `npm --prefix ui run typecheck`, `lint`, `build`, `e2e`. Документация: раздел «Перепривязка» в `docs/user/release-history.md`; `CHANGELOG.md`. Commit: `feat(ui): confirm release profiles, plot dynamics and re-pin analyses`.

**Риски R8:** (1) график и таблица зависят от динамики, чей набор строк ограничен техническим ключом: релизы, посчитанные по другим правилам, не получают чисел и подписываются «другие правила анализа» (подсказка уже есть в `oldRulesHint`); (2) перепривязка многоплечевых релизов отложена до P1.

**Documentation impact:** `docs/user/release-history.md`, `CHANGELOG.md`.

---

## R9. UI: BaselinePanel показывает коды 422, предупреждения и профиль

**Цель:** `BaselinePanel.vue` объясняет новые отказы выбора baseline русским текстом, показывает новые предупреждения в порядке сервера и профиль пары, блокирует кнопку выбора с причиной для заведомо неподходящего анализа (сервер всё равно проверяет сам).

**Ветка:** `feat/ui-baseline-codes`. **Размер:** M. **Зависит от:** R1, R4, R5; согласуется с U5a и U5b плана UI (`docs/superpowers/plans/2026-10-04-ui-u3-u7.md`: U5a переводит `BaselinePanel.vue` на русский с параметром `labels`, U5b добавляет коды ADR 0019 и малую выборку после R4). Если U5a влит раньше, R9 правит уже переведённую панель; строки предупреждений `BASELINE_LABELS.warnings` при этом добавляют серверные срезы R4 и R5 в своих PR (договорённость плана UI), а R9 берёт на себя только блокировку кнопки с причиной, строку профиля пары, `BASELINE_ERROR_LABELS` и тесты порядка. **Identity и контракты:** нет.

**Что не входит:** слоты (B3), блокировка сравнения при `PROFILE_MISMATCH` (отклонено), выбор кандидатов из истории (R8).

**Files:**

- Modify: `ui/src/shell/labels.ts` (`BASELINE_LABELS.warnings` и новый `BASELINE_ERROR_LABELS`), `ui/src/BaselinePanel.vue`, `ui/src/types.ts` (`BaselineComparison.profile`, `BaselineComparisonWarning`), `ui/src/App.vue` (передача фактов выбранного анализа), `ui/e2e/baseline.spec.ts`
- Modify: `docs/user/slice-1-local-analysis.md`, `CHANGELOG.md`

- [ ] **Step 1: Красные e2e** (`baseline.spec.ts`): (1) анализ с проваливающей политикой открыт: кнопка «Set as baseline» `disabled`, рядом текст «Вердикт не PASS: ...» (`aria-describedby`); прямой вызов `POST /api/baseline` через `page.request` возвращает `422 BASELINE_CANDIDATE_NOT_PASS` (сервер остаётся источником правды). (2) Сравнение двух анализов с разными политиками (две разрешающие политики с разными `policy_id`, получаются разные `policy_sha256`) показывает предупреждение `POLICY_DIFFERS` текстом. (3) Два релиза (через `page.request.post('/api/releases')` с заголовками `Origin` и `X-LTV-CSRF`) с разными профилями показывают `PROFILE_MISMATCH` и строку «Профиль: различается: pacing». (4) Порядок предупреждений в DOM совпадает с порядком ответа. (5) Известное сообщение `BASELINE_MIXED_SEMANTICS` остаётся (строка из R1).

- [ ] **Step 2: Реализация.** `BASELINE_LABELS.warnings` получает `BASELINE_NOT_PASS`, `BASELINE_SMALL_SAMPLE`, `BASELINE_SERIES_DIFFERS`, `POLICY_DIFFERS`, `PROFILE_MISMATCH` с русскими текстами (например `POLICY_DIFFERS`: «Политики анализов различаются: PASS относится к политике своего анализа.»). `BASELINE_ERROR_LABELS` отображает `BASELINE_CANDIDATE_INVALID`, `INCOMPLETE`, `NOT_PASS`, `TOO_LARGE` в текст; `showError` берёт текст по `errorCode`, а при неизвестном коде серверное сообщение. Правило допуска на клиенте (`baselineIneligibility(facts)` в `history.ts`) зеркалит `baselineCandidateRejection` (три первых кода и `SMALL_SAMPLE`) и используется для блокировки кнопки; `App.vue` передаёт `result.value` поля (`run_validity`, `policy_verdict`, `analysis_coverage`). Профиль пары выводится строкой под таблицей метрик.
- [ ] **Step 3:** Run: `npm --prefix ui run typecheck`, `lint`, `build`, `e2e -- e2e/baseline.spec.ts`. Документация, `CHANGELOG.md`. Commit: `feat(ui): explain baseline refusals and show comparison warnings`.

**Риски R9:** конфликт с U5 (экран сравнения плана UI U3-U7): `BaselinePanel.vue` и `labels.ts` правят оба; порядок слияния согласуется владельцем очереди UI-срезов. Клиентское правило допуска может разойтись с сервером: тест в `history.ts` перебирает таблицу из R1 и сверяет коды.

**Documentation impact:** `docs/user/slice-1-local-analysis.md`, `CHANGELOG.md`.

---

## B1. Слоты baseline `(series, arm)`: хранилище

**Цель:** активный baseline хранится по паре `(series, arm)` в `<data>/baselines/<sha256 канонического {series, arm}>.json` (до `MAX_BASELINE_SLOTS = 64`, файл до 32 KiB, содержимое остаётся `local-baseline.v1`); прежний `<data>/baseline.json` читается как legacy-слот; выбор слота, чтение ссылки и чтение или удаление записей условий выполняются одной операцией хранилища под одним `operationLock` (ADR 0019, раздел 7).

**Ветка:** `feat/baseline-slots-store`. **Размер:** L. **Зависит от:** R1 (читатель identity), R2 (порядок правок `RunBundleStore.kt`). **Identity и контракты:** identity не меняется; формат `local-baseline.v1` и `local-baseline-conditions.v1` не меняются; новый каталог `<data>/baselines/`.

**Что не входит:** маршруты (B2), UI (B3), миграция legacy-файла (он читается как есть и затеняется при перезаписи ключа), правило технического ключа с `resource_arm` (принадлежит P1 ADR 0014).

**Files:**

- Modify: `src/main/kotlin/io/ltverdict/storage/RunBundleStore.kt` (слоты, одна операция, `readAnalysisIdentity`, предел записей условий)
- Modify (тесты): `src/test/kotlin/io/ltverdict/storage/RunBundleStoreTest.kt`

**Interfaces (`package io.ltverdict.storage`):**

```kotlin
internal data class BaselineSlot(val series: String, val arm: String?, val selection: JsonObject, val legacy: Boolean)
internal const val MAX_BASELINE_SLOTS = 64
internal const val MAX_BASELINE_CONDITION_FILES = 4_096

RunBundleStore.readAnalysisIdentity(runId: String, analysisId: String): JsonObject?            // identity.json, проверен по analysis_id; null, если анализа нет
RunBundleStore.listBaselineSlots(): List<BaselineSlot>                                         // эффективные слоты (файлы слотов и не затенённый legacy)
RunBundleStore.readBaselineSlotWithCondition(
    series: String?, arm: String?, currentReference: JsonObject, windows: WindowComparisonRequest?,
): Pair<BaselineSlot?, JsonObject?>                                                            // series == null: только legacy-файл, как сегодня
RunBundleStore.replaceBaselineSlot(selection: JsonObject, arm: String?): BaselineSlot          // IAE("BASELINE_SLOTS_LIMIT_REACHED")
RunBundleStore.clearBaselineSlot(series: String, arm: String?): Boolean                        // адресное удаление
RunBundleStore.clearBaseline()                                                                 // очистка legacy-файла и ТОЛЬКО его записей условий, не используемых другим слотом
```

Ключ слота: `sha256Hex(canonicalJson({"arm": <строка|null>, "series": <строка>}))`. Плечо слота при записи берёт API из `identity.resource_arm` анализа baseline (`null`, пока P1 не введён); при чтении плечо слота выводится заново из identity ссылки и сверяется с именем файла (расхождение: `CORRUPT_BASELINE`).

### Task 1: ключ, слот-файлы, эффективный набор

- [ ] **Step 1.1: Красные тесты** (`RunBundleStoreTest.kt`; помощники `saveAnalysis` из R1, `manualBaselineSelection`):

1. `slot key depends on series and arm and nothing else`: `replaceBaselineSlot` для `("a", null)`, `("a", "blue")`, `("b", null)` создаёт три разных файла `baselines/<64 hex>.json`; повторная запись `("a", null)` заменяет файл, а не добавляет; `arm = null` и `arm = "null"` дают разные ключи; файл байт-в-байт равен `canonicalJson(selection)` и ≤ 32 KiB.
2. `legacy file is a slot until its key is written`: `replaceBaseline(...)` (прежний писатель) кладёт `baseline.json` на `("legacy-series", null)`; `listBaselineSlots()` возвращает его с `legacy = true`; запись слота другой серии не трогает legacy; запись слота `("legacy-series", null)` создаёт файл слота и **затем** удаляет `baseline.json`; повторное чтение возвращает один слот.
3. `a shadowed legacy file loses to the slot of the same key`: оба файла одного ключа (слот записан в обход хранилища, как после остановки между шагами): `listBaselineSlots()` возвращает только слот; `baseline.json` остаётся на диске до следующей мутации ключа и удаляется ей; `clearBaselineSlot` удаляет сначала legacy-файл ключа, затем слот (после первого шага «воскрешения» из legacy нет).
4. `slot limit counts effective keys`: 64 разных слота проходят, 65-й даёт IAE `BASELINE_SLOTS_LIMIT_REACHED`, замена существующего ключа при 64 проходит; **64 файла слотов плюс прежний `baseline.json` другого ключа** уже дают 65 эффективных слотов, поэтому новый ключ отвергается, а при затенённом legacy (тот же ключ) 64 файла остаются в пределе; лишний файл в `baselines/` или каталог/ссылка ломает чтение (`CORRUPT_BASELINE`), 66 элементов тоже.
5. `slot arm is rederived from the baseline analysis identity`: анализ с `resource_arm = "blue"` (помощник `saveAnalysis(arm = "blue")`) записывается слотом `("s", "blue")`; `listBaselineSlots()` возвращает `arm = "blue"`; слот-файл, чьё имя не равно ключу от `(series, arm из identity)`, даёт `CORRUPT_BASELINE`; отсутствие анализа слота даёт `CORRUPT_BASELINE`, как у legacy.
6. `identity read is small and verified`: `readAnalysisIdentity` возвращает identity, не читая результат (подмена результата не мешает); подмена identity при сохранённом размере даёт `CORRUPT_RUN_BUNDLE`; неизвестный анализ `null`.
7. `slot survives reopen and writes are atomic`: после `DataDirectory.open` заново слоты те же; `.staging` пуст после каждой операции и после отказа (65-й слот, неверный выбор).

- [ ] **Step 1.2:** Run: `.\gradlew.bat test --tests "io.ltverdict.storage.RunBundleStoreTest"`. Expected: FAIL.

- [ ] **Step 1.3: Реализация.** Каталог слотов и операции повторяют образец `replaceBaselineCondition` (создание каталога с `forceDirectory(root)`, UUID-staging, `writeForced`, `ATOMIC_MOVE` с `REPLACE_EXISTING`, `forceDirectory`). Эффективный набор:

```kotlin
    private fun effectiveSlotsUnlocked(): List<BaselineSlot> {
        val slots = slotFilesUnlocked()                              // ≤ MAX_BASELINE_SLOTS + 1 элементов, иначе corruptBaseline
            .map { (name, selection) -> slotFor(selection, fromFile = name) }
        val legacy = readBaselineUnlocked()?.let { slotFor(it, fromFile = null).copy(legacy = true) }
        return if (legacy == null || slots.any { it.key() == legacy.key() }) slots else slots + legacy
    }
```

`slotFor` читает только `identity.json` ссылки (`readIdentityUnlocked`: файл до 8 MiB, SHA-256 равен `analysis_id`, привязка `run_id`; без обхода каталога анализа и без манифеста) и берёт `resource_arm`; для файла слота сверяет имя файла с ключом. Число эффективных слотов считается после затенения (файлы слотов плюс не затенённый legacy не больше 64). `replaceBaselineSlot`: под замком проверка предела (число эффективных слотов, новый ключ при 64), запись, затем удаление legacy-файла того же ключа. Безадресный `clearBaseline()` удаляет legacy-файл и только записи условий, ссылающиеся на его выбор и не используемые эффективными слотами (то же правило, что у адресной очистки): прежний обход «удалить все записи условий» после появления слотов стёр бы подтверждения другого слота. `clearBaselineSlot`: порядок «legacy того же ключа, затем записи условий, затем файл слота» (потеря подтверждения безопасна: пара остаётся «не подтверждена»; потеря удаления повторяется пользователем). Записи условий удаляются, только если их `baseline`-ссылка равна ссылке удаляемого выбора и не используется другими эффективными слотами; перечисление каталога условий ограничено `MAX_BASELINE_CONDITION_FILES` (больше: `corruptBaseline("too many condition records")`), нечитаемые файлы условий остаются на месте. `replaceBaselineCondition` для **нового** ключа при заполненном каталоге условий даёт IAE `BASELINE_CONDITIONS_LIMIT_REACHED`; замена существующей записи проходит (вопрос В5). `readBaselineSlotWithCondition`: один `synchronized`: выбор слота (`series == null`: только legacy-файл), построение `baselineConditionBinding(ссылка слота, currentReference, windows)`, чтение записи условий.

- [ ] **Step 1.4:** добавить тесты 8-10: (8) `scoped delete keeps conditions used by another slot`: два слота разных серий ссылаются на один анализ, запись условий для пары (ссылка, текущий) переживает удаление первого слота и исчезает с удалением второго; записи условий другого baseline не затронуты; (9) `condition directory is bounded`: засев `MAX_BASELINE_CONDITION_FILES` корректных записей, новый ключ даёт `BASELINE_CONDITIONS_LIMIT_REACHED`, замена существующего проходит; (10) `legacy clear keeps slots and shared conditions`: `clearBaseline()` удаляет только legacy-файл и его записи условий; запись условий общей пары (legacy и новый слот ссылаются на один анализ) остаётся; (11) `slot scan stays short`: 64 слота и legacy читаются за разумное время, а параллельный `listRuns` отвечает, пока идёт `listBaselineSlots` (замер выводится в описание PR). Run: `.\gradlew.bat test --tests "io.ltverdict.storage.RunBundleStoreTest"`, `ktlintCheck`. Expected: PASS. Commit: `feat(storage): keep one active baseline per series and arm`.

**Риски B1:** (1) чтение каждого слота разбирает `identity.json` (небольшой файл, хэш по `analysis_id`): до 65 слотов на операцию под замком, оценка единицы миллисекунд, замеряется тестом 11; (2) 4 096 записей условий (до 16 MiB) читаются при адресном удалении под замком: потолок записан на запись и в ADR 0019 раздел 7 не упомянут (вопрос В5); (3) файл слота без поля плеча хранит плечо только в ключе: порча identity анализа делает слот нечитаемым, как у legacy.

**Documentation impact:** `docs/architecture/slice-1-local-runtime.md` обновляется в B2.

---

## B2. Слоты в API: comparison, условия, адресный DELETE

**Цель:** `POST /api/baseline` пишет слот; comparison и baseline-conditions выбирают слот по серии релиза текущего анализа (или явному `series`) и плечу из его identity; `GET /api/baseline` добавляет `baselines`; `DELETE /api/baseline` принимает `series` и `arm`; предупреждение `BASELINE_SERIES_DIFFERS` удаляется (после слотов оно недостижимо, ADR 0019, раздел 5).

**Ветка:** `feat/baseline-slots-api`. **Размер:** L. **Зависит от:** B1, R5. **Identity и контракты:** identity не меняется; приватный API: `baselines` в ответе, `series`/`arm` в `DELETE`, `series` в comparison и conditions, коды `BASELINE_SERIES_CONFLICT`, `BASELINE_SLOTS_LIMIT_REACHED`, `BASELINE_CONDITIONS_LIMIT_REACHED`.

**Что не входит:** UI (B3), автоматический выбор единственного слота для несвязанного анализа (ADR: без `series` для незарегистрированного анализа читается только legacy-файл; вопрос В6).

**Files:**

- Modify: `src/main/kotlin/io/ltverdict/web/LocalApi.kt` (baseline-маршруты 384-403, conditions 405-441, comparison 443-473, `selectBaseline`)
- Modify: `src/main/kotlin/io/ltverdict/core/BaselineComparison.kt` (удаление `BASELINE_SERIES_DIFFERS`)
- Modify (тесты): `LocalApiTest.kt`, `BaselineComparisonTest.kt`, `ui/e2e/baseline.spec.ts` (общий сброс слотов в `beforeEach`)
- Modify: `docs/architecture/slice-1-local-runtime.md`, `docs/user/slice-1-local-analysis.md`, `CHANGELOG.md`

**Interfaces (API):**

| Маршрут | Поведение после B2 |
| --- | --- |
| `GET /api/baseline` | поле `baseline` хранит содержимое legacy-файла либо `null`; поле `baselines` - массив `{series, arm, source, baseline}`, где `source` равен `SLOT` или `LEGACY`, а `baseline` - выбор `local-baseline.v1` |
| `POST /api/baseline` | как раньше по телу; пишет слот `(series, arm из identity.resource_arm выбранного/победившего анализа)`; ответ `{baseline: <selection>}`; предел слотов даёт `422 BASELINE_SLOTS_LIMIT_REACHED` с `error.limit` |
| `DELETE /api/baseline[?series=&arm=]` | без параметров: очистка legacy-файла и его записей условий; `series` (и необязательный `arm`): адресное удаление слота и затенённого legacy; `arm` без `series` даёт `400` |
| `GET .../comparison`, `GET\|POST .../baseline-conditions` | необязательный `series`; серия берётся из релиза текущего анализа; заданная `series`, противоречащая серии релиза, даёт `422 BASELINE_SERIES_CONFLICT`; без релиза и без `series` читается legacy-файл |

Серия в `POST /api/baseline` и в `series` нормализуется `normalizeReleaseText` (NFC, обрезка), чтобы ключ слота и серия релиза совпадали; пустая после нормализации даёт `400`, как сейчас.

- [ ] **Step 1: Красные тесты** (`LocalApiTest.kt`): (1) два baseline в двух сериях живут одновременно: `GET /api/baseline` перечисляет оба слота (`source: "SLOT"`); перезапись одной серии не трогает другую; (2) comparison анализа из релиза серии A берёт слот A, серии B слот B (проверка по `baseline.series` и `reference` в ответе); без релиза и без `series` на пустом хранилище `404`, с legacy-файлом comparison как сегодня; явный `series` для незарегистрированного анализа выбирает слот; `series`, противоречащая серии релиза, даёт `422 BASELINE_SERIES_CONFLICT`; (3) `BASELINE_SERIES_DIFFERS` в `warnings` не появляется (тест R5 о коде удаляется), остальные предупреждения без изменений; (4) условия: `POST .../baseline-conditions` для пары слота A сохраняется и читается; `DELETE /api/baseline?series=A` не удаляет условия слота B (разные ссылки) и удаляет условия A, а условия, общие с другим слотом, остаются; (5) legacy: `store.replaceBaseline(...)` затем `POST /api/baseline` той же серии и плеча заменяет слот и удаляет legacy-файл; `DELETE /api/baseline` без параметров очищает legacy-файл, не слоты; `arm` без `series` даёт `400`; (6) предел: 64 слота проходят, 65-й даёт `422 BASELINE_SLOTS_LIMIT_REACHED`, `error.limit == 64`; (7) серия `" Checkout "` и `"Checkout"` дают один слот; (8) плечо: анализ с `resource_arm` (прямая запись identity в `store`) пишет слот с этим плечом; comparison анализа другого плеча с релизом той же серии не находит слот (`404`), а не сравнивает чужие плечи.

- [ ] **Step 2:** Run: `.\gradlew.bat test --tests "io.ltverdict.web.LocalApiTest" --tests "io.ltverdict.core.BaselineComparisonTest"`. Expected: FAIL.

- [ ] **Step 3: Реализация.** Общая функция выбора для трёх обработчиков:

```kotlin
private suspend fun resolveBaselineScope(call: ApplicationCall, store: RunBundleStore, current: JsonObject): Pair<String?, String?> {
    val identity = baselineOperation { store.readAnalysisIdentity(current.baselineString("run_id"), current.baselineString("analysis_id")) }
        ?: notFound("Referenced analysis was not found")
    val arm = (identity["resource_arm"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    val explicit = call.singleQuery("series")?.let { normalizeBaselineSeries(it) }
    val release = baselineOperation { store.findReleasesByAnalysis(setOf(current.baselineString("analysis_id"))) }.byAnalysis.values.singleOrNull()
    val registered = (release?.get("series") as? JsonPrimitive)?.content
    if (explicit != null && registered != null && explicit != registered) {
        throw ApiFailure(HttpStatusCode.UnprocessableEntity, "BASELINE_SERIES_CONFLICT", "Series differs from the release series")
    }
    return (registered ?: explicit) to arm
}
```

Обработчики вызывают её, затем `store.readBaselineSlotWithCondition(series, arm, current, windows)`; `selectBaseline` возвращает пару (выбор, плечо) для записи слота. `requireOnlyQueries` расширяется на `series`. Повреждённый реестр релизов: `findReleasesByAnalysis` не должен ломать comparison, как в R5 (подстановка «релиза нет»). Удалить код `BASELINE_SERIES_DIFFERS` из `compareAnalyses` и его тест, строку из `BASELINE_LABELS` оставить в R9/B3 (ключ неиспользуемый удаляется в B3).

- [ ] **Step 4:** e2e: `baseline.spec.ts` `beforeEach` очищает все слоты общим помощником (`GET /api/baseline`, затем `DELETE /api/baseline?series=...&arm=...` по каждому элементу `baselines`, плюс `DELETE /api/baseline` для legacy). Run: `.\gradlew.bat test`, `npm --prefix ui run e2e -- e2e/baseline.spec.ts`, `ktlintCheck`. Документация: архитектурный раздел (слоты, порядок операций, пределы), пользовательское руководство (протокол как область baseline, предупреждение о нескольких baseline), `CHANGELOG.md`. Commit: `feat(baseline): select the active baseline per series and arm`.

**Риски B2:** (1) без `series` незарегистрированный анализ не находит слотов, появившихся после B1, и получает `404 No baseline is selected`: это буквальное правило ADR 0019 (раздел 7); интерфейс (B3) всегда передаёт `series` из поля BaselinePanel; (2) нормализация серии меняет ключ слота для серий с краевыми пробелами: такие серии теперь совпадают с обрезанными; (3) e2e общий: незачищенный слот соседнего теста ломает ожидания (общий помощник сброса обязателен).

**Documentation impact:** `docs/architecture/slice-1-local-runtime.md`, `docs/user/slice-1-local-analysis.md`, `CHANGELOG.md`.

---

## B3. UI: baseline по серии

**Цель:** BaselinePanel показывает все активные baseline (серия, режим, анализ) и работает с baseline выбранной серии; сравнение и подтверждение условий передают `series`; строка истории отмечает релиз-эталон своей серии; удаление baseline адресное.

**Ветка:** `feat/ui-baseline-slots`. **Размер:** M. **Зависит от:** B2, R7. **Identity и контракты:** нет.

**Что не входит:** групповая перепривязка серий, редактор слотов, вывод плеч сверх существующей подписи (до P1 плеча нет).

**Files:**

- Modify: `ui/src/api.ts` (`getBaseline` возвращает `{baseline, baselines}`; `compareBaseline(reference, windows, series?)`; `getBaselineConditions`/`setBaselineConditions` получают `series?`; `clearBaseline(series?, arm?)`), `ui/src/types.ts` (`BaselineSlotView`), `ui/src/BaselinePanel.vue`, `ui/src/shell/HistoryPanel.vue` (бейдж «baseline»), `ui/src/shell/labels.ts` (удалить ключ `BASELINE_SERIES_DIFFERS`, добавить `BASELINE_ERROR_LABELS.BASELINE_SERIES_CONFLICT`, `BASELINE_SLOTS_LIMIT_REACHED`), `ui/e2e/baseline.spec.ts`, `ui/e2e/history.spec.ts`
- Modify: `docs/user/release-history.md`, `docs/user/slice-1-local-analysis.md`, `CHANGELOG.md`

- [ ] **Step 1: Красные e2e.** (1) Два baseline в двух сериях: панель перечисляет оба (`[data-testid="baseline-slots"]` с двумя элементами), в сравнении видно baseline серии текущего анализа; поле «Comparison series» по умолчанию равно серии релиза текущего анализа (или серии единственного слота). (2) Удаление baseline серии A оставляет серию B; подтверждение условий пары серии B сохраняется. (3) Строка истории релиза-эталона помечена бейджем «baseline», вторая серия отдельной таблицей. (4) Сообщение `BASELINE_SERIES_CONFLICT` и `BASELINE_SLOTS_LIMIT_REACHED` показывается текстом из словаря (предел из `error.limit`).
- [ ] **Step 2: Реализация.** `BaselinePanel.vue`: `baselines` из `getBaseline`, выбор активной серии (радиогруппа по слотам), `series` передаётся во все вызовы comparison и conditions; кнопка «Снять baseline серии» вызывает `clearBaseline(series, arm)`. Режим «нет baseline» формулируется по серии. В `HistoryPanel.vue` бейдж ставится, если `reference` слота равна `(run_id, analysis_id)` анализа релиза.
- [ ] **Step 3:** Run: `npm --prefix ui run typecheck`, `lint`, `build`, `e2e`. Документация, `CHANGELOG.md`. Commit: `feat(ui): show and compare baselines per series`.

**Риски B3:** конфликт с UI-срезами U5 по `BaselinePanel.vue` (общая очередь UI-срезов); пока P1 не вводит плечо, `arm` в интерфейсе скрыт.

**Documentation impact:** `docs/user/release-history.md`, `docs/user/slice-1-local-analysis.md`, `CHANGELOG.md`.

---

## Перепривязка после смены identity

Срезы, меняющие общую identity (ADR 0016 S3, ADR 0018 S1/S3/S7, ADR 0014 limits, arm и автошаг), дают новый `analysis_id` тому же входу. Что происходит с релизами и как это обрабатывает этот план:

1. **Записи релизов остаются валидными.** Они ссылаются на неизменяемые анализы, которые читаются без перезаписи; `analysis_state` остаётся `OK`; копии verdict и покрытия описывают старый анализ.
2. **Динамика делится на эпохи.** `/analytics` берёт строки только с тем же техническим ключом, что у открытого анализа; релизы старой эпохи не получают чисел (`excluded_incompatible_count`), интерфейс подписывает причину («другие правила анализа»).
3. **Путь перепривязки без нового API.** Запись релиза допускает замену `analyses` внутри прежнего `run_id` (`PUT /api/releases/{releaseId}`): релиз остаётся «тем же релизом», но ссылается на пересчитанный анализ того же запуска. Для этого нужен повторный анализ входа (он новый, потому что identity новая), затем в строке истории «Перепривязать анализ» (R8). Серия, `run_id`, `started_at`, `created_at` не меняются.
4. **Baseline перезакрепляется отдельно** (ADR 0016: «два независимых повторных закрепления»): `POST /api/baseline` с новым анализом той же серии заменяет слот `(series, arm)` (B1/B2); записи условий старой пары остаются привязанными к старой точной паре и не применяются к новой.
5. **Окно пересчёта объявляет владелец** до каждого внешнего identity-среза: готовится перечень серий (до десятка), по каждой пересчитываются нужные релизы и закрепляется baseline. Групповой помощник перепривязки в МВП не планируется (вопрос В9): объём ручной работы порядка числа серий, а не числа релизов, потому что историю старой эпохи пересчитывать не обязательно.
6. **Pod-view (P2b плана платформы) окна не создаёт:** анализ без pod-view не меняется.

---

## Pod-view (ADR 0020): срезы P2a-P2e плана платформы

Реализация ADR 0020 расписана в `docs/superpowers/plans/2026-10-04-platform-p0-p5.md` (влит в `main`): P2a контракт и валидатор, P2b вход job, привязка, identity, хранение и CLI, P2c API чтения, P2d адаптер и синтетика, P2e замер пределов; экран карты и таблицы подов P4c. Второй набор срезов здесь дублировал бы его и разошёлся бы с ним, поэтому первая редакция этого плана (четыре среза pod-view) заменена сверкой. Реализует и ведёт pod-view план платформы; ниже то, что этот план добавляет к нему по результатам собственной сверки с кодом и ADR (замечания внести в пошаговые планы P2b и P2c при слиянии предыдущего среза: у P2c и P2e пошагового плана ещё нет).

| В плане платформы | Зависит от (по его таблице) | Identity | Что проверяет эта сверка |
| --- | --- | --- | --- |
| P2a | P1a (порядок ADR 0020, раздел 7) | нет | пункты 1-2 ниже |
| P2b | P2a, P1a | условно, только анализ с pod-view | пункты 3-4 |
| P2c | P2b, D0 (конвенция строгих параметров и курсора) | нет | пункты 5-8 |
| P2e | P2c, P2d | нет | пункт 9 |
| P4c | P2c, D2, U3 | нет | экран: состояния «нет артефакта», `null`, неполный `coverage`, разные сетки (ADR 0020, раздел 6) |

Замечания к P2 (проверены по `origin/main`; ссылки на ADR 0020):

1. **P2a, значения агрегации.** `ResourceAggregation` уже содержит `interval_max` и `interval_min` (#45): допустимые значения `aggregation` в схеме и валидаторе берутся из `ResourceAggregation.entries` (четыре значения), тест синхронности перечня схемы и ядра обязателен.
2. **P2a, примеры и бюджет.** `basic.json` строится на сетке и входе примера снимка `docs/contracts/resources/v1/examples/valid/basic.json` (`start_epoch_ms = 1767225600000`, `step_ms = 10000`, вход `0123...cdef`), чтобы P2b мог использовать его в golden-фикстуре identity; `resource_snapshot_sha256` равен семантическому хэшу этого снимка. Предел ячеек равен произведению пределов рядов и колонок (`2 560 x 240 = 614 400`) и самостоятельно не срабатывает: нужен тест `POD_VIEW_CELLS_MAX == POD_VIEW_ROWS_MAX * POD_VIEW_COLUMNS_MAX`, который упадёт, если P2e изменит одну константу и не пересмотрит бюджет. Чтение байт с пределом, строгий UTF-8 и `resourceFail` приватны в `ResourceSnapshot.kt`: дублирование около 30 строк предпочтительнее новой общей абстракции (AGENTS.md); `StrictJsonScanner` (`Policy.kt:566`) используется как есть, правило «число без экспоненты» проверяется отдельным тестом.
3. **P2b, оракулы.** `analysis-result.json` с pod-view побайтно равен результату того же входа без него; `analysisIdentity` с pod-view отличается от `analysis-identity-resources.v1.json` ровно двумя верхнеуровневыми полями, блоки `modules`, `input_versions`, `limits` побайтно равны; сравнение (`compareAnalyses`) и динамика для пары identity с pod-view и без него не дают `INCOMPATIBLE_METRIC_DEFINITION` и `BASELINE_MIXED_SEMANTICS` (ключ сопоставимости не меняется). Существующие golden-фикстуры не правятся: новая фикстура идёт отдельным файлом и записью манифеста и пересчитывается после перебазирования на последний identity-срез (ADR 0016 S3 влит, ADR 0018 S1 влит; остаётся P1a).
4. **P2b, предел и память.** Лимит частей job 24 -> 25 и рост `MAX_JOB_REQUEST_BYTES` на предел новой части проверяются полной допустимой комбинацией частей и запросом ровно на предел; разбор pod-view (до около 154 МБ по оценке ADR 0020, не измерено) идёт последовательно со снимком, дерево освобождается до анализа; совместный пик входит в P2e.
5. **P2c, целостность при каждом чтении.** ADR 0020, раздел 5, требует сверять SHA-256 `pod-view.json` с манифестом и `pod_view_sha256` identity при каждом запросе API: чтение и хэш (до 12 MiB) идут вне `operationLock` по образцу `readVerifiedAnalysis` и параметра `outsideLock` среза R1 этого плана (под замком только `readAnalysisUnlocked`), с тем же тестом «медленное чтение не блокирует хранилище».
6. **P2c, разбор и кэш.** Первый запрос разбирает до 12 MiB JSON: вычислять разбор под `Semaphore(1)` (как `postgresCapturePermit`), хранить ограниченный кэш разобранного представления (два элемента, ключ `pod_view_sha256`, значения в `DoubleArray`, пропуск как `NaN`), хэш файла проверять при каждом запросе, а кэш не должен скрывать подмену файла (тест: подмена между запросами обнаруживается следующим).
7. **P2c, границы значений.** `from_ms` и `to_ms` лежат на границах колонок; единственная допустимая верхняя граница вне кратности `step_ms` - конец сетки снимка `start + point_count x шаг снимка`, когда последняя колонка неполная (ADR 0020, разделы 4 и 6): тест с некратным `point_count x шаг` и `to_ms`, равным концу сетки. Курсор `after` обязан принадлежать выбранному набору `service`/`metric`, иначе `400 INVALID_CURSOR`; страница не больше 100 000 ячеек.
8. **P2c, образец конвенции.** `/buckets` курсора `after` не имеет (параметры `rollup`, `from_ms`, `to_ms`, `limit`, ответ `next_from_ms`, `LocalApi.kt:922-936`); образец `after` и `next_after` даёт список запусков `GET /api/runs` (`LocalApi.kt:202-232`). Пошаговый план P2c ссылается на оба образца раздельно.
9. **P2e, сценарии.** S1: 78 подов вариант F (546 рядов) x 240 колонок; S2: вариант C (936 рядов); S3: предел 256 подов, 2 560 рядов, 240 колонок, числовые токены по 12 байт; S4: S3 плюс снимок ресурсов до `C_max` в одном job при `-Xmx512m` (совместный пик); S5: три анализа подряд (кэш P2c не растёт выше двух элементов). Для каждого: байты файла, время валидации и хэша, постоянная и пиковая куча, время страницы `values` (холодная и тёплая), три повтора после прогрева. Тест замера не входит в CI (переменная окружения `LTV_POD_VIEW_MEASURE=1`), тяжёлые прогоны корень выполняет под `Global\ltv-heavy`. Решение о `R_max = 3 072`, ячейках 737 280 и файле 16 MiB принимает владелец по таблице замера (ADR 0020, «Подлежит пересмотру»).

Стыки pod-view и истории релизов: pod-view не входит ни в запись релиза, ни в динамику, ни в ключ сопоставимости; анализ с pod-view и без него (один и тот же вход) получают разные `analysis_id` и регистрируются в релизе как два разных анализа одного запуска (при одинаковом плече повтор плеча отвергается `RELEASE_ARM_CONFLICT`: пользователь выбирает, какой из анализов считать анализом релиза, остальные остаются в списке анализов запуска).

---

## Вопросы владельцу

Каждый вопрос с рекомендацией. До ответа срезы, зависящие от вопроса, не стартуют; остальные идут независимо.

| № | Вопрос | Рекомендация | Что блокирует |
| --- | --- | --- | --- |
| В1 | Входит ли в живое демо «сравнение с прошлым релизом» (история 2-4 релизов)? Из перечня демо (коннектор Grafana, анализ по SLA, анализ теста максимума, урезанный глубокий анализ) этого не следует | Нет. Из этого плана обязателен только R1 (эталон только из `PASS`); R0, R2, R3, R6, R7 берутся, если сценарий демо покажет динамику | порядок: R1 вперёд, остальное по решению |
| В2 | Принять поправку ADR 0019 (раздел R0): копии `coverage_reasons` и `policy_sha256` в `analyses[]`, список по убыванию `release_id`, `analysis_state` в списке по существованию манифеста, `error.limit`, объекты в `corrupt_names`, коды `RELEASE_TOO_LARGE`, `RELEASE_ARM_CONFLICT`, `RELEASE_RESULT_TOO_LARGE`, вычисление допуска вне замка | Да. Формат `local-release.v1` ещё не выпущен, миграции нет. Без копий `coverage_reasons` сводка списка не отличит допустимую малую выборку от непригодной (ложный запрет «Сделать baseline»); альтернатива «читать результат каждой строки списка» дороже на порядок и держит замок | R0, R2, R3 |
| В3 | Подтвердить порядок R1, затем R4, затем S10 ADR 0018 и перенос шагов 1.1a и 1.3 S10 в R4 (S1 уже влит, допуск `SMALL_SAMPLE` наблюдаем сразу); D5a выпускается вместе с S10 | Да: единственная правка условия отбора, допуск и предупреждение в одном PR (требование ADR 0018 и ADR 0019). Правку плана ADR 0018 вносит его автор | R4, S10 |
| В4 | Параллельные `PUT` одной записи обнаруживаются (`RELEASE_CHANGED`), но правка из устаревшего окна браузера перезапишет более новую: клиентского предусловия нет | Принять. Локальный однопользовательский контур; предусловие (`updated_at` в теле `PUT`) разошлось бы с ADR 0019, раздел 3 | R3 |
| В5 | Предел 4 096 записей условий baseline (нужен, чтобы адресное удаление слота читало их под замком в ограниченном объёме): новая запись сверх предела отказывает `422 BASELINE_CONDITIONS_LIMIT_REACHED` | Принять. ADR 0019 раздел 7 предел не называет; без него объём чтения при удалении не ограничен. На масштабе владельца записей десятки | B1, B2 |
| В6 | Незарегистрированный анализ без `series` читает только прежний `baseline.json` (буквально ADR 0019, раздел 7); после слотов новые baseline туда не попадают | Оставить по ADR: интерфейс всегда передаёт `series`; автовыбор единственного слота добавляет неявное правило | B2, B3 |
| В7 | Вкладка «История» только в новой оболочке (`?shell=new`); прежний интерфейс получает лишь коды и предупреждения BaselinePanel (R9) | Да: прежний интерфейс остаётся до ворот фазы 4 | R7, R9 |
| В8 | Распространение защиты Origin/CSRF на `PUT` фиксируется в архитектурном описании, а не дополнением ADR 0002 | Достаточно: ADR 0019, раздел 3, уже обязывает к этому; дополнение ADR 0002 дублировало бы его | R3 |
| В9 | Нужен ли групповой помощник перепривязки серий после смены identity | Нет в МВП: перепривязывается baseline серии (порядка десяти) и при желании отдельные релизы; историю старой эпохи пересчитывать не нужно | не блокирует |
| В10 | Числа динамики отсутствуют для анализов с документами больше 8 MiB (4-8-часовой тест с рядами ресурсов даёт результат 32-55 МБ, ADR 0014) | Не менять ADR (копирование метрик в релиз отклонено): проверить частоту пропусков на стенде вне МВП; при частых пропусках отдельным срезом читать из результата только `metrics` потоково | R7 (UI честно объясняет), не блокирует |
| В11 | Подтвердить, что pod-view реализуется только срезами P2a-P2e плана платформы, а этот план его не дублирует (четыре среза V1-V4 первой редакции удалены) | Да: два плана на одно решение разошлись бы; порядок ADR 0020 (P2 после автошага, D0 и P1) соблюдается платформенным планом, замечания этой сверки передаются в его P2b, P2c, P2e | pod-view |
| В12 | Кто и когда выполняет замер P2e | Корень под `Global\ltv-heavy`, синтетика, без стенда; решение о `R_max = 3 072`, ячейках 737 280 и файле 16 MiB принимает владелец по таблице замера | P2e |
| В13 | Приватность пользовательского текста: `label` и краткий профиль идут в экспорты динамики, `notes` не экспортируются никогда и не попадают в ИИ-разбор и журналы | Принять; интерфейс подписывает поле заметок «Остаётся только в локальном хранилище» | R3, R6, R7 |

## Порядок слияния и граф зависимостей

```text
R1 ------------------------------------------------------------> (демо-минимум)
R0 -> R2 -> R3 -> R6 -> R4 -> R5 -> R7 -> R9 -> R8 -> B1 -> B2 -> B3
                |_________________^  (R5 после R4; R7 после R3 и R6)
pod-view: P1a -> P2a -> P2b -> P2c (нужен D0) -> P2e  (план платформы)
S10 ADR 0018 после R4 (проверка verdict_gates)
```

## Проверка

Команды, оракулы и гейты. Тяжёлые прогоны (Gradle, e2e) выполняет корень или субагент Claude; этот PR документальный и их не требует.

### Для каждого среза (AGENTS.md, «Completion gate»)

1. `git status --short --branch`, `git diff origin/main --stat`, полный просмотр diff; в индексе только файлы среза.
2. `.\gradlew.bat clean check installDist` (как в CI, `.github/workflows/runtime-quality.yml:71`; включает ktlint: правило `ktlintFormat`, затем `ktlintCheck`; примеры кода в плане могут нарушать форматирование и подгоняются под ktlint).
3. `npm --prefix ui run typecheck`, `lint`, `test:contracts`, `build`, `e2e` (e2e общий сервер и один каталог данных: тесты сами очищают релизы и baseline).
4. `python tools/verify_slice0.py` и `python -m unittest discover -s tools -p "test_*.py"`, если затронуты `fixtures/slice1`, контракты или `tools/`.
5. Документация: `npx --yes markdownlint-cli2@0.23.2 "**/*.md"`, проверка относительных ссылок (CI: lychee по `./**/*.md`), `git diff --check`, проверка секретов (CI: gitleaks) и поиск пользовательских данных в тестовых фикстурах.
6. Отчёт: команды и результаты, число тестов до и после, ограничения, непроверенные допущения.

### Оракулы (независимые проверки результата)

| Оракул | Где | Что доказывает |
| --- | --- | --- |
| Golden identity без pod-view побайтно прежние; новая golden отличается ровно двумя полями | P2b плана платформы | pod-view не меняет `analysis_id` других анализов и ключ сопоставимости |
| `analysis-result.json` с pod-view побайтно равен результату без него | P2b плана платформы | диагностическая проекция не влияет на verdict |
| Канонический байт-golden записи `local-release.v1` | R2 | формат не плывёт |
| Модельный тест хранилища: 200 случайных операций (seed фиксирован) `create`/`replace`/`delete`/`list` против модели в памяти: уникальность `analysis_id`, число записей не больше `MAX_RELEASES`, порядок списка, полнота `series_summary` | R2 | инварианты реестра при любой последовательности |
| Подмена результата при сохранённом размере отвергается; подмена `pod-view.json` отвергается | R1, R3, P2c | целостность читается по хэшу, а не по размеру |
| Гонка: два параллельных `POST` одного `analysis_id` дают ровно один `201`; 20 повторов | R2, R3 | уникальность под замком |
| «Медленное чтение не блокирует хранилище» | R1, P2c | короткий замок (ADR 0002, дополнение 2026-10-01) |
| Один и тот же код допуска у сводки списка, POST baseline и тестов таблицы | R1, R3, R9 | правило допуска не расходится |
| Замер скана 1 000 записей, слотов и таблица P2e | R2, B1, P2e | пределы и задержки подтверждены измерением, а не оценкой ADR |

### Гейты

- Гейт среза: пункты 1-6 выше и красный-зелёный цикл TDD в каждом шаге.
- Гейт группы D5a: R1-R9 и S10 ADR 0018 (S1 влит) вливаются согласованно; полный CI зелёный; ручной прогон сценария «сохранить два релиза, выбрать baseline из PASS, сравнить»; ревью Codex `gpt-6-sol` `xhigh` по границам хранилища и безопасности (замок, `PUT`, пределы).
- Гейт D5b (B1-B3): то же плюс тест сохранности условий другого слота и ручной прогон двух серий.
- Гейт pod-view: принадлежит плану платформы (P2a-P2c, P2e и экран P4c); этот план закрывает только сверку (раздел «Pod-view») и не объявляет ADR 0020 выполненным.
- Приёмка на реальном стенде в гейты **не входит**.

## Риски

Общие риски по срезам названы в самих срезах. Здесь только то, что зависит от реального стенда и потому остаётся непроверенным в рамках МВП:

1. **Крупные результаты.** Результаты 4-8-часовых тестов с рядами ресурсов (32-55 МБ, замер ADR 0014) превышают предел документа истории (8 MiB): строки динамики для них отсутствуют, таблица истории покажет релизы без чисел (интерфейс объясняет причину). Частота этого на реальных данных неизвестна (В10).
2. **Память чтения результата.** Регистрация релиза и выбор baseline читают результат до 64 MiB и разбирают его в `JsonObject`; пик порядка сотен МБ на анализ не измерен на данных стенда; statistical baseline на 20 крупных кандидатах держит все документы сразу (поведение не меняется, но теперь нагрузка включает хэширование).
3. **Реальные поды.** Имена подов, ротация при масштабировании, число контейнеров, форма меток и доля пропусков известны только на стенде; пределы `pod-view` (256 подов, 2 560 рядов, 240 колонок, 12 MiB) остаются предварительными (P2e плана платформы замеряет синтетикой). Адаптер, пишущий `pod-view.v1`, в репозитории отсутствует: соответствие реального производителя контракту не проверено.
4. **Плечи.** До P1 релиз содержит один анализ; многоплечевые релизы, слоты по плечу и UI плеч проверены только на синтетических identity.
5. **Протоколы и профили.** Практика именования серий и заполнения профиля пользователями (до десятка протоколов, 2-4 релиза) подтверждена только словами владельца; удобство выбора baseline из истории не проверялось на людях.
6. **Задержка скана.** Скан реестра в 1 000 записей под замком измеряется на хосте разработки; на медленном диске стенда задержка может быть выше (граница пересмотра - около 5 000 записей, ADR 0019).

## Результат независимого ревью плана

Ревью: Codex `gpt-6-sol`, `xhigh`, read-only, один раунд по редакции до ребейза на `e36a6d2` (24 замечания: 2 BLOCKER, 17 MAJOR, 5 MINOR). Каждое проверено по коду и ADR; приняты и внесены:

- BLOCKER, подготовка и `fsync` записи под замком: запись готовится вне `operationLock` (`prepareRelease`), под замком остаются перечисление, проверки, цель и публикация; конкурентная замена даёт `RELEASE_CHANGED` (R2, R3, R0).
- BLOCKER, безадресный `clearBaseline()` стирал бы условия другого слота: он очищает только условия своего выбора, не используемые эффективными слотами (B1).
- MAJOR: фактический порядок кодов на HTTP-пути (запросные проверки до чтения) и вердикт в сообщении `NOT_PASS` для обоих режимов (R1); создание без перезаписи держится на единственном писателе, а не на `ATOMIC_MOVE` (R2); `analysisExists` проверяет всех предков без ссылок, ошибки чтения одной записи не рвут список (R2); граница гарантии копий фактов (R0); лимит слотов считается по эффективным ключам и чтение слотов не обходит каталоги анализов (B1, Global Constraints); `HistoryPanel` монтируется только при `shellNew` (R7); предложения и статистический режим требуют заявленного совпавшего профиля (R7); D5a выпускается вместе с S10 (гейты); оракул `RELEASE_TOO_LARGE` построен на записи, действительно больше 8 KiB (R2, R3); база обновлена до `e36a6d2`, `interval_max` и ADR 0018 S1 учтены как влитые; плейсхолдеры убраны (R5), срезы pod-view заменены сверкой.
- MINOR: импорты `MAX_TIMESTAMP_EPOCH_MILLIS`, образец курсора `after` (`/api/runs`, не `/buckets`), `runStart`, очистка релизов по страницам в e2e, пагинация выбора анализа при перепривязке, формулировка про демо.

Приняты с изменением: замечания 11, 13, 17 по pod-view (V3, зависимость от D0, гейт ADR 0020) решены тем, что pod-view реализует план платформы (P2a-P2e, влит), а замечания переданы ему (раздел «Pod-view»). Не принято: добавление отдельного кода `RELEASE_RUN_MISMATCH`-теста через другой запуск (пара `(run_id, analysis_id)` другого запуска не находится и даёт `404`, это записано в R0). Не подтверждено ревью без запуска: компиляция фрагментов целиком, времена хэша и скана, поведение `ATOMIC_MOVE` на целевых файловых системах. Второй раунд ревью не проводился (экономия запросов по указанию координатора).

## Self-review

- Покрытие ADR 0019: записи и факты (R2, R3), идентификатор и хранение (R2), API (R3), профиль и три слоя сопоставимости (R5, R8), предупреждения и порядок (R4, R5), baseline только из `PASS` (R1), малая выборка (R4), слоты (B1-B3), динамика и UI (R6-R9), совместимость и миграция (Global Constraints, B1, раздел «Перепривязка»). ADR 0020 реализует план платформы (P2a-P2e); здесь сверка и замечания.
- Размеры, зависимости, параллельность, горячие точки, очередь identity, минимум для демо, вопросы с рекомендациями и раздел проверки присутствуют.
- Имена согласованы между срезами: `readVerifiedAnalysis`, `VerifiedAnalysis`, `baselineCandidateRejection`, `findReleasesByAnalysis`, `ReleaseComparisonContext`, `createRelease`/`replaceRelease`/`deleteRelease`/`listReleases`/`readRelease`, `replaceBaselineSlot`/`clearBaselineSlot`/`readBaselineSlotWithCondition`.
- Известные допущения плана: список файлов тестов R1 уточняется шагом 4.2; R9 согласуется с U5a и U5b плана UI; пошаговые планы P2c и P2e плана платформы ещё не написаны (замечания сверки им передаются).
