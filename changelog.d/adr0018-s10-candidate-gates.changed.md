- Статистический автовыбор baseline (ADR 0018, срез S10) принимает в кандидаты
  только анализы с политикой и блоком `verdict_gates` в identity: режим выборки
  должен быть известен. Анализ, сохранённый до появления блока (или с
  `policy_sha256`, равным `NO_POLICY`), отклоняется кодом
  `BASELINE_CANDIDATE_GATES_UNKNOWN` (HTTP 422) в ответе `POST /api/baseline`,
  после проверок `INVALID`, `INCOMPLETE` и `NOT_PASS`, до повторного анализа с
  политикой. Ручной выбор baseline, ключ сопоставимости и `analysis_id` не
  меняются.
