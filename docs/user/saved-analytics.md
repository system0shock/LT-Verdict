# Saved-run analytics

Панель **Saved-run analytics** читает только сохранённые локальные RunBundles. Она не повторяет запросы к VictoriaMetrics, Prometheus, OpenSearch или другим внешним источникам.

N-run dynamics по умолчанию показывает 10 последних найденных analyses с точным comparability key; допустимый предел — 1–100 строк. Локальный history scan ограничен 1000 analyses, 4096 directory entries и 16 MiB проверенных metadata. Если scan остановлен по любому из этих bounds, результат может пропустить сопоставимые прогоны, а latest-N относится только к просмотренной части истории. Для history проверяются canonical manifest и hashes сохранённых `run.json`, result и identity; выбранные current/baseline проходят проверку RunBundle по manifest, путям и размерам файлов, без пересчёта SHA-256 входа и артефактов (см. «Целостность хранилища» в руководстве по локальному анализу).

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
