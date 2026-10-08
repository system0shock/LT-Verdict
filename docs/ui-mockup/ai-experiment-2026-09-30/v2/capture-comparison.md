# Захват реального запроса Qwen Code 0.21.1 и сравнение с харнессом v1 (0 запросов к провайдеру)

Способ: Qwen Code 0.21.1 (закреплённая версия, sha256 `cli-entry.js` совпал с `CLI_ENTRY_SHA256`) запущен headless с продуктовыми флагами на `relay2.mjs` в режиме `capture`
(fake-ответ, провайдеру ничего не уходит). Сохранено входящее тело (`incoming-request.json`) и то, что relay переслал бы провайдеру (`forwarded-request.json`), без заголовков.
Файлы: `capture/S0-K04`, `capture/S1-K02`, `capture/S2-K02`. Продуктовый relay не менялся; расхождения с продуктом перечислены в `preregistration-v2.md`, раздел 2.

| Параметр | харнесс v1 | Qwen Code 0.21.1 | Комментарий |
|---|---|---|---|
| `temperature` | не задан | `0` | relay оставляет; продукт работает при 0 |
| `max_tokens` | не задан | `64000` | relay удаляет перед провайдером |
| `stream` | `false` | `true` + `stream_options.include_usage` | |
| `tool_choice` | не задан | не задан | в обоих случаях модель сама решает вызвать tool |
| описание tool | «Return the final structured output matching the required JSON schema.» | длинное, «CRITICAL ... the ONLY way to deliver the final result ... the first call with valid arguments ends the session ... MUST validate ... may retry» | |
| schema в `parameters` | с `$schema`, `$id` | без них, остальное то же | |
| system | system-prompt.md | то же (1752 B), байт в байт | |
| user | evidence JSON | три `<system-reminder>` (skills, контекст Qwen: дата, ОС, cwd и список каталога, повтор даты) + evidence JSON | cwd в эксперименте - пустой временный каталог |
| `n`, `parallel_tool_calls` | 1, false | добавляет relay | |

Локальная проверка: Qwen Code валидирует аргументы tool по схеме (`additionalProperties:false`); при ошибке отправляет модели текст ошибки и делает повторный запрос.
В продуктовом пути повторный запрос блокирует relay (409), и запуск заканчивается ошибкой `error_during_execution`.

Объясняет ли захват обёртки v1? Не полностью и, судя по смоуку, не главная причина. Захват показывает пять отличий (temperature 0, развёрнутое описание tool,
stream, schema без `$schema`/`$id`, user-контекст). Смоук v2 (раздел `CHANGELOG-after-smoke-v2.md`): при ВСЕХ продуктовых параметрах первый ответ DeepSeek снова обёрнут (`{"arguments": "<JSON-строка>"}`),
второй нет. Значит обёртка свойственна модели при вызове tool, а не артефакт нашего запроса; JSON-режим Qwen Code её не убирает, а превращает в сбой запуска. Частота измерена в пилоте v2 (метрика W и критерий C6 в `report-v2.md`).
