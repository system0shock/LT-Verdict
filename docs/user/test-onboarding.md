# Подготовка нагрузочного теста к LT Verdict

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
