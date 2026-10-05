- Provenance совета ИИ-разбора (срез CM3 ADR 0023): новое поле
  `provenance.endpoint_host`, host и порт, на которые relay фактически отправил
  evidence (`host:port`, строчные буквы; значение сообщает relay, а не файл
  конфигурации); `provenance.model_id` проверяется шаблоном слага вместо
  равенства одной модели, поэтому смена файла конфигурации не делает прежние
  советы нечитаемыми. У нового совета `endpoint_host` обязателен, у сохранённого
  ранее (модель `deepseek-v4-flash-0731`, метка `advisory-system.v1`) его нет, и
  такой совет читается как раньше. `ai-advice.v1` расширен аддитивно
  (`docs/contracts/advice/v1/ai-advice.schema.json`, примеры в `examples/ai-advice`),
  relay пишет `upstream_host` в `relay-result.json`, launcher отдаёт
  `endpoint_host` в `runtime-result.json`. Ограничения: интерфейс поле пока не
  показывает (CM5); до среза выбора модели (CM4) в нём всегда встроенный
  ModelStudio; API запуска и статус задания не менялись.
