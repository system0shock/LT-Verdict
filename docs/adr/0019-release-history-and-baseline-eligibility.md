# ADR 0019 — История релизов как сущность: профиль нагрузки, сопоставимость, baseline только из PASS

**Дата:** 2026-10-01

**Статус:** Proposed. При принятии частично меняет `docs/adr/0004-local-baseline-selection.md` (допуск `FAIL` и `NO_POLICY` к выбору baseline; один активный baseline на каталог данных) и `docs/adr/0010-baseline-conditions-confirmation.md` (область очистки записей условий). Принятый `docs/adr/0017-baseline-candidates-and-confirmation.md` сохраняет правило «предупреждение, не отказ» для отношений между baseline и текущим анализом.

## Контекст

Владелец описал рабочий масштаб 2026-09-29/30: обычно не более десяти протоколов, чаще пять; в одном протоколе сравниваются два–четыре релиза. Протоколом здесь называется существующее поле `series` формата `local-baseline.v1`, ограниченное 128 байтами UTF-8 (`src/main/kotlin/io/ltverdict/core/BaselineComparison.kt:925`, `src/main/kotlin/io/ltverdict/core/BaselineComparison.kt:931`, `src/main/kotlin/io/ltverdict/core/BaselineComparison.kt:937`). Материалы макета и плана (`docs/ui-mockup/lt-verdict-ui-mockup.html`, `feature-gap.md`, `implementation-plan.md`) лежат неотслеживаемыми в основном рабочем дереве владельца и не входят в `origin/main`; они задают сценарий экрана, но не подтверждают текущую реализацию. Ссылки `файл:строка` на код относятся к `e8e096f`.

Сегодня сохраняются неизменяемые анализы: `run_id` составлен из типа источника и SHA-256 входа; повторная запись того же `analysis_id` возвращает существующий анализ (`src/main/kotlin/io/ltverdict/storage/RunBundleStore.kt:120`, `src/main/kotlin/io/ltverdict/storage/RunBundleStore.kt:249`). Манифест фиксирует путь, SHA-256 и размер каждого артефакта (`src/main/kotlin/io/ltverdict/storage/RunBundleStore.kt:691`, `src/main/kotlin/io/ltverdict/storage/RunBundleStore.kt:765`). Любой лишний файл в каталоге анализа нарушает проверку состава артефактов (`src/main/kotlin/io/ltverdict/storage/RunBundleStore.kt:685`). Поэтому изменяемые сведения о релизе нельзя поместить в анализ или в его identity.

Существующая динамика выбирает технически сопоставимые анализы, сортирует их по `run.started_at` убыванию и ограничивает ответ 1–100 строками (`src/main/kotlin/io/ltverdict/core/RunComparison.kt:39`, `src/main/kotlin/io/ltverdict/core/RunComparison.kt:42`, `src/main/kotlin/io/ltverdict/core/RunComparison.kt:417`). Поля `application_version` и `load_profile` уже присутствуют в строках и экспорте, но `/analytics` их не заполняет (`src/main/kotlin/io/ltverdict/core/RunComparison.kt:61`, `src/main/kotlin/io/ltverdict/core/AnalyticsExport.kt:65`, `src/main/kotlin/io/ltverdict/web/LocalApi.kt:690`). Список анализов запуска показывает verdict и validity, но не плечо и не хэш снимка (`src/main/kotlin/io/ltverdict/web/LocalApi.kt:882`).

История анализов не является полным реестром релизов. `readComparisonHistory` ограничивает обход 1 000 записями, 16 MiB метаданных и 4 096 просмотренными элементами; обход прекращается до сортировки и выставляет `truncated` (`src/main/kotlin/io/ltverdict/storage/RunBundleStore.kt:437`, `src/main/kotlin/io/ltverdict/storage/RunBundleStore.kt:443`, `src/main/kotlin/io/ltverdict/storage/RunBundleStore.kt:469`, `src/main/kotlin/io/ltverdict/storage/RunBundleStore.kt:481`). Каждый читаемый документ identity, result или run ограничен 8 MiB (`src/main/kotlin/io/ltverdict/storage/RunBundleStore.kt:507`, `src/main/kotlin/io/ltverdict/storage/RunBundleStore.kt:513`). Замер ADR 0014 получил `analysis-result.json` размером 32,5 и 54,7 МБ, поэтому такие анализы не попадают в эту динамику (`docs/adr/0014-resource-series-limits-autostep-arm-api.md:943`). `/analytics` сообщает `history_scan_truncated` и пределы сканирования (`src/main/kotlin/io/ltverdict/web/LocalApi.kt:723`). Нельзя обещать полный список последних релизов, просто переименовав эту динамику.

Сейчас manual-выбор проверяет существование анализа, но не `VALID`, `COMPLETE` и `PASS` (`src/main/kotlin/io/ltverdict/web/LocalApi.kt:1030`). Statistical-выбор требует `VALID`, `COMPLETE`, разных `run_id` и одинаковой технической семантики, но не проверяет `policy_verdict` (`src/main/kotlin/io/ltverdict/core/BaselineComparison.kt:101`, `src/main/kotlin/io/ltverdict/core/BaselineComparison.kt:102`, `src/main/kotlin/io/ltverdict/core/BaselineComparison.kt:115`). ADR 0004 прямо допускал `FAIL` и `NO_POLICY` (`docs/adr/0004-local-baseline-selection.md:46`, строки 46-48). Значения verdict в публичном результате — `PASS`, `FAIL`, `NO_POLICY`, `NO_VERDICT`; validity — `VALID`, `DEGRADED`, `INVALID` (`docs/contracts/result/v1/analysis-result.schema.json:30`, `docs/contracts/result/v1/analysis-result.schema.json:37`).

ADR 0017 принят и уже определил три неблокирующих предупреждения и явное подтверждение условий пары (`docs/adr/0017-baseline-candidates-and-confirmation.md:5`, `src/main/kotlin/io/ltverdict/core/BaselineComparison.kt:155`, `src/main/kotlin/io/ltverdict/core/BaselineComparison.kt:156`). ADR 0014 принял измеренные пределы снимка `S_max = 1 024`, `C_max = 1 500 000`, `B_max = 32 MiB`; `resource_arm` остаётся частью будущего среза P1 (`docs/adr/0014-resource-series-limits-autostep-arm-api.md:3`, `docs/adr/0014-resource-series-limits-autostep-arm-api.md:484`, `src/main/kotlin/io/ltverdict/core/ResourceSnapshot.kt:651`). ADR 0014 и ADR 0017 передали сюда число активных baseline, область по плечу и очистку условий (`docs/adr/0014-resource-series-limits-autostep-arm-api.md:562`, `docs/adr/0017-baseline-candidates-and-confirmation.md:299`).

## Решение

### 1. Запись релиза и её факты

Релиз — явно созданная локальная запись `local-release.v1`, ссылающаяся на сохранённые анализы. Это приватная версия формата, как `local-baseline.v1` и `local-baseline-conditions.v1`; публичная схема в `docs/contracts` для неё не создаётся. Запись не копирует RunBundle, результат или числовые метрики.

Запись содержит строго следующие поля:

| Поле | Смысл |
| --- | --- |
| `schema_version` | Константа `local-release.v1`. |
| `release_id` | Серверный неизменяемый идентификатор записи. |
| `series` | Протокол; непустая строка до 128 байт UTF-8. |
| `label` | Отображаемое имя до 128 байт UTF-8. Не уникально: повторные прогоны одного релиза допустимы. |
| `run_id` | Общий запуск всех перечисленных анализов. |
| `started_at` | Копия неизменяемого `run.json.started_at`; задаёт хронологию теста. |
| `analyses` | От одного до четырёх объектов с `analysis_id`, `arm`, `policy_verdict`, `run_validity`, `coverage_status`. Последние три значения — копии фактов результата. |
| `profile` | Заявленный профиль нагрузки или `null`. |
| `notes` | Пользовательский текст до 1 KiB; в ключ профиля не входит. |
| `created_at`, `updated_at` | Серверное время создания и последней замены записи. |

На записи все `analysis_id` различны и принадлежат одному `run_id`. Каждый анализ обязан иметь `run.json`; анализ без него нельзя зарегистрировать, ответ — `RELEASE_ANALYSIS_NO_RUN_METADATA`. Невалидный запуск сейчас сохраняет результат без `run.json`, валидный сохраняет его с `started_at` (`src/main/kotlin/io/ltverdict/core/AnalysisService.kt:200`, `src/main/kotlin/io/ltverdict/core/AnalysisService.kt:489`, `src/main/kotlin/io/ltverdict/core/AnalysisService.kt:664`). Значение `started_at` сверяется с документами выбранных анализов. Если они расходятся, запись отклоняется, а не получает произвольную дату.

Значения `arm` в одной записи различны. Анализ без плеча допускается только как единственный элемент `analyses`. До P1 все анализы без `resource_arm`, поэтому один релиз содержит ровно один анализ. После P1 `arm` копируется из неизменяемого `identity.resource_arm`, заданного ADR 0014; клиент не может приписать анализ другому плечу (`docs/adr/0014-resource-series-limits-autostep-arm-api.md:506-513`). Копии verdict, validity и coverage при POST/PUT сверяются с настоящими документами. Они служат для списка и фильтра; при назначении baseline настоящий результат всегда читается снова. Отсутствующий позднее анализ обозначается в списке `analysis_state: MISSING`, без подмены другим.

### 2. Идентификатор, хранение и полный список

`release_id` создаёт сервер: 13-значная миллисекундная метка `started_at` с ведущими нулями, дефис и восемь случайных hex-символов. Лексикографический порядок идентификаторов совпадает с хронологией тестов; одинаковое время различает случайный суффикс. Курсор `after` означает последний выданный `release_id`, исключительно. Клиент не задаёт имя файла.

Запись хранится в `<data>/releases/<release_id>.json`, вне каталога анализа. Каталог создаётся при первой записи. Предел записи — 8 KiB, предел каталога — 1 000 файлов релизов, включая повреждённые: POST сверх предела отвечает `422 RELEASE_LIMIT_REACHED`. При масштабе владельца это до 100 релизов на каждый из десяти протоколов. Канонический JSON, staging, `ATOMIC_MOVE`, принудительная запись файла и каталога, запрет symlink и специальных файлов, а также `operationLock` повторяют образец изменяемых `baseline.json`, `baseline-conditions` и `run-period.json` (`src/main/kotlin/io/ltverdict/storage/RunBundleStore.kt:275`, `src/main/kotlin/io/ltverdict/storage/RunBundleStore.kt:308`, `src/main/kotlin/io/ltverdict/storage/RunBundleStore.kt:374`, `src/main/kotlin/io/ltverdict/storage/DataDirectory.kt:23`). Каталог данных по-прежнему открывает один процесс через `.ltv.lock` (`src/main/kotlin/io/ltverdict/storage/DataDirectory.kt:49`, `src/main/kotlin/io/ltverdict/storage/DataDirectory.kt:71`).

Список просматривает весь ограниченный каталог, чтобы точно получить `series_summary` и учесть повреждения; для фильтра по `series` он читает не более 1 000 записей по 8 KiB, то есть не более 8 MiB содержимого. Типичный объём ожидается около 1–2 MiB и подлежит замеру. После отбора возвращается только нужная страница. Сортировка по `release_id` выполняется до усечения страницей; `truncated` в этом API нет. Это отдельная гарантия полноты реестра релизов, которой нет у `readComparisonHistory`.

Повреждённый JSON, неверное имя, недопустимая структура, превышение 8 KiB и неизвестная версия пропускаются в списке. Ответ несёт `corrupt_count`, до 20 имён в `corrupt_names` и причину `UNSUPPORTED_VERSION` для неизвестной версии. `GET` повреждённой записи по идентификатору отвечает `500 CORRUPT_RELEASE`; `DELETE` удаляет её по безопасному идентификатору без разбора содержимого, что даёт путь восстановления. Неизвестная версия не преобразуется молча.

### 3. Приватный API

Новые маршруты используют существующую проверку Host и те же Origin/session/CSRF требования для мутаций, включая новый метод PUT. Сейчас перехватчик применяет эту проверку только к POST и DELETE, поэтому реализация PUT обязана расширить его (`src/main/kotlin/io/ltverdict/web/LocalApi.kt:137`, `src/main/kotlin/io/ltverdict/web/LocalApi.kt:145`). JSON тела ограничен 16 KiB и глубиной 8, как текущие baseline-запросы; наборы ключей строго заданы (`src/main/kotlin/io/ltverdict/web/LocalApi.kt:987`, `src/main/kotlin/io/ltverdict/web/LocalApi.kt:1010`).

| Метод и путь | Поведение |
| --- | --- |
| `GET /api/releases` | Необязательные `series`, `after`, `limit` (1–100, по умолчанию 50). Ответ: `releases`, `next_after` или `null`, `series_summary: [{series, count}]`, `corrupt_count`, `corrupt_names`. |
| `GET /api/releases/{releaseId}` | Полная запись и вычисляемое состояние её ссылок. |
| `POST /api/releases` | Проверяет ссылки и факты, создаёт запись и серверный `release_id`. |
| `PUT /api/releases/{releaseId}` | Заменяет `label`, `notes`, `profile`, `analyses` в пределах прежнего `run_id`; `series`, `run_id`, `release_id`, `started_at`, `created_at` неизменны. |
| `DELETE /api/releases/{releaseId}` | Удаляет только запись релиза. Анализы, выбранный baseline и записи условий остаются. |

Сводка каждого анализа и релиза включает `baseline_eligible` и `ineligible_reasons`, вычисленные по сохранённым копиям и признаку `MISSING`. Это удобство UI, не решение сервера о допуске. `label` не уникален; UI предупреждает о совпадении, но не запрещает его.

### 4. Профиль и три независимых слоя сопоставимости

`profile` — заявление пользователя о заданных условиях, а не реконструкция из JTL или достигнутого RPS. ADR 0004 перечисляет именно scenario/mix, environment/dataset, load model, targets/stages, pacing и ограничения генератора; фактический RPS не входит в утверждение об одинаковых условиях (`docs/adr/0004-local-baseline-selection.md:19`, `docs/adr/0004-local-baseline-selection.md:40`).

Профиль содержит ровно шесть полей: `scenario_mix`, `environment_dataset`, `load_model`, `targets_stages`, `pacing`, `generator_limits`. Каждое — строка до 128 байт UTF-8 или `null`; весь `profile` тоже может быть `null`. Сравнение двух заявленных профилей — равенство канонических значений всех шести полей. Ответ `MATCH` либо `MISMATCH` со списком `differing_fields` в фиксированном порядке полей. `notes` и фактические метрики не участвуют.

Три слоя не заменяют друг друга:

1. `compatible` — техническое равенство `analysis_mode` и полей identity из `SEMANTIC_FIELDS`: `source_type`, `engine`, `parsers`, `modules`, `input_versions`, `outputs`, `histogram`, `normalization`, `limits`. При отсутствии любого поля ключ не строится; `policy_sha256` в нём нет (`src/main/kotlin/io/ltverdict/core/BaselineComparison.kt:603`, `src/main/kotlin/io/ltverdict/core/BaselineComparison.kt:942`).
2. Профиль — сравнение заявлений о шести условиях; он ничего не доказывает о фактическом выполнении опыта.
3. `comparability` — сохранённое явное решение пользователя для точной baseline/current-пары и, при наличии, пары окон по ADR 0010 и ADR 0017. `USER_CONFIRMED` возникает только при `conditionsConfirmed == true` (`docs/adr/0010-baseline-conditions-confirmation.md:21`, `src/main/kotlin/io/ltverdict/core/BaselineComparison.kt:155`).

Совпадение профиля не выдаёт `USER_CONFIRMED`, не меняет числа, `metrics` и `window_comparison`. Отсутствие профиля остаётся `null` в новом поле, а условия пары — `UNCONFIRMED`, пока пользователь их явно не подтвердил.

### 5. Сравнение и предупреждения

Ответ comparison получает `profile`. Если baseline- либо текущий анализ не сопоставлен однозначно с релизом, или у одного из найденных релизов `profile == null`, поле равно `null`. Иначе оно содержит `status: MATCH|MISMATCH`, `differing_fields`, `baseline_release_id`, `current_release_id`. Поиск по `analysis_id` просматривает не более 1 000 локальных записей на запрос; отдельный индекс не вводится. Такой же принцип ограниченного сканирования уже отмечен для истории анализов (`src/main/kotlin/io/ltverdict/storage/RunBundleStore.kt:436`).

К существующему массиву `warnings` дописываются после трёх кодов ADR 0017:

1. `BASELINE_IS_CURRENT_ANALYSIS` или `BASELINE_IS_CURRENT_RUN` — существующие взаимоисключающие предупреждения.
2. `CURRENT_IN_CANDIDATE_SET` — существующее предупреждение statistical-режима.
3. `BASELINE_NOT_PASS` — настоящий baseline-анализ при сравнении не имеет `policy_verdict == PASS`; после нового правила это возможно прежде всего у прежнего `baseline.json`.
4. `POLICY_DIFFERS` — `policy_sha256` в двух identity различаются: `PASS` относится к конкретной политике.
5. `PROFILE_MISMATCH` — оба профиля заявлены и различаются.

Именно такой порядок детерминирован. Первые три кода уже формируются в `compareAnalyses` (`src/main/kotlin/io/ltverdict/core/BaselineComparison.kt:156`). Новые коды, как и старые, не блокируют сравнение и не меняют метрики, verdict, validity, coverage или статусы окон. Отсутствующий профиль не превращается в отдельное предупреждение. Текущий run не отклоняется при выборе baseline: в момент глобального выбора тестируемый прогон неизвестен, а ADR 0017 решает самосравнение предупреждением (`docs/adr/0017-baseline-candidates-and-confirmation.md:55`, `docs/adr/0017-baseline-candidates-and-confirmation.md:112`).

### 6. Baseline только из PASS

Для каждого кандидата обоих режимов `POST /api/baseline` проверяет настоящий сохранённый результат: `run_validity == VALID`, `analysis_coverage.status == COMPLETE`, `policy_verdict == PASS`. Нарушение возвращает 422 с `BASELINE_CANDIDATE_INVALID`, `BASELINE_CANDIDATE_INCOMPLETE` либо новым `BASELINE_CANDIDATE_NOT_PASS`; сообщение последнего называет фактический verdict. Общий текст `baselineIneligible` перестаёт говорить только о statistical baseline (`src/main/kotlin/io/ltverdict/web/LocalApi.kt:1090`). Дополнительные требования statistical-режима — `comparable == true`, 3–20 кандидатов из разных runs, доступные метрики и одинаковая техническая семантика — остаются (`src/main/kotlin/io/ltverdict/web/LocalApi.kt:1036`, `src/main/kotlin/io/ltverdict/core/BaselineComparison.kt:94`, `src/main/kotlin/io/ltverdict/core/BaselineComparison.kt:108`, `src/main/kotlin/io/ltverdict/core/BaselineComparison.kt:115`).

Это частично отменяет допуск `FAIL` и `NO_POLICY` в ADR 0004. Пара из двух релизов по-прежнему использует manual baseline: `median-rank-v1` требует минимум три кандидата (`src/main/kotlin/io/ltverdict/core/BaselineComparison.kt:928`). Manual тоже выбирает только `PASS`. Неизменяемый verdict самого baseline-анализа проверяется при выборе; отношения этой пары с будущим текущим анализом — самосравнение, политика, профиль и подтверждение условий — остаются предупреждениями и решениями при сравнении. UI вправе скрыть открытый run из предложения кандидатов, но сервер не вводит такой отказ.

Ранее сохранённый `<data>/baseline.json` читается без миграции и без нового запрета на чтение. Его структурная валидация остаётся прежней (`src/main/kotlin/io/ltverdict/storage/RunBundleStore.kt:540`, `src/main/kotlin/io/ltverdict/storage/RunBundleStore.kt:555`). Если его настоящий анализ не `PASS`, comparison выдаёт `BASELINE_NOT_PASS`; отдельные `LEGACY_INELIGIBLE` и override не вводятся. Форматы `local-baseline.v1` и `local-baseline-conditions.v1` не меняются.

### 7. Активный baseline по протоколу и плечу

Область активного baseline — пара `(series, arm)`. `series` берётся из выбора `local-baseline.v1`, `arm` — из `identity.resource_arm` baseline-анализа; отсутствие поля до P1 означает `null`. После P1 ADR 0014 добавляет плечо в технический ключ и допускает baseline-дельту только между одинаковыми плечами (`docs/adr/0014-resource-series-limits-autostep-arm-api.md:555`). Сопоставление A с B остаётся отдельным контрастом, не baseline-дельтой.

Срез D5b хранит не более 64 активных слотов в `<data>/baselines/<sha256 канонического {series, arm}>.json`, с пределом 32 KiB на файл. Содержимое остаётся `local-baseline.v1`: слот задаёт область хранения, новый ключ в selection не добавляется. Старый `<data>/baseline.json` читается как legacy-слот по собственным `series` и плечу ссылки. При записи нового слота с тем же ключом новый файл становится победителем; после успешной атомарной записи старый файл удаляется. Если процесс остановится между этими действиями, новый слот уже выигрывает.

Comparison выбирает слот по `series` релиза, которому принадлежит текущий анализ, и `arm` из его identity. Явно заданный query `series` для незарегистрированного анализа выбирает слот этой серии и его плеча. Если анализ не зарегистрирован и `series` не задан, используется прежний `baseline.json`, как сегодня. Если заданная серия противоречит серии найденного релиза, запрос отклоняется как неоднозначный, без молчаливого выбора другого эталона. Такой же выбор слота применяется к чтению и записи baseline-conditions. `GET /api/baseline` без параметров сохраняет поле `baseline` для legacy-файла или `null` и добавляет `baselines` со всеми эффективными слотами.

`DELETE /api/baseline` принимает необязательные `series` и `arm`. Адресное удаление затрагивает выбранный слот либо legacy-файл; без параметров сохраняется явная операция очистки доступного legacy-baseline. Удаляются только записи условий, чья точная baseline-ссылка относится к удалённому выбору и не используется другими активными слотами. Binding условий остаётся парой immutable ссылок и окон, поэтому запись общей пары сохраняется для другого слота (`docs/adr/0010-baseline-conditions-confirmation.md:21`). Это частично меняет прежнее правило удаления всех записей условий (`docs/adr/0010-baseline-conditions-confirmation.md:35`, `src/main/kotlin/io/ltverdict/storage/RunBundleStore.kt:340`). Замена слота, как и раньше, не удаляет условия другой пары.

До P1 каждый релиз содержит один анализ с `arm == null`, а D5b уже даёт отдельный активный baseline каждой серии. После P1 запись может показывать до четырёх разных плеч одного запуска и выбирать по одному baseline на `(series, arm)`. Интерфейс показывает два независимых verdict рядом. Общий verdict стенда «оба плеча прошли» здесь не определяется; правила свёртки относятся к зарезервированному ADR-B (0018).

### 8. Динамика, UI и граница документов

Экран «История релизов» выбирает протокол и по умолчанию показывает последние `N ≤ 4` его релиза. Таблица показывает label, дату теста, verdict каждого плеча, число нарушений, профиль и знак «другой профиль», а также действия «Открыть», «Сделать baseline», «Сравнить». Динамика раскрашивает точки по verdict. Одинаковые labels различаются датой и `release_id`; два плеча показаны рядом без свёрнутого verdict. Автоматического продвижения baseline нет, как требует ADR 0004 (`docs/adr/0004-local-baseline-selection.md:122`). UI предлагает manual-эталоном последний `PASS`-релиз с совпавшим профилем и тем же плечом. Statistical доступен при не менее чем трёх `PASS`-релизах одного профиля и плеча из разных runs; прежний диапазон 3–20 сохраняется. ADR 0017 отмечает слабость statistical-выбора на 2–4 прогонах, и этот ADR её не скрывает (`docs/adr/0017-baseline-candidates-and-confirmation.md:107`).

Диалог «Сохранить как релиз» предлагает профиль предыдущего релиза выбранной серии для явного подтверждения или правки. Кнопка назначения baseline для не-`PASS`, не-`VALID`, неполного либо отсутствующего анализа неактивна с причиной; сервер всё равно выполняет собственную проверку. `BaselinePanel.vue` показывает новые 422-коды и предупреждения `BASELINE_NOT_PASS`, `POLICY_DIFFERS`, `PROFILE_MISMATCH` в фиксированном порядке. Макет (неотслеживаемый `docs/ui-mockup/lt-verdict-ui-mockup.html`), экран «История релизов», блокирует baseline при другом профиле; предложенное серверное правило этого не делает: несовпадение профиля предупреждает, а решение пользователя о паре остаётся явным по ADR 0017. Нужны сообщения об усечении аналитики, повреждённых записях и пустых состояниях.

Для зарегистрированных анализов `/analytics` заполняет существующие `application_version` из `label` и `load_profile` кратким представлением шести полей профиля в фиксированном порядке; `jenkins_build` не заполняется. Отбор строк по техническому ключу не меняется (`src/main/kotlin/io/ltverdict/core/RunComparison.kt:316`, `src/main/kotlin/io/ltverdict/core/RunComparison.kt:424`). Таблица истории берёт дату, verdict, профиль и baseline-бейджи из записей релизов, а p95, ошибки и RPS — из динамики. Если `history_scan_truncated == true` или документ анализа превышает 8 MiB, UI объясняет отсутствие чисел, а не показывает его как ноль (`src/main/kotlin/io/ltverdict/web/LocalApi.kt:724`, `src/main/kotlin/io/ltverdict/storage/RunBundleStore.kt:513`). Метрики в релиз не копируются.

Граница с соседними решениями: ADR 0004 сохраняет точный immutable baseline и `median-rank-v1`, кроме названных отмен; `docs/adr/0010-baseline-conditions-confirmation.md` сохраняет binding точной пары, кроме области DELETE; ADR 0017 сохраняет три warnings и только явное подтверждение; ADR 0014 задаёт ресурсные пределы и будущее `resource_arm`; ADR-B (зарезервированный 0018) определит платформенный verdict и малую выборку; ADR-E отдельно решает печать хэша в результате; П-1 в `docs/analytics-scale-triage.md`, раздел «П-1», касается агрегации подов, а не релизного профиля. Номер 0016 зарезервирован под план «числа ядра». В `docs/adr` есть два файла с номером 0010; здесь всегда назван нужный файл полностью.

## Следствия

### UI, совместимость и миграция

Отсутствие `<data>/releases` означает пустую историю. Старые runs, analyses, `baseline.json` и записи условий не переписываются. Автоимпорта всех анализов нет: пользователь явно нажимает «Сохранить как релиз». Удаление записи релиза не удаляет анализ и не меняет уже выбранный baseline. Изменение метаданных релиза через PUT не меняет `analysis_id`, потому что identity и каталог анализа не затрагиваются (`src/main/kotlin/io/ltverdict/storage/RunBundleStore.kt:249`, `src/main/kotlin/io/ltverdict/storage/RunBundleStore.kt:685`).

Новый предел в 1 000 файлов делает реестр релизов полным в пределах каталога, но не устраняет усечение независимой истории анализов. Копии статусов в релизе могут устареть при ручном удалении или повреждении анализа; UI показывает `MISSING`, а назначение baseline перечитывает настоящий анализ. Неизвестная версия записи не запускает неявную миграцию.

### Порядок срезов

1. **D5a:** `local-release.v1`, хранилище, API, проверка `PASS` для обоих режимов, новые предупреждения, профиль сравнения, заполнение существующих полей динамики и UI истории. Активный baseline пока один в прежнем `baseline.json`.
2. **D5b:** слоты `(series, arm)`, выбор слота в comparison и baseline-conditions, адресное удаление и сохранение условий других слотов. Срез полезен до P1: тогда `arm == null`, но серии уже разделены.
3. **P1 ADR 0014:** вводит непустое `resource_arm` и анализы плеч. После него те же слоты и записи релиза работают по плечам без смены `local-baseline.v1`.

При принятии этого ADR строки статуса ADR 0004 и `docs/adr/0010-baseline-conditions-confirmation.md` получают пометку «частично отменён ADR 0019» в PR реализации или документационного принятия; этот PR их не меняет.

## Отклонённые альтернативы

- Релиз внутри RunBundle либо identity: mutable-запись меняла бы `analysis_id` или состав неизменяемого каталога.
- Автосоздание релиза на каждый анализ: загрязняет историю переанализами и не выражает явное имя релиза. Метка только на уровне run теряет независимые анализы плеч.
- Отдельная БД или индекс: при 1 000 файлах ограниченный скан проще и достаточен; индекс добавляется лишь при измеренной необходимости.
- Baseline из `FAIL`/`NO_POLICY` с предупреждением или принудительным override: это не прошедший политику эталон. Отказ «текущий run»: выбор глобален, а ADR 0017 уже определил предупреждение.
- Профиль из JTL или фактического RPS: JTL не содержит всех заявленных условий, а падение достигнутого RPS может быть эффектом деградации.
- Отдельная сущность «протокол» с файлом и версиями профиля: существующее `series` покрывает требуемый масштаб.
- Серверная блокировка baseline из-за `PROFILE_MISMATCH`: подменяет явное решение пользователя автоматическим выводом из заявления.
- Два ручных имени серии вместо измерения `arm`: плечо уже определяется неизменяемой identity после P1, а имена могут разойтись.
- Копирование p95, ошибок и RPS в запись релиза: создаёт второй источник чисел и не исправляет полноту `/analytics`.

## Тесты и план проверки

1. Storage/API: одинаковые labels разрешены; до P1 второй анализ без плеча отвергается; после P1 допустимы разные плечи одного run; чужой run, несовпадающие копии фактов и анализ без `run.json` отвергаются; запись остаётся вне бандла. На 1 000 записях пагинация и `series_summary` полны независимо от порядка обхода; 1 001-я запись получает 422.
2. Повреждение и восстановление: список выдаёт `corrupt_count` и имена, неизвестную версию помечает `UNSUPPORTED_VERSION`; GET даёт `CORRUPT_RELEASE`, DELETE удаляет файл без разбора; отсутствующий анализ даёт `MISSING`.
3. Baseline: manual и statistical одинаково отвергают `INVALID`, `INCOMPLETE`, `FAIL`, `NO_POLICY`, `NO_VERDICT`; принимают только `VALID`/`COMPLETE`/`PASS`. Старый `baseline.json` читается и при сравнении не-`PASS` даёт `BASELINE_NOT_PASS`. Текущий run не получает отказа по этому признаку.
4. Сравнение и слоты: порядок пяти позиций warnings точен; `profile == null`, `MATCH`, `MISMATCH` воспроизводимы; профиль не создаёт `USER_CONFIRMED` и не меняет числа. До P1 работают два слота разных `series` с `arm == null`; после P1 A и B независимы; DELETE одного слота не удаляет условия, используемые другим.
5. UI: e2e проверяет текст и порядок warnings, новые 422-коды, пустую историю, повтор label, `MISSING`, сообщение `history_scan_truncated` и недоступную кнопку baseline с причиной. Проверить `RunBundleStoreTest`, `BaselineComparisonTest`, `LocalApiTest`, `ui/e2e/baseline.spec.ts`, затем применимые build, lint, контрактные и документационные проверки. Пределы и задержку скана измерить на 1 000 записях.

## Что не входит

Вердикт по двум плечам, правила платформы и малая выборка ADR-B; автоматическое доказательство одинаковых условий; rolling baseline; изменение форматов `local-baseline.v1`, `local-baseline-conditions.v1`, `analysis-result.v1`; перенос релизов во внешнее хранилище; Jenkins; изменение числовых формул сравнения и корреляций; данные по подам ADR 0020.

## Открытые вопросы владельцу

1. Разрешать ли `NO_POLICY` как исключение для baseline? Рекомендация: нет; перепроанализировать вход с политикой, получив новый `analysis_id`.
2. Нужен ли принудительный baseline не из `PASS` с обязательной причиной? Рекомендация: не вводить в первой версии.
3. Показывать ли `POLICY_DIFFERS` при разных `policy_sha256`? Рекомендация: да, поскольку `PASS` относителен политике.
4. Должен ли `PROFILE_MISMATCH` только предупреждать или также переводить `CANDIDATE` в `DESCRIPTIVE`? Рекомендация: только предупреждать; явное решение о паре остаётся отдельным.
5. Создавать релиз при завершении job из полей формы или только отдельной кнопкой? Рекомендация: кнопка в первой версии.
6. Принять ли предел 1 000 записей? Рекомендация: да; он делает полноту списка проверяемой при масштабе владельца.
7. Вводить ли слоты `(series, arm)` в D5b до P1? Рекомендация: да; разделение по `series` полезно уже при `arm == null`.
8. Если ADR-B введёт «PASS с оговоркой» для малой выборки, считать ли его пригодным baseline? Рекомендация: решить в ADR-B; до того пригоден только обычный `PASS`.
9. Может ли один `analysis_id` принадлежать нескольким записям релиза? Это сделает поиск профиля для comparison неоднозначным. Рекомендация: запретить повторное членство при POST/PUT; повторные прогоны одного label оформлять разными analyses.

Documentation impact: при принятии и реализации обновить README и пользовательское описание истории и baseline, `CHANGELOG.md`, документацию приватного API, а также строки статуса ADR 0004 и `docs/adr/0010-baseline-conditions-confirmation.md`; этот PR меняет только `docs/adr/`.
