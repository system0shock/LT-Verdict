# Подготовка нагрузочного теста к LT Verdict

## Быстрый старт одной командой из `ltv.zip` (<= 5 минут)

После распаковки `ltv-<версия>.zip` клон репозитория не нужен — встроенный набор `examples/quickstart/` и стартовые профили источников уже включены в архив. Выполните одну команду из каталога с распакованным `ltv-<версия>`:

```powershell
.\ltv-<версия>\bin\ltv.bat quickstart --out-dir .\quickstart-out --data-dir .\quickstart-data
```

В Linux:

```bash
./ltv-<версия>/bin/ltv quickstart --out-dir ./quickstart-out --data-dir ./quickstart-data
```

Команда за несколько секунд:

- импортирует встроенный JTL (`POST /checkout` с нарушением `p95` и долей ошибок HTTP 500/503 в окне `steady`, а также проходящий `GET /catalog`) и ресурсный снимок (`checkout-api` с нарушением CPU SLA в `steady` и проходящий `catalog-svc`);
- записывает в `--out-dir` пять артефактов (`result.json`, `report.html` с таблицей инцидентов и группами ошибок, `chart.svg`, `summary.txt`, `junit.xml`);
- печатает пути к `report.html` и команду `ltv ui --data-dir ...` для просмотра результатов в веб-интерфейсе.

Стартовые профили источников внутри распакованного архива:

- OpenShift (PromQL): `docs/contracts/sources/v1/platform-openshift-connections.example.json` (`peak` и `autostep` варианты рядом);
- JVM Micrometer в OpenShift и standalone Prometheus (`job`): `docs/contracts/sources/v1/platform-jvm-connections.example.json`, `docs/contracts/sources/v1/platform-jvm-standalone-connections.example.json`;
- PostgreSQL (Prometheus `postgres_exporter` PromQL и JDBC `pre`/`post`): `docs/contracts/sources/v1/platform-postgresql-promql-connections.example.json`, `docs/contracts/sources/v1/postgresql-connections.example.json`.

Проверьте любой профиль без сети:

```powershell
.\ltv-<версия>\bin\ltv.bat source validate --connections .\ltv-<версия>\docs\contracts\sources\v1\platform-postgresql-promql-connections.example.json
```

Подробнее о генерации и настройке профилей — в [руководстве по пакетам метрик](platform-metric-packs.md) и [онлайн-источникам](online-sources.md).

## Подготовка тестового репозитория (`tools/onboard_test.py`)

Поставляемый skill: `skills/lt-verdict-onboard-test/SKILL.md`. Глобальная установка не выполняется. Wrapper работает локально, без модели и без исполнения кода тестового репозитория.

## Подготовка

Из каталога LT Verdict укажите подтверждённые пути относительно корня тестового Git-репозитория и новый output-каталог вне него:

```powershell
python tools/onboard_test.py prepare --repo C:/tests/shop --output C:/review/shop-01 --path Jenkinsfile --path load/shop.jmx --scenario checkout --stand test --artifact-path results/run.jtl
```

Результат: `audit.json`, отфильтрованный `view/`, `proposal.json`. Wrapper учитывает `.gitignore` и `.ltverdictignore`, запрещает ссылки и sensitive paths, исключает текст с подозрением на секрет. Этот ограниченный scan не является гарантией отсутствия всех секретов: содержимое view нужно проверить перед разрешением внешней передачи. Никакой передачи автоматически нет.

Proposal добавляет только новый `ltv-run.yaml` в JSON-compatible YAML. Это onboarding metadata, а не canonical run manifest. Существующий manifest не перезаписывается, код JMeter/Gatling не меняется. Предложение доступно для обычного просмотра; missing metadata остаются отсутствующими.

## Применение

После просмотра и отдельного подтверждения конкретного содержимого:

```powershell
Get-FileHash C:/review/shop-01/proposal.json -Algorithm SHA256
python tools/onboard_test.py apply --repo C:/tests/shop --proposal C:/review/shop-01/proposal.json --sha256 <approved-sha256> --confirm
```

При изменении revision или исходных файлов применение отклоняется. Нельзя обходить отказ сменой хеша без повторного просмотра. Wrapper не запускает build, hooks, Git commit или сетевые запросы.

Автоматический AI audit/patch требует отдельного host runtime с read-only mount, allowlisted tools и сетевой изоляцией. Сам SKILL.md и file attributes этого не обеспечивают. Такой запуск в этой поставке не подключён; audit/patch подготовка остаётся локальной. Проверки: `python -m unittest tools.test_onboard_test -v`.
