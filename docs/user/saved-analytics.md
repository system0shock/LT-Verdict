# Saved-run analytics

Панель **Saved-run analytics** читает только сохранённые локальные RunBundles. Она не повторяет запросы к VictoriaMetrics, Prometheus, OpenSearch или другим внешним источникам.

N-run dynamics по умолчанию показывает 10 последних найденных analyses с точным comparability key; допустимый предел — 1–100 строк. Локальный history scan ограничен 1000 analyses, 4096 directory entries и 16 MiB проверенных metadata. Если scan остановлен по любому из этих bounds, результат может пропустить сопоставимые прогоны, а latest-N относится только к просмотренной части истории. Для history проверяются canonical manifest и hashes сохранённых `run.json`, result и identity; выбранные current/baseline проходят проверку RunBundle по manifest, путям и размерам файлов, без пересчёта SHA-256 входа и артефактов, кроме identity (см. «Целостность хранилища» в руководстве по локальному анализу).

Если анализ входит в запись релиза ([ADR 0019](../adr/0019-release-history-and-baseline-eligibility.md), `local-release.v1`), его строка динамики получает `application_version` (метка релиза, `label`) и `load_profile` (краткое представление заявленного профиля: заполненные из шести полей `scenario_mix`, `environment_dataset`, `load_model`, `targets_stages`, `pacing`, `generator_limits` в этом порядке, в виде `поле=значение` через точку с запятой, например `load_model=open; pacing=10 s`). Для анализа вне релиза, для релиза без профиля (поле `load_profile`), для анализа, который назван в нескольких записях релизов (например, запись скопирована вручную), и при нечитаемом реестре релизов значения равны `null`: динамика при этом строится как раньше. `jenkins_build` по-прежнему не заполняется. Отбор строк по ключу сопоставимости от записей релизов не зависит. Метка и поля профиля выводятся в экспорты HTML, AsciiDoc и Confluence через то же экранирование, что и остальные ячейки; заметки релиза (`notes`) в ответ и экспорты не попадают никогда.

В **Select runs shown and exported** можно скрыть строки. Скрытые строки не входят в JSON, HTML, AsciiDoc и Confluence export. Уже рассчитанные deltas по-прежнему относятся к исходному предыдущему сопоставимому прогону и после скрытия строки не пересчитываются. Выбор кодируется в GET query повторяющимся `exclude`; сервер принимает до 100 значений длиной до 256 символов. При большом числе длинных identifiers запрос также ограничен допустимой длиной URL HTTP-сервера, поэтому обычный default `N = 10` является основным интерактивным путём.

Transaction comparison использует полный scope `group_path + label + sample_kind`, server-side filter до 256 UTF-8 bytes и предел 1–200 строк. При усечении панель показывает предупреждение. Несовместимые определения метрик и отсутствующие значения отображаются как `N/A` с причиной.

JVM/OpenShift pack summary перечисляет только capabilities, реально представленные в сохранённых resource summaries, и ссылки на существующие explicit-threshold findings. Отсутствующие metrics дают `DEGRADED` или `SKIPPED`, а не здоровый результат. Manual baseline resource deltas остаются в существующем baseline/window comparison.

OpenSearch series и markers отображаются отдельно по profile. Markers также накладываются на общую относительную ось load charts; временное совпадение не трактуется как причина. Opt-in correlation сначала готовится offline:

```text
ltv opensearch prepare \
  --context opensearch-errors.json \
  --templates opensearch-correlation-templates.json \
  --load-sha256 <sha256 исходного load artifact> \
  --output-dir prepared-opensearch
```

Команда принимает до 16 contexts, каждый до 16 MiB и вместе до 32 MiB. Template file ограничен 1 MiB. Опциональный `--resources` объединяется только при точном совпадении grid; interpolation и wall-clock stretching отсутствуют. Подробный контракт и следующий `ltv analyze` вызов описаны в `docs/user/opensearch-correlation-preparation.md`.
