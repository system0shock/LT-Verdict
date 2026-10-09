# W2.6 Отчёт для людей: русские заголовки и приложение для машинных сведений (PR 3 из 3)

> **Для исполнителя:** superpowers:executing-plans. Шаги отмечены чекбоксами.

**Goal:** `report.html` читается сверху вниз как документ для человека (вердикт, область и окно, прогон и
график, топ ошибок, правила, транзакции, сравнение с baseline, ограничения), а сырые сведения (метрики
по идентификаторам, проверки, находки, списки evidence, канонический JSON) лежат в конце, в разделе
«Приложение», свёрнутыми. Заголовок страницы, заголовки панелей и подписи шапки по-русски. Критерий W2.6
из `docs/superpowers/plans/2026-10-08-review-work-plan.md`. Общий план и разбиение:
`docs/superpowers/plans/2026-10-09-w2-6-human-report.md` (PR 1 #236 и PR 2 #240 влиты). Числа, вердикты,
тексты нарушений и коды не меняются. `analysis_id`, `identity.json`, `analysis-result.json` и состав
каталога анализа не меняются.

**Architecture:** только `report/HtmlReport.kt`: порядок вызовов секций, русские тексты заголовков,
обёртка сырых блоков в `<details>` внутри `<section><h2>Приложение</h2>`, несколько правил в `STYLE`.
Новых функций-слоёв нет; одна маленькая локальная функция `appendixItem`.

**Tech Stack:** Kotlin, JUnit 5, Playwright (оффлайн e2e, одна правка ожидания).

## Блок AGENTS.md

```text
REQUESTED: машинный хвост HTML-отчёта в приложение в конце; русский <h1> и русские заголовки панелей
  (часть W2.6, PR 3 из 3).
REQUIRED TO ACHIEVE IT:
  - HtmlReport.kt: <title> и <h1> по-русски; подписи шапки (Run, Analysis, Run validity, Policy verdict,
    Coverage) по-русски; секции собираются в порядке: шапка, область вердикта, вердикт, диагностика
    ресурсов, прогон, ошибки, правила, транзакции, сравнение с baseline, ограничения, приложение.
  - Раздел «Приложение» (h2) с текстом-пояснением и свёрнутыми <details><summary> для: Overall and
    transaction metrics, Policy checks, Resource binding/summaries/Window policy outcomes/Resource
    policy checks (если есть), диагностических разделов (если есть), Findings, Evidence IDs, Canonical
    JSON. Заголовки блоков по-русски, содержимое блоков прежнее, обёртка lang="en".
  - Две ссылки в тексте на старые английские названия («Overall and transaction metrics»,
    «Resource policy checks») меняются на новые.
  - STYLE: 3 коротких правила для details/summary (граница, курсор, фокус-кольцо).
  - Тесты: новый HumanReportAppendixTest (порядок и заголовки, доступность, CSP, равенство данных со
    снимком старого HTML); минимальные правки старых ожиданий HTML; e2e: заголовок h1.
  - golden HTML (3 файла + responses.txt) отдельным коммитом, только флагами обновления.
  - Документация docs/user (3 строки), фрагмент changelog.
NOT REQUIRED: AsciiDoc, Confluence, summary.txt/json, junit, CLI, ui/src (вкладки интерфейса), русификация
  тел сырых блоков («Samples:», «unavailable», ключи JSON), новые данные в человеческой части (например,
  строка overall-метрик), JSON-скрипты/раскрывающие скрипты (CSP их запрещает), ADR (контракты файла и
  результата не меняются; формат HTML для человека не контракт), пересчёт старых анализов.
EXPECTED FILES TO CHANGE:
  src/main/kotlin/io/ltverdict/report/HtmlReport.kt
  src/test/kotlin/io/ltverdict/report/HumanReportAppendixTest.kt (новый)
  src/test/kotlin/io/ltverdict/report/{HtmlReportTest,StageReportsTest,WindowHeadingsTest,RunTimelineReportTest}.kt
    (ожидания HTML, список в разделе «Правки существующих тестов»)
  fixtures/report-html-before-ru-appendix/*.html (новый снимок старого HTML для теста равенства данных)
  fixtures/stages/plain-reports/*/report.html (3), fixtures/http-layer/responses.txt (строки report?format=html)
  ui/e2e/report-export.spec.ts (имя h1)
  docs/user/slice-1-local-analysis.md, docs/user/demo-script.md, changelog.d/w2-6-ru-appendix.changed.md,
  этот план
```

## Факты по коду (origin/main aacf265)

- Текущая структура (`ramp-fail`): `h1 lang=en "LT Verdict report"`, `dl lang=en` (Run, Analysis, Run
  validity, Policy verdict, Coverage, затем «Область вердикта»/«Доля окна» с `lang=ru`), русские секции
  («Вердикт и причины», «Прогон», «Правила», «Ошибки», «Транзакции», «Ограничения»), затем английский
  хвост секциями `lang=en`: Overall and transaction metrics, Policy checks, [Resource binding, Resource
  summaries, Window policy outcomes, Resource policy checks], [Source acquisition, Diagnostic analysis,
  Correlations, Anomaly checks, Window metrics], Findings, Evidence IDs, Canonical JSON.
- Хвост уже в конце, но не отделён и не свёрнут; шапка и заголовок английские; `<title>` английский.
- Метрики по всему прогону (overall) в человеческой части не показаны, они есть только в блоке
  «Overall and transaction metrics». После переноса в свёрнутое приложение их труднее найти (см. Ruling 5
  и «Вне скоупа»).
- CSP: `style-src 'sha256-<хэш итогового STYLE>'`; атрибуты `style=""` и скрипты запрещены. `<details>` и
  `<summary>` работают без скриптов, фокус и Enter/Space на `<summary>` дают браузеры (клавиатура).
- Потребители: `PlainReportsGoldenTest`, `LocalApiSnapshotTest` (golden), `HtmlReportTest`,
  `StageReportsTest`, `WindowHeadingsTest`, `RunTimelineReportTest`, e2e `report-export.spec.ts`
  (`getByRole('heading', { name: 'LT Verdict report' })`, два места).

## Ruling'и

1. **Идентификаторы run и analysis остаются в шапке.** Бриф относит «идентификаторы» к приложению; здесь
   это понято как списки `Evidence IDs` и id в сырых блоках. Run id и analysis id нужны при пересылке,
   печати и ссылке из тикета, а свёрнутое `<details>` на печать не выводится. Две строки не мешают
   чтению. Цена ошибки: если владелец хочет и их в приложение, это перенос двух `<dt>/<dd>`.
2. **Свёрнуто по умолчанию, без скриптов.** Каждый сырой блок отдельный `<details>` (без `open`). Цена:
   на печати и в PDF сырые блоки не видны (человеческая часть полная); поиск по странице (Ctrl+F)
   раскрывает совпавший блок в Chrome и Firefox. Один общий `<details>` на весь хвост хуже: пользователь
   разворачивает 40 КБ канонического JSON, чтобы увидеть список находок.
3. **Заголовки сырых блоков по-русски, тела прежние.** «Заголовки панелей по-русски» из задания; тела
   (`Samples:`, `unavailable`, ключи JSON) это данные, их русификация меняет содержимое и не входит.
   Обёртка тела `lang="en"` сохраняется там, где оно сегодня помечено.
4. **Порядок человеческой части по заданию.** Точный порядок (необязательные секции пропускаются):
   шапка (`dl`), «Область вердикта» (только со стадиями), «Вердикт и причины», «Диагностика ресурсов»
   (только при сработавших диагностических правилах), «Прогон», «Ошибки», «Правила», «Транзакции»,
   «Изменения относительно baseline» (только с `--baseline`), «Ограничения», «Приложение». Область
   вердикта остаётся перед вердиктом, как сегодня: фраза о том, что вердикт посчитан по окну steady,
   нужна до заголовка вердикта. Сдвиги против текущего: «Ошибки» выше «Правил»,
   «Изменения относительно baseline» ниже «Транзакций». «Ограничения» остаются последней человеческой
   секцией, потом приложение. Тест порядка идёт по сценариям со всеми необязательными секциями.
5. **Overall-метрики не выносятся в человеческую часть.** Это новое содержимое (новая строка/таблица),
   то есть другой PR. Выявлено при просмотре, в отчёт как предложение.
6. **Структура заголовков:** `h1` один; `h2` секции; `h3` внутри секций (как сейчас) и внутри блоков
   приложения (метрики по id). Раздел «Приложение» это `h2`; `<summary>` не заголовок, но его текст
   служит именем блока. Пропусков уровней нет.
7. **Публичные контракты.** Меняется только содержимое HTML (`ltv report --format html`, `report.html`
   в `--out-dir`, `GET .../report?format=html`, «Экспорт HTML»/`Download HTML`). Формат вывода CLI, коды
   выхода, схемы, `analysis-result`, identity, AsciiDoc, Confluence, summary, junit не меняются.
   Старые заголовки AsciiDoc/Confluence остаются английскими (`== Canonical JSON`, `<h2>Canonical
   JSON</h2>`); выравнивание это предложение в отчёте.
8. **Доказательство «данные не изменились».** Снимок HTML до изменения (5 сценариев: три golden,
   стадийный прогон, богатый результат с ресурсными и диагностическими блоками) лежит в
   `fixtures/report-html-before-ru-appendix/`. Тест извлекает из старого и нового HTML мультимножества
   записей в контексте: строка таблицы целиком (`td|td|...`), пара `dt=dd`, `li`, `p`, `code`, `pre`,
   `h3`, сырой фрагмент `figure` (геометрия графика). Допускается явный список: переименованные
   подписи `dt` (5), две переименованные ссылки в тексте, один новый абзац-пояснение приложения.
   Значения, подставленные между строками, разделами или подписями, тест ловит (запись содержит
   контекст). Меняются только `title`, `h1`, `h2`, `summary`, порядок и обёртки.
9. **Совместимость потребителей HTML.** Схемы и identity не меняются, но меняются якоря: тексты
   `h1`/`h2`, порядок разделов, раскрытие блоков. Внутри репозитория потребители: golden-тесты,
   `HtmlReportTest`, `RunTimelineReportTest` (три зашитых хэша отчёта без timeline пересняты осознанно:
   это историческая гарантия PR 2, после PR 3 доказательство «данные те же» это тест из Ruling 8),
   e2e `report-export.spec.ts`. Внешние скрейперы HTML не поддерживаются и не известны.
10. **Язык.** `h1` и шапка без `lang="en"` (наследуют `ru` от `<html>`); `lang="en"` остаётся на телах
    сырых блоков, не на `summary`. Подписи `h3` метрик содержат метки транзакций пользователя и id
    evidence и не переводятся.
11. **Доступность блоков приложения.** Каждый `<summary>` первый потомок своего `<details>`;
    клавиатура: Tab фокусирует, Enter/Space переключает (встроено в браузер), фокус-кольцо в `STYLE`.
    Проверяется оффлайн Playwright на экспортированном отчёте (фокус, переключение, видимость тела).
    Цена вынесения заголовков в `summary`: они не попадают в навигацию по заголовкам программ
    экранного чтения, но попадают в «навигацию по элементам управления»; приложение как `h2` остаётся
    в навигации. Принято: блоки приложения вторичны. Про печать: свёрнутые блоки на печать не выходят;
    это сознательно (человеческая часть полная, кроме overall-метрик, см. Ruling 5), проверено в
    браузере.

## Правки существующих тестов (перечень, заполняется по ходу)

- `HtmlReportTest`: ожидания английских `<h2>`/`<section lang="en">` и английского `<h1>` (список ниже по
  факту).
- `StageReportsTest`, `WindowHeadingsTest`, `RunTimelineReportTest`: подписи шапки и порядок.
- `RunTimelineReportTest`: три зашитых хэша отчёта без timeline пересняты (Ruling 9).
- `ui/e2e/report-export.spec.ts`: имя h1 в двух тестах; в первый добавлена проверка клавиатуры для
  `<details>` приложения (Ruling 11).

## Приёмка

1. **A1.** `<title>` и `<h1>` русские; `<html lang="ru">`; в документе ровно один `h1`; уровни `h` идут без
   пропусков.
2. **A2.** Порядок секций `h2` как в Ruling 4; последний `h2` это «Приложение».
3. **A3.** Все сырые блоки внутри «Приложения», каждый в `<details><summary>` без атрибута `open`;
   вне приложения нет `lang="en"`-секций и нет JSON-дампа.
4. **A4.** Нет `<script>`, `style=`, обработчиков; CSP-хэш равен SHA-256 итогового `<style>`.
5. **A5.** Равенство данных со снимком старого HTML на 5 сценариях (Ruling 8).
6. **A6.** Все заголовки `h2`, `<summary>`, `<h1>` и подписи `dt` шапки, созданные рендерером, без
   латиницы кроме `LT Verdict`, `baseline`, `JSON`, `RPS`, `evidence` (метки транзакций в `h3` не
   проверяются).
7. **A7.** Golden-отчёты обновлены осознанным запуском отдельным коммитом, diff golden читается по
   структуре, не по числам.

## Команды проверки

```text
Invoke-LtvSlot { .\gradlew.bat --no-daemon test --tests "io.ltverdict.report.*" }
Invoke-LtvExclusive { .\gradlew.bat --no-daemon cleanTest check installDist }   # на результате слияния с origin/main
cd ui; npm run typecheck; npm run lint; npm run test:contracts
Invoke-LtvE2E { npm --prefix ui run e2e }   # оффлайн Playwright
python tools/verify_slice0.py; python tools/changelog_assemble.py --check; markdownlint
```

Documentation impact: `docs/user/slice-1-local-analysis.md` (описание структуры HTML) и
`docs/user/demo-script.md` (замечание об английской нижней части). Журнал: `changelog.d/w2-6-ru-appendix.changed.md`.
