- CLI: `ltv analyze --out-dir <dir>` одним вызовом записывает `result.json`,
  `report.html`, `chart.svg`, `summary.txt` и `junit.xml` (случай `gate` JUnit
  совпадает с кодом выхода, далее по случаю на правило). `ltv analyze` печатает
  `analysis_id=<id> run_id=<id>` в stderr, команда `ltv summary <run-id>
  <analysis-id>` выводит компактный JSON
  `cli-summary.v1` с общими метриками, транзакциями, перцентилями и проверками
  правил. `--policy -` и `ltv policy validate -` читают policy из stdin,
  `ltv --help` и `ltv --version` печатают справку и версию сборки (версия
  по умолчанию `0.1.0-SNAPSHOT`, релизная задаётся `-PltvVersion=<x.y.z>`).
  Схемы `analysis-result.v1`, identity, API и хранилище не менялись.
