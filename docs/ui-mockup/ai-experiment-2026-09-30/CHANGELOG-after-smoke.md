# Правки после смоука

Смоук 2026-09-30: 2 живых запроса (S0, K04-1 и K02-1), ledger total=2. Канал работает, `tool_calls` с `structured_output` разбирается,
`usage` (prompt/completion/total, reasoning_tokens, cached_tokens) и `model` присутствуют в ответе. Правок кода, парсера и формата запроса после смоука не потребовалось.
Наблюдение (не правка): K04-1 сослался на `analysis-result.json#/findings` (массив целиком), что продуктовый валидатор отверг бы как UNKNOWN_EVIDENCE_REFERENCE;
K02-1 воспроизвёл дефект P-A (knee). Ответы смоука в анализ не входят (`runs_smoke/`).
