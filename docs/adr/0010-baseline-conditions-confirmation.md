# ADR 0010 — подтверждение условий ручной baseline-пары

**Дата:** 2026-09-06

**Статус:** Accepted — пользователь разрешил реализацию `BASELINE-CONDITIONS-01`.
Частично отменён [ADR 0017](0017-baseline-candidates-and-confirmation.md): ограничение
«только для active manual baseline» снято, endpoints условий работают и для statistical
baseline (binding по выбранному победителю), код `BASELINE_MANUAL_REQUIRED` не
возвращается.
Частично отменён [ADR 0019](0019-release-history-and-baseline-eligibility.md): `DELETE /api/baseline`
удаляет не все записи условий, а только относящиеся к удаляемому baseline и не используемые
другими активными baseline.

## Контекст

ADR 0004 отделяет выбор baseline от подтверждения сопоставимости условий.
Сейчас manual comparison всегда остаётся `UNCONFIRMED`, а подтверждение
statistical candidate set нельзя использовать как подтверждение произвольной
пары из двух analyses. Нужен локальный явный выбор, который переживает reload
и не переносится на другую пару или другие окна.

## Решение

Хранить каждое решение отдельным private-файлом
`<data>/baseline-conditions/<binding-sha256>.json`. Binding состоит только из
точных `baseline` и `current` ссылок (`run_id + analysis_id`) и либо `null`,
либо пары `baseline_window + current_window`. SHA-256 вычисляется сервером от
canonical JSON binding; имя файла не принимается из HTTP.

Запись `local-baseline-conditions.v1` содержит решение `CONFIRMED`,
`NOT_CONFIRMED` или `UNKNOWN`, фактический server-side `updated_at` и
`provenance: EXPLICIT_LOCAL_ACTION`. Пользовательская/auth identity не
изобретается. Новый POST разрешён только для active manual baseline.

Lookup всегда получает ожидаемый binding и возвращает запись только при его
точном совпадении. Замена active baseline не удаляет записи: они безопасно
недоступны для другого binding и снова применимы при явном возврате к той же
immutable паре. `DELETE /api/baseline` является явной очисткой и удаляет также
все condition records.

`CONFIRMED` передаётся существующему `compareAnalyses` как `true`,
`NOT_CONFIRMED` как `false`, `UNKNOWN` как `null`. Этот nullable mapping
используется только для manual path; statistical default ADR 0004 не получает
возможность превратить сохранённый `UNKNOWN` в подтверждение. Числа, формулы,
materiality thresholds, SLA и immutable analysis result не меняются.

## Private API

```text
GET  /api/runs/{runId}/analyses/{analysisId}/baseline-conditions
POST /api/runs/{runId}/analyses/{analysisId}/baseline-conditions
query: none OR baseline_window=<id>&current_window=<id>
POST body: {"decision":"CONFIRMED"|"NOT_CONFIRMED"|"UNKNOWN"}
response: {"conditions":ConditionRecord|null}
```

Comparison response получает `conditions: ConditionRecord|null`. Отсутствие
записи и сохранённый `UNKNOWN` различимы по этому полю, хотя оба оставляют
manual comparison `UNCONFIRMED`.

## Последствия и границы

Нет нового registry/service, зависимости, authenticated audit actor или
истории изменений. Много записей допустимо только по числу реально созданных
binding; повторный POST атомарно заменяет тот же keyed record. Body сохраняет
существующие 16 KiB/depth 8 ограничения, record ограничен 4 KiB, window id —
128 UTF-8 bytes без control characters. Symlink/special paths запрещены.

Автоматическое доказательство одинаковых условий, causal inference, Jenkins,
экспорт comparison и влияние на verdict остаются вне решения.
