# Подготовка opt-in OpenSearch correlation

Команда работает только с уже сохранёнными файлами и не выполняет внешние запросы:

```text
ltv opensearch prepare \
  --context opensearch-errors.json \
  --templates opensearch-correlation-templates.json \
  --load-sha256 <sha256 исходного load artifact> \
  --output-dir prepared-opensearch
```

`--context` можно повторить до 16 раз. Если уже есть `resource-snapshot.v1` на той же точной временной сетке, его можно передать через `--resources`. `--output-dir` должен указывать на ещё не существующий каталог.

Команда создаёт `resource-snapshot.json` и `correlation-plan.json`. Их следует явно передать следующему локальному анализу:

```text
ltv analyze results.jtl \
  --resources prepared-opensearch/resource-snapshot.json \
  --correlation prepared-opensearch/correlation-plan.json \
  --source-context opensearch-errors.json
```

Template contract приведён в `docs/contracts/diagnostics/v1/opensearch-correlation-templates.example.json`. Каждая template явно задаёт OpenSearch profile, load metric, окна, допустимый lag, минимальные эффекты, controls, topology basis и clock alignment. Сетки всех выбранных snapshots должны совпадать точно; команда не растягивает данные и не интерполирует gaps.

Подготовка и correlation выключены по умолчанию. Результат использует существующий bounded diagnostic engine, остаётся ассоциацией, не формирует root cause и не меняет deterministic verdict.
