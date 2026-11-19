- Стадии нагрузки вместе со снимком ресурсов и онлайн-источником (ADR 0037):
  `ltv analyze --stages` теперь сочетается с `--resources` (снимок без `windows`) и с
  `--source`, а задание анализа API принимает часть `stages` вместе с
  `resource_snapshot` без окон и с `source_request`. Окна оценки задают только стадии
  `steady`: бизнес-правила считаются по точным границам стадии, SLA-правила снимка и
  платформенные правила по полным ячейкам сетки снимка внутри стадии. В результате
  `resource_binding.mode = declared_stages`, у стадий `steady` в `stage_binding`
  появились необязательные `resource_from_epoch_ms`, `resource_to_epoch_ms` и
  `resource_partial`. Онлайн-запрос со стадиями строит снимок без окна `full`.
- Новые коды: `STAGE_WINDOW_NO_FULL_CELLS` (в стадии `steady` нет ни одной полной ячейки
  сетки), `STAGES_CORRELATION_CONFLICT`, `STAGES_TREND_CONFLICT`; новая причина
  покрытия `STAGE_WINDOW_SNAPSHOT_PARTIAL` (сетка покрывает стадию не целиком: оценка по
  покрытой части, покрытие `INCOMPLETE`, вердикт не меняется; HTML-отчёт и UI называют
  её словами, отчёт добавляет имена стадий). `STAGES_RESOURCES_CONFLICT` теперь только
  для снимка с непустым `windows`; `STAGES_SOURCE_CONFLICT` больше не выдаётся.
- Смена кода для прежнего отказа: `--stages` с `--correlation` или `--trend` **без
  снимка** раньше давал `DIAGNOSTIC_RESOURCE_REQUIRED` или `TREND_RESOURCE_REQUIRED`,
  теперь `STAGES_CORRELATION_CONFLICT` или `STAGES_TREND_CONFLICT` (CLI: выход 4, как
  раньше; API: `422 INVALID_STAGES` вместо `422 INVALID_DIAGNOSTICS` или
  `422 INVALID_TREND_PLAN`). Проверки со стадиями идут в одном порядке в ядре, CLI и API:
  план ёмкости, снимок с окнами, план корреляций, план тренда (с онлайн-запросом до
  обращения к источнику); со `--stages` CLI читает файл снимка раньше политики, поэтому
  ошибка снимка выводится раньше ошибки политики. Скрипты, ждавшие
  `STAGES_RESOURCES_CONFLICT` на любой снимок или `STAGES_SOURCE_CONFLICT`, теперь
  получают анализ.
- Не меняется: анализы без стадий, со стадиями без снимка и со снимком без стадий
  (вердикт, `analysis_id`, `identity.json`, `analysis-result.json`, отчёты); схемы
  `analysis-result.v1`, `resource-snapshot.v1`, `load-stages.v1`, `source-request.*`.
  Ограничения: стадии не сочетаются с планом ёмкости, корреляций и тренда и со снимком с
  собственными окнами (в том числе сохранённым снимком явного онлайн-окна); покрытие
  стадии окном онлайн-запроса проверяется только после выборки; у каждого края стадии
  до одного шага снимка ресурсными правилами не проверяется.
