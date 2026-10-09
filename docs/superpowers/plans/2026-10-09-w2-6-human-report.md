# W2.6 Отчёт для людей: разбивка ошибок (PR 1 из 3)

> **Для исполнителя:** superpowers:executing-plans. Шаги отмечены чекбоксами.

**Goal:** разработчик видит топ ошибок (код ответа, сообщение, транзакция) в отчётах HTML,
AsciiDoc, Confluence и в `summary.txt` без JMeter Dashboard. Критерий W2.6 из
`docs/superpowers/plans/2026-10-08-review-work-plan.md`: «Разработчик видит топ ошибок без JMeter
Dashboard». `analysis_id` и байты `analysis-result.json` существующих прогонов не меняются.

**Architecture:** парсеры сохраняют `responseCode` и `failureMessage` в `LoadSample` только у
неуспешных сэмплов; во втором проходе `AnalysisService` их собирает ограниченный аккумулятор;
итог пишется отдельным артефактом каталога анализа `error-groups.json` (по образцу
`load-stages.json`, `rollup-*.ndjson`), вне identity и вне `analysis-result`. Рендеры получают
байты артефакта необязательным параметром.

**Tech Stack:** Kotlin, kotlinx.serialization (`JsonObject`, `canonicalJson`), JUnit 5, Gradle.

## Разбиение по правилу 10 MINIMAL-CHANGE

W2.6 содержит шесть независимых частей. Один PR на всё это тронул бы парсеры, ядро, три рендера,
CLI и все golden-отчёты сразу. Поэтому:

| PR | Содержание | Оценка |
| --- | --- | --- |
| **PR 1 (этот)** | `responseCode`/`failureMessage` в `LoadSample`, аккумулятор, артефакт `error-groups.json`, разбивка ошибок в HTML, AsciiDoc, Confluence, `summary.txt` и `ltv summary` JSON | ~700 строк кода и тестов, 14 файлов |
| PR 2 | время создания, длительность, пиковый RPS (из `rollup-*.ndjson`/`run.json`, без нового хранения) + встроенный SVG графика в HTML | ~250 строк, 3 файла, правка golden HTML |
| PR 3 | машинный хвост (`Canonical JSON`, `Evidence IDs`) в приложение в конец отчёта, русский `<h1>` и панели, перевод англоязычных блоков | ~300 строк, правка golden HTML; делать после W2.3, потому что оба правят `HtmlReport.kt` |

Порядок с W2.3: W2.3 (`--baseline`, разделы сравнения) идёт после PR 1 и правит `HtmlReport.kt`.
PR 1 добавляет в `HtmlReport.kt` одну функцию `errorGroupsSection` и одну строку в сборке страницы
после `transactionsSection`, чтобы конфликт слияния был минимальным.

## Блок AGENTS.md

```text
REQUESTED: хранить responseCode и failureMessage в LoadSample; показывать топ ошибок по (код ответа,
  сообщение) в отчётах и summary (часть W2.6, PR 1).
REQUIRED TO ACHIEVE IT:
  - LoadSample: два необязательных поля с значением по умолчанию null; четыре парсера заполняют их у
    неуспешных сэмплов (JTL CSV, JTL XML, Gatling text, Gatling binary).
  - core/ErrorGroups.kt: аккумулятор с лимитами, очистка текста, кодирование error-groups.v1.
  - AnalysisService: запись артефакта error-groups.json (только если есть ошибки), параметр уже
    существует в цепочке записи, identity и result не меняются.
  - Рендеры HTML, AsciiDoc, Confluence и CliArtifacts: необязательный параметр errorGroups: ByteArray?
    и раздел/строки; три места вызова (CommandLine.kt, RunRoutes.kt) читают артефакт из каталога
    анализа.
  - ADR 0031 (формат артефакта, лимиты, место хранения, совместимость с ADR 0029), фрагмент changelog,
    пользовательская документация.
NOT REQUIRED: время/длительность/пиковый RPS, встроенный SVG, перенос машинного хвоста, русский <h1>
  и панели (PR 2 и 3); evidence в analysis-result и новые находки (это поправка к ADR 0029 перед
  W3.7); JSON-схема в docs/contracts (ADR описывает формат; схема вместе с поправкой 0029);
  разбивка ошибок по окнам стадий; изменение UI (ui/); нормализация текста сообщений (замена
  чисел и идентификаторов); пересчёт разбивки для уже сохранённых анализов; изменение версии
  парсера или модулей identity.
EXPECTED FILES TO CHANGE:
  src/main/kotlin/io/ltverdict/ingest/{LoadSample,JtlCsvParser,JtlXmlParser,GatlingTextParser,GatlingBinaryParser}.kt
  src/main/kotlin/io/ltverdict/core/{ErrorGroups (новый),AnalysisService}.kt
  src/main/kotlin/io/ltverdict/report/{HtmlReport,AsciiDocReport,ErrorGroupsView (новый)}.kt
  src/main/kotlin/io/ltverdict/integrations/report/ConfluenceReport.kt
  src/main/kotlin/io/ltverdict/cli/{CliArtifacts,CommandLine}.kt, src/main/kotlin/io/ltverdict/web/RunRoutes.kt
  src/test/... (новые тесты и минимальные правки старых), fixtures/... (golden-отчёты, только
  затронутые, отдельным коммитом)
  docs/adr/0031-error-groups-artifact.md, docs/user/..., changelog.d/w2-6-error-groups.added.md,
  этот план
```

## Факты по коду (проверено на origin/main 72b3f95)

- `LoadSample` (`ingest/LoadSample.kt`) хранит время, длительность, метку, путь групп, вид, успех.
  Создаётся в четырёх парсерах; ошибок и кодов не хранит.
- `AnalysisService.analyze` читает вход дважды: первый проход собирает границы прогона, второй
  кормит `MetricsAccumulator` и необязательные аккумуляторы (окна, ёмкость, диагностика). Затем
  `store.writeAnalysisAtomically` пишет файлы в staging; `manifest.json` строится по факту
  содержимого staging (`inspectStagedArtifacts`), белого списка имён нет. Поэтому новый файл
  попадает в манифест сам, а анализ без этого файла остаётся валидным.
- `analysis_id` это SHA-256 от `identity.json`; `analysisResult(...)` в identity не входит (проверено
  в `analysisIdentity`: входят хэши входов, версии парсера и модулей, лимиты, привязки). Любой путь,
  не трогающий `analysisIdentity` и `analysisResult`, не меняет ни `analysis_id`, ни байты результата.
- Повторный анализ того же входа с тем же `analysis_id` возвращает сохранённый результат без второго
  прохода (`store.readAnalysis` ... `return AnalysisOutcome`). Старые анализы поэтому не получают
  артефакт.
- Рендеры принимают `(resultBytes, analysisId)`. Вызовы: `CommandLine.kt` (analyze `--out-dir`, report)
  и `RunRoutes.kt` (`/report`). `summaryText`/`summaryJson` в `CliArtifacts.kt` читают только байты
  результата. Фикстуры-golden: `fixtures/stages/plain-reports/*`, `fixtures/http-layer/responses.txt`
  (HTML, AsciiDoc, Confluence, summary).
- Метрики считают ошибки `!successful` у `JMETER_SAMPLER` и `GATLING_REQUEST` в `overall`; контейнеры
  (`JMETER_CONTAINER`, `GATLING_GROUP`) дублируют ошибки дочерних и в `overall` не входят.

## Ruling'и

1. **Место хранения: вариант (b), отдельный артефакт `error-groups.json` вне identity.**
   Вариант (a) (evidence в `analysis-result`) меняет байты результата всех прогонов с ошибками и
   вынуждает новый `analysis_id` (иначе под одним id лежали бы два разных результата), то есть
   перегенерацию golden identity; это работа W3.7 (ADR 0029, решение 2). Вариант (c) (пересчёт при
   отрисовке) требует разбора входа до 4 ГБ при каждом открытии отчёта и недоступен, когда исходник
   удалён. Вариант (b) не меняет ни identity, ни результат; цена: у одного `analysis_id` бывают два
   состояния каталога (до и после W2.6), старые анализы остаются без разбивки, отчёт говорит об этом
   явно. Артефакт полностью определяется входом (детерминирован), поэтому состояния не противоречат
   друг другу. Цена ошибки: если позже понадобится evidence в результате (W3.7), оно добавит ссылку на
   этот артефакт по SHA-256 из манифеста, переделывать формат не придётся.
2. **Файл пишется только если ошибки были.** Прогоны без ошибок не получают нового файла: каталог,
   манифест и golden-файлы хранилища без ошибок не меняются. Отсутствие файла при `error_count` > 0 в
   `metric_summary` означает «анализ создан до появления разбивки»; при 0 ошибок раздела нет.
3. **Что считается ошибкой.** Только сэмплы `JMETER_SAMPLER` и `GATLING_REQUEST` с `successful = false`,
   то есть ровно те, что дают `overall.error_count`; показанные группы плюс `omitted_error_count` плюс `untracked_error_count`
   равны ему (проверка в тесте). Аккумулятор получает сэмплы только во втором проходе вместе с `MetricsAccumulator`. Контейнеры не входят.
4. **Текст сообщения.** `LoadSample.failureMessage` = столбец `failureMessage`, если непустой, иначе
   `responseMessage` (HTTP 500 без ассертов несёт текст только там); для XML: первый непустой
   `<failureMessage>` внутри `<assertionResult>` сэмпла, иначе атрибут `rm`; Gatling: текст сообщения
   KO. `responseCode`: столбец CSV `responseCode`, атрибут XML `rc`; у Gatling `null`. Столбцы и атрибуты необязательны: ранее принятые входы остаются валидными (нет столбца: `null`). XML: текст `<failureMessage>` собирается потоково из событий CHARACTERS/CDATA и принадлежит сэмплу на вершине стека в момент открытия элемента; после 4096 символов хвост отбрасывается. Поля заполняются только у неуспешных
   сэмплов (у успешных остаются `null`), чтобы не держать лишние строки в памяти.
5. **Лимиты и очистка (недоверенные данные).** Код ответа: до 64 символов (code points). Сообщение: до
   200 символов, затем `…`, признак `message_truncated`. Очистка до группировки: символы Unicode категорий
   Cc, Cf, Zl, Zp, Cs (одиночные суррогаты) и неназначенные заменяются пробелом, серии пробельных
   символов сворачиваются в один, края обрезаются; пустой результат равен `null`. Это убирает
   переводы строк (ломают литеральные блоки AsciiDoc), символы управления (недопустимы в XML 1.0 для
   Confluence) и управляющие символы двунаправленности (подмена порядка текста). Группы: в артефакте не
   более 20 групп (топ по числу среди отслеживаемых ключей), отслеживается не более 2048 различных
   ключей и не более 16 MiB байт их текстов (метка, путь, код, сообщение; ключ, не влезший в любой из
   двух лимитов, не отслеживается); ошибки неотслеживаемых ключей идут в `untracked_error_count`. Цена ошибки лимита 2048: поздний ключ (в том числе доминирующий) после 2048 других
   попадёт в «прочие»; принято, потому что без нормализации чисел в сообщениях неограниченное число
   ключей сломало бы память; отчёт показывает счётчик «вне учёта».
6. **Экранирование.** HTML: существующий `escape`. AsciiDoc: строка группы идёт внутри литерального
   блока `[subs=specialchars]`, начинается с числа ошибок (не может совпасть со строкой `----`), без
   переводов строк (очищено). Confluence: `xml()` экранирует `&<>"'`, управляющих символов нет.
   Очистка применяется ещё раз при чтении артефакта в рендере (артефакт на диске недоверенный, как
   любой файл каталога данных), метка транзакции очищается тем же правилом при показе.
7. **Порядок и ключ.** Ключ группы: (метка, путь групп, вид сэмпла, код, сообщение). Порядок: число
   по убыванию, затем метка, путь групп, вид, код, сообщение по байтам UTF-8. `id` группы =
   `errgrp-` + SHA-256 канонического JSON `{scope, response_code, message}` (канонический JSON и
   хэши identity не затрагиваются: это отдельный хэш).
8. **Окна.** v1 считает за весь прогон; `from_epoch_ms`/`to_epoch_ms` группы это первая и последняя
   ошибка группы (`to` = максимум конца сэмпла), `window_id` отсутствует. В отчёте раздел подписан «за
   весь прогон», при вердикте по окну steady это справочная величина, как метрики всего прогона
   (ADR 0030, R10).
9. **Публичные контракты.** Новый формат файла `error-groups.v1` (ADR 0031); `summary.txt` получает
   блок «top errors» только при наличии групп; `ltv summary` JSON (`cli-summary.v1`) получает
   необязательный ключ `error_groups` только при наличии групп: `{scope: "whole_run", total_error_count,
   omitted_error_count, untracked_error_count, groups: [{label, group_path, sample_kind, response_code,
   message, count}]}`, не более 5 групп (то же число в `summary.txt`) (прогоны без групп и golden `summary.json`
   без ошибок не меняются). Коды выхода, `analyze`-вывод JSON, HTTP-контракты, схемы `docs/contracts`,
   `analysis-result.v1`, `identity` не меняются. Параметр рендеров необязателен. Без него отчёт прогона без ошибок не меняется; отчёт прогона с ошибками
   получает раздел «Ошибки» с пометкой «Разбивка ошибок недоступна» (ruling 2), поэтому golden-отчёты
   с ошибками меняются осознанно.
10. **Совместимость с ADR 0029.** Группа несёт `id`, область `scope {kind, label, group_path,
    sample_kind}`, `from_epoch_ms`/`to_epoch_ms` и находится в артефакте с SHA-256 в `manifest.json`;
    это та форма, которую ADR 0029 требует для атома TRANSACTION. Недостающее для W3.7 (`finding_id`,
    `evidence_id`, `window_id`) добавится поправкой к ADR 0029 как evidence-ссылка на артефакт; она меняет
    результат и identity, поэтому принадлежит W3.7.

11. **Чтение артефакта.** Читатель берёт файл из каталога анализа, уже проверенного `readAnalysis`
    (пути и размеры манифеста): обычный файл без ссылок, не более 1 MiB, JSON не глубже 8 уровней, не
    более 20 групп, `schema_version` равна `error-groups.v1`, счётчики неотрицательные целые; любое
    нарушение трактуется как «разбивка недоступна», не как ошибка отчёта. SHA-256 файла не
    перепроверяется (как у `rollup-60s.ndjson`, читаемого графиком); цена: подмена файла того же
    размера внутри каталога данных покажет подставленный текст, он очищается и экранируется как любой
    недоверенный. Сырые метка и путь групп в артефакте остаются как есть (нужны для сопоставления с
    областью транзакции), очищается только показ.
12. **Манифест и ИИ-рекомендации.** `ai/AiAdviceStore` привязывает рекомендацию к SHA-256 манифеста
    анализа. Новый файл появляется только у вновь созданных анализов и сразу в манифесте; манифест
    существующих анализов не переписывается (backfill не делается), поэтому существующие рекомендации не
    инвалидируются.

## Контракт `error-groups.v1`

```text
{
  "schema_version": "error-groups.v1",
  "run_id": "<run_id>",
  "scope_note": "whole_run",
  "total_error_count": <int>,            // = overall.error_count
  "tracked_error_count": <int>,          // сумма по отслеживаемым ключам
  "untracked_error_count": <int>,        // total = tracked + untracked; tracked = сумма groups + omitted
  "tracked_group_count": <int>,
  "omitted_group_count": <int>,          // отслеживаемые ключи, не вошедшие в топ 20
  "omitted_error_count": <int>,
  "limits": {"groups_max": 20, "tracked_groups_max": 2048, "code_chars_max": 64, "message_chars_max": 200},
  "groups": [ {
    "id": "errgrp-<sha256>",
    "scope": {"kind": "transaction", "label": "...", "group_path": [...], "sample_kind": "JMETER_SAMPLER"},
    "response_code": "503" | null,
    "message": "..." | null,
    "message_truncated": false,
    "count": <int>,
    "from_epoch_ms": <int>, "to_epoch_ms": <int>
  } ]
}
```

Файл пишется через `canonicalJson`. Читатель игнорирует неизвестные ключи верхнего уровня.

## Приёмка

1. **A1.** `analysis_id` и SHA-256 `analysis-result.json` для входа с ошибками совпадают до и после
   изменения (базовый тест первым коммитом на неизменённом коде).
2. **A2.** Прогон без ошибок: набор файлов каталога и `manifest.json` не изменились (golden
   хранилища без правок).
3. **A3.** CSV/XML/Gatling text/Gatling binary с неуспешными сэмплами дают `error-groups.json`; сумма
   `count` показанных групп плюс `omitted_error_count` плюс `untracked_error_count` равна
   `total_error_count` и `overall.error_count`.
4. **A4.** Тест лимитов: сообщение 10 000 символов обрезается до 200; код 100 символов до 64; 3000
   различных сообщений дают `untracked_error_count` > 0 и ровно 20 групп; управляющие символы,
   `‮`, переводы строк, одиночный суррогат очищены.
5. **A5.** Сообщение `<script>x</script>`, `----`, `]]>`, `&` не ломает HTML (нет живого тега), AsciiDoc
   (литеральный блок не закрывается) и Confluence (разбор XML успешен).
6. **A6.** Старый анализ без артефакта и с ошибками: отчёт содержит фразу «Разбивка ошибок недоступна»,
   без ошибок раздела нет.
7. **A7.** `ltv analyze --out-dir` и `ltv report` на реальном JTL с ошибками показывают топ ошибок в
   `report.html` и `summary.txt`; `ltv summary` JSON содержит `error_groups`.
8. **A8.** Только осознанно затронутые golden-файлы изменены, каждый перечислен с причиной в PR.

## Проверки

- Целевые: `gradlew test --tests "*ErrorGroups*"` и тесты парсеров/рендеров через
  `Invoke-LtvSlot`.
- Полный (на результате слияния с origin/main): `Invoke-LtvExclusive { gradlew --no-daemon cleanTest check installDist }`;
  `cd ui; npm run typecheck; npm run lint; npm run test:contracts`; оффлайн Playwright
  (`Invoke-LtvE2E`); `python tools/verify_slice0.py`; `python tools/changelog_assemble.py --check`;
  markdownlint по изменённым `.md`.

## Задачи

- [ ] 0. План и совет Astra (read-only), правки плана.
- [ ] 1. Базовый тест A1 на неизменённом коде (отдельный коммит).
- [ ] 2. Красный тест парсеров, `LoadSample`, парсеры.
- [ ] 3. Красный тест аккумулятора/лимитов, `ErrorGroups.kt`.
- [ ] 4. Красный тест `AnalysisService` (артефакт, A2, A3), запись.
- [ ] 5. Красные тесты рендеров (A5, A6), HTML/AsciiDoc/Confluence/CLI, места вызова.
- [ ] 6. Осознанное обновление golden (отдельный коммит).
- [ ] 7. ADR 0031, документация, changelog.
- [ ] 8. Совет Astra по диффу, слияние origin/main, полный прогон, push, PR.
