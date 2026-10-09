- Сравнение с baseline в CLI и отчётах (W2.3): флаг `--baseline <analysis-result.json>` у
  `ltv analyze` (вместе с `--out-dir`), `ltv report --format html|asciidoc|confluence` и
  `ltv summary`. Эталон это сохранённый анализ из того же `--data-dir`. Отчёты HTML, AsciiDoc и
  Confluence получают раздел «Изменения относительно baseline», `summary.txt` блок строк `baseline:`,
  `cli-summary.v1` необязательный ключ `baseline_comparison`. При `stage_binding` (ADR 0030, R7)
  дельты считаются по `window_metric_summary` окон `steady`, а не по метрикам «весь прогон»; если
  стадии или условия обработки у анализов разные, раздел пишет причину несопоставимости. В CLI нет
  подтверждения условий: сравнение всегда не подтверждено, статус «материальная дельта, значимость
  не оценена» не возникает, дельты описательные. Эталон без политики допустим. Код выхода `analyze`,
  stdout, `result.json`, `junit.xml`, `analysis_id` и вердикт не меняются; без `--baseline` все
  артефакты прежние побайтово. Ошибка эталона (`BASELINE_NOT_FOUND`, `BASELINE_CORRUPT`,
  `BASELINE_TOO_LARGE`, `BASELINE_OUT_DIR_REQUIRED`) даёт код `4`. Эталон с другой машины в CLI
  не поддерживается: сначала нужен анализ в том же каталоге данных.
