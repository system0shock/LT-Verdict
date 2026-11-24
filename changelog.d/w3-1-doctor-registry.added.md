- Добавлены команда `ltv doctor` (схема `doctor-report.v1`, офлайн по
  умолчанию и последовательные запросы с `--online` в пределах
  `max_requests_per_run`), атомарная перезагрузка профилей через
  `SourceRegistry` (схема `source-reload.v1`) и маршруты локального API
  `POST /api/sources/reload`, `POST /api/sources/validate`,
  `POST /api/sources/probe` (только режим `profile_query` по сохранённому
  `query_id`) и `POST /api/resources/semantic-hash`, а также кнопки «Перечитать
  профили», «Проверить профиль» и «Пробный запрос» на вкладке «Новый анализ»
  без перезапуска `ltv ui`. Секреты, адреса, имена переменных окружения и сырые
  ответы источника наружу не передаются; прежний интерфейс (`?shell=old`),
  `GET /api/sources` и контракты анализа не менялись.
