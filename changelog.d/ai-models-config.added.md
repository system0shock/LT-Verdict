- Файл конфигурации моделей ИИ-разбора `ai-models.v1` (срез CM2 ADR 0023):
  переменная `LT_VERDICT_AI_MODELS_FILE` задаёт модели, их подписи, модель по
  умолчанию и подпись назначения; без переменной действует встроенная
  конфигурация (ModelStudio, `deepseek-v4-flash-0731`), как раньше. `GET
  /api/bootstrap` получил поле `advisory_ai` (подписи моделей, `measured`,
  `endpoint_label`; адрес endpoint не отдаётся). Недопустимый файл не
  останавливает запуск: ИИ-разбор становится недоступен с причиной
  `MODEL_CONFIG_INVALID`, а код ошибки и указатель на поле пишутся в stderr.
  Контракт `docs/contracts/advice/v1/ai-models.schema.json`, описание в
  `docs/user/advisory-ai.md`. Ограничение: пока запуск не научен выбирать
  модель (срез CM4), файл с другим endpoint или слагами отвергается
  (`NOT_YET_SUPPORTED`); запрос на анализ, интерфейс и `ai-advice.v1` не менялись.
