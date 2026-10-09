- Ядро окна устойчивого состояния (W2.5, PR A, [ADR 0030](docs/adr/0030-load-stages-steady-window.md)):
  новый вход `load-stages.v1` (схема и примеры в `docs/contracts/stages/v1`) объявляет стадии прогона
  смещениями от его начала; стадии с ролью `steady` становятся окнами и оцениваются существующей
  оконной оценкой политики без снимка ресурсов. В результат добавляются свидетельства `stage_binding`
  (границы окон в epoch, `evaluated_millis`, `excluded_millis`, `clipped_to_run_end`) и
  `window_metric_summary` на окно; метрики «весь прогон» остаются. Конец `steady` за концом прогона
  обрезается, `steady`, начинающаяся в конце прогона или позже, даёт `STAGE_OUTSIDE_RUN`;
  платформенные SLA-правила со стадиями дают `NO_VERDICT` с причиной `RESOURCE_SNAPSHOT_REQUIRED`.
  Сочетание стадий со снимком ресурсов, планом ёмкости или онлайн-запросом источника отвергается
  (`STAGES_RESOURCES_CONFLICT`, `STAGES_CAPACITY_CONFLICT`, `STAGES_SOURCE_CONFLICT`). Хэш объявления
  входит в identity (`load_stages_sha256`, `load_stages_version`, `input_versions.stages`, модули и
  лимиты стадий) и в ключ сопоставимости только при стадиях, файл `load-stages.json` лежит в каталоге
  анализа. Прогон без стадий не меняется: `identity.json`, `analysis_id`, `analysis-result.json`
  и ключ сопоставимости прежние. Флага CLI `--stages`, части API `stages`, подписей отчётов, junit,
  сводок и UI в этом срезе нет (PR B и PR C).
