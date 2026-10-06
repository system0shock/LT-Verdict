- Провенанс совета ИИ-разбора (срезы B2-B4 ADR 0021): новое поле
  `provenance.provider_requests`, число запросов, которые relay переслал
  провайдеру ради совета (1, либо 2 после повтора при ошибке схемы). У нового
  совета оно записывается всегда, у сохранённого ранее его нет и это означает
  один запрос. Метка `provenance.prompt_version` принимает `advisory-system.v1` и
  `advisory-system.v2`; `v2` жёстко связана с файлом
  `docs/contracts/advice/v1/system-prompt-v2.md` (закреплённый SHA-256 по байтам
  LF), требует `provider_requests` и `endpoint_host`, а неизвестная метка или
  другой хэш читаются как `CORRUPT_AI_ADVICE`. `prompt_sha256` нового совета берётся
  из результата launcher (хэш снимка prompt, смонтированного в контейнер), а не
  из повторного чтения файла. Контракт `ai-advice.v1` расширен аддитивно
  (`docs/contracts/advice/v1/ai-advice.schema.json`, примеры в `examples/ai-advice`).
  Метка по умолчанию остаётся `advisory-system.v1`: prompt v2 в продукте пока не
  запускается (ждёт холдаута, ADR 0021, Д7), сохранённые советы читаются без
  запроса к модели.
