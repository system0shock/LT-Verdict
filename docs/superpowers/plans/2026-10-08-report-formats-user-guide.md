# Форматы ltv report в руководстве пользователя

> **Для исполнителя:** superpowers:executing-plans. Шаги отмечены чекбоксами.

**Goal:** описание команды `ltv report` в `docs/user/slice-1-local-analysis.md`
соответствует коду: добавлены форматы `confluence` и `svg`.

**Architecture:** только Markdown, две правки в одном файле.

**Tech Stack:** Markdown, markdownlint-cli2.

**Spec:** `src/main/kotlin/io/ltverdict/cli/CommandLine.kt` (разбор `--format` и
строка usage), README (json, html, asciidoc, confluence, svg).

## Блок AGENTS.md

```text
REQUESTED: исправить описание команды report: отсутствуют форматы confluence и svg.
REQUIRED TO ACHIEVE IT: блок «CLI» (строка ltv report) и абзац «ltv report читает...»
  в docs/user/slice-1-local-analysis.md.
NOT REQUIRED: любые другие правки руководства (например, «JSON/HTML/AsciiDoc reports»
  в разделе про обзор отчётов), код, README, changelog.
EXPECTED FILES TO CHANGE: docs/user/slice-1-local-analysis.md, этот план.
```

## Факты по коду

- `CommandLine.kt`: `--format` принимает `json`, `html`, `asciidoc`, `confluence`,
  `svg`, `summary`. Строка usage перечисляет первые пять; `summary` внутренний
  режим команды `ltv summary` (она вызывает report с `--format summary`) и в
  описание `ltv report` не включается (документирована отдельная команда).
- `svg` строит график из `rollup-60s.ndjson` сохранённого анализа (UTF-8 текст SVG),
  `confluence` выводит Confluence storage XHTML.

## Ruling

- Ruling: `summary` не добавляется в список `ltv report`: usage его не называет, для
  него есть `ltv summary`. Цена ошибки: читатель не узнает об альтернативной форме
  вызова, что безвредно.

## Приёмка и проверки

- Строка `ltv report ... --format` в руководстве совпадает с usage в `CommandLine.kt`.
- `markdownlint-cli2` по изменённым файлам без ошибок; ссылки не добавлялись.
- Documentation impact: пользовательский документ исправлен; changelog не нужен (нет
  путей src/main, ui/src, docs/contracts, tools).

## Задачи

- [x] Править блок CLI и абзац про `ltv report`.
- [x] Проверки, коммит, push, PR.
