# Базовый сценарий: catalog и Prometheus

## Подготовка

Установите дистрибутив LT Verdict, добавьте его `bin` в `PATH` и откройте терминал
в корне репозитория. Ниже используется `ltv`; на Windows команда дистрибутива
называется `ltv.bat`.

Для зондов нужен доступный Prometheus по `http://127.0.0.1:9090`.
Профиль `catalog-prometheus` ожидает метрики `process_cpu_seconds_total` и
`process_resident_memory_bytes` с меткой `job="catalog"`. CPU вычисляется в ядрах
через `rate`, RSS измеряется в байтах; внешняя `sum` сворачивает каждый запрос
в один ряд. `$__interval` заменяется шагом запроса. Для шага 10 секунд интервал
scrape должен быть достаточно коротким, чтобы `rate` получал несколько точек.
Замените `base_url` и селекторы в `connections.json` под свой стенд.
В примере нет аутентификации; настройка credentials через environment описана в
[онлайн-источниках](../../docs/user/online-sources.md).

Для офлайн-команд Prometheus не нужен: `resource-snapshot.json` и `pod-view.json`
уже содержат синтетические данные. Нагрузочный вход длится около двух минут;
плато `steady` занимает 40–100 секунд от начала. Политика проверяет транзакцию
`GET /items`: p95 ≤ 500 мс, p99 ≤ 600 мс, долю ошибок ≤ 0,01, throughput ≥ 1 RPS.
Ресурсные SLA нарушаются при CPU > 0,8 ядра или RSS > 512 МиБ в двух подряд
идущих ячейках. Значения примера остаются ниже этих ресурсных порогов.

## Команды по порядку

Сначала проверьте форму подключения без обращения к сети:

```text
ltv source validate --connections examples/basic/connections.json --profile catalog-prometheus --format json
```

Затем проверьте каждый запрос на вашем Prometheus. Здесь зондируется последняя
минута относительно текущего времени, а не историческое окно примера:

```text
ltv source probe --connections examples/basic/connections.json --profile catalog-prometheus --query-id cpu --window-ms 60000 --step-ms 10000 --format json
ltv source probe --connections examples/basic/connections.json --profile catalog-prometheus --query-id rss --window-ms 60000 --step-ms 10000 --format json
```

Проверьте `series_count` и `decoder_check`: успешный ответ с пустыми рядами
не подтверждает наличие метрик. Зонды печатают результат в stdout и не сохраняют
его на диск. Без доступного Prometheus пропустите зонды и продолжите офлайн.

Воспроизводимый анализ со снимком ресурсов, бизнес-политикой, корреляцией
и pod-view:

```text
ltv analyze examples/basic/input.jtl --policy examples/basic/policy.json --resources examples/basic/resource-snapshot.json --correlation examples/basic/correlation-plan.json --pod-view examples/basic/pod-view.json --data-dir examples/basic/data --out-dir examples/basic/out/resources
```

Окно оценки берётся из `resource-snapshot.json`: `steady`, 00:00:40–00:01:40 UTC.
Корреляционный план проверяет CPU и p95 с контролем достигнутого RPS.
Короткий ряд и слабая вариация могут ограничить диагностику; пример показывает
формат входа, а не доказывает причинность. Pod-view служит диагностической
проекцией и не задаёт SLA.

Отдельно выполните анализ тех же стадий без ресурсов:

```text
ltv analyze examples/basic/input.jtl --policy examples/basic/policy.json --stages examples/basic/load-stages.json --data-dir examples/basic/data --out-dir examples/basic/out/stages
```

Стадия `steady` задаёт то же окно 40–100 секунд. Разгон и снижение нагрузки
помечены `excluded` и не участвуют в оконной оценке бизнес-политики.

Все перечисленные флаги нельзя совместить в одной команде:
`--stages` с `--resources` даёт `STAGES_RESOURCES_CONFLICT`, с `--source` —
`STAGES_SOURCE_CONFLICT`. Онлайн-сбор с `--correlation` или `--pod-view` даёт
`SOURCE_INPUT_CONFLICT`. Поэтому эти варианты показаны отдельными запусками.

## Онлайн-сбор для своего прогона

Чтобы получить настоящие ресурсы, подготовьте JTL вашего сервиса и поменяйте
в `source-request.json` начало и конец на его UTC epoch milliseconds.
Явное окно должно целиком лежать внутри периода JTL и быть кратным шагу.
В примере оно заканчивается на 110-й секунде: последний запрос JTL завершается
раньше 120-й секунды, поэтому правая граница округлена вниз до шага 10 секунд.
Prometheus должен хранить данные этого периода. Следующая команда с исходными
файлами обращается к историческому окну примера 2026-01-01; она полезна только
если такие данные действительно есть в вашем источнике:

```text
ltv analyze examples/basic/input.jtl --policy examples/basic/policy.json --connections examples/basic/connections.json --source examples/basic/source-request.json --data-dir examples/basic/data --out-dir examples/basic/out/online
```

Этот явный запрос создаёт окно снимка `full`, охватывающее 0–110 секунд,
включая разгон. Поэтому его вердикт может отличаться от оценки плато.
Отсутствие метрик отражается в `source_summary` и `analysis_coverage`;
успешный бизнес-анализ не означает успешного сбора ресурсов.

Для корреляции сначала сохраните результат онлайн-сбора, затем используйте
полученный `resource-snapshot.json` с `--resources`. Получить его семантический
хэш можно командой:

```text
ltv source hash examples/basic/resource-snapshot.json
```

Эта команда показывает хэш учебного снимка; для нового снимка подставьте его
путь. Обновите `resource_snapshot_sha256` в плане и pod-view, идентификаторы
рядов и окон, а также `load_input_sha256` и сетку pod-view под новый прогон.
Не подставляйте SHA-256 сырых байтов JSON вместо семантического хэша снимка.
После обновления повторите офлайн-команду с соответствующими путями.

## Где результаты

Каждый `analyze` печатает JSON результата в stdout, а `analysis_id` и `run_id`
в stderr. Каталоги `examples/basic/out/resources/`, `out/stages/` и `out/online/`
содержат `result.json`, `report.html`, `chart.svg`, `summary.txt` и `junit.xml`.
Откройте `report.html` для отчёта, `result.json` для машинной обработки.

Хранилище находится в `examples/basic/data/`; сохранённые анализы — в
`runs/<run_id>/analyses/<analysis_id>/`. Онлайн-анализ дополнительно сохраняет
`source-acquisition.json`, снимок `resource-snapshot.json`, если он сформирован,
и успешные `source-response-N.json`. Учебные файлы лежат вне этого хранилища.

Код выхода анализа: `0` — PASS или NO_POLICY, `2` — FAIL,
`3` — NO_VERDICT или DEGRADED, `4` — некорректный вход,
`5` — некорректная политика.
При FAIL отчёты всё равно создаются. Сгенерированные `data/` и `out/`
не включайте в коммит с примером.
