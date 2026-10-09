- Вход стадий нагрузки в CLI и API (W2.5, PR B, [ADR 0030](docs/adr/0030-load-stages-steady-window.md)):
  `ltv analyze <input> --stages <load-stages.json>` и multipart-часть `stages` задания анализа
  (`POST /api/jobs`, одна часть) включают вердикт по окну `steady` без снимка ресурсов. Недопустимое
  объявление даёт в CLI выход 4 со строками `<code> <json-pointer>: <message>`, в API `422 INVALID_STAGES`
  с `errors[{code, json_pointer, message}]`; слишком большой файл или больше 16 стадий это
  `413 RESOURCE_LIMIT_EXCEEDED`. Сочетание стадий со снимком ресурсов, планом ёмкости или онлайн-запросом
  источника отвергается (`STAGES_RESOURCES_CONFLICT`, `STAGES_CAPACITY_CONFLICT`, `STAGES_SOURCE_CONFLICT`:
  в CLI выход 4 до чтения входа и без обращения к источнику, в API `422 INVALID_STAGES`); офлайн-контекст
  `--source-context` без снимка со стадиями разрешён. `steady`, начинающаяся в конце прогона или позже,
  даёт `STAGE_OUTSIDE_RUN` (в CLI выход 4, в API задание `FAILED` с `diagnostic.code`). В строку `analyze`
  справки `ltv --help` добавлен `[--stages <load-stages.json>]`. Прогон без `--stages` и без части `stages`
  не меняется. Подписи в отчётах, `junit.xml`, сводках и UI (PR C) и руководство (PR D) в этом срезе
  не входят.
